package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Tooltips setting as the screens use it (decided 2026-11-17): one switch in {@link ClientConfig#tooltips}. A tooltip is explanation (what a
 * thing does in detail, how to use it, which key), so with it off the settings screens' own hover tooltips are not made, and every wheel and
 * tree line that explains is a {@code dev.morphreborn.client.tips.Tagged} tip left out by the same filter. Names, levels, state, biomass costs
 * and rates are never behind it.
 */
public final class TipUi {
	private TipUi() {}

	/** Whether explanation lines are shown. */
	public static boolean on() {
		return ClientConfig.INSTANCE.tooltips;
	}

	/** A hover tooltip for a settings widget, or null (none) with Tooltips off. */
	public static @Nullable Tooltip widget(Component text) {
		return on() ? Tooltip.create(text) : null;
	}
}
