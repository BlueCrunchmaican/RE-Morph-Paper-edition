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
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The claws (my design, 2026-11-16: the shears-based form whose special, Rake, tears three slashes and leaves its targets
 * bleeding): three or four curved talons of black braided tendril standing over the knuckles, their points hardened to red static.
 * <p>
 * Grip space G (section 2.0), px. The fist closes on a short braided bar along {@code +Y} (red core under one winding ribbon), which the
 * claw fingers of the sheath wrap, so the bar shows between them. Above the fist a cuff ring holds the talons' roots, spread round the
 * leading side ({@code -X}, where the knuckles are and where a swing leads): each talon leaves the ring heading forward along {@code +Y}
 * and curls toward its own outward direction like a cat's claw, the outer ones curling harder and standing shorter, so the four
 * of them fan across both {@code X} and {@code Z} and never cross. A thin strand winds up the root of each (red on every second one, a
 * counter-winding black one from level 2) and the last third of each is hardened static, red, and glowing at level 3. A red bone spike
 * hangs under the fist; from level 2 a cord trails off it on the rope's inertia and one or two black heel spurs curl off the back of the cuff.
 * <p>
 * Level 1 ("Raw") has three talons, 8 px long, one strand each and no spur or cord. Level 2 ("Honed") has four, 9.6 px, a second strand,
 * a spur and a cord. Level 3 ("Awakened") has four 11.4 px talons that glow at the points, two spurs and two cords.
 * <p>
 * Everything moves: the static crawls, pulses swell through the grip and the talons, each curl sways at its own rate, the cord swings.
 * A swing lays the talons back (the curl deepens); a hit flares the points and flicks beads off them; Rake (FX_CLAWS) spreads and
 * straightens them to rake, lengthens them and lights their points. Low biomass thins and shortens them, cracks the braid red, erodes
 * the points and makes the whole spasm. Forming: the grip rises out of the fist, the cuff rings on, then the talons grow one after
 * another out of it, base to point.
 * <p>
 * Stateless between calls; render thread only (shared scratch).
 */
public final class ClawsGeometry implements FormGeometry {
	/** The grip bar's ends in G px and its radius under the fingers. */
	public static final float GRIP_TOP = 2.0F, GRIP_BOTTOM = -2.6F, HILT_R = 0.78F;
	/** Where the cuff ring that carries the talons' roots stands, and its radius. */
	public static final float CUFF_Y = 2.0F, CUFF_R = 1.15F;
	/** The bone spike under the fist: where it starts and how long it is. */
	public static final float SPIKE_Y = -2.5F, SPIKE_LEN = 2.0F;
	/** A talon is drawn from this many samples (fixed: the curve is rebuilt every frame in static scratch). */
	private static final int N = 24;
	/** Where along a talon (0..1) the hardened static begins. */
	public static final float TIP_FROM = 0.68F;

	/** The talons at each level, by their slot: which row of the per-talon table, and the angle off the leading side (degrees). */
	private static final int[][] SLOT_IDX = {{0, 1, 3}, {0, 1, 2, 3}, {0, 1, 2, 3}};
	private static final float[][] SLOT_DELTA = {{-34F, 0F, 34F}, {-48F, -16F, 16F, 48F}, {-48F, -16F, 16F, 48F}};
	/** Length by level, and the radius at the root. */
	private static final float[] LENGTH = {0F, 8.0F, 9.6F, 11.4F}, ROOT_R = {0F, 0.62F, 0.70F, 0.78F};
	/** The table, a row a talon: length factor, the heading at the point (degrees from +Y toward the outward direction), strand turns, width factor. */
	private static final float[] LEN_F = {0.82F, 1.06F, 1.0F, 0.78F}, END_DEG = {78F, 60F, 66F, 86F}, TWIST = {1.7F, -1.3F, 1.9F, -1.5F},
			WIDTH_F = {0.86F, 1.0F, 0.95F, 0.82F};
	private static final float START_DEG = 8F, MAX_HEADING_DEG = 87F;

	private static final int PART_TALON = 1, PART_TIP = 6, PART_STRAND = 10, PART_COUNTER = 16, PART_CRACK = 22, PART_SPUR = 28, PART_CUFF = 34, PART_KNOT = 36,
			PART_RIBBON = 44, PART_THIN = 45, PART_CORE = 46, PART_RING = 47, PART_SPIKE = 48, PART_CORD = 50, PART_DRIP = 54, PART_FAR = 58;

	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Vector3f DOWN = new Vector3f(), Q = new Vector3f(), P = new Vector3f();
	/** The cord's root when nothing simulates it (never written: Rope.droop only reads it). */
	private static final Vector3f ZERO = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);
	private static final float[] PL = new float[3 * 6];
	private static final float[] CORD = new float[3 * ArmDraw.ROPE_NODES];
	private static final Vector3f CORD_ROOT = new Vector3f(0.45F, SPIKE_Y - 0.1F, 0.25F);
	private static final Vector3f CORD_OUT = new Vector3f(0.25F, -0.97F, 0F).normalize();

	// the talon being drawn: its centre line and the two directions a strand winds in, sampled evenly along it
	private static final float[] CX = new float[N], CY = new float[N], CZ = new float[N], NX = new float[N], NY = new float[N], NZ = new float[N],
			BX = new float[N], BY = new float[N], BZ = new float[N];
	private static float talonLen, talonRoot, strandTurns, strandPhase, strandSign;
	private static int talonSides;

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, lod, count, tailSet;
	private static float time, feedLength, spread, lenMul, curl, rake, flare, swing, yHi, yLo, ringScale, droop, tipGrey, frontG, fringeW;

	private static final Loft.CurveFn TALON = (s, out) -> centreAt(s, out);
	private static final Loft.RadiusFn TALON_R = s -> {
		float taper = 0.93F * (float) Math.pow(Math.max(0F, 1F - s), 0.75F) + 0.07F;
		float swell = 1F + 0.3F * cur.swell(talonPhi(s), feedLength);
		return FistGeometry.width(talonRoot * taper * swell, talonSides);
	};
	private static final Loft.AlphaFn FRINGE = s -> ArmMath.fringe((frontG - s) * talonLen, fringeW);
	private static final Loft.ColourFn TIP_COLOUR = s -> FistGeometry.grey(tipGrey);
	private static final Loft.CurveFn STRAND = (s, out) -> {
		centreAt(s, out);
		float f = ArmMath.clamp01(s) * (N - 1);
		int i = Math.min(N - 2, (int) f);
		float u = f - i;
		float nx = ArmMath.lerp(NX[i], NX[i + 1], u), ny = ArmMath.lerp(NY[i], NY[i + 1], u), nz = ArmMath.lerp(NZ[i], NZ[i + 1], u);
		float bx = ArmMath.lerp(BX[i], BX[i + 1], u), by = ArmMath.lerp(BY[i], BY[i + 1], u), bz = ArmMath.lerp(BZ[i], BZ[i + 1], u);
		float a = strandSign * ArmMath.TAU * strandTurns * s + strandPhase + 0.015F * time * strandSign;
		float taper = 0.93F * (float) Math.pow(Math.max(0F, 1F - s), 0.75F) + 0.07F;
		float r = 0.82F * talonRoot * taper * (1F + 0.3F * cur.swell(talonPhi(s), feedLength));
		float c = r * (float) Math.cos(a), sn = r * (float) Math.sin(a);
		out.add(c * nx + sn * bx, c * ny + sn * by, c * nz + sn * bz);
	};
	private static final Loft.RadiusFn STRAND_R = s -> FistGeometry.width(0.3F * talonRoot / 0.62F * (0.35F + 0.65F * (1F - s)), 4);
	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * 3F;
		int i = Math.min(2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(PL[3 * i], PL[3 * i + 3], u), ArmMath.lerp(PL[3 * i + 1], PL[3 * i + 4], u), ArmMath.lerp(PL[3 * i + 2], PL[3 * i + 5], u));
	};
	private static final Loft.RadiusFn SPUR_R = s -> FistGeometry.width(ArmMath.lerp(0.62F, 0.1F, s) * ringScale, 4);
	private static final Loft.CurveFn RIBBON = (s, out) -> {
		float y = ArmMath.lerp(yHi, yLo, s);
		float a = ArmMath.TAU * (y - GRIP_BOTTOM) / 2.1F;
		float wr = 1F + 0.05F * (float) Math.sin(cur.phase() + 5F * s + 0.31F * time);
		out.set(0.55F * wr * (float) Math.cos(a), y, 0.55F * wr * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RIBBON_W = s -> FistGeometry.width(0.74F * (1F + 0.3F * ringScale), 4);
	private static final Loft.CurveFn THIN = (s, out) -> {
		float y = ArmMath.lerp(yHi, yLo, s);
		float a = -ArmMath.TAU * (y - GRIP_BOTTOM) / 1.7F + ArmMath.PI;
		float wr = 1F + 0.06F * (float) Math.sin(cur.phase() * 0.8F + 7F * s + 0.37F * time);
		out.set(0.62F * wr * (float) Math.cos(a), y, 0.62F * wr * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn THIN_W = s -> FistGeometry.width(0.3F * (1F + 0.3F * ringScale), 4);
	private static final Loft.CurveFn CUFF = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = CUFF_R * (1F + 0.06F * (float) Math.cos(3F * (a - 0.02F * time))) * ringScale;
		out.set(r * (float) Math.cos(a), CUFF_Y + 0.12F * (float) Math.sin(3F * (a - 0.02F * time)) * ringScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn CUFF_W = s -> FistGeometry.width(0.62F * ringScale, 4);
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 0.95F * ringScale;
		out.set(r * (float) Math.cos(a), -2.05F + 0.08F * (float) Math.sin(3F * a) * ringScale, r * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.4F * ringScale, 4);
	private static final Loft.CurveFn CORD_CURVE = (s, out) -> cordPoint(s, out);
	private static final Loft.RadiusFn CORD_W = s -> FistGeometry.width(ArmMath.lerp(0.5F, 0.22F, s), 4);

	public ClawsGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.CLAWS;
	}

	@Override
	public float feedLength(int tier) {
		return FistGeometry.PHI_TOOL + 2F + LENGTH[Math.clamp(tier, 1, 3)] * 1.06F;
	}

	@Override
	public float guiScale() {
		// the fan stands up the diagonal and curls to its leading side: level 3 with Rake's spread is the widest it gets
		return 0.62F;
	}

	@Override
	public float guiGripPx() {
		return 5.6F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** How many talons stand at {@code tier}: three, then four. */
	public static int talonCount(int tier) {
		return SLOT_IDX[Math.clamp(tier, 1, 3) - 1].length;
	}

	/** A talon's length (px) at {@code tier}, before its own factor. */
	public static float talonLength(int tier) {
		return LENGTH[Math.clamp(tier, 1, 3)];
	}

	/** The heel spurs at {@code tier}: none, one, two. */
	public static int spurCount(int tier) {
		return Math.clamp(tier, 1, 3) - 1;
	}

	/** The cords trailing off the spike: none, one, two. */
	public static int cordCount(int tier) {
		return Math.clamp(tier, 1, 3) - 1;
	}

	/** The strands winding each talon's root: one, then two. */
	public static int strandCount(int tier) {
		return tier <= 1 ? 1 : 2;
	}

	/** The feed coordinate on the grip bar at height {@code y}: up and down from the fist. */
	static float phiGrip(float y) {
		float a = Math.abs(y);
		return a >= 1.8F ? FistGeometry.PHI_TOOL + a - 1.8F : FistGeometry.PHI_TOOL - (1.8F - a) * 1.1F;
	}

	/** The feed coordinate along a talon at {@code s} (0 at its root). */
	private static float talonPhi(float s) {
		return FistGeometry.PHI_TOOL + 2F + s * talonLen;
	}

	/** FORM's pop for something appearing {@code x} ticks after its moment: 0 to 1.25 to 1.0 over 2 ticks. */
	private static float pop(float x) {
		if (x <= 0F) return 0F;
		if (x < 1F) return 1.25F * ArmMath.sm(x);
		if (x < 2F) return ArmMath.lerp(1.25F, 1F, ArmMath.sm(x - 1F));
		return 1F;
	}

	// ------------------------------------------------------------------ the talons' shape

	/** Works out what every part of this emit reads from the draw. */
	private static void params(ArmDraw d) {
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		lod = Math.clamp(d.lod(), 0, 3);
		count = SLOT_IDX[tier - 1].length;
		time = d.time();
		feedLength = LENGTH[tier] > 0F ? FistGeometry.PHI_TOOL + 2F + LENGTH[tier] * 1.06F : 0F;
		rake = d.specialWeight(ArmProtocol.FX_CLAWS);
		swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		flare = Math.max(d.hitFlare(), rake);
		int stage = d.lowStage();
		float thin = stage >= ArmDraw.LOW_FAILING ? 0.82F : stage >= ArmDraw.LOW_THINNING ? 0.92F : 1F;
		// Rake opens and straightens them; a surge lengthens them; a swing lays them back
		lenMul = (1F + 0.18F * rake + 0.12F * ArmMath.clamp01(d.surge())) * thin * (1F - 0.05F * swing);
		curl = (1F - 0.24F * rake) * (1F + 0.3F * swing);
		spread = 1F + 0.55F * rake;
		droop = d.droop();
	}

	/**
	 * Samples talon {@code slot} of this level into the scratch arrays: its centre line, and at every sample the two directions a strand
	 * winds in (N toward the talon's outward side, B across it). The curve leaves the cuff ring heading forward along +Y and bends
	 * toward its outward direction, more and more toward the point, fanned sideways by its place among the others.
	 */
	private static void sample(int slot) {
		int idx = SLOT_IDX[tier - 1][slot];
		float deltaDeg = SLOT_DELTA[tier - 1][slot] * spread;
		float a = ArmMath.PI + (float) Math.toRadians(deltaDeg);
		float ox = (float) Math.cos(a), oz = (float) Math.sin(a), tx = -oz, tz = ox;
		float len = LENGTH[tier] * LEN_F[idx] * lenMul * (1F + 0.02F * (float) Math.sin(0.07F * time + 1.9F * slot));
		float end = (float) Math.toRadians(END_DEG[idx]) * curl * (1F + 0.07F * (float) Math.sin(0.11F * time + 1.7F * slot));
		end = Math.min(end, (float) Math.toRadians(MAX_HEADING_DEG));
		float start = (float) Math.toRadians(START_DEG);
		float splay = 0.9F * (float) Math.sin(Math.toRadians(deltaDeg));
		float step = len / (N - 1);
		float x = (CUFF_R - 0.2F) * ox, y = CUFF_Y, z = (CUFF_R - 0.2F) * oz;
		for (int i = 0; i < N; i++) {
			CX[i] = x;
			CY[i] = y;
			CZ[i] = z;
			float u = i / (float) (N - 1);
			float phi = start + (end - start) * u * (float) Math.sqrt(u);
			float sp = splay * (0.2F + 0.8F * u);
			float hx = (float) Math.sin(phi) * ox + sp * tx, hy = (float) Math.cos(phi), hz = (float) Math.sin(phi) * oz + sp * tz;
			float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
			x += hx / hl * step;
			y += hy / hl * step;
			z += hz / hl * step;
			if (droop > 0F) {
				// melting: the tips sag toward world down as the arm goes
				float f = droop * u * u * 0.12F;
				x += DOWN.x * f;
				y += DOWN.y * f;
				z += DOWN.z * f;
			}
		}
		for (int i = 0; i < N; i++) {
			int a0 = Math.max(0, i - 1), a1 = Math.min(N - 1, i + 1);
			float dx = CX[a1] - CX[a0], dy = CY[a1] - CY[a0], dz = CZ[a1] - CZ[a0];
			float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			dx /= dl;
			dy /= dl;
			dz /= dl;
			// N: the outward direction made square to the heading; B = T x N
			float dot = ox * dx + oz * dz;
			float nx = ox - dot * dx, ny = -dot * dy, nz = oz - dot * dz;
			float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (nl < 1e-4F) {
				nx = tx;
				ny = 0F;
				nz = tz;
				nl = 1F;
			}
			nx /= nl;
			ny /= nl;
			nz /= nl;
			NX[i] = nx;
			NY[i] = ny;
			NZ[i] = nz;
			BX[i] = dy * nz - dz * ny;
			BY[i] = dz * nx - dx * nz;
			BZ[i] = dx * ny - dy * nx;
		}
		talonLen = len;
		talonRoot = ROOT_R[tier] * WIDTH_F[idx] * (lowThin() ? 0.88F : 1F);
		strandTurns = TWIST[idx];
		strandPhase = idx * 1.3F + 0.4F;
		strandSign = 1F;
	}

	private static boolean lowThin() {
		return cur.lowStage() >= ArmDraw.LOW_THINNING;
	}

	/** The talon's centre line at {@code s} (0..1 along it): the sampled curve, linear between samples. */
	private static void centreAt(float s, Vector3f out) {
		float f = ArmMath.clamp01(s) * (N - 1);
		int i = Math.min(N - 2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(CX[i], CX[i + 1], u), ArmMath.lerp(CY[i], CY[i + 1], u), ArmMath.lerp(CZ[i], CZ[i + 1], u));
	}

	/** How much of talon {@code slot} is out, 0..1 along it: each sprouts after the one before, base to point. */
	private static float grow(ArmDraw d, int slot, boolean detailed, float ft, float reach) {
		float g = detailed ? ArmMath.sm((ft - (5F + 0.9F * slot)) / 3.2F) : ArmMath.sm((reach * 1.3F - 0.3F - 0.05F * slot) / 0.7F);
		if (d.lowStage() >= ArmDraw.LOW_FAILING) g = Math.min(g, 0.9F);
		return g;
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (d == null || layer == ArmLayer.SENSE) return;
		params(d);
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
		// the grip rises out of the fist both ways first; the cuff rings on, then the talons grow out of it
		if (detailed) {
			yHi = GRIP_TOP * ArmMath.sm((ft - 1F) / 3F);
			yLo = GRIP_BOTTOM * ArmMath.sm((ft - 1.5F) / 3.5F);
			ringScale = pop(ft - 4F);
		} else {
			float g = ArmMath.sm(reach / 0.45F);
			yHi = GRIP_TOP * g;
			yLo = GRIP_BOTTOM * g;
			ringScale = ArmMath.sm((reach - 0.3F) / 0.25F);
		}
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m, detailed, ft, reach);
			return;
		}
		grip(d, layer, m, detailed, ft);
		spike(d, layer, m, detailed, ft);
		if (lod <= 1) cuff(d, layer, m, detailed, ft);
		if (lod >= 2) {
			talonsFar(d, layer, m, detailed, ft, reach);
			return;
		}
		talons(d, layer, m, detailed, ft, reach);
		if (lod == 0) {
			spurs(d, layer, m, detailed, ft);
			cords(d, layer, m, detailed, ft);
		}
		beads(d, layer, m, detailed, ft, reach, rt);
		if (lod == 0 && d.dripsNow() && (!detailed || ft >= 13F)) drips(d, layer, m);
	}

	// ------------------------------------------------------------------ the talons

	private static void talons(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float reach) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		boolean glowTips = d.glow() && tier >= 3;
		ArmLayer tipFaces = glowTips ? ArmLayer.GLOW : red;
		if (layer != black && layer != red && layer != ArmLayer.GLOW) return;
		boolean flaring = d.glow() && !glowTips && flare > 0.05F;
		int stage = d.lowStage();
		boolean failing = stage >= ArmDraw.LOW_FAILING;
		talonSides = lod == 0 ? 5 : 4;
		int stations = lod == 0 ? 12 : 7;
		boolean dissolving = d.dissolve();
		fringeW = failing && d.reach() >= 1F ? 1.5F : d.fringe();
		for (int slot = 0; slot < count; slot++) {
			float g = grow(d, slot, detailed, ft, reach);
			if (g <= 0.02F) continue;
			sample(slot);
			frontG = g;
			boolean growing = g < 0.999F || failing;
			Loft.AlphaFn fringe = dissolving && growing ? FRINGE : null;
			float body = Math.min(g, TIP_FROM + 0.01F);
			// the black body, root to where the hardened point begins
			if (layer == black) {
				int n = Math.max(3, Math.round(stations * body));
				Loft.tube(m, TALON, 0F, body, n, talonSides, TALON_R, black, layer, ST, PART_TALON + slot, 2F, talonPhi(0F), talonPhi(body), UP_Z, fringe);
			}
			// the hardened point: red static (glowing at level 3), its edge a little finer than the body's
			if (g > TIP_FROM) {
				int n = Math.max(3, Math.round(stations * (g - TIP_FROM)) + 1);
				if (glowTips) {
					tipGrey = ArmMath.clamp01(0.55F + 0.45F * flare);
					if (layer == ArmLayer.GLOW) Loft.colourNext(TIP_COLOUR);
				}
				Loft.tube(m, TALON, TIP_FROM, g, n, talonSides, TALON_R, tipFaces, layer, ST, PART_TIP + slot, 2F, talonPhi(TIP_FROM), talonPhi(g), UP_Z, fringe);
				if (flaring && layer == ArmLayer.GLOW) {
					tipGrey = ArmMath.clamp01(flare);
					Loft.colourNext(TIP_COLOUR);
					Loft.tube(m, TALON, TIP_FROM, g, n, talonSides, TALON_R, ArmLayer.GLOW, layer, ST, PART_TIP + slot, 2F, talonPhi(TIP_FROM), talonPhi(g), UP_Z, fringe);
				}
			}
			// a starving arm's braid cracks red across the root
			if (stage >= ArmDraw.LOW_STARVING && layer == red && g > 0.42F) {
				Loft.tube(m, TALON, 0.30F, 0.40F, 3, talonSides, TALON_R, red, layer, ST, PART_CRACK + slot, 2F, talonPhi(0.3F), talonPhi(0.4F), UP_Z, 1F);
			}
			if (lod == 0 && g > 0.14F) strands(d, layer, m, slot, g, black, red, slot);
		}
	}

	/** The strands winding up each talon's root: one (red on every second talon, black on the others), and from level 2 a black one the other way. */
	private static void strands(ArmDraw d, ArmLayer layer, SkinMesh m, int slot, float g, ArmLayer black, ArmLayer red, int part) {
		float s0 = 0.04F, s1 = Math.min(g - 0.02F, TIP_FROM - 0.04F);
		if (s1 - s0 < 0.1F) return;
		int n = Math.max(4, Math.round(7 * (s1 - s0) / (TIP_FROM - 0.08F)) + 2);
		ArmLayer a = (slot & 1) == 1 ? red : black;
		if (layer == a || (a == red && layer == ArmLayer.GLOW)) {
			strandSign = 1F;
			Loft.tube(m, STRAND, s0, s1, n, 4, STRAND_R, a, layer, ST, PART_STRAND + part, 2F, talonPhi(s0), talonPhi(s1), UP_Z, 1F);
		}
		if (tier >= 2 && layer == black) {
			strandSign = -1F;
			strandPhase += 2.1F;
			Loft.tube(m, STRAND, s0, s1, n, 4, STRAND_R, black, layer, ST, PART_COUNTER + part, 2F, talonPhi(s0), talonPhi(s1), UP_Z, 1F);
		}
		strandSign = 1F;
	}

	/** LOD 2: each talon one tapering box along its chord, with a red point. */
	private static void talonsFar(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float reach) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer != black && layer != red) return;
		for (int slot = 0; slot < count; slot++) {
			float g = grow(d, slot, detailed, ft, reach);
			if (g <= 0.05F) continue;
			sample(slot);
			centreAt(0F, Q);
			centreAt(g, P);
			float dx = P.x - Q.x, dy = P.y - Q.y, dz = P.z - Q.z;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 0.2F) continue;
			LOCAL.translation(Q).rotateTowards(dx, dy, dz, 0F, 1F, 0F);
			float w = 2F * talonRoot;
			if (layer == black) {
				m.frustum(LOCAL, len * 0.8F, w, w, w * 0.45F, w * 0.45F, ST.u0(PART_TALON, slot, 12F), ST.v0(PART_TALON, slot, 12F), 1.5F, 1F, 1F, m.tint());
			} else {
				LOCAL.translate(0F, 0F, len * 0.8F);
				m.frustum(LOCAL, len * 0.2F, w * 0.45F, w * 0.45F, 0.1F, 0.1F, ST.u0(PART_TIP, slot, 14F), ST.v0(PART_TIP, slot, 14F), 1.5F, 1F, 1F, m.tint());
			}
		}
	}

	// ------------------------------------------------------------------ the heel spurs

	/** The spurs off the cuff's back (level 2 one, level 3 two): short curved thorns leaning up and away, black with a red point. */
	private static void spurs(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		int n = spurCount(tier);
		if (n == 0) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		for (int k = 0; k < n; k++) {
			float grow = detailed ? pop(ft - 9F - 0.8F * k) : ringScale;
			if (grow <= 0.05F) continue;
			ringScale = Math.min(1.25F, grow);
			float a = (float) Math.toRadians(k == 0 ? 25 : -25) + (n == 1 ? 0F : 0F);
			float ox = (float) Math.cos(a), oz = (float) Math.sin(a);
			float len = (k == 0 ? 3.3F : 2.6F) * (1F + 0.12F * (float) Math.sin(0.09F * time + k)) * grow;
			float r0 = CUFF_R - 0.15F;
			PL[0] = r0 * ox;
			PL[1] = CUFF_Y - 0.2F;
			PL[2] = r0 * oz;
			float x = PL[0], y = PL[1], z = PL[2];
			for (int j = 1; j <= 3; j++) {
				// out along the trailing side, curling up
				float ang = 0.35F + 0.5F * j + 0.12F * (float) Math.sin(0.12F * time + 1.3F * k + j);
				x += (ox * (float) Math.cos(ang) * 0.9F) * len / 3F;
				y += (float) Math.sin(ang) * len / 3F;
				z += (oz * (float) Math.cos(ang) * 0.9F) * len / 3F;
				PL[3 * j] = x;
				PL[3 * j + 1] = y;
				PL[3 * j + 2] = z;
			}
			Loft.tube(m, POLYLINE, 0F, 1F, 5, 4, SPUR_R, black, layer, ST, PART_SPUR + k, 2F, FistGeometry.PHI_TOOL + 1F, FistGeometry.PHI_TOOL + 1F + len, UP_Z, 1F);
		}
		ringScale = 1F;
	}

	// ------------------------------------------------------------------ the cuff and the grip

	/**
	 * The cuff: a ring of tendril round the fist's top holding the talons' roots, a red knot on it at the foot of each talon (and
	 * the knuckle line's, so the roots read as knuckles), turning slowly.
	 */
	private static void cuff(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (ringScale <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			Loft.closedTube(m, CUFF, lod == 0 ? 9 : 6, 4, CUFF_W, black, layer, ST, PART_CUFF, 2F, phiGrip(CUFF_Y), UP_Y, 1F);
		}
		if (layer == red || Loft.pulseGlowPass(layer)) {
			for (int slot = 0; slot < count; slot++) {
				float a = ArmMath.PI + (float) Math.toRadians(SLOT_DELTA[tier - 1][slot] * spread);
				float grow = detailed ? pop(ft - (4.6F + 0.6F * slot)) : ringScale;
				if (grow <= 0.02F) continue;
				float k = 0.85F + 0.1F * cur.swell(phiGrip(CUFF_Y), feedLength);
				LOCAL.translation(CUFF_R * (float) Math.cos(a), CUFF_Y, CUFF_R * (float) Math.sin(a)).rotateY(-a).rotateZ(0.2F * (float) Math.sin(0.04F * time + slot)).scale(grow * k);
				m.box(LOCAL, -0.38F, -0.42F, -0.42F, 0.76F, 0.84F, 0.84F, ST.u0(PART_KNOT, slot, phiGrip(CUFF_Y)), ST.v0(PART_KNOT, slot, phiGrip(CUFF_Y)), 1.5F, 1F, m.tint());
			}
		}
	}

	/**
	 * The grip bar the fingers close on: a red core under one broad ribbon winding up it and, from level 2, a thin one winding the
	 * other way; a bone ring at its foot.
	 */
	private static void grip(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (yHi - yLo <= 0.05F) return;
		if (lod >= 2) {
			if (layer == black) m.box(null, -0.7F, yLo, -0.62F, 1.4F, yHi - yLo, 1.24F, ST.u0(PART_RIBBON, 0, 10F), ST.v0(PART_RIBBON, 0, 10F), 1F, 1F, m.tint());
			return;
		}
		if (layer == red) {
			m.box(null, -0.46F, yLo, -0.4F, 0.92F, yHi - yLo, 0.8F, ST.u0(PART_CORE, 0, 10F), ST.v0(PART_CORE, 0, 10F), 1F, 1F, m.tint());
		}
		if (layer != black) return;
		int stations = lod == 0 ? 22 : 10;
		int n = Math.max(2, Math.round(stations * (yHi - yLo) / (GRIP_TOP - GRIP_BOTTOM)));
		float saved = ringScale;
		ringScale = d.swell(phiGrip(0.5F * (yHi + yLo)), feedLength);
		Loft.tube(m, RIBBON, 0F, 1F, n, 4, RIBBON_W, black, layer, ST, PART_RIBBON, 2F, phiGrip(yHi), phiGrip(yLo), UP_Y, 1F);
		if (tier >= 2 && lod == 0) {
			Loft.tube(m, THIN, 0F, 1F, n + 2, 4, THIN_W, black, layer, ST, PART_THIN, 2F, phiGrip(yHi), phiGrip(yLo), UP_Y, 1F);
		}
		ringScale = detailed ? pop(ft - 5.2F) : saved;
		if (ringScale > 0.02F && yLo < -2F) {
			Loft.closedTube(m, RING, lod == 0 ? 7 : 5, 4, RING_W, black, layer, ST, PART_RING, 2F, phiGrip(-2.05F), UP_Y, 1F);
		}
		ringScale = saved;
	}

	/** The bone spike under the fist: a thorn pointing down, red, the cord's anchor. */
	private static void spike(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (lod >= 2 || layer != red && !Loft.pulseGlowPass(layer)) return;
		float grow = detailed ? pop(ft - 3.5F) : ArmMath.sm((yHi - yLo) / (GRIP_TOP - GRIP_BOTTOM) - 0.2F);
		if (grow <= 0.02F || yLo > SPIKE_Y + 0.4F) return;
		float len = SPIKE_LEN * grow;
		float w = 1.05F * (1F + 0.2F * cur.swell(phiGrip(SPIKE_Y), feedLength));
		LOCAL.translation(0F, SPIKE_Y, 0F).rotateTowards(0F, -1F, 0F, 0F, 0F, 1F);
		m.frustum(LOCAL, len, w, w * 0.9F, 0.08F, 0.08F, ST.u0(PART_SPIKE, 0, phiGrip(SPIKE_Y)), ST.v0(PART_SPIKE, 0, phiGrip(SPIKE_Y)), 2F, 1F, 1F, m.tint());
	}

	// ------------------------------------------------------------------ the cords

	/** From level 2 a cord (level 3 two, turned 120 degrees) trails off the spike on the rope's inertia. */
	private static void cords(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		int n = cordCount(tier);
		ArmLayer black = d.paint(ArmLayer.BLACK);
		// not in an icon: a cord hanging down the slot would shrink the claws to fit it
		if (n == 0 || layer != black || d.gui()) return;
		float grow = detailed ? ArmMath.sm((ft - 9F) / 3F) : ArmMath.sm((yHi - yLo) / (GRIP_TOP - GRIP_BOTTOM) - 0.2F);
		if (grow <= 0.02F) return;
		float[] nodes = d.ropeNodes();
		if (nodes == null || nodes.length < CORD.length) {
			Rope.droop(ZERO, CORD_OUT, DOWN, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, CORD);
		} else {
			System.arraycopy(nodes, 0, CORD, 0, CORD.length);
		}
		for (int k = 0; k < n; k++) {
			tailSet = k;
			Loft.tube(m, CORD_CURVE, 0F, grow, 8, 4, CORD_W, black, layer, ST, PART_CORD + k, 2F, phiGrip(SPIKE_Y), phiGrip(SPIKE_Y) + 8F, UP_Z, 1F);
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

	private static float catmull(float p0, float p1, float p2, float p3, float u) {
		float u2 = u * u, u3 = u2 * u;
		return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
	}

	// ------------------------------------------------------------------ beads and drips

	/**
	 * Loose beads: two flicked off the points by a hit, beads dropping off retracting points, a melt shedding them, and a failing arm's
	 * three beads orbiting where its points used to be.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float reach, float rt) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = Math.min(d.hitAge(), d.breakAge());
		if (hitAge >= 0F && hitAge < 8F && reach >= 1F) {
			for (int j = 0; j < 2; j++) {
				int slot = j == 0 ? 1 : count - 1;
				sample(slot);
				centreAt(1F, Q);
				float a = hitAge;
				float x = Q.x + 0.5F * a * ((j == 0) ? -1F : 1F) + DOWN.x * 0.06F * a * a;
				float y = Q.y + 0.6F * a + DOWN.y * 0.06F * a * a;
				float z = Q.z + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
			}
		}
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < count; k++) {
				float a = rt - (2F + 1.2F * k);
				if (a < 0F || a > 3F) continue;
				sample(k);
				centreAt(1F - ArmMath.sm(1.2F * k / 7F), Q);
				FistGeometry.bead(d, layer, m, ST, k % 2 == 0 ? ArmLayer.BLACK : ArmLayer.RED, Q.x + DOWN.x * 0.5F * a * a, Q.y + DOWN.y * 0.5F * a * a,
						Q.z + DOWN.z * 0.5F * a * a, 0.5F * (1F - a / 3F), 2 + k);
			}
		}
		float mt = d.meltTick();
		if (mt >= 2F && mt < 20F) {
			int newest = (int) Math.floor(mt * 2F);
			for (int q = 0; q < 6; q++) {
				int idx = newest - q;
				float born = idx / 2F;
				if (born < 2F || born > 16F) continue;
				float a = mt - born;
				if (a < 0F || a > 4F) continue;
				sample(Math.floorMod(idx, count));
				centreAt(1F - ArmMath.sm((born - 2F) / 14F), Q);
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, Q.x + DOWN.x * 0.4F * a * a, Q.y + DOWN.y * 0.4F * a * a,
						Q.z + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (d.lowStage() >= ArmDraw.LOW_FAILING) {
			sample(1);
			centreAt(0.9F, Q);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * time + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, Q.x + 0.6F * (float) Math.cos(a), Q.y, Q.z + 0.6F * (float) Math.sin(a), 0.5F,
						16 + k);
			}
		}
	}

	/** Drips off two talon points and the cord's end, each only while its piece is out. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float rate = d.dripRate();
		int j = 0;
		for (int k = 0; k < 2; k++) {
			int slot = k == 0 ? 1 : count - 1; // the two talon points: no array per frame
			if (grow(d, slot, d.detailedForm(), d.formTick(), d.reach()) < 0.95F) {
				j++;
				continue;
			}
			sample(slot);
			centreAt(1F, Q);
			Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, time, j + 4 * (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
			j++;
		}
		if (tier >= 2 && yLo < SPIKE_Y + 0.4F) {
			tailSet = 0;
			cordPoint(1F, Q);
			Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, time, 3, rate, d.blocksToPx(), ST, PART_DRIP);
		}
	}

	// ------------------------------------------------------------------ LOD 3

	/** LOD 3: the grip and the talons' fan as three boxes. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float reach) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black && yHi - yLo > 0.1F) {
			m.box(null, -0.7F, yLo, -0.65F, 1.4F, yHi - yLo, 1.3F, ST.u0(PART_FAR, 0, 10F), ST.v0(PART_FAR, 0, 10F), 1F, 1F, m.tint());
		}
		float g = detailed ? ArmMath.sm((ft - 5F) / 5F) : ArmMath.sm((reach * 1.3F - 0.3F) / 0.7F);
		if (g <= 0.05F) return;
		// the fan as one tapering block standing on the cuff and leaning to the leading side, its point red
		float len = LENGTH[tier] * 0.85F * g;
		float w = 2.2F * ROOT_R[tier] * (1F + 0.6F * ArmMath.clamp01(spread - 1F));
		LOCAL.translation(-0.4F, CUFF_Y, 0F).rotateTowards(-0.3F, 1F, 0F, 0F, 0F, 1F);
		if (layer == black) {
			m.frustum(LOCAL, len * 0.7F, w * 1.6F, w, w * 0.7F, w * 0.45F, ST.u0(PART_FAR, 1, 20F), ST.v0(PART_FAR, 1, 20F), 1F, 1F, 1F, m.tint());
		} else if (layer == red) {
			LOCAL.translate(0F, 0F, len * 0.7F);
			m.frustum(LOCAL, len * 0.3F, w * 0.7F, w * 0.45F, 0.1F, 0.1F, ST.u0(PART_FAR, 2, 20F), ST.v0(PART_FAR, 2, 20F), 1F, 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(0F, 0F, 0F);
		params(d);
		d.down(DOWN);
		int slot = 1;
		float g = grow(d, slot, d.detailedForm(), d.formTick(), d.reach());
		sample(slot);
		centreAt(which == ArmTips.MID ? 0.55F * g : g, out);
		return out;
	}

	/** Talon {@code slot}'s point at rest for a tier-{@code tier} draw, into {@code out}: for the tests and the extents. */
	static Vector3f restTip(int tier, int slot, Vector3f out) {
		params(ArmDraw.rest(ArmProtocol.CLAWS, tier, ArmDraw.CTX_TP, 0, 0F));
		DOWN.set(0F, -1F, 0F);
		sample(slot);
		centreAt(1F, out);
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		DOWN.set(0F, -1F, 0F);
		// the rest pose at level 3, and the same with Rake's spread (the widest it gets), through the cuff and the spike
		for (int pass = 0; pass < 2; pass++) {
			params(ArmDraw.rest(ArmProtocol.CLAWS, 3, ArmDraw.CTX_TP, 0, 0F));
			if (pass == 1) {
				spread = 1.55F;
				lenMul *= 1.18F;
				curl *= 0.76F;
			}
			for (int slot = 0; slot < count; slot++) {
				sample(slot);
				for (int i = 0; i < N; i += 3) {
					out.accept(new Vector3f(CX[i], CY[i], CZ[i]));
				}
				out.accept(new Vector3f(CX[N - 1], CY[N - 1], CZ[N - 1]));
			}
		}
		float[][] pts = {{1.4F, GRIP_TOP, 1.4F}, {-1.4F, GRIP_TOP, -1.4F}, {1.1F, GRIP_BOTTOM, 1.1F}, {-1.1F, GRIP_BOTTOM, -1.1F}, {0F, SPIKE_Y - SPIKE_LEN, 0F},
				{1.8F, CUFF_Y + 1F, 0.8F}};
		for (float[] p : pts) out.accept(new Vector3f(p));
	}
}
