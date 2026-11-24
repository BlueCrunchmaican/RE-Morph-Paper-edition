package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.GridView;
import dev.morphreborn.paper.arm.rules.VeinPlanner;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Vein Rip's walk over a map of blocks, and the tendril tree built from its order. */
class VeinPlannerTest {
	/** Blocks by position: their ore group, and those the arm can't break. Everything else is stone (no group). */
	private static final class Grid implements GridView {
		final Map<Pos, String> groups = new LinkedHashMap<>();
		final Set<Pos> unbreakable = new HashSet<>();
		int lookups;

		Grid put(String group, int x, int y, int z) {
			groups.put(new Pos(x, y, z), group);
			return this;
		}

		@Override
		public @Nullable String group(int x, int y, int z) {
			lookups++;
			return groups.get(new Pos(x, y, z));
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			return !unbreakable.contains(new Pos(x, y, z));
		}
	}

	private static final Pos ORIGIN = new Pos(0, 0, 0);

	private static List<Pos> plan(Grid g, int max) {
		return VeinPlanner.plan(g, ORIGIN, max, 6, 4 * max);
	}

	@Test
	void reachesDiagonals() {
		Grid g = new Grid().put("diamond", 0, 0, 0).put("diamond", 1, 1, 1).put("diamond", 2, 2, 2).put("diamond", 3, 2, 1);
		assertEquals(List.of(ORIGIN, new Pos(1, 1, 1), new Pos(2, 2, 2), new Pos(3, 2, 1)), plan(g, 8));
	}

	@Test
	void capKeepsTheNearest() {
		Grid g = new Grid();
		for (int x = -10; x <= 10; x++) g.put("iron", x, 0, 0);
		List<Pos> p = VeinPlanner.plan(g, ORIGIN, 5, 20, 100);
		assertEquals(5, p.size());
		for (Pos pos : p) assertTrue(Math.abs(pos.x()) <= 2, pos + " is one of the nearest");
	}

	@Test
	void staysInTheOffsetBox() {
		Grid g = new Grid();
		for (int x = 0; x <= 10; x++) g.put("iron", x, 0, 0);
		List<Pos> p = VeinPlanner.plan(g, ORIGIN, 32, 6, 128);
		assertEquals(7, p.size());
		assertTrue(p.stream().allMatch(pos -> pos.x() <= 6));
	}

	@Test
	void deepslateVariantsShareTheirGroupStoneDoesNot() {
		// diamond ore and deepslate diamond ore are both "diamond"; the stone between is in no group
		Grid g = new Grid().put("diamond", 0, 0, 0).put("diamond", 0, -1, 0).put("diamond", 0, -2, 1);
		List<Pos> p = plan(g, 8);
		assertEquals(3, p.size());
		assertEquals(0, plan(new Grid(), 8).size(), "stone is no vein");
	}

	@Test
	void neverCrossesGroups() {
		// diamond, then coal touching it, then more diamond touching only the coal
		Grid g = new Grid().put("diamond", 0, 0, 0).put("coal", 1, 0, 0).put("diamond", 2, 0, 0).put("coal", 0, 1, 0);
		assertEquals(List.of(ORIGIN), plan(g, 8));
	}

	@Test
	void deterministicOrder() {
		Grid a = new Grid();
		List<Pos> cells = new ArrayList<>();
		for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) cells.add(new Pos(x, y, z));
		for (Pos c : cells) a.put("gold", c.x(), c.y(), c.z());
		Grid b = new Grid();
		List<Pos> shuffled = new ArrayList<>(cells);
		Collections.shuffle(shuffled, new java.util.Random(5));
		for (Pos c : shuffled) b.put("gold", c.x(), c.y(), c.z());
		List<Pos> pa = VeinPlanner.plan(a, ORIGIN, 27, 6, 200), pb = VeinPlanner.plan(b, ORIGIN, 27, 6, 200);
		assertEquals(pa, pb);
		assertEquals(27, pa.size());
		assertEquals(ORIGIN, pa.get(0));
		// one step out: higher first, then x, then z
		assertEquals(new Pos(-1, 1, -1), pa.get(1));
		assertEquals(new Pos(-1, 1, 0), pa.get(2));
		assertEquals(new Pos(1, -1, 1), pa.get(26));
	}

	@Test
	void visitCapBoundsTheWalk() {
		// the start, eight unbreakable cells above it (looked at first), and a breakable chain further on
		Grid g = new Grid().put("debris", 0, 0, 0);
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				if (x == 0 && z == 0) continue;
				g.put("debris", x, 1, z);
				g.unbreakable.add(new Pos(x, 1, z));
			}
		}
		g.put("debris", 1, 0, 0).put("debris", 2, 0, 0);
		assertEquals(List.of(ORIGIN), VeinPlanner.plan(g, ORIGIN, 8, 6, 5), "five looks: the start and four skipped cells");
		assertEquals(List.of(ORIGIN, new Pos(1, 0, 0), new Pos(2, 0, 0)), VeinPlanner.plan(g, ORIGIN, 8, 6, 100));
	}

	@Test
	void nonPreferredSkippedAndNotWalkedThrough() {
		Grid g = new Grid().put("diamond", 0, 0, 0).put("diamond", 1, 0, 0).put("diamond", 2, 0, 0);
		g.unbreakable.add(new Pos(1, 0, 0));
		List<Pos> p = plan(g, 8);
		assertEquals(List.of(ORIGIN), p, "the far ore is reached only through one the arm can't break");
		Grid start = new Grid().put("diamond", 0, 0, 0);
		start.unbreakable.add(ORIGIN);
		assertEquals(List.of(), plan(start, 8));
	}

	/** A grid that applies the special's reach rule as VeinRip's WorldGrid does: eyes at (ex, ey, ez), PLAN_REACH to a block's centre. */
	private static final class ReachGrid implements GridView {
		final Grid inner;
		final double ex, ey, ez;

		ReachGrid(Grid inner, double ex, double ey, double ez) {
			this.inner = inner;
			this.ex = ex;
			this.ey = ey;
			this.ez = ez;
		}

		@Override
		public @Nullable String group(int x, int y, int z) {
			return inner.group(x, y, z);
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			return VeinPlanner.withinReach(ex, ey, ez, x, y, z, VeinPlanner.PLAN_REACH) && inner.breakable(x, y, z);
		}
	}

	@Test
	void reachIsMeasuredToTheBlockCentre() {
		assertEquals(8.0, VeinPlanner.PLAN_REACH);
		assertTrue(VeinPlanner.TEAR_REACH > VeinPlanner.PLAN_REACH, "a little slack for the owner walking on");
		// eyes at the middle of block (0, 0, 0): a block 8 away along x is exactly on the line, 9 is out
		assertTrue(VeinPlanner.withinReach(0.5, 0.5, 0.5, 8, 0, 0, VeinPlanner.PLAN_REACH));
		assertFalse(VeinPlanner.withinReach(0.5, 0.5, 0.5, 9, 0, 0, VeinPlanner.PLAN_REACH));
		assertTrue(VeinPlanner.withinReach(0.5, 0.5, 0.5, 9, 0, 0, VeinPlanner.TEAR_REACH));
		// diagonals count in all three axes: (5, 5, 5) is 8.66 away
		assertFalse(VeinPlanner.withinReach(0.5, 0.5, 0.5, 5, 5, 5, VeinPlanner.PLAN_REACH));
		assertTrue(VeinPlanner.withinReach(0.5, 0.5, 0.5, 4, 4, 4, VeinPlanner.PLAN_REACH));
	}

	@Test
	void theWalkStopsAtOresPastTheEyesReachSoThePlanAndTheResultAgree() {
		// a vein running away from the eyes at (0.5, 0.5, 0.5): the first ores are near, the rest are past 8 blocks
		Grid g = new Grid();
		for (int x = 5; x <= 14; x++) g.put("iron", x, 0, 0);
		List<Pos> plan = VeinPlanner.plan(new ReachGrid(g, 0.5, 0.5, 0.5), new Pos(5, 0, 0), 32, 6, 200);
		assertEquals(4, plan.size(), "x = 5 to 8, centres at most 8 from the eyes");
		assertEquals(new Pos(8, 0, 0), plan.get(plan.size() - 1));
		// ores beyond the cut are not planned, not even through a gap: every planned ore touches an earlier one
		int[] parents = VeinPlanner.parents(plan);
		for (int i = 1; i < parents.length; i++) assertTrue(parents[i] >= 0);
		// without the rule the same vein plans out to the box (offset 6 from x = 5)
		assertEquals(7, VeinPlanner.plan(g, new Pos(5, 0, 0), 32, 6, 200).size());
	}

	@Test
	void everyPlannedOreTouchesAnEarlierOne() {
		Grid g = new Grid();
		java.util.Random r = new java.util.Random(11);
		for (int i = 0; i < 300; i++) g.put("redstone", r.nextInt(9) - 4, r.nextInt(9) - 4, r.nextInt(9) - 4);
		g.put("redstone", 0, 0, 0);
		List<Pos> p = VeinPlanner.plan(g, ORIGIN, 32, 6, 128);
		int[] parents = VeinPlanner.parents(p);
		assertEquals(-1, parents[0]);
		for (int i = 1; i < p.size(); i++) {
			assertTrue(parents[i] >= 0 && parents[i] < i, "ore " + i + " grows from an earlier one");
			assertTrue(p.get(i).touches(p.get(parents[i])));
		}
	}

	@Test
	void parentFixture() {
		List<Pos> fixture = List.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(1, 1, 0), new Pos(2, 1, 1), new Pos(0, -1, 0));
		assertArrayEquals(new int[] {-1, 0, 1, 2, 0}, VeinPlanner.parents(fixture));
	}

	@Test
	void parentIsMinusOneWhenNothingEarlierTouches() {
		assertArrayEquals(new int[] {-1, -1}, VeinPlanner.parents(List.of(new Pos(0, 0, 0), new Pos(5, 0, 0))));
	}

	@Test
	void positionsPackLikeBlockPos() {
		for (Pos p : List.of(new Pos(0, 0, 0), new Pos(-30_000_000, -64, 29_999_999), new Pos(123, 319, -456), new Pos(-1, -1, -1))) {
			assertEquals(p, Pos.fromLong(p.asLong()));
		}
		// the game's own packing of (1, 2, 3): x << 38 | z << 12 | y
		assertEquals((1L << 38) | (3L << 12) | 2L, new Pos(1, 2, 3).asLong());
		assertFalse(new Pos(0, 0, 0).touches(new Pos(0, 0, 0)), "a block doesn't touch itself");
	}
}
