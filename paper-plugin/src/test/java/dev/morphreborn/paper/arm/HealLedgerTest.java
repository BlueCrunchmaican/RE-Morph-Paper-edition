package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.arm.rules.HealLedger;
import org.junit.jupiter.api.Test;

/** Draining Cut's healing: per pulse, per creature over the cut and its latches, and per special. */
class HealLedgerTest {
	// level 3: heal-per-pulse 2.0, heal-per-target 4, heal-cap 12, heal-ratio 0.5
	private static final double RATIO = 0.5, PULSE_CAP = 2.0;

	@Test
	void perTargetCapIsSharedWithLatchPulses() {
		HealLedger ledger = new HealLedger(4, 12);
		assertEquals(2.0, ledger.take(1, HealLedger.pulse(8, RATIO, 1, PULSE_CAP)), 1e-9, "the cut: 8 taken, capped at 2 a pulse");
		assertEquals(1.0, ledger.take(1, HealLedger.pulse(2, RATIO, 1, PULSE_CAP)), 1e-9, "latch 1");
		assertEquals(1.0, ledger.take(1, HealLedger.pulse(2, RATIO, 1, PULSE_CAP)), 1e-9, "latch 2 fills the creature's 4");
		assertEquals(0, ledger.take(1, HealLedger.pulse(2, RATIO, 1, PULSE_CAP)), 1e-9, "nothing more from it");
		assertEquals(4.0, ledger.from(1), 1e-9);
		assertEquals(2.0, ledger.take(2, 2.0), 1e-9, "another creature still gives");
	}

	@Test
	void totalCapAcrossTheSpecial() {
		HealLedger ledger = new HealLedger(4, 12);
		double healed = 0;
		for (int id = 1; id <= 8; id++) healed += ledger.take(id, HealLedger.pulse(6, RATIO, 1, PULSE_CAP));
		assertEquals(12, healed, 1e-9, "eight creatures at 2 each, capped at 12");
		assertEquals(12, ledger.total(), 1e-9);
		assertEquals(0, ledger.take(9, 2), 1e-9);
	}

	@Test
	void partialGrantAtTheCap() {
		HealLedger ledger = new HealLedger(3, 9);
		assertEquals(1.5, ledger.take(1, 1.5), 1e-9);
		assertEquals(1.5, ledger.take(1, 2.0), 1e-9, "only what is left of the creature's 3");
	}

	@Test
	void ratesScaleThePulse() {
		assertEquals(2.0, HealLedger.pulse(4, RATIO, 1.0, PULSE_CAP), 1e-9, "a hostile mob");
		assertEquals(1.0, HealLedger.pulse(4, RATIO, 0.5, PULSE_CAP), 1e-9, "passive, lesser or a player");
		assertEquals(0.75, HealLedger.pulse(3, RATIO, 0.5, 1.0), 1e-9);
		assertEquals(1.0, HealLedger.pulse(30, RATIO, 0.5, 1.0), 1e-9, "the per-pulse cap");
	}

	@Test
	void tookNothingHealsNothing() {
		HealLedger ledger = new HealLedger(4, 12);
		assertEquals(0, HealLedger.pulse(0, RATIO, 1, PULSE_CAP), 1e-9);
		assertEquals(0, ledger.take(1, 0), 1e-9);
		assertEquals(0, ledger.take(1, -3), 1e-9);
		assertEquals(0, ledger.take(1, Double.NaN), 1e-9);
		assertEquals(0, ledger.total(), 1e-9);
	}
}
