package dev.morphreborn.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Where a lent fin or tail ends up on the main torso (rig, 2026-11-21): the geometry of HybridMorph.Graft on
 * hand-built boxes, in the frame the graft works in (y up, -z the front, the pose frame of a model drawn through scale(-1, -1, 1)).
 */
class HybridMorphGraftTest {
	private static final float SET_IN = 0.03F;

	/** A model cube in pixels (the model's y is down). */
	private static ModelPart.Cube cube(float x, float y, float z, float w, float h, float d) {
		return new ModelPart.Cube(0, 0, x, y, z, w, h, d, 0F, 0F, 0F, false, 32F, 32F, EnumSet.allOf(Direction.class));
	}

	/** A part drawn as the renderer draws it: through the model's (-1, -1, 1) axes, then the part's own offset in pixels and its x turn. */
	private static HybridMorph.Cube part(ModelPart.Cube cube, float px, float py, float pz, float xRot) {
		Matrix4f pose = new Matrix4f().scale(-1F, -1F, 1F).translate(px / 16F, py / 16F, pz / 16F).rotateX(xRot);
		return new HybridMorph.Cube(pose, pose.get3x3(new Matrix3f()), cube);
	}

	private static float[] range(Matrix4f move, HybridMorph.Cube c, int axis) {
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		Matrix4f m = new Matrix4f(move).mul(c.pose());
		ModelPart.Cube k = c.cube();
		for (int corner = 0; corner < 8; corner++) {
			Vector3f p = m.transformPosition(((corner & 1) == 0 ? k.minX : k.maxX) / 16F, ((corner & 2) == 0 ? k.minY : k.maxY) / 16F,
					((corner & 4) == 0 ? k.minZ : k.maxZ) / 16F, new Vector3f());
			float v = axis == 0 ? p.x : axis == 1 ? p.y : p.z;
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		return new float[] {lo, hi};
	}

	private static HybridMorph.Graft graft(float[][] own, float[][] main, boolean shell) {
		return graft(own, main, shell, false, null);
	}

	private static HybridMorph.Graft graft(float[][] own, float[][] main, boolean shell, boolean tadpole, float[][] ownTail) {
		return graft(own, main, shell, tadpole, ownTail, null, false, false);
	}

	/** {@code tip}: the box of the last segment of the ender dragon's tail chain; {@code lift}: wings are lifted as a vex's are on a big body; {@code vex}: the lender is a vex. */
	private static HybridMorph.Graft graft(float[][] own, float[][] main, boolean shell, boolean tadpole, float[][] ownTail, float[][] tip, boolean lift, boolean vex) {
		Matrix4f identity = new Matrix4f();
		// a tadpole's whole box runs out to the end of its tail
		float[][] all = !tadpole ? main : new float[][] {main[0], {Math.max(main[1][0], ownTail[1][0]), Math.max(main[1][1], ownTail[1][1]), Math.max(main[1][2], ownTail[1][2])}};
		return new HybridMorph.Graft(own, main, identity, identity, 1F, own, all, Float.MAX_VALUE, Float.MAX_VALUE, 1F, shell, lift, tadpole, ownTail, tip, 0F, vex);
	}

	@Test
	void aDorsalFinStandsOnTheBackOfAStandingBody() {
		// a fish's body, 0.25 x 0.3 x 1.0, and a flat fin over its back (3 pixels high, 8 long); a person's torso
		float[][] own = {{-0.125F, 0F, -0.5F}, {0.125F, 0.3F, 0.5F}};
		float[][] person = {{-0.25F, 0.75F, -0.125F}, {0.25F, 1.5F, 0.125F}};
		HybridMorph.Cube fin = part(cube(0F, -8F, -4F, 0F, 3.2F, 8F), 0F, 0F, 0F, 0F);
		HybridMorph.Graft graft = graft(own, person, false);
		Matrix4f move = graft.dorsalMove(List.of(fin), 1F);
		assertNotNull(move);
		float[] z = range(move, fin, 2);
		assertEquals(0.125F - SET_IN, z[0], 0.004F, "its base is in the back plane, not off it");
		assertTrue(z[1] > z[0] + 0.1F, "and it stands out of the back");
	}

	@Test
	void aSweptDorsalFinStandsUprightOnALyingBodyAndIsNoTallerThanIt() {
		// a dolphin's back fin: a 1 x 4 x 5 block turned 60 degrees about its part's x axis, a parallelogram 30 degrees off upright
		float[][] own = {{-0.1F, 0F, -0.5F}, {0.1F, 0.25F, 0.5F}};
		float[][] salmon = {{-0.09375F, 0F, -0.5F}, {0.09375F, 0.3125F, 0.5F}};
		HybridMorph.Cube fin = part(cube(-0.5F, 0F, 8F, 1F, 4F, 5F), 0F, 0F, 0F, (float) (Math.PI / 3));
		HybridMorph.Graft graft = graft(own, salmon, false);
		Matrix4f move = graft.dorsalMove(List.of(fin), 1F);
		assertNotNull(move);
		float[] y = range(move, fin, 1);
		assertEquals(0.3125F - SET_IN, y[0], 0.004F, "its base is on the top of the body");
		// upright: as tall as its 5 pixel side (3 quarters of the body's thickness at most), not the 6.3 pixels a leaning fin reaches
		float height = y[1] - y[0];
		assertTrue(height <= 0.75F * 0.3125F + 0.004F, "no taller than 0.75 of the body's thickness: " + height);
		assertTrue(height >= 0.75F * 0.3125F - 0.004F, "and at that height, upright, not leaning: " + height);
	}

	@Test
	void aTailCentredOnItsOwnBodyIsCentredOnAWideMainsSpine() {
		// a fox's brush: its pivot 4 pixels off the middle, its block 4 pixels the other way; the main torso is a wither's 20 pixel shoulder bar
		float[][] own = {{-0.19F, 0F, -0.4F}, {0.19F, 0.4F, 0.4F}};
		float[][] wither = {{-0.625F, 0.5F, -0.1F}, {0.625F, 1.5F, 0.1F}};
		HybridMorph.Cube tail = part(cube(2F, 0F, 0F, 4F, 4F, 8F), -4F, 15F, 7F, 0F);
		HybridMorph.Graft graft = graft(own, wither, false);
		Vector3f joint = tail.pose().transformPosition(new Vector3f());
		Matrix4f move = graft.move(joint, "tail", 1F);
		Matrix4f touched = graft.touch(move, List.of(tail), List.of(tail));
		float[] x = range(touched, tail, 0);
		assertEquals(0F, (x[0] + x[1]) / 2F, 0.01F, "the tail hangs on the spine, not at the shoulder tip");
	}

	@Test
	void aTailOnAShellBodyIsOnTheClosedEndAndPointsAway() {
		float[][] own = {{-0.1F, 0F, -0.4F}, {0.1F, 0.3F, 0.4F}};
		float[][] shell = {{-0.3F, 0F, -0.3F}, {0.3F, 0.6F, 0.3F}};
		HybridMorph.Cube tail = part(cube(-1F, 0F, 0F, 2F, 2F, 8F), 0F, 8F, 6F, 0F);
		HybridMorph.Graft graft = graft(own, shell, true);
		Vector3f joint = tail.pose().transformPosition(new Vector3f());
		Matrix4f touched = graft.touch(graft.move(joint, "tail", 1F), List.of(tail), List.of(tail));
		float[] z = range(touched, tail, 2);
		assertEquals(-0.3F + SET_IN, z[1], 0.01F, "its root is in the closed end of the shell");
		assertTrue(z[0] < -0.3F - 0.1F, "and it runs away from the shell, not over the open end");
	}

	@Test
	void aSalmonsBackFinIsTheTailAtTheRearOfAStandingBody() {
		float[][] own = {{-0.094F, 0F, -0.45F}, {0.094F, 0.31F, 0.55F}};
		float[][] person = {{-0.25F, 0.75F, -0.125F}, {0.25F, 1.5F, 0.125F}};
		HybridMorph.Graft graft = graft(own, person, false);
		// its pivot 8 pixels behind the body's centre line, at the body's rear edge
		Vector3f at = graft.place(new Vector3f(0F, 0.15F, 0.55F), "back_fin", 1F);
		assertEquals(0.125F - SET_IN, at.z, 1e-4F, "at the person's back");
		assertTrue(at.y >= 0.75F + 0.15F * 0.75F - 1e-4F, "from the small of the back up, not at the bottom of the torso");
	}

	@Test
	void aBackFinAtTheRearOfItsBodyIsATailAndOneWithinItIsNot() {
		float[][] own = {{-0.1F, 0F, -0.5F}, {0.1F, 0.25F, 0.5F}};
		float[][] person = {{-0.25F, 0.75F, -0.125F}, {0.25F, 1.5F, 0.125F}};
		HybridMorph.Graft graft = graft(own, person, true);
		// a salmon's tail is named back_fin, at the end of its body; a dolphin's dorsal is the same name over the middle of its back
		assertTrue(graft.rearFin("back_fin", new Vector3f(0F, 0.1F, 0.5F)));
		assertTrue(!graft.rearFin("back_fin", new Vector3f(0F, 0.2F, 0F)));
		assertTrue(!graft.rearFin("dorsal_fin", new Vector3f(0F, 0.1F, 0.5F)));
	}

	@Test
	void aDorsalFinOnATorsoWithNoThicknessIsNotShrunkToNothing() {
		float[][] own = {{-0.1F, 0F, -0.5F}, {0.1F, 0.25F, 0.5F}};
		float[][] flat = {{-0.25F, 0F, -0.5F}, {0.25F, 0F, 0.5F}};
		HybridMorph.Cube fin = part(cube(0F, -8F, -4F, 0F, 3.2F, 8F), 0F, 0F, 0F, 0F);
		Matrix4f move = graft(own, flat, false).dorsalMove(List.of(fin), 1F);
		if (move != null) assertTrue(Math.abs(move.getScale(new Vector3f()).x) > 0.5F, "scale " + move.getScale(new Vector3f()).x);
	}

	@Test
	void aLentTailOnTheDragonIsRootedOnTopOfItsFirstTailSegment() {
		// the dragon's trunk is 1.5 x 1.5 x 4 and ends inside its first tail segment, a 0.625 cube behind it (round 5, ender dragon + horse family)
		float[][] own = {{-0.1F, 0.4F, -0.5F}, {0.1F, 1F, 0.5F}};
		float[][] trunk = {{-0.75F, 0F, -2F}, {0.75F, 1.5F, 2F}};
		float[][] segment = {{-0.3125F, 0.5F, 1.8F}, {0.3125F, 1.125F, 2.4F}};
		HybridMorph.Graft graft = graft(own, trunk, false, false, segment);
		Vector3f at = graft.place(new Vector3f(0F, 0.9F, 0.55F), "tail", 1F);
		assertEquals(1.125F, at.y, 1e-4F, "on the top of the segment");
		assertEquals(2.1F - SET_IN, at.z, 1e-4F, "in the middle of it along the tail, not at the trunk's rear face inside it");
		assertEquals(0F, at.x, 1e-4F);
		// the tail meets that plane: the rear face of the trunk is not where it is held to
		assertEquals(2.1F, graft.back(), 1e-4F);
	}

	@Test
	void aLentTailOnATadpoleIsRootedAtTheHeightOfItsTailAndWhereItIsSwung() {
		// a tadpole's body 3 x 2 x 3 pixels and its flat tail 2 pixels tall behind it, swung to one side at the tip
		float[][] own = {{-0.1F, 0.2F, -0.4F}, {0.1F, 0.7F, 0.4F}};
		float[][] body = {{-0.094F, 0.06F, -0.19F}, {0.094F, 0.19F, 0F}};
		float[][] tail = {{0F, 0.06F, 0F}, {0.1F, 0.19F, 0.44F}};
		HybridMorph.Graft graft = graft(own, body, false, true, tail);
		Vector3f at = graft.place(new Vector3f(0F, 0.7F, 0.4F), "tail", 1F);
		assertEquals(0.125F, at.y, 1e-4F, "level with the slab, not the torso's third");
		assertEquals(0.1F, at.x, 1e-4F, "at the swung end");
		assertEquals(0.44F - SET_IN, at.z, 1e-4F);
	}

	@Test
	void aDorsalFinStaysADorsalWhenTheBodysYawMovesItOffTheMidline() {
		// a salmon's body 3 pixels across; its fin 0.04 off the middle, 0.35 along the body: the yaw of 5.6 degrees does that, and a 0.03 test lost it
		float[][] salmon = {{-0.094F, 0F, -0.5F}, {0.094F, 0.31F, 0.5F}};
		HybridMorph.Cube fin = part(cube(0F, -8F, -4F, 0F, 3.2F, 8F), -0.64F, 0F, 5.6F, 0F);
		assertNotNull(graft(salmon, salmon, false).dorsalMove(List.of(fin), 1F), "still the dorsal");
		// a fin that is a side fin (0.1 off the middle at the same place) is not
		HybridMorph.Cube side = part(cube(0F, -8F, -4F, 0F, 3.2F, 8F), -1.6F, 0F, 5.6F, 0F);
		assertEquals(null, graft(salmon, salmon, false).dorsalMove(List.of(side), 1F), "a pectoral is not");
	}

	@Test
	void aFinOnAWideStandingMainIsRootedInFromTheEndOfItsShoulders() {
		float[][] own = {{-0.1F, 0F, -0.4F}, {0.1F, 0.25F, 0.4F}};
		float[][] wither = {{-0.625F, 0.5F, -0.1F}, {0.625F, 1.5F, 0.1F}};
		Vector3f at = graft(own, wither, false).place(new Vector3f(-0.2F, 0.1F, 0F), "left_fin", 1F);
		assertEquals(-0.625F + 0.25F, at.x, 1e-4F, "a fifth of the bar in, over the ribs");
		// a person's torso (0.5 wide) keeps the old root
		float[][] person = {{-0.25F, 0.75F, -0.125F}, {0.25F, 1.5F, 0.125F}};
		Vector3f old = graft(own, person, false).place(new Vector3f(-0.2F, 0.1F, 0F), "left_fin", 1F);
		assertEquals(-0.25F - 0.1F, old.x, 1e-4F);
		// a giant's 3 block torso: a fifth would be 0.6 of fin buried in its flank, so the inset stops at WIDE_FIN_INSET_MAX
		float[][] giant = {{-1.5F, 1.5F, -0.75F}, {1.5F, 4.5F, 0.75F}};
		Vector3f deep = graft(own, giant, false).place(new Vector3f(-0.2F, 0.1F, 0F), "left_fin", 1F);
		assertEquals(-1.5F + LentHeadSize.WIDE_FIN_INSET_MAX, deep.x, 1e-4F, "in by 0.3, not 0.6");
	}

	@Test
	void aPectoralFinOnABigCubeIsRootedAtTheMiddleOfTheFlank() {
		float[][] own = {{-0.1F, 0F, -0.5F}, {0.1F, 0.25F, 0.5F}};
		float[][] elder = {{-1.175F, 0F, -1.175F}, {1.175F, 2.35F, 1.175F}};
		Vector3f at = graft(own, elder, false).place(new Vector3f(-0.15F, 0.1F, -0.45F), "left_fin", 1F);
		assertEquals(0F, at.z, 1e-4F, "along the flank, not at its front corner");
		assertTrue(at.y >= 1.175F - 1e-4F, "at mid-height");
		// a small cube (a slime's 1 block) keeps the corner it had
		float[][] slime = {{-0.5F, 0F, -0.5F}, {0.5F, 1F, 0.5F}};
		assertEquals(-0.1F, graft(own, slime, false).place(new Vector3f(-0.15F, 0.1F, -0.45F), "left_fin", 1F).z, 1e-4F);
	}

	@Test
	void aHangingWingOnALowLyingTorsoIsRootedAtItsBackLine() {
		float[][] parrot = {{-0.1F, 0F, -0.1F}, {0.1F, 0.3F, 0.1F}};
		float[][] frog = {{-0.2F, 0F, -0.25F}, {0.2F, 0.19F, 0.25F}};
		HybridMorph.Graft graft = graft(parrot, frog, false);
		Vector3f at = graft.place(new Vector3f(-0.1F, 0.2F, 0F), "left_wing", 1F, HybridMorph.Graft.HANGING_WING_CLEAR, true);
		assertEquals(0.19F - SET_IN, at.y, 1e-4F, "it hangs from the back, not from two thirds of a 3 pixel torso");
		// a wing that does not hang keeps the share
		Vector3f flat = graft.place(new Vector3f(-0.1F, 0.2F, 0F), "left_wing", 1F);
		assertTrue(flat.y < 0.19F - SET_IN + 1e-4F);
		// the flag says it hangs, not the distance: the same clearance with the flag off is the share
		Vector3f notHanging = graft.place(new Vector3f(-0.1F, 0.2F, 0F), "left_wing", 1F, HybridMorph.Graft.HANGING_WING_CLEAR, false);
		assertEquals(flat.y, notHanging.y, 1e-4F);
		// and a torso taller than a pixel row keeps it for the hanging kind too
		float[][] ravager = {{-0.5F, 0F, -0.6F}, {0.5F, 1.1F, 0.6F}};
		Vector3f big = graft(parrot, ravager, false).place(new Vector3f(-0.1F, 0.2F, 0F), "left_wing", 1F, HybridMorph.Graft.HANGING_WING_CLEAR, true);
		assertTrue(big.y < 1.1F - SET_IN - 0.05F);
	}

	@Test
	void aStripTailOnAShellIsClearOfTheClosedFace() {
		float[][] own = {{-0.1F, 0F, -0.4F}, {0.1F, 0.3F, 0.4F}};
		float[][] shell = {{-0.3F, 0F, -0.3F}, {0.3F, 0.6F, 0.3F}};
		// a camel's tail: 3 x 14 pixels, no thickness
		HybridMorph.Cube tail = part(cube(-1.5F, 0F, 0F, 3F, 14F, 0F), 0F, 8F, 6F, 0F);
		HybridMorph.Graft graft = graft(own, shell, true);
		Vector3f joint = tail.pose().transformPosition(new Vector3f());
		Matrix4f touched = graft.touch(graft.move(joint, "tail", 1F), List.of(tail), List.of(tail));
		float[] z = range(touched, tail, 2);
		assertTrue(z[1] <= -0.3F - 0.0199F, "its plane stands 2 centimetres out of the face, not coplanar with it: " + z[1]);
	}

	@Test
	void aLentTailThatHangsIsRootedAtTheEndOfTheDragonsTailChainAndOneThatRunsBackIsNot() {
		// round 6: a horse's tail rooted on the top of the first 10 pixel segment pointed down through it and only its far end showed
		float[][] own = {{-0.1F, 0.4F, -0.5F}, {0.1F, 1F, 0.5F}};
		float[][] trunk = {{-0.75F, 0F, -2F}, {0.75F, 1.5F, 2F}};
		float[][] first = {{-0.3125F, 0.5F, 1.8F}, {0.3125F, 1.125F, 2.4F}};
		float[][] tip = {{-0.3F, 0.2F, 9.9F}, {0.3F, 0.9F, 10.5F}};
		HybridMorph.Graft graft = graft(own, trunk, false, false, first, tip, false, false);
		// a horse's tail: 3 x 14 x 4 pixels, turned back 30 degrees at its pivot
		HybridMorph.Cube horse = part(cube(-1.5F, 0F, 0F, 3F, 14F, 4F), 0F, 6F, 14F, (float) (Math.PI / 6));
		assertTrue(graft.hangs(List.of(horse)), "a horse's tail hangs");
		Vector3f at = graft.place(new Vector3f(0F, 0.9F, 0.55F), "tail", 1F, HybridMorph.Graft.CLEAR, true);
		assertEquals(0F, at.x, 1e-4F);
		assertEquals(0.55F, at.y, 1e-4F, "level with the middle of the last segment");
		assertEquals(10.5F - SET_IN, at.z, 1e-4F, "on its rear face, hanging free past the chain's end");
		// a fox's brush runs back: 4 x 4 x 8 pixels, level
		HybridMorph.Cube fox = part(cube(-2F, 0F, 0F, 4F, 4F, 8F), 0F, 6F, 14F, 0F);
		assertTrue(!graft.hangs(List.of(fox)), "a brush runs back");
		// an armadillo's tail is a 1 x 6 x 1 box tipped 29 degrees: tall enough for the ratio but 0.36 of a block, too short to hang off the dragon's tail;
		// and a wolf-sized 2 x 8 x 2 tail, which only a pose change away from the ratio, is under the height floor too
		HybridMorph.Cube armadillo = part(cube(-0.5F, 0F, -0.5F, 1F, 6F, 1F), 0F, 6F, 14F, (float) Math.toRadians(29));
		assertTrue(!graft.hangs(List.of(armadillo)), "an armadillo's short tail does not hang");
		HybridMorph.Cube wolf = part(cube(-1F, 0F, 0F, 2F, 7.5F, 2F), 0F, 6F, 14F, 0F);
		assertTrue(!graft.hangs(List.of(wolf)), "a wolf's tail does not hang");
		Vector3f top = graft.place(new Vector3f(0F, 0.9F, 0.55F), "tail", 1F, HybridMorph.Graft.CLEAR, false);
		assertEquals(1.125F, top.y, 1e-4F, "it keeps the top of the first segment");
		assertEquals(2.1F - SET_IN, top.z, 1e-4F);
	}

	@Test
	void onlyAPectoralFinIsSizedAndRootedAsOneAndTheDorsalsKeepTheStripFinRules() {
		for (String pectoral : new String[] {"left_fin", "right_fin", "left_blue_fin", "right_blue_fin", "left_front_fin", "right_back_fin"}) {
			assertTrue(HybridMorph.sideFin(pectoral), pectoral + " is a pectoral");
		}
		// a cod's and a tropical fish's dorsal, a salmon's and a pufferfish's two, a dolphin's, its tail's, and the tail (fin) of a fish
		for (String other : new String[] {"top_fin", "top_front_fin", "top_back_fin", "top_middle_fin", "back_fin", "tail_fin", "tail"}) {
			assertTrue(!HybridMorph.sideFin(other), other + " is not");
		}
	}

	@Test
	void aSquidsMantleIsABlockAndAPectoralFinOnItIsRootedAtTheMiddleOfItsFlank() {
		// round 6: the squid's 12 x 16 x 12 pixel mantle is outside the cube's window, and the cod's pectorals hung off its lower front corners to the floor
		float[][] squid = {{-0.375F, 0F, -0.375F}, {0.375F, 1F, 0.375F}};
		float[][] cow = {{-0.375F, 0.5F, -0.6F}, {0.375F, 1.1F, 0.6F}};
		assertTrue(!HybridMorph.Graft.cubic(squid) && HybridMorph.Graft.blocky(squid), "a block");
		assertTrue(!HybridMorph.Graft.blocky(cow), "a cow's torso is not");
		float[][] cod = {{-0.0625F, 0F, -0.25F}, {0.0625F, 0.25F, 0.25F}};
		// not a goat's torso (0.8 as tall as it is deep); a nautilus's shell counts through the shell, its box being 0.8 as wide as it is tall
		assertTrue(!HybridMorph.Graft.blocky(new float[][] {{-0.345F, 0F, -0.53F}, {0.345F, 0.87F, 0.53F}}));
		assertTrue(graft(cod, new float[][] {{-0.4375F, 0F, -0.6F}, {0.4375F, 1.1F, 0.6F}}, true).blockish(), "a shell");
		assertTrue(graft(cod, squid, false).blockish() && !graft(cod, cow, false).blockish());
		Vector3f at = graft(cod, squid, false).place(new Vector3f(-0.0625F, 0.05F, -0.2F), "left_fin", 1F);
		assertEquals(0.5F, at.y, 1e-4F, "at mid-height, not the floor");
		assertEquals(-0.375F + SET_IN, at.x, 1e-4F, "on the flank");
		// and the same for a right fin, set, not raised: a root that came out above the middle (the front of a fish is the top of a block that stands) comes down
		Vector3f high = graft(cod, squid, false).place(new Vector3f(0.0625F, 0.25F, -0.25F), "right_fin", 1F);
		assertEquals(0.5F, high.y, 1e-4F);
		assertEquals(0.375F - SET_IN, high.x, 1e-4F);
	}

	@Test
	void aTailOnACubeThatIsNotSwappedIsStillRootedAtTheMiddleOfItsRearFace() {
		// round 6: a dolphin's fluke on a magma cube or a sulfur cube whose box counts as lying (no swap of the axes) lay on the floor
		float[][] dolphin = {{-0.25F, 0F, -0.4F}, {0.25F, 0.44F, 0.4F}};
		float[][] cube = {{-0.5F, 0F, -0.5F}, {0.5F, 0.99F, 0.5F}};
		assertTrue(!HybridMorph.Graft.stands(cube) && HybridMorph.Graft.cubic(cube));
		Vector3f at = graft(dolphin, cube, false).place(new Vector3f(0F, 0.05F, 0.4F), "tail", 1F);
		assertTrue(at.y >= 0.495F - 1e-4F, "mid-height: " + at.y);
	}

	@Test
	void aDorsalFinOnACubeIsAFinAndNotAPostHalfTheCubeTall() {
		// round 6: a dolphin's dorsal grown to 0.6 of the cube by its length stood half a cube over the top, 1 pixel thick
		float[][] dolphin = {{-0.25F, 0F, -0.5F}, {0.25F, 0.44F, 0.5F}};
		float[][] cube = {{-0.5F, 0F, -0.5F}, {0.5F, 1F, 0.5F}};
		HybridMorph.Cube fin = part(cube(-0.5F, 0F, 8F, 1F, 4F, 5F), 0F, 0F, 0F, (float) (Math.PI / 3));
		Matrix4f move = graft(dolphin, cube, false).dorsalMove(List.of(fin), 3F);
		assertNotNull(move);
		float[] y = range(move, fin, 1);
		assertTrue(y[1] - y[0] <= LentHeadSize.CUBE_DORSAL_SHARE + 0.01F, "no more than 0.3 of the cube's height: " + (y[1] - y[0]));
		assertEquals(1F - SET_IN, y[0], 0.01F, "its base on the top");
	}

	@Test
	void aVexsWingsOnALyingMainAreLaidOutAsFoldedWingsRunningBackAlongIt() {
		// round 7: the wing plane laid out from its own axes (Graft.foldedWing), not turned by a fixed quarter: normal out to the side, the 8 pixel edge back, the 5 pixel edge up
		// (HybridMorphRealMainsTest runs the same on the real vex, ravager and sniffer)
		float[][] vex = {{-0.1F, 0.3F, -0.06F}, {0.1F, 0.6F, 0.06F}};
		float[][] ravager = {{-0.6F, 0.8F, -1F}, {0.6F, 1.9F, 1F}};
		HybridMorph.Graft graft = graft(vex, ravager, false, false, null, null, true, true);
		for (int side = -1; side <= 1; side += 2) {
			boolean left = side < 0;
			float yaw = 1.0995574F;
			// body: offset (0, 17.5, 0), x turn 9 degrees; wing: offset (+-0.5, 1, 1), the vex model's rest pose (x 27, y +-63, z -+27 degrees)
			Matrix4f pose = new Matrix4f().scale(-1F, -1F, 1F).translate(0F, 17.5F / 16F, 0F).rotateX((float) (Math.PI / 20))
					.translate((left ? 0.5F : -0.5F) / 16F, 1F / 16F, 1F / 16F).rotateZYX(left ? -0.47123888F : 0.47123888F, left ? yaw : -yaw, 0.47123888F);
			Vector3f joint = pose.transformPosition(new Vector3f());
			Matrix4f move = graft.foldedWing(joint, pose, left ? "left_wing" : "right_wing", 1F, HybridMorph.Graft.VEX_LIFT_ROLL);
			Matrix4f placed = new Matrix4f(move).mul(pose);
			Vector3f normal = placed.transformDirection(new Vector3f(1F, 0F, 0F)).normalize();
			Vector3f span = placed.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
			Vector3f tall = placed.transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
			assertTrue(Math.abs(normal.x) > 0.75F, "its face looks out to the side: " + normal);
			assertTrue(span.z > 0.95F, "its long edge runs straight back along the body: " + span);
			assertTrue(tall.y > 0.75F, "its short edge stands up: " + tall);
			assertTrue(tall.x * side > 0.4F, "leaning out of the back, not over it: " + tall + " side " + side);
			// the root is in the top fifth of the flank
			assertTrue(placed.transformPosition(new Vector3f()).y >= 1.9F - 0.2F * 1.1F - 1e-3F);
		}
	}

	@Test
	void aSquidOrAGuardiansLyingBlockIsBlockishAndAPolarBearsBodyOfTheSameShapeIsNot() {
		// rig, 2026-11-22: the elder guardian's core and the mantle of a squid on land are 12 x 12 x 16 pixels (0.75 x 0.75 x 1 blocks, h/d 0.75), outside both the cube and the upright mantle
		float[][] block = {{-0.375F, 0F, -0.5F}, {0.375F, 0.75F, 0.5F}};
		assertTrue(!HybridMorph.Graft.cubic(block) && !HybridMorph.Graft.blocky(block));
		float[][] cod = {{-0.0625F, 0F, -0.25F}, {0.0625F, 0.25F, 0.25F}};
		Matrix4f identity = new Matrix4f();
		HybridMorph.Graft squid = new HybridMorph.Graft(cod, block, identity, identity, 1F, cod, block, Float.MAX_VALUE, Float.MAX_VALUE, 1F, false, false, false, null, null, 0F, false, true);
		assertTrue(squid.blockish(), "a squid or guardian's block takes the side-fin rules");
		assertTrue(!graft(cod, block, false).blockish(), "a body of the same shape that is not one of those four does not (a polar bear's 14 x 14 x 19 body)");
		// the cod's pectoral joins its body at the front, bottom: at the middle of the flank on the block, at mid-height and mid-length, not at the lower front corner
		Vector3f at = squid.place(new Vector3f(-0.0625F, 0.05F, -0.2F), "left_fin", 1F);
		assertEquals(0.375F, at.y, 1e-4F, "mid-height");
		assertEquals(-0.375F + SET_IN, at.x, 1e-4F, "on the flank");
		assertEquals(0F, at.z, 1e-4F, "half way along it, not at the tip (the front corner)");
		Vector3f right = squid.place(new Vector3f(0.0625F, 0.05F, -0.2F), "right_fin", 1F);
		assertEquals(0.375F - SET_IN, right.x, 1e-4F);
		assertEquals(0F, right.z, 1e-4F);
		// the mantle upright in the water keeps its own rule, and a pitched one (up to 20 pixels either way) is a block too
		assertTrue(HybridMorph.Graft.lyingBlockBox(new float[][] {{-0.375F, 0F, -0.375F}, {0.375F, 1F, 0.375F}}));
		assertTrue(HybridMorph.Graft.lyingBlockBox(new float[][] {{-0.375F, 0F, -0.62F}, {0.375F, 1.24F, 0.62F}}));
		assertTrue(!HybridMorph.Graft.lyingBlockBox(new float[][] {{-0.375F, 0.5F, -0.6F}, {0.375F, 1.1F, 0.6F}}), "a cow's torso is not");
	}

	/** A squid's or guardian's graft with the lying-block flag, {@code main} the torso box (rig 2026-11-22: the real elder guardian's core is 12 x 12 x 16 pixels at 2.35). */
	private static HybridMorph.Graft blockGraft(float[][] own, float[][] main) {
		Matrix4f identity = new Matrix4f();
		return new HybridMorph.Graft(own, main, identity, identity, 1F, own, main, Float.MAX_VALUE, Float.MAX_VALUE, 1F, false, false, false, null, null, 0F, false, true);
	}

	@Test
	void theElderGuardiansLyingBlockTakesTheCubesTailAndDorsalRulesAndTheUprightMantleKeepsItsOwn() {
		// the real elder guardian's core is 12 x 12 x 16 pixels at 2.35: h/d 0.75, so cubic() leaves it out, and the rules that were settled on a hand-built cube
		// (the tail from the middle of the rear face, the dorsal held to a share of the height, the longer strip fin cap) did not run on the real mob
		float s = 2.35F;
		float[][] block = {{-0.375F * s, 0F, -0.5F * s}, {0.375F * s, 0.75F * s, 0.5F * s}};
		float[][] dolphin = {{-0.25F, 0F, -0.5F}, {0.25F, 0.44F, 0.5F}};
		assertTrue(!HybridMorph.Graft.cubic(block));
		HybridMorph.Graft elder = blockGraft(dolphin, block);
		assertTrue(elder.cubical(), "the lying block is a cube for those rules");
		assertTrue(!graft(dolphin, block, false).cubical(), "a polar bear's body of the same shape is not");
		Vector3f tail = elder.place(new Vector3f(0F, 0.05F, 0.4F), "tail", 1F);
		assertTrue(tail.y >= 0.5F * 0.75F * s - 1e-3F, "the tail leaves the middle of the rear face, not the floor: " + tail.y);
		HybridMorph.Cube fin = part(cube(-0.5F, 0F, 8F, 1F, 4F, 5F), 0F, 0F, 0F, (float) (Math.PI / 3));
		Matrix4f move = elder.dorsalMove(List.of(fin), 3F);
		assertNotNull(move);
		float[] y = range(move, fin, 1);
		assertTrue(y[1] - y[0] <= LentHeadSize.CUBE_DORSAL_SHARE * 0.75F * s + 0.01F, "the dorsal is held to a share of the block's height: " + (y[1] - y[0]));
		// the mantle upright in the water (h/d over 1) is not the cube: it keeps the rules its pairs were settled with
		float[][] upright = {{-0.375F, 0F, -0.375F}, {0.375F, 1.33F * 0.75F, 0.375F}};
		assertTrue(blockGraft(dolphin, upright).lyingBlocks() && !blockGraft(dolphin, upright).cubical());
	}

	@Test
	void aSquidsPectoralStaysOnTheMiddleOfItsFlankAcrossTheWholeSwing() {
		// the mantle pitches from lying (h/d 0.75) through the point where stands() flips (h/d 1) to upright (h/d 1.33): a side fin does not depend on that flip,
		// its root is on the flank at mid-height and mid-length in every pose, so it cannot jump as the squid tilts
		float[][] cod = {{-0.0625F, 0F, -0.25F}, {0.0625F, 0.25F, 0.25F}};
		for (float ratio : new float[] {0.75F, 0.95F, 1.05F, 1.33F}) {
			float[][] box = {{-0.375F, 0F, -0.375F / ratio}, {0.375F, 0.75F, 0.375F / ratio}};
			assertTrue(blockGraft(cod, box).lyingBlocks(), "h/d " + ratio);
			Vector3f at = blockGraft(cod, box).place(new Vector3f(-0.0625F, 0.05F, -0.2F), "left_fin", 1F);
			assertEquals(0.375F, at.y, 1e-4F, "mid-height at h/d " + ratio);
			assertEquals(0F, at.z, 1e-4F, "mid-length at h/d " + ratio);
			assertEquals(-0.375F + SET_IN, at.x, 1e-4F, "on the flank at h/d " + ratio);
		}
	}

	@Test
	void aStripTailThatIsTiltedAtRestOnAShellIsWhollyOutsideTheClosedFace() {
		// rig, 2026-11-22: the lent camel's posed strip is tilted about 5 degrees (0.076 block deep), so it was taken for a thick tail and its root end stayed 0.03 inside the face,
		// hidden down to the shell's floor line. A camel's tail, 3 x 14 pixels with no depth at rest, at the pivot the real run logged, on the nautilus's shell box
		float[][] own = {{-0.1F, 0F, -0.4F}, {0.1F, 0.3F, 0.4F}};
		float[][] shell = {{-0.4375F, 0.001F, -0.5F}, {0.4375F, 1.126F, 0.75F}};
		HybridMorph.Cube rest = part(cube(-1.5F, 0F, 0F, 3F, 14F, 0F), 0F, 8F, 6F, 0F);
		for (float tilt : new float[] {5F, -5F}) {
			HybridMorph.Cube posed = part(cube(-1.5F, 0F, 0F, 3F, 14F, 0F), 0F, 8F, 6F, (float) Math.toRadians(tilt));
			HybridMorph.Graft graft = graft(own, shell, true);
			Vector3f joint = posed.pose().transformPosition(new Vector3f());
			Matrix4f touched = graft.touch(graft.move(joint, "tail", 1F), List.of(posed), List.of(rest));
			float[] z = range(touched, posed, 2);
			float[] y = range(touched, posed, 1);
			assertTrue(z[1] <= -0.5F - 0.0199F, "all of it, root end included, stands out of the closed face: " + z[1]);
			assertTrue(y[0] >= 0.001F - 1e-3F && y[1] <= 1.126F + 1e-3F, "and it hangs over the face, not past the bottom of the shell: " + y[0] + " to " + y[1]);
			// its root is within a hair of the face for the lean the camel model has (+5, after the half turn the root end is the near one). The other lean puts the root end the strip's own 0.076 block off the face, still
			// wholly outside it (checked above): no lender has a strip tilted that way, and rooting it by the root would bury the far end, so it is left
			float rootGap = -0.5F - new Matrix4f(touched).mul(posed.pose()).transformPosition(new Vector3f()).z;
			if (tilt > 0) assertTrue(rootGap >= 0F && rootGap <= 0.03F, "the root is on the shell, tilted " + tilt + ": " + rootGap);
		}
		// a strip that stood well off the face is seated on it, not buried
		HybridMorph.Graft graft = graft(own, shell, true);
		Vector3f joint = rest.pose().transformPosition(new Vector3f());
		Matrix4f far = new Matrix4f().translate(0F, 0F, -0.3F).mul(graft.move(joint, "tail", 1F));
		assertTrue(range(graft.touch(far, List.of(rest), List.of(rest)), rest, 2)[1] <= -0.5F - 0.0199F, "never buried in the shell");
	}

	@Test
	void aStripTailOnAShellHangsOverTheFaceAndNotPastTheBottomOfTheShell() {
		// round 6: rooted a third of the way up the closed end, a strip longer than that hung below the shell. A camel's tail, 3 x 14 pixels, at 0.9
		float[][] own = {{-0.1F, 0F, -0.4F}, {0.1F, 0.3F, 0.4F}};
		float[][] shell = {{-0.45F, 0F, -0.6F}, {0.45F, 1.1F, 0.6F}};
		HybridMorph.Cube tail = part(cube(-1.5F, 0F, 0F, 3F, 14F, 0F), 0F, 8F, 6F, 0F);
		HybridMorph.Graft graft = graft(own, shell, true);
		Vector3f joint = tail.pose().transformPosition(new Vector3f());
		Matrix4f touched = graft.touch(graft.move(joint, "tail", 0.9F), List.of(tail), List.of(tail));
		float[] y = range(touched, tail, 1);
		assertTrue(y[0] >= -1e-3F, "not below the floor of the shell: " + y[0]);
		assertTrue(y[1] <= 1.1F + 1e-3F, "nor over its top: " + y[1]);
	}

	@Test
	void aDolphinsFlipperOnACubeStandsOutFromTheMiddleOfItsFlankAndDoesNotHangToTheFloor() {
		// round 6: the flipper (1 x 4 x 7 pixels, turned 120 degrees about z and 60 about x, so it hangs 30 degrees below level) lay along the floor of a magma cube
		float[][] dolphin = {{-0.25F, 0F, -0.5F}, {0.25F, 0.44F, 0.5F}};
		float[][] cube = {{-0.5F, 0F, -0.5F}, {0.5F, 1F, 0.5F}};
		Matrix4f pose = new Matrix4f().scale(-1F, -1F, 1F).translate(2F / 16F, -2F / 16F, 4F / 16F).rotateZYX((float) (Math.PI * 2.0 / 3.0), 0F, (float) (Math.PI / 3));
		HybridMorph.Cube flipper = new HybridMorph.Cube(pose, pose.get3x3(new Matrix3f()), cube(-0.5F, -4F, 0F, 1F, 4F, 7F));
		Vector3f joint = pose.transformPosition(new Vector3f());
		HybridMorph.Graft graft = graft(dolphin, cube, false);
		float lowLevel = range(new Matrix4f(graft.move(joint, "left_fin", 0.5F, false, HybridMorph.Graft.PECTORAL_LIFT_ROLL)), flipper, 1)[0];
		float lowBare = range(new Matrix4f(graft.move(joint, "left_fin", 0.5F)), flipper, 1)[0];
		assertTrue(lowLevel > lowBare + 0.02F, "turned up at the free edge, it is not as low as it was: " + lowLevel + " against " + lowBare);
		assertTrue(lowLevel >= 0.5F - 0.2F, "and well above the floor: " + lowLevel);
		float[] x = range(new Matrix4f(graft.move(joint, "left_fin", 0.5F, false, HybridMorph.Graft.PECTORAL_LIFT_ROLL)), flipper, 0);
		assertTrue(x[0] < -0.5F - 0.1F, "it stands out of the left flank: " + x[0]);
	}
}
