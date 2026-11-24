package dev.morphreborn.client.arm.geom;

import org.joml.Vector3f;

/**
 * Where the first-person overlay put each accessory this frame (2026-11-24): the shift of its free end and the scale of its free part that
 * {@link FpClearance} worked out to keep it clear of the item in hand and of the other accessories. Written once a frame by the hand pass
 * before the accessories are submitted, read by their first-person layouts when the draw runs (later in the same frame); nothing else reads it, so
 * third person, the body, the doll and First-person Model draw exactly as before. Indexed like {@code ArmProtocol.ACCESSORIES} (the lantern 0).
 */
public final class FpLayout {
	private static final float[] SX = new float[4], SY = new float[4], SZ = new float[4], K = {1F, 1F, 1F, 1F};

	private FpLayout() {
	}

	/** Records accessory {@code index}'s placement for this frame's draws. */
	public static void set(int index, FpClearance.Pose p) {
		SX[index] = p.sx;
		SY[index] = p.sy;
		SZ[index] = p.sz;
		K[index] = p.scale;
	}

	/** What the first-person lantern's chain keeps out of while it swings: the item in hand and the other accessories as placed this frame (spheres in the hand pass's view space). */
	private static final FpClearance.Obstacles AVOID = new FpClearance.Obstacles();

	/** The solid things the lantern's chain must swing round this frame (the overlay's: held and the other accessories as placed). */
	public static void avoid(FpClearance.Obstacles solid) {
		AVOID.clear();
		for (int i = 0; i < solid.count(); i++) AVOID.copyFrom(solid, i);
	}

	/** How many solid spheres the chain avoids now (the tests). */
	public static int avoidCount() {
		return AVOID.count();
	}

	/**
	 * Pushes the chain's nodes {@code p} (xyz each, node 0 the hook, which stays) out of {@link #avoid}'s spheres: the links as thick as the chain is (a half px
	 * across), the last node as the lure and its cage hung under it (3.6 px, centred 1.7 px under the cap along the last link). Only where the lens could show the
	 * contact, as the placement did. The caller restores the link lengths after.
	 */
	public static void collide(float[] p, int nodes) {
		if (AVOID.count() == 0) return;
		for (int i = 1; i < nodes; i++) {
			int o = 3 * i;
			float cx = p[o], cy = p[o + 1], cz = p[o + 2], r = 0.5F, ox = 0F, oy = 0F, oz = 0F;
			if (i == nodes - 1) {
				float dx = p[o] - p[o - 3], dy = p[o + 1] - p[o - 2], dz = p[o + 2] - p[o - 1];
				float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (l > 1e-5F) {
					ox = dx / l * 1.7F;
					oy = dy / l * 1.7F;
					oz = dz / l * 1.7F;
				}
				r = 3.6F;
			}
			for (int j = 0; j < AVOID.count(); j++) {
				float sx = AVOID.x[j], sy = AVOID.y[j], sz = AVOID.z[j], sr = AVOID.r[j];
				float ex = cx + ox - sx, ey = cy + oy - sy, ez = cz + oz - sz;
				float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
				float pen = r + sr + FpClearance.MARGIN - d;
				if (pen <= 0F || !FpClearance.seen(cx + ox, cy + oy, cz + oz, sx, sy, sz)) continue;
				if (d < 1e-4F) {
					ex = 0F;
					ey = 1F;
					ez = 0F;
					d = 1F;
				}
				p[o] += ex / d * pen;
				p[o + 1] += ey / d * pen;
				p[o + 2] += ez / d * pen;
				cx = p[o];
				cy = p[o + 1];
				cz = p[o + 2];
			}
		}
	}

	/** Back to every accessory at home (the draws that follow are not the overlay's, or nothing intrudes). */
	public static void clear() {
		AVOID.clear();
		for (int i = 0; i < 4; i++) {
			SX[i] = 0F;
			SY[i] = 0F;
			SZ[i] = 0F;
			K[i] = 1F;
		}
	}

	/** The scale of accessory {@code index}'s free part: 1 at home. */
	public static float scale(int index) {
		return K[index];
	}

	public static float shiftX(int index) {
		return SX[index];
	}

	public static float shiftY(int index) {
		return SY[index];
	}

	public static float shiftZ(int index) {
		return SZ[index];
	}

	/**
	 * Applies accessory {@code index}'s placement to a stalk of control points {@code p0} (the root, on the screen's edge: fixed), {@code p1}
	 * and {@code p2} (the free end): the free part drawn smaller about the root, then the free end shifted, the middle point following at half.
	 */
	static void apply(int index, Vector3f p0, Vector3f p1, Vector3f p2) {
		float k = K[index];
		if (k != 1F) {
			p1.sub(p0).mul(k).add(p0);
			p2.sub(p0).mul(k).add(p0);
		}
		p1.add(SX[index] * 0.5F, SY[index] * 0.5F, SZ[index] * 0.5F);
		p2.add(SX[index], SY[index], SZ[index]);
	}
}
