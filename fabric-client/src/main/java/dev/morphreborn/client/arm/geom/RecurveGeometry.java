package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.Pulse;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The recurve (section 2.11): a bow of tendril, the forearm its riser. Two braided limbs of three strands grow from the
 * riser's ends, flex toward the archer and curl back at their tips, each carrying plates of chitin on its back with a red
 * ridge, a few swept barbs and a red nock knot where the string sits. The string is a thin red tendril that is alive
 * (it writhes, goes taut as the bow is drawn, and twangs when a bolt leaves). While the bow is drawn a black bolt with a
 * red core forms on the string, growing out of it a little at a time with an eroding front, and the string, the limbs
 * and the riser's hand all take the draw.
 * <p>
 * Grip space G is the bow's own frame ({@code ArmTransforms.bowGrip}): the origin on the riser's grip, {@code +Y} toward
 * the upper limb's tip, {@code +X} toward the string and the archer, {@code -X} the bow's back, facing the target. The
 * bolt points toward {@code -X}. Drawn with the vanilla bow's third-person transform and the handheld first-person
 * one ({@link #bowTransforms()}), so the bow is held, aimed and drawn like the one it replaces. The draw (the pull, 0 to
 * 1, vanilla's power curve of the seconds drawn) is read from {@link ArmDraw#bowPull()}, which every viewer has (the
 * use state of the player is in the entity data), and the twang from the SPEND pulse a shot starts.
 * <p>
 * Level 2 ("Honed") is the baseline. Level 1 ("Raw") has two strands a limb, two plates and one barb a limb, and no
 * cords. Level 3 ("Awakened") has longer limbs, five plates and five barbs a limb, red nock knots that glow, a glowing
 * core and a glowing bolt head.
 * <p>
 * Everything moves: the static crawls out through the riser into both limbs and the strands, pulses swell outward
 * through the limbs, the braid twists, the plates breathe, the barbs sway, the string writhes, the nock knots beat,
 * drips gather and fall. Low biomass thins it, cracks the limbs red, slackens and frays the string, erodes the tips and
 * makes it spasm. Every timeline is read from {@link ArmDraw}: forming, the riser grows out of the fist, the limbs
 * out of the riser, then the nocks, the string drawn tight between them, the plates, the barbs and the arrow rest follow,
 * base to tip.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class RecurveGeometry implements FormGeometry {
	/** The riser's half-length, and the pull of the string at full draw (px). */
	public static final float RISER_HALF = 3.6F, STRING_PULL = 6F;
	/** The bolt's length at full growth (px) and the draw at which it is fully formed. */
	public static final float BOLT_LENGTH = 12.5F, BOLT_FORMED_AT = 0.30F;
	/**
	 * The bow's size in your own first-person view, a share of its third-person one (2026-11-16, from the rig: at full draw the black limb
	 * filled about a third of the screen). The recurve spans 30 px (the riser and both limbs) and is three times as thick as the
	 * vanilla bow sprite it replaces, which spans about 17 px along its diagonal; vanilla's hand transform for the bow (the one used
	 * here, checked by ArmTransformsTest) puts that 17 px bow at the right of the view, so the same transform puts a 30 px one over it. 0.6 makes the span
	 * 18 px. Scaled about the grip (G's origin), so the hand holds it where it did; kept all the time in first person, not only
	 * while drawn, so the bow doesn't jump in size at the loose.
	 */
	public static final float FP_SCALE = 0.6F;
	/** The upper limb's centreline: a cubic from the riser's end out, flexing toward the string and recurving at the tip. */
	private static final float[] LIMB = {-0.1F, 3.5F, 2.6F, 5.4F, 4.2F, 8.6F, 2.4F, 11.4F};
	/** Limb length by level: 0.92, 1, 1.06 of level 2's. */
	private static final float[] TIER_SCALE = {0F, 0.92F, 1F, 1.06F};
	/** Where along the limb the string's nock sits: before the recurve begins to turn back. */
	private static final float NOCK_U = 0.80F;
	private static final float[][] LIMB_K = new float[4][8];
	private static final float[] LIMB_ARC = new float[4], NOCK_X = new float[4], NOCK_Y = new float[4];
	/** The limbs' feed coordinate starts where the riser ends. */
	private static final float PHI_LIMB0 = phiRiser(RISER_HALF);

	/**
	 * Each limb's chitin plates (position u along the limb, length px, tilt degrees, width along the flat), upper limb first
	 * then the lower; their own sizes and angles so neither limb repeats the other. Level 1 has two (the second and
	 * fourth), level 2 four, level 3 five.
	 */
	private static final float[][] PLATE_U = {{0.16F, 0.34F, 0.52F, 0.70F, 0.88F}, {0.20F, 0.38F, 0.55F, 0.73F, 0.90F}},
			PLATE_LEN = {{1.7F, 1.3F, 1.5F, 1.0F, 0.8F}, {1.4F, 1.6F, 1.1F, 1.2F, 0.7F}},
			PLATE_TILT = {{10F, -14F, 8F, -12F, 6F}, {-9F, 12F, -8F, 14F, -10F}},
			PLATE_WIDTH = {{1.1F, 1.0F, 1.25F, 0.9F, 0.7F}, {1.0F, 1.2F, 0.95F, 1.1F, 0.75F}};
	/**
	 * Each limb's barbs on its back, each with its own position, length, lean from the limb's back toward the tip
	 * (degrees), hook, thickness, sideways lean and fork; level 1 keeps the first, level 2 three, level 3 five.
	 */
	private static final float[][] BARB_U = {{0.30F, 0.52F, 0.70F, 0.42F, 0.84F}, {0.34F, 0.48F, 0.66F, 0.26F, 0.80F}},
			BARB_LEN = {{1.5F, 1.0F, 1.3F, 0.8F, 0.7F}, {0.9F, 1.4F, 1.1F, 0.8F, 0.65F}},
			BARB_LEAN = {{52F, 33F, 46F, 60F, 40F}, {38F, 57F, 28F, 44F, 63F}},
			BARB_CURL = {{0.3F, -0.2F, 0.4F, 0.1F, 0.25F}, {-0.25F, 0.35F, 0.1F, -0.3F, 0.2F}},
			BARB_W = {{0.9F, 1.15F, 0.8F, 0.7F, 0.6F}, {1.1F, 0.8F, 1.25F, 0.7F, 0.6F}},
			BARB_SIDE = {{0.12F, -0.18F, 0.2F, -0.1F, 0.1F}, {-0.15F, 0.1F, -0.2F, 0.18F, -0.1F}};
	private static final boolean[][] BARB_FORK = {{false, true, false, false, false}, {true, false, true, false, false}};
	/** FORM: the first plate pops at this tick, then one every {@link #PLATE_STAGGER}; the barbs come after. */
	private static final float PLATE_FROM = 7.4F, PLATE_STAGGER = 0.5F, BARB_FROM = 9.4F;
	/** Starving cracks on each limb (u). */
	private static final float CRACK_A = 0.30F, CRACK_B = 0.36F;

	// static parts, one per piece (each segment of a piece then takes its own offset)
	private static final int PART_RISER = 1, PART_WRAP = 2, PART_RING = 5, PART_KNOT = 8, PART_BRAID = 10, PART_CORE = 16, PART_PLATE = 20, PART_RIDGE = 30,
			PART_NOCK = 40, PART_STRING = 42, PART_BARB = 44, PART_FORK = 54, PART_CLAW = 64, PART_BOLT = 70, PART_DRIP = 90, PART_FAR = 92;

	private static final Static ST = new Static();
	private static final float[] PL = new float[3 * 8];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f DOWN = new Vector3f(), M = new Vector3f(), T = new Vector3f(), P = new Vector3f(), B = new Vector3f(0F, 0F, 1F), Q = new Vector3f(),
			R = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, lod, plCount, side, strandK, strands;
	private static float feedLength, droop, flex, pull, slack, yHi, yLo, limbReach, fringeW, swellA, tubeW, stringReach, boltReach, thin, ringScale, ringY,
			ringTurn, twang;
	private static boolean dissolve, glowCore;
	private static ArmLayer strandLayer = ArmLayer.BLACK;

	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * (plCount - 1);
		int i = Math.min(plCount - 2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(PL[3 * i], PL[3 * i + 3], u), ArmMath.lerp(PL[3 * i + 1], PL[3 * i + 4], u), ArmMath.lerp(PL[3 * i + 2], PL[3 * i + 5], u));
	};
	private static final Loft.RadiusFn BARB_RADIUS = s -> FistGeometry.width(ArmMath.lerp(1F, 0.3F, s) * tubeW, 4);
	private static final Loft.RadiusFn STRING_RADIUS = s -> FistGeometry.width(0.3F * (1F + 0.5F * swellA), 4);
	private static final Loft.CurveFn RISER = (s, out) -> {
		float y = ArmMath.lerp(yLo, yHi, s);
		out.set(riserX(y), y, 0F);
	};
	private static final Loft.RadiusFn RISER_W = s -> FistGeometry.width(1.05F, 4);
	private static int wrapStrand;
	private static final Loft.CurveFn WRAP = (s, out) -> {
		float y = ArmMath.lerp(yHi, yLo, s);
		float ph = 2F * ArmMath.PI * (y + RISER_HALF) / 2.1F;
		float a = wrapStrand == 0 ? ph : -ph + ArmMath.PI;
		float wr = 1F + 0.05F * (float) Math.sin(cur.phase() + 5F * s + 0.31F * cur.time() + wrapStrand * 2F);
		out.set(riserX(y) + 0.82F * wr * (float) Math.cos(a), y, 0.7F * wr * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn WRAP_W = s -> FistGeometry.width(0.62F * (1F + 0.45F * swellA), 4);
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.05F * (1F + 0.07F * (float) Math.cos(3F * (a - ringTurn))) * ringScale;
		out.set(riserX(ringY) + r * (float) Math.cos(a), ringY + 0.1F * (float) Math.sin(3F * (a - ringTurn)) * ringScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.45F * ringScale, 4);
	/** A braid strand along limb {@link #side}: the centreline plus its own turn about it. */
	private static final Loft.CurveFn BRAID = (s, out) -> {
		float u = s * limbReach;
		limbFrame(u, side);
		float arc = u * LIMB_ARC[tier];
		float psi = ArmMath.TAU * strandK / strands + ArmMath.TAU * arc / 4.2F + 0.03F * cur.time() * (side > 0 ? 1F : -1F);
		float r = (0.50F + 0.14F * (float) Math.sin(ArmMath.PI * arc / 2.1F + ArmMath.TAU * strandK / strands)) * (1F - 0.42F * u);
		out.set(M).fma(r * (float) Math.cos(psi), P).fma(r * (float) Math.sin(psi), B);
		// the writhe, small, along the frame's two sides
		float wn = ArmMath.writheN(s, cur.phase() + strandK + side, cur.time()), wb = ArmMath.writheB(s, cur.phase() + strandK, cur.time());
		out.fma(wn * (float) Math.sin(ArmMath.PI * s), P).fma(wb * (float) Math.sin(ArmMath.PI * s), B);
	};
	private static final Loft.RadiusFn BRAID_W = s -> {
		float u = s * limbReach;
		float w = ArmMath.lerp(0.82F, 0.46F, u) * thin * (1F + cur.swell(limbPhi(u), feedLength));
		if (cur.frontBulb() && limbReach < 1F) w *= 1F + 0.3F * Math.max(0F, 1F - (limbReach - u) * LIMB_ARC[tier] / 3F);
		return FistGeometry.width(w, 4);
	};
	private static final Loft.AlphaFn BRAID_ALPHA = s -> {
		float u = s * limbReach;
		if (dissolve && limbReach < 1F) return ArmMath.fringe((limbReach - u) * LIMB_ARC[tier], fringeW);
		return 1F;
	};
	private static final Loft.CurveFn CORE_CURVE = (s, out) -> {
		limbFrame(s * limbReach, side);
		out.set(M);
	};
	private static final Loft.RadiusFn CORE_W = s -> FistGeometry.width(0.5F * thin, 4);
	/** The string from a nock to the string's middle: a taut tendril, writhing less as it is drawn. */
	private static final Loft.CurveFn STRING = (s, out) -> {
		float t = s * stringReach;
		// from the nock (t = 0) to the middle (t = 1)
		float x = ArmMath.lerp(Q.x, R.x, t), y = ArmMath.lerp(Q.y, R.y, t);
		float pin = (float) Math.sin(ArmMath.PI * s);
		float env = pin * (0.14F * (1F - 0.75F * pull) + 0.18F * slack);
		float wa = env * (float) Math.sin(cur.phase() + 7F * t + 0.35F * cur.time() + side), wb = 0.6F * env * (float) Math.cos(0.8F * cur.phase() + 5F * t + 0.28F * cur.time());
		// after a shot the string rings: a fast wave across the bow's flat, dying away
		float ring = twang * pin * (float) Math.sin(2.6F * cur.time() - 2F * t);
		out.set(x + wa + 0.4F * ring, y, wb + ring);
	};

	static {
		for (int t = 1; t <= 3; t++) prepare(t);
	}

	public RecurveGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.BOW;
	}

	@Override
	public float feedLength(int tier) {
		return PHI_LIMB0 + LIMB_ARC[Math.clamp(tier, 1, 3)];
	}

	@Override
	public float guiScale() {
		// the bow's grip is the middle of its arc, so the slot's centre: the limb tips (y 11.4, x to 4.3 at level 3) fit in 1..15 on both axes
		return 0.56F;
	}

	@Override
	public float guiGripPx() {
		return 8F;
	}

	@Override
	public boolean bowTransforms() {
		return true;
	}

	/** One limb's arc length at {@code tier}, px, as drawn at rest. */
	public static float limbArc(int tier) {
		return LIMB_ARC[Math.clamp(tier, 1, 3)];
	}

	/** Where the string's nock is at rest on the upper limb at {@code tier}: x, y (the lower limb's is its mirror in y). */
	public static float[] nock(int tier) {
		int t = Math.clamp(tier, 1, 3);
		return new float[] {NOCK_X[t], NOCK_Y[t]};
	}


	/** The feed coordinate on the riser at height {@code y}: up from the fist, and down. */
	static float phiRiser(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	private static float limbPhi(float u) {
		return PHI_LIMB0 + u * LIMB_ARC[tier];
	}

	/** The riser's centreline x at height {@code y}: a slight belly toward the target at the grip. */
	private static float riserX(float y) {
		float k = y / (RISER_HALF + 0.4F);
		return -0.55F * Math.max(0F, 1F - k * k);
	}

	// ------------------------------------------------------------------ the limbs

	/** Level {@code t}'s limb scaled about its base, and its arc length and nock. */
	private static void prepare(int t) {
		float k = TIER_SCALE[t];
		for (int i = 0; i < 4; i++) {
			LIMB_K[t][2 * i] = LIMB[0] + (LIMB[2 * i] - LIMB[0]) * k;
			LIMB_K[t][2 * i + 1] = LIMB[1] + (LIMB[2 * i + 1] - LIMB[1]) * k;
		}
		tier = t;
		float arc = 0F, px = LIMB_K[t][0], py = LIMB_K[t][1];
		for (int i = 1; i <= 64; i++) {
			float x = bez(t, 0, i / 64F), y = bez(t, 1, i / 64F);
			arc += (float) Math.hypot(x - px, y - py);
			px = x;
			py = y;
		}
		LIMB_ARC[t] = arc;
		NOCK_X[t] = bez(t, 0, NOCK_U);
		NOCK_Y[t] = bez(t, 1, NOCK_U);
	}

	private static float bez(int t, int axis, float u) {
		float[] k = LIMB_K[t];
		float w = 1F - u;
		return w * w * w * k[axis] + 3F * w * w * u * k[2 + axis] + 3F * w * u * u * k[4 + axis] + u * u * u * k[6 + axis];
	}

	private static float bezD(int t, int axis, float u) {
		float[] k = LIMB_K[t];
		float w = 1F - u;
		return 3F * w * w * (k[2 + axis] - k[axis]) + 6F * w * u * (k[4 + axis] - k[2 + axis]) + 3F * u * u * (k[6 + axis] - k[4 + axis]);
	}

	/**
	 * Limb {@code sd} (+1 upper, -1 lower) at {@code u}: its centreline into M (flexed toward the archer by the draw, drooping
	 * when melting), its tangent T toward the tip, P (toward the limb's back, away from the string) and B (the flat).
	 */
	private static void limbFrame(float u, int sd) {
		float x = bez(tier, 0, u), y = bez(tier, 1, u);
		float dx = bezD(tier, 0, u), dy = bezD(tier, 1, u);
		// the draw flexes the tip 1.5 px toward the archer and 0.9 px in toward the riser, growing with u²
		x += 1.5F * flex * u * u;
		y -= 0.9F * flex * u * u;
		dx += 3F * flex * u;
		dy -= 1.8F * flex * u;
		float l = (float) Math.hypot(dx, dy);
		if (l < 1e-6F) {
			dx = 0F;
			dy = 1F;
			l = 1F;
		}
		dx /= l;
		dy /= l;
		T.set(dx, sd * dy, 0F);
		P.set(-dy, sd * dx, 0F).mul(1F);
		// the back is on the far side of the limb from the string: left of the upper limb's heading, mirrored for the lower
		M.set(x, sd * y, 0F);
		if (droop > 0F) {
			float d = (PHI_LIMB0 - FistGeometry.PHI_TOOL + u * LIMB_ARC[tier]) / (PHI_LIMB0 - FistGeometry.PHI_TOOL + LIMB_ARC[tier]);
			M.fma(droop * d * d, DOWN);
		}
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (d == null || layer == ArmLayer.SENSE) return;
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		lod = Math.clamp(d.lod(), 0, 3);
		feedLength = feedLength(tier);
		ST.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		d.down(DOWN);
		if (d.firstPerson()) {
			FistGeometry.mirror(m, d.leftHand());
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, 1.05F, feedLength);
			FistGeometry.mirror(m, false);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm() * viewScale(d);
		m.transform(sp != 1F ? LOCAL.scaling(sp) : null);
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	/** The share of its size the bow is drawn at in {@code d}'s view: {@link #FP_SCALE} in your own hand pass, 1 everywhere else. */
	static float viewScale(ArmDraw d) {
		return d != null && d.firstPerson() ? FP_SCALE : 1F;
	}

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float reach = d.reach();
		boolean detailed = d.detailedForm();
		float ft = d.formTick();
		float rt = d.retractTick();
		droop = d.droop();
		dissolve = d.dissolve();
		fringeW = d.fringe();
		int stage = d.lowStage();
		boolean failing = stage == ArmDraw.LOW_FAILING;
		thin = stage >= ArmDraw.LOW_THINNING ? 0.85F : 1F;
		// the draw: the limbs flex and the string comes back; the idle bow breathes a hair; a shot twangs the string
		pull = d.bowPull();
		flex = pull + 0.1F * (float) Math.sin(0.05F * d.time() + d.phase());
		slack = stage >= ArmDraw.LOW_STARVING ? 1F : 0F;
		twang = twang(d);
		// the plan: how much of each piece is out
		float arc = LIMB_ARC[tier];
		float total = RISER_HALF + arc;
		float riserOut, limbOut, nockS, stringS;
		if (detailed) {
			riserOut = ArmMath.sm((ft - 1F) / 3F);
			limbOut = ArmMath.sm((ft - 1.5F) / 6.5F);
			nockS = pop(ft - 7.2F);
			stringS = ArmMath.sm((ft - 8F) / 2F);
		} else {
			float up = reach * total;
			riserOut = ArmMath.clamp01(up / RISER_HALF);
			limbOut = ArmMath.clamp01((up - RISER_HALF) / arc);
			nockS = ArmMath.sm((limbOut - NOCK_U + 0.05F) / 0.08F);
			stringS = ArmMath.sm((limbOut - NOCK_U) / 0.2F);
			if (rt >= 0F) stringS = Math.min(stringS, 1F - ArmMath.sm((rt - 1F) / 2F));
		}
		limbReach = limbOut;
		if (failing) limbReach = Math.min(limbReach, 0.92F);
		yHi = RISER_HALF * riserOut;
		yLo = -RISER_HALF * riserOut;
		stringReach = stringS;
		boltReach = pull > 0.015F && stringS > 0.9F ? ArmMath.sm(pull / BOLT_FORMED_AT) : 0F;
		glowCore = tier >= 3 && d.glow();
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m);
			return;
		}
		riser(d, layer, m, detailed, ft, rt);
		limbs(d, layer, m, detailed, ft);
		plates(d, layer, m, detailed, ft);
		nocks(d, layer, m, nockS);
		barbs(d, layer, m, detailed, ft);
		string(d, layer, m, stringS);
		bolt(d, layer, m);
		claws(d, layer, m, detailed, ft);
		beads(d, layer, m, rt, failing);
		if (lod == 0 && d.dripsNow() && (!detailed || ft >= 13F)) drips(d, layer, m);
	}

	/** FORM's pop for something appearing {@code x} ticks after its moment: 0 to 1.25 to 1.0 over 2 ticks. */
	private static float pop(float x) {
		if (x <= 0F) return 0F;
		if (x < 1F) return 1.25F * ArmMath.sm(x);
		if (x < 2F) return ArmMath.lerp(1.25F, 1F, ArmMath.sm(x - 1F));
		return 1F;
	}

	/**
	 * The string's twang after a shot: a bolt's SPEND pulse starts it, and the string, back at rest, rings for 8 ticks, the
	 * wave dying away. Zero while drawn or with no recent pulse.
	 */
	private static float twang(ArmDraw d) {
		if (d.bowPull() > 0.015F) return 0F;
		float age = ArmDraw.NEVER;
		float[] pulses = d.pulses();
		for (int i = 0; i < d.pulseCount() && 2 * i + 1 < pulses.length; i++) {
			if ((int) pulses[2 * i] != Pulse.Kind.SPEND.ordinal()) continue;
			float a = d.time() - pulses[2 * i + 1];
			if (a >= 0F && a < age) age = a;
		}
		if (age > 8F) return 0F;
		return 0.9F * (float) Math.exp(-0.35F * age);
	}

	// ------------------------------------------------------------------ the riser

	/** The riser: a red core, two wrap strands crossing over it, rings at its ends, and the limbs' root knots. */
	private static void riser(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float rt) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (yHi < 0.05F) return;
		if (lod >= 2) {
			if (layer == black) m.box(null, -1.0F, yLo, -0.7F, 1.6F, yHi - yLo, 1.4F, ST.u0(PART_RISER, 0, 10F), ST.v0(PART_RISER, 0, 10F), 1F, 1F, m.tint());
			return;
		}
		float wrapReach = detailed ? ArmMath.sm((ft - 2F) / 3F) : 1F;
		if (layer == (glowCore ? ArmLayer.GLOW : red)) {
			Loft.tube(m, RISER, 0F, 1F, lod == 0 ? 5 : 3, 4, RISER_W, glowCore ? ArmLayer.GLOW : red, layer, ST, PART_RISER, 1F, phiRiser(yLo), phiRiser(yHi), UP_Z, 1F);
		}
		if (glowCore && layer == red) return;
		if (layer == black && wrapReach > 0.02F) {
			swellA = d.swell(phiRiser(2.5F), feedLength);
			int strandsN = tier <= 1 ? 1 : 2;
			int stations = lod == 0 ? 9 : 5;
			float savedHi = yHi, savedLo = yLo;
			yLo = ArmMath.lerp(savedHi, savedLo, wrapReach);
			for (int k = 0; k < strandsN; k++) {
				wrapStrand = k;
				float span = savedHi - yLo;
				Loft.tube(m, WRAP, 0F, 1F, Math.max(2, Math.round(stations * span / (2F * RISER_HALF))), 4, WRAP_W, black, layer, ST, PART_WRAP + k, 2F, phiRiser(savedHi),
						phiRiser(yLo), UP_Y, 1F);
			}
			yLo = savedLo;
			yHi = savedHi;
		}
		if (layer == black && lod == 0) {
			// rings binding the limbs' roots (turning against each other), and a knot where each limb leaves the riser
			for (int k = 0; k < 2; k++) {
				float y = (k == 0 ? 1F : -1F) * 2.9F;
				if (Math.abs(y) > yHi + 0.05F) continue;
				ringScale = detailed ? pop(ft - (5F + 0.6F * k)) : ArmMath.sm((yHi - Math.abs(y)) / 0.8F);
				if (rt >= 0F) ringScale = Math.min(ringScale, 1F - ArmMath.sm((rt - 3F - 1.2F * k) / 1.5F));
				if (ringScale <= 0.02F) continue;
				ringY = y;
				ringTurn = ((k & 1) == 0 ? 0.02F : -0.02F) * d.time() + k;
				Loft.closedTube(m, RING, 6, 4, RING_W, black, layer, ST, PART_RING + k, 2F, phiRiser(y), UP_Y, 1F);
			}
		}
		if (layer == black && lod == 0) {
			for (int k = 0; k < 2; k++) {
				float sd = k == 0 ? 1F : -1F;
				if (RISER_HALF > yHi + 0.05F) continue;
				float s = detailed ? pop(ft - 4.2F - 0.5F * k) : ArmMath.sm((yHi - RISER_HALF + 0.05F) / 0.3F);
				if (s <= 0.02F) continue;
				s *= 1F + 0.2F * d.swell(PHI_LIMB0, feedLength);
				LOCAL.translation(-0.1F, sd * (RISER_HALF + 0.1F), 0F).rotateZ(sd * 0.25F).scale(s);
				m.box(LOCAL, -0.8F, -0.5F, -0.65F, 1.6F, 1.0F, 1.3F, ST.u0(PART_KNOT, k, PHI_LIMB0), ST.v0(PART_KNOT, k, PHI_LIMB0), 1F, 1F, m.tint());
			}
		}
	}

	// ------------------------------------------------------------------ the limbs

	/** Both limbs: three braided strands round a red core (level 1 two strands), cut at the growth front, flexing with the draw. */
	private static void limbs(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (limbReach <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		ArmLayer coreLayer = glowCore ? ArmLayer.GLOW : red;
		int cracked = d.lowStage() >= ArmDraw.LOW_STARVING ? 1 : 0;
		for (int sd = 1; sd >= -1; sd -= 2) {
			side = sd;
			if (lod >= 2) {
				// one strand, drawn as a single four-sided tube
				strands = 1;
				strandK = 0;
				if (layer == black) {
					Loft.tube(m, CORE_CURVE, 0F, 1F, 5, 4, s -> FistGeometry.width(ArmMath.lerp(1.1F, 0.4F, s), 4), black, layer, ST, PART_BRAID + (sd > 0 ? 0 : 3), 1.5F,
							PHI_LIMB0, limbPhi(limbReach), UP_Z, 1F);
				}
				continue;
			}
			// the red core between the strands (and its starving cracks)
			if (layer == coreLayer) {
				Loft.tube(m, CORE_CURVE, 0F, 1F, lod == 0 ? 5 : 3, 4, CORE_W, coreLayer, layer, ST, PART_CORE + (sd > 0 ? 0 : 1), 1.5F, PHI_LIMB0, limbPhi(limbReach), UP_Z, 1F);
			}
			strands = tier <= 1 || lod >= 1 ? 2 : 3;
			int stations = lod == 0 ? 10 : 6;
			for (int k = 0; k < strands; k++) {
				strandK = k;
				if (cracked > 0) {
					// the starving crack: its stretch of strand is red and thin (a second, short tube is laid over the black one)
					if (layer == red) crackStrand(m, red, layer, stations);
				}
				if (layer == black) {
					Loft.tube(m, BRAID, 0F, 1F, Math.max(3, Math.round(stations * limbReach)), 4, BRAID_W, black, layer, ST, PART_BRAID + (sd > 0 ? 0 : 3) + k, 1.5F,
							PHI_LIMB0, limbPhi(limbReach), UP_Z, BRAID_ALPHA);
				}
			}
		}
	}

	/** A strand's starving crack: the stretch u in CRACK_A..CRACK_B as a thin red tube over the strand. */
	private static void crackStrand(SkinMesh m, ArmLayer red, ArmLayer layer, int stations) {
		if (limbReach < CRACK_B) return;
		int n = 3;
		Loft.CurveFn crack = (s, out) -> {
			float full = ArmMath.lerp(CRACK_A, CRACK_B, s) / limbReach;
			BRAID.at(full, out);
		};
		Loft.tube(m, crack, 0F, 1F, n, 4, s -> FistGeometry.width(0.5F, 4), red, layer, ST, PART_BRAID + 8 + (side > 0 ? 0 : 3) + strandK, 2F, limbPhi(CRACK_A), limbPhi(CRACK_B),
				UP_Z, 1F);
	}

	/**
	 * Chitin plates on each limb's back, each a box with a red ridge, laid along the limb at its own tilt, position and size.
	 * Forming they pop one by one up the limbs once the strands are out; they breathe (a few percent) and swell with the
	 * pulses.
	 */
	private static void plates(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (lod >= 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int count = tier <= 1 ? 2 : tier >= 3 ? 5 : 4;
		float time = d.time();
		for (int sd = 1; sd >= -1; sd -= 2) {
			int limb = sd > 0 ? 0 : 1;
			for (int j = 0; j < count; j++) {
				int idx = tier <= 1 ? 1 + 2 * j : j;
				if (lod == 1 && (j & 1) == 1) continue;
				float u = PLATE_U[limb][idx];
				if (u > limbReach - 0.04F) continue;
				float grow = detailed ? pop(ft - PLATE_FROM - PLATE_STAGGER * j) : ArmMath.sm((limbReach - u - 0.04F) / 0.12F);
				if (grow <= 0.02F) continue;
				side = sd;
				limbFrame(u, sd);
				float amp = d.swell(limbPhi(u), feedLength);
				float breath = 1F + 0.03F * (float) Math.sin(0.05F * time + 1.7F * j + limb) + 0.3F * amp;
				float len = PLATE_LEN[limb][idx] * breath * grow * thin, wd = PLATE_WIDTH[limb][idx] * grow * thin;
				float tilt = (float) Math.toRadians(PLATE_TILT[limb][idx]);
				float ang = (float) Math.atan2(T.y, T.x) - ArmMath.PI / 2F + tilt;
				float off = (0.38F + 0.28F * (1F - u)) * grow;
				if (layer == black) {
					LOCAL.translation(M.x + P.x * off, M.y + P.y * off, 0F).rotateZ(ang);
					m.box(LOCAL, -0.28F, -len / 2F, -wd / 2F, 0.56F, len, wd, ST.u0(PART_PLATE + 5 * limb + j, 0, limbPhi(u)), ST.v0(PART_PLATE + 5 * limb + j, 0, limbPhi(u)), 1.5F,
							1F, m.tint());
				} else if (layer == red && lod == 0) {
					// the ridge: a thin red strip down the plate's back
					LOCAL.translation(M.x + P.x * (off + 0.3F * grow), M.y + P.y * (off + 0.3F * grow), 0F).rotateZ(ang);
					m.box(LOCAL, -0.08F, -len * 0.45F, -0.1F, 0.16F, len * 0.9F, 0.2F, ST.u0(PART_RIDGE + 5 * limb + j, 0, limbPhi(u)),
							ST.v0(PART_RIDGE + 5 * limb + j, 0, limbPhi(u)), 2F, 1F, m.tint());
				}
			}
		}
	}

	/** The nock knots where the string sits on each limb: a red knot with two small prongs, beating with the pulses (glowing at level 3). */
	private static void nocks(ArmDraw d, ArmLayer layer, SkinMesh m, float s) {
		if (lod >= 2 || s <= 0.02F || limbReach < NOCK_U - 0.06F) return;
		ArmLayer red = tier >= 3 && d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		for (int sd = 1; sd >= -1; sd -= 2) {
			side = sd;
			limbFrame(NOCK_U, sd);
			float beat = 1F + 0.35F * d.swell(limbPhi(NOCK_U), feedLength) + d.knotBeat();
			float k = s * beat;
			float ang = (float) Math.atan2(T.y, T.x) - ArmMath.PI / 2F;
			LOCAL.translation(M.x, M.y, 0F).rotateZ(ang).scale(k);
			m.box(LOCAL, -0.5F, -0.4F, -0.45F, 1.0F, 0.8F, 0.9F, ST.u0(PART_NOCK, sd > 0 ? 0 : 1, limbPhi(NOCK_U)), ST.v0(PART_NOCK, sd > 0 ? 0 : 1, limbPhi(NOCK_U)), 1.5F, 1F,
					m.tint());
		}
	}

	/**
	 * Barbs on the limbs' backs, each swept toward the tip with its own length, lean, hook, thickness and sideways lean (a few
	 * forked): they sway, lengthen with a surge, shorten when thinning.
	 */
	private static void barbs(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (lod >= 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		int count = tier <= 1 ? 1 : tier >= 3 ? 5 : 3;
		float time = d.time();
		float thinB = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int sd = 1; sd >= -1; sd -= 2) {
			int limb = sd > 0 ? 0 : 1;
			for (int j = 0; j < count; j++) {
				if (lod == 1 && BARB_LEN[limb][j] < 1.0F) continue;
				float u = BARB_U[limb][j];
				if (u > limbReach - 0.05F) continue;
				float grow = detailed ? pop(ft - BARB_FROM - 0.4F * j - 0.3F * limb) : ArmMath.sm((limbReach - u - 0.05F) / 0.12F);
				if (grow <= 0.02F) continue;
				side = sd;
				limbFrame(u, sd);
				float len = (BARB_LEN[limb][j] + 0.22F * (float) Math.sin(0.09F * time + 1.9F * j + limb) + 0.7F * d.surge() * BARB_LEN[limb][j]) * thinB * grow;
				float lean = (float) Math.toRadians(BARB_LEAN[limb][j]);
				Q.set(M).fma(0.45F, P);
				barbPoints(Q.x, Q.y, Q.z, len, lean, BARB_CURL[limb][j], BARB_SIDE[limb][j], time, j + 3 * limb);
				float bx = PL[3], by = PL[4], bz = PL[5];
				tubeW = BARB_W[limb][j];
				plCount = 4;
				float phi = limbPhi(u);
				Loft.tube(m, POLYLINE, 0F, 1F, lod == 0 ? 4 : 3, 4, BARB_RADIUS, black, layer, ST, PART_BARB + 5 * limb + j, 2F, phi, phi + 2F, UP_Z, 1F);
				if (BARB_FORK[limb][j] && lod == 0) {
					barbPoints(bx, by, bz, 0.5F * len, lean + 0.7F, -BARB_CURL[limb][j], -BARB_SIDE[limb][j], time, j + 5 + 3 * limb);
					PL[6] = PL[9];
					PL[7] = PL[10];
					PL[8] = PL[11];
					plCount = 2;
					tubeW = 0.6F * BARB_W[limb][j];
					Loft.tube(m, POLYLINE, 0F, 1F, 2, 4, BARB_RADIUS, black, layer, ST, PART_FORK + 5 * limb + j, 2F, phi + 0.6F, phi + 1.2F, UP_Z, 1F);
				}
			}
		}
		tubeW = 1F;
	}

	/** A barb's four points into PL, in the frame left by {@link #limbFrame}: leaving the back {@code lean} toward the tip, hooking, swaying. */
	private static void barbPoints(float x, float y, float z, float len, float lean, float curl, float sideLean, float time, int k) {
		PL[0] = x;
		PL[1] = y;
		PL[2] = z;
		float step = len / 3F;
		for (int j = 1; j <= 3; j++) {
			float sway = 0.3F * (float) Math.sin(0.15F * time + 1.7F * k + 0.5F * j) * (j / 3F);
			float ang = lean + curl * (j - 1) + sway;
			float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
			x += (P.x * c + T.x * sn + B.x * sideLean) * step;
			y += (P.y * c + T.y * sn + B.y * sideLean) * step;
			z += (P.z * c + T.z * sn + B.z * sideLean) * step;
			PL[3 * j] = x;
			PL[3 * j + 1] = y;
			PL[3 * j + 2] = z;
		}
	}

	// ------------------------------------------------------------------ the string and the bolt

	/**
	 * The string: a thin red tendril from each nock to the middle (where it is pulled back {@code 6 · pull} px), taut when
	 * drawn, writhing and ringing after a shot, slack (and writhing more) when starving. It is drawn in from the nocks as
	 * the bow forms.
	 */
	private static void string(ArmDraw d, ArmLayer layer, SkinMesh m, float s) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer != red || s <= 0.02F || limbReach < NOCK_U - 0.04F) return;
		swellA = d.swell(limbPhi(NOCK_U), feedLength);
		float midX = nockXAt() + STRING_PULL * pull - 0.5F * slack;
		for (int sd = 1; sd >= -1; sd -= 2) {
			side = sd;
			limbFrame(NOCK_U, sd);
			Q.set(M);
			R.set(midX, 0F, 0F);
			stringReach = s;
			float phi = limbPhi(NOCK_U);
			Loft.tube(m, STRING, 0F, 1F, lod == 0 ? 5 : 3, 4, STRING_RADIUS, red, layer, ST, PART_STRING + (sd > 0 ? 0 : 1), 2F, phi, phi + 6F, UP_Z, 1F);
		}
		// the knot where the arrow is nocked: it moves with the string
		if (lod <= 1 && s >= 0.95F) {
			LOCAL.translation(midX, 0F, 0F);
			m.box(LOCAL, -0.45F, -0.45F, -0.4F, 0.9F, 0.9F, 0.8F, ST.u0(PART_STRING, 5, limbPhi(NOCK_U)), ST.v0(PART_STRING, 5, limbPhi(NOCK_U)), 1.5F, 1F, m.tint());
		}
	}

	/** The flexed nock's x (the same for both limbs). */
	private static float nockXAt() {
		float x = bez(tier, 0, NOCK_U) + 1.5F * flex * NOCK_U * NOCK_U;
		return x;
	}

	/**
	 * The bolt on the string while the bow is drawn: a red core with two black strands winding round it, a barbed black head
	 * with a red point and three fletching vanes, forming from the nock out to its point as the draw begins (an eroding front
	 * with dissolve on, a cut off without), then riding the string back with the draw.
	 */
	private static void bolt(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (boltReach <= 0.02F || lod >= 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		ArmLayer headTip = tier >= 3 && d.glow() ? ArmLayer.GLOW : red;
		float x0 = nockXAt() + STRING_PULL * pull - 0.5F * slack;
		float len = BOLT_LENGTH * boltReach;
		float time = d.time();
		float reachF = boltReach;
		int n = lod == 0 ? 8 : 4;
		// the core and the strands run from the string toward the target (-X)
		if (layer == red) {
			Loft.CurveFn core = (s, out) -> out.set(x0 - len * s, 0F, 0F);
			Loft.tube(m, core, 0F, 1F, lod == 0 ? 4 : 3, 4, s -> FistGeometry.width(0.55F, 4), red, layer, ST, PART_BOLT, 1.5F, PHI_LIMB0, PHI_LIMB0 + 8F, UP_Y, 1F);
		}
		if (layer == black) {
			float spin = 0.2F * time;
			for (int k = 0; k < 2; k++) {
				final int kk = k;
				Loft.CurveFn strand = (s, out) -> {
					float ang = ArmMath.TAU * 2F * s * reachF + kk * ArmMath.PI + spin;
					float r = 0.5F + 0.06F * (float) Math.sin(cur.phase() + 5F * s + 0.3F * cur.time() + kk);
					out.set(x0 - len * s, r * (float) Math.cos(ang), r * (float) Math.sin(ang));
				};
				Loft.AlphaFn alpha = s -> dissolve && boltReach < 1F ? ArmMath.fringe(len * (1F - s) + 0.2F, 1.6F) : 1F;
				Loft.tube(m, strand, 0F, 1F, n, 4, s -> FistGeometry.width(0.5F, 4), black, layer, ST, PART_BOLT + 1 + k, 2F, PHI_LIMB0, PHI_LIMB0 + 8F, UP_Y, alpha);
			}
		}
		if (reachF > 0.97F && lod == 0) {
			// the head: a black collar widening to its barbs, three barbs swept back, a red point; the vanes come after
			float hx = x0 - len;
			if (layer == black) {
				LOCAL.translation(hx + 1.6F, 0F, 0F).rotateY(-ArmMath.PI / 2F);
				m.frustum(LOCAL, 1.6F, 0.5F, 0.5F, 1.5F, 1.5F, ST.u0(PART_BOLT, 3, 10F), ST.v0(PART_BOLT, 3, 10F), 1.5F, 1F, 1F, m.tint());
				for (int k = 0; k < 3; k++) {
					float a = ArmMath.TAU * k / 3F + 0.5F * (float) Math.sin(0.1F * time + k);
					float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
					LOCAL.translation(hx + 0.1F, 0.7F * ca, 0.7F * sa).rotateTowards(0.7F, 0.6F * ca, 0.6F * sa, 0F, 1F, 0F);
					m.frustum(LOCAL, 1.2F, 0.45F, 0.45F, 0.05F, 0.05F, ST.u0(PART_BOLT, 4 + k, 10F), ST.v0(PART_BOLT, 4 + k, 10F), 2F, 1F, 1F, m.tint());
				}
			}
			if (layer == headTip) {
				LOCAL.translation(hx + 0.1F, 0F, 0F).rotateY(-ArmMath.PI / 2F);
				m.frustum(LOCAL, 2.0F, 1.5F, 1.5F, 0.05F, 0.05F, ST.u0(PART_BOLT, 7, 10F), ST.v0(PART_BOLT, 7, 10F), 1.5F, 1F, 1F, m.tint());
			}
		}
		if (lod == 0 && reachF > 0.3F && (layer == black || layer == red)) {
			// the vanes at the string, alternating red and black, fluttering a hair
			for (int k = 0; k < 3; k++) {
				if ((k == 1) != (layer == red)) continue;
				float a = ArmMath.TAU * k / 3F + 0.15F * (float) Math.sin(0.2F * time + k);
				LOCAL.translation(x0 - 0.4F, 0F, 0F).rotateX(a);
				m.box(LOCAL, -2.1F * ArmMath.clamp01((reachF - 0.3F) / 0.4F), 0.3F, -0.05F, 2.1F * ArmMath.clamp01((reachF - 0.3F) / 0.4F), 0.8F, 0.1F,
						ST.u0(PART_BOLT, 8 + k, 10F), ST.v0(PART_BOLT, 8 + k, 10F), 2F, 1F, m.tint());
			}
		}
	}

	/** The arrow rest: two small claws on the riser's front that pinch the bolt's shaft; they close a little as the bow is drawn. */
	private static void claws(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (lod >= 1 || tier < 2 || yHi < RISER_HALF - 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float s = detailed ? pop(ft - 10.4F) : ArmMath.clamp01(limbReach);
		if (s <= 0.02F) return;
		float time = d.time();
		for (int k = 0; k < 2; k++) {
			float sd = k == 0 ? 1F : -1F;
			float close = 0.15F * pull + 0.1F * (float) Math.sin(0.1F * time + 2F * k);
			PL[0] = 0.5F;
			PL[1] = sd * 1.7F;
			PL[2] = 0.2F * sd;
			PL[3] = 1.0F;
			PL[4] = sd * (1.3F - close);
			PL[5] = 0.1F * sd;
			PL[6] = 1.6F;
			PL[7] = sd * (0.75F - close);
			PL[8] = 0.04F * sd;
			PL[9] = 2.0F * s;
			PL[10] = sd * (0.45F - close);
			PL[11] = 0F;
			plCount = 4;
			for (int i = 1; i < 4; i++) {
				PL[3 * i] = ArmMath.lerp(PL[0], PL[3 * i], s);
				PL[3 * i + 1] = ArmMath.lerp(PL[1], PL[3 * i + 1], s);
			}
			tubeW = 1F;
			Loft.tube(m, POLYLINE, 0F, 1F, 4, 4, s2 -> FistGeometry.width(ArmMath.lerp(0.5F, 0.12F, s2), 4), black, layer, ST, PART_CLAW + k, 2F, FistGeometry.PHI_TOOL,
					FistGeometry.PHI_TOOL + 2F, UP_Z, 1F);
		}
	}

	// ------------------------------------------------------------------ beads and drips

	/**
	 * Loose beads: two flicked off the nock by a hit, beads dropping off a retracting front, a melt shedding two a tick at
	 * its erosion front, and a failing arm's three beads orbiting where a limb's tip used to be.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, float rt, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = d.hitAge();
		if (hitAge >= 0F && hitAge < 8F && limbReach > 0.5F) {
			limbFrame(1F, 1);
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float x = M.x + P.x * (0.8F * a + 0.3F * j) + T.x * 0.2F * j + DOWN.x * 0.06F * a * a;
				float y = M.y + P.y * (0.8F * a + 0.3F * j) + T.y * 0.2F * j + DOWN.y * 0.06F * a * a;
				float z = M.z + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
			}
		}
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float a = rt - (2F + 1.4F * k);
				if (a < 0F || a > 3F) continue;
				float u = 1F - ArmMath.sm(1.4F * k / 7F);
				limbFrame(u, k % 2 == 0 ? 1 : -1);
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
				float u = 1F - ArmMath.sm((born - 2F) / 14F);
				limbFrame(u, (idx & 2) == 0 ? 1 : -1);
				float jitter = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, M.x + P.x * jitter * 1.5F + DOWN.x * 0.4F * a * a,
						M.y + P.y * jitter * 1.5F + DOWN.y * 0.4F * a * a, M.z + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing && limbReach > 0.5F) {
			limbFrame(limbReach, 1);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * d.time() + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, M.x + 0.6F * ((float) Math.cos(a) * P.x),
						M.y + 0.6F * ((float) Math.cos(a) * P.y), M.z + 0.6F * (float) Math.sin(a), 0.5F, 16 + k);
			}
		}
	}

	/** Drips off the lower limb's tip, the riser's foot and the upper limb's back, each only while its piece is out. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate();
		if (limbReach >= 0.95F) {
			side = -1;
			limbFrame(0.97F, -1);
			Drips.emit(m, null, M.x, M.y, M.z, DOWN, d.time(), 0 + 4 * (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
			side = 1;
			limbFrame(0.6F, 1);
			Drips.emit(m, null, M.x + P.x * 0.4F, M.y + P.y * 0.4F, M.z, DOWN, d.time(), 1, rate, d.blocksToPx(), ST, PART_DRIP);
		}
		if (yHi >= RISER_HALF - 0.05F) Drips.emit(m, null, riserX(-RISER_HALF), -RISER_HALF, 0F, DOWN, d.time(), 2, rate, d.blocksToPx(), ST, PART_DRIP);
	}

	// ------------------------------------------------------------------ LOD 3

	/** LOD 3: four boxes, the riser, two limbs (each a box along its chord) and a thin one for the string. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (yHi < 0.1F) return;
		if (layer == black) m.box(null, -1.0F, yLo, -0.7F, 1.6F, yHi - yLo, 1.4F, ST.u0(PART_FAR, 0, 10F), ST.v0(PART_FAR, 0, 10F), 1F, 1F, m.tint());
		if (limbReach < 0.05F) return;
		for (int sd = 1; sd >= -1; sd -= 2) {
			side = sd;
			limbFrame(0F, sd);
			float x0 = M.x, y0 = M.y;
			limbFrame(limbReach, sd);
			float dx = M.x - x0, dy = M.y - y0;
			float len = (float) Math.hypot(dx, dy);
			if (len < 0.2F) continue;
			if (sd < 0 && layer == black) {
				LOCAL.translation(x0, y0, 0F).rotateZ((float) Math.atan2(dy, dx) - ArmMath.PI / 2F);
				m.box(LOCAL, -0.6F, 0F, -0.4F, 1.2F, len, 0.8F, ST.u0(PART_FAR, 1, 20F), ST.v0(PART_FAR, 1, 20F), 1F, 1F, m.tint());
			} else if (sd > 0 && layer == black) {
				LOCAL.translation(x0, y0, 0F).rotateZ((float) Math.atan2(dy, dx) - ArmMath.PI / 2F);
				m.box(LOCAL, -0.6F, 0F, -0.4F, 1.2F, len, 0.8F, ST.u0(PART_FAR, 2, 20F), ST.v0(PART_FAR, 2, 20F), 1F, 1F, m.tint());
			}
		}
		if (layer == red && stringReach > 0.5F) {
			float x = nockXAt() + STRING_PULL * pull;
			m.box(null, Math.min(NOCK_X[tier] + 1F, x) - 0.15F, -NOCK_Y[tier], -0.15F, 0.3F, 2F * NOCK_Y[tier], 0.3F, ST.u0(PART_FAR, 3, 20F), ST.v0(PART_FAR, 3, 20F), 2F, 1F,
					m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	/**
	 * The recorded points for the bow: TIP is the arrow rest on the riser's front (where a bolt leaves from, where the
	 * harpoon's line starts), MID the string's middle (it moves with the draw), GRIP the fist.
	 */
	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		// the points are where the drawn bow is, so they take its size in this view too (FP_SCALE in your own hand)
		float k = viewScale(d);
		if (which == ArmTips.TIP) return out.set((riserX(0F) - 0.3F) * k, 0.4F * k, 0F);
		int t = Math.clamp(d.tier(), 1, 3);
		float x = bez(t, 0, NOCK_U) + STRING_PULL * d.bowPull();
		return out.set(x * k, 0F, 0F);
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{3.3F, 12.0F, 1F}, {3.3F, -12.0F, -1F}, {4.4F, 8.0F, 0F}, {4.4F, -8.0F, 0F}, {-1.6F, 0F, 1.1F}, {-1.6F, 0F, -1.1F}, {0.5F, 3.9F, 0F},
				{0.5F, -3.9F, 0F}};
		for (float[] p : pts) out.accept(new Vector3f(p));
	}
}
