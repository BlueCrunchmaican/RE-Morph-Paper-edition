package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.spear.special (my design, 2026-11-16, 8.0.0-beta.2) as typed values: Impale, the spear's F.
 * The spear's own jab and charge are vanilla's (per-tier components on the item), so the only settings are the special's.
 * Read from the form's section, which the manager hands the module with the bundled defaults merged in; a value that is missing
 * or unreadable takes the default (which is exactly what the bundled file says, tested), and numbers are clamped rather than
 * rejected. Distances are in blocks, damage in health points, the hold and the cooldown in seconds in the file and ticks here.
 * Pure, so it is unit tested without a server.
 *
 * @param name the special's name for messages and the wheel
 * @param lunge how far the lunge goes at most, by level (blocks along the ground)
 * @param rootSeconds how long the spikes pin the creature, by level
 * @param damage what the skewer deals, by level
 * @param cooldownSeconds the special's cooldown, by level
 * @param reach how far in front of the lunge's end the spear still skewers (blocks, from the body's middle to the creature's near side)
 * @param pinPlayers the spikes may pin a player (the same default as the whip's snare: off); a player is skewered for the damage
 *            either way, when arms.players and the PvP rules allow it
 */
public record SpearSettings(String name, ArmConfig.Levels lunge, ArmConfig.Levels rootSeconds, ArmConfig.Levels damage, ArmConfig.Levels cooldownSeconds,
		double reach, boolean pinPlayers) {
	/** The defaults: the bundled abilities.yml's spear special. */
	public static final SpearSettings DEFAULTS = new SpearSettings("Impale", new ArmConfig.Levels(3, 4, 5), new ArmConfig.Levels(1.5, 2, 2.5),
			new ArmConfig.Levels(4, 5, 6), new ArmConfig.Levels(8, 7, 6), 2.5, false);

	/**
	 * The most the lunge may be set to (what ImpaleMath can drive in its longest run: a longer one would time out short of the creature
	 * and F would always pay for a whiff), the furthest the spear may be set to reach past it, and the longest a pin may be set to hold
	 * (what the clients draw: 100 ticks). Review 2026-11-16.
	 */
	public static final double MAX_LUNGE = ImpaleMath.MAX_DISTANCE, MAX_REACH = 6, MAX_ROOT_SECONDS = 5;

	/** The form's section (arms.forms.spear) as plain values to settings; null or empty gives the defaults. */
	public static SpearSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty() || !(form.get("special") instanceof Map<?, ?> raw)) return DEFAULTS;
		@SuppressWarnings("unchecked")
		Map<String, Object> s = (Map<String, Object>) raw;
		SpearSettings d = DEFAULTS;
		return new SpearSettings(string(s.get("name"), d.name), levels(s.get("lunge"), d.lunge, 0, MAX_LUNGE), levels(s.get("root-seconds"), d.rootSeconds, 0, MAX_ROOT_SECONDS),
				levels(s.get("damage"), d.damage, 0, 1000), levels(s.get("cooldown"), d.cooldownSeconds, 0, 3600), number(s.get("reach"), d.reach, 0.5, MAX_REACH),
				bool(s.get("pin-players"), d.pinPlayers));
	}

	/** How far the lunge goes at a level (blocks). */
	public double lungeAt(int level) {
		return lunge.at(level);
	}

	/** How long the spikes hold at a level, in ticks (at least 1: a pin of no length is no pin). */
	public int rootTicks(int level) {
		return Math.max(1, (int) Math.round(rootSeconds.at(level) * 20));
	}

	/** The cooldown at a level in ticks (before the gameplay mode's factor). */
	public int cooldownTicks(int level) {
		return Math.max(0, (int) Math.round(cooldownSeconds.at(level) * 20));
	}

	/**
	 * The level's numbers in words for the upgrade tree: "Lunges 4 blocks · pins 2 s · 5 damage". The spear's own tier (jab, charge)
	 * is said by the manager's "Diamond strength" before it.
	 */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/** The same as typed pieces for the Tooltips setting: all of it is the level's numbers. */
	public Lines lines(int level) {
		return Lines.builder().always("Lunges " + number(lunge.at(level)) + " blocks").always("pins " + number(rootSeconds.at(level)) + " s")
				.always(number(damage.at(level)) + " damage").build();
	}

	/** 6.0 as "6", 6.5 as "6.5". */
	static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}

	// ------------------------------------------------------------------ plain-value helpers

	private static String string(@Nullable Object v, String fallback) {
		return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
	}

	private static boolean bool(@Nullable Object v, boolean fallback) {
		if (v instanceof Boolean b) return b;
		if (v instanceof String s) {
			String t = s.trim().toLowerCase(Locale.ROOT);
			if (t.equals("true")) return true;
			if (t.equals("false")) return false;
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
