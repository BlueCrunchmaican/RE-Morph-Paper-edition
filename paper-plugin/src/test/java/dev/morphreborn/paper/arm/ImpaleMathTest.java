package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ImpaleMath;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;

/** The spear's lunge on a fake ground: where it ends, what stops it, how long it takes, and whether the spear then reaches. */
class ImpaleMathTest {
	/**
	 * A heightfield for a player 0.6 wide and 1.8 tall: {@code floor(x, z)} is the surface at a point, {@code ceiling(x, z)} the
	 * underside of whatever is overhead. The body fits when no point of its footprint (the four corners and the middle) has a floor
	 * above the feet or a ceiling under the head; it is held up when some point has a floor within a sliver under the feet.
	 */
	private record Ground(DoubleBinaryOperator floor, DoubleBinaryOperator ceiling) implements ImpaleMath.Space {
		static final double HALF = 0.3, HEIGHT = 1.8, SLICE = 0.03;

		static Ground flat() {
			return new Ground((x, z) -> 0, (x, z) -> 100);
		}

		private double[][] feet(double x, double z) {
			return new double[][] {{x, z}, {x - HALF, z - HALF}, {x + HALF, z - HALF}, {x - HALF, z + HALF}, {x + HALF, z + HALF}};
		}

		@Override
		public boolean fits(double x, double y, double z) {
			for (double[] p : feet(x, z)) {
				if (floor.applyAsDouble(p[0], p[1]) > y + 1e-9 || ceiling.applyAsDouble(p[0], p[1]) < y + HEIGHT - 1e-9) return false;
			}
			return true;
		}

		@Override
		public boolean supported(double x, double y, double z) {
			for (double[] p : feet(x, z)) {
				double f = floor.applyAsDouble(p[0], p[1]);
				if (f >= y - SLICE && f <= y + 1e-9) return true;
			}
			return false;
		}
	}

	private static ImpaleMath.Lunge go(Ground g, double wanted) {
		return ImpaleMath.lunge(0, 0, 0, 1, 0, wanted, g);
	}

	@Test
	void openGroundTakesTheWholeLunge() {
		ImpaleMath.Lunge l = go(Ground.flat(), 4);
		assertEquals(4, l.distance(), 1e-9);
		assertEquals(4, l.x(), 1e-9);
		assertEquals(0, l.y(), 1e-9);
		assertEquals(0, l.z(), 1e-9);
		assertEquals(0, go(Ground.flat(), 0).distance(), "no distance wanted is no lunge");
	}

	@Test
	void theLungeFollowsItsDirectionOnTheGround() {
		ImpaleMath.Lunge l = ImpaleMath.lunge(10, 0, -5, 0, -1, 3, Ground.flat());
		assertEquals(3, l.distance(), 1e-9);
		assertEquals(10, l.x(), 1e-9);
		assertEquals(-8, l.z(), 1e-9);
		assertEquals(0, ImpaleMath.lunge(0, 0, 0, 0, 0, 3, Ground.flat()).distance(), "no direction, no lunge");
	}

	@Test
	void aWallStopsItBeforeTheBodyIsInsideIt() {
		// a wall from x = 3: the body (0.3 either side) may not reach past a middle of 2.7
		Ground wall = new Ground((x, z) -> x >= 3 ? 10 : 0, (x, z) -> 100);
		ImpaleMath.Lunge l = go(wall, 5);
		assertTrue(l.distance() <= 2.7 + 1e-9 && l.distance() >= 2.5 - 1e-9, "stopped at " + l.distance());
		assertEquals(0, l.y(), 1e-9);
	}

	@Test
	void aThinWallIsNotSkipped() {
		Ground thin = new Ground((x, z) -> x >= 2.0 && x < 2.2 ? 10 : 0, (x, z) -> 100);
		assertTrue(go(thin, 5).distance() < 2.0, "the 0.25 steps overlap the 0.6 wide body");
	}

	@Test
	void itClimbsWhatAWalkClimbsAndNoMore() {
		Ground slab = new Ground((x, z) -> x >= 2 ? 0.5 : 0, (x, z) -> 100);
		ImpaleMath.Lunge up = go(slab, 4);
		assertEquals(4, up.distance(), 1e-9, "a half block is stepped up");
		assertEquals(0.5, up.y(), 1e-9, "and the feet end on it");
		Ground block = new Ground((x, z) -> x >= 2 ? 1 : 0, (x, z) -> 100);
		assertTrue(go(block, 4).distance() < 2, "a whole block needs a jump");
	}

	@Test
	void itWalksDownAStepButNotOffALedge() {
		Ground step = new Ground((x, z) -> x >= 2 ? -1 : 0, (x, z) -> 100);
		ImpaleMath.Lunge down = go(step, 4);
		assertEquals(4, down.distance(), 1e-9, "a block down is walked down");
		assertEquals(-1, down.y(), 1e-9);
		Ground cliff = new Ground((x, z) -> x >= 2 ? -2 : 0, (x, z) -> 100);
		ImpaleMath.Lunge edge = go(cliff, 4);
		assertTrue(edge.distance() <= 2.5, "it stops at the edge: " + edge.distance());
		assertEquals(0, edge.y(), 1e-9, "and stays on the ground it was on");
	}

	@Test
	void aLowCeilingStopsIt() {
		Ground low = new Ground((x, z) -> 0, (x, z) -> x >= 2 ? 1.7 : 100);
		assertTrue(go(low, 4).distance() < 2.0);
	}

	@Test
	void itNeverGoesOnOverAGap() {
		// the module refuses to start one without ground under the feet; the math itself stops when the last of the body's footing is gone
		Ground gap = new Ground((x, z) -> x >= 0.5 ? -5 : 0, (x, z) -> 100);
		assertTrue(go(gap, 4).distance() <= 0.75 + 1e-9);
	}

	@Test
	void flatDirectionIgnoresHeightAndFallsBackWhenLookingStraightUpOrDown() {
		assertArrayEquals(new double[] {1, 0}, ImpaleMath.flat(3, 5, 0, 0, 1), 1e-9);
		assertArrayEquals(new double[] {1, 0}, ImpaleMath.flat(0, -4, -0.0001, 1, 0), 1e-9, "straight down: the facing");
		assertArrayEquals(new double[] {0, 1}, ImpaleMath.flat(0, 1, 0, 0, 7), 1e-9, "the fallback is made a unit");
		assertArrayEquals(new double[] {0, 0}, ImpaleMath.flat(0, 1, 0, 0, 0), 1e-9);
		double[] d = ImpaleMath.flat(3, 0, 4, 0, 0);
		assertEquals(0.6, d[0], 1e-9);
		assertEquals(0.8, d[1], 1e-9);
	}

	@Test
	void theLungeEndsASpearsLengthShortOfTheCreature() {
		assertEquals(0, ImpaleMath.gap(0.1, 0, 0.3), "inside the creature's width is no gap");
		assertEquals(3, ImpaleMath.gap(3, 0, -0.7), 1e-9, "a negative half width counts as none");
		assertEquals(4.5, ImpaleMath.gap(3, 4, 0.5), 1e-9);
		assertEquals(3, ImpaleMath.wanted(8, 3), 1e-9, "never past the level's lunge");
		assertEquals(0.5, ImpaleMath.wanted(1.5, 3), 1e-9, "stops short by the spear's body");
		assertEquals(0, ImpaleMath.wanted(0.4, 3), 1e-9, "already close: no lunge");
		assertEquals(0, ImpaleMath.wanted(8, -1), 1e-9);
		assertEquals(ImpaleMath.MAX_DISTANCE, ImpaleMath.wanted(40, 500), 1e-9, "no setting plans a lunge the movement can't cover");
	}

	@Test
	void theLongestLungeCanBeCoveredInItsTimePlusTheSpare() {
		// ideal motion: every tick moves as far as the speed set that tick (a real lunge is slower, never faster)
		double d = ImpaleMath.MAX_DISTANCE, pos = 0;
		int ticks = ImpaleMath.lungeTicks(d), e = 0;
		while (!ImpaleMath.over(pos, d, e, ticks) && e < 50) {
			pos += ImpaleMath.speed(d - pos);
			e++;
		}
		assertTrue(pos >= d - 0.15, "arrived, not timed out: " + pos + " of " + d + " at tick " + e);
		assertEquals(ImpaleMath.MAX_LUNGE_TICKS, ticks);
	}

	@Test
	void theSpearOnlyStrikesWhatIsWithinThreeBlocksUpOrDown() {
		assertTrue(ImpaleMath.withinHeight(64, 64));
		assertTrue(ImpaleMath.withinHeight(64, 67));
		assertTrue(ImpaleMath.withinHeight(64, 61));
		assertFalse(ImpaleMath.withinHeight(64, 67.01), "a bat five blocks overhead isn't reached, so F costs nothing");
		assertFalse(ImpaleMath.withinHeight(64, 58));
	}

	@Test
	void aLaggyClientsLungeGetsItsPingAsSpareTicks() {
		assertEquals(0, ImpaleMath.lagTicks(0));
		assertEquals(0, ImpaleMath.lagTicks(-5));
		assertEquals(1, ImpaleMath.lagTicks(30));
		assertEquals(4, ImpaleMath.lagTicks(200));
		assertEquals(ImpaleMath.MAX_LAG_TICKS, ImpaleMath.lagTicks(5000), "a bound, so a stuck client can't hold a lunge open");
		// at 200 ms the lunge's clock still runs when the velocity first shows: a 1-tick lunge is not over at elapsed 4
		assertTrue(ImpaleMath.over(0, 3, 4, 1), "without the allowance it ends at elapsed 4, before any movement shows");
		assertFalse(ImpaleMath.over(0, 3, 4, 1 + ImpaleMath.lagTicks(200)));
	}

	/** A lunge on a client whose ping is {@code lag} ticks (even): what the server sets reaches it half a ping later, and what is read of it is half a ping old. Returns where the client ends up. */
	private static double lungeAtPing(double d, int lag, boolean reckon) {
		int ticks = ImpaleMath.lungeTicks(d), n = 60, half = lag / 2;
		double[] pos = new double[n + 1], vel = new double[n + 1];
		boolean ended = false;
		for (int t = 0; t < n; t++) {
			double read = pos[Math.max(0, t - half)];
			if (!ended) {
				double flight = 0;
				for (int i = 1; i <= Math.min(lag, t); i++) flight += vel[t - i];
				double ahead = reckon ? ImpaleMath.reckoned(read, flight) : read;
				if (ImpaleMath.over(ahead, d, t, ticks + lag)) {
					ended = true;
				} else {
					vel[t] = ImpaleMath.speed(d - ahead);
				}
			}
			pos[t + 1] = pos[t] + (t - half >= 0 ? vel[t - half] : 0);
		}
		return pos[n];
	}

	@Test
	void aHighPingLungeStopsWhereItWasPlannedNotAPingPast() {
		double d = 4;
		assertTrue(lungeAtPing(d, 4, false) > d + 1.5, "without the reckoning the 200 ms lunge runs on well past its end");
		for (int lag : new int[] {2, 4, 8}) {
			double end = lungeAtPing(d, lag, true);
			assertTrue(end <= d + 0.8 && end >= d - 0.8, "lag " + lag + " ended at " + end + " of " + d);
		}
		assertEquals(3.5, ImpaleMath.reckoned(2, 1.5), 1e-9);
		assertEquals(2, ImpaleMath.reckoned(2, 0), 1e-9, "nothing is in flight at the start");
		assertEquals(2, ImpaleMath.reckoned(2, -1), 1e-9, "never behind the reading");
	}

	@Test
	void theSpearReachesWhatIsWithinItsReachOfTheEnd() {
		assertTrue(ImpaleMath.reaches(5, 3, 2.5), "2 blocks left of the gap");
		assertTrue(ImpaleMath.reaches(5.5, 3, 2.5), "exactly the reach");
		assertFalse(ImpaleMath.reaches(5.6, 3, 2.5));
		assertFalse(ImpaleMath.reaches(5, 1, 2.5), "a lunge a wall cut short");
	}

	@Test
	void aLungeIsASnapAtASteadySpeed() {
		assertEquals(3, ImpaleMath.lungeTicks(3));
		assertEquals(5, ImpaleMath.lungeTicks(5), "5 blocks at 1.1 a tick");
		assertEquals(1, ImpaleMath.lungeTicks(0.1));
		assertEquals(1, ImpaleMath.lungeTicks(0));
		assertEquals(ImpaleMath.MAX_LUNGE_TICKS, ImpaleMath.lungeTicks(50), "never a ride");
		assertEquals(ImpaleMath.LUNGE_SPEED, ImpaleMath.speed(10), 1e-9);
		assertEquals(ImpaleMath.MIN_SPEED, ImpaleMath.speed(0.01), 1e-9);
		assertEquals(0.6, ImpaleMath.speed(1.0), 1e-9, "eased over the last stretch");
	}

	@Test
	void theLungeIsOverWhenItIsThereOrOutOfTime() {
		assertTrue(ImpaleMath.over(3.9, 4, 1, 4), "within a hair");
		assertFalse(ImpaleMath.over(2, 4, 3, 4));
		assertFalse(ImpaleMath.over(2, 4, 6, 4), "two ticks of lag allowed");
		assertTrue(ImpaleMath.over(2, 4, 7, 4));
		assertTrue(ImpaleMath.over(0, 0, 0, 1), "no distance is over at once");
	}

	@Test
	void theVerticalPushKeepsTheFeetOnTheGroundOrTakesThemUpAStep() {
		assertEquals(-0.0784, ImpaleMath.vertical(0), 1e-9, "level: gravity's own pull");
		assertTrue(ImpaleMath.vertical(0.5) > 0 && ImpaleMath.vertical(0.5) <= 0.3);
		assertTrue(ImpaleMath.vertical(-1) < 0 && ImpaleMath.vertical(-1) >= -0.4);
	}
}
