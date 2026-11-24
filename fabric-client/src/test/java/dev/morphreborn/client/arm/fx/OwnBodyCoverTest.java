package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The owner's third-person back view (rig 2026-11-16): the hook flies straight ahead, onto the camera's axis, so the torso covers it. The
 * body is a cylinder of radius 0.3 and height 1.8 on the origin; the owner looks toward +z, the camera is behind (-z) and above.
 */
class OwnBodyCoverTest {
	private static final double R = 0.3, H = 1.8;

	private static boolean hides(double cx, double cy, double cz, double px, double py, double pz) {
		return OwnBodyCover.hides(cx, cy, cz, px, py, pz, 0, 0, 0, R, H);
	}

	@Test
	void aHookOnTheCamerasAxisBehindTheBodyIsHidden() {
		// camera 4 blocks behind and 1 above the eye; the claw on the ground 5 blocks ahead of the owner, on the same vertical plane
		assertTrue(hides(0, 2.6, -4, 0, 0.1, 5));
		// the line's own start, the hand 0.25 to the side of the axis, is covered too
		assertTrue(hides(0, 2.6, -4, 0.25, 1.2, 0.6));
	}

	@Test
	void aHookOffTheAxisIsInTheClear() {
		// about 40 degrees round (the report's repro): the claw lands 3 blocks to the side
		assertFalse(hides(0, 2.6, -4, 3, 0.1, 5));
		// just outside the body's radius at the claw's distance
		assertFalse(hides(0, 2.6, -4, 1.5, 0.1, 5));
	}

	@Test
	void frontViewAndPointsInOrNearTheBodyAreNeverHidden() {
		// the camera ahead of the owner looking back: the hook is between the camera and the body
		assertFalse(hides(0, 2.0, 4, 0, 0.1, 3));
		// a point inside the body is in it, not behind it
		assertFalse(hides(0, 2.6, -4, 0, 1.0, 0.0));
		// the camera inside the body (first person) sees nothing hidden by it
		assertFalse(hides(0, 1.6, 0, 0, 0.1, 5));
	}

	@Test
	void aHookAboveTheHeadSightLineIsHiddenOnlyWhenTheLineCrossesTheBody() {
		// a camera high up, looking down at a hook that is beyond the body but the sight line passes over the head
		assertFalse(hides(0, 6, -2, 0, 3.5, 4));
		// the same camera with the hook low and far: the sight line dips through the torso
		assertTrue(hides(0, 2.6, -2, 0, 0.0, 4));
	}

	@Test
	void badNumbersAreNotHidden() {
		assertFalse(hides(Double.NaN, 2.6, -4, 0, 0.1, 5));
		assertFalse(hides(0, 2.6, -4, Double.NaN, 0.1, 5));
		assertFalse(OwnBodyCover.hides(0, 2.6, -4, 0, 0.1, 5, 0, 0, 0, 0.0, H));
	}

	@Test
	void theLinesHiddenShareRunsFromAllOfItInTheBackViewToNoneToTheSide() {
		// whip tip at the hand, hook 5 blocks ahead on the axis: the whole line runs away behind the torso (the tip is inside it, so not counted)
		float behind = OwnBodyCover.hiddenShare(0, 2.6, -4, 0, 1.0, 0.1, 0, 0.1, 5, 0, 0, 0, R, H, 12);
		assertTrue(behind > 0.7F, "the line hides behind the body: " + behind);
		float aside = OwnBodyCover.hiddenShare(0, 2.6, -4, 0, 1.0, 0.1, 4, 0.1, 5, 0, 0, 0, R, H, 12);
		assertTrue(aside < 0.3F, "a hook off to the side shows most of its line: " + aside);
		assertEquals(0F, OwnBodyCover.hiddenShare(0, 2.0, 4, 0, 1.0, 0.1, 0, 0.1, 3, 0, 0, 0, R, H, 1), 1e-6F);
	}
}
