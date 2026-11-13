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
		assertEquals(0.75, at[1], 1e-9, "the rear becomes the bottom of the torso");
	}

	@Test
	void rodsKeepTheirShareOfTheWholeHeight() {
		// a blaze's rod a quarter of the way up its whole body goes a quarter of the way up the main body
		double[] at = BorrowedHeads.place(STANDING, LYING, STANDING_ALL, LYING_ALL, new double[] {0.3, 0.5, 0}, "rods", 1);
		assertEquals(0.25 * 1.4, at[1], 1e-9);
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
