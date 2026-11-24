package dev.morphreborn.client.arm.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.WhipLineShape;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** The whip's world maths: the lash's timeline and wave (spec 9) and the line's vanilla slack. */
class WhipFxTest {
	@Test
	void lashShootsOutInTwoTicksAndDrawsBackOverFour() {
		assertEquals(0F, WhipFx.reach(0F), 1e-6F);
		assertEquals(1F, WhipFx.reach(2F), 1e-6F);
		assertTrue(WhipFx.reach(1F) > 0.5F, "eased out: most of the way by tick 1");
		assertTrue(WhipFx.reach(4F) < 1F && WhipFx.reach(4F) > 0F);
		assertEquals(0F, WhipFx.reach(6F), 1e-6F);
	}

	@Test
	void waveIsPinnedAtBothEndsAndDecaysAfterTheCrack() {
		for (float age = 0F; age < 6F; age += 0.5F) {
			assertEquals(0F, WhipFx.wave(0F, age), 1e-6F);
			assertEquals(0F, WhipFx.wave(1F, age), 1e-5F);
		}
		float peak = 0F;
		for (float s = 0F; s <= 1F; s += 0.01F) peak = Math.max(peak, Math.abs(WhipFx.wave(s, 1F)));
		assertTrue(peak > 0.6F, "an S across the line: " + peak);
		assertEquals(1F, WhipFx.decay(2F), 1e-6F);
		assertTrue(WhipFx.decay(5F) < 0.3F);
	}

	@Test
	void lineHangsInVanillaSlackFromTheWhipToTheHook() {
		Vector3f p = new Vector3f();
		// whip 16 px above and 32 px along from the hook; floating (all slack), no writhe
		WhipLineShape.Shape shape = new WhipLineShape.Shape().aim(32F, 16F, 0F);
		shape.slack = 1F;
		shape.at(0F, p);
		assertEquals(16F, p.y, 1e-4F);
		assertEquals(32F, p.x, 1e-4F);
		shape.at(1F, p);
		assertEquals(0F, p.length(), 1e-4F);
		shape.at(0.5F, p);
		assertEquals(16F * 0.375F, p.y, 1e-4F, "vanilla's y·(a² + a)/2 at the middle");
		assertTrue(p.y < 8F, "sags under the straight line");
		assertEquals(ArmProtocol.FX_WHIP, 13);
	}
}
