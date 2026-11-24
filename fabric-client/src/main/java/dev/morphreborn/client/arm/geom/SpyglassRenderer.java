package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The spyglass accessory's look (2026-11-16): a tendril eye stalk, in the lantern's style but with an eye where the lure
 * hangs. A braided black stalk with a red sinew winding up it grows out of the arm (or the armour over it) at the top of the
 * shoulder and rises above it, ending in a socket of tendrils that cups an eyeball: a lump of light with a bright iris and a black
 * slit of a pupil, under two black lids. The stalk's static fizzes like every other part of the arm and its swellings run up it with
 * the arm's pulses (at the owner's pulse speed).
 * <p>
 * <b>The eye turns to look where its owner looks.</b> It looks along the owner's view (the world's yaw and pitch, brought into the arm's
 * space through the world pass's pose, {@link SpyglassMotion#toArm}), following it a little lazily; at rest it also wanders a little
 * round that, heavy-lidded, and blinks now and then.
 * <p>
 * <b>While the owner zooms</b> (the accessory's arg 1, and for your own eye the key at once) the stalk reaches out forward over the tool,
 * the lids open wide, the pupil widens and the eyeball brightens, and the eye locks on dead ahead. It lets go again when the zoom does.
 * <p>
 * Space: arm space A of the arm that holds the tool, px, y down, forward -Z (third person); the hand pass's view space in first
 * person, where the stalk comes in from the right edge (the left one for a left-handed player) above the lantern's, and draws back
 * out of sight as the zoom starts (first person hides the hands while zooming).
 * <p>
 * Detail drops with distance: LOD 1 (and icons) a plainer stalk, the socket and the eye with its lids; LOD 2 a thin stalk and a box; LOD 3
 * two boxes. Render thread only (shared scratch), and nothing is allocated per frame.
 */
public final class SpyglassRenderer implements AccessoryRenderer {
	// ------------------------------------------------------------------ the numbers (px)

	/** The stalk's width at its root and at the socket. */
	static final float STALK_W0 = 1.0F, STALK_W1 = 0.62F;
	/** The eyeball's side (a cube, with a turned one inside it so it reads as a lump), the iris's, and the lids' half-height. */
	static final float BALL = 2.1F, IRIS = 1.3F, LID_H = 1.3F;
	/** The socket's length, its width at the stalk and at its rim. */
	static final float SOCKET_LEN = 0.9F, SOCKET_W0 = 1.1F, SOCKET_W1 = 2.7F;
	/** The eye's size in first person, as a share of third person's. */
	static final float FP_SCALE = 0.85F;
	/** How far the zoom pushes the stalk's tip (px): sideways, up, and forward, times how far out it is. */
	static final float REACH_X = 3.2F, REACH_UP = 1.5F, REACH_Z = 5.2F;
	/** Stalk samples (frames carried along it) and its feed coordinate's length beyond its own (so the pulse runs on past the tip). */
	private static final int SN = 12;
	private static final float FEED_PAD = 10F;
	private static final int PART_STALK = 250, PART_SINEW = 251, PART_BARB = 252, PART_KNOT = 264, PART_SOCKET = 265, PART_FINGER = 266,
			PART_RIM = 272, PART_BALL = 273, PART_IRIS = 275, PART_PUPIL = 276, PART_LID = 277, PART_BOX = 280;
	/** The stalk's three knuckles: where (s) and how much thicker. */
	private static final float[] KNUCKLE_S = {0.28F, 0.55F, 0.80F}, KNUCKLE_A = {0.22F, 0.18F, 0.26F};
	/**
	 * The bristles along the stalk, base to tip: where (s), which way round it (radians), length (px), lean from straight out toward the
	 * tip (degrees), curl (radians), width (px at the root) and the wave a cilium makes (0: a stiff barb). Hand-tuned, so no two look alike.
	 */
	static final float[] BR_S = {0.16F, 0.34F, 0.50F, 0.66F, 0.84F}, BR_AZ = {1.0F, 4.3F, 2.4F, 5.5F, 3.3F}, BR_LEN = {1.5F, 1.0F, 1.8F, 0.9F, 1.2F},
			BR_LEAN = {58F, 80F, 40F, 72F, 64F}, BR_CURL = {0.30F, 0.05F, -0.20F, 0.10F, 0.35F}, BR_W = {0.42F, 0.13F, 0.46F, 0.12F, 0.36F},
			BR_WAVE = {0F, 0.38F, 0F, 0.34F, 0F};
	/** The socket's fingers: where each starts round the rim (radians). */
	private static final float[] FINGER_A = {0.6F, 2.0F, 3.5F, 5.0F};

	// ------------------------------------------------------------------ the draw's working state (render thread, one draw at a time)

	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f(), HOLD = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f(), TIP = new Vector3f(), AIM = new Vector3f(), EYE = new Vector3f(),
			RC = new Vector3f(), XF = new Vector3f(), YF = new Vector3f(), V1 = new Vector3f(), V2 = new Vector3f(), UP_Y = new Vector3f(0F, 1F, 0F),
			ALONG_Z = new Vector3f(0F, 0F, 1F);
	private static final float[] FT = new float[3], FN = new float[3], FB = new float[3], TX = new float[3 * SN], NX = new float[3 * SN], BX = new float[3 * SN];
	private static final float[] THORN = new float[9], ANGLES = new float[2];
	private static ArmDraw cur;
	private static boolean fp, eroding;
	private static float time, ph, built, ext, reach, eyeK, holdK, arc, wrAmp, sclK, near, ringX, ringOut, thornW, cover, focus;

	// ------------------------------------------------------------------ the interface

	@Override
	public String id() {
		return ArmProtocol.SPYGLASS;
	}

	@Override
	public void emitThirdPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, false);
	}

	@Override
	public void emitFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, true);
	}

	/** This accessory's place in {@code ArmProtocol.ACCESSORIES}, for {@link FpLayout}. */
	private static final int INDEX = ArmProtocol.accessoryIndex(ArmProtocol.SPYGLASS);

	/**
	 * The stalk and eye as spheres in the hand pass's view space at home, for {@link FpClearance} (the same points {@link #layout} uses in first
	 * person: the root on the screen's edge, the eye where the socket cups it): the stalk's thickness and writhe, and the eye with its socket and lids.
	 */
	public static void firstPersonBody(boolean mainArmLeft, FpClearance.Body out) {
		float s = mainArmLeft ? -1F : 1F;
		Vector3f a = new Vector3f(s * 19F, -1.0F, -4F), b = new Vector3f(s * 16.5F, 7.0F, -9F), c = new Vector3f(s * 10.5F, 4.8F, -13F), t = new Vector3f(), dir = new Vector3f(c).sub(b).normalize();
		out.clear(a.x, a.y, a.z, 0.5F);
		for (int i = 1; i <= 6; i++) {
			float u = i / 6F;
			ArmMath.bezier(a, b, c, u, t);
			out.addFixed(t.x, t.y, t.z, STALK_W0 * 0.5F + 0.95F, u);
		}
		// the socket and the eyeball (the lids and the bristles' tips reach a little further)
		out.add(c.x + dir.x * 1.0F, c.y + dir.y * 1.0F, c.z + dir.z * 1.0F, (BALL * 0.5F * 1.4F + 0.35F) * FP_SCALE + 0.5F, 1F);
	}

	/** Where the eye sits at rest in arm space A of the arm that holds the tool (the effects' start, as the sheath layer records it). */
	@Override
	public @Nullable Vector3f restPoint(boolean slim, boolean leftArm, Vector3f out) {
		float outSign = leftArm ? 1F : -1F;
		float xo = (slim ? 0.5F : 1F) * outSign + outSign * (slim ? 1.5F : 2F);
		return out.set(xo + outSign * 0.7F, -7.6F, -1.5F);
	}

	// ------------------------------------------------------------------ the draw

	private static void draw(ArmDraw d, ArmLayer layer, SkinMesh m, boolean firstPerson) {
		if (layer == ArmLayer.SENSE || !d.accessoryOn(ArmProtocol.SPYGLASS)) return;
		built = SpyglassMotion.built(d.accessoryAge(ArmProtocol.SPYGLASS));
		if (!(built > 0.01F)) return;
		cur = d;
		fp = firstPerson;
		time = d.time();
		ph = d.phase();
		SpyglassMotion.Source src = SpyglassMotion.source();
		boolean zooming = d.accessoryArg(ArmProtocol.SPYGLASS) != 0 || src != null && src.ownZoom(d.seed());
		ext = SpyglassMotion.extend(d.seed(), time, zooming);
		if (fp || AccessoryView.backView()) {
			// first person hides the hands while zooming, so the eye goes with them (and on First-person Model's body, where the eye would
			// otherwise reach out toward the lens)
			built *= 1F - ArmMath.sm(ext / 0.35F);
			if (!(built > 0.01F)) return;
		}
		int lod = d.lod();
		boolean full = lod == ArmDraw.LOD_FULL;
		ST.set(time, d.cadence(), 1F, d.seed());
		cover = fp ? SpyglassMotion.REST_COVER * (1F - ext) : SpyglassMotion.cover(d.seed(), time, ext);
		// the blink is only the owner's own to see in third person; first person's eye is a decoration at the screen's edge and blinks too
		if (fp) cover = Math.max(cover, SpyglassMotion.blink(d.seed(), time));
		focus = ext;
		// the stalk grows first, the eye comes alive at its tip, then the bristles sprout (ticks 0..14 of the build)
		reach = ArmMath.sm(built / 0.62F);
		eyeK = ArmMath.sm((built - 0.45F) / 0.4F);
		holdK = ArmMath.sm(built / 0.25F);
		near = fp ? FpLayout.scale(INDEX) : AccessoryView.near();
		sclK = (fp ? FP_SCALE : 1F) * near;
		eroding = d.dissolve() && reach < 0.999F;
		try {
			aim(d, m);
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
			int sides = full ? (fp ? 6 : 4) : 3;
			int stations = full ? (fp ? 10 : 8) : 4;
			if (full && !fp) holder(d, layer, m);
			if (layer == ArmLayer.BLACK) {
				Loft.tube(m, STALK, 0F, reach, stations, sides, STALK_R, ArmLayer.BLACK, layer, ST, PART_STALK, 1F, 0F, arc * reach, UP_Y, STALK_A);
			}
			if (full) Loft.tube(m, SINEW, 0F, reach, fp ? 12 : 9, 3, SINEW_R, ArmLayer.RED, layer, ST, PART_SINEW, 2F, 0F, arc * reach, UP_Y, 1F);
			if (full) bristles(layer, m);
			if (eyeK > 0.03F) eye(d, layer, m, full);
		} finally {
			m.transform(null);
			Loft.pulseFrom(null, 0F);
		}
	}

	// ------------------------------------------------------------------ where the eye looks

	/** Works out {@link #AIM}: the unit direction the pupil faces, in the drawing space of {@code m}. */
	private static void aim(ArmDraw d, SkinMesh m) {
		PoseStack.Pose pose = m.pose();
		boolean world = !fp && pose != null && SpyglassMotion.aim(d.seed(), time, ext, ANGLES);
		if (world) {
			SpyglassMotion.toArm(pose.pose(), ANGLES[0], ANGLES[1], AIM);
		} else {
			// no world to read (first person, a preview, the doll): the eye looks ahead, wandering a little, the stalk's own side turned in
			float yaw = SpyglassMotion.wanderYaw(d.seed(), time) * (1F - ext), pitch = SpyglassMotion.wanderPitch(d.seed(), time) * (1F - ext);
			if (fp) yaw += (d.leftHand() ? 1F : -1F) * 16F * (1F - ext); // it sits at the screen's edge and looks in toward the middle
			SpyglassMotion.local(yaw, pitch + (fp ? 8F : 0F), AIM);
			if (fp) AIM.y = -AIM.y; // the hand pass's y is up
		}
	}

	// ------------------------------------------------------------------ layout and the stalk

	/** Sets the stalk's three control points for this view, the writhe, and the frames carried along it. */
	private static void layout(ArmDraw d) {
		if (!fp) {
			float out = d.leftHand() ? 1F : -1F;
			float hx = d.slim() ? 1.5F : 2F;
			float xc = (d.slim() ? 0.5F : 1F) * out;
			float xo = xc + out * hx;
			ringX = xc;
			ringOut = out;
			// out of the outer face at the top of the arm, just clear of its back, up over the shoulder to the socket beside the head
			P0.set(xo - 0.3F * out, 0.9F, 0.9F);
			P1.set(xo + out * 1.9F, -3.4F, 1.7F);
			P2.set(xo + out * 0.7F, -7.0F, -1.3F);
			// what the arm shows decides where the stalk's root is: under the top plate on the armour, on the outer face otherwise
			gripVariant = AccessoryGrip.variantOf(d);
			AccessoryGrip.spyglassPort(d, out, gripVariant, P0, P1);
			// near the lens (First-person Model's body): the free stalk is drawn smaller about its root, which stays on the arm
			if (near < 1F) {
				P1.sub(P0).mul(near).add(P0);
				P2.sub(P0).mul(near).add(P0);
			}
			// reaching out: forward and up, in the direction the owner looks (leaning toward the head, where the shoulder is in the way, only a little)
			float ex = AIM.x, ez = AIM.z;
			if (ex * out < 0F) ex *= 0.2F;
			P2.add(ex * REACH_X * ext, -REACH_UP * ext, Math.min(ez, 0.2F) * REACH_Z * ext);
			P1.add(ex * REACH_X * 0.35F * ext, -REACH_UP * 0.4F * ext, Math.min(ez, 0.2F) * REACH_Z * 0.3F * ext);
			wrAmp = 0.28F * (1F - 0.6F * ext);
		} else {
			// in from the right edge, over the lantern's stalk, to the eye at the top right of the view (x right, y up)
			float s = d.leftHand() ? -1F : 1F;
			P0.set(s * 19F, -1.0F, -4F);
			P1.set(s * 16.5F, 7.0F, -9F);
			P2.set(s * 10.5F, 4.8F, -13F);
			// made room for what is in the hand and for the other accessories (FpClearance), by shifting the free end and shrinking the free part
			FpLayout.apply(INDEX, P0, P1, P2);
			wrAmp = 0.4F;
		}
		TIP.set(P2).sub(P1);
		if (TIP.lengthSquared() < 1e-6F) TIP.set(0F, -1F, 0F);
		TIP.normalize();
		// the stalk's length, for the feed coordinate and the fringe (a polyline through the control points is close)
		arc = 0.5F * (P0.distance(P1) + P1.distance(P2)) + 0.5F * P0.distance(P2);
		// frames carried along the base curve
		for (int i = 0; i < SN; i++) {
			float s = i / (float) (SN - 1);
			ArmMath.bezierTangent(P0, P1, P2, s, V2).normalize();
			TX[3 * i] = V2.x;
			TX[3 * i + 1] = V2.y;
			TX[3 * i + 2] = V2.z;
		}
		// N: start from a vector square to the first tangent, then carry it (parallel transport)
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
			V1.cross(V2, XF);
			BX[3 * i] = XF.x;
			BX[3 * i + 1] = XF.y;
			BX[3 * i + 2] = XF.z;
		}
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
		float turn = 0.9F * ArmMath.TAU * s * (arc / 13F) + 0.045F * time + ph;
		float r = radius(s) * 1.05F + 0.18F;
		centre(s, out);
		frame(s);
		float c = (float) Math.cos(turn) * r, sn = (float) Math.sin(turn) * r;
		out.add(FN[0] * c + FB[0] * sn, FN[1] * c + FB[1] * sn, FN[2] * c + FB[2] * sn);
	};
	private static final Loft.RadiusFn SINEW_R = s -> 0.17F * (1F + 0.35F * (float) Math.sin(ph + 6F * s - 0.5F * time));
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

	/** The stalk's tube radius at {@code s}: 1.0 to 0.62 px across, with its knuckles, a swelling into the socket and the pulses. */
	private static float radius(float s) {
		float w = ArmMath.lerp(STALK_W0, STALK_W1, s);
		for (int i = 0; i < KNUCKLE_S.length; i++) w *= 1F + KNUCKLE_A[i] * ArmMath.gauss(s - KNUCKLE_S[i], 0.05F);
		w *= 1F + 0.3F * ArmMath.sm((s - 0.9F) / 0.1F);
		w *= 1F + 0.45F * cur.pulse(s * arc, arc + FEED_PAD);
		return FistGeometry.width(w, fp ? 6 : 4);
	}

	// ------------------------------------------------------------------ the holder: the tendrils where it leaves the arm

	/** What the arm under the stalk shows (the plain arm, the tendril arm, the plain hand, the armour): chosen in {@link #layout}. */
	private static AccessoryGrip.Variant gripVariant = AccessoryGrip.Variant.BARE;
	private static final Vector3f KNOT_N = new Vector3f();

	/**
	 * The tendrils the stalk holds on to the arm with (2026-11-24, in place of the knot and two collars): five open strands winding the forearm's
	 * top, a different part of a turn each, ending in a small knot where the stalk leaves; a zoom tightens them.
	 */
	private static void holder(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (holdK < 0.03F) return;
		AccessoryGrip.begin(d, AccessoryGrip.SPYGLASS, ringOut, gripVariant, P0, 0.20F * ext);
		if (layer == ArmLayer.BLACK) AccessoryGrip.knot(m, ST, PART_KNOT, P0, AccessoryGrip.normalAt(P0.x, P0.z, gripVariant, KNOT_N), holdK);
		AccessoryGrip.strands(m, layer, ST, holdK, 9, 5);
	}

	// ------------------------------------------------------------------ bristles

	private static void bristles(ArmLayer layer, SkinMesh m) {
		for (int i = 0; i < BR_S.length; i++) {
			boolean barb = BR_WAVE[i] == 0F;
			ArmLayer pieceLayer = barb ? ArmLayer.BLACK : ArmLayer.RED;
			if (layer != pieceLayer) continue;
			float sprout = ArmMath.sm((built - (0.45F + 0.05F * i)) / 0.2F);
			if (BR_S[i] > reach || sprout < 0.03F) continue;
			float len = BR_LEN[i] * sprout * (fp ? 1.15F : 1F);
			float s = BR_S[i];
			centre(s, V1);
			frame(s);
			float az = BR_AZ[i] + 0.15F * (float) Math.sin(ph + 0.17F * time + i);
			float rx = FN[0] * (float) Math.cos(az) + FB[0] * (float) Math.sin(az), ry = FN[1] * (float) Math.cos(az) + FB[1] * (float) Math.sin(az),
					rz = FN[2] * (float) Math.cos(az) + FB[2] * (float) Math.sin(az);
			float lean = (float) Math.toRadians(BR_LEAN[i]), cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
			float root = radius(s) * 0.6F;
			float p0x = V1.x + rx * root, p0y = V1.y + ry * root, p0z = V1.z + rz * root;
			float dx = rx * cl + FT[0] * sl, dy = ry * cl + FT[1] * sl, dz = rz * cl + FT[2] * sl;
			float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			dx /= dl;
			dy /= dl;
			dz /= dl;
			float half = len * 0.5F;
			float p1x = p0x + dx * half, p1y = p0y + dy * half, p1z = p0z + dz * half;
			// the second half bends toward the tip by the curl (a hook), and a cilium sways in a travelling wave
			float wave = BR_WAVE[i] * (float) Math.sin(ph + 5F * s + 0.55F * time + i * 1.3F);
			float qx = dx + FT[0] * BR_CURL[i] * 1.6F + wave * rx, qy = dy + FT[1] * BR_CURL[i] * 1.6F + wave * ry, qz = dz + FT[2] * BR_CURL[i] * 1.6F + wave * rz;
			float ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
			qx /= ql;
			qy /= ql;
			qz /= ql;
			THORN[0] = p0x;
			THORN[1] = p0y;
			THORN[2] = p0z;
			THORN[3] = p1x;
			THORN[4] = p1y;
			THORN[5] = p1z;
			THORN[6] = p1x + qx * half;
			THORN[7] = p1y + qy * half;
			THORN[8] = p1z + qz * half;
			thornW = BR_W[i] * (fp ? 1.2F : 1F) * 0.5F * sprout + 0.02F;
			Loft.tube(m, THORN_PATH, 0F, 1F, 3, 3, THORN_R, pieceLayer, layer, ST, PART_BARB + i, 2F, 0F, len, UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ the eye

	private static final Loft.CurveFn RIM = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 0.5F * SOCKET_W1 * sclK * (0.35F + 0.65F * eyeK) * (1F + 0.04F * (float) Math.sin(0.2F * time + ph + 7F * s));
		float c = (float) Math.cos(a) * r, sn = (float) Math.sin(a) * r;
		out.set(RC.x + FN[0] * c + FB[0] * sn, RC.y + FN[1] * c + FB[1] * sn, RC.z + FN[2] * c + FB[2] * sn);
	};
	private static final Loft.RadiusFn RIM_R = s -> 0.16F * (1F + 0.25F * (float) Math.sin(2F * ArmMath.TAU * s + 0.2F * time + ph));
	private static int fingerI;
	private static final Loft.CurveFn FINGER = (s, out) -> {
		float f = ArmMath.clamp01(s) * 2F;
		int i = Math.min(1, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(THORN[3 * i], THORN[3 * i + 3], u), ArmMath.lerp(THORN[3 * i + 1], THORN[3 * i + 4], u), ArmMath.lerp(THORN[3 * i + 2], THORN[3 * i + 5], u));
	};

	/** The socket, the eyeball with its iris and pupil, and the lids; {@code full}: the fingers, the rim and the lids' detail too. */
	private static void eye(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full) {
		float k = eyeK * sclK;
		float sock = SOCKET_LEN * eyeK;
		frame(1F);
		// the eye's centre: past the socket along the stalk's last direction
		RC.set(P2).fma(sock, TIP);
		EYE.set(P2).fma(sock + 0.25F * k, TIP);
		if (layer == ArmLayer.BLACK) {
			// the socket: a cup of tendrils that grows from the stalk's width to its rim
			HOLD.identity().translate(P2).rotate(TURN.rotationTo(ALONG_Z, TIP));
			m.frustum(HOLD, Math.max(0.05F, sock), SOCKET_W0 * eyeK, SOCKET_W0 * eyeK, SOCKET_W1 * k, SOCKET_W1 * k, ST.u0(PART_SOCKET, 0, 0.5F), ST.v0(PART_SOCKET, 0, 0.5F), 2F, 1F, 1F,
					m.tint());
			if (full) fingers(m, k);
		}
		if (full && layer == ArmLayer.RED && eyeK > 0.3F) {
			Loft.closedTube(m, RIM, 8, 3, RIM_R, ArmLayer.RED, layer, ST, PART_RIM, 2F, 0F, TIP, 1F);
		}
		basis();
		// the eyeball: two cubes, one turned, so it reads as a lump of light and not a die; brighter while zooming
		float shade = 0.5F + 0.4F * focus + 0.06F * (float) Math.sin(0.19F * time + ph);
		float size = BALL * k;
		boolean lit = d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED;
		if (lit && size > 0.05F) {
			int grey = FistGeometry.grey(shade);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BALL, 0, 0F), ST.v0(PART_BALL, 0, 0F), 1F, 1F, grey);
			if (full) {
				LOCAL.rotateZ(0.78F).rotateX(0.62F);
				float small = size * 0.78F;
				m.box(LOCAL, -small / 2F, -small / 2F, -small / 2F, small, small, small, ST.u0(PART_BALL, 1, 0F), ST.v0(PART_BALL, 1, 0F), 1F, 1F, grey);
				basis();
			}
			// the iris, brighter than the rest, on the face the eye looks along
			float is = IRIS * k * (0.92F + 0.12F * focus);
			m.box(LOCAL, -is / 2F, -is / 2F, 0.85F * k, is, is, 0.4F * k, ST.u0(PART_IRIS, 0, 0F), ST.v0(PART_IRIS, 0, 0F), 1F, 1F, FistGeometry.grey(Math.min(1F, shade + 0.35F)));
		}
		if (layer == ArmLayer.BLACK) {
			// the pupil, a vertical slit that widens while it focuses, standing just out of the iris
			float pw = ArmMath.lerp(0.32F, 0.8F, focus) * k, ph2 = (1.1F - 0.1F * focus) * k;
			m.box(LOCAL, -pw / 2F, -ph2 / 2F, 1.1F * k, pw, ph2, 0.25F * k, ST.u0(PART_PUPIL, 0, 0F), ST.v0(PART_PUPIL, 0, 0F), 1F, 1F, m.tint());
			lids(m, k);
		}
	}

	/** Puts {@link #LOCAL} on the eye's own frame at its centre: +Z along where it looks, +Y down (the stalk's own down), right-handed. */
	private static void basis() {
		float downY = fp ? -1F : 1F;
		XF.set(0F, downY, 0F).cross(AIM);
		if (XF.lengthSquared() < 1e-6F) XF.set(1F, 0F, 0F).cross(AIM);
		XF.normalize();
		AIM.cross(XF, YF);
		LOCAL.set(XF.x, XF.y, XF.z, 0F, YF.x, YF.y, YF.z, 0F, AIM.x, AIM.y, AIM.z, 0F, EYE.x, EYE.y, EYE.z, 1F);
	}

	/** Two black lids in front of the ball: each covers from its edge in by {@code cover} of the ball's half-height, and leaves a brow's sliver when open. */
	private static void lids(SkinMesh m, float k) {
		float h = LID_H * k, w = 2.5F * k;
		float c = ArmMath.clamp01(cover);
		float t = (c * h + 0.18F * k) * ArmMath.sm(eyeK * 2F);
		// in front of the pupil's face (1.35), so a shut lid hides it
		float front = 1.0F * k, depth = 0.6F * k;
		// the upper lid hangs from the top (-Y) and the lower rises from the bottom (+Y)
		m.box(LOCAL, -w / 2F, -h, front, w, t, depth, ST.u0(PART_LID, 0, 0F), ST.v0(PART_LID, 0, 0F), 1F, 1F, m.tint());
		m.box(LOCAL, -w / 2F, h - t, front, w, t, depth, ST.u0(PART_LID, 1, 0F), ST.v0(PART_LID, 1, 0F), 1F, 1F, m.tint());
	}

	/** Four tendrils that rise from the socket's rim and curl over the eyeball's sides, holding it. */
	private static void fingers(SkinMesh m, float k) {
		frame(1F);
		for (fingerI = 0; fingerI < FINGER_A.length; fingerI++) {
			float a = FINGER_A[fingerI] + 0.12F * (float) Math.sin(ph + 0.11F * time + fingerI);
			float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
			float ux = FN[0] * ca + FB[0] * sa, uy = FN[1] * ca + FB[1] * sa, uz = FN[2] * ca + FB[2] * sa;
			float rim = 0.5F * SOCKET_W1 * k;
			// root on the rim, a bend beside the ball, the tip curled in over its front
			THORN[0] = RC.x + ux * rim * 0.8F;
			THORN[1] = RC.y + uy * rim * 0.8F;
			THORN[2] = RC.z + uz * rim * 0.8F;
			THORN[3] = EYE.x + ux * rim * 1.15F + TIP.x * 0.5F * k;
			THORN[4] = EYE.y + uy * rim * 1.15F + TIP.y * 0.5F * k;
			THORN[5] = EYE.z + uz * rim * 1.15F + TIP.z * 0.5F * k;
			THORN[6] = EYE.x + ux * rim * 0.95F + TIP.x * 1.5F * k;
			THORN[7] = EYE.y + uy * rim * 0.95F + TIP.y * 1.5F * k;
			THORN[8] = EYE.z + uz * rim * 0.95F + TIP.z * 1.5F * k;
			thornW = 0.3F * k;
			Loft.tube(m, FINGER, 0F, 1F, 3, 3, THORN_R, ArmLayer.BLACK, ArmLayer.BLACK, ST, PART_FINGER + fingerI, 2F, 0F, 2.2F, UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ the far levels of detail

	/** LOD 2: a thin stalk and the eye as one small box. */
	private static void lod2(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.BLACK) {
			Loft.tube(m, STALK, 0F, reach, 4, 3, STALK_R, ArmLayer.BLACK, layer, ST, PART_STALK, 1F, 0F, arc, UP_Y, 1F);
		}
		float size = BALL * 1.2F * eyeK;
		if (size > 0.05F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			LOCAL.identity().translate(P2.x + TIP.x * 0.9F, P2.y + TIP.y * 0.9F, P2.z + TIP.z * 0.9F);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BOX, 0, 0F), ST.v0(PART_BOX, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		}
	}

	/** LOD 3: the stalk as one tapering box and the eye as one box. */
	private static void lod3(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.BLACK) {
			V1.set(P2).sub(P0);
			float len = V1.length();
			if (len > 1e-4F) {
				TURN.rotationTo(0F, 0F, 1F, V1.x / len, V1.y / len, V1.z / len);
				LOCAL.identity().translate(P0).rotate(TURN);
				m.frustum(LOCAL, len * reach, 1F, 1F, 0.6F, 0.6F, ST.u0(PART_BOX, 1, 0F), ST.v0(PART_BOX, 1, 0F), 1F, 1F, 1F, SkinMesh.WHITE);
			}
		}
		float size = BALL * 1.2F * eyeK;
		if (size > 0.05F && (d.glow() ? layer == ArmLayer.GLOW : layer == ArmLayer.RED)) {
			LOCAL.identity().translate(P2);
			m.box(LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, ST.u0(PART_BOX, 0, 0F), ST.v0(PART_BOX, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		}
	}
}
