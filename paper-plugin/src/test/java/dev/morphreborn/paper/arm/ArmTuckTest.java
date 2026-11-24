package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.arm.rules.ArmTuck;
import dev.morphreborn.paper.arm.rules.ArmTuck.OnClose;
import dev.morphreborn.paper.arm.rules.ArmTuck.OnOpen;
import org.junit.jupiter.api.Test;

/** The tuck rows (2026-11-16): a screen opening keeps the arm formed and gives the real item back; closing puts the arm item back. */
class ArmTuckTest {
	@Test
	void openingAScreenTucksAFormedArm() {
		assertEquals(OnOpen.TUCK, ArmTuck.onOpen(true, false, false, true, true));
	}

	@Test
	void openingWithNoArmOrAnAlreadyTuckedOneDoesNothing() {
		assertEquals(OnOpen.NOTHING, ArmTuck.onOpen(false, false, false, false, true));
		assertEquals(OnOpen.NOTHING, ArmTuck.onOpen(true, true, false, false, true), "a repeated OPEN");
	}

	@Test
	void anArmThatCannotBeTuckedGoesBackAtOnce() {
		assertEquals(OnOpen.END, ArmTuck.onOpen(true, false, true, true, true), "retracting or melting: the ordinary retract");
		assertEquals(OnOpen.END, ArmTuck.onOpen(true, false, false, false, true), "its item is gone from the slot: the retract repairs it");
		assertEquals(OnOpen.END, ArmTuck.onOpen(true, false, false, true, false), "an unreadable stash is kept for /morph arm recover by the retract");
	}

	@Test
	void closingWithNoArmDeploysTheBoundHeldSlot() {
		assertEquals(OnClose.DEPLOY_BOUND, ArmTuck.onClose(false, false, false, false, true));
		assertEquals(OnClose.DEPLOY_BOUND, ArmTuck.onClose(false, false, false, true, false));
	}

	@Test
	void closingUntucksInPlace() {
		assertEquals(OnClose.UNTUCK, ArmTuck.onClose(true, true, false, false, true));
	}

	@Test
	void closingAfterTheSelectionMovedEndsTheSessionAndScrolls() {
		assertEquals(OnClose.END_AND_SCROLL, ArmTuck.onClose(true, true, false, true, true));
		assertEquals(OnClose.END_AND_SCROLL, ArmTuck.onClose(true, true, false, true, false));
	}

	@Test
	void anItemTooBigForTheStashEndsTheSession() {
		assertEquals(OnClose.END_TOO_BIG, ArmTuck.onClose(true, true, false, false, false));
	}

	@Test
	void closingNeverTouchesAnArmThatIsNotTucked() {
		assertEquals(OnClose.NOTHING, ArmTuck.onClose(true, false, false, false, true), "formed in hand: nothing to untuck");
		assertEquals(OnClose.NOTHING, ArmTuck.onClose(true, false, true, false, true), "retracting in hand: it runs out by itself");
	}

	@Test
	void aTuckedArmThatIsGoingBackEndsAtOnceWhenTheScreenCloses() {
		// nothing in the hand to see it out and nothing to give back (the stash is empty), so it is not left to run out
		assertEquals(OnClose.END_ENDING, ArmTuck.onClose(true, true, true, false, true));
		assertEquals(OnClose.END_ENDING, ArmTuck.onClose(true, true, true, true, false), "whether or not the selection moved");
	}
}
