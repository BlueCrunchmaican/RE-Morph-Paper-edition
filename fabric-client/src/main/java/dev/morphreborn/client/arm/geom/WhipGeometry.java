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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The whip (section 9): a short braided grip with a long barbed lash that hangs from it in a loose coil. The grip is a
 * red core under braided black strands, banded with rings, ending in a knotted butt with a wrist loop below and a collar
 * above, crowned with thorns and a hooked thumb thorn on the leading (−X) side. The lash leaves the collar and falls in a
 * helix, a braided cord of 1, 2 or 3 strands (by tier) over a red core, tapering from 1.6 px to 0.2 px across 40
 * stations, studded with knots near its base, fraying hairs along its coil, and, on its last third, hooked barbs that
 * each have their own length, lean, roll, hook and thickness, a few forked. Level 3's barbs glow.
 * <p>
 * Grip space G (section 2.0), px. The leading side is built directly on −X (a whip has no edge; the thumb thorn and the
 * barbs' lean are what lead the swing), so there is no X mirror here. World-facing inputs (world down, the rope's nodes)
 * are already in G.
 * <p>
 * The lash is the idle {@link Rope} the spec asks for: the renderer simulates a hanging chain at the grip's pommel
 * ({@code ArmDraw.ropeNodes}, 6 nodes of 1.6 px), and the lash takes its shape from that chain, scaled to links of 2.4 px
 * and carried on to 10 nodes by a relaxing continuation, so the coil swings and lags with the arm. Where nothing
 * simulates (GUI icons, the preview, far arms) it hangs in a still droop with a slow sway of its own.
 * <p>
 * Casting a line gathers the lash up (it becomes the line's anchor), and so does the Lash special, which throws the lash out
 * as a world effect; both are read from what every modded viewer has (the fishing hook in the world and S_ARM_FX kind 13,
 * recorded by {@link #markCast} and {@link #markLash}), never from local input.
 * <p>
 * Stateless between calls apart from those marks; render thread only (shared scratch).
 */
public final class WhipGeometry implements FormGeometry {
	/** The grip's ends and where the lash leaves it (G px). */
	public static final float HANDLE_Y0 = -5.4F, HANDLE_Y1 = 4.2F, LASH_Y = 4.7F;
	/** Stations along the lash at full detail, and nodes in its chain. */
	public static final int STATIONS = 40, NODES = 10;
	/** Chain link in px (spec 9: 2.4) and the nominal hang of the lash: nine links. */
	public static final float LINK = 2.4F;
	public static final float HANG = (NODES - 1) * LINK;
	/** The cord's full width at the grip end and at the tip (spec 9: 1.6 to 0.2 px). */
	public static final float WIDTH0 = 1.6F, WIDTH1 = 0.2F;
	/** The wrap's radius under the fingers. */
	public static final float HANDLE_R = 0.95F;
	/** How far from the grip's axis the lash's centreline is kept (px): the grip's braid and rings, and the cord's own width. */
	private static final float GRIP_CLEAR = 2.3F;
	/** The strands end here (a station index at full detail): the last third of the lash is a single cord. */
	private static final int STRAND_END = 26;
	/** The hang and the coil, by tier: the lash's share of {@link #HANG}, the coil's radius (px) and its turns. */
	private static final float[] HANG_SHARE = {0F, 0.88F, 1F, 1.14F}, COIL_R = {0F, 1.2F, 1.5F, 1.8F}, TURNS = {0F, 2.0F, 2.5F, 3.0F};
	/** The path's length along the coil by tier (set below), and the feed coordinate where the lash leaves the grip. */
	private static final float[] PATH = new float[4];
	private static final float PHI_LASH = phiHandle(LASH_Y);

	/**
	 * Each barb's own character, by its place on the lash (base to tip): length (× 0.9 px), lean back from the cord (degrees),
	 * roll round the cord (degrees from the helix's outward side), hook sideways (radians), thickness, and whether a small
	 * barb forks off it. A jitter moves each along the lash. Nothing like a regular comb.
	 */
	private static final float[] BARB_LEN = {0.9F, 1.5F, 0.7F, 1.7F, 1.1F, 0.8F, 1.35F},
			BARB_LEAN = {28F, 41F, 22F, 52F, 34F, 26F, 47F},
			BARB_ROLL = {0F, 168F, 24F, 205F, -18F, 150F, 12F},
			BARB_HOOK = {0.5F, -0.3F, 0.7F, 0.15F, -0.6F, 0.4F, -0.1F},
			BARB_W = {1.0F, 1.3F, 0.8F, 1.25F, 0.9F, 1.4F, 1.0F},
			BARB_JIT = {0.00F, 0.012F, -0.01F, 0.02F, -0.006F, 0.014F, -0.012F};
	private static final boolean[] BARB_FORK = {false, true, false, true, false, false, true};
	/** The fraying hairs: where on the lash (0..1), how long, which way round, how far they lean along it. */
	private static final float[] HAIR_AT = {0.05F, 0.14F, 0.27F, 0.40F, 0.52F, 0.60F, 0.78F},
			HAIR_LEN = {1.0F, 1.7F, 0.8F, 2.1F, 1.2F, 0.9F, 1.6F},
			HAIR_ROLL = {40F, 230F, 120F, 310F, 75F, 195F, 5F},
			HAIR_LEAN = {0.4F, -0.2F, 0.7F, 0.1F, -0.5F, 0.3F, -0.1F};
	/** The knots (turk's heads) near the lash's base: where (0..1) and how big. */
	private static final float[] KNOT_AT = {0.09F, 0.20F, 0.33F}, KNOT_SIZE = {1.0F, 0.85F, 1.15F};
	/** The grip's rings by tier (y px). */
	private static final float[][] RINGS = {{}, {-3.6F}, {-3.6F, 2.6F}, {-4.4F, -3.2F, 2.6F}};
	/** The crown thorns round the collar: angle (radians from +X), tilt off the axis, length, base width. */
	private static final float[] CROWN_ANGLE = {3.14F, 0.9F, -1.1F, 2.2F, -2.4F}, CROWN_TILT = {0.55F, 0.9F, 0.4F, 0.75F, 0.6F},
			CROWN_LEN = {2.3F, 1.5F, 1.2F, 1.9F, 1.0F}, CROWN_W = {0.65F, 0.5F, 0.45F, 0.55F, 0.42F};
	/** Starving cracks along the lash (0..1 of its length) and on the grip (y px). */
	private static final float[] LASH_CRACKS = {0.20F, 0.24F, 0.45F, 0.50F, 0.62F, 0.66F};
	private static final float GRIP_CRACK0 = 1.6F, GRIP_CRACK1 = 2.2F;

	// static parts, one per piece (each segment of a piece then takes its own offset)
	private static final int PART_CORE = 1, PART_STRAND = 2, PART_HCORE = 6, PART_HBRAID = 7, PART_RING = 11, PART_COLLAR = 15, PART_CROWN = 16, PART_KNOB = 22,
			PART_THUMB = 24, PART_BUTT = 26, PART_LOOP = 27, PART_KNOT = 28, PART_BARB = 32, PART_FORK = 40, PART_HAIR = 48, PART_DRIP = 60, PART_BEADS = 61,
			PART_FAR = 62;

	private static final Static ST = new Static();
	private static final Stations CORE = new Stations(), STRAND = new Stations();
	private static final float[] RING4 = new float[8];
	private static final Matrix4f LOCAL = new Matrix4f(), BASIS = new Matrix4f(), SPASM = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f DOWN = new Vector3f(), P = new Vector3f(), T = new Vector3f(), N = new Vector3f(), B = new Vector3f(), O = new Vector3f(),
			W = new Vector3f(), D1 = new Vector3f(), D2 = new Vector3f(), Q = new Vector3f(), SIDE = new Vector3f(), ARCH = new Vector3f();
	private static final Vector3f ZERO = new Vector3f(), OUT = new Vector3f(-0.35F, 0.65F, 0F).normalize(), UP_Y = new Vector3f(0F, 1F, 0F),
			UP_Z = new Vector3f(0F, 0F, 1F);
	/** The chain (10 nodes, G px from the lash's root) and a scratch for the nodes' source. */
	private static final float[] CH = new float[3 * NODES], SRC = new float[3 * NODES];
	/** The lash's stations: u along it, centre, frame, helix path, arc, width and alpha. */
	private static final float[] U = new float[STATIONS + 2], CX = new float[3 * (STATIONS + 2)], TX = new float[3 * (STATIONS + 2)], NX = new float[3 * (STATIONS + 2)],
			BX = new float[3 * (STATIONS + 2)], QX = new float[3 * (STATIONS + 2)], QARC = new float[STATIONS + 2], QW = new float[STATIONS + 2],
			QA = new float[STATIONS + 2];
	private static final float[] PTS = new float[3 * 48], RAD = new float[48], ALPHA = new float[48];
	private static final ArmLayer[] SEG = new ArmLayer[48];
	private static final boolean[] CRACKED = new boolean[STATIONS + 2];
	private static ArmLayer capLayer = ArmLayer.BLACK;

	// what the current emit has worked out (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, lod, nL;
	private static float time, ph, feedLength, droop, reach, up, yHi, yLo, frontFrac, hangPx, linkPx, thin, lashScale, swingS, hitF, ft, rt, castS, lashS;
	private static boolean detailed, starving, failing, fringeOn;
	private static float fringeW, loopS, collarS, thumbS, ringS, ringY, ringTurn, breath, loopTurn;

	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 0.98F * (1F + 0.06F * (float) Math.cos(3F * (a - ringTurn))) * ringS;
		out.set(r * (float) Math.cos(a), ringY + 0.08F * (float) Math.sin(3F * (a - ringTurn)) * ringS, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.40F * ringS, 4);
	private static final Loft.CurveFn COLLAR = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.25F * breath * collarS * (1F + 0.07F * (float) Math.cos(2F * a + 0.4F));
		out.set(r * (float) Math.cos(a), 4.3F + 0.1F * (float) Math.sin(2F * a), r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn COLLAR_W = s -> FistGeometry.width(0.5F * collarS, 4);
	/** The wrist loop: a ring in the XY plane under the butt, tilting about Y as it swings. */
	private static final Loft.CurveFn LOOP = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.6F * loopS * (1F + 0.05F * (float) Math.sin(3F * a + loopTurn));
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		out.set(r * c * (float) Math.cos(loopTurn), -7.0F + r * sn, r * c * (float) Math.sin(loopTurn)); // its top runs through the butt's knot
	};
	private static final Loft.RadiusFn LOOP_W = s -> FistGeometry.width(0.55F * loopS, 4);
	private static final Loft.FaceLayer BY_SEGMENT = (seg, face) -> face < 0 ? capLayer : SEG[Math.clamp(seg, 0, SEG.length - 1)];

	static {
		for (int t = 1; t <= 3; t++) {
			float hang = HANG * HANG_SHARE[t], len = 0F, px = 0F, py = 0F, pz = 0F;
			for (int j = 0; j <= 400; j++) {
				float u = j / 400F;
				float a = ArmMath.TAU * TURNS[t] * u;
				float r = COIL_R[t] * profile(u);
				float x = r * (float) Math.cos(a), y = -u * hang, z = r * (float) Math.sin(a);
				if (j > 0) len += (float) Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
				px = x;
				py = y;
				pz = z;
			}
			PATH[t] = len;
		}
	}

	public WhipGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.WHIP;
	}

	@Override
	public float feedLength(int tier) {
		return PHI_LASH + PATH[Math.clamp(tier, 1, 3)];
	}

	@Override
	public float guiScale() {
		return 1.0F;
	}

	@Override
	public float guiGripPx() {
		return 7.2F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The lash's path length along its coil at {@code tier}, in px (the feed coordinate's lash part). */
	public static float pathLength(int tier) {
		return PATH[Math.clamp(tier, 1, 3)];
	}

	/** How many barbs the lash carries at {@code tier}: one every 2.2 px over its last third. */
	public static int barbCount(int tier) {
		return Math.clamp(Math.round(PATH[Math.clamp(tier, 1, 3)] / 3F / 2.2F), 3, BARB_LEN.length);
	}

	/** The cord's full width at {@code u} (0..1 along the lash): 1.6 px tapering to 0.2. */
	public static float cordWidth(float u) {
		return ArmMath.lerp(WIDTH0, WIDTH1, (float) Math.pow(ArmMath.clamp01(u), 0.85));
	}

	/** The coil's radius profile along the lash: nothing at the collar, full by a fifth of the way, easing in toward the tip. */
	static float profile(float u) {
		return ArmMath.sm(u / 0.22F) * (1F - 0.7F * ArmMath.sm((u - 0.4F) / 0.6F));
	}

	/** The feed coordinate on the grip at height {@code y}: up from the fist to the collar, and down to the butt. */
	static float phiHandle(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	/** Barb {@code k}'s character: length, lean, roll, hook and width (the tables above): for the tests. */
	static float[] barbCharacter(int k) {
		return new float[] {BARB_LEN[k], BARB_LEAN[k], BARB_ROLL[k], BARB_HOOK[k], BARB_W[k]};
	}

	/** The lash's stations as the last emit left them (their count, centres, path and arcs): for the tests. */
	static int lastStations() {
		return nL;
	}

	static float lastU(int i) {
		return U[i];
	}

	static float lastWidth(int i) {
		return QW[i];
	}

	static float lastArc(int i) {
		return QARC[i];
	}

	static Vector3f lastPath(int i, Vector3f out) {
		return out.set(QX[3 * i], QX[3 * i + 1], QX[3 * i + 2]);
	}

	// ------------------------------------------------------------------ what every viewer knows: a cast line, a thrown lash

	private static final Map<Integer, float[]> CASTS = new HashMap<>();
	private static final Map<Integer, Float> LASHES = new HashMap<>();
	/** Per owner: when a whip move began and how long the hand's lash stays coiled away for it (ticks). */
	private static final Map<Integer, float[]> HOLDS = new HashMap<>();
	/** The arm clock wraps hourly (ArmDrawFactory.CLOCK_WRAP): an age is read the short way round it. */
	private static final float WRAP = 72000F;

	/** An age on the wrapping arm clock, the short way round. */
	public static float ageOf(float now, float then) {
		float a = now - then;
		if (a < -WRAP / 2F) a += WRAP;
		else if (a > WRAP / 2F) a -= WRAP;
		return a;
	}

	/**
	 * {@code owner}'s fishing hook is out and drawn as a whip's line at {@code now} (arm clock): the lash gathers up, to
	 * leave the line's end at the grip. Called each frame such a hook is drawn.
	 */
	public static void markCast(int owner, float now) {
		float[] c = CASTS.get(owner);
		if (c == null || ageOf(now, c[1]) > 12F) CASTS.put(owner, new float[] {now, now});
		else c[1] = now;
	}

	/** How far the cast line has gathered {@code owner}'s lash at {@code now}: 0 none, 1 all the way; eased in and out. */
	static float castAmount(int owner, float now) {
		float[] c = CASTS.get(owner);
		if (c == null) return 0F;
		float since = ageOf(now, c[1]);
		float rise = ArmMath.sm(ageOf(c[1], c[0]) / 4F);
		if (since < -1F) return 0F;
		if (since <= 1.5F) return ArmMath.sm(ageOf(now, c[0]) / 4F);
		return rise * (1F - ArmMath.sm((since - 1.5F) / 4F));
	}

	/** {@code owner} threw the Lash special at {@code now}: the lash in hand coils away as the world's takes its place. */
	public static void markLash(int owner, float now) {
		LASHES.put(owner, now);
	}

	/**
	 * {@code owner} began one of the whip's moves at {@code now} (FX_WHIP_MOVE, decided 2026-11-16) that keeps
	 * the world's lash out for {@code holdTicks} (a snare holds it round its creature): the lash in hand coils away in 2
	 * ticks, stays gone for the hold and comes back over the 8 after it.
	 */
	public static void markHold(int owner, float now, float holdTicks) {
		float hold = Math.max(2F, holdTicks);
		float[] old = HOLDS.get(owner);
		if (old != null) {
			// a short move must not cut a long snare's hold short: keep the later end, from the old start so the lash does not re-coil
			float since = ageOf(now, old[0]);
			if (since >= 0F && since <= old[1]) {
				old[1] = Math.max(old[1], since + hold);
				return;
			}
		}
		HOLDS.put(owner, new float[] {now, hold});
	}

	/** The thrown lash's hold on the hand's lash at {@code now}, 0..1: gone in 2 ticks, held to tick 6, back by tick 16; or a move's hold. */
	static float lashWeight(int owner, float now) {
		float w = 0F;
		Float at = LASHES.get(owner);
		if (at != null) {
			float a = ageOf(now, at);
			if (a >= 0F && a <= 20F) w = a < 2F ? ArmMath.sm(a / 2F) : a < 6F ? 1F : 1F - ArmMath.sm((a - 6F) / 10F);
		}
		float[] h = HOLDS.get(owner);
		if (h != null) {
			float a = ageOf(now, h[0]);
			if (a >= 0F && a <= h[1] + 8F) w = Math.max(w, a < 2F ? ArmMath.sm(a / 2F) : a < h[1] ? 1F : 1F - ArmMath.sm((a - h[1]) / 8F));
		}
		return w;
	}

	/** Forgets every mark (joining or leaving a world). */
	public static void clearMarks() {
		CASTS.clear();
		LASHES.clear();
		HOLDS.clear();
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (d == null || layer == ArmLayer.SENSE) return;
		begin(d);
		ST.set(time, d.staticCadence(), d.crawlDir(), d.seed());
		if (d.firstPerson()) {
			FistGeometry.mirror(m, d.leftHand());
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, HANDLE_R, feedLength);
			FistGeometry.mirror(m, false);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm();
		m.transform(sp != 1F ? SPASM.scaling(sp) : null);
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	/** Reads the draw: tier, detail, low biomass, the reactions, how much of each piece is out, and the lash's shape. */
	private static void begin(ArmDraw d) {
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		lod = Math.clamp(d.lod(), 0, 3);
		time = d.time();
		ph = d.phase();
		feedLength = PHI_LASH + PATH[tier];
		d.down(DOWN);
		droop = d.droop();
		int stage = d.lowStage();
		starving = stage >= ArmDraw.LOW_STARVING;
		failing = stage >= ArmDraw.LOW_FAILING;
		thin = stage >= ArmDraw.LOW_THINNING ? 0.85F : 1F;
		hangPx = HANG * HANG_SHARE[tier];
		linkPx = LINK * HANG_SHARE[tier];
		reach = ArmMath.clamp01(d.reach());
		detailed = d.detailedForm();
		ft = d.formTick();
		rt = d.retractTick();
		float total = LASH_Y + hangPx;
		up = reach * total;
		yHi = Math.min(HANDLE_Y1, up);
		// the butt end comes out of the fist 1.6× as fast, as the pickaxe's does
		yLo = -Math.min(-HANDLE_Y0, 1.6F * reach * -HANDLE_Y0);
		frontFrac = ArmMath.clamp01((up - LASH_Y) / hangPx);
		fringeOn = d.dissolve() && (reach < 1F || failing);
		fringeW = d.fringe();
		if (failing) {
			// the lash's last 12% erodes (cut off with dissolve off), a ragged front
			frontFrac = Math.min(frontFrac, 0.88F);
			fringeW = Math.max(fringeW, 1.7F);
		}
		swingS = (float) Math.sin(Math.PI * ArmMath.clamp01(d.swing()));
		hitF = d.hitFlare();
		castS = 0F;
		lashS = 0F;
		if (!d.gui()) {
			castS = castAmount(d.seed(), time);
			lashS = Math.max(lashWeight(d.seed(), time), d.specialWeight(ArmProtocol.FX_WHIP));
		}
		// the lash gathers up for a cast, for the thrown lash, and in an icon (a short coil beside the grip)
		lashScale = Math.max(0.12F, (1F - 0.7F * castS) * (1F - 0.85F * lashS) * (d.gui() ? 0.45F : 1F));
		breath = 1F + 0.02F * (float) Math.sin(0.04F * time);
		loopTurn = 0.45F * (float) Math.sin(0.07F * time + ph);
		chain(d);
		lash(d);
	}

	// ------------------------------------------------------------------ the lash: chain, centreline, helix

	/**
	 * Fills {@link #CH}: the lash's ten nodes from its root (G px). The renderer's simulated chain gives the first six
	 * (scaled from 1.6 px links to ours), a droop stands in where there is none, and the last nodes relax from the chain's
	 * direction toward world down with a slow sway. A stiff arch lifts the first links away from the collar, the lash's
	 * scale gathers it, and a melt sinks it toward world down.
	 */
	private static void chain(ArmDraw d) {
		float[] nodes = d.ropeNodes();
		int have;
		int in = nodes == null ? 0 : nodes.length / 3;
		if (in >= 2) {
			have = Math.min(in, NODES);
			float sc = in >= NODES ? linkPx / LINK : linkPx / ArmDraw.ROPE_LINK_PX;
			for (int i = 0; i < have; i++) {
				CH[3 * i] = (nodes[3 * i] - nodes[0]) * sc;
				CH[3 * i + 1] = (nodes[3 * i + 1] - nodes[1]) * sc;
				CH[3 * i + 2] = (nodes[3 * i + 2] - nodes[2]) * sc;
			}
		} else {
			have = 6;
			Rope.droop(ZERO, OUT, DOWN, have, linkPx, SRC);
			System.arraycopy(SRC, 0, CH, 0, 3 * have);
		}
		// the rest: each link relaxes toward world down, swaying
		SIDE.set(DOWN).cross(0F, 0F, 1F);
		if (SIDE.lengthSquared() < 1e-4F) SIDE.set(1F, 0F, 0F);
		SIDE.normalize();
		D1.set(CH[3 * have - 3] - CH[3 * have - 6], CH[3 * have - 2] - CH[3 * have - 5], CH[3 * have - 1] - CH[3 * have - 4]);
		if (D1.lengthSquared() < 1e-8F) D1.set(DOWN);
		D1.normalize();
		for (int i = have; i < NODES; i++) {
			float sway = 0.22F * (float) Math.sin(0.13F * time + 0.9F * i + ph);
			D1.mul(0.7F).fma(0.3F, DOWN).fma(sway, SIDE).normalize();
			CH[3 * i] = CH[3 * i - 3] + D1.x * linkPx;
			CH[3 * i + 1] = CH[3 * i - 2] + D1.y * linkPx;
			CH[3 * i + 2] = CH[3 * i - 1] + D1.z * linkPx;
		}
		// the stiff arch: away from gravity and out to +X, a half sine over the first eight links
		ARCH.set(DOWN).mul(-0.8F).add(0.35F, 0F, 0F).normalize();
		float total = LASH_Y + hangPx;
		for (int i = 1; i < NODES; i++) {
			float h = 2.2F * (float) Math.sin(Math.PI * ArmMath.clamp01(i / 8F)) * (d.gui() ? 0.5F : 1F);
			float wob = 0.3F * (i / 9F) * (float) Math.sin(0.09F * time + 0.8F * i + ph);
			float x = CH[3 * i] + ARCH.x * h + SIDE.x * wob, y = CH[3 * i + 1] + ARCH.y * h + SIDE.y * wob, z = CH[3 * i + 2] + ARCH.z * h + SIDE.z * wob;
			x *= lashScale;
			y *= lashScale;
			z *= lashScale;
			if (droop > 0F) {
				float f = (LASH_Y + i * linkPx * lashScale) / total;
				float s = droop * f * f;
				x += DOWN.x * s;
				y += DOWN.y * s;
				z += DOWN.z * s;
			}
			CH[3 * i] = x;
			CH[3 * i + 1] = y;
			CH[3 * i + 2] = z;
		}
		keepOffGrip(d);
	}

	/**
	 * Keeps the lash out of the grip (I saw it fall straight down through the handle): a node inside a capsule
	 * round the grip, the butt and its loop is pushed straight out from the grip's axis, to the side it already leans (the
	 * arch's side when it hangs dead on the axis), so the lash drapes over and round the grip. The clearance grows from
	 * the collar's own radius at the root to the grip's plus the coil's within a few px, so the lash still leaves the collar.
	 * CH is relative to the root, which sits on the axis at {@link #LASH_Y}.
	 */
	private static void keepOffGrip(ArmDraw d) {
		float full = GRIP_CLEAR + COIL_R[tier] * (d.gui() ? 0.7F : 1F);
		float bottom = HANDLE_Y0 - 3.5F - LASH_Y;
		float ax = ARCH.x, az = ARCH.z, al = (float) Math.sqrt(ax * ax + az * az);
		if (al < 1e-4F) {
			ax = 1F;
			az = 0F;
		} else {
			ax /= al;
			az /= al;
		}
		for (int i = 1; i < NODES; i++) {
			float x = CH[3 * i], y = CH[3 * i + 1], z = CH[3 * i + 2];
			if (y < bottom || y > 0.5F) continue;
			float clear = ArmMath.lerp(1.4F, full, ArmMath.sm(-y / 3F));
			float r = (float) Math.sqrt(x * x + z * z);
			if (r >= clear) continue;
			if (r < 1e-3F) {
				CH[3 * i] = ax * clear;
				CH[3 * i + 2] = az * clear;
			} else {
				CH[3 * i] = x / r * clear;
				CH[3 * i + 2] = z / r * clear;
			}
		}
	}

	/** The chain's Catmull-Rom point and tangent at {@code tau} (0..9) into {@code p} and {@code t}. */
	private static void centre(float tau, Vector3f p, Vector3f t) {
		float f = ArmMath.clamp(tau, 0F, NODES - 1F);
		int i = Math.min(NODES - 2, (int) f);
		float u = f - i;
		int i0 = Math.max(0, i - 1), i1 = i, i2 = i + 1, i3 = Math.min(NODES - 1, i + 2);
		float u2 = u * u, u3 = u2 * u;
		for (int a = 0; a < 3; a++) {
			float p0 = CH[3 * i0 + a], p1 = CH[3 * i1 + a], p2 = CH[3 * i2 + a], p3 = CH[3 * i3 + a];
			float v = 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
			float dv = 0.5F * ((-p0 + p2) + 2F * (2F * p0 - 5F * p1 + 4F * p2 - p3) * u + 3F * (-p0 + 3F * p1 - 3F * p2 + p3) * u2);
			if (a == 0) {
				p.x = v;
				t.x = dv;
			} else if (a == 1) {
				p.y = v;
				t.y = dv;
			} else {
				p.z = v;
				t.z = dv;
			}
		}
	}

	/**
	 * The lash's stations for the front's reach: {@code u} on the grid (a station every 1/39, every other at LOD 1) up to the
	 * front, then the front itself (growth by truncation: stations are never moved, only added). For each, the chain's
	 * centre and a frame carried along it, the coil's path round it, the path's arc, the cord's width and the dissolve alpha.
	 * Everything is in G px from the lash's root at (0, {@link #LASH_Y}, 0), written into the arrays.
	 */
	private static void lash(ArmDraw d) {
		nL = 0;
		float front = frontFrac;
		if (front <= 0.004F) return;
		int step = lod == 0 ? 1 : 2;
		for (int i = 0; i < STATIONS; i += step) {
			float u = i / (float) (STATIONS - 1);
			if (u < front - 0.004F) U[nL++] = u;
			else break;
		}
		U[nL++] = front >= 0.999F ? 1F : front;
		if (nL < 2) {
			nL = 0;
			return;
		}
		float turns = TURNS[tier], rMax = COIL_R[tier];
		float spin = -0.05F * time + 0.3F * ph;
		float rScale = (1F - 0.65F * swingS) * (float) Math.sqrt(lashScale) * (d.gui() ? 0.7F : 1F);
		float arc = 0F, px = 0F, py = 0F, pz = 0F;
		float nx = 0F, ny = 0F, nz = 1F;
		float growth = d.frontBulb() && reach < 1F ? 1F : 0F;
		for (int k = 0; k < nL; k++) {
			float u = U[k];
			centre(u * (NODES - 1), P, T);
			if (T.lengthSquared() < 1e-10F) T.set(DOWN);
			T.normalize();
			// the frame: N carried from the last station (the first starts from +Z, made square to T)
			float dd = nx * T.x + ny * T.y + nz * T.z;
			N.set(nx - dd * T.x, ny - dd * T.y, nz - dd * T.z);
			if (N.lengthSquared() < 1e-8F) N.set(1F, 0F, 0F).fma(-T.x, T);
			if (N.lengthSquared() < 1e-8F) N.set(0F, 0F, 1F).fma(-T.z, T);
			N.normalize();
			nx = N.x;
			ny = N.y;
			nz = N.z;
			B.set(T).cross(N);
			float phi = PHI_LASH + arc;
			float amp = d.swell(phi, feedLength);
			float theta = ArmMath.TAU * turns * u + spin;
			float r = rMax * profile(u) * rScale * (1F + 0.3F * amp);
			float cx = P.x, cy = P.y + LASH_Y, cz = P.z;
			CX[3 * k] = cx;
			CX[3 * k + 1] = cy;
			CX[3 * k + 2] = cz;
			TX[3 * k] = T.x;
			TX[3 * k + 1] = T.y;
			TX[3 * k + 2] = T.z;
			NX[3 * k] = N.x;
			NX[3 * k + 1] = N.y;
			NX[3 * k + 2] = N.z;
			BX[3 * k] = B.x;
			BX[3 * k + 1] = B.y;
			BX[3 * k + 2] = B.z;
			float c = (float) Math.cos(theta), s = (float) Math.sin(theta);
			float qx = cx + r * (c * N.x + s * B.x), qy = cy + r * (c * N.y + s * B.y), qz = cz + r * (c * N.z + s * B.z);
			QX[3 * k] = qx;
			QX[3 * k + 1] = qy;
			QX[3 * k + 2] = qz;
			if (k > 0) arc += (float) Math.sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py) + (qz - pz) * (qz - pz));
			QARC[k] = arc;
			px = qx;
			py = qy;
			pz = qz;
			float w = cordWidth(u) * thin * (1F + 0.35F * amp);
			if (growth > 0F) w *= 1F + 0.3F * Math.max(0F, 1F - (front - u) * hangPx / 3F);
			QW[k] = w;
			float alpha = 1F;
			float crack = starving ? crackWeight(u) : 0F;
			CRACKED[k] = crack > 0.5F;
			if (fringeOn) alpha = ArmMath.fringe((front - u) * hangPx, fringeW);
			if (crack > 0.5F && d.dissolve()) alpha = Math.min(alpha, 0.72F);
			QA[k] = alpha;
		}
	}

	/** How deep in a starving crack lash position {@code u} is: 1 inside, easing out over a hundredth. */
	private static float crackWeight(float u) {
		float w = 0F;
		for (int i = 0; i < LASH_CRACKS.length; i += 2) {
			float a = LASH_CRACKS[i], b = LASH_CRACKS[i + 1];
			if (u >= a - 0.01F && u <= b + 0.01F) w = Math.max(w, u < a ? 1F - (a - u) / 0.01F : u > b ? 1F - (u - b) / 0.01F : 1F);
		}
		return w;
	}

	/** The station index (as a float) of lash position {@code u} on the grid, or −1 when that part of the lash isn't out. */
	private static float stationAt(float u) {
		for (int k = 0; k + 1 < nL; k++) {
			if (u >= U[k] && u <= U[k + 1]) return k + (U[k + 1] > U[k] ? (u - U[k]) / (U[k + 1] - U[k]) : 0F);
		}
		return -1F;
	}

	/** The point, tangent, N and B at lash position {@code f} (a station index, fractional) into P, T, N, B, and Q (the centre). */
	private static void frameAt(float f) {
		int k = Math.min(nL - 2, (int) f);
		float u = f - k;
		lerp3(QX, k, u, P);
		lerp3(CX, k, u, Q);
		lerp3(TX, k, u, T);
		lerp3(NX, k, u, N);
		lerp3(BX, k, u, B);
		T.normalize();
		N.fma(-N.dot(T), T);
		if (N.lengthSquared() < 1e-8F) N.set(1F, 0F, 0F).fma(-T.x, T);
		N.normalize();
		B.set(T).cross(N);
	}

	private static void lerp3(float[] a, int k, float u, Vector3f out) {
		out.set(ArmMath.lerp(a[3 * k], a[3 * k + 3], u), ArmMath.lerp(a[3 * k + 1], a[3 * k + 4], u), ArmMath.lerp(a[3 * k + 2], a[3 * k + 5], u));
	}

	// ------------------------------------------------------------------ the tool

	/** FORM's pop for something appearing {@code x} ticks after its moment: 0 → 1.25 → 1.0 over 2 ticks. */
	private static float pop(float x) {
		if (x <= 0F) return 0F;
		if (x < 1F) return 1.25F * ArmMath.sm(x);
		if (x < 2F) return ArmMath.lerp(1.25F, 1F, ArmMath.sm(x - 1F));
		return 1F;
	}

	/**
	 * How far a detail on the lash at {@code passU} is out: forming, it pops at FORM tick {@code at} (once the front has
	 * passed it); otherwise it grows as the front passes. Never more than the front allows.
	 */
	private static float sprout(float at, float passU) {
		float gate = ArmMath.sm((frontFrac - passU) * hangPx / 1.0F);
		return detailed ? pop(ft - at) * gate : gate;
	}

	/** A detail of the grip: popping at FORM tick {@code at} while forming, otherwise by how far the grip is out. */
	private static float sproutGrip(float at, float gate) {
		return detailed ? pop(ft - at) * ArmMath.sm(gate) : ArmMath.sm(gate);
	}

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (reach <= 0.003F) return;
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m);
			return;
		}
		if (lod == 2) {
			coarse(d, layer, m);
			return;
		}
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean glowBarbs = tier >= 3 && d.glow();
		handleCore(layer, red, m);
		handleBraid(d, layer, black, red, m);
		rings(d, layer, black, m);
		butt(d, layer, black, red, m);
		collar(d, layer, black, red, m);
		thumb(d, layer, black, red, m);
		if (nL >= 2) {
			cord(d, layer, black, red, m);
			knots(d, layer, black, m);
			barbs(d, layer, red, glowBarbs ? ArmLayer.GLOW : red, m);
			if (lod == 0) hairs(d, layer, black, m);
		}
		beads(d, layer, m);
		if (lod == 0 && d.dripsNow() && (!detailed || ft >= 13F)) drips(d, layer, black, m);
	}

	// ------------------------------------------------------------------ the grip

	/** The grip's red core: a 0.8 px tube with a slight belly, from the butt to the collar. */
	private static void handleCore(ArmLayer layer, ArmLayer red, SkinMesh m) {
		if (layer != red || yHi - yLo < 0.05F) return;
		Stations s = STRAND.reset(4).feed(phiHandle(yLo)).hint(0F, 0F, 1F);
		for (int i = 0; i < 3; i++) {
			float y = ArmMath.lerp(yLo, yHi, i / 2F);
			float w = FistGeometry.width(i == 1 ? 0.95F : 0.8F, 4);
			ArmMath.ring(RING4, 4, w, w, ArmMath.PI / 4F);
			s.add(0F, y, 0F, 0F, 1F, 0F, 1F, 0F, 0F, y - yLo, RING4, 1F);
		}
		capLayer = red;
		for (int i = 0; i < 2; i++) SEG[i] = red;
		Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_HCORE, 1F, false, false);
	}

	/**
	 * Strands braided over the core (1, 2 or 3 by tier): a tighter twist than the pickaxe's haft, round the grip's
	 * belly, passing over and under by the radius rising and falling out of step. A starving crack turns them red and thin.
	 */
	private static void handleBraid(ArmDraw d, ArmLayer layer, ArmLayer black, ArmLayer red, SkinMesh m) {
		if (yHi - yLo < 0.05F) return;
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int strands = lod == 0 ? tier : 1;
		int full = lod == 0 ? 12 : 7;
		float span = HANDLE_Y1 - HANDLE_Y0;
		int n = Math.max(2, Math.round((full - 1) * (yHi - yLo) / span) + 1);
		boolean dissolve = d.dissolve();
		for (int k = 0; k < strands; k++) {
			float phase = ArmMath.TAU * k / strands;
			for (int i = 0; i < n; i++) {
				float y = ArmMath.lerp(yLo, yHi, i / (float) (n - 1));
				float yy = y - HANDLE_Y0;
				float psi = phase + ArmMath.TAU * yy / 3.6F + 0.03F * time;
				float belly = 1F + 0.14F * (float) Math.sin(Math.PI * ArmMath.clamp01(yy / span));
				float r = (0.50F + 0.12F * (float) Math.sin(Math.PI * yy / 1.8F + phase)) * belly;
				boolean crack = starving && y >= GRIP_CRACK0 - 0.2F && y <= GRIP_CRACK1 + 0.2F;
				float w = ArmMath.lerp(0.6F, 0.5F, yy / span) * thin * (1F + d.swell(phiHandle(y), feedLength)) * (crack ? 0.75F : 1F);
				PTS[3 * i] = r * (float) Math.cos(psi);
				PTS[3 * i + 1] = y;
				PTS[3 * i + 2] = r * (float) Math.sin(psi);
				RAD[i] = FistGeometry.width(w, 3);
				ALPHA[i] = crack && dissolve ? 0.72F : 1F;
				if (i > 0) {
					float ym = ArmMath.lerp(yLo, yHi, (i - 0.5F) / (n - 1));
					SEG[i - 1] = starving && ym >= GRIP_CRACK0 && ym <= GRIP_CRACK1 ? red : black;
				}
			}
			capLayer = black;
			strandSides = 3;
			strand(m, n, layer, PART_HBRAID + k, 1.5F, phiHandle(yLo), 0F, 0F, 1F, false);
			strandSides = 4;
		}
	}

	/** Rings round the grip (tier 2 has two, tier 3 three), turning against each other, popping on as the grip comes out. */
	private static void rings(ArmDraw d, ArmLayer layer, ArmLayer black, SkinMesh m) {
		if (layer != black || lod > 0) return;
		float[] ys = RINGS[tier];
		for (int k = 0; k < ys.length; k++) {
			float y = ys[k];
			if (y > yHi + 0.05F || y < yLo - 0.05F) continue;
			ringS = detailed ? pop(ft - 8.3F - 0.5F * k) : ArmMath.sm((Math.min(yHi - y, y - yLo)) / 0.8F);
			if (ringS <= 0.02F) continue;
			ringY = y;
			ringTurn = ((k & 1) == 0 ? 0.02F : -0.02F) * time + k;
			Loft.closedTube(m, RING, lod == 0 ? 6 : 5, 4, RING_W, black, layer, ST, PART_RING + k, 2F, phiHandle(y), UP_Y, 1F);
		}
	}

	/** The butt: a knot, and under it the wrist loop (a ring in the XY plane) that swings as the arm does. */
	private static void butt(ArmDraw d, ArmLayer layer, ArmLayer black, ArmLayer red, SkinMesh m) {
		loopS = 0F;
		float buttS = sproutGrip(detailed ? 6.8F : 0F, (-yLo - 5.0F) / 0.5F);
		if (buttS <= 0.02F) return;
		if (layer == black) {
			LOCAL.identity().translate(0F, -5.55F, 0F).scale(buttS);
			m.box(LOCAL, -0.75F, -0.45F, -0.75F, 1.5F, 0.9F, 1.5F, ST.u0(PART_BUTT, 0, 17F), ST.v0(PART_BUTT, 0, 17F), 1F, 1F, m.tint());
		}
		loopS = detailed ? pop(ft - 8.4F) * ArmMath.sm((-yLo - 5.2F) / 0.4F) : ArmMath.sm((-yLo - 5.2F) / 0.4F);
		if (loopS <= 0.02F) return;
		if (layer == black) {
			Loft.closedTube(m, LOOP, lod == 0 ? 6 : 5, 4, LOOP_W, black, layer, ST, PART_LOOP, 1.5F, phiHandle(-6.5F), UP_Z, 1F);
		}
		if (layer == red) {
			// the knot where the loop is tied on
			LOCAL.identity().translate(0F, -5.75F, 0F).rotateY(loopTurn).scale(loopS);
			m.box(LOCAL, -0.6F, -0.4F, -0.6F, 1.2F, 0.8F, 1.2F, ST.u0(PART_BUTT, 1, 17.5F), ST.v0(PART_BUTT, 1, 17.5F), 1F, 1F, m.tint());
		}
	}

	/**
	 * The collar where the lash leaves the grip: a black ring that breathes, red knobs on it, and a crown of thorns (2, 3
	 * or 4 by tier... one more on the thumb side) each with its own length, tilt and width.
	 */
	private static void collar(ArmDraw d, ArmLayer layer, ArmLayer black, ArmLayer red, SkinMesh m) {
		collarS = sproutGrip(detailed ? 8.2F : 0F, (up - HANDLE_Y1) / 0.8F);
		if (collarS <= 0.02F) return;
		if (layer == black) {
			Loft.closedTube(m, COLLAR, lod == 0 ? 6 : 5, 4, COLLAR_W, black, layer, ST, PART_COLLAR, 1.5F, phiHandle(4.3F), UP_Y, 1F);
		}
		float r = 1.25F * breath * collarS;
		if (layer == red) {
			int knobs = tier >= 2 ? 3 : 2;
			for (int k = 0; k < knobs; k++) {
				float a = ArmMath.TAU * k / knobs + 0.7F + 0.004F * time;
				LOCAL.identity().translate(r * 1.05F * (float) Math.cos(a), 4.3F, r * 1.05F * (float) Math.sin(a)).rotateY(-a).scale(collarS);
				m.box(LOCAL, -0.35F, -0.3F, -0.35F, 0.7F, 0.6F, 0.7F, ST.u0(PART_KNOB, k, 14F), ST.v0(PART_KNOB, k, 14F), 1F, 1F, m.tint());
			}
		}
		if (layer == black && lod == 0) {
			int thorns = tier + 1;
			for (int k = 0; k < thorns; k++) {
				float a = CROWN_ANGLE[k] + 0.002F * time;
				float tilt = CROWN_TILT[k] + 0.08F * (float) Math.sin(0.1F * time + 1.7F * k + ph);
				float len = CROWN_LEN[k] * collarS * (1F + 0.3F * hitF);
				float ca = (float) Math.cos(a), sa = (float) Math.sin(a), st = (float) Math.sin(tilt), ct = (float) Math.cos(tilt);
				D1.set(ca * st, ct, sa * st);
				TURN.rotationTo(0F, 0F, 1F, D1.x, D1.y, D1.z);
				LOCAL.identity().translate(r * ca, 4.3F, r * sa).rotate(TURN);
				m.frustum(LOCAL, len, CROWN_W[k], CROWN_W[k], 0.05F, 0.05F, ST.u0(PART_CROWN, k, 15F), ST.v0(PART_CROWN, k, 15F), 2F, 1F, 1F, m.tint());
			}
		}
	}

	/**
	 * The thumb thorn on the leading (−X) side: a hooked talon curving out and up from the grip beside the collar, its last
	 * stretch red; tier 3 adds a second, smaller one lower down. It sways a little and splays when the arm strikes.
	 */
	private static void thumb(ArmDraw d, ArmLayer layer, ArmLayer black, ArmLayer red, SkinMesh m) {
		thumbS = sproutGrip(detailed ? 8.6F : 0F, (up - 3.2F) / 0.8F);
		if (thumbS <= 0.02F || lod > 1) return;
		int count = tier >= 3 ? 2 : 1;
		for (int k = 0; k < count; k++) {
			float len = k == 0 ? (tier <= 1 ? 0.8F : 1F) : 0.6F;
			float y0 = k == 0 ? 2.2F : 0.9F;
			float sway = 0.25F * (float) Math.sin(0.12F * time + 1.3F * k + ph) + 0.5F * swingS * (k == 0 ? 1F : 0.6F);
			int n = lod == 0 ? 6 : 4;
			for (int i = 0; i < n; i++) {
				float s = i / (float) (n - 1);
				float u = 1F - s;
				// a quadratic Bezier (−0.9, y0) → (−2.8, y0 + 0.8) → (−3.3, y0 + 3.6): out, then hooking up
				float x = u * u * -0.9F + 2F * u * s * (-2.8F - 0.2F * sway) + s * s * (-3.3F - 0.5F * sway);
				float y = u * u * y0 + 2F * u * s * (y0 + 0.8F) + s * s * (y0 + 3.6F * len);
				x = ArmMath.lerp(-0.9F, x, thumbS);
				PTS[3 * i] = x * (k == 0 ? 1F : 0.85F);
				PTS[3 * i + 1] = ArmMath.lerp(y0, y, thumbS);
				PTS[3 * i + 2] = 0.12F * (float) Math.sin(3F * s + ph);
				RAD[i] = FistGeometry.width(ArmMath.lerp(0.75F, 0.08F, (float) Math.pow(s, 0.8)) * thumbS, 4);
				ALPHA[i] = 1F;
				if (i > 0) SEG[i - 1] = i >= n - 2 ? red : black;
			}
			capLayer = red;
			strand(m, n, layer, PART_THUMB + k, 1.5F, phiHandle(y0), 0F, 0F, 1F, true);
		}
	}

	// ------------------------------------------------------------------ the lash

	/**
	 * The lash's cord: a red core along its whole length (black past the strands, where it is alone), and the braided strands
	 * (1, 2 or 3) over it for the first 28 stations, twisting round the cord as it coils.
	 */
	private static void cord(ArmDraw d, ArmLayer layer, ArmLayer black, ArmLayer red, SkinMesh m) {
		// the core (always built: the strands take their frames from it)
		Stations s = CORE.reset(4).feed(PHI_LASH).hint(NX[0], NX[1], NX[2]);
		for (int i = 0; i < nL; i++) {
			float half = FistGeometry.width(0.5F * QW[i], 4);
			ArmMath.ring(RING4, 4, half, half, ArmMath.PI / 4F);
			int a = i > 0 ? i - 1 : 0, b = i + 1 < nL ? i + 1 : nL - 1;
			s.addTransported(QX[3 * i], QX[3 * i + 1], QX[3 * i + 2], QX[3 * b] - QX[3 * a], QX[3 * b + 1] - QX[3 * a + 1], QX[3 * b + 2] - QX[3 * a + 2], QARC[i], RING4,
					QA[i]);
			if (i > 0) SEG[i - 1] = CRACKED[i - 1] || CRACKED[i] ? red : U[i] * (STATIONS - 1) <= STRAND_END + 0.5F ? red : black;
		}
		capLayer = black;
		if (layer == red || layer == black) Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_CORE, 2F, false, true);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int strands = lod == 0 ? tier : 1;
		int count = 0;
		for (int i = 0; i < nL; i++) {
			if (U[i] * (STATIONS - 1) <= STRAND_END + 0.5F || i == 0) count = i + 1;
			else break;
		}
		if (count < 2) return;
		// the strands: a ring of them round the core, twisting along it, the over-under in their radius
		for (int k = 0; k < strands; k++) {
			float phase = ArmMath.TAU * k / strands;
			for (int i = 0; i < count; i++) {
				float u = U[i];
				float w = QW[i];
				float fade = 1F - 0.8F * ArmMath.sm((u * (STATIONS - 1) - (STRAND_END - 10F)) / 10F);
				float psi = phase + QARC[i] * ArmMath.TAU / 4.5F + 0.04F * time;
				float rho = w * 0.27F * (1F + 0.25F * (float) Math.sin(2F * psi + phase)) * fade;
				float c = (float) Math.cos(psi), sn = (float) Math.sin(psi);
				float nx = s.normal(i, 0), ny = s.normal(i, 1), nz = s.normal(i, 2);
				float tx = s.tangent(i, 0), ty = s.tangent(i, 1), tz = s.tangent(i, 2);
				float bx = ty * nz - tz * ny, by = tz * nx - tx * nz, bz = tx * ny - ty * nx;
				PTS[3 * i] = QX[3 * i] + rho * (c * nx + sn * bx);
				PTS[3 * i + 1] = QX[3 * i + 1] + rho * (c * ny + sn * by);
				PTS[3 * i + 2] = QX[3 * i + 2] + rho * (c * nz + sn * bz);
				float sw = (strands == 1 ? 0.5F : strands == 2 ? 0.48F : 0.46F) * w * fade;
				RAD[i] = FistGeometry.width(Math.max(0.06F, sw), 3);
				ALPHA[i] = QA[i];
				if (i > 0) SEG[i - 1] = CRACKED[i - 1] || CRACKED[i] ? red : black;
			}
			capLayer = black;
			strandSides = 3;
			strand(m, count, layer, PART_STRAND + k, 1.5F, PHI_LASH, NX[0], NX[1], NX[2], true);
			strandSides = 4;
		}
	}

	/**
	 * Knots on the cord near its base (turk's heads, one to three by tier), each its own size, turning against its
	 * neighbour, swelling as a pulse passes.
	 */
	private static void knots(ArmDraw d, ArmLayer layer, ArmLayer black, SkinMesh m) {
		if (layer != black || lod > 0) return;
		for (int k = 0; k < tier; k++) {
			float grow = sprout(8.4F + 0.6F * k, KNOT_AT[k]);
			if (grow <= 0.02F) continue;
			float f = stationAt(KNOT_AT[k]);
			if (f < 0F) continue;
			frameAt(f);
			float w = cordWidth(KNOT_AT[k]) * thin * KNOT_SIZE[k] * (1F + 0.4F * d.swell(PHI_LASH + QARC[Math.min(nL - 1, Math.round(f))], feedLength));
			float turn = ((k & 1) == 0 ? 0.03F : -0.03F) * time + k;
			BASIS.set(N.x, N.y, N.z, 0F, T.x, T.y, T.z, 0F, B.x, B.y, B.z, 0F, 0F, 0F, 0F, 1F);
			LOCAL.identity().translate(Q).mul(BASIS).rotateY(turn).scale(grow);
			float h = 0.65F * w;
			m.box(LOCAL, -h, -0.4F * w, -h, 2F * h, 0.8F * w, 2F * h, ST.u0(PART_KNOT, k, PHI_LASH + 2F + 3F * k), ST.v0(PART_KNOT, k, PHI_LASH + 2F + 3F * k), 1.5F,
					1F, m.tint());
		}
	}

	/**
	 * The barbs on the lash's last third: hooked, tapering from 0.35 px to 0.05, each with its own length, lean, roll
	 * round the cord, hook and width (tables), a few forked, swaying; a hit flares them wide and the Lash special bristles them.
	 * Level 3's glow.
	 */
	private static void barbs(ArmDraw d, ArmLayer layer, ArmLayer red, ArmLayer barbLayer, SkinMesh m) {
		if (layer != barbLayer || lod > 1) return;
		int count = barbCount(tier);
		if (lod == 1) count = Math.min(count, 4);
		float flare = Math.max(hitF, lashS);
		int glowSet = barbLayer == ArmLayer.GLOW ? 1 : 0;
		float glow = ArmMath.clamp01(0.55F + 0.45F * flare);
		for (int k = 0; k < count; k++) {
			if (lod == 1 && (k & 1) == 1) continue;
			float u = 0.667F + 0.303F * (k + 0.5F) / count + BARB_JIT[k];
			float grow = sprout(8.6F + 0.55F * k, u);
			if (grow <= 0.02F) continue;
			float f = stationAt(u);
			if (f < 0F) continue;
			frameAt(f);
			float lean = (float) Math.toRadians(BARB_LEAN[k]) + 0.25F * flare;
			float roll = (float) Math.toRadians(BARB_ROLL[k]) + ArmMath.TAU * TURNS[tier] * u + 0.25F * (float) Math.sin(0.17F * time + 1.9F * k) + 0.5F * swingS;
			// outward from the cord, rolled round it by the helix angle (the same turn the coil makes)
			float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
			O.set(N).mul(cr).fma(sr, B);
			W.set(T).cross(O);
			float w = cordWidth(u) * thin;
			float len = 1.1F * BARB_LEN[k] * (1F + 0.6F * hitF + 0.5F * lashS) * grow;
			float cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
			D1.set(T).mul(-cl).fma(sl, O).normalize();
			float hook = BARB_HOOK[k];
			float ch = (float) Math.cos(hook), sh = (float) Math.sin(hook);
			D2.set(D1).mul(ch).fma(sh, W).fma(0.25F, T).normalize();
			PTS[0] = P.x + O.x * w * 0.3F;
			PTS[1] = P.y + O.y * w * 0.3F;
			PTS[2] = P.z + O.z * w * 0.3F;
			PTS[3] = PTS[0] + D1.x * len * 0.5F;
			PTS[4] = PTS[1] + D1.y * len * 0.5F;
			PTS[5] = PTS[2] + D1.z * len * 0.5F;
			PTS[6] = PTS[3] + D2.x * len * 0.5F;
			PTS[7] = PTS[4] + D2.y * len * 0.5F;
			PTS[8] = PTS[5] + D2.z * len * 0.5F;
			float bw = 0.35F * BARB_W[k];
			RAD[0] = FistGeometry.width(bw, 4);
			RAD[1] = FistGeometry.width(bw * 0.62F, 4);
			RAD[2] = FistGeometry.width(0.05F, 4);
			int n = 3;
			if (lod == 1) {
				PTS[3] = PTS[6];
				PTS[4] = PTS[7];
				PTS[5] = PTS[8];
				RAD[1] = RAD[2];
				n = 2;
			}
			ALPHA[0] = ALPHA[1] = ALPHA[2] = 1F;
			SEG[0] = SEG[1] = barbLayer;
			capLayer = barbLayer;
			strandRgb = glowSet == 1 ? FistGeometry.grey(glow) : -1;
			strand(m, n, layer, PART_BARB + k, 2F, PHI_LASH + QARC[Math.min(nL - 1, Math.round(f))], T.x, T.y, T.z, true);
			if (BARB_FORK[k] && lod == 0) {
				// a small barb off the first half, leaning the other way and sideways
				D2.set(D1).mul(0.6F).fma(-0.8F, W).fma(0.2F, O).normalize();
				PTS[6] = PTS[3] + D2.x * len * 0.4F;
				PTS[7] = PTS[4] + D2.y * len * 0.4F;
				PTS[8] = PTS[5] + D2.z * len * 0.4F;
				PTS[0] = PTS[3];
				PTS[1] = PTS[4];
				PTS[2] = PTS[5];
				PTS[3] = PTS[6];
				PTS[4] = PTS[7];
				PTS[5] = PTS[8];
				RAD[0] = FistGeometry.width(bw * 0.5F, 4);
				RAD[1] = FistGeometry.width(0.04F, 4);
				SEG[0] = barbLayer;
				strand(m, 2, layer, PART_FORK + k, 2F, PHI_LASH + QARC[Math.min(nL - 1, Math.round(f))], T.x, T.y, T.z, true);
			}
			strandRgb = -1;
		}
	}

	/** Hairs fraying off the coil: a frustum each, own length, roll and lean, swaying; 3, 5 or 7 by tier. */
	private static void hairs(ArmDraw d, ArmLayer layer, ArmLayer black, SkinMesh m) {
		if (layer != black) return;
		int count = tier + 2;
		for (int k = 0; k < count; k++) {
			float u = HAIR_AT[k];
			float grow = sprout(9.4F + 0.3F * k, u);
			if (grow <= 0.02F) continue;
			float f = stationAt(u);
			if (f < 0F) continue;
			frameAt(f);
			float roll = (float) Math.toRadians(HAIR_ROLL[k]) + ArmMath.TAU * TURNS[tier] * u + 0.4F * (float) Math.sin(0.15F * time + 1.7F * k);
			O.set(N).mul((float) Math.cos(roll)).fma((float) Math.sin(roll), B);
			float lean = HAIR_LEAN[k] + 0.35F * (float) Math.sin(0.11F * time + k) + 0.8F * swingS;
			D1.set(O).fma(lean, T).normalize();
			float len = HAIR_LEN[k] * (1F + 0.3F * (float) Math.sin(0.08F * time + 2F * k)) * (1F + 0.5F * lashS) * grow;
			float w = cordWidth(u) * thin;
			TURN.rotationTo(0F, 0F, 1F, D1.x, D1.y, D1.z);
			LOCAL.identity().translate(P.x + O.x * w * 0.3F, P.y + O.y * w * 0.3F, P.z + O.z * w * 0.3F).rotate(TURN);
			m.frustum(LOCAL, len, 0.14F, 0.14F, 0.04F, 0.04F, ST.u0(PART_HAIR, k, PHI_LASH + 3F), ST.v0(PART_HAIR, k, PHI_LASH + 3F), 2F, 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ strands, beads, drips

	/**
	 * A four-sided strand through the first {@code n} of {@link #PTS} (radius {@link #RAD}, alpha {@link #ALPHA}), its frame
	 * carried along by parallel transport from the hint, with the layers of {@link #SEG}; capped at its end when asked.
	 */
	private static void strand(SkinMesh m, int n, ArmLayer wanted, int part, float uvScale, float phi, float hx, float hy, float hz, boolean capEnd) {
		if (n < 2) return;
		boolean any = capEnd && capLayer == wanted;
		for (int i = 0; i + 1 < n && !any; i++) any = SEG[i] == wanted;
		if (!any) return;
		Stations s = STRAND.reset(strandSides).feed(phi).hint(hx, hy, hz).color(strandRgb);
		float arc = 0F;
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				float dx = PTS[3 * i] - PTS[3 * i - 3], dy = PTS[3 * i + 1] - PTS[3 * i - 2], dz = PTS[3 * i + 2] - PTS[3 * i - 1];
				arc += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			int a = i > 0 ? i - 1 : 0, b = i + 1 < n ? i + 1 : n - 1;
			ArmMath.ring(RING4, strandSides, RAD[i], RAD[i], strandSides == 4 ? ArmMath.PI / 4F : ArmMath.PI / 2F);
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * b] - PTS[3 * a], PTS[3 * b + 1] - PTS[3 * a + 1], PTS[3 * b + 2] - PTS[3 * a + 2], arc,
					RING4, ALPHA[i]);
		}
		Loft.sweep(m, s, wanted, BY_SEGMENT, ST, part, uvScale, false, capEnd);
	}

	/** The brightness the next strand's stations take ({@code FistGeometry.grey}), or −1 for the mesh's tint. */
	private static int strandRgb = -1;
	/** The sides of the next strand: 4, or 3 for the braids (a third fewer quads, and at 0.5 px nobody sees the corner). */
	private static int strandSides = 4;

	/** Loose beads: two flicked off the tip by a hit, beads dropping off a retracting or melting front, a failing arm's orbiting the old tip. */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		if (nL < 2) return;
		float hitAge = d.hitAge();
		if (hitAge >= 0F && hitAge < 8F) {
			tipFrame();
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float side = j == 0 ? 1F : -1F;
				float x = P.x + O.x * (0.8F * a * side) + T.x * 0.2F * j + DOWN.x * 0.06F * a * a;
				float y = P.y + O.y * (0.8F * a * side) + T.y * 0.2F * j + DOWN.y * 0.06F * a * a;
				float z = P.z + O.z * (0.8F * a * side) + T.z * 0.2F * j + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
			}
		}
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float a = rt - (2F + 1.4F * k);
				if (a < 0F || a > 3F) continue;
				float u = frontFrac * (1F - 0.15F * k);
				float f = stationAt(Math.min(u, U[nL - 1]));
				if (f < 0F) continue;
				frameAt(f);
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
				float f = stationAt(Math.min(1F - ArmMath.sm((born - 2F) / 14F), U[nL - 1]));
				if (f < 0F) continue;
				frameAt(f);
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, P.x + O.x * side + DOWN.x * 0.4F * a * a,
						P.y + O.y * side + DOWN.y * 0.4F * a * a, P.z + O.z * side + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			tipFrame();
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * time + ArmMath.TAU * k / 3F;
				float c = (float) Math.cos(a), s = (float) Math.sin(a);
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, P.x + 0.6F * (c * N.x + s * B.x), P.y + 0.6F * (c * N.y + s * B.y),
						P.z + 0.6F * (c * N.z + s * B.z), 0.5F, 16 + k);
			}
		}
	}

	/** The tip's point and frame (the lash's last station) into P, T, N, B, and an outward O. */
	private static void tipFrame() {
		frameAt(nL - 1.0001F);
		P.set(QX[3 * (nL - 1)], QX[3 * (nL - 1) + 1], QX[3 * (nL - 1) + 2]);
		O.set(N);
	}

	/** Drips off the lash's tip, the coil's lowest turn and the wrist loop's lowest point. */
	private static void drips(ArmDraw d, ArmLayer layer, ArmLayer black, SkinMesh m) {
		if (layer != black) return;
		float rate = d.dripRate();
		if (nL >= 2 && frontFrac >= 0.98F) {
			Drips.emit(m, null, QX[3 * (nL - 1)], QX[3 * (nL - 1) + 1], QX[3 * (nL - 1) + 2], DOWN, d.time(), 0, rate, d.blocksToPx(), ST, PART_DRIP);
		}
		if (tier >= 2 && nL >= 12) {
			int low = 6;
			float best = -Float.MAX_VALUE;
			for (int i = 4; i < nL - 3; i++) {
				float dd = QX[3 * i] * DOWN.x + QX[3 * i + 1] * DOWN.y + QX[3 * i + 2] * DOWN.z;
				if (dd > best) {
					best = dd;
					low = i;
				}
			}
			Drips.emit(m, null, QX[3 * low], QX[3 * low + 1], QX[3 * low + 2], DOWN, d.time(), 1, rate, d.blocksToPx(), ST, PART_DRIP);
		}
		if (loopS >= 0.9F) {
			Drips.emit(m, null, -0.5F, -9.1F * (DOWN.y < 0F ? 1F : 0.9F), 0F, DOWN, d.time(), 2, rate, d.blocksToPx(), ST, PART_DRIP);
		}
	}

	// ------------------------------------------------------------------ coarse levels of detail

	/** LOD 2 (section 2.10): the grip one box, the collar one box, the lash a four-sided tube of six stations, two barbs. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			if (yHi - yLo > 0.05F) m.box(null, -0.85F, yLo, -0.85F, 1.7F, yHi - yLo, 1.7F, ST.u0(PART_FAR, 0, 12F), ST.v0(PART_FAR, 0, 12F), 1F, 1F, m.tint());
			float collar = ArmMath.sm((up - HANDLE_Y1) / 0.8F);
			if (collar > 0.02F) {
				LOCAL.identity().translate(0F, 4.3F, 0F).scale(collar);
				m.box(LOCAL, -1.3F, -0.4F, -1.3F, 2.6F, 0.8F, 2.6F, ST.u0(PART_FAR, 1, 15F), ST.v0(PART_FAR, 1, 15F), 1F, 1F, m.tint());
			}
		}
		if (nL < 2) return;
		// six stations of the lash's cord (no coil's radius: the chain itself)
		Stations s = CORE.reset(4).feed(PHI_LASH).hint(NX[0], NX[1], NX[2]);
		int n = Math.min(6, nL);
		float arc = 0F;
		for (int k = 0; k < n; k++) {
			int i = n == nL ? k : Math.round(k * (nL - 1) / (float) (n - 1));
			float half = FistGeometry.width(0.5F * QW[i], 4);
			ArmMath.ring(RING4, 4, half, half, ArmMath.PI / 4F);
			if (k > 0) {
				int j = n == nL ? k - 1 : Math.round((k - 1) * (nL - 1) / (float) (n - 1));
				arc += (float) Math.sqrt((QX[3 * i] - QX[3 * j]) * (QX[3 * i] - QX[3 * j]) + (QX[3 * i + 1] - QX[3 * j + 1]) * (QX[3 * i + 1] - QX[3 * j + 1])
						+ (QX[3 * i + 2] - QX[3 * j + 2]) * (QX[3 * i + 2] - QX[3 * j + 2]));
			}
			s.addTransported(CX[3 * i], CX[3 * i + 1], CX[3 * i + 2], TX[3 * i], TX[3 * i + 1], TX[3 * i + 2], arc, RING4, 1F);
			SEG[Math.max(0, k - 1)] = black;
		}
		capLayer = black;
		if (layer == black) Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_CORE, 2F, false, true);
		// two barbs, as small red frustums on the last third
		if (layer == red) {
			for (int k = 0; k < 2; k++) {
				float u = k == 0 ? 0.74F : 0.9F;
				float f = stationAt(u);
				if (f < 0F) continue;
				frameAt(f);
				float roll = k == 0 ? 0.3F : 3.4F;
				O.set(N).mul((float) Math.cos(roll)).fma((float) Math.sin(roll), B);
				D1.set(T).mul(-0.8F).fma(0.6F, O).normalize();
				TURN.rotationTo(0F, 0F, 1F, D1.x, D1.y, D1.z);
				LOCAL.identity().translate(P).rotate(TURN);
				m.frustum(LOCAL, 1.2F, 0.4F, 0.4F, 0.05F, 0.05F, ST.u0(PART_BARB, k, PHI_LASH + 14F), ST.v0(PART_BARB, k, PHI_LASH + 14F), 2F, 1F, 1F, m.tint());
			}
		}
	}

	/** LOD 3: four boxes: the grip, the collar, the upper lash and, red, the lower lash. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			m.box(null, -0.85F, yLo, -0.85F, 1.7F, Math.max(0.01F, yHi - yLo), 1.7F, ST.u0(PART_FAR, 0, 12F), ST.v0(PART_FAR, 0, 12F), 1F, 1F, m.tint());
			float collar = ArmMath.sm((up - HANDLE_Y1) / 0.8F);
			if (collar > 0.02F) {
				LOCAL.identity().translate(0F, 4.3F, 0F).scale(collar);
				m.box(LOCAL, -1.3F, -0.4F, -1.3F, 2.6F, 0.8F, 2.6F, ST.u0(PART_FAR, 1, 15F), ST.v0(PART_FAR, 1, 15F), 1F, 1F, m.tint());
			}
		}
		if (nL < 2) return;
		// the lash as two boxes along the chain: its upper half black, its lower half red
		int mid = nL / 2;
		for (int part = 0; part < 2; part++) {
			ArmLayer l = part == 0 ? black : red;
			if (layer != l) continue;
			int a = part == 0 ? 0 : mid, b = part == 0 ? mid : nL - 1;
			D1.set(CX[3 * b] - CX[3 * a], CX[3 * b + 1] - CX[3 * a + 1], CX[3 * b + 2] - CX[3 * a + 2]);
			float len = D1.length();
			if (len < 0.2F) continue;
			D1.div(len);
			TURN.rotationTo(0F, 0F, 1F, D1.x, D1.y, D1.z);
			LOCAL.identity().translate(CX[3 * a], CX[3 * a + 1], CX[3 * a + 2]).rotate(TURN);
			float w = Math.max(0.3F, 0.7F * (QW[a] + QW[b]) / 2F);
			m.box(LOCAL, -w / 2F, -w / 2F, 0F, w, w, len, ST.u0(PART_FAR, 2 + part, 20F), ST.v0(PART_FAR, 2 + part, 20F), 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		begin(d);
		if (nL < 2) return out.set(0F, Math.max(0F, yHi), 0F);
		int i = which == ArmTips.MID ? Math.max(1, (nL - 1) / 2) : nL - 1;
		// the point is where the lash is: its centre, not the coil's turn round it
		return out.set(CX[3 * i], CX[3 * i + 1], CX[3 * i + 2]);
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{-3.6F, 6.6F, 1F}, {2F, 6F, -1F}, {-2F, 4F, 2F}, {2F, 4F, 2F}, {-1.2F, -8.9F, 0.5F}, {0.8F, -6F, 0.5F}, {-3.8F, 2F, -0.5F},
				{3.5F, -2F, 1F}, {7F, -4F, 0F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0], p[1], p[2]));
	}
}
