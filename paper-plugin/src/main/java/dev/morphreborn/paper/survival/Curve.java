package dev.morphreborn.paper.survival;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * An upgrade's value or cost by level: iChun's BiomassUpgradeInfo.Multiplier, with the same five equations, or a plain
 * list with one number per level. Level 0 (not bought) is always 0.
 *
 * @param equation 1 linear {@code base + increment * multiplier * level};
 *                 2 power {@code base + increment * (level - 1)^multiplier};
 *                 3 exponential {@code base + increment * multiplier^(level - 1)};
 *                 4 log {@code base + increment * log10(level * multiplier)};
 *                 5 square root {@code base + increment * sqrt((level - 1) * multiplier)}
 * @param list     when not empty, used instead of the formula: level 1 is the first number, and levels past the end
 *                 keep the last one
 */
public record Curve(double base, double increment, double multiplier, int equation, List<Double> list) {
	public static final Curve ZERO = new Curve(0, 0, 0, 1);

	public Curve(double base, double increment, double multiplier, int equation) {
		this(base, increment, multiplier, equation, List.of());
	}

	public Curve {
		list = List.copyOf(list);
	}

	public static Curve of(double... values) {
		List<Double> list = new ArrayList<>();
		for (double v : values) list.add(v);
		return new Curve(0, 0, 0, 1, list);
	}

	public double at(int level) {
		if (level <= 0) return 0;
		if (!list.isEmpty()) return list.get(Math.min(level, list.size()) - 1);
		return switch (equation) {
			case 2 -> base + increment * Math.pow(level - 1, multiplier);
			case 3 -> base + increment * Math.pow(multiplier, level - 1);
			case 4 -> base + increment * Math.log10(level * multiplier);
			case 5 -> base + increment * Math.sqrt((level - 1) * multiplier);
			default -> base + increment * multiplier * level;
		};
	}

	/**
	 * {@code key} in {@code parent}: either a list, one number per level ({@code [3, 8, 16]}), or
	 * {@code {base, increment, multiplier, equation}} where, like iChun's, a missing increment means "same as base".
	 */
	public static Curve read(ConfigurationSection parent, String key) {
		if (parent.isList(key)) {
			List<Double> list = new ArrayList<>();
			for (Object o : parent.getList(key, List.of())) {
				if (o instanceof Number n) list.add(n.doubleValue());
				else if (o != null) {
					try {
						list.add(Double.parseDouble(o.toString().replace(",", "").trim()));
					} catch (NumberFormatException e) {
						list.add(0.0);
					}
				}
			}
			return list.isEmpty() ? ZERO : new Curve(0, 0, 0, 1, list);
		}
		return read(parent.getConfigurationSection(key));
	}

	public static Curve read(@Nullable ConfigurationSection c) {
		if (c == null) return ZERO;
		double base = c.getDouble("base", 0);
		return new Curve(base, c.getDouble("increment", base), c.getDouble("multiplier", 0), c.getInt("equation", 1));
	}
}
