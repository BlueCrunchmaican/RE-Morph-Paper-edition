package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.ability.BodyReach.Box;
import org.junit.jupiter.api.Test;

/** Short fixed-range abilities from a big body: the reach is measured from the body's hitbox edge, never grown with the scale. */
class BodyReachTest {
	/** A body of {@code scale} standing at the origin: 0.6 wide, 1.8 high at scale 1. */
	private static Box body(double scale) {
		double half = 0.3 * scale;
		return new Box(-half, 0, -half, half, 1.8 * scale, half);
	}

	/** A cow-sized box (0.9 x 1.4) standing {@code dx} east of the origin, on the ground. */
	private static Box cowAt(double dx) {
		return new Box(dx - 0.45, 0, -0.45, dx + 0.45, 1.4, 0.45);
	}

	@Test
	void onlyBodiesBiggerThanAPlayerUseTheBodyEdge() {
		assertFalse(BodyReach.applies(1.0));
		assertFalse(BodyReach.applies(1.0004));
		assertFalse(BodyReach.applies(0.5));
		assertTrue(BodyReach.applies(1.08));
		assertTrue(BodyReach.applies(6.67));
	}

	@Test
	void gapIsZeroWhenBoxesTouchOrOverlap() {
		assertEquals(0, BodyReach.gap(body(1), cowAt(0.5)), 1e-9);
		assertEquals(0, BodyReach.gap(body(1), cowAt(0.75)), 1e-9);
	}

	@Test
	void gapIsTheStraightDistanceBetweenNearestPoints() {
		// body edge at x 0.3, cow edge at 2.55 - 0.45 = 2.1 -> 1.8 apart on one axis
		assertEquals(1.8, BodyReach.gap(body(1), cowAt(2.55)), 1e-9);
		// diagonal: 3 in x and 4 in y apart is 5
		assertEquals(5.0, BodyReach.gap(new Box(0, 0, 0, 1, 1, 1), new Box(4, 5, 0, 5, 6, 1)), 1e-9);
	}

	@Test
	void aGiantReachesACowAtItsFeetButNotOneFarBeyondItsBody() {
		Box giant = body(6.67); // 4 wide, 12 high
		// a cow right at its ankle, a sting (3) reaches it
		assertTrue(BodyReach.within(giant, cowAt(2.5), 3));
		// edge of the body is at x 2.0: a cow just under 3 blocks beyond the edge is in range, one just over is not
		assertTrue(BodyReach.within(giant, cowAt(2.0 + 2.99 + 0.45), 3));
		assertFalse(BodyReach.within(giant, cowAt(2.0 + 3.01 + 0.45), 3));
	}

	@Test
	void theRangeIsNotScaledWithTheBody() {
		// the same 5 block tongue past a dragon's or a giant's edge reaches exactly as far as past a zombie's
		for (double scale : new double[] {1.08, 2.22, 4.44, 6.67}) {
			double edge = 0.3 * scale;
			assertTrue(BodyReach.within(body(scale), cowAt(edge + 4.99 + 0.45), 5), "scale " + scale);
			assertFalse(BodyReach.within(body(scale), cowAt(edge + 5.01 + 0.45), 5), "scale " + scale);
		}
	}

	@Test
	void aTargetAboveTheBodyIsMeasuredFromTheBodyToo() {
		Box dragon = body(4.44); // 8 high
		Box flyer = new Box(-0.3, 10, -0.3, 0.3, 11, 0.3);
		assertTrue(BodyReach.within(dragon, flyer, 3)); // 2 above the top
		assertFalse(BodyReach.within(dragon, new Box(-0.3, 12, -0.3, 0.3, 13, 0.3), 3)); // 4 above the top
	}

	@Test
	void theTraceFindsEveryTargetWithinReachButIsNoLongerThanNeeded() {
		Box giant = body(6.67);
		double eyeY = 1.8 * 6.67 * 0.9; // about 10.8
		double length = BodyReach.traceLength(3, giant, 0, eyeY, 0);
		// the far bottom corner of the body is the farthest point from the eye
		double half = giant.maxX();
		double farthest = Math.sqrt(half * half + eyeY * eyeY + half * half);
		assertEquals(3 + farthest, length, 1e-9);
		// a cow at the ankle (eye to cow top-near corner) is inside the trace
		assertTrue(Math.sqrt(2.5 * 2.5 + eyeY * eyeY) < length);
	}

	@Test
	void aPlayerSizedEyeTraceIsAlreadyLongEnoughSoNothingChanges() {
		// scale 1: the trace is range + the eye's distance to the farthest body corner, and gap <= range always
		// holds for anything the plain eye trace (eye distance <= range) can hit, since the eye is inside the hitbox
		Box player = body(1);
		for (double d = 0.5; d <= 3; d += 0.5) {
			assertTrue(BodyReach.within(player, cowAt(d + 0.3), 3));
		}
	}
}
