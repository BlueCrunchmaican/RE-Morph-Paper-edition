package dev.morphreborn.paper.arm.rules;

/**
 * The numbers behind the mace arm's Skyward Leap (my design, 2026-11-16, 8.0.0-beta.2): how fast to launch a player to
 * rise a given height, how long the rise takes, how much room a ceiling leaves, and what vanilla's smash adds for a fall. Pure,
 * so it is unit tested without a server.
 * <p>
 * The player's vertical motion is vanilla's: each tick the body moves by its velocity, then {@code v = (v - 0.08) * 0.98}. A jump
 * (0.42) rises 1.25 blocks by this rule, which {@code MaceMathTest} pins, so a launch speed that rises 8 blocks here rises 8 blocks
 * in the game. The fall that follows is vanilla's too (fall distance counts the descent), and the smash on the next mace hit reads
 * that fall distance (MaceItem.getAttackDamageBonus), which is why the leap never touches it.
 */
public final class MaceMath {
	/** Vanilla's gravity and drag on a player in the air, per tick. */
	private static final double GRAVITY = 0.08, DRAG = 0.98;
	/** The fastest launch the search tries (a rise of far more than any setting allows). */
	private static final double MAX_SPEED = 6;
	/** The leap is refused when a ceiling leaves less rise than this (blocks): a hop that low is no leap and no smash. */
	public static final double MIN_RISE = 2.0;
	/** How far under a ceiling the apex is kept, so the head doesn't strike it (blocks). */
	public static final double CEILING_MARGIN = 0.25;
	/** The most the settings may ask for (blocks). */
	public static final double MAX_HEIGHT = 40;
	/**
	 * How far past the planned height a landing's fall may be and still count as the leap's own (blocks): the fall is counted from the apex,
	 * so landing a step or two lower than the launch (a stair, a ledge) adds that much, and the forgiveness must not shrink for it. Over it
	 * the leaper stepped off something (a cliff, a tower) and the plan is all that is forgiven (review 2026-11-16).
	 */
	public static final double FALL_MARGIN = 2.0;
	/** F needs the body on the ground: a fall distance at or over this is a fall in progress, not a leap from rest (blocks). */
	public static final double MAX_START_FALL = 0.5;
	/** The largest {@link #fallTenths}: 100 blocks. */
	public static final int MAX_TENTHS = 1000;

	private MaceMath() {}

	/** How high a body launched upward at {@code speed} (blocks per tick) rises before it turns, in blocks. */
	public static double riseHeight(double speed) {
		double y = 0, v = speed;
		while (v > 0) {
			y += v;
			v = (v - GRAVITY) * DRAG;
		}
		return y;
	}

	/** How many ticks a body launched at {@code speed} rises for (the tick it turns on included). */
	public static int riseTicks(double speed) {
		int n = 0;
		double v = speed;
		while (v > 0) {
			n++;
			v = (v - GRAVITY) * DRAG;
		}
		return n;
	}

	/** The launch speed (blocks per tick) that rises {@code height} blocks; 0 for a height of nothing. Found by bisection on {@link #riseHeight}. */
	public static double launchSpeed(double height) {
		if (!(height > 0)) return 0;
		double lo = 0, hi = MAX_SPEED;
		for (int i = 0; i < 60; i++) {
			double mid = (lo + hi) / 2;
			if (riseHeight(mid) < height) lo = mid;
			else hi = mid;
		}
		return hi;
	}

	/**
	 * The height to plan for: what the level asks ({@code wanted}), or, under a ceiling that leaves only {@code free} blocks, just short
	 * of it ({@link #CEILING_MARGIN}). 0 when what is left is under {@link #MIN_RISE}: there is no room to leap.
	 */
	public static double plan(double wanted, double free) {
		double h = free >= wanted ? wanted : free - CEILING_MARGIN;
		return h >= MIN_RISE ? h : 0;
	}

	/**
	 * What is left of a landing's fall damage after the leap's forgiveness. A fall within the plan plus {@link #FALL_MARGIN} is the leap's
	 * own landing: all of it forgiven (0). A longer one (the leaper went off an edge mid-air) is forgiven only the planned height's worth,
	 * a block of fall being a point of damage. Armour and enchantments scale what is left as they do any fall.
	 *
	 * @param damage the event's base fall damage
	 * @param fall the fall distance at the event
	 * @param planned the height the leap was planned to rise
	 */
	public static double fallDamageAfter(double damage, double fall, double planned) {
		if (!(damage > 0)) return 0;
		if (fall <= planned + FALL_MARGIN) return 0;
		return Math.max(0, damage - Math.max(0, planned));
	}

	/**
	 * What vanilla's mace adds to a smash for a fall of {@code fall} blocks (MaceItem.getAttackDamageBonus without enchantments):
	 * 4 a block for the first 3, 2 a block to 8, then 1 a block. Nothing at or under the smash threshold of 1.5 blocks.
	 */
	public static double smashBonus(double fall) {
		if (fall <= 1.5) return 0;
		if (fall <= 3) return 4 * fall;
		if (fall <= 8) return 12 + 2 * (fall - 3);
		return 22 + fall - 8;
	}

	/**
	 * A fall distance as the landing effect carries it: tenths of a block, rounded, kept to 0..{@link #MAX_TENTHS} (a varInt that is
	 * never negative, and a size the clients can scale a shockwave by without a runaway number).
	 */
	public static int fallTenths(double fall) {
		if (!(fall > 0)) return 0;
		return (int) Math.min(MAX_TENTHS, Math.round(fall * 10));
	}
}
