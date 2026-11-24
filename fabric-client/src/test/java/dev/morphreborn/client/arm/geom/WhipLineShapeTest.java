package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** The whip line's blend of a thrown chain's arc and a tendril's straight shot (decided 2026-11-16). */
class WhipLineShapeTest {
	private static WhipLineShape.Shape arcing(float dx, float dy, float dz, float vx, float vy, float vz) {
		WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(dx, dy, dz).trail(vx, vy, vz);
		sh.arc = 1F;
		return sh;
	}

	@Test
	void tautLineIsStraightFromTheWhipToTheHook() {
		WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(100F, 30F, -40F);
		Vector3f p = new Vector3f();
		for (float s = 0F; s <= 1F; s += 0.125F) {
			sh.at(s, p);
			assertEquals(100F * (1F - s), p.x, 1e-3F);
			assertEquals(30F * (1F - s), p.y, 1e-3F);
			assertEquals(-40F * (1F - s), p.z, 1e-3F);
		}
	}

	@Test
	void everyShapeIsPinnedAtTheWhipAndTheHook() {
		WhipLineShape.Shape sh = arcing(120F, -20F, 60F, 0.3F, -0.25F, 0.1F);
		sh.slack = 0.3F;
		sh.writhe = 0.8F;
		sh.shiver = 4F;
		sh.phase = 1.7F;
		sh.time = 33F;
		Vector3f p = new Vector3f();
		sh.at(0F, p);
		assertEquals(120F, p.x, 1e-3F);
		assertEquals(-20F, p.y, 1e-3F);
		assertEquals(60F, p.z, 1e-3F);
		sh.at(1F, p);
		assertEquals(0F, p.length(), 1e-3F);
	}

	@Test
	void arcArrivesAtTheHookAlongItsHeading() {
		// a hook flying away from a whip 160 px behind it, climbing a little: the curve's tangent at the hook is its heading
		WhipLineShape.Shape sh = arcing(-160F, -10F, 0F, 0.5F, 0.1F, 0F);
		sh.sag = 0F;
		Vector3f p = new Vector3f();
		sh.at(0.999F, p);
		float n = p.length();
		assertEquals(-0.5F / (float) Math.hypot(0.5, 0.1), p.x / n, 0.01F, "trailing straight back along the heading");
		assertEquals(-0.1F / (float) Math.hypot(0.5, 0.1), p.y / n, 0.01F);
	}

	@Test
	void aHookFlyingStraightAwayFromTheWhipDrawsAStraightShot() {
		WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(-100F, 0F, 0F).trail(0.6F, 0F, 0F);
		assertEquals(0F, sh.bx, 1e-3F);
		assertEquals(0F, sh.by, 1e-3F);
		assertEquals(0F, sh.bz, 1e-3F);
	}

	@Test
	void aFallingHookHumpsTheLineUpAndARisingOneSagsIt() {
		// the real path of a thrown chain: a falling hook is trailed by a line that came up and over, a rising one by one that hangs below
		WhipLineShape.Shape falling = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(0.3F, -0.3F, 0F);
		assertTrue(falling.by > 1F, "control point above the straight line: " + falling.by);
		WhipLineShape.Shape rising = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(0.3F, 0.3F, 0F);
		assertTrue(rising.by < -1F, "control point below it: " + rising.by);
		Vector3f p = new Vector3f();
		falling.arc = 1F;
		falling.sag = 0F;
		falling.at(0.5F, p);
		assertTrue(p.y > 1F, "the midpoint rides above the straight line");
	}

	@Test
	void theArcIsGentleHoweverTheHookTurns() {
		// a hook heading straight back at the whip would fold the line double without the cap: the bulge stays within 30 percent of the length
		WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(-0.6F, 0.2F, 0F);
		assertTrue((float) Math.sqrt(sh.bx * sh.bx + sh.by * sh.by + sh.bz * sh.bz) <= WhipLineShape.MAX_BULGE * 160F + 1e-3F);
		sh.arc = 1F;
		Vector3f p = new Vector3f();
		for (float s = 0F; s <= 1F; s += 0.05F) {
			sh.at(s, p);
			// within the box the straight line spans, widened by the cap and the sag
			assertTrue(Math.abs(p.y) < 0.3F * 160F + WhipLineShape.MAX_SAG_PX, "s=" + s + " y=" + p.y);
		}
	}

	@Test
	void aSlowHookHasNoHeadingToTrustAndAFastOneIsTrustedFully() {
		assertEquals(0F, WhipLineShape.trust(0F), 1e-6F);
		assertEquals(0F, WhipLineShape.trust(WhipLineShape.SLOW), 1e-6F);
		assertEquals(1F, WhipLineShape.trust(WhipLineShape.FAST), 1e-6F);
		assertEquals(1F, WhipLineShape.trust(2F), 1e-6F);
		float last = 0F;
		for (float v = 0F; v <= 0.5F; v += 0.02F) {
			float t = WhipLineShape.trust(v);
			assertTrue(t >= last - 1e-6F, "never falls as the hook speeds up");
			last = t;
		}
		WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(0.05F, -0.05F, 0F);
		assertEquals(0F, sh.by, 1e-6F, "too slow: straight, with its own sag only");
		assertTrue(sh.sag > 0F);
		// a fast shot sags less than a slow throw
		WhipLineShape.Shape fast = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(0.6F, 0F, 0F);
		assertTrue(fast.sag < sh.sag);
	}

	@Test
	void sagIsBoundedAndNeverNaNOnADegenerateLine() {
		WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(0F, 0F, 0F).trail(0.3F, 0F, 0F);
		sh.arc = 1F;
		Vector3f p = new Vector3f();
		sh.at(0.5F, p);
		assertFalse(Float.isNaN(p.x + p.y + p.z));
		WhipLineShape.Shape far = new WhipLineShape.Shape().aim(4000F, 0F, 0F).trail(0.3F, 0F, 0F);
		assertEquals(WhipLineShape.MAX_SAG_PX * 0.5F, new WhipLineShape.Shape().aim(4000F, 0F, 0F).trail(0.6F, 0F, 0F).sag, 1e-3F);
		assertTrue(far.sag <= WhipLineShape.MAX_SAG_PX);
	}

	@Test
	void theFrameAcrossTheLineIsSquareEvenOnAPlumbLine() {
		for (float[] d : new float[][] {{50F, 20F, 10F}, {0F, 80F, 0F}, {0F, -80F, 0F}, {-7F, 0F, 3F}}) {
			WhipLineShape.Shape sh = new WhipLineShape.Shape().aim(d[0], d[1], d[2]);
			float fx = d[0] / sh.len, fy = d[1] / sh.len, fz = d[2] / sh.len;
			assertEquals(1F, sh.sx * sh.sx + sh.sy * sh.sy + sh.sz * sh.sz, 1e-4F);
			assertEquals(1F, sh.ux * sh.ux + sh.uy * sh.uy + sh.uz * sh.uz, 1e-4F);
			assertEquals(0F, sh.sx * fx + sh.sy * fy + sh.sz * fz, 1e-4F);
			assertEquals(0F, sh.ux * fx + sh.uy * fy + sh.uz * fz, 1e-4F);
			assertEquals(0F, sh.sx * sh.ux + sh.sy * sh.uy + sh.sz * sh.uz, 1e-4F);
		}
	}

	@Test
	void modeFollowsWhatTheHookDoes() {
		assertEquals(WhipLineShape.RESTING, WhipLineShape.modeOf(true, false, 0F));
		assertEquals(WhipLineShape.RESTING, WhipLineShape.modeOf(true, false, 50F), "still in water is only bobbing");
		assertEquals(WhipLineShape.ANCHORED, WhipLineShape.modeOf(true, true, 0F), "a creature caught in water is held");
		assertEquals(WhipLineShape.FLYING, WhipLineShape.modeOf(false, false, 0F));
		assertEquals(WhipLineShape.FLYING, WhipLineShape.modeOf(false, false, WhipLineShape.STILL_TICKS - 0.1F));
		assertEquals(WhipLineShape.ANCHORED, WhipLineShape.modeOf(false, false, WhipLineShape.STILL_TICKS));
		assertEquals(WhipLineShape.ANCHORED, WhipLineShape.modeOf(false, true, 0F));
	}

	@Test
	void aHookThatStopsDeadOnAWallIsAnchoredAtOnce() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		mo.update(1F, false, false, 0.5F, 0.1F, 0F);
		assertEquals(WhipLineShape.FLYING, mo.mode());
		// the client's own hook zeroes its motion on a wall, and the server holds an anchor at zero
		mo.update(1F, false, false, 0F, 0F, 0F);
		assertTrue(mo.anchored(), "the stop is the catch");
		assertEquals(0F, mo.snapAge, 1e-6F, "and the snap starts on that frame");
		mo.update(1F, false, false, 0.4F, 0F, 0F);
		assertEquals(WhipLineShape.FLYING, mo.mode(), "moving again: the anchor was let go");
	}

	@Test
	void aHookHeldByTheServerWithGravityPlayingOnTheClientStaysAnchored() {
		// the client's hook adds gravity and drag to a zero it keeps being reset to: about -0.0276 a tick, well under the still line
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		mo.update(1F, false, false, 0.5F, 0.3F, 0F);
		for (int i = 0; i < 40; i++) {
			mo.update(0.25F, false, false, 0F, -0.0276F, 0F);
			assertTrue(mo.anchored() || i == 0, "frame " + i);
		}
		assertTrue(mo.anchored());
	}

	@Test
	void aHookThatMerelyCreepsToAHaltNeedsLongerThanAnyLobApexLasts() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		mo.update(1F, false, false, 0.06F, 0F, 0F);
		for (int i = 0; i < 6; i++) mo.update(1F, false, false, 0.02F, 0F, 0F);
		assertEquals(WhipLineShape.FLYING, mo.mode(), "7 ticks under the line: still no longer than a lob's top can last");
		mo.update(1F, false, false, 0.02F, 0F, 0F);
		assertTrue(mo.anchored());
	}

	/** One of vanilla's hook ticks (gravity, then drag), so the apex tests fly the real path. */
	private static float[] step(float[] v) {
		return new float[] {v[0] * 0.92F, (v[1] - 0.03F) * 0.92F, v[2] * 0.92F};
	}

	@Test
	void aHookOverTheApexOfASteepCastIsNeverTakenForAnAnchor() {
		// straight up, nearly: the old 2.5-tick stillness test fired here with the hook still flying
		for (float up : new float[] {0.6F, 0.75F, 0.9F, 1.1F, 1.4F}) {
			for (float side : new float[] {0F, 0.02F, 0.05F}) {
				WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
				float[] v = {side, up, 0F};
				for (int tick = 0; tick < 80 && v[1] > -0.9F; tick++) {
					v = step(v);
					// several frames to a tick, at a high frame rate, the velocity the same across them
					for (int frame = 0; frame < 4; frame++) {
						mo.update(0.25F, false, false, v[0], v[1], v[2]);
						assertEquals(WhipLineShape.FLYING, mo.mode(), "up " + up + " side " + side + " tick " + tick + " vy " + v[1]);
					}
				}
			}
		}
	}

	@Test
	void anApexIsNotAnchoredAtALowFrameRateEither() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		float[] v = {0F, 0.9F, 0F};
		for (int tick = 0; tick < 60; tick++) {
			// one frame a tick, and sometimes two ticks to a frame
			boolean slow = tick % 5 == 4;
			v = step(v);
			if (slow) v = step(v);
			mo.update(slow ? 2F : 1F, false, false, v[0], v[1], v[2]);
			assertEquals(WhipLineShape.FLYING, mo.mode(), "tick " + tick);
		}
	}

	@Test
	void aSplashdownIsNotAnAnchorEvenWhenTheWaterTakesMostOfTheSpeed() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		mo.update(1F, false, false, 0.3F, 0.1F, 0F);
		mo.update(1F, true, false, 0.09F, 0.02F, 0F);
		mo.update(1F, true, false, 0.01F, 0F, 0F);
		assertEquals(WhipLineShape.RESTING, mo.mode());
		mo.update(1F, false, false, 0.01F, 0F, 0F);
		assertEquals(WhipLineShape.FLYING, mo.mode(), "out of the water the splashdown's stop does not linger as an anchor");
	}

	@Test
	void theLineKeepsItsFlyingHeadingWhileTheArcEasesOutAfterACatch() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		mo.update(1F, false, false, 0.5F, -0.2F, 0.1F);
		assertEquals(0.5F, mo.hx, 1e-6F);
		mo.update(0.5F, false, false, 0F, 0F, 0F);
		assertTrue(mo.anchored());
		assertEquals(0.5F, mo.hx, 1e-6F, "the stop does not overwrite it");
		assertEquals(-0.2F, mo.hy, 1e-6F);
		// with that heading the bulge and the sag at the catch are the flight's, and only the arc weight takes them away
		WhipLineShape.Shape flying = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(0.5F, -0.2F, 0.1F);
		WhipLineShape.Shape landed = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(mo.hx, mo.hy, mo.hz);
		assertEquals(flying.by, landed.by, 1e-6F);
		assertEquals(flying.sag, landed.sag, 1e-6F);
		WhipLineShape.Shape dropped = new WhipLineShape.Shape().aim(-160F, 0F, 0F).trail(0F, 0F, 0F);
		assertTrue(dropped.sag > landed.sag, "without it the sag would pulse up at the catch");
	}

	@Test
	void aHookTooSlowToHaveAHeadingLeavesNoneForTheEasing() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		mo.update(1F, false, false, 0.5F, 0F, 0F);
		mo.update(1F, false, false, 0.2F, 0F, 0F);
		mo.update(1F, false, false, 0.09F, 0F, 0F);
		mo.update(1F, false, false, 0.05F, 0F, 0F);
		assertEquals(WhipLineShape.FLYING, mo.mode());
		assertEquals(0F, mo.hx, 1e-6F);
	}

	@Test
	void theArcSnapsStraightInAboutTwoTicksWhenTheHookCatches() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		assertEquals(1F, mo.arc, 1e-6F);
		mo.update(0.5F, false, true, 0F, 0F, 0F);
		assertEquals(0F, mo.snapAge, 1e-6F, "the snap begins at the catch");
		assertTrue(mo.arc < 1F && mo.arc > 0F, "eased, not jumped: " + mo.arc);
		mo.update(0.5F, false, true, 0F, 0F, 0F);
		mo.update(0.5F, false, true, 0F, 0F, 0F);
		mo.update(0.5F, false, true, 0F, 0F, 0F);
		assertEquals(0F, mo.arc, 1e-6F, "straight within two ticks");
		assertEquals(0F, mo.slack, 1e-6F);
		assertTrue(mo.snapAge >= 1.4F);
	}

	@Test
	void shiverFollowsTheCatchAndDiesAway() {
		assertEquals(0F, WhipLineShape.shiver(-1F), 1e-6F);
		assertEquals(0F, WhipLineShape.shiver(0F), 1e-6F);
		assertEquals(0F, WhipLineShape.shiver(99F), 1e-6F);
		float peak = 0F, late = 0F;
		for (float a = 0F; a < 8F; a += 0.1F) {
			float v = WhipLineShape.shiver(a);
			assertTrue(Math.abs(v) <= 1F);
			if (a < 2F) peak = Math.max(peak, Math.abs(v));
			if (a > 5F) late = Math.max(late, Math.abs(v));
		}
		assertTrue(peak > 0.3F, "a visible shiver at the start: " + peak);
		assertTrue(late < 0.05F, "gone by tick 5: " + late);
	}

	@Test
	void flyingLineEasesInAndFloatingLineGoesBackToVanillaSlack() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.RESTING);
		assertEquals(1F, mo.slack, 1e-6F);
		for (int i = 0; i < 10; i++) mo.update(1F, false, false, 0.6F, 0F, 0F);
		assertEquals(1F, mo.arc, 1e-6F);
		assertEquals(0F, mo.slack, 1e-6F);
		// it lands in water: back to the slack, never with both at once
		for (int i = 0; i < 12; i++) {
			mo.update(1F, true, false, 0.05F, 0F, 0F);
			assertTrue(mo.slack + mo.arc <= 1F + 1e-6F);
			assertTrue(mo.slack >= 0F && mo.arc >= 0F && mo.slack <= 1F && mo.arc <= 1F);
		}
		assertEquals(1F, mo.slack, 1e-6F);
		assertEquals(0F, mo.arc, 1e-6F);
		assertEquals(WhipLineShape.RESTING, mo.mode());
	}

	@Test
	void weightsStayInRangeOverAnyRunOfFrames() {
		WhipLineShape.Motion mo = new WhipLineShape.Motion(WhipLineShape.FLYING);
		java.util.Random r = new java.util.Random(7);
		for (int i = 0; i < 2000; i++) {
			mo.update(r.nextFloat() * 3F - 0.5F, r.nextBoolean(), r.nextInt(4) == 0, r.nextFloat() * 0.7F, r.nextFloat() * 0.2F - 0.1F, 0F);
			assertTrue(mo.slack >= -1e-6F && mo.slack <= 1F + 1e-6F);
			assertTrue(mo.arc >= -1e-6F && mo.arc <= 1F + 1e-6F);
			assertTrue(mo.slack + mo.arc <= 1F + 1e-5F);
		}
	}

	@Test
	void tautLineIsQuieterThanASlackOne() {
		WhipLineShape.Motion taut = new WhipLineShape.Motion(WhipLineShape.ANCHORED);
		WhipLineShape.Motion slack = new WhipLineShape.Motion(WhipLineShape.RESTING);
		WhipLineShape.Motion flying = new WhipLineShape.Motion(WhipLineShape.FLYING);
		float w0 = new WhipLineShape.Shape().aim(100F, 0F, 0F).mix(slack, 1F, 0F, 0F).writhe;
		float w1 = new WhipLineShape.Shape().aim(100F, 0F, 0F).mix(flying, 1F, 0F, 0F).writhe;
		float w2 = new WhipLineShape.Shape().aim(100F, 0F, 0F).mix(taut, 1F, 0F, 0F).writhe;
		assertEquals(1F, w0, 1e-6F);
		assertTrue(w1 < w0 && w1 > w2, "slack > flying > taut: " + w0 + ", " + w1 + ", " + w2);
		assertTrue(w2 > 0F, "still alive when taut");
	}
}
