package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.SaidOnce;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The said-once rule for refusals of arms nobody asked for (2026-11-16). */
class SaidOnceTest {
	private static final String MORPHED = "Arm forms are for your own body only: turn back first.";
	private static final String BIOMASS = "Your arm needs at least 1 biomass to take shape.";

	@Test
	void aRefusalIsSaidOnceNotOnEveryCloseOfAChest() {
		SaidOnce<UUID> once = new SaidOnce<>();
		UUID p = UUID.randomUUID();
		assertTrue(once.shouldSay(p, MORPHED));
		for (int i = 0; i < 5; i++) assertFalse(once.shouldSay(p, MORPHED), "the same reason again: quiet");
	}

	@Test
	void aNewReasonIsSaidAndThenQuietToo() {
		SaidOnce<UUID> once = new SaidOnce<>();
		UUID p = UUID.randomUUID();
		assertTrue(once.shouldSay(p, MORPHED));
		assertTrue(once.shouldSay(p, BIOMASS), "the reason changed");
		assertFalse(once.shouldSay(p, BIOMASS));
		assertTrue(once.shouldSay(p, MORPHED), "and changing back is news again too");
	}

	@Test
	void anArmFormingMakesTheNextRefusalNewsAgain() {
		SaidOnce<UUID> once = new SaidOnce<>();
		UUID p = UUID.randomUUID();
		assertTrue(once.shouldSay(p, MORPHED));
		once.clear(p);
		assertNull(once.lastSaid(p));
		assertTrue(once.shouldSay(p, MORPHED));
	}

	@Test
	void eachPlayerHasTheirOwn() {
		SaidOnce<UUID> once = new SaidOnce<>();
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		assertTrue(once.shouldSay(a, MORPHED));
		assertTrue(once.shouldSay(b, MORPHED));
		assertFalse(once.shouldSay(a, MORPHED));
		once.clear(a);
		assertEquals(MORPHED, once.lastSaid(b));
	}

	@Test
	void nothingIsNothingToSay() {
		SaidOnce<UUID> once = new SaidOnce<>();
		UUID p = UUID.randomUUID();
		assertFalse(once.shouldSay(p, null));
		assertFalse(once.shouldSay(p, ""));
		assertTrue(once.shouldSay(p, MORPHED), "and it didn't remember nothing");
	}
}
