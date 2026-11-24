package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.accessories.flint (my design, 2026-11-16, 8.0.0-beta.2) as typed values. Read from the accessory's
 * section, which the manager hands the module with the bundled defaults merged in; a value that is missing or unreadable takes the
 * default (exactly what the bundled file says, tested) and numbers are clamped rather than rejected. Pure, so it is unit tested without
 * a server.
 *
 * @param igniteSeconds how long a melee hit sets its target alight, by level (3, 4, 5: the owner's numbers)
 * @param boltSeconds how long a shot sets its target alight (4, 5, 6: the bow's Fire tip, so the shots burn alike): the crossbow's bolts by the crossbow's level,
 *        any other arrow or a thrown trident by the flint and steel's level when it was loosed
 * @param lightBlocks whether a right-click with the arm in hand lights a campfire, a candle, TNT or a portal frame like flint and steel
 */
public record FlintSettings(ArmConfig.Levels igniteSeconds, ArmConfig.Levels boltSeconds, boolean lightBlocks) {
	/** The defaults: the bundled abilities.yml's flint section. */
	public static final FlintSettings DEFAULTS = new FlintSettings(new ArmConfig.Levels(3, 4, 5), new ArmConfig.Levels(4, 5, 6), true);

	/** The accessory's section (arms.accessories.flint) as plain values to settings; null or empty gives the defaults. */
	public static FlintSettings parse(@Nullable Map<String, Object> section) {
		if (section == null || section.isEmpty()) return DEFAULTS;
		FlintSettings d = DEFAULTS;
		return new FlintSettings(levels(section.get("ignite-seconds"), d.igniteSeconds, 0, 600), levels(section.get("bolt-seconds"), d.boltSeconds, 0, 600),
				bool(section.get("light-blocks"), d.lightBlocks));
	}

	/** A melee hit's fire at a level in ticks, at least 0. */
	public int igniteTicks(int level) {
		return ticks(igniteSeconds, level);
	}

	/** A shot's fire at a level in ticks, at least 0. */
	public int boltTicks(int level) {
		return ticks(boltSeconds, level);
	}

	private static int ticks(ArmConfig.Levels seconds, int level) {
		return Math.max(0, (int) Math.round(seconds.at(level) * 20));
	}

	/** The level's numbers in words for the upgrade tree. {@code igniteCost} is what one ignition costs, "0.5 biomass" (empty for free). */
	public String describe(int level, String igniteCost) {
		return lines(level, igniteCost).text(true);
	}

	/**
	 * The same as typed pieces for the Tooltips setting. The length of the fire and the price of an ignition are rates and stay; what lights what
	 * is explanation. Said exactly (it read as unclear before, 2026-11-17): there is no key. Switch it on, and then every melee hit you land
	 * sets the target alight, your bow and crossbow shots burn, and a right-click on a campfire, candle, TNT or portal frame with a formed arm in
	 * your main hand lights it as flint and steel would.
	 */
	public Lines lines(int level, String igniteCost) {
		Lines.Builder b = Lines.builder().tip("While it is on, every melee hit you land sets the target alight").always("fire lasts " + number(igniteSeconds.at(level)) + " s");
		if (!igniteCost.isEmpty()) b.always(igniteCost + " per ignition");
		b.tip("your bow and crossbow shots burn"); // any bow or crossbow, vanilla ones too (2026-11-24); the trident is in the guide, the wording is shared with the client
		if (lightBlocks) b.tip("right-click a campfire, candle, TNT or portal frame with a formed arm in your main hand to light it");
		return b.build();
	}

	private static String number(double v) {
		return Math.rint(v) == v ? Long.toString((long) v) : Double.toString(Math.round(v * 10) / 10.0);
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
