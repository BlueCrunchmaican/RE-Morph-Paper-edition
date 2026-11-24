package dev.morphreborn.paper.arm.special;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.RecallSweep;
import dev.morphreborn.paper.arm.rules.TridentFlight;
import dev.morphreborn.paper.arm.rules.TridentSettings;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilKills;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The trident arm (my design, 2026-11-16, 8.0.0-beta.2): a real trident whose throw a tendril takes back, and Recall, its F.
 * <p>
 * <b>The throw.</b> The arm item is a real TRIDENT, so every client raises it as a trident's throw. Releasing it would make the game spend
 * the item and fly it (and a thrown arm item is the stray every guard exists to stop), so the launch is cancelled
 * (PlayerLaunchProjectileEvent, with ProjectileLaunchEvent behind it) and the module flies its own trident instead: a copy of the stack
 * that is a decoy (it carries no arm tag, ArmItems.isArm is false for it; it can't be picked up; nothing of the arm ever leaves the
 * slot). It flies at the settings' speed and hits for the tier's damage (8 / 9 / 10; the game's own trident always does 8, so the damage
 * is set on the damage event). One throw at a time: the session is {@code thrown} until the trident is back, and a right-click meanwhile
 * does nothing (PlayerInteractEvent refuses the item's use). Each throw costs {@code arm-cost.specials.trident-throw} all or nothing, each
 * hit the usual per-hit, and a hit that would kill is the player's tendril kill (TendrilKills), absorbed wherever the creature is.
 * <p>
 * <b>The tendril.</b> The trident flies until it has struck a creature, rested in a block {@code stuck-ticks}, flown {@code flight-ticks} or
 * got past the range (rules/TridentFlight.Flight). Then the tendril takes hold and reels it back, Loyalty-like but the module's own: no
 * Loyalty is on the item (so the game never puts a copy in a slot), and the module sets the trident's velocity toward the hand each tick,
 * speeding up to the tier's pull; through blocks it is moved, not stopped. When it reaches the hand it is removed and the session is free
 * to throw again. <b>It never pulls the player</b> (my rule): only the trident moves, and the player's velocity is never touched.
 * <p>
 * <b>Recall</b> (F, paid up front). The tendril snaps the trident back at once, whether it is flying, stuck or already reeling: every creature
 * along the straight line from the trident to the hand (within {@code width} of it, in the order the line meets them) takes the tier's damage as
 * a tendril's hit, the trident is removed and the throw is over. With no trident out F does nothing and costs nothing.
 * <p>
 * <b>Cleaning up.</b> The trident is removed whenever the throw ends: caught, recalled, the arm retracted or reshaped, the owner dying, quitting or
 * changing world, a screen opening (the manager's abort), the trident's chunk unloading, a reload or the plugin going down. It is never
 * persistent, so a crash leaves none behind.
 * <p>
 * The clients are sent S_ARM_FX kind FX_TRIDENT, the move in {@code arg} ({@link #FX_END} .. {@link #FX_STRIKE}); the fields are on each constant.
 * {@code thrown} is a client-side state: true from FX_THROW until FX_END, FX_CATCH or FX_RECALL. Nothing is sent again on a timer (decided
 * 2026-11-16: a resent FX_THROW restarted every client's flight and made the line pop once a second): a viewer who comes into range
 * of the trident mid-flight is sent FX_THROW (and FX_REEL when it is being reeled) once, to them alone, when their game starts tracking the
 * trident entity ({@link Events#onTrack}); a client ends a flight itself when the projectile is gone. Players whose game doesn't draw the new
 * forms get a line of dust and sounds instead.
 */
public final class TridentArm implements ArmSpecialModule {
	public static final String FORM = "trident";
	/** Its rows in survival.yml arm-cost.specials. */
	private static final String RECALL_COST = "trident-recall", THROW_COST = "trident-throw";

	/**
	 * FX_TRIDENT {@code arg}: the throw is over without a flourish (the arm retracted, the owner died, quit or left the world, the
	 * trident was lost): the client clears {@code thrown} and lets its tendril go. level = the tier; ticks = 0; ids = []; positions = [].
	 */
	public static final int FX_END = 0;
	/**
	 * FX_TRIDENT {@code arg}: the trident is out and flying (or resting in a block) and its tendril trails slack from the hand: sent when it
	 * is thrown and, once and to that viewer alone, when a viewer's game starts tracking the trident later. {@code thrown} is true. level =
	 * the tier; ticks = how long the flight has left before the tendril takes hold (0 when it is out of time); seed = one number for the
	 * whole throw (the clients' randomness); ids = [the trident's entity id]; positions = [] (for that late viewer: [the trident's block], which
	 * tells the client it is no new throw, so the owner's hand is not wound up again). A client keeps the flight it has on for the same owner
	 * and trident id, so hearing it twice changes nothing.
	 */
	public static final int FX_THROW = 1;
	/**
	 * FX_TRIDENT {@code arg}: the tendril has taken hold and is reeling the trident in: sent once when the reel begins, and once
	 * (after an FX_THROW) to a viewer whose game starts tracking the trident during it. {@code thrown} stays true. level = the tier; ticks =
	 * about how long the reel has left (to the hand where it is now; the hand moves, so it is a guide); seed = the throw's; ids = [the
	 * trident's entity id]; positions = [].
	 */
	public static final int FX_REEL = 2;
	/**
	 * FX_TRIDENT {@code arg}: Recall, once: the tendril snaps the trident to the hand in this one tick, hitting creatures along the way.
	 * {@code thrown} is false from here. level = the tier; ticks = how long the snap shows, 6; seed = the throw's; ids = [the trident's entity
	 * id, then the creatures hit, in the order the line met them]; positions = [the block the trident was in when it was snapped, BlockPos.asLong].
	 * The trident is removed in the same tick, so a client that no longer finds it draws the snap from that block.
	 */
	public static final int FX_RECALL = 3;
	/**
	 * FX_TRIDENT {@code arg}: the trident reached the hand, once: the tendril goes slack and draws in. {@code thrown} is false from here.
	 * level = the tier; ticks = 0; seed = the throw's; ids = [the trident's entity id]; positions = [].
	 */
	public static final int FX_CATCH = 4;
	/**
	 * FX_TRIDENT {@code arg}: the flying trident struck a creature (the damage went through), once; the throw goes on (the reel follows within a
	 * tick or two). level = the tier; ticks = 0; seed = the throw's; ids = [the trident's entity id, the creature]; positions = [the block the
	 * creature stands in].
	 */
	public static final int FX_STRIKE = 5;

	/** How long the Recall snap shows on a client. */
	private static final int RECALL_SHOW_TICKS = 6;
	/** Recall hits at most this many creatures. */
	private static final int MAX_RECALL_TARGETS = 16;
	/** How far above the eyes' line the trident starts (a thrown trident leaves from the shoulder of the hand that holds it). */
	private static final double START_DROP = 0.1;
	/** A thrown trident's hand: this far below the eyes, for the reel and Recall's line (the tendril's own tip is drawn by the clients). */
	private static final double HAND_DROP = 0.4;
	/** Lines of dust and the tendril for players who don't draw the form. */
	private static final double LINE_STEP = 0.4;
	private static final int DRAW_EVERY = 2, DRAW_LINE_EVERY = 4;
	private static final float TENDRIL_FROM_PX = 2.0F, TENDRIL_TO_PX = 1.0F;
	/** The "your trident is away" line is said at most this often (ticks). */
	private static final int AWAY_SAID_EVERY = 20;

	/** The arm item's tags (ArmItems): the form id, and the nonce the stray hunt keys on. A decoy has neither. */
	private static final NamespacedKey ARM_KEY = MorphRebornPlugin.key("arm");
	private static final NamespacedKey NONCE_KEY = MorphRebornPlugin.key("arm_nonce");
	/** On the flying trident entity, and on its decoy stack. */
	private static final NamespacedKey TRIDENT_KEY = MorphRebornPlugin.key("arm_trident");
	private static final NamespacedKey DECOY_KEY = MorphRebornPlugin.key("arm_decoy");

	private @Nullable ArmContext ctx;
	private TridentSettings settings = TridentSettings.DEFAULTS;
	/** Viewers whose game draws the new forms (FX_TRIDENT reaches only them); every particle and tendril fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private final Map<UUID, Throw> throwsOut = new HashMap<>();
	/** The tick each player's launch was last handled (a launch that both events report is one throw), and the tick the away line was last said. */
	private final Map<UUID, Integer> lastLaunch = new HashMap<>(), awaySaid = new HashMap<>();
	private final Listener events = new Events();
	/** Recall's or a lethal throw's own blow is being struck: the damage listeners leave it alone. */
	private boolean restriking;
	/** While restriking a lethal throw's blow: the creature, and whether its damage event came out with something taken (a totem's pop counts). */
	private @Nullable LivingEntity restrikeVictim;
	private boolean restrikeTook;
	/** The module is teleporting its own trident (the reel through a block): the teleport guard below leaves that one alone. */
	private boolean selfMove;

	/** The tether's key in SkinEffects: one per player. */
	private record StrandKey(UUID owner) {}

	private enum Phase { FLYING, REELING }

	/** One throw under way. */
	private static final class Throw {
		final Player player;
		final long token;
		final World world;
		final int start, level, seed;
		final Trident trident;
		final TridentFlight.Flight flight;
		final StrandKey key;
		Phase phase = Phase.FLYING;
		int reelStart;
		/** The trident struck something: the reel begins. */
		boolean struck;
		boolean over;

		Throw(Player player, long token, int start, int level, int seed, Trident trident, TridentFlight.Flight flight) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.seed = seed;
			this.trident = trident;
			this.flight = flight;
			this.key = new StrandKey(player.getUniqueId());
		}
	}

	public TridentArm() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsNewForms;
		this.settings = TridentSettings.parse(ctx.config().form(FORM));
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = TridentSettings.parse(formSection);
	}

	@Override
	public @Nullable Listener listener() {
		return events;
	}

	@Override
	public boolean breaksBlocks() {
		return false;
	}

	@Override
	public String specialName(int level) {
		return settings.name();
	}

	@Override
	public long costMilli(int level) {
		ArmContext c = ctx;
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(RECALL_COST, level(level));
	}

	@Override
	public int cooldownTicks(int level) {
		return settings.cooldownTicks(level(level));
	}

	@Override
	public Lines describeLines(int level) {
		return settings.lines(level(level));
	}

	/**
	 * Never "running": a throw is out for as long as the trident is away, and F (Recall) has to work exactly then. The manager refuses F while
	 * a module says it is still going.
	 */
	@Override
	public boolean running(Player p) {
		return false;
	}

	// ------------------------------------------------------------------ the throw

	/**
	 * A release of the trident's draw, taken over from the game: this player throws, if they may. Returns whether a trident is out now.
	 * {@code held} is the arm item in the main hand.
	 */
	private boolean throwFrom(ArmContext c, Player p, ItemStack held) {
		if (!FORM.equals(formOf(held)) || !c.stillArmed(p, c.token(p)) || p.getGameMode() == GameMode.SPECTATOR) return false;
		Throw out = throwsOut.get(p.getUniqueId());
		if (out != null && !out.over) {
			sayAway(c, p);
			return false;
		}
		int level = Math.max(1, c.level(p, FORM));
		World world = p.getWorld();
		Location eye = p.getEyeLocation();
		Vector dir = eye.getDirection();
		Location start = eye.clone().subtract(0, START_DROP, 0);

		// 1. the trident, built and offered to other plugins (a region plugin or an anticheat may cancel it)
		Trident trident = world.createEntity(start, Trident.class);
		trident.setShooter(p);
		ItemStack decoy = decoyOf(held);
		trident.setItemStack(decoy);
		trident.setWeapon(decoy);
		trident.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		trident.setLoyaltyLevel(0);
		trident.setPersistent(false);
		trident.setCritical(false);
		trident.setDamage(settings.throwDamage().at(level));
		trident.setVelocity(dir.clone().multiply(settings.throwSpeed()));
		trident.getPersistentDataContainer().set(TRIDENT_KEY, PersistentDataType.BYTE, (byte) 1);
		ProjectileLaunchEvent launch = new ProjectileLaunchEvent(trident);
		c.plugin().getServer().getPluginManager().callEvent(launch);
		if (launch.isCancelled()) return false;

		// 2. its price, all or nothing: no biomass, no throw (the item stays in the hand either way)
		long cost = c.config().costs().special(THROW_COST, level);
		ArmLedger.Spend paid = c.spend(p, cost, false);
		if (!paid.paid()) {
			p.sendActionBar(Component.translatable("morphreborn.arm.trident.refused", "Not enough biomass to throw the trident", NamedTextColor.RED));
			return false;
		}

		// 3. it joins the world; if another plugin refuses it nothing flew, so the price is given back (the item stays in the hand either way)
		world.addEntity(trident);
		if (!trident.isValid()) {
			if (paid.charged() > 0) c.gain(p, paid.charged());
			return false;
		}
		int now = now(c);
		Throw th = new Throw(p, c.token(p), now, level, ThreadLocalRandom.current().nextInt(1 << 30), trident, new TridentFlight.Flight(now, settings, level));
		throwsOut.put(p.getUniqueId(), th);
		p.swingMainHand();
		world.playSound(eye, Sound.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1.0F, 1.0F);
		world.playSound(eye, Sound.ENTITY_WARDEN_TENDRIL_CLICKS, SoundCategory.PLAYERS, 0.5F, 1.5F);
		sendFx(c, th, FX_THROW, th.flight.ticksLeft(now), new int[] {trident.getEntityId()}, null);
		if (c.config().report()) {
			c.report("trident: " + p.getName() + " at level " + level + " threw it, damage " + number(settings.throwDamage().at(level)) + ", cost " + cost + " milli");
		}
		return true;
	}

	/**
	 * The flying trident's item: a copy of the arm stack with the arm's tags taken off, so no guard, audit or stray hunt ever takes it for an
	 * arm, and a decoy mark on it. Nothing picks it up (the pickup is DISALLOWED, and it is removed when the throw ends).
	 */
	private static ItemStack decoyOf(ItemStack arm) {
		ItemStack decoy = arm.clone();
		decoy.setAmount(1);
		decoy.editPersistentDataContainer(pdc -> {
			pdc.remove(ARM_KEY);
			pdc.remove(NONCE_KEY);
			pdc.set(DECOY_KEY, PersistentDataType.BYTE, (byte) 1);
		});
		return decoy;
	}

	private void sayAway(ArmContext c, Player p) {
		int now = now(c);
		Integer said = awaySaid.get(p.getUniqueId());
		if (said != null && now - said < AWAY_SAID_EVERY) return;
		awaySaid.put(p.getUniqueId(), now);
		p.sendActionBar(Component.text("Your trident is away: F recalls it.", NamedTextColor.GRAY));
	}

	// ------------------------------------------------------------------ Recall

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		Throw th = throwsOut.get(p.getUniqueId());
		if (th == null || th.over || !th.trident.isValid()) {
			if (th != null && !th.over) end(c, th, true); // the trident was lost under it: the throw is over, so the arm is free again
			return SpecialResult.noTarget("Your trident is in your hand");
		}
		if (!c.stillArmed(p, th.token) || th.world != p.getWorld()) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		int lvl = level(level);
		if (!charge.pay(costMilli(lvl), cooldownTicks(lvl))) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		// the way back: a straight line from the trident to the hand, swept for creatures it may take
		Location at = th.trident.getLocation();
		Location hand = handPoint(p);
		Sweep sweep = along(c, p, th.trident, at, hand);
		List<LivingEntity> hits = sweep.hits();
		int[] ids = new int[1 + hits.size()];
		ids[0] = th.trident.getEntityId();
		double damage = settings.recallDamage().at(lvl);
		int landed = 0;
		for (LivingEntity victim : hits) {
			double took = strike(c, p, victim, damage);
			if (took > 0 || victim.isDead()) ids[1 + landed++] = victim.getEntityId();
		}
		int[] sent = landed == hits.size() ? ids : java.util.Arrays.copyOf(ids, 1 + landed);
		World world = p.getWorld();
		world.playSound(at, Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0F, 1.4F);
		if (landed > 0) world.playSound(hand, Sound.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 0.8F, 1.2F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, RECALL_SHOW_TICKS, th.seed, sent, new long[] {block(at.getX(), at.getY(), at.getZ())}, FX_RECALL),
				ArmProtocol.fxRadius(ArmProtocol.FX_TRIDENT));
		fallbackSnap(c, p, at, hand);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " at level " + lvl + " snapped the trident back from " + number(at.distance(hand)) + " blocks, hit " + landed
					+ " of " + hits.size() + " for " + number(damage) + " (" + sweep.onPath() + " on the path, " + sweep.unseen() + " out of sight, " + (hits.size() - landed)
					+ " took nothing, as under damage immunity)");
		}
		end(c, th, false); // the snap above is the end; no FX_END behind it
		return SpecialResult.FIRED;
	}

	/**
	 * What the sweep found: the creatures a tendril may strike, in the order the line meets them, and how many the line was on before the
	 * owner's sight spared some, so the Recall report can tell a miss of the sweep from one of the sight test or of damage immunity.
	 */
	private record Sweep(List<LivingEntity> hits, int onPath, int unseen) {}

	/** One creature the line is on: where it is first met, then (to break a tie) where the line comes nearest and how near. */
	private record OnPath(LivingEntity living, double t, double nearT, double nearDist) {}

	/** The creatures along the line from {@code a} to {@code b}, in the order the line meets them, that a tendril may strike. */
	private Sweep along(ArmContext c, Player p, Entity trident, Location a, Location b) {
		World world = a.getWorld();
		if (world == null) return new Sweep(List.of(), 0, 0);
		double width = settings.recallWidth();
		BoundingBox span = BoundingBox.of(a, b).expand(width + 1.0);
		double[] from = {a.getX(), a.getY(), a.getZ()}, to = {b.getX(), b.getY(), b.getZ()};
		List<OnPath> found = new ArrayList<>();
		for (Entity e : world.getNearbyEntities(span)) {
			if (!(e instanceof LivingEntity living) || e == trident || !TendrilTargets.canTarget(c.plugin(), p, living, true, c.config().players())) continue;
			// the creature's whole body, not its centre: any part of the box within the width of the path is on it (2026-11-16 rig: a creature
			// 0.8 off at width 0.8 was missed)
			BoundingBox box = living.getBoundingBox();
			double[] min = {box.getMinX(), box.getMinY(), box.getMinZ()}, max = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
			double t = RecallSweep.entry(from, to, min, max, width);
			if (t < 0) continue;
			double[] near = RecallSweep.nearest(from, to, min, max);
			found.add(new OnPath(living, t, near[0], near[1]));
		}
		// everything within the width of the trident's own spot is met at 0, so a crowd there is ordered by how near the line passes, then where it
		// comes nearest, then the entity id: the 16-target cut below follows the path and is the same every time, not the game's entity listing order
		found.sort(Comparator.comparingDouble(OnPath::t).thenComparingDouble(OnPath::nearDist).thenComparingDouble(OnPath::nearT)
				.thenComparingInt(f -> f.living().getEntityId()));
		List<LivingEntity> out = new ArrayList<>();
		Location eye = p.getEyeLocation();
		int unseen = 0;
		for (OnPath f : found) {
			if (out.size() >= MAX_RECALL_TARGETS) break;
			// the reel goes through blocks but the blow does not: a creature the owner could not see (behind a wall, in a house or a cave on the way) is spared
			if (seen(world, eye, f.living())) out.add(f.living());
			else unseen++;
		}
		return new Sweep(out, found.size(), unseen);
	}

	/** Whether the owner's eyes have a clear line to the creature: its middle or its eyes (blocks you can see through don't count). */
	private static boolean seen(World world, Location eye, LivingEntity living) {
		BoundingBox box = living.getBoundingBox();
		return clear(world, eye, box.getCenterX(), box.getCenterY(), box.getCenterZ()) || clear(world, eye, living.getEyeLocation().getX(), living.getEyeLocation().getY(),
				living.getEyeLocation().getZ());
	}

	private static boolean clear(World world, Location from, double x, double y, double z) {
		double dx = x - from.getX(), dy = y - from.getY(), dz = z - from.getZ();
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.5) return true;
		// stops a little short, so a creature pressed against a wall is not blocked by the wall it stands at
		return world.rayTraceBlocks(from, new Vector(dx / len, dy / len, dz / len), len - 0.3, FluidCollisionMode.NEVER, true) == null;
	}

	/** One tendril blow: a trident's damage as the player's hit, flagged as a special's (so it isn't billed as melee), taking a kill in wherever it is. */
	private double strike(ArmContext c, Player p, LivingEntity victim, double damage) {
		double[] took = {0};
		restriking = true;
		try {
			c.asSpecialDamage(p, () -> TendrilKills.during(c.plugin(), p, victim, () -> took[0] = DrainHit.strike(p, victim, damage, DamageType.TRIDENT)));
		} finally {
			restriking = false;
		}
		return took[0];
	}

	// ------------------------------------------------------------------ the flight and the reel

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || throwsOut.isEmpty()) return;
		int now = now(c);
		for (Throw th : List.copyOf(throwsOut.values())) {
			if (th.over) continue;
			if (!c.stillArmed(th.player, th.token) || th.player.getWorld() != th.world || !th.trident.isValid() || th.trident.getWorld() != th.world) {
				end(c, th, true); // the arm is gone, or the trident was lost (a chunk unloaded, another plugin removed it)
				continue;
			}
			if (th.phase == Phase.FLYING) fly(c, th, now);
			else reel(c, th, now);
		}
	}

	private void fly(ArmContext c, Throw th, int now) {
		Trident t = th.trident;
		double distance = t.getLocation().distance(th.player.getLocation());
		TridentFlight.Cue cue = th.flight.update(now, th.struck, t.isInBlock(), distance);
		if (cue != TridentFlight.Cue.NONE) {
			startReel(c, th, now, cue);
			return;
		}
		draw(c, th, t.getLocation(), now);
	}

	/** The tendril takes hold: the trident can no longer strike or fall, and the reel begins. */
	private void startReel(ArmContext c, Throw th, int now, TridentFlight.Cue why) {
		Trident t = th.trident;
		th.phase = Phase.REELING;
		th.reelStart = now;
		t.setHasDealtDamage(true); // the reel is no attack: Recall is what hits on the way back
		t.setGravity(false);
		Location hand = handPoint(th.player);
		sendFx(c, th, FX_REEL, reelTicksFrom(th, t.getLocation().distance(hand)), new int[] {t.getEntityId()}, null);
		th.world.playSound(t.getLocation(), Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 0.9F, 1.0F);
		if (c.config().report()) c.report("trident: " + th.player.getName() + "'s trident is reeled in (" + why.name().toLowerCase(java.util.Locale.ROOT) + ")");
	}

	/** One step of the pull: the trident goes toward the hand, faster each tick, through anything in the way, until it is there. */
	private void reel(ArmContext c, Throw th, int now) {
		Trident t = th.trident;
		Player p = th.player;
		Location at = t.getLocation();
		Location hand = handPoint(p);
		TridentFlight.Step step = TridentFlight.reel(at.getX(), at.getY(), at.getZ(), hand.getX(), hand.getY(), hand.getZ(), now - th.reelStart,
				settings.reelSpeed().at(th.level), settings.reelAccel(), settings.reelMaxTicks());
		if (step.arrived()) {
			caught(c, th, at, hand);
			return;
		}
		Vector v = new Vector(step.vx(), step.vy(), step.vz());
		// blocks don't hold it: a trident resting in one, or about to meet one, is moved the step instead of left to the game's collision
		if (t.isInBlock() || blocked(th.world, at, v)) {
			Location next = at.clone().add(v);
			next.setDirection(v);
			selfMove = true;
			try {
				t.teleport(next);
			} finally {
				selfMove = false;
			}
			// the step is taken: no velocity on top of it, or the game moves the trident a second step this tick (past the hand, hitting the
			// block again and again)
			t.setVelocity(new Vector());
		} else {
			t.setVelocity(v);
		}
		draw(c, th, at, now);
	}

	/** About how many ticks a reel of {@code distance} blocks takes at this throw's tier (what FX_REEL tells the clients). */
	private int reelTicksFrom(Throw th, double distance) {
		return TridentFlight.reelTicks(distance, settings.reelSpeed().at(th.level), settings.reelAccel(), settings.reelMaxTicks());
	}

	private static boolean blocked(World world, Location at, Vector v) {
		double length = v.length();
		if (length < 1e-6) return false;
		RayTraceResult hit = world.rayTraceBlocks(at, v, length + 0.3, FluidCollisionMode.NEVER, true);
		return hit != null;
	}

	/** The trident reached the hand: it is taken in, the tendril draws back, and the arm may throw again. */
	private void caught(ArmContext c, Throw th, Location at, Location hand) {
		Player p = th.player;
		World world = th.world;
		world.playSound(hand, Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 0.8F, 1.6F);
		world.playSound(hand, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.5F, 1.2F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, 0, th.seed, new int[] {th.trident.getEntityId()}, null, FX_CATCH),
				ArmProtocol.fxRadius(ArmProtocol.FX_TRIDENT));
		if (c.config().report()) c.report("trident: " + p.getName() + "'s trident is back after " + (now(c) - th.start) + " ticks");
		end(c, th, false);
	}

	// ------------------------------------------------------------------ ending, FX and the tether

	/**
	 * The throw is over: the trident goes, and the tendril draws back. {@code sendEnd}: tell the clients nothing else did (a catch and a
	 * Recall send their own message first). Safe to call twice.
	 */
	private void end(ArmContext c, Throw th, boolean sendEnd) {
		if (th.over) return;
		th.over = true;
		throwsOut.remove(th.player.getUniqueId(), th);
		if (th.trident.isValid()) th.trident.remove();
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendrilEnd(th.key);
		if (sendEnd && th.player.isOnline()) {
			c.fx(th.player, new ArmFxMsg(th.player.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, 0, th.seed, null, null, FX_END),
					ArmProtocol.fxRadius(ArmProtocol.FX_TRIDENT));
		}
	}

	/** Any FX_TRIDENT move that is a state or an event with a tick count, to everyone in range. */
	private void sendFx(ArmContext c, Throw th, int arg, int ticks, int @Nullable [] ids, long @Nullable [] positions) {
		c.fx(th.player, new ArmFxMsg(th.player.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, Math.max(0, ticks), th.seed, ids, positions, arg),
				ArmProtocol.fxRadius(ArmProtocol.FX_TRIDENT));
	}

	/**
	 * A viewer who comes into range mid-throw: FX_THROW (what is left of the flight, with the trident's block as its one position: it is no
	 * new throw, so the owner's hand is not wound again), then FX_REEL when the tendril has taken hold, to them alone. No distance limit: the
	 * viewer tracks the trident, which can be far from its owner, and without the throw would see the vanilla decoy for the whole flight.
	 */
	private void tellViewer(ArmContext c, Throw th, Player viewer) {
		int now = now(c);
		int[] ids = {th.trident.getEntityId()};
		Player o = th.player;
		double radius = Double.POSITIVE_INFINITY;
		java.util.function.Predicate<Player> only = v -> v == viewer;
		Location at = th.trident.getLocation();
		c.plugin().channel().broadcastArmFx(o, new ArmFxMsg(o.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, th.flight.ticksLeft(now), th.seed, ids,
				new long[] {block(at.getX(), at.getY(), at.getZ())}, FX_THROW), radius, only);
		if (th.phase == Phase.REELING) {
			int ticks = reelTicksFrom(th, th.trident.getLocation().distance(handPoint(o)));
			c.plugin().channel().broadcastArmFx(o, new ArmFxMsg(o.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, ticks, th.seed, ids, null, FX_REEL), radius, only);
		}
	}

	/**
	 * The tendril from the hand to the trident for players whose game doesn't draw the new forms: the black skin's from the arm for players
	 * with the pack, a line of the skin's grey dust for the rest.
	 */
	private void draw(ArmContext c, Throw th, Location to, int now) {
		if (now % DRAW_EVERY != 0) return;
		Location tip = SkinEffects.armTip(th.player);
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendril(th.player, th.key, tip, to, "stick", SkinEffects.ARM_SEGMENTS, TENDRIL_FROM_PX, TENDRIL_TO_PX, true, natives);
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null && (now - th.start) % DRAW_LINE_EVERY == 0) {
			vanilla.line(th.player, tip, to, new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.0F), LINE_STEP, natives);
		}
	}

	/** Recall's snap for players whose game doesn't draw the new forms: a bright line of dust from where the trident was to the hand. */
	private void fallbackSnap(ArmContext c, Player p, Location from, Location to) {
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.line(p, from, to, new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.4F), LINE_STEP * 0.5, natives);
	}

	/** Where the trident is taken to when it comes home: a little in front of and below the eyes, where the hand is. */
	private static Location handPoint(Player p) {
		Location eye = p.getEyeLocation();
		return eye.clone().add(eye.getDirection().multiply(0.6)).subtract(0, HAND_DROP, 0);
	}

	// ------------------------------------------------------------------ leaving

	@Override
	public void abort(Player p) {
		ArmContext c = ctx;
		Throw th = throwsOut.get(p.getUniqueId());
		if (c != null && th != null) end(c, th, true);
	}

	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c == null) return;
		for (Throw th : List.copyOf(throwsOut.values())) end(c, th, true);
		lastLaunch.clear();
		awaySaid.clear();
	}

	// ------------------------------------------------------------------ events

	private final class Events implements Listener {
		/**
		 * The game's own throw is cancelled and the module's flies instead (the arm item must not be spent, nor fly). Nothing is consumed
		 * either way. Other plugins that cancelled the launch first are respected.
		 */
		@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
		public void onPlayerLaunch(PlayerLaunchProjectileEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getProjectile() instanceof Trident) || event.getProjectile().getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) {
				return;
			}
			Player p = event.getPlayer();
			ItemStack held = p.getInventory().getItemInMainHand();
			if (!FORM.equals(formOf(event.getItemStack())) && !FORM.equals(formOf(held))) return;
			event.setCancelled(true);
			event.setShouldConsume(false);
			handled(c, p, held);
		}

		/** Behind it: a trident the game launched for a player with the arm in hand by a path the event above did not see. */
		@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
		public void onLaunch(ProjectileLaunchEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof Trident t) || t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			if (!(t.getShooter() instanceof Player p)) return;
			// the game spends the stack (consumeAndReturn) before it makes the projectile, so the hand may be empty already: the arm is read off the
			// projectile's own item and weapon too, and put back in the hand when the launch is cancelled (the item must never leave its slot)
			ItemStack held = p.getInventory().getItemInMainHand();
			ItemStack arm = FORM.equals(formOf(held)) ? held : FORM.equals(formOf(t.getItemStack())) ? t.getItemStack() : FORM.equals(formOf(t.getWeapon())) ? t.getWeapon() : null;
			if (arm == null) return;
			event.setCancelled(true);
			if (arm != held) {
				ItemStack back = arm.clone();
				back.setAmount(1);
				if (held.isEmpty()) p.getInventory().setItemInMainHand(back);
				held = back;
			}
			handled(c, p, held);
		}

		/** One launch is one throw, whichever of the two events came first. */
		private void handled(ArmContext c, Player p, ItemStack held) {
			int now = now(c);
			Integer last = lastLaunch.put(p.getUniqueId(), now);
			if (last != null && last == now) return;
			throwFrom(c, p, held);
		}

		/** The arm item can't be used while the trident is away: no second throw until it is back. */
		@EventHandler(priority = EventPriority.LOW)
		public void onUse(PlayerInteractEvent event) {
			ArmContext c = ctx;
			if (c == null || event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
			Player p = event.getPlayer();
			Throw th = throwsOut.get(p.getUniqueId());
			if (th == null || th.over || !FORM.equals(formOf(event.getItem()))) return;
			event.setUseItemInHand(Event.Result.DENY);
			sayAway(c, p);
		}

		/** What the trident hit: a creature that isn't the owner's to hurt is passed through; anything else it strikes starts the reel. */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onHit(ProjectileHitEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof Trident t) || !t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			Throw th = throwOf(t);
			if (th == null) {
				t.remove(); // a trident nobody holds any more
				return;
			}
			Entity hit = event.getHitEntity();
			if (hit == null) return; // a block: the flight's own stuck timer takes it from here
			// what a tendril may not strike (the owner and their pets, players when arms.players is off, NPCs, mounts, armour stands, boats, item
			// frames, shulker helpers) is passed through, exactly as Recall and the bow's harpoon refuse it
			if (hit.equals(th.player) || !(hit instanceof LivingEntity living) || !TendrilTargets.canTarget(c.plugin(), th.player, living, true, c.config().players())) {
				event.setCancelled(true);
				return;
			}
			th.struck = true;
		}

		/** The thrown trident's blow is the tier's damage (the game's own is always 8). */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onDamage(EntityDamageByEntityEvent event) {
			if (restriking || !(event.getDamager() instanceof Trident t) || !t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			Throw th = throwOf(t);
			if (th == null) {
				// an orphan (a clone another dimension's portal made, a trident its throw let go of): no blow, and no trident
				event.setCancelled(true);
				t.remove();
				return;
			}
			event.setDamage(settings.throwDamage().at(th.level));
		}

		/**
		 * A thrown blow that would kill is done again as the player's tendril kill: absorbed wherever the creature is (TendrilKills), flagged
		 * as a special's hit. Highest, so it sees the final damage.
		 */
		@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
		public void onLethal(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || restriking || !(event.getDamager() instanceof Trident t) || !(event.getEntity() instanceof LivingEntity victim)) return;
			if (!t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			Throw th = throwOf(t);
			// the final damage already has the absorption hearts taken off, so what kills is a final damage that reaches the health alone
			if (th == null || !lethal(event.getFinalDamage(), victim.getHealth())) return;
			Player owner = th.player;
			double damage = event.getDamage();
			DamageSource source = DamageSource.builder(DamageType.TRIDENT).withCausingEntity(owner).withDirectEntity(t).build();
			event.setCancelled(true);
			boolean[] went = {false};
			restriking = true;
			restrikeVictim = victim;
			restrikeTook = false;
			try {
				c.asSpecialDamage(owner, () -> TendrilKills.during(c.plugin(), owner, victim, () -> {
					// Damageable.damage returns nothing: the blow went through if the creature lost anything (or died), or if its damage event
					// came out with something taken (restrikeTook: a totem's pop can leave health plus absorption above what it was)
					double before = victim.getHealth() + victim.getAbsorptionAmount();
					victim.damage(damage, source);
					went[0] = victim.isDead() || victim.getHealth() + victim.getAbsorptionAmount() < before || restrikeTook;
				}));
			} finally {
				restriking = false;
				restrikeVictim = null;
				restrikeTook = false;
			}
			th.struck = true; // the reel follows whether or not the blow went through (a shield, a protection plugin)
			if (!went[0]) return; // nothing was taken: not billed, not drawn as a strike
			landed(c, th, t, victim, damage);
			if (c.config().report()) c.report("trident: " + owner.getName() + "'s trident killed " + victim.getType().getKey().getKey());
		}

		/** The blow went through: it is billed (the usual per-hit, never refused) and drawn, and the trident is taken back. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onStruck(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getDamager() instanceof Trident t) || !(event.getEntity() instanceof LivingEntity victim)) return;
			if (!t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			// a lethal throw's own restrike: onLethal bills it, but this event is where it is seen whether a totem took the blow
			if (restriking) {
				if (victim == restrikeVictim && taken(event) > 0) restrikeTook = true;
				return;
			}
			Throw th = throwOf(t);
			if (th == null) return;
			// a blow a shield took (nothing taken, hearts or absorption) is no strike: the reel still follows (onHit began it) but nothing is
			// billed or drawn. Absorption hearts that took the whole blow are a strike: the damage went through and cost them.
			double took = taken(event);
			if (took > 0) landed(c, th, t, victim, took);
		}

		/**
		 * A new viewer comes into range of the trident: they are told the throw (and the reel, if it is on) once, to them alone, from inside
		 * the event, so it reaches them ahead of the entity's add packet (sent right after the event) and the client never draws the vanilla
		 * decoy for a tick. Nothing is resent on a timer.
		 */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onTrack(PlayerTrackEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof Trident t) || !t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			Player viewer = event.getPlayer();
			Throw th = throwOf(t);
			if (th == null || !viewer.isOnline() || viewer.getWorld() != th.world) return;
			tellViewer(c, th, viewer);
		}

		/**
		 * The plugin's trident goes through no portal or gateway: the game would remake it in the other dimension as a clone the module no longer
		 * owns (no tier damage, no billing, no kill absorb). The tendril takes hold instead and brings it back.
		 */
		@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
		public void onPortal(EntityPortalEvent event) {
			refuseMove(event.getEntity(), event);
		}

		/** An end gateway (or anything else that teleports it): refused too, except the module's own step through a block. */
		@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
		public void onTeleport(EntityTeleportEvent event) {
			if (!selfMove) refuseMove(event.getEntity(), event);
		}

		private void refuseMove(Entity e, org.bukkit.event.Cancellable event) {
			if (!(e instanceof Trident t) || !t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) return;
			event.setCancelled(true);
			Throw th = throwOf(t);
			if (th != null) th.struck = true; // the reel begins
			else t.remove(); // a clone nobody owns
		}

		/** A decoy is never picked up, by anyone. */
		@EventHandler(priority = EventPriority.LOWEST)
		public void onPickup(PlayerPickupArrowEvent event) {
			if (event.getArrow().getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) event.setCancelled(true);
		}

		/** A flying trident in a chunk that unloads goes with it (it is not persistent, and the throw ends at the next tick). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onUnload(EntitiesUnloadEvent event) {
			for (Entity e : event.getEntities()) {
				if (e instanceof Trident t && t.getPersistentDataContainer().has(TRIDENT_KEY, PersistentDataType.BYTE)) t.remove();
			}
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			leave(event.getPlayer());
			lastLaunch.remove(event.getPlayer().getUniqueId());
			awaySaid.remove(event.getPlayer().getUniqueId());
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onDeath(PlayerDeathEvent event) {
			leave(event.getEntity());
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onWorld(PlayerChangedWorldEvent event) {
			leave(event.getPlayer());
		}

		private void leave(Player p) {
			ArmContext c = ctx;
			Throw th = throwsOut.get(p.getUniqueId());
			if (c != null && th != null) end(c, th, true);
		}
	}

	/**
	 * A thrown blow went through (or killed): it is billed, the usual per-hit and never refused (a partial spend), drawn (FX_STRIKE), and
	 * the trident is to be taken back.
	 */
	private void landed(ArmContext c, Throw th, Trident t, LivingEntity victim, double damage) {
		th.struck = true;
		long cost = c.config().costs().charge(c.config().costs().perHit());
		ArmLedger.Spend spend = c.spend(th.player, cost, true);
		Location at = victim.getLocation();
		c.fx(th.player, new ArmFxMsg(th.player.getEntityId(), ArmProtocol.FX_TRIDENT, th.level, 0, th.seed, new int[] {t.getEntityId(), victim.getEntityId()},
				new long[] {block(at.getX(), at.getY(), at.getZ())}, FX_STRIKE), ArmProtocol.fxRadius(ArmProtocol.FX_TRIDENT));
		if (c.config().report()) {
			c.report("trident: " + th.player.getName() + "'s trident hit " + victim.getType().getKey().getKey() + " for " + number(damage) + ": "
					+ (spend.charged() > 0 ? "-" + spend.charged() + " milli" : "free"));
		}
	}

	/** The throw this trident belongs to: its shooter's, and this very trident. */
	private @Nullable Throw throwOf(Trident t) {
		if (!(t.getShooter() instanceof Player owner)) return null;
		Throw th = throwsOut.get(owner.getUniqueId());
		return th != null && !th.over && th.trident == t ? th : null;
	}

	// ------------------------------------------------------------------ helpers

	/** The form id on an arm stack, null for anything else (a decoy has no arm tag). */
	private static @Nullable String formOf(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		try {
			return stack.getPersistentDataContainer().get(ARM_KEY, PersistentDataType.STRING);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static int level(int level) {
		return Math.clamp(level, 1, 3);
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	/**
	 * Whether a blow whose final damage (absorption hearts already taken off it) is {@code finalDamage} kills a creature with {@code health}:
	 * the absorption is netted out of the damage, so it must not be added to the health again.
	 */
	public static boolean lethal(double finalDamage, double health) {
		return finalDamage >= health;
	}

	/** What a blow took from the creature, hearts and absorption together: its final damage, plus the share the absorption hearts took off it. */
	@SuppressWarnings("deprecation")
	private static double taken(EntityDamageByEntityEvent event) {
		return taken(event.getFinalDamage(), event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION) ? event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION) : 0);
	}

	/** {@code finalDamage} plus what the absorption took ({@code absorptionModifier}, which is zero or negative). */
	public static double taken(double finalDamage, double absorptionModifier) {
		return finalDamage - Math.min(0, absorptionModifier);
	}

	private static long block(double x, double y, double z) {
		return new Pos((int) Math.floor(x), (int) Math.floor(y + 1e-4), (int) Math.floor(z)).asLong();
	}

	private static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}
}
