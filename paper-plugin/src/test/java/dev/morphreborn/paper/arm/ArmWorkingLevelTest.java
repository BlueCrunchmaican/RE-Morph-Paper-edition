package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The level a form's module works at (rig 2026-11-22: a bow, whip, trident or crossbow forced to level 1 or 3 hit like level 2, the Classic
 * level): the formed session's own level when it is that form, the owned level otherwise.
 */
class ArmWorkingLevelTest {
	private static ArmSession session(String form, int level) {
		return new ArmSession(UUID.randomUUID(), form, level, 0, 1L, new byte[0], 0L, true);
	}

	@Test
	void aForcedLevelIsWhatTheFormWorksAt() {
		assertEquals(1, ArmManager.workingLevel(session("bow", 1), "bow", 2), "forced to 1 while the player owns 2");
		assertEquals(3, ArmManager.workingLevel(session("trident", 3), "trident", 2), "forced to 3");
	}

	@Test
	void aThinnedSurvivalArmWorksAtTheTierItSteppedDownTo() {
		assertEquals(1, ArmManager.workingLevel(session("whip", 1), "whip", 3), "biomass low: owned 3, formed at 1");
	}

	@Test
	void anotherFormOrNoSessionFallsBackToTheOwnedLevel() {
		assertEquals(2, ArmManager.workingLevel(session("katana", 3), "bow", 2), "the session is another form");
		assertEquals(2, ArmManager.workingLevel(null, "bow", 2), "no arm formed");
		assertEquals(0, ArmManager.workingLevel(null, "bow", 0), "not allowed stays 0");
	}
}
