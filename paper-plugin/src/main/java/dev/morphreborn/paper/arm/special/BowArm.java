package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.accessory.Ignition;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.BleedLedger;
import dev.morphreborn.paper.arm.rules.BowSettings;
import dev.morphreborn.paper.arm.rules.BowTips;
import dev.morphreborn.paper.arm.rules.BowTips.Tip;
import dev.morphreborn.paper.arm.rules.ClawMath;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilKills;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The bow arm (spec 3.9): a recurve of black static that looses bolts of biomass, and its special, Triple Arrow.
 * <p>
 * <b>Bolts.</b> The arm item is a consumable with the bow's draw (ArmItems), so a draw and a release are the same
 * events as a real bow's: letting go fires PlayerStopUsingItemEvent with the ticks held, and that is what shoots
 * ({@code /morph arm <player> shoot} fires the same event). The power is the game's own, {@code (f² + 2f) / 3} for
 * {@code f = ticks / 20}, at most 1, and nothing below 0.1. The shot is built first and offered to other plugins as an
 * EntityShootBowEvent (a cancel ends it there); then one biomass is taken, all or nothing (when the arm can't pay, there
 * is no shot, and what little biomass is left goes into the melt); then the arrow joins the world, flying at 3 blocks a
 * tick times the power, never to be picked up, hitting for the level's base damage times its speed (about 6 / 8 / 9 at a full draw, no critical bonus). It is removed a
 * second after it sticks in a block, so a fight leaves no field of arrows. No arrow is ever used from the inventory.
 * <p>
 * <b>Triple Arrow</b> (F; 2026-11-16, in place of the Harpoon Shot). F makes the next full draw loose three
 * bolts in a fan, {@code spread} degrees apart (15), each a normal bolt: the middle one is the shot the draw would have been
 * (and pays the bolt's biomass), the other two ride on the volley's own cost. F again puts it away. It waits
 * {@code armed-seconds} (30), and the biomass and the cooldown are paid when the volley is loosed, not when F is pressed, so
 * an unused Triple Arrow costs nothing and a draw that isn't full is a normal shot that leaves it ready. When the arm can't pay
 * for the volley the draw is a normal shot and it stays ready. Retracting the arm puts it away. The volley is paid for only once at
 * least two of its three bolts are there to fly (2026-11-16 review): when other plugins refuse the others it is one normal shot too.
 * <p>
 * <b>Arrow tips</b> (Sneak+F). Bleed (the default), Slowness and Poison; Fire while the flint and steel accessory is on and
 * Glowing while the spyglass is on (BowTips). The player's choice is kept on the player ({@value #TIP_PDC}); one that is not on
 * offer acts as Bleed. The tip rides on every bolt, normal shots and the volley's three alike, and is applied when a bolt's
 * damage goes through: Bleed is the claws' damage over time (BleedLedger, the same FX_CLAWS bleed the clients already draw),
 * Slowness and Poison and Glowing are the potion effects, Fire sets the creature alight and costs the flint's ignite price in
 * biomass, paid once the creature can be lit (it can burn, isn't in water or alight) and no plugin stopped the fire; when that can't be
 * paid the creature is simply not lit. The bolt that kills carries no tip (2026-11-16 review). Sneak+F works through the volley's cooldown
 * ({@link #sneakBypassesCooldown}). A plain client sees the tip as a tipped arrow's coloured swirl
 * (and the Fire tip's flames) on the arrow.
 * <p>
 * What the clients are told rides on S_ARM_FX FX_BOLT's {@code arg} (BowTips documents the bits).
 */
public final class BowArm implements ArmSpecialModule {
	public static final String FORM = "bow";
	/** Its row in survival.yml arm-cost.specials: Triple Arrow's. */
	static final String COST_KEY = "bow-triple";
	/** The player's chosen tip, kept on the player (the plugin's namespace), as the lantern's brightness is. */
	static final String TIP_PDC = "bow_tip";

	/**
	 * The arrow keys: a bolt is told apart by this, and a tipped bolt carries its tip and level (see {@link #packTip}). This one is public for the
	 * flint and steel, which leaves the arm's own bolts to the Fire tip (FlintAccessory).
	 */
	public static final NamespacedKey BOLT_KEY = new NamespacedKey("morphreborn", "bolt");
	private static final NamespacedKey TIP_KEY = new NamespacedKey("morphreborn", "bolt_tip");
	/** The arm item's tag (ArmItems.FORM): the stack's form id. */
	private static final NamespacedKey ARM_KEY = new NamespacedKey("morphreborn", "arm");

	/** A bow's own numbers: nothing shoots below this power, and a full draw is this fast a tick. */
	static final double MIN_POWER = 0.1;
	private static final double BOLT_SPEED = 3.0;
	/** How far above the eyes' line the bolt starts (an arrow comes from the shoulder of the hand that holds the bow). */
	private static final double BOLT_DROP = 0.1;
	/** A bolt stuck in a block goes after this many ticks. */
	private static final int STUCK_TICKS = 60;
	/** The TIP message is sent when a draw starts, but never more often than this many ticks per player. */
	private static final int TIP_FX_EVERY = 10;
	/** A bleeding creature's wound, as a share of its height, and how far away the particle fallback is drawn. */
	private static final double WOUND_HEIGHT = 0.6;
	private static final double BLOOD_RANGE_SQ = 48 * 48;
	private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(140, 8, 16), 0.9F);

	private @Nullable ArmContext ctx;
	private BowSettings settings = BowSettings.DEFAULTS;
	private final Listener listener = new Events();
	/** Triple Arrow is ready: who has it and until when. */
	private final Map<UUID, Armed> armed = new HashMap<>();
	/** Who bleeds from a bolt, and the one who loosed it (a creature hit from two players' bows bleeds for each). */
	private final BleedLedger<Key> ledger = new BleedLedger<>();
	private final Map<Key, Wound> wounds = new HashMap<>();
	/** The tick each player's tip was last sent to the viewers on a draw. */
	private final Map<UUID, Integer> lastTipFx = new HashMap<>();
	/**
	 * The tick each player last let go of a draw (a bolt, or not), and the tick each last changed the held slot. The game
	 * also ends a draw when the slot changes, and PlayerStopUsingItemEvent doesn't say which it was: a stop in the tick of
	 * a slot change is no release, and a second stop in the tick of a release is the same one.
	 */
	private final Map<UUID, Integer> lastStop = new HashMap<>(), lastSlotChange = new HashMap<>();
	/** The pulse being struck right now, watched by the module's damage listener (one deep: a pulse never starts another). */
	private @Nullable Player blowOwner;
	private @Nullable LivingEntity blowTarget;
	private boolean blowSeen, blowCancelled;

	/** Triple Arrow ready: the charge that pays and starts the cooldown when it is loosed, the arm's token, and the tick it runs out. */
	private record Armed(SpecialCharge charge, long token, int until) {}

	/** A bleed's key: the owner of the bow and the creature that bleeds. */
	private record Key(UUID owner, UUID target) {}

	/** What a bleed takes at each pulse, and the level of the bolt that made it. */
	private record Wound(int level, double damage) {}

	public BowArm() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		load(ctx.config().form(FORM));
	}

	/** arms.forms.bow: {@code bolt-damage}, {@code triple: {name, spread, armed-seconds, cooldown}} and {@code tips: {bleed, slowness, poison, fire, glowing}}. */
	@Override
	public void load(Map<String, Object> formSection) {
		settings = BowSettings.parse(formSection);
	}

	@Override
	public @Nullable Listener listener() {
		return listener;
	}

	@Override
	public boolean breaksBlocks() {
		return false;
	}

	/** Sneak+F cycles the arrow tip, which is free and has no cooldown: it works while the volley cools down (2026-11-16 review, my call). */
	@Override
	public boolean sneakBypassesCooldown() {
		return true;
	}

	@Override
	public String specialName(int level) {
		return settings.tripleName();
	}

	@Override
	public long costMilli(int level) {
		ArmContext c = ctx;
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(COST_KEY, Math.clamp(level, 1, 3));
	}

	@Override
	public int cooldownTicks(int level) {
		return settings.cooldownTicks(Math.clamp(level, 1, 3));
	}

	/** "Triple Arrow: three bolts 15° apart on a full draw · bolts hit for 2.5 · tips last 4 s (bleed)" for the upgrade tree. */
	@Override
	public Lines describeLines(int level) {
		return settings.lines(Math.clamp(level, 1, 3));
	}

	/** "Triple Arrow 6 biomass · Sneak+F: arrow tip" for the arm item's lore. */
	@Override
	public Lines loreLines(int level, boolean survival, java.util.function.LongUnaryOperator charge) {
		return BowTips.loreLines(settings.tripleName(), charge.applyAsLong(costMilli(level)), survival);
	}

	// ------------------------------------------------------------------ the draw

	/**
	 * The power of a bow drawn for {@code ticks}: the game's, {@code (f² + 2f) / 3} with {@code f = ticks / 20}, at most 1.
	 * Computed in single precision, as the game does, so the numbers are the same.
	 */
	public static float power(int ticks) {
		float f = Math.max(0, ticks) / 20.0F;
		f = (f * f + f * 2.0F) / 3.0F;
		return f > 1.0F ? 1.0F : f;
	}

	/** A draw this strong looses a bolt: the game's cut-off, nothing below 0.1. */
	public static boolean looses(float power) {
		return !((double) power < MIN_POWER);
	}

	/** A draw this strong is a full one: what Triple Arrow waits for. */
	public static boolean full(float power) {
		return power >= 1.0F;
	}

	// ------------------------------------------------------------------ F and Sneak+F

	/**
	 * F makes Triple Arrow ready for the next full draw (nothing is paid until the volley is loosed), or puts it away when it was; Sneak+F
	 * is the next arrow tip. Both are free here, so each answers FIRED without charging, and neither starts a cooldown.
	 */
	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.tripleName() + " isn't ready yet.");
		if (sneaking) return cycleTip(c, p);
		UUID id = p.getUniqueId();
		int now = now(c);
		Tip tip = tipOf(c, p);
		if (armed.remove(id) != null) {
			armedFx(c, p, level, tip, 0);
			p.sendActionBar(Component.text(settings.tripleName() + " put away", NamedTextColor.GRAY));
			return SpecialResult.FIRED;
		}
		int ticks = settings.armedTicks();
		armed.put(id, new Armed(charge, c.token(p), now + ticks));
		armedFx(c, p, level, tip, ticks);
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, SoundCategory.PLAYERS, 0.6F, 1.5F);
		p.sendActionBar(Component.text(settings.tripleName() + " ready: your next full draw looses three bolts (" + tip.label() + " tip)", color(tip)));
		if (c.config().report()) c.report(settings.tripleName() + ": " + p.getName() + " at level " + level + " has it ready for " + ticks + " ticks");
		return SpecialResult.FIRED;
	}

	/** Sneak+F: the next tip on offer, kept on the player and shown to them and to every viewer. */
	private SpecialResult cycleTip(ArmContext c, Player p) {
		Tip next = BowTips.next(tipOf(c, p), id -> c.accessoryOn(p, id));
		setTip(c, p, next);
		p.sendActionBar(Component.text("Arrow tip: " + next.label(), color(next)));
		tipFx(c, p, Math.max(1, c.level(p, FORM)), next);
		if (c.config().report()) c.report("bow tip: " + p.getName() + " chose " + next.label());
		return SpecialResult.FIRED;
	}

	/** The tip that acts for this player now: their choice if it is on offer (its accessory is on), else Bleed. */
	private Tip tipOf(ArmContext c, Player p) {
		Tip chosen = null;
		try {
			chosen = Tip.byName(p.getPersistentDataContainer().get(new NamespacedKey(c.plugin(), TIP_PDC), PersistentDataType.STRING));
		} catch (IllegalArgumentException e) {
			// a value of another type under the key: no choice
		}
		return BowTips.effective(chosen, id -> c.accessoryOn(p, id));
	}

	private void setTip(ArmContext c, Player p, Tip tip) {
		p.getPersistentDataContainer().set(new NamespacedKey(c.plugin(), TIP_PDC), PersistentDataType.STRING, tip.name());
	}

	private static TextColor color(Tip tip) {
		return TextColor.color(tip.rgb());
	}

	// ------------------------------------------------------------------ the module's rounds

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || (armed.isEmpty() && ledger.isEmpty())) return;
		int now = now(c);
		if (!armed.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Armed>> it = armed.entrySet().iterator(); it.hasNext();) {
				Map.Entry<UUID, Armed> e = it.next();
				Player p = Bukkit.getPlayer(e.getKey());
				Armed a = e.getValue();
				if (p == null || !p.isOnline()) {
					it.remove();
				} else if (!c.stillArmed(p, a.token()) || now >= a.until()) {
					it.remove();
					armedFx(c, p, Math.max(1, c.level(p, FORM)), tipOf(c, p), 0);
					if (now >= a.until()) p.sendActionBar(Component.text(settings.tripleName() + " put away", NamedTextColor.GRAY));
				}
			}
		}
		if (!ledger.isEmpty()) bleedTick(c, now);
	}

	/** A retract, a reshape, leaving: Triple Arrow is put away. A bleed goes on: the wound isn't the arm's. */
	@Override
	public void abort(Player p) {
		ArmContext c = ctx;
		if (c != null && armed.remove(p.getUniqueId()) != null && p.isOnline()) armedFx(c, p, Math.max(1, c.level(p, FORM)), tipOf(c, p), 0);
	}

	@Override
	public void abortAll() {
		armed.clear();
		ledger.clear();
		wounds.clear();
	}

	// ------------------------------------------------------------------ events

	private final class Events implements Listener {
		/** Letting go of the draw: a bolt (spec 3.9). */
		@EventHandler(priority = EventPriority.NORMAL)
		public void onRelease(PlayerStopUsingItemEvent event) {
			ArmContext c = ctx;
			if (c == null) return;
			Player p = event.getPlayer();
			if (!FORM.equals(formOf(p.getInventory().getItemInMainHand()))) return; // only a bow arm's draws are tracked
			int now = now(c);
			Integer changed = lastSlotChange.get(p.getUniqueId()), stopped = lastStop.put(p.getUniqueId(), now);
			if ((changed != null && changed == now) || (stopped != null && stopped == now)) return;
			loose(c, p, event.getTicksHeldFor());
		}

		/** Scrolling to another slot ends a draw without releasing it (the event runs before the game stops the draw). */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onSlot(PlayerItemHeldEvent event) {
			ArmContext c = ctx;
			if (c == null || event.getPreviousSlot() == event.getNewSlot()) return;
			if (!FORM.equals(formOf(event.getPlayer().getInventory().getItem(event.getPreviousSlot())))) return;
			lastSlotChange.put(event.getPlayer().getUniqueId(), now(c));
		}

		/** A draw begins: every viewer is told which tip is nocked (so a bow they just came into view of draws the right one). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onDrawStart(PlayerInteractEvent event) {
			ArmContext c = ctx;
			if (c == null || event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == Event.Result.DENY) return;
			if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
			Player p = event.getPlayer();
			if (!FORM.equals(formOf(p.getInventory().getItemInMainHand()))) return;
			int now = now(c);
			Integer last = lastTipFx.get(p.getUniqueId());
			if (last != null && now - last < TIP_FX_EVERY) return;
			lastTipFx.put(p.getUniqueId(), now);
			tipFx(c, p, Math.max(1, c.level(p, FORM)), tipOf(c, p));
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			UUID id = event.getPlayer().getUniqueId();
			lastStop.remove(id);
			lastSlotChange.remove(id);
			lastTipFx.remove(id);
			armed.remove(id);
			forget(k -> k.target().equals(id) || k.owner().equals(id));
		}

		/** A bolt stuck in a block goes soon. */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onHit(org.bukkit.event.entity.ProjectileHitEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof AbstractArrow arrow)) return;
			if (arrow.getPersistentDataContainer().has(BOLT_KEY, PersistentDataType.BYTE) && event.getHitBlock() != null) removeLater(c, arrow);
		}

		/** A bleed pulse's blow, watched: did its damage event run, and did anything cancel it. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onBlow(EntityDamageByEntityEvent event) {
			if (blowOwner == null || event.getDamager() != blowOwner || event.getEntity() != blowTarget) return;
			blowSeen = true;
			blowCancelled = event.isCancelled();
		}

		/** A bolt's damage went through: its tip takes hold of the creature. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onTipHit(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getDamager() instanceof AbstractArrow arrow) || !(event.getEntity() instanceof LivingEntity victim)) return;
			Integer packed = arrow.getPersistentDataContainer().get(TIP_KEY, PersistentDataType.INTEGER);
			if (packed == null || !(arrow.getShooter() instanceof Player owner) || victim == owner || victim.isDead() || !(event.getFinalDamage() > 0)) return;
			// MONITOR runs before the damage is applied, so isDead() is never true for the killing hit: the bolt that kills carries no tip
			if (lethal(victim.getHealth(), event.getFinalDamage())) return;
			applyTip(c, owner, victim, arrow, tipOfPacked(packed), levelOfPacked(packed));
		}

		/** A creature that died bleeds no more. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onDeath(EntityDeathEvent event) {
			UUID id = event.getEntity().getUniqueId();
			forget(k -> k.target().equals(id));
		}

		/** A player who dies stops bleeding, and so do the wounds their bow made; their volley is put away. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onPlayerDeath(PlayerDeathEvent event) {
			UUID id = event.getEntity().getUniqueId();
			armed.remove(id);
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
	}

	private void removeLater(ArmContext c, AbstractArrow arrow) {
		try {
			Bukkit.getScheduler().runTaskLater(c.plugin(), () -> {
				if (arrow.isValid()) arrow.remove();
			}, STUCK_TICKS);
		} catch (IllegalPluginAccessException e) {
			// the plugin is going down; the arrow isn't saved (non-persistent) and goes with the world
		}
	}

	// ------------------------------------------------------------------ bolts

	/** A tip and the level of the bolt that carries it, as one number on the arrow: the tip's id in the low four bits. */
	static int packTip(Tip tip, int level) {
		return tip.id() | Math.clamp(level, 1, 3) << 4;
	}

	static Tip tipOfPacked(int packed) {
		return Tip.of(packed & 0xF);
	}

	static int levelOfPacked(int packed) {
		return Math.clamp(packed >> 4, 1, 3);
	}

	/** One bolt, built (not yet in the world): the level's damage, the tip, never to be picked up. {@code fanTurn}: degrees from the aim. */
	private Arrow build(World world, Player p, Location start, Vector dir, float power, int level, Tip tip, double fanTurn) {
		Vector aim = dir.clone();
		if (fanTurn != 0) {
			double[] v = BowTips.turn(dir.getX(), dir.getY(), dir.getZ(), fanTurn);
			aim.setX(v[0]).setY(v[1]).setZ(v[2]);
		}
		Arrow arrow = world.createEntity(start, Arrow.class);
		arrow.setShooter(p);
		arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		arrow.setDamage(settings.boltDamage().at(level));
		// never critical: the flag draws the game's crit stars along the flight (they hid the archer from anyone behind, and flashed in their own view)
		// and adds a random bonus. The hit is the base damage times the speed (the crossbow forces its own flat number in its damage event; the bow does not)
		arrow.setCritical(false);
		arrow.setPersistent(false);
		arrow.setVelocity(aim.multiply(BOLT_SPEED * power));
		arrow.getPersistentDataContainer().set(BOLT_KEY, PersistentDataType.BYTE, (byte) 1);
		arrow.getPersistentDataContainer().set(TIP_KEY, PersistentDataType.INTEGER, packTip(tip, level));
		// what a plain client sees: the tipped arrow's swirl in the tip's colour, and the flames of a Fire tip
		arrow.setColor(Color.fromRGB(tip.rgb()));
		if (tip == Tip.FIRE) arrow.setVisualFire(true);
		return arrow;
	}

	/** A draw let go: shoot it if the player holds this arm, formed, and the draw was strong enough. */
	private void loose(ArmContext c, Player p, int ticksHeld) {
		float power = power(ticksHeld);
		if (!looses(power)) return;
		ItemStack held = p.getInventory().getItemInMainHand();
		if (!FORM.equals(formOf(held)) || !c.stillArmed(p, c.token(p)) || p.getGameMode() == GameMode.SPECTATOR) return;
		int level = Math.max(1, c.level(p, FORM));
		World world = p.getWorld();
		Location eye = p.getEyeLocation();
		Vector dir = eye.getDirection();
		Location start = eye.clone().subtract(0, BOLT_DROP, 0);
		Tip tip = tipOf(c, p);
		Armed ready = full(power) ? armed.get(p.getUniqueId()) : null; // Triple Arrow waits for a full draw
		if (ready != null && !c.stillArmed(p, ready.token())) ready = null;

		// 1. the shot, built and offered to other plugins (an anticheat or a region plugin may cancel it)
		Arrow arrow = build(world, p, start, dir, power, level, tip, 0);
		EntityShootBowEvent shoot = new EntityShootBowEvent(p, held, null, arrow, EquipmentSlot.HAND, power, false);
		Bukkit.getPluginManager().callEvent(shoot);
		if (shoot.isCancelled()) return;

		// 2. one biomass, all or nothing: no biomass, no bolt, and what little there is left goes into the melt
		long cost = c.config().costs().charge(c.config().costs().perBolt());
		ArmLedger.Spend paid = c.spend(p, cost, false);
		if (!paid.paid()) {
			p.sendActionBar(Component.translatable("morphreborn.arm.bolt.refused", "Not enough biomass for a bolt", NamedTextColor.RED));
			if (paid.after() > 0) c.spend(p, paid.after(), true);
			return;
		}

		// 3. the shot joins the world first, and the volley is only paid for once its bolts are known to exist (2026-11-16 review): the
		// side bolts are built and offered to other plugins now but not yet added, and the volley's biomass and cooldown are paid only
		// when at least two bolts of the three are there to fly; otherwise this is the one normal shot and Triple Arrow stays ready.
		// Known cost (second review): the charge can't be asked "could you pay?" without paying, so the side bolts' EntityShootBowEvents are
		// offered before the price is known; on a full draw the arm can't afford, listeners see two shots that never fly, again on each
		// retry while it stays ready. Paying first would bill a volley that other plugins then cut down, which is worse.
		int seed = ThreadLocalRandom.current().nextInt(1 << 30);
		int percent = Math.round(power * 100);
		Entity flying = shoot.getProjectile();
		world.addEntity(flying);
		boolean middle = flying.isValid();
		Entity[] sides = new Entity[2];
		int[] places = {BowTips.FAN_LEFT, BowTips.FAN_RIGHT};
		int sideCount = 0;
		if (ready != null) {
			for (int i = 0; i < places.length; i++) {
				Arrow side = build(world, p, start, dir, power, level, tip, BowTips.fanOffset(places[i], settings.spreadDeg()));
				EntityShootBowEvent sideShoot = new EntityShootBowEvent(p, held, null, side, EquipmentSlot.HAND, power, false);
				Bukkit.getPluginManager().callEvent(sideShoot);
				if (!sideShoot.isCancelled()) {
					sides[i] = sideShoot.getProjectile();
					sideCount++;
				}
			}
		}
		boolean fan = ready != null && (middle ? 1 : 0) + sideCount >= 2 && ready.charge().pay(costMilli(level), cooldownTicks(level));
		if (fan) {
			armed.remove(p.getUniqueId());
			armedFx(c, p, level, tip, 0);
		}
		int loosed = 0;
		if (middle) {
			loosed++;
			boltFx(c, p, level, seed, flying, BowTips.boltArg(percent, tip, fan ? BowTips.FAN_MIDDLE : -1));
		}
		if (fan) {
			for (int i = 0; i < places.length; i++) {
				Entity side = sides[i];
				if (side == null) continue;
				world.addEntity(side);
				if (!side.isValid()) continue;
				loosed++;
				boltFx(c, p, level, seed, side, BowTips.boltArg(percent, tip, places[i]));
			}
		}
		if (loosed == 0) {
			// another plugin removed every bolt on add: the bolt's biomass goes back, as nothing flew (the volley is paid only when two bolts were there, so this is the rare case of both side bolts vanishing on add)
			if (paid.charged() > 0) c.gain(p, paid.charged());
			return;
		}
		float pitch = 1.0F / (ThreadLocalRandom.current().nextFloat() * 0.4F + 1.2F) + power * 0.5F;
		world.playSound(eye, Sound.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0F, pitch);
		if (c.config().report()) {
			c.report("bolt: " + p.getName() + " at level " + level + " let go of a " + ticksHeld + "-tick draw, power " + SurvivalMode.number(power) + ", damage "
					+ SurvivalMode.number(settings.boltDamage().at(level)) + ", " + tip.label() + " tip"
					+ (fan ? ", " + settings.tripleName() + " (" + loosed + " bolts, " + cost + " milli + " + costMilli(level) + " milli, cooldown " + cooldownTicks(level) + " ticks)" : ""));
		}
	}

	// ------------------------------------------------------------------ the tips

	/** The bolt's damage went through on {@code victim}: its tip takes hold, if the creature is fair game for the player's tendrils. */
	private void applyTip(ArmContext c, Player owner, LivingEntity victim, AbstractArrow arrow, Tip tip, int level) {
		if (!TendrilTargets.canTarget(c.plugin(), owner, victim, true, c.config().players())) return;
		BowSettings s = settings;
		switch (tip) {
			case BLEED -> startBleed(c, owner, victim, level, now(c));
			case SLOWNESS -> potion(victim, PotionEffectType.SLOWNESS, BowSettings.ticks(s.slowSeconds(), level), (int) Math.round(s.slowAmplifier().at(level)));
			case POISON -> potion(victim, PotionEffectType.POISON, BowSettings.ticks(s.poisonSeconds(), level), (int) Math.round(s.poisonAmplifier().at(level)));
			case GLOWING -> potion(victim, PotionEffectType.GLOWING, BowSettings.ticks(s.glowSeconds(), level), 0);
			case FIRE -> ignite(c, owner, victim, arrow, BowSettings.ticks(s.fireSeconds(), level), level);
		}
	}

	private static void potion(LivingEntity victim, PotionEffectType type, int ticks, int amplifier) {
		if (ticks <= 0) return;
		victim.addPotionEffect(new PotionEffect(type, ticks, Math.max(0, amplifier), true, true, true));
	}

	/**
	 * Whether a hit of this final damage kills a creature of this health. The final damage is already net of the creature's absorption
	 * hearts (the event's own ABSORPTION modifier is in it), so health is all there is to take it from.
	 */
	public static boolean lethal(double health, double finalDamage) {
		return health - finalDamage <= 0;
	}

	/**
	 * Fire: one ignition, paid in biomass at the flint and steel's price once the creature can be lit and no other plugin has stopped it
	 * (as for any fire arrow); not paid, not lit. The test of what can burn, the event and the price are the flint and steel's
	 * ({@link Ignition}, moved there on 2026-11-16 so the melee hits and the crossbow's bolts light creatures the same way).
	 */
	private void ignite(ArmContext c, Player owner, LivingEntity victim, AbstractArrow arrow, int ticks, int level) {
		Ignition.ignite(c, owner, victim, arrow, ticks, level);
	}

	// ------------------------------------------------------------------ bleed (the claws' damage over time, BleedLedger)

	/** The creature starts to bleed from this bolt (or its bleed is topped up). */
	private void startBleed(ArmContext c, Player owner, LivingEntity t, int level, int now) {
		BowSettings s = settings;
		int pulses = s.bleedPulses(level);
		if (pulses <= 0) return;
		Key key = new Key(owner.getUniqueId(), t.getUniqueId());
		Wound wound = new Wound(level, s.bleedDamage().at(level));
		Wound old = wounds.get(key);
		if (old == null || wound.damage() >= old.damage()) wounds.put(key, wound);
		ledger.start(key, now, pulses, s.bleedIntervalTicks());
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_CLAWS, level, ledger.ticksLeft(key, now), ThreadLocalRandom.current().nextInt(1 << 30),
				new int[] {t.getEntityId()}, null, ClawMath.bleedArg(s.bleedIntervalTicks())), ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
		blood(c, t, 4);
		if (c.config().report()) {
			c.report("bow bleed: " + owner.getName() + " made " + t.getName() + " bleed (" + ledger.pulsesLeft(key) + " pulses of " + SurvivalMode.number(wound.damage()) + ")");
		}
	}

	/** The pulses due now: each takes its damage, or ends its bleed when it can no longer go on. */
	private void bleedTick(ArmContext c, int now) {
		for (Key key : ledger.due(now)) {
			Wound wound = wounds.get(key);
			Player owner = Bukkit.getPlayer(key.owner());
			Entity e = Bukkit.getEntity(key.target());
			if (wound == null || owner == null || !owner.isOnline() || owner.isDead() || !(e instanceof LivingEntity t) || !alive(t) || t.getWorld() != owner.getWorld()
					|| !TendrilTargets.canTarget(c.plugin(), owner, t, true, c.config().players())) {
				// gone, or no longer fair game: nothing more to take (the clients time the bleed out themselves)
				stop(key, e instanceof LivingEntity gone && alive(gone) && owner != null && owner.isOnline() ? gone : null, owner);
				continue;
			}
			double[] took = {0};
			boolean hadFrames = t.getNoDamageTicks() > 0;
			blowOwner = owner;
			blowTarget = t;
			blowSeen = false;
			blowCancelled = false;
			try {
				c.asSpecialDamage(owner, () -> TendrilKills.during(c.plugin(), owner, t, () -> took[0] = DrainHit.drain(owner, t, wound.damage(), c.config().playerFloor())));
			} finally {
				blowOwner = null;
				blowTarget = null;
			}
			if (blowSeen && blowCancelled) {
				// a claim, a PvP rule or a team says no: the wound closes
				stop(key, t, owner);
				continue;
			}
			if (!(took[0] > 0) && !blowSeen && hadFrames) {
				// inside a hurt window from something else: try again next tick
				if (ledger.retry(key, now)) stop(key, alive(t) ? t : null, owner);
				continue;
			}
			if (took[0] > 0) {
				fxMove(c, owner, wound.level(), t, ClawMath.MOVE_BLEED_PULSE);
				blood(c, t, 6);
			}
			if (!alive(t)) {
				wounds.remove(key);
				ledger.stop(key);
			} else if (ledger.pulsed(key, now)) {
				wounds.remove(key);
				fxMove(c, owner, wound.level(), t, ClawMath.MOVE_BLEED_END); // the last pulse: the drips stop
			}
		}
	}

	/** A bleed is over before its time: forget it, and tell the clients when the creature is still there to show it. */
	private void stop(Key key, @Nullable LivingEntity stillThere, @Nullable Player owner) {
		ledger.stop(key);
		Wound wound = wounds.remove(key);
		ArmContext c = ctx;
		if (c != null && stillThere != null && owner != null && wound != null) fxMove(c, owner, wound.level(), stillThere, ClawMath.MOVE_BLEED_END);
	}

	/** Every bleed this says to stop (a creature gone, a player leaving): quietly, the clients time theirs out. */
	private void forget(java.util.function.Predicate<Key> drop) {
		if (ledger.isEmpty()) return;
		ledger.stopIf(drop);
		wounds.keySet().removeIf(drop);
	}

	/** FX_CLAWS BLEED_PULSE or BLEED_END for a creature (the claws' own messages; the clients draw the bow's bleed as theirs). */
	private static void fxMove(ArmContext c, Player owner, int level, LivingEntity t, int move) {
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_CLAWS, level, 0, 0, new int[] {t.getEntityId()}, null, move), ArmProtocol.fxRadius(ArmProtocol.FX_CLAWS));
	}

	/** Dark red drops at a creature's wound, for players whose game doesn't draw the new forms' effects. */
	private static void blood(ArmContext c, LivingEntity t, int count) {
		Location at = t.getLocation().add(0, t.getHeight() * WOUND_HEIGHT, 0);
		double spreadX = t.getWidth() / 3, spreadY = t.getHeight() / 5;
		for (Player v : t.getWorld().getPlayers()) {
			if (c.drawsNewForms(v) || v.getLocation().distanceSquared(at) > BLOOD_RANGE_SQ) continue;
			v.spawnParticle(Particle.DUST, at, count, spreadX, spreadY, spreadX, 0, BLOOD);
		}
	}

	private static boolean alive(@Nullable LivingEntity e) {
		return e != null && e.isValid() && !e.isDead();
	}

	// ------------------------------------------------------------------ what the screens are sent (all FX_BOLT; BowTips documents the arg)

	/** FX_BOLT for one bolt: level = the tier; ticks 0; ids = [the arrow]; arg = BowTips.boltArg (power percent, tip, volley place). */
	private static void boltFx(ArmContext c, Player p, int level, int seed, Entity arrow, int arg) {
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_BOLT, level, 0, seed, new int[] {arrow.getEntityId()}, null, arg), ArmProtocol.fxRadius(ArmProtocol.FX_BOLT));
	}

	/**
	 * FX_BOLT ARMED (no arrow: ids empty): Triple Arrow is ready for {@code ticks} more ticks, or (ticks 0) no longer. level = the tier; arg =
	 * BowTips.armedArg (the tip, so the three nocked arrows can show it).
	 */
	private static void armedFx(ArmContext c, Player p, int level, Tip tip, int ticks) {
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_BOLT, level, Math.max(0, ticks), 0, null, null, BowTips.armedArg(tip)), ArmProtocol.fxRadius(ArmProtocol.FX_BOLT));
	}

	/** FX_BOLT TIP (no arrow: ids empty): the player's arrow tip is now this one. level = the tier; ticks 0; arg = BowTips.tipArg. */
	private static void tipFx(ArmContext c, Player p, int level, Tip tip) {
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_BOLT, level, 0, 0, null, null, BowTips.tipArg(tip)), ArmProtocol.fxRadius(ArmProtocol.FX_BOLT));
	}

	// ------------------------------------------------------------------ helpers

	/** The form id on an arm stack, null for anything else. */
	private static @Nullable String formOf(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		try {
			return stack.getPersistentDataContainer().get(ARM_KEY, PersistentDataType.STRING);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}
}
