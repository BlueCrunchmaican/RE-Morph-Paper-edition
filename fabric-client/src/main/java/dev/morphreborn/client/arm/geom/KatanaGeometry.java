package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.Rope;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The katana (section 2.4): an old curved sword grown out of the arm. A single-edged blade lofted along a real katana's
 * line (straight from the collar, bowing toward the edge through the middle, the point leaning back), with a ridge, a
 * wavering red edge band (the hamon) that breathes and lifts as pulses run up the blade, a back that ripples, and a
 * dagger point (the kissaki) where the edge sweeps up into the back. Below it a red collar (habaki), a guard of two
 * looping tendrils with four lobes (the tsuba), a grip whose two wrapped strands cross over a red core to leave red
 * diamonds, a knotted pommel and a hanging tail (the sageo) with real inertia. Spines of every size bristle along the back, a
 * few of them barbed; the point stays clean.
 * <p>
 * Grip space G (section 2.0), px; level 2 ("Honed") is the baseline. Level 1 ("Raw") is plainer: one wrap strand, one
 * guard loop, fewer bristles, no tail, a nearly straight hamon, a shorter blade. Level 3 ("Awakened") is longer, its
 * edge band and collar glow, its guard grows thorns and it trails two tails.
 * <p>
 * Everything moves: the static crawls up the arm into the blade, idle pulses swell up the hilt and down to the tail,
 * the hamon breathes, the back ripples, the spines sway, the guard breathes, the wraps writhe, the
 * tail swings, drips gather and fall. Hits flare the hamon and flick beads off the point; Draining Cut bristles the fuzz
 * and holds the hamon wide; low biomass thins it, then cracks it red, then erodes its point and makes it spasm. Every
 * timeline (form, quick-form, reshape, retract, melt) is read from {@link ArmDraw}.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class KatanaGeometry implements FormGeometry {
	/**
	 * Which side of G the edge is on: −1, the −X side, so the edge faces down in a held sword and leads vanilla's swing
	 * (seen in game 2026-11-15: with +1 the spines faced the ground). The katana is built with its edge on +X and drawn
	 * through an X mirror of this sign ({@link #TOOL}), so world-facing inputs (down, the tail's rope nodes) are mirrored
	 * in and the points handed out mirrored back. The preview's readout shows it.
	 */
	public static final float EDGE_SIGN = -1F;
	/** Blade stations at full detail, and where the kissaki (the point's curve) starts along the spine. */
	public static final int STATIONS = 28;
	public static final float KISSAKI = 0.88F;
	/** The wrap's radius under the fingers. */
	public static final float HILT_R = 1.05F;
	/** The blade's base sits this far up the grip; the feed coordinate's blade part starts there. */
	private static final float BLADE_PHI0 = 13.7F;
	/** The mune line's control points (K1), and level 3's longer tip. */
	private static final float P0X = -0.95F, P0Y = 3.5F, P1X = -0.95F, P1Y = 14.0F, P2X = -3.95F, P2Y = 24.3F, P2X_L3 = -4.2F, P2Y_L3 = 25.7F;
	/** The level-2 blade's arc, for placing the fuzz and cracks on other levels' blades in proportion. */
	private static final float L2_ARC;
	private static final float[] ARC_BY_TIER = new float[4];
	/**
	 * The spines along the back (v px on the level-2 blade), by level: irregular, and none on the kissaki (it starts
	 * about 18.5 px up), so the point stays clean.
	 */
	private static final float[] FUZZ_L1 = {3.0F, 9.2F, 13.8F}, FUZZ_L2 = {2.2F, 4.6F, 8.9F, 10.4F, 14.6F, 17.3F},
			FUZZ_L3 = {1.4F, 3.1F, 5.9F, 8.2F, 9.6F, 12.9F, 15.8F, 17.9F};
	/**
	 * Each spine's own character, by its place in the level's list, so no two look alike: length (× the base), lean back
	 * from the edge's normal (degrees), hook (radians added per segment, + bends toward the tip), thickness, sideways
	 * lean (× the step, out of the blade's plane), and whether a small barb forks off it. LOD 1 keeps only the long ones.
	 */
	private static final float[] SPINE_LEN = {0.75F, 1.35F, 0.6F, 1.6F, 0.95F, 0.7F, 1.2F, 0.55F},
			SPINE_ANG = {28F, 44F, 20F, 52F, 34F, 24F, 47F, 30F},
			SPINE_CURL = {0.25F, -0.15F, 0.4F, 0.1F, -0.3F, 0.35F, 0F, -0.2F},
			SPINE_W = {1.0F, 1.25F, 0.8F, 1.35F, 1.0F, 0.85F, 1.15F, 0.8F},
			SPINE_SIDE = {0.15F, -0.1F, 0.22F, -0.2F, 0.05F, -0.18F, 0.1F, 0.2F};
	private static final boolean[] SPINE_FORK = {false, true, false, true, false, false, true, false};
	/** FORM: the first spine sprouts at this tick, then one every {@link #SPINE_STAGGER} ticks up the blade. */
	private static final float SPINE_FROM = 8.5F, SPINE_STAGGER = 0.55F;
	/** How deep inside the blade (toward the edge, px) a spine's root starts, and the extra first segment that covers it. */
	private static final float SPINE_ROOT = 0.4F;
	/** Starving cracks along the edge (v px on the level-2 blade). */
	private static final float[] CRACKS = {6.5F, 7.5F, 11.5F, 12.3F, 15.8F, 16.4F};

	// static parts, one per piece (each segment of a piece then takes its own offset)
	private static final int PART_BLADE = 1, PART_HABAKI = 2, PART_LOOP = 3, PART_LOBE = 5, PART_CORE = 10, PART_WRAP = 11, PART_KASHIRA = 13,
			PART_TAIL = 14, PART_FUZZ = 16, PART_DRIP = 27, PART_THORN = 28, PART_BEADS = 29, PART_FORK = 30, PART_ARM = 40, PART_BLOB = 50;

	private static final Static ST = new Static();
	private static final Stations BLADE = new Stations();
	private static final Stations RING = new Stations();
	private static final float[] POLY = new float[14];
	private static final float[] RECT = new float[8];
	private static final float[] ARC = new float[33];
	private static final float[] S_LIST = new float[40];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Matrix4f TOOL = new Matrix4f();
	private static final Vector3f CP0 = new Vector3f(), CP1 = new Vector3f(), CP2 = new Vector3f();
	private static final Vector3f M = new Vector3f(), T = new Vector3f(), E = new Vector3f(), Z = new Vector3f(), DOWN = new Vector3f(), Q = new Vector3f(),
			R = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);
	private static final float[] FUZZ_PTS = new float[12];
	private static final float[] TAIL = new float[3 * ArmDraw.ROPE_NODES];
	private static final float[] TARGETS = new float[18];
	private static final float[] PASS = new float[6];
	private static final Vector3f TAIL_ROOT = new Vector3f(0F, -5.4F, 0F);
	/** Where nothing swings the tail (no nodes given): it leaves its root (0, as the nodes are relative) this way and droops. */
	private static final Vector3f TAIL_ROOT_LOCAL = new Vector3f(), TAIL_OUT = new Vector3f(0.3F, -0.95F, 0F).normalize();
	/** The core's three section heights (scratch), and the edge's drip points (v px on the level-2 blade). */
	private static final float[] CORE_YS = new float[3], EDGE_DRIPS = {2.0F, 5.5F};

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static float arcLength, vFront, feedLength, droop, yHi, yLo, swellA;
	private static int tailSet;
	private static float loopScale, loopDrift, breath, fuzzWidth, tailWidthScale;

	private static final Loft.CurveFn LOOP2 = (s, out) -> {
		float th = ArmMath.TAU * s - loopDrift * 0.7F;
		float r = 1.72F * (1F + 0.15F * (float) Math.cos(4F * th + ArmMath.PI)) * breath * loopScale;
		float a = th + ArmMath.PI / 4F;
		out.set(r * (float) Math.cos(a), 2.8F, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn LOOP2_W = s -> FistGeometry.width(0.55F, 4);
	/**
	 * The guard's arms (2026-11-15: seen down the blade, a defined, curving four-armed symbol made of
	 * tendrils, like a Maltese cross whirled round). Arm {@link #armK} leaves the hub ring and sweeps round as it goes out,
	 * all four the same way; at levels 2 and 3 it is two tendril edges ({@link #armEdge} −1 and +1) that flare apart
	 * toward the end, the trailing one hooking into a curl; at level 1 one tendril ({@code armEdge} 0).
	 */
	private static int armK;
	private static float armEdge;
	private static final float ARM_R0 = 1.55F, ARM_R1 = 3.7F, ARM_SWEEP = 1.05F, ARM_FLARE = 1.15F, ARM_CURL = 1.3F;
	private static final Loft.CurveFn ARM = (s, out) -> armPoint(armK, armEdge, s, out);
	private static final Loft.RadiusFn ARM_W = s -> FistGeometry.width((armEdge == 0F ? 0.55F : 0.42F) - 0.12F * s, 4);
	private static int wrapStrand;
	private static final Loft.CurveFn WRAP = (s, out) -> {
		float y = ArmMath.lerp(yHi, yLo, s);
		float ph = 7F * ArmMath.PI * (y + 4.6F) / 7.2F;
		float a = wrapStrand == 0 ? ph : -ph + ArmMath.PI;
		float wr = 1F + 0.05F * (float) Math.sin(cur.phase() + 5F * s + 0.31F * cur.time() + wrapStrand * 2F) / 0.8F;
		out.set(0.80F * wr * (float) Math.cos(a), y, 0.68F * wr * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn WRAP_W = s -> FistGeometry.width(0.5F * (1F + 0.45F * swellA), 4);
	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		// FUZZ_PTS: four points, the stations land on them exactly (s = 0, 1/3, 2/3, 1)
		float f = ArmMath.clamp(s, 0F, 1F) * 3F;
		int i = Math.min(2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(FUZZ_PTS[3 * i], FUZZ_PTS[3 * i + 3], u), ArmMath.lerp(FUZZ_PTS[3 * i + 1], FUZZ_PTS[3 * i + 4], u),
				ArmMath.lerp(FUZZ_PTS[3 * i + 2], FUZZ_PTS[3 * i + 5], u));
	};
	private static final Loft.RadiusFn FUZZ_W = s -> FistGeometry.width(ArmMath.lerp(0.5F, 0.11F, (float) Math.sqrt(s)) * fuzzWidth, 4);
	private static final Loft.CurveFn TAIL_CURVE = (s, out) -> tailPoint(s, out);
	private static final Loft.RadiusFn TAIL_W = s -> FistGeometry.width(ArmMath.lerp(0.55F, 0.25F, s) * tailWidthScale, 4);

	static {
		for (int t = 1; t <= 3; t++) {
			spine(t, 0F, ARC_BY_TIER);
			ARC_BY_TIER[t] = ARC[32];
		}
		L2_ARC = ARC_BY_TIER[2];
	}

	@Override
	public String form() {
		return ArmProtocol.KATANA;
	}

	@Override
	public float feedLength(int tier) {
		return BLADE_PHI0 + ARC_BY_TIER[Math.clamp(tier, 1, 3)];
	}

	@Override
	public float guiScale() {
		// the point lands at g + k·0.707·y along the slot's diagonal: 0.64 and 3.4 keep the level-3 tip (y 25.7) at 15 px
		return 0.64F;
	}

	@Override
	public float guiGripPx() {
		return 3.4F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The blade's arc length at {@code tier} (21.0 px at level 2), as drawn at rest. */
	public static float bladeArc(int tier) {
		return ARC_BY_TIER[Math.clamp(tier, 1, 3)];
	}

	/** The blade's stations as the last emit that drew the blade left them (their count, corners and arcs): for the tests. */
	static Stations lastBlade() {
		return BLADE;
	}

	/**
	 * The guard's clearance: the closest the inner loop's inside edge comes to the blade's axis, over its whole turn and
	 * breath, in px. The blade's collar is 0.95 px half-high there, so this must stay above that.
	 */
	public static float tsubaClearance() {
		float min = Float.MAX_VALUE;
		for (int i = 0; i < 360; i++) {
			float th = ArmMath.TAU * i / 360F;
			float r = 1.72F * (1F + 0.15F * (float) Math.cos(4F * th + ArmMath.PI)) * 0.98F;
			min = Math.min(min, r - FistGeometry.width(0.55F, 4));
		}
		return min;
	}

	// ------------------------------------------------------------------ the spine

	/**
	 * Sets the mune line's control points for {@code tier} (level 1 0.95 long along its axis; level 3's longer tip) with
	 * the retract's sag ({@code sag} px down at the tip), and fills {@link #ARC} with its cumulative arc length at
	 * s = 0, 1/32 .. 1.
	 */
	private static void spine(int tier, float sag, float[] unused) {
		float k = tier <= 1 ? 0.95F : 1F;
		float p2x = tier >= 3 ? P2X_L3 : P2X, p2y = tier >= 3 ? P2Y_L3 : P2Y;
		CP0.set(P0X, P0Y, 0F);
		CP1.set(P0X + (P1X - P0X) * k, P0Y + (P1Y - P0Y) * k, 0F);
		CP2.set(P0X + (p2x - P0X) * k, P0Y + (p2y - P0Y) * k - sag, 0F);
		ARC[0] = 0F;
		float px = CP0.x, py = CP0.y;
		for (int i = 1; i <= 32; i++) {
			ArmMath.bezier(CP0, CP1, CP2, i / 32F, M);
			ARC[i] = ARC[i - 1] + (float) Math.hypot(M.x - px, M.y - py);
			px = M.x;
			py = M.y;
		}
	}

	/** The arc length at spine parameter {@code s}. */
	private static float arcAt(float s) {
		float f = ArmMath.clamp01(s) * 32F;
		int i = Math.min(31, (int) f);
		return ArmMath.lerp(ARC[i], ARC[i + 1], f - i);
	}

	/** The spine parameter at arc length {@code v}. */
	private static float sAt(float v) {
		if (v <= 0F) return 0F;
		if (v >= ARC[32]) return 1F;
		int i = 0;
		while (i < 31 && ARC[i + 1] < v) i++;
		float span = ARC[i + 1] - ARC[i];
		return (i + (span > 1e-6F ? (v - ARC[i]) / span : 0F)) / 32F;
	}

	/**
	 * The spine's point and frame at {@code s} into M, T, E (toward the edge), Z = E × T; with melting's droop toward
	 * world down, more the further along the tool.
	 */
	private static void frameAt(float s) {
		ArmMath.bezier(CP0, CP1, CP2, s, M);
		ArmMath.bezierTangent(CP0, CP1, CP2, s, T);
		if (T.lengthSquared() < 1e-8F) T.set(0F, 1F, 0F);
		T.normalize();
		E.set(1F, 0F, 0F).fma(-T.x, T).normalize();
		E.cross(T, Z);
		if (droop > 0F) {
			float d = (P0Y + arcAt(s)) / (P0Y + arcLength);
			M.fma(droop * d * d, DOWN);
		}
	}

	/** The blade's width (mihaba) at {@code s}: 1.90 to 1.35, then the kissaki's curve to the point on the back. */
	static float mihaba(float s) {
		if (s <= KISSAKI) return ArmMath.lerp(1.90F, 1.35F, s / KISSAKI);
		float u = (s - KISSAKI) / (1F - KISSAKI);
		return 1.35F * (float) Math.sqrt(Math.max(0F, 1F - u * u));
	}

	/** The blade's thickness (kasane) at {@code s}: 0.55 to 0.40, then thinning to 0.12 at the point. */
	static float kasane(float s) {
		if (s <= KISSAKI) return ArmMath.lerp(0.55F, 0.40F, s / KISSAKI);
		return ArmMath.lerp(0.40F, 0.12F, (s - KISSAKI) / (1F - KISSAKI));
	}

	/**
	 * Where the hamon line sits between the ridge (0) and the edge (1) at arc {@code v}, time {@code t}, for a pulse of
	 * strength {@code amp} there (section 2.4 K1): it wavers along the blade, breathes slowly, and lifts (the red band
	 * widening) as a pulse passes; level 1's is nearly straight, level 3's has a finer ripple. Clamped to 0.25..0.75.
	 */
	public static float hamon(int tier, float v, float t, float amp) {
		float a1 = tier <= 1 ? 0.07F : 0.13F, a2 = tier <= 1 ? 0.03F : 0.06F;
		float f = 0.50F + a1 * (float) Math.sin(0.85F * v + 0.6F) + a2 * (float) Math.sin(2.3F * v + 1.9F) + 0.04F * (float) Math.sin(0.05F * t + 0.4F * v)
				- 0.18F * amp;
		if (tier >= 3) f += 0.05F * (float) Math.sin(4.1F * v);
		return ArmMath.clamp(f, 0.25F, 0.75F);
	}

	private static float phiBlade(float v) {
		return BLADE_PHI0 + v;
	}

	/** The feed coordinate on the hilt at height {@code y}: up from the fist to the blade, and down to the pommel. */
	private static float phiHilt(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.SENSE) return;
		cur = d;
		int tier = Math.clamp(d.tier(), 1, 3);
		feedLength = feedLength(tier);
		ST.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		d.down(DOWN);
		DOWN.x *= EDGE_SIGN; // into the mirrored build space
		// first person: the arm itself (mirrored for the left hand), then the hand's shared pieces
		if (d.firstPerson()) {
			FistGeometry.mirror(m, d.leftHand());
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, HILT_R, feedLength);
			FistGeometry.mirror(m, false);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm();
		m.transform(TOOL.scaling(EDGE_SIGN * sp, sp, sp));
		try {
			tool(d, tier, layer, m);
		} finally {
			m.transform(null);
		}
	}

	private static void tool(ArmDraw d, int tier, ArmLayer layer, SkinMesh m) {
		float reach = d.reach();
		boolean detailed = d.detailedForm();
		float ft = d.formTick();
		float rt = d.retractTick();
		droop = d.droop();
		spine(tier, rt >= 0F ? ArmMath.sm(rt / 3F) : 0F, null);
		arcLength = ARC[32];
		// the fronts: the blade's (from the collar), and the hilt's up and down from the grip
		float total = P0Y + arcLength;
		float up = detailed ? P0Y + reach * arcLength : reach * total;
		vFront = ArmMath.clamp(up - P0Y, 0F, arcLength);
		boolean failing = d.lowStage() == ArmDraw.LOW_FAILING;
		if (failing) vFront = Math.min(vFront, 0.92F * arcLength);
		float guard = detailed ? ArmMath.sm(ft - 2.5F) : ArmMath.sm((up - 2.4F) / 1.2F);
		if (rt >= 0F) guard = Math.min(guard, 1F - ArmMath.sm((rt - 5F) / 3F));
		float down = detailed ? 5.6F : reach * 5.6F;
		if (detailed) {
			float wrapReach = ArmMath.sm((ft - 2F) / 4F);
			yHi = 2.6F;
			yLo = 2.6F - 7.2F * wrapReach;
		} else {
			yHi = Math.min(2.6F, up);
			yLo = Math.max(-4.6F, -down);
			if (rt >= 0F) yLo = Math.max(yLo, ArmMath.lerp(-4.6F, 2.6F, ArmMath.sm((rt - 3F) / 5F)));
		}
		float pommel = detailed ? ArmMath.sm(ft - 6F) : ArmMath.sm((down - 4.4F) / 0.8F);
		int lod = d.lod();
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m, reach, guard);
			return;
		}
		blade(d, tier, layer, m, detailed, ft, rt, failing);
		if (lod <= 1) habaki(d, tier, layer, m, guard); // LOD 2: the guard boxes cover it
		tsuba(d, tier, layer, m, detailed, ft, rt, guard);
		hilt(d, tier, layer, m, pommel);
		if (lod == 0) blobs(d, tier, layer, m, detailed, ft, rt, guard);
		if (lod == 0 && tier >= 2) tail(d, tier, layer, m, detailed ? ArmMath.sm((ft - 9F) / 3F) : pommel);
		fuzz(d, tier, layer, m, detailed, ft, 1F);
		beads(d, layer, m, rt, failing);
		if (lod == 0 && d.dripsNow() && (!detailed || ft >= 13F)) drips(d, layer, m, guard, pommel);
		if (d.burstCount() > 0) burst(d, layer, m);
	}

	/** K1: the blade, lofted section by section, cut at the growth front, its hamon, ripple, cracks and fringe. */
	private static void blade(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float rt, boolean failing) {
		if (vFront <= 0.05F) return;
		int lod = d.lod();
		boolean lod2 = lod >= 2;
		boolean glowEdge = tier >= 3 && d.glow();
		ArmLayer red = d.paint(ArmLayer.RED), black = d.paint(ArmLayer.BLACK);
		ArmLayer edge = glowEdge ? ArmLayer.GLOW : red;
		if (layer != red && layer != black && layer != edge && !Loft.pulseGlowPass(layer)) return;
		// how far the red band has been drawn (FORM reveals it from the collar; a retract takes it back from the tip)
		float vRed = detailed ? arcLength * ArmMath.sm((ft - 7F) / 2F) : rt >= 0F ? arcLength * (1F - ArmMath.sm(rt / 3F)) : arcLength;
		int stage = d.lowStage();
		float scale = arcLength / L2_ARC;
		float time = d.time();
		float hit = d.hitFlare();
		float cut = d.specialWeight(ArmProtocol.FX_SWIPE);
		boolean growing = d.reach() < 1F || failing;
		float fringeW = failing && d.reach() >= 1F ? 1.7F : d.fringe();
		boolean fringe = d.dissolve() && growing;
		float glowBright = ArmMath.clamp01((detailed ? 0.55F * ArmMath.sm((ft - 9F) / 3F) : 0.55F) + 0.45F * Math.max(hit, cut));
		// the station list: the fixed ones under the front, then the front itself
		int n = 0;
		int fixed = lod2 ? 7 : lod == 1 ? 15 : STATIONS;
		int body = lod2 ? 5 : lod == 1 ? 10 : 21;
		for (int i = 0; i < fixed; i++) {
			float s = i <= body ? KISSAKI * i / body : KISSAKI + (1F - KISSAKI) * (i - body) / (fixed - 1 - body);
			if (arcAt(s) < vFront - 0.02F) S_LIST[n++] = s;
		}
		boolean truncated = vFront < arcLength - 0.02F;
		S_LIST[n++] = truncated ? sAt(vFront) : 1F;
		if (n < 2) return;
		int sides = lod2 ? 5 : 7;
		Stations out = BLADE.reset(sides).feed(BLADE_PHI0);
		boolean glowPass = layer == ArmLayer.GLOW;
		out.color(glowPass ? FistGeometry.grey(glowBright) : -1);
		for (int i = 0; i < n; i++) {
			float s = S_LIST[i];
			float v = arcAt(s);
			frameAt(s);
			float amp = d.swell(phiBlade(v), feedLength);
			float h = mihaba(s);
			float th = kasane(s) + 0.12F * amp;
			if (stage >= ArmDraw.LOW_THINNING) th *= 0.85F;
			if (d.frontBulb() && d.reach() < 1F) th *= 1F + 0.3F * Math.max(0F, 1F - (vFront - v) / 3F);
			float f = hamon(tier, v, time, amp);
			if (s > KISSAKI) f = ArmMath.lerp(f, 0.45F, ArmMath.sm((s - KISSAKI) / 0.06F)); // the boshi
			if (stage >= ArmDraw.LOW_THINNING) f = Math.min(0.85F, f + 0.12F);
			f = ArmMath.lerp(f, 0.15F, hit);
			f = ArmMath.lerp(f, 0.10F, cut);
			// the back ripples (not into the point); the edge stays still unless the arm is failing
			float rip = 0.10F * (float) Math.sin(1.3F * v - 0.21F * time) * ArmMath.clamp01((0.85F - s) / 0.1F);
			float eF = h, zF = failing ? 0.15F * (float) Math.sin(1.9F * v - 0.5F * time + d.phase()) : 0F;
			float crack = stage >= ArmDraw.LOW_STARVING ? crackWeight(v / scale) : 0F;
			eF -= 0.35F * crack;
			float alpha = 1F;
			if (fringe) alpha = ArmMath.fringe(vFront - v, fringeW);
			if (crack > 0.5F && d.dissolve()) alpha = Math.min(alpha, 0.72F);
			float eC = 0.3F * h, eH = ArmMath.lerp(eC, eF, f);
			if (lod2) {
				POLY[0] = rip; POLY[1] = 0.4F * th;
				POLY[2] = rip; POLY[3] = -0.4F * th;
				POLY[4] = 0.55F * h; POLY[5] = -0.5F * th;
				POLY[6] = eF; POLY[7] = -zF;
				POLY[8] = 0.55F * h; POLY[9] = 0.5F * th;
			} else {
				// B, A, C, H+, F, H−, D in (n, b) = (along E, −z): the ridge (shinogi) at C and D, the hamon at H±
				POLY[0] = rip; POLY[1] = 0.35F * th;
				POLY[2] = rip; POLY[3] = -0.35F * th;
				POLY[4] = eC; POLY[5] = -0.5F * th;
				POLY[6] = eH; POLY[7] = -ArmMath.lerp(0.5F * th, zF, f);
				POLY[8] = eF; POLY[9] = -zF;
				POLY[10] = eH; POLY[11] = -ArmMath.lerp(-0.5F * th, zF, f);
				POLY[12] = eC; POLY[13] = 0.5F * th;
			}
			out.add(M.x, M.y, M.z, T.x, T.y, T.z, E.x, E.y, E.z, v, POLY, alpha);
		}
		final float red0 = vRed;
		final float sc = scale;
		final boolean starving = stage >= ArmDraw.LOW_STARVING;
		final int edgeA = lod2 ? 2 : 3, edgeB = lod2 ? 3 : 4;
		Loft.sweep(m, out, layer, (seg, face) -> {
			if (face < 0) return black;
			float vm = 0.5F * (out.arc(Math.max(0, seg)) + out.arc(Math.min(out.count() - 1, seg + 1)));
			if (starving && crackWeight(vm / sc) > 0.4F) return red;
			if (face == edgeA || face == edgeB) return vm <= red0 ? edge : black;
			return black;
		}, ST, PART_BLADE, 1F, true, truncated);
	}

	/** How deep in a starving crack arc {@code v} (level-2 px) is: 1 inside, easing out over 0.3 px. */
	private static float crackWeight(float v) {
		float w = 0F;
		for (int i = 0; i < CRACKS.length; i += 2) {
			float a = CRACKS[i], b = CRACKS[i + 1];
			if (v >= a - 0.3F && v <= b + 0.3F) w = Math.max(w, v < a ? 1F - (a - v) / 0.3F : v > b ? 1F - (v - b) / 0.3F : 1F);
		}
		return w;
	}

	/** K2: the red collar (a seven-sided band 1.25 × 0.55) and its two knots; it glows faintly at level 3. */
	private static void habaki(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, float scale) {
		if (scale <= 0.02F) return;
		ArmLayer red = tier >= 3 && d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		Stations out = RING.reset(7).feed(phiHilt(3.0F));
		out.color(layer == ArmLayer.GLOW ? FistGeometry.grey(0.3F) : -1);
		ArmMath.ring(POLY, 7, 1.25F * scale, 0.55F * scale, 0F);
		out.add(0F, 3.0F, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 0F, POLY, 1F);
		out.add(0F, 3.8F, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 0.8F, POLY, 1F);
		Loft.sweep(m, out, layer, (seg, face) -> red, ST, PART_HABAKI, 1F, false, false);
		if (d.lod() == 0) {
			for (int k = 0; k < 2; k++) {
				float z = k == 0 ? 0.75F : -0.75F;
				LOCAL.identity().translate(0F, 3.4F, z * scale).scale(scale);
				m.box(LOCAL, -0.35F, -0.4F, -0.35F, 0.7F, 0.8F, 0.7F, ST.u0(PART_HABAKI, 4 + k, 13F), ST.v0(PART_HABAKI, 4 + k, 13F), 1F, 1F, m.tint());
			}
		}
	}

	/**
	 * K3: the guard, seen down the blade a curving four-armed symbol made of tendrils: a breathing hub ring, and four
	 * arms sweeping out of it the same way round, each flaring wider toward its end (two tendril edges from level 2, the
	 * trailing one curling back), a red knot at each end, and level 3's thorns between them. Forming, the arms trace out
	 * from the hub and the knots pop one by one; retracting, the knots collapse in turn.
	 */
	private static void tsuba(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float rt, float guard) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int lod = d.lod();
		float time = d.time();
		breath = 1F + 0.02F * (float) Math.sin(0.04F * time);
		loopDrift = 0.004F * time;
		float cut = 1F + 0.3F * d.specialWeight(ArmProtocol.FX_SWIPE);
		float trace = detailed ? ArmMath.sm((ft - 2.5F) / 3F) : 1F;
		loopScale = detailed ? 1F : guard;
		if (lod >= 2) {
			// four guard boxes
			if (layer != black || guard <= 0.02F) return;
			for (int k = 0; k < 4; k++) {
				float a = ArmMath.PI / 2F * k + loopDrift;
				LOCAL.identity().translate(2.0F * guard * (float) Math.cos(a), 2.9F, 2.0F * guard * (float) Math.sin(a)).rotateY(-a).scale(guard);
				m.box(LOCAL, -0.9F, -0.35F, -0.9F, 1.8F, 0.7F, 1.8F, ST.u0(PART_LOOP, k, 13F), ST.v0(PART_LOOP, k, 13F), 1F, 1F, m.tint());
			}
			return;
		}
		if (layer == black && trace > 0.02F && loopScale > 0.02F) {
			if (lod == 0) {
				// the hub: the inner breathing ring the arms leave from (it also keeps clear of the blade's collar)
				int stations = 14;
				if (trace >= 0.999F) Loft.closedTube(m, LOOP2, stations, 4, LOOP2_W, black, layer, ST, PART_LOOP + 1, 1.5F, phiHilt(2.8F), UP_Y, 1F);
				else Loft.tube(m, LOOP2, 0F, trace, Math.max(2, Math.round(stations * trace)), 4, LOOP2_W, black, layer, ST, PART_LOOP + 1, 1.5F, 13F, 13F, UP_Y, 1F);
			}
			// the four arms, traced out from the hub as the guard forms (thin three-sided edges: section 2.10's budget)
			boolean pair = tier >= 2 && lod == 0;
			int armStations = lod == 0 ? 6 : 4, sides = pair || lod > 0 ? 3 : 4;
			for (int k = 0; k < 4; k++) {
				armK = k;
				int st = Math.max(2, Math.round(armStations * trace));
				for (int e = 0; e < (pair ? 2 : 1); e++) {
					armEdge = pair ? (e == 0 ? -1F : 1F) : 0F;
					Loft.tube(m, ARM, 0F, trace, st, sides, ARM_W, black, layer, ST, PART_ARM + 2 * k + e, 1.5F, phiHilt(3F), phiHilt(3F) + 3F, UP_Y, 1F);
				}
			}
		}
		// the knots at the arms' ends, popping in turn while forming, collapsing in turn while retracting
		ArmLayer lobeLayer = red;
		if (layer == lobeLayer) {
			for (int k = 0; k < 4; k++) {
				float pop = detailed ? pop(ft - 5.0F - 0.6F * k) : guard;
				if (rt >= 0F) pop = Math.min(pop, 1F - ArmMath.sm((rt - 3F - 1.25F * k) / 1.25F));
				pop *= cut;
				if (pop <= 0.02F) continue;
				armPoint(k, 0F, 1F, Q);
				float a = (float) Math.atan2(Q.z, Q.x);
				LOCAL.identity().translate(Q.x, Q.y, Q.z).rotateY(-a).scale(pop);
				m.box(LOCAL, -0.475F, -0.375F, -0.475F, 0.95F, 0.75F, 0.95F, ST.u0(PART_LOBE, k, 13F), ST.v0(PART_LOBE, k, 13F), 1F, 1F, m.tint());
				if (tier >= 3 && lod == 0) {
					// a thorn between this arm and the next, out and up toward the blade
					float b = ArmMath.PI / 2F * k + loopDrift + ArmMath.PI / 4F + 0.5F;
					float rt2 = 2.25F * 0.82F * breath * loopScale;
					LOCAL.identity().translate(rt2 * (float) Math.cos(b), 3.0F, rt2 * (float) Math.sin(b)).rotateY(ArmMath.PI / 2F - b).rotateX(-0.5F)
							.scale(Math.min(1F, pop));
					m.frustum(LOCAL, 1.1F, 0.5F, 0.5F, 0.05F, 0.05F, ST.u0(PART_THORN, k, 13F), ST.v0(PART_THORN, k, 13F), 2F, 1F, 1F, m.tint());
				}
			}
		}
	}

	/**
	 * Floating blobs of the arm's static drifting round the grip, held by nothing (2026-11-15): two, three or
	 * four by level (full detail only), each its own size, orbit, speed and bob, lumpy (two boxes turned against each other,
	 * slowly tumbling), the last one red. Forming, they pop out after the spines start; retracting, they shrink with the
	 * guard.
	 */
	private static final float[] BLOB_SIZE = {0.62F, 0.46F, 0.72F, 0.52F}, BLOB_R = {1.95F, 2.3F, 1.75F, 2.15F}, BLOB_Y = {-3.6F, 1.3F, -1.2F, -4.6F},
			BLOB_SPEED = {0.031F, -0.024F, 0.019F, -0.037F};

	private static void blobs(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float rt, float guard) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int n = tier + 1;
		float time = d.time();
		for (int k = 0; k < n; k++) {
			ArmLayer l = k == n - 1 && n > 2 ? red : black;
			if (layer != l) continue;
			float grow = detailed ? pop(ft - 9.5F - 0.7F * k) : guard;
			if (rt >= 0F) grow = Math.min(grow, 1F - ArmMath.sm((rt - 2F - k) / 2F));
			if (grow <= 0.02F) continue;
			float a = ArmMath.TAU * k / n + BLOB_SPEED[k] * time;
			float r = BLOB_R[k] + 0.25F * (float) Math.sin(0.07F * time + 1.3F * k);
			float y = BLOB_Y[k] + 0.35F * (float) Math.sin(0.09F * time + 1.7F * k);
			float size = BLOB_SIZE[k] * (1F + 0.1F * (float) Math.sin(0.13F * time + k)) * grow * (1F + 0.3F * d.surge());
			float tumble = 0.02F * time * (k % 2 == 0 ? 1F : -1F) + k;
			for (int b = 0; b < (k % 2 == 0 ? 2 : 1); b++) { // every other blob lumpy
				float sz = b == 0 ? size : 0.78F * size;
				LOCAL.identity().translate(r * (float) Math.cos(a), y, r * (float) Math.sin(a)).rotateY(tumble + b * 0.785F).rotateX(0.6F * tumble + b * 0.6F);
				m.box(LOCAL, -sz / 2F, -sz / 2F, -sz / 2F, sz, sz, sz, ST.u0(PART_BLOB, 2 * k + b, 6F), ST.v0(PART_BLOB, 2 * k + b, 6F), 1F, 1F, m.tint());
			}
		}
	}

	/**
	 * A point on guard arm {@code k}, edge {@code edge} (−1 trailing, +1 leading, 0 the middle line), at {@code s} 0..1
	 * from the hub out: the arm's centre sweeps {@link #ARM_SWEEP} rad round as it goes out (faster toward the end), its
	 * edges flare apart by up to {@link #ARM_FLARE} px each side, and the trailing edge's last quarter hooks back in.
	 * The guard breathes ({@link #breath}), drifts ({@link #loopDrift}) and writhes a little; {@link #loopScale} grows it.
	 */
	private static void armPoint(int k, float edge, float s, Vector3f out) {
		float time = cur == null ? 0F : cur.time();
		float a = ArmMath.PI / 2F * k + loopDrift + ARM_SWEEP * (float) Math.pow(s, 1.6) + 0.05F * (float) Math.sin(0.05F * time + 2.1F * k + 3F * s);
		float r = (ARM_R0 + (ARM_R1 - ARM_R0) * s) * breath;
		float curl = edge < 0F ? ArmMath.sm((s - 0.72F) / 0.28F) : 0F;
		a += edge * ARM_FLARE * (0.12F + s * s) / r - ARM_CURL * 0.35F * curl;
		r -= 0.9F * curl;
		float y = 3.0F + 0.22F * (float) Math.sin(ArmMath.PI * s) - 0.18F * s;
		out.set(r * (float) Math.cos(a) * loopScale, y, r * (float) Math.sin(a) * loopScale);
	}

	/** FORM's pop for something appearing {@code x} ticks after its moment: 0 → 1.25 → 1.0 over 2 ticks. */
	private static float pop(float x) {
		if (x <= 0F) return 0F;
		if (x < 1F) return 1.25F * ArmMath.sm(x);
		if (x < 2F) return ArmMath.lerp(1.25F, 1F, ArmMath.sm(x - 1F));
		return 1F;
	}

	/** K4..K6: the red core, the two wrap strands crossing over it, and the pommel's three knots. */
	private static void hilt(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, float pommel) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		int lod = d.lod();
		if (lod >= 2) {
			// LOD 2 (section 2.10): the hilt is one box, the pommel another
			if (layer != black) return;
			if (yHi - yLo > 0.05F) m.box(null, -0.8F, yLo, -0.68F, 1.6F, yHi - yLo, 1.36F, ST.u0(PART_WRAP, 0, 10F), ST.v0(PART_WRAP, 0, 10F), 1F, 1F, m.tint());
			if (pommel > 0.02F) {
				LOCAL.identity().translate(0F, -4.9F, 0F).scale(pommel);
				m.box(LOCAL, -0.85F, -0.45F, -0.7F, 1.7F, 0.9F, 1.4F, ST.u0(PART_KASHIRA, 0, 17F), ST.v0(PART_KASHIRA, 0, 17F), 1F, 1F, m.tint());
			}
			return;
		}
		if (yHi - yLo > 0.05F) {
			if (layer == red) {
				Stations out = RING.reset(4).feed(0F);
				float[] ys = CORE_YS;
				ys[0] = yLo;
				ys[1] = Math.max(yLo, Math.min(yHi, -1.0F));
				ys[2] = yHi;
				int added = 0;
				for (int i = 0; i < 3; i++) {
					if (i == 1 && (ys[1] <= yLo + 0.05F || ys[1] >= yHi - 0.05F)) continue;
					float y = ys[i];
					float k = Math.abs(y + 1.0F) / 3.6F;
					float hw = ArmMath.lerp(0.68F, 0.75F, ArmMath.clamp01(k)), hd = ArmMath.lerp(0.57F, 0.625F, ArmMath.clamp01(k));
					RECT[0] = hw; RECT[1] = -hd;
					RECT[2] = hw; RECT[3] = hd;
					RECT[4] = -hw; RECT[5] = hd;
					RECT[6] = -hw; RECT[7] = -hd;
					out.add(0F, y, 0F, 0F, 1F, 0F, 1F, 0F, 0F, y - yLo, RECT, 1F);
					added++;
				}
				if (added >= 2) Loft.sweep(m, out, layer, (seg, face) -> red, ST, PART_CORE, 1F, false, false);
			}
			if (layer == black) {
				int strands = tier <= 1 ? 1 : 2;
				int stations = lod == 0 ? 20 : lod == 1 ? 10 : 5;
				if (lod >= 2) strands = 1;
				swellA = d.swell(phiHilt(-2.5F), feedLength);
				for (int k = 0; k < strands; k++) {
					wrapStrand = k;
					int n = Math.max(2, Math.round(stations * (yHi - yLo) / 7.2F));
					Loft.tube(m, WRAP, 0F, 1F, n, 4, WRAP_W, black, layer, ST, PART_WRAP + k, 2F, phiHilt(yHi), phiHilt(yLo), UP_Y, 1F);
				}
			}
		}
		if (layer == black && pommel > 0.02F) {
			LOCAL.identity().translate(0F, -4.9F, 0F).scale(pommel);
			m.box(LOCAL, -0.85F, -0.45F, -0.7F, 1.7F, 0.9F, 1.4F, ST.u0(PART_KASHIRA, 0, 17F), ST.v0(PART_KASHIRA, 0, 17F), 1F, 1F, m.tint());
			if (lod <= 1) {
				LOCAL.identity().translate(0.3F, -5.3F, 0.1F).rotateZ((float) Math.toRadians(20)).scale(pommel);
				m.box(LOCAL, -0.6F, -0.4F, -0.6F, 1.2F, 0.8F, 1.2F, ST.u0(PART_KASHIRA, 1, 17F), ST.v0(PART_KASHIRA, 1, 17F), 1F, 1F, m.tint());
				LOCAL.identity().translate(-0.35F, -5.25F, -0.1F).rotateZ((float) Math.toRadians(-25)).scale(pommel);
				m.box(LOCAL, -0.5F, -0.35F, -0.5F, 1.0F, 0.7F, 1.0F, ST.u0(PART_KASHIRA, 2, 17F), ST.v0(PART_KASHIRA, 2, 17F), 1F, 1F, m.tint());
			}
		}
	}

	/**
	 * K7: the sageo tail hanging from the pommel, a tube through the rope's nodes (the renderer swings them; a still
	 * droop in the GUI), two links' worth of stations per link; level 3 trails a second one, turned 120°.
	 */
	private static void tail(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, float grow) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || grow <= 0.02F) return;
		float[] nodes = d.ropeNodes();
		if (nodes == null || nodes.length < TAIL.length) {
			Rope.droop(TAIL_ROOT_LOCAL, TAIL_OUT, DOWN, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, TAIL);
		} else {
			System.arraycopy(nodes, 0, TAIL, 0, TAIL.length);
			for (int i = 0; i < TAIL.length; i += 3) TAIL[i] *= EDGE_SIGN; // the rope swings in G: into the build space
		}
		tailWidthScale = 1F;
		int tails = tier >= 3 ? 2 : 1;
		for (int k = 0; k < tails; k++) {
			tailSet = k;
			Loft.tube(m, TAIL_CURVE, 0F, grow, 11, 4, TAIL_W, black, layer, ST, PART_TAIL + k, 2F, phiHilt(-5.4F), phiHilt(-5.4F) + 8F, UP_Z, 1F);
		}
	}

	/** A point on the tail at {@code s}: Catmull-Rom through the nodes, the second tail turned 120° about the hilt. */
	private static void tailPoint(float s, Vector3f out) {
		int links = ArmDraw.ROPE_NODES - 1;
		float f = ArmMath.clamp01(s) * links;
		int i = Math.min(links - 1, (int) f);
		float u = f - i;
		int i0 = Math.max(0, i - 1), i2 = Math.min(links, i + 1), i3 = Math.min(links, i + 2);
		float x = catmull(TAIL[3 * i0], TAIL[3 * i], TAIL[3 * i2], TAIL[3 * i3], u);
		float y = catmull(TAIL[3 * i0 + 1], TAIL[3 * i + 1], TAIL[3 * i2 + 1], TAIL[3 * i3 + 1], u);
		float z = catmull(TAIL[3 * i0 + 2], TAIL[3 * i + 2], TAIL[3 * i2 + 2], TAIL[3 * i3 + 2], u);
		if (tailSet == 1) {
			float c = -0.5F, sn = 0.8660254F;
			float rx = x * c - z * sn, rz = x * sn + z * c;
			x = rx;
			z = rz;
		}
		out.set(TAIL_ROOT.x + x, TAIL_ROOT.y + y, TAIL_ROOT.z + z);
	}

	private static float catmull(float p0, float p1, float p2, float p3, float u) {
		float u2 = u * u, u3 = u2 * u;
		return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
	}

	/**
	 * K8: fuzz along the back, short filaments leaning back from the blade and swaying, streaming behind a swing,
	 * bristling straight out for Draining Cut, longer in a surge, shorter when thinning.
	 */
	private static void fuzz(ArmDraw d, int tier, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float sprout) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || (!detailed && sprout <= 0.02F)) return;
		int lod = d.lod();
		if (lod >= 2) return;
		float[] at = tier <= 1 ? FUZZ_L1 : tier >= 3 ? FUZZ_L3 : FUZZ_L2;
		float scale = arcLength / L2_ARC;
		float time = d.time();
		float a = d.swing();
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(a));
		float bristle = d.specialWeight(ArmProtocol.FX_SWIPE);
		float thin = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int k = 0; k < at.length; k++) {
			if (lod == 1 && SPINE_LEN[k] < 1F) continue;
			float v = at[k] * scale;
			if (v > vFront - 0.5F) continue;
			// forming, each spine pops out in turn up the blade once the blade is out
			float grow = detailed ? pop(ft - SPINE_FROM - SPINE_STAGGER * k) : sprout;
			if (grow <= 0.02F) continue;
			float s = sAt(v);
			frameAt(s);
			float len = (1.6F + 0.5F * (float) Math.sin(0.09F * time + 1.9F * k) + 1.5F * d.surge()) * SPINE_LEN[k] * (1F + 0.4F * swing) * thin;
			len = ArmMath.lerp(len, 3F * SPINE_LEN[k], bristle) * grow;
			float base = (float) Math.toRadians(SPINE_ANG[k]);
			float side = SPINE_SIDE[k] * (1F - bristle);
			// rooted inside the blade, so each spine grows out of the back rather than sitting on it (my call: they looked
			// like they hovered)
			spinePoints(M.x + E.x * SPINE_ROOT, M.y + E.y * SPINE_ROOT, M.z + E.z * SPINE_ROOT, len, base, SPINE_CURL[k], side, time, k, swing, bristle);
			float bx = FUZZ_PTS[3], by = FUZZ_PTS[4], bz = FUZZ_PTS[5];
			fuzzWidth = SPINE_W[k];
			Loft.tube(m, POLYLINE, 0F, 1F, lod == 0 ? 4 : 3, 4, FUZZ_W, black, layer, ST, PART_FUZZ + k, 2F, phiBlade(v), phiBlade(v) + 2F, UP_Z, 1F);
			if (SPINE_FORK[k] && lod == 0) {
				// a small barb off the first third, swept further back and the other way sideways
				spinePoints(bx, by, bz, 0.45F * len, base + 0.75F, -SPINE_CURL[k], -side, time, k + 5, swing, bristle);
				fuzzWidth = 0.6F * SPINE_W[k];
				Loft.tube(m, POLYLINE, 0F, 1F, 3, 4, FUZZ_W, black, layer, ST, PART_FORK + k, 2F, phiBlade(v) + 0.7F, phiBlade(v) + 1.6F, UP_Z, 1F);
			}
		}
		fuzzWidth = 1F;
	}

	/**
	 * A spine's four points into FUZZ_PTS from ({@code x}, {@code y}, {@code z}), in the frame left by {@link #frameAt}:
	 * leaning {@code base} back from −E toward −T, hooking {@code curl} more each segment, swaying, streaming back in a
	 * swing, bristling straight out for Draining Cut, and leaning {@code side} out of the blade's plane.
	 */
	private static void spinePoints(float x, float y, float z, float len, float base, float curl, float side, float time, int k, float swing, float bristle) {
		FUZZ_PTS[0] = x;
		FUZZ_PTS[1] = y;
		FUZZ_PTS[2] = z;
		for (int j = 1; j <= 3; j++) {
			// the first segment leaves the blade more upright (and is longer by the root's depth); the lean comes after
			float step = len / 3F + (j == 1 ? SPINE_ROOT : 0F);
			float sway = 0.35F * (float) Math.sin(0.15F * time + 1.7F * k + 0.5F * j) * (j / 3F);
			float ang = (j == 1 ? 0.45F * base : base + curl * (j - 1)) + sway + (float) Math.toRadians(50) * swing;
			ang *= 1F - bristle;
			float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
			x += (-E.x * c - T.x * sn + Z.x * side) * step;
			y += (-E.y * c - T.y * sn + Z.y * side) * step;
			z += (-E.z * c - T.z * sn + Z.z * side) * step;
			FUZZ_PTS[3 * j] = x;
			FUZZ_PTS[3 * j + 1] = y;
			FUZZ_PTS[3 * j + 2] = z;
		}
	}

	/**
	 * Loose beads: two flicked off the point by a hit, beads dropping off a retracting front, a melt shedding two a tick
	 * at its erosion front, and a failing arm's three beads orbiting where its point used to be.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, float rt, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = d.hitAge();
		if (hitAge >= 0F && hitAge < 8F && vFront > 1F) {
			frameAt(sAt(vFront));
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float x = M.x + E.x * (0.8F * a + 0.3F * j) + T.x * 0.2F * j + DOWN.x * 0.06F * a * a;
				float y = M.y + E.y * (0.8F * a + 0.3F * j) + T.y * 0.2F * j + DOWN.y * 0.06F * a * a;
				float z = M.z + E.z * (0.8F * a + 0.3F * j) + T.z * 0.2F * j + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
			}
		}
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float a = rt - (2F + 1.4F * k);
				if (a < 0F || a > 3F) continue;
				float v = arcLength * (1F - ArmMath.sm((2F + 1.4F * k - 2F) / 7F));
				frameAt(sAt(v));
				FistGeometry.bead(d, layer, m, ST, k % 2 == 0 ? ArmLayer.BLACK : ArmLayer.RED, M.x + DOWN.x * 0.5F * a * a, M.y + DOWN.y * 0.5F * a * a,
						M.z + DOWN.z * 0.5F * a * a, 0.5F * (1F - a / 3F), 2 + k);
			}
		}
		float mt = d.meltTick();
		if (mt >= 2F && mt < 20F) {
			int newest = (int) Math.floor(mt * 2F);
			for (int q = 0; q < 8; q++) {
				int idx = newest - q;
				float born = idx / 2F;
				if (born < 2F || born > 16F) continue;
				float a = mt - born;
				if (a < 0F || a > 4F) continue;
				float v = arcLength * (1F - ArmMath.sm((born - 2F) / 14F));
				frameAt(sAt(v));
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, M.x + E.x * side * 1.5F + DOWN.x * 0.4F * a * a,
						M.y + E.y * side * 1.5F + DOWN.y * 0.4F * a * a, M.z + E.z * side * 1.5F + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			frameAt(1F);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * d.time() + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, M.x + 0.6F * ((float) Math.cos(a) * E.x + (float) Math.sin(a) * Z.x),
						M.y + 0.6F * ((float) Math.cos(a) * E.y + (float) Math.sin(a) * Z.y), M.z + 0.6F * ((float) Math.cos(a) * E.z + (float) Math.sin(a) * Z.z),
						0.5F, 16 + k);
			}
		}
	}

	/**
	 * K10: drips off the edge (whichever side of the blade hangs lower), the lowest guard lobe and the tail's end, each
	 * only while its piece is out ({@code guard}, {@code pommel}: how far the guard and the pommel with its tail are).
	 */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m, float guard, float pommel) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate();
		float scale = arcLength / L2_ARC;
		int j = 0;
		for (float v0 : EDGE_DRIPS) {
			float v = v0 * scale;
			if (v < vFront) {
				frameAt(sAt(v));
				float h = mihaba(sAt(v));
				float side = E.dot(DOWN) >= 0F ? h : 0F;
				Drips.emit(m, null, M.x + E.x * side, M.y + E.y * side, M.z + E.z * side, DOWN, d.time(), j + 4 * (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
			}
			j++;
		}
		if (guard < 0.9F) return; // no guard (and so no tail below it) to drip from yet
		// the knot of the guard arm that hangs lowest
		float best = -Float.MAX_VALUE, bx = 0F, by = 2.7F, bz = 0F;
		for (int k = 0; k < 4; k++) {
			armPoint(k, 0F, 1F, R);
			float dd = R.x * DOWN.x + R.z * DOWN.z;
			if (dd > best) {
				best = dd;
				bx = R.x;
				by = R.y - 0.4F;
				bz = R.z;
			}
		}
		Drips.emit(m, null, bx, by, bz, DOWN, d.time(), 2, rate, d.blocksToPx(), ST, PART_DRIP);
		if (d.tier() >= 2 && pommel >= 0.9F) {
			tailSet = 0;
			tailPoint(1F, Q);
			Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, d.time(), 3, rate, d.blocksToPx(), ST, PART_DRIP);
		}
	}

	/**
	 * FORM's burst tendrils, out of the knuckles to six points of the katana's outline: the point, the edge a third and
	 * two thirds up, the back near the middle, the pommel and a guard lobe; each sinks in as the growing blade (or the
	 * wraps, or the guard) reaches it.
	 */
	private static void burst(ArmDraw d, ArmLayer layer, SkinMesh m) {
		spine(Math.clamp(d.tier(), 1, 3), 0F, null);
		float saved = droop;
		droop = 0F;
		float l = ARC[32];
		float scale = l / L2_ARC;
		setTarget(0, 1F, 0F);
		setTarget(1, sAt(7F * scale), 1F);
		setTarget(2, sAt(14F * scale), 1F);
		setTarget(3, sAt(10F * scale), 0F);
		TARGETS[12] = 0F;
		TARGETS[13] = -5.3F;
		TARGETS[14] = 0F;
		TARGETS[15] = 2.66F;
		TARGETS[16] = 3.0F;
		TARGETS[17] = 0F;
		PASS[0] = passTick(l, l);
		PASS[1] = passTick(7F * scale, l);
		PASS[2] = passTick(14F * scale, l);
		PASS[3] = passTick(10F * scale, l);
		PASS[4] = 9F;
		PASS[5] = 5F;
		droop = saved;
		FistGeometry.burst(d, layer, m, ST, TARGETS, PASS);
	}

	/** Target {@code i} on the blade at {@code s}: the back, or ({@code edge} 1) the edge. */
	private static void setTarget(int i, float s, float edge) {
		frameAt(s);
		float h = mihaba(s) * edge;
		TARGETS[3 * i] = M.x + E.x * h;
		TARGETS[3 * i + 1] = M.y + E.y * h;
		TARGETS[3 * i + 2] = M.z + E.z * h;
	}

	/** The FORM tick the growing spine reaches arc {@code v} of {@code length}: inverting {@code sm((t−4)/6)}. */
	private static float passTick(float v, float length) {
		float y = ArmMath.clamp01(v / Math.max(1e-3F, length));
		float lo = 0F, hi = 1F;
		for (int i = 0; i < 14; i++) {
			float mid = 0.5F * (lo + hi);
			if (ArmMath.sm(mid) < y) lo = mid;
			else hi = mid;
		}
		return 4F + 6F * 0.5F * (lo + hi);
	}

	/** LOD 3: four boxes, a blade with its red edge, the guard and the hilt. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m, float reach, float guard) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float len = vFront;
		float ang = (float) Math.atan2(-(CP2.x - CP0.x), CP2.y - CP0.y);
		if (len > 0.3F) {
			LOCAL.identity().translate(CP0.x, CP0.y, 0F).rotateZ(ang);
			if (layer == black) m.box(LOCAL, 0F, 0F, -0.25F, 1.5F, len, 0.5F, ST.u0(PART_BLADE, 0, 20F), ST.v0(PART_BLADE, 0, 20F), 1F, 1F, m.tint());
			if (layer == red) m.box(LOCAL, 1.5F, 0F, -0.12F, 0.45F, len * 0.95F, 0.24F, ST.u0(PART_BLADE, 1, 20F), ST.v0(PART_BLADE, 1, 20F), 1F, 1F, m.tint());
		}
		if (layer == black && guard > 0.02F) {
			LOCAL.identity().translate(0F, 2.9F, 0F).scale(guard);
			m.box(LOCAL, -2.3F, -0.35F, -2.3F, 4.6F, 0.7F, 4.6F, ST.u0(PART_LOOP, 0, 13F), ST.v0(PART_LOOP, 0, 13F), 1F, 1F, m.tint());
		}
		if (layer == black && reach > 0.02F) m.box(null, -0.8F, -5.3F, -0.7F, 1.6F, 7.9F, 1.4F, ST.u0(PART_WRAP, 0, 10F), ST.v0(PART_WRAP, 0, 10F), 1F, 1F, m.tint());
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		int tier = Math.clamp(d.tier(), 1, 3);
		float saved = droop;
		droop = 0F;
		spine(tier, d.retractTick() >= 0F ? ArmMath.sm(d.retractTick() / 3F) : 0F, null);
		float l = ARC[32];
		float reach = d.reach();
		float up = d.detailedForm() ? P0Y + reach * l : reach * (P0Y + l);
		float front = ArmMath.clamp(up - P0Y, 0F, l);
		float v = which == ArmTips.MID ? 0.55F * front : front;
		ArmMath.bezier(CP0, CP1, CP2, sAt(v), out);
		out.x *= EDGE_SIGN;
		droop = saved;
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{-4.4F, 26F, 0.6F}, {1.2F, 14F, -0.6F}, {-3F, 3F, 3F}, {3F, 2.6F, -3F}, {2.7F, 3.2F, 0F}, {-2.7F, 3.2F, 0F}, {-1F, -5.8F, -1F},
				{1F, -12F, 1F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0] * EDGE_SIGN, p[1], p[2]));
	}
}
