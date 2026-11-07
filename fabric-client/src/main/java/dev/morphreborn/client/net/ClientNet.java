package dev.morphreborn.client.net;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.Collection;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.gui.SelectorHud;
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
	/** The server lets players combine morphs (multimorph.enabled); servers before 6.7.0 don't say, and can't. */
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
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			reset();
			ticksSinceJoin = -1;
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
	}

	/** Called every client tick: sends HELLO once Paper has advertised our channel. */
	public static void tick() {
		tick++;
		if (ticksSinceJoin < 0 || helloSent) return;
		ticksSinceJoin++;
		if (ClientPlayNetworking.canSend(MorphPayload.TYPE)) {
			helloSent = true;
			String version = FabricLoader.getInstance().getModContainer(MorphClient.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
			send(new Wire.Out(Wire.C_HELLO).varInt(Wire.PROTOCOL_VERSION).string(version));
		} else if (ticksSinceJoin > HELLO_TIMEOUT_TICKS) {
			ticksSinceJoin = -1;
			MorphClient.LOGGER.info("Server does not run RE:Morph; the selector stays disabled.");
		}
	}

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

	public static void message(Component text) {
		if (Minecraft.getInstance().player != null) Minecraft.getInstance().gui.hud.setOverlayMessage(text, false);
	}

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

	public static void ability() {
		lastAbilityAt = System.currentTimeMillis();
		if (abilitiesAvailable) send(new Wire.Out(Wire.C_ABILITY));
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
				case Wire.S_FORGET -> {
					UUID who = in.uuid();
					MorphClient.STATES.remove(who);
					MorphClient.ENTITIES.forget(who);
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
		multimorphAllowed = in.hasMore() && in.bool(); // since 6.7.0
		active = accepted && protocol == Wire.PROTOCOL_VERSION;
		if (!active) {
			MorphClient.LOGGER.warn("RE:Morph server speaks protocol {} (this mod: {}); {}", protocol, Wire.PROTOCOL_VERSION, message);
		} else {
			MorphClient.LOGGER.info("Connected to a RE:Morph server (protocol {}{}).", protocol, serverVersion.isEmpty() ? "" : ", plugin " + serverVersion);
			dev.morphreborn.client.UpdateCheck.onJoin(serverVersion);
		}
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
		if (mc.gui.screen() instanceof dev.morphreborn.client.gui.UpgradeTreeScreen screen) screen.update(tree);
		else if (open) mc.gui.setScreen(new dev.morphreborn.client.gui.UpgradeTreeScreen(tree));
	}

	public static void buyUpgrade(String id) {
		send(new Wire.Out(Wire.C_BUY_UPGRADE).string(id));
	}

	private static void handleSurvival(Wire.In in) {
		biomassUnlocked = in.bool();
		int now = in.varInt();
		biomassCapacity = in.varInt();
		survivalCanMorph = in.bool();
		if (now != biomass) biomassChangedAt = tick;
		if (now > biomass) {
			lastGain = now - biomass;
			lastGainAt = tick;
		}
		biomass = now;
	}

	private static void handleCollection(Wire.In in) {
		int rows = in.count();
		List<Collection.Row> out = new ArrayList<>(rows);
		for (int i = 0; i < rows; i++) {
			String type = in.string();
			Collection.Row row = Collection.newRow(type);
			int variants = in.count();
			for (int j = 0; j < variants; j++) {
				MorphRef ref = in.ref();
				row.variants.add(Collection.newVariant(ref, in.bool()));
			}
			if (!row.variants.isEmpty()) out.add(row);
		}
		MorphClient.COLLECTION.replace(out);
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
		if (!overlay.isEmpty() && state.next != null && !state.next.isPlayer()) state.next = withOverlay(state.next, overlay);
		state.borrowed = in.hasMore() ? in.optionalRef() : null;
		MorphClient.LOGGER.debug("Morph state for {}: next={} overlay={}", player, state.next == null ? null : state.next.snbt(), overlay);
		state.receivedAt = tick;
		carryBorrowed(MorphClient.STATES.get(player), state);
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
		boolean newMorph = !in.hasMore() || in.bool(); // servers before 6.1.6 don't say: treat as a new morph, as before
		if (origin instanceof LivingEntity o && acquired instanceof LivingEntity a) MorphWorldRenderer.startAcquisition(o, a, newMorph);
	}

	private static void handleResult(Wire.In in) {
		int code = in.varInt();
		String message = in.string();
		if (code != 0 && !message.isEmpty() && Minecraft.getInstance().player != null) {
			Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(message).withStyle(ChatFormatting.RED), false);
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
