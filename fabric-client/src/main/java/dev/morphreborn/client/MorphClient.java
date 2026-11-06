package dev.morphreborn.client;

import dev.morphreborn.client.data.Collection;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.gui.MorphGuiEntities;
import dev.morphreborn.client.gui.MorphSettingsScreen;
import dev.morphreborn.client.gui.SelectorHud;
import dev.morphreborn.client.morph.MorphEntities;
import dev.morphreborn.client.morph.MorphSync;
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
		Keys.init();
		ClientNet.init();
		PictureInPictureRendererRegistry.register(context -> new MorphGuiEntities.Renderer());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "selector"), SelectorHud.INSTANCE::render);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "biomass"), dev.morphreborn.client.gui.BiomassHud::render);
		LevelRenderEvents.COLLECT_SUBMITS.register(MorphWorldRenderer::collectSubmits);
		ClientTickEvents.END_CLIENT_TICK.register(MorphClient::tick);
		// Client-side command, so it works on any server: opens the settings screen.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(ClientCommands.literal("morphclient").executes(c -> {
			openSettings = true;
			return 1;
		})));
	}

	/** Screen asked for by /morphclient: opened on the next tick, after the chat screen that ran the command closes. */
	private static boolean openSettings;

	private static void tick(Minecraft mc) {
		if (openSettings) {
			openSettings = false;
			mc.gui.setScreen(new MorphSettingsScreen(null));
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
			syncForm(e.getKey(), state.prev, player);
			syncForm(e.getKey(), state.next, player);
		}
		MorphWorldRenderer.tickEffects();
		dev.morphreborn.client.morph.MorphAnimations.tick();
	}

	private static void syncForm(UUID owner, MorphRef ref, Player player) {
		if (ref == null || ref.isSelf()) return;
		LivingEntity morph = ENTITIES.forWorld(owner, ref);
		if (morph != null) MorphSync.tick(morph, player);
	}
}
