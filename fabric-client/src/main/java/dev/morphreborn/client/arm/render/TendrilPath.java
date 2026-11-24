package dev.morphreborn.client.arm.render;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A living tendril between two points, the frenzy's tendril maths (RageTethers.tendril) made general and drawn as one
 * smooth lofted strand instead of a chain of boxes:
 * <ul>
 *   <li>a writhe that is still at both ends (the envelope {@code sin(πs)}), so the ends stay pinned where they are
 *       attached: {@code side·sin(phase + 7s + 0.35t) + up·0.6·cos(0.8·phase + 5s + 0.28t)} times {@code amp};</li>
 *   <li>a width along it and swellings that run along it ({@link #swell}: {@code 1 + A·exp(−(s−p)²/0.006)});</li>
 *   <li>growth by truncation: a tendril reaching out is the same wavy path cut off at {@code reach}, so its tip feels
 *       its way forward along the curve rather than the whole thing stretching;</li>
 *   <li>{@link #pieces} segments, as the frenzy's: {@code clamp(ceil(length in blocks · 2.5), 4, 40)}.</li>
 * </ul>
 * The strand is a square section carried along by parallel transport, capped at both ends (the cut front as well), and
 * its static crawls from {@code from} to {@code to}. Units are the mesh's (px for arm geometry; the world effects
 * translate and scale their pose so world points arrive in px too); {@code amp} and the widths are in the same units.
 * Render thread only (shared scratch).
 */
public final class TendrilPath {
	/** The tendril's full width at {@code s}. */
	public interface WidthFn {
		float at(float s);
	}

	/** The width multiplier at {@code s} (1: none); see {@link #swell}. */
	public interface SwellFn {
		float at(float s);
	}

	private static final Stations PATH = new Stations();
	private static final float[] SQUARE = new float[8];
	private static final Vector3f P = new Vector3f(), T = new Vector3f(), SIDE = new Vector3f(), UP = new Vector3f(), DIR = new Vector3f();
	/** The current line's length, set with its frame. */
	private static float length;
	/** Below this a tendril's length counts as none (nothing is drawn, and no direction is made up). */
	private static final float MIN_LENGTH = 1e-4F;

	private TendrilPath() {}

	/** The frenzy's piece count for a tendril {@code lengthBlocks} long. */
	public static int pieces(float lengthBlocks) {
		if (!(lengthBlocks > 0F)) return 4;
		return Math.clamp((int) Math.ceil(lengthBlocks * 2.5F), 4, 40);
	}

	/** A swelling of strength {@code a} centred at {@code p} along the tendril, as felt at {@code s}. */
	public static float swell(float s, float p, float a) {
		float d = s - p;
		return 1F + a * (float) Math.exp(-(d * d) / 0.006F);
	}

	/**
	 * Draws the tendril from {@code from} to {@code to}, cut at {@code reach} (0..1) of its length, writhing with
	 * {@code phase} and {@code time}, in {@code layer} (nothing unless that is {@code wanted}). {@code swell} may be
	 * null. Nothing is drawn for a tendril of no length or no reach.
	 */
	public static void sweep(SkinMesh m, Vector3fc from, Vector3fc to, float reach, float phase, float time, float amp, WidthFn width, @Nullable SwellFn swell,
			int pieces, ArmLayer layer, ArmLayer wanted, @Nullable Static st, int part) {
		if (layer != wanted || !(reach > 0F)) return;
		if (!frame(from, to)) return;
		float r = Math.min(reach, 1F);
		int n = Math.clamp(pieces, 1, 64);
		Stations out = PATH.reset(4).hint(UP.x, UP.y, UP.z);
		float arc = 0F, px = 0F, py = 0F, pz = 0F, thin = width.at(0.5F);
		for (int i = 0; i <= n; i++) {
			float s = Math.min(i / (float) n, r);
			point(from, s, phase, time, amp);
			tangent(s, phase, time, amp);
			if (i > 0) arc += (float) Math.sqrt((P.x - px) * (P.x - px) + (P.y - py) * (P.y - py) + (P.z - pz) * (P.z - pz));
			px = P.x;
			py = P.y;
			pz = P.z;
			float half = 0.5F * Math.max(0F, width.at(s) * (swell == null ? 1F : swell.at(s)));
			ArmMath.ring(SQUARE, 4, half, half, ArmMath.PI / 4F);
			out.addTransported(P.x, P.y, P.z, T.x, T.y, T.z, arc, SQUARE, 1F);
			if (s >= r) break;
		}
		Loft.emit(m, out, wanted, null, layer, st, part, thin < 0.6F ? 2F : 1F, true, true);
	}

	/**
	 * The point at {@code s} (0..1) on the tendril from {@code from} to {@code to} (exactly {@code from} at 0 and
	 * {@code to} at 1), into {@code out}; for anchoring knots and latches on it. A tendril of no length is its start.
	 */
	public static Vector3f point(Vector3fc from, Vector3fc to, float s, float phase, float time, float amp, Vector3f out) {
		if (!frame(from, to)) return out.set(from);
		point(from, s, phase, time, amp);
		return out.set(P);
	}

	/**
	 * The frame RageTethers uses: DIR along the line (its length into {@link #length}), SIDE square to it and to +Y
	 * (or +X when the line is vertical), UP square to both. False when there is no line.
	 */
	private static boolean frame(Vector3fc from, Vector3fc to) {
		float dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(len > MIN_LENGTH) || !Float.isFinite(len)) return false;
		DIR.set(dx / len, dy / len, dz / len);
		length = len;
		DIR.cross(0F, 1F, 0F, SIDE);
		if (SIDE.lengthSquared() < 1e-4F) SIDE.set(1F, 0F, 0F);
		SIDE.normalize();
		SIDE.cross(DIR, UP).normalize();
		return true;
	}

	/** The path's point at {@code s} into {@link #P}. */
	private static void point(Vector3fc from, float s, float phase, float time, float amp) {
		float env = (float) Math.sin(Math.PI * s) * amp;
		float a = (float) Math.sin(phase + 7F * s + 0.35F * time) * env;
		float b = 0.6F * (float) Math.cos(0.8F * phase + 5F * s + 0.28F * time) * env;
		float along = length * s;
		P.set(from.x() + DIR.x * along + SIDE.x * a + UP.x * b, from.y() + DIR.y * along + SIDE.y * a + UP.y * b, from.z() + DIR.z * along + SIDE.z * a + UP.z * b);
	}

	/** The path's derivative at {@code s} into {@link #T}: the line, plus the writhe's own slope. */
	private static void tangent(float s, float phase, float time, float amp) {
		float ps = (float) Math.PI * s;
		float env = (float) Math.sin(ps) * amp, denv = (float) (Math.PI * Math.cos(ps)) * amp;
		float w1 = phase + 7F * s + 0.35F * time, w2 = 0.8F * phase + 5F * s + 0.28F * time;
		float a = denv * (float) Math.sin(w1) + env * 7F * (float) Math.cos(w1);
		float b = 0.6F * (denv * (float) Math.cos(w2) - env * 5F * (float) Math.sin(w2));
		T.set(DIR.x * length + SIDE.x * a + UP.x * b, DIR.y * length + SIDE.y * a + UP.y * b, DIR.z * length + SIDE.z * a + UP.z * b);
	}
}
