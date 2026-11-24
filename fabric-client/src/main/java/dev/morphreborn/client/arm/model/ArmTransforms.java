package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where an arm form sits in each view: the item transforms (the same numbers as vanilla's own swords and bow, built in
 * code because no resource pack may change them) and the local transforms that put grip space G onto the item.
 * <p>
 * The item transforms (section 2.0): third person and first person use {@code item/handheld}'s, the bow's third person
 * uses {@code item/bow}'s, so an arm is held, swung and drawn exactly as the vanilla tool it replaces. Translations are
 * stored in blocks (the JSON's pixels × 1/16, as the deserializer stores them). The left-hand contexts store vanilla's
 * own mirrored "lefthand" values; {@code ItemTransform.apply(true, ...)} then un-mirrors them, just as it does for a
 * vanilla sword in the left hand (ArmTransformsTest checks the result against the JSON). Every other context (GUI,
 * frames, the ground, shelves, heads) is the identity, which is what {@code item/generated} has for the GUI, and the
 * form's own GUI fit goes in the local transform instead.
 * <p>
 * The local transforms. The item pipeline leaves the layer in the item's 0..1 box (the transform ends with a
 * translate of -0.5), and:
 * <ul>
 *   <li>{@link #grip}: {@code L_G = translate(3.5/16, 3.5/16, 8/16) · rotateZ(−45°) · scale(1/16)}, which puts G's
 *       origin on the sword sprite's grip pixel (3.5, 3.5) in the middle of its depth and lays +Y along the blade's
 *       diagonal; the units become model pixels.</li>
 *   <li>{@link #bowGrip}: the bow's G frame, the same orientation with the origin on the bow sprite's grip, the middle
 *       of its arc at (5.5, 11.5), so +Y runs to the upper limb's tip and +X toward the string.</li>
 *   <li>{@link #gui}: {@code L_GUI = translate(g/16, g/16, 8/16) · rotateZ(−45°) · scale(k/16)}, a form's own fit into a
 *       slot ({@code k} item pixels per form pixel, the grip on item pixel {@code g} of the diagonal).</li>
 * </ul>
 * <p>
 * The new forms (2026-11-16) each sit on a different vanilla item, so each has its own display block ({@link Set}): the spear's
 * {@code item/spear_in_hand}, the mace's {@code item/handheld_mace}, the trident's {@code item/trident_in_hand} (and its
 * {@code trident_throwing} while thrown), the crossbow's {@code item/crossbow} and the claws' (shears) {@code item/generated}.
 * A set also names where grip space G's origin sits in the item box ({@link Set#gripX}, {@link Set#gripY}, item pixels): the
 * sword's grip pixel for the handheld ones, the box's middle for the rest, so a new form's geometry starts from a sensible
 * hold and phase 2 moves it by changing one number here. In third person {@link #handFrame} then moves that frame so the
 * item's own display block lands G where the sheath holds it.
 * <p>
 * Pure: no game state, so the unit tests build every matrix here.
 */
public final class ArmTransforms {
	/** The sword sprite's grip pixel, along both axes. */
	public static final float GRIP_PX = 3.5F;
	/** The bow sprite's grip: the middle of its arc. */
	public static final float BOW_GRIP_X = 5.5F, BOW_GRIP_Y = 11.5F;

	private static final float RIGHT_ANGLE = (float) Math.toRadians(-45.0);

	// item/handheld.json
	static final ItemTransform HANDHELD_TP_RIGHT = transform(0, -90, 55, 0, 4.0F, 0.5F, 0.85F);
	static final ItemTransform HANDHELD_TP_LEFT = transform(0, 90, -55, 0, 4.0F, 0.5F, 0.85F);
	static final ItemTransform HANDHELD_FP_RIGHT = transform(0, -90, 25, 1.13F, 3.2F, 1.13F, 0.68F);
	static final ItemTransform HANDHELD_FP_LEFT = transform(0, 90, -25, 1.13F, 3.2F, 1.13F, 0.68F);
	// item/bow.json (its first person is handheld's)
	static final ItemTransform BOW_TP_RIGHT = transform(-80, 260, -40, -1, -2, 2.5F, 0.9F);
	static final ItemTransform BOW_TP_LEFT = transform(-80, -280, 40, -1, -2, 2.5F, 0.9F);

	// item/spear_in_hand.json (the iron, diamond and netherite spears; the flat sprite is only for the GUI and frames)
	static final ItemTransform SPEAR_TP_RIGHT = transform(5, 270, -40, 0, 2, 2, 1.7F, 1.7F, 0.85F);
	static final ItemTransform SPEAR_TP_LEFT = transform(5, -270, 40, 0, 2, 2, 1.7F, 1.7F, 0.85F);
	static final ItemTransform SPEAR_FP_RIGHT = transform(-20, 90, -35, 3.13F, 2.0F, 0.13F, 1.36F, 1.36F, 0.68F);
	static final ItemTransform SPEAR_FP_LEFT = transform(-20, -90, 35, 3.13F, 2.0F, 0.13F, 1.36F, 1.36F, 0.68F);
	// item/handheld_mace.json (handheld with its own numbers)
	static final ItemTransform MACE_TP_RIGHT = transform(0, -90, 55, 0, 4.0F, 1F, 1F);
	static final ItemTransform MACE_TP_LEFT = transform(0, 90, -55, 0, 4.0F, 1F, 1F);
	static final ItemTransform MACE_FP_RIGHT = transform(0, -90, 25, 0, 3F, 0.8F, 0.9F);
	static final ItemTransform MACE_FP_LEFT = transform(0, 90, -25, 0, 3F, 0.8F, 0.9F);
	// item/trident_in_hand.json, and item/trident_throwing.json while the use animation runs (its first person is in_hand's)
	static final ItemTransform TRIDENT_TP_RIGHT = transform(0, 60, 0, 11, 17, -2, 1F);
	static final ItemTransform TRIDENT_TP_LEFT = transform(0, 60, 0, 3, 17, 12, 1F);
	static final ItemTransform TRIDENT_FP_RIGHT = transform(0, -90, 25, -3, 17, 1, 1F);
	static final ItemTransform TRIDENT_FP_LEFT = transform(0, 90, -25, 13, 17, 1, 1F);
	static final ItemTransform TRIDENT_THROW_TP_RIGHT = transform(0, 90, 180, 8, -17, 9, 1F);
	static final ItemTransform TRIDENT_THROW_TP_LEFT = transform(0, 90, 180, 8, -17, -7, 1F);
	// item/crossbow.json
	static final ItemTransform CROSSBOW_TP_RIGHT = transform(-90, 0, -60, 2, 0.1F, -3, 0.9F);
	static final ItemTransform CROSSBOW_TP_LEFT = transform(-90, 0, 30, 2, 0.1F, -3, 0.9F);
	static final ItemTransform CROSSBOW_FP_RIGHT = transform(-90, 0, -55, 1.13F, 3.2F, 1.13F, 0.68F);
	static final ItemTransform CROSSBOW_FP_LEFT = transform(-90, 0, 35, 1.13F, 3.2F, 1.13F, 0.68F);
	// item/generated.json (the shears: it has no lefthand blocks, so the game uses the right hand's, which the left-hand fix then mirrors)
	static final ItemTransform GENERATED_TP = transform(0, 0, 0, 0, 3, 1, 0.55F);
	static final ItemTransform GENERATED_FP = transform(0, -90, 25, 1.13F, 3.2F, 1.13F, 0.68F);

	/**
	 * Which vanilla item's display block a form is held with, and its grip frame. {@link #of} picks it from the form id (and the
	 * geometry's own bow flag for the old forms).
	 */
	public enum Set {
		HANDHELD(HANDHELD_TP_RIGHT, HANDHELD_TP_LEFT, HANDHELD_FP_RIGHT, HANDHELD_FP_LEFT, GRIP_PX, GRIP_PX),
		BOW(BOW_TP_RIGHT, BOW_TP_LEFT, HANDHELD_FP_RIGHT, HANDHELD_FP_LEFT, BOW_GRIP_X, BOW_GRIP_Y),
		SPEAR(SPEAR_TP_RIGHT, SPEAR_TP_LEFT, SPEAR_FP_RIGHT, SPEAR_FP_LEFT, 8F, 8F),
		MACE(MACE_TP_RIGHT, MACE_TP_LEFT, MACE_FP_RIGHT, MACE_FP_LEFT, GRIP_PX, GRIP_PX),
		TRIDENT(TRIDENT_TP_RIGHT, TRIDENT_TP_LEFT, TRIDENT_FP_RIGHT, TRIDENT_FP_LEFT, 8F, 8F),
		CROSSBOW(CROSSBOW_TP_RIGHT, CROSSBOW_TP_LEFT, CROSSBOW_FP_RIGHT, CROSSBOW_FP_LEFT, 8F, 8F),
		GENERATED(GENERATED_TP, GENERATED_TP, GENERATED_FP, GENERATED_FP, 8F, 8F);

		final ItemTransform tpRight, tpLeft, fpRight, fpLeft;
		/** G's origin in the item box, item pixels (the grip frame is {@code translation(gripX/16, gripY/16, 8/16) · rotateZ(-45°) · scale(1/16)}). */
		public final float gripX, gripY;

		Set(ItemTransform tpRight, ItemTransform tpLeft, ItemTransform fpRight, ItemTransform fpLeft, float gripX, float gripY) {
			this.tpRight = tpRight;
			this.tpLeft = tpLeft;
			this.fpRight = fpRight;
			this.fpLeft = fpLeft;
			this.gripX = gripX;
			this.gripY = gripY;
		}

		/** The set {@code form} is held with; {@code bow} is its geometry's {@link dev.morphreborn.client.arm.geom.FormGeometry#bowTransforms()}. */
		public static Set of(String form, boolean bow) {
			return switch (form == null ? "" : form) {
				case ArmProtocol.SPEAR -> SPEAR;
				case ArmProtocol.MACE -> MACE;
				case ArmProtocol.TRIDENT -> TRIDENT;
				case ArmProtocol.CROSSBOW -> CROSSBOW;
				case ArmProtocol.CLAWS -> GENERATED;
				default -> bow ? BOW : HANDHELD;
			};
		}
	}

	private ArmTransforms() {}

	/** A transform as the JSON writes it: rotation in degrees, translation in pixels, one scale for all axes. */
	private static ItemTransform transform(float rx, float ry, float rz, float tx, float ty, float tz, float scale) {
		return transform(rx, ry, rz, tx, ty, tz, scale, scale, scale);
	}

	/** As above with a scale per axis (the spear's in-hand model is flattened). */
	private static ItemTransform transform(float rx, float ry, float rz, float tx, float ty, float tz, float sx, float sy, float sz) {
		// the deserializer stores translations in blocks, clamped to ±5
		Vector3f translation = new Vector3f(tx, ty, tz).mul(1F / 16F);
		translation.set(clamp(translation.x, 5F), clamp(translation.y, 5F), clamp(translation.z, 5F));
		return new ItemTransform(new Vector3f(rx, ry, rz), translation, new Vector3f(sx, sy, sz));
	}

	private static float clamp(float v, float limit) {
		return Math.max(-limit, Math.min(limit, v));
	}

	/**
	 * The item transform for {@code ctx}: the handheld (or, with {@code bow}, the bow's) numbers in the four hand
	 * contexts, the identity everywhere else.
	 */
	public static ItemTransform forContext(ItemDisplayContext ctx, boolean bow) {
		return forContext(ctx, bow ? Set.BOW : Set.HANDHELD);
	}

	/** The item transform for {@code ctx} in {@code set}'s display block, the identity outside the four hand contexts. */
	public static ItemTransform forContext(ItemDisplayContext ctx, Set set) {
		return switch (ctx) {
			case THIRD_PERSON_RIGHT_HAND -> set.tpRight;
			case THIRD_PERSON_LEFT_HAND -> set.tpLeft;
			case FIRST_PERSON_RIGHT_HAND -> set.fpRight;
			case FIRST_PERSON_LEFT_HAND -> set.fpLeft;
			default -> ItemTransform.NO_TRANSFORM;
		};
	}

	/**
	 * The transform {@code form} is held with in {@code ctx}: its own set's ({@link Set#of}). {@code throwing} is the trident's
	 * raised-to-throw pose in third person (its {@code trident_throwing} block, while the use animation runs); every other
	 * form and context ignores it.
	 */
	public static ItemTransform forForm(ItemDisplayContext ctx, String form, boolean bow, boolean throwing) {
		Set set = Set.of(form, bow);
		if (throwing && set == Set.TRIDENT) {
			if (ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return TRIDENT_THROW_TP_RIGHT;
			if (ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) return TRIDENT_THROW_TP_LEFT;
		}
		return forContext(ctx, set);
	}

	public static ItemTransform forForm(ItemDisplayContext ctx, String form, boolean bow) {
		return forForm(ctx, form, bow, false);
	}

	/** Whether {@code ctx} is drawn in grip space on the item (a hand) rather than with the GUI fit. */
	public static boolean inHand(ItemDisplayContext ctx) {
		return ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
				|| ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
	}

	/** {@code L_G} into {@code out}: grip space G on the sword sprite, in model pixels. */
	public static Matrix4f grip(Matrix4f out) {
		return out.translation(GRIP_PX / 16F, GRIP_PX / 16F, 8F / 16F).rotateZ(RIGHT_ANGLE).scale(1F / 16F);
	}

	/** The bow's G frame into {@code out}: the sword frame's orientation, its origin on the bow's grip. */
	public static Matrix4f bowGrip(Matrix4f out) {
		return out.translation(BOW_GRIP_X / 16F, BOW_GRIP_Y / 16F, 8F / 16F).rotateZ(RIGHT_ANGLE).scale(1F / 16F);
	}

	/** {@code set}'s grip frame into {@code out}: {@link #grip} for the handheld set, {@link #bowGrip} for the bow's, the same orientation on {@code set}'s own grip pixel for the rest. */
	public static Matrix4f gripFrame(Set set, Matrix4f out) {
		return out.translation(set.gripX / 16F, set.gripY / 16F, 8F / 16F).rotateZ(RIGHT_ANGLE).scale(1F / 16F);
	}

	/**
	 * The set whose hold the sheath draws {@code set}'s tool in: the bow's own, and the handheld one for every other (the new forms
	 * use it until phase 2 gives each its own hold, 2026-11-16). Phase 2 refines a form by pointing it at a hold of its own here
	 * and in {@code SheathGeometry.grip}, the two places that have to agree.
	 */
	static Set holdSet(Set set) {
		// the crossbow is held as vanilla holds it (2026-11-16): flat along the raised arm, its pistol grip where the claw closes
		return set == Set.BOW || set == Set.CROSSBOW ? set : Set.HANDHELD;
	}

	/**
	 * The local transform that puts grip space G on the item in {@code ctx}, for a tool {@code form} (with the geometry's {@code
	 * bow} flag). In first person, and for the sets the sheath holds as they are, it is {@link #gripFrame}. In third person a new
	 * form's own display block would put G where vanilla holds that item, which is 4 to 19 px off the hand the sheath closes
	 * (the item and the sheath disagreed, 2026-11-16), so its frame is moved in item space until the item's own transform lands G
	 * exactly where the hold set's would: {@code M(set)^-1 · M(hold) · gripFrame(hold)}, M being the display block's matrix as the
	 * game applies it (the arm bone cancels out of it). {@code throwing} (the trident raised to throw) keeps its own pose, which
	 * is meant to differ from the rest hold.
	 */
	public static Matrix4f handFrame(ItemDisplayContext ctx, String form, boolean bow, boolean throwing, Matrix4f out) {
		Set set = Set.of(form, bow);
		Set hold = holdSet(set);
		boolean thirdPerson = ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
		if (!thirdPerson || set == hold || throwing && set == Set.TRIDENT) return gripFrame(set, out);
		Matrix4f actual = applied(forContext(ctx, set), ctx);
		Matrix4f reference = applied(forContext(ctx, hold), ctx);
		return out.set(actual.invert().mul(reference).mul(gripFrame(hold, new Matrix4f())));
	}

	/**
	 * Grip space G of a tool {@code form} held in first person, as seen from the hand pass's pose at the item (what vanilla's first-person
	 * renderer has on its stack when it submits the item): the display block's matrix, then {@link #handFrame}. {@code leftHand}: the left
	 * hand holds it. In G px, +Y runs along the tool from the grip toward its tip.
	 */
	public static Matrix4f firstPersonFrame(String form, boolean bow, boolean leftHand, Matrix4f out) {
		ItemDisplayContext ctx = leftHand ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
		Matrix4f hand = handFrame(ctx, form, bow, false, new Matrix4f());
		return out.set(applied(forContext(ctx, Set.of(form, bow)), ctx)).mul(hand);
	}

	/** The matrix the game builds from {@code t} in {@code ctx} (the left-hand fix in left-hand contexts), ending with its -0.5 shift. */
	private static Matrix4f applied(ItemTransform t, ItemDisplayContext ctx) {
		PoseStack.Pose pose = new PoseStack().last();
		t.apply(ctx.leftHand(), pose);
		return new Matrix4f(pose.pose());
	}

	/**
	 * Where grip space G's origin is in arm space A (px, a right arm; the sheath mirrors it for a left one) for a tool of {@code set}
	 * held as vanilla holds it in third person: the arm bone, then {@code ItemInHandLayer}'s turn and offset, then the item
	 * transform, then the grip frame. For the handheld set this is (-1, 8.56, -0.67) and for the bow's (0.05, 11.25, -0.96), the
	 * numbers the sheath has always used ({@code ArmTransformsTest} checks both). For the new sets it is where vanilla holds the item, the
	 * starting point for phase 2's own hold (the sheath uses the handheld hold for them until then: a long, flat or entity-modelled item
	 * like the trident puts the raw point well off the hand).
	 */
	public static Vector3f gripInArm(Set set, Vector3f out) {
		Matrix4f m = new Matrix4f().rotateX((float) Math.toRadians(-90.0)).rotateY((float) Math.toRadians(180.0)).translate(1F / 16F, 2F / 16F, -10F / 16F);
		ItemTransform t = set.tpRight;
		m.translate(t.translation().x(), t.translation().y(), t.translation().z());
		m.rotate(new org.joml.Quaternionf().rotationXYZ((float) Math.toRadians(t.rotation().x()), (float) Math.toRadians(t.rotation().y()),
				(float) Math.toRadians(t.rotation().z())));
		m.scale(t.scale().x(), t.scale().y(), t.scale().z());
		m.translate(-0.5F, -0.5F, -0.5F);
		m.mul(gripFrame(set, new Matrix4f()));
		m.transformPosition(out.set(0F, 0F, 0F));
		return out.mul(16F);
	}

	/** {@code L_GUI} into {@code out}: a form fitted into a slot at scale {@code k}, its grip on item pixel {@code g}. */
	public static Matrix4f gui(float k, float g, Matrix4f out) {
		return out.translation(g / 16F, g / 16F, 8F / 16F).rotateZ(RIGHT_ANGLE).scale(k / 16F);
	}

	/**
	 * A GUI fit turned for a turntable icon (the Arsenal wheel, the upgrade tree, the preview): about the slot's centre
	 * (the item box's 0.5, 0.5, 0.5), tilted {@code pitchDeg} and turned {@code yawDeg}, scaled {@code scale} on top of
	 * the fit, then {@code L_GUI}.
	 */
	public static Matrix4f guiTurned(float k, float g, float yawDeg, float pitchDeg, float scale, Matrix4f out) {
		out.translation(0.5F, 0.5F, 0.5F).rotateX((float) Math.toRadians(pitchDeg)).rotateY((float) Math.toRadians(yawDeg)).scale(scale)
				.translate(-0.5F, -0.5F, -0.5F);
		return out.mul(gui(k, g, new Matrix4f()));
	}

	/**
	 * Where a GUI fit puts the rest pose along the slot's diagonal, in item pixels: the lowest and highest of
	 * {@code (x + y)/2} over the extents (the preview checks they stay within 1..15).
	 */
	public static float[] guiSpan(float k, float g, Iterable<? extends org.joml.Vector3fc> extents) {
		Matrix4f m = gui(k, g, new Matrix4f());
		Vector3f p = new Vector3f();
		float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
		for (org.joml.Vector3fc e : extents) {
			m.transformPosition(p.set(e));
			float along = (p.x + p.y) * 0.5F * 16F;
			lo = Math.min(lo, along);
			hi = Math.max(hi, along);
		}
		return new float[] {lo, hi};
	}
}
