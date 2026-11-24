package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.mace.special (my design, 2026-11-16, 8.0.0-beta.2) as typed values: Skyward Leap, the mace's F.
 * The mace's own smash is vanilla's (the item is a real mace, so its damage grows with the fall distance), so the only settings are
 * the leap's. Read from the form's section, which the manager hands the module with the bundled defaults merged in; a value that is
 * missing or unreadable takes the default (which is exactly what the bundled file says, tested), and numbers are clamped rather than
 * rejected. Heights are in blocks, the cooldown and the protection in seconds in the file and ticks here. Pure, so it is unit tested
 * without a server.
 *
 * @param name the special's name for messages and the wheel
 * @param height how high the leap rises, by level (blocks)
 * @param cooldownSeconds the special's cooldown, by level
 * @param protectSeconds the longest the leap's no-fall protection lasts from the launch, for a leap that never lands (a void, a very
 *            long flight); a landing ends it long before this
 */
public record MaceSettings(String name, ArmConfig.Levels height, ArmConfig.Levels cooldownSeconds, double protectSeconds) {
	/** The defaults: the bundled abilities.yml's mace special. */
	public static final MaceSettings DEFAULTS = new MaceSettings("Skyward Leap", new ArmConfig.Levels(6, 8, 10), new ArmConfig.Levels(10, 9, 8), 30);

	/** The form's section (arms.forms.mace) as plain values to settings; null or empty gives the defaults. */
	public static MaceSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty() || !(form.get("special") instanceof Map<?, ?> raw)) return DEFAULTS;
		@SuppressWarnings("unchecked")
		Map<String, Object> s = (Map<String, Object>) raw;
		MaceSettings d = DEFAULTS;
		return new MaceSettings(string(s.get("name"), d.name), levels(s.get("height"), d.height, 0, MaceMath.MAX_HEIGHT),
				levels(s.get("cooldown"), d.cooldownSeconds, 0, 3600), number(s.get("protect-seconds"), d.protectSeconds, 1, 3600));
	}

	/** How high the leap rises at a level (blocks). */
	public double heightAt(int level) {
		return height.at(level);
	}

	/** The cooldown at a level in ticks (before the gameplay mode's factor). */
	public int cooldownTicks(int level) {
		return Math.max(0, (int) Math.round(cooldownSeconds.at(level) * 20));
	}

	/** The longest the protection lasts, in ticks. */
	public int protectTicks() {
		return Math.max(1, (int) Math.round(protectSeconds * 20));
	}

	/**
	 * The level's numbers in words for the upgrade tree: "Leaps 8 blocks · the fall adds 22 damage to the smash". What the fall adds is
	 * vanilla's own smash bonus for a fall of the leap's height (MaceMath.smashBonus).
	 */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/** The same as typed pieces for the Tooltips setting: the leap's height and what the fall adds are the level's numbers, so they stay. */
	public Lines lines(int level) {
		return Lines.builder().always("Leaps " + number(height.at(level)) + " blocks")
				.always("the fall adds " + number(MaceMath.smashBonus(height.at(level))) + " damage to the smash").build();
	}

	/** 6.0 as "6", 6.5 as "6.5". */
	static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}

	// ------------------------------------------------------------------ plain-value helpers

	private static String string(@Nullable Object v, String fallback) {
		return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
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
