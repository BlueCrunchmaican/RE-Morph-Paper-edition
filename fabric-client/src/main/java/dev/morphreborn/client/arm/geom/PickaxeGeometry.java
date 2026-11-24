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
 * The pickaxe (section 2.5), an "ancient talon pick" grown out of the arm: a haft of three strands braided over a red
 * core and held by rings, a knotted socket with a red eye on each flat, a curved talon of a pick on the leading side
 * whose red keel runs to its point, a short chisel adze behind, sinews pulling the two toward the haft, and a knotted
 * butt whose roots splay into nothing. Grip space G, px; level 2 is the baseline.
 * <p>
 * Everything moves: the braid twists, the rings turn against each other, the prong breathes and the tendril coiled
 * round its point turns and wriggles, the sinews tense, the eye blinks, the fuzz sways, goo drips. Mining, the prong gnaws (a little bite every 5
 * ticks), the coil spins and its free end thrashes, the eye flickers and the forearm's veins beat with each bite; a broken block makes the prong
 * recoil and overshoot and flick beads off its point; Vein Rip pushes the prong out and lights the eye.
 * <p>
 * Tiers: level 1 has two strands, two rings, two sinews, no coil and a stub where the adze would be; level 3 grows a
 * serrated keel of five thorns, a beak spur under the socket, a fourth red ring, and its eye and keel glow.
 * <p>
 * The timelines are the katana's (section 2.6), read from {@link ArmDraw}: forming, the braid grows both ways out of
 * the fist (faster toward the butt), the rings pop, the socket inflates, the prongs extrude with a swelling, eroding
 * front, the sinews snap taut and wobble, the eye opens; anything else grows or shrinks the tool as one piece out of the
 * grip. Low biomass thins it, cracks it red (the prong at v 3..3.6, the haft at y 6..6.8), and erodes the prong's last
 * 15%.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class PickaxeGeometry implements FormGeometry {
	/**
	 * Which side of G the talon is on, as the katana's edge: built on +X and drawn through an X mirror ({@link #TOOL}),
	 * so in hand the talon's point faces down and leads vanilla's swing. Until 2026-11-16 it was drawn unmirrored and
	 * I saw the pick held upside down (the talon trailing, pointing up). World-facing inputs (down) are mirrored
	 * in and the points handed out mirrored back.
	 */
	static final float EDGE_SIGN = KatanaGeometry.EDGE_SIGN;
	/** The haft's radius under the fingers (F8's grip). */
	public static final float HAFT_R = 0.95F;
	/** The haft (P1, P2) from the butt to the socket. */
	private static final float HAFT_Y0 = -5.0F, HAFT_Y1 = 14.2F;
	/** The socket's centre (P5), where the prong and the adze leave it. */
	private static final float SOCKET_Y = 15.4F, HEAD_Y = 15.6F;
	/** P7's Bezier (its middle rises and falls a little: the prong breathing) and P8's. */
	private static final float Q0X = 0.9F, Q1X = 5.6F, Q1Y = 16.7F, Q2X = 8.6F, Q2Y = 12.4F;
	private static final float A0X = -0.9F, A1X = -4.1F, A1Y = 16.5F, A2X = -5.9F, A2Y = 14.0F;
	private static final int PRONG_STATIONS = 14, ADZE_STATIONS = 10, BRAID_STATIONS = 22;
	/**
	 * The rings' and the sinews' stations: 8 and 4 rather than the spec's 10 and 6, so the full pick (about 780 quads)
	 * keeps within section 2.10's ceilings with the sheath or forearm; a 6 px ring and a 3 px sinew don't show the
	 * difference.
	 */
	private static final int RING_STATIONS = 8, SINEW_STATIONS = 4;
	/**
	 * The rings (P3) by level, and level 3's red top one. Evenly spaced up the haft, with one closing the grip below the
	 * fist, so the handle reads as a made thing in segments (2026-11-15: "a bit more defined").
	 */
	private static final float[][] BINDINGS = {{-3.6F, 3.0F, 12.6F}, {-3.6F, 3.0F, 6.2F, 9.4F, 12.6F}, {-3.6F, 3.0F, 6.2F, 9.4F, 12.6F, 14.0F}};
	/** Level 3's keel thorns along the prong (v px). */
	private static final float[] KEEL_THORNS = {1.5F, 3.0F, 4.5F, 6.0F, 7.5F};
	/** Starving cracks: the prong at v 3..3.6, the haft at y 6.0..6.8. */
	private static final float PRONG_CRACK0 = 3.0F, PRONG_CRACK1 = 3.6F, HAFT_CRACK0 = 6.0F, HAFT_CRACK1 = 6.8F;
	/** The prong's and adze's arcs at rest, and the feed coordinate where the head starts. */
	static final float PRONG_ARC, ADZE_ARC;
	private static final float PHI_HEAD = phiHaft(HEAD_Y);
	/** The tool's reach from the grip for the timelines that grow it whole: up to the prong's point, down to the roots' ends. */
	private static final float DOWN_MAX = 8.4F;

	static {
		Vector3f a = new Vector3f(Q0X, HEAD_Y, 0F), b = new Vector3f(Q1X, Q1Y, 0F), c = new Vector3f(Q2X, Q2Y, 0F), p = new Vector3f(), q = new Vector3f(a);
		float arc = 0F;
		for (int i = 1; i <= 64; i++) {
			ArmMath.bezier(a, b, c, i / 64F, p);
			arc += p.distance(q);
			q.set(p);
		}
		PRONG_ARC = arc;
		a.set(A0X, HEAD_Y, 0F);
		b.set(A1X, A1Y, 0F);
		c.set(A2X, A2Y, 0F);
		q.set(a);
		arc = 0F;
		for (int i = 1; i <= 64; i++) {
			ArmMath.bezier(a, b, c, i / 64F, p);
			arc += p.distance(q);
			q.set(p);
		}
		ADZE_ARC = arc;
	}

	// static parts, one per piece
	private static final int PART_CORE = 1, PART_BRAID = 2, PART_RING = 5, PART_BUTT = 9, PART_ROOT = 10, PART_SOCKET = 13, PART_EYE = 14, PART_PRONG = 15,
			PART_ADZE = 16, PART_SINEW = 17, PART_TEETH = 21, PART_FUZZ = 22, PART_THORN = 28, PART_SPUR = 29, PART_DRIP = 30, PART_BEADS = 31, PART_FAR = 32;

	private static final Static ST = new Static();
	private static final Stations STRAND = new Stations(), PRONG = new Stations(), ADZE = new Stations();
	private static final float[] RING4 = new float[8], POLY = new float[10], RIBBON = new float[4];
	private static final float[] PTS = new float[3 * 40], RAD = new float[40], ALPHA = new float[40];
	private static final ArmLayer[] SEG = new ArmLayer[40];
	private static final boolean[] PRONG_CRACKED = new boolean[PRONG_STATIONS + 2], ADZE_RED = new boolean[ADZE_STATIONS + 2];
	private static final float[] TARGETS = new float[18], PASS = new float[6];
	private static final Matrix4f LOCAL = new Matrix4f(), TOOL = new Matrix4f();
	private static final Vector3f DOWN = new Vector3f(), C0 = new Vector3f(), C1 = new Vector3f(), C2 = new Vector3f(), P = new Vector3f(), T = new Vector3f(),
			U = new Vector3f(), Z = new Vector3f(0F, 0F, 1F), Q = new Vector3f(), R = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);

	// what the current emit has worked out (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, lod;
	private static float time, feedLength, droop, gnaw, recoil, rip, yHi, yLo, vFront, aFront, socketS, eyeOpen, sinewS, sinewBow, buttS, rootReach, teethS,
			fuzzS, fringeW, thin, ringY, ringTurn, ringScale;
	private static boolean detailed, cutting, starving, failing;
	private static ArmLayer capLayer = ArmLayer.BLACK, keelLayer = ArmLayer.RED, prongBlack = ArmLayer.BLACK, adzeTip = ArmLayer.RED;
	private static final Loft.FaceLayer BY_SEGMENT = (seg, face) -> face < 0 ? capLayer : SEG[Math.clamp(seg, 0, SEG.length - 1)];
	/** The prong's faces: the two keel faces (0 and 4) red (glowing at level 3), a starving crack all red. */
	private static final Loft.FaceLayer PRONG_FACES = (seg, face) -> {
		if (face < 0) return prongBlack;
		if (PRONG_CRACKED[Math.clamp(seg, 0, PRONG_CRACKED.length - 1)]) return keelLayer == ArmLayer.GLOW ? cur.paint(ArmLayer.RED) : keelLayer;
		return face == 0 || face == 4 ? keelLayer : prongBlack;
	};
	/** The adze's faces: black, its last 0.6 px (the chisel edge) and end cap red. */
	private static final Loft.FaceLayer ADZE_FACES = (seg, face) -> face < 0 ? (seg < 0 ? prongBlack : adzeTip)
			: ADZE_RED[Math.clamp(seg, 0, ADZE_RED.length - 1)] ? cur.paint(ArmLayer.RED) : prongBlack;
	/** A binding ring at {@link #ringY}: radius 0.98 with a slight three-lobed knot, so its turning shows. */
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 0.98F * (1F + 0.06F * (float) Math.cos(3F * (a - ringTurn))) * ringScale;
		out.set(r * (float) Math.cos(a), ringY + 0.08F * (float) Math.sin(3F * (a - ringTurn)) * ringScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.5F * ringScale, 4);

	public PickaxeGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.PICKAXE;
	}

	@Override
	public float feedLength(int tier) {
		return PHI_HEAD + PRONG_ARC;
	}

	@Override
	public float guiScale() {
		return 0.80F;
	}

	@Override
	public float guiGripPx() {
		return 4.2F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The prong's stations as the last emit that drew the prong left them (count, corners, arcs): for the tests. */
	static Stations lastProng() {
		return PRONG;
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
		// first person: the arm itself (mirrored for the left hand), its veins beating with each gnaw
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

	/** Reads the draw: tier, detail, low biomass, the reactions, and the timeline's plan of how much of each piece is out. */
	private static void begin(ArmDraw d) {
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		lod = Math.clamp(d.lod(), 0, 3);
		time = d.time();
		feedLength = PHI_HEAD + PRONG_ARC;
		d.down(DOWN);
		DOWN.x *= EDGE_SIGN; // into the mirrored build space
		droop = d.droop();
		int stage = d.lowStage();
		starving = stage >= ArmDraw.LOW_STARVING;
		failing = stage >= ArmDraw.LOW_FAILING;
		thin = stage >= ArmDraw.LOW_THINNING ? 0.85F : 1F;
		// mining: a bite every 5 ticks; a broken block: recoil −0.8 over 2 ticks, overshoot +0.4 over the next 3; Vein Rip:
		// the prong pushed out 1.5 px
		float bite = (float) Math.sin(ArmMath.TAU * time / 5F);
		gnaw = d.mining() ? Math.max(0F, bite) * Math.max(0F, bite) * Math.max(0F, bite) : 0F;
		float a = d.breakAge();
		if (a < 2F) recoil = -0.8F * ArmMath.sm(a / 2F);
		else if (a < 3.5F) recoil = ArmMath.lerp(-0.8F, 0.4F, ArmMath.sm((a - 2F) / 1.5F));
		else if (a < 5F) recoil = ArmMath.lerp(0.4F, 0F, ArmMath.sm((a - 3.5F) / 1.5F));
		else recoil = 0F;
		rip = d.specialWeight(ArmProtocol.FX_VEIN);
		plan(d);
	}

	/** How much of each piece is out (section 2.6, the pickaxe's FORM; the whole-tool timelines otherwise). */
	private static void plan(ArmDraw d) {
		detailed = d.detailedForm();
		float ft = d.formTick();
		if (detailed) {
			yHi = HAFT_Y1 * ArmMath.sm((ft - 3F) / 6F);
			yLo = HAFT_Y0 * ArmMath.sm((ft - 3F) / 4F); // 1.5× faster toward the butt
			socketS = pop((ft - 7F) / 4F);
			float out = ArmMath.sm((ft - 7.5F) / 3.5F);
			vFront = out * PRONG_ARC;
			aFront = out * ADZE_ARC;
			buttS = pop((ft - 6.8F) / 1.5F);
			rootReach = ArmMath.sm((ft - 7.2F) / 1.6F);
			sinewS = ArmMath.sm((ft - 10F) / 0.6F);
			// the sinews snap taut (bow 1.2 → 0.3 px in 2 ticks), then wobble three times, dying away
			sinewBow = ft < 12F ? ArmMath.lerp(1.2F, 0.3F, ArmMath.sm((ft - 10F) / 2F))
					: 0.3F + 0.35F * (float) Math.exp(-1.5F * (ft - 12F)) * (float) Math.cos(ArmMath.TAU * 1.5F * (ft - 12F));
			eyeOpen = ArmMath.sm((ft - 12F) / 2F);
			teethS = fuzzS = ArmMath.sm((ft - 11F) / 2F);
			cutting = ft < 11.5F;
		} else {
			float reach = d.reach();
			float up = reach * (HEAD_Y + PRONG_ARC + 1F), down = reach * DOWN_MAX;
			yHi = Math.min(HAFT_Y1, up);
			yLo = Math.max(HAFT_Y0, -down);
			socketS = ArmMath.sm((up - 14.4F) / 1.6F);
			vFront = ArmMath.clamp(up - HEAD_Y, 0F, PRONG_ARC);
			aFront = ArmMath.clamp(up - HEAD_Y, 0F, ADZE_ARC);
			buttS = ArmMath.sm((down - 5F) / 0.8F);
			rootReach = ArmMath.clamp01((down - 6F) / 2.2F);
			sinewS = ArmMath.sm((up - 16.5F) / 1.5F);
			sinewBow = 0.3F;
			eyeOpen = socketS;
			teethS = vFront >= PRONG_ARC - 0.05F ? 1F : 0F;
			fuzzS = socketS;
			cutting = reach < 1F;
		}
		fringeW = d.fringe();
		if (failing) {
			// the prong's last 15% erodes away (cut off with dissolve off)
			vFront = Math.min(vFront, 0.85F * PRONG_ARC);
			fringeW = Math.max(fringeW, 1.7F);
			teethS = 0F;
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
		ArmLayer black = d.paint(ArmLayer.BLACK);
		prongBlack = black;
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
		bindings(d, layer, m);
		butt(d, layer, m);
		socket(d, layer, m);
		eyes(d, layer, m);
		prong(d, layer, m);
		adze(d, layer, m);
		if (lod == 0) {
			sinews(d, layer, m);
			if (tier >= 2) teeth(d, layer, m);
			if (tier >= 3) spur(d, layer, m);
		}
		fuzz(d, layer, m);
		beads(d, layer, m);
		if (lod == 0 && d.dripsNow() && (!detailed || d.formTick() >= 13F)) drips(d, layer, m);
		if (d.burstCount() > 0) bursts(d, layer, m);
	}

	// ------------------------------------------------------------------ P1 core, P2 braid, P3 bindings, P4 butt

	private static void core(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer != red || yHi - yLo < 0.05F) return;
		Stations s = STRAND.reset(4).feed(phiHaft(yLo));
		ArmMath.ring(RING4, 4, FistGeometry.width(0.8F, 4), FistGeometry.width(0.8F, 4), ArmMath.PI / 4F);
		s.add(0F, yLo, 0F, 0F, 1F, 0F, 1F, 0F, 0F, 0F, RING4, 1F);
		s.add(0F, yHi, 0F, 0F, 1F, 0F, 1F, 0F, 0F, yHi - yLo, RING4, 1F);
		capLayer = red;
		for (int i = 0; i < 2; i++) SEG[i] = red;
		Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_CORE, 1F, false, false);
	}

	/**
	 * P2: three strands braided round the core, {@code ψ = 2πk/3 + 2π(y+5)/5 + 0.03t}, their distance from the axis
	 * rising and falling out of step ({@code r_k}) so they seem to pass over and under each other; a starving crack at y
	 * 6..6.8 turns them red and thin there.
	 */
	private static void braid(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (yHi - yLo < 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		int strands = tier <= 1 ? 2 : 3;
		int full = lod == 0 ? BRAID_STATIONS : 11;
		int n = Math.max(2, Math.round((full - 1) * (yHi - yLo) / (HAFT_Y1 - HAFT_Y0)) + 1);
		boolean dissolve = d.dissolve();
		for (int k = 0; k < strands; k++) {
			float phase = ArmMath.TAU * k / strands;
			for (int i = 0; i < n; i++) {
				float y = ArmMath.lerp(yLo, yHi, i / (float) (n - 1));
				float psi = phase + ArmMath.TAU * (y + 5F) / 5F + 0.03F * time;
				float r = 0.47F + 0.06F * (float) Math.sin(ArmMath.PI * (y + 5F) / 2.5F + phase); // tight, so the haft's line stays clean
				boolean crack = starving && y >= HAFT_CRACK0 - 0.2F && y <= HAFT_CRACK1 + 0.2F;
				float w = ArmMath.lerp(0.70F, 0.62F, (y - HAFT_Y0) / (HAFT_Y1 - HAFT_Y0)) * thin * (1F + d.swell(phiHaft(y), feedLength)) * (crack ? 0.75F : 1F);
				PTS[3 * i] = r * (float) Math.cos(psi);
				PTS[3 * i + 1] = y;
				PTS[3 * i + 2] = r * (float) Math.sin(psi);
				RAD[i] = FistGeometry.width(w, 4);
				ALPHA[i] = crack && dissolve ? 0.72F : 1F;
				if (i > 0) {
					float ym = ArmMath.lerp(yLo, yHi, (i - 0.5F) / (n - 1));
					SEG[i - 1] = starving && ym >= HAFT_CRACK0 && ym <= HAFT_CRACK1 ? red : black;
				}
			}
			capLayer = black;
			strand(m, n, layer, PART_BRAID + k, 1.5F, phiHaft(yLo), 0F, 0F, 1F, false);
		}
	}

	/** P3: red rings round the haft, turning against each other, popping on as the braid passes them; level 3's top one glows. */
	private static void bindings(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float[] ys = BINDINGS[tier - 1];
		float ft = d.formTick();
		for (int k = 0; k < ys.length; k++) {
			float y = ys[k];
			if (y > yHi + 0.05F || y < yLo - 0.05F) continue;
			if (lod > 0 && (y == 6.2F || y == 9.4F)) continue; // the middle rings only at full detail
			// red bands on the black braid, so the handle reads in segments (I wanted it more defined); level 3's
			// top one glows when glow is on
			ArmLayer l = tier >= 3 && k == ys.length - 1 && d.glow() ? ArmLayer.GLOW : red;
			if (layer != l) continue;
			ringScale = detailed ? pop((ft - (6F + Math.min(k, 2))) / 2F) : ArmMath.sm((yHi - y) / 0.8F);
			if (ringScale <= 0.02F) continue;
			ringY = y;
			ringTurn = ((k & 1) == 0 ? 0.02F : -0.02F) * time + k;
			Loft.closedTube(m, RING, lod == 0 ? RING_STATIONS : 6, 4, RING_W, l, layer, ST, PART_RING + k, 2F, phiHaft(y), UP_Y, 1F);
		}
	}

	/** P4: the pommel (a flared cap and a knot under it), and three roots splaying out and down, writhing. */
	private static void butt(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || buttS <= 0.02F) return;
		// a pommel: a cap flaring out below the haft's end, and a knot under it the roots leave from
		LOCAL.identity().translate(0F, -4.6F, 0F).rotateY(ArmMath.PI / 4F).rotateX(ArmMath.PI / 2F).scale(buttS);
		m.frustum(LOCAL, 1.0F, 1.5F, 1.5F, 2.4F, 2.4F, ST.u0(PART_BUTT, 0, 17F), ST.v0(PART_BUTT, 0, 17F), 1F, 1F, 1F, m.tint());
		LOCAL.identity().translate(0F, -6.0F, 0F).rotateY((float) Math.toRadians(25)).scale(buttS);
		m.box(LOCAL, -0.8F, -0.4F, -0.8F, 1.6F, 0.8F, 1.6F, ST.u0(PART_BUTT, 1, 17.5F), ST.v0(PART_BUTT, 1, 17.5F), 1F, 1F, m.tint());
		if (lod > 0 || rootReach <= 0.02F) return;
		float ph = d.phase();
		for (int k = 0; k < 3; k++) {
			root(k, ph, Q, R);
			for (int i = 0; i < 4; i++) {
				float s = rootReach * i / 3F;
				float wr = 0.1F * (float) Math.sin(Math.PI * s) * (float) Math.sin(ph + 5F * s + 0.31F * time + 2F * k);
				PTS[3 * i] = Q.x + R.x * 2.2F * s + R.z * wr;
				PTS[3 * i + 1] = Q.y + R.y * 2.2F * s;
				PTS[3 * i + 2] = Q.z + R.z * 2.2F * s - R.x * wr;
				RAD[i] = FistGeometry.width(ArmMath.lerp(0.45F, 0.15F, s), 4);
				ALPHA[i] = 1F;
				if (i > 0) SEG[i - 1] = black;
			}
			capLayer = black;
			strand(m, 4, layer, PART_ROOT + k, 2F, phiHaft(-6.2F), 0F, 1F, 0F, true);
		}
	}

	/** Root {@code k}'s start (under the butt's knot) and its unit direction: 40° off straight down, splayed 120° apart. */
	private static void root(int k, float ph, Vector3f start, Vector3f dir) {
		float a = ArmMath.TAU * k / 3F + 0.3F;
		float off = (float) Math.toRadians(40);
		start.set(0F, -6.2F, 0F);
		dir.set((float) Math.sin(off) * (float) Math.cos(a), -(float) Math.cos(off), (float) Math.sin(off) * (float) Math.sin(a));
	}

	// ------------------------------------------------------------------ P5 socket, P6 eyes

	private static void socket(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || socketS <= 0.02F) return;
		float k = socketS, phi = phiHaft(SOCKET_Y);
		LOCAL.identity().translate(0F, SOCKET_Y, 0F).scale(k);
		m.box(LOCAL, -1.3F, -1.0F, -0.9F, 2.6F, 2.0F, 1.8F, ST.u0(PART_SOCKET, 0, phi), ST.v0(PART_SOCKET, 0, phi), 1F, 1F, m.tint());
		LOCAL.identity().translate(0.25F, 15.8F, 0.1F).rotateZ((float) Math.toRadians(15)).scale(k);
		m.box(LOCAL, -1.0F, -1.2F, -0.8F, 2.0F, 2.4F, 1.6F, ST.u0(PART_SOCKET, 1, phi), ST.v0(PART_SOCKET, 1, phi), 1F, 1F, m.tint());
		LOCAL.identity().translate(-0.3F, 15.0F, -0.1F).rotateZ((float) Math.toRadians(-10)).scale(k);
		m.box(LOCAL, -0.8F, -0.8F, -1.0F, 1.6F, 1.6F, 2.0F, ST.u0(PART_SOCKET, 2, phi), ST.v0(PART_SOCKET, 2, phi), 1F, 1F, m.tint());
	}

	/**
	 * P6: an eye on each flat of the socket. Red, glowing at level 3, at level 2 and up while mining (flickering at
	 * 3 Hz), and fully in Vein Rip. It blinks every 120 to 200 ticks, and opens at the end of forming.
	 */
	private static void eyes(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (socketS <= 0.02F || eyeOpen <= 0.02F) return;
		boolean mining = d.mining();
		boolean glows = d.glow() && (tier >= 3 || tier >= 2 && mining || rip > 0.01F);
		ArmLayer l = glows ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != l) return;
		float bright = tier >= 3 ? 0.7F : 0.55F;
		if (mining) bright = Math.sin(ArmMath.TAU * 3F * time / 20F) > 0 ? 1F : 0.4F;
		bright = Math.max(bright, rip);
		int rgb = l == ArmLayer.GLOW ? FistGeometry.grey(bright) : m.tint();
		float open = eyeOpen * blink(d) * socketS;
		for (int k = 0; k < 2; k++) {
			// the +Z flat's eye sits proud of the socket at 1.06; the −Z flat's clears S3's deeper face (1.1)
			float z = k == 0 ? 1.06F : -1.16F;
			LOCAL.identity().translate(0.1F, 15.5F, z * socketS).scale(socketS, open, socketS);
			m.box(LOCAL, -0.375F, -0.375F, -0.06F, 0.75F, 0.75F, 0.12F, ST.u0(PART_EYE, k, 28F), ST.v0(PART_EYE, k, 28F), 2F, 1F, rgb);
		}
	}

	/** The eye's Y scale: 1, closing to 0.1 and opening again over 4 ticks every 120 to 200 ticks (seeded per player). */
	private static float blink(ArmDraw d) {
		float t = time + (d.seed() & 255) * 3.1F;
		int n = (int) Math.floor(t / 160F);
		float start = n * 160F + 40F * FistGeometry.hash(n * 7 + d.seed());
		float u = t - start;
		if (u < 0F || u > 4F) return 1F;
		return 1F - 0.9F * (float) Math.sin(Math.PI * u / 4F);
	}

	// ------------------------------------------------------------------ P7 prong, P8 adze

	/** The prong's control points for this frame: its middle breathing up and down a little. */
	private static void prongCurve() {
		C0.set(Q0X, HEAD_Y, 0F);
		C1.set(Q1X, Q1Y + 0.15F * (float) Math.sin(0.06F * time), 0F);
		C2.set(Q2X, Q2Y, 0F);
	}

	/** How far the prong is pushed out along itself at {@code s}: the gnaw (its last 40%), a break's recoil and Vein Rip. */
	private static float extension(float s) {
		return 0.6F * gnaw * ArmMath.clamp01((s - 0.6F) / 0.4F) + (recoil + 1.5F * rip) * s;
	}

	/** The prong at {@code s}: centre into P (pushed out, drooping in a melt), tangent T, outward U (Z × T). */
	private static void prongAt(float s) {
		ArmMath.bezier(C0, C1, C2, s, P);
		ArmMath.bezierTangent(C0, C1, C2, s, T);
		if (T.lengthSquared() < 1e-8F) T.set(1F, 0F, 0F);
		T.normalize();
		Z.cross(T, U).normalize(); // Z × T: in the blade's plane, away from the curve's inside
		P.fma(extension(s), T);
		if (droop > 0F) {
			float far = (HEAD_Y + s * PRONG_ARC) / (HEAD_Y + PRONG_ARC);
			P.fma(droop * far * far, DOWN);
		}
	}

	/**
	 * P7: the talon. A pentagon (keel R outward, flats S±, a broad back K±) swept along its curve, tapering to a point,
	 * ringed with growth bands; the keel's two faces red (glowing at level 3), cut at the front while it extrudes.
	 */
	private static void prong(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (vFront <= 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean glowKeel = tier >= 3 && d.glow();
		keelLayer = glowKeel ? ArmLayer.GLOW : red;
		if (layer != black && layer != red && layer != keelLayer && !Loft.pulseGlowPass(layer)) return;
		prongCurve();
		int n = lod == 0 ? PRONG_STATIONS : 8;
		boolean cut = vFront < PRONG_ARC - 0.02F;
		boolean fringe = d.dissolve() && cutting;
		float frontS = vFront / PRONG_ARC;
		Stations s = PRONG.reset(5).feed(PHI_HEAD);
		float bulb = d.frontBulb() && cut ? 1F : 0F;
		int count = 0;
		for (int i = 0; i < n; i++) {
			float sNom = i / (float) (n - 1);
			boolean last = sNom >= frontS - 1e-4F;
			float sv = last ? frontS : sNom;
			float v = sv * PRONG_ARC;
			prongAt(sv);
			float g = d.swell(PHI_HEAD + v, feedLength);
			float taper = (float) Math.pow(1F - sv, 0.85F);
			float rings = v < 0.85F * PRONG_ARC ? 1F + 0.07F * (float) Math.sin(ArmMath.TAU * v / 1.4F) : 1F;
			float breathe = 1F + 0.03F * (float) Math.sin(0.08F * time + 2F * sv);
			float h = (1.9F * taper + 0.12F) * rings * breathe * thin * (1F + 0.15F * g);
			float w = (1.6F * taper + 0.10F) * rings * breathe * thin * (1F + 0.15F * g);
			if (bulb > 0F) {
				float k = 1F + 0.3F * Math.max(0F, 1F - (vFront - v) / 3F);
				h *= k;
				w *= k;
			}
			boolean crack = starving && v >= PRONG_CRACK0 - 0.2F && v <= PRONG_CRACK1 + 0.2F;
			float keel = 0.5F * h - (crack ? 0.3F : 0F) + (failing ? 0.15F * (float) Math.sin(1.9F * v - 0.5F * time + d.phase()) : 0F);
			// (n, b) = (along U, along T × U = Z): R, S+, K+, K−, S−
			POLY[0] = keel;
			POLY[1] = 0F;
			POLY[2] = 0.15F * h;
			POLY[3] = 0.5F * w;
			POLY[4] = -0.5F * h;
			POLY[5] = 0.35F * w;
			POLY[6] = -0.5F * h;
			POLY[7] = -0.35F * w;
			POLY[8] = 0.15F * h;
			POLY[9] = -0.5F * w;
			float alpha = 1F;
			if (fringe) alpha = ArmMath.fringe(vFront - v, fringeW);
			if (crack && d.dissolve()) alpha = Math.min(alpha, 0.72F);
			s.color(layer == ArmLayer.GLOW ? FistGeometry.grey(ArmMath.clamp01(0.6F + 0.4F * g + rip)) : -1);
			s.add(P.x, P.y, P.z, T.x, T.y, T.z, U.x, U.y, U.z, v, POLY, alpha);
			if (count > 0) {
				float vm = 0.5F * (s.arc(count - 1) + v);
				PRONG_CRACKED[count - 1] = starving && vm >= PRONG_CRACK0 && vm <= PRONG_CRACK1;
			}
			count++;
			if (last) break;
		}
		if (count < 2) return;
		Loft.sweep(m, s, layer, PRONG_FACES, ST, PART_PRONG, 1F, false, cut);
		if (tier >= 3 && lod == 0 && layer == red) {
			// Awakened: a serrated keel of five thorns
			for (int k = 0; k < KEEL_THORNS.length; k++) {
				float v = KEEL_THORNS[k];
				if (v > vFront - 0.3F) continue;
				float sv = v / PRONG_ARC;
				prongAt(sv);
				float h = (1.9F * (float) Math.pow(1F - sv, 0.85F) + 0.12F) * thin;
				Q.set(P).fma(0.5F * h - 0.1F, U);
				LOCAL.identity().translate(Q).rotateTowards(U.x, U.y, U.z, Z.x, Z.y, Z.z);
				m.frustum(LOCAL, 0.7F, 0.4F, 0.4F, 0.05F, 0.05F, ST.u0(PART_THORN, k, PHI_HEAD + v), ST.v0(PART_THORN, k, PHI_HEAD + v), 2F, 1F, 1F, m.tint());
			}
		}
	}

	/**
	 * P8: the adze, a flat chisel on the trailing side, its rectangle narrowing to an edge whose last 0.6 px (and end) are
	 * red. Level 1 has only a 2 px black stub there.
	 */
	private static void adze(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float length = tier <= 1 ? Math.min(2F, ADZE_ARC) : ADZE_ARC;
		float front = Math.min(aFront, length);
		if (front <= 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && !Loft.pulseGlowPass(layer)) return;
		C0.set(A0X, HEAD_Y, 0F);
		C1.set(A1X, A1Y, 0F);
		C2.set(A2X, A2Y, 0F);
		boolean cut = front < length - 0.02F;
		boolean fringe = d.dissolve() && cutting;
		boolean stub = tier <= 1;
		adzeTip = stub || cut ? black : red;
		int n = lod == 0 ? ADZE_STATIONS : 6;
		Stations s = ADZE.reset(4).feed(PHI_HEAD);
		// the stations: n − 1 evenly up to 0.6 px short of the end (where the red edge starts), then the end
		float edgeAt = stub ? length : length - 0.6F;
		int count = 0;
		for (int i = 0; i < n; i++) {
			float v = stub ? length * i / (n - 1) : i < n - 1 ? edgeAt * i / (n - 2) : length;
			boolean last = v >= front - 1e-4F;
			if (last) v = front;
			float sv = v / ADZE_ARC;
			ArmMath.bezier(C0, C1, C2, sv, P);
			ArmMath.bezierTangent(C0, C1, C2, sv, T);
			T.normalize();
			T.cross(Z, U).normalize(); // T × Z: out of the curve's inside, as the prong's is
			if (droop > 0F) {
				float far = (HEAD_Y + v) / (HEAD_Y + PRONG_ARC);
				P.fma(droop * far * far, DOWN);
			}
			float u = sv;
			float hu = ArmMath.lerp(0.65F, 0.125F, u) * thin, hz = ArmMath.lerp(1.1F, 0.95F, u) * thin;
			POLY[0] = hu;
			POLY[1] = -hz;
			POLY[2] = hu;
			POLY[3] = hz;
			POLY[4] = -hu;
			POLY[5] = hz;
			POLY[6] = -hu;
			POLY[7] = -hz;
			float alpha = fringe ? ArmMath.fringe(front - v, fringeW) : 1F;
			s.add(P.x, P.y, P.z, T.x, T.y, T.z, U.x, U.y, U.z, v, POLY, alpha);
			if (count > 0) {
				float vm = 0.5F * (s.arc(count - 1) + v);
				ADZE_RED[count - 1] = !stub && vm > edgeAt;
			}
			count++;
			if (last) break;
		}
		if (count < 2) return;
		Loft.sweep(m, s, layer, ADZE_FACES, ST, PART_ADZE, 1F, false, true);
	}

	// ------------------------------------------------------------------ P9 sinews, P10 the coil, the beak spur

	/**
	 * P9: sinews from the upper haft to the middle of each prong (two to the pick, two to the adze; level 1 one each),
	 * bowed out a little and tensing in and out; forming they snap taut and wobble.
	 */
	private static void sinews(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || sinewS <= 0.02F || vFront < 0.45F * PRONG_ARC) return;
		float ph = d.phase();
		for (int k = 0; k < 4; k++) {
			if (tier <= 1 && (k & 1) == 1) continue;
			boolean pick = k < 2;
			float sz = (k & 1) == 0 ? 1F : -1F;
			if (pick) {
				prongCurve();
				prongAt(0.45F);
				float w = (1.6F * (float) Math.pow(0.55F, 0.85F) + 0.10F) * thin;
				R.set(P).add(0F, 0F, 0.45F * w * sz);
			} else {
				if (tier <= 1 || aFront < 0.45F * ADZE_ARC) continue;
				ArmMath.bezier(C0.set(A0X, HEAD_Y, 0F), C1.set(A1X, A1Y, 0F), C2.set(A2X, A2Y, 0F), 0.45F, R);
				R.add(0F, 0F, 0.45F * ArmMath.lerp(2.2F, 1.9F, 0.45F) * sz);
			}
			Q.set(pick ? 0.42F : -0.42F, 11.6F, 0.42F * sz);
			float bow = sinewBow * (1F + 0.25F * (float) Math.sin(0.06F * time + k));
			// bowed out from the haft (along x, away from it) and down a little
			float bx = pick ? 1F : -1F;
			for (int i = 0; i < SINEW_STATIONS; i++) {
				float s = i / (float) (SINEW_STATIONS - 1);
				float b = (float) Math.sin(Math.PI * s) * bow;
				float wr = 0.06F * (float) Math.sin(Math.PI * s) * (float) Math.sin(ph + 5F * s + 0.31F * time + k);
				PTS[3 * i] = ArmMath.lerp(Q.x, R.x, s) + bx * b * 0.7F;
				PTS[3 * i + 1] = ArmMath.lerp(Q.y, R.y, s) - b * 0.7F + wr;
				PTS[3 * i + 2] = ArmMath.lerp(Q.z, R.z, s) + wr;
				RAD[i] = FistGeometry.width(0.40F * sinewS, 4);
				ALPHA[i] = 1F;
				if (i > 0) SEG[i - 1] = black;
			}
			capLayer = black;
			strand(m, SINEW_STATIONS, layer, PART_SINEW + k, 2F, phiHaft(11.6F), 0F, 0F, 1F, false);
		}
	}

	/**
	 * P10: a thin tendril coiled round the talon's last part, hugging its tapering section and closing on the point, its
	 * free end flicking a little past the tip (2026-11-15: the three teeth that were here "felt off"; a tendril
	 * going round the pick is the same idea grown in naturally). It turns slowly; mining spins it faster and the free end
	 * thrashes. Forming, it winds out along the talon. Levels 2 and 3 (level 3 starts further down, with more turns).
	 */
	private static void teeth(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || teethS <= 0.02F) return;
		prongCurve();
		boolean mining = d.mining();
		coilFrom = tier >= 3 ? 0.3F : 0.4F;
		coilTurns = tier >= 3 ? 2.5F : 2F;
		coilSpin = (mining ? 0.25F : 0.04F) * time;
		coilWriggle = mining ? 0.45F : 0.2F;
		coilTime = time * (mining ? 0.9F : 0.3F);
		Loft.tube(m, COIL, 0F, teethS, lod == 0 ? 13 : 8, 3, COIL_W, black, layer, ST, PART_TEETH, 2F, feedLength - 4F, feedLength + 1F, UP_Z, 1F);
	}

	private static float coilFrom, coilTurns, coilSpin, coilWriggle, coilTime;
	/** The coil's share of its curve that winds round the talon; the rest is the free end past the point. */
	private static final float COIL_WOUND = 0.85F;
	private static final Loft.CurveFn COIL = (u, out) -> {
		if (u <= COIL_WOUND) {
			float f = u / COIL_WOUND;
			float sv = coilFrom + (1F - coilFrom) * f;
			prongAt(sv);
			float taper = (float) Math.pow(1F - sv, 0.85F);
			float a = 0.5F * (1.9F * taper + 0.12F) * thin + 0.2F, b = 0.5F * (1.6F * taper + 0.10F) * thin + 0.2F;
			float phi = ArmMath.TAU * coilTurns * f + coilSpin;
			out.set(P).fma(a * (float) Math.cos(phi), U).fma(b * (float) Math.sin(phi), Z);
		} else {
			// past the point: straight on along the talon, wriggling
			float e = (u - COIL_WOUND) / (1F - COIL_WOUND);
			prongAt(1F);
			float w = coilWriggle * e * (float) Math.sin(6F * e - 2.5F * coilTime);
			out.set(P).fma(1.4F * e, T).fma(w, U).fma(0.6F * w, Z);
		}
	};
	private static final Loft.RadiusFn COIL_W = u -> FistGeometry.width(ArmMath.lerp(0.32F, 0.07F, u), 3);

	/** Level 3's beak spur: 3 px under the socket, toward the pick's side and down. */
	private static void spur(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || socketS <= 0.02F) return;
		Q.set(0.7071F, -0.7071F, 0F);
		LOCAL.identity().translate(0.6F, 14.6F, 0F).rotateTowards(Q.x, Q.y, Q.z, 0F, 0F, 1F).scale(socketS);
		m.frustum(LOCAL, 3F, 0.6F, 0.6F, 0.08F, 0.08F, ST.u0(PART_SPUR, 0, PHI_HEAD), ST.v0(PART_SPUR, 0, PHI_HEAD), 2F, 1F, 1F, m.tint());
	}

	// ------------------------------------------------------------------ P11 fuzz, P12 drips, beads, bursts

	/** P11: fuzz, four filaments on the socket's top and two on the adze's back (the katana's, 1.2 to 2.0 px long). */
	private static void fuzz(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || fuzzS <= 0.02F) return;
		float a = d.swing();
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(a));
		float thinFuzz = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		int count = lod == 0 ? 6 : 2;
		int points = lod == 0 ? 4 : 3;
		for (int k = 0; k < count; k++) {
			float bx, by, bz, ox, oy, oz, lx, ly, lz;
			if (k < 4 || lod > 0) {
				// on the socket's top, out of it (+Y), leaning back (−X)
				int j = lod > 0 ? k * 2 + 1 : k;
				bx = -0.75F + 0.5F * j;
				by = 16.3F * socketS + SOCKET_Y * (1F - socketS);
				bz = (j & 1) == 0 ? 0.35F : -0.35F;
				ox = 0F;
				oy = 1F;
				oz = 0F;
				lx = -1F;
				ly = 0F;
				lz = 0F;
			} else {
				// on the adze's back
				if (tier <= 1 || aFront < 3F) continue;
				float v = k == 4 ? 1.0F : 2.2F;
				C0.set(A0X, HEAD_Y, 0F);
				C1.set(A1X, A1Y, 0F);
				C2.set(A2X, A2Y, 0F);
				ArmMath.bezier(C0, C1, C2, v / ADZE_ARC, P);
				ArmMath.bezierTangent(C0, C1, C2, v / ADZE_ARC, T);
				T.normalize();
				T.cross(Z, U).normalize();
				float hu = ArmMath.lerp(0.65F, 0.125F, v / ADZE_ARC);
				bx = P.x + U.x * hu;
				by = P.y + U.y * hu;
				bz = P.z;
				ox = U.x;
				oy = U.y;
				oz = U.z;
				lx = -T.x;
				ly = -T.y;
				lz = -T.z;
			}
			float len = (1.6F + 0.4F * (float) Math.sin(0.09F * time + 1.9F * k) + 1.5F * d.surge()) * (1F + 0.4F * swing) * thinFuzz * fuzzS;
			float lean = (float) Math.toRadians(35) + (float) Math.toRadians(50) * swing;
			float x = bx - ox * 0.15F, y = by - oy * 0.15F, z = bz - oz * 0.15F;
			PTS[0] = x;
			PTS[1] = y;
			PTS[2] = z;
			RAD[0] = FistGeometry.width(0.32F, 4);
			ALPHA[0] = 1F;
			int segs = points - 1;
			for (int j = 1; j <= segs; j++) {
				float f = j / (float) segs;
				float th = lean + 0.35F * (float) Math.sin(0.15F * time + 1.7F * k + 0.5F * j) * f;
				float c = (float) Math.cos(th), sn = (float) Math.sin(th), step = len / segs;
				x += (ox * c + lx * sn) * step;
				y += (oy * c + ly * sn) * step;
				z += (oz * c + lz * sn) * step;
				PTS[3 * j] = x;
				PTS[3 * j + 1] = y;
				PTS[3 * j + 2] = z;
				RAD[j] = FistGeometry.width(ArmMath.lerp(0.32F, 0.12F, f), 4);
				ALPHA[j] = 1F;
				SEG[j - 1] = black;
			}
			capLayer = black;
			strand(m, points, layer, PART_FUZZ + k, 2F, PHI_HEAD + 1F, lx, ly, lz, false);
		}
	}

	/** P12: goo off the pick's point, from under the socket and off two of the roots' ends. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate(), px = d.blocksToPx();
		if (vFront >= 0.97F * PRONG_ARC) {
			prongCurve();
			prongAt(0.97F);
			Drips.emit(m, null, P.x, P.y, P.z, DOWN, time, 0, rate, px, ST, PART_DRIP);
		}
		if (socketS > 0.9F) Drips.emit(m, null, 0F, 14.3F, 0F, DOWN, time, 1, rate, px, ST, PART_DRIP);
		if (rootReach >= 0.99F) {
			for (int k = 0; k < 2; k++) {
				root(k, d.phase(), Q, R);
				Q.fma(2.2F, R);
				Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, time, 2 + k, rate, px, ST, PART_DRIP);
			}
		}
	}

	/**
	 * Loose beads: three flung off the point by a broken block, beads dropping off the prong's shrinking front as it
	 * retracts, the melt's off its erosion front, and a failing arm's three beads orbiting where the point was.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		prongCurve();
		float g = 0.35F * d.blocksToPx() / 16F;
		float br = d.breakAge();
		if (br < 6F && vFront > 1F) {
			prongAt(vFront / PRONG_ARC);
			for (int k = 0; k < 3; k++) {
				float a = br;
				float spread = (k - 1) * 0.5F;
				float x = P.x + T.x * 0.7F * a + U.x * spread * a * 0.4F + DOWN.x * 0.5F * g * a * a;
				float y = P.y + T.y * 0.7F * a + U.y * spread * a * 0.4F + DOWN.y * 0.5F * g * a * a;
				float z = P.z + T.z * 0.7F * a + 0.25F * spread * a + DOWN.z * 0.5F * g * a * a;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, x, y, z, 0.5F * (1F - a / 6F), k);
			}
		}
		float rt = d.retractTick();
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float born = 2F + 1.4F * k, a = rt - born;
				if (a < 0F || a > 3F) continue;
				float up = (1F - ArmMath.sm((born - 2F) / 7F)) * (HEAD_Y + PRONG_ARC + 1F);
				float v = ArmMath.clamp(up - HEAD_Y, 0F, PRONG_ARC);
				if (v > 0.2F) prongAt(v / PRONG_ARC);
				else P.set(0F, Math.max(0F, Math.min(up, HAFT_Y1)), 0F);
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
				float up = (1F - ArmMath.sm((born - 2F) / 14F)) * (HEAD_Y + PRONG_ARC + 1F);
				float v = ArmMath.clamp(up - HEAD_Y, 0F, PRONG_ARC);
				if (v > 0.2F) prongAt(v / PRONG_ARC);
				else P.set(0F, Math.max(HAFT_Y0, Math.min(up, HAFT_Y1)), 0F);
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, P.x + side * 1.2F + DOWN.x * 0.4F * a * a,
						P.y + DOWN.y * 0.4F * a * a, P.z + side * 0.6F + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			prongAt(1F);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * time + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, P.x + 0.6F * ((float) Math.cos(a) * U.x),
						P.y + 0.6F * ((float) Math.cos(a) * U.y), P.z + 0.6F * (float) Math.sin(a), 0.5F, 16 + k);
			}
		}
	}

	/**
	 * FORM's burst tendrils out of the knuckles to the butt, the middle of the haft, the socket, the pick's point, the
	 * adze's edge and the upper haft; each sinks in as the growing tool reaches it.
	 */
	private static void bursts(ArmDraw d, ArmLayer layer, SkinMesh m) {
		setTarget(0, 0F, -5.6F, 0F);
		setTarget(1, 0.55F, 5.0F, 0.35F);
		setTarget(2, 0F, SOCKET_Y, 1.0F);
		setTarget(3, Q2X, Q2Y, 0F);
		setTarget(4, A2X, A2Y, 0F);
		setTarget(5, -0.6F, 11.0F, -0.3F);
		PASS[0] = 7F; // the braid reaches the butt
		PASS[1] = 3F + 6F * smInv(5.0F / HAFT_Y1);
		PASS[2] = 9F; // the socket, half inflated
		PASS[3] = 11F; // the prongs out
		PASS[4] = 11F;
		PASS[5] = 3F + 6F * smInv(11.0F / HAFT_Y1);
		FistGeometry.burst(d, layer, m, ST, TARGETS, PASS);
	}

	private static void setTarget(int i, float x, float y, float z) {
		TARGETS[3 * i] = x;
		TARGETS[3 * i + 1] = y;
		TARGETS[3 * i + 2] = z;
	}

	/** The x at which smoothstep reaches {@code y} (0..1). */
	private static float smInv(float y) {
		return 0.5F - (float) Math.sin(Math.asin(1F - 2F * ArmMath.clamp01(y)) / 3.0);
	}

	// ------------------------------------------------------------------ LOD 2 and 3

	/** LOD 2 (about 60 quads): a haft box, two ring boxes, the socket, a four-sided prong with a red keel ribbon, the adze. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			if (yHi - yLo > 0.05F) m.box(null, -0.6F, yLo, -0.6F, 1.2F, yHi - yLo, 1.2F, ST.u0(PART_FAR, 0, 14F), ST.v0(PART_FAR, 0, 14F), 1F, 1F, m.tint());
			for (int k = 0; k < 2; k++) {
				float y = k == 0 ? 3.0F : 12.6F;
				if (y < yHi) m.box(null, -0.95F, y - 0.2F, -0.95F, 1.9F, 0.4F, 1.9F, ST.u0(PART_RING, k, 14F), ST.v0(PART_RING, k, 14F), 1F, 1F, m.tint());
			}
			if (socketS > 0.02F) {
				LOCAL.identity().translate(0F, SOCKET_Y, 0F).scale(socketS);
				m.box(LOCAL, -1.3F, -1.0F, -0.9F, 2.6F, 2.0F, 1.8F, ST.u0(PART_SOCKET, 0, 27F), ST.v0(PART_SOCKET, 0, 27F), 1F, 1F, m.tint());
			}
			if (buttS > 0.02F) m.box(null, -0.7F, -5.8F, -0.7F, 1.4F, 1.0F, 1.4F, ST.u0(PART_BUTT, 0, 17F), ST.v0(PART_BUTT, 0, 17F), 1F, 1F, m.tint());
			float front = Math.min(aFront, ADZE_ARC);
			if (front > 0.3F) {
				Q.set(A2X - A0X, A2Y - HEAD_Y, 0F);
				LOCAL.identity().translate(A0X, HEAD_Y, 0F).rotateTowards(Q.x, Q.y, Q.z, 0F, 0F, 1F);
				m.box(LOCAL, -0.4F, -1.0F, 0F, 0.8F, 2.0F, front * 0.9F, ST.u0(PART_ADZE, 0, 28F), ST.v0(PART_ADZE, 0, 28F), 1F, 1F, m.tint());
			}
		}
		if (vFront <= 0.05F || layer != black && layer != red) return;
		prongCurve();
		Stations s = PRONG.reset(4).feed(PHI_HEAD), r = STRAND.reset(2).feed(PHI_HEAD);
		for (int i = 0; i <= 4; i++) {
			float sv = vFront / PRONG_ARC * i / 4F;
			prongAt(sv);
			float taper = (float) Math.pow(1F - sv, 0.85F);
			float h = (1.9F * taper + 0.12F) * thin, w = (1.6F * taper + 0.10F) * thin;
			POLY[0] = 0.35F * h;
			POLY[1] = -0.5F * w;
			POLY[2] = 0.35F * h;
			POLY[3] = 0.5F * w;
			POLY[4] = -0.5F * h;
			POLY[5] = 0.35F * w;
			POLY[6] = -0.5F * h;
			POLY[7] = -0.35F * w;
			s.add(P.x, P.y, P.z, T.x, T.y, T.z, U.x, U.y, U.z, sv * PRONG_ARC, POLY, 1F);
			RIBBON[0] = 0.35F * h;
			RIBBON[1] = 0F;
			RIBBON[2] = 0.5F * h + 0.05F;
			RIBBON[3] = 0F;
			r.add(P.x, P.y, P.z, T.x, T.y, T.z, U.x, U.y, U.z, sv * PRONG_ARC, RIBBON, 1F);
		}
		capLayer = black;
		for (int i = 0; i < 4; i++) SEG[i] = black;
		if (layer == black) Loft.sweep(m, s, layer, BY_SEGMENT, ST, PART_PRONG, 1F, false, false);
		capLayer = red;
		for (int i = 0; i < 4; i++) SEG[i] = red;
		if (layer == red) Loft.sweep(m, r, layer, BY_SEGMENT, ST, PART_PRONG + 1, 1F, false, false);
	}

	/** LOD 3: four boxes (haft, head, prong, adze). */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black && yHi - yLo > 0.05F) m.box(null, -0.7F, yLo, -0.7F, 1.4F, yHi - yLo, 1.4F, ST.u0(PART_FAR, 0, 14F), ST.v0(PART_FAR, 0, 14F), 1F, 1F,
				m.tint());
		if (layer == black && socketS > 0.02F) {
			LOCAL.identity().translate(0F, SOCKET_Y, 0F).scale(socketS);
			m.box(LOCAL, -1.4F, -1.1F, -1.0F, 2.8F, 2.2F, 2.0F, ST.u0(PART_FAR, 1, 27F), ST.v0(PART_FAR, 1, 27F), 1F, 1F, m.tint());
		}
		if (layer == red && vFront > 0.3F) {
			Q.set(Q2X - Q0X, Q2Y - HEAD_Y, 0F);
			LOCAL.identity().translate(Q0X, HEAD_Y + 0.5F, 0F).rotateTowards(Q.x, Q.y, Q.z, 0F, 0F, 1F);
			m.box(LOCAL, -0.6F, -0.6F, 0F, 1.2F, 1.2F, Q.length() * vFront / PRONG_ARC, ST.u0(PART_FAR, 2, 30F), ST.v0(PART_FAR, 2, 30F), 1F, 1F, m.tint());
		}
		if (layer == black && aFront > 0.3F) {
			Q.set(A2X - A0X, A2Y - HEAD_Y, 0F);
			LOCAL.identity().translate(A0X, HEAD_Y, 0F).rotateTowards(Q.x, Q.y, Q.z, 0F, 0F, 1F);
			m.box(LOCAL, -0.5F, -1.0F, 0F, 1.0F, 2.0F, Q.length() * aFront / ADZE_ARC, ST.u0(PART_FAR, 3, 28F), ST.v0(PART_FAR, 3, 28F), 1F, 1F, m.tint());
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
			s.addTransported(PTS[3 * i], PTS[3 * i + 1], PTS[3 * i + 2], PTS[3 * b] - PTS[3 * a], PTS[3 * b + 1] - PTS[3 * a + 1], PTS[3 * b + 2] - PTS[3 * a + 2],
					arc, RING4, ALPHA[i]);
		}
		Loft.sweep(m, s, wanted, BY_SEGMENT, ST, part, uvScale, false, capEnd);
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		begin(d);
		prongCurve();
		if (vFront <= 0.05F) return out.set(0F, Math.max(0F, yHi), 0F);
		prongAt(which == ArmTips.MID ? Math.min(0.45F, vFront / PRONG_ARC) : vFront / PRONG_ARC);
		out.set(P);
		out.x *= EDGE_SIGN;
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{9.4F, 12.0F, 0.9F}, {-6.4F, 13.6F, -1.2F}, {5.6F, 17.8F, 1.2F}, {-4.4F, 17.6F, 1.2F}, {-1.4F, 14.2F, -1.2F}, {1.0F, -8.0F, 1.0F},
				{-1.6F, -7.6F, -1.6F}, {1.6F, 3.0F, 1.6F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0] * EDGE_SIGN, p[1], p[2]));
	}
}
