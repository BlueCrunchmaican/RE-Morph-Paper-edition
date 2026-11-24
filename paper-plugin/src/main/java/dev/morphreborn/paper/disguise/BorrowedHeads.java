package dev.morphreborn.paper.disguise;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.nbt.Snbt;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
 * <p>
 * A shulker body keeps what it is lent inside its shell, a surprise in the box (my design): the lent head takes
 * the place of the shulker's own little head, at about its size, hidden while the shell is shut and popping out as the
 * lid lifts around it; a creature's ears come along, and nothing else it lends is shown, since wings or a tail would
 * stick out of the shut box. Held to a block ({@link Shell}) the shell is drawn square in its block and turned onto the
 * face it holds on to, and the head turns in that face's frame (see {@link #tickShell}).
 * <p>
 * A giant that borrows a player form shows nothing here: its disguise is that player, a giant's size ({@link GiantPlayer}).
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
	 * its own model, under <mob>_baby (head/wolf_baby). {@code tilt}: how far down (radians) a body's head hangs by its own pose, as
	 * the mod draws it, not by the look: the ender dragon's, 45 degrees; 0 for every other body, whose head follows the look.
	 */
	record Head(@Nullable Vector neck, double height, double scale, Vector anchor, Vector size, double @Nullable [][] crown,
			@Nullable Frame frame, double @Nullable [][] torso, double @Nullable [][] all, @Nullable Ears ears, List<Part> parts, boolean lendsHead,
			String model, boolean baby, double @Nullable [][] opaque, double @Nullable [] reach, double tilt) {}

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
			List.of(), true, "player", false, null, null, 0);
	/** Blocks borrowed ears reach into the head they go on (the client mod's EAR_SET_IN). */
	private static final double EAR_SET_IN = 0.0625;
	/** Blocks a borrowed tail is set into the body it joins (the client mod's Graft.SET_IN). */
	private static final double TAIL_SET_IN = 0.03;
	/** Blocks borrowed wings stand clear of the body (the client mod's Graft.CLEAR). */
	private static final double WING_CLEAR = 0.04;
	/** Where on a standing torso, from its bottom, a tail's root is at lowest, as a share of its height (the client mod's Graft.TAIL_LOW). */
	private static final double TAIL_LOW = 0.15;
	/** How far below the pivot they turn on a breeze's rods' middle is, in blocks (the client mod's LentHeadSize.RODS_MIDDLE_BELOW). */
	static final double RODS_MIDDLE_BELOW = 0.0913;

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
	/**
	 * A skull's head in the same frame: the middle of its 8-pixel cube (half a block hanging from the display's origin), the
	 * cube about it, and the whole head with its hat layer (twice SKULL_ANCHOR's reach back). In a shulker's shell a
	 * skull goes by these (see {@link #spawnInShell}).
	 */
	private static final Vector SKULL_MIDDLE = new Vector(0, -0.25, 0), SKULL_WHOLE = new Vector(0.5625, 0.5625, 0.5625);
	private static final double[][] SKULL_CROWN = {{-0.25, -0.25, -0.25}, {0.25, 0.25, 0.25}};
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
	 * order of the lent creature's parts. Any may be missing. {@code pin}: put in a shulker body's shell (no parts there),
	 * null on any other body.
	 */
	private record Shown(@Nullable ItemDisplay pack, @Nullable ItemDisplay skull, @Nullable ItemDisplay ears, List<ItemDisplay> parts, @Nullable Pin pin) {
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

	/**
	 * Displays put in a shulker body's shell: the block and face they were put in, whether the shell was free (no hold),
	 * the head's yaw (in the face's frame) they show, and each display's own transformation before the turn. A display
	 * turns only by its yaw and pitch, which can't lay a head on a wall, so the whole turn goes into the transformation
	 * instead (yaw and pitch 0), and is turned again as the head turns. Held in another block or on another face, they
	 * are put there afresh: the viewers' games jump the shell there and slide it in (Shulker.setPos), which a glide across
	 * the gap would not match. A free shell's follow the player (a passenger's shell is never snapped to a block).
	 */
	private static final class Pin {
		final UUID world;
		final int x, y, z;
		final BlockFace face;
		final boolean free;
		/** The shell's turn onto its face (Shell.turn). */
		final Quaternionf body;
		float yaw;
		final Map<ItemDisplay, Transformation> rest = new IdentityHashMap<>();

		Pin(Location anchor, Shell shell, float yaw, boolean free) {
			this.world = anchor.getWorld().getUID();
			this.x = anchor.getBlockX();
			this.y = anchor.getBlockY();
			this.z = anchor.getBlockZ();
			this.face = shell.face();
			this.free = free;
			this.body = shell.turn();
			this.yaw = yaw;
		}

		boolean holds(Location anchor, Shell shell, boolean free) {
			if (free != this.free || shell.face() != face || !anchor.getWorld().getUID().equals(world)) return false;
			return free || anchor.getBlockX() == x && anchor.getBlockY() == y && anchor.getBlockZ() == z;
		}

		/** The head's turn: the shell's, then the head's yaw about the shell's axis (a display's yaw is a turn by minus it). */
		Quaternionf head() {
			return new Quaternionf(body).rotateY((float) Math.toRadians(-yaw));
		}

		/** The head turned to {@code yaw}: every head display glides to it, as the displays glide to a new spot. */
		void turnHead(float yaw) {
			this.yaw = yaw;
			Quaternionf head = head();
			for (Map.Entry<ItemDisplay, Transformation> e : rest.entrySet()) {
				ItemDisplay d = e.getKey();
				if (!d.isValid()) continue;
				d.setInterpolationDelay(0);
				d.setInterpolationDuration(GLIDE_TICKS);
				d.setTransformation(turned(e.getValue(), head));
			}
		}
	}

	/** {@code t} turned about the display's origin by {@code by}: its shift and its own turn alike. */
	private static Transformation turned(Transformation t, Quaternionf by) {
		return new Transformation(by.transform(new Vector3f(t.getTranslation())), new Quaternionf(by).mul(t.getLeftRotation()), new Vector3f(t.getScale()),
				new Quaternionf(t.getRightRotation()));
	}

	private final Map<UUID, Shown> displays = new HashMap<>();

	/**
	 * The block a shulker body's shell was last seen to snap to (Shulker.setPos), and the tick it got there. The viewers'
	 * games slide a shulker into a new block over {@link #SLIDE_TICKS} (Shulker.getRenderPosition), and the head inside
	 * waits for it: put in the new block at once, it showed there, outside the shell still sliding in after a teleport.
	 */
	private record Cell(UUID world, int x, int y, int z, long since) {
		Cell(Location anchor, long since) {
			this(anchor.getWorld().getUID(), anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ(), since);
		}

		boolean holds(Location anchor) {
			return anchor.getWorld().getUID().equals(world) && anchor.getBlockX() == x && anchor.getBlockY() == y && anchor.getBlockZ() == z;
		}
	}

	private final Map<UUID, Cell> cells = new HashMap<>();
	/** Which players have been swimming long enough to count (the pose flaps at the surface). */
	private final PoseLatch swimming = new PoseLatch();
	/** Ticks a viewer's game slides a shulker into the block it moved to (Shulker.setPos: clientSideTeleportInterpolation). */
	private static final int SLIDE_TICKS = 6;
	/** Degrees a shulker body's head turns before its displays are turned after it (a head-look packet's step is 1.4). */
	private static final float HEAD_STEP = 1F;
	/** A shulker body with nowhere to hold on, as its disguise draws it: on the floor, shut (a free shulker's own). */
	private static final Shell FREE = new Shell(BlockFace.DOWN, 0);
	/**
	 * How much bigger than the shulker's own 6-pixel head a lent head's big blocks are drawn in the shell, at least: about
	 * 6.4 pixels. Players without the mod always see the shulker's own head (Lib's Disguises draws it), so it is covered.
	 */
	private static final double INNER_COVER = 6.4 / 6;
	/** Blocks (at a shulker's size 1) a head in the shell keeps inside the shut shell's sides, so it never shows through. */
	private static final double SHELL_CLEARANCE = 1 / 32.0;
	/**
	 * Players whose game loaded RE:Morph's resource pack (any pack, when no appearance.resource-pack-ids are set). Read off the
	 * main thread too, by the arm look's packet rewriter.
	 */
	private final Set<UUID> packLoaded = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
				if (h.has("whole") && h.get("whole").getAsBoolean()) whole.add(e.getKey());
				heads.put(e.getKey(), read(e.getKey(), h));
			}
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not read morph-heads.json; multimorph heads won't show to players without the mod.", e);
		}
	}

	/** One entry of morph-heads.json (also read by the tests, which size heads by the real table). */
	static Head read(String key, JsonObject h) {
		// no neck: parts only, no body for a borrowed head. (The ender dragon has one since 2026-11-17: its own head is where a
		// borrowed one goes; it still lends its parts and no head, lendsHead false.)
		Vector neck = h.has("neck") ? vector(h.getAsJsonArray("neck")) : null;
		Vector anchor = h.has("anchor") ? vector(h.getAsJsonArray("anchor")) : new Vector();
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
		return new Head(neck, h.get("height").getAsDouble(), h.get("scale").getAsDouble(), anchor, size, crown, frame, torso, all, ears,
				List.copyOf(parts), lendsHead, key.substring(key.indexOf(':') + 1), key.endsWith(BABY),
				h.has("opaque") ? box(h.getAsJsonArray("opaque")) : null, h.has("reach") ? reach(h.getAsJsonArray("reach")) : null,
				h.has("tilt") ? h.get("tilt").getAsDouble() : 0);
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

	/** {up, down, out} from a JSON array of three (a head's reach, model blocks). */
	private static double[] reach(JsonArray a) {
		return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
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
		// A giant drawn as the player it borrows from is that player's disguise (GiantPlayer): it becomes the giant again,
		// another player, or the player in place of the giant now. (A new body's disguise is built as it transforms.)
		MorphRef main = plugin.morphs().activeForm(player);
		if (GiantPlayer.applies(main, before) || GiantPlayer.applies(main, form)) plugin.morphs().refreshDisguise(player);
		// players with the mod draw the hybrid themselves: tell them
		var state = plugin.morphs().state(player);
		if (state != null) plugin.channel().broadcastState(player, state);
		// the same body, another borrowed form: its traits change with it (a new body gets them when it has transformed)
		if (state != null && !state.isTransitioning()) plugin.abilities().onFormChanged(player, plugin.morphs().activeForm(player), true);
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
	 * smoothly (a display's interpolation), so a stroke costs one update for each viewer. (A shulker body shows no parts.)
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

	/** Every tick: a head on the neck of each fully transformed multimorph (in a shulker body's shell), and none anywhere else. */
	public void tick() {
		ticks++;
		if (!cells.isEmpty()) cells.keySet().retainAll(borrowed.keySet());
		swimming.retainAll(borrowed.keySet());
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
			// ticked every tick, not inside the short-circuit below, so its hold counts real time
			boolean swimHidden = player != null && swimming.tick(e.getKey(), player.getPose() == org.bukkit.entity.Pose.SWIMMING);
			// a giant borrowing a player form shows no head: its disguise is that player, a giant's size (GiantPlayer)
			boolean show = !mode.equals("off") && player != null && !player.isDead() && main != null && !main.isSelf()
					&& !plugin.morphs().isTransitioning(player) && body != null && (lentHead != null || skullType(lent) != null)
					&& !GiantPlayer.applies(main, lent) && lentShown(player.isInvisible(), player.getGameMode(), player.getPose(), swimHidden);
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
				cells.remove(e.getKey());
				continue;
			}
			if (isShulker(main)) {
				tickShell(e.getKey(), player, main, body, lent, lentHead);
			} else {
				cells.remove(e.getKey());
				Shown shown = displays.get(e.getKey());
				Location neck = neck(player, main, body, isWhole(lent.entityType()), lentHead == null || lentHead.lendsHead());
				List<Placed> partsAt = lentHead == null ? List.of() : partsAt(player, main, body, lent, lentHead);
				// displays put in a shulker's shell are turned in their transformations: any other body's are put on afresh
				if (shown == null || shown.pin() != null || !shown.valid(player, partsAt.size())) {
					removeDisplay(e.getKey());
					displays.put(e.getKey(), spawn(player, main, body, lent, lentHead, neck, partsAt, coverScale(player, main, body, lent, lentHead)));
				} else {
					shown.teleport(neck, partsAt);
				}
				Shown now = displays.get(e.getKey());
				if (now != null) lightWhereBuried(player, now);
			}
			if (lentHead != null) flap(displays.get(e.getKey()), lentHead);
		}
	}

	/**
	 * An item display is lit by the block it stands in, and a block that is solid lets no light in: a lent head on a dragon
	 * on the ground hangs at the surface, its display inside the block below, and was drawn black (2026-11-17, rig B3-08).
	 * A display in such a block takes the light of the block its owner stands in instead; any other keeps
	 * the light of its own place, as every display does.
	 */
	private static void lightWhereBuried(Player owner, Shown shown) {
		Display.Brightness own = null;
		for (ItemDisplay d : shown.all()) {
			if (!d.isValid()) continue;
			Display.Brightness now = d.getBrightness();
			if (!d.getLocation().getBlock().getType().isOccluding()) {
				if (now != null) d.setBrightness(null);
				continue;
			}
			if (own == null) {
				Block at = owner.getLocation().getBlock();
				own = new Display.Brightness(at.getLightFromBlocks(), at.getLightFromSky());
			}
			if (now == null || now.getBlockLight() != own.getBlockLight() || now.getSkyLight() != own.getSkyLight()) d.setBrightness(own);
		}
	}

	/**
	 * Whether the lent head and parts are drawn at all. Not on an invisible player (the body vanishes and the parts would
	 * float), not on a spectator (the game hides them from everyone), and not while the body lies down or swims (asleep, a
	 * crawl, a swim): the head is placed on the standing neck, which floats clear of a body that is lying down. A swim only
	 * counts once it has held (PoseLatch): the pose flaps at a water's surface and each flap would respawn every display.
	 */
	static boolean lentShown(boolean invisible, org.bukkit.GameMode mode, org.bukkit.entity.Pose pose, boolean swimLatched) {
		return !invisible && mode != org.bukkit.GameMode.SPECTATOR && pose != org.bukkit.entity.Pose.SLEEPING && !swimLatched;
	}

	/** Whether {@code main} is a shulker body: only a main body is ever held to a block or hides a head (a borrowed shulker never does). */
	private static boolean isShulker(MorphRef main) {
		return !main.isPlayer() && SHULKER.equals(main.entityType());
	}

	/**
	 * One tick of a multimorph on a shulker body, as players without the mod see it: the lent head takes the place of the
	 * shulker's own little head inside the shell, hidden while the shell is shut and in view while it is open. A vanilla
	 * shulker's head (ShulkerModel) stays where it is as the lid lifts around it, never nods, and turns in the frame of the
	 * face the shell holds on to; the lent head does the same, at about its size (see {@link #spawnInShell}). A creature
	 * that lends its ears and tail brings only its ears, onto the shulker's own head; one that lends only its parts (wings,
	 * fins) brings nothing in.
	 * <p>
	 * Held to a block, the shell is the overlay's (ShulkerLock); a shulker with nowhere to hold on is drawn free, on the
	 * floor and shut, its head turned by the player's own look ({@link #FREE}), as its disguise is. Everything is put by
	 * the block the shell snaps to, not by where the player stands (a passenger's shell is never snapped), and only once
	 * the viewers' games have slid the shell into that block ({@link Cell}).
	 */
	private void tickShell(UUID id, Player player, MorphRef main, Head body, MorphRef lent, @Nullable Head lentHead) {
		if (lentHead != null && !lentHead.lendsHead() && lentHead.ears() == null) {
			removeDisplay(id);
			return;
		}
		Shell held = Shell.of(plugin.morphs().overlay(player));
		boolean free = held == null;
		Shell shell = free ? FREE : held;
		Location look = player.getLocation();
		boolean riding = free && player.isInsideVehicle();
		Location anchor = riding ? look.clone() : anchor(look);
		if (riding) {
			cells.remove(id);
		} else {
			Cell cell = cells.get(id);
			if (cell == null || !cell.holds(anchor)) cells.put(id, cell = new Cell(anchor, ticks));
			if (ticks - cell.since() < SLIDE_TICKS) {
				// the shell is shut while it slides (a move shuts it), and nothing in it shows
				removeDisplay(id);
				return;
			}
		}
		Shown shown = displays.get(id);
		Pin pin = shown == null ? null : shown.pin();
		float yaw = shell.headYaw(look);
		if (Float.isNaN(yaw)) yaw = pin != null ? pin.yaw : 0F; // looking straight along the shell's axis: it keeps its turn
		// a small turn isn't sent: the head stays as it is shown
		if (pin != null && Math.abs(wrapDegrees(yaw - pin.yaw)) <= HEAD_STEP) yaw = pin.yaw;
		double size = sizeOf(player, main, body);
		// every display sits on the shulker's head's middle, on the shell's axis: the head's turn, in their
		// transformations, leaves them there
		Vector middle = headMiddle(main, body, size);
		if (lentHead != null && lentHead.lendsHead()) middle.setY(middle.getY() + wholeLift(main, body, lent, lentHead, size));
		Location at = anchor.clone().add(onShell(middle, shell, size));
		at.setYaw(0F);
		at.setPitch(0F);
		if (shown == null || pin == null || !pin.holds(anchor, shell, free) || !shown.valid(player, 0)) {
			removeDisplay(id);
			displays.put(id, spawnInShell(player, main, body, lent, lentHead, at, size, new Pin(anchor, shell, yaw, free)));
			return;
		}
		shown.teleport(at, List.of());
		if (yaw != pin.yaw) pin.turnHead(yaw);
	}

	private static float wrapDegrees(float degrees) {
		float d = degrees % 360F;
		if (d >= 180F) d -= 360F;
		if (d < -180F) d += 360F;
		return d;
	}

	/**
	 * The middle of the main body's own head's big blocks, in blocks from its feet (+z forward) before any turn. A
	 * shulker's is the middle of its little head in the shell (ShulkerModel's 6-pixel cube, hanging from its pivot 12
	 * pixels up): on the shell's axis, 9 pixels up.
	 */
	private Vector headMiddle(MorphRef main, Head body, double size) {
		double[][] crown = crownOf(body, body.scale() * size);
		// item space faces -z: the body's +z is its -z
		return neckPoint(main, body, size, false, false).add(new Vector(-(crown[0][0] + crown[1][0]) / 2, (crown[0][1] + crown[1][1]) / 2,
				-(crown[0][2] + crown[1][2]) / 2));
	}

	/** {@code n} (blocks from a floor shulker's feet, +z forward, at {@code size}) on the shell turned onto its face. */
	private static Vector onShell(Vector n, Shell shell, double size) {
		double middle = 0.5 * size;
		Vector3f v = shell.turn().transform(new Vector3f((float) n.getX(), (float) (n.getY() - middle), (float) n.getZ()));
		return new Vector(v.x, v.y + middle, v.z);
	}

	/**
	 * The displays in a shulker body's shell for {@code form}, all on the shulker's head's middle ({@code at}) and turned
	 * with its head ({@code pin}): its head (the pack's model, and a vanilla skull for the others) with the middle of its
	 * big blocks there, at the size {@link #innerScale} gives; or an animal's ears on the shulker's own head, kept under
	 * the shut shell's top.
	 */
	private Shown spawnInShell(Player owner, MorphRef main, Head body, MorphRef form, @Nullable Head head, Location at, double size, Pin pin) {
		Vector middle = headMiddle(main, body, size);
		// the shut shell: ShulkerModel's lid and base fill the block (the body's whole box), less a little clearance
		double[][] shell = body.all() != null ? scale(body.all(), size) : new double[][] {{-0.5 * size, 0, -0.5 * size}, {0.5 * size, size, 0.5 * size}};
		double clear = SHELL_CLEARANCE * size;
		double top = shell[1][1] - clear;
		if (head != null && !head.lendsHead()) {
			// the ears' display sits on the head's middle too, and the head's neck point, where ears are put from, is behind
			// and below it (the body's frame before the head's turn is the display's: the face +z)
			Vector neck = neckPoint(main, body, size, false, false);
			Vector3f from = new Vector3f((float) (neck.getX() - middle.getX()), (float) (neck.getY() - middle.getY()), (float) (neck.getZ() - middle.getZ()));
			return new Shown(null, null, ears(owner, main, body, form, head, at, pin, from, top - neck.getY()), List.of(), pin);
		}
		Room room = room(main, body, size);
		double inner = room.inner(), up = room.up(), down = room.down(), round = room.round();
		ItemDisplay pack = null;
		if (head != null) {
			double[][] crown = crownOf(head, 1);
			Vector centre = head.anchor().clone().add(new Vector((crown[0][0] + crown[1][0]) / 2, (crown[0][1] + crown[1][1]) / 2, (crown[0][2] + crown[1][2]) / 2));
			pack = display(owner, packItem("head", head), at, (float) packScale(form, head, room), centre, false, FOR_PACK, pin);
		}
		ItemDisplay skull = null;
		ItemStack skullItem = skullItem(form);
		if (skullItem != null) {
			skull = display(owner, skullItem, at, (float) innerScale(SKULL_CROWN, null, SKULL_CROWN, SKULL_WHOLE, inner, up, down, round), SKULL_MIDDLE, true,
					head == null ? FOR_ALL : FOR_NO_PACK, pin);
		}
		return new Shown(pack, skull, null, List.of(), pin);
	}

	/**
	 * The room in a shulker's shut shell round its little head (blocks): {@code inner} the head's side, {@code up} and
	 * {@code down} from its middle to the shell's top and bottom, {@code round} to its sides, each less a little
	 * clearance (ShulkerModel's lid and base fill the block: the body's whole box).
	 */
	private record Room(double inner, double up, double down, double round) {}

	private Room room(MorphRef main, Head body, double size) {
		Vector middle = headMiddle(main, body, size);
		double[][] shell = body.all() != null ? scale(body.all(), size) : new double[][] {{-0.5 * size, 0, -0.5 * size}, {0.5 * size, size, 0.5 * size}};
		double clear = SHELL_CLEARANCE * size;
		double[][] own = crownOf(body, body.scale() * size);
		double inner = Math.min(own[1][0] - own[0][0], Math.min(own[1][1] - own[0][1], own[1][2] - own[0][2]));
		return new Room(inner, shell[1][1] - clear - middle.getY(), middle.getY() - shell[0][1] - clear,
				Math.min(Math.min(shell[1][0], -shell[0][0]), Math.min(shell[1][2], -shell[0][2])) - clear);
	}

	/**
	 * The pack head's scale in the shell. A creature lent whole (a squid, a tadpole, a guardian) has no head to cover the
	 * shulker's with and reaches well past its big blocks (tentacles, a tail, spikes): held to the little head's room it
	 * shrank inside the shulker's own head and never showed, so it gets the shell's whole height instead
	 * ({@link #wholeLift} puts it in the middle of that).
	 */
	private double packScale(MorphRef form, Head head, Room room) {
		double[][] crown = crownOf(head, 1);
		double[][] cover = head.opaque() != null ? head.opaque() : crown;
		double[] reach = head.reach();
		if (isWhole(form.entityType()) && reach != null) {
			double least = Math.min(cover[1][0] - cover[0][0], Math.min(cover[1][1] - cover[0][1], cover[1][2] - cover[0][2]));
			double k = least > 1e-4 ? INNER_COVER * room.inner() / least : 1;
			if (reach[0] + reach[1] > 1e-4) k = Math.min(k, (room.up() + room.down()) / (reach[0] + reach[1]));
			if (reach[2] > 1e-4) k = Math.min(k, room.round() / reach[2]);
			return k;
		}
		return innerScale(cover, reach, crown, head.size(), room.inner(), room.up(), room.down(), room.round());
	}

	/**
	 * How far up the shell's axis (blocks) a creature lent whole is moved from the little head's middle, so all of it
	 * (body over tentacles) sits in the middle of the shut shell's height; 0 for any other head.
	 */
	private double wholeLift(MorphRef main, Head body, MorphRef form, Head head, double size) {
		double[] reach = head.reach();
		if (!isWhole(form.entityType()) || reach == null) return 0;
		Room room = room(main, body, size);
		double k = packScale(form, head, room);
		return (room.up() - room.down()) / 2 - k * (reach[0] - reach[1]) / 2;
	}

	/**
	 * The scale a lent head is drawn at in a shulker's shell, where the shulker's own head is ({@code inner}: its side,
	 * blocks): just big enough that its big blocks ({@code crown}, model blocks), kept in proportion, hide that head on
	 * every side (INNER_COVER), as players without the mod always see it; but never so big that any of it, turned any
	 * way, reaches out of the shut shell ({@code up}, {@code down}: blocks from the head's middle to the shell's top and
	 * bottom; {@code round}: to its sides), where it would show with the shell shut. The cover is measured on the big
	 * blocks you can see ({@code cover}: without a skin's see-through hat layer, which made a zombie's head smaller than
	 * the shulker's own and left it hidden). How far it reaches is the head pack's measure of every element from the big
	 * blocks' middle ({@code reach}: up, down, out; a tadpole's tail, a squid's tentacles, a piglin's ears); without one
	 * (a vanilla skull) a head bigger than its big blocks ({@code whole}: a snout, horns, ears) is counted as reaching
	 * that much further on every side.
	 */
	private static double innerScale(double[][] cover, double @Nullable [] reach, double[][] crown, Vector whole, double inner, double up, double down,
			double round) {
		double least = Math.min(cover[1][0] - cover[0][0], Math.min(cover[1][1] - cover[0][1], cover[1][2] - cover[0][2]));
		double k = least > 1e-4 ? INNER_COVER * inner / least : 1;
		if (reach != null) {
			if (reach[0] > 1e-4) k = Math.min(k, up / reach[0]);
			if (reach[1] > 1e-4) k = Math.min(k, down / reach[1]);
			if (reach[2] > 1e-4) k = Math.min(k, round / reach[2]);
			return k;
		}
		double w = crown[1][0] - crown[0][0], h = crown[1][1] - crown[0][1], d = crown[1][2] - crown[0][2];
		double ex = w / 2 + Math.max(0, whole.getX() - w), ey = h / 2 + Math.max(0, whole.getY() - h), ez = d / 2 + Math.max(0, whole.getZ() - d);
		if (ey > 1e-4) k = Math.min(k, Math.min(up, down) / ey);
		double sideways = Math.hypot(ex, ez);
		if (sideways > 1e-4) k = Math.min(k, round / sideways);
		return k;
	}

	/**
	 * Where the borrowed head goes: where the main body's own head meets its neck, turned with the body; the head turned
	 * as the player looks. {@code head}: a head goes there (not ears), lifted out of the body's torso as the mod lifts it.
	 */
	private Location neck(Player player, MorphRef main, Head body, boolean centred, boolean head) {
		double size = sizeOf(player, main, body);
		double yaw = Math.toRadians(drawnYaw(player, main));
		Vector n = neckPoint(main, body, size, centred, head);
		// The head display turns about its own neck point, but the body's head, which players without the mod see under it,
		// turns about its joint: move the neck point about the joint by the same look first, nod then turn, as the mod does
		// (HybridMorph.place). Turned about the neck point alone, a giant's head nodded off its own: looking down the giant's
		// face showed above it, looking up its chin below. A creature's body turns with the look, so its turn is mostly 0.
		Vector joint = body.neck().clone().multiply(size);
		// a head that hangs by its own pose (the dragon's, 2026-11-17, rig B3-08) is turned by that, not by the look: the mod
		// turns the borrowed head with the body's head, which a dragon's look does not move
		double pitch = body.tilt() != 0 ? body.tilt() : Math.toRadians(player.getLocation().getPitch());
		double turn = Math.toRadians(player.getLocation().getYaw()) - yaw;
		double dx = n.getX() - joint.getX(), dy = n.getY() - joint.getY(), dz = n.getZ() - joint.getZ();
		double nodY = dy * Math.cos(pitch) - dz * Math.sin(pitch), nodZ = dz * Math.cos(pitch) + dy * Math.sin(pitch);
		n = new Vector(joint.getX() + dx * Math.cos(turn) - nodZ * Math.sin(turn), joint.getY() + nodY,
				joint.getZ() + dx * Math.sin(turn) + nodZ * Math.cos(turn));
		double x = n.getX() * Math.cos(yaw) - n.getZ() * Math.sin(yaw);
		double z = n.getX() * Math.sin(yaw) + n.getZ() * Math.cos(yaw);
		Location at = player.getLocation().add(x, n.getY(), z);
		at.setYaw(player.getLocation().getYaw());
		at.setPitch(body.tilt() != 0 ? (float) Math.toDegrees(body.tilt()) : player.getLocation().getPitch());
		return at;
	}

	/**
	 * Where the main body's own head meets its neck, in blocks from its feet (+z forward) before any turn: from its head's
	 * pivot to the neck point; {@code head}: moved out of the body's torso.
	 */
	private Vector neckPoint(MorphRef main, Head body, double size, boolean centred, boolean head) {
		// the pivot, then the body's own head's neck point from it (item space faces -z: the body's +z is its -z); a
		// creature lent whole (a slime) stands on the middle of the head's big blocks, not at its back, or it sat behind it
		double s = body.scale() * size;
		double back = body.anchor().getZ();
		if (centred) back += body.crown() != null ? (body.crown()[0][2] + body.crown()[1][2]) / 2.0 : -body.size().getZ() / 2.0;
		// a body that is one whole creature (a strider, a squid, a ghast) has its neck point in blocks from its feet already: its anchor is
		// where the item display's pivot sits on the whole body, not an offset to the neck, and adding it put the lent head a body's height
		// over it (rig, 2026-11-21: strider, squid and ghast + zombie, strider + tadpole)
		double lift = !main.isPlayer() && isWhole(main.entityType()) ? 0 : body.anchor().getY() * s;
		Vector n = body.neck().clone().multiply(size).add(new Vector(-body.anchor().getX() * s, lift, -back * s));
		// a head goes on a neck point out of the body's torso, as the client mod puts it (HybridMorph.outOfTorso): a
		// camel's, a llama's, a pig's and a baby goat's heads meet their necks inside their chests, and a borrowed head
		// set there sank into the body for players without the mod. Ears go on the head itself and aren't moved.
		if (head && body.torso() != null && (main.isPlayer() || !isWhole(main.entityType()))) outOfTorso(n, scale(body.torso(), size));
		return n;
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
		if (head == null || body.size().lengthSquared() == 0 || head.size().lengthSquared() == 0) return 1;
		boolean whole = !form.isPlayer() && isWhole(form.entityType());
		if (heads.get(main.entityType()) != null && isWhole(main.entityType())) {
			return standInCap(whole, main.entityType(), bareSide(body) * body.scale() * sizeOf(owner, main, body), coreSide(head) * head.scale() * sizeOf(owner, form, head));
		}
		return cover(body, sizeOf(owner, main, body), head, sizeOf(owner, form, head), whole, !main.isPlayer() && GROWS_HEADS.contains(main.entityType()));
	}

	/** A body with no head part under this many blocks across holds a lent head to 1 times its core, not BIG_HEAD (the mod's LentHeadSize.TINY_HEADLESS). */
	private static final double TINY_HEADLESS = 0.4965;

	/**
	 * What a lent head is drawn at on a body with no head part (a silverfish, an endermite, a slime...), as a share of its own size: held to
	 * the body's core as a stand-in head, as the mod does (HybridMorph.place, LentHeadSize.headCap), never grown. A giant's head on a
	 * silverfish was a 3 block cube hiding it (rig, 2026-11-21): there was no cap on such a body at all. A ghast, a happy ghast and an elder
	 * guardian are held to the mod's stand-in, half the cube, as a giant is: a small head grows up to it, a head over 1.5 times it is cut to
	 * that, and a creature lent whole is exactly as big as it (the mod's rule; the pack drew every head there at its own size until round 5).
	 */
	static double standInCap(boolean whole, String type, double mainCore, double lentCore) {
		// the mod's stand-in on these three is half the cube, and it grows a smaller head up to it (HybridMorph.place, LentHeadSize.headlessStandIn,
		// hugeCube); the pack drew a zombie's head at its own 0.5 block on the ghast's 4 block cube, a third of the mod's 1.6 (rig, 2026-11-21,
		// round 5). Both ways, as the mod does it: a giant's 3 block head on the elder guardian's 1.175 stand-in is held to 1.5 times it, and a
		// ghast lent whole to a ghast is shrunk to 2, not drawn at 4 (the 1.5 times rule). Never over STAND_IN_GROW_MAX, so a head with next to no
		// size cannot ask for a display filling the screen
		if (type.equals("minecraft:ghast") || type.equals("minecraft:happy_ghast") || type.equals("minecraft:elder_guardian")) {
			return Math.min(STAND_IN_GROW_MAX, modScale(whole, true, mainCore * HUGE_HEADLESS_SHARE, lentCore, Double.MAX_VALUE, BIG_HEAD));
		}
		// the mod's core for a silverfish is its three big segments (6 x 4 x 8 pixels, 0.36 block); the table's crown, 0.61, is all seven, which
		// put it over TINY_HEADLESS and let a giant's head be 1.5 times the core, 2.8 times the mod's (rig, 2026-11-21, round 5)
		if (type.equals("minecraft:silverfish")) mainCore *= SILVERFISH_BIG_BLOCKS;
		return Math.min(1, modScale(whole, false, mainCore, lentCore, Double.MAX_VALUE, mainCore < TINY_HEADLESS ? 1 : BIG_HEAD));
	}

	/** The most a head is grown to fill a ghast's or an elder guardian's stand-in (the same limit as PARTS_MAX): no real head is 24 times smaller than its stand-in. */
	private static final double STAND_IN_GROW_MAX = 24;

	/** The share of a ghast-sized cube the mod's stand-in head is (LentHeadSize.HUGE_HEADLESS_SHARE). */
	private static final double HUGE_HEADLESS_SHARE = 0.5;

	/** A silverfish's big blocks' core over the table's crown of all its segments: 0.36 / 0.61. */
	private static final double SILVERFISH_BIG_BLOCKS = 0.36 / 0.61;

	/**
	 * The bodies whose own head is so big, or so unlike the borrowed one, that the client mod scales a small borrowed head
	 * up to theirs (HybridMorph.place): at its own size a cow's head was a speck on a dragon's neck or a giant's shoulders.
	 */
	private static final Set<String> GROWS_HEADS = Set.of("minecraft:giant", "minecraft:ender_dragon");

	/**
	 * The size (as a share of its own) the client mod draws a borrowed head at, by the width of the big blocks of the body's
	 * head ({@code mainCore}) and of the borrowed one ({@code lentCore}), both in blocks as drawn (HybridMorph.place):
	 * <ul>
	 * <li>on any body, a creature lent whole (a ghast, a slime) no bigger than the head it stands in for: a ghast's 4.5
	 * blocks on a person's head buried the body and the ground under its tentacles;</li>
	 * <li>on a giant or a dragon ({@code grows}), a small head scaled up to the body's head, a creature lent whole exactly as
	 * big as it (a squid is no speck on a giant), and any other head at most BIG_HEAD times it (2026-11-17: a
	 * ghast on a dragon was as big as the whole dragon, hiding it);</li>
	 * <li>on any other body, a head at most BIG_HEAD times the body's (a giant's on a person), as it is otherwise.</li>
	 * </ul>
	 */
	static double modScale(boolean whole, boolean grows, double mainCore, double lentCore) {
		return modScale(whole, grows, mainCore, lentCore, Double.MAX_VALUE);
	}

	/**
	 * {@link #modScale(boolean, boolean, double, double)} held to BIG_HEAD times the main's head on every side too, not only by the cube
	 * roots (the client mod's LentHeadSize.grow, 2026-11-20): {@code axisFit}, {@link #axisFit}. Not for a creature lent whole.
	 */
	static double modScale(boolean whole, boolean grows, double mainCore, double lentCore, double axisFit) {
		return modScale(whole, grows, mainCore, lentCore, axisFit, BIG_HEAD);
	}

	/** {@link #modScale(boolean, boolean, double, double, double)} held to {@code cap} times the main's head instead of BIG_HEAD (1 on a tiny body with no head part). */
	static double modScale(boolean whole, boolean grows, double mainCore, double lentCore, double axisFit, double cap) {
		if (mainCore <= 1e-3 || lentCore <= 1e-3) return 1;
		if (whole) return grows ? mainCore / lentCore : Math.min(1, mainCore / lentCore);
		double k;
		if (grows) k = Math.min(Math.max(1, mainCore / lentCore), cap * mainCore / lentCore);
		else k = lentCore > cap * mainCore ? cap * mainCore / lentCore : 1;
		return Math.min(k, cap * axisFit);
	}

	/**
	 * How many times bigger than the main's head a borrowed one may be along its tightest side (the client mod's LentHeadSize.axisFit):
	 * the least, over the x, y and z sides of the two boxes of big blocks, of the main's over the borrowed one's, a main side under a
	 * quarter of its longest counting as that quarter. {@link Double#MAX_VALUE} for none.
	 */
	static double axisFit(double[][] main, double[][] own) {
		return axisFit(main, own, FLAT_FLOOR);
	}

	/** A main side under this share of its longest counts as that share in {@link #axisFit} (the mod's LentHeadSize.FLAT_FLOOR). */
	static final double FLAT_FLOOR = 0.25, PITCHED_FLAT_FLOOR = 0.5;
	// PITCHED_FLAT_FLOOR is the mod's for a hoglin's or zoglin's level head. The pack's head table still holds the box round the tilted head
	// (tools/gen_head_pack.py has to be run again for a level one), so nothing here uses it yet.

	/** {@link #axisFit(double[][], double[][])} with {@code floor} as the share of the longest side a flat side counts as (the mod's LentHeadSize.axisFit). */
	static double axisFit(double[][] main, double[][] own, double floor) {
		double[] m = new double[3], o = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			m[axis] = main[1][axis] - main[0][axis];
			o[axis] = own[1][axis] - own[0][axis];
		}
		double longest = Math.max(m[0], Math.max(m[1], m[2])), fit = Double.MAX_VALUE;
		if (longest <= 0.01) return Double.MAX_VALUE;
		for (int axis = 0; axis < 3; axis++) {
			if (o[axis] > 0.005) fit = Math.min(fit, Math.max(m[axis], floor * longest) / o[axis]);
		}
		return fit;
	}

	/** {@link #coverScale} from the two heads' numbers and sizes ({@code bodySize}, {@code headSize}: how big each is drawn against its table). */
	static double cover(Head body, double bodySize, Head head, double headSize, boolean whole, boolean grows) {
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
		// the main's is its bare head (bareSide): the mod holds a lent head to 1.5 times the head it shows, not the hat layer round it
		double mainCore = bareSide(body) * sB, lentCore = coreSide(head) * sH;
		if (mainCore <= 1e-3 || lentCore <= 1e-3) return whole ? fit : Math.max(1, fit);
		// the size the client mod draws it at. A giant's or a dragon's grows small heads to its own: this used to take the
		// head's own size for the mod's there (2026-11-17, rig B3-08: a ghast on a giant was 1.28 times wider in the mod)
		double mod = modScale(whole, grows, mainCore, lentCore, axisFit(bareCrown(body, sB), crownOf(head, sH)));
		// a head the mod holds below its own size (a giant's on a person, a llama's tall column) is drawn at that, not grown to cover
		if (!whole && mod < 1 - 1e-9) return mod;
		// what it takes to hide the body's head: on every axis of the whole head, and (not for a creature lent whole,
		// which sits on the middle of the head) its big blocks holding the body's from where both meet their necks: a
		// zombie's face showed in front of a llama's neck, which is 6 pixels deep
		double cover = whole ? fit : Math.max(fit, crownFit(crownOf(body, sB), crownOf(head, sH)));
		// My call (7.5.0): covering is worth at most COVER_MAX times the mod's size; past it the body's head may
		// show (a cow's head on a llama, a panda's on a camel were two to three times their size, to hide the long neck
		// those heads hold). Unless at that size the head would still sit inside the body's on every side, where it would
		// vanish (a baby turtle's on a cow; the wolf's in the iron golem's, why covering began): that one is covered fully.
		// Not by size alone: a frog's head is small but flat and wide, and covering a zombie's grew it to three times.
		if (holdLeast(crownOf(body, sB), crownOf(head, sH), whole) < COVER_MAX * mod) cover = Math.min(cover, COVER_MAX * mod);
		// A giant's or a dragon's head is drawn at the mod's size, give or take the 4 percent a cover leaves: the pack and the mod
		// agree there (2026-11-17, rig B3-08). At COVER_MAX a ghast on a dragon was 1.5
		// blocks in the pack against the mod's 1.
		if (grows) return Math.max(mod, Math.min(cover, GROWN_COVER_MAX * mod));
		return whole ? cover : Math.max(mod, cover);
	}

	/** On a giant or a dragon, covering the body's head is worth at most this many times the mod's size (the 4 percent margin of a fit). */
	private static final double GROWN_COVER_MAX = 1.04;

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

	/** Covering a body's head is worth at most this many times the size the mod draws a head at (my call). */
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

	/**
	 * The head a borrowed one is held to: the body's own, bare (the box of what is drawn without the 0.5 pixel hat overlay of a humanoid,
	 * {@code opaque} in the table), as the mod measures it (HybridMorph.restHeadBox). A zombie's is 0.5, not the 0.5625 its crown holds
	 * with the overlay; the 12 percent between them was the difference between a giant's lent head here and in the mod.
	 */
	private static double[][] bareCrown(Head h, double scale) {
		return h.opaque() != null ? scale(h.opaque(), scale) : crownOf(h, scale);
	}

	/** {@link #coreSide} of the bare head, see {@link #bareCrown}. */
	private static double bareSide(Head h) {
		double[][] c = h.opaque() != null ? h.opaque() : h.crown();
		if (c == null) return coreSide(h);
		return Math.cbrt(Math.max(0, (c[1][0] - c[0][0]) * (c[1][1] - c[0][1]) * (c[1][2] - c[0][2])));
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
	 * without the pack its skull, if the game has one (the dragon's), as before it lent its parts. Not for a shulker body,
	 * which keeps what it is lent in its shell ({@link #spawnInShell}).
	 */
	private Shown spawn(Player owner, MorphRef main, Head body, MorphRef form, @Nullable Head head, Location at, List<Placed> partsAt, double cover) {
		List<ItemDisplay> parts = new java.util.ArrayList<>();
		for (int i = 0; head != null && i < partsAt.size(); i++) {
			Placed p = partsAt.get(i);
			parts.add(display(owner, packItem("part/" + head.model() + "/" + head.parts().get(i).name()), p.at(), p.scale(), new Vector3f(), new Quaternionf(), FOR_PACK,
					null, false));
		}
		if (head != null && !head.lendsHead()) {
			ItemDisplay ears = head.ears() == null ? null : ears(owner, main, body, form, head, at, null, new Vector3f(), Double.NaN);
			ItemStack skullItem = skullItem(form);
			// no cover here: this head sits on no body's head (the dragon's lends its parts, the mod and the pack draw no head for it), and
			// the dragon-neck data gave it a size that made cover() shrink its skull to 0.84 (it was full size before; fix round, 2026-11-17)
			ItemDisplay skull = skullItem == null ? null : display(owner, skullItem, at, SKULL_SCALE, SKULL_ANCHOR, true, FOR_NO_PACK, null);
			return new Shown(null, skull, ears, List.copyOf(parts), null);
		}
		ItemDisplay pack = null;
		if (head != null) {
			pack = display(owner, packItem("head", head), at, (float) (head.scale() * sizeOf(owner, form, head) * cover), head.anchor(), false, FOR_PACK, null);
		}
		ItemDisplay skull = null;
		ItemStack skullItem = skullItem(form);
		// a skull with no pack model beside it (player forms, the dragon) is the only head there is: everyone sees it
		// (a player's at the size coverScale measured it: a disguised player's head, PLAYER.scale of the model's)
		double skullScale = SKULL_SCALE * (form.isPlayer() ? PLAYER.scale() : 1) * cover;
		if (skullItem != null) skull = display(owner, skullItem, at, (float) skullScale, SKULL_ANCHOR, true, head == null ? FOR_ALL : FOR_NO_PACK, null);
		return new Shown(pack, skull, null, List.copyOf(parts), null);
	}

	/**
	 * Borrowed ears on the main body's own head, as the client mod places them (HybridMorph.placeEars): at the size the
	 * main head's width asks for (half to twice their own), where they stand on their own head as a share of it, their
	 * middle over the head from front to back, and their lowest third (at least EAR_SET_IN) in it. The display sits on
	 * the neck point, as a head would, so the ears turn and nod with the head. None on a body that is all head (a slime).
	 * <p>
	 * In a shulker's shell ({@code pin}) the display sits elsewhere on the head, and the neck point is {@code from} it
	 * (display space); and the ears reach no higher than {@code maxTop} (item space from the neck point: the shut shell's
	 * top), smaller if they must, or they would show through the lid. Elsewhere {@code from} is 0 and {@code maxTop} NaN.
	 */
	private @Nullable ItemDisplay ears(Player owner, MorphRef main, Head body, MorphRef form, Head head, Location at, @Nullable Pin pin, Vector3f from,
			double maxTop) {
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
		// the client mod's LentHeadSize.earScale: never twice the head (an armadillo's at 2.5 were), but on a giant or a dragon, and
		// not under 0.15 (a guardian's plates on a parrot's head)
		boolean hugeEars = !main.isPlayer() && PARTS_GROW.contains(main.entityType());
		double k = earScale(wB / wA, hugeEars);
		double tall = box[1][1] - box[0][1];
		// and the tallest ear no taller than three quarters of the head it stands on (the mod's LentHeadSize.earCap)
		k = earCap(k, tall, mine[1][1] - mine[0][1], hugeEars);
		if (!Double.isNaN(maxTop) && tall > 1e-4) {
			// their top is top - sink + k * tall, sink below: k * tall - EAR_SET_IN and 2/3 k * tall each fit under maxTop
			double room = Math.max(0, maxTop - top);
			k = Math.min(k, Math.min((room + EAR_SET_IN) / tall, 1.5 * room / tall));
		}
		// the borrowed head's top middle goes on the main head's top middle, (cx, top, cz)
		double tx = (core[0][0] + core[1][0]) / 2, ty = core[1][1], tz = (core[0][2] + core[1][2]) / 2;
		double middle = cz + k * ((box[0][2] + box[1][2]) / 2 - tz);
		double front = mine[0][2] + EAR_SET_IN, back = mine[1][2] - EAR_SET_IN;
		double dz = front < back ? Math.clamp(middle, front, back) - middle : 0;
		double bottom = top + k * (box[0][1] - ty);
		double sink = earSink(k * (box[1][1] - box[0][1]));
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
		shift.add(from);
		return display(owner, packItem("ears", head), at, (float) (ea.scale() * sizeA * k), shift, turn, FOR_PACK, pin, true);
	}

	/** How far an ear {@code height} tall is sunk into the head: the client mod's LentHeadSize.earSink (an ear under two pixels keeps half of itself above it). */
	static double earSink(double height) {
		double third = height / 3;
		return third >= EAR_SET_IN ? third : Math.min(EAR_SET_IN, height / 2);
	}

	/** The size ears are drawn at on the body's head, as a share of their own: the client mod's LentHeadSize.earScale. */
	static double earScale(double headRatio, boolean huge) {
		return Math.max(0.15, huge ? headRatio : Math.min(2, headRatio));
	}

	/** The tallest a lent ear is drawn, as a share of the head it stands on (the client mod's LentHeadSize.EAR_HEIGHT_SHARE). */
	private static final double EAR_HEIGHT_SHARE = 0.75;

	/**
	 * {@code size} held so an ear {@code earHeight} tall at its own size is at most EAR_HEIGHT_SHARE of {@code headHeight} (all of it on a giant, a
	 * dragon or a ghast, {@code huge}): the client mod's LentHeadSize.earCap.
	 */
	static double earCap(double size, double earHeight, double headHeight, boolean huge) {
		if (earHeight <= 0.001 || headHeight <= 0.001) return size;
		double most = (huge ? 1 : EAR_HEIGHT_SHARE) * headHeight;
		return earHeight * size > most ? most / earHeight : size;
	}

	/** A torso narrower than this puts wings at its side edges, not a fifth in (the mod's LentHeadSize.THIN_TORSO). */
	private static final double THIN_TORSO = 0.35;

	/** The inset eases in over this width under THIN_TORSO (the mod's LentHeadSize.WING_RAMP). */
	private static final double WING_RAMP = 0.05;

	/** How far in from each side edge a standing torso {@code width} across puts its wings' roots: the mod's LentHeadSize.wingInset. */
	static double wingInset(double width) {
		if (width >= THIN_TORSO) return 0.2 * width;
		return width <= THIN_TORSO - WING_RAMP ? 0 : 0.2 * width * (width - (THIN_TORSO - WING_RAMP)) / WING_RAMP;
	}

	/** A lent wing, fin or stinger is at least this share of the main torso's longest side across (the mod's LentHeadSize.PART_FLOOR). */
	private static final double PART_FLOOR = 0.15;

	/** {@code scale} raised so a part {@code extent} across is at least PART_FLOOR of {@code torsoLongest}: the mod's LentHeadSize.partFloor, with no tiny-main ceiling (the pack has none). */
	static double partFloor(double scale, double extent, double torsoLongest) {
		return partFloor(scale, extent, torsoLongest, false);
	}

	/** A lent flat fin is at least this share of the torso's longest side across, and no fin is more than FIN_CAP of it (the mod's LentHeadSize.FIN_FLOOR, FIN_CAP). */
	static final double FIN_FLOOR = 0.3, FIN_CAP = 0.6;

	/** Whether a part with these sides (smallest first) is a flat plane, the mod's LentHeadSize.planar. */
	static boolean planar(double[] sides) {
		double longest = sides[2];
		return longest > 0.01 && sides[0] <= 0.25 * longest && sides[1] >= 0.4 * longest;
	}

	/** {@link #partFloor(double, double, double)} with FIN_FLOOR for the share where the part is a flat plane: the mod's LentHeadSize.partFloor. */
	static double partFloor(double scale, double extent, double torsoLongest, boolean planar) {
		if (extent <= 0.01 || torsoLongest <= 0.01) return scale;
		return Math.max(scale, Math.min((planar ? FIN_FLOOR : PART_FLOOR) * torsoLongest / extent, PARTS_MAX));
	}

	/** {@code scale} held so a fin {@code extent} across is at most FIN_CAP of {@code torsoLongest}: the mod's LentHeadSize.finCap. */
	static double finCap(double scale, double extent, double torsoLongest) {
		if (extent <= 0.01 || torsoLongest <= 0.01) return scale;
		return Math.min(scale, FIN_CAP * torsoLongest / extent);
	}

	/** A flat tail is not drawn under FIN_FLOOR of the torso's longest side: the mod's LentHeadSize.tailFloor. */
	static double tailFloor(double scale, double tailExtent, double torsoLongest, boolean planar) {
		if (!planar || tailExtent <= 0.01 || torsoLongest <= 0.01) return scale;
		return Math.max(scale, Math.min(FIN_FLOOR * torsoLongest / tailExtent, PARTS_MAX));
	}

	/**
	 * {@link #tailFloor(double, double, double, boolean)} held to the thickness limit of {@link #tailScale(double, double, double, double, double)}
	 * too: the tail's middle side {@code tailWidth} at most TAIL_THICK_SHARE of the torso's thinner side {@code torsoThin}. The mod's
	 * LentHeadSize.tailFloor, which tells why.
	 */
	static double tailFloor(double scale, double tailExtent, double tailWidth, double torsoLongest, double torsoThin, boolean planar) {
		if (!planar || tailExtent <= 0.01 || torsoLongest <= 0.01) return scale;
		double floor = Math.min(FIN_FLOOR * torsoLongest / tailExtent, PARTS_MAX);
		if (tailWidth > 0.01 && torsoThin > 0.01) floor = Math.min(floor, TAIL_THICK_SHARE * torsoThin / tailWidth);
		return Math.max(scale, floor);
	}

	/** A huge body this much bigger than the creature a part comes from (by their torsos) grows the part with it (the client mod's LentHeadSize). */
	private static final double PARTS_STAY = 2.5, PARTS_FOLLOW = 4, PARTS_MAX = 24;

	/**
	 * The bodies that grow a lent creature's wings, fins, rods and tail with them, the client mod's LentHeadSize.growsParts: a giant, a
	 * ghast, a happy ghast and the ender dragon. Every other body keeps them at their own size (the ender dragon's own parts, lent, are always scaled to the body, whatever body it is lent to).
	 */
	private static final Set<String> PARTS_GROW = Set.of("minecraft:giant", "minecraft:ghast", "minecraft:happy_ghast", "minecraft:ender_dragon", "minecraft:elder_guardian");

	/** How much to scale a lent creature's parts for a body whose torso is {@code fit} times as big: the client mod's LentHeadSize.partScale. */
	static double partScale(boolean hugeMain, double fit) {
		if (!hugeMain || fit <= PARTS_STAY) return 1;
		if (fit >= PARTS_FOLLOW) return Math.min(fit, PARTS_MAX);
		return 1 + (fit - PARTS_STAY) / (PARTS_FOLLOW - PARTS_STAY) * (PARTS_FOLLOW - 1);
	}

	/**
	 * Where each of the lent creature's parts goes on the main body this tick, and how big it is drawn, as the client mod's
	 * Graft puts it (see {@link #place}); each turned with the body. Empty when either body has no torso to measure. (A
	 * shulker body shows none: they would stick out of its shut shell.)
	 */
	private List<Placed> partsAt(Player player, MorphRef main, Head body, MorphRef lent, Head lentHead) {
		if (lentHead.parts().isEmpty() || body.torso() == null || lentHead.torso() == null) return List.of();
		// a baby body's borrowed creature comes with its baby's numbers, where it has babies (headFor), as with the mod
		double sizeA = sizeOf(player, lent, lentHead), sizeM = sizeOf(player, main, body);
		double[][] own = scale(lentHead.torso(), sizeA), mine = scale(body.torso(), sizeM);
		double[][] ownAll = lentHead.all() == null ? own : scale(lentHead.all(), sizeA);
		double[][] mineAll = body.all() == null ? mine : scale(body.all(), sizeM);
		// the dragon's parts are scaled to the body they go on (my choice in the mod); everyone else's keep their size, but on a huge
		// body (a giant, a ghast), where they grow with it, as the mod draws them
		double fit = lent.entityType().equals("minecraft:ender_dragon") ? fit(own, mine)
				: partScale(!main.isPlayer() && PARTS_GROW.contains(main.entityType()), fit(own, mine));
		float drawn = drawnYaw(player, main);
		double yaw = Math.toRadians(drawn);
		List<Placed> out = new java.util.ArrayList<>(lentHead.parts().size());
		for (Part part : lentHead.parts()) {
			double[] joint = {part.joint().getX() * sizeA, part.joint().getY() * sizeA, part.joint().getZ() * sizeA};
			// a tail longer than the torso it goes on is drawn as long as it (the mod's Graft.tailScale)
			double[] sides = sortedSides(part.box(), sizeA);
			boolean flat = planar(sides);
			double partFit = part.touch()
					? tailFloor(tailScale(fit, longestSide(part.box()) * sizeA, middleSide(part.box(), sizeA), longestSide(mine),
							Math.min(mine[1][0] - mine[0][0], mine[1][1] - mine[0][1])), longestSide(part.box()) * sizeA, middleSide(part.box(), sizeA),
							longestSide(mine), Math.min(mine[1][0] - mine[0][0], mine[1][1] - mine[0][1]), flat)
					// wings, fins and a stinger are not left a speck on a big body; rods keep their size
					: part.kind().equals("rods") ? fit : fin(part) ? finCap(partFloor(fit, longestSide(part.box()) * sizeA, longestSide(mine), flat),
							longestSide(part.box()) * sizeA, longestSide(mine)) : partFloor(fit, longestSide(part.box()) * sizeA, longestSide(mine));
			double[] at = place(own, mine, ownAll, mineAll, joint, part.kind(), part.name(), partFit);
			if (part.touch()) {
				// a tail just reaches into the back: in by TAIL_SET_IN where it stands off it, out where it would be hidden in
				// it (a camel's flat tail)
				double k = sizeA * partFit;
				double lo = at[2] + part.box()[0][2] * k, hi = at[2] + part.box()[1][2] * k, back = mine[1][2];
				if (hi < back + 0.005) at[2] += back + 0.005 - hi;
				else if (lo > back - TAIL_SET_IN) at[2] += back - TAIL_SET_IN - lo;
			}
			// item space is the body's frame turned half way round; then the body's facing
			double bx = -at[0], bz = -at[2];
			Location l = player.getLocation().add(bx * Math.cos(yaw) - bz * Math.sin(yaw), at[1], bx * Math.sin(yaw) + bz * Math.cos(yaw));
			l.setYaw(drawn);
			l.setPitch(0);
			out.add(new Placed(l, (float) (part.scale() * sizeA * partFit)));
		}
		return out;
	}

	/** The longest side of a box {lo, hi}. */
	private static double longestSide(double[][] box) {
		return Math.max(box[1][0] - box[0][0], Math.max(box[1][1] - box[0][1], box[1][2] - box[0][2]));
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

	/** As {@link #drawnYaw(Player, MorphRef)} for {@code main} borrowing {@code lent}: a giant drawn as a player (GiantPlayer) turns as one. */
	public static float drawnYaw(Player player, MorphRef main, @Nullable MorphRef lent) {
		return GiantPlayer.applies(main, lent) ? player.getBodyYaw() : drawnYaw(player, main);
	}

	// ------------------------------------------------------------------ a shulker held to a block

	/** The only creature the plugin holds to a block, as its main body (ShulkerLock). */
	public static final String SHULKER = "minecraft:shulker";

	/**
	 * A shulker main body held to a block: the plugin's hold puts it in the live overlay (MorphManager.setOverlay) with
	 * vanilla's own keys, {@code {AttachFace:<b>b,Peek:<p>b}}. Players without the mod see a vanilla shulker, which their
	 * game draws square in the block it snaps to (Shulker.setPos), its body never turned, the whole shell turned onto the
	 * face it holds on to about the block's middle (ShulkerRenderer.setupRotations), its head turned in that face's frame.
	 * No keys (a shulker with nowhere to hold on, or any other body): a free shulker, which viewers' games still snap to
	 * its block, on the floor and shut; a shulker body keeps its lent head inside that shell too ({@link #FREE}).
	 *
	 * @param face the way to the block it holds on to (a floor shulker's is DOWN, a ceiling shulker's UP)
	 * @param peek how far the shell is open, 0 to 100
	 */
	public record Shell(BlockFace face, int peek) {
		/** Faces by Direction's 3D id, which is what the AttachFace byte holds. */
		private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

		/** The hold in an overlay, or null when it has none. */
		public static @Nullable Shell of(String overlaySnbt) {
			if (!overlaySnbt.contains("AttachFace")) return null;
			try {
				return of(Snbt.parseCompound(overlaySnbt));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		public static @Nullable Shell of(Map<String, Object> overlay) {
			if (!(overlay.get("AttachFace") instanceof Number id) || id.intValue() < 0 || id.intValue() >= FACES.length) return null;
			int peek = overlay.get("Peek") instanceof Number p ? Math.clamp(p.intValue(), 0, 100) : 0;
			return new Shell(FACES[id.intValue()], peek);
		}

		/** On a floor, where the shell's frame is the world's. */
		public boolean floor() {
			return face == BlockFace.DOWN;
		}

		/** The turn from a floor shulker onto this face, about the block's middle: Direction.getRotation of the way it opens. */
		public Quaternionf turn() {
			float quarter = (float) (Math.PI / 2), half = (float) Math.PI;
			return switch (face.getOppositeFace()) {
				case DOWN -> new Quaternionf().rotationX(half);
				case NORTH -> new Quaternionf().rotationXYZ(quarter, 0F, half);
				case SOUTH -> new Quaternionf().rotationX(quarter);
				case WEST -> new Quaternionf().rotationXYZ(quarter, 0F, quarter);
				case EAST -> new Quaternionf().rotationXYZ(quarter, 0F, -quarter);
				default -> new Quaternionf();
			};
		}

		/**
		 * The head's yaw in the shell's frame for where {@code look} looks, as a vanilla shulker turns its head
		 * (ShulkerLookControl.getYRotD): the look's own yaw on a floor; NaN looking straight along the shell's axis, where
		 * it has none.
		 */
		public float headYaw(Location look) {
			if (floor()) return look.getYaw();
			Vector dir = look.getDirection();
			Quaternionf turn = turn();
			// forward is a floor shulker's south, turned onto the face; right is up x forward, the turned east
			Vector3f forward = turn.transform(new Vector3f(0F, 0F, 1F)), right = turn.transform(new Vector3f(1F, 0F, 0F));
			double r = right.x * dir.getX() + right.y * dir.getY() + right.z * dir.getZ();
			double f = forward.x * dir.getX() + forward.y * dir.getY() + forward.z * dir.getZ();
			if (Math.abs(r) < 1e-4 && Math.abs(f) < 1e-4) return Float.NaN;
			return (float) Math.toDegrees(Math.atan2(-r, f));
		}
	}

	/**
	 * Where a viewer's game draws a shulker standing at {@code at}: the middle of the bottom of the block it snaps to
	 * (Shulker.setPos). A held player stands there already; this keeps what goes on the shell with it if they drift.
	 */
	public static Location anchor(Location at) {
		return new Location(at.getWorld(), Math.floor(at.getX()) + 0.5, Math.floor(at.getY() + 0.5), Math.floor(at.getZ()) + 0.5);
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
		return place(own, main, ownAll, mainAll, joint, kind, "", scale);
	}

	/** {@link #place} for the part called {@code name}: a breeze's rods ("rods") stand round the middle of the torso, a blaze's ("part0"...) keep their share. */
	static double[] place(double[][] own, double[][] main, double[][] ownAll, double[][] mainAll, double[] joint, String kind, String name, double scale) {
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
				// a breeze's rods run from 0.16 above their pivot to 0.35 below it, and that pivot is low on its body: by their share of the
				// whole body they sat at the knees of a person. The pivot goes to the middle of the torso, a little above the middle so the
				// rods' own is there, as the mod puts it. The mod also pushes each rod out to ring the torso; one display cannot
				if (name.equals("rods")) {
					at[0] = (main[0][0] + main[1][0]) / 2;
					at[2] = (main[0][2] + main[1][2]) / 2;
					at[1] = (main[0][1] + main[1][1]) / 2 + RODS_MIDDLE_BELOW * scale;
				}
			}
			case "wing" -> {
				double w = main[1][0] - main[0][0], h = main[1][1] - main[0][1];
				if (mainStands) {
					double inset = wingInset(w);
					at[0] = Math.clamp(at[0], main[0][0] + inset, main[1][0] - inset);
					at[2] = Math.max(at[2], main[1][2] + WING_CLEAR);
					at[1] = Math.max(at[1], main[0][1] + 0.92 * h);
				} else {
					boolean left = at[0] < (main[0][0] + main[1][0]) / 2;
					// the mod's Graft.place: touching a body under 0.4 wide, and never over its top (only there)
					boolean tiny = w < TINY_MAIN;
					double clear = tiny ? -TAIL_SET_IN : WING_CLEAR;
					at[0] = left ? Math.min(at[0], main[0][0] - clear) : Math.max(at[0], main[1][0] + clear);
					at[1] = Math.max(at[1], main[0][1] + 0.67 * h);
					if (tiny) at[1] = Math.min(at[1], main[1][1] - TAIL_SET_IN);
				}
			}
			case "tail" -> {
				at[2] = main[1][2] - TAIL_SET_IN;
				// a lying creature's rear on a standing body lands at its bottom, with the pivot past it: from the small of the back up
				if (mainStands && from[1] == 2) at[1] = Math.max(at[1], main[0][1] + TAIL_LOW * (main[1][1] - main[0][1]));
			}
			default -> {
				// a bee's stinger plate stands 5 to 7 pixels behind its pivot: its front edge goes into the rear (the mod's Graft.place)
				if (name.equals("stinger")) at[2] = main[1][2] - 5.0 / 16.0 * scale - TAIL_SET_IN;
			}
		}
		return at;
	}

	/** A body under this many blocks across is tiny (the client mod's LentHeadSize.TINY_MAIN). */
	private static final double TINY_MAIN = 0.4;
	/** A tail is never shrunk under this share of its size (the mod's LentHeadSize.TINY_PARTS_FLOOR). */
	private static final double TAIL_FLOOR = 0.2;

	/**
	 * The scale a lent tail is drawn at, as the client mod's LentHeadSize.tailScale: {@code scale} unless the tail, {@code extent} long at
	 * scale 1, would be longer than the torso's longest side {@code longest}; then as long as it, never under TAIL_FLOOR of {@code scale}.
	 */
	static double tailScale(double scale, double extent, double longest) {
		if (extent <= 0.01 || longest <= 0.01 || extent * scale <= longest) return scale;
		return Math.max(TAIL_FLOOR * scale, longest / extent);
	}

	/** A lent tail is at most this share of the torso's thinner side across (the mod's LentHeadSize.TAIL_THICK_SHARE). */
	private static final double TAIL_THICK_SHARE = 0.8;

	/**
	 * {@link #tailScale(double, double, double)} also held so the tail's middle side ({@code width}) is at most TAIL_THICK_SHARE of the torso's
	 * thinner side {@code thin}: the mod's LentHeadSize.tailScale, which tells why.
	 */
	static double tailScale(double scale, double extent, double width, double longest, double thin) {
		double s = tailScale(scale, extent, longest);
		if (width <= 0.01 || thin <= 0.01 || width * s <= TAIL_THICK_SHARE * thin) return s;
		return Math.max(TAIL_FLOOR * scale, TAIL_THICK_SHARE * thin / width);
	}

	/** A box's three sides at {@code by} times its size, smallest first. */
	private static double[] sortedSides(double[][] box, double by) {
		double[] d = {(box[1][0] - box[0][0]) * by, (box[1][1] - box[0][1]) * by, (box[1][2] - box[0][2]) * by};
		java.util.Arrays.sort(d);
		return d;
	}

	/** Whether a lent part is a fin, as the mod names them (HybridMorph.findAppendages): a name with "fin" in it. */
	private static boolean fin(Part part) {
		return part.name().contains("fin");
	}

	/** The middle of a box's three sides at {@code by} times its size. */
	private static double middleSide(double[][] box, double by) {
		double[] d = {(box[1][0] - box[0][0]) * by, (box[1][1] - box[0][1]) * by, (box[1][2] - box[0][2]) * by};
		java.util.Arrays.sort(d);
		return d[1];
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
	 * or {@link #FOR_ALL} (players without the mod). {@code pin}: on a held shulker, turned with its head.
	 */
	private ItemDisplay display(Player owner, ItemStack item, Location at, float scale, Vector anchor, boolean skull, byte audience, @Nullable Pin pin) {
		// the item is drawn turned half way round inside the display: its -z face looks the display's way
		Vector3f shift = new Vector3f((float) anchor.getX() * scale, (float) -anchor.getY() * scale, (float) anchor.getZ() * scale);
		Quaternionf turn = skull ? new Quaternionf().rotateY((float) Math.PI) : new Quaternionf();
		return display(owner, item, at, scale, shift, turn, audience, pin, true);
	}

	/**
	 * One display following the owner: the model moved by {@code shift} (display space) and turned by {@code turn}. On a
	 * held shulker ({@code pin}) all of that is turned again by the shell's turn, and with {@code head} by the head's
	 * too, which {@link Pin#turnHead} keeps up to date.
	 */
	private ItemDisplay display(Player owner, ItemStack item, Location at, float scale, Vector3f shift, Quaternionf turn, byte audience, @Nullable Pin pin,
			boolean head) {
		Transformation rest = new Transformation(shift, turn, new Vector3f(scale), new Quaternionf());
		Transformation shown = pin == null ? rest : turned(rest, head ? pin.head() : pin.body);
		ItemDisplay display = owner.getWorld().spawn(at, ItemDisplay.class, d -> {
			d.setPersistent(false);
			d.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
			d.setItemStack(item);
			d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
			d.setBillboard(Display.Billboard.FIXED);
			d.setTeleportDuration(GLIDE_TICKS);
			d.setTransformation(shown);
		});
		if (pin != null && head) pin.rest.put(display, rest);
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

	/** Whether this player's game loaded RE:Morph's resource pack. Safe from any thread. */
	public boolean packLoaded(UUID viewer) {
		return packLoaded.contains(viewer);
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
		resendArmedHands(viewer);
	}

	/**
	 * A viewer's pack loaded or their mod finished its handshake: every armed player near them has their hand sent again, so
	 * the equipment packet is rewritten for what this viewer now has (the arm's pack model, or the plain tool).
	 */
	private void resendArmedHands(Player viewer) {
		var arms = plugin.arms();
		if (!arms.enabled()) return;
		double range = 128 * 128;
		for (Player owner : viewer.getWorld().getPlayers()) {
			if (owner == viewer || owner.getLocation().distanceSquared(viewer.getLocation()) > range) continue;
			ItemStack hand = owner.getInventory().getItemInMainHand();
			// a tucked arm has no item in the hand, but others are still shown it there, so the hand is sent for it too
			if (arms.isArm(hand) || arms.isTucked(owner)) viewer.sendEquipmentChange(owner, org.bukkit.inventory.EquipmentSlot.HAND, hand);
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
			// only RE:Morph's own pack counts, by the id or hash it was sent with (appearance.resource-pack-ids; none set: any pack)
			if (!plugin.settings().isOurPack(event.getID(), hashOf(event))) return;
			packLoaded.add(event.getPlayer().getUniqueId());
			refreshViewer(event.getPlayer());
		}
	}

	/** The SHA-1 the pack was sent with, or null (the event's getHash is on its way out of the API). */
	@SuppressWarnings("removal")
	private static @Nullable String hashOf(PlayerResourcePackStatusEvent event) {
		try {
			return event.getHash();
		} catch (NoSuchMethodError | RuntimeException e) {
			return null;
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
		cells.remove(id);
		if (helmets.remove(id) != null) plugin.disguises().setHeadOverride(event.getPlayer(), null);
		borrowed.remove(id); // only from memory: it stays saved with the player (PlayerMorphData.borrowed)
		packLoaded.remove(id);
	}
}
