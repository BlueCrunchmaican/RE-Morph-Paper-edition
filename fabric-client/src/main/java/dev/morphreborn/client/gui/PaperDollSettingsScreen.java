package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The paper doll's own settings page, opened from RE:Morph Settings, in titled sections ({@link SettingsLayout#DOLL}). Changes show on
 * the doll at once.
 */
public final class PaperDollSettingsScreen extends SettingsPageScreen {
	public PaperDollSettingsScreen(@Nullable Screen lastScreen) {
		super(lastScreen, SettingsLayout.DOLL);
	}

	@Override
	protected void beforeOptions() {
		ClientConfig.dollToScreenPixels(); // the sliders are in screen pixels
	}
}
