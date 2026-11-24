package dev.morphreborn.paper.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.disguise.BorrowedHeads;
import dev.morphreborn.paper.disguise.BorrowedHeads.Shell;
import dev.morphreborn.paper.disguise.GiantPlayer;
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
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
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
		static Box empty(Vector3f middle, Box other) {
			return new Box(new Vector3f(middle), new Vector3f(), other.turn);
		}

		/** The box as its model stands in {@code pose}; as it is with none. */
		Box posed(@Nullable Pose pose) {
			return pose == null ? this : new Box(pose.place(centre), new Vector3f(size), new Quaternionf(pose.turn()).mul(turn));
		}

		static Box lerp(Box a, Box b, float t) {
			return new Box(new Vector3f(a.centre).lerp(b.centre, t), new Vector3f(a.size).lerp(b.size, t), new Quaternionf(a.turn).slerp(b.turn, t));
		}
	}

	private record Model(float height, List<Box> boxes) {}

	/**
	 * How a model stands in a melt whose displays face south, where it would otherwise face the way the displays do:
	 * turned by {@code turn} about {@code pivot} (blocks from the feet).
	 */
	record Pose(Quaternionf turn, Vector3f pivot) {
		Vector3f place(Vector3f point) {
			return new Quaternionf(turn).transform(new Vector3f(point).sub(pivot)).add(pivot);
		}
	}

	/** Blocks the skin stands out from the body it covers on every side, so the body's own faces don't show through. */
	private static final float COVER = 0.015F;
	/** Ticks a display glides to where its body moved (as the borrowed heads do). */
	private static final int GLIDE = 3;
	/** Segments of a Devouring Frenzy tendril, and ticks it takes to reach its creature or draw back (the mod's). */
	private static final int TETHER_SEGMENTS = 5, TETHER_GROW = 6;
	private static final double VIEW_RANGE_SQ = 64 * 64;
	private static final NamespacedKey MARKER = MorphRebornPlugin.key("skin_effect");
	/** The player model's boxes: player forms', and a giant's drawn as the player it borrows from (GiantPlayer). */
	private static final String PLAYER = "minecraft:player";

	private final MorphRebornPlugin plugin;
	private final Map<String, Model> models = new HashMap<>();
	/**
	 * Who sees a display: everyone who draws the skin but {@code hideFrom}, or only {@code onlyFor}; never a viewer
	 * {@code natives} picks out (an arm effect leaves out players whose game draws arms itself).
	 */
	private record Audience(@Nullable UUID hideFrom, @Nullable UUID onlyFor, @Nullable Predicate<Player> natives) {
		Audience(@Nullable UUID hideFrom, @Nullable UUID onlyFor) {
			this(hideFrom, onlyFor, null);
		}

		boolean includes(Player viewer) {
			if (natives != null && natives.test(viewer)) return false;
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
		held.clear();
		lentBefore.clear();
		strands.clear();
		armModels.clear();
	}

	// ------------------------------------------------------------------ models

	/** The boxes {@code form} is drawn with as a main body borrowing {@code lent}: a giant drawn as a player (GiantPlayer) is that player's. */
	private @Nullable Model model(Player owner, MorphRef form, @Nullable MorphRef lent) {
		return GiantPlayer.applies(form, lent) ? models.get(PLAYER) : model(owner, form);
	}

	/** How big {@code form} borrowing {@code lent} is drawn against its boxes: a giant drawn as a player is that player, a giant's size. */
	private float sizeOf(Player owner, MorphRef form, @Nullable MorphRef lent, Model model) {
		return GiantPlayer.applies(form, lent) ? (float) GiantPlayer.GIANT_PLAYER_SCALE : sizeOf(owner, form, model);
	}

	private @Nullable Model model(Player owner, MorphRef form) {
		if (form.isPlayer() || form.isSelf()) return models.get(PLAYER);
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
		/** What each body borrowed as the melt began (null: nothing), which can change how it is drawn (GiantPlayer). */
		final @Nullable MorphRef fromLent, toLent;
		final List<ItemDisplay> displays = new ArrayList<>();
		final List<Box> start = new ArrayList<>(), end = new ArrayList<>();
		/** The skin is off (the mod fades it off the new body over the last eighth; this takes it off half way through). */
		boolean done;
		/**
		 * Into a shulker, or out of one held to a block (see {@link #startMelt}): each body's pose, the displays facing
		 * south throughout. Both null otherwise, the displays turning with the player as any melt's do.
		 */
		@Nullable Pose fromPose, toPose;
		/** The held shulker on each side, or null. */
		@Nullable Shell fromShell, toShell;
		/** The face the new body's boxes were last put on (null: a free body), so a hold that comes or moves is seen. */
		@Nullable BlockFace filledFace;

		Melt(MorphRef from, @Nullable MorphRef fromLent, MorphRef to, @Nullable MorphRef toLent) {
			this.from = from;
			this.to = to;
			this.fromLent = fromLent;
			this.toLent = toLent;
		}

		boolean fixed() {
			return fromPose != null;
		}
	}

	/**
	 * Each held shulker's hold (see {@link BorrowedHeads.Shell}) as it was when a transformation began: the transformation
	 * clears it at once, and a melt out of a wall or ceiling starts from there.
	 */
	private final Map<UUID, Shell> held = new HashMap<>();
	/**
	 * What each player's body borrowed as a transformation began: the transformation gives the new body its borrowed form
	 * at once, and a melt out of a giant drawn as the player it borrowed from (GiantPlayer) starts from that player.
	 */
	private final Map<UUID, MorphRef> lentBefore = new HashMap<>();

	/** Every tick, outside transformations: who holds on to which face, and what each body borrows. */
	private void trackHolds() {
		for (Player p : plugin.getServer().getOnlinePlayers()) {
			if (plugin.morphs().isTransitioning(p)) continue; // what it held and borrowed before it began
			Shell shell = heldBy(plugin.morphs().activeForm(p), plugin.morphs().overlay(p));
			if (shell == null) held.remove(p.getUniqueId());
			else held.put(p.getUniqueId(), shell);
			MorphRef lent = plugin.borrowedHeads().borrowed(p);
			if (lent == null) lentBefore.remove(p.getUniqueId());
			else lentBefore.put(p.getUniqueId(), lent);
		}
	}

	/** The hold on {@code form} as the main body, from its overlay: only a shulker is ever held to a block. */
	private static @Nullable Shell heldBy(@Nullable MorphRef form, String overlay) {
		if (form == null || form.isPlayer() || !BorrowedHeads.SHULKER.equals(form.entityType())) return null;
		return Shell.of(overlay);
	}

	/** The face {@code shell} holds on to; null for none. Only the face poses a body (the peek doesn't). */
	private static @Nullable BlockFace faceOf(@Nullable Shell shell) {
		return shell == null ? null : shell.face();
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
			melt = startMelt(player, s, from, to);
			if (melt == null) return false;
			melts.put(player.getUniqueId(), melt);
		}
		if (melt.done) return true;
		if (s.time >= Math.round(s.duration * 0.94F)) {
			for (ItemDisplay d : melt.displays) remove(d);
			melt.done = true;
			return true;
		}
		Location at;
		if (melt.fixed()) {
			// facing south, the poses hold each body's turn; on the side that shows now, a held shell is where its block is
			melt.toShell = heldBy(to, s.overlay);
			at = (s.morphProgress() < 0.5F ? melt.fromShell : melt.toShell) != null ? BorrowedHeads.anchor(player.getLocation()) : player.getLocation();
			at.setYaw(0F);
		} else {
			at = player.getLocation();
			at.setYaw(BorrowedHeads.drawnYaw(player, to, melt.toLent));
		}
		at.setPitch(0);
		for (ItemDisplay d : melt.displays) {
			if (d.isValid() && d.getWorld() == at.getWorld()) d.teleport(at);
		}
		int first = Math.max(2, Math.round(s.duration * 0.125F)), stroke = Math.max(1, Math.round(s.duration * 0.25F));
		if (s.time >= first && (s.time - first) % stroke == 0 && (s.time - first) / stroke < 3) {
			// the hold may have moved to another face (or gone) since the melt began: the new body ends on the one it has
			if (melt.fixed()) fill(player, melt);
			float t = ActiveMorph.sineify(((s.time - first) / stroke + 1) / 3F);
			for (int i = 0; i < melt.displays.size(); i++) moveTo(melt.displays.get(i), boxAt(Box.lerp(melt.start.get(i), melt.end.get(i), t)), stroke);
		} else if (melt.fixed() && s.time > first && faceOf(melt.toShell) != melt.filledFace) {
			// The hold came (most often with the half-way swap, which lands mid-stroke) or moved: the stroke under way goes
			// on to the new body on that face, in the ticks it has left (or a short glide once the strokes are over),
			// rather than to the free body it set out for.
			fill(player, melt);
			int k = Math.min(2, (s.time - first) / stroke), left = first + (k + 1) * stroke - s.time;
			float t = ActiveMorph.sineify((k + 1) / 3F);
			for (int i = 0; i < melt.displays.size(); i++) {
				moveTo(melt.displays.get(i), boxAt(Box.lerp(melt.start.get(i), melt.end.get(i), t)), left > 0 ? left : GLIDE);
			}
		}
		return true;
	}

	/**
	 * The melt's displays and first boxes. A shulker held to a block on either side (the old body's hold as it was, the
	 * new body's as it comes) is drawn as players without the mod see it: never turned by the player's look, square in its
	 * block and turned onto its face about the block's middle (ShulkerRenderer.setupRotations). The displays then face
	 * south throughout, each body standing in its own pose: a held shell in its block's frame, a free body turned the way
	 * it faced as the melt began.
	 * <p>
	 * Every melt into a shulker is drawn that way, held yet or not: the lock gives the new body its hold as the
	 * transformation begins only when it could put the player in the cell then, and otherwise with the half-way swap. The
	 * melt reads the hold every tick and its last stroke lands the shell on the face it holds by then; one that turned with
	 * the player's look would end on the floor, turned, where the shell is on its wall. A shulker that never holds on is
	 * still right: the viewers' games never turn a shulker's shell.
	 */
	private @Nullable Melt startMelt(Player player, ActiveMorph s, MorphRef from, MorphRef to) {
		// the old body with what it borrowed before the transformation, the new one with what it borrows now
		MorphRef fromLent = lentBefore.get(player.getUniqueId()), toLent = plugin.borrowedHeads().borrowed(player);
		Model a = model(player, from, fromLent), b = model(player, to, toLent);
		if (a == null || b == null) return null;
		Melt melt = new Melt(from, fromLent, to, toLent);
		melt.fromShell = BorrowedHeads.SHULKER.equals(from.entityType()) ? held.get(player.getUniqueId()) : null;
		melt.toShell = heldBy(to, s.overlay);
		if (melt.fromShell != null || BorrowedHeads.SHULKER.equals(to.entityType())) {
			melt.fromPose = pose(player, from, fromLent, a, melt.fromShell);
			melt.toPose = pose(player, to, toLent, b, melt.toShell);
		}
		fill(player, melt);
		Location at;
		if (melt.fixed()) {
			at = melt.fromShell != null ? BorrowedHeads.anchor(player.getLocation()) : player.getLocation();
			at.setYaw(0F);
		} else {
			at = player.getLocation();
			at.setYaw(BorrowedHeads.drawnYaw(player, from, fromLent));
		}
		at.setPitch(0);
		// never to the player themself: from inside it the boxes hid their view (the mod draws none around your camera)
		for (int i = 0; i < melt.start.size(); i++) melt.displays.add(spawn(at, "box_" + (i % 4), boxAt(melt.start.get(i)), GLIDE, player));
		return melt;
	}

	/**
	 * How {@code form} stands in a melt facing south: a held shell turned onto its face about its middle, a free shulker
	 * as it is (the viewers' games never turn a shulker's shell either), anything else turned the way it faces now, as a
	 * display at its yaw would turn it.
	 */
	private Pose pose(Player player, MorphRef form, @Nullable MorphRef lent, Model model, @Nullable Shell shell) {
		if (shell != null) return new Pose(shell.turn(), new Vector3f(0F, model.height * sizeOf(player, form, lent, model) / 2F, 0F));
		if (BorrowedHeads.SHULKER.equals(form.entityType())) return new Pose(new Quaternionf(), new Vector3f());
		return new Pose(new Quaternionf().rotationY((float) Math.toRadians(-BorrowedHeads.drawnYaw(player, form, lent))), new Vector3f());
	}

	/**
	 * Both bodies' boxes, in pairs: a box with nothing to become grows from (or shrinks into) the other body's middle. In
	 * a melt facing south, a new shulker body lands on the face it holds on to now (another body keeps the yaw it had as
	 * the melt began).
	 */
	private void fill(Player player, Melt melt) {
		Model a = model(player, melt.from, melt.fromLent), b = model(player, melt.to, melt.toLent);
		if (a == null || b == null) return;
		if (melt.fixed() && BorrowedHeads.SHULKER.equals(melt.to.entityType())) melt.toPose = pose(player, melt.to, melt.toLent, b, melt.toShell);
		melt.filledFace = faceOf(melt.toShell);
		float sa = sizeOf(player, melt.from, melt.fromLent, a), sb = sizeOf(player, melt.to, melt.toLent, b);
		Vector3f midA = new Vector3f(0F, a.height * sa / 2F, 0F), midB = new Vector3f(0F, b.height * sb / 2F, 0F);
		if (melt.fromPose != null) midA = melt.fromPose.place(midA);
		if (melt.toPose != null) midB = melt.toPose.place(midB);
		int n = Math.max(a.boxes.size(), b.boxes.size());
		List<Box> start = new ArrayList<>(n), end = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			start.add(i < a.boxes.size() ? a.boxes.get(i).scaled(sa).posed(melt.fromPose) : null);
			end.add(i < b.boxes.size() ? b.boxes.get(i).scaled(sb).posed(melt.toPose) : null);
		}
		melt.start.clear();
		melt.end.clear();
		for (int i = 0; i < n; i++) {
			Box ba = start.get(i), bb = end.get(i);
			melt.start.add(ba != null ? ba : Box.empty(midA, bb));
			melt.end.add(bb != null ? bb : Box.empty(midB, ba));
		}
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
				float size = a.victim instanceof Player p && giantPlayer(p) ? (float) GiantPlayer.GIANT_PLAYER_SCALE
						: m.height <= 0 ? 1F : (float) (a.victim.getHeight() / m.height);
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
		return form == null || form.isSelf() || form.isPlayer() || giantPlayer(p) ? PLAYER : form.entityType();
	}

	/** Whether {@code p} is a giant drawn as the player it borrows from (GiantPlayer): it is covered as that player. */
	private boolean giantPlayer(Player p) {
		return GiantPlayer.applies(plugin.morphs().activeForm(p), plugin.borrowedHeads().borrowed(p));
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
		if (enabled()) trackHolds();
		else held.clear();
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
		tickStrands();
		if (ticks % ARM_AUDIT_TICKS == 0) auditArmDisplays();
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
		held.remove(event.getPlayer().getUniqueId());
		lentBefore.remove(event.getPlayer().getUniqueId());
		Map<UUID, Tether> mine = tethers.remove(event.getPlayer().getUniqueId());
		if (mine != null) {
			for (Tether t : mine.values()) {
				for (ItemDisplay d : t.segments) remove(d);
				for (ItemDisplay d : t.own) remove(d);
			}
		}
		removeArmEffectsOf(event.getPlayer().getUniqueId());
	}

	// ------------------------------------------------------------------ arm forms (8.0.0)

	/** Segments of an arm tendril: more than a frenzy tendril's five, for the arm's finer look. */
	public static final int ARM_SEGMENTS = 8;
	/** Ticks an arm tendril takes to grow out to its far end, and to draw back once ended. */
	private static final int STRAND_GROW = 4, STRAND_RETRACT = 3;
	/** An arm tendril nobody refreshed for this long draws back by itself (its special ended without saying so). */
	private static final int STRAND_STALE = 5;
	/**
	 * How often the arm's displays are shown to viewers who came into range since they appeared, and hidden from
	 * viewers whose game now draws arms itself (its mod finished its handshake). The frenzy's tendrils are too short-lived
	 * to need it; a lit lantern's lure stays for minutes.
	 */
	private static final int ARM_AUDIT_TICKS = 10;
	/** The longest glide a display's teleport can have (Display.setTeleportDuration). */
	private static final int MAX_GLIDE = 59;

	/**
	 * An arm tendril (Draining Cut's healing tendrils, Vein Rip's root, later the harpoon's line): segments from one
	 * point to another on a wave that is still at both ends, as everyone sees it, and as its owner does when the end at
	 * their arm is drawn for them from below their view.
	 */
	private static final class Strand {
		final UUID owner;
		final String model;
		final boolean ownerCopy;
		final List<ItemDisplay> shared = new ArrayList<>(), own = new ArrayList<>();
		final float phase;
		final long born;
		long seen;
		/** Ticks since it started drawing back; -1 while it holds. */
		int retract = -1;

		Strand(UUID owner, String model, boolean ownerCopy, float phase, long born) {
			this.owner = owner;
			this.model = model;
			this.ownerCopy = ownerCopy;
			this.phase = phase;
			this.born = born;
			this.seen = born;
		}
	}

	/** Arm tendrils by the key their special gave them. */
	private final Map<Object, Strand> strands = new HashMap<>();

	/** A free-standing arm model (the lantern's lure, phase 2), and who it belongs to. */
	private static final class ArmModel {
		final UUID owner;
		final String model;
		final Predicate<Player> natives;
		ItemDisplay display;

		ArmModel(UUID owner, String model, Predicate<Player> natives, ItemDisplay display) {
			this.owner = owner;
			this.model = model;
			this.natives = natives;
			this.display = display;
		}
	}

	private final Map<Object, ArmModel> armModels = new HashMap<>();

	/**
	 * One step of an arm tendril from {@code from} to {@code to} for players without the mod who have the pack (call it
	 * every other tick while it lasts, as the frenzy's tendrils are; the viewers' games glide between the steps). It
	 * grows out from {@code from} over its first {@value #STRAND_GROW} ticks and thins from {@code widthFromPx} to
	 * {@code widthToPx} pixels. A key that isn't refreshed for {@value #STRAND_STALE} ticks draws back on its own;
	 * {@link #tendrilEnd} draws it back at once.
	 * <p>
	 * {@code ownerCopy}: the end nearer the owner's arm tip ({@link #armTip}) is their arm, and the owner gets their own copy with that end
	 * moved in front of them and below their view, half as thick ({@link #ownArmTip}), as the mod draws a first-person
	 * tendril (from the arm as others see it, it would fill their view). Without it the owner sees the same tendril as
	 * everyone else. {@code drawsNative} leaves out the viewers whose game draws arms itself.
	 *
	 * @param model a skin model ({@code stick} black, {@code red_stick} red), or a pack model path ({@link #armItem})
	 */
	public void tendril(Player owner, Object key, Location from, Location to, String model, int segments, float widthFromPx, float widthToPx, boolean ownerCopy,
			Predicate<Player> drawsNative) {
		if (!enabled() || from.getWorld() == null || from.getWorld() != to.getWorld() || from.getWorld() != owner.getWorld()) {
			Strand gone = strands.remove(key);
			if (gone != null) drop(gone);
			return;
		}
		int n = Math.clamp(segments, 1, 32);
		Strand s = strands.get(key);
		if (s != null && (s.retract >= 0 || s.shared.size() != n || !s.model.equals(model) || s.ownerCopy != ownerCopy || !s.owner.equals(owner.getUniqueId())
				|| (!s.shared.isEmpty() && s.shared.get(0).getWorld() != from.getWorld()))) {
			strands.remove(key);
			drop(s);
			s = null;
		}
		// the arm's end is the one nearer where the arm tip is taken to be (not the eyes: a creature cut up close can be
		// nearer the eyes than the blade is)
		Location tip = armTip(owner);
		boolean ownFrom = from.distanceSquared(tip) <= to.distanceSquared(tip);
		Location own = ownArmTip(owner);
		if (s == null) {
			s = new Strand(owner.getUniqueId(), model, ownerCopy, (key.hashCode() % 997) * 1.7F, ticks);
			ItemStack item = armItem(model);
			Transformation none = segment(new Vector3f(), new Vector3f(0, 0, 0.01F), 0.05F);
			Audience everyone = new Audience(ownerCopy ? owner.getUniqueId() : null, null, drawsNative);
			for (int i = 0; i < n; i++) s.shared.add(spawnArm(flat(from), item, none, 2, everyone));
			if (ownerCopy && drawsFor(owner) && !drawsNative.test(owner)) {
				Audience mine = new Audience(null, owner.getUniqueId(), drawsNative);
				for (int i = 0; i < n; i++) s.own.add(spawnArm(flat(ownFrom ? own : from), item, none, 2, mine));
			}
			strands.put(key, s);
		}
		s.seen = ticks;
		float reach = Math.min(1F, (ticks - s.born + 1) / (float) STRAND_GROW);
		lay(s.shared, s.phase, from, to, reach, widthFromPx, widthToPx);
		if (!s.own.isEmpty()) lay(s.own, s.phase, ownFrom ? own : from, ownFrom ? to : own, reach, widthFromPx * 0.5F, widthToPx * 0.5F);
	}

	/** The arm tendril with this key draws back into its start over {@value #STRAND_RETRACT} ticks, then goes. */
	public void tendrilEnd(Object key) {
		Strand s = strands.get(key);
		if (s != null && s.retract < 0) retract(s);
	}

	/**
	 * A free-standing arm model at {@code at} for players without the mod who have the pack (the lantern's lure, phase
	 * 2), {@code scale} times its size, until {@link #removeModel}. A key already in use is replaced. Viewers who come
	 * into range later are shown it within half a second; its owner's quitting removes it.
	 *
	 * @param model a pack model path ({@code arm/lantern_lure}) or a skin model; see {@link #armItem}
	 */
	public void spawnModel(Player owner, Object key, String model, Location at, float scale, Predicate<Player> drawsNative) {
		removeModel(key);
		if (!enabled() || at.getWorld() == null) return;
		ItemDisplay d = spawnArm(flat(at), armItem(model), scaled(new Quaternionf(), scale), 2, new Audience(null, null, drawsNative));
		armModels.put(key, new ArmModel(owner.getUniqueId(), model, drawsNative, d));
	}

	/**
	 * Move an arm model to {@code at}, turned by {@code rotation} and {@code scale} times its size, gliding there over
	 * {@code ticks} in the viewers' games. A model whose owner went to another world is made again there.
	 */
	public void moveModel(Object key, Location at, Quaternionf rotation, float scale, int ticks) {
		ArmModel m = armModels.get(key);
		if (m == null || at.getWorld() == null) return;
		Transformation t = scaled(rotation, scale);
		if (!m.display.isValid() || m.display.getWorld() != at.getWorld()) {
			remove(m.display);
			m.display = spawnArm(flat(at), armItem(m.model), t, Math.clamp(ticks, 0, MAX_GLIDE), new Audience(null, null, m.natives));
			return;
		}
		m.display.setTeleportDuration(Math.clamp(ticks, 0, MAX_GLIDE));
		m.display.teleport(flat(at));
		moveTo(m.display, t, ticks);
	}

	public void removeModel(Object key) {
		ArmModel m = armModels.remove(key);
		if (m != null) remove(m.display);
	}

	/**
	 * Where the server takes an armed player's arm tip to be as others see it (the mod knows exactly; the server only
	 * estimates): 1.1 blocks along the look, 0.35 to the arm's side and 0.3 below the eyes. Facing south, as a
	 * tendril's frame is the world's.
	 */
	public static Location armTip(Player p) {
		Location eye = p.getEyeLocation();
		Location at = eye.clone().add(eye.getDirection().multiply(1.1)).add(armSide(p).multiply(0.35)).add(0, -0.3, 0);
		return flat(at);
	}

	/**
	 * The arm tip as its owner sees it: half a block ahead along the ground, 0.7 below the eyes and 0.3 to the arm's
	 * side, so a tendril from it starts below their view rather than in the middle of it.
	 */
	public static Location ownArmTip(Player p) {
		Location eye = p.getEyeLocation();
		Vector look = eye.getDirection().setY(0);
		if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
		Location at = eye.clone().add(look.normalize().multiply(0.5)).add(0, -0.7, 0).add(armSide(p).multiply(0.3));
		return flat(at);
	}

	/** The player's right along the ground, or their left for a left-handed player: the side the arm is on. */
	private static Vector armSide(Player p) {
		double yaw = Math.toRadians(p.getLocation().getYaw());
		Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
		return p.getMainHand() == MainHand.LEFT ? right.multiply(-1) : right;
	}

	/** A copy facing south and level: a display's own turn would turn everything drawn on it. */
	private static Location flat(Location at) {
		Location out = at.clone();
		out.setYaw(0F);
		out.setPitch(0F);
		return out;
	}

	private static Transformation scaled(Quaternionf rotation, float scale) {
		float s = Math.max(0F, scale);
		return new Transformation(new Vector3f(), new Quaternionf(rotation), new Vector3f(s, s, s), new Quaternionf());
	}

	/**
	 * The item an arm display shows: a bare name is a skin model ({@code red_stick} is {@code morphreborn:skin/red_stick}),
	 * a path is one of RE:Morph's ({@code arm/lantern_lure}), and a full id is used as it is.
	 */
	private static ItemStack armItem(String model) {
		if (model.indexOf(':') < 0 && model.indexOf('/') < 0) return skin(model);
		NamespacedKey key;
		try {
			key = model.indexOf(':') >= 0 ? NamespacedKey.fromString(model) : new NamespacedKey(MorphRebornPlugin.NAMESPACE, model);
		} catch (IllegalArgumentException e) {
			key = null;
		}
		if (key == null) return skin(model);
		ItemStack item = new ItemStack(Material.PAPER);
		ItemMeta meta = item.getItemMeta();
		meta.setItemModel(key);
		item.setItemMeta(meta);
		return item;
	}

	/** An arm display: as {@link #spawnFor}, showing {@code item}. */
	private ItemDisplay spawnArm(Location at, ItemStack item, Transformation t, int glide, Audience audience) {
		ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
			d.setPersistent(false);
			d.setVisibleByDefault(false);
			d.getPersistentDataContainer().set(MARKER, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
			d.setItemStack(item);
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

	/**
	 * The segments along the wave from {@code from} to {@code to}, drawn out to {@code reach} of the way, thinning from
	 * {@code widthFromPx} to {@code widthToPx} pixels: the frenzy's {@link #place} with both widths given.
	 */
	private void lay(List<ItemDisplay> segments, float phase, Location from, Location to, float reach, float widthFromPx, float widthToPx) {
		if (segments.isEmpty()) return;
		Vector3f line = offset(from, to);
		float length = line.length();
		if (length < 0.05F) return;
		Vector3f dir = new Vector3f(line).div(length);
		Vector3f side = new Vector3f(dir).cross(0F, 1F, 0F);
		if (side.lengthSquared() < 1e-4F) side.set(1F, 0F, 0F);
		side.normalize();
		Vector3f up = new Vector3f(side).cross(dir).normalize();
		float amp = Math.min(0.35F, 0.06F * length + 0.05F);
		float time = ticks;
		int n = segments.size();
		Location origin = flat(from);
		Vector3f prev = new Vector3f();
		for (int i = 1; i <= n; i++) {
			float s = i / (float) n * reach;
			float wave = (float) Math.sin(s * Math.PI) * amp;
			Vector3f p = new Vector3f(dir).mul(length * s)
					.add(new Vector3f(side).mul(wave * (float) Math.sin(phase + s * 7F + time * 0.35F)))
					.add(new Vector3f(up).mul(wave * 0.6F * (float) Math.cos(phase * 0.8F + s * 5F + time * 0.28F)));
			float mid = (i - 0.5F) / n;
			float width = (widthFromPx + (widthToPx - widthFromPx) * mid) / 16F;
			ItemDisplay d = segments.get(i - 1);
			if (d.isValid()) {
				d.teleport(origin);
				moveTo(d, segment(prev, p, width), 2);
			}
			prev = p;
		}
	}

	/** Draw a strand back into its start: every segment shrinks to nothing where the tendril began. */
	private static void retract(Strand s) {
		s.retract = 0;
		for (List<ItemDisplay> set : List.of(s.shared, s.own)) {
			for (ItemDisplay d : set) {
				if (!d.isValid()) continue;
				moveTo(d, new Transformation(new Vector3f(), d.getTransformation().getLeftRotation(), new Vector3f(), new Quaternionf()), STRAND_RETRACT);
			}
		}
	}

	private void drop(Strand s) {
		for (ItemDisplay d : s.shared) remove(d);
		for (ItemDisplay d : s.own) remove(d);
	}

	/** Every tick: strands nobody refreshes draw back, and drawn-back ones go. */
	private void tickStrands() {
		for (Iterator<Strand> it = strands.values().iterator(); it.hasNext(); ) {
			Strand s = it.next();
			if (plugin.getServer().getPlayer(s.owner) == null) {
				drop(s);
				it.remove();
				continue;
			}
			if (s.retract < 0 && ticks - s.seen > STRAND_STALE) retract(s);
			if (s.retract >= 0 && ++s.retract > STRAND_RETRACT + 1) {
				drop(s);
				it.remove();
			}
		}
		for (Iterator<ArmModel> it = armModels.values().iterator(); it.hasNext(); ) {
			ArmModel m = it.next();
			if (plugin.getServer().getPlayer(m.owner) == null || !m.display.isValid()) {
				remove(m.display);
				it.remove();
			}
		}
	}

	/** Show the arm's displays to viewers who now qualify, and hide them from viewers who no longer do. */
	private void auditArmDisplays() {
		for (Strand s : strands.values()) {
			for (ItemDisplay d : s.shared) audit(d);
			for (ItemDisplay d : s.own) audit(d);
		}
		for (ArmModel m : armModels.values()) audit(m.display);
	}

	private void audit(ItemDisplay d) {
		Audience a = live.get(d);
		if (a == null || !d.isValid()) return;
		Location at = d.getLocation();
		for (Player v : d.getWorld().getPlayers()) {
			boolean wanted = a.includes(v) && drawsFor(v);
			if (wanted && v.getLocation().distanceSquared(at) <= VIEW_RANGE_SQ) v.showEntity(plugin, d);
			else if (!wanted) v.hideEntity(plugin, d);
		}
	}

	/** A player left: their arm tendrils and models go at once. */
	private void removeArmEffectsOf(UUID owner) {
		for (Iterator<Strand> it = strands.values().iterator(); it.hasNext(); ) {
			Strand s = it.next();
			if (!s.owner.equals(owner)) continue;
			drop(s);
			it.remove();
		}
		for (Iterator<ArmModel> it = armModels.values().iterator(); it.hasNext(); ) {
			ArmModel m = it.next();
			if (!m.owner.equals(owner)) continue;
			remove(m.display);
			it.remove();
		}
	}
}
