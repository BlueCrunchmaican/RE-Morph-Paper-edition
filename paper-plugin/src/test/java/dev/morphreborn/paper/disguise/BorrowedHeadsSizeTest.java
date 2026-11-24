package dev.morphreborn.paper.disguise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * How big a lent head is drawn for players without the mod on a giant or an ender dragon, against the size the client mod
 * draws it at (LentHeadSize, which mirrors {@link BorrowedHeads#modScale}); rig B3-08, 2026-11-17. Sized by the real table
 * (morph-heads.json), every creature at its table's own size (a body drawn at the size its numbers were measured at).
 */
class BorrowedHeadsSizeTest {
	private static final Map<String, BorrowedHeads.Head> TABLE = new HashMap<>();

	@BeforeAll
	static void load() throws Exception {
		try (var in = BorrowedHeadsSizeTest.class.getClassLoader().getResourceAsStream("morph-heads.json")) {
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			root.entrySet().forEach(e -> TABLE.put(e.getKey(), BorrowedHeads.read(e.getKey(), e.getValue().getAsJsonObject())));
		}
	}

	private static BorrowedHeads.Head head(String name) {
		BorrowedHeads.Head h = TABLE.get("minecraft:" + name);
		assertNotNull(h, name);
		return h;
	}

	/** The side of the cube of a head's big blocks, in blocks as drawn. */
	private static double core(BorrowedHeads.Head h) {
		double[][] c = h.crown();
		return Math.cbrt((c[1][0] - c[0][0]) * (c[1][1] - c[0][1]) * (c[1][2] - c[0][2])) * h.scale();
	}

	/** The side of the cube of a head's bare big blocks (without a humanoid's 0.5 pixel hat overlay), in blocks as drawn: the head the mod holds a lent one to. */
	private static double bare(BorrowedHeads.Head h) {
		double[][] c = h.opaque() != null ? h.opaque() : h.crown();
		return Math.cbrt((c[1][0] - c[0][0]) * (c[1][1] - c[0][1]) * (c[1][2] - c[0][2])) * h.scale();
	}

	/** The pack's drawn size of {@code lent} on {@code main}, as the side of its big blocks. */
	private static double packSize(String main, String lent, boolean whole) {
		BorrowedHeads.Head body = head(main), head = head(lent);
		return core(head) * BorrowedHeads.cover(body, 1, head, 1, whole, main.equals("giant") || main.equals("ender_dragon"));
	}

	/** The mod's drawn size of {@code lent} on {@code main} (HybridMorph.place, by LentHeadSize). */
	private static double modSize(String main, String lent, boolean whole) {
		return core(head(lent)) * BorrowedHeads.modScale(whole, main.equals("giant") || main.equals("ender_dragon"), bare(head(main)), core(head(lent)));
	}

	@Test
	void theDragonIsABodyForALentHeadAndStillLendsOnlyItsParts() {
		BorrowedHeads.Head dragon = head("ender_dragon");
		assertNotNull(dragon.neck(), "its own head is where a lent one goes");
		assertFalse(dragon.lendsHead(), "as a lent form it still brings its wings and tail, not its head");
		assertFalse(dragon.parts().isEmpty());
		assertNotNull(dragon.crown());
		// its head: the one-block upper head the lent head covers
		assertEquals(1.0, core(dragon), 1e-3);
	}

	@Test
	void theDragonsHeadHangsWhereItDoesWhenItHoversAndOnlyTheDragonsDoes() {
		// dumped from the client posed as a hovering dragon is (2026-11-17, rig B3-08): its neck bends down and the head hangs
		// at the end of it, 45 degrees down. At rest the pivot was at z 2.875 and y 0.25, under the middle of the neck.
		BorrowedHeads.Head dragon = head("ender_dragon");
		assertEquals(Math.PI / 4, dragon.tilt(), 1e-3);
		assertEquals(5.68, dragon.neck().getZ(), 0.01);
		assertEquals(1.2, dragon.neck().getY(), 0.01);
		for (String body : new String[] {"giant", "zombie", "cow", "ghast", "horse"}) {
			assertEquals(0.0, head(body).tilt(), 0.0, body + "'s head follows the look");
		}
	}

	@Test
	void aGhastOnADragonIsDrawnNoBiggerThanItsHeadForEveryone() {
		for (String ghast : new String[] {"ghast", "happy_ghast"}) {
			double mod = modSize("ender_dragon", ghast, true);
			assertEquals(1.0, mod, 1e-3, "the mod draws it as big as the dragon's head, not as big as the dragon: " + ghast);
			double pack = packSize("ender_dragon", ghast, true);
			// the pack draws it as the mod does, within the 4 percent a cover adds (2026-11-17: "fix")
			assertTrue(pack >= mod - 1e-9 && pack <= 1.04 * mod + 1e-9, ghast + " in the pack " + pack + " against the mod's " + mod);
		}
	}

	@Test
	void aSmallHeadOnADragonIsGrownAndCappedTheSameWay() {
		for (String lent : new String[] {"cow", "zombie", "creeper", "pig"}) {
			double mod = modSize("ender_dragon", lent, false);
			assertEquals(1.0, mod, 1e-3, lent + " is scaled up to the dragon's head");
			double pack = packSize("ender_dragon", lent, false);
			assertTrue(pack >= mod - 1e-9 && pack <= 1.04 * mod + 1e-9, lent + " in the pack " + pack + " against the mod's " + mod);
		}
	}

	@Test
	void aBigHeadOnADragonIsCappedAtOneAndAHalfTimesItsHead() {
		// a giant's head is 3.4 blocks, the dragon's 1
		double mod = modSize("ender_dragon", "giant", false);
		assertEquals(1.5, mod, 1e-3);
		assertEquals(mod, packSize("ender_dragon", "giant", false), 1e-3);
	}

	@Test
	void theWholeBodiesOnAGiantAreTheSameSizeInThePackAndTheMod() {
		// the mod drew a ghast at its own size, 1.28 times wider than the pack's, which covers the giant's head by 4 percent
		for (String lent : new String[] {"ghast", "happy_ghast", "squid", "glow_squid", "magma_cube", "slime"}) {
			double mod = modSize("giant", lent, true);
			double pack = packSize("giant", lent, true);
			assertEquals(bare(head("giant")), mod, 1e-3, lent + " is as big as the giant's bare head in the mod");
			// within the 4 percent a cover adds, a slim creature (a squid) too: the pack no longer widens it to hide the giant's head
			assertTrue(pack >= mod - 1e-9 && pack <= 1.04 * mod + 1e-9, lent + ": pack " + pack + ", mod " + mod);
		}
	}

	@Test
	void aHumanoidsHatOverlayIsNotThePartOfItsHeadALentOneIsHeldTo() {
		// the table's crown holds the 0.5 pixel overlay (a zombie's 0.5625), `opaque` the bare head (0.5): the mod measures the bare one (HybridMorph.restHeadBox)
		for (String name : new String[] {"zombie", "skeleton", "wither_skeleton", "giant", "villager"}) {
			BorrowedHeads.Head h = head(name);
			assertTrue(h.opaque() != null && bare(h) < core(h) - 1e-3, name + ": the bare head is smaller than the crown");
		}
		assertEquals(0.5, bare(head("zombie")), 1e-3);
		assertEquals(0.6, bare(head("wither_skeleton")), 1e-3);
		// a big head (a wither's, a giant's) on a wither skeleton: 1.5 times its bare 0.6, as the mod holds it, not 1.5 times the 0.675 with the overlay
		for (String lent : new String[] {"giant", "ravager"}) {
			double held = core(head(lent)) * BorrowedHeads.modScale(false, false, bare(head("wither_skeleton")), core(head(lent)));
			assertEquals(1.5 * 0.6, held, 1e-3, lent);
			// and the pack's own cover never draws it past the mod's size by more than what a cover adds over it (COVER_MAX), from that reference
			double pack = packSize("wither_skeleton", lent, false);
			assertTrue(pack <= 1.5 * held + 1e-6, lent + " in the pack " + pack);
		}
	}

	@Test
	void everythingElseKeepsItsSize() {
		// the mod's rule on a person: a creature lent whole no bigger than the head it replaces, a big head at most 1.5 times
		assertEquals(0.5625, core(head("zombie")) * BorrowedHeads.modScale(false, false, 0.5625, core(head("zombie"))), 1e-3);
		assertEquals(1.5 * 0.5625, core(head("giant")) * BorrowedHeads.modScale(false, false, 0.5625, core(head("giant"))), 1e-3);
		assertEquals(0.5625, core(head("ghast")) * BorrowedHeads.modScale(true, false, 0.5625, core(head("ghast"))), 1e-3);
	}

	/** The x, y and z sides of a head's big blocks at the size it is drawn, as the box {lo, hi} {@link BorrowedHeads#axisFit} takes. */
	private static double[][] box(BorrowedHeads.Head h) {
		double[][] c = h.crown();
		return new double[][] {{0, 0, 0}, {(c[1][0] - c[0][0]) * h.scale(), (c[1][1] - c[0][1]) * h.scale(), (c[1][2] - c[0][2]) * h.scale()}};
	}

	/** {@link #box} of the bare head (the reference the mod holds a lent head to, HybridMorph.restHeadBox). */
	private static double[][] bareBox(BorrowedHeads.Head h) {
		double[][] c = h.opaque() != null ? h.opaque() : h.crown();
		return new double[][] {{0, 0, 0}, {(c[1][0] - c[0][0]) * h.scale(), (c[1][1] - c[0][1]) * h.scale(), (c[1][2] - c[0][2]) * h.scale()}};
	}

	/** The mod's per-axis cap (LentHeadSize.grow with axisFit) for the pack's table: the longest side of the lent head as drawn, in multiples of the main's. */
	private static double overshoot(String main, String lent) {
		BorrowedHeads.Head body = head(main), lender = head(lent);
		double k = BorrowedHeads.modScale(false, false, bare(body), core(lender), BorrowedHeads.axisFit(bareBox(body), box(lender)));
		double[][] b = bareBox(body), l = box(lender);
		double worst = 0;
		for (int axis = 0; axis < 3; axis++) worst = Math.max(worst, l[1][axis] * k / Math.max(b[1][axis], 0.25 * Math.max(b[1][0], Math.max(b[1][1], b[1][2]))));
		return worst;
	}

	@Test
	void noLentHeadIsMoreThanOneAndAHalfTimesTheHeadItReplacesOnAnySide() {
		// the cube roots let a llama's column, a ravager's, a warden's and a wither's through (rig, 2026-11-20): none passes a side now
		for (String main : new String[] {"zombie", "cat", "allay", "creeper", "pig", "villager", "iron_golem"}) {
			for (String lent : new String[] {"llama", "ravager", "warden", "wither", "sniffer", "creaking"}) {
				if (TABLE.get("minecraft:" + main) == null || TABLE.get("minecraft:" + lent) == null) continue;
				assertTrue(overshoot(main, lent) <= 1.5 + 1e-6, lent + " on " + main + ": " + overshoot(main, lent));
			}
		}
	}

	@Test
	void aTallColumnIsShrunkAndAHeadThatAlreadyFitsIsNot() {
		double llamaOnZombie = BorrowedHeads.modScale(false, false, core(head("zombie")), core(head("llama")), BorrowedHeads.axisFit(box(head("zombie")), box(head("llama"))));
		assertTrue(llamaOnZombie < 1, "a llama's column on a zombie's head is held: " + llamaOnZombie);
		// a pig's head on a pig's is exactly itself
		assertEquals(1.0, BorrowedHeads.modScale(false, false, core(head("pig")), core(head("pig")), BorrowedHeads.axisFit(box(head("pig")), box(head("pig")))), 1e-9);
		// nothing measured: the cube roots alone, as before
		assertEquals(1.0, BorrowedHeads.modScale(false, false, 0.5, 0.5, Double.MAX_VALUE), 1e-9);
	}

	// ---- the pure rules added in the grid cleanup, 2026-11-20 (the client mod's LentHeadSizeTest holds the same numbers)

	@Test
	void aFlatMainHeadsFloorIsAParameter() {
		double[][] hoglin = {{0, 0, 0}, {0.875, 0.375, 1.1875}}, ravager = {{0, 0, 0}, {1, 1.25, 1}};
		assertEquals(0.375 / 1.25, BorrowedHeads.axisFit(hoglin, ravager), 1e-9);
		assertEquals(0.375 / 1.25, BorrowedHeads.axisFit(hoglin, ravager, BorrowedHeads.FLAT_FLOOR), 1e-9);
		assertEquals(0.5 * 1.1875 / 1.25, BorrowedHeads.axisFit(hoglin, ravager, BorrowedHeads.PITCHED_FLAT_FLOOR), 1e-9);
	}

	@Test
	void anEarIsNeverTallerThanThreeQuartersOfTheHeadItStandsOn() {
		assertEquals(0.75 * 0.5, 0.45 * BorrowedHeads.earCap(1.33, 0.45, 0.5, false), 1e-9);
		assertEquals(0.8, BorrowedHeads.earCap(0.8, 0.3, 0.5, false), 1e-9);
		// a giant's, a dragon's or a ghast's ears grow with their heads, but not past one of them
		assertEquals(1.0, 0.3 * BorrowedHeads.earCap(10, 0.3, 1, true), 1e-9);
		assertEquals(4.0, BorrowedHeads.earCap(4, 0.1, 1, true), 1e-9);
		assertEquals(2.0, BorrowedHeads.earCap(2, 0, 0.5, false), 1e-9);
	}

	@Test
	void aLongThinTorsoHoldsALentTailToItsWidthToo() {
		double s = BorrowedHeads.tailScale(1, 0.71, 0.36, 0.8, 0.2);
		assertEquals(0.8 * 0.2, 0.36 * s, 1e-9);
		assertEquals(1.0, BorrowedHeads.tailScale(1, 0.71, 0.1, 0.8, 0.2), 1e-9, "thin enough: the length rule alone");
		assertEquals(0.2, BorrowedHeads.tailScale(1, 0.5, 2, 5, 0.05), 1e-9, "never under the floor of its scale");
	}

	@Test
	void aThinTorsoPutsWingsAtItsSideEdgesAndABigBodyNeverLeavesAThemASpeck() {
		assertEquals(0.0, BorrowedHeads.wingInset(0.25), 1e-9);
		assertEquals(0.1, BorrowedHeads.wingInset(0.5), 1e-9);
		assertEquals(0.15 * 3, 0.375 * BorrowedHeads.partFloor(1, 0.375, 3), 1e-9);
		assertEquals(1.0, BorrowedHeads.partFloor(1, 0.375, 1.1), 1e-9, "big enough already");
		assertEquals(2.0, BorrowedHeads.partFloor(2, 0, 3), 1e-9, "nothing measured");
		assertEquals(24.0, BorrowedHeads.partFloor(1, 0.011, 4.5), 1e-9, "never raised past the parts maximum");
		assertEquals(BorrowedHeads.wingInset(0.35), BorrowedHeads.wingInset(0.35 - 1e-4), 1e-3, "eased in under the line, no jump");
		assertEquals(0.0, BorrowedHeads.wingInset(0.3), 1e-9);
	}

	@Test
	void aFlatFinIsRaisedToAThirdOfTheTorsoAndNoFinIsMoreThanSixTenths() {
		// a tropical fish's fin (0.375 across, flat) on a 0.75 torso: 0.3 of it, not the 0.15 every part gets
		assertTrue(BorrowedHeads.planar(new double[] {0.02, 0.3, 0.375}));
		assertFalse(BorrowedHeads.planar(new double[] {0.06, 0.06, 0.5}), "a stick is not a plane");
		assertEquals(0.3 * 0.75, 0.375 * BorrowedHeads.partFloor(0.1, 0.375, 0.75, true), 1e-9);
		assertEquals(1.0, BorrowedHeads.partFloor(1, 0.375, 0.75, true), 1e-9, "already above it");
		assertEquals(0.15 * 0.75 / 0.375, BorrowedHeads.partFloor(0.1, 0.375, 0.75, false), 1e-9, "other parts keep 0.15");
		assertEquals(0.6 * 0.5 / 0.4, BorrowedHeads.finCap(1, 0.4, 0.5), 1e-9, "a dolphin flipper held to 0.6 of the torso");
		assertEquals(1.0, BorrowedHeads.finCap(1, 0.4, 3.0), 1e-9, "never raised");
		assertEquals(0.3 * 0.75 / 0.3, BorrowedHeads.tailFloor(0.2, 0.3, 0.75, true), 1e-9, "a flat tail has the same floor");
		assertEquals(0.2, BorrowedHeads.tailFloor(0.2, 0.3, 0.75, false), 1e-9, "a thick tail does not");
	}

	@Test
	void theFlatTailFloorIsHeldToTheThicknessLimitToo() {
		// a flat tail 0.3 long and 0.25 across on a torso 0.75 long and 0.15 thin: the floor stops at 0.8 * 0.15 / 0.25 = 0.48, not 0.75
		assertEquals(0.75, BorrowedHeads.tailFloor(0.2, 0.3, 0.75, true), 1e-9);
		assertEquals(0.48, BorrowedHeads.tailFloor(0.48, 0.3, 0.25, 0.75, 0.15, true), 1e-9);
		assertEquals(0.75, BorrowedHeads.tailFloor(0.2, 0.3, 0.25, 0.75, 0.5, true), 1e-9, "a thick enough torso keeps the floor");
		assertEquals(1.5, BorrowedHeads.tailFloor(1.5, 0.3, 0.25, 0.75, 0.15, true), 1e-9, "never made smaller");
		assertEquals(0.2, BorrowedHeads.tailFloor(0.2, 0.3, 0.25, 0.75, 0.15, false), 1e-9, "a thick tail has none");
	}

	@Test
	void aLentHeadOnABodyWithNoHeadPartIsHeldToItsCore() {
		// a giant's 3 block head on a slime's 0.6 core: 1.5 of it, 0.9 block, not 3; on a silverfish's 0.4 (tiny) 1 times it
		assertEquals(0.9 / 3, BorrowedHeads.standInCap(false, "minecraft:slime", 0.6, 3.0), 1e-9);
		// a silverfish's table crown is 0.61 (all seven segments); the mod's core is its three big ones, 0.36: held to 1 times that, not 1.5 times 0.61
		assertEquals(0.36 / 3, BorrowedHeads.standInCap(false, "minecraft:silverfish", 0.61, 3.0), 1e-9, "round 5: a giant's head was 2.8 times the mod's");
		// a tiny headless body holds it to 1 times the core: the bee's 0.4927 is under 0.4965
		assertEquals(0.4927, BorrowedHeads.standInCap(false, "minecraft:bee", 0.4927, 1.0), 1e-9);
		assertEquals(1.0, BorrowedHeads.standInCap(false, "minecraft:silverfish", 0.61, 0.3), 1e-9, "a small head keeps its size");
		// a ghast and an elder guardian: held to 1.5 times the mod's stand-in (half the cube) as every head is, the 9 block head too (round 5)
		assertEquals(3.0 / 9, BorrowedHeads.standInCap(false, "minecraft:ghast", 4.0, 9.0), 1e-9, "1.5 times the 2 block stand-in");
		assertEquals(1.35 / 9, BorrowedHeads.standInCap(false, "minecraft:elder_guardian", 1.8, 9.0), 1e-9, "1.5 times the 0.9 block stand-in");
		// a giant's 3 block head on the elder guardian's 2.35 block cube: the stand-in is 1.175 and the cap 1.76
		assertEquals(1.7625 / 3.0, BorrowedHeads.standInCap(false, "minecraft:elder_guardian", 2.35, 3.0), 1e-9);
		// a creature lent whole is exactly the stand-in, down as well as up: a ghast on a ghast is 2 blocks, not 4
		assertEquals(2.0 / 4.0, BorrowedHeads.standInCap(true, "minecraft:ghast", 4.0, 4.0), 1e-9);
		// and no head asks for more than 24 times its size
		assertEquals(24.0, BorrowedHeads.standInCap(false, "minecraft:ghast", 4.0, 0.002), 1e-9);
	}

	@Test
	void aSmallHeadOnAGhastOrAnElderGuardianIsGrownToTheModsStandIn() {
		// round 5: the mod's stand-in head is half the cube (2 blocks on the ghast's 4) and grows a zombie's 0.5 block head up to it; the pack drew it at 0.5
		assertEquals(2.0 / 0.5, BorrowedHeads.standInCap(false, "minecraft:ghast", 4.0, 0.5), 1e-9);
		assertEquals(2.0 / 0.5, BorrowedHeads.standInCap(false, "minecraft:happy_ghast", 4.0, 0.5), 1e-9);
		assertEquals(1.175 / 0.5, BorrowedHeads.standInCap(false, "minecraft:elder_guardian", 2.35, 0.5), 1e-9);
		// a creature lent whole is exactly as big as that head, up here
		assertEquals(2.0 / 0.75, BorrowedHeads.standInCap(true, "minecraft:ghast", 4.0, 0.75), 1e-9);
	}

	@Test
	void aSilverfishSeatsALentHeadOnItsThickSegmentsAndNotOnItsBackPlates() {
		// the table's neck for a whole body is the top of everything it draws, and a silverfish's is its thin back plates (0.5 block); the mod seats a head on
		// the three thick segments, 0.25 block up, and the pack seat a quarter block higher hovered a giant's head over the worm (rig, 2026-11-22)
		BorrowedHeads.Head fish = head("silverfish");
		assertEquals(0.25, fish.neck().getY(), 1e-6, "the top of the thick segments");
		assertTrue(fish.neck().getY() < fish.crown()[1][1] - 0.2, "under the plates' top, which the crown still holds");
	}
}
