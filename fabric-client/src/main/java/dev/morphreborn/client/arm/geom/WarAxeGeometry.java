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
 * The war axe (section 2.11), a bearded medieval axe grown out of the arm: a haft of two tendrils twisted round a red
 * core and lashed under the head with spirals of rawhide-like tendril, a knotted cuff, and a head that is a fan. Six
 * curved ribs spread from the cuff like a bat's wing and are webbed with thin black membrane plates carrying red rune
 * strips; along the outside of the fan runs the crescent edge, a red band from the top horn round to a hooked beard,
 * chipped at two notches. Behind the cuff a knot of three thorns balances it (the poll), a thong with a frayed end
 * hangs from the butt and swings, and bristles of every length lean off the ribs, the haft and the lashings.
 * <p>
 * Grip space G (section 2.0), px; level 2 is the baseline. The axe is built with its edge on +X and drawn through an
 * X mirror ({@link #EDGE_SIGN}, the katana's), so in hand the edge faces down and leads the swing; the world-facing
 * inputs (down, the thong's nodes) are mirrored in, and the points and extents are mirrored back out. Level 1 has four
 * ribs, one haft strand, two lashings and no thong or runes; level 3 has seven ribs, a longer beard, a second hook on
 * the top horn, and its edge and runes glow.
 * <p>
 * Everything moves: the static crawls up the haft and out along the ribs, the twist and lashings turn, the ribs
 * breathe and tense, the edge band swells and runs with pulses, the bristles sway, the thong swings, goo drips off the
 * edge. Chopping (mining a log) bites the head and flares the edge; a broken block recoils it and flicks beads off the
 * edge; Cleave splays the plates 0.6 px apart for 6 ticks; Fell rattles the ribs and bristles the fur. Low biomass thins,
 * cracks and erodes it. Forming, the haft grows out of the fist and only then do the lashings, cuff, ribs, plates,
 * edge, runes and bristles come out, one after another.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class WarAxeGeometry implements FormGeometry {
	/** Which side of G the edge is on, as the katana's: the edge is built on +X and drawn mirrored. */
	static final float EDGE_SIGN = KatanaGeometry.EDGE_SIGN;
	/** The haft's radius under the fingers (the fist's grip). */
	public static final float HAFT_R = 0.95F;
	/** The haft from the butt to the top of its finial, and the twist's pitch. */
	private static final float HAFT_Y0 = -5.0F, HAFT_Y1 = 18.2F, TWIST = 6.5F, FINIAL_FROM = 16.2F;
	/** The head turns about here (the cuff's face) when it bites or recoils. */
	private static final float PIVOT_X = 1.4F, PIVOT_Y = 15.0F;
	/** Where the ribs start on the cuff's face. */
	private static final float RIB_X0 = 1.4F;
	/** The feed coordinate where the head starts, the ribs' length on it, and the lashings' heights. */
	private static final float PHI_HEAD = phiHaft(15.0F), RIB_PHI = 6.8F;
	private static final float[] LASH_Y = {3.2F, 8.6F, 12.1F};
	/** The edge band's control points by level (x0, y0, x1, y1, x2, y2): the top horn, the belly, the beard's toe. */
	private static final float[][] EDGE = {{6.0F, 17.2F, 8.2F, 13.4F, 4.8F, 9.6F}, {6.5F, 18.0F, 9.0F, 13.5F, 5.0F, 9.0F}, {6.7F, 18.4F, 9.4F, 13.3F, 5.0F, 7.5F}};
	private static final int ARC_N = 32;
	private static final float[][] EDGE_ARC_TABLE = new float[3][ARC_N + 1];
	/** Where along the edge each rib ends, how it bows (px of control point, + toward the top), its width and its sideways bow, by level. */
	private static final float[][] RIB_S = {{0.02F, 0.36F, 0.68F, 0.98F}, {0.03F, 0.22F, 0.41F, 0.60F, 0.79F, 0.98F},
			{0.02F, 0.18F, 0.35F, 0.51F, 0.67F, 0.83F, 0.99F}};
	private static final float[][] RIB_BEND = {{0.9F, 0.2F, -0.2F, -0.95F}, {0.9F, 0.55F, 0.2F, -0.15F, -0.55F, -0.95F},
			{1.0F, 0.65F, 0.3F, 0.0F, -0.3F, -0.65F, -1.05F}};
	private static final float[][] RIB_W = {{0.70F, 0.60F, 0.60F, 0.70F}, {0.62F, 0.55F, 0.50F, 0.50F, 0.55F, 0.66F},
			{0.62F, 0.55F, 0.50F, 0.46F, 0.50F, 0.55F, 0.66F}};
	private static final float[] RIB_Z = {0.14F, -0.14F, 0.12F, -0.12F, 0.14F, -0.14F, 0.12F};
	private static final int MAX_RIBS = 7;
	/** The plates' rows along the ribs (u), the first where a plate starts. */
	private static final float PLATE_FROM = 0.40F;
	/** The edge band's stations (s along the edge): irregular, with a station on each chip's point. */
	private static final float[] EDGE_S = {0F, 0.12F, 0.23F, 0.29F, 0.33F, 0.37F, 0.46F, 0.56F, 0.65F, 0.70F, 0.75F, 0.84F, 0.92F, 1F};
	private static final float[] EDGE_S_LOD1 = {0F, 0.17F, 0.33F, 0.50F, 0.70F, 0.85F, 1F};
	/** The chips (notches): where, how deep (+ out, − in) and how wide along s. */
	private static final float CHIP0_AT = 0.33F, CHIP0 = -0.15F, CHIP1_AT = 0.70F, CHIP1 = 0.12F, CHIP_W = 0.045F;
	/** Starving cracks along the edge (s). */
	private static final float[] CRACKS = {0.20F, 0.25F, 0.50F, 0.56F, 0.80F, 0.85F};
	/**
	 * The bristles' own characters, by place (3 on the top rib, 2 on the beard rib, 2 on the haft, 2 hanging off the
	 * lashings), so no two look alike: length, lean from the surface's normal (degrees), hook (radians more each
	 * segment), thickness, sideways lean out of the head's plane, and which of them fork.
	 */
	private static final float[] BR_LEN = {1.0F, 1.7F, 0.8F, 1.3F, 0.7F, 1.1F, 0.75F, 1.5F, 1.1F},
			BR_ANG = {32F, 50F, 22F, 44F, 28F, 40F, 18F, 12F, 20F},
			BR_CURL = {0.30F, -0.18F, 0.40F, 0.12F, -0.30F, 0.22F, 0F, 0.18F, -0.22F},
			BR_W = {0.85F, 1.10F, 0.70F, 1.00F, 0.75F, 0.90F, 0.70F, 0.80F, 0.70F},
			BR_SIDE = {0.12F, -0.10F, 0.20F, -0.18F, 0.06F, -0.15F, 0.14F, 0.10F, -0.12F};
	private static final boolean[] BR_FORK = {false, true, false, true, false, false, false, false, true};
	/** FORM: the first bristle sprouts at this tick, then one every {@code BR_STAGGER}. */
	private static final float BR_FROM = 10.6F, BR_STAGGER = 0.3F;
	/** The poll's thorns: direction (in the plane), length, lean out of it. */
	private static final float[] THORN_DX = {-0.6F, -1.0F, -0.7F}, THORN_DY = {0.8F, 0.1F, -0.7F}, THORN_LEN = {2.0F, 1.5F, 1.2F}, THORN_Z = {0.0F, 0.2F, -0.1F};

	// static parts, one per piece
	private static final int PART_CORE = 1, PART_STRAND = 2, PART_LASH = 5, PART_BUTT = 9, PART_TAIL = 10, PART_FRAY = 11, PART_CUFF = 13, PART_BOSS = 16,
			PART_RIB = 17, PART_PLATE = 25, PART_EDGE = 34, PART_RUNE = 35, PART_HOOK = 43, PART_POLL = 46, PART_THORN = 47, PART_BRISTLE = 50, PART_FORK = 60,
			PART_DRIP = 75, PART_BEADS = 76, PART_FAR = 77;

	private static final Static ST = new Static();
	private static final Stations STRAND = new Stations(), BOSS_ST = new Stations(), PLATE = new Stations(), EDGE_ST = new Stations(), RUNE_ST = new Stations();
	private static final float[] RING4 = new float[8], POLY = new float[16], RIBBON = new float[4];
	private static final float[] PTS = new float[3 * 40], RAD = new float[40], ALPHA = new float[40];
	private static final ArmLayer[] SEG = new ArmLayer[40];
	private static final boolean[] EDGE_CRACKED = new boolean[24];
	private static final Matrix4f LOCAL = new Matrix4f(), SPASM = new Matrix4f(), TOOL = new Matrix4f();
	private static final Vector3f DOWN = new Vector3f(), P = new Vector3f(), T = new Vector3f(), U = new Vector3f(), Q = new Vector3f(), R = new Vector3f(),
			A = new Vector3f(), B = new Vector3f(), M = new Vector3f(), EP = new Vector3f(), ET = new Vector3f(), RA = new Vector3f(), RB = new Vector3f(),
			RC = new Vector3f(), EA = new Vector3f(), EB = new Vector3f(), EC = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);
	private static final float[] TAIL = new float[3 * ArmDraw.ROPE_NODES];
	private static final Vector3f TAIL_ROOT = new Vector3f(0F, -5.4F, 0F), TAIL_ROOT_LOCAL = new Vector3f(), TAIL_OUT = new Vector3f(0.3F, -0.95F, 0F).normalize();
	private static final float[] BR_PTS = new float[12];

	static {
		for (int t = 0; t < 3; t++) {
			float[] e = EDGE[t];
			Vector3f a = new Vector3f(e[0], e[1], 0F), b = new Vector3f(e[2], e[3], 0F), c = new Vector3f(e[4], e[5], 0F), p = new Vector3f(), q = new Vector3f(a);
			float arc = 0F;
			EDGE_ARC_TABLE[t][0] = 0F;
			for (int i = 1; i <= ARC_N; i++) {
				ArmMath.bezier(a, b, c, i / (float) ARC_N, p);
				arc += p.distance(q);
				q.set(p);
				EDGE_ARC_TABLE[t][i] = arc;
			}
		}
	}

	// what the current emit has worked out (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, ti, lod, nRibs, bristleSeg;
	private static float time, feedLength, droop, headRot, headCos = 1F, headSin, splay, edgeFlare, fell, thin, fringeW, brFuzz;
	private static float yHi, yLo, cuffS, pollS, buttS, tailS, edgeR, hookS, hook2S, runeR, fringeS, gnaw, shake;
	private static final float[] LASH_S = new float[3], RIB_R = new float[MAX_RIBS], PLATE_R = new float[MAX_RIBS], BRISTLE_S = new float[9];
	private static boolean detailed, cutting, starving, failing;
	private static ArmLayer capLayer = ArmLayer.BLACK, edgeLayer = ArmLayer.RED, runeLayer = ArmLayer.RED, blackNow = ArmLayer.BLACK;
	private static final Loft.FaceLayer BY_SEGMENT = (seg, face) -> face < 0 ? capLayer : SEG[Math.clamp(seg, 0, SEG.length - 1)];
	private static final Loft.FaceLayer ALL_BLACK = (seg, face) -> blackNow;
	/** The edge band's faces: the two cutting faces (0 and 4) red (glowing at level 3), a starving crack black. */
	private static final Loft.FaceLayer EDGE_FACES = (seg, face) -> {
		if (face < 0) return edgeLayer;
		if (face == 0 || face == 4) return EDGE_CRACKED[Math.clamp(seg, 0, EDGE_CRACKED.length - 1)] ? blackNow : edgeLayer;
		return blackNow;
	};
	private static final Loft.FaceLayer RUNE_FACES = (seg, face) -> runeLayer;
	/** A lashing: a spiral of tendril round the haft, {@link #lashY} up for 1.4 px, 1.6 turns. */
	private static float lashY, lashTurn, lashScale, lashDir = 1F;
	private static final Loft.CurveFn LASH = (s, out) -> {
		float a = lashTurn + lashDir * ArmMath.TAU * 1.6F * s;
		float r = 1.12F * lashScale * (1F + 0.05F * (float) Math.sin(3F * a));
		out.set(r * (float) Math.cos(a), lashY + 1.4F * s * lashScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn LASH_W = s -> FistGeometry.width(0.55F * lashScale * (0.8F + 0.4F * (float) Math.sin(ArmMath.PI * s)), 4);
	private static float ringY, ringTurn;
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.18F * cuffS * (1F + 0.07F * (float) Math.cos(3F * (a - ringTurn)));
		out.set(r * (float) Math.cos(a), ringY + 0.08F * (float) Math.sin(3F * (a - ringTurn)), r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.5F * cuffS, 4);
	private static final Loft.CurveFn TAIL_CURVE = (s, out) -> tailPoint(s, out);
	private static final Loft.RadiusFn TAIL_W = s -> FistGeometry.width(ArmMath.lerp(0.5F, 0.28F, s), 4);
	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * (bristleSeg);
		int i = Math.min(bristleSeg - 1, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(BR_PTS[3 * i], BR_PTS[3 * i + 3], u), ArmMath.lerp(BR_PTS[3 * i + 1], BR_PTS[3 * i + 4], u),
				ArmMath.lerp(BR_PTS[3 * i + 2], BR_PTS[3 * i + 5], u));
	};
	private static final Loft.RadiusFn BR_RADIUS = s -> FistGeometry.width(ArmMath.lerp(0.32F, 0.12F, s) * brFuzz, 4);

	public WarAxeGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.AXE;
	}

	@Override
	public float feedLength(int tier) {
		return feedFor(tier);
	}
	
	private static float feedFor(int tier) {
		return PHI_HEAD + RIB_PHI + EDGE_ARC_TABLE[Math.clamp(tier, 1, 3) - 1][ARC_N];
	}

	@Override
	public float guiScale() {
		// the head's top horn (y 18.4) and the butt (y -6) keep to 1..15 px along the slot's diagonal
		return 0.62F;
	}

	@Override
	public float guiGripPx() {
		return 3.7F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The edge band's arc length at {@code tier} as drawn at rest (about 9.7 px at level 2): for the tests. */
	static float edgeArc(int tier) {
		return EDGE_ARC_TABLE[Math.clamp(tier, 1, 3) - 1][ARC_N];
	}

	/** The edge's stations as the last emit that drew the edge left them: for the tests. */
	static Stations lastEdge() {
		return EDGE_ST;
	}

	/** The ribs fanned at {@code tier}. */
	static int ribCount(int tier) {
		return RIB_S[Math.clamp(tier, 1, 3) - 1].length;
	}

	/** The feed coordinate on the haft at height {@code y}: up from the fist to the head, and down to the butt. */
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
		// first person: the arm itself (mirrored for the left hand), its veins beating with each chop
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

	/** Reads the draw: level, detail, low biomass, the reactions and the timeline's plan of how much of each piece is out. */
	private static void begin(ArmDraw d) {
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		ti = tier - 1;
		nRibs = RIB_S[ti].length;
		lod = Math.clamp(d.lod(), 0, 3);
		time = d.time();
		feedLength = feedFor(tier);
		d.down(DOWN);
		DOWN.x *= EDGE_SIGN; // into the mirrored build space
		droop = d.droop();
		int stage = d.lowStage();
		starving = stage >= ArmDraw.LOW_STARVING;
		failing = stage >= ArmDraw.LOW_FAILING;
		thin = stage >= ArmDraw.LOW_THINNING ? 0.85F : 1F;
		// chopping: a bite every 5 ticks; a broken block: the head tips and overshoots; Cleave: the plates splay; Fell: it rattles
		float bite = (float) Math.sin(ArmMath.TAU * time / 5F);
		gnaw = d.mining() ? Math.max(0F, bite) * Math.max(0F, bite) * Math.max(0F, bite) : 0F;
		float a = d.breakAge();
		float recoil;
		if (a < 2F) recoil = 0.10F * ArmMath.sm(a / 2F);
		else if (a < 3.5F) recoil = ArmMath.lerp(0.10F, -0.05F, ArmMath.sm((a - 2F) / 1.5F));
		else if (a < 5F) recoil = ArmMath.lerp(-0.05F, 0F, ArmMath.sm((a - 3.5F) / 1.5F));
		else recoil = 0F;
		headRot = recoil - 0.05F * gnaw;
		headCos = (float) Math.cos(headRot);
		headSin = (float) Math.sin(headRot);
		splay = cleave(d);
		fell = d.specialWeight(ArmProtocol.FX_FELL);
		edgeFlare = Math.max(Math.max(d.hitFlare(), 0.7F * gnaw), Math.max(0.6F * fell, splay));
		shake = fell * (float) Math.sin(1.9F * time);
		plan(d);
	}

	/** Cleave's plate splay, 0..1: out over 1.5 ticks, held, and closed by the sixth. */
	private static float cleave(ArmDraw d) {
		if (d.specialKind() != ArmProtocol.FX_CLEAVE || !d.specialRunning()) return 0F;
		float a = d.specialAge();
		return ArmMath.sm(a / 1.5F) * (1F - ArmMath.sm((a - 4F) / 2F));
	}

	/** How much of each piece is out (section 2.6 as I asked for it: the haft first, then the details). */
	private static void plan(ArmDraw d) {
		detailed = d.detailedForm() || d.state() == ArmProtocol.STATE_QUICKFORM;
		float ft = d.formTick();
		for (int k = 0; k < 3; k++) LASH_S[k] = 0F;
		if (detailed) {
			float r = ArmDraw.formReach(ft);
			yHi = HAFT_Y1 * r;
			yLo = HAFT_Y0 * r;
			for (int k = 0; k < 3; k++) LASH_S[k] = pop((ft - (8.0F + 0.7F * k)) / 2F);
			buttS = pop((ft - 8.2F) / 2F);
			tailS = ArmMath.sm((ft - 9F) / 3F);
			cuffS = pop((ft - 8.4F) / 2F);
			pollS = pop((ft - 8.8F) / 2F);
			for (int k = 0; k < MAX_RIBS; k++) RIB_R[k] = ArmMath.sm((ft - (9F + 0.4F * k)) / 2.4F);
			for (int j = 0; j < MAX_RIBS; j++) PLATE_R[j] = ArmMath.sm((ft - (10F + 0.35F * j)) / 1.8F);
			edgeR = ArmMath.sm((ft - 10.8F) / 1.8F);
			hookS = pop((ft - 12.2F) / 2F);
			hook2S = pop((ft - 12.6F) / 2F);
			runeR = ArmMath.sm((ft - 12.4F) / 1.2F);
			for (int k = 0; k < 9; k++) BRISTLE_S[k] = pop((ft - BR_FROM - BR_STAGGER * k) / 1.6F);
			fringeS = ArmMath.sm((ft - 11F) / 2F);
			cutting = ft < 12.5F;
		} else {
			float rr = d.reach();
			float up = rr * (HAFT_Y1 + 0.8F), down = rr * 5.8F;
			yHi = Math.min(HAFT_Y1, up);
			yLo = Math.max(HAFT_Y0, -down);
			for (int k = 0; k < 3; k++) LASH_S[k] = ArmMath.sm((up - (LASH_Y[k] + 0.6F)) / 0.8F);
			buttS = ArmMath.sm((down - 4.4F) / 0.8F);
			tailS = ArmMath.sm((down - 5.2F) / 0.6F);
			cuffS = ArmMath.sm((up - 13F) / 1.4F);
			pollS = ArmMath.sm((up - 13.8F) / 1.2F);
			float head = ArmMath.sm((up - 13.6F) / 4.8F);
			for (int k = 0; k < MAX_RIBS; k++) RIB_R[k] = head;
			for (int j = 0; j < MAX_RIBS; j++) PLATE_R[j] = head;
			edgeR = head;
			hookS = hook2S = ArmMath.sm((head - 0.85F) / 0.15F);
			runeR = head;
			for (int k = 0; k < 9; k++) BRISTLE_S[k] = ArmMath.sm((head - 0.85F) / 0.15F);
			fringeS = ArmMath.sm((up - 3.8F) / 0.8F);
			cutting = rr < 1F;
		}
		fringeW = d.fringe();
		if (failing) {
			// the edge's last 8% erodes away (cut off with dissolve off)
			edgeR = Math.min(edgeR, 0.92F);
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
		strands(d, layer, m);
		lashings(d, layer, m);
		butt(d, layer, m);
		cuff(d, layer, m);
		poll(d, layer, m);
		ribs(d, layer, m);
		plates(d, layer, m);
		edge(d, layer, m);
		if (lod == 0) hooks(d, layer, m);
		runes(d, layer, m);
		bristles(d, layer, m);
		beads(d, layer, m);
		if (lod == 0 && d.dripsNow() && (!detailed || d.formTick() >= 13F)) drips(d, layer, m);
	}

	// ------------------------------------------------------------------ the head's curves

	/** The edge band's centreline at {@code s} into {@link #EP}, its direction into {@link #ET} (unit, along s), with the head's turn applied. */
	private static void edgeAt(float s) {
		float[] e = EDGE[ti];
		EA.set(e[0], e[1], 0F);
		EB.set(e[2], e[3], 0F);
		EC.set(e[4], e[5], 0F);
		ArmMath.bezier(EA, EB, EC, s, EP);
		ArmMath.bezierTangent(EA, EB, EC, s, ET);
		if (ET.lengthSquared() < 1e-8F) ET.set(0F, -1F, 0F);
		ET.normalize();
		head(EP, true);
		rotateDir(ET);
	}

	/** The head's turn about the cuff and the melt's droop for a point of the head (x, y in G; z kept). */
	private static void head(Vector3f p, boolean sag) {
		float x = p.x - PIVOT_X, y = p.y - PIVOT_Y;
		p.x = PIVOT_X + x * headCos - y * headSin;
		p.y = PIVOT_Y + x * headSin + y * headCos;
		if (sag && droop > 0F) {
			float f = ArmMath.clamp01((p.y - HAFT_Y0) / (HAFT_Y1 - HAFT_Y0));
			p.fma(droop * f * f, DOWN);
		}
	}

	private static void rotateDir(Vector3f v) {
		float x = v.x * headCos - v.y * headSin, y = v.x * headSin + v.y * headCos;
		v.x = x;
		v.y = y;
	}

	/** The outward normal of the edge at its current {@link #ET}: right of the way down, toward +X. */
	private static void edgeNormal(Vector3f out) {
		out.set(-ET.y, ET.x, 0F).normalize();
	}

	/** The chip's inset at {@code s}: a V-shaped notch, in px (+ pushes the edge out). */
	private static float chip(float s) {
		float c0 = Math.max(0F, 1F - Math.abs(s - CHIP0_AT) / CHIP_W), c1 = Math.max(0F, 1F - Math.abs(s - CHIP1_AT) / CHIP_W);
		return CHIP0 * c0 + CHIP1 * c1;
	}

	/** The arc length along the edge at {@code s}. */
	private static float arcAt(float s) {
		float f = ArmMath.clamp01(s) * ARC_N;
		int i = Math.min(ARC_N - 1, (int) f);
		return ArmMath.lerp(EDGE_ARC_TABLE[ti][i], EDGE_ARC_TABLE[ti][i + 1], f - i);
	}

	/**
	 * Rib {@code k} at {@code u} (0 at the cuff, 1 at the edge) into {@code out}: a quadratic bow from its base on the
	 * cuff's face to the edge point it ends at, a hair of sideways bow so the fan isn't flat, the plates' splay for
	 * Cleave, the ribs' own writhe, and the head's turn.
	 */
	private static void ribAt(int k, float u, Vector3f out) {
		int n = nRibs;
		float by = 15.8F - 1.8F * k / (n - 1F);
		float[] e = EDGE[ti];
		RA.set(e[0], e[1], 0F);
		RB.set(e[2], e[3], 0F);
		RC.set(e[4], e[5], 0F);
		ArmMath.bezier(RA, RB, RC, RIB_S[ti][k], out); // the end, on the edge (before the head's turn)
		float ex = out.x, ey = out.y;
		float dx = ex - RIB_X0, dy = ey - by, len = (float) Math.sqrt(dx * dx + dy * dy);
		float px = -dy / len, py = dx / len;
		float bend = RIB_BEND[ti][k] * 1.6F * (1F + 0.12F * (float) Math.sin(0.05F * time + 1.7F * k)) + 0.25F * shake * ((k & 1) != 0 ? 1F : -1F);
		float cx = 0.5F * (RIB_X0 + ex) + px * bend, cy = 0.5F * (by + ey) + py * bend;
		float v = 1F - u;
		out.x = v * v * RIB_X0 + 2F * v * u * cx + u * u * ex;
		out.y = v * v * by + 2F * v * u * cy + u * u * ey;
		float env = (float) Math.sin(ArmMath.PI * Math.min(u, 1F));
		float wr = 0.05F * env * (float) Math.sin(cur.phase() + 5F * u + 0.31F * time + k);
		out.x += px * wr;
		out.y += py * wr;
		out.z = RIB_Z[k] * env + splay * 0.30F * ((k & 1) == 0 ? 1F : -1F) * ArmMath.sm(u) + 0.06F * env * (float) Math.cos(0.8F * cur.phase() + 3F * u + 0.23F * time);
		head(out, true);
	}

	/** Rib {@code k}'s point and unit direction at {@code u} into {@code p} and {@code t} (a short central difference). */
	private static void ribFrame(int k, float u, Vector3f p, Vector3f t) {
		ribAt(k, u, p);
		float u0 = Math.max(0F, u - 0.03F), u1 = u + 0.03F;
		ribAt(k, u1, Q);
		ribAt(k, u0, R);
		t.set(Q).sub(R);
		if (t.lengthSquared() < 1e-10F) t.set(1F, 0F, 0F);
		t.normalize();
	}

	/** A rib's width at {@code u}: its own at the cuff, thinning to 55% at the edge, thickened by pulses. */
	private static float ribWidth(int k, float u) {
		float swell = 1F + 0.25F * cur.swell(PHI_HEAD + RIB_PHI * u, feedLength);
		return ArmMath.lerp(RIB_W[ti][k], RIB_W[ti][k] * 0.55F, u) * thin * swell;
	}

	// ------------------------------------------------------------------ the haft: core, twisted strands, lashings, butt

	/** The red core under the strands, so red shows between their twists. */
	private static void core(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer != red || yHi - yLo < 0.05F) return;
		Stations s = STRAND.reset(4).feed(phiHaft(yLo));
		float top = Math.min(yHi, 16.9F);
		if (top - yLo < 0.05F) return;
		ArmMath.ring(RING4, 4, FistGeometry.width(0.8F, 4), FistGeometry.width(0.8F, 4), ArmMath.PI / 4F);
		s.add(0F, yLo, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 0F, RING4, 1F);
		s.add(0F, top, 0F, 0F, 1F, 0F, 1F, 0F, 0F, top - yLo, RING4, 1F);
		capLayer = red;
		SEG[0] = red;
		SEG[1] = red;
		Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_CORE, 1F, false, false);
	}

	/**
	 * Two strands twisted round the core ({@code ψ = πk + 2π(y+5)/6.5 + 0.03t}), their distance from the axis rising and
	 * falling out of step so they pass over and under; they run together into a point above the head (the finial);
	 * level 1 has one strand, and a starving crack at y 6..6.8 turns them red and thin.
	 */
	private static void strands(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (yHi - yLo < 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int count = tier <= 1 ? 1 : 2;
		int full = lod == 0 ? 14 : 7;
		int n = Math.max(2, Math.round((full - 1) * (yHi - yLo) / (HAFT_Y1 - HAFT_Y0)) + 1);
		boolean dissolve = d.dissolve();
		float tw = tier >= 3 ? 1.1F : 1F;
		for (int k = 0; k < count; k++) {
			float phase = ArmMath.PI * k;
			for (int i = 0; i < n; i++) {
				float y = ArmMath.lerp(yLo, yHi, i / (float) (n - 1));
				float psi = phase + ArmMath.TAU * (y + 5F) / TWIST + 0.03F * time;
				float r = 0.45F + 0.10F * (float) Math.sin(ArmMath.PI * (y + 5F) / (TWIST / 2F) + phase);
				float finial = y > FINIAL_FROM ? ArmMath.clamp01((HAFT_Y1 - y) / (HAFT_Y1 - FINIAL_FROM)) : 1F;
				boolean crack = starving && y >= 5.8F && y <= 7.0F;
				float w = ArmMath.lerp(0.78F, 0.60F, (y - HAFT_Y0) / (HAFT_Y1 - HAFT_Y0)) * thin * tw * (1F + d.swell(phiHaft(y), feedLength)) * (crack ? 0.75F : 1F)
						* Math.max(0.05F, finial);
				PTS[3 * i] = r * finial * (float) Math.cos(psi);
				PTS[3 * i + 1] = y;
				PTS[3 * i + 2] = r * finial * (float) Math.sin(psi);
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

	/** Rawhide lashings: tight spirals of tendril round the haft under the head, turning against each other. */
	private static void lashings(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		int first = tier <= 1 ? 1 : 0;
		for (int k = first; k < 3; k++) {
			float y = LASH_Y[k];
			if (y + 1.4F > yHi + 0.05F || y < yLo - 0.05F) continue;
			ArmLayer l = tier >= 3 && k == 2 ? red : black;
			if (layer != l) continue;
			lashScale = LASH_S[k];
			if (lashScale <= 0.02F) continue;
			lashY = y;
			lashDir = (k & 1) == 0 ? 1F : -1F;
			lashTurn = ((k & 1) == 0 ? 0.025F : -0.025F) * time + 2F * k;
			if (lod == 0) {
				Loft.tube(m, LASH, 0F, 1F, 7, 4, LASH_W, l, layer, ST, PART_LASH + k, 2F, phiHaft(y), phiHaft(y) + 1.4F, UP_Y, 1F);
			} else if (layer == black) {
				m.box(null, -1.05F * lashScale, y, -1.05F * lashScale, 2.1F * lashScale, 1.2F, 2.1F * lashScale, ST.u0(PART_LASH, k, 14F), ST.v0(PART_LASH, k, 14F), 1F, 1F,
						m.tint());
			}
		}
	}

	/** The butt's knot (with a smaller one under it) and the thong that hangs from it, fraying out at its end. */
	private static void butt(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || buttS <= 0.02F) return;
		LOCAL.identity().translate(0F, -5.3F, 0F).rotateY((float) Math.toRadians(15)).scale(buttS);
		m.box(LOCAL, -0.65F, -0.45F, -0.65F, 1.3F, 0.9F, 1.3F, ST.u0(PART_BUTT, 0, 17F), ST.v0(PART_BUTT, 0, 17F), 1F, 1F, m.tint());
		LOCAL.identity().translate(0.15F, -5.9F, 0.05F).rotateZ((float) Math.toRadians(-18)).scale(buttS);
		m.box(LOCAL, -0.45F, -0.35F, -0.45F, 0.9F, 0.7F, 0.9F, ST.u0(PART_BUTT, 1, 17.5F), ST.v0(PART_BUTT, 1, 17.5F), 1F, 1F, m.tint());
		if (lod > 0 || tier < 2 || tailS <= 0.02F) return;
		float[] nodes = d.ropeNodes();
		if (nodes == null || nodes.length < TAIL.length) {
			Rope.droop(TAIL_ROOT_LOCAL, TAIL_OUT, DOWN, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, TAIL);
		} else {
			System.arraycopy(nodes, 0, TAIL, 0, TAIL.length);
			for (int i = 0; i < TAIL.length; i += 3) TAIL[i] *= EDGE_SIGN; // the rope swings in G: into the build space
		}
		Loft.tube(m, TAIL_CURVE, 0F, tailS, 9, 4, TAIL_W, black, layer, ST, PART_TAIL, 2F, phiHaft(-5.4F), phiHaft(-5.4F) + 8F, UP_Z, 1F);
		if (tailS >= 0.95F) {
			// the frayed end: a knot, and two filaments splaying off it along the last link, turned either way
			tailPoint(1F, P);
			tailPoint(0.9F, Q);
			T.set(P).sub(Q);
			if (T.lengthSquared() < 1e-8F) T.set(DOWN);
			T.normalize();
			LOCAL.identity().translate(P);
			m.box(LOCAL, -0.35F, -0.35F, -0.35F, 0.7F, 0.7F, 0.7F, ST.u0(PART_FRAY, 0, 20F), ST.v0(PART_FRAY, 0, 20F), 1F, 1F, m.tint());
			for (int k = 0; k < 2; k++) {
				float a = (k == 0 ? 0.45F : -0.6F) + 0.25F * (float) Math.sin(0.11F * time + 2F * k);
				float c = (float) Math.cos(a), sn = (float) Math.sin(a);
				// the link's direction turned by a in the XY plane
				R.set(T.x * c - T.y * sn, T.x * sn + T.y * c, T.z);
				LOCAL.identity().translate(P).rotateTowards(R.x, R.y, R.z, 0F, 0F, 1F);
				m.frustum(LOCAL, k == 0 ? 1.4F : 1.0F, 0.34F, 0.34F, 0.06F, 0.06F, ST.u0(PART_FRAY, 1 + k, 20F), ST.v0(PART_FRAY, 1 + k, 20F), 2F, 1F, 1F, m.tint());
			}
		}
	}

	/** A point on the thong at {@code s}: Catmull-Rom through the rope's nodes, from the butt. */
	private static void tailPoint(float s, Vector3f out) {
		int links = ArmDraw.ROPE_NODES - 1;
		float f = ArmMath.clamp01(s) * links;
		int i = Math.min(links - 1, (int) f);
		float u = f - i;
		int i0 = Math.max(0, i - 1), i2 = Math.min(links, i + 1), i3 = Math.min(links, i + 2);
		out.set(TAIL_ROOT.x + catmull(TAIL[3 * i0], TAIL[3 * i], TAIL[3 * i2], TAIL[3 * i3], u),
				TAIL_ROOT.y + catmull(TAIL[3 * i0 + 1], TAIL[3 * i + 1], TAIL[3 * i2 + 1], TAIL[3 * i3 + 1], u),
				TAIL_ROOT.z + catmull(TAIL[3 * i0 + 2], TAIL[3 * i + 2], TAIL[3 * i2 + 2], TAIL[3 * i3 + 2], u));
	}

	private static float catmull(float p0, float p1, float p2, float p3, float u) {
		float u2 = u * u, u3 = u2 * u;
		return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
	}

	// ------------------------------------------------------------------ the cuff and the poll

	/** The cuff the ribs fan from: a ring above and a ring below, and a knobbed boss between them. */
	private static void cuff(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || cuffS <= 0.02F) return;
		for (int k = 0; k < 2; k++) {
			ringY = k == 0 ? 13.1F : 16.9F;
			ringTurn = (k == 0 ? 0.03F : -0.03F) * time + 1.3F * k;
			Loft.closedTube(m, RING, lod == 0 ? 10 : 6, 4, RING_W, black, layer, ST, PART_CUFF + k, 2F, phiHaft(ringY), UP_Y, 1F);
		}
		float k = cuffS;
		// the boss: a six-sided pillar bulging in the middle, every corner swelling on its own
		Stations bs = BOSS_ST.reset(6).feed(phiHaft(13.4F));
		for (int i = 0; i < 3; i++) {
			float y = 13.4F + 1.6F * i;
			float base = (i == 1 ? 1.5F : 1.15F + 0.05F * i) * k;
			for (int c = 0; c < 6; c++) {
				float a = ArmMath.PI / 6F + c * ArmMath.PI / 3F;
				float r = base * (1F + 0.10F * (float) Math.sin(cur.phase() + 2.7F * c + 0.2F * time + i));
				POLY[2 * c] = r * (float) Math.cos(a);
				POLY[2 * c + 1] = r * (float) Math.sin(a);
			}
			bs.add(0.2F, y, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 1.6F * i, POLY, 1F);
		}
		blackNow = black;
		Loft.sweep(m, bs, layer, ALL_BLACK, ST, PART_BOSS, 1F, false, false);
		if (lod > 1) return;
		LOCAL.identity().translate(0.9F, 16.1F, 0.1F).rotateZ((float) Math.toRadians(-14)).scale(k);
		m.box(LOCAL, -0.8F, -0.55F, -0.7F, 1.6F, 1.1F, 1.4F, ST.u0(PART_BOSS, 1, PHI_HEAD), ST.v0(PART_BOSS, 1, PHI_HEAD), 1F, 1F, m.tint());
		LOCAL.identity().translate(0.8F, 14.0F, -0.1F).rotateZ((float) Math.toRadians(11)).scale(k);
		m.box(LOCAL, -0.7F, -0.5F, -0.7F, 1.4F, 1.0F, 1.4F, ST.u0(PART_BOSS, 2, PHI_HEAD), ST.v0(PART_BOSS, 2, PHI_HEAD), 1F, 1F, m.tint());
	}

	/** The poll behind the cuff: a knot of two lumps and three thorns of different lengths and leans, swaying a little. */
	private static void poll(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || pollS <= 0.02F) return;
		float k = pollS;
		LOCAL.identity().translate(-2.1F, 15.1F, 0F).rotateZ((float) Math.toRadians(12)).scale(k);
		m.box(LOCAL, -0.75F, -0.7F, -0.65F, 1.5F, 1.4F, 1.3F, ST.u0(PART_POLL, 0, PHI_HEAD), ST.v0(PART_POLL, 0, PHI_HEAD), 1F, 1F, m.tint());
		if (lod > 0) return;
		LOCAL.identity().translate(-3.0F, 16.0F, 0.1F).rotateZ((float) Math.toRadians(-20)).scale(k);
		m.box(LOCAL, -0.5F, -0.45F, -0.5F, 1.0F, 0.9F, 1.0F, ST.u0(PART_POLL, 1, PHI_HEAD), ST.v0(PART_POLL, 1, PHI_HEAD), 1F, 1F, m.tint());
		int thorns = tier <= 1 ? 1 : 3;
		for (int i = 0; i < thorns; i++) {
			float sway = 0.12F * (float) Math.sin(0.13F * time + 2.3F * i);
			float dx = THORN_DX[i], dy = THORN_DY[i] + sway, dz = THORN_Z[i];
			float len = THORN_LEN[i] * k * (1F + 0.4F * fell);
			LOCAL.identity().translate(-2.5F + 0.2F * i, 15.1F + 0.4F * (1 - i), 0F).rotateTowards(dx, dy, dz, 0F, 0F, 1F);
			m.frustum(LOCAL, len, 0.62F - 0.08F * i, 0.62F - 0.08F * i, 0.06F, 0.06F, ST.u0(PART_THORN, i, PHI_HEAD), ST.v0(PART_THORN, i, PHI_HEAD), 2F, 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ the head: ribs, plates, edge, hooks, runes

	/** Six (at level 2) curved ribs fanning from the cuff, each ending in the edge band; growing from the cuff outward. */
	private static void ribs(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		boolean fringe = d.dissolve() && cutting;
		for (int k = 0; k < nRibs; k++) {
			float rr = RIB_R[k];
			if (rr <= 0.03F) continue;
			int n = lod == 0 ? 5 : 4;
			float len = 7.5F;
			for (int i = 0; i < n; i++) {
				float u = rr * i / (n - 1F);
				ribAt(k, u, P);
				PTS[3 * i] = P.x;
				PTS[3 * i + 1] = P.y;
				PTS[3 * i + 2] = P.z;
				RAD[i] = FistGeometry.width(ribWidth(k, u), 4);
				ALPHA[i] = fringe && rr < 1F ? ArmMath.fringe((rr - u) * len, fringeW) : 1F;
				if (i > 0) SEG[i - 1] = black;
			}
			capLayer = black;
			strand(m, n, layer, PART_RIB + k, 1.5F, PHI_HEAD, 0F, 0F, 1F, rr < 0.999F);
		}
	}

	/**
	 * The membrane plates between neighbouring ribs, thin black slabs that start a little way out from the cuff and
	 * follow the ribs to the edge; Cleave twists them apart (the ribs alternate ±0.3 px in depth).
	 */
	private static void plates(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		boolean fringe = d.dissolve() && cutting;
		int rows = lod == 0 ? 3 : 2;
		for (int j = 0; j + 1 < nRibs; j++) {
			float pr = PLATE_R[j];
			float uEnd = Math.min(PLATE_FROM + (1F - PLATE_FROM) * pr, Math.min(RIB_R[j], RIB_R[j + 1]));
			if (uEnd < PLATE_FROM + 0.05F) continue;
			Stations s = PLATE.reset(4).feed(PHI_HEAD);
			float arc = 0F;
			float px = 0F, py = 0F, pz = 0F;
			for (int i = 0; i < rows; i++) {
				float u = PLATE_FROM + (uEnd - PLATE_FROM) * i / (rows - 1F);
				plateCentre(j, u, M, T, U);
				if (i > 0) arc += (float) Math.sqrt((M.x - px) * (M.x - px) + (M.y - py) * (M.y - py) + (M.z - pz) * (M.z - pz));
				px = M.x;
				py = M.y;
				pz = M.z;
				float hw = 0.5F * U.length(), ht = 0.15F * thin * (1F + 0.2F * cur.swell(PHI_HEAD + RIB_PHI * u, feedLength));
				POLY[0] = -hw;
				POLY[1] = -ht;
				POLY[2] = hw;
				POLY[3] = -ht;
				POLY[4] = hw;
				POLY[5] = ht;
				POLY[6] = -hw;
				POLY[7] = ht;
				float alpha = fringe && uEnd < 0.999F ? ArmMath.fringe((uEnd - u) * 7.5F, fringeW) : 1F;
				s.add(M.x, M.y, M.z, T.x, T.y, T.z, U.x, U.y, U.z, arc, POLY, alpha);
			}
			blackNow = black;
			Loft.sweep(m, s, layer, ALL_BLACK, ST, PART_PLATE + j, 1F, false, uEnd < 0.999F);
		}
	}

	/** The middle of plate {@code j} at {@code u} into {@code centre}, the way it runs into {@code t} and the span across it (rib to rib) into {@code across}. */
	private static void plateCentre(int j, float u, Vector3f centre, Vector3f t, Vector3f across) {
		ribAt(j, u, centre);
		ribAt(j + 1, u, Q);
		across.set(Q).sub(centre);
		centre.add(Q).mul(0.5F);
		// the way along: the middle's difference a step out and back
		float a = Math.max(0F, u - 0.03F), b = u + 0.03F;
		ribAt(j, b, Q);
		ribAt(j + 1, b, R);
		t.set(Q).add(R).mul(0.5F);
		ribAt(j, a, Q);
		ribAt(j + 1, a, R);
		Q.add(R).mul(0.5F);
		t.sub(Q);
		if (t.lengthSquared() < 1e-10F) t.set(1F, 0F, 0F);
		t.normalize();
		if (across.lengthSquared() < 1e-8F) across.set(0F, 0.1F, 0F);
	}

	/**
	 * The crescent edge: a five-sided band along the outside of the fan, its two cutting faces red (glowing at level 3),
	 * chipped at two notches, tapering to points at the top horn and the beard's toe; it breathes and swells as pulses run
	 * round it, flares with a hit, a chop or a Fell, and is cut at the growth front while it draws out.
	 */
	private static void edge(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (edgeR <= 0.03F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean glowEdge = tier >= 3 && d.glow();
		edgeLayer = glowEdge ? ArmLayer.GLOW : red;
		if (layer != black && layer != red && layer != edgeLayer && !Loft.pulseGlowPass(layer)) return;
		blackNow = black;
		float[] list = lod == 0 ? EDGE_S : EDGE_S_LOD1;
		Stations s = EDGE_ST.reset(5).feed(PHI_HEAD + RIB_PHI);
		float last = Math.min(edgeR, 1F);
		boolean truncated = last < 0.999F;
		boolean fringe = d.dissolve() && cutting;
		float glowBright = ArmMath.clamp01(0.55F + 0.45F * edgeFlare);
		s.color(layer == ArmLayer.GLOW ? FistGeometry.grey(glowBright) : -1);
		float frontArc = arcAt(last);
		int count = 0;
		float prevS = 0F;
		for (int i = 0; i <= list.length; i++) {
			float f = i < list.length ? list[i] : last;
			if (i < list.length && f >= last - 1e-3F) continue;
			edgeAt(f);
			edgeNormal(U);
			float v = arcAt(f);
			float amp = d.swell(PHI_HEAD + RIB_PHI + v, feedLength);
			float taper = (float) Math.sqrt(Math.max(0.0, 1.0 - Math.pow(2.0 * f - 1.0, 8)));
			if (truncated && i == list.length) taper = Math.max(taper, 0.6F);
			float crack = starving ? crackWeight(f) : 0F;
			float swellE = 1F + 0.25F * amp + 0.3F * edgeFlare;
			float fOut = (0.5F * swellE + chip(f) - 0.4F * crack + (failing ? 0.12F * (float) Math.sin(1.9F * v - 0.5F * time + d.phase()) : 0F)) * taper;
			float hs = 0.30F * thin * taper * (1F + 0.15F * edgeFlare), hb = 0.20F * thin * taper;
			// F, S+, B+, B−, S− in (n along the outward normal, b along Z)
			POLY[0] = fOut;
			POLY[1] = 0F;
			POLY[2] = 0.12F * taper;
			POLY[3] = hs;
			POLY[4] = -0.38F * taper;
			POLY[5] = hb;
			POLY[6] = -0.38F * taper;
			POLY[7] = -hb;
			POLY[8] = 0.12F * taper;
			POLY[9] = -hs;
			float alpha = fringe ? ArmMath.fringe(frontArc - v, fringeW) : 1F;
			if (crack > 0.5F && d.dissolve()) alpha = Math.min(alpha, 0.72F);
			s.add(EP.x, EP.y, EP.z, ET.x, ET.y, ET.z, U.x, U.y, U.z, v, POLY, alpha);
			if (count > 0) EDGE_CRACKED[Math.min(EDGE_CRACKED.length - 1, count - 1)] = starving && crackWeight(0.5F * (f + prevS)) > 0.4F;
			prevS = f;
			count++;
		}
		if (count < 2) return;
		Loft.sweep(m, s, layer, EDGE_FACES, ST, PART_EDGE, 1F, false, truncated);
	}

	/** How deep in a starving crack {@code s} is: 1 inside, easing out over 0.02. */
	private static float crackWeight(float s) {
		float w = 0F;
		for (int i = 0; i < CRACKS.length; i += 2) {
			float a = CRACKS[i], b = CRACKS[i + 1];
			if (s >= a - 0.02F && s <= b + 0.02F) w = Math.max(w, s < a ? 1F - (a - s) / 0.02F : s > b ? 1F - (s - b) / 0.02F : 1F);
		}
		return w;
	}

	/**
	 * The hooks: the beard's toe curls back in on itself (level 2 up), and level 3's top horn curls out and forward; each a
	 * tapering tendril of six points that tightens and loosens a little.
	 */
	private static void hooks(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || tier < 2) return;
		for (int h = 0; h < (tier >= 3 ? 2 : 1); h++) {
			float grow = h == 0 ? hookS : hook2S;
			if (grow <= 0.03F || edgeR < 0.95F) continue;
			float at = h == 0 ? 1F : 0F;
			edgeAt(at);
			// the edge leaves its end along ET (the beard) or against it (the horn), and curls round
			float dirx = h == 0 ? ET.x : -ET.x, diry = h == 0 ? ET.y : -ET.y;
			float ang = (float) Math.atan2(diry, dirx);
			float turn = (h == 0 ? -0.55F : 0.5F) * (1F + 0.12F * (float) Math.sin(0.07F * time + h));
			float px = EP.x, py = EP.y, pz = 0F;
			int n = 6;
			for (int i = 0; i < n; i++) {
				PTS[3 * i] = px;
				PTS[3 * i + 1] = py;
				PTS[3 * i + 2] = pz;
				float u = i / (n - 1F);
				RAD[i] = FistGeometry.width(ArmMath.lerp(0.36F, 0.08F, u) * grow * thin, 4);
				ALPHA[i] = 1F;
				if (i > 0) SEG[i - 1] = black;
				ang += turn;
				float step = (h == 0 ? 0.7F : 0.55F) * grow;
				px += (float) Math.cos(ang) * step;
				py += (float) Math.sin(ang) * step;
				pz += 0.03F * (float) Math.sin(ang + h);
			}
			capLayer = black;
			strand(m, n, layer, PART_HOOK + h, 2F, PHI_HEAD + RIB_PHI + (h == 0 ? EDGE_ARC_TABLE[ti][ARC_N] : 0F), 0F, 0F, 1F, false);
		}
	}

	/**
	 * Red rune strips zigzagging up each plate's face (+Z; both faces at level 3), brightening and widening as pulses and
	 * hits pass; at level 3 they glow. They draw on from the cuff as the plates fill.
	 */
	private static void runes(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (lod > 0 || tier < 2 || runeR <= 0.03F) return;
		boolean glow = tier >= 3 && d.glow();
		runeLayer = glow ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != runeLayer) return;
		int faces = tier >= 3 ? 2 : 1;
		for (int j = 0; j + 1 < nRibs; j++) {
			float uEnd = Math.min(PLATE_FROM + (1F - PLATE_FROM) * PLATE_R[j], Math.min(RIB_R[j], RIB_R[j + 1])) - 0.04F;
			for (int f = 0; f < faces; f++) {
				float side = f == 0 ? 1F : -1F;
				int pts = 0;
				for (int i = 0; i < 7; i++) {
					float u = 0.50F + 0.075F * i;
					if (u > uEnd || u > 0.95F) break;
					float across = 0.5F + 0.27F * ((((i + j) & 1) == 0) ? -1F : 1F) * (1F - 0.15F * i / 6F);
					ribAt(j, u, A);
					ribAt(j + 1, u, B);
					P.set(A).lerp(B, across);
					P.z += side * 0.19F;
					PTS[3 * pts] = P.x;
					PTS[3 * pts + 1] = P.y;
					PTS[3 * pts + 2] = P.z;
					pts++;
				}
				if (pts < 2) continue;
				float amp = d.swell(PHI_HEAD + RIB_PHI * 0.75F, feedLength);
				float bright = ArmMath.clamp01(0.60F + 0.40F * Math.min(1F, 1.6F * amp) + 0.4F * edgeFlare);
				float width = 0.30F * (1F + 0.8F * Math.min(1F, amp)) * runeR;
				Stations s = RUNE_ST.reset(2).feed(PHI_HEAD + RIB_PHI * 0.5F);
				s.color(FistGeometry.grey(layer == ArmLayer.GLOW ? bright : 0.7F + 0.3F * bright));
				RIBBON[0] = -width / 2F;
				RIBBON[1] = 0F;
				RIBBON[2] = width / 2F;
				RIBBON[3] = 0F;
				float arc = 0F;
				for (int i = 0; i < pts; i++) {
					int a = Math.max(0, i - 1), z = Math.min(pts - 1, i + 1);
					float tx = PTS[3 * z] - PTS[3 * a], ty = PTS[3 * z + 1] - PTS[3 * a + 1], tz = PTS[3 * z + 2] - PTS[3 * a + 2];
					if (i > 0) arc += (float) Math.sqrt((PTS[3 * i] - PTS[3 * i - 3]) * (PTS[3 * i] - PTS[3 * i - 3]) + (PTS[3 * i + 1] - PTS[3 * i - 2]) * (PTS[3 * i + 1] - PTS[3 * i - 2])
							+ (PTS[3 * i + 2] - PTS[3 * i - 1]) * (PTS[3 * i + 2] - PTS[3 * i - 1]));
					// the ribbon lies in the plate: its width across the way it runs, square to the plate's normal (±Z)
					R.set(tx, ty, tz).cross(0F, 0F, side);
					s.add(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], tx, ty, tz, R.x, R.y, R.z, arc, RIBBON, 1F);
				}
				Loft.sweep(m, s, layer, RUNE_FACES, ST, PART_RUNE + j * 2 + f, 2F, false, false);
			}
		}
	}

	// ------------------------------------------------------------------ bristles

	/**
	 * Bristles of every kind: three along the top rib's back, two along the beard rib's, two on the haft under the
	 * lashings and two hanging off the lowest lashing. Each has its own length, lean, hook, thickness and sideways lean
	 * from the tables, some fork, they sway, stream back in a swing, bristle straight out when a Fell begins, and grow in
	 * one after another from the cuff out. None sits at the edge's ends, so the tips stay clean.
	 */
	private static void bristles(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		float thinFuzz = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int k = 0; k < 9; k++) {
			if (lod > 0 && BR_LEN[k] < 1.0F) continue;
			float grow = BRISTLE_S[k] * (k >= 7 ? fringeS : 1F);
			if (grow <= 0.02F) continue;
			// the base, the surface's outward direction (ox..), and the way it leans (lx..)
			float bx, by, bz, ox, oy, oz, lx, ly, lz;
			if (k < 5) {
				int rib = k < 3 ? 0 : nRibs - 1;
				float u = k == 0 ? 0.30F : k == 1 ? 0.55F : k == 2 ? 0.80F : k == 3 ? 0.45F : 0.78F;
				if (RIB_R[rib] < u + 0.1F) continue;
				ribFrame(rib, u, P, T);
				float sgn = k < 3 ? 1F : -1F;
				ox = -T.y * sgn;
				oy = T.x * sgn;
				oz = 0F;
				float off = 0.45F * ribWidth(rib, u);
				bx = P.x + ox * off;
				by = P.y + oy * off;
				bz = P.z;
				lx = -T.x;
				ly = -T.y;
				lz = -T.z;
			} else if (k < 7) {
				float y = k == 5 ? 10.4F : 5.6F;
				if (y > yHi - 0.4F) continue;
				float sgn = k == 5 ? 1F : -1F;
				bx = 0.62F * sgn;
				by = y;
				bz = 0.3F * sgn;
				ox = sgn;
				oy = 0F;
				oz = 0.25F;
				lx = 0F;
				ly = -1F;
				lz = 0F;
			} else {
				if (tier < 2 || LASH_Y[0] > yHi - 0.4F) continue;
				float sgn = k == 7 ? 1F : -1F;
				bx = 0.55F * sgn;
				by = LASH_Y[0] + 0.3F;
				bz = 0.5F * sgn;
				ox = DOWN.x;
				oy = DOWN.y;
				oz = DOWN.z;
				lx = 0F;
				ly = 0F;
				lz = sgn;
			}
			float bristle = fell;
			float len = (1.6F + 0.5F * (float) Math.sin(0.09F * time + 1.9F * k) + 1.5F * d.surge()) * BR_LEN[k] * (1F + 0.4F * swing) * thinFuzz;
			len = ArmMath.lerp(len, 3F * BR_LEN[k], bristle) * grow;
			float base = (float) Math.toRadians(BR_ANG[k]);
			float side = BR_SIDE[k] * (1F - bristle);
			int segs = BR_LEN[k] >= 1.0F && lod == 0 ? 3 : 2;
			spinePoints(bx, by, bz, ox, oy, oz, lx, ly, lz, len, base, BR_CURL[k], side, k, swing, bristle, segs);
			brFuzz = BR_W[k];
			float phi = PHI_HEAD - 3F;
			Loft.tube(m, POLYLINE, 0F, 1F, segs + 1, 4, BR_RADIUS, black, layer, ST, PART_BRISTLE + k, 2F, phi, phi + 2F, UP_Z, 1F);
			if (BR_FORK[k] && lod == 0) {
				// a small barb off the first third, swept further back and the other way sideways
				float fx = BR_PTS[3], fy = BR_PTS[4], fz = BR_PTS[5];
				spinePoints(fx, fy, fz, ox, oy, oz, lx, ly, lz, 0.45F * len, base + 0.75F, -BR_CURL[k], -side, k + 5, swing, bristle, 2);
				brFuzz = 0.6F * BR_W[k];
				Loft.tube(m, POLYLINE, 0F, 1F, 3, 4, BR_RADIUS, black, layer, ST, PART_FORK + k, 2F, phi + 0.7F, phi + 1.6F, UP_Z, 1F);
			}
		}
		brFuzz = 1F;
	}

	/**
	 * A bristle's points into {@link #BR_PTS} from ({@code x, y, z}): leaving along {@code o}, leaning {@code base} toward
	 * {@code l}, hooking {@code curl} more each segment, swaying, streaming further back in a swing, straightening out
	 * when bristling, and leaning {@code side} out of the head's plane.
	 */
	private static void spinePoints(float x, float y, float z, float ox, float oy, float oz, float lx, float ly, float lz, float len, float base, float curl,
			float side, int k, float swing, float bristle, int segs) {
		bristleSeg = segs;
		BR_PTS[0] = x;
		BR_PTS[1] = y;
		BR_PTS[2] = z;
		float step = len / segs;
		for (int j = 1; j <= segs; j++) {
			float sway = 0.35F * (float) Math.sin(0.15F * time + 1.7F * k + 0.5F * j) * (j / (float) segs);
			float ang = base + curl * (j - 1) + sway + (float) Math.toRadians(50) * swing;
			ang *= 1F - bristle;
			float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
			x += (ox * c + lx * sn) * step;
			y += (oy * c + ly * sn) * step;
			z += (oz * c + lz * sn + side) * step;
			BR_PTS[3 * j] = x;
			BR_PTS[3 * j + 1] = y;
			BR_PTS[3 * j + 2] = z;
		}
	}

	// ------------------------------------------------------------------ drips, beads

	/** Goo off the edge (two places), the beard's toe and the thong's end, each only while its piece is out. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate(), px = d.blocksToPx();
		if (edgeR < 0.98F) return;
		for (int j = 0; j < 2; j++) {
			edgeAt(j == 0 ? 0.30F : 0.72F);
			edgeNormal(U);
			float out = U.dot(DOWN) >= 0F ? 0.5F : -0.38F;
			Drips.emit(m, null, EP.x + U.x * out, EP.y + U.y * out, EP.z, DOWN, time, j + 4 * (d.seed() & 1), rate, px, ST, PART_DRIP);
		}
		edgeAt(1F);
		Drips.emit(m, null, EP.x, EP.y, EP.z, DOWN, time, 2, rate, px, ST, PART_DRIP);
		if (tier >= 2 && tailS >= 0.95F && buttS >= 0.9F) {
			tailPoint(1F, P);
			Drips.emit(m, null, P.x, P.y, P.z, DOWN, time, 3, rate, px, ST, PART_DRIP);
		}
	}

	/**
	 * Loose beads: two flicked off the edge by a hit, beads dropping off the head as it retracts, the melt's off its
	 * erosion front, and a failing arm's three beads orbiting where the edge's end was.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = d.hitAge();
		if (hitAge >= 0F && hitAge < 8F && edgeR > 0.5F) {
			edgeAt(0.5F);
			edgeNormal(U);
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float x = EP.x + U.x * (0.5F + 0.8F * a + 0.3F * j) + ET.x * 0.4F * j + DOWN.x * 0.06F * a * a;
				float y = EP.y + U.y * (0.5F + 0.8F * a + 0.3F * j) + ET.y * 0.4F * j + DOWN.y * 0.06F * a * a;
				float z = 0.15F * (j == 0 ? 1F : -1F) * a + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
			}
		}
		float rt = d.retractTick();
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float born = 2F + 1.4F * k, a = rt - born;
				if (a < 0F || a > 3F) continue;
				float up = (1F - ArmMath.sm((born - 2F) / 7F)) * (HAFT_Y1 + 0.8F);
				if (up > 14F) {
					edgeAt(ArmMath.clamp01((up - 14F) / 4.4F));
					P.set(EP);
				} else {
					P.set(0F, Math.max(0F, Math.min(up, HAFT_Y1)), 0F);
				}
				FistGeometry.bead(d, layer, m, ST, k % 2 == 0 ? ArmLayer.BLACK : ArmLayer.RED, P.x + DOWN.x * 0.5F * a * a, P.y + DOWN.y * 0.5F * a * a,
						P.z + DOWN.z * 0.5F * a * a, 0.5F * (1F - a / 3F), 2 + k);
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
				float up = (1F - ArmMath.sm((born - 2F) / 14F)) * (HAFT_Y1 + 0.8F);
				if (up > 14F) {
					edgeAt(ArmMath.clamp01((up - 14F) / 4.4F));
					P.set(EP);
				} else {
					P.set(0F, Math.max(HAFT_Y0, Math.min(up, HAFT_Y1)), 0F);
				}
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, P.x + side * 1.2F + DOWN.x * 0.4F * a * a, P.y + DOWN.y * 0.4F * a * a,
						P.z + side * 0.6F + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			edgeAt(0.92F);
			edgeNormal(U);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * time + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, EP.x + 0.6F * (float) Math.cos(a) * U.x, EP.y + 0.6F * (float) Math.cos(a) * U.y,
						0.6F * (float) Math.sin(a), 0.5F, 16 + k);
			}
		}
	}

	// ------------------------------------------------------------------ LOD 2 and 3

	/** LOD 2 (about 60 quads): a haft box, the cuff, a slab for the fan with a red edge ribbon, four rib boxes, the poll. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			if (yHi - yLo > 0.05F) m.box(null, -0.6F, yLo, -0.6F, 1.2F, Math.min(yHi, 17F) - yLo, 1.2F, ST.u0(PART_FAR, 0, 14F), ST.v0(PART_FAR, 0, 14F), 1F, 1F, m.tint());
			if (cuffS > 0.02F) {
				LOCAL.identity().translate(0.4F, 15F, 0F).scale(cuffS);
				m.box(LOCAL, -1.5F, -1.5F, -0.9F, 3.0F, 3.0F, 1.8F, ST.u0(PART_BOSS, 0, 27F), ST.v0(PART_BOSS, 0, 27F), 1F, 1F, m.tint());
			}
			if (pollS > 0.02F) {
				LOCAL.identity().translate(-2.4F, 15.4F, 0F).scale(pollS);
				m.box(LOCAL, -0.8F, -0.8F, -0.6F, 1.6F, 1.6F, 1.2F, ST.u0(PART_POLL, 0, 27F), ST.v0(PART_POLL, 0, 27F), 1F, 1F, m.tint());
			}
			if (buttS > 0.02F) m.box(null, -0.65F, -5.8F, -0.65F, 1.3F, 0.9F, 1.3F, ST.u0(PART_BUTT, 0, 17F), ST.v0(PART_BUTT, 0, 17F), 1F, 1F, m.tint());
			// the fan: one slab along the edge's chord, thin, four stations
			if (edgeR > 0.03F) {
				Stations s = PLATE.reset(4).feed(PHI_HEAD);
				float arc = 0F, px = 0F, py = 0F;
				for (int i = 0; i < 4; i++) {
					float sv = edgeR * i / 3F;
					edgeAt(sv);
					A.set(EP);
					float hx = ArmMath.lerp(RIB_X0, A.x, 0.5F), hy = ArmMath.lerp(15F, A.y, 0.5F);
					float half = 0.5F * (float) Math.hypot(A.x - RIB_X0, A.y - 15F) * 0.9F;
					if (i > 0) arc += (float) Math.hypot(hx - px, hy - py);
					px = hx;
					py = hy;
					POLY[0] = half;
					POLY[1] = -0.15F;
					POLY[2] = half;
					POLY[3] = 0.15F;
					POLY[4] = -half;
					POLY[5] = 0.15F;
					POLY[6] = -half;
					POLY[7] = -0.15F;
					// each slab section runs from the cuff to the edge point: centre on the chord, N along it
					float tx = A.x - RIB_X0, ty = A.y - 15F;
					s.add(hx, hy, 0F, ty, -tx, 0F, tx, ty, 0F, arc, POLY, 1F);
				}
				blackNow = black;
				Loft.sweep(m, s, layer, ALL_BLACK, ST, PART_PLATE, 1F, false, false);
			}
		}
		if (edgeR > 0.03F && layer == red) {
			Stations s = EDGE_ST.reset(2).feed(PHI_HEAD + RIB_PHI);
			RIBBON[0] = -0.35F;
			RIBBON[1] = 0F;
			RIBBON[2] = 0.35F;
			RIBBON[3] = 0F;
			float arc = 0F, px = 0F, py = 0F;
			for (int i = 0; i <= 5; i++) {
				float sv = edgeR * i / 5F;
				edgeAt(sv);
				edgeNormal(U);
				float x = EP.x + U.x * 0.3F, y = EP.y + U.y * 0.3F;
				if (i > 0) arc += (float) Math.hypot(x - px, y - py);
				px = x;
				py = y;
				s.add(x, y, 0F, ET.x, ET.y, 0F, 0F, 0F, 1F, arc, RIBBON, 1F);
			}
			capLayer = red;
			Loft.sweep(m, s, layer, (seg, face) -> red, ST, PART_EDGE, 1F, false, false);
		}
	}

	/** LOD 3: four boxes (haft, cuff, head slab, red edge). */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black && yHi - yLo > 0.05F) m.box(null, -0.7F, yLo, -0.7F, 1.4F, yHi - yLo, 1.4F, ST.u0(PART_FAR, 0, 14F), ST.v0(PART_FAR, 0, 14F), 1F, 1F, m.tint());
		if (layer == black && cuffS > 0.02F) {
			LOCAL.identity().translate(0.4F, 15F, 0F).scale(cuffS);
			m.box(LOCAL, -1.6F, -1.5F, -1.0F, 3.2F, 3.0F, 2.0F, ST.u0(PART_FAR, 1, 27F), ST.v0(PART_FAR, 1, 27F), 1F, 1F, m.tint());
		}
		if (edgeR > 0.03F) {
			float k = edgeR;
			if (layer == black) {
				LOCAL.identity().translate(RIB_X0, 14.2F, 0F).scale(k);
				m.box(LOCAL, 0F, -1.0F, -0.3F, 5.0F, 5.5F, 0.6F, ST.u0(PART_FAR, 2, 30F), ST.v0(PART_FAR, 2, 30F), 1F, 1F, m.tint());
			}
			if (layer == red) {
				LOCAL.identity().translate(RIB_X0, 14.2F, 0F).scale(k);
				m.box(LOCAL, 4.9F, -1.4F, -0.25F, 0.8F, 6.2F, 0.5F, ST.u0(PART_FAR, 3, 30F), ST.v0(PART_FAR, 3, 30F), 1F, 1F, m.tint());
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
		if (edgeR <= 0.05F) return out.set(0F, Math.max(0F, yHi), 0F);
		if (which == ArmTips.MID) {
			ribAt(nRibs / 2, 0.6F, P);
			out.set(P);
		} else {
			edgeAt(Math.min(0.5F, edgeR));
			edgeNormal(U);
			out.set(EP).fma(0.4F, U);
		}
		out.x *= EDGE_SIGN;
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{8.1F, 18.6F, 0.8F}, {8.4F, 12.0F, -0.8F}, {5.2F, 7.2F, 0.8F}, {-4.4F, 17.2F, 1.2F}, {-1.4F, 14.2F, -1.2F}, {1.0F, -7.0F, 1.0F},
				{-1.6F, -6.8F, -1.6F}, {1.6F, 19.0F, 1.6F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0] * EDGE_SIGN, p[1], p[2]));
	}
}
