package dev.morphreborn.paper.tendril;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The floor under a blade's cut on a player (arms.player-floor): Draining Cut and Cleave never take a player below it. */
class DrainHitTest {
	@Test
	void aBlowFarFromTheFloorIsWhole() {
		assertEquals(14, DrainHit.cappedBlow(14, 20, 2), 1e-9);
	}

	@Test
	void aBlowThatWouldCrossTheFloorStopsAtIt() {
		assertEquals(4, DrainHit.cappedBlow(14, 6, 2), 1e-9);
		assertEquals(0.5, DrainHit.cappedBlow(6.888, 2.5, 2), 1e-9);
	}

	@Test
	void aPlayerAtTheFloorOrBelowTakesNothing() {
		assertEquals(0, DrainHit.cappedBlow(14, 2, 2), 1e-9);
		assertEquals(0, DrainHit.cappedBlow(14, 1.5, 2), 1e-9);
		assertEquals(0, DrainHit.cappedBlow(14, 0.5, 2), 1e-9);
	}

	@Test
	void aSliverAboveTheFloorIsNotWorthAHit() {
		assertEquals(0, DrainHit.cappedBlow(14, 2.04, 2), 1e-9);
		assertEquals(0.06, DrainHit.cappedBlow(14, 2.06, 2), 1e-9);
	}

	@Test
	void aSmallBlowIsNeverRaised() {
		assertEquals(1.5, DrainHit.cappedBlow(1.5, 20, 2), 1e-9);
	}
}
