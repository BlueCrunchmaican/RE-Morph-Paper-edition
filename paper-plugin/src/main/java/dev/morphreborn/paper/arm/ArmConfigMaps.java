package dev.morphreborn.paper.arm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * A settings section as plain values: nested maps, lists, strings, numbers and booleans, nothing of Bukkit's. The arm
 * settings (ArmConfig) and the arm modules read plain maps so they can be unit tested without a server, and so a
 * module can't keep a live section that a reload later replaces under it.
 * <p>
 * Nested sections become nested maps here. A section's own {@code getValues(false)} would leave them as section
 * objects, which ArmConfig drops as "not a plain value", so the arm settings would silently lose every nested key.
 */
public final class ArmConfigMaps {
	private ArmConfigMaps() {}

	/** The section's keys and values, nested sections as nested maps; empty for a missing section. */
	public static Map<String, Object> of(@Nullable ConfigurationSection section) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (section == null) return out;
		for (String key : section.getKeys(false)) {
			Object value = plain(section.get(key));
			if (value != null) out.put(key, value);
		}
		return out;
	}

	private static @Nullable Object plain(@Nullable Object v) {
		if (v instanceof ConfigurationSection s) return of(s);
		if (v instanceof Map<?, ?> m) {
			// a list of maps in YAML ({ a: 1 } entries) comes back as maps, not sections
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				Object value = plain(e.getValue());
				if (e.getKey() != null && value != null) out.put(String.valueOf(e.getKey()), value);
			}
			return out;
		}
		if (v instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object o : list) {
				Object value = plain(o);
				if (value != null) out.add(value);
			}
			return out;
		}
		return v;
	}
}
