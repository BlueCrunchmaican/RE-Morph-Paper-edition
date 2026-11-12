package dev.morphreborn.paper.net;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerMorphData;
import dev.morphreborn.paper.morph.ActiveMorph;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jspecify.annotations.Nullable;

/**
 * Server side of the Fabric companion protocol (docs/PROTOCOL.md). A player counts as "modded" only after a HELLO
 * with our exact protocol version; everyone else is treated as a vanilla client and sees Lib's Disguises.
 * Every packet is decoded defensively: a malformed packet is logged once and ignored, never trusted.
 */
public final class ClientChannel implements PluginMessageListener {
	public static final int RESULT_OK = 0;
	public static final int RESULT_DENIED = 1;
	public static final int RESULT_UNKNOWN_MORPH = 2;
	public static final int RESULT_BUSY = 3;

	private final MorphRebornPlugin plugin;
	private final Set<UUID> modded = ConcurrentHashMap.newKeySet();
	private final Map<UUID, String> versions = new ConcurrentHashMap<>();
	/** Client mod versions newer than this plugin that the console was told about (once each per server run). */
	private final java.util.Set<String> newerClients = ConcurrentHashMap.newKeySet();

	public ClientChannel(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	public void register() {
		Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, Wire.CHANNEL);
		Bukkit.getMessenger().registerIncomingPluginChannel(plugin, Wire.CHANNEL, this);
	}

	public void unregister() {
		Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, Wire.CHANNEL, this);
		Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, Wire.CHANNEL);
	}

	public boolean isModded(Player player) {
		return modded.contains(player.getUniqueId());
	}

	public @Nullable String clientVersion(Player player) {
		return versions.get(player.getUniqueId());
	}

	public List<Player> moddedPlayers() {
		return Bukkit.getOnlinePlayers().stream().filter(this::isModded).map(p -> (Player) p).toList();
	}

	public void forget(Player player) {
		modded.remove(player.getUniqueId());
		versions.remove(player.getUniqueId());
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!Wire.CHANNEL.equals(channel)) return;
		try {
			Wire.In in = new Wire.In(message);
			int id = in.varInt();
			if (id == Wire.C_HELLO) {
				handleHello(player, in);
				return;
			}
			if (!isModded(player)) return; // no handshake, no requests
			switch (id) {
				case Wire.C_SELECT -> {
					String main = in.string(64);
					plugin.morphs().requestMorph(player, main, in.hasMore() ? in.string(64) : null);
				}
				case Wire.C_FAVOURITE -> plugin.morphs().setFavourite(player, in.string(64), in.bool());
				case Wire.C_DELETE -> plugin.morphs().deleteVariant(player, in.string(64));
				case Wire.C_ABILITY -> plugin.abilities().trigger(player, player.isSneaking());
				case Wire.C_REQUEST_SYNC -> fullSync(player);
				case Wire.C_OPEN_UPGRADES -> plugin.openUpgrades(player);
				case Wire.C_BUY_UPGRADE -> {
					String upgrade = in.string(64);
					if (!plugin.survival().active()) return;
					var result = plugin.survival().buyWithFeedback(player, upgrade);
					sendUpgrades(player, false, plugin.survival().describe(result, player, upgrade));
				}
				case Wire.C_RAGE -> plugin.rage().trigger(player);
				default -> plugin.debug("Unknown packet " + id + " from " + player.getName());
			}
		} catch (Wire.MalformedPacketException | IllegalArgumentException e) {
			plugin.getLogger().log(Level.WARNING, "Ignoring malformed RE:Morph packet from " + player.getName() + ": " + e.getMessage());
		}
	}

	private void handleHello(Player player, Wire.In in) {
		int protocol = in.varInt();
		String modVersion = in.hasMore() ? in.string(64) : "?";
		boolean accepted = protocol == Wire.PROTOCOL_VERSION;
		send(player, hello(accepted));
		if (!accepted) {
			plugin.getLogger().info(player.getName() + " has RE:Morph client protocol " + protocol + " (server speaks " + Wire.PROTOCOL_VERSION
					+ "); treating them as a vanilla client.");
			player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.settings().outdatedMessage));
			return;
		}
		boolean wasModded = !modded.add(player.getUniqueId());
		versions.put(player.getUniqueId(), modVersion);
		plugin.debug(player.getName() + " joined with RE:Morph client " + modVersion);
		if (!wasModded) {
			plugin.morphs().onClientModded(player);
		}
		fullSync(player);
		sendTraits(player, plugin.abilities().clientTraits(player));
		plugin.survival().sync(player);
		plugin.rage().sync(player);
		String server = plugin.getPluginMeta().getVersion();
		String message = plugin.settings().oldClientMessage;
		if (wasModded || modVersion.equals("?")) return;
		int age = dev.morphreborn.paper.SemVer.compare(modVersion, server);
		if (age < 0 && !message.isEmpty()) {
			player.sendMessage(MiniMessage.miniMessage().deserialize(message.replace("{client}", dev.morphreborn.paper.SemVer.clean(modVersion))
					.replace("{server}", dev.morphreborn.paper.SemVer.clean(server))));
		} else if (age > 0 && newerClients.add(dev.morphreborn.paper.SemVer.clean(modVersion))) {
			// The mod tells the player itself; the console hears it once per version.
			plugin.getLogger().info(player.getName() + " has RE:Morph client mod " + dev.morphreborn.paper.SemVer.clean(modVersion)
					+ ", newer than this server's " + dev.morphreborn.paper.SemVer.clean(server) + ". A newer plugin is probably out: "
					+ dev.morphreborn.paper.UpdateChecker.MODRINTH_PAGE + " or " + dev.morphreborn.paper.UpdateChecker.GITHUB_PAGE);
		}
	}

	private Wire.Out hello(boolean accepted) {
		return new Wire.Out(Wire.S_HELLO)
				.varInt(Wire.PROTOCOL_VERSION)
				.bool(accepted)
				.string(accepted ? "" : plugin.settings().outdatedMessage)
				.varInt(plugin.settings().transitionTicks)
				.bool(plugin.settings().allowDelete)
				.bool(plugin.settings().selectorEnabled)
				.bool(plugin.abilities().anyActive())
				.string(plugin.settings().nameTagMode.name())
				.string(plugin.mode().id())
				.string(plugin.getPluginMeta().getVersion()) // since 6.6.0; older clients stop reading before it
				.bool(plugin.settings().multimorphEnabled) // since 7.0.0
				.bool(plugin.rage().enabled()); // since 7.2.0: the client shows a rage meter and sends C_RAGE only when this is true
	}

	/** Send the server settings again (the gameplay mode changed). The client takes a repeated hello as an update. */
	public void resendHello(Player player) {
		if (isModded(player)) send(player, hello(true));
	}

	/** Collection plus the morph state of everyone in the player's world. */
	public void fullSync(Player player) {
		sendCollection(player);
		for (Player other : player.getWorld().getPlayers()) {
			ActiveMorph state = plugin.morphs().state(other);
			if (state != null && state.isMorphed()) sendState(player, other, state);
		}
	}

	public void sendCollection(Player player) {
		if (!isModded(player)) return;
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		Wire.Out out = new Wire.Out(Wire.S_COLLECTION);
		out.varInt(data.morphs.size());
		for (Map.Entry<String, List<PlayerMorphData.StoredVariant>> e : data.morphs.entrySet()) {
			out.string(e.getKey());
			out.varInt(e.getValue().size());
			for (PlayerMorphData.StoredVariant v : e.getValue()) {
				out.ref(PlayerMorphData.toRef(e.getKey(), v));
				out.bool(v.favourite);
			}
		}
		// Since 7.2.0, after the rows (older clients stop reading before it), for the selector's room counters: the
		// most kinds of creature and the most looks of one the collection may hold, each plus 1 (0: no limit), then for
		// each row above, in the same order, whether its creature comes in more than one look.
		out.varInt(plugin.mode().typeCapacity(player) + 1);
		out.varInt(plugin.morphs().lookLimit(player) + 1);
		for (Map.Entry<String, List<PlayerMorphData.StoredVariant>> e : data.morphs.entrySet()) {
			out.bool(plugin.morphs().hasLooks(player, e.getKey(), e.getValue().size()));
		}
		send(player, out);
		plugin.survival().sendCosts(player);
	}

	/** Survival: what each morph in the collection costs, by variant id (empty: morphing is free). */
	public void sendCosts(Player player, Map<String, Integer> costs) {
		if (!isModded(player)) return;
		Wire.Out out = new Wire.Out(Wire.S_COSTS).varInt(costs.size());
		for (Map.Entry<String, Integer> e : costs.entrySet()) out.string(e.getKey()).varInt(e.getValue());
		out.varInt(plugin.survival().multimorphExtraPercent()); // since 7.0.0: a multimorph's extra, for the price panel
		send(player, out);
	}

	public void sendState(Player viewer, Player subject, ActiveMorph state) {
		if (!isModded(viewer)) return;
		send(viewer, new Wire.Out(Wire.S_MORPH_STATE)
				.uuid(subject.getUniqueId())
				.varInt(subject.getEntityId())
				.optionalRef(state.prev)
				.optionalRef(state.look()) // a vanilla conversion shows as its creature, with the form's variant id
				.varInt(state.time)
				.varInt(state.duration)
				.string(state.overlay)
				.optionalRef(plugin.borrowedHeads().borrowed(subject))); // multimorph; last, so older clients never read it
	}

	/** Push a state change to every modded player who can be in render range (same world) and to the subject. */
	public void broadcastState(Player subject, ActiveMorph state) {
		for (Player viewer : subject.getWorld().getPlayers()) sendState(viewer, subject, state);
	}

	public void broadcastForget(Player subject, Collection<? extends Player> viewers) {
		for (Player viewer : viewers) {
			if (isModded(viewer)) send(viewer, new Wire.Out(Wire.S_FORGET).uuid(subject.getUniqueId()));
		}
	}

	/** {@code newMorph}: a new morph rather than biomass only. Sent last, so clients that predate it ignore it. */
	public void sendAcquire(Player origin, Entity acquired, boolean newMorph) {
		Wire.Out out = new Wire.Out(Wire.S_ACQUIRE).varInt(origin.getEntityId()).varInt(acquired.getEntityId()).bool(newMorph);
		byte[] bytes = out.bytes();
		send(origin, bytes);
		for (Player viewer : acquired.getTrackedBy()) {
			if (viewer != origin) send(viewer, bytes);
		}
	}

	/**
	 * Tell viewers that {@code subject} just used an ability, so their morph plays the mob's animation: modded viewers
	 * through S_ANIMATE, unmodded ones through the disguise.
	 */
	public void broadcastAnimation(Player subject, String ability) {
		byte[] bytes = new Wire.Out(Wire.S_ANIMATE).uuid(subject.getUniqueId()).string(ability).bytes();
		for (Player viewer : subject.getWorld().getPlayers()) send(viewer, bytes);
		plugin.disguises().animate(subject, ability);
	}

	/** Tells a modded player which traits of their current form their client runs itself (Wire.TRAIT_*). */
	public void sendTraits(Player player, int traits) {
		if (isModded(player)) send(player, new Wire.Out(Wire.S_TRAITS).varInt(traits));
	}

	/** Survival Mode status for the client's biomass bar and selector (only sent in Survival Mode). */
	public void sendSurvival(Player player, boolean unlocked, int biomass, int capacity, boolean canMorph) {
		if (isModded(player)) send(player, new Wire.Out(Wire.S_SURVIVAL).bool(unlocked).varInt(biomass).varInt(capacity).bool(canMorph));
	}

	/**
	 * The rage meter (S_RAGE, since 7.2.0; older clients skip the unknown packet): whether the player can rage, the hits
	 * within the window of those needed, the wait left of the whole wait in ticks (0: none), whether they are raging now,
	 * and the rage's ticks left of its whole length.
	 */
	public void sendRage(Player player, boolean available, int hits, int needed, int waitLeft, int wait, boolean raging, int rageLeft, int rageLength) {
		if (isModded(player)) {
			send(player, new Wire.Out(Wire.S_RAGE).bool(available).varInt(hits).varInt(needed).varInt(waitLeft).varInt(wait).bool(raging)
					.varInt(rageLeft).varInt(rageLength));
		}
	}

	/** A rage's tendrils (S_RAGE_DRAIN): the creatures {@code origin} drains now, to players with the mod nearby (empty: none). */
	public void broadcastRageDrain(Player origin, java.util.List<Integer> targets) {
		Wire.Out out = new Wire.Out(Wire.S_RAGE_DRAIN).varInt(origin.getEntityId()).varInt(targets.size());
		for (int id : targets) out.varInt(id);
		byte[] bytes = out.bytes();
		for (Player viewer : origin.getWorld().getPlayers()) {
			if (isModded(viewer) && (viewer == origin || viewer.getLocation().distanceSquared(origin.getLocation()) <= 96 * 96)) send(viewer, bytes);
		}
	}

	/** The upgrade tree for the client's own screen; {@code open} opens it, otherwise an open one refreshes. */
	public void sendUpgrades(Player player, boolean open, String message) {
		if (!isModded(player)) return;
		var survival = plugin.survival();
		var views = survival.views(player);
		Wire.Out out = new Wire.Out(Wire.S_UPGRADES).bool(open).string(message).bool(survival.isUnlocked(player))
				.varInt((int) Math.floor(survival.data(player).biomass)).varInt((int) Math.floor(survival.capacity(player))).varInt(views.size());
		for (var v : views) {
			out.string(v.id()).string(v.name()).string(v.description()).string(v.icon()).string(v.parent()).varInt(v.level()).varInt(v.maxLevel())
					.string(v.now()).string(v.next()).varInt(v.cost() + 1).string(v.missing()).bool(v.affordable());
		}
		send(player, out);
	}

	public void sendUnlock(Player player, MorphRef ref) {
		send(player, new Wire.Out(Wire.S_UNLOCK).ref(ref));
	}

	public void sendResult(Player player, int code, String message) {
		send(player, new Wire.Out(Wire.S_RESULT).varInt(code).string(message));
	}

	private void send(Player player, Wire.Out out) {
		send(player, out.bytes());
	}

	private void send(Player player, byte[] bytes) {
		if (!isModded(player) && !(bytes.length > 0 && bytes[0] == Wire.S_HELLO)) return;
		if (!player.isOnline()) return;
		player.sendPluginMessage(plugin, Wire.CHANNEL, bytes);
	}
}
