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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The spear (my design, 2026-11-16: Impale, a lunge that pins what it skewers): a long spear grown out of the
 * arm, and the longest tool the arm makes. Grip space G (px), +Y toward the head, the fist at the origin.
 * <p>
 * The shaft is a braid. A black core runs from the butt to the collar and strands of tendril wind round it the two ways at
 * once, so they cross like a braided rope (two black strands at level 1; level 2 adds a red one; level 3 has two black and
 * two red). Over the fist the strands are the grip. Above the shaft sits a red collar, and on it the head: a leaf-shaped
 * blade lofted out of the collar, black with a red ridge that wavers like the katana's hamon and lights as pulses run up it,
 * its point a dagger's. Barbs sweep out of the collar and hook up round the blade like a harpoon's flukes, tipped red; level 2
 * adds two lugs that hook back down the shaft, level 3 a cross of four barbs and four lugs. Red binding rings round the
 * shaft (none, one, two) and a pennon of tendril streaming from under the head on the rope's chain (level 2 one, level 3 two)
 * make the levels differ at a glance. The butt ends in a knot with tail spikes (one, three, five). Bristles lean back from
 * the shaft and sway.
 * <p>
 * Everything moves: the static crawls up the shaft into the head, pulses swell the braid and light the red, the barbs sway,
 * the bristles stream back in a jab and stand on end while Impale pins (the head's red ridge opens wide and its barbs flare), a
 * hit flares the ridge and flicks beads off the point, low biomass thins it, cracks it red, then erodes its point and makes it
 * spasm, and melting droops it. Every timeline is read from {@link ArmDraw}.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class SpearGeometry implements FormGeometry {
	/** The braid's outer radius under the fingers, as the other forms' grips. */
	public static final float HILT_R = 1.05F;
	/** The butt's knot sits here; the shaft runs from just above it to the collar. */
	public static final float BUTT = -8F;
	/** The collar's centre, the head's length and its greatest half-width, by tier (index 1 to 3). */
	private static final float[] COLLAR = {0F, 20.5F, 21.5F, 22.5F}, HEAD_LEN = {0F, 8.5F, 10.5F, 12.5F}, HEAD_HALF = {0F, 1.5F, 1.9F, 2.3F};
	/** The head starts this far above the collar's centre (inside it). */
	private static final float HEAD_ROOT = 0.4F;
	/** The head's greatest width is this far along it. */
	private static final float HEAD_BELLY = 0.28F;
	/** The braid's turn: px of shaft to a full turn of a strand, and its radius. */
	private static final float PITCH = 7F, BRAID_R = 0.78F;
	/** The barbs' azimuths round the shaft (radians; 0 is +X, the blade's broad side), by tier. */
	private static final float[][] HEAD_BARBS = {{}, {0F, ArmMath.PI}, {0F, ArmMath.PI}, {0F, ArmMath.PI / 2F, ArmMath.PI, 3F * ArmMath.PI / 2F}};
	private static final float[][] LUGS = {{}, {}, {ArmMath.PI / 2F, 3F * ArmMath.PI / 2F},
			{ArmMath.PI / 4F, 3F * ArmMath.PI / 4F, 5F * ArmMath.PI / 4F, 7F * ArmMath.PI / 4F}};
	/** Where bristles stand on the shaft (y px), by tier: above the fist, below the collar. */
	private static final float[][] BRISTLES = {{}, {5.5F, 10.5F, 15F, 17.5F}, {4.5F, 7.5F, 10.5F, 13F, 15.5F, 18F},
			{4F, 6.2F, 8.6F, 11F, 13.2F, 15.4F, 17.5F, 19.2F}};
	/** The butt's tail spikes: how many, by tier. */
	private static final int[] TAIL_SPIKES = {0, 1, 3, 5};
	/** The binding rings round the shaft: how many, by tier. */
	private static final int[] RINGS = {0, 0, 1, 2};
	/** The pennons streaming from under the head: how many, by tier. */
	private static final int[] PENNONS = {0, 0, 1, 2};
	/** Strands in the braid, by tier, and which are red. */
	private static final int[] STRANDS = {0, 2, 3, 4};
	/** Each bristle's own character, by its place: length (x the base), lean back from the shaft's normal (degrees), width. */
	private static final float[] BR_LEN = {1.0F, 1.5F, 0.75F, 1.7F, 1.1F, 0.8F, 1.3F, 0.65F}, BR_ANG = {38F, 52F, 30F, 56F, 42F, 34F, 50F, 40F},
			BR_W = {0.55F, 0.7F, 0.45F, 0.75F, 0.55F, 0.45F, 0.65F, 0.45F};
	/** The head's sections at full detail and at LOD 1. */
	private static final int HEAD_STATIONS = 13, HEAD_STATIONS_LOD1 = 6;

	// static parts, one per piece
	private static final int PART_CORE = 1, PART_WRAP = 2, PART_HEAD = 8, PART_COLLAR = 9, PART_RING = 10, PART_BARB = 14, PART_BRISTLE = 40, PART_BUTT = 52,
			PART_SPIKE = 54, PART_PENNON = 62, PART_BEAD = 66, PART_DRIP = 70, PART_CRACK = 72, PART_BOX = 74;

	private static final Static ST = new Static();
	private static final Stations HEAD = new Stations();
	private static final Stations RING7 = new Stations();
	private static final float[] POLY = new float[14];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Matrix4f TOOL = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f(), Q = new Vector3f(), DOWN = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);
	private static final float[] TAIL = new float[3 * ArmDraw.ROPE_NODES];
	private static final Vector3f ROOT_LOCAL = new Vector3f(), ROOT_OUT = new Vector3f(0.3F, -0.95F, 0F).normalize();

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier;
	private static float feedLength, front, low, shaftTop, droop, thin, bristle, spread, fold, flare, sprout, fringeW;
	private static boolean fringeOn;
	/** The stretch of the core or a strand the next tube covers (y px). */
	private static float ty0, ty1;
	private static int strand;
	private static float strandWidth;
	/** The barb the next tube draws: azimuth, and the head's or a lug's. */
	private static float barbAz;
	private static boolean barbLug;
	private static float barbSway;
	private static int pennonSide;
	private static float ringY, ringR;
	private static ArmLayer ridgeLayer = ArmLayer.RED, collarLayer = ArmLayer.RED;

	private static final Loft.CurveFn CORE = (s, out) -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		out.set(0F, y, 0F);
		bend(y, out);
	};
	private static final Loft.RadiusFn CORE_R = s -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		float base = y < 0F ? ArmMath.lerp(1.0F, 1.3F, ArmMath.clamp01((y - BUTT) / -BUTT)) : 1.3F;
		return FistGeometry.width(base * thin * (1F + 0.35F * cur.swell(phi(y), feedLength)), 4);
	};
	private static final Loft.AlphaFn SHAFT_FRINGE = s -> ArmMath.fringe(front - ArmMath.lerp(ty0, ty1, s), fringeW);
	private static final Loft.CurveFn WRAP = (s, out) -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		// the strands wind both ways, so they cross; each is offset round the shaft by its own share of the turn
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
	private static final Loft.CurveFn BARB = (s, out) -> barbPoint(s, out);
	private static final Loft.RadiusFn BARB_R = s -> FistGeometry.width(ArmMath.lerp(0.85F, 0.14F, (float) Math.pow(s, 0.8)) * thin
			* (1F + 0.3F * cur.swell(phi(COLLAR[tier]), feedLength)), 4);
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s + 0.004F * cur.time();
		float r = ringR * (1F + 0.04F * (float) Math.sin(3F * a + 0.05F * cur.time()));
		out.set(r * (float) Math.cos(a), ringY, r * (float) Math.sin(a));
		bend(ringY, out);
	};
	private static final Loft.RadiusFn RING_R = s -> FistGeometry.width(0.5F * thin, 4);
	private static final Loft.CurveFn PENNON = (s, out) -> pennonPoint(s, out);
	private static final Loft.RadiusFn PENNON_R = s -> FistGeometry.width(ArmMath.lerp(0.75F, 0.25F, s), 4);

	@Override
	public String form() {
		return ArmProtocol.SPEAR;
	}

	/** Where the point is at rest, by tier (G px). */
	public static float tipY(int tier) {
		int t = Math.clamp(tier, 1, 3);
		return COLLAR[t] + HEAD_ROOT + HEAD_LEN[t];
	}

	/** The collar's height by tier. */
	public static float collarY(int tier) {
		return COLLAR[Math.clamp(tier, 1, 3)];
	}

	/** The head's length by tier. */
	public static float headLength(int tier) {
		return HEAD_LEN[Math.clamp(tier, 1, 3)];
	}

	/** Strands in the braid, head barbs, lugs, rings, pennons, bristles and tail spikes, by tier: the tiers differ in these. */
	public static int strandCount(int tier) {
		return STRANDS[Math.clamp(tier, 1, 3)];
	}

	public static int barbCount(int tier) {
		return HEAD_BARBS[Math.clamp(tier, 1, 3)].length;
	}

	public static int lugCount(int tier) {
		return LUGS[Math.clamp(tier, 1, 3)].length;
	}

	public static int ringCount(int tier) {
		return RINGS[Math.clamp(tier, 1, 3)];
	}

	public static int pennonCount(int tier) {
		return PENNONS[Math.clamp(tier, 1, 3)];
	}

	public static int bristleCount(int tier) {
		return BRISTLES[Math.clamp(tier, 1, 3)].length;
	}

	public static int tailSpikeCount(int tier) {
		return TAIL_SPIKES[Math.clamp(tier, 1, 3)];
	}

	/**
	 * The head's half-width at {@code u} (0 at the collar, 1 at the point): a leaf. It starts at 55% of its greatest, swells to it
	 * a little over a quarter of the way up, then narrows, bowed, to a point.
	 */
	public static float headHalfWidth(int tier, float u) {
		float w = HEAD_HALF[Math.clamp(tier, 1, 3)];
		float x = ArmMath.clamp01(u);
		if (x <= HEAD_BELLY) return w * (0.55F + 0.45F * ArmMath.sm(x / HEAD_BELLY));
		float r = 1F - (x - HEAD_BELLY) / (1F - HEAD_BELLY);
		return w * (float) Math.pow(r, 0.85) + 0.02F * (x >= 1F ? 0F : 1F);
	}

	/** The head's half-thickness at {@code u}: 0.55 at the collar thinning to 0.12 at the point. */
	public static float headHalfThickness(float u) {
		return ArmMath.lerp(0.55F, 0.12F, ArmMath.clamp01(u));
	}

	@Override
	public float feedLength(int tier) {
		return FistGeometry.PHI_TOOL + tipY(tier) - 1.8F;
	}

	@Override
	public float guiScale() {
		// the point lands at g + k*0.707*y along the slot's diagonal: 0.40 and 4.4 keep the level-3 point (y 35.4) and the butt's
		// tail (y -11.7) between item pixels 1 and 15
		return 0.40F;
	}

	@Override
	public float guiGripPx() {
		return 4.4F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/**
	 * First person draws the spear at half the size G is built in: vanilla holds the spear at scale 1.36 (x and y) where the handheld
	 * tools have 0.68, so a G pixel would come out twice the size of every other form's and the spear would be 3 blocks long. The
	 * display block's depth is already 0.68, so only x and y are halved (measured against ArmTransforms: 30 G px are 2.55 blocks long
	 * in the spear set's first person, 1.275 in the handheld one's). Third person needs none: the hold frame cancels the display scale.
	 */
	public static final float FP_SCALE = 0.5F;

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
		m.transform(TOOL.scaling(sp * fp, sp * fp, sp));
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
		float sw = d.specialWeight(ArmProtocol.FX_SPEAR);
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		bristle = sw;
		spread = sw;
		fold = 0.8F * swing * (1F - sw);
		flare = Math.max(d.hitFlare(), sw);
		sprout = ArmMath.sm((reach - 0.5F) / 0.5F);
		fringeOn = d.dissolve() && (reach < 1F || failing);
		fringeW = failing && reach >= 1F ? 1.7F : d.fringe();
		shaftTop = Math.min(front, COLLAR[tier] - 0.3F);
		ridgeLayer = tier >= 3 && d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		int lod = d.lod();
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m);
			return;
		}
		if (lod == 2) {
			coarse(d, layer, m);
			return;
		}
		shaft(d, layer, m, lod, failing);
		collar(d, layer, m);
		rings(d, layer, m, lod);
		head(d, layer, m, lod);
		barbs(d, layer, m, lod);
		bristles(d, layer, m, lod, swing);
		butt(d, layer, m, lod);
		if (lod == 0) pennons(d, layer, m);
		beads(d, layer, m, failing);
		if (lod == 0 && d.dripsNow() && reach >= 0.99F) drips(d, layer, m);
	}

	// ------------------------------------------------------------------ the shaft

	/** The black core, the braid's strands and (starving) the red cracks across it. */
	private static void shaft(ArmDraw d, ArmLayer layer, SkinMesh m, int lod, boolean failing) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float y0 = Math.max(low, BUTT + 0.6F), y1 = shaftTop;
		if (y1 - y0 < 0.05F) return;
		Loft.AlphaFn fringe = fringeOn ? SHAFT_FRINGE : null;
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
		for (int k = 0; k < n; k++) {
			boolean isRed = k >= STRANDS[tier] - reds;
			ArmLayer strandLayer = isRed ? red : black;
			if (isRed ? !wantRed : !wantBlack) continue;
			strand = k;
			strandWidth = isRed ? 0.5F : 0.56F;
			ty0 = y0;
			ty1 = y1;
			int stations = Math.max(2, Math.round((y1 - y0) * (lod == 0 ? (tier >= 3 ? 0.6F : 0.72F) : 0.4F)));
			Loft.tube(m, WRAP, 0F, 1F, stations, sides, WRAP_R, strandLayer, layer, ST, PART_WRAP + k, 2F, phi(y0), phi(y1), UP_Y, fringe);
		}
		if (wantRed && d.lowStage() >= ArmDraw.LOW_STARVING && lod == 0) {
			// starving cracks: red rings where the braid has split
			for (int j = 0; j < 3; j++) {
				float y = 6F + 6.5F * j;
				if (y > y1 - 0.5F) continue;
				ringY = y;
				ringR = BRAID_R + 0.35F;
				Loft.closedTube(m, RING, 8, 4, RING_R, red, layer, ST, PART_CRACK + j, 1.5F, phi(y), UP_Y, 1F);
			}
		}
	}

	/** The red collar: a seven-sided band that belies, with the pulse's light. */
	private static void collar(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float yc = COLLAR[tier];
		float grow = ArmMath.sm((front - (yc - 0.6F)) / 1.2F);
		if (grow <= 0.02F) return;
		ArmLayer red = tier >= 3 && d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		float swell = 1F + 0.3F * cur.swell(phi(yc), feedLength);
		Stations out = RING7.reset(7).feed(phi(yc - 0.6F));
		out.color(layer == ArmLayer.GLOW ? FistGeometry.grey(0.3F + 0.4F * flare) : -1);
		for (int i = 0; i < 3; i++) {
			float y = yc - 0.6F + 0.6F * i;
			float r = (i == 1 ? 1.5F : 1.2F) * grow * swell;
			ArmMath.ring(POLY, 7, r, 0.92F * r, 0F);
			Vector3f c = Q.set(0F, y, 0F);
			bend(y, c);
			out.add(c.x, c.y, c.z, 0F, 1F, 0F, 1F, 0F, 0F, 0.6F * i, POLY, 1F);
		}
		collarLayer = red;
		Loft.sweep(m, out, layer, (seg, face) -> collarLayer, ST, PART_COLLAR, 1F, false, false);
	}

	/** Red binding rings round the shaft under the collar. */
	private static void rings(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		int n = RINGS[tier];
		ArmLayer red = d.paint(ArmLayer.RED);
		if (n == 0 || lod > 0 || layer != red && !Loft.pulseGlowPass(layer)) return;
		for (int j = 0; j < n; j++) {
			float y = COLLAR[tier] - 2.8F - 3.6F * j;
			if (y > front - 0.4F || y < low + 0.5F) continue;
			ringY = y;
			ringR = BRAID_R + 0.42F;
			Loft.closedTube(m, RING, 8, 4, RING_R, red, layer, ST, PART_RING + j, 1.5F, phi(y), UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ the head

	/**
	 * The leaf-shaped head: sections of six points (the edge, the ridge's flat and its mirror) lofted from the collar to the
	 * point, cut at the growth front. Black, with the ridge's two flats red (wider as a hit or Impale opens them), glowing at
	 * level 3.
	 */
	private static void head(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float base = COLLAR[tier] + HEAD_ROOT, len = HEAD_LEN[tier];
		if (front <= base + 0.05F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red && layer != ridgeLayer && !Loft.pulseGlowPass(layer)) return;
		boolean truncated = front < base + len - 0.02F;
		int total = lod == 0 ? HEAD_STATIONS : HEAD_STATIONS_LOD1;
		Stations out = HEAD.reset(6).feed(phi(base));
		boolean glowPass = layer == ArmLayer.GLOW;
		out.color(glowPass ? FistGeometry.grey(ArmMath.clamp01(0.55F + 0.45F * flare)) : -1);
		float ridge = 0.18F + 0.22F * flare;
		int stage = d.lowStage();
		for (int i = 0; i < total; i++) {
			float u = (float) i / (total - 1);
			// the sections crowd toward the point, where the shape changes fastest
			u = 1F - (1F - u) * (1F - u) * 0.6F - (1F - u) * 0.4F;
			float y = base + len * u;
			boolean last = i == total - 1;
			if (y >= front - 0.02F && !last) {
				// this section would pass the growth front: it is replaced by the front's own below
				break;
			}
			if (last && truncated) break;
			section(out, u, y, base, ridge, stage, 1F);
		}
		if (truncated) {
			float u = ArmMath.clamp01((front - base) / len);
			section(out, u, front, base, ridge, stage, fringeOn ? 0F : 1F);
		}
		if (out.count() < 2) return;
		Loft.sweep(m, out, layer, (seg, face) -> face == 1 || face == 4 ? ridgeLayer : ArmLayer.BLACK, ST, PART_HEAD, 1F, true, truncated);
	}

	/** One section of the head at height {@code y} ({@code u} along it from {@code base}), added to {@code out}. */
	private static void section(Stations out, float u, float y, float base, float ridge, int stage, float alpha) {
		float w = headHalfWidth(tier, u) * (stage >= ArmDraw.LOW_THINNING ? 0.9F : 1F);
		float t = headHalfThickness(u) + 0.1F * cur.swell(phi(y), feedLength);
		// the ridge wavers like a hamon: its width breathes along the head
		float waver = 1F + 0.12F * (float) Math.sin(1.3F * (y - COLLAR[tier]) - 0.2F * cur.time());
		float rw = Math.min(0.9F, ridge * waver) * w;
		POLY[0] = w;
		POLY[1] = 0F;
		POLY[2] = rw;
		POLY[3] = t;
		POLY[4] = -rw;
		POLY[5] = t;
		POLY[6] = -w;
		POLY[7] = 0F;
		POLY[8] = -rw;
		POLY[9] = -t;
		POLY[10] = rw;
		POLY[11] = -t;
		Vector3f c = Q.set(0F, y, 0F);
		bend(y, c);
		out.add(c.x, c.y, c.z, 0F, 1F, 0F, 1F, 0F, 0F, y - base, POLY, alpha);
	}

	// ------------------------------------------------------------------ the barbs

	/**
	 * The harpoon barbs: out of the collar, sweeping out and hooking up round the blade (the head's), and the lugs that hook
	 * back down the shaft. Black, with a red tip; they sway, open while Impale pins and fold back in a jab.
	 */
	private static void barbs(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float grow = ArmMath.sm((front - COLLAR[tier]) / 3F);
		if (grow <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean wantBlack = layer == black, wantRed = layer == red || Loft.pulseGlowPass(layer);
		if (!wantBlack && !wantRed) return;
		int sides = lod == 0 ? 4 : 3;
		int stations = lod == 0 ? 6 : 4;
		float[] heads = HEAD_BARBS[tier], lugs = LUGS[tier];
		int count = heads.length + (lod == 0 ? lugs.length : 0);
		for (int k = 0; k < count; k++) {
			barbLug = k >= heads.length;
			barbAz = barbLug ? lugs[k - heads.length] : heads[k];
			barbSway = 0.06F * (float) Math.sin(0.05F * cur.time() + 1.9F * k + cur.phase());
			setBarb(k);
			float tipFrom = 0.7F;
			float end = grow;
			if (wantBlack) {
				Loft.tube(m, BARB, 0F, Math.min(end, tipFrom + 0.04F), stations, sides, BARB_R, black, layer, ST, PART_BARB + 2 * k, 1.5F, phi(COLLAR[tier]),
						phi(COLLAR[tier]) + 3F, UP_Y, 1F);
			}
			if (wantRed && end > tipFrom) {
				Loft.tube(m, BARB, tipFrom, end, Math.max(3, stations / 2), sides, BARB_R, red, layer, ST, PART_BARB + 2 * k + 1, 2F, phi(COLLAR[tier]),
						phi(COLLAR[tier]) + 3F, UP_Y, 1F);
			}
		}
	}

	/** Sets the control points of barb {@code k}: out of the collar, bowing out, then hooking (up for a head barb, down for a lug). */
	private static void setBarb(int k) {
		float yc = COLLAR[tier];
		float open = 1F + 0.3F * spread - 0.22F * fold;
		float az = barbAz + barbSway;
		float c = (float) Math.cos(az), s = (float) Math.sin(az);
		float r0 = 0.95F, r1, r2, y0, y1, y2;
		if (barbLug) {
			r1 = (2.4F + 0.3F * tier) * open;
			r2 = (2.2F + 0.2F * tier) * open;
			y0 = yc - 0.3F;
			y1 = yc - 1.2F;
			y2 = yc - 4.4F - 0.5F * tier;
		} else {
			r1 = (2.7F + 0.45F * tier) * open;
			r2 = (2.2F + 0.4F * tier) * open;
			y0 = yc + 0.1F;
			y1 = yc + 1.5F;
			y2 = yc + 3.8F + 1.1F * tier;
		}
		P0.set(r0 * c, y0, r0 * s);
		P1.set(r1 * c, y1, r1 * s);
		P2.set(r2 * c, y2, r2 * s);
	}

	private static void barbPoint(float s, Vector3f out) {
		ArmMath.bezier(P0, P1, P2, s, out);
		bend(out.y, out);
	}

	// ------------------------------------------------------------------ the bristles

	/** Short filaments leaning back from the shaft and swaying: streaming in a jab, standing on end while Impale pins. */
	private static void bristles(ArmDraw d, ArmLayer layer, SkinMesh m, int lod, float swing) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black || sprout <= 0.02F || lod >= 2) return;
		float[] at = BRISTLES[tier];
		float time = d.time();
		float thinK = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.7F : 1F;
		for (int k = 0; k < at.length; k++) {
			if (lod == 1 && (k & 1) == 1) continue;
			float y = at[k];
			if (y > front - 0.5F || y < low + 1F) continue;
			float az = ArmMath.TAU * FistGeometry.hash(k * 7 + 1 + 13 * d.seed() % 11) + 0.9F * k;
			float len = (1.9F + 0.5F * (float) Math.sin(0.09F * time + 1.9F * k) + 1.4F * d.surge()) * BR_LEN[k] * (1F + 0.4F * swing) * thinK;
			len = ArmMath.lerp(len, 3F * BR_LEN[k], bristle) * sprout;
			float ang = (float) Math.toRadians(BR_ANG[k]) + 0.3F * (float) Math.sin(0.15F * time + 1.7F * k) * 0.5F + (float) Math.toRadians(40) * swing;
			ang *= 1F - bristle;
			float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
			float dx = (float) Math.cos(az) * ca, dy = -sa, dz = (float) Math.sin(az) * ca;
			Q.set(BRAID_R * 1.05F * (float) Math.cos(az), y, BRAID_R * 1.05F * (float) Math.sin(az));
			bend(y, Q);
			spike(m, Q.x, Q.y, Q.z, dx, dy, dz, len, BR_W[k], 0.12F, PART_BRISTLE + k, 0, m.tint());
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

	// ------------------------------------------------------------------ the butt and the pennons

	/** The butt's knot and its tail spikes, out as the shaft grows down. */
	private static void butt(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float grow = ArmMath.sm((-low - 5.2F) / 2.8F);
		if (grow <= 0.02F || layer != d.paint(ArmLayer.BLACK)) return;
		LOCAL.identity().translate(0F, BUTT, 0F).scale(grow);
		m.box(LOCAL, -0.85F, -0.55F, -0.7F, 1.7F, 1.1F, 1.4F, ST.u0(PART_BUTT, 0, 4F), ST.v0(PART_BUTT, 0, 4F), 1F, 1F, m.tint());
		if (lod >= 1) {
			spike(m, 0F, BUTT - 0.4F * grow, 0F, 0F, -1F, 0F, 3F * grow, 0.9F, 0.12F, PART_SPIKE, 0, m.tint());
			return;
		}
		LOCAL.identity().translate(0.25F * grow, BUTT - 0.55F * grow, 0.1F).rotateZ((float) Math.toRadians(22)).scale(grow);
		m.box(LOCAL, -0.55F, -0.4F, -0.55F, 1.1F, 0.8F, 1.1F, ST.u0(PART_BUTT, 1, 4F), ST.v0(PART_BUTT, 1, 4F), 1F, 1F, m.tint());
		int n = TAIL_SPIKES[tier];
		for (int k = 0; k < n; k++) {
			// the first spike points straight down; the rest fan out round it, further out for each pair
			float fan = k == 0 ? 0F : ((k & 1) == 1 ? 1F : -1F) * 0.28F * ((k + 1) / 2);
			float side = k >= 3 ? 1F : 0F; // the outer pair splay out of the plane
			float len = (k == 0 ? 3.3F : 2.3F) * grow;
			spike(m, 0F, BUTT - 0.4F * grow, 0F, (float) Math.sin(fan) * (1F - side), -(float) Math.cos(fan), (float) Math.sin(fan) * side, len,
					k == 0 ? 0.9F : 0.6F, 0.1F, PART_SPIKE + k, k, m.tint());
		}
	}

	/** The pennons: tendril streaming from under the head on the rope's chain (a still droop where nothing simulates it). */
	private static void pennons(ArmDraw d, ArmLayer layer, SkinMesh m) {
		int n = PENNONS[tier];
		float grow = ArmMath.sm((front - COLLAR[tier] - 1F) / 3F);
		if (n == 0 || grow <= 0.02F || layer != d.paint(ArmLayer.BLACK)) return;
		float[] nodes = d.ropeNodes();
		if (nodes == null || nodes.length < TAIL.length) Rope.droop(ROOT_LOCAL, ROOT_OUT, DOWN, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, TAIL);
		else System.arraycopy(nodes, 0, TAIL, 0, TAIL.length);
		for (int k = 0; k < n; k++) {
			pennonSide = k == 0 ? 1 : -1;
			Loft.tube(m, PENNON, 0F, grow, 9, 4, PENNON_R, layer, layer, ST, PART_PENNON + k, 2F, phi(COLLAR[tier]), phi(COLLAR[tier]) + 8F, UP_Z, 1F);
		}
	}

	/** A point on pennon {@code pennonSide}: Catmull-Rom through the rope's nodes, rooted beside the shaft under the collar. */
	private static void pennonPoint(float s, Vector3f out) {
		int links = ArmDraw.ROPE_NODES - 1;
		float f = ArmMath.clamp01(s) * links;
		int i = Math.min(links - 1, (int) f);
		float u = f - i;
		int i0 = Math.max(0, i - 1), i2 = Math.min(links, i + 1), i3 = Math.min(links, i + 2);
		float x = catmull(TAIL[3 * i0], TAIL[3 * i], TAIL[3 * i2], TAIL[3 * i3], u);
		float y = catmull(TAIL[3 * i0 + 1], TAIL[3 * i + 1], TAIL[3 * i2 + 1], TAIL[3 * i3 + 1], u);
		float z = catmull(TAIL[3 * i0 + 2], TAIL[3 * i + 2], TAIL[3 * i2 + 2], TAIL[3 * i3 + 2], u);
		out.set(0.95F * pennonSide + x * pennonSide, COLLAR[tier] - 1.4F + y, z);
	}

	private static float catmull(float p0, float p1, float p2, float p3, float u) {
		float u2 = u * u, u3 = u2 * u;
		return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
	}

	// ------------------------------------------------------------------ beads and drips

	/** Beads flicked off the point by a hit, shed by a melt, and orbiting where a failing arm's point used to be. */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = d.hitAge();
		if (hitAge >= 0F && hitAge < 8F && front > 1F) {
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float x = (0.7F * a + 0.3F * j) * (j == 0 ? 1F : -1F) + DOWN.x * 0.06F * a * a;
				float y = front + 0.4F * a + 0.2F * j + DOWN.y * 0.06F * a * a;
				float z = DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
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
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, side * 1.5F + DOWN.x * 0.4F * a * a, y + DOWN.y * 0.4F * a * a,
						DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing) {
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * d.time() + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, 0.6F * (float) Math.cos(a), front, 0.6F * (float) Math.sin(a), 0.5F, 16 + k);
			}
		}
	}

	/** Drips off the head's lower edge and the butt's lowest spike. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != d.paint(ArmLayer.BLACK)) return;
		float rate = d.dripRate();
		float yb = COLLAR[tier] + HEAD_ROOT + HEAD_BELLY * HEAD_LEN[tier];
		float side = DOWN.x >= 0F ? 1F : -1F;
		Drips.emit(m, null, side * headHalfWidth(tier, HEAD_BELLY), yb, 0F, DOWN, d.time(), 4 + (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
		if (-low > 7F) Drips.emit(m, null, 0F, BUTT - 3F, 0F, DOWN, d.time(), 1, rate, d.blocksToPx(), ST, PART_DRIP);
	}

	// ------------------------------------------------------------------ levels of detail

	/** LOD 2: boxes for the shaft, collar and butt, a tapered box for the head, a box for each pair of barbs. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float yc = COLLAR[tier];
		if (layer == black && shaftTop - low > 0.05F) {
			m.box(null, -0.8F, low, -0.8F, 1.6F, shaftTop - low, 1.6F, ST.u0(PART_BOX, 0, 10F), ST.v0(PART_BOX, 0, 10F), 1F, 1F, m.tint());
		}
		if (layer == red && front > yc - 0.6F) {
			m.box(null, -1.3F, yc - 0.6F, -1.3F, 2.6F, 1.2F, 2.6F, ST.u0(PART_BOX, 1, 10F), ST.v0(PART_BOX, 1, 10F), 1F, 1F, m.tint());
		}
		float base = yc + HEAD_ROOT;
		if (layer == black && front > base + 0.1F) {
			float len = Math.min(front, tipY(tier)) - base;
			float u = len / HEAD_LEN[tier];
			float w1 = 2F * Math.max(0.05F, headHalfWidth(tier, u));
			LOCAL.translation(0F, base, 0F).rotateX(-ArmMath.PI / 2F);
			m.frustum(LOCAL, len, 2F * headHalfWidth(tier, 0F), 1F, w1, 0.3F, ST.u0(PART_BOX, 2, 10F), ST.v0(PART_BOX, 2, 10F), 1F, 1F, 1F, m.tint());
		}
		if (layer == black && front > yc + 1F) {
			float spreadX = 3.2F + 0.3F * tier;
			m.box(null, -spreadX, yc + 0.6F, -0.3F, 2F * spreadX, 3.5F, 0.6F, ST.u0(PART_BOX, 3, 10F), ST.v0(PART_BOX, 3, 10F), 1F, 1F, m.tint());
		}
		if (layer == black && -low > 5F) {
			m.box(null, -0.8F, BUTT - 0.5F, -0.7F, 1.6F, 1.1F, 1.4F, ST.u0(PART_BOX, 4, 10F), ST.v0(PART_BOX, 4, 10F), 1F, 1F, m.tint());
		}
	}

	/** LOD 3: the shaft and the head, two boxes. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != d.paint(ArmLayer.BLACK)) return;
		float top = Math.min(front, tipY(tier));
		if (top - low > 0.1F) {
			m.box(null, -0.8F, low, -0.7F, 1.6F, Math.min(top, COLLAR[tier]) - low, 1.4F, ST.u0(PART_BOX, 5, 10F), ST.v0(PART_BOX, 5, 10F), 1F, 1F, m.tint());
		}
		float base = COLLAR[tier] + HEAD_ROOT;
		if (top > base + 0.1F) {
			m.box(null, -headHalfWidth(tier, 0.28F), base, -0.4F, 2F * headHalfWidth(tier, 0.28F), top - base, 0.8F, ST.u0(PART_BOX, 6, 10F),
					ST.v0(PART_BOX, 6, 10F), 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		float tip = tipY(d.tier());
		float f = d.reach() * tip;
		if (d.lowStage() == ArmDraw.LOW_FAILING) f = Math.min(f, 0.92F * tip);
		f = Math.max(0F, f) * (d.firstPerson() ? FP_SCALE : 1F); // as drawn
		return out.set(0F, which == ArmTips.MID ? 0.55F * f : f, 0F);
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{-3.8F, 36F, -0.6F}, {3.8F, 36F, 0.6F}, {-3.8F, -12F, -2.4F}, {3.8F, -12F, 2.4F}, {-3.8F, 18F, -3.8F}, {3.8F, 18F, 3.8F},
				{-1F, 36F, -1F}, {1F, -12F, 1F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0], p[1], p[2]));
	}
}
