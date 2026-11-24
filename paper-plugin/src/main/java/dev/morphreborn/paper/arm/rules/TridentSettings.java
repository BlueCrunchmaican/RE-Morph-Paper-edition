package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.trident (my design, 2026-11-16, 8.0.0-beta.2) as typed values: the throw, the tendril that reels the
 * trident back, and Recall, the trident's F. Read from the form's section, which the manager hands the module with the bundled defaults
 * merged in; a value that is missing or unreadable takes the default (which is exactly what the bundled file says, tested), and numbers
 * are clamped rather than rejected. Lengths are in blocks, times in seconds in the file and ticks here. Pure, so it is unit tested
 * without a server.
 * <p>
 * The file's three parts: {@code special} is Recall (name, damage by tier, cooldown, the width of the path it sweeps), {@code throw} is the
 * flight (damage by tier, speed, how long and how far it flies before the tendril takes it back, how long it may rest in a block) and
 * {@code reel} is the tendril's pull (speed by tier, how fast it speeds up, the longest it may take). None of it ever moves the player:
 * the trident comes to the hand, the hand never goes to the trident (my rule).
 *
 * @param name Recall's name for messages and the wheel
 * @param recallDamage what Recall does to each creature on the way back, by tier
 * @param recallCooldownSeconds Recall's cooldown, by tier
 * @param recallWidth how far either side of the return path a creature still counts as on it (blocks, from the line to the creature's box)
 * @param throwDamage what the thrown trident does to what it strikes, by tier (vanilla's is 8)
 * @param throwSpeed the throw's speed in blocks a tick (vanilla's is 2.5)
 * @param flightTicks how long it flies, at most, before the tendril takes it back
 * @param range how far from the owner it may get, by tier, before the tendril takes it back (blocks)
 * @param stuckTicks how long it rests in a block before the tendril takes it back
 * @param reelSpeed the tendril's fastest pull, by tier (blocks a tick)
 * @param reelAccel how much faster the pull gets each tick (blocks a tick, a tick)
 * @param reelMaxTicks the longest a pull lasts; at the end the trident is simply back (a trident the pull can't free, a very long way)
 */
public record TridentSettings(String name, ArmConfig.Levels recallDamage, ArmConfig.Levels recallCooldownSeconds, double recallWidth, ArmConfig.Levels throwDamage,
		double throwSpeed, int flightTicks, ArmConfig.Levels range, int stuckTicks, ArmConfig.Levels reelSpeed, double reelAccel, int reelMaxTicks) {
	/** The defaults: the bundled abilities.yml's trident section. */
	public static final TridentSettings DEFAULTS = new TridentSettings("Recall", new ArmConfig.Levels(4, 5, 6), new ArmConfig.Levels(4, 3.5, 3), 0.8,
			new ArmConfig.Levels(8, 9, 10), 2.5, 40, new ArmConfig.Levels(32, 40, 48), 10, new ArmConfig.Levels(2.0, 2.4, 2.8), 0.3, 80);

	/** The form's section (arms.forms.trident) as plain values to settings; null or empty gives the defaults. */
	public static TridentSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty()) return DEFAULTS;
		TridentSettings d = DEFAULTS;
		Map<String, Object> special = map(form.get("special")), thrown = map(form.get("throw")), reel = map(form.get("reel"));
		return new TridentSettings(string(special.get("name"), d.name), levels(special.get("damage"), d.recallDamage, 0, 1000),
				levels(special.get("cooldown"), d.recallCooldownSeconds, 0, 3600), number(special.get("width"), d.recallWidth, 0.1, 4),
				levels(thrown.get("damage"), d.throwDamage, 0, 1000), number(thrown.get("speed"), d.throwSpeed, 0.5, 5),
				(int) Math.round(number(thrown.get("flight-ticks"), d.flightTicks, 1, 600)), levels(thrown.get("range"), d.range, 4, 128),
				(int) Math.round(number(thrown.get("stuck-ticks"), d.stuckTicks, 0, 200)), levels(reel.get("speed"), d.reelSpeed, 0.5, 5),
				number(reel.get("accel"), d.reelAccel, 0, 2), (int) Math.round(number(reel.get("max-ticks"), d.reelMaxTicks, 5, 600)));
	}

	/** Recall's cooldown at a level in ticks (before the gameplay mode's factor). */
	public int cooldownTicks(int level) {
		return Math.max(0, (int) Math.round(recallCooldownSeconds.at(level) * 20));
	}

	/** The level's numbers in words for the upgrade tree: "Recall snaps it back at once, hitting everything on the way for 5 · thrown for 9". */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/** The same as typed pieces for the Tooltips setting: what the recall does is explanation; its damage and the throw's are rates. */
	public Lines lines(int level) {
		return Lines.builder().tip(name + " snaps the trident back at once, hitting everything on the way")
				.always(name + " hits for " + number(recallDamage.at(level))).always("thrown for " + number(throwDamage.at(level))).build();
	}

	/** 6.0 as "6", 6.5 as "6.5". */
	static String number(double v) {
		return java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
	}

	// ------------------------------------------------------------------ plain-value helpers

	private static Map<String, Object> map(@Nullable Object o) {
		if (!(o instanceof Map<?, ?> m)) return Map.of();
		Map<String, Object> out = new java.util.HashMap<>();
		for (Map.Entry<?, ?> e : m.entrySet()) {
			if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
		}
		return out;
	}

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
