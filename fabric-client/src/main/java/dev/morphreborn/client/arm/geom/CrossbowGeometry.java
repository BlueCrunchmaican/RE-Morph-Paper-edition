package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The crossbow (my design, 2026-11-16: it loads itself, and after every shot the tendril bolt regrows out of the weapon over
 * the vanilla charge time): a crossbow of tendril with a ribbed stock of static. The stock is a red core in a row of black ribs,
 * so the static shows between them like the gaps in a spine, with a pistol grip hanging under it (the hand closes round that, and
 * it is where the sheath's claw is) and a butt knot behind. Two rails run along its top with the red flight groove between them. At
 * the front a riser knot carries the prod: two braided limbs that sweep out and curl forward to red nocks, and the string, a thin
 * red tendril that is alive.
 * <p>
 * <b>The bolt.</b> A loaded crossbow has its bolt lying in the groove with the string cocked behind it: a red core with two black
 * strands winding round it, three vanes at the nock, a barbed head with a red point. A shot empties the groove at once and the
 * string snaps forward and rings. Then the bolt grows back out of the stock (the server's REGROW, whose length is the vanilla
 * charge time; {@link #markRegrow}, which keeps its own start when the server sends the same regrow again): the string draws itself back to the latch first (over a third of the time, the limbs bending
 * with it), then the strands come out of the latch and run forward along the groove with an eroding front, the ribs flaring in a wave
 * that follows it, and the head, barbs and point, pops in last. The truth is the held stack's loaded state ({@link ArmDraw#thrown}: set while the groove is empty), so a viewer who
 * joins late sees it right; the marks only ease the shot and the regrow.
 * <p>
 * <b>Frames.</b> Designed in the crossbow's own frame C: {@code +Y} the way the bolt flies, {@code +X} across the prod, {@code +Z} up, the
 * origin at the middle of the weapon. The vanilla crossbow item has its front toward the sprite's upper left, which is {@code -X}
 * in grip space G, and the prod across G's Y (the same way the bow's G frame is laid), so C is G turned a quarter about Z
 * ({@link #TO_G}); the arm's item path then lays G on the crossbow display block ({@code ArmTransforms.Set.CROSSBOW}), the weapon
 * held flat and pointing along the raised arm exactly as vanilla holds the real item. G's origin is the middle of the sprite.
 * <p>
 * Everything moves: the static crawls from the grip out into the stock and the prod, pulses swell the ribs and the limbs, the braid
 * twists, the nocks beat, the string writhes, drips gather and fall. Low biomass thins it, slackens the string and shortens the limbs;
 * melting droops it. Every timeline is read from {@link ArmDraw}. A level 1 ("Raw") has five ribs, two strands a limb and a plain
 * grip; level 2 ("Honed") seven ribs, three strands, two plates a limb and a trigger guard; level 3 ("Awakened") nine ribs, longer
 * limbs, three plates and two barbs a limb, butt spikes, and a glowing groove, nocks and bolt head.
 * <p>
 * Stateless between calls (the marks of {@link #markShot}, {@link #markRegrow} and {@link #markBurst} are the effect handler's
 * word about what just happened, never the truth); render thread only (shared scratch).
 */
public final class CrossbowGeometry implements FormGeometry {
	/** The feed coordinate at the grip (the top of the fist). */
	private static final float TOOL_PHI = FistGeometry.PHI_TOOL;
	/** Where the pistol grip's axis crosses the stock (y), the butt's end, the riser's centre and the front of the groove. */
	public static final float GRIP_Y = -4.4F, BUTT_Y = -9F, RISER_Y = 5F, MUZZLE_Y = 7F;
	/** The grip's bottom (z), its rake back at the bottom (y), and its radius under the fingers. */
	private static final float GRIP_BOTTOM = -4.9F, GRIP_RAKE = -0.8F, HILT_R = 1.05F;
	/** Heights (z) of the stock's core, the limbs, the string, the rails, the groove and the bolt's axis. */
	private static final float CORE_Z = 0.1F, LIMB_Z = 1.0F, STRING_Z = 1.15F, RAIL_Z = 1.05F, GROOVE_Z = 0.98F, BOLT_Z = 1.35F;
	/** The rails' half-gap (x). */
	private static final float RAIL_X = 0.85F;
	/** The string's middle when cocked (y). Released it sits at the nocks. */
	private static final float LATCH_Y = -3F;
	/** The bolt: its shaft's length (from the string's middle to the head), the head's length, its radius. */
	public static final float SHAFT = 8.2F, HEAD = 2.2F;
	/**
	 * The regrow's stages, as shares of its time: the string draws itself back over the first {@link #COCKED_BY}, the shaft runs forward out of
	 * the latch from {@link #SHAFT_FROM} to {@link #HEAD_AT}, and the head pops in over what is left.
	 */
	private static final float COCKED_BY = 0.35F, SHAFT_FROM = 0.3F, HEAD_AT = 0.92F;
	/** A shot hides the bolt this many ticks even before the stack says it is gone (the stack's own change follows within a tick or two). */
	private static final float SHOT_SETTLE = 3F;
	/** After a regrow's end the weapon keeps showing the bolt this long for the stack's load to arrive, then the stack is believed. */
	private static final float REGROW_HOLD = 40F;
	/** The ribs, by level: how many, from the butt's end to the riser, the first and the last (y). */
	private static final int[] RIBS = {0, 5, 7, 9};
	private static final float RIB_FROM = -8.1F, RIB_TO = 3.8F;
	/** The prod's limb on the +X side: a cubic (x, y per point) from the riser out, sweeping outward and curling forward at its tip. */
	private static final float[] LIMB = {1.2F, 5F, 3.5F, 4.4F, 5.9F, 5.1F, 6.7F, 7.7F};
	private static final float[] TIER_SCALE = {0F, 0.9F, 1F, 1.1F};
	/** Where along the limb the string's nock sits. */
	private static final float NOCK_U = 0.84F;
	private static final float[][] LIMB_K = new float[4][8];
	private static final float[] LIMB_ARC = new float[4], NOCK_X = new float[4], NOCK_Y = new float[4];
	/** The limbs' feed coordinate starts at the riser. */
	private static final float PHI_LIMB0 = TOOL_PHI + (RISER_Y - GRIP_Y);
	/** Plates on a limb's front (u, length px, tilt degrees, width): by level 0, 2, 3 of them. */
	private static final float[] PLATE_U = {0.22F, 0.42F, 0.62F}, PLATE_LEN = {1.6F, 1.4F, 1.1F}, PLATE_TILT = {10F, -12F, 8F}, PLATE_W = {1.1F, 1.0F, 0.85F};
	private static final int[] PLATES = {0, 0, 2, 3};
	/** Barbs on a limb's front at level 3 (u, length, lean degrees). */
	private static final float[] BARB_U = {0.34F, 0.58F}, BARB_LEN = {1.5F, 1.1F}, BARB_LEAN = {42F, 55F};
	/** Strands round a limb's core, by level. */
	private static final int[] STRANDS = {0, 2, 3, 3};
	/** The grip's wrap strands wind this far apart (px a turn). */
	private static final float WRAP_PITCH = 3.2F;

	// static parts, one per piece (each segment of a piece then takes its own offset)
	private static final int PART_CORE = 1, PART_RIB = 4, PART_GRIP = 20, PART_WRAP = 22, PART_RING = 25, PART_GUARD = 27, PART_RAIL = 30, PART_GROOVE = 33,
			PART_RISER = 35, PART_BUTT = 37, PART_SPIKE = 40, PART_BRAID = 44, PART_LCORE = 52, PART_PLATE = 56, PART_BARB = 62, PART_NOCK = 66, PART_STRING = 70,
			PART_BOLT = 76, PART_VANE = 82, PART_HEAD = 88, PART_FLARE = 96, PART_DRIP = 110, PART_BOX = 114;

	private static final Static ST = new Static();
	private static final Matrix4f LOCAL = new Matrix4f();
	/** C into G (a quarter turn about Z), and the same with a spasm's scale. */
	private static final Matrix4f TO_G = new Matrix4f().rotationZ(ArmMath.PI / 2F);
	private static final Matrix4f TO_G_SCALED = new Matrix4f();
	/** First person: the fist's frame (its Y up the pistol grip) in G, right and left. */
	private static final Matrix4f FIST_R = new Matrix4f().translation(-GRIP_Y, 0F, -2.4F).rotateX(ArmMath.PI / 2F);
	private static final Matrix4f FIST_L = new Matrix4f().translation(-GRIP_Y, 0F, -2.4F).rotateX(ArmMath.PI / 2F).scale(-1F, 1F, 1F);
	private static final float[] PL = new float[3 * 8];
	private static final Vector3f DOWN = new Vector3f(), M = new Vector3f(), T = new Vector3f(), P = new Vector3f(), B = new Vector3f(0F, 0F, 1F), Q = new Vector3f(),
			R = new Vector3f(), A0 = new Vector3f(), A1 = new Vector3f(), A2 = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F), UP_X = new Vector3f(1F, 0F, 0F);

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier, lod, plCount, side, strandK, strands, wrapK;
	private static float feedLength, droop, thin, fringeW, flex, cock, growth, shaftReach, headPop, stockLo, stockHi, gripOut, limbReach, stringReach, twang, ringY,
			sY, boltRear, ty0, ty1, swellA, tubeW, gripLen;
	private static boolean dissolve, glowCore;

	private static final Loft.CurveFn POLYLINE = (s, out) -> {
		float f = ArmMath.clamp(s, 0F, 1F) * (plCount - 1);
		int i = Math.min(plCount - 2, (int) f);
		float u = f - i;
		out.set(ArmMath.lerp(PL[3 * i], PL[3 * i + 3], u), ArmMath.lerp(PL[3 * i + 1], PL[3 * i + 4], u), ArmMath.lerp(PL[3 * i + 2], PL[3 * i + 5], u));
	};
	private static final Loft.RadiusFn TUBE_W = s -> FistGeometry.width(ArmMath.lerp(1F, 0.3F, s) * tubeW, 4);
	/** The stretch of the stock the core covers, along y. */
	private static final Loft.CurveFn CORE = (s, out) -> out.set(0F, ArmMath.lerp(ty0, ty1, s), CORE_Z);
	private static final Loft.RadiusFn CORE_W = s -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		return FistGeometry.width(1.4F * thin * (1F + 0.3F * cur.swell(phi(y), feedLength)), 4);
	};
	/** The pistol grip's centreline from the stock's underside down, drawn out to {@link #gripLen}. */
	private static final Loft.CurveFn GRIP = (s, out) -> {
		float k = s * gripLen;
		out.set(0F, GRIP_Y + GRIP_RAKE * k + 0.22F * (float) Math.sin(ArmMath.PI * k), -0.2F - (0.2F - GRIP_BOTTOM) * k);
	};
	private static final Loft.RadiusFn GRIP_W = s -> FistGeometry.width(2.1F * ArmMath.lerp(1F, 1.18F, s) * thin * (1F + 0.3F * cur.swell(TOOL_PHI + 1F, feedLength)), 4);
	/** A wrap strand round the grip: turns about its axis, the two strands the opposite ways so they cross. */
	private static final Loft.CurveFn WRAP = (s, out) -> {
		float k = s * gripLen;
		float z = -0.2F - (0.2F - GRIP_BOTTOM) * k;
		float a = (wrapK == 0 ? 1F : -1F) * ArmMath.TAU * (-z) / WRAP_PITCH + ArmMath.PI * wrapK;
		float wr = 1F + 0.05F * (float) Math.sin(cur.phase() + 5F * s + 0.31F * cur.time() + wrapK * 2F);
		float r = 0.8F * wr;
		out.set(r * (float) Math.cos(a), GRIP_Y + GRIP_RAKE * k + 0.22F * (float) Math.sin(ArmMath.PI * k) + r * (float) Math.sin(a), z);
	};
	private static final Loft.RadiusFn WRAP_W = s -> FistGeometry.width(0.62F * thin * (1F + 0.4F * swellA), 4);
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s;
		float r = 1.3F * (1F + 0.06F * (float) Math.cos(3F * a));
		out.set(r * (float) Math.cos(a), GRIP_Y + GRIP_RAKE + r * (float) Math.sin(a), ringY);
	};
	private static final Loft.RadiusFn RING_W = s -> FistGeometry.width(0.5F, 4);
	/** A rail from its rear to the front, curling outward at the muzzle. */
	private static final Loft.CurveFn RAIL = (s, out) -> {
		float y = ArmMath.lerp(ty0, ty1, s);
		float curl = ArmMath.sm((y - (MUZZLE_Y - 1.3F)) / 1.3F);
		out.set(side * (RAIL_X + 0.7F * curl), y, RAIL_Z + 0.25F * curl);
	};
	private static final Loft.RadiusFn RAIL_W = s -> FistGeometry.width(ArmMath.lerp(0.65F, 0.35F, s * s), 4);
	private static final Loft.CurveFn GROOVE = (s, out) -> out.set(0F, ArmMath.lerp(ty0, ty1, s), GROOVE_Z);
	private static final Loft.RadiusFn GROOVE_W = s -> FistGeometry.width(0.5F * (1F + 0.5F * swellA), 4);
	/** A limb's core: the centreline itself. */
	private static final Loft.CurveFn LIMB_CORE = (s, out) -> {
		limbFrame(s * limbReach, side);
		out.set(M);
	};
	private static final Loft.RadiusFn LIMB_CORE_W = s -> FistGeometry.width(0.55F * thin, 4);
	/** A braid strand along a limb: the centreline plus its own turn about it. */
	private static final Loft.CurveFn BRAID = (s, out) -> {
		float u = s * limbReach;
		limbFrame(u, side);
		float arc = u * LIMB_ARC[tier];
		float psi = ArmMath.TAU * strandK / strands + ArmMath.TAU * arc / 4.2F + 0.03F * cur.time() * side;
		float r = (0.5F + 0.12F * (float) Math.sin(ArmMath.PI * arc / 2.1F + ArmMath.TAU * strandK / strands)) * (1F - 0.4F * u);
		out.set(M).fma(r * (float) Math.cos(psi), P).fma(r * (float) Math.sin(psi), B);
		float wn = ArmMath.writheN(s, cur.phase() + strandK + side, cur.time()), wb = ArmMath.writheB(s, cur.phase() + strandK, cur.time());
		out.fma(wn * (float) Math.sin(ArmMath.PI * s), P).fma(wb * (float) Math.sin(ArmMath.PI * s), B);
	};
	private static final Loft.RadiusFn BRAID_W = s -> {
		float u = s * limbReach;
		float w = ArmMath.lerp(0.8F, 0.46F, u) * thin * (1F + cur.swell(limbPhi(u), feedLength));
		if (cur.frontBulb() && limbReach < 1F) w *= 1F + 0.3F * Math.max(0F, 1F - (limbReach - u) * LIMB_ARC[tier] / 3F);
		return FistGeometry.width(w, 4);
	};
	private static final Loft.AlphaFn BRAID_ALPHA = s -> {
		float u = s * limbReach;
		return dissolve && limbReach < 1F ? ArmMath.fringe((limbReach - u) * LIMB_ARC[tier], fringeW) : 1F;
	};
	/** The string from a nock to its middle: taut when cocked, writhing and ringing when it has just been loosed. */
	private static final Loft.CurveFn STRING = (s, out) -> {
		float t = s * stringReach;
		float x = ArmMath.lerp(Q.x, R.x, t), y = ArmMath.lerp(Q.y, R.y, t);
		float pin = (float) Math.sin(ArmMath.PI * s);
		float env = pin * (0.14F * (1F - 0.75F * cock) + 0.2F * (1F - thin) * 3F);
		float wa = env * (float) Math.sin(cur.phase() + 7F * t + 0.35F * cur.time() + side), wb = 0.6F * env * (float) Math.cos(0.8F * cur.phase() + 5F * t + 0.28F * cur.time());
		float ring = twang * pin * (float) Math.sin(2.8F * cur.time() - 2F * t);
		out.set(x + wa + 0.3F * ring, y + wb * 0.5F, ArmMath.lerp(Q.z, R.z, t) + ring);
	};
	private static final Loft.RadiusFn STRING_W = s -> FistGeometry.width(0.3F * (1F + 0.5F * swellA), 4);
	/** The bolt's red core along the groove, rear to its growth front. */
	private static final Loft.CurveFn BOLT_CORE = (s, out) -> out.set(0F, boltRear + SHAFT * shaftReach * s, BOLT_Z);
	/** A bolt strand winding round the core: two turns and a half over the whole shaft, the front end wound tighter as it grows. */
	private static final Loft.CurveFn BOLT_STRAND = (s, out) -> {
		float ang = ArmMath.TAU * 2.5F * s * shaftReach + strandK * ArmMath.PI + 0.15F * cur.time();
		float r = 0.52F + 0.06F * (float) Math.sin(cur.phase() + 5F * s + 0.3F * cur.time() + strandK);
		out.set(r * (float) Math.cos(ang), boltRear + SHAFT * shaftReach * s, BOLT_Z + r * (float) Math.sin(ang));
	};
	private static final Loft.AlphaFn BOLT_ALPHA = s -> dissolve && shaftReach < 1F ? ArmMath.fringe(SHAFT * shaftReach * (1F - s) + 0.2F, 1.6F) : 1F;

	static {
		for (int t = 1; t <= 3; t++) prepare(t);
	}

	public CrossbowGeometry() {}

	@Override
	public String form() {
		return ArmProtocol.CROSSBOW;
	}

	@Override
	public float feedLength(int tier) {
		return PHI_LIMB0 + LIMB_ARC[Math.clamp(tier, 1, 3)];
	}

	/** The GUI fit: the weapon is about 18 px by 14, centred on the origin, so a little over half scale puts it in the slot. */
	@Override
	public float guiScale() {
		return 0.58F;
	}

	@Override
	public float guiGripPx() {
		return 8F;
	}

	@Override
	public boolean bowTransforms() {
		return false;
	}

	/** One limb's arc length at {@code tier}, px, as drawn at rest. */
	public static float limbArc(int tier) {
		return LIMB_ARC[Math.clamp(tier, 1, 3)];
	}

	/** The feed coordinate at height {@code y} along the stock: out from the grip. */
	static float phi(float y) {
		return TOOL_PHI + Math.abs(y - GRIP_Y);
	}

	private static float limbPhi(float u) {
		return PHI_LIMB0 + u * LIMB_ARC[tier];
	}

	// ------------------------------------------------------------------ what just happened (the effect handler's marks)

	private static final int MARKS = 16;
	private static final int[] MARK_ID = new int[MARKS];
	private static final boolean[] MARK_USED = new boolean[MARKS], SHOT_SET = new boolean[MARKS], REGROW_SET = new boolean[MARKS], BURST_SET = new boolean[MARKS];
	private static final float[] SHOT_AT = new float[MARKS], REGROW_AT = new float[MARKS], REGROW_DUR = new float[MARKS], BURST_AT = new float[MARKS],
			BURST_GAP = new float[MARKS];
	/** How many spines the burst fires (1 to {@link #MAX_FLARES}): the muzzle flashes once for each, and the burst lasts until the last has left. */
	private static final int[] BURST_N = new int[MARKS];
	/** The most muzzle flares drawn: PART_FLARE's slots in the texture layout (CrossbowFx.MAX_SPINES is the same 12). */
	static final int MAX_FLARES = 12;
	/** The seed of the regrow each owner's mark is for: a message with the same seed is the same regrow and keeps the mark's own start. */
	private static final int[] REGROW_SEED = new int[MARKS];
	private static int markNext;
	/**
	 * A mark this many ticks in the future is the clock's wrap or a mistake (a mark made this very tick can read a fraction before the frame's
	 * time); one older than its effect can last is stale. Both fall back to the stack.
	 */
	private static final float FUTURE_SLACK = 1F;
	/** A shot's mark is kept this long (ticks): the string's ring and the trigger's kick are over well before; a longer-lived mark is stale. */
	private static final float SHOT_KEPT = 1200F;

	private static int find(int owner) {
		for (int i = 0; i < MARKS; i++) {
			if (MARK_USED[i] && MARK_ID[i] == owner) return i;
		}
		return -1;
	}

	private static int slot(int owner) {
		int i = find(owner);
		if (i >= 0) return i;
		for (int k = 0; k < MARKS && i < 0; k++) {
			if (!MARK_USED[k]) i = k;
		}
		if (i < 0) {
			i = markNext;
			markNext = (markNext + 1) % MARKS;
		}
		MARK_USED[i] = true;
		MARK_ID[i] = owner;
		SHOT_SET[i] = false;
		REGROW_SET[i] = false;
		BURST_SET[i] = false;
		REGROW_SEED[i] = 0;
		return i;
	}

	/** {@code owner}'s crossbow was just shot at {@code now} (the arm clock): the groove empties at once, the string snaps forward and rings, any regrow stops. */
	public static void markShot(int owner, float now) {
		int i = slot(owner);
		SHOT_SET[i] = true;
		SHOT_AT[i] = now;
		REGROW_SET[i] = false;
	}

	/**
	 * {@code owner}'s bolt is regrowing (the server's REGROW): it takes {@code total} ticks in all and {@code left} of them are left at
	 * {@code now}, so it began {@code total - left} ticks ago. {@code seed} is the regrow's: the server sends the message again every 10 ticks, and
	 * a message for the regrow the mark already has (same seed) changes nothing, so the growth never restarts; a new seed (the next regrow, or
	 * the first message a viewer who just came into range gets) sets the mark, and a late joiner's growth starts where the server's is.
	 */
	public static void markRegrow(int owner, float now, int total, int left, int seed) {
		int i = slot(owner);
		if (REGROW_SET[i] && REGROW_SEED[i] == seed) return;
		float dur = Math.max(1, total);
		REGROW_SET[i] = true;
		REGROW_SEED[i] = seed;
		REGROW_DUR[i] = dur;
		REGROW_AT[i] = now - (dur - ArmMath.clamp(left, 0, dur));
	}

	/**
	 * {@code owner} fired the Spine Burst at {@code now}: {@code count} spines (the spines setting, up to {@link #MAX_FLARES}), one every
	 * {@code gap} ticks, each a flare at the muzzle. The burst takes
	 * the bolt, so any regrow's hold of a whole bolt (it was only waiting for the stack's load) ends: the groove is empty from here.
	 */
	public static void markBurst(int owner, float now, int gap, int count) {
		int i = slot(owner);
		BURST_SET[i] = true;
		BURST_AT[i] = now;
		BURST_GAP[i] = Math.max(1, gap);
		BURST_N[i] = Math.clamp(count, 1, MAX_FLARES);
		REGROW_SET[i] = false;
	}

	/** Forgets {@code owner}'s marks only (the arm dump draws one pose per regrow stage and must not wipe the live viewers' marks). */
	public static void clearMark(int owner) {
		int i = find(owner);
		if (i < 0) return;
		MARK_USED[i] = false;
		SHOT_SET[i] = false;
		REGROW_SET[i] = false;
		BURST_SET[i] = false;
	}

	/** Forgets every mark (joining or leaving a world). */
	public static void clearMarks() {
		java.util.Arrays.fill(MARK_USED, false);
		java.util.Arrays.fill(SHOT_SET, false);
		java.util.Arrays.fill(REGROW_SET, false);
		java.util.Arrays.fill(BURST_SET, false);
		markNext = 0;
	}

	/**
	 * How much of the bolt is out in {@code d}, 0 (the groove is empty) to 1 (loaded), as the owner's marks and the stack say. The
	 * regrow, while one runs, wins over the stack (the server may load the stack at its start or at its end); a shot empties the groove
	 * for {@link #SHOT_SETTLE} ticks whatever the stack still says; with neither, the stack ({@link ArmDraw#thrown} is "the groove
	 * is empty") is the truth, and an icon or a preview, which have no stack, show it loaded.
	 */
	public static float growth(ArmDraw d) {
		boolean empty = d.thrown();
		int i = find(d.seed());
		if (i >= 0) {
			float now = d.time();
			// a mark whose age is negative or longer than its effect can last is stale (the clock wraps: WhipGeometry.ageOf reads a mark older than
			// half a wrap as one from the future, and one older than a whole wrap as brand new): it says nothing, the stack is believed, and the
			// mark is dropped, so a crossbow viewed for half an hour after a shot is not drawn empty for good, nor a stale regrow brought back an hour on
			if (REGROW_SET[i]) {
				float age = WhipGeometry.ageOf(now, REGROW_AT[i]);
				if (age >= -FUTURE_SLACK) {
					age = Math.max(0F, age);
					if (age < REGROW_DUR[i]) return ArmMath.clamp01(age / REGROW_DUR[i]);
					if (age < REGROW_DUR[i] + REGROW_HOLD) return 1F;
				}
				REGROW_SET[i] = false;
			} else if (SHOT_SET[i]) {
				float age = WhipGeometry.ageOf(now, SHOT_AT[i]);
				if (age >= -FUTURE_SLACK && age < SHOT_SETTLE) return 0F;
				if (!(age >= -FUTURE_SLACK && age < SHOT_KEPT)) SHOT_SET[i] = false;
			}
		}
		return empty ? 0F : 1F;
	}

	/** Ticks since {@code d}'s owner's last shot, or {@link ArmDraw#NEVER}. */
	static float shotAge(ArmDraw d) {
		int i = find(d.seed());
		if (i < 0 || !SHOT_SET[i]) return ArmDraw.NEVER;
		float a = WhipGeometry.ageOf(d.time(), SHOT_AT[i]);
		if (!(a >= -FUTURE_SLACK && a < SHOT_KEPT)) {
			SHOT_SET[i] = false; // stale: not a shot to ring for
			return ArmDraw.NEVER;
		}
		return a < 0F ? ArmDraw.NEVER : a;
	}

	/** Ticks since the burst's start, its gap and its spine count, into {@code out[0..2]}; false with none running. */
	private static boolean burst(ArmDraw d, float[] out) {
		int i = find(d.seed());
		if (i < 0 || !BURST_SET[i]) return false;
		float a = WhipGeometry.ageOf(d.time(), BURST_AT[i]);
		// over once the last spine's flash (5 ticks) is done, a tick's slack on top
		float end = (BURST_N[i] - 1) * BURST_GAP[i] + 6F;
		if (a < -FUTURE_SLACK || a > end) BURST_SET[i] = false; // over, or stale: dropped
		if (a < 0F || a > end) return false;
		out[0] = a;
		out[1] = BURST_GAP[i];
		out[2] = BURST_N[i];
		return true;
	}

	private static final float[] BURST = new float[3];

	// ------------------------------------------------------------------ the limbs

	/** Level {@code t}'s limb scaled about its base, and its arc length and nock. */
	private static void prepare(int t) {
		float k = TIER_SCALE[t];
		for (int i = 0; i < 4; i++) {
			LIMB_K[t][2 * i] = LIMB[0] + (LIMB[2 * i] - LIMB[0]) * k;
			LIMB_K[t][2 * i + 1] = LIMB[1] + (LIMB[2 * i + 1] - LIMB[1]) * k;
		}
		float arc = 0F, px = LIMB_K[t][0], py = LIMB_K[t][1];
		for (int i = 1; i <= 64; i++) {
			float x = bez(t, 0, i / 64F), y = bez(t, 1, i / 64F);
			arc += (float) Math.hypot(x - px, y - py);
			px = x;
			py = y;
		}
		LIMB_ARC[t] = arc;
		NOCK_X[t] = bez(t, 0, NOCK_U);
		NOCK_Y[t] = bez(t, 1, NOCK_U);
	}

	private static float bez(int t, int axis, float u) {
		float[] k = LIMB_K[t];
		float w = 1F - u;
		return w * w * w * k[axis] + 3F * w * w * u * k[2 + axis] + 3F * w * u * u * k[4 + axis] + u * u * u * k[6 + axis];
	}

	private static float bezD(int t, int axis, float u) {
		float[] k = LIMB_K[t];
		float w = 1F - u;
		return 3F * w * w * (k[2 + axis] - k[axis]) + 6F * w * u * (k[4 + axis] - k[2 + axis]) + 3F * u * u * (k[6 + axis] - k[4 + axis]);
	}

	/**
	 * Limb {@code sd} (+1 the +X one, -1 the other) at {@code u}: its centreline into M (flexed back and in by the cocking, drooping
	 * when melting), its tangent T toward the tip, P (toward the limb's front, the side that faces the target, +Y) and B (up).
	 */
	private static void limbFrame(float u, int sd) {
		float x = bez(tier, 0, u), y = bez(tier, 1, u);
		float dx = bezD(tier, 0, u), dy = bezD(tier, 1, u);
		// cocking bends the tips toward the archer: back 1.8 px and in 0.5 px, growing with u squared
		x -= 0.5F * flex * u * u;
		y -= 1.8F * flex * u * u;
		dx -= 1F * flex * u;
		dy -= 3.6F * flex * u;
		float l = (float) Math.hypot(dx, dy);
		if (l < 1e-6F) {
			dx = 1F;
			dy = 0F;
			l = 1F;
		}
		dx /= l;
		dy /= l;
		T.set(sd * dx, dy, 0F);
		P.set(-sd * dy, dx, 0F);
		M.set(sd * x, y, LIMB_Z);
		if (droop > 0F) {
			float d = (PHI_LIMB0 - TOOL_PHI + u * LIMB_ARC[tier]) / (PHI_LIMB0 - TOOL_PHI + LIMB_ARC[tier]);
			M.fma(droop * d * d, DOWN);
		}
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (d == null || layer == ArmLayer.SENSE) return;
		cur = d;
		tier = Math.clamp(d.tier(), 1, 3);
		lod = Math.clamp(d.lod(), 0, 3);
		feedLength = feedLength(tier);
		ST.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		d.down(DOWN);
		// the geometry is built in C; the world's down in C is G's turned back the quarter
		float dx = DOWN.x;
		DOWN.x = DOWN.y;
		DOWN.y = -dx;
		if (d.firstPerson()) {
			// the fist closes on the pistol grip, whose axis is G's Z (FIRST_PERSON_ARM is off, so these draw nothing today)
			m.transform(d.leftHand() ? FIST_L : FIST_R);
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, HILT_R, feedLength);
			m.transform(null);
		}
		FistGeometry.knot(d, layer, m, ST);
		float sp = d.spasm();
		m.transform(sp != 1F ? TO_G_SCALED.scaling(sp).rotateZ(ArmMath.PI / 2F) : TO_G);
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	/** FORM's pop for something appearing {@code x} ticks after its moment: 0 to 1.25 to 1.0 over 2 ticks. */
	private static float pop(float x) {
		if (x <= 0F) return 0F;
		if (x < 1F) return 1.25F * ArmMath.sm(x);
		if (x < 2F) return ArmMath.lerp(1.25F, 1F, ArmMath.sm(x - 1F));
		return 1F;
	}

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float reach = d.reach();
		boolean detailed = d.detailedForm();
		float ft = d.formTick();
		float rt = d.retractTick();
		droop = d.droop();
		dissolve = d.dissolve();
		fringeW = d.fringe();
		int stage = d.lowStage();
		boolean failing = stage == ArmDraw.LOW_FAILING;
		thin = stage >= ArmDraw.LOW_THINNING ? 0.85F : 1F;
		glowCore = tier >= 3 && d.glow();
		// the plan: how much of each piece is out
		float gripPlan, stockPlan, railPlan, riserPlan, limbPlan, nockPlan, stringPlan, boltPlan;
		if (detailed) {
			gripPlan = ArmMath.sm((ft - 1F) / 2.5F);
			stockPlan = ArmMath.sm((ft - 1.5F) / 5F);
			railPlan = ArmMath.sm((ft - 5F) / 3F);
			riserPlan = pop(ft - 6.4F);
			limbPlan = ArmMath.sm((ft - 6.5F) / 5F);
			nockPlan = pop(ft - 11F);
			stringPlan = ArmMath.sm((ft - 11F) / 2F);
			boltPlan = ArmMath.sm((ft - 11.5F) / 2.5F);
		} else {
			gripPlan = ArmMath.clamp01(reach / 0.16F);
			stockPlan = ArmMath.sm((reach - 0.06F) / 0.34F);
			railPlan = ArmMath.sm((reach - 0.34F) / 0.2F);
			riserPlan = ArmMath.sm((reach - 0.42F) / 0.12F);
			limbPlan = ArmMath.sm((reach - 0.46F) / 0.4F);
			nockPlan = ArmMath.sm((reach - 0.82F) / 0.08F);
			stringPlan = ArmMath.sm((reach - 0.84F) / 0.12F);
			boltPlan = ArmMath.sm((reach - 0.9F) / 0.1F);
			if (rt >= 0F) stringPlan = Math.min(stringPlan, 1F - ArmMath.sm((rt - 1F) / 2F));
		}
		// the bolt: the owner's marks and the stack, cut by the plan while the weapon forms
		growth = Math.min(growth(d), boltPlan);
		float sinceShot = shotAge(d);
		// the string follows the bolt (the weapon cocks itself as the bolt grows) and rings after a shot
		float slack = stage >= ArmDraw.LOW_STARVING ? 0.85F : 1F;
		cock = ArmMath.sm(ArmMath.clamp01(growth / COCKED_BY)) * slack;
		twang = sinceShot < 10F ? 0.9F * (float) Math.exp(-0.4F * sinceShot) * (1F - ArmMath.sm((sinceShot) / 10F)) : 0F;
		// the limbs flex with the cocking, a hair of breath, and the burst's kick
		float sp = d.specialWeight(ArmProtocol.FX_CROSSBOW);
		flex = cock + 0.08F * (float) Math.sin(0.05F * d.time() + d.phase()) - 0.25F * Math.max(0F, twang);
		shaftReach = ArmMath.sm(ArmMath.clamp01((growth - SHAFT_FROM) / (HEAD_AT - SHAFT_FROM)));
		headPop = growth >= 0.999F ? 1F : pop((growth - HEAD_AT) / (1F - HEAD_AT) * 2F);
		sY = ArmMath.lerp(nockYAt() + 0.3F, LATCH_Y, cock);
		boltRear = sY + 0.25F;
		limbReach = failing ? Math.min(limbPlan, 0.92F) : limbPlan;
		stringReach = stringPlan;
		float stockUp = stockPlan;
		stockHi = ArmMath.lerp(GRIP_Y, RISER_Y - 0.6F, stockUp);
		stockLo = ArmMath.lerp(GRIP_Y, BUTT_Y + 0.2F, ArmMath.clamp01(stockUp * 1.6F));
		gripOut = gripPlan;
		if (lod >= ArmDraw.LOD_FAR) {
			far(d, layer, m, riserPlan);
			return;
		}
		if (lod >= 2) {
			boxes(d, layer, m, riserPlan);
			return;
		}
		grip(d, layer, m, detailed, ft);
		stock(d, layer, m, detailed, ft, sp);
		rails(d, layer, m, railPlan);
		riser(d, layer, m, riserPlan);
		limbs(d, layer, m, detailed, ft);
		nocks(d, layer, m, nockPlan);
		string(d, layer, m);
		bolt(d, layer, m);
		flares(d, layer, m);
		beads(d, layer, m, rt, failing);
		if (lod == 0 && d.dripsNow() && (!detailed || ft >= 13F)) drips(d, layer, m);
	}

	/** The nock's y at rest (the string's released middle), from the tier's limb. */
	private static float nockYAt() {
		return NOCK_Y[tier];
	}

	// ------------------------------------------------------------------ the grip

	/**
	 * The pistol grip: a black core in two crossing wrap strands (the fist and the sheath's claw close on it), a red pommel ring, and
	 * at level 2 and 3 a trigger guard sweeping forward to the stock. A red trigger stands in the guard and is pulled back by a shot.
	 */
	private static void grip(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (gripOut <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		gripLen = gripOut;
		swellA = d.swell(TOOL_PHI + 1F, feedLength);
		if (lod >= 1) {
			if (layer == black) {
				float z0 = -0.2F, z1 = z0 + (GRIP_BOTTOM - z0) * gripLen;
				m.box(null, -0.9F, GRIP_Y + GRIP_RAKE * gripLen - 0.9F, z1, 1.8F, 1.8F - GRIP_RAKE * gripLen * 0.6F, z0 - z1, ST.u0(PART_GRIP, 4, TOOL_PHI), ST.v0(PART_GRIP, 4, TOOL_PHI),
						1F, 1F, m.tint());
			}
			return;
		}
		Loft.tube(m, GRIP, 0F, 1F, 6, 4, GRIP_W, black, layer, ST, PART_GRIP, 1.5F, TOOL_PHI - 1F, TOOL_PHI + 1F, UP_Y, 1F);
		float wrapReach = detailed ? ArmMath.sm((ft - 2F) / 3F) : 1F;
		if (layer == black && wrapReach > 0.02F) {
			float saved = gripLen;
			gripLen = saved * wrapReach;
			for (int k = 0; k < 2; k++) {
				wrapK = k;
				Loft.tube(m, WRAP, 0F, 1F, 8, 4, WRAP_W, black, layer, ST, PART_WRAP + k, 2F, TOOL_PHI - 1.5F, TOOL_PHI + 1.5F, UP_Y, 1F);
			}
			gripLen = saved;
		}
		// the pommel ring and the guard come once the grip has reached its bottom
		if (gripOut > 0.96F) {
			ringY = GRIP_BOTTOM;
			float rs = detailed ? pop(ft - 4F) : 1F;
			if (rs > 0.02F) {
				Loft.closedTube(m, RING, 8, 4, RING_W, red, layer, ST, PART_RING, 2F, TOOL_PHI - 1F, UP_Z, 1F);
			}
			if (tier >= 2) {
				// the guard: a black tendril from the grip's heel forward and up to the stock's underside
				A0.set(0F, GRIP_Y + GRIP_RAKE * 0.8F, GRIP_BOTTOM + 0.9F);
				A1.set(0F, GRIP_Y + 3.4F, GRIP_BOTTOM + 0.2F);
				A2.set(0F, GRIP_Y + 3.1F, -0.7F);
				Loft.CurveFn guard = (s, out) -> ArmMath.bezier(A0, A1, A2, s, out);
				float gs = detailed ? ArmMath.sm((ft - 5F) / 3F) : 1F;
				if (gs > 0.02F) {
					Loft.tube(m, guard, 0F, gs, 6, 4, s -> FistGeometry.width(ArmMath.lerp(0.75F, 0.5F, s) * thin, 4), black, layer, ST, PART_GUARD, 1.5F, TOOL_PHI, TOOL_PHI + 4F,
							UP_X, 1F);
				}
			}
		}
		// the trigger: a red barb in the guard, pulled back by a shot (the click is fast: it springs forward again in 6 ticks)
		if (gripOut > 0.6F && (layer == red || Loft.pulseGlowPass(layer))) {
			float pull = triggerPull(d);
			LOCAL.translation(0F, GRIP_Y + 1.1F - 0.5F * pull, -1.6F).rotateTowards(0F, 0.55F - 0.15F * pull, -1F, 1F, 0F, 0F);
			m.box(LOCAL, -0.3F, -0.25F, 0F, 0.6F, 0.5F, 1.9F, ST.u0(PART_GUARD, 5, TOOL_PHI), ST.v0(PART_GUARD, 5, TOOL_PHI), 1.5F, 1F, m.tint());
		}
	}

	/** The trigger's pull, 0..1: full at the shot, back by tick 6. */
	private static float triggerPull(ArmDraw d) {
		float a = shotAge(d);
		return a < 6F ? 1F - ArmMath.sm(a / 6F) : 0F;
	}

	// ------------------------------------------------------------------ the stock

	/**
	 * The stock: a red core, the black ribs round it (the static shows in the gaps; a rib swells with a pulse passing, and a wave of
	 * them flares along the stock as the bolt regrows or a special runs), a butt pad with a red slit and, at level 3, two spikes.
	 */
	private static void stock(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, float sp) {
		if (stockHi - stockLo < 0.2F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		ArmLayer coreLayer = glowCore ? ArmLayer.GLOW : red;
		ty0 = stockLo;
		ty1 = stockHi;
		Loft.tube(m, CORE, 0F, 1F, lod == 0 ? 7 : 4, 4, CORE_W, coreLayer, layer, ST, PART_CORE, 1.5F, phi(stockLo), phi(stockHi), UP_Z, 1F);
		int n = RIBS[tier];
		float time = d.time();
		// the wave: where the bolt's growth front is along the stock (it runs from the latch forward while a regrow runs)
		boolean regrowing = growth > 0.01F && growth < 0.999F;
		float front = boltRear + SHAFT * shaftReach;
		for (int k = 0; k < n; k++) {
			if (lod == 1 && (k & 1) == 1) continue;
			float t = n == 1 ? 0F : k / (float) (n - 1);
			float y = ArmMath.lerp(RIB_FROM, RIB_TO, t);
			float gate;
			if (y < GRIP_Y) gate = ArmMath.clamp01((y - stockLo) / 1.2F);
			else gate = ArmMath.clamp01((stockHi - y) / 1.2F);
			if (detailed) gate = Math.min(gate, pop(ft - 3.4F - 0.28F * Math.abs(y - GRIP_Y)));
			if (gate <= 0.02F) continue;
			float amp = d.swell(phi(y), feedLength);
			float flare = regrowing || sp > 0.02F ? 0.25F * Math.max(sp, regrowing ? 1F : 0F) * ArmMath.gauss(y - front, 1.6F) : 0F;
			float k2 = 1F + 0.3F * amp + flare;
			float hw = (1.15F + 0.35F * (1F - t)) * thin * gate, th = (0.5F + 0.12F * FistGeometry.hash(k * 7 + d.seed() % 13)) * gate;
			float breathe = 1F + 0.03F * (float) Math.sin(0.05F * time + 1.7F * k);
			if (layer == black) {
				LOCAL.translation(0F, y, 0.05F).scale(k2 * breathe, 1F, k2 * breathe);
				m.box(LOCAL, -hw, -th / 2F, -0.95F, 2F * hw, th, 1.9F, ST.u0(PART_RIB + (k & 7), k >> 3, phi(y)), ST.v0(PART_RIB + (k & 7), k >> 3, phi(y)), 1.5F, 1F, m.tint());
			} else if ((layer == red || Loft.pulseGlowPass(layer)) && lod == 0 && tier >= 2 && (k & 1) == 0) {
				// a red ridge along the rib's top
				LOCAL.translation(0F, y, 0.05F).scale(k2 * breathe, 1F, k2 * breathe);
				m.box(LOCAL, -hw * 0.55F, -th * 0.32F, 0.9F, 2F * hw * 0.55F, th * 0.64F, 0.3F, ST.u0(PART_RIB + 8, k, phi(y)), ST.v0(PART_RIB + 8, k, phi(y)), 2F, 1F, m.tint());
			}
		}
		// the butt: a pad with a red slit, spikes at level 3
		if (stockLo < BUTT_Y + 0.5F) {
			float s = detailed ? pop(ft - 4.5F) : ArmMath.sm((BUTT_Y + 0.7F - stockLo) / -0.6F + 1F);
			s = Math.min(1F, s);
			if (s > 0.02F) {
				float sw = 1F + 0.3F * d.swell(phi(BUTT_Y), feedLength);
				if (layer == black) {
					LOCAL.translation(0F, BUTT_Y + 0.4F, 0F).scale(s * sw);
					m.box(LOCAL, -1.7F, -0.55F, -1.2F, 3.4F, 1.1F, 2.4F, ST.u0(PART_BUTT, 0, phi(BUTT_Y)), ST.v0(PART_BUTT, 0, phi(BUTT_Y)), 1.5F, 1F, m.tint());
					if (tier >= 3 && lod == 0) {
						for (int k = 0; k < 2; k++) {
							float sd = k == 0 ? 1F : -1F;
							LOCAL.translation(sd * 1.0F, BUTT_Y + 0.4F, 0.4F).rotateTowards(sd * 0.35F, -1F, 0.3F, 0F, 0F, 1F).scale(s);
							m.frustum(LOCAL, 2.4F, 0.9F, 0.7F, 0.08F, 0.08F, ST.u0(PART_SPIKE, k, phi(BUTT_Y)), ST.v0(PART_SPIKE, k, phi(BUTT_Y)), 2F, 1F, 1F, m.tint());
						}
					}
				} else if ((layer == red || Loft.pulseGlowPass(layer)) && lod == 0) {
					LOCAL.translation(0F, BUTT_Y - 0.12F, 0F).scale(s * sw);
					m.box(LOCAL, -0.25F, -0.1F, -0.8F, 0.5F, 0.2F, 1.6F, ST.u0(PART_BUTT, 1, phi(BUTT_Y)), ST.v0(PART_BUTT, 1, phi(BUTT_Y)), 2F, 1F, m.tint());
				}
			}
		}
	}

	// ------------------------------------------------------------------ the rails and the groove

	/** Two black rails along the top, curling outward at the muzzle, and the red flight groove between them (it glows at level 3). */
	private static void rails(ArmDraw d, ArmLayer layer, SkinMesh m, float plan) {
		if (plan <= 0.02F || lod >= 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float y0 = -5.2F, y1 = ArmMath.lerp(y0, MUZZLE_Y, plan);
		ty0 = y0;
		ty1 = y1;
		if (layer == black) {
			int n = Math.max(3, Math.round((lod == 0 ? 8 : 4) * plan));
			// ty0 and ty1 are y0 and y1 (set above), read from the fields so the lambda captures nothing and costs no allocation per emit
			Loft.AlphaFn fringe = dissolve && plan < 1F ? s -> ArmMath.fringe((ty1 - ArmMath.lerp(ty0, ty1, s)), fringeW) : null;
			for (int sd = 1; sd >= -1; sd -= 2) {
				side = sd;
				Loft.tube(m, RAIL, 0F, 1F, n, 4, RAIL_W, black, layer, ST, PART_RAIL + (sd > 0 ? 0 : 1), 1.5F, phi(y0), phi(y1), UP_Z, fringe);
			}
		}
		ArmLayer grooveLayer = glowCore ? ArmLayer.GLOW : red;
		swellA = d.swell(phi(0F), feedLength);
		Loft.tube(m, GROOVE, 0F, 1F, lod == 0 ? 6 : 3, 4, GROOVE_W, grooveLayer, layer, ST, PART_GROOVE, 1.5F, phi(y0), phi(y1), UP_Z, 1F);
	}

	// ------------------------------------------------------------------ the riser

	/** The riser knot at the front of the stock, where the limbs leave it: a black block with a red heart that beats. */
	private static void riser(ArmDraw d, ArmLayer layer, SkinMesh m, float plan) {
		if (plan <= 0.02F || lod >= 2) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float s = plan * (1F + 0.2F * d.swell(PHI_LIMB0, feedLength) + d.knotBeat());
		if (layer == black) {
			LOCAL.translation(0F, RISER_Y, 0.55F).scale(s);
			m.box(LOCAL, -1.7F, -1.0F, -1.0F, 3.4F, 2F, 2.2F, ST.u0(PART_RISER, 0, PHI_LIMB0), ST.v0(PART_RISER, 0, PHI_LIMB0), 1.5F, 1F, m.tint());
		} else if ((layer == red || Loft.pulseGlowPass(layer)) && lod == 0) {
			LOCAL.translation(0F, RISER_Y + 1.0F, 0.55F).scale(s);
			m.box(LOCAL, -0.7F, -0.2F, -0.5F, 1.4F, 0.4F, 1F, ST.u0(PART_RISER, 1, PHI_LIMB0), ST.v0(PART_RISER, 1, PHI_LIMB0), 2F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ the prod

	/** Both limbs: a red core in braided black strands (two at level 1, three after), plates on the front, barbs at level 3. */
	private static void limbs(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft) {
		if (limbReach <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		ArmLayer coreLayer = glowCore ? ArmLayer.GLOW : red;
		for (int sd = 1; sd >= -1; sd -= 2) {
			side = sd;
			if (lod >= 1) {
				// one tube per limb
				if (layer == black) {
					Loft.tube(m, LIMB_CORE, 0F, 1F, 5, 4, s -> FistGeometry.width(ArmMath.lerp(1.2F, 0.5F, s) * thin, 4), black, layer, ST, PART_BRAID + (sd > 0 ? 0 : 3), 1.5F,
							PHI_LIMB0, limbPhi(limbReach), UP_Z, 1F);
				}
				continue;
			}
			Loft.tube(m, LIMB_CORE, 0F, 1F, 5, 4, LIMB_CORE_W, coreLayer, layer, ST, PART_LCORE + (sd > 0 ? 0 : 1), 1.5F, PHI_LIMB0, limbPhi(limbReach), UP_Z, 1F);
			strands = STRANDS[tier];
			int stations = 10;
			for (int k = 0; k < strands; k++) {
				strandK = k;
				if (layer == black) {
					Loft.tube(m, BRAID, 0F, 1F, Math.max(3, Math.round(stations * limbReach)), 4, BRAID_W, black, layer, ST, PART_BRAID + (sd > 0 ? 0 : 3) + k, 1.5F, PHI_LIMB0,
							limbPhi(limbReach), UP_Z, BRAID_ALPHA);
				}
			}
			plates(d, layer, m, detailed, ft, sd);
			limbBarbs(d, layer, m, detailed, ft, sd);
		}
	}

	/** Chitin plates along a limb's front: boxes with their own length, tilt and width, breathing a little and swelling with a pulse. */
	private static void plates(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, int sd) {
		int count = PLATES[tier];
		if (count == 0 || layer != d.paint(ArmLayer.BLACK)) return;
		float time = d.time();
		for (int j = 0; j < count; j++) {
			float u = PLATE_U[j];
			if (u > limbReach - 0.04F) continue;
			float grow = detailed ? pop(ft - 8.5F - 0.5F * j) : ArmMath.sm((limbReach - u - 0.04F) / 0.12F);
			if (grow <= 0.02F) continue;
			limbFrame(u, sd);
			float amp = d.swell(limbPhi(u), feedLength);
			float breath = 1F + 0.03F * (float) Math.sin(0.05F * time + 1.7F * j + sd) + 0.3F * amp;
			float len = PLATE_LEN[j] * breath * grow * thin, wd = PLATE_W[j] * grow * thin;
			float tilt = (float) Math.toRadians(PLATE_TILT[j]) * sd;
			float ang = (float) Math.atan2(T.y, T.x) - ArmMath.PI / 2F + tilt;
			float off = 0.45F * grow;
			LOCAL.translation(M.x + P.x * off, M.y + P.y * off, M.z).rotateZ(ang);
			m.box(LOCAL, -len / 2F, -0.28F, -wd / 2F, len, 0.56F, wd, ST.u0(PART_PLATE + 3 * (sd > 0 ? 0 : 1) + j, 0, limbPhi(u)), ST.v0(PART_PLATE + 3 * (sd > 0 ? 0 : 1) + j, 0, limbPhi(u)),
					1.5F, 1F, m.tint());
		}
	}

	/** Level 3's barbs on a limb's front, swept toward the tip, swaying. */
	private static void limbBarbs(ArmDraw d, ArmLayer layer, SkinMesh m, boolean detailed, float ft, int sd) {
		if (tier < 3 || layer != d.paint(ArmLayer.BLACK)) return;
		float time = d.time();
		for (int j = 0; j < 2; j++) {
			float u = BARB_U[j];
			if (u > limbReach - 0.05F) continue;
			float grow = detailed ? pop(ft - 9.4F - 0.4F * j) : ArmMath.sm((limbReach - u - 0.05F) / 0.12F);
			if (grow <= 0.02F) continue;
			limbFrame(u, sd);
			float len = (BARB_LEN[j] + 0.2F * (float) Math.sin(0.09F * time + 1.9F * j + sd) + 0.7F * d.surge() * BARB_LEN[j]) * thin * grow;
			float lean = (float) Math.toRadians(BARB_LEAN[j]);
			Q.set(M).fma(0.45F, P);
			PL[0] = Q.x;
			PL[1] = Q.y;
			PL[2] = Q.z;
			float step = len / 3F;
			float x = Q.x, y = Q.y, z = Q.z;
			for (int i = 1; i <= 3; i++) {
				float sway = 0.3F * (float) Math.sin(0.15F * time + 1.7F * j + 0.5F * i) * (i / 3F);
				float ang = lean + 0.25F * (i - 1) + sway;
				float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
				x += (P.x * c + T.x * sn) * step;
				y += (P.y * c + T.y * sn) * step;
				PL[3 * i] = x;
				PL[3 * i + 1] = y;
				PL[3 * i + 2] = z;
			}
			plCount = 4;
			tubeW = 0.9F;
			float phi = limbPhi(u);
			Loft.tube(m, POLYLINE, 0F, 1F, 4, 4, TUBE_W, d.paint(ArmLayer.BLACK), layer, ST, PART_BARB + 2 * (sd > 0 ? 0 : 1) + j, 2F, phi, phi + 2F, UP_Z, 1F);
		}
	}

	/** The red nock knots where the string sits on each limb: beating with the pulses (glowing at level 3), and kicking at a shot. */
	private static void nocks(ArmDraw d, ArmLayer layer, SkinMesh m, float s) {
		if (lod >= 1 || s <= 0.02F || limbReach < NOCK_U - 0.06F) return;
		ArmLayer red = glowCore ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		for (int sd = 1; sd >= -1; sd -= 2) {
			limbFrame(NOCK_U, sd);
			float beat = 1F + 0.35F * d.swell(limbPhi(NOCK_U), feedLength) + d.knotBeat() + 0.5F * twang;
			float k = s * beat;
			float ang = (float) Math.atan2(T.y, T.x) - ArmMath.PI / 2F;
			LOCAL.translation(M.x, M.y, M.z).rotateZ(ang).scale(k);
			m.box(LOCAL, -0.5F, -0.4F, -0.45F, 1.0F, 0.8F, 0.9F, ST.u0(PART_NOCK, sd > 0 ? 0 : 1, limbPhi(NOCK_U)), ST.v0(PART_NOCK, sd > 0 ? 0 : 1, limbPhi(NOCK_U)), 1.5F, 1F,
					m.tint());
		}
	}

	// ------------------------------------------------------------------ the string and the bolt

	/**
	 * The string: a thin red tendril from each nock to its middle, which is where the weapon has cocked it ({@link #sY}): drawn back
	 * as the bolt grows, forward and ringing after a shot. A red latch knot sits at the middle.
	 */
	private static void string(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (stringReach <= 0.02F || limbReach < NOCK_U - 0.04F) return;
		swellA = d.swell(limbPhi(NOCK_U), feedLength);
		for (int sd = 1; sd >= -1; sd -= 2) {
			side = sd;
			limbFrame(NOCK_U, sd);
			Q.set(M);
			R.set(0F, sY, STRING_Z);
			float phi = limbPhi(NOCK_U);
			Loft.tube(m, STRING, 0F, 1F, lod == 0 ? 5 : 3, 4, STRING_W, red, layer, ST, PART_STRING + (sd > 0 ? 0 : 1), 2F, phi, phi + 6F, UP_Z, 1F);
		}
		if (lod == 0 && stringReach >= 0.95F && (layer == red || Loft.pulseGlowPass(layer))) {
			LOCAL.translation(0F, sY, STRING_Z);
			m.box(LOCAL, -0.5F, -0.4F, -0.4F, 1F, 0.8F, 0.8F, ST.u0(PART_STRING, 5, limbPhi(NOCK_U)), ST.v0(PART_STRING, 5, limbPhi(NOCK_U)), 1.5F, 1F, m.tint());
		}
	}

	/**
	 * The bolt in the groove: a red core with two black strands winding round it, growing out of the latch forward with an eroding front
	 * (the head last: a collar, three barbs swept back and a red point), and three vanes at its nock coming in as the shaft passes them.
	 * At full growth it is exactly the loaded bolt; at none, nothing is drawn.
	 */
	private static void bolt(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (shaftReach <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		ArmLayer headTip = tier >= 3 && d.glow() ? ArmLayer.GLOW : red;
		float phi0 = phi(boltRear), phi1 = phi(boltRear + SHAFT * shaftReach);
		if (lod >= 1) {
			if (layer == black) {
				m.box(null, -0.5F, boltRear, BOLT_Z - 0.5F, 1F, SHAFT * shaftReach + HEAD * headPop, 1F, ST.u0(PART_BOLT, 5, phi0), ST.v0(PART_BOLT, 5, phi0), 1F, 1F, m.tint());
			}
			return;
		}
		Loft.tube(m, BOLT_CORE, 0F, 1F, 5, 4, s -> FistGeometry.width(0.6F, 4), red, layer, ST, PART_BOLT, 1.5F, phi0, phi1, UP_Z, BOLT_ALPHA);
		if (layer == black) {
			for (int k = 0; k < 2; k++) {
				strandK = k;
				Loft.tube(m, BOLT_STRAND, 0F, 1F, 8, 4, s -> FistGeometry.width(0.5F, 4), black, layer, ST, PART_BOLT + 1 + k, 2F, phi0, phi1, UP_Z, BOLT_ALPHA);
			}
		}
		// the vanes at the nock: alternating red and black, fluttering a hair, coming in as the shaft passes them
		float vs = ArmMath.clamp01((shaftReach - 0.12F) / 0.3F);
		if (vs > 0.02F && (layer == black || layer == red)) {
			float time = d.time();
			for (int k = 0; k < 3; k++) {
				if ((k == 1) != (layer == red)) continue;
				float a = ArmMath.TAU * k / 3F + 0.15F * (float) Math.sin(0.2F * time + k) + ArmMath.PI / 2F;
				LOCAL.translation(0F, boltRear + 0.5F, BOLT_Z).rotateY(a);
				m.box(LOCAL, 0.3F, -0.55F, -0.05F, 2F * vs, 1.3F, 0.1F, ST.u0(PART_VANE, k, phi0), ST.v0(PART_VANE, k, phi0), 2F, 1F, m.tint());
			}
		}
		// the head, last
		if (headPop > 0.02F) {
			float hy = boltRear + SHAFT;
			float hs = headPop * (1F + 0.3F * d.swell(phi(hy), feedLength));
			if (layer == black) {
				LOCAL.translation(0F, hy - 0.3F, BOLT_Z).rotateX(-ArmMath.PI / 2F).scale(hs);
				m.frustum(LOCAL, 1.5F, 0.9F, 0.9F, 1.5F, 1.5F, ST.u0(PART_HEAD, 0, phi1), ST.v0(PART_HEAD, 0, phi1), 1.5F, 1F, 1F, m.tint());
				for (int k = 0; k < 3; k++) {
					float a = ArmMath.TAU * k / 3F + 0.5F * (float) Math.sin(0.1F * d.time() + k);
					float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
					LOCAL.translation(0.7F * ca * hs, hy + 1.1F * hs, BOLT_Z + 0.7F * sa * hs).rotateTowards(0.6F * ca, -0.7F, 0.6F * sa, 0F, 1F, 0F);
					m.frustum(LOCAL, 1.2F * hs, 0.45F, 0.45F, 0.05F, 0.05F, ST.u0(PART_HEAD, 1 + k, phi1), ST.v0(PART_HEAD, 1 + k, phi1), 2F, 1F, 1F, m.tint());
				}
			}
			if (layer == headTip || layer == red && Loft.pulseGlowPass(layer)) {
				LOCAL.translation(0F, hy + 1.2F * hs, BOLT_Z).rotateX(-ArmMath.PI / 2F).scale(hs);
				m.frustum(LOCAL, HEAD - 0.4F, 1.4F, 1.4F, 0.05F, 0.05F, ST.u0(PART_HEAD, 5, phi1), ST.v0(PART_HEAD, 5, phi1), 1.5F, 1F, 1F, m.tint());
			}
		}
	}

	/**
	 * The Spine Burst's muzzle: each spine flashes a ring of red spikes at the groove's front as it leaves, one every
	 * {@code gap} ticks, each lasting 5 ticks (the marks are the effect handler's, so a viewer who missed the packet shows none).
	 */
	private static void flares(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (lod >= 1 || limbReach < 0.5F) return;
		ArmLayer red = tier >= 3 && d.glow() ? ArmLayer.GLOW : d.paint(ArmLayer.RED);
		if (layer != red || !burst(d, BURST)) return;
		for (int i = 0, n = (int) BURST[2]; i < n; i++) {
			float age = BURST[0] - i * BURST[1];
			if (age < 0F || age >= 5F) continue;
			float k = (float) Math.sin(ArmMath.PI * age / 5F);
			for (int j = 0; j < 4; j++) {
				float a = ArmMath.TAU * j / 4F + 0.8F * i + ArmMath.PI / 4F;
				float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
				LOCAL.translation(0F, MUZZLE_Y + 0.2F, BOLT_Z).rotateTowards(0.7F * ca, 0.8F, 0.7F * sa, 0F, 0F, 1F);
				m.frustum(LOCAL, 2.4F * k, 0.5F, 0.5F, 0.06F, 0.06F, ST.u0(PART_FLARE + i, j, phi(MUZZLE_Y)), ST.v0(PART_FLARE + i, j, phi(MUZZLE_Y)), 2F, 1F, 1F, m.tint());
			}
		}
	}

	// ------------------------------------------------------------------ beads and drips

	/**
	 * Loose beads: two flicked off the nocks by a hit, beads dropping off a retracting front, a melt shedding two a tick, and a failing
	 * arm's three beads orbiting where a limb's tip used to be.
	 */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, float rt, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float hitAge = d.hitAge();
		if (hitAge >= 0F && hitAge < 8F && limbReach > 0.5F) {
			limbFrame(1F, 1);
			for (int j = 0; j < 2; j++) {
				float a = hitAge;
				float x = M.x + P.x * (0.8F * a + 0.3F * j) + T.x * 0.2F * j + DOWN.x * 0.06F * a * a;
				float y = M.y + P.y * (0.8F * a + 0.3F * j) + T.y * 0.2F * j + DOWN.y * 0.06F * a * a;
				float z = M.z + DOWN.z * 0.06F * a * a;
				FistGeometry.bead(d, layer, m, ST, j == 0 ? ArmLayer.BLACK : ArmLayer.RED, x, y, z, 0.6F * (1F - a / 8F), j);
			}
		}
		if (rt >= 2F && rt < 12F) {
			for (int k = 0; k < 5; k++) {
				float a = rt - (2F + 1.4F * k);
				if (a < 0F || a > 3F) continue;
				float u = 1F - ArmMath.sm(1.4F * k / 7F);
				limbFrame(u, k % 2 == 0 ? 1 : -1);
				FistGeometry.bead(d, layer, m, ST, k % 2 == 0 ? ArmLayer.BLACK : ArmLayer.RED, M.x + DOWN.x * 0.5F * a * a, M.y + DOWN.y * 0.5F * a * a, M.z + DOWN.z * 0.5F * a * a,
						0.5F * (1F - a / 3F), 2 + k);
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
				float u = 1F - ArmMath.sm((born - 2F) / 14F);
				limbFrame(u, (idx & 2) == 0 ? 1 : -1);
				float jitter = FistGeometry.hash(idx * 13 + d.seed()) - 0.5F;
				FistGeometry.bead(d, layer, m, ST, (idx & 1) == 0 ? ArmLayer.BLACK : ArmLayer.RED, M.x + P.x * jitter * 1.5F + DOWN.x * 0.4F * a * a,
						M.y + P.y * jitter * 1.5F + DOWN.y * 0.4F * a * a, M.z + DOWN.z * 0.4F * a * a, 0.5F * (1F - a / 4F), 8 + q);
			}
		}
		if (failing && limbReach > 0.5F) {
			limbFrame(limbReach, 1);
			for (int k = 0; k < 3; k++) {
				float a = 0.3F * d.time() + ArmMath.TAU * k / 3F;
				FistGeometry.bead(d, layer, m, ST, k == 1 ? ArmLayer.RED : ArmLayer.BLACK, M.x + 0.6F * ((float) Math.cos(a) * P.x), M.y + 0.6F * ((float) Math.cos(a) * P.y),
						M.z + 0.6F * (float) Math.sin(a), 0.5F, 16 + k);
			}
		}
	}

	/** Drips off the grip's heel, the butt and the limbs' tips, each only while its piece is out. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != d.paint(ArmLayer.BLACK)) return;
		float rate = d.dripRate();
		float bp = d.blocksToPx();
		if (gripOut >= 0.95F) Drips.emit(m, null, 0F, GRIP_Y + GRIP_RAKE, GRIP_BOTTOM, DOWN, d.time(), 0, rate, bp, ST, PART_DRIP);
		if (stockLo < BUTT_Y + 0.5F) Drips.emit(m, null, 0F, BUTT_Y, -1.0F, DOWN, d.time(), 1, rate, bp, ST, PART_DRIP);
		if (limbReach >= 0.95F) {
			side = -1;
			limbFrame(0.97F, -1);
			Drips.emit(m, null, M.x, M.y, M.z, DOWN, d.time(), 2 + (d.seed() & 1), rate, bp, ST, PART_DRIP);
		}
	}

	// ------------------------------------------------------------------ LOD 2 and 3

	/** LOD 2: the stock, the prod and the bolt as boxes (the grip too). */
	private static void boxes(ArmDraw d, ArmLayer layer, SkinMesh m, float riserPlan) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (stockHi - stockLo > 0.2F && layer == black) {
			m.box(null, -1.2F, stockLo, -0.9F, 2.4F, stockHi - stockLo, 1.9F, ST.u0(PART_BOX, 0, 12F), ST.v0(PART_BOX, 0, 12F), 1F, 1F, m.tint());
		}
		if (gripOut > 0.1F && layer == black) {
			m.box(null, -0.9F, GRIP_Y + GRIP_RAKE * gripOut - 0.9F, GRIP_BOTTOM * gripOut - 0.9F, 1.8F, 1.8F, -GRIP_BOTTOM * gripOut, ST.u0(PART_BOX, 1, 12F),
					ST.v0(PART_BOX, 1, 12F), 1F, 1F, m.tint());
		}
		if (limbReach > 0.1F && layer == black) {
			float xr = NOCK_X[tier] * limbReach;
			m.box(null, -xr, RISER_Y - 0.5F, LIMB_Z - 0.5F, 2F * xr, 1.4F, 1F, ST.u0(PART_BOX, 2, 20F), ST.v0(PART_BOX, 2, 20F), 1F, 1F, m.tint());
		}
		if (shaftReach > 0.05F && layer == red) {
			m.box(null, -0.4F, boltRear, BOLT_Z - 0.4F, 0.8F, (SHAFT + HEAD * headPop) * shaftReach, 0.8F, ST.u0(PART_BOX, 3, 20F), ST.v0(PART_BOX, 3, 20F), 2F, 1F, m.tint());
		}
	}

	/** LOD 3: two boxes, the stock and the prod bar. */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m, float riserPlan) {
		if (layer != d.paint(ArmLayer.BLACK)) return;
		if (stockHi - stockLo > 0.2F) {
			m.box(null, -1.2F, stockLo, -0.9F, 2.4F, stockHi - stockLo, 1.9F, ST.u0(PART_BOX, 4, 12F), ST.v0(PART_BOX, 4, 12F), 1F, 1F, m.tint());
		}
		if (limbReach > 0.1F) {
			float xr = NOCK_X[tier] * limbReach;
			m.box(null, -xr, RISER_Y - 0.5F, LIMB_Z - 0.5F, 2F * xr, 1.4F, 1F, ST.u0(PART_BOX, 5, 20F), ST.v0(PART_BOX, 5, 20F), 1F, 1F, m.tint());
		}
	}

	// ------------------------------------------------------------------ points and extents

	/**
	 * The recorded points, in G: TIP is the front of the groove (where a bolt leaves from), MID the middle of the bolt, GRIP the pistol
	 * grip's middle. C's {@code (x, y, z)} is G's {@code (-y, x, z)}.
	 */
	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) return out.set(-GRIP_Y, 0F, -2.4F);
		float reach = d.reach();
		float y = which == ArmTips.TIP ? ArmMath.lerp(GRIP_Y, MUZZLE_Y + 0.4F, reach) : ArmMath.lerp(GRIP_Y, 1.5F, reach);
		return out.set(-y, 0F, BOLT_Z);
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float xr = 7F, y0 = BUTT_Y - 0.4F, y1 = MUZZLE_Y + HEAD, z0 = GRIP_BOTTOM - 0.6F, z1 = BOLT_Z + 1F;
		// C's corners, in G
		for (int i = 0; i < 8; i++) {
			float cx = (i & 1) == 0 ? -xr : xr, cy = (i & 2) == 0 ? y0 : y1, cz = (i & 4) == 0 ? z0 : z1;
			out.accept(new Vector3f(-cy, cx, cz));
		}
	}
}
