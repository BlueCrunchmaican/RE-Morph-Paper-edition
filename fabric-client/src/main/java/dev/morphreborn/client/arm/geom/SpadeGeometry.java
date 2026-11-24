package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The spade (section 2.11), a bronze-age shovel grown out of the arm: a haft of three fine strands braided
 * over a red core and tied with two knotted rings, ending below in a closed loop for the other hand; at its top a
 * socket of four tendrils clutching the base of the blade. The blade is a thin, deeply dished scoop that lifts forward
 * along its length, full width from a third of the way up, its shoulders closing to a point, ribbed by converging tendrils with a red midrib, its leading margin toothed with thorns of
 * every size and a thin red band, a foot-step flaring out at its shoulders.
 * <p>
 * Grip space G (section 2.0), px; level 2 is the baseline. Like the katana it is built with its leading margin on +X and
 * drawn through an X mirror ({@link #EDGE_SIGN}), so in hand that margin faces down. Level 1 has three ribs, two
 * strands, one ring and two clutching tendrils; level 3 has seven ribs, doubled thorns, a bigger foot-step, and its
 * midrib, ribs and margin glow.
 * <p>
 * Everything moves: the static crawls up the braid and out along the ribs, the braid twists and the rings turn, the
 * leaf's margin ripples and breathes, the thorns sway, the clutching tendrils flex, pulses run up the midrib and
 * across the ribs, goo drips. Digging (mining) bites the tip out and tightens the clutch; a broken block jolts the leaf
 * and flicks beads off its tip; Burrow splits the leaf's upper part into three digging claws ringed by writhing
 * tendrils for as long as the window is open. Low biomass thins, cracks and erodes it. Forming, the haft grows out of
 * the fist, and then the rings, the loop, the socket, the leaf, its ribs, thorns and foot-step come out one after
 * another.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class SpadeGeometry implements FormGeometry {
	/** Which side of G the leading margin is on, as the katana's: built on +X and drawn mirrored. */
	static final float EDGE_SIGN = KatanaGeometry.EDGE_SIGN;
	/** The haft's radius under the fingers. */
	public static final float HAFT_R = 0.9F;
	/** The haft (strands and core) from the butt to under the leaf, and the braid's pitch. */
	private static final float HAFT_Y0 = -5.0F, HAFT_Y1 = 13.0F, TWIST = 4.2F;
	/** The leaf: its base and length, its widest half-width, where along it that is, the cup and the axis's lean toward +X. */
	private static final float LEAF_Y0 = 10.4F, LEAF_LEN = 8.2F, LEAF_H = 2.95F, CUP = 1.4F, AXIS_BEND = 0.5F;
	/** How far the blade's end lifts forward into its own dish (px at the tip): with the cup, a scoop (2026-11-15). */
	private static final float LIFT = 1.6F;
	/** The leaf's thickness (half) at its middle and at its margins. */
	private static final float HT_MID = 0.27F, HT_EDGE = 0.15F;
	/** The feed coordinate where the leaf starts, and the rings' heights. */
	private static final float PHI_LEAF = phiHaft(LEAF_Y0);
	private static final float[] RING_Y = {4.6F, 8.2F};
	/** The ribs' places across the leaf (× the half-width there) by level: the midrib first, then the pairs. */
	private static final float[][] RIB_C = {{0F, -0.55F, 0.55F}, {0F, -0.88F, -0.45F, 0.45F, 0.88F}, {0F, -0.95F, -0.66F, -0.33F, 0.33F, 0.66F, 0.95F}};
	/** Each rib's width (px) by place in {@link #RIB_C}. */
	private static final float[] RIB_W = {0.80F, 0.62F, 0.62F, 0.58F, 0.58F, 0.54F, 0.54F};
	private static final int MAX_RIBS = 7;
	/**
	 * The thorns' own characters, by place (five along the leading margin, two along the other, one up the haft): the
	 * leaf's t, length, lean from the margin's normal (degrees), hook (radians more each segment), thickness, sideways
	 * lean out of the leaf's plane, and which of them fork. None sits past t 0.82, so the point stays clean.
	 */
	private static final float[] TH_T = {0.27F, 0.41F, 0.55F, 0.69F, 0.81F, 0.36F, 0.60F, 0.21F, 0.73F, 0F},
			TH_LEN = {0.55F, 0.9F, 0.65F, 1.0F, 0.5F, 0.5F, 0.6F, 0.45F, 0.4F, 1.2F}, // short on the blade: a shovel, not a mace
			TH_ANG = {36F, 22F, 48F, 30F, 54F, 26F, 40F, 44F, 32F, 20F},
			TH_CURL = {0.30F, -0.20F, 0.35F, 0.05F, -0.30F, 0.25F, -0.15F, -0.25F, 0.20F, 0.25F},
			TH_W = {0.85F, 1.15F, 0.90F, 1.30F, 0.75F, 0.70F, 0.80F, 0.65F, 0.60F, 0.80F},
			TH_SIDE = {0.10F, -0.14F, 0.18F, -0.10F, 0.06F, 0.12F, -0.16F, 0.15F, -0.08F, 0.14F};
	private static final boolean[] TH_FORK = {false, true, false, true, false, false, true, false, false, false};
	/** FORM: the first thorn sprouts at this tick, then one every {@code TH_STAGGER}. */
	private static final float TH_FROM = 11.3F, TH_STAGGER = 0.32F;
	/** Starving cracks along the leaf (t). */
	private static final float[] CRACKS = {0.30F, 0.36F, 0.56F, 0.62F, 0.78F, 0.83F};
	/** Where the burrowing claws start along the leaf, and how far the three splay (radians). */
	private static final float CLAW_T = 0.60F, CLAW_SPLAY = 0.6F;

	// static parts, one per piece
	private static final int PART_CORE = 1, PART_STRAND = 2, PART_RING = 5, PART_LOOP = 8, PART_KNOT = 9, PART_CUFF = 10, PART_CLUTCH = 11, PART_SLAB = 16,
			PART_RIB = 17, PART_MARGIN = 60, PART_STEP = 27, PART_THORN = 28, PART_FORK = 40, PART_CLAW = 50, PART_RINGT = 54, PART_DRIP = 70, PART_FAR = 77;

	private static final Static ST = new Static();
	private static final Stations STRAND = new Stations(), SLAB = new Stations(), CLAWS = new Stations(), MARGIN = new Stations();
	private static final float[] RING4 = new float[8], POLY = new float[24], RIBBON = new float[4];
	private static final float[] PTS = new float[3 * 40], RAD = new float[40], ALPHA = new float[40];
	private static final ArmLayer[] SEG = new ArmLayer[40];
	private static final float[] TH_PTS = new float[12];
	private static final float[] RIB_R = new float[MAX_RIBS], TH_S = new float[10], CLUTCH_R = new float[4], RING_S = new float[2];
	private static final boolean[] SLAB_CRACKED = new boolean[16];
	private static final float[] VEIN_ORDER = new float[MAX_RIBS];
	private static final Matrix4f LOCAL = new Matrix4f(), SPASM = new Matrix4f(), TOOL = new Matrix4f();
	private static final Vector3f DOWN = new Vector3f(), P = new Vector3f(), T = new Vector3f(), U = new Vector3f(), Q = new Vector3f(), R = new Vector3f(),
			A = new Vector3f(), B = new Vector3f(), N = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);

	// what the current emit has worked out (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, ti, lod, nRibs, thSeg;
	private static float time, feedLength, droop, thin, fringeW, gnaw, recoil, claw, flare, thFuzz;
	private static float yHi, yLo, leafR, cuffS, buttS, marginR, stepS, tipLoss;
	private static boolean detailed, cutting, starving, failing;
	private static ArmLayer capLayer = ArmLayer.BLACK, blackNow = ArmLayer.BLACK, midLayer = ArmLayer.RED, marginLayer = ArmLayer.RED, crackLayer = ArmLayer.RED;
	private static final Loft.FaceLayer BY_SEGMENT = (seg, face) -> face < 0 ? capLayer : SEG[Math.clamp(seg, 0, SEG.length - 1)];
	private static final Loft.FaceLayer ALL_BLACK = (seg, face) -> blackNow;
	/** The leaf's slab: black, a starving crack's faces red. */
	private static final Loft.FaceLayer SLAB_FACES = (seg, face) -> SLAB_CRACKED[Math.clamp(seg, 0, SLAB_CRACKED.length - 1)] ? crackLayer : blackNow;
	private static final Loft.FaceLayer MARGIN_FACES = (seg, face) -> marginLayer;
	private static float ringY, ringTurn, ringScale;
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.0F * ringScale * (1F + 0.07F * (float) Math.cos(3F * (a - ringTurn)));
		out.set(r * (float) Math.cos(a), ringY + 0.1F * (float) Math.sin(3F * (a - ringTurn)) * ringScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.46F * ringScale, 4);
	private static float loopScale;
	/** The loop for the other hand: a D in the XY plane under the butt, its top meeting the haft. */
	private static final Loft.CurveFn LOOP = (s, out) -> {
		float a = ArmMath.TAU * s;
		float wob = 1F + 0.05F * (float) Math.sin(3F * a + 0.04F * time);
		out.set(2.1F * loopScale * (float) Math.sin(a) * wob, -7.2F - 1.9F * loopScale * (float) Math.cos(a) * wob + (1F - loopScale) * 2F, 0.08F * (float) Math.sin(2F * a));
	};
	private static final Loft.RadiusFn LOOP_W = s -> FistGeometry.width(0.62F * loopScale * (1F + 0.15F * (float) Math.sin(ArmMath.TAU * 2F * s)), 4);
	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * thSeg;
		int i = Math.min(thSeg - 1, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(TH_PTS[3 * i], TH_PTS[3 * i + 3], u), ArmMath.lerp(TH_PTS[3 * i + 1], TH_PTS[3 * i + 4], u), ArmMath.lerp(TH_PTS[3 * i + 2], TH_PTS[3 * i + 5], u));
	};
	private static final Loft.RadiusFn TH_RADIUS = s -> FistGeometry.width(ArmMath.lerp(0.34F, 0.10F, s) * thFuzz, 4);

	public SpadeGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.SHOVEL;
	}

	@Override
	public float feedLength(int tier) {
		return PHI_LEAF + LEAF_LEN + (tier >= 3 ? 0.8F : 0F);
	}

	@Override
	public float guiScale() {
		// the tip (y 18.6) and the loop (y -9.1) keep to 1..15 px along the slot's diagonal
		return 0.64F;
	}

	@Override
	public float guiGripPx() {
		return 5.4F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The leaf's half-width at {@code t} along it (0 at the base, {@link #LEAF_H} a third of the way, 0 at the point), for the tests. */
	static float halfWidth(float t) {
		if (t <= 0F || t >= 1F) return 0F;
		// a round-point shovel: out of the socket to full width by a third, wide through the middle, then full shoulders
		// closing to a point (my call: the leaf read as a mace, but the fully rounded end was too round; the point stays)
		if (t < WIDE_AT) return LEAF_H * (float) Math.pow(Math.sin(ArmMath.PI / 2F * t / WIDE_AT), 0.8);
		float u = (t - WIDE_AT) / (1F - WIDE_AT);
		return LEAF_H * (float) Math.pow(1F - Math.pow(u, 1.7), 0.85);
	}

	/** Where the blade reaches its full width (t). */
	private static final float WIDE_AT = 0.33F;

	/** The leaf's thickness at its middle (the spec's 0.55 px), for the tests. */
	static float slabThickness() {
		return 2F * HT_MID;
	}

	/** The leaf's slab stations as the last emit that drew it left them: for the tests. */
	static Stations lastSlab() {
		return SLAB;
	}

	/** The feed coordinate on the haft at height {@code y}: up from the fist, and down to the butt. */
	static float phiHaft(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (d == null || layer == ArmLayer.SENSE) return;
		begin(d);
		ST.set(time, d.staticCadence(), d.crawlDir(), d.seed());
		if (d.firstPerson()) {
			FistGeometry.mirror(m, d.leftHand());
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0.8F * gnaw);
			FistGeometry.emit(d, layer, m, ST, HAFT_R, feedLength);
			FistGeometry.mirror(m, false);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm();
		m.transform(TOOL.scaling(EDGE_SIGN * sp, sp, sp));
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	private static void begin(ArmDraw d) {
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		ti = tier - 1;
		nRibs = RIB_C[ti].length;
		lod = Math.clamp(d.lod(), 0, 3);
		time = d.time();
		feedLength = tier >= 3 ? PHI_LEAF + LEAF_LEN + 0.8F : PHI_LEAF + LEAF_LEN;
		d.down(DOWN);
		DOWN.x *= EDGE_SIGN; // into the mirrored build space
		droop = d.droop();
		int stage = d.lowStage();
		starving = stage >= ArmDraw.LOW_STARVING;
		failing = stage >= ArmDraw.LOW_FAILING;
		thin = stage >= ArmDraw.LOW_THINNING ? 0.85F : 1F;
		// digging: a bite every 5 ticks; a broken block: the leaf recoils 0.8 px over 2 ticks and overshoots 0.4
		float bite = (float) Math.sin(ArmMath.TAU * time / 5F);
		gnaw = d.mining() ? Math.max(0F, bite) * Math.max(0F, bite) * Math.max(0F, bite) : 0F;
		float a = d.breakAge();
		if (a < 2F) recoil = -0.8F * ArmMath.sm(a / 2F);
		else if (a < 3.5F) recoil = ArmMath.lerp(-0.8F, 0.4F, ArmMath.sm((a - 2F) / 1.5F));
		else if (a < 5F) recoil = ArmMath.lerp(0.4F, 0F, ArmMath.sm((a - 3.5F) / 1.5F));
		else recoil = 0F;
		claw = burrow(d);
		flare = Math.max(d.hitFlare(), Math.max(0.7F * gnaw, 0.6F * claw));
		plan(d);
	}

	/**
	 * Burrow's claws, 0..1: opening over the window's first 5 ticks and held while it is open (the effect's resends extend
	 * the running window rather than restart it, so the age keeps counting), closing over the 8 ticks after it ends.
	 */
	private static float burrow(ArmDraw d) {
		if (d.specialKind() != ArmProtocol.FX_BURROW) return 0F;
		if (d.specialRunning()) return ArmMath.sm(d.specialAge() / 5F);
		float ago = d.specialEndedAgo();
		return ago < 8F ? 1F - ArmMath.sm(ago / 8F) : 0F;
	}

	/** How much of each piece is out: the haft first, then the details (as I asked for FORM). */
	private static void plan(ArmDraw d) {
		detailed = d.detailedForm() || d.state() == ArmProtocol.STATE_QUICKFORM;
		float ft = d.formTick();
		if (detailed) {
			float r = ArmDraw.formReach(ft);
			yHi = HAFT_Y1 * r;
			yLo = HAFT_Y0 * r;
			for (int k = 0; k < 2; k++) RING_S[k] = pop((ft - (8.0F + 0.7F * k)) / 2F);
			buttS = pop((ft - 8.2F) / 2F);
			cuffS = pop((ft - 8.5F) / 2F);
			for (int k = 0; k < 4; k++) CLUTCH_R[k] = ArmMath.sm((ft - (9.0F + 0.4F * k)) / 2.2F);
			leafR = ArmMath.sm((ft - 9.6F) / 3.0F);
			for (int k = 0; k < MAX_RIBS; k++) RIB_R[k] = ArmMath.sm((ft - (10.4F + 0.25F * k)) / 2.4F);
			for (int k = 0; k < 10; k++) TH_S[k] = pop((ft - TH_FROM - TH_STAGGER * k) / 1.6F);
			marginR = ArmMath.sm((ft - 12F) / 1.5F);
			stepS = pop((ft - 10.6F) / 2F);
			cutting = ft < 12.6F;
		} else {
			float rr = d.reach();
			float up = rr * (LEAF_Y0 + LEAF_LEN + 0.6F), down = rr * 5.8F;
			yHi = Math.min(HAFT_Y1, up);
			yLo = Math.max(HAFT_Y0, -down);
			for (int k = 0; k < 2; k++) RING_S[k] = ArmMath.sm((up - (RING_Y[k] + 0.6F)) / 0.8F);
			buttS = ArmMath.sm((down - 4.4F) / 0.8F);
			cuffS = ArmMath.sm((up - 9.8F) / 1.0F);
			for (int k = 0; k < 4; k++) CLUTCH_R[k] = ArmMath.sm((up - 10.2F) / 1.8F);
			leafR = ArmMath.sm((up - LEAF_Y0 - 0.2F) / (LEAF_LEN + 0.4F));
			for (int k = 0; k < MAX_RIBS; k++) RIB_R[k] = leafR;
			for (int k = 0; k < 10; k++) TH_S[k] = ArmMath.sm((leafR - 0.85F) / 0.15F);
			marginR = leafR;
			stepS = ArmMath.sm((up - 11.4F) / 1.0F);
			cutting = rr < 1F;
		}
		fringeW = d.fringe();
		tipLoss = 0F;
		if (failing) {
			// the leaf's last 15% erodes away (cut off with dissolve off)
			leafR = Math.min(leafR, 0.85F);
			fringeW = Math.max(fringeW, 1.7F);
			cutting = true;
		}
	}

	/** FORM's pop: 0 → 1.25 → 1 over {@code u} 0..1 (0 before, 1 after). */
	private static float pop(float u) {
		if (u <= 0F) return 0F;
		if (u >= 1F) return 1F;
		return u < 0.5F ? 1.25F * ArmMath.sm(2F * u) : ArmMath.lerp(1.25F, 1F, ArmMath.sm(2F * u - 1F));
	}

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		blackNow = d.paint(ArmLayer.BLACK);
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m);
			return;
		}
		if (lod == 2) {
			coarse(d, layer, m);
			return;
		}
		core(d, layer, m);
		braid(d, layer, m);
		rings(d, layer, m);
		loop(d, layer, m);
		cuff(d, layer, m);
		clutch(d, layer, m);
		leaf(d, layer, m);
		ribs(d, layer, m);
		veinlets(d, layer, m);
		margin(d, layer, m);
		steps(d, layer, m);
		if (claw > 0.02F && lod == 0) {
			claws(d, layer, m);
			ringOfTendrils(d, layer, m);
		}
		thorns(d, layer, m);
		beads(d, layer, m);
		if (lod == 0 && d.dripsNow() && (!detailed || d.formTick() >= 13F)) drips(d, layer, m);
	}

	// ------------------------------------------------------------------ the leaf's shape

	/** The leaf's centre line at {@code t}: y and the lean toward +X, with the recoil, the bite and the melt's droop. */
	private static void axisAt(float t, Vector3f out) {
		float ext = recoil * t + 0.6F * gnaw * ArmMath.clamp01((t - 0.6F) / 0.4F) - tipLoss * t;
		out.set(AXIS_BEND * t * t, LEAF_Y0 + LEAF_LEN * t + ext, LIFT * t * t);
		if (droop > 0F) {
			float f = ArmMath.clamp01((out.y - HAFT_Y0) / (LEAF_Y0 + LEAF_LEN - HAFT_Y0));
			out.fma(droop * f * f, DOWN);
		}
	}

	/** The leaf's half-width at {@code t}, breathing and swelling with the pulses that pass. */
	private static float width(float t) {
		float swell = 1F + 0.08F * cur.swell(PHI_LEAF + LEAF_LEN * t, feedLength);
		float breathe = 1F + 0.03F * (float) Math.sin(0.06F * time + 3F * t);
		return halfWidth(t) * thin * breathe * swell;
	}

	/** The point at fraction {@code c} (−1..1) across the leaf at {@code t}, in the leaf's mid-plane, into {@code out}. */
	private static void leafAt(float t, float c, Vector3f out) {
		axisAt(t, out);
		float h = width(t);
		float x = c * h;
		float ripple = 0.10F * c * c * (float) Math.sin(cur.phase() + 4.5F * t + 0.3F * time + 1.7F * c);
		out.x += x;
		out.z += CUP * (x / LEAF_H) * (x / LEAF_H) + ripple;
	}

	/**
	 * A section across the leaf at {@code t}: the slab polygon into {@link #POLY} as (n, b) pairs, {@code k} points along
	 * each face. The loft's frame has B = −Z here (T +Y, N +X), so b is the negative of the leaf's depth.
	 */
	private static void slabSection(float t, int k) {
		float h = width(t);
		for (int i = 0; i < k; i++) {
			float c = -1F + 2F * i / (k - 1F);
			float x = c * h;
			float z = CUP * (x / LEAF_H) * (x / LEAF_H) + 0.10F * c * c * (float) Math.sin(cur.phase() + 4.5F * t + 0.3F * time + 1.7F * c);
			float tt = ArmMath.lerp(HT_MID, HT_EDGE, Math.abs(c)) * thin;
			POLY[2 * i] = x;
			POLY[2 * i + 1] = -(z + tt);
			POLY[2 * (2 * k - 1 - i)] = x;
			POLY[2 * (2 * k - 1 - i) + 1] = -(z - tt);
		}
	}

	// ------------------------------------------------------------------ the haft: core, braid, rings, loop

	private static void core(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer != red || yHi - yLo < 0.05F) return;
		Stations s = STRAND.reset(4).feed(phiHaft(yLo));
		ArmMath.ring(RING4, 4, FistGeometry.width(0.7F, 4), FistGeometry.width(0.7F, 4), ArmMath.PI / 4F);
		s.add(0F, yLo, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 0F, RING4, 1F);
		s.add(0F, yHi, 0F, 0F, 1F, 0F, 1F, 0F, 0F, yHi - yLo, RING4, 1F);
		capLayer = red;
		SEG[0] = red;
		SEG[1] = red;
		Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_CORE, 1F, false, false);
	}

	/**
	 * Three fine strands braided round the core ({@code ψ = 2πk/3 + 2π(y+5)/4.2 + 0.03t}), the distance from the axis
	 * rising and falling out of step so they pass over and under; each pulse thickens them; knots swell up the haft where
	 * the rings tie it; level 1 has two strands, and a starving crack at y 6..6.8 turns them red and thin.
	 */
	private static void braid(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (yHi - yLo < 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int strands = tier <= 1 ? 2 : 3;
		int full = lod == 0 ? 19 : 7;
		int n = Math.max(2, Math.round((full - 1) * (yHi - yLo) / (HAFT_Y1 - HAFT_Y0)) + 1);
		boolean dissolve = d.dissolve();
		for (int k = 0; k < strands; k++) {
			float phase = ArmMath.TAU * k / strands;
			for (int i = 0; i < n; i++) {
				float y = ArmMath.lerp(yLo, yHi, i / (float) (n - 1));
				float psi = phase + ArmMath.TAU * (y + 5F) / TWIST + 0.03F * time;
				float r = 0.42F + 0.12F * (float) Math.sin(ArmMath.PI * (y + 5F) / (TWIST / 2F) + phase);
				boolean crack = starving && y >= 5.8F && y <= 7.0F;
				float w = ArmMath.lerp(0.64F, 0.56F, (y - HAFT_Y0) / (HAFT_Y1 - HAFT_Y0)) * thin * (1F + d.swell(phiHaft(y), feedLength)) * (crack ? 0.75F : 1F);
				PTS[3 * i] = r * (float) Math.cos(psi);
				PTS[3 * i + 1] = y;
				PTS[3 * i + 2] = r * (float) Math.sin(psi);
				RAD[i] = FistGeometry.width(w, 4);
				ALPHA[i] = crack && dissolve ? 0.72F : 1F;
				if (i > 0) {
					float ym = ArmMath.lerp(yLo, yHi, (i - 0.5F) / (n - 1));
					SEG[i - 1] = starving && ym >= 6.0F && ym <= 6.8F ? red : black;
				}
			}
			capLayer = black;
			strand(m, n, layer, PART_STRAND + k, 1.5F, phiHaft(yLo), 0F, 0F, 1F, false);
		}
	}

	/** Two knotted rings tying the haft, turning against each other; level 3's upper one red. */
	private static void rings(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		int first = tier <= 1 ? 1 : 0;
		for (int k = first; k < 2; k++) {
			float y = RING_Y[k];
			if (y > yHi + 0.05F || y < yLo - 0.05F) continue;
			ArmLayer l = tier >= 3 && k == 1 ? red : black;
			if (layer != l) continue;
			ringScale = RING_S[k];
			if (ringScale <= 0.02F) continue;
			ringY = y;
			ringTurn = ((k & 1) == 0 ? 0.02F : -0.02F) * time + k;
			Loft.closedTube(m, RING, lod == 0 ? 8 : 6, 4, RING_W, l, layer, ST, PART_RING + k, 2F, phiHaft(y), UP_Y, 1F);
			if (lod == 0) {
				// the knot where its ends meet
				float a = 1.2F * k;
				LOCAL.identity().translate(1.05F * ringScale * (float) Math.cos(a), y, 1.05F * ringScale * (float) Math.sin(a)).rotateY(-a).scale(ringScale);
				m.box(LOCAL, -0.45F, -0.4F, -0.4F, 0.9F, 0.8F, 0.8F, ST.u0(PART_RING + 2, k, phiHaft(y)), ST.v0(PART_RING + 2, k, phiHaft(y)), 1F, 1F, m.tint());
			}
		}
	}

	/** The loop for the other hand under the butt (a D of tendril), with a knot where it meets the haft. */
	private static void loop(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || buttS <= 0.02F) return;
		LOCAL.identity().translate(0F, -5.2F, 0F).rotateY((float) Math.toRadians(20)).scale(buttS);
		m.box(LOCAL, -0.7F, -0.5F, -0.7F, 1.4F, 1.0F, 1.4F, ST.u0(PART_KNOT, 0, 17F), ST.v0(PART_KNOT, 0, 17F), 1F, 1F, m.tint());
		if (lod > 0 || tier < 2) return;
		loopScale = buttS;
		Loft.closedTube(m, LOOP, 10, 4, LOOP_W, black, layer, ST, PART_LOOP, 2F, phiHaft(-6F), UP_Z, 1F);
	}

	// ------------------------------------------------------------------ the socket: cuff and clutching tendrils

	private static void cuff(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || cuffS <= 0.02F) return;
		float k = cuffS;
		LOCAL.identity().translate(0F, 9.9F, 0F).rotateY((float) Math.toRadians(12)).scale(k);
		m.box(LOCAL, -0.95F, -0.55F, -0.8F, 1.9F, 1.1F, 1.6F, ST.u0(PART_CUFF, 0, PHI_LEAF), ST.v0(PART_CUFF, 0, PHI_LEAF), 1F, 1F, m.tint());
		LOCAL.identity().translate(0.1F, 10.9F, 0F).rotateY((float) Math.toRadians(-20)).scale(k);
		m.box(LOCAL, -0.8F, -0.45F, -0.7F, 1.6F, 0.9F, 1.4F, ST.u0(PART_CUFF, 1, PHI_LEAF), ST.v0(PART_CUFF, 1, PHI_LEAF), 1F, 1F, m.tint());
	}

	/**
	 * The socket's four tendrils: out of the haft's sides, bowing outward and curling in over the leaf's shoulders to grip
	 * its face, flexing as if holding on (tighter when digging, loosening when the leaf is withdrawn).
	 */
	private static void clutch(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		int count = tier <= 1 ? 2 : 4;
		float squeeze = 0.25F * gnaw + 0.15F * claw;
		for (int j = 0; j < count; j++) {
			float rr = CLUTCH_R[j];
			if (rr <= 0.03F || cuffS < 0.5F) continue;
			float sx = (j & 1) == 0 ? 1F : -1F, sz = (j & 2) == 0 ? 1F : -1F;
			int n = lod == 0 ? 5 : 3;
			float flex = 0.12F * (float) Math.sin(0.11F * time + 1.9F * j);
			A.set(0.5F * sx, 8.8F, 0.5F * sz);
			B.set(1.9F * sx, 10.6F, 1.5F * sz).add(flex * sx, 0F, 0F);
			N.set((1.15F - squeeze) * sx, 12.6F, 0.62F * sz);
			for (int i = 0; i < n; i++) {
				float u = rr * i / (n - 1F);
				ArmMath.bezier(A, B, N, u, P);
				PTS[3 * i] = P.x;
				PTS[3 * i + 1] = P.y;
				PTS[3 * i + 2] = P.z;
				RAD[i] = FistGeometry.width(ArmMath.lerp(0.62F, 0.22F, u) * thin * (1F + 0.2F * d.swell(phiHaft(9.5F + 3F * u), feedLength)), 4);
				ALPHA[i] = d.dissolve() && cutting && rr < 1F ? ArmMath.fringe((rr - u) * 5F, fringeW) : 1F;
				if (i > 0) SEG[i - 1] = black;
			}
			capLayer = black;
			strand(m, n, layer, PART_CLUTCH + j, 1.5F, phiHaft(8.8F), 0F, 0F, 1F, true);
		}
	}

	// ------------------------------------------------------------------ the leaf: slab, ribs, margin, foot-step

	/**
	 * The leaf's membrane: a thin cupped slab along its length, widest a third of the way up, running to a point (cut
	 * blunt at the growth front while it grows, and at the claws' root while Burrow has split it), ripple on its margins.
	 */
	private static void leaf(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean glow = tier >= 3 && d.glow();
		marginLayer = glow ? ArmLayer.GLOW : red;
		crackLayer = red;
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		blackNow = black;
		float top = leafR;
		if (claw > 0F) top = Math.min(top, ArmMath.lerp(1F, CLAW_T, claw));
		if (top <= 0.03F) return;
		boolean truncated = top < 0.999F;
		boolean fringe = d.dissolve() && cutting;
		int k = lod == 0 ? 5 : 3;
		int stations = lod == 0 ? 7 : 4;
		Stations s = SLAB.reset(2 * k).feed(PHI_LEAF);
		float arc = 0F;
		float px = 0F, py = 0F;
		int count = 0;
		float frontArc = top * LEAF_LEN;
		for (int i = 0; i < stations; i++) {
			float t = top * (0.02F + 0.98F * i / (stations - 1F));
			if (!truncated && i == stations - 1) t = 1F;
			slabSection(Math.min(t, 1F), k);
			axisAt(t, A);
			if (count > 0) arc += (float) Math.hypot(A.x - px, A.y - py);
			px = A.x;
			py = A.y;
			float alpha = fringe ? ArmMath.fringe(frontArc - t * LEAF_LEN, fringeW) : 1F;
			boolean crack = starving && crackAt(t) > 0.4F;
			if (crack && d.dissolve()) alpha = Math.min(alpha, 0.72F);
			s.add(A.x, A.y, A.z, 0F, 1F, 0F, 1F, 0F, 0F, arc, POLY, alpha);
			if (count > 0) SLAB_CRACKED[Math.min(SLAB_CRACKED.length - 1, count - 1)] = starving && crackAt(t - 0.5F * top / stations) > 0.4F;
			count++;
		}
		if (count < 2) return;
		Loft.sweep(m, s, layer, SLAB_FACES, ST, PART_SLAB, 1F, false, truncated);
	}

	private static float crackAt(float t) {
		float w = 0F;
		for (int i = 0; i < CRACKS.length; i += 2) {
			float a = CRACKS[i], b = CRACKS[i + 1];
			if (t >= a - 0.02F && t <= b + 0.02F) w = Math.max(w, t < a ? 1F - (a - t) / 0.02F : t > b ? 1F - (t - b) / 0.02F : 1F);
		}
		return w;
	}

	/**
	 * The ribs: tendrils converging from the base to the point, the midrib red (glowing at level 3, brighter with every
	 * pulse and flare) and thicker, the others black; each grows out of the base.
	 */
	private static void ribs(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean glow = tier >= 3 && d.glow();
		midLayer = glow ? ArmLayer.GLOW : red;
		if (layer != black && layer != midLayer && layer != red && !Loft.pulseGlowPass(layer)) return;
		float top = leafR;
		if (claw > 0F) top = Math.min(top, ArmMath.lerp(1F, CLAW_T, claw));
		boolean fringe = d.dissolve() && cutting;
		for (int k = 0; k < nRibs; k++) {
			float rr = Math.min(RIB_R[k], top);
			if (rr <= 0.04F) continue;
			boolean mid = k == 0;
			ArmLayer l = mid ? midLayer : black;
			if (layer != l) continue;
			int n = lod == 0 ? 6 : 3;
			float c = RIB_C[ti][k];
			float swell = mid ? 0.5F : 0.25F;
			for (int i = 0; i < n; i++) {
				float t = rr * (0.03F + 0.97F * i / (n - 1F));
				leafAt(Math.min(t, 0.985F), c, P);
				// the rib narrows toward the point where the leaf does, so it never leaves the membrane
				PTS[3 * i] = P.x;
				PTS[3 * i + 1] = P.y;
				PTS[3 * i + 2] = P.z;
				float w = RIB_W[k] * ArmMath.lerp(1F, 0.4F, t) * thin * (1F + swell * d.swell(PHI_LEAF + LEAF_LEN * t, feedLength) + (mid ? 0.4F * flare : 0F));
				RAD[i] = FistGeometry.width(w, 4);
				ALPHA[i] = fringe && rr < 1F ? ArmMath.fringe((rr - t) * LEAF_LEN, fringeW) : 1F;
				if (i > 0) SEG[i - 1] = l;
			}
			capLayer = l;
			strand(m, n, layer, PART_RIB + k, 1.5F, PHI_LEAF, 0F, 0F, 1F, rr < 0.999F);
		}
	}

	/**
	 * Veinlets: thin red zigzags between the ribs, one across each gap (level 2 and up), crawling up from the base as the
	 * leaf fills; they brighten with every pulse and flare, and glow at level 3.
	 */
	private static void veinlets(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (lod > 0 || tier < 2) return;
		boolean glow = tier >= 3 && d.glow();
		ArmLayer l = glow ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != l) return;
		float[] cs = RIB_C[ti];
		// the ribs' places in order across the leaf, left to right
		float[] order = VEIN_ORDER;
		int count = cs.length;
		System.arraycopy(cs, 0, order, 0, count);
		java.util.Arrays.sort(order, 0, count);
		float top = Math.min(leafR, claw > 0F ? ArmMath.lerp(1F, CLAW_T, claw) : 1F);
		for (int g = 0; g + 1 < count; g++) {
			float rr = Math.min(top, Math.min(RIB_R[indexOf(order[g])], RIB_R[indexOf(order[g + 1])]));
			int pts = 0;
			for (int i = 0; i < 7; i++) {
				float t = 0.22F + 0.095F * i;
				if (t > rr - 0.04F || t > 0.9F) break;
				float f = ((i + g) & 1) == 0 ? 0.3F : 0.7F;
				float c = ArmMath.lerp(order[g], order[g + 1], f);
				leafAt(t, c, P);
				PTS[3 * pts] = P.x;
				PTS[3 * pts + 1] = P.y;
				PTS[3 * pts + 2] = P.z + HT_MID * thin + 0.05F;
				pts++;
			}
			if (pts < 2) continue;
			float amp = d.swell(PHI_LEAF + 0.5F * LEAF_LEN, feedLength);
			float bright = ArmMath.clamp01(0.60F + 0.40F * Math.min(1F, 1.6F * amp) + 0.4F * flare);
			Stations s = MARGIN.reset(2).feed(PHI_LEAF + 0.3F * LEAF_LEN);
			s.color(FistGeometry.grey(layer == ArmLayer.GLOW ? bright : 0.7F + 0.3F * bright));
			RIBBON[0] = -0.14F;
			RIBBON[1] = 0F;
			RIBBON[2] = 0.14F;
			RIBBON[3] = 0F;
			float arc = 0F;
			for (int i = 0; i < pts; i++) {
				int a = Math.max(0, i - 1), z = Math.min(pts - 1, i + 1);
				float tx = PTS[3 * z] - PTS[3 * a], ty = PTS[3 * z + 1] - PTS[3 * a + 1], tz = PTS[3 * z + 2] - PTS[3 * a + 2];
				if (i > 0) arc += (float) Math.sqrt((PTS[3 * i] - PTS[3 * i - 3]) * (PTS[3 * i] - PTS[3 * i - 3]) + (PTS[3 * i + 1] - PTS[3 * i - 2]) * (PTS[3 * i + 1] - PTS[3 * i - 2])
						+ (PTS[3 * i + 2] - PTS[3 * i - 1]) * (PTS[3 * i + 2] - PTS[3 * i - 1]));
				// the ribbon lies in the leaf: its width across the way it runs
				U.set(tx, ty, tz).cross(0F, 0F, 1F);
				s.add(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], tx, ty, tz, U.x, U.y, U.z, arc, RIBBON, 1F);
			}
			marginLayer = l;
			Loft.sweep(m, s, layer, MARGIN_FACES, ST, PART_MARGIN + 1 + g, 2F, false, false);
		}
	}

	/** The index of rib place {@code c} in this level's list. */
	private static int indexOf(float c) {
		float[] cs = RIB_C[ti];
		for (int i = 0; i < cs.length; i++) if (cs[i] == c) return i;
		return 0;
	}

	/**
	 * A thin red band along the leading margin from a third of the way up almost to the point: the cutting edge of the
	 * leaf. It draws on after the leaf (forming) and flares with hits, digging and Burrow.
	 */
	private static void margin(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (lod > 0 || marginR <= 0.03F || tier < 2) return;
		boolean glow = tier >= 3 && d.glow();
		marginLayer = glow ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != marginLayer) return;
		float t1 = Math.min(0.94F, Math.min(leafR, claw > 0F ? ArmMath.lerp(1F, CLAW_T, claw) : 1F));
		float t0 = 0.30F;
		float reach = t0 + (t1 - t0) * marginR;
		if (reach < t0 + 0.04F) return;
		int n = 9;
		Stations s = MARGIN.reset(2).feed(PHI_LEAF + 0.3F * LEAF_LEN);
		float bright = ArmMath.clamp01(0.62F + 0.38F * Math.min(1F, 1.6F * d.swell(PHI_LEAF + 0.65F * LEAF_LEN, feedLength)) + 0.4F * flare);
		s.color(FistGeometry.grey(layer == ArmLayer.GLOW ? bright : 0.7F + 0.3F * bright));
		RIBBON[0] = -0.2F;
		RIBBON[1] = 0F;
		RIBBON[2] = 0.2F;
		RIBBON[3] = 0F;
		float arc = 0F;
		float px = 0F, py = 0F, pz = 0F;
		for (int i = 0; i < n; i++) {
			float t = ArmMath.lerp(t0, reach, i / (n - 1F));
			leafAt(t, 1F, P);
			leafAt(Math.min(t + 0.02F, 1F), 1F, Q);
			T.set(Q).sub(P);
			if (T.lengthSquared() < 1e-8F) T.set(0F, 1F, 0F);
			T.normalize();
			// the ribbon lies in the leaf's plane just outside the rim: its width along the outward direction
			U.set(T.y, -T.x, 0F);
			P.fma(0.25F, U);
			if (i > 0) arc += (float) Math.sqrt((P.x - px) * (P.x - px) + (P.y - py) * (P.y - py) + (P.z - pz) * (P.z - pz));
			px = P.x;
			py = P.y;
			pz = P.z;
			s.add(P.x, P.y, P.z, T.x, T.y, T.z, U.x, U.y, U.z, arc, RIBBON, 1F);
		}
		Loft.sweep(m, s, layer, MARGIN_FACES, ST, PART_MARGIN, 2F, false, false);
	}

	/** The foot-step: a pair of flanges flaring from the leaf's shoulders, bigger at level 3. */
	private static void steps(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || stepS <= 0.02F || tier < 2) return;
		for (int k = 0; k < 2; k++) {
			float sx = k == 0 ? 1F : -1F;
			float len = (tier >= 3 ? 2.3F : 1.7F) * stepS;
			float sway = 0.1F * (float) Math.sin(0.09F * time + 2F * k);
			// out and a little down from the shoulder, flat to the leaf
			LOCAL.identity().translate(sx * (width(0.12F) * 0.8F), LEAF_Y0 + 1.3F, 0F).rotateTowards(sx * 1F, -0.45F + sway, 0F, 0F, 0F, 1F);
			m.frustum(LOCAL, len, 0.55F, 0.4F, 0.12F, 0.1F, ST.u0(PART_STEP, k, PHI_LEAF), ST.v0(PART_STEP, k, PHI_LEAF), 2F, 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ Burrow: claws and the ring of tendrils

	/**
	 * Burrow's digging claws: from where the leaf was cut, three tapering claws spreading apart (the middle straight on,
	 * the others splayed outward) and curling toward the cupped face, each a flat tendril narrowing to a point.
	 */
	private static void claws(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		axisAt(CLAW_T, A);
		float h = width(CLAW_T);
		blackNow = black;
		for (int i = 0; i < 3; i++) {
			float c = (i - 1) * 0.62F;
			float splay = (i - 1) * CLAW_SPLAY * claw + 0.05F * (float) Math.sin(0.2F * time + 2F * i);
			float len = LEAF_LEN * (1F - CLAW_T) * (0.92F + 0.12F * (i == 1 ? 1F : 0F)) * claw;
			if (len < 0.3F) continue;
			Stations s = CLAWS.reset(4).feed(PHI_LEAF + LEAF_LEN * CLAW_T);
			int n = 5;
			float arc = 0F;
			float px = 0F, py = 0F, pz = 0F;
			for (int j = 0; j < n; j++) {
				float u = j / (n - 1F);
				float ang = splay * (0.4F + u);
				// out of the cut along the leaf, turning by ang toward the claw's side and curling +Z
				float x = A.x + c * h + (float) Math.sin(ang) * len * u;
				float y = A.y + (float) Math.cos(ang) * len * u;
				float z = A.z + 0.7F * claw * u * u * (1F + 0.2F * (float) Math.sin(0.25F * time + i));
				if (j > 0) arc += (float) Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
				px = x;
				py = y;
				pz = z;
				float w = ArmMath.lerp(0.95F, 0.08F, u * u) * thin * (1F + 0.15F * d.swell(PHI_LEAF + LEAF_LEN * (CLAW_T + (1F - CLAW_T) * u), feedLength));
				float th = ArmMath.lerp(0.34F, 0.08F, u) * thin;
				POLY[0] = -w / 2F;
				POLY[1] = -th / 2F;
				POLY[2] = w / 2F;
				POLY[3] = -th / 2F;
				POLY[4] = w / 2F;
				POLY[5] = th / 2F;
				POLY[6] = -w / 2F;
				POLY[7] = th / 2F;
				float tx = (float) Math.sin(ang) * len, ty = (float) Math.cos(ang) * len, tz = 1.4F * claw * u;
				s.add(x, y, z, tx, ty, tz, 1F, 0F, 0F, arc, POLY, 1F);
			}
			Loft.sweep(m, s, layer, ALL_BLACK, ST, PART_CLAW + i, 1F, false, false);
		}
	}

	/**
	 * Burrow's ring of tendrils: six short writhing tendrils standing round the leaf at the claws' root, turning slowly
	 * about it, each curling out and up like a finger of the dig.
	 */
	private static void ringOfTendrils(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		axisAt(CLAW_T, A);
		for (int j = 0; j < 6; j++) {
			float a = 0.06F * time + ArmMath.TAU * j / 6F;
			float rx = (float) Math.cos(a), rz = (float) Math.sin(a);
			float r0 = 1.6F * claw;
			float len = (1.9F + 0.5F * (float) Math.sin(0.2F * time + 1.7F * j)) * claw;
			int n = 4;
			for (int i = 0; i < n; i++) {
				float u = i / (n - 1F);
				float out = r0 + 0.9F * u * u * len * 0.5F;
				float wr = 0.15F * (float) Math.sin(d.phase() + 5F * u + 0.31F * time + j);
				PTS[3 * i] = A.x + rx * (out + wr);
				PTS[3 * i + 1] = A.y - 0.5F + len * u * 0.8F;
				PTS[3 * i + 2] = A.z + rz * (out + wr);
				RAD[i] = FistGeometry.width(ArmMath.lerp(0.42F, 0.1F, u) * thin, 4);
				ALPHA[i] = 1F;
				if (i > 0) SEG[i - 1] = black;
			}
			capLayer = black;
			strand(m, n, layer, PART_RINGT + j, 2F, PHI_LEAF + 4F, 0F, 1F, 0F, false);
		}
	}

	// ------------------------------------------------------------------ thorns, drips, beads

	/**
	 * Thorns of every size along the leading margin (five), the other margin (four) and the haft (one): each with its own
	 * length, lean, hook, thickness and sideways lean from the tables, some forked, swaying, streaming in a swing,
	 * bristling straight out when the claws open; they sprout one after another up the leaf. None reaches the point.
	 */
	private static void thorns(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		float thinFuzz = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int k = 0; k < 10; k++) {
			if (lod > 0 && TH_LEN[k] < 1.0F) continue;
			float grow = TH_S[k];
			if (grow <= 0.02F) continue;
			float bx, by, bz, ox, oy, oz, lx, ly, lz;
			if (k < 9) {
				float t = TH_T[k];
				float cutT = claw > 0F ? ArmMath.lerp(1F, CLAW_T, claw) : 1F;
				if (t > Math.min(leafR, cutT) - 0.04F) continue;
				float c = k < 5 ? 1F : -1F; // 0..4 the leading margin, 5..8 the other
				leafAt(t, c, P);
				leafAt(t + 0.03F, c, Q);
				T.set(Q).sub(P);
				if (T.lengthSquared() < 1e-8F) T.set(0F, 1F, 0F);
				T.normalize();
				// out of the margin, in the leaf's plane, away from the axis
				ox = c * T.y;
				oy = -c * T.x;
				oz = 0F;
				float ol = (float) Math.sqrt(ox * ox + oy * oy);
				if (ol > 1e-4F) {
					ox /= ol;
					oy /= ol;
				}
				bx = P.x + ox * 0.15F;
				by = P.y + oy * 0.15F;
				bz = P.z;
				lx = -T.x;
				ly = -T.y;
				lz = -T.z;
			} else {
				if (tier < 2 || 6.3F > yHi - 0.4F) continue;
				bx = 0.55F;
				by = 6.3F;
				bz = 0.3F;
				ox = 1F;
				oy = 0F;
				oz = 0.2F;
				lx = 0F;
				ly = -1F;
				lz = 0F;
			}
			float bristle = claw;
			float len = (1.25F + 0.4F * (float) Math.sin(0.09F * time + 1.9F * k) + 1.2F * d.surge()) * TH_LEN[k] * (1F + 0.4F * swing) * thinFuzz;
			len = ArmMath.lerp(len, 2.6F * TH_LEN[k], bristle) * grow;
			float base = (float) Math.toRadians(TH_ANG[k]);
			float side = TH_SIDE[k] * (1F - bristle);
			int segs = TH_LEN[k] >= 1.0F && lod == 0 ? 3 : 2;
			spinePoints(bx, by, bz, ox, oy, oz, lx, ly, lz, len, base, TH_CURL[k], side, k, swing, bristle, segs);
			thFuzz = TH_W[k];
			float phi = PHI_LEAF + 2F;
			Loft.tube(m, POLYLINE, 0F, 1F, segs + 1, 4, TH_RADIUS, black, layer, ST, PART_THORN + k, 2F, phi, phi + 2F, UP_Z, 1F);
			if (TH_FORK[k] && lod == 0) {
				float fx = TH_PTS[3], fy = TH_PTS[4], fz = TH_PTS[5];
				spinePoints(fx, fy, fz, ox, oy, oz, lx, ly, lz, 0.45F * len, base + 0.75F, -TH_CURL[k], -side, k + 5, swing, bristle, 2);
				thFuzz = 0.6F * TH_W[k];
				Loft.tube(m, POLYLINE, 0F, 1F, 3, 4, TH_RADIUS, black, layer, ST, PART_FORK + k, 2F, phi + 0.7F, phi + 1.6F, UP_Z, 1F);
			}
		}
		thFuzz = 1F;
	}

	/** A thorn's points into {@link #TH_PTS}: as the axe's bristles', leaving along {@code o} and leaning toward {@code l}. */
	private static void spinePoints(float x, float y, float z, float ox, float oy, float oz, float lx, float ly, float lz, float len, float base, float curl,
			float side, int k, float swing, float bristle, int segs) {
		thSeg = segs;
		TH_PTS[0] = x;
		TH_PTS[1] = y;
		TH_PTS[2] = z;
		float step = len / segs;
		for (int j = 1; j <= segs; j++) {
			float sway = 0.35F * (float) Math.sin(0.15F * time + 1.7F * k + 0.5F * j) * (j / (float) segs);
			float ang = base + curl * (j - 1) + sway + (float) Math.toRadians(50) * swing;
			ang *= 1F - bristle;
			float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
			x += (ox * c + lx * sn) * step;
			y += (oy * c + ly * sn) * step;
			z += (oz * c + lz * sn + side) * step;
			TH_PTS[3 * j] = x;
			TH_PTS[3 * j + 1] = y;
			TH_PTS[3 * j + 2] = z;
		}
	}

	/** Goo off the leaf's point, off its leading margin, under the cuff and off the loop. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate(), px = d.blocksToPx();
		if (leafR >= 0.97F && claw < 0.3F) {
			leafAt(0.97F, 0F, P);
			Drips.emit(m, null, P.x, P.y, P.z, DOWN, time, 0, rate, px, ST, PART_DRIP);
		}
		if (leafR >= 0.6F) {
			leafAt(0.55F, 1F, P);
			Drips.emit(m, null, P.x + 0.15F, P.y, P.z, DOWN, time, 1, rate, px, ST, PART_DRIP);
		}
		if (cuffS > 0.9F) Drips.emit(m, null, 0F, 9.3F, 0F, DOWN, time, 2, rate, px, ST, PART_DRIP);
		if (tier >= 2 && buttS >= 0.95F) Drips.emit(m, null, 0F, -9.2F, 0F, DOWN, time, 3, rate, px, ST, PART_DRIP);
	}

	/** Loose beads: two flicked off the point by a broken block, retract's and melt's, and a failing arm's orbiting the point. */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float br = d.breakAge();
		if (br < 6F && leafR > 0.5F) {
			leafAt(Math.min(leafR, 0.97F), 0F, P);
			for (int k = 0; k < 3; k++) {
				float a = br;
				float spread = (k - 1) * 0.5F;
				float x = P.x + 0.5F * spread * a + DOWN.x * 0.06F * a * a;
				float y = P.y + 0.7F * a + DOWN.y * 0.06F * a * a;
				float z = P.z + 0.25F * spread * a + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, x, y, z, 0.5F * (1F - a / 6F), k);
			}
		}
		float rt = d.retractTick();
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float born = 2F + 1.4F * k, a = rt - born;
				if (a < 0F || a > 3F) continue;
				float up = (1F - ArmMath.sm((born - 2F) / 7F)) * (LEAF_Y0 + LEAF_LEN + 0.6F);
				if (up > LEAF_Y0) {
					leafAt(ArmMath.clamp01((up - LEAF_Y0) / LEAF_LEN), 0F, P);
				} else {
					P.set(0F, Math.max(0F, Math.min(up, HAFT_Y1)), 0F);
				}
				FistGeometry.bead(d, layer, m, ST, k % 2 == 0 ? ArmLayer.BLACK : ArmLayer.RED, P.x + DOWN.x * 0.5F * a * a, P.y + DOWN.y * 0.5F * a * a,
						P.z + DOWN.z * 0.5F * a * a, 0.5F * (1F - a / 3F), 3 + k);
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
				float up = (1F - ArmMath.sm((born - 2F) / 14F)) * (LEAF_Y0 + LEAF_LEN + 0.6F);
				if (up > LEAF_Y0) {
					leafAt(ArmMath.clamp01((up - LEAF_Y0) / LEAF_LEN), 0F, P);
				} else {
					P.set(0F, Math.max(HAFT_Y0, Math.min(up, HAFT_Y1)), 0F);
				}
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, P.x + side * 1.2F + DOWN.x * 0.4F * a * a, P.y + DOWN.y * 0.4F * a * a,
						P.z + side * 0.6F + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			leafAt(0.85F, 0F, P);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * time + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, P.x + 0.6F * (float) Math.cos(a), P.y + 0.3F,
						P.z + 0.6F * (float) Math.sin(a), 0.5F, 16 + k);
			}
		}
	}

	// ------------------------------------------------------------------ LOD 2 and 3

	/** LOD 2 (about 50 quads): a haft box, the cuff, one slab for the leaf with its red midrib ribbon, the loop's box. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			if (yHi - yLo > 0.05F) m.box(null, -0.6F, yLo, -0.6F, 1.2F, yHi - yLo, 1.2F, ST.u0(PART_FAR, 0, 14F), ST.v0(PART_FAR, 0, 14F), 1F, 1F, m.tint());
			for (int k = 0; k < 2; k++) {
				if (RING_Y[k] < yHi && RING_S[k] > 0.02F) {
					m.box(null, -0.95F, RING_Y[k] - 0.2F, -0.95F, 1.9F, 0.4F, 1.9F, ST.u0(PART_RING, k, 14F), ST.v0(PART_RING, k, 14F), 1F, 1F, m.tint());
				}
			}
			if (cuffS > 0.02F) {
				LOCAL.identity().translate(0F, 10.2F, 0F).scale(cuffS);
				m.box(LOCAL, -1.1F, -0.8F, -0.9F, 2.2F, 1.6F, 1.8F, ST.u0(PART_CUFF, 0, 27F), ST.v0(PART_CUFF, 0, 27F), 1F, 1F, m.tint());
			}
			if (buttS > 0.02F) m.box(null, -2.1F, -9.0F, -0.3F, 4.2F, 4.0F, 0.6F, ST.u0(PART_LOOP, 0, 17F), ST.v0(PART_LOOP, 0, 17F), 1F, 1F, m.tint());
			float top = leafR;
			if (top > 0.03F) {
				Stations s = SLAB.reset(4).feed(PHI_LEAF);
				float arc = 0F, py = 0F;
				for (int i = 0; i < 4; i++) {
					float t = top * (0.02F + 0.98F * i / 3F);
					float h = Math.max(0.05F, width(Math.min(t, 1F)));
					POLY[0] = -h;
					POLY[1] = -0.15F;
					POLY[2] = h;
					POLY[3] = -0.15F;
					POLY[4] = h;
					POLY[5] = 0.15F;
					POLY[6] = -h;
					POLY[7] = 0.15F;
					axisAt(t, A);
					if (i > 0) arc += Math.abs(A.y - py);
					py = A.y;
					s.add(A.x, A.y, A.z, 0F, 1F, 0F, 1F, 0F, 0F, arc, POLY, 1F);
				}
				blackNow = black;
				Loft.sweep(m, s, layer, ALL_BLACK, ST, PART_SLAB, 1F, false, top < 0.999F);
			}
		}
		if (layer == red && leafR > 0.03F) {
			Stations s = MARGIN.reset(2).feed(PHI_LEAF);
			RIBBON[0] = -0.3F;
			RIBBON[1] = 0F;
			RIBBON[2] = 0.3F;
			RIBBON[3] = 0F;
			float arc = 0F, py = 0F;
			for (int i = 0; i <= 4; i++) {
				float t = leafR * (0.05F + 0.9F * i / 4F);
				axisAt(t, A);
				if (i > 0) arc += Math.abs(A.y - py);
				py = A.y;
				s.add(A.x, A.y, A.z + 0.2F, 0F, 1F, 0F, 1F, 0F, 0F, arc, RIBBON, 1F);
			}
			marginLayer = red;
			Loft.sweep(m, s, layer, MARGIN_FACES, ST, PART_MARGIN, 1F, false, false);
		}
	}

	/** LOD 3: four boxes (haft, socket, leaf, red midrib). */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black && yHi - yLo > 0.05F) m.box(null, -0.7F, yLo, -0.7F, 1.4F, yHi - yLo, 1.4F, ST.u0(PART_FAR, 0, 14F), ST.v0(PART_FAR, 0, 14F), 1F, 1F, m.tint());
		if (layer == black && cuffS > 0.02F) {
			LOCAL.identity().translate(0F, 10.2F, 0F).scale(cuffS);
			m.box(LOCAL, -1.2F, -0.9F, -1.0F, 2.4F, 1.8F, 2.0F, ST.u0(PART_FAR, 1, 27F), ST.v0(PART_FAR, 1, 27F), 1F, 1F, m.tint());
		}
		if (leafR > 0.03F) {
			float k = leafR;
			if (layer == black) {
				LOCAL.identity().translate(0F, LEAF_Y0 + 0.5F, 0F).scale(k);
				m.box(LOCAL, -2.3F, 0F, -0.3F, 4.6F, LEAF_LEN * 0.9F, 0.6F, ST.u0(PART_FAR, 2, 30F), ST.v0(PART_FAR, 2, 30F), 1F, 1F, m.tint());
			}
			if (layer == red) {
				LOCAL.identity().translate(0F, LEAF_Y0 + 0.5F, 0F).scale(k);
				m.box(LOCAL, -0.3F, 0F, 0.25F, 0.6F, LEAF_LEN * 0.9F, 0.3F, ST.u0(PART_FAR, 3, 30F), ST.v0(PART_FAR, 3, 30F), 1F, 1F, m.tint());
			}
		}
	}

	// ------------------------------------------------------------------ a strand with a layer per segment

	/**
	 * A four-sided strand through the first {@code n} of {@link #PTS} (half-widths in {@link #RAD} as corner radii,
	 * alphas in {@link #ALPHA}), segment i in {@code SEG[i]} and an end cap in {@link #capLayer} when asked; its frame
	 * starts from the hint and is carried along by parallel transport, so it never twists.
	 */
	private static void strand(SkinMesh m, int n, ArmLayer wanted, int part, float uvScale, float phi, float hx, float hy, float hz, boolean capEnd) {
		if (n < 2) return;
		boolean any = capEnd && capLayer == wanted;
		for (int i = 0; i + 1 < n && !any; i++) any = SEG[i] == wanted;
		if (!any) return;
		Stations s = STRAND.reset(4).feed(phi).hint(hx, hy, hz);
		float arc = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				float dx = PTS[3 * i] - PTS[3 * i - 3], dy = PTS[3 * i + 1] - PTS[3 * i - 2], dz = PTS[3 * i + 2] - PTS[3 * i - 1];
				arc += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			int a = i > 0 ? i - 1 : 0, b = i + 1 < n ? i + 1 : n - 1;
			ArmMath.ring(RING4, 4, RAD[i], RAD[i], ArmMath.PI / 4F);
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * b] - PTS[3 * a], PTS[3 * b + 1] - PTS[3 * a + 1], PTS[3 * b + 2] - PTS[3 * a + 2], arc,
					RING4, ALPHA[i]);
		}
		Loft.sweep(m, s, wanted, BY_SEGMENT, ST, part, uvScale, false, capEnd);
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		begin(d);
		if (leafR <= 0.05F) return out.set(0F, Math.max(0F, yHi), 0F);
		float t = which == ArmTips.MID ? 0.4F * Math.min(1F, leafR) : Math.min(0.99F, leafR);
		leafAt(t, 0F, P);
		out.set(P);
		out.x *= EDGE_SIGN;
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{3.4F, 15.2F, 1.0F}, {-3.0F, 14.0F, -1.0F}, {0.6F, 19.0F, 0.6F}, {2.6F, 11.0F, 1.2F}, {-2.6F, 11.0F, -1.2F}, {2.4F, -9.4F, 0.6F},
				{-2.4F, -9.4F, -0.6F}, {1.4F, -5.4F, 1.4F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0] * EDGE_SIGN, p[1], p[2]));
	}
}
