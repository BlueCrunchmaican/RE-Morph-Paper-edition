package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The shield accessory's look (2026-11-16): a shield woven out of black tendrils on the OFF arm, in the lantern's
 * style (static fizzing along every strand, red sinews and veins, a red eye in the boss), raised across the body while right-click
 * is held (sneak, until 2026-11-17) and drawn in to a knot at the wrist when lowered. It has no item: vanilla clients are sent a real shield instead,
 * and this is what players with the mod see.
 * <p>
 * <b>The look is MORPH</b> (my pick, 2026-11-16, from the two I was shown: "im not sure between having it come around
 * from the back of the player model or having that hand morph into the shield"; I chose the hand morphing into the shield, and the
 * back look, its setting and its command were removed): the hand itself is the shield. Rings of tendril close over the lower
 * forearm and the hand, and spokes spread from the wrist to the shield's rim, so the shield grows from the wrist and the shield is
 * where the hand was. There is no setting for it any more; an old config's {@code shieldLook} is ignored.
 * <p>
 * <b>The shield</b> is drawn in its own frame S (px): x across the face, y up, z out of the face toward whatever it faces. A
 * heater shape 12.8 wide and 19 tall with a rim, a plain weave of six warp strands and eight weft rows that follow the shape
 * (each weft row is a contour of the outline, so they bend down toward the point), a boss with a red eye ring, a red cross of
 * veins, and a few barbs and cilia round the rim. The weave is real: each strand passes over and under the ones it crosses.
 * {@code XF} carries S into the space the view draws in: arm space A of the off arm in third person (px, as the sheath's) and
 * the hand pass's view space in first person.
 * <p>
 * <b>Motion.</b> It grows out when switched on (the rings and spokes first, then the rim, the warp, the weft and last the boss) and
 * draws back the same way played backwards ({@link ShieldMotion#built}). Raising eases between a lowered pose (a small shield at
 * the wrist, the arm's axis ending in it) and the raised one ({@link ShieldMotion#raise}); the pose mixin raises the
 * off arm across the body with it. A blocked hit kicks the shield back and sends a ring through the weave
 * ({@link ShieldMotion#recoil}, {@link ShieldMotion#ripple}).
 * <p>
 * Detail drops with distance: LOD 1 (and icons) four warp strands and five rows with a thin rim; LOD 2 a plate of boxes; LOD 3
 * two boxes. Render thread only (shared scratch).
 */
public final class ShieldRenderer implements AccessoryRenderer {
	// ------------------------------------------------------------------ the shield's shape (frame S, px)

	/** Half the width, the top, the waist (where the sides start to curve in) and the point's y. */
	static final float HW = 6.4F, TOP = 8.6F, WAIST = 0F, TIP = -10.4F;
	/** How far the face bulges out at its middle, and the weave's amplitude and strand half-width (the tubes just touch where they cross). */
	static final float BULGE = 1.5F, WEAVE_A = 0.6F, STRAND_R = 0.55F;
	/** Warp strands, their spacing (px), and the stations along each (so the weft rows are the stations between the top and the point). */
	static final int NV = 6, STATIONS = 10;
	static final float COL = 1.8F;
	/** The warp's first column's x (the columns are centred). */
	static final float X0 = -COL * (NV - 1) / 2F;
	/** The weft's reach to either side, its stations (the columns and an end on each side, under the rim). */
	static final float EDGE = COL * (NV + 1) / 2F;
	static final int WEFT_STATIONS = NV + 2;
	/** The boss: its centre's y on the face, and the rings' radii. */
	static final float BOSS_Y = 1.2F, BOSS_R = 2.1F, EYE_R = 1.35F;
	/** Pulse feed coordinate: the shield is about this long from root to rim. */
	private static final float FEED = 36F;

	/** The part numbers of the static (each piece its own pattern). */
	private static final int PART_WARP = 140, PART_WEFT = 150, PART_RIM = 162, PART_RIM_RED = 163, PART_BOSS = 164, PART_EYE = 165, PART_CORE = 166, PART_VEIN = 167,
			PART_BARB = 180, PART_CILIA = 190, PART_CUFF = 200, PART_SPOKE = 206, PART_STEM = 214, PART_BOX = 230;

	/** The shield's size lowered, as a share of raised, and raised (third person). */
	private static final float LOW_SCALE_MORPH = 0.42F, RAISED_SCALE_MORPH = 0.88F;

	// ------------------------------------------------------------------ the outline (a closed loop of points in the face's plane)

	/** The outline's points, x and y each, from the top's middle clockwise (x to the right) round the side, down to the point and up the other side. */
	private static final int OUTLINE_N;
	private static final float[] OUTLINE;

	static {
		// the right half from the top's middle to the point, then the left half mirrored back up
		int top = 6, side = 3, bottom = 12;
		int half = top + side + bottom; // the points of the right half, the top's middle first and the point last
		float[] right = new float[2 * half];
		int k = 0;
		for (int i = 0; i < top; i++) {
			float x = HW * i / top;
			right[2 * k] = x;
			right[2 * k + 1] = topY(x);
			k++;
		}
		for (int i = 0; i < side; i++) {
			float y = ArmMath.lerp(topY(HW), WAIST, i / (float) side);
			right[2 * k] = HW;
			right[2 * k + 1] = y;
			k++;
		}
		for (int i = 0; i < bottom; i++) {
			// from the waist to the point, closer together near the point where the outline turns fastest
			float u = 1F - (float) Math.pow(i / (float) (bottom - 1), 0.85);
			float x = HW * u;
			right[2 * k] = x;
			right[2 * k + 1] = botY(x);
			k++;
		}
		OUTLINE_N = 2 * half - 2;
		OUTLINE = new float[2 * OUTLINE_N];
		for (int i = 0; i < half; i++) {
			OUTLINE[2 * i] = right[2 * i];
			OUTLINE[2 * i + 1] = right[2 * i + 1];
		}
		for (int i = 1; i < half - 1; i++) {
			int j = half - 1 + i;
			OUTLINE[2 * j] = -right[2 * (half - 1 - i)];
			OUTLINE[2 * j + 1] = right[2 * (half - 1 - i) + 1];
		}
	}

	/** The top edge's y at {@code x}: a shallow arch. */
	static float topY(float x) {
		float u = x / HW;
		return TOP - 1.3F * u * u;
	}

	/** The lower edge's y at {@code x}: the sides run straight down to the waist, then curve in to the point. */
	static float botY(float x) {
		float u = ArmMath.clamp01(Math.abs(x) / HW);
		return TIP + (WAIST - TIP) * (1F - (float) Math.pow(1F - u, 1.7));
	}

	/** How far the face stands out at ({@code x}, {@code y}): a shallow dome, highest in the middle. */
	static float face(float x, float y) {
		float u = x / HW;
		float v = (y - 0.5F) / 10F;
		return BULGE * (1F - u * u) * (1F - 0.3F * v * v);
	}

	// ------------------------------------------------------------------ one draw's working state

	private static final Static ST = new Static();
	private static final Matrix4f XF = new Matrix4f();
	private static final Quaternionf Q_LOW = new Quaternionf(), Q_UP = new Quaternionf(), Q_NOW = new Quaternionf();
	private static final Matrix3f BASIS = new Matrix3f();
	private static final Vector3f V1 = new Vector3f(), V2 = new Vector3f(), V3 = new Vector3f(), NRM = new Vector3f(), UPV = new Vector3f(), XV = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F);
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();

	// the current draw's numbers, for the curve callbacks below (render thread, one draw at a time)
	private static ArmDraw cur;
	private static boolean fp, eroding, rippling;
	private static int sides;
	private static float time, ph, built, raise, sizeK, rippleAge, rippleX, rippleY, rippleAmp, flinch;
	/** The tube being drawn: the strand's index and column x, its top and bottom y, its weave sign, its reach, its feed start. */
	private static int strand, row;
	private static float sx, sy0, sy1, sSign, reachNow, arcNow, feed0;
	/** The red accents' z above the face. */
	private static float lift;
	/** The weave's share of its amplitude (0 where the shield is too far away to show it), and the ring being drawn: its y and size. */
	private static float weaveK = 1F, ringY, ringScale = 1F;
	/** The red veins and sinews stand this far out of the face: toward whoever sees the shield (the front in third person, the back in first). */
	private static float veinLift = 0.95F;

	private static final Loft.CurveFn WARP = (s, out) -> {
		float k = s * (STATIONS - 1);
		float y = ArmMath.lerp(sy0, sy1, s);
		float x = sx + 0.1F * (float) Math.sin(ph + 3.7F * s + 0.19F * time + strand);
		float z = face(sx, y) + weaveK * sSign * WEAVE_A * (float) Math.cos(ArmMath.PI * k) + 0.08F * (float) Math.sin(ph + 0.27F * time + 2F * s + strand) + dent(x, y);
		out.set(x, y, z);
	};

	private static final Loft.CurveFn WEFT = (s, out) -> {
		float m = s * (WEFT_STATIONS - 1);
		float x = -EDGE + 2F * EDGE * s;
		float f = (row + 1F) / (STATIONS - 1F);
		float y = ArmMath.lerp(topY(x), botY(x), f) + 0.08F * (float) Math.sin(ph + 0.23F * time + 2.7F * s + row);
		float z = face(x, y) - weaveK * sSign * WEAVE_A * (float) Math.cos(ArmMath.PI * m) + dent(x, y);
		out.set(x, y, z);
	};

	private static final Loft.CurveFn RIM = (s, out) -> rimPoint(s, lift, out);
	private static final Loft.RadiusFn RIM_R = s -> rimRadius(s);
	private static final Loft.AlphaFn FRINGE = s -> eroding ? ArmMath.fringe((reachNow - s) * arcNow, 1.5F) : 1F;
	private static final Loft.RadiusFn STRAND = s -> STRAND_R;
	private static final Loft.RadiusFn VEIN_R = s -> 0.17F * (1F + 0.5F * cur.pulse(feed0 + s * 14F, FEED));

	/** The ring's displacement of the weave at ({@code x}, {@code y}) while a blow rings through it, else 0. */
	private static float dent(float x, float y) {
		if (!rippling) return 0F;
		float dx = x - rippleX, dy = y - rippleY;
		return rippleAmp * ShieldMotion.ripple((float) Math.sqrt(dx * dx + dy * dy), rippleAge);
	}

	/** A point of the outline at {@code s} (0..1 round the loop from the top's middle), standing {@code up} px out of the face, into {@code out}. */
	private static void rimPoint(float s, float up, Vector3f out) {
		float f = ArmMath.frac(s) * OUTLINE_N;
		int i = Math.min(OUTLINE_N - 1, (int) f);
		int j = (i + 1) % OUTLINE_N;
		float u = f - i;
		float x = ArmMath.lerp(OUTLINE[2 * i], OUTLINE[2 * j], u), y = ArmMath.lerp(OUTLINE[2 * i + 1], OUTLINE[2 * j + 1], u);
		out.set(x, y, face(x, y) + up + dent(x, y));
	}

	/** The rim's radius: thick, with a knuckle at each corner (the top corners, the waist, the point) and the pulses swelling it. */
	private static float rimRadius(float s) {
		float w = 0.85F;
		float f = ArmMath.frac(s);
		// corners at the top's ends (the loop's 0.0 and 0.5 are the top's middle and the point; the top's corners are ~0.12 and 0.88)
		w *= 1F + 0.28F * (ArmMath.gauss(f - 0.12F, 0.025F) + ArmMath.gauss(f - 0.88F, 0.025F) + ArmMath.gauss(f - 0.5F, 0.03F));
		w *= 1F + 0.35F * cur.pulse(feed0 + f * 40F, FEED + 10F);
		// the overlay's cue that it is up: a fatter rim while raised
		if (fp) w *= 1F + 0.25F * raise;
		return w;
	}

	// ------------------------------------------------------------------ the interface

	/** This accessory's place in {@code ArmProtocol.ACCESSORIES}, for {@link FpLayout}; and whether {@link #firstPersonBody} is placing it at home. */
	private static final int INDEX = ArmProtocol.accessoryIndex(ArmProtocol.SHIELD);
	private static boolean homeLayout;
	private static final float[][] BODY = {{0F, 6F, 1.2F, 5.6F}, {0F, 0F, 1.5F, 6.6F}, {0F, -5.5F, 1.2F, 5F}, {0F, -9.5F, 1F, 2.6F}};

	/**
	 * The shield as spheres in the hand pass's view space at home for {@link FpClearance}: its heater (a few spheres down its length, as wide as it
	 * is and a little thicker than its dome) where it is now, raised or lowered or between, and its stem's root at the screen's corner for it to
	 * shrink toward. Empty while it has not grown.
	 */
	public static void firstPersonBody(ArmDraw d, FpClearance.Body out) {
		float b = ShieldMotion.built(d.accessoryAge(ArmProtocol.SHIELD));
		boolean offLeft = !d.leftHand();
		float side = offLeft ? -1F : 1F;
		out.clear(side * 12F, -13F, -9F, 0.5F);
		if (!(b > 0.01F)) return;
		built = b;
		fp = true;
		raise = ShieldMotion.raise(d.seed(), d.time(), d.accessoryArg(ArmProtocol.SHIELD)) * ArmMath.clamp01(b * 2.5F);
		homeLayout = true;
		try {
			layoutFirstPerson(d, 0F);
		} finally {
			homeLayout = false;
		}
		float k = (float) Math.sqrt(XF.m00() * XF.m00() + XF.m01() * XF.m01() + XF.m02() * XF.m02());
		for (float[] s : BODY) {
			XF.transformPosition(V1.set(s[0], s[1], s[2]));
			out.add(V1.x, V1.y, V1.z, s[3] * k + 0.4F, 1F);
		}
		// the stem: three braided tendrils from the corner to the shield's back (handFirstPerson's bezier), which follows the shield by the square of how far along it is
		R0.set(side * 12F, -13F, -9F);
		R1.set(side * 8.5F, -7F, -10.5F);
		XF.transformPosition(V1.set(0F, -1.2F, face(0F, 0F) - 0.9F), R2);
		for (int i = 1; i <= 3; i++) {
			float u = i / 4F;
			ArmMath.bezier(R0, R1, R2, u, V2);
			out.addFixed(V2.x, V2.y, V2.z, 1.8F, u * u);
		}
	}

	/**
	 * The wrist bands: the animated gooey tendril bands are the shield's look and there is no player setting for them (removed 2026-11-24, after
	 * a day as a choice). The two closed rings it had before stay in {@link #hand} for one reason: a developer can start the game with
	 * {@code -Dmorphreborn.dev.shieldBands=classic} to see the old look again, or to restore it should the tendrils be disliked. It is read once, at
	 * class load, and is not documented for players.
	 */
	private static boolean classicBands = "classic".equalsIgnoreCase(System.getProperty("morphreborn.dev.shieldBands"));

	/** The tests' switch for the old rings (their golden quad stream); the game reads only the developer flag above. */
	static void classicBands(boolean classic) {
		classicBands = classic;
	}

	@Override
	public String id() {
		return ArmProtocol.SHIELD;
	}

	@Override
	public boolean offArm() {
		return true;
	}

	@Override
	public void emitThirdPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, false);
	}

	@Override
	public void emitFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m) {
		draw(d, layer, m, true);
	}

	/**
	 * Where the raised shield's face is in arm space A of the off arm (the effects' start: the sparks of a block, a shot sent back),
	 * as the sheath layer records it.
	 */
	@Override
	public @Nullable Vector3f restPoint(boolean slim, boolean leftArm, Vector3f out) {
		float outSign = leftArm ? 1F : -1F;
		float xc = (slim ? 0.5F : 1F) * outSign;
		return out.set(xc, 11F, -3F);
	}

	/**
	 * Where the raised shield's face is in the hand pass's view space (px), for the owner's own first-person view: the effects
	 * reach for it through the camera.
	 */
	public static Vector3f restPointFirstPerson(boolean offLeftArm, Vector3f out) {
		float side = offLeftArm ? -1F : 1F;
		return out.set(side * 4.8F, -0.8F, -11.8F);
	}

	// ------------------------------------------------------------------ the draw

	private static void draw(ArmDraw d, ArmLayer layer, SkinMesh m, boolean firstPerson) {
		if (layer == ArmLayer.SENSE || !d.accessoryOn(ArmProtocol.SHIELD)) return;
		built = ShieldMotion.built(d.accessoryAge(ArmProtocol.SHIELD));
		if (!(built > 0.01F)) return;
		cur = d;
		fp = firstPerson;
		time = d.time();
		ph = d.phase();
		int lod = d.lod();
		boolean full = lod == ArmDraw.LOD_FULL;
		// the same factor as the pose mixin's (ShieldPose.apply): the shield is up only as far as the arm is, so it does not float before the arm rises
		raise = ShieldMotion.raise(d.seed(), d.time(), d.accessoryArg(ArmProtocol.SHIELD)) * ArmMath.clamp01(built * 2.5F);
		ST.set(time, d.cadence(), 1F, d.seed());
		// a blow rings through the weave and kicks the shield back
		rippleAge = ShieldMotion.blockAge(d.seed(), d.time());
		rippling = full && rippleAge < ShieldMotion.BLOCK_TICKS;
		float power = ShieldMotion.blockPower(d.seed());
		rippleAmp = power;
		int hit = ShieldMotion.blockSeed(d.seed());
		rippleX = (hash(hit, 1) - 0.5F) * 7F;
		rippleY = 1F + hash(hit, 2) * 6F;
		float kick = ShieldMotion.recoil(rippleAge) * power * 1.6F;
		flinch = ShieldMotion.recoil(rippleAge) * power;
		if (fp && full && !rippling) {
			// the overlay's flash on reaching the raised pose: one ring through the weave from the boss, as a light blow would
			float up = ShieldMotion.raisedAge(d.seed(), d.time());
			if (up < ShieldMotion.BLOCK_TICKS) {
				rippling = true;
				rippleAge = up;
				rippleAmp = 0.5F;
				rippleX = 0F;
				rippleY = BOSS_Y;
			}
		}
		eroding = d.dissolve() && built < 0.999F;
		sides = 3;
		weaveK = full ? 1F : 0F;
		veinLift = fp || AccessoryView.backView() ? -0.95F : 0.95F;
		Loft.pulseFrom(d, FEED);
		try {
			if (firstPerson) layoutFirstPerson(d, kick);
			else layoutThirdPerson(d, kick);
			if (lod >= ArmDraw.LOD_FAR) {
				lod3(m, layer);
				return;
			}
			if (lod == 2) {
				lod2(m, layer);
				return;
			}
			float growth = built;
			// the hand's own rings and spokes hold the shield, in the space the shield sits in
			m.transform(null);
			hand(d, layer, m, full, growth);
			m.transform(XF);
			weave(d, layer, m, full, growth);
			rim(layer, m, full, growth);
			boss(layer, m, full, growth);
			if (full) barbs(layer, m, growth);
			m.transform(null);
		} finally {
			m.transform(null);
			Loft.pulseFrom(null, 0F);
		}
	}

	private static float hash(int a, int b) {
		int h = a * 0x27D4EB2D + b * 0x165667B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	// ------------------------------------------------------------------ layout: where the shield is

	/** The off arm's side and the arm's centre in A, set by {@link #layoutThirdPerson}. */
	private static float outSign, armX;
	/** The held shield's placement parameters of this draw (A or view px): its centre raised, lowered, and its sizes. */
	private static float scaleRaised, scaleLowered;
	private static final Vector3f C_UP = new Vector3f(), C_LOW = new Vector3f(), CENTRE = new Vector3f();

	/** The unit {@code n} (the face's outward normal) and {@code up} (toward the top, made square to {@code n}) as a rotation into {@code q}. */
	private static void orient(Quaternionf q, float nx, float ny, float nz, float ux, float uy, float uz) {
		NRM.set(nx, ny, nz).normalize();
		float dot = ux * NRM.x + uy * NRM.y + uz * NRM.z;
		UPV.set(ux - NRM.x * dot, uy - NRM.y * dot, uz - NRM.z * dot).normalize();
		UPV.cross(NRM, XV).normalize(); // x = y × z
		BASIS.set(XV.x, XV.y, XV.z, UPV.x, UPV.y, UPV.z, NRM.x, NRM.y, NRM.z);
		q.setFromNormalized(BASIS);
	}

	/** Third person: S into the off arm's space A. */
	private static void layoutThirdPerson(ArmDraw d, float kick) {
		boolean offLeft = !d.leftHand();
		outSign = offLeft ? 1F : -1F;
		armX = (d.slim() ? 0.5F : 1F) * outSign;
		// the arm's axis ends in the shield: the plate stands square-ish to it at the wrist, tilted up to face forward as the arm rises
		C_UP.set(armX, 10.9F, -0.2F);
		C_LOW.set(armX, 11.2F, -0.3F);
		scaleRaised = RAISED_SCALE_MORPH;
		scaleLowered = LOW_SCALE_MORPH;
		orient(Q_UP, outSign * 0.1F, 0.74F, -0.66F, 0F, -0.66F, -0.74F);
		orient(Q_LOW, 0F, 1F, -0.15F, 0F, 0F, -1F);
		place(kick);
	}

	/** First person: S into the hand pass's view space (px, x right, y up, z away). */
	private static void layoutFirstPerson(ArmDraw d, float kick) {
		boolean offLeft = !d.leftHand();
		float side = offLeft ? -1F : 1F;
		outSign = offLeft ? 1F : -1F;
		C_UP.set(side * 4.8F, -0.8F, -11.8F);
		// lowered but out: the rim rises into the screen's lower corner (its top 40 percent shows, about 3 px over the bottom edge at this depth), where vanilla
		// rests an off-hand item, so the player can see the shield is on (2026-11-24: lowered it was wholly below the lens and nothing showed)
		C_LOW.set(side * 8.2F, -7.8F, -10.4F);
		scaleRaised = 0.40F;
		scaleLowered = 0.38F;
		orient(Q_UP, -side * 0.16F, 0.12F, -0.98F, 0F, 1F, 0F);
		orient(Q_LOW, -side * 0.35F, 0.25F, -0.9F, -side * 0.4F, 1F, 0F);
		place(kick);
	}

	/** Blends the lowered and the raised placement by {@link #raise}, kicks the shield back along its normal, and sets {@link #XF}. */
	private static void place(float kick) {
		float r = raise;
		CENTRE.set(C_LOW).lerp(C_UP, r);
		sizeK = ArmMath.lerp(scaleLowered, scaleRaised, r);
		// the overlay made room for what is in the hand (FpClearance): the shield shifted and drawn smaller (not while its home is being worked out)
		if (fp && !homeLayout) {
			// shrunk toward the corner its stem comes from (the body's root in firstPersonBody), then shifted: the same placement FpClearance solved
			float k = FpLayout.scale(INDEX), side = outSign > 0F ? -1F : 1F;
			CENTRE.set(side * 12F + (CENTRE.x - side * 12F) * k + FpLayout.shiftX(INDEX), -13F + (CENTRE.y + 13F) * k + FpLayout.shiftY(INDEX),
					-9F + (CENTRE.z + 9F) * k + FpLayout.shiftZ(INDEX));
			sizeK *= k;
		}
		// on First-person Model's body, half a block from the lens: the shield's free part drawn smaller about the wrist, which stays on the arm
		float near = fp ? 1F : AccessoryView.near();
		if (near < 1F) {
			CENTRE.x = armX + (CENTRE.x - armX) * near;
			CENTRE.y = WRIST_Y + (CENTRE.y - WRIST_Y) * near;
			CENTRE.z *= near;
			sizeK *= near;
		}
		Q_NOW.set(Q_LOW).slerp(Q_UP, r);
		// the shield grows out of the spot it will hold: small at first
		float grow = 0.35F + 0.65F * ArmMath.sm(built * 2.2F);
		XF.translation(CENTRE).rotate(Q_NOW).translate(0F, 0F, -kick).scale(sizeK * grow);
	}

	// ------------------------------------------------------------------ the pieces

	private static float u(float g, float from, float to) {
		return ArmMath.sm((g - from) / (to - from));
	}

	/** Truncates a tube to {@code reach} of its length (a growing strand) with the fringe at its end; false when there is nothing to draw. */
	private static boolean reaching(float reach, float lengthPx) {
		reachNow = reach;
		arcNow = lengthPx;
		return reach > 0.03F;
	}

	/** The warp and weft. */
	private static void weave(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full, float g) {
		int lod = d.lod();
		boolean coarse = !full;
		int warpStride = coarse ? 2 : 1;
		int stations = coarse ? 6 : STATIONS;
		// the warp runs down from the top, in columns from the middle out
		for (int i = 0; i < NV; i += warpStride) {
			strand = i;
			sx = X0 + i * COL;
			sy0 = topY(sx);
			sy1 = botY(sx);
			sSign = (i & 1) == 0 ? 1F : -1F;
			float centre = Math.abs(i - (NV - 1) / 2F);
			// the weave spreads from the wrist, middle first
			float from = 0.5F + 0.04F * centre;
			float reach = u(g, from, from + 0.3F);
			if (!reaching(reach, sy0 - sy1)) continue;
			feed0 = 6F + i * 2F;
			Loft.tube(m, WARP, 0F, reach, stations, sides, STRAND, ArmLayer.BLACK, layer, ST, PART_WARP + i, 2F, feed0, feed0 + (sy0 - sy1), UP_Z, FRINGE);
		}
		// the weft follows the contours, each row after the warp has reached it
		int rows = STATIONS - 2;
		int rowStride = coarse ? 2 : 1;
		for (int j = 0; j < rows; j += rowStride) {
			row = j;
			// the weave's sign: warp i is over at station k when i + k is even and the weft row j sits at station j + 1, so the weft (which
			// subtracts its sign) is under there: its sign follows its row's parity
			sSign = (j & 1) == 0 ? 1F : -1F;
			float from = 0.55F + 0.03F * j;
			float reach = u(g, from, from + 0.3F);
			if (!reaching(reach, 2F * EDGE)) continue;
			feed0 = 10F + j * 1.5F;
			Loft.tube(m, WEFT, 0F, reach, coarse ? 5 : WEFT_STATIONS, sides, STRAND, ArmLayer.BLACK, layer, ST, PART_WEFT + j, 2F, feed0, feed0 + 2F * EDGE, UP_Z, FRINGE);
		}
		// the red cross: veins over the weave along the middle, down and across, brighter with the pulses
		if (full || lod == 1) {
			float veinReach = u(g, 0.7F, 1F);
			if (reaching(veinReach, 18F)) {
				lift = 0F;
				feed0 = 12F;
				Loft.tube(m, SPINE, 0F, veinReach, 9, 3, VEIN_R, ArmLayer.RED, layer, ST, PART_VEIN, 2F, feed0, feed0 + 14F, UP_Z, FRINGE);
				Loft.tube(m, CROSS, 0F, veinReach, 7, 3, VEIN_R, ArmLayer.RED, layer, ST, PART_VEIN + 1, 2F, feed0 + 4F, feed0 + 16F, UP_Z, FRINGE);
			}
		}
	}

	/** The red vein down the shield's middle, from the boss's ring to the point, and its cross-bar across the waist. */
	private static final Loft.CurveFn SPINE = (s, out) -> {
		float y = ArmMath.lerp(topY(0F) - 0.3F, botY(0F) + 1F, s);
		out.set(0.04F * (float) Math.sin(ph + 6F * s + 0.2F * time), y, face(0F, y) + veinLift + dent(0F, y));
	};
	private static final Loft.CurveFn CROSS = (s, out) -> {
		float x = ArmMath.lerp(-HW + 1F, HW - 1F, s);
		float y = BOSS_Y + 0.05F * (float) Math.sin(ph + 5F * s + 0.17F * time);
		out.set(x, y, face(x, y) + veinLift + dent(x, y));
	};

	/** The rim, with its red accent over it. */
	private static void rim(ArmLayer layer, SkinMesh m, boolean full, float g) {
		float reach = u(g, 0.32F, 0.66F);
		if (!reaching(reach, 56F)) return;
		feed0 = 0F;
		lift = 0.25F;
		int stations = full ? 26 : 12;
		if (reach > 0.999F && !eroding) {
			Loft.closedTube(m, RIM, stations, sides, RIM_R, ArmLayer.BLACK, layer, ST, PART_RIM, 2F, feed0, UP_Z, 1F);
		} else {
			Loft.tube(m, RIM, 0F, reach, Math.max(5, (int) (stations * reach)), sides, RIM_R, ArmLayer.BLACK, layer, ST, PART_RIM, 2F, feed0, feed0 + 56F, UP_Z, FRINGE);
		}
		if (full && reach > 0.6F) {
			// the red sinew laid over the rim, a little proud of it
			lift = veinLift;
			float red = u(g, 0.62F, 0.9F);
			if (red > 0.03F) {
				reachNow = red;
				Loft.tube(m, RIM, 0F, red, Math.max(6, (int) (14 * red)), 3, RED_RIM_R, ArmLayer.RED, layer, ST, PART_RIM_RED, 2F, 0F, 56F, UP_Z, FRINGE);
			}
		}
	}

	private static final Loft.RadiusFn RED_RIM_R = s -> 0.19F * (1F + 0.6F * cur.pulse(ArmMath.frac(s) * 40F, FEED + 10F)) * cue();

	/**
	 * The overlay's two cues on the red rim: a slow pulse (about two seconds) while the shield is out but lowered, so it reads as ready, and a
	 * brighter, fatter sinew while it is raised. 1 everywhere but the vanilla first-person overlay.
	 */
	private static float cue() {
		if (!fp) return 1F;
		float lowered = 0.6F * (0.5F + 0.5F * (float) Math.sin(ArmMath.TAU * time / 40F + ph)) * (1F - raise);
		return 1F + lowered + 0.5F * raise;
	}

	/** The boss: a ring on the face with a core in it and a red eye ring turning round the core. */
	private static void boss(ArmLayer layer, SkinMesh m, boolean full, float g) {
		float k = u(g, 0.78F, 1F);
		if (k < 0.04F) return;
		bossK = k;
		Loft.closedTube(m, BOSS_RING, full ? 12 : 8, 3, BOSS_RING_R, ArmLayer.BLACK, layer, ST, PART_BOSS, 2F, 18F, UP_Z, 1F);
		if (layer == ArmLayer.BLACK) {
			// the core, a small cube standing on its corner and turning
			TURN.rotationXYZ(0.62F + 0.03F * time + ph, 0.78F + 0.021F * time, 0.4F);
			float c = 1.7F * k, z = face(0F, BOSS_Y) + 0.7F;
			LOCAL.translation(0F, BOSS_Y, z).rotate(TURN);
			m.box(LOCAL, -c / 2F, -c / 2F, -c / 2F, c, c, c, ST.u0(PART_CORE, 0, 18F), ST.v0(PART_CORE, 0, 18F), 1F, 1F, SkinMesh.WHITE);
		}
		if (full || k > 0.5F) Loft.closedTube(m, EYE, full ? 10 : 6, 3, EYE_RAD, ArmLayer.RED, layer, ST, PART_EYE, 2F, 20F, UP_Z, 1F);
	}

	private static float bossK = 1F;
	private static final Loft.CurveFn BOSS_RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = BOSS_R * (0.4F + 0.6F * bossK) * (1F + 0.05F * (float) Math.sin(3F * a + ph + 0.1F * time));
		float x = r * (float) Math.cos(a), y = BOSS_Y + r * (float) Math.sin(a);
		out.set(x, y, face(x, y) + 0.8F + dent(x, y));
	};
	private static final Loft.RadiusFn BOSS_RING_R = s -> 0.5F * (1F + 0.25F * cur.pulse(18F + 8F * s, FEED));
	/** The eye ring: tilted off the face and turning, so it flickers past the core like the lantern's. */
	private static final Loft.CurveFn EYE = (s, out) -> {
		float a = ArmMath.TAU * s;
		float precess = 0.08F * time + ph;
		float tilt = 0.5F + 0.12F * (float) Math.sin(0.13F * time + ph);
		float r = EYE_R * (0.4F + 0.6F * bossK);
		float x = (float) Math.cos(a) * r;
		float z = (float) Math.sin(tilt) * (float) Math.cos(a - precess) * r + (float) Math.sin(a) * r * 0.6F;
		float y = (float) Math.sin(a) * r * 0.9F + BOSS_Y;
		out.set(x, y, face(0F, BOSS_Y) + 1.1F + z);
	};
	private static final Loft.RadiusFn EYE_RAD = s -> 0.17F * (1F + 0.25F * (float) Math.sin(2F * ArmMath.TAU * s + 0.2F * time));

	/**
	 * The barbs (black, hooked outward) and cilia (red, waving) round the rim: where along the outline, which way they lean out of the
	 * face, their lengths. Hand-tuned so no two are alike (the lantern's bristles' way).
	 */
	private static final float[] BARB_S = {0.02F, 0.14F, 0.37F, 0.5F, 0.63F, 0.86F}, BARB_LEN = {1.4F, 1.9F, 1.3F, 1.8F, 1.3F, 1.9F},
			BARB_LEAN = {0.5F, 0.2F, -0.3F, 0.6F, -0.2F, 0.3F};
	private static final float[] CIL_S = {0.08F, 0.31F, 0.58F, 0.92F}, CIL_LEN = {1.5F, 1.2F, 1.6F, 1.3F};
	private static final float[] THORN = new float[9];
	private static float thornW;
	private static final Loft.CurveFn THORN_PATH = (s, out) -> {
		float f = ArmMath.clamp01(s) * 2F;
		int i = Math.min(1, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(THORN[3 * i], THORN[3 * i + 3], u), ArmMath.lerp(THORN[3 * i + 1], THORN[3 * i + 4], u), ArmMath.lerp(THORN[3 * i + 2], THORN[3 * i + 5], u));
	};
	private static final Loft.RadiusFn THORN_R = s -> thornW * (1F - 0.8F * s);

	private static void barbs(ArmLayer layer, SkinMesh m, float g) {
		float sprout = u(g, 0.85F, 1F);
		if (sprout < 0.04F) return;
		for (int i = 0; i < BARB_S.length; i++) {
			if (layer != ArmLayer.BLACK) break;
			thorn(BARB_S[i], BARB_LEN[i] * sprout, BARB_LEAN[i], 0F);
			thornW = 0.34F * sprout + 0.02F;
			Loft.tube(m, THORN_PATH, 0F, 1F, 3, 3, THORN_R, ArmLayer.BLACK, layer, ST, PART_BARB + i, 2F, 4F, 8F, UP_Z, 1F);
		}
		for (int i = 0; i < CIL_S.length; i++) {
			if (layer != ArmLayer.RED && layer != ArmLayer.GLOW) break;
			thorn(CIL_S[i], CIL_LEN[i] * sprout, 0F, 0.45F * (float) Math.sin(ph + 0.4F * time + i * 1.3F));
			thornW = 0.15F * sprout + 0.02F;
			Loft.tube(m, THORN_PATH, 0F, 1F, 3, 3, THORN_R, ArmLayer.RED, layer, ST, PART_CILIA + i, 2F, 4F, 8F, UP_Z, 1F);
		}
	}

	/**
	 * Lays {@code THORN}: a barb or cilium that leaves the rim at {@code s} straight out of the outline, {@code lean} px/px toward the
	 * face's normal along its length, its second half swaying {@code wave} px sideways.
	 */
	private static void thorn(float s, float len, float lean, float wave) {
		rimPoint(s, 0.25F, V1);
		// the outline's outward direction at s: the normal of the outline's tangent in the face's plane
		float e = 0.004F;
		rimPoint(s - e, 0.25F, V2);
		rimPoint(s + e, 0.25F, V3);
		float tx = V3.x - V2.x, ty = V3.y - V2.y;
		float tl = (float) Math.sqrt(tx * tx + ty * ty);
		if (tl < 1e-6F) {
			tx = 1F;
			ty = 0F;
			tl = 1F;
		}
		// the loop runs clockwise (x to the right at the top), so the outward side is the tangent turned a quarter to the right
		float ox = ty / tl, oy = -tx / tl;
		THORN[0] = V1.x;
		THORN[1] = V1.y;
		THORN[2] = V1.z;
		THORN[3] = V1.x + ox * len * 0.5F;
		THORN[4] = V1.y + oy * len * 0.5F;
		THORN[5] = V1.z + lean * len * 0.5F;
		THORN[6] = THORN[3] + ox * len * 0.5F + (-oy) * wave;
		THORN[7] = THORN[4] + oy * len * 0.5F + ox * wave;
		THORN[8] = THORN[5] + lean * len * 0.5F;
	}

	// ------------------------------------------------------------------ the first-person stem's braid

	/**
	 * The tendrils of the first-person stem ({@link #handFirstPerson}): a bezier from {@code R0} over {@code R1} to {@code R2}, writhing a little.
	 * (They were written for the back look's roots, which the owner dropped for the morph look on 2026-11-16.)
	 */
	private static final Vector3f R0 = new Vector3f(), R1 = new Vector3f(), R2 = new Vector3f();
	private static int rootK;
	private static float rootW0, rootW1;
	private static final Loft.CurveFn ROOT = (s, out) -> {
		ArmMath.bezier(R0, R1, R2, s, out);
		// a slow braid: the root writhes a little, still at both ends
		float env = (float) Math.sin(Math.PI * s) * 0.45F;
		out.add(0F, env * (float) Math.sin(ph + 5F * s + 0.3F * time + rootK * 2F), env * 0.7F * (float) Math.cos(0.8F * ph + 4F * s + 0.22F * time + rootK));
	};
	private static final Loft.RadiusFn ROOT_RAD = s -> ArmMath.lerp(rootW0, rootW1, s) * 0.5F * (1F + 0.3F * cur.pulse(feed0 + s * 24F, FEED));
	/** The rings' tube radius. */
	private static final Loft.RadiusFn STRAP_R = s -> 0.5F;

	// ------------------------------------------------------------------ the morph look: the hand becomes the shield

	/** The rings that close over the lower forearm and hand, and the spokes from the wrist out to the rim. */
	private static final Vector3f S0 = new Vector3f(), S1 = new Vector3f(), S2 = new Vector3f();
	private static int spokeK;
	private static float spokeJit;
	private static final Loft.CurveFn SPOKE = (s, out) -> {
		ArmMath.bezier(S0, S1, S2, s, out);
		float env = (float) Math.sin(Math.PI * s) * 0.4F;
		out.add(0F, env * (float) Math.sin(ph + 4F * s + 0.27F * time + spokeK * 1.9F), env * (float) Math.cos(0.8F * ph + 3F * s + 0.2F * time + spokeK));
	};
	/** How much thicker the spokes are at their roots next to the tendril bands (0 for the developer-only classic rings and in first person). */
	private static float spokeRoot;
	private static final Loft.RadiusFn SPOKE_R = s -> (ArmMath.lerp(0.62F, 0.4F, s) + spokeRoot * (1F - s)) * (1F + 0.3F * cur.pulse(feed0 + s * 16F, FEED));
	/** Where the four spokes meet the rim (S) and where they leave the wrist (A, relative to the arm's centre). */
	private static final float WRIST_Y = 9.8F;
	private static final float[] SPOKE_RIM = {-5.6F, 2.5F, 5.6F, 2.5F, 0F, 7.6F, 0F, -9.2F}, WRIST_DX = {-1.9F, 1.9F, 0F, 0F}, WRIST_DZ = {0F, 0F, -1.9F, 1.9F};
	/** Rings over the arm: where (y) and how far out the squircle reaches. */
	private static final float[] CUFF_Y = {7.2F, 9.2F};
	private static final Loft.CurveFn CUFF = (s, out) -> {
		float a = ArmMath.TAU * s;
		float hx = 2.5F + (cur.chestArmour() ? 0.35F : 0F);
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		float x = hx * Math.signum(c) * (float) Math.sqrt(Math.abs(c)), z = hx * Math.signum(sn) * (float) Math.sqrt(Math.abs(sn));
		float flare = ringScale > 0F ? 1F + 0.12F * (float) Math.sin(a * 4F + ph + 0.1F * time) : 1F;
		out.set(armX + x * ringScale * flare, ringY, z * ringScale * flare);
	};

	private static void hand(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full, float g) {
		if (fp) {
			handFirstPerson(d, layer, m, full, g);
			return;
		}
		if (classicBands) {
			// the rings close over the forearm and the hand one after another, wrist last, so the hand is covered from the elbow down
			int rings = full ? 2 : 1;
			for (int i = 0; i < rings; i++) {
				float k = u(g, 0.12F * i, 0.12F * i + 0.28F);
				if (k < 0.05F) continue;
				ringY = CUFF_Y[i];
				ringScale = k;
				Loft.closedTube(m, CUFF, full ? 12 : 8, 3, STRAP_R, ArmLayer.BLACK, layer, ST, PART_CUFF + i, 2F, 20F + 2F * i, UP_Y, 1F);
			}
			spokeRoot = 0F;
		} else {
			// the animated, gooey tendril bands (2026-11-24): six strands wind down the forearm into a knot at the wrist, and the spokes
			// leave that knot a little thicker at their roots so the strands visibly flow into them
			AccessoryGrip.shieldBands(d, layer, m, ST, outSign, g, raise, flinch, full, d.lod());
			spokeRoot = 0.15F;
		}
		// the spokes leave the wrist, bow out, and meet the rim
		int spokes = full ? 4 : 2;
		for (int i = 0; i < spokes; i++) {
			float reach = u(g, 0.22F + 0.04F * i, 0.55F + 0.04F * i);
			spokeK = i;
			if (!reaching(reach, 14F)) continue;
			int w = full ? i : i * 2 % 4;
			S0.set(armX + WRIST_DX[w], 9.8F, WRIST_DZ[w]);
			XF.transformPosition(V3.set(SPOKE_RIM[2 * w], SPOKE_RIM[2 * w + 1], face(SPOKE_RIM[2 * w], SPOKE_RIM[2 * w + 1]) + 0.1F), S2);
			// the middle control point: halfway, pushed out from the arm's axis so the spokes bow like a cage
			S1.set(S0).lerp(S2, 0.5F);
			S1.x += WRIST_DX[w] * 0.9F;
			S1.z += WRIST_DZ[w] * 0.9F;
			feed0 = 20F + 2F * i;
			Loft.tube(m, SPOKE, 0F, reach, full ? 7 : 5, 3, SPOKE_R, ArmLayer.BLACK, layer, ST, PART_SPOKE + i, 2F, feed0, feed0 + 16F, UP_Y, FRINGE);
		}
	}

	/** First person: the forearm's stem, three tendrils braided from beyond the screen's corner to the shield's back, with two rings on it. */
	private static void handFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m, boolean full, float g) {
		float side = -outSign;
		spokeRoot = 0F;
		for (int k = 0; k < (full ? 3 : 2); k++) {
			float reach = u(g, 0.04F * k, 0.45F + 0.04F * k);
			rootK = k;
			if (!reaching(reach, 24F)) continue;
			float off = (k - 1) * 0.9F;
			R0.set(side * (12F + off), -13F + 0.5F * off, -9F + off);
			R1.set(side * (8.5F + off), -7F, -10.5F - off);
			XF.transformPosition(V3.set(off * 0.8F, -1.2F + 0.4F * off, face(0F, 0F) - 0.9F), R2);
			rootW0 = 1.1F;
			rootW1 = 0.8F;
			feed0 = 4F + 2F * k;
			Loft.tube(m, ROOT, 0F, reach, full ? 9 : 6, 3, ROOT_RAD, ArmLayer.BLACK, layer, ST, PART_STEM + k, 2F, feed0, feed0 + 24F, UP_Y, FRINGE);
		}
		// spokes from the stem's end out to the rim
		int spokes = full ? 4 : 2;
		for (int i = 0; i < spokes; i++) {
			float reach = u(g, 0.3F + 0.04F * i, 0.6F + 0.04F * i);
			spokeK = i;
			if (!reaching(reach, 12F)) continue;
			int w = full ? i : i * 2 % 4;
			XF.transformPosition(V3.set(0F, -1.2F, face(0F, 0F) - 0.9F), S0);
			XF.transformPosition(V3.set(SPOKE_RIM[2 * w], SPOKE_RIM[2 * w + 1], face(SPOKE_RIM[2 * w], SPOKE_RIM[2 * w + 1]) - 0.2F), S2);
			S1.set(S0).lerp(S2, 0.5F);
			S1.z += 1.2F;
			feed0 = 22F + 2F * i;
			Loft.tube(m, SPOKE, 0F, reach, full ? 7 : 5, 3, SPOKE_R, ArmLayer.BLACK, layer, ST, PART_SPOKE + i, 2F, feed0, feed0 + 16F, UP_Y, FRINGE);
		}
	}

	// ------------------------------------------------------------------ far away

	/** LOD 2: the plate and its boss as a few boxes. */
	private static void lod2(SkinMesh m, ArmLayer layer) {
		if (layer != ArmLayer.BLACK) return;
		m.transform(XF);
		float k = ArmMath.sm(built * 1.5F);
		m.box(null, -HW * k, -TOP * 0.2F * k, 0F, 2F * HW * k, (TOP * 1.2F) * k, 1.2F, ST.u0(PART_BOX, 0, 0F), ST.v0(PART_BOX, 0, 0F), 1F, 1F, SkinMesh.WHITE);
		m.box(null, -HW * 0.5F * k, TIP * k, 0F, HW * k, (-TIP - TOP * 0.2F) * k, 1.2F, ST.u0(PART_BOX, 1, 0F), ST.v0(PART_BOX, 1, 0F), 1F, 1F, SkinMesh.WHITE);
		m.box(null, -1.2F * k, BOSS_Y - 1.2F * k, 1.1F, 2.4F * k, 2.4F * k, 1.2F, ST.u0(PART_BOX, 2, 0F), ST.v0(PART_BOX, 2, 0F), 1F, 1F, SkinMesh.WHITE);
		m.transform(null);
	}

	/** LOD 3: two boxes. */
	private static void lod3(SkinMesh m, ArmLayer layer) {
		if (layer != ArmLayer.BLACK) return;
		m.transform(XF);
		float k = ArmMath.sm(built * 1.5F);
		m.box(null, -HW * k, TIP * 0.4F * k, 0F, 2F * HW * k, (TOP - TIP * 0.4F) * k, 1.4F, ST.u0(PART_BOX, 3, 0F), ST.v0(PART_BOX, 3, 0F), 1F, 1F, SkinMesh.WHITE);
		m.box(null, -1.8F * k, TIP * k, 0F, 3.6F * k, (-TIP * 0.6F) * k, 1.4F, ST.u0(PART_BOX, 4, 0F), ST.v0(PART_BOX, 4, 0F), 1F, 1F, SkinMesh.WHITE);
		m.transform(null);
	}
}
