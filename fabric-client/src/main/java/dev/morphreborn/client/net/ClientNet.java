package dev.morphreborn.client.net;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.Collection;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.gui.SelectorHud;
import dev.morphreborn.client.morph.ShulkerLock;
import dev.morphreborn.client.render.MorphWorldRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Client side of the protocol. The client is purely presentation: it sends requests and applies whatever state the
 * server sends back; it never decides morph ownership or state on its own.
 */
public final class ClientNet {
	private static final SystemToast.SystemToastId UNLOCK_TOAST = new SystemToast.SystemToastId(5000L);
	/** Give the server this many ticks to register our channel after joining before deciding it has no RE:Morph. */
	private static final int HELLO_TIMEOUT_TICKS = 200;

	private static boolean active;
	private static boolean helloSent;
	private static int ticksSinceJoin = -1;
	private static boolean warnedInactive;
	private static int serverTransitionTicks = 100;
	private static boolean deleteAllowed = true;
	private static boolean selectorAllowed = true;
	/** The server lets players combine morphs (multimorph.enabled); servers before 7.0.0 don't say, and can't. */
	private static boolean multimorphAllowed;
	private static boolean abilitiesAvailable;
	/** Server's appearance.name-tags: MORPH, PLAYER or HIDDEN. */
	private static String nameTagMode = "MORPH";
	/** "classic" or "survival", from the server's hello. */
	private static String gameplayMode = "classic";
	// Survival Mode status (S_SURVIVAL)
	private static boolean biomassUnlocked;
	private static int biomass;
	private static int biomassCapacity;
	/** Survival: what morphing into each variant of the collection costs, by variant id (empty when free). */
	private static final java.util.Map<String, Integer> morphCosts = new java.util.HashMap<>();
	private static boolean survivalCanMorph;
	/** Client tick of the last biomass change, for the "when it changes" biomass bar. */
	private static long biomassChangedAt = -1000;
	/** The last biomass gain and when it came, for the bar's "+21". */
	private static int lastGain;
	private static long lastGainAt = -1000;
	private static long tick;

	public static String nameTagMode() {
		return nameTagMode;
	}

	private ClientNet() {}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(MorphPayload.TYPE, MorphPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MorphPayload.TYPE, MorphPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(MorphPayload.TYPE, (payload, context) -> handle(payload.data()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			reset();
			ticksSinceJoin = 0;
			dev.morphreborn.client.UpdateCheck.onConnect();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			reset();
			ticksSinceJoin = -1;
			dev.morphreborn.client.UpdateCheck.onDisconnect();
		});
	}

	private static void reset() {
		active = false;
		helloSent = false;
		warnedInactive = false;
		MorphClient.COLLECTION.clear();
		MorphClient.STATES.clear();
		MorphClient.ENTITIES.clear();
		MorphWorldRenderer.clearEffects();
		SelectorHud.INSTANCE.reset();
		dev.morphreborn.client.morph.ClientTraits.set(0);
		gameplayMode = "classic";
		biomassUnlocked = false;
		biomass = 0;
		biomassCapacity = 0;
		morphCosts.clear();
		survivalCanMorph = false;
		rageOffered = rageAvailable = raging = rageWasWaiting = false;
		rageHits = rageHitsNeeded = rageWait = rageLength = 0;
		rageWaitEndsAt = rageEndsAt = 0;
		dev.morphreborn.client.render.RageTethers.clear();
		armsOffered = false;
		TOOLTIP_SYNC.reset();
		PASSIVES.clear();
		TREE_ANSWERS.reset();
		biomassMilliFraction = 0;
		drainMilliPerMinute = 0;
		lastSpendMilli = 0;
		lastSpendAt = -1000;
		lastArmRequestAt = -1000;
		dev.morphreborn.client.arm.Arms.clear();
		dev.morphreborn.client.arm.ArmColours.clear();
		dev.morphreborn.client.arm.ArmSlots.clear();
		// recoloured skins are rebuilt on use, from the textures loaded then (a resource pack change); on the render thread
		net.minecraft.client.Minecraft.getInstance().execute(dev.morphreborn.client.arm.render.ArmTint::clearSkins);
		dev.morphreborn.client.arm.ArmClientModules.resetAll();
	}

	/** Called every client tick: sends HELLO once Paper has advertised our channel. */
	public static void tick() {
		tick++;
		if (active) {
			// your arm colour, once after the handshake and whenever the setting changes (ArmColours throttles it)
			var mc = net.minecraft.client.Minecraft.getInstance();
			Wire.Out colour = dev.morphreborn.client.arm.ArmColours.tick(mc.player == null ? null : mc.player.getUUID(), tick,
					serverAtLeast(dev.morphreborn.client.arm.ArmProtocol.PULSE_SPEED_SINCE));
			if (colour != null) send(colour);
			// the Tooltips setting, once after the handshake and whenever it changes, so the descriptions the server builds follow it
			Boolean tooltips = TOOLTIP_SYNC.due(ClientConfig.INSTANCE.tooltips, active, serverVersion);
			if (tooltips != null) send(new Wire.Out(Wire.C_TOOLTIPS).bool(tooltips));
		}
		if (ticksSinceJoin < 0 || helloSent) return;
		ticksSinceJoin++;
		if (ClientPlayNetworking.canSend(MorphPayload.TYPE)) {
			helloSent = true;
			String version = FabricLoader.getInstance().getModContainer(MorphClient.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
			send(new Wire.Out(Wire.C_HELLO).varInt(Wire.PROTOCOL_VERSION).string(version));
		} else if (ticksSinceJoin > HELLO_TIMEOUT_TICKS) {
			ticksSinceJoin = -1;
			MorphClient.LOGGER.info("Server does not run RE:Morph; the selector stays disabled.");
			dev.morphreborn.client.UpdateCheck.onOtherServer();
		}
	}

	/** What the server was last told of the Tooltips setting (C_TOOLTIPS). */
	private static final dev.morphreborn.client.tips.TooltipSync TOOLTIP_SYNC = new dev.morphreborn.client.tips.TooltipSync();

	/** The server plugin's version, sent since 6.6.0 ("" from older servers). */
	public static String serverVersion = "";

	public static boolean isActive() {
		return active;
	}

	/** In Survival Mode the selector only opens once the player has bought the Morph Ability. */
	public static boolean selectorAllowed() {
		return selectorAllowed && (!isSurvival() || survivalCanMorph);
	}

	public static boolean isSurvival() {
		return active && gameplayMode.equals("survival");
	}

	public static boolean biomassUnlocked() {
		return biomassUnlocked;
	}

	/** Biomass a morph into this variant costs, or 0 when morphing is free. */
	public static int morphCost(String variantId) {
		return morphCosts.getOrDefault(variantId, 0);
	}

	/** Survival: percent on top of a multimorph's price (survival.yml morph-cost.multimorph-extra). */
	private static int multimorphExtraPercent;

	/** A multimorph's price: the forms that are new to the body ({@code cost}) and the extra on top. */
	public static int multimorphCost(int cost) {
		return cost <= 0 ? 0 : cost + (int) Math.ceil(cost * multimorphExtraPercent / 100.0 - 1e-9);
	}

	public static int biomass() {
		return biomass;
	}

	public static int biomassCapacity() {
		return biomassCapacity;
	}

	public static boolean survivalCanMorph() {
		return survivalCanMorph;
	}

	public static long ticksSinceBiomassChange() {
		return tick - biomassChangedAt;
	}

	public static int lastGain() {
		return lastGain;
	}

	public static long ticksSinceGain() {
		return tick - lastGainAt;
	}

	/** Ask the server for its upgrade menu (a chest menu, the same one players without the mod use). */
	public static void openUpgrades() {
		send(new Wire.Out(Wire.C_OPEN_UPGRADES));
	}

	/** Which S_UPGRADES answers belong to the open tree's own refresh requests, not to a key press (fix round, 2026-11-16). */
	private static final dev.morphreborn.client.gui.TreeAnswerGate TREE_ANSWERS = new dev.morphreborn.client.gui.TreeAnswerGate();

	/**
	 * The open tree asks for a fresh copy of itself. Same packet as {@link #openUpgrades()}, but its answer is counted: if the screen is gone
	 * when it arrives, it is dropped instead of opening the tree again.
	 */
	public static void refreshUpgrades() {
		TREE_ANSWERS.sent(tick);
		openUpgrades();
	}

	public static void message(Component text) {
		overlayOrChat(text);
	}

	/**
	 * Shows {@code text} on the action bar, or in chat when it is wider than the screen: the action bar is one centred line that is clipped at the
	 * screen's edges (a 60-character server message was cut at 320 to 430 scaled px wide), and chat wraps it.
	 */
	private static void overlayOrChat(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (mc.font.width(text) > mc.getWindow().getGuiScaledWidth() - 24) {
			// chat appends where the action bar replaces itself, so a refused key held down would fill it: the same line is sent once per window
			long now = System.nanoTime();
			String plain = text.getString();
			if (plain.equals(lastChatLine) && now - lastChatLineAt < CHAT_REPEAT_NS) return;
			lastChatLine = plain;
			lastChatLineAt = now;
			mc.player.sendSystemMessage(text);
		} else {
			mc.gui.hud.setOverlayMessage(text, false);
		}
	}

	/** The last wide line that went to chat and when (ns), and how long the same line is held back. */
	private static String lastChatLine = "";
	private static long lastChatLineAt;
	private static final long CHAT_REPEAT_NS = 8_000_000_000L;

	public static boolean deleteAllowed() {
		return deleteAllowed;
	}

	public static int serverTransitionTicks() {
		return serverTransitionTicks;
	}

	public static void warnInactive() {
		if (warnedInactive || Minecraft.getInstance().player == null) return;
		warnedInactive = true;
		Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("morphreborn.message.no_server").withStyle(ChatFormatting.GRAY), false);
	}

	// ------------------------------------------------------------------ requests

	public static void select(String variantId) {
		send(new Wire.Out(Wire.C_SELECT).string(variantId));
	}

	/** Multimorph: become {@code mainId} borrowing from {@code borrowedId}. Rides after the main id; older servers ignore it. */
	public static void select(String mainId, String borrowedId) {
		send(new Wire.Out(Wire.C_SELECT).string(mainId).string(borrowedId));
	}

	/** Servers from 7.1.3 take your own body as part of a multimorph; older ones would just turn you back into it. */
	public static boolean serverCombinesOwnBody() {
		return serverAtLeast("7.1.3");
	}

	/** The server's plugin is this version or newer (false when it didn't say: servers before 6.6.0). */
	public static boolean serverAtLeast(String version) {
		return !serverVersion.isEmpty() && dev.morphreborn.client.SemVer.compare(serverVersion, version) >= 0;
	}

	public static boolean multimorphAllowed() {
		return multimorphAllowed;
	}

	public static void favourite(String variantId, boolean favourite) {
		send(new Wire.Out(Wire.C_FAVOURITE).string(variantId).bool(favourite));
	}

	public static void delete(String variantId) {
		send(new Wire.Out(Wire.C_DELETE).string(variantId));
	}

	/** When this player last pressed the ability key (System.currentTimeMillis), for the paper doll's "While Active". */
	public static long lastAbilityAt;

	// ------------------------------------------------------------------ rage (servers from 7.2.0)

	/** The server has rage (its hello said so); older servers don't: no meter, and the key only says so. */
	private static boolean rageOffered;
	/** The rage meter, from S_RAGE. */
	private static boolean rageAvailable;
	private static int rageHits;
	private static int rageHitsNeeded;
	private static int rageWait;
	private static boolean raging;
	private static int rageLength;
	/** Client ticks the wait and the rage end at (counted down here between packets). */
	private static long rageWaitEndsAt;
	private static long rageEndsAt;
	/** Client tick of the last change to the meter, for the "when it changes" display. */
	private static long rageChangedAt = -1000;
	private static boolean rageWasWaiting;

	/** The Rage key: set off a charged rage (the server says why not otherwise), and bring up the meter. */
	public static void rage() {
		rageChangedAt = tick;
		if (rageOffered) send(new Wire.Out(Wire.C_RAGE));
		else message(Component.translatable("morphreborn.rage.not_here").withStyle(net.minecraft.ChatFormatting.GRAY));
	}

	/** Charged and waiting to be set off. */
	public static boolean rageReady() {
		return rageAvailable() && !raging && rageWaitLeft() == 0 && rageHitsNeeded > 0 && rageHits >= rageHitsNeeded;
	}

	private static void handleRage(Wire.In in) {
		boolean available = in.bool();
		int hits = in.varInt();
		int needed = in.varInt();
		int waitLeft = in.varInt();
		int wait = in.varInt();
		boolean now = in.bool();
		int left = in.hasMore() ? in.varInt() : 0;
		int length = in.hasMore() ? in.varInt() : 0;
		if (available != rageAvailable || hits != rageHits || needed != rageHitsNeeded || now != raging || Math.abs(tick + waitLeft - rageWaitEndsAt) > 20) {
			rageChangedAt = tick;
		}
		rageAvailable = available;
		rageHits = hits;
		rageHitsNeeded = needed;
		rageWait = wait;
		rageWaitEndsAt = tick + waitLeft;
		raging = now;
		rageEndsAt = tick + left;
		rageLength = length;
	}

	/** Whether to show the rage meter at all: the server has rage and this player may use it. */
	public static boolean rageAvailable() {
		return active && rageOffered && rageAvailable;
	}

	/** Hits taken within the server's window, and how many set it off. */
	public static int rageHits() {
		return rageHits;
	}

	public static int rageHitsNeeded() {
		return rageHitsNeeded;
	}

	/** Ticks left of the wait after a rage, and the whole wait (0: none). */
	public static int rageWaitLeft() {
		return (int) Math.max(0, rageWaitEndsAt - tick);
	}

	public static int rageWait() {
		return rageWait;
	}

	public static boolean raging() {
		return raging;
	}

	/** Ticks left of the rage in progress, and its whole length. */
	public static int rageLeft() {
		return (int) Math.max(0, rageEndsAt - tick);
	}

	public static int rageLength() {
		return rageLength;
	}

	/** Ticks since the meter last changed (a wait running out counts). */
	public static long ticksSinceRageChange() {
		boolean waiting = rageWaitLeft() > 0;
		if (!waiting && rageWasWaiting) rageChangedAt = tick;
		rageWasWaiting = waiting;
		return tick - rageChangedAt;
	}

	public static void ability() {
		lastAbilityAt = System.currentTimeMillis();
		if (abilitiesAvailable) send(new Wire.Out(Wire.C_ABILITY));
	}

	// ------------------------------------------------------------------ arm forms (servers from 8.0.0)

	/** The server has arm forms (its hello said so); older servers don't: nothing is sent, and the arm key only says so. */
	private static boolean armsOffered;
	/** Survival: the thousandths of biomass under {@link #biomass} and the drain running now (S_SURVIVAL's trailer, 8.0.0). */
	private static int biomassMilliFraction;
	private static int drainMilliPerMinute;
	/** The last time biomass went down (a block, a hit, a special, the drain, a purchase): by how much, and when (Arms.TICK). */
	private static long lastSpendMilli;
	private static long lastSpendAt = -1000;
	/** Client tick of the last C_ARM sent, to keep to one every ArmProtocol.CLIENT_THROTTLE_TICKS. */
	private static long lastArmRequestAt = -1000;

	public static boolean armsOffered() {
		return active && armsOffered;
	}

	/**
	 * Ask the server to form, retract, or light or put out the lantern ({@code ArmProtocol.ACTION_*}; the form id only
	 * with FORM). It answers with S_ARM, or S_RESULT 1 and a message (shown red on the action bar); nothing is predicted
	 * here. At most one request every 4 ticks: one sooner is dropped. On a server without arm forms the action bar says so.
	 */
	public static void sendArm(int action, String form) {
		if (!active) {
			warnInactive();
			return;
		}
		if (!armsOffered) {
			message(Component.translatable("morphreborn.arm.not_here").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (tick - lastArmRequestAt < dev.morphreborn.client.arm.ArmProtocol.CLIENT_THROTTLE_TICKS) return;
		lastArmRequestAt = tick;
		send(dev.morphreborn.client.arm.Arms.requestPacket(action, form));
	}

	/**
	 * Step accessory {@code id}'s mode (the lantern's brightness) by {@code step}, +1 or -1: ACTION_ACCESSORY_MODE, to servers from
	 * ArmProtocol.NEW_FORMS_SINCE. Shares sendArm's throttle but reports it: true when the packet went, false when it was held back
	 * or the server is older (the caller then falls back to the chat command, or tries again), so a step is never counted as sent
	 * when it was dropped.
	 */
	public static boolean sendAccessoryStep(String id, int step) {
		if (!active || !armNewFormsOffered()) return false;
		if (tick - lastArmRequestAt < dev.morphreborn.client.arm.ArmProtocol.CLIENT_THROTTLE_TICKS) return false;
		lastArmRequestAt = tick;
		send(dev.morphreborn.client.arm.Arms.requestPacket(dev.morphreborn.client.arm.ArmProtocol.ACTION_ACCESSORY_MODE, id, step));
		return true;
	}

	/**
	 * Tell the server accessory {@code id}'s key is held or let go (ACTION_ACCESSORY_HOLD: the spyglass's zoom, 2026-11-16), to servers from
	 * ArmProtocol.NEW_FORMS_SINCE. Not throttled, and the server skips its own throttle for it, so a release is never dropped behind a
	 * press; the caller sends one change a tick at most. False when nothing could be sent (no server, an older one).
	 */
	public static boolean sendAccessoryHold(String id, boolean held) {
		if (!active || !armNewFormsOffered()) return false;
		send(dev.morphreborn.client.arm.Arms.requestPacket(dev.morphreborn.client.arm.ArmProtocol.ACTION_ACCESSORY_HOLD, id, held ? 1 : 0));
		return true;
	}

	/** The server has arm slots (S_ARM_SLOTS and the inventory actions): arms offered, and its plugin from ArmProtocol.SLOTS_SINCE. */
	public static boolean armSlotsOffered() {
		return armsOffered() && serverAtLeast(dev.morphreborn.client.arm.ArmProtocol.SLOTS_SINCE);
	}

	/** The server has the new forms (the accessory actions, the accessory lists on S_ARM and S_ARSENAL): arms offered, and its plugin from ArmProtocol.NEW_FORMS_SINCE. */
	public static boolean armNewFormsOffered() {
		return armsOffered() && serverAtLeast(dev.morphreborn.client.arm.ArmProtocol.NEW_FORMS_SINCE);
	}

	/**
	 * Switch accessory {@code id} on or off (the Arsenal wheel's inner ring). A server from NEW_FORMS_SINCE takes the accessory
	 * actions with the id; an older one only knows the lantern, by its own two actions. Shares {@link #sendArm}'s throttle and
	 * refusals; false when there was nothing to send (an accessory an older server has never heard of).
	 */
	public static boolean sendAccessory(String id, boolean on) {
		if (armNewFormsOffered()) {
			sendArm(on ? dev.morphreborn.client.arm.ArmProtocol.ACTION_ACCESSORY_ON : dev.morphreborn.client.arm.ArmProtocol.ACTION_ACCESSORY_OFF, id);
			return true;
		}
		if (dev.morphreborn.client.arm.ArmProtocol.LANTERN.equals(id)) {
			sendArm(on ? dev.morphreborn.client.arm.ArmProtocol.ACTION_LANTERN_ON : dev.morphreborn.client.arm.ArmProtocol.ACTION_LANTERN_OFF, "");
			return true;
		}
		return false;
	}

	/**
	 * Tell the server a container screen opened or closed ({@code ArmProtocol.ACTION_INVENTORY_OPEN} or
	 * {@code ACTION_INVENTORY_CLOSED}; 2026-11-16). Shares sendArm's throttle but reports it: true when the
	 * packet went, false when it was held back (or the server has no arm slots), so the caller tries again next tick and a
	 * CLOSED is never lost after an OPEN.
	 */
	public static boolean sendArmInventory(int action) {
		if (!armSlotsOffered()) return false;
		if (tick - lastArmRequestAt < dev.morphreborn.client.arm.ArmProtocol.CLIENT_THROTTLE_TICKS) return false;
		lastArmRequestAt = tick;
		send(dev.morphreborn.client.arm.Arms.requestPacket(action, ""));
		return true;
	}

	/** Survival: biomass in thousandths (1000 = one biomass), as exact as the server sends it. */
	public static long biomassMilli() {
		return (long) biomass * 1000L + biomassMilliFraction;
	}

	/** Survival: what is draining biomass now, in thousandths per minute (a formed arm, a lit lantern); 0 when nothing is. */
	public static int drainMilliPerMinute() {
		return drainMilliPerMinute;
	}

	/** The last drop in biomass, in thousandths (positive), and when it arrived on {@code Arms.TICK}. */
	public static long lastSpendMilli() {
		return lastSpendMilli;
	}

	public static long lastSpendAt() {
		return lastSpendAt;
	}

	private static void send(Wire.Out out) {
		if (ClientPlayNetworking.canSend(MorphPayload.TYPE)) ClientPlayNetworking.send(new MorphPayload(out.bytes()));
	}

	// ------------------------------------------------------------------ incoming

	private static void handle(byte[] data) {
		try {
			Wire.In in = new Wire.In(data);
			int id = in.varInt();
			if (id == Wire.S_HELLO) {
				handleHello(in);
				return;
			}
			if (!active) return;
			switch (id) {
				case Wire.S_COLLECTION -> handleCollection(in);
				case Wire.S_MORPH_STATE -> handleState(in);
				case Wire.S_ACQUIRE -> handleAcquire(in);
				case Wire.S_RESULT -> handleResult(in);
				case Wire.S_UNLOCK -> handleUnlock(in);
				case Wire.S_ANIMATE -> dev.morphreborn.client.morph.MorphAnimations.ability(in.uuid(), in.string());
				case Wire.S_TRAITS -> dev.morphreborn.client.morph.ClientTraits.set(in.varInt());
				case Wire.S_SURVIVAL -> handleSurvival(in);
				case Wire.S_UPGRADES -> handleUpgrades(in);
				case Wire.S_COSTS -> {
					morphCosts.clear();
					int count = in.count();
					for (int i = 0; i < count; i++) morphCosts.put(in.string(), in.varInt());
					multimorphExtraPercent = in.hasMore() ? in.varInt() : 0;
				}
				case Wire.S_RAGE -> handleRage(in);
				case Wire.S_RAGE_DRAIN -> {
					int origin = in.varInt();
					int count = in.count();
					int[] targets = new int[count];
					for (int i = 0; i < count; i++) targets[i] = in.varInt();
					// what the legs are doing (servers from 8.0.0; 7.4.0 and 7.5.0 sent a bool in the same one byte: 0 or 1)
					int legs = in.hasMore() ? in.varInt() : dev.morphreborn.client.render.RageTethers.LEGS_NONE;
					dev.morphreborn.client.render.RageTethers.set(origin, targets, legs);
				}
				case Wire.S_FORGET -> {
					UUID who = in.uuid();
					MorphClient.STATES.remove(who);
					MorphClient.ENTITIES.forget(who);
					dev.morphreborn.client.arm.Arms.forget(who);
				}
				case Wire.S_ARM -> dev.morphreborn.client.arm.Arms.handleState(in);
				case Wire.S_ARM_FX -> dev.morphreborn.client.arm.Arms.handleFx(in);
				case Wire.S_ARSENAL -> dev.morphreborn.client.arm.Arms.handleArsenal(in);
				case Wire.S_ARM_COLOUR -> dev.morphreborn.client.arm.ArmColours.handle(in);
				case Wire.S_ARM_SLOTS -> dev.morphreborn.client.arm.ArmSlots.handle(in);
				case Wire.S_PASSIVES -> {
					if (PASSIVES.read(in)) treeDataChanged(); // a forced repeat of the same list is no change
				}
				default -> MorphClient.LOGGER.debug("Ignoring unknown RE:Morph packet {}", id);
			}
		} catch (Wire.MalformedPacketException | IllegalArgumentException e) {
			MorphClient.LOGGER.warn("Ignoring malformed RE:Morph packet: {}", e.getMessage());
		}
	}

	private static void handleHello(Wire.In in) {
		int protocol = in.varInt();
		boolean accepted = in.bool();
		String message = in.string();
		serverTransitionTicks = in.varInt();
		deleteAllowed = in.bool();
		selectorAllowed = in.bool();
		abilitiesAvailable = in.bool();
		nameTagMode = in.hasMore() ? in.string() : "MORPH";
		gameplayMode = in.hasMore() ? in.string() : "classic";
		serverVersion = in.hasMore() ? in.string() : ""; // since 6.6.0
		multimorphAllowed = in.hasMore() && in.bool(); // since 7.0.0
		rageOffered = in.hasMore() && in.bool(); // since 7.2.0; older servers have no rage
		armsOffered = in.hasMore() && in.bool(); // since 8.0.0; older servers have no arm forms
		PASSIVES.clear(); // a repeated hello (the mode changed) ends the passive toggles until the server sends its list again
		TREE_ANSWERS.reset();
		active = accepted && protocol == Wire.PROTOCOL_VERSION;
		if (!active) {
			MorphClient.LOGGER.warn("RE:Morph server speaks protocol {} (this mod: {}); {}", protocol, Wire.PROTOCOL_VERSION, message);
		} else {
			MorphClient.LOGGER.info("Connected to a RE:Morph server (protocol {}{}).", protocol, serverVersion.isEmpty() ? "" : ", plugin " + serverVersion);
		}
		dev.morphreborn.client.UpdateCheck.onRemorphServer(serverVersion, active);
	}

	/** The upgrade tree: open its screen, or refresh the one that is open. */
	private static void handleUpgrades(Wire.In in) {
		boolean open = in.bool();
		String message = in.string();
		boolean unlocked = in.bool();
		int amount = in.varInt();
		int capacity = in.varInt();
		int count = in.count();
		java.util.List<dev.morphreborn.client.gui.UpgradeTree.Node> nodes = new java.util.ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String id = in.string();
			String name = in.string();
			String description = in.string();
			String icon = in.string();
			String parent = in.string();
			int level = in.varInt();
			int maxLevel = in.varInt();
			String now = in.string();
			String next = in.string();
			int cost = in.varInt() - 1;
			String missing = in.string();
			boolean affordable = in.bool();
			nodes.add(new dev.morphreborn.client.gui.UpgradeTree.Node(id, name, description, icon, parent, level, maxLevel, now, next, cost, missing, affordable));
		}
		dev.morphreborn.client.gui.UpgradeTree tree = new dev.morphreborn.client.gui.UpgradeTree(message, unlocked, amount, capacity, nodes);
		Minecraft mc = Minecraft.getInstance();
		boolean showing = mc.gui.screen() instanceof dev.morphreborn.client.gui.UpgradeTreeScreen;
		// open=true only ever answers C_OPEN_UPGRADES; one that answers the tree's own refresh after the player closed it must not reopen it
		if (open && !TREE_ANSWERS.accept(tick, showing)) return;
		if (mc.gui.screen() instanceof dev.morphreborn.client.gui.UpgradeTreeScreen screen) screen.update(tree);
		else if (open) mc.gui.setScreen(new dev.morphreborn.client.gui.UpgradeTreeScreen(tree));
	}

	public static void buyUpgrade(String id) {
		send(new Wire.Out(Wire.C_BUY_UPGRADE).string(id));
	}

	/** The player's biomass passives as the server last sent them (S_PASSIVES, servers from PassiveProtocol.SINCE), for the upgrade tree's toggles. */
	public static final dev.morphreborn.client.passive.PassiveStates PASSIVES = new dev.morphreborn.client.passive.PassiveStates();

	/** The tree may draw a toggle on passive {@code id} and send C_PASSIVE for it: the server sent its states, and {@code id} is bought. */
	public static boolean canTogglePassive(String id) {
		return active && PASSIVES.canToggle(id, serverVersion);
	}

	/**
	 * Ask the server to switch passive {@code id} to the other state (on when it is off, off when it is on): C_PASSIVE. The server answers with
	 * S_PASSIVES at once and nothing is predicted here. At most one every 4 ticks; false when nothing went (an older server, not bought, too soon).
	 */
	public static boolean togglePassive(String id) {
		if (!canTogglePassive(id) || !PASSIVES.takeSendSlot(tick)) return false;
		send(dev.morphreborn.client.passive.PassiveProtocol.request(id, PASSIVES.wantOn(id)));
		return true;
	}

	private static void handleSurvival(Wire.In in) {
		boolean unlockedBefore = biomassUnlocked;
		int capacityBefore = biomassCapacity;
		biomassUnlocked = in.bool();
		int now = in.varInt();
		biomassCapacity = in.varInt();
		survivalCanMorph = in.bool();
		// Since 8.0.0 (arm forms spend tenths): the thousandths under the whole number, and the drain running now. Older
		// servers send neither: whole biomass only, nothing draining.
		int milli = in.hasMore() ? Math.clamp(in.varInt(), 0, 999) : 0;
		int drain = in.hasMore() ? Math.max(0, in.varInt()) : 0;
		boolean treeChanged = unlockedBefore != biomassUnlocked || capacityBefore != biomassCapacity || now != biomass;
		long before = biomassMilli();
		long after = (long) now * 1000L + milli;
		if (now != biomass) biomassChangedAt = tick;
		if (now > biomass) {
			lastGain = now - biomass;
			lastGainAt = tick;
		}
		if (after < before) {
			lastSpendMilli = before - after;
			lastSpendAt = dev.morphreborn.client.arm.Arms.TICK;
		}
		biomass = now;
		biomassMilliFraction = milli;
		drainMilliPerMinute = drain;
		if (treeChanged) treeDataChanged();
	}

	/**
	 * Something the open upgrade tree shows changed (S_PASSIVES with a new list, S_SURVIVAL with a new balance, capacity or unlock): the screen asks
	 * for a fresh tree soon (paced there). Nothing happens without an open tree.
	 */
	private static void treeDataChanged() {
		if (Minecraft.getInstance().gui.screen() instanceof dev.morphreborn.client.gui.UpgradeTreeScreen screen) screen.dataChanged();
	}

	private static void handleCollection(Wire.In in) {
		int rows = in.count();
		List<Collection.Row> all = new ArrayList<>(rows);
		for (int i = 0; i < rows; i++) {
			String type = in.string();
			Collection.Row row = Collection.newRow(type);
			int variants = in.count();
			for (int j = 0; j < variants; j++) {
				MorphRef ref = in.ref();
				row.variants.add(Collection.newVariant(ref, in.bool()));
			}
			all.add(row);
		}
		// Since 7.2.0: the collection's limits (each + 1, 0 for none), then per row whether its creature has looks.
		// Older servers send neither: no limits, no counters.
		int kindLimit = -1;
		int lookLimit = -1;
		if (in.hasMore()) {
			kindLimit = in.varInt() - 1;
			lookLimit = in.varInt() - 1;
			for (Collection.Row row : all) row.hasLooks = in.bool();
		}
		List<Collection.Row> out = new ArrayList<>(rows);
		for (Collection.Row row : all) if (!row.variants.isEmpty()) out.add(row);
		MorphClient.COLLECTION.replace(out, rows, kindLimit, lookLimit);
	}

	private static void handleState(Wire.In in) {
		UUID player = in.uuid();
		in.varInt(); // entity id: informational, we key on UUID so states survive re-tracking
		MorphState state = new MorphState();
		state.prev = in.optionalRef();
		state.next = in.optionalRef();
		state.time = in.varInt();
		state.duration = in.varInt();
		String overlay = in.hasMore() ? in.string() : "";
		String sent = overlay;
		if (ShulkerLock.isShulker(state.next)) overlay = takeLock(state, overlay);
		if (!overlay.isEmpty() && state.next != null && !state.next.isPlayer()) state.next = withOverlay(state.next, overlay);
		state.borrowed = in.hasMore() ? in.optionalRef() : null;
		MorphClient.LOGGER.debug("Morph state for {}: next={} overlay={}", player, state.next == null ? null : state.next.snbt(), sent);
		state.receivedAt = tick;
		MorphState old = MorphClient.STATES.get(player);
		carryBorrowed(old, state);
		carryLock(old, state);
		if (state.next == null || (state.next.isSelf() && !state.isTransitioning())) {
			MorphClient.STATES.remove(player);
			MorphClient.ENTITIES.forget(player);
		} else {
			MorphClient.STATES.put(player, state);
			MorphClient.ENTITIES.retain(player, state.prev, state.next, state.borrowed, state.prevBorrowed);
		}
	}

	/**
	 * Multimorph: what the borrowed form was, so its parts morph away instead of vanishing. A transformation takes the
	 * old body's borrowed form along as {@code prevBorrowed}; another borrowed form on the same body starts a short
	 * transformation of its own (half the server's, at least half a second). A repeated state (a new look, the same
	 * forms) keeps whatever is under way.
	 */
	private static void carryBorrowed(@org.jspecify.annotations.Nullable MorphState old, MorphState state) {
		if (old == null) return;
		if (state.isTransitioning()) {
			state.prevBorrowed = old.isTransitioning() ? old.prevBorrowed : old.borrowed;
			return;
		}
		boolean sameBody = old.next != null && state.next != null && old.next.variantId().equals(state.next.variantId()) && !old.isTransitioning();
		if (!sameBody) return;
		if (!sameForm(old.borrowed, state.borrowed)) {
			state.prevBorrowed = old.borrowed;
			state.borrowDuration = Math.max(10, serverTransitionTicks / 2);
			state.borrowTime = 0;
		} else if (old.isBorrowChanging()) {
			state.prevBorrowed = old.prevBorrowed;
			state.borrowDuration = old.borrowDuration;
			state.borrowTime = old.borrowTime;
		}
	}

	/**
	 * A shulker main body held in a block's space: the server's lock keys (ShulkerLock) come out of the overlay into the
	 * state, and the rest of the overlay is returned. Left in, every change of the lid's opening would be another
	 * appearance, so another morph entity, whose lid snapped open or shut instead of moving. A bad overlay is returned as
	 * it is (withOverlay says so).
	 */
	private static String takeLock(MorphState state, String overlay) {
		if (overlay.isEmpty()) return overlay;
		try {
			net.minecraft.nbt.CompoundTag live = net.minecraft.nbt.TagParser.parseCompoundFully(overlay).copy(); // the parser shares its "{}" instance
			if (!live.contains(ShulkerLock.FACE_KEY) && !live.contains(ShulkerLock.PEEK_KEY)) return overlay;
			int face = live.getIntOr(ShulkerLock.FACE_KEY, -1);
			state.lockFace = face >= 0 && face < net.minecraft.core.Direction.values().length ? net.minecraft.core.Direction.from3DDataValue(face) : null;
			state.peek = Math.clamp(live.getIntOr(ShulkerLock.PEEK_KEY, 0), 0, ShulkerLock.MAX_PEEK);
			live.remove(ShulkerLock.FACE_KEY);
			live.remove(ShulkerLock.PEEK_KEY);
			return live.isEmpty() ? "" : live.toString();
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			return overlay;
		}
	}

	/**
	 * A transformation out of a held shulker starts from the face it was held by: the server sends the lock keys only
	 * for the new body, so the old body's are carried over (as {@link #carryBorrowed} carries its borrowed form). A
	 * repeated state during the same transformation keeps what was carried.
	 * <p>
	 * The old body is matched by what it is ({@link #sameCreature}), not by its appearance: the body we had carries the
	 * rest of its overlay (a borrowed bee's anger or sting lands there), while the server's {@code prev} is the plain
	 * form, so an appearance match missed it and the melt started from a free shell on the floor.
	 */
	private static void carryLock(@org.jspecify.annotations.Nullable MorphState old, MorphState state) {
		if (old == null || !state.isTransitioning() || !ShulkerLock.isShulker(state.prev)) return;
		if (old.isTransitioning() && sameCreature(old.prev, state.prev)) {
			state.prevLockFace = old.prevLockFace;
			state.prevPeek = old.prevPeek;
		} else if (sameCreature(old.next, state.prev)) {
			state.prevLockFace = old.lockFace;
			state.prevPeek = old.peek;
		}
	}

	/** The same creature and variant (a shulker's colour), whatever live overlay either carries. */
	private static boolean sameCreature(@org.jspecify.annotations.Nullable MorphRef a, @org.jspecify.annotations.Nullable MorphRef b) {
		return a != null && b != null && a.entityType().equals(b.entityType()) && a.variantId().equals(b.variantId());
	}

	private static boolean sameForm(@org.jspecify.annotations.Nullable MorphRef a, @org.jspecify.annotations.Nullable MorphRef b) {
		return a == null ? b == null : b != null && a.appearanceKey().equals(b.appearanceKey());
	}

	/**
	 * Layer the server's live appearance (an enderman's carried block, a primed creeper) on the variant data. The
	 * result is a different appearance key, so a matching entity is built and the vanilla renderer draws it.
	 */
	private static MorphRef withOverlay(MorphRef ref, String overlay) {
		try {
			net.minecraft.nbt.CompoundTag base = ref.snbt().isEmpty() ? new net.minecraft.nbt.CompoundTag()
					: net.minecraft.nbt.TagParser.parseCompoundFully(ref.snbt()).copy(); // the parser shares its "{}" instance
			net.minecraft.nbt.CompoundTag live = net.minecraft.nbt.TagParser.parseCompoundFully(overlay);
			for (String key : live.keySet()) base.put(key, live.get(key).copy()); // replace whole keys; a deep merge leaks old block properties
			return new MorphRef(ref.entityType(), ref.variantId(), base.toString(), ref.skin());
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			MorphClient.LOGGER.warn("Ignoring bad morph overlay {}: {}", overlay, e.getMessage());
			return ref;
		}
	}

	private static void handleAcquire(Wire.In in) {
		int originId = in.varInt();
		int acquiredId = in.varInt();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Entity origin = mc.level.getEntity(originId);
		Entity acquired = mc.level.getEntity(acquiredId);
		boolean newMorph = !in.hasMore() || in.bool(); // servers before 6.3.0 don't say: treat as a new morph, as before
		if (origin instanceof LivingEntity o && acquired instanceof LivingEntity a) MorphWorldRenderer.startAcquisition(o, a, newMorph);
	}

	private static void handleResult(Wire.In in) {
		int code = in.varInt();
		String message = in.string();
		if (code != 0 && !message.isEmpty() && Minecraft.getInstance().player != null) {
			overlayOrChat(Component.literal(message).withStyle(ChatFormatting.RED));
		}
	}

	private static void handleUnlock(Wire.In in) {
		MorphRef ref = in.ref();
		if (!ClientConfig.INSTANCE.unlockToasts) return;
		Component name;
		if (ref.isPlayer()) {
			name = Component.literal(ref.skin() != null ? ref.skin().name() : "?");
		} else {
			Identifier id = Identifier.tryParse(ref.entityType());
			name = id == null ? Component.literal(ref.entityType())
					: BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(t -> (Component) t.getDescription()).orElse(Component.literal(ref.entityType()));
		}
		SystemToast.add(Minecraft.getInstance().gui.toastManager(), UNLOCK_TOAST, Component.translatable("morphreborn.toast.unlocked"), name);
	}
}
