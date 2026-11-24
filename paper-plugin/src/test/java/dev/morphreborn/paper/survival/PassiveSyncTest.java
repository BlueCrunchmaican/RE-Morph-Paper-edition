package dev.morphreborn.paper.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** S_PASSIVES's coalescing and C_PASSIVE's throttle (PassiveSync), on a server tick clock and with no server. */
class PassiveSyncTest {
	private static final UUID P = UUID.fromString("00000000-0000-0001-0000-000000000002");
	private static final UUID Q = UUID.fromString("00000000-0000-0001-0000-000000000003");
	private static final Map<String, Passives.State> IDLE = Map.of("passive_gills", Passives.State.IDLE);
	private static final Map<String, Passives.State> RUNNING = Map.of("passive_gills", Passives.State.RUNNING);

	@Test
	void aPlayerNeverSentOneGetsTheFirstAtOnce() {
		assertTrue(new PassiveSync().shouldSend(P, IDLE, 100, false));
		assertTrue(new PassiveSync().shouldSend(P, Map.of(), 100, false), "an empty list too: the client must hear the server has toggles");
	}

	@Test
	void anUnchangedListIsNotSentAgain() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, IDLE, 0, false));
		assertFalse(s.shouldSend(P, IDLE, 10, false));
		assertFalse(s.shouldSend(P, IDLE, 1000, false));
	}

	@Test
	void aChangeWaitsOutTheWindowAndThenGoes() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, IDLE, 0, false));
		assertFalse(s.shouldSend(P, RUNNING, 3, false), "inside the 5-tick window");
		assertFalse(s.shouldSend(P, RUNNING, 4, false));
		assertTrue(s.shouldSend(P, RUNNING, 5, false), "the window has passed: it goes, with what it has become");
		assertFalse(s.shouldSend(P, RUNNING, 6, false));
	}

	@Test
	void aFlipThatUndoesItselfInsideTheWindowSendsNothing() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, IDLE, 0, false));
		assertFalse(s.shouldSend(P, RUNNING, 2, false));
		assertFalse(s.shouldSend(P, IDLE, 4, false), "back to what the client has");
		assertFalse(s.shouldSend(P, IDLE, 50, false));
	}

	@Test
	void aFlipEveryTickSendsAtMostOnePerWindow() {
		PassiveSync s = new PassiveSync();
		int sent = 0;
		for (long t = 0; t < 100; t++) {
			Map<String, Passives.State> now = t % 2 == 0 ? IDLE : RUNNING; // worse than the real run, which is every 10 ticks
			if (s.shouldSend(P, now, t, false)) sent++;
		}
		assertTrue(sent <= 100 / PassiveProtocol.SEND_MIN_TICKS + 1, "sent " + sent);
	}

	@Test
	void forceAlwaysSendsAndStartsTheWindow() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, IDLE, 0, false));
		assertTrue(s.shouldSend(P, IDLE, 1, true), "the tree opened: the client must have it, changed or not");
		assertFalse(s.shouldSend(P, RUNNING, 3, false), "the forced send opened a window of its own");
		assertTrue(s.shouldSend(P, RUNNING, 6, false));
	}

	@Test
	void notBoughtDoesNotCountAsAState() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, Map.of("passive_gills", Passives.State.IDLE, "passive_antitoxin", Passives.State.NOT_BOUGHT), 0, false));
		assertFalse(s.shouldSend(P, Map.of("passive_gills", Passives.State.IDLE), 20, false), "a passive listed as not bought is the same as one left out");
		assertTrue(s.shouldSend(P, Map.of("passive_gills", Passives.State.IDLE, "passive_antitoxin", Passives.State.OFF), 40, false), "buying one is a change");
	}

	@Test
	void forgettingStartsOver() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, IDLE, 0, false));
		s.forget(P);
		assertTrue(s.shouldSend(P, IDLE, 1, false), "the client dropped its list on a new hello: it is sent whatever it says");
	}

	@Test
	void aClockThatWentBackDoesNotLockThePlayerOut() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.shouldSend(P, IDLE, 5000, false));
		assertTrue(s.shouldSend(P, RUNNING, 10, false), "a restarted tick counter");
		assertTrue(s.allowToggle(P, 5000));
		assertTrue(s.allowToggle(P, 10));
	}

	@Test
	void theToggleThrottleDropsOneInsideTwoTicks() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.allowToggle(P, 100));
		assertFalse(s.allowToggle(P, 100));
		assertFalse(s.allowToggle(P, 101));
		assertTrue(s.allowToggle(P, 102));
		assertFalse(s.allowToggle(P, 103));
	}

	@Test
	void aDroppedToggleDoesNotMoveTheWindowSoAFloodIsStillAnswered() {
		PassiveSync s = new PassiveSync();
		int acted = 0;
		for (long t = 0; t < 20; t++) if (s.allowToggle(P, t)) acted++; // one every tick
		assertEquals(10, acted, "one in two ticks is acted on");
	}

	@Test
	void playersAreThrottledSeparately() {
		PassiveSync s = new PassiveSync();
		assertTrue(s.allowToggle(P, 5));
		assertTrue(s.allowToggle(Q, 5));
		assertTrue(s.shouldSend(P, IDLE, 5, false));
		assertTrue(s.shouldSend(Q, RUNNING, 5, false));
		assertFalse(s.shouldSend(P, IDLE, 6, false));
	}
}
