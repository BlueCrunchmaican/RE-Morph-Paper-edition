package dev.morphreborn.client.arm.lantern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lantern's dynamic lights follow its lit state (section 3.10, acceptance for C-LANTERN): added when a lantern is
 * lit, aimed while it stays lit, the line moved only when it moved enough, removed when it goes out, when its player
 * vanishes or when the setting is turned off, and with no LambDynamicLights (no sink) nothing happens and nothing
 * breaks. Driven through the module's reconcile step with a recording sink in place of the mod's behaviours.
 */
class LanternLightsTest {
	/** Records what it is asked; its handles are numbers. */
	private static final class Recorder implements ArmLantern.Sink {
		final List<String> calls = new ArrayList<>();
		int made;
		boolean failNext;

		@Override
		public Object create(double x, double y, double z, double[] lure, double[] hit) {
			if (failNext) throw new IllegalStateException("the mod's API moved");
			calls.add("create");
			return ++made;
		}

		@Override
		public void spot(Object handle, double x, double y, double z) {
			calls.add("spot" + handle);
		}

		@Override
		public void line(Object handle, double[] lure, double[] hit) {
			calls.add("line" + handle);
		}

		@Override
		public void remove(Object handle) {
			calls.add("remove" + handle);
		}

		long count(String what) {
			return calls.stream().filter(c -> c.startsWith(what)).count();
		}
	}

	private final Map<UUID, ArmLantern.Track> tracks = new HashMap<>();
	private final UUID a = new UUID(0, 1), b = new UUID(0, 2);

	@BeforeEach
	@AfterEach
	void clean() {
		ArmLantern.resetFailure();
	}

	private ArmLantern.Track lit(UUID who, long now) {
		ArmLantern.Track t = tracks.computeIfAbsent(who, k -> new ArmLantern.Track());
		t.seen = now;
		t.has = true;
		t.hx = 5;
		t.hy = 64;
		t.hz = 5;
		t.lure[0] = 0.4;
		t.lure[1] = 65.9;
		t.lure[2] = 0;
		t.hit[0] = 5;
		t.hit[1] = 64;
		t.hit[2] = 5;
		return t;
	}

	@Test
	void addedWhenLitAndRemovedWhenOut() {
		Recorder sink = new Recorder();
		lit(a, 10);
		ArmLantern.reconcile(tracks, 10, sink, true);
		assertEquals(1, sink.count("create"), "lit: a spot and a line are added");
		assertNotNull(tracks.get(a).handle);
		lit(a, 11);
		ArmLantern.reconcile(tracks, 11, sink, true);
		assertEquals(1, sink.count("create"), "and not added again while it stays lit");
		assertEquals(1, sink.count("spot"), "the spot is aimed every tick (it decides itself whether that matters)");
		assertEquals(0, sink.count("remove"));
		// the lantern goes out: nothing marks it at tick 12
		ArmLantern.reconcile(tracks, 12, sink, true);
		assertEquals(1, sink.count("remove"), "out: both lights are removed");
		assertFalse(tracks.containsKey(a), "and the player is forgotten");
		// lit again: a fresh pair
		lit(a, 13);
		ArmLantern.reconcile(tracks, 13, sink, true);
		assertEquals(2, sink.count("create"));
	}

	@Test
	void theLineOnlyMovesWhenItMovedEnough() {
		Recorder sink = new Recorder();
		lit(a, 1);
		ArmLantern.reconcile(tracks, 1, sink, true);
		ArmLantern.Track t = lit(a, 2);
		t.lure[0] += 0.1; // a tremor
		t.hit[0] += 0.1;
		ArmLantern.reconcile(tracks, 2, sink, true);
		assertEquals(0, sink.count("line"), "0.1 blocks isn't worth moving the line for");
		t = lit(a, 3);
		t.hit[0] = 6.0; // the beam swung a block
		ArmLantern.reconcile(tracks, 3, sink, true);
		assertEquals(1, sink.count("line"));
		// creeping 0.1 a tick: compared with where it was last set, so it does move once it has crept far enough
		long before = sink.count("line");
		for (int i = 0; i < 6; i++) {
			t = lit(a, 4 + i);
			t.hit[0] = 6.0 + 0.1 * (i + 1);
			ArmLantern.reconcile(tracks, 4 + i, sink, true);
		}
		assertTrue(sink.count("line") > before, "the creep adds up");
	}

	@Test
	void eachPlayerHasTheirOwnLights() {
		Recorder sink = new Recorder();
		lit(a, 1);
		lit(b, 1);
		ArmLantern.reconcile(tracks, 1, sink, true);
		assertEquals(2, sink.count("create"));
		lit(b, 2); // only b stays lit
		ArmLantern.reconcile(tracks, 2, sink, true);
		assertEquals(1, sink.count("remove"), "a's went");
		assertTrue(tracks.containsKey(b));
		assertNotNull(tracks.get(b).handle, "b's stayed");
	}

	@Test
	void turningTheSettingOffTakesTheLightsOutWhileLit() {
		Recorder sink = new Recorder();
		lit(a, 1);
		ArmLantern.reconcile(tracks, 1, sink, true);
		lit(a, 2);
		ArmLantern.reconcile(tracks, 2, sink, false);
		assertEquals(1, sink.count("remove"), "the setting is off: the behaviours must not stay in the mod");
		assertNull(tracks.get(a).handle);
		assertTrue(tracks.containsKey(a), "the beam is still tracked for the motes");
		lit(a, 3);
		ArmLantern.reconcile(tracks, 3, sink, false);
		assertEquals(1, sink.count("create"), "and nothing is made while it is off");
		lit(a, 4);
		ArmLantern.reconcile(tracks, 4, sink, true);
		assertEquals(2, sink.count("create"), "back on: lit again");
	}

	@Test
	void withoutLambDynamicLightsNothingHappensAndNothingBreaks() {
		// no sink: the manager is null, as on every client without the mod
		lit(a, 1);
		ArmLantern.reconcile(tracks, 1, null, true);
		assertNull(tracks.get(a).handle, "no light made");
		ArmLantern.reconcile(tracks, 2, null, true);
		assertTrue(tracks.isEmpty(), "and the player is forgotten when they go out, as ever");
	}

	@Test
	void aSinkThatFailsIsGivenUpOnWithoutTakingTheGameDown() {
		Recorder sink = new Recorder();
		sink.failNext = true;
		lit(a, 1);
		ArmLantern.reconcile(tracks, 1, sink, true); // must not throw
		assertNull(tracks.get(a).handle);
		assertNull(dev.morphreborn.client.arm.ArmLights.manager);
	}
}
