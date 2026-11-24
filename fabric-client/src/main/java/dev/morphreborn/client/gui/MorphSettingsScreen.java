package dev.morphreborn.client.gui;

import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * RE:Morph's client settings (config/morphreborn-client.json), laid out like a vanilla options page. Opened with the
 * "RE:Morph Settings" key (unbound by default) or {@code /morphclient}. Everything here only changes what this
 * player's own game shows; morphing, abilities and what other players see are untouched.
 * <p>
 * The main page holds the general switches, two buttons to the other pages (Paper Doll and Arm Forms), what morphing looks like, the
 * selector and what is drawn on the screen: {@link SettingsLayout#MAIN}.
 */
public final class MorphSettingsScreen extends SettingsPageScreen {
	public MorphSettingsScreen(@Nullable Screen lastScreen) {
		super(lastScreen, SettingsLayout.MAIN);
	}

	/**
	 * Arm forms' own page (the Arsenal, servers from 8.0.0), opened from the main page: your arm's look, colour, pulse speed and the
	 * lantern's brightness (the settings other players with the mod see too), the bar under an arm in the hotbar, the wheel's size, and
	 * how arms and the lantern are drawn for you ({@link SettingsLayout#ARMS}). None of it changes what the arm does.
	 */
	public static final class ArmSettings extends SettingsPageScreen {
		public ArmSettings(@Nullable Screen lastScreen) {
			super(lastScreen, SettingsLayout.ARMS);
		}
	}
}
