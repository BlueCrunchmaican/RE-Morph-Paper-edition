package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.crossbow (my design, 2026-11-16, 8.0.0-beta.2) as typed values: the tendril bolt the crossbow shoots, how
 * long it takes to grow back, and Spine Burst, the crossbow's F. Read from the form's section, which the manager hands the module with the
 * bundled defaults merged in; a value that is missing or unreadable takes the default (which is exactly what the bundled file says, tested),
 * and numbers are clamped rather than rejected. Lengths are in blocks, times in seconds in the file and ticks here. Pure, so it is unit tested
 * without a server.
 * <p>
 * The file's parts: {@code bolt-damage} and {@code bolt-speed} are the bolt a click fires (damage by tier, flat: the game's own would be
 * its base damage times the speed plus a random critical bonus, which no one can read off a tooltip), {@code regrow-seconds} is how long a
 * bolt takes to grow back after every shot (my call: the same time it takes to charge a crossbow, which is 1.25 seconds, 25 ticks, and
 * shorter with Quick Charge as the game works it out: {@link CrossbowBolt#growTicks}), and {@code special} is Spine Burst (its name, how many
 * spines, how many creatures each pierces, the damage of each by tier, their speed and reach, how many ticks apart they leave, the cooldown).
 *
 * @param name Spine Burst's name for messages and the wheel
 * @param boltDamage what a bolt does to what it strikes, by tier
 * @param boltSpeed a bolt's speed in blocks a tick (the game's own crossbow shoots 3.15)
 * @param regrowSeconds how long a bolt takes to grow back with no Quick Charge (the game's own crossbow charges for 1.25)
 * @param spines how many spines a burst fires
 * @param pierce how many creatures each spine passes through before it stops
 * @param spineDamage what each spine does to each creature it strikes, by tier
 * @param spineSpeed a spine's speed in blocks a tick
 * @param spineRange how far a spine flies before it is gone, in blocks
 * @param gapTicks how many ticks apart the spines leave
 * @param cooldownSeconds Spine Burst's cooldown, by tier
 */
public record CrossbowSettings(String name, ArmConfig.Levels boltDamage, double boltSpeed, double regrowSeconds, int spines, int pierce, ArmConfig.Levels spineDamage,
		double spineSpeed, double spineRange, int gapTicks, ArmConfig.Levels cooldownSeconds) {
	/** The defaults: the bundled abilities.yml's crossbow section. */
	public static final CrossbowSettings DEFAULTS = new CrossbowSettings("Spine Burst", new ArmConfig.Levels(7, 8, 9), 3.15, 1.25, 3, 3, new ArmConfig.Levels(3, 4, 5), 3.5, 48, 2,
			new ArmConfig.Levels(8, 7, 6));

	/** The form's section (arms.forms.crossbow) as plain values to settings; null or empty gives the defaults. */
	public static CrossbowSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty()) return DEFAULTS;
		CrossbowSettings d = DEFAULTS;
		Map<String, Object> special = map(form.get("special"));
		return new CrossbowSettings(string(special.get("name"), d.name), levels(form.get("bolt-damage"), d.boltDamage, 0, 1000), number(form.get("bolt-speed"), d.boltSpeed, 0.5, 6),
				number(form.get("regrow-seconds"), d.regrowSeconds, 0.05, 60), (int) Math.round(number(special.get("spines"), d.spines, 1, 12)),
				(int) Math.round(number(special.get("pierce"), d.pierce, 1, 16)), levels(special.get("damage"), d.spineDamage, 0, 1000),
				number(special.get("speed"), d.spineSpeed, 0.5, 6), number(special.get("range"), d.spineRange, 4, 128),
				(int) Math.round(number(special.get("gap-ticks"), d.gapTicks, 1, 10)), levels(special.get("cooldown"), d.cooldownSeconds, 0, 3600));
	}

	/** Spine Burst's cooldown at a level in ticks (before the gameplay mode's factor). */
	public int cooldownTicks(int level) {
		return Math.max(0, (int) Math.round(cooldownSeconds.at(level) * 20));
	}

	/**
	 * The arrow's pierce level for a spine that passes through {@code pierce} creatures: the game's piercing N stops an arrow after N + 1
	 * creatures, so the level is one less.
	 */
	public int pierceLevel() {
		return Math.max(0, pierce - 1);
	}

	/** How many ticks a spine flies before it is removed: the range at its speed, and a tick for the last of it. */
	public int spineLifeTicks() {
		return (int) Math.ceil(spineRange / spineSpeed) + 1;
	}

	/** How many ticks a burst takes from its first spine to its last (the first leaves at once). */
	public int burstTicks() {
		return (spines - 1) * gapTicks;
	}

	/** The level's numbers in words for the upgrade tree: "Spine Burst fires 3 spines in a line, each through up to 3 creatures for 4 · bolts hit for 8". */
	public String describe(int level) {
		return lines(level).text(true);
	}

	/**
	 * The same as typed pieces for the Tooltips setting. What the burst does is explanation; how many spines, how many creatures each pierces and
	 * each one's damage are numbers of the level and stay ("Spine Burst 3 spines, each piercing up to 3 creatures" with tooltips off).
	 */
	public Lines lines(int level) {
		return Lines.builder().always(name).tipMore(" fires").alwaysMore(" " + spines + " spines").tipMore(" in a line")
				.alwaysMore(", each piercing up to " + pierce + " creatures")
				.always("spines hit for " + number(spineDamage.at(level))).always("bolts hit for " + number(boltDamage.at(level))).build();
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
