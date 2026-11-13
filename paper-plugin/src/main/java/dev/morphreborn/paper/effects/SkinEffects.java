package dev.morphreborn.paper.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.disguise.BorrowedHeads;
import dev.morphreborn.paper.morph.ActiveMorph;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The mod's morph-skin animations for players without the mod whose game has RE:Morph's resource pack: a
 * transformation as the old body's boxes melting into the new body's, an absorbed creature covered in the skin and pulled
 * in by tendrils, and Devouring Frenzy's tendrils. Everything is drawn with item displays showing the pack's morph-skin
 * box and tendril models; the viewer's game moves them smoothly between the poses the server sets (a display's own
 * interpolation), so each step is one update for each viewer. Players without the pack keep the particles of
 * {@link VanillaEffects}; players with the mod draw these themselves.
 * <p>
 * The boxes come from {@code morph-boxes.json} ({@code tools/gen_head_pack.py}, from the client's model dump): each
 * model's cubes, posed as the mod captures them, facing +z, in blocks from the feet.
 */
public final class SkinEffects implements Listener {
	/** One box of a model: its middle, its size along its own axes, and its turn. */
	record Box(Vector3f centre, Vector3f size, Quaternionf turn) {
		Box scaled(float by) {
			return new Box(new Vector3f(centre).mul(by), new Vector3f(size).mul(by), turn);
		}

		/** A box of no size where {@code other} would grow from or shrink into: the model's middle. */
		static Box empty(float midY, Box other) {
			return new Box(new Vector3f(0, midY, 0), new Vector3f(), other.turn);
		}

		static Box lerp(Box a, Box b, float t) {
			return new Box(new Vector3f(a.centre).lerp(b.centre, t), new Vector3f(a.size).lerp(b.size, t), new Quaternionf(a.turn).slerp(b.turn, t));
		}
	}

	private record Model(float height, List<Box> boxes) {}

	/** Blocks the skin stands out from the body it covers on every side, so the body's own faces don't show through. */
	private static final float COVER = 0.015F;
	/** Ticks a display glides to where its body moved (as the borrowed heads do). */
	private static final int GLIDE = 3;
	/** Segments of a Devouring Frenzy tendril, and ticks it takes to reach its creature or draw back (the mod's). */
	private static final int TETHER_SEGMENTS = 5, TETHER_GROW = 6;
	private static final double VIEW_RANGE_SQ = 64 * 64;
	private static final NamespacedKey MARKER = MorphRebornPlugin.key("skin_effect");

	private final MorphRebornPlugin plugin;
	private final Map<String, Model> models = new HashMap<>();
	/** Who sees a display: everyone who draws the skin but {@code hideFrom}, or only {@code onlyFor}. */
	private record Audience(@Nullable UUID hideFrom, @Nullable UUID onlyFor) {
		boolean includes(Player viewer) {
			UUID id = viewer.getUniqueId();
			return onlyFor != null ? onlyFor.equals(id) : !id.equals(hideFrom);
		}
	}

	/** Every display alive, and who sees it. */
	private final Map<ItemDisplay, Audience> live = new HashMap<>();
	private final Map<UUID, Melt> melts = new HashMap<>();
	private final List<Absorb> absorbs = new ArrayList<>();
	/** Raging players, to their tendrils by creature. */
	private final Map<UUID, Map<UUID, Tether>> tethers = new HashMap<>();
	private long ticks;

	public SkinEffects(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		load();
	}

	private void load() {
		try (InputStream in = plugin.getResource("morph-boxes.json")) {
			if (in == null) return;
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				JsonObject m = e.getValue().getAsJsonObject();
				List<Box> boxes = new ArrayList<>();
				for (JsonElement be : m.getAsJsonArray("boxes")) {
					JsonArray b = be.getAsJsonArray();
					float[] v = new float[10];
					for (int i = 0; i < 10; i++) v[i] = b.get(i).getAsFloat();
					boxes.add(new Box(new Vector3f(v[0], v[1], v[2]), new Vector3f(v[3], v[4], v[5]), new Quaternionf(v[6], v[7], v[8], v[9]).normalize()));
				}
				models.put(e.getKey(), new Model(m.get("height").getAsFloat(), List.copyOf(boxes)));
			}
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not read morph-boxes.json; players without the mod see particles for transformations.", e);
		}
	}

	/** On, and RE:Morph's pack models are shown at all. */
	private boolean enabled() {
		return plugin.settings().vanillaSkinEffects && !plugin.settings().multimorphVanillaHeads.equals("off") && !models.isEmpty();
	}

	/** Whether {@code viewer} is drawn these (no mod, the pack): the rest get particles. */
	public boolean drawsFor(Player viewer) {
		return enabled() && plugin.borrowedHeads().seesPack(viewer);
	}

	// ------------------------------------------------------------------ displays

	private static ItemStack skin(String model) {
		ItemStack item = new ItemStack(Material.PAPER);
		ItemMeta meta = item.getItemMeta();
		meta.setItemModel(new NamespacedKey(MorphRebornPlugin.NAMESPACE, "skin/" + model));
		item.setItemMeta(meta);
		return item;
	}

	/** A display of the skin model {@code model}, shown only to the players {@link #drawsFor} (never {@code hideFrom}). */
	private ItemDisplay spawn(Location at, String model, Transformation t, int glide, @Nullable Player hideFrom) {
		return spawnFor(at, model, t, glide, new Audience(hideFrom == null ? null : hideFrom.getUniqueId(), null));
	}

	private ItemDisplay spawnFor(Location at, String model, Transformation t, int glide, Audience audience) {
		ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
			d.setPersistent(false);
			d.setVisibleByDefault(false);
			d.getPersistentDataContainer().set(MARKER, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
			d.setItemStack(skin(model));
			d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
			d.setBillboard(Display.Billboard.FIXED);
			d.setTeleportDuration(glide);
			d.setTransformation(t);
		});
		live.put(display, audience);
		for (Player v : at.getWorld().getPlayers()) {
			if (audience.includes(v) && v.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ && drawsFor(v)) v.showEntity(plugin, display);
		}
		return display;
	}

	/** Move a display's model to {@code t} over {@code ticks}, smoothly in the viewers' games. */
	private static void moveTo(ItemDisplay d, Transformation t, int ticks) {
		if (!d.isValid()) return;
		d.setInterpolationDelay(0);
		d.setInterpolationDuration(Math.max(1, ticks));
		d.setTransformation(t);
	}

	private void remove(ItemDisplay d) {
		live.remove(d);
		if (d.isValid()) d.remove();
	}

	/** A box of a model as a display's transformation (the box model is a block, centred on the display). */
	private static Transformation boxAt(Box b) {
		Vector3f size = b.size.lengthSquared() < 1e-8F ? new Vector3f() : new Vector3f(b.size).add(COVER * 2, COVER * 2, COVER * 2);
		return new Transformation(new Vector3f(b.centre), new Quaternionf(b.turn), size, new Quaternionf());
	}

	/** A tendril segment from {@code from} to {@code to} (blocks from the display), {@code width} blocks thick. */
	private static Transformation segment(Vector3f from, Vector3f to, float width) {
		Vector3f d = new Vector3f(to).sub(from);
		float len = d.length();
		Quaternionf turn = len < 1e-5F ? new Quaternionf() : new Quaternionf().rotationTo(0F, 0F, 1F, d.x / len, d.y / len, d.z / len);
		return new Transformation(new Vector3f(from), turn, new Vector3f(width, width, len), new Quaternionf());
	}

	/** A viewer's client mod finished its handshake, its pack loaded, or it joined: show or hide every display for it. */
	public void refreshViewer(Player viewer) {
		for (Map.Entry<ItemDisplay, Audience> e : live.entrySet()) {
			ItemDisplay d = e.getKey();
			if (!d.isValid()) continue;
			if (drawsFor(viewer) && e.getValue().includes(viewer)) viewer.showEntity(plugin, d);
			else viewer.hideEntity(plugin, d);
		}
	}

	public void removeAll() {
		for (ItemDisplay d : List.copyOf(live.keySet())) remove(d);
		melts.clear();
		absorbs.clear();
		tethers.clear();
		legs.clear();
	}

	// ------------------------------------------------------------------ models

	private @Nullable Model model(Player owner, MorphRef form) {
		if (form.isPlayer() || form.isSelf()) return models.get("minecraft:player");
		// a baby's own boxes (26.3's babies have models of their own), where there are any
		if (plugin.templates().get(form, owner.getWorld()) instanceof org.bukkit.entity.Ageable a && !a.isAdult()) {
			Model baby = models.get(form.entityType() + "_baby");
			if (baby != null) return baby;
		}
		return models.get(form.entityType());
	}

	/** How big {@code form} is drawn against the size its boxes were taken at (a baby, a big slime). */
	private float sizeOf(Player owner, MorphRef form, Model model) {
		if (form.isPlayer() || form.isSelf()) return 1F;
		LivingEntity template = plugin.templates().get(form, owner.getWorld());
		return template == null || model.height <= 0 ? 1F : (float) (template.getHeight() / model.height);
	}

	// ------------------------------------------------------------------ transformations

	/** A transformation's boxes, from the old body's to the new body's. */
	private final class Melt {
		final MorphRef from, to;
		final List<ItemDisplay> displays = new ArrayList<>();
		final List<Box> start = new ArrayList<>(), end = new ArrayList<>();
		/** The skin is off (the mod fades it off the new body over the last eighth; this takes it off half way through). */
		boolean done;

		Melt(MorphRef from, MorphRef to) {
			this.from = from;
			this.to = to;
		}
	}

	/**
	 * One tick of {@code player}'s transformation, as the mod draws it ({@code MorphWorldRenderer.submitTransition}): the
	 * old body covered in the skin, its boxes melting into the new body's from 12.5% to 87.5% of the way (eased, in three
	 * strokes), the skin taken off at the end. Returns whether it is shown (false: no boxes for one of the bodies, or the
	 * effect is off), when players with the pack need the particles instead.
	 */
	public boolean transitionTick(Player player, ActiveMorph s) {
		if (!enabled() || s.prev == null || s.next == null) {
			endTransition(player);
			return false;
		}
		MorphRef from = s.prev, to = s.look();
		Melt melt = melts.get(player.getUniqueId());
		if (melt != null && (!melt.from.equals(from) || !melt.to.equals(to) || s.time <= 1)) {
			endTransition(player);
			melt = null;
		}
		if (melt == null) {
			melt = startMelt(player, from, to);
			if (melt == null) return false;
			melts.put(player.getUniqueId(), melt);
		}
		if (melt.done) return true;
		if (s.time >= Math.round(s.duration * 0.94F)) {
			for (ItemDisplay d : melt.displays) remove(d);
			melt.done = true;
			return true;
		}
		Location at = player.getLocation();
		at.setYaw(BorrowedHeads.drawnYaw(player, to));
		at.setPitch(0);
		for (ItemDisplay d : melt.displays) {
			if (d.isValid() && d.getWorld() == at.getWorld()) d.teleport(at);
		}
		int first = Math.max(2, Math.round(s.duration * 0.125F)), stroke = Math.max(1, Math.round(s.duration * 0.25F));
		if (s.time >= first && (s.time - first) % stroke == 0 && (s.time - first) / stroke < 3) {
			float t = ActiveMorph.sineify(((s.time - first) / stroke + 1) / 3F);
			for (int i = 0; i < melt.displays.size(); i++) moveTo(melt.displays.get(i), boxAt(Box.lerp(melt.start.get(i), melt.end.get(i), t)), stroke);
		}
		return true;
	}

	private @Nullable Melt startMelt(Player player, MorphRef from, MorphRef to) {
		Model a = model(player, from), b = model(player, to);
		if (a == null || b == null) return null;
		Melt melt = new Melt(from, to);
		float sa = sizeOf(player, from, a), sb = sizeOf(player, to, b);
		int n = Math.max(a.boxes.size(), b.boxes.size());
		for (int i = 0; i < n; i++) {
			Box ba = i < a.boxes.size() ? a.boxes.get(i).scaled(sa) : null, bb = i < b.boxes.size() ? b.boxes.get(i).scaled(sb) : null;
			melt.start.add(ba != null ? ba : Box.empty(a.height * sa / 2F, bb));
			melt.end.add(bb != null ? bb : Box.empty(b.height * sb / 2F, ba));
		}
		Location at = player.getLocation();
		at.setYaw(BorrowedHeads.drawnYaw(player, from));
		at.setPitch(0);
		// never to the player themself: from inside it the boxes hid their view (the mod draws none around your camera)
		for (int i = 0; i < n; i++) melt.displays.add(spawn(at, "box_" + (i % 4), boxAt(melt.start.get(i)), GLIDE, player));
		return melt;
	}

	/** The transformation ended (or was cut short): its skin comes off. */
	public void endTransition(Player player) {
		Melt melt = melts.remove(player.getUniqueId());
		if (melt != null) for (ItemDisplay d : melt.displays) remove(d);
	}

	/**
	 * Whether every player without the mod who can see {@code player} draws the skin: then the disguise inside the melting
	 * boxes can be hidden altogether, instead of shrinking and growing for those who see the particles.
	 */
	public boolean onlySkinViewers(Player player) {
		if (!melts.containsKey(player.getUniqueId())) return false;
		Location at = player.getLocation();
		for (Player v : at.getWorld().getPlayers()) {
			if (plugin.channel().isModded(v) || v.getLocation().distanceSquared(at) > VIEW_RANGE_SQ) continue;
			if (!drawsFor(v)) return false;
		}
		return true;
	}

	// ------------------------------------------------------------------ absorbing

	/** An absorbed creature: tendrils reach it, the skin covers it, and everything is pulled into the player. */
	private final class Absorb {
		final Player origin;
		final LivingEntity victim;
		final boolean red;
		final List<ItemDisplay> near = new ArrayList<>(), far = new ArrayList<>(), body = new ArrayList<>();
		final List<Vector3f> bends = new ArrayList<>();
		final List<Player> hidden = new ArrayList<>();
		int age;

		Absorb(Player origin, LivingEntity victim, boolean red) {
			this.origin = origin;
			this.victim = victim;
			this.red = red;
		}

		String model(String name) {
			return (red ? "red_" : "") + name;
		}
	}

	/**
	 * Ticks: the tendrils' first half reaching out (a tick after they appear, so the viewers' games have them to move),
	 * their second half, the skin covering the creature, the pull, and the end.
	 */
	private static final int START = 2, REACH = 5, FAR = START + REACH, COVERED = FAR + REACH, PULL = COVERED + 10, PULL_TICKS = 16,
			GONE = PULL + PULL_TICKS + 1;
	/** How small the body is when it reaches the player's chest, before it goes (the mod's shrinks as it nears). */
	private static final float PULLED_SIZE = 0.2F;

	/**
	 * Someone absorbed {@code victim}, as the mod draws it ({@code AcquisitionEffect}): black tendrils for a new morph,
	 * the red ones for biomass only (the mod's defaults). Returns whether it is drawn.
	 */
	public boolean absorb(Player origin, LivingEntity victim, boolean newMorph) {
		if (!enabled() || !plugin.settings().vanillaAcquisitionEffects) return false;
		Location from = chest(origin, 0.5);
		boolean anyone = false;
		for (Player v : from.getWorld().getPlayers()) anyone |= v.getLocation().distanceSquared(from) <= VIEW_RANGE_SQ && drawsFor(v);
		if (!anyone) return true; // nobody to draw it for; nobody with the pack gets particles either
		Absorb a = new Absorb(origin, victim, !newMorph);
		Vector3f to = offset(from, chest(victim, 0.5));
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int i = 0; i < 4; i++) {
			// each tendril bends once, off the straight line, as the mod's turn towards the creature as they grow
			Vector3f side = new Vector3f(to).cross(0, 1, 0);
			if (side.lengthSquared() < 1e-4F) side.set(1, 0, 0);
			side.normalize().mul((float) r.nextGaussian() * 0.15F * Math.min(3F, to.length()));
			Vector3f bend = new Vector3f(to).mul(0.35F + 0.2F * r.nextFloat()).add(side).add(0, (float) r.nextGaussian() * 0.25F, 0);
			a.bends.add(bend);
			float w = (1.6F + 0.8F * r.nextFloat()) / 16F; // pixels, as thick as the mod's
			// the half nearest the player isn't drawn for them: from the chest it filled their view (the mod fades it in
			// first person)
			a.near.add(spawn(from, a.model("stick"), segment(new Vector3f(), new Vector3f(bend).mul(0.01F), w), 1, origin));
			// the far half starts at the bend; pulled in, it glides to the chest as it shrinks, so its end stays on the body
			Location mid = from.clone().add(bend.x, bend.y, bend.z);
			a.far.add(spawn(mid, a.model("stick"), segment(new Vector3f(), new Vector3f(to).sub(bend).mul(0.01F), w * 0.8F), PULL_TICKS, null));
		}
		absorbs.add(a);
		return true;
	}

	private void tickAbsorb(Absorb a) {
		a.age++;
		Location from = chest(a.origin, 0.5);
		if (a.age == START) {
			for (int i = 0; i < a.near.size(); i++) moveTo(a.near.get(i), segment(new Vector3f(), a.bends.get(i), widthOf(a.near.get(i))), REACH);
		} else if (a.age == FAR) {
			Vector3f to = offset(from, chest(a.victim, 0.5));
			for (int i = 0; i < a.far.size(); i++) {
				moveTo(a.far.get(i), segment(new Vector3f(), new Vector3f(to).sub(a.bends.get(i)), widthOf(a.far.get(i))), REACH);
			}
		} else if (a.age == COVERED) {
			// the skin over the creature, which goes from the sight of those who see the skin
			String key = a.victim instanceof Player p ? modelKey(p) : a.victim.getType().getKey().toString();
			Model m = a.victim instanceof org.bukkit.entity.Ageable young && !young.isAdult() && models.containsKey(key + "_baby") ? models.get(key + "_baby")
					: models.get(key);
			if (m != null) {
				float size = m.height <= 0 ? 1F : (float) (a.victim.getHeight() / m.height);
				Location at = a.victim.getLocation();
				at.setYaw(a.victim.getBodyYaw());
				at.setPitch(0);
				for (int i = 0; i < m.boxes.size(); i++) a.body.add(spawn(at, a.model("box_" + (i % 4)), boxAt(m.boxes.get(i).scaled(size)), PULL_TICKS, null));
				for (Player v : at.getWorld().getPlayers()) {
					// never a player (hiding one takes it off the viewer's player list)
					if (!(a.victim instanceof Player) && drawsFor(v) && v.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ) {
						v.hideEntity(plugin, a.victim);
						a.hidden.add(v);
					}
				}
			}
		} else if (a.age == PULL) {
			// everything into the player's chest, in straight lines at one pace (the far halves glide from the bends to the
			// chest as they shrink): the tendrils draw back holding the body, which turns and shrinks as it comes
			for (ItemDisplay d : a.near) moveTo(d, shrunk(d.getTransformation()), PULL_TICKS);
			for (ItemDisplay d : a.far) {
				d.teleport(from);
				moveTo(d, shrunk(d.getTransformation()), PULL_TICKS);
			}
			Location into = from.clone();
			into.setYaw(a.victim.getBodyYaw() + 150F);
			for (ItemDisplay d : a.body) {
				Transformation t = d.getTransformation();
				d.teleport(into);
				moveTo(d, new Transformation(new Vector3f(t.getTranslation()).mul(PULLED_SIZE), t.getLeftRotation(), new Vector3f(t.getScale()).mul(PULLED_SIZE),
						new Quaternionf()), PULL_TICKS);
			}
		}
	}

	private void endAbsorb(Absorb a) {
		for (ItemDisplay d : a.near) remove(d);
		for (ItemDisplay d : a.far) remove(d);
		for (ItemDisplay d : a.body) remove(d);
		for (Player v : a.hidden) {
			if (v.isOnline()) v.showEntity(plugin, a.victim); // gone by now; this only forgets it was hidden
		}
	}

	/** A tendril segment drawn all the way back to where it starts, as thick as it was. */
	private static Transformation shrunk(Transformation t) {
		return new Transformation(t.getTranslation(), t.getLeftRotation(), new Vector3f(t.getScale().x, t.getScale().y, 0F), t.getRightRotation());
	}

	private static float widthOf(ItemDisplay d) {
		return d.getTransformation().getScale().x;
	}

	private String modelKey(Player p) {
		MorphRef form = plugin.morphs().activeForm(p);
		return form == null || form.isSelf() || form.isPlayer() ? "minecraft:player" : form.entityType();
	}

	// ------------------------------------------------------------------ Devouring Frenzy

	/** A Devouring Frenzy tendril: segments from the player's chest to the creature, wavering. */
	private static final class Tether {
		/** As others see it, and as its player sees it (in front of them and below their view, thinner, as the mod's). */
		final List<ItemDisplay> segments = new ArrayList<>(), own = new ArrayList<>();
		final float phase;
		long seen;
		int grow;
		int retract = -1;

		Tether(float phase) {
			this.phase = phase;
		}
	}

	/**
	 * One step of a tendril from a raging player to a creature it drains (called every other tick, as the particles are),
	 * as the mod draws it ({@code RageTethers}): red skin on a sideways wave that is still at both ends, thinning towards
	 * the creature, grown out over its first ticks. Returns whether it is drawn.
	 */
	public boolean tether(Player origin, LivingEntity target, int tick) {
		if (!enabled()) return false;
		Map<UUID, Tether> mine = tethers.computeIfAbsent(origin.getUniqueId(), k -> new HashMap<>());
		Tether t = mine.get(target.getUniqueId());
		if (t != null && t.retract >= 0) {
			for (ItemDisplay d : t.segments) remove(d);
			for (ItemDisplay d : t.own) remove(d);
			mine.remove(target.getUniqueId());
			t = null;
		}
		Location from = chest(origin, 0.6);
		Location eyes = ownStart(origin);
		if (t == null) {
			t = new Tether(target.getEntityId() * 1.7F);
			Transformation none = segment(new Vector3f(), new Vector3f(0, 0, 0.01F), 0.1F);
			for (int i = 0; i < TETHER_SEGMENTS; i++) t.segments.add(spawn(from, "red_stick", none, 2, origin));
			if (drawsFor(origin)) {
				for (int i = 0; i < TETHER_SEGMENTS; i++) t.own.add(spawnFor(eyes, "red_stick", none, 2, new Audience(null, origin.getUniqueId())));
			}
			mine.put(target.getUniqueId(), t);
		}
		t.seen = ticks;
		t.grow = Math.min(TETHER_GROW, t.grow + 2);
		float reach = t.grow / (float) TETHER_GROW;
		place(t.segments, t.phase, from, chest(target, 0.5), reach, tick, 2.6F);
		place(t.own, t.phase, eyes, chest(target, 0.5), reach, tick, 1.3F);
		return true;
	}

	/**
	 * Where a raging player's own tendrils start for them: in front of them and below their view, as the mod's do in first
	 * person (from the chest they started under the camera and filled the screen).
	 */
	private static Location ownStart(Player p) {
		Vector look = p.getEyeLocation().getDirection().setY(0);
		if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
		Location at = p.getEyeLocation().add(look.normalize().multiply(0.5)).add(0, -0.7, 0);
		at.setYaw(0F);
		at.setPitch(0F);
		return at;
	}

	/**
	 * The segments along the wave from {@code from} to {@code to}, drawn out to {@code reach} of the way, {@code start}
	 * pixels thick at the player and thinning to the creature.
	 */
	private void place(List<ItemDisplay> segments, float phase, Location from, Location to, float reach, int tick, float start) {
		if (segments.isEmpty()) return;
		Vector3f line = offset(from, to);
		float length = line.length();
		if (length < 0.2F) return;
		Vector3f dir = new Vector3f(line).div(length);
		Vector3f side = new Vector3f(dir).cross(0F, 1F, 0F);
		if (side.lengthSquared() < 1e-4F) side.set(1F, 0F, 0F);
		side.normalize();
		Vector3f up = new Vector3f(side).cross(dir).normalize();
		float amp = Math.min(0.35F, 0.06F * length + 0.05F);
		float time = tick;
		int n = segments.size();
		Vector3f prev = new Vector3f();
		for (int i = 1; i <= n; i++) {
			float s = i / (float) n * reach;
			float wave = (float) Math.sin(s * Math.PI) * amp;
			Vector3f p = new Vector3f(dir).mul(length * s)
					.add(new Vector3f(side).mul(wave * (float) Math.sin(phase + s * 7F + time * 0.35F)))
					.add(new Vector3f(up).mul(wave * 0.6F * (float) Math.cos(phase * 0.8F + s * 5F + time * 0.28F)));
			float mid = (i - 0.5F) / n;
			float width = (start + (1.2F - start) * mid) / 16F;
			ItemDisplay d = segments.get(i - 1);
			if (d.isValid()) {
				d.teleport(from);
				moveTo(d, segment(prev, p, width), 2);
			}
			prev = p;
		}
	}

	private void tickTethers() {
		for (Iterator<Map.Entry<UUID, Map<UUID, Tether>>> it = tethers.entrySet().iterator(); it.hasNext(); ) {
			Map<UUID, Tether> mine = it.next().getValue();
			for (Iterator<Tether> ti = mine.values().iterator(); ti.hasNext(); ) {
				Tether t = ti.next();
				if (t.retract < 0 && ticks - t.seen > 3) {
					// let go (or dead): drawn back into the player
					t.retract = 0;
					for (List<ItemDisplay> set : List.of(t.segments, t.own)) {
						for (ItemDisplay d : set) {
							Transformation now = d.getTransformation();
							moveTo(d, new Transformation(new Vector3f(), now.getLeftRotation(), new Vector3f(), new Quaternionf()), TETHER_GROW);
						}
					}
				}
				if (t.retract >= 0 && ++t.retract > TETHER_GROW + 1) {
					for (ItemDisplay d : t.segments) remove(d);
					for (ItemDisplay d : t.own) remove(d);
					ti.remove();
				}
			}
			if (mine.isEmpty()) it.remove();
		}
	}

	// ------------------------------------------------------------------ the hover's legs

	/** Hovering players, to their legs' segments (hip to knee, knee to foot, four legs). */
	private final Map<UUID, List<ItemDisplay>> legs = new HashMap<>();

	/**
	 * One step of a frenzy hover's legs, in the red skin: {@code segments} are pairs of world points, {@code widths} each
	 * one's thickness in pixels. Returns whether they are drawn.
	 */
	public boolean legs(Player p, List<Vector[]> segments, List<Float> widths) {
		if (!enabled()) return false;
		Location at = p.getLocation();
		at.setYaw(0F);
		at.setPitch(0F);
		List<ItemDisplay> mine = legs.get(p.getUniqueId());
		if (mine == null || mine.size() != segments.size()) {
			legsEnd(p);
			mine = new ArrayList<>();
			for (int i = 0; i < segments.size(); i++) mine.add(spawn(at, "red_stick", segment(new Vector3f(), new Vector3f(0, 0, 0.01F), 0.1F), 2, null));
			legs.put(p.getUniqueId(), mine);
		}
		Vector base = at.toVector();
		for (int i = 0; i < segments.size(); i++) {
			Vector[] s = segments.get(i);
			Vector a = s[0].clone().subtract(base), b = s[1].clone().subtract(base);
			float width = widths.get(i) / 16F;
			ItemDisplay d = mine.get(i);
			d.teleport(at);
			moveTo(d, segment(new Vector3f((float) a.getX(), (float) a.getY(), (float) a.getZ()), new Vector3f((float) b.getX(), (float) b.getY(), (float) b.getZ()), width), 2);
		}
		return true;
	}

	/** The hover ended: its legs go. */
	public void legsEnd(Player p) {
		List<ItemDisplay> mine = legs.remove(p.getUniqueId());
		if (mine != null) for (ItemDisplay d : mine) remove(d);
	}

	// ------------------------------------------------------------------ shared

	/** A point up {@code share} of a creature's height, facing south: a tendril's frame is the world's. */
	private static Location chest(LivingEntity e, double share) {
		Location at = e.getLocation().add(0, e.getHeight() * share, 0);
		at.setYaw(0F);
		at.setPitch(0F);
		return at;
	}

	private static Vector3f offset(Location from, Location to) {
		Vector v = to.toVector().subtract(from.toVector());
		return new Vector3f((float) v.getX(), (float) v.getY(), (float) v.getZ());
	}

	/** Every tick: absorbs and tendrils advance; displays of a world a player left go. */
	public void tick() {
		ticks++;
		for (Iterator<Absorb> it = absorbs.iterator(); it.hasNext(); ) {
			Absorb a = it.next();
			if (!a.origin.isOnline() || a.age >= GONE) {
				endAbsorb(a);
				it.remove();
				continue;
			}
			tickAbsorb(a);
		}
		tickTethers();
		for (Iterator<Map.Entry<UUID, Melt>> it = melts.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Melt> e = it.next();
			Player p = plugin.getServer().getPlayer(e.getKey());
			if (p == null || !p.isOnline() || !plugin.morphs().isTransitioning(p)) {
				for (ItemDisplay d : e.getValue().displays) remove(d);
				it.remove();
			}
		}
		live.keySet().removeIf(d -> !d.isValid());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		endTransition(event.getPlayer());
		legsEnd(event.getPlayer());
		Map<UUID, Tether> mine = tethers.remove(event.getPlayer().getUniqueId());
		if (mine != null) {
			for (Tether t : mine.values()) {
				for (ItemDisplay d : t.segments) remove(d);
				for (ItemDisplay d : t.own) remove(d);
			}
		}
	}

}
