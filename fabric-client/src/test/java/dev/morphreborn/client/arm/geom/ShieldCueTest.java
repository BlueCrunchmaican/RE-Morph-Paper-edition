package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.render.ArmLayer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shield's cue in vanilla first person (2026-11-24): lowered, it was wholly below the 70 degree 16:9 lens and nothing showed that it was on; now
 * its rim peeks into the lower corner and pulses slowly, and when it is raised the rim is fatter and one ring runs through the weave as it arrives.
 */
class ShieldCueTest {
	@AfterEach
	void reset() {
		ShieldMotion.clear();
	}

	/** The share of the quads' vertices inside the hand pass's lens: 70 degrees tall at 16:9, so |y| < 0.70 depth and |x| < 1.245 depth. */
	static double visibleShare(List<float[]> vertices) {
		int in = 0;
		for (float[] v : vertices) {
			float depth = -v[2];
			if (depth > 0.5F && Math.abs(v[1]) < 0.7002F * depth && Math.abs(v[0]) < 1.2447F * depth) in++;
		}
		return vertices.isEmpty() ? 0D : in / (double) vertices.size();
	}

	@Test
	void theLoweredShieldShowsItsRimInTheLowerCorner() {
		for (boolean toolLeft : new boolean[] {false, true}) {
			List<float[]> kept = new ArrayList<>();
			ShieldRendererTest.quads(ShieldRendererTest.draw(500F, false, 0, ArmDraw_CTX_FP(), toolLeft, 100F), true, kept);
			double share = visibleShare(kept);
			assertTrue(share > 0.12, "the lowered shield is in the lens (tool left " + toolLeft + "): " + share);
			assertTrue(share < 0.8, "and only its top shows, so it reads as lowered: " + share);
			// the corner it is in: the off hand's side, low
			double sx = 0D;
			int n = 0;
			for (float[] v : kept) {
				if (-v[2] > 0.5F && v[1] > -0.7002F * -v[2]) {
					sx += v[0];
					n++;
				}
			}
			assertTrue(n > 0);
			assertEquals(toolLeft ? 1.0 : -1.0, Math.signum(sx / n), "the off hand's corner");
		}
	}

	private static int ArmDraw_CTX_FP() {
		return ArmDraw.CTX_FP;
	}

	@Test
	void theRaisedShieldIsWhollyInView() {
		List<float[]> kept = new ArrayList<>();
		ShieldRendererTest.quads(ShieldRendererTest.draw(500F, true, 0, ArmDraw.CTX_FP, false, 100F), true, kept);
		assertTrue(visibleShare(kept) > 0.9, "raised: " + visibleShare(kept));
	}

	/** The red rim's vertices at the clock {@code t}, lowered. */
	private static List<float[]> redRim(float t, boolean raised) {
		List<float[]> kept = new ArrayList<>();
		ShieldRendererTest.quads(ShieldRendererTest.draw(500F, raised, 0, ArmDraw.CTX_FP, false, t), true, kept, new ArmLayer[] {ArmLayer.RED});
		return kept;
	}

	@Test
	void theLoweredRimPulsesSlowlyOverAboutTwoSeconds() {
		double a = extent(redRim(100F, false)), b = extent(redRim(110F, false)), c = extent(redRim(120F, false)), d = extent(redRim(140F, false));
		assertNotEquals(a, b, 1e-6, "the rim changes over ten ticks");
		assertTrue(Math.abs(a - d) < 0.05 * a || Math.abs(a - c) > 1e-6, "and comes round within 40 ticks (" + a + ", " + b + ", " + c + ", " + d + ")");
	}

	private static double extent(List<float[]> v) {
		double s = 0D;
		for (float[] p : v) s += Math.abs(p[0]) + Math.abs(p[1]);
		return s;
	}

	@Test
	void raisingFlashesOnceThenSettlesWithAFatterRim() {
		// first seen lowered, raised at tick 101: it arrives at 105; a ring runs from then
		ShieldMotion.raise(7, 100F, 0);
		ShieldMotion.raise(7, 101F, 1);
		assertEquals(ArmDraw.NEVER, ShieldMotion.raisedAge(7, 103F), "still rising");
		assertEquals(2F, ShieldMotion.raisedAge(7, 107F), 1e-4F, "two ticks after arriving");
		List<float[]> flash = new ArrayList<>();
		ShieldRendererTest.quads(ShieldRendererTest.draw(500F, true, 0, ArmDraw.CTX_FP, false, 107F), true, flash);
		ShieldMotion.clear();
		ShieldMotion.raise(7, 80F, 1); // first seen already up: no arrival to flash
		assertTrue(ShieldMotion.raisedAge(7, 107F) > ShieldMotion.BLOCK_TICKS, "long ago");
		List<float[]> calm = new ArrayList<>();
		ShieldRendererTest.quads(ShieldRendererTest.draw(500F, true, 0, ArmDraw.CTX_FP, false, 107F), true, calm);
		assertEquals(flash.size(), calm.size());
		boolean differs = false;
		for (int i = 0; i < flash.size() && !differs; i++) differs = flash.get(i)[2] != calm.get(i)[2];
		assertTrue(differs, "the ring dented the weave");
		// and later the flash is gone
		ShieldMotion.clear();
		ShieldMotion.raise(7, 100F, 0);
		ShieldMotion.raise(7, 101F, 1);
		assertEquals(ArmDraw.NEVER, ShieldMotion.raisedAge(7, 101F + 3F), "not yet");
		assertTrue(ShieldMotion.raisedAge(7, 140F) > ShieldMotion.BLOCK_TICKS, "long after the ring has gone");
	}

	@Test
	void thirdPersonLooksAreUnmovedByTheCue() {
		// the cue is the overlay's only: other players' shields and the body's keep their own placement
		List<float[]> tp = new ArrayList<>();
		ShieldRendererTest.quads(ShieldRendererTest.draw(500F, false, 0, ArmDraw.CTX_TP, false, 100F), false, tp);
		assertTrue(!tp.isEmpty());
	}
}
