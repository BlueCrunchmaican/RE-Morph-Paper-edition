package dev.morphreborn.paper;

import dev.morphreborn.paper.ability.AbilityListener;
import dev.morphreborn.paper.ability.AbilityManager;
import dev.morphreborn.paper.command.MorphCommand;
import dev.morphreborn.paper.data.DataStore;
import dev.morphreborn.paper.disguise.DisguiseBridge;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.mode.ClassicMode;
import dev.morphreborn.paper.mode.GameplayMode;
import dev.morphreborn.paper.morph.MorphManager;
import dev.morphreborn.paper.morph.MorphTemplates;
import dev.morphreborn.paper.net.ClientChannel;
import dev.morphreborn.paper.survival.SurvivalConfig;
import dev.morphreborn.paper.survival.SurvivalListener;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.variant.VariantCapture;
import dev.morphreborn.paper.variant.VariantRules;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MorphRebornPlugin extends JavaPlugin {
	/**
	 * Namespace of the attribute modifiers and entity data this plugin saves. It came from the plugin's name before the
	 * rename to RE-Morph and stays, so modifiers already saved on players are still recognised and removed.
	 */
	public static final String NAMESPACE = "morphreborn";
	/** The plugin's name, and so its data folder, before 6.1.2. */
	private static final String OLD_NAME = "MorphReborn";

	public static NamespacedKey key(String path) {
		return Objects.requireNonNull(NamespacedKey.fromString(NAMESPACE + ":" + path));
	}

	/** Set when plugins/MorphReborn exists but couldn't be moved; the plugin then refuses to start. */
	private boolean folderMoveFailed;
	private PluginSettings settings;
	private DataStore store;
	private VariantCapture capture;
	private MorphTemplates templates;
	private DisguiseBridge disguises;
	private dev.morphreborn.paper.disguise.BorrowedHeads borrowedHeads;
	private ClientChannel channel;
	private VanillaEffects effects;
	private dev.morphreborn.paper.effects.SkinEffects skin;
	private AbilityManager abilities;
	private MorphManager morphs;
	private SurvivalMode survival;
	private dev.morphreborn.paper.ability.Jockeys jockeys;
	private dev.morphreborn.paper.ability.Rage rage;
	private dev.morphreborn.paper.ability.VillagerTrading trading;
	/** config.yml gameplay-mode; switched live by /morph mode or /morph reload ({@link #switchMode}). */
	private GameplayMode mode;

	@Override
	public void onLoad() {
		moveOldDataFolder();
	}

	/**
	 * Before 6.1.2 the plugin was called MorphReborn, so its files are in plugins/MorphReborn. Move them to the new
	 * folder once, before anything reads them. If both folders have files, the new one wins and the old one is left
	 * alone for the admin to sort out.
	 */
	private void moveOldDataFolder() {
		File folder = getDataFolder();
		File old = new File(folder.getParentFile(), OLD_NAME);
		if (!old.isDirectory()) return;
		String[] inNew = folder.list();
		if (inNew != null && inNew.length > 0) {
			getLogger().warning("plugins/" + OLD_NAME + " is still there next to plugins/" + folder.getName() + ". Using "
					+ folder.getName() + "; delete " + OLD_NAME + " once you no longer need it.");
			return;
		}
		try {
			if (folder.isDirectory()) Files.delete(folder.toPath()); // empty
			Files.move(old.toPath(), folder.toPath());
			getLogger().info("Moved plugins/" + OLD_NAME + " to plugins/" + folder.getName() + " (the plugin's new name). Settings and player "
					+ "collections carry over.");
		} catch (IOException e) {
			folderMoveFailed = true;
			getLogger().severe("Could not move plugins/" + OLD_NAME + " to plugins/" + folder.getName() + ": " + e);
		}
	}

	@Override
	public void onEnable() {
		if (folderMoveFailed) {
			getLogger().severe("Not starting, so nobody loses their morphs. Stop the server, rename plugins/" + OLD_NAME + " to plugins/"
					+ getDataFolder().getName() + ", and start it again.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		saveDefaultConfig();
		upgradeConfig();
		installOrUpgrade("variants.yml");
		installOrUpgrade("abilities.yml");
		mergeUpgrade("survival.yml", "pre-6.1.6", SURVIVAL_REPLACED);
		settings = new PluginSettings(getConfig());
		try {
			store = new DataStore(getDataFolder().toPath().resolve("players"), getLogger());
		} catch (IOException e) {
			getLogger().severe("Cannot create the player data folder; disabling. " + e);
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		capture = new VariantCapture(VariantRules.load(yaml("variants.yml"), getConfig()), getLogger());
		templates = new MorphTemplates(getLogger());
		disguises = new DisguiseBridge(getLogger());
		borrowedHeads = new dev.morphreborn.paper.disguise.BorrowedHeads(this);
		getServer().getPluginManager().registerEvents(borrowedHeads, this);
		channel = new ClientChannel(this);
		effects = new VanillaEffects(this);
		skin = new dev.morphreborn.paper.effects.SkinEffects(this);
		getServer().getPluginManager().registerEvents(skin, this);
		abilities = new AbilityManager(this);
		abilities.load(yaml("abilities.yml"));
		jockeys = new dev.morphreborn.paper.ability.Jockeys(this);
		jockeys.load(yaml("abilities.yml").getConfigurationSection("jockeys"));
		rage = new dev.morphreborn.paper.ability.Rage(this);
		rage.load(yaml("abilities.yml").getConfigurationSection("rage"));
		morphs = new MorphManager(this);
		survival = new SurvivalMode(this, new SurvivalConfig(yaml("survival.yml"), getLogger()));
		mode = settings.gameplayMode.equals("survival") ? survival : new ClassicMode(this);

		channel.register();
		getServer().getPluginManager().registerEvents(new MorphListener(this), this);
		getServer().getPluginManager().registerEvents(new AbilityListener(this), this);
		getServer().getPluginManager().registerEvents(jockeys, this);
		getServer().getPluginManager().registerEvents(rage, this);
		trading = new dev.morphreborn.paper.ability.VillagerTrading(this);
		getServer().getPluginManager().registerEvents(trading, this);
		getServer().getPluginManager().registerEvents(new SurvivalListener(this), this);
		conversions = new dev.morphreborn.paper.morph.Conversions(this);
		getServer().getPluginManager().registerEvents(conversions, this);
		UpdateChecker updates = new UpdateChecker(this);
		getServer().getPluginManager().registerEvents(updates, this);
		updates.start();
		MorphCommand command = new MorphCommand(this);
		Objects.requireNonNull(getCommand("morph")).setExecutor(command);
		Objects.requireNonNull(getCommand("morph")).setTabCompleter(command);

		Bukkit.getScheduler().runTaskTimer(this, () -> {
			morphs.tick();
			abilities.tick();
			jockeys.tick();
			rage.tick();
			borrowedHeads.tick();
			skin.tick();
			conversions.tick();
		}, 1L, 1L);
		scheduleAutosave();

		for (Player p : Bukkit.getOnlinePlayers()) morphs.onJoin(p); // /reload safety
		getLogger().info("RE:Morph enabled: " + mode.id() + " mode, protocol v" + dev.morphreborn.paper.net.Wire.PROTOCOL_VERSION + ", transition "
				+ settings.transitionTicks + " ticks.");
	}

	@Override
	public void onDisable() {
		if (jockeys != null) jockeys.releaseAll();
		if (rage != null) rage.releaseAll();
		if (trading != null) trading.releaseAll();
		if (borrowedHeads != null) borrowedHeads.removeAll();
		if (skin != null) skin.removeAll();
		if (morphs != null) morphs.shutdown();
		if (channel != null) channel.unregister();
		if (store != null) store.shutdown();
	}

	private int autosaveTask = -1;

	private void scheduleAutosave() {
		if (autosaveTask != -1) Bukkit.getScheduler().cancelTask(autosaveTask);
		long period = settings.autosaveSeconds * 20L;
		autosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> store.flushDirtyAsync(), period, period).getTaskId();
	}

	public void reloadEverything() {
		upgradeConfig();
		reloadConfig();
		settings = new PluginSettings(getConfig());
		installOrUpgrade("variants.yml");
		installOrUpgrade("abilities.yml");
		mergeUpgrade("survival.yml", "pre-6.1.6", SURVIVAL_REPLACED);
		survival.setConfig(new SurvivalConfig(yaml("survival.yml"), getLogger()));
		if (!settings.gameplayMode.equals(mode.id())) switchMode(settings.gameplayMode, false);
		capture.setRules(VariantRules.load(yaml("variants.yml"), getConfig()));
		abilities.load(yaml("abilities.yml"));
		jockeys.load(yaml("abilities.yml").getConfigurationSection("jockeys"));
		rage.load(yaml("abilities.yml").getConfigurationSection("rage"));
		templates.clear();
		scheduleAutosave();
		for (Player p : Bukkit.getOnlinePlayers()) {
			abilities.onFormChanged(p, morphs.activeForm(p));
			channel.fullSync(p);
			survival.sync(p);
			rage.sync(p);
		}
	}

	/** Re-read config.yml's typed settings only (after /morph setting changed one). */
	public void reloadSettings() {
		settings = new PluginSettings(getConfig());
	}

	private void upgradeConfig() {
		mergeUpgrade("config.yml", "pre-6.1.4", Map.of());
	}

	/**
	 * config.yml and survival.yml hold the admin's own settings, so unlike abilities.yml and variants.yml they're never
	 * replaced. When this release's copy is newer (its config-version), the admin's file is rewritten in the new
	 * layout: settings added since appear with their defaults and comments, and every value the admin set is kept (see
	 * {@link ConfigMerge}). The old file stays next to it as {@code <name>-old-<version>.yml}. A missing file is written
	 * from the bundled one. {@code unversioned} names files from before the file had a config-version.
	 * <p>
	 * {@code replaced}: sections whose meaning changed in a release (section -> that release). A file older than it gets
	 * the bundled section instead of keeping its own, since the old values would mean something else now.
	 */
	private void mergeUpgrade(String name, String unversioned, Map<String, String> replaced) {
		File file = new File(getDataFolder(), name);
		if (!file.exists()) {
			if (!name.equals("config.yml")) saveResource(name, false); // saveDefaultConfig writes config.yml
			return;
		}
		String stem = name.substring(0, name.lastIndexOf('.'));
		YamlConfiguration bundled = new YamlConfiguration();
		try (java.io.Reader reader = new java.io.InputStreamReader(Objects.requireNonNull(getResource(name)), java.nio.charset.StandardCharsets.UTF_8)) {
			bundled.load(reader);
		} catch (IOException | InvalidConfigurationException | NullPointerException e) {
			getLogger().warning("Could not read the bundled " + name + "; leaving yours as it is. " + e);
			return;
		}
		YamlConfiguration current = new YamlConfiguration();
		try {
			current.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			getLogger().severe(name + " has an error, so it wasn't updated with this release's new settings: " + e.getMessage()
					+ " Fix the file and restart; until then, settings it can't read use their defaults.");
			return;
		}
		String bundledVersion = bundled.getString(CONFIG_VERSION, "");
		String currentVersion = current.getString(CONFIG_VERSION, "");
		int order = compareVersions(currentVersion, bundledVersion);
		if (order > 0) {
			getLogger().warning(name + " is from RE:Morph " + currentVersion + ", newer than this one (" + bundledVersion + "). Leaving it as it is.");
		}
		if (order >= 0) return;
		List<String> reset = new java.util.ArrayList<>();
		for (Map.Entry<String, String> r : replaced.entrySet()) {
			if (current.contains(r.getKey()) && compareVersions(currentVersion, r.getValue()) < 0) {
				current.set(r.getKey(), null);
				reset.add(r.getKey());
			}
		}
		List<String> added = new java.util.ArrayList<>(ConfigMerge.merge(bundled, current, CONFIG_VERSION));
		added.removeIf(key -> reset.stream().anyMatch(r -> key.equals(r) || key.startsWith(r + ".")));
		bundled.options().width(1000); // long messages stay on one line
		String from = currentVersion.isEmpty() ? unversioned : currentVersion;
		File backup = new File(getDataFolder(), stem + "-old-" + from + ".yml");
		for (int n = 2; backup.exists(); n++) backup = new File(getDataFolder(), stem + "-old-" + from + "-" + n + ".yml");
		try {
			Files.copy(file.toPath(), backup.toPath());
			bundled.save(file);
		} catch (IOException e) {
			getLogger().severe("Could not update " + name + " to this release's layout: " + e + ". Your settings still apply; new settings use their"
					+ " defaults until you add them by hand.");
			return;
		}
		getLogger().info("Updated " + name + " from RE:Morph " + from + " to " + bundledVersion + ". Your settings are unchanged"
				+ (reset.isEmpty() ? ". " : " except the replaced sections below. ")
				+ (added.isEmpty() ? "No new settings." : "New settings, at their defaults: " + String.join(", ", added) + ".")
				+ " Your old file is " + backup.getName() + ".");
		for (String section : reset) {
			getLogger().warning(name + ": the '" + section + "' section changed its layout in " + replaced.get(section)
					+ ", so it was replaced with the new one. Copy back any changes you made from " + backup.getName() + ".");
		}
	}

	/**
	 * survival.yml sections replaced on update. 6.6.0: the upgrade tree's levels changed meaning (Morph Efficiency and
	 * Morph Abilities became "% of Classic", reach and absorbable mass got new levels) and the ability unlocks were added.
	 * 7.3.0: Rage became Devouring Frenzy, with three levels.
	 */
	private static final Map<String, String> SURVIVAL_REPLACED = Map.of("upgrades", "6.6.0", "upgrades.rage", "7.4.0");

	/**
	 * Write the bundled file if it's missing, or replace it when it comes from an older release (its config-version,
	 * the RE:Morph version that last changed the file, is lower; files from before 5.0.0 have none). Abilities get
	 * renamed and reshaped between releases, and an old file silently keeps them broken: a witch whose ability no
	 * longer exists does nothing. The old file is kept as {@code <name>-old-<version>.yml} for admins to copy back from.
	 */
	private void installOrUpgrade(String name) {
		File file = new File(getDataFolder(), name);
		if (!file.exists()) {
			saveResource(name, false);
			return;
		}
		String bundled;
		try (java.io.Reader reader = new java.io.InputStreamReader(Objects.requireNonNull(getResource(name)), java.nio.charset.StandardCharsets.UTF_8)) {
			bundled = YamlConfiguration.loadConfiguration(reader).getString(CONFIG_VERSION, "");
		} catch (IOException | NullPointerException e) {
			getLogger().warning("Could not read the bundled " + name + "; keeping yours. " + e);
			return;
		}
		String current = yaml(name).getString(CONFIG_VERSION, "");
		int order = compareVersions(current, bundled);
		if (order > 0) {
			getLogger().warning(name + " is from RE:Morph " + current + ", newer than this one (" + bundled + "). Keeping it, but some entries may not work.");
		}
		if (order >= 0) return;
		String from = current.isEmpty() ? "pre-5.0.0" : current;
		String stem = name.substring(0, name.lastIndexOf('.'));
		File backup = new File(getDataFolder(), stem + "-old-" + from + ".yml");
		for (int n = 2; backup.exists(); n++) backup = new File(getDataFolder(), stem + "-old-" + from + "-" + n + ".yml");
		try {
			java.nio.file.Files.move(file.toPath(), backup.toPath());
		} catch (IOException e) {
			getLogger().severe("Your " + name + " is from RE:Morph " + from + " and this release needs its " + bundled
					+ " version, but it could not be moved aside: " + e + ". Replace it with the bundled one by hand, or some abilities will not work.");
			return;
		}
		saveResource(name, false);
		getLogger().warning("Replaced " + name + " from RE:Morph " + from + " with this release's version (" + bundled
				+ "). Your old file is " + backup.getName() + "; copy back any changes you made.");
	}

	/** Dotted version order (5.0.0 < 5.1.0 < 5.10.0); a missing version sorts first. Non-numeric parts count as 0. */
	static int compareVersions(String a, String b) {
		String[] x = a.isEmpty() ? new String[0] : a.split("\\.");
		String[] y = b.isEmpty() ? new String[0] : b.split("\\.");
		if (x.length == 0 || y.length == 0) return Integer.compare(x.length == 0 ? 0 : 1, y.length == 0 ? 0 : 1);
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int c = Integer.compare(part(x, i), part(y, i));
			if (c != 0) return c;
		}
		return 0;
	}

	private static int part(String[] parts, int i) {
		if (i >= parts.length) return 0;
		try {
			return Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static final String CONFIG_VERSION = "config-version";

	private FileConfiguration yaml(String name) {
		return YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
	}

	public void debug(String message) {
		if (settings != null && settings.debug) getLogger().info("[debug] " + message);
	}

	public PluginSettings settings() {
		return settings;
	}

	public DataStore store() {
		return store;
	}

	public VariantCapture capture() {
		return capture;
	}

	private dev.morphreborn.paper.morph.Conversions conversions;

	public dev.morphreborn.paper.morph.Conversions conversions() {
		return conversions;
	}

	public MorphTemplates templates() {
		return templates;
	}

	public dev.morphreborn.paper.disguise.BorrowedHeads borrowedHeads() {
		return borrowedHeads;
	}

	public DisguiseBridge disguises() {
		return disguises;
	}

	public ClientChannel channel() {
		return channel;
	}

	public VanillaEffects effects() {
		return effects;
	}

	/** The mod's morph-skin animations for players without the mod who have the pack; null until enabled. */
	public dev.morphreborn.paper.effects.SkinEffects skin() {
		return skin;
	}

	public dev.morphreborn.paper.ability.Rage rage() {
		return rage;
	}

	public AbilityManager abilities() {
		return abilities;
	}

	public MorphManager morphs() {
		return morphs;
	}

	/**
	 * Switch between Classic and Survival right now. Nothing is deleted: collections and Survival progress are kept in
	 * both modes. Online players are brought in line with the new rules: a morphed player who may not morph any more
	 * turns back, flight and abilities are re-checked, and modded clients learn the mode (selector, Biomass button, bar).
	 * {@code save} also writes it to config.yml so it lasts across restarts. Returns false if already in that mode.
	 */
	public boolean switchMode(String id, boolean save) {
		GameplayMode next = id.equals("survival") ? survival : new ClassicMode(this);
		if (next.id().equals(mode.id())) return false;
		String from = mode.id();
		mode = next;
		if (save) {
			getConfig().set("gameplay-mode", next.id());
			if (getConfig() instanceof YamlConfiguration yaml) yaml.options().width(1000);
			saveConfig();
			settings = new PluginSettings(getConfig());
		}
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (mode.cannotMorph(p) != null && morphs.activeForm(p) != null) {
				morphs.demorph(p, false);
			} else {
				abilities.onFormChanged(p, morphs.activeForm(p));
			}
			channel.resendHello(p);
			channel.fullSync(p);
			survival.sync(p);
			rage.sync(p); // the meter counts differently in each mode
			mode.onJoin(p);
		}
		getLogger().info("Switched from " + from + " to " + next.id() + " mode" + (save ? " (saved to config.yml)." : "."));
		return true;
	}

	/**
	 * Change one survival.yml setting (null removes it), keeping the file's comments and everything else, then apply it
	 * and send modded players their new morph prices. For /morph cost.
	 */
	public void setSurvivalSetting(String path, @org.jspecify.annotations.Nullable Object value) {
		File file = new File(getDataFolder(), "survival.yml");
		YamlConfiguration yaml = new YamlConfiguration();
		try {
			yaml.load(file);
		} catch (IOException | InvalidConfigurationException e) {
			throw new IllegalArgumentException("survival.yml can't be read (" + e.getMessage() + "); fix it first.");
		}
		yaml.set(path, value);
		yaml.options().width(1000);
		try {
			yaml.save(file);
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not save survival.yml: " + e.getMessage());
		}
		survival.setConfig(new SurvivalConfig(yaml("survival.yml"), getLogger()));
		for (Player p : Bukkit.getOnlinePlayers()) survival.sendCosts(p);
	}

	/** The server's gameplay mode: Classic or Survival. */
	public GameplayMode mode() {
		return mode;
	}

	/** /morph upgrades and the client's Biomass Menu key. */
	public void openUpgrades(Player player) {
		if (mode != survival) {
			player.sendMessage(net.kyori.adventure.text.Component.text("This server plays Classic Mode: morphs unlock by killing, with no upgrades.",
					net.kyori.adventure.text.format.NamedTextColor.GRAY));
			return;
		}
		// Players with the client mod get its upgrade tree screen; everyone else the chest menu.
		if (channel.isModded(player)) channel.sendUpgrades(player, true, "");
		else dev.morphreborn.paper.survival.SurvivalMenu.open(survival, player);
	}

	/** Survival Mode progress; exists in both modes (see {@link SurvivalMode#active()}). */
	public SurvivalMode survival() {
		return survival;
	}
}
