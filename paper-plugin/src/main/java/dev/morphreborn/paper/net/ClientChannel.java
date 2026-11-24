package dev.morphreborn.paper.net;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.arm.ArmColours;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSlotsMsg;
import dev.morphreborn.paper.arm.api.ArmStateMsg;
import dev.morphreborn.paper.arm.api.ArsenalMsg;
import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerMorphData;
import dev.morphreborn.paper.morph.ActiveMorph;
import dev.morphreborn.paper.survival.PassiveProtocol;
import dev.morphreborn.paper.survival.PassiveSync;
import dev.morphreborn.paper.survival.Passives;
import dev.morphreborn.paper.tips.TooltipPrefs;
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
	/** The server tick of each player's last C_ARM that was acted on: more than one every 2 ticks is dropped. */
	private final Map<UUID, Integer> lastArmRequest = new ConcurrentHashMap<>();
	/** Everyone's arm colour (C_ARM_COLOUR / S_ARM_COLOUR). */
	private final ArmColours armColours = new ArmColours();
	/** Players with a throttled arm colour request waiting to be taken (one retry task each). */
	private final java.util.Set<UUID> armColourRetry = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** What each client was last told of their passives (S_PASSIVES) and C_PASSIVE's throttle. */
	private final PassiveSync passiveSync = new PassiveSync();
	/** How many packets each client may send (PacketBudget): a modified client that floods is dropped, an honest one never reaches it. */
	private final PacketBudget budget = new PacketBudget();
	/** The most distinct newer client versions the console is told about per server run (a client may make versions up). */
	private static final int NEWER_CLIENTS_CAP = 32;

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
		lastArmRequest.remove(player.getUniqueId());
		armColours.forget(player.getUniqueId());
		passiveSync.forget(player.getUniqueId());
		budget.forget(player.getUniqueId());
	}

	/** Everyone's arm colour, as their clients sent it. */
	public ArmColours armColours() {
		return armColours;
	}

	/**
	 * The viewer's client draws arm forms: the mod, at {@link ArmProtocol#CLIENT_SINCE} or newer. Arm packets go only to
	 * these; everyone else, older modded clients included, gets the server's particle fallbacks.
	 */
	public boolean drawsArms(Player viewer) {
		if (!isModded(viewer)) return false;
		String version = clientVersion(viewer);
		return version != null && SemVer.compare(version, ArmProtocol.CLIENT_SINCE) >= 0;
	}

	/**
	 * The listener's client has the arm's sound events: the mod, at {@link ArmProtocol#ARM_SOUNDS_SINCE} or newer. Older modded
	 * clients get the stand-in sound, which every player without the mod or the pack gets too.
	 */
	public boolean hasArmSounds(Player listener) {
		if (!isModded(listener)) return false;
		String version = clientVersion(listener);
		return version != null && SemVer.compare(version, ArmProtocol.ARM_SOUNDS_SINCE) >= 0;
	}

	/**
	 * The viewer's client knows arm slots: the mod, at {@link ArmProtocol#SLOTS_SINCE} or newer (2026-11-16).
	 * S_ARM_SLOTS goes only to these; older modded clients keep working with the single arm in the held slot.
	 */
	public boolean drawsSlots(Player viewer) {
		if (!isModded(viewer)) return false;
		String version = clientVersion(viewer);
		return version != null && SemVer.compare(version, ArmProtocol.SLOTS_SINCE) >= 0;
	}

	/**
	 * The viewer's client draws the whip's moves: the mod, at {@link ArmProtocol#WHIP_MOVES_SINCE} or newer
	 * (2026-11-16). FX_WHIP_MOVE goes only to these; older ones that draw arms get the server's particles for the moves.
	 */
	public boolean drawsWhipMoves(Player viewer) {
		if (!drawsArms(viewer)) return false;
		String version = clientVersion(viewer);
		return version != null && SemVer.compare(version, ArmProtocol.WHIP_MOVES_SINCE) >= 0;
	}

	/**
	 * The viewer's client knows the new forms (8.0.0-beta.2): the mod, at {@link ArmProtocol#NEW_FORMS_SINCE} or newer. The new FX
	 * kinds (FX_SPEAR .. FX_ACCESSORY) and the accessory lists appended to S_ARM and S_ARSENAL go only to these; older clients that
	 * draw arms get the packets as they were (and, for the new FX, the server's particles).
	 */
	public boolean drawsNewForms(Player viewer) {
		if (!drawsArms(viewer)) return false;
		String version = clientVersion(viewer);
		return version != null && SemVer.compare(version, ArmProtocol.NEW_FORMS_SINCE) >= 0;
	}

	/**
	 * The player's client has the passive toggles in its upgrade tree (8.0.0-beta.2): the mod, at {@link PassiveProtocol#SINCE} or newer.
	 * S_PASSIVES goes only to these, and a C_PASSIVE from any other is ignored (an older client never sends one).
	 */
	public boolean knowsPassives(Player player) {
		return isModded(player) && PassiveProtocol.knows(clientVersion(player));
	}

	/**
	 * The viewer's client reads the pulse speed on S_ARM_COLOUR: the mod, at {@link ArmProtocol#PULSE_SPEED_SINCE} or newer
	 * (2026-11-16). Older clients get the packet without it and show every pulse at the usual speed.
	 */
	public boolean drawsPulseSpeed(Player viewer) {
		if (!drawsArms(viewer)) return false;
		String version = clientVersion(viewer);
		return version != null && SemVer.compare(version, ArmProtocol.PULSE_SPEED_SINCE) >= 0;
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!Wire.CHANNEL.equals(channel)) return;
		long now = Bukkit.getCurrentTick();
		try {
			Wire.In in = new Wire.In(message);
			int id = in.varInt();
			if (id == Wire.C_HELLO) {
				if (!gate(player, PacketBudget.Kind.HELLO, now)) return;
				handleHello(player, in);
				return;
			}
			if (!isModded(player)) return; // no handshake, no requests
			if (!gate(player, PacketBudget.Kind.ANY, now)) return;
			switch (id) {
				case Wire.C_SELECT -> {
					if (!gateKind(player, PacketBudget.Kind.LIST, now)) return;
					String main = in.string(64);
					String borrowed = in.hasMore() ? in.string(64) : null;
					if (player.isDead()) { // a dead player has no body to change; the selector closes on the death screen, so only a modified client gets here
						sendResult(player, RESULT_DENIED, "You can't morph while dead.");
						return;
					}
					plugin.morphs().requestMorph(player, main, borrowed);
				}
				case Wire.C_FAVOURITE -> {
					if (!gateKind(player, PacketBudget.Kind.LIST, now)) return;
					plugin.morphs().setFavourite(player, in.string(64), in.bool());
				}
				case Wire.C_DELETE -> {
					if (!gateKind(player, PacketBudget.Kind.LIST, now)) return;
					plugin.morphs().deleteVariant(player, in.string(64));
				}
				case Wire.C_ABILITY -> {
					if (!gateKind(player, PacketBudget.Kind.ABILITY, now)) return;
					// the ability key's event never fires for a dead player or a spectator, so an ability from one is a modified client's (the sneak state is the server's own)
					if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
					plugin.abilities().trigger(player, player.isSneaking());
				}
				case Wire.C_REQUEST_SYNC -> {
					if (!gateKind(player, PacketBudget.Kind.SYNC, now)) return;
					fullSync(player);
				}
				case Wire.C_OPEN_UPGRADES -> {
					if (!gateKind(player, PacketBudget.Kind.UPGRADES, now)) return;
					plugin.openUpgrades(player);
				}
				case Wire.C_BUY_UPGRADE -> {
					if (!gateKind(player, PacketBudget.Kind.UPGRADES, now)) return;
					String upgrade = in.string(64);
					if (!plugin.survival().active()) return;
					var result = plugin.survival().buyWithFeedback(player, upgrade);
					sendUpgrades(player, false, plugin.survival().describe(result, player, upgrade));
				}
				case Wire.C_RAGE -> {
					if (!gateKind(player, PacketBudget.Kind.ABILITY, now)) return;
					plugin.rage().trigger(player); // refuses a dead player, a spectator, a frenzy not charged or not bought itself
				}
				case Wire.C_ARM -> {
					ArmRequest request = ArmRequest.read(in);
					// The screen signals (2026-11-16) skip the throttle: a CLOSED dropped after an OPEN would leave the held
					// arm away, and the server takes a repeat of either as "look at the held slot again".
					if (skipsArmThrottle(request.action())) {
						// these skip the 2-tick throttle, but not the budget (armBudget): each can tuck, untuck or form an arm, or hold a key
						PacketBudget.Kind kind = armBudget(request.action(), request.step());
						if (kind != null && !gateKind(player, kind, now)) return;
					} else {
						int tick = (int) now;
						Integer last = lastArmRequest.get(player.getUniqueId());
						if (last != null && tick - last < ArmProtocol.SERVER_THROTTLE_TICKS && tick >= last) return; // a client sends one every 4 ticks at most
						lastArmRequest.put(player.getUniqueId(), tick);
					}
					plugin.arms().request(player, request.action(), request.form(), request.step());
				}
				case Wire.C_ARM_COLOUR -> {
					int rgb = in.varInt();
					int look = in.hasMore() ? in.varInt() : ArmProtocol.LOOK_DEFAULT; // no look: an older client
					// no pulse speed (2026-11-16): a client from before it, which pulses at the usual speed
					armColour(player, rgb, look, in.hasMore() ? in.varInt() : ArmProtocol.PULSE_SPEED_DEFAULT);
				}
				case Wire.C_PASSIVE -> {
					PassiveProtocol.Request request = PassiveProtocol.Request.read(in);
					setPassive(player, request.id(), request.on());
				}
				case Wire.C_TOOLTIPS -> {
					boolean on = in.bool();
					// a client older than the setting never sends it; one that says it did is not believed, so it can't change what the commands and the menu show
					if (TooltipPrefs.clientKnows(clientVersion(player))) plugin.setTooltips(player, on);
				}
				default -> plugin.debug("Unknown packet " + id + " from " + player.getName());
			}
		} catch (Wire.MalformedPacketException | IllegalArgumentException e) {
			// logged once per half minute per player (PacketBudget.shouldLog), so garbage sent all day is not a log of its own
			int quiet = budget.shouldLog(player.getUniqueId(), now);
			if (quiet >= 0) {
				plugin.getLogger().log(Level.WARNING, "Ignoring malformed RE:Morph packet from " + player.getName() + ": " + e.getMessage()
						+ (quiet > 0 ? " (" + quiet + " more kept quiet)" : ""));
			}
		} catch (RuntimeException e) {
			// anything else a handler threw for this player's packet stops here: the packet is dropped and nobody else is touched
			int quiet = budget.shouldLog(player.getUniqueId(), now);
			if (quiet >= 0) {
				plugin.getLogger().log(Level.WARNING, "A RE:Morph packet from " + player.getName() + " failed" + (quiet > 0 ? " (" + quiet + " more kept quiet)" : "") + ".", e);
			}
		}
	}

	/** The arm requests that skip the 2-tick throttle: the screen signals and a held key (the spyglass's zoom, the shield's use key). */
	static boolean skipsArmThrottle(int action) {
		return action == ArmProtocol.ACTION_INVENTORY_OPEN || action == ArmProtocol.ACTION_INVENTORY_CLOSED || action == ArmProtocol.ACTION_ACCESSORY_HOLD;
	}

	/**
	 * The bucket (besides the total) a throttle-skipping arm request draws from. The screen signals draw from SCREEN and a key PRESS from HOLD,
	 * so a run of right-clicks cannot use up the screen signals. A key RELEASE draws from none: it only lowers something, and one dropped for
	 * an empty bucket left the shield or the zoom up until its timeout (the total still counts it, so a flood of them is still bounded).
	 */
	static PacketBudget.@Nullable Kind armBudget(int action, int step) {
		if (action == ArmProtocol.ACTION_ACCESSORY_HOLD) return step > 0 ? PacketBudget.Kind.HOLD : null;
		return PacketBudget.Kind.SCREEN;
	}

	/**
	 * Draws one packet of {@code kind} from the player's budget, and the total with it (PacketBudget). False when the budget is spent: the
	 * packet is dropped with no answer, and the flood is noted in the log once per half minute. For C_HELLO (before the modded check) and
	 * for ANY itself; every other kind goes through {@link #gateKind}, since ANY was already drawn once at the top of the switch.
	 */
	private boolean gate(Player player, PacketBudget.Kind kind, long now) {
		return admit(player, kind, budget.allowWithTotal(player.getUniqueId(), kind, now), now);
	}

	/**
	 * Draws one packet from the kind's own bucket only: the total was drawn once already by the {@code gate(ANY)} before the switch. Drawing
	 * it here again made every real limit half the documented one (fix round 2026-11-16), enough to drop an honest player's INVENTORY_CLOSED.
	 */
	private boolean gateKind(Player player, PacketBudget.Kind kind, long now) {
		return admit(player, kind, budget.allow(player.getUniqueId(), kind, now), now);
	}

	private boolean admit(Player player, PacketBudget.Kind kind, boolean allowed, long now) {
		if (allowed) return true;
		int quiet = budget.shouldLog(player.getUniqueId(), now);
		if (quiet >= 0) {
			plugin.getLogger().warning("Dropping RE:Morph packets from " + player.getName() + ": more than the client mod ever sends (" + kind.name().toLowerCase(java.util.Locale.ROOT)
					+ ")" + (quiet > 0 ? "; " + quiet + " more kept quiet" : "") + ".");
		}
		return false;
	}

	private void handleHello(Player player, Wire.In in) {
		int protocol = in.varInt();
		// the version is the client's own word: kept only if it is a plain version (ClientVersion), else "?" like a client that sent none
		String modVersion = in.hasMore() ? ClientVersion.sanitize(in.string(64)) : ClientVersion.UNKNOWN;
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
		syncPassives(player, true);
		plugin.rage().sync(player);
		String server = plugin.getPluginMeta().getVersion();
		String message = plugin.settings().oldClientMessage;
		if (wasModded || modVersion.equals("?")) return;
		int age = dev.morphreborn.paper.SemVer.compare(modVersion, server);
		if (age < 0 && !message.isEmpty()) {
			player.sendMessage(MiniMessage.miniMessage().deserialize(message.replace("{client}", dev.morphreborn.paper.SemVer.clean(modVersion))
					.replace("{server}", dev.morphreborn.paper.SemVer.clean(server))));
		} else if (age > 0 && newerClients.size() < NEWER_CLIENTS_CAP && newerClients.add(dev.morphreborn.paper.SemVer.clean(modVersion))) {
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
				.bool(plugin.rage().enabled()) // since 7.2.0: the client shows a rage meter and sends C_RAGE only when this is true
				.bool(plugin.arms().enabled()); // since 8.0.0: the client offers the Arsenal wheel and sends C_ARM only when this is true
	}

	/** Send the server settings again (the gameplay mode changed). The client takes a repeated hello as an update. */
	public void resendHello(Player player) {
		if (!isModded(player)) return;
		send(player, hello(true));
		passiveSync.forget(player.getUniqueId()); // the client drops its toggles on a hello: the next list goes out whatever it says
	}

	/**
	 * Collection plus the morph state of everyone in the player's world, then their Arsenal and the arms of that world,
	 * then everyone's arm colours.
	 */
	public void fullSync(Player player) {
		sendCollection(player);
		for (Player other : player.getWorld().getPlayers()) {
			ActiveMorph state = plugin.morphs().state(other);
			if (state != null && state.isMorphed()) sendState(player, other, state);
		}
		plugin.arms().sync(player);
		syncArmColours(player);
	}

	// ------------------------------------------------------------------ arm colours (8.0.0)

	/**
	 * C_ARM_COLOUR: {@code player} chose {@code rgb}, {@code look} and {@code pulseSpeed} (percent). A colour out of range
	 * or a request too soon after the last (ArmColours) is dropped; a look this build does not know counts as the default
	 * and a pulse speed out of range as the nearest allowed. A change is kept in their file and sent to everyone else
	 * whose client draws arms.
	 */
	private void armColour(Player player, int rgb, int look, int pulseSpeed) {
		ArmColours.Result result = armColours.request(player.getUniqueId(), rgb, look, pulseSpeed, Bukkit.getCurrentTick());
		if (result == ArmColours.Result.THROTTLED) {
			// too soon after the last: the latest is held and taken once the window has passed (one task per player)
			UUID id = player.getUniqueId();
			if (armColourRetry.add(id)) {
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					armColourRetry.remove(id);
					int[] held = armColours.takePending(id);
					if (held != null && player.isOnline()) armColour(player, held[0], held[1], held[2]);
				}, ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS);
			}
			return;
		}
		if (result == ArmColours.Result.DROPPED) {
			if (!ArmProtocol.validColour(rgb)) plugin.debug("Ignoring arm colour " + rgb + " from " + player.getName() + ": out of range");
			return;
		}
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		Integer kept = rgb == ArmProtocol.COLOUR_DEFAULT ? null : rgb;
		int lookKept = ArmProtocol.lookOrDefault(look);
		Integer keptLook = lookKept == ArmProtocol.LOOK_DEFAULT ? null : lookKept;
		int pulseKept = ArmProtocol.clampPulseSpeed(pulseSpeed);
		Integer keptPulse = pulseKept == ArmProtocol.PULSE_SPEED_DEFAULT ? null : pulseKept;
		if (!java.util.Objects.equals(data.armColour, kept) || !java.util.Objects.equals(data.armLook, keptLook)
			|| !java.util.Objects.equals(data.armPulseSpeed, keptPulse)) {
			data.armColour = kept;
			data.armLook = keptLook;
			data.armPulseSpeed = keptPulse;
			plugin.store().markDirty(player.getUniqueId());
		}
		if (result == ArmColours.Result.CHANGED) broadcastArmColour(player);
	}

	/**
	 * On the handshake (and every full sync): the player's kept colour, look and pulse speed come back from their file
	 * until their client sends them, everyone else's go to them, and theirs go to everyone else.
	 */
	private void syncArmColours(Player player) {
		// only for players with the mod: they are the ones who choose a colour and a look, and restoring a player without
		// it only on a full sync (a reload) would change how their arm looks halfway through a session
		if (!isModded(player)) return;
		PlayerMorphData data = plugin.store().get(player.getUniqueId());
		if (data.armColour != null || data.armLook != null || data.armPulseSpeed != null) {
			armColours.restore(player.getUniqueId(),
				data.armColour != null ? data.armColour : ArmProtocol.COLOUR_DEFAULT,
				data.armLook != null ? data.armLook : ArmProtocol.LOOK_DEFAULT,
				data.armPulseSpeed != null ? data.armPulseSpeed : ArmProtocol.PULSE_SPEED_DEFAULT);
		}
		if (drawsArms(player)) {
			for (Player other : Bukkit.getOnlinePlayers()) {
				if (other != player && armColours.custom(other.getUniqueId())) send(player, armColourPacketFor(player, other));
			}
		}
		if (armColours.custom(player.getUniqueId())) broadcastArmColour(player);
	}

	/**
	 * S_ARM_COLOUR for {@code subject} to every other online player whose client draws arms: with the pulse speed for
	 * those new enough to read it, without it for the rest (two packets built once, not one per viewer).
	 */
	public void broadcastArmColour(Player subject) {
		UUID id = subject.getUniqueId();
		byte[] plain = armColourPacket(id, armColours.of(id), armColours.lookOf(id));
		byte[] withPulse = armColourPacket(id, armColours.of(id), armColours.lookOf(id), armColours.pulseSpeedOf(id));
		for (Player viewer : Bukkit.getOnlinePlayers()) {
			if (viewer != subject && drawsArms(viewer)) send(viewer, drawsPulseSpeed(viewer) ? withPulse : plain);
		}
	}

	/** The S_ARM_COLOUR about {@code subject} that {@code viewer}'s client can read (the pulse speed only if it is new enough). */
	private byte[] armColourPacketFor(Player viewer, Player subject) {
		UUID id = subject.getUniqueId();
		return drawsPulseSpeed(viewer)
			? armColourPacket(id, armColours.of(id), armColours.lookOf(id), armColours.pulseSpeedOf(id))
			: armColourPacket(id, armColours.of(id), armColours.lookOf(id));
	}

	/** S_ARM_COLOUR for a client from before the pulse speed: uuid player, varInt RGB, varInt look. */
	public static byte[] armColourPacket(UUID player, int rgb, int look) {
		return new Wire.Out(Wire.S_ARM_COLOUR).uuid(player).varInt(rgb & 0xFFFFFF).varInt(look).bytes();
	}

	/** S_ARM_COLOUR: uuid player, varInt RGB, varInt look, varInt pulse speed in percent (clients from ArmProtocol.PULSE_SPEED_SINCE). */
	public static byte[] armColourPacket(UUID player, int rgb, int look, int pulseSpeed) {
		return new Wire.Out(Wire.S_ARM_COLOUR).uuid(player).varInt(rgb & 0xFFFFFF).varInt(look).varInt(ArmProtocol.clampPulseSpeed(pulseSpeed)).bytes();
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

	/** Survival Mode status for the client's biomass bar and selector (only sent in Survival Mode), without arm details. */
	public void sendSurvival(Player player, boolean unlocked, int biomass, int capacity, boolean canMorph) {
		sendSurvival(player, unlocked, biomass, capacity, canMorph, 0, 0);
	}

	/**
	 * Survival Mode status with the arms' trailer: the biomass's thousandths ({@code milliFraction}, 0-999), so the bar
	 * shows an arm's 0.1 spends, and what drains now in milli a minute (0: nothing).
	 */
	public void sendSurvival(Player player, boolean unlocked, int biomass, int capacity, boolean canMorph, int milliFraction, int drainMilliPerMinute) {
		if (isModded(player)) send(player, survivalPacket(unlocked, biomass, capacity, canMorph, milliFraction, drainMilliPerMinute));
	}

	// ------------------------------------------------------------------ arm forms (8.0.0)

	/** S_ARM to one viewer, if their client draws arms. */
	public void sendArm(Player viewer, ArmStateMsg m) {
		if (drawsArms(viewer)) send(viewer, armStatePacket(m, drawsNewForms(viewer)));
	}

	/** S_ARM to every player in the subject's world whose client draws arms, the subject included. */
	public void broadcastArm(Player subject, ArmStateMsg m) {
		byte[] plain = armStatePacket(m, false);
		byte[] withNew = armStatePacket(m, true); // the accessory list, only for clients that know it
		for (Player viewer : subject.getWorld().getPlayers()) {
			if (drawsArms(viewer)) send(viewer, drawsNewForms(viewer) ? withNew : plain);
		}
	}

	/** S_ARM_SLOTS to its owner, if their client knows arm slots (7.6.0-beta.3). */
	public void sendArmSlots(Player owner, ArmSlotsMsg m) {
		if (drawsSlots(owner)) send(owner, armSlotsPacket(m));
	}

	/** S_ARM_FX to the origin and to players in its world within {@code radius} whose client draws arms. */
	public void broadcastArmFx(Player origin, ArmFxMsg m, double radius) {
		broadcastArmFx(origin, m, radius, v -> true);
	}

	/**
	 * As {@link #broadcastArmFx(Player, ArmFxMsg, double)}, to the viewers {@code to} accepts. A whip move (FX_WHIP_MOVE) goes
	 * only to clients that know it, and so does each of the new forms' effects (ArmProtocol.newFormsFx), whatever {@code to} says.
	 */
	public void broadcastArmFx(Player origin, ArmFxMsg m, double radius, java.util.function.Predicate<Player> to) {
		byte[] bytes = armFxPacket(m);
		double far = radius * radius;
		boolean whipMove = m.kind() == ArmProtocol.FX_WHIP_MOVE;
		boolean newForms = ArmProtocol.newFormsFx(m.kind());
		for (Player viewer : origin.getWorld().getPlayers()) {
			if (newForms ? !drawsNewForms(viewer) : whipMove ? !drawsWhipMoves(viewer) : !drawsArms(viewer)) continue;
			if (!to.test(viewer)) continue;
			if (viewer == origin || viewer.getLocation().distanceSquared(origin.getLocation()) <= far) send(viewer, bytes);
		}
	}

	/** S_ARSENAL to its owner, if their client draws arms. */
	public void sendArsenal(Player owner, ArsenalMsg m) {
		if (drawsArms(owner)) send(owner, arsenalPacket(m, drawsNewForms(owner)));
	}

	/**
	 * C_ARM's fields: the action (ArmProtocol.ACTION_*), the form id ("" when absent) and, only for
	 * {@link ArmProtocol#ACTION_ACCESSORY_MODE}, the step (+1 or -1) as a varInt after the form string, and for
	 * {@link ArmProtocol#ACTION_ACCESSORY_HOLD} the key's state (1 held, 0 let go, in the same field); 0 for every other action.
	 */
	public record ArmRequest(int action, String form, int step) {
		public ArmRequest(int action, String form) {
			this(action, form, 0);
		}

		public static ArmRequest read(Wire.In in) {
			int action = in.varInt();
			String form = in.hasMore() ? in.string(ArmProtocol.MAX_FORM_ID) : "";
			// a step is read only for the one action that has it, and a missing one counts as 0 (a no-op)
			// (a held key's 1 or 0, ACTION_ACCESSORY_HOLD, rides in the same field: 1 held, 0 let go)
			boolean stepped = action == ArmProtocol.ACTION_ACCESSORY_MODE || action == ArmProtocol.ACTION_ACCESSORY_HOLD;
			int step = stepped && in.hasMore() ? Integer.signum(in.varInt()) : 0;
			return new ArmRequest(action, form, step);
		}
	}

	public static byte[] survivalPacket(boolean unlocked, int biomass, int capacity, boolean canMorph, int milliFraction, int drainMilliPerMinute) {
		return new Wire.Out(Wire.S_SURVIVAL).bool(unlocked).varInt(biomass).varInt(capacity).bool(canMorph)
				.varInt(Math.clamp(milliFraction, 0, 999)) // since 8.0.0, after the fields older clients read
				.varInt(Math.max(0, drainMilliPerMinute)).bytes();
	}

	/** S_ARM as clients before {@link ArmProtocol#NEW_FORMS_SINCE} read it: no accessory list. */
	public static byte[] armStatePacket(ArmStateMsg m) {
		return armStatePacket(m, false);
	}

	/**
	 * S_ARM; with {@code withAccessories} (clients at ArmProtocol.NEW_FORMS_SINCE), after the lantern brightness: varInt count,
	 * then per accessory that is on, in switch-on order: string id, bool on, varInt level, varInt arg (accessory-specific: the
	 * shield raised 0 or 1, the spyglass zooming 0 or 1, the lantern's brightness). An accessory not listed is off.
	 */
	public static byte[] armStatePacket(ArmStateMsg m, boolean withAccessories) {
		Wire.Out out = new Wire.Out(Wire.S_ARM).uuid(m.subject()).varInt(m.entityId()).string(text(m.form())).string(text(m.fromForm()))
				.varInt(Math.clamp(m.level(), 0, 3)).varInt(m.state()).varInt(Math.max(0, m.time())).varInt(Math.max(0, m.duration()))
				.varInt(m.slot() < 0 ? 0 : m.slot() + 1).varInt(m.why()).bool(m.lanternLit()).varInt(Math.max(0, m.lanternLevel()))
				.varInt(Math.max(0, m.lanternTime())).varInt(Math.max(0, m.recallTicks()))
				.varInt(Math.clamp(m.lanternBright(), 0, 5)); // since 8.0.0, after the fields older clients read
		if (withAccessories) {
			out.varInt(m.accessories().size());
			for (ArmStateMsg.Accessory a : m.accessories()) out.string(text(a.id())).bool(a.on()).varInt(Math.clamp(a.level(), 0, 3)).varInt(Math.max(0, a.arg()));
		}
		return out.bytes();
	}

	/**
	 * S_ARM_SLOTS: varInt count, then per binding varInt slot (0 to 8), string form, varInt tier (1 to 3); then bool held,
	 * and when held: string item id, varInt count, bool glint.
	 */
	public static byte[] armSlotsPacket(ArmSlotsMsg m) {
		Wire.Out out = new Wire.Out(Wire.S_ARM_SLOTS).varInt(m.slots().size());
		for (ArmSlotsMsg.Slot s : m.slots()) out.varInt(Math.clamp(s.slot(), 0, 8)).string(text(s.form())).varInt(Math.clamp(s.tier(), 1, 3));
		out.bool(m.held());
		if (m.held()) out.string(m.heldItem().isEmpty() ? "minecraft:air" : m.heldItem()).varInt(Math.max(0, m.heldCount())).bool(m.heldGlint());
		return out.bytes();
	}

	public static byte[] armFxPacket(ArmFxMsg m) {
		Wire.Out out = new Wire.Out(Wire.S_ARM_FX).varInt(m.origin()).varInt(m.kind()).varInt(m.level()).varInt(m.ticks()).varInt(m.seed());
		int[] ids = m.ids();
		out.varInt(ids.length);
		for (int id : ids) out.varInt(id);
		long[] positions = m.positions();
		out.varInt(positions.length);
		for (long p : positions) out.longValue(p);
		return out.varInt(m.arg()).bytes();
	}

	/** S_ARSENAL as clients before {@link ArmProtocol#NEW_FORMS_SINCE} read it: no accessory list. */
	public static byte[] arsenalPacket(ArsenalMsg m) {
		return arsenalPacket(m, false);
	}

	/**
	 * S_ARSENAL; with {@code withAccessories} (clients at ArmProtocol.NEW_FORMS_SINCE), after the flare's fields: varInt count, then
	 * per built accessory in ArmForm.ACCESSORIES order (the lantern included): string id, varInt owned level (0: not owned), bool on.
	 */
	public static byte[] arsenalPacket(ArsenalMsg m, boolean withAccessories) {
		Wire.Out out = new Wire.Out(Wire.S_ARSENAL).bool(m.usable()).string(text(m.whyNot())).bool(m.free()).varInt(Math.max(0, m.perBlockMilli()))
				.varInt(Math.max(0, m.perHitMilli())).varInt(Math.max(0, m.perBoltMilli())).varInt(Math.max(0, m.drainMilliPerMinute()))
				.varInt(Math.max(0, m.lanternDrainMilliPerMinute())).varInt(m.entries().size());
		for (ArsenalMsg.Entry e : m.entries()) {
			out.string(text(e.id())).string(text(e.name())).varInt(Math.max(0, e.level())).varInt(Math.max(0, e.maxLevel())).string(text(e.specialName()))
					.varInt(Math.max(0, e.specialCostMilli())).varInt(Math.max(0, e.cooldownTicks())).varInt(Math.max(0, e.cooldownLeftTicks()));
		}
		// anything added later goes after the flare's fields, never inside the entries
		out.string(text(m.lastForm())).varInt(Math.max(0, m.lanternLevel())).bool(m.lanternLit()).varInt(Math.max(0, m.flareCostMilli()))
				.varInt(Math.max(0, m.flareCooldownTicks())).varInt(Math.max(0, m.flareCooldownLeftTicks()));
		if (withAccessories) {
			out.varInt(m.accessories().size());
			for (ArsenalMsg.AccessoryEntry a : m.accessories()) out.string(text(a.id())).varInt(Math.clamp(a.level(), 0, 3)).bool(a.on());
		}
		return out.bytes();
	}

	private static String text(@Nullable String s) {
		return s == null ? "" : s;
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
	/**
	 * {@code legs} (at the end: older mods stop before it): what the player's tendril legs are doing, FrenzyHover.LEGS_*
	 * (8.0.0: none, hovering, lowering the player, drawing back). 7.4.0 sent a bool, hovering or not; a varInt this small
	 * is the same one byte, so mods from 7.4.0 read 1 to 3 as hovering and 0 as not.
	 */
	public void broadcastRageDrain(Player origin, java.util.List<Integer> targets, int legs) {
		Wire.Out out = new Wire.Out(Wire.S_RAGE_DRAIN).varInt(origin.getEntityId()).varInt(targets.size());
		for (int id : targets) out.varInt(id);
		out.varInt(legs);
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
		// the passives' states go with the tree: forced when it opens, otherwise (a purchase) as any other change, coalesced
		syncPassives(player, open);
	}

	// ------------------------------------------------------------------ passive toggles (8.0.0-beta.2)

	/** {@link #syncPassives(Player, boolean)} without force: the plugin's passive run calls this every half second for each player. */
	public void syncPassives(Player player) {
		syncPassives(player, false);
	}

	/**
	 * S_PASSIVES to {@code player} if their client has the toggles and Survival Mode is the mode. {@code force}: the handshake, the tree
	 * opening or an answer to C_PASSIVE, sent whether or not anything changed; otherwise only a changed list goes, and not sooner than
	 * {@link PassiveProtocol#SEND_MIN_TICKS} after the last one (PassiveSync), so a state that flips every run is a few packets a second at most.
	 */
	public void syncPassives(Player player, boolean force) {
		if (!knowsPassives(player) || !plugin.survival().active()) return;
		var states = plugin.passives().states(player);
		if (passiveSync.shouldSend(player.getUniqueId(), states, Bukkit.getCurrentTick(), force)) send(player, PassiveProtocol.statesPacket(states));
	}

	/**
	 * C_PASSIVE: the same switch as {@code /morph passive <id> on|off} ({@code Passives.set}). Ignored without the handshake and the
	 * version (an older client never sends it), outside Survival Mode, for an id that is no passive and for one that is not bought, and
	 * when one came less than 2 ticks after the last that was acted on. A refusal of an id (not a passive, not bought) still answers with the
	 * real states at once, so a client that guessed wrong shows the truth; one dropped by the throttle only gets the ordinary coalesced
	 * sync (a changed list, at most every {@link PassiveProtocol#SEND_MIN_TICKS} ticks), so a flood is not answered packet for packet.
	 */
	private void setPassive(Player player, String id, boolean on) {
		if (!knowsPassives(player) || !plugin.survival().active()) return;
		boolean allowed = passiveSync.allowToggle(player.getUniqueId(), Bukkit.getCurrentTick());
		if (allowed && Passives.ALL.contains(id)) plugin.passives().set(player, id, on);
		syncPassives(player, allowed);
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
