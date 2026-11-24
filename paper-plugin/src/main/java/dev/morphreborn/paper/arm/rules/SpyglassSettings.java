package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.accessories.spyglass (my design, 2026-11-16, 8.0.0-beta.2) as typed values. Read from the accessory's
 * section, which the manager hands the module with the bundled defaults merged in; a value that is missing or unreadable takes the
 * default (exactly what the bundled file says, tested) and numbers are clamped rather than rejected. Pure, so it is unit tested without
 * a server.
 *
 * @param range how far the zoom marks, by level, in blocks (64, 96, 128)
 * @param marks how many creatures may be marked at once, by level (1, 2, 3)
 * @param markTicks how long a mark outlasts the last glance at it (ticks)
 * @param scanTicks ticks between two looks along the owner's eyes while they zoom
 * @param rayWidth how much wider than its body a creature counts when the look is aimed (blocks, either side): a far creature is a speck
 * @param zoomTimeoutTicks a zoom the client has not repeated for this long is over (a lost "released" must not leave it on for ever)
 * @param markPlayers whether other players may be marked (their outline shows through walls to the marker, so a server may keep it off)
 */
public record SpyglassSettings(ArmConfig.Levels range, ArmConfig.Levels marks, int markTicks, int scanTicks, double rayWidth, int zoomTimeoutTicks, boolean markPlayers) {
	/** The defaults: the bundled abilities.yml's spyglass section. */
	public static final SpyglassSettings DEFAULTS = new SpyglassSettings(new ArmConfig.Levels(64, 96, 128), new ArmConfig.Levels(1, 2, 3), 100, 4, 0.4, 600, true);

	/** The shortest zoom timeout: the client repeats a held key every 10 seconds (a finding of 2026-11-16: a 2 s timeout ended every zoom held longer), so less than this would end a zoom still held. */
	public static final int MIN_TIMEOUT_SECONDS = 15;

	/** The accessory's section (arms.accessories.spyglass) as plain values to settings; null or empty gives the defaults. */
	public static SpyglassSettings parse(@Nullable Map<String, Object> section) {
		if (section == null || section.isEmpty()) return DEFAULTS;
		SpyglassSettings d = DEFAULTS;
		return new SpyglassSettings(levels(section.get("range"), d.range, 4, 512), levels(section.get("marks"), d.marks, 1, 16),
				seconds(section.get("mark-seconds"), d.markTicks, 1, 120), (int) Math.round(number(section.get("scan-ticks"), d.scanTicks, 1, 20)),
				number(section.get("ray-width"), d.rayWidth, 0, 2), seconds(section.get("zoom-timeout-seconds"), d.zoomTimeoutTicks, MIN_TIMEOUT_SECONDS, 600),
				bool(section.get("mark-players"), d.markPlayers));
	}

	/** How far a level marks, in blocks. */
	public double rangeAt(int level) {
		return range.at(level);
	}

	/** How many creatures a level may have marked at once (at least one). */
	public int marksAt(int level) {
		return Math.max(1, marks.intAt(level));
	}

	/** The level's numbers in words for the upgrade tree. */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/**
	 * The same as typed pieces for the Tooltips setting. How many it marks and how far are the level's numbers; the key and what a mark looks like
	 * are explanation. The key is said exactly: it has to be on (the Arsenal wheel or /morph arm spyglass), then the spyglass zoom key held
	 * (Z unless it is rebound in Controls, under RE:Morph; the client mod's key, so players without the mod cannot zoom).
	 */
	public Lines lines(int level) {
		int n = marksAt(level);
		return Lines.builder().tip("Hold the zoom key (Z) while it is on")
				.always("marks up to " + n + (n == 1 ? " creature" : " creatures") + " within " + Math.round(rangeAt(level)) + " blocks")
				.tip("they glow for you alone and show their distance").build();
	}

	// ------------------------------------------------------------------ plain-value helpers

	private static boolean bool(@Nullable Object v, boolean fallback) {
		if (v instanceof Boolean b) return b;
		if (v instanceof String s) {
			String t = s.trim().toLowerCase(Locale.ROOT);
			if (t.equals("true") || t.equals("yes") || t.equals("on")) return true;
			if (t.equals("false") || t.equals("no") || t.equals("off")) return false;
		}
		return fallback;
	}

	private static double number(@Nullable Object v, double fallback, double min, double max) {
		double d = fallback;
		if (v instanceof Number n && Double.isFinite(n.doubleValue())) {
			d = n.doubleValue();
		} else if (v instanceof String s) {
			try {
				double parsed = Double.parseDouble(s.trim().toLowerCase(Locale.ROOT));
				if (Double.isFinite(parsed)) d = parsed;
			} catch (NumberFormatException e) {
				// the default stands
			}
		}
		return Math.clamp(d, min, max);
	}

	/** A length given in seconds (the file's unit), kept in ticks; a missing or bad value keeps {@code fallbackTicks}. */
	private static int seconds(@Nullable Object v, int fallbackTicks, double minSeconds, double maxSeconds) {
		return (int) Math.round(number(v, fallbackTicks / 20.0, minSeconds, maxSeconds) * 20);
	}

	/** One number for all three levels, or a list (fewer entries repeat the last; a bad entry takes that level's default). */
	private static ArmConfig.Levels levels(@Nullable Object v, ArmConfig.Levels fallback, double min, double max) {
		if (v instanceof Number || v instanceof String) {
			double d = number(v, Double.NaN, min, max);
			return Double.isNaN(d) ? fallback : new ArmConfig.Levels(d, d, d);
		}
		if (!(v instanceof List<?> list) || list.isEmpty()) return fallback;
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) out[i] = number(list.get(Math.min(i, list.size() - 1)), fallback.at(i + 1), min, max);
		return new ArmConfig.Levels(out[0], out[1], out[2]);
	}
}
