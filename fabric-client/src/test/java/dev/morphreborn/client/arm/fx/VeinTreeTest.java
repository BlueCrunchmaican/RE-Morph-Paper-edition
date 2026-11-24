package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Vein Rip's network as the client rebuilds it: the same tree as the server's planner from the same ordered list (the
 * protocol's fixture), the heights the drawing back runs by, and the timing that lands each tendril on its ore as the
 * server breaks it. Draining Cut's pulses live here too ({@link DrainPulse}): they are this package's other timing
 * promise, that a heal's swelling reaches the blade on exactly the tick the server said. So does the rule for which ink
 * puff an arm's ending gets ({@link InkPuffs}).
 */
class VeinTreeTest {
	/** BlockPos.asLong, as the server's VeinPlanner.Pos packs it. */
	private static long pack(int x, int y, int z) {
		return ((x & ((1L << 26) - 1)) << 38) | (y & ((1L << 12) - 1)) | ((z & ((1L << 26) - 1)) << 12);
	}

	private static long[] positions(int... xyz) {
		long[] out = new long[xyz.length / 3];
		for (int i = 0; i < out.length; i++) out[i] = pack(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]);
		return out;
	}

	/** Section 4's vein fixture: (0,0,0), (1,0,0), (1,1,0), (2,1,1), (0,-1,0). */
	private static final long[] FIXTURE = positions(0, 0, 0, 1, 0, 0, 1, 1, 0, 2, 1, 1, 0, -1, 0);

	@Test
	void parentFixture() {
		assertArrayEquals(new int[] {-1, 0, 1, 2, 0}, VeinFx.parents(FIXTURE));
	}

	@Test
	void unpacksTheGamesPacking() {
		int[][] cases = {{0, 0, 0}, {1, -1, 2}, {-5, -64, 30000}, {29_999_999, 2047, -29_999_999}, {-1, -2048, -1}};
		for (int[] c : cases) {
			long packed = BlockPos.asLong(c[0], c[1], c[2]);
			assertEquals(pack(c[0], c[1], c[2]), packed, "the server's packing is the game's");
			assertEquals(c[0], VeinFx.x(packed));
			assertEquals(c[1], VeinFx.y(packed));
			assertEquals(c[2], VeinFx.z(packed));
		}
	}

	@Test
	void nearestEarlierNeighbourWinsThenTheEarliest() {
		// (1,1,1) touches (0,0,0) by a corner, (0,0,1) by an edge and (1,1,0) by a face: the face wins although it came
		// last (the spec's prose said "the earliest"; its own fixture, and the server, say the nearest)
		assertEquals(2, VeinFx.parents(positions(0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1))[3]);
		// (0,1,0) touches (0,0,0) by a face, (0,1,1) by a face, (1,1,1) by an edge: of the two faces, the earliest
		assertEquals(0, VeinFx.parents(positions(0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 1, 0))[3]);
		// nothing earlier touches it: -1, and the rest of the tree is still built
		assertArrayEquals(new int[] {-1, -1, 0}, VeinFx.parents(positions(0, 0, 0, 5, 5, 5, 1, 1, 1)));
		// a block listed twice is not its own neighbour
		assertArrayEquals(new int[] {-1, -1}, VeinFx.parents(positions(3, 3, 3, 3, 3, 3)));
		assertArrayEquals(new int[0], VeinFx.parents(new long[0]));
	}

	@Test
	void heightsCountFromTheLeaves() {
		assertArrayEquals(new int[] {3, 2, 1, 0, 0}, VeinFx.heights(VeinFx.parents(FIXTURE)));
		assertArrayEquals(new int[] {0, 0}, VeinFx.heights(new int[] {-1, -1}));
	}

	@Test
	void branchesArriveAsTheirOreBreaks() {
		for (int rate : new int[] {10, 15, 20}) {
			int[] parents = VeinFx.parents(FIXTURE);
			int[] heights = VeinFx.heights(parents);
			int[] breakAt = new int[FIXTURE.length];
			for (int i = 0; i < breakAt.length; i++) breakAt[i] = ArmProtocol.veinBreakTick(i, rate);
			int end = breakAt[breakAt.length - 1];
			for (int i = 1; i < breakAt.length; i++) {
				int from = VeinFx.branchStart(breakAt, parents, i);
				assertTrue(from < breakAt[i], "it grows for at least a tick (rate " + rate + ", ore " + i + ")");
				assertTrue(from >= breakAt[i] - VeinFx.BRANCH_GROW, "and for at most " + VeinFx.BRANCH_GROW);
				assertTrue(from >= breakAt[parents[i]] || breakAt[i] - breakAt[parents[i]] < 1, "never before the ore it grows from breaks, unless they break together");
				assertEquals(0F, VeinFx.branchReach(from, breakAt, parents, heights, end, i), 1e-6F, "nothing yet at its start");
				assertEquals(1F, VeinFx.branchReach(breakAt[i], breakAt, parents, heights, end, i), 1e-6F, "there as its ore breaks");
				float last = 0F;
				for (float age = from; age <= breakAt[i]; age += 0.1F) {
					float reach = VeinFx.branchReach(age, breakAt, parents, heights, end, i);
					assertTrue(reach >= last - 1e-6F, "growth only ever extends");
					last = reach;
				}
			}
			// the drawing back: leaves first, 3 ticks each, then their parents, and the root last of all
			float leavesGone = end + VeinFx.LEAF_RETRACT;
			assertEquals(0F, VeinFx.branchReach(leavesGone, breakAt, parents, heights, end, 3), 1e-6F, "a leaf has drawn back");
			assertEquals(0F, VeinFx.branchReach(leavesGone, breakAt, parents, heights, end, 4), 1e-6F, "so has the other");
			assertEquals(1F, VeinFx.branchReach(leavesGone, breakAt, parents, heights, end, 2), 1e-6F, "its parent hasn't started");
			assertEquals(1F, VeinFx.rootReach(end + VeinFx.LEAF_RETRACT * heights[0], heights[0], end), 1e-6F, "the root waits for every branch");
			assertEquals(0F, VeinFx.rootReach(end + VeinFx.LEAF_RETRACT * heights[0] + VeinFx.ROOT_RETRACT, heights[0], end), 1e-6F, "then snaps back");
		}
		assertEquals(0F, VeinFx.rootReach(0F, 0, 10), 1e-6F);
		assertEquals(1F, VeinFx.rootReach(VeinFx.ROOT_GROW, 0, 10), 1e-6F, "the root reaches the first ore by tick 3, as it breaks");
		assertEquals(3, ArmProtocol.veinBreakTick(0, 10), "the first ore breaks on tick 3");
	}

	@Test
	void aRipCutShortDrawsBackFromWhereItGotTo() {
		int[] parents = VeinFx.parents(FIXTURE);
		int[] heights = VeinFx.heights(parents);
		int[] breakAt = new int[FIXTURE.length];
		for (int i = 0; i < breakAt.length; i++) breakAt[i] = ArmProtocol.veinBreakTick(i, 5); // half an ore a tick: 3, 5, 7, 9, 11
		int end = 6; // the arm went at tick 6: ore 1 is out, ore 2's branch is halfway there
		float atCut = VeinFx.branchReach(end, breakAt, parents, heights, end, 2);
		assertTrue(atCut > 0F && atCut < 1F, "ore 2's branch was on its way");
		float last = atCut;
		for (float age = end; age <= end + 20; age += 0.25F) {
			float reach = VeinFx.branchReach(age, breakAt, parents, heights, end, 2);
			assertTrue(reach <= last + 1e-6F, "it never grows on after the cut (age " + age + ")");
			last = reach;
			assertEquals(0F, VeinFx.branchReach(age, breakAt, parents, heights, end, 3), 0F, "ore 3's branch hadn't started, so it never sprouts");
		}
		assertEquals(0F, last, 0F, "and it is gone in the end");
		// cut before the root got there: it stops where it was and snaps back once the branches are in
		float rootAtCut = VeinFx.rootReach(1F, heights[0], 1);
		assertTrue(rootAtCut < 1F);
		assertTrue(VeinFx.rootReach(2.5F, heights[0], 1) <= rootAtCut + 1e-6F, "the root doesn't reach on after the cut either");
		assertEquals(0F, VeinFx.rootReach(1 + VeinFx.LEAF_RETRACT * heights[0] + VeinFx.ROOT_RETRACT, heights[0], 1), 0F);
	}

	/** Draining Cut's pulses: a heal's swelling reaches the blade on exactly the tick the server said ({@code ticks}). */
	@Nested
	class DrainPulse {
		private static final int ORIGIN = 7, TARGET = 42;

		private ArmFxEvent event(int kind, int ticks, int... ids) {
			return new ArmFxEvent(ORIGIN, kind, 2, ticks, 0, ids, new long[0], 0, Arms.TICK);
		}

		@Test
		void arrivesOnTheEventsTicks() {
			long saved = Arms.TICK;
			try {
				for (int travel = 1; travel <= 12; travel++) {
					DrainFx drain = new DrainFx();
					Arms.TICK = 5000;
					// as the server sends them: the pulse, then the drain that lists its creature
					drain.start(event(ArmProtocol.FX_PULSE, travel, TARGET));
					drain.start(event(ArmProtocol.FX_DRAIN, 0, TARGET));
					for (int t = 0; t < travel; t++) {
						assertEquals(t / (float) travel, drain.pulseAt(ORIGIN, TARGET, 0F), 1e-6F, "on its way (travel " + travel + ", tick " + t + ")");
						assertTrue(drain.pulseAt(ORIGIN, TARGET, 0.99F) < 1F, "not there before " + travel + " ticks");
						Arms.TICK++;
						drain.tick();
					}
					assertEquals(1F, drain.pulseAt(ORIGIN, TARGET, 0F), 0F, "at the blade after exactly " + travel + " ticks");
					Arms.TICK++;
					drain.tick();
					assertEquals(-1F, drain.pulseAt(ORIGIN, TARGET, 0F), 0F, "and gone the tick after");
				}
			} finally {
				Arms.TICK = saved;
			}
		}

		@Test
		void aTendrilIsAlwaysAheadOfAPulseSentWithIt() {
			for (int travel = DrainFx.GROW; travel <= 10; travel++) {
				for (float age = 0F; age <= travel; age += 0.05F) {
					assertTrue(DrainFx.grown(age) >= DrainFx.pulsePosition(age, travel) - 1e-6F, "travel " + travel + " age " + age);
				}
			}
			assertEquals(1F, DrainFx.grown(DrainFx.GROW), 0F, "grown in 4 ticks");
		}

		@Test
		void lettingGoAndSilenceEndIt() {
			long saved = Arms.TICK;
			try {
				Arms.TICK = 100;
				DrainFx drain = new DrainFx();
				drain.start(event(ArmProtocol.FX_PULSE, 8, TARGET));
				drain.start(event(ArmProtocol.FX_DRAIN, 0)); // an empty list lets go of everything
				assertEquals(-1F, drain.pulseAt(ORIGIN, TARGET, 0F), 0F, "nothing heals once it lets go");
				// caught again: a new tendril and its pulse (a slow one, so it is still on its way when the drain goes quiet)
				drain.start(event(ArmProtocol.FX_PULSE, 200, TARGET));
				assertEquals(0F, drain.pulseAt(ORIGIN, TARGET, 0F), 0F);
				for (int t = 0; t < ArmProtocol.STATEFUL_TIMEOUT_TICKS; t++) {
					Arms.TICK++;
					drain.tick();
				}
				assertTrue(drain.pulseAt(ORIGIN, TARGET, 0F) > 0F, "still latched after 60 quiet ticks");
				Arms.TICK++;
				drain.tick();
				assertEquals(-1F, drain.pulseAt(ORIGIN, TARGET, 0F), 0F, "a drain not heard of for longer lets go");
			} finally {
				Arms.TICK = saved;
			}
		}
	}

	/** Which puff an arm's ending gets: its own when its timeline ran out, the pop's when it was cut off, none at a death. */
	@Nested
	class InkPuffs {
		private static final int RETRACT = ArmProtocol.RETRACT_TICKS, MELT = ArmProtocol.MELT_TICKS;

		@Test
		void aTimelineThatRanOutGetsItsOwnPuff() {
			assertEquals(ArmFx.PUFF_RETRACTED, ArmFx.endPuff(ArmProtocol.STATE_RETRACTING, RETRACT, RETRACT, false, ArmProtocol.WHY_CHOSEN));
			assertEquals(ArmFx.PUFF_RETRACTED, ArmFx.endPuff(ArmProtocol.STATE_RETRACTING, RETRACT - 1, RETRACT, false, -1), "a NONE a tick early");
			assertEquals(ArmFx.PUFF_NONE, ArmFx.endPuff(ArmProtocol.STATE_RETRACTING, RETRACT, RETRACT, true, ArmProtocol.WHY_CHOSEN),
					"the retract puffed at tick " + ArmFx.RETRACT_PUFF_TICK + " already");
			assertEquals(ArmFx.PUFF_MELTED, ArmFx.endPuff(ArmProtocol.STATE_MELTING, MELT, MELT, false, ArmProtocol.WHY_DEPLETED));
			assertEquals(ArmFx.PUFF_MELTED, ArmFx.endPuff(ArmProtocol.STATE_MELTING, MELT + 20, MELT, false, -1), "settled here when the NONE never came");
		}

		@Test
		void anArmCutOffPops() {
			assertEquals(ArmFx.PUFF_POPPED, ArmFx.endPuff(ArmProtocol.STATE_RETRACTING, 5, RETRACT, false, ArmProtocol.WHY_SLOT));
			assertEquals(ArmFx.PUFF_POPPED, ArmFx.endPuff(ArmProtocol.STATE_MELTING, 10, MELT, false, ArmProtocol.WHY_MORPH));
			for (int state : new int[] {ArmProtocol.STATE_FORMING, ArmProtocol.STATE_FORMED, ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_QUICKFORM}) {
				assertEquals(ArmFx.PUFF_POPPED, ArmFx.endPuff(state, 3, ArmProtocol.duration(state), false, ArmProtocol.WHY_SLOT), "state " + state);
				assertEquals(ArmFx.PUFF_POPPED, ArmFx.endPuff(state, 300, ArmProtocol.duration(state), false, -1), "state " + state + ", no reason seen");
			}
		}

		@Test
		void noArmOrADeathPuffsNothing() {
			assertEquals(ArmFx.PUFF_NONE, ArmFx.endPuff(ArmProtocol.STATE_NONE, 40, 0, false, -1), "a lantern or recall state going");
			for (int state = ArmProtocol.STATE_FORMING; state <= ArmProtocol.STATE_QUICKFORM; state++) {
				assertEquals(ArmFx.PUFF_NONE, ArmFx.endPuff(state, 3, ArmProtocol.duration(state), false, ArmProtocol.WHY_DEATH), "state " + state);
			}
		}
	}
}
