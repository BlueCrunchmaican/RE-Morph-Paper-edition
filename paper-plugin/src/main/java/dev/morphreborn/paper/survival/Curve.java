package dev.morphreborn.paper.survival;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * An upgrade's value or cost by level: iChun's BiomassUpgradeInfo.Multiplier, with the same five equations.
 * Level 0 (not bought) is always 0.
 *
 * @param equation 1 linear {@code base + increment * multiplier * level};
 *                 2 power {@code base + increment * (level - 1)^multiplier};
 *                 3 exponential {@code base + increment * multiplier^(level - 1)};
 *                 4 log {@code base + increment * log10(level * multiplier)};
 *                 5 square root {@code base + increment * sqrt((level - 1) * multiplier)}
 */
public record Curve(double base, double increment, double multiplier, int equation) {
	public static final Curve ZERO = new Curve(0, 0, 0, 1);

	public double at(int level) {
		if (level <= 0) return 0;
		return switch (equation) {
			case 2 -> base + increment * Math.pow(level - 1, multiplier);
			case 3 -> base + increment * Math.pow(multiplier, level - 1);
			case 4 -> base + increment * Math.log10(level * multiplier);
			case 5 -> base + increment * Math.sqrt((level - 1) * multiplier);
			default -> base + increment * multiplier * level;
		};
	}

	/** {@code {base, increment, multiplier, equation}}; like iChun's, a missing increment means "same as base". */
	public static Curve read(@Nullable ConfigurationSection c) {
		if (c == null) return ZERO;
		double base = c.getDouble("base", 0);
		return new Curve(base, c.getDouble("increment", base), c.getDouble("multiplier", 0), c.getInt("equation", 1));
	}
}
