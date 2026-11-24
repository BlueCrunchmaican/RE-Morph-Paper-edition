package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.gui.UiLayout;
import java.util.List;

/**
 * The Arsenal wheel's arithmetic, apart from the drawing so the unit tests can check it: where the tool slots and the
 * accessories sit and how big their icons are for any number of each (2026-11-16: twelve tools and four accessories,
 * laid out from the counts so one more would still fit).
 * <p>
 * Angles are radians clockwise from the top, as the slots are counted. Distances are fractions of the ring's radius {@code R}
 * unless a name says pixels; sizes that scale with the wheel take its text scale {@code ts} (1 at the original wheel's radius).
 */
public final class WheelLayout {
	/** Where the tool icons sit: halfway across the ring. */
	public static final float SLOT_RADIUS = 0.775F;
	/** The dead zone in the middle, and the first accessory's drop below the middle (the lantern's place since it was the only one). */
	public static final float DEAD_ZONE = 0.55F;
	public static final float SATELLITE_DROP = 0.32F;
	/** An accessory orb's scale (times the text scale). */
	public static final float SATELLITE_SCALE = 0.45F;
	/** Tool slots up to this many keep full-size icons; more shrink them to fit round the ring. */
	public static final int FULL_SIZE_SLOTS = 8;
	/** The icon scale at {@link #FULL_SIZE_SLOTS} and under (the icon is 16 GUI px times 2 times the fit). */
	private static final float ICON_BASE = 2F;
	/** What a slot's icon is shrunk by per slot over the full-size count: 8.4 / n, so twelve tools get 0.7 (22 px icons, name and pips under them still clear of the next). */
	private static final float FIT_NUMERATOR = 8.4F;

	private WheelLayout() {}

	/** The angle of tool slot {@code i} of {@code n} (0 at the top). */
	public static float slotAngle(int i, int n) {
		return i * (float) (Math.PI * 2 / Math.max(1, n));
	}

	/** The share of the usual icon size slots get when there are {@code n} of them: 1 up to {@link #FULL_SIZE_SLOTS}, then 8.4 / n. */
	public static float iconFit(int n) {
		return n <= FULL_SIZE_SLOTS ? 1F : FIT_NUMERATOR / n;
	}

	/** A tool icon's scale (1 is item size; the icon is 16 px times this): the usual 2 at wheel scale {@code ts} (capped at 1), times the fit for {@code n} slots. */
	public static float iconScale(int n, float ts) {
		return ICON_BASE * Math.min(1F, ts) * iconFit(n);
	}

	/** The angle of accessory {@code k} of {@code count} round the inner ring: the first (the lantern) straight below the middle, the rest evenly spaced clockwise from it. */
	public static float accessoryAngle(int k, int count) {
		return (float) (Math.PI + k * (Math.PI * 2 / Math.max(1, count)));
	}

	/** The unit offset of accessory {@code k} of {@code count} from the middle (x right, y down the screen), into {@code out}[0..1]. */
	public static void accessoryDirection(int k, int count, float[] out) {
		float a = accessoryAngle(k, count);
		out[0] = (float) Math.sin(a);
		out[1] = (float) -Math.cos(a);
	}

	/** Whether an accessory sits in the upper half of the ring (its label goes above it, away from the middle's). */
	public static boolean accessoryAbove(int k, int count) {
		float[] d = new float[2];
		accessoryDirection(k, count, d);
		return d[1] < -0.5F;
	}

	/**
	 * How much of the bottom of the screen the survival HUD takes (GUI px): the hotbar (22), the XP bar and its number, the hearts at
	 * 39 and an armour row above them at 49, plus a pixel to spare. The wheel's hint lines never go into it (the rig, 2026-11-16: the
	 * "Tap: form ..." line sat on the hotbar and the XP number at the default wheel size).
	 */
	public static final float HUD_RESERVE = 60F;
	/** The least gap between the hint lines and the screen's top edge when they are put above the ring. */
	public static final float HINT_EDGE = 2F;

	/**
	 * Where the hint lines' block starts (its top edge, GUI px) for a ring of radius {@code dist} centred at {@code cy}, a block
	 * {@code blockHeight} tall and a gap {@code gap} from the ring, on a screen {@code guiHeight} tall. They go under the ring
	 * as they always did when the whole block clears the HUD; else (the wheel at its default 0.75 reaches 0.875 of the screen's height,
	 * which is under the HUD's top at every GUI scale) above the ring, where nothing is; and with no room there either (a wheel at full
	 * size), as high as keeps them off the HUD, over the ring's translucent bottom rather than over the hotbar.
	 */
	public static float hintTop(float cy, float dist, float gap, float blockHeight, float guiHeight) {
		return hintTop(cy, dist, gap, blockHeight, guiHeight, cy + dist);
	}

	/** {@link #hintTop(float, float, float, float, float)} for a wheel whose drawing reaches down to {@code reach} (GUI px), see {@link #hintClear}. */
	public static float hintTop(float cy, float dist, float gap, float blockHeight, float guiHeight, float reach) {
		float clear = hintClear(cy, dist, gap, blockHeight, guiHeight, reach);
		return Float.isNaN(clear) ? Math.max(HINT_EDGE, guiHeight - HUD_RESERVE - blockHeight) : clear;
	}

	/**
	 * The top of a hint block centred under the ring or over it that touches neither the wheel's drawing nor the HUD, or NaN when
	 * there is no such place. {@code reach} is how far down the wheel draws: the lowest slot's name and pips hang below the ring's edge
	 * ({@code cy + dist}) at a small window, and the hint lines sat on the names of Spear, Pickaxe and Spade there (960 by 520 at GUI scale 2,
	 * 2026-11-18). Over the ring is clear of everything because the top slot's labels hang down into it.
	 */
	public static float hintClear(float cy, float dist, float gap, float blockHeight, float guiHeight, float reach) {
		float hudTop = guiHeight - HUD_RESERVE;
		float below = Math.max(cy + dist, reach) + gap;
		if (below + blockHeight <= hudTop) return below;
		float above = cy - dist - gap - blockHeight;
		if (above >= HINT_EDGE) return above;
		return Float.NaN;
	}

	/** The least width (GUI px) of the free column beside the ring that the hint lines are put in; narrower and they stay over the ring's bottom as before. */
	public static final float HINT_MIN_SIDE = 56F;

	/**
	 * The width (GUI px) of the column of free screen between the screen's edge and the ring, with the margin and a gap, for the hint lines
	 * when they fit neither under nor over the ring ({@link #hintClear}): they go in the top corner on that side, where the ring and
	 * the survival HUD are not. Smaller than {@link #HINT_MIN_SIDE} means there is no such column.
	 */
	public static float hintSideWidth(float guiW, float cx, float radius) {
		return Math.min(cx, guiW - cx) - radius - CARD_GAP - UiLayout.MARGIN;
	}

	// ------------------------------------------------------------------ fitting the wheel to the screen (2026-11-18)

	/** The ring radius (GUI px) at which the wheel's text scale is 1: the original wheel's, from which every size scales. */
	public static final float BASE_RADIUS = 96.375F;

	/** The wheel's text scale (1 at the original wheel's radius) for a ring of radius {@code radius}. */
	public static float textScale(float radius) {
		return radius / BASE_RADIUS;
	}

	/**
	 * A tool icon's scale (1 is item size; the icon is 16 px times this) on a ring with text scale {@code ts}: {@link #iconScale} without
	 * its cap, so the icons grow with the ring at every GUI scale (the ring and the text did; the icons stopped at the original wheel's
	 * size and looked lost at GUI scale 1 or 2), in whole device pixels.
	 */
	public static float iconScaleFor(int n, float ts, int guiScale) {
		float raw = ICON_BASE * ts * iconFit(n);
		return UiLayout.wholeDevicePixels(guiScale, 16F * raw) / 16F;
	}

	/**
	 * The scale the ring's own text (names, level pips, centre lump) is drawn at: half the wheel's text scale as it always was, snapped to
	 * whole device texels per font pixel and never below one (a fractional factor, or one under 1, minified the 8 px font so strokes vanished
	 * or came out uneven: 7.5 device px at 854 by 480).
	 */
	public static float labelScale(int guiScale, float ts) {
		return UiLayout.textScale(guiScale, 0.5F * ts);
	}

	/** The width (font px) a typical short form name is assumed to take when deciding whether the slot labels may be drawn larger. */
	public static final int NAME_PX = 44;

	/**
	 * The scale the ring's slot labels (names, level pips, the middle's text) are drawn at on a ring of radius {@code radius} with {@code n}
	 * slots: {@link #labelScale}, raised to two device texels per font pixel when that is still no bigger than vanilla's own text (GUI scale 2 or
	 * more) and a typical name still fits between neighbouring slots at that size. At GUI scale 2 and 3 the half-size labels were 8 device px
	 * tall, half the info card's and vanilla's (2026-11-18); a crowded ring (twelve tools) keeps the smaller size, and a name that still
	 * does not fit is left out as before. The ring's radius is fitted with the smaller labels ({@link #fitRadius}), so that it never depends on
	 * them; the bigger ones are used only when the lowest slot's labels still end on a screen {@code guiH} tall.
	 */
	public static float slotLabelScale(int guiScale, float ts, float radius, int n, float guiH) {
		float big = nameScaleMax(guiScale, ts, radius, n, guiH);
		return big > labelScale(guiScale, ts) && NAME_PX * big <= slotSpacing(radius, n) - 2F ? big : labelScale(guiScale, ts);
	}

	/**
	 * The larger size one slot's name may be drawn at, whatever the other names do: {@link #labelScale}, raised to two device texels per font
	 * pixel on the same terms as {@link #slotLabelScale} but without asking that a typical name fits between neighbours, since the caller
	 * checks the name itself (a crowded ring of twelve tools kept every name at 8 device px, half of vanilla's, even short ones like "Bow", 2026-11-18).
	 */
	public static float nameScaleMax(int guiScale, float ts, float radius, int n, float guiH) {
		float base = labelScale(guiScale, ts);
		if (guiScale < 2 || Math.round(base * guiScale) >= 2) return base;
		float two = 2F / guiScale;
		float bottom = guiH / 2F + Math.max(0.2F, lowestSlot(n)) * SLOT_RADIUS * radius + 10F * iconScaleFor(n, ts, guiScale) + labelBand(two);
		return bottom <= guiH - UiLayout.MARGIN ? two : base;
	}

	/**
	 * The scale the hint lines under the ring are drawn at: 0.45 of the wheel's text scale, but never under 0.75 GUI px per font pixel (the
	 * smallest of it was 0.45 at the default size, half the size of vanilla text), whole texels.
	 */
	public static float hintScale(int guiScale, float ts) {
		return UiLayout.textScale(guiScale, Math.max(0.45F * ts, 0.75F));
	}

	/**
	 * The info card's text scale: 0.75 GUI px per font pixel, or 0.3 of the wheel's text scale when the ring is bigger (a 4K screen at GUI
	 * scale 1 had 32 device px names beside 8 device px details), whole texels.
	 */
	public static float cardScale(int guiScale, float ts) {
		return UiLayout.textScale(guiScale, Math.max(0.75F, 0.3F * ts));
	}

	/** How much room a slot's name and pips take under its icon (GUI px) at label scale {@code label}: the name's line, the pips and the gaps and backing round them. */
	public static float labelBand(float label) {
		return 15F * label + 4F;
	}

	/** {@link #labelBand(float)} at the base {@link #labelScale}: what {@link #fitRadius} keeps on screen (so the ring's size does not depend on the bigger labels). */
	public static float labelBand(int guiScale, float ts) {
		return labelBand(labelScale(guiScale, ts));
	}

	/** How far down the screen the lowest of {@code n} slots sits, as a share of the slot radius (1 for an even count, less for an odd one). */
	public static float lowestSlot(int n) {
		float low = 0F;
		for (int i = 0; i < n; i++) low = Math.max(low, -(float) Math.cos(slotAngle(i, n)));
		return low;
	}

	/**
	 * The ring radius (GUI px) to use for the wanted {@code wanted} (the Arsenal Wheel Size setting's share of the screen): never more than
	 * fits the screen with its margin, and small enough that the lowest slot's icon, name and pips end inside the screen (at 100% the
	 * labels left it). The setting keeps its meaning: it is the largest the wheel gets, and 100% means as large as fits.
	 *
	 * @param guiScale vanilla's GUI scale (text sizes are snapped to it), {@code n} the tool slots showing
	 */
	public static float fitRadius(float wanted, float guiW, float guiH, int guiScale, int n) {
		float radius = Math.max(20F, Math.min(wanted, Math.min(guiW, guiH) / 2F - UiLayout.MARGIN));
		float low = Math.max(0.2F, lowestSlot(n)) * SLOT_RADIUS;
		for (int pass = 0; pass < 6; pass++) {
			float ts = textScale(radius);
			float room = guiH / 2F - UiLayout.MARGIN - 10F * iconScaleFor(n, ts, guiScale) - labelBand(guiScale, ts);
			float most = room / low;
			if (radius <= most + 0.01F) break;
			radius = Math.max(20F, most);
		}
		return radius;
	}

	/** Where the ring is: its radius and the y (GUI px) of its middle; x is the screen's middle. */
	public record Fit(float radius, float cy) {}

	/** The most the ring's middle may rise above the screen's (as a share of the radius): the cursor starts at the screen's middle and must still be in the dead zone ({@link #DEAD_ZONE}). */
	public static final float MAX_RISE = 0.3F;
	/** The least share of its screen-fitted radius the ring keeps when it is made smaller to clear the survival HUD. */
	public static final float MIN_HUD_SHRINK = 0.8F;

	/**
	 * The ring's radius and middle: {@link #fitRadius}, then raised off the screen's middle (by at most {@link #MAX_RISE} of the radius, and
	 * keeping the ring's top on screen) so the lowest slot's name and pips end above the survival HUD's top ({@link #HUD_RESERVE}) instead of
	 * over the hearts and armour, and where that is not enough made smaller, to no less than {@link #MIN_HUD_SHRINK} of the fitted radius
	 * (2026-11-18: at the default size on a 854 by 480 window the lowest labels sat on the hearts and the dark backing hid them). What the
	 * raise and the shrink cannot clear stays over the HUD, as before.
	 */
	public static Fit fit(float wanted, float guiW, float guiH, int guiScale, int n) {
		float fitted = fitRadius(wanted, guiW, guiH, guiScale, n);
		float floor = Math.max(20F, fitted * MIN_HUD_SHRINK);
		float radius = fitted;
		float cy = guiH / 2F;
		float low = Math.max(0.2F, lowestSlot(n)) * SLOT_RADIUS;
		float hudTop = guiH - HUD_RESERVE;
		for (int pass = 0; pass < 8; pass++) {
			float ts = textScale(radius);
			float below = low * radius + 10F * iconScaleFor(n, ts, guiScale) + labelBand(guiScale, ts);
			float rise = Math.max(0F, Math.min(MAX_RISE * radius, guiH / 2F - radius - UiLayout.MARGIN));
			float need = guiH / 2F + below - hudTop;
			if (need <= rise + 0.01F) {
				cy = guiH / 2F - Math.max(0F, need);
				return new Fit(radius, cy);
			}
			// not enough by rising alone: a smaller ring lowers the labels' reach and gives the top more room, about half a pixel per pixel of radius
			cy = guiH / 2F - rise;
			if (radius <= floor + 0.01F) break;
			radius = Math.max(floor, radius - (need - rise) / 0.45F);
		}
		float rise = Math.max(0F, Math.min(MAX_RISE * radius, guiH / 2F - radius - UiLayout.MARGIN));
		return new Fit(radius, guiH / 2F - rise);
	}

	/**
	 * The favourites radial's ring radius (GUI px) for the wanted {@code wanted}: no more than fits the screen with its margin, and small
	 * enough that the lowest favourite's name and price lines (under its picture, at {@link #labelScale}) end on screen, as
	 * {@link #fitRadius} does for the Arsenal wheel.
	 */
	public static float fitRadialRadius(float wanted, float guiW, float guiH, int guiScale) {
		float radius = Math.max(20F, Math.min(wanted, Math.min(guiW, guiH) / 2F - UiLayout.MARGIN));
		for (int pass = 0; pass < 6; pass++) {
			float ts = textScale(radius);
			float s = labelScale(guiScale, ts);
			// the lowest picture's centre is at 0.775 R; its name starts 0.1 R + a little under it, two 9 px lines of text follow
			float below = 0.1F * radius + 5.2F * ts + 2F * 9F * s + 2F;
			float most = (guiH / 2F - UiLayout.MARGIN - below) / SLOT_RADIUS;
			if (radius <= most + 0.01F) break;
			radius = Math.max(20F, most);
		}
		return radius;
	}

	/** The distance (GUI px) between the centres of two neighbouring tool slots on a ring of radius {@code radius}. */
	public static float slotSpacing(float radius, int n) {
		return n < 2 ? Float.MAX_VALUE : 2F * SLOT_RADIUS * radius * (float) Math.sin(Math.PI / n);
	}

	/**
	 * How big the middle lump may get (GUI px) before it reaches the accessories' lumps round it (their inner edge) or, with none, the dead
	 * zone's edge. It was sized from its text alone, so "51,200.0 biomass" overlapped the satellite lumps; the text now gets shorter
	 * ("51.2k") before the lump gets bigger.
	 */
	public static float centreLimit(float radius, boolean accessories) {
		float ts = textScale(radius);
		if (!accessories) return DEAD_ZONE * radius * 0.9F;
		return Math.max(6F, SATELLITE_DROP * radius - SATELLITE_SCALE * ts * 16F - 1F * ts);
	}

	/** The gap (GUI px) between the ring and an info card beside it. */
	public static final float CARD_GAP = 6F;

	/**
	 * Where the hover info card (the hovered form's or accessory's lines, in one box) goes: beside the ring on the side away from the hovered
	 * slot ({@code hoverX} is its x offset from the middle, GUI px) when there is room, else the other side, else above or below the ring,
	 * else a corner. Always inside the screen's margin, and off the survival HUD's bottom rows where it stands over the hotbar.
	 *
	 * @param cx the ring's middle, {@code radius} its radius, {@code w} by {@code h} the card's size, all GUI px
	 */
	public static UiLayout.Rect placeCard(float guiW, float guiH, float cx, float cy, float radius, float hoverX, float w, float h) {
		float m = UiLayout.MARGIN;
		float hudTop = guiH - HUD_RESERVE;
		float y = UiLayout.clamp(cy - h / 2F, m, guiH - m - h);
		float[] sides = hoverX > 0 ? new float[] {cx - radius - CARD_GAP - w, cx + radius + CARD_GAP} : new float[] {cx + radius + CARD_GAP, cx - radius - CARD_GAP - w};
		for (float x : sides) {
			if (x < m || x + w > guiW - m) continue;
			float top = y;
			// beside the ring is outside the hotbar, but a narrow screen can put the card over it
			if (x < guiW / 2F + 92F && x + w > guiW / 2F - 121F && top + h > hudTop) top = Math.max(m, hudTop - h);
			return new UiLayout.Rect(x, top, w, h);
		}
		float x = UiLayout.clamp(cx - w / 2F, m, guiW - m - w);
		float above = cy - radius - CARD_GAP - h;
		if (above >= m) return new UiLayout.Rect(x, above, w, h);
		float below = cy + radius + CARD_GAP;
		if (below + h <= hudTop) return new UiLayout.Rect(x, below, w, h);
		// no room round the ring: a top corner, over the ring's edge where it must be (the card has its own backing)
		float cornerX = hoverX > 0 ? m : guiW - m - w;
		return new UiLayout.Rect(UiLayout.clamp(cornerX, m, guiW - m - w), m, w, h);
	}

	/** The brightnesses a lantern of {@code level} has: 1 up to clamp(level, 1, 3) + 2, as the server's maxBrightness. */
	public static int maxBrightness(int level) {
		return Math.clamp(level, 1, 3) + 2;
	}

	/** The brightness after one scroll step from {@code current} (0 or less: unknown, reads as the middle one), kept to 1..max (no wrap: a wheel flick must not jump from the dimmest to the brightest). */
	public static int steppedBrightness(int current, int dir, int level) {
		int max = maxBrightness(level);
		int from = current <= 0 ? Math.min(3, max) : current;
		return Math.clamp(from + dir, 1, max);
	}

	/**
	 * The tool slots showing, in wheel order: the seven the wheel has always had (an empty socket where the server has not built
	 * one) and any of the five new forms the server has built, which are left out until then. The slices are laid out from this
	 * list's size.
	 */
	public static List<String> activeSlots(dev.morphreborn.client.arm.ArmArsenal arsenal) {
		List<String> out = new java.util.ArrayList<>(ArmProtocol.WHEEL.size());
		for (String form : ArmProtocol.WHEEL) {
			if (ArmProtocol.CLASSIC_FORMS.contains(form) || arsenal.entry(form) != null) out.add(form);
		}
		return out;
	}

	/** The cursor counts as moved from where the wheel opened beyond this many scaled pixels. */
	private static final double MOVED = 1.5;

	/**
	 * Whether the cursor has moved by ({@code dx}, {@code dy}) scaled pixels since the wheel opened. The game puts it at the window's middle then,
	 * where the lantern's lump can lie, so a lump under a cursor that has not moved is not a choice the player made (rig 2026-11-22: at the
	 * default 75% size a tap lit the lantern instead of retracting the arm).
	 */
	public static boolean moved(double dx, double dy) {
		return dx * dx + dy * dy > MOVED * MOVED;
	}
}
