package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The arm colour picker (Arm Forms settings, "Arm Colour..."): red, green and blue sliders, a hex box, a row of team
 * colours, and Reset to the default red. Your arm takes the colour at once; the server passes it on to other players with
 * the mod when the screen closes or a moment after it changes (ArmColours). The swatches show the colour as the arm's two
 * red layers are drawn in it: the red static and the glow.
 */
public final class ArmColourScreen extends Screen {
	/** Team colours, one click each: red (the default), orange, yellow, green, cyan, blue, purple, pink, white. */
	private static final int[] PRESETS = {ArmProtocol.COLOUR_DEFAULT, 0xFF8A1E, 0xFFD21E, 0x3AD23A, 0x1ED2D2, 0x2A5BFF, 0x9A3AFF, 0xFF4AC8, 0xF0F0F0};
	/** The brightest red-static and glow texels, recoloured for the swatches. */
	private static final int RED_TEXEL = 0xFF69181A, GLOW_TEXEL = 0xFFFF5A4A, GLOW_DARK_TEXEL = 0xFF7A1010;

	private final @Nullable Screen lastScreen;
	private int rgb;
	private final ChannelSlider[] sliders = new ChannelSlider[3];
	private @Nullable EditBox hex;
	/** Set while the widgets are being brought in line with {@link #rgb}, so they don't echo back. */
	private boolean syncing;
	/** The help line, wrapped to the screen, and where the colour preview starts (both set by {@link #init}). */
	private java.util.List<FormattedCharSequence> help = java.util.List.of();
	private int previewY;

	public ArmColourScreen(@Nullable Screen lastScreen) {
		super(Component.translatable("morphreborn.settings.armColour.title"));
		this.lastScreen = lastScreen;
		this.rgb = ArmProtocol.parseColour(ClientConfig.INSTANCE.armColour);
	}

	@Override
	protected void init() {
		int cx = width / 2;
		// laid out from the screen's height (2026-11-18), not from fixed y: at 240 scaled px tall the last line touched Done. The help line wraps to
		// the width, the rows follow it, and Done is the footer
		help = TipUi.on() ? UiText.wrap(font, Component.translatable("morphreborn.settings.armColour.help"), width - 24) : java.util.List.of();
		int y = 24 + (help.isEmpty() ? 0 : help.size() * 10 + 2) + 6;
		String[] names = {"red", "green", "blue"};
		for (int i = 0; i < 3; i++) {
			int shift = 16 - 8 * i;
			ChannelSlider slider = new ChannelSlider(cx - 100, y + i * 22, 200, Component.translatable("morphreborn.settings.armColour." + names[i]),
					(rgb >> shift) & 0xFF, v -> set((rgb & ~(0xFF << shift)) | (v << shift)));
			sliders[i] = addRenderableWidget(slider);
		}
		y += 3 * 22 + 2;
		EditBox box = new EditBox(font, cx - 100, y, 96, 20, Component.translatable("morphreborn.settings.armColour.hex"));
		box.setMaxLength(7);
		box.setValue(ArmProtocol.colourHex(rgb));
		box.setResponder(text -> {
			if (syncing) return;
			String s = text.trim();
			if (s.startsWith("#")) s = s.substring(1);
			if (s.length() == 6 && s.chars().allMatch(c -> Character.digit(c, 16) >= 0)) set(Integer.parseInt(s, 16), false);
		});
		box.setTooltip(TipUi.widget(Component.translatable("morphreborn.settings.armColour.hex.tooltip")));
		hex = addRenderableWidget(box);
		addRenderableWidget(Button.builder(Component.translatable("morphreborn.settings.armColour.reset"), b -> set(ArmProtocol.COLOUR_DEFAULT))
				.tooltip(TipUi.widget(Component.translatable("morphreborn.settings.armColour.reset.tooltip")))
				.bounds(cx + 4, y, 96, 20).build());
		y += 24;
		int size = 20, gap = 2, row = PRESETS.length * size + (PRESETS.length - 1) * gap;
		for (int i = 0; i < PRESETS.length; i++) {
			int preset = PRESETS[i];
			addRenderableWidget(new Swatch(cx - row / 2 + i * (size + gap), y, size, preset, () -> set(preset)));
		}
		previewY = y + 24;
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(cx - 100, height - 26, 200, 20).build());
	}

	private void set(int colour) {
		set(colour, true);
	}

	/** The colour is now {@code colour}: kept in the settings at once (the arm shows it), widgets brought in line. */
	private void set(int colour, boolean updateHex) {
		rgb = colour & 0xFFFFFF;
		ClientConfig.INSTANCE.armColour = ArmProtocol.colourHex(rgb);
		syncing = true;
		try {
			for (int i = 0; i < 3; i++) if (sliders[i] != null) sliders[i].show((rgb >> (16 - 8 * i)) & 0xFF);
			if (updateHex && hex != null) hex.setValue(ArmProtocol.colourHex(rgb));
		} finally {
			syncing = false;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int cx = width / 2;
		g.centeredText(font, title, cx, 10, 0xFFFFFFFF);
		int helpY = 24;
		for (FormattedCharSequence line : help) {
			g.centeredText(font, line, cx, helpY, 0xFFA0A0A0);
			helpY += 10;
		}
		// the swatches: the red static and the glow (its dark and bright ends), as the arm is drawn in this colour
		int y = previewY;
		int w = 60, h = 16;
		int[] shown = {ArmTint.recolour(RED_TEXEL, rgb), ArmTint.recolour(GLOW_DARK_TEXEL, rgb), ArmTint.recolour(GLOW_TEXEL, rgb)};
		String[] labels = {"static", "glow_dark", "glow"};
		for (int i = 0; i < 3; i++) {
			int x = cx - 3 * w / 2 - 4 + i * (w + 4);
			g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
			g.fill(x, y, x + w, y + h, 0xFF000000 | shown[i]);
			g.centeredText(font, Component.translatable("morphreborn.settings.armColour.swatch." + labels[i]), x + w / 2, y + h + 3, 0xFFC0C0C0);
		}
		// only where it leaves Done its room
		if (rgb == ArmProtocol.COLOUR_DEFAULT && y + h + 14 + font.lineHeight <= height - 28) {
			g.centeredText(font, Component.translatable("morphreborn.settings.armColour.isDefault"), cx, y + h + 16, 0xFF808080);
		}
	}

	@Override
	public void onClose() {
		ClientConfig.save();
		minecraft.gui.setScreen(lastScreen);
	}

	@Override
	public void removed() {
		super.removed();
		ClientConfig.save();
	}

	/** One channel, 0 to 255, labelled "Red: 255". */
	private static final class ChannelSlider extends AbstractSliderButton {
		private final Component label;
		private final IntConsumer set;
		private boolean quiet;

		ChannelSlider(int x, int y, int width, Component label, int value, IntConsumer set) {
			super(x, y, width, 20, Component.empty(), value / 255.0);
			this.label = label;
			this.set = set;
			updateMessage();
		}

		private int current() {
			return (int) Math.round(this.value * 255.0);
		}

		/** Moves the slider to {@code v} without reporting it back. */
		void show(int v) {
			quiet = true;
			this.value = v / 255.0;
			updateMessage();
			quiet = false;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.generic_value", label, current()));
		}

		@Override
		protected void applyValue() {
			if (!quiet) set.accept(current());
		}
	}

	/** A preset colour: a square of it that sets it when clicked. */
	private static final class Swatch extends Button {
		private final int colour;

		Swatch(int x, int y, int size, int colour, Runnable onPress) {
			super(x, y, size, size, Component.literal(ArmProtocol.colourHex(colour)), b -> onPress.run(), DEFAULT_NARRATION);
			this.colour = colour;
			setTooltip(Tooltip.create(Component.literal(ArmProtocol.colourHex(colour))));
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			int x = getX(), y = getY(), s = getWidth();
			g.fill(x, y, x + s, y + s, isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF000000);
			g.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0xFF000000 | colour);
		}
	}
}
