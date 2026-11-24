package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArcTargets;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.BleedLedger;
import dev.morphreborn.paper.arm.rules.ClawMath;
import dev.morphreborn.paper.arm.rules.ClawSettings;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilKills;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.MainHand;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Rake, the claws' special (F; my design, 2026-11-16): a three-slash claw sweep in a 100-degree fan in front of the
 * player that makes what it hits Bleed. The claws' base is the shears, so the arm item itself (fast on leaves, wool and cobweb,
 * shearing sheep) is ArmItems' work; this class is only F and the bleed.
 * <p>
 * In ticks from F (ClawMath):
 * <ul>
 * <li>0: the creatures in the fan are found before anything is paid (ArcTargets: reach to the nearest point of each one's box, the
 * fan and a band about the eyes, only what the player can see), so a Rake at nothing only swings. Then the cost and cooldown,
 * the swing, and S_ARM_FX FX_CLAWS RAKE for every screen that draws it, a particle fan for the rest.</li>
 * <li>1, 4, 7 (one slash every 3 ticks): the slash lands. Who is in the fan is worked out again from the player's look then, so
 * turning mid-Rake rakes what you now face; each creature in it takes the slash's damage (a tendril's hit, PLAYER_ATTACK: armour
 * counts, the PvP rules and protection plugins decide) and, if the hit landed, starts to Bleed. The later slashes of one Rake
 * clear the creature's hurt window from the slash before and keep it where it stands, so the three hits all land and the
 * creature is raked rather than thrown.</li>
 * </ul>
 * Bleed is plugin-managed, a count of pulses (BleedLedger): one every {@code bleed-interval} seconds, {@code bleed-seconds} worth,
 * each taking {@code bleed-damage} as generic damage from the player (armour doesn't stop it; players are never taken below
 * arms.player-floor by it). Three slashes of one Rake are one bleed: a new hit tops the pulses up, never adds. It goes on after the
 * arm is put away (a wound isn't the arm's), and stops when the creature dies or is gone (unloaded, another world), the player
 * who made it dies or quits or is in another world, a pulse is cancelled by a protection or PvP rule, or the module is reset.
 * A pulse that fell inside the creature's hurt window from something else is tried again next tick.
 * <p>
 * The clients are sent S_ARM_FX kind FX_CLAWS five ways, by {@code arg & 0xF} (ClawMath.MOVE_*); their fields are documented on
 * {@link #fireFx}, {@link #slashFx}, {@link #bleedFx}, {@link #pulseFx} and {@link #endFx}.
 * <p>
 * Every step of a sweep checks that the arm is still formed and in hand (ArmContext.stillArmed) in the world it fired in; if
 * not, the sweep stops where it is: no refund, no further cost. Every hit is flagged as the special's (not billed as a melee hit,
 * never fed to the frenzy) and counts as a tendril kill (taken in wherever the creature is, TendrilKills).
 */
public final class ClawRake implements ArmSpecialModule {
	public static final String FORM = "claws";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "claws";
	/** The search box reaches this much past the reach: a big creature's box can be in reach while its middle isn't. */
	private static final double SEARCH_SLACK = 2;
	/** The particle fan's radius, as a share of the reach (it reads as the claws' path, not the reach's edge). */
	private static final double FAN_RADIUS_SHARE = 0.5;
	/** The wound a drop of blood comes from: this far up the creature. */
	private static final double WOUND_HEIGHT = 0.6;
	/** Viewers further than this from a hit or a pulse don't get its blood particles. */
	private static final double BLOOD_RANGE_SQ = 48 * 48;
	/** The blood's colour: dark, not the arm's bright red, so it reads as a wound. */
	private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(140, 8, 16), 0.9F);

	private @Nullable ArmContext ctx;
	/** Viewers whose game draws the new forms' effects (FX_CLAWS reaches only them): every particle fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private ClawSettings settings = ClawSettings.DEFAULTS;
	private final Events events = new Events();
	/** Each player's sweep under way. */
	private final Map<UUID, Run> runs = new HashMap<>();
	/** Who bleeds, and the one whose claws did it: a creature bleeding from two players' claws bleeds for each. */
	private final BleedLedger<Key> ledger = new BleedLedger<>();
	private final Map<Key, Wound> wounds = new HashMap<>();

	/** A bleed's key: the owner of the claws and the creature that bleeds. */
	private record Key(UUID owner, UUID target) {}

	/** What a bleed takes at each pulse, and the level of the hit that made it. */
	private record Wound(int level, double damage) {}

	/** One sweep under way. */
	private static final class Run {
		final Player player;
		final long token;
		final World world;
		final int start, level, seed;
		final int slashes;
		int done;
		/** The last slash that landed on each creature (by entity id): a creature hit by the slash before is still in its hurt window. */
		final Map<Integer, Integer> lastLanded = new HashMap<>();
		// for the report
		int landed, bled;
		double took;

		Run(Player player, long token, int start, int level, int seed, int slashes) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.seed = seed;
			this.slashes = slashes;
		}
	}

	public ClawRake() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsNewForms;
		this.settings = ClawSettings.parse(ctx.config().form(FORM));
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = ClawSettings.parse(formSection);
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
		ClawSettings s = settings;
		Location eye = p.getEyeLocation();
		// the F gate asks the world's rules too (a PvP-off player, a teammate, a claim's animal): a Rake that could land on nobody is
		// no target, unpaid (the slashes themselves test each hit again as it lands)
		List<ArcTargets.Hit> hits = reached(c, p, eye, lvl, new HashMap<>(), true);
		if (hits.isEmpty()) {
			p.swingMainHand(); // a swing at nothing: no cost, no cooldown, no message
			return new SpecialResult(SpecialResult.Outcome.NO_TARGET, null);
		}
		long cost = costMilli(lvl);
		int cooldown = cooldownTicks(lvl);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c);
		Run old = runs.get(p.getUniqueId());
		if (old != null) runs.remove(p.getUniqueId(), old); // the gate refuses while one runs, so only ever a leftover
		Run run = new Run(p, c.token(p), now, lvl, ThreadLocalRandom.current().nextInt(1 << 30), s.slashes());
		runs.put(p.getUniqueId(), run);

		p.swingMainHand();
		p.resetCooldown();
		int[] ids = new int[hits.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = hits.get(i).id();
		fireFx(c, p, run, ids);
		if (c.config().report()) {
			c.report(s.name() + ": " + p.getName() + " at level " + lvl + " raked " + hits.size() + (hits.size() == 1 ? " creature" : " creatures") + " (" + s.slashes()
					+ " slashes, reach " + number(s.reach().at(lvl)) + "); cost " + cost + " milli, cooldown " + cooldown + " ticks");
		}
		return SpecialResult.FIRED;
	}

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || (runs.isEmpty() && ledger.isEmpty())) return;
		int now = now(c);
		for (Run run : List.copyOf(runs.values())) {
			if (!live(c, run)) {
				runs.remove(run.player.getUniqueId(), run);
				if (c.config().report()) c.report(settings.name() + ": " + run.player.getName() + " stopped after " + run.done + " of " + run.slashes + " slashes");
				continue;
			}
			// the slashes due (a late tick never skips one)
			// one that throws ends only its own sweep, never the other players' or the bleeds
			try {
				while (run.done < run.slashes && now >= run.start + ClawMath.hitTick(run.done)) {
					run.done++; // counted first, so a throwing slash is never run again
					slash(c, run, run.done - 1, now);
					if (!live(c, run)) break; // a kill can set off handling that retracts the arm
				}
			} catch (RuntimeException ex) {
				c.plugin().getLogger().log(Level.WARNING, settings.name() + ": " + run.player.getName() + "'s sweep failed and was dropped", ex);
				runs.remove(run.player.getUniqueId(), run);
				continue;
			}
			if (run.done >= run.slashes) {
				runs.remove(run.player.getUniqueId(), run);
				if (c.config().report()) {
					c.report(settings.name() + ": " + run.player.getName() + " finished: " + run.landed + " hits, " + run.bled + " bleeds, took " + amount(run.took));
				}
			}
		}
		if (!ledger.isEmpty()) bleedTick(c, now);
	}

	@Override
	public boolean running(Player p) {
		return runs.containsKey(p.getUniqueId());
	}

	/** A retract, a reshape, leaving: the sweep stops where it is. A bleed goes on: the wound isn't the arm's. */
	@Override
	public void abort(Player p) {
		runs.remove(p.getUniqueId());
	}

	@Override
	public void abortAll() {
		runs.clear();
		ledger.clear();
		wounds.clear();
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

	/** "3 slashes · 2 damage each · bleeds 4 s" for the upgrade tree, after the manager's "Diamond strength · ". */
	@Override
	public Lines describeLines(int level) {
		return settings.lines(level(level));
	}

	// ------------------------------------------------------------------ the slashes

	/**
	 * Who the claws reach from {@code eye}, in the order the claws sweep through them (a right-handed player's first slash goes right
	 * to left). {@code found} gets each creature by entity id. Only what the player can see, and only what the arm may take; with
	 * {@code probe} (F) each creature in the fan is also tested against the world's and the protection plugins' rules (a test hit per
	 * creature), nearest first, and only the ones that pass count towards max-targets. The fan is cut out first, so nobody outside it
	 * is ever tested; a slash tests only when more are in the fan than it may cut (ClawMath.cut), and otherwise lets each hit test itself.
	 */
	private List<ArcTargets.Hit> reached(ArmContext c, Player p, Location eye, int lvl, Map<Integer, LivingEntity> found, boolean probe) {
		ClawSettings s = settings;
		double reach = s.reach().at(lvl);
		List<ArcTargets.Candidate> candidates = new ArrayList<>();
		for (LivingEntity e : p.getWorld().getNearbyLivingEntities(eye, reach + SEARCH_SLACK)) {
			if (!TendrilTargets.canTarget(c.plugin(), p, e, true, c.config().players()) || !p.hasLineOfSight(e)) continue;
			// no boss, as no other form takes one (decided 2026-11-16): the Ender Dragon, the Wither, the Warden, the Elder Guardian
			if (ArmHold.neverHeld(p, e)) continue;
			found.put(e.getEntityId(), e);
			candidates.add(candidate(e));
		}
		List<ArcTargets.Hit> fan = ArcTargets.select(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), reach, s.arcDeg(), s.band(), Integer.MAX_VALUE,
				p.getMainHand() == MainHand.LEFT, candidates);
		return ClawMath.cut(fan, s.maxTargets().intAt(lvl), probe, id -> {
			LivingEntity e = found.get(id);
			return e != null && ArmTouch.mayAffect(c, p, e);
		});
	}

	/** One slash lands: each creature in the fan is struck, and one that was hit starts to bleed. */
	private void slash(ArmContext c, Run run, int index, int now) {
		Player p = run.player;
		ClawSettings s = settings;
		Map<Integer, LivingEntity> found = new HashMap<>();
		List<ArcTargets.Hit> hits = reached(c, p, p.getEyeLocation(), run.level, found, false);
		int[] landed = new int[hits.size()];
		int n = 0;
		double damage = s.damage().at(run.level);
		// the claws come back the other way on an odd slash
		boolean forward = ClawMath.rightToLeft(index);
		for (int k = 0; k < hits.size(); k++) {
			LivingEntity t = found.get(hits.get(forward ? k : hits.size() - 1 - k).id());
			if (t == null || !alive(t) || !live(c, run)) continue;
			boolean again = run.lastLanded.getOrDefault(t.getEntityId(), -2) == index - 1;
			double before = t.getHealth() + t.getAbsorptionAmount();
			// the slash before left the creature in its hurt window, which would swallow this one; it is cleared for the claws' own follow-up only
			// (never a player's: their server-side velocity is not their real motion, and setting it sends them a stale packet)
			Vector still = again && !(t instanceof Player) ? t.getVelocity() : null;
			if (again && t.getNoDamageTicks() > 0) t.setNoDamageTicks(0);
			Blow blow = hurt(c, p, t, damage);
			if (!blow.counts()) continue;
			run.landed++; // a slash swallowed by someone else's hurt window still counts for the report
			run.took += Math.max(0, before - (alive(t) ? t.getHealth() + t.getAbsorptionAmount() : 0));
			if (still != null && blow.dealt() && alive(t)) t.setVelocity(still); // raked, not thrown
			// but only a blow that went through marks the creature, leaves the hurt window for the next slash to clear and makes it bleed:
			// the claws never take another attacker's window as their own, nor bleed a creature they did no harm to (2026-11-16)
			if (!blow.dealt()) continue;
			run.lastLanded.put(t.getEntityId(), index);
			landed[n++] = t.getEntityId();
			if (alive(t) && startBleed(c, run, t, now)) run.bled++;
			blood(t, 4);
		}
		int[] ids = java.util.Arrays.copyOf(landed, n);
		slashFx(c, p, run, index, ids);
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 1.1F + 0.15F * (index % 3));
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 0.5F, 1.5F + 0.1F * (index % 3));
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.sweepArc(p, FAN_RADIUS_SHARE * s.reach().at(run.level), s.arcDeg(), VanillaEffects.ARM_RED, natives);
		if (c.config().report()) c.report(s.name() + ": " + p.getName() + " slash " + (index + 1) + " of " + run.slashes + " reached " + hits.size() + ", hit " + n);
	}

	// ------------------------------------------------------------------ the hits

	/** The blow being struck right now, watched by the module's damage listener (one deep: a hit never starts another). */
	private @Nullable Player blowOwner;
	private @Nullable LivingEntity blowTarget;
	private boolean blowSeen, blowCancelled;

	/**
	 * A slash's damage, as a tendril's hit (PLAYER_ATTACK, so armour counts as for a sword), flagged so it isn't billed as a melee
	 * hit. {@code counts} when the hit landed (the whip's rule, WhipMoves.blowLanded): its damage event ran and nothing cancelled it (a
	 * claim, a PvP rule, a team), or the health really fell, or vanilla's hurt window swallowed it (no event at all) while the creature
	 * was fair game by the same tests as a Snare's. {@code dealt} only when the health fell or the event ran uncancelled: a swallowed
	 * slash must not start a bleed or clear the other attacker's window. With no damage to deal (the setting is 0) the tests alone
	 * decide both.
	 */
	private Blow hurt(ArmContext c, Player p, LivingEntity t, double damage) {
		if (!(damage > 0)) {
			boolean ok = ArmTouch.mayAffect(c, p, t);
			return new Blow(ok, ok);
		}
		double[] took = {0};
		boolean hadFrames = t.getNoDamageTicks() > 0;
		watch(p, t);
		try {
			c.asSpecialDamage(p, () -> TendrilKills.during(c.plugin(), p, t, () -> took[0] = DrainHit.strike(p, t, damage, DamageType.PLAYER_ATTACK)));
		} finally {
			unwatch();
		}
		// the test is only worth its event when nothing else decided it (a swallowed hit has no event to ask)
		boolean allowed = !(took[0] > 0) && !blowSeen && hadFrames && ArmTouch.mayAffect(c, p, t);
		boolean counts = WhipMoves.blowLanded(took[0], blowSeen, blowCancelled, hadFrames, allowed);
		return new Blow(counts, ClawMath.dealt(took[0], blowSeen, blowCancelled));
	}

	/** What a slash's hit came to: {@code counts} as landed by the whip's rule (the report's), {@code dealt} it really went through (damage taken, or its event ran uncancelled). */
	private record Blow(boolean counts, boolean dealt) {}

	private void watch(Player p, LivingEntity t) {
		blowOwner = p;
		blowTarget = t;
		blowSeen = false;
		blowCancelled = false;
	}

	private void unwatch() {
		blowOwner = null;
		blowTarget = null;
	}

	// ------------------------------------------------------------------ bleed

	/** The creature starts to bleed from this hit (or its bleed is topped up). True when there is a bleed to show for it. */
	private boolean startBleed(ArmContext c, Run run, LivingEntity t, int now) {
		ClawSettings s = settings;
		int pulses = s.bleedPulses(run.level);
		if (pulses <= 0) return false;
		Key key = new Key(run.player.getUniqueId(), t.getUniqueId());
		Wound wound = new Wound(run.level, s.bleedDamage().at(run.level));
		Wound old = wounds.get(key);
		if (old == null || wound.damage() >= old.damage()) wounds.put(key, wound);
		ledger.start(key, now, pulses, s.bleedIntervalTicks());
		bleedFx(c, run.player, run.level, run.seed, t, ledger.ticksLeft(key, now), s.bleedIntervalTicks());
		if (c.config().report()) {
			c.report(s.name() + ": " + run.player.getName() + " made " + t.getName() + " bleed (" + ledger.pulsesLeft(key) + " pulses of " + number(wound.damage()) + ")");
		}
		return true;
	}

	/** The pulses due now: each takes its damage, or ends its bleed when it can no longer go on. */
	private void bleedTick(ArmContext c, int now) {
		for (Key key : ledger.due(now)) {
			try {
				pulse(c, key, now);
			} catch (RuntimeException ex) {
				// a wound that throws stops only itself: every other bleed goes on
				c.plugin().getLogger().log(Level.WARNING, settings.name() + ": a bleed failed and was dropped", ex);
				ledger.stop(key);
				wounds.remove(key);
			}
		}
	}

	/** One bleed's pulse, due now. */
	private void pulse(ArmContext c, Key key, int now) {
		Wound wound = wounds.get(key);
		Player owner = c.plugin().getServer().getPlayer(key.owner());
		Entity e = c.plugin().getServer().getEntity(key.target());
		if (wound == null || owner == null || !owner.isOnline() || owner.isDead() || !(e instanceof LivingEntity t) || !alive(t) || t.getWorld() != owner.getWorld()
				|| ArmHold.neverHeld(owner, t) || !TendrilTargets.canTarget(c.plugin(), owner, t, true, c.config().players())) {
			// gone, or no longer fair game: nothing more to take (the clients time the bleed out themselves)
			stop(key, e instanceof LivingEntity gone && alive(gone) && owner != null && owner.isOnline() ? gone : null, owner);
			return;
		}
		double[] took = {0};
		boolean hadFrames = t.getNoDamageTicks() > 0;
		// the pulse is a damage call from the owner, which vanilla answers with knockback: the wound bleeds, it doesn't shove (2026-11-16).
		// Only for a pulse that took health, and never for a player: the server's velocity of a player is not their real motion, and
		// setting it sends them a packet with that stale value (a runner would stall), which is worse than the knock it would undo
		Vector still = t instanceof Player ? null : t.getVelocity();
		watch(owner, t);
		try {
			c.asSpecialDamage(owner, () -> TendrilKills.during(c.plugin(), owner, t, () -> took[0] = DrainHit.drain(owner, t, wound.damage(), c.config().playerFloor())));
		} finally {
			unwatch();
		}
		if (still != null && took[0] > 0 && alive(t)) t.setVelocity(still);
		if (blowSeen && blowCancelled) {
			// a claim, a PvP rule or a team says no: the wound closes
			if (c.config().report()) c.report(settings.name() + ": " + owner.getName() + "'s bleed on " + t.getName() + " was refused; it stops");
			stop(key, t, owner);
			return;
		}
		if (!(took[0] > 0) && !blowSeen && hadFrames) {
			// inside a hurt window from something else: try again next tick
			if (ledger.retry(key, now)) stop(key, alive(t) ? t : null, owner);
			return;
		}
		if (took[0] > 0) {
			pulseFx(c, owner, wound.level(), t);
			blood(t, 6);
		}
		if (c.config().report()) {
			c.report(settings.name() + ": " + t.getName() + " bled " + amount(took[0]) + " for " + owner.getName() + (alive(t) ? "" : " (killed)"));
		}
		if (!alive(t)) {
			wounds.remove(key);
			ledger.stop(key);
		} else if (ledger.pulsed(key, now)) {
			wounds.remove(key);
			endFx(c, owner, wound.level(), t); // the last pulse: the drips stop
		}
	}

	/** A bleed is over before its time: forget it, and tell the clients when the creature is still there to show it. */
	private void stop(Key key, @Nullable LivingEntity stillThere, @Nullable Player owner) {
		ledger.stop(key);
		Wound wound = wounds.remove(key);
		ArmContext c = ctx;
		if (c != null && stillThere != null && owner != null && wound != null) endFx(c, owner, wound.level(), stillThere);
	}

	/** Every bleed this says to stop (a creature gone, a player leaving): quietly, the clients time theirs out. */
	private void forget(Predicate<Key> drop) {
		if (ledger.isEmpty()) return;
		ledger.stopIf(drop);
		wounds.keySet().removeIf(drop);
	}

	// ------------------------------------------------------------------ the module's own events

	private final class Events implements Listener {
		/** The blow a slash or a bleed pulse is striking now: did its damage event run, and did anything cancel it. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onBlow(EntityDamageByEntityEvent event) {
			if (blowOwner == null || event.getDamager() != blowOwner || event.getEntity() != blowTarget) return;
			blowSeen = true;
			blowCancelled = event.isCancelled();
		}

		/** A creature that died bleeds no more. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onDeath(EntityDeathEvent event) {
			UUID id = event.getEntity().getUniqueId();
			forget(k -> k.target().equals(id));
		}

		/** A player who dies stops bleeding, and so do the wounds their claws made; their sweep ends. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onPlayerDeath(PlayerDeathEvent event) {
			UUID id = event.getEntity().getUniqueId();
			runs.remove(id);
			forget(k -> k.target().equals(id) || k.owner().equals(id));
		}

		/** A player who leaves: no more sweep, no bleed on them, and none of the wounds their claws made. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			UUID id = event.getPlayer().getUniqueId();
			runs.remove(id);
			forget(k -> k.target().equals(id) || k.owner().equals(id));
		}

		/** Creatures whose chunk goes out of the world are not bled (they are found again when it loads, with no wound). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onUnload(EntitiesUnloadEvent event) {
			if (ledger.isEmpty()) return;
			for (Entity e : event.getEntities()) {
				UUID id = e.getUniqueId();
				forget(k -> k.target().equals(id));
			}
		}

		/** A world going away takes the bleeds of everything in it. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onWorldUnload(WorldUnloadEvent event) {
			ArmContext c = ctx;
			if (c == null || ledger.isEmpty()) return;
			World gone = event.getWorld();
			forget(k -> !(c.plugin().getServer().getEntity(k.target()) instanceof Entity e) || e.getWorld().equals(gone));
			runs.values().removeIf(r -> r.world.equals(gone));
		}
	}

	// ------------------------------------------------------------------ what the screens are sent

	/**
	 * FX_CLAWS RAKE, sent when F is paid. level = the tier; ticks = how long the whole Rake takes (ClawMath.totalTicks); seed = for the
	 * client's randomness; ids = the creatures in the fan at that moment; positions: none; arg = ClawMath.rakeArg (move 1, bits 4-7 the
	 * slashes, 8-17 the fan in degrees, 18 left-handed, 19-23 the ticks per slash, 24-29 the reach in tenths of a block). The client
	 * draws one claw sweep per slash: slash i swings from tick GAP*i to GAP*(i+1), right to left on even i for a right-handed player,
	 * mirrored for a left-handed one.
	 */
	private void fireFx(ArmContext c, Player p, Run run, int[] ids) {
		ClawSettings s = settings;
		int arg = ClawMath.rakeArg(s.slashes(), s.arcWhole(), p.getMainHand() == MainHand.LEFT, s.reachTenths(run.level));
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_CLAWS, run.level, ClawMath.totalTicks(s.slashes()), run.seed, ids, null, arg),
				ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
	}

	/**
	 * FX_CLAWS SLASH, sent when slash i lands (tick ClawMath.hitTick(i) after F). level = the tier; ticks = 0; seed = the Rake's;
	 * ids = the creatures this slash hit (it may be none); arg = ClawMath.slashArg(i) (move 2, bits 4-7 the slash's index, 0 first).
	 * The client marks each creature with claw cuts for about half a second.
	 */
	private void slashFx(ArmContext c, Player p, Run run, int index, int[] ids) {
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_CLAWS, run.level, 0, run.seed, ids, null, ClawMath.slashArg(index)), ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
	}

	/**
	 * FX_CLAWS BLEED, sent each time a hit starts or tops up a creature's bleed. level = the tier; ticks = how long it will bleed
	 * from now, to its last pulse; seed; ids = [the creature]; arg = ClawMath.bleedArg (move 3, bits 4-11 the ticks between pulses).
	 * The client shows blood dripping from the creature for that long, from the latest message (it replaces an earlier one), and
	 * lets it go early on BLEED_END or when the creature is gone.
	 */
	private void bleedFx(ArmContext c, Player owner, int level, int seed, LivingEntity t, int ticks, int interval) {
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_CLAWS, level, ticks, seed, new int[] {t.getEntityId()}, null, ClawMath.bleedArg(interval)),
				ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
	}

	/** FX_CLAWS BLEED_PULSE, sent when a bleed pulse took health. level = the tier; ticks = 0; ids = [the creature]; arg = 4 (ClawMath.MOVE_BLEED_PULSE). A spurt of blood. */
	private void pulseFx(ArmContext c, Player owner, int level, LivingEntity t) {
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_CLAWS, level, 0, 0, new int[] {t.getEntityId()}, null, ClawMath.MOVE_BLEED_PULSE),
				ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
	}

	/**
	 * FX_CLAWS BLEED_END, sent when a creature's bleed is over while the creature is still there (the last pulse, or a protection rule
	 * closed the wound). level = the tier; ticks = 0; ids = [the creature]; arg = 5 (ClawMath.MOVE_BLEED_END). The drips stop. Not sent for
	 * a creature that died or left (the client drops it with the entity) nor when the player who made it left or died (the clients' own
	 * timer, from BLEED's ticks, ends it).
	 */
	private void endFx(ArmContext c, Player owner, int level, LivingEntity t) {
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_CLAWS, level, 0, 0, new int[] {t.getEntityId()}, null, ClawMath.MOVE_BLEED_END),
				ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
	}

	/** Dark red drops at a creature's wound, for players whose game doesn't draw the claws' effects (the new clients draw their own). */
	private void blood(LivingEntity t, int count) {
		Location at = t.getLocation().add(0, t.getHeight() * WOUND_HEIGHT, 0);
		double spreadX = t.getWidth() / 3, spreadY = t.getHeight() / 5;
		for (Player v : t.getWorld().getPlayers()) {
			if (natives.test(v) || v.getLocation().distanceSquared(at) > BLOOD_RANGE_SQ) continue;
			v.spawnParticle(Particle.DUST, at, count, spreadX, spreadY, spreadX, 0, BLOOD);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** A level in 1..3 (0 reads as iron: a formed arm always has one). */
	private static int level(int level) {
		return Math.clamp(level, 1, 3);
	}

	private boolean live(ArmContext c, Run run) {
		return c.stillArmed(run.player, run.token) && run.player.getWorld() == run.world;
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	private static boolean alive(@Nullable LivingEntity e) {
		return e != null && e.isValid() && !e.isDead();
	}

	private static ArcTargets.Candidate candidate(LivingEntity e) {
		BoundingBox b = e.getBoundingBox();
		return new ArcTargets.Candidate(e.getEntityId(), b.getCenterX(), b.getCenterY(), b.getCenterZ(), b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(),
				b.getMaxY(), b.getMaxZ());
	}

	private static String number(double v) {
		return BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}

	/** Health for the report, to two decimals: the caps are checked against these lines. */
	private static String amount(double v) {
		return number(v);
	}
}
