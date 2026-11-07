package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * RE:Morph's client settings (config/morphreborn-client.json), laid out like a vanilla options page. Opened with the
 * "RE:Morph Settings" key (unbound by default) or {@code /morphclient}. Everything here only changes what this
 * player's own game shows; morphing, abilities and what other players see are untouched.
 */
public final class MorphSettingsScreen extends OptionsSubScreen {
	public MorphSettingsScreen(@Nullable Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("morphreborn.settings.title"));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;
		ClientConfig c = ClientConfig.INSTANCE;
		List<String> absorbStyles = List.of("TENDRILS", "RED_TENDRILS", "WISPS", "PLAIN", "OFF");
		this.list.addSmall(choice("absorbMorph", c.absorbMorph, absorbStyles, v -> c.absorbMorph = v),
				choice("absorbBiomass", c.absorbBiomass, absorbStyles, v -> c.absorbBiomass = v));
		this.list.addSmall(animation("transition", c.transitionAnimation, v -> c.transitionAnimation = v),
				toggle("unlockToasts", c.unlockToasts, v -> c.unlockToasts = v));
		this.list.addSmall(toggle("nameTags", c.showNameTags, v -> c.showNameTags = v), toggle("mouse", c.selectorAllowMouseControl, v -> c.selectorAllowMouseControl = v));
		this.list.addSmall(toggle("invertScroll", c.invertScroll, v -> c.invertScroll = v), toggle("updateCheck", c.updateCheck, v -> c.updateCheck = v));
		this.list.addSmall(CycleButton.<String>builder(v -> Component.translatable("morphreborn.settings.biomassBar." + v.toLowerCase(java.util.Locale.ROOT)), c.biomassBar)
				.withValues("CHANGES", "ALWAYS", "OFF")
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings.biomassBar.tooltip")))
				.create(Component.translatable("morphreborn.settings.biomassBar"), (button, v) -> c.biomassBar = v),
				net.minecraft.client.gui.components.Button.builder(Component.translatable("morphreborn.settings.doll.open"),
								button -> Minecraft.getInstance().gui.setScreen(new PaperDollSettingsScreen(this)))
						.tooltip(Tooltip.create(Component.translatable("morphreborn.settings.doll.open.tooltip")))
						.width(150)
						.build());
	}

	/** RE:Morph's own animation, or what players without the mod see. */
	private static CycleButton<Boolean> animation(String key, boolean value, Consumer<Boolean> set) {
		return CycleButton.booleanBuilder(Component.translatable("morphreborn.settings.animation.morph"), Component.translatable("morphreborn.settings.animation.vanilla"),
						value)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	/** A setting with named choices; each choice reads morphreborn.settings.<key>.<choice in lower case>. */
	private static CycleButton<String> choice(String key, String value, List<String> values, Consumer<String> set) {
		return CycleButton.<String>builder(v -> Component.translatable("morphreborn.settings." + key + "." + v.toLowerCase(java.util.Locale.ROOT)), value)
				.withValues(values)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	private static CycleButton<Boolean> toggle(String key, boolean value, Consumer<Boolean> set) {
		return CycleButton.onOffBuilder(value)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	@Override
	public void removed() {
		super.removed();
		ClientConfig.save();
	}
}
