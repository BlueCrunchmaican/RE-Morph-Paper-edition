package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.arm.api.ArsenalMsg;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * /morph arm (spec 3.14): arm forms by command. Players without the client mod have no J wheel, so this is their only
 * way to form an arm, put it away, switch an accessory (the lantern, shield, spyglass, flint) on or off or fire a special; with the mod it does the same as the keys.
 * Admins (morphreborn.arm.admin) also drive another player's arm with it, which is how arms are tested without real
 * input: forced forms at any level, free of cost, breaks and hits made with the arm, and what the arm is doing now.
 * <p>
 * MorphCommand hands over the words after "arm". The first is a form or accessory id or a keyword (off, special, brightness) for
 * the sender's own arm; anything else is a player name, for the admin commands. Errors are thrown as
 * IllegalArgumentException, which MorphCommand shows in red. {@link #command} returns false when arms are off, and
 * MorphCommand then says so.
 */
final class ArmCommand {
	static final String ADMIN = "morphreborn.arm.admin";
	/** The words after "arm" that are about the sender's own arm and aren't form ids. */
	private static final List<String> KEYWORDS = List.of("off", "special", "brightness", "flare");
	/** What an admin may do to another player's arm, besides naming a form. */
	private static final List<String> ADMIN_ACTIONS = List.of("off", "lantern", "shield", "spyglass", "flint", "special", "brightness", "info", "recover", "break", "hit",
			"shoot");
	private static final String ADMIN_USAGE = "/morph arm <player> <form|off [all]|lantern|shield|spyglass|flint> [level] [free] | special | brightness | info | recover"
			+ " | break <x> <y> <z> | hit <target> | shoot [power]";
	/** /morph arm <player> hit acts on at most this many creatures at once. */
	private static final int MAX_HITS = 32;

	private final ArmManager arms;

	ArmCommand(ArmManager arms) {
		this.arms = arms;
	}

	boolean command(CommandSender sender, String[] args) {
		if (args.length == 0) {
			if (!arms.enabled()) return false;
			list(sender, self(sender));
			return true;
		}
		String first = args[0].toLowerCase(Locale.ROOT);
		if (ArmForm.of(first) != null || KEYWORDS.contains(first)) {
			if (!arms.enabled()) return false;
			own(sender, self(sender), first, args);
			return true;
		}
		return admin(sender, args);
	}

	// ------------------------------------------------------------------ the sender's own arm

	private void own(CommandSender sender, Player p, String what, String[] args) {
		switch (what) {
			// off puts the arm away and frees the held slot; off all frees every slot (2026-11-16)
			case "off" -> fail(args.length > 1 && args[1].equalsIgnoreCase("all") ? arms.unbindAll(p, ArmProtocol.WHY_CHOSEN) : arms.retractChosen(p));
			case "special" -> arms.special(p, p.isSneaking(), sender);
			case "brightness", "flare" -> arms.stepBrightness(p, sender); // "flare": its old name, before 8.0.0
			default -> {
				ArmForm f = ArmForm.of(what);
				if (f != null && !f.tool()) {
					if (f == ArmForm.LANTERN && args.length > 1 && args[1].equalsIgnoreCase("bright")) {
						if (args.length < 3) throw new IllegalArgumentException("/morph arm lantern bright <1-5>");
						int n;
						try {
							n = Integer.parseInt(args[2]);
						} catch (NumberFormatException e) {
							throw new IllegalArgumentException("/morph arm lantern bright <1-5>");
						}
						sender.sendMessage(gray(arms.setLanternBrightness(p, n)));
					} else {
						accessory(p, f.id(), args.length > 1 ? args[1] : "", false, 0, false, sender);
					}
				} else {
					fail(arms.form(p, what, ArmManager.Cause.CHOSEN, -1, 0, false));
				}
			}
		}
	}

	/**
	 * {@code /morph arm <accessory> [on|off]}: no word toggles it. An admin's adds a level and {@code free}. (The lantern's
	 * {@code bright N} is handled before this.)
	 */
	private void accessory(Player p, String id, String word, boolean admin, int level, boolean free, CommandSender sender) {
		String w = word.toLowerCase(Locale.ROOT);
		String usage = admin ? ADMIN_USAGE : "/morph arm " + id + " [on|off]";
		boolean on = switch (w) {
			case "on" -> true;
			case "off" -> false;
			case "" -> !arms.isOn(p, id);
			default -> throw new IllegalArgumentException(usage);
		};
		boolean lantern = id.equals(ArmForm.LANTERN.id());
		if (on) {
			fail(arms.switchOn(p, id, admin, level, free));
			if (admin) sender.sendMessage(gray(p.getName() + "'s " + id + " is " + (lantern ? "lit" : "on") + (free ? ", free." : ".")));
			return;
		}
		if (!arms.isOn(p, id)) {
			throw new IllegalArgumentException(admin ? p.getName() + "'s " + id + " isn't " + (lantern ? "lit." : "on.") : "Your " + id + " isn't " + (lantern ? "lit." : "on."));
		}
		arms.switchOff(p, id, admin ? ArmProtocol.WHY_ADMIN : ArmProtocol.WHY_CHOSEN);
		if (admin) sender.sendMessage(gray(p.getName() + "'s " + id + " is " + (lantern ? "out." : "off.")));
	}

	/**
	 * {@code /morph arm}: the forms this server has, the player's level of each with its special's cost and cooldown,
	 * what an arm costs to use, and what is formed now.
	 */
	private void list(CommandSender sender, Player p) {
		ArsenalMsg a = arms.arsenal(p);
		ArmConfig config = arms.config();
		// the Tooltips setting: the key hints, the usage line and what happens at 0 biomass are explanation; names, levels, costs and rates stay
		boolean tips = arms.plugin().tooltips(p);
		sender.sendMessage(Component.text("Arm forms", NamedTextColor.GOLD)
				.append(gray(a.free() ? "  (free here)" : "  (" + ArmItemSpecs.biomass(arms.balanceMilli(p, 0)) + " biomass)")));
		if (!a.usable()) sender.sendMessage(Component.text(a.whyNot(), NamedTextColor.RED));
		if (a.entries().isEmpty()) sender.sendMessage(gray("  This server has no arm forms in its build."));
		for (ArsenalMsg.Entry e : a.entries()) {
			if (e.level() < 1) {
				sender.sendMessage(Component.text("  " + e.name() + ": ", NamedTextColor.DARK_GRAY).append(gray("not grown yet")));
				continue;
			}
			String special = e.specialName().isEmpty() ? ""
					: " · " + (tips ? "F: " : "") + e.specialName() + (e.specialCostMilli() > 0 ? " · " + ArmItemSpecs.biomass(e.specialCostMilli()) + " biomass" : "")
							+ (e.cooldownLeftTicks() > 0 ? " (ready in " + seconds(e.cooldownLeftTicks()) + ")" : "");
			int fortune = config.fortune(e.id(), e.level()), efficiency = config.efficiency(e.id(), e.level());
			sender.sendMessage(Component.text("  " + e.name() + " ", NamedTextColor.RED).append(gray("(" + e.id() + "): level " + e.level() + ", "
					+ ArmTiers.tierName(e.level()) + " strength" + (fortune > 0 ? ", Fortune " + ArmItemSpecs.roman(fortune) : "") + (efficiency > 0 ? ", Efficiency " + ArmItemSpecs.roman(efficiency) : "")
					+ special)));
		}
		for (ArsenalMsg.AccessoryEntry acc : a.accessories()) {
			boolean lantern = acc.id().equals(ArmForm.LANTERN.id());
			String line = acc.level() < 1 ? "not grown yet"
					: "level " + acc.level() + (acc.on() ? (lantern ? ", lit" : ", on") : (lantern ? ", out" : ", off"))
							+ (lantern && tips ? " · brightness: Sneak+F with no tool out, or /morph arm lantern bright 1-5" : "");
			sender.sendMessage(Component.text("  " + ArmTiers.displayName(acc.id()) + ": ", NamedTextColor.RED).append(gray(line)));
		}
		if (!a.free()) {
			List<String> drains = new ArrayList<>();
			for (ArsenalMsg.AccessoryEntry acc : a.accessories()) {
				if (acc.level() > 0) drains.add(acc.id() + " " + ArmItemSpecs.biomass(arms.config().costs().accessoryDrainPerMinute(acc.id())));
			}
			sender.sendMessage(gray("  Each block " + ArmItemSpecs.biomass(a.perBlockMilli()) + ", each hit " + ArmItemSpecs.biomass(a.perHitMilli())
					+ "; a formed arm drains " + ArmItemSpecs.biomass(a.drainMilliPerMinute()) + " a minute"
					+ (drains.isEmpty() ? "" : ", and while on: " + String.join(", ", drains))
					+ (tips ? ". At 0 the arm melts and gives your item back." : ".")));
		}
		ArmSession s = arms.session(p);
		String now = s == null ? "Your arm is bare." : "Formed now: " + ArmForm.of(s.form).displayName() + " (" + ArmProtocol.stateName(s.state) + ").";
		sender.sendMessage(gray("  " + now + " Arm slots: " + arms.describeSlots(p) + "."));
		if (tips) sender.sendMessage(gray("  /morph arm <form> (binds your held slot) | off [all] (frees it, or every slot) | lantern|shield|spyglass|flint [on|off] | lantern bright 1-5 | special"));
	}

	// ------------------------------------------------------------------ admins: another player's arm

	private boolean admin(CommandSender sender, String[] args) {
		if (!sender.hasPermission(ADMIN)) {
			if (!arms.enabled()) return false;
			List<String> built = arms.builtForms();
			throw new IllegalArgumentException("There is no arm form '" + args[0] + "'." + (built.isEmpty() ? "" : " Forms: " + String.join(", ", built) + "."));
		}
		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			if (!arms.enabled()) return false;
			throw new IllegalArgumentException("'" + args[0] + "' is neither an arm form nor a player online. " + ADMIN_USAGE);
		}
		String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
		// these two work with arms switched off: repairing items owed doesn't need arms on
		if (action.equals("info")) {
			for (String line : arms.info(target)) sender.sendMessage(gray(line));
			return true;
		}
		if (action.equals("recover")) {
			sender.sendMessage(gray(arms.recover(target)));
			return true;
		}
		if (!arms.enabled()) throw new IllegalArgumentException("Arm forms are off on this server; only info and recover work.");
		switch (action) {
			case "off" -> {
				boolean all = args.length > 2 && args[2].equalsIgnoreCase("all");
				fail(all ? arms.unbindAll(target, ArmProtocol.WHY_ADMIN) : arms.retract(target, ArmProtocol.WHY_ADMIN));
				sender.sendMessage(gray(target.getName() + (all ? "'s arm slots are free and their arm is going back." : "'s arm is going back and its slot is free.")));
			}
			case "lantern", "shield", "spyglass", "flint" -> adminAccessory(sender, target, action, args);
			case "special" -> arms.special(target, target.isSneaking(), sender);
			case "brightness", "flare" -> arms.stepBrightness(target, sender);
			case "break" -> breakBlock(sender, target, args);
			case "hit" -> hit(sender, target, args);
			case "shoot" -> shoot(sender, target, args);
			default -> force(sender, target, action, args);
		}
		return true;
	}

	/**
	 * {@code /morph arm <player> <form> [level] [free]}: formed whether or not they own it, at any level, free of cost if asked.
	 * It binds the slot only when the player could form that form themselves and it is neither free nor at a set level
	 * (2026-11-16: a binding can't carry those); otherwise it is a one-off, and the reply says so.
	 */
	private void force(CommandSender sender, Player target, String formId, String[] args) {
		ArmForm f = ArmForm.of(formId);
		if (f == null || !f.tool()) throw new IllegalArgumentException("Unknown arm form or action '" + formId + "'. " + ADMIN_USAGE);
		Options o = options(args, 2);
		String oneOff = arms.adminOneOffReason(target, f.id(), o.level, o.free); // before forming: ownership is what it was when they asked
		fail(arms.form(target, f.id(), ArmManager.Cause.ADMIN, -1, o.level, o.free));
		ArmSession s = arms.session(target);
		if (s == null) return;
		sender.sendMessage(gray(target.getName() + "'s arm: " + f.displayName() + " level " + s.level + (s.free ? ", free" : "") + " ("
				+ ArmProtocol.stateName(s.state) + ") in slot " + (s.slot + 1) + ", holding " + ArmStash.describe(s.stash)
				+ (oneOff == null ? ", bound to that slot." : ". One-off, no slot bound (" + oneOff + "): it just goes back when they scroll away (an open inventory only tucks it).")));
	}

	/** {@code /morph arm <player> <accessory> [on|off] [level] [free]}. */
	private void adminAccessory(CommandSender sender, Player target, String id, String[] args) {
		String word = "";
		int from = 2;
		if (args.length > 2 && (args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
			word = args[2];
			from = 3;
		}
		Options o = options(args, from);
		accessory(target, id, word, true, o.level, o.free, sender);
	}

	/** An admin's {@code [level] [free]}, in either order. */
	private record Options(int level, boolean free) {}

	private static Options options(String[] args, int from) {
		int level = 0;
		boolean free = false;
		for (int i = from; i < args.length; i++) {
			String a = args[i].toLowerCase(Locale.ROOT);
			if (a.equals("free")) {
				free = true;
				continue;
			}
			int tier = ArmTiers.tier(a);
			if (tier > 0) {
				level = tier;
				continue;
			}
			try {
				level = Integer.parseInt(a);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("'" + args[i] + "' is neither a level (1-3) nor 'free'. " + ADMIN_USAGE);
			}
			if (level < 1 || level > 3) throw new IllegalArgumentException("Arm levels are 1 to 3 (iron, diamond, netherite).");
		}
		return new Options(level, free);
	}

	/**
	 * {@code /morph arm <player> break <x> <y> <z>}: the player breaks that block with what they hold, as if they had
	 * dug it (Player#breakBlock: the break event, protection plugins, drops with the arm's Fortune, and the arm's block
	 * cost). {@code ~} coordinates are relative to the player.
	 */
	private void breakBlock(CommandSender sender, Player target, String[] args) {
		if (args.length < 5) throw new IllegalArgumentException("/morph arm " + target.getName() + " break <x> <y> <z>");
		Location at = target.getLocation();
		int x = coordinate(args[2], at.getBlockX()), y = coordinate(args[3], at.getBlockY()), z = coordinate(args[4], at.getBlockZ());
		Block block = target.getWorld().getBlockAt(x, y, z);
		String what = block.getType().getKey().getKey();
		boolean broke = target.breakBlock(block);
		sender.sendMessage(gray(target.getName() + (broke ? " broke the " + what : " couldn't break the " + what + " (cancelled, protected or unbreakable)") + " at "
				+ x + " " + y + " " + z + (arms.session(target) == null ? ", without an arm formed." : " with their arm.")));
	}

	private static int coordinate(String arg, int relativeTo) {
		try {
			if (arg.startsWith("~")) return relativeTo + (arg.length() == 1 ? 0 : (int) Math.floor(Double.parseDouble(arg.substring(1))));
			return (int) Math.floor(Double.parseDouble(arg));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'" + arg + "' isn't a coordinate.");
		}
	}

	/**
	 * {@code /morph arm <player> hit <target>}: a real melee attack by the player on each creature the target selects
	 * (a selector, a name or a UUID, resolved from the command's sender), with the swing everyone sees. Attack
	 * strength, criticals, sweeps, armour and the arm's hit cost all apply as for a click.
	 */
	private void hit(CommandSender sender, Player target, String[] args) {
		if (args.length < 3) throw new IllegalArgumentException("/morph arm " + target.getName() + " hit <target selector>");
		List<Entity> victims;
		try {
			victims = Bukkit.selectEntities(sender, args[2]);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("'" + args[2] + "' doesn't select anything: " + e.getMessage());
		}
		int hit = 0;
		for (Entity victim : victims) {
			if (victim == target || !victim.isValid() || victim.getWorld() != target.getWorld()) continue;
			if (hit >= MAX_HITS) break;
			target.attack(victim);
			target.swingMainHand();
			hit++;
		}
		sender.sendMessage(gray(hit == 0 ? "Nothing to hit: the target selects no creature in " + target.getName() + "'s world."
				: target.getName() + " hit " + hit + (hit == 1 ? " creature." : " creatures.")));
	}

	/**
	 * {@code /morph arm <player> shoot [power]}: lets go of the bow arm's draw as if it had been held long enough for
	 * that power (0.1 to 1, as a bow's: 1 is a full 20-tick draw), by the same event a real release fires, so the bow's
	 * own code shoots the bolt and bills it.
	 */
	private void shoot(CommandSender sender, Player target, String[] args) {
		ArmSession s = arms.session(target);
		if (s == null || !s.form.equals(ArmForm.BOW.id()) || !s.usable()) {
			throw new IllegalArgumentException(target.getName() + " needs the " + ArmForm.BOW.displayName() + " formed to shoot.");
		}
		double power = 1;
		if (args.length > 2) {
			try {
				power = Double.parseDouble(args[2]);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("'" + args[2] + "' isn't a power (0.1 to 1).");
			}
		}
		power = Math.clamp(power, 0.1, 1);
		int ticks = drawTicks(power);
		Bukkit.getPluginManager().callEvent(new PlayerStopUsingItemEvent(target, target.getInventory().getItemInMainHand(), ticks));
		sender.sendMessage(gray(target.getName() + " let go of a " + ticks + "-tick draw (power " + Math.round(power * 100) / 100.0 + ")."));
	}

	/**
	 * The draw that gives a bow this power: vanilla's {@code power = (f^2 + 2f) / 3} with {@code f = ticks / 20}, solved
	 * for the ticks and rounded up, so the shot is at least that strong.
	 */
	static int drawTicks(double power) {
		double f = Math.sqrt(1 + 3 * Math.clamp(power, 0, 1)) - 1;
		return Math.max(1, (int) Math.ceil(f * 20 - 1e-9));
	}

	// ------------------------------------------------------------------ tab completion

	List<String> tabComplete(CommandSender sender, String[] args) {
		boolean admin = sender.hasPermission(ADMIN);
		if (!arms.enabled() && !admin) return List.of();
		Player self = player(sender);
		List<String> out = new ArrayList<>();
		if (args.length <= 1) {
			if (arms.enabled() && self != null) {
				for (String form : arms.builtForms()) {
					if (arms.levelOf(self, form) >= 1) out.add(form);
				}
				out.add("off");
				for (ArmForm f : ArmForm.ACCESSORIES) {
					if (arms.accessoryBuilt(f.id()) && arms.levelOf(self, f.id()) >= 1) out.add(f.id());
				}
				out.add("special");
			}
			if (admin) {
				for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
			}
			return out;
		}
		String first = args[0].toLowerCase(Locale.ROOT);
		if (ArmForm.of(first) != null || KEYWORDS.contains(first)) {
			if (first.equals("off") && args.length == 2) return List.of("all");
			ArmForm acc = ArmForm.of(first);
			if (acc != null && !acc.tool() && args.length == 2) return acc == ArmForm.LANTERN ? List.of("on", "off", "bright") : List.of("on", "off");
			if (first.equals("lantern") && args.length == 3 && args[1].equalsIgnoreCase("bright")) return List.of("1", "2", "3", "4", "5");
			return List.of();
		}
		if (!admin || Bukkit.getPlayerExact(args[0]) == null) return List.of();
		Player target = Bukkit.getPlayerExact(args[0]);
		if (args.length == 2) {
			out.addAll(arms.builtForms());
			for (String a : ADMIN_ACTIONS) {
				ArmForm acc = ArmForm.of(a);
				if (acc != null && !acc.tool() && !arms.accessoryBuilt(acc.id())) continue;
				if (a.equals("brightness") && !arms.lanternBuilt()) continue;
				out.add(a);
			}
			return out;
		}
		String action = args[1].toLowerCase(Locale.ROOT);
		ArmForm f = ArmForm.of(action);
		if (f != null && f.tool()) return List.of("1", "2", "3", "free");
		return switch (action) {
			case "lantern", "shield", "spyglass", "flint" -> List.of("on", "off", "1", "2", "3", "free");
			case "off" -> args.length == 3 ? List.of("all") : List.of();
			case "break" -> breakCompletion(target, args.length - 3);
			case "hit" -> args.length == 3 ? List.of("@e[type=!player,distance=..4,sort=nearest,limit=1]", "@e[distance=..5,sort=nearest]") : List.of();
			case "shoot" -> args.length == 3 ? List.of("1", "0.5") : List.of();
			default -> List.of();
		};
	}

	/** The block the player is looking at, one coordinate at a time, or ~. */
	private static List<String> breakCompletion(@Nullable Player target, int index) {
		if (index < 0 || index > 2) return List.of();
		Block looked = target == null ? null : target.getTargetBlockExact(6);
		if (looked == null) return List.of("~");
		int c = index == 0 ? looked.getX() : index == 1 ? looked.getY() : looked.getZ();
		return List.of(Integer.toString(c), "~");
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * The player a command is about: the sender, or the player an {@code /execute as} runs it as (so rig scripts can
	 * drive a player's own arm commands from the console).
	 */
	private static @Nullable Player player(CommandSender sender) {
		if (sender instanceof Player p) return p;
		if (sender instanceof ProxiedCommandSender proxied && proxied.getCallee() instanceof Player p) return p;
		return null;
	}

	private static Player self(CommandSender sender) {
		Player p = player(sender);
		if (p == null) throw new IllegalArgumentException("Only players have an arm to shape; give a player name.");
		return p;
	}

	/** A refusal from the arm manager becomes the command's error. */
	private static void fail(@Nullable String why) {
		if (why != null) throw new IllegalArgumentException(why);
	}

	private static Component gray(String text) {
		return Component.text(text, NamedTextColor.GRAY);
	}

	private static String seconds(int ticks) {
		return ((ticks + 19) / 20) + "s";
	}
}
