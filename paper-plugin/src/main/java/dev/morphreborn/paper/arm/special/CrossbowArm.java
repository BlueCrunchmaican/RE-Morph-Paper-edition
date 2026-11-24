package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.accessory.FlintAccessory;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.ArmView;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.CrossbowBolt;
import dev.morphreborn.paper.arm.rules.CrossbowSettings;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The crossbow arm (my design note, 2026-11-16, 8.0.0-beta.2): a real crossbow that loads itself, and Spine Burst, its F.
 * <p>
 * <b>The loaded state.</b> The arm item is a real CROSSBOW (ArmTiers.material), so every client, Bedrock included, shows its loaded and
 * unloaded poses natively. The plugin owns the charged state: the bolt is a marker arrow in the item's CHARGED_PROJECTILES, set when the bolt is
 * whole and cleared when it isn't, and the player never loads it. The arm is formed loaded (the first tick it is formed and in hand). The state
 * itself lives in {@link CrossbowBolt}; every tick this module writes the held item to match it (it is the one truth: a rebuild of the stack by a
 * re-tier or an untuck, a vanilla charge that finished, anything, is put right within the tick). A change of that one component does not touch
 * the arm's tags, nonce or marks, so the stash and the guards never notice it.
 * <p>
 * <b>Firing.</b> A right-click with the bolt whole is the game's own: it fires the charged crossbow at once. That shot is intercepted
 * (EntityShootBowEvent, with ProjectileLaunchEvent behind it): the game's arrow is cancelled, so no real arrow ever flies, drops or reaches an
 * inventory, and the arm's own bolt flies instead, a tendril bolt (S_ARM_FX FX_CROSSBOW BOLT) at {@code bolt-speed} for {@code bolt-damage}, offered to
 * other plugins as an EntityShootBowEvent first (a cancel ends it before anything is paid), as the bow's is.
 * Each bolt costs the usual {@code per-bolt}, all or nothing (the game's wear is cancelled by the guard, which doesn't bill it again:
 * ArmTiers.billsOwnWear). The crossbow is empty from the shot; <b>the bolt regrows</b> over the time the game takes to charge a crossbow, 25 ticks,
 * less with Quick Charge as the game works it out ({@link CrossbowBolt#growTicks}), playing the crossbow's own charge sounds, and is loaded again
 * when it is whole. A right-click with no whole bolt does nothing: the use is refused (PlayerInteractEvent) so the game never charges the crossbow
 * from the inventory's arrows, and a load that got through anyway is cancelled (EntityLoadCrossbowEvent), so no real arrow is ever used.
 * <p>
 * <b>Spine Burst</b> (F). It needs the whole bolt, takes it (the crossbow is empty at once) and fires {@code spines} bone spines one after another,
 * {@code gap-ticks} apart, along the owner's aim as it is when each leaves: a tight line, not the bow's spread. Each spine pierces up to {@code pierce}
 * creatures for {@code damage} by tier, and each lands in full whatever the one before it did (the hit clears the creature's invulnerability frames,
 * which would otherwise swallow the second and third). Spines don't fall; they are gone at {@code range}, or when they hit a block. F is paid up
 * front (arm-cost specials.crossbow), all or nothing. When the last spine has left, the bolt starts to regrow. F with no whole bolt finds
 * nothing and costs nothing. A creature that isn't the owner's to hurt (TendrilTargets: their pets, mounts, NPCs, and players unless arms.players) is
 * passed through; a player is otherwise struck only if the damage event lets it (PvP).
 * <p>
 * Every later step checks that the arm is still formed and in hand in the world it fired in. If not, the rest of the burst is dropped.
 * <p>
 * The clients are sent S_ARM_FX kind FX_CROSSBOW (kind 18), the move in {@code arg} ({@link #FX_BOLT}, {@link #FX_SPINES}, {@link #FX_REGROW};
 * the fields are on each constant, and docs/PROTOCOL.md has the table). The plain shot goes out as kind 18 arg 1, not as the bow's FX_BOLT: the
 * client draws the crossbow's own tendril bolt for it.
 */
public final class CrossbowArm implements ArmSpecialModule {
	public static final String FORM = "crossbow";
	/** Its row in survival.yml arm-cost.specials (Spine Burst). */
	private static final String COST_KEY = "crossbow";

	/**
	 * FX_CROSSBOW {@code arg}: a click fired the bolt, once per shot. level = the tier; ticks = 0; seed = a number for the client's look (random);
	 * ids = [the bolt's arrow entity id]; positions = []. Never sent when another plugin refused the arrow (nothing flew: the crossbow stays loaded
	 * and the click is refunded). The arrow is a real vanilla arrow entity that nothing can pick up; a client draws it as the tendril bolt in the arrow's place and does not draw the arrow.
	 * The weapon in the hand empties its groove at once. FX_REGROW follows on the same tick.
	 */
	public static final int FX_BOLT = 1;
	/**
	 * FX_CROSSBOW {@code arg}: Spine Burst has begun, once, ONE message for the whole burst. level = the tier; ticks = the spacing between two
	 * spines (gap-ticks, "2 to 3"); seed = the burst's; ids = the arrow entity ids of every spine the burst will fire, in firing
	 * order (3 by default, up to 12), all known up front (the arrows are made at the start, and each is added to the world on its own tick, the
	 * first on this one: a client waits for one that hasn't appeared yet); positions = []. A client draws each as a bone spine in the arrow's
	 * place and flashes the muzzle as each leaves. The crossbow is empty from here; FX_REGROW follows when the last spine has left.
	 */
	public static final int FX_SPINES = 2;
	/**
	 * FX_CROSSBOW {@code arg}: the bolt is growing back out of the crossbow: sent when the shot (or the burst) is over and the regrow starts, and
	 * again every {@value CrossbowBolt#RESEND_TICKS} ticks while it grows, for a viewer who came into range meanwhile. level = the tier; ticks =
	 * the regrow's TOTAL length (25 by default; less with Quick Charge), the same on every message of one regrow; ids = [how many ticks are left
	 * before the bolt is whole] (the same as ticks on the first message); seed = the regrow's seed (the same on every message of one regrow, a new
	 * one for the next: a client that already has a regrow with this seed keeps its own start and ignores the message, one that doesn't derives
	 * growth = 1 - left / total); positions = []. A message whose ids are empty means all of ticks are left. The crossbow's item has no charged
	 * projectile from here until the bolt is whole (the client draws the growth in its place); no message says when it is: the client reaches 1.
	 */
	public static final int FX_REGROW = 3;

	/** A bolt's flight: how far above the eyes' line it starts (a bolt comes from the shoulder of the hand that holds the crossbow). */
	private static final double BOLT_DROP = 0.1;
	/** A bolt that finished growing is heard (its last charge sound) only if it finished this many ticks ago or less: not for one that finished while the arm was away. */
	private static final int READY_SOUND_WINDOW = 5;
	/** A bolt or spine stuck in a block goes after this many ticks (a bolt, as the bow's), a spine at once. */
	private static final int STUCK_TICKS = 60;

	/** The marks on the projectiles: one number, kind and tier (CrossbowBolt.mark). Public for the flint and steel, which leaves these shots to this module. */
	public static final NamespacedKey MARK_KEY = MorphRebornPlugin.key("arm_crossbow");
	/** The arm item's tag (ArmItems.FORM): the stack's form id. */
	private static final NamespacedKey ARM_KEY = MorphRebornPlugin.key("arm");
	/** The marker arrow's mark, so nothing mistakes it for a real arrow. */
	private static final NamespacedKey MARKER_KEY = MorphRebornPlugin.key("arm_bolt_marker");

	private @Nullable ArmContext ctx;
	private CrossbowSettings settings = CrossbowSettings.DEFAULTS;
	private final Map<UUID, CrossbowBolt> bolts = new HashMap<>();
	private final Map<UUID, Burst> bursts = new HashMap<>();
	private final List<Spine> spines = new ArrayList<>();
	/** The tick each player's shot was last taken over (a shot that both events report is one shot). */
	private final Map<UUID, Integer> lastShot = new HashMap<>();
	private final Listener events = new Events();

	/** A burst under way. */
	private static final class Burst {
		final Player player;
		final long token;
		final World world;
		final int start, level, seed, grow;
		final int[] at;
		/** The spines' arrows, made at the start (so one FX_SPINES message can name them all) and added to the world one by one. */
		final Arrow[] arrows;
		int next;

		Burst(Player player, long token, int start, int level, int seed, int[] at, Arrow[] arrows, int grow) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.seed = seed;
			this.at = at;
			this.arrows = arrows;
			this.grow = grow;
		}
	}

	/** A spine in flight, gone at {@code until}. */
	private record Spine(AbstractArrow arrow, int until) {}

	public CrossbowArm() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.settings = CrossbowSettings.parse(ctx.config().form(FORM));
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = CrossbowSettings.parse(formSection);
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
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(COST_KEY, level(level));
	}

	@Override
	public int cooldownTicks(int level) {
		return settings.cooldownTicks(level(level));
	}

	@Override
	public Lines describeLines(int level) {
		return settings.lines(level(level));
	}

	/** A burst is still firing: F refuses until its last spine has gone. */
	@Override
	public boolean running(Player p) {
		return bursts.containsKey(p.getUniqueId());
	}

	// ------------------------------------------------------------------ the loaded state

	/**
	 * Every tick: spines are timed out, bursts go on, and each player holding this arm gets the crossbow's charged state written to match
	 * the bolt's state (and the regrow's sounds and messages sent). Cheap for everyone else: a main hand that isn't a crossbow ends the check.
	 */
	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null) return;
		int now = now(c);
		if (!spines.isEmpty()) {
			for (Iterator<Spine> it = spines.iterator(); it.hasNext();) {
				Spine s = it.next();
				if (!s.arrow().isValid()) it.remove();
				else if (now >= s.until()) {
					s.arrow().remove();
					it.remove();
				}
			}
		}
		if (!bursts.isEmpty()) {
			for (Burst b : List.copyOf(bursts.values())) stepBurst(c, b, now);
		}
		for (Player p : c.plugin().getServer().getOnlinePlayers()) {
			ItemStack held = p.getInventory().getItemInMainHand();
			if (held.getType() != Material.CROSSBOW || !FORM.equals(formOf(held))) {
				tickTucked(c, p, now);
				continue;
			}
			long token = c.token(p);
			if (token == 0 || !c.stillArmed(p, token)) continue;
			CrossbowBolt bolt = stateOf(p);
			bolt.advance(now);
			// a burst gone without its end having been noticed must not leave the crossbow empty for good
			if (bolt.phase() == CrossbowBolt.Phase.BURSTING && !bursts.containsKey(p.getUniqueId())) bolt.endBurst(now, growTicks(held));
			if (isLoaded(held) != bolt.loaded()) setLoaded(p, bolt.loaded());
			if (bolt.takeReady()) ready(c, p, bolt, now);
			else if (bolt.phase() == CrossbowBolt.Phase.GROWING) growing(c, p, bolt, now);
		}
	}

	/**
	 * A tucked crossbow (a container screen is open, so the arm item is away and the hand holds the real item) goes on regrowing: the state is
	 * the player's and time-based, so it advances here as it does in hand, with the regrow's sounds and resends (a viewer who comes into range
	 * meanwhile hears of it). With no item to write the loaded state to, the stand-in others are shown is refreshed instead
	 * ({@link ArmContext#tuckedLoaded}), so the weapon fills when the bolt is whole, not only when the screen closes (rig bug B1, 2026-11-16:
	 * the stand-in was a snapshot from the moment of the tuck, so a crossbow tucked right after a shot looked empty for as long as the screen
	 * stayed open). Cheap for everyone else: a player with no tucked crossbow ends at the first lookup.
	 */
	private void tickTucked(ArmContext c, Player p, int now) {
		// a player who never held this arm has no bolt state (the arm's own tick makes one): no boxed id, no view lookup for them
		CrossbowBolt bolt = bolts.get(p.getUniqueId());
		if (bolt == null) return;
		ArmView view = c.views().get(p.getEntityId());
		if (view == null || !view.tucked() || !FORM.equals(view.form())) return;
		bolt.advance(now);
		// a tuck ends a burst (abortSpecial), so one still marked as running without its end noticed must not leave the bolt gone for good;
		// the burst's own regrow length (Quick Charge counted) was kept when it began, as there is no held item to read it from
		if (!bursts.containsKey(p.getUniqueId())) bolt.recoverOrphanedBurst(now, growTicks(null));
		if (bolt.standInStale(view.charged())) c.tuckedLoaded(p, bolt.loaded());
		if (bolt.takeReady()) ready(c, p, bolt, now);
		else if (bolt.phase() == CrossbowBolt.Phase.GROWING) growing(c, p, bolt, now);
	}

	/**
	 * The player's bolt state, kept across arm sessions (a new nonce from a tuck and untuck, a re-form or a reshape does not touch it: the
	 * review of 2026-11-16 found that a state keyed to the nonce made each of those a free reload, skipping the regrow). A player with none
	 * gets one formed loaded.
	 */
	private CrossbowBolt stateOf(Player p) {
		return bolts.computeIfAbsent(p.getUniqueId(), id -> new CrossbowBolt(ThreadLocalRandom.current().nextInt()));
	}

	/** The state if the player holds a formed arm of this form; null otherwise (nothing to fire, nothing to refuse). */
	private @Nullable CrossbowBolt armed(ArmContext c, Player p, @Nullable ItemStack stack) {
		if (!FORM.equals(formOf(stack))) return null;
		long token = c.token(p);
		if (token == 0 || !c.stillArmed(p, token)) return null;
		CrossbowBolt bolt = stateOf(p);
		bolt.advance(now(c));
		return bolt;
	}

	/**
	 * The bolt is whole: its last sound. No message goes to the clients: the regrow they hold reaches 1 on its own clock, and the stack they
	 * see is loaded from here. (A bolt that finished while the arm was away has been whole for a while: it is heard only if it was just now.)
	 */
	private void ready(ArmContext c, Player p, CrossbowBolt bolt, int now) {
		if (now - bolt.readyAt() > READY_SOUND_WINDOW) return;
		p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, SoundCategory.PLAYERS, 1.0F,
				1.0F / (ThreadLocalRandom.current().nextFloat() * 0.5F + 1.0F) + 0.2F);
		if (c.config().report()) c.report("crossbow: " + p.getName() + "'s bolt has grown back (regrow " + bolt.cycle() + ")");
	}

	/** The regrow goes on: the crossbow's own charge sounds, and the state sent again for anyone who came into range. */
	private void growing(ArmContext c, Player p, CrossbowBolt bolt, int now) {
		int sound = bolt.soundDue(now);
		if (sound != CrossbowBolt.SOUND_NONE) {
			Sound s = sound == CrossbowBolt.SOUND_START ? Sound.ITEM_CROSSBOW_LOADING_START : Sound.ITEM_CROSSBOW_LOADING_MIDDLE;
			p.getWorld().playSound(p.getLocation(), s, SoundCategory.PLAYERS, 0.5F, 1.0F);
		}
		if (bolt.resendDue(now)) regrowFx(c, p, Math.max(1, c.level(p, FORM)), bolt, now);
	}

	/** FX_REGROW for the state as it is now: ticks = the whole regrow, ids = [the ticks left], seed = the regrow's (see the constant). */
	private void regrowFx(ArmContext c, Player p, int level, CrossbowBolt bolt, int now) {
		fx(c, p, level, bolt.totalTicks(), bolt.regrowSeed(), FX_REGROW, new int[] {bolt.ticksLeft(now)});
	}

	/**
	 * The crossbow's item is loaded or emptied to match the state: one component on the held stack, nothing else about it. The stack
	 * getItemInMainHand gives is a live mirror of the slot's item, so setData writes the item itself; it is NOT put back with setItem, which
	 * stores a copy of it and so orphans the very stack the game is in the middle of using when this runs inside a shot's event (2026-11-16).
	 */
	private void setLoaded(Player p, boolean loaded) {
		ItemStack held = p.getInventory().getItemInMainHand();
		if (held.isEmpty()) return;
		if (loaded) held.setData(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles.chargedProjectiles().add(marker()));
		else held.setData(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles.chargedProjectiles());
	}

	/** The stack is loaded: its charged projectiles aren't empty. */
	private static boolean isLoaded(ItemStack stack) {
		ChargedProjectiles charged = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
		return charged != null && !charged.projectiles().isEmpty();
	}

	/**
	 * What sits in the loaded crossbow: an arrow named for what it is, so the item's tooltip reads "Tendril Bolt" and nothing mistakes it for
	 * a real arrow (it never leaves the component: the game's shot is cancelled and its copy discarded).
	 */
	private static ItemStack marker() {
		ItemStack arrow = ItemStack.of(Material.ARROW);
		arrow.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Tendril Bolt", TextColor.color(ArmItemSpecs.NAME_COLOR)).decoration(TextDecoration.ITALIC, false));
		arrow.editPersistentDataContainer(pdc -> pdc.set(MARKER_KEY, PersistentDataType.BYTE, (byte) 1));
		return arrow;
	}

	// ------------------------------------------------------------------ a click: the bolt

	/**
	 * The game fired the crossbow for a player (its shot was cancelled, or is about to be): this player's bolt flies instead, if they may.
	 * Whatever happens, no real arrow does. Order: the bolt is built, offered to other plugins as the bow's is (EntityShootBowEvent: a
	 * cancel ends it, before anything is paid), paid for, and added to the world.
	 */
	private void takeOver(ArmContext c, Player p, @Nullable ItemStack crossbow) {
		int now = now(c);
		Integer last = lastShot.put(p.getUniqueId(), now);
		if (last != null && last == now) return; // the other event of the same shot
		CrossbowBolt bolt = armed(c, p, crossbow);
		if (bolt == null || !bolt.loaded() || crossbow == null || p.getGameMode() == GameMode.SPECTATOR) return;
		int level = Math.max(1, c.level(p, FORM));
		World world = p.getWorld();
		Location eye = p.getEyeLocation();
		Location start = eye.clone().subtract(0, BOLT_DROP, 0);
		Vector dir = eye.getDirection();

		// 1. the bolt, built (nothing of it is in the world yet) and offered to other plugins: an anticheat or a region plugin may cancel it
		Arrow arrow = world.createEntity(start, Arrow.class);
		arrow.setShooter(p);
		arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		arrow.setDamage(settings.boltDamage().at(level));
		arrow.setCritical(false);
		arrow.setPersistent(false);
		arrow.setVelocity(dir.clone().multiply(settings.boltSpeed()));
		arrow.getPersistentDataContainer().set(MARK_KEY, PersistentDataType.INTEGER, CrossbowBolt.mark(CrossbowBolt.KIND_BOLT, level));
		// the flint and steel accessory (8.0.0-beta.2): the bolt leaves lit, and sets what it hurts alight (onBurn)
		if (c.accessoryOn(p, FlintAccessory.ID)) FlintAccessory.lightBolt(arrow, p, level);
		// the mark makes this module's own onShoot let it through
		EntityShootBowEvent shoot = new EntityShootBowEvent(p, crossbow, null, arrow, EquipmentSlot.HAND, 1.0F, false);
		Bukkit.getPluginManager().callEvent(shoot);
		if (shoot.isCancelled()) return; // nothing paid; the state is still loaded, and the tick loads the stack the game emptied

		// 2. one biomass, all or nothing: no biomass, no bolt (the crossbow stays loaded), and what little there is left goes into the melt
		long cost = c.config().costs().charge(c.config().costs().perBolt());
		ArmLedger.Spend paid = c.spend(p, cost, false);
		if (!paid.paid()) {
			p.sendActionBar(Component.translatable("morphreborn.arm.bolt.refused", "Not enough biomass for a bolt", NamedTextColor.RED));
			if (paid.after() > 0) c.spend(p, paid.after(), true);
			return;
		}

		// 3. the bolt joins the world, and the crossbow is empty
		Entity flying = shoot.getProjectile();
		world.addEntity(flying);
		if (!flying.isValid()) {
			// another plugin refused the arrow's addition (a launch event it cancelled): nothing flew, so the biomass is given back and the crossbow
			// stays loaded (the game's own emptying of the stack is put right by the next tick); the click costs nothing and starts no regrow
			if (paid.charged() > 0) c.gain(p, paid.charged());
			return;
		}
		int grow = growTicks(crossbow);
		bolt.fired(now, grow);
		setLoaded(p, false);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_CROSSBOW, level, 0, ThreadLocalRandom.current().nextInt(1 << 30), new int[] {flying.getEntityId()}, null, FX_BOLT),
				ArmProtocol.fxRadius(ArmProtocol.FX_CROSSBOW));
		regrowFx(c, p, level, bolt, now);
		if (c.config().report()) {
			c.report("crossbow: " + p.getName() + " at level " + level + " fired a bolt for " + number(settings.boltDamage().at(level)) + ", regrowing for " + grow + " ticks");
		}
	}

	/** The ticks a bolt takes to grow back: the game's charge time for this crossbow (Quick Charge if the item has it). */
	private int growTicks(@Nullable ItemStack crossbow) {
		int quick = crossbow == null ? 0 : crossbow.getEnchantmentLevel(Enchantment.QUICK_CHARGE);
		return CrossbowBolt.growTicks(settings.regrowSeconds(), quick);
	}

	// ------------------------------------------------------------------ Spine Burst

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		ItemStack held = p.getInventory().getItemInMainHand();
		CrossbowBolt bolt = armed(c, p, held);
		if (bolt == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		if (bursts.containsKey(p.getUniqueId())) return SpecialResult.refused(settings.name() + " is still going.");
		if (!bolt.loaded() || !isLoaded(held)) return SpecialResult.noTarget("Your bolt is still growing");
		int lvl = level(level);
		if (!charge.pay(costMilli(lvl), cooldownTicks(lvl))) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		// the bolt goes into the spines: the crossbow is empty at once, so no click can fire it as well
		int grow = growTicks(held);
		bolt.beginBurst(grow);
		setLoaded(p, false);
		int now = now(c);
		// the spines' arrows are made now, so the one FX_SPINES message can name them all; each is added to the world on its own tick (launchSpine)
		int[] at = CrossbowBolt.spineTicks(settings.spines(), settings.gapTicks());
		Arrow[] arrows = new Arrow[at.length];
		int[] ids = new int[at.length];
		Location eye = p.getEyeLocation();
		for (int i = 0; i < at.length; i++) {
			arrows[i] = p.getWorld().createEntity(eye.clone().subtract(0, BOLT_DROP, 0), Arrow.class);
			ids[i] = arrows[i].getEntityId();
		}
		Burst burst = new Burst(p, c.token(p), now, lvl, ThreadLocalRandom.current().nextInt(1 << 30), at, arrows, grow);
		bursts.put(p.getUniqueId(), burst);
		fx(c, p, lvl, settings.gapTicks(), burst.seed, FX_SPINES, ids);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " at level " + lvl + " fired " + settings.spines() + " spines for " + number(settings.spineDamage().at(lvl))
					+ " each, through up to " + settings.pierce() + " creatures; cost " + costMilli(lvl) + " milli");
		}
		stepBurst(c, burst, now); // the first spine leaves at once
		return SpecialResult.FIRED;
	}

	/** One tick of a burst: every spine that is due leaves; when the last has, the bolt starts to regrow. The arm gone ends it. */
	private void stepBurst(ArmContext c, Burst b, int now) {
		Player p = b.player;
		if (!c.stillArmed(p, b.token) || p.getWorld() != b.world) {
			endBurst(c, b, now, false);
			return;
		}
		while (b.next < b.at.length && now - b.start >= b.at[b.next]) {
			launchSpine(c, b);
			b.next++;
		}
		if (b.next >= b.at.length) endBurst(c, b, now, true);
	}

	/** The burst is over, the last spine gone (or the arm was): the bolt starts to grow back. Safe to call twice. */
	private void endBurst(ArmContext c, Burst b, int now, boolean finished) {
		if (!bursts.remove(b.player.getUniqueId(), b)) return;
		// whatever ended it (the arm gone included), the bolt the burst took grows back: the state is the player's, not the session's
		CrossbowBolt bolt = bolts.get(b.player.getUniqueId());
		if (bolt == null) return;
		bolt.endBurst(now, b.grow);
		if (b.player.isOnline() && bolt.phase() == CrossbowBolt.Phase.GROWING) regrowFx(c, b.player, b.level, bolt, now);
		if (c.config().report()) c.report(settings.name() + ": " + b.player.getName() + (finished ? " finished" : " stopped") + " after " + (now - b.start) + " ticks");
	}

	/** One spine leaves along the aim as it is now: it flies straight at the burst's speed and is gone at range. */
	private void launchSpine(ArmContext c, Burst b) {
		Player p = b.player;
		World world = p.getWorld();
		Location eye = p.getEyeLocation();
		Location start = eye.clone().subtract(0, BOLT_DROP, 0);
		Arrow spine = b.arrows[b.next];
		// made at the start of the burst at the eye as it was then: moved to where it is now (a no-op that returns false if the game won't move a
		// creature that isn't in the world yet, which leaves the spine at the burst's start, a block or so behind)
		try {
			spine.teleport(start);
		} catch (RuntimeException e) {
			// the spine leaves from where it was made
		}
		spine.setShooter(p);
		spine.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
		spine.setDamage(settings.spineDamage().at(b.level));
		spine.setCritical(false);
		spine.setPersistent(false);
		spine.setGravity(false);
		spine.setPierceLevel(settings.pierceLevel());
		spine.setVelocity(eye.getDirection().multiply(settings.spineSpeed()));
		spine.getPersistentDataContainer().set(MARK_KEY, PersistentDataType.INTEGER, CrossbowBolt.mark(CrossbowBolt.KIND_SPINE, b.level));
		// a protection plugin may refuse the launch; the mark makes this module's own handler let it through
		ProjectileLaunchEvent launch = new ProjectileLaunchEvent(spine);
		c.plugin().getServer().getPluginManager().callEvent(launch);
		if (launch.isCancelled()) return;
		world.addEntity(spine);
		if (!spine.isValid()) return;
		spines.add(new Spine(spine, now(c) + settings.spineLifeTicks()));
		float pitch = 1.3F + 0.15F * b.next;
		world.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 0.8F, pitch);
		world.playSound(eye, Sound.ENTITY_SKELETON_SHOOT, SoundCategory.PLAYERS, 0.5F, pitch);
	}

	@Override
	public void abort(Player p) {
		ArmContext c = ctx;
		Burst b = bursts.get(p.getUniqueId());
		if (c != null && b != null) endBurst(c, b, now(c), false);
	}

	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c == null) return;
		int now = now(c);
		for (Burst b : List.copyOf(bursts.values())) endBurst(c, b, now, false);
		for (Spine s : spines) s.arrow().remove();
		spines.clear();
		lastShot.clear();
	}

	// ------------------------------------------------------------------ events

	private final class Events implements Listener {
		/**
		 * The game's shot of a charged arm crossbow: its arrow is cancelled (no real arrow ever flies), and the arm's own bolt flies in its
		 * place. A shot another plugin already cancelled is left as it is: the crossbow is empty in the game's eyes, and the state, which is
		 * still loaded, loads it again within the tick.
		 */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onShoot(EntityShootBowEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof Player p)) return;
			ItemStack bow = event.getBow();
			if (!FORM.equals(formOf(bow)) || event.getProjectile().getPersistentDataContainer().has(MARK_KEY, PersistentDataType.INTEGER)) return;
			event.setCancelled(true);
			event.setConsumeItem(false); // no wear, no ammunition, whatever else looks at the event
			event.setConsumeArrow(false);
			takeOver(c, p, bow);
		}

		/**
		 * Behind it: an arrow the arm crossbow itself fired by a path the event above did not see. Only the arm's own shot counts, told by the
		 * weapon the arrow records (AbstractArrow.getWeapon, the stack the game fired it from): an off-hand bow or crossbow, another plugin's
		 * launchProjectile or any arrow a plugin spawns with the player as its shooter is left alone (2026-11-16: it was cancelled
		 * whenever the arm crossbow was merely in the main hand, and the ammunition it had consumed was lost).
		 */
		@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
		public void onLaunch(ProjectileLaunchEvent event) {
			ArmContext c = ctx;
			// an arrow of the game's, not a thrown trident (also an AbstractArrow) the off hand might throw
			if (c == null || !(event.getEntity() instanceof AbstractArrow arrow) || arrow instanceof org.bukkit.entity.Trident || arrow.getPersistentDataContainer().has(MARK_KEY, PersistentDataType.INTEGER)) return;
			if (!(arrow.getShooter() instanceof Player p)) return;
			ItemStack weapon = arrow.getWeapon();
			if (!FORM.equals(formOf(weapon))) return;
			event.setCancelled(true);
			takeOver(c, p, weapon);
		}

		/**
		 * A click with no whole bolt does nothing: the game would charge the crossbow from the arrows in the inventory. (A click on a block still
		 * uses the block: only the item's use is refused.) Whenever the held stack is this arm's crossbow, whatever state the session is in: while
		 * the arm is forming, quick-forming or reshaping it is in the hand unloaded, and a use then would start a real charge (its sound and pull
		 * animation); the same goes for a stray copy of the arm item.
		 */
		@EventHandler(priority = EventPriority.LOW)
		public void onUse(PlayerInteractEvent event) {
			ArmContext c = ctx;
			if (c == null || event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
			ItemStack item = event.getItem();
			if (!FORM.equals(formOf(item))) return;
			CrossbowBolt bolt = armed(c, event.getPlayer(), item);
			if (bolt == null || !bolt.loaded() || !isLoaded(item)) event.setUseItemInHand(Event.Result.DENY);
		}

		/** The game never loads the arm's crossbow: no real arrow is taken from an inventory for it. */
		@EventHandler(priority = EventPriority.LOW)
		public void onLoad(EntityLoadCrossbowEvent event) {
			if (!FORM.equals(formOf(event.getCrossbow()))) return;
			event.setCancelled(true);
			event.setConsumeItem(false);
		}

		/** A bolt or a spine is never picked up, by anyone. */
		@EventHandler(priority = EventPriority.LOWEST)
		public void onPickup(PlayerPickupArrowEvent event) {
			if (event.getArrow().getPersistentDataContainer().has(MARK_KEY, PersistentDataType.INTEGER)) event.setCancelled(true);
		}

		/**
		 * A creature the owner has no business hurting is passed through, a spine's hit lands in full whatever struck the creature just before
		 * it, and a block ends a spine and, soon, a bolt.
		 */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onHit(ProjectileHitEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof AbstractArrow arrow)) return;
			Integer mark = arrow.getPersistentDataContainer().get(MARK_KEY, PersistentDataType.INTEGER);
			if (mark == null) return;
			int kind = CrossbowBolt.kindOf(mark);
			Entity hit = event.getHitEntity();
			// a part of a creature (the Ender Dragon's body parts) stands for the creature: it is the one that is struck, and the one that is judged
			if (hit instanceof ComplexEntityPart part) hit = part.getParent();
			if (hit != null) {
				if (!(arrow.getShooter() instanceof Player owner)) return;
				boolean may = kind == CrossbowBolt.KIND_SPINE ? spineMay(c, owner, hit) : !hit.equals(owner) && !TendrilTargets.ownPet(owner, hit);
				if (!may) {
					event.setCancelled(true); // through it, as the tendril's strikes never touch what is the owner's
					return;
				}
				if (kind == CrossbowBolt.KIND_SPINE && hit instanceof LivingEntity living) living.setNoDamageTicks(0);
				return;
			}
			if (event.getHitBlock() == null) return;
			if (kind == CrossbowBolt.KIND_SPINE) arrow.remove();
			else removeLater(c, arrow);
		}

		/** The blow is the settings' damage at the tier the shot was made at (flat, no critical bonus). */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onDamage(EntityDamageByEntityEvent event) {
			if (!(event.getDamager() instanceof AbstractArrow arrow)) return;
			Integer mark = arrow.getPersistentDataContainer().get(MARK_KEY, PersistentDataType.INTEGER);
			if (mark == null) return;
			int kind = CrossbowBolt.kindOf(mark), level = CrossbowBolt.levelOf(mark);
			if (kind == CrossbowBolt.KIND_BOLT) event.setDamage(settings.boltDamage().at(level));
			else if (kind == CrossbowBolt.KIND_SPINE) event.setDamage(settings.spineDamage().at(level));
		}

		/** A lit bolt (fired with the flint and steel on) whose damage went through sets its target alight, as the bow's Fire tip does; spines never burn. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onBurn(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getDamager() instanceof AbstractArrow arrow) || !(event.getEntity() instanceof LivingEntity victim)) return;
			Integer mark = arrow.getPersistentDataContainer().get(MARK_KEY, PersistentDataType.INTEGER);
			if (mark == null || CrossbowBolt.kindOf(mark) != CrossbowBolt.KIND_BOLT || !(arrow.getShooter() instanceof Player owner)) return;
			FlintAccessory.burnHit(c, owner, victim, arrow, level(CrossbowBolt.levelOf(mark)), event.getFinalDamage());
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			UUID id = event.getPlayer().getUniqueId();
			ArmContext c = ctx;
			Burst b = bursts.get(id);
			if (c != null && b != null) endBurst(c, b, now(c), false);
			// a bolt that is whole has nothing to keep; one still growing is kept, so leaving and coming back is no free reload
			CrossbowBolt bolt = bolts.get(id);
			if (bolt != null && bolt.loaded()) bolts.remove(id);
			lastShot.remove(id);
		}
	}

	/**
	 * Whether a spine may strike {@code hit} (already resolved to the whole creature): TendrilTargets.canTarget (their pets, mounts, NPCs, and
	 * players unless arms.players are passed through), except that a creature carrying a rider is fair game as it is for a plain bolt (canTarget
	 * refuses "someone's mount, in use": a spider or chicken jockey, a ravager with a captain, any ridden mob, so a spine would pass through
	 * them while a bolt did not; review, 2026-11-16). The owner's own mount and riders, their pets, NPCs and protected players still aren't.
	 */
	private static boolean spineMay(ArmContext c, Player owner, Entity hit) {
		if (TendrilTargets.canTarget(c.plugin(), owner, hit, true, c.config().players())) return true;
		if (!(hit instanceof LivingEntity living) || hit.getPassengers().isEmpty()) return false; // refused for another reason
		if (hit.equals(owner) || hit.equals(owner.getVehicle()) || hit.getPassengers().contains(owner) || TendrilTargets.ownPet(owner, hit)
				|| living.isDead() || living.isMarkedInvulnerable() || hit.hasMetadata("NPC") || hit.getWorld() != owner.getWorld()) return false;
		if (hit instanceof Player player) return c.config().players() && TendrilTargets.playable(player);
		return hit instanceof Mob;
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

	// ------------------------------------------------------------------ helpers

	/** One FX_CROSSBOW message: {@code arg} says which (see the constants), {@code ids} may be null. */
	private void fx(ArmContext c, Player p, int level, int ticks, int seed, int arg, int @Nullable [] ids) {
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_CROSSBOW, level, Math.max(0, ticks), seed, ids, null, arg), ArmProtocol.fxRadius(ArmProtocol.FX_CROSSBOW));
	}

	/** The form id on an arm stack, null for anything else. */
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

	private static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}
}
