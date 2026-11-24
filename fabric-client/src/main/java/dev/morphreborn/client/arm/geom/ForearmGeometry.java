package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The first-person forearm (section 2.3 F1..F7): with an item in hand vanilla draws no arm at all, so in first person
 * the arm that holds the tool is this, a thick sheath of black static from the wrist back toward the elbow, bent like a
 * tendril limb, with three helices winding round it, red veins that brighten as pulses run down to the hand, and drips
 * off its lowest side. Grip space G, px, through the left hand's mirror ({@link FistGeometry#mirror}).
 * <p>
 * Where it goes: the wrist is fixed beside the fist; the far end is the camera's elbow point (section 2.3 F1, a point
 * low on the screen worked out by the renderer each frame, {@link ArmDraw#fpAnchor}) pulled 55% toward a point fixed on
 * the tool, so it follows the swing partway and bends instead of swinging rigidly with the item. The centreline is a
 * quadratic curve from the wrist through a point sagging 1.2 px toward world down to that end.
 * <p>
 * While forming the sheath slides up from the hand toward the elbow with an eroding front, the helices spinning up;
 * retracting and melting it slides back down and the helices unwind. Render thread only (shared scratch).
 */
public final class ForearmGeometry {
	/** The wrist (F2) and the rig's elbow point (F1), right hand; the left hand is mirrored. */
	public static final float WRIST_X = 0.4F, WRIST_Y = -1.6F, WRIST_Z = 1.9F;
	public static final float RIG_X = 1.2F, RIG_Y = -20F, RIG_Z = 3.5F;
	/** How far the camera's elbow point is pulled toward the rig's, and how long the forearm may be. */
	public static final float ANCHOR_BLEND = 0.55F, MIN_LENGTH = 13F, MAX_LENGTH = 22F;
	/** The camera's elbow point in view space, blocks (x times the hand's side). */
	public static final float VIEW_X = 0.80F, VIEW_Y = -1.05F, VIEW_Z = -0.30F;

	private static final int PART_SHEATH = 30, PART_HELIX = 31, PART_VEIN = 34, PART_DRIP = 37;
	private static final int[] VEIN_FACES = {1, 3, 6};

	private static final Stations TUBE = new Stations();
	private static final Stations VEIN = new Stations();
	private static final float[] OCT = new float[16];
	private static final float[] RIBBON = new float[4];
	private static final Vector3f W = new Vector3f(), CTRL = new Vector3f(), END = new Vector3f(), DOWN = new Vector3f();
	private static final Vector3f P = new Vector3f(), T = new Vector3f(), N = new Vector3f(), B = new Vector3f(), Q = new Vector3f();
	private static final Vector3f HINT = new Vector3f(0F, 0F, 1F);

	// the draw being emitted, for the callbacks
	private static ArmDraw current;
	private static float feedLength, unwind, spin, cover;
	private static int helix, cords;
	/**
	 * The forearm's fibres (2026-11-15: muscle-like fibres running along the arm, not a barber pole): {@link #cords}
	 * muscle-like tendrils running along the forearm over red flesh, each its own thickness, bulging out through the
	 * middle of the forearm by its own amount, wandering a little as they writhe, and gathering in at the wrist where they
	 * run into the grip.
	 */
	private static final float[] FIBRE_W = {1.0F, 0.72F, 0.9F, 0.62F, 0.85F, 0.7F}, FIBRE_BULGE = {0.5F, 0.15F, 0.35F, 0.6F, 0.2F, 0.45F},
			FIBRE_AT = {0F, 0.95F, 2.05F, 3.1F, 4.15F, 5.25F};
	private static final Loft.CurveFn HELIX = (s, out) -> {
		frame(s);
		float base = cords >= 6 ? FIBRE_AT[helix] : ArmMath.TAU * helix / cords;
		float a = base + 0.18F * s + spin * 0.15F + 0.07F * (float) Math.sin(0.05F * current.time() + 3F * s + 1.7F * helix);
		float bulge = 1F + FIBRE_BULGE[helix % FIBRE_BULGE.length] * 0.35F * (float) Math.sin(ArmMath.PI * ArmMath.clamp01((s - 0.1F) / 0.85F));
		float r = cordRadius(s) * bulge * (0.5F + 0.5F * (1F - unwind));
		out.set(P).add(N.x * (float) Math.cos(a) * r + B.x * (float) Math.sin(a) * r, N.y * (float) Math.cos(a) * r + B.y * (float) Math.sin(a) * r,
				N.z * (float) Math.cos(a) * r + B.z * (float) Math.sin(a) * r);
	};
	private static final Loft.RadiusFn HELIX_WIDTH = s -> FistGeometry.width((0.55F + 0.55F * Math.min(1F, s / 0.15F)) * FIBRE_W[helix % FIBRE_W.length]
			* (0.5F + 0.5F * (1F - unwind)) * (1F + 0.15F * current.swell(phi(s), feedLength)), 4);

	// ------------------------------------------------------------------ the tendril forearm (2026-11-15)
	//
	// As in third person (SheathGeometry): separate tendrils as bulky as the forearm they replace, each its own
	// thickness, writhing and weaving over each other with gaps to see through, two of them red, gathering at the wrist
	// into the claw (FistGeometry). They grow out of the morph static from the wrist toward the elbow, one after another.

	/** Each tendril: angle round the forearm, how far out (of the old sheath's half-width), width (px), growth delay. */
	private static final float[] T_ANGLE = {0.2F, 1.15F, 2.1F, 3.05F, 4.0F, 5.0F, 1.6F, 4.6F};
	private static final float[] T_OUT = {0.9F, 0.84F, 0.94F, 0.86F, 0.92F, 0.86F, 0.32F, 0.3F};
	private static final float[] T_WIDTH = {1.45F, 1.1F, 1.35F, 1.05F, 1.5F, 1.15F, 1.6F, 1.35F};
	private static final float[] T_DELAY = {0F, 0.12F, 0.05F, 0.2F, 0.08F, 0.24F, 0.02F, 0.16F};
	private static final int[] T_LOD0 = {0, 1, 2, 3, 4, 5, 6, 7}, T_LOD1 = {0, 2, 3, 4, 6};
	private static final int PART_TENDRIL = 40, PART_STATIC = 50;
	private static int tendril;
	private static float tendrilFront;

	private static final Loft.CurveFn TENDRIL = (s, out) -> {
		frame(s);
		int k = tendril;
		float t = current.time();
		float a = T_ANGLE[k] + 0.15F * (float) Math.sin(5F * s - 0.11F * t + 1.7F * k) + 0.08F * (float) Math.sin(11F * s + 0.07F * t + k);
		float out0 = T_OUT[k] + (k < 6 ? 0.12F : 0.2F) * (float) Math.sin(7F * s + 0.09F * t + 2.3F * k);
		// tapering from the elbow down to the wrist (2026-11-15: too bulky)
		float r = out0 * halfWidth(s) * swellAt(s) * ArmMath.lerp(0.62F, 0.92F, ArmMath.sm(s));
		// gathered in to the claw at the wrist
		if (s < 0.15F) r = ArmMath.lerp(0.45F + 0.15F * (k % 3), r, ArmMath.sm(s / 0.15F));
		float ca = (float) Math.cos(a) * r, sa = (float) Math.sin(a) * r;
		out.set(P).add(N.x * ca + B.x * sa, N.y * ca + B.y * sa, N.z * ca + B.z * sa);
	};

	private static final Loft.RadiusFn TENDRIL_WIDTH = s -> {
		float w = T_WIDTH[tendril] * (1F + 0.14F * (float) Math.sin(13F * s + 0.13F * current.time() + tendril)) * ArmMath.lerp(0.7F, 1F, ArmMath.sm(s));
		w *= ArmMath.lerp(0.5F, 1F, ArmMath.sm(s / 0.15F));
		if (tendrilFront < 1F) w *= ArmMath.lerp(0.15F, 1F, ArmMath.sm((tendrilFront - s) / 0.09F));
		return FistGeometry.width(w * (1F + 0.12F * ArmMath.clamp01(current.swell(phi(s), feedLength))), 4);
	};

	private static void tendrils(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, boolean full, float growth, float extraPulse) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		boolean glow = d.glow();
		ArmLayer vein = glow ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		int stations = full ? 9 : 6;
		for (int k : full ? T_LOD0 : T_LOD1) {
			boolean red = k == 2 || k == 6;
			ArmLayer own = red ? vein : black;
			if (layer != own) continue;
			float gk = ArmMath.clamp01((growth - T_DELAY[k]) / 0.76F);
			if (gk <= 0.01F) continue;
			tendril = k;
			tendrilFront = gk;
			// the pulses light the red ones where they are, running from the elbow end down to the claw
			Loft.tube(m, TENDRIL, 0F, gk, Math.max(3, Math.round(stations * (0.4F + 0.6F * gk))), 4, TENDRIL_WIDTH, own, layer, st, PART_TENDRIL + k, 1.5F, 8F,
					8F * (1F - gk), HINT, 1F);
		}
		tendrilFront = 1F;
	}

	private ForearmGeometry() {}

	/**
	 * The forearm's far end in G px for {@code fix} (the camera's elbow point brought into G, unmirrored), into
	 * {@code out} as x y z: blended 55% toward the rig point and kept 13..22 px from the wrist (section 2.3 F1). The
	 * renderer calls this when it finishes a first-person draw.
	 */
	public static float[] anchor(float fixX, float fixY, float fixZ, boolean leftHand, float[] out) {
		float side = leftHand ? -1F : 1F;
		float ax = ArmMath.lerp(fixX, RIG_X * side, ANCHOR_BLEND), ay = ArmMath.lerp(fixY, RIG_Y, ANCHOR_BLEND), az = ArmMath.lerp(fixZ, RIG_Z, ANCHOR_BLEND);
		float wx = WRIST_X * side, dx = ax - wx, dy = ay - WRIST_Y, dz = az - WRIST_Z;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!Float.isFinite(len) || len < 1e-4F) {
			out[0] = RIG_X * side;
			out[1] = RIG_Y;
			out[2] = RIG_Z;
			return out;
		}
		float k = ArmMath.clamp(len, MIN_LENGTH, MAX_LENGTH) / len;
		out[0] = wx + dx * k;
		out[1] = WRIST_Y + dy * k;
		out[2] = WRIST_Z + dz * k;
		return out;
	}

	/** The static the pinned entry point draws with (the forms hand theirs to the other one). */
	private static final Static OWN_STATIC = new Static();

	/**
	 * The pinned entry point (section 6.1): the forearm's faces of {@code layer} for {@code d} in plain grip space G, the
	 * left hand's mirror applied here (and cleared after), its own static, the pulses read on {@code d}'s form. The forms
	 * themselves call the other entry point, sharing their static and their mirror with the fist.
	 */
	public static void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (d == null || layer == ArmLayer.SENSE) return;
		OWN_STATIC.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		FistGeometry.mirror(m, d.leftHand());
		try {
			emit(d, layer, m, OWN_STATIC, ArmGeometry.get(d.form()).feedLength(d.tier()), 0F);
		} finally {
			FistGeometry.mirror(m, false);
		}
	}

	/**
	 * Draws the forearm's faces of {@code layer} (black sheath and helices, red or glowing veins, drips) for a form whose
	 * feed coordinate ends at {@code formFeedLength}. {@code extraPulse} adds to the veins' pulse (the pick's gnaw).
	 * Call inside the left hand's mirror.
	 */
	public static void emit(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, float formFeedLength, float extraPulse) {
		if (!ArmDraw.FIRST_PERSON_ARM) return; // first person shows just the tool
		current = d;
		feedLength = formFeedLength;
		cover = d.sheathCover();
		float staticShow = d.staticShow();
		if (cover <= 0.01F && staticShow <= 0.01F) return;
		unwind = d.unwind();
		spin = d.helixSpin();
		boolean mirror = d.leftHand();
		// the ends, in the mirrored space (the anchor is unmirrored G; the curve sags toward world down)
		W.set(WRIST_X, WRIST_Y, WRIST_Z);
		float[] a = d.fpAnchor();
		if (a != null && a.length >= 3 && Float.isFinite(a[0] + a[1] + a[2])) END.set(mirror ? -a[0] : a[0], a[1], a[2]);
		else END.set(RIG_X, RIG_Y, RIG_Z);
		d.down(DOWN);
		if (mirror) DOWN.x = -DOWN.x;
		CTRL.set(W).add(END).mul(0.5F).fma(1.2F, DOWN);
		boolean full = d.lod() == 0;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		// the morph static: the forearm turns to it from the wrist back, then it sinks inside the tendrils as they grow
		if (staticShow > 0.01F && layer == black) {
			float extent = d.armStatic();
			if (extent > 0.01F) sheath(d, m, st, full ? 8 : 5, false, extent, ArmMath.lerp(1.08F, 0.3F, 1F - staticShow), black, PART_STATIC);
		}
		if (cover > 0.01F) tendrils(d, layer, m, st, full, cover, extraPulse);
		if (full && d.dripsNow() && layer == black && cover > 0.3F) {
			for (int j = 0; j < 2; j++) {
				float s = j == 0 ? 0.3F : 0.6F;
				if (s > cover) continue;
				lowest(s, Q);
				Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, d.time(), j + 2 * (d.seed() & 3), d.dripRate(), d.blocksToPx(), st, PART_DRIP);
			}
		}
	}

	/** The point at {@code s} on the centreline and its frame: P, T, N (G's Z made square to T), B = T × N. */
	private static void frame(float s) {
		ArmMath.bezier(W, CTRL, END, s, P);
		ArmMath.bezierTangent(W, CTRL, END, s, T);
		if (T.lengthSquared() < 1e-8F) T.set(0F, -1F, 0F);
		T.normalize();
		N.set(HINT).fma(-HINT.dot(T), T);
		if (N.lengthSquared() < 1e-6F) N.set(1F, 0F, 0F);
		N.normalize();
		T.cross(N, B);
	}

	/** The sheath's half-width at {@code s} (F4): 2.1 at the wrist, 2.6 a third of the way, 2.9 at the elbow. */
	private static float halfWidth(float s) {
		return s < 0.35F ? ArmMath.lerp(2.1F, 2.6F, s / 0.35F) : ArmMath.lerp(2.6F, 2.9F, (s - 0.35F) / 0.65F);
	}

	/** The feed coordinate at {@code s}: 8 at the wrist, where the hand takes over, back to 0 at the elbow end. */
	private static float phi(float s) {
		return 8F * (1F - s);
	}

	private static float swellAt(float s) {
		return 1F + 0.25F * current.swell(phi(s), feedLength);
	}

	/** The core's share of the old sheath's width: thin, showing between the cords. */
	private static final float CORE = 0.6F;

	/** The cords' distance from the centreline: out round the core, gathering in to the grip at the wrist. */
	private static float cordRadius(float s) {
		if (s < 0.15F) return ArmMath.lerp(0.55F, (CORE + 0.18F) * halfWidth(0.15F) * swellAt(0.15F), s / 0.15F);
		return (CORE + 0.18F) * halfWidth(s) * swellAt(s);
	}

	/** The helices' radius (F5): 0.4 px off the sheath, diving into the fist over the first tenth. */
	private static float helixRadius(float s) {
		float r = halfWidth(s) * swellAt(s) + 0.4F;
		if (s < 0.1F) return ArmMath.lerp(halfWidth(0F) * 0.6F, halfWidth(0.1F) * swellAt(0.1F) + 0.4F, s / 0.1F);
		return r;
	}

	/**
	 * An octagonal sheath round the centreline from the wrist to {@code extent}, {@code scale} of the forearm's
	 * half-width, each corner boiling on its own (F4), in {@code paint}: the morph static as the arm turns.
	 */
	private static void sheath(ArmDraw d, SkinMesh m, Static st, int stations, boolean fringe, float extent, float scale, ArmLayer paint, int part) {
		Stations out = TUBE.reset(8).feed(0F);
		float time = d.time();
		float arc = 0F;
		float px = 0F, py = 0F, pz = 0F;
		for (int i = 0; i < stations; i++) {
			float s = extent * i / (float) (stations - 1);
			frame(s);
			if (i > 0) arc += (float) Math.sqrt((P.x - px) * (P.x - px) + (P.y - py) * (P.y - py) + (P.z - pz) * (P.z - pz));
			px = P.x;
			py = P.y;
			pz = P.z;
			float r = scale * halfWidth(s) * swellAt(s) / (float) Math.cos(Math.PI / 8);
			for (int k = 0; k < 8; k++) {
				float ang = ArmMath.PI / 8F + k * ArmMath.PI / 4F;
				float boil = 1F + 0.05F * (float) Math.sin(6.6F * s + 0.2F * time + 1.3F * k);
				OCT[2 * k] = r * boil * (float) Math.cos(ang);
				OCT[2 * k + 1] = r * boil * (float) Math.sin(ang);
			}
			float alpha = fringe ? ArmMath.fringe((extent - s) * 18F, 1.2F) : 1F;
			out.add(P.x, P.y, P.z, T.x, T.y, T.z, N.x, N.y, N.z, arc, OCT, alpha);
		}
		// the stations run wrist to elbow, the feed coordinate the other way (the arm feeds the hand)
		Loft.sweep(m, out, paint, (seg, face) -> paint, st, part, 1F, false, extent >= 0.99F);
	}

	/**
	 * Three red strips on octagon faces 1, 3 and 6 (F6), waving across their face, 0.03 px proud of it, widening and
	 * brightening as pulses pass. With glow on they draw in the glow layer instead, at brightness 0.35 + 0.65·G (1 while
	 * a heal arrives): the same strip, lit by itself.
	 */
	private static void veins(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, int count, int segments, float extraPulse) {
		boolean glow = d.glow();
		ArmLayer vein = glow ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != vein) return;
		float flash = d.feedAge() < 4F ? 1F - d.feedAge() / 4F : 0F;
		float flicker = d.lowStage() >= ArmDraw.LOW_STARVING ? 0.5F + 0.5F * (float) Math.sin(d.time() * ArmMath.TAU * (0.2F + 0.3F * FistGeometry.hash(d.seed())) / 20F) : 1F;
		float s0 = 0.05F, s1 = Math.min(cover, 0.95F);
		if (s1 <= s0 + 0.05F) return;
		for (int v = 0; v < count; v++) {
			int face = VEIN_FACES[v];
			float beta = (face + 1) * ArmMath.PI / 4F; // the face's middle, between corners `face` and `face + 1`
			Stations out = VEIN.reset(2).feed(0F);
			float arc = 0F, px = 0F, py = 0F, pz = 0F;
			for (int i = 0; i <= segments; i++) {
				float s = ArmMath.lerp(s0, s1, i / (float) segments);
				frame(s);
				float g = d.swell(phi(s), feedLength) + extraPulse;
				float apothem = halfWidth(s) * swellAt(s) + 0.03F;
				float cb = (float) Math.cos(beta), sb = (float) Math.sin(beta);
				// across the face: (−sin β, cos β) in the N, B plane
				float lateral = 0.3F * (float) Math.sin(1.7F * arc + v);
				Q.set(P).add(N.x * (cb * apothem - sb * lateral) + B.x * (sb * apothem + cb * lateral),
						N.y * (cb * apothem - sb * lateral) + B.y * (sb * apothem + cb * lateral),
						N.z * (cb * apothem - sb * lateral) + B.z * (sb * apothem + cb * lateral));
				if (i > 0) arc += (float) Math.sqrt((Q.x - px) * (Q.x - px) + (Q.y - py) * (Q.y - py) + (Q.z - pz) * (Q.z - pz));
				px = Q.x;
				py = Q.y;
				pz = Q.z;
				float w = 0.45F * (1F + 0.8F * ArmMath.clamp01(g));
				RIBBON[0] = 0F;
				RIBBON[1] = -w / 2F;
				RIBBON[2] = 0F;
				RIBBON[3] = w / 2F;
				float bright = glow ? Math.max(flash, 0.35F + 0.65F * ArmMath.clamp01(g)) : 0.6F * (1F + 1.6F * ArmMath.clamp01(g)) + flash;
				out.color(FistGeometry.grey(bright * flicker) & 0xFFFFFF);
				// the face's outward normal, so the strip lies flat on it
				float nx = N.x * cb + B.x * sb, ny = N.y * cb + B.y * sb, nz = N.z * cb + B.z * sb;
				out.add(Q.x, Q.y, Q.z, T.x, T.y, T.z, nx, ny, nz, arc, RIBBON, 1F);
			}
			Loft.sweep(m, out, vein, (seg, f) -> vein, st, PART_VEIN + v, 2F, false, false);
		}
	}

	/** The lowest point of the sheath's surface at {@code s} (toward world down), for drips. */
	private static void lowest(float s, Vector3f out) {
		frame(s);
		float dn = DOWN.dot(N), db = DOWN.dot(B);
		float l = (float) Math.sqrt(dn * dn + db * db);
		float r = halfWidth(s) * swellAt(s);
		if (l < 1e-4F) {
			out.set(P);
			return;
		}
		out.set(P).add((N.x * dn + B.x * db) / l * r, (N.y * dn + B.y * db) / l * r, (N.z * dn + B.z * db) / l * r);
	}

	/** The wrist, for anything that starts at the hand (unmirrored, right hand). */
	public static Vector3f wrist(boolean leftHand, @Nullable Vector3f out) {
		Vector3f o = out == null ? new Vector3f() : out;
		return o.set(leftHand ? -WRIST_X : WRIST_X, WRIST_Y, WRIST_Z);
	}
}
