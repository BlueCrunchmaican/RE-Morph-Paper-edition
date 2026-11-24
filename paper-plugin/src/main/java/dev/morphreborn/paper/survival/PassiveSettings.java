package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.tips.Lines;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What each biomass passive costs and how it is billed (survival.yml {@code passives:}), with the built-in defaults, so a
 * stale survival.yml (a server that ran a beta keeps its own file, as docs/SURVIVAL-MODE.md says) still bills them: before
 * the defaults lived in the file alone and a passive with no entry cost nothing. Pure (no server types), so it is unit tested.
 * <p>
 * My rule (2026-11-16): a passive uses no biomass until it acts. Each one is one of three kinds:
 * <ul>
 *   <li>{@link Billing#TRIGGERED}: free until it acts, then billed for each thing it does (Fire Shedding for each second of
 *       fire it stops, Antitoxin for each effect, Scavenger for each item).</li>
 *   <li>{@link Billing#ACTING}: drains only in the runs in which it is doing something (Night Eyes in the dark, Gills under
 *       water, Tendril Sense with a creature in range, Sustenance for each point of hunger it fills).</li>
 *   <li>{@link Billing#CONSTANT}: drains the whole time it is on (Borrowed Scent, while a creature is combined with you: your own
 *       body with one lent to it, or one with your own head; in your own body, or a morph of creatures alone, which is
 *       Camouflage's, it does nothing and costs nothing).</li>
 * </ul>
 * An entry in the file with no {@code billing} key is the old layout of the 8.0.0 betas (a drain a minute for every passive,
 * which meant something else), so it is ignored for the defaults rather than half-applied.
 */
public final class PassiveSettings {
	public static final String SUSTENANCE = "passive_sustenance", NIGHT_EYES = "passive_night_eyes", GILLS = "passive_gills",
			FIRE_SHEDDING = "passive_fire_shedding", ANTITOXIN = "passive_antitoxin", TENDRIL_SENSE = "passive_tendril_sense",
			SCAVENGER = "passive_scavenger", BORROWED_SCENT = "passive_borrowed_scent";
	/** In the order the command and the menu list them. */
	public static final List<String> ALL = List.of(SUSTENANCE, NIGHT_EYES, GILLS, FIRE_SHEDDING, ANTITOXIN, TENDRIL_SENSE, SCAVENGER, BORROWED_SCENT);

	public enum Billing {
		CONSTANT, ACTING, TRIGGERED;

		/** The word in survival.yml, or null when it isn't one of them. */
		public static @Nullable Billing parse(@Nullable Object o) {
			if (o == null) return null;
			try {
				return valueOf(o.toString().trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/** The ones whose kind an admin may switch between acting and constant ("billing: constant" in survival.yml); the others' kind is how they work. */
	private static final Set<String> SWITCHABLE = Set.of(NIGHT_EYES, TENDRIL_SENSE, BORROWED_SCENT);

	private static final Map<String, Map<String, Object>> DEFAULTS = defaults();

	private static Map<String, Map<String, Object>> defaults() {
		Map<String, Map<String, Object>> m = new LinkedHashMap<>();
		m.put(SUSTENANCE, map("billing", "acting", "per-food-point", 2, "per-saturation", 0.5, "saturation-floor", 5));
		m.put(NIGHT_EYES, map("billing", "acting", "drain-per-minute", 1.5, "dark-light-level", 6));
		m.put(GILLS, map("billing", "acting", "per-second", 0.15));
		m.put(FIRE_SHEDDING, map("billing", "triggered", "per-second", 0.5));
		m.put(ANTITOXIN, map("billing", "triggered", "per-effect", 1));
		m.put(TENDRIL_SENSE, map("billing", "acting", "drain-per-minute", 30, "radius", 24));
		m.put(SCAVENGER, map("billing", "triggered", "per-item", 0.05, "reach", List.of(6, 10, 16)));
		m.put(BORROWED_SCENT, map("billing", "constant", "drain-per-minute", 6));
		return Collections.unmodifiableMap(m);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return Collections.unmodifiableMap(m);
	}

	private PassiveSettings() {}

	/** The built-in defaults of {@code id}, empty for an id that isn't a passive. */
	public static Map<String, Object> defaultsOf(String id) {
		return DEFAULTS.getOrDefault(id, Map.of());
	}

	/** One passive's settings: its kind and its numbers, the file's over the defaults. */
	public static final class Entry {
		private final String id;
		private final Billing billing;
		private final Map<String, Object> values;

		private Entry(String id, Billing billing, Map<String, Object> values) {
			this.id = id;
			this.billing = billing;
			this.values = values;
		}

		public String id() {
			return id;
		}

		public Billing billing() {
			return billing;
		}

		/** A number setting, never below 0; the default (or {@code fallback}) when missing or not a number. */
		public double num(String key, double fallback) {
			return Math.max(0, number(values.get(key), number(DEFAULTS.getOrDefault(id, Map.of()).get(key), fallback)));
		}

		public double num(String key) {
			return num(key, 0);
		}

		/** A number setting in whole milli (0.05 is 50). */
		public long milli(String key) {
			return Math.round(num(key) * 1000);
		}

		/** A per-level number: a list (the last entry for higher levels) or one number for all. */
		public double perLevel(String key, int level, double fallback) {
			Object v = values.get(key);
			if (v instanceof List<?> list && !list.isEmpty()) return Math.max(0, number(list.get(Math.clamp(level, 1, list.size()) - 1), fallback));
			return num(key, fallback);
		}

		/**
		 * Milli a minute for the drain kinds: {@code drain-per-minute}, or {@code per-second} times 60 (Gills is priced by the
		 * second). Both are billed through one exact accumulator.
		 */
		public long drainMilliPerMinute() {
			if (values.containsKey("per-second") && !values.containsKey("drain-per-minute")) return Math.round(num("per-second") * 60_000);
			return Math.round(num("drain-per-minute") * 1000);
		}

		/** The one line the menu shows under the description: what it costs and when. */
		public String costLine() {
			return costLines().text(true);
		}

		/**
		 * {@link #costLine} as typed pieces for the Tooltips setting: what a passive costs and per what is a rate and always stays; when it
		 * is billed (only in the dark, nothing while you are full) is explanation. The sentences used to hold both and were split so
		 * the price is never lost with its condition.
		 */
		public Lines costLines() {
			boolean constant = billing == Billing.CONSTANT;
			return switch (id) {
				case SUSTENANCE -> Lines.builder().always("Costs " + show(num("per-food-point")) + " biomass for each point of hunger it fills, and " + show(num("per-saturation"))
						+ " for each point of saturation that comes with it.").tipMore(" Nothing while you are full.").build();
				case NIGHT_EYES -> Lines.builder().always("Costs " + show(num("drain-per-minute")) + " biomass a minute").tipMore(constant ? " while on." : ", only in the dark.")
						.build();
				case GILLS -> Lines.builder().always("Costs " + show(num("per-second")) + " biomass a second").tipMore(", only under water.").build();
				case FIRE_SHEDDING -> Lines.builder().always("Costs " + show(num("per-second")) + " biomass for each second of fire it stops.").tipMore(" Nothing otherwise.").build();
				case ANTITOXIN -> Lines.builder().always("Costs " + show(num("per-effect")) + " biomass for each effect it stops.").tipMore(" Nothing otherwise.").build();
				case TENDRIL_SENSE -> Lines.builder().always("Costs " + show(num("drain-per-minute")) + " biomass a minute")
						.tipMore(constant ? " while on." : ", only with a creature in range.").build();
				case SCAVENGER -> Lines.builder().always("Costs " + show(num("per-item")) + " biomass for each item it fetches.").tipMore(" Nothing otherwise.").build();
				case BORROWED_SCENT -> Lines.builder().always("Costs " + show(num("drain-per-minute")) + " biomass a minute")
						.tipMore(" while on and a creature that can target you is combined with you. Nothing in your own body, in a morph of creatures alone, or with one that never targets players (a cow).")
						.build();
				default -> Lines.EMPTY;
			};
		}
	}

	/**
	 * The settings of {@code id}: the file's {@code yml} entry over the defaults, or the defaults alone when the file has no
	 * entry, the entry has no valid {@code billing} (the old 8.0.0 beta layout) or the id isn't a passive.
	 */
	public static Entry of(String id, @Nullable Map<String, Object> yml) {
		Map<String, Object> base = DEFAULTS.getOrDefault(id, Map.of());
		Billing byDefault = Billing.parse(base.get("billing"));
		if (byDefault == null) byDefault = Billing.TRIGGERED;
		Billing fromFile = yml == null ? null : Billing.parse(yml.get("billing"));
		if (fromFile == null) return new Entry(id, byDefault, base);
		Map<String, Object> merged = new HashMap<>(base);
		merged.putAll(yml);
		Billing billing = fromFile;
		if (!SWITCHABLE.contains(id) || billing == Billing.TRIGGERED) billing = byDefault;
		return new Entry(id, billing, Collections.unmodifiableMap(merged));
	}

	/**
	 * The passives of the file's {@code entries} that are in the old 8.0.0 beta layout (no {@code billing} key at all), in
	 * menu order. Ids that aren't passives are the admin's own and never count. Empty for a current file and for a file with
	 * no passives section. A {@code billing} that is there but not a kind is a typo, not the old layout: {@link #badBilling}.
	 */
	public static List<String> oldLayout(Map<String, Map<String, Object>> entries) {
		List<String> old = new ArrayList<>();
		for (String id : ALL) {
			Map<String, Object> entry = entries.get(id);
			if (entry != null && entry.get("billing") == null) old.add(id);
		}
		return List.copyOf(old);
	}

	/**
	 * The passives whose {@code billing} is written but isn't a kind (a typo such as "constnt"), in menu order; the built-in
	 * billing applies to them (see {@link #of}). Not counted as the old layout, which a typo is not (config-versions item,
	 * 2026-11-16).
	 */
	public static List<String> badBilling(Map<String, Map<String, Object>> entries) {
		List<String> bad = new ArrayList<>();
		for (String id : ALL) {
			Map<String, Object> entry = entries.get(id);
			if (entry != null && entry.get("billing") != null && Billing.parse(entry.get("billing")) == null) bad.add(id);
		}
		return List.copyOf(bad);
	}

	/** One line for the log about the survival.yml {@code passives:} section. {@code warning}: the admin has something to fix. */
	public record Notice(boolean warning, String text) {}

	/**
	 * What the log should say about the file's passives {@code entries}: at most one INFO line for the old layout (however
	 * many of the eight entries have it) and one WARN line naming the entries with an unreadable billing; nothing for a
	 * current file. Pure, so the wording and the count are unit tested; SurvivalConfig only logs what this returns.
	 */
	public static List<Notice> notices(Map<String, Map<String, Object>> entries) {
		List<Notice> out = new ArrayList<>();
		List<String> old = oldLayout(entries);
		if (!old.isEmpty()) {
			out.add(new Notice(false, "survival.yml: the passives section is the old beta layout (" + old.size() + " entries without a 'billing'); using the"
					+ " built-in billing; delete the passives section or the file to get the new one."));
		}
		List<String> bad = badBilling(entries);
		if (!bad.isEmpty()) {
			out.add(new Notice(true, "survival.yml: the 'billing' of " + String.join(", ", bad) + " is not one of constant, acting or triggered; using the"
					+ " built-in billing for " + (bad.size() == 1 ? "it." : "them.")));
		}
		return List.copyOf(out);
	}

	static double number(@Nullable Object o, double fallback) {
		double v = fallback;
		if (o instanceof Number n) v = n.doubleValue();
		else if (o != null) {
			try {
				v = Double.parseDouble(o.toString().trim());
			} catch (NumberFormatException e) {
				v = fallback;
			}
		}
		return Double.isNaN(v) || Double.isInfinite(v) ? fallback : v;
	}

	/** 2 -> "2", 0.05 -> "0.05", 1.5 -> "1.5". */
	static String show(double v) {
		return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}
}
