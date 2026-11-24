package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The clearance round your own eye (the rig, 2026-11-16): a bolt's trail runs back through the bow and the eye after a loose, and the mace
 * landing's shards fly past the lens, and a piece that near fills the view; in first person they keep out of a sphere round the camera.
 */
class NearCameraTest {
	@Test
	void fadeIsGoneInsideTheClearanceAndWholeBeyondTheBand() {
		assertEquals(0F, NearCamera.fade(0.0, 1.4, 0.9), 0F);
		assertEquals(0F, NearCamera.fade(1.4, 1.4, 0.9), 0F, "at the edge of the clearance: gone");
		assertEquals(1F, NearCamera.fade(2.3, 1.4, 0.9), 0F);
		assertEquals(1F, NearCamera.fade(50.0, 1.4, 0.9), 0F);
		assertEquals(0.5F, NearCamera.fade(1.85, 1.4, 0.9), 1e-6F, "the smooth step is half way half way");
		float last = 0F;
		for (double d = 1.4; d <= 2.3; d += 0.05) {
			float v = NearCamera.fade(d, 1.4, 0.9);
			assertTrue(v >= last - 1e-6F, "never grows back smaller as it moves out");
			last = v;
		}
		assertEquals(1F, NearCamera.fade(0.5, 0.0, 0.0), 0F, "no clearance, no band: everything stays");
		assertEquals(1F, NearCamera.fade(Double.NaN, 1.4, 0.9), 0F, "a bad distance doesn't hide anything");
	}

	/**
	 * Fix round 2026-11-16: the owner's own smash burst (shards round the ground point, the eye about 1.62 blocks up standing, 1.27 sneaking)
	 * and a point-blank bolt landing must not shrink away; only a piece within a hand's breadth of the lens does.
	 */
	@Test
	void theBurstClearanceLeavesTheOwnersGroundBurstWhole() {
		assertEquals(1F, NearCamera.fade(1.65, NearCamera.CLEAR, NearCamera.BAND), 0F, "a shard at the ground, eye standing: whole");
		assertEquals(1F, NearCamera.fade(1.27, NearCamera.CLEAR, NearCamera.BAND), 0F, "eye sneaking: whole");
		assertEquals(1F, NearCamera.fade(1.0, NearCamera.CLEAR, NearCamera.BAND), 0F, "whole by a block");
		assertEquals(0F, NearCamera.fade(0.3, NearCamera.CLEAR, NearCamera.BAND), 0F, "right at the lens: gone");
		assertTrue(NearCamera.fade(0.75, NearCamera.CLEAR, NearCamera.BAND) > 0.4F, "a bolt hitting a wall a block ahead keeps most of its burst");
		assertTrue(NearCamera.TRAIL_CLEAR > NearCamera.CLEAR + NearCamera.BAND, "the trail, a long tube through the eye, keeps the wider clearance");
	}

	@Test
	void aTrailThroughTheEyeIsCutWhereItEntersTheSphere() {
		// an arrow 3 blocks ahead of the camera flying away from it; its 4 block trail runs back through the eye
		double len = NearCamera.trailLength(0, 0, 3, 0, 0, 1, 4.0, 1.4);
		assertEquals(1.6, len, 1e-9, "3 - 1.4 of trail: it stops at the sphere");
		// the same arrow once it has gone 8 blocks: the whole trail is clear of the eye
		assertEquals(4.0, NearCamera.trailLength(0, 0, 8, 0, 0, 1, 4.0, 1.4), 0.0);
		// the arrow itself inside the sphere (the first frame after the loose): no trail at all
		assertEquals(0.0, NearCamera.trailLength(0, 0, 0.3, 0, 0, 1, 4.0, 1.4), 0.0);
	}

	@Test
	void aTrailThatPassesBesideOrRunsAwayIsLeftAlone() {
		// flying across the view, 2 blocks off the line: the trail never comes within 1.4
		assertEquals(4.0, NearCamera.trailLength(2, 0, 6, 0, 0, 1, 4.0, 1.4), 0.0);
		// flying toward the camera: the trail runs away from it
		assertEquals(4.0, NearCamera.trailLength(0, 0, 3, 0, 0, -1, 4.0, 1.4), 0.0);
		// a non-number place isn't cut
		assertEquals(4.0, NearCamera.trailLength(Double.NaN, 0, 3, 0, 0, 1, 4.0, 1.4), 0.0);
		assertEquals(0.0, NearCamera.trailLength(0, 0, 3, 0, 0, 1, 0.0, 1.4), 0.0, "no trail stays none");
	}

	@Test
	void aTrailNeverEndsInsideTheSphere() {
		// whatever the line, every point of the allowed length is at least the clearance from the camera (a sweep of places and headings)
		for (int i = 0; i < 360; i += 15) {
			double yaw = Math.toRadians(i);
			double dx = Math.cos(yaw), dz = Math.sin(yaw);
			for (double ahead = 0.5; ahead < 7.0; ahead += 0.5) {
				for (double side = -2.0; side <= 2.0; side += 0.5) {
					double ax = side, az = ahead, ay = 0.2;
					double len = NearCamera.trailLength(ax, ay, az, dx, 0.0, dz, 4.0, 1.4);
					for (double t = 0.0; t <= len; t += 0.05) {
						double px = ax - dx * t, py = ay, pz = az - dz * t;
						double dist = Math.sqrt(px * px + py * py + pz * pz);
						assertTrue(dist >= 1.4 - 1e-6 || Math.sqrt(ax * ax + ay * ay + az * az) <= 1.4 && len == 0.0,
								"a trail point " + dist + " from the camera (heading " + i + ", " + ahead + " ahead, " + side + " aside, t " + t + ")");
					}
				}
			}
		}
	}
}
