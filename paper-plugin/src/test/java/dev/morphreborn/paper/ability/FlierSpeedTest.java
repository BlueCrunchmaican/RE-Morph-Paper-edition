package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * A watched creaking and a hanging bat are both held by flying at zero speed, so whichever hold ends, the other keeps it: the speed
 * goes through one place that knows both.
 */
class FlierSpeedTest {
	@Test
	void aFreeFlierFliesAtTheConfiguredSpeed() {
		assertEquals(0.05F, AbilityManager.flierSpeed(false, false, 0.05F), 0F);
	}

	@Test
	void aWatchedFlierIsHeld() {
		assertEquals(0F, AbilityManager.flierSpeed(true, false, 0.05F), 0F);
	}

	@Test
	void aPerchedBatIsHeldAlthoughNobodyIsWatching() {
		// unwatched while perched must not hand its flight back
		assertEquals(0F, AbilityManager.flierSpeed(false, true, 0.05F), 0F);
	}

	@Test
	void aWatchedPerchedBatIsHeldTwice() {
		assertEquals(0F, AbilityManager.flierSpeed(true, true, 0.05F), 0F);
	}
}
