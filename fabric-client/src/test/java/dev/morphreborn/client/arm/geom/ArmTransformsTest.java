package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.model.ArmTransforms;
import java.lang.reflect.Constructor;
import java.util.List;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The arm's item transforms against vanilla's own: the display blocks of {@code item/handheld} and {@code item/bow}
 * (copied from the 26.3 client jar) are read by the game's own deserializer, and every hand context, applied as the
 * game applies it (left-hand contexts with the left-hand fix), must give the same matrix as the arm's. So a sword arm in
 * either hand, in either person, sits exactly where a vanilla sword would. Also: the GUI and the other contexts are the
 * identity, and the grip frame lands on the sword sprite's grip with +Y along its blade.
 */
class ArmTransformsTest {
	/** item/handheld.json's display block, as shipped. */
	private static final String HANDHELD = """
			{
			    "thirdperson_righthand": { "rotation": [ 0, -90, 55 ], "translation": [ 0, 4.0, 0.5 ], "scale": [ 0.85, 0.85, 0.85 ] },
			    "thirdperson_lefthand": { "rotation": [ 0, 90, -55 ], "translation": [ 0, 4.0, 0.5 ], "scale": [ 0.85, 0.85, 0.85 ] },
			    "firstperson_righthand": { "rotation": [ 0, -90, 25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] },
			    "firstperson_lefthand": { "rotation": [ 0, 90, -25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] }
			}""";
	/** item/bow.json's display block, as shipped. */
	private static final String BOW = """
			{
			    "thirdperson_righthand": { "rotation": [ -80, 260, -40 ], "translation": [ -1, -2, 2.5 ], "scale": [ 0.9, 0.9, 0.9 ] },
			    "thirdperson_lefthand": { "rotation": [ -80, -280, 40 ], "translation": [ -1, -2, 2.5 ], "scale": [ 0.9, 0.9, 0.9 ] },
			    "firstperson_righthand": { "rotation": [ 0, -90, 25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] },
			    "firstperson_lefthand": { "rotation": [ 0, 90, -25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] }
			}""";

	/** item/spear_in_hand.json's display block (the iron, diamond and netherite spears in the hand), as shipped. */
	private static final String SPEAR = """
			{
			    "firstperson_righthand": { "rotation": [ -20, 90, -35 ], "translation": [ 3.13, 2.0, 0.13 ], "scale": [ 1.36, 1.36, 0.68 ] },
			    "firstperson_lefthand": { "rotation": [ -20, -90, 35 ], "translation": [ 3.13, 2.0, 0.13 ], "scale": [ 1.36, 1.36, 0.68 ] },
			    "thirdperson_righthand": { "rotation": [ 5, 270, -40 ], "translation": [ 0, 2, 2 ], "scale": [ 1.7, 1.7, 0.85 ] },
			    "thirdperson_lefthand": { "rotation": [ 5, -270, 40 ], "translation": [ 0, 2, 2 ], "scale": [ 1.7, 1.7, 0.85 ] }
			}""";
	/** item/handheld_mace.json's display block, as shipped. */
	private static final String MACE = """
			{
			    "thirdperson_righthand": { "rotation": [ 0, -90, 55 ], "translation": [ 0, 4.0, 1 ], "scale": [ 1, 1, 1 ] },
			    "thirdperson_lefthand": { "rotation": [ 0, 90, -55 ], "translation": [ 0, 4.0, 1 ], "scale": [ 1, 1, 1 ] },
			    "firstperson_righthand": { "rotation": [ 0, -90, 25 ], "translation": [ 0, 3, 0.8 ], "scale": [ 0.9, 0.9, 0.9 ] },
			    "firstperson_lefthand": { "rotation": [ 0, 90, -25 ], "translation": [ 0, 3, 0.8 ], "scale": [ 0.9, 0.9, 0.9 ] }
			}""";
	/** item/trident_in_hand.json's display block, as shipped. */
	private static final String TRIDENT = """
			{
			    "thirdperson_righthand": { "rotation": [ 0, 60, 0 ], "translation": [ 11, 17, -2 ], "scale": [ 1, 1, 1 ] },
			    "thirdperson_lefthand": { "rotation": [ 0, 60, 0 ], "translation": [ 3, 17, 12 ], "scale": [ 1, 1, 1 ] },
			    "firstperson_righthand": { "rotation": [ 0, -90, 25 ], "translation": [ -3, 17, 1 ], "scale": [ 1, 1, 1 ] },
			    "firstperson_lefthand": { "rotation": [ 0, 90, -25 ], "translation": [ 13, 17, 1 ], "scale": [ 1, 1, 1 ] }
			}""";
	/** item/trident_throwing.json's third person (its first person is in_hand's), as shipped. */
	private static final String TRIDENT_THROWING = """
			{
			    "thirdperson_righthand": { "rotation": [ 0, 90, 180 ], "translation": [ 8, -17, 9 ], "scale": [ 1, 1, 1 ] },
			    "thirdperson_lefthand": { "rotation": [ 0, 90, 180 ], "translation": [ 8, -17, -7 ], "scale": [ 1, 1, 1 ] }
			}""";
	/** item/crossbow.json's display block, as shipped. */
	private static final String CROSSBOW = """
			{
			    "thirdperson_righthand": { "rotation": [ -90, 0, -60 ], "translation": [ 2, 0.1, -3 ], "scale": [ 0.9, 0.9, 0.9 ] },
			    "thirdperson_lefthand": { "rotation": [ -90, 0, 30 ], "translation": [ 2, 0.1, -3 ], "scale": [ 0.9, 0.9, 0.9 ] },
			    "firstperson_righthand": { "rotation": [ -90, 0, -55 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] },
			    "firstperson_lefthand": { "rotation": [ -90, 0, 35 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] }
			}""";
	/** item/generated.json's hand blocks (the shears' model's parent), as shipped: right hands only, the game uses them for the left hands too. */
	private static final String GENERATED = """
			{
			    "thirdperson_righthand": { "rotation": [ 0, 0, 0 ], "translation": [ 0, 3, 1 ], "scale": [ 0.55, 0.55, 0.55 ] },
			    "firstperson_righthand": { "rotation": [ 0, -90, 25 ], "translation": [ 1.13, 3.2, 1.13 ], "scale": [ 0.68, 0.68, 0.68 ] }
			}""";

	private static final List<ItemDisplayContext> HANDS = List.of(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
			ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, ItemDisplayContext.FIRST_PERSON_LEFT_HAND);

	/** The game's own ItemTransform deserializer (a protected nested class, reached by reflection). */
	@SuppressWarnings("unchecked")
	private static ItemTransform vanilla(String json, ItemDisplayContext ctx) throws ReflectiveOperationException {
		Class<?> type = Class.forName("net.minecraft.client.resources.model.cuboid.ItemTransform$Deserializer");
		Constructor<?> make = type.getDeclaredConstructor();
		make.setAccessible(true);
		JsonDeserializer<ItemTransform> deserializer = (JsonDeserializer<ItemTransform>) make.newInstance();
		JsonObject display = JsonParser.parseString(json).getAsJsonObject();
		JsonElement entry = display.get(ctx.getSerializedName());
		return deserializer.deserialize(entry, ItemTransform.class, null);
	}

	/** The matrix the game builds from a transform in {@code ctx}: applied with the left-hand fix in left-hand contexts. */
	private static Matrix4f applied(ItemTransform t, ItemDisplayContext ctx) {
		PoseStack.Pose pose = new PoseStack().last();
		t.apply(ctx.leftHand(), pose);
		return new Matrix4f(pose.pose());
	}

	private static void assertMatrix(Matrix4fc expected, Matrix4fc actual, String what) {
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				assertEquals(expected.get(c, r), actual.get(c, r), 1e-5F, what + " m" + c + r);
			}
		}
	}

	@Test
	void handContextsMatchTheVanillaSword() throws ReflectiveOperationException {
		for (ItemDisplayContext ctx : HANDS) {
			Matrix4f expected = applied(vanilla(HANDHELD, ctx), ctx);
			Matrix4f actual = applied(ArmTransforms.forContext(ctx, false), ctx);
			assertMatrix(expected, actual, "sword " + ctx.getSerializedName());
		}
	}

	@Test
	void bowContextsMatchTheVanillaBow() throws ReflectiveOperationException {
		for (ItemDisplayContext ctx : HANDS) {
			Matrix4f expected = applied(vanilla(BOW, ctx), ctx);
			Matrix4f actual = applied(ArmTransforms.forContext(ctx, true), ctx);
			assertMatrix(expected, actual, "bow " + ctx.getSerializedName());
		}
	}

	/** As {@link #vanilla}, but a left hand the block does not have takes the right hand's, as the game's ItemTransforms does. */
	private static ItemTransform vanillaOrRight(String json, ItemDisplayContext ctx) throws ReflectiveOperationException {
		boolean has = JsonParser.parseString(json).getAsJsonObject().has(ctx.getSerializedName());
		if (has) return vanilla(json, ctx);
		ItemDisplayContext right = ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
				: ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
		return vanilla(json, right);
	}

	private static void assertSet(ArmTransforms.Set set, String json) throws ReflectiveOperationException {
		for (ItemDisplayContext ctx : HANDS) {
			Matrix4f expected = applied(vanillaOrRight(json, ctx), ctx);
			Matrix4f actual = applied(ArmTransforms.forContext(ctx, set), ctx);
			assertMatrix(expected, actual, set + " " + ctx.getSerializedName());
		}
	}

	@Test
	void theNewFormsMatchTheirVanillaItems() throws ReflectiveOperationException {
		assertSet(ArmTransforms.Set.SPEAR, SPEAR);
		assertSet(ArmTransforms.Set.MACE, MACE);
		assertSet(ArmTransforms.Set.TRIDENT, TRIDENT);
		assertSet(ArmTransforms.Set.CROSSBOW, CROSSBOW);
		assertSet(ArmTransforms.Set.GENERATED, GENERATED);
	}

	@Test
	void theTridentThrowsInItsOwnPose() throws ReflectiveOperationException {
		for (ItemDisplayContext ctx : List.of(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ItemDisplayContext.THIRD_PERSON_LEFT_HAND)) {
			Matrix4f expected = applied(vanilla(TRIDENT_THROWING, ctx), ctx);
			Matrix4f actual = applied(ArmTransforms.forForm(ctx, "trident", false, true), ctx);
			assertMatrix(expected, actual, "throwing " + ctx.getSerializedName());
		}
		// first person is in_hand's whatever the use state, and no other form has a throwing pose
		assertSame(ArmTransforms.forForm(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, "trident", false, false),
				ArmTransforms.forForm(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, "trident", false, true));
		assertSame(ArmTransforms.forForm(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, "spear", false, false),
				ArmTransforms.forForm(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, "spear", false, true));
	}

	@Test
	void everyFormPicksItsOwnSet() {
		assertSame(ArmTransforms.Set.HANDHELD, ArmTransforms.Set.of("katana", false));
		assertSame(ArmTransforms.Set.HANDHELD, ArmTransforms.Set.of("whip", false));
		assertSame(ArmTransforms.Set.BOW, ArmTransforms.Set.of("bow", true));
		assertSame(ArmTransforms.Set.SPEAR, ArmTransforms.Set.of("spear", false));
		assertSame(ArmTransforms.Set.MACE, ArmTransforms.Set.of("mace", false));
		assertSame(ArmTransforms.Set.TRIDENT, ArmTransforms.Set.of("trident", false));
		assertSame(ArmTransforms.Set.CROSSBOW, ArmTransforms.Set.of("crossbow", false));
		assertSame(ArmTransforms.Set.GENERATED, ArmTransforms.Set.of("claws", false));
		// the old boolean API is the handheld and bow sets
		for (ItemDisplayContext ctx : HANDS) {
			assertSame(ArmTransforms.forContext(ctx, ArmTransforms.Set.HANDHELD), ArmTransforms.forContext(ctx, false));
			assertSame(ArmTransforms.forContext(ctx, ArmTransforms.Set.BOW), ArmTransforms.forContext(ctx, true));
		}
	}

	@Test
	void gripInArmReproducesTheSheathsNumbers() {
		// the sheath has always put G at (-1, 8.56, -0.67) for the handheld tools and (0.05, 11.25, -0.96) for the bow; the sum that
		// gives the new forms theirs gives these two exactly
		Vector3f hand = ArmTransforms.gripInArm(ArmTransforms.Set.HANDHELD, new Vector3f());
		assertEquals(-1F, hand.x, 0.01F);
		assertEquals(8.56F, hand.y, 0.01F);
		assertEquals(-0.67F, hand.z, 0.01F);
		Vector3f bow = ArmTransforms.gripInArm(ArmTransforms.Set.BOW, new Vector3f());
		assertEquals(0.05F, bow.x, 0.01F);
		assertEquals(11.25F, bow.y, 0.01F);
		assertEquals(-0.96F, bow.z, 0.01F);
		// every set gives a finite point (phase 2 reads them as starting points for each new form's hold)
		for (ArmTransforms.Set set : ArmTransforms.Set.values()) {
			Vector3f g = ArmTransforms.gripInArm(set, new Vector3f());
			assertTrue(Float.isFinite(g.x) && Float.isFinite(g.y) && Float.isFinite(g.z), set + " grip " + g);
		}
	}

	@Test
	void gripFrameIsTheSwordsAndTheBowsForTheOldSets() {
		assertMatrix(ArmTransforms.grip(new Matrix4f()), ArmTransforms.gripFrame(ArmTransforms.Set.HANDHELD, new Matrix4f()), "sword frame");
		assertMatrix(ArmTransforms.bowGrip(new Matrix4f()), ArmTransforms.gripFrame(ArmTransforms.Set.BOW, new Matrix4f()), "bow frame");
		assertMatrix(ArmTransforms.grip(new Matrix4f()), ArmTransforms.gripFrame(ArmTransforms.Set.MACE, new Matrix4f()), "the mace is held by the sword's grip pixel");
	}

	@Test
	void everyNewFormsItemLandsItsGripWhereTheSheathHoldsIt() {
		// in third person the item's own display block plus the hand frame put G exactly where the handheld hold would, so the item
		// and the sheath's claw closed round it agree (the new forms' own display blocks would put G 4 to 19 px off)
		for (ItemDisplayContext ctx : new ItemDisplayContext[] {ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, ItemDisplayContext.THIRD_PERSON_LEFT_HAND}) {
			Matrix4f expected = applied(ArmTransforms.forContext(ctx, ArmTransforms.Set.HANDHELD), ctx)
					.mul(ArmTransforms.gripFrame(ArmTransforms.Set.HANDHELD, new Matrix4f()));
			// (the crossbow is held as vanilla holds it, flat along the raised arm: crossbowIsHeldAsVanillaHoldsItWithItsPistolGripWhereTheClawCloses)
			for (String form : new String[] {"spear", "mace", "trident", "claws"}) {
				Matrix4f frame = ArmTransforms.handFrame(ctx, form, false, false, new Matrix4f());
				Matrix4f actual = applied(ArmTransforms.forForm(ctx, form, false), ctx).mul(frame);
				assertMatrix(expected, actual, form + " " + ctx.getSerializedName());
			}
		}
	}

	/**
	 * The crossbow (2026-11-16) keeps its own display block and frame in third person, as vanilla holds the real item in the raised
	 * arm's pose: G's origin is the middle of the sprite, its X runs back along the arm (the front is -X), and the pistol grip (4.8 px behind the
	 * middle, 2.4 below it) is where the sheath's claw closes, (-1.88, 8.83) in arm space.
	 */
	@Test
	void crossbowIsHeldAsVanillaHoldsItWithItsPistolGripWhereTheClawCloses() {
		ItemDisplayContext ctx = ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
		assertMatrix(ArmTransforms.gripFrame(ArmTransforms.Set.CROSSBOW, new Matrix4f()), ArmTransforms.handFrame(ctx, "crossbow", false, false, new Matrix4f()),
				"the crossbow's own frame");
		// the arm bone, then ItemInHandLayer's turn and offset (as gripInArm), then the display block, then G
		Matrix4f m = new Matrix4f().rotateX((float) Math.toRadians(-90.0)).rotateY((float) Math.toRadians(180.0)).translate(1F / 16F, 2F / 16F, -10F / 16F);
		m.mul(applied(ArmTransforms.forForm(ctx, "crossbow", false), ctx)).mul(ArmTransforms.handFrame(ctx, "crossbow", false, false, new Matrix4f()));
		Vector3f grip = m.transformPosition(new Vector3f(4.8F, 0F, -2.4F)).mul(16F);
		assertEquals(-1.88F, grip.x, 0.15F, "the pistol grip in arm space x: " + grip);
		assertEquals(8.83F, grip.y, 0.15F, "and y: " + grip);
		// the barrel (G's -X) points along the arm toward the hand, a little in: arm +Y, as vanilla's real crossbow does
		Vector3f front = m.transformDirection(new Vector3f(-1F, 0F, 0F)).normalize();
		assertTrue(front.y > 0.9F, "the barrel runs down the arm's length: " + front);
	}

	@Test
	void theHandFrameIsTheGripFrameWhereNothingNeedsMoving() {
		ItemDisplayContext fp = ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, tp = ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
		for (String form : new String[] {"spear", "mace", "trident", "crossbow", "claws"}) {
			assertMatrix(ArmTransforms.gripFrame(ArmTransforms.Set.of(form, false), new Matrix4f()), ArmTransforms.handFrame(fp, form, false, false, new Matrix4f()),
					form + " in first person");
		}
		// the old sets, and the trident raised to throw, keep their own frames
		assertMatrix(ArmTransforms.grip(new Matrix4f()), ArmTransforms.handFrame(tp, "katana", false, false, new Matrix4f()), "sword");
		assertMatrix(ArmTransforms.bowGrip(new Matrix4f()), ArmTransforms.handFrame(tp, "bow", true, false, new Matrix4f()), "bow");
		assertMatrix(ArmTransforms.gripFrame(ArmTransforms.Set.TRIDENT, new Matrix4f()), ArmTransforms.handFrame(tp, "trident", false, true, new Matrix4f()),
				"trident throwing");
	}

	@Test
	void leftHandIsTheRightHandMirrored() {
		// the stored lefthand values with the fix give the right hand's pose mirrored across x: what the fix is for
		for (boolean bow : new boolean[] {false, true}) {
			for (ItemDisplayContext[] pair : new ItemDisplayContext[][] {
					{ItemDisplayContext.THIRD_PERSON_LEFT_HAND, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND},
					{ItemDisplayContext.FIRST_PERSON_LEFT_HAND, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND}}) {
				Matrix4f left = applied(ArmTransforms.forContext(pair[0], bow), pair[0]);
				Matrix4f right = applied(ArmTransforms.forContext(pair[1], bow), pair[1]);
				// the item's own centre goes to mirrored places
				Vector3f l = left.transformPosition(new Vector3f(0.5F, 0.5F, 0.5F));
				Vector3f r = right.transformPosition(new Vector3f(0.5F, 0.5F, 0.5F));
				assertEquals(-r.x, l.x, 1e-5F, "mirrored x, bow " + bow + " " + pair[0]);
				assertEquals(r.y, l.y, 1e-5F);
				assertEquals(r.z, l.z, 1e-5F);
			}
		}
	}

	@Test
	void everythingElseIsTheIdentity() {
		for (ItemDisplayContext ctx : ItemDisplayContext.values()) {
			if (HANDS.contains(ctx)) continue;
			assertSame(ItemTransform.NO_TRANSFORM, ArmTransforms.forContext(ctx, false), ctx.getSerializedName());
			assertSame(ItemTransform.NO_TRANSFORM, ArmTransforms.forContext(ctx, true), ctx.getSerializedName());
		}
	}

	@Test
	void gripFrameSitsOnTheSwordSprite() {
		Matrix4f g = ArmTransforms.grip(new Matrix4f());
		// the origin on the grip pixel, in the middle of the sprite's depth
		Vector3f o = g.transformPosition(new Vector3f());
		assertEquals(3.5F / 16F, o.x, 1e-6F);
		assertEquals(3.5F / 16F, o.y, 1e-6F);
		assertEquals(0.5F, o.z, 1e-6F);
		// +Y along the blade's diagonal, in pixels: 12·√2 px up the blade is the sprite's tip pixel (15.5, 15.5)
		Vector3f tip = g.transformPosition(new Vector3f(0F, 12F * (float) Math.sqrt(2.0), 0F)).mul(16F);
		assertEquals(15.5F, tip.x, 1e-4F);
		assertEquals(15.5F, tip.y, 1e-4F);
		// +X (the edge) toward the sprite's lower right, +Z out of the sprite toward its front
		Vector3f edge = g.transformDirection(new Vector3f(1F, 0F, 0F)).normalize();
		assertEquals((float) Math.sqrt(0.5), edge.x, 1e-6F);
		assertEquals(-(float) Math.sqrt(0.5), edge.y, 1e-6F);
		Vector3f normal = g.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
		assertEquals(1F, normal.z, 1e-6F);
	}

	@Test
	void guiFitPutsTheGripOnTheDiagonal() {
		Matrix4f m = ArmTransforms.gui(0.70F, 3.9F, new Matrix4f());
		Vector3f o = m.transformPosition(new Vector3f()).mul(16F);
		assertEquals(3.9F, o.x, 1e-5F);
		assertEquals(3.9F, o.y, 1e-5F);
		// 10 form pixels up the blade is 7 item pixels further along the diagonal
		float[] span = ArmTransforms.guiSpan(0.70F, 3.9F, List.of(new Vector3f(0F, 0F, 0F), new Vector3f(0F, 10F, 0F)));
		assertEquals(3.9F, span[0], 1e-4F);
		assertEquals(3.9F + 7F * (float) Math.sqrt(0.5), span[1], 1e-4F);
		// a turntable at rest is the plain fit
		Matrix4f turned = ArmTransforms.guiTurned(0.70F, 3.9F, 0F, 0F, 1F, new Matrix4f());
		assertMatrix(m, turned, "turntable at rest");
	}

	@Test
	void placeholderFitsTheSlot() {
		// the stand-in rod every unbuilt form is drawn as keeps inside the slot's 1..15 along the diagonal
		PlaceholderGeometry rod = new PlaceholderGeometry("whip");
		List<Vector3f> corners = new java.util.ArrayList<>();
		rod.extents(v -> corners.add(new Vector3f(v)));
		float[] span = ArmTransforms.guiSpan(rod.guiScale(), rod.guiGripPx(), corners);
		assertTrue(span[0] >= 1F, "butt inside the slot: " + span[0]);
		assertTrue(span[1] <= 15F, "tip inside the slot: " + span[1]);
	}
}
