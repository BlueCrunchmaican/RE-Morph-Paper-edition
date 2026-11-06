package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The paper doll's own settings page, opened from RE:Morph Settings, in titled sections. Changes show on the doll
 * at once.
 */
public final class PaperDollSettingsScreen extends OptionsSubScreen {
	public PaperDollSettingsScreen(@Nullable Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("morphreborn.settings.doll.title"));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) return;
		ClientConfig c = ClientConfig.INSTANCE;

		header("doll");
		this.list.addSmall(toggle("doll", c.doll, v -> c.doll = v), choice("dollShow", c.dollShow, List.of("ALWAYS", "MORPHED", "ACTIVE"), v -> c.dollShow = v));
		this.list.addSmall(new IntSlider("dollLinger", c.dollLinger, 0, 50, v -> String.format(Locale.ROOT, "%.1f s", v / 10F), v -> c.dollLinger = v), null);

		header("placement");
		this.list.addSmall(choice("dollCorner", c.dollCorner, List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"), v -> c.dollCorner = v),
				new IntSlider("dollSize", c.dollSize, 20, 200, String::valueOf, v -> c.dollSize = v));
		this.list.addSmall(new IntSlider("dollX", c.dollX, 0, 400, String::valueOf, v -> c.dollX = v),
				new IntSlider("dollY", c.dollY, 0, 300, String::valueOf, v -> c.dollY = v));

		header("look");
		this.list.addSmall(new IntSlider("dollAngle", c.dollAngle, -180, 180, v -> v + "°", v -> c.dollAngle = v),
				new IntSlider("dollTilt", c.dollTilt, -60, 60, v -> v + "°", v -> c.dollTilt = v));
		this.list.addSmall(choice("dollHead", c.dollHead, List.of("FOLLOW", "TURN", "LOOK", "FORWARD", "AT_VIEWER"), v -> c.dollHead = v),
				choice("dollScale", c.dollScale, List.of("TRUE_SIZE", "FILL"), v -> c.dollScale = v));
		this.list.addSmall(choice("dollLight", c.dollLight, List.of("BRIGHT", "WORLD"), v -> c.dollLight = v),
				new IntSlider("dollOpacity", c.dollOpacity, 10, 100, v -> v + "%", v -> c.dollOpacity = v));
		this.list.addSmall(choice("dollOutline", c.dollOutline, List.of("OFF", "THIN", "THICK"), v -> c.dollOutline = v),
				new IntSlider("dollOutlineStrength", c.dollOutlineStrength, 10, 100, v -> v + "%", v -> c.dollOutlineStrength = v));

		header("pose");
		this.list.addSmall(toggle("dollLockElytra", c.dollLockElytra, v -> c.dollLockElytra = v), toggle("dollLockSpin", c.dollLockSpin, v -> c.dollLockSpin = v));
		this.list.addSmall(toggle("dollVehicle", c.dollVehicle, v -> c.dollVehicle = v), null);

		header("hide");
		this.list.addSmall(toggle("dollInMenus", c.dollInMenus, v -> c.dollInMenus = v),
				toggle("dollHideThirdPerson", c.dollHideThirdPerson, v -> c.dollHideThirdPerson = v));
		this.list.addSmall(toggle("dollHideDebug", c.dollHideDebug, v -> c.dollHideDebug = v), toggle("dollHideMusic", c.dollHideMusic, v -> c.dollHideMusic = v));

		header("showWhen");
		List<String> triggers = PaperDoll.TRIGGERS;
		for (int i = 0; i < triggers.size(); i += 2) {
			AbstractWidget second = i + 1 < triggers.size() ? trigger(c, triggers.get(i + 1)) : null;
			this.list.addSmall(trigger(c, triggers.get(i)), second);
		}
	}

	private void header(String key) {
		this.list.addHeader(Component.translatable("morphreborn.settings.doll.section." + key));
	}

	/** One "While Active" situation: on shows the doll while it happens. */
	private static CycleButton<Boolean> trigger(ClientConfig c, String id) {
		String key = "morphreborn.settings.dollWhen." + id.toLowerCase(Locale.ROOT);
		return CycleButton.onOffBuilder(c.dollShowWhen.contains(id))
				.withTooltip(v -> Tooltip.create(Component.translatable(key + ".tooltip")))
				.create(Component.translatable(key), (button, v) -> {
					c.dollShowWhen.remove(id);
					if (v) c.dollShowWhen.add(id);
				});
	}

	private static CycleButton<String> choice(String key, String value, List<String> values, Consumer<String> set) {
		return CycleButton.<String>builder(v -> Component.translatable("morphreborn.settings." + key + "." + v.toLowerCase(Locale.ROOT)), value)
				.withValues(values)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	private static CycleButton<Boolean> toggle(String key, boolean value, Consumer<Boolean> set) {
		return CycleButton.onOffBuilder(value)
				.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")))
				.create(Component.translatable("morphreborn.settings." + key), (button, v) -> set.accept(v));
	}

	/** A whole-number slider labelled "Name: value", the value shown through {@code format}. */
	private static final class IntSlider extends AbstractSliderButton {
		private final String key;
		private final int min, max;
		private final IntFunction<String> format;
		private final IntConsumer set;

		IntSlider(String key, int value, int min, int max, IntFunction<String> format, IntConsumer set) {
			super(0, 0, 150, 20, Component.empty(), (value - min) / (double) (max - min));
			this.key = key;
			this.min = min;
			this.max = max;
			this.format = format;
			this.set = set;
			setTooltip(Tooltip.create(Component.translatable("morphreborn.settings." + key + ".tooltip")));
			updateMessage();
		}

		private int current() {
			return Mth.floor(Mth.clampedLerp(this.value, min, max) + 0.5);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.generic_value", Component.translatable("morphreborn.settings." + key), format.apply(current())));
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
