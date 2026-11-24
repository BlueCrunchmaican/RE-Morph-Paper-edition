package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The YAML the jar ships has to parse as the server parses it. An unquoted value with a colon and a space in it ("... (/morph tooltips
 * on|off): the explanation lines ...") in plugin.yml's permission descriptions made Paper refuse to load the plugin at all (2026-11-18),
 * which no other test could see, so every shipped file is read here, strictly (a repeated key is an error too).
 */
class ShippedYamlTest {
	private static Object parse(String resource) throws Exception {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		InputStream stream = ShippedYamlTest.class.getClassLoader().getResourceAsStream(resource);
		assertNotNull(stream, resource + " is not in the jar");
		try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return new Yaml(options).load(in);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"plugin.yml", "config.yml", "abilities.yml", "variants.yml", "survival.yml"})
	void shippedFileParses(String resource) throws Exception {
		Object root = parse(resource);
		assertInstanceOf(Map.class, root, resource + " must be a map at the top");
		assertTrue(!((Map<?, ?>) root).isEmpty(), resource + " is empty");
	}

	@Test
	void pluginYmlHasWhatPaperNeeds() throws Exception {
		Map<?, ?> yml = (Map<?, ?>) parse("plugin.yml");
		for (String key : List.of("name", "main", "version", "api-version")) assertNotNull(yml.get(key), "plugin.yml has no " + key);
		assertEquals("RE-Morph", yml.get("name"), "plugin name");
		// every permission's description is text: a colon-and-space inside an unquoted one would have made it a nested map
		Object permissions = yml.get("permissions");
		if (permissions instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (e.getValue() instanceof Map<?, ?> permission && permission.containsKey("description")) {
					assertInstanceOf(String.class, permission.get("description"), "description of " + e.getKey());
				}
			}
		}
	}
}
