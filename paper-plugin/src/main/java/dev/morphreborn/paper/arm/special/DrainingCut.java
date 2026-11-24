package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArcTargets;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.HealLedger;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilKills;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.MainHand;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Draining Cut, the katana arm's special (F; spec 3.7): one wide sweep of the blade cuts every creature in front of the
 * player at once, and each one it hurt is held by a red tendril from its wound to the blade, which carries part of what
 * the cut took back into the player as health. From level 2 the tendril stays latched and drains the creature again
 * (once at level 2, twice at 3), half a second apart, each drain healing in turn: a small Devouring Frenzy of the
 * blade's own, aimed rather than charged.
 * <p>
 * In ticks from F:
 * <ul>
 * <li>0: the creatures in the arc (ArcTargets: reach to the nearest point of each one's box, the arc and a band about
 * the eyes, only what the player can see) are found before anything is paid, so a swing at nothing only swings. Then
 * the cost and cooldown, the swing, and the swipe for every screen: S_ARM_FX SWIPE for players whose game draws arms,
 * a particle arc for the rest.</li>
 * <li>1 to 3: the blade reaches each creature in turn, from the start of the sweep to its end (right to left for a
 * right-handed player), and cuts it (DrainHit.strike, a player's attack: armour counts, PvP rules decide). A cut that
 * took health latches a tendril: S_ARM_FX PULSE (a swell that runs creature to blade in the tendril's travel time) and
 * DRAIN (the creatures linked now).</li>
 * <li>When the swell arrives (4 to 10 ticks later, the further the slower) the player heals: what the cut took times
 * heal-ratio times the creature's rate (TendrilTargets.healRate), at most heal-per-pulse, within the caps per creature
 * and per special (HealLedger).</li>
 * <li>Every 10 ticks after that, each latch pulse drains again (generic damage, players never below player-floor) and
 * sends another swell, while the creature is alive, in reach and was in sight within the last 10 ticks; otherwise the
 * tendril lets go.</li>
 * <li>The special ends when every tendril has let go: DRAIN with nothing linked.</li>
 * </ul>
 * Every step first checks that the arm is still formed and in hand (ArmContext.stillArmed) in the world it fired in;
 * if not, the special stops where it is: no refund, no further cost, nothing more heals. Every hit is flagged as the
 * special's (not billed as a melee hit, never fed to the frenzy) and counts as a tendril kill (taken in wherever the
 * creature is, TendrilKills).
 */
public final class DrainingCut implements ArmSpecialModule {
	public static final String FORM = "katana";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "katana";
	/** Ticks between latch pulses: a creature ignores a second hit sooner (its hurt cooldown), as for the frenzy's drain. */
	private static final int LATCH_INTERVAL = 10;
	/** A latch pulse needs the creature to have been in sight within this many ticks. */
	private static final int SIGHT_MEMORY = 10;
	/** How much further than the reach a creature may be when the blade gets to it, and when a latch pulses. */
	private static final double HIT_SLACK = 1, LATCH_SLACK = 3;
	/** The search box reaches this much past the reach: a big creature's box can be in reach while its middle isn't. */
	private static final double SEARCH_SLACK = 2;
	/** The cut's own push, the way the player looks, as a sword's (the hurt's own knockback, away from the player, comes on top). */
	private static final double KNOCKBACK = 0.4;
	/** The swipe's length for the clients (it sweeps over ticks 2 to 6). */
	private static final int SWIPE_TICKS = 6;
	/** A swell's travel from creature to blade: 3 ticks plus 1.2 a block, within these. */
	private static final int MIN_TRAVEL = 4, MAX_TRAVEL = 10;
	/** The particle arc's radius, as a share of the reach (it reads as the blade's path, not the reach's edge). */
	private static final double SWEEP_RADIUS_SHARE = 0.45;
	/** The wound a tendril grows from: this far up the creature. */
	private static final double WOUND_HEIGHT = 0.6;
	/** The healing tendril's thickness in pixels, at the creature and at the blade. */
	private static final float TENDRIL_FROM_PX = 2.2F, TENDRIL_TO_PX = 1.0F;

	private @Nullable ArmContext ctx;
	/** Viewers whose game draws arms: every particle and pack fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private ArmConfig.Katana katana = ArmConfig.DEFAULTS.katana();
	/** Each player's special under way. */
	private final Map<UUID, Run> runs = new HashMap<>();

	/** A tendril's key in SkinEffects: one per player and creature. */
	private record StrandKey(UUID owner, int target) {}

	/** A creature the sweep will reach, and the server tick it does. */
	private static final class Cut {
		final LivingEntity target;
		final int at;
		boolean done;

		Cut(LivingEntity target, int at) {
			this.target = target;
			this.at = at;
		}
	}

	/** A swell on its way to the blade: the tick it arrives and the health the hit behind it took. */
	private record Arrival(int at, double took) {}

	/** A latched tendril: the creature, its swells under way, and the latch pulses it has left. */
	private static final class Link {
		final LivingEntity target;
		final int id;
		/** How much of what it gives heals (TendrilTargets.healRate), fixed when it latched. */
		final double rate;
		final int travel;
		final StrandKey key;
		final List<Arrival> arrivals = new ArrayList<>();
		int latchesLeft, nextLatch, lastSeen;
		boolean broken;

		Link(LivingEntity target, double rate, int travel, StrandKey key) {
			this.target = target;
			this.id = target.getEntityId();
			this.rate = rate;
			this.travel = travel;
			this.key = key;
		}
	}

	/** One special under way. */
	private static final class Run {
		final Player player;
		final long token;
		final World world;
		final int start, level, seed;
		final float yaw;
		final double reach;
		final HealLedger heals;
		final List<Cut> cuts = new ArrayList<>();
		/** Linked creatures by entity id, in the order they latched (DRAIN's order). */
		final Map<Integer, Link> links = new LinkedHashMap<>();
		boolean over;
		/** The clients were last told some creature is linked (so the end must tell them none is). */
		boolean drainShown;
		boolean drainDirty;
		int drainSentAt;
		// for the report
		int cutsLanded;
		double took, healed;

		Run(Player player, long token, int start, int level, int seed, float yaw, double reach, HealLedger heals) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.seed = seed;
			this.yaw = yaw;
			this.reach = reach;
			this.heals = heals;
		}
	}

	public DrainingCut() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsArms;
		this.katana = ctx.config().katana();
	}

	/** The section is read with ArmConfig's own rules, so the module sees exactly what the manager's config does. */
	@Override
	public void load(Map<String, Object> formSection) {
		Map<String, Object> section = formSection == null ? Map.of() : formSection;
		katana = ArmConfig.parse(Map.<String, Object>of("forms", Map.<String, Object>of(FORM, section)), null, null).katana();
	}

	@Override
	public boolean breaksBlocks() {
		return false;
	}

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(katana.name() + " isn't ready yet.");
		ArmConfig cfg = c.config();
		Location eye = p.getEyeLocation();
		double reach = katana.reach().at(level), arc = katana.arc().at(level);
		Map<Integer, LivingEntity> found = new HashMap<>();
		List<ArcTargets.Candidate> candidates = new ArrayList<>();
		for (LivingEntity e : p.getWorld().getNearbyLivingEntities(eye, reach + SEARCH_SLACK)) {
			if (!TendrilTargets.canTarget(c.plugin(), p, e, true, cfg.players()) || !p.hasLineOfSight(e)) continue;
			found.put(e.getEntityId(), e);
			candidates.add(candidate(e));
		}
		List<ArcTargets.Hit> hits = ArcTargets.select(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw(), reach, arc, katana.band(), katana.maxTargets().intAt(level),
				p.getMainHand() == MainHand.LEFT, candidates);
		if (hits.isEmpty()) {
			p.swingMainHand(); // a swing at nothing: no cost, no cooldown, no message
			return new SpecialResult(SpecialResult.Outcome.NO_TARGET, null);
		}
		long cost = costMilli(level);
		int cooldown = cooldownTicks(level);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c);
		Run old = runs.get(p.getUniqueId());
		if (old != null) end(old, false); // the gate refuses while one runs, so only ever a leftover
		Run run = new Run(p, c.token(p), now, level, ThreadLocalRandom.current().nextInt(1 << 30), eye.getYaw(), reach,
				new HealLedger(katana.healPerTarget().at(level), katana.healCap().at(level)));
		int[] ids = new int[hits.size()];
		for (int i = 0; i < hits.size(); i++) {
			ArcTargets.Hit h = hits.get(i);
			ids[i] = h.id();
			run.cuts.add(new Cut(found.get(h.id()), now + ArcTargets.hitTick(h.angleNorm())));
		}
		runs.put(p.getUniqueId(), run);

		p.swingMainHand();
		p.resetCooldown();
		Location at = p.getLocation();
		p.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1F, 0.8F);
		p.getWorld().playSound(at, Sound.ITEM_TRIDENT_RETURN, 0.5F, 1.6F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_SWIPE, level, SWIPE_TICKS, run.seed, ids, null, (int) Math.round(arc)),
				ArmProtocol.fxRadius(ArmProtocol.FX_SWIPE));
		// the pack has no arc of its own, so players with it see the particles too
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.sweepArc(p, SWEEP_RADIUS_SHARE * reach, arc, VanillaEffects.ARM_GREY, natives);

		if (cfg.report()) {
			StringBuilder line = new StringBuilder(katana.name() + ": " + p.getName() + " at level " + level + " swept " + hits.size()
					+ (hits.size() == 1 ? " creature" : " creatures") + " (");
			for (int i = 0; i < hits.size(); i++) {
				LivingEntity t = found.get(hits.get(i).id());
				line.append(i == 0 ? "" : ", ").append(t == null ? "?" : t.getName()).append(" at ").append(amount(hits.get(i).dist()));
			}
			c.report(line.append("); cost ").append(cost).append(" milli, cooldown ").append(cooldown).append(" ticks").toString());
		}
		return SpecialResult.FIRED;
	}

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || runs.isEmpty()) return;
		int now = now(c);
		for (Run run : List.copyOf(runs.values())) {
			if (run.over) continue;
			if (!live(c, run)) {
				end(run, false);
				continue;
			}
			step(c, run, now);
		}
	}

	@Override
	public boolean running(Player p) {
		Run run = runs.get(p.getUniqueId());
		return run != null && !run.over;
	}

	@Override
	public void abort(Player p) {
		Run run = runs.get(p.getUniqueId());
		if (run != null) end(run, false);
	}

	@Override
	public void abortAll() {
		for (Run run : List.copyOf(runs.values())) end(run, false);
	}

	@Override
	public String specialName(int level) {
		return katana.name();
	}

	@Override
	public long costMilli(int level) {
		return config().costs().special(COST_KEY, level);
	}

	@Override
	public int cooldownTicks(int level) {
		return Math.max(0, katana.cooldownTicks().intAt(level));
	}

	/**
	 * The level in a few words for the upgrade tree, after the manager's "Diamond strength · ": "Draining Cut: up to 6
	 * creatures, heals up to 4.5 hearts" (spec 5.1). Short on purpose: the tree and the chest menu show it on one line,
	 * and what grows from level to level is how many it reaches and how much it can give back.
	 */
	@Override
	public Lines describeLines(int level) {
		int targets = katana.maxTargets().intAt(level);
		double hearts = katana.healCap().at(level) / 2;
		// all of it is the level's numbers (how many it reaches and how much it gives back), so it all stays
		return Lines.always(katana.name() + ": up to " + targets + (targets == 1 ? " creature" : " creatures") + ", heals up to " + SurvivalMode.number(hearts)
				+ (SurvivalMode.number(hearts).equals("1") ? " heart" : " hearts"));
	}

	// ------------------------------------------------------------------ the steps

	private void step(ArmContext c, Run run, int now) {
		// the blade reaches each creature in turn
		for (Cut cut : run.cuts) {
			if (cut.done || cut.at > now) continue;
			// several cuts can land in one tick, and a kill sets off the kill handling: each step checks again
			if (!live(c, run)) {
				end(run, false);
				return;
			}
			cut.done = true;
			cut(c, run, cut, now);
			if (run.over) return;
		}
		for (Link link : List.copyOf(run.links.values())) {
			// swells arriving (before a latch pulse due the same tick, which starts the next one)
			for (Iterator<Arrival> it = link.arrivals.iterator(); it.hasNext(); ) {
				Arrival a = it.next();
				if (a.at() > now) continue;
				it.remove();
				arrive(c, run, link, a);
				if (run.over) return;
			}
			if (!alive(link.target)) link.latchesLeft = 0; // a creature the cut killed holds no latch
			if (link.latchesLeft > 0 && link.nextLatch <= now) {
				if (!live(c, run)) {
					end(run, false);
					return;
				}
				latch(c, run, link, now);
				if (run.over) return;
			}
			if (link.broken || (link.latchesLeft <= 0 && link.arrivals.isEmpty())) release(c, run, link);
		}
		// the tendrils, every other tick, as the frenzy's
		if ((now - run.start) % 2 == 0) {
			for (Link link : run.links.values()) {
				see(run, link, now);
				draw(c, run, link, now);
			}
		}
		if (run.drainDirty || (run.drainShown && now - run.drainSentAt >= ArmProtocol.DRAIN_RESEND_TICKS)) sendDrain(c, run, now);
		if (run.links.isEmpty() && allCut(run)) end(run, true);
	}

	/** The blade reaches one creature: still there, still in reach and sight, it is cut, and a cut that took health latches. */
	private void cut(ArmContext c, Run run, Cut cut, int now) {
		Player p = run.player;
		LivingEntity t = cut.target;
		ArmConfig cfg = c.config();
		if (!alive(t) || t.getWorld() != run.world || !TendrilTargets.canTarget(c.plugin(), p, t, true, cfg.players()) || reachTo(p, t) > run.reach + HIT_SLACK
				|| !p.hasLineOfSight(t)) {
			if (cfg.report()) c.report(katana.name() + ": " + p.getName() + " missed " + (t == null ? "a creature" : t.getName()) + " (gone, out of reach or sight)");
			return;
		}
		double dist = reachTo(p, t);
		double damage = katana.damage().at(run.level);
		double[] took = {0};
		c.asSpecialDamage(p, () -> TendrilKills.during(c.plugin(), p, t, () -> took[0] = DrainHit.strike(p, t, damage, DamageType.PLAYER_ATTACK, cfg.playerFloor())));
		run.took += took[0];
		// pushed the way the player looks, but only when the cut landed: a hit the PvP rules stopped moves nothing
		if (took[0] > 0 && alive(t)) {
			double yaw = Math.toRadians(run.yaw);
			t.knockback(KNOCKBACK, Math.sin(yaw), -Math.cos(yaw));
		}
		if (cfg.report()) {
			c.report(katana.name() + ": " + p.getName() + " cut " + t.getName() + " for " + amount(took[0]) + " of " + SurvivalMode.number(damage)
					+ (alive(t) ? "" : " (killed)"));
		}
		if (!(took[0] > 0)) return;
		run.cutsLanded++;
		int travel = Math.clamp(Math.round(3 + 1.2 * dist), MIN_TRAVEL, MAX_TRAVEL);
		Link link = new Link(t, TendrilTargets.healRate(c.plugin(), p, t, cfg.lesserRate(), cfg.passiveRate()), travel, new StrandKey(p.getUniqueId(), t.getEntityId()));
		link.latchesLeft = alive(t) ? katana.latchPulses().intAt(run.level) : 0;
		link.arrivals.add(new Arrival(now + travel, took[0]));
		link.nextLatch = now + travel + LATCH_INTERVAL;
		link.lastSeen = now;
		run.links.put(link.id, link);
		// the link first, so the clients have the tendril when its swell starts
		sendDrain(c, run, now);
		pulse(c, run, link);
		draw(c, run, link, now);
	}

	/** A swell reaches the blade: the player heals by what it carries, within the caps, if the arm is still formed. */
	private void arrive(ArmContext c, Run run, Link link, Arrival a) {
		Player p = run.player;
		if (!c.stillArmed(p, run.token)) {
			end(run, false);
			return;
		}
		double offer = HealLedger.pulse(a.took(), katana.healRatio(), link.rate, katana.healPerPulse().at(run.level));
		double heal = run.heals.take(link.id, offer);
		double healed = 0;
		if (heal > 0 && !p.isDead()) {
			double before = p.getHealth();
			p.heal(heal, EntityRegainHealthEvent.RegainReason.CUSTOM);
			healed = Math.max(0, p.getHealth() - before);
			run.healed += healed;
			p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.4F, 0.7F);
		}
		if (c.config().report()) {
			c.report(katana.name() + ": " + p.getName() + " drew " + amount(heal) + " from " + link.target.getName() + " (took "
					+ amount(a.took()) + ", rate " + SurvivalMode.number(link.rate) + ", healed " + amount(healed) + ")");
		}
	}

	/** A latch pulse: the tendril drains its creature again, or lets go if it can't. */
	private void latch(ArmContext c, Run run, Link link, int now) {
		Player p = run.player;
		LivingEntity t = link.target;
		ArmConfig cfg = c.config();
		link.latchesLeft--;
		link.nextLatch = now + LATCH_INTERVAL;
		if (!alive(t) || t.getWorld() != run.world || !TendrilTargets.canTarget(c.plugin(), p, t, true, cfg.players()) || reachTo(p, t) > run.reach + LATCH_SLACK
				|| now - link.lastSeen > SIGHT_MEMORY) {
			link.broken = true;
			return;
		}
		double amount = katana.latchDamage().at(run.level);
		double[] took = {0};
		c.asSpecialDamage(p, () -> TendrilKills.during(c.plugin(), p, t, () -> took[0] = DrainHit.drain(p, t, amount, cfg.playerFloor())));
		run.took += took[0];
		if (cfg.report()) {
			c.report(katana.name() + ": " + p.getName() + " drained " + t.getName() + " for " + amount(took[0]) + (alive(t) ? "" : " (killed)"));
		}
		if (!(took[0] > 0)) {
			link.broken = true;
			return;
		}
		if (!alive(t)) link.latchesLeft = 0;
		link.arrivals.add(new Arrival(now + link.travel, took[0]));
		pulse(c, run, link);
	}

	/** The tendril lets go: the clients draw it snapping back into the creature (or the absorb, if it died). */
	private void release(ArmContext c, Run run, Link link) {
		if (run.links.remove(link.id) == null) return;
		run.drainDirty = true;
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendrilEnd(link.key);
	}

	/** Remember when each latched creature was last in sight, for the latch pulses. */
	private static void see(Run run, Link link, int now) {
		LivingEntity t = link.target;
		if (alive(t) && t.getWorld() == run.world && run.player.hasLineOfSight(t)) link.lastSeen = now;
	}

	/**
	 * One step of a tendril for players whose game doesn't draw arms: the red skin from the wound to the blade for
	 * players with the pack (their own copy below their view for the owner), red particles for the rest, with motes
	 * running into the blade.
	 */
	private void draw(ArmContext c, Run run, Link link, int now) {
		LivingEntity t = link.target;
		SkinEffects skin = c.plugin().skin();
		if (!alive(t) || t.getWorld() != run.world) {
			// a creature the cut killed is taken in by the absorb animation instead
			if (skin != null) skin.tendrilEnd(link.key);
			return;
		}
		Location wound = t.getLocation().add(0, t.getHeight() * WOUND_HEIGHT, 0);
		Location blade = SkinEffects.armTip(run.player);
		if (skin != null) {
			skin.tendril(run.player, link.key, wound, blade, "red_stick", SkinEffects.ARM_SEGMENTS, TENDRIL_FROM_PX, TENDRIL_TO_PX, true, natives);
		}
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.tendril(run.player, blade, wound, VanillaEffects.ARM_RED, run.seed ^ link.id, now, true, natives);
	}

	/** S_ARM_FX PULSE: a swell from the creature to the blade, arriving when its heal lands. */
	private void pulse(ArmContext c, Run run, Link link) {
		c.fx(run.player, new ArmFxMsg(run.player.getEntityId(), ArmProtocol.FX_PULSE, run.level, link.travel, run.seed, new int[] {link.id}, null, 0),
				ArmProtocol.fxRadius(ArmProtocol.FX_PULSE));
	}

	/** S_ARM_FX DRAIN: the creatures linked now; an empty list ends the clients' tendrils. */
	private void sendDrain(ArmContext c, Run run, int now) {
		int[] ids = new int[run.links.size()];
		int i = 0;
		for (int id : run.links.keySet()) ids[i++] = id;
		c.fx(run.player, new ArmFxMsg(run.player.getEntityId(), ArmProtocol.FX_DRAIN, run.level, 0, run.seed, ids, null, 0), ArmProtocol.fxRadius(ArmProtocol.FX_DRAIN));
		run.drainShown = ids.length > 0;
		run.drainDirty = false;
		run.drainSentAt = now;
	}

	/** The special is over (every tendril let go), or stopped ({@code finished} false): nothing more happens, nothing is refunded. */
	private void end(Run run, boolean finished) {
		if (run.over) return;
		run.over = true;
		runs.remove(run.player.getUniqueId(), run);
		ArmContext c = ctx;
		if (c == null) return;
		SkinEffects skin = c.plugin().skin();
		if (skin != null) for (Link link : run.links.values()) skin.tendrilEnd(link.key);
		run.links.clear();
		if (run.drainShown) sendDrain(c, run, now(c));
		if (c.config().report()) {
			c.report(katana.name() + ": " + run.player.getName() + (finished ? " finished" : " stopped") + " after " + (now(c) - run.start) + " ticks: "
					+ run.cutsLanded + " cut, took " + amount(run.took) + ", healed " + amount(run.healed) + " (booked "
					+ amount(run.heals.total()) + ")");
		}
	}

	// ------------------------------------------------------------------ helpers

	private ArmConfig config() {
		ArmContext c = ctx;
		return c == null ? ArmConfig.DEFAULTS : c.config();
	}

	private boolean live(ArmContext c, Run run) {
		return c.stillArmed(run.player, run.token) && run.player.getWorld() == run.world;
	}

	private static boolean allCut(Run run) {
		for (Cut cut : run.cuts) {
			if (!cut.done) return false;
		}
		return true;
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	private static boolean alive(@Nullable LivingEntity e) {
		return e != null && e.isValid() && !e.isDead();
	}

	/** From the player's eyes to the nearest point of the creature's box, as the arc measures reach. */
	private static double reachTo(Player p, LivingEntity t) {
		Location eye = p.getEyeLocation();
		return ArcTargets.boxDistance(eye.getX(), eye.getY(), eye.getZ(), candidate(t));
	}

	private static ArcTargets.Candidate candidate(LivingEntity e) {
		BoundingBox b = e.getBoundingBox();
		return new ArcTargets.Candidate(e.getEntityId(), b.getCenterX(), b.getCenterY(), b.getCenterZ(), b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(),
				b.getMaxY(), b.getMaxZ());
	}

	/**
	 * Health or a distance for the report, to two decimals ("0.75", "1.5", "6"): the caps are checked against these
	 * lines, and the tree's one-decimal numbers would round a 1.46 heal up to the 1.5 cap.
	 */
	private static String amount(double v) {
		return BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}
}
