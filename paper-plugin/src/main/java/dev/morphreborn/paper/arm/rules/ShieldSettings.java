package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.accessories.shield (my design, 2026-11-16, 8.0.0-beta.2) as typed values. Read from the accessory's
 * section, which the manager hands the module with the bundled defaults merged in; a value that is missing or unreadable takes the
 * default (exactly what the bundled file says, tested) and numbers are clamped rather than rejected. Pure, so it is unit tested without
 * a server.
 *
 * @param reduction the share of a frontal blow's damage the shield cuts, by level (0.5, 0.6, 0.7: the owner's numbers)
 * @param reflectProjectiles frontal arrows, tridents and fireballs are sent back at their shooter; off, they are cut like a blow
 * @param coneDegrees how far from the look a blow may come and still be frontal (either side, so the front is twice this wide; vanilla's
 *            shield is 90)
 * @param raiseDelayTicks ticks the shield takes to hold once the use key (sneak, for an owner with no other way) is down (vanilla's is 5)
 * @param reflectMinSpeed the slowest a sent-back projectile leaves at (blocks a tick), so a spent arrow still comes back with some force
 * @param reflectMaxSpeed the fastest it leaves at
 */
public record ShieldSettings(ArmConfig.Levels reduction, boolean reflectProjectiles, double coneDegrees, int raiseDelayTicks, double reflectMinSpeed,
		double reflectMaxSpeed) {
	/** The defaults: the bundled abilities.yml's shield section. */
	public static final ShieldSettings DEFAULTS = new ShieldSettings(new ArmConfig.Levels(0.5, 0.6, 0.7), true, 100, 3, 1.2, 3.0);

	/** The accessory's section (arms.accessories.shield) as plain values to settings; null or empty gives the defaults. */
	public static ShieldSettings parse(@Nullable Map<String, Object> section) {
		if (section == null || section.isEmpty()) return DEFAULTS;
		ShieldSettings d = DEFAULTS;
		double min = number(section.get("reflect-min-speed"), d.reflectMinSpeed, 0.1, 6);
		double max = number(section.get("reflect-max-speed"), d.reflectMaxSpeed, 0.1, 6);
		return new ShieldSettings(levels(section.get("reduction"), d.reduction, 0, 1), bool(section.get("reflect-projectiles"), d.reflectProjectiles),
				number(section.get("cone-degrees"), d.coneDegrees, 0, 180), (int) Math.round(number(section.get("raise-delay-ticks"), d.raiseDelayTicks, 0, 40)),
				Math.min(min, max), Math.max(min, max));
	}

	/** The level's numbers in words for the upgrade tree. {@code blockCost} is what a blocked hit costs, "1 biomass" (empty for free). */
	public String describe(int level, String blockCost) {
		return lines(level, blockCost).text(true);
	}

	/**
	 * The same as typed pieces for the Tooltips setting. How much it cuts and what a blocked hit costs are rates and stay; which click raises it,
	 * what the click does first and what it sends back are explanation. The click is said exactly (2026-11-17): hold right-click; an item in the
	 * main hand that has its own use for the click (a bow, food, a block to place) is used first, and while the shield is up the off hand is locked.
	 */
	public Lines lines(int level, String blockCost) {
		String cut = Math.round(reduction.at(level) * 100) + "%";
		Lines.Builder b = Lines.builder().tip("Hold right-click to raise it (an item in your main hand with its own use for the click goes first; the off hand is locked while it is up)")
				.always("cuts frontal blows by " + cut);
		b.tip(reflectProjectiles ? "arrows, tridents and fireballs from the front fly back at their shooter" : "arrows, tridents and fireballs from the front are cut by " + cut + " too");
		if (!blockCost.isEmpty()) b.always(blockCost + " per blocked hit");
		return b.build();
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
