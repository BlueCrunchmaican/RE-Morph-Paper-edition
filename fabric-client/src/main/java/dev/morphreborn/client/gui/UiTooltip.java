package dev.morphreborn.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/**
 * The mod's tooltips: wrapped to the screen by pixel width (vanilla's text wrap, not a character count) and kept inside the screen on all
 * four edges. Vanilla's positioner flips left and clamps the bottom only, so a tall tooltip ran above the top edge on a short screen; this
 * one drops the lines it cannot fit (the last first) and clamps the top too ({@link UiLayout#tooltipPos}).
 */
public final class UiTooltip {
	private static final ClientTooltipPositioner POSITIONER = new ClientTooltipPositioner() {
		@Override
		public Vector2ic positionTooltip(int screenWidth, int screenHeight, int x, int y, int w, int h) {
			int[] at = UiLayout.tooltipPos(screenWidth, screenHeight, x, y, w, h);
			return new Vector2i(at[0], at[1]);
		}
	};

	private UiTooltip() {}

	/** Shows {@code lines} (each wrapped to the screen) beside the mouse at ({@code mx}, {@code my}) in the next frame's tooltip layer. */
	public static void show(GuiGraphicsExtractor g, Font font, List<? extends Component> lines, int mx, int my) {
		show(g, font, lines, mx, my, -1);
	}

	/**
	 * {@link #show(GuiGraphicsExtractor, Font, List, int, int)} where line {@code elastic} (a description) is cut first, to what the room
	 * leaves and ending in an ellipsis, or dropped when under two lines of it fit, before any other line is lost; -1 for none. A tall
	 * tooltip lost its last line, the one that says what a click does, and covered the window's title row.
	 */
	public static void show(GuiGraphicsExtractor g, Font font, List<? extends Component> lines, int mx, int my, int elastic) {
		int maxWidth = UiLayout.tooltipMaxWidth(g.guiWidth());
		int room = UiLayout.tooltipMaxLines(g.guiHeight());
		List<FormattedCharSequence> wrapped = new ArrayList<>(UiText.wrapAll(font, lines, maxWidth));
		if (elastic >= 0 && elastic < lines.size() && wrapped.size() > room) {
			List<FormattedCharSequence> before = UiText.wrapAll(font, lines.subList(0, elastic), maxWidth);
			List<FormattedCharSequence> block = UiText.wrapAll(font, lines.subList(elastic, elastic + 1), maxWidth);
			List<FormattedCharSequence> after = UiText.wrapAll(font, lines.subList(elastic + 1, lines.size()), maxWidth);
			int keep = UiLayout.elasticKeep(before.size() + after.size(), block.size(), room, 2);
			if (keep < block.size()) {
				wrapped = new ArrayList<>(before);
				if (keep > 0) {
					// wrapped a little narrower so the ellipsis does not widen the box
					List<FormattedCharSequence> cut = new ArrayList<>(UiText.wrapAll(font, lines.subList(elastic, elastic + 1), maxWidth - font.width("...")));
					while (cut.size() > keep) cut.removeLast();
					cut.set(cut.size() - 1, FormattedCharSequence.composite(cut.getLast(), FormattedCharSequence.forward("...", Style.EMPTY.withColor(ChatFormatting.GRAY))));
					wrapped.addAll(cut);
				}
				wrapped.addAll(after);
			}
		}
		while (wrapped.size() > room) wrapped.removeLast();
		// a trailing spacer line only costs height
		while (wrapped.size() > 1 && wrapped.getLast() == FormattedCharSequence.EMPTY) wrapped.removeLast();
		g.setTooltipForNextFrame(font, wrapped, POSITIONER, mx, my, false);
	}
}
