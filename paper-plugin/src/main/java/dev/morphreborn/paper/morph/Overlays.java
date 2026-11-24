package dev.morphreborn.paper.morph;

import dev.morphreborn.paper.nbt.Snbt;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The live look layered on a form is one SNBT compound, written by several abilities at once (a charged creeper's
 * aura, a carried block, a bee's sting, a roll). Each ability owns its own keys; this merges one writer's keys into
 * the compound without touching anyone else's. Bukkit-free so it can be tested.
 */
public final class Overlays {
	private Overlays() {}

	/**
	 * {@code current} with the keys {@code owned} replaced by those of {@code mine} (an empty {@code mine} just takes
	 * them out). Everything else in {@code current} is kept. An unreadable {@code current} is dropped.
	 */
	public static String merge(String current, Set<String> owned, String mine) {
		Map<String, Object> tags = read(current);
		tags.keySet().removeAll(owned);
		for (Map.Entry<String, Object> e : read(mine).entrySet()) {
			if (owned.contains(e.getKey())) tags.put(e.getKey(), e.getValue());
		}
		return tags.isEmpty() ? "" : Snbt.write(tags);
	}

	private static Map<String, Object> read(String snbt) {
		if (snbt == null || snbt.isBlank()) return new LinkedHashMap<>();
		try {
			return new LinkedHashMap<>(Snbt.parseCompound(snbt));
		} catch (IllegalArgumentException e) {
			return new LinkedHashMap<>();
		}
	}
}
