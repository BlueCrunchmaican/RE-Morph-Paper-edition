package dev.morphreborn.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How big a borrowed head is drawn on its body (rig B3-08, 2026-11-17). The plugin's BorrowedHeadsSizeTest holds the same
 * numbers for the pack heads players without the mod see.
 */
class LentHeadSizeTest {
	/** The ender dragon's head (its 16-pixel upper head) and a giant's (a zombie's, six times over), in blocks. */
	private static final float DRAGON = 1.0F, GIANT = 3.375F;
	/** A ghast (4.5 blocks across) and a cow's head (0.45), in blocks. */
	private static final float GHAST = 4.5F, COW = 0.454F;

	@Test
	void aGhastOnADragonIsNoBiggerThanTheDragonsHead() {
		float k = LentHeadSize.grow(true, true, GHAST, DRAGON);
		assertEquals(DRAGON, GHAST * k, 1e-4, "the lent body is as big as the dragon's head, not as big as the dragon");
	}

	@Test
	void aGhastOnAGiantIsNoBiggerThanTheGiantsHead() {
		// it was drawn at its own 4.5 blocks, 1.33 times the giant's head, while players without the mod saw 3.5
		assertEquals(GIANT, GHAST * LentHeadSize.grow(true, true, GHAST, GIANT), 1e-3);
	}

	@Test
	void aSmallCreatureLentWholeIsScaledUpToTheBodysHeadToo() {
		// a squid (0.83 blocks) on a giant was the same size on both before; it stays as big as the giant's head
		assertEquals(GIANT, 0.83F * LentHeadSize.grow(true, true, 0.83F, GIANT), 1e-3);
	}

	@Test
	void aSmallHeadIsScaledUpToADragonsOrAGiants() {
		assertEquals(DRAGON, COW * LentHeadSize.grow(false, true, COW, DRAGON), 1e-4);
		assertEquals(GIANT, COW * LentHeadSize.grow(false, true, COW, GIANT), 1e-3);
	}

	@Test
	void aBigHeadOnADragonIsCappedAtOneAndAHalfTimesItsHead() {
		// a giant's head (3.4 blocks) on a dragon (1)
		assertEquals(1.5F * DRAGON, GIANT * LentHeadSize.grow(false, true, GIANT, DRAGON), 1e-4);
		// between its head and one and a half times it, a head keeps its own size
		assertEquals(1F, LentHeadSize.grow(false, true, 1.2F, DRAGON), 1e-6);
	}

	@Test
	void otherBodiesKeepTheirRules() {
		// a creature lent whole: never bigger than the head it replaces, never grown
		assertEquals(0.5625F, GHAST * LentHeadSize.grow(true, false, GHAST, 0.5625F), 1e-4);
		assertEquals(1F, LentHeadSize.grow(true, false, 0.2F, 0.5625F), 1e-6);
		// any other head: its own size, unless much bigger than the body's (then one and a half times)
		assertEquals(1F, LentHeadSize.grow(false, false, COW, 0.5625F), 1e-6);
		assertEquals(1.5F * 0.5625F, 2F * LentHeadSize.grow(false, false, 2F, 0.5625F), 1e-4);
		assertTrue(LentHeadSize.grow(false, false, 0F, 0.5625F) == 1F, "a head with no size is left alone");
	}

	/** A pufferfish's core (the side of the cube of its body), a husk's head, a tropical fish's core, in blocks (rig, 2026-11-18). */
	private static final float PUFFER = 0.164F, HUSK = 0.60F;

	@Test
	void aBodyWithoutAHeadPartCapsALentHeadAtOneAndAHalfTimesItsCore() {
		// it was never capped: a husk's head (0.60) was 3.65 times a pufferfish's core, and 4 to 5 times the whole fish
		float k = LentHeadSize.grow(false, false, HUSK, LentHeadSize.headlessStandIn(PUFFER, false));
		assertEquals(1.5F * PUFFER, HUSK * k, 1e-4);
	}

	@Test
	void aWholeCreatureOnABodyWithoutAHeadIsNoBiggerThanItsCore() {
		// the nautilus's shell on a tadpole or a bee, an endermite on a tadpole
		assertEquals(PUFFER, 0.9F * LentHeadSize.grow(true, false, 0.9F, LentHeadSize.headlessStandIn(PUFFER, false)), 1e-4);
	}

	@Test
	void aHeadOnAGhastGrowsToHalfItsCube() {
		float stand = LentHeadSize.headlessStandIn(GHAST, true);
		assertEquals(GHAST / 2F, stand, 1e-6);
		// a creeper's head (0.5) on the 4.5 cube was a speck at its own size
		assertEquals(stand, 0.5F * LentHeadSize.grow(false, true, 0.5F, stand), 1e-4);
		// and a ghast lent to a happy ghast is head-sized, not a second cube as tall as the body
		assertEquals(stand, GHAST * LentHeadSize.grow(true, true, GHAST, stand), 1e-4);
	}

	@Test
	void partsKeepTheirSizeOnOrdinaryBodiesAndGrowWithHugeOnes() {
		assertEquals(1F, LentHeadSize.partScale(true, 0.2F), 1e-6);
		assertEquals(1F, LentHeadSize.partScale(true, 1F), 1e-6);
		assertEquals(1F, LentHeadSize.partScale(true, 2.5F), 1e-6, "a bat's wings on a zombie are as they were");
		// from four times the donor's torso they follow a huge body, as the dragon's parts always do
		assertEquals(6F, LentHeadSize.partScale(true, 6F), 1e-6);
		assertEquals(14F, LentHeadSize.partScale(true, 14F), 1e-6);
		// no jump in between
		assertEquals(4F, LentHeadSize.partScale(true, 4F), 1e-5);
		assertTrue(LentHeadSize.partScale(true, 3.2F) > 1F && LentHeadSize.partScale(true, 3.2F) < 4F);
		assertTrue(LentHeadSize.partScale(true, 3.2F) < LentHeadSize.partScale(true, 3.6F), "growing with the body, never back");
	}

	@Test
	void anOrdinaryBodyKeepsTheLentPartsAtTheirOwnSizeHoweverSmallTheirTorso() {
		// a horse, cow or pig with an allay's, a bat's or a vex's wings: a fit of 3 to 4, and no scaling
		assertEquals(1F, LentHeadSize.partScale(false, 3.5F), 1e-6);
		assertEquals(1F, LentHeadSize.partScale(false, 14F), 1e-6);
		assertEquals(1F, LentHeadSize.partScale(false, 500F), 1e-6);
	}

	@Test
	void aThinTorsoCannotGrowPartsPastTheCeiling() {
		assertEquals(LentHeadSize.PARTS_MAX, LentHeadSize.partScale(true, 5000F), 1e-6);
		assertEquals(LentHeadSize.PARTS_MAX, LentHeadSize.partScale(true, LentHeadSize.PARTS_MAX), 1e-6);
		assertTrue(LentHeadSize.partScale(true, 20F) < LentHeadSize.PARTS_MAX);
	}

	@Test
	void earsAreNeverTwiceTheHeadExceptOnAGiantOrADragon() {
		assertEquals(2F, LentHeadSize.earScale(2.5F, false), 1e-6);
		assertEquals(0.15F, LentHeadSize.earScale(0.05F, false), 1e-6, "a guardian's plates on a parrot's head, not at half a block");
		assertEquals(0.4F, LentHeadSize.earScale(0.4F, false), 1e-6);
		assertEquals(6.8F, LentHeadSize.earScale(6.8F, true), 1e-6, "ears on a giant's head grow with it");
	}

	@Test
	void aTallLentHeadStillGoesOnTheShouldersOfAHorse() {
		// a horse's head and neck rise about 0.9 over its torso and are about 1.0 tall; a llama's column (1.3) asked for 0.97 of it
		float needed = LentHeadSize.riseNeeded(1.3F, 1F, 1.0F, true);
		assertEquals(0.5F, needed, 1e-6);
		assertTrue(0.9F >= needed);
		// a short head asks what it did: three quarters of its height
		assertEquals(0.3375F, LentHeadSize.riseNeeded(0.45F, 1F, 1.0F, true), 1e-6);
		// a cow's head, which does not rise over its torso, still asks for more than it has
		assertTrue(0F < LentHeadSize.riseNeeded(0.45F, 1F, 0.5F, true));
	}

	@Test
	void aBodyWhoseHeadIsNotTiltedUpAsksWhatItAlwaysDid() {
		// a person's head rises exactly its own height; the half of it a horse gets would put a warden's head on its shoulders
		assertEquals(0.75F, LentHeadSize.riseNeeded(1F, 1F, 0.5F, false), 1e-6);
		assertEquals(1.125F, LentHeadSize.riseNeeded(1F, 1.5F, 0.5F, false), 1e-6);
	}

	/** A llama's neck and head column (0.5 x 1.125 x 0.375) and a zombie's head with its hat layer (0.5625), in blocks (rig, 2026-11-20). */
	private static final float[] LLAMA = {0.5F, 1.125F, 0.375F}, ZOMBIE_HEAD = {0.5625F, 0.5625F, 0.5625F};

	@Test
	void aTallColumnIsHeldToOneAndAHalfTimesTheHeadOnEveryAxisNotOnlyItsCubeRoot() {
		// the cube root of the llama's column (0.595) passed the cap and it stood 2 times a zombie's head
		float ownSize = (float) Math.cbrt(0.5 * 1.125 * 0.375);
		assertEquals(1F, LentHeadSize.grow(false, false, ownSize, 0.5625F), 1e-6, "the cube roots alone let it through");
		float fit = LentHeadSize.axisFit(ZOMBIE_HEAD, LLAMA);
		float k = LentHeadSize.grow(false, false, ownSize, 0.5625F, fit, LentHeadSize.BIG_HEAD);
		assertEquals(1.5F * 0.5625F, 1.125F * k, 1e-5, "the column is 1.5 times the head's height");
		assertTrue(0.5F * k <= 1.5F * 0.5625F && 0.375F * k <= 1.5F * 0.5625F);
	}

	@Test
	void aWideHeadIsHeldOnItsWideSideToo() {
		// a warden's head (0.96 x 0.96 x 0.6) on a goat's (0.3 x 0.4 x 0.5)
		float[] warden = {0.96F, 0.96F, 0.6F}, goat = {0.3F, 0.4F, 0.5F};
		float k = LentHeadSize.grow(false, false, (float) Math.cbrt(0.96 * 0.96 * 0.6), (float) Math.cbrt(0.3 * 0.4 * 0.5), LentHeadSize.axisFit(goat, warden), 1.5F);
		assertEquals(1.5F * 0.3F, 0.96F * k, 1e-5, "no wider than 1.5 goat heads");
	}

	@Test
	void aHeadThatFitsOnEveryAxisIsLeftAlone() {
		float[] cow = {0.5F, 0.5F, 0.5F};
		assertEquals(1F, LentHeadSize.grow(false, false, 0.45F, 0.5F, LentHeadSize.axisFit(cow, new float[] {0.45F, 0.45F, 0.45F}), 1.5F), 1e-6);
		// nothing measured: the cube roots alone, as before
		assertEquals(1F, LentHeadSize.grow(false, false, 0.45F, 0.5F, LentHeadSize.axisFit(null, null), 1.5F), 1e-6);
	}

	@Test
	void aFlatMainHeadIsNotTheReferenceForASliver() {
		// a frog's head is 3 pixels tall (0.19); a side under a quarter of the longest counts as that quarter
		float[] frog = {0.44F, 0.1F, 0.44F};
		assertEquals(0.25F * 0.44F / 0.5F, LentHeadSize.axisFit(frog, new float[] {0.5F, 0.5F, 0.5F}), 1e-6);
	}

	@Test
	void theCapOnAGiantStillHoldsASlab() {
		// a llama column on a giant: the giant's head (3.375 each way) takes a small head up, but no side past 1.5 times its own
		float ownSize = (float) Math.cbrt(0.5 * 1.125 * 0.375);
		float k = LentHeadSize.grow(false, true, ownSize, 3.375F, LentHeadSize.axisFit(new float[] {3.375F, 3.375F, 3.375F}, LLAMA), 1.5F);
		assertEquals(1.5F * 3.375F, 1.125F * k, 1e-4);
		assertTrue(k < 3.375F / ownSize, "the cube roots alone would grow it to the giant's head");
	}

	@Test
	void aWholeCreatureIsHeldPerAxisToo() {
		// a squid (0.75 mantle, 1.9 with its tentacles) on a cat's head (0.5 across and tall): the mantle alone fits, the whole squid was 3
		// times the head (rig, 2026-11-20). Its core is 0.75, the head 0.5; the whole is 0.75 x 1.9 x 0.75 against a head of 0.5
		float[] cat = {0.5F, 0.5F, 0.5F}, squid = {0.75F, 1.9F, 0.75F};
		float fit = LentHeadSize.axisFit(cat, squid);
		float k = LentHeadSize.grow(true, false, 0.75F, 0.5F, fit, 1.5F);
		assertEquals(1.5F * 0.5F, 1.9F * k, 1e-5, "no side of the whole creature past 1.5 heads");
		// a creature that fits whole keeps the rule it had: no bigger than the head, never grown
		assertEquals(0.5F, 1F * LentHeadSize.grow(true, false, 1F, 0.5F, Float.MAX_VALUE, 1.5F), 1e-6);
		assertEquals(1F, LentHeadSize.grow(true, false, 0.2F, 0.5F, 10F, 1.5F), 1e-6);
		// on a giant, a dragon or a ghast it stays exactly as big as the head, by design
		assertEquals(3.375F, 0.83F * LentHeadSize.grow(true, true, 0.83F, 3.375F, 0.01F, 1.5F), 1e-3);
	}

	@Test
	void aHeadRestingPitchedIsMeasuredLevelAndFlatsAreFlooredHigherForIt() {
		// a hoglin's head level is 14 x 6 x 19 pixels: 0.875 x 0.375 x 1.1875. A ravager's head (1 x 1.25 x 1) on it
		float[] hoglin = {0.875F, 0.375F, 1.1875F}, ravager = {1F, 1.25F, 1F};
		float flat = LentHeadSize.axisFit(hoglin, ravager);
		float pitched = LentHeadSize.axisFit(hoglin, ravager, LentHeadSize.PITCHED_FLAT_FLOOR);
		assertEquals(0.375F / 1.25F, flat, 1e-6, "the snout's own 6 pixels bind: 0.3, a head 0.45 of its size");
		assertEquals(0.5F * 1.1875F / 1.25F, pitched, 1e-6, "the half floor for a main head that is a snout: 0.475, a head 0.71 of its size");
		assertTrue(pitched > flat);
		// the plain overload keeps the quarter
		assertEquals(flat, LentHeadSize.axisFit(hoglin, ravager, LentHeadSize.FLAT_FLOOR), 1e-6);
		// the height the AABB of the tilted head gave (18.4 pixels) let a head 1.3 across in: 4.4 times the head's height
		float tilted = LentHeadSize.axisFit(new float[] {0.875F, 1.15F, 1.05F}, ravager);
		assertTrue(tilted > pitched, "the box round the tilted head fitted more than the level one");
	}

	@Test
	void anEarIsNeverTallerThanThreeQuartersOfTheHeadItStandsOn() {
		// a donkey's ears (0.45 tall at their own size) at 1.33 on a zombie's head (0.5): 0.6 over a 0.5 head
		float size = LentHeadSize.earCap(1.33F, 0.45F, 0.5F, false);
		assertEquals(0.75F * 0.5F, 0.45F * size, 1e-5);
		// an ear that fits is left alone
		assertEquals(0.8F, LentHeadSize.earCap(0.8F, 0.3F, 0.5F, false), 1e-6);
		// a giant's or a dragon's ears grow with their heads but not past one of them: a parrot's crest 3 blocks over a 1 block head
		assertEquals(1F, 0.3F * LentHeadSize.earCap(10F, 0.3F, 1F, true), 1e-5);
		assertEquals(4F, LentHeadSize.earCap(4F, 0.1F, 1F, true), 1e-6, "an ear that fits on a dragon grows as it did");
		// nothing measured: unchanged
		assertEquals(2F, LentHeadSize.earCap(2F, 0F, 0.5F, false), 1e-6);
		assertEquals(2F, LentHeadSize.earCap(2F, 0.3F, 0F, false), 1e-6);
	}

	@Test
	void aLongThinTorsoHoldsALentTailToItsWidthToo() {
		// a cat (torso 0.2 wide, 0.3 tall, 0.8 long) with a wither's tail slab (0.36 wide, 0.71 long): 1.8 times the width
		float s = LentHeadSize.tailScale(1F, 0.71F, 0.36F, 0.8F, 0.2F);
		assertEquals(LentHeadSize.TAIL_THICK_SHARE * 0.2F, 0.36F * s, 1e-5);
		// the length rule is unchanged: 0.71 fits a torso 0.8 long
		assertEquals(LentHeadSize.tailScale(1F, 0.71F, 0.8F), 1F, 1e-6);
		// never under the floor of its scale
		assertEquals(LentHeadSize.TINY_PARTS_FLOOR, LentHeadSize.tailScale(1F, 0.5F, 2F, 5F, 0.05F), 1e-6);
		// a tail that is thin enough keeps the scale the length rule gave
		assertEquals(0.3F / 1.25F, LentHeadSize.tailScale(1F, 1.25F, 0.1F, 0.3F, 0.5F), 1e-6);
		assertEquals(1F, LentHeadSize.tailScale(1F, 0.5F, 0.1F, 0.75F, 0.5F), 1e-6);
		// nothing measured: the length rule alone
		assertEquals(1F, LentHeadSize.tailScale(1F, 0.5F, 0F, 0.75F, 0.5F), 1e-6);
	}

	@Test
	void aThinTorsoPutsItsWingsAtTheSideEdges() {
		assertEquals(0F, LentHeadSize.wingInset(0.25F), 1e-6, "a vex's two roots were a tenth of a block apart");
		assertEquals(0.1F, LentHeadSize.wingInset(0.5F), 1e-6, "a person's torso: a fifth in, as it was");
		assertEquals(0.2F * LentHeadSize.THIN_TORSO, LentHeadSize.wingInset(LentHeadSize.THIN_TORSO), 1e-6);
		// eased in just under the line: no jump of 0.07 block as a body's size crosses it
		assertEquals(LentHeadSize.wingInset(LentHeadSize.THIN_TORSO), LentHeadSize.wingInset(LentHeadSize.THIN_TORSO - 1e-4F), 1e-3);
		assertEquals(0F, LentHeadSize.wingInset(0.3F), 1e-6);
		assertTrue(LentHeadSize.wingInset(0.325F) > 0F && LentHeadSize.wingInset(0.325F) < LentHeadSize.wingInset(0.35F));
	}

	@Test
	void aLentPartIsNeverRaisedPastThePartsMaximum() {
		// a part with next to no extent (a folded or flat plane) on a giant's 4.5 block torso asked for 0.15 * 4.5 / 0.011, about 60 times
		assertEquals(LentHeadSize.PARTS_MAX, LentHeadSize.partFloor(1F, 0.011F, 4.5F, Float.MAX_VALUE), 1e-6);
		assertEquals(30F, LentHeadSize.partFloor(30F, 0.011F, 4.5F, Float.MAX_VALUE), 1e-6, "a scale it already had is not cut");
	}

	@Test
	void aPufferfishCoreOfExactlyHalfABlockDoesNotFlipWithFloatNoise() {
		// a full pufferfish's 8 pixel core is 0.5 and is re-measured every frame through yaw-dependent products: 0.49999997 or 0.50000006
		assertEquals(1.5F, LentHeadSize.headCap(true, Math.nextDown(0.5F)), 1e-6);
		assertEquals(1.5F, LentHeadSize.headCap(true, Math.nextUp(0.5F)), 1e-6);
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1.2F, Math.nextDown(0.5F), LentHeadSize.TINY_HEADLESS));
	}

	@Test
	void aLentWingOrFinIsNotLeftASpeckOnABigBody() {
		// a tropical fish's fin (0.375 across) on a body 3.0 long: at least 0.15 of it, 0.45
		float s = LentHeadSize.partFloor(1F, 0.375F, 3F, Float.MAX_VALUE);
		assertEquals(0.15F * 3F, 0.375F * s, 1e-5);
		// a part already big enough keeps its scale (a horse's torso 1.1 long takes the fin as it is)
		assertEquals(1F, LentHeadSize.partFloor(1F, 0.375F, 1.1F, Float.MAX_VALUE), 1e-6);
		// the tiny-main limit still wins
		assertEquals(0.4F, LentHeadSize.partFloor(1F, 0.375F, 3F, 0.4F), 1e-6);
		assertEquals(0.3F, LentHeadSize.partFloor(0.5F, 0.375F, 0.1F, 0.3F), 1e-6);
		// nothing measured: the scale it had, held by the limit
		assertEquals(2F, LentHeadSize.partFloor(2F, 0F, 3F, Float.MAX_VALUE), 1e-6);
	}

	@Test
	void aHeadlessBodyUnderHalfABlockIsTinyForHeadsAndParts() {
		// the bee's core (0.48): a head held to its size, and its parts to 1.5 of it, as a pufferfish's or a tadpole's
		assertEquals(1F, LentHeadSize.headCap(true, 0.48F), 1e-6);
		assertEquals(1.5F, LentHeadSize.headCap(true, 0.5F), 1e-6);
		assertEquals(1.5F * 0.48F / 1.2F, LentHeadSize.tinyMainPartLimit(1.2F, 0.48F, LentHeadSize.TINY_HEADLESS), 1e-6);
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1.2F, 0.48F), "a body with a head part keeps TINY_MAIN");
		assertEquals(Float.MAX_VALUE, LentHeadSize.tinyMainPartLimit(1.2F, 0.5F, LentHeadSize.TINY_HEADLESS));
	}

	@Test
	void aTinyBodyWithoutAHeadHoldsALentHeadToItsOwnSizeNotOneAndAHalfTimes() {
		assertEquals(1F, LentHeadSize.headCap(true, 0.2F), 1e-6);
		assertEquals(1.5F, LentHeadSize.headCap(true, 0.5F), 1e-6, "a body over 0.4 keeps the cap");
		assertEquals(1.5F, LentHeadSize.headCap(false, 0.2F), 1e-6, "a body with a head part keeps the cap");
		// a husk's head (0.6) on a tadpole (0.2): as big as the tadpole, not 1.5 times it
		float k = LentHeadSize.grow(false, false, 0.6F, 0.2F, LentHeadSize.axisFit(new float[] {0.2F, 0.2F, 0.2F}, new float[] {0.6F, 0.6F, 0.6F}), 1F);
		assertEquals(0.2F, 0.6F * k, 1e-5);
	}

	@Test
	void aHeadThatRestsFarNoseDownIsLeveledAndOneThatRestsALittleIsNot() {
		float hoglin = (float) Math.toRadians(50), horse = (float) Math.toRadians(30);
		assertEquals(hoglin, LentHeadSize.levelPitch(hoglin), 1e-6);
		assertEquals(0F, LentHeadSize.levelPitch(horse), 1e-6, "a horse's own carriage stays");
		assertEquals(-hoglin, LentHeadSize.levelPitch(-hoglin), 1e-6);
		assertEquals(0F, LentHeadSize.levelPitch(0F), 1e-6);
	}

	@Test
	void aRestPitchIsReadFromTheDirectionNotItsLength() {
		// the direction a head faces comes out of a matrix that may carry a size: 50 degrees down reads 50 at 0.5, 1 and 2 times
		float down = (float) Math.toRadians(50), y = (float) Math.sin(down), z = -(float) Math.cos(down);
		for (float size : new float[] {0.5F, 1F, 2F}) {
			assertEquals(down, LentHeadSize.pitchOf(0F, y * size, z * size), 1e-5, "at " + size);
		}
		assertEquals(-down, LentHeadSize.pitchOf(0F, -y * 2F, z * 2F), 1e-5);
		assertEquals(0F, LentHeadSize.pitchOf(0F, 0F, 0F), 1e-6, "no direction, no pitch");
	}

	@Test
	void aTailLongerThanTheTorsoIsDrawnAsLongAsIt() {
		// a wither's 1.25 block tail slab on an allay (torso 0.3 at its longest)
		assertEquals(0.3F / 1.25F, LentHeadSize.tailScale(1F, 1.25F, 0.3F), 1e-6);
		// never under the floor of its size
		assertEquals(LentHeadSize.TINY_PARTS_FLOOR, LentHeadSize.tailScale(1F, 10F, 0.3F), 1e-6);
		// a tail that fits keeps the scale it had
		assertEquals(1F, LentHeadSize.tailScale(1F, 0.5F, 0.75F), 1e-6);
		assertEquals(2.5F, LentHeadSize.tailScale(2.5F, 0.2F, 0.75F), 1e-6);
		// the scale a tail has is part of its length
		assertEquals(0.75F / 0.5F, LentHeadSize.tailScale(3F, 0.5F, 0.75F), 1e-6);
	}

	@Test
	void aRodAlreadyOutsideTheTorsoStaysAndOneInsideIsPushedOutAlongItsLine() {
		float[] out = LentHeadSize.ringPush(0.5F, 0F, 0.25F, 0.125F, 0.12F);
		assertEquals(0.5F, out[0], 1e-6, "past the torso's side and its margin already");
		float[] in = LentHeadSize.ringPush(0.1F, 0F, 0.25F, 0.125F, 0.12F);
		assertEquals(0.37F, in[0], 1e-5, "to the torso's half width and the margin");
		assertEquals(0F, in[1], 1e-6);
		// the front rod stands in front of the chest, not at its side
		float[] front = LentHeadSize.ringPush(0F, -0.2F, 0.25F, 0.125F, 0.12F);
		assertEquals(0F, front[0], 1e-6);
		assertEquals(-0.245F, front[1], 1e-5);
		// a rod on the axis has no line to move along
		assertEquals(0F, LentHeadSize.ringPush(0F, 0F, 0.25F, 0.125F, 0.12F)[0], 1e-6);
		// a diagonal one reaches the box's edge where the line leaves it
		float[] diagonal = LentHeadSize.ringPush(0.1F, 0.1F, 0.25F, 0.125F, 0.12F);
		assertEquals(diagonal[0], diagonal[1], 1e-6);
		assertEquals(0.245F, diagonal[0], 1e-5);
	}

	@Test
	void anEarUnderTwoPixelsKeepsHalfOfItAboveTheHead() {
		float px = 1F / 16F;
		// a cat's one pixel ears were sunk a whole pixel and lay flush
		assertEquals(px / 2F, LentHeadSize.earSink(px, 0.0625F), 1e-6);
		// two pixels and up: as they were, the larger of a third of the height and EAR_SET_IN
		assertEquals(0.0625F, LentHeadSize.earSink(2 * px, 0.0625F), 1e-6);
		assertEquals(0.2F / 3F, LentHeadSize.earSink(0.2F, 0.0625F), 1e-6);
		assertEquals(0.0625F, LentHeadSize.earSink(0.1875F, 0.0625F), 1e-6);
	}

	@Test
	void aFlatFinIsRaisedToAThirdOfTheTorsoAndNoFinIsMoreThanSixTenthsOfIt() {
		// a tropical fish's flank fin: 0.02 thick, 0.3 and 0.375 across; a cat's tail 1 x 1 x 8 pixels is a stick
		assertTrue(LentHeadSize.planar(new float[] {0.02F, 0.3F, 0.375F}));
		assertFalse(LentHeadSize.planar(new float[] {0.0625F, 0.0625F, 0.5F}));
		assertFalse(LentHeadSize.planar(new float[] {0.3F, 0.4F, 0.5F}), "a block is not a plane");
		// on a person's 0.75 torso it comes out 0.225 across, not the 0.11 of every other part
		float s = LentHeadSize.partFloor(1F, 0.375F, 0.75F, Float.MAX_VALUE, true);
		assertEquals(1F, s, 1e-6, "a 0.375 fin on a 0.75 torso is already above 0.225: it keeps its size");
		assertEquals(LentHeadSize.FIN_FLOOR * 3F / 0.1F, LentHeadSize.partFloor(1F, 0.1F, 3F, Float.MAX_VALUE, true), 1e-5, "a 2 pixel cod fin on a 3 block body");
		assertEquals(LentHeadSize.PART_FLOOR * 3F / 0.1F, LentHeadSize.partFloor(1F, 0.1F, 3F, Float.MAX_VALUE, false), 1e-5, "any other part keeps the old share");
		// the fin limit of a tiny body wins
		assertEquals(0.4F, LentHeadSize.partFloor(1F, 0.1F, 3F, 0.4F, true), 1e-6);
		// the cap: a dolphin's 0.44 flipper on a 0.5 block torso is held to 0.3; never raised; nothing measured
		assertEquals(0.6F * 0.5F / 0.44F, LentHeadSize.finCap(1F, 0.44F, 0.5F), 1e-6);
		assertEquals(1F, LentHeadSize.finCap(1F, 0.44F, 3F), 1e-6);
		assertEquals(2F, LentHeadSize.finCap(2F, 0F, 3F), 1e-6);
	}

	@Test
	void aFlatTailIsNotLeftAHairlineEither() {
		// a tropical fish's tail (0.3 across at its own size) on a 0.75 torso at scale 0.2: drawn 0.225
		assertEquals(0.3F * 0.75F / 0.3F, LentHeadSize.tailFloor(0.2F, 0.3F, 0.75F, Float.MAX_VALUE, true), 1e-6);
		assertEquals(0.2F, LentHeadSize.tailFloor(0.2F, 0.3F, 0.75F, Float.MAX_VALUE, false), 1e-6, "a stick keeps its scale");
		assertEquals(0.5F, LentHeadSize.tailFloor(0.2F, 0.3F, 0.75F, 0.5F, true), 1e-6, "the tiny-main fin limit still holds it");
		assertEquals(2F, LentHeadSize.tailFloor(2F, 0.3F, 0.75F, Float.MAX_VALUE, true), 1e-6, "never made smaller");
	}

	@Test
	void theFlatTailFloorDoesNotUndoTheThicknessLimit() {
		// a flat tail nearly as wide as long (0.3 across, 0.25 thick side) on a cod-like torso 0.75 long and 0.15 thin: the thickness limit
		// is 0.8 * 0.15 / 0.25 = 0.48, the plain floor 0.3 * 0.75 / 0.3 = 0.75 would be a tail 0.1875 across on a 0.15 torso
		float held = LentHeadSize.tailScale(1F, 0.3F, 0.25F, 0.75F, 0.15F);
		assertEquals(0.48F, held, 1e-6);
		assertEquals(0.75F, LentHeadSize.tailFloor(held, 0.3F, 0.75F, Float.MAX_VALUE, true), 1e-6, "the old floor undid it");
		assertEquals(0.48F, LentHeadSize.tailFloor(held, 0.3F, 0.25F, 0.75F, 0.15F, Float.MAX_VALUE, true), 1e-6);
		// a torso thick enough for the floor keeps it
		assertEquals(0.75F, LentHeadSize.tailFloor(0.2F, 0.3F, 0.25F, 0.75F, 0.5F, Float.MAX_VALUE, true), 1e-6);
		// a scale already past the thickness limit (its own floor) is not made smaller, and a stick and a torso with no size are left alone
		assertEquals(1.5F, LentHeadSize.tailFloor(1.5F, 0.3F, 0.25F, 0.75F, 0.15F, Float.MAX_VALUE, true), 1e-6);
		assertEquals(0.2F, LentHeadSize.tailFloor(0.2F, 0.3F, 0.25F, 0.75F, 0.15F, Float.MAX_VALUE, false), 1e-6);
		assertEquals(0.75F, LentHeadSize.tailFloor(0.2F, 0.3F, 0.25F, 0.75F, 0F, Float.MAX_VALUE, true), 1e-6);
	}

	@Test
	void theSlimeSquishDoesNotMoveACoreAcrossTheTinyLine() {
		// a small sulfur cube's core at rest, 0.4995, over the line of 0.4965; a jump squish scale(w, 1/w, w) swings the core measured as drawn
		// by w^(1/3) (0.9 to 1.1 for w from 0.75 to 1.33)
		float core = 0.4995F;
		assertEquals(LentHeadSize.BIG_HEAD, LentHeadSize.headCap(true, core), 1e-6);
		for (float w : new float[] {0.75F, 0.9F, 1F, 1.1F, 1.33F}) {
			float drawn = core * (float) Math.cbrt(w * (1F / w) * w);
			float factor = LentHeadSize.squishFree(w, 1F / w, w);
			assertEquals(core, drawn * factor, 1e-5, "squish-free at w " + w);
			assertEquals(LentHeadSize.BIG_HEAD, LentHeadSize.headCap(true, drawn * factor), 1e-6, "no pop at w " + w);
		}
		// a uniform scale (a baby's), nothing to take out; a pose with no size
		assertEquals(1F, LentHeadSize.squishFree(0.5F, 0.5F, 0.5F), 1e-6);
		assertEquals(1F, LentHeadSize.squishFree(0F, 0F, 0F), 1e-6);
	}

	@Test
	void aFlatFinOnATinyBodyGetsAHigherFloorThanOtherParts() {
		// a part set 3 blocks across on a 0.35 core: reach 0.175. Other parts are lifted to 0.2 (and never past 1.5 of the reach, 0.2625),
		// a flat fin toward half its own size, held there by the same 1.5 of the reach
		float parts = LentHeadSize.tinyMainPartLimit(3F, 0.35F, LentHeadSize.TINY_MAIN);
		float fins = LentHeadSize.tinyMainPartLimit(3F, 0.35F, LentHeadSize.TINY_MAIN, LentHeadSize.TINY_FIN_FLOOR);
		assertEquals(LentHeadSize.TINY_PARTS_FLOOR, parts, 1e-6);
		assertEquals(1.5F * 1.5F * 0.35F / 3F, fins, 1e-6);
		assertTrue(fins > parts);
		// a set that fits (reach over 0.5): the floor is not needed, both are the reach
		assertEquals(1.5F * 0.35F / 0.5F, LentHeadSize.tinyMainPartLimit(0.5F, 0.35F, LentHeadSize.TINY_MAIN, LentHeadSize.TINY_FIN_FLOOR), 1e-6);
		// 7 blocks of dragon on a 0.1 core: the reach decides, not the floor
		assertEquals(1.5F * (1.5F * 0.1F / 7F), LentHeadSize.tinyMainPartLimit(7F, 0.1F, LentHeadSize.TINY_MAIN, LentHeadSize.TINY_FIN_FLOOR), 1e-6);
	}

	@Test
	void aBeeIsTinyForHeadsAtItsRealCoreOfPointFourNineThree() {
		// 7 x 7 x 10 pixels: the cube root of 490 pixels is 7.88 pixels, 0.4927 block. Over the old line of 0.49 the head cap was 1.5
		float bee = (float) Math.cbrt(7 * 7 * 10) / 16F;
		assertEquals(0.4927F, bee, 1e-4);
		assertEquals(1F, LentHeadSize.headCap(true, bee), 1e-6);
		assertTrue(LentHeadSize.tinyMainPartLimit(1.2F, bee, LentHeadSize.TINY_HEADLESS) < Float.MAX_VALUE);
		// a full pufferfish stays as it was
		assertEquals(1.5F, LentHeadSize.headCap(true, 0.5F), 1e-6);
	}

	@Test
	void aStripFinIsRaisedByItsHeightToo() {
		// a cod's dorsal (6 x 1 pixels) on a 0.75 block torso: the length already passes the floors, so its one pixel of height never grew
		float height = 1F / 16F;
		float s = LentHeadSize.finHeightFloor(1F, height, 0.75F, Float.MAX_VALUE);
		assertEquals(LentHeadSize.FIN_HEIGHT_FLOOR * 0.75F, height * s, 1e-5, "the fin is 0.12 of the torso's longest side high");
		// a fin already that high is left, and the tiny-main ceiling still holds
		assertEquals(3F, LentHeadSize.finHeightFloor(3F, height, 0.75F, Float.MAX_VALUE), 1e-6);
		assertEquals(0.5F, LentHeadSize.finHeightFloor(0.5F, height, 0.75F, 0.5F), 1e-6);
		// nothing to measure: unchanged
		assertEquals(1F, LentHeadSize.finHeightFloor(1F, 0F, 0.75F, Float.MAX_VALUE), 1e-6);
		assertEquals(1F, LentHeadSize.finHeightFloor(1F, height, 0F, Float.MAX_VALUE), 1e-6);
	}

	@Test
	void aWingHasAHigherFloorOnABigBody() {
		// a bee's 9 pixel wing (0.5625 block) on a 2.4 block camel: 0.3 of it, not 0.15
		float extent = 9F / 16F;
		assertEquals(0.3F * 2.4F, extent * LentHeadSize.wingFloor(1F, extent, 2.4F, Float.MAX_VALUE), 1e-4);
		// under BIG_MAIN it is the old 0.15 (a wing of its own size on a 1 block body is already over it)
		assertEquals(1F, LentHeadSize.wingFloor(1F, extent, 1F, Float.MAX_VALUE), 1e-6);
		assertEquals(0.15F * 1.4F, extent * LentHeadSize.wingFloor(0.1F, extent, 1.4F, Float.MAX_VALUE), 1e-4);
		// never past the ceiling
		assertEquals(0.2F, LentHeadSize.wingFloor(0.1F, extent, 2.4F, 0.2F), 1e-6);
	}

	@Test
	void breezeRodsAreRaisedOnATallBodyAndTheirRingGrowsWithThem() {
		// 0.51 block rods on a 1.8 block sniffer: 0.4 of its height
		float rods = 0.51F;
		assertEquals(0.4F * 1.8F, rods * LentHeadSize.rodsScale(1F, rods, 1.8F, Float.MAX_VALUE), 1e-4);
		// a body under 1.5 blocks (a person, a camel) keeps them as they were
		assertEquals(1F, LentHeadSize.rodsScale(1F, rods, 1.45F, Float.MAX_VALUE), 1e-6);
		assertEquals(0.7F, LentHeadSize.rodsScale(1F, rods, 1.8F, 0.7F) * 1F, 1e-6, "held to the ceiling");
		// the ring stands further out as the rods grow, nearer as they are held small
		assertEquals(0.12F, LentHeadSize.ringMargin(0.12F, 1F), 1e-6);
		assertEquals(0.12F * 1.5F, LentHeadSize.ringMargin(0.12F, 1.5F), 1e-6);
		assertEquals(0.12F * 0.5F, LentHeadSize.ringMargin(0.12F, 0.5F), 1e-6);
	}

	@Test
	void onlyAHeadAsTallAsTheAxisCapLeavesABigOneIsSeatedAtAPitchedHead() {
		// a hoglin's head block, 0.9 x 0.375 x 1.2: a big head is held to 1.5 times half its longest side tall (0.9) and is seated at it
		float longest = 1.2F;
		assertTrue(LentHeadSize.fillsPitchedHead(0.9F, longest), "a giant's, a ravager's, a sniffer's, a warden's, all 0.9 tall");
		// a zombie's (0.5625), a villager's (0.65) and an iron golem's (0.625) are taller than the 0.375 block and keep the earlier seat
		for (float height : new float[] {0.375F, 0.5F, 0.5625F, 0.646F, 0.8F}) assertFalse(LentHeadSize.fillsPitchedHead(height, longest), "height " + height);
		assertFalse(LentHeadSize.fillsPitchedHead(0.9F, 0F), "no head block, no seat");
	}

	@Test
	void aHeadHingedAtTheMiddleOfAFlatTopIsMovedBackUntilItsFrontIsAtTheTopsFrontEdge() {
		// round 5, magma cube + goat: a 1.5 cube deep head hinged at the middle of a 1 block top had 1 of its 1.5 blocks over the front edge
		assertEquals(0.5F, LentHeadSize.backIntoTop(1F, 1F), 1e-6, "a head as deep as the top: back to the rear edge, so it fills the top");
		assertEquals(0.75F, LentHeadSize.backIntoTop(1.5F, 1F), 1e-6, "a head deeper than the top is centred on it");
		assertEquals(0F, LentHeadSize.backIntoTop(0.5F, 1F), 1e-6, "a head in the front half already stays on the top");
		assertEquals(0F, LentHeadSize.backIntoTop(0F, 1F), 1e-6);
		assertEquals(0F, LentHeadSize.backIntoTop(1F, 0F), 1e-6);
		// the head's front stays at or behind the top's front edge: depth - shift <= topDepth / 2 for a head up to the top's depth
		for (float depth : new float[] {0.6F, 0.8F, 1F}) assertTrue(depth - LentHeadSize.backIntoTop(depth, 1F) <= 0.5F + 1e-6, "depth " + depth);
	}

	@Test
	void aStripWithNoThicknessIsAStripTailAndAFinOrAStickIsNot() {
		// a camel's tail 3 x 14 pixels: smallest side first, in blocks
		assertTrue(LentHeadSize.stripTail(new float[] {0F, 0.1875F, 0.875F}));
		assertFalse(LentHeadSize.planar(new float[] {0F, 0.1875F, 0.875F}), "which planar leaves out");
		assertFalse(LentHeadSize.stripTail(new float[] {0.0625F, 0.0625F, 0.5F}), "a cat's stick has thickness");
		assertFalse(LentHeadSize.stripTail(new float[] {0F, 0.0625F, 0.875F}), "a hairline is not a strip either");
		// a strip tail gets the flat tail floor, 0.3 of a 0.9 block shell
		float s = LentHeadSize.tailFloor(0.1F, 0.875F, 0.1875F, 0.9F, 0.6F, Float.MAX_VALUE, true);
		assertEquals(LentHeadSize.FIN_FLOOR * 0.9F, 0.875F * s, 1e-4F);
	}

	@Test
	void finsOnACreatureWhoseTorsoIsASmallPartOfItAreSizedAgainstItsHeight() {
		// an allay: a 0.3 block torso under a 0.6 block creature; a blaze: a 0.5 block head cube in 1.8 blocks of rods; a vex under its wings
		assertEquals(0.6F, LentHeadSize.partReference(0.3F, 0.6F), 1e-6);
		assertEquals(1F, LentHeadSize.partReference(0.5F, 1.8F), 1e-6, "held to twice the torso");
		// a person's torso (0.75) and a cat's (1.0) are not small, and nothing without a measured height changes
		assertEquals(0.75F, LentHeadSize.partReference(0.75F, 1.8F), 1e-6);
		assertEquals(0.3F, LentHeadSize.partReference(0.3F, 0F), 1e-6);
		// a chicken's 0.5 torso is most of its 0.7 body
		assertEquals(0.5F, LentHeadSize.partReference(0.5F, 0.7F), 1e-6);
		// the floor and the fin cap follow it: a 0.3 block fin on the allay, not 0.09
		float extent = 0.375F;
		assertEquals(LentHeadSize.FIN_FLOOR * 0.6F, extent * LentHeadSize.partFloor(0.2F, extent, LentHeadSize.partReference(0.3F, 0.6F), Float.MAX_VALUE, true), 1e-4F);
		assertEquals(LentHeadSize.FIN_CAP * 0.6F, extent * LentHeadSize.finCap(4F, extent, LentHeadSize.partReference(0.3F, 0.6F)), 1e-4F);
	}

	@Test
	void aFlatWingPlaneHasAHigherFloorOnABigBodyAndAStripFinIsFifthOfTheTorsoHigh() {
		float extent = 0.5F;
		assertEquals(LentHeadSize.PLANAR_WING_FLOOR * 2.2F, extent * LentHeadSize.wingFloor(1F, extent, 2.2F, Float.MAX_VALUE, true), 1e-4F, "a vex's plane on a ravager's 2.2");
		assertEquals(LentHeadSize.WING_FLOOR * 2.2F, extent * LentHeadSize.wingFloor(1F, extent, 2.2F, Float.MAX_VALUE, false), 1e-4F, "a thick wing keeps its share");
		assertEquals(LentHeadSize.PART_FLOOR * 1.4F, extent * LentHeadSize.wingFloor(0.1F, extent, 1.4F, Float.MAX_VALUE, true), 1e-4F, "a small body keeps the old one");
		assertEquals(0.2F, LentHeadSize.FIN_HEIGHT_FLOOR, 1e-6F);
		float height = 0.0625F;
		assertEquals(0.2F * 2.35F, height * LentHeadSize.finHeightFloor(1F, height, 2.35F, Float.MAX_VALUE), 1e-4F, "a cod's dorsal on the elder guardian's 2.35 cube");
	}

	@Test
	void aStripFinIsAsLongAsTheCapAllowsOnABigCubeAndTheFloorAloneNeverShowedOnACod() {
		float height = 0.0625F, length = 0.375F; // the cod's top fin: 0 x 1 x 6 pixels
		// the floor alone asks for 7.5 times its size, which the length cap then takes back to 3.76: 0.6 of the torso long is 0.235 high. This is
		// what was drawn on the elder guardian after the floor went to 0.2: the same as before
		float floored = LentHeadSize.finHeightFloor(1F, height, 2.35F, Float.MAX_VALUE);
		float plain = LentHeadSize.stripFinScale(1F, length, height, 2.35F, Float.MAX_VALUE, false);
		assertEquals(LentHeadSize.finCap(floored, length, 2.35F), plain, 1e-6F);
		assertEquals(0.1F * 2.35F, height * plain, 1e-4F, "a body that is not a cube keeps 0.6 long, 0.1 of it high");
		// on a big cube the longer cap: 0.8 long, 0.133 of the torso high (0.31 on the guardian)
		float cube = LentHeadSize.stripFinScale(1F, length, height, 2.35F, Float.MAX_VALUE, true);
		assertEquals(LentHeadSize.STRIP_FIN_CAP * 2.35F, length * cube, 1e-4F);
		assertTrue(height * cube > 1.3F * height * plain, "taller than before");
		assertEquals(0.3133F, height * cube, 1e-3F);
		// a small cube (a slime's 1 block) is not a big one: the old cap
		assertEquals(LentHeadSize.stripFinScale(1F, length, height, 1F, Float.MAX_VALUE, false), LentHeadSize.stripFinScale(1F, length, height, 1F, Float.MAX_VALUE, true), 1e-6F);
		// held by the ceiling a tiny body sets
		assertEquals(0.5F, LentHeadSize.stripFinScale(0.5F, length, height, 2.35F, 0.5F, true), 1e-6F);
	}

	@Test
	void theDepthOfACubeIsTheSameAtEveryPointOfItsJumpSquish() {
		// the renderer scales (w, 1/w, w) times the size: the depth drawn is w times the cube's
		float size = 0.5F;
		for (float w : new float[] {0.8F, 1F, 1.3F}) {
			float drawn = 1F * (size * w);
			assertEquals(1F * size, drawn * LentHeadSize.squishFreeDepth(size * w, size / w, size * w), 1e-5F);
		}
		assertEquals(1F, LentHeadSize.squishFreeDepth(0.5F, 0.5F, 0.5F), 1e-6F);
		assertEquals(1F, LentHeadSize.squishFreeDepth(1F, 1F, 0F), 1e-6F, "no depth scale: unchanged");
	}

	@Test
	void aStripTailOnAShellIsAtLeastAsWideAsTheFloorAndNoLongerThanHalfTheShellsHeight() {
		// round 6: a camel's strip (3 x 14 pixels: 0.1875 across, 0.875 long) on a 1.1 block shell was 0.04 to 0.08 block across
		float s = LentHeadSize.stripTailWidthFloor(0.2F, 0.1875F, 0.875F, 1.1F, Float.MAX_VALUE);
		assertTrue(0.1875F * s >= LentHeadSize.STRIP_TAIL_WIDTH - 0.01F, "wide enough: " + 0.1875F * s);
		assertTrue(0.875F * s <= LentHeadSize.STRIP_TAIL_LENGTH_SHARE * 1.1F + 0.01F, "and not a streamer down the end: " + 0.875F * s);
		// a bigger scale stays; a short shell holds the length; a ceiling and a body with no size leave it alone
		assertEquals(2F, LentHeadSize.stripTailWidthFloor(2F, 0.1875F, 0.875F, 1.1F, Float.MAX_VALUE), 1e-6F);
		assertEquals(0.5F * 0.5F / 0.875F, LentHeadSize.stripTailWidthFloor(0.1F, 0.1875F, 0.875F, 0.5F, Float.MAX_VALUE), 1e-6F);
		assertEquals(0.3F, LentHeadSize.stripTailWidthFloor(0.2F, 0.1875F, 0.875F, 1.1F, 0.3F), 1e-6F);
		assertEquals(0.2F, LentHeadSize.stripTailWidthFloor(0.2F, 0.1875F, 0.875F, 0F, Float.MAX_VALUE), 1e-6F);
	}

	@Test
	void aGapUnderAHeadOnABodysTopIsClosedAndAHeadIsNeverLifted() {
		// model axes: +y is down, so a head whose bottom (lowest) has a smaller y than the top is in the air
		assertEquals(0.1F + 0.02F, LentHeadSize.closeGap(0.4F, 0.5F, 0.02F), 1e-6F, "the gap and how far it sinks in");
		assertEquals(0F, LentHeadSize.closeGap(0.498F, 0.5F, 0.02F), 1e-6F, "float noise is not a gap");
		assertEquals(0F, LentHeadSize.closeGap(0.5F, 0.5F, 0.02F), 1e-6F, "flush");
		assertEquals(0F, LentHeadSize.closeGap(0.6F, 0.5F, 0.02F), 1e-6F, "a head sunk into the top stays where it is");
	}

	@Test
	void aPectoralOnABlockIsHeldShorterThanAFinAndADorsalOnACubeShorterStill() {
		assertTrue(LentHeadSize.PECTORAL_CAP < LentHeadSize.FIN_CAP);
		assertTrue(LentHeadSize.CUBE_DORSAL_SHARE < LentHeadSize.PECTORAL_CAP);
		// a 7 pixel flipper (0.4375 block) on a 1 block cube is 0.4 of the cube, whatever the parts' scale was (grown to 1.8 times or a speck), and 0.3 on a squid's 0.75 mantle
		assertEquals(0.4F / 0.4375F, LentHeadSize.pectoralScale(1.8F, 0.4375F, 1F, Float.MAX_VALUE), 1e-5F);
		assertEquals(0.4F / 0.4375F, LentHeadSize.pectoralScale(0.1F, 0.4375F, 1F, Float.MAX_VALUE), 1e-5F);
		assertEquals(0.3F / 0.4375F, LentHeadSize.pectoralScale(1F, 0.4375F, 0.75F, Float.MAX_VALUE), 1e-5F);
		assertEquals(0.5F, LentHeadSize.pectoralScale(1F, 0.4375F, 1F, 0.5F), 1e-6F, "a tiny main's limit holds");
		assertEquals(1F, LentHeadSize.pectoralScale(1F, 0F, 1F, Float.MAX_VALUE), 1e-6F, "no extent: unchanged");
	}
}
