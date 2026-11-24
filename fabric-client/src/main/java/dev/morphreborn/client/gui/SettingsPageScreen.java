package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.gui.SettingsLayout.Page;
import dev.morphreborn.client.gui.SettingsLayout.Section;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A settings page laid out like a vanilla options page: titled sections, two settings to a row, scrolling when the window is short. What
 * goes on it, and in which order, is a {@link Page} of {@link SettingsLayout}; the widgets are {@link SettingsWidgets}'. The settings file
 * is saved when the page closes.
 */
abstract class SettingsPageScreen extends OptionsSubScreen {
	private final Page page;

	SettingsPageScreen(@Nullable Screen lastScreen, Page page) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable(page.titleKey()));
		this.page = page;
	}

	/** Runs before the widgets are made: a page that has to bring the settings up to date first does it here. */
	protected void beforeOptions() {}

	@Override
	protected final void addOptions() {
		if (this.list == null) return;
		beforeOptions();
		SettingsWidgets.Host host = new SettingsWidgets.Host(this, this::rebuild);
		for (Section section : page.sections()) {
			this.list.addHeader(Component.translatable(page.sectionKey(section)));
			List<AbstractWidget> row = new ArrayList<>(section.settings().size());
			for (SettingsLayout.Setting setting : section.settings()) row.add(SettingsWidgets.make(setting, host));
			this.list.addSmall(row);
		}
	}

	/**
	 * Builds the page again, so a changed setting (Tooltips) shows at once. The layout keeps the title, the list and the Done button it was
	 * given: rebuilding the widgets alone added a second list on top of the old one, and the old widgets' hover texts stayed until the screen
	 * was reopened (seen 2026-11-18).
	 */
	private void rebuild() {
		this.layout.removeChildren();
		rebuildWidgets();
	}

	@Override
	public void removed() {
		super.removed();
		ClientConfig.save();
	}
}
