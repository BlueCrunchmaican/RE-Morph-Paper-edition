package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.gui.SettingsLayout.Setting;
import dev.morphreborn.client.net.ClientNet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The widget for each setting of {@link SettingsLayout}, one place for all three pages: every widget is 150 wide, named
 * {@code morphreborn.settings.<id>}, with the hover text {@code .tooltip} (none with Tooltips off, see {@link TipUi}). A setting's
 * value is read when its page is built and written the moment it changes; the page saves the file when it closes.
 */
final class SettingsWidgets {
	/** What a widget needs from the page it sits on: the page itself (the parent of one it opens) and a way to rebuild it. */
	record Host(Screen screen, Runnable rebuild) {}

	/** The Arsenal wheel's size, as a share of the screen's shorter side: ClientConfig keeps it to this range. */
	private static final double WHEEL_MIN = 0.2, WHEEL_MAX = 1.0;

	private static final Map<String, Function<Host, AbstractWidget>> MAKERS = new LinkedHashMap<>();

	static {
		// every setting is read inside its lambda, when the page is built: ClientConfig.INSTANCE is replaced when the file loads
		// ---- the main page
		put("tooltips", h -> {
			ClientConfig cc = ClientConfig.INSTANCE;
			// the Tooltips switch's own tooltip is always there, so the way back is never hidden; flipping it rebuilds the page, so the other
			// widgets' hover tooltips appear or go at once
			return CycleButton.onOffBuilder(cc.tooltips)
					.withTooltip(v -> Tooltip.create(Component.translatable("morphreborn.settings.tooltips.tooltip")))
					.create(name("tooltips"), (button, v) -> {
						cc.tooltips = v;
						h.rebuild().run();
					});
		});
		toggle("updateCheck", () -> ClientConfig.INSTANCE.updateCheck, v -> ClientConfig.INSTANCE.updateCheck = v);
		pageButton("doll.open", h -> new PaperDollSettingsScreen(h.screen()));
		pageButton("arms.open", h -> new MorphSettingsScreen.ArmSettings(h.screen()));
		choice("absorbMorph", () -> ClientConfig.INSTANCE.absorbMorph, v -> ClientConfig.INSTANCE.absorbMorph = v);
		choice("absorbBiomass", () -> ClientConfig.INSTANCE.absorbBiomass, v -> ClientConfig.INSTANCE.absorbBiomass = v);
		// RE:Morph's own animation, or what players without the mod see
		put("transition", h -> CycleButton.booleanBuilder(Component.translatable("morphreborn.settings.animation.morph"), Component.translatable("morphreborn.settings.animation.vanilla"),
						ClientConfig.INSTANCE.transitionAnimation)
				.withTooltip(v -> TipUi.widget(Component.translatable("morphreborn.settings.transition.tooltip")))
				.create(name("transition"), (button, v) -> ClientConfig.INSTANCE.transitionAnimation = v));
		toggle("nameTags", () -> ClientConfig.INSTANCE.showNameTags, v -> ClientConfig.INSTANCE.showNameTags = v);
		toggle("mouse", () -> ClientConfig.INSTANCE.selectorAllowMouseControl, v -> ClientConfig.INSTANCE.selectorAllowMouseControl = v);
		toggle("invertScroll", () -> ClientConfig.INSTANCE.invertScroll, v -> ClientConfig.INSTANCE.invertScroll = v);
		choice("biomassBar", () -> ClientConfig.INSTANCE.biomassBar, v -> ClientConfig.INSTANCE.biomassBar = v);
		choice("frenzyMeter", () -> ClientConfig.INSTANCE.frenzyMeter, v -> ClientConfig.INSTANCE.frenzyMeter = v);
		toggle("unlockToasts", () -> ClientConfig.INSTANCE.unlockToasts, v -> ClientConfig.INSTANCE.unlockToasts = v);

		// ---- the Arm Forms page
		choice("armLook", () -> ArmProtocol.LOOK_NAMES.get(ArmProtocol.parseLook(ClientConfig.INSTANCE.armLook)), v -> ClientConfig.INSTANCE.armLook = v);
		pageButton("armColour.open", h -> new ArmColourScreen(h.screen()));
		put("armPulseSpeed", h -> new PulseSpeedSlider(ClientConfig.INSTANCE.armPulseSpeed, v -> ClientConfig.INSTANCE.armPulseSpeed = v));
		// the lantern's brightness is the light everyone sees, so it goes to the server (the same as Sneak+F with the lantern lit, or
		// /morph arm lantern bright N), which keeps it on the player
		choice("lanternBrightness", () -> String.valueOf(lanternBrightnessNow(ClientConfig.INSTANCE)), v -> {
			ClientConfig.INSTANCE.lanternBrightness = Integer.parseInt(v);
			sendLanternBrightness(ClientConfig.INSTANCE.lanternBrightness);
		});
		choice("armBar", () -> ClientConfig.INSTANCE.armBar, v -> ClientConfig.INSTANCE.armBar = v);
		put("armWheelScale", h -> new WheelScaleSlider(ClientConfig.INSTANCE.armWheelScale, v -> ClientConfig.INSTANCE.armWheelScale = v));
		choice("armDetail", () -> ClientConfig.INSTANCE.armDetail, v -> ClientConfig.INSTANCE.armDetail = v);
		toggle("armGlow", () -> ClientConfig.INSTANCE.armGlow, v -> ClientConfig.INSTANCE.armGlow = v);
		choice("armDissolve", () -> ClientConfig.INSTANCE.armDissolve, v -> ClientConfig.INSTANCE.armDissolve = v);
		toggle("armDrips", () -> ClientConfig.INSTANCE.armDrips, v -> ClientConfig.INSTANCE.armDrips = v);
		toggle("armSenseOutline", () -> ClientConfig.INSTANCE.armSenseOutline, v -> ClientConfig.INSTANCE.armSenseOutline = v);
		toggle("lanternDynamicLight", () -> ClientConfig.INSTANCE.lanternDynamicLight, v -> ClientConfig.INSTANCE.lanternDynamicLight = v);
		toggle("lanternMotes", () -> ClientConfig.INSTANCE.lanternMotes, v -> ClientConfig.INSTANCE.lanternMotes = v);
		toggle("lanternGlow", () -> ClientConfig.INSTANCE.lanternGlow, v -> ClientConfig.INSTANCE.lanternGlow = v);

		// ---- the Paper Doll page (the sliders are in screen pixels: the page converts an older file before it builds them)
		toggle("doll", () -> ClientConfig.INSTANCE.doll, v -> ClientConfig.INSTANCE.doll = v);
		choice("dollShow", () -> ClientConfig.INSTANCE.dollShow, v -> ClientConfig.INSTANCE.dollShow = v);
		toggle("dollVehicle", () -> ClientConfig.INSTANCE.dollVehicle, v -> ClientConfig.INSTANCE.dollVehicle = v);
		intSlider("dollLinger", () -> ClientConfig.INSTANCE.dollLinger, 0, 50, v -> String.format(Locale.ROOT, "%.1f s", v / 10F), v -> ClientConfig.INSTANCE.dollLinger = v);
		choice("dollCorner", () -> ClientConfig.INSTANCE.dollCorner, v -> ClientConfig.INSTANCE.dollCorner = v);
		intSlider("dollSize", () -> ClientConfig.INSTANCE.dollSize, 40, 800, v -> v + " px", v -> ClientConfig.INSTANCE.dollSize = v);
		intSlider("dollX", () -> ClientConfig.INSTANCE.dollX, 0, 1600, v -> v + " px", v -> ClientConfig.INSTANCE.dollX = v);
		intSlider("dollY", () -> ClientConfig.INSTANCE.dollY, 0, 1200, v -> v + " px", v -> ClientConfig.INSTANCE.dollY = v);
		intSlider("dollAngle", () -> ClientConfig.INSTANCE.dollAngle, -180, 180, v -> v + "°", v -> ClientConfig.INSTANCE.dollAngle = v);
		intSlider("dollTilt", () -> ClientConfig.INSTANCE.dollTilt, -60, 60, v -> v + "°", v -> ClientConfig.INSTANCE.dollTilt = v);
		choice("dollHead", () -> ClientConfig.INSTANCE.dollHead, v -> ClientConfig.INSTANCE.dollHead = v);
		choice("dollScale", () -> ClientConfig.INSTANCE.dollScale, v -> ClientConfig.INSTANCE.dollScale = v);
		toggle("dollLockElytra", () -> ClientConfig.INSTANCE.dollLockElytra, v -> ClientConfig.INSTANCE.dollLockElytra = v);
		toggle("dollLockSpin", () -> ClientConfig.INSTANCE.dollLockSpin, v -> ClientConfig.INSTANCE.dollLockSpin = v);
		choice("dollLight", () -> ClientConfig.INSTANCE.dollLight, v -> ClientConfig.INSTANCE.dollLight = v);
		intSlider("dollOpacity", () -> ClientConfig.INSTANCE.dollOpacity, 10, 100, v -> v + "%", v -> ClientConfig.INSTANCE.dollOpacity = v);
		intSlider("dollVignette", () -> ClientConfig.INSTANCE.dollVignette, 0, ClientConfig.VIGNETTE_MAX,
				v -> v == 0 ? Component.translatable("morphreborn.settings.dollVignette.off").getString() : v + " px", v -> ClientConfig.INSTANCE.dollVignette = v);
		intSlider("dollOutlineStrength", () -> ClientConfig.INSTANCE.dollOutlineStrength, 10, 100, v -> v + "%", v -> ClientConfig.INSTANCE.dollOutlineStrength = v);
		toggle("dollInMenus", () -> ClientConfig.INSTANCE.dollInMenus, v -> ClientConfig.INSTANCE.dollInMenus = v);
		toggle("dollHideThirdPerson", () -> ClientConfig.INSTANCE.dollHideThirdPerson, v -> ClientConfig.INSTANCE.dollHideThirdPerson = v);
		toggle("dollHideDebug", () -> ClientConfig.INSTANCE.dollHideDebug, v -> ClientConfig.INSTANCE.dollHideDebug = v);
		toggle("dollHideMusic", () -> ClientConfig.INSTANCE.dollHideMusic, v -> ClientConfig.INSTANCE.dollHideMusic = v);
		// the "While Active" situations: one on/off each, kept together in the list the doll reads
		for (String trigger : SettingsLayout.DOLL_TRIGGERS) {
			toggle(SettingsLayout.triggerId(trigger), () -> ClientConfig.INSTANCE.dollShowWhen.contains(trigger), v -> {
				ClientConfig.INSTANCE.dollShowWhen.remove(trigger);
				if (v) ClientConfig.INSTANCE.dollShowWhen.add(trigger);
			});
		}
	}

	private SettingsWidgets() {}

	/** Whether {@code id} has a widget (the layout test checks that every setting of the pages does). */
	static boolean has(String id) {
		return MAKERS.containsKey(id);
	}

	/** The widget for {@code setting}, 150 wide. */
	static AbstractWidget make(Setting setting, Host host) {
		Function<Host, AbstractWidget> maker = MAKERS.get(setting.id());
		if (maker == null) throw new IllegalStateException("No widget for the setting " + setting.id());
		return maker.apply(host);
	}

	private static void put(String id, Function<Host, AbstractWidget> maker) {
		MAKERS.put(id, maker);
	}

	private static Component name(String id) {
		return Component.translatable("morphreborn.settings." + id);
	}

	private static void toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
		put(id, h -> CycleButton.onOffBuilder(get.getAsBoolean())
				.withTooltip(v -> TipUi.widget(Component.translatable("morphreborn.settings." + id + ".tooltip")))
				.create(name(id), (button, v) -> set.accept(v)));
	}

	/** A setting with named choices: the values come from the layout, each one's text is {@code morphreborn.settings.<id>.<value in lower case>}. */
	private static void choice(String id, Supplier<String> get, Consumer<String> set) {
		List<String> values = SettingsLayout.PAGES.stream().flatMap(p -> p.settings().stream()).filter(s -> s.id().equals(id)).findFirst()
				.orElseThrow(() -> new IllegalStateException("The layout has no setting " + id)).values();
		put(id, h -> CycleButton.<String>builder(v -> Component.translatable("morphreborn.settings." + id + "." + v.toLowerCase(Locale.ROOT)), get.get())
				.withValues(values)
				.withTooltip(v -> TipUi.widget(Component.translatable("morphreborn.settings." + id + ".tooltip")))
				.create(name(id), (button, v) -> set.accept(v)));
	}

	private static void intSlider(String id, java.util.function.IntSupplier get, int min, int max, IntFunction<String> format, IntConsumer set) {
		put(id, h -> new IntSlider(id, get.getAsInt(), min, max, format, set));
	}

	/** A button that opens another page over this one. */
	private static void pageButton(String id, Function<Host, Screen> open) {
		put(id, h -> Button.builder(name(id), button -> Minecraft.getInstance().gui.setScreen(open.apply(h)))
				.tooltip(TipUi.widget(Component.translatable("morphreborn.settings." + id + ".tooltip")))
				.width(SettingsLayout.COLUMN_W)
				.build());
	}

	// ---------------------------------------------------------------- the lantern's brightness

	/** The brightness the server last said for your lantern, else the one chosen here last. */
	private static int lanternBrightnessNow(ClientConfig c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			ArmState st = Arms.state(mc.player.getUUID());
			if (st != null && st.lanternBright > 0) return st.lanternBright;
		}
		return Math.clamp(c.lanternBrightness, 1, 5);
	}

	/** Tells the server (one that offers arm forms) the brightness chosen; it keeps it and shows it to everyone. */
	private static void sendLanternBrightness(int bright) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientNet.armsOffered()) return;
		mc.player.connection.sendCommand("morph arm lantern bright " + Math.clamp(bright, 1, 5));
	}

	// ---------------------------------------------------------------- the sliders

	/** A whole-number slider labelled "Name: value", the value shown through {@code format}. */
	private static final class IntSlider extends AbstractSliderButton {
		private final String id;
		private final int min, max;
		private final IntFunction<String> format;
		private final IntConsumer set;

		IntSlider(String id, int value, int min, int max, IntFunction<String> format, IntConsumer set) {
			super(0, 0, SettingsLayout.COLUMN_W, 20, Component.empty(), (value - min) / (double) (max - min));
			this.id = id;
			this.min = min;
			this.max = max;
			this.format = format;
			this.set = set;
			setTooltip(TipUi.widget(Component.translatable("morphreborn.settings." + id + ".tooltip")));
			updateMessage();
		}

		private int current() {
			return Mth.floor(Mth.clampedLerp(this.value, min, max) + 0.5);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.generic_value", name(id), format.apply(current())));
		}

		@Override
		protected void applyValue() {
			set.accept(current());
		}
	}

	/** The pulse speed, a quarter to three times the usual in steps of 0.05, labelled "Pulse Speed: 1.00x". */
	private static final class PulseSpeedSlider extends AbstractSliderButton {
		private final Consumer<Float> set;

		PulseSpeedSlider(float speed, Consumer<Float> set) {
			super(0, 0, SettingsLayout.COLUMN_W, 20, Component.empty(), ArmProtocol.pulseSpeedToSlider(speed));
			this.set = set;
			setTooltip(TipUi.widget(Component.translatable("morphreborn.settings.armPulseSpeed.tooltip")));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.generic_value", name("armPulseSpeed"),
					String.format(Locale.ROOT, "%.2fx", ArmProtocol.pulseSpeedFromSlider(this.value))));
		}

		@Override
		protected void applyValue() {
			set.accept(ArmProtocol.pulseSpeedFromSlider(this.value));
		}
	}

	/** The Arsenal wheel's size, in steps of 5% of the screen, labelled "Arsenal Wheel Size: 75%". */
	private static final class WheelScaleSlider extends AbstractSliderButton {
		private final java.util.function.DoubleConsumer set;

		WheelScaleSlider(double value, java.util.function.DoubleConsumer set) {
			super(0, 0, SettingsLayout.COLUMN_W, 20, Component.empty(), (Mth.clamp(value, WHEEL_MIN, WHEEL_MAX) - WHEEL_MIN) / (WHEEL_MAX - WHEEL_MIN));
			this.set = set;
			setTooltip(TipUi.widget(Component.translatable("morphreborn.settings.armWheelScale.tooltip")));
			updateMessage();
		}

		/** The slider's value in whole steps of 5%, so the saved number stays tidy. */
		private double current() {
			return Math.round(Mth.clampedLerp(this.value, WHEEL_MIN, WHEEL_MAX) * 20.0) / 20.0;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.generic_value", name("armWheelScale"), Math.round(current() * 100) + "%"));
		}

		@Override
		protected void applyValue() {
			set.accept(current());
		}
	}
}
