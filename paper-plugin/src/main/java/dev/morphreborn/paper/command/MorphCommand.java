package dev.morphreborn.paper.command;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerMorphData;
import dev.morphreborn.paper.morph.ActiveMorph;
import dev.morphreborn.paper.morph.MorphManager;
import dev.morphreborn.paper.nbt.Snbt;
import dev.morphreborn.paper.survival.SurvivalConfig;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tips.TooltipPrefs;
import dev.morphreborn.paper.variant.VariantCapture;
import dev.morphreborn.paper.variant.VariantRules;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** /morph: player selection for unmodded clients plus admin management. */
public final class MorphCommand implements TabExecutor {
	private static final List<String> PLAYER_SUBS = List.of("list", "use", "combine", "off", "ability", "fav", "upgrades", "upgrade", "passive", "biomass", "frenzy", "arm",
			"tooltips");
	private static final List<String> ADMIN_SUBS = List.of("grant", "revoke", "reset", "force", "borrow", "info", "clients", "carry", "setting", "reload", "survival", "mode", "cost");
	private static final List<String> SURVIVAL_ACTIONS = List.of("info", "unlock", "lock", "give", "take", "set", "level", "buy", "reset");

	private final MorphRebornPlugin plugin;

	public MorphCommand(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			help(sender, label);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (ADMIN_SUBS.contains(sub) && !sender.hasPermission("morphreborn.admin")) {
			sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
			return true;
		}
		try {
			switch (sub) {
				case "list" -> list(sender, args.length > 1 ? player(args[1]) : self(sender));
				case "use" -> use(sender, args);
				case "combine" -> combine(sender, args);
				case "off" -> {
					Player p = self(sender);
					if (p != null && commandsAllowed(p)) plugin.morphs().requestMorph(p, MorphRef.SELF_ID);
				}
				case "ability" -> {
					// Admins (and the console) can fire another player's ability: /morph ability <player>
					Player p = args.length > 1 && sender.hasPermission("morphreborn.admin") ? player(args[1]) : self(sender);
					if (p != null) plugin.abilities().trigger(p);
				}
				case "fav" -> fav(sender, args);
				// admins: open it for another player (support, testing)
				case "upgrades" -> plugin.openUpgrades(args.length > 1 && sender.hasPermission("morphreborn.admin") ? player(args[1]) : self(sender));
				case "upgrade" -> upgrade(sender, args);
				case "passive", "passives" -> passive(sender, args);
				case "tooltips" -> tooltips(sender, args);
				case "biomass" -> biomass(sender, args.length > 1 && sender.hasPermission("morphreborn.admin") ? player(args[1]) : self(sender));
				case "survival" -> survival(sender, args);
				case "frenzy", "rage" -> frenzy(sender, args); // called /morph rage in 7.2.0
				case "arm" -> arm(sender, label, args);
				case "grant" -> grant(sender, args);
				case "revoke" -> revoke(sender, args);
				case "reset" -> reset(sender, args);
				case "force" -> force(sender, args);
				case "borrow" -> borrow(sender, args);
				case "info" -> info(sender, args);
				case "clients" -> clients(sender);
				case "carry" -> carry(sender, args);
				case "setting" -> setting(sender, args);
				case "mode" -> mode(sender, args);
				case "cost" -> cost(sender, args);
				case "reload" -> {
					plugin.reloadEverything();
					sender.sendMessage(Component.text("RE:Morph reloaded.", NamedTextColor.GREEN));
				}
				default -> help(sender, label);
			}
		} catch (IllegalArgumentException e) {
			sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
		}
		return true;
	}

	private void help(CommandSender sender, String label) {
		sender.sendMessage(Component.text("RE:Morph", NamedTextColor.GOLD));
		sender.sendMessage(Component.text("/" + label + " list | use <id> | off | ability | fav <id>", NamedTextColor.GRAY));
		if (plugin.settings().multimorphEnabled) {
			sender.sendMessage(Component.text("/" + label + " combine <main> <borrowed> | combine <borrowed> | combine off (\"self\" is your own body)", NamedTextColor.GRAY));
		}
		if (plugin.survival().active()) sender.sendMessage(Component.text("/" + label + " biomass | upgrades | upgrade <id>", NamedTextColor.GRAY));
		if (sender.hasPermission("morphreborn.tooltips")) sender.sendMessage(Component.text("/" + label + " tooltips [on|off]", NamedTextColor.GRAY));
		if (plugin.arms().enabled()) sender.sendMessage(Component.text("/" + label + " " + ARM_USAGE, NamedTextColor.GRAY));
		if (plugin.arms().enabled() && sender.hasPermission("morphreborn.arm.admin")) {
			sender.sendMessage(Component.text("/" + label + " " + ARM_ADMIN_USAGE, NamedTextColor.GRAY));
		}
		if (sender.hasPermission("morphreborn.admin")) {
			sender.sendMessage(Component.text("/" + label + " grant <player> <type> [snbt] | revoke <player> <type|id|all> | reset <player>", NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " force <player> <type|id|off> [snbt] | info <player> | clients | reload", NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " carry <player> <block|none> | setting [name] [value] | mode [classic|survival]", NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " cost [on|off | rate <per-kg> | minimum <n> | price <mob> <biomass|formula> | gain <multiplier> | arm ...]",
					NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " survival <player> info | unlock | lock | give|take|set <biomass> | level <upgrade> <n> | buy <upgrade> | reset",
					NamedTextColor.GRAY));
		}
	}

	private static final String ARM_USAGE = "arm | arm <form> | arm off | arm lantern|shield|spyglass|flint [on|off] | arm lantern bright 1-5 | arm special";
	private static final String ARM_ADMIN_USAGE = "arm <player> <form|off|lantern|shield|spyglass|flint> [level] [free] | special | brightness | info | recover | break <x> <y> <z>"
			+ " | hit <target> | shoot [power]";

	/**
	 * /morph arm: arm forms, which the arm code answers ({@code plugin.arms()}, given the words after "arm"). Not gated by
	 * commandsAllowed, unlike picking morphs: players without the client mod have no other way to form an arm or use its
	 * special.
	 */
	private void arm(CommandSender sender, String label, String[] args) {
		if (plugin.arms().command(sender, java.util.Arrays.copyOfRange(args, 1, args.length))) return;
		if (!plugin.arms().enabled()) throw new IllegalArgumentException("Arm forms are off on this server.");
		sender.sendMessage(Component.text("/" + label + " " + ARM_USAGE, NamedTextColor.GRAY));
	}

	// ------------------------------------------------------------------ /morph cost arm

	/** /morph cost arm <what> <biomass>: the survival.yml key each one writes. */
	private static final Map<String, String> ARM_COST_PATHS = Map.of("block", "arm-cost.per-block", "hit", "arm-cost.per-hit", "transform",
			"arm-cost.per-transform", "bolt", "arm-cost.per-bolt", "drain", "arm-cost.drain-per-minute.tool", "lantern-drain",
			"arm-cost.drain-per-minute.lantern", "shield-drain", "arm-cost.drain-per-minute.shield", "spyglass-drain", "arm-cost.drain-per-minute.spyglass",
			"flint-drain", "arm-cost.drain-per-minute.flint");
	private static final List<String> ARM_COST_ACTIONS = List.of("on", "off", "block", "hit", "transform", "bolt", "drain", "lantern-drain", "shield-drain",
			"spyglass-drain", "flint-drain", "special");
	private static final String ARM_COST_USAGE = "/morph cost arm [on|off | block|hit|transform|bolt <biomass> | drain|lantern-drain|shield-drain|spyglass-drain|flint-drain <biomass a minute>"
			+ " | special <key> <level 1> <level 2> <level 3>]";

	/** The keys of arm-cost.specials: the bundled ones. */
	private static List<String> armSpecialKeys() {
		return new ArrayList<>(dev.morphreborn.paper.arm.rules.ArmConfig.DEFAULTS.costs().specials().keySet());
	}

	/**
	 * /morph cost arm: what arm forms cost in Survival (survival.yml arm-cost), shown or changed and saved. Arms read
	 * their costs when they load, so a change is handed to them through their reload, which puts formed arms away
	 * first (their items back in their slots); they can be formed again at once.
	 */
	private void costArm(CommandSender sender, String[] args) {
		if (args.length < 3) {
			showArmCosts(sender);
			return;
		}
		String what = args[2].toLowerCase(Locale.ROOT);
		String path;
		Object value;
		String done;
		switch (what) {
			case "on", "off" -> {
				path = "arm-cost.enabled";
				value = what.equals("on");
				done = what.equals("on") ? "Arm forms spend biomass in Survival again." : "Arm forms are free now, and don't drain.";
			}
			case "block", "hit", "transform", "bolt", "drain", "lantern-drain", "shield-drain", "spyglass-drain", "flint-drain" -> {
				boolean drain = what.endsWith("drain");
				double amount = number(args, 3, "/morph cost arm " + what + " <biomass" + (drain ? " a minute>" : ">"));
				path = ARM_COST_PATHS.get(what);
				value = yamlNumber(amount);
				String biomass = armBiomass(amount);
				done = switch (what) {
					case "block" -> "Each block an arm breaks now costs " + biomass + ".";
					case "hit" -> "Each melee hit with an arm now costs " + biomass + ".";
					case "transform" -> "Each strip, till, path or scrape now costs " + biomass + ".";
					case "bolt" -> "Each bolt now costs " + biomass + ".";
					case "drain" -> "A formed arm now drains " + biomass + " a minute.";
					case "lantern-drain" -> "A lit lantern now drains " + biomass + " a minute, on top of the arm's.";
					default -> "A " + what.substring(0, what.length() - "-drain".length()) + " that is on now drains " + biomass + " a minute, on top of the arm's.";
				};
			}
			case "special" -> {
				String usage = "/morph cost arm special <" + String.join("|", armSpecialKeys()) + "> <level 1> <level 2> <level 3>";
				if (args.length < 7) throw new IllegalArgumentException(usage);
				String key = args[3].toLowerCase(Locale.ROOT);
				if (!armSpecialKeys().contains(key)) throw new IllegalArgumentException("Unknown special '" + args[3] + "'. " + usage);
				List<Object> levels = new ArrayList<>();
				List<String> shown = new ArrayList<>();
				for (int i = 4; i <= 6; i++) {
					double amount = number(args, i, usage);
					levels.add(yamlNumber(amount));
					shown.add(armBiomass(amount));
				}
				path = "arm-cost.specials." + key;
				value = levels;
				done = "The " + key + " special now costs " + String.join(" / ", shown) + " (levels 1, 2 and 3).";
			}
			default -> throw new IllegalArgumentException(ARM_COST_USAGE);
		}
		plugin.setSurvivalSetting(path, value);
		if (plugin.arms().enabled()) {
			reloadArms();
			done += " Saved to survival.yml; arm forms use it now (any formed arm was put away).";
		} else {
			done += " Saved to survival.yml.";
		}
		sender.sendMessage(Component.text(done, NamedTextColor.GREEN));
	}

	private void showArmCosts(CommandSender sender) {
		org.bukkit.configuration.ConfigurationSection section = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "survival.yml"))
				.getConfigurationSection("arm-cost");
		var costs = dev.morphreborn.paper.arm.rules.ArmConfig.parse(Map.of(), section == null ? Map.of() : section.getValues(true), Map.of()).costs();
		sender.sendMessage(Component.text("Arm cost: " + (costs.enabled() ? "on" : "off") + ". Per block " + milli(costs.perBlock()) + ", per hit "
				+ milli(costs.perHit()) + ", per strip, till or path " + milli(costs.perTransform()) + ", per bolt " + milli(costs.perBolt()) + "; "
				+ milli(costs.toolDrainPerMinute()) + " a minute while formed, and more while on: lantern " + milli(costs.accessoryDrainPerMinute("lantern")) + ", shield "
				+ milli(costs.accessoryDrainPerMinute("shield")) + ", spyglass " + milli(costs.accessoryDrainPerMinute("spyglass")) + ", flint "
				+ milli(costs.accessoryDrainPerMinute("flint")) + ".",
				NamedTextColor.GOLD));
		List<String> specials = new ArrayList<>();
		costs.specials().forEach((key, l) -> specials.add(key + " " + milli(l.l1()) + "/" + milli(l.l2()) + "/" + milli(l.l3())));
		sender.sendMessage(Component.text("Specials (levels 1/2/3): " + String.join(", ", specials), NamedTextColor.GRAY));
		sender.sendMessage(Component.text("Change with " + ARM_COST_USAGE, NamedTextColor.GRAY));
		if (!plugin.survival().active()) sender.sendMessage(Component.text("These apply in Survival mode; the server is in Classic.", NamedTextColor.GRAY));
	}

	/**
	 * Arm forms take their settings again from the files: abilities.yml arms (over the plugin's own copy, as at startup)
	 * and survival.yml arm-cost. Then every player gets their Arsenal again with the new costs.
	 */
	private void reloadArms() {
		File folder = plugin.getDataFolder();
		org.bukkit.configuration.ConfigurationSection arms = YamlConfiguration.loadConfiguration(new File(folder, "abilities.yml")).getConfigurationSection("arms");
		org.bukkit.configuration.ConfigurationSection armCost = YamlConfiguration.loadConfiguration(new File(folder, "survival.yml"))
				.getConfigurationSection("arm-cost");
		org.bukkit.configuration.ConfigurationSection bundled = null;
		java.io.InputStream in = plugin.getResource("abilities.yml");
		if (in != null) {
			try (java.io.Reader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
				bundled = YamlConfiguration.loadConfiguration(reader).getConfigurationSection("arms");
			} catch (java.io.IOException e) {
				plugin.getLogger().warning("Could not read the bundled abilities.yml: " + e); // the server's own section still applies
			}
		}
		plugin.arms().reload(arms, armCost, bundled);
		for (Player p : Bukkit.getOnlinePlayers()) plugin.arms().sync(p);
	}

	/** A whole number as one (so the file reads 12, not 12.0), anything else as it is. */
	private static Object yamlNumber(double v) {
		return v == Math.rint(v) && Math.abs(v) < Integer.MAX_VALUE ? (Object) (int) v : (Object) v;
	}

	/** Biomass as arms bill it, to the thousandth: 0.25 stays 0.25. */
	private static String armBiomass(double biomass) {
		return milli(dev.morphreborn.paper.arm.rules.ArmLedger.toMilli(biomass));
	}

	private static String milli(long milli) {
		return dev.morphreborn.paper.arm.rules.ArmItemSpecs.biomass(milli);
	}

	/**
	 * /morph cost: Survival's morph prices and biomass gain, shown or changed and saved to survival.yml.
	 * on|off, rate <biomass per kg>, minimum <biomass>, price <mob> <biomass|formula>, gain <multiplier>; arm ... for what
	 * arm forms cost ({@link #costArm}).
	 */
	private void cost(CommandSender sender, String[] args) {
		var c = plugin.survival().config();
		if (args.length >= 2 && args[1].equalsIgnoreCase("arm")) {
			costArm(sender, args);
			return;
		}
		if (args.length < 2) {
			sender.sendMessage(Component.text("Morph cost: " + (c.morphCostEnabled ? "on" : "off") + ", " + c.morphCostPerKg + " biomass per kg of the form's mass, at least "
					+ c.morphCostMinimum + ". Biomass gain x" + c.gainMultiplier + ".", NamedTextColor.GOLD));
			if (!c.morphPrices.isEmpty()) {
				List<String> fixed = new ArrayList<>();
				c.morphPrices.forEach((type, price) -> fixed.add(type.replace("minecraft:", "") + " " + price));
				java.util.Collections.sort(fixed);
				sender.sendMessage(Component.text("Fixed prices: " + String.join(", ", fixed), NamedTextColor.GRAY));
			}
			if (plugin.arms().enabled()) sender.sendMessage(Component.text("What arm forms cost: /morph cost arm", NamedTextColor.GRAY));
			if (!plugin.survival().active()) sender.sendMessage(Component.text("These apply in Survival mode; the server is in Classic.", NamedTextColor.GRAY));
			return;
		}
		String what = args[1].toLowerCase(Locale.ROOT);
		String path;
		Object value;
		String done;
		switch (what) {
			case "on", "off" -> {
				path = "morph-cost.enabled";
				value = what.equals("on");
				done = "Morphing " + (what.equals("on") ? "now costs biomass." : "is free now.");
			}
			case "rate" -> {
				double rate = number(args, 2, "/morph cost rate <biomass per kg>, e.g. 0.01");
				path = "morph-cost.per-kg";
				value = rate;
				done = "Morph prices are now " + rate + " biomass per kg (a zombie costs " + (int) Math.ceil(702 * rate - 1e-9) + ").";
			}
			case "minimum" -> {
				int min = (int) number(args, 2, "/morph cost minimum <biomass>");
				path = "morph-cost.minimum";
				value = min;
				done = "Every morph now costs at least " + min + ".";
			}
			case "gain" -> {
				double gain = number(args, 2, "/morph cost gain <multiplier>, e.g. 2 for twice as much biomass");
				path = "biomass.gain-multiplier";
				value = gain;
				done = "Kills now give x" + gain + " biomass.";
			}
			case "price" -> {
				if (args.length < 4) throw new IllegalArgumentException("/morph cost price <mob> <biomass|formula>");
				String type = dev.morphreborn.paper.variant.VariantRules.normaliseType(args[2].toLowerCase(Locale.ROOT));
				if (Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.fromString(type)) == null) throw new IllegalArgumentException("Unknown mob '" + args[2] + "'.");
				path = "morph-cost.prices." + type;
				if (args[3].equalsIgnoreCase("formula")) {
					value = null;
					done = type.replace("minecraft:", "") + " morphs are priced by the mass formula again.";
				} else {
					int price = (int) number(args, 3, "/morph cost price <mob> <biomass|formula>");
					value = price;
					done = type.replace("minecraft:", "") + " morphs now cost " + price + ".";
				}
			}
			default -> throw new IllegalArgumentException("/morph cost [on|off | rate <per-kg> | minimum <n> | price <mob> <biomass|formula> | gain <multiplier> | arm ...]");
		}
		plugin.setSurvivalSetting(path, value);
		sender.sendMessage(Component.text(done + " Saved to survival.yml.", NamedTextColor.GREEN));
	}

	private static double number(String[] args, int index, String usage) {
		if (args.length <= index) throw new IllegalArgumentException(usage);
		try {
			double v = Double.parseDouble(args[index]);
			if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
			return v;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + args[index] + "' isn't a number of 0 or more. " + usage);
		}
	}

	/** /morph mode [classic|survival]: show or switch the gameplay mode, live, and save it to config.yml. */
	private void mode(CommandSender sender, String[] args) {
		String current = plugin.mode().id();
		if (args.length < 2) {
			sender.sendMessage(Component.text("Gameplay mode: " + current + ". Switch with /morph mode classic|survival.", NamedTextColor.GOLD));
			return;
		}
		String wanted = args[1].toLowerCase(Locale.ROOT);
		if (wanted.equals("default")) wanted = "survival";
		if (!wanted.equals("classic") && !wanted.equals("survival")) throw new IllegalArgumentException("The mode can be classic or survival.");
		if (!plugin.switchMode(wanted, true)) {
			sender.sendMessage(Component.text("Already in " + current + " mode.", NamedTextColor.GRAY));
			return;
		}
		sender.sendMessage(Component.text("Switched to " + wanted + " mode and saved it to config.yml. Collections and Survival progress are kept.",
				NamedTextColor.GREEN));
		// what to do next (where the progress is) is a tip, so each player is told by their own Tooltips setting
		String survival = "The server switched to Survival Mode: morphing is now earned with biomass.";
		Component withTips = Component.text(survival + " /morph upgrades shows your progress.", NamedTextColor.GOLD);
		Component without = Component.text(survival, NamedTextColor.GOLD);
		Component classic = Component.text("The server switched to Classic Mode: kill a creature to unlock it as a morph.", NamedTextColor.GOLD);
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p != sender) p.sendMessage(wanted.equals("survival") ? (plugin.tooltips(p) ? withTips : without) : classic);
		}
	}

	private @Nullable Player self(CommandSender sender) {
		if (sender instanceof Player p) return p;
		throw new IllegalArgumentException("Only players can do that; give a player name.");
	}

	private Player player(String name) {
		Player p = Bukkit.getPlayerExact(name);
		if (p == null) throw new IllegalArgumentException("Player '" + name + "' is not online.");
		return p;
	}

	private boolean commandsAllowed(Player p) {
		if (plugin.settings().commandsForVanilla || plugin.channel().isModded(p)) return true;
		p.sendMessage(Component.text("Use the RE:Morph client mod to pick morphs.", NamedTextColor.RED));
		return false;
	}

	/**
	 * /morph frenzy: set off your charged Devouring Frenzy (or hear why not). Admins, for testing: /morph frenzy <player>
	 * as their own key press would, {@code go [level]} to set it off charged or not (at that level, or theirs), {@code
	 * fill} to charge it.
	 */
	private void frenzy(CommandSender sender, String[] args) {
		if (args.length >= 2 && sender.hasPermission("morphreborn.admin")) {
			Player target = player(args[1]);
			String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
			switch (action) {
				case "" -> plugin.rage().trigger(target);
				case "go" -> {
					int level = 0;
					if (args.length >= 4) {
						try {
							level = Integer.parseInt(args[3]);
						} catch (NumberFormatException e) {
							level = -1;
						}
						if (level < 1 || level > plugin.rage().levels()) {
							throw new IllegalArgumentException("The level is 1 to " + plugin.rage().levels() + ".");
						}
					}
					String why = plugin.rage().start(target, level);
					sender.sendMessage(why == null ? Component.text(target.getName() + " is in a frenzy (level " + (level > 0 ? level : plugin.rage().level(target))
							+ ": " + plugin.rage().describe(level > 0 ? level : plugin.rage().level(target)) + ").", NamedTextColor.GREEN)
							: Component.text(why, NamedTextColor.RED));
				}
				case "fill" -> {
					plugin.rage().fill(target);
					sender.sendMessage(Component.text(target.getName() + "'s frenzy is charged.", NamedTextColor.GREEN));
				}
				default -> throw new IllegalArgumentException("Usage: /morph frenzy <player> [go [level]|fill]");
			}
			return;
		}
		Player p = self(sender);
		if (p != null) plugin.rage().trigger(p);
	}

	private void list(CommandSender sender, Player target) {
		PlayerMorphData data = plugin.store().get(target.getUniqueId());
		ActiveMorph state = plugin.morphs().state(target);
		String currentId = state != null && state.next != null ? state.next.variantId() : MorphRef.SELF_ID;
		sender.sendMessage(Component.text(target.getName() + "'s morphs (" + data.variantCount() + "):", NamedTextColor.GOLD));
		if (plugin.survival().active()) {
			// Survival's room, as the selector shows it to players with the mod
			int kinds = plugin.mode().typeCapacity(target);
			int looks = plugin.morphs().lookLimit(target);
			List<String> room = new ArrayList<>();
			if (kinds >= 0) room.add("Morphs " + data.morphs.size() + "/" + kinds + (data.morphs.size() >= kinds ? " full" : ""));
			if (looks >= 0) room.add("up to " + looks + (looks == 1 ? " look" : " looks") + " of each creature");
			if (!room.isEmpty()) sender.sendMessage(Component.text(" " + String.join(", ", room), NamedTextColor.GRAY));
		}
		sender.sendMessage(entry(MorphRef.self(VariantCapture.skinOf(target)), false, currentId.equals(MorphRef.SELF_ID), sender == target, !(sender instanceof Player viewer) || plugin.tooltips(viewer))
				.append(Component.text(" (your own form)", NamedTextColor.DARK_GRAY)));
		for (Map.Entry<String, List<PlayerMorphData.StoredVariant>> e : data.morphs.entrySet()) {
			for (PlayerMorphData.StoredVariant v : e.getValue()) {
				MorphRef ref = PlayerMorphData.toRef(e.getKey(), v);
				sender.sendMessage(entry(ref, v.favourite, v.id.equals(currentId), sender == target, !(sender instanceof Player viewer) || plugin.tooltips(viewer)));
			}
		}
	}

	private Component entry(MorphRef ref, boolean favourite, boolean current, boolean clickable, boolean tips) {
		Component name = MorphManager.displayName(ref).color(current ? NamedTextColor.GOLD : NamedTextColor.WHITE);
		Component line = Component.text(favourite ? " ★ " : " - ", NamedTextColor.YELLOW).append(name);
		if (!ref.isPlayer() && !ref.snbt().equals("{}") && !ref.snbt().isEmpty()) {
			line = line.append(Component.text(" " + ref.snbt(), NamedTextColor.DARK_GRAY));
		}
		line = line.append(Component.text(" [" + ref.variantId() + "]", NamedTextColor.DARK_GRAY));
		if (clickable) {
			line = line.clickEvent(ClickEvent.runCommand("/morph use " + ref.variantId()));
			if (tips) line = line.hoverEvent(HoverEvent.showText(Component.text("Click to morph"))); // the click works either way; saying so is a tip
		}
		return line;
	}

	private void use(CommandSender sender, String[] args) {
		Player p = self(sender);
		if (p == null || !commandsAllowed(p)) return;
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph use <id|type>");
		plugin.morphs().requestMorph(p, ownedId(p, args[1]));
	}

	/** A morph id the player owns, from an id or a type ("cow" picks their first grown-up cow). */
	private String ownedId(Player p, String arg) {
		if (arg.equalsIgnoreCase("me") || arg.equalsIgnoreCase(p.getName())) return MorphRef.SELF_ID; // your own body
		PlayerMorphData data = plugin.store().get(p.getUniqueId());
		if (data.byId(arg) == null && !arg.equals(MorphRef.SELF_ID)) {
			List<PlayerMorphData.StoredVariant> ofType = data.morphs.get(VariantRules.normaliseType(arg));
			if (ofType == null || ofType.isEmpty()) throw new IllegalArgumentException("You don't have a morph '" + arg + "'. See /morph list.");
			return PlayerMorphData.byTypeName(ofType).id; // a grown-up one when there is one: a baby stored first is not what "cow" means
		}
		return arg;
	}

	/**
	 * Multimorph without the client mod: {@code /morph combine <main> <borrowed>} becomes the main body borrowing from the
	 * second; {@code /morph combine <borrowed>} lends the body you're in a second form; {@code /morph combine off} drops it.
	 */
	private void combine(CommandSender sender, String[] args) {
		Player p = self(sender);
		if (p == null || !commandsAllowed(p)) return;
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph combine <main> <borrowed> | combine <borrowed> | combine off");
		MorphRef body = plugin.morphs().activeForm(p);
		if (args.length == 2) {
			if (body == null || body.isSelf()) {
				// in your own body: your body borrows the form
				if (args[1].equalsIgnoreCase("off")) return;
				plugin.morphs().requestMorph(p, MorphRef.SELF_ID, ownedId(p, args[1]));
				return;
			}
			if (args[1].equalsIgnoreCase("off")) {
				plugin.morphs().requestMorph(p, body.variantId(), null);
				return;
			}
			plugin.morphs().requestMorph(p, body.variantId(), ownedId(p, args[1]));
			return;
		}
		plugin.morphs().requestMorph(p, ownedId(p, args[1]), ownedId(p, args[2]));
	}

	private void fav(CommandSender sender, String[] args) {
		Player p = self(sender);
		if (p == null) return;
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph fav <id>");
		PlayerMorphData.StoredVariant v = plugin.store().get(p.getUniqueId()).storedById(args[1]);
		if (v == null) throw new IllegalArgumentException("No morph with id " + args[1]);
		plugin.morphs().setFavourite(p, args[1], !v.favourite);
		p.sendMessage(Component.text(v.favourite ? "Added to favourites." : "Removed from favourites.", NamedTextColor.GREEN));
	}

	private MorphRef appearanceFrom(String typeArg, String[] args, int snbtIndex) {
		String type = VariantRules.normaliseType(typeArg);
		if (type.equals(MorphRef.PLAYER_TYPE)) throw new IllegalArgumentException("Player morphs can only be unlocked by killing that player.");
		EntityType entityType = Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.fromString(type));
		if (entityType == null || !entityType.isAlive() || !entityType.isSpawnable()) throw new IllegalArgumentException("Unknown or non-living entity type " + type);
		String raw = args.length > snbtIndex ? String.join(" ", java.util.Arrays.copyOfRange(args, snbtIndex, args.length)) : "{}";
		try {
			Snbt.parseCompound(raw);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Bad SNBT: " + e.getMessage());
		}
		return new MorphRef(type, "", plugin.capture().filter(type, raw), null);
	}

	private void grant(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph grant <player> <type> [snbt]");
		Player target = player(args[1]);
		MorphRef added = plugin.morphs().unlock(target, appearanceFrom(args[2], args, 3));
		sender.sendMessage(added == null ? Component.text(target.getName() + " already has that variant.", NamedTextColor.YELLOW)
				: Component.text("Granted ", NamedTextColor.GREEN).append(MorphManager.displayName(added)).append(Component.text(" [" + added.variantId() + "] to " + target.getName())));
	}

	private void revoke(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph revoke <player> <type|id|all>");
		Player target = player(args[1]);
		PlayerMorphData data = plugin.store().get(target.getUniqueId());
		MorphRef current = plugin.morphs().activeForm(target);
		int removed;
		if (args[2].equalsIgnoreCase("all")) {
			removed = data.variantCount();
			data.morphs.clear();
		} else if (data.byId(args[2]) != null) {
			removed = data.remove(args[2]) ? 1 : 0;
		} else {
			removed = data.removeType(VariantRules.normaliseType(args[2]));
		}
		if (current != null && data.byId(current.variantId()) == null && !current.isSelf() && !current.isSelfBody()) plugin.morphs().demorph(target, true);
		plugin.morphs().checkBorrowed(target);
		plugin.store().markDirty(target.getUniqueId());
		plugin.channel().sendCollection(target);
		sender.sendMessage(Component.text("Removed " + removed + " morph(s) from " + target.getName() + ".", NamedTextColor.GREEN));
	}

	private void reset(CommandSender sender, String[] args) {
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph reset <player>");
		Player target = player(args[1]);
		plugin.morphs().demorph(target, true);
		PlayerMorphData data = plugin.store().get(target.getUniqueId());
		data.morphs.clear();
		data.killCounts.clear();
		data.current = null;
		plugin.store().markDirty(target.getUniqueId());
		plugin.channel().sendCollection(target);
		sender.sendMessage(Component.text("Reset " + target.getName() + "'s morph collection.", NamedTextColor.GREEN));
	}

	private void force(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph force <player> <type|id|off> [snbt]");
		Player target = player(args[1]);
		if (args[2].equalsIgnoreCase("off")) {
			plugin.morphs().demorph(target, false);
			return;
		}
		PlayerMorphData data = plugin.store().get(target.getUniqueId());
		MorphRef ref = data.byId(args[2]);
		if (ref == null) {
			MorphRef appearance = appearanceFrom(args[2], args, 3);
			PlayerMorphData.StoredVariant owned = data.find(appearance);
			ref = owned != null ? PlayerMorphData.toRef(appearance.entityType(), owned) : plugin.morphs().unlock(target, appearance);
		}
		if (ref == null) throw new IllegalArgumentException("Could not create that morph.");
		if (plugin.morphs().isTransitioning(target)) throw new IllegalArgumentException(target.getName() + " is mid-transformation; try again in a moment.");
		plugin.morphs().morphTo(target, ref, false);
		sender.sendMessage(Component.text("Morphing " + target.getName() + " into ", NamedTextColor.GREEN).append(MorphManager.displayName(ref)));
	}

	/** Multimorph test: {@code /morph borrow <player> <type|off> [snbt]} gives a morphed player a borrowed form. */
	private void borrow(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph borrow <player> <type|self|off> [snbt]");
		Player target = player(args[1]);
		MorphRef body = plugin.morphs().activeForm(target);
		boolean ownBody = body == null || body.isSelf() || body.isSelfBody();
		if (args[2].equalsIgnoreCase("off")) {
			if (body != null && body.isSelfBody()) plugin.morphs().demorph(target, true); // their own body again
			else plugin.borrowedHeads().setBorrowed(target, null);
			sender.sendMessage(Component.text(target.getName() + " borrows nothing now.", NamedTextColor.GREEN));
			return;
		}
		MorphRef ref;
		if (args[2].equalsIgnoreCase("self") || args[2].equalsIgnoreCase(target.getName())) {
			if (ownBody) throw new IllegalArgumentException(target.getName() + " is in their own body: morph them first.");
			ref = MorphRef.selfBody(VariantCapture.skinOf(target));
		} else {
			// the player's own morph of that kind when they have one, so it is saved and comes back after a relog
			PlayerMorphData data = plugin.store().get(target.getUniqueId());
			ref = data.byId(args[2]);
			if (ref == null) {
				MorphRef appearance = appearanceFrom(args[2], args, 3);
				PlayerMorphData.StoredVariant owned = data.find(appearance);
				ref = owned != null ? PlayerMorphData.toRef(appearance.entityType(), owned) : appearance;
			}
		}
		if (ownBody && (body == null || !body.isSelfBody())) plugin.morphs().lendToOwnBody(target, ref); // their own body borrows it
		else plugin.borrowedHeads().setBorrowed(target, ref);
		sender.sendMessage(Component.text(target.getName() + " borrows from ", NamedTextColor.GREEN).append(MorphManager.displayName(ref)));
	}

	private void info(CommandSender sender, String[] args) {
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph info <player>");
		Player target = player(args[1]);
		ActiveMorph s = plugin.morphs().state(target);
		String version = plugin.channel().clientVersion(target);
		sender.sendMessage(Component.text(target.getName() + ": " + (version == null ? "vanilla client" : "RE:Morph client " + version), NamedTextColor.GOLD));
		sender.sendMessage(Component.text(" form: " + (s == null || s.next == null ? "own body" : s.next.isSelfBody() ? "own body, combined" : s.next.entityType() + " " + s.next.snbt()
				+ (s.overlay.isEmpty() ? "" : " look " + s.overlay))
				+ (s != null && s.isTransitioning() ? " (transforming " + s.time + "/" + s.duration + ")" : ""), NamedTextColor.GRAY));
		if (s != null && s.converted != null) {
			sender.sendMessage(Component.text(" turned into (not saved): " + s.converted.entityType() + " " + s.converted.snbt(), NamedTextColor.GRAY));
		}
		MorphRef lent = plugin.borrowedHeads().borrowed(target);
		if (lent != null) sender.sendMessage(Component.text(" borrows from: " + lent.entityType() + " [" + lent.variantId() + "]", NamedTextColor.GRAY));
		sender.sendMessage(Component.text(" collection: " + plugin.store().get(target.getUniqueId()).variantCount() + " variants", NamedTextColor.GRAY));
		sender.sendMessage(Component.text(" disguised for vanilla viewers: " + plugin.disguises().describe(target), NamedTextColor.GRAY));
		sender.sendMessage(Component.text(" gameplay mode: " + plugin.mode().id() + "; " + survivalSummary(target), NamedTextColor.GRAY));
	}

	private String survivalSummary(Player target) {
		SurvivalMode s = plugin.survival();
		if (!s.isUnlocked(target)) return "biomass locked";
		return "biomass " + SurvivalMode.number(Math.floor(s.data(target).biomass)) + "/" + SurvivalMode.number(s.capacity(target))
				+ (s.canMorph(target) ? ", can morph" : ", can't morph yet");
	}

	/** /morph biomass: progress in chat, for everyone (the menu shows the same). */
	private void biomass(CommandSender sender, Player target) {
		SurvivalMode s = plugin.survival();
		if (!s.active()) sender.sendMessage(Component.text("This server plays Classic Mode; Survival progress is kept but not used.", NamedTextColor.GRAY));
		sender.sendMessage(Component.text(target.getName() + ": " + survivalSummary(target), NamedTextColor.GOLD));
		for (SurvivalConfig.Upgrade u : s.config().upgrades.values()) {
			if (s.unbuiltArm(u.id())) continue; // an arm form or accessory this build doesn't have yet isn't listed
			int level = s.level(target, u.id());
			String line = " " + u.name() + ": " + (u.maxLevel() == 1 ? (level >= 1 ? "yes" : "no")
					: level + "/" + u.maxLevel() + (level > 0 ? " (" + s.formatValue(u, u.value().at(level), plugin.tooltips(target)) + ")" : ""));
			double cost = s.nextCost(target, u);
			if (cost >= 0) line += ", next costs " + SurvivalMode.number(cost);
			sender.sendMessage(Component.text(line, level > 0 ? NamedTextColor.WHITE : NamedTextColor.GRAY));
		}
	}

	/** The upgrade ids to complete for a player: every one but the arm forms and accessories this build doesn't have (SurvivalMode.unbuiltArm). */
	private List<String> builtUpgradeIds() {
		return upgradeIds(false);
	}

	/**
	 * The upgrade ids to complete; {@code all} adds the unbuilt arm upgrades too, for an admin's {@code /morph survival <p> level|buy},
	 * since SurvivalMode.unbuiltArm says an admin can still set their level.
	 */
	private List<String> upgradeIds(boolean all) {
		SurvivalMode s = plugin.survival();
		List<String> ids = new ArrayList<>();
		for (String id : s.config().upgrades.keySet()) {
			if (all || !s.unbuiltArm(id)) ids.add(id);
		}
		return ids;
	}

	/**
	 * {@code /morph tooltips [on|off]}: the Tooltips setting for players without the client mod (a player with the mod, at a version that sends
	 * its own choice, has it in the mod's settings, where the screen and this command would otherwise disagree). On shows the explanation lines
	 * (how to use a thing, which key, what it does in detail) in the descriptions the server builds, off leaves them out; names, levels, state,
	 * costs and rates always stay. No word reads the setting back.
	 */
	private void tooltips(CommandSender sender, String[] args) {
		Player p = self(sender);
		if (!p.hasPermission("morphreborn.tooltips")) throw new IllegalArgumentException("You don't have permission to do that.");
		boolean now = plugin.tooltips(p);
		if (TooltipPrefs.clientKnows(plugin.channel().clientVersion(p))) {
			p.sendMessage(Component.text("Tooltips are " + (now ? "on" : "off") + ". With the RE:Morph client mod you change that in its settings (/morphclient).", NamedTextColor.GRAY));
			return;
		}
		if (args.length < 2) {
			p.sendMessage(Component.text("Tooltips are " + (now ? "on" : "off") + ": /morph tooltips " + (now ? "off" : "on") + " to switch (also a button in /morph upgrades).",
					NamedTextColor.GRAY));
			return;
		}
		Boolean want = TooltipPrefs.parse(args[1]);
		if (want == null) throw new IllegalArgumentException("Use /morph tooltips on or /morph tooltips off.");
		plugin.setTooltips(p, want);
		p.sendMessage(Component.text(want ? "Tooltips on: descriptions explain how things work." : "Tooltips off: descriptions show only names, levels, costs and rates.",
				want ? NamedTextColor.GREEN : NamedTextColor.GRAY));
	}

	/**
	 * {@code /morph passive [id] [on|off]}: the biomass passives, what each is doing; with an id, switch it (no word
	 * toggles). The same switches are on the chest menu's Passives page and, for a modded client, on the upgrade tree's nodes (C_PASSIVE);
	 * all three go through Passives.set.
	 */
	private void passive(CommandSender sender, String[] args) {
		Player p = self(sender);
		SurvivalMode s = plugin.survival();
		if (!s.active()) throw new IllegalArgumentException("This server plays Classic Mode: there are no passives.");
		dev.morphreborn.paper.survival.Passives ps = plugin.passives();
		if (args.length < 2) {
			p.sendMessage(Component.text("Biomass passives", NamedTextColor.GOLD));
			for (Map.Entry<String, String> e : ps.describe(p).entrySet()) {
				p.sendMessage(Component.text("  " + s.displayName(e.getKey()) + " (" + e.getKey().replace("passive_", "") + "): ", NamedTextColor.GRAY)
						.append(Component.text(e.getValue(), e.getValue().startsWith("on") ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)));
			}
			if (plugin.tooltips(p)) p.sendMessage(Component.text("  /morph passive <name> [on|off]", NamedTextColor.DARK_GRAY)); // how to switch one is a tip
			return;
		}
		String id = args[1].toLowerCase(Locale.ROOT);
		if (!id.startsWith("passive_")) id = "passive_" + id;
		if (!dev.morphreborn.paper.survival.Passives.ALL.contains(id)) throw new IllegalArgumentException("Unknown passive " + args[1] + ".");
		boolean want = args.length > 2 ? args[2].equalsIgnoreCase("on") : !ps.on(p, id);
		if (!ps.set(p, id, want)) throw new IllegalArgumentException("You haven't grown " + s.displayName(id) + " yet: /morph upgrade " + id);
		p.sendMessage(Component.text(s.displayName(id) + " " + (want ? "on." : "off."), want ? NamedTextColor.GREEN : NamedTextColor.GRAY));
	}

	/** /morph upgrade <id>: buy without the menu. */
	private void upgrade(CommandSender sender, String[] args) {
		Player p = self(sender);
		SurvivalMode s = plugin.survival();
		if (!s.active()) throw new IllegalArgumentException("This server plays Classic Mode: there are no upgrades.");
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph upgrade <" + String.join("|", builtUpgradeIds()) + ">");
		String id = args[1].toLowerCase(Locale.ROOT);
		SurvivalConfig.Upgrade u = s.config().upgrades.get(id);
		Component result = switch (s.buy(p, id)) {
			case BOUGHT -> Component.text("Bought " + s.displayName(id) + (u != null && u.maxLevel() > 1 ? " level " + s.level(p, id) : "") + ".", NamedTextColor.GREEN);
			case BIOMASS_LOCKED -> Component.text("Unlock the Biomass ability first: have Wither and Regeneration at the same time.", NamedTextColor.RED);
			case MAXED -> Component.text(s.displayName(id) + " is already fully upgraded.", NamedTextColor.YELLOW);
			case MISSING_REQUIREMENT -> Component.text("Needs " + s.missingRequirement(p, u) + " first.", NamedTextColor.RED);
			case TOO_EXPENSIVE -> Component.text("Not enough biomass: it costs " + SurvivalMode.number(s.nextCost(p, u)) + ".", NamedTextColor.RED);
			case UNKNOWN -> Component.text("Unknown upgrade " + id + ". Upgrades: " + String.join(", ", builtUpgradeIds()), NamedTextColor.RED);
		};
		p.sendMessage(result);
	}

	/** Admin: /morph survival <player> <action> ... Works in Classic too, to prepare progress before switching. */
	private void survival(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph survival <player> " + String.join("|", SURVIVAL_ACTIONS));
		Player target = player(args[1]);
		SurvivalMode s = plugin.survival();
		String action = args[2].toLowerCase(Locale.ROOT);
		boolean capped = false;
		switch (action) {
			case "info" -> {
				biomass(sender, target);
				return;
			}
			case "unlock" -> {
				if (s.active()) {
					s.unlock(target);
				} else {
					s.data(target).unlocked = true;
					plugin.store().markDirty(target.getUniqueId());
				}
			}
			case "lock" -> {
				s.data(target).unlocked = false;
				plugin.store().markDirty(target.getUniqueId());
				s.sync(target);
			}
			case "give", "take", "set" -> {
				if (args.length < 4) throw new IllegalArgumentException("Usage: /morph survival <player> " + action + " <biomass>");
				double amount;
				try {
					amount = Double.parseDouble(args[3]);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Not a number: " + args[3]);
				}
				double now = s.data(target).biomass;
				double next = action.equals("set") ? amount : action.equals("give") ? now + amount : now - amount;
				// give stops at the capacity like a kill does (5000 on a 100 bar read 5,031 / 100); set is how an admin overfills on purpose
				if (action.equals("give") && next > s.capacity(target)) {
					next = Math.max(now, s.capacity(target));
					capped = true;
				}
				s.setBiomass(target, next);
			}
			case "level" -> {
				if (args.length < 5) throw new IllegalArgumentException("Usage: /morph survival <player> level <upgrade> <level>");
				int level;
				try {
					level = Integer.parseInt(args[4]);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Not a number: " + args[4]);
				}
				s.setLevel(target, args[3].toLowerCase(Locale.ROOT), level);
			}
			case "buy" -> {
				// Buys for the player, by the normal rules and from their biomass (helping a player, or testing)
				if (args.length < 4) throw new IllegalArgumentException("Usage: /morph survival <player> buy <upgrade>");
				String id = args[3].toLowerCase(Locale.ROOT);
				SurvivalMode.Purchase result = s.buy(target, id);
				if (result != SurvivalMode.Purchase.BOUGHT) throw new IllegalArgumentException("Not bought: " + result.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
			}
			case "reset" -> s.reset(target);
			default -> throw new IllegalArgumentException("Usage: /morph survival <player> " + String.join("|", SURVIVAL_ACTIONS));
		}
		sender.sendMessage(Component.text("Done" + (capped ? " (stopped at their capacity; use set to go past it)" : "") + ". " + target.getName() + ": " + survivalSummary(target),
				NamedTextColor.GREEN));
	}

	/** Admin: set the block an enderman-morphed player is holding (none to clear). */
	private void carry(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph carry <player> <block|none>");
		Player target = player(args[1]);
		MorphRef form = plugin.morphs().activeForm(target);
		if (form == null || !MorphManager.ENDERMAN.equals(form.entityType())) throw new IllegalArgumentException(target.getName() + " is not morphed into an enderman.");
		PlayerMorphData data = plugin.store().get(target.getUniqueId());
		if (args[2].equalsIgnoreCase("none")) {
			data.carriedBlock = null;
		} else {
			try {
				data.carriedBlock = org.bukkit.Bukkit.createBlockData(args[2].contains(":") ? args[2] : "minecraft:" + args[2]).getAsString();
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Unknown block " + args[2]);
			}
		}
		plugin.store().markDirty(target.getUniqueId());
		plugin.morphs().setOverlay(target, data.carriedBlock == null ? "" : MorphManager.carriedBlockOverlay(data.carriedBlock));
		sender.sendMessage(Component.text(target.getName() + " now carries " + (data.carriedBlock == null ? "nothing" : data.carriedBlock), NamedTextColor.GREEN));
	}

	/** In-game switches for config.yml, saved to the file: world effects and villager trading. */
	private static final Map<String, List<String>> SETTINGS = Map.of(
			"explosion-block-damage", List.of("on", "off", "gamerule"),
			"fire", List.of("on", "off", "gamerule"),
			"creeper-explosion-kills-user", List.of("true", "false"),
			"enderman-pickup", List.of("vanilla", "any"),
			"bee-sting-kills-user", List.of("true", "false"),
			"villager-trading", List.of("true", "false"),
			"freeze-trading", List.of("true", "false"));

	/** Where each setting lives in config.yml. */
	private static String settingPath(String name) {
		return switch (name) {
			case "villager-trading" -> "trading.villagers";
			case "freeze-trading" -> "trading.freeze-villager";
			default -> "world-effects." + name;
		};
	}

	/** Admin: show or change a world-effects setting. */
	private void setting(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(Component.text("Settings (change with /morph setting <name> <value>):", NamedTextColor.GOLD));
			for (String name : SETTINGS.keySet().stream().sorted().toList()) {
				sender.sendMessage(Component.text(" " + name + ": " + settingValue(name) + "  " + SETTINGS.get(name), NamedTextColor.GRAY));
			}
			return;
		}
		String name = args[1].toLowerCase(Locale.ROOT);
		List<String> allowed = SETTINGS.get(name);
		if (allowed == null) throw new IllegalArgumentException("Unknown setting '" + args[1] + "'. Settings: " + String.join(", ", SETTINGS.keySet().stream().sorted().toList()));
		if (args.length < 3) {
			sender.sendMessage(Component.text(name + " is " + settingValue(name), NamedTextColor.GOLD));
			return;
		}
		String value = args[2].toLowerCase(Locale.ROOT);
		if (!allowed.contains(value)) throw new IllegalArgumentException(name + " can be " + String.join(", ", allowed) + ".");
		plugin.getConfig().set(settingPath(name), allowed.contains("true") ? Boolean.parseBoolean(value) : value);
		if (plugin.getConfig() instanceof org.bukkit.configuration.file.YamlConfiguration yaml) yaml.options().width(1000); // no wrapped lines
		plugin.saveConfig();
		plugin.reloadSettings();
		sender.sendMessage(Component.text(name + " set to " + value + ".", NamedTextColor.GREEN));
	}

	private String settingValue(String name) {
		PluginSettings s = plugin.settings();
		return switch (name) {
			case "explosion-block-damage" -> s.explosionBlockDamage.name().toLowerCase(Locale.ROOT);
			case "fire" -> s.abilityFire.name().toLowerCase(Locale.ROOT);
			case "creeper-explosion-kills-user" -> String.valueOf(s.creeperExplosionKillsUser);
			case "enderman-pickup" -> s.endermanPickupAny ? "any" : "vanilla";
			case "bee-sting-kills-user" -> String.valueOf(s.beeStingKillsUser);
			case "villager-trading" -> String.valueOf(s.tradingVillagers);
			case "freeze-trading" -> String.valueOf(s.tradingFreeze);
			default -> "?";
		};
	}

	private void clients(CommandSender sender) {
		List<String> lines = new ArrayList<>();
		for (Player p : Bukkit.getOnlinePlayers()) {
			String v = plugin.channel().clientVersion(p);
			lines.add(p.getName() + ": " + (v == null ? "vanilla" : "mod " + v));
		}
		sender.sendMessage(Component.text(String.join(", ", lines), NamedTextColor.GRAY));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			Stream<String> subs = PLAYER_SUBS.stream().filter(s -> !s.equals("arm") || plugin.arms().enabled())
					.filter(s -> !s.equals("tooltips") || sender.hasPermission("morphreborn.tooltips"));
			if (sender.hasPermission("morphreborn.admin")) subs = Stream.concat(subs, ADMIN_SUBS.stream());
			return filter(subs.toList(), args[0]);
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (sub.equals("tooltips")) {
			return args.length == 2 && sender.hasPermission("morphreborn.tooltips") ? filter(TooltipPrefs.WORDS, args[1]) : List.of();
		}
		if (sub.equals("arm")) {
			// owned forms and the keywords for players, player names and their actions for admins: the arm code knows which
			return filter(plugin.arms().tabComplete(sender, java.util.Arrays.copyOfRange(args, 1, args.length)), args[args.length - 1]);
		}
		if (sub.equals("survival") && sender.hasPermission("morphreborn.admin")) {
			if (args.length == 2) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
			if (args.length == 3) return filter(SURVIVAL_ACTIONS, args[2]);
			if (args.length == 4 && (args[2].equalsIgnoreCase("level") || args[2].equalsIgnoreCase("buy"))) return filter(upgradeIds(true), args[3]);
			return List.of();
		}
		if (args.length == 2 && sub.equals("upgrade")) return filter(builtUpgradeIds(), args[1]);
		if (args.length == 2 && List.of("grant", "revoke", "reset", "force", "borrow", "info", "list", "carry", "ability", "biomass", "upgrades").contains(sub)) {
			return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
		}
		if (sub.equals("cost") && sender.hasPermission("morphreborn.admin")) {
			if (args.length == 2) return filter(List.of("on", "off", "rate", "minimum", "price", "gain", "arm"), args[1]);
			if (args[1].equalsIgnoreCase("arm")) {
				if (args.length == 3) return filter(ARM_COST_ACTIONS, args[2]);
				if (args.length == 4 && args[2].equalsIgnoreCase("special")) return filter(armSpecialKeys(), args[3]);
				return List.of();
			}
			if (args.length == 3 && args[1].equalsIgnoreCase("price")) {
				List<String> types = new ArrayList<>();
				for (EntityType t : Registry.ENTITY_TYPE) if (t.isAlive() && t.isSpawnable()) types.add(t.getKey().getKey());
				return filter(types, args[2]);
			}
			if (args.length == 4 && args[1].equalsIgnoreCase("price")) return filter(List.of("formula"), args[3]);
			return List.of();
		}
		if ((sub.equals("frenzy") || sub.equals("rage")) && sender.hasPermission("morphreborn.admin")) {
			if (args.length == 2) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
			if (args.length == 3) return filter(List.of("go", "fill"), args[2]);
			if (args.length == 4 && args[2].equalsIgnoreCase("go")) {
				List<String> levels = new ArrayList<>();
				for (int i = 1; i <= plugin.rage().levels(); i++) levels.add(String.valueOf(i));
				return filter(levels, args[3]);
			}
			return List.of();
		}
		if (sub.equals("mode") && sender.hasPermission("morphreborn.admin")) {
			return args.length == 2 ? filter(List.of("classic", "survival"), args[1]) : List.of();
		}
		if (sub.equals("setting") && sender.hasPermission("morphreborn.admin")) {
			if (args.length == 2) return filter(List.copyOf(SETTINGS.keySet()), args[1]);
			if (args.length == 3) return filter(SETTINGS.getOrDefault(args[1].toLowerCase(Locale.ROOT), List.of()), args[2]);
			return List.of();
		}
		if (((args.length == 2 && (sub.equals("use") || sub.equals("fav") || sub.equals("combine"))) || (args.length == 3 && sub.equals("combine")))
				&& sender instanceof Player p) {
			List<String> ids = new ArrayList<>();
			plugin.store().get(p.getUniqueId()).morphs.forEach((type, list) -> {
				ids.add(type.replace("minecraft:", ""));
				list.forEach(v -> ids.add(v.id));
			});
			if (sub.equals("combine")) ids.add("self");
			if (sub.equals("combine") && args.length == 2) ids.add("off");
			return filter(ids, args[args.length - 1]);
		}
		if (args.length == 3 && (sub.equals("grant") || sub.equals("force") || sub.equals("revoke") || sub.equals("borrow"))) {
			List<String> types = new ArrayList<>();
			for (EntityType t : Registry.ENTITY_TYPE) {
				if (t.isAlive() && t.isSpawnable()) types.add(t.getKey().getKey());
			}
			if (sub.equals("revoke")) types.add("all");
			if (sub.equals("force")) types.add("off");
			return filter(types, args[2]);
		}
		return List.of();
	}

	private static List<String> filter(List<String> options, String prefix) {
		String p = prefix.toLowerCase(Locale.ROOT);
		return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
	}
}
