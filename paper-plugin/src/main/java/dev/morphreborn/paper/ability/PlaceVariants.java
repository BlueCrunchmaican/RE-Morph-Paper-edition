package dev.morphreborn.paper.ability;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which of a multimorph's forms runs an ability that two of them share, when the variants differ by where they work
 * (the dash: a camel's on land, a dolphin's or nautilus's only through water); Bukkit-free so it can be tested.
 * My call, 2026-11-17: the place decides, whichever form the variant comes from (camel + dolphin has both dashes).
 */
final class PlaceVariants {
	private PlaceVariants() {}

	/**
	 * The form whose ability the key fires: the main form's first, as for any shared ability, except that an ability with a
	 * water and a land variant takes the one that fits {@code inWater}. {@code waterOnly} maps such an ability's name to the
	 * forms whose variant works only in water (the rest are the land variant). When no form fits the place (a dolphin on
	 * land, a camel and a husk in water) the main form's is kept: it refuses or runs as it did for one form.
	 *
	 * @param forms     the player's forms, the main one first
	 * @param abilityOf the active ability's name a form has, or null for none
	 * @return the form to run, or null when none of them has an ability
	 */
	static @Nullable String pick(List<String> forms, Function<String, @Nullable String> abilityOf, Map<String, Set<String>> waterOnly, boolean inWater) {
		String first = null;
		String name = null;
		for (String t : forms) {
			String n = abilityOf.apply(t);
			if (n != null) {
				first = t;
				name = n;
				break;
			}
		}
		if (first == null) return null;
		Set<String> water = waterOnly.get(name);
		if (water == null) return first;
		for (String t : forms) {
			if (name.equals(abilityOf.apply(t)) && water.contains(t) == inWater) return t;
		}
		return first;
	}
}
