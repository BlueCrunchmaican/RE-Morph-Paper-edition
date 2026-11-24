package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.SurvivalData;
import dev.morphreborn.paper.survival.SurvivalConfig.Upgrade;
import dev.morphreborn.paper.tips.Tagged;
import dev.morphreborn.paper.tips.TooltipPrefs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The biomass upgrade menu: a chest anyone can use (with or without the client mod, Bedrock included), laid out like
 * the client mod's tree. Top row: the player's biomass, and Close. Then the Biomass section, with the Morph Ability in
 * its own frame beside it; the Morph section; two rows of Abilities; and along the bottom Arm Shaping and the doors to the
 * Arsenal, a second page with the twelve tools and four accessories, and to the Passives, a third with the eight biomass
 * passives and their switches. Click an upgrade to buy its next level. Where each one goes is {@link SurvivalMenuLayout}'s.
 */
public final class SurvivalMenu implements InventoryHolder {
	private static final int SIZE = SurvivalMenuLayout.SIZE;
	/** Upgrades already warned about having no slot, so a menu re-drawn on every click doesn't repeat it. */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	/** Each upgrade's parent, null for an id the tree doesn't have, as SurvivalMenuLayout walks them. */
	private static Function<String, @Nullable String> parents(Map<String, Upgrade> upgrades) {
		return id -> {
			Upgrade u = upgrades.get(id);
			return u == null ? null : u.parent();
		};
	}

	private final SurvivalMode survival;
	private final Player player;
	private final Inventory inventory;
	private final Map<Integer, String> slots = new HashMap<>();
	private SurvivalMenuLayout.Page page = SurvivalMenuLayout.Page.MAIN;
	/** The main page shows the door to the Arsenal page (the tree offers an Arsenal form). */
	private boolean door;
	/** ...and the door to the Passives page (the tree offers a passive). */
	private boolean passivesDoor;

	public static void open(SurvivalMode survival, Player player) {
		SurvivalMenu menu = new SurvivalMenu(survival, player);
		menu.render();
		player.openInventory(menu.inventory);
	}

	private SurvivalMenu(SurvivalMode survival, Player player) {
		this.survival = survival;
		this.player = player;
		this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Biomass Upgrades"));
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	void render() {
		inventory.clear();
		slots.clear();
		ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
		for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);
		inventory.setItem(SurvivalMenuLayout.STATUS_SLOT, status());
		inventory.setItem(SurvivalMenuLayout.CLOSE_SLOT, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of(), false));
		inventory.setItem(SurvivalMenuLayout.TOOLTIPS_SLOT, tooltipsToggle());
		if (page == SurvivalMenuLayout.Page.ARSENAL) {
			renderArsenal();
			return;
		}
		if (page == SurvivalMenuLayout.Page.PASSIVES) {
			renderPassives();
			return;
		}
		inventory.setItem(SurvivalMenuLayout.BIOMASS_LABEL, label(Material.RED_STAINED_GLASS_PANE, "Biomass", NamedTextColor.RED));
		inventory.setItem(SurvivalMenuLayout.MORPH_LABEL, label(Material.PURPLE_STAINED_GLASS_PANE, "Morph", NamedTextColor.LIGHT_PURPLE));
		inventory.setItem(SurvivalMenuLayout.ABILITIES_LABEL, label(Material.ORANGE_STAINED_GLASS_PANE, "Abilities", NamedTextColor.GOLD));
		inventory.setItem(SurvivalMenuLayout.ABILITIES_LABEL_2, label(Material.ORANGE_STAINED_GLASS_PANE, "Abilities", NamedTextColor.GOLD));
		ItemStack frame = item(Material.MAGENTA_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
		for (int slot : SurvivalMenuLayout.FRAME) inventory.setItem(slot, frame);
		Map<String, Upgrade> all = survival.config().upgrades;
		// What the tree offers: views() leaves out arm forms whose code isn't in this build, so their slots stay empty.
		List<String> offered = new ArrayList<>();
		for (SurvivalMode.View v : survival.views(player)) {
			if (all.containsKey(v.id())) offered.add(v.id());
		}
		Function<String, @Nullable String> parentOf = parents(all);
		Map<String, Integer> layout = SurvivalMenuLayout.assign(offered, parentOf);
		for (String id : offered) {
			Integer slot = layout.get(id);
			if (slot == null) {
				// the Arsenal's forms and the passives are on pages of their own: only one with no place anywhere is news
				if (!SurvivalMenuLayout.onOtherPage(id, parentOf)) warnNoRoom(id, SurvivalMenuLayout.section(id, parentOf));
				continue;
			}
			slots.put(slot, id);
			inventory.setItem(slot, upgradeItem(all.get(id)));
		}
		// The Arsenal's forms are on their own page; its door sits beside Arm Shaping once the tree offers any of them.
		boolean arsenal = false, passives = false;
		for (String id : offered) {
			SurvivalMenuLayout.Section section = SurvivalMenuLayout.section(id, parentOf);
			arsenal |= section == SurvivalMenuLayout.Section.ARSENAL;
			passives |= section == SurvivalMenuLayout.Section.PASSIVES;
		}
		door = arsenal;
		if (arsenal) inventory.setItem(SurvivalMenuLayout.ARSENAL_DOOR_SLOT, arsenalDoor(offered, parentOf));
		passivesDoor = passives;
		if (passives) inventory.setItem(SurvivalMenuLayout.PASSIVES_DOOR_SLOT, passivesDoor(offered, parentOf));
	}

	/**
	 * The Tooltips switch (decided 2026-11-17), on every page: for a player without the client mod it is the way to turn the explanation lines
	 * of every description off and on (the modded client has it in its settings). Its own lines are not tips: when the explanations are off,
	 * this is the one place that still says how to bring them back.
	 */
	private ItemStack tooltipsToggle() {
		boolean on = tips();
		Lore lore = new Lore();
		lore.always("Explanations: " + (on ? "shown" : "hidden"), on ? NamedTextColor.GREEN : NamedTextColor.GRAY);
		lore.always("Click to " + (on ? "hide" : "show") + " them", NamedTextColor.YELLOW);
		lore.always("Names, levels, costs and rates always show.", NamedTextColor.DARK_GRAY);
		return item(Material.OAK_SIGN, Component.text("Tooltips: " + (on ? "on" : "off"), on ? NamedTextColor.GREEN : NamedTextColor.GRAY), lore.build(true), on);
	}

	/** The player's Tooltips setting. */
	private boolean tips() {
		return plugin().tooltips(player);
	}

	private static MorphRebornPlugin plugin() {
		return JavaPlugin.getPlugin(MorphRebornPlugin.class);
	}

	/**
	 * A lore built from typed lines (Tagged): each line says whether it is explanation or always shown, and {@link #build} drops the explanation
	 * for a player who turned Tooltips off, without leaving a gap where it was.
	 */
	/**
	 * How wide a lore line is wrapped, in characters: about 5.6 px each in the default font, so 36 is about 200 px, which a 320-wide screen shows
	 * with room to spare wherever the mouse is (40 was 224 px, and a Bedrock client does no wrapping of its own); and the most lines a lore has.
	 */
	static final int LORE_WIDTH = 36, MAX_LORE_LINES = 20;

	private static final class Lore {
		private final List<Tagged<Component>> items = new ArrayList<>();

		Lore always(String text, TextColor color) {
			items.add(Tagged.always(line(text, color)));
			return this;
		}

		Lore tip(String text, TextColor color) {
			items.add(Tagged.tip(line(text, color)));
			return this;
		}

		Lore alwaysWrapped(String text, TextColor color) {
			for (String wrapped : wrap(text, LORE_WIDTH)) always(wrapped, color);
			return this;
		}

		/** A line wrapped to the lore's width, its continuation lines indented ("Now: ...", "Needs ... first": the client wraps its own copy of these, the chest did not). */
		Lore alwaysHanging(String text, TextColor color) {
			boolean first = true;
			for (String wrapped : wrap(text, LORE_WIDTH)) {
				always((first ? "" : "  ") + wrapped, color);
				first = false;
			}
			return this;
		}

		Lore tipWrapped(String text, TextColor color) {
			for (String wrapped : wrap(text, LORE_WIDTH)) tip(wrapped, color);
			return this;
		}

		Lore blank() {
			items.add(Tagged.always(Component.empty()));
			return this;
		}

		List<Component> build(boolean tips) {
			List<Component> out = new ArrayList<>(Tagged.filter(items, tips, c -> Component.empty().equals(c)));
			// The client's tooltip is kept off the bottom edge but not the top, so a lore taller than the screen lost its first lines (the name and the
			// description): when it is over the cap, the description's last lines go (the costs and what a click does come after it and stay)
			while (out.size() > MAX_LORE_LINES) {
				int blank = out.indexOf(Component.empty());
				if (blank < 2) break;
				out.remove(blank - 1);
			}
			return out;
		}
	}

	/** The Passives page: Back, then the eight in a row, each with its On/Off pane under it and its buy button under that. */
	private void renderPassives() {
		inventory.setItem(SurvivalMenuLayout.PASSIVES_BACK_SLOT, item(Material.ARROW, Component.text("Back to the upgrades", NamedTextColor.YELLOW), List.of(), false));
		inventory.setItem(SurvivalMenuLayout.PASSIVES_LABEL, label(Material.PURPLE_STAINED_GLASS_PANE, "Passives", NamedTextColor.LIGHT_PURPLE));
		inventory.setItem(SurvivalMenuLayout.SWITCH_LABEL, label(Material.LIME_STAINED_GLASS_PANE, "On / Off", NamedTextColor.GREEN));
		inventory.setItem(SurvivalMenuLayout.BUY_LABEL, label(Material.ORANGE_STAINED_GLASS_PANE, "Buy", NamedTextColor.GOLD));
		Map<String, Upgrade> all = survival.config().upgrades;
		List<String> offered = new ArrayList<>();
		for (SurvivalMode.View v : survival.views(player)) {
			if (all.containsKey(v.id())) offered.add(v.id());
		}
		Function<String, @Nullable String> parentOf = parents(all);
		Map<String, Integer> layout = SurvivalMenuLayout.assignPassives(offered, parentOf);
		Passives passives = passives();
		for (String id : offered) {
			if (SurvivalMenuLayout.section(id, parentOf) != SurvivalMenuLayout.Section.PASSIVES) continue;
			Integer slot = layout.get(id);
			if (slot == null) {
				warnNoRoom(id, SurvivalMenuLayout.Section.PASSIVES);
				continue;
			}
			Upgrade u = all.get(id);
			Passives.State state = passives.state(player, id);
			slots.put(slot, id);
			inventory.setItem(slot, passiveItem(u, state, passives.settings(id)));
			inventory.setItem(SurvivalMenuLayout.switchSlot(slot), switchPane(state));
			if (survival.level(player, id) < u.maxLevel()) inventory.setItem(SurvivalMenuLayout.buySlot(slot), buyPane(u));
		}
	}

	private Passives passives() {
		return JavaPlugin.getPlugin(MorphRebornPlugin.class).passives();
	}

	/** The door to the Passives page: how many the player has grown and how many of those are on. */
	private ItemStack passivesDoor(List<String> offered, Function<String, @Nullable String> parentOf) {
		int total = 0, owned = 0, on = 0;
		Passives passives = passives();
		for (String id : offered) {
			if (SurvivalMenuLayout.section(id, parentOf) != SurvivalMenuLayout.Section.PASSIVES) continue;
			total++;
			if (survival.level(player, id) >= 1) owned++;
			if (passives.on(player, id)) on++;
		}
		Lore lore = new Lore();
		lore.tipWrapped("Abilities that cost biomass only when they act. Switch the ones you have grown on and off here.", NamedTextColor.GRAY);
		lore.blank();
		lore.always(owned + " of " + total + " grown, " + on + " on", NamedTextColor.WHITE);
		lore.tip("Click to open", NamedTextColor.YELLOW);
		return item(Material.SCULK_SENSOR, Component.text("The Passives", NamedTextColor.LIGHT_PURPLE), lore.build(tips()), on > 0);
	}

	/** One passive on its page: what it does, what it costs to run, what it is doing, and what a click does. */
	private ItemStack passiveItem(Upgrade u, Passives.State state, PassiveSettings.Entry settings) {
		int level = survival.level(player, u.id());
		boolean maxed = level >= u.maxLevel();
		double cost = survival.nextCost(player, u);
		String missing = survival.missingRequirement(player, u);
		double biomass = survival.data(player).biomass;
		boolean tips = tips();
		Lore lore = new Lore();
		lore.tipWrapped(u.description(), NamedTextColor.GRAY);
		lore.blank();
		// what it costs and per what always stays; when it is billed is explanation (PassiveSettings.costLines)
		for (String clause : settings.costLines().clauses(tips)) lore.alwaysWrapped(clause, NamedTextColor.AQUA);
		lore.blank();
		lore.always(u.maxLevel() == 1 ? (level >= 1 ? "Bought" : "Not bought") : "Level " + level + " / " + u.maxLevel(), NamedTextColor.WHITE);
		if (u.showsValue() && level >= 1) lore.alwaysHanging("Now: " + survival.formatValue(u, u.value().at(level), tips), NamedTextColor.AQUA);
		if (!maxed && u.showsValue()) lore.alwaysHanging("Next: " + survival.formatValue(u, u.value().at(level + 1), tips), NamedTextColor.AQUA);
		if (!maxed) lore.always("Cost: " + SurvivalMode.number(cost) + " biomass", biomass + 1e-9 >= cost ? NamedTextColor.GREEN : NamedTextColor.RED);
		if (missing != null && !maxed) lore.alwaysHanging("Needs " + missing + " first", NamedTextColor.RED);
		lore.blank();
		if (level < 1) lore.tip(missing != null ? "Grow what it needs first" : "Click to buy", missing != null ? NamedTextColor.DARK_GRAY : NamedTextColor.YELLOW);
		else {
			lore.tip("Click to switch " + (state == Passives.State.OFF ? "on" : "off"), NamedTextColor.YELLOW);
			if (!maxed) lore.tip("Right click: buy the next level", NamedTextColor.YELLOW);
		}
		TextColor nameColor = level < 1 ? NamedTextColor.WHITE : state == Passives.State.OFF ? NamedTextColor.GRAY : NamedTextColor.GREEN;
		return item(u.icon(), Component.text(u.name(), nameColor), lore.build(tips), level > 0 && state != Passives.State.OFF);
	}

	/** The pane under a passive that says whether it is on, and switches it when clicked. */
	private ItemStack switchPane(Passives.State state) {
		boolean tips = tips();
		return switch (state) {
			case NOT_BOUGHT -> item(Material.BLACK_STAINED_GLASS_PANE, Component.text("Not bought", NamedTextColor.DARK_GRAY), List.of(), false);
			case OFF -> item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Off", NamedTextColor.GRAY),
					new Lore().tip("Click to switch on", NamedTextColor.YELLOW).build(tips), false);
			case RUNNING -> item(Material.LIME_STAINED_GLASS_PANE, Component.text("On: running", NamedTextColor.GREEN),
					new Lore().tip("Click to switch off", NamedTextColor.YELLOW).build(tips), false);
			case IDLE -> item(Material.YELLOW_STAINED_GLASS_PANE, Component.text("On: idle", NamedTextColor.YELLOW),
					new Lore().tip("Nothing to do right now, so it costs nothing.", NamedTextColor.GRAY).tip("Click to switch off", NamedTextColor.YELLOW).build(tips), false);
			case BROKE -> item(Material.RED_STAINED_GLASS_PANE, Component.text("On: no biomass", NamedTextColor.RED),
					new Lore().tip("It can't pay, so it does nothing.", NamedTextColor.GRAY).tip("Click to switch off", NamedTextColor.YELLOW).build(tips), false);
		};
	}

	/** The button under a passive's switch that buys its next level (not drawn once it is fully upgraded): the way in for a player with no right click. */
	private ItemStack buyPane(Upgrade u) {
		int level = survival.level(player, u.id());
		double cost = survival.nextCost(player, u);
		String missing = survival.missingRequirement(player, u);
		boolean affordable = survival.data(player).biomass + 1e-9 >= cost && missing == null && survival.isUnlocked(player);
		List<Component> lore = new ArrayList<>();
		lore.add(line("Cost: " + SurvivalMode.number(cost) + " biomass", affordable ? NamedTextColor.GREEN : NamedTextColor.RED));
		if (missing != null) lore.add(line("Needs " + missing + " first", NamedTextColor.RED));
		else if (!survival.isUnlocked(player)) lore.add(line("Unlock the Biomass ability first", NamedTextColor.DARK_GRAY));
		return item(affordable ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
				Component.text(level < 1 ? "Buy" : "Buy level " + (level + 1), affordable ? NamedTextColor.GREEN : NamedTextColor.RED), lore, false);
	}

	/** The Arsenal page: Back, then the tools (seven, then the new five) and the accessories on rows of their own. */
	private void renderArsenal() {
		inventory.setItem(SurvivalMenuLayout.ARSENAL_BACK_SLOT, item(Material.ARROW, Component.text("Back to the upgrades", NamedTextColor.YELLOW), List.of(), false));
		inventory.setItem(SurvivalMenuLayout.TOOLS_LABEL, label(Material.RED_STAINED_GLASS_PANE, "Tools", NamedTextColor.RED));
		inventory.setItem(SurvivalMenuLayout.ACCESSORIES_LABEL, label(Material.ORANGE_STAINED_GLASS_PANE, "Accessories", NamedTextColor.GOLD));
		Map<String, Upgrade> all = survival.config().upgrades;
		List<String> offered = new ArrayList<>();
		for (SurvivalMode.View v : survival.views(player)) {
			if (all.containsKey(v.id())) offered.add(v.id());
		}
		Function<String, @Nullable String> parentOf = parents(all);
		Map<String, Integer> layout = SurvivalMenuLayout.assignArsenal(offered, parentOf);
		for (String id : offered) {
			if (SurvivalMenuLayout.section(id, parentOf) != SurvivalMenuLayout.Section.ARSENAL) continue;
			Integer slot = layout.get(id);
			if (slot == null) {
				warnNoRoom(id, SurvivalMenuLayout.Section.ARSENAL);
				continue;
			}
			slots.put(slot, id);
			inventory.setItem(slot, upgradeItem(all.get(id)));
		}
	}

	/** The door to the Arsenal page: how many of its forms the player has grown. */
	private ItemStack arsenalDoor(List<String> offered, Function<String, @Nullable String> parentOf) {
		int total = 0, owned = 0;
		List<String> tools = new ArrayList<>();
		List<String> accessories = new ArrayList<>();
		for (String id : offered) {
			if (SurvivalMenuLayout.section(id, parentOf) != SurvivalMenuLayout.Section.ARSENAL) continue;
			total++;
			if (survival.level(player, id) >= 1) owned++;
			// only what this build has (offered is already without the unbuilt ones), so the lore never names a form that isn't there
			dev.morphreborn.paper.arm.ArmForm f = dev.morphreborn.paper.arm.ArmForm.byUpgrade(id);
			if (f != null) (f.tool() ? tools : accessories).add(f.id());
		}
		Lore lore = new Lore();
		for (String text : arsenalDoorLines(tools, accessories)) lore.tip(text, NamedTextColor.GRAY);
		lore.blank();
		lore.always(owned + " of " + total + " grown", NamedTextColor.WHITE);
		lore.tip("Click to open", NamedTextColor.YELLOW);
		return item(Material.NETHERITE_SWORD, Component.text("The Arsenal", NamedTextColor.RED), lore.build(tips()), owned > 0);
	}

	/** The door's description: the built tools and accessories by name, each list on its own wrapped lines; none for an empty list. */
	static List<String> arsenalDoorLines(List<String> tools, List<String> accessories) {
		List<String> lines = new ArrayList<>();
		if (!tools.isEmpty()) lines.addAll(wrap("Tools: " + String.join(", ", tools) + ".", LORE_WIDTH));
		if (!accessories.isEmpty()) lines.addAll(wrap("Accessories: " + String.join(", ", accessories) + ".", LORE_WIDTH));
		return lines;
	}

	/** Once per upgrade: it has no slot (its row is full, or it is an admin's own upgrade under Arm Shaping). */
	private static void warnNoRoom(String id, SurvivalMenuLayout.Section section) {
		if (!WARNED.add(id)) return;
		JavaPlugin.getPlugin(MorphRebornPlugin.class).getLogger().warning("survival.yml: the chest menu has no room for upgrade " + id + " ("
				+ section.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " section), so it isn't shown there. Players without the client mod can"
				+ " still buy it with /morph upgrade " + id + ".");
	}

	void click(int slot) {
		click(slot, ClickType.LEFT);
	}

	/** Clicks that do something on the Passives page: the mouse's two buttons, alone or with shift (a Bedrock player's tap is a left click). */
	static boolean passiveClick(ClickType type) {
		return type == ClickType.LEFT || type == ClickType.RIGHT || type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT;
	}

	void click(int slot, ClickType type) {
		if (slot == SurvivalMenuLayout.CLOSE_SLOT) {
			player.closeInventory();
			return;
		}
		if (slot == SurvivalMenuLayout.TOOLTIPS_SLOT) {
			// the same two rules as /morph tooltips: the permission, and a client mod that sends its own choice keeps it in its settings
			if (!player.hasPermission("morphreborn.tooltips")) {
				player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.GRAY));
				return;
			}
			if (TooltipPrefs.clientKnows(plugin().channel().clientVersion(player))) {
				player.sendMessage(Component.text("With the RE:Morph client mod you change Tooltips in its settings (/morphclient).", NamedTextColor.GRAY));
				return;
			}
			boolean want = !tips();
			plugin().setTooltips(player, want);
			player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, want ? 1.4F : 0.8F);
			render();
			return;
		}
		if (page == SurvivalMenuLayout.Page.MAIN && slot == SurvivalMenuLayout.PASSIVES_DOOR_SLOT && passivesDoor) {
			page = SurvivalMenuLayout.Page.PASSIVES;
			render();
			return;
		}
		if (page == SurvivalMenuLayout.Page.PASSIVES) {
			if (slot == SurvivalMenuLayout.PASSIVES_BACK_SLOT) {
				page = SurvivalMenuLayout.Page.MAIN;
				render();
			} else if (passiveClick(type)) {
				clickPassive(slot, type.isRightClick() || type.isShiftClick());
			}
			return;
		}
		if (page == SurvivalMenuLayout.Page.MAIN && slot == SurvivalMenuLayout.ARSENAL_DOOR_SLOT && door) {
			page = SurvivalMenuLayout.Page.ARSENAL;
			render();
			return;
		}
		if (page == SurvivalMenuLayout.Page.ARSENAL && slot == SurvivalMenuLayout.ARSENAL_BACK_SLOT) {
			page = SurvivalMenuLayout.Page.MAIN;
			render();
			return;
		}
		String id = slots.get(slot);
		if (id == null) return;
		survival.buyWithFeedback(player, id);
		render();
	}

	/** A click on a passive, its switch or its buy button: switch it, or buy (see {@link SurvivalMenuLayout#passiveAction}). */
	private void clickPassive(int slot, boolean alt) {
		SurvivalMenuLayout.PassiveRole role = SurvivalMenuLayout.passiveRole(slot);
		String id = role == null ? null : slots.get(SurvivalMenuLayout.passiveItemSlot(slot));
		if (role == null || id == null) return;
		Passives passives = passives();
		switch (SurvivalMenuLayout.passiveAction(role, passives.owns(player, id), alt)) {
			case TOGGLE -> {
				boolean want = !passives.on(player, id);
				if (passives.set(player, id, want)) {
					player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, want ? 1.4F : 0.8F);
					player.sendActionBar(Component.text(survival.displayName(id) + (want ? " on." : " off."), want ? NamedTextColor.GREEN : NamedTextColor.GRAY));
				}
			}
			case BUY -> {
				SurvivalMode.Purchase result = survival.buyWithFeedback(player, id);
				if (result != SurvivalMode.Purchase.BOUGHT && result != SurvivalMode.Purchase.UNKNOWN) {
					player.sendActionBar(Component.text(survival.describe(result, player, id), NamedTextColor.RED));
				}
			}
			case NONE -> player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6F, 0.7F);
		}
		render();
	}

	private ItemStack status() {
		SurvivalData d = survival.data(player);
		Lore lore = new Lore();
		boolean tips = tips();
		if (!survival.isUnlocked(player)) {
			lore.tip("Kills give you nothing yet.", NamedTextColor.GRAY);
			lore.tip("Unlock the Biomass ability by having", NamedTextColor.GRAY);
			lore.tip("Wither and Regeneration at the same time.", NamedTextColor.GRAY);
			lore.tip("(Nether fortresses have what you need.)", NamedTextColor.DARK_GRAY);
			return item(Material.WITHER_ROSE, Component.text("Biomass: locked", NamedTextColor.DARK_GREEN), lore.build(tips), false);
		}
		double capacity = survival.capacity(player);
		lore.always(bar(d.biomass, capacity), NamedTextColor.RED);
		lore.tip("Kills add biomass, which buys the upgrades below.", NamedTextColor.GRAY);
		// the state stays; what to buy to change it is explanation
		if (survival.canMorph(player)) {
			lore.always("Morphing: unlocked", NamedTextColor.GREEN);
		} else {
			lore.always("Morphing: not yet", NamedTextColor.YELLOW);
			lore.tip("Buy the Morph Ability.", NamedTextColor.YELLOW);
		}
		return item(Material.REDSTONE, Component.text("Biomass " + SurvivalMode.number(Math.floor(d.biomass)) + " / " + SurvivalMode.number(capacity),
				NamedTextColor.RED), lore.build(tips), false);
	}

	private static String bar(double value, double max) {
		int filled = max <= 0 ? 0 : (int) Math.round(20 * Math.min(1, value / max));
		return "▮".repeat(filled) + "▯".repeat(20 - filled);
	}

	private ItemStack upgradeItem(Upgrade u) {
		int level = survival.level(player, u.id());
		boolean maxed = level >= u.maxLevel();
		double cost = survival.nextCost(player, u);
		String missing = survival.missingRequirement(player, u);
		double biomass = survival.data(player).biomass;
		boolean tips = tips();
		Lore lore = new Lore();
		lore.tipWrapped(u.description(), NamedTextColor.GRAY);
		lore.blank();
		lore.always(u.maxLevel() == 1 ? (level >= 1 ? "Bought" : "Not bought") : "Level " + level + " / " + u.maxLevel(), NamedTextColor.WHITE);
		if (u.showsValue() && level >= 1) lore.alwaysHanging("Now: " + survival.formatValue(u, u.value().at(level), tips), NamedTextColor.AQUA);
		if (!maxed && u.showsValue()) lore.alwaysHanging("Next: " + survival.formatValue(u, u.value().at(level + 1), tips), NamedTextColor.AQUA);
		if (!maxed) lore.always("Cost: " + SurvivalMode.number(cost) + " biomass", biomass + 1e-9 >= cost ? NamedTextColor.GREEN : NamedTextColor.RED);
		if (missing != null && !maxed) lore.alwaysHanging("Needs " + missing + " first", NamedTextColor.RED);
		lore.blank();
		if (maxed) lore.always(u.maxLevel() == 1 ? "Unlocked" : "Fully upgraded", NamedTextColor.GOLD);
		else if (!survival.isUnlocked(player)) lore.always("Unlock the Biomass ability first", NamedTextColor.DARK_GRAY);
		else if (missing == null) lore.tip("Click to buy", NamedTextColor.YELLOW);
		TextColor nameColor = maxed ? NamedTextColor.GOLD : level > 0 ? NamedTextColor.GREEN : NamedTextColor.WHITE;
		return item(u.icon(), Component.text(u.name(), nameColor), lore.build(tips), level > 0);
	}

	private static ItemStack label(Material pane, String text, TextColor color) {
		return item(pane, Component.text(text, color).decoration(TextDecoration.BOLD, true), List.of(), false);
	}

	private static Component line(String text, TextColor color) {
		return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
	}

	private static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.displayName(name.decoration(TextDecoration.ITALIC, false));
			meta.lore(lore);
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
			if (glint) meta.setEnchantmentGlintOverride(true);
			item.setItemMeta(meta);
		}
		return item;
	}

	static List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
				out.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty()) out.add(line.toString());
		return out;
	}

	/**
	 * Redraws the Passives page if {@code player} has it open: the run states change every 10 ticks, and a page only drawn on a click
	 * would say "idle" right after a switch-on or a purchase until the next click (fix round, 2026-11-16).
	 */
	static void refreshPassives(Player player) {
		SurvivalMenu menu = of(player.getOpenInventory().getTopInventory().getHolder(false));
		if (menu != null && menu.player == player && menu.page == SurvivalMenuLayout.Page.PASSIVES) menu.render();
	}

	/** The menu a player has open, if it is this one. */
	static @Nullable SurvivalMenu of(@Nullable InventoryHolder holder) {
		return holder instanceof SurvivalMenu menu ? menu : null;
	}
}
