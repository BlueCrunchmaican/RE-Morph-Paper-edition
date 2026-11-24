package dev.morphreborn.client.arm.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmSlots;
import org.junit.jupiter.api.Test;

/** What the hotbar draws in each slot, and the inventory signal's decision. Pure data: no game needed. */
class ArmHotbarTest {
	private static ArmSlots.Binding[] bound(ArmSlots.Binding... bs) {
		ArmSlots.Binding[] out = new ArmSlots.Binding[ArmSlots.SLOTS];
		for (ArmSlots.Binding b : bs) out[b.slot()] = b;
		return out;
	}

	@Test
	void unboundSlotsAreVanilla() {
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 1));
		for (int slot = 0; slot < 9; slot++) {
			if (slot == 2) continue;
			assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(slot, 2, false, false, false, false, b).kind(), "slot " + slot);
		}
	}

	@Test
	void anUnheldBoundSlotShowsItsArm() {
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 2), new ArmSlots.Binding(5, "bow", 3));
		ArmHotbar.Plan p = ArmHotbar.decide(5, 2, false, false, false, false, b);
		assertEquals(ArmHotbar.Kind.ARM_ICON, p.kind());
		assertEquals("bow", p.form());
		assertEquals(3, p.tier());
	}

	@Test
	void theHeldDeployedArmKeepsVanillaDrawingWithTheCorner() {
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 2));
		ArmHotbar.Plan p = ArmHotbar.decide(2, 2, false, true, false, false, b);
		assertEquals(ArmHotbar.Kind.HELD_CORNER, p.kind());
		assertEquals("katana", p.form());
	}

	@Test
	void aBoundHeldSlotJustSelectedShowsTheArmWhileTheServerFormsIt() {
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 1));
		assertEquals(ArmHotbar.Kind.ARM_ICON, ArmHotbar.decide(2, 2, false, false, true, false, b).kind());
	}

	@Test
	void aBoundHeldSlotWithNoArmOutShowsItsRealItem() {
		// refused (no biomass, morphed) or melted: the player is holding the real item, so that is what is drawn (found in review)
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 1));
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(2, 2, false, false, false, false, b).kind());
		// the other bound slots still show their arms
		ArmSlots.Binding[] two = bound(new ArmSlots.Binding(2, "katana", 1), new ArmSlots.Binding(5, "pickaxe", 2));
		assertEquals(ArmHotbar.Kind.ARM_ICON, ArmHotbar.decide(5, 2, false, false, false, false, two).kind());
	}

	@Test
	void theHeldBoundSlotKeepsTheArmWhileTuckedWithNoScreen() {
		// just after the inventory closes the real item is still in the slot but the arm is out: not flat (2026-11-16)
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 2));
		ArmHotbar.Plan p = ArmHotbar.decide(2, 2, false, false, false, true, b);
		assertEquals(ArmHotbar.Kind.ARM_ICON, p.kind());
		assertEquals("katana", p.form());
		assertEquals(2, p.tier());
		// unbound slots and an open screen are as ever
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(3, 2, false, false, false, true, b).kind());
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(2, 2, true, false, false, true, b).kind());
	}

	@Test
	void aStrayArmOutsideTheHeldSlotIsLeftAlone() {
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(4, "axe", 1));
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(4, 1, false, true, false, false, b).kind());
	}

	@Test
	void anyOpenScreenDrawsNothingExtra() {
		ArmSlots.Binding[] b = bound(new ArmSlots.Binding(2, "katana", 1), new ArmSlots.Binding(5, "bow", 3));
		for (int slot = 0; slot < 9; slot++) {
			assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(slot, 2, true, slot == 2, false, false, b).kind(), "slot " + slot);
		}
	}

	@Test
	void nothingBoundOrASlotOutOfRangeIsVanilla() {
		ArmSlots.Binding[] none = bound();
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(3, 3, false, false, false, false, none).kind());
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(-1, 3, false, false, false, false, none).kind());
		assertEquals(ArmHotbar.Kind.NORMAL, ArmHotbar.decide(9, 3, false, false, false, false, none).kind());
	}

	@Test
	void slotIsReadFromTheHotbarLoopsX() {
		int width = 427; // centre 213: slot i draws at 213 - 90 + 20 i + 2
		for (int i = 0; i < 9; i++) assertEquals(i, ArmHotbar.slotAt(213 - 90 + 20 * i + 2, width));
		assertEquals(-1, ArmHotbar.slotAt(213 - 90 + 20 * 9 + 2, width), "a tenth slot does not exist");
		assertEquals(-1, ArmHotbar.slotAt(213 - 91 - 26, width), "the left offhand");
		assertEquals(-1, ArmHotbar.slotAt(213 + 91 + 10, width), "the right offhand");
	}

	@Test
	void theInventorySignalSendsEachChangeOnce() {
		assertEquals(ArmProtocol.ACTION_INVENTORY_OPEN, ArmInventorySignal.next(true, false));
		assertEquals(ArmProtocol.ACTION_INVENTORY_CLOSED, ArmInventorySignal.next(false, true));
		assertEquals(-1, ArmInventorySignal.next(true, true));
		assertEquals(-1, ArmInventorySignal.next(false, false));
	}

	@Test
	void theTuckLastsWhileTheServerHoldsItOpen() {
		long never = Long.MIN_VALUE / 2;
		assertTrue(ArmInventorySignal.tuckedAt(true, 5_000_000_000L, never, 3, 3));
		assertTrue(ArmInventorySignal.tuckedAt(true, 5_000_000_000L, never, 4, 3), "a screen open for the server is open whatever the slot");
		assertFalse(ArmInventorySignal.tuckedAt(false, 5_000_000_000L, never, 3, 3), "nothing was ever closed");
	}

	@Test
	void theGraceRunsFromWhenClosedWasSentOnTheSameSlot() {
		long sent = 10_000_000_000L;
		assertTrue(ArmInventorySignal.tuckedAt(false, sent + 1, sent, 3, 3));
		assertTrue(ArmInventorySignal.tuckedAt(false, sent + ArmInventorySignal.UNTUCK_GRACE_NS - 1, sent, 3, 3));
		assertFalse(ArmInventorySignal.tuckedAt(false, sent + ArmInventorySignal.UNTUCK_GRACE_NS, sent, 3, 3));
	}

	@Test
	void scrollingOffTheOpenedSlotEndsTheGraceAtOnce() {
		long sent = 10_000_000_000L;
		assertFalse(ArmInventorySignal.tuckedAt(false, sent + 1, sent, 4, 3));
		assertFalse(ArmInventorySignal.tuckedAt(false, sent + 1, sent, 3, -1), "no slot recorded: no grace");
	}
}
