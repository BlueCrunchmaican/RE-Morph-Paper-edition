package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ImpaleMath;
import dev.morphreborn.paper.arm.rules.SpearSettings;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilKills;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Impale, the spear arm's special (F; my design, 2026-11-16, 8.0.0-beta.2). The arm is a real spear of the arm's tier
 * (iron, diamond or netherite: ArmItems gives it the vanilla spear's own components), so the jab on left-click and the charge on
 * hold-right-click are vanilla's, billed per hit like any melee hit (ArmBilling). F adds the move the spear can't do:
 * <ol>
 * <li><b>The lunge.</b> F finds the creature you aim at (the look's ray, else a flat one at chest height), and you lunge along the
 * ground toward it, 3 / 4 / 5 blocks at most by tier, stopping a spear's length short of it. The lunge keeps to the ground
 * (rules/ImpaleMath: it climbs what a walk climbs, stops at a wall, a ceiling or a ledge, and never leaves the ground) and is
 * driven by velocity for a handful of ticks, as the whip's reel is.</li>
 * <li><b>The skewer.</b> When the lunge ends the spear strikes the creature for a tendril's blow (damage by tier, protection and
 * PvP rules as the whip's: ArmTouch).</li>
 * <li><b>The pin.</b> Spikes erupt from the ground round it and hold it where it stands, 1.5 / 2 / 2.5 s (ArmHold: the whip's Snare
 * way, so a walker, a floater, a swimmer and a hopper are each stopped as they move). Players are pinned only when
 * {@code pin-players} is on (off, as the whip's snare-players); bosses and other players' pets never are.</li>
 * </ol>
 * Nothing in reach is free and quiet (my design leaves a swing at nothing to cost nothing, like every other form): no
 * target means no lunge, no cost, no cooldown, and the manager's short lockout. A lunge is a way to close on a creature, not a
 * dash, so it never happens without one: that also keeps F from being a cheap way to cross ground, off ledges or into voids.
 * <p>
 * The clients are sent S_ARM_FX kind FX_SPEAR three ways, by {@code arg}: {@link #FX_LUNGE} when F is paid, {@link #FX_PIN} when the
 * skewer lands, and {@link #FX_RELEASE} when a pin is cut short; their fields are documented on each constant. (The three-message
 * form is my decision of 2026-11-16 after review: the client's SpearFx was rewritten to it, not the other way round.)
 */
public final class SpearImpale implements ArmSpecialModule {
	public static final String FORM = "spear";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "spear";

	/**
	 * FX_SPEAR {@code arg}: the lunge, sent when F is paid. level = the tier; ticks = how long the lunge takes; ids = [the creature];
	 * positions = [the block the lunger's feet start in, the block they end in] (BlockPos.asLong); seed = the clients' randomness.
	 * The clients draw only the lunge's trail from it (nothing has landed yet): the cage and skewer wait for {@link #FX_PIN}.
	 */
	public static final int FX_LUNGE = 1;
	/**
	 * FX_SPEAR {@code arg}: the skewer landed, sent when the lunge ends. level = the tier; ticks = how long the spikes hold it (0: it
	 * is skewered but not held, or it died of the blow, so the spikes only break through and withdraw); ids = [the creature];
	 * positions = [the block under the creature's feet: the cage stands on that block's top]; seed as above. The spikes are timed by
	 * the clients' own clock from receipt: they hold for {@code ticks}, then sink. Sent only when the blow landed.
	 */
	public static final int FX_PIN = 2;
	/** FX_SPEAR {@code arg}: a pin ended early (the creature died or went, the module was reset). ids = [the creature]; the spikes withdraw at once. */
	public static final int FX_RELEASE = 3;

	/** The pin's modifier key: this module's own, so the whip's snare and this never free each other's creature. */
	private static final NamespacedKey PIN_MODIFIER = MorphRebornPlugin.key("spear_pin");
	/** How wide F's aim is: it finds a creature this close to the line of sight (the whip's). */
	private static final double AIM_RAY = 0.6;
	/** The aim looks this far past where the lunge and the spear can together reach (a creature's half width and a little slack). */
	private static final double AIM_SLACK = 1.5;
	/** Ticks without progress that end a lunge that has begun to move (a creature or a wall in the way). */
	private static final int STALL_TICKS = 3;
	/** A target that moved off while the lunge ran is still skewered within this much more than the spear's reach. */
	private static final double MOVED_SLACK = 1.0;
	/** The held creature is puffed for the players whose game doesn't draw the spikes this often, in ticks. */
	private static final int PUFF_TICKS = 10;
	/** The server's fallback tendril's thickness in pixels, at the arm and at the end. */
	private static final float LUNGE_FROM_PX = 1.6F, LUNGE_TO_PX = 0.6F;
	/** The spikes of the fallback: how many round the creature, and how tall. */
	private static final int FALLBACK_SPIKES = 6;
	private static final double SPIKE_RING = 0.9, SPIKE_HEIGHT = 1.4;

	private @Nullable ArmContext ctx;
	private SpearSettings settings = SpearSettings.DEFAULTS;
	/** Viewers whose game draws the new forms' effects (8.0.0-beta.2 and later: FX_SPEAR reaches only them); every particle fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private final ArmHold pins = new ArmHold(PIN_MODIFIER);
	/** Who owns each pin and the pinned creature's entity id, so a pin cut short can always name the one creature it ended on (a RELEASE never has to say -1 and sink the owner's others; second review 2026-11-16). */
	private final Map<UUID, PinFx> pinFx = new HashMap<>();
	private record PinFx(UUID owner, int entityId) {}
	/** The lunges under way, by owner. */
	private final Map<UUID, Run> runs = new HashMap<>();
	/** The runs a tick steps, copied out of {@link #runs} first: a damage event mid-step may remove one (a death, a retract). Reused, so a tick allocates nothing. */
	private final List<Run> stepping = new ArrayList<>();
	private final Listener events = new Events();
	/** The blow the skewer is striking now: did its damage event run, and did anything cancel it (a claim, a PvP rule). */
	private @Nullable Player blowOwner;
	private @Nullable LivingEntity blowTarget;
	private boolean blowSeen, blowCancelled;

	/** One lunge: who, at what, where along the ground, and since when. */
	private static final class Run {
		final UUID owner, target;
		final long token;
		final World world;
		final int level, seed, start, ticks;
		/** Ticks of the owner's ping the lunge's clock is given on top of its own (the velocity takes a ping to show as movement). */
		final int lag;
		final double sx, sz, dx, dz, distance, endY;
		int stalled;
		double lastAlong;
		/** The speeds the last ticks set, newest at {@link #sent} (blocks a tick): what is still on its way to a client whose picture the server has a ping late. */
		final double[] speeds = new double[ImpaleMath.MAX_LAG_TICKS];
		int sent;
		/** The owner has been seen to move along the lunge: only from then on does a stand-still count as a stall (a ping hides the first ticks). */
		boolean moved;

		/** The distance the speeds set in the last {@link #lag} ticks (no more than have been set) will still move the owner. */
		double inFlight(int elapsed) {
			double sum = 0;
			int n = Math.min(Math.min(lag, elapsed), sent);
			for (int i = 1; i <= n; i++) sum += speeds[(sent - i) % speeds.length];
			return sum;
		}

		Run(Player owner, UUID target, long token, int level, int seed, int start, int ticks, double dx, double dz, double distance, double endY) {
			this.owner = owner.getUniqueId();
			this.target = target;
			this.token = token;
			this.world = owner.getWorld();
			this.level = level;
			this.seed = seed;
			this.start = start;
			this.ticks = ticks;
			this.lag = ImpaleMath.lagTicks(owner.getPing());
			Location at = owner.getLocation();
			this.sx = at.getX();
			this.sz = at.getZ();
			this.dx = dx;
			this.dz = dz;
			this.distance = distance;
			this.endY = endY;
		}
	}

	/** A tendril's skin key: one per player, so a new lunge replaces their last. */
	private record LungeKey(UUID owner) {}

	public SpearImpale() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsNewForms;
		this.settings = SpearSettings.parse(ctx.config().form(FORM));
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = SpearSettings.parse(formSection);
	}

	@Override
	public @Nullable Listener listener() {
		return events;
	}

	@Override
	public boolean breaksBlocks() {
		return false;
	}

	// ------------------------------------------------------------------ F

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		int lvl = level(level);
		// a pinned player's velocity isn't stopped by the hold's attributes, so a lunge would carry them out of it
		if (pins.isHeld(p.getUniqueId(), now(c))) return SpecialResult.refused("You can't lunge while you're pinned.");
		if (p.isInsideVehicle() || p.isGliding() || p.isFlying() || p.isSwimming()) return SpecialResult.refused(settings.name() + " needs your feet on the ground.");
		World world = p.getWorld();
		Location feet = p.getLocation();
		WorldSpace space = new WorldSpace(world, p.getBoundingBox());
		if (!space.supported(feet.getX(), feet.getY(), feet.getZ())) return SpecialResult.refused(settings.name() + " needs your feet on solid ground.");
		Location eye = p.getEyeLocation();
		Vector look = eye.getDirection();
		double range = settings.lungeAt(lvl), reach = settings.reach();
		LivingEntity t = aim(c, p, eye, look, range + reach + AIM_SLACK);
		if (t == null) return SpecialResult.noTarget("Nothing to impale in reach");
		// along the ground toward it (not along the look, which may be up or down), and how far to go to end a spear's length short
		double tx = t.getLocation().getX() - feet.getX(), tz = t.getLocation().getZ() - feet.getZ();
		double[] dir = ImpaleMath.flat(tx, 0, tz, look.getX(), look.getZ());
		double gap = ImpaleMath.gap(tx, tz, t.getWidth() / 2);
		double wanted = ImpaleMath.wanted(gap, range);
		ImpaleMath.Lunge plan = ImpaleMath.lunge(feet.getX(), feet.getY(), feet.getZ(), dir[0], dir[1], wanted, space);
		if (!ImpaleMath.reaches(gap, plan.distance(), reach)) {
			return SpecialResult.noTarget(plan.distance() < wanted - 1e-3 ? "Something is in the way" : "Too far to impale");
		}
		// the strike measures the same height, so a creature the spear could never reach costs nothing (it was a paid whiff before)
		if (!ImpaleMath.withinHeight(plan.y(), t.getLocation().getY())) return SpecialResult.noTarget("Too far to impale");
		// a claim or a no-PvP world says no before anything is paid
		if (!ArmTouch.mayAffect(c, p, t)) return SpecialResult.noTarget("You can't impale that here.");
		if (!charge.pay(costMilli(lvl), cooldownTicks(lvl))) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c);
		int seed = ThreadLocalRandom.current().nextInt(1 << 30);
		int ticks = ImpaleMath.lungeTicks(plan.distance());
		Run old = runs.remove(p.getUniqueId());
		if (old != null) damp(p); // the gate refuses while one runs, so only ever a leftover
		Run run = new Run(p, t.getUniqueId(), c.token(p), lvl, seed, now, ticks, dir[0], dir[1], plan.distance(), plan.y());
		runs.put(p.getUniqueId(), run);
		p.swingMainHand();
		world.playSound(feet, Sound.ITEM_SPEAR_LUNGE_1, SoundCategory.PLAYERS, 1F, 1F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_SPEAR, lvl, ticks, seed, new int[] {t.getEntityId()},
				new long[] {block(feet.getX(), feet.getY(), feet.getZ()), block(plan.x(), plan.y(), plan.z())}, FX_LUNGE), ArmProtocol.fxRadius(ArmProtocol.FX_SPEAR));
		fallbackLine(c, p, bodyOf(t), seed);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " lunges " + number(plan.distance()) + " blocks at " + t.getName() + " (" + ticks + " ticks)");
		}
		try {
			step(c, run, now); // the first push now, not a tick later
		} catch (RuntimeException ex) {
			runs.remove(run.owner, run);
			c.plugin().getLogger().log(Level.WARNING, "Impale failed at the first step", ex);
		}
		return SpecialResult.FIRED;
	}

	/** The creature F aims at: the look's ray within {@code reach}, else a flat ray at chest height along the horizontal look; null when a block comes first or nothing is there. */
	private @Nullable LivingEntity aim(ArmContext c, Player p, Location eye, Vector look, double reach) {
		World w = p.getWorld();
		RayTraceResult hit = w.rayTrace(eye, look, reach, FluidCollisionMode.NEVER, true, AIM_RAY, e -> aimable(c, p, e));
		if (hit == null || !(hit.getHitEntity() instanceof LivingEntity)) {
			double[] flat = ImpaleMath.flat(look.getX(), look.getY(), look.getZ(), 0, 0);
			if (flat[0] != 0 || flat[1] != 0) {
				Location chest = p.getLocation().add(0, p.getBoundingBox().getHeight() * 0.5, 0);
				hit = w.rayTrace(chest, new Vector(flat[0], 0, flat[1]), reach, FluidCollisionMode.NEVER, true, AIM_RAY, e -> aimable(c, p, e));
			}
		}
		return hit != null && hit.getHitEntity() instanceof LivingEntity t ? t : null;
	}

	/** F's ray may find a creature a tendril may take hold of, and nothing else. */
	private boolean aimable(ArmContext c, Player p, Entity e) {
		return e instanceof LivingEntity && !e.equals(p) && TendrilTargets.canTarget(c.plugin(), p, e, true, c.config().players());
	}

	// ------------------------------------------------------------------ the lunge and the skewer

	/**
	 * One tick of a lunge: push along the ground. When it is over the run is taken out of {@link #runs} FIRST and then the skewer is
	 * struck, so whatever the strike does (a damage event that kills the owner, a handler that throws) can't leave the lunge standing
	 * to strike again every tick (review 2026-11-16).
	 */
	private void step(ArmContext c, Run run, int now) {
		Player p = c.plugin().getServer().getPlayer(run.owner);
		if (p == null || !p.isOnline() || p.isDead() || p.isInsideVehicle() || p.getWorld() != run.world || !c.stillArmed(p, run.token)) {
			runs.remove(run.owner, run);
			if (p != null && p.isOnline()) damp(p);
			return;
		}
		Location at = p.getLocation();
		double along = (at.getX() - run.sx) * run.dx + (at.getZ() - run.sz) * run.dz;
		int elapsed = now - run.start;
		// a wall, a creature or a lag that has stopped the lunge ends it where it is: the spear still strikes if it can. Only a lunge seen
		// to be moving can stall: the first ticks of a laggy player's lunge show no movement yet (the velocity takes a ping to arrive)
		if (!run.moved && along > 0.05) run.moved = true;
		if (run.moved && along - run.lastAlong < 0.05) run.stalled++;
		else run.stalled = 0;
		run.lastAlong = along;
		// the position read is a ping old: the client has gone on at the speed last set. Ending and easing by where it must be now (not
		// where it was) is what keeps a laggy player from overshooting the planned end, past a ledge or the target (second review 2026-11-16)
		double ahead = ImpaleMath.reckoned(along, run.inFlight(elapsed));
		if (ImpaleMath.over(ahead, run.distance, elapsed, run.ticks + run.lag) || run.stalled >= STALL_TICKS) {
			runs.remove(run.owner, run);
			damp(p);
			skewer(c, run, p, now);
			return;
		}
		double speed = ImpaleMath.speed(run.distance - ahead);
		run.speeds[run.sent++ % run.speeds.length] = speed;
		p.setVelocity(new Vector(run.dx * speed, ImpaleMath.vertical(run.endY - at.getY()), run.dz * speed));
		p.setFallDistance(0);
	}

	/** The lunge ends: what is left of its speed is taken off, so the player stops where it ended instead of sliding on. */
	private static void damp(Player p) {
		Vector v = p.getVelocity();
		p.setVelocity(new Vector(v.getX() * 0.2, Math.min(0, v.getY()), v.getZ() * 0.2));
		p.setFallDistance(0);
	}

	/** The spear strikes the creature, if it is still there and within reach: the blow, then the spikes. */
	private void skewer(ArmContext c, Run run, Player p, int now) {
		if (!(c.plugin().getServer().getEntity(run.target) instanceof LivingEntity t) || !alive(t) || t.getWorld() != p.getWorld()) return;
		Location me = p.getLocation(), there = t.getLocation();
		double gap = ImpaleMath.gap(there.getX() - me.getX(), there.getZ() - me.getZ(), t.getWidth() / 2);
		if (gap > settings.reach() + MOVED_SLACK || !ImpaleMath.withinHeight(me.getY(), there.getY()) || !clearBetween(p, t)) {
			// it moved off while the lunge ran, or a wall is in the way (a lunge stopped short by one): the spear finds air (the biomass is
			// spent, as for any swing that missed)
			p.getWorld().playSound(me, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8F, 1.2F);
			return;
		}
		boolean landed = hurt(c, p, t, settings.damage().at(run.level));
		if (!landed) return;
		Location body = bodyOf(t);
		boolean held = alive(t) && pinnable(c, p, t);
		int rootTicks = held ? settings.rootTicks(run.level) : 0;
		if (held) {
			t.setVelocity(new Vector()); // the blow's knockback isn't the spikes' to leave
			pins.hold(t, p.getUniqueId(), now + rootTicks);
			pinFx.put(t.getUniqueId(), new PinFx(p.getUniqueId(), t.getEntityId()));
		}
		World world = t.getWorld();
		world.playSound(body, Sound.ITEM_SPEAR_HIT, SoundCategory.PLAYERS, 1.2F, 0.9F);
		world.playSound(body, Sound.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 1.1F, 0.7F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_SPEAR, run.level, rootTicks, run.seed, new int[] {t.getEntityId()},
				new long[] {block(there.getX(), there.getY() - 0.1, there.getZ())}, FX_PIN), ArmProtocol.fxRadius(ArmProtocol.FX_SPEAR));
		fallbackSpikes(t);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " skewered " + t.getName() + (held ? " and pinned it for " + rootTicks + " ticks" : (alive(t) ? " (not pinnable)" : " (dead)")));
		}
	}

	/** Nothing solid between the owner's eyes and the creature (its middle, or its eyes): a spear doesn't go through a wall. */
	private static boolean clearBetween(Player p, LivingEntity t) {
		Location eye = p.getEyeLocation();
		return openTo(eye, bodyOf(t)) || openTo(eye, t.getEyeLocation());
	}

	private static boolean openTo(Location from, Location to) {
		Vector d = to.toVector().subtract(from.toVector());
		double len = d.length();
		if (len < 1e-3) return true;
		return from.getWorld().rayTraceBlocks(from, d.multiply(1 / len), len, FluidCollisionMode.NEVER, true) == null;
	}

	/**
	 * The spikes may hold it: not a boss or another player's pet, nothing the hold has no way to stop (an armour stand, a mob with
	 * its AI off), and a player only when the setting allows.
	 */
	private boolean pinnable(ArmContext c, Player p, LivingEntity t) {
		if (ArmHold.neverHeld(p, t) || ArmHold.kindOf(t) == WhipMoves.HoldKind.NONE) return false;
		return !(t instanceof Player) || settings.pinPlayers();
	}

	/**
	 * The skewer's damage, as a tendril's hit (PLAYER_ATTACK, so armour counts as for a sword), flagged so it isn't billed as a melee
	 * hit. True when the hit landed (the whip's rule, WhipMoves.blowLanded): its damage event ran and nothing cancelled it, or the
	 * health really fell, or vanilla's invulnerability frames swallowed it (no event at all, the creature was hurt a moment ago)
	 * while the creature is still fair game now (ArmTouch). With no damage to deal (the setting is 0) nothing is struck, and ArmTouch decides.
	 */
	private boolean hurt(ArmContext c, Player p, LivingEntity t, double damage) {
		// the claim, PvP and team rules are asked again at the strike (the creature may have run into a claim while the lunge ran, and
		// vanilla's invulnerability frames can swallow the blow's event, so the event alone may never have been asked): the same
		// tests as the whip's. With no damage to deal (the setting is 0) they alone decide it.
		if (!(damage > 0)) return ArmTouch.mayAffect(c, p, t);
		double[] took = {0};
		boolean hadFrames = t.getNoDamageTicks() > 0;
		blowOwner = p;
		blowTarget = t;
		blowSeen = false;
		blowCancelled = false;
		try {
			c.asSpecialDamage(p, () -> TendrilKills.during(c.plugin(), p, t, () -> took[0] = DrainHit.strike(p, t, damage, DamageType.PLAYER_ATTACK)));
		} finally {
			blowOwner = null;
			blowTarget = null;
		}
		// the test is only worth its event when nothing else decided it (a swallowed hit has no event to ask)
		boolean allowed = !(took[0] > 0) && !blowSeen && hadFrames && ArmTouch.mayAffect(c, p, t);
		return WhipMoves.blowLanded(took[0], blowSeen, blowCancelled, hadFrames, allowed);
	}

	// ------------------------------------------------------------------ the world, for the lunge

	/** The ground as the lunge sees it: the player's own body (as it is now) tested against the world's collisions. */
	private static final class WorldSpace implements ImpaleMath.Space {
		/** The body's box is shrunk this much on every side, so touching a wall isn't being inside it; the support slice under the feet is this thick (under ImpaleMath's 1/16 drop step, so a surface is found at the step it is on). */
		private static final double SKIN = 0.01, SLICE = 0.03;
		private final World world;
		private final double half, height;

		WorldSpace(World world, BoundingBox body) {
			this.world = world;
			this.half = body.getWidthX() / 2;
			this.height = body.getHeight();
		}

		private boolean loaded(double x, double z) {
			return world.isChunkLoaded((int) Math.floor((x - half) / 16), (int) Math.floor((z - half) / 16))
					&& world.isChunkLoaded((int) Math.floor((x + half) / 16), (int) Math.floor((z + half) / 16));
		}

		@Override
		public boolean fits(double x, double y, double z) {
			if (!loaded(x, z)) return false;
			return !world.hasCollisionsIn(new BoundingBox(x - half + SKIN, y + SKIN, z - half + SKIN, x + half - SKIN, y + height - SKIN, z + half - SKIN));
		}

		@Override
		public boolean supported(double x, double y, double z) {
			if (!loaded(x, z)) return false;
			return world.hasCollisionsIn(new BoundingBox(x - half + SKIN, y - SLICE, z - half + SKIN, x + half - SKIN, y, z + half - SKIN));
		}
	}

	// ------------------------------------------------------------------ what the clients and the others see

	/**
	 * The server's own picture of the lunge for players whose game doesn't draw the new forms: a tendril from the arm to the
	 * creature and a puff there. The new clients are sent FX_LUNGE and draw it themselves.
	 */
	private void fallbackLine(ArmContext c, Player p, Location at, int seed) {
		Location tip = SkinEffects.armTip(p);
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendril(p, new LungeKey(p.getUniqueId()), tip, at, "stick", SkinEffects.ARM_SEGMENTS, LUNGE_FROM_PX, LUNGE_TO_PX, true, natives);
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) {
			vanilla.tendril(p, tip, at, VanillaEffects.ARM_GREY, seed, now(c), false, natives);
			vanilla.armBurst(at, natives);
		}
	}

	/** Grey spikes standing round the creature, for players whose game doesn't draw them (the new clients draw their own from FX_PIN). */
	private void fallbackSpikes(LivingEntity t) {
		Location feet = t.getLocation();
		World world = t.getWorld();
		Particle.DustOptions dust = new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.3F);
		for (Player v : world.getPlayers()) {
			if (natives.test(v) || v.getLocation().distanceSquared(feet) > 48 * 48) continue;
			for (int i = 0; i < FALLBACK_SPIKES; i++) {
				double a = i * 2 * Math.PI / FALLBACK_SPIKES;
				for (double h = 0; h <= SPIKE_HEIGHT; h += 0.35) {
					v.spawnParticle(Particle.DUST, feet.getX() + Math.cos(a) * SPIKE_RING, feet.getY() + h, feet.getZ() + Math.sin(a) * SPIKE_RING, 1, 0, 0, 0, 0, dust);
				}
			}
		}
	}

	/** A few grey motes round a held creature, for players whose game doesn't draw the spikes (the others see the spikes). */
	private void puff(LivingEntity t) {
		BoundingBox box = t.getBoundingBox();
		Location mid = box.getCenter().toLocation(t.getWorld());
		Particle.DustOptions dust = new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.1F);
		for (Player v : t.getWorld().getPlayers()) {
			if (natives.test(v) || v.getLocation().distanceSquared(mid) > 48 * 48) continue;
			v.spawnParticle(Particle.DUST, mid, 6, box.getWidthX() / 2, box.getHeight() / 3, box.getWidthZ() / 2, 0, dust);
		}
	}

	// ------------------------------------------------------------------ the module's face

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null) return;
		int now = now(c);
		if (!runs.isEmpty()) {
			// a snapshot, and one guarded step each: a strike's damage events can end other lunges (a death, a retract) or throw, and
			// neither may strand a run, skip the others or skip the pins below (review 2026-11-16)
			stepping.clear();
			stepping.addAll(runs.values());
			for (int i = 0; i < stepping.size(); i++) {
				Run run = stepping.get(i);
				if (runs.get(run.owner) != run) continue; // ended since the snapshot
				try {
					step(c, run, now);
				} catch (RuntimeException ex) {
					runs.remove(run.owner, run);
					c.plugin().getLogger().log(Level.WARNING, "Impale failed for " + run.owner, ex);
				}
			}
			stepping.clear();
		}
		if (pins.isEmpty()) return;
		pins.tick(c.plugin().getServer(), now, (id, held, ranCourse) -> {
			// a pin that ran its course withdraws by the clients' own clock; one cut short (died, left) is told to withdraw now
			PinFx fx = pinFx.remove(id);
			if (ranCourse || fx == null) return;
			sendRelease(c, held.owner(), fx.entityId());
		}, t -> {
			if ((now + t.getUniqueId().hashCode()) % PUFF_TICKS == 0) puff(t);
		});
	}

	/** Tells the owner's clients to sink the spikes of one pin that was cut short, by the creature's id (kept from the pin, so never a -1 that would sink them all). */
	private void sendRelease(ArmContext c, UUID ownerId, int entityId) {
		if (!(c.plugin().getServer().getPlayer(ownerId) instanceof Player owner)) return;
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_SPEAR, 1, 0, 0, new int[] {entityId}, null, FX_RELEASE),
				ArmProtocol.fxRadius(ArmProtocol.FX_SPEAR));
	}

	/** A pinned player let go outside the tick's sweep (died, left): their pin's spikes are told to sink, as ArmHold.tick does for a creature. */
	private void cutShort(Player p) {
		ArmContext c = ctx;
		PinFx fx = pinFx.remove(p.getUniqueId());
		if (c != null && fx != null && pins.get(p.getUniqueId(), now(c)) != null) sendRelease(c, fx.owner(), fx.entityId());
		pins.release(p);
	}

	@Override
	public boolean running(Player p) {
		return runs.containsKey(p.getUniqueId());
	}

	/** A retract, a reshape, leaving: the lunge stops where it is. A pin stays: the spikes are in the world, not on the arm. */
	@Override
	public void abort(Player p) {
		if (runs.remove(p.getUniqueId()) != null && p.isOnline()) damp(p);
	}

	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c != null) {
			for (UUID id : runs.keySet()) {
				if (c.plugin().getServer().getPlayer(id) instanceof Player p) damp(p);
			}
		}
		runs.clear();
		// a held creature is let go (the modifier is never saved, but a reload mustn't leave one frozen until its time is up)
		if (c != null) {
			for (Map.Entry<UUID, PinFx> e : pinFx.entrySet()) {
				if (pins.isHeld(e.getKey(), now(c))) sendRelease(c, e.getValue().owner(), e.getValue().entityId());
			}
			pins.releaseAll(c.plugin().getServer());
		}
		pinFx.clear();
	}

	@Override
	public String specialName(int level) {
		return settings.name();
	}

	@Override
	public long costMilli(int level) {
		ArmContext c = ctx;
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(COST_KEY, level(level));
	}

	@Override
	public int cooldownTicks(int level) {
		return settings.cooldownTicks(level(level));
	}

	/** "Lunges 4 blocks · pins 2 s · 5 damage" for the upgrade tree, after the manager's "Diamond strength · ". */
	@Override
	public Lines describeLines(int level) {
		return settings.lines(level(level));
	}

	// ------------------------------------------------------------------ the module's own events

	private final class Events implements Listener {
		/** The blow the skewer is striking now: did its damage event run, and did anything cancel it (a claim, a PvP rule). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onBlow(EntityDamageByEntityEvent event) {
			if (blowOwner == null || event.getDamager() != blowOwner || event.getEntity() != blowTarget) return;
			blowSeen = true;
			blowCancelled = event.isCancelled();
		}

		/** A pinned creature doesn't teleport out of the spikes (an enderman's blink, a shulker's hop). */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onTeleport(EntityTeleportEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof LivingEntity t) || t instanceof Player || !pins.isHeld(t.getUniqueId(), now(c))) return;
			event.setCancelled(true);
		}

		/** Nor does a pinned player, by ender pearl or chorus fruit. */
		@SuppressWarnings("removal") // chorus fruit's cause is marked for removal but still what the server reports
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onPlayerTeleport(PlayerTeleportEvent event) {
			ArmContext c = ctx;
			PlayerTeleportEvent.TeleportCause cause = event.getCause();
			if (c != null && (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)
					&& pins.isHeld(event.getPlayer().getUniqueId(), now(c))) {
				event.setCancelled(true);
			}
		}

		/** A pinned player who dies is free (the respawned body has none of the spikes' modifiers). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onDeath(PlayerDeathEvent event) {
			cutShort(event.getEntity());
			runs.remove(event.getEntity().getUniqueId());
		}

		/** A player who leaves: their lunge is dropped, and a pin on them let go. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			Player p = event.getPlayer();
			runs.remove(p.getUniqueId());
			cutShort(p);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** A level in 1..3 (0 reads as iron: a formed arm always has one). */
	private static int level(int level) {
		return Math.clamp(level, 1, 3);
	}

	/** The server's own tick count, as the other specials time against. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	private static boolean alive(LivingEntity e) {
		return e.isValid() && !e.isDead();
	}

	/** The middle of a creature's body. */
	private static Location bodyOf(LivingEntity t) {
		return t.getBoundingBox().getCenter().toLocation(t.getWorld());
	}

	/** A point's block as S_ARM_FX packs positions (BlockPos.asLong, the protocol's; never Paper's Block.getBlockKey, which packs differently). */
	private static long block(double x, double y, double z) {
		return new Pos((int) Math.floor(x), (int) Math.floor(y + 1e-4), (int) Math.floor(z)).asLong();
	}

	private static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}
}
