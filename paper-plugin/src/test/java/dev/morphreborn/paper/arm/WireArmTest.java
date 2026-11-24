package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmStateMsg;
import dev.morphreborn.paper.arm.api.ArsenalMsg;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.net.Wire;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The arm packets byte for byte (spec section 4). The readers here are written as the client mod reads, field by field,
 * so a field added or moved on the server side shows up as a failure. The client has the same fixture.
 */
class WireArmTest {
	private static final UUID SUBJECT = new UUID(1, 2);

	@Test
	void idsAreTheAgreedOnes() {
		assertEquals(9, Wire.C_ARM);
		assertEquals(14, Wire.S_ARM);
		assertEquals(15, Wire.S_ARM_FX);
		assertEquals(16, Wire.S_ARSENAL);
		assertEquals(3, Wire.PROTOCOL_VERSION, "everything is additive");
	}

	@Test
	void stateFixture() {
		ArmStateMsg m = new ArmStateMsg(SUBJECT, 300, "katana", "", 2, ArmProtocol.STATE_FORMING, 3, 14, 3, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0);
		byte[] expected = HexFormat.ofDelimiter(" ").parseHex("0E 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 AC 02 06 6B 61 74 61 6E 61 00 02 01 03 0E 04 00 00 00 00 00 00"); // the last: the lantern brightness (7.6.0)
		assertArrayEquals(expected, ClientChannel.armStatePacket(m));
	}

	/** S_ARM as the client reads it. */
	private static ArmStateMsg readState(byte[] bytes) {
		Wire.In in = new Wire.In(bytes);
		assertEquals(Wire.S_ARM, in.varInt());
		ArmStateMsg m = new ArmStateMsg(in.uuid(), in.varInt(), in.string(64), in.string(64), in.varInt(), in.varInt(), in.varInt(), in.varInt(), in.varInt() - 1,
				in.varInt(), in.bool(), in.varInt(), in.varInt(), in.varInt(), in.hasMore() ? in.varInt() : 0);
		assertFalse(in.hasMore());
		return m;
	}

	@Test
	void stateRoundTrip() {
		ArmStateMsg m = new ArmStateMsg(SUBJECT, 123_456, "pickaxe", "katana", 3, ArmProtocol.STATE_RESHAPING, 7, 16, 8, ArmProtocol.WHY_UPGRADE, true, 2, 4321, 155, 4);
		assertEquals(m, readState(ClientChannel.armStatePacket(m)));
		ArmStateMsg none = new ArmStateMsg(SUBJECT, 1, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_SLOT, false, 0, 0, 160);
		assertEquals(none, readState(ClientChannel.armStatePacket(none)));
	}

	/** S_ARM_FX as the client reads it. */
	private static ArmFxMsg readFx(byte[] bytes) {
		Wire.In in = new Wire.In(bytes);
		assertEquals(Wire.S_ARM_FX, in.varInt());
		int origin = in.varInt(), kind = in.varInt(), level = in.varInt(), ticks = in.varInt(), seed = in.varInt();
		int[] ids = new int[in.varInt()];
		for (int i = 0; i < ids.length; i++) ids[i] = in.varInt();
		long[] positions = new long[in.varInt()];
		for (int i = 0; i < positions.length; i++) positions[i] = in.longValue();
		ArmFxMsg m = new ArmFxMsg(origin, kind, level, ticks, seed, ids, positions, in.varInt());
		assertFalse(in.hasMore());
		return m;
	}

	@Test
	void fxRoundTripEveryKind() {
		long[] vein = {new Pos(0, 0, 0).asLong(), new Pos(1, 0, 0).asLong(), new Pos(-5, -60, 12).asLong()};
		for (int kind = 0; kind < ArmProtocol.FX_KINDS; kind++) {
			ArmFxMsg m = switch (kind) {
				case ArmProtocol.FX_HIT, ArmProtocol.FX_CLEAVE, ArmProtocol.FX_BOLT -> new ArmFxMsg(77, kind, 2, 0, 5, new int[] {901}, new long[0], 0);
				case ArmProtocol.FX_BREAK, ArmProtocol.FX_FLARE -> new ArmFxMsg(77, kind, 3, 600, 6, new int[0], new long[] {vein[2]}, 0);
				case ArmProtocol.FX_SWIPE -> new ArmFxMsg(77, kind, 2, 6, 7, new int[] {12, 13, 14}, new long[0], 135);
				case ArmProtocol.FX_DRAIN -> new ArmFxMsg(77, kind, 2, 0, 8, new int[] {12, 14}, new long[0], 0);
				case ArmProtocol.FX_PULSE -> new ArmFxMsg(77, kind, 2, 7, 9, new int[] {12}, new long[0], 0);
				case ArmProtocol.FX_VEIN, ArmProtocol.FX_FELL, ArmProtocol.FX_REAP -> new ArmFxMsg(77, kind, 1, 10, -3, new int[0], vein, 15);
				case ArmProtocol.FX_BURROW -> new ArmFxMsg(77, kind, 1, 280, 10, new int[0], new long[0], 0);
				case ArmProtocol.FX_HARPOON -> new ArmFxMsg(77, kind, 3, 12, 11, new int[] {555}, new long[] {vein[1]}, 0);
				// a disarm whose item was destroyed carries -1; a crack carries the far point
				case ArmProtocol.FX_WHIP_MOVE -> new ArmFxMsg(77, kind, 2, 6, 13, new int[] {901, -1}, new long[] {vein[2]}, ArmProtocol.WHIP_CRACK);
				default -> new ArmFxMsg(77, kind, 1, 0, Integer.MIN_VALUE, new int[] {Integer.MAX_VALUE}, new long[] {Long.MIN_VALUE}, -1);
			};
			assertEquals(m, readFx(ClientChannel.armFxPacket(m)), ArmProtocol.fxName(kind));
		}
		// the empty DRAIN that ends the tendrils
		ArmFxMsg end = new ArmFxMsg(77, ArmProtocol.FX_DRAIN, 2, 0, 0, new int[0], new long[0], 0);
		assertEquals(end, readFx(ClientChannel.armFxPacket(end)));
	}

	/** S_ARSENAL as the client reads it. */
	private static ArsenalMsg readArsenal(byte[] bytes, boolean expectEnd) {
		Wire.In in = new Wire.In(bytes);
		assertEquals(Wire.S_ARSENAL, in.varInt());
		boolean usable = in.bool();
		String whyNot = in.string(256);
		boolean free = in.bool();
		int perBlock = in.varInt(), perHit = in.varInt(), perBolt = in.varInt(), drain = in.varInt(), lanternDrain = in.varInt();
		int n = in.varInt();
		List<ArsenalMsg.Entry> entries = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			entries.add(new ArsenalMsg.Entry(in.string(32), in.string(64), in.varInt(), in.varInt(), in.string(64), in.varInt(), in.varInt(), in.varInt()));
		}
		ArsenalMsg m = new ArsenalMsg(usable, whyNot, free, perBlock, perHit, perBolt, drain, lanternDrain, entries, in.string(32), in.varInt(), in.bool(),
				in.varInt(), in.varInt(), in.varInt());
		if (expectEnd) assertFalse(in.hasMore());
		return m;
	}

	@Test
	void arsenalRoundTrip() {
		ArsenalMsg m = new ArsenalMsg(true, "", false, 100, 250, 1000, 1000, 500,
				List.of(new ArsenalMsg.Entry("katana", "Tendril Katana", 2, 3, "Draining Cut", 15_000, 120, 37),
						new ArsenalMsg.Entry("pickaxe", "Tendril Pickaxe", 0, 3, "Vein Rip", 8_000, 160, 0)),
				"katana", 1, true, 8_000, 600, 12);
		assertEquals(m, readArsenal(ClientChannel.arsenalPacket(m), true));
		ArsenalMsg refused = new ArsenalMsg(false, "Arms are for your own body.", true, 0, 0, 0, 0, 0, List.of(), "", 0, false, 0, 0, 0);
		assertEquals(refused, readArsenal(ClientChannel.arsenalPacket(refused), true));
	}

	@Test
	void arsenalReaderStopsBeforeLaterFields() {
		// a later release adds fields after the flare's: today's reader reads what it knows and ignores the rest
		ArsenalMsg m = new ArsenalMsg(true, "", true, 0, 0, 0, 0, 0, List.of(), "bow", 0, false, 0, 0, 0);
		byte[] now = ClientChannel.arsenalPacket(m);
		byte[] later = new byte[now.length + 2];
		System.arraycopy(now, 0, later, 0, now.length);
		later[now.length] = 1;
		later[now.length + 1] = 42;
		assertEquals(m, readArsenal(later, false));
	}

	@Test
	void armRequestRoundTrip() {
		// as the client sends it
		byte[] form = new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_FORM).string("katana").bytes();
		Wire.In in = new Wire.In(form);
		assertEquals(Wire.C_ARM, in.varInt());
		assertEquals(new ClientChannel.ArmRequest(ArmProtocol.ACTION_FORM, "katana"), ClientChannel.ArmRequest.read(in));
		byte[] retract = new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_RETRACT).bytes();
		in = new Wire.In(retract);
		in.varInt();
		assertEquals(new ClientChannel.ArmRequest(ArmProtocol.ACTION_RETRACT, ""), ClientChannel.ArmRequest.read(in), "no form string: empty");
	}

	@Test
	void armRequestFormIsCapped() {
		byte[] tooLong = new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_FORM).string("x".repeat(33)).bytes();
		Wire.In in = new Wire.In(tooLong);
		in.varInt();
		boolean refused = false;
		try {
			ClientChannel.ArmRequest.read(in);
		} catch (Wire.MalformedPacketException e) {
			refused = true;
		}
		assertTrue(refused, "a form id over 32 bytes is malformed");
	}

	// ------------------------------------------------------------------ the new forms (8.0.0-beta.2)

	@Test
	void theNewFormsContractIsTheAgreedOne() {
		assertEquals("8.0.0-beta.2", ArmProtocol.NEW_FORMS_SINCE);
		assertEquals(6, ArmProtocol.ACTION_ACCESSORY_ON);
		assertEquals(7, ArmProtocol.ACTION_ACCESSORY_OFF);
		assertEquals(2, ArmProtocol.ACTION_LANTERN_ON, "the lantern's own actions keep working for older clients");
		assertEquals(3, ArmProtocol.ACTION_LANTERN_OFF);
		assertEquals(List.of(15, 16, 17, 18, 19, 20), List.of(ArmProtocol.FX_SPEAR, ArmProtocol.FX_MACE, ArmProtocol.FX_TRIDENT, ArmProtocol.FX_CROSSBOW,
				ArmProtocol.FX_CLAWS, ArmProtocol.FX_ACCESSORY));
		assertEquals(21, ArmProtocol.FX_KINDS);
		assertEquals(List.of("spear", "mace", "trident", "crossbow", "claws", "accessory"), List.of(ArmProtocol.fxName(15), ArmProtocol.fxName(16),
				ArmProtocol.fxName(17), ArmProtocol.fxName(18), ArmProtocol.fxName(19), ArmProtocol.fxName(20)));
		for (int kind = 0; kind < ArmProtocol.FX_KINDS; kind++) assertEquals(kind >= 15, ArmProtocol.newFormsFx(kind), "kind " + kind);
		assertEquals(List.of("katana", "pickaxe", "axe", "shovel", "sickle", "bow", "whip", "spear", "mace", "trident", "crossbow", "claws"), ArmTiers.TOOL_FORMS);
		assertEquals(List.of("katana", "axe", "bow", "whip", "sickle", "shovel", "pickaxe", "spear", "mace", "trident", "crossbow", "claws"),
				ArmForm.WHEEL.stream().map(ArmForm::id).toList(), "the wheel's order");
		assertEquals(List.of("lantern", "shield", "spyglass", "flint"), ArmForm.ACCESSORIES.stream().map(ArmForm::id).toList());
		assertEquals(ArmTiers.ACCESSORIES, ArmForm.ACCESSORIES.stream().map(ArmForm::id).toList());
		for (ArmForm f : ArmForm.values()) assertTrue(f.id().length() <= ArmProtocol.MAX_FORM_ID, f.id());
	}

	@Test
	void theTridentAndCrossbowBillTheirOwnWear() {
		for (String form : List.of("whip", "trident", "crossbow")) assertTrue(ArmTiers.billsOwnWear(form), form);
		for (String form : List.of("katana", "pickaxe", "axe", "shovel", "sickle", "bow", "spear", "mace", "claws")) assertFalse(ArmTiers.billsOwnWear(form), form);
	}

	@Test
	void anAccessoryModeRequestCarriesItsStepAfterTheString() {
		assertEquals(8, ArmProtocol.ACTION_ACCESSORY_MODE);
		for (int step : new int[] {1, -1}) {
			Wire.In in = new Wire.In(new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_ACCESSORY_MODE).string("lantern").varInt(step).bytes());
			in.varInt();
			assertEquals(new ClientChannel.ArmRequest(ArmProtocol.ACTION_ACCESSORY_MODE, "lantern", step), ClientChannel.ArmRequest.read(in), "a negative varInt round-trips");
		}
		Wire.In big = new Wire.In(new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_ACCESSORY_MODE).string("lantern").varInt(-40).bytes());
		big.varInt();
		assertEquals(-1, ClientChannel.ArmRequest.read(big).step(), "only the sign counts");
		Wire.In none = new Wire.In(new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_ACCESSORY_MODE).string("lantern").bytes());
		none.varInt();
		assertEquals(0, ClientChannel.ArmRequest.read(none).step(), "no step: a no-op, not a malformed packet");
		// no other action reads one: a stray trailing byte is left alone
		Wire.In other = new Wire.In(new Wire.Out(Wire.C_ARM).varInt(ArmProtocol.ACTION_ACCESSORY_ON).string("shield").varInt(1).bytes());
		other.varInt();
		assertEquals(0, ClientChannel.ArmRequest.read(other).step());
	}

	@Test
	void anAccessoryRequestCarriesItsIdInTheFormString() {
		for (String id : List.of("lantern", "shield", "spyglass", "flint")) {
			for (int action : new int[] {ArmProtocol.ACTION_ACCESSORY_ON, ArmProtocol.ACTION_ACCESSORY_OFF}) {
				Wire.In in = new Wire.In(new Wire.Out(Wire.C_ARM).varInt(action).string(id).bytes());
				in.varInt();
				assertEquals(new ClientChannel.ArmRequest(action, id), ClientChannel.ArmRequest.read(in));
			}
		}
	}

	private static List<ArmStateMsg.Accessory> readAccessories(Wire.In in) {
		int n = in.varInt();
		List<ArmStateMsg.Accessory> out = new ArrayList<>();
		for (int i = 0; i < n; i++) out.add(new ArmStateMsg.Accessory(in.string(32), in.bool(), in.varInt(), in.varInt()));
		return out;
	}

	@Test
	void stateCarriesTheAccessoryListOnlyForNewClients() {
		List<ArmStateMsg.Accessory> list = List.of(new ArmStateMsg.Accessory("lantern", true, 2, 4), new ArmStateMsg.Accessory("shield", true, 1, 1),
				new ArmStateMsg.Accessory("spyglass", true, 3, 0));
		ArmStateMsg m = new ArmStateMsg(SUBJECT, 123, "bow", "", 2, ArmProtocol.STATE_FORMED, 0, 0, 3, ArmProtocol.WHY_CHOSEN, true, 2, 600, 0, 4, list);
		byte[] older = ClientChannel.armStatePacket(m, false);
		assertArrayEquals(older, ClientChannel.armStatePacket(m), "the plain packet is the one clients before NEW_FORMS_SINCE read");
		assertEquals(new ArmStateMsg(SUBJECT, 123, "bow", "", 2, ArmProtocol.STATE_FORMED, 0, 0, 3, ArmProtocol.WHY_CHOSEN, true, 2, 600, 0, 4), readState(older),
				"no accessory list, and the lantern's own fields as ever");
		byte[] newer = ClientChannel.armStatePacket(m, true);
		assertTrue(newer.length > older.length);
		assertArrayEquals(older, java.util.Arrays.copyOf(newer, older.length), "the new packet only appends: an older reader's fields are unchanged");
		Wire.In in = new Wire.In(newer);
		assertEquals(Wire.S_ARM, in.varInt());
		in.uuid();
		in.varInt();
		in.string(64);
		in.string(64);
		for (int i = 0; i < 6; i++) in.varInt(); // level, state, time, duration, slot + 1, why
		in.bool();
		for (int i = 0; i < 4; i++) in.varInt(); // lantern level, time, recall, brightness
		assertEquals(list, readAccessories(in));
		assertFalse(in.hasMore());
		// none on: a count of 0 after the lantern fields
		ArmStateMsg none = new ArmStateMsg(SUBJECT, 1, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0, 0, List.of());
		byte[] noneNew = ClientChannel.armStatePacket(none, true);
		assertEquals(ClientChannel.armStatePacket(none, false).length + 1, noneNew.length, "one byte: the count 0");
		assertEquals(0, noneNew[noneNew.length - 1]);
	}

	@Test
	void arsenalCarriesTheAccessoriesOnlyForNewClients() {
		ArsenalMsg m = new ArsenalMsg(true, "", false, 100, 250, 1000, 1000, 500,
				List.of(new ArsenalMsg.Entry("katana", "Tendril Katana", 2, 3, "Draining Cut", 15_000, 120, 37)), "katana", 1, true, 0, 0, 12,
				List.of(new ArsenalMsg.AccessoryEntry("lantern", 1, true), new ArsenalMsg.AccessoryEntry("shield", 0, false),
						new ArsenalMsg.AccessoryEntry("spyglass", 3, false), new ArsenalMsg.AccessoryEntry("flint", 2, true)));
		byte[] older = ClientChannel.arsenalPacket(m, false);
		assertArrayEquals(older, ClientChannel.arsenalPacket(m));
		assertEquals(new ArsenalMsg(true, "", false, 100, 250, 1000, 1000, 500, m.entries(), "katana", 1, true, 0, 0, 12), readArsenal(older, true));
		byte[] newer = ClientChannel.arsenalPacket(m, true);
		assertArrayEquals(older, java.util.Arrays.copyOf(newer, older.length), "only appended, after the flare's fields");
		// today's reader reads what it knew and ignores the rest
		assertEquals(new ArsenalMsg(true, "", false, 100, 250, 1000, 1000, 500, m.entries(), "katana", 1, true, 0, 0, 12), readArsenal(newer, false));
		// a new reader continues from there
		Wire.In in = new Wire.In(newer);
		in.varInt();
		in.bool();
		in.string(256);
		in.bool();
		for (int i = 0; i < 5; i++) in.varInt();
		int n = in.varInt();
		for (int i = 0; i < n; i++) {
			in.string(32);
			in.string(64);
			in.varInt();
			in.varInt();
			in.string(64);
			in.varInt();
			in.varInt();
			in.varInt();
		}
		in.string(32);
		in.varInt();
		in.bool();
		for (int i = 0; i < 3; i++) in.varInt();
		List<ArsenalMsg.AccessoryEntry> got = new ArrayList<>();
		int count = in.varInt();
		for (int i = 0; i < count; i++) got.add(new ArsenalMsg.AccessoryEntry(in.string(32), in.varInt(), in.bool()));
		assertEquals(m.accessories(), got);
		assertFalse(in.hasMore());
	}

	@Test
	void everyNewFxKindRoundTrips() {
		for (int kind = ArmProtocol.FX_SPEAR; kind <= ArmProtocol.FX_ACCESSORY; kind++) {
			ArmFxMsg m = new ArmFxMsg(77, kind, 2, 8, 9, new int[] {12, 13}, new long[] {new Pos(1, 2, 3).asLong()}, 3);
			assertEquals(m, readFx(ClientChannel.armFxPacket(m)), ArmProtocol.fxName(kind));
		}
	}

	@Test
	void survivalTrailer() {
		byte[] bytes = ClientChannel.survivalPacket(true, 42, 100, true, 375, 1500);
		// today's client: the four old fields, then the trailer when there is one
		Wire.In in = new Wire.In(bytes);
		assertEquals(Wire.S_SURVIVAL, in.varInt());
		assertTrue(in.bool());
		assertEquals(42, in.varInt());
		assertEquals(100, in.varInt());
		assertTrue(in.bool());
		assertEquals(375, in.hasMore() ? in.varInt() : 0);
		assertEquals(1500, in.hasMore() ? in.varInt() : 0);
		assertFalse(in.hasMore());
		// an older client reads the four fields and stops: hasMore is left true and nothing breaks
		Wire.In old = new Wire.In(bytes);
		old.varInt();
		old.bool();
		old.varInt();
		old.varInt();
		old.bool();
		assertTrue(old.hasMore());
		// a packet from an older server, read by today's client: the trailer reads as 0
		byte[] older = new Wire.Out(Wire.S_SURVIVAL).bool(false).varInt(1).varInt(2).bool(false).bytes();
		Wire.In fresh = new Wire.In(older);
		fresh.varInt();
		fresh.bool();
		fresh.varInt();
		fresh.varInt();
		fresh.bool();
		assertEquals(0, fresh.hasMore() ? fresh.varInt() : 0);
		assertEquals(0, fresh.hasMore() ? fresh.varInt() : 0);
		// clamped: the fraction is thousandths
		Wire.In clamped = new Wire.In(ClientChannel.survivalPacket(true, 1, 1, true, 5000, -7));
		clamped.varInt();
		clamped.bool();
		clamped.varInt();
		clamped.varInt();
		clamped.bool();
		assertEquals(999, clamped.varInt());
		assertEquals(0, clamped.varInt());
	}

	@Test
	void helloTrailer() {
		// S_HELLO's fields as the client reads them (ClientChannel.hello), then the arms flag appended in 7.6.0
		Wire.Out out = new Wire.Out(Wire.S_HELLO).varInt(Wire.PROTOCOL_VERSION).bool(true).string("").varInt(40).bool(true).bool(true).bool(true)
				.string("SHOW").string("survival").string("7.6.0").bool(true).bool(true);
		byte[] before = out.bytes();
		byte[] after = out.bool(true).bytes();
		assertTrue(readHello(after));
		assertFalse(readHello(before), "a server without arms: armsOffered = hasMore() && bool()");
		assertEquals(before.length + 1, after.length);
	}

	/** The client's hello reader: armsOffered is false when the server didn't send the flag. */
	private static boolean readHello(byte[] bytes) {
		Wire.In in = new Wire.In(bytes);
		in.varInt();
		in.varInt();
		in.bool();
		in.string(1024);
		in.varInt();
		in.bool();
		in.bool();
		in.bool();
		in.string(64);
		in.string(64);
		in.string(64);
		in.bool();
		in.bool();
		return in.hasMore() && in.bool();
	}

	@Test
	void veinBreakTicks() {
		// 1 ore a tick: 3, 4, 5...; 1.5 a tick: 3, 3, 4, 5, 5; 2 a tick: two per tick
		assertEquals(List.of(3, 4, 5), List.of(ArmProtocol.veinBreakTick(0, 10), ArmProtocol.veinBreakTick(1, 10), ArmProtocol.veinBreakTick(2, 10)));
		assertEquals(List.of(3, 3, 4, 5, 5), List.of(ArmProtocol.veinBreakTick(0, 15), ArmProtocol.veinBreakTick(1, 15), ArmProtocol.veinBreakTick(2, 15),
				ArmProtocol.veinBreakTick(3, 15), ArmProtocol.veinBreakTick(4, 15)));
		assertEquals(3 + 31 * 10 / 20, ArmProtocol.veinBreakTick(31, 20));
		assertEquals(0x7A00012C, ArmProtocol.crackSourceId(300));
	}
}
