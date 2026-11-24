package dev.morphreborn.client.arm;

import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jspecify.annotations.Nullable;

/**
 * Starts the client's arm forms: a second client entrypoint, listed after MorphClient's in fabric.mod.json, so the
 * config, the keys and the network are set up by the time the arm modules load. It loads the modules, then keeps the
 * arm clock: {@link Arms#tick} and every module's tick run on each game tick while a world is loaded and the game isn't
 * paused (the same ticks the morph timelines count).
 * <p>
 * After a world change everyone else's arm is forgotten ({@link Arms#clearOthers}); the server sends the new world's
 * armed players again. Like the rest of the mod, all of it stays off under {@code -Dmorphreborn.disable}.
 */
public final class ArmClientInit implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (Boolean.getBoolean("morphreborn.disable")) return; // MorphClient says so in the log
		ArmHooks.live = true;
		ArmClientModules.initAll();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null || mc.isPaused()) return;
			Arms.tick();
			ArmClientModules.tickAll(mc);
		});
		Arms.ownLookup(() -> ownId(Minecraft.getInstance()));
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> Arms.clearOthers(ownId(mc)));
	}

	/** This player's UUID: the player's, or while a respawn has none yet, the profile the connection logged in with. */
	private static @Nullable UUID ownId(Minecraft mc) {
		if (mc.player != null) return mc.player.getUUID();
		ClientPacketListener connection = mc.getConnection();
		return connection == null ? null : connection.getLocalGameProfile().id();
	}
}
