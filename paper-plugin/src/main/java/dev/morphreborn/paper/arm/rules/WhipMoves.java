package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;

/**
 * The whip's F moves (2026-11-16: the whip rework), pure: which move F is, whether a disarmed item drops,
 * who a cone or a spin catches, where a creature is pushed and how a flung item flies, and each move's own cooldown.
 * The module (arm/special/WhipLash) finds the creature the player aims at, asks here what that makes F, and applies
 * what comes back; nothing in this class knows about the server, so every rule is unit tested.
 */
public final class WhipMoves {
	/** What F does: the four moves, each with its own cost row in survival.yml arm-cost.specials and its own cooldown. */
	public enum Move {
		SNARE("whip-snare", "Snare"), DISARM("whip-disarm", "Disarm"), CRACK("whip-crack", "Crack"), WHIRL("whip-whirl", "Whirl");

		private final String costKey, label;

		Move(String costKey, String label) {
			this.costKey = costKey;
			this.label = label;
		}

		/** The move's row in arm-cost.specials. */
		public String costKey() {
			return costKey;
		}

		/** The move's name for the player. */
		public String label() {
			return label;
		}
	}

	/** A pull on a flung item: how fast it flies to its owner (blocks a tick) and the lift that keeps it off the ground. */
	public static final double FLING_SPEED = 0.55, FLING_LIFT = 0.2;
	/** How far a whiffed Crack or Whirl (nothing in reach: free) is shown and heard, in blocks: a quiet swing, never a server-wide boom. */
	public static final double WHIFF_RADIUS = 16;

	/** How a snared creature is held: what the snare must take from it, since the speed attributes alone don't stop everything. */
	public enum HoldKind {
		/** Walks: the speed and jump attributes hold it, and any shove along the ground is taken back. */
		WALKER,
		/** Flies or floats with no gravity (phantom, ghast, bee...): held with all three axes of its motion zeroed each tick. */
		HOVER,
		/** Swims (fish, squid, dolphin, guardian): all three axes zeroed while in water, falling as normal out of it. */
		SWIMMER,
		/** Hops (slime, magma cube): no sliding along the ground and no hop off it. */
		HOPPER,
		/** Never walks, only teleports (shulker): held by cancelling the teleport, which is done for every snared creature. */
		ROOTED,
		/** Not a creature with a mind of its own to hold (an armour stand, a mob with its AI off): it isn't snared, and F aimed at it is a Crack. */
		NONE
	}

	private static final java.util.Set<String> HOVERING = java.util.Set.of("minecraft:phantom", "minecraft:ghast", "minecraft:happy_ghast", "minecraft:vex",
			"minecraft:bee", "minecraft:allay", "minecraft:parrot", "minecraft:bat", "minecraft:blaze", "minecraft:breeze");
	private static final java.util.Set<String> SWIMMING = java.util.Set.of("minecraft:cod", "minecraft:salmon", "minecraft:pufferfish",
			"minecraft:tropical_fish", "minecraft:squid", "minecraft:glow_squid", "minecraft:dolphin", "minecraft:guardian", "minecraft:elder_guardian",
			"minecraft:tadpole", "minecraft:axolotl", "minecraft:nautilus", "minecraft:zombie_nautilus");
	private static final java.util.Set<String> HOPPING = java.util.Set.of("minecraft:slime", "minecraft:magma_cube");

	/**
	 * How a creature is held by the snare, decided by what it is (every living entity carries the speed and jump attributes,
	 * even an armour stand, so having them says nothing): only a mob with its AI on moves of its own accord and so can be held
	 * ({@code isMob}, {@code hasAi}); anything else is {@link HoldKind#NONE}. Of those the kinds named here are known, and any
	 * other that the world doesn't pull down ({@code hasGravity} false) is a floater too; the rest walk.
	 */
	public static HoldKind holdKind(String typeKey, boolean isMob, boolean hasAi, boolean hasGravity) {
		if (!isMob || !hasAi) return HoldKind.NONE;
		if (typeKey.equals("minecraft:shulker")) return HoldKind.ROOTED;
		if (HOVERING.contains(typeKey)) return HoldKind.HOVER;
		if (SWIMMING.contains(typeKey)) return HoldKind.SWIMMER;
		if (HOPPING.contains(typeKey)) return HoldKind.HOPPER;
		return hasGravity ? HoldKind.WALKER : HoldKind.HOVER;
	}

	/**
	 * The velocity a held creature is left with this tick, from the one it has: a walker or a hopper keeps only its fall (a
	 * snared creature never rises, on the ground or off it: a hop is made inside the creature's own tick, after this one, so
	 * the ground can't be what decides it), a floater is stopped on every axis (a swimmer only while it is in a liquid, so out
	 * of one it falls), and a rooted or unholdable one is left as it is.
	 *
	 * @return {x, y, z}
	 */
	public static double[] heldVelocity(HoldKind kind, double vx, double vy, double vz, boolean inLiquid) {
		return switch (kind) {
			case WALKER, HOPPER -> new double[] {0, Math.min(vy, 0), 0};
			case HOVER -> new double[] {0, 0, 0};
			case SWIMMER -> inLiquid ? new double[] {0, 0, 0} : new double[] {0, vy, 0};
			case ROOTED, NONE -> new double[] {vx, vy, vz};
		};
	}

	/**
	 * Whether a Snare or Disarm may touch a creature, given what the server said about it: a player in a world with PvP
	 * off is never affected, and a test hit from the owner that a protection plugin cancelled stops it for anything (mobs
	 * too: a claim's animals are safe from the lash as they are from a sword).
	 */
	public static boolean mayAffect(boolean targetIsPlayer, boolean worldPvp, boolean testCancelled) {
		if (targetIsPlayer && !worldPvp) return false;
		return !testCancelled;
	}

	/**
	 * Whether a scoreboard team stops the lash: two players on the same team, with that team's friendly fire off. Vanilla
	 * ({@code Player.canHarmPlayer}) stops such a hit before any damage event, so the test hit never sees it.
	 */
	public static boolean teamProtects(boolean sameTeam, boolean allowFriendlyFire) {
		return sameTeam && !allowFriendlyFire;
	}

	/**
	 * Whether a move's blow landed, for the push and the scare that follow it. Damage dealt, or a damage event that ran and was
	 * not cancelled, is a hit; one that was cancelled is not. When no event ran at all, vanilla's invulnerability frames swallowed
	 * the hit (the creature was hurt a moment ago, {@code hadFrames}): it counts when the target was allowed ({@code allowed}:
	 * the test hit and the world's and team's rules said yes), since the lash was struck and billed all the same.
	 */
	public static boolean blowLanded(double damageTaken, boolean eventSeen, boolean eventCancelled, boolean hadFrames, boolean allowed) {
		if (damageTaken > 0) return true;
		if (eventSeen) return !eventCancelled;
		return hadFrames && allowed;
	}

	/** Whether a viewer at squared distance {@code distSq} is within a whiff's {@link #WHIFF_RADIUS}. */
	public static boolean nearWhiff(double distSq) {
		return distSq <= WHIFF_RADIUS * WHIFF_RADIUS;
	}

	/** The hardest a push may be, in blocks a tick, however strong the setting and however near the creature. */
	public static final double MAX_PUSH = 3.0;
	/** A push lifts a creature a little, so friction doesn't eat it. */
	public static final double PUSH_LIFT = 0.28;

	private WhipMoves() {}

	/**
	 * What F is. Sneaking is the Whirl, whatever you aim at. Otherwise it is picked by what you aim at: a creature the lash
	 * can hold that is already snared by you is the Disarm, any other creature it can hold is the Snare, and nothing it can
	 * hold (nothing at all, a boss, a player the settings protect) is the Crack.
	 *
	 * @param holdable the aim found a creature the lash may take hold of (the Snare's rules)
	 * @param snaredByMe that creature is snared by this player right now
	 */
	public static Move pick(boolean sneaking, boolean holdable, boolean snaredByMe) {
		if (sneaking) return Move.WHIRL;
		if (!holdable) return Move.CRACK;
		return snaredByMe ? Move.DISARM : Move.SNARE;
	}

	/**
	 * Whether a disarmed item drops, as it would have if its owner had died holding it: a drop chance of 1 or more is
	 * guaranteed (an item a mob picked up, which vanilla gives chance 2), 0 or less never, anything between is rolled
	 * ({@code roll} in [0, 1): the item drops when it is under the chance). Anti-farm (2026-11-16): this is
	 * what stops a disarm giving more than killing would have. A player's items drop with chance 1.
	 */
	public static boolean disarmDrops(double dropChance, double roll) {
		if (!(dropChance > 0)) return false;
		if (dropChance >= 1) return true;
		return roll < dropChance;
	}

	/**
	 * Whether a creature is in a cone: its middle at offset ({@code dx}, {@code dy}, {@code dz}) from the eye, the cone
	 * pointing along ({@code lx}, {@code ly}, {@code lz}) (any length but zero), reaching {@code range} blocks and
	 * {@code halfAngleDeg} either side of the look. {@code margin} is the creature's half width: a wide one is caught by
	 * the edge of the cone, and one right at the eye always is. A zero look catches nothing.
	 */
	public static boolean inCone(double dx, double dy, double dz, double lx, double ly, double lz, double range, double halfAngleDeg, double margin) {
		double look = Math.sqrt(lx * lx + ly * ly + lz * lz);
		if (!(look > 1e-9)) return false;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double pad = Math.max(0, margin);
		if (dist > range + pad) return false;
		if (dist <= pad) return true;
		double cos = (dx * lx + dy * ly + dz * lz) / (dist * look);
		double angle = Math.toDegrees(Math.acos(Math.clamp(cos, -1, 1)));
		// a creature of half width m at distance d spans asin(m / d) either side of its middle
		double widen = Math.toDegrees(Math.asin(Math.min(1, pad / dist)));
		return angle <= Math.clamp(halfAngleDeg, 0, 180) + widen;
	}

	/** Whether a creature's middle is within {@code radius} of the owner's (the Whirl), its half width {@code margin} counted. */
	public static boolean inRadius(double dx, double dy, double dz, double radius, double margin) {
		double reach = radius + Math.max(0, margin);
		return dx * dx + dy * dy + dz * dz <= reach * reach;
	}

	/**
	 * The velocity that pushes a creature away from a point: horizontal along the gap at {@code strength} a tick, less
	 * by its knockback resistance (0 to 1: 1 is unmoved), with {@link #PUSH_LIFT} up so friction doesn't eat it. A creature
	 * right on the point goes straight up. Never faster than {@link #MAX_PUSH}.
	 *
	 * @return {x, y, z}
	 */
	public static double[] push(double dx, double dz, double strength, double resistance) {
		double keep = 1 - Math.clamp(resistance, 0, 1);
		double s = Math.min(MAX_PUSH, Math.max(0, strength)) * keep;
		double len = Math.sqrt(dx * dx + dz * dz);
		double vy = s > 0 ? PUSH_LIFT : 0;
		if (!(len > 1e-6)) return new double[] {0, vy, 0};
		return new double[] {dx / len * s, vy, dz / len * s};
	}

	/**
	 * The velocity a disarmed item flies at, from where it was ripped out toward its new owner: {@link #FLING_SPEED} a tick
	 * along the gap, with {@link #FLING_LIFT} up. A zero gap just rises.
	 *
	 * @return {x, y, z}
	 */
	public static double[] fling(double dx, double dy, double dz) {
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(len > 1e-6)) return new double[] {0, FLING_LIFT, 0};
		return new double[] {dx / len * FLING_SPEED, dy / len * FLING_SPEED + FLING_LIFT, dz / len * FLING_SPEED};
	}

	/**
	 * The item's F line after "F: ": each move named, with its cost when {@code showCosts} and any of them costs something:
	 * "Snare 6 · Disarm 4 · Crack 6 · Whirl (sneak) 10 biomass"; "Snare · Disarm · Crack · Whirl (sneak)" without. Costs are milli.
	 */
	public static String loreLine(long snareMilli, long disarmMilli, long crackMilli, long whirlMilli, boolean showCosts) {
		return loreLines(snareMilli, disarmMilli, crackMilli, whirlMilli, showCosts).text(true);
	}

	/** {@link #loreLine} as typed pieces for the Tooltips setting: the moves and what each costs stay, "(sneak)" (which key makes the whirl) is explanation. */
	public static Lines loreLines(long snareMilli, long disarmMilli, long crackMilli, long whirlMilli, boolean showCosts) {
		boolean costs = showCosts && (snareMilli > 0 || disarmMilli > 0 || crackMilli > 0 || whirlMilli > 0);
		long[] milli = {snareMilli, disarmMilli, crackMilli, whirlMilli};
		Lines.Builder b = Lines.builder();
		for (Move m : Move.values()) {
			b.always(m.label());
			if (m == Move.WHIRL) b.tipMore(" (sneak)");
			if (costs) b.alwaysMore(" " + ArmItemSpecs.biomass(milli[m.ordinal()]));
		}
		return costs ? b.alwaysMore(" biomass").build() : b.build();
	}

	/** The level's numbers in words, for the upgrade tree: "Hits 6 blocks · Snare 2 s · Disarm · Crack 6 blocks · Whirl 4 blocks". */
	public static String describe(double hitReach, double snareSeconds, double crackRange, double whirlRadius) {
		return "Hits " + number(hitReach) + " blocks · Snare " + number(snareSeconds) + " s · Disarm · Crack " + number(crackRange) + " blocks · Whirl "
				+ number(whirlRadius) + " blocks";
	}

	/** 6.0 as "6", 6.5 as "6.5". */
	static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}

	/**
	 * The moves' own cooldowns, per player: each move has its own, so a Snare doesn't lock the Crack. {@code start} is
	 * called when a move is paid for; a move that found nothing starts none. Ticks are the server's tick count.
	 */
	public static final class Cooldowns {
		private final int[] ready = new int[Move.values().length];

		/** Ticks until the move is ready (0: ready now). */
		public int left(Move move, int now) {
			return Math.max(0, ready[move.ordinal()] - now);
		}

		/** The move is ready again {@code ticks} from now. */
		public void start(Move move, int now, int ticks) {
			ready[move.ordinal()] = now + Math.max(0, ticks);
		}

		/** Every move is ready: nothing here still has to be remembered. */
		public boolean expired(int now) {
			return longestLeft(now) == 0;
		}

		/** The longest any move still has to wait (what a display of the whip as a whole would show). */
		public int longestLeft(int now) {
			int most = 0;
			for (Move m : Move.values()) most = Math.max(most, left(m, now));
			return most;
		}
	}
}
