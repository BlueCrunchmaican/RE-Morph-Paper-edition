package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.claws.special (my design, 2026-11-16, 8.0.0-beta.2) as typed values: Rake, the claws' F, a
 * sweep of {@code slashes} claw slashes in front of you, each hitting everything in a {@code arc}-degree fan, and what it hits
 * Bleeds. Read from the form's section, which the manager hands the module with the bundled defaults merged in; a value that is
 * missing or unreadable takes the default (which is exactly what the bundled file says, tested), and numbers are clamped rather
 * than rejected. Distances are in blocks, damage in health points, times in seconds in the file and ticks here. Pure, so it is
 * unit tested without a server.
 *
 * @param name the special's name for messages and the wheel
 * @param slashes how many slashes a Rake makes (the design says three; 1 to {@link #MAX_SLASHES})
 * @param damage what each slash deals to each creature it reaches, by level
 * @param reach how far the claws reach from the eyes (to the nearest point of a creature's box), by level
 * @param arcDeg the fan's whole width in degrees
 * @param band how far above or below the eyes a creature's middle may be and still be raked
 * @param maxTargets the most creatures one slash reaches, by level (the nearest are kept)
 * @param bleedSeconds how long a hit makes the creature bleed, by level
 * @param bleedDamage what each bleed pulse takes, by level
 * @param bleedIntervalSeconds the time between bleed pulses
 * @param cooldownSeconds the special's cooldown, by level
 */
public record ClawSettings(String name, int slashes, ArmConfig.Levels damage, ArmConfig.Levels reach, double arcDeg, double band, ArmConfig.Levels maxTargets,
		ArmConfig.Levels bleedSeconds, ArmConfig.Levels bleedDamage, double bleedIntervalSeconds, ArmConfig.Levels cooldownSeconds) {
	/** The defaults: the bundled abilities.yml's claws special. */
	public static final ClawSettings DEFAULTS = new ClawSettings("Rake", 3, new ArmConfig.Levels(1.5, 2, 2.5), new ArmConfig.Levels(3.5, 4, 4.5), 100, 2.0,
			new ArmConfig.Levels(4, 6, 8), new ArmConfig.Levels(3, 4, 5), new ArmConfig.Levels(1, 1, 1), 1.0, new ArmConfig.Levels(7, 6, 5));

	/**
	 * The limits. At most six slashes (the wire packs the count in four bits and the clients draw one claw sweep each). The reach
	 * stops at 6.3 blocks (the wire carries it in tenths in six bits). A bleed pulse is never sooner than half a second after the
	 * last, because a creature shrugs off a second hit inside its hurt window (the half of its 20 invulnerable ticks).
	 */
	public static final int MAX_SLASHES = 6;
	public static final double MAX_REACH = 6.3, MIN_BLEED_INTERVAL = 0.5;

	/** The form's section (arms.forms.claws) as plain values to settings; null or empty gives the defaults. */
	public static ClawSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty() || !(form.get("special") instanceof Map<?, ?> raw)) return DEFAULTS;
		@SuppressWarnings("unchecked")
		Map<String, Object> s = (Map<String, Object>) raw;
		ClawSettings d = DEFAULTS;
		return new ClawSettings(string(s.get("name"), d.name), (int) Math.round(number(s.get("slashes"), d.slashes, 1, MAX_SLASHES)),
				levels(s.get("damage"), d.damage, 0, 1000), levels(s.get("reach"), d.reach, 1, MAX_REACH), number(s.get("arc"), d.arcDeg, 10, 180),
				number(s.get("band"), d.band, 0.5, 6), levels(s.get("max-targets"), d.maxTargets, 1, 16), levels(s.get("bleed-seconds"), d.bleedSeconds, 0, 120),
				levels(s.get("bleed-damage"), d.bleedDamage, 0, 100), number(s.get("bleed-interval"), d.bleedIntervalSeconds, MIN_BLEED_INTERVAL, 60),
				levels(s.get("cooldown"), d.cooldownSeconds, 0, 3600));
	}

	/** The cooldown at a level in ticks (before the gameplay mode's factor). */
	public int cooldownTicks(int level) {
		return Math.max(0, (int) Math.round(cooldownSeconds.at(level) * 20));
	}

	/** The time between bleed pulses in ticks. */
	public int bleedIntervalTicks() {
		return Math.max(10, (int) Math.round(bleedIntervalSeconds * 20));
	}

	/** How many pulses a bleed of this level gives: its seconds over the interval, none when it is set to bleed for no time. */
	public int bleedPulses(int level) {
		double seconds = bleedSeconds.at(level);
		if (!(seconds > 0) || !(bleedDamage.at(level) > 0)) return 0;
		return Math.max(1, (int) Math.floor(seconds / bleedIntervalSeconds + 1e-9));
	}

	/** The reach at a level in tenths of a block, as the wire carries it. */
	public int reachTenths(int level) {
		return (int) Math.round(reach.at(level) * 10);
	}

	/** The fan's width as the wire carries it: whole degrees. */
	public int arcWhole() {
		return (int) Math.round(arcDeg);
	}

	/** The level's numbers in words for the upgrade tree: "3 slashes · 2 damage each · bleeds 4 s". */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/** The same as typed pieces for the Tooltips setting: every clause is a number of the level, so all of it stays. */
	public Lines lines(int level) {
		return Lines.builder().always(slashes + (slashes == 1 ? " slash" : " slashes")).always(number(damage.at(level)) + " damage each")
				.always("bleeds " + number(bleedSeconds.at(level)) + " s").build();
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
