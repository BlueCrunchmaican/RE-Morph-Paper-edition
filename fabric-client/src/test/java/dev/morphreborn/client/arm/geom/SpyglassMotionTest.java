package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The spyglass eye's per-player tables (2026-11-16): a crowd of zooming players keeps its state (32 slots evicted each other). */
class SpyglassMotionTest {
	@BeforeEach
	@AfterEach
	void reset() {
		SpyglassMotion.source(null);
		SpyglassMotion.clear();
	}

	/** A source that says everyone looks at 90 degrees yaw, level, and that the draw is the world's. */
	private static final class Fixed implements SpyglassMotion.Source {
		@Override
		public boolean ownZoom(int entityId) {
			return false;
		}

		@Override
		public boolean look(int entityId, float pt, float[] out) {
			out[0] = 90F;
			out[1] = 0F;
			return true;
		}

		@Override
		public boolean worldPass() {
			return true;
		}
	}

	@Test
	void aCrowdOfZoomersEachKeepsItsOwnEase() {
		int crowd = 200;
		float t = 1000F;
		for (int id = 1; id <= crowd; id++) SpyglassMotion.extend(id, t, false);
		for (int id = 1; id <= crowd; id++) SpyglassMotion.extend(id, t + 1F, true); // they all start to zoom
		float half = SpyglassMotion.EXTEND_TICKS / 2F;
		float first = SpyglassMotion.extend(1, t + 1F + half, true);
		assertTrue(first > 0F && first < 1F, "mid-ease for the first: " + first);
		for (int id = 2; id <= crowd; id++) {
			assertEquals(first, SpyglassMotion.extend(id, t + 1F + half, true), 1e-6F, "player " + id + " is on the same ease, not snapped back to the start or the end");
		}
	}

	@Test
	void aCrowdOfEyesKeepsItsOwnAimLag() {
		SpyglassMotion.source(new Fixed());
		float[] out = new float[2];
		int crowd = 150;
		// every eye settled on 90 degrees; then the look is asked again a tick later and must still be a smoothed follow, not a restart
		for (int id = 1; id <= crowd; id++) assertTrue(SpyglassMotion.aim(id, 1000F, 1F, out));
		for (int id = 1; id <= crowd; id++) {
			assertTrue(SpyglassMotion.aim(id, 1001F, 1F, out));
			assertEquals(90F, out[0], 1e-3F, "it stays on the owner's view");
		}
	}
}
