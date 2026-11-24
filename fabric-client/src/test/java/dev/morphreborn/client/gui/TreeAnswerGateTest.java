package dev.morphreborn.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A late answer to the tree's own refresh request must not reopen a tree the player closed (fix round, 2026-11-16). */
class TreeAnswerGateTest {
	@Test
	void anAnswerWithNoRequestOfOursIsAKeyPressAndIsHandled() {
		TreeAnswerGate g = new TreeAnswerGate();
		assertTrue(g.accept(10, false), "the key press opens the tree");
		assertTrue(g.accept(11, true));
	}

	@Test
	void aLateAnswerAfterTheScreenClosedIsSwallowed() {
		TreeAnswerGate g = new TreeAnswerGate();
		g.sent(100);
		assertFalse(g.accept(103, false), "the player pressed Esc before the answer came");
		assertTrue(g.accept(110, false), "the next key press's answer is wanted again");
	}

	@Test
	void anAnswerWhileTheScreenShowsIsAppliedAndUsesUpItsRequest() {
		TreeAnswerGate g = new TreeAnswerGate();
		g.sent(100);
		assertTrue(g.accept(102, true));
		assertTrue(g.accept(150, false), "that request is answered, so a later key press opens the tree");
	}

	@Test
	void everyOutstandingRequestIsSwallowedInOrderBeforeAKeyPressAnswer() {
		TreeAnswerGate g = new TreeAnswerGate();
		g.sent(100);
		g.sent(105);
		assertFalse(g.accept(108, false));
		assertFalse(g.accept(109, false));
		assertTrue(g.accept(112, false), "the third answer is the key press's");
	}

	@Test
	void anUnansweredRequestIsForgottenSoItCannotSwallowALaterKeyPress() {
		TreeAnswerGate g = new TreeAnswerGate();
		g.sent(100); // never answered: the mode went Classic, or the packet was lost
		assertTrue(g.accept(100 + TreeAnswerGate.WINDOW + 1, false));
	}

	@Test
	void aStaleCountDoesNotCarryIntoANewRequest() {
		TreeAnswerGate g = new TreeAnswerGate();
		g.sent(0);
		g.sent(1000); // the first is long gone; only this one is awaited
		assertFalse(g.accept(1002, false));
		assertTrue(g.accept(1003, false));
	}

	@Test
	void resetForgetsEverythingAwaited() {
		TreeAnswerGate g = new TreeAnswerGate();
		g.sent(5);
		g.reset();
		assertTrue(g.accept(6, false));
	}
}
