package dev.morphreborn.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Keybinds, configurable in Controls like any vanilla key. Defaults match the original: [ and ] open the selector and
 * move up/down, Shift+[ / Shift+] move left/right, ` holds the favourites radial. Enter/Esc/Delete/arrows are read
 * directly while the selector is open (as the original did), so they never need binding.
 */
public final class Keys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "keys"));

	public static final KeyMapping SELECTOR_UP = key("selectorUp", InputConstants.KEY_LBRACKET);
	public static final KeyMapping SELECTOR_DOWN = key("selectorDown", InputConstants.KEY_RBRACKET);
	/** Optional dedicated keys; by default Shift+[ and Shift+] do the same. */
	public static final KeyMapping SELECTOR_LEFT = key("selectorLeft", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping SELECTOR_RIGHT = key("selectorRight", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping FAVOURITE = key("favourite", InputConstants.KEY_GRAVE);
	public static final KeyMapping ABILITY = key("ability", InputConstants.KEY_R);
	public static final KeyMapping DEMORPH = key("demorph", InputConstants.UNKNOWN.getValue());
	/** Survival Mode: opens the server's biomass upgrade menu (iChun's Biomass Menu key was Shift+B). */
	public static final KeyMapping BIOMASS = key("biomass", InputConstants.KEY_B);
	/** Opens the client settings (also /morphclient). Unbound by default. */
	public static final KeyMapping SETTINGS = key("settings", InputConstants.UNKNOWN.getValue());
	/** Shows or hides the paper doll (and saves it). Unbound by default. */
	public static final KeyMapping DOLL = key("doll", InputConstants.UNKNOWN.getValue());

	/** Original keys were "holdable": held keys keep scrolling. Delay then repeat, in ticks. */
	private static final int HOLD_DELAY = 8;
	private static final int HOLD_REPEAT = 2;
	private static final KeyMapping[] HOLDABLE = {SELECTOR_UP, SELECTOR_DOWN, SELECTOR_LEFT, SELECTOR_RIGHT};
	private static final int[] HELD = new int[HOLDABLE.length];

	public interface Shift {
		void shift(boolean down, boolean horizontal);
	}

	private Keys() {}

	private static KeyMapping key(String name, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MorphClient.MOD_ID + "." + name, InputConstants.Type.KEYBOARD, code, CATEGORY));
	}

	public static void init() {
		// class load registers the mappings
	}

	public static boolean shiftDown() {
		return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
	}

	/** Repeat selector movement while a selector key is held down. */
	public static void tickHolds(Shift action, boolean inGame) {
		for (int i = 0; i < HOLDABLE.length; i++) {
			if (!inGame || !HOLDABLE[i].isDown()) {
				HELD[i] = 0;
				continue;
			}
			HELD[i]++;
			if (HELD[i] > HOLD_DELAY && (HELD[i] - HOLD_DELAY) % HOLD_REPEAT == 0) {
				boolean down = i == 1 || i == 3;
				boolean horizontal = i >= 2 || shiftDown();
				action.shift(down, horizontal);
			}
		}
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
