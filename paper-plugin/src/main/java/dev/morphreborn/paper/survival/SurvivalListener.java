package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.MorphRebornPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Survival Mode events: the biomass unlock and the upgrade menu. */
public final class SurvivalListener implements Listener {
	private final MorphRebornPlugin plugin;

	public SurvivalListener(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	/** "A Mutation Complication": checked a tick after the player's effects change, once the change has applied. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEffect(EntityPotionEffectEvent event) {
		if (!(event.getEntity() instanceof Player player) || event.getNewEffect() == null || !plugin.survival().active()) return;
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			if (player.isOnline()) plugin.survival().checkUnlock(player);
		});
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent event) {
		SurvivalMenu menu = SurvivalMenu.of(event.getView().getTopInventory().getHolder(false));
		if (menu == null) return;
		event.setCancelled(true);
		if (event.getClickedInventory() == event.getView().getTopInventory()) menu.click(event.getRawSlot());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDrag(InventoryDragEvent event) {
		if (SurvivalMenu.of(event.getView().getTopInventory().getHolder(false)) != null) event.setCancelled(true);
	}
}
