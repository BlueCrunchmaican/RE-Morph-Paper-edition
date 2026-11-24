package dev.morphreborn.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * The decisions behind updating an admin's config.yml, survival.yml and abilities.yml to this release, free of files and
 * the server so they are unit tested with the very code the plugin runs (MorphRebornPlugin only reads, writes and logs).
 */
final class ConfigUpgrade {
	private ConfigUpgrade() {}

	/** What to do with a file that is there. */
	enum Action {
		/** It is as new as the bundled one (or newer): leave it. */
		KEEP,
		/** Older, but of a layout the release still reads: the bundled file is the template and every value the admin set is written over it. */
		MERGE,
		/** Older, and of a layout that is no longer read as it was: the bundled file replaces it and the old one is kept beside it. */
		REPLACE
	}

	/**
	 * abilities.yml from this release on is updated, not replaced, when it is marked {@code mergeFrom} or later: the 7.4.0 file
	 * (the one 7.5.0 shipped) and every 8.0.0 beta have the layout of this release's, with keys added since (the arm forms,
	 * accessories, bow tips, whip moves, the shulker and Frenzy keys), so an admin's hand edits stay in the live file and the
	 * new keys arrive at their defaults. A file from before it keeps the old rule (2026-11-16: merge the new keys
	 * without clobbering what a server set).
	 */
	static final String ABILITIES_MERGE_FROM = "7.4.0";

	/** {@code mergeFrom} null: always replace an older file. A file with no version is older than everything and is never merged. */
	static Action decide(String current, String bundled, @Nullable String mergeFrom) {
		if (MorphRebornPlugin.compareVersions(current, bundled) >= 0) return Action.KEEP;
		if (mergeFrom != null && !current.isEmpty() && MorphRebornPlugin.compareVersions(current, mergeFrom) >= 0) return Action.MERGE;
		return Action.REPLACE;
	}

	/** What {@link #upgrade} did: the settings the admin's file lacked (as the console lists them) and the sections it replaced. */
	record Result(List<String> added, List<String> reset) {}

	/**
	 * Writes the admin's {@code current} over {@code bundled} (the template, changed in place and ready to save). {@code replaced}:
	 * sections whose meaning changed in a release (section -> that release): a file older than it gets the bundled section
	 * instead of its own.
	 */
	static Result upgrade(YamlConfiguration bundled, YamlConfiguration current, Map<String, String> replaced) {
		String currentVersion = current.getString(MorphRebornPlugin.CONFIG_VERSION, "");
		List<String> reset = new ArrayList<>();
		for (Map.Entry<String, String> r : replaced.entrySet()) {
			if (current.contains(r.getKey()) && MorphRebornPlugin.compareVersions(currentVersion, r.getValue()) < 0) {
				current.set(r.getKey(), null);
				reset.add(r.getKey());
			}
		}
		List<String> added = new ArrayList<>(ConfigMerge.merge(bundled, current, MorphRebornPlugin.CONFIG_VERSION));
		added.removeIf(key -> reset.stream().anyMatch(r -> key.equals(r) || key.startsWith(r + ".")));
		return new Result(List.copyOf(added), List.copyOf(reset));
	}

	/**
	 * The survival.yml upgrade nodes 8.0.0 beta 2 added (the spear, mace, trident, crossbow, claws, and the shield, spyglass
	 * and flint accessories). The file stays marked 8.0.0 from beta 1 on (ConfigVersionTest wants a plain release, and a
	 * version is not bumped for a beta), so a test server's beta 1 file is never older than the bundled one and would lack
	 * them for good: every start and reload would log "upgrade ... is missing" eight times and what they unlock would stay
	 * locked for non-ops (config-versions item, 2026-11-16).
	 */
	static final List<String> BETA2_ARM_UPGRADES = List.of("arm_spear", "arm_mace", "arm_trident", "arm_crossbow", "arm_claws", "arm_shield",
			"arm_spyglass", "arm_flint");

	/**
	 * Whether a survival.yml marked with this release's version is an earlier beta's: it has none of {@link #BETA2_ARM_UPGRADES}.
	 * It is then merged once like an older file (every value kept, the new nodes and their cost rows added), and the merge makes
	 * the nodes present, so this is false from then on. An admin who removed only some of the eight is left alone: that is a
	 * choice, and a file with none of them is not.
	 */
	static boolean lacksBeta2ArmUpgrades(ConfigurationSection current) {
		ConfigurationSection upgrades = current.getConfigurationSection("upgrades");
		if (upgrades == null) return true;
		for (String id : BETA2_ARM_UPGRADES) {
			if (upgrades.contains(id)) return false;
		}
		return true;
	}
}
