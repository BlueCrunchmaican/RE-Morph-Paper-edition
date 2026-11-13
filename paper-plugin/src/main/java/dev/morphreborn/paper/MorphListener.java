package dev.morphreborn.paper;

import java.util.List;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Player lifecycle and kills. */
public final class MorphListener implements Listener {
	private final MorphRebornPlugin plugin;

	public MorphListener(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		plugin.morphs().onJoin(player);
		// a Fabric player without the client mod could have it (and its look in First-person Model's view): told once, a
		// few seconds in, when the mod would long have answered
		String message = plugin.settings().noClientMessage;
		if (!message.isEmpty()) {
			plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
				String brand = player.getClientBrandName();
				if (player.isOnline() && !plugin.channel().isModded(player) && brand != null && brand.toLowerCase(java.util.Locale.ROOT).contains("fabric")) {
					player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(message));
				}
			}, 100L);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		plugin.abilities().forget(player);
		plugin.morphs().onQuit(player);
		plugin.channel().forget(player);
		plugin.store().unload(player.getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSwing(PlayerAnimationEvent event) {
		if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) plugin.disguises().swing(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityDeath(EntityDeathEvent event) {
		LivingEntity victim = event.getEntity();
		Player killer = victim.getKiller();
		if (killer != null && killer != victim) plugin.morphs().handleKill(killer, victim);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerDeath(PlayerDeathEvent event) {
		plugin.morphs().onDeath(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRespawn(PlayerRespawnEvent event) {
		plugin.morphs().onRespawn(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldChange(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		List<Player> previous = event.getFrom().getPlayers();
		plugin.morphs().onWorldChange(player, previous);
	}
}
