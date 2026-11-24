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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The mace (my design, 2026-11-16: Skyward Leap, a launch straight up and a smash on the way down): a heavy head of
 * tendril knotted into a ball on a short braided haft, and the squattest, weightiest tool the arm makes. Grip space G (px), +Y
 * toward the head, the fist at the origin.
 * <p>
 * The haft is a braid like the other tools', but thick and tightly wound: a black core with strands of tendril winding round it the
 * two ways at once (two black at level 1, a red one more at level 2, two black and two red at level 3), over the fist the grip. A
 * red collar caps it, with a ring of tassels hanging off it that sway. On the collar sits the head: a ball of black tendril with
 * a knot of tendril wound round it, the way a mace's head is bound (a trefoil at level 1, a four-lobed knot at level 2, two
 * interlaced five-lobed knots at level 3, one of them red), and the head's flanges standing out of it, hardened static: flat red fins
 * that run from a horn below the ball to a horn above it, twisted a little round it as they go (four at level 1, six at level 2,
 * eight at level 3). A red finial crowns it, longer by tier. The butt is a heavy knot, ringed in red from level 2, with tail
 * spikes at level 3.
 * <p>
 * Everything moves: the static crawls up the haft into the head, pulses swell the braid and light the red, the knot crawls round the
 * ball, the tassels sway, and the flanges breathe. A swing flings the tassels back and a hit flares the flanges; while Skyward Leap
 * (and the landing) runs the flanges open wide, the knot spins up and the tassels stand on end. Low biomass thins it, cracks the haft
 * red, then erodes the finial and makes it spasm; melting droops it. Every timeline is read from {@link ArmDraw}.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class MaceGeometry implements FormGeometry {
	/** The braid's outer radius under the fingers: a little thicker than the swords'. */
	public static final float HILT_R = 1.15F;
	/** The butt's knot sits here; the haft runs from just above it to the head. */
	public static final float BUTT = -6.5F;
	/**
	 * First person draws the mace at 0.68 / 0.9 of the size G is built in: vanilla holds the mace at scale 0.9 where the handheld
	 * tools have 0.68 (the display blocks in {@code ArmTransforms}: MACE_FP 0.9, HANDHELD_FP 0.68), so a G pixel would come out a third
	 * bigger than every other form's. Third person needs none: the hold frame cancels the display scale.
	 */
	public static final float FP_SCALE = 0.68F / 0.9F;
	/** The collar's height, the head's centre and its radius at the middle, by tier (index 1 to 3). */
	private static final float[] NECK = {0F, 16.4F, 17F, 17.6F}, HEAD_C = {0F, 22.4F, 23.2F, 24F}, HEAD_R = {0F, 3.5F, 4F, 4.5F};
	/** The head is taller than it is wide by this much. */
	private static final float STRETCH = 1.12F;
	/** The flanges: how many, how far out of the ball they stand at the middle (px), by tier. */
	private static final int[] FINS = {0, 4, 6, 8};
	private static final float[] FIN_EXT = {0F, 2.1F, 2.7F, 3.3F};
	/** The flanges' horns reach this much past the ball's top and bottom (x the ball's half-height), and a flange's half-thickness. */
	private static final float HORN = 1.3F, FIN_T = 0.5F;
	/** The finial's length, by tier. */
	private static final float[] FINIAL = {0F, 1.8F, 3F, 4.4F};
	/** Strands in the braid, by tier (the last tier-1 are red). */
	private static final int[] STRANDS = {0, 2, 3, 4};
	/** The braid's turn: px of haft to a full turn of a strand, and its radius. */
	private static final float PITCH = 6F, BRAID_R = 0.95F;
	/** The knot round the head: windings round the ball, lobes up and down, how many (the second is red), by tier. */
	private static final int[] KNOT_P = {0, 2, 3, 3}, KNOT_Q = {0, 3, 4, 5}, KNOTS = {0, 1, 1, 2};
	/** The tassels hanging off the collar, by tier, and the butt's knot, its tail spikes. */
	private static final int[] TASSELS = {0, 3, 4, 6}, TAIL_SPIKES = {0, 0, 1, 3};
	private static final float[] POMMEL = {0F, 1.5F, 1.75F, 2F};
	/** Where the head's sections and flanges' stations are taken, at full detail and at LOD 1. */
	private static final int BALL_STATIONS = 9, BALL_STATIONS_LOD1 = 5, FIN_STATIONS = 5, FIN_STATIONS_LOD1 = 3, KNOT_STATIONS = 32, KNOT_STATIONS_LOD1 = 18;
	/** The leanings of each tassel from the haft (degrees) and its length (x the base). */
	private static final float[] TS_ANG = {52F, 38F, 60F, 44F, 56F, 40F}, TS_LEN = {1.0F, 1.35F, 0.8F, 1.2F, 0.95F, 1.5F};

	// static parts, one per piece
	private static final int PART_CORE = 1, PART_WRAP = 2, PART_BALL = 8, PART_COLLAR = 9, PART_FIN = 10, PART_KNOT = 24, PART_FINIAL = 28, PART_TASSEL = 30,
			PART_POMMEL = 40, PART_SPIKE = 44, PART_BEAD = 50, PART_DRIP = 54, PART_CRACK = 56, PART_BOX = 60;

	private static final Static ST = new Static();
	private static final Stations BALL = new Stations();
	private static final Stations FIN = new Stations();
	private static final Stations COLLAR_RING = new Stations();
	private static final float[] POLY = new float[16];
	private static final float[] FIN_POLY = new float[8];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Matrix4f TOOL = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f Q = new Vector3f(), DOWN = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier;
	private static float feedLength, front, low, shaftTop, droop, thin, spread, fold, flare, sprout, fringeW, spin;
	private static boolean fringeOn;
	/** The stretch of the core or a strand the next tube covers (y px). */
	private static float ty0, ty1;
	private static int strand;
	private static float strandWidth;
	private static float ringY, ringR;
	/** The knot the next tube draws: which of the tier's knots, how far grown. */
	private static int knotIndex;
	private static float knotGrow;
	private static ArmLayer ridgeLayer = ArmLayer.RED, blackLayer = ArmLayer.BLACK;

	private static final Loft.CurveFn CORE = (s, out) -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		out.set(0F, y, 0F);
		bend(y, out);
	};
	private static final Loft.RadiusFn CORE_R = s -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		float base = y < 0F ? ArmMath.lerp(1.2F, 1.5F, ArmMath.clamp01((y - BUTT) / -BUTT)) : 1.5F;
		return FistGeometry.width(base * thin * (1F + 0.35F * cur.swell(phi(y), feedLength)), 4);
	};
	private static final Loft.AlphaFn HAFT_FRINGE = s -> ArmMath.fringe(front - ArmMath.lerp(ty0, ty1, s), fringeW);
	private static final Loft.CurveFn WRAP = (s, out) -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		// the strands wind both ways, so they cross; each is offset round the haft by its own share of the turn
		float dir = (strand & 1) == 0 ? 1F : -1F;
		float a = dir * ArmMath.TAU * (y - BUTT) / PITCH + ArmMath.PI * strand * 0.5F;
		float wr = 1F + 0.05F * (float) Math.sin(cur.phase() + 5F * s + 0.31F * cur.time() + strand * 2F) / 0.8F;
		out.set(BRAID_R * wr * (float) Math.cos(a), y, BRAID_R * wr * (float) Math.sin(a));
		bend(y, out);
	};
	private static final Loft.RadiusFn WRAP_R = s -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		return FistGeometry.width(strandWidth * thin * (1F + 0.45F * cur.swell(phi(y), feedLength)), 4);
	};
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s + 0.004F * cur.time();
		float r = ringR * (1F + 0.04F * (float) Math.sin(3F * a + 0.05F * cur.time()));
		out.set(r * (float) Math.cos(a), ringY, r * (float) Math.sin(a));
		bend(ringY, out);
	};
	private static final Loft.RadiusFn RING_R = s -> FistGeometry.width(0.5F * thin, 4);
	private static final Loft.CurveFn KNOT = (s, out) -> knotPoint(s, out);
	private static final Loft.RadiusFn KNOT_R = s -> FistGeometry.width(0.62F * thin * (1F + 0.3F * cur.swell(phi(HEAD_C[tier]), feedLength)), 4);

	@Override
	public String form() {
		return ArmProtocol.MACE;
	}

	/** Where the top of the finial is at rest, by tier (G px). */
	public static float tipY(int tier) {
		int t = Math.clamp(tier, 1, 3);
		return HEAD_C[t] + HEAD_R[t] * STRETCH + FINIAL[t];
	}

	/** The head's centre by tier. */
	public static float headY(int tier) {
		return HEAD_C[Math.clamp(tier, 1, 3)];
	}

	/** The ball's radius at its middle by tier. */
	public static float headRadius(int tier) {
		return HEAD_R[Math.clamp(tier, 1, 3)];
	}

	/** How far a flange stands out of the ball at its middle by tier. */
	public static float finExtent(int tier) {
		return FIN_EXT[Math.clamp(tier, 1, 3)];
	}

	/** Strands in the braid, flanges, knots, the knot's windings and lobes, tassels, tail spikes and the finial's length, by tier: the tiers differ in these. */
	public static int strandCount(int tier) {
		return STRANDS[Math.clamp(tier, 1, 3)];
	}

	public static int finCount(int tier) {
		return FINS[Math.clamp(tier, 1, 3)];
	}

	public static int knotCount(int tier) {
		return KNOTS[Math.clamp(tier, 1, 3)];
	}

	public static int knotWindings(int tier) {
		return KNOT_P[Math.clamp(tier, 1, 3)];
	}

	public static int knotLobes(int tier) {
		return KNOT_Q[Math.clamp(tier, 1, 3)];
	}

	public static int tasselCount(int tier) {
		return TASSELS[Math.clamp(tier, 1, 3)];
	}

	public static int tailSpikeCount(int tier) {
		return TAIL_SPIKES[Math.clamp(tier, 1, 3)];
	}

	public static float finialLength(int tier) {
		return FINIAL[Math.clamp(tier, 1, 3)];
	}

	/** The ball's radius at height {@code v} above its centre (0 beyond its poles): a sphere stretched by {@link #STRETCH}. */
	public static float ballRadius(int tier, float v) {
		int t = Math.clamp(tier, 1, 3);
		float x = v / (HEAD_R[t] * STRETCH);
		return Math.abs(x) >= 1F ? 0F : HEAD_R[t] * (float) Math.sqrt(1F - x * x);
	}

	/** How far a flange reaches from the axis at height {@code v} above the head's centre, at its rest (px): the ball's own edge plus the flange. */
	public static float finOuter(int tier, float v) {
		int t = Math.clamp(tier, 1, 3);
		float hf = HEAD_R[t] * STRETCH * HORN;
		float x = Math.abs(v) / hf;
		if (x >= 1F) return 0.2F * HEAD_R[t];
		return Math.max(ballRadius(t, v), 0.25F * HEAD_R[t]) + FIN_EXT[t] * (1F - x * x);
	}

	@Override
	public float feedLength(int tier) {
		return FistGeometry.PHI_TOOL + tipY(tier) - 1.8F;
	}

	@Override
	public float guiScale() {
		// the finial lands at g + k*0.707*y along the slot's diagonal: 0.38 and 4.6 keep the level-3 finial (y 33.8) and the butt's
		// tail (y -9.5) between item pixels 1 and 15
		return 0.38F;
	}

	@Override
	public float guiGripPx() {
		return 4.6F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** The feed coordinate at height {@code y}: up and down from the fist, as the katana's hilt. */
	private static float phi(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	/** Melting's droop: each point sinks toward world down by {@code droop * (y / tip)^2}. */
	private static void bend(float y, Vector3f p) {
		if (droop <= 0F || y <= 0F) return;
		float u = y / tipY(tier);
		p.fma(droop * u * u, DOWN);
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.SENSE) return;
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		feedLength = feedLength(tier);
		ST.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		d.down(DOWN);
		if (d.firstPerson()) {
			FistGeometry.mirror(m, d.leftHand());
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, HILT_R, feedLength);
			FistGeometry.mirror(m, false);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm();
		float fp = d.firstPerson() ? FP_SCALE : 1F;
		m.transform(TOOL.scaling(sp * fp, sp * fp, sp * fp));
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float reach = d.reach();
		boolean failing = d.lowStage() == ArmDraw.LOW_FAILING;
		float tip = tipY(tier);
		front = reach * tip;
		if (failing) front = Math.min(front, 0.92F * tip);
		low = reach * BUTT;
		droop = d.droop();
		thin = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.88F : 1F;
		float sw = d.specialWeight(ArmProtocol.FX_MACE);
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		spread = sw;
		fold = 0.7F * swing * (1F - sw);
		flare = Math.max(d.hitFlare(), sw);
		sprout = ArmMath.sm((reach - 0.5F) / 0.5F);
		fringeOn = d.dissolve() && (reach < 1F || failing);
		fringeW = failing && reach >= 1F ? 1.7F : d.fringe();
		// the haft ends inside the ball
		shaftTop = Math.min(front, HEAD_C[tier] - HEAD_R[tier] * STRETCH + 0.8F);
		blackLayer = d.paint(ArmLayer.BLACK);
		ridgeLayer = tier >= 3 && d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		// the knot crawls round the ball, and spins up while the special runs
		spin = 0.016F * d.time() + 0.9F * sw * (float) Math.sin(0.35F * d.time());
		int lod = d.lod();
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m);
			return;
		}
		if (lod == 2) {
			coarse(d, layer, m);
			return;
		}
		haft(d, layer, m, lod, failing);
		collar(d, layer, m);
		ball(d, layer, m, lod);
		fins(d, layer, m, lod);
		knots(d, layer, m, lod);
		finial(d, layer, m);
		tassels(d, layer, m, lod, swing, sw);
		pommel(d, layer, m, lod);
		beads(d, layer, m, failing);
		if (lod == 0 && d.dripsNow() && reach >= 0.99F) drips(d, layer, m);
	}

	// ------------------------------------------------------------------ the haft

	/**
	 * Which strand the i-th drawn one is. LOD 0 draws them in order. LOD 1 draws two, and they must be a black one and (from level 2) the
	 * first red one, or the braid pops from black and red to all black across the LOD boundary (review 2026-11-16: the red ones are the
	 * last ones, so the first two were both black at levels 2 and 3).
	 */
	static int strandAt(int lod, int tier, int i) {
		int reds = Math.max(0, tier - 1);
		return lod == 0 || i == 0 || reds == 0 ? i : STRANDS[tier] - reds;
	}

	/** The black core, the braid's strands and (starving) the red cracks across it. */
	private static void haft(ArmDraw d, ArmLayer layer, SkinMesh m, int lod, boolean failing) {
		ArmLayer black = blackLayer, red = d.paint(ArmLayer.RED);
		float y0 = Math.max(low, BUTT + 0.6F), y1 = shaftTop;
		if (y1 - y0 < 0.05F) return;
		Loft.AlphaFn fringe = fringeOn ? HAFT_FRINGE : null;
		boolean wantBlack = layer == black, wantRed = layer == red || Loft.pulseGlowPass(layer);
		if (wantBlack) {
			ty0 = y0;
			ty1 = y1;
			int stations = Math.max(2, Math.round((y1 - y0) * (lod == 0 ? 0.5F : 0.25F)));
			Loft.tube(m, CORE, 0F, 1F, stations, 4, CORE_R, black, layer, ST, PART_CORE, 1F, phi(y0), phi(y1), UP_Z, fringe);
		}
		int n = Math.min(STRANDS[tier], lod == 0 ? 4 : 2);
		int sides = lod == 0 ? 4 : 3;
		// the red strands are the last ones: none at level 1, one at 2, two at 3
		int reds = Math.max(0, tier - 1);
		for (int i = 0; i < n; i++) {
			int k = strandAt(lod, tier, i);
			boolean isRed = k >= STRANDS[tier] - reds;
			ArmLayer strandLayer = isRed ? red : black;
			if (isRed ? !wantRed : !wantBlack) continue;
			strand = k;
			strandWidth = isRed ? 0.55F : 0.62F;
			ty0 = y0;
			ty1 = y1;
			int stations = Math.max(2, Math.round((y1 - y0) * (lod == 0 ? 0.62F : 0.4F)));
			Loft.tube(m, WRAP, 0F, 1F, stations, sides, WRAP_R, strandLayer, layer, ST, PART_WRAP + k, 2F, phi(y0), phi(y1), UP_Y, fringe);
		}
		if (wantRed && d.lowStage() >= ArmDraw.LOW_STARVING && lod == 0) {
			// starving cracks: red rings where the braid has split
			for (int j = 0; j < 3; j++) {
				float y = 5F + 4.5F * j;
				if (y > y1 - 0.5F) continue;
				ringY = y;
				ringR = BRAID_R + 0.4F;
				Loft.closedTube(m, RING, 8, 4, RING_R, red, layer, ST, PART_CRACK + j, 1.5F, phi(y), UP_Y, 1F);
			}
		}
	}

	/** The red collar: a seven-sided band that belies, with the pulse's light. */
	private static void collar(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float yc = NECK[tier];
		float grow = ArmMath.sm((front - (yc - 0.7F)) / 1.4F);
		if (grow <= 0.02F) return;
		ArmLayer red = ridgeLayer;
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		float swell = 1F + 0.3F * cur.swell(phi(yc), feedLength);
		Stations out = COLLAR_RING.reset(7).feed(phi(yc - 0.7F));
		out.color(layer == ArmLayer.GLOW ? FistGeometry.grey(0.3F + 0.4F * flare) : -1);
		for (int i = 0; i < 3; i++) {
			float y = yc - 0.7F + 0.7F * i;
			float r = (i == 1 ? 2.1F : 1.7F) * grow * swell;
			ArmMath.ring(POLY, 7, r, 0.92F * r, 0F);
			Vector3f c = Q.set(0F, y, 0F);
			bend(y, c);
			out.add(c.x, c.y, c.z, 0F, 1F, 0F, 1F, 0F, 0F, 0.6F * i, POLY, 1F);
		}
		Loft.sweep(m, out, layer, (seg, face) -> ridgeLayer, ST, PART_COLLAR, 1F, false, false);
	}

	// ------------------------------------------------------------------ the head

	/**
	 * The ball: sections of eight points lofted from pole to pole, a sphere a little taller than wide, cut at the growth front (the last
	 * section eroding when dissolve is on). Black, swelling with the pulse.
	 */
	private static void ball(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float yc = HEAD_C[tier], r = HEAD_R[tier], rv = r * STRETCH;
		float base = yc - rv;
		if (front <= base + 0.05F || layer != blackLayer) return;
		boolean truncated = front < yc + rv - 0.02F;
		int total = lod == 0 ? BALL_STATIONS : BALL_STATIONS_LOD1;
		int sides = lod == 0 ? 8 : 6;
		Stations out = BALL.reset(sides).feed(phi(base));
		for (int i = 0; i < total; i++) {
			// the sections crowd toward the poles, where the shape changes fastest
			float u = (float) Math.sin(ArmMath.PI * ((float) i / (total - 1) - 0.5F));
			float y = yc + rv * u;
			boolean last = i == total - 1;
			if (y >= front - 0.02F && !last) break;
			if (last && truncated) break;
			ballSection(out, u, y, sides, 1F);
		}
		if (truncated) {
			float y = front;
			ballSection(out, ArmMath.clamp((y - yc) / rv, -1F, 1F), y, sides, fringeOn ? 0F : 1F);
		}
		if (out.count() < 2) return;
		Loft.sweep(m, out, layer, (seg, face) -> blackLayer, ST, PART_BALL, 1F, true, truncated);
	}

	private static void ballSection(Stations out, float u, float y, int sides, float alpha) {
		float rr = Math.max(0.26F * HEAD_R[tier], HEAD_R[tier] * (float) Math.sqrt(Math.max(0F, 1F - u * u)));
		rr *= thin * (1F + 0.07F * cur.swell(phi(y), feedLength));
		ArmMath.ring(POLY, sides, rr, rr, ArmMath.PI / sides);
		Vector3f c = Q.set(0F, y, 0F);
		bend(y, c);
		out.add(c.x, c.y, c.z, 0F, 1F, 0F, 1F, 0F, 0F, y - (HEAD_C[tier] - HEAD_R[tier] * STRETCH), POLY, alpha);
	}

	/**
	 * The flanges: flat fins of hardened static, each a lofted lens from a horn below the ball to a horn above it, standing out of the
	 * ball furthest at its middle and twisted a quarter-turn round it as they climb. Red (glowing at level 3); they breathe, open wide
	 * while the special runs and flare when a hit lands.
	 */
	private static void fins(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		int n = FINS[tier];
		float yc = HEAD_C[tier], hf = HEAD_R[tier] * STRETCH * HORN;
		if (front <= yc - hf + 0.1F) return;
		if (layer != ridgeLayer && !Loft.pulseGlowPass(layer)) return;
		int total = lod == 0 ? FIN_STATIONS : FIN_STATIONS_LOD1;
		int step = lod == 0 ? 1 : 2; // LOD 1 keeps every other flange
		boolean glowPass = layer == ArmLayer.GLOW;
		int stage = d.lowStage();
		float ext = 1F + 0.55F * spread + 0.25F * flare - 0.3F * fold;
		if (stage >= ArmDraw.LOW_THINNING) ext *= 0.9F;
		for (int k = 0; k < n; k += step) {
			Stations out = FIN.reset(4).feed(phi(yc - hf));
			out.color(glowPass ? FistGeometry.grey(ArmMath.clamp01(0.5F + 0.5F * flare)) : -1);
			float az0 = ArmMath.TAU * k / n + 0.07F * (float) Math.sin(0.05F * cur.time() + 1.7F * k + cur.phase());
			boolean truncated = false;
			for (int i = 0; i < total; i++) {
				float u = -1F + 2F * i / (total - 1);
				float v = u * hf;
				float y = yc + v;
				if (y >= front - 0.02F) {
					truncated = true;
					break;
				}
				finSection(out, az0, v, y, ext, 1F);
			}
			if (truncated) finSection(out, az0, ArmMath.clamp(front - yc, -hf, hf), Math.min(front, yc + hf), ext, fringeOn ? 0F : 1F);
			if (out.count() < 2) continue;
			Loft.sweep(m, out, layer, (seg, face) -> ridgeLayer, ST, PART_FIN + k, 1F, true, truncated);
		}
	}

	/** One section of a flange at height {@code v} above the head's centre. */
	private static void finSection(Stations out, float az0, float v, float y, float ext, float alpha) {
		float r = HEAD_R[tier], hf = r * STRETCH * HORN;
		float x = Math.abs(v) / hf;
		float outer = Math.max(ballRadius(tier, v), 0.25F * r) + FIN_EXT[tier] * ext * (1F - x * x);
		float inner = Math.max(0.2F * r, 0.45F * ballRadius(tier, v));
		if (x >= 1F) outer = 0.2F * r + 0.01F;
		float depth = Math.max(0.05F, 0.5F * (outer - inner));
		float centre = 0.5F * (outer + inner);
		float az = az0 + 0.5F * (v / hf);
		float ca = (float) Math.cos(az), sa = (float) Math.sin(az);
		float t = FIN_T * thin * (0.45F + 0.55F * (1F - x * x));
		FIN_POLY[0] = depth;
		FIN_POLY[1] = 0F;
		FIN_POLY[2] = 0F;
		FIN_POLY[3] = t;
		FIN_POLY[4] = -depth;
		FIN_POLY[5] = 0F;
		FIN_POLY[6] = 0F;
		FIN_POLY[7] = -t;
		Vector3f c = Q.set(centre * ca, y, centre * sa);
		bend(y, c);
		out.add(c.x, c.y, c.z, 0F, 1F, 0F, ca, 0F, sa, v + hf, FIN_POLY, alpha);
	}

	/**
	 * The knot: a tendril wound round the ball, going round it {@code p} times while it climbs and falls {@code q} times, never off the
	 * ball's skin. Level 3 winds two, the second red, a half-step round from the first so they cross.
	 */
	private static void knots(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float yc = HEAD_C[tier], r = HEAD_R[tier];
		knotGrow = ArmMath.sm((front - yc - 0.3F * r) / (0.9F * r));
		if (knotGrow <= 0.02F) return;
		ArmLayer red = d.paint(ArmLayer.RED);
		int stations = lod == 0 ? KNOT_STATIONS : KNOT_STATIONS_LOD1;
		int n = lod == 0 ? KNOTS[tier] : 1;
		for (int k = 0; k < n; k++) {
			boolean isRed = k > 0;
			ArmLayer knotLayer = isRed ? red : blackLayer;
			if (isRed ? layer != red && !Loft.pulseGlowPass(layer) : layer != blackLayer) continue;
			knotIndex = k;
			// the second knot is a thread: three faces and fewer stations
			int sides = lod == 0 && k == 0 ? 4 : 3;
			Loft.closedTube(m, KNOT, k == 0 ? stations : Math.round(0.8F * stations), sides, KNOT_R, knotLayer, layer, ST, PART_KNOT + k, 1.5F, phi(yc), UP_Y, 1F);
		}
	}

	private static void knotPoint(float s, Vector3f out) {
		float yc = HEAD_C[tier], rv = HEAD_R[tier] * STRETCH;
		float th = ArmMath.TAU * s;
		int p = KNOT_P[tier], q = KNOT_Q[tier];
		float shift = knotIndex == 0 ? 0F : ArmMath.PI / p;
		float v = 0.8F * rv * knotGrow * (float) Math.sin(q * th + (knotIndex == 0 ? 0F : ArmMath.PI / q));
		float rho = Math.max(0.3F * HEAD_R[tier], ballRadius(tier, v) * thin) + 0.4F;
		rho *= 1F + 0.05F * (float) Math.sin(3F * th + 0.1F * cur.time() + 2F * knotIndex);
		float a = p * th + spin + shift;
		out.set(rho * (float) Math.cos(a), yc + v, rho * (float) Math.sin(a));
		bend(out.y, out);
	}

	/** The red finial on top of the ball: grown out as the front passes. */
	private static void finial(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float top = HEAD_C[tier] + HEAD_R[tier] * STRETCH;
		float grow = ArmMath.clamp01((front - top + 0.4F) / FINIAL[tier]);
		if (grow <= 0.02F) return;
		if (layer != ridgeLayer) return;
		float len = FINIAL[tier] * grow * (1F + 0.12F * flare);
		Q.set(0F, top - 0.5F, 0F);
		bend(Q.y, Q);
		spike(m, Q.x, Q.y, Q.z, 0F, 1F, 0F, len + 0.5F, 1.1F, 0.12F, PART_FINIAL, 0, layer == ArmLayer.GLOW ? FistGeometry.grey(0.55F + 0.45F * flare) : m.tint());
	}

	// ------------------------------------------------------------------ the tassels

	/** Short filaments hanging off the collar and swaying: flung back in a swing, standing on end while the special runs. */
	private static void tassels(ArmDraw d, ArmLayer layer, SkinMesh m, int lod, float swing, float sw) {
		if (layer != blackLayer || sprout <= 0.02F || lod >= 2 || front < NECK[tier]) return;
		int n = TASSELS[tier];
		float time = d.time();
		float thinK = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int k = 0; k < n; k++) {
			if (lod == 1 && (k & 1) == 1) continue;
			float az = ArmMath.TAU * (k + 0.5F) / n + 0.4F * FistGeometry.hash(k * 7 + 3 + 13 * d.seed() % 11);
			float len = (3.2F + 0.6F * (float) Math.sin(0.09F * time + 1.9F * k) + 1.2F * d.surge()) * TS_LEN[k] * thinK;
			len *= (1F + 0.5F * swing) * sprout;
			// hangs down and out from the collar; a swing flings it up and back, and the special stands it on end
			float ang = (float) Math.toRadians(TS_ANG[k]) + 0.3F * (float) Math.sin(0.15F * time + 1.7F * k) * 0.5F;
			ang = ArmMath.lerp(ang, (float) Math.toRadians(-70), Math.max(sw, 0.8F * swing));
			float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
			float dx = (float) Math.cos(az) * sa, dy = -ca, dz = (float) Math.sin(az) * sa;
			Q.set(1.8F * (float) Math.cos(az), NECK[tier] - 0.3F, 1.8F * (float) Math.sin(az));
			bend(Q.y, Q);
			spike(m, Q.x, Q.y, Q.z, dx, dy, dz, len, 0.75F, 0.14F, PART_TASSEL + k, 0, m.tint());
		}
	}

	/** A tapered spike from ({@code x, y, z}) along ({@code dx, dy, dz}) (any length), {@code len} px long, {@code w0} wide to {@code w1}. */
	private static void spike(SkinMesh m, float x, float y, float z, float dx, float dy, float dz, float len, float w0, float w1, int part, int seg, int rgb) {
		float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(len > 0.02F) || !(l > 1e-6F)) return;
		TURN.rotationTo(0F, 0F, 1F, dx / l, dy / l, dz / l);
		LOCAL.translation(x, y, z).rotate(TURN);
		m.frustum(LOCAL, len, w0, w0, w1, w1, ST.u0(part, seg, 0F), ST.v0(part, seg, 0F), 2F, 1F, 1F, rgb);
	}

	// ------------------------------------------------------------------ the butt

	/** The butt's knot (ringed in red from level 2) and its tail spikes, out as the haft grows down. */
	private static void pommel(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float grow = ArmMath.sm((-low - 4.6F) / 2.4F);
		if (grow <= 0.02F) return;
		float r = POMMEL[tier];
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer == blackLayer) {
			LOCAL.identity().translate(0F, BUTT, 0F).scale(grow);
			m.box(LOCAL, -r, -r * 0.65F, -r * 0.85F, 2F * r, 1.3F * r, 1.7F * r, ST.u0(PART_POMMEL, 0, 4F), ST.v0(PART_POMMEL, 0, 4F), 1F, 1F, m.tint());
			if (lod >= 1) return;
			LOCAL.identity().translate(0.2F * grow, BUTT - 0.1F * grow, 0.1F).rotateY((float) Math.toRadians(40)).rotateZ((float) Math.toRadians(25)).scale(grow);
			m.box(LOCAL, -0.8F * r, -0.55F * r, -0.8F * r, 1.6F * r, 1.1F * r, 1.6F * r, ST.u0(PART_POMMEL, 1, 4F), ST.v0(PART_POMMEL, 1, 4F), 1F, 1F, m.tint());
			int n = TAIL_SPIKES[tier];
			for (int k = 0; k < n; k++) {
				// the first spike points straight down; the other two splay either side of it
				float fan = k == 0 ? 0F : (k == 1 ? 1F : -1F) * 0.42F;
				spike(m, 0F, BUTT - 0.55F * r * grow, 0F, (float) Math.sin(fan), -(float) Math.cos(fan), 0F, (k == 0 ? 3.4F : 2.4F) * grow, k == 0 ? 0.95F : 0.65F, 0.1F,
						PART_SPIKE + k, k, m.tint());
			}
		}
		if (tier >= 2 && lod == 0 && (layer == red || Loft.pulseGlowPass(layer))) {
			ringY = BUTT + 0.1F;
			ringR = r * 0.95F * grow + 0.5F;
			Loft.closedTube(m, RING, 10, 4, RING_R, red, layer, ST, PART_POMMEL + 2, 1.5F, phi(BUTT), UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ beads and drips

	/** Beads flicked off the head by a hit, shed by a melt, and orbiting where a failing arm's finial used to be. */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = d.hitAge();
		float yh = HEAD_C[tier];
		if (hitAge >= 0F && hitAge < 8F && front > yh) {
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float x = (0.9F * a + 3F + 0.3F * j) * (j == 0 ? 1F : -1F) + DOWN.x * 0.06F * a * a;
				float y = yh + 0.4F * a + 0.2F * j + DOWN.y * 0.06F * a * a;
				float z = DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.7F * (1F - a / 8F), j);
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
				float y = tipY(tier) * (1F - ArmMath.sm((born - 2F) / 14F));
				float side = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, side * 2F + DOWN.x * 0.4F * a * a, y + DOWN.y * 0.4F * a * a,
						DOWN.z * 0.4F * a * a, 0.55F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * d.time() + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, 0.7F * (float) Math.cos(a), front, 0.7F * (float) Math.sin(a), 0.55F, 16 + k);
			}
		}
	}

	/** Drips off the ball's lower side and the butt. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != blackLayer) return;
		float rate = d.dripRate();
		float side = DOWN.x >= 0F ? 1F : -1F;
		float yh = HEAD_C[tier] - 0.4F * HEAD_R[tier];
		Drips.emit(m, null, side * ballRadius(tier, -0.4F * HEAD_R[tier]), yh, 0F, DOWN, d.time(), 4 + (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
		if (-low > 5F) Drips.emit(m, null, 0F, BUTT - 2F, 0F, DOWN, d.time(), 1, rate, d.blocksToPx(), ST, PART_DRIP);
	}

	// ------------------------------------------------------------------ levels of detail

	/** LOD 2: boxes for the haft, collar, butt and ball, and a flat box for the flanges. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = blackLayer, red = ridgeLayer;
		float yc = HEAD_C[tier], r = HEAD_R[tier], rv = r * STRETCH;
		if (layer == black && shaftTop - low > 0.05F) {
			m.box(null, -1F, low, -1F, 2F, shaftTop - low, 2F, ST.u0(PART_BOX, 0, 10F), ST.v0(PART_BOX, 0, 10F), 1F, 1F, m.tint());
		}
		if (layer == red && front > NECK[tier] - 0.7F) {
			m.box(null, -1.9F, NECK[tier] - 0.7F, -1.9F, 3.8F, 1.4F, 3.8F, ST.u0(PART_BOX, 1, 10F), ST.v0(PART_BOX, 1, 10F), 1F, 1F, m.tint());
		}
		float top = Math.min(front, yc + rv);
		if (layer == black && top > yc - rv + 0.1F) {
			m.box(null, -0.85F * r, yc - rv, -0.85F * r, 1.7F * r, top - (yc - rv), 1.7F * r, ST.u0(PART_BOX, 2, 10F), ST.v0(PART_BOX, 2, 10F), 1F, 1F, m.tint());
		}
		if (layer == red && front > yc) {
			float w = r + FIN_EXT[tier];
			m.box(null, -w, yc - 0.6F * rv, -0.5F, 2F * w, Math.min(front - yc, 0.6F * rv) + 0.6F * rv, 1F, ST.u0(PART_BOX, 3, 10F), ST.v0(PART_BOX, 3, 10F), 1F, 1F,
					m.tint());
		}
		if (layer == black && -low > 4F) {
			m.box(null, -POMMEL[tier], BUTT - 0.6F, -0.85F * POMMEL[tier], 2F * POMMEL[tier], 1.3F * POMMEL[tier], 1.7F * POMMEL[tier], ST.u0(PART_BOX, 4, 10F),
					ST.v0(PART_BOX, 4, 10F), 1F, 1F, m.tint());
		}
	}

	/** LOD 3: the haft and the head, two boxes. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != blackLayer) return;
		float yc = HEAD_C[tier], r = HEAD_R[tier];
		float top = Math.min(front, tipY(tier));
		float neck = Math.min(top, yc - r);
		if (neck - low > 0.1F) {
			m.box(null, -1F, low, -1F, 2F, neck - low, 2F, ST.u0(PART_BOX, 5, 10F), ST.v0(PART_BOX, 5, 10F), 1F, 1F, m.tint());
		}
		if (top > yc - r + 0.1F) {
			m.box(null, -r, yc - r * STRETCH, -r, 2F * r, Math.min(top, yc + r * STRETCH) - (yc - r * STRETCH), 2F * r, ST.u0(PART_BOX, 6, 10F), ST.v0(PART_BOX, 6, 10F), 1F,
					1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		int t = Math.clamp(d.tier(), 1, 3);
		float tip = tipY(t);
		float f = d.reach() * tip;
		if (d.lowStage() == ArmDraw.LOW_FAILING) f = Math.min(f, 0.92F * tip);
		f = Math.max(0F, f);
		// the middle is the head's centre (where the mace strikes, and where healing tendrils latch)
		float y = which == ArmTips.MID ? Math.min(f, HEAD_C[t]) : f;
		return out.set(0F, y * (d.firstPerson() ? FP_SCALE : 1F), 0F);
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float w = HEAD_R[3] + FIN_EXT[3] + 0.8F;
		float[][] pts = {{-w, 34.5F, -w}, {w, 34.5F, w}, {-w, -11.5F, -w}, {w, -11.5F, w}, {-w, 20F, w}, {w, 27F, -w}, {-1F, 34.5F, -1F}, {1F, -11.5F, 1F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0], p[1], p[2]));
	}
}
