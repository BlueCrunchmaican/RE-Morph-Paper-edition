package dev.morphreborn.client.arm.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 3D static: how often it re-patterns in each situation, and that each re-pattern sweeps along the arm at 5 px a
 * tick (toward the tip, or back while feeding).
 */
class StaticTest {
	// ArmProtocol's states
	private static final int NONE = 0, FORMING = 1, FORMED = 2, RESHAPING = 3, RETRACTING = 4, MELTING = 5, QUICKFORM = 6;

	/** The ticks (to a 1/20 tick) at which segment 0 of part 0 at {@code phi} gets a new offset, over 0..40 t. */
	private static List<Float> changes(int cadence, float phi) {
		Static st = new Static();
		List<Float> at = new ArrayList<>();
		float lastU = Float.NaN, lastV = Float.NaN;
		for (int i = 0; i <= 800; i++) {
			float t = i / 20F;
			st.set(t, cadence, 1F, 5);
			float u = st.u0(0, 0, phi), v = st.v0(0, 0, phi);
			if (i > 0 && (u != lastU || v != lastV)) at.add(t);
			lastU = u;
			lastV = v;
		}
		return at;
	}

	private static void assertEvery(float ticks, List<Float> changes) {
		assertTrue(changes.size() > 5, "it re-patterns: " + changes);
		// sampled every 1/20 tick, so a change can be seen up to one sample late
		for (int i = 1; i < changes.size(); i++) assertEquals(ticks, changes.get(i) - changes.get(i - 1), 0.051F, "between re-patterns: " + changes);
	}

	@Test
	void idleEveryTwoTicks() {
		int c = Static.cadence(FORMED, false, false, 0);
		assertEquals(2, c);
		assertEvery(2F, changes(c, 0F));
		assertEquals(2, Static.cadence(NONE, false, false, 0));
	}

	@Test
	void formingSpecialsAndHitsEveryTick() {
		for (int state : new int[] {FORMING, RESHAPING, RETRACTING, MELTING, QUICKFORM}) assertEquals(1, Static.cadence(state, false, false, 0), "state " + state);
		assertEquals(1, Static.cadence(FORMED, true, false, 0));
		assertEquals(1, Static.cadence(FORMED, false, true, 0));
		assertEquals(1, Static.cadence(FORMING, false, false, 2), "movement wins over hunger");
		assertEvery(1F, changes(Static.cadence(FORMING, false, false, 0), 0F));
	}

	@Test
	void thinningEveryThreeTicks() {
		int c = Static.cadence(FORMED, false, false, 1);
		assertEquals(3, c);
		assertEvery(3F, changes(c, 7.5F));
	}

	@Test
	void starvingFlickers() {
		for (int stage : new int[] {2, 3}) {
			int c = Static.cadence(FORMED, false, false, stage);
			assertEquals(3, Static.period(c));
			assertNotEquals(3, c, "starving carries its flicker");
		}
		List<Float> starving = changes(Static.STARVING, 0F), thinning = changes(3, 0F);
		assertTrue(starving.size() > thinning.size() + 3, "a starving arm stutters: " + starving.size() + " vs " + thinning.size());
	}

	@Test
	void crawlRunsFivePixelsATick() {
		Static st = new Static();
		for (int cadence : new int[] {1, 2, 3}) {
			for (float dir : new float[] {1F, -1F}) {
				for (float t = 0.013F; t < 30F; t += 0.37F) { // never exactly on a re-pattern
					for (float phi = 0F; phi < 40F; phi += 3.1F) {
						// what segment phi shows at t, phi + 5 px shows a tick later (behind it while feeding)
						st.set(t, cadence, dir, 0);
						int now = st.frame(phi);
						st.set(t + 1F, cadence, dir, 0);
						assertEquals(now, st.frame(phi + 5F * dir), "cadence " + cadence + " dir " + dir + " t " + t + " phi " + phi);
					}
				}
			}
		}
		// with no crawl, the whole piece re-patterns at once
		st.set(4.5F, 2, 0F, 0);
		assertEquals(st.u0(1, 2, 0F), st.u0(1, 2, 30F));
	}

	@Test
	void segmentsPartsAndPlayersDiffer() {
		Static a = new Static().set(10F, 2, 1F, 0), b = new Static().set(10F, 2, 1F, 300);
		assertNotEquals(a.u0(0, 0, 0F), a.u0(0, 1, 0F));
		assertNotEquals(a.u0(0, 0, 0F), a.u0(1, 0, 0F));
		assertNotEquals(a.u0(0, 0, 0F) * 64 + a.v0(0, 0, 0F), b.u0(0, 0, 0F) * 64 + b.v0(0, 0, 0F));
		for (int seg = -3; seg < 50; seg++) {
			float u = a.u0(2, seg, seg * 0.7F), v = a.v0(2, seg, seg * 0.7F);
			assertTrue(u >= 0F && u < 64F && v >= 0F && v < 64F, "offsets stay on the sheet: " + u + ", " + v);
		}
	}
}
