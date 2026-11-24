package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.survival.SustenanceMath.Plan;
import org.junit.jupiter.api.Test;

/** Sustenance restores a point a run, paid per point, and costs nothing when you are full (2026-11-16). */
class SustenanceMathTest {
	private static Plan plan(int food, double sat, long balance) {
		return SustenanceMath.plan(food, sat, 5, balance, 2000, 500);
	}

	@Test
	void aFullStomachCostsNothingWhateverTheSaturation() {
		Plan p = plan(20, 0, 1_000_000);
		assertFalse(p.any());
		assertEquals(0, p.costMilli());
	}

	@Test
	void oneFoodPointARunAndItsSaturation() {
		Plan p = plan(10, 0, 1_000_000);
		assertEquals(1, p.food(), "at most one point a run, however hungry");
		assertEquals(1.0, p.saturation(), 1e-9);
		assertEquals(2000 + 500, p.costMilli());
	}

	@Test
	void saturationStopsAtTheFloor() {
		Plan p = plan(19, 4.5, 1_000_000);
		assertEquals(1, p.food());
		assertEquals(0.5, p.saturation(), 1e-9, "only up to 5");
		assertEquals(2000 + 250, p.costMilli());
		assertEquals(0.0, plan(19, 5, 1_000_000).saturation(), 1e-9);
	}

	@Test
	void saturationCannotPassTheFoodLevelItJoins() {
		Plan p = SustenanceMath.plan(0, 0, 20, 1_000_000, 2000, 500);
		assertEquals(1, p.food());
		assertEquals(1.0, p.saturation(), 1e-9, "the level after this run's point is 1");
		assertEquals(0.5, SustenanceMath.plan(0, 0.5, 20, 1_000_000, 2000, 500).saturation(), 1e-9);
	}

	@Test
	void onlyWhatTheBalanceCovers() {
		assertFalse(plan(10, 0, 1999).any(), "a point costs 2000 milli");
		assertEquals(0, plan(10, 0, 0).costMilli());
		Plan p = plan(10, 0, 2000);
		assertEquals(1, p.food());
		assertEquals(0.0, p.saturation(), 1e-9, "nothing left for saturation");
		assertEquals(2000, p.costMilli());
		Plan half = plan(10, 0, 2250);
		assertEquals(1, half.food());
		assertEquals(0.5, half.saturation(), 1e-9, "a part of a point, as far as it is paid for");
		assertEquals(2250, half.costMilli());
	}

	@Test
	void theCostIsNeverMoreThanTheBalance() {
		for (long balance = 0; balance < 6000; balance += 7) {
			Plan p = plan(15, 0.3, balance);
			assertTrue(p.costMilli() <= balance, balance + ": " + p);
		}
	}

	@Test
	void aFreePriceRestoresWithoutPaying() {
		Plan p = SustenanceMath.plan(10, 0, 5, 0, 0, 0);
		assertEquals(1, p.food());
		assertEquals(0, p.costMilli());
	}
}
