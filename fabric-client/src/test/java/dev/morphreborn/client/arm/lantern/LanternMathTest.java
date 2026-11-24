package dev.morphreborn.client.arm.lantern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.morphreborn.client.arm.ArmClientModule;
import dev.morphreborn.client.arm.ArmLights;
import org.junit.jupiter.api.Test;

/**
 * The lantern's client maths against section 3.10: the spot's falloff {@code 15·(1 − d/8)^0.7} and its box, the 0.35 block
 * hysteresis on its centre and the 2 degree one on the line of light, the beam's reach by level, the lure's place as the
 * server's lure cell has it, and the motes' cadence and placing. And the optional-dependency contract: without
 * LambDynamicLights the manager is null, the module loads and starts without touching the mod, and the part that names its
 * classes is never needed.
 */
class LanternMathTest {
	// ------------------------------------------------------------------ the spot

	@Test
	void spotLightFollowsTheSpecsFalloff() {
		assertEquals(15.0, ArmLantern.Spot.luminance(0.0), 1e-9, "full strength at the centre");
		assertEquals(15.0 * Math.pow(0.5, 0.7), ArmLantern.Spot.luminance(4.0), 1e-9, "half way out");
		assertEquals(15.0 * Math.pow(0.875, 0.7), ArmLantern.Spot.luminance(1.0), 1e-9);
		assertEquals(0.0, ArmLantern.Spot.luminance(8.0), 1e-9, "nothing at 8 blocks");
		assertEquals(0.0, ArmLantern.Spot.luminance(20.0), 1e-9);
		double last = Double.MAX_VALUE;
		for (double d = 0.0; d < 8.0; d += 0.25) {
			double l = ArmLantern.Spot.luminance(d);
			assertTrue(l <= last, "the light only falls with distance (" + d + ")");
			assertTrue(l >= 0.0 && l <= 15.0);
			last = l;
		}
	}

	@Test
	void spotAnswersForTheBlocksMiddleAndItsBoxIsPlusMinusEight() {
		ArmLantern.Spot s = new ArmLantern.Spot(10.5, 64.5, -3.5);
		assertEquals(15.0, s.lightAt(10, 64, -4), 1e-9, "the block whose middle is the centre");
		assertEquals(ArmLantern.Spot.luminance(3.0), s.lightAt(13, 64, -4), 1e-9, "three blocks east");
		assertEquals(0.0, s.lightAt(18, 64, -4), 1e-9);
		assertEquals(0.0, s.lightAt(10, 72, -4), 1e-9, "8 blocks up");
		assertArrayEquals(new int[] {2, 56, -12, 18, 72, 4}, s.box());
		ArmLantern.Spot neg = new ArmLantern.Spot(-0.25, 0.0, -0.25);
		assertArrayEquals(new int[] {-9, -8, -9, 7, 8, 7}, neg.box(), "floor, not truncation, below zero");
	}

	@Test
	void spotOnlyMovesPastThirtyFiveHundredthsOfABlock() {
		ArmLantern.Spot s = new ArmLantern.Spot(0.0, 0.0, 0.0);
		assertTrue(s.changed(), "the first report always counts");
		assertFalse(s.changed(), "nothing moved");
		s.moveTo(0.2, 0.1, 0.0);
		assertFalse(s.changed(), "a hand's tremor");
		assertEquals(0.0, s.x(), 0.0, "and the light still answers for where it was");
		s.moveTo(0.34, 0.0, 0.0);
		assertFalse(s.changed(), "just under");
		s.moveTo(0.36, 0.0, 0.0);
		assertTrue(s.changed(), "past 0.35 it follows");
		assertEquals(0.36, s.x(), 1e-12);
		assertFalse(s.changed(), "and is steady again");
		// creeping in small steps still gets there: the comparison is to what was last reported, not to the last tick
		for (int i = 1; i <= 6; i++) s.moveTo(0.35 + 0.1 * i, 0.0, 0.0);
		assertTrue(s.changed());
	}

	// ------------------------------------------------------------------ the beam

	@Test
	void beamReachesSixteenTwentyFourThirtyTwo() {
		assertEquals(16.0, ArmLantern.Beam.reach(1), 0.0);
		assertEquals(24.0, ArmLantern.Beam.reach(2), 0.0);
		assertEquals(32.0, ArmLantern.Beam.reach(3), 0.0);
		assertEquals(16.0, ArmLantern.Beam.reach(0), 0.0, "an unknown level isn't longer than the first");
		assertEquals(32.0, ArmLantern.Beam.reach(9), 0.0);
	}

	@Test
	void theLurePointIsTheServersLureCell() {
		double[] p = new double[3];
		// facing south (look +z), right-handed: the lure is on the left, which is +x when facing +z
		ArmLantern.Beam.lurePoint(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, true, p);
		assertEquals(0.45, p[0], 1e-9, "off side: left of a player facing +z is +x");
		assertEquals(0.4, p[1], 1e-9, "0.4 up");
		assertEquals(0.0, p[2], 1e-9);
		ArmLantern.Beam.lurePoint(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, false, p);
		assertEquals(-0.45, p[0], 1e-9, "left-handed: the lure is on the right");
		// facing east (+x): the left is -z
		ArmLantern.Beam.lurePoint(10.0, 70.0, 5.0, 1.0, 0.0, 0.0, true, p);
		assertEquals(10.0, p[0], 1e-9);
		assertEquals(70.4, p[1], 1e-9);
		assertEquals(5.0 - 0.45, p[2], 1e-9);
		// looking steeply down: "up" tips toward the way they face and the lure stays within the spec's distance of the eyes
		double lx = 0.0, ly = -0.8, lz = 0.6;
		ArmLantern.Beam.lurePoint(0.0, 0.0, 0.0, lx, ly, lz, true, p);
		assertEquals(Math.sqrt(0.4 * 0.4 + 0.45 * 0.45), Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-9);
		// straight down: no horizontal to turn a quarter, still a finite point
		ArmLantern.Beam.lurePoint(0.0, 0.0, 0.0, 0.0, -1.0, 0.0, true, p);
		for (double v : p) assertTrue(Double.isFinite(v));
	}

	@Test
	void theLineOfLightMovesAtHalfABlockOrTwoDegrees() {
		double[] s0 = {0, 0, 0}, e0 = {0, 0, 10};
		assertFalse(ArmLantern.Beam.lineChanged(s0, e0, new double[] {0, 0, 0}, new double[] {0, 0, 10}), "the same line");
		assertFalse(ArmLantern.Beam.lineChanged(s0, e0, new double[] {0.1, 0, 0}, new double[] {0.1, 0, 10}), "a tremor");
		assertTrue(ArmLantern.Beam.lineChanged(s0, e0, new double[] {0.36, 0, 0}, new double[] {0.36, 0, 10}), "the start moved past 0.35");
		assertTrue(ArmLantern.Beam.lineChanged(s0, e0, s0, new double[] {0, 0.4, 10}), "the end moved 0.4");
		// on a short line the aim can change by more than 2 degrees while the end moves under 0.35 blocks: only the angle fires
		double[] shortEnd = {0, 0, 6};
		double small = Math.tan(Math.toRadians(1.5)) * 6.0, big = Math.tan(Math.toRadians(2.5)) * 6.0;
		assertFalse(ArmLantern.Beam.lineChanged(s0, shortEnd, s0, new double[] {small, 0, 6}), "1.5 degrees");
		assertTrue(ArmLantern.Beam.lineChanged(s0, shortEnd, s0, new double[] {big, 0, 6}), "2.5 degrees, though the end only moved " + big + " blocks");
	}

	@Test
	void landingPointSmoothingGlidesToItsTarget() {
		double x = 0.0;
		for (int i = 0; i < 12; i++) x = ArmLantern.Beam.smooth(x, 10.0, 0.5);
		assertEquals(10.0, x, 0.01);
		assertEquals(5.0, ArmLantern.Beam.smooth(0.0, 10.0, 0.5), 0.0);
	}

	// ------------------------------------------------------------------ the motes

	@Test
	void oneMoteEveryFourTicksPerPlayerStaggered() {
		int a = 0, b = 0;
		for (long t = 0; t < 40; t++) {
			if (LanternMotes.due(t, 100)) a++;
			if (LanternMotes.due(t, 101)) b++;
		}
		assertEquals(10, a, "every 4th tick for 40 ticks");
		assertEquals(10, b);
		boolean together = false;
		for (long t = 0; t < 40; t++) together |= LanternMotes.due(t, 100) && LanternMotes.due(t, 101);
		assertFalse(together, "neighbouring ids never shed on the same tick");
		assertTrue(LanternMotes.due(-4L, 0), "ticks never go negative, but the staggering doesn't care");
	}

	@Test
	void motesOnlyFormWithinEightBlocksOfTheCamera() {
		double[] r = new double[2];
		// a beam straight ahead of a camera at its start: the first 8 blocks
		assertTrue(LanternMotes.nearRange(0, 0, 0, 0, 0, 1, 30, 0, 0, 0, 8, r));
		assertEquals(LanternMotes.FROM, r[0], 1e-9);
		assertEquals(8.0, r[1], 1e-9);
		// a camera 5 blocks to the side of the beam: it sees a chord of it, centred beside it
		assertTrue(LanternMotes.nearRange(0, 0, 0, 0, 0, 1, 30, 5, 0, 15, 8, r));
		assertEquals(15.0 - 6.2449, r[0], 1e-3);
		assertEquals(15.0 + 6.2449, r[1], 1e-3);
		// a camera far away sees none of it
		assertFalse(LanternMotes.nearRange(0, 0, 0, 0, 0, 1, 30, 40, 0, 15, 8, r));
		// behind the lure, within range: only what is ahead counts, and not the first 0.7 blocks
		assertTrue(LanternMotes.nearRange(0, 0, 0, 0, 0, 1, 30, 0, 0, -5, 8, r));
		assertEquals(LanternMotes.FROM, r[0], 1e-9);
		assertEquals(3.0, r[1], 1e-9);
		// a beam shorter than the gap has none
		assertFalse(LanternMotes.nearRange(0, 0, 0, 0, 0, 1, 0.5, 0, 0, 0, 8, r));
	}

	@Test
	void noMoteIsShedNearTheCamera() {
		// the owner's own camera sits a few tenths from where motes start (0.7 to 1.6 along the beam, 0.22 off it): those would fill the screen
		assertFalse(LanternMotes.clearOfCamera(0.9, 0, 0, 0, 0, 0));
		assertFalse(LanternMotes.clearOfCamera(0, 0, 2.4, 0, 0, 0));
		assertTrue(LanternMotes.clearOfCamera(0, 0, 2.5, 0, 0, 0), "from the clearance out a mote is fine");
		assertTrue(LanternMotes.clearOfCamera(6, 0, 0, 0, 0, 0), "a watcher's camera is a few blocks off");
	}

	@Test
	void motesThickenTowardTheLure() {
		assertEquals(2.0, LanternMotes.pick(0.0, 2.0, 10.0), 1e-9);
		assertEquals(10.0, LanternMotes.pick(1.0, 2.0, 10.0), 1e-9);
		assertEquals(4.0, LanternMotes.pick(0.5, 2.0, 10.0), 1e-9, "a quarter of the samples land in the first quarter... of the range's half");
		int near = 0;
		for (int i = 0; i < 1000; i++) {
			if (LanternMotes.pick(i / 1000.0, 0.0, 10.0) < 5.0) near++;
		}
		assertTrue(near > 650, "more than half the motes are in the near half: " + near);
	}

	// ------------------------------------------------------------------ LambDynamicLights is optional

	@Test
	void withoutLambDynamicLightsNothingIsThereAndNothingBreaks() {
		boolean present;
		try {
			Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior", false, getClass().getClassLoader());
			present = true;
		} catch (ClassNotFoundException | LinkageError e) {
			present = false;
		}
		// the API is compile-only: tests run without it, which is exactly the player without the mod
		assumeTrue(!present, "the API is on the test classpath; this only checks a game without it");
		assertNull(ArmLights.manager, "nothing sets the manager without the mod's own entrypoint");
		assertFalse(ArmLights.available());
		// the module, the pure parts and the motes all load and run without ever naming a LambDynamicLights class
		ArmLantern module = new ArmLantern();
		assertNotNull(module);
		assertTrue(module instanceof ArmClientModule);
		assertNotNull(new ArmLantern.Spot(0, 0, 0));
		assertEquals(16.0, ArmLantern.Beam.reach(1), 0.0);
		// the part that does name it is only reached with a manager, so asking for it here would fail, as it should
		boolean failed = false;
		try {
			Class.forName("dev.morphreborn.client.arm.lantern.SpotLightBehavior", true, getClass().getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			failed = true;
		}
		assertTrue(failed, "SpotLightBehavior implements the mod's interface, so it can't load without it");
	}

	@Test
	void theModuleIsListedForTheClientToLoad() throws Exception {
		Class<?> c = Class.forName("dev.morphreborn.client.arm.lantern.ArmLantern");
		assertTrue(ArmClientModule.class.isAssignableFrom(c));
		assertNotNull(c.getDeclaredConstructor().newInstance(), "a public no-argument constructor, as ArmClientModules needs");
	}
}
