package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.variant.VariantRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

/** survival.yml: the biomass unlock, iChun's biomass formula and the upgrade tree. */
public final class SurvivalConfig {
	// Upgrade ids the code gives a meaning to. Costs and curves come from survival.yml.
	public static final String BIOMASS_CAPACITY = "biomass_capacity";
	public static final String BIOMASS_EFFICIENCY = "biomass_efficiency";
	public static final String BIOMASS_REACH = "biomass_reach";
	public static final String BIOMASS_MAX_MASS = "biomass_max_mass_absorbable";
	public static final String MORPH_ABILITY = "morph_ability";
	public static final String MORPH_CAPACITY = "morph_capacity";
	public static final String MORPH_VARIANT_CAPACITY = "morph_variant_capacity";
	public static final String MORPH_EFFICIENCY = "morph_efficiency";
	public static final String MORPH_ABILITIES = "morph_abilities";
	public static final String MORPH_FLIGHT = "morph_flight";
	public static final List<String> KNOWN = List.of(BIOMASS_CAPACITY, BIOMASS_EFFICIENCY, BIOMASS_REACH, BIOMASS_MAX_MASS, MORPH_ABILITY,
			MORPH_CAPACITY, MORPH_VARIANT_CAPACITY, MORPH_EFFICIENCY, MORPH_ABILITIES, MORPH_FLIGHT);

	/**
	 * One node of the upgrade tree.
	 *
	 * @param parent   must be at least level 1 before this can be bought ("root" for none)
	 * @param requires other upgrades and the level each needs
	 * @param format   how a value is shown, {value} replaced
	 */
	public record Upgrade(String id, String name, String description, Material icon, String effectIcon, String parent, Map<String, Integer> requires,
			int startLevel, int maxLevel, Curve value, Curve cost, String format) {}

	public final List<PotionEffectType> unlockEffects;
	public final boolean bypassUnlock;
	public final double biomassValue;
	public final Map<String, Double> massMultipliers;
	public final Map<String, Double> massOverrides;
	public final Set<String> noBiomass;
	/** In menu order. */
	public final Map<String, Upgrade> upgrades;

	public SurvivalConfig(FileConfiguration c, Logger logger) {
		List<PotionEffectType> effects = new ArrayList<>();
		for (String name : c.getStringList("unlock.effects")) {
			PotionEffectType type = Registry.MOB_EFFECT.get(key(name));
			if (type == null) logger.warning("survival.yml: unknown effect '" + name + "' in unlock.effects");
			else effects.add(type);
		}
		unlockEffects = List.copyOf(effects);
		bypassUnlock = c.getBoolean("unlock.bypass", false);
		biomassValue = Math.max(0, c.getDouble("biomass.value", 0.3));
		massMultipliers = doubles(c.getConfigurationSection("biomass.multipliers"));
		massOverrides = doubles(c.getConfigurationSection("biomass.masses"));
		Set<String> none = new HashSet<>();
		for (String type : c.getStringList("biomass.none")) none.add(VariantRules.normaliseType(type));
		noBiomass = Set.copyOf(none);

		Map<String, Upgrade> loaded = new LinkedHashMap<>();
		ConfigurationSection section = c.getConfigurationSection("upgrades");
		if (section != null) {
			for (String id : section.getKeys(false)) {
				ConfigurationSection u = section.getConfigurationSection(id);
				if (u == null) continue;
				Map<String, Integer> requires = new LinkedHashMap<>();
				ConfigurationSection req = u.getConfigurationSection("requires");
				if (req != null) for (String r : req.getKeys(false)) requires.put(r, req.getInt(r, 1));
				Material icon = Material.matchMaterial(u.getString("icon", "minecraft:paper"));
				int max = Math.max(1, u.getInt("max-level", 1));
				String effectIcon = "";
				String effectName = u.getString("effect-icon", "");
				if (!effectName.isEmpty()) {
					NamespacedKey effectKey = key(effectName);
					if (effectKey != null && Registry.MOB_EFFECT.get(effectKey) != null) effectIcon = effectKey.toString();
					else logger.warning("survival.yml: unknown effect-icon '" + effectName + "' for " + id);
				}
				loaded.put(id, new Upgrade(id, u.getString("name", id), u.getString("description", ""), icon == null ? Material.PAPER : icon, effectIcon,
						u.getString("parent", "root"), requires, Math.clamp(u.getInt("start-level", 0), 0, max), max, Curve.read(u.getConfigurationSection("value")),
						Curve.read(u.getConfigurationSection("cost")), u.getString("format", "{value}")));
			}
		}
		// Same rule as iChun's loader: an upgrade whose parent doesn't exist is dropped.
		loaded.values().removeIf(u -> {
			boolean orphan = !u.parent().equals("root") && !loaded.containsKey(u.parent());
			if (orphan) logger.warning("survival.yml: upgrade " + u.id() + " has unknown parent " + u.parent() + "; ignoring it.");
			return orphan;
		});
		for (String id : KNOWN) {
			if (!loaded.containsKey(id)) logger.warning("survival.yml: upgrade " + id + " is missing; what it unlocks stays locked.");
		}
		upgrades = java.util.Collections.unmodifiableMap(loaded);
	}

	private static NamespacedKey key(String name) {
		String n = name.toLowerCase(Locale.ROOT).trim();
		return NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n);
	}

	private static Map<String, Double> doubles(ConfigurationSection s) {
		Map<String, Double> out = new HashMap<>();
		if (s != null) for (String k : s.getKeys(false)) out.put(VariantRules.normaliseType(k), s.getDouble(k));
		return Map.copyOf(out);
	}
}
