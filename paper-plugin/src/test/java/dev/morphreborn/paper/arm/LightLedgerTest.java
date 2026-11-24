package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.lantern.LightLedger;
import dev.morphreborn.paper.arm.rules.LightBeam.Light;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The lantern's per-viewer ledger against a pretend client: whatever the ledger has sent and not taken back, the client
 * shows as light, and every way out (a cell no longer wanted, a viewer out of range, one viewer dropped, the lantern put
 * out) restores exactly that set, never a cell that wasn't sent and never leaving one behind.
 */
class LightLedgerTest {
	private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

	/** A viewer's game: the cells it was sent as light and not yet given back, plus what the ledger did. */
	private static final class Client implements LightLedger.Sender {
		final Map<UUID, Map<Long, Light>> shown = new HashMap<>();
		final List<Set<Long>> restores = new ArrayList<>();
		int lightMessages, lightCells;

		@Override
		public void lights(UUID viewer, Map<Long, Light> cells) {
			assertFalse(cells.isEmpty(), "nothing is sent empty");
			lightMessages++;
			lightCells += cells.size();
			shown.computeIfAbsent(viewer, k -> new HashMap<>()).putAll(cells);
		}

		@Override
		public void restore(UUID viewer, Set<Long> cells) {
			assertFalse(cells.isEmpty(), "nothing is restored empty");
			restores.add(Set.copyOf(cells));
			Map<Long, Light> have = shown.getOrDefault(viewer, new HashMap<>());
			for (Long c : cells) assertTrue(have.remove(c) != null, "restored a cell that was never sent to this viewer: " + Pos.fromLong(c));
			if (have.isEmpty()) shown.remove(viewer);
		}

		Set<Long> shownTo(UUID v) {
			return new HashSet<>(shown.getOrDefault(v, Map.of()).keySet());
		}
	}

	private static long cell(int x, int y, int z) {
		return new Pos(x, y, z).asLong();
	}

	private static Map<Long, Light> wanted(Object... cellsAndLevels) {
		Map<Long, Light> out = new HashMap<>();
		for (int i = 0; i < cellsAndLevels.length; i += 2) {
			Pos p = (Pos) cellsAndLevels[i];
			out.put(p.asLong(), new Light(p, (Integer) cellsAndLevels[i + 1], false));
		}
		return out;
	}

	@Test
	void sendsOnlyWhatIsNewOrChanged() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3), q = new Pos(4, 5, 6);
		ledger.sync(Set.of(A), wanted(p, 15, q, 9), false, client);
		assertEquals(1, client.lightMessages);
		assertEquals(2, client.lightCells);

		ledger.sync(Set.of(A), wanted(p, 15, q, 9), false, client);
		assertEquals(1, client.lightMessages, "nothing changed, nothing sent");

		ledger.sync(Set.of(A), wanted(p, 15, q, 10), false, client);
		assertEquals(2, client.lightMessages);
		assertEquals(3, client.lightCells, "only the changed cell again");
		assertTrue(client.restores.isEmpty());
	}

	@Test
	void aCellNoLongerWantedIsRestoredAndOnlyThatOne() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3), q = new Pos(4, 5, 6);
		ledger.sync(Set.of(A), wanted(p, 15, q, 9), false, client);
		ledger.sync(Set.of(A), wanted(p, 15), false, client);
		assertEquals(List.of(Set.of(q.asLong())), client.restores);
		assertEquals(Set.of(p.asLong()), client.shownTo(A));
		assertEquals(Set.of(p.asLong()), ledger.shown(A));
	}

	@Test
	void fullSendsEveryWantedCellAgain() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3), q = new Pos(4, 5, 6);
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9), false, client);
		int before = client.lightCells;
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9), true, client);
		assertEquals(before + 4, client.lightCells, "both cells to both viewers");
		assertTrue(client.restores.isEmpty());
	}

	@Test
	void aViewerOutOfRangeGetsExactlyWhatWasSentRestored() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3), q = new Pos(4, 5, 6), r = new Pos(-7, 8, -9);
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9), false, client);
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9, r, 11), false, client);
		Set<Long> sentToB = client.shownTo(B);
		assertEquals(Set.of(p.asLong(), q.asLong(), r.asLong()), sentToB);

		client.restores.clear();
		ledger.sync(Set.of(A), wanted(p, 15, q, 9, r, 11), false, client);
		assertEquals(List.of(sentToB), client.restores, "the restore set is the sent set");
		assertTrue(client.shownTo(B).isEmpty());
		assertEquals(Set.of(p.asLong(), q.asLong(), r.asLong()), client.shownTo(A), "the others are undisturbed");
		assertTrue(ledger.shown(B).isEmpty());

		// back in range later: sent afresh, nothing remembered
		int cells = client.lightCells;
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9, r, 11), false, client);
		assertEquals(cells + 3, client.lightCells);
	}

	@Test
	void dropRestoresOneViewersSentCellsAndReturnsThem() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3), q = new Pos(4, 5, 6);
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9), false, client);
		Set<Long> before = client.shownTo(A);
		client.restores.clear();
		Set<Long> restored = ledger.drop(A, client);
		assertEquals(before, restored);
		assertEquals(List.of(before), client.restores);
		assertTrue(client.shownTo(A).isEmpty());
		assertEquals(2, client.shownTo(B).size());
		assertTrue(ledger.drop(A, client).isEmpty(), "a second drop has nothing to give");
		assertEquals(1, client.restores.size());
	}

	@Test
	void clearGivesEveryViewerEverythingBack() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3), q = new Pos(4, 5, 6), r = new Pos(7, 8, 9);
		ledger.sync(Set.of(A, B, C), wanted(p, 15, q, 9), false, client);
		ledger.sync(Set.of(A, B), wanted(p, 15, q, 9, r, 10), false, client);
		Map<UUID, Set<Long>> before = new HashMap<>();
		for (UUID v : List.of(A, B, C)) before.put(v, client.shownTo(v));
		before.values().removeIf(Set::isEmpty);

		Map<UUID, Set<Long>> restored = ledger.clear(client);
		assertEquals(before, restored, "restore set == sent set, per viewer");
		assertTrue(client.shown.isEmpty(), "the clients are left with nothing");
		assertTrue(ledger.isEmpty());
		assertTrue(ledger.clear(client).isEmpty(), "clearing twice restores nothing twice");
		assertEquals(ledger.sentCells(), ledger.restoredCells(), "every cell sent was given back, once each (none was sent twice here)");
	}

	@Test
	void wantedNothingRestoresEverythingButKeepsTheViewers() {
		LightLedger ledger = new LightLedger();
		Client client = new Client();
		Pos p = new Pos(1, 2, 3);
		ledger.sync(Set.of(A), wanted(p, 15), false, client);
		ledger.sync(Set.of(A), Map.of(), false, client);
		assertTrue(client.shown.isEmpty());
		assertEquals(List.of(Set.of(p.asLong())), client.restores);
		assertTrue(ledger.isEmpty());
	}

	/**
	 * A long run of arbitrary wanted sets and viewers, with exits thrown in at random (drop a viewer, clear all): after
	 * every step each viewer's client shows exactly the wanted cells (or nothing, out of range), and every exit restores
	 * exactly what the client showed.
	 */
	@Test
	void everyExitRestoresExactlyWhatWasSentWhateverTheHistory() {
		Random rnd = new Random(20261115);
		UUID[] all = {A, B, C, new UUID(0, 4), new UUID(0, 5)};
		for (int round = 0; round < 40; round++) {
			LightLedger ledger = new LightLedger();
			Client client = new Client();
			for (int step = 0; step < 150; step++) {
				Set<UUID> viewers = new HashSet<>();
				for (UUID v : all) if (rnd.nextInt(3) > 0) viewers.add(v);
				Map<Long, Light> want = new HashMap<>();
				int n = rnd.nextInt(7);
				for (int i = 0; i < n; i++) {
					Pos p = new Pos(rnd.nextInt(12) - 6, rnd.nextInt(6), rnd.nextInt(12) - 6);
					want.put(p.asLong(), new Light(p, 9 + rnd.nextInt(7), rnd.nextBoolean()));
				}
				int exit = rnd.nextInt(10);
				if (exit == 0) {
					UUID v = all[rnd.nextInt(all.length)];
					Set<Long> before = client.shownTo(v);
					client.restores.clear();
					assertEquals(before, ledger.drop(v, client));
					assertEquals(before.isEmpty() ? List.of() : List.of(before), client.restores);
					assertTrue(client.shownTo(v).isEmpty());
					continue;
				}
				if (exit == 1) {
					Map<UUID, Set<Long>> before = new HashMap<>();
					for (UUID v : all) before.put(v, client.shownTo(v));
					before.values().removeIf(Set::isEmpty);
					assertEquals(before, ledger.clear(client));
					assertTrue(client.shown.isEmpty());
					assertTrue(ledger.isEmpty());
					continue;
				}
				ledger.sync(viewers, want, rnd.nextInt(5) == 0, client);
				for (UUID v : all) {
					Map<Long, Light> seen = client.shown.getOrDefault(v, Map.of());
					if (viewers.contains(v)) assertEquals(want, new HashMap<>(seen), "viewer " + v + " shows the wanted light, round " + round + " step " + step);
					else assertTrue(seen.isEmpty(), "a viewer out of range keeps nothing, round " + round + " step " + step);
					assertEquals(client.shownTo(v), ledger.shown(v), "the ledger and the client agree");
				}
			}
			ledger.clear(client);
			assertTrue(client.shown.isEmpty(), "round " + round + " ends clean");
		}
	}
}
