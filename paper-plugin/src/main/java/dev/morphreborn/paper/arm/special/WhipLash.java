package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.arm.rules.WhipMath;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import dev.morphreborn.paper.arm.rules.WhipSettings;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tendril.DrainHit;
import dev.morphreborn.paper.tendril.TendrilKills;
import dev.morphreborn.paper.tendril.TendrilTargets;
import dev.morphreborn.paper.tips.Lines;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The whip arm (spec section 9): a real fishing rod, so casting and fishing are vanilla (Lure and Luck of the Sea are
 * the item's own enchantments, from its level), with a tendril's way with what it catches.
 * <ul>
 * <li>Reeling in with the hook in the ground (or stuck against a wall) is a grapple: the line drags you toward the
 * hook, at a speed that grows with the distance, up to the level's range.</li>
 * <li>Reeling in a creature yanks it to you harder than a rod would; a creature far heavier than you pulls you to it
 * instead.</li>
 * <li>Left-click is a regular attack with a long reach (6 / 6.5 / 7 blocks): the item's own attribute modifiers
 * (ArmTiers), so vanilla clients and the server agree on reach, damage and speed; this module adds nothing to it.</li>
 * <li>F has four moves, all at level 1 (levels raise the numbers), picked by what you aim at (2026-11-16; they
 * replaced the old Lash, which copied the grapple and the bow's Harpoon Shot): at a creature, <b>Snare</b> holds it where
 * it stands for a couple of seconds; at one you have snared, <b>Disarm</b> rips its held item away and flings it to you;
 * at nothing, <b>Crack</b> is a sonic cone ahead that knocks back, hurts a little and scares mobs off; Sneak+F is the
 * <b>Whirl</b>, the lash spun round you.</li>
 * </ul>
 * What each costs is in survival.yml arm-cost.specials: the cast and a caught fish (whip-cast), a grapple (whip-pull), a
 * yank (whip-yank) and the four moves (whip-snare, whip-disarm, whip-crack, whip-whirl). The manager keeps one cooldown
 * per form, so it is only the short shared one; each move's own is kept here. A fish caught on the line may also give a
 * little biomass (fish-biomass-chance and fish-biomass in the whip's section). A rod normally wears when it reels; here
 * the wear is cancelled and the line is billed in biomass instead. Everything the others see of a move is sent as
 * S_ARM_FX kind 14 (clients that know it) and the item's own state; the server's own particles stand in for players whose
 * game doesn't draw it, and every move's sound is played by the server (to everyone near; a Crack or Whirl that found nothing is
 * only a quiet swing the owner hears). Snare and Disarm ask the world's PvP flag and the protection plugins first (a test hit
 * from the owner), and a Crack or Whirl pushes and scares only what its hit really landed on.
 */
public final class WhipLash implements ArmSpecialModule {
	public static final String FORM = "whip";
	/** Its line costs' rows in survival.yml arm-cost.specials (the moves' rows are WhipMoves.Move#costKey). */
	private static final String CAST_KEY = "whip-cast", PULL_KEY = "whip-pull", YANK_KEY = "whip-yank";
	/** The arm tag and the session nonce on an arm item (the keys ArmItems writes), read here to know a rod is the whip. */
	private static final NamespacedKey ARM_FORM = MorphRebornPlugin.key("arm"), ARM_NONCE = MorphRebornPlugin.key("arm_nonce");
	/** How wide F's ray is: it finds a creature this close to the line of sight. */
	private static final double AIM_RAY = 0.6;
	/** How long each move shows on the clients' screens, in ticks (the Snare shows for as long as it holds). */
	private static final int DISARM_TICKS = 6, CRACK_TICKS = 6, WHIRL_TICKS = 8;
	/** The most creatures one Crack or Whirl takes, so a crowd can't turn one lash into a server-wide hit. */
	private static final int MAX_HITS = 16;
	/** How often a held creature is puffed for the players whose game doesn't draw the lash, and a scared mob told where to run, in ticks. */
	private static final int SNARE_PUFF_TICKS = 10, FLEE_PERIOD = 4;
	/** How often finished cooldown records of players who left are swept out (every minute). */
	private static final int COOLDOWN_PRUNE_PERIOD = 1200;
	/** A disarmed item can't be picked up for this long, so it isn't snatched back by the mob it was taken from. */
	private static final int PICKUP_DELAY = 40;
	/** A mob the Crack scared runs this far from where the Crack was, at this speed (a multiple of its walk). */
	private static final double FLEE_DISTANCE = 10, FLEE_SPEED = 1.4;
	/** The snare's hold: a transient (never saved) modifier of -100% on each of these, so a held creature can't walk, jump or fly. */
	private static final NamespacedKey SNARE_MODIFIER = MorphRebornPlugin.key("whip_snare");
	/** (Looked up when used, not held in a constant: the attribute constants need a running server's registry, and this class is loaded by tests.) */
	private static List<Attribute> heldAttributes() {
		return List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.FLYING_SPEED);
	}
	/** After a pull, falling doesn't hurt for this long (the pull itself isn't a fall). */
	private static final int NO_FALL_TICKS = 10;
	/** A hook this close to a block counts as stuck against it, when the line is reeled in the air. */
	private static final double STUCK_MARGIN = 0.2;
	/** A tendril's thickness in pixels, at the arm and at the end (the server's fallback drawing). */
	private static final float LASH_FROM_PX = 1.6F, LASH_TO_PX = 0.6F;
	/** Kinds the yank and the snare never take: bosses and the like, whatever their mass. */
	private static final Set<String> NEVER_YANKED = Set.of("minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian");

	private @Nullable ArmContext ctx;
	private WhipSettings settings = WhipSettings.DEFAULTS;
	/**
	 * Viewers whose game draws the whip's moves (7.6.0-beta.4 and later: FX_WHIP_MOVE reaches only them): every particle and
	 * pack fallback of a move leaves them out, and every other viewer, a modded one on an older beta included, gets it.
	 */
	private Predicate<Player> movers = v -> false;
	/** Players whose fall distance is held at 0, and until which server tick. */
	private final Map<UUID, Integer> noFall = new HashMap<>();

	// ------------------------------------------------------------------ anchors and reels (2026-11-15)
	//
	// "it should be able to land that anchor on the walls and ceilings for pulling yourself up", "pull you up onto edges
	// and reel you in": a cast hook that hits a block sticks there, on any face, a wall or a ceiling too; reeling it in
	// reels you along the line to it over a moment rather than one yank (the Lash that also did this was removed on 2026-11-16); and arriving at a
	// wall with room above it, you are lifted up and over onto the ledge.

	/** A cast hook stuck where it hit: the hook, where it is held, the block and the face it hit. */
	private record Anchor(UUID hook, Location at, Block block, org.bukkit.block.BlockFace face) {}

	/** One player being reeled toward a point: where to, how it ends (onto a ledge, under a ceiling) and since when. */
	private static final class Reel {
		final Vector to;
		final boolean ledge, ceiling;
		final Vector into;
		final int ledgeTop, start, lvl;
		/** The tick the lift onto the ledge began (-1: still reeling): the push over waits until the feet clear the top. */
		int mantleFrom = -1;
		/** Under a ceiling: held there from this tick (-1: not yet), until sneaking lets go or {@link #HANG_TICKS} pass. */
		int hangFrom = -1;

		Reel(Vector to, boolean ledge, boolean ceiling, Vector into, int ledgeTop, int start, int lvl) {
			this.to = to;
			this.ledge = ledge;
			this.ceiling = ceiling;
			this.into = into;
			this.ledgeTop = ledgeTop;
			this.start = start;
			this.lvl = lvl;
		}
	}

	private final Map<UUID, Anchor> anchors = new HashMap<>();
	private final Map<UUID, Reel> reels = new HashMap<>();
	/** A reel lasts this long at most; it ends this close to its point (blocks); sneaking lets go after this many ticks. */
	private static final int REEL_MAX_TICKS = 40, REEL_SNEAK_AFTER = 4;
	private static final double REEL_ARRIVE = 1.4;
	/** How long a reel to a ceiling holds you up there. */
	private static final int HANG_TICKS = 60;
	private final Listener events = new Events();

	public WhipLash() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.movers = ctx::drawsWhipMoves;
		this.settings = WhipSettings.parse(ctx.config().form(FORM));
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = WhipSettings.parse(formSection);
	}

	@Override
	public Listener listener() {
		return events;
	}

	@Override
	public boolean breaksBlocks() {
		return false;
	}

	// ------------------------------------------------------------------ the moves (F)
	//
	// The whip rework (2026-11-16). F is picked by what you aim at (WhipMoves.pick): a creature the lash can hold
	// is a Snare, one you have already snared is a Disarm, nothing is a Crack, and Sneak+F is the Whirl. A move that found
	// nothing to act on costs nothing and starts no cooldown.

	/** A creature held by a Snare: whose it is, until which tick, and how it is held (WhipMoves.holdKind). */
	private record Snare(UUID owner, int until, WhipMoves.HoldKind kind) {}

	/** A mob scared by a Crack: until which tick, and the point it runs from. */
	private record Flee(int until, Vector from) {}

	/** Each player's own move cooldowns (kept across retracts, so re-forming the arm never dodges one). */
	private final Map<UUID, WhipMoves.Cooldowns> cooldowns = new HashMap<>();
	/** Snared creatures by id, and the mobs a Crack is making run. */
	private final Map<UUID, Snare> snares = new HashMap<>();
	private final Map<UUID, Flee> fleeing = new HashMap<>();

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		int lvl = level(level);
		WhipSettings.Moves mv = settings.moves();
		int now = now(c);
		Location eye = p.getEyeLocation();
		Vector dir = eye.getDirection();
		// what the aim finds: a creature within reach with no block in the way (Sneak+F doesn't look: it is the Whirl)
		LivingEntity aimed = sneaking ? null : aim(c, p, eye, dir, mv.reach().at(lvl));
		boolean holdable = aimed != null && holdable(c, p, aimed);
		boolean snared = holdable && snaredBy(aimed, p, now);
		WhipMoves.Move move = WhipMoves.pick(sneaking, holdable, snared);
		WhipMoves.Cooldowns cds = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new WhipMoves.Cooldowns());
		int left = cds.left(move, now);
		if (left > 0) return SpecialResult.refused(move.label() + " ready in " + seconds(left));
		int seed = ThreadLocalRandom.current().nextInt(1 << 30);
		Hold hold = new Hold(c, p, lvl, now, seed, charge, cds);
		return switch (move) {
			case SNARE -> snare(hold, aimed);
			case DISARM -> disarm(hold, aimed);
			case CRACK -> crack(hold, eye, dir);
			case WHIRL -> whirl(hold);
		};
	}

	/** What one F press carries from move to move. */
	private record Hold(ArmContext c, Player p, int lvl, int now, int seed, SpecialCharge charge, WhipMoves.Cooldowns cds) {}

	/**
	 * Pay for a move that found something: the biomass through the manager (which also starts the short shared cooldown), then
	 * the move's own cooldown. False when it couldn't be paid (the manager has said why).
	 */
	private boolean pay(Hold h, WhipMoves.Move move) {
		if (!h.charge().pay(costMilli(move, h.lvl()), settings.moves().sharedCooldownTicks())) return false;
		int ticks = (int) Math.round(settings.moves().cooldownTicks(move, h.lvl()) * h.c().plugin().mode().cooldownFactor(h.p()));
		h.cds().start(move, h.now(), ticks);
		h.c().refreshArsenal(h.p());
		return true;
	}

	/** The nearest creature F's ray finds within {@code reach}, null when a block comes first or nothing is there. */
	private @Nullable LivingEntity aim(ArmContext c, Player p, Location eye, Vector dir, double reach) {
		RayTraceResult hit = p.getWorld().rayTrace(eye, dir, reach, FluidCollisionMode.NEVER, true, AIM_RAY, e -> aimable(c, p, e));
		return hit != null && hit.getHitEntity() instanceof LivingEntity t ? t : null;
	}

	/** F's ray may find a creature a tendril may take hold of, and nothing else. */
	private boolean aimable(ArmContext c, Player p, Entity e) {
		return e instanceof LivingEntity && !e.equals(p) && TendrilTargets.canTarget(c.plugin(), p, e, true, c.config().players());
	}

	/**
	 * The Snare may take hold of it: never bosses, other players' pets or mounts in use, players only when the settings allow,
	 * and nothing the snare has no way to hold (it would bill for a lash that holds nothing: F is a Crack at it).
	 */
	private boolean holdable(ArmContext c, Player p, LivingEntity t) {
		return yankable(c, p, t) && (!(t instanceof Player) || settings.snarePlayers()) && holdKindOf(t) != WhipMoves.HoldKind.NONE;
	}

	/**
	 * How the snare would hold this creature: a player is always a walker (the speed and jump attributes; a teleport is
	 * cancelled). Decided by what it is, not by its attributes (every living entity has them, an armour stand too): a mob with
	 * its AI on by its kind, anything else not at all.
	 */
	private static WhipMoves.HoldKind holdKindOf(LivingEntity t) {
		if (t instanceof Player) return WhipMoves.HoldKind.WALKER;
		return WhipMoves.holdKind(t.getType().getKey().toString(), t instanceof Mob, t instanceof Mob m && m.hasAI(), t.hasGravity());
	}

	/** Whether the lash may touch this creature under the world's rules and the protection plugins (ArmTouch, shared with the spear's Impale). */
	private boolean mayAffect(ArmContext c, Player p, LivingEntity t) {
		return ArmTouch.mayAffect(c, p, t);
	}

	private boolean snaredBy(LivingEntity t, Player owner, int now) {
		Snare s = snares.get(t.getUniqueId());
		return s != null && s.until() > now && s.owner().equals(owner.getUniqueId());
	}

	/** The creature is held by a Snare that hasn't run out. */
	private boolean isSnared(LivingEntity t) {
		ArmContext c = ctx;
		Snare s = snares.get(t.getUniqueId());
		return c != null && s != null && s.until() > now(c);
	}

	/**
	 * Everything the module keeps for a player who quit. Their move cooldowns are kept across a retract, a death and a relog
	 * (leaving and coming back never dodges one): only a record with nothing left to wait for is dropped, so nothing grows
	 * without end and nothing running is ever lost.
	 */
	void forget(Player p) {
		UUID id = p.getUniqueId();
		ArmContext c = ctx;
		if (c == null) cooldowns.remove(id);
		else dropExpired(id, now(c));
		noFall.remove(id);
		anchors.remove(id);
		reels.remove(id);
		fleeing.remove(id);
		if (snares.remove(id) != null) release(p);
	}

	/** Forget this player's cooldown record if every move in it is ready again. */
	private void dropExpired(UUID id, int now) {
		cooldowns.computeIfPresent(id, (k, cds) -> cds.expired(now) ? null : cds);
	}

	// ---- Snare

	private SpecialResult snare(Hold h, LivingEntity t) {
		ArmContext c = h.c();
		Player p = h.p();
		// a claim or a no-PvP world says no before anything is paid
		if (!mayAffect(c, p, t)) return SpecialResult.noTarget("You can't snare that here.");
		if (!pay(h, WhipMoves.Move.SNARE)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why
		int ticks = settings.moves().snareTicks(h.lvl());
		p.swingMainHand();
		hold(t);
		snares.put(t.getUniqueId(), new Snare(p.getUniqueId(), h.now() + ticks, holdKindOf(t)));
		fleeing.remove(t.getUniqueId()); // a held mob isn't running anywhere
		Location at = bodyOf(t);
		sound(at, Sound.BLOCK_CHAIN_PLACE, 1.2F, 0.7F);
		sound(p.getLocation(), Sound.ENTITY_BREEZE_WHIRL, 0.6F, 1.8F);
		sendMove(c, p, h.lvl(), h.seed(), ticks, new int[] {t.getEntityId()}, new long[0], ArmProtocol.WHIP_SNARE);
		fallbackLine(c, p, at, h.seed(), movers);
		if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " snared " + t.getName() + " for " + ticks + " ticks");
		return SpecialResult.FIRED;
	}

	/** Hold a creature where it stands: no walking, jumping or flying, by modifiers that are never saved. Its AI, and its target, go on. */
	private static void hold(LivingEntity t) {
		for (Attribute a : heldAttributes()) {
			AttributeInstance inst = t.getAttribute(a);
			if (inst == null || inst.getModifier(SNARE_MODIFIER) != null) continue;
			inst.addTransientModifier(new AttributeModifier(SNARE_MODIFIER, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
		}
	}

	private static void release(LivingEntity t) {
		for (Attribute a : heldAttributes()) {
			AttributeInstance inst = t.getAttribute(a);
			if (inst != null && inst.getModifier(SNARE_MODIFIER) != null) inst.removeModifier(SNARE_MODIFIER);
		}
	}

	// ---- Disarm

	private SpecialResult disarm(Hold h, LivingEntity t) {
		ArmContext c = h.c();
		Player p = h.p();
		if (t instanceof Player && !settings.disarmPlayers()) return SpecialResult.noTarget("Players can't be disarmed here.");
		if (!mayAffect(c, p, t)) return SpecialResult.noTarget("You can't disarm that here.");
		EntityEquipment eq = t.getEquipment();
		EquipmentSlot slot = eq == null ? null : holdingSlot(eq);
		if (eq == null || slot == null) return SpecialResult.noTarget("It holds nothing.");
		ItemStack item = eq.getItem(slot).clone();
		// Anti-farm (2026-11-16): the item drops only if it would have dropped when the mob died, rolled against its
		// equipment drop chance (a picked-up item is guaranteed); otherwise it is destroyed. A player's items always drop.
		double chance = t instanceof Mob ? eq.getDropChance(slot) : 1.0;
		boolean drops = t instanceof Player || WhipMoves.disarmDrops(chance, ThreadLocalRandom.current().nextDouble());
		Location from = t instanceof Player ? t.getLocation() : t.getEyeLocation().subtract(0, 0.4, 0);
		// The item is made first and the creature's hand emptied last: a drop that didn't happen (another plugin vetoed the
		// spawn) leaves it holding its item, and the move is free.
		Item dropped = null;
		if (drops) {
			dropped = dropOf(p, t, from, item);
			if (dropped == null) return SpecialResult.noTarget("It won't let go.");
			// whatever ran on the spawn may have changed what it holds: take back the copy rather than duplicate an item
			if (!item.isSimilar(eq.getItem(slot))) {
				dropped.remove();
				return SpecialResult.noTarget("It holds nothing.");
			}
		}
		if (!pay(h, WhipMoves.Move.DISARM)) {
			if (dropped != null) dropped.remove();
			return new SpecialResult(SpecialResult.Outcome.REFUSED, null);
		}
		p.swingMainHand();
		eq.setItem(slot, new ItemStack(Material.AIR));
		int itemId = dropped == null ? -1 : dropped.getEntityId();
		String outcome;
		if (dropped == null) {
			t.getWorld().spawnParticle(Particle.ITEM, from, 18, 0.25, 0.25, 0.25, 0.08, item.asOne());
			sound(from, Sound.ENTITY_ITEM_BREAK, 1F, 1F);
			outcome = "destroyed (drop chance " + amount(chance) + ")";
		} else {
			outcome = t instanceof Player ? "dropped at their feet" : "flung to " + p.getName();
		}
		sound(bodyOf(t), Sound.BLOCK_CHAIN_BREAK, 1F, 1.3F);
		sound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1F, 1.2F);
		sendMove(c, p, h.lvl(), h.seed(), DISARM_TICKS, new int[] {t.getEntityId(), itemId}, new long[0], ArmProtocol.WHIP_DISARM, ArmProtocol.fxRadius(ArmProtocol.FX_WHIP_MOVE));
		fallbackLine(c, p, bodyOf(t), h.seed(), movers);
		if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " disarmed " + t.getName() + " of " + item.getType().getKey().getKey() + ": " + outcome);
		return SpecialResult.FIRED;
	}

	/**
	 * The disarmed item as an item in the world: at a player's own feet (never at the owner's), or flung toward the owner for a
	 * mob. Null when it didn't come to be (another plugin cancelled the spawn, or the call refused it).
	 */
	private static @Nullable Item dropOf(Player owner, LivingEntity t, Location from, ItemStack item) {
		World world = t.getWorld();
		try {
			Item dropped;
			if (t instanceof Player) {
				dropped = world.dropItem(from, item, i -> i.setPickupDelay(PICKUP_DELAY));
			} else {
				Location eye = owner.getEyeLocation();
				double[] v = WhipMoves.fling(eye.getX() - from.getX(), eye.getY() - 0.4 - from.getY(), eye.getZ() - from.getZ());
				dropped = world.dropItem(from, item, i -> {
					i.setVelocity(new Vector(v[0], v[1], v[2]));
					i.setPickupDelay(PICKUP_DELAY);
				});
			}
			return dropped != null && dropped.isValid() ? dropped : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** What a creature holds that can be taken: its main hand, then its off hand; never an arm (taking one would put a second copy in the world). */
	private static @Nullable EquipmentSlot holdingSlot(EntityEquipment eq) {
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
			ItemStack s = eq.getItem(slot);
			if (!s.isEmpty() && !isArmItem(s)) return slot;
		}
		return null;
	}

	private static boolean isArmItem(ItemStack s) {
		try {
			return s.getPersistentDataContainer().has(ARM_FORM);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	// ---- Crack

	private SpecialResult crack(Hold h, Location eye, Vector dir) {
		ArmContext c = h.c();
		Player p = h.p();
		WhipSettings.Moves mv = settings.moves();
		double range = mv.crackRange().at(h.lvl());
		// the far point along the look, stopping at a block
		RayTraceResult wall = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
		Vector far = wall != null ? wall.getHitPosition() : eye.toVector().add(dir.clone().multiply(range));
		List<LivingEntity> targets = new ArrayList<>();
		for (Entity e : p.getNearbyEntities(range + 1, range + 1, range + 1)) {
			if (!(e instanceof LivingEntity t) || !TendrilTargets.canTarget(c.plugin(), p, t, true, c.config().players())) continue;
			Vector at = t.getBoundingBox().getCenter().subtract(eye.toVector());
			if (!WhipMoves.inCone(at.getX(), at.getY(), at.getZ(), dir.getX(), dir.getY(), dir.getZ(), range, mv.crackHalfAngle(), t.getWidth() / 2)) continue;
			if (!p.hasLineOfSight(t)) continue;
			targets.add(t);
		}
		targets.sort(Comparator.comparingDouble(t -> t.getLocation().distanceSquared(p.getLocation())));
		if (targets.size() > MAX_HITS) targets = new ArrayList<>(targets.subList(0, MAX_HITS));
		Location farAt = far.toLocation(p.getWorld());
		Location mid = eye.clone().add(dir.clone().multiply(Math.min(range, far.distance(eye.toVector())) / 2));
		if (targets.isEmpty()) {
			// found nothing: it still cracks (the lash was swung) but costs nothing, starts no cooldown, and is only a quiet swing:
			// heard by the owner alone and shown to those close by, so tapping F at the air isn't heard across the server
			p.swingMainHand();
			quietSwing(p, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5F, 1.6F);
			sendMove(c, p, h.lvl(), h.seed(), CRACK_TICKS, new int[0], new long[] {endBlockKey(far)}, ArmProtocol.WHIP_CRACK, WhipMoves.WHIFF_RADIUS);
			Predicate<Player> skip = nearOnly(p);
			for (Player v : p.getWorld().getPlayers()) {
				if (!skip.test(v)) v.spawnParticle(Particle.SONIC_BOOM, mid, 1);
			}
			if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " cracked 0 creatures (0 pushed, 0 running): nothing in the cone, free");
			return new SpecialResult(SpecialResult.Outcome.NO_TARGET, null);
		}
		if (!pay(h, WhipMoves.Move.CRACK)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null);
		p.swingMainHand();
		double damage = mv.crackDamage().at(h.lvl()), knock = mv.crackKnockback().at(h.lvl());
		int[] ids = new int[targets.size()];
		int hits = 0, fled = 0;
		for (int i = 0; i < targets.size(); i++) {
			LivingEntity t = targets.get(i);
			ids[i] = t.getEntityId();
			// pushed and scared only when the blow really landed: a hit that a claim, a PvP rule or another plugin cancelled
			// does neither (a mob is no different from a player there)
			boolean landed = hurt(c, p, t, damage);
			if (landed && push(t, p.getLocation().toVector(), knock)) hits++;
			if (landed && t instanceof Mob m && alive(m) && !(m instanceof Boss) && mv.fleeTicks() > 0) {
				fleeing.put(m.getUniqueId(), new Flee(h.now() + mv.fleeTicks(), p.getLocation().toVector()));
				fleeStep(m, fleeing.get(m.getUniqueId()));
				fled++;
			}
		}
		sound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.2F, 1.7F);
		sendMove(c, p, h.lvl(), h.seed(), CRACK_TICKS, ids, new long[] {endBlockKey(far)}, ArmProtocol.WHIP_CRACK, ArmProtocol.fxRadius(ArmProtocol.FX_WHIP_MOVE));
		fallbackLine(c, p, farAt, h.seed(), movers);
		for (Player v : p.getWorld().getPlayers()) {
			if (!movers.test(v) && v.getLocation().distanceSquared(mid) <= 48 * 48) v.spawnParticle(Particle.SONIC_BOOM, mid, 1);
		}
		if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " cracked " + targets.size() + " creatures (" + hits + " pushed, " + fled + " running)");
		return SpecialResult.FIRED;
	}

	/** The scared mob's next step: its target dropped, and a path away from where the Crack was (re-asked every few ticks, since its own goals take over). */
	private void fleeStep(Mob m, Flee f) {
		m.setTarget(null);
		Vector away = m.getLocation().toVector().subtract(f.from()).setY(0);
		if (away.lengthSquared() < 1e-4) away = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5, 0, ThreadLocalRandom.current().nextDouble() - 0.5);
		if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
		away.normalize().multiply(FLEE_DISTANCE);
		m.getPathfinder().moveTo(m.getLocation().add(away), FLEE_SPEED);
	}

	// ---- Whirl

	private SpecialResult whirl(Hold h) {
		ArmContext c = h.c();
		Player p = h.p();
		WhipSettings.Moves mv = settings.moves();
		double radius = mv.whirlRadius().at(h.lvl());
		Vector me = center(p);
		List<LivingEntity> targets = new ArrayList<>();
		for (Entity e : p.getNearbyEntities(radius + 1, radius + 1, radius + 1)) {
			if (!(e instanceof LivingEntity t) || !TendrilTargets.canTarget(c.plugin(), p, t, true, c.config().players())) continue;
			Vector at = t.getBoundingBox().getCenter().subtract(me);
			if (!WhipMoves.inRadius(at.getX(), at.getY(), at.getZ(), radius, t.getWidth() / 2) || !p.hasLineOfSight(t)) continue;
			targets.add(t);
		}
		targets.sort(Comparator.comparingDouble(t -> t.getLocation().distanceSquared(p.getLocation())));
		if (targets.size() > MAX_HITS) targets = new ArrayList<>(targets.subList(0, MAX_HITS));
		VanillaEffects vanilla = c.plugin().effects();
		if (targets.isEmpty()) {
			// nothing in reach: free, no cooldown, and only a quiet swing (the owner hears it, those close by see it)
			p.swingMainHand();
			quietSwing(p, Sound.ENTITY_BREEZE_WHIRL, 0.4F, 1.4F);
			sendMove(c, p, h.lvl(), h.seed(), WHIRL_TICKS, new int[0], new long[0], ArmProtocol.WHIP_WHIRL, WhipMoves.WHIFF_RADIUS);
			if (vanilla != null) vanilla.sweepArc(p, radius, 360, VanillaEffects.ARM_GREY, nearOnly(p));
			if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " whirled 0 creatures (0 pushed): nothing in reach, free");
			return new SpecialResult(SpecialResult.Outcome.NO_TARGET, null);
		}
		if (!pay(h, WhipMoves.Move.WHIRL)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null);
		p.swingMainHand();
		double damage = mv.whirlDamage().at(h.lvl()), knock = mv.whirlKnockback().at(h.lvl());
		int[] ids = new int[targets.size()];
		int hits = 0;
		for (int i = 0; i < targets.size(); i++) {
			LivingEntity t = targets.get(i);
			ids[i] = t.getEntityId();
			if (hurt(c, p, t, damage) && push(t, p.getLocation().toVector(), knock)) hits++;
		}
		sound(p.getLocation(), Sound.ENTITY_BREEZE_WHIRL, SoundCategory.PLAYERS, 1.4F, 1.1F);
		sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0F, 0.8F);
		sendMove(c, p, h.lvl(), h.seed(), WHIRL_TICKS, ids, new long[0], ArmProtocol.WHIP_WHIRL, ArmProtocol.fxRadius(ArmProtocol.FX_WHIP_MOVE));
		if (vanilla != null) vanilla.sweepArc(p, radius, 360, VanillaEffects.ARM_GREY, movers);
		if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " whirled " + targets.size() + " creatures (" + hits + " pushed)");
		return SpecialResult.FIRED;
	}

	/** A whiff's sound: the owner alone hears it. */
	private static void quietSwing(Player p, Sound sound, float volume, float pitch) {
		p.playSound(p.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
	}

	/** The viewers a whiff's particles skip: those whose game draws the move (they get the packet) and everyone beyond {@link WhipMoves#WHIFF_RADIUS}. */
	private Predicate<Player> nearOnly(Player owner) {
		Location at = owner.getLocation();
		return v -> movers.test(v) || v.getWorld() != owner.getWorld() || !WhipMoves.nearWhiff(v.getLocation().distanceSquared(at));
	}

	// ---- hits and pushes

	/** The blow being struck right now, watched by the module's damage listener (one deep: a hit never starts another). */
	private @Nullable Player blowOwner;
	private @Nullable LivingEntity blowTarget;
	private boolean blowSeen, blowCancelled;

	/**
	 * A move's damage, as a tendril's hit (PLAYER_ATTACK, so armour counts as for a sword), flagged so it isn't billed as a melee
	 * hit. True when the hit landed: its damage event ran and nothing cancelled it (a protection plugin, a PvP rule, a team), or
	 * the health really fell. A hit that vanilla's invulnerability frames swallowed (no event at all, the target was hurt a
	 * moment ago) still counts when the same tests as a Snare's allow the target: the lash was struck and billed, so the push
	 * and the scare go on. With no damage to deal (the setting is 0) those tests alone decide it.
	 */
	private boolean hurt(ArmContext c, Player p, LivingEntity t, double damage) {
		if (!(damage > 0)) return mayAffect(c, p, t);
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
		boolean allowed = !(took[0] > 0) && !blowSeen && hadFrames && mayAffect(c, p, t);
		return WhipMoves.blowLanded(took[0], blowSeen, blowCancelled, hadFrames, allowed);
	}

	/** Push a creature away from a point, less by its knockback resistance. False (and no push) when it died of the hit. */
	private static boolean push(LivingEntity t, Vector from, double strength) {
		if (!alive(t)) return false;
		AttributeInstance resist = t.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
		Vector at = t.getLocation().toVector();
		double[] v = WhipMoves.push(at.getX() - from.getX(), at.getZ() - from.getZ(), strength, resist == null ? 0 : resist.getValue());
		Vector now = t.getVelocity();
		t.setVelocity(new Vector(now.getX() + v[0], Math.max(now.getY(), v[1]), now.getZ() + v[2]));
		return true;
	}

	// ------------------------------------------------------------------ the line

	/**
	 * A creature is on the line: a light one is yanked to you, a heavy one drags you to it, one that
	 * is never taken is left alone. {@code vanillaPulls}: the reel-in goes on to add vanilla's own pull, so only the part
	 * beyond it is added. {@code costKey} is what it costs (null: paid already). Returns what happened, for the report.
	 */
	private String pullCreature(ArmContext c, Player p, LivingEntity t, Location hookAt, int lvl, boolean vanillaPulls, @Nullable String costKey) {
		if (!yankable(c, p, t)) return "left alone";
		double mass = c.plugin().survival().massOf(t);
		if (WhipMath.light(mass, settings)) {
			Location me = p.getLocation();
			double scale = t instanceof Player ? Math.clamp(c.config().lesserRate(), 0, 1) : 1;
			double[] v = WhipMath.yank(me.getX() - hookAt.getX(), me.getY() - hookAt.getY(), me.getZ() - hookAt.getZ(), lvl, scale, vanillaPulls, settings);
			t.setVelocity(t.getVelocity().add(new Vector(v[0], v[1], v[2])));
			if (costKey != null) bill(c, p, costKey, lvl, "yank");
			return "yanked (" + SurvivalMode.number(mass) + " kg)";
		}
		boolean pulled = grapple(c, p, centerOf(t), lvl, true, costKey);
		return pulled ? "dragged to it (" + SurvivalMode.number(mass) + " kg)" : "too heavy and too far";
	}

	/**
	 * Drag the player toward a point. False, with nothing changed and nothing paid, when they can't be: riding, dead,
	 * the point too near to go anywhere, or (for a line) further than it reaches.
	 *
	 * @param byLine the point is the end of a line, which breaks beyond the level's range
	 * @param costKey what it costs, or null when it is paid already
	 */
	private boolean grapple(ArmContext c, Player p, Vector at, int lvl, boolean byLine, @Nullable String costKey) {
		if (p.isDead() || p.isInsideVehicle()) return false;
		Vector from = center(p);
		double dist = from.distance(at);
		if (!WhipMath.farEnough(dist)) return false;
		if (byLine && !WhipMath.inRange(dist, lvl, settings)) {
			p.sendActionBar(Component.text("The line is too long to pull you (" + SurvivalMode.number(settings.range(lvl)) + " blocks at most).", NamedTextColor.GRAY));
			return false;
		}
		boolean above = at.getY() > p.getEyeLocation().getY();
		double[] v = WhipMath.grapple(at.getX() - from.getX(), at.getY() - from.getY(), at.getZ() - from.getZ(), above, p.isOnGround(), lvl, settings);
		p.setVelocity(new Vector(v[0], v[1], v[2]));
		p.setFallDistance(0);
		noFall.put(p.getUniqueId(), now(c) + NO_FALL_TICKS);
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, 1F, 1.6F);
		if (costKey != null) bill(c, p, costKey, lvl, "pull");
		return true;
	}

	/**
	 * Start reeling the player along a line to {@code at}, a point on {@code face} of {@code block}: false, with nothing
	 * changed and nothing paid, when they can't be (as {@link #grapple}). Arriving at a wall's face with room above the
	 * block (two passable blocks), they are lifted over onto it; at a ceiling, they stop under it a moment.
	 */
	private boolean reel(ArmContext c, Player p, Vector at, Block block, org.bukkit.block.@Nullable BlockFace face, int lvl, boolean byLine,
			@Nullable String costKey) {
		if (p.isDead() || p.isInsideVehicle()) return false;
		double dist = center(p).distance(at);
		if (!WhipMath.farEnough(dist)) return false;
		if (byLine && !WhipMath.inRange(dist, lvl, settings)) {
			p.sendActionBar(Component.text("The line is too long to pull you (" + SurvivalMode.number(settings.range(lvl)) + " blocks at most).", NamedTextColor.GRAY));
			return false;
		}
		org.bukkit.block.BlockFace f = face == null ? org.bukkit.block.BlockFace.UP : face;
		boolean side = f.getModY() == 0;
		// the wall's top, if it is within two blocks above where the line caught (room for a player on it)
		int top = Integer.MIN_VALUE;
		if (side) {
			for (int k = 0; k <= 2; k++) {
				Block b = block.getRelative(org.bukkit.block.BlockFace.UP, k);
				if (b.isPassable()) break;
				Block over = b.getRelative(org.bukkit.block.BlockFace.UP);
				if (over.isPassable() && over.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
					top = b.getY() + 1;
					break;
				}
			}
		}
		boolean ledge = top != Integer.MIN_VALUE;
		Vector into = new Vector(-f.getModX(), 0, -f.getModZ());
		reels.put(p.getUniqueId(), new Reel(at.clone(), ledge, f == org.bukkit.block.BlockFace.DOWN, into, ledge ? top : block.getY() + 1, now(c), lvl));
		p.setFallDistance(0);
		noFall.put(p.getUniqueId(), now(c) + REEL_MAX_TICKS + NO_FALL_TICKS);
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, 1F, 1.6F);
		if (costKey != null) bill(c, p, costKey, lvl, "pull");
		return true;
	}

	/** One tick of a reel: along the line at a speed that grows with the distance left; true when it is over. */
	private boolean reelTick(Player p, Reel r, int now) {
		if (p.isDead() || p.isInsideVehicle() || !p.isOnline()) return true;
		if (r.hangFrom >= 0) {
			// hanging under the ceiling on the line: held there until sneaking lets go
			if (p.isSneaking() || now - r.hangFrom > HANG_TICKS) return true;
			Vector d = r.to.clone().subtract(center(p));
			p.setVelocity(d.length() > 0.6 ? d.multiply(0.25) : new Vector());
			p.setFallDistance(0);
			return false;
		}
		if (r.mantleFrom >= 0) {
			// rising beside the wall: once the feet are over its top, over onto it (pushed earlier, the wall takes it)
			if (p.getLocation().getY() >= r.ledgeTop + 0.02) {
				p.setVelocity(r.into.clone().multiply(0.3).setY(Math.max(0.1, p.getVelocity().getY())));
				p.setFallDistance(0);
				return true;
			}
			return now - r.mantleFrom > 14;
		}
		Vector from = center(p);
		Vector d = r.to.clone().subtract(from);
		double dist = d.length();
		boolean letGo = p.isSneaking() && now - r.start >= REEL_SNEAK_AFTER;
		if (dist < REEL_ARRIVE || now - r.start > REEL_MAX_TICKS || letGo) {
			if (r.ledge && !letGo) {
				// up and over: enough to bring the feet above the ledge, and onto it
				double rise = Math.max(0.6, r.ledgeTop + 0.4 - p.getLocation().getY());
				double vy = Math.clamp(Math.sqrt(2 * 0.08 * rise) + 0.1, 0.42, 1.1);
				p.setVelocity(new Vector(0, vy, 0));
				p.setFallDistance(0);
				r.mantleFrom = now;
				return false;
			} else if (r.ceiling && !letGo) {
				p.setVelocity(new Vector());
				p.setFallDistance(0);
				r.hangFrom = now;
				return false;
			} else {
				p.setVelocity(p.getVelocity().multiply(0.35));
			}
			p.setFallDistance(0);
			return true;
		}
		double speed = Math.clamp(0.42 + 0.075 * dist, 0.42, 1.3);
		Vector v = d.multiply(speed / dist);
		if (r.to.getY() > p.getEyeLocation().getY()) v.setY(v.getY() + 0.08); // against gravity on the way up
		p.setVelocity(v);
		p.setFallDistance(0);
		return false;
	}

	/** Never taken: bosses and the other creatures the tendrils leave alone, whatever they weigh. */
	private boolean yankable(ArmContext c, Player p, LivingEntity t) {
		if (!TendrilTargets.canTarget(c.plugin(), p, t, true, c.config().players())) return false;
		if (t instanceof org.bukkit.entity.Boss || NEVER_YANKED.contains(t.getType().getKey().toString())) return false;
		return !(t instanceof Tameable pet && pet.isTamed() && !p.getUniqueId().equals(pet.getOwnerUniqueId()));
	}

	/** Charge a part of the whip's use: partial, so the reel that empties the arm still goes through (it melts after). */
	private void bill(ArmContext c, Player p, String key, int lvl, String what) {
		long milli = c.config().costs().special(key, lvl);
		if (milli <= 0) return;
		ArmLedger.Spend spend = c.spend(p, milli, true);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " " + what + ": " + (spend.charged() > 0 ? "-" + spend.charged() + " milli, " + spend.after() + " left" : "free"));
		}
	}

	// ------------------------------------------------------------------ what the clients and the others see

	/** A tendril's skin key: one per player, so a new lash replaces their last. */
	private record MoveKey(UUID owner) {}

	/**
	 * S_ARM_FX kind 14 to the clients that know the whip's moves (ArmProtocol.FX_WHIP_MOVE: {@code arg} is the move, ids and
	 * positions as its Javadoc says).
	 */
	private void sendMove(ArmContext c, Player p, int lvl, int seed, int ticks, int[] ids, long[] positions, int move) {
		sendMove(c, p, lvl, seed, ticks, ids, positions, move, ArmProtocol.fxRadius(ArmProtocol.FX_WHIP_MOVE));
	}

	/** As above, to viewers within {@code radius} (a whiff is shown only to those close by). */
	private void sendMove(ArmContext c, Player p, int lvl, int seed, int ticks, int[] ids, long[] positions, int move, double radius) {
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_WHIP_MOVE, lvl, ticks, seed, ids, positions, move), radius);
	}

	/**
	 * The server's own picture for players whose game doesn't draw the whip's moves: a tendril from the arm to {@code at} and
	 * a puff there. {@code drawsNative} leaves out the viewers who get the move itself.
	 */
	private void fallbackLine(ArmContext c, Player p, Location at, int seed, Predicate<Player> drawsNative) {
		Location tip = SkinEffects.armTip(p);
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendril(p, new MoveKey(p.getUniqueId()), tip, at, "stick", SkinEffects.ARM_SEGMENTS, LASH_FROM_PX, LASH_TO_PX, true, drawsNative);
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) {
			vanilla.tendril(p, tip, at, VanillaEffects.ARM_GREY, seed, now(c), false, drawsNative);
			vanilla.armBurst(at, drawsNative);
		}
	}

	private static void sound(Location at, Sound sound, float volume, float pitch) {
		sound(at, sound, SoundCategory.PLAYERS, volume, pitch);
	}

	/** Every move's sound is the server's, played to everyone near, mod or not (the client draws; it doesn't need to play it). */
	private static void sound(Location at, Sound sound, SoundCategory category, float volume, float pitch) {
		World world = at.getWorld();
		if (world != null) world.playSound(at, sound, category, volume, pitch);
	}

	/** The middle of a creature's body. */
	private static Location bodyOf(LivingEntity t) {
		return t.getBoundingBox().getCenter().toLocation(t.getWorld());
	}

	/**
	 * A point's block as S_ARM_FX packs positions (BlockPos.asLong, the protocol's, which the client unpacks). Paper's own
	 * Block.getBlockKey packs the bits differently, so it must never go on the wire.
	 */
	public static long endBlockKey(Vector end) {
		return new Pos(end.getBlockX(), end.getBlockY(), end.getBlockZ()).asLong();
	}

	// ------------------------------------------------------------------ the module's own events

	private final class Events implements Listener {
		/** The line: a cast, a catch, a reel-in against a block, a creature on the hook. Vanilla has done its part; this adds the arm's. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onFish(PlayerFishEvent event) {
			ArmContext c = ctx;
			Player p = event.getPlayer();
			if (c == null || !holdsWhip(c, p, event.getHand())) return;
			int lvl = level(c.level(p, FORM));
			FishHook hook = event.getHook();
			switch (event.getState()) {
				case FISHING -> bill(c, p, CAST_KEY, lvl, "cast");
				// a catch: first the chance of biomass (so a lucky one can pay for itself), then the cast it is billed as
				case CAUGHT_FISH -> {
					fishBiomass(c, p);
					bill(c, p, CAST_KEY, lvl, "catch");
				}
				case IN_GROUND -> reelAgainstBlock(c, p, hook, lvl);
				// in the air, a hook stuck on a wall or a ceiling reels in too
				case REEL_IN -> {
					Anchor a = anchors.get(p.getUniqueId());
					if (a != null && a.hook().equals(hook.getUniqueId()) || stuck(hook)) reelAgainstBlock(c, p, hook, lvl);
				}
				case CAUGHT_ENTITY -> {
					if (event.getCaught() instanceof LivingEntity t) {
						String said = pullCreature(c, p, t, hook.getLocation(), lvl, true, YANK_KEY);
						if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " reeled in " + t.getName() + ": " + said);
					}
				}
				default -> {}
			}
		}

		/** A whip's cast hook hit a block: it sticks there, on whatever face it hit (held each tick, see {@link #tick}). */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onHookHit(ProjectileHitEvent event) {
			ArmContext c = ctx;
			if (c == null || !(event.getEntity() instanceof FishHook hook) || !(hook.getShooter() instanceof Player p)) return;
			Block block = event.getHitBlock();
			if (block == null || event.getHitBlockFace() == null || !holdsWhip(c, p, EquipmentSlot.HAND)) return;
			// the hook is where it was at the start of the tick it hit: carry it along its move to the surface it hit
			Location at = hook.getLocation().clone();
			Vector vel = hook.getVelocity();
			if (vel.lengthSquared() > 1e-6) {
				RayTraceResult rt = hook.getWorld().rayTraceBlocks(at, vel.clone().normalize(), vel.length() + 1.5, FluidCollisionMode.NEVER, true);
				if (rt != null && rt.getHitBlockFace() != null) {
					Vector n = rt.getHitBlockFace().getDirection().multiply(0.1);
					at = rt.getHitPosition().add(n).toLocation(hook.getWorld(), at.getYaw(), at.getPitch());
				}
			}
			anchors.put(p.getUniqueId(), new Anchor(hook.getUniqueId(), at, block, event.getHitBlockFace()));
			hook.setVelocity(new Vector());
			hook.setGravity(false);
		}

		/** The blow a move is striking now: did its damage event run, and did anything cancel it (a claim, a PvP rule). */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onBlow(EntityDamageByEntityEvent event) {
			if (blowOwner == null || event.getDamager() != blowOwner || event.getEntity() != blowTarget) return;
			blowSeen = true;
			blowCancelled = event.isCancelled();
		}

		/** A snared creature doesn't teleport out of the lash (an enderman's blink, a shulker's hop). */
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onTeleport(EntityTeleportEvent event) {
			if (!(event.getEntity() instanceof LivingEntity t) || t instanceof Player || !isSnared(t)) return;
			event.setCancelled(true);
		}

		/** Nor does a snared player, by ender pearl or chorus fruit. */
		@SuppressWarnings("removal") // chorus fruit's cause is marked for removal but still what the server reports
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onPlayerTeleport(PlayerTeleportEvent event) {
			PlayerTeleportEvent.TeleportCause cause = event.getCause();
			if ((cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) && isSnared(event.getPlayer())) {
				event.setCancelled(true);
			}
		}

		/** A player who leaves is forgotten: their move cooldowns (only the ones that have run out), a reel, a stuck hook, a no-fall window and any snare on them. */
		@EventHandler(priority = EventPriority.MONITOR)
		public void onQuit(PlayerQuitEvent event) {
			forget(event.getPlayer());
		}

		/** The rod wears when it reels; the arm's wear is biomass, billed above (the guard also cancels it, so this is only a backstop). */
		@EventHandler(priority = EventPriority.HIGH)
		public void onItemDamage(PlayerItemDamageEvent event) {
			if (!event.isCancelled() && FORM.equals(formOf(event.getItem()))) event.setCancelled(true);
		}
	}

	/**
	 * A fish on the whip's line may give a little biomass (fish-biomass-chance, fish-biomass): through the gameplay mode,
	 * so the player's bar and its gain pulse update as for a kill. Nothing for arms that are free (Classic, creative).
	 */
	private void fishBiomass(ArmContext c, Player p) {
		long milli = settings.fishGainMilli(ThreadLocalRandom.current().nextDouble());
		if (milli <= 0) return;
		long gained = c.gain(p, milli);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " fish biomass: " + (gained > 0 ? "+" + gained + " milli" : "none (free arm or biomass full)"));
		}
	}

	private void reelAgainstBlock(ArmContext c, Player p, FishHook hook, int lvl) {
		Anchor a = anchors.remove(p.getUniqueId());
		if (a != null && a.hook().equals(hook.getUniqueId())) {
			boolean pulled = reel(c, p, a.at().toVector(), a.block(), a.face(), lvl, true, PULL_KEY);
			if (pulled && c.config().report()) c.report(settings.name() + ": " + p.getName() + " reeling to " + a.block().getType().getKey().getKey() + " (" + a.face() + ")");
			return;
		}
		boolean pulled = grapple(c, p, hook.getLocation().toVector(), lvl, true, PULL_KEY);
		if (pulled && c.config().report()) c.report(settings.name() + ": " + p.getName() + " grappled " + amount(center(p).distance(hook.getLocation().toVector())) + " blocks");
	}

	/** The whip is in this player's hand: a live session's own arm, in the main hand, the hand the line was cast from. */
	private boolean holdsWhip(ArmContext c, Player p, EquipmentSlot hand) {
		if (hand != EquipmentSlot.HAND) return false;
		long token = c.token(p);
		if (token == 0) return false;
		ItemStack main = p.getInventory().getItemInMainHand();
		if (!FORM.equals(formOf(main))) return false;
		try {
			Long nonce = main.getPersistentDataContainer().get(ARM_NONCE, PersistentDataType.LONG);
			return nonce != null && nonce == token && c.stillArmed(p, token);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** An arm item's form id, null when it isn't a rod or carries no arm tag. */
	private static @Nullable String formOf(@Nullable ItemStack stack) {
		if (stack == null || stack.getType() != Material.FISHING_ROD) return null;
		try {
			return stack.getPersistentDataContainer().get(ARM_FORM, PersistentDataType.STRING);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The hook, in the air, is against a block (a wall or a ceiling): it is a grapple point like one in the ground. */
	private static boolean stuck(FishHook hook) {
		if (hook.getState() != FishHook.HookState.UNHOOKED || hook.isInWater()) return false;
		World world = hook.getWorld();
		BoundingBox box = hook.getBoundingBox().expand(STUCK_MARGIN);
		for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
			for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
				if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
				for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
					Block b = world.getBlockAt(x, y, z);
					if (b.isPassable()) continue;
					for (BoundingBox part : b.getCollisionShape().getBoundingBoxes()) {
						if (part.clone().shift(x, y, z).overlaps(box)) return true;
					}
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ the module's face

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null) return;
		int now = now(c);
		// stuck hooks stay where they hit (a wall or a ceiling would otherwise let them fall)
		for (Iterator<Map.Entry<UUID, Anchor>> it = anchors.entrySet().iterator(); it.hasNext(); ) {
			Anchor a = it.next().getValue();
			if (!(c.plugin().getServer().getEntity(a.hook()) instanceof FishHook hook) || !hook.isValid()) {
				it.remove();
				continue;
			}
			if (hook.getLocation().distanceSquared(a.at()) > 1e-4) hook.teleport(a.at());
			hook.setVelocity(new Vector());
		}
		for (Iterator<Map.Entry<UUID, Reel>> it = reels.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Reel> e = it.next();
			Player p = c.plugin().getServer().getPlayer(e.getKey());
			if (p == null || reelTick(p, e.getValue(), now)) it.remove();
		}
		tickMoves(c, now);
		// a cooldown record is kept while it runs, even after its owner quit; this is what lets it go once it is up
		if (now % COOLDOWN_PRUNE_PERIOD == 0 && !cooldowns.isEmpty()) {
			cooldowns.values().removeIf(cds -> cds.expired(now));
		}
		if (noFall.isEmpty()) return;
		for (Iterator<Map.Entry<UUID, Integer>> it = noFall.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Integer> e = it.next();
			Player p = c.plugin().getServer().getPlayer(e.getKey());
			if (p == null || !p.isOnline() || p.isDead() || now > e.getValue()) {
				it.remove();
				continue;
			}
			p.setFallDistance(0);
		}
	}

	/** One tick of the moves: snares end (or hold the creature where it stands), scared mobs are told where to run again. */
	private void tickMoves(ArmContext c, int now) {
		if (!snares.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Snare>> it = snares.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, Snare> e = it.next();
				Entity found = c.plugin().getServer().getEntity(e.getKey());
				// an entity that is gone (dead, unloaded) took its transient modifiers with it
				if (!(found instanceof LivingEntity t) || !alive(t)) {
					it.remove();
					continue;
				}
				if (now >= e.getValue().until()) {
					release(t);
					it.remove();
					continue;
				}
				if (!(t instanceof Player)) {
					// held in place: a walker's walk is gone with its speed and what is left is a shove, which the lash takes back;
					// one that flies, floats or hops moves without those attributes, so its motion is stopped on every axis it has
					Vector v = t.getVelocity();
					double[] w = WhipMoves.heldVelocity(e.getValue().kind(), v.getX(), v.getY(), v.getZ(), t.isInWater() || t.isInLava());
					if (Math.abs(w[0] - v.getX()) > 1e-3 || Math.abs(w[1] - v.getY()) > 1e-3 || Math.abs(w[2] - v.getZ()) > 1e-3) {
						t.setVelocity(new Vector(w[0], w[1], w[2]));
					}
				}
				if ((now + e.getKey().hashCode()) % SNARE_PUFF_TICKS == 0) snarePuff(t);
			}
		}
		if (!fleeing.isEmpty() && now % FLEE_PERIOD == 0) {
			for (Iterator<Map.Entry<UUID, Flee>> it = fleeing.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<UUID, Flee> e = it.next();
				if (!(c.plugin().getServer().getEntity(e.getKey()) instanceof Mob m) || !alive(m) || now >= e.getValue().until()) {
					it.remove();
					continue;
				}
				if (!snares.containsKey(m.getUniqueId())) fleeStep(m, e.getValue());
			}
		}
	}

	/** A few grey motes round a held creature, for players whose game doesn't draw the whip's moves (the others see the lash itself). */
	private void snarePuff(LivingEntity t) {
		BoundingBox box = t.getBoundingBox();
		Location mid = box.getCenter().toLocation(t.getWorld());
		Particle.DustOptions dust = new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.1F);
		for (Player v : t.getWorld().getPlayers()) {
			if (movers.test(v) || v.getLocation().distanceSquared(mid) > 48 * 48) continue;
			v.spawnParticle(Particle.DUST, mid, 6, box.getWidthX() / 2, box.getHeight() / 3, box.getWidthZ() / 2, 0, dust);
		}
	}

	@Override
	public void abortAll() {
		noFall.clear();
		anchors.clear();
		reels.clear();
		fleeing.clear();
		// a held creature is let go (the modifier is never saved, but a reload mustn't leave one frozen until its time is up)
		for (UUID id : snares.keySet()) {
			if (ctx != null && ctx.plugin().getServer().getEntity(id) instanceof LivingEntity t) release(t);
		}
		snares.clear();
	}

	@Override
	public String specialName(int level) {
		return settings.name();
	}

	/** What F costs at the creature it is mostly pointed at (the Snare); the wheel and the shared gate know no more than one number. */
	@Override
	public long costMilli(int level) {
		return costMilli(WhipMoves.Move.SNARE, level);
	}

	private long costMilli(WhipMoves.Move move, int level) {
		ArmContext c = ctx;
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(move.costKey(), level(level));
	}

	/** The manager's cooldown for the form is only the short one every move shares; each move's own is kept here ({@link WhipMoves.Cooldowns}). */
	@Override
	public int cooldownTicks(int level) {
		return settings.moves().sharedCooldownTicks();
	}

	/**
	 * The level in a few words for the upgrade tree, after the manager's "Diamond strength · ": "Lure I · Hits 6 blocks ·
	 * Snare 2 s · Disarm · Crack 6 blocks · Whirl 4 blocks". The rod's enchantments first (they are what grows for fishing),
	 * then the left-click's reach and the four moves.
	 */
	@Override
	public Lines describeLines(int level) {
		int lvl = level(level);
		int lure = ArmTiers.lure(FORM, lvl), luck = ArmTiers.luckOfTheSea(FORM, lvl);
		StringBuilder out = new StringBuilder();
		if (lure > 0) out.append("Lure ").append(ArmItemSpecs.roman(lure));
		if (luck > 0) out.append(" + Luck of the Sea ").append(ArmItemSpecs.roman(luck));
		ArmContext c = ctx;
		double hit = ArmTiers.BASE_REACH + (c == null ? ArmConfig.DEFAULTS : c.config()).extraReach(FORM, lvl);
		WhipSettings.Moves mv = settings.moves();
		// the enchantments, the reach and the moves with their numbers are all the level's, so all of it stays
		return Lines.builder().always(out.toString()).always(WhipMoves.describe(hit, mv.snareSeconds().at(lvl), mv.crackRange().at(lvl), mv.whirlRadius().at(lvl))).build();
	}

	/** The item's F line: each move with what it costs ("Snare 6 · Disarm 4 · Crack 6 · Whirl (sneak) 10 biomass"). */
	@Override
	public Lines loreLines(int level, boolean survival, java.util.function.LongUnaryOperator charge) {
		int lvl = level(level);
		long[] cost = new long[WhipMoves.Move.values().length];
		for (WhipMoves.Move m : WhipMoves.Move.values()) cost[m.ordinal()] = charge.applyAsLong(costMilli(m, lvl));
		return WhipMoves.loreLines(cost[0], cost[1], cost[2], cost[3], survival);
	}

	// ------------------------------------------------------------------ helpers

	/** A level in 1..3 (0 reads as iron: a formed arm always has one). */
	private static int level(int level) {
		return Math.clamp(level, 1, 3);
	}

	/** The middle of the player's body: where the line drags from. */
	private static Vector center(Player p) {
		return p.getBoundingBox().getCenter();
	}

	private static Vector centerOf(Entity e) {
		return e.getBoundingBox().getCenter();
	}

	/** The server's own tick count, as the other specials time against. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	private static boolean alive(LivingEntity e) {
		return e.isValid() && !e.isDead();
	}

	/** Ticks as seconds to a tenth, for "Snare ready in 3.2 s". */
	private static String seconds(int ticks) {
		return SurvivalMode.number(Math.round(ticks / 2.0) / 10.0) + " s";
	}

	/** A health amount for the report, to two decimals. */
	private static String amount(double v) {
		return SurvivalMode.number(Math.round(v * 100) / 100.0);
	}
}
