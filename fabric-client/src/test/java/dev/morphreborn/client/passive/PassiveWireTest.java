package dev.morphreborn.client.passive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.net.Wire;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The passive toggles' packets byte for byte, against the same fixtures as the plugin's PassiveProtocolTest (2026-11-16: toggles in
 * the upgrades menu), so a field added or moved on one side fails the other's test.
 */
class PassiveWireTest {
	/** The server's S_PASSIVES: passive_gills running, passive_scavenger no biomass. */
	static final String STATES = "13 02 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 02 11 70 61 73 73 69 76 65 5F 73 63 61 76 65 6E 67 65 72 04";

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
		assertEquals(4, PassiveProtocol.CLIENT_THROTTLE_TICKS);
	}

	@Test
	void everyStateHasAWireCodeThatRoundTrips() {
		for (PassiveProtocol.State s : PassiveProtocol.State.values()) assertEquals(s, PassiveProtocol.state(PassiveProtocol.code(s)));
		assertEquals(PassiveProtocol.State.IDLE, PassiveProtocol.state(99), "a state a newer server added reads as the neutral one");
		assertEquals(PassiveProtocol.State.IDLE, PassiveProtocol.state(-1));
	}

	@Test
	void readsTheServersFixture() {
		PassiveStates s = new PassiveStates();
		Wire.In in = new Wire.In(hex(STATES));
		assertEquals(Wire.S_PASSIVES, in.varInt());
		s.read(in);
		assertFalse(in.hasMore(), "the reader takes exactly what the server wrote");
		assertTrue(s.received());
		assertEquals(PassiveProtocol.State.RUNNING, s.state("passive_gills"));
		assertEquals(PassiveProtocol.State.BROKE, s.state("passive_scavenger"));
		assertEquals(PassiveProtocol.State.NOT_BOUGHT, s.state("passive_antitoxin"), "an id the server did not list is not bought");
	}

	@Test
	void anEmptyListIsAListAndEndsEarlierToggles() {
		PassiveStates s = new PassiveStates();
		Wire.In in = new Wire.In(hex(STATES));
		in.varInt();
		s.read(in);
		Wire.In empty = new Wire.In(hex("13 00"));
		empty.varInt();
		s.read(empty);
		assertTrue(s.received(), "the server has toggles, there is just nothing bought");
		assertEquals(PassiveProtocol.State.NOT_BOUGHT, s.state("passive_gills"), "the whole list each time: what is missing is not bought");
	}

	@Test
	void requestFixtureIsWhatTheServerReads() {
		assertArrayEquals(hex("0B 12 70 61 73 73 69 76 65 5F 6E 69 67 68 74 5F 65 79 65 73 00"), PassiveProtocol.request("passive_night_eyes", false).bytes());
		assertArrayEquals(hex("0B 12 70 61 73 73 69 76 65 5F 6E 69 67 68 74 5F 65 79 65 73 01"), PassiveProtocol.request("passive_night_eyes", true).bytes());
	}

	@Test
	void aMalformedListIsRejectedNotTrusted() {
		PassiveStates s = new PassiveStates();
		Wire.In cut = new Wire.In(hex("13 02 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 02")); // the second passive is missing
		cut.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> s.read(cut));
		Wire.In huge = new Wire.In(hex("13 C8 01")); // 200 passives
		huge.varInt();
		assertThrows(Wire.MalformedPacketException.class, () -> s.read(huge));
	}

	@Test
	void theColoursAreTheChestPanesOnes() {
		assertEquals(0xFF4CAF50, PassiveProtocol.State.RUNNING.colour(), "green");
		assertEquals(0xFFFFD54F, PassiveProtocol.State.IDLE.colour(), "yellow");
		assertEquals(0xFFE53935, PassiveProtocol.State.BROKE.colour(), "red");
		assertEquals(0xFF8A8A8A, PassiveProtocol.State.OFF.colour(), "grey");
		assertTrue(PassiveProtocol.State.RUNNING.on() && PassiveProtocol.State.IDLE.on() && PassiveProtocol.State.BROKE.on());
		assertFalse(PassiveProtocol.State.OFF.on() || PassiveProtocol.State.NOT_BOUGHT.on());
		assertEquals("morphreborn.passive.state.running", PassiveProtocol.State.RUNNING.langKey());
	}
}
