package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.arm.api.ArmSlotsMsg;
import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.net.Wire;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S_ARM_SLOTS byte for byte (2026-11-16). The reader is written as the client mod reads it, field by field,
 * so a field added or moved on the server shows up as a failure. The client has the same fixture.
 */
class WireArmSlotsTest {
	@Test
	void idsAndConstantsAreTheAgreedOnes() {
		assertEquals(18, Wire.S_ARM_SLOTS);
		assertEquals(4, ArmProtocol.ACTION_INVENTORY_OPEN);
		assertEquals(5, ArmProtocol.ACTION_INVENTORY_CLOSED);
		assertEquals("7.6.0-beta.3", ArmProtocol.SLOTS_SINCE);
		assertEquals(3, Wire.PROTOCOL_VERSION, "everything is additive");
	}

	@Test
	void slotsAreForClientsFromTheirVersion() {
		assertEquals(true, SemVer.compare("7.6.0-beta.3", ArmProtocol.SLOTS_SINCE) >= 0);
		assertEquals(true, SemVer.compare("7.6.0", ArmProtocol.SLOTS_SINCE) >= 0);
		assertEquals(true, SemVer.compare("7.6.0-beta.2", ArmProtocol.SLOTS_SINCE) < 0, "the arms' first beta doesn't know slots");
	}

	@Test
	void heldFixture() {
		ArmSlotsMsg m = new ArmSlotsMsg(List.of(new ArmSlotsMsg.Slot(0, "katana", 2), new ArmSlotsMsg.Slot(4, "bow", 3)), true, "minecraft:stone", 64, false);
		byte[] expected = HexFormat.ofDelimiter(" ").parseHex("12 02 00 06 6B 61 74 61 6E 61 02 04 03 62 6F 77 03 01 0F 6D 69 6E 65 63 72 61 66 74 3A 73 74 6F 6E 65 40 00");
		assertArrayEquals(expected, ClientChannel.armSlotsPacket(m));
	}

	@Test
	void idleFixture() {
		assertArrayEquals(HexFormat.ofDelimiter(" ").parseHex("12 00 00"), ClientChannel.armSlotsPacket(ArmSlotsMsg.idle(List.of())));
		byte[] one = HexFormat.ofDelimiter(" ").parseHex("12 01 08 04 77 68 69 70 01 00");
		assertArrayEquals(one, ClientChannel.armSlotsPacket(ArmSlotsMsg.idle(List.of(new ArmSlotsMsg.Slot(8, "whip", 1)))));
	}

	/** S_ARM_SLOTS as the client reads it. */
	private static ArmSlotsMsg read(byte[] bytes) {
		Wire.In in = new Wire.In(bytes);
		assertEquals(Wire.S_ARM_SLOTS, in.varInt());
		int count = in.varInt();
		List<ArmSlotsMsg.Slot> slots = new ArrayList<>();
		for (int i = 0; i < count; i++) slots.add(new ArmSlotsMsg.Slot(in.varInt(), in.string(ArmProtocol.MAX_FORM_ID), in.varInt()));
		boolean held = in.bool();
		ArmSlotsMsg m = held ? new ArmSlotsMsg(slots, true, in.string(256), in.varInt(), in.bool()) : ArmSlotsMsg.idle(slots);
		assertFalse(in.hasMore());
		return m;
	}

	@Test
	void roundTrip() {
		List<ArmSlotsMsg.Slot> all = new ArrayList<>();
		String[] forms = {"katana", "pickaxe", "axe", "shovel", "sickle", "bow", "whip", "katana2", "x"};
		for (int i = 0; i < 9; i++) all.add(new ArmSlotsMsg.Slot(i, forms[i], 1 + i % 3));
		ArmSlotsMsg full = new ArmSlotsMsg(all, true, "minecraft:enchanted_golden_apple", 12, true);
		assertEquals(full, read(ClientChannel.armSlotsPacket(full)));
		ArmSlotsMsg air = new ArmSlotsMsg(all.subList(0, 3), true, "minecraft:air", 0, false);
		assertEquals(air, read(ClientChannel.armSlotsPacket(air)));
		ArmSlotsMsg idle = ArmSlotsMsg.idle(all.subList(2, 5));
		assertEquals(idle, read(ClientChannel.armSlotsPacket(idle)));
		assertEquals(ArmSlotsMsg.idle(List.of()), read(ClientChannel.armSlotsPacket(ArmSlotsMsg.idle(List.of()))));
	}

	@Test
	void anOutOfRangeValueIsClampedNotWritten() {
		ArmSlotsMsg m = new ArmSlotsMsg(List.of(new ArmSlotsMsg.Slot(12, "katana", 0), new ArmSlotsMsg.Slot(-1, "bow", 9)), true, "", -5, false);
		ArmSlotsMsg back = read(ClientChannel.armSlotsPacket(m));
		assertEquals(new ArmSlotsMsg.Slot(8, "katana", 1), back.slots().get(0));
		assertEquals(new ArmSlotsMsg.Slot(0, "bow", 3), back.slots().get(1));
		assertEquals("minecraft:air", back.heldItem(), "no id reads as air");
		assertEquals(0, back.heldCount());
	}
}
