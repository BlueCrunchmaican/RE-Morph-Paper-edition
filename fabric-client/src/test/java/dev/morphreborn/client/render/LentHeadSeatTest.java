package dev.morphreborn.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Where lent heads, ears and parts sit on bodies the first multimorph rounds got wrong (late-wave rig, 2026-11-19): the numbers behind
 * HybridMorph's seats, so the cases the rig found stay found.
 */
class LentHeadSeatTest {
	@Test
	void aSnowGolemsPumpkinIsTenPixelsAndSitsAboveItsHeadBox() {
		float[][] box = LentHeadSize.snowGolemPumpkin(1F);
		assertEquals(10F / 16F, box[1][0] - box[0][0], 1e-6, "SnowGolemHeadLayer scales the block to 0.625");
		assertEquals(10F / 16F, box[1][2] - box[0][2], 1e-6);
		assertEquals(10F / 16F, box[1][1] - box[0][1], 1e-6);
		// the head part is 8 pixels with a -0.5 deformation: its box tops out 7.5 pixels over the pivot, the pumpkin 10.5 over it
		assertEquals(-10.5F / 16F, box[0][1], 1e-6);
		assertEquals(-0.5F / 16F, box[1][1], 1e-6);
		assertTrue(box[0][1] < -7.5F / 16F, "ears seated on the head box were inside the pumpkin");
	}

	@Test
	void theGolemsPumpkinGrowsWithTheBody() {
		float[][] one = LentHeadSize.snowGolemPumpkin(1F), two = LentHeadSize.snowGolemPumpkin(2F);
		for (int axis = 0; axis < 3; axis++) {
			assertEquals(2F * one[0][axis], two[0][axis], 1e-6);
			assertEquals(2F * one[1][axis], two[1][axis], 1e-6);
		}
	}

	@Test
	void onlyTheSilverfishPlatesAreBackPlates() {
		for (String name : new String[] {"layer0", "layer1", "layer2", "layer10"}) assertTrue(LentHeadSize.backPlate(name), name);
		for (String name : new String[] {"segment0", "segment2", "body", "layer", "layers", "player", "layer0a", "tail0"}) assertFalse(LentHeadSize.backPlate(name), name);
	}

	@Test
	void aSlimesShellIsOneModelPixelAboveItsInnerCube() {
		// SlimeModel: inner cube y 17..23, SlimeOuterLayer: y 16..24
		assertEquals(1F, LentHeadSize.SLIME_SHELL_LIFT_PX, 1e-6);
	}

	@Test
	void aTinyBodyHoldsLentPartsToOneAndAHalfTimesItsCore() {
		// a silverfish: its segments' core is about 0.36 blocks; a bat's wings and tail reach about 1.1
		float limit = LentHeadSize.tinyMainPartLimit(1.1F, 0.36F);
		assertEquals(1.5F * 0.36F / 1.1F, limit, 1e-6);
		assertTrue(limit < 1F);
		// a part that already fits is not made bigger by it: the caller takes the smaller of this and its own scale
		assertTrue(LentHeadSize.tinyMainPartLimit(0.3F, 0.36F) > 1F);
	}

	@Test
	void tinyPartsNeverShrinkToNothing() {
		// the reach would be 0.15 of the parts' size; the floor of 0.2 keeps them something to look at
		assertEquals(LentHeadSize.TINY_PARTS_FLOOR, LentHeadSize.tinyMainPartLimit(2F, 0.2F), 1e-6);
	}

	@Test
	void theFloorNeverLiftsPartsPastOneAndAHalfTimesTheReach() {
		// a 7 block dragon on a 0.31 block mite: the reach is 0.066 of its size and the floor of 0.2 made wings 3 times the animal
		float reach = 1.5F * 0.31F / 7F;
		assertEquals(1.5F * reach, LentHeadSize.tinyMainPartLimit(7F, 0.31F), 1e-6);
		assertEquals(1.5F * (1.5F * 0.2F / 40F), LentHeadSize.tinyMainPartLimit(40F, 0.2F), 1e-6);
	}

	@Test
	void anyOtherBodyHasNoLimit() {
		// a bee (0.48 across at its core), a cow, a person
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1.1F, 0.48F));
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1.1F, LentHeadSize.TINY_MAIN));
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1.1F, 0.9F));
		// nothing measured, nothing limited
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(0F, 0.2F));
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1F, 0F));
	}

	/** The renderer's frame for a living body: its yaw (180 - bodyRot about y), then any pitch, then the model's (-1, -1, 1) axes. */
	private static Matrix4f frame(float bodyRotDegrees, float pitchDegrees) {
		return new Matrix4f().translation(3F, 1F, -2F).rotateY((float) Math.toRadians(180F - bodyRotDegrees)).rotateX((float) Math.toRadians(pitchDegrees))
				.rotateZ((float) Math.PI);
	}

	private static Matrix4f yaw(float bodyRotDegrees) {
		return new Matrix4f().rotationY((float) Math.toRadians(180F - bodyRotDegrees));
	}

	private static void assertSame(Vector3f want, Vector3f got) {
		assertEquals(want.x, got.x, 1e-4);
		assertEquals(want.y, got.y, 1e-4);
		assertEquals(want.z, got.z, 1e-4);
	}

	@Test
	void aBodyThatIsNotTippedNeedsNoFix() {
		for (float rot : new float[] {0F, 37F, 180F, 271F}) {
			Matrix4f fix = LentHeadSize.uprightFix(frame(rot, 0F), yaw(rot));
			Vector3f v = fix.transformPosition(new Vector3f(0.3F, -0.7F, 0.2F));
			assertSame(new Vector3f(0.3F, -0.7F, 0.2F), v);
		}
	}

	@Test
	void aSquidLyingOnLandGetsItsHeadStoodUpright() {
		// the squid is pitched 90 degrees: a head put on it in its frame faced the floor with its top forward
		for (float rot : new float[] {0F, 90F, 215F}) {
			Matrix4f tipped = frame(rot, 90F), upright = frame(rot, 0F);
			Matrix4f fix = LentHeadSize.uprightFix(tipped, yaw(rot));
			// the lent head's up and forward, in the frame it is drawn in, come out as the untipped body's
			for (Vector3f dir : new Vector3f[] {new Vector3f(0F, -1F, 0F), new Vector3f(0F, 0F, -1F), new Vector3f(1F, 0F, 0F)}) {
				Vector3f got = tipped.transformDirection(fix.transformDirection(new Vector3f(dir)));
				assertSame(upright.transformDirection(new Vector3f(dir)), got);
			}
			// and the fix is a turn only: it moves nothing it is applied about
			assertSame(new Vector3f(), fix.transformPosition(new Vector3f()));
		}
	}

	@Test
	void theTipOfAnyAngleIsUndone() {
		Matrix4f fix = LentHeadSize.uprightFix(frame(40F, -30F), yaw(40F));
		Vector3f up = frame(40F, -30F).transformDirection(fix.transformDirection(new Vector3f(0F, -1F, 0F)));
		assertSame(frame(40F, 0F).transformDirection(new Vector3f(0F, -1F, 0F)), up);
	}
}
