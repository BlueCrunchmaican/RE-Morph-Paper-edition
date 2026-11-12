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
 * The display is a real, unsaved entity, moved to the neck every tick with a short glide so it keeps up with the
 * disguise as the viewers' games draw it. It is hidden from the player themselves and from players with the client
 * mod, who will draw multimorphs themselves.
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
	 */
	private record Head(Vector neck, double height, double scale, Vector anchor, Vector size, double @Nullable [][] torso, @Nullable Ears ears,
			@Nullable Tail tail) {
		/** An animal that lends its ears and tail instead of its head (wolf, fox, cat...), as the client mod does. */
		boolean lendsEarsAndTail() {
			return ears != null && tail != null;
		}
	}

	/**
	 * An animal's ears: their box and the box of its head's big blocks without them (item space, blocks from the head's
	 * pivot, at the mob's real size), and the ears model's display scale at that size.
	 */
	private record Ears(double[][] box, double[][] core, double scale) {}

	/**
	 * An animal's tail: where it joins its body (item space, blocks from the feet, real size), its box from there, and the
	 * tail model's display scale.
	 */
	private record Tail(Vector joint, double[][] box, double scale) {}

	/**
	 * A player's body (a player form): Lib's Disguises draws a player, 0.9375 of the model's size. Its torso is the
	 * model's 8 x 12 x 4 body at that size.
	 */
	private static final Head PLAYER = new Head(new Vector(0, 1.40625, 0), 1.8, 0.9375, new Vector(0, -0.0312, 0.2812), new Vector(0.5625, 0.5625, 0.5625),
			new double[][] {{-0.2344, 0.7031, -0.1172}, {0.2344, 1.4063, 0.1172}}, null, null);
	/** Blocks borrowed ears reach into the head they go on (the client mod's EAR_SET_IN). */
	private static final double EAR_SET_IN = 0.0625;
	/** Blocks a borrowed tail is set into the body it joins (the client mod's Graft.SET_IN). */
	private static final double TAIL_SET_IN = 0.03;

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
	 * lends its ears and tail, the pack's ears (on the body's head) and tail (at its back). Any may be missing.
	 */
	private record Shown(@Nullable ItemDisplay pack, @Nullable ItemDisplay skull, @Nullable ItemDisplay ears, @Nullable ItemDisplay tail) {
		ItemDisplay[] all() {
			return new ItemDisplay[] {pack, skull, ears, tail};
		}

		boolean valid(Player owner) {
			for (ItemDisplay d : all()) {
				if (d != null && (!d.isValid() || d.getWorld() != owner.getWorld())) return false;
			}
			return pack != null || skull != null || ears != null || tail != null;
		}

		void teleport(Location at, @Nullable Location tailAt) {
			if (pack != null) pack.teleport(at);
			if (skull != null) skull.teleport(at);
			if (ears != null) ears.teleport(at);
			if (tail != null && tailAt != null) tail.teleport(tailAt);
		}

		void remove() {
			for (ItemDisplay d : all()) if (d != null && d.isValid()) d.remove();
		}
	}

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
				JsonArray n = h.getAsJsonArray("neck");
				JsonArray a = h.has("anchor") ? h.getAsJsonArray("anchor") : null;
				Vector anchor = a == null ? new Vector() : new Vector(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
				if (h.has("whole") && h.get("whole").getAsBoolean()) whole.add(e.getKey());
				JsonArray z = h.has("size") ? h.getAsJsonArray("size") : null;
				Vector size = z == null ? new Vector() : new Vector(z.get(0).getAsDouble(), z.get(1).getAsDouble(), z.get(2).getAsDouble());
				double[][] torso = h.has("torso") ? box(h.getAsJsonArray("torso")) : null;
				Ears ears = null;
				Tail tail = null;
				if (h.has("ears") && h.has("tail")) {
					JsonObject ea = h.getAsJsonObject("ears"), ta = h.getAsJsonObject("tail");
					JsonArray j = ta.getAsJsonArray("joint");
					ears = new Ears(box(ea.getAsJsonArray("box")), box(ea.getAsJsonArray("core")), ea.get("scale").getAsDouble());
					tail = new Tail(new Vector(j.get(0).getAsDouble(), j.get(1).getAsDouble(), j.get(2).getAsDouble()), box(ta.getAsJsonArray("box")),
							ta.get("scale").getAsDouble());
				}
				heads.put(e.getKey(), new Head(new Vector(n.get(0).getAsDouble(), n.get(1).getAsDouble(), n.get(2).getAsDouble()),
						h.get("height").getAsDouble(), h.get("scale").getAsDouble(), anchor, size, torso, ears, tail));
			}
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not read morph-heads.json; multimorph heads won't show to players without the mod.", e);
		}
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

	/** A body's numbers: the head table's, or a player's for player forms. */
	private @Nullable Head body(MorphRef form) {
		return form.isPlayer() ? PLAYER : heads.get(form.entityType());
	}

	/** Every tick: a head on the neck of each fully transformed multimorph, and none anywhere else. */
	public void tick() {
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
			Head body = main == null ? null : body(main);
			boolean show = !mode.equals("off") && player != null && !player.isDead() && main != null && !main.isSelf()
					&& !plugin.morphs().isTransitioning(player) && body != null && (heads.containsKey(lent.entityType()) || skullType(lent) != null);
			// a body that wears things on its head wears the borrowed skull, for every viewer without the mod
			boolean helmet = show && HELMETED.contains(main.isPlayer() ? "minecraft:player" : main.entityType()) && skullType(lent) != null;
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
			Location neck = neck(player, main, body, isWhole(lent.entityType()));
			Head lentHead = lent.isPlayer() ? null : heads.get(lent.entityType());
			Location tailAt = lentHead != null && lentHead.lendsEarsAndTail() ? tailAt(player, main, body, lent, lentHead) : null;
			if (shown == null || !shown.valid(player)) {
				removeDisplay(e.getKey());
				displays.put(e.getKey(), spawn(player, main, body, lent, neck, tailAt, coverScale(player, main, body, lent)));
			} else {
				shown.teleport(neck, tailAt);
			}
		}
	}

	/**
	 * Where the borrowed head goes: where the main body's own head meets its neck, turned with the body; the head turned
	 * as the player looks.
	 */
	private Location neck(Player player, MorphRef main, Head body, boolean centred) {
		LivingEntity template = main.isPlayer() ? null : plugin.templates().get(main, player.getWorld());
		double size = template == null || body.height() <= 0 ? 1.0 : template.getHeight() / body.height();
		double yaw = Math.toRadians(player.getBodyYaw());
		// the pivot, then the body's own head's neck point from it (item space faces -z: the body's +z is its -z); a
		// creature lent whole (a slime) stands on the middle of the head's bottom, not at its back, or it sat behind it
		double s = body.scale() * size;
		double back = centred ? body.anchor().getZ() - body.size().getZ() / 2.0 : body.anchor().getZ();
		Vector n = body.neck().clone().multiply(size).add(new Vector(-body.anchor().getX() * s, body.anchor().getY() * s, -back * s));
		double x = n.getX() * Math.cos(yaw) - n.getZ() * Math.sin(yaw);
		double z = n.getX() * Math.sin(yaw) + n.getZ() * Math.cos(yaw);
		Location at = player.getLocation().add(x, n.getY(), z);
		at.setYaw(player.getLocation().getYaw());
		at.setPitch(player.getLocation().getPitch());
		return at;
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
	private double coverScale(Player owner, MorphRef main, Head body, MorphRef form) {
		// a player's head (another player's form, or your own on a creature) covers the body's head too, or it hid in it
		Head head = form.isPlayer() ? PLAYER : heads.get(form.entityType());
		if (head == null || body.size().lengthSquared() == 0 || head.size().lengthSquared() == 0 || heads.get(main.entityType()) != null
				&& isWhole(main.entityType())) {
			return 1;
		}
		boolean whole = !form.isPlayer() && isWhole(form.entityType());
		LivingEntity bodyTemplate = main.isPlayer() ? null : plugin.templates().get(main, owner.getWorld());
		LivingEntity headTemplate = form.isPlayer() ? null : plugin.templates().get(form, owner.getWorld());
		double bodySize = bodyTemplate == null || body.height() <= 0 ? 1.0 : bodyTemplate.getHeight() / body.height();
		double headSize = headTemplate == null || head.height() <= 0 ? 1.0 : headTemplate.getHeight() / head.height();
		// what it takes to cover the body's head on every axis
		double fit = 0, mainBig = 0, lentBig = 0;
		for (int axis = 0; axis < 3; axis++) {
			double main3 = component(body.size(), axis) * body.scale() * bodySize;
			double lent3 = component(head.size(), axis) * head.scale() * headSize;
			if (lent3 > 1e-3) fit = Math.max(fit, main3 / lent3 * 1.04);
			mainBig = Math.max(mainBig, main3);
			lentBig = Math.max(lentBig, lent3);
		}
		if (fit <= 0) return 1;
		// a creature lent whole is a head, so it shrinks to the head it covers (a full-size ghast on a person); any other
		// head much bigger than the body's (a giant's) is drawn at most BIG_HEAD times it, as the client mod does
		if (whole) return fit;
		if (mainBig > 1e-3 && lentBig > BIG_HEAD * mainBig) return BIG_HEAD * mainBig / lentBig;
		return Math.max(1, fit);
	}

	private static double component(Vector v, int axis) {
		return axis == 0 ? v.getX() : axis == 1 ? v.getY() : v.getZ();
	}

	/** Mobs lent whole (no head part: slimes, ghasts...); as a body they have no head of their own to cover. */
	private final java.util.Set<String> whole = new java.util.HashSet<>();

	private boolean isWhole(String type) {
		return whole.contains(type);
	}

	/** How big a form is drawn against the size its numbers were measured at (a baby, a big slime). */
	private double sizeOf(Player owner, MorphRef form, Head head) {
		LivingEntity template = form.isPlayer() ? null : plugin.templates().get(form, owner.getWorld());
		return template == null || head.height() <= 0 ? 1.0 : template.getHeight() / head.height();
	}

	/** An item showing one of the pack's models: {@code kind} "head", "ears" or "tail", for {@code form}'s mob. */
	private static ItemStack packItem(String kind, MorphRef form) {
		ItemStack item = new ItemStack(Material.PAPER);
		ItemMeta meta = item.getItemMeta();
		String name = form.entityType().substring(form.entityType().indexOf(':') + 1);
		meta.setItemModel(new NamespacedKey(MorphRebornPlugin.NAMESPACE, kind + "/" + name));
		item.setItemMeta(meta);
		return item;
	}

	/**
	 * The displays for {@code form} on {@code main}: the head at {@code at} (the pack's model where there is one, a vanilla
	 * skull where there is one), or for an animal that lends its ears and tail, those (pack only: the game has no ears).
	 */
	private Shown spawn(Player owner, MorphRef main, Head body, MorphRef form, Location at, @Nullable Location tailAt, double cover) {
		Head head = form.isPlayer() ? null : heads.get(form.entityType());
		if (head != null && head.lendsEarsAndTail()) {
			ItemDisplay ears = ears(owner, main, body, form, head, at);
			ItemDisplay tail = tailAt == null || head.tail() == null ? null
					: display(owner, packItem("tail", form), tailAt, (float) (head.tail().scale() * sizeOf(owner, form, head)), new Vector3f(), new Quaternionf(), FOR_PACK);
			return new Shown(null, null, ears, tail);
		}
		ItemDisplay pack = null;
		if (head != null) {
			pack = display(owner, packItem("head", form), at, (float) (head.scale() * sizeOf(owner, form, head) * cover), head.anchor(), false, FOR_PACK);
		}
		ItemDisplay skull = null;
		ItemStack skullItem = skullItem(form);
		// a skull with no pack model beside it (player forms, the dragon) is the only head there is: everyone sees it
		// (a player's at the size coverScale measured it: a disguised player's head, PLAYER.scale of the model's)
		double skullScale = SKULL_SCALE * (form.isPlayer() ? PLAYER.scale() : 1) * cover;
		if (skullItem != null) skull = display(owner, skullItem, at, (float) skullScale, SKULL_ANCHOR, true, head == null ? FOR_ALL : FOR_NO_PACK);
		return new Shown(pack, skull, null, null);
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
		// the main head, item space from its neck point (the middle of its bottom, at the back; the face towards -z)
		double wB = body.size().getX() * sB, hB = body.size().getY() * sB, dB = body.size().getZ() * sB;
		double[][] core = scale(ea.core(), sizeA), box = scale(ea.box(), sizeA);
		double wA = Math.max(0.01, core[1][0] - core[0][0]);
		double k = Math.clamp(wB / wA, 0.5, 2); // an armadillo's at 2.5 were twice its head
		// the borrowed head's top middle goes on the main head's top middle, (0, hB, -dB / 2)
		double tx = (core[0][0] + core[1][0]) / 2, ty = core[1][1], tz = (core[0][2] + core[1][2]) / 2;
		double middle = -dB / 2 + k * ((box[0][2] + box[1][2]) / 2 - tz);
		double front = -dB + EAR_SET_IN, back = -EAR_SET_IN;
		double dz = front < back ? Math.clamp(middle, front, back) - middle : 0;
		double bottom = hB + k * (box[0][1] - ty);
		double sink = Math.max(EAR_SET_IN, k * (box[1][1] - box[0][1]) / 3);
		double dy = hB - sink - bottom;
		// where the ears model's origin (the borrowed head's pivot) goes, item space; the display shows it turned round
		double ox = -k * tx, oy = hB - k * ty + dy, oz = -dB / 2 - k * tz + dz;
		Vector3f shift = new Vector3f((float) -ox, (float) oy, (float) -oz);
		return display(owner, packItem("ears", form), at, (float) (ea.scale() * sizeA * k), shift, new Quaternionf(), FOR_PACK);
	}

	/**
	 * Where a borrowed tail joins the main body, as the client mod's Graft puts it: at the same share of the main torso's
	 * box as of its own (a lying torso on a standing one as if it stood up: its rear to the bottom, so a wolf's tail joins
	 * a person at the small of the back), at the back, then moved so it just reaches into the body. Turned with the body.
	 */
	private @Nullable Location tailAt(Player player, MorphRef main, Head body, MorphRef lent, Head lentHead) {
		Tail tail = lentHead.tail();
		if (tail == null || body.torso() == null || lentHead.torso() == null) return null;
		double sizeA = sizeOf(player, lent, lentHead);
		double[][] own = scale(lentHead.torso(), sizeA), mine = scale(body.torso(), sizeOf(player, main, body));
		double[] at = graft(own, mine, new double[] {tail.joint().getX() * sizeA, tail.joint().getY() * sizeA, tail.joint().getZ() * sizeA});
		double lo = at[2] + tail.box()[0][2] * sizeA, hi = at[2] + tail.box()[1][2] * sizeA, back = mine[1][2];
		if (hi < back + 0.005) at[2] += back + 0.005 - hi;
		else if (lo > back - TAIL_SET_IN) at[2] += back - TAIL_SET_IN - lo;
		// item space is the body's frame turned half way round; then the body's facing
		double yaw = Math.toRadians(player.getBodyYaw());
		double bx = -at[0], bz = -at[2];
		Location l = player.getLocation().add(bx * Math.cos(yaw) - bz * Math.sin(yaw), at[1], bx * Math.sin(yaw) + bz * Math.cos(yaw));
		l.setYaw(player.getBodyYaw());
		l.setPitch(0);
		return l;
	}

	/** The client mod's Graft.place for a tail (item space: the back is +z): where {@code joint} on {@code own} goes on {@code main}. */
	private static double[] graft(double[][] own, double[][] main, double[] joint) {
		boolean ownStands = own[1][1] - own[0][1] >= own[1][2] - own[0][2];
		boolean mainStands = main[1][1] - main[0][1] >= main[1][2] - main[0][2];
		int[] from = ownStands == mainStands ? new int[] {0, 1, 2} : new int[] {0, 2, 1};
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
				double off = j - (lo + hi) / 2;
				at[axis] = (mlo + mhi) / 2 + (flip[axis] ? -off : off);
			} else if (j < lo) {
				at[axis] = flip[axis] ? mhi + (lo - j) : mlo - (lo - j);
			} else if (j > hi) {
				at[axis] = flip[axis] ? mlo - (j - hi) : mhi + (j - hi);
			} else {
				double share = (j - lo) / (hi - lo);
				at[axis] = mlo + (flip[axis] ? 1 - share : share) * (mhi - mlo);
			}
		}
		at[2] = main[1][2] - TAIL_SET_IN; // at the back, set into it a little
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

	private boolean canSee(Player viewer, String mode, ItemDisplay display) {
		Byte audience = display.getPersistentDataContainer().get(PACK_KEY, PersistentDataType.BYTE);
		return canSee(viewer, mode, audience == null ? FOR_PACK : audience);
	}

	/** A viewer's client mod finished its handshake, or its resource pack loaded: show or hide every head for it. */
	public void refreshViewer(Player viewer) {
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
