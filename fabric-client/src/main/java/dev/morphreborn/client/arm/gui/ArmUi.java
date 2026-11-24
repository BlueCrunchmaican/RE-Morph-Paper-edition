package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.ArmClientModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ExtractItemDecorationsCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * The arm forms' screen parts, as one arm module (ArmClientModules loads it by name): the Arsenal wheel ({@link
 * ArmWheel}) as a HUD element drawn right after the morph selector's, its key handled every client tick, and the goo bar
 * under an arm in the hotbar and inventories ({@link ArmBarDecoration}).
 * <p>
 * The wheel's key is read on every client tick, paused or not, rather than in the module tick (which only runs while
 * the game isn't paused): the pause menu that Esc brings up has to close the wheel too, and in single player that menu
 * pauses the game. The HUD's biomass bar, the settings and the upgrade tree's Arsenal are drawn by their own screens,
 * which use the wheel's icon and number helpers.
 */
public final class ArmUi implements ArmClientModule {
	private static final Identifier SELECTOR = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "selector");
	public static final Identifier WHEEL = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "arm_wheel");

	@Override
	public void init() {
		try {
			HudElementRegistry.attachElementAfter(SELECTOR, WHEEL, ArmWheel.INSTANCE::render);
		} catch (RuntimeException e) {
			// the selector's element should always be there (MorphClient starts first); on top of everything is fine too
			MorphClient.LOGGER.warn("Could not place the Arsenal wheel after the selector ({}); drawing it last", e.toString());
			HudElementRegistry.addLast(WHEEL, ArmWheel.INSTANCE::render);
		}
		ExtractItemDecorationsCallback.EVENT.register(ArmBarDecoration::draw);
		ClientTickEvents.END_CLIENT_TICK.register(ArmWheel.INSTANCE::tick);
		// the held arm steps aside while a container screen shows the real items (2026-11-16)
		ClientTickEvents.END_CLIENT_TICK.register(ArmInventorySignal::tick);
		// the spyglass accessory's zoom key and its marked creature's distance (2026-11-16); paused or not, like the wheel,
		// so a menu opening lets the key go
		SpyglassZoom.init();
		SpyglassHud.init();
		ClientTickEvents.END_CLIENT_TICK.register(SpyglassZoom::tick);
		// the shield accessory's raise on right-click, main hand first (decided 2026-11-17), told to the server like the zoom key is
		ClientTickEvents.END_CLIENT_TICK.register(ShieldHold::tick);
	}

	@Override
	public void reset() {
		ArmWheel.INSTANCE.reset();
		ArmInventorySignal.reset();
		SpyglassZoom.reset();
		ShieldHold.reset();
		SpyglassHud.reset();
	}
}
