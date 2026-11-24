package dev.morphreborn.client;

import dev.morphreborn.client.data.Collection;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.gui.MorphGuiEntities;
import dev.morphreborn.client.gui.MorphSettingsScreen;
import dev.morphreborn.client.gui.SelectorHud;
import dev.morphreborn.client.morph.MorphEntities;
import dev.morphreborn.client.morph.MorphSync;
import dev.morphreborn.client.morph.ShulkerLock;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.net.MorphRef;
import dev.morphreborn.client.render.MorphWorldRenderer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MorphClient implements ClientModInitializer {
	public static final String MOD_ID = "morphreborn";
	public static final Logger LOGGER = LoggerFactory.getLogger("RE:Morph");

	/** This player's collection (server-owned, mirrored). */
	public static final Collection COLLECTION = new Collection();
	/** Morph state of every player the server has told us about, keyed by player UUID. */
	public static final Map<UUID, MorphState> STATES = new HashMap<>();
	public static final MorphEntities ENTITIES = new MorphEntities();
	/** Last seen entity id of each morphed player, to detect the server re-sending the entity (debugging flicker). */
	private static final Map<UUID, Integer> ENTITY_IDS = new HashMap<>();

	@Override
	public void onInitializeClient() {
		if (Boolean.getBoolean("morphreborn.disable")) {
			// Test switch: behave like an unmodded client (no channel, no handshake) to check vanilla-viewer support.
			LOGGER.warn("RE:Morph disabled by -Dmorphreborn.disable=true; this client will look vanilla to the server.");
			return;
		}
		ClientConfig.load();
		dev.morphreborn.client.compat.FirstPersonCompat.init();
		Keys.init();
		ClientNet.init();
		PictureInPictureRendererRegistry.register(context -> new MorphGuiEntities.Renderer());
		PictureInPictureRendererRegistry.register(context -> new dev.morphreborn.client.gui.PaperDoll.Renderer());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "selector"), SelectorHud.INSTANCE::render);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "biomass"), dev.morphreborn.client.gui.BiomassHud::render);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "rage"), dev.morphreborn.client.gui.RageHud::render); // after the bar: it reads where the bar went
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "paper_doll"), dev.morphreborn.client.gui.PaperDoll::render);
		// With a menu open the doll is drawn over the menu (Show in Menus); Fabric makes a screen's events anew each init.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> ScreenEvents.afterExtract(screen).register(dev.morphreborn.client.gui.PaperDoll::renderOverScreen));
		UpdateCheck.start();
		LevelRenderEvents.COLLECT_SUBMITS.register(MorphWorldRenderer::collectSubmits);
		ClientTickEvents.END_CLIENT_TICK.register(MorphClient::tick);
		// Client-side command, so it works on any server: opens the settings screen.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			var command = ClientCommands.literal("morphclient").executes(c -> {
				openSettings = true;
				return 1;
			});
			// Any two creatures combined, as a multimorph draws them, to see how a combination looks.
			com.mojang.brigadier.suggestion.SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> creatures = (ctx, builder) ->
					net.minecraft.commands.SharedSuggestionProvider.suggest(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet().stream()
							.filter(id -> id.getNamespace().equals("minecraft")).map(net.minecraft.resources.Identifier::getPath), builder);
			command.then(ClientCommands.literal("preview").executes(c -> preview("zombie", "bat"))
					.then(ClientCommands.argument("main", com.mojang.brigadier.arguments.StringArgumentType.word()).suggests(creatures)
							.executes(c -> preview(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "main"), null))
							.then(ClientCommands.argument("borrowed", com.mojang.brigadier.arguments.StringArgumentType.word()).suggests(creatures)
									.executes(c -> preview(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "main"),
											com.mojang.brigadier.arguments.StringArgumentType.getString(c, "borrowed"))))));
			// Development only: write every mob's head model for tools/gen_head_pack.py (dev/HeadDump).
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
				command.then(ClientCommands.literal("dumpheads").executes(c -> {
					c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(dev.morphreborn.client.dev.HeadDump.run(Minecraft.getInstance())));
					return 1;
				}));
			}
			dispatcher.register(command);
		});
	}

	/** Screen asked for by /morphclient: opened on the next tick, after the chat screen that ran the command closes. */
	private static boolean openSettings;
	/** /morphclient preview: the two creatures, opened on the next tick like the settings. */
	private static String @org.jspecify.annotations.Nullable [] openPreview;

	private static int preview(String main, @org.jspecify.annotations.Nullable String borrowed) {
		openPreview = new String[] {main, borrowed};
		return 1;
	}

	/** Development: MORPHREBORN_DUMPHEADS=1 writes the head dump once the first world has loaded (dev/HeadDump). */
	private static boolean dumpHeads = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
			&& System.getenv("MORPHREBORN_DUMPHEADS") != null;

	private static void tick(Minecraft mc) {
		dev.morphreborn.client.dev.CommandInbox.tick(mc);
		if (dumpHeads && mc.level != null && mc.player != null && mc.player.tickCount > 40) {
			dumpHeads = false;
			LOGGER.info("Head dump: {}", dev.morphreborn.client.dev.HeadDump.run(mc));
		}
		if (openSettings) {
			openSettings = false;
			mc.gui.setScreen(new MorphSettingsScreen(null));
		}
		if (openPreview != null) {
			String[] pair = openPreview;
			openPreview = null;
			mc.gui.setScreen(new dev.morphreborn.client.gui.MultimorphPreviewScreen(pair[0], pair[1]));
		}
		while (Keys.DOLL.consumeClick()) {
			ClientConfig.INSTANCE.doll = !ClientConfig.INSTANCE.doll;
			ClientConfig.save();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(ClientConfig.INSTANCE.doll ? "morphreborn.doll.shown" : "morphreborn.doll.hidden"));
			}
		}
		while (Keys.SETTINGS.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(new MorphSettingsScreen(null));
		}
		while (Keys.BIOMASS.consumeClick()) {
			if (mc.gui.screen() != null || mc.player == null) continue;
			if (!ClientNet.isActive()) ClientNet.warnInactive();
			else if (ClientNet.isSurvival()) ClientNet.openUpgrades();
			else ClientNet.message(net.minecraft.network.chat.Component.translatable("morphreborn.message.classic_mode").withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		while (Keys.RAGE.consumeClick()) {
			if (mc.gui.screen() != null || mc.player == null) continue;
			if (!ClientNet.isActive()) ClientNet.warnInactive();
			else ClientNet.rage();
		}
		ClientNet.tick();
		SelectorHud.INSTANCE.onRenderTick();
		SelectorHud.INSTANCE.tick();
		if (mc.level == null || mc.isPaused()) return;
		dev.morphreborn.client.morph.WallClimb.tick(mc.player);
		dev.morphreborn.client.morph.ClientTraits.tickLava(mc.player);
		dev.morphreborn.client.morph.ClientTraits.tickSlowFall(mc.player);
		for (Iterator<Map.Entry<UUID, MorphState>> it = STATES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, MorphState> e = it.next();
			MorphState state = e.getValue();
			if (!state.tick()) {
				it.remove();
				ENTITIES.forget(e.getKey());
				continue;
			}
			Player player = mc.level.getPlayerByUUID(e.getKey());
			Integer lastId = ENTITY_IDS.put(e.getKey(), player == null ? -1 : player.getId());
			if (lastId != null && lastId >= 0 && player != null && lastId != player.getId()) {
				LOGGER.info("Morphed player {} was re-sent by the server mid-morph (entity {} -> {})", player.getName().getString(), lastId, player.getId());
			}
			if (player == null) continue;
			if (!ClientConfig.INSTANCE.transitionAnimation) dev.morphreborn.client.render.VanillaStyleEffects.transitionTick(player, state);
			// the main bodies (old and new) carry a shulker's hold in its block; a borrowed form never does
			syncForm(e.getKey(), state.prev, player, ShulkerLock.hold(state.prev, state.prevLockFace, state.prevPeek));
			syncForm(e.getKey(), state.next, player, ShulkerLock.hold(state.next, state.lockFace, state.peek));
			syncForm(e.getKey(), state.borrowed, player, null);
			syncForm(e.getKey(), state.prevBorrowed, player, null);
			// a giant wearing a player form is drawn as a giant-sized player of its own (GiantPlayer): the old body's pair
			// through a transformation, the body's own, and the pair it had before a change of the borrowed form
			syncGiantPlayer(e.getKey(), state.prev, state.prevBorrowed, player);
			syncGiantPlayer(e.getKey(), state.next, state.borrowed, player);
			if (state.isBorrowChanging()) syncGiantPlayer(e.getKey(), state.next, state.prevBorrowed, player);
		}
		MorphWorldRenderer.tickEffects();
		dev.morphreborn.client.morph.MorphAnimations.tick();
	}

	private static void syncForm(UUID owner, MorphRef ref, Player player, ShulkerLock.@org.jspecify.annotations.Nullable Hold hold) {
		if (ref == null || ref.isSelf()) return;
		syncEntity(ENTITIES.forWorld(owner, ref), player, hold);
	}

	/**
	 * The giant-sized player drawn for a giant main body {@code main} wearing the player form {@code lent}, if that is
	 * what they are (GiantPlayer): it follows the player like any morph body, so it walks, swings, sneaks and holds what
	 * they hold.
	 */
	private static void syncGiantPlayer(UUID owner, @org.jspecify.annotations.Nullable MorphRef main, @org.jspecify.annotations.Nullable MorphRef lent,
			Player player) {
		if (!dev.morphreborn.client.morph.GiantPlayer.isGiantPlayer(main, lent)) return;
		syncEntity(ENTITIES.giantPlayerForWorld(owner, lent), player, null);
	}

	private static void syncEntity(@org.jspecify.annotations.Nullable LivingEntity morph, Player player, ShulkerLock.@org.jspecify.annotations.Nullable Hold hold) {
		if (morph == null) return;
		dev.morphreborn.client.morph.OwnParticles.begin(player);
		try {
			MorphSync.tick(morph, player, hold);
		} finally {
			dev.morphreborn.client.morph.OwnParticles.end();
		}
	}
}
