package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.net.Wire;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The passive toggles' packets byte for byte (2026-11-16: toggles in the upgrades menu, so in the client's tree). The client's
 * PassiveWireTest reads the same fixtures with its own codec, so a field added or moved on one side fails there.
 */
class PassiveProtocolTest {
	private static byte[] hex(String s) {
		return HexFormat.ofDelimiter(" ").parseHex(s);
	}

	@Test
	void idsAndConstantsAreTheAgreedOnes() {
		assertEquals(11, Wire.C_PASSIVE);
		assertEquals(19, Wire.S_PASSIVES);
		assertEquals("8.0.0-beta.2", PassiveProtocol.SINCE);
		assertEquals(3, Wire.PROTOCOL_VERSION, "everything is additive");
		assertEquals(0, PassiveProtocol.STATE_NOT_BOUGHT);
		assertEquals(1, PassiveProtocol.STATE_OFF);
		assertEquals(2, PassiveProtocol.STATE_RUNNING);
		assertEquals(3, PassiveProtocol.STATE_IDLE);
		assertEquals(4, PassiveProtocol.STATE_BROKE);
	}

	@Test
	void everyStateHasAWireCodeThatRoundTrips() {
		for (Passives.State s : Passives.State.values()) assertEquals(s, PassiveProtocol.state(PassiveProtocol.code(s)));
		assertEquals(Passives.State.IDLE, PassiveProtocol.state(99), "a state a newer server added reads as the neutral one");
		assertEquals(Passives.State.IDLE, PassiveProtocol.state(-1));
	}

	@Test
	void statesFixture() {
		Map<String, Passives.State> states = new LinkedHashMap<>();
		states.put("passive_gills", Passives.State.RUNNING);
		states.put("passive_scavenger", Passives.State.BROKE);
		assertArrayEquals(hex("13 02 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 02 11 70 61 73 73 69 76 65 5F 73 63 61 76 65 6E 67 65 72 04"),
				PassiveProtocol.statesPacket(states));
	}

	@Test
	void aPassiveNotBoughtIsLeftOutAndTheCountSaysSo() {
		Map<String, Passives.State> states = new LinkedHashMap<>();
		states.put("passive_sustenance", Passives.State.NOT_BOUGHT);
		states.put("passive_gills", Passives.State.RUNNING);
		states.put("passive_antitoxin", Passives.State.NOT_BOUGHT);
		byte[] only = PassiveProtocol.statesPacket(Map.of("passive_gills", Passives.State.RUNNING));
		assertArrayEquals(only, PassiveProtocol.statesPacket(states));
		assertArrayEquals(hex("13 00"), PassiveProtocol.statesPacket(Map.of("passive_gills", Passives.State.NOT_BOUGHT)));
		assertArrayEquals(hex("13 00"), PassiveProtocol.statesPacket(Map.of()));
	}

	@Test
	void requestFixtureRoundTrips() {
		byte[] packet = PassiveProtocol.requestPacket("passive_night_eyes", false);
		assertArrayEquals(hex("0B 12 70 61 73 73 69 76 65 5F 6E 69 67 68 74 5F 65 79 65 73 00"), packet);
		Wire.In in = new Wire.In(hex("0B 12 70 61 73 73 69 76 65 5F 6E 69 67 68 74 5F 65 79 65 73 01"));
		assertEquals(Wire.C_PASSIVE, in.varInt());
		PassiveProtocol.Request r = PassiveProtocol.Request.read(in);
		assertEquals("passive_night_eyes", r.id());
		assertTrue(r.on());
		assertFalse(in.hasMore());
	}

	@Test
	void aMalformedRequestIsRejectedNotTrusted() {
		// a string longer than the cap, a packet that ends before the bool, one that ends in the string
		StringBuilder longId = new StringBuilder();
		for (int i = 0; i <= PassiveProtocol.MAX_ID; i++) longId.append('x');
		Wire.In a = new Wire.In(PassiveProtocol.requestPacket(longId.toString(), true));
		a.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> PassiveProtocol.Request.read(a));
		Wire.In b = new Wire.In(hex("0B 01 78"));
		b.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> PassiveProtocol.Request.read(b));
		Wire.In c = new Wire.In(hex("0B 09 78"));
		c.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> PassiveProtocol.Request.read(c));
	}

	@Test
	void theGateIsTheClientVersion() {
		assertTrue(PassiveProtocol.knows("8.0.0-beta.2"));
		assertTrue(PassiveProtocol.knows("8.0.0-beta.3"));
		assertTrue(PassiveProtocol.knows("8.0.0"));
		assertTrue(PassiveProtocol.knows("v8.1.0+26.3"));
		assertFalse(PassiveProtocol.knows("8.0.0-beta.1"), "the mod just before the toggles");
		assertFalse(PassiveProtocol.knows("7.6.0"));
		assertFalse(PassiveProtocol.knows("6.0.0"));
		assertFalse(PassiveProtocol.knows("?"), "a client that did not say its version is treated as an old one");
		assertFalse(PassiveProtocol.knows(null));
	}

	@Test
	void theThrottlesAreTheDocumentedOnes() {
		assertEquals(2, PassiveProtocol.SERVER_THROTTLE_TICKS);
		assertEquals(4, PassiveProtocol.CLIENT_THROTTLE_TICKS);
		assertEquals(5, PassiveProtocol.SEND_MIN_TICKS);
	}
}
