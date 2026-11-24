package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.net.Wire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S_ARM_SLOTS (servers from 7.6.0-beta.3) as the server writes it, and the store behind it. */
class ArmSlotsTest {
	/** A reader past the packet id, as ClientNet hands it on. */
	private static Wire.In in(Wire.Out out) {
		Wire.In in = new Wire.In(out.bytes());
		assertEquals(Wire.S_ARM_SLOTS, in.varInt());
		return in;
	}

	@BeforeEach
	void reset() {
		ArmSlots.clear();
	}

	@Test
	void decodesBindingsAndTheHeldDisplay() {
		Wire.Out out = new Wire.Out(Wire.S_ARM_SLOTS).varInt(2)
				.varInt(0).string("katana").varInt(2)
				.varInt(7).string("pickaxe").varInt(3)
				.bool(true).string("minecraft:cobblestone").varInt(64).bool(true);
		Wire.In in = in(out);
		ArmSlots.Snapshot s = ArmSlots.read(in);
		assertFalse(in.hasMore(), "read to the last byte");
		assertEquals(2, s.bindings().size());
		assertEquals(new ArmSlots.Binding(0, "katana", 2), s.bindings().get(0));
		assertEquals(new ArmSlots.Binding(7, "pickaxe", 3), s.bindings().get(1));
		assertEquals(new ArmSlots.Held("minecraft:cobblestone", 64, true), s.held());
		assertTrue(s.held().visible());
	}

	@Test
	void noHeldArmMeansNoDisplay() {
		ArmSlots.Snapshot s = ArmSlots.read(in(new Wire.Out(Wire.S_ARM_SLOTS).varInt(1).varInt(4).string("axe").varInt(1).bool(false)));
		assertEquals(1, s.bindings().size());
		assertNull(s.held());
	}

	@Test
	void emptyMessageIsEmpty() {
		ArmSlots.Snapshot s = ArmSlots.read(in(new Wire.Out(Wire.S_ARM_SLOTS).varInt(0).bool(false)));
		assertTrue(s.bindings().isEmpty());
		assertNull(s.held());
	}

	@Test
	void airUnderTheArmIsNothingToDraw() {
		ArmSlots.Snapshot s = ArmSlots.read(in(new Wire.Out(Wire.S_ARM_SLOTS).varInt(0).bool(true).string("minecraft:air").varInt(0).bool(false)));
		assertNotNull(s.held());
		assertFalse(s.held().visible());
	}

	@Test
	void badSlotsAndUnknownFormsAreSkippedAndTiersClamped() {
		ArmSlots.Snapshot s = ArmSlots.read(in(new Wire.Out(Wire.S_ARM_SLOTS).varInt(4)
				.varInt(9).string("katana").varInt(1)
				.varInt(2).string("a_form_from_the_future").varInt(1)
				.varInt(3).string("shovel").varInt(9)
				.varInt(5).string("whip").varInt(0)
				.bool(false)));
		assertEquals(2, s.bindings().size());
		assertEquals(new ArmSlots.Binding(3, "shovel", 3), s.bindings().get(0));
		assertEquals(new ArmSlots.Binding(5, "whip", 1), s.bindings().get(1));
	}

	@Test
	void shortPacketIsMalformed() {
		Wire.Out out = new Wire.Out(Wire.S_ARM_SLOTS).varInt(1).varInt(0).string("katana"); // no tier, no held flag
		assertThrows(Wire.MalformedPacketException.class, () -> ArmSlots.read(in(out)));
	}

	@Test
	void handleReplacesTheWholeStateAndClearForgetsIt() {
		ArmSlots.handle(in(new Wire.Out(Wire.S_ARM_SLOTS).varInt(2).varInt(0).string("katana").varInt(1).varInt(8).string("bow").varInt(2)
				.bool(true).string("minecraft:stick").varInt(3).bool(false)));
		assertEquals(new ArmSlots.Binding(0, "katana", 1), ArmSlots.binding(0));
		assertEquals(new ArmSlots.Binding(8, "bow", 2), ArmSlots.binding(8));
		assertNull(ArmSlots.binding(1));
		assertNull(ArmSlots.binding(-1));
		assertNull(ArmSlots.binding(9));
		assertTrue(ArmSlots.any());
		assertNotNull(ArmSlots.held());

		// the next message is the whole state: slot 0 is gone, and so is the held display
		ArmSlots.handle(in(new Wire.Out(Wire.S_ARM_SLOTS).varInt(1).varInt(3).string("axe").varInt(2).bool(false)));
		assertNull(ArmSlots.binding(0));
		assertNull(ArmSlots.binding(8));
		assertEquals(new ArmSlots.Binding(3, "axe", 2), ArmSlots.binding(3));
		assertNull(ArmSlots.held());

		ArmSlots.clear();
		assertFalse(ArmSlots.any());
		assertNull(ArmSlots.binding(3));
		assertNull(ArmSlots.held());
	}
}
