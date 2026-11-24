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
	/**
	 * How long after the client's game has loaded the client-mod message waits for the mod's answer (ticks); much longer when
	 * the server only counted it loaded because it took over 3 s (isTimeout): that client is still busy, and its mod's
	 * channel and hello come late (the rig, 2026-11-16: a shader pack compiling held one up for 9 s).
	 */
	private static final long NO_CLIENT_TICKS = 100L, NO_CLIENT_SLOW_TICKS = 400L;
	/** Players who joined and are still to be told about the client mod, once their game has loaded (onClientLoaded). */
	private final java.util.Set<java.util.UUID> toTell = new java.util.HashSet<>();

	public MorphListener(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		plugin.shulkerLock().onJoin(player);
		plugin.morphs().onJoin(player);
		if (!plugin.settings().noClientMessage.isEmpty()) toTell.add(player.getUniqueId());
	}

	/**
	 * A Fabric player without the client mod could have it (and its look in First-person Model's view): told once per join,
	 * a few seconds after their game has loaded the world, when the mod would long have answered. Counted from the client's
	 * own "loaded" (not from the server's join, as it was until 8.0.0), and never to a client that registered the mod's
	 * channel: a client still loading, for instance compiling a shader pack, answers late, and a modded one was told it had
	 * no mod (seen on the rig 2026-11-16, a 9 s load).
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onClientLoaded(io.papermc.paper.event.player.PlayerClientLoadedWorldEvent event) {
		Player player = event.getPlayer();
		if (!toTell.remove(player.getUniqueId())) return; // once per join: it fires again on respawn and world changes
		String message = plugin.settings().noClientMessage;
		if (message.isEmpty()) return;
		plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
			String brand = player.getClientBrandName();
			// a client that registered the mod's channel has the mod, even if its hello is still on the way (a client busy
			// loading answers late; the server counts it "loaded" after 3 s whether it is or not)
			boolean hasMod = plugin.channel().isModded(player) || player.getListeningPluginChannels().contains(dev.morphreborn.paper.net.Wire.CHANNEL);
			if (player.isOnline() && !hasMod && brand != null && brand.toLowerCase(java.util.Locale.ROOT).contains("fabric")) {
				player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(message));
			}
		}, event.isTimeout() ? NO_CLIENT_SLOW_TICKS : NO_CLIENT_TICKS);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		// first: a shulker body clinging to a block lets go, so the player file is saved with their own flight
		plugin.shulkerLock().forget(player);
		plugin.abilities().forget(player);
		plugin.morphs().onQuit(player);
		plugin.channel().forget(player);
		plugin.store().unload(player.getUniqueId());
		toTell.remove(player.getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSwing(PlayerAnimationEvent event) {
		if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) plugin.disguises().swing(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityDeath(EntityDeathEvent event) {
		LivingEntity victim = event.getEntity();
		if (plugin.shulkerLock().isHelper(victim)) return; // a shulker player's hidden helper is no shulker to unlock
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
