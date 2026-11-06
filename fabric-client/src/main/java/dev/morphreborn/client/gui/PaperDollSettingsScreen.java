package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/** The paper doll's own settings page, opened from RE:Morph Settings. Changes show on the doll at once. */
public final class PaperDollSettingsScreen extends OptionsSubScreen {
	public PaperDollSettingsScreen(@Nullable Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("morphreborn.settings.doll.title"));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;
		ClientConfig c = ClientConfig.INSTANCE;
		this.list.addSmall(toggle("doll", c.doll, v -> c.doll = v),
				choice("dollShow", c.dollShow, List.of("ALWAYS", "MORPHED", "ACTIVE"), v -> c.dollShow = v));
		this.list.addSmall(choice("dollCorner", c.dollCorner, List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"), v -> c.dollCorner = v),
				new IntSlider("dollSize", c.dollSize, 20, 200, "", v -> c.dollSize = v));
		this.list.addSmall(new IntSlider("dollX", c.dollX, 0, 400, "", v -> c.dollX = v), new IntSlider("dollY", c.dollY, 0, 300, "", v -> c.dollY = v));
		this.list.addSmall(new IntSlider("dollAngle", c.dollAngle, -180, 180, "°", v -> c.dollAngle = v),
				choice("dollHead", c.dollHead, List.of("FOLLOW", "FORWARD"), v -> c.dollHead = v));
		this.list.addSmall(choice("dollLight", c.dollLight, List.of("BRIGHT", "WORLD"), v -> c.dollLight = v),
				toggle("dollVehicle", c.dollVehicle, v -> c.dollVehicle = v));
		this.list.addSmall(toggle("dollInMenus", c.dollInMenus, v -> c.dollInMenus = v),
				toggle("dollHideThirdPerson", c.dollHideThirdPerson, v -> c.dollHideThirdPerson = v));
		this.list.addSmall(toggle("dollHideDebug", c.dollHideDebug, v -> c.dollHideDebug = v),
				new IntSlider("dollOpacity", c.dollOpacity, 10, 100, "%", v -> c.dollOpacity = v));
	}

	private static CycleButton<String> choice(String key, String value, List<String> values, java.util.function.Consumer<String> set) {
		return CycleButton.<String>builder(v -> Component.translatable("morphreborn.settings." + key + "." + v.toLowerCase(java.util.Locale.ROOT)), value)
				.withValues(values)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	private static CycleButton<Boolean> toggle(String key, boolean value, java.util.function.Consumer<Boolean> set) {
		return CycleButton.onOffBuilder(value)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	/** A whole-number slider labelled "Name: value". */
	private static final class IntSlider extends AbstractSliderButton {
		private final String key;
		private final int min, max;
		private final String unit;
		private final IntConsumer set;

		IntSlider(String key, int value, int min, int max, String unit, IntConsumer set) {
			super(0, 0, 150, 20, Component.empty(), (value - min) / (double) (max - min));
			this.key = key;
			this.min = min;
			this.max = max;
			this.unit = unit;
			this.set = set;
			setTooltip(Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")));
			updateMessage();
		}

		private int current() {
			return Mth.floor(Mth.clampedLerp(this.value, min, max) + 0.5);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.generic_value", Component.translatable("morphreborn.settings." + key), current() + unit));
		}

		@Override
		protected void applyValue() {
			set.accept(current());
		}
	}

	@Override
	public void removed() {
		super.removed();
		ClientConfig.save();
	}
}
