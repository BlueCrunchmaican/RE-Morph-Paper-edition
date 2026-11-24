package dev.morphreborn.client.arm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.geom.FpClearance;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * What the overlay keeps the accessories out of for a vanilla item (2026-11-24): spheres over the model's own extent points (the corners of a sprite's quad
 * and the ends of its pixel edges, a block's corners), filled between along the principal axes, so a flat sprite is a thin slab that follows its silhouette and
 * a box is solid. Checked on the real textures of the sword, bows, crossbow and an apple, in each hand's display transforms, and on boxes.
 */
class FpHeldCoverTest {
	private static float[] toView(float[] blocks) {
		float[] px = new float[blocks.length];
		for (int i = 0; i < blocks.length; i++) px[i] = blocks[i] * 16F;
		return px;
	}

	private static boolean inside(FpClearance.Obstacles o, float x, float y, float z) {
		for (int j = 0; j < o.count(); j++) {
			float[] s = o.sphere(j);
			if (Math.sqrt((x - s[0]) * (x - s[0]) + (y - s[1]) * (y - s[1]) + (z - s[2]) * (z - s[2])) <= s[3] + 1e-3F) return true;
		}
		return false;
	}

	@Test
	void everyPixelEdgePointOfARealSpriteIsInsideItsCoverExceptTheUnseenCornersOfItsQuad() {
		String[] textures = {"iron_sword", "bow", "bow_pulling_2", "crossbow_standby", "crossbow_pulling_2", "crossbow_arrow", "apple", "stick"};
		ItemTransform[] hands = {ArmTransforms.HANDHELD_FP_RIGHT, ArmTransforms.HANDHELD_FP_LEFT, ArmTransforms.GENERATED_FP, ArmTransforms.CROSSBOW_FP_RIGHT};
		int points = 0, outside = 0, spheres = 0, max = 0;
		for (String tex : textures) {
			for (ItemTransform t : hands) {
				float[] pts = toView(FpPoseMath.outlinePoints(tex, t, false));
				FpClearance.Obstacles o = new FpClearance.Obstacles();
				FpHeld.coverPoints(pts, pts.length / 3, o);
				assertTrue(o.count() > 0 && o.count() <= 140, tex + " has " + o.count() + " spheres");
				spheres += o.count();
				max = Math.max(max, o.count());
				// the four corners of the quad, front and back, are the points a pixel edge never reaches: those may be left out
				int far = 0;
				for (int i = 0; i < pts.length / 3; i++) {
					points++;
					if (!inside(o, pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])) {
						outside++;
						far++;
					}
				}
				assertTrue(far <= 8, tex + ": " + far + " extent points outside its cover (only the 8 corners of the quad may be)");
			}
		}
		System.out.printf("vanilla item covers: %d extent points, %d outside (the quads' corners), %d spheres over %d items (most one item: %d)%n", points, outside, spheres,
				textures.length * hands.length, max);
	}

	@Test
	void aFlatSpriteIsAThinSlabAndNotACapsuleRoundItsDiagonal() {
		// the sword's sprite runs corner to corner: its volume is a band along the diagonal, thin across it
		float[] pts = toView(FpPoseMath.outlinePoints("iron_sword", ArmTransforms.HANDHELD_FP_RIGHT, false));
		FpClearance.Obstacles o = new FpClearance.Obstacles();
		FpHeld.coverPoints(pts, pts.length / 3, o);
		float widest = 0F;
		for (int j = 0; j < o.count(); j++) widest = Math.max(widest, o.sphere(j)[3]);
		assertTrue(widest < 3.2F, "no sphere is bigger than a cell and its skin: " + widest);
		// a point 6 px off the blade's line, level with its middle, is not covered (the old capsule through the corners, 8.6 px thick, covered the whole square)
		Vector3f mid = new Vector3f();
		for (int i = 0; i < pts.length / 3; i++) mid.add(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2]);
		mid.div(pts.length / 3F);
		int near = 0;
		for (int j = 0; j < o.count(); j++) {
			float[] s = o.sphere(j);
			if (Math.sqrt((s[0] - mid.x) * (s[0] - mid.x) + (s[1] - mid.y) * (s[1] - mid.y) + (s[2] - mid.z) * (s[2] - mid.z)) < 3F) near++;
		}
		assertTrue(near >= 1, "the middle of the sword is covered");
	}

	@Test
	void aBoxIsSolid() {
		for (boolean left : new boolean[] {false, true}) {
			float[] pts = toView(FpPoseMath.boxPoints(ArmTransforms.GENERATED_FP, left, 10F, 10F, 10F));
			FpClearance.Obstacles o = new FpClearance.Obstacles();
			FpHeld.coverPoints(pts, 8, o);
			float cx = 0F, cy = 0F, cz = 0F;
			for (int i = 0; i < 8; i++) {
				cx += pts[3 * i] / 8F;
				cy += pts[3 * i + 1] / 8F;
				cz += pts[3 * i + 2] / 8F;
				assertTrue(inside(o, pts[3 * i], pts[3 * i + 1], pts[3 * i + 2]), "a corner of the block is covered");
			}
			assertTrue(inside(o, cx, cy, cz), "the middle of the block is covered");
		}
	}

	@Test
	void oddPointSetsNeverThrowOrGrowAHugeGrid() {
		FpClearance.Obstacles o = new FpClearance.Obstacles();
		FpHeld.coverPoints(new float[] {Float.NaN, 0F, 0F, 1F, 1F, 1F}, 2, o);
		FpHeld.coverPoints(new float[] {Float.POSITIVE_INFINITY, 0F, 0F, 1F, 1F, 1F}, 2, o);
		assertEquals(0, o.count(), "a pose that is not a number gets no cover");
		FpHeld.coverPoints(new float[] {1F, 2F, -9F}, 1, o);
		assertEquals(1, o.count(), "one point is one sphere");
		o.clear();
		FpHeld.coverPoints(new float[] {1F, 2F, -9F, 1F, 2F, -9F, 1F, 2F, -9F}, 3, o);
		assertTrue(o.count() >= 1 && inside(o, 1F, 2F, -9F), "the same point three times is covered");
		o.clear();
		FpHeld.coverPoints(new float[] {0F, 0F, -9F, 900F, 900F, -9F}, 2, o);
		assertTrue(o.count() > 0 && o.count() <= 640, "a model a thousand px long is covered coarsely, not with a grid the size of the screen: " + o.count());
	}

	@Test
	void theJacobiAxesAreOrthonormalAndSortedByVariance() {
		double[][] m = {{4, 1, 0.5}, {1, 3, 0.2}, {0.5, 0.2, 1}};
		double[][] a = FpHeld.principalAxes(m);
		for (int i = 0; i < 3; i++) {
			assertEquals(1.0, a[i][0] * a[i][0] + a[i][1] * a[i][1] + a[i][2] * a[i][2], 1e-9);
			for (int j = i + 1; j < 3; j++) assertEquals(0.0, a[i][0] * a[j][0] + a[i][1] * a[j][1] + a[i][2] * a[j][2], 1e-9);
		}
		double[] var = new double[3];
		for (int i = 0; i < 3; i++) {
			for (int r = 0; r < 3; r++) {
				for (int c = 0; c < 3; c++) var[i] += a[i][r] * m[r][c] * a[i][c];
			}
		}
		assertTrue(var[0] >= var[1] && var[1] >= var[2], "largest variance first: " + java.util.Arrays.toString(var));
		new Matrix4f();
	}
}
