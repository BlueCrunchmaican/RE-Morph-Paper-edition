package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the looks that must not change did not (2026-11-24 accessory work): the quad streams recorded before that work are in
 * {@code src/test/resources/accessory-golden.txt}, and the lantern (every view and detail level), the shield's classic bands (third
 * person), the shield in the overlay while it is up, and the spyglass and flint in the overlay still draw those exact quads. Set
 * {@code ACCESSORY_GOLDEN_RECORD} to rewrite the file (only when a look changed on purpose).
 */
class AccessoryGoldenTest {
	private static final Path FILE = Path.of("src", "test", "resources", "accessory-golden.txt");

	@BeforeEach
	@AfterEach
	void clean() {
		ShieldMotion.clear();
		FlintMotion.clear();
		SpyglassMotion.clear();
		LureGeometry.clear();
		LureGeometry.setOffhand(0F);
		ShieldRenderer.classicBands(true);
	}

	/** The keys whose look must be exactly the recorded one. */
	static boolean mustStay(String key) {
		if (key.startsWith("lantern/")) return true;
		boolean fp = key.contains("/fp/");
		if (key.startsWith("shield/")) {
			// classic bands in third person; the overlay's shield is placed and lit differently on purpose (the cue, 2026-11-24)
			return !fp;
		}
		// the spyglass and flint overlay: the same stalk from the screen edge
		return fp;
	}

	@Test
	void unchangedLooksStayByteForByte() throws IOException {
		Map<String, String> now = AccessoryGolden.grid();
		if (System.getenv("ACCESSORY_GOLDEN_RECORD") != null || !Files.exists(FILE)) {
			Files.createDirectories(FILE.getParent());
			Files.write(FILE, AccessoryGolden.lines(now), StandardCharsets.UTF_8);
			return;
		}
		List<String> recorded = Files.readAllLines(FILE, StandardCharsets.UTF_8);
		Map<String, String> before = new HashMap<>();
		for (String line : recorded) {
			int sp = line.indexOf(' ');
			before.put(line.substring(0, sp), line.substring(sp + 1));
		}
		int checked = 0;
		for (Map.Entry<String, String> e : before.entrySet()) {
			if (!mustStay(e.getKey())) continue;
			assertEquals(e.getValue(), now.get(e.getKey()), "the look changed: " + e.getKey());
			checked++;
		}
		assertTrue(checked > 300, "the golden grid covers " + checked + " draws");
	}
}
