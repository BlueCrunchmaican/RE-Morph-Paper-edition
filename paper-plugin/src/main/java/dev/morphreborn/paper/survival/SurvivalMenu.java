package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.data.SurvivalData;
import dev.morphreborn.paper.survival.SurvivalConfig.Upgrade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * The biomass upgrade menu: a chest anyone can use (with or without the client mod, Bedrock included). Top row: the
 * player's biomass; then the biomass upgrades; then the morph upgrades. Click an upgrade to buy its next level.
 */
public final class SurvivalMenu implements InventoryHolder {
	private static final int SIZE = 45;
	private static final int STATUS_SLOT = 4;
	private static final int CLOSE_SLOT = 40;
	private static final int[] BIOMASS_ROW = {10, 12, 14, 16, 11, 13, 15};
	private static final int[] MORPH_ROW = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

	private final SurvivalMode survival;
	private final Player player;
	private final Inventory inventory;
	private final Map<Integer, String> slots = new HashMap<>();

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
		inventory.setItem(STATUS_SLOT, status());
		inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED), List.of(), false));
		int b = 0, m = 0;
		for (Upgrade u : survival.config().upgrades.values()) {
			int slot;
			if (u.id().startsWith("biomass_") && b < BIOMASS_ROW.length) slot = BIOMASS_ROW[b++];
			else if (m < MORPH_ROW.length) slot = MORPH_ROW[m++];
			else continue;
			slots.put(slot, u.id());
			inventory.setItem(slot, upgradeItem(u));
		}
	}

	void click(int slot) {
		if (slot == CLOSE_SLOT) {
			player.closeInventory();
			return;
		}
		String id = slots.get(slot);
		if (id == null) return;
		survival.buyWithFeedback(player, id);
		render();
	}

	private ItemStack status() {
		SurvivalData d = survival.data(player);
		List<Component> lore = new ArrayList<>();
		if (!survival.isUnlocked(player)) {
			lore.add(line("Kills give you nothing yet.", NamedTextColor.GRAY));
			lore.add(line("Unlock the Biomass ability by having", NamedTextColor.GRAY));
			lore.add(line("Wither and Regeneration at the same time.", NamedTextColor.GRAY));
			lore.add(line("(Nether fortresses have what you need.)", NamedTextColor.DARK_GRAY));
			return item(Material.WITHER_ROSE, Component.text("Biomass: locked", NamedTextColor.DARK_GREEN), lore, false);
		}
		double capacity = survival.capacity(player);
		lore.add(line(bar(d.biomass, capacity), NamedTextColor.RED));
		lore.add(line("Kills add biomass, which buys the upgrades below.", NamedTextColor.GRAY));
		lore.add(line(survival.canMorph(player) ? "Morphing: unlocked" : "Morphing: buy the Morph Ability", survival.canMorph(player) ? NamedTextColor.GREEN
				: NamedTextColor.YELLOW));
		return item(Material.REDSTONE, Component.text("Biomass " + SurvivalMode.number(Math.floor(d.biomass)) + " / " + SurvivalMode.number(capacity),
				NamedTextColor.RED), lore, false);
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
		List<Component> lore = new ArrayList<>();
		for (String wrapped : wrap(u.description(), 40)) lore.add(line(wrapped, NamedTextColor.GRAY));
		lore.add(Component.empty());
		lore.add(line(u.maxLevel() == 1 ? (level >= 1 ? "Bought" : "Not bought") : "Level " + level + " / " + u.maxLevel(), NamedTextColor.WHITE));
		if (u.maxLevel() > 1 && level >= 1) lore.add(line("Now: " + survival.formatValue(u, u.value().at(level)), NamedTextColor.AQUA));
		if (!maxed && u.maxLevel() > 1) lore.add(line("Next: " + survival.formatValue(u, u.value().at(level + 1)), NamedTextColor.AQUA));
		if (!maxed) lore.add(line("Cost: " + SurvivalMode.number(cost) + " biomass", biomass + 1e-9 >= cost ? NamedTextColor.GREEN : NamedTextColor.RED));
		if (missing != null && !maxed) lore.add(line("Needs " + missing + " first", NamedTextColor.RED));
		lore.add(Component.empty());
		if (maxed) lore.add(line(u.maxLevel() == 1 ? "Unlocked" : "Fully upgraded", NamedTextColor.GOLD));
		else if (!survival.isUnlocked(player)) lore.add(line("Unlock the Biomass ability first", NamedTextColor.DARK_GRAY));
		else if (missing == null) lore.add(line("Click to buy", NamedTextColor.YELLOW));
		TextColor nameColor = maxed ? NamedTextColor.GOLD : level > 0 ? NamedTextColor.GREEN : NamedTextColor.WHITE;
		return item(u.icon(), Component.text(u.name(), nameColor), lore, level > 0);
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

	private static List<String> wrap(String text, int width) {
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

	/** The menu a player has open, if it is this one. */
	static @Nullable SurvivalMenu of(@Nullable InventoryHolder holder) {
		return holder instanceof SurvivalMenu menu ? menu : null;
	}
}
