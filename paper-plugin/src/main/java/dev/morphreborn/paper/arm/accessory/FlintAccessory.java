package dev.morphreborn.paper.arm.accessory;

import com.destroystokyo.paper.event.block.TNTPrimeEvent;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.look.ShieldGrip;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.FlintRules;
import dev.morphreborn.paper.arm.rules.FlintSettings;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.arm.special.BowArm;
import dev.morphreborn.paper.arm.special.CrossbowArm;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The Tendril Flint and Steel (my design, 2026-11-16, 8.0.0-beta.2): a sparking tendril over the arm, beside any form or a bare
 * hand (several accessories may be on at once). While it is on:
 * <ul>
 *   <li><b>Melee hits light their target.</b> A hit the owner lands themself (a blow, a sweep, the spear's jab and charge, the mace's smash)
 *       sets the creature alight for {@code ignite-seconds} (3 / 4 / 5 by level). It is done as Fire Aspect is: only to a creature that can
 *       burn, isn't in water and isn't alight already, only when no plugin stops the fire (EntityCombustByEntityEvent), and only then is it
 *       billed, one ignition at {@code arm-cost.specials.flint-ignite}, all or nothing. A hit the damage events already refused (PvP off, a
 *       claim, a shield) lights nothing, and neither does a special's own damage, the blow that kills, or the owner's own pet. All of it is
 *       {@link Ignition}.</li>
 *   <li><b>Shots burn.</b> The bow's Fire tip is offered while it is on (BowTips), and the crossbow's bolts leave lit
 *       ({@link #lightBolt}): when one's damage goes through ({@link #burnHit}) its target is set alight for {@code bolt-seconds} by the
 *       crossbow's level, as the Fire tip is, and billed the same. The spines of Spine Burst are bone and don't burn. So does every arrow
 *       a player looses from any bow or crossbow, vanilla items included, and a thrown trident (2026-11-24): it leaves lit and burns what it
 *       hurts as Flame would, for {@code bolt-seconds} by the flint and steel's level at the launch, one ignition billed per creature actually
 *       set alight (a miss and an arrow into a creature that is already alight cost nothing). An arrow that is on fire itself (a Flame bow's)
 *       is the game's and lights its target free; a shot a shield turned round burns nobody.</li>
 *   <li><b>Lighting blocks.</b> With the arm in the main hand, a right-click on an unlit campfire or candle, a candle cake, TNT, or the face of
 *       a piece of obsidian (a portal frame) lights it as flint and steel does ({@code light-blocks}), for one ignition's price. Only then are the
 *       arm's own use of the click and of the item cancelled, so a click on anything else (a shovel's path, an axe's strip, the bow's draw) is
 *       exactly what it was. Not in Adventure or Spectator (as vanilla flint and steel isn't usable there). Other plugins are asked first
 *       (BlockPlaceEvent for build permission, BlockIgniteEvent, TNTPrimeEvent, and a click a claim already refused is left alone), after the
 *       owner's biomass is checked (nothing is asked of anyone for a player who cannot pay) and vanilla's spawn protection and the world border
 *       are honoured ({@link FlintRules#underSpawnProtection}).</li>
 * </ul>
 * It drains while it is on (survival.yml arm-cost.drain-per-minute, the manager's). Modded clients are told each ignition and lighting
 * with S_ARM_FX kind FX_ACCESSORY ({@link FlintRules}: 0x301 ignite, 0x302 light, 0x303 the switch-on spark); everyone sees the creature burn and hears flint and steel.
 * <p>
 * It puts itself out when its owner goes offline, dies or turns spectator; the manager does the rest (morphing, death, depletion, game mode,
 * teleport and world changes with inventory safety on).
 */
public final class FlintAccessory implements ArmAccessoryModule {
	public static final String ID = "flint";
	/** The arrow tag of a shot that left lit: FlintRules.stamp, who shot it and at what level (the plugin's namespace, like the arm's own marks). */
	private static final NamespacedKey FLAME_KEY = new NamespacedKey("morphreborn", "bolt_flame");

	private @Nullable ArmContext ctx;
	private FlintSettings settings = FlintSettings.DEFAULTS;
	/** The settings as last loaded, for {@link #burnHit} (static, called by the crossbow): the module is created once, so this is its {@link #settings}. */
	private static volatile FlintSettings live = FlintSettings.DEFAULTS;
	/** Who has it on (main thread only). */
	private final Map<UUID, State> states = new LinkedHashMap<>();
	private final Listener events = new Events();

	/** One flint and steel that is on. */
	private static final class State {
		final UUID id;
		final int level, since;

		State(Player p, int level, int since) {
			this.id = p.getUniqueId();
			this.level = level;
			this.since = since;
		}
	}

	public FlintAccessory() {}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		load(ctx.config().accessory(ID));
	}

	/** arms.accessories.flint as plain values; anything missing or unreadable keeps the bundled default. */
	@Override
	public void load(Map<String, Object> section) {
		settings = FlintSettings.parse(section);
		live = settings;
	}

	@Override
	public Listener listener() {
		return events;
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
		if (c == null) return "The Tendril Flint and Steel isn't ready yet.";
		if (states.containsKey(p.getUniqueId())) return "Your flint and steel is already on.";
		int lvl = Math.clamp(level, 1, 3);
		states.put(p.getUniqueId(), new State(p, lvl, c.tick()));
		// the striker's first strike for modded clients (FX_SPARK: no ids, no positions); they hold it until the tendril has grown out. It goes
		// out ahead of the manager's S_ARM, so the client takes nothing from the owner's state.
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_ACCESSORY, lvl, 0, ThreadLocalRandom.current().nextInt(), null, null, FlintRules.fxArg(FlintRules.FX_SPARK)),
				ArmProtocol.fxRadius(ArmProtocol.FX_ACCESSORY));
		return null;
	}

	@Override
	public void switchOff(Player p, int why) {
		states.remove(p.getUniqueId());
	}

	@Override
	public void switchOffAll() {
		states.clear();
	}

	/** A flint and steel whose owner is gone is put out; nothing else happens on a tick. */
	@Override
	public void tick() {
		if (states.isEmpty()) return;
		for (Iterator<State> it = states.values().iterator(); it.hasNext();) {
			Player p = Bukkit.getPlayer(it.next().id);
			if (p == null || !p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) it.remove();
		}
	}

	@Override
	public Lines describeLines(int level) {
		ArmContext c = ctx;
		long cost = c == null ? 0 : c.config().costs().special(Ignition.COST_KEY, level);
		return settings.lines(level, cost <= 0 ? "" : ArmItemSpecs.biomass(cost) + " biomass");
	}

	// ------------------------------------------------------------------ the crossbow's bolts

	/**
	 * A bolt leaves the crossbow lit: tagged, and drawn alight for plain and modded clients alike. It is setVisualFire and not setFireTicks on
	 * purpose (checked against 26.3's source, 2026-11-16): Entity.baseTick sets the shared on-fire flag (metadata index 0, bit 0) to
	 * {@code remainingFireTicks > 0 || hasVisualFire} on every server tick, so the flag reaches every client that tracks the arrow, and a client's
	 * Entity.isOnFire() reads exactly that flag; while the server's isOnFire() stays false (no fire ticks), so AbstractArrow's own "a burning arrow
	 * ignites what it hits for 5 s" never runs and the only fire is the one {@link #burnHit} sets and bills. A real setFireTicks would light the
	 * victim unpaid. Called by the crossbow when the flint and steel is on, and by {@link Events#onLaunch} for every other arrow and
	 * thrown trident a player looses. {@code level} is the flint and steel's, for the shots that read it back (the crossbow's bolts burn by its own level).
	 */
	public static void lightBolt(AbstractArrow arrow, Player owner, int level) {
		arrow.getPersistentDataContainer().set(FLAME_KEY, PersistentDataType.STRING, FlintRules.stamp(owner.getUniqueId(), level));
		arrow.setVisualFire(true);
	}

	/**
	 * A lit shot's damage went through on {@code victim}: it is set alight for {@code bolt-seconds} at {@code level}, if it is fair game for
	 * the owner's tendrils (as the bow's tips are) and the shot did not kill it. Nothing for a shot that was never lit for {@code owner}. Called
	 * by the crossbow, and by {@link Events#onShotHit} for every other lit shot.
	 *
	 * @param level the crossbow's level for its own bolts, the flint and steel's at the launch for any other shot
	 * @param finalDamage the damage event's final damage, worked out before it is applied
	 */
	public static void burnHit(ArmContext c, Player owner, LivingEntity victim, AbstractArrow arrow, int level, double finalDamage) {
		// a shot burns only for the player it was lit for: one a shield turned round has a new shooter and burns nobody (2026-11-24)
		if (!FlintRules.stampedBy(arrow.getPersistentDataContainer().get(FLAME_KEY, PersistentDataType.STRING), owner.getUniqueId())) return;
		if (victim.equals(owner) || victim.isDead() || !(finalDamage > 0) || FlintRules.lethal(victim.getHealth(), finalDamage)) return;
		// a bolt that was lit at the crossbow burns only while the owner's flint and steel still applies (2026-11-16): gone offline, dead, a
		// spectator, morphed (the manager puts the accessory out) or switched off mid-flight, nothing is lit and nothing is billed
		if (!owner.isOnline() || owner.isDead() || owner.getGameMode() == GameMode.SPECTATOR || !c.accessoryOn(owner, ID)) return;
		if (!TendrilTargets.canTarget(c.plugin(), owner, victim, true, c.config().players())) return;
		Ignition.ignite(c, owner, victim, arrow, live.boltTicks(level), level);
	}

	// ------------------------------------------------------------------ events

	private final class Events implements Listener {
		/** The owner's melee hit landed (it went through every other plugin's say): the creature is set alight. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onHit(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || states.isEmpty() || !(event.getDamager() instanceof Player p) || !(event.getEntity() instanceof LivingEntity victim) || victim == p) return;
			State s = states.get(p.getUniqueId());
			if (s == null) return;
			DamageCause cause = event.getCause();
			boolean attack = cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK;
			if (!FlintRules.meleeBlow(attack, event.getDamageSource().getDamageType().getKey().toString(), event.getDamageSource().getDirectEntity() == p)) return;
			// MONITOR runs before the damage is applied, so isDead() is never true for the killing hit: the blow that kills lights nothing
			double damage = event.getFinalDamage();
			if (!(damage > 0) || victim.isDead() || FlintRules.lethal(victim.getHealth(), damage)) return;
			if (c.plugin().arms().isSpecialHit(event)) return;
			// fair game for the owner's tendrils, as for the bow's tips and the crossbow's bolts (2026-11-16): arms.players off lights no player, and
			// the owner's pets and mounts, others' mounts, armour stands and NPCs are left alone (canTarget covers all of them)
			if (!TendrilTargets.canTarget(c.plugin(), p, victim, true, c.config().players())) return;
			try {
				Ignition.ignite(c, p, victim, p, settings.igniteTicks(s.level), s.level);
			} catch (Throwable t) {
				c.plugin().getLogger().log(Level.WARNING, "The flint and steel failed to light a creature for " + p.getName() + ".", t);
			}
		}

		/**
		 * An arrow or a thrown trident a player looses with the flint and steel on leaves lit (2026-11-24): from any bow or crossbow, vanilla items
		 * included, and the trident (the arm's throw too). ProjectileLaunchEvent is heard for all of them, whatever fired them (EntityShootBowEvent
		 * is not raised for a thrown trident, and a launch event is raised for every arrow of a Multishot). The arm's own bolts are left alone: the
		 * bow's burn by its Fire tip and the crossbow's are lit by its module. Nothing is billed here, only at a hit that sets something alight.
		 */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onLaunch(ProjectileLaunchEvent event) {
			if (states.isEmpty() || !(event.getEntity() instanceof AbstractArrow arrow) || !(arrow.getShooter() instanceof Player p)) return;
			State s = states.get(p.getUniqueId());
			var data = arrow.getPersistentDataContainer();
			boolean armBolt = data.has(BowArm.BOLT_KEY, PersistentDataType.BYTE) || data.has(CrossbowArm.MARK_KEY, PersistentDataType.INTEGER);
			if (s == null || !FlintRules.litAtLaunch(true, armBolt, data.has(FLAME_KEY, PersistentDataType.STRING))) return;
			lightBolt(arrow, p, s.level);
		}

		/**
		 * A lit shot's damage went through: its target is set alight (burnHit holds every rule). The crossbow arm's bolts are its own handler's, so a
		 * bolt never burns twice; an arrow that is on fire itself (Flame) is the game's, which lights the target free.
		 */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onShotHit(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getDamager() instanceof AbstractArrow arrow) || !(event.getEntity() instanceof LivingEntity victim) || !(arrow.getShooter() instanceof Player owner)) return;
			var data = arrow.getPersistentDataContainer();
			String stamp = data.get(FLAME_KEY, PersistentDataType.STRING);
			if (stamp == null || data.has(CrossbowArm.MARK_KEY, PersistentDataType.INTEGER) || !FlintRules.shotBurns(stamp, owner.getUniqueId(), arrow.getFireTicks())) return;
			try {
				burnHit(c, owner, victim, arrow, FlintRules.stampLevel(stamp), event.getFinalDamage());
			} catch (Throwable t) {
				c.plugin().getLogger().log(Level.WARNING, "The flint and steel failed to light a creature for " + owner.getName() + ".", t);
			}
		}

		/**
		 * A right-click on something it can light, with the arm in the main hand: lit as flint and steel lights it, and only then is the click's
		 * own use cancelled. A click a claim or another plugin already refused (the event is cancelled) is not touched.
		 */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onClick(PlayerInteractEvent event) {
			ArmContext c = ctx;
			Block block = event.getClickedBlock();
			if (c == null || block == null || states.isEmpty() || !settings.lightBlocks() || event.getHand() != EquipmentSlot.HAND
					|| event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
			Player p = event.getPlayer();
			State s = states.get(p.getUniqueId());
			if (s == null || !FlintRules.mayLightIn(p.getGameMode().name()) || !c.plugin().arms().isArm(event.getItem())) return;
			BlockData data = block.getBlockData();
			FlintRules.Target target = FlintRules.target(block.getType().getKey().toString(), data instanceof Lightable l && l.isLit(),
					data instanceof Waterlogged w && w.isWaterlogged());
			if (target == FlintRules.Target.NONE) return;
			try {
				if (!light(c, p, s, event, block, data, target)) return;
			} catch (Throwable t) {
				c.plugin().getLogger().log(Level.WARNING, "The flint and steel failed to light a block for " + p.getName() + ".", t);
				return;
			}
			event.setUseInteractedBlock(Event.Result.DENY);
			event.setUseItemInHand(Event.Result.DENY);
			ShieldGrip.mainHandUsed(p); // the click was the main hand's (flint), so it does not also raise the shield
		}
	}

	// ------------------------------------------------------------------ lighting blocks

	/** Whether vanilla would let this player build at the block: not within the spawn protection (ops are exempt) and inside the world border. */
	@SuppressWarnings("deprecation")
	private static boolean mayBuildAt(Player p, Block b) {
		World w = b.getWorld();
		if (!w.getWorldBorder().isInside(b.getLocation())) return false;
		World spawnWorld = Bukkit.getWorlds().get(0);
		if (w != spawnWorld) return true;
		Location spawn = w.getSpawnLocation();
		return !FlintRules.underSpawnProtection(true, !Bukkit.getOperators().isEmpty(), p.isOp(), Bukkit.getServer().getSpawnRadius(), b.getX() - spawn.getBlockX(),
				b.getZ() - spawn.getBlockZ());
	}

	/**
	 * Light what was clicked, after asking other plugins and paying. False when nothing was lit (nothing paid either): the click goes on as it was.
	 * TNTPrimeEvent is marked for removal in 26.3 but is the event protection plugins hear when TNT is primed, so it is still offered.
	 */
	@SuppressWarnings("removal")
	private boolean light(ArmContext c, Player p, State s, PlayerInteractEvent event, Block block, BlockData data, FlintRules.Target target) {
		Block at = block;
		BlockData fire = null;
		if (target == FlintRules.Target.PORTAL_FRAME) {
			// fire on the face that was clicked, where it could burn at all: the floor of a frame, not the air beside a wall
			at = block.getRelative(event.getBlockFace());
			fire = Material.FIRE.createBlockData();
			if (!at.getType().isAir() || !fire.isSupported(at)) return false;
		} else if (target == FlintRules.Target.CANDLE_CAKE || target == FlintRules.Target.CAMPFIRE || target == FlintRules.Target.CANDLE) {
			if (!(data instanceof Lightable)) return false;
		}
		if (target == FlintRules.Target.TNT && !Boolean.TRUE.equals(block.getWorld().getGameRuleValue(GameRule.TNT_EXPLODES))) return false; // vanilla's TntBlock.prime refuses too
		// can the owner pay at all? Asked before any other plugin is, with nothing billed, so a player short of biomass sends no placement events (2026-11-16)
		long cost = c.config().costs().special(Ignition.COST_KEY, s.level);
		if (!c.canAfford(p, cost)) {
			p.sendActionBar(Component.text("Not enough biomass to light it", NamedTextColor.RED));
			return false;
		}
		// vanilla's spawn protection and the world border sit ahead of the use of an item in the game and are not PlayerInteractEvent's to hear
		if (!mayBuildAt(p, block) || !mayBuildAt(p, at)) return false;
		// build permission (2026-11-16): a lit block, a primed TNT and a placed fire are changes a player's building would make, so the plugins that guard
		// building are asked as they would be (BlockPlaceEvent: not applied, the block as it is now against the clicked one) before the fire event, and a
		// cancel or "can't build" is honoured. The game itself puts none of this to PlayerInteractEvent's listeners first.
		BlockPlaceEvent place = new BlockPlaceEvent(at, at.getState(), block, event.getItem() == null ? new ItemStack(Material.FLINT_AND_STEEL) : event.getItem(), p, true,
				EquipmentSlot.HAND);
		Bukkit.getPluginManager().callEvent(place);
		if (place.isCancelled() || !place.canBuild()) return false;
		BlockIgniteEvent ignite = new BlockIgniteEvent(at, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, p);
		Bukkit.getPluginManager().callEvent(ignite);
		if (ignite.isCancelled()) return false;
		if (target == FlintRules.Target.TNT) {
			TNTPrimeEvent prime = new TNTPrimeEvent(block, TNTPrimeEvent.PrimeReason.ITEM, p);
			Bukkit.getPluginManager().callEvent(prime);
			if (prime.isCancelled()) return false;
		}
		ArmLedger.Spend paid = c.spend(p, cost, false);
		if (!paid.paid()) {
			p.sendActionBar(Component.text("Not enough biomass to light it", NamedTextColor.RED));
			return false;
		}
		switch (target) {
			case TNT -> {
				block.setType(Material.AIR);
				block.getWorld().spawn(block.getLocation().add(0.5, 0, 0.5), TNTPrimed.class, tnt -> tnt.setSource(p));
				block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_TNT_PRIMED, SoundCategory.BLOCKS, 1.0F, 1.0F);
			}
			case PORTAL_FRAME -> at.setBlockData(fire, true);
			default -> {
				Lightable lit = (Lightable) data;
				lit.setLit(true);
				block.setBlockData(lit);
			}
		}
		Location where = at.getLocation().add(0.5, 0.5, 0.5);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		where.getWorld().playSound(where, Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 1.0F, rnd.nextFloat() * 0.4F + 0.8F);
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_ACCESSORY, s.level, 0, rnd.nextInt(), null, new long[] {new Pos(at.getX(), at.getY(), at.getZ()).asLong()},
				FlintRules.fxArg(FlintRules.FX_LIGHT)), ArmProtocol.fxRadius(ArmProtocol.FX_ACCESSORY));
		return true;
	}
}
