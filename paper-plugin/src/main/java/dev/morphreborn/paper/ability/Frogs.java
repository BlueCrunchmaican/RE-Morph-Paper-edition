package dev.morphreborn.paper.ability;

/** Which froglight a frog's tongue makes of a magma cube; Bukkit-free so it can be tested. */
final class Frogs {
	private Frogs() {}

	/** The froglight of the frog whose form data is {@code snbt}: warm pearlescent, cold verdant, else (temperate) ochre. */
	static String froglight(String snbt) {
		if (snbt == null) return "ochre";
		if (snbt.contains("warm")) return "pearlescent";
		return snbt.contains("cold") ? "verdant" : "ochre";
	}
}
