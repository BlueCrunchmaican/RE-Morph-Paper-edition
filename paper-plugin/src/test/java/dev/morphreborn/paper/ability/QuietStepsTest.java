package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Quiet steps: which movement vibrations a form silences (pure rules), the shipped table and its settings, and the places the trait is
 * documented and gated. The event wiring itself (AbilityListener.onGameEvent) needs a running server and is checked on the rig.
 */
class QuietStepsTest {
	/** Every living mob of Minecraft 26.3, the list tools/gen_abilities_doc.py holds. */
	private static final List<String> MOBS = List.of(
			"allay", "armadillo", "axolotl", "bat", "bee", "blaze", "bogged", "breeze", "camel", "camel_husk", "cat", "cave_spider", "chicken",
			"cod", "copper_golem", "cow", "creaking", "creeper", "dolphin", "donkey", "drowned", "elder_guardian", "ender_dragon", "enderman",
			"endermite", "evoker", "fox", "frog", "ghast", "giant", "glow_squid", "goat", "guardian", "happy_ghast", "hoglin", "horse", "husk",
			"illusioner", "iron_golem", "llama", "magma_cube", "mooshroom", "mule", "nautilus", "ocelot", "panda", "parched", "parrot", "phantom",
			"pig", "piglin", "piglin_brute", "pillager", "polar_bear", "pufferfish", "rabbit", "ravager", "salmon", "sheep", "shulker",
			"silverfish", "skeleton", "skeleton_horse", "slime", "sniffer", "snow_golem", "spider", "squid", "stray", "strider", "sulfur_cube",
			"tadpole", "trader_llama", "tropical_fish", "turtle", "vex", "villager", "vindicator", "wandering_trader", "warden", "witch", "wither",
			"wither_skeleton", "wolf", "zoglin", "zombie", "zombie_horse", "zombie_nautilus", "zombie_villager", "zombified_piglin");

	private static final Set<String> QUIET = Set.of("minecraft:fox", "minecraft:rabbit", "minecraft:cat");

	private static YamlConfiguration yaml(String name) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(QuietStepsTest.class.getResourceAsStream("/" + name)), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	private static QuietSteps.Settings shipped() throws Exception {
		return QuietSteps.Settings.read(Objects.requireNonNull(yaml("abilities.yml").getConfigurationSection("passive.quiet-steps")));
	}

	// ------------------------------------------------------------------ the rules

	@Test
	void aStepIsSilencedOnlyForAQuietBody() {
		assertTrue(QuietSteps.silences(QuietSteps.Move.STEP, 0, 3, true, true));
		assertFalse(QuietSteps.silences(QuietSteps.Move.STEP, 0, 3, true, false));
	}

	@Test
	void swimmingIsSilencedOnlyWhenTheSettingIsOn() {
		assertTrue(QuietSteps.silences(QuietSteps.Move.SWIM, 0, 3, true, true));
		assertFalse(QuietSteps.silences(QuietSteps.Move.SWIM, 0, 3, false, true));
		assertFalse(QuietSteps.silences(QuietSteps.Move.SWIM, 0, 3, true, false));
	}

	@Test
	void aLandingIsSilentUpToTheSoftHeightAndLoudAboveIt() {
		for (float fall : new float[] {0.5F, 1, 2.99F, 3}) assertTrue(QuietSteps.silences(QuietSteps.Move.LANDING, fall, 3, true, true), "fall " + fall);
		for (float fall : new float[] {3.01F, 4, 10, 100}) assertFalse(QuietSteps.silences(QuietSteps.Move.LANDING, fall, 3, true, true), "fall " + fall);
	}

	@Test
	void aSoftHeightOfZeroLeavesEveryLandingLoudAndAHugeOneSilencesThemAll() {
		for (float fall : new float[] {0.1F, 1, 3, 10}) assertFalse(QuietSteps.silences(QuietSteps.Move.LANDING, fall, 0, true, true), "fall " + fall);
		assertTrue(QuietSteps.silences(QuietSteps.Move.LANDING, 200, 1000, true, true));
	}

	@Test
	void aBodyThatIsNotQuietIsNeverSilencedForAnyMove() {
		for (QuietSteps.Move move : QuietSteps.Move.values()) {
			assertFalse(QuietSteps.silences(move, 0, 1000, true, false), move.toString());
			assertFalse(QuietSteps.silences(move, 1, 1000, true, false), move.toString());
		}
	}

	@Test
	void theMainBodyDecidesWhateverIsLent() {
		assertTrue(QuietSteps.quietBody("minecraft:fox", "minecraft:creeper", QUIET), "a fox with a creeper head is quiet");
		assertFalse(QuietSteps.quietBody("minecraft:creeper", "minecraft:fox", QUIET), "a creeper with a fox head is heard");
		assertFalse(QuietSteps.quietBody("minecraft:player", "minecraft:fox", QUIET), "your own body with a fox head is heard");
		assertFalse(QuietSteps.quietBody(null, "minecraft:fox", QUIET), "no body to judge (own body, mid-transformation)");
		assertTrue(QuietSteps.quietBody("minecraft:rabbit", null, QUIET));
		assertFalse(QuietSteps.quietBody("minecraft:fox", null, Set.of()), "nothing is quiet when the list is empty");
	}

	// ------------------------------------------------------------------ the event filter (the part AbilityListener feeds)

	@Test
	void onlyAQuietBodysOwnMovementEventsAreDropped() throws Exception {
		QuietSteps.Settings table = shipped();
		// a non-movement game event (a hit, a door, an explosion...) arrives as no move at all
		assertFalse(table.silences(null, "minecraft:fox", 0), "everything that is not movement stays as loud as ever");
		assertFalse(table.silences(null, "minecraft:creeper", 0));
		// no body to judge: own body, no morph or still transforming
		for (QuietSteps.Move move : QuietSteps.Move.values()) assertFalse(table.silences(move, null, 0), move.toString());
		// the four named forms
		for (QuietSteps.Move move : List.of(QuietSteps.Move.STEP, QuietSteps.Move.SWIM)) {
			assertTrue(table.silences(move, "minecraft:fox", 0), "fox " + move);
			assertTrue(table.silences(move, "minecraft:rabbit", 0), "rabbit " + move);
			assertFalse(table.silences(move, "minecraft:creeper", 0), "creeper " + move);
			assertFalse(table.silences(move, "minecraft:skeleton", 0), "skeleton " + move);
		}
		// a hard landing is heard from a rabbit too; a soft one is not
		assertTrue(table.silences(QuietSteps.Move.LANDING, "minecraft:rabbit", 2));
		assertFalse(table.silences(QuietSteps.Move.LANDING, "minecraft:rabbit", 8));
		assertFalse(table.silences(QuietSteps.Move.LANDING, "minecraft:creeper", 1));
		// sneaking is not asked: a quiet form is silent either way and vanilla alone silences a sneaking normal one, so the filter
		// has no sneaking input and leaves a normal form's answer to vanilla
		assertFalse(table.silences(QuietSteps.Move.STEP, "minecraft:zombie", 0));
	}

	@Test
	void theSettingsReadWithTheirDefaultsAndNeverNegative() {
		assertEquals(QuietSteps.Settings.NONE, QuietSteps.Settings.read(null));
		YamlConfiguration y = new YamlConfiguration();
		y.set("types", List.of("Fox", "minecraft:rabbit", "custom:thing"));
		QuietSteps.Settings s = QuietSteps.Settings.read(y);
		assertEquals(Set.of("minecraft:fox", "minecraft:rabbit", "custom:thing"), s.types());
		assertEquals(3, s.softLanding(), "default: the fall that never hurts");
		assertTrue(s.swimming());
		y.set("soft-landing", -4);
		y.set("swimming", false);
		s = QuietSteps.Settings.read(y);
		assertEquals(0, s.softLanding(), "a negative height counts as none");
		assertFalse(s.swimming());
		assertTrue(QuietSteps.Settings.read(new YamlConfiguration()).types().isEmpty());
	}

	// ------------------------------------------------------------------ the shipped table

	@Test
	void theShippedTableIsTheAgreedOne() throws Exception {
		YamlConfiguration abilities = yaml("abilities.yml");
		ConfigurationSection section = abilities.getConfigurationSection("passive.quiet-steps");
		assertNotNull(section, "passive.quiet-steps is in the bundled abilities.yml");
		List<String> types = section.getStringList("types");
		assertFalse(types.isEmpty());
		assertTrue(types.contains("fox") && types.contains("rabbit"), "the two named soft-footed forms");
		for (String loud : List.of("creeper", "skeleton", "player")) assertFalse(types.contains(loud), loud + " is heard");
		assertEquals(types.size(), new HashSet<>(types).size(), "no form twice");
		for (String t : types) {
			assertEquals(t.toLowerCase(java.util.Locale.ROOT), t, t + " is lowercase");
			assertTrue(MOBS.contains(t), t + " is a mob of 26.3");
		}
		assertEquals(28, types.size());
		assertTrue(section.isInt("soft-landing") || section.isDouble("soft-landing"), "soft-landing is a number");
		assertTrue(section.getDouble("soft-landing") >= 0);
		assertEquals(3, section.getDouble("soft-landing"), "the fall that never hurts");
		assertTrue(section.isBoolean("swimming"), "swimming is a boolean");
		assertTrue(shipped().swimming());
	}

	// ------------------------------------------------------------------ docs (the gating is in survival/QuietStepsGateTest)

	@Test
	void theGeneratedAbilityListLabelsEveryQuietFormAndNoLoudOne() throws Exception {
		Path doc = Path.of("..", "docs", "ABILITIES.md");
		assumeTrue(Files.exists(doc), "run from the repo");
		List<String> rows = Files.readAllLines(doc, StandardCharsets.UTF_8);
		QuietSteps.Settings table = shipped();
		int labelled = 0;
		for (String row : rows) {
			if (!row.startsWith("| ") || row.startsWith("| Mob") || row.startsWith("|---")) continue;
			String mob = "minecraft:" + row.substring(2, row.indexOf(" |")).replace(' ', '_');
			boolean has = row.contains("Quiet steps");
			assertEquals(table.types().contains(mob), has, mob + " row: " + row);
			if (has) labelled++;
		}
		assertEquals(table.types().size(), labelled);
	}
}
