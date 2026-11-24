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
	public static final String MULTIMORPH = "multimorph";
	public static final String RAGE = "rage";
	/**
	 * The Arsenal: Arm Shaping (under Morph Ability) opens it, and each arm form is an upgrade under it whose level is the
	 * form's level (ArmForm.upgradeId). Only the levels live here; what an arm does at each is in abilities.yml arms.
	 */
	public static final String ARM_SHAPING = "arm_shaping";
	public static final String ARM_KATANA = "arm_katana";
	public static final String ARM_PICKAXE = "arm_pickaxe";
	public static final String ARM_AXE = "arm_axe";
	public static final String ARM_SHOVEL = "arm_shovel";
	public static final String ARM_SICKLE = "arm_sickle";
	public static final String ARM_BOW = "arm_bow";
	public static final String ARM_LANTERN = "arm_lantern";
	/** The whip: between the bow and the new tools in the Arsenal. */
	public static final String ARM_WHIP = "arm_whip";
	/** The new tool forms (2026-11-16; 8.0.0-beta.2), after the whip. */
	public static final String ARM_SPEAR = "arm_spear";
	public static final String ARM_MACE = "arm_mace";
	public static final String ARM_TRIDENT = "arm_trident";
	public static final String ARM_CROSSBOW = "arm_crossbow";
	public static final String ARM_CLAWS = "arm_claws";
	/** The new accessories, after the lantern. */
	public static final String ARM_SHIELD = "arm_shield";
	public static final String ARM_SPYGLASS = "arm_spyglass";
	public static final String ARM_FLINT = "arm_flint";
	public static final List<String> KNOWN = List.of(BIOMASS_CAPACITY, BIOMASS_EFFICIENCY, BIOMASS_REACH, BIOMASS_MAX_MASS, MORPH_ABILITY,
			MORPH_CAPACITY, MORPH_VARIANT_CAPACITY, MORPH_EFFICIENCY, MORPH_ABILITIES, MORPH_FLIGHT, MULTIMORPH, RAGE, ARM_SHAPING, ARM_KATANA,
			ARM_PICKAXE, ARM_AXE, ARM_SHOVEL, ARM_SICKLE, ARM_BOW, ARM_WHIP, ARM_SPEAR, ARM_MACE, ARM_TRIDENT, ARM_CROSSBOW, ARM_CLAWS, ARM_LANTERN,
			ARM_SHIELD, ARM_SPYGLASS, ARM_FLINT);

	/**
	 * One node of the upgrade tree.
	 *
	 * @param parent   must be at least level 1 before this can be bought ("root" for none)
	 * @param requires other upgrades and the level each needs
	 * @param format   how a value is shown, {value} replaced
	 * @param traits   the beneficial morph traits (abilities.yml passive names, effects as {@code effect:<name>}) that
	 *                 stay off until this upgrade is bought
	 */
	public record Upgrade(String id, String name, String description, Material icon, String effectIcon, String parent, Map<String, Integer> requires,
			int startLevel, int maxLevel, Curve value, Curve cost, String format, List<String> traits) {
		/**
		 * Whether the menu and the tree give this upgrade's level a line of its own (Now / Next): one with several levels does, and so does a
		 * single-level one that has a format of its own (its figure is the level's, not explanation, so it stays when Tooltips are off).
		 */
		public boolean showsValue() {
			return maxLevel > 1 || !format.equals("{value}");
		}
	}

	public final List<PotionEffectType> unlockEffects;
	public final boolean bypassUnlock;
	public final double biomassValue;
	public final Map<String, Double> massMultipliers;
	public final Map<String, Double> massOverrides;
	public final Set<String> noBiomass;
	/** Multiplies biomass from kills. */
	public final double gainMultiplier;
	/** Morphing costs biomass: mass x morphCostPerKg (rounded up, at least morphCostMinimum), or a fixed price. */
	public final boolean morphCostEnabled;
	public final double morphCostPerKg;
	public final int morphCostMinimum;
	/** Percent on top of a multimorph's price (morph-cost.multimorph-extra). */
	public final int multimorphExtraPercent;
	public final Map<String, Integer> morphPrices;
	/** In menu order. */
	public final Map<String, Upgrade> upgrades;
	/** survival.yml passives: each biomass passive's running cost and settings, as plain values (Passives reads them). */
	public final Map<String, Map<String, Object>> passives;
	/** Trait -> the upgrades that unlock it (any one of them is enough). Traits not listed are always on. */
	public final Map<String, List<String>> traitUnlocks;

	public SurvivalConfig(FileConfiguration c, Logger logger) {
		this(c, logger, true);
	}

	/** {@code announce}: whether to log what is off about the passives section (see {@link #logPassiveNotices}); false for a rebuild after a /morph cost change. */
	public SurvivalConfig(FileConfiguration c, Logger logger, boolean announce) {
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
		gainMultiplier = Math.max(0, c.getDouble("biomass.gain-multiplier", 1.0));
		morphCostEnabled = c.getBoolean("morph-cost.enabled", true);
		morphCostPerKg = Math.max(0, c.getDouble("morph-cost.per-kg", 0.01));
		morphCostMinimum = Math.max(0, c.getInt("morph-cost.minimum", 1));
		multimorphExtraPercent = Math.max(0, c.getInt("morph-cost.multimorph-extra", 25));
		Map<String, Integer> prices = new HashMap<>();
		ConfigurationSection priceSection = c.getConfigurationSection("morph-cost.prices");
		if (priceSection != null) for (String k : priceSection.getKeys(false)) prices.put(VariantRules.normaliseType(k), Math.max(0, priceSection.getInt(k)));
		morphPrices = Map.copyOf(prices);

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
				List<String> traits = new ArrayList<>();
				for (String t : u.getStringList("traits")) traits.add(normaliseTrait(t));
				loaded.put(id, new Upgrade(id, u.getString("name", id), u.getString("description", ""), icon == null ? Material.PAPER : icon, effectIcon,
						u.getString("parent", "root"), requires, Math.clamp(u.getInt("start-level", 0), 0, max), max, Curve.read(u, "value"),
						Curve.read(u, "cost"), u.getString("format", "{value}"), List.copyOf(traits)));
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
		Map<String, Map<String, Object>> passiveSettings = new HashMap<>();
		ConfigurationSection ps = c.getConfigurationSection("passives");
		if (ps != null) {
			for (String id : ps.getKeys(false)) {
				ConfigurationSection one = ps.getConfigurationSection(id);
				if (one == null) continue;
				passiveSettings.put(id, Map.copyOf(one.getValues(false)));
			}
			// config-version stays 8.0.0 (ConfigVersionTest wants a plain x.y.z), so an 8.0.0 beta file is never regenerated, and
			// only my test servers ever had the old layout. One INFO line per load, not a WARN for each of the eight
			// entries (my test server logged eight on every start and /morph reload; config-versions item, 2026-11-16).
			// Nothing is rewritten: the file may hold changes we cannot tell from the old numbers, and the section is ignored anyway.
			// /morph cost builds a config for every price it changes, and says nothing (announce = false): the line is for starts,
			// reloads and mode switches.
			if (announce) logPassiveNotices(logger, passiveSettings);
		}
		passives = Map.copyOf(passiveSettings);
		Map<String, List<String>> unlocks = new HashMap<>();
		for (Upgrade u : loaded.values()) {
			for (String t : u.traits()) unlocks.computeIfAbsent(t, k -> new ArrayList<>()).add(u.id());
		}
		unlocks.replaceAll((k, v) -> List.copyOf(v));
		traitUnlocks = Map.copyOf(unlocks);
	}

	/** Logs {@link PassiveSettings#notices}: one INFO line for the old layout however many entries have it, a WARN only for a typo'd billing. */
	static void logPassiveNotices(Logger logger, Map<String, Map<String, Object>> passiveSettings) {
		for (PassiveSettings.Notice notice : PassiveSettings.notices(passiveSettings)) {
			logger.log(notice.warning() ? java.util.logging.Level.WARNING : java.util.logging.Level.INFO, notice.text());
		}
	}

	/** {@code Fire_Immunity} ->{@code fire-immunity}; {@code effect:minecraft:night_vision} -> {@code effect:night_vision}. */
	public static String normaliseTrait(String trait) {
		String t = trait.toLowerCase(Locale.ROOT).trim();
		if (t.startsWith("effect:")) {
			String effect = t.substring("effect:".length()).trim();
			if (effect.startsWith("minecraft:")) effect = effect.substring("minecraft:".length());
			return "effect:" + effect;
		}
		return t.replace('_', '-');
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
