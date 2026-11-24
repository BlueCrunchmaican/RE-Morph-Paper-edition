package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The unprompted re-form never closes a container the player has open (2026-11-17, B2-04). */
class OwnBodyDeployTest {
	@Test
	void theOwnInventoryViewIsNoScreen() {
		assertTrue(OwnBodyDeploy.fairMoment("CRAFTING"));
		assertTrue(OwnBodyDeploy.fairMoment("CREATIVE"));
	}

	@Test
	void anyContainerOrWorkstationWaits() {
		for (String t : new String[] {"CHEST", "FURNACE", "ANVIL", "ENDER_CHEST", "MERCHANT", "ENCHANTING", "WORKBENCH"}) {
			assertFalse(OwnBodyDeploy.fairMoment(t), t);
		}
	}
}
