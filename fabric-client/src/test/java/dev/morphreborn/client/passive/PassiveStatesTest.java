package dev.morphreborn.client.passive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.net.Wire;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The tree's toggle rules: which passives get one, what a click asks for, the version gate and the client's throttle. */
class PassiveStatesTest {
	private static PassiveStates with(String hex) {
		PassiveStates s = new PassiveStates();
		Wire.In in = new Wire.In(HexFormat.ofDelimiter(" ").parseHex(hex));
		in.varInt();
		s.read(in);
		return s;
	}

	/** passive_gills off (1), passive_antitoxin running (2). */
	private static PassiveStates offAndRunning() {
		return with("13 02 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 01 11 70 61 73 73 69 76 65 5F 61 6E 74 69 74 6F 78 69 6E 02");
	}

	@Test
	void noListMeansNoToggles() {
		PassiveStates s = new PassiveStates();
		assertFalse(s.received());
		assertFalse(s.canToggle("passive_gills", "8.0.0-beta.2"), "an older server never sends a list: the tree shows no toggles");
	}

	@Test
	void aBoughtPassiveOnAServerWithTogglesHasOne() {
		PassiveStates s = offAndRunning();
		assertTrue(s.canToggle("passive_gills", "8.0.0-beta.2"));
		assertTrue(s.canToggle("passive_antitoxin", "8.0.0"));
		assertFalse(s.canToggle("passive_scavenger", "8.0.0-beta.2"), "not bought: nothing to switch");
		assertFalse(s.canToggle("arm_katana", "8.0.0-beta.2"), "not a passive at all");
	}

	@Test
	void theServerVersionGatesEvenAListThatArrived() {
		PassiveStates s = offAndRunning();
		assertFalse(s.canToggle("passive_gills", "8.0.0-beta.1"));
		assertFalse(s.canToggle("passive_gills", "7.6.0"));
		assertFalse(s.canToggle("passive_gills", ""), "a server before 6.6.0 never said its version");
		assertTrue(PassiveProtocol.serverKnows("8.0.0-beta.2"));
		assertTrue(PassiveProtocol.serverKnows("8.1.0"));
		assertFalse(PassiveProtocol.serverKnows("8.0.0-beta.1"));
		assertFalse(PassiveProtocol.serverKnows(""));
	}

	@Test
	void aClickAsksForTheOtherState() {
		PassiveStates s = offAndRunning();
		assertTrue(s.wantOn("passive_gills"), "off: the click turns it on");
		assertFalse(s.wantOn("passive_antitoxin"), "running: the click turns it off");
		PassiveStates idle = with("13 01 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 03");
		assertFalse(idle.wantOn("passive_gills"), "idle is on");
		PassiveStates broke = with("13 01 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 04");
		assertFalse(broke.wantOn("passive_gills"), "no biomass is still on: the click switches it off");
	}

	@Test
	void readSaysWhetherTheListChanged() {
		String hex = "13 02 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 01 11 70 61 73 73 69 76 65 5F 61 6E 74 69 74 6F 78 69 6E 02";
		String other = "13 02 0D 70 61 73 73 69 76 65 5F 67 69 6C 6C 73 02 11 70 61 73 73 69 76 65 5F 61 6E 74 69 74 6F 78 69 6E 02";
		PassiveStates s = new PassiveStates();
		assertTrue(read(s, hex), "the first list is news");
		assertFalse(read(s, hex), "the forced repeat that answers the open tree's own request is not (else it would ask again for ever)");
		assertTrue(read(s, other), "a passive switched");
	}

	private static boolean read(PassiveStates s, String hex) {
		Wire.In in = new Wire.In(HexFormat.ofDelimiter(" ").parseHex(hex));
		in.varInt();
		return s.read(in);
	}

	@Test
	void clearingEndsTheTogglesUntilTheServerSendsAgain() {
		PassiveStates s = offAndRunning();
		s.clear();
		assertFalse(s.received());
		assertFalse(s.canToggle("passive_gills", "8.0.0-beta.2"));
	}

	@Test
	void theClientSendsOneEveryFourTicksAtMost() {
		PassiveStates s = new PassiveStates();
		assertTrue(s.takeSendSlot(100));
		assertFalse(s.takeSendSlot(100));
		assertFalse(s.takeSendSlot(103));
		assertTrue(s.takeSendSlot(104));
		assertFalse(s.takeSendSlot(105));
	}

	@Test
	void theThrottleSurvivesTheTickCounterRestarting() {
		PassiveStates s = new PassiveStates();
		assertTrue(s.takeSendSlot(5000));
		assertTrue(s.takeSendSlot(3), "a new connection's counter starts again");
		s.clear();
		assertTrue(s.takeSendSlot(4));
	}
}
