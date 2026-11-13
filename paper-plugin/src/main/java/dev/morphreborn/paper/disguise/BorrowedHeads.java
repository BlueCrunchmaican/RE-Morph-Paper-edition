package dev.morphreborn.paper.disguise;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Multimorph, as players without the client mod see it: Lib's Disguises shows the main body, and the borrowed form's
 * head sits on its neck at the borrowed mob's real size (a cow's head on a chicken is a cow's head). The head is an
 * item display following the player, showing that mob's head model from RE:Morph's resource pack
 * ({@code tools/gen_head_pack.py}); {@code morph-heads.json} says where each body's neck is and how big each head is.
 * <p>
 * A creature the client mod shows by its parts lends those instead, each as a display of its own from the pack, put
 * where the mod grafts it (see {@link #place}): a bat's wings on the shoulder blades, a cod's fins, a blaze's rods
 * around the body, the ender dragon's wings and tail scaled to the body; an animal with ears and a tail lends those.
 * A creature that lends its head lends any tail too (the wither's).
 * <p>
 * The displays are real, unsaved entities, moved every tick with a short glide so they keep up with the disguise as
 * the viewers' games draw it. They are hidden from the player themselves and from players with the client mod, who
 * draw multimorphs themselves.
 */
public final class BorrowedHeads implements Listener {
	/** Ticks a head glides to its next spot: about the time a viewer's game takes to move the body it sits on. */
	private static final int GLIDE_TICKS = 3;
	private static final NamespacedKey MARKER = MorphRebornPlugin.key("borrowed_head");
	/** On a head display, who it is for: {@link #FOR_PACK}, {@link #FOR_NO_PACK} or {@link #FOR_ALL}. */
	private static final NamespacedKey PACK_KEY = MorphRebornPlugin.key("borrowed_head_pack");

	/**
	 * One mob's numbers: its head's pivot on its body (blocks from its feet, +z forward), its height, its head's size,
	 * and where its head meets its neck (the middle of the head's bottom, at the back) in the head model's blocks from
	 * the pivot (item space: +y up, the face towards -z). Heads are lined up by that point, not by their pivots, which sit
	 * in different places (a wolf's mid-head, a golem's at its chin): lining up pivots sank heads into bodies.
	 * {@code model} names its pack models (head/wolf, part/wolf/tail); a baby's numbers ({@code baby}) are its own, from
	 * its own model, under <mob>_baby (head/wolf_baby).
	 */
	private record Head(@Nullable Vector neck, double height, double scale, Vector anchor, Vector size, double @Nullable [][] crown,
			@Nullable Frame frame, double @Nullable [][] torso, double @Nullable [][] all, @Nullable Ears ears, List<Part> parts, boolean lendsHead,
			String model, boolean baby) {}

	/**
	 * A head tilted at rest (a horse's, 30 degrees): its turn (as a display's left rotation) and its big blocks' box in
	 * its own frame (item space, model blocks from its pivot). Borrowed ears go on top of that box and turn with it, as
	 * the client mod places them in the head's own frame.
	 */
	private record Frame(Quaternionf tilt, double[][] crown) {}

	/**
	 * An animal's ears: their box and the box of its head's big blocks without them (item space, blocks from the head's
	 * pivot, at the mob's real size), and the ears model's display scale at that size.
	 */
	private record Ears(double[][] box, double[][] core, double scale) {}

	/**
	 * One part a creature lends (a wing, a tail, a fin, a rod, a stinger): its name in its model, its kind ("wing",
	 * "tail", "rods" or "other": where the client mod puts it), whether it is moved to just touch the body's back (a
	 * tail), where it joins its own body (item space, blocks from the feet, real size), its box from there, and its model's
	 * display scale; and if its creature flaps, its turn to the other end of the flap (as a display's left rotation) and
	 * the ticks one stroke takes.
	 */
	private record Part(String name, String kind, boolean touch, Vector joint, double[][] box, double scale, @Nullable Quaternionf flap, int flapTicks) {}

	/**
	 * A player's body (a player form): Lib's Disguises draws a player, 0.9375 of the model's size. Its torso is the
	 * model's 8 x 12 x 4 body at that size, its whole box the model with its arms and head.
	 */
	private static final Head PLAYER = new Head(new Vector(0, 1.40625, 0), 1.8, 0.9375, new Vector(0, -0.0312, 0.2812), new Vector(0.5625, 0.5625, 0.5625),
			null, null, new double[][] {{-0.2344, 0.7031, -0.1172}, {0.2344, 1.4063, 0.1172}}, new double[][] {{-0.4688, 0, -0.2344}, {0.4688, 1.875, 0.2344}}, null,
			List.of(), true, "player", false);
	/** Blocks borrowed ears reach into the head they go on (the client mod's EAR_SET_IN). */
	private static final double EAR_SET_IN = 0.0625;
	/** Blocks a borrowed tail is set into the body it joins (the client mod's Graft.SET_IN). */
	private static final double TAIL_SET_IN = 0.03;
	/** Blocks borrowed wings stand clear of the body (the client mod's Graft.CLEAR). */
	private static final double WING_CLEAR = 0.04;

	/**
	 * Vanilla heads, for players without the resource pack and for the forms the pack has no head for (the ender
	 * dragon, player forms): the game's own skulls, which need nothing installed. A skull item on a display (26.3,
	 * transform NONE) is drawn at the head's real size, its top at the display's origin, facing backwards; see
	 * {@link #SKULL_ANCHOR}.
	 */
	private static final Map<String, Material> SKULLS = Map.of("minecraft:zombie", Material.ZOMBIE_HEAD, "minecraft:skeleton", Material.SKELETON_SKULL,
			"minecraft:wither_skeleton", Material.WITHER_SKELETON_SKULL, "minecraft:creeper", Material.CREEPER_HEAD, "minecraft:piglin", Material.PIGLIN_HEAD,
			"minecraft:ender_dragon", Material.DRAGON_HEAD, "minecraft:player", Material.PLAYER_HEAD);
	/**
	 * Bodies whose renderer draws what is worn on the head (CustomHeadLayer): a borrowed form's skull goes on the
	 * disguise's head slot instead of a display. It then covers the body's own head and turns with it exactly, which a
	 * display can't (Lib's Disguises always draws the body's head).
	 */
	private static final java.util.Set<String> HELMETED = java.util.Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned",
			"minecraft:zombie_villager", "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton", "minecraft:bogged", "minecraft:parched",
			"minecraft:piglin", "minecraft:piglin_brute", "minecraft:zombified_piglin", "minecraft:villager", "minecraft:wandering_trader",
			"minecraft:pillager", "minecraft:vindicator", "minecraft:evoker", "minecraft:illusioner", "minecraft:copper_golem", "minecraft:player");
	/** Players whose disguise wears a borrowed skull, and which form's. */
	private final Map<UUID, String> helmets = new HashMap<>();

	/**
	 * A skull item's neck point (blocks, at scale 1, once it is turned to face forward): the middle of its hat layer's
	 * bottom, at the back, as the pack heads' anchors are. Measured in game against a block ruler (2026-11-11): the head
	 * hangs half a block down from the display's origin, a quarter block either side. The old numbers (half size, centred
	 * on the neck) sank a lent player's head inside the body's head, so players without the mod never saw it.
	 */
	private static final Vector SKULL_ANCHOR = new Vector(0, -0.53125, 0.28125);
	/** A borrowed head is drawn at most this many times the size of the body's own head (a giant's on a person). */
	private static final double BIG_HEAD = 1.5;
	private static final float SKULL_SCALE = 1F;
	/** Who a head display is for: viewers with the resource pack, without it, or both (a skull with no pack model beside it). */
	private static final byte FOR_NO_PACK = 0, FOR_PACK = 1, FOR_ALL = 2;

	private final MorphRebornPlugin plugin;
	private final Map<String, Head> heads = new HashMap<>();
	private final Map<UUID, MorphRef> borrowed = new HashMap<>();
	/**
	 * Each multimorph's displays: the resource pack's head model and a vanilla skull for the others, or for an animal that
	 * lends its ears and tail, the pack's ears (on the body's head); and the pack's parts (wings, tails, fins, rods), in the
	 * order of the lent creature's parts. Any may be missing.
	 */
	private record Shown(@Nullable ItemDisplay pack, @Nullable ItemDisplay skull, @Nullable ItemDisplay ears, List<ItemDisplay> parts) {
		List<ItemDisplay> all() {
			List<ItemDisplay> out = new java.util.ArrayList<>(parts);
			if (pack != null) out.add(pack);
			if (skull != null) out.add(skull);
			if (ears != null) out.add(ears);
			return out;
		}

		boolean valid(Player owner, int partCount) {
			for (ItemDisplay d : all()) {
				if (!d.isValid() || d.getWorld() != owner.getWorld()) return false;
			}
			return parts.size() == partCount && !all().isEmpty();
		}

		void teleport(Location at, List<Placed> partsAt) {
			if (pack != null) pack.teleport(at);
			if (skull != null) skull.teleport(at);
			if (ears != null) ears.teleport(at);
			for (int i = 0; i < parts.size() && i < partsAt.size(); i++) parts.get(i).teleport(partsAt.get(i).at());
		}

		void remove() {
			for (ItemDisplay d : all()) if (d.isValid()) d.remove();
		}
	}

	/** Where one lent part goes this tick, and the size its display is drawn at. */
	private record Placed(Location at, float scale) {}

	private final Map<UUID, Shown> displays = new HashMap<>();
	/** Players whose game loaded the server's resource pack. */
	private final Set<UUID> packLoaded = new HashSet<>();

	public BorrowedHeads(MorphRebornPlugin plugin) {
		this.plugin = plugin;
		load();
	}

	private void load() {
		try (InputStream in = plugin.getResource("morph-heads.json")) {
			if (in == null) {
				plugin.getLogger().warning("morph-heads.json is missing from the jar; multimorph heads won't show to players without the mod.");
				return;
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : root.entrySet()) {
				JsonObject h = e.getValue().getAsJsonObject();
				// no neck: parts only (the ender dragon), no body for a borrowed head
				Vector neck = h.has("neck") ? vector(h.getAsJsonArray("neck")) : null;
				Vector anchor = h.has("anchor") ? vector(h.getAsJsonArray("anchor")) : new Vector();
				if (h.has("whole") && h.get("whole").getAsBoolean()) whole.add(e.getKey());
				Vector size = h.has("size") ? vector(h.getAsJsonArray("size")) : new Vector();
				double[][] crown = h.has("crown") ? box(h.getAsJsonArray("crown")) : null;
				Frame frame = null;
				if (h.has("frame")) {
					// item space to the display's: the display draws its item turned half way round about y
					JsonObject f = h.getAsJsonObject("frame");
					JsonArray q = f.getAsJsonArray("tilt");
					frame = new Frame(new Quaternionf(-q.get(0).getAsFloat(), q.get(1).getAsFloat(), -q.get(2).getAsFloat(), q.get(3).getAsFloat()).normalize(),
							box(f.getAsJsonArray("crown")));
				}
				double[][] torso = h.has("torso") ? box(h.getAsJsonArray("torso")) : null;
				double[][] all = h.has("all") ? box(h.getAsJsonArray("all")) : null;
				Ears ears = null;
				if (h.has("ears")) {
					JsonObject ea = h.getAsJsonObject("ears");
					ears = new Ears(box(ea.getAsJsonArray("box")), box(ea.getAsJsonArray("core")), ea.get("scale").getAsDouble());
				}
				List<Part> parts = new java.util.ArrayList<>();
				if (h.has("parts")) {
					for (JsonElement pe : h.getAsJsonArray("parts")) {
						JsonObject p = pe.getAsJsonObject();
						Quaternionf flap = null;
						if (p.has("flap")) {
							// item space to the display's: the display draws its item turned half way round about y
							JsonArray q = p.getAsJsonArray("flap");
							flap = new Quaternionf(-q.get(0).getAsFloat(), q.get(1).getAsFloat(), -q.get(2).getAsFloat(), q.get(3).getAsFloat()).normalize();
						}
						parts.add(new Part(p.get("name").getAsString(), p.get("kind").getAsString(), p.get("touch").getAsBoolean(),
								vector(p.getAsJsonArray("joint")), box(p.getAsJsonArray("box")), p.get("scale").getAsDouble(), flap,
								p.has("flapTicks") ? Math.max(1, p.get("flapTicks").getAsInt()) : 0));
					}
				}
				boolean lendsHead = !h.has("lendsHead") || h.get("lendsHead").getAsBoolean();
				String key = e.getKey();
				heads.put(key, new Head(neck, h.get("height").getAsDouble(), h.get("scale").getAsDouble(), anchor, size, crown, frame, torso, all, ears,
						List.copyOf(parts), lendsHead, key.substring(key.indexOf(':') + 1), key.endsWith(BABY)));
			}
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not read morph-heads.json; multimorph heads won't show to players without the mod.", e);
		}
	}

	private static Vector vector(JsonArray a) {
		return new Vector(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	/** {lo, hi} from two JSON arrays of three. */
	private static double[][] box(JsonArray a) {
		double[][] out = new double[2][3];
		for (int c = 0; c < 2; c++) {
			JsonArray v = a.get(c).getAsJsonArray();
			for (int i = 0; i < 3; i++) out[c][i] = v.get(i).getAsDouble();
		}
		return out;
	}

	public @Nullable MorphRef borrowed(Player player) {
		return borrowed.get(player.getUniqueId());
	}

	/** Set (or clear, with null) the form {@code player} borrows from, and tell players with the mod. */
	public void setBorrowed(Player player, @Nullable MorphRef form) {
		setBorrowed(player, form, true);
	}

	/**
	 * Set (or clear) the borrowed form and save it with the player. {@code broadcast} false when the caller sends the
	 * morph state itself (a new transformation carries it).
	 */
	public void setBorrowed(Player player, @Nullable MorphRef form, boolean broadcast) {
		MorphRef before = borrowed.get(player.getUniqueId());
		if (before != null && before.equals(form)) return;
		removeDisplay(player.getUniqueId());
		if (form == null) borrowed.remove(player.getUniqueId());
		else borrowed.put(player.getUniqueId(), form);
		var data = plugin.store().get(player.getUniqueId());
		data.borrowed = form;
		plugin.store().markDirty(player.getUniqueId());
		if (!broadcast) return;
		// players with the mod draw the hybrid themselves: tell them
		var state = plugin.morphs().state(player);
		if (state != null) plugin.channel().broadcastState(player, state);
		// the same body, another borrowed form: its traits change with it (a new body gets them when it has transformed)
		if (state != null && !state.isTransitioning()) plugin.abilities().onFormChanged(player, plugin.morphs().activeForm(player));
	}

	public boolean hasHead(String entityType) {
		return heads.containsKey(entityType) || SKULLS.containsKey(entityType);
	}

	/** A body's numbers: the head table's, or a player's for player forms; null for a body with no neck in it (the dragon). */
	private @Nullable Head body(Player owner, MorphRef form) {
		if (form.isPlayer()) return PLAYER;
		Head head = headFor(owner, form, false);
		return head == null || head.neck() == null ? null : head;
	}

	/** The table's key suffix for a creature's baby. */
	private static final String BABY = "_baby";

	/**
	 * The numbers {@code form} is drawn by: its baby's when it is a baby, or when {@code young} (a baby body's borrowed
	 * creature, drawn as its baby where it has one, as the client mod draws it); otherwise its own. 26.3 draws babies with
	 * models of their own, in proportions of their own: a grown creature's numbers at half size put a borrowed head
	 * beside a baby's and its parts at the wrong size.
	 */
	private @Nullable Head headFor(Player owner, MorphRef form, boolean young) {
		if (form.isPlayer()) return null;
		if (young || isBaby(owner, form)) {
			Head baby = heads.get(form.entityType() + BABY);
			if (baby != null) return baby;
		}
		return heads.get(form.entityType());
	}

	/** Ticks counted by {@link #tick}, which times the flaps. */
	private long ticks;

	/**
	 * Wings and other parts that flap swing between the two ends of their creature's flap: at each stroke's start, the
	 * display is given the other end's turn and the stroke's length to get there in, and the viewer's game turns it
	 * smoothly (a display's interpolation), so a stroke costs one update for each viewer.
	 */
	private void flap(@Nullable Shown shown, Head lent) {
		if (shown == null) return;
		for (int i = 0; i < shown.parts().size() && i < lent.parts().size(); i++) {
			Part part = lent.parts().get(i);
			if (part.flap() == null || part.flapTicks() <= 0 || ticks % part.flapTicks() != 0) continue;
			boolean out = (ticks / part.flapTicks()) % 2 == 1;
			ItemDisplay d = shown.parts().get(i);
			Transformation now = d.getTransformation();
			d.setInterpolationDelay(0);
			d.setInterpolationDuration(part.flapTicks());
			d.setTransformation(new Transformation(now.getTranslation(), out ? new Quaternionf(part.flap()) : new Quaternionf(), now.getScale(),
					now.getRightRotation()));
		}
	}

	/** Every tick: a head on the neck of each fully transformed multimorph, and none anywhere else. */
	public void tick() {
		ticks++;
		if (borrowed.isEmpty() && displays.isEmpty() && helmets.isEmpty()) return;
		for (UUID id : List.copyOf(displays.keySet())) {
			if (!borrowed.containsKey(id)) removeDisplay(id);
		}
		for (UUID id : List.copyOf(helmets.keySet())) {
			if (borrowed.containsKey(id)) continue;
			helmets.remove(id);
			Player owner = plugin.getServer().getPlayer(id);
			if (owner != null) plugin.disguises().setHeadOverride(owner, null);
		}
		String mode = plugin.settings().multimorphVanillaHeads;
		for (Map.Entry<UUID, MorphRef> e : borrowed.entrySet()) {
			Player player = plugin.getServer().getPlayer(e.getKey());
			MorphRef main = player == null ? null : plugin.morphs().activeForm(player);
			MorphRef lent = e.getValue();
			Head body = main == null ? null : body(player, main);
			Head lentHead = lent.isPlayer() || main == null ? null : headFor(player, lent, isBaby(player, main));
			boolean show = !mode.equals("off") && player != null && !player.isDead() && main != null && !main.isSelf()
					&& !plugin.morphs().isTransitioning(player) && body != null && (lentHead != null || skullType(lent) != null);
			// a body that wears things on its head wears the borrowed skull, for every viewer without the mod (not a
			// creature that lends its parts instead of its head: the dragon)
			boolean helmet = show && (lentHead == null || lentHead.lendsHead()) && HELMETED.contains(main.isPlayer() ? "minecraft:player" : main.entityType())
					&& skullType(lent) != null;
			if (helmet) {
				removeDisplay(e.getKey());
				String key = lent.entityType() + "|" + lent.variantId() + "|" + lent.snbt();
				if (!key.equals(helmets.get(e.getKey()))) {
					helmets.put(e.getKey(), key);
					plugin.disguises().setHeadOverride(player, skullItem(lent));
				}
				continue;
			}
			if (player != null && helmets.remove(e.getKey()) != null) plugin.disguises().setHeadOverride(player, null);
			if (!show) {
				removeDisplay(e.getKey());
				continue;
			}
			Shown shown = displays.get(e.getKey());
			Location neck = neck(player, main, body, isWhole(lent.entityType()), lentHead == null || lentHead.lendsHead());
			List<Placed> partsAt = lentHead == null ? List.of() : partsAt(player, main, body, lent, lentHead);
			if (shown == null || !shown.valid(player, partsAt.size())) {
				removeDisplay(e.getKey());
				displays.put(e.getKey(), spawn(player, main, body, lent, lentHead, neck, partsAt, coverScale(player, main, body, lent, lentHead)));
			} else {
				shown.teleport(neck, partsAt);
			}
			if (lentHead != null) flap(displays.get(e.getKey()), lentHead);
		}
	}

	/**
	 * Where the borrowed head goes: where the main body's own head meets its neck, turned with the body; the head turned
	 * as the player looks. {@code head}: a head goes there (not ears), lifted out of the body's torso as the mod lifts it.
	 */
	private Location neck(Player player, MorphRef main, Head body, boolean centred, boolean head) {
		double size = sizeOf(player, main, body);
		double yaw = Math.toRadians(drawnYaw(player, main));
		// the pivot, then the body's own head's neck point from it (item space faces -z: the body's +z is its -z); a
		// creature lent whole (a slime) stands on the middle of the head's big blocks, not at its back, or it sat behind it
		double s = body.scale() * size;
		double back = body.anchor().getZ();
		if (centred) back += body.crown() != null ? (body.crown()[0][2] + body.crown()[1][2]) / 2.0 : -body.size().getZ() / 2.0;
		Vector n = body.neck().clone().multiply(size).add(new Vector(-body.anchor().getX() * s, body.anchor().getY() * s, -back * s));
		// a head goes on a neck point out of the body's torso, as the client mod puts it (HybridMorph.outOfTorso): a
		// camel's, a llama's, a pig's and a baby goat's heads meet their necks inside their chests, and a borrowed head
		// set there sank into the body for players without the mod. Ears go on the head itself and aren't moved.
		if (head && body.torso() != null && (main.isPlayer() || !isWhole(main.entityType()))) outOfTorso(n, scale(body.torso(), size));
		double x = n.getX() * Math.cos(yaw) - n.getZ() * Math.sin(yaw);
		double z = n.getX() * Math.sin(yaw) + n.getZ() * Math.cos(yaw);
		Location at = player.getLocation().add(x, n.getY(), z);
		at.setYaw(player.getLocation().getYaw());
		at.setPitch(player.getLocation().getPitch());
		return at;
	}

	/**
	 * {@code n} (blocks from the feet, +z forward) moved out of {@code torso} (item space, the face towards -z) when it is
	 * inside it: up to its top or forward to its front, whichever is nearer, as the client mod's outOfTorso. A tie (within
	 * the numbers' rounding) goes up, as the mod's does: a baby goat's is one.
	 */
	private static void outOfTorso(Vector n, double[][] torso) {
		double x = -n.getX(), y = n.getY(), z = -n.getZ();
		double[] lo = torso[0], hi = torso[1];
		double inside = 0.01;
		if (x <= lo[0] + inside || x >= hi[0] - inside || y <= lo[1] + inside || y >= hi[1] - inside || z <= lo[2] + inside || z >= hi[2] - inside) {
			return;
		}
		double up = hi[1] - y, forward = z - lo[2];
		if (up <= forward + 1e-3) n.setY(hi[1]);
		else n.setZ(-lo[2]);
	}

	/** The vanilla skull for {@code form}, or null when the game has none. */
	private static @Nullable Material skullType(MorphRef form) {
		if (form.isSelf()) return null;
		return SKULLS.get(form.isPlayer() ? "minecraft:player" : form.entityType());
	}

	/**
	 * How much bigger to draw a borrowed head than its real size so it covers the body's own head (players without the
	 * mod always see the body's head; a smaller head would sit hidden inside it): at least 1.
	 */
	private double coverScale(Player owner, MorphRef main, Head body, MorphRef form, @Nullable Head lentHead) {
		// a player's head (another player's form, or your own on a creature) covers the body's head too, or it hid in it
		Head head = form.isPlayer() ? PLAYER : lentHead;
		if (head == null || body.size().lengthSquared() == 0 || head.size().lengthSquared() == 0 || heads.get(main.entityType()) != null
				&& isWhole(main.entityType())) {
			return 1;
		}
		boolean whole = !form.isPlayer() && isWhole(form.entityType());
		double bodySize = sizeOf(owner, main, body), headSize = sizeOf(owner, form, head);
		// what it takes to cover the body's head on every axis
		double fit = 0;
		for (int axis = 0; axis < 3; axis++) {
			double main3 = component(body.size(), axis) * body.scale() * bodySize;
			double lent3 = component(head.size(), axis) * head.scale() * headSize;
			if (lent3 > 1e-3) fit = Math.max(fit, main3 / lent3 * 1.04);
		}
		if (fit <= 0) return 1;
		// heads compared as the client mod compares them (HybridMorph.headSize): by their big blocks' box, not the
		// longest side of everything on them. A llama's head is mostly neck, 18 pixels tall: by its longest side a baby
		// llama's was more than BIG_HEAD times a baby goat's and was drawn at three quarters of its size, by its big
		// blocks it isn't, and the mod draws it at its own
		double sB = body.scale() * bodySize, sH = head.scale() * headSize;
		double mainCore = coreSide(body) * sB, lentCore = coreSide(head) * sH;
		if (mainCore <= 1e-3 || lentCore <= 1e-3) return whole ? fit : Math.max(1, fit);
		// the size the client mod draws it at: a creature lent whole no bigger than the head it stands in for, any other
		// head at most BIG_HEAD times the body's (a giant's), as it is
		double mod = whole ? Math.min(1, mainCore / lentCore) : lentCore > BIG_HEAD * mainCore ? BIG_HEAD * mainCore / lentCore : 1;
		if (!whole && lentCore > BIG_HEAD * mainCore) return mod;
		// what it takes to hide the body's head: on every axis of the whole head, and (not for a creature lent whole,
		// which sits on the middle of the head) its big blocks holding the body's from where both meet their necks: a
		// zombie's face showed in front of a llama's neck, which is 6 pixels deep
		double cover = whole ? fit : Math.max(fit, crownFit(crownOf(body, sB), crownOf(head, sH)));
		// The user's call (7.5.0): covering is worth at most COVER_MAX times the mod's size; past it the body's head may
		// show (a cow's head on a llama, a panda's on a camel were two to three times their size, to hide the long neck
		// those heads hold). Unless at that size the head would still sit inside the body's on every side, where it would
		// vanish (a baby turtle's on a cow; the wolf's in the iron golem's, why covering began): that one is covered fully.
		// Not by size alone: a frog's head is small but flat and wide, and covering a zombie's grew it to three times.
		if (holdLeast(crownOf(body, sB), crownOf(head, sH), whole) < COVER_MAX * mod) cover = Math.min(cover, COVER_MAX * mod);
		return whole ? cover : Math.max(mod, cover);
	}

	/**
	 * The least, over its sides, of how far {@code mine} reaches past {@code own}, both from where they meet their necks
	 * (sideways each way, up and forward; for a head set on the middle of the other, {@code middle}, back too): drawn that
	 * many times bigger, {@code own} would still sit inside {@code mine} on every side.
	 */
	private static double holdLeast(double[][] mine, double[][] own, boolean middle) {
		if (middle) {
			mine = centredFrontToBack(mine);
			own = centredFrontToBack(own);
		}
		double least = Math.min(Math.min(ratio(mine[0][0], own[0][0]), ratio(mine[1][0], own[1][0])),
				Math.min(ratio(mine[1][1], own[1][1]), ratio(mine[0][2], own[0][2])));
		return middle ? Math.min(least, ratio(mine[1][2], own[1][2])) : least;
	}

	private static double[][] centredFrontToBack(double[][] box) {
		double half = (box[1][2] - box[0][2]) / 2;
		return new double[][] {{box[0][0], box[0][1], -half}, {box[1][0], box[1][1], half}};
	}

	/** Covering a body's head is worth at most this many times the size the mod draws a head at (the user's call). */
	private static final double COVER_MAX = 1.5;

	/** A head's big blocks (item space from where it meets its neck, the face towards -z), times {@code scale}. */
	private static double[][] crownOf(Head h, double scale) {
		if (h.crown() != null) return scale(h.crown(), scale);
		double w = h.size().getX() * scale, ht = h.size().getY() * scale, d = h.size().getZ() * scale;
		return new double[][] {{-w / 2, 0, -d}, {w / 2, ht, 0}};
	}

	/**
	 * How much bigger {@code own} must be drawn to hold {@code mine}, both from where they meet their necks (the back of
	 * their bottom, which the head display lines up): sideways each way, up and forward. 0 when it holds it already.
	 */
	private static double crownFit(double[][] mine, double[][] own) {
		double fit = Math.max(Math.max(ratio(mine[0][0], own[0][0]), ratio(mine[1][0], own[1][0])),
				Math.max(ratio(mine[1][1], own[1][1]), ratio(mine[0][2], own[0][2])));
		return fit * 1.04;
	}

	private static double ratio(double mine, double own) {
		return Math.abs(own) > 1e-4 && mine * own > 0 ? mine / own : 0;
	}

	/** A head's size as the client mod measures it (HybridMorph.headSize): its big blocks' box, as one side of a cube. */
	private static double coreSide(Head h) {
		double[][] c = h.crown();
		double v = c != null ? (c[1][0] - c[0][0]) * (c[1][1] - c[0][1]) * (c[1][2] - c[0][2]) : h.size().getX() * h.size().getY() * h.size().getZ();
		return Math.cbrt(Math.max(0, v));
	}

	private static double component(Vector v, int axis) {
		return axis == 0 ? v.getX() : axis == 1 ? v.getY() : v.getZ();
	}

	/** Mobs lent whole (no head part: slimes, ghasts...); as a body they have no head of their own to cover. */
	private final java.util.Set<String> whole = new java.util.HashSet<>();

	private boolean isWhole(String type) {
		return whole.contains(type);
	}

	private boolean isBaby(Player owner, MorphRef form) {
		if (form.isPlayer()) return false;
		return plugin.templates().get(form, owner.getWorld()) instanceof org.bukkit.entity.Ageable a && !a.isAdult();
	}

	/**
	 * How big a form is drawn against the size its numbers were measured at (a big slime, a scaled mob). A grown creature
	 * drawn by its baby's numbers (a baby body's borrowed creature) is its own scale against its grown height.
	 */
	private double sizeOf(Player owner, MorphRef form, Head head) {
		LivingEntity template = form.isPlayer() ? null : plugin.templates().get(form, owner.getWorld());
		if (template == null || head.height() <= 0) return 1.0;
		if (head.baby() && !(template instanceof org.bukkit.entity.Ageable a && !a.isAdult())) {
			Head grown = heads.get(form.entityType());
			return grown == null || grown.height() <= 0 ? 1.0 : template.getHeight() / grown.height();
		}
		return template.getHeight() / head.height();
	}

	/** An item showing one of the pack's models: {@code kind} "head" or "ears", for the creature {@code head} is. */
	private static ItemStack packItem(String kind, Head head) {
		return packItem(kind + "/" + head.model());
	}

	/** An item showing the pack's model {@code morphreborn:<path>}. */
	private static ItemStack packItem(String path) {
		ItemStack item = new ItemStack(Material.PAPER);
		ItemMeta meta = item.getItemMeta();
		meta.setItemModel(new NamespacedKey(MorphRebornPlugin.NAMESPACE, path));
		item.setItemMeta(meta);
		return item;
	}

	/**
	 * The displays for {@code form} on {@code main}: its parts at {@code partsAt} (pack only: the game has no wings to
	 * show), and the head at {@code at} (the pack's model where there is one, a vanilla skull where there is one), or for
	 * an animal that lends its ears and tail, its ears; a creature that lends its parts instead of its head shows players
	 * without the pack its skull, if the game has one (the dragon's), as before it lent its parts.
	 */
	private Shown spawn(Player owner, MorphRef main, Head body, MorphRef form, @Nullable Head head, Location at, List<Placed> partsAt, double cover) {
		List<ItemDisplay> parts = new java.util.ArrayList<>();
		for (int i = 0; head != null && i < partsAt.size(); i++) {
			Placed p = partsAt.get(i);
			parts.add(display(owner, packItem("part/" + head.model() + "/" + head.parts().get(i).name()), p.at(), p.scale(), new Vector3f(), new Quaternionf(), FOR_PACK));
		}
		if (head != null && !head.lendsHead()) {
			ItemDisplay ears = head.ears() == null ? null : ears(owner, main, body, form, head, at);
			ItemStack skullItem = skullItem(form);
			ItemDisplay skull = skullItem == null ? null : display(owner, skullItem, at, (float) (SKULL_SCALE * cover), SKULL_ANCHOR, true, FOR_NO_PACK);
			return new Shown(null, skull, ears, List.copyOf(parts));
		}
		ItemDisplay pack = null;
		if (head != null) {
			pack = display(owner, packItem("head", head), at, (float) (head.scale() * sizeOf(owner, form, head) * cover), head.anchor(), false, FOR_PACK);
		}
		ItemDisplay skull = null;
		ItemStack skullItem = skullItem(form);
		// a skull with no pack model beside it (player forms, the dragon) is the only head there is: everyone sees it
		// (a player's at the size coverScale measured it: a disguised player's head, PLAYER.scale of the model's)
		double skullScale = SKULL_SCALE * (form.isPlayer() ? PLAYER.scale() : 1) * cover;
		if (skullItem != null) skull = display(owner, skullItem, at, (float) skullScale, SKULL_ANCHOR, true, head == null ? FOR_ALL : FOR_NO_PACK);
		return new Shown(pack, skull, null, List.copyOf(parts));
	}

	/**
	 * Borrowed ears on the main body's own head, as the client mod places them (HybridMorph.placeEars): at the size the
	 * main head's width asks for (half to twice their own), where they stand on their own head as a share of it, their
	 * middle over the head from front to back, and their lowest third (at least EAR_SET_IN) in it. The display sits on
	 * the neck point, as a head would, so the ears turn and nod with the head. None on a body that is all head (a slime).
	 */
	private @Nullable ItemDisplay ears(Player owner, MorphRef main, Head body, MorphRef form, Head head, Location at) {
		Ears ea = head.ears();
		if (ea == null || (!main.isPlayer() && isWhole(main.entityType())) || body.size().lengthSquared() == 0) return null;
		double sizeA = sizeOf(owner, form, head);
		double sB = body.scale() * sizeOf(owner, main, body);
		// the main head's big blocks, item space from its neck point (the middle of its bottom, at the back; the face
		// towards -z), as the client mod measures them: not its own ears, horns or snout (counted in, a wolf's own ears
		// stood borrowed ones two pixels up and its snout pulled them forward)
		double wS = body.size().getX() * sB, hS = body.size().getY() * sB, dS = body.size().getZ() * sB;
		// a head tilted at rest (a horse's): measured in its own frame from its pivot, the ears turned with it below
		Frame frame = body.frame();
		double[][] mine = frame != null ? scale(frame.crown(), sB)
				: body.crown() != null ? scale(body.crown(), sB) : new double[][] {{-wS / 2, 0, -dS}, {wS / 2, hS, 0}};
		double wB = mine[1][0] - mine[0][0], top = mine[1][1];
		double cx = (mine[0][0] + mine[1][0]) / 2, cz = (mine[0][2] + mine[1][2]) / 2;
		double[][] core = scale(ea.core(), sizeA), box = scale(ea.box(), sizeA);
		double wA = Math.max(0.01, core[1][0] - core[0][0]);
		double k = Math.clamp(wB / wA, 0.5, 2); // an armadillo's at 2.5 were twice its head
		// the borrowed head's top middle goes on the main head's top middle, (cx, top, cz)
		double tx = (core[0][0] + core[1][0]) / 2, ty = core[1][1], tz = (core[0][2] + core[1][2]) / 2;
		double middle = cz + k * ((box[0][2] + box[1][2]) / 2 - tz);
		double front = mine[0][2] + EAR_SET_IN, back = mine[1][2] - EAR_SET_IN;
		double dz = front < back ? Math.clamp(middle, front, back) - middle : 0;
		double bottom = top + k * (box[0][1] - ty);
		double sink = Math.max(EAR_SET_IN, k * (box[1][1] - box[0][1]) / 3);
		double dy = top - sink - bottom;
		// where the ears model's origin (the borrowed head's pivot) goes, item space; the display shows it turned round
		double ox = cx - k * tx, oy = top - k * ty + dy, oz = cz - k * tz + dz;
		Vector3f shift = new Vector3f((float) -ox, (float) oy, (float) -oz);
		Quaternionf turn = new Quaternionf();
		if (frame != null) {
			// from the tilted head's own frame: turned with it about its pivot, then from the pivot to where the head
			// meets its neck, which is where the display sits
			turn = new Quaternionf(frame.tilt());
			turn.transform(shift);
			shift.add((float) (body.anchor().getX() * sB), (float) (-body.anchor().getY() * sB), (float) (body.anchor().getZ() * sB));
		}
		return display(owner, packItem("ears", head), at, (float) (ea.scale() * sizeA * k), shift, turn, FOR_PACK);
	}

	/**
	 * Where each of the lent creature's parts goes on the main body this tick, and how big it is drawn, as the client mod's
	 * Graft puts it (see {@link #place}); each turned with the body. Empty when either body has no torso to measure.
	 */
	private List<Placed> partsAt(Player player, MorphRef main, Head body, MorphRef lent, Head lentHead) {
		if (lentHead.parts().isEmpty() || body.torso() == null || lentHead.torso() == null) return List.of();
		// a baby body's borrowed creature comes with its baby's numbers, where it has babies (headFor), as with the mod
		double sizeA = sizeOf(player, lent, lentHead), sizeM = sizeOf(player, main, body);
		double[][] own = scale(lentHead.torso(), sizeA), mine = scale(body.torso(), sizeM);
		double[][] ownAll = lentHead.all() == null ? own : scale(lentHead.all(), sizeA);
		double[][] mineAll = body.all() == null ? mine : scale(body.all(), sizeM);
		// the dragon's parts are scaled to the body they go on (the user's choice in the mod); everyone else's keep their size
		double fit = lent.entityType().equals("minecraft:ender_dragon") ? fit(own, mine) : 1;
		float drawn = drawnYaw(player, main);
		double yaw = Math.toRadians(drawn);
		List<Placed> out = new java.util.ArrayList<>(lentHead.parts().size());
		for (Part part : lentHead.parts()) {
			double[] joint = {part.joint().getX() * sizeA, part.joint().getY() * sizeA, part.joint().getZ() * sizeA};
			double[] at = place(own, mine, ownAll, mineAll, joint, part.kind(), fit);
			if (part.touch()) {
				// a tail just reaches into the back: in by TAIL_SET_IN where it stands off it, out where it would be hidden in
				// it (a camel's flat tail)
				double k = sizeA * fit;
				double lo = at[2] + part.box()[0][2] * k, hi = at[2] + part.box()[1][2] * k, back = mine[1][2];
				if (hi < back + 0.005) at[2] += back + 0.005 - hi;
				else if (lo > back - TAIL_SET_IN) at[2] += back - TAIL_SET_IN - lo;
			}
			// item space is the body's frame turned half way round; then the body's facing
			double bx = -at[0], bz = -at[2];
			Location l = player.getLocation().add(bx * Math.cos(yaw) - bz * Math.sin(yaw), at[1], bx * Math.sin(yaw) + bz * Math.cos(yaw));
			l.setYaw(drawn);
			l.setPitch(0);
			out.add(new Placed(l, (float) (part.scale() * sizeA * fit)));
		}
		return out;
	}

	/**
	 * The way the main body faces as players without the mod see it. A creature (Lib's Disguises draws a mob) turns its
	 * body the way the player looks: when moving at once, standing still soon after the head (BodyRotationControl). The
	 * server's body yaw lags up to 50 degrees behind the look as a player's does (a player looking sideways kept the wings
	 * where the body had faced, off its back), so that is used only for a player form, which is drawn as a player.
	 */
	public static float drawnYaw(Player player, MorphRef main) {
		return main.isPlayer() ? player.getBodyYaw() : player.getLocation().getYaw();
	}

	private static boolean stands(double[][] box) {
		return box[1][1] - box[0][1] >= box[1][2] - box[0][2];
	}

	/** Each main axis's lent axis: a lying torso on a standing one swaps height and length (the mod's Graft.axes). */
	private static int[] axes(double[][] own, double[][] main) {
		return stands(own) == stands(main) ? new int[] {0, 1, 2} : new int[] {0, 2, 1};
	}

	/** How much to scale a dragon's parts for the main torso: the cube root of the torsos' volumes' ratio (the mod's Graft.fit). */
	static double fit(double[][] own, double[][] main) {
		int[] from = axes(own, main);
		double ratio = 1;
		for (int axis = 0; axis < 3; axis++) {
			ratio *= Math.max(0.01, main[1][axis] - main[0][axis]) / Math.max(0.01, own[1][from[axis]] - own[0][from[axis]]);
		}
		return Math.cbrt(ratio);
	}

	/**
	 * The client mod's Graft.place, in item space (the face towards -z, the back +z, blocks from the feet): where a part
	 * joining its own body at {@code joint} goes on the main body. Each axis keeps its share of the torso (past the torso,
	 * its distance, times {@code scale}); a lying torso maps onto a standing one as if it stood up (front up, top to the
	 * back), so a wolf's tail joins a person at the small of the back. Then by kind: rods keep their share of the whole
	 * body's height (a blaze's spread from head to feet); wings go on a standing body's back near the middle, at the
	 * shoulder blades, standing clear of it, and on a lying body's flanks, high up (on its back they hid in it); a tail
	 * goes to the back, set into it a little.
	 */
	static double[] place(double[][] own, double[][] main, double[][] ownAll, double[][] mainAll, double[] joint, String kind, double scale) {
		int[] from = axes(own, main);
		boolean mainStands = stands(main);
		boolean[] flip = new boolean[3];
		if (from[1] == 2) {
			if (mainStands) flip[1] = true; // lying -> standing: front up, top to the back
			else flip[2] = true; // standing -> lying: top forward, back up
		}
		double[] at = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			int a = from[axis];
			double lo = own[0][a], hi = own[1][a], j = joint[a], mlo = main[0][axis], mhi = main[1][axis];
			if (hi - lo < 0.01) {
				double off = (j - (lo + hi) / 2) * scale;
				at[axis] = (mlo + mhi) / 2 + (flip[axis] ? -off : off);
			} else if (j < lo) {
				at[axis] = flip[axis] ? mhi + (lo - j) * scale : mlo - (lo - j) * scale;
			} else if (j > hi) {
				at[axis] = flip[axis] ? mlo - (j - hi) * scale : mhi + (j - hi) * scale;
			} else {
				double share = (j - lo) / (hi - lo);
				at[axis] = mlo + (flip[axis] ? 1 - share : share) * (mhi - mlo);
			}
		}
		switch (kind) {
			case "rods" -> {
				double ownHeight = ownAll[1][1] - ownAll[0][1];
				double share = ownHeight < 0.01 ? 0.5 : Math.clamp((joint[1] - ownAll[0][1]) / ownHeight, 0, 1);
				at[1] = mainAll[0][1] + share * (mainAll[1][1] - mainAll[0][1]);
			}
			case "wing" -> {
				double w = main[1][0] - main[0][0], h = main[1][1] - main[0][1];
				if (mainStands) {
					at[0] = Math.clamp(at[0], main[0][0] + 0.2 * w, main[1][0] - 0.2 * w);
					at[2] = Math.max(at[2], main[1][2] + WING_CLEAR);
					at[1] = Math.max(at[1], main[0][1] + 0.92 * h);
				} else {
					boolean left = at[0] < (main[0][0] + main[1][0]) / 2;
					at[0] = left ? Math.min(at[0], main[0][0] - WING_CLEAR) : Math.max(at[0], main[1][0] + WING_CLEAR);
					at[1] = Math.max(at[1], main[0][1] + 0.67 * h);
				}
			}
			case "tail" -> at[2] = main[1][2] - TAIL_SET_IN;
			default -> {}
		}
		return at;
	}

	private static double[][] scale(double[][] box, double by) {
		return new double[][] {{box[0][0] * by, box[0][1] * by, box[0][2] * by}, {box[1][0] * by, box[1][1] * by, box[1][2] * by}};
	}

	/** The vanilla skull for {@code form} as an item (a player form's with its skin), or null when there is none. */
	private @Nullable ItemStack skullItem(MorphRef form) {
		Material type = skullType(form);
		if (type == null) return null;
		ItemStack item = new ItemStack(type);
		if (form.isPlayer() && form.skin() != null && item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta meta) {
			var profile = plugin.getServer().createProfile(form.skin().profileId(), form.skin().name());
			profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", form.skin().textureValue(), form.skin().textureSignature()));
			meta.setPlayerProfile(profile);
			item.setItemMeta(meta);
		}
		return item;
	}

	/**
	 * One head display following the owner. The model is moved so its neck point ({@code anchor}, item space) is the
	 * display's origin: the head sits on the neck, and nods about it. {@code skull}: a vanilla skull, which the game
	 * draws facing backwards, so it is turned half way round. {@code audience}: {@link #FOR_PACK}, {@link #FOR_NO_PACK}
	 * or {@link #FOR_ALL} (players without the mod).
	 */
	private ItemDisplay display(Player owner, ItemStack item, Location at, float scale, Vector anchor, boolean skull, byte audience) {
		// the item is drawn turned half way round inside the display: its -z face looks the display's way
		Vector3f shift = new Vector3f((float) anchor.getX() * scale, (float) -anchor.getY() * scale, (float) anchor.getZ() * scale);
		Quaternionf turn = skull ? new Quaternionf().rotateY((float) Math.PI) : new Quaternionf();
		return display(owner, item, at, scale, shift, turn, audience);
	}

	/** One display following the owner: the model moved by {@code shift} (display space) and turned by {@code turn}. */
	private ItemDisplay display(Player owner, ItemStack item, Location at, float scale, Vector3f shift, Quaternionf turn, byte audience) {
		ItemDisplay display = owner.getWorld().spawn(at, ItemDisplay.class, d -> {
			d.setPersistent(false);
			d.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
			d.setItemStack(item);
			d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
			d.setBillboard(Display.Billboard.FIXED);
			d.setTeleportDuration(GLIDE_TICKS);
			d.setTransformation(new Transformation(shift, turn, new Vector3f(scale), new Quaternionf()));
		});
		display.getPersistentDataContainer().set(PACK_KEY, PersistentDataType.BYTE, audience);
		owner.hideEntity(plugin, display);
		String mode = plugin.settings().multimorphVanillaHeads;
		for (Player viewer : plugin.getServer().getOnlinePlayers()) {
			if (viewer != owner && !canSee(viewer, mode, audience)) viewer.hideEntity(plugin, display);
		}
		return display;
	}

	/**
	 * Players with the mod draw multimorphs themselves. The pack's models go to players who have the pack (in "auto",
	 * those whose game loaded it; in "pack", everyone), skulls to the rest, and a skull that is the only head there is
	 * to everyone.
	 */
	private boolean canSee(Player viewer, String mode, byte audience) {
		if (plugin.channel().isModded(viewer)) return false;
		if (audience == FOR_ALL) return true;
		boolean hasPack = mode.equals("pack") || (mode.equals("auto") && packLoaded.contains(viewer.getUniqueId()));
		return (audience == FOR_PACK) == hasPack;
	}

	/** Whether {@code viewer} is shown the resource pack's models (no mod, and the pack, as {@code vanilla-heads} decides). */
	public boolean seesPack(Player viewer) {
		String mode = plugin.settings().multimorphVanillaHeads;
		return !mode.equals("off") && canSee(viewer, mode, FOR_PACK);
	}

	private boolean canSee(Player viewer, String mode, ItemDisplay display) {
		Byte audience = display.getPersistentDataContainer().get(PACK_KEY, PersistentDataType.BYTE);
		return canSee(viewer, mode, audience == null ? FOR_PACK : audience);
	}

	/** A viewer's client mod finished its handshake, or its resource pack loaded: show or hide every head for it. */
	public void refreshViewer(Player viewer) {
		if (plugin.skin() != null) plugin.skin().refreshViewer(viewer);
		String mode = plugin.settings().multimorphVanillaHeads;
		for (Map.Entry<UUID, Shown> e : displays.entrySet()) {
			if (e.getKey().equals(viewer.getUniqueId())) continue;
			for (ItemDisplay d : e.getValue().all()) {
				if (d == null) continue;
				if (canSee(viewer, mode, d)) viewer.showEntity(plugin, d);
				else viewer.hideEntity(plugin, d);
			}
		}
	}

	private void removeDisplay(UUID owner) {
		Shown shown = displays.remove(owner);
		if (shown != null) shown.remove();
	}

	public void removeAll() {
		for (UUID id : List.copyOf(displays.keySet())) removeDisplay(id);
	}

	@EventHandler
	public void onPack(PlayerResourcePackStatusEvent event) {
		if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
			packLoaded.add(event.getPlayer().getUniqueId());
			refreshViewer(event.getPlayer());
		}
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		refreshViewer(event.getPlayer());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		removeDisplay(id);
		if (helmets.remove(id) != null) plugin.disguises().setHeadOverride(event.getPlayer(), null);
		borrowed.remove(id); // only from memory: it stays saved with the player (PlayerMorphData.borrowed)
		packLoaded.remove(id);
	}
}
