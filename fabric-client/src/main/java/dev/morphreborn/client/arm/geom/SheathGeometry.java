package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.model.ArmSnapshot;
import dev.morphreborn.client.arm.model.ArmTransforms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The third-person sheath (section 2.3 S1..S8): the arm that has become the tool, seen on the body. A shell of black
 * static grown over the arm from the hand up past the elbow, its top ring a ragged edge of tongues licking up the
 * sleeve (the biomass bar's rippling edge in 3D), tendrils climbing above it, three helices winding down to the hand,
 * red veins, a knot of lumps where the hand grips, and drips off its lowest side. After a scroll away it shrinks to
 * the recall bracelet, two counter-turning rings at the wrist saying that scrolling back re-forms the arm (only servers before
 * 7.6.0-beta.3 still send a recall; later ones never do, but the bracelet stays for them).
 * <p>
 * Arm space A (section 2.0): the origin at the shoulder's pivot, +Y down the arm, −Z forward, px; the right arm, with
 * the left drawn through an x mirror. It sits 0.9 to 1.25 px over the bare arm (0.35 px more over a chestplate's arm),
 * clear of the sleeve, 3D skin layers' voxels and the armour. The vanilla arm is never hidden: the shell simply covers
 * it, and the vanilla hand still holds the tool (drawn by the item path).
 * <p>
 * Render thread only (shared scratch).
 */
public final class SheathGeometry {
	/** The shell's top at full cover (just over the elbow, at about 4.2) and at none (the hand). */
	/** The tendril arm runs from the shoulder's top (it replaces the whole arm) to the hand; forming, it grows up from the hand. */
	public static final float TOP = -2.0F, BOTTOM_TOP = 10.4F, END = 10.5F;
	private static final float[] GROW_Y = {3.2F, 4.4F, 5.5F, 7.0F, 8.4F, 9.3F, 10.0F, 10.5F};
	private static final float[] GROW = {0.9F, 1.0F, 1.05F, 1.25F, 1.10F, 0.95F, 1.25F, 0.60F};
	/** Extra growth over a worn chestplate's arm. */
	public static final float CHEST_GROW = 0.35F;

	private static final int PART_SHELL = 20, PART_TONGUE = 21, PART_CLIMB = 22, PART_HELIX = 25, PART_VEIN = 28, PART_KNOT = 30, PART_DRIP = 31, PART_RING = 32;

	private static final Stations SHELL = new Stations();
	private static final Stations VEIN = new Stations();
	private static final float[] OCT = new float[16];
	private static final float[] RIBBON = new float[4];
	private static final float[] YS = new float[12];
	/** The shell's fixed station heights below its top ring, by level of detail (8, 6 and 3 stations in all). */
	private static final float[] FIXED_0 = {5.5F, 7.0F, 8.4F, 9.3F, 10.0F}, FIXED_1 = {5.5F, 8.4F, 10.0F}, FIXED_2 = {8.4F};
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Matrix4f MIRROR = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f DIR = new Vector3f(), DOWN = new Vector3f(), Q = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), ALONG_Y = new Vector3f(0F, 0F, 1F);

	// the arm being drawn, for the callbacks
	private static ArmDraw current;
	private static float xc, hx, hz, chest, yTop, yFront, unwind, spin, feedLength;
	private static int strand, cordCount = 5;
	private static float climbAngle, climbLength, ringY, ringTurn;

	/**
	 * The fibres (2026-11-15: tendrils running down the arm into the tool, not a solid
	 * sleeve): {@link #cordCount} tendrils twisting down over the bare arm from the top to the hand, lying on the skin, and
	 * gathering in round the hand where they run into the grip.
	 */
	private static final float[] FIBRE_W = {1.0F, 0.72F, 0.9F, 0.62F, 0.85F, 0.7F}, FIBRE_BULGE = {0.5F, 0.15F, 0.35F, 0.6F, 0.2F, 0.45F},
			FIBRE_AT = {0.2F, 1.2F, 2.15F, 3.2F, 4.3F, 5.3F};
	private static final Loft.CurveFn HELIX = (s, out) -> {
		float y = ArmMath.lerp(yTop + 0.3F, END + 0.9F, s);
		float base = cordCount >= 6 ? FIBRE_AT[strand] : ArmMath.TAU * strand / cordCount;
		float a = base + 0.2F * s + 0.15F * spin + 0.07F * (float) Math.sin(0.05F * current.time() + 2.5F * s + 1.7F * strand);
		float bulge = FIBRE_BULGE[strand % FIBRE_BULGE.length] * 0.9F * (float) Math.sin(ArmMath.PI * ArmMath.clamp01((y - yTop) / (END - yTop)));
		float lie = (0.25F + chest + bulge + 0.12F * (swell(Math.min(y, END)) - 1F) * 4F) * (0.5F + 0.5F * (1F - unwind));
		float in = 1F - 0.35F * ArmMath.sm((y - 9.2F) / 2.2F);
		out.set(xc + (hx + lie) * in * (float) Math.cos(a), y, (hz + lie) * in * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn HELIX_WIDTH = s -> FistGeometry.width(ArmMath.lerp(1.3F, 0.8F, s) * FIBRE_W[strand % FIBRE_W.length]
			* (0.5F + 0.5F * (1F - unwind)), 4);
	private static final Loft.CurveFn CLIMBER = (s, out) -> {
		float y = yTop - s * climbLength * (float) Math.cos(Math.toRadians(40));
		float g = grow(yTop) + 0.7F;
		float a = climbAngle + s * climbLength * (float) Math.sin(Math.toRadians(40)) / (hx + g);
		float w = 0.1F * (float) Math.sin(ArmMath.PI * s) * (float) Math.sin(current.phase() + 5F * s + 0.31F * current.time() + strand);
		out.set(xc + (hx + g + w) * (float) Math.cos(a), y, (hz + g + w) * (float) Math.sin(a));
	};
	private static final Loft.RadiusFn CLIMBER_WIDTH = s -> FistGeometry.width(ArmMath.lerp(0.6F, 0.22F, s), 4);
	/** The recall bracelet's ring: round the skin arm's square section, not a circle (2026-11-16). */
	private static final Loft.CurveFn RING = (s, out) -> onBox(ArmMath.TAU * s + ringTurn, 0.55F + chest, ringY, out);
	private static final Loft.RadiusFn RING_WIDTH = s -> FistGeometry.width(0.4F, 3);

	private SheathGeometry() {}

	// ------------------------------------------------------------------ the arm look (2026-11-16)
	//
	// Each player chooses how their arm looks while a form is out, and everyone with the mod sees that choice (sent with
	// the arm colour: ClientConfig.armLook, C_ARM_COLOUR / S_ARM_COLOUR, ArmColours): ArmProtocol.LOOK_ARM the tendril
	// arm with the claw (the look since the first arm forms, the default), LOOK_HAND the player's own arm holding the
	// tool with a few small tendrils round the hand, LOOK_ARMOUR the tendril arm, slimmer, under chitin plates with
	// glowing seams. (Chosen from look options built the same day: a fused arm, an open palm and a palm with a cuff were
	// tried and dropped.) The look is the arm's owner's, found from the draw's seed, its owner's entity id.

	/** For tests: a look every arm is drawn in (-1: each owner's own). */
	static int lookOverride = -1;

	/** The look {@code d} is drawn in: its owner's (your own from your setting), the default for a preview. */
	static int lookOf(ArmDraw d) {
		return lookOverride >= 0 ? lookOverride : ArmTint.lookOfEntity(d.seed());
	}

	/**
	 * With the armour (2026-11-16: "the arm can be thinner overall with this armor piece being a part of it,
	 * the armor can match the arms size or just slightly over"): the tendrils lie this much closer in and are this much
	 * thinner, and the plates sit just over the plain arm's own size, so arm and armour together are about an arm's width.
	 */
	private static final float ARMOUR_SLIM = 0.7F, ARMOUR_SLIM_WIDTH = 0.8F;
	/** The plates' centres: this share of the arm's half-width out (their outer faces end about 0.25 px over the arm). */
	static final float ARMOUR_FIT = 0.97F;
	private static float armSlim = 1F, armSlimWidth = 1F;

	/**
	 * Decided 2026-11-16: "the shoulder can be thicker but the arm should get thinner where the armor begins, taper
	 * down so it looks normal". Full thickness down to just below the shoulder joint, then a smooth taper to the slim
	 * arm by the top edge of the first row of plates.
	 */
	private static final float ARMOUR_TAPER_Y0 = 0.3F, ARMOUR_TAPER_Y1 = 1.65F;

	/** How much of its full thickness the tendril arm keeps at height {@code y} when it is slimmed to {@code slimmed}. */
	private static float slimAt(float y, float slimmed) {
		if (slimmed >= 1F) return 1F;
		return ArmMath.lerp(1F, slimmed, ArmMath.sm((y - ARMOUR_TAPER_Y0) / (ARMOUR_TAPER_Y1 - ARMOUR_TAPER_Y0)));
	}

	/** The static the pinned entry point draws with (the other entry point is handed one). */
	private static final Static OWN_STATIC = new Static();

	/**
	 * The pinned entry point (section 6.1, called by ArmSheathLayer once it has posed the pose stack to the arm bone):
	 * the sheath's faces of {@code layer} for {@code d} on the arm whose box is {@code slim} and which is the left arm
	 * ({@code leftArm}), in arm space A. With no arm out and a recall running it draws the recall bracelet instead.
	 */
	public static void emit(ArmDraw d, ArmLayer layer, SkinMesh m, boolean slim, boolean leftArm) {
		boolean bracelet = (d.form().isEmpty() || d.state() == ArmProtocol.STATE_NONE) && d.recallLeft() > 0F;
		draw(d, layer, m, OWN_STATIC, bracelet, slim, leftArm);
	}

	/**
	 * Draws the sheath's faces of {@code layer} for {@code d} (third person, arm space A of the arm holding the tool:
	 * {@code d.leftHand()} says which, {@code d.slim()} its box). {@code bracelet}: the recall bracelet instead (no arm
	 * out, a recall running).
	 */
	public static void emit(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, boolean bracelet) {
		draw(d, layer, m, st, bracelet, d.slim(), d.leftHand());
	}

	private static void draw(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, boolean bracelet, boolean slim, boolean leftArm) {
		if (d == null || layer == ArmLayer.SENSE) return;
		current = d;
		hx = slim ? 1.5F : 2F;
		hz = 2F;
		xc = slim ? -0.5F : -1F;
		chest = d.chestArmour() ? CHEST_GROW : 0F;
		feedLength = ArmGeometry.get(d.form()).feedLength(d.tier());
		st.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		m.transform(leftArm ? MIRROR.scaling(-1F, 1F, 1F) : null);
		try {
			if (bracelet) {
				bracelet(d, layer, m, st);
				return;
			}
			int lod = d.lod();
			ArmLayer black = d.paint(ArmLayer.BLACK);
			// the morph static over the skin arm (it creeps up from the hand, then sinks into the tendrils as they grow)
			int look = lookOf(d);
			boolean hand = look == ArmProtocol.LOOK_HAND;
			if (layer == black) staticArm(d, m, st, lod, hand);
			float cover = d.sheathCover();
			if (cover <= 0.01F) return;
			yTop = BOTTOM_TOP - (BOTTOM_TOP - TOP) * cover;
			// the arm forms from the shoulder down (2026-11-16): how far down the arm it has come
			yFront = TOP + (END - TOP) * cover;
			unwind = d.unwind();
			spin = d.helixSpin();
			d.down(DOWN);
			if (leftArm) DOWN.x = -DOWN.x; // drawn through the mirror
			if (lod >= ArmDraw.LOD_FAR) {
				if (layer == black) {
					float x0 = xc - hx - 0.3F, z0 = -hz - 0.3F, y0 = hand ? HAND_FAR_TOP : TOP, y1 = Math.min(END, yFront);
					if (y1 > y0 + 0.05F) {
						m.box(null, x0, y0, z0, 2F * hx + 0.6F, y1 - y0, 2F * hz + 0.6F, st.u0(PART_SHELL, 0, 4F), st.v0(PART_SHELL, 0, 4F), 1F, 1F, m.tint());
					}
				}
				return;
			}
			if (hand) {
				// your own arm stays; only a few tendrils round the hand
				if (lod <= 1) hand(d, layer, m, st, lod, cover);
				return;
			}
			boolean armour = look == ArmProtocol.LOOK_ARMOUR;
			armSlim = armour ? ARMOUR_SLIM : 1F;
			armSlimWidth = armour ? ARMOUR_SLIM_WIDTH : 1F;
			tendrilArm(d, layer, m, st, lod, cover, leftArm);
			if (lod <= 1) {
				claw(d, layer, m, st, lod, cover);
				if (armour) armour(d, layer, m, st, lod, cover);
			}
			if (layer == black && lod == 0 && d.dripsNow()) drips(d, m, st);
		} finally {
			m.transform(null);
		}
	}

	// ------------------------------------------------------------------ the tendril arm (2026-11-15)
	//
	// The arm the tool grows from: the skin arm is gone (PlayerModelArmMixin hides it) and this is the arm, made of
	// separate tendrils, each its own thickness, always writhing and weaving over each other so the arm can be seen
	// through between them. It is fullest at the shoulder, where its tendrils root into the body (over the top of the
	// shoulder to the neck, down the shoulder blade, across the collarbone) so it grows out of the body rather than
	// stopping at the shoulder, and it tapers down to a narrow wrist above the grip. From the wrist only claw fingers go
	// on, holding the handle at its ends so the handle's own design shows between them. Two tendrils are red (glowing
	// with glow on), and the pulses run down them from the shoulder. It grows out of the morph static from the hand up,
	// the tendrils one after another.

	/** Each tendril: its angle round the arm, how far out (1: the old arm's surface), width (px), growth delay (of 1). */
	private static final float[] T_ANGLE = {0.15F, 1.05F, 1.95F, 2.75F, 3.6F, 4.45F, 5.35F, 1.4F, 4.3F};
	private static final float[] T_OUT = {0.8F, 0.72F, 0.82F, 0.74F, 0.8F, 0.72F, 0.78F, 0.26F, 0.28F};
	private static final float[] T_WIDTH = {1.1F, 0.8F, 1.0F, 0.75F, 1.15F, 0.85F, 0.95F, 1.2F, 1.0F};
	private static final float[] T_DELAY = {0F, 0.12F, 0.05F, 0.2F, 0.08F, 0.25F, 0.15F, 0.02F, 0.18F};
	/** Which tendrils each level of detail draws (the red ones, 2 and 7, kept as long as there are three). */
	private static final int[] T_LOD0 = {0, 1, 2, 3, 4, 5, 6, 7, 8}, T_LOD1 = {0, 2, 4, 5, 7}, T_LOD2 = {2, 4, 7};
	/**
	 * Under the armour (full detail): the plates hide most of the slimmed arm, so an inner tendril (8) and one that
	 * neither feeds a finger nor roots into the body (4) are left out, to stay within the third-person quad budget.
	 */
	private static final int[] T_LOD0_ARMOUR = {0, 1, 2, 3, 5, 6, 7};
	/** The wrist: where the tendrils end, gathered, above the grip; the claw goes on from here. */
	private static final float WRIST_Y = 7.4F;
	private static final float T_Y0 = TOP - 0.1F, T_Y1 = WRIST_Y;
	private static final int PART_TENDRIL = 40, PART_ROOT = 52, PART_CLAW = 64, PART_STATIC = 70;
	private static int tendril;
	/** The growing tendril's front (s; 1: fully out, no taper). */
	private static float tendrilFront = 1F, gripY, gripZ, gripX, clawZ, clawFrom, clawSweep, clawR0, clawR1;

	/** How full the arm is at height {@code y}: whole through the shoulder, tapering gently to the wrist. */
	private static float bulk(float y) {
		return ArmMath.lerp(1F, 0.74F, ArmMath.sm((y - 1F) / (WRIST_Y - 1F)));
	}

	/** Tendril {@link #tendril}'s centre at {@code s} (0 at the shoulder, 1 at the wrist). */
	private static final Loft.CurveFn TENDRIL = (s, out) -> tendrilAt(tendril, s, out);

	private static void tendrilAt(int k, float s, Vector3f out) {
		float y = ArmMath.lerp(T_Y0, T_Y1, s), t = current.time();
		// writhing: each sways round the arm and weaves in and out on its own beat, travelling down the arm
		float a = T_ANGLE[k] + 0.14F * (float) Math.sin(0.9F * y - 0.11F * t + 1.7F * k) + 0.08F * (float) Math.sin(2.3F * y + 0.07F * t + k);
		float r = (T_OUT[k] + (k < 7 ? 0.11F : 0.2F) * (float) Math.sin(1.25F * y + 0.09F * t + 2.3F * k)) * bulk(y) * slimAt(y, armSlim);
		float lie = chest + 0.1F * (swell(y) - 1F) * 4F;
		float x = xc + (hx + lie) * r * (float) Math.cos(a), z = (hz + lie) * r * (float) Math.sin(a);
		// drawn in a little toward the grip at the wrist, but not to a point: each ends where its claw finger starts
		float gather = ArmMath.sm((y - (WRIST_Y - 1.8F)) / 1.8F);
		out.set(ArmMath.lerp(x, gripX + (x - xc) * 0.8F, gather), y, ArmMath.lerp(z, wristZ() + z * 0.8F, gather));
	}

	/** The wrist's z: over the middle of the grip's length. */
	private static float wristZ() {
		return gripZ + 0.2F;
	}

	private static final Loft.RadiusFn TENDRIL_WIDTH = s -> {
		float y = ArmMath.lerp(T_Y0, T_Y1, s);
		float w = T_WIDTH[tendril] * (1F + 0.14F * (float) Math.sin(2.1F * y + 0.13F * current.time() + tendril)) * ArmMath.lerp(1F, 0.75F, (1F - bulk(y)) / 0.26F);
		w *= ArmMath.lerp(0.55F, 1F, ArmMath.sm(s / 0.08F)) * ArmMath.lerp(1F, 0.7F, ArmMath.sm((s - 0.85F) / 0.15F));
		// the growing front tapers to a point
		if (tendrilFront < 1F) w *= ArmMath.lerp(0.15F, 1F, ArmMath.sm((tendrilFront - s) / 0.09F));
		return FistGeometry.width(w * slimAt(y, armSlimWidth) * (1F + 0.12F * ArmMath.clamp01(current.swell(phi(y), feedLength))), 4);
	};

	/** The tendrils, and the roots they send into the body at the shoulder. */
	private static void tendrilArm(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, int lod, float growth, boolean leftArm) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		ArmLayer vein = d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		grip(d);
		int[] which = lod == 0 ? (armSlim < 1F ? T_LOD0_ARMOUR : T_LOD0) : lod == 1 ? T_LOD1 : T_LOD2;
		// under the armour the slim tendrils are mostly covered: fewer stations along them (the third-person quad budget)
		int stations = lod == 0 ? (armSlim < 1F ? 6 : 8) : lod == 1 ? 6 : 4;
		for (int k : which) {
			boolean red = k == 2 || k == 7;
			ArmLayer own = red ? vein : black;
			if (layer != own) continue;
			float gk = ArmMath.clamp01((growth - T_DELAY[k]) / 0.72F);
			if (gk <= 0.01F) continue;
			tendril = k;
			// from the shoulder down (2026-11-16): the front comes down the arm toward the wrist
			tendrilFront = gk < 1F ? gk : 1F;
			Loft.tube(m, TENDRIL, 0F, gk, Math.max(3, Math.round(stations * (0.4F + 0.6F * gk))), k >= 7 ? 3 : 4, TENDRIL_WIDTH, own, layer, st, PART_TENDRIL + k, 1.5F,
					phi(T_Y0), phi(ArmMath.lerp(T_Y0, T_Y1, gk)), UP_Y, 1F);
		}
		tendrilFront = 1F;
		if (lod <= 1 && layer == black) roots(d, m, st, lod, growth, leftArm);
	}

	// ------------------------------------------------------------------ the roots into the body

	/**
	 * Where the roots go into the body, in the body's own space (px; the right side, the left mirrored), with the body's
	 * surface normal there: four fanning over the back like a shoulder blade, one up toward the back of the neck, and
	 * two over the front of the shoulder toward the collar (the top of the torso is under the head, so nothing goes
	 * there). And which tendril each comes from (the back ones from the tendrils on the arm's back).
	 */
	private static final float[][] ROOT_TO = {{-0.7F, 0.5F, 2.0F}, {-2.4F, 1.7F, 2.0F}, {-1.3F, 3.6F, 2.0F}, {-3.0F, 4.6F, 2.0F}, {-1.1F, 0.6F, -2.0F},
			{-3.0F, 1.8F, -2.0F}};
	private static final float[][] ROOT_N = {{0F, 0F, 1F}, {0F, 0F, 1F}, {0F, 0F, 1F}, {0F, 0F, 1F}, {0F, 0F, -1F}, {0F, 0F, -1F}};
	private static final int[] ROOT_FROM = {0, 1, 3, 1, 5, 6};
	private static final int[] ROOT_LOD1 = {0, 2, 4};

	/** The body's space as seen from the arm's (px to px), for the draw on the way; null when it isn't known. */
	private static @Nullable Matrix4fc bodyToArm;
	private static final Vector3f R0 = new Vector3f(), R1 = new Vector3f(), RC = new Vector3f(), RN = new Vector3f();
	private static int root;

	/**
	 * The transform from the body's model space to the arm's (both in px, as the model posed them this frame), for the
	 * next {@link #emit}: ArmSheathLayer sets it just before, and clears it after. Without it there are no roots.
	 */
	public static void bodyToArm(@Nullable Matrix4fc m) {
		bodyToArm = m;
	}

	/** Root {@link #root}: out of the top of its tendril, over the shoulder's surface, and down into the body. */
	private static final Loft.CurveFn ROOT = (s, out) -> {
		ArmMath.bezier(R0, RC, R1, s, out);
		float w = 0.18F * (float) Math.sin(ArmMath.PI * s) * (float) Math.sin(0.15F * current.time() + 2.3F * root + 4F * s);
		out.add(RN.x * w, RN.y * w, RN.z * w);
	};
	private static final Loft.RadiusFn ROOT_WIDTH = s -> FistGeometry.width(ArmMath.lerp(T_WIDTH[ROOT_FROM[root]] * 0.85F, 0.3F, ArmMath.sm(s / 0.8F))
			* (1F + 0.5F * ArmMath.sm((s - 0.85F) / 0.15F)), 4);

	private static void roots(ArmDraw d, SkinMesh m, Static st, int lod, float growth, boolean leftArm) {
		Matrix4fc b = bodyToArm;
		if (b == null) return;
		float reach = ArmMath.sm(growth / 0.3F); // at the shoulder, so first (the arm forms from the shoulder down)
		if (reach <= 0.01F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		for (int q = 0; q < ROOT_TO.length; q++) {
			if (lod == 1 && q != ROOT_LOD1[0] && q != ROOT_LOD1[1] && q != ROOT_LOD1[2]) continue;
			root = q;
			float side = leftArm ? -1F : 1F;
			tendrilAt(ROOT_FROM[q], 0F, R0);
			// the target and its normal, from the body's space into the arm's, then through the left arm's mirror
			b.transformPosition(ROOT_TO[q][0] * side, ROOT_TO[q][1], ROOT_TO[q][2], R1);
			b.transformDirection(ROOT_N[q][0] * side, ROOT_N[q][1], ROOT_N[q][2], RN);
			if (leftArm) {
				R1.x = -R1.x;
				RN.x = -RN.x;
			}
			RN.normalize();
			// just under the surface at the end; the bend lifted off the surface so the root lies over it
			R1.fma(-0.2F, RN);
			RC.set(R0).add(R1).mul(0.5F).fma(0.6F, RN);
			Loft.tube(m, ROOT, 0F, reach, lod == 0 && armSlim >= 1F ? 6 : 4, 4, ROOT_WIDTH, black, black, st, PART_ROOT + q, 1.5F, 0F, 0F, UP_Y, 1F);
		}
	}

	/**
	 * Where the tool's grip is in arm space: the item path puts grip space G's origin at (−1, 8.56, −0.67) with its +Y
	 * forward along −Z for the handheld tools, and at (0.05, 11.25, −0.96) for the bow.
	 */
	private static void grip(ArmDraw d) {
		if (ArmProtocol.BOW.equals(d.form())) {
			gripX = 0.05F;
			gripY = 11.25F;
			gripZ = -0.96F;
		} else if (ArmProtocol.CROSSBOW.equals(d.form())) {
			// the crossbow's pistol grip (2026-11-16): where vanilla's crossbow display block puts the weapon's (4.8, 0, -2.4) in G, the point the
			// claw closes round, with its fingers spread down the grip's height (the grip runs along the arm's Z)
			gripX = -1.88F;
			gripY = 8.83F;
			gripZ = 1.2F;
		} else {
			// the handheld tools, and for now the new forms (spear, mace, trident, crossbow, claws; 2026-11-16): the claw closes where a
			// sword is held. Phase 2 gives each its own hold with its geometry; ArmTransforms.gripInArm(set) works out where vanilla
			// holds the item they sit on, as a starting point (it gives these two numbers exactly for the sword and the bow)
			gripX = -1F;
			gripY = 8.56F;
			gripZ = -0.67F;
		}
	}

	/** The display set a form is held with, as the sheath sees it: the bow's by its id, the new forms' by theirs, everything else handheld. */
	private static ArmTransforms.Set gripSet(String form) {
		return ArmTransforms.Set.of(form, ArmProtocol.BOW.equals(form));
	}

	/**
	 * How the claw's fingers lie along the grip for a set: the spread (1 full length of the handle, less for a short hold) and the
	 * offset along it. The handheld tools' is the one the claw was made for (the spear, mace and trident take it, being long-handled);
	 * the short holds (the bow, and the crossbow and claws) gather the fingers; phase 2 tunes them with the geometry.
	 */
	private static float clawSpread(ArmTransforms.Set set) {
		return switch (set) {
			case CROSSBOW -> 0.85F;
			case BOW, GENERATED -> 0.45F;
			default -> 1F;
		};
	}

	private static float clawOffset(ArmTransforms.Set set) {
		return switch (set) {
			case HANDHELD, MACE, SPEAR, TRIDENT -> 0.67F;
			default -> 0F;
		};
	}

	// ------------------------------------------------------------------ the hand

	/** The claw fingers' places along the grip (z, px; the thumb last), and the tendril each grows out of. */
	private static final float[] CLAW_AT = {1.0F, -0.9F, -2.6F, -1.75F};
	private static final int[] CLAW_FROM_TENDRIL = {1, 2, 5, 0};
	/** The red finger (the middle one, out of red tendril 2, so the red runs down into it). */
	private static final int RED_FINGER = 1;
	private static final Vector3f CLAW_START = new Vector3f();
	private static float clawW0, clawW1, clawIn;
	/** The share of a finger spent reaching from its tendril's end to the grip. */
	private static final float CLAW_LEAD = 0.2F;

	/**
	 * The claw (2026-11-15, the fourth of four hands tried): three fingers and a thumb, each out of the end of
	 * its own tendril at the wrist, spread along the grip's length (by the pommel, the middle, the guard) so the handle's
	 * design shows between them; each over the grip, round its outer side and under it, hooked up into a point, the
	 * thumb coming round the other way. The middle finger is red (glowing with glow on). They flex a little as they hold,
	 * and reach round as the arm grows.
	 */
	private static void claw(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, int lod, float growth) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		ArmLayer vein = d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != black && layer != vein) return;
		float reach = ArmMath.sm((growth - 0.8F) / 0.2F); // the hand, last, as the tendrils reach it
		if (reach <= 0.01F) return;
		grip(d);
		ArmTransforms.Set set = gripSet(d.form());
		float spread = clawSpread(set), offset = clawOffset(set);
		int n = lod == 0 ? 7 : 5;
		for (int f = 0; f < 4; f++) {
			ArmLayer own = f == RED_FINGER ? vein : black;
			if (layer != own) continue;
			boolean thumb = f == 3;
			strand = f;
			tendrilAt(CLAW_FROM_TENDRIL[f], 1F, CLAW_START);
			clawZ = gripZ + spread * CLAW_AT[f] + offset;
			clawFrom = (float) Math.toRadians(270);
			// each finger waits for its own tendril's front to reach the wrist (they come down one after another)
			float gkF = ArmMath.clamp01((growth - T_DELAY[CLAW_FROM_TENDRIL[f]]) / 0.72F);
			float fr = Math.min(reach, ArmMath.sm((gkF - 0.85F) / 0.15F));
			if (fr <= 0.01F) continue;
			clawSweep = (float) Math.toRadians(thumb ? 190 : -235) * fr;
			clawR0 = 1.12F;
			clawR1 = 0.92F;
			clawW0 = thumb ? 0.68F : 0.6F;
			clawW1 = 0.14F;
			clawIn = 0.12F;
			Loft.tube(m, CLAW, 0F, 1F, n, 4, CLAW_WIDTH, own, layer, st, PART_CLAW + f, 1.5F, 8F, 12F, UP_Y, 1F);
		}
	}

	/**
	 * Claw finger {@link #strand}: its first stretch from the end of its tendril to the grip, then round the grip's axis
	 * (along Z) in the X–Y plane, closing in to its hooked point.
	 */
	private static final Loft.CurveFn CLAW = (s, out) -> {
		float flex = 0.06F * (float) Math.sin(0.12F * current.time() + 1.9F * strand);
		if (s < CLAW_LEAD) {
			float u = ArmMath.sm(s / CLAW_LEAD);
			float a0 = clawFrom;
			out.set(ArmMath.lerp(CLAW_START.x, gripX + clawR0 * (float) Math.cos(a0), u), ArmMath.lerp(CLAW_START.y, gripY + clawR0 * (float) Math.sin(a0), u),
					ArmMath.lerp(CLAW_START.z, clawZ, u));
			return;
		}
		float u = (s - CLAW_LEAD) / (1F - CLAW_LEAD);
		float a = clawFrom + clawSweep * u * (1F + flex);
		float r = ArmMath.lerp(clawR0, clawR1, ArmMath.sm(u / 0.7F)) * (1F - 0.22F * ArmMath.sm((u - 0.82F) / 0.18F));
		out.set(gripX + r * (float) Math.cos(a), gripY + r * (float) Math.sin(a), clawZ - clawIn * u);
	};


	private static final Loft.RadiusFn CLAW_WIDTH = s -> FistGeometry.width(ArmMath.lerp(clawW0, clawW1, ArmMath.sm((s - 0.45F) / 0.55F)), 4);

	// ------------------------------------------------------------------ look: the plain hand (LOOK_HAND)

	private static final int PART_HAND = 80, PART_PLATE = 100, PART_SEAM = 112, PART_SPUR = 124;
	/** The far box covers only the hand and wrist for the plain hand (the arm under it is the player's own). */
	private static final float HAND_FAR_TOP = 6.8F;
	/** How far up from the hand the morph static creeps over the plain hand's skin before it sinks (px). */
	private static final float HAND_STATIC = 4.2F;
	/** How far out of the skin arm's faces the plain hand's tendrils lie (past the sleeve layer, which is 0.25 px out). */
	private static final float HAND_MARGIN = 0.55F;
	/** Each small tendril: where it starts on the forearm (y), its angle round the arm there (radians), how far it winds (turns). */
	private static final float[] HAND_Y = {5.1F, 5.9F, 5.4F, 6.3F, 5.7F};
	private static final float[] HAND_A = {0.4F, 1.65F, 2.9F, 4.15F, 5.4F};
	private static final float[] HAND_TURN = {0.55F, -0.6F, 0.5F, -0.5F, 0.65F};
	/** Where each ends on the handle: z along it (just out of the fist, in front or behind) and the angle round it (degrees). */
	private static final float[] HAND_Z = {-2.6F, -2.3F, 2.5F, 2.8F, -2.9F};
	private static final float[] HAND_GRIP_A = {250F, 290F, 245F, 300F, 270F};
	/** Which tendrils each detail level draws (the red ones, 1 and 3, kept). */
	private static final int[] HAND_LOD0 = {0, 1, 2, 3, 4}, HAND_LOD1 = {1, 2, 3};
	private static final Vector3f HAND_GRIP = new Vector3f(), HAND_WRIST = new Vector3f();

	/** Whether small tendril {@code k} is a red one. */
	private static boolean handRed(int k) {
		return k == 1 || k == 3;
	}

	/**
	 * Small tendril {@link #strand}: s = 0 on the handle where it leaves the fist, s = 1 on the forearm. Off the handle
	 * onto the back of the hand, then winding up round the wrist and the forearm, lying just over the skin.
	 */
	private static final Loft.CurveFn HAND_TENDRIL = (s, out) -> {
		int k = strand;
		float t = current.time();
		float lead = 0.28F;
		float a1 = HAND_A[k] + ArmMath.TAU * HAND_TURN[k];
		float rWrist = HAND_MARGIN + chest;
		if (s < lead) {
			float u = ArmMath.sm(s / lead);
			onBox(a1, rWrist, 9.2F, HAND_WRIST);
			float ga = (float) Math.toRadians(HAND_GRIP_A[k]);
			HAND_GRIP.set(gripX + 1.05F * (float) Math.cos(ga), gripY + 1.05F * (float) Math.sin(ga), HAND_Z[k]);
			// a little proud of the straight line between, so it arches off the hand onto the handle
			float arch = 0.35F * (float) Math.sin(ArmMath.PI * u);
			out.set(ArmMath.lerp(HAND_GRIP.x, HAND_WRIST.x, u), ArmMath.lerp(HAND_GRIP.y, HAND_WRIST.y, u) - arch, ArmMath.lerp(HAND_GRIP.z, HAND_WRIST.z, u));
			return;
		}
		float u = (s - lead) / (1F - lead);
		float a = ArmMath.lerp(a1, HAND_A[k], u) + 0.06F * (float) Math.sin(0.11F * t + 3F * u + k);
		float y = ArmMath.lerp(9.2F, HAND_Y[k], u);
		float r = rWrist + 0.05F * (float) Math.sin(0.13F * t + 5F * u + 2F * k);
		onBox(a, r, y, out);
	};


	/**
	 * The point at angle {@code a} round the skin arm, {@code margin} out from its faces, at height {@code y}: on a
	 * superellipse (exponent 8) round the arm's box, so it keeps clear of the box's corners where an oval would sink into
	 * them (2026-11-16: the tendrils vanished at the corners and showed as dashes).
	 */
	private static void onBox(float a, float margin, float y, Vector3f out) {
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		float sx = Math.signum(c) * (float) Math.pow(Math.abs(c), 0.25), sz = Math.signum(s) * (float) Math.pow(Math.abs(s), 0.25);
		out.set(xc + (hx + margin) * sx, y, (hz + margin) * sz);
	}
	/** Thin, thickest round the wrist, tapering to points at both ends. */
	private static final Loft.RadiusFn HAND_TENDRIL_WIDTH = s -> FistGeometry.width((handRed(strand) ? 0.5F : 0.42F) * ArmMath.sm(s / 0.12F)
			* (1F - 0.8F * ArmMath.sm((s - 0.7F) / 0.3F)) * (1F + 0.12F * (float) Math.sin(0.15F * current.time() + 9F * s)) + 0.08F, 4);

	/**
	 * The plain hand (LOOK_HAND, 2026-11-16: "your normal minecraft arm and you have some small tendrils
	 * around your hand at the end while holding the tools like normal"): the player's own arm stays and holds the tool;
	 * five thin tendrils (three further out) come off the handle where it leaves the fist, over the back of the hand,
	 * and wind up round the wrist onto the forearm, two of them red. They grow from the tool up the hand as the arm forms.
	 */
	private static void hand(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, int lod, float growth) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		ArmLayer vein = d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != black && layer != vein) return;
		// once the static's band has come down the arm to the hand. A quick-form's static now sinks into the skin from about tick 4
		// while its growth is still low, which left the wrist bare skin until these reached it (2026-11-16): they start
		// sooner and wind down faster there, so they are at the wrist by about tick 4. Every other timeline keeps the old curve
		float reach = d.state() == ArmProtocol.STATE_QUICKFORM ? ArmMath.sm((growth - 0.05F) / 0.55F) : ArmMath.sm((growth - 0.3F) / 0.7F);
		if (reach <= 0.01F) return;
		grip(d);
		for (int k : lod == 0 ? HAND_LOD0 : HAND_LOD1) {
			ArmLayer own = handRed(k) ? vein : black;
			if (layer != own) continue;
			strand = k;
			// s = 1 is on the forearm: they grow from there down over the hand onto the handle
			Loft.tube(m, HAND_TENDRIL, 1F - reach, 1F, lod == 0 ? 9 : 6, 4, HAND_TENDRIL_WIDTH, own, layer, st, PART_HAND + k, 1.5F, 12F, 8F, UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ look: armour (the claw with a plated forearm)

	/** Plate rows down the forearm (y) and their angles round it (radians; π is the outer side, 3π/2 the front). */
	private static final float[] PLATE_Y = {2.6F, 4.1F, 5.6F};
	/** The plates' height (px): the top row's upper edge is at {@code PLATE_Y[0] - PLATE_H / 2}. */
	private static final float PLATE_H = 1.9F;

	/** The top edge of the armour's highest row of plates (arm space y, px): where the lantern's stalk comes out. */
	static float armourTopY() {
		return PLATE_Y[0] - PLATE_H / 2F;
	}
	/** Three plates a row (four were over the third-person quad budget), each a little wider than before so they still overlap. */
	private static final float[] PLATE_A = {0.72F * ArmMath.PI, 1.07F * ArmMath.PI, 1.42F * ArmMath.PI};

	/**
	 * The angle round the arm of the plate in row {@code row}, column {@code c} (radians). The same in every row: the middle row was
	 * turned 0.12 pi against the others until 2026-11-24 (a brick offset, about 0.7 px sideways and its face turned 21.6 degrees), and
	 * on a 2.6 px plate that read as the middle plate hanging ajar from the ones above and below it. A brick offset, if wanted again,
	 * should shift the whole plate along the arm, not turn it.
	 */
	static float plateAngle(int row, int c) {
		return PLATE_A[c];
	}

	/** The plate layout the accessories' ports and the tests read: columns, rows, and a row's centre height (arm space y, px). */
	static int plateColumns() {
		return PLATE_A.length;
	}

	static int plateRows() {
		return PLATE_Y.length;
	}

	static float plateY(int row) {
		return PLATE_Y[row];
	}

	static float plateHeight() {
		return PLATE_H;
	}

	/**
	 * How far out, as a share of the arm's half-width, the tendril arm's strands lie at height {@code y} (their middle, 0.82 of the old
	 * surface, narrowing toward the wrist and, under the armour, slimmed), for the accessories' grips: they ride just outside it, over the
	 * arm's own tendrils (2026-11-24).
	 */
	static float tendrilReach(boolean armour, float y) {
		return 0.82F * bulk(y) * slimAt(y, armour ? ARMOUR_SLIM : 1F);
	}

	/** The outer edge of the tendril arm along one axis: its centre line at {@code half * reach} plus the thickest strand (0.58) and its weave (0.07). */
	static float tendrilEnvelope(float half, float reach) {
		return half * reach + 0.65F;
	}

	/**
	 * Armour over the tendril arm (look option, 2026-11-16): on the outer side and the front of the forearm, rows of
	 * overlapping chitin plates, each tipped out at its lower edge like a scale, a thin red seam glowing under each lower
	 * edge, and two spurs at the elbow. The claw holds the tool as before. The plates breathe with the arm and grow in
	 * row by row from the hand up as the arm forms.
	 */
	private static void armour(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, int lod, float growth) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		ArmLayer vein = d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != black && layer != vein) return;
		float t = d.time();
		for (int row = 0; row < PLATE_Y.length; row++) {
			float rowGrowth = ArmMath.sm((growth - 0.25F - 0.18F * row) / 0.2F); // the top row first, down the arm
			if (rowGrowth <= 0.01F) continue;
			float y = PLATE_Y[row];
			for (int c = 0; c < PLATE_A.length; c++) {
				if (lod == 1 && c == 1) continue;
				float a = plateAngle(row, c);
				float r = ARMOUR_FIT;
				float out = chest + 0.04F * (float) Math.sin(0.1F * t + row + 2F * c);
				float px = xc + (hx * r + out) * (float) Math.cos(a), pz = (hz * r + out) * (float) Math.sin(a);
				float theta = ArmMath.PI / 2F - a; // turns local +Z onto the outward normal (cos a, 0, sin a)
				float w = 2.6F * rowGrowth, h = PLATE_H * rowGrowth;
				LOCAL.identity().translate(px, y, pz).rotateY(theta).rotateX((float) Math.toRadians(14)); // +X turn: local +Y (down the arm) gains +Z, so the lower edge tips out (review fix 2026-11-16)
				if (layer == black) {
					m.box(LOCAL, -w / 2F, -h / 2F, -0.15F, w, h, 0.5F, st.u0(PART_PLATE, row * 4 + c, 4F), st.v0(PART_PLATE, row * 4 + c, 4F), 1F, 1F, m.tint());
					// elbow spurs, on the top row's outer plates
					if (row == 0 && c <= 1 && lod == 0) {
						LOCAL.translate(0F, -0.2F, 0.3F).rotateX((float) Math.toRadians(35)); // the spur leans up the arm (−Y) and out
						m.frustum(LOCAL, 1.6F * rowGrowth, 0.7F, 0.6F, 0.08F, 0.08F, st.u0(PART_SPUR, c, 0.5F), st.v0(PART_SPUR, c, 0.5F), 2F, 1F, 1F, m.tint());
					}
				} else if (layer == vein) {
					// the seam: a thin red strip just under the plate's lower edge
					m.box(LOCAL, -w / 2F + 0.15F, h / 2F - 0.05F, -0.05F, w - 0.3F, 0.22F, 0.3F, st.u0(PART_SEAM, row * 4 + c, 4F), st.v0(PART_SEAM, row * 4 + c, 4F), 1F, 1F,
							m.tint());
				}
			}
		}
	}

	/**
	 * The morph static over the skin arm (2026-11-15: the arm turns the way a body morphs into a mob, in the
	 * mod's own static): a box of it just over the arm, from the hand up to how far it has crept, its front ragged (four
	 * columns, each a little ahead or behind), then sinking inside the tendrils as they grow until it is gone.
	 */
	private static void staticArm(ArmDraw d, SkinMesh m, Static st, int lod, boolean handOnly) {
		float show = d.staticShow(), cover = d.armStatic();
		if (show <= 0.01F || cover <= 0.01F) return;
		float sink = 1F - show;
		float g = ArmMath.lerp(0.28F + chest, -1.1F, sink);
		float hw = hx + g, hd = hz + g;
		if (hw <= 0.15F || hd <= 0.15F) return;
		float bottom = END + 0.05F + 0.25F * (1F - sink);
		// from the shoulder down (2026-11-16), its front ragged; for the plain hand a band that travels down the
		// arm and stops over the hand, where the small tendrils grow out of it
		float top = TOP - 0.3F, full = bottom - top;
		float height = full * cover;
		if (handOnly) {
			top = ArmMath.lerp(TOP - 0.3F, bottom - HAND_STATIC, cover);
			height = Math.min(Math.min(HAND_STATIC, full * cover), bottom - top); // grows in (and out), no pop
		}
		if (lod >= 2) {
			m.box(null, xc - hw, top, -hd, 2F * hw, height, 2F * hd, st.u0(PART_STATIC, 0, 4F), st.v0(PART_STATIC, 0, 4F), 1F, 1F, m.tint());
			return;
		}
		for (int q = 0; q < 4; q++) {
			float x0 = (q & 1) == 0 ? xc - hw : xc, z0 = (q & 2) == 0 ? -hd : 0F;
			float ragged = cover < 1F && !handOnly ? (FistGeometry.hash(q * 31 + d.seed()) - 0.5F) * 2.4F * (1F - cover) : 0F;
			float h = Math.clamp(height + ragged, 0F, bottom - top);
			if (h <= 0.05F) continue;
			m.box(null, x0, top, z0, hw, h, hd, st.u0(PART_STATIC, q, 4F), st.v0(PART_STATIC, q, 4F), 1F, 1F, m.tint());
		}
	}



	/** The shell's growth over the bare arm at height {@code y} (S1), the chestplate's extra included. */
	private static float grow(float y) {
		float g;
		if (y <= GROW_Y[0]) g = GROW[0];
		else if (y >= GROW_Y[GROW_Y.length - 1]) g = GROW[GROW.length - 1];
		else {
			int i = 0;
			while (i + 1 < GROW_Y.length && GROW_Y[i + 1] < y) i++;
			g = ArmMath.lerp(GROW[i], GROW[i + 1], (y - GROW_Y[i]) / (GROW_Y[i + 1] - GROW_Y[i]));
		}
		return g + chest;
	}

	/** The feed coordinate on the sheath: 0 at the top (by the shoulder), 8 at the hand. */
	private static float phi(float y) {
		return 8F * ArmMath.clamp01((y - TOP) / (END - TOP));
	}

	private static float swell(float y) {
		return 1F + 0.25F * current.swell(phi(y), feedLength);
	}

	/** The shell's stations by level of detail: its top, the ring under it, then the fixed heights below. */
	private static int stations(int lod) {
		int n = 0;
		YS[n++] = yTop;
		if (lod <= 1 && yTop + 1.2F < END - 0.15F) YS[n++] = yTop + 1.2F;
		float[] fixed = lod == 0 ? FIXED_0 : lod == 1 ? FIXED_1 : FIXED_2;
		for (float y : fixed) {
			if (y > YS[n - 1] + 0.15F) YS[n++] = y;
		}
		if (END > YS[n - 1] + 0.05F) YS[n++] = END;
		return n;
	}

	/** S1: the octagonal shell, its corners boiling, swelling with the pulses, its top eroding while it grows. */
	private static void shell(ArmDraw d, SkinMesh m, Static st, int lod, float cover) {
		int n = stations(lod);
		if (n < 2) return;
		// LOD 2: three stations of a plain four-sided shell (section 2.10's ≈36 quads with the helix and knot)
		int sides = lod >= 2 ? 4 : 8;
		Stations out = SHELL.reset(sides).feed(0F);
		float time = d.time();
		boolean fringe = d.dissolve() && cover < 1F;
		for (int i = 0; i < n; i++) {
			float y = YS[i];
			// over the skin while it engulfs the arm; once the arm is replaced (fully covered), a touch inside its old outline
			float g = cover >= ArmSnapshot.REPLACE_COVER ? -0.45F + 0.25F * (swell(y) - 1F) : 0.3F * (i == 0 ? GROW[0] + chest : i == 1 && y < 5.5F ? GROW[1] + chest : grow(y)) * swell(y);
			if (sides == 4) {
				float hn = hx + g, hb = hz + g;
				OCT[0] = hn;
				OCT[1] = -hb;
				OCT[2] = hn;
				OCT[3] = hb;
				OCT[4] = -hn;
				OCT[5] = hb;
				OCT[6] = -hn;
				OCT[7] = -hb;
			} else {
				ArmMath.chamfer(OCT, hx + g, hz + g, 0.35F * Math.min(hx, hz) + 0.2F);
			}
			float s = (y - TOP) / (END - TOP);
			for (int k = 0; k < sides; k++) {
				float boil = 1F + 0.05F * (float) Math.sin(6.6F * s + 0.2F * time + 1.3F * k);
				OCT[2 * k] *= boil;
				OCT[2 * k + 1] *= boil;
			}
			float alpha = fringe ? ArmMath.fringe(y - yTop, 1.2F) : 1F;
			// T along the arm (+Y), N across it (+X): B = T × N = −Z, so the polygon's b is −z (it is symmetric)
			out.add(xc, y, 0F, 0F, 1F, 0F, 1F, 0F, 0F, y - yTop, OCT, alpha);
		}
		ArmLayer flesh = d.paint(ArmLayer.RED); // red flesh, thin over the skin, under the black fibres
		Loft.sweep(m, out, flesh, (seg, face) -> flesh, st, PART_SHELL, 1F, false, true);
	}

	/**
	 * S2: eight tongues of goo licking up from the top ring, each with its own beat, leaning outward, and against the
	 * swing as the arm swings (boxes at LOD 1, none further out). Half height when starving; a reshape stirs them up.
	 */
	private static void tongues(ArmDraw d, SkinMesh m, Static st, int lod) {
		if (lod >= 2) return;
		float time = d.time();
		float scale = (d.state() == ArmProtocol.STATE_RESHAPING ? 1.5F : 1F) * (d.lowStage() >= ArmDraw.LOW_STARVING ? 0.5F : 1F);
		float swingLean = (float) Math.toRadians(15) * (float) Math.sin(ArmMath.PI * d.swing());
		float g = GROW[0] + chest;
		ArmMath.chamfer(OCT, hx + g, hz + g, 0.35F * Math.min(hx, hz) + 0.2F);
		for (int k = 0; k < 8; k++) {
			float px = OCT[2 * k], pz = -OCT[2 * k + 1];
			float rl = (float) Math.sqrt(px * px + pz * pz);
			float rx = rl > 1e-4F ? px / rl : 0F, rz = rl > 1e-4F ? pz / rl : 0F;
			float h = (1.2F + 0.8F * (float) Math.sin(0.07F * time + 1.9F * k)) * scale;
			float lean = (float) Math.toRadians(10);
			// up the arm (−Y), tipped outward and back against a forward swing (+Z)
			DIR.set(rx * (float) Math.sin(lean), -(float) Math.cos(lean), rz * (float) Math.sin(lean) + (float) Math.sin(swingLean)).normalize();
			LOCAL.identity().translate(xc + px, yTop + 0.25F, pz).rotate(TURN.rotationTo(ALONG_Y, DIR));
			float u = st.u0(PART_TONGUE, k, 0.5F), v = st.v0(PART_TONGUE, k, 0.5F);
			if (lod == 0) m.frustum(LOCAL, h, 0.9F, 0.5F, 0.1F, 0.1F, u, v, 2F, 1F, 1F, m.tint());
			else m.box(LOCAL, -0.45F, -0.25F, 0F, 0.9F, 0.5F, h * 0.7F, u, v, 2F, 1F, m.tint());
		}
	}

	/** S3: three thin tendrils climbing above the shell's top, spiralling 40°, each reaching and drawing back on its own. */
	private static void climbers(ArmDraw d, ArmLayer layer, SkinMesh m, Static st) {
		if (yTop < TOP + 0.5F && d.sheathCover() < 0.99F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK);
		for (int k = 0; k < 3; k++) {
			strand = k;
			climbAngle = ArmMath.TAU * k / 3F + 0.3F;
			climbLength = 3F + (float) Math.sin(0.05F * d.time() + 2.1F * k);
			Loft.tube(m, CLIMBER, 0F, 1F, 5, 4, CLIMBER_WIDTH, black, layer, st, PART_CLIMB + k, 2F, 0F, 0F, UP_Y, 1F);
		}
	}

	/**
	 * S5: two red veins, down the outer face and the front face, waving across them, swelling and brightening with the
	 * pulses (glowing instead when glow is on, as in first person).
	 */
	private static void veins(ArmDraw d, ArmLayer layer, SkinMesh m, Static st, int segments) {
		boolean glow = d.glow();
		ArmLayer vein = glow ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != vein) return;
		float y0 = yTop + 1F, y1 = 9.8F;
		if (y1 <= y0 + 0.3F) return;
		float flash = d.feedAge() < 4F ? 1F - d.feedAge() / 4F : 0F;
		float flicker = d.lowStage() >= ArmDraw.LOW_STARVING ? 0.5F + 0.5F * (float) Math.sin(d.time() * ArmMath.TAU * (0.2F + 0.3F * FistGeometry.hash(d.seed())) / 20F) : 1F;
		float time = d.time();
		for (int v = 0; v < 2; v++) {
			Stations out = VEIN.reset(2).feed(phi(y0));
			// outer face (−X, between corners 4 and 5), front face (−Z, between corners 2 and 3)
			int c0 = v == 0 ? 4 : 2;
			for (int i = 0; i <= segments; i++) {
				float y = ArmMath.lerp(y0, y1, i / (float) segments);
				float g = grow(y) * swell(y);
				float s = (y - TOP) / (END - TOP);
				float boil = 1F + 0.05F * Math.max((float) Math.sin(6.6F * s + 0.2F * time + 1.3F * c0), (float) Math.sin(6.6F * s + 0.2F * time + 1.3F * (c0 + 1)));
				float lateral = 0.3F * (float) Math.sin(1.7F * (y - y0) + v);
				float amp = ArmMath.clamp01(d.swell(phi(y), feedLength));
				float w = 0.45F * (1F + 0.8F * amp);
				RIBBON[0] = 0F;
				RIBBON[1] = -w / 2F;
				RIBBON[2] = 0F;
				RIBBON[3] = w / 2F;
				float bright = glow ? Math.max(flash, 0.35F + 0.65F * amp) : 0.6F * (1F + 1.6F * amp) + flash;
				out.color(FistGeometry.grey(bright * flicker));
				if (v == 0) {
					float x = xc - (hx + g) * boil - 0.03F;
					// N out of the face (−X): B = T × N = Y × −X = +Z, so the ribbon spans the face along z
					out.add(x, y, lateral, 0F, 1F, 0F, -1F, 0F, 0F, y - y0, RIBBON, 1F);
				} else {
					float z = -(hz + g) * boil - 0.03F;
					out.add(xc + lateral, y, z, 0F, 1F, 0F, 0F, 0F, -1F, y - y0, RIBBON, 1F);
				}
			}
			Loft.sweep(m, out, vein, (seg, f) -> vein, st, PART_VEIN + v, 2F, false, false);
		}
	}

	/** S6: four lumps where the hand grips, beating on a hit or a heal (one at LOD 2). */
	private static void knot(ArmDraw d, SkinMesh m, Static st, int lod) {
		if (yTop > 10.2F) return;
		float beat = 1F + d.knotBeat();
		int count = lod >= 2 ? 1 : 4;
		for (int k = 0; k < count; k++) {
			float x = k == 0 ? xc - hx - 0.5F : k == 1 ? xc + hx + 0.5F : xc;
			float z = k == 2 ? -hz - 0.5F : k == 3 ? hz + 0.5F : 0F;
			if (count == 1) {
				x = xc - hx - 0.4F;
				z = 0F;
			}
			float tilt = (float) Math.toRadians(k % 2 == 0 ? 12 : -12);
			LOCAL.identity().translate(x, 10.4F, z).rotateY(tilt).rotateZ(tilt * 0.7F).scale(beat);
			m.box(LOCAL, -0.7F, -0.6F, -0.7F, 1.4F, 1.2F, 1.4F, st.u0(PART_KNOT, k, 8F), st.v0(PART_KNOT, k, 8F), 1F, 1F, m.tint());
		}
	}

	/** S7: drips off the shell's lowest side (off the hand's end when the arm hangs straight down). */
	private static void drips(ArmDraw d, SkinMesh m, Static st) {
		float sx = DOWN.x, sz = DOWN.z;
		float l = (float) Math.sqrt(sx * sx + sz * sz);
		for (int j = 0; j < 2; j++) {
			float y = j == 0 ? 3.0F : 5.6F;
			if (y > yFront) continue; // not reached yet (the arm forms from the shoulder down)
			float k = 0.9F * bulk(y);
			if (l > 0.35F) {
				Q.set(xc + hx * k * sx / l, y, hz * k * sz / l);
			} else {
				if (j == 1) continue;
				Q.set(gripX, WRIST_Y + 0.3F, wristZ());
			}
			Drips.emit(m, null, Q.x, Q.y, Q.z, DOWN, d.time(), j + 2 * (d.seed() & 3), d.dripRate(), 16F / 0.9375F, st, PART_DRIP);
		}
	}

	/** S8: the recall bracelet, two rings turning against each other at the wrist and one short climber. */
	private static void bracelet(ArmDraw d, ArmLayer layer, SkinMesh m, Static st) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		if (layer != black) return;
		float time = d.time();
		// enough stations that the chords don't cut the square section's corners (found in review)
		int stations = d.lod() == 0 ? 12 : 10;
		for (int k = 0; k < 2; k++) {
			ringY = k == 0 ? 8.8F : 9.4F;
			ringTurn = (k == 0 ? 0.03F : -0.03F) * time;
			Loft.closedTube(m, RING, stations, 3, RING_WIDTH, black, layer, st, PART_RING + k, 2F, 8F, UP_Y, 1F);
		}
		if (d.lod() == 0) {
			yTop = 8.8F;
			strand = 0;
			climbAngle = 0.03F * time;
			climbLength = 1.5F;
			Loft.tube(m, CLIMBER, 0F, 1F, 4, 4, CLIMBER_WIDTH, black, layer, st, PART_RING + 2, 2F, 0F, 0F, UP_Y, 1F);
		}
	}
}
