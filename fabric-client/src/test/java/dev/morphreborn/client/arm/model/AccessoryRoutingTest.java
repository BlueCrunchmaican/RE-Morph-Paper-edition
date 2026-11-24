package dev.morphreborn.client.arm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which pass draws the shield, the spyglass and the flint and steel (2026-11-24): on the body wherever the world pass draws one, including the
 * camera player's own under First-person Model, and the hand pass only where no body is drawn (vanilla first person) or First-person Model shows
 * vanilla hands on that side; and the hand pass leaves out what the world pass drew.
 */
class AccessoryRoutingTest {
	@AfterEach
	void clear() {
		ArmRender.worldSubmits(true);
		ArmRender.worldSubmits(false);
	}

	@Test
	void everyBodyOtherThanTheCameraPlayersOwnTakesThemAsBefore() {
		assertTrue(AccessoryRouting.onBody(false, false), "another player, F5, the doll");
		assertTrue(AccessoryRouting.onBody(false, true));
	}

	@Test
	void theCameraPlayersOwnBodyTakesThemOnlyWhenFirstPersonModelIsDrawingIt() {
		assertTrue(AccessoryRouting.onBody(true, true), "First-person Model's body: on the real arm, as the tools are");
		assertFalse(AccessoryRouting.onBody(true, false), "a draw the mod did not make is left to the hand pass");
	}

	@Test
	void theArmMustBeThereToCarryThem() {
		assertTrue(AccessoryRouting.armCarries(true, false, false, false));
		assertTrue(AccessoryRouting.armCarries(false, true, true, false), "the main arm hidden under the tendril arm still carries the spyglass and the striker");
		assertFalse(AccessoryRouting.armCarries(false, true, false, false), "the off arm has no tendril arm to stand in for it");
		assertFalse(AccessoryRouting.armCarries(true, false, true, true), "First-person Model's vanilla-hands modes: the overlay keeps them");
	}

	@Test
	void theHandPassLeavesOutWhatTheWorldPassDrewAndTheNextWorldPassForgetsIt() {
		for (String id : new String[] {ArmProtocol.SHIELD, ArmProtocol.SPYGLASS, ArmProtocol.FLINT}) assertFalse(ArmRender.accessoryOnBody(id));
		ArmRender.worldSubmits(true);
		ArmRender.accessoryDrawnOnBody(ArmProtocol.accessoryIndex(ArmProtocol.SHIELD));
		assertTrue(ArmRender.accessoryOnBody(ArmProtocol.SHIELD));
		assertFalse(ArmRender.accessoryOnBody(ArmProtocol.SPYGLASS), "one bit each");
		assertFalse(ArmRender.accessoryOnBody(ArmProtocol.FLINT));
		ArmRender.worldSubmits(false);
		assertTrue(ArmRender.accessoryOnBody(ArmProtocol.SHIELD), "kept after the world pass for the hand pass and the effects");
		ArmRender.worldSubmits(true);
		assertFalse(ArmRender.accessoryOnBody(ArmProtocol.SHIELD), "a new frame's world pass starts with none drawn");
		assertEquals(false, ArmRender.accessoryOnBody("not-an-accessory"));
	}

	@Test
	void theLanternGoesTheSameWayAsTheOtherThreeAndTheHandPassKeepsOnlyWhatTheBodyDidNotDraw() {
		// the lantern: on the body wherever the world pass draws one, including First-person Model's own; the overlay otherwise
		assertTrue(AccessoryRouting.onBody(true, true), "First-person Model's body takes the lantern too");
		assertFalse(AccessoryRouting.onBody(true, false), "vanilla first person: no body, the hand pass hangs it over the tool");
		assertTrue(AccessoryRouting.armCarries(true, false, true, false));
		assertFalse(AccessoryRouting.armCarries(true, false, true, true), "vanilla hands on that side: nothing to hang it on, the overlay keeps it");
		// the hand pass: its own lure only when the body did not draw it, and the accessories on their own
		assertTrue(AccessoryRouting.lureInOverlay(true, false));
		assertFalse(AccessoryRouting.lureInOverlay(true, true), "the body drew it: no second lantern");
		assertFalse(AccessoryRouting.lureInOverlay(false, false), "not lit");
		int lantern = 1, shield = 1 << ArmProtocol.accessoryIndex(ArmProtocol.SHIELD), flint = 1 << ArmProtocol.accessoryIndex(ArmProtocol.FLINT);
		assertFalse(AccessoryRouting.handPassNeeded(false, 0, 0), "nothing on");
		assertFalse(AccessoryRouting.handPassNeeded(false, lantern | shield, shield), "the lantern's own bit is the lure's business, the shield was drawn on the body");
		assertTrue(AccessoryRouting.handPassNeeded(false, shield | flint, shield), "the striker was not (vanilla hands on its side): the pass still runs, though the lantern is on the body");
		assertTrue(AccessoryRouting.handPassNeeded(true, 0, shield));
	}
}
