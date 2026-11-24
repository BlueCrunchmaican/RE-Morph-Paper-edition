package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.HeldSlotWatch;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Catching a held slot that moved without the held event (pick-block, another plugin), once, and never every tick. */
class HeldSlotWatchTest {
	@Test
	void theFirstSightingIsNotAMove() {
		HeldSlotWatch<UUID> w = new HeldSlotWatch<>();
		assertFalse(w.moved(UUID.randomUUID(), 4), "a join is the join's business");
	}

	@Test
	void aMoveIsSeenOnceThenQuietWhileTheSlotStays() {
		HeldSlotWatch<UUID> w = new HeldSlotWatch<>();
		UUID p = UUID.randomUUID();
		w.moved(p, 0);
		assertFalse(w.moved(p, 0));
		assertTrue(w.moved(p, 3), "pick-block moved the selection");
		for (int tick = 0; tick < 5; tick++) assertFalse(w.moved(p, 3), "a deploy that was refused is not tried every tick");
		assertTrue(w.moved(p, 0));
	}

	@Test
	void aMoveTheEventHandledIsNotSeenAgain() {
		HeldSlotWatch<UUID> w = new HeldSlotWatch<>();
		UUID p = UUID.randomUUID();
		w.moved(p, 0);
		w.note(p, 2); // PlayerItemHeldEvent dealt with the scroll to 2
		assertFalse(w.moved(p, 2));
	}

	@Test
	void aForgottenPlayerStartsAgain() {
		HeldSlotWatch<UUID> w = new HeldSlotWatch<>();
		UUID p = UUID.randomUUID();
		w.moved(p, 0);
		w.forget(p);
		assertFalse(w.moved(p, 5), "rejoined: a first sighting");
		w.note(p, 1);
		assertTrue(w.moved(p, 2));
	}
}
