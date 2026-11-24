package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Rake's timeline and the wire numbers for its S_ARM_FX (kind FX_CLAWS), apart from the server so both are tested. The client
 * keeps a copy of the constants and the unpacking: neither side may renumber.
 * <p>
 * The timeline, in ticks after F: slash {@code i} (0 first) swings from {@code GAP * i} to {@code GAP * (i + 1)} and lands its hits
 * at {@link #hitTick}, {@code 1 + GAP * i}, a third of the way through the swing, so the first creature is hit on the tick after
 * F as Draining Cut's first cut is. Slashes alternate direction: slash 0 sweeps from the player's right to their left (the
 * vanilla swing, mirrored for a left-handed player), slash 1 back, and so on.
 */
public final class ClawMath {
	/** FX_CLAWS {@code arg & 0xF}: which of the claws' effects a message is. Never renumber. */
	public static final int MOVE_RAKE = 1, MOVE_SLASH = 2, MOVE_BLEED = 3, MOVE_BLEED_PULSE = 4, MOVE_BLEED_END = 5;
	/** The ticks one slash takes, and so the ticks between two slashes. */
	public static final int GAP = 3;
	/** The ticks from F to the first slash's hits. */
	public static final int FIRST_HIT = 1;
	/** The mask of the move in {@code arg}. */
	public static final int MOVE_MASK = 0xF;

	private ClawMath() {}

	/** The tick after F slash {@code index} lands its hits on. */
	public static int hitTick(int index) {
		return FIRST_HIT + GAP * index;
	}

	/** How many ticks a Rake of {@code slashes} slashes takes, F to the end of the last swing. */
	public static int totalTicks(int slashes) {
		return GAP * Math.max(1, slashes);
	}

	/** Whether slash {@code index} sweeps right to left (true) or left to right, for a right-handed player; a left-handed one is mirrored. */
	public static boolean rightToLeft(int index) {
		return index % 2 == 0;
	}

	/**
	 * Whether a slash really went through (2026-11-16): the health fell, or its damage event ran and nothing cancelled it. A hit only
	 * swallowed by another attacker's hurt window (no event, no damage) is not: it starts no bleed and opens no window for the next slash.
	 */
	public static boolean dealt(double damageTaken, boolean eventSeen, boolean eventCancelled) {
		return damageTaken > 0 || eventSeen && !eventCancelled;
	}

	/**
	 * The creatures a Rake takes from the whole fan ({@code fan}: ArcTargets.select with no cut, in sweep order), at most {@code max}, the
	 * nearest first (2026-11-16, review): each is asked {@code allowed} (the world's and the protection plugins' rules) in that order, and
	 * only those that pass count towards {@code max}, so claim-protected animals nearer than a valid mob never use up its place.
	 * {@code allowed} is only asked when it matters: always when {@code always} (F, which must know there is someone to rake), else only
	 * when the fan holds more than {@code max} (the slashes test each hit as it lands), and never for a creature beyond the cut. Returned in
	 * sweep order.
	 */
	public static List<ArcTargets.Hit> cut(List<ArcTargets.Hit> fan, int max, boolean always, IntPredicate allowed) {
		if (max <= 0 || fan.isEmpty()) return List.of();
		if (!always && fan.size() <= max) return fan;
		List<ArcTargets.Hit> byDistance = new ArrayList<>(fan);
		byDistance.sort(Comparator.comparingDouble(ArcTargets.Hit::dist).thenComparingDouble(ArcTargets.Hit::angleNorm).thenComparingInt(ArcTargets.Hit::id));
		List<ArcTargets.Hit> kept = new ArrayList<>(Math.min(max, byDistance.size()));
		for (ArcTargets.Hit h : byDistance) {
			if (kept.size() >= max) break;
			if (allowed.test(h.id())) kept.add(h);
		}
		kept.sort(Comparator.comparingDouble(ArcTargets.Hit::angleNorm).thenComparingDouble(ArcTargets.Hit::dist).thenComparingInt(ArcTargets.Hit::id));
		return List.copyOf(kept);
	}

	// ---- FX_CLAWS arg

	/**
	 * MOVE_RAKE: bits 0-3 the move, 4-7 how many slashes, 8-17 the fan's width in degrees, bit 18 the player is left-handed,
	 * 19-23 the ticks per slash (ClawMath.GAP), 24-29 the reach in tenths of a block.
	 */
	public static int rakeArg(int slashes, int arcDeg, boolean leftHanded, int reachTenths) {
		return MOVE_RAKE | (Math.clamp(slashes, 0, 15) << 4) | (Math.clamp(arcDeg, 0, 1023) << 8) | (leftHanded ? 1 << 18 : 0) | (GAP << 19)
				| (Math.clamp(reachTenths, 0, 63) << 24);
	}

	/** MOVE_SLASH: bits 0-3 the move, 4-7 which slash this is (0 first). */
	public static int slashArg(int index) {
		return MOVE_SLASH | (Math.clamp(index, 0, 15) << 4);
	}

	/** MOVE_BLEED: bits 0-3 the move, 4-11 the ticks between pulses. */
	public static int bleedArg(int intervalTicks) {
		return MOVE_BLEED | (Math.clamp(intervalTicks, 0, 255) << 4);
	}

	public static int move(int arg) {
		return arg & MOVE_MASK;
	}

	public static int slashes(int arg) {
		return (arg >> 4) & 0xF;
	}

	public static int arcDeg(int arg) {
		return (arg >> 8) & 0x3FF;
	}

	public static boolean leftHanded(int arg) {
		return (arg & (1 << 18)) != 0;
	}

	public static int gap(int arg) {
		return (arg >> 19) & 0x1F;
	}

	public static int reachTenths(int arg) {
		return (arg >> 24) & 0x3F;
	}

	public static int slashIndex(int arg) {
		return (arg >> 4) & 0xF;
	}

	public static int bleedInterval(int arg) {
		return (arg >> 4) & 0xFF;
	}
}
