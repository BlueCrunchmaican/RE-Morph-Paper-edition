package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.bow as typed values: the bolts' damage, Triple Arrow (2026-11-16, 8.0.0-beta.2: it replaced the
 * Harpoon Shot) and the arrow tips. Read from the form's section, which the manager hands the module with the bundled defaults merged in; a
 * value that is missing or unreadable takes the default (which is exactly what the bundled file says, tested) and numbers are clamped rather
 * than rejected. Distances are in degrees, damage in health points, times in seconds in the file and ticks here. Pure, so it is unit tested
 * without a server.
 *
 * @param boltDamage a bolt's base damage, by level (an arrow's own is 2); the game multiplies it by the bolt's speed, so a full draw hits for about three times it
 * @param tripleName the special's name for messages and the wheel
 * @param spreadDeg how far apart the three bolts of a volley fly, in degrees
 * @param armedSeconds how long Triple Arrow waits for a full draw before it is put away unspent
 * @param cooldownSeconds the cooldown that starts when the volley is loosed, by level
 * @param bleedSeconds how long a bolt makes a creature bleed, by level (0: no bleed)
 * @param bleedDamage what each bleed pulse takes, by level
 * @param bleedIntervalSeconds the time between bleed pulses
 * @param slowSeconds how long the Slowness tip slows, by level
 * @param slowAmplifier the Slowness tip's effect level minus one, by level
 * @param poisonSeconds how long the Poison tip poisons, by level
 * @param poisonAmplifier the Poison tip's effect level minus one, by level
 * @param fireSeconds how long the Fire tip sets alight, by level
 * @param glowSeconds how long the Glowing tip marks, by level
 */
public record BowSettings(ArmConfig.Levels boltDamage, String tripleName, double spreadDeg, double armedSeconds, ArmConfig.Levels cooldownSeconds,
		ArmConfig.Levels bleedSeconds, ArmConfig.Levels bleedDamage, double bleedIntervalSeconds, ArmConfig.Levels slowSeconds, ArmConfig.Levels slowAmplifier,
		ArmConfig.Levels poisonSeconds, ArmConfig.Levels poisonAmplifier, ArmConfig.Levels fireSeconds, ArmConfig.Levels glowSeconds) {
	/** The defaults: the bundled abilities.yml's bow. */
	public static final BowSettings DEFAULTS = new BowSettings(new ArmConfig.Levels(2.0, 2.5, 3.0), "Triple Arrow", 15, 30, new ArmConfig.Levels(6, 5, 4),
			new ArmConfig.Levels(3, 4, 5), new ArmConfig.Levels(0.5, 0.75, 1), 1.0, new ArmConfig.Levels(3, 4, 5), new ArmConfig.Levels(1, 1, 1),
			new ArmConfig.Levels(4, 5, 6), new ArmConfig.Levels(0, 0, 0), new ArmConfig.Levels(4, 5, 6), new ArmConfig.Levels(6, 8, 10));

	/** A bleed pulse is never sooner than half a second after the last (a creature shrugs off a hit inside its hurt window), as the claws'. */
	public static final double MIN_BLEED_INTERVAL = 0.5;
	/** The widest a fan may be: 45 degrees either way leaves the bolts flying at the sides, not ahead. */
	public static final double MAX_SPREAD = 45;

	/** The form's section (arms.forms.bow) as plain values to settings; null or empty gives the defaults. */
	public static BowSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty()) return DEFAULTS;
		BowSettings d = DEFAULTS;
		Map<String, Object> t = map(form.get("triple"));
		Map<String, Object> tips = map(form.get("tips"));
		Map<String, Object> bleed = map(tips.get("bleed")), slow = map(tips.get("slowness")), poison = map(tips.get("poison")), fire = map(tips.get("fire")),
				glow = map(tips.get("glowing"));
		return new BowSettings(levels(form.get("bolt-damage"), d.boltDamage, 0, 1000), string(t.get("name"), d.tripleName), number(t.get("spread"), d.spreadDeg, 1, MAX_SPREAD),
				number(t.get("armed-seconds"), d.armedSeconds, 1, 600), levels(t.get("cooldown"), d.cooldownSeconds, 0, 3600),
				levels(bleed.get("seconds"), d.bleedSeconds, 0, 120), levels(bleed.get("damage"), d.bleedDamage, 0, 100),
				number(bleed.get("interval"), d.bleedIntervalSeconds, MIN_BLEED_INTERVAL, 60), levels(slow.get("seconds"), d.slowSeconds, 0, 600),
				levels(slow.get("amplifier"), d.slowAmplifier, 0, 9), levels(poison.get("seconds"), d.poisonSeconds, 0, 600),
				levels(poison.get("amplifier"), d.poisonAmplifier, 0, 9), levels(fire.get("seconds"), d.fireSeconds, 0, 600), levels(glow.get("seconds"), d.glowSeconds, 0, 600));
	}

	/** The cooldown at a level in ticks (before the gameplay mode's factor). */
	public int cooldownTicks(int level) {
		return Math.max(0, (int) Math.round(cooldownSeconds.at(level) * 20));
	}

	/** How long Triple Arrow stays ready, in ticks. */
	public int armedTicks() {
		return Math.max(20, (int) Math.round(armedSeconds * 20));
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

	/** An effect's length at a level in ticks (a tip's seconds), at least 0. */
	public static int ticks(ArmConfig.Levels seconds, int level) {
		return Math.max(0, (int) Math.round(seconds.at(level) * 20));
	}

	/** The level's numbers in words for the upgrade tree: "Triple Arrow: three bolts 15° apart on a full draw · bolts hit for 2.5 · bleed 4 s". */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/** The same as typed pieces for the Tooltips setting: that it fires three bolts, the damage and the tip's length stay; how they are spread and when is explanation. */
	public Lines lines(int level) {
		return Lines.builder().always(tripleName + ": three bolts").tipMore(" " + number(spreadDeg) + "° apart on a full draw")
				.always("bolts hit for " + number(boltDamage.at(level)))
				.always("tips last " + number(bleedSeconds.at(level)) + " s (bleed)").build();
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
