package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** How an admin's old config.yml is brought up to a newer release (MorphRebornPlugin.upgradeConfig). */
class ConfigMergeTest {
	private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
		YamlConfiguration config = new YamlConfiguration();
		config.loadFromString(text);
		return config;
	}

	private static final String TEMPLATE = """
			# header

			config-version: 6.1.4

			# the mode
			gameplay-mode: classic

			morphs:
			  # ticks
			  transition-ticks: 100
			  disabled-types:
			    - minecraft:armor_stand
			  required-kills-per-type: {}

			attributes:
			  enabled: true
			  list:
			    minecraft:max_health: { direction: more, cap: 20 }
			    minecraft:jump_strength: { direction: more, cap: 0.7 }
			""";

	@Test
	void addsNewSettingsAndKeepsEveryValueTheAdminSet() throws Exception {
		YamlConfiguration template = yaml(TEMPLATE);
		YamlConfiguration admin = yaml("""
				morphs:
				  transition-ticks: 40
				  disabled-types: []
				  required-kills-per-type:
				    minecraft:wither: 3
				attributes:
				  enabled: false
				  list:
				    minecraft:max_health: { direction: more }
				""");
		List<String> added = ConfigMerge.merge(template, admin, "config-version");

		assertEquals(List.of("gameplay-mode", "attributes.list.minecraft:jump_strength"), added);
		assertEquals("classic", template.getString("gameplay-mode"));
		assertEquals(40, template.getInt("morphs.transition-ticks"));
		assertTrue(template.getList("morphs.disabled-types").isEmpty(), "an emptied list stays empty");
		assertEquals(3, template.getInt("morphs.required-kills-per-type.minecraft:wither"));
		assertFalse(template.getBoolean("attributes.enabled"));
		assertFalse(template.contains("attributes.list.minecraft:max_health.cap"), "an admin's entry isn't completed with default fields");
		assertEquals(0.7, template.getDouble("attributes.list.minecraft:jump_strength.cap"));
		assertEquals("6.1.4", template.getString("config-version"));
	}

	@Test
	void keepsUnknownKeysAndTheTemplatesComments() throws Exception {
		YamlConfiguration template = yaml(TEMPLATE);
		YamlConfiguration admin = yaml("""
				config-version: 6.1.2
				old-setting: 5
				morphs:
				  transition-ticks: 60
				""");
		ConfigMerge.merge(template, admin, "config-version");
		String out = template.saveToString();

		assertEquals(5, template.getInt("old-setting"));
		assertEquals("6.1.4", template.getString("config-version"), "the version always comes from the release");
		assertTrue(out.contains("# the mode"), out);
		assertTrue(out.contains("# ticks"), out);
		assertTrue(out.indexOf("gameplay-mode") < out.indexOf("morphs:"), "new settings sit where the release puts them");
	}

	@Test
	void anOldConfigFromTheFirstReleaseGetsEverySettingAddedSince() throws Exception {
		YamlConfiguration bundled = new YamlConfiguration();
		try (Reader reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8)) {
			bundled.load(reader);
		}
		// What the first release's config.yml lacked, with a few changed values.
		YamlConfiguration admin = yaml(bundled.saveToString());
		admin.set("config-version", null);
		admin.set("gameplay-mode", null);
		admin.set("world-effects.bee-sting-kills-user", null);
		admin.set("attributes.min-max-health", null);
		admin.set("attributes.health-overrides", null);
		admin.set("attributes.list.minecraft:jump_strength", null);
		admin.set("morphs.transition-ticks", 40);
		admin.set("debug", true);

		List<String> added = ConfigMerge.merge(bundled, admin, "config-version");

		assertEquals(List.of("gameplay-mode", "world-effects.bee-sting-kills-user", "attributes.list.minecraft:jump_strength",
				"attributes.min-max-health", "attributes.health-overrides"), added);
		assertEquals(40, bundled.getInt("morphs.transition-ticks"));
		assertTrue(bundled.getBoolean("debug"));
		assertEquals(20, bundled.getInt("attributes.health-overrides.minecraft:creaking"));
	}
}
