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
 * The sickle (section 2.11): one long tapering tendril grown into a crescent, hooked through some 200 degrees, its
 * cutting edge on the inside of the curve. A red band (the hamon) runs along that edge, and the edge itself is toothed
 * with a saw of curved, leaning barbs, no two alike. The outside of the hook is a spine of knuckles like a backbone,
 * with a few long whiskers trailing off its crown, and two thorns at the heel point back toward the hand. Below, a short
 * grip: a red core under one broad ribbon strand and a thin one winding the other way, held by bone rings, ending in a
 * lanyard ring with a cord on real inertia.
 * <p>
 * Grip space G (section 2.0), px, built directly with the hook opening toward G's {@code -X} side: that is the side a
 * held tool's cutting edge faces down on, and it leads vanilla's swing (my look rule 4, 2026-11-15). The crescent
 * bulges toward {@code +X} and curls back over the top, so the point swings down and back toward the hand. No mirror is
 * needed, so world down and the cord's rope nodes are used as given.
 * <p>
 * Level 2 ("Honed") is the baseline. Level 1 ("Raw") is plainer and shorter: five teeth, one wrap strand, no whiskers,
 * no cord, a nearly straight hamon. Level 3 ("Awakened") is longer, with eleven teeth and a third heel thorn, red
 * knuckles, a glowing edge band and two cords.
 * <p>
 * Everything moves: the static crawls up the arm and along the blade, pulses swell through the grip and the hook, the
 * hamon breathes and lifts, the back ripples, teeth and whiskers sway, the rings turn, the cord swings. A swing lays the
 * teeth back; a hit flares the hamon and flicks beads off the point; a broken crop rakes the teeth and bites the point;
 * Reap winds the blade up, bristles the teeth and lashes a whip out of the point. Low biomass thins it, cracks it red,
 * erodes its point and makes it spasm. Every timeline is read from {@link ArmDraw}: forming, the grip and the hook grow
 * out of the fist before any detail, then the wrap, the heel, the hamon, the teeth up the edge, the knuckles, the
 * whiskers, the lanyard and the cord follow, base to point.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class SickleGeometry implements FormGeometry {
	/** The grip's ends in G px, the wrap's radius under the fingers, and the blade's start on the grip. */
	public static final float GRIP_TOP = 5.0F, GRIP_BOTTOM = -3.6F, HILT_R = 1.0F;
	/** Blade stations at full detail. */
	public static final int STATIONS = 26;
	/** Level 2's spine: where the crescent runs (spec: (0,5) to (8,15) to (3.5,20.5) to (-2,17.5), hooking on past it). */
	private static final float[][] SPINE = {{0.0F, 5.0F}, {4.6F, 9.4F}, {8.0F, 15.0F}, {3.5F, 20.5F}, {-2.0F, 17.5F}, {-3.0F, 15.3F}};
	/** Blade length by level: 0.92, 1, 1.07 of level 2's. */
	private static final float[] TIER_SCALE = {0F, 0.92F, 1F, 1.07F};
	private static final int SAMPLES_PER_SEG = 16, SAMPLES = (SPINE.length - 1) * SAMPLES_PER_SEG;
	/** The spine sampled densely: x, y and arc length, per level. */
	private static final float[][] SX = new float[4][SAMPLES + 1], SY = new float[4][SAMPLES + 1], SA = new float[4][SAMPLES + 1];
	private static final float L2_ARC;
	/** The blade's start on the feed coordinate: the grip's top. */
	private static final float BLADE_PHI0 = phiGrip(GRIP_TOP);

	/**
	 * The teeth along the edge (arc position v px on the level-2 blade), each with its own character, by its place in the
	 * level's list: length, lean from the edge's normal toward the point (degrees), hook (radians added a segment, + bends
	 * toward the point), thickness, sideways lean (out of the blade's plane) and whether a small barb forks off it. None
	 * stands on the last stretch, so the point stays clean.
	 */
	private static final float[] TOOTH_V = {4.6F, 7.4F, 9.5F, 12.4F, 14.3F, 17.0F, 19.2F, 21.4F, 23.3F, 10.9F, 25.0F},
			TOOTH_LEN = {1.5F, 2.2F, 1.3F, 2.4F, 1.7F, 2.1F, 1.25F, 1.75F, 1.05F, 1.9F, 0.9F},
			TOOTH_LEAN = {24F, 38F, 52F, 30F, 58F, 41F, 62F, 35F, 55F, 46F, 50F},
			TOOTH_CURL = {0.3F, -0.2F, 0.45F, 0.1F, -0.35F, 0.5F, 0.2F, -0.1F, 0.4F, -0.25F, 0.3F},
			TOOTH_W = {0.55F, 0.7F, 0.5F, 0.8F, 0.6F, 0.65F, 0.5F, 0.6F, 0.45F, 0.6F, 0.4F},
			TOOTH_SIDE = {0.1F, -0.15F, 0.2F, -0.1F, 0.18F, -0.2F, 0.05F, 0.15F, -0.12F, 0.1F, -0.1F};
	private static final boolean[] TOOTH_FORK = {false, true, false, true, false, false, true, false, false, true, false};
	/** Which of those stand at each level (level 1: five; level 2: nine; level 3: all eleven), in arc order. */
	private static final int[] TEETH_L1 = {0, 1, 3, 5, 7}, TEETH_L2 = {0, 1, 2, 3, 4, 5, 6, 7, 8}, TEETH_L3 = {0, 1, 2, 9, 3, 4, 5, 6, 7, 8, 10};
	/** The knuckles on the back: arc position (level-2 px), size and tilt (degrees). */
	private static final float[] KNOB_V = {2.4F, 5.3F, 8.8F, 12.6F, 16.4F, 20.1F, 23.6F}, KNOB_SIZE = {1.0F, 0.85F, 0.95F, 0.8F, 0.7F, 0.6F, 0.5F},
			KNOB_TILT = {14F, -18F, 22F, -10F, 25F, -20F, 12F};
	/** The whiskers off the back's crown: arc position, length (px), sway rate. */
	private static final float[] WHISKER_V = {10.0F, 13.5F, 16.8F}, WHISKER_LEN = {3.1F, 3.9F, 2.6F}, WHISKER_RATE = {0.13F, 0.09F, 0.17F};
	/** FORM: the first tooth sprouts at this tick, then one every {@link #TOOTH_STAGGER} up the edge. */
	private static final float TOOTH_FROM = 7.6F, TOOTH_STAGGER = 0.45F;
	/** Starving cracks along the edge (level-2 px). */
	private static final float[] CRACKS = {7.0F, 8.0F, 14.0F, 14.8F};
	/** Where the lanyard ring and its cord hang from. */
	private static final float RING_Y = -5.3F, RING_R = 1.25F;
	private static final Vector3f CORD_ROOT = new Vector3f(0F, RING_Y - RING_R * 1.1F, 0F);

	// static parts, one per piece (each segment of a piece then takes its own offset)
	private static final int PART_BLADE = 1, PART_HEEL = 2, PART_KNOB = 3, PART_RIBBON = 10, PART_THIN = 11, PART_CORE = 12, PART_RING = 13, PART_LANYARD = 17,
			PART_CORD = 18, PART_TOOTH = 20, PART_FORK = 32, PART_WHISKER = 36, PART_DRIP = 40, PART_LASH = 42, PART_FAR = 43, PART_FIN = 44, PART_RUNG = 46, PART_BIND = 62;

	private static final Static ST = new Static();
	private static final Stations BLADE = new Stations(), CORE = new Stations();
	private static final float[] POLY = new float[14], RECT = new float[8];
	private static final float[] S_LIST = new float[48];
	private static final float[] PL = new float[3 * 12];
	private static final float[] CORD = new float[3 * ArmDraw.ROPE_NODES];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f DOWN = new Vector3f(), M = new Vector3f(), T = new Vector3f(), E = new Vector3f(), Z = new Vector3f(0F, 0F, -1F),
			Q = new Vector3f(), R = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);
	private static final Vector3f CORD_OUT = new Vector3f(0.25F, -0.97F, 0F).normalize();

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, lod, plCount;
	private static float arcLength, vFront, feedLength, droop, sag, yHi, yLo, swellA, ringTurn, ringScale, tubeW, cordW, breath, lash;
	private static int tailSet;
	private static float ringY;

	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * (plCount - 1);
		int i = Math.min(plCount - 2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(PL[3 * i], PL[3 * i + 3], u), ArmMath.lerp(PL[3 * i + 1], PL[3 * i + 4], u), ArmMath.lerp(PL[3 * i + 2], PL[3 * i + 5], u));
	};
	private static final Loft.RadiusFn TOOTH_RADIUS = s -> FistGeometry.width(ArmMath.lerp(1F, 0.3F, s) * tubeW, 4);
	private static final Loft.RadiusFn WHISKER_RADIUS = s -> FistGeometry.width(ArmMath.lerp(0.3F, 0.09F, s) * tubeW, 4);
	/** The fin's rail: thin through the middle, thickening into roots where its ends run into the blade. */
	private static final Loft.RadiusFn FIN_RADIUS = s -> FistGeometry.width((0.24F + 0.32F * (1F - (float) Math.sin(ArmMath.PI * s))) * tubeW, 3);
	/** The sinews binding the blade's heel to the grip. */
	private static final Loft.RadiusFn BIND_RADIUS = s -> FistGeometry.width(ArmMath.lerp(0.42F, 0.26F, s) * tubeW, 3);
	private static final Loft.RadiusFn LASH_RADIUS = s -> FistGeometry.width(ArmMath.lerp(0.5F, 0.08F, s), 4);
	private static final Loft.CurveFn RIBBON = (s, out) -> {
		float y = ArmMath.lerp(yHi, yLo, s);
		float a = ArmMath.TAU * (y - GRIP_BOTTOM) / 2.3F;
		float wr = 1F + 0.05F * (float) Math.sin(cur.phase() + 5F * s + 0.31F * cur.time());
		out.set(0.78F * wr * (float) Math.cos(a), y, 0.66F * wr * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RIBBON_W = s -> FistGeometry.width(0.9F * (1F + 0.4F * swellA), 4);
	private static final Loft.CurveFn THIN = (s, out) -> {
		float y = ArmMath.lerp(yHi, yLo, s);
		float a = -ArmMath.TAU * (y - GRIP_BOTTOM) / 1.9F + ArmMath.PI;
		float wr = 1F + 0.06F * (float) Math.sin(cur.phase() * 0.8F + 7F * s + 0.37F * cur.time());
		out.set(0.9F * wr * (float) Math.cos(a), y, 0.76F * wr * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn THIN_W = s -> FistGeometry.width(0.38F * (1F + 0.3F * swellA), 4);
	private static final Loft.CurveFn BINDING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.0F * (1F + 0.07F * (float) Math.cos(3F * (a - ringTurn))) * ringScale;
		out.set(r * (float) Math.cos(a), ringY + 0.1F * (float) Math.sin(3F * (a - ringTurn)) * ringScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn BINDING_W = s -> FistGeometry.width(0.42F * ringScale, 4);
	private static final Loft.CurveFn LANYARD = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = RING_R * breath * ringScale;
		out.set(r * (float) Math.cos(a), RING_Y + 1.1F * r * (float) Math.sin(a), 0.25F * r * (float) Math.sin(2F * a + 0.03F * cur.time()));
	};
	private static final Loft.RadiusFn LANYARD_W = s -> FistGeometry.width(0.5F * ringScale, 4);
	private static final Loft.CurveFn CORD_CURVE = (s, out) -> cordPoint(s, out);
	private static final Loft.RadiusFn CORD_W = s -> FistGeometry.width(ArmMath.lerp(0.5F, 0.22F, s) * cordW, 4);

	static {
		for (int t = 1; t <= 3; t++) sample(t);
		L2_ARC = SA[2][SAMPLES];
	}

	public SickleGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.SICKLE;
	}

	@Override
	public float feedLength(int tier) {
		return BLADE_PHI0 + SA[Math.clamp(tier, 1, 3)][SAMPLES];
	}

	@Override
	public float guiScale() {
		// level 3's point is the farthest out: this keeps every corner of its rest pose inside 1..15 in both slot axes
		return 0.55F;
	}

	@Override
	public float guiGripPx() {
		return 4.7F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The blade's arc length at {@code tier}, as drawn at rest. */
	public static float bladeArc(int tier) {
		return SA[Math.clamp(tier, 1, 3)][SAMPLES];
	}

	/** The blade's stations as the last emit that drew the blade left them (their count, corners and arcs): for the tests. */
	static Stations lastBlade() {
		return BLADE;
	}

	/** How far the spine's heading turns from the heel to the point at {@code tier}, in degrees (about 200 at level 2). */
	static float turnDegrees(int tier) {
		int t = Math.clamp(tier, 1, 3);
		double a0 = Math.atan2(SY[t][2] - SY[t][0], SX[t][2] - SX[t][0]);
		double a1 = Math.atan2(SY[t][SAMPLES] - SY[t][SAMPLES - 2], SX[t][SAMPLES] - SX[t][SAMPLES - 2]);
		double turn = Math.toDegrees(a1 - a0);
		return (float) (turn < 0 ? turn + 360 : turn);
	}

	/** The spine's point at fraction {@code s} of its arc at {@code tier}, into {@code out} (x, y): for the tests. */
	static void spineAt(int tier, float s, float[] out) {
		int t = Math.clamp(tier, 1, 3);
		int save = SickleGeometry.tier;
		SickleGeometry.tier = t;
		arcLength = bladeArc(t);
		droop = 0F;
		sag = 0F;
		frameAt(s * arcLength);
		out[0] = M.x;
		out[1] = M.y;
		SickleGeometry.tier = save;
	}

	/** The feed coordinate on the grip at height {@code y}: up from the fist to the blade, and down to the lanyard. */
	static float phiGrip(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	// ------------------------------------------------------------------ the spine

	/** Samples level {@code t}'s crescent: a Catmull-Rom through the control points (the ends reflected), scaled about the blade's base. */
	private static void sample(int t) {
		float k = TIER_SCALE[t];
		int n = SPINE.length;
		float px = 0F, py = 0F;
		for (int i = 0; i <= SAMPLES; i++) {
			float u = Math.min(n - 1F, i / (float) SAMPLES_PER_SEG);
			int seg = Math.min(n - 2, (int) u);
			float f = u - seg;
			float[] p1 = SPINE[seg], p2 = SPINE[seg + 1];
			float[] p0 = seg == 0 ? new float[] {2F * p1[0] - p2[0], 2F * p1[1] - p2[1]} : SPINE[seg - 1];
			float[] p3 = seg + 2 >= n ? new float[] {2F * p2[0] - p1[0], 2F * p2[1] - p1[1]} : SPINE[seg + 2];
			float x = catmull(p0[0], p1[0], p2[0], p3[0], f), y = catmull(p0[1], p1[1], p2[1], p3[1], f);
			x = SPINE[0][0] + (x - SPINE[0][0]) * k;
			y = SPINE[0][1] + (y - SPINE[0][1]) * k;
			SX[t][i] = x;
			SY[t][i] = y;
			SA[t][i] = i == 0 ? 0F : SA[t][i - 1] + (float) Math.hypot(x - px, y - py);
			px = x;
			py = y;
		}
	}

	private static float catmull(float p0, float p1, float p2, float p3, float u) {
		float u2 = u * u, u3 = u2 * u;
		return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
	}

	/**
	 * The spine's point and frame at arc {@code v} into M, T, E (toward the inside of the hook, where the edge is) and
	 * Z (the blade's flat); with a retract's sag at the point and melting's droop toward world down.
	 */
	private static void frameAt(float v) {
		float[] sx = SX[tier], sy = SY[tier], sa = SA[tier];
		v = ArmMath.clamp(v, 0F, sa[SAMPLES]);
		int i = 0;
		while (i < SAMPLES - 1 && sa[i + 1] < v) i++;
		float span = sa[i + 1] - sa[i];
		float f = span > 1e-6F ? (v - sa[i]) / span : 0F;
		float x = ArmMath.lerp(sx[i], sx[i + 1], f), y = ArmMath.lerp(sy[i], sy[i + 1], f);
		int a = Math.max(0, i - 1), b = Math.min(SAMPLES, i + 2);
		float tx = sx[b] - sx[a], ty = sy[b] - sy[a];
		float tl = (float) Math.hypot(tx, ty);
		if (tl < 1e-6F) {
			tx = 0F;
			ty = 1F;
		} else {
			tx /= tl;
			ty /= tl;
		}
		T.set(tx, ty, 0F);
		E.set(-ty, tx, 0F);
		M.set(x, y, 0F);
		float d = v / Math.max(1e-3F, arcLength);
		if (sag > 0F) M.y -= sag * d * d;
		if (droop > 0F) M.fma(droop * d * d, DOWN);
	}

	/** The blade's half-width at {@code s} (0..1 along it): 2.6 px at the heel, bellying a little, to a point. */
	static float mihaba(float s) {
		return 2.6F * (float) Math.pow(Math.max(0F, 1F - s), 0.85F) * (1F + 0.28F * (float) Math.sin(ArmMath.PI * s));
	}

	/** The blade's thickness at {@code s}: 0.72 at the heel to 0.14 at the point. */
	static float kasane(float s) {
		return ArmMath.lerp(0.72F, 0.14F, (float) Math.pow(ArmMath.clamp01(s), 0.8F));
	}

	/**
	 * Where the hamon line sits between the ridge (0) and the edge (1) at arc {@code v}, time {@code t}, for a pulse of
	 * strength {@code amp} there: it wavers along the edge, breathes slowly and lifts (the red band widening) as a pulse
	 * passes; level 1's is nearly straight, level 3's has a finer ripple. Clamped to 0.25..0.75.
	 */
	public static float hamon(int tier, float v, float t, float amp) {
		float a1 = tier <= 1 ? 0.07F : 0.14F, a2 = tier <= 1 ? 0.03F : 0.06F;
		float f = 0.50F + a1 * (float) Math.sin(0.7F * v + 1.3F) + a2 * (float) Math.sin(2.1F * v + 0.4F) + 0.04F * (float) Math.sin(0.05F * t + 0.4F * v)
				- 0.18F * amp;
		if (tier >= 3) f += 0.05F * (float) Math.sin(3.7F * v);
		return ArmMath.clamp(f, 0.25F, 0.75F);
	}

	/** FORM's pop for something appearing {@code x} ticks after its moment: 0 to 1.25 to 1.0 over 2 ticks. */
	private static float pop(float x) {
		if (x <= 0F) return 0F;
		if (x < 1F) return 1.25F * ArmMath.sm(x);
		if (x < 2F) return ArmMath.lerp(1.25F, 1F, ArmMath.sm(x - 1F));
		return 1F;
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
		// first person: the arm itself (mirrored for the left hand), then the hand's shared pieces
		if (d.firstPerson()) {
			FistGeometry.mirror(m, d.leftHand());
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, HILT_R, feedLength);
			FistGeometry.mirror(m, false);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm();
		m.transform(sp != 1F ? LOCAL.scaling(sp) : null);
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float reach = d.reach();
		boolean detailed = d.detailedForm();
		float ft = d.formTick();
		float rt = d.retractTick();
		droop = d.droop();
		sag = rt >= 0F ? 1.2F * ArmMath.sm(rt / 3F) : 0F;
		arcLength = bladeArc(tier);
		// the fronts: the blade's (from the grip's top), and the grip's up and down from the fist
		float total = GRIP_TOP + arcLength;
		float up = reach * total;
		vFront = detailed ? arcLength * ArmMath.sm((ft - 3F) / 5.5F) : ArmMath.clamp(up - GRIP_TOP, 0F, arcLength);
		boolean failing = d.lowStage() == ArmDraw.LOW_FAILING;
		if (failing) vFront = Math.min(vFront, 0.92F * arcLength);
		// a broken crop bites the point: it recoils 0.8 px and comes back
		float breakAge = d.breakAge();
		if (reach >= 1F && breakAge < 4F) vFront -= 0.8F * (float) Math.sin(ArmMath.PI * breakAge / 4F);
		float down = detailed ? -GRIP_BOTTOM : reach * -GRIP_BOTTOM;
		if (detailed) {
			// the grip rises out of the fist both ways first (its wrap winding with it); the crescent comes out of its top after
			yHi = GRIP_TOP * ArmMath.sm((ft - 1F) / 3F);
			yLo = GRIP_BOTTOM * ArmMath.sm((ft - 1.5F) / 3.5F);
		} else {
			yHi = Math.min(GRIP_TOP, up);
			yLo = Math.max(GRIP_BOTTOM, -down);
			if (rt >= 0F) yLo = Math.max(yLo, ArmMath.lerp(GRIP_BOTTOM, GRIP_TOP, ArmMath.sm((rt - 3F) / 5F)));
		}
		// the lanyard ring hangs off the grip's end (3.6 px down): it grows on as the grip reaches it (it used to wait for 3.5..4.3 px,
		// so a formed sickle drew it at a few percent: specks under the grip)
		float ring = detailed ? pop(ft - 6F) : ArmMath.sm((down - 2.8F) / 0.8F);
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m, ring);
			return;
		}
		lash = d.specialWeight(ArmProtocol.FX_REAP);
		blade(d, layer, m, detailed, ft, rt, failing);
		if (lod <= 1) heel(d, layer, m, detailed, ft);
		if (lod <= 2) teeth(d, layer, m, detailed, ft);
		knobs(d, layer, m, detailed, ft);
		if (lod == 0) fin(d, tier, layer, m, detailed, ft);
		if (lod == 0) binding(d, layer, m, detailed, ft);
		whiskers(d, layer, m, detailed, ft);
		grip(d, layer, m, ring, ft, rt);
		lanyard(d, layer, m, detailed, ft, ring);
		lashThread(d, layer, m);
		beads(d, layer, m, rt, failing);
		if (lod == 0 && d.dripsNow() && (!detailed || ft >= 13F)) drips(d, layer, m, ring);
	}

	// ------------------------------------------------------------------ the blade

	/** The crescent: lofted section by section, cut at the growth front, its hamon, ripple, cracks and fringe. */
	private static void blade(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float rt, boolean failing) {
		if (vFront <= 0.05F) return;
		boolean lod2 = lod >= 2;
		boolean glowEdge = tier >= 3 && d.glow();
		ArmLayer red = d.paint(ArmLayer.RED), black = d.paint(ArmLayer.BLACK);
		ArmLayer edge = glowEdge ? ArmLayer.GLOW : red;
		if (layer != red && layer != black && layer != edge && !Loft.pulseGlowPass(layer)) return;
		// how far the red band has been drawn (FORM reveals it from the heel; a retract takes it back from the point)
		float vRed = detailed ? arcLength * ArmMath.sm((ft - 7F) / 2F) : rt >= 0F ? arcLength * (1F - ArmMath.sm(rt / 3F)) : arcLength;
		int stage = d.lowStage();
		float scale = arcLength / L2_ARC;
		float time = d.time();
		float hit = d.hitFlare();
		float reap = lash;
		boolean growing = vFront < arcLength - 0.02F || failing;
		float fringeW = failing && d.reach() >= 1F ? 1.7F : d.fringe();
		boolean fringe = d.dissolve() && growing;
		float glowBright = ArmMath.clamp01((detailed ? 0.55F * ArmMath.sm((ft - 9F) / 3F) : 0.55F) + 0.45F * Math.max(hit, reap));
		int fixed = lod2 ? 7 : lod == 1 ? 15 : STATIONS;
		int n = 0;
		for (int i = 0; i < fixed; i++) {
			float v = arcLength * i / (fixed - 1);
			if (v < vFront - 0.02F) S_LIST[n++] = v;
		}
		boolean truncated = vFront < arcLength - 0.02F;
		S_LIST[n++] = truncated ? vFront : arcLength;
		if (n < 2) return;
		int sides = lod2 ? 5 : 7;
		Stations out = BLADE.reset(sides).feed(BLADE_PHI0);
		boolean glowPass = layer == ArmLayer.GLOW;
		out.color(glowPass ? FistGeometry.grey(glowBright) : -1);
		for (int i = 0; i < n; i++) {
			float v = S_LIST[i];
			float s = v / arcLength;
			frameAt(v);
			float amp = d.swell(BLADE_PHI0 + v, feedLength);
			float h = mihaba(s);
			float th = kasane(s) + 0.12F * amp;
			if (stage >= ArmDraw.LOW_THINNING) th *= 0.85F;
			if (d.frontBulb() && vFront < arcLength - 0.02F) th *= 1F + 0.3F * Math.max(0F, 1F - (vFront - v) / 3F);
			float f = hamon(tier, v, time, amp);
			if (stage >= ArmDraw.LOW_THINNING) f = Math.min(0.85F, f + 0.12F);
			f = ArmMath.lerp(f, 0.15F, hit);
			f = ArmMath.lerp(f, 0.10F, reap);
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
				// the back's two points, the ridge, the hamon line on each flat, the edge: in (n, b) = (toward the edge, the flat)
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

	// ------------------------------------------------------------------ the teeth, knuckles, whiskers, heel

	/** The teeth standing at this level, in arc order. */
	private static int[] teethOf() {
		return tier <= 1 ? TEETH_L1 : tier >= 3 ? TEETH_L3 : TEETH_L2;
	}

	/**
	 * The toothed edge: curved barbs leaning toward the point, each its own length, lean, hook, thickness and sideways
	 * lean, a few forked. Forming, they sprout one by one up the edge once the red band is drawn; a swing lays them back,
	 * a broken crop rakes them, Reap bristles them straight out, a surge lengthens them, thinning shortens them.
	 */
	private static void teeth(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		int[] set = teethOf();
		float scale = arcLength / L2_ARC;
		float time = d.time();
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		float rake = d.breakAge() < 3F ? (float) Math.sin(ArmMath.PI * d.breakAge() / 3F) : 0F;
		float thin = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int k = 0; k < set.length; k++) {
			int idx = set[k];
			if (lod == 1 && TOOTH_LEN[idx] < 1.2F) continue;
			if (lod == 2 && (k & 1) == 1) continue;
			float v = TOOTH_V[idx] * scale;
			if (v > vFront - 0.5F) continue;
			float grow = detailed ? pop(ft - TOOTH_FROM - TOOTH_STAGGER * k) : ArmMath.sm((vFront - v - 0.5F) / 1.2F);
			if (grow <= 0.02F) continue;
			frameAt(v);
			float s = v / arcLength;
			float len = (TOOTH_LEN[idx] + 0.25F * (float) Math.sin(0.09F * time + 1.9F * k) + 0.8F * d.surge() * TOOTH_LEN[idx]) * (1F + 0.3F * rake) * thin;
			len = ArmMath.lerp(len, 2.4F * TOOTH_LEN[idx], lash) * grow;
			float lean = (float) Math.toRadians(TOOTH_LEAN[idx]) - (float) Math.toRadians(26) * swing + (float) Math.toRadians(18) * rake;
			lean = ArmMath.lerp(lean, ArmMath.PI / 2F * 0.85F, lash);
			float h = mihaba(s) * 0.92F;
			Q.set(M).fma(h, E);
			if (lod >= 2) {
				// a box along the tooth's first heading
				float c = (float) Math.cos(lean), sn = (float) Math.sin(lean);
				LOCAL.translation(Q).rotateZ((float) Math.atan2(E.y * c + T.y * sn, E.x * c + T.x * sn) - ArmMath.PI / 2F);
				m.box(LOCAL, -0.3F, 0F, -0.2F, 0.6F, Math.max(0.1F, len), 0.4F, ST.u0(PART_TOOTH, k, BLADE_PHI0 + v), ST.v0(PART_TOOTH, k, BLADE_PHI0 + v), 1F, 1F,
						m.tint());
				continue;
			}
			toothPoints(Q.x, Q.y, Q.z, len, lean, TOOTH_CURL[idx], TOOTH_SIDE[idx] * (1F - lash), time, k, swing, lash);
			float bx = PL[3], by = PL[4], bz = PL[5];
			tubeW = TOOTH_W[idx] * (tier >= 3 ? 1.1F : 1F);
			plCount = 4;
			Loft.tube(m, POLYLINE, 0F, 1F, lod == 0 ? 4 : 3, 4, TOOTH_RADIUS, black, layer, ST, PART_TOOTH + k, 2F, BLADE_PHI0 + v, BLADE_PHI0 + v + 2F, UP_Z, 1F);
			if (TOOTH_FORK[idx] && lod == 0) {
				// a small barb off the first third, swept further forward and the other way sideways
				toothPoints(bx, by, bz, 0.5F * len, lean + 0.7F, -TOOTH_CURL[idx], -TOOTH_SIDE[idx] * (1F - lash), time, k + 5, swing, lash);
				PL[6] = PL[9];
				PL[7] = PL[10];
				PL[8] = PL[11];
				plCount = 2;
				tubeW = 0.6F * TOOTH_W[idx];
				Loft.tube(m, POLYLINE, 0F, 1F, 2, 4, TOOTH_RADIUS, black, layer, ST, PART_FORK + k, 2F, BLADE_PHI0 + v + 0.7F, BLADE_PHI0 + v + 1.4F, UP_Z, 1F);
			}
		}
		tubeW = 1F;
	}

	/**
	 * A tooth's four points into PL from ({@code x, y, z}), in the frame left by {@link #frameAt}: leaving the edge
	 * {@code lean} from its normal toward the point, hooking {@code curl} more each segment, swaying, leaning {@code side}
	 * out of the blade's flat. A swing lays it back a little more up its length.
	 */
	private static void toothPoints(float x, float y, float z, float len, float lean, float curl, float side, float time, int k, float swing, float reap) {
		PL[0] = x;
		PL[1] = y;
		PL[2] = z;
		float step = len / 3F;
		for (int j = 1; j <= 3; j++) {
			float sway = 0.3F * (float) Math.sin(0.15F * time + 1.7F * k + 0.5F * j) * (j / 3F) * (1F - reap);
			float ang = (lean + curl * (j - 1)) * (1F - 0.6F * reap) + sway - 0.25F * swing * j;
			float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
			x += (E.x * c + T.x * sn + Z.x * side) * step;
			y += (E.y * c + T.y * sn + Z.y * side) * step;
			z += (E.z * c + T.z * sn + Z.z * side) * step;
			PL[3 * j] = x;
			PL[3 * j + 1] = y;
			PL[3 * j + 2] = z;
		}
	}

	/**
	 * The backbone: knuckles along the outside of the hook, a box each, tilted this way and that and shrinking toward the
	 * point; they pop on base to point as the wrap finishes forming, and swell with the pulses. Level 3's are red.
	 */
	private static void knobs(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (lod >= 2 || (tier >= 2 && lod == 0)) return; // from level 2 the fin's rungs are the backbone
		ArmLayer knobLayer = tier >= 3 ? d.paint(ArmLayer.RED) : d.paint(ArmLayer.BLACK);
		if (layer != knobLayer) return;
		int count = tier <= 1 ? 3 : KNOB_V.length;
		float scale = arcLength / L2_ARC;
		float time = d.time();
		for (int k = 0; k < count; k++) {
			if (lod == 1 && (k & 1) == 1) continue;
			int idx = tier <= 1 ? k * 2 + 1 : k;
			float v = KNOB_V[idx] * scale;
			if (v > vFront - 0.4F) continue;
			float grow = detailed ? pop(ft - 6.2F - 0.45F * k) : ArmMath.sm((vFront - v - 0.4F) / 1.0F);
			if (grow <= 0.02F) continue;
			frameAt(v);
			float amp = d.swell(BLADE_PHI0 + v, feedLength);
			float size = KNOB_SIZE[idx] * (1F + 0.35F * amp) * grow * (d.lowStage() >= ArmDraw.LOW_THINNING ? 0.85F : 1F);
			float tilt = (float) Math.toRadians(KNOB_TILT[idx]) + 0.08F * (float) Math.sin(0.12F * time + 2.1F * k);
			float ang = (float) Math.atan2(T.y, T.x) + tilt;
			float out = -0.3F * size;
			LOCAL.translation(M.x + E.x * out, M.y + E.y * out, 0F).rotateZ(ang);
			float w = 0.95F * size, hh = 0.62F * size, dd = 0.85F * size;
			m.box(LOCAL, -w / 2F, -hh / 2F, -dd / 2F, w, hh, dd, ST.u0(PART_KNOB, k, BLADE_PHI0 + v), ST.v0(PART_KNOB, k, BLADE_PHI0 + v), 1.5F, 1F, m.tint());
		}
	}

	/** The back fin by level (none at level 1): where along the back it runs (level-2 px), and its rungs. */
	private static final float FIN_FROM = 3.0F, FIN_TO = 19.5F;
	private static final float[] RUNG_AT = {0.12F, 0.31F, 0.47F, 0.66F, 0.84F, 0.22F, 0.57F}, RUNG_W = {0.55F, 0.45F, 0.6F, 0.4F, 0.5F, 0.35F, 0.42F};

	/**
	 * A skeletal fin along the crescent's back (2026-11-15: the sickle looked bland): a thin rail standing off
	 * the back, furthest out through the middle of the curve, held by ribs, each its own width, every other one red.
	 * Everything is joined: the rail's ends run back into the blade with thicker roots, and each rib is rooted inside the
	 * blade and reaches exactly into the rail where it is (my call: the ends and the ribs didn't look attached). Level 2
	 * has five ribs, level 3 seven. Forming, it grows out of the back after the knuckles; it breathes and ripples with the
	 * back.
	 */
	private static void fin(ArmDraw d, int unusedTier, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (tier < 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		float scale = arcLength / L2_ARC;
		float v0 = FIN_FROM * scale, v1 = Math.min(FIN_TO * scale, vFront - 0.6F);
		if (v1 - v0 < 1F) return;
		float grow = detailed ? ArmMath.sm((ft - 8.5F) / 2.5F) : ArmMath.sm((vFront - v1 - 0.2F) / 1.5F + 1F);
		if (grow <= 0.02F) return;
		float time = d.time();
		float thin = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.8F : 1F;
		float depth = 2.1F * grow * thin;
		if (layer == black) {
			int n = 8;
			for (int i = 0; i < n; i++) {
				float f = i / (n - 1F);
				finRail(ArmMath.lerp(v0, v1, f), f, depth, time, Q);
				PL[3 * i] = Q.x;
				PL[3 * i + 1] = Q.y;
				PL[3 * i + 2] = Q.z;
			}
			plCount = n;
			tubeW = thin;
			Loft.tube(m, POLYLINE, 0F, 1F, n, 3, FIN_RADIUS, black, layer, ST, PART_FIN, 2F, BLADE_PHI0 + v0, BLADE_PHI0 + v1, UP_Z, 1F);
		}
		// the ribs: from inside the blade straight into the rail where it is
		int ribs = tier >= 3 ? 7 : 5;
		for (int k = 0; k < ribs; k++) {
			if (layer != ((k & 1) == 1 ? red : black)) continue;
			float f = RUNG_AT[k];
			float v = ArmMath.lerp(v0, v1, f);
			finRail(v, f, depth, time, R);
			frameAt(v);
			float rx = M.x + E.x * FIN_ROOT, ry = M.y + E.y * FIN_ROOT, rz = M.z;
			float dx = R.x - rx, dy = R.y - ry, dz = R.z - rz;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < FIN_ROOT + 0.4F) continue;
			LOCAL.translation(rx, ry, rz).rotateTowards(dx, dy, dz, 0F, 0F, 1F);
			float w = RUNG_W[k] * thin;
			// a little past the rail's centre, so it ends inside the rail
			m.frustum(LOCAL, len + 0.15F, w, w * 0.8F, w * 0.5F, w * 0.45F, ST.u0(PART_RUNG, k, BLADE_PHI0 + v), ST.v0(PART_RUNG, k, BLADE_PHI0 + v), 2F, 1F, 1F,
					m.tint());
		}
	}

	/**
	 * The fin's rail at arc {@code v} ({@code f} 0..1 along the fin) into {@code out}: rooted {@link #FIN_ROOT} px inside the
	 * blade at both ends, out to {@code depth} px off the back through the middle, rippling a little.
	 */
	private static void finRail(float v, float f, float depth, float time, Vector3f out) {
		frameAt(v);
		float rise = (float) Math.sin(ArmMath.PI * f);
		float off = -FIN_ROOT + (FIN_ROOT + depth) * rise * (1F + 0.06F * (float) Math.sin(0.08F * time + 3F * f));
		out.set(M.x - E.x * off, M.y - E.y * off, M.z + 0.1F * rise * (float) Math.sin(0.11F * time + 4F * f));
	}

	/** How deep inside the blade (px, toward the edge) the fin's rail ends and ribs are rooted. */
	private static final float FIN_ROOT = 0.45F;

	/**
	 * Binds the blade to the grip (2026-11-15: it didn't look attached): a knotted collar where the grip ends,
	 * and two sinews that leave the grip below it and wind up round the blade's heel, crossing each other. Forming, they
	 * wind on once the crescent is out of the grip.
	 */
	private static void binding(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		float grow = detailed ? ArmMath.sm((ft - 5F) / 2.5F) : ArmMath.sm((vFront - 1F) / 2F);
		if (grow <= 0.02F || yHi < GRIP_TOP - 0.3F) return;
		float time = d.time();
		if (layer == red) {
			// the collar's knots, turning slowly
			for (int k = 0; k < 3; k++) {
				float a = ArmMath.TAU * k / 3F + 0.015F * time + 0.4F;
				LOCAL.translation(1.05F * (float) Math.cos(a), GRIP_TOP - 0.25F, 1.05F * (float) Math.sin(a)).rotateY(-a).scale(grow);
				m.box(LOCAL, -0.42F, -0.35F, -0.42F, 0.84F, 0.7F, 0.84F, ST.u0(PART_BIND, k, BLADE_PHI0), ST.v0(PART_BIND, k, BLADE_PHI0), 1.5F, 1F, m.tint());
			}
			return;
		}
		float scale = arcLength / L2_ARC;
		float vEnd = Math.min(3.6F * scale, vFront - 0.3F) * grow;
		if (vEnd < 0.4F) return;
		for (int k = 0; k < 2; k++) {
			int n = 7;
			// from the grip, a little below the collar, round to the blade
			float a0 = k * ArmMath.PI + 0.6F;
			PL[0] = 0.95F * (float) Math.cos(a0);
			PL[1] = GRIP_TOP - 1.4F;
			PL[2] = 0.95F * (float) Math.sin(a0);
			for (int i = 1; i < n; i++) {
				float f = i / (n - 1F);
				float v = vEnd * f;
				frameAt(v);
				float s = v / Math.max(1e-3F, arcLength);
				float h = mihaba(s), th = kasane(s);
				// round the blade's section: an ellipse round its middle, the turn crossing the other sinew's
				float theta = a0 + (k == 0 ? 1F : -1F) * ArmMath.TAU * 1.25F * f + 0.03F * (float) Math.sin(0.1F * time + k);
				float ea = 0.5F * h + 0.3F, eb = 0.5F * th + 0.3F;
				float c = (float) Math.cos(theta), sn = (float) Math.sin(theta);
				PL[3 * i] = M.x + E.x * (0.5F * h + ea * c);
				PL[3 * i + 1] = M.y + E.y * (0.5F * h + ea * c);
				PL[3 * i + 2] = M.z + eb * sn;
			}
			plCount = n;
			tubeW = 1F;
			Loft.tube(m, POLYLINE, 0F, 1F, n, 3, BIND_RADIUS, black, layer, ST, PART_BIND + 4 + k, 2F, phiGrip(GRIP_TOP - 1.4F), BLADE_PHI0 + vEnd, UP_Z, 1F);
		}
	}

	/** Long thin whiskers off the back's crown, each swaying at its own rate and streaming behind a swing (level 2 and up). */
	private static void whiskers(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (lod >= 1 || tier < 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float scale = arcLength / L2_ARC;
		float time = d.time();
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		float thin = d.lowStage() >= ArmDraw.LOW_STARVING ? 0.5F : 1F;
		for (int k = 0; k < (tier >= 3 ? 2 : 1); k++) {
			float v = WHISKER_V[k] * scale;
			if (v > vFront - 0.5F) continue;
			float grow = detailed ? ArmMath.sm((ft - 10F - 0.5F * k) / 1.5F) : ArmMath.sm((vFront - v - 0.5F) / 1.5F);
			if (grow <= 0.02F) continue;
			frameAt(v);
			float len = WHISKER_LEN[k] * thin * grow * (1F + d.surge() * 0.5F);
			Q.set(M).fma(-0.2F, E);
			PL[0] = Q.x;
			PL[1] = Q.y;
			PL[2] = Q.z;
			float x = Q.x, y = Q.y, z = Q.z;
			float step = len / 4F;
			for (int j = 1; j <= 4; j++) {
				// out from the back, drifting toward the point, the free end swaying widely and trailing behind a swing
				float sway = 0.55F * (float) Math.sin(WHISKER_RATE[k] * time + 2.3F * k + 0.8F * j) * (j / 4F);
				float ang = 0.35F + sway + 0.4F * swing * j / 4F;
				float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
				float side = 0.25F * (float) Math.sin(0.11F * time + 1.3F * k + j);
				x += (-E.x * c + T.x * sn * 0.6F + Z.x * side) * step;
				y += (-E.y * c + T.y * sn * 0.6F + Z.y * side) * step;
				z += (-E.z * c + T.z * sn * 0.6F + Z.z * side) * step;
				PL[3 * j] = x;
				PL[3 * j + 1] = y;
				PL[3 * j + 2] = z;
			}
			plCount = 5;
			tubeW = 1F;
			Loft.tube(m, POLYLINE, 0F, 1F, 5, 4, WHISKER_RADIUS, black, layer, ST, PART_WHISKER + k, 2F, BLADE_PHI0 + v, BLADE_PHI0 + v + 3F, UP_Z, 1F);
		}
	}

	/** The heel: two thorns (a third at level 3) at the blade's base, pointing back toward the hand, one on each side. */
	private static void heel(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		int count = tier >= 3 ? 3 : 2;
		float vAt = 0.9F;
		if (vAt > vFront) return;
		frameAt(vAt);
		for (int k = 0; k < count; k++) {
			float grow = detailed ? pop(ft - 4F - 0.7F * k) : ArmMath.sm((vFront - vAt) / 1.0F);
			if (grow <= 0.02F) continue;
			// thorn k leans from the blade's normal back down the grip: 120, 150, 100 degrees from +T; the sides alternate
			float side = k == 0 ? 1F : k == 1 ? -1F : 1F;
			float angle = (float) Math.toRadians(k == 0 ? 130 : k == 1 ? 155 : 105);
			float len = (k == 0 ? 1.8F : k == 1 ? 1.35F : 1.0F) * grow * (1F + 0.15F * (float) Math.sin(0.1F * d.time() + k));
			float dx = (T.x * (float) Math.cos(angle) + E.x * (float) Math.sin(angle) * side);
			float dy = (T.y * (float) Math.cos(angle) + E.y * (float) Math.sin(angle) * side);
			float base = 0.6F * (1F + 0.2F * d.swell(BLADE_PHI0 + vAt, feedLength));
			Q.set(M).fma(side * 0.6F, E);
			LOCAL.translation(Q).rotateTowards(dx, dy, 0F, 0F, 0F, 1F);
			m.frustum(LOCAL, len, base, base * 0.6F, 0.06F, 0.06F, ST.u0(PART_HEEL, k, BLADE_PHI0), ST.v0(PART_HEEL, k, BLADE_PHI0), 2F, 1F, 1F, m.tint());
		}
	}

	/** The point's lash: while Reap runs the point draws out into a thread that writhes (the world whip takes over from it). */
	private static void lashThread(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (lash <= 0.02F || lod >= 1 || vFront < arcLength - 0.1F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		frameAt(arcLength);
		float len = 3.2F * lash;
		PL[0] = M.x;
		PL[1] = M.y;
		PL[2] = M.z;
		float time = d.time();
		for (int j = 1; j <= 4; j++) {
			float u = j / 4F;
			float wave = 0.5F * (float) Math.sin(1.6F * time - 3F * u + d.phase()) * u;
			PL[3 * j] = M.x + (T.x * len * u + E.x * wave) + 0F;
			PL[3 * j + 1] = M.y + (T.y * len * u + E.y * wave);
			PL[3 * j + 2] = M.z + Z.z * wave * 0.5F;
		}
		plCount = 5;
		Loft.tube(m, POLYLINE, 0F, 1F, 5, 4, LASH_RADIUS, black, layer, ST, PART_LASH, 2F, BLADE_PHI0 + arcLength, BLADE_PHI0 + arcLength + 3F, UP_Z, 1F);
	}

	// ------------------------------------------------------------------ the grip

	/**
	 * The grip: a red core, a broad ribbon strand winding up it and a thin one winding the other way (level 2 and up),
	 * and bone rings that turn against each other. The wrap spirals down from the top as it forms.
	 */
	private static void grip(ArmDraw d, ArmLayer layer, SkinMesh m, float ring, float ft, float rt) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (lod >= 2) {
			// LOD 2 (section 2.10): the grip is one box
			if (layer == black && yHi - yLo > 0.05F) {
				m.box(null, -0.8F, yLo, -0.68F, 1.6F, yHi - yLo, 1.36F, ST.u0(PART_RIBBON, 0, 10F), ST.v0(PART_RIBBON, 0, 10F), 1F, 1F, m.tint());
			}
			return;
		}
		if (yHi - yLo <= 0.05F) return;
		if (layer == red) {
			Stations out = CORE.reset(4).feed(0F);
			float hw = 0.66F, hd = 0.56F;
			RECT[0] = hw; RECT[1] = -hd;
			RECT[2] = hw; RECT[3] = hd;
			RECT[4] = -hw; RECT[5] = hd;
			RECT[6] = -hw; RECT[7] = -hd;
			out.add(0F, yLo, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 0F, RECT, 1F);
			out.add(0F, yHi, 0F, 0F, 1F, 0F, 1F, 0F, 0F, yHi - yLo, RECT, 1F);
			Loft.sweep(m, out, layer, (seg, face) -> red, ST, PART_CORE, 1F, false, false);
		}
		if (layer != black) return;
		int stations = lod == 0 ? 22 : 10;
		int n = Math.max(2, Math.round(stations * (yHi - yLo) / (GRIP_TOP - GRIP_BOTTOM)));
		swellA = d.swell(phiGrip(0.5F * (yHi + yLo)), feedLength);
		Loft.tube(m, RIBBON, 0F, 1F, n, 4, RIBBON_W, black, layer, ST, PART_RIBBON, 2F, phiGrip(yHi), phiGrip(yLo), UP_Y, 1F);
		if (tier >= 2 && lod == 0) {
			Loft.tube(m, THIN, 0F, 1F, Math.max(2, n + 2), 4, THIN_W, black, layer, ST, PART_THIN, 2F, phiGrip(yHi), phiGrip(yLo), UP_Y, 1F);
		}
		int rings = tier <= 1 ? 1 : 3;
		for (int k = 0; k < rings; k++) {
			float y = tier <= 1 ? 1.0F : k == 0 ? -3.0F : k == 1 ? 0.9F : 4.3F;
			if (y > yHi + 0.05F || y < yLo - 0.05F) continue;
			ringScale = detailed(d) ? pop(ft - (5F + k * 0.6F)) : ArmMath.sm((yHi - y) / 0.8F);
			if (rt >= 0F) ringScale = Math.min(ringScale, 1F - ArmMath.sm((rt - 3F - 1.2F * k) / 1.5F));
			if (ringScale <= 0.02F) continue;
			ringY = y;
			ringTurn = ((k & 1) == 0 ? 0.02F : -0.02F) * d.time() + k;
			Loft.closedTube(m, BINDING, lod == 0 ? 6 : 5, 4, BINDING_W, black, layer, ST, PART_RING + k, 2F, phiGrip(y), UP_Y, 1F);
		}
	}

	private static boolean detailed(ArmDraw d) {
		return d.detailedForm();
	}

	/**
	 * The lanyard: a ring of tendril at the grip's butt with a red knot at its foot, and the cord hanging from it on the
	 * rope's inertia (level 2 and up; level 3 trails a second cord turned 120 degrees). It breathes and slowly turns.
	 */
	private static void lanyard(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float ring) {
		if (lod >= 2 || ring <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		breath = 1F + 0.03F * (float) Math.sin(0.05F * d.time());
		ringScale = ring;
		if (layer == black) {
			Loft.closedTube(m, LANYARD, lod == 0 ? 8 : 6, 4, LANYARD_W, black, layer, ST, PART_LANYARD, 2F, phiGrip(RING_Y), UP_Z, 1F);
		}
		if (layer == red) {
			float k = ring;
			LOCAL.translation(0F, RING_Y - RING_R * 1.1F * breath, 0F).rotateZ(0.4F * (float) Math.sin(0.04F * d.time())).scale(k);
			m.box(LOCAL, -0.45F, -0.4F, -0.4F, 0.9F, 0.8F, 0.8F, ST.u0(PART_LANYARD, 5, phiGrip(RING_Y)), ST.v0(PART_LANYARD, 5, phiGrip(RING_Y)), 1.5F, 1F, m.tint());
		}
		if (lod == 0 && tier >= 2 && layer == black) {
			float grow = detailed ? ArmMath.sm((ft - 9F) / 3F) : ring;
			if (grow <= 0.02F) return;
			float[] nodes = d.ropeNodes();
			if (nodes == null || nodes.length < CORD.length) {
				Rope.droop(new Vector3f(), CORD_OUT, DOWN, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, CORD);
			} else {
				System.arraycopy(nodes, 0, CORD, 0, CORD.length);
			}
			cordW = 1F;
			int cords = tier >= 3 ? 2 : 1;
			for (int k = 0; k < cords; k++) {
				tailSet = k;
				Loft.tube(m, CORD_CURVE, 0F, grow, 8, 4, CORD_W, black, layer, ST, PART_CORD + k, 2F, phiGrip(RING_Y), phiGrip(RING_Y) + 8F, UP_Z, 1F);
			}
		}
	}

	/** A point on the cord at {@code s}: Catmull-Rom through the rope's nodes, the second cord turned 120 degrees about the grip. */
	private static void cordPoint(float s, Vector3f out) {
		int links = ArmDraw.ROPE_NODES - 1;
		float f = ArmMath.clamp01(s) * links;
		int i = Math.min(links - 1, (int) f);
		float u = f - i;
		int i0 = Math.max(0, i - 1), i2 = Math.min(links, i + 1), i3 = Math.min(links, i + 2);
		float x = catmull(CORD[3 * i0], CORD[3 * i], CORD[3 * i2], CORD[3 * i3], u);
		float y = catmull(CORD[3 * i0 + 1], CORD[3 * i + 1], CORD[3 * i2 + 1], CORD[3 * i3 + 1], u);
		float z = catmull(CORD[3 * i0 + 2], CORD[3 * i + 2], CORD[3 * i2 + 2], CORD[3 * i3 + 2], u);
		if (tailSet == 1) {
			float c = -0.5F, sn = 0.8660254F;
			float rx = x * c - z * sn, rz = x * sn + z * c;
			x = rx;
			z = rz;
		}
		out.set(CORD_ROOT.x + x, CORD_ROOT.y + y, CORD_ROOT.z + z);
	}

	// ------------------------------------------------------------------ beads and drips

	/**
	 * Loose beads: two flicked off the point by a hit (and a crop's bite), beads dropping off a retracting front, a melt
	 * shedding two a tick at its erosion front, and a failing arm's three beads orbiting where its point used to be.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, float rt, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = Math.min(d.hitAge(), d.breakAge());
		if (hitAge >= 0F && hitAge < 8F && vFront > 1F) {
			frameAt(vFront);
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
				float v = arcLength * (1F - ArmMath.sm((1.4F * k) / 7F));
				frameAt(v);
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
				frameAt(v);
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, M.x + E.x * side * 1.5F + DOWN.x * 0.4F * a * a,
						M.y + E.y * side * 1.5F + DOWN.y * 0.4F * a * a, M.z + E.z * side * 1.5F + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			frameAt(arcLength);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * d.time() + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, M.x + 0.6F * ((float) Math.cos(a) * E.x + (float) Math.sin(a) * Z.x),
						M.y + 0.6F * ((float) Math.cos(a) * E.y + (float) Math.sin(a) * Z.y), M.z + 0.6F * ((float) Math.cos(a) * E.z + (float) Math.sin(a) * Z.z),
						0.5F, 16 + k);
			}
		}
	}

	/** Where along the blade (px of arc at the level-2 length) the drips form. */
	private static final float[] DRIP_V = {6.0F, 15.5F};

	/** Drips off the edge's lowest side at two places, the lanyard's knot and the cord's end, each only while its piece is out. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m, float ring) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate();
		float scale = arcLength / L2_ARC;
		int j = 0;
		for (float v0 : DRIP_V) {
			float v = v0 * scale;
			if (v < vFront) {
				frameAt(v);
				float h = mihaba(v / arcLength);
				float side = E.dot(DOWN) >= 0F ? h : -0.2F;
				Drips.emit(m, null, M.x + E.x * side, M.y + E.y * side, M.z + E.z * side, DOWN, d.time(), j + 4 * (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
			}
			j++;
		}
		if (ring < 0.9F) return;
		Drips.emit(m, null, 0F, RING_Y - RING_R * 1.3F, 0F, DOWN, d.time(), 2, rate, d.blocksToPx(), ST, PART_DRIP);
		if (tier >= 2) {
			tailSet = 0;
			cordPoint(1F, Q);
			Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, d.time(), 3, rate, d.blocksToPx(), ST, PART_DRIP);
		}
	}

	// ------------------------------------------------------------------ LOD 3

	/** LOD 3: four boxes, the grip and three along the crescent (the last with the red edge on it). */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m, float ring) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		arcLength = bladeArc(tier);
		droop = 0F;
		sag = 0F;
		if (layer == black && yHi - yLo > 0.1F) {
			m.box(null, -0.8F, yLo, -0.7F, 1.6F, yHi - yLo, 1.4F, ST.u0(PART_FAR, 0, 10F), ST.v0(PART_FAR, 0, 10F), 1F, 1F, m.tint());
		}
		for (int k = 0; k < 2; k++) {
			float v0 = arcLength * (k == 0 ? 0F : 0.5F), v1 = Math.min(vFront, arcLength * (k == 0 ? 0.5F : 1F));
			if (v1 - v0 < 0.3F) continue;
			frameAt(v0);
			float x0 = M.x, y0 = M.y;
			frameAt(v1);
			float dx = M.x - x0, dy = M.y - y0;
			float len = (float) Math.hypot(dx, dy);
			if (len < 0.2F) continue;
			LOCAL.translation(x0, y0, 0F).rotateZ((float) Math.atan2(dy, dx) - ArmMath.PI / 2F);
			if (layer == black) m.box(LOCAL, -0.7F, 0F, -0.3F, 1.4F, len, 0.6F, ST.u0(PART_FAR, 1 + k, 20F), ST.v0(PART_FAR, 1 + k, 20F), 1F, 1F, m.tint());
			if (layer == red && k == 1) m.box(LOCAL, 0.5F, 0F, -0.15F, 0.5F, len * 0.95F, 0.3F, ST.u0(PART_FAR, 3, 20F), ST.v0(PART_FAR, 3, 20F), 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		int t = Math.clamp(d.tier(), 1, 3);
		tier = t;
		arcLength = bladeArc(t);
		droop = 0F;
		float rt = d.retractTick();
		sag = rt >= 0F ? 1.2F * ArmMath.sm(rt / 3F) : 0F;
		float reach = d.reach();
		float front = d.detailedForm() ? bladeArc(t) * ArmMath.sm((d.formTick() - 3F) / 5.5F)
				: ArmMath.clamp(reach * (GRIP_TOP + arcLength) - GRIP_TOP, 0F, arcLength);
		frameAt(which == ArmTips.MID ? 0.55F * front : front);
		out.set(M);
		sag = 0F;
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{8.9F, 15F, 0.6F}, {3.5F, 21.6F, -0.6F}, {-3.6F, 15.2F, 0.5F}, {-1.5F, 17.8F, 0F}, {1.2F, 5F, -1F}, {-1.3F, 5.2F, 1F},
				{1.4F, -3.6F, 1F}, {-1.4F, -3.6F, -1F}, {0F, -6.8F, 0F}, {1.6F, -5.3F, 0F}, {-1.6F, -5.3F, 0F}};
		for (float[] p : pts) out.accept(new Vector3f(p));
	}
}
