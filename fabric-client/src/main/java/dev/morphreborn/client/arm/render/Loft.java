package dev.morphreborn.client.arm.render;

import dev.morphreborn.client.arm.geom.ArmDraw;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Sweeps cross-sections along a path into one continuous skin: the blade, the haft, every strand, ring and tendril of
 * an arm form. A chain of boxes, which is all the morph geometry had before, steps at every joint and shows it at
 * first-person range; a loft shares each section between two segments, so a bending strand is smooth and a tip can
 * taper to a point.
 * <p>
 * One quad per segment and face. Each segment takes its own static offset ({@link Static}), so the noise re-patterns
 * segment by segment as the crawl sweeps along. Texture u runs round the section (perimeter px × uvScale) and v along
 * the piece (arc px × uvScale); with uvScale 1 that is 4 noise texels a pixel, and thin parts (under 0.6 px) use 2 so
 * their static doesn't smear into one colour.
 * <p>
 * Every quad faces outward whichever way its section winds; caps face back along the piece at the start and on along
 * it at the end. Render thread only (shared scratch).
 */
public final class Loft {
	/** Which layer face {@code face} of segment {@code segment} belongs to; caps ask with face -1 (segment -1: the start). */
	public interface FaceLayer {
		ArmLayer at(int segment, int face);
	}

	/** A centreline: the point at {@code s} into {@code out}. */
	public interface CurveFn {
		void at(float s, Vector3f out);
	}

	/** A tube's radius at {@code s}, in px. */
	public interface RadiusFn {
		float at(float s);
	}

	/** A tube's brightness at {@code s} as 0xRRGGBB (a pulse running along it), or -1 for the mesh's tint. */
	public interface ColourFn {
		int at(float s);
	}

	/**
	 * The brightness along the next {@link #tube}, or null: set it just before the call, and it is cleared after (a pulse
	 * travelling down a tendril rather than the whole tendril flashing at once). Render thread only.
	 */
	private static @Nullable ColourFn nextColour;

	public static void colourNext(@Nullable ColourFn colour) {
		nextColour = colour;
	}

	/**
	 * The arm being drawn, for the pulses (2026-11-15: the tools should pulse, from the shoulder down the arm
	 * to the tip, not flash all at once): while it is set, every red and glow face is lit by the pulse where it is on the
	 * feed coordinate, a little dimmer at rest and full where a pulse is passing. The renderers set it round each emit.
	 */
	private static @Nullable ArmDraw pulseDraw;
	private static float pulseFeed;
	/** The red and glow faces' brightness at rest (a pulse lifts them to 1). */
	static final float PULSE_REST_RED = 0.62F, PULSE_REST_GLOW = 0.5F;

	/** Off: no pulse lighting (the dev command inbox's {@code pulse off}, for before-and-after pictures). */
	public static boolean pulseLighting = true;

	public static void pulseFrom(@Nullable ArmDraw d, float feedLength) {
		pulseDraw = pulseLighting ? d : null;
		pulseFeed = feedLength;
	}

	/**
	 * True when a face of {@code layer} is drawn again in the {@code wanted} pass as the pulse's glow: a red face in the
	 * glow pass while an arm is drawn (2026-11-15: accentuate the red that is there, make it pulse and glow; no
	 * new red). Only with glow on, as the glow pass only runs then.
	 */
	private static boolean pulseOverlay(ArmLayer layer, ArmLayer wanted) {
		return layer == ArmLayer.RED && wanted == ArmLayer.GLOW && pulseDraw != null;
	}

	/**
	 * True in the glow pass while an arm is drawn with the pulse on: a form's piece that filters by layer lets this pass
	 * through too, so its red faces can light up where a pulse is (Loft draws nothing else in it for them).
	 */
	public static boolean pulseGlowPass(ArmLayer layer) {
		return layer == ArmLayer.GLOW && pulseDraw != null;
	}

	/** How bright a red face glows at feed coordinate {@code phi}: 0 away from a pulse, up to 1 in its middle. */
	private static float glowAmp(float phi) {
		ArmDraw d = pulseDraw;
		if (d == null) return 0F;
		return ArmMath.clamp01((ArmMath.clamp01(1.8F * d.swell(phi, pulseFeed)) - 0.35F) / 0.65F);
	}

	private static int grey(float b) {
		int c = Math.round(ArmMath.clamp01(b) * 255F);
		return (c << 16) | (c << 8) | c;
	}

	/** Moves quad corner {@code slot} 4% out from station {@code i}'s centre. */
	private static void lift(int slot, Stations s, int i) {
		int c = 3 * i;
		for (int a = 0; a < 3; a++) QP[3 * slot + a] = s.c[c + a] + (QP[3 * slot + a] - s.c[c + a]) * 1.04F;
	}

	/** {@code rgb} lit by the pulse at feed coordinate {@code phi}, for a face in {@code layer}. */
	private static int pulsed(int rgb, float phi, ArmLayer layer) {
		ArmDraw d = pulseDraw;
		if (d == null || layer != ArmLayer.RED && layer != ArmLayer.GLOW) return rgb;
		float amp = ArmMath.clamp01(1.8F * d.swell(phi, pulseFeed)); // an idle pulse (0.45) lights nearly fully
		float rest = layer == ArmLayer.GLOW ? PULSE_REST_GLOW : PULSE_REST_RED;
		float k = rest + (1F - rest) * amp;
		int r = Math.round(((rgb >> 16) & 0xFF) * k), g = Math.round(((rgb >> 8) & 0xFF) * k), b = Math.round((rgb & 0xFF) * k);
		return (r << 16) | (g << 8) | b;
	}

	/** A tube's dissolve alpha at {@code s} (a growth front's fringe). */
	public interface AlphaFn {
		float at(float s);
	}

	private static final Stations TUBE = new Stations();
	private static float[] samples = new float[3 * 32];
	private static float[] ring = new float[2 * 8];
	private static final Vector3f POINT = new Vector3f();
	private static final float[] QP = new float[12];
	private static final float[] QUV = new float[8];
	private static final float[] QA = new float[4];
	private static final int[] QRGB = new int[4];

	private Loft() {}

	/**
	 * Draws {@code s}: the faces whose {@code layerOf} is {@code wanted} (null {@code layerOf}: every face is
	 * {@link ArmLayer#BLACK}), each segment with its static offset from {@code st} (null: none), and fan caps at either
	 * end when asked. A cap of k points is {@code ceil((k − 2) / 2)} quads (3 for a blade's 7-point section).
	 */
	public static void sweep(SkinMesh m, Stations s, ArmLayer wanted, @Nullable FaceLayer layerOf, @Nullable Static st, int part, float uvScale, boolean capStart,
			boolean capEnd) {
		emit(m, s, wanted, layerOf, ArmLayer.BLACK, st, part, uvScale, capStart, capEnd);
	}

	/**
	 * A tube of {@code sides} faces (4, 6 or 8) along {@code centre} from {@code s0} to {@code s1}, sampled at
	 * {@code stations} points, radius {@code r(s)}, all in {@code layer} (nothing is drawn unless that is
	 * {@code wanted}). Its frame starts from {@code upHint} and is carried along by parallel transport, so it never
	 * twists; tangents come from the samples. The feed coordinate runs {@code phi0..phi1} along it. No caps: tubes end
	 * inside other parts or taper away.
	 */
	public static void tube(SkinMesh m, CurveFn centre, float s0, float s1, int stations, int sides, RadiusFn r, ArmLayer layer, ArmLayer wanted, @Nullable Static st,
			int part, float uvScale, float phi0, float phi1, Vector3fc upHint, float alpha) {
		tube(m, centre, s0, s1, stations, sides, r, layer, wanted, st, part, uvScale, phi0, phi1, upHint, null, alpha, 0F);
	}

	/**
	 * As {@link #tube}, for a run {@code s0..s1} cut out of a longer curve: the arc position (the texture's v) starts at {@code arc0} px
	 * instead of 0, so the texture stays put on the line as the cut moves (the own-view whip line trimmed off the lens).
	 */
	public static void tube(SkinMesh m, CurveFn centre, float s0, float s1, int stations, int sides, RadiusFn r, ArmLayer layer, ArmLayer wanted, @Nullable Static st,
			int part, float uvScale, float phi0, float phi1, Vector3fc upHint, float alpha, float arc0) {
		tube(m, centre, s0, s1, stations, sides, r, layer, wanted, st, part, uvScale, phi0, phi1, upHint, null, alpha, arc0);
	}

	/** As {@link #tube}, with the dissolve alpha given along it ({@code alpha(s)}), for a tube growing with a fringe. */
	public static void tube(SkinMesh m, CurveFn centre, float s0, float s1, int stations, int sides, RadiusFn r, ArmLayer layer, ArmLayer wanted, @Nullable Static st,
			int part, float uvScale, float phi0, float phi1, Vector3fc upHint, @Nullable AlphaFn alpha) {
		tube(m, centre, s0, s1, stations, sides, r, layer, wanted, st, part, uvScale, phi0, phi1, upHint, alpha, 1F, 0F);
	}

	private static void tube(SkinMesh m, CurveFn centre, float s0, float s1, int stations, int sides, RadiusFn r, ArmLayer layer, ArmLayer wanted, @Nullable Static st,
			int part, float uvScale, float phi0, float phi1, Vector3fc upHint, @Nullable AlphaFn alphaFn, float alpha, float arc0) {
		ColourFn colour = nextColour;
		nextColour = null;
		if (layer != wanted && !pulseOverlay(layer, wanted)) return;
		int n = Math.max(2, stations);
		int k = Math.max(2, sides);
		sample(centre, s0, s1, n, false);
		Stations out = TUBE.reset(k).hint(upHint.x(), upHint.y(), upHint.z());
		float arc = arc0;
		for (int i = 0; i < n; i++) {
			float f = i / (float) (n - 1);
			float s = s0 + (s1 - s0) * f;
			if (i > 0) arc += dist(i - 1, i);
			int a = Math.max(0, i - 1), z = Math.min(n - 1, i + 1);
			ArmMath.ring(ring(k), k, r.at(s), r.at(s), ArmMath.PI / k);
			if (colour != null) out.color(colour.at(s));
			out.addTransported(samples[3 * i], samples[3 * i + 1], samples[3 * i + 2], samples[3 * z] - samples[3 * a], samples[3 * z + 1] - samples[3 * a + 1],
					samples[3 * z + 2] - samples[3 * a + 2], arc, ring, alphaFn == null ? alpha : alphaFn.at(s));
			out.phi[i] = phi0 + (phi1 - phi0) * f;
		}
		emit(m, out, wanted, null, layer, st, part, uvScale, false, false);
	}

	/**
	 * A closed loop of {@code sides} faces along {@code centre} over s in 0..1 (the curve must come back to its start),
	 * sampled at {@code stations} points, all of them joined: a ring of n stations is n segments (the tsuba's loops,
	 * the haft's bindings, the recall bracelet). Its frame's N is {@code upHint} made square to the curve at every
	 * station rather than carried round, because parallel transport round a loop comes back twisted and the seam would
	 * show; give the loop's plane normal. The whole loop sits at feed coordinate {@code phi}.
	 */
	public static void closedTube(SkinMesh m, CurveFn centre, int stations, int sides, RadiusFn r, ArmLayer layer, ArmLayer wanted, @Nullable Static st, int part,
			float uvScale, float phi, Vector3fc upHint, float alpha) {
		if (layer != wanted && !pulseOverlay(layer, wanted)) return;
		int n = Math.max(3, stations);
		int k = Math.max(2, sides);
		sample(centre, 0F, 1F, n, true);
		Stations out = TUBE.reset(k).feed(phi);
		float arc = 0F;
		for (int i = 0; i <= n; i++) {
			int at = i % n;
			float s = at / (float) n;
			if (i > 0) arc += dist(i - 1, at);
			int a = (at + n - 1) % n, z = (at + 1) % n;
			ArmMath.ring(ring(k), k, r.at(s), r.at(s), ArmMath.PI / k);
			out.add(samples[3 * at], samples[3 * at + 1], samples[3 * at + 2], samples[3 * z] - samples[3 * a], samples[3 * z + 1] - samples[3 * a + 1],
					samples[3 * z + 2] - samples[3 * a + 2], upHint.x(), upHint.y(), upHint.z(), arc, ring, alpha);
			out.phi[i] = phi;
		}
		emit(m, out, wanted, null, layer, st, part, uvScale, false, false);
	}

	/** {@code n} points of {@code centre} into {@link #samples}: s0..s1 inclusive, or (closed) 0..1 without the end. */
	private static void sample(CurveFn centre, float s0, float s1, int n, boolean closed) {
		if (samples.length < 3 * n) samples = new float[3 * Math.max(n, samples.length / 3 * 2)];
		for (int i = 0; i < n; i++) {
			float s = closed ? i / (float) n : s0 + (s1 - s0) * (i / (float) (n - 1));
			centre.at(s, POINT);
			samples[3 * i] = POINT.x;
			samples[3 * i + 1] = POINT.y;
			samples[3 * i + 2] = POINT.z;
		}
	}

	private static float dist(int a, int b) {
		float dx = samples[3 * b] - samples[3 * a], dy = samples[3 * b + 1] - samples[3 * a + 1], dz = samples[3 * b + 2] - samples[3 * a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static float[] ring(int k) {
		if (ring.length < 2 * k) ring = new float[2 * k];
		return ring;
	}

	/**
	 * The sweep itself. With {@code layerOf} null every face is {@code fixed}. Shared with {@link TendrilPath}, which
	 * builds its own stations.
	 */
	static void emit(SkinMesh m, Stations s, ArmLayer wanted, @Nullable FaceLayer layerOf, ArmLayer fixed, @Nullable Static st, int part, float uvScale,
			boolean capStart, boolean capEnd) {
		if (layerOf == null && fixed != wanted && !pulseOverlay(fixed, wanted)) return;
		int n = s.n, k = s.k;
		if (n < 1 || k < 2) return;
		int faces = k == 2 ? 1 : k; // a ribbon is drawn once
		for (int i = 0; i + 1 < n; i++) {
			float phiMid = (s.phi[i] + s.phi[i + 1]) * 0.5F;
			float su = st == null ? 0F : st.u0(part, i, phiMid), sv = st == null ? 0F : st.v0(part, i, phiMid);
			boolean ccw = s.area[i] + s.area[i + 1] >= 0F;
			for (int j = 0; j < faces; j++) {
				ArmLayer layer = layerOf == null ? fixed : layerOf.at(i, j);
				// in the glow pass, a red face lights up where a pulse is passing (and only there)
				boolean overlay = layer != wanted && pulseOverlay(layer, wanted);
				if (layer != wanted && !overlay) continue;
				float g0 = 0F, g1 = 0F;
				if (overlay) {
					g0 = glowAmp(s.phi[i]);
					g1 = glowAmp(s.phi[i + 1]);
					if (g0 <= 0F && g1 <= 0F) continue;
				}
				int j1 = (j + 1) % k;
				if (ccw) {
					side(0, s, i, j, j, su, sv, uvScale, m);
					side(1, s, i, j1, j + 1, su, sv, uvScale, m);
					side(2, s, i + 1, j1, j + 1, su, sv, uvScale, m);
					side(3, s, i + 1, j, j, su, sv, uvScale, m);
				} else {
					side(0, s, i, j1, j + 1, su, sv, uvScale, m);
					side(1, s, i, j, j, su, sv, uvScale, m);
					side(2, s, i + 1, j, j, su, sv, uvScale, m);
					side(3, s, i + 1, j1, j + 1, su, sv, uvScale, m);
				}
				if (overlay) {
					// over the red face, a hair out from it so the two never fight, as bright as the pulse there
					lift(0, s, i);
					lift(1, s, i);
					lift(2, s, i + 1);
					lift(3, s, i + 1);
					int c0 = grey(g0), c1 = grey(g1);
					QRGB[0] = c0;
					QRGB[1] = c0;
					QRGB[2] = c1;
					QRGB[3] = c1;
				} else if (pulseDraw != null && (wanted == ArmLayer.RED || wanted == ArmLayer.GLOW)) {
					// slots 0 and 1 are on station i, 2 and 3 on station i + 1 (either winding)
					QRGB[0] = pulsed(QRGB[0], s.phi[i], wanted);
					QRGB[1] = pulsed(QRGB[1], s.phi[i], wanted);
					QRGB[2] = pulsed(QRGB[2], s.phi[i + 1], wanted);
					QRGB[3] = pulsed(QRGB[3], s.phi[i + 1], wanted);
				}
				m.quad(QP, QUV, QA, QRGB);
			}
		}
		if (k < 3) return;
		if (capStart) cap(m, s, 0, false, layerOf == null ? fixed : layerOf.at(-1, -1), wanted, st, part, uvScale);
		if (capEnd) cap(m, s, n - 1, true, layerOf == null ? fixed : layerOf.at(n - 1, -1), wanted, st, part, uvScale);
	}

	/** A side quad's corner {@code slot}: corner {@code j} of station {@code i}, its u from perimeter index {@code pj}. */
	private static void side(int slot, Stations s, int i, int j, int pj, float su, float sv, float uvScale, SkinMesh m) {
		corner(slot, s, i, j, m);
		QUV[2 * slot] = su + s.perim[(s.k + 1) * i + pj] * uvScale;
		QUV[2 * slot + 1] = sv + s.v[i] * uvScale;
	}

	private static void corner(int slot, Stations s, int i, int j, SkinMesh m) {
		int o = 2 * s.k * i + 2 * j;
		float pn = s.poly[o], pb = s.poly[o + 1];
		int c = 3 * i;
		QP[3 * slot] = s.c[c] + s.nn[c] * pn + s.b[c] * pb;
		QP[3 * slot + 1] = s.c[c + 1] + s.nn[c + 1] * pn + s.b[c + 1] * pb;
		QP[3 * slot + 2] = s.c[c + 2] + s.nn[c + 2] * pn + s.b[c + 2] * pb;
		QA[slot] = s.alpha[i];
		QRGB[slot] = s.rgb[i] < 0 ? m.tint() : s.rgb[i];
	}

	/**
	 * A fan over station {@code i}'s polygon from its first point, two triangles a quad (an odd one left over closes
	 * on a doubled corner), facing on along the piece ({@code end}) or back. Planar texture from the section itself.
	 */
	private static void cap(SkinMesh m, Stations s, int i, boolean end, ArmLayer layer, ArmLayer wanted, @Nullable Static st, int part, float uvScale) {
		if (layer != wanted) return;
		int k = s.k;
		int seg = end ? s.n - 1 : -1;
		float su = st == null ? 0F : st.u0(part, seg, s.phi[i]), sv = st == null ? 0F : st.v0(part, seg, s.phi[i]);
		// counter-clockwise from +T faces +T: the end cap as it is, the start cap reversed (and both flipped for a
		// clockwise section)
		boolean forward = end == (s.area[i] >= 0F);
		for (int a = 1; a < k - 1; a += 2) {
			int p1 = a, p2 = a + 1, p3 = Math.min(a + 2, k - 1);
			if (forward) {
				capCorner(0, s, i, 0, su, sv, uvScale, m);
				capCorner(1, s, i, p1, su, sv, uvScale, m);
				capCorner(2, s, i, p2, su, sv, uvScale, m);
				capCorner(3, s, i, p3, su, sv, uvScale, m);
			} else {
				capCorner(0, s, i, p3, su, sv, uvScale, m);
				capCorner(1, s, i, p2, su, sv, uvScale, m);
				capCorner(2, s, i, p1, su, sv, uvScale, m);
				capCorner(3, s, i, 0, su, sv, uvScale, m);
			}
			m.quad(QP, QUV, QA, QRGB);
		}
	}

	private static void capCorner(int slot, Stations s, int i, int j, float su, float sv, float uvScale, SkinMesh m) {
		corner(slot, s, i, j, m);
		int o = 2 * s.k * i + 2 * j;
		QUV[2 * slot] = su + s.poly[o] * uvScale;
		QUV[2 * slot + 1] = sv + s.poly[o + 1] * uvScale;
	}
}
