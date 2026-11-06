package dev.morphreborn.paper.variant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * The appearance whitelist from {@code variants.yml}. A variant is "the entity type plus these keys",
 * which is how the original mod's {@code mobsupport/nbt} modifiers defined variants. Keys are matched
 * against the entity's top-level save data.
 */
public final class VariantRules {
	public final Set<String> globalKeys;
	public final Map<String, Set<String>> extraKeys;
	public final Map<String, Set<String>> removedKeys;
	public final boolean keepCustomNames;
	public final boolean allowBabies;
	public final List<Pattern> disabledTypes;
	public final Set<String> collapseVariantTypes;

	private VariantRules(Set<String> globalKeys, Map<String, Set<String>> extraKeys, Map<String, Set<String>> removedKeys, boolean keepCustomNames,
			boolean allowBabies, List<Pattern> disabledTypes, Set<String> collapseVariantTypes) {
		this.globalKeys = globalKeys;
		this.extraKeys = extraKeys;
		this.removedKeys = removedKeys;
		this.keepCustomNames = keepCustomNames;
		this.allowBabies = allowBabies;
		this.disabledTypes = disabledTypes;
		this.collapseVariantTypes = collapseVariantTypes;
	}

	public static VariantRules load(FileConfiguration variants, FileConfiguration config) {
		Set<String> global = new LinkedHashSet<>(variants.getStringList("global-keys"));
		Map<String, Set<String>> extra = new HashMap<>();
		Map<String, Set<String>> removed = new HashMap<>();
		ConfigurationSection types = variants.getConfigurationSection("types");
		if (types != null) {
			for (String type : types.getKeys(false)) {
				ConfigurationSection section = types.getConfigurationSection(type);
				if (section == null) continue;
				String id = normaliseType(type);
				extra.put(id, new HashSet<>(section.getStringList("keep")));
				removed.put(id, new HashSet<>(section.getStringList("drop")));
			}
		}
		List<Pattern> disabled = new ArrayList<>();
		for (String pattern : config.getStringList("morphs.disabled-types")) {
			disabled.add(Pattern.compile(globToRegex(normaliseType(pattern))));
		}
		Set<String> collapse = new HashSet<>();
		for (String type : config.getStringList("morphs.single-variant-types")) collapse.add(normaliseType(type));
		return new VariantRules(global, extra, removed, variants.getBoolean("keep-custom-names", true), config.getBoolean("morphs.allow-baby-morphs", true),
				disabled, collapse);
	}

	public boolean isDisabled(String entityType) {
		for (Pattern p : disabledTypes) {
			if (p.matcher(entityType).matches()) return true;
		}
		return false;
	}

	public boolean keeps(String entityType, String key) {
		if (collapseVariantTypes.contains(entityType)) return false;
		if (removedKeys.getOrDefault(entityType, Set.of()).contains(key)) return false;
		if (key.equals("CustomName")) return keepCustomNames;
		return globalKeys.contains(key) || extraKeys.getOrDefault(entityType, Set.of()).contains(key);
	}

	public static String normaliseType(String type) {
		String t = type.trim().toLowerCase(java.util.Locale.ROOT);
		return t.contains(":") ? t : "minecraft:" + t;
	}

	private static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (char c : glob.toCharArray()) {
			if (c == '*') sb.append(".*");
			else sb.append(Pattern.quote(String.valueOf(c)));
		}
		return sb.toString();
	}
}
