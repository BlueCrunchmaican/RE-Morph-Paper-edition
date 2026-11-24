package dev.morphreborn.paper.arm.accessory;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.look.Bedrock;
import dev.morphreborn.paper.arm.look.ShieldGrip;
import dev.morphreborn.paper.arm.look.ShieldLook;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.ShieldRules;
import dev.morphreborn.paper.arm.rules.ShieldSettings;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.arm.special.ArmTouch;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The Tendril Shield (my design, 2026-11-16, 8.0.0-beta.2): an accessory beside any form (several accessories may be on at
 * once). There is no real off-hand item (an engineering decision: vanilla blocks off-hand use whenever the main hand's item has
 * a use action of its own, which most of the tool forms have, and a real shield would need a second home slot with its own stash and
 * guards). <b>Right-click raises it</b> (decided 2026-11-17), with every form: held while the use key is held and lowered on release, and it
 * holds after {@code raise-delay-ticks}. The main hand comes first, as it does for a vanilla shield: a click the main hand's item uses (an arm
 * form's bow, crossbow, trident, spear or whip, food, a block to place, a door, a villager, flint lighting something) is that item's, and only a click
 * it did not use raises the shield. How the click is read depends on the owner ({@link ShieldRules.Control}):
 * <ul>
 *   <li><b>the mod</b> (at the new forms' version) knows the key and what the main hand did, and tells the server with C_ARM
 *       ACTION_ACCESSORY_HOLD ({@link #hold}); its game does not use the off hand at all while the shield is on;</li>
 *   <li><b>a game without the mod</b> is shown a vanilla shield in its own off hand by packet ({@link ShieldGrip}, {@code ShieldHandView}), so the game
 *       raises it natively on a click the main hand did not use and the server reads the use packet and the release;</li>
 *   <li><b>sneak</b> stays for the owners with no way to read a click: no PacketEvents, a Bedrock player, a game with no off hand (before 1.9), creative mode.</li>
 * </ul>
 * <b>The off hand is the shield's while it is on:</b> the real item there is never used (every use, placement, bucket, meal and shot from the off
 * hand is cancelled, for every kind of owner) and never touched, moved or stashed. Once raised it holds after {@code raise-delay-ticks}:
 * <ul>
 *   <li><b>Projectiles from the front</b> (arrows, spectral arrows, tridents, fireballs, skulls, wind charges) within {@code cone-degrees}
 *       of where the owner looks are stopped in the air and <b>sent back at the shooter</b>: aimed at them (with the arrow's own fall
 *       worked out), at the speed they came with (kept between the settings' least and greatest), the owner as their new shooter, so the
 *       damage is the owner's and every PvP rule and protection plugin judges it as the owner's shot. The tag on the sent-back projectile
 *       makes it pass through its owner on the way out, and one that has been sent back {@link ShieldRules#MAX_REFLECTS} times (counted on the
 *       projectile itself for its whole life) is taken instead. A player's own trident is never turned or destroyed (it is bounced off and
 *       stays the thrower's), and a player's arrow that is taken is dropped as the item it is, for its shooter ({@link #absorb}). A projectile that carries one of this plugin's own marks (the arm's bolts and thrown trident) is not reflected, only cut
 *       like a blow, so the other forms' bookkeeping never sees a shooter it did not expect.</li>
 *   <li><b>PvP rules for what is sent back.</b> A shooter the owner may not hurt (a player in a world with PvP off or on the owner's team
 *       with friendly fire off, a creature in someone's claim, any player when {@code arms.players} is off) is not aimed at: the projectile is
 *       taken (removed) instead, still a block. A shooter with no creature to aim at (a dispenser, one that is gone) gets it back along
 *       its way.</li>
 *   <li><b>Frontal blows</b> (a creature's or player's melee, a sweep, and any projectile that was not sent back) are cut by the level's
 *       share (0.5 / 0.6 / 0.7 of the damage).</li>
 *   <li>Each block bills {@code arm-cost.specials.shield-block}, partial like a hit (the block that empties the arm still holds). A block
 *       that can't be paid at all (no biomass) doesn't hold.</li>
 * </ul>
 * <b>Shown to others.</b> Modded clients at the new forms' version draw the shield themselves from S_ARM ({@link #arg}: 1 raised, 0 down)
 * and are sent S_ARM_FX kind FX_ACCESSORY for each block; everyone else is shown a vanilla shield in the owner's off hand, held up while it
 * is raised, by the packet rewrite of {@code ArmEquipmentView} (the states are {@link ShieldLook}), plus a sound and dust. The real off-hand
 * item is never read for its type, changed, stashed or guarded.
 * <p>
 * The shield puts itself out when its owner goes offline, dies or turns spectator (the manager polls {@link #isOn}); the manager puts it
 * out for death, quitting, morphing, depletion, game mode, teleport and world changes with inventory safety on.
 */
public final class ShieldAccessory implements ArmAccessoryModule {
	public static final String ID = "shield";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String BLOCK_COST = "shield-block";

	// FX_ACCESSORY moves (ShieldRules.fxArg: the arg is fxArg(move), the accessory's place in bits 8-11 and the move in bits 0-7). Never renumber.
	/**
	 * A frontal melee blow (or a projectile that was not sent back) was cut: level = the shield's level, ticks 0, ids = [the attacker, or the
	 * projectile], positions = [the owner's own block]. The client flashes the shield towards the attacker.
	 */
	public static final int FX_BLOCK = 1;
	/**
	 * A projectile was sent back: level = the shield's level, ticks = 0, ids = [the projectile's entity id, then the shooter's when it is a
	 * creature], positions = [the block the projectile was in]. The projectile keeps flying (the server aimed it), so a client draws the flash
	 * at the shield and the tendril catching and throwing it, and follows the entity.
	 */
	public static final int FX_REFLECT = 2;
	/**
	 * A projectile was stopped (the shooter may not be hurt by the owner, it was sent back too often, or it is a player's trident): level =
	 * the shield's level, ticks 0, ids = [the projectile's entity id], positions = [the block it was in]. The projectile is removed this tick,
	 * except a player's trident, which is bounced off and stays (the client may still find the entity, and must not rely on it; see absorb).
	 */
	public static final int FX_ABSORB = 3;

	/** How long a sent-back projectile passes through its new owner (ticks): longer than it needs to leave the hitbox at its slowest. */
	private static final int PASS_TICKS = 20;
	/**
	 * How many times a projectile has been sent back, in its own persistent data, for its whole life (review of 2026-11-16: the count
	 * used to live in the short {@link #sent} window, so two shields far apart could trade one forever). It is in this plugin's namespace
	 * like the arm's marks, so {@link #marked} must leave it out.
	 */
	private static final NamespacedKey REFLECTS = MorphRebornPlugin.key("shield_reflects");
	/**
	 * Who shot the projectile before any shield turned it (the second review of 2026-11-16): a player's UUID, or "" when it was not a player's.
	 * Taking it at the limit drops the arrow for that player, not for whichever shield owner held it last. Also in this plugin's namespace,
	 * so {@link #marked} leaves it out too.
	 */
	private static final NamespacedKey ORIGIN = MorphRebornPlugin.key("shield_origin");
	/** Dust at the shield for viewers whose game doesn't draw it. */
	private static final Particle.DustOptions SPARK = new Particle.DustOptions(VanillaEffects.ARM_RED, 0.8F);

	private @Nullable ArmContext ctx;
	private ShieldSettings settings = ShieldSettings.DEFAULTS;
	/** Who has it on (main thread only). */
	private final Map<UUID, State> states = new LinkedHashMap<>();
	/** Projectiles sent back, by entity id (main thread only): their new owner passes them for a while (the count is the projectile's own, {@link #REFLECTS}). */
	private final Map<Integer, Sent> sent = new HashMap<>();
	private final Listener events = new Events();

	/** One shield that is on. */
	private static final class State {
		final UUID id;
		final int entityId, level;
		boolean raised;
		/** The tick the raise (or the lowering) began. */
		int since;
		/** Whether this owner's own game is being shown the off-hand shield (the control is GHOST). */
		boolean ghost;
		/** The mod's key: held as last told, the tick it was first told held (a repeat keeps it), the tick of the last 1. */
		boolean keyDown;
		int keyFrom = -1, keyAt;

		State(Player p, int level, int now) {
			this.id = p.getUniqueId();
			this.entityId = p.getEntityId();
			this.level = level;
			this.since = now;
		}
	}

	/** A projectile this shield sent back. */
	private static final class Sent {
		final UUID owner;
		final int until;

		Sent(UUID owner, int until) {
			this.owner = owner;
			this.until = until;
		}
	}

	public ShieldAccessory() {}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		load(ctx.config().accessory(ID));
	}

	/** arms.accessories.shield as plain values; anything missing or unreadable keeps the bundled default. */
	@Override
	public void load(Map<String, Object> section) {
		settings = ShieldSettings.parse(section);
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
		if (c == null) return "The Tendril Shield isn't ready yet.";
		if (states.containsKey(p.getUniqueId())) return "Your shield is already on.";
		State s = new State(p, Math.clamp(level, 1, 3), c.tick());
		states.put(s.id, s);
		ShieldGrip.owner(s.entityId, true);
		// the shield has priority over the off hand: a meal, a drink or a draw already going on there stops (the item itself is left alone)
		if (p.isHandRaised() && p.getHandRaised() == EquipmentSlot.OFF_HAND) p.clearActiveItem();
		ShieldLook.set(p, ShieldLook.HELD);
		return null;
	}

	/** S_ARM's accessory argument for the shield: 1 while it is raised (the use key, or sneak for an owner with no other way, is down), 0 down or off. */
	@Override
	public int arg(Player p) {
		State s = states.get(p.getUniqueId());
		return s != null && s.raised ? 1 : 0;
	}

	@Override
	public void switchOff(Player p, int why) {
		State s = states.remove(p.getUniqueId());
		if (s != null) lookOff(s, p);
	}

	@Override
	public void switchOffAll() {
		for (Iterator<State> it = states.values().iterator(); it.hasNext();) {
			State s = it.next();
			it.remove();
			lookOff(s, Bukkit.getPlayer(s.id));
		}
		sent.clear();
	}

	/**
	 * Clears what others are shown, by the entity id the state was made with: a respawn gives the player a new id, and clearing by the
	 * player's current one would leave the old id in {@link ShieldLook} for good (then {@link ShieldLook#any} stays true and the rewriter
	 * parses every metadata packet). A player still on that id is also sent the lowered look; any other is only forgotten.
	 */
	private static void lookOff(State s, @Nullable Player p) {
		if (p != null && p.getEntityId() == s.entityId) {
			ShieldLook.set(p, ShieldLook.NONE);
			ShieldGrip.setGhost(p, false); // their own off hand is the real item again
		} else {
			ShieldLook.forget(s.entityId);
		}
		ShieldGrip.forget(s.entityId);
	}

	// ------------------------------------------------------------------ the tick

	/** The owner's control, from who they are right now (see {@link ShieldRules#control}). */
	private static ShieldRules.Control control(Player p, ArmContext c) {
		return ShieldRules.control(c.drawsNewForms(p), ShieldGrip.available(p), Bedrock.is(p), p.getGameMode() == GameMode.CREATIVE);
	}

	/**
	 * The use key (or sneak, for an owner with no other way) raises it and letting go lowers it, noticed here once a tick; a shield whose owner is
	 * gone is put out. The owner's control is read again every tick, so the off-hand shield a game without the mod is shown comes and goes with it.
	 */
	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null) return;
		int now = c.tick();
		ShieldGrip.clock(now);
		if (!sent.isEmpty()) sent.values().removeIf(s -> s.until <= now);
		if (states.isEmpty()) return;
		for (Iterator<State> it = states.values().iterator(); it.hasNext();) {
			State s = it.next();
			Player p = Bukkit.getPlayer(s.id);
			// a changed entity id is a respawn the death hook did not get to first: the old state goes, by its own id
			if (p == null || !p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getEntityId() != s.entityId) {
				it.remove();
				lookOff(s, p);
				continue;
			}
			ShieldRules.Control control = control(p, c);
			boolean ghost = control == ShieldRules.Control.GHOST;
			if (ghost != s.ghost) {
				// the state follows the grip's own even when showing it fails, so one owner's failure is not retried every tick or allowed to stop the others
				try {
					ShieldGrip.setGhost(p, ghost);
				} catch (Throwable t) {
					c.plugin().getLogger().log(Level.WARNING, "The shield's off-hand look failed for " + p.getName() + ".", t);
				}
				s.ghost = ghost;
			}
			// a click the main hand's own plugin handler used (flint, a carried block, milking, a mount) is not the shield's, whoever heard it first
			boolean ignored = ShieldRules.sameClick(s.keyFrom, ShieldGrip.usedAt(s.entityId));
			boolean want = ShieldRules.wantsRaise(control, p.isSneaking(), ShieldRules.keyHeld(s.keyDown, s.keyAt, now, ignored),
					ShieldRules.ghostHeld(ShieldGrip.pressedAt(s.entityId), ShieldGrip.usedAt(s.entityId), now));
			if (want == s.raised) continue;
			s.raised = want;
			s.since = now;
			try {
				ShieldLook.set(p, want ? ShieldLook.RAISED : ShieldLook.HELD);
				c.accessoryChanged(p);
			} catch (Throwable t) {
				c.plugin().getLogger().log(Level.WARNING, "The shield failed to show for " + p.getName() + ".", t);
			}
		}
	}

	/**
	 * The mod's use key goes down on a click the main hand did not use (the client works that out, with vanilla's own rules) or is let go
	 * (C_ARM ACTION_ACCESSORY_HOLD; also repeated about every 10 seconds while held, which keeps it believed: {@link ShieldRules#HOLD_TIMEOUT_TICKS}).
	 * It only records: {@link #tick} raises and lowers, so no number of calls in a tick makes more than one change.
	 */
	@Override
	public void hold(Player p, boolean held) {
		ArmContext c = ctx;
		State s = states.get(p.getUniqueId());
		if (c == null || s == null) return;
		if (!held) {
			s.keyDown = false;
			s.keyFrom = -1;
			return;
		}
		if (ShieldRules.newPress(s.keyDown, s.keyAt, c.tick())) s.keyFrom = c.tick();
		s.keyDown = true;
		s.keyAt = c.tick();
	}

	@Override
	public Lines describeLines(int level) {
		ArmContext c = ctx;
		long cost = c == null ? 0 : c.config().costs().special(BLOCK_COST, level);
		return settings.lines(level, cost <= 0 ? "" : ArmItemSpecs.biomass(cost) + " biomass");
	}

	// ------------------------------------------------------------------ blocking

	/** The shield is up and has held long enough, for a player who has it on. */
	private @Nullable State holding(Player owner, ArmContext c) {
		State s = states.get(owner.getUniqueId());
		if (s == null || !ShieldRules.holds(s.raised, c.tick() - s.since, settings.raiseDelayTicks())) return null;
		// a real shield already up in the owner's hand blocks by the game's own rules; the tendril one adds nothing to it
		return owner.isBlocking() ? null : s;
	}

	private boolean frontal(Player owner, Location source) {
		Location at = owner.getLocation();
		return ShieldRules.frontal(at.getYaw(), source.getX() - at.getX(), source.getZ() - at.getZ(), settings.coneDegrees());
	}

	/** One block's price, partial like a hit. False when nothing at all could be paid (the shield does not hold). */
	private boolean pay(ArmContext c, Player owner, State s) {
		long milli = c.config().costs().special(BLOCK_COST, s.level);
		ArmLedger.Spend spend = c.spend(owner, milli, true);
		return spend.charged() > 0 || spend.paid();
	}

	/**
	 * A projectile with one of this plugin's own marks: the arm's own bolts and thrown trident, whose modules keep track of who shot them.
	 * The shield's own count of sends ({@link #REFLECTS}) is not one: a projectile that has been sent back once is still the shield's.
	 */
	private static boolean marked(Projectile projectile) {
		String ours = REFLECTS.getNamespace();
		for (NamespacedKey key : projectile.getPersistentDataContainer().getKeys()) {
			if (key.getNamespace().equals(ours) && !key.equals(REFLECTS) && !key.equals(ORIGIN)) return true;
		}
		return false;
	}

	/** How many times this projectile has been sent back, over its whole life (any shield). */
	private static int reflects(Projectile projectile) {
		Integer n = projectile.getPersistentDataContainer().get(REFLECTS, PersistentDataType.INTEGER);
		return n == null ? 0 : n;
	}

	/**
	 * A trident a player threw: it is their real item (enchantments and all), and Loyalty would carry it to whoever owns it, so the shield
	 * never takes it, never turns it and never makes its owner anyone else ({@link #bounce}). Other tridents (a drowned's) are turned like
	 * an arrow, with Loyalty put out and no pickup.
	 * <p>
	 * A thrower that cannot be found (a player who left while it was in the air: getShooter is null) is still a player's if the trident can be
	 * picked up: a drowned's never can (DISALLOWED), so only that one is a mob's (the second review of 2026-11-16).
	 */
	private static boolean playersTrident(Projectile projectile) {
		if (!(projectile instanceof Trident trident)) return false;
		ProjectileSource shooter = trident.getShooter();
		if (shooter == null) return trident.getPickupRule() != AbstractArrow.PickupRule.DISALLOWED;
		return shooter instanceof Player;
	}

	/** Whether the owner may send a projectile at this shooter: PvP, teams, claims, and arms.players (ArmTouch, TendrilTargets). */
	private boolean mayHurt(ArmContext c, Player owner, @Nullable ProjectileSource shooter) {
		if (shooter instanceof Player p) {
			return p != owner && TendrilTargets.canTarget(c.plugin(), owner, p, true, c.config().players()) && ArmTouch.mayAffect(c, owner, p);
		}
		if (shooter instanceof LivingEntity living) return !living.isDead() && ArmTouch.mayAffect(c, owner, living);
		return true;
	}

	/**
	 * Where a shooter is aimed at: the middle of the body of a creature, the middle of a dispenser's block, else nothing. A shooter in
	 * another world (an arrow that came through a portal) has coordinates that mean nothing here, so it is nothing too, and the projectile
	 * goes back the way it came.
	 */
	private static @Nullable Vector aimPoint(@Nullable ProjectileSource shooter, World world) {
		if (shooter instanceof LivingEntity living && !living.isDead() && living.isValid() && living.getWorld().equals(world)) {
			BoundingBox box = living.getBoundingBox();
			return new Vector(box.getCenterX(), box.getCenterY(), box.getCenterZ());
		}
		if (shooter instanceof BlockProjectileSource block && block.getBlock().getWorld().equals(world)) {
			return block.getBlock().getLocation().toVector().add(new Vector(0.5, 0.5, 0.5));
		}
		return null;
	}

	private final class Events implements Listener {
		/**
		 * A projectile reaches a raised shield from the front: sent back (cancelling the hit lets it pass through the owner, then it is
		 * turned), or taken when the shooter may not be hurt. A projectile that this shield already sent back passes through its owner.
		 */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onHit(ProjectileHitEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getHitEntity() instanceof Player owner)) return;
			Projectile projectile = event.getEntity();
			Sent was = sent.get(projectile.getEntityId());
			if (was != null && was.owner.equals(owner.getUniqueId())) {
				event.setCancelled(true); // on its way out through the one who sent it
				return;
			}
			if (states.isEmpty()) return;
			State s = holding(owner, c);
			if (s == null || !settings.reflectProjectiles()) return; // the damage event cuts it instead
			if (ShieldRules.kind(projectile.getType().getKey().toString()) == ShieldRules.Kind.OTHER) return;
			if (marked(projectile) || owner.equals(projectile.getShooter())) return;
			if (projectile instanceof AbstractArrow arrow && arrow.isInBlock()) return;
			Location at = projectile.getLocation();
			if (!frontal(owner, at) || !pay(c, owner, s)) return;
			// AbstractArrow.stepMoveAndHit goes round again from the same spot for as long as a piercing arrow's hit was not deflected, and a
			// cancelled hit is not remembered as pierced: it would find the owner again for ever. The piercing is put out first (a turned arrow
			// loses it; a taken one is gone).
			if (projectile instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0) arrow.setPierceLevel(0);
			event.setCancelled(true);
			try {
				boolean back = reflects(projectile) < ShieldRules.MAX_REFLECTS && !playersTrident(projectile) && mayHurt(c, owner, projectile.getShooter());
				if (back && sendBack(c, owner, s, projectile)) return;
				absorb(c, owner, s, projectile);
			} catch (Throwable t) {
				c.plugin().getLogger().log(Level.WARNING, "The shield failed to turn a projectile for " + owner.getName() + ".", t);
			}
		}

		/** A frontal blow (melee, or a projectile that was not sent back) is cut by the level's share, after it is paid. */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onDamage(EntityDamageByEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || states.isEmpty() || !(event.getEntity() instanceof Player owner)) return;
			Entity damager = event.getDamager();
			DamageCause cause = event.getCause();
			boolean melee = (cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK) && damager instanceof LivingEntity;
			if (damager == owner || !(melee || damager instanceof Projectile) || event.getFinalDamage() <= 0) return;
			State s = holding(owner, c);
			if (s == null || !frontal(owner, damager.getLocation()) || !pay(c, owner, s)) return;
			event.setDamage(ShieldRules.cut(event.getDamage(), settings.reduction().at(s.level)));
			Location at = owner.getLocation();
			effects(c, owner, s, FX_BLOCK, new int[] {damager.getEntityId()}, at.getBlock());
		}

		// ---- the off hand is the shield's while it is on (decided 2026-11-17): the real item there is never used, whoever is playing

		private boolean locked(Player p) {
			return !states.isEmpty() && states.containsKey(p.getUniqueId());
		}

		/** Any use of the off hand: a block placed or used with it, a bow drawn, food started, a bucket, a throw. Cancelled first, so no later handler works on it. */
		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandUse(PlayerInteractEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && locked(event.getPlayer())) event.setCancelled(true);
		}

		/** The off hand on a creature or a frame (also the at-entity click, which is a subclass). */
		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandEntity(PlayerInteractEntityEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && locked(event.getPlayer())) event.setCancelled(true);
		}

		/** The rest, for uses that did not start from a click this tick (a meal, a bucket or a shot begun before the shield came on, another plugin's call). */
		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandPlace(BlockPlaceEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && locked(event.getPlayer())) event.setCancelled(true);
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandBucket(PlayerBucketEmptyEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && locked(event.getPlayer())) event.setCancelled(true);
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandFill(PlayerBucketFillEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && locked(event.getPlayer())) event.setCancelled(true);
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandConsume(PlayerItemConsumeEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && locked(event.getPlayer())) event.setCancelled(true);
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onOffHandShot(EntityShootBowEvent event) {
			if (event.getHand() == EquipmentSlot.OFF_HAND && event.getEntity() instanceof Player p && locked(p)) event.setCancelled(true);
		}

		// ---- signs that a game without the mod is no longer holding the key. It sends the use packet once and the release only from the
		// game's own key handling, which does not run behind a screen and does not run for the new player of a world change, so these end the press

		/** A screen opened (a chest, a trader, a crafting table): the game lets go of the keys when one opens, and says nothing until it closes. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onScreenOpen(InventoryOpenEvent event) {
			if (event.getPlayer() instanceof Player p && locked(p)) ShieldGrip.release(p.getEntityId());
		}

		/** A click in any window, the player's own inventory included (opening that one sends nothing, its first click does). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onScreenClick(InventoryClickEvent event) {
			if (event.getWhoClicked() instanceof Player p && locked(p)) ShieldGrip.release(p.getEntityId());
		}

		/** A window closed: whatever the key did behind it was never sent, so the press is over (a key still held starts a new one with its next click). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onScreenClose(InventoryCloseEvent event) {
			if (event.getPlayer() instanceof Player p && locked(p)) ShieldGrip.release(p.getEntityId());
		}

		/** A new world builds a new player in the game, which is not using anything and sends no release. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onWorldChange(PlayerChangedWorldEvent event) {
			Player p = event.getPlayer();
			if (locked(p)) ShieldGrip.release(p.getEntityId());
		}

		/** A viewer whose game starts tracking a shield's owner is sent what vanilla leaves out of a packet it sends by itself (the raised flags). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onTrack(PlayerTrackEntityEvent event) {
			ArmContext c = ctx;
			if (c == null || states.isEmpty() || !(event.getEntity() instanceof Player owner) || !states.containsKey(owner.getUniqueId())) return;
			Player viewer = event.getPlayer();
			// a tick later, once the pairing is done (the equipment is sent with it, and the rewriter makes it the shield)
			Bukkit.getScheduler().runTask(c.plugin(), () -> {
				if (viewer.isOnline() && owner.isOnline() && states.containsKey(owner.getUniqueId()) && owner.getTrackedBy().contains(viewer)) ShieldLook.tracked(viewer, owner);
			});
		}
	}

	// ------------------------------------------------------------------ what a block does

	/**
	 * Turn the projectile round: aimed at the shooter (or back along its way, when there is nothing to aim at), the owner its shooter.
	 * False when it couldn't be aimed at all (it was standing still), which absorbs it instead.
	 * <p>
	 * What it carries is left as it was found. {@code AbstractArrow.setOwner} (which {@code setShooter} calls) turns a DISALLOWED pickup into
	 * ALLOWED for a player, so a skeleton's or a drowned's arrow would become a free item for the shield's owner: the pickup is put back
	 * after it. A player's arrow stays ALLOWED (an arrow item that lands anywhere is anyone's to pick up in the game, and one that hits
	 * its shooter is used up, as a hit always uses it). A drowned's trident has Loyalty put out, so it never flies to its new owner.
	 * A player's own trident never gets here ({@link #playersTrident}).
	 * <p>
	 * An arrow's velocity is set for the end of this tick, not for its first move (see {@link ShieldRules#launch}). A hurting projectile
	 * (a fireball, a skull, a wind charge) is given the velocity only: Paper's {@code Fireball.setDirection} would put it back to
	 * {@code direction.normalize() * accelerationPower} (0.1 for a fireball, 0 for a wind charge, which would hang in the owner), and the
	 * projectile accelerates along its own movement anyway ({@code AbstractHurtingProjectile.applyInertia}).
	 */
	private boolean sendBack(ArmContext c, Player owner, State s, Projectile projectile) {
		ShieldRules.Kind kind = ShieldRules.kind(projectile.getType().getKey().toString());
		Vector v = projectile.getVelocity();
		double speed = ShieldRules.reflectSpeed(v.length(), settings.reflectMinSpeed(), settings.reflectMaxSpeed());
		ProjectileSource shooter = projectile.getShooter();
		if (!projectile.getPersistentDataContainer().has(ORIGIN, PersistentDataType.STRING)) {
			projectile.getPersistentDataContainer().set(ORIGIN, PersistentDataType.STRING, shooter instanceof Player p ? p.getUniqueId().toString() : "");
		}
		Vector target = aimPoint(shooter, owner.getWorld());
		Location from = projectile.getLocation();
		boolean falls = kind == ShieldRules.Kind.ARROW && projectile.hasGravity();
		double gravity = falls ? ShieldRules.ARROW_GRAVITY : 0;
		double[] aim = null;
		if (target != null) {
			aim = ShieldRules.aim(from.getX(), from.getY(), from.getZ(), target.getX(), target.getY(), target.getZ(), speed, gravity, ShieldRules.ARROW_DRAG);
		}
		if (aim == null) aim = ShieldRules.reverse(v.getX(), v.getY(), v.getZ(), speed);
		if (aim == null) return false;
		if (kind == ShieldRules.Kind.ARROW) aim = ShieldRules.launch(aim[0], aim[1], aim[2], gravity, ShieldRules.ARROW_DRAG);
		AbstractArrow.PickupRule pickup = projectile instanceof AbstractArrow arrow ? arrow.getPickupRule() : null;
		projectile.setShooter(owner);
		if (projectile instanceof AbstractArrow arrow) {
			if (pickup != null) arrow.setPickupRule(pickup);
			if (arrow instanceof Trident trident) trident.setLoyaltyLevel(0);
		}
		projectile.setVelocity(new Vector(aim[0], aim[1], aim[2]));
		projectile.getPersistentDataContainer().set(REFLECTS, PersistentDataType.INTEGER, reflects(projectile) + 1);
		sent.put(projectile.getEntityId(), new Sent(owner.getUniqueId(), c.tick() + PASS_TICKS));
		int[] ids = shooter instanceof Entity e ? new int[] {projectile.getEntityId(), e.getEntityId()} : new int[] {projectile.getEntityId()};
		effects(c, owner, s, FX_REFLECT, ids, from.getBlock());
		return true;
	}

	/**
	 * Stop the projectile: the block still held, and nothing flies back. What it carries is never destroyed or handed on:
	 * <ul>
	 *   <li>a player's trident is bounced off ({@link #bounce}), staying in the world as the thrower's, Loyalty and all;</li>
	 *   <li>an arrow whose pickup is ALLOWED (a player's, a dispenser's) is the real item it was shot from, and is dropped where it was
	 *       stopped, for its shooter alone when that is a player ({@code Item.setOwner}), else for anyone;</li>
	 *   <li>anything else (a mob's arrow or trident, a creative player's, a fireball) is removed.</li>
	 * </ul>
	 */
	private void absorb(ArmContext c, Player owner, State s, Projectile projectile) {
		Block block = projectile.getLocation().getBlock();
		int id = projectile.getEntityId();
		sent.remove(id);
		if (projectile instanceof Trident trident && playersTrident(trident)) {
			bounce(trident);
		} else {
			dropCarried(projectile);
			projectile.remove();
		}
		effects(c, owner, s, FX_ABSORB, new int[] {id}, block);
	}

	/**
	 * Knocks a player's trident back as the game does one that was blocked (x and z times -0.01, y times -0.1) and marks it as having dealt its
	 * damage, which the game's own tick treats as spent: it can't hit the owner again (a cancelled hit is not remembered, so it would be stopped
	 * and billed every tick), and one with Loyalty goes home to its thrower, who is still its owner.
	 */
	private static void bounce(Trident trident) {
		trident.setHasDealtDamage(true);
		trident.setVelocity(trident.getVelocity().multiply(new Vector(-0.01, -0.1, -0.01)));
	}

	/** An arrow with an ALLOWED pickup is a real item: dropped where it stopped (for its shooter alone when that is a player). */
	private static void dropCarried(Projectile projectile) {
		if (!(projectile instanceof AbstractArrow arrow) || arrow.getPickupRule() != AbstractArrow.PickupRule.ALLOWED) return;
		ItemStack stack = arrow.getItemStack();
		if (stack.isEmpty()) return;
		Item item = projectile.getWorld().dropItem(projectile.getLocation(), stack.clone());
		UUID shooter = originalShooter(projectile);
		if (shooter != null) {
			item.setOwner(shooter);
			item.setThrower(shooter);
		}
	}

	/** The player who shot it before a shield first turned it (see {@link #ORIGIN}), else its current shooter when that is a player, else nobody. */
	private static @Nullable UUID originalShooter(Projectile projectile) {
		String origin = projectile.getPersistentDataContainer().get(ORIGIN, PersistentDataType.STRING);
		if (origin != null) {
			try {
				return origin.isEmpty() ? null : UUID.fromString(origin);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		return projectile.getShooter() instanceof Player p ? p.getUniqueId() : null;
	}

	/**
	 * The block's show: S_ARM_FX kind FX_ACCESSORY to clients that draw the new forms, and a shield's sound to everyone near with dust for
	 * the viewers who don't draw it themselves.
	 */
	private void effects(ArmContext c, Player owner, State s, int move, int[] ids, Block at) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		c.fx(owner, new ArmFxMsg(owner.getEntityId(), ArmProtocol.FX_ACCESSORY, s.level, 0, rnd.nextInt(), ids, new long[] {new Pos(at.getX(), at.getY(), at.getZ()).asLong()},
				ShieldRules.fxArg(move)), ArmProtocol.fxRadius(ArmProtocol.FX_ACCESSORY));
		World world = owner.getWorld();
		Location spot = owner.getLocation().add(0, 1.0, 0);
		world.playSound(spot, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0F, 0.9F + rnd.nextFloat() * 0.2F);
		for (Player viewer : world.getPlayers()) {
			if (c.drawsNewForms(viewer) || !viewer.canSee(owner) || viewer.getLocation().distanceSquared(spot) > 48 * 48) continue;
			viewer.spawnParticle(Particle.DUST, spot.clone().add(owner.getLocation().getDirection().setY(0).normalize().multiply(0.5)), 6, 0.2, 0.25, 0.2, 0, SPARK, true);
		}
	}
}
