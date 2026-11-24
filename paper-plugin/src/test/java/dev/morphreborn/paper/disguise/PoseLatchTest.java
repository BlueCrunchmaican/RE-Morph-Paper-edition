package dev.morphreborn.paper.disguise;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A pose that flaps at a water's surface must not make the lent head come and go with it. */
class PoseLatchTest {
	private final UUID id = UUID.randomUUID();

	private boolean run(PoseLatch l, boolean raw, int ticks) {
		boolean last = false;
		for (int i = 0; i < ticks; i++) last = l.tick(id, raw);
		return last;
	}

	@Test
	void aPoseNeedsToHoldBeforeItCounts() {
		PoseLatch l = new PoseLatch();
		assertFalse(run(l, true, PoseLatch.HOLD - 1));
		assertTrue(l.tick(id, true));
	}

	@Test
	void aFlapShorterThanTheHoldChangesNothing() {
		PoseLatch l = new PoseLatch();
		for (int i = 0; i < 50; i++) {
			assertFalse(l.tick(id, i % 2 == 0)); // toggling every tick: never latches
		}
	}

	@Test
	void leavingThePoseIsAlsoDebounced() {
		PoseLatch l = new PoseLatch();
		assertTrue(run(l, true, PoseLatch.HOLD));
		assertTrue(run(l, false, PoseLatch.HOLD - 1));
		assertFalse(l.tick(id, false));
	}

	@Test
	void aBlipOutOfThePoseDoesNotReleaseIt() {
		PoseLatch l = new PoseLatch();
		run(l, true, PoseLatch.HOLD);
		assertTrue(l.tick(id, false));
		assertTrue(run(l, true, 5));
		assertTrue(run(l, false, PoseLatch.HOLD - 1)); // the streak restarted
	}

	@Test
	void retainAllForgetsTheOthers() {
		PoseLatch l = new PoseLatch();
		run(l, true, PoseLatch.HOLD);
		l.retainAll(Set.of());
		assertFalse(l.tick(id, false));
	}
}
