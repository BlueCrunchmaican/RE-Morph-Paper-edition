package dev.morphreborn.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Brings an admin's config.yml up to a newer release's layout without changing anything they set. The bundled file is
 * the template: its order, comments and new settings stay, and every value from the admin's file is written over it.
 *
 * <ul>
 *   <li>Settings the admin's file doesn't have are added with their default (and listed, so the log can name them).</li>
 *   <li>Settings the admin's file has keep the admin's value, whatever it is.</li>
 *   <li>Keys the bundled file doesn't know (old or misspelt settings) are carried over untouched.</li>
 *   <li>Lists of data keyed by ids, like {@code attributes.list} or {@code health-overrides}, are maps where a key
 *       contains ':'. Their entries are taken whole: an admin's entry is never completed with default fields, and
 *       default entries the admin's map lacks are added.</li>
 * </ul>
 */
final class ConfigMerge {
	private ConfigMerge() {}

	/**
	 * Writes the admin's values over {@code template} (the bundled file, changed in place) and returns the paths of
	 * the settings that were missing from the admin's file. {@code skip} names top-level keys that always come from
	 * the template, such as config-version.
	 */
	static List<String> merge(ConfigurationSection template, ConfigurationSection admin, String skip) {
		List<String> added = new ArrayList<>();
		merge(template, admin, skip, "", added);
		return added;
	}

	private static void merge(ConfigurationSection template, ConfigurationSection admin, String skip, String prefix, List<String> added) {
		boolean dataMap = isDataMap(template);
		for (String key : List.copyOf(template.getKeys(false))) {
			if (prefix.isEmpty() && key.equals(skip)) continue;
			if (!admin.contains(key, true)) {
				added.add(prefix + key);
				continue;
			}
			Object value = admin.get(key);
			if (!dataMap && template.isConfigurationSection(key) && value instanceof ConfigurationSection adminSection) {
				merge(template.getConfigurationSection(key), adminSection, skip, prefix + key + ".", added);
			} else {
				put(template, key, value);
			}
		}
		for (String key : admin.getKeys(false)) {
			if (prefix.isEmpty() && key.equals(skip)) continue;
			if (!template.contains(key, true)) put(template, key, admin.get(key));
		}
	}

	/** A map of entries keyed by ids (attribute, entity type) rather than a group of named settings. */
	private static boolean isDataMap(ConfigurationSection section) {
		if (section.getKeys(false).isEmpty()) return true;
		for (String key : section.getKeys(false)) {
			if (key.indexOf(':') >= 0) return true;
		}
		return false;
	}

	private static void put(ConfigurationSection target, String key, Object value) {
		if (value instanceof ConfigurationSection section) {
			target.createSection(key, toMap(section)); // keeps the comments above the key
		} else {
			target.set(key, value);
		}
	}

	private static Map<String, Object> toMap(ConfigurationSection section) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (String key : section.getKeys(false)) {
			Object value = section.get(key);
			map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
		}
		return map;
	}
}
