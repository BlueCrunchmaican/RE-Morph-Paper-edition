package dev.morphreborn.paper.disguise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where borrowed parts go for players without the mod ({@link BorrowedHeads#place}, the client mod's Graft.place), and
 * that every part the plugin's table names has a model in the resource pack.
 */
class BorrowedPartsTest {
	/** A standing torso (a person's, roughly): 0.5 wide, 0.75 tall, 0.25 deep, from 0.75 up. Item space: the back is +z. */
	private static final double[][] STANDING = {{-0.25, 0.75, -0.125}, {0.25, 1.5, 0.125}};
	private static final double[][] STANDING_ALL = {{-0.5, 0, -0.25}, {0.5, 2, 0.25}};
	/** A lying torso (a cow's, roughly): 0.75 wide, 0.6 tall, 1.1 long. */
	private static final double[][] LYING = {{-0.375, 0.6, -0.55}, {0.375, 1.2, 0.55}};
	private static final double[][] LYING_ALL = {{-0.45, 0, -0.9}, {0.45, 1.4, 0.6}};

	@Test
	void wingsGoOnAStandingBackAtTheShoulderBlades() {
		// a wing joining the middle of its own (standing) torso's side
		double[] at = BorrowedHeads.place(STANDING, STANDING, STANDING_ALL, STANDING_ALL, new double[] {0.25, 1.1, 0}, "wing", 1);
		assertTrue(at[2] >= 0.125 + 0.04 - 1e-9, "behind the back, clear of it: " + at[2]);
		assertTrue(at[1] >= 0.75 + 0.92 * 0.75 - 1e-9, "at the shoulder blades: " + at[1]);
		assertTrue(at[0] <= 0.25 - 0.2 * 0.5 + 1e-9, "near the middle: " + at[0]);
	}

	@Test
	void wingsGoOnALyingBodysFlanks() {
		double[] left = BorrowedHeads.place(STANDING, LYING, STANDING_ALL, LYING_ALL, new double[] {-0.2, 1.1, 0}, "wing", 1);
		double[] right = BorrowedHeads.place(STANDING, LYING, STANDING_ALL, LYING_ALL, new double[] {0.2, 1.1, 0}, "wing", 1);
		assertTrue(left[0] <= -0.375 - 0.04 + 1e-9, "left flank: " + left[0]);
		assertTrue(right[0] >= 0.375 + 0.04 - 1e-9, "right flank: " + right[0]);
		assertTrue(left[1] >= 0.6 + 0.67 * 0.6 - 1e-9, "high up: " + left[1]);
	}

	@Test
	void aLyingTailJoinsAStandingBackLow() {
		// a wolf's tail: at its torso's rear (+z), near the top; on a person it goes to the small of the back
		double[] at = BorrowedHeads.place(LYING, STANDING, LYING_ALL, STANDING_ALL, new double[] {0, 1.1, 0.55}, "tail", 1);
		assertEquals(0.125 - 0.03, at[2], 1e-9, "at the back, set in");
		// the rear becomes the bottom of the torso, and from the small of the back up: 15 percent of the torso's height (the mod's TAIL_LOW)
		assertEquals(0.75 + 0.15 * 0.75, at[1], 1e-9);
	}

	@Test
	void aTailOnALyingBodyIsNotRaised() {
		double[] at = BorrowedHeads.place(LYING, LYING, LYING_ALL, LYING_ALL, new double[] {0, 0.7, 0.55}, "tail", 1);
		assertEquals(0.7, at[1], 1e-9);
	}

	@Test
	void rodsKeepTheirShareOfTheWholeHeight() {
		// a blaze's rod a quarter of the way up its whole body goes a quarter of the way up the main body
		double[] at = BorrowedHeads.place(STANDING, LYING, STANDING_ALL, LYING_ALL, new double[] {0.3, 0.5, 0}, "rods", 1);
		assertEquals(0.25 * 1.4, at[1], 1e-9);
	}

	@Test
	void aBreezesRodsStandRoundTheMiddleOfTheTorsoAndABlazesKeepTheirShare() {
		double[][] tall = {{-0.25, 0.5, -0.125}, {0.25, 2.5, 0.125}}, tallAll = {{-0.5, 0, -0.25}, {0.5, 3, 0.25}};
		// breeze: the rods run 0.16 above their pivot to 0.35 below it, so the pivot is a little above the middle of the torso and
		// their own middle is at it; and on the torso's own axis, not where a lying body's mapping put it
		double[] breeze = BorrowedHeads.place(STANDING, tall, STANDING_ALL, tallAll, new double[] {0.3, 0.2, 0}, "rods", "rods", 1);
		assertEquals(1.5 + BorrowedHeads.RODS_MIDDLE_BELOW, breeze[1], 1e-9);
		assertEquals(0, breeze[0], 1e-9);
		assertEquals(0, breeze[2], 1e-9);
		// scaled with the body they go on
		double[] big = BorrowedHeads.place(STANDING, tall, STANDING_ALL, tallAll, new double[] {0.3, 0.2, 0}, "rods", "rods", 2);
		assertEquals(1.5 + 2 * BorrowedHeads.RODS_MIDDLE_BELOW, big[1], 1e-9);
		// on a lying body they stand at the middle of the torso, not at its rear
		double[] cow = BorrowedHeads.place(STANDING, LYING, STANDING_ALL, LYING_ALL, new double[] {0.3, 0.2, 0}, "rods", "rods", 1);
		assertEquals(0.9 + BorrowedHeads.RODS_MIDDLE_BELOW, cow[1], 1e-9);
		assertEquals(0, cow[2], 1e-9);
		// a blaze's part0 keeps its share of the whole body's height, as before
		double[] blaze = BorrowedHeads.place(STANDING, LYING, STANDING_ALL, LYING_ALL, new double[] {0.3, 0.5, 0}, "rods", "part0", 1);
		assertEquals(0.25 * 1.4, blaze[1], 1e-9);
	}

	@Test
	void aBeesStingerGoesIntoTheRearOfTheBodyNotBehindItsMiddle() {
		// its plate stands 5 pixels behind the pivot: the pivot is that far in front of the rear face, less the set-in
		double[] at = BorrowedHeads.place(LYING, STANDING, LYING_ALL, STANDING_ALL, new double[] {0, 1.1, 0}, "other", "stinger", 1);
		assertEquals(0.125 - 5.0 / 16.0 - 0.03, at[2], 1e-9);
	}

	@Test
	void wingsOnAFlatLyingBodyTouchItAndNeverStandOverItsTop() {
		double[][] mantle = {{-0.15, 0.2, -0.3}, {0.15, 0.5, 0.3}};
		// a bee's joint lands above a flat mantle: the discs come down to its top, touching its side (a body under 0.4 wide)
		double[] at = BorrowedHeads.place(STANDING, mantle, STANDING_ALL, mantle, new double[] {-0.2, 1.4, 0}, "wing", 1);
		assertTrue(at[1] <= 0.5 - 0.03 + 1e-9, "not over the top: " + at[1]);
		assertEquals(-0.15 + 0.03, at[0], 1e-9, "into the flank by the set-in, not 0.04 off it");
	}

	@Test
	void wingRootsAboveTheBackOfAWideLyingBodyStayAboveIt() {
		// a cow's, wolf's or horse's back (0.75 wide): the over-the-top clamp is for the tiny bodies only, so pairs settled earlier do not move
		double[] at = BorrowedHeads.place(LYING, LYING, LYING_ALL, LYING_ALL, new double[] {-0.2, 1.8, 0}, "wing", 1);
		assertEquals(1.8, at[1], 1e-9, "the root stays where its own distance above the back puts it");
		assertEquals(-0.375 - 0.04, at[0], 1e-9, "0.04 off the flank");
	}

	@Test
	void aTailLongerThanTheTorsoIsDrawnAsLongAsIt() {
		assertEquals(0.3 / 1.25, BorrowedHeads.tailScale(1, 1.25, 0.3), 1e-9);
		assertEquals(0.2, BorrowedHeads.tailScale(1, 10, 0.3), 1e-9);
		assertEquals(1, BorrowedHeads.tailScale(1, 0.5, 0.75), 1e-9);
	}

	@Test
	void anEarUnderTwoPixelsKeepsHalfOfItAboveTheHead() {
		assertEquals(1.0 / 32, BorrowedHeads.earSink(1.0 / 16), 1e-9);
		assertEquals(0.0625, BorrowedHeads.earSink(2.0 / 16), 1e-9);
		assertEquals(0.2 / 3, BorrowedHeads.earSink(0.2), 1e-9);
	}

	@Test
	void partsGrowOnlyOnHugeBodiesAndAreCapped() {
		assertEquals(1, BorrowedHeads.partScale(false, 3.5), 1e-9, "a horse with an allay's wings");
		assertEquals(1, BorrowedHeads.partScale(true, 2.5), 1e-9);
		assertEquals(6, BorrowedHeads.partScale(true, 6), 1e-9);
		assertEquals(24, BorrowedHeads.partScale(true, 5000), 1e-9, "a thin torso cannot blow them up");
		assertTrue(BorrowedHeads.partScale(true, 3.2) > 1 && BorrowedHeads.partScale(true, 3.2) < 4);
	}

	@Test
	void earsAreNeverTwiceTheHeadExceptOnAGiantOrADragonAndNeverUnderASeventh() {
		assertEquals(2, BorrowedHeads.earScale(2.5, false), 1e-9);
		assertEquals(0.15, BorrowedHeads.earScale(0.05, false), 1e-9);
		assertEquals(0.4, BorrowedHeads.earScale(0.4, false), 1e-9);
		assertEquals(6.8, BorrowedHeads.earScale(6.8, true), 1e-9);
	}

	@Test
	void theDragonsPartsAreScaledToTheBody() {
		double[][] dragon = {{-0.75, 0, 0.5}, {0.75, 1.5, 4.5}}; // lying, 1.5 x 1.5 x 4
		double fit = BorrowedHeads.fit(dragon, STANDING);
		// lying on standing: main height against own length, main depth against own height
		assertEquals(Math.cbrt((0.5 / 1.5) * (0.75 / 4.0) * (0.25 / 1.5)), fit, 1e-9);
	}

	@Test
	void everyPartHasAModelInThePack() throws Exception {
		Path pack = Path.of("..", "resourcepack", "assets", "morphreborn");
		JsonObject table;
		try (var in = BorrowedPartsTest.class.getClassLoader().getResourceAsStream("morph-heads.json")) {
			table = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		List<String> missing = new ArrayList<>();
		int parts = 0;
		for (Map.Entry<String, JsonElement> e : table.entrySet()) {
			JsonObject h = e.getValue().getAsJsonObject();
			if (!h.has("parts")) continue;
			String mob = e.getKey().substring(e.getKey().indexOf(':') + 1);
			for (JsonElement p : h.getAsJsonArray("parts")) {
				String name = p.getAsJsonObject().get("name").getAsString();
				parts++;
				for (String dir : new String[] {"models", "items"}) {
					Path model = pack.resolve(dir).resolve("part").resolve(mob).resolve(name + ".json");
					if (!Files.isRegularFile(model)) missing.add(model.toString());
				}
			}
			if (h.has("ears") && !Files.isRegularFile(pack.resolve("models").resolve("ears").resolve(mob + ".json"))) missing.add("ears/" + mob);
		}
		assertTrue(parts > 0, "the table has parts");
		assertTrue(missing.isEmpty(), "missing from the pack: " + missing);
	}
}
