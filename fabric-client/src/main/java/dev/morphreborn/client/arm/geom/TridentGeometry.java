package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.model.ArmTransforms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Drips;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.Rope;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.Stations;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The trident (my design, 2026-11-16: a thrown trident on a tendril line that reels it back, Recall snapping it home): a
 * three-pronged fork on a braided shaft. Grip space G (px), +Y toward the prongs, the fist at the origin.
 * <p>
 * The shaft is a braid as the spear's, shorter: a black core with strands of tendril winding round it the two ways at once (two
 * black at level 1, a red one more at level 2, two black and two red at level 3). Above it sits a red collar and the fork, three
 * prongs of hardened static that grow out of a crossbar: the middle one straight, the sides sweeping out and rising beside it, each
 * black with a red point and hooked red barbs (none, some, more by level). Level 2 adds red binding rings and two spurs on the
 * collar, level 3 four spurs, a crossbar doubled and a pennon of tendril streaming from under the fork. The butt ends in a red eye
 * where the line is made fast, and below the fist the line itself lies coiled round the shaft in red turns (more by level), ready
 * to pay out.
 * <p>
 * <b>Thrown.</b> While the trident is away ({@link ArmDraw#thrown}; the hand's own state, eased in and out by {@link #markAway}) the
 * shaft and the prongs reel into the fist and the hand keeps a stub of braided tendril, a writhing finger with a bud of three hairs,
 * in the empty hand; the world line to the flying trident (TridentFx) starts at its end, which is {@link ArmTips#TIP} meanwhile. The
 * coil is gone with the line. The hand pose and the sheath don't change: only what the hand holds does.
 * <p>
 * Everything moves: the static crawls up the shaft into the prongs, pulses swell the braid and light the red, the barbs sway, the
 * bristles stream back in a stab; drawing a throw (the use animation's {@link ArmDraw#bowPull}) spreads the side prongs and lights
 * the points, a hit flares them and flicks beads off, Recall and the throw wind the hand up ({@link ArmDraw#specialWeight}); low
 * biomass thins it, cracks it red, then erodes its points and makes it spasm, and melting droops it. Every timeline is read from
 * {@link ArmDraw}.
 * <p>
 * <b>Where it is held.</b> Vanilla's trident model has its origin at the base of the prongs, 13 px from where the hand holds it, and
 * a scale of its own in first person; the form is meant to sit in the hand as every other form does, so in first person (and in third
 * person while a throw is drawn, when the game switches to the raised pose's display block) it is moved by {@link #holdFix}, which
 * lands grip space exactly where the sword's lands in the same context.
 * <p>
 * Stateless between calls (apart from the away marks, a handful of numbers); render thread only (shared scratch).
 */
public final class TridentGeometry implements FormGeometry {
	/** The braid's outer radius under the fingers, as the other forms' grips. */
	public static final float HILT_R = 1.05F;
	/** The butt: the shaft ends here and the eye hangs just below it. */
	public static final float BUTT = -6.2F;
	/** The fork's base (the collar's centre), the middle prong's length from it, and the side prongs' rise and spread, by tier (index 1 to 3). */
	private static final float[] COLLAR = {0F, 16.5F, 17.5F, 18.5F}, MID_LEN = {0F, 11.5F, 13F, 14.5F}, SIDE_LEN = {0F, 8.2F, 9.4F, 10.6F},
			SIDE_X = {0F, 3.3F, 3.8F, 4.3F};
	/** The braid's turn: px of shaft to a full turn of a strand, and its radius. */
	private static final float PITCH = 6.5F, BRAID_R = 0.78F;
	/** Strands in the braid, by tier; the last {@code tier - 1} are red. */
	private static final int[] STRANDS = {0, 2, 3, 4};
	/** Hooked barbs on the middle prong (a pair at a time) and on each side prong, by tier. */
	private static final int[] MID_BARBS = {0, 0, 2, 4}, SIDE_BARBS = {0, 1, 1, 2};
	/** Spurs on the collar, binding rings, pennons, tail spikes under the eye, turns of coiled line and crossbars, by tier. */
	private static final int[] SPURS = {0, 0, 2, 4}, RINGS = {0, 0, 1, 2}, PENNONS = {0, 0, 0, 1}, TAIL_SPIKES = {0, 1, 2, 3}, BARS = {1, 1, 1, 2};
	private static final float[] COIL_TURNS = {0F, 2.5F, 3.5F, 4.5F};
	/** Where bristles stand on the shaft (y px), by tier: above the fist, below the fork. */
	private static final float[][] BRISTLES = {{}, {5F, 9.5F, 13.5F}, {4.5F, 7.5F, 10.5F, 13F, 15.5F}, {4F, 6.2F, 8.6F, 11F, 13.2F, 15.2F, 17F}};
	private static final float[] BR_LEN = {1.0F, 1.5F, 0.75F, 1.7F, 1.1F, 0.8F, 1.3F}, BR_ANG = {38F, 52F, 30F, 56F, 42F, 34F, 50F},
			BR_W = {0.55F, 0.7F, 0.45F, 0.75F, 0.55F, 0.45F, 0.65F};
	/** The prongs' share of their length that is the black part (the rest is the red point). */
	private static final float TIP_FROM = 0.74F;
	/** The stub's length in px and the bud's, and the time the hand takes to reel the trident in and to take it back (ticks). */
	private static final float STUB_LEN = 7F, BUD_LEN = 1.7F, AWAY_TICKS = 3F, HOME_TICKS = 5F;
	/** The barbs' length and the world size of the thrown trident (how much of G's px a world px is). */
	private static final float BARB_LEN = 2.4F;
	/** First person and the raised pose draw through {@link #holdFix}. */
	private static final float MIN_PULL = 1e-4F;

	// static parts, one per piece
	private static final int PART_CORE = 1, PART_WRAP = 2, PART_COLLAR = 9, PART_RING = 10, PART_PRONG = 14, PART_BAR = 20, PART_BARB = 24, PART_SPUR = 40,
			PART_BRISTLE = 46, PART_EYE = 56, PART_COIL = 58, PART_SPIKE = 60, PART_PENNON = 66, PART_STUB = 68, PART_HAIR = 70, PART_BEAD = 74, PART_DRIP = 78,
			PART_CRACK = 80, PART_BOX = 84;

	private static final Static ST = new Static();
	private static final Stations RING7 = new Stations();
	private static final float[] POLY = new float[14];
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Matrix4f TOOL = new Matrix4f();
	private static final Matrix4f BASE = new Matrix4f();
	private static final Matrix4f MIRROR = new Matrix4f().scaling(-1F, 1F, 1F);
	private static final Quaternionf TURN = new Quaternionf();
	private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f(), Q = new Vector3f(), R = new Vector3f(), DOWN = new Vector3f();
	private static final Vector3f UP_Y = new Vector3f(0F, 1F, 0F), UP_Z = new Vector3f(0F, 0F, 1F), UP_X = new Vector3f(1F, 0F, 0F);
	private static final float[] TAIL = new float[3 * ArmDraw.ROPE_NODES];
	private static final Vector3f ROOT_LOCAL = new Vector3f(), ROOT_OUT = new Vector3f(0.3F, -0.95F, 0F).normalize();

	// what the current emit has worked out, for the callbacks (render thread, one emit at a time)
	private static ArmDraw cur;
	private static int tier;
	private static float feedLength, front, low, shaftTop, droop, thin, flare, spread, bristle, home, away, stubLen, fringeW;
	private static boolean fringeOn, flying;
	/** The stretch of the core or a strand the next tube covers (y px), and which strand. */
	private static float ty0, ty1, strandWidth;
	private static int strand;
	/** The prong the next tube draws: its base height and length, and the sway. */
	private static float prongYc, prongLen, prongSway;
	private static int prongK;
	private static float ringY, ringR;
	private static float coilAmount;
	private static float barY, barHalf, barLift;
	private static int pennonSide;
	private static ArmLayer collarLayer = ArmLayer.RED;

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
	private static final Loft.CurveFn PRONG = (s, out) -> {
		ArmMath.bezier(P0, P1, P2, s, out);
		bend(out.y, out);
	};
	private static final Loft.RadiusFn PRONG_R = s -> {
		float y = prongYc + prongLen * s;
		float w = prongK == 0 ? ArmMath.lerp(1.9F, 0.2F, (float) Math.pow(s, 0.85)) : ArmMath.lerp(1.5F, 0.18F, (float) Math.pow(s, 0.85));
		return FistGeometry.width(w * thin * (1F + 0.3F * cur.swell(phi(y), feedLength)), 4);
	};
	private static final Loft.AlphaFn PRONG_FRINGE = s -> ArmMath.fringe(front - (prongYc + prongLen * s), fringeW);
	private static final Loft.CurveFn BAR = (s, out) -> {
		float x = barHalf * (2F * s - 1F);
		float arch = 1F - (2F * s - 1F) * (2F * s - 1F);
		out.set(x, barY - barLift * arch, 0.2F * (float) Math.sin(cur.phase() + 3F * s + 0.2F * cur.time()));
		bend(out.y, out);
	};
	private static final Loft.RadiusFn BAR_R = s -> FistGeometry.width(ArmMath.lerp(0.62F, 0.88F, 1F - Math.abs(2F * s - 1F)) * thin, 4);
	private static final Loft.CurveFn RING = (s, out) -> {
		float a = ArmMath.TAU * s + 0.004F * cur.time();
		float r = ringR * (1F + 0.04F * (float) Math.sin(3F * a + 0.05F * cur.time()));
		out.set(r * (float) Math.cos(a), ringY, r * (float) Math.sin(a));
		bend(ringY, out);
	};
	private static final Loft.RadiusFn RING_R = s -> FistGeometry.width(0.5F * thin, 4);
	/** The eye: a loop in the YZ plane, hanging just under the butt. */
	private static final Loft.CurveFn EYE = (s, out) -> {
		float a = ArmMath.TAU * s;
		out.set(0F, BUTT - 1.0F + 1.35F * (float) Math.cos(a), 1.35F * (float) Math.sin(a));
		bend(out.y, out);
	};
	private static final Loft.RadiusFn EYE_R = s -> FistGeometry.width(0.55F * thin, 4);
	/** The coiled line: a helix up the shaft under the fist. */
	private static final Loft.CurveFn COIL = (s, out) -> {
		float turns = COIL_TURNS[tier] * coilAmount;
		float a = ArmMath.TAU * turns * s;
		float y = ArmMath.lerp(-5.2F, -1.7F, s * coilAmount);
		out.set((BRAID_R + 0.42F) * (float) Math.cos(a), y, (BRAID_R + 0.42F) * (float) Math.sin(a));
		bend(y, out);
	};
	private static final Loft.RadiusFn COIL_R = s -> FistGeometry.width(0.46F * thin * (1F + 0.3F * cur.swell(phi(-3F), feedLength)), 4);
	/** The stub: a finger of braided tendril out of the empty fist, writhing. */
	private static final Loft.CurveFn STUB = (s, out) -> {
		float y = 0.4F + stubLen * s;
		float wave = (float) Math.sin(cur.phase() + 5.5F * s + 0.32F * cur.time()) * 0.5F * s;
		out.set(wave, y, 0.6F * (float) Math.cos(1.3F * cur.phase() + 4.2F * s + 0.27F * cur.time()) * 0.5F * s);
	};
	private static final Loft.RadiusFn STUB_R = s -> FistGeometry.width(ArmMath.lerp(1.5F, 0.75F, s) * thin * (1F + 0.4F * cur.swell(phi(stubLen * s), feedLength)), 4);
	private static final Loft.CurveFn PENNON = (s, out) -> pennonPoint(s, out);
	private static final Loft.RadiusFn PENNON_R = s -> FistGeometry.width(ArmMath.lerp(0.75F, 0.25F, s), 4);

	@Override
	public String form() {
		return ArmProtocol.TRIDENT;
	}

	/** Where the middle prong's point is at rest, by tier (G px). */
	public static float tipY(int tier) {
		int t = Math.clamp(tier, 1, 3);
		return COLLAR[t] + MID_LEN[t];
	}

	/** The fork's base (the collar's centre) by tier. */
	public static float collarY(int tier) {
		return COLLAR[Math.clamp(tier, 1, 3)];
	}

	/** Where the side prongs stand out to by tier (G px from the axis), and how far they rise. */
	public static float sideX(int tier) {
		return SIDE_X[Math.clamp(tier, 1, 3)];
	}

	public static float sideRise(int tier) {
		return SIDE_LEN[Math.clamp(tier, 1, 3)];
	}

	/** What the levels carry: strands in the braid, barbs on the middle prong and on a side one, spurs, rings, pennons, bristles, tail spikes, coil turns, crossbars. */
	public static int strandCount(int tier) {
		return STRANDS[Math.clamp(tier, 1, 3)];
	}

	public static int midBarbCount(int tier) {
		return MID_BARBS[Math.clamp(tier, 1, 3)];
	}

	public static int sideBarbCount(int tier) {
		return SIDE_BARBS[Math.clamp(tier, 1, 3)];
	}

	public static int spurCount(int tier) {
		return SPURS[Math.clamp(tier, 1, 3)];
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

	public static float coilTurns(int tier) {
		return COIL_TURNS[Math.clamp(tier, 1, 3)];
	}

	public static int barCount(int tier) {
		return BARS[Math.clamp(tier, 1, 3)];
	}

	/** The line's end on the trident, in G px: the eye's lowest point. */
	public static float eyeY() {
		return BUTT - 1F - 1.35F;
	}

	/** The stub's length at full (px), where the world line starts while the trident is away. */
	public static float stubLength() {
		return STUB_LEN + 0.4F + BUD_LEN * 0.5F;
	}

	@Override
	public float feedLength(int tier) {
		return FistGeometry.PHI_TOOL + tipY(tier) - 1.8F;
	}

	@Override
	public float guiScale() {
		// the point lands at g + k*0.707*y along the slot's diagonal: 0.40 and 4.7 keep the level-3 point (y 33) and the tail spikes
		// (y about -11) between item pixels 1 and 15
		return 0.40F;
	}

	@Override
	public float guiGripPx() {
		return 4.7F;
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

	// ------------------------------------------------------------------ away: what the hand holds

	private static final int MARKS = 16;
	private static final int[] MARK_ID = new int[MARKS];
	private static final boolean[] MARK_USED = new boolean[MARKS], MARK_TO = new boolean[MARKS];
	private static final float[] MARK_SINCE = new float[MARKS], MARK_FROM = new float[MARKS];
	private static int markNext;

	/**
	 * {@code owner}'s trident went away ({@code thrown}) or came back at {@code now} (the arm clock): the hand reels it in over 3 ticks or
	 * takes it back over 5. The draw reads {@link ArmDraw#thrown} as the truth and uses a mark only to ease toward it, so a draw with no
	 * mark (a preview, an icon, another viewer who never saw the throw) simply shows the state at once.
	 */
	public static void markAway(int owner, boolean thrown, float now) {
		int slot = -1;
		for (int i = 0; i < MARKS; i++) {
			if (MARK_USED[i] && MARK_ID[i] == owner) {
				slot = i;
				break;
			}
		}
		float from = thrown ? 0F : 1F;
		if (slot >= 0) from = amountAt(slot, now);
		else {
			for (int i = 0; i < MARKS && slot < 0; i++) {
				if (!MARK_USED[i]) slot = i;
			}
			if (slot < 0) {
				slot = markNext;
				markNext = (markNext + 1) % MARKS;
			}
		}
		MARK_USED[slot] = true;
		MARK_ID[slot] = owner;
		MARK_TO[slot] = thrown;
		MARK_SINCE[slot] = now;
		MARK_FROM[slot] = from;
	}

	private static float amountAt(int slot, float now) {
		float to = MARK_TO[slot] ? 1F : 0F;
		float dur = MARK_TO[slot] ? AWAY_TICKS : HOME_TICKS;
		float k = ArmMath.sm(ArmMath.clamp01(WhipGeometry.ageOf(now, MARK_SINCE[slot]) / dur));
		return ArmMath.lerp(MARK_FROM[slot], to, k);
	}

	/** How far the trident is away in {@code d}, 0 (in hand) to 1 (gone, the stub in the fist). */
	static float away(ArmDraw d) {
		float truth = d.thrown() ? 1F : 0F;
		for (int i = 0; i < MARKS; i++) {
			if (MARK_USED[i] && MARK_ID[i] == d.seed()) return MARK_TO[i] == d.thrown() ? amountAt(i, d.time()) : truth;
		}
		return truth;
	}

	/** Forgets every mark (joining or leaving a world). */
	public static void clearMarks() {
		java.util.Arrays.fill(MARK_USED, false);
		markNext = 0;
	}

	// ------------------------------------------------------------------ where it is held

	/** The corrections {@link #holdFix} hands out, worked out once (they only depend on vanilla's display blocks). */
	private static final class Fix {
		static final Matrix4f FP_RIGHT = firstPerson(false), FP_LEFT = firstPerson(true), RAISED_RIGHT = raised(false), RAISED_LEFT = raised(true);

		private static Matrix4f applied(ArmTransforms.Set set, ItemDisplayContext ctx) {
			PoseStack.Pose pose = new PoseStack().last();
			ArmTransforms.forContext(ctx, set).apply(ctx.leftHand(), pose);
			return new Matrix4f(pose.pose());
		}

		/** Moves G so that vanilla's trident display block lands it where the sword's lands it in first person. */
		private static Matrix4f firstPerson(boolean left) {
			ItemDisplayContext ctx = left ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
			Matrix4f x = applied(ArmTransforms.Set.TRIDENT, ctx).invert().mul(applied(ArmTransforms.Set.HANDHELD, ctx))
					.mul(ArmTransforms.gripFrame(ArmTransforms.Set.HANDHELD, new Matrix4f()));
			return ArmTransforms.gripFrame(ArmTransforms.Set.TRIDENT, new Matrix4f()).invert().mul(x);
		}

		/**
		 * Third person while a throw is drawn: the game uses the raised pose's display block, and the form's frame is its rest hold's
		 * (the one fixed to the trident model), not the model's own origin.
		 */
		private static Matrix4f raised(boolean left) {
			ItemDisplayContext ctx = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			Matrix4f x = ArmTransforms.handFrame(ctx, ArmProtocol.TRIDENT, false, false, new Matrix4f());
			return ArmTransforms.gripFrame(ArmTransforms.Set.TRIDENT, new Matrix4f()).invert().mul(x);
		}
	}

	/** Whether {@code d} is drawn through {@link #holdFix}: first person, and third person while a throw is drawn (the raised pose). */
	static boolean fixed(ArmDraw d) {
		return d.firstPerson() || d.thirdPerson() && d.bowPull() > MIN_PULL;
	}

	/**
	 * The correction applied to everything this form draws in {@code d}'s context, into {@code out}: identity where the item path already
	 * lands G on the sword's hold (third person at rest, the icons). It is {@code gripFrame(TRIDENT)^-1 · X}, X being the frame that puts
	 * G where the sword's is for the vanilla display block in use.
	 */
	static Matrix4f holdFix(ArmDraw d, Matrix4f out) {
		if (!fixed(d)) return out.identity();
		if (d.firstPerson()) return out.set(d.leftHand() ? Fix.FP_LEFT : Fix.FP_RIGHT);
		return out.set(d.leftHand() ? Fix.RAISED_LEFT : Fix.RAISED_RIGHT);
	}

	// ------------------------------------------------------------------ emit

	@Override
	public void emit(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.SENSE) return;
		prepare(d, false);
		float sp = d.spasm();
		holdFix(d, BASE);
		if (d.firstPerson() && ArmDraw.FIRST_PERSON_ARM) {
			// the forearm and fist, in the frame the other forms' are built for (the sword's), mirrored for the left hand
			LOCAL.set(BASE);
			if (d.leftHand()) LOCAL.mul(MIRROR);
			m.transform(LOCAL);
			ForearmGeometry.emit(d, layer, m, ST, feedLength, 0F);
			FistGeometry.emit(d, layer, m, ST, HILT_R, feedLength);
		}
		FistGeometry.knot(d, layer, m, ST);
		m.transform(BASE.mul(TOOL.scaling(sp, sp, sp)));
		try {
			tool(d, layer, m);
		} finally {
			m.transform(null);
		}
	}

	/**
	 * The trident flying free (TridentFx draws the thrown projectile with it): the whole tool at full reach in {@code d}'s own level of
	 * detail, with no fist, coil or stub, in whatever frame {@code m} was given. {@code d} is any draw of this form for its tier, clock,
	 * seed and look; its state is ignored.
	 */
	public static void emitFlying(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer == ArmLayer.SENSE) return;
		prepare(d, true);
		tool(d, layer, m);
	}

	private static void prepare(ArmDraw d, boolean freeFlight) {
		cur = d;
		flying = freeFlight;
		tier = Math.clamp(d.tier(), 1, 3);
		feedLength = TRIDENT_FEED[tier];
		ST.set(d.time(), d.staticCadence(), d.crawlDir(), d.seed());
		d.down(DOWN);
	}

	private static final float[] TRIDENT_FEED = {0F, FistGeometry.PHI_TOOL + tipY(1) - 1.8F, FistGeometry.PHI_TOOL + tipY(2) - 1.8F,
			FistGeometry.PHI_TOOL + tipY(3) - 1.8F};

	private static void tool(ArmDraw d, ArmLayer layer, SkinMesh m) {
		float reach = flying ? 1F : d.reach();
		away = flying ? 0F : away(d);
		home = 1F - away;
		boolean failing = d.lowStage() == ArmDraw.LOW_FAILING;
		float tip = tipY(tier);
		front = reach * tip * home;
		if (failing) front = Math.min(front, 0.92F * tip);
		low = reach * BUTT * home;
		droop = d.droop();
		thin = d.lowStage() >= ArmDraw.LOW_THINNING ? 0.88F : 1F;
		float sw = d.specialWeight(ArmProtocol.FX_TRIDENT);
		float swing = (float) Math.sin(ArmMath.PI * ArmMath.clamp01(d.swing()));
		float pull = flying ? 0F : ArmMath.clamp01(d.bowPull());
		// drawing a throw spreads the side prongs and lights the points; Recall and the throw wind the hand up the same way (the fork opens, the bristles stand)
		spread = Math.max(Math.max(pull, sw), flying ? 0.35F : 0F);
		bristle = sw;
		flare = Math.max(Math.max(d.hitFlare(), sw), pull);
		fringeOn = d.dissolve() && (reach < 1F || failing || home < 1F);
		fringeW = failing && reach >= 1F ? 1.7F : d.fringe();
		shaftTop = Math.min(front, COLLAR[tier] - 0.3F);
		stubLen = flying ? 0F : STUB_LEN * ArmMath.sm((away - 0.15F) / 0.85F) * ArmMath.sm((reach - 0.4F) / 0.6F) * (1F + 0.25F * sw);
		coilAmount = flying ? 0F : home * ArmMath.sm((reach - 0.5F) / 0.5F);
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
		fork(d, layer, m, lod);
		barbs(d, layer, m, lod);
		spurs(d, layer, m, lod);
		bristles(d, layer, m, lod, swing);
		butt(d, layer, m, lod);
		if (lod == 0) coil(d, layer, m);
		if (lod == 0) pennons(d, layer, m);
		stub(d, layer, m, lod);
		beads(d, layer, m, failing);
		if (lod == 0 && d.dripsNow() && reach >= 0.99F && home > 0.99F) drips(d, layer, m);
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
			int stations = Math.max(2, Math.round((y1 - y0) * (lod == 0 ? 0.55F : 0.28F)));
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
			int stations = Math.max(2, Math.round((y1 - y0) * (lod == 0 ? (tier >= 3 ? 0.62F : 0.74F) : 0.4F)));
			Loft.tube(m, WRAP, 0F, 1F, stations, sides, WRAP_R, strandLayer, layer, ST, PART_WRAP + k, 2F, phi(y0), phi(y1), UP_Y, fringe);
		}
		if (wantRed && d.lowStage() >= ArmDraw.LOW_STARVING && lod == 0) {
			// starving cracks: red rings where the braid has split
			for (int j = 0; j < 3; j++) {
				float y = 5.5F + 4.6F * j;
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
		collarLayer = layer;
		Loft.sweep(m, out, layer, (seg, face) -> collarLayer, ST, PART_COLLAR, 1F, false, false);
	}

	/** Red binding rings round the shaft under the fork. */
	private static void rings(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		int n = RINGS[tier];
		ArmLayer red = d.paint(ArmLayer.RED);
		if (n == 0 || lod > 0 || layer != red && !Loft.pulseGlowPass(layer)) return;
		for (int j = 0; j < n; j++) {
			float y = COLLAR[tier] - 2.8F - 3.4F * j;
			if (y > front - 0.4F || y < low + 0.5F) continue;
			ringY = y;
			ringR = BRAID_R + 0.42F;
			Loft.closedTube(m, RING, 8, 4, RING_R, red, layer, ST, PART_RING + j, 1.5F, phi(y), UP_Y, 1F);
		}
	}

	// ------------------------------------------------------------------ the fork

	/**
	 * Sets the control points of prong {@code k} (0 the middle one, 1 the one on -X, 2 on +X): the middle one straight up from the
	 * collar; the sides sweeping out and rising beside it (wider as a throw is drawn), swaying a hair.
	 */
	private static void setProng(int k) {
		float yc = COLLAR[tier];
		prongK = k;
		prongYc = yc - 0.3F;
		if (k == 0) {
			prongLen = MID_LEN[tier] + 0.3F;
			P0.set(0F, yc - 0.3F, 0F);
			P1.set(0F, yc + 0.5F * MID_LEN[tier], 0F);
			P2.set(0F, yc + MID_LEN[tier], 0F);
			return;
		}
		float side = k == 1 ? -1F : 1F;
		float x = SIDE_X[tier] * (1F + 0.22F * spread);
		prongLen = SIDE_LEN[tier] + 0.3F;
		prongSway = 0.12F * (float) Math.sin(0.06F * cur.time() + 2.1F * k + cur.phase());
		P0.set(side * 1.0F, yc - 0.3F, 0F);
		P1.set(side * x * 1.4F, yc + 0.34F * SIDE_LEN[tier], 0F);
		P2.set(side * (x + prongSway), yc + SIDE_LEN[tier], 0F);
	}

	/**
	 * The three prongs: black up to a red point (the points glow at level 3), tapering to a hair, four-sided like cut stone. The crossbar
	 * ties their bases together.
	 */
	private static void fork(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float yc = COLLAR[tier];
		if (front <= yc - 0.5F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		ArmLayer tipLayer = tier >= 3 && d.glow() ? ArmLayer.GLOW : red;
		boolean wantBlack = layer == black, wantRed = layer == red || layer == tipLayer || Loft.pulseGlowPass(layer);
		if (!wantBlack && !wantRed) return;
		int sides = lod == 0 ? 4 : 3;
		Loft.AlphaFn fringe = fringeOn ? PRONG_FRINGE : null;
		for (int k = 0; k < 3; k++) {
			setProng(k);
			float len = prongLen;
			float s1 = ArmMath.clamp01((front - prongYc) / len);
			if (s1 <= 0.01F) continue;
			int stations = lod == 0 ? (k == 0 ? 9 : 7) : 4;
			if (wantBlack) {
				Loft.tube(m, PRONG, 0F, Math.min(s1, TIP_FROM + 0.03F), Math.max(3, Math.round(stations * Math.min(s1, TIP_FROM))), sides, PRONG_R, black, layer, ST,
						PART_PRONG + 2 * k, 1.5F, phi(prongYc), phi(prongYc) + len, UP_Z, fringe);
			}
			if (wantRed && s1 > TIP_FROM) {
				Loft.tube(m, PRONG, TIP_FROM, s1, Math.max(3, stations / 2), sides, PRONG_R, tipLayer, layer, ST, PART_PRONG + 2 * k + 1, 2F, phi(prongYc),
						phi(prongYc) + len, UP_Z, fringe);
			}
		}
		// the crossbar (a second, lower one at level 3), a red core in it from level 2
		if (lod == 0 && front > yc + 0.8F) {
			float grow = ArmMath.sm((front - yc - 0.8F) / 1.6F);
			for (int b = 0; b < BARS[tier]; b++) {
				barHalf = SIDE_X[tier] * (1F + 0.12F * spread) * grow * (b == 0 ? 0.95F : 0.7F);
				barY = yc + (b == 0 ? 1.7F : -0.4F);
				barLift = b == 0 ? 1.3F : 0.9F;
				if (wantBlack) Loft.tube(m, BAR, 0F, 1F, 7, 4, BAR_R, black, layer, ST, PART_BAR + b, 1.5F, phi(yc), phi(yc) + 3F, UP_Z, 1F);
				if (tier >= 2 && wantRed && b == 0) {
					barLift -= 0.15F;
					Loft.tube(m, BAR, 0.12F, 0.88F, 5, 4, s -> FistGeometry.width(0.38F * thin, 4), red, layer, ST, PART_BAR + 2, 2F, phi(yc), phi(yc) + 3F, UP_Z, 1F);
				}
			}
		}
	}

	/**
	 * Hooked red barbs on the prongs: out and back down the prong from partway up it (the middle prong's in pairs, the sides' outward,
	 * a second inward at level 3). They sway, open as a throw is drawn and fold back in a stab.
	 */
	private static void barbs(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (layer != red && !Loft.pulseGlowPass(layer)) return;
		float yc = COLLAR[tier];
		if (front <= yc + 2F) return;
		int mid = lod == 0 ? MID_BARBS[tier] : Math.min(2, MID_BARBS[tier]);
		for (int j = 0; j < mid; j++) {
			// pairs at 0.46 and 0.64 up the prong, one each side
			float at = 0.46F + 0.18F * (j >> 1);
			float out = (j & 1) == 0 ? -1F : 1F;
			barb(d, m, 0, at, out, j);
		}
		int sideBarbs = lod == 0 ? SIDE_BARBS[tier] : Math.min(1, SIDE_BARBS[tier]);
		for (int k = 1; k <= 2; k++) {
			for (int j = 0; j < sideBarbs; j++) {
				float out = (k == 1 ? -1F : 1F) * (j == 0 ? 1F : -1F);
				barb(d, m, k, j == 0 ? 0.5F : 0.62F, out, 6 + 2 * k + j);
			}
		}
	}

	/** One barb on prong {@code k} at {@code at} (0..1 up it), leaving toward {@code out} (X: -1 left, +1 right). */
	private static void barb(ArmDraw d, SkinMesh m, int k, float at, float out, int id) {
		setProng(k);
		float s = at;
		if (prongYc + prongLen * s > front - 0.8F) return;
		ArmMath.bezier(P0, P1, P2, s, Q);
		ArmMath.bezierTangent(P0, P1, P2, s, R);
		float tl = R.length();
		if (tl < 1e-5F) return;
		R.div(tl);
		float sway = 1F + 0.2F * (float) Math.sin(0.05F * cur.time() + 1.7F * id + cur.phase());
		float open = 0.8F + 0.5F * spread;
		// back down the prong and out
		float dx = out * open * 0.85F * sway - R.x * 0.55F, dy = -R.y * 0.55F - 0.05F, dz = -R.z * 0.55F;
		bend(Q.y, Q);
		float len = BARB_LEN * (k == 0 ? 1.1F : 1F) * ArmMath.sm((front - prongYc - prongLen * s) / 2.5F);
		float w = 0.8F * thin;
		spike(m, Q.x, Q.y, Q.z, dx, dy, dz, len, w, 0.1F, PART_BARB + id, 0, m.tint());
	}

	/** Spurs on the collar: short thorns leaving it across the fork's plane and down the shaft (level 2: a pair; level 3: four). */
	private static void spurs(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		int n = SPURS[tier];
		ArmLayer red = d.paint(ArmLayer.RED);
		if (n == 0 || lod > 0 || layer != red && !Loft.pulseGlowPass(layer)) return;
		float yc = COLLAR[tier];
		float grow = ArmMath.sm((front - yc - 0.5F) / 2.5F);
		if (grow <= 0.02F) return;
		for (int k = 0; k < n; k++) {
			// across the plane, then the in-plane pair at level 3
			float az = k < 2 ? ArmMath.PI * 0.5F + ArmMath.PI * k : (k == 2 ? 0F : ArmMath.PI);
			float c = (float) Math.cos(az), s = (float) Math.sin(az);
			Q.set(1.1F * c, yc - 0.6F, 1.1F * s);
			bend(Q.y, Q);
			float len = (k < 2 ? 3.4F : 2.6F) * grow * (1F + 0.25F * flare);
			spike(m, Q.x, Q.y, Q.z, 0.85F * c, -0.55F, 0.85F * s, len, 0.85F, 0.1F, PART_SPUR + k, 0, m.tint());
		}
	}

	// ------------------------------------------------------------------ the bristles

	/** Short filaments leaning back from the shaft and swaying: streaming in a stab, standing on end while the hand is wound up. */
	private static void bristles(ArmDraw d, ArmLayer layer, SkinMesh m, int lod, float swing) {
		ArmLayer black = d.paint(ArmLayer.BLACK);
		float sprout = ArmMath.sm((d.reach() * home - 0.5F) / 0.5F);
		if (flying) sprout = 1F;
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
			float ang = (float) Math.toRadians(BR_ANG[k]) + 0.15F * (float) Math.sin(0.15F * time + 1.7F * k) + (float) Math.toRadians(40) * swing;
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

	// ------------------------------------------------------------------ the butt, the coil and the pennon

	/** The butt's knot, the red eye the line is made fast to and the tail spikes under it, out as the shaft grows down. */
	private static void butt(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		float grow = ArmMath.sm((-low - 4.6F) / 1.9F);
		if (grow <= 0.02F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		if (layer == black) {
			LOCAL.identity().translate(0F, BUTT, 0F).scale(grow);
			m.box(LOCAL, -0.85F, -0.55F, -0.7F, 1.7F, 1.1F, 1.4F, ST.u0(PART_EYE, 0, 4F), ST.v0(PART_EYE, 0, 4F), 1F, 1F, m.tint());
			int n = lod >= 1 ? Math.min(1, TAIL_SPIKES[tier]) : TAIL_SPIKES[tier];
			for (int k = 0; k < n; k++) {
				// the first spike points straight down from the knot, the rest fan across the eye's plane
				float fan = k == 0 ? 0F : ((k & 1) == 1 ? 1F : -1F) * 0.45F;
				spike(m, 0F, BUTT - 2.4F * grow, 0F, 0F, -(float) Math.cos(fan), (float) Math.sin(fan), (k == 0 ? 2.6F : 2F) * grow, 0.8F, 0.1F, PART_SPIKE + k, k,
						m.tint());
			}
		}
		if (lod == 0 && (layer == red || Loft.pulseGlowPass(layer))) {
			// the eye: a loop in the YZ plane under the knot; its lowest point is where the line is made fast
			Loft.closedTube(m, EYE, 10, 4, EYE_R, red, layer, ST, PART_EYE + 1, 1.5F, phi(BUTT), UP_X, 1F);
		}
	}

	/** The line coiled round the shaft under the fist (while the trident is home): red turns that unwind as it goes. */
	private static void coil(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer red = d.paint(ArmLayer.RED);
		if (coilAmount <= 0.02F || low > -5.4F || layer != red && !Loft.pulseGlowPass(layer)) return;
		Loft.tube(m, COIL, 0F, 1F, Math.max(6, Math.round(COIL_TURNS[tier] * coilAmount * 5F)), 4, COIL_R, red, layer, ST, PART_COIL, 2F, phi(-5F), phi(-1.7F), UP_Y, 1F);
	}

	/** The pennons: tendril streaming from under the fork on the rope's chain (a still droop where nothing simulates it). */
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

	// ------------------------------------------------------------------ the stub: what the empty hand keeps

	/**
	 * While the trident is away the fist keeps a stub: a finger of braided tendril (black, with a red strand winding round it) writhing out
	 * of the grip, ending in a bud of three hairs, the world line's root. It is {@code stubLen} long, growing as the trident reels in.
	 */
	private static void stub(ArmDraw d, ArmLayer layer, SkinMesh m, int lod) {
		if (stubLen <= 0.3F) return;
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		int stations = lod == 0 ? 9 : 5;
		int sides = lod == 0 ? 4 : 3;
		if (layer == black) {
			Loft.tube(m, STUB, 0F, 1F, stations, sides, STUB_R, black, layer, ST, PART_STUB, 1.5F, phi(0.4F), phi(0.4F + stubLen), UP_Z, 1F);
			// the bud's three hairs, fanning from the end
			if (stubLen > 4F) {
				STUB.at(1F, Q);
				for (int k = 0; k < (lod == 0 ? 3 : 1); k++) {
					float a = ArmMath.TAU * k / 3F + 0.2F * cur.time() * 0.1F + cur.phase();
					float len = BUD_LEN * (1F + 0.3F * (float) Math.sin(0.2F * cur.time() + 2F * k)) * ArmMath.sm((stubLen - 4F) / 3F);
					spike(m, Q.x, Q.y, Q.z, 0.55F * (float) Math.cos(a), 1F, 0.55F * (float) Math.sin(a), len, 0.55F, 0.08F, PART_HAIR + k, 0, m.tint());
				}
			}
		} else if (layer == red || Loft.pulseGlowPass(layer)) {
			// a red strand winds round the stub
			Loft.tube(m, STUB_RED, 0F, 1F, stations, 3, s -> FistGeometry.width(ArmMath.lerp(0.5F, 0.3F, s), 3), red, layer, ST, PART_STUB + 1, 2F, phi(0.4F),
					phi(0.4F + stubLen), UP_Y, 1F);
		}
	}

	private static final Loft.CurveFn STUB_RED = (s, out) -> {
		STUB.at(s, out);
		float a = ArmMath.TAU * 2.2F * s + 0.15F * cur.time();
		float r = ArmMath.lerp(1.1F, 0.7F, s) * 0.5F + 0.15F;
		out.add(r * (float) Math.cos(a), 0F, r * (float) Math.sin(a));
	};

	// ------------------------------------------------------------------ beads and drips

	/** Beads flicked off the points by a hit, shed by a melt, and orbiting where a failing arm's point used to be. */
	private static void beads(ArmDraw d, ArmLayer layer, SkinMesh m, boolean failing) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED || flying) return;
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

	/** Drips off the crossbar's low point and the eye. */
	private static void drips(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != d.paint(ArmLayer.BLACK)) return;
		float rate = d.dripRate();
		float side = DOWN.x >= 0F ? 1F : -1F;
		Drips.emit(m, null, side * SIDE_X[tier] * 0.6F, COLLAR[tier] + 0.8F, 0F, DOWN, d.time(), 4 + (d.seed() & 1), rate, d.blocksToPx(), ST, PART_DRIP);
		if (-low > 5F) Drips.emit(m, null, 0F, BUTT - 4.2F, 0F, DOWN, d.time(), 1, rate, d.blocksToPx(), ST, PART_DRIP);
	}

	// ------------------------------------------------------------------ levels of detail

	/** LOD 2: boxes for the shaft, collar, crossbar and butt, a tapered box for each prong. */
	private static void coarse(ArmDraw d, ArmLayer layer, SkinMesh m) {
		ArmLayer black = d.paint(ArmLayer.BLACK), red = d.paint(ArmLayer.RED);
		float yc = COLLAR[tier];
		if (layer == black && shaftTop - low > 0.05F) {
			m.box(null, -0.8F, low, -0.8F, 1.6F, shaftTop - low, 1.6F, ST.u0(PART_BOX, 0, 10F), ST.v0(PART_BOX, 0, 10F), 1F, 1F, m.tint());
		}
		if (layer == red && front > yc - 0.6F) {
			m.box(null, -1.3F, yc - 0.6F, -1.3F, 2.6F, 1.2F, 2.6F, ST.u0(PART_BOX, 1, 10F), ST.v0(PART_BOX, 1, 10F), 1F, 1F, m.tint());
		}
		if (layer == black && front > yc + 0.5F) {
			float x = SIDE_X[tier];
			float top = Math.min(front, yc + MID_LEN[tier]);
			float mid = top - (yc - 0.3F);
			LOCAL.translation(0F, yc - 0.3F, 0F).rotateX(-ArmMath.PI / 2F);
			m.frustum(LOCAL, mid, 1.6F, 1.6F, 0.3F, 0.3F, ST.u0(PART_BOX, 2, 10F), ST.v0(PART_BOX, 2, 10F), 1F, 1F, 1F, m.tint());
			float sideTop = Math.min(front, yc + SIDE_LEN[tier]) - (yc - 0.3F);
			for (int k = 0; k < 2; k++) {
				LOCAL.translation(k == 0 ? -x : x, yc - 0.3F, 0F).rotateX(-ArmMath.PI / 2F);
				m.frustum(LOCAL, sideTop, 1.3F, 1.3F, 0.3F, 0.3F, ST.u0(PART_BOX, 3 + k, 10F), ST.v0(PART_BOX, 3 + k, 10F), 1F, 1F, 1F, m.tint());
			}
		}
		if (layer == black && -low > 5F) {
			m.box(null, -0.8F, BUTT - 0.5F, -0.7F, 1.6F, 1.1F, 1.4F, ST.u0(PART_BOX, 5, 10F), ST.v0(PART_BOX, 5, 10F), 1F, 1F, m.tint());
		}
		if (layer == black && stubLen > 0.5F) {
			m.box(null, -0.7F, 0F, -0.7F, 1.4F, stubLen, 1.4F, ST.u0(PART_BOX, 6, 10F), ST.v0(PART_BOX, 6, 10F), 1F, 1F, m.tint());
		}
	}

	/** LOD 3: the shaft and the fork, two boxes (and the stub's, one). */
	private static void far(ArmDraw d, ArmLayer layer, SkinMesh m) {
		if (layer != d.paint(ArmLayer.BLACK)) return;
		float top = Math.min(front, tipY(tier));
		if (Math.min(top, COLLAR[tier]) - low > 0.1F) {
			m.box(null, -0.8F, low, -0.7F, 1.6F, Math.min(top, COLLAR[tier]) - low, 1.4F, ST.u0(PART_BOX, 7, 10F), ST.v0(PART_BOX, 7, 10F), 1F, 1F, m.tint());
		}
		float base = COLLAR[tier];
		if (top > base + 0.1F) {
			float x = SIDE_X[tier] + 0.6F;
			m.box(null, -x, base, -0.4F, 2F * x, top - base, 0.8F, ST.u0(PART_BOX, 8, 10F), ST.v0(PART_BOX, 8, 10F), 1F, 1F, m.tint());
		}
		if (stubLen > 0.5F) m.box(null, -0.6F, 0F, -0.6F, 1.2F, stubLen, 1.2F, ST.u0(PART_BOX, 9, 10F), ST.v0(PART_BOX, 9, 10F), 1F, 1F, m.tint());
	}

	// ------------------------------------------------------------------ points and extents

	@Override
	public Vector3f point(ArmDraw d, int which, Vector3f out) {
		if (which == ArmTips.GRIP) {
			out.set(0F, 0F, 0F);
		} else {
			float away = away(d);
			float tip = tipY(d.tier());
			float f = d.reach() * tip * (1F - away);
			if (d.lowStage() == ArmDraw.LOW_FAILING) f = Math.min(f, 0.92F * tip);
			f = Math.max(0F, f);
			float reach = d.reach();
			// away, the point is where the world line starts: the stub's bud
			float stub = away > 0.15F ? STUB_LEN * ArmMath.sm((away - 0.15F) / 0.85F) * ArmMath.sm((reach - 0.4F) / 0.6F) + 0.4F + BUD_LEN * 0.5F : 0F;
			float y = Math.max(f, stub);
			out.set(0F, which == ArmTips.MID ? 0.55F * y : y, 0F);
		}
		if (fixed(d)) holdFix(d, BASE).transformPosition(out);
		return out;
	}

	@Override
	public void extents(Consumer<Vector3fc> out) {
		float[][] pts = {{-6.6F, 34F, -0.6F}, {6.6F, 34F, 0.6F}, {-6.6F, -11.8F, -2.4F}, {6.6F, -11.8F, 2.4F}, {-3.8F, 18F, -3.8F}, {3.8F, 18F, 3.8F},
				{-1F, 34F, -1F}, {1F, -11.8F, 1F}};
		for (float[] p : pts) out.accept(new Vector3f(p[0], p[1], p[2]));
	}
}
