package dev.morphreborn.paper;

import dev.morphreborn.paper.variant.VariantRules;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Typed view of config.yml. Rebuilt on reload; consumers should re-read {@code plugin.settings()} each use. */
public final class PluginSettings {
	public record AttributeRule(boolean more, Double cap) {}

	public final int transitionTicks;
	public final int requiredKills;
	public final Map<String, Integer> requiredKillsPerType;
	public final boolean allowPlayerMorphs;
	public final boolean stealMorphs;
	public final boolean allowDelete;
	public final int maxVariantsPerType;
	public final boolean perTypePermissions;
	public final int morphCooldownTicks;

	public final boolean keepMorphOnDeath;
	public final boolean loseCurrentMorphOnDeath;
	public final boolean resetCollectionOnDeath;

	public final boolean persistCurrentMorph;
	public final int autosaveSeconds;

	/** What name floats above a morphed player. */
	public enum NameTagMode {
		/** Mob morphs show no name (or the mob's own custom name); player morphs show the copied player's name. */
		MORPH,
		/** Always the real player's name. */
		PLAYER,
		/** No name at all while morphed. */
		HIDDEN
	}

	public final NameTagMode nameTagMode;
	public final boolean vanillaSelfDisguise;
	public final boolean hitboxScaling;
	public final double minScale;
	public final double maxScale;
	public final boolean vanillaTransition;
	public final boolean vanillaAcquisitionEffects;
	public final String morphSound;
	public final String morphSoundFallback;
	public final float morphSoundVolume;
	public final boolean vanillaHasSoundPack;

	public final boolean attributesEnabled;
	public final Map<String, AttributeRule> attributes;

	public final boolean selectorEnabled;
	public final boolean commandsForVanilla;

	/** How an ability's effect on the world is decided: always, never, or by the mobGriefing gamerule (vanilla). */
	public enum WorldRule {
		ON, OFF, GAMERULE;

		public static WorldRule parse(String value) {
			return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
				case "on", "true", "yes" -> ON;
				case "off", "false", "no" -> OFF;
				case "gamerule", "mobgriefing", "follow-gamerule" -> GAMERULE;
				default -> throw new IllegalArgumentException("Expected on, off or gamerule, not '" + value + "'.");
			};
		}

		public boolean allows(org.bukkit.World world) {
			return switch (this) {
				case ON -> true;
				case OFF -> false;
				case GAMERULE -> Boolean.TRUE.equals(world.getGameRuleValue(org.bukkit.GameRules.MOB_GRIEFING));
			};
		}
	}

	public final WorldRule explosionBlockDamage;
	public final WorldRule abilityFire;
	public final boolean creeperExplosionKillsUser;
	/** Enderman morphs may pick up any block, not just the ones real endermen can (#enderman_holdable). */
	public final boolean endermanPickupAny;
	/** A bee morph dies a while after it stings, like a real bee. */
	public final boolean beeStingKillsUser;
	/** No morph gets less max health than this, and these types get exactly this max health. */
	public final double minMaxHealth;
	public final Map<String, Double> healthOverrides;

	public final String outdatedMessage;
	public final boolean unlockChatMessage;
	public final boolean debug;
	/** "classic" or "survival" (config.yml gameplay-mode). Applied at startup, on /morph reload and by /morph mode. */
	public final String gameplayMode;

	PluginSettings(FileConfiguration c) {
		transitionTicks = Math.max(20, c.getInt("morphs.transition-ticks", 100));
		requiredKills = Math.max(1, c.getInt("morphs.required-kills", 1));
		requiredKillsPerType = new HashMap<>();
		ConfigurationSection perType = c.getConfigurationSection("morphs.required-kills-per-type");
		if (perType != null) {
			for (String key : perType.getKeys(false)) requiredKillsPerType.put(VariantRules.normaliseType(key), Math.max(1, perType.getInt(key)));
		}
		allowPlayerMorphs = c.getBoolean("morphs.allow-player-morphs", true);
		stealMorphs = c.getBoolean("morphs.steal-morphs-from-players", true);
		allowDelete = c.getBoolean("morphs.allow-delete", true);
		maxVariantsPerType = Math.max(0, c.getInt("morphs.max-variants-per-type", 64));
		perTypePermissions = c.getBoolean("morphs.per-type-permissions", false);
		morphCooldownTicks = Math.max(0, c.getInt("morphs.morph-cooldown-seconds", 0)) * 20;

		keepMorphOnDeath = c.getBoolean("death.keep-morph-on-death", true);
		loseCurrentMorphOnDeath = c.getBoolean("death.lose-current-morph-on-death", false);
		resetCollectionOnDeath = c.getBoolean("death.reset-collection-on-death", false);

		explosionBlockDamage = worldRule(c, "world-effects.explosion-block-damage");
		abilityFire = worldRule(c, "world-effects.fire");
		creeperExplosionKillsUser = c.getBoolean("world-effects.creeper-explosion-kills-user", true);
		endermanPickupAny = "any".equalsIgnoreCase(c.getString("world-effects.enderman-pickup", "vanilla").trim());
		beeStingKillsUser = c.getBoolean("world-effects.bee-sting-kills-user", true);
		minMaxHealth = Math.max(1, c.getDouble("attributes.min-max-health", 4));
		healthOverrides = new HashMap<>();
		ConfigurationSection overrides = c.getConfigurationSection("attributes.health-overrides");
		if (overrides != null) {
			for (String key : overrides.getKeys(false)) healthOverrides.put(VariantRules.normaliseType(key), Math.max(1, overrides.getDouble(key)));
		}

		persistCurrentMorph = c.getBoolean("persistence.persist-current-morph", true);
		autosaveSeconds = Math.max(5, c.getInt("persistence.autosave-seconds", 30));

		NameTagMode mode;
		try {
			mode = NameTagMode.valueOf(c.getString("appearance.name-tags", "morph").trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			mode = NameTagMode.MORPH;
		}
		nameTagMode = mode;
		vanillaSelfDisguise = c.getBoolean("appearance.vanilla-self-disguise", true);
		hitboxScaling = c.getBoolean("appearance.hitbox-scaling", true);
		minScale = Math.max(0.0625, c.getDouble("appearance.min-scale", 0.2));
		maxScale = Math.max(minScale, c.getDouble("appearance.max-scale", 2.5));
		vanillaTransition = c.getBoolean("appearance.vanilla-transition", true);
		vanillaAcquisitionEffects = c.getBoolean("appearance.vanilla-acquisition-effects", true);
		morphSound = c.getString("appearance.morph-sound", "morphreborn:morph");
		morphSoundFallback = c.getString("appearance.morph-sound-fallback", "minecraft:entity.illusioner.mirror_move");
		morphSoundVolume = (float) c.getDouble("appearance.morph-sound-volume", 1.0);
		vanillaHasSoundPack = c.getBoolean("appearance.vanilla-clients-have-sound-pack", false);

		attributesEnabled = c.getBoolean("attributes.enabled", true);
		attributes = new LinkedHashMap<>();
		ConfigurationSection attrs = c.getConfigurationSection("attributes.list");
		if (attrs != null) {
			for (String key : attrs.getKeys(false)) {
				ConfigurationSection a = attrs.getConfigurationSection(key);
				boolean more = a == null || !"less".equalsIgnoreCase(a.getString("direction", "more"));
				Double cap = a != null && a.contains("cap") ? a.getDouble("cap") : null;
				attributes.put(key, new AttributeRule(more, cap));
			}
		}

		selectorEnabled = c.getBoolean("selector.enabled", true);
		commandsForVanilla = c.getBoolean("selector.allow-commands-for-vanilla", true);

		outdatedMessage = c.getString("client.outdated-message", "<yellow>Your RE:Morph client mod is outdated.");
		unlockChatMessage = c.getBoolean("client.unlock-chat-message", true);
		debug = c.getBoolean("debug", false);
		String chosenMode = c.getString("gameplay-mode", "classic").trim().toLowerCase(java.util.Locale.ROOT);
		gameplayMode = chosenMode.equals("survival") || chosenMode.equals("default") ? "survival" : "classic";
	}

	private static WorldRule worldRule(FileConfiguration c, String path) {
		try {
			return WorldRule.parse(String.valueOf(c.get(path, "gamerule")));
		} catch (IllegalArgumentException e) {
			return WorldRule.GAMERULE;
		}
	}

	public int requiredKills(String entityType) {
		return requiredKillsPerType.getOrDefault(entityType, requiredKills);
	}
}
