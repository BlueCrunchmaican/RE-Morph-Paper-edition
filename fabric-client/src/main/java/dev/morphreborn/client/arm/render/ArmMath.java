package dev.morphreborn.client.arm.render;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Small maths shared by the arm forms' geometry: the easings the timelines are written in, Gaussians for pulses and
 * swellings, quadratic Beziers for curved centrelines, and the polygons that lofts sweep. Everything here is plain
 * float maths with no Minecraft classes, so the unit tests and every geometry class can use it freely, and nothing
 * allocates (results go into arrays or vectors the caller owns).
 */
public final class ArmMath {
	public static final float PI = (float) Math.PI;
	public static final float TAU = (float) (Math.PI * 2);

	private ArmMath() {}

	public static float clamp(float x, float lo, float hi) {
		return x < lo ? lo : x > hi ? hi : x;
	}

	/** {@code x} clamped to 0..1; NaN becomes 0, so a bad input can only hide a piece, never stretch one to infinity. */
	public static float clamp01(float x) {
		return x > 0F ? (x < 1F ? x : 1F) : 0F;
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** The spec's {@code sm(x)}: smoothstep {@code x²(3 − 2x)} on {@code x} clamped to 0..1. */
	public static float sm(float x) {
		float t = clamp01(x);
		return t * t * (3F - 2F * t);
	}

	/**
	 * The spec's {@code sine(x)}: the same half-sine ease as {@code MorphState.sineify}, so arm animations move with the
	 * transformation's feel. Written out here rather than called, so these maths load without the morph state classes.
	 */
	public static float sine(float x) {
		return (float) Math.sin(Math.toRadians(-90F + 180F * clamp01(x))) / 2F + 0.5F;
	}

	/** The fractional part, always in 0..1 (also for negative {@code x}, which a plain {@code %} gets wrong). */
	public static float frac(float x) {
		return x - (float) Math.floor(x);
	}

	/** A Gaussian bump {@code exp(−d²/(2σ²))}: 1 at {@code d = 0}. */
	public static float gauss(float d, float sigma) {
		return (float) Math.exp(-(d * d) / (2F * sigma * sigma));
	}

	/** The per-player phase {@code ph = entityId · 0.37} (radians), so two armed players never writhe in step. */
	public static float phase(int entityId) {
		return entityId * 0.37F;
	}

	/**
	 * The vertex alpha of a dissolving front: 0 at the front, rising to 1 at {@code width} behind it. With the dissolve
	 * render type, that erodes the last {@code width} of a growing or melting piece into the mask's noise. A
	 * {@code width} of 0 gives a hard edge (1 behind the front, 0 past it).
	 */
	public static float fringe(float behindFront, float width) {
		if (width <= 0F) return behindFront >= 0F ? 1F : 0F;
		return clamp01(behindFront / width);
	}

	/**
	 * The mounted tubes' writhe along their frame's N, in px: {@code 0.12·sin(ph + 5s + 0.31t)}. Tubes laid on the arm
	 * or the tool (helices, wraps, sinews) writhe this little, so they stay on the surface they cling to.
	 */
	public static float writheN(float s, float ph, float time) {
		return 0.12F * (float) Math.sin(ph + 5F * s + 0.31F * time);
	}

	/** The mounted tubes' writhe along their frame's B, in px: {@code 0.08·cos(0.8ph + 3s + 0.23t)}. */
	public static float writheB(float s, float ph, float time) {
		return 0.08F * (float) Math.cos(0.8F * ph + 3F * s + 0.23F * time);
	}

	/** The point at {@code s} on the quadratic Bezier {@code a → b → c}. */
	public static Vector3f bezier(Vector3fc a, Vector3fc b, Vector3fc c, float s, Vector3f out) {
		float u = 1F - s;
		float wa = u * u, wb = 2F * u * s, wc = s * s;
		return out.set(a.x() * wa + b.x() * wb + c.x() * wc, a.y() * wa + b.y() * wb + c.y() * wc, a.z() * wa + b.z() * wb + c.z() * wc);
	}

	/** The (unnormalised) tangent at {@code s} on the quadratic Bezier {@code a → b → c}: its derivative. */
	public static Vector3f bezierTangent(Vector3fc a, Vector3fc b, Vector3fc c, float s, Vector3f out) {
		float u = 1F - s;
		return out.set(2F * (u * (b.x() - a.x()) + s * (c.x() - b.x())), 2F * (u * (b.y() - a.y()) + s * (c.y() - b.y())),
				2F * (u * (b.z() - a.z()) + s * (c.z() - b.z())));
	}

	/**
	 * A regular {@code sides}-gon (an ellipse with radii {@code rn} along N and {@code rb} along B) into {@code out} as
	 * (n, b) pairs, counter-clockwise seen from +T, the way {@link Stations} wants its polygons. {@code rot} turns it:
	 * {@code π/sides} lays a square's faces along N and B, like a box's.
	 */
	public static float[] ring(float[] out, int sides, float rn, float rb, float rot) {
		for (int j = 0; j < sides; j++) {
			double a = rot + Math.PI * 2 * j / sides;
			out[2 * j] = rn * (float) Math.cos(a);
			out[2 * j + 1] = rb * (float) Math.sin(a);
		}
		return out;
	}

	/**
	 * The octagon of a {@code 2hn × 2hb} rectangle with its corners cut {@code cut} deep along each side, into
	 * {@code out} as 8 (n, b) pairs, counter-clockwise from +T: the sheath's section, which follows the square arm
	 * without its sharp corners.
	 */
	public static float[] chamfer(float[] out, float hn, float hb, float cut) {
		float c = clamp(cut, 0F, Math.min(hn, hb));
		out[0] = hn;
		out[1] = -hb + c;
		out[2] = hn;
		out[3] = hb - c;
		out[4] = hn - c;
		out[5] = hb;
		out[6] = -hn + c;
		out[7] = hb;
		out[8] = -hn;
		out[9] = hb - c;
		out[10] = -hn;
		out[11] = -hb + c;
		out[12] = -hn + c;
		out[13] = -hb;
		out[14] = hn - c;
		out[15] = -hb;
		return out;
	}
}
