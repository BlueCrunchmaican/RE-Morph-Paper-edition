package dev.morphreborn.client.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.net.Wire;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The arm packets byte by byte, as the server's WireArmTest writes them (docs/PROTOCOL.md, the arm build spec section 4). */
class ArmWireTest {
	/** The shared S_ARM fixture: katana, level 2, forming 3 of 14 ticks, slot 3, nothing else. */
	static final String FIXTURE = "0E 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 AC 02 06 6B 61 74 61 6E 61 00 02 01 03 0E 04 00 00 00 00 00";
	static final UUID SUBJECT = UUID.fromString("00000000-0000-0001-0000-000000000002");

	/** The server's Wire.Out, as far as these tests need it (the client's own only writes what the client sends). */
	static final class Enc {
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

		Enc(int id) {
			varInt(id);
		}

		Enc varInt(int value) {
			while ((value & ~0x7F) != 0) {
				buf.write((value & 0x7F) | 0x80);
				value >>>= 7;
			}
			buf.write(value);
			return this;
		}

		Enc bool(boolean b) {
			buf.write(b ? 1 : 0);
			return this;
		}

		Enc longValue(long v) {
			for (int i = 7; i >= 0; i--) buf.write((int) (v >>> (i * 8)) & 0xFF);
			return this;
		}

		Enc string(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			varInt(bytes.length);
			buf.writeBytes(bytes);
			return this;
		}

		Enc uuid(UUID u) {
			return longValue(u.getMostSignificantBits()).longValue(u.getLeastSignificantBits());
		}

		/** S_ARM's fields after the id, in order; {@code slot} is the hotbar slot (-1 none), written + 1. */
		Enc arm(UUID subject, int entity, String form, String from, int level, int state, int time, int duration, int slot, int why, boolean lit,
				int lanternLevel, int lanternTime, int recall) {
			return uuid(subject).varInt(entity).string(form).string(from).varInt(level).varInt(state).varInt(time).varInt(duration).varInt(slot + 1)
					.varInt(why).bool(lit).varInt(lanternLevel).varInt(lanternTime).varInt(recall);
		}

		byte[] bytes() {
			return buf.toByteArray();
		}

		/** A reader past the packet id, as ClientNet hands it on. */
		Wire.In in() {
			Wire.In in = new Wire.In(bytes());
			in.varInt();
			return in;
		}
	}

	static byte[] hex(String s) {
		String[] parts = s.trim().split("\\s+");
		byte[] out = new byte[parts.length];
		for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
		return out;
	}

	@BeforeEach
	void reset() {
		Arms.clear();
		Arms.TICK = 1000;
	}

	@Test
	void fixtureParses() {
		Wire.In in = new Wire.In(hex(FIXTURE));
		assertEquals(Wire.S_ARM, in.varInt());
		ArmState s = ArmState.read(in, 1000);
		assertFalse(in.hasMore(), "the fixture is read to its last byte");
		assertEquals(SUBJECT, s.subject);
		assertEquals(300, s.entityId);
		assertEquals("katana", s.form);
		assertEquals("", s.fromForm);
		assertEquals(2, s.level);
		assertEquals(ArmProtocol.STATE_FORMING, s.state);
		assertEquals(14, s.duration);
		assertEquals(3, s.slot);
		assertEquals(ArmProtocol.WHY_CHOSEN, s.why);
		assertFalse(s.lanternLit);
		assertEquals(0, s.lanternLevel);
		assertEquals(997, s.startTick, "3 ticks of forming had passed when it came");
		assertEquals(3F, s.elapsed(0F), 1e-6);
		assertEquals(0F, s.recallLeft(0F), 1e-6);
	}

	@Test
	void fixtureMatchesTheEncoder() {
		byte[] ours = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "katana", "", 2, ArmProtocol.STATE_FORMING, 3, 14, 3, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0).bytes();
		assertArrayEquals(hex(FIXTURE), ours);
	}

	@Test
	void fixtureThroughArms() {
		Wire.In in = new Wire.In(hex(FIXTURE));
		in.varInt();
		Arms.handleState(in);
		ArmState s = Arms.state(SUBJECT);
		assertNotNull(s);
		assertSame(s, Arms.stateByEntity(300));
		assertEquals(1, Arms.states().size());
	}

	@Test
	void newerServerFieldsAreLeftUnread() {
		Enc e = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "katana", "", 2, ArmProtocol.STATE_FORMED, 40, 0, 0, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0);
		// the brightness (7.6.0), the accessory list (8.0.0-beta.2: none), then a field from the future
		e.varInt(3).varInt(0).varInt(77).string("a field from the future");
		Wire.In in = e.in();
		ArmState s = ArmState.read(in, 1000);
		assertEquals(ArmProtocol.STATE_FORMED, s.state);
		assertEquals(3, s.lanternBright);
		assertTrue(s.accessories().isEmpty());
		assertTrue(in.hasMore(), "the extra fields stay for a newer reader");
	}

	@Test
	void anOlderServersStateHasNoAccessoryList() {
		Enc e = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "katana", "", 2, ArmProtocol.STATE_FORMED, 40, 0, 0, ArmProtocol.WHY_CHOSEN, true, 2, 10, 0);
		e.varInt(4); // 7.6.0 ends at the lantern's brightness
		Wire.In in = e.in();
		ArmState s = ArmState.read(in, 1000);
		assertFalse(in.hasMore());
		assertEquals(4, s.lanternBright);
		assertTrue(s.accessories().isEmpty());
		assertTrue(s.accessoryOn(ArmProtocol.LANTERN), "the lantern is still read from its own fields");
		assertFalse(s.accessoryOn(ArmProtocol.SHIELD));
		assertEquals(0, s.accessoryMask(1000) & ~1, "only the lantern's bit");
	}

	@Test
	void accessoryListIsReadAndUnknownOnesSkipped() {
		Enc e = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0);
		e.varInt(0); // brightness
		e.varInt(4);
		e.string("shield").bool(true).varInt(2).varInt(1);
		e.string("spyglass").bool(false).varInt(1).varInt(0);
		e.string("teleporter").bool(true).varInt(1).varInt(0); // a newer server's accessory: skipped
		e.string("flint").bool(true).varInt(3).varInt(0);
		Wire.In in = e.in();
		ArmState s = ArmState.read(in, 1000);
		assertFalse(in.hasMore());
		assertEquals(3, s.accessories().size());
		assertTrue(s.accessoryOn(ArmProtocol.SHIELD));
		assertEquals(2, s.accessoryLevel(ArmProtocol.SHIELD));
		assertEquals(1, s.accessoryArg(ArmProtocol.SHIELD), "raised");
		assertFalse(s.accessoryOn(ArmProtocol.SPYGLASS));
		assertEquals(1, s.accessoryLevel(ArmProtocol.SPYGLASS), "owned but off");
		assertTrue(s.accessoryOn(ArmProtocol.FLINT));
		assertFalse(s.accessoryOn("teleporter"));
		// the set: shield (bit 1) and flint (bit 3), the lantern's and spyglass's clear
		assertEquals(0b1010, s.accessoryMask(1000));
		assertEquals(1 << 4, s.accessoryArgs(), "the shield's arg in its four bits");
		assertTrue(s.anyAccessory(1000));
	}

	@Test
	void anAccessoryAloneKeepsAStateAliveAndDrawsBack() {
		// first sight: already built; then it goes off and draws back for ACCESSORY_OUT_TICKS before the state goes
		Enc on = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0);
		on.varInt(0).varInt(1).string("shield").bool(true).varInt(1).varInt(0);
		Arms.handleState(on.in());
		ArmState s = Arms.state(SUBJECT);
		assertNotNull(s, "an accessory on keeps a NONE state");
		assertEquals(ArmState.ACCESSORY_BUILT_TICKS, s.accessoryAge(ArmProtocol.SHIELD, 0F), 1e-6F, "a viewer arriving late sees it built");
		Enc off = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0);
		off.varInt(0).varInt(1).string("shield").bool(false).varInt(1).varInt(0);
		Arms.handleState(off.in());
		s = Arms.state(SUBJECT);
		assertNotNull(s, "still drawing back");
		assertTrue(s.accessoryGoingOut(ArmProtocol.SHIELD, Arms.TICK));
		assertTrue(s.accessoryDrawAge(ArmProtocol.SHIELD, 0F) < 0F, "negative while it plays backwards");
		assertEquals(1 << 1, s.accessoryMask(Arms.TICK));
		for (int i = 0; i < ArmState.ACCESSORY_OUT_TICKS + 2; i++) Arms.tick();
		assertNull(Arms.state(SUBJECT), "nothing left to show");
	}

	private static Wire.In shieldState(boolean on) {
		Enc e = new Enc(Wire.S_ARM).arm(SUBJECT, 300, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0);
		return e.varInt(0).varInt(1).string("shield").bool(on).varInt(1).varInt(0).in();
	}

	@Test
	void anAccessorySwitchedOnGrowsFromNothingWhereAFirstSightShowsItBuilt() {
		// someone else's: first sight is built (arriving late), then it goes off and the state is dropped once it has drawn back ...
		Arms.handleState(shieldState(true));
		assertEquals(ArmState.ACCESSORY_BUILT_TICKS, Arms.state(SUBJECT).accessoryAge(ArmProtocol.SHIELD, 0F), 1e-6F);
		Arms.handleState(shieldState(false));
		for (int i = 0; i < ArmState.ACCESSORY_OUT_TICKS + 2; i++) Arms.tick();
		assertNull(Arms.state(SUBJECT));
		// ... and switching it on again is a live change: it starts at age 0 and grows
		Arms.handleState(shieldState(true));
		assertEquals(0F, Arms.state(SUBJECT).accessoryAge(ArmProtocol.SHIELD, 0F), 1e-6F, "grows, not drawn already built");
		Arms.tick();
		Arms.tick();
		assertEquals(2F, Arms.state(SUBJECT).accessoryAge(ArmProtocol.SHIELD, 0F), 1e-6F);
		// after the player is forgotten (left the view) a state is a first sight again
		Arms.forget(SUBJECT);
		Arms.handleState(shieldState(true));
		assertEquals(ArmState.ACCESSORY_BUILT_TICKS, Arms.state(SUBJECT).accessoryAge(ArmProtocol.SHIELD, 0F), 1e-6F);
	}

	@Test
	void ourOwnAccessoryNeverArrivesBuilt() {
		Arms.clearOthers(SUBJECT); // the client knows who it is from the first level on
		Arms.handleState(shieldState(true));
		assertEquals(0F, Arms.state(SUBJECT).accessoryAge(ArmProtocol.SHIELD, 0F), 1e-6F, "the first toggle of our own, with no state before it, grows");
	}

	@Test
	void anAccessoryHiddenAtLevelZeroIsNotInTheRing() {
		ArmArsenal a = new ArmArsenal(true, "", false, 0, 0, 0, 0, 0, List.of(), "", 0, false, 0, 0, 0L, 0L,
				List.of(new ArmArsenal.AccessoryEntry("lantern", 0, false), new ArmArsenal.AccessoryEntry("shield", 2, false)));
		assertEquals(List.of("shield"), a.accessories().stream().map(ArmArsenal.AccessoryEntry::id).toList());
	}

	@Test
	void drawAgesAreOneArrayRefilledPerFrame() {
		Arms.handleState(shieldState(true));
		ArmState s = Arms.state(SUBJECT);
		int mask = s.accessoryMask(Arms.TICK);
		float[] first = s.drawAges(mask, 0.25F);
		assertEquals(ArmState.ACCESSORY_BUILT_TICKS + 0.25F, first[ArmProtocol.accessoryIndex(ArmProtocol.SHIELD)], 1e-4F);
		assertSame(first, s.drawAges(mask, 0.25F), "nothing allocated per call");
		Arms.tick();
		assertSame(first, s.drawAges(mask, 0.5F), "nor per frame: refilled in place");
		assertEquals(ArmState.ACCESSORY_BUILT_TICKS + 1.5F, first[ArmProtocol.accessoryIndex(ArmProtocol.SHIELD)], 1e-4F);
	}

	@Test
	void truncatedStateIsMalformed() {
		byte[] full = hex(FIXTURE);
		byte[] cut = java.util.Arrays.copyOf(full, full.length - 3);
		Wire.In in = new Wire.In(cut);
		in.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> ArmState.read(in, 0));
	}

	@Test
	void noneWithNothingLeftClears() {
		Arms.handleState(new Enc(Wire.S_ARM).arm(SUBJECT, 300, "katana", "", 2, ArmProtocol.STATE_FORMED, 0, 0, 3, 0, false, 0, 0, 0).in());
		assertNotNull(Arms.state(SUBJECT));
		Arms.handleState(new Enc(Wire.S_ARM).arm(SUBJECT, 300, "", "", 0, ArmProtocol.STATE_NONE, 0, 0, -1, ArmProtocol.WHY_CHOSEN, false, 0, 0, 0).in());
		assertNull(Arms.state(SUBJECT));
		assertNull(Arms.stateByEntity(300));
	}

	@Test
	void anotherEntityIdMovesTheIndex() {
		Arms.handleState(new Enc(Wire.S_ARM).arm(SUBJECT, 300, "katana", "", 1, ArmProtocol.STATE_FORMED, 0, 0, 0, 0, false, 0, 0, 0).in());
		Arms.handleState(new Enc(Wire.S_ARM).arm(SUBJECT, 412, "katana", "", 1, ArmProtocol.STATE_FORMED, 0, 0, 0, 0, false, 0, 0, 0).in());
		assertNull(Arms.stateByEntity(300), "the old entity id (before a respawn) no longer leads anywhere");
		assertNotNull(Arms.stateByEntity(412));
		Arms.forget(SUBJECT);
		assertNull(Arms.stateByEntity(412));
		assertTrue(Arms.states().isEmpty());
	}

	@Test
	void clearOthersKeepsOurOwn() {
		UUID other = new UUID(7, 7);
		Arms.handleState(new Enc(Wire.S_ARM).arm(SUBJECT, 300, "katana", "", 1, ArmProtocol.STATE_FORMED, 0, 0, 0, 0, false, 0, 0, 0).in());
		Arms.handleState(new Enc(Wire.S_ARM).arm(other, 301, "pickaxe", "", 1, ArmProtocol.STATE_FORMED, 0, 0, 0, 0, false, 0, 0, 0).in());
		Arms.clearOthers(SUBJECT);
		assertNotNull(Arms.state(SUBJECT));
		assertNull(Arms.state(other));
		assertNull(Arms.stateByEntity(301));
	}

	@Test
	void everyEffectKindRoundTrips() {
		for (int kind = ArmProtocol.FX_HIT; kind <= ArmProtocol.FX_WHIP; kind++) {
			int[] ids = kind % 3 == 0 ? new int[0] : new int[] {5, 1_000_000, 42};
			long[] positions = kind % 2 == 0 ? new long[0] : new long[] {0L, -1L, 274_877_906_943L, Long.MIN_VALUE};
			Enc e = new Enc(Wire.S_ARM_FX).varInt(300).varInt(kind).varInt(3).varInt(17).varInt(-123_456_789);
			e.varInt(ids.length);
			for (int id : ids) e.varInt(id);
			e.varInt(positions.length);
			for (long p : positions) e.longValue(p);
			e.varInt(kind * 10);
			Wire.In in = e.in();
			ArmFxEvent fx = ArmFxEvent.read(in, 1000);
			assertFalse(in.hasMore());
			assertEquals(300, fx.origin());
			assertEquals(kind, fx.kind());
			assertEquals(3, fx.level());
			assertEquals(17, fx.ticks());
			assertEquals(-123_456_789, fx.seed(), "a negative seed survives its five-byte varInt");
			assertArrayEquals(ids, fx.ids());
			assertArrayEquals(positions, fx.positions());
			assertEquals(kind * 10, fx.arg());
			assertEquals(1000, fx.receivedTick());
		}
	}

	@Test
	void effectsReachEveryListener() {
		List<ArmFxEvent> got = new ArrayList<>();
		Arms.addFxListener(e -> {
			throw new IllegalStateException("a broken listener");
		});
		Arms.addFxListener(got::add);
		Enc e = new Enc(Wire.S_ARM_FX).varInt(9).varInt(ArmProtocol.FX_DRAIN).varInt(2).varInt(0).varInt(1).varInt(0).varInt(0).varInt(0);
		Arms.handleFx(e.in());
		Arms.handleFx(e.in());
		assertEquals(2, got.size(), "a listener that throws doesn't keep the effect from the others");
		assertEquals(ArmProtocol.FX_DRAIN, got.getFirst().kind());
		assertTrue(got.getFirst().ends(), "an empty drain list ends the tendrils");
	}

	@Test
	void drainEndsWithAnEmptyList() {
		ArmFxEvent end = new ArmFxEvent(1, ArmProtocol.FX_DRAIN, 1, 0, 0, new int[0], new long[0], 0, 0);
		ArmFxEvent on = new ArmFxEvent(1, ArmProtocol.FX_DRAIN, 1, 0, 0, new int[] {4}, new long[0], 0, 0);
		ArmFxEvent burrowEnd = new ArmFxEvent(1, ArmProtocol.FX_BURROW, 1, 0, 0, new int[0], new long[0], 0, 0);
		ArmFxEvent hit = new ArmFxEvent(1, ArmProtocol.FX_HIT, 1, 0, 0, new int[0], new long[0], 0, 0);
		assertTrue(end.ends());
		assertFalse(on.ends());
		assertTrue(burrowEnd.ends());
		assertFalse(hit.ends(), "one-off effects never end anything");
	}

	@Test
	void arsenalParses() {
		Enc e = new Enc(Wire.S_ARSENAL).bool(true).string("").bool(false).varInt(100).varInt(250).varInt(1000).varInt(1000).varInt(500);
		e.varInt(2);
		e.string("katana").string("Tendril Katana").varInt(2).varInt(3).string("Draining Cut").varInt(15_000).varInt(120).varInt(80);
		e.string("pickaxe").string("Tendril Pickaxe").varInt(0).varInt(3).string("Vein Rip").varInt(8_000).varInt(160).varInt(0);
		e.string("katana").varInt(1).bool(true).varInt(10_000).varInt(500).varInt(300);
		// the accessories (8.0.0-beta.2): the shield owned and on, the spyglass not owned, a stranger skipped; then a newer server's block
		e.varInt(3).string("shield").varInt(2).bool(true).string("spyglass").varInt(0).bool(false).string("teleporter").varInt(1).bool(true);
		e.varInt(5).string("trailing block of a newer server");
		Wire.In in = e.in();
		ArmArsenal a = ArmArsenal.read(in, 1000);
		assertTrue(in.hasMore(), "a newer server's trailing block is left unread");
		assertEquals(2, a.accessory("shield").level());
		assertTrue(a.accessory("shield").on());
		assertFalse(a.accessory("spyglass").owned());
		assertNull(a.accessory("teleporter"));
		// the ring: the lantern (from its own fields, as the server did not list it), then the listed ones in ring order; the spyglass
		// is listed at level 0 (not grown), so it is hidden rather than shown as a dimmed lump (2026-11-16)
		assertEquals(java.util.List.of("lantern", "shield"), a.accessories().stream().map(ArmArsenal.AccessoryEntry::id).toList());
		assertSame(a.accessories(), a.accessories(), "worked out once: the wheel asks every frame");
		assertTrue(a.usable());
		assertEquals("", a.whyNot());
		assertFalse(a.free());
		assertEquals(100, a.perBlockMilli());
		assertEquals(250, a.perHitMilli());
		assertEquals(1000, a.perBoltMilli());
		assertEquals(1000, a.drainMilliPerMinute());
		assertEquals(500, a.lanternDrainMilliPerMinute());
		assertEquals(2, a.entries().size());
		ArmArsenal.Entry katana = a.entry("katana");
		assertNotNull(katana);
		assertEquals("Tendril Katana", katana.name());
		assertEquals(2, katana.level());
		assertEquals(3, katana.maxLevel());
		assertEquals("Draining Cut", katana.specialName());
		assertEquals(15_000, katana.specialCostMilli());
		assertEquals(120, katana.cooldownTicks());
		assertEquals(1080, katana.cooldownEndTick(), "the wait left becomes an end tick");
		assertEquals(30, katana.cooldownLeft(1050));
		assertEquals(0, katana.cooldownLeft(2000));
		ArmArsenal.Entry pickaxe = a.entry("pickaxe");
		assertNotNull(pickaxe);
		assertFalse(pickaxe.owned());
		assertNull(a.entry("lantern"), "the lantern is never an entry");
		assertEquals("katana", a.lastForm());
		assertEquals(1, a.lanternLevel());
		assertTrue(a.lanternLit());
		assertEquals(10_000, a.flareCostMilli());
		assertEquals(500, a.flareCooldownTicks());
		assertEquals(1300, a.flareCooldownEndTick());
	}

	@Test
	void arsenalThroughArmsAndBack() {
		assertSame(ArmArsenal.EMPTY, Arms.arsenal());
		assertFalse(Arms.arsenal().usable());
		Enc e = new Enc(Wire.S_ARSENAL).bool(false).string("Buy Arm Shaping first").bool(true).varInt(0).varInt(0).varInt(0).varInt(0).varInt(0)
				.varInt(0).string("").varInt(0).bool(false).varInt(0).varInt(0).varInt(0);
		Arms.handleArsenal(e.in());
		assertEquals("Buy Arm Shaping first", Arms.arsenal().whyNot());
		assertTrue(Arms.arsenal().entries().isEmpty());
		Arms.clear();
		assertSame(ArmArsenal.EMPTY, Arms.arsenal());
	}

	@Test
	void requestPackets() {
		assertArrayEquals(new Enc(Wire.C_ARM).varInt(ArmProtocol.ACTION_FORM).string("katana").bytes(),
				Arms.requestPacket(ArmProtocol.ACTION_FORM, "katana").bytes());
		assertArrayEquals(hex("09 01 00"), Arms.requestPacket(ArmProtocol.ACTION_RETRACT, "katana").bytes(), "only FORM carries the form");
		assertArrayEquals(hex("09 02 00"), Arms.requestPacket(ArmProtocol.ACTION_LANTERN_ON, "").bytes());
		// the accessory toggles carry the accessory's id (6 on, 7 off), as FORM carries the form's
		assertArrayEquals(new Enc(Wire.C_ARM).varInt(6).string("shield").bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_ON, "shield").bytes());
		assertArrayEquals(new Enc(Wire.C_ARM).varInt(7).string("flint").bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_OFF, "flint").bytes());
		assertArrayEquals(hex("09 06 00"), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_ON, null).bytes());
		// the mode step (the lantern's brightness): the accessory's id, then the step as one more varInt, for this action only
		assertArrayEquals(new Enc(Wire.C_ARM).varInt(8).string("lantern").varInt(1).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_MODE, "lantern", 1).bytes());
		assertArrayEquals(new Enc(Wire.C_ARM).varInt(8).string("lantern").varInt(-1).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_MODE, "lantern", -1).bytes());
		assertArrayEquals(new Enc(Wire.C_ARM).varInt(8).string("lantern").varInt(1).bytes(), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_MODE, "lantern", 7).bytes(), "only the sign");
		assertArrayEquals(hex("09 06 06 73 68 69 65 6C 64"), Arms.requestPacket(ArmProtocol.ACTION_ACCESSORY_ON, "shield", 1).bytes(), "no step with the other actions");
		byte[] long40 = Arms.requestPacket(ArmProtocol.ACTION_FORM, "a".repeat(40)).bytes();
		assertEquals(2 + 1 + ArmProtocol.MAX_FORM_ID, long40.length, "a form id is cut to 32");
	}

	@Test
	void newFormsContractMatchesTheServersNumbers() {
		assertEquals(6, ArmProtocol.ACTION_ACCESSORY_ON);
		assertEquals(7, ArmProtocol.ACTION_ACCESSORY_OFF);
		assertEquals(15, ArmProtocol.FX_SPEAR);
		assertEquals(16, ArmProtocol.FX_MACE);
		assertEquals(17, ArmProtocol.FX_TRIDENT);
		assertEquals(18, ArmProtocol.FX_CROSSBOW);
		assertEquals(19, ArmProtocol.FX_CLAWS);
		assertEquals(20, ArmProtocol.FX_ACCESSORY);
		assertEquals(21, ArmProtocol.FX_KINDS);
		assertEquals("spear", ArmProtocol.fxName(ArmProtocol.FX_SPEAR));
		assertEquals("mace", ArmProtocol.fxName(ArmProtocol.FX_MACE));
		assertEquals("trident", ArmProtocol.fxName(ArmProtocol.FX_TRIDENT));
		assertEquals("crossbow", ArmProtocol.fxName(ArmProtocol.FX_CROSSBOW));
		assertEquals("claws", ArmProtocol.fxName(ArmProtocol.FX_CLAWS));
		assertEquals("accessory", ArmProtocol.fxName(ArmProtocol.FX_ACCESSORY));
		assertEquals("8.0.0-beta.2", ArmProtocol.NEW_FORMS_SINCE);
		assertEquals(java.util.List.of("katana", "axe", "bow", "whip", "sickle", "shovel", "pickaxe", "spear", "mace", "trident", "crossbow", "claws"),
				ArmProtocol.WHEEL);
		assertEquals(java.util.List.of("lantern", "shield", "spyglass", "flint"), ArmProtocol.ACCESSORIES);
		assertTrue(ArmProtocol.FORMS.containsAll(ArmProtocol.WHEEL) && ArmProtocol.WHEEL.containsAll(ArmProtocol.FORMS), "the wheel and the slot forms are the same twelve");
		// whether the new kinds resend is the phase 2 handlers' call; until then they are ordinary one-off effects
		for (int kind = ArmProtocol.FX_SPEAR; kind <= ArmProtocol.FX_ACCESSORY; kind++) assertFalse(ArmProtocol.statefulFx(kind));
	}

	@Test
	void idsMatchTheServer() {
		assertEquals(9, Wire.C_ARM);
		assertEquals(14, Wire.S_ARM);
		assertEquals(15, Wire.S_ARM_FX);
		assertEquals(16, Wire.S_ARSENAL);
		assertEquals(3, ArmProtocol.veinBreakTick(0, 10));
		assertEquals(3 + 6, ArmProtocol.veinBreakTick(9, 15), "floor(9 * 10 / 15)");
		assertEquals(3 + 15, ArmProtocol.veinBreakTick(31, 20));
	}
}
