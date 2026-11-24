package dev.morphreborn.paper.arm.accessory;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.rules.SpyglassMarks;
import dev.morphreborn.paper.arm.rules.SpyglassRules;
import dev.morphreborn.paper.arm.rules.SpyglassSettings;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.arm.special.ArmTouch;
import dev.morphreborn.paper.tips.Lines;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;

/**
 * The Tendril Spyglass (my design, 2026-11-16, 8.0.0-beta.2): an eye on a stalk over the arm, on beside any form (several
 * accessories may be on at once). It does two things on the server; the zoom itself is the client's.
 * <ul>
 *   <li><b>The zoom signal.</b> The client's zoom key (Z, held) is told to the server as C_ARM {@code ACTION_ACCESSORY_HOLD}
 *       ({@link #hold}): 1 when it goes down (repeated about every 10 seconds while it stays down), 0 when it is let go. {@link #hold}
 *       only records what the key says; {@link #tick} acts on it, at most once per {@link #ZOOM_GAP_TICKS}, so a client that flips the key
 *       a hundred times a second makes at most a few S_ARM broadcasts (2026-11-16). S_ARM shows the
 *       state to everyone as the accessory's {@link #arg} (1 zooming, 0 not), so the eye stalk is seen to raise. A zoom the client has not
 *       repeated for {@code zoom-timeout-seconds} is over, so a lost 0 cannot leave it on for ever. The zoom is never kept: it is off
 *       after a rejoin, a death, a teleport or any switch off, like the accessory itself.</li>
 *   <li><b>The marks.</b> While the owner zooms, every {@code scan-ticks} the server traces their eyes' line ({@code range} blocks by
 *       level: 64 / 96 / 128, never past the chunks their game was sent, never through a block; the level is read live, so an upgrade
 *       or an admin's change counts at the next look, unless an admin switched the accessory on at a level of their own) and marks the
 *       creature it meets, up to {@code marks} at once (1 / 2 / 3). Another player is marked only where they could be struck
 *       (arms.players on, the world's PvP on, not a teammate of friendly fire off; the {@code mark-players} setting too). A marked creature <b>glows for the owner alone</b>: the glow bit of its flags byte (metadata
 *       index 0) is set in the owner's copy only, through PacketEvents ({@link SpyglassGlow}; where PacketEvents isn't installed
 *       nothing glows but the HUD still gets the marks). The mark is kept up while it is looked at, lasts {@code mark-seconds} (5) after
 *       the last glance, goes when a new one needs its room (the one seen longest ago), when the creature dies, leaves the range or is
 *       no longer visible to the owner, and all of them go when the zoom ends, the accessory goes off, or the owner leaves.</li>
 * </ul>
 * <b>What the client is told.</b> S_ARM_FX kind FX_ACCESSORY with {@link SpyglassRules#fxArg} (the spyglass is place 2), to the owner
 * only (see {@link #OWNER_ONLY}): {@link #FX_MARK} when a creature is marked, again every second while it is kept up, and once more when a
 * look moves on from a mark that was last seen after the client was told (so its clock ends where the server's does), and
 * {@link #FX_UNMARK} when marks end. The client shows each marked creature's distance on its HUD and takes a mark away when the message
 * says so, or {@code ticks} after the last FX_MARK (a mark the server stopped announcing: ticks is the mark's time left, not its whole life).
 * <p>
 * The bow's Glowing arrow tip is on offer while this accessory is on (BowTips asks the manager's accessory API); marking has no cost of its
 * own beyond the accessory's drain (arm-cost.drain-per-minute.spyglass).
 */
public final class SpyglassAccessory implements ArmAccessoryModule {
	public static final String ID = "spyglass";
	/** Where the glow module is found (it needs PacketEvents, so this class never names it in code that links). */
	private static final String GLOW = "dev.morphreborn.paper.arm.accessory.SpyglassGlow";

	// FX_ACCESSORY moves (SpyglassRules.fxArg: the arg is fxArg(move), the spyglass's place 2 in bits 8-11 and the move in bits 0-7). Never renumber.
	/**
	 * A creature is marked, or still is (repeated every {@link SpyglassRules#resendTicks} (20 ticks at most) while it is looked at, and when the look moves
	 * on from it): level = the accessory's level now, ticks = how long the mark has left without another glance (the server's own clock:
	 * the client's timer is set to it, so the two end together), ids = [the creature], positions = [the block it is in] (informational: the
	 * client looks the entity up by id and drops a mark whose entity it does not have until the next message). The client shows its distance
	 * on the HUD; one message per creature, so several marks are several messages.
	 */
	public static final int FX_MARK = SpyglassRules.FX_MARK;
	/**
	 * Marks ended: level = the accessory's level, ticks 0, ids = the creatures that are no longer marked, or empty for all of them, positions
	 * empty. (An unmarked creature stops glowing: the server sets its flags again.)
	 */
	public static final int FX_UNMARK = SpyglassRules.FX_UNMARK;

	/** The radius that reaches nobody but the origin itself (no viewer's distance is {@code <= NaN}): the marks are the marker's alone, and radius 0 would also reach anyone standing exactly where they do. */
	private static final double OWNER_ONLY = Double.NaN;
	/** The fewest ticks between two changes of the zoom (a start or an end) for one player. */
	public static final int ZOOM_GAP_TICKS = 4;
	/** How many times a look is traced again past a creature the marker may not mark (not visible to them, not tracked, another player's rules). */
	private static final int RETRACES = 3;

	/** What makes a marked creature glow for its owner alone: PacketEvents' flags byte, kept apart so this class loads without PacketEvents. */
	public interface Glow {
		/** Called before the first mark and after the last one (a packet listener is on while any player has the spyglass). */
		void open();

		void close();

		/** The owner's copy of the target's flags with the glow bit set ({@code glow}) or as it really is. Main thread; never throws. */
		void show(Player owner, Entity target, boolean glow);

		/** The owner is gone: forget what was marked for them without sending anything. */
		void forget(int ownerEntityId);
	}

	/** No glow: where PacketEvents isn't installed. */
	private static final Glow NO_GLOW = new Glow() {
		@Override
		public void open() {}

		@Override
		public void close() {}

		@Override
		public void show(Player owner, Entity target, boolean glow) {}

		@Override
		public void forget(int ownerEntityId) {}
	};

	private @Nullable ArmContext ctx;
	private SpyglassSettings settings = SpyglassSettings.DEFAULTS;
	private Glow glow = NO_GLOW;
	private boolean glowOpen;
	/** Who has it on (main thread only). */
	private final Map<UUID, State> states = new LinkedHashMap<>();
	/** The look being traced now (main thread only): its owner and the creatures it has passed over, which the ray's filter keeps in one place instead of one object per look. */
	private @Nullable Player scanOwner;
	private final ArrayList<Entity> passedOver = new ArrayList<>(RETRACES);
	private final Predicate<Entity> rayFilter = this::cheapMarkable;

	/** One spyglass that is on. */
	private static final class State {
		final UUID id;
		final int entityId, since;
		/** The level it works at: read live from the player's own level at each look, unless {@code fixed} (an admin switched it on at a level of their own). */
		int level;
		final boolean fixed;
		boolean zooming;
		/** What the client's key last said, held or not (applied by the tick) and the tick the zoom last changed. */
		boolean wantZoom;
		int changedAt;
		/** The tick the client last said the key was held, and the tick of the last look. */
		int lastHold, lastScan;
		final SpyglassMarks marks = new SpyglassMarks();
		/** The marked creatures by entity id, to set their flags back; a creature that is gone is dropped with its mark. */
		final HashMap<Integer, Entity> entities = new HashMap<>(4);

		State(Player p, int level, boolean fixed, int now) {
			this.id = p.getUniqueId();
			this.entityId = p.getEntityId();
			this.level = level;
			this.fixed = fixed;
			this.since = now;
			this.changedAt = now - ZOOM_GAP_TICKS; // the first change need not wait
		}
	}

	public SpyglassAccessory() {}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		load(ctx.config().accessory(ID));
		try {
			Object made = Class.forName(GLOW, true, SpyglassAccessory.class.getClassLoader()).getConstructor().newInstance();
			if (made instanceof Glow g) glow = g;
		} catch (ClassNotFoundException | LinkageError e) {
			noGlowQuietly(ctx);
		} catch (InvocationTargetException e) {
			// the constructor's own failure (its deliberate NoClassDefFoundError without PacketEvents) comes wrapped
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof LinkageError || cause instanceof IllegalStateException) noGlowQuietly(ctx);
			else ctx.plugin().getLogger().log(Level.WARNING, "The Tendril Spyglass's glow failed to start; marked creatures won't glow.", cause);
		} catch (Throwable t) {
			ctx.plugin().getLogger().log(Level.WARNING, "The Tendril Spyglass's glow failed to start; marked creatures won't glow.", t);
		}
	}

	private static void noGlowQuietly(ArmContext ctx) {
		ctx.plugin().getLogger().info("The Tendril Spyglass won't make marked creatures glow: it needs PacketEvents, which isn't installed. The HUD marks still work.");
	}

	/** arms.accessories.spyglass as plain values; anything missing or unreadable keeps the bundled default. */
	@Override
	public void load(Map<String, Object> section) {
		settings = SpyglassSettings.parse(section);
	}

	// ------------------------------------------------------------------ on or off

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
		if (c == null) return "The Tendril Spyglass isn't ready yet.";
		if (states.containsKey(p.getUniqueId())) return "Your spyglass is already on.";
		int own = c.level(p, ID);
		states.put(p.getUniqueId(), new State(p, Math.clamp(level, 1, 3), own != level, c.tick()));
		openGlow();
		return null;
	}

	/** S_ARM's accessory argument for the spyglass: 1 while its owner zooms, 0 otherwise. */
	@Override
	public int arg(Player p) {
		State s = states.get(p.getUniqueId());
		return s != null && s.zooming ? 1 : 0;
	}

	@Override
	public void switchOff(Player p, int why) {
		State s = states.remove(p.getUniqueId());
		if (s == null) return;
		release(p, s, why != ArmProtocol.WHY_QUIT);
		closeGlowIfIdle();
	}

	@Override
	public void switchOffAll() {
		for (Iterator<State> it = states.values().iterator(); it.hasNext();) {
			State s = it.next();
			it.remove();
			Player p = Bukkit.getPlayer(s.id);
			if (p != null) release(p, s, true);
			else drop(s);
		}
		closeGlowIfIdle();
	}

	// ------------------------------------------------------------------ the zoom signal

	/**
	 * The client's zoom key goes down (also repeated while held, to keep the zoom alive) or is let go. Only called while the accessory is on.
	 * It only records: {@link #tick} starts or ends the zoom, so no number of calls in a tick makes more than one change.
	 */
	@Override
	public void hold(Player p, boolean held) {
		ArmContext c = ctx;
		State s = states.get(p.getUniqueId());
		if (c == null || s == null) return;
		s.wantZoom = held;
		if (held) s.lastHold = c.tick();
	}

	private void startZoom(Player p, State s, ArmContext c) {
		s.zooming = true;
		s.lastScan = c.tick() - settings.scanTicks(); // the first look is this tick's
		c.accessoryChanged(p);
	}

	/** The zoom is over: the marks go with it, and everyone is told it is lowered. */
	private void endZoom(Player p, State s, ArmContext c) {
		s.zooming = false;
		unmark(p, s, s.marks.clear(), c, true);
		c.accessoryChanged(p);
	}

	// ------------------------------------------------------------------ the tick

	/** Once a tick: a spyglass whose owner is gone is put out, a zoom nobody repeated ends, and a zooming owner's eyes are looked along every {@code scan-ticks}. */
	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || states.isEmpty()) return;
		int now = c.tick();
		boolean any = false;
		for (Iterator<State> it = states.values().iterator(); it.hasNext();) {
			State s = it.next();
			Player p = Bukkit.getPlayer(s.id);
			if (p == null || !p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
				it.remove();
				if (p != null) release(p, s, p.isOnline()); // still here (dead, a spectator): its client is told the marks are gone
				else drop(s);
				any = true;
				continue;
			}
			try {
				if (s.zooming && now - s.lastHold > settings.zoomTimeoutTicks()) {
					s.wantZoom = false; // nobody repeated the key: it is over
					s.changedAt = now;
					endZoom(p, s, c);
				} else if (s.wantZoom != s.zooming && now - s.changedAt >= ZOOM_GAP_TICKS) {
					s.changedAt = now;
					if (s.wantZoom) startZoom(p, s, c);
					else endZoom(p, s, c);
				}
				if (!s.zooming) continue;
				if (now - s.lastScan >= settings.scanTicks()) {
					s.lastScan = now;
					scan(p, s, c, now);
				}
			} catch (Throwable t) {
				c.plugin().getLogger().log(Level.WARNING, "The spyglass failed to look for " + p.getName() + ".", t);
			}
		}
		if (any) closeGlowIfIdle();
	}

	@Override
	public Lines describeLines(int level) {
		return settings.lines(level);
	}

	// ------------------------------------------------------------------ the marks

	/** The level this owner's spyglass works at now: their own live level, or the one an admin switched it on at. */
	private int levelNow(Player p, State s, ArmContext c) {
		if (s.fixed) return s.level;
		int own = c.level(p, ID);
		if (own >= 1) s.level = Math.clamp(own, 1, 3);
		return s.level;
	}

	/** One look: marks that no longer hold go, then the creature along the owner's eyes (if any) is marked or kept. */
	private void scan(Player p, State s, ArmContext c, int now) {
		int level = levelNow(p, s, c);
		double range = settings.rangeAt(level);
		int cap = settings.marksAt(level);
		int life = settings.markTicks();
		Location eye = p.getEyeLocation();
		// a level that went down (an admin lowered the upgrade) leaves too many marks: the ones seen longest ago go
		unmark(p, s, s.marks.trim(cap), c, false);
		// marks whose creature is gone, out of range, or hidden from the owner, then those not looked at for the mark's life
		if (!s.entities.isEmpty()) {
			int[] gone = new int[s.entities.size()];
			int n = 0;
			Location at = p.getLocation();
			for (Map.Entry<Integer, Entity> e : s.entities.entrySet()) {
				Entity m = e.getValue();
				if (!markable(p, m, c) || !SpyglassRules.inRange(m.getLocation().distanceSquared(at), range)) gone[n++] = e.getKey();
			}
			for (int i = 0; i < n; i++) s.marks.remove(gone[i]);
			unmark(p, s, java.util.Arrays.copyOf(gone, n), c, false);
		}
		unmark(p, s, s.marks.expire(now, life), c, false);
		Entity target = look(p, eye, SpyglassRules.reach(range, p.getSendViewDistance()), c);
		int looked = SpyglassMarks.NO_ONE;
		if (target != null) {
			looked = target.getEntityId();
			SpyglassMarks.Seen seen = s.marks.see(looked, now, cap, life, SpyglassRules.resendTicks(life, settings.scanTicks()));
			if (seen.evicted() != SpyglassMarks.NO_ONE) unmark(p, s, new int[] {seen.evicted()}, c, false);
			if (seen.isNew()) {
				s.entities.put(looked, target);
				glow.show(p, target, true);
			}
			if (seen.announce()) announce(p, s, c, target, level, life);
		}
		// a mark the look has moved off, last seen after its client was told: the client's clock is set again, so it ends where ours does
		int[] stale = s.marks.stale(now, life, looked);
		for (int id : stale) {
			Entity e = s.entities.get(id);
			if (e != null) announce(p, s, c, e, level, s.marks.left(id, now, life));
		}
	}

	/** FX_MARK for {@code target}: level, ticks = its time left (the whole {@code left} when a mark is made or kept up), the creature and its block. */
	private void announce(Player p, State s, ArmContext c, Entity target, int level, int left) {
		Location at = target.getLocation();
		c.fx(p, new ArmFxMsg(s.entityId, ArmProtocol.FX_ACCESSORY, level, left, ThreadLocalRandom.current().nextInt(), new int[] {target.getEntityId()},
				new long[] {new Pos(at.getBlockX(), at.getBlockY(), at.getBlockZ()).asLong()}, SpyglassRules.fxArg(FX_MARK)), OWNER_ONLY);
	}

	/**
	 * The creature along {@code eye}'s line that the owner may mark, or null. The ray's own filter sees every creature in the box round the
	 * line (a long diagonal is a huge box), so it takes only the cheap tests ({@link #cheapMarkable}); the dear ones (does the owner's game
	 * have it, may a player be marked) are made on the creature the ray hits, and a creature that fails them is passed over and the line
	 * traced again, a few times at most.
	 */
	private @Nullable Entity look(Player owner, Location eye, double reach, ArmContext c) {
		scanOwner = owner;
		passedOver.clear();
		try {
			for (int tries = 0; tries <= RETRACES; tries++) {
				RayTraceResult hit = owner.getWorld().rayTrace(eye, eye.getDirection(), reach, FluidCollisionMode.NEVER, true, settings.rayWidth(), rayFilter);
				if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) return null;
				if (markable(owner, target, c)) return target;
				passedOver.add(target);
			}
			return null;
		} finally {
			scanOwner = null;
			passedOver.clear();
		}
	}

	/**
	 * The cheap part of whether the look's owner may mark {@code e} (the ray's filter, for every creature near the line): a living creature that
	 * is alive and valid, that isn't invisible, an armour stand, the owner or what they ride or carry, a spectator, or one already passed over.
	 */
	private boolean cheapMarkable(Entity e) {
		Player owner = scanOwner;
		if (owner == null || e == owner || !(e instanceof LivingEntity living) || living instanceof ArmorStand || !e.isValid() || living.isDead() || living.isInvisible()) return false;
		if (owner.getVehicle() == e || e.getVehicle() == owner || e instanceof Player other && other.getGameMode() == GameMode.SPECTATOR) return false;
		for (int i = 0; i < passedOver.size(); i++) {
			if (passedOver.get(i) == e) return false;
		}
		return true;
	}

	/**
	 * Whether {@code e} may be marked by {@code owner}: the cheap tests ({@link #cheapMarkable}), in the owner's world, that the owner's game
	 * has (their client tracks it, so a glow and a distance mean something to it), and, for another player, that the marker's settings
	 * and the server's PvP rules allow it (mark-players, arms.players, the world's PvP, no teammate of a team that is safe from friendly
	 * fire: the checks the arm's strikes make, without the test hit).
	 */
	private boolean markable(Player owner, Entity e, ArmContext c) {
		Player was = scanOwner;
		scanOwner = owner;
		try {
			if (!cheapMarkable(e)) return false;
		} finally {
			scanOwner = was;
		}
		if (e.getWorld() != owner.getWorld() || !owner.canSee(e) || !e.getTrackedBy().contains(owner)) return false;
		if (e instanceof Player other) {
			return settings.markPlayers() && c.config().players() && other.getWorld().getPVP() && !ArmTouch.teamProtects(owner, other);
		}
		return true;
	}

	/**
	 * Marks ended (already out of the set): each one's glow put back, and the owner told once. {@code all} says the zoom ended: the message
	 * then carries no ids (every mark, even ones only the client holds) and is sent even when the server held none.
	 */
	private void unmark(Player owner, State s, int[] ids, ArmContext c, boolean all) {
		if (ids.length == 0 && !all) return;
		for (int id : ids) {
			Entity e = s.entities.remove(id);
			if (e != null) glow.show(owner, e, false);
		}
		c.fx(owner, new ArmFxMsg(s.entityId, ArmProtocol.FX_ACCESSORY, s.level, 0, 0, all ? new int[0] : ids, new long[0], SpyglassRules.fxArg(FX_UNMARK)), OWNER_ONLY);
	}

	/** The accessory is going off with its owner here: every glow put back and the client told, unless {@code tell} is off (the owner is quitting). */
	private void release(Player p, State s, boolean tell) {
		ArmContext c = ctx;
		int[] ids = s.marks.clear();
		try {
			for (int id : ids) {
				Entity e = s.entities.remove(id);
				if (e != null) glow.show(p, e, false);
			}
			if (tell && c != null && (ids.length > 0 || s.zooming)) {
				c.fx(p, new ArmFxMsg(s.entityId, ArmProtocol.FX_ACCESSORY, s.level, 0, 0, new int[0], new long[0], SpyglassRules.fxArg(FX_UNMARK)), OWNER_ONLY);
			}
		} catch (Throwable t) {
			if (c != null) c.plugin().getLogger().log(Level.WARNING, "The spyglass failed to let go of its marks for " + p.getName() + ".", t);
		}
		drop(s);
	}

	/** Forget everything about an owner who isn't here to be sent anything. */
	private void drop(State s) {
		s.marks.clear();
		s.entities.clear();
		glow.forget(s.entityId);
	}

	private void openGlow() {
		if (glowOpen) return;
		glowOpen = true;
		try {
			glow.open();
		} catch (Throwable t) {
			glow = NO_GLOW; // PacketEvents is there but not ready: nothing glows, the marks still go to the HUD
			ArmContext c = ctx;
			if (c != null) c.plugin().getLogger().log(Level.WARNING, "The Tendril Spyglass's glow couldn't start; marked creatures won't glow.", t);
		}
	}

	private void closeGlowIfIdle() {
		if (!states.isEmpty() || !glowOpen) return;
		glowOpen = false;
		try {
			glow.close();
		} catch (Throwable t) {
			// shutting down: nothing left to tell
		}
	}
}
