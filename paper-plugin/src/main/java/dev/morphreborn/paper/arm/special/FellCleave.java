package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.ArmView;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArcTargets;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmConfig.Levels;
import dev.morphreborn.paper.arm.rules.FellPlanner;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Effect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The war axe arm's special, two blows on one key (F; spec 3.9), and its other half, the extra log.
 * <p>
 * <b>Fell.</b> Looking at a tree's trunk within reach (the block the player looks at is a log), the arm sends a tendril
 * up it and brings the whole tree down, top first. The plan is FellPlanner's and is made before anything is paid: the
 * logs of the trunk's family within the box, and only if natural leaves touch them (a log wall or a pile of logs is
 * refused with "not a tree"). Then the cost and cooldown, S_ARM_FX FELL with the whole plan for players whose game draws
 * arms (they draw the tendril on the same clock), a tendril or a line of particles to the log being taken for everyone
 * else. Log {@code i} breaks {@code FellPlanner.breakTick(i, per-tick)} ticks after F, a tick after it cracks, through
 * Player.breakBlock, so protection plugins and the block row's billing (0.1 a log) work as for chopping by hand; a log a
 * plugin refuses, or that changed since the plan, is skipped and costs nothing. The leaves are left to decay.
 * <p>
 * <b>Cleave.</b> With no trunk in sight (or a creature in front of it), F is one heavy blow at what the player looks at:
 * the first creature within the level's reach and in sight. Two ticks after F, with the swing, it takes a player's
 * attack for the level's damage and is knocked back; a blocking player's shield is put down for shield-ticks, as a
 * vanilla axe puts it down. The hit goes through the damage event like any player's, so PvP rules and claims decide: a
 * hit they stop pushes nothing and disables nothing.
 * <p>
 * <b>Extra logs.</b> Every log or stem the axe arm breaks, mining or Fell, has the level's chance (arms.forms.axe.extra-log-chance)
 * of giving one more of that log. Fortune does nothing for logs, so this is the axe's own roll, seeded from the block and
 * the tick (FellPlanner.extraLog).
 * <p>
 * Every step first checks that the arm is still formed and in hand in the world it fired in; if not, the special stops
 * there, its cracks are cleared, and nothing is refunded (the logs already taken stay billed).
 */
public final class FellCleave implements ArmSpecialModule {
	public static final String FORM = "axe";
	/** Their rows in survival.yml arm-cost.specials. */
	private static final String FELL_COST_KEY = "axe-fell", CLEAVE_COST_KEY = "axe-cleave";
	/** A log further than this from the eyes when its turn comes is skipped (the tree's top can be 30 blocks up). */
	private static final double MAX_BREAK_DISTANCE_SQ = 40 * 40;
	/** Players this near a log see it crack. */
	private static final double CRACK_RANGE_SQ = 64 * 64;
	private static final float CRACK_PROGRESS = 0.9F;
	/** Ticks between the particle lines to the log being taken (the dust lasts a while). */
	private static final int LINE_EVERY = 4;
	private static final double LINE_STEP = 0.4;
	/** The tendril's thickness in pixels, at the arm and at the log. */
	private static final float TENDRIL_FROM_PX = 2.0F, TENDRIL_TO_PX = 1.0F;
	/** The blow lands this many ticks after F, with the swing (the clients' own swing runs on the same clock). */
	private static final int CLEAVE_HIT_TICK = 2;
	/** The clients' length for the splay of the axe's plates, as the cleave effect's ticks. */
	private static final int CLEAVE_FX_TICKS = 6;
	/** How much further than the reach a creature may be when the blow lands. */
	private static final double HIT_SLACK = 1;
	/** How wide the cleave's aim is: a creature's box counts when the look passes this near it. */
	private static final double AIM_SIZE = 0.3;
	/** The particle arc for players without the mod: short and heavy, as the blow. */
	private static final double SWEEP_RADIUS_SHARE = 0.6, SWEEP_ARC = 70;

	private @Nullable ArmContext ctx;
	/** Viewers whose game draws arms: every particle and pack fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private Settings settings = Settings.parse(Map.of());
	private final Map<UUID, Run> runs = new HashMap<>();
	private final Events events = new Events();

	/** arms.forms.axe as this module reads it. */
	private record Settings(Levels extraLogChance, Levels cooldownTicks, String fellName, double fellReach, Levels maxLogs, int radius, int height, int perTick,
			int minLeaves, String cleaveName, Levels cleaveReach, Levels cleaveDamage, double knockback, int shieldTicks) {
		static Settings parse(Map<String, Object> s) {
			Map<String, Object> fell = map(s.get("fell")), cleave = map(s.get("cleave"));
			Levels cooldown = levels(s.get("cooldown"), new Levels(8, 7, 6));
			return new Settings(clamp01(levels(s.get("extra-log-chance"), new Levels(0.20, 0.30, 0.40))),
					new Levels(Math.round(Math.max(0, cooldown.l1()) * 20), Math.round(Math.max(0, cooldown.l2()) * 20), Math.round(Math.max(0, cooldown.l3()) * 20)),
					string(fell.get("name"), "Fell"), Math.max(0, number(fell.get("reach"), 5)), whole(levels(fell.get("max-logs"), new Levels(32, 64, 128))),
					(int) Math.max(0, Math.round(number(fell.get("radius"), 8))), (int) Math.max(0, Math.round(number(fell.get("height"), 32))),
					(int) Math.max(1, Math.round(number(fell.get("per-tick"), 8))), (int) Math.max(0, Math.round(number(fell.get("min-leaves"), 3))),
					string(cleave.get("name"), "Cleave"), nonNegative(levels(cleave.get("reach"), new Levels(3.5, 4.0, 4.5))),
					nonNegative(levels(cleave.get("damage"), new Levels(12, 14, 16))), Math.max(0, number(cleave.get("knockback"), 0.8)),
					(int) Math.max(0, Math.round(number(cleave.get("shield-ticks"), 100))));
		}
	}

	/** One log of the plan: where, what it was, when it breaks, and how far it has got (0, cracked, done). */
	private static final class Log {
		final Pos pos;
		final Block block;
		final Material material;
		final int breakTick;
		int stage;

		Log(Pos pos, Block block, int breakTick) {
			this.pos = pos;
			this.block = block;
			this.material = block.getType();
			this.breakTick = breakTick;
		}

		Location centre() {
			return new Location(block.getWorld(), pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
		}
	}

	/** One special under way: a tree coming down, or a blow on its way. */
	private abstract static class Run {
		final Player player;
		final long token;
		final World world;
		final int start, level, seed;
		boolean over;

		Run(Player player, long token, int start, int level, int seed) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.seed = seed;
		}
	}

	private static final class FellRun extends Run {
		final Log[] logs;
		final StrandKey key;
		/** The first log not done yet. */
		int next;
		int broken, skipped;

		FellRun(Player player, long token, int start, int level, int seed, Log[] logs) {
			super(player, token, start, level, seed);
			this.logs = logs;
			this.key = new StrandKey(player.getUniqueId());
		}
	}

	private static final class CleaveRun extends Run {
		final LivingEntity target;
		final double reach;
		final float yaw;

		CleaveRun(Player player, long token, int start, int level, int seed, LivingEntity target, double reach, float yaw) {
			super(player, token, start, level, seed);
			this.target = target;
			this.reach = reach;
			this.yaw = yaw;
		}
	}

	/** The tendril's key in SkinEffects: one per player. */
	private record StrandKey(UUID owner) {}

	/** What the cleave's damage event did, read by the one that sent it (the hit runs inside one call, so it is one deep). */
	private static final class Probe {
		@Nullable Player owner;
		@Nullable Entity target;
		boolean seen, cancelled;
	}

	private final Probe probe = new Probe();

	public FellCleave() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsArms;
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = Settings.parse(formSection == null ? Map.of() : formSection);
	}

	@Override
	public @Nullable Listener listener() {
		return events;
	}

	/**
	 * False: only Fell breaks blocks, and Cleave must work in worlds and modes where Fell may not, so Fell asks the world
	 * rule and the game mode itself (with the manager's own words).
	 */
	@Override
	public boolean breaksBlocks() {
		return false;
	}

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.fellName() + " isn't ready yet.");
		Location eye = p.getEyeLocation();
		Vector look = eye.getDirection();
		World world = p.getWorld();
		// the trunk looked at, if any (passable blocks like grass don't hide it), and how far away it is
		RayTraceResult ray = settings.fellReach() > 0 ? world.rayTraceBlocks(eye, look, settings.fellReach(), FluidCollisionMode.NEVER, true) : null;
		Block hit = ray == null ? null : ray.getHitBlock();
		Block trunk = hit != null && family(hit.getType()) != null ? hit : null;
		double trunkDistance = trunk == null ? Double.MAX_VALUE : ray.getHitPosition().distance(eye.toVector());
		// a creature in front of the trunk is the one the player means
		Aim aim = aim(c, p, eye, look, settings.cleaveReach().at(level));
		if (aim != null && aim.distance < trunkDistance) return cleave(c, p, level, aim.target, charge);
		if (trunk != null) return fell(c, p, level, trunk, charge);
		return SpecialResult.noTarget("Look at a tree, or at something to cleave");
	}

	/** A creature in the line of sight within reach, and how far along the look it is. */
	private record Aim(LivingEntity target, double distance) {}

	private @Nullable Aim aim(ArmContext c, Player p, Location eye, Vector look, double reach) {
		if (!(reach > 0)) return null;
		boolean players = c.config().players();
		RayTraceResult r = p.getWorld().rayTraceEntities(eye, look, reach, AIM_SIZE, e -> TendrilTargets.canTarget(c.plugin(), p, e, true, players));
		if (r == null || !(r.getHitEntity() instanceof LivingEntity target)) return null;
		double distance = r.getHitPosition().distance(eye.toVector());
		// nothing solid between (the entity ray goes through walls)
		if (p.getWorld().rayTraceBlocks(eye, look, Math.max(0, distance - 0.05), FluidCollisionMode.NEVER, true) != null || !p.hasLineOfSight(target)) return null;
		return new Aim(target, distance);
	}

	// ------------------------------------------------------------------ Fell

	private SpecialResult fell(ArmContext c, Player p, int level, Block start, SpecialCharge charge) {
		ArmConfig cfg = c.config();
		String name = settings.fellName();
		World world = start.getWorld();
		// the gate asks this of modules that break blocks; this one only does when it fells
		if (!PluginSettings.WorldRule.valueOf(cfg.blockSpecials().name()).allows(world)) return SpecialResult.refused(name + " can't break blocks in this world.");
		if (p.getGameMode() == GameMode.ADVENTURE) return SpecialResult.refused(name + " can't break blocks in adventure mode.");
		ItemStack arm = p.getInventory().getItemInMainHand();
		FellPlanner.Plan plan = FellPlanner.plan(new WorldGrid(world, arm), new Pos(start.getX(), start.getY(), start.getZ()), settings.maxLogs().intAt(level),
				settings.radius(), settings.height(), settings.minLeaves());
		if (plan.outcome() == FellPlanner.Outcome.NOT_A_TREE) return SpecialResult.refused(name + ": not a tree");
		if (!plan.ok()) return SpecialResult.refused("Your arm can't fell that");
		long cost = fellCostMilli(level);
		int cooldown = cooldownTicks(level);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c), perTick = settings.perTick();
		List<Pos> order = plan.logs();
		Log[] logs = new Log[order.size()];
		long[] positions = new long[order.size()];
		for (int i = 0; i < logs.length; i++) {
			Pos pos = order.get(i);
			logs[i] = new Log(pos, world.getBlockAt(pos.x(), pos.y(), pos.z()), FellPlanner.breakTick(i, perTick));
			positions[i] = pos.asLong();
		}
		Run old = runs.get(p.getUniqueId());
		if (old != null) end(c, old, false); // the gate refuses while one runs, so only ever a leftover
		FellRun run = new FellRun(p, c.token(p), now, level, ThreadLocalRandom.current().nextInt(1 << 30), logs);
		runs.put(p.getUniqueId(), run);

		p.swingMainHand();
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_FELL, level, logs[logs.length - 1].breakTick, run.seed, null, positions, perTick),
				ArmProtocol.fxRadius(ArmProtocol.FX_FELL));
		world.playSound(p.getLocation(), Sound.ENTITY_WARDEN_DIG, 0.7F, 0.8F);
		draw(c, run, now);
		if (cfg.report()) {
			c.report(name + ": " + p.getName() + " at level " + level + " planned " + logs.length + (logs.length == 1 ? " log" : " logs") + " of "
					+ family(start.getType()) + " (" + plan.canopy() + " canopy of " + plan.scanned() + " logs seen) from " + start.getX() + " " + start.getY() + " "
					+ start.getZ() + ", " + perTick + " a tick; cost " + cost + " milli, cooldown " + cooldown + " ticks");
		}
		return SpecialResult.FIRED;
	}

	private void stepFell(ArmContext c, FellRun run, int now) {
		int elapsed = now - run.start;
		for (int i = run.next; i < run.logs.length; i++) {
			Log l = run.logs[i];
			if (l.breakTick - FellPlanner.CRACK_LEAD > elapsed) break; // the rest come later (the plan's ticks never go down)
			if (l.stage < 1) {
				crack(run, l, CRACK_PROGRESS);
				l.stage = 1;
			}
			if (l.stage < 2 && elapsed >= l.breakTick) {
				// eight logs can break in one tick, and each one bills: the arm may have melted since the last
				if (!live(c, run)) {
					end(c, run, false);
					return;
				}
				take(c, run, l);
				l.stage = 2;
				if (run.over) return;
			}
		}
		while (run.next < run.logs.length && run.logs[run.next].stage == 2) run.next++;
		if (run.next >= run.logs.length) {
			end(c, run, true);
			return;
		}
		if (elapsed % 2 == 0) draw(c, run, now);
	}

	/** A log's turn: still what was planned, still near enough, it is broken as the player would break it. */
	private void take(ArmContext c, FellRun run, Log l) {
		Player p = run.player;
		Block b = l.block;
		Location centre = l.centre();
		boolean broke = false;
		// near enough is asked first, so a log far away (the player was moved) never loads its chunk to be looked at
		if (centre.distanceSquared(p.getEyeLocation()) <= MAX_BREAK_DISTANCE_SQ && b.getType() == l.material) {
			BlockData data = b.getBlockData();
			boolean[] done = {false};
			c.asSpecialBreak(p, () -> done[0] = p.breakBlock(b));
			broke = done[0];
			// the game shows a break (its pieces and sound) to everyone but the breaker, who is meant to have seen it
			// coming apart under their own axe; this one wasn't, so the breaker gets the same level event
			if (broke) p.playEffect(b.getLocation(), Effect.DESTROY_BLOCK_WITH_SOUND, data);
		}
		if (broke) run.broken++;
		else run.skipped++;
		crack(run, l, 0F);
	}

	/** The crack on a log, for everyone near: {@code progress} 0.9, or 0 to clear it. */
	private static void crack(FellRun run, Log l, float progress) {
		Location at = l.block.getLocation();
		int source = ArmProtocol.crackSourceId(run.player.getEntityId());
		for (Player v : run.world.getPlayers()) {
			if (v.getLocation().distanceSquared(at) <= CRACK_RANGE_SQ) v.sendBlockDamage(at, progress, source);
		}
	}

	/**
	 * The tendril to the log being taken, for players whose game doesn't draw arms: the black skin from the arm for
	 * players with the pack (their own copy below their view for the owner), a line of the skin's grey dust for the rest.
	 */
	private void draw(ArmContext c, FellRun run, int now) {
		int i = run.next;
		while (i < run.logs.length && run.logs[i].stage == 2) i++;
		if (i >= run.logs.length) return;
		Location tip = SkinEffects.armTip(run.player), log = run.logs[i].centre();
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendril(run.player, run.key, tip, log, "stick", SkinEffects.ARM_SEGMENTS, TENDRIL_FROM_PX, TENDRIL_TO_PX, true, natives);
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null && (now - run.start) % LINE_EVERY == 0) {
			vanilla.line(run.player, tip, log, new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.0F), LINE_STEP, natives);
		}
	}

	// ------------------------------------------------------------------ Cleave

	private SpecialResult cleave(ArmContext c, Player p, int level, LivingEntity target, SpecialCharge charge) {
		long cost = cleaveCostMilli(level);
		int cooldown = cooldownTicks(level);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c);
		double reach = settings.cleaveReach().at(level);
		Run old = runs.get(p.getUniqueId());
		if (old != null) end(c, old, false); // the gate refuses while one runs, so only ever a leftover
		CleaveRun run = new CleaveRun(p, c.token(p), now, level, ThreadLocalRandom.current().nextInt(1 << 30), target, reach, p.getEyeLocation().getYaw());
		runs.put(p.getUniqueId(), run);

		p.swingMainHand();
		p.resetCooldown();
		Location at = p.getLocation();
		p.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1F, 0.7F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_CLEAVE, level, CLEAVE_FX_TICKS, run.seed, new int[] {target.getEntityId()}, null, 0),
				ArmProtocol.fxRadius(ArmProtocol.FX_CLEAVE));
		// the pack has no blow of its own, so players with it see the particles too
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.sweepArc(p, SWEEP_RADIUS_SHARE * reach, SWEEP_ARC, VanillaEffects.ARM_GREY, natives);
		if (c.config().report()) {
			c.report(settings.cleaveName() + ": " + p.getName() + " at level " + level + " swung at " + target.getName() + "; cost " + cost + " milli, cooldown "
					+ cooldown + " ticks");
		}
		return SpecialResult.FIRED;
	}

	/** The blow lands: the creature is still there, in reach and sight, and takes it. */
	private void stepCleave(ArmContext c, CleaveRun run, int now) {
		if (now - run.start < CLEAVE_HIT_TICK) return;
		Player p = run.player;
		LivingEntity t = run.target;
		ArmConfig cfg = c.config();
		String name = settings.cleaveName();
		if (!alive(t) || t.getWorld() != run.world || !TendrilTargets.canTarget(c.plugin(), p, t, true, cfg.players()) || reachTo(p, t) > run.reach + HIT_SLACK
				|| !p.hasLineOfSight(t)) {
			if (cfg.report()) c.report(name + ": " + p.getName() + " missed " + (t == null ? "a creature" : t.getName()) + " (gone, out of reach or sight)");
			end(c, run, true);
			return;
		}
		double damage = settings.cleaveDamage().at(run.level);
		boolean blocking = t instanceof Player tp && tp.isBlocking();
		double[] took = {0};
		probe.owner = p;
		probe.target = t;
		probe.seen = false;
		probe.cancelled = false;
		boolean landed;
		try {
			c.asSpecialDamage(p, () -> took[0] = DrainHit.strike(p, t, damage, DamageType.PLAYER_ATTACK, cfg.playerFloor()));
		} finally {
			// the damage event ran and wasn't cancelled: the blow landed, even if a shield took all of it
			landed = probe.seen && !probe.cancelled;
			probe.owner = null;
			probe.target = null;
		}
		if (landed && alive(t)) {
			double yaw = Math.toRadians(run.yaw);
			t.knockback(settings.knockback(), Math.sin(yaw), -Math.cos(yaw));
		}
		boolean disabled = false;
		if (blocking && landed && t instanceof Player tp && tp.isOnline() && settings.shieldTicks() > 0) {
			tp.setCooldown(Material.SHIELD, settings.shieldTicks());
			tp.clearActiveItem();
			run.world.playSound(tp.getLocation(), Sound.ITEM_SHIELD_BREAK, 1F, 1F);
			disabled = true;
		}
		if (landed) {
			run.world.playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1F, 0.6F);
			VanillaEffects vanilla = c.plugin().effects();
			if (vanilla != null) vanilla.armBurst(t.getLocation().add(0, t.getHeight() * 0.6, 0), natives);
		}
		if (cfg.report()) {
			c.report(name + ": " + p.getName() + " hit " + t.getName() + " for " + amount(took[0]) + " of " + SurvivalMode.number(damage) + (alive(t) ? "" : " (killed)")
					+ (landed ? "" : " (stopped)") + (disabled ? ", shield down for " + settings.shieldTicks() + " ticks" : ""));
		}
		end(c, run, true);
	}

	// ------------------------------------------------------------------ the module's face

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || runs.isEmpty()) return;
		int now = now(c);
		for (Run run : List.copyOf(runs.values())) {
			if (run.over) continue;
			if (!live(c, run)) {
				end(c, run, false);
				continue;
			}
			if (run instanceof FellRun f) stepFell(c, f, now);
			else if (run instanceof CleaveRun cl) stepCleave(c, cl, now);
		}
	}

	@Override
	public boolean running(Player p) {
		Run run = runs.get(p.getUniqueId());
		return run != null && !run.over;
	}

	@Override
	public void abort(Player p) {
		ArmContext c = ctx;
		Run run = runs.get(p.getUniqueId());
		if (c != null && run != null) end(c, run, false);
	}

	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c == null) return;
		for (Run run : List.copyOf(runs.values())) end(c, run, false);
	}

	/** One name for the two: the manager shows it on the key's tooltip and in "ready in" and "not enough biomass" lines. */
	@Override
	public String specialName(int level) {
		return settings.fellName() + " / " + settings.cleaveName();
	}

	/** What F costs at most: Fell's, or Cleave's if it is more (both 10, 12, 14 by default). */
	@Override
	public long costMilli(int level) {
		return Math.max(fellCostMilli(level), cleaveCostMilli(level));
	}

	@Override
	public int cooldownTicks(int level) {
		return (int) Math.max(0, Math.round(settings.cooldownTicks().at(level)));
	}

	/**
	 * The level in a few words for the upgrade tree, after the manager's "Diamond strength · ": "Fell: up to 64 logs,
	 * Cleave: 14 damage" (spec 5.1). The extra-log chance is the arm's own and isn't repeated here.
	 */
	@Override
	public Lines describeLines(int level) {
		int logs = settings.maxLogs().intAt(level);
		return Lines.always(settings.fellName() + ": up to " + logs + (logs == 1 ? " log" : " logs") + ", " + settings.cleaveName() + ": "
				+ SurvivalMode.number(settings.cleaveDamage().at(level)) + " damage");
	}

	/** The special is over, or stopped ({@code finished} false): cracks left on the logs are cleared, nothing is refunded. */
	private void end(ArmContext c, Run run, boolean finished) {
		if (run.over) return;
		run.over = true;
		runs.remove(run.player.getUniqueId(), run);
		if (run instanceof FellRun f) {
			for (Log l : f.logs) {
				if (l.stage == 1) crack(f, l, 0F);
			}
			SkinEffects skin = c.plugin().skin();
			if (skin != null) skin.tendrilEnd(f.key);
			if (!finished && run.player.isOnline()) {
				// an empty FX_FELL cuts this feller's tendril short on clients that draw it: a reshape stops the felling
				// while the arm stays usable, so the client can't tell from S_ARM alone
				c.fx(run.player, new ArmFxMsg(run.player.getEntityId(), ArmProtocol.FX_FELL, run.level, 0, run.seed, null, null, settings.perTick()),
						ArmProtocol.fxRadius(ArmProtocol.FX_FELL));
			}
			if (c.config().report()) {
				c.report(settings.fellName() + ": " + run.player.getName() + (finished ? " finished" : " stopped") + " after " + (now(c) - run.start) + " ticks: broke "
						+ f.broken + ", skipped " + f.skipped + " of " + f.logs.length);
			}
		} else if (!finished && c.config().report()) {
			c.report(settings.cleaveName() + ": " + run.player.getName() + " stopped before the blow landed");
		}
	}

	// ------------------------------------------------------------------ events

	private final class Events implements Listener {
		/** The axe's extra log: a log or stem broken with the arm gives one more of itself, at the level's chance. */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onDrop(BlockDropItemEvent event) {
			ArmContext c = ctx;
			Material type = event.getBlockState().getType();
			if (c == null || family(type) == null) return;
			Player p = event.getPlayer();
			int tier = axeTier(c, p);
			if (tier == 0) return;
			double chance = settings.extraLogChance().at(tier);
			Block b = event.getBlock();
			if (!FellPlanner.extraLog(chance, b.getX(), b.getY(), b.getZ(), now(c))) return;
			for (Item item : event.getItems()) {
				ItemStack stack = item.getItemStack();
				if (stack.getType() != type || stack.getAmount() >= stack.getMaxStackSize()) continue;
				stack.setAmount(stack.getAmount() + 1);
				item.setItemStack(stack);
				if (c.config().report()) c.report("axe: " + p.getName() + " got an extra " + type.getKey().getKey() + " (chance " + SurvivalMode.number(chance) + ")");
				return;
			}
		}

		/** Watches the cleave's own blow: did its damage event run, and did anything cancel it. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onDamage(EntityDamageByEntityEvent event) {
			if (probe.owner == null || event.getDamager() != probe.owner || event.getEntity() != probe.target) return;
			probe.seen = true;
			probe.cancelled = event.isCancelled();
		}
	}

	/** The player's axe arm tier now (its level), 0 when they aren't holding a formed or forming axe arm. */
	private int axeTier(ArmContext c, Player p) {
		ArmView v = c.views().get(p.getEntityId());
		if (v == null || !FORM.equals(v.form()) || !ArmProtocol.usable(v.state())) return 0;
		return c.plugin().arms().isArm(p.getInventory().getItemInMainHand()) ? v.tier() : 0;
	}

	// ------------------------------------------------------------------ the world, for the planner

	/**
	 * The log family a block belongs to: "oak" for oak logs, oak wood and their stripped kin, "crimson" for crimson
	 * stems and hyphae; null for anything that is not a log or a stem.
	 */
	static @Nullable String family(Material m) {
		if (!Tag.LOGS.isTagged(m)) return null;
		String key = m.getKey().getKey().toLowerCase(Locale.ROOT);
		if (key.startsWith("stripped_")) key = key.substring("stripped_".length());
		for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae"}) {
			if (key.endsWith(suffix)) return key.substring(0, key.length() - suffix.length());
		}
		return key;
	}

	/** The world as FellPlanner sees it: log families, natural leaves, and whether this arm may break a log. */
	private static final class WorldGrid implements FellPlanner.TreeGrid {
		private final World world;
		private final ItemStack arm;
		private final int minY, maxY;

		WorldGrid(World world, ItemStack arm) {
			this.world = world;
			this.arm = arm;
			this.minY = world.getMinHeight();
			this.maxY = world.getMaxHeight();
		}

		private boolean loaded(int x, int y, int z) {
			return y >= minY && y < maxY && world.isChunkLoaded(x >> 4, z >> 4);
		}

		/** No family outside the world's height or in a chunk that isn't loaded (the walk never loads one). */
		@Override
		public @Nullable String group(int x, int y, int z) {
			return loaded(x, y, z) ? family(world.getType(x, y, z)) : null;
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			Block b = world.getBlockAt(x, y, z);
			return b.getType().getHardness() >= 0 && b.isPreferredTool(arm);
		}

		/** Leaves a tree grew (not the persistent ones a player places), or a huge fungus's wart blocks and shroomlights. */
		@Override
		public boolean canopy(int x, int y, int z) {
			if (!loaded(x, y, z)) return false;
			Material m = world.getType(x, y, z);
			if (Tag.LEAVES.isTagged(m)) return world.getBlockData(x, y, z) instanceof Leaves leaves && !leaves.isPersistent();
			return m == Material.NETHER_WART_BLOCK || m == Material.WARPED_WART_BLOCK || m == Material.SHROOMLIGHT;
		}
	}

	// ------------------------------------------------------------------ helpers

	private long fellCostMilli(int level) {
		return config().costs().special(FELL_COST_KEY, level);
	}

	private long cleaveCostMilli(int level) {
		return config().costs().special(CLEAVE_COST_KEY, level);
	}

	private ArmConfig config() {
		ArmContext c = ctx;
		return c == null ? ArmConfig.DEFAULTS : c.config();
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

	/** From the player's eyes to the nearest point of the creature's box, as the arc measures reach. */
	private static double reachTo(Player p, LivingEntity t) {
		Location eye = p.getEyeLocation();
		BoundingBox b = t.getBoundingBox();
		return ArcTargets.boxDistance(eye.getX(), eye.getY(), eye.getZ(),
				new ArcTargets.Candidate(t.getEntityId(), b.getCenterX(), b.getCenterY(), b.getCenterZ(), b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()));
	}

	/** Health or a distance for the report, to two decimals. */
	private static String amount(double v) {
		return SurvivalMode.number(Math.round(v * 100) / 100.0);
	}

	// ------------------------------------------------------------------ settings helpers (plain values, as the file gave them)

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(@Nullable Object v) {
		return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}

	private static double number(@Nullable Object v, double fallback) {
		if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
		if (v instanceof String s) {
			try {
				double d = Double.parseDouble(s.trim());
				return Double.isFinite(d) ? d : fallback;
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		return fallback;
	}

	private static String string(@Nullable Object v, String fallback) {
		return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
	}

	/** Three levels from a list (fewer entries repeat the last; a bad entry takes that level's default) or one number for all three. */
	private static Levels levels(@Nullable Object v, Levels fallback) {
		if (v instanceof Number || v instanceof String) {
			double d = number(v, Double.NaN);
			return Double.isNaN(d) ? fallback : new Levels(d, d, d);
		}
		if (!(v instanceof List<?> list) || list.isEmpty()) return fallback;
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) out[i] = number(list.get(Math.min(i, list.size() - 1)), fallback.at(i + 1));
		return new Levels(out[0], out[1], out[2]);
	}

	private static Levels nonNegative(Levels l) {
		return new Levels(Math.max(0, l.l1()), Math.max(0, l.l2()), Math.max(0, l.l3()));
	}

	private static Levels whole(Levels l) {
		return new Levels(Math.max(0, Math.round(l.l1())), Math.max(0, Math.round(l.l2())), Math.max(0, Math.round(l.l3())));
	}

	private static Levels clamp01(Levels l) {
		return new Levels(Math.clamp(l.l1(), 0, 1), Math.clamp(l.l2(), 0, 1), Math.clamp(l.l3(), 0, 1));
	}
}
