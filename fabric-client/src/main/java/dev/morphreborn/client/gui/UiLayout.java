package dev.morphreborn.client.gui;

/**
 * The arithmetic every screen and overlay of the mod shares for fitting itself to the window, apart from the drawing so the unit tests
 * can sweep window sizes and GUI scales without a game (UiLayoutTest).
 * <p>
 * Three rules come out of the 2026-11-18 layout round: sizes are worked out from the scaled screen (never from fixed pixels that assume
 * a big window); pose-scaled text is snapped to a whole number of device texels per font pixel, at least one ({@link #textScale}); and
 * everything drawn is kept inside a small margin of the screen ({@link #MARGIN}) and out of the survival HUD where it matters.
 */
public final class UiLayout {
	/** The least gap between anything drawn and the screen's edge (GUI px). */
	public static final float MARGIN = 4F;
	/** The font's height in GUI px at scale 1 (vanilla's {@code lineHeight}). */
	public static final int LINE = 9;
	/** Vanilla's smallest scaled screen: the GUI scale is chosen so the scaled window is never below this. */
	public static final int MIN_SCALED_W = 320, MIN_SCALED_H = 240;
	/** A tooltip's widest line, as vanilla's {@code Tooltip} (170) with room for the mod's longer numbers. */
	public static final int TOOLTIP_MAX_W = 200;

	private UiLayout() {}

	/**
	 * The GUI scale vanilla uses in a window {@code winW} by {@code winH} device pixels for the setting {@code setting} (0 is Auto: the
	 * largest scale that keeps the scaled screen at {@value #MIN_SCALED_W} by {@value #MIN_SCALED_H} or more; a number is clamped to
	 * that same largest, as the options screen does).
	 */
	public static int guiScale(int winW, int winH, int setting) {
		int max = 1;
		while (max < 64 && winW / (max + 1) >= MIN_SCALED_W && winH / (max + 1) >= MIN_SCALED_H) max++;
		return setting <= 0 ? max : Math.min(setting, max);
	}

	/** The scaled size along one axis (vanilla rounds a partial pixel up). */
	public static int scaled(int device, int guiScale) {
		return (int) Math.ceil(device / (double) guiScale);
	}

	/**
	 * How many device texels one font pixel covers when text is drawn at {@code wantedGui} GUI px per font pixel: the wanted size
	 * rounded to a whole number, never below 1. A fractional texel scale minifies or smears the 8 px font (strokes vanish or are uneven),
	 * and one below 1 is smaller than the font was drawn for.
	 */
	public static int texels(int guiScale, float wantedGui) {
		return Math.max(1, Math.round(wantedGui * guiScale));
	}

	/** The pose scale (GUI px per font pixel) that makes {@link #texels} whole texels per font pixel at {@code guiScale}. */
	public static float textScale(int guiScale, float wantedGui) {
		return texels(guiScale, wantedGui) / (float) guiScale;
	}

	/** A pixel size (GUI px) rounded to whole device pixels, at least one device pixel. */
	public static float wholeDevicePixels(int guiScale, float gui) {
		return Math.max(1, Math.round(gui * guiScale)) / (float) guiScale;
	}

	/**
	 * A position (GUI px) moved to the nearest whole device pixel. Text drawn at a whole number of texels per font pixel still lands on
	 * half a device pixel when its position is fractional (a ring slot at 41.25 GUI px at scale 2), and the font's one-pixel strokes then
	 * double or vanish: every pose-translated text and pip is put through this.
	 */
	public static float snap(int guiScale, float gui) {
		return Math.round(gui * guiScale) / (float) guiScale;
	}

	/** The widest a tooltip may be on a screen {@code guiW} wide (GUI px). */
	public static int tooltipMaxWidth(int guiW) {
		return Math.max(80, Math.min(TOOLTIP_MAX_W, guiW / 2));
	}

	/** The share of the screen's height a tooltip may take: a taller one covered the window's title row and the counter in it (the Tendril Flint upgrade, 960 by 520 at GUI scale 2, 2026-11-18). */
	public static final float TOOLTIP_MAX_SHARE = 0.75F;
	/** The fewest lines a tooltip is cut to by the share above, so a short screen still shows a name, a value and a cost. */
	public static final int TOOLTIP_MIN_CAP = 8;

	/** How many tooltip lines (10 px each) are shown on a screen {@code guiH} tall: what fits with its margins, at most {@link #TOOLTIP_MAX_SHARE} of the height (never under {@link #TOOLTIP_MIN_CAP} where that fits). */
	public static int tooltipMaxLines(int guiH) {
		int fits = Math.max(1, (guiH - 2 * 5 - 4 + 2) / 10);
		int share = (int) ((guiH * TOOLTIP_MAX_SHARE - 4F + 2F) / 10F);
		return Math.min(fits, Math.max(TOOLTIP_MIN_CAP, share));
	}

	/**
	 * How many lines of the tooltip's elastic block (its description: it says what the upgrade does, and the numbers under it are what the
	 * player came for) are kept when the rest takes {@code fixed} lines and at most {@code room} are shown: all {@code elastic} of them when
	 * everything fits, else what the room leaves, or none when that is under {@code minKeep} (two lines of a description say nothing).
	 */
	public static int elasticKeep(int fixed, int elastic, int room, int minKeep) {
		int keep = Math.min(elastic, room - fixed);
		return keep >= elastic ? elastic : keep < minKeep ? 0 : keep;
	}

	/**
	 * Where a tooltip {@code w} by {@code h} (the text's own box, as vanilla measures it: its 3 px padding and 1 px border come on top)
	 * goes for a mouse at ({@code mx}, {@code my}): vanilla's spot (12 right, 12 up), flipped to the mouse's left when it would run off the
	 * right, then kept inside the screen on all four edges (vanilla clamps only the bottom, so a tall tooltip went above the top).
	 *
	 * @return {x, y}
	 */
	public static int[] tooltipPos(int screenW, int screenH, int mx, int my, int w, int h) {
		int x = mx + 12;
		int y = my - 12;
		if (x + w > screenW - 5) x = Math.max(mx - 24 - w, 5);
		x = Math.max(5, Math.min(x, screenW - w - 5));
		y = Math.max(5, Math.min(y, screenH - h - 5));
		return new int[] {x, y};
	}

	/** A rectangle in GUI pixels. */
	public record Rect(float x, float y, float w, float h) {
		public float right() {
			return x + w;
		}

		public float bottom() {
			return y + h;
		}

		/** Whether this lies entirely inside {@code outer}. */
		public boolean inside(Rect outer) {
			return x >= outer.x - 0.001F && y >= outer.y - 0.001F && right() <= outer.right() + 0.001F && bottom() <= outer.bottom() + 0.001F;
		}

		public boolean overlaps(Rect o) {
			return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
		}
	}

	/** The screen less its margin. */
	public static Rect safeScreen(float guiW, float guiH) {
		return new Rect(MARGIN, MARGIN, Math.max(0F, guiW - 2 * MARGIN), Math.max(0F, guiH - 2 * MARGIN));
	}

	/** {@code value} kept within {@code lo}..{@code hi} (lo wins when they cross, so a box too big for the room is pinned to the near edge). */
	public static float clamp(float value, float lo, float hi) {
		return Math.max(lo, Math.min(hi, value));
	}
}
