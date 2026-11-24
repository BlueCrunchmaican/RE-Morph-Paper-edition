package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The flint-and-steel accessory's look (2026-11-16): a sparking tendril over the arm, in the lantern's style. A braided black
 * striker grows out of the outer face of the forearm, bows out and sweeps down past the hand, and ends in two hardened knuckles that
 * face each other across a small gap. Every now and then they slam together and strike sparks; between strikes a glint sits in the gap and
 * small embers drift up off the knuckles. The tendril's static fizzes like every other part of the arm, a red sinew winds up it, and its
 * swellings run along it with the arm's pulses (at the owner's pulse speed); the sparks, embers and glint are in the owner's colour, as the
 * arm's red and glow are.
 * <p>
 * <b>What strikes.</b> The knuckles strike on their own now and then ({@link FlintMotion#idleAge}: each player's own beat), and at once
 * when the server says so (FX_ACCESSORY FLINT_SPARK when the accessory is switched on, FLINT_IGNITE when a hit sets something alight:
 * {@link FlintMotion#struck}, harder for an ignite). A strike is a clack ({@link FlintMotion#clack}: the knuckles shut and spring open
 * again), a flash of the glint, the striking faces going white-hot and cooling, and a spray of sparks that fly out from the gap along
 * the tendril's tip, shrinking as they cool, and fall with the world's gravity ({@link #UPV}, the world's up brought into the arm's
 * space through the pose, as the spyglass does for its eye).
 * <p>
 * <b>Why it is not like the others.</b> The lantern hangs a lure over the hand, the spyglass rises over the shoulder, and the shield is
 * on the other arm; this one runs down the outside of the arm to the hand and is built of hard, angular lumps (each knuckle is two cubes,
 * one turned, with a hot face) rather than something soft, with spurs along it standing stiff, pointing at the knuckles.
 * <p>
 * Space: arm space A of the arm that holds the tool, px, y down, forward -Z (third person); the hand pass's view space in first person
 * (y up), where the striker comes in from the screen's lower right edge (the left for a left-handed player), beyond the lantern's lure
 * and below the spyglass's eye.
 * <p>
 * Detail drops with distance: LOD 1 (and icons) a plainer tendril, the knuckles with their hot faces, the glint, fewer sparks and two
 * embers; LOD 2 a thin tendril, one box per knuckle and the glint; LOD 3 two boxes. Render thread only (shared scratch), and nothing is
 * allocated per frame.
 */
public final class FlintRenderer implements AccessoryRenderer {
	// ------------------------------------------------------------------ the numbers (px)

	/** The tendril's width at its root and at its tip. */
	static final float STALK_W0 = 1.05F, STALK_W1 = 0.7F;
	/** The knuckles' sizes (across the jaw, along the tip, across the other way), and the gap between them open and shut. */
	static final float KN_J = 1.5F, KN_T = 2.0F, KN_B = 1.4F, GAP_OPEN = 1.9F, GAP_SHUT = 0.12F;
	/** How far the knuckles sit past the tendril's tip (along it). */
	static final float KN_ALONG = 1.0F;
	/** The knuckles' size in first person, as a share of third person's. */
	static final float FP_SCALE = 0.85F;
	/** The sparks at full strength and the embers, at full detail. */
	static final int SPARKS = 8, EMBERS = 4;
	/** The sparks' reach (px a spark flies in its life at power 1) and the pull of gravity on them (px a tick squared). */
	static final float SPARK_REACH = 7.5F, SPARK_FALL = 0.11F;
	/** What the knuckles' hot faces glow at between strikes (the idle glow, 0..1, moved a little by the clock). */
	static final float IDLE_HEAT = 0.4F;
	/** Stalk samples (frames carried along it) and its feed coordinate's length beyond its own (so the pulse runs on past the tip). */
	private static final int SN = 10;
	private static final float FEED_PAD = 10F;
	private static final int PART_STALK = 300, PART_SINEW = 301, PART_SPUR = 302, PART_KNOT = 308, PART_NECK = 309, PART_KN = 312, PART_HOT = 316,
			PART_GLINT = 318, PART_SPARK = 320, PART_EMBER = 340, PART_BOX = 350;
	/** The stalk's two knuckles (swellings): where (s) and how much thicker. */
	private static final float[] KNUCKLE_S = {0.42F, 0.72F}, KNUCKLE_A = {0.5F, 0.4F};
	/**
	 * The spurs along the stalk, base to tip: where (s), which way round it (radians), length (px), lean from straight out toward the tip
	 * (degrees) and width (px at the root). Stiff, short and leaning hard toward the knuckles; hand-tuned, so no two look alike.
	 */
	static final float[] SP_S = {0.30F, 0.52F, 0.66F}, SP_AZ = {0.9F, 3.9F, 2.3F}, SP_LEN = {1.7F, 1.3F, 1.5F}, SP_LEAN = {62F, 70F, 55F}, SP_W = {0.46F, 0.4F, 0.36F};

	// ------------------------------------------------------------------ the draw's working state (render thread, one draw at a time)

	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f(), HOLD = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f(), TIP = new Vector3f(), GAP = new Vector3f(), JAW = new Vector3f(),
			ACROSS = new Vector3f(), K1 = new Vector3f(), K2 = new Vector3f(), V1 = new Vector3f(), V2 = new Vector3f(), UP_Y = new Vector3f(0F, 1F, 0F),
			ALONG_Z = new Vector3f(0F, 0F, 1F), UPV = new Vector3f(), UPJ = new Vector3f(0F, -1F, 0F), DIR = new Vector3f(), AT = new Vector3f();
	private static final float[] FT = new float[3], FN = new float[3], FB = new float[3], TX = new float[3 * SN], NX = new float[3 * SN], BX = new float[3 * SN];
	private static final float[] THORN = new float[9];
	private static ArmDraw cur;
	private static boolean fp, eroding;
	private static int sidesNow, strikeSeed;
	private static float time, ph, built, reach, knuckK, holdK, arc, wrAmp, sclK, near, ringX, ringOut, thornW, strikeAge, strikePower, shut, heat, scale, gapNow;

	// ------------------------------------------------------------------ the interface

	@Override
	public String id() {
		return ArmProtocol.FLINT;
	}

	@Override
	public void emitThirdPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, false);
	}

	@Override
	public void emitFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, true);
	}

	/** Where the striker's knuckles sit at rest in arm space A of the arm that holds the tool (the effects' start, as the sheath layer records it). */
	@Override
	public @Nullable Vector3f restPoint(boolean slim, boolean leftArm, Vector3f out) {
		thirdPoints(slim, leftArm);
		return out.set(P2);
	}

	/**
	 * Where the knuckles are at rest in the hand pass's view space (px, y up), for a left- or right-handed player: the world effects of
	 * your own striker in first person start from it, as the hand pass records no point of its own for an accessory.
	 */
	public static Vector3f restPointFirstPerson(boolean mainArmLeft, Vector3f out) {
		firstPoints(mainArmLeft);
		return out.set(P2);
	}

	// ------------------------------------------------------------------ the draw

	private static void draw(ArmDraw d, ArmLayer layer, SkinMesh m, boolean firstPerson) {
		if (layer == ArmLayer.SENSE || !d.accessoryOn(ArmProtocol.FLINT)) return;
		built = FlintMotion.built(d.accessoryAge(ArmProtocol.FLINT));
		if (!(built > 0.01F)) return;
		cur = d;
		fp = firstPerson;
		time = d.time();
		ph = d.phase();
		int lod = d.lod();
		boolean full = lod == ArmDraw.LOD_FULL;
		ST.set(time, d.cadence(), 1F, d.seed());
		sidesNow = full ? (fp ? 6 : 4) : 3;
		strike(d);
		// the tendril grows first, the knuckles come alive at its tip and last the bristles sprout (ticks 0..11 of the build)
		reach = ArmMath.sm(built / 0.65F);
		knuckK = ArmMath.sm((built - 0.5F) / 0.4F);
		holdK = ArmMath.sm(built / 0.25F);
		near = fp ? FpLayout.scale(INDEX) : AccessoryView.near();
		sclK = (fp ? FP_SCALE : 1F) * near;
		eroding = d.dissolve() && reach < 0.999F;
		try {
			worldUp(m);
			layout(d);
			Loft.pulseFrom(d, arc + FEED_PAD);
			if (lod >= ArmDraw.LOD_FAR) {
				lod3(d, layer, m);
				return;
			}
			if (lod == 2) {
				lod2(d, layer, m);
				return;
			}
			int stations = full ? (fp ? 9 : 7) : 4;
			if (full && !fp) holder(d, layer, m);
			if (layer == ArmLayer.BLACK) {
				Loft.tube(m, STALK, 0F, reach, stations, sidesNow, STALK_R, ArmLayer.BLACK, layer, ST, PART_STALK, 1F, 0F, arc * reach, UP_Y, STALK_A);
			}
			if (full) Loft.tube(m, SINEW, 0F, reach, fp ? 11 : 8, 3, SINEW_R, ArmLayer.RED, layer, ST, PART_SINEW, 2F, 0F, arc * reach, UP_Y, 1F);
			if (layer == ArmLayer.BLACK && (full || lod == ArmDraw.LOD_ICON)) spurs(m, full);
			if (knuckK > 0.03F) knuckles(d, layer, m, full);
			if (knuckK > 0.3F) sparks(d, layer, m, full ? SPARKS : 3);
			if (knuckK > 0.6F && lod <= ArmDraw.LOD_ICON) embers(d, layer, m, full ? EMBERS : 2);
		} finally {
			m.transform(null);
			Loft.pulseFrom(null, 0F);
		}
	}

	/**
	 * Works out this draw's strike: how long since the knuckles last struck (the server's strike, or the idle one, whichever is more
	 * recent), how hard, how far shut they are and how hot the faces, and the pick that sets the sparks. None while it is still growing.
	 */
	private static void strike(ArmDraw d) {
		int seed = d.seed();
		float idle = FlintMotion.idleAge(seed, time), forced = FlintMotion.forcedAge(seed, time);
		boolean useForced = forced < ArmDraw.NEVER && forced <= idle;
		strikeAge = useForced ? forced : idle;
		strikePower = useForced ? Math.max(0.5F, FlintMotion.forcedPower(seed)) : FlintMotion.POWER_IDLE;
		strikeSeed = useForced ? FlintMotion.forcedSeed(seed) : (int) Math.floor(time / FlintMotion.SLOT_TICKS) * 131 + seed;
		if (!(built > 0.9F)) strikeAge = ArmDraw.NEVER;
		shut = FlintMotion.clack(strikeAge);
		float idleGlow = IDLE_HEAT + 0.12F * (float) Math.sin(0.23F * time + ph) + 0.06F * (float) Math.sin(0.61F * time + 2F * ph);
		heat = FlintMotion.heat(strikeAge, idleGlow);
	}

	/**
	 * Works out {@link #UPV}, the way up in the drawing space of {@code m}: the world's up brought through the pose (the arm space's own
	 * {@code y} axis is whichever way the arm hangs, so the sparks and embers use the world's instead), or the space's own up when there
	 * is no pose to read (a preview). In the hand pass, whose pose is the view's, it is the world's up in the view's space, worked out from the
	 * camera's own axes (2026-11-16: it was the view's +Y, so sparks fell across the screen whenever the player looked up or down). Also sets
	 * {@link #UPJ}, the up the knuckles' jaws are ordered by: the view's own in the hand pass, so a pitch never flips them.
	 */
	private static void worldUp(SkinMesh m) {
		if (fp && cameraUp(UPV)) {
			UPJ.set(0F, 1F, 0F);
			return;
		}
		worldUp(m.pose(), fp, UPV);
		UPJ.set(UPV);
	}

	/**
	 * The world's up in the hand pass's view space (x right, y up, z toward the viewer) from the camera's own axes, as world vectors; the
	 * view's +Y when the camera has none (a camera looking straight along an axis still has all three).
	 */
	static Vector3f viewUp(float leftY, float upY, float forwardY, Vector3f out) {
		out.set(-leftY, upY, -forwardY);
		float len2 = out.lengthSquared();
		return len2 > 1e-10F && Float.isFinite(len2) ? out.normalize() : out.set(0F, 1F, 0F);
	}

	/** Fills {@code out} from the game camera; false when there is no game (a preview, a test), and the caller falls back on the view's +Y. */
	private static boolean cameraUp(Vector3f out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gameRenderer == null) return false;
		var cam = mc.gameRenderer.mainCamera();
		if (cam == null) return false;
		viewUp(cam.leftVector().y(), cam.upVector().y(), cam.forwardVector().y(), out);
		return true;
	}

	/** {@code out} = the world's up in the space whose pose is {@code pose} (null, or the hand pass: that space's own up, arm space's -Y or the view's +Y). */
	static Vector3f worldUp(PoseStack.@Nullable Pose pose, boolean firstPerson, Vector3f out) {
		if (firstPerson) return out.set(0F, 1F, 0F);
		if (pose != null) {
			// the pose maps arm space to the world, a rotation and a uniform scale: the arm-space direction the world's up comes from is the
			// matrix's second row
			Matrix4f p = pose.pose();
			out.set(p.m01(), p.m11(), p.m21());
			float len2 = out.lengthSquared();
			if (len2 > 1e-10F && Float.isFinite(len2)) return out.normalize();
		}
		return out.set(0F, -1F, 0F);
	}

	// ------------------------------------------------------------------ layout and the stalk

	/** Sets {@link #P0}, {@link #P1}, {@link #P2} for the third-person view: out of the forearm's outer face, bowed out, down past the hand. */
	private static void thirdPoints(boolean slim, boolean leftArm) {
		float out = leftArm ? 1F : -1F;
		float hx = slim ? 1.5F : 2F;
		float xc = (slim ? 0.5F : 1F) * out;
		float xo = xc + out * hx;
		ringX = xc;
		ringOut = out;
		P0.set(xo - 0.3F * out, 2.2F, -1.1F); // the spyglass is above it and the lantern below (2026-11-24: the old root at 3.6 met the lantern's)
		P1.set(xo + out * 2.7F, 6.8F, 0.1F);
		P2.set(xo + out * 1.3F, 11.0F, -3.0F);
	}

	/** The first-person view's points: in from the lower right edge, over to the knuckles low on the right (x right, y up). */
	private static void firstPoints(boolean mainArmLeft) {
		float s = mainArmLeft ? -1F : 1F;
		P0.set(s * 19F, -7.5F, -4F);
		P1.set(s * 16.5F, -4.5F, -9F);
		P2.set(s * 11.8F, -5.6F, -12.5F);
		// made room for what is in the hand and for the other accessories (FpClearance): the free end shifted, the free part smaller
		FpLayout.apply(INDEX, P0, P1, P2);
	}

	/** This accessory's place in {@code ArmProtocol.ACCESSORIES}, for {@link FpLayout}. */
	private static final int INDEX = ArmProtocol.accessoryIndex(ArmProtocol.FLINT);

	/**
	 * The stalk and the jaws as spheres in the hand pass's view space at home, for {@link FpClearance}: the same points {@link #firstPoints} uses
	 * (the root on the screen's edge, the knuckles low on the right), the stalk's thickness and writhe, and the knuckles with their sparks' start.
	 */
	public static void firstPersonBody(boolean mainArmLeft, FpClearance.Body out) {
		float s = mainArmLeft ? -1F : 1F;
		Vector3f a = new Vector3f(s * 19F, -7.5F, -4F), b = new Vector3f(s * 16.5F, -4.5F, -9F), c = new Vector3f(s * 11.8F, -5.6F, -12.5F), t = new Vector3f(),
				dir = new Vector3f(c).sub(b).normalize();
		out.clear(a.x, a.y, a.z, 0.5F);
		for (int i = 1; i <= 5; i++) {
			float u = i / 5F;
			ArmMath.bezier(a, b, c, u, t);
			out.addFixed(t.x, t.y, t.z, STALK_W0 * 0.5F + 0.95F, u);
		}
		// the knuckles: a jaw each side of the gap, 2 px long, with the hot faces and the glint
		out.add(c.x + dir.x * 1.2F, c.y + dir.y * 1.2F, c.z + dir.z * 1.2F, (KN_J + GAP_OPEN * 0.5F) * FP_SCALE + 0.6F, 1F);
	}

	/** Sets the stalk's three control points for this view, the writhe, the tip's frame and the frames carried along the stalk. */
	private static void layout(ArmDraw d) {
		if (!fp) {
			thirdPoints(d.slim(), d.leftHand());
			// what the arm shows decides where the stalk's root is: from under the plating's bottom edge on the armour, the outer face otherwise
			gripVariant = AccessoryGrip.variantOf(d);
			AccessoryGrip.flintPort(d, ringOut, gripVariant, P0, P1);
			// near the lens (First-person Model's body): the free stalk is drawn smaller about its root, which stays on the arm
			if (near < 1F) {
				P1.sub(P0).mul(near).add(P0);
				P2.sub(P0).mul(near).add(P0);
			}
			wrAmp = 0.2F;
		} else {
			firstPoints(d.leftHand());
			wrAmp = 0.35F;
		}
		TIP.set(P2).sub(P1);
		if (TIP.lengthSquared() < 1e-6F) TIP.set(0F, -1F, 0F);
		TIP.normalize();
		// the stalk's length, for the feed coordinate and the fringe (a polyline through the control points is close)
		arc = 0.5F * (P0.distance(P1) + P1.distance(P2)) + 0.5F * P0.distance(P2);
		// frames carried along the base curve: tangents, then a normal carried along them (parallel transport) and the binormal
		for (int i = 0; i < SN; i++) {
			ArmMath.bezierTangent(P0, P1, P2, i / (float) (SN - 1), V2).normalize();
			TX[3 * i] = V2.x;
			TX[3 * i + 1] = V2.y;
			TX[3 * i + 2] = V2.z;
		}
		V1.set(TX[0], TX[1], TX[2]);
		V2.set(Math.abs(V1.y) < 0.9F ? 0F : 1F, Math.abs(V1.y) < 0.9F ? 1F : 0F, 0F);
		float seed = V1.dot(V2);
		V2.set(V2.x - V1.x * seed, V2.y - V1.y * seed, V2.z - V1.z * seed).normalize();
		for (int i = 0; i < SN; i++) {
			V1.set(TX[3 * i], TX[3 * i + 1], TX[3 * i + 2]);
			if (i > 0) {
				float dot = V2.dot(V1);
				V2.sub(V1.x * dot, V1.y * dot, V1.z * dot);
				if (V2.lengthSquared() < 1e-8F) V2.set(NX[3 * i - 3], NX[3 * i - 2], NX[3 * i - 1]);
				V2.normalize();
			}
			NX[3 * i] = V2.x;
			NX[3 * i + 1] = V2.y;
			NX[3 * i + 2] = V2.z;
			V1.cross(V2, AT);
			BX[3 * i] = AT.x;
			BX[3 * i + 1] = AT.y;
			BX[3 * i + 2] = AT.z;
		}
		// the knuckles' frame: the tip's direction along, the jaw's axis across the gap (the carried normal at the tip, turned toward the way
		// down so the jaws open and shut one above the other and the sparks fall through the gap), the third axis square to both
		frame(1F);
		JAW.set(FN[0], FN[1], FN[2]);
		if (JAW.dot(UPJ) > 0F) JAW.negate();
		JAW.fma(-JAW.dot(TIP), TIP);
		if (JAW.lengthSquared() < 1e-6F) JAW.set(FB[0], FB[1], FB[2]);
		JAW.normalize();
		JAW.cross(TIP, ACROSS).normalize();
		scale = sclK * (0.35F + 0.65F * knuckK);
		float gap = gapNow = ArmMath.lerp(GAP_OPEN, GAP_SHUT, ArmMath.clamp01(shut)) * scale;
		float centre = KN_ALONG * knuckK;
		GAP.set(P2).fma(centre + 0.3F * KN_T * scale, TIP);
		float off = 0.5F * gap + 0.5F * KN_J * scale;
		K1.set(GAP).fma(off, JAW);
		K2.set(GAP).fma(-off, JAW);
	}

	/** Interpolates the carried frame at {@code s} into {@link #FT}, {@link #FN}, {@link #FB}. */
	private static void frame(float s) {
		float f = ArmMath.clamp01(s) * (SN - 1);
		int i = Math.min(SN - 2, (int) f);
		float u = f - i;
		for (int a = 0; a < 3; a++) {
			FT[a] = ArmMath.lerp(TX[3 * i + a], TX[3 * i + 3 + a], u);
			FN[a] = ArmMath.lerp(NX[3 * i + a], NX[3 * i + 3 + a], u);
			FB[a] = ArmMath.lerp(BX[3 * i + a], BX[3 * i + 3 + a], u);
		}
	}

	private static final Loft.CurveFn STALK = (s, out) -> centre(s, out);
	private static final Loft.RadiusFn STALK_R = s -> radius(s);
	/** The growth front's fringe, only while the stalk is still growing (a finished stalk keeps its tip). */
	private static final Loft.AlphaFn STALK_A = s -> eroding ? ArmMath.fringe((reach - s) * arc, 1.5F) : 1F;
	private static final Loft.CurveFn SINEW = (s, out) -> {
		float turn = 0.9F * ArmMath.TAU * s * (arc / 12F) + 0.05F * time + ph;
		float r = radius(s) * 1.05F + 0.18F;
		centre(s, out);
		frame(s);
		float c = (float) Math.cos(turn) * r, sn = (float) Math.sin(turn) * r;
		out.add(FN[0] * c + FB[0] * sn, FN[1] * c + FB[1] * sn, FN[2] * c + FB[2] * sn);
	};
	private static final Loft.RadiusFn SINEW_R = s -> 0.17F * (1F + 0.35F * (float) Math.sin(ph + 6F * s - 0.5F * time));
	/** A thorn, neck or finger: a polyline through the three points in {@link #THORN}. */
	private static final Loft.CurveFn THORN_PATH = (s, out) -> {
		float f = ArmMath.clamp01(s) * 2F;
		int i = Math.min(1, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(THORN[3 * i], THORN[3 * i + 3], u), ArmMath.lerp(THORN[3 * i + 1], THORN[3 * i + 4], u), ArmMath.lerp(THORN[3 * i + 2], THORN[3 * i + 5], u));
	};
	private static final Loft.RadiusFn THORN_R = s -> thornW * (1F - 0.82F * s);

	/** The stalk's centre line at {@code s}: the Bezier plus a writhe that is still at both ends (RageTethers' maths). */
	private static void centre(float s, Vector3f out) {
		ArmMath.bezier(P0, P1, P2, s, out);
		frame(s);
		float env = (float) Math.sin(Math.PI * s) * wrAmp;
		float a = env * (float) Math.sin(ph + 6F * s + 0.31F * time);
		float b = 0.6F * env * (float) Math.cos(0.8F * ph + 4F * s + 0.23F * time);
		out.add(FN[0] * a + FB[0] * b, FN[1] * a + FB[1] * b, FN[2] * a + FB[2] * b);
	}

	/** The stalk's tube radius at {@code s}: 1.05 to 0.7 px across, with its two knuckles, a swelling into the tip and the pulses. */
	private static float radius(float s) {
		float w = ArmMath.lerp(STALK_W0, STALK_W1, s);
		for (int i = 0; i < KNUCKLE_S.length; i++) w *= 1F + KNUCKLE_A[i] * ArmMath.gauss(s - KNUCKLE_S[i], 0.045F);
		w *= 1F + 0.25F * ArmMath.sm((s - 0.9F) / 0.1F);
		w *= 1F + 0.45F * cur.pulse(s * arc, arc + FEED_PAD);
		return FistGeometry.width(w, sidesNow);
	}

	// ------------------------------------------------------------------ the holder: the tendrils where it leaves the arm

	/** What the arm under the stalk shows (the plain arm, the tendril arm, the plain hand, the armour): chosen in {@link #layout}. */
	private static AccessoryGrip.Variant gripVariant = AccessoryGrip.Variant.BARE;
	private static final Vector3f KNOT_N = new Vector3f();

	/**
	 * The tendrils the striker holds on to the arm with (2026-11-24, in place of the knot and two collars): five open strands round the
	 * forearm below the spyglass's, ending in a small knot where the stalk leaves; a strike snaps them tight for the instant the jaws shut.
	 */
	private static void holder(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (holdK < 0.03F) return;
		AccessoryGrip.begin(d, AccessoryGrip.FLINT, ringOut, gripVariant, P0, 0.25F * shut);
		if (layer == ArmLayer.BLACK) AccessoryGrip.knot(m, ST, PART_KNOT, P0, AccessoryGrip.normalAt(P0.x, P0.z, gripVariant, KNOT_N), holdK);
		AccessoryGrip.strands(m, layer, ST, holdK, 9, 5);
	}

	// ------------------------------------------------------------------ spurs

	/** Stiff spurs along the stalk, leaning toward the knuckles (two at LOD 1, three at full detail). */
	private static void spurs(SkinMesh m, boolean full) {
		int n = full ? SP_S.length : 2;
		for (int i = 0; i < n; i++) {
			float sprout = ArmMath.sm((built - (0.4F + 0.06F * i)) / 0.2F);
			if (SP_S[i] > reach || sprout < 0.03F) continue;
			float len = SP_LEN[i] * sprout * (fp ? 1.15F : 1F);
			float s = SP_S[i];
			centre(s, V1);
			frame(s);
			float az = SP_AZ[i] + 0.1F * (float) Math.sin(ph + 0.17F * time + i);
			float rx = FN[0] * (float) Math.cos(az) + FB[0] * (float) Math.sin(az), ry = FN[1] * (float) Math.cos(az) + FB[1] * (float) Math.sin(az),
					rz = FN[2] * (float) Math.cos(az) + FB[2] * (float) Math.sin(az);
			float lean = (float) Math.toRadians(SP_LEAN[i]), cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
			float root = radius(s) * 0.6F;
			float p0x = V1.x + rx * root, p0y = V1.y + ry * root, p0z = V1.z + rz * root;
			float dx = rx * cl + FT[0] * sl, dy = ry * cl + FT[1] * sl, dz = rz * cl + FT[2] * sl;
			float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			dx /= dl;
			dy /= dl;
			dz /= dl;
			// a straight spur (a stiff one: two segments in the same line)
			THORN[0] = p0x;
			THORN[1] = p0y;
			THORN[2] = p0z;
			THORN[3] = p0x + dx * len * 0.5F;
			THORN[4] = p0y + dy * len * 0.5F;
			THORN[5] = p0z + dz * len * 0.5F;
			THORN[6] = p0x + dx * len;
			THORN[7] = p0y + dy * len;
			THORN[8] = p0z + dz * len;
			thornW = SP_W[i] * (fp ? 1.2F : 1F) * 0.5F * sprout + 0.02F;
			Loft.tube(m, THORN_PATH, 0F, 1F, 3, 3, THORN_R, ArmLayer.BLACK, ArmLayer.BLACK, ST, PART_SPUR + i, 2F, 0F, len, UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ the knuckles

	/** Puts {@link #LOCAL} on a frame at {@code c}: x across the third way, y along the jaw's axis, z along the tip. */
	private static void basis(Vector3f c) {
		LOCAL.set(ACROSS.x, ACROSS.y, ACROSS.z, 0F, JAW.x, JAW.y, JAW.z, 0F, TIP.x, TIP.y, TIP.z, 0F, c.x, c.y, c.z, 1F);
	}

	/**
	 * The two hardened knuckles, the gap's glint and (black layer) their necks: each knuckle is a hard black lump of two cubes, one
	 * turned about the tip's axis so it reads as faceted, with a hot face toward the gap. {@code full}: the necks, the turned cubes and the
	 * glint's cross.
	 */
	private static void knuckles(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full) {
		float k = scale;
		boolean lit = d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED;
		float w = KN_B * k, h = KN_J * k, l = KN_T * k;
		for (int i = 0; i < 2; i++) {
			Vector3f c = i == 0 ? K1 : K2;
			float side = i == 0 ? 1F : -1F;
			basis(c);
			if (layer == ArmLayer.BLACK) {
				m.box(LOCAL, -w / 2F, -h / 2F, -l / 2F, w, h, l, ST.u0(PART_KN, i, 0F), ST.v0(PART_KN, i, 0F), 1F, 1F, m.tint());
				if (full) {
					// the turned cube: the same lump seen at 45 degrees round the tip's axis, a little smaller, so the corners read as facets
					LOCAL.rotateZ(0.785F);
					float sw = w * 0.78F, sh = h * 0.78F, sl = l * 0.8F;
					m.box(LOCAL, -sw / 2F, -sh / 2F, -sl / 2F, sw, sh, sl, ST.u0(PART_KN, 2 + i, 0F), ST.v0(PART_KN, 2 + i, 0F), 1F, 1F, m.tint());
					basis(c);
				}
			}
			if (lit && knuckK > 0.3F) {
				// the striking face: a thin slab on the side facing the gap, white-hot after a strike and cooling to an ember's glow
				float hw = w * 0.8F, hl = l * 0.8F, thick = hotThick(gapNow, k);
				m.box(LOCAL, -hw / 2F, -side * (h / 2F + 0.02F * k) - (side > 0F ? thick : 0F), -hl / 2F, hw, thick, hl, ST.u0(PART_HOT, i, 0F), ST.v0(PART_HOT, i, 0F), 1F, 1F,
						FistGeometry.grey(0.35F + 0.65F * heat));
			}
		}
		if (layer == ArmLayer.BLACK && full) necks(m);
		glint(d, layer, m, full, lit);
	}

	/**
	 * How thick a knuckle's hot face may be (px) with the jaws {@code gap} apart (face to face) at knuckle scale {@code k}: its natural
	 * 0.28 k, but never so thick that it reaches the middle of the gap, where it would pass through the other knuckle's black cube (and
	 * through the other face). Each face stands 0.02 k off its own knuckle, so with the jaws shut (0.12 k) it is 0.03 k and stops a hair short
	 * of the middle. The slab is only drawn if it has any thickness (2026-11-16: it used to run 0.24 k into the other knuckle at full clack).
	 */
	static float hotThick(float gap, float k) {
		return Math.max(0.01F * k, Math.min(0.28F * k, 0.5F * gap - 0.03F * k));
	}

	/** Two short black tendrils from the stalk's tip to the knuckles, the stalk's end splitting into them. */
	private static void necks(SkinMesh m) {
		for (int i = 0; i < 2; i++) {
			Vector3f c = i == 0 ? K1 : K2;
			THORN[0] = P2.x;
			THORN[1] = P2.y;
			THORN[2] = P2.z;
			THORN[3] = P2.x + TIP.x * 0.5F * scale + (c.x - GAP.x) * 0.5F;
			THORN[4] = P2.y + TIP.y * 0.5F * scale + (c.y - GAP.y) * 0.5F;
			THORN[5] = P2.z + TIP.z * 0.5F * scale + (c.z - GAP.z) * 0.5F;
			THORN[6] = c.x - TIP.x * 0.5F * KN_T * scale;
			THORN[7] = c.y - TIP.y * 0.5F * KN_T * scale;
			THORN[8] = c.z - TIP.z * 0.5F * KN_T * scale;
			thornW = 0.4F * scale;
			Loft.tube(m, THORN_PATH, 0F, 1F, 3, 3, THORN_R, ArmLayer.BLACK, ArmLayer.BLACK, ST, PART_NECK + i, 2F, 0F, 2.5F, UP_Y, 1F);
		}
	}

	/** The glint in the gap: a speck that breathes and flares at a strike, with a thin cross through it when it is bright. */
	private static void glint(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full, boolean lit) {
		if (!lit || knuckK < 0.4F) return;
		float flash = strikeAge < 3F ? 1F - strikeAge / 3F : 0F;
		float size = (0.5F + 0.9F * heat * heat + 1.2F * flash * Math.min(1.4F, strikePower)) * scale;
		basis(GAP);
		m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_GLINT, 0, 0F), ST.v0(PART_GLINT, 0, 0F), 1F, 1F, FistGeometry.grey(0.6F + 0.4F * heat));
		if (full && (heat > 0.55F || flash > 0F)) {
			float arm = (2.6F + 2.4F * flash) * scale, thin = 0.2F * scale;
			int grey = FistGeometry.grey(0.75F + 0.25F * flash);
			m.box(LOCAL, -arm / 2F, -thin / 2F, -thin / 2F, arm, thin, thin, ST.u0(PART_GLINT, 1, 0F), ST.v0(PART_GLINT, 1, 0F), 1F, 1F, grey);
			m.box(LOCAL, -thin / 2F, -arm / 2F, -thin / 2F, thin, arm, thin, ST.u0(PART_GLINT, 2, 0F), ST.v0(PART_GLINT, 2, 0F), 1F, 1F, grey);
		}
	}

	// ------------------------------------------------------------------ sparks and embers

	/**
	 * The strike's sparks: little cubes thrown out of the gap in a cone about the tip's direction, each with its own direction, speed
	 * and life, slowing, falling with the world's gravity and shrinking as they cool, each trailing a smaller one a moment behind it. Only
	 * the lit layer (the red, or the glow's when it is on) is drawn: sparks are light.
	 */
	private static void sparks(ArmDraw d, ArmLayer layer, SkinMesh m, int max) {
		if (!(strikeAge < FlintMotion.SPARK_TICKS) || !(d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) return;
		int count = Math.min(max, Math.max(2, Math.round(max * strikePower / FlintMotion.POWER_IGNITE * 1.25F)));
		float fall = SPARK_FALL * sclK;
		for (int i = 0; i < count; i++) {
			float sk = FlintMotion.spark(strikeAge, i, strikeSeed);
			if (!(sk < 1F)) continue;
			// a direction in a cone about the tip's, some of it back toward the jaw's axis so the spray is a fan and not a line
			float az = ArmMath.TAU * FlintMotion.hash(strikeSeed, i + 11);
			float cone = 0.25F + 1.0F * FlintMotion.hash(strikeSeed, i + 23);
			float sc = (float) Math.sin(cone), cc = (float) Math.cos(cone);
			float ca = (float) Math.cos(az), sa = (float) Math.sin(az);
			DIR.set(TIP).mul(cc).fma(sc * ca, JAW).fma(sc * sa, ACROSS);
			float speed = SPARK_REACH * sclK * (0.45F + 0.55F * FlintMotion.hash(strikeSeed, i + 37)) * (float) Math.sqrt(strikePower);
			// out fast, easing off (1 - (1 - sk)^2 of the reach), and pulled down as the square of the time
			float x = 1F - (1F - sk) * (1F - sk);
			float life = FlintMotion.SPARK_TICKS * (0.5F + 0.5F * FlintMotion.hash(strikeSeed + 7, i));
			float t = sk * life;
			float size = (0.8F - 0.6F * sk) * sclK * (0.7F + 0.3F * Math.min(1.5F, strikePower));
			int grey = FistGeometry.grey(1F - 0.55F * sk);
			for (int tail = 0; tail < 2; tail++) {
				// the trailing one sits a little earlier on the same path
				float xs = tail == 0 ? x : 1F - (1F - Math.max(0F, sk - 0.12F)) * (1F - Math.max(0F, sk - 0.12F));
				float ts = tail == 0 ? t : Math.max(0F, t - 1.2F);
				float sz = tail == 0 ? size : size * 0.6F;
				AT.set(GAP).fma(speed * xs, DIR).fma(-fall * ts * ts, UPV);
				float half = sz / 2F;
				if (half < 0.02F) continue;
				m.box(null, AT.x - half, AT.y - half, AT.z - half, sz, sz, sz, ST.u0(PART_SPARK + tail, i, 0F), ST.v0(PART_SPARK + tail, i, 0F), 1F, 1F, grey);
			}
		}
	}

	/**
	 * A few embers that lift off the knuckles all the time: each rises along the world's up, sways a little, grows from nothing and
	 * shrinks again as it cools, then starts over from a knuckle, on a period of its own (26..40 ticks), so they never come in step.
	 */
	private static void embers(ArmDraw d, ArmLayer layer, SkinMesh m, int count) {
		if (!(d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) return;
		float rise = (fp ? 7F : 6F) * sclK;
		for (int j = 0; j < count; j++) {
			float period = 26F + 14F * FlintMotion.hash(d.seed(), j + 61);
			float u = ArmMath.frac(time / period + FlintMotion.hash(d.seed(), j + 73));
			float grow = ArmMath.sm(u * 7F) * (1F - u) * (1F - u);
			float size = 1.15F * sclK * grow;
			if (size < 0.05F) continue;
			Vector3f from = (j & 1) == 0 ? K1 : K2;
			float sway = 0.7F * u * (float) Math.sin(ph + 2.1F * j + 0.35F * time);
			AT.set(from).fma(rise * u, UPV).fma(sway, ACROSS).fma(0.5F * sway, TIP);
			float half = size / 2F;
			m.box(null, AT.x - half, AT.y - half, AT.z - half, size, size, size, ST.u0(PART_EMBER, j, 0F), ST.v0(PART_EMBER, j, 0F), 1F, 1F, FistGeometry.grey(0.9F - 0.5F * u));
		}
	}

	// ------------------------------------------------------------------ the far levels of detail

	/** LOD 2: a thin stalk, a box for each knuckle and the glint. */
	private static void lod2(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.BLACK) {
			Loft.tube(m, STALK, 0F, reach, 4, 3, STALK_R, ArmLayer.BLACK, layer, ST, PART_STALK, 1F, 0F, arc, UP_Y, 1F);
			if (knuckK > 0.03F) {
				for (int i = 0; i < 2; i++) {
					basis(i == 0 ? K1 : K2);
					float w = KN_B * scale, h = KN_J * scale, l = KN_T * scale;
					m.box(LOCAL, -w / 2F, -h / 2F, -l / 2F, w, h, l, ST.u0(PART_BOX, i, 0F), ST.v0(PART_BOX, i, 0F), 1F, 1F, SkinMesh.WHITE);
				}
			}
		}
		if (knuckK > 0.4F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			float size = (0.9F + 1.4F * heat * heat) * scale;
			basis(GAP);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BOX, 2, 0F), ST.v0(PART_BOX, 2, 0F), 1F, 1F, FistGeometry.grey(0.6F + 0.4F * heat));
		}
	}

	/** LOD 3: the stalk as one tapering box and the knuckles as one box. */
	private static void lod3(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.BLACK) {
			V1.set(P2).sub(P0);
			float len = V1.length();
			if (len > 1e-4F) {
				TURN.rotationTo(0F, 0F, 1F, V1.x / len, V1.y / len, V1.z / len);
				LOCAL.identity().translate(P0).rotate(TURN);
				m.frustum(LOCAL, len * reach, 1F, 1F, 0.7F, 0.7F, ST.u0(PART_BOX, 1, 0F), ST.v0(PART_BOX, 1, 0F), 1F, 1F, 1F, SkinMesh.WHITE);
			}
		}
		float size = KN_J * 1.3F * knuckK;
		if (size > 0.05F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			LOCAL.identity().translate(GAP);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BOX, 0, 0F), ST.v0(PART_BOX, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		}
	}
}
