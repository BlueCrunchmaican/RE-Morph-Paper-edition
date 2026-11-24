package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.FellPlanner;
import dev.morphreborn.paper.arm.rules.FellPlanner.Outcome;
import dev.morphreborn.paper.arm.rules.FellPlanner.Plan;
import dev.morphreborn.paper.arm.rules.FellPlanner.TreeGrid;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Fell's walk over a map of blocks (a tree is not a log wall), and the dice for the axe's extra log. */
class FellPlannerTest {
	private static final Pos BASE = new Pos(0, 0, 0);

	/** Logs by position with their family; crown blocks (natural leaves); logs the arm can't break. */
	private static final class Grid implements TreeGrid {
		final Map<Pos, String> logs = new LinkedHashMap<>();
		final Set<Pos> crown = new HashSet<>();
		final Set<Pos> unbreakable = new HashSet<>();

		Grid log(String family, int x, int y, int z) {
			logs.put(new Pos(x, y, z), family);
			return this;
		}

		Grid leaf(int x, int y, int z) {
			crown.add(new Pos(x, y, z));
			return this;
		}

		@Override
		public @Nullable String group(int x, int y, int z) {
			return logs.get(new Pos(x, y, z));
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			return !unbreakable.contains(new Pos(x, y, z));
		}

		@Override
		public boolean canopy(int x, int y, int z) {
			return crown.contains(new Pos(x, y, z));
		}
	}

	/** An oak: a trunk of {@code height} logs and a crown of leaves around its top two. */
	private static Grid oak(int height) {
		Grid g = new Grid();
		for (int y = 0; y < height; y++) g.log("oak", 0, y, 0);
		for (int y = height - 2; y < height; y++) {
			g.leaf(1, y, 0).leaf(-1, y, 0).leaf(0, y, 1).leaf(0, y, -1);
		}
		g.leaf(0, height, 0);
		return g;
	}

	private static Plan plan(Grid g, int max) {
		return FellPlanner.plan(g, BASE, max, 8, 32, 3);
	}

	@Test
	void aNaturalTreeFallsTopFirst() {
		Plan p = plan(oak(6), 64);
		assertEquals(Outcome.OK, p.outcome());
		assertEquals(6, p.logs().size());
		assertEquals(new Pos(0, 5, 0), p.logs().get(0), "the top log goes first");
		assertEquals(BASE, p.logs().get(5), "the base goes last");
		for (int i = 1; i < p.logs().size(); i++) assertTrue(p.logs().get(i).y() <= p.logs().get(i - 1).y(), "never rises");
		assertTrue(p.canopy() >= 3);
	}

	@Test
	void aLogWallWithoutLeavesIsNotATree() {
		Grid g = new Grid();
		for (int x = 0; x < 6; x++) {
			for (int y = 0; y < 4; y++) g.log("oak", x, y, 0);
		}
		Plan p = plan(g, 64);
		assertEquals(Outcome.NOT_A_TREE, p.outcome());
		assertTrue(p.logs().isEmpty(), "nothing falls");
		assertEquals(0, p.canopy());
		assertEquals(24, p.scanned());
	}

	@Test
	void aTrunkWhoseLeavesAreNotNaturalIsNotATree() {
		// the grid answers canopy() false for persistent (placed) leaves, so a pillar dressed with them has no canopy
		Grid g = new Grid();
		for (int y = 0; y < 5; y++) g.log("oak", 0, y, 0);
		assertEquals(Outcome.NOT_A_TREE, plan(g, 64).outcome());
	}

	@Test
	void theCanopyNeedsMinLeaves() {
		Grid g = new Grid();
		for (int y = 0; y < 4; y++) g.log("oak", 0, y, 0);
		g.leaf(1, 3, 0).leaf(-1, 3, 0);
		assertEquals(Outcome.NOT_A_TREE, plan(g, 64).outcome(), "two leaves are not a tree");
		assertEquals(2, plan(g, 64).canopy());
		g.leaf(0, 4, 0);
		assertEquals(Outcome.OK, plan(g, 64).outcome(), "three are");
	}

	@Test
	void aLeafTouchingTwoLogsCountsOnce() {
		Grid g = new Grid();
		g.log("oak", 0, 0, 0).log("oak", 1, 1, 0);
		g.leaf(1, 0, 0).leaf(0, 1, 0); // each touches both logs by a face
		Plan p = plan(g, 64);
		assertEquals(2, p.canopy());
		assertEquals(Outcome.NOT_A_TREE, p.outcome());
	}

	@Test
	void leavesTouchingOnlyAtACornerDoNotCount() {
		Grid g = new Grid();
		g.log("oak", 0, 0, 0).leaf(1, 1, 1).leaf(-1, -1, -1).leaf(1, -1, 1);
		assertEquals(0, plan(g, 64).canopy());
	}

	@Test
	void aBlockThatIsNoLogIsRefused() {
		Plan p = FellPlanner.plan(oak(4), new Pos(5, 5, 5), 64, 8, 32, 3);
		assertEquals(Outcome.NOT_A_LOG, p.outcome());
		assertTrue(p.logs().isEmpty());
	}

	@Test
	void anotherFamilyIsLeftStanding() {
		Grid g = oak(5);
		for (int y = 0; y < 5; y++) g.log("birch", 1, y, 0);
		Plan p = plan(g, 64);
		assertEquals(5, p.logs().size());
		assertTrue(p.logs().stream().allMatch(l -> l.x() == 0));
	}

	@Test
	void diagonalBranchesAreFollowed() {
		Grid g = oak(4);
		g.log("oak", 1, 4, 1).log("oak", 2, 5, 2);
		assertEquals(6, plan(g, 64).logs().size());
	}

	@Test
	void theCapKeepsTheNearestLogs() {
		Plan p = plan(oak(20), 5);
		assertEquals(Outcome.OK, p.outcome());
		assertEquals(5, p.logs().size());
		for (Pos l : p.logs()) assertTrue(l.y() <= 4, l + " is one of the nearest to the base");
		assertEquals(new Pos(0, 4, 0), p.logs().get(0), "still top first");
	}

	@Test
	void theCrownIsCountedOverTheWholeTreeNotJustTheCap() {
		// a tall trunk whose leaves are all at its top: the cap holds back the top, the tree is still a tree
		Plan p = plan(oak(30), 8);
		assertEquals(Outcome.OK, p.outcome());
		assertEquals(8, p.logs().size());
		assertTrue(p.canopy() >= 3);
	}

	@Test
	void stopsAtTheBoxSideways() {
		Grid g = oak(3);
		for (int x = 1; x <= 12; x++) g.log("oak", x, 0, 0);
		Plan p = FellPlanner.plan(g, BASE, 128, 8, 32, 3);
		assertTrue(p.logs().stream().allMatch(l -> Math.abs(l.x()) <= 8), "within the radius");
		assertEquals(3 + 8, p.logs().size());
	}

	@Test
	void stopsAtTheBoxUpAndDown() {
		Grid g = oak(40);
		g.leaf(1, 5, 0).leaf(-1, 5, 0).leaf(0, 5, 1); // some leaves within the box (a tall tree's crown is far above it)
		Plan p = FellPlanner.plan(g, BASE, 128, 8, 10, 3);
		assertEquals(Outcome.OK, p.outcome());
		assertTrue(p.logs().stream().allMatch(l -> l.y() <= 10));
		assertEquals(11, p.logs().size());
	}

	@Test
	void aMidTrunkStartTakesTheLogsBelowToo() {
		Plan p = FellPlanner.plan(oak(8), new Pos(0, 4, 0), 64, 8, 32, 3);
		assertEquals(8, p.logs().size());
	}

	@Test
	void logsTheArmCantBreakStopTheWalk() {
		Grid g = oak(6);
		g.leaf(1, 1, 0).leaf(-1, 1, 0).leaf(0, 1, 1);
		g.unbreakable.add(new Pos(0, 2, 0));
		Plan p = plan(g, 64);
		// the walk doesn't go through a block it can't break, so the part above it is never reached
		assertEquals(Outcome.OK, p.outcome());
		assertEquals(Set.of(BASE, new Pos(0, 1, 0)), new HashSet<>(p.logs()));
	}

	@Test
	void anUnbreakableStartIsRefused() {
		Grid g = oak(4);
		g.unbreakable.add(BASE);
		assertEquals(Outcome.UNBREAKABLE, plan(g, 64).outcome());
	}

	@Test
	void theSamePlanEveryTime() {
		assertEquals(plan(oak(12), 64), plan(oak(12), 64));
	}

	@Test
	void breakTicksCountEightATick() {
		assertEquals(FellPlanner.FIRST_BREAK_TICK, FellPlanner.breakTick(0, 8));
		assertEquals(FellPlanner.FIRST_BREAK_TICK, FellPlanner.breakTick(7, 8));
		assertEquals(FellPlanner.FIRST_BREAK_TICK + 1, FellPlanner.breakTick(8, 8));
		assertEquals(FellPlanner.FIRST_BREAK_TICK + 15, FellPlanner.breakTick(127, 8));
		assertEquals(FellPlanner.FIRST_BREAK_TICK + 5, FellPlanner.breakTick(5, 0), "a zero rate counts as one a tick");
	}

	// ------------------------------------------------------------------ the extra log

	@Test
	void theExtraLogRollMeanIsTheChance() {
		// seeds from a spread of positions and ticks, as real breaks come
		for (double chance : new double[] {0.2, 0.3, 0.4}) {
			int n = 200_000, hits = 0;
			for (int i = 0; i < n; i++) {
				int x = (i * 7) % 211 - 100, y = (i / 211) % 97 - 30, z = (i * 13) % 307 - 150;
				if (FellPlanner.extraLog(chance, x, y, z, 1000L + i / 3)) hits++;
			}
			assertEquals(chance, hits / (double) n, 0.01, "mean at chance " + chance);
		}
	}

	@Test
	void theExtraLogRollIsFairForNeighboursInOneTick() {
		// one tick, a whole forest's worth of neighbouring blocks: no streaks from the seeding
		int n = 0, hits = 0;
		for (int x = 0; x < 100; x++) {
			for (int y = 0; y < 40; y++) {
				for (int z = 0; z < 50; z++) {
					n++;
					if (FellPlanner.extraLog(0.3, x, y, z, 12345)) hits++;
				}
			}
		}
		assertEquals(0.3, hits / (double) n, 0.01);
	}

	@Test
	void theExtraLogRollIsSeeded() {
		for (int i = 0; i < 50; i++) {
			ThreadLocalRandom r = ThreadLocalRandom.current();
			int x = r.nextInt(-500, 500), y = r.nextInt(-64, 320), z = r.nextInt(-500, 500);
			long tick = r.nextLong(1, 1_000_000);
			assertEquals(FellPlanner.extraLog(0.3, x, y, z, tick), FellPlanner.extraLog(0.3, x, y, z, tick), "the same break rolls the same way");
			double v = FellPlanner.roll(x, y, z, tick);
			assertTrue(v >= 0 && v < 1);
		}
	}

	@Test
	void theExtraLogEdges() {
		assertFalse(FellPlanner.extraLog(0, 1, 2, 3, 4));
		assertFalse(FellPlanner.extraLog(-1, 1, 2, 3, 4));
		assertTrue(FellPlanner.extraLog(1, 1, 2, 3, 4));
		assertFalse(FellPlanner.extraLog(Double.NaN, 1, 2, 3, 4));
	}

	@Test
	void anEmptyWorldHasNothingToFell() {
		assertEquals(Outcome.NOT_A_LOG, FellPlanner.plan(new Grid(), BASE, 8, 8, 32, 3).outcome());
		assertEquals(1, FellPlanner.CRACK_LEAD);
	}
}
