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
import dev.morphreborn.paper.variant.VariantCapture;
import dev.morphreborn.paper.variant.VariantRules;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** /morph: player selection for unmodded clients plus admin management. */
public final class MorphCommand implements TabExecutor {
	private static final List<String> PLAYER_SUBS = List.of("list", "use", "off", "ability", "fav", "upgrades", "upgrade", "biomass");
	private static final List<String> ADMIN_SUBS = List.of("grant", "revoke", "reset", "force", "info", "clients", "carry", "setting", "reload", "survival", "mode");
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
				case "upgrades" -> plugin.openUpgrades(self(sender));
				case "upgrade" -> upgrade(sender, args);
				case "biomass" -> biomass(sender, args.length > 1 && sender.hasPermission("morphreborn.admin") ? player(args[1]) : self(sender));
				case "survival" -> survival(sender, args);
				case "grant" -> grant(sender, args);
				case "revoke" -> revoke(sender, args);
				case "reset" -> reset(sender, args);
				case "force" -> force(sender, args);
				case "info" -> info(sender, args);
				case "clients" -> clients(sender);
				case "carry" -> carry(sender, args);
				case "setting" -> setting(sender, args);
				case "mode" -> mode(sender, args);
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
		if (plugin.survival().active()) sender.sendMessage(Component.text("/" + label + " biomass | upgrades | upgrade <id>", NamedTextColor.GRAY));
		if (sender.hasPermission("morphreborn.admin")) {
			sender.sendMessage(Component.text("/" + label + " grant <player> <type> [snbt] | revoke <player> <type|id|all> | reset <player>", NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " force <player> <type|id|off> [snbt] | info <player> | clients | reload", NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " carry <player> <block|none> | setting [name] [value] | mode [classic|survival]", NamedTextColor.GRAY));
			sender.sendMessage(Component.text("/" + label + " survival <player> info | unlock | lock | give|take|set <biomass> | level <upgrade> <n> | buy <upgrade> | reset",
					NamedTextColor.GRAY));
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
		Component notice = Component.text(wanted.equals("survival")
				? "The server switched to Survival Mode: morphing is now earned with biomass. /morph upgrades shows your progress."
				: "The server switched to Classic Mode: kill a creature to unlock it as a morph.", NamedTextColor.GOLD);
		for (Player p : Bukkit.getOnlinePlayers()) if (p != sender) p.sendMessage(notice);
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

	private void list(CommandSender sender, Player target) {
		PlayerMorphData data = plugin.store().get(target.getUniqueId());
		ActiveMorph state = plugin.morphs().state(target);
		String currentId = state != null && state.next != null ? state.next.variantId() : MorphRef.SELF_ID;
		sender.sendMessage(Component.text(target.getName() + "'s morphs (" + data.variantCount() + "):", NamedTextColor.GOLD));
		sender.sendMessage(entry(MorphRef.self(VariantCapture.skinOf(target)), false, currentId.equals(MorphRef.SELF_ID), sender == target)
				.append(Component.text(" (your own form)", NamedTextColor.DARK_GRAY)));
		for (Map.Entry<String, List<PlayerMorphData.StoredVariant>> e : data.morphs.entrySet()) {
			for (PlayerMorphData.StoredVariant v : e.getValue()) {
				MorphRef ref = PlayerMorphData.toRef(e.getKey(), v);
				sender.sendMessage(entry(ref, v.favourite, v.id.equals(currentId), sender == target));
			}
		}
	}

	private Component entry(MorphRef ref, boolean favourite, boolean current, boolean clickable) {
		Component name = MorphManager.displayName(ref).color(current ? NamedTextColor.GOLD : NamedTextColor.WHITE);
		Component line = Component.text(favourite ? " ★ " : " - ", NamedTextColor.YELLOW).append(name);
		if (!ref.isPlayer() && !ref.snbt().equals("{}") && !ref.snbt().isEmpty()) {
			line = line.append(Component.text(" " + ref.snbt(), NamedTextColor.DARK_GRAY));
		}
		line = line.append(Component.text(" [" + ref.variantId() + "]", NamedTextColor.DARK_GRAY));
		if (clickable) {
			line = line.clickEvent(ClickEvent.runCommand("/morph use " + ref.variantId())).hoverEvent(HoverEvent.showText(Component.text("Click to morph")));
		}
		return line;
	}

	private void use(CommandSender sender, String[] args) {
		Player p = self(sender);
		if (p == null || !commandsAllowed(p)) return;
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph use <id|type>");
		String arg = args[1];
		PlayerMorphData data = plugin.store().get(p.getUniqueId());
		if (data.byId(arg) == null && !arg.equals(MorphRef.SELF_ID)) {
			List<PlayerMorphData.StoredVariant> ofType = data.morphs.get(VariantRules.normaliseType(arg));
			if (ofType == null || ofType.isEmpty()) throw new IllegalArgumentException("You don't have a morph '" + arg + "'. See /morph list.");
			arg = ofType.getFirst().id;
		}
		plugin.morphs().requestMorph(p, arg);
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
		if (current != null && data.byId(current.variantId()) == null && !current.isSelf()) plugin.morphs().demorph(target, true);
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

	private void info(CommandSender sender, String[] args) {
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph info <player>");
		Player target = player(args[1]);
		ActiveMorph s = plugin.morphs().state(target);
		String version = plugin.channel().clientVersion(target);
		sender.sendMessage(Component.text(target.getName() + ": " + (version == null ? "vanilla client" : "RE:Morph client " + version), NamedTextColor.GOLD));
		sender.sendMessage(Component.text(" form: " + (s == null || s.next == null ? "own body" : s.next.entityType() + " " + s.next.snbt()
				+ (s.overlay.isEmpty() ? "" : " look " + s.overlay))
				+ (s != null && s.isTransitioning() ? " (transforming " + s.time + "/" + s.duration + ")" : ""), NamedTextColor.GRAY));
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
			int level = s.level(target, u.id());
			String line = " " + u.name() + ": " + (u.maxLevel() == 1 ? (level >= 1 ? "yes" : "no")
					: level + "/" + u.maxLevel() + (level > 0 ? " (" + s.formatValue(u, u.value().at(level)) + ")" : ""));
			double cost = s.nextCost(target, u);
			if (cost >= 0) line += ", next costs " + SurvivalMode.number(cost);
			sender.sendMessage(Component.text(line, level > 0 ? NamedTextColor.WHITE : NamedTextColor.GRAY));
		}
	}

	/** /morph upgrade <id>: buy without the menu. */
	private void upgrade(CommandSender sender, String[] args) {
		Player p = self(sender);
		SurvivalMode s = plugin.survival();
		if (!s.active()) throw new IllegalArgumentException("This server plays Classic Mode: there are no upgrades.");
		if (args.length < 2) throw new IllegalArgumentException("Usage: /morph upgrade <" + String.join("|", s.config().upgrades.keySet()) + ">");
		String id = args[1].toLowerCase(Locale.ROOT);
		SurvivalConfig.Upgrade u = s.config().upgrades.get(id);
		Component result = switch (s.buy(p, id)) {
			case BOUGHT -> Component.text("Bought " + s.displayName(id) + (u != null && u.maxLevel() > 1 ? " level " + s.level(p, id) : "") + ".", NamedTextColor.GREEN);
			case BIOMASS_LOCKED -> Component.text("Unlock the Biomass ability first: have Wither and Regeneration at the same time.", NamedTextColor.RED);
			case MAXED -> Component.text(s.displayName(id) + " is already fully upgraded.", NamedTextColor.YELLOW);
			case MISSING_REQUIREMENT -> Component.text("Needs " + s.missingRequirement(p, u) + " first.", NamedTextColor.RED);
			case TOO_EXPENSIVE -> Component.text("Not enough biomass: it costs " + SurvivalMode.number(s.nextCost(p, u)) + ".", NamedTextColor.RED);
			case UNKNOWN -> Component.text("Unknown upgrade " + id + ". Upgrades: " + String.join(", ", s.config().upgrades.keySet()), NamedTextColor.RED);
		};
		p.sendMessage(result);
	}

	/** Admin: /morph survival <player> <action> ... Works in Classic too, to prepare progress before switching. */
	private void survival(CommandSender sender, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("Usage: /morph survival <player> " + String.join("|", SURVIVAL_ACTIONS));
		Player target = player(args[1]);
		SurvivalMode s = plugin.survival();
		String action = args[2].toLowerCase(Locale.ROOT);
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
				s.setBiomass(target, action.equals("set") ? amount : action.equals("give") ? now + amount : now - amount);
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
		sender.sendMessage(Component.text("Done. " + target.getName() + ": " + survivalSummary(target), NamedTextColor.GREEN));
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

	/** In-game switches for config.yml's world-effects section, saved to the file. */
	private static final Map<String, List<String>> SETTINGS = Map.of(
			"explosion-block-damage", List.of("on", "off", "gamerule"),
			"fire", List.of("on", "off", "gamerule"),
			"creeper-explosion-kills-user", List.of("true", "false"),
			"enderman-pickup", List.of("vanilla", "any"),
			"bee-sting-kills-user", List.of("true", "false"));

	/** Admin: show or change a world-effects setting. */
	private void setting(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage(Component.text("World effects (change with /morph setting <name> <value>):", NamedTextColor.GOLD));
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
		plugin.getConfig().set("world-effects." + name, allowed.contains("true") ? Boolean.parseBoolean(value) : value);
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
			Stream<String> subs = PLAYER_SUBS.stream();
			if (sender.hasPermission("morphreborn.admin")) subs = Stream.concat(subs, ADMIN_SUBS.stream());
			return filter(subs.toList(), args[0]);
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (sub.equals("survival") && sender.hasPermission("morphreborn.admin")) {
			if (args.length == 2) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
			if (args.length == 3) return filter(SURVIVAL_ACTIONS, args[2]);
			if (args.length == 4 && (args[2].equalsIgnoreCase("level") || args[2].equalsIgnoreCase("buy"))) return filter(List.copyOf(plugin.survival().config().upgrades.keySet()), args[3]);
			return List.of();
		}
		if (args.length == 2 && sub.equals("upgrade")) return filter(List.copyOf(plugin.survival().config().upgrades.keySet()), args[1]);
		if (args.length == 2 && List.of("grant", "revoke", "reset", "force", "info", "list", "carry", "ability", "biomass").contains(sub)) {
			return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
		}
		if (sub.equals("mode") && sender.hasPermission("morphreborn.admin")) {
			return args.length == 2 ? filter(List.of("classic", "survival"), args[1]) : List.of();
		}
		if (sub.equals("setting") && sender.hasPermission("morphreborn.admin")) {
			if (args.length == 2) return filter(List.copyOf(SETTINGS.keySet()), args[1]);
			if (args.length == 3) return filter(SETTINGS.getOrDefault(args[1].toLowerCase(Locale.ROOT), List.of()), args[2]);
			return List.of();
		}
		if ((args.length == 2 && (sub.equals("use") || sub.equals("fav"))) && sender instanceof Player p) {
			List<String> ids = new ArrayList<>();
			plugin.store().get(p.getUniqueId()).morphs.forEach((type, list) -> {
				ids.add(type.replace("minecraft:", ""));
				list.forEach(v -> ids.add(v.id));
			});
			return filter(ids, args[1]);
		}
		if (args.length == 3 && (sub.equals("grant") || sub.equals("force") || sub.equals("revoke"))) {
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
