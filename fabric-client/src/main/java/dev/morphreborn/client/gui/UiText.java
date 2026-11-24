package dev.morphreborn.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Text drawing helpers that keep it readable at every window size (the 2026-11-18 layout round): wrapping by pixel width as vanilla's
 * tooltips do (not by character count), ellipsis for one line, a dark backing for text drawn over the world, and text drawn at a
 * whole number of device texels per font pixel ({@link UiLayout#textScale}).
 */
public final class UiText {
	private UiText() {}

	/** {@code text} split into lines no wider than {@code maxPx} font pixels (vanilla's own word wrap, as its tooltips use). */
	public static List<FormattedCharSequence> wrap(Font font, Component text, int maxPx) {
		if (text.getString().isEmpty()) return List.of(FormattedCharSequence.EMPTY);
		return font.split(text, Math.max(8, maxPx));
	}

	/** Every line of {@code lines} wrapped to {@code maxPx}, a blank line kept as one blank line. */
	public static List<FormattedCharSequence> wrapAll(Font font, List<? extends Component> lines, int maxPx) {
		List<FormattedCharSequence> out = new ArrayList<>(lines.size());
		for (Component line : lines) out.addAll(wrap(font, line, maxPx));
		return out;
	}

	/** {@code text} cut to {@code maxPx} with an ellipsis when it is wider, in its own style. */
	public static Component fit(Font font, Component text, int maxPx) {
		if (font.width(text) <= maxPx) return text;
		int ellipsis = font.width("...");
		FormattedText cut = font.substrByWidth(text, Math.max(0, maxPx - ellipsis));
		Style style = text.getStyle();
		return Component.literal(cut.getString() + "...").withStyle(style);
	}

	/** A translucent black strip behind text drawn over the world, {@code pad} px bigger than the text's box on every side. */
	public static void backing(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int pad, int argb) {
		g.fill(Math.round(x0) - pad, Math.round(y0) - pad, Math.round(x1) + pad, Math.round(y1) + pad, argb);
	}

	/** The default backing's colour (the biomass bar's strip). */
	public static final int BACKING = 0x90000000;

	/** Vanilla's GUI scale (device pixels per GUI pixel), the unit {@link UiLayout#snap} rounds to. */
	public static int guiScale() {
		return Math.max(1, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
	}

	/**
	 * Text with its top left at ({@code x}, {@code y}) at pose scale {@code scale}, with a shadow, the position put on a whole device pixel
	 * ({@link UiLayout#snap}) so a one-texel font's strokes do not straddle two device pixels.
	 */
	public static void at(GuiGraphicsExtractor g, Font font, Component text, float x, float y, float scale, int color) {
		int gs = guiScale();
		g.pose().pushMatrix();
		g.pose().translate(UiLayout.snap(gs, x), UiLayout.snap(gs, y));
		g.pose().scale(scale, scale);
		g.text(font, text, 0, 0, color, true);
		g.pose().popMatrix();
	}

	/** {@link #at(GuiGraphicsExtractor, Font, Component, float, float, float, int)} for an already wrapped line. */
	public static void at(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, float x, float y, float scale, int color) {
		int gs = guiScale();
		g.pose().pushMatrix();
		g.pose().translate(UiLayout.snap(gs, x), UiLayout.snap(gs, y));
		g.pose().scale(scale, scale);
		g.text(font, text, 0, 0, color, true);
		g.pose().popMatrix();
	}

	/**
	 * Text centred on {@code x}, its top at {@code y}, at pose scale {@code scale}, with a shadow. Its left edge is worked out from the exact
	 * width (an odd width halved as a whole font pixel put the middle half a pixel off) and then snapped to a device pixel.
	 */
	public static void centred(GuiGraphicsExtractor g, Font font, Component text, float x, float y, float scale, int color) {
		at(g, font, text, x - font.width(text) * scale / 2F, y, scale, color);
	}

	/** {@link #centred(GuiGraphicsExtractor, Font, Component, float, float, float, int)} for an already wrapped line. */
	public static void centred(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, float x, float y, float scale, int color) {
		at(g, font, text, x - font.width(text) * scale / 2F, y, scale, color);
	}

	/** Text centred on {@code x} at {@code scale} on a backing strip the width of the text. */
	public static void centredBacked(GuiGraphicsExtractor g, Font font, Component text, float x, float y, float scale, int color) {
		float half = font.width(text) * scale / 2F;
		backing(g, x - half, y, x + half, y + font.lineHeight * scale, 2, BACKING);
		centred(g, font, text, x, y, scale, color);
	}
}
