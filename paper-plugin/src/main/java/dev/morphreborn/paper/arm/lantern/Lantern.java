package dev.morphreborn.paper.arm.lantern;

import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.rules.LightBeam;
import dev.morphreborn.paper.arm.rules.LightBeam.Light;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tips.Lines;
import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.MainHand;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The Tendril Lantern (spec 3.10): a lure on a chain of tendril over the hand that holds the form, beside any form or a
 * bare hand, lighting the area round its holder. The light is visual only. Every few ticks the cells are worked out
 * ({@link LightBeam#halo}: one round the holder at the chosen brightness's level, and past 14 blocks a taper of cells on a
 * world-aligned lattice, fading out to the brightness's reach, 2026-11-16) and shown to the players around through fake light blocks,
 * per viewer ({@link LightLedger}); nothing is ever placed in the world, so a crash or a chunk unloading leaves nothing
 * behind, and every way out (put out, owner gone, viewer out of range or in another world) gives the viewers the real
 * blocks back.
 * <p>
 * Everything a viewer sees comes from the server, so everyone sees the same: the light for everyone; the lure for
 * modded clients through S_ARM (the manager's: lit, level and brightness), and for the rest as the pack's lure model
 * (SkinEffects, for players who have the pack) or red dust at the lure.
 * <p>
 * The brightness is the lantern's mode (the manager's: Sneak+F with no tool out steps it, scrolling over the lantern in the
 * wheel steps it, /morph arm lantern bright N sets it), which comes here through {@link #mode}.
 * <p>
 * The manager puts the lantern out for death, quitting, morphing, depletion, game mode changes and world changes with
 * inventory safety on, and this class puts itself out when its owner goes offline, dies or turns spectator; the manager
 * polls {@link #isOn} each tick and tells clients. A teleport inside a world keeps it lit and the cells are worked out
 * again at once; a world change without inventory safety carries it over, the old world's cells restored first.
 */
public final class Lantern implements ArmAccessoryModule {
	private static final String LURE_MODEL = "arm/lantern_lure";
	/** Ticks between every viewer being sent everything again (a client that reloaded its chunks loses the light). */
	private static final int FULL_RESEND_TICKS = 40;
	/** Ticks between the lure's dust for players without the mod or the pack. */
	private static final int DUST_EVERY = 4;
	/** The viewers of particles stand within this many blocks. */
	private static final double PARTICLE_RANGE_SQ = 64 * 64;
	private static final Particle.DustOptions LURE_DUST = new Particle.DustOptions(VanillaEffects.ARM_RED, 0.8F);

	private @Nullable ArmContext ctx;
	private Settings settings = Settings.DEFAULTS;
	private final Map<UUID, State> states = new LinkedHashMap<>();
	/** Owners whose lantern failed in a tick: logged once each. */
	private final Set<UUID> failed = new HashSet<>();
	private final Listener events = new Events();
	/** Light blocks by level and whether they are waterlogged, made once. */
	private final BlockData[][] lightBlocks = new BlockData[2][16];

	/** The lure's key in SkinEffects: one per player. */
	private record LureKey(UUID owner) {}

	/** One lit lantern. */
	private static final class State {
		final UUID id;
		final int level, since, phase;
		/** The brightness its holder chose (1 to 5): the light's level, so how far it reaches. */
		int bright = 3;
		final LightLedger ledger = new LightLedger();
		final LureKey key;
		World world;
		/** Work the beam out on this tick whatever the clock says, and send everyone everything. */
		boolean dirty = true, full = true;
		int lastFull = Integer.MIN_VALUE / 2;
		boolean lureShown;
		/**
		 * The cells the viewers are shown now (levels steadied against the holder's walking) and where the holder stood when
		 * they were worked out (eyes' block, feet's height): a holder who hasn't moved a block gets the same cells, so
		 * standing still costs nothing.
		 */
		Map<Long, Light> shown = new LinkedHashMap<>();
		@Nullable Pos lastEye;
		int lastFeetY, lastReach = -1;

		State(Player p, int level, int since) {
			this.id = p.getUniqueId();
			this.level = level;
			this.since = since;
			this.phase = ThreadLocalRandom.current().nextInt(12);
			this.key = new LureKey(id);
			this.world = p.getWorld();
		}
	}

	public Lantern() {}

	@Override
	public String id() {
		return "lantern";
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		load(ctx.config().rawLantern());
	}

	/** arms.lantern as plain values; anything missing or unreadable keeps the bundled default. */
	@Override
	public void load(Map<String, Object> section) {
		settings = Settings.parse(section);
	}

	@Override
	public Listener listener() {
		return events;
	}

	// ------------------------------------------------------------------ lit or out

	@Override
	public boolean isOn(Player p) {
		return states.containsKey(p.getUniqueId());
	}

	@Override
	public int onTicks(Player p) {
		State s = states.get(p.getUniqueId());
		ArmContext c = ctx;
		return s == null || c == null ? 0 : Math.max(0, c.tick() - s.since);
	}

	@Override
	public @Nullable String switchOn(Player p, int level) {
		ArmContext c = ctx;
		if (c == null) return "The Tendril Lantern isn't ready yet.";
		if (states.containsKey(p.getUniqueId())) return "Your lantern is already lit.";
		State s = new State(p, Math.clamp(level, 1, 3), c.tick());
		states.put(s.id, s);
		failed.remove(s.id);
		try {
			update(s, p, c.tick());
			showLure(s, p);
		} catch (Throwable t) {
			end(s);
			c.plugin().getLogger().log(Level.WARNING, "The lantern failed to light for " + p.getName() + ".", t);
			return "The lantern failed to light; the server log says why.";
		}
		return null;
	}

	/** The brightness the player chose, 1 to 5 (how far the light reaches round them): applied now if it is lit. */
	@Override
	public void mode(Player p, int bright) {
		State s = states.get(p.getUniqueId());
		if (s == null) return;
		s.bright = Math.clamp(bright, 1, 5);
		s.dirty = true;
	}

	/** The brightest a lantern of each level may be set (level 1: 3, 2: 4, 3: 5). */
	@Override
	public int maxMode(int level) {
		return Math.clamp(level, 1, 3) + 2;
	}

	/** Until they choose: 3 (or the level's brightest, if that is less, which the manager keeps it to). */
	@Override
	public int defaultMode() {
		return 3;
	}

	/** S_ARM's accessory argument for the lantern: the brightness it shines at, 0 when it is out. */
	@Override
	public int arg(Player p) {
		State s = states.get(p.getUniqueId());
		return s == null ? 0 : s.bright;
	}

	@Override
	public void switchOff(Player p, int why) {
		State s = states.get(p.getUniqueId());
		if (s != null) end(s);
	}

	@Override
	public void switchOffAll() {
		for (State s : List.copyOf(states.values())) end(s);
	}

	/** Out: every cell back to the real block for everyone who was shown it, and the lure model gone. */
	private void end(State s) {
		states.remove(s.id);
		failed.remove(s.id);
		try {
			s.ledger.clear(new WorldSender(s.world));
		} catch (Throwable t) {
			warn("The lantern's light couldn't be put right for a viewer.", t);
		}
		hideLure(s);
	}

	// ------------------------------------------------------------------ the tick

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || states.isEmpty()) return;
		int now = c.tick();
		for (State s : List.copyOf(states.values())) {
			try {
				step(s, now);
			} catch (Throwable t) {
				if (failed.add(s.id)) c.plugin().getLogger().log(Level.WARNING, "The lantern failed for a player; it is put out.", t);
				end(s);
			}
		}
	}

	private void step(State s, int now) {
		Player p = Bukkit.getPlayer(s.id);
		if (p == null || !p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
			end(s);
			return;
		}
		if (p.getWorld() != s.world) rehome(s, p);
		if (s.dirty || Math.floorMod(now + s.phase, settings.updateTicks) == 0) update(s, p, now);
		if (Math.floorMod(now + s.phase, 2) == 0) moveLure(s, p);
		if (Math.floorMod(now + s.phase, DUST_EVERY) == 0) lureDust(p);
	}

	/** The owner is in another world (a teleport with inventory safety off): the old world's cells back, the beam begun again. */
	private void rehome(State s, Player p) {
		try {
			s.ledger.clear(new WorldSender(s.world));
		} catch (Throwable t) {
			warn("The lantern's light couldn't be put right after a world change.", t);
		}
		s.world = p.getWorld();
		s.shown = new LinkedHashMap<>(); // the old world's levels mean nothing here
		s.dirty = true;
		s.full = true;
	}

	/**
	 * Work the light out and bring every viewer in line with it: one cell round the holder at the level of their chosen
	 * brightness and, past 14 blocks, a taper of cells out to its reach ({@link LightBeam#halo}). The cells are only worked
	 * out again when the holder has moved a block, the brightness changed, a block changed inside the light's reach
	 * ({@link #terrainChanged}) or a full resend is due (the backstop for terrain changes nothing announced); every update
	 * still brings new viewers in line.
	 */
	private void update(State s, Player p, int now) {
		Location eye = p.getEyeLocation();
		World world = s.world;
		boolean full = s.full || now - s.lastFull >= FULL_RESEND_TICKS;
		int reach = settings.brightReach(s.bright);
		Pos at = new Pos(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ());
		int feetY = p.getLocation().getBlockY();
		if (s.dirty || full || reach != s.lastReach || feetY != s.lastFeetY || !at.equals(s.lastEye)) {
			WorldGrid grid = new WorldGrid(world);
			Map<Long, Light> wanted = new LinkedHashMap<>();
			for (Light l : LightBeam.halo(grid, at.x(), at.y(), feetY, at.z(), reach)) {
				wanted.merge(l.at().asLong(), l, (a, b) -> a.level() >= b.level() ? a : b);
			}
			s.shown = LightBeam.steady(s.shown, wanted);
			s.lastEye = at;
			s.lastFeetY = feetY;
			s.lastReach = reach;
		}
		s.ledger.sync(viewers(p, world, eye), s.shown, full, new WorldSender(world));
		if (full) {
			s.full = false;
			s.lastFull = now;
		}
		s.dirty = false;
	}

	/** Players in the owner's world within the viewing range (the owner too) who can see the owner. */
	private Set<UUID> viewers(Player owner, World world, Location eye) {
		double range = settings.viewerRange();
		double r2 = range * range;
		Set<UUID> out = new HashSet<>();
		out.add(owner.getUniqueId());
		for (Player v : world.getPlayers()) {
			if (v.isOnline() && v.canSee(owner) && v.getLocation().distanceSquared(eye) <= r2) out.add(v.getUniqueId());
		}
		return out;
	}

	/** The side of the hand that holds the form, where the lure hangs (2026-11-15: over the tool hand). */
	private static Vector lureSide(Player p) {
		double yaw = Math.toRadians(p.getLocation().getYaw());
		Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
		return p.getMainHand() == MainHand.LEFT ? right.multiply(-1) : right;
	}

	// ------------------------------------------------------------------ the lure, for players without the mod

	/**
	 * Where the lure hangs as others see it: the mod knows exactly, the server estimates it over the blade in front of
	 * the hand that holds the form, a little above the hand. (This is the lure itself, not the light, which is round the
	 * holder.)
	 */
	private Location lureAt(Player p, Location eye) {
		Vector side = lureSide(p);
		Vector ahead = eye.getDirection().setY(0);
		if (ahead.lengthSquared() < 1e-6) ahead = new Vector(0, 0, 1);
		ahead.normalize();
		return eye.clone().add(side.multiply(0.4)).add(ahead.multiply(0.45)).add(0, -0.85, 0);
	}

	/** Players who have the pack (and not the mod) get the lure as a model; the mod and the others are left out of it. */
	private void showLure(State s, Player p) {
		ArmContext c = ctx;
		SkinEffects skin = c == null ? null : c.plugin().skin();
		if (skin == null) return;
		Predicate<Player> natives = c::drawsArms;
		skin.spawnModel(p, s.key, LURE_MODEL, lureAt(p, p.getEyeLocation()), 1F, natives);
		s.lureShown = true;
	}

	private void moveLure(State s, Player p) {
		ArmContext c = ctx;
		SkinEffects skin = c == null ? null : c.plugin().skin();
		if (skin == null) return;
		if (!s.lureShown) {
			showLure(s, p);
			return;
		}
		skin.moveModel(s.key, lureAt(p, p.getEyeLocation()), new Quaternionf(), 1F, 2);
	}

	private void hideLure(State s) {
		ArmContext c = ctx;
		SkinEffects skin = c == null ? null : c.plugin().skin();
		if (skin != null && s.lureShown) skin.removeModel(s.key);
		s.lureShown = false;
	}

	/** One red dust at the lure for players who draw neither the mod's lure nor the pack's. */
	private void lureDust(Player p) {
		Location at = lureAt(p, p.getEyeLocation());
		for (Player v : plainViewers(p, at)) v.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, LURE_DUST, true);
	}

	/** Players near {@code at} in the owner's world who see neither the mod's drawing nor the pack's model, and can see the owner. */
	private List<Player> plainViewers(Player owner, Location at) {
		ArmContext c = ctx;
		if (c == null || at.getWorld() == null) return List.of();
		SkinEffects skin = c.plugin().skin();
		List<Player> out = new ArrayList<>();
		for (Player v : at.getWorld().getPlayers()) {
			if (c.drawsArms(v) || (skin != null && skin.drawsFor(v)) || !v.canSee(owner)) continue;
			if (v.getLocation().distanceSquared(at) <= PARTICLE_RANGE_SQ) out.add(v);
		}
		return out;
	}

	@Override
	public Lines describeLines(int level) {
		int lv = Math.clamp(level, 1, 3);
		int max = lv + 2;
		return Lines.builder().tip("Lights the area round you").always("brightness 1 to " + max + " (up to " + settings.brightReach(max) + " blocks)").build();
	}

	// ------------------------------------------------------------------ events

	/**
	 * A block changed at ({@code x, y, z}) in {@code world}: every lit lantern whose halo reaches it works its cells out
	 * again next tick, so a tunnel dug or a wall put up is lit or shadowed at once, not at the next full resend. The check
	 * is a squared distance against where the holder's cells were last worked out, so a world full of changes costs
	 * almost nothing.
	 */
	private void terrainChanged(World world, int x, int y, int z) {
		for (State s : states.values()) {
			Pos at = s.lastEye;
			if (s.dirty || at == null || s.world != world) continue;
			long reach = settings.brightReach(s.bright) + LightBeam.TAPER_SPREAD + 3L;
			long dx = x - at.x(), dy = y - at.y(), dz = z - at.z();
			if (dx * dx + dy * dy + dz * dz <= reach * reach) s.dirty = true;
		}
	}

	/** G20: a teleport inside a world keeps the lantern lit, and its cells are worked out again where the player is now. */
	private final class Events implements Listener {
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onTeleport(PlayerTeleportEvent event) {
			State s = states.get(event.getPlayer().getUniqueId());
			if (s == null) return;
			s.dirty = true;
			s.full = true;
		}

		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onBreak(BlockBreakEvent event) {
			if (!states.isEmpty()) terrainChanged(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
		}

		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onPlace(BlockPlaceEvent event) {
			if (!states.isEmpty()) terrainChanged(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
		}

		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onEntityExplode(EntityExplodeEvent event) {
			if (!states.isEmpty()) event.blockList().forEach(b -> terrainChanged(b.getWorld(), b.getX(), b.getY(), b.getZ()));
		}

		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onBlockExplode(BlockExplodeEvent event) {
			if (!states.isEmpty()) event.blockList().forEach(b -> terrainChanged(b.getWorld(), b.getX(), b.getY(), b.getZ()));
		}
	}

	// ------------------------------------------------------------------ the world

	/** The blocks the beam looks at, straight from the world, never loading a chunk. */
	private record WorldGrid(World world) implements LightBeam.Grid {
		@Override
		public LightBeam.Kind kind(int x, int y, int z) {
			if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return LightBeam.Kind.BLOCKED;
			Material m = world.getType(x, y, z);
			int level = 0;
			if (m == Material.WATER && world.getBlockData(x, y, z) instanceof Levelled l) level = l.getLevel();
			return LightBeam.classify(m.getKey().getKey(), level);
		}
	}

	/** Sends a viewer the light, or the real blocks, of one world: a viewer who isn't in it now is skipped (their client forgot it). */
	private final class WorldSender implements LightLedger.Sender {
		private final World world;

		WorldSender(World world) {
			this.world = world;
		}

		@Override
		public void lights(UUID viewer, Map<Long, Light> cells) {
			Player v = viewerIn(viewer);
			if (v == null) return;
			Map<Position, BlockData> out = new HashMap<>();
			for (Map.Entry<Long, Light> e : cells.entrySet()) {
				Pos pos = Pos.fromLong(e.getKey());
				out.put(Position.block(pos.x(), pos.y(), pos.z()), lightBlock(e.getValue()));
			}
			v.sendMultiBlockChange(out);
		}

		@Override
		public void restore(UUID viewer, Set<Long> cells) {
			Player v = viewerIn(viewer);
			if (v == null) return;
			Map<Position, BlockData> out = new HashMap<>();
			for (Long key : cells) {
				Pos pos = Pos.fromLong(key);
				// a chunk the server has unloaded is gone from the client too
				if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue;
				out.put(Position.block(pos.x(), pos.y(), pos.z()), world.getBlockData(pos.x(), pos.y(), pos.z()));
			}
			if (!out.isEmpty()) v.sendMultiBlockChange(out);
		}

		private @Nullable Player viewerIn(UUID id) {
			Player v = Bukkit.getPlayer(id);
			return v != null && v.isOnline() && v.getWorld().equals(world) ? v : null;
		}
	}

	private BlockData lightBlock(Light l) {
		int level = Math.clamp(l.level(), 0, 15);
		int w = l.waterlogged() ? 1 : 0;
		BlockData d = lightBlocks[w][level];
		if (d == null) {
			org.bukkit.block.data.type.Light light = (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
			light.setLevel(level);
			light.setWaterlogged(l.waterlogged());
			lightBlocks[w][level] = d = light;
		}
		return d;
	}

	private void warn(String message, Throwable t) {
		ArmContext c = ctx;
		if (c != null) c.plugin().getLogger().log(Level.WARNING, message, t);
	}

	// ------------------------------------------------------------------ settings

	/** arms.lantern, read leniently: a missing or odd value is the default. */
	private record Settings(int updateTicks, double viewerRange, int[] brightReach) {
		static final Settings DEFAULTS = parse(Map.of());

		/** How far (blocks) the light reaches round its holder at each brightness, 1 to 5. */
		int brightReach(int bright) {
			return Math.clamp(brightReach[Math.clamp(bright, 1, 5) - 1], 0, 64);
		}

		static Settings parse(@Nullable Map<String, Object> m) {
			Map<String, Object> s = m == null ? Map.of() : m;
			return new Settings((int) Math.clamp(Math.round(number(s.get("update-ticks"), 2)), 1, 20), Math.max(8, number(s.get("viewer-range"), 64)),
					fives(s.get("brightness-reach"), 8, 11, 14, 20, 30));
		}

		/** Five numbers (brightness 1 to 5): a list (a short one keeps its last for the rest), or the fallback. */
		private static int[] fives(@Nullable Object v, int... fallback) {
			int[] out = fallback.clone();
			if (v instanceof List<?> list && !list.isEmpty()) {
				int last = out[0];
				for (int i = 0; i < 5; i++) {
					double x = i < list.size() ? number(list.get(i), Double.NaN) : Double.NaN;
					out[i] = Double.isNaN(x) ? (i < list.size() ? out[i] : last) : (int) Math.clamp(Math.round(x), 0, 64);
					last = out[i];
				}
			}
			return out;
		}

		private static double number(@Nullable Object o, double fallback) {
			if (o instanceof Number n) return n.doubleValue();
			if (o != null) {
				try {
					return Double.parseDouble(o.toString().trim().toLowerCase(Locale.ROOT));
				} catch (NumberFormatException e) {
					return fallback;
				}
			}
			return fallback;
		}
	}
}
