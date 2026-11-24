package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Your own whip line kept off the lens (the rig, 2026-11-16, with First-person Model: for a frame the line from the body's whip tip, half a
 * block from the camera, to a new hook at the eye filled the view): the part of the straight line outside the camera's clearance sphere.
 */
class NearCameraLineTest {
	private static final double[] OUT = new double[2];

	private static double[] span(double ax, double ay, double az, double bx, double by, double bz, double clear) {
		NearCamera.outsideSphere(ax, ay, az, bx, by, bz, clear, OUT);
		return OUT;
	}

	@Test
	void aLineFarFromTheCameraIsWhole() {
		double[] s = span(1, 0, 3, 8, 0, 12, 0.5);
		assertEquals(0.0, s[0], 0.0);
		assertEquals(1.0, s[1], 0.0);
		s = span(2, 0, 0, 2, 0, 9, 0.5); // passes beside the sphere
		assertEquals(0.0, s[0], 0.0);
		assertEquals(1.0, s[1], 0.0);
	}

	@Test
	void aLineStartingAtTheEyeLosesItsNearEnd() {
		// from the eye straight ahead to 10 blocks: the first 0.5 block goes, 5 % of the line
		double[] s = span(0, 0, 0, 0, 0, 10, 0.5);
		assertEquals(0.05, s[0], 1e-9);
		assertEquals(1.0, s[1], 0.0);
	}

	@Test
	void aLineEndingAtTheEyeLosesItsFarEnd() {
		double[] s = span(0, 0, 10, 0, 0, 0, 0.5);
		assertEquals(0.0, s[0], 0.0);
		assertEquals(0.95, s[1], 1e-9);
	}

	@Test
	void aLineWhollyInsideIsNone() {
		// the first frame with the body's whip: the tip 0.45 away, the hook 0.3 away (both inside 0.5)
		double[] s = span(0.3, -0.3, 0.2, 0.0, 0.0, 0.3, 0.5);
		assertTrue(s[0] >= s[1], "nothing of it stays: " + s[0] + ".." + s[1]);
	}

	@Test
	void aLineThroughTheMiddleKeepsTheLongerPiece() {
		// 2 blocks to one side of the camera to 6 blocks the other, through the sphere in its middle third: the longer (the far) piece stays
		double[] s = span(-2, 0, 0, 6, 0, 0, 0.5);
		// inside for x in [-0.5, 0.5], s in [0.1875, 0.3125]; the left piece is 0.1875, the right 0.6875
		assertEquals(0.3125, s[0], 1e-9);
		assertEquals(1.0, s[1], 0.0);
		s = span(-6, 0, 0, 2, 0, 0, 0.5);
		assertEquals(0.0, s[0], 0.0);
		assertEquals(0.6875, s[1], 1e-9);
	}

	@Test
	void everyKeptPointIsOutsideTheSphere() {
		for (double ax = -3; ax <= 3; ax += 0.75) {
			for (double bz = -3; bz <= 3; bz += 0.75) {
				double ay = 0.1, by = -0.2, az = 0.4, bx = 1.0;
				double[] s = span(ax, ay, az, bx, by, bz, 0.5);
				if (!(s[0] < s[1])) continue;
				for (int i = 0; i <= 20; i++) {
					double t = s[0] + (s[1] - s[0]) * i / 20.0;
					double x = ax + (bx - ax) * t, y = ay + (by - ay) * t, z = az + (bz - az) * t;
					assertTrue(Math.sqrt(x * x + y * y + z * z) >= 0.5 - 1e-9, "a kept point inside the sphere (" + ax + ", " + bz + ", t " + t + ")");
				}
			}
		}
	}

	@Test
	void badNumbersLeaveTheWholeLine() {
		double[] s = span(Double.NaN, 0, 3, 0, 0, 9, 0.5);
		assertEquals(0.0, s[0], 0.0);
		assertEquals(1.0, s[1], 0.0);
		s = span(0, 0, 3, 0, 0, 9, 0.0);
		assertEquals(1.0, s[1], 0.0);
	}
}
