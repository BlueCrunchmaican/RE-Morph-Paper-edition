package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.jspecify.annotations.Nullable;

/**
 * Trading with a villager morph, like with a real villager: right-click a player morphed as a villager to open its
 * trades. The game itself generates them for that villager's profession and level (a novice librarian sells books, a
 * master one more), each trade has its limited uses, and they all restock once a Minecraft day. What the buyer pays is
 * used up, as with a real villager; the villager player gives and gets nothing. Optionally the villager player is held
 * in place while someone trades with them (config.yml trading.freeze-villager).
 *
 * <p>Trades are kept per villager player and look, for as long as the server runs, so uses carry over between visits.
 */
public final class VillagerTrading implements Listener {
	private static final String VILLAGER = "minecraft:villager";
	private final MorphRebornPlugin plugin;
	private final NamespacedKey freezeKey = MorphRebornPlugin.key("trade_freeze");
	private final Map<String, Shop> shops = new HashMap<>();
	/** Villager player -> players who have their trades open. */
	private final Map<UUID, Set<UUID>> customers = new HashMap<>();

	private static final class Shop {
		final Merchant merchant;
		long day;

		Shop(Merchant merchant, long day) {
			this.merchant = merchant;
			this.day = day;
		}
	}

	public VillagerTrading(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEntityEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || !plugin.settings().tradingVillagers) return;
		if (!(event.getRightClicked() instanceof Player villager) || villager.equals(event.getPlayer())) return;
		MorphRef form = plugin.morphs().activeForm(villager);
		if (form == null || !VILLAGER.equals(form.entityType()) || plugin.morphs().isTransitioning(villager)) return;
		event.setCancelled(true);
		Player buyer = event.getPlayer();
		Shop shop = shop(villager, form);
		if (shop == null || shop.merchant.getRecipes().isEmpty()) {
			buyer.sendActionBar(Component.text("This villager has nothing to trade.", NamedTextColor.GRAY));
			return;
		}
		if (buyer.openMerchant(shop.merchant, true) == null) return;
		customers.computeIfAbsent(villager.getUniqueId(), k -> new HashSet<>()).add(buyer.getUniqueId());
		if (plugin.settings().tradingFreeze) freeze(villager, true);
	}

	/** This villager player's trades for their current look, restocked if a new day has begun. */
	private @Nullable Shop shop(Player villager, MorphRef form) {
		World world = villager.getWorld();
		long day = world.getFullTime() / 24000L;
		String key = villager.getUniqueId() + "|" + form.variantId();
		Shop shop = shops.get(key);
		if (shop == null) {
			Merchant merchant = generate(villager, form);
			if (merchant == null) return null;
			shop = new Shop(merchant, day);
			shops.put(key, shop);
		} else if (shop.day != day) {
			List<MerchantRecipe> restocked = new ArrayList<>();
			for (MerchantRecipe r : shop.merchant.getRecipes()) {
				MerchantRecipe copy = copy(r);
				copy.setUses(0);
				restocked.add(copy);
			}
			shop.merchant.setRecipes(restocked);
			shop.day = day;
		}
		return shop;
	}

	/**
	 * Trades as the game makes them: a stand-in villager (never added to the world) with this look's profession, type
	 * and level fills its offers level by level, as a real one does when it levels up.
	 */
	private @Nullable Merchant generate(Player villager, MorphRef form) {
		LivingEntity template = plugin.templates().get(form, villager.getWorld());
		if (!(template instanceof Villager look)) return null;
		Villager standIn = villager.getWorld().createEntity(villager.getLocation(), Villager.class);
		standIn.setVillagerType(look.getVillagerType());
		standIn.setProfession(look.getProfession());
		standIn.setVillagerLevel(1);
		List<MerchantRecipe> recipes = new ArrayList<>(standIn.getRecipes()); // generates the novice trades
		int level = Math.clamp(look.getVillagerLevel(), 1, 5);
		if (level > 1 && standIn.increaseLevel(level - 1)) recipes = new ArrayList<>(standIn.getRecipes());
		List<MerchantRecipe> copies = new ArrayList<>();
		for (MerchantRecipe r : recipes) copies.add(copy(r));
		String profession = look.getProfession().getKey().getKey();
		Merchant merchant = Bukkit.createMerchant(Component.translatable("entity.minecraft.villager." + profession));
		merchant.setRecipes(copies);
		return merchant;
	}

	/**
	 * A copy read through the getters. MerchantRecipe's own copy constructor reads its fields, which the server's
	 * recipes (a villager's, a merchant's) leave at 0 because they keep their values elsewhere; such a copy has no uses.
	 */
	private static MerchantRecipe copy(MerchantRecipe r) {
		MerchantRecipe copy = new MerchantRecipe(r.getResult().clone(), r.getUses(), r.getMaxUses(), r.hasExperienceReward(),
				r.getVillagerExperience(), r.getPriceMultiplier(), r.getDemand(), r.getSpecialPrice(), r.shouldIgnoreDiscounts());
		List<org.bukkit.inventory.ItemStack> ingredients = new ArrayList<>();
		for (org.bukkit.inventory.ItemStack i : r.getIngredients()) ingredients.add(i.clone());
		copy.setIngredients(ingredients);
		return copy;
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		UUID buyer = event.getPlayer().getUniqueId();
		for (Map.Entry<UUID, Set<UUID>> e : List.copyOf(customers.entrySet())) {
			if (!e.getValue().remove(buyer)) continue;
			if (e.getValue().isEmpty()) {
				customers.remove(e.getKey());
				Player villager = Bukkit.getPlayer(e.getKey());
				if (villager != null) freeze(villager, false);
			}
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		freeze(player, false);
		customers.remove(player.getUniqueId());
		shops.keySet().removeIf(k -> k.startsWith(player.getUniqueId() + "|"));
	}

	/** Hold the villager player in place (no walking, no jumping) while someone trades with them, or let them go. */
	private void freeze(Player villager, boolean frozen) {
		for (Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
			AttributeInstance instance = villager.getAttribute(attribute);
			if (instance == null) continue;
			instance.removeModifier(freezeKey);
			if (frozen) instance.addTransientModifier(new AttributeModifier(freezeKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
		}
	}

	/** Let every held villager go (shutdown, or the setting switched off). */
	public void releaseAll() {
		for (UUID id : List.copyOf(customers.keySet())) {
			Player villager = Bukkit.getPlayer(id);
			if (villager != null) freeze(villager, false);
		}
		customers.clear();
	}
}
