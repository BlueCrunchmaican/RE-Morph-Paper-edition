package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.DrainAccumulator;
import org.junit.jupiter.api.Test;

/** The drain is exact: after T ticks at a rate, floor(T * rate / 1200) milli, however it is billed. */
class DrainAccumulatorTest {
	/** Ticks {@code ticks} times at {@code rate}, taking what is due every 20 ticks as the manager does. */
	private static long run(DrainAccumulator a, long ticks, long rate) {
		long billed = 0;
		for (long t = 1; t <= ticks; t++) {
			a.tick(rate);
			if (t % 20 == 0) billed += a.takeDue();
		}
		return billed + a.takeDue();
	}

	@Test
	void oneMinuteIsExactlyOneBiomass() {
		assertEquals(1000, run(new DrainAccumulator(), 1200, 1000));
	}

	@Test
	void anHourIsExactlySixty() {
		assertEquals(60_000, run(new DrainAccumulator(), 72_000, 1000));
	}

	@Test
	void otherRatesGiveTheFloor() {
		for (long rate : new long[] {500, 333, 250}) {
			for (long ticks : new long[] {1, 7, 1199, 1200, 1201, 12_345}) {
				assertEquals(Math.floorDiv(ticks * rate, 1200), run(new DrainAccumulator(), ticks, rate), rate + "/min over " + ticks + " t");
			}
		}
	}

	@Test
	void toolAndLanternAdd() {
		DrainAccumulator both = new DrainAccumulator();
		long billed = 0;
		for (int t = 1; t <= 2400; t++) {
			both.tick(1000);
			both.tick(500);
			if (t % 20 == 0) billed += both.takeDue();
		}
		assertEquals(3000, billed + both.takeDue());
	}

	@Test
	void formRetractCyclesCantDodgeTheDrain() {
		DrainAccumulator a = new DrainAccumulator();
		long cycles = 0;
		long ticksPerCycle = 59 * 20;
		for (int i = 0; i < 100; i++) {
			cycles += run(a, ticksPerCycle, 1000);
			a.reset(); // a retract drops less than one milli
		}
		long continuous = Math.floorDiv(100 * ticksPerCycle * 1000, 1200);
		assertTrue(cycles >= continuous - 100, cycles + " vs continuous " + continuous);
		assertTrue(cycles <= continuous);
	}

	@Test
	void rateZeroChargesNothing() {
		assertEquals(0, run(new DrainAccumulator(), 72_000, 0));
		assertEquals(0, run(new DrainAccumulator(), 100, -1000));
	}

	@Test
	void resetDropsUnderOneMilli() {
		DrainAccumulator a = new DrainAccumulator();
		a.tick(1000); // 1000/1200 of a milli
		assertEquals(0, a.takeDue());
		assertEquals(1000, a.remainder());
		a.reset();
		a.tick(1000);
		assertEquals(0, a.takeDue(), "the remainder was dropped");
	}
}
