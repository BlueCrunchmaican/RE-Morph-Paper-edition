package dev.morphreborn.paper.arm.rules;

/**
 * The spear's Impale (my design, 2026-11-16), pure: where the lunge ends, how fast it goes, and whether the spear then
 * reaches the creature. The module (arm/special/SpearImpale) reads the world through {@link Space}, asks here how far a
 * lunge along the ground can go, and drives the player along the answer; nothing in this class knows about the server, so every
 * rule is unit tested.
 * <p>
 * The lunge keeps to the ground, as a thrust does: it follows the horizontal part of the look, climbs only what a walking player
 * climbs unaided ({@link #STEP_UP}), comes down a step at most ({@link #MAX_DROP}) and stops at a ledge, a wall or a ceiling
 * rather than leaving the ground. It is checked in short steps ({@link #STEP}) so a thin wall is never skipped.
 */
public final class ImpaleMath {
	/** How far apart the lunge's checks are (blocks); smaller than the player's width, so no wall is stepped over. */
	public static final double STEP = 0.25;
	/**
	 * The most a lunge climbs in one step, and the most it drops. A walking player climbs 0.6 unaided (anything higher needs a
	 * jump); 9/16 is the tallest block shape under that (a bed), and, like every block shape, a multiple of {@link #DROP_STEP},
	 * so the feet come to rest exactly on a step's surface.
	 */
	public static final double STEP_UP = 0.5625, MAX_DROP = 1.0;
	/** Finer than any block shape (all are multiples of 1/16), so a surface is found where it is. */
	private static final double DROP_STEP = 0.0625;
	/** The lunge ends this far from the creature's near side when it can (the spear's body length). */
	public static final double STOP_GAP = 1.0;
	/** Blocks a tick at most, and the longest a lunge takes in ticks (a lunge is a snap, never a ride). */
	public static final double LUNGE_SPEED = 1.1;
	public static final int MAX_LUNGE_TICKS = 6;
	/** The slowest a lunge goes while it still has ground to cover, so it never crawls the last block. */
	public static final double MIN_SPEED = 0.2;
	/**
	 * The furthest a lunge can be planned: what its longest run covers at full speed. A setting past it could not be honoured (the
	 * lunge would time out short of the creature, and F would pay for a whiff every time; review 2026-11-16).
	 */
	public static final double MAX_DISTANCE = LUNGE_SPEED * MAX_LUNGE_TICKS;
	/** The most a creature's feet may be above or below the lunger's, for the spear to strike it (blocks). */
	public static final double MAX_DY = 3;
	/** The most ticks of a laggy client's delay a lunge is given on top of its own time (about 400 ms of ping). */
	public static final int MAX_LAG_TICKS = 8;
	private static final double EPS = 1e-6;

	private ImpaleMath() {}

	/**
	 * The ground as the lunge sees it, at a player's feet position: the body fits there (no block inside it), and something
	 * holds it up (a block under the feet, within a sliver of them). The module answers from the world's collisions.
	 */
	public interface Space {
		boolean fits(double x, double y, double z);

		boolean supported(double x, double y, double z);
	}

	/** Where a lunge ends: the distance travelled along the ground and the feet there. */
	public record Lunge(double distance, double x, double y, double z) {}

	/**
	 * The unit vector along the ground the lunge goes: the horizontal part of ({@code dx}, {@code dy}, {@code dz}), or when that
	 * is (nearly) nothing (looking straight up or down) the fallback direction. {0, 0} when both are nothing.
	 *
	 * @return {x, z}
	 */
	public static double[] flat(double dx, double dy, double dz, double fallbackX, double fallbackZ) {
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len > 1e-3) return new double[] {dx / len, dz / len};
		double fl = Math.sqrt(fallbackX * fallbackX + fallbackZ * fallbackZ);
		if (fl > 1e-6) return new double[] {fallbackX / fl, fallbackZ / fl};
		return new double[] {0, 0};
	}

	/** The horizontal gap from the lunger's middle to the creature's near side: its middle's distance less its half width, never under 0. */
	public static double gap(double dx, double dz, double halfWidth) {
		return Math.max(0, Math.sqrt(dx * dx + dz * dz) - Math.max(0, halfWidth));
	}

	/**
	 * How far the lunge wants to go to end {@link #STOP_GAP} short of the creature: the gap less that, within 0 and the level's
	 * {@code range} (itself never over {@link #MAX_DISTANCE}, whatever a setting says).
	 */
	public static double wanted(double gap, double range) {
		return Math.clamp(gap - STOP_GAP, 0, Math.clamp(range, 0, MAX_DISTANCE));
	}

	/**
	 * The creature's feet are close enough to the lunger's in height for the spear to strike (the same test at F, so nothing is paid for a
	 * lunge that cannot hit, and at the strike).
	 */
	public static boolean withinHeight(double lungerY, double creatureY) {
		return Math.abs(creatureY - lungerY) <= MAX_DY;
	}

	/** Ticks of delay to allow a client whose ping is {@code pingMillis}: its velocity comes back as movement about a ping later; 0 to {@link #MAX_LAG_TICKS}. */
	public static int lagTicks(int pingMillis) {
		return Math.clamp((int) Math.ceil(Math.max(0, pingMillis) / 50.0), 0, MAX_LAG_TICKS);
	}

	/** The spear reaches the creature from where the lunge ended: what is left of the gap is within {@code reach}. */
	public static boolean reaches(double gap, double travelled, double reach) {
		return gap - travelled <= reach + EPS;
	}

	/**
	 * Lunge from the feet at ({@code x}, {@code y}, {@code z}) along the unit direction ({@code dx}, {@code dz}), up to {@code wanted}
	 * blocks, stopping at the last step the ground allows: where the next step would be inside a block (a wall, a ceiling, a
	 * step too high to walk up), or would leave the ground (a ledge: nothing under it within {@link #MAX_DROP}).
	 */
	public static Lunge lunge(double x, double y, double z, double dx, double dz, double wanted, Space space) {
		double cx = x, cy = y, cz = z, travelled = 0;
		if (!(Math.abs(dx) + Math.abs(dz) > EPS)) return new Lunge(0, x, y, z);
		while (wanted - travelled > EPS) {
			double step = Math.min(STEP, wanted - travelled);
			double nx = cx + dx * step, nz = cz + dz * step;
			double ny = settle(space, nx, cy, nz, MAX_DROP);
			if (Double.isNaN(ny) && space.fits(cx, cy + STEP_UP, cz)) ny = settle(space, nx, cy + STEP_UP, nz, STEP_UP);
			if (Double.isNaN(ny)) break;
			cx = nx;
			cy = ny;
			cz = nz;
			travelled += step;
		}
		return new Lunge(travelled, cx, cy, cz);
	}

	/** The height the feet rest at when lowered from {@code y} by up to {@code maxDrop}, NaN when the body doesn't fit there or nothing holds it up in that range. */
	private static double settle(Space space, double x, double y, double z, double maxDrop) {
		if (!space.fits(x, y, z)) return Double.NaN;
		for (double d = 0; d <= maxDrop + EPS; d += DROP_STEP) {
			double at = y - d;
			if (d > 0 && !space.fits(x, at, z)) return Double.NaN;
			if (space.supported(x, at, z)) return at;
		}
		return Double.NaN;
	}

	/** How many ticks a lunge of {@code distance} blocks takes: at {@link #LUNGE_SPEED}, 1 to {@link #MAX_LUNGE_TICKS}. */
	public static int lungeTicks(double distance) {
		return Math.clamp((int) Math.ceil(Math.max(0, distance) / LUNGE_SPEED - EPS), 1, MAX_LUNGE_TICKS);
	}

	/** The lunge's speed with {@code remaining} blocks to go: eased over the last stretch, never over {@link #LUNGE_SPEED} or under {@link #MIN_SPEED}. */
	public static double speed(double remaining) {
		return Math.clamp(remaining * 0.6, MIN_SPEED, LUNGE_SPEED);
	}

	/**
	 * Where a laggy owner will have got to once what is on its way arrives: the position the server reads is about a ping old, and
	 * {@code inFlight} is the distance the speeds set during that ping (the last {@link #lagTicks} of them) will still move it. The lunge
	 * ends and eases by this, so a late reading can't carry the owner past the end the plan stopped at (a ledge, a wall, the creature).
	 * Never reckoned less than the reading itself. (The owner's high ping, 2026-11-16 second review.)
	 */
	public static double reckoned(double along, double inFlight) {
		return along + Math.max(0, inFlight);
	}

	/** The lunge is over: it has covered the distance (within a hair), or its ticks, with a couple spare for a lagging client, are up. */
	public static boolean over(double along, double distance, int elapsedTicks, int ticks) {
		return along >= distance - 0.15 || elapsedTicks > ticks + 2;
	}

	/**
	 * The vertical velocity that takes the feet to the lunge's end height: gravity's own pull when it is level (so the player
	 * stays on the ground), up over a step or down a stair otherwise.
	 */
	public static double vertical(double dy) {
		if (Math.abs(dy) < 0.05) return -0.0784;
		return Math.clamp(dy * 0.5, -0.4, 0.3);
	}
}
