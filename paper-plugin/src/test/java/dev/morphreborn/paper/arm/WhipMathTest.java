package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.WhipMath;
import dev.morphreborn.paper.arm.rules.WhipSettings;
import org.junit.jupiter.api.Test;

/** The whip's grapple, yank and range rules (spec section 9) at the bundled numbers. */
class WhipMathTest {
	private static final WhipSettings S = WhipSettings.DEFAULTS;
	private static final double EPS = 1e-9;

	private static double len(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	@Test
	void grappleSpeedIsEighteenHundredthsPerBlockWithinTheClamps() {
		// 0.18 a block, never under 0.6 (a hook 3 blocks away is already past 0.54)
		assertEquals(0.6, WhipMath.grappleSpeed(0, 1, S), EPS);
		assertEquals(0.6, WhipMath.grappleSpeed(2, 1, S), EPS);
		assertEquals(0.18 * 5, WhipMath.grappleSpeed(5, 1, S), EPS);
		assertEquals(0.18 * 8, WhipMath.grappleSpeed(8, 2, S), EPS);
		// the ceiling is 1.6 + 0.2 a level
		assertEquals(1.8, WhipMath.grappleSpeed(12, 1, S), EPS);
		assertEquals(2.0, WhipMath.grappleSpeed(16, 2, S), EPS);
		assertEquals(2.2, WhipMath.grappleSpeed(20, 3, S), EPS);
		assertEquals(2.2, WhipMath.grappleSpeed(500, 3, S), EPS);
		assertEquals(1.8, WhipMath.grappleSpeed(500, 1, S), EPS, "the ceiling is the level's own");
	}

	@Test
	void theLineBreaksBeyondTheRange() {
		assertTrue(WhipMath.inRange(12, 1, S));
		assertFalse(WhipMath.inRange(12.01, 1, S));
		assertTrue(WhipMath.inRange(16, 2, S));
		assertFalse(WhipMath.inRange(16.5, 2, S));
		assertTrue(WhipMath.inRange(20, 3, S));
		assertFalse(WhipMath.inRange(20.5, 3, S));
		assertFalse(WhipMath.farEnough(1.2));
		assertTrue(WhipMath.farEnough(1.5));
	}

	@Test
	void theGrappleGoesTowardTheHookAtThatSpeed() {
		double[] v = WhipMath.grapple(6, 0, 8, false, false, 1, S);
		assertEquals(WhipMath.grappleSpeed(10, 1, S), len(v), 1e-9);
		assertEquals(0.6, v[0] / len(v), 1e-9, "the direction of the gap");
		assertEquals(0.8, v[2] / len(v), 1e-9);
		assertEquals(0, v[1], EPS, "level with the hook, in the air: no lift");
	}

	@Test
	void aHookAboveTheEyesLiftsAQuarterBlockMore() {
		double[] flat = WhipMath.grapple(0, 4, 4, false, false, 2, S);
		double[] up = WhipMath.grapple(0, 4, 4, true, false, 2, S);
		assertEquals(flat[1] + 0.25, up[1], EPS);
		assertEquals(flat[0], up[0], EPS);
		assertEquals(flat[2], up[2], EPS);
	}

	@Test
	void onTheGroundAFlatPullStillLiftsYouALittle() {
		double[] flat = WhipMath.grapple(10, 0, 0, false, true, 1, S);
		assertEquals(WhipMath.GROUND_LIFT, flat[1], EPS);
		double[] down = WhipMath.grapple(10, -1, 0, false, true, 1, S);
		assertEquals(WhipMath.GROUND_LIFT, down[1], EPS, "a hook below you on the ground lifts you too");
		double[] steep = WhipMath.grapple(0, 6, 1, false, true, 1, S);
		assertTrue(steep[1] > 1, "a pull that already lifts isn't changed: " + steep[1]);
		assertArrayEquals(new double[] {0, 0, 0}, WhipMath.grapple(0, 0, 0, true, true, 3, S), EPS, "no gap, no pull");
	}

	@Test
	void theYankIsTheLevelsMultipleOfVanillasPull() {
		// vanilla adds 0.1 of the gap; the yank is 2.0 / 2.4 / 2.8 times that
		double[] lvl1 = WhipMath.yank(0, 0, 5, 1, 1, false, S);
		double[] lvl2 = WhipMath.yank(0, 0, 5, 2, 1, false, S);
		double[] lvl3 = WhipMath.yank(0, 0, 5, 3, 1, false, S);
		assertEquals(0.1 * 5 * 2.0, lvl1[2], EPS);
		assertEquals(0.1 * 5 * 2.4, lvl2[2], EPS);
		assertEquals(0.1 * 5 * 2.8, lvl3[2], EPS);
		assertEquals(1.6 + 0.4 * 1, S.factor(1), EPS, "the spec's 1.6 + 0.4 a level");
		assertEquals(1.6 + 0.4 * 2, S.factor(2), EPS);
		assertEquals(1.6 + 0.4 * 3, S.factor(3), EPS);
	}

	@Test
	void whenVanillaPullsToo_OnlyTheRestIsAdded() {
		double[] extra = WhipMath.yank(3, 1, 4, 2, 1, true, S);
		double[] all = WhipMath.yank(3, 1, 4, 2, 1, false, S);
		for (int i = 0; i < 3; i++) {
			// vanilla's own pull (gap * 0.1) plus the extra is the whole yank
			double vanilla = new double[] {3, 1, 4}[i] * WhipMath.VANILLA_PULL;
			assertEquals(all[i], extra[i] + vanilla, EPS);
		}
	}

	@Test
	void aPlayerIsYankedAtTheLesserShareAndNothingIsEverNegative() {
		double[] creature = WhipMath.yank(0, 0, 4, 3, 1, false, S);
		double[] player = WhipMath.yank(0, 0, 4, 3, 0.5, false, S);
		assertEquals(creature[2] / 2, player[2], EPS);
		assertArrayEquals(new double[] {0, 0, 0}, WhipMath.yank(0, 0, 4, 3, 0, false, S), EPS);
		assertArrayEquals(new double[] {0, 0, 0}, WhipMath.yank(0, 0, 4, 3, -1, false, S), EPS, "a negative share is none");
	}

	@Test
	void aYankIsCappedSoALongLineDoesntFlingAnything() {
		double[] far = WhipMath.yank(0, 0, 100, 3, 1, false, S);
		assertEquals(WhipMath.YANK_CAP, len(far), 1e-9);
		double[] diagonal = WhipMath.yank(30, 30, 30, 3, 1, false, S);
		assertEquals(WhipMath.YANK_CAP, len(diagonal), 1e-9);
		assertEquals(diagonal[0], diagonal[1], 1e-9, "the direction is kept");
	}

	@Test
	void creaturesUnderTwelveHundredKilogramsAreLight() {
		assertTrue(WhipMath.light(0, S));
		assertTrue(WhipMath.light(648, S), "a player");
		assertTrue(WhipMath.light(1199.9, S));
		assertFalse(WhipMath.light(1200, S), "at the limit it pulls you");
		assertFalse(WhipMath.light(5000, S));
	}
}
