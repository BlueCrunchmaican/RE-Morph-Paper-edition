package dev.morphreborn.paper.arm.rules;

import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The flint and steel accessory's small rules (my design, 2026-11-16, 8.0.0-beta.2): the S_ARM_FX numbers, which blows count
 * as melee hits that light their target, and which blocks a right-click may light. Pure, so each is unit tested without a server.
 */
public final class FlintRules {
	/** Flint's place in the accessory ring (ArmProtocol.ACCESSORIES: lantern 0, shield 1, spyglass 2, flint 3), in bits 8-11 of an FX_ACCESSORY arg. */
	public static final int PLACE = 3;
	/** FX_ACCESSORY move: a creature was set alight (a melee hit, or a bow's or crossbow's bolt). */
	public static final int FX_IGNITE = 1;
	/** FX_ACCESSORY move: a block was lit like flint and steel does (a campfire, a candle, TNT, a portal frame). */
	public static final int FX_LIGHT = 2;
	/**
	 * FX_ACCESSORY move: the striker was switched on, a cosmetic strike of the knuckles (no ids, no positions). my decision,
	 * 2026-11-16: the server's encoding of the flint's moves stands (0x301 ignite, 0x302 light) and this one is added to it.
	 */
	public static final int FX_SPARK = 3;

	private FlintRules() {}

	/** S_ARM_FX kind FX_ACCESSORY {@code arg} for a flint move: the place in bits 8-11, the move in bits 0-7 (as ShieldRules.fxArg does for the shield). */
	public static int fxArg(int move) {
		return (PLACE << 8) | (move & 0xFF);
	}

	/** The move in an FX_ACCESSORY {@code arg}, or 0 when the arg is not flint's. */
	public static int fxMove(int arg) {
		return (arg >> 8 & 0xF) == PLACE ? arg & 0xFF : 0;
	}

	/**
	 * Whether a damage event is the owner's own melee hit: a plain or sweeping attack (a bow's arrow or thorns are other causes), or a blow
	 * of the new weapons whose damage type Bukkit has no cause for (the spear's jab and charge, the mace's smash), struck by the owner
	 * themself ({@code directIsOwner}: the damage's direct entity, so not an arrow or a thrown item).
	 *
	 * @param attackCause the cause is ENTITY_ATTACK or ENTITY_SWEEP_ATTACK
	 * @param damageTypeKey the damage type's key, "minecraft:player_attack"
	 */
	public static boolean meleeBlow(boolean attackCause, String damageTypeKey, boolean directIsOwner) {
		return directIsOwner && (attackCause || ArmTiers.isWeaponDamageType(damageTypeKey));
	}

	/**
	 * Whether a hit of this final damage kills a creature of this health. It is worked out before the damage is applied (the event's final
	 * damage is already net of absorption), and the blow that kills lights nothing: it would be billed for a creature that is gone.
	 */
	public static boolean lethal(double health, double finalDamage) {
		return health - finalDamage <= 0;
	}

	/**
	 * The tag a shot carries when it leaves lit (my design, 2026-11-24): who shot it and at what level of flint and steel, "uuid:level". The
	 * owner is in it so that a shot a shield turned round (it changes shooter) burns nobody and bills nobody on the new shooter's flint, and the
	 * level so that switching the accessory to another level in flight does not change what the shot was lit with.
	 */
	public static String stamp(UUID owner, int level) {
		return owner + ":" + Math.clamp(level, 1, 3);
	}

	/** Who a stamp says shot it, or null for a tag that isn't one. */
	public static @Nullable UUID stampOwner(@Nullable String stamp) {
		if (stamp == null) return null;
		int at = stamp.lastIndexOf(':');
		if (at <= 0) return null;
		try {
			return UUID.fromString(stamp.substring(0, at));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The level in a stamp, 1 to 3; 1 for a tag that has none. */
	public static int stampLevel(@Nullable String stamp) {
		if (stamp == null) return 1;
		int at = stamp.lastIndexOf(':');
		if (at < 0) return 1;
		try {
			return Math.clamp(Integer.parseInt(stamp.substring(at + 1)), 1, 3);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	/** Whether a stamp was made for this shooter (a shot that changed hands is not theirs to burn). */
	public static boolean stampedBy(@Nullable String stamp, UUID shooter) {
		return shooter.equals(stampOwner(stamp));
	}

	/**
	 * Whether a shot a player just launched is lit: the flint and steel is on, the shot isn't one of the arm's own bolts (the bow's burn by its
	 * Fire tip, the crossbow's are lit by its module), and it carries no stamp already (it is never lit twice). The thrown trident, the arm's
	 * included, is a shot like any other.
	 *
	 * @param armBolt the shot is the bow arm's bolt or the crossbow arm's bolt or spine
	 * @param stamped it already carries a stamp
	 */
	public static boolean litAtLaunch(boolean flintOn, boolean armBolt, boolean stamped) {
		return flintOn && !armBolt && !stamped;
	}

	/**
	 * Whether a lit shot sets what it hurt alight: its stamp is the shooter's and the shot isn't burning itself. A shot already on fire (a Flame
	 * bow's arrow, one that flew through lava) is left to the game, which sets the target alight for 5 s by itself, free (the player has paid for
	 * that fire already).
	 */
	public static boolean shotBurns(@Nullable String stamp, UUID shooter, int shotFireTicks) {
		return stampedBy(stamp, shooter) && shotFireTicks <= 0;
	}

	/**
	 * Whether a game mode may light blocks with the arm: not Adventure (vanilla flint and steel can't be used on a block there unless the
	 * item says it may break it, and the game's refusal comes after PlayerInteractEvent, which a region plugin only guarding building never
	 * sees) and not Spectator. Pure so the rule is tested; {@code gameMode} is {@code GameMode.name()}.
	 */
	public static boolean mayLightIn(String gameMode) {
		return gameMode.equals("SURVIVAL") || gameMode.equals("CREATIVE");
	}

	/**
	 * Vanilla's spawn protection (DedicatedServer.isUnderSpawnProtection, checked against 26.3's source 2026-11-16): a non-op player may not
	 * build within {@code radius} blocks (a square, the larger of the two axes) of the world spawn, when the server has any ops and the radius
	 * is above 0. The game's own check sits ahead of the use of an item, so a block a player may not touch there is one the flint must not
	 * light either, whatever PlayerInteractEvent's listeners say.
	 *
	 * @param spawnWorld the block is in the world the spawn belongs to
	 * @param anyOps the server has at least one op
	 * @param dx the block's distance from the spawn along x (any sign)
	 */
	public static boolean underSpawnProtection(boolean spawnWorld, boolean anyOps, boolean op, int radius, int dx, int dz) {
		return spawnWorld && anyOps && !op && radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) <= radius;
	}

	/** What a right-click with the flint on may light. */
	public enum Target {
		NONE,
		/** An unlit campfire or soul campfire that isn't waterlogged. */
		CAMPFIRE,
		/** An unlit candle (any colour) that isn't waterlogged. */
		CANDLE,
		/** An unlit cake with a candle on it. */
		CANDLE_CAKE,
		/** TNT: primed, as flint and steel primes it. */
		TNT,
		/** Obsidian: fire is set on the face that was clicked, which lights a portal frame the same way flint and steel does. */
		PORTAL_FRAME
	}

	/**
	 * What the clicked block is, for lighting: the block's key ("minecraft:campfire"), and its lit and waterlogged state (false for a block
	 * that has neither). Anything else, and anything already lit or under water, is NONE, so a right-click on it does what it always did.
	 */
	public static Target target(String blockKey, boolean lit, boolean waterlogged) {
		String key = blockKey.toLowerCase(Locale.ROOT);
		if (key.equals("minecraft:tnt")) return Target.TNT;
		if (key.equals("minecraft:obsidian")) return Target.PORTAL_FRAME;
		if (lit || waterlogged) return Target.NONE;
		if (key.equals("minecraft:campfire") || key.equals("minecraft:soul_campfire")) return Target.CAMPFIRE;
		if (key.equals("minecraft:candle") || key.startsWith("minecraft:") && key.endsWith("_candle") && !key.endsWith("_candle_cake")) return Target.CANDLE;
		if (key.equals("minecraft:candle_cake") || key.endsWith("_candle_cake")) return Target.CANDLE_CAKE;
		return Target.NONE;
	}
}
