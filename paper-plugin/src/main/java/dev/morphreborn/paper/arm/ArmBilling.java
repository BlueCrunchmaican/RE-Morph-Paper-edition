package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.rules.ArmDurability;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.DrainAccumulator;
import dev.morphreborn.paper.arm.rules.VeinPlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What an arm costs (spec 3.5): biomass is its durability. Every row bills whole milli-biomass through the gameplay
 * mode (ArmManager.spend: free in Classic, for creative players and with arm-cost off):
 * <ul>
 * <li>a block broken with the arm (hardness above 0, as a tool loses durability), specials' own breaks included;</li>
 * <li>a melee hit that lands (sweep victims are free, and specials' hits are billed by the special);</li>
 * <li>a strip, till, path or scrape (the guard listener cancels the item damage and calls {@link #transform});</li>
 * <li>a steady drain while an arm is formed or an accessory is on, counted exactly by a DrainAccumulator and charged every
 * second.</li>
 * </ul>
 * The item's durability bar shows the biomass, and writing it is the delicate part: any change to the held item's
 * components restarts the client's block-breaking progress (MultiPlayerGameMode.java:307-310), so the bar is written
 * only when the whole biomass changes and the player hasn't been digging for half a second (decision D33). A digging
 * flag stuck for 30 seconds (a client that never sent its abort) is let go.
 */
final class ArmBilling implements Listener {
	/** Ticks after digging stops before the bar may be written. */
	private static final int DIG_SETTLE_TICKS = 10;
	/** A digging flag older than this is taken as stuck. */
	private static final int DIG_STUCK_TICKS = 600;
	/** Ticks between drain charges. */
	private static final int DRAIN_PERIOD = 20;

	/** Whether a player is digging, since when, and when they last stopped. */
	private static final class Dig {
		boolean digging;
		int since;
		int last = Integer.MIN_VALUE / 2;
	}

	/** How long a whip's blow shows on a client that draws it as a lash. */
	private static final int STRIKE_TICKS = 5;

	private final ArmManager arms;
	private final Map<UUID, Dig> digs = new HashMap<>();
	private final Map<UUID, DrainAccumulator> drains = new HashMap<>();
	/** Scratch for {@link #tick}: who drains, and the session ids, reused every tick. */
	private final Set<UUID> draining = new HashSet<>();
	private final List<UUID> snapshot = new ArrayList<>();

	ArmBilling(ArmManager arms) {
		this.arms = arms;
	}

	// ------------------------------------------------------------------ the rows

	/** Blocks: billed once the break went through, after every protection plugin had its say. Also ends digging. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onBreak(BlockBreakEvent event) {
		Player p = event.getPlayer();
		stopDigging(p);
		if (event.isCancelled()) return;
		ArmSession s = arms.session(p);
		if (s == null || !s.usable() || !arms.isSessionArm(s, p.getInventory().getItemInMainHand())) return;
		Block block = event.getBlock();
		if (!(block.getType().getHardness() > 0)) return; // torches, crops, grass: a tool wouldn't wear on them either
		long cost = arms.config().costs().charge(arms.config().costs().perBlock());
		ArmLedger.Spend spend = arms.spend(p, cost, true);
		arms.report(p, "block " + block.getType().getKey().getKey() + ": " + charged(spend, cost) + (arms.inSpecialBreak(p) ? " (special)" : ""));
		arms.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_BREAK, s.level, 0, ThreadLocalRandom.current().nextInt(),
				new int[0], new long[] {new VeinPlanner.Pos(block.getX(), block.getY(), block.getZ()).asLong()}, 0), ArmProtocol.fxRadius(ArmProtocol.FX_BREAK));
	}

	/**
	 * Melee hits that landed. A hit's own swing pays; the creatures a sword's sweep catches beside it are free (they're
	 * the same swing). The S_ARM_FX HIT's {@code arg} says what kind of hit it was: 1 a critical hit, 2 a sweep victim,
	 * 0 a plain one (crits and sweeps add world tendrils on the client).
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHit(EntityDamageByEntityEvent event) {
		if (!(event.getDamager() instanceof Player p) || !(event.getEntity() instanceof LivingEntity victim)) return;
		boolean sweep = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
		// Bukkit has no cause of its own for a spear's jab or charge or a mace's smash (8.0.0-beta.2), so their damage types count as melee too,
		// whichever cause Paper reports for them
		boolean melee = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK || sweep
				|| ArmTiers.isWeaponDamageType(event.getDamageSource().getDamageType().getKey().toString());
		ArmSession s = arms.session(p);
		// the spear's jab and charge need nothing more: both strike with the item's own minecraft:spear damage type (LivingEntity.stabAttack
		// and Player.attack read it from the item; the arm's spear keeps it), which isWeaponDamageType already counts as melee above. The
		// old "any own blow with the spear in hand" path billed other plugins' damage too, so it is gone (review 2026-11-16).
		// The mace in hand, the smash included (no cause of its own either): the arm in hand says it is the mace's blow
		boolean ownBlow = event.getDamageSource().getDirectEntity() == p, thorns = event.getCause() == EntityDamageEvent.DamageCause.THORNS;
		if (!melee && !(s != null && ArmTiers.isMaceBlow(s.form, ownBlow, thorns))) return;
		if (s == null || !s.usable() || !arms.isSessionArm(s, p.getInventory().getItemInMainHand())) return;
		if (arms.isSpecialHit(event)) return; // the special paid for it up front
		if (!sweep) {
			long cost = arms.config().costs().charge(arms.config().costs().perHit());
			ArmLedger.Spend spend = arms.spend(p, cost, true);
			arms.report(p, "hit " + victim.getType().getKey().getKey() + ": " + charged(spend, cost));
		}
		int kind = event.isCritical() ? 1 : sweep ? 2 : 0;
		ArmFxMsg hit = new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_HIT, s.level, 0, ThreadLocalRandom.current().nextInt(), new int[] {victim.getEntityId()},
				new long[0], kind);
		if (ArmForm.WHIP.id().equals(s.form) && !sweep) {
			// the whip's left-click is a lash to what it hit (2026-11-16): clients that know the whip's moves are sent
			// that instead of the plain hit, so one blow is never drawn twice; older ones keep the plain hit
			arms.fx(p, hit, ArmProtocol.fxRadius(ArmProtocol.FX_HIT), v -> !arms.plugin().channel().drawsWhipMoves(v));
			arms.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_WHIP_MOVE, s.level, STRIKE_TICKS, ThreadLocalRandom.current().nextInt(),
					new int[] {victim.getEntityId()}, new long[0], ArmProtocol.WHIP_STRIKE), ArmProtocol.fxRadius(ArmProtocol.FX_WHIP_MOVE));
			return;
		}
		arms.fx(p, hit, ArmProtocol.fxRadius(ArmProtocol.FX_HIT));
	}

	/** G11: a strip, till, path or scrape tried to wear the arm. The guard cancels the wear; this bills it. */
	void transform(Player p, ArmSession s, int damage) {
		if (damage <= 0 || !s.usable()) return;
		long cost = arms.config().costs().charge(arms.config().costs().perTransform() * damage);
		ArmLedger.Spend spend = arms.spend(p, cost, true);
		arms.report(p, "transform x" + damage + ": " + charged(spend, cost));
	}

	// ------------------------------------------------------------------ digging (for the bar)

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDig(BlockDamageEvent event) {
		if (event.getInstaBreak()) return; // broken this tick; the break event ends it
		Dig d = digs.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new Dig());
		d.digging = true;
		d.since = arms.now();
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onDigAbort(BlockDamageAbortEvent event) {
		stopDigging(event.getPlayer());
	}

	private void stopDigging(Player p) {
		Dig d = digs.get(p.getUniqueId());
		if (d == null) {
			if (arms.session(p) == null) return; // only armed players are tracked
			d = new Dig();
			digs.put(p.getUniqueId(), d);
		}
		d.digging = false;
		d.last = arms.now();
	}

	/** Whether the arm's item may be swapped now (a re-tier): the same rule as the bar's. */
	boolean settled(UUID id, int tick) {
		return barWritable(id, tick);
	}

	/** The bar may change now: not digging, and not for the last half second (or the flag is stuck). */
	private boolean barWritable(UUID id, int tick) {
		Dig d = digs.get(id);
		if (d == null) return true;
		if (d.digging) {
			if (tick - d.since <= DIG_STUCK_TICKS) return false;
			d.digging = false; // a client that never said it stopped
			d.last = tick - DIG_SETTLE_TICKS;
		}
		return tick - d.last >= DIG_SETTLE_TICKS;
	}

	// ------------------------------------------------------------------ every tick: drain and bar

	void tick(int tick) {
		// the sets are fields, cleared and refilled: this runs every tick whether or not anyone has an arm, so it allocates nothing
		// once they have grown. The session ids are a snapshot because a charge can end a session while we walk them.
		draining.clear();
		snapshot.clear();
		arms.collectSessionIds(snapshot);
		draining.addAll(snapshot);
		draining.addAll(arms.accessoryOwners());
		for (UUID id : draining) {
			Player p = arms.plugin().getServer().getPlayer(id);
			if (p == null) continue;
			long rate = rate(p);
			DrainAccumulator acc = drains.computeIfAbsent(id, k -> new DrainAccumulator());
			acc.tick(rate);
			if (tick % DRAIN_PERIOD == 0) charge(p, acc, "drain");
		}
		for (Iterator<UUID> it = drains.keySet().iterator(); it.hasNext();) {
			if (!draining.contains(it.next())) it.remove();
		}
		for (int i = 0, n = snapshot.size(); i < n; i++) {
			UUID id = snapshot.get(i);
			ArmSession s = arms.session(id);
			Player p = arms.plugin().getServer().getPlayer(id);
			if (s != null && p != null && (s.usable() || s.tucked) && barWritable(id, tick)) writeBar(p, s); // a tucked arm's bar is only its stub's
		}
	}

	private void charge(Player p, DrainAccumulator acc, String what) {
		long due = acc.takeDue();
		if (due <= 0) return;
		ArmLedger.Spend spend = arms.spend(p, due, true);
		arms.report(p, what + ": " + charged(spend, due));
	}

	/**
	 * What drains now, milli a minute: the formed arm and every accessory that is on, each at its arm-cost rate, summed (the
	 * S_SURVIVAL trailer still carries the total). 0 when arms are free for the player (Classic, creative, arm-cost off, an
	 * admin's free arm) or they aren't in survival or adventure; an accessory an admin switched on with free adds nothing.
	 */
	long rate(Player p) {
		if (arms.free(p) || !(p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) return 0;
		var costs = arms.config().costs();
		long rate = 0;
		if (arms.session(p) != null) rate += costs.charge(costs.toolDrainPerMinute());
		return rate + arms.accessoryDrainPerMinute(p); // every tick for every player with an arm or accessory: allocation-free
	}

	/**
	 * A session is ending: bill what has come due, so retracting can't dodge the drain. The part-milli left over (under
	 * 1) is dropped once nothing drains any more.
	 */
	void flush(Player p, boolean stillDraining) {
		DrainAccumulator acc = drains.get(p.getUniqueId());
		if (acc == null) return;
		charge(p, acc, "drain flush");
		if (!stillDraining) {
			acc.reset();
			drains.remove(p.getUniqueId());
		}
	}

	/** The drain accumulator's state, for /morph arm info. */
	String drainInfo(Player p) {
		DrainAccumulator acc = drains.get(p.getUniqueId());
		return acc == null ? "none" : acc.peekDue() + " milli due, " + acc.remainder() + "/" + DrainAccumulator.TICKS_PER_MINUTE + " toward the next";
	}

	/**
	 * Rewrite the bar when its whole biomass (or the capacity) changed. Survival only: in Classic the item has no bar.
	 * Creative players on a Survival server see their biomass too, though for them it never moves.
	 */
	private void writeBar(Player p, ArmSession s) {
		if (!arms.plugin().survival().active()) return;
		PlayerInventory inv = p.getInventory();
		ItemStack item = inv.getItem(s.slot);
		long capacity = arms.capacityMilli(p);
		int max = ArmDurability.maxDamage(capacity);
		int damage = ArmDurability.damage(arms.balanceMilli(p, capacity), max);
		if (s.tucked) { // no item to rewrite: the bar others are shown on the stub follows the biomass instead
			arms.tuckedBar(p, s, max, damage);
			return;
		}
		if (!arms.isSessionArm(s, item)) return; // the audit deals with it
		if (ArmItems.maxDamageOf(item) == max && ArmItems.damageOf(item) == damage) return;
		arms.write(p, () -> inv.setItem(s.slot, ArmItems.withDurability(item, max, damage)));
	}

	void forget(UUID id) {
		digs.remove(id);
		drains.remove(id);
	}

	void clear() {
		digs.clear();
		drains.clear();
	}

	private static String charged(ArmLedger.Spend spend, long cost) {
		if (cost <= 0) return "free";
		if (spend.charged() == 0 && spend.refused() == 0) return "free (" + ArmItemSpecs.biomass(cost) + ")";
		return "-" + spend.charged() + " milli" + (spend.refused() > 0 ? " (" + spend.refused() + " short)" : "") + ", " + spend.after() + " left"
				+ (spend.depleted() ? ", depleted" : "");
	}
}
