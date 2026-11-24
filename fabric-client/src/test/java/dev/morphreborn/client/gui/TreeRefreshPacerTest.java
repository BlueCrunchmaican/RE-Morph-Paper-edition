package dev.morphreborn.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.gui.UpgradeTreeScreen.TreeLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When the open upgrade tree asks for a fresh copy, and what an in-place refresh keeps (the rig, 2026-11-16: stale levels after an admin change). */
class TreeRefreshPacerTest {
	@Test
	void quietTreeIsPolledEveryFiveSeconds() {
		TreeRefreshPacer p = new TreeRefreshPacer(0);
		for (int t = 1; t < TreeRefreshPacer.POLL; t++) assertFalse(p.due(t), "tick " + t);
		assertTrue(p.due(TreeRefreshPacer.POLL), "an admin's change sends no packet, so the slow poll finds it");
		assertFalse(p.due(TreeRefreshPacer.POLL + 1));
		assertTrue(p.due(2 * TreeRefreshPacer.POLL));
	}

	@Test
	void aSeenChangeAsksOnceAfterTheMinimumGap() {
		TreeRefreshPacer p = new TreeRefreshPacer(0);
		p.changed();
		assertFalse(p.due(5), "not sooner than the gap after the last request (the open itself counts)");
		assertTrue(p.due(TreeRefreshPacer.MIN_GAP));
		assertFalse(p.due(TreeRefreshPacer.MIN_GAP + 1), "one request per change");
	}

	@Test
	void aChangeEveryTenTicksCostsOneRequestASecond() {
		TreeRefreshPacer p = new TreeRefreshPacer(0);
		int requests = 0;
		for (int t = 1; t <= 200; t++) {
			if (t % 10 == 0) p.changed(); // a formed arm's drain: S_SURVIVAL every 10 ticks
			if (p.due(t)) requests++;
		}
		assertEquals(10, requests, "at most one every " + TreeRefreshPacer.MIN_GAP + " ticks (200 / 20)");
	}

	@Test
	void aTreeThatArrivedCoversWhatWasPending() {
		TreeRefreshPacer p = new TreeRefreshPacer(0);
		p.changed();
		p.received(10); // a purchase's answer came meanwhile
		assertFalse(p.due(TreeRefreshPacer.MIN_GAP + 10), "nothing waits any more");
		assertFalse(p.due(TreeRefreshPacer.POLL + 9), "and the poll restarted at the arrival");
		assertTrue(p.due(TreeRefreshPacer.POLL + 10));
	}

	@Test
	void aClockThatWentBackStartsOverInsteadOfWaitingForEver() {
		TreeRefreshPacer p = new TreeRefreshPacer(1000);
		assertFalse(p.due(3));
		assertTrue(p.due(3 + TreeRefreshPacer.POLL));
	}

	private static UpgradeTree.Node node(String id, String parent, int level) {
		return new UpgradeTree.Node(id, id, "", "paper", parent, level, 3, "", "", 5, "", true);
	}

	private static UpgradeTree tree(UpgradeTree.Node... nodes) {
		return new UpgradeTree("", true, 10, 100, List.of(nodes));
	}

	@Test
	void newLevelsAreTheSameShapeSoNothingIsLaidOutAgain() {
		UpgradeTree before = tree(node("a", "root", 3), node("b", "a", 0));
		UpgradeTree after = tree(node("a", "root", 2), node("b", "a", 1));
		assertTrue(before.sameShape(after));
	}

	@Test
	void otherUpgradesOrParentsAreAnotherShape() {
		UpgradeTree base = tree(node("a", "root", 1), node("b", "a", 0));
		assertFalse(base.sameShape(tree(node("a", "root", 1))), "one fewer");
		assertFalse(base.sameShape(tree(node("a", "root", 1), node("c", "a", 0))), "another id, same count (the old size check missed this)");
		assertFalse(base.sameShape(tree(node("a", "root", 1), node("b", "root", 0))), "another parent");
	}

	@Test
	void scrollIsKeptWhenTheContentStillOverflowsAndClampedWhenItShrinks() {
		assertEquals(-30.0, TreeLayout.scrollFor(-30, 500, 300), "kept");
		assertEquals(-200.0, TreeLayout.scrollFor(-350, 500, 300), "clamped to the new end");
		assertEquals(0.0, TreeLayout.scrollFor(12, 500, 300), "never past the start");
		assertEquals(0.0, TreeLayout.scrollFor(0, 500, 300), "starts at the root");
		assertEquals(50.0, TreeLayout.scrollFor(-30, 200, 300), "content that fits is centred");
	}
}
