package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ArmLedger;
import dev.morphreborn.paper.arm.rules.ArmLedger.Spend;
import org.junit.jupiter.api.Test;

/** Arm biomass in whole milli: partial and all-or-nothing spends, depletion, and no drift. */
class ArmLedgerTest {
	@Test
	void partialTakesWhatThereIs() {
		Spend s = ArmLedger.spend(150, 250, true);
		assertEquals(150, s.charged());
		assertEquals(100, s.refused());
		assertEquals(0, s.after());
		assertTrue(s.depleted(), "the hit that empties the arm goes through and melts it");
	}

	@Test
	void allOrNothingRefusedTakesNothing() {
		Spend s = ArmLedger.spend(14_999, 15_000, false);
		assertEquals(0, s.charged());
		assertEquals(15_000, s.refused());
		assertEquals(14_999, s.after());
		assertFalse(s.depleted());
		assertFalse(s.paid());
	}

	@Test
	void allOrNothingAffordedTakesItAll() {
		Spend s = ArmLedger.spend(15_000, 15_000, false);
		assertEquals(15_000, s.charged());
		assertEquals(0, s.refused());
		assertTrue(s.depleted(), "depleted exactly at 0");
		assertTrue(s.paid());
	}

	@Test
	void depletionOnlyAtZero() {
		assertFalse(ArmLedger.spend(101, 100, true).depleted());
		assertTrue(ArmLedger.spend(100, 100, true).depleted());
		assertTrue(ArmLedger.spend(0, 100, true).depleted(), "billing an empty arm melts it");
	}

	@Test
	void tenBlocksFromFiveIsExactlyFour() {
		long balance = ArmLedger.toMilli(5.0);
		for (int i = 0; i < 10; i++) balance = ArmLedger.spend(balance, ArmLedger.toMilli(0.1), true).after();
		assertEquals(4000, balance);
		assertEquals(4.0, ArmLedger.fromMilli(balance), 0);
	}

	@Test
	void fractionalStartKeepsItsRemainder() {
		long balance = 5_432; // 5.432 biomass
		balance = ArmLedger.spend(balance, 250, true).after();
		balance = ArmLedger.spend(balance, 100, true).after();
		assertEquals(5_082, balance);
	}

	@Test
	void freeAndZeroCostChargeNothing() {
		Spend free = Spend.free(Long.MAX_VALUE);
		assertEquals(0, free.charged());
		assertEquals(0, free.refused());
		assertFalse(free.depleted());
		Spend zero = ArmLedger.spend(500, 0, false);
		assertEquals(0, zero.charged());
		assertEquals(500, zero.after());
		assertFalse(zero.depleted());
		assertEquals(500, ArmLedger.spend(500, -100, true).after(), "a negative cost never adds biomass");
	}

	@Test
	void neverNegative() {
		Spend s = ArmLedger.spend(-50, 100, true);
		assertEquals(0, s.charged());
		assertEquals(0, s.after());
		for (long cost : new long[] {1, 99, 1000, Long.MAX_VALUE}) {
			assertTrue(ArmLedger.spend(500, cost, true).after() >= 0);
			assertTrue(ArmLedger.spend(500, cost, false).after() >= 0);
		}
	}

	@Test
	void configDecimalsRoundToMilli() {
		assertEquals(100, ArmLedger.toMilli(0.1));
		assertEquals(250, ArmLedger.toMilli(0.25));
		assertEquals(1000, ArmLedger.toMilli(1));
		assertEquals(0, ArmLedger.toMilli(Double.NaN));
	}
}
