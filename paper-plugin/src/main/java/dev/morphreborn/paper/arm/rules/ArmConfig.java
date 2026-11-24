package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The arm forms' settings: abilities.yml {@code arms:} and survival.yml {@code arm-cost:} (spec 3.13), read into typed
 * values once per load. Costs are in milli-biomass, cooldowns in ticks.
 * <p>
 * The server's {@code arms:} section is laid over the bundled one, key by key, before anything is read. abilities.yml
 * is only replaced when its config-version is older than the bundled one (8.0.0 since the config-versions item,
 * 2026-11-16; a server's 8.0.0 beta file is the same version, so it is never replaced), so a server's file can lack
 * the {@code arms:} section or newer keys in it; it still gets every setting, from the plugin's own copy. The code's defaults equal the bundled file (tested), so even a missing bundled section changes
 * nothing.
 * <p>
 * Values are clamped, never rejected: a negative cost, reach or cooldown counts as 0. YAML reads a bare {@code on} and
 * {@code off} as true and false, so the three-way settings accept booleans too.
 * <p>
 * {@link #rawForms}, {@link #rawLantern} and {@link #rawAccessories} carry each form's, the lantern's and each accessory's merged
 * section as plain values for the modules that read their own keys (the lantern's is {@code arms.lantern}; the shield, spyglass
 * and flint's are {@code arms.accessories.<id>}). They are left out of {@link #equals}: they hold the same values in
 * whatever types the file gave (120 and 120.0), and the typed values are what decide behaviour.
 * <p>
 * Pure: {@link #parse} takes plain maps (nested maps, or a section's flattened {@code a.b.c} keys), so it is unit
 * tested without a server.
 *
 * @param recallSeconds no longer used since arm slots (2026-11-16: bindings replaced the 8-second recall); still read
 *            so an existing abilities.yml keeps loading
 * @param minToFormMilli the biomass a Survival player needs to form an arm (milli)
 * @param stashMaxBytes the largest held item an arm may replace, serialised
 * @param disabledForms forms and accessories switched off with {@code enabled: false}
 * @param rawAccessories every accessory's merged section by id (lantern, shield, spyglass, flint), the lantern's the same map as {@code rawLantern}
 */
public record ArmConfig(boolean enabled, boolean report, int classicLevel, int reformLockoutTicks, int recallSeconds, long minToFormMilli, int stashMaxBytes,
		InventorySafety inventorySafety, Commands commandGuard, BlockSpecials blockSpecials, boolean players, double playerFloor, double lesserRate,
		double passiveRate, boolean tierFollowsBiomass, Set<String> disabledForms, Costs costs, Katana katana, Pickaxe pickaxe, Map<String, Map<String, Object>> rawForms,
		Map<String, Object> rawLantern, Map<String, Map<String, Object>> rawAccessories) {

	/** arms.inventory-safety: retract before world and game-mode changes always, never, or when a per-world inventory plugin is installed. */
	public enum InventorySafety {
		AUTO, ON, OFF;

		static InventorySafety parse(@Nullable Object v) {
			if (v instanceof Boolean b) return b ? ON : OFF;
			if (v == null) return AUTO;
			return switch (v.toString().trim().toLowerCase(Locale.ROOT)) {
				case "on", "true", "yes", "always" -> ON;
				case "off", "false", "no", "never" -> OFF;
				default -> AUTO;
			};
		}
	}

	/** arms.block-specials: whether the specials that break blocks may, always, never, or by the mobGriefing gamerule. */
	public enum BlockSpecials {
		ON, OFF, GAMERULE;

		static BlockSpecials parse(@Nullable Object v) {
			if (v instanceof Boolean b) return b ? ON : OFF;
			if (v == null) return ON;
			return switch (v.toString().trim().toLowerCase(Locale.ROOT)) {
				case "off", "false", "no" -> OFF;
				case "gamerule", "mobgriefing", "follow-gamerule" -> GAMERULE;
				default -> ON;
			};
		}
	}

	/** arms.command-guard: the mode and the two lists, names normalised (no slash, no namespace, lower case). */
	public record Commands(CommandGuard.Mode mode, Set<String> listed, Set<String> keep) {
		public Commands {
			listed = Set.copyOf(listed);
			keep = Set.copyOf(keep);
		}

		/** CommandGuard.retractFirst with these lists. */
		public boolean retractFirst(String typedLabel, @Nullable String resolvedName) {
			return CommandGuard.retractFirst(typedLabel, resolvedName, mode, listed, keep);
		}
	}

	/** One number for each level: 1 iron, 2 diamond, 3 netherite. A level outside 1..3 reads the nearest. */
	public record Levels(double l1, double l2, double l3) {
		public double at(int level) {
			return level <= 1 ? l1 : level == 2 ? l2 : l3;
		}

		public int intAt(int level) {
			return (int) Math.round(at(level));
		}
	}

	/** One milli amount for each level. */
	public record MilliLevels(long l1, long l2, long l3) {
		public long at(int level) {
			return level <= 1 ? l1 : level == 2 ? l2 : l3;
		}
	}

	/**
	 * arm-cost, in milli. {@code enabled} false makes every arm action free and stops the drain even in Survival; read
	 * costs through {@link #charge} so that holds everywhere.
	 *
	 * @param accessoryDrain milli a minute each accessory costs while it is on (lantern, shield, spyglass, flint); {@code lanternDrainPerMinute} is the lantern's
	 * @param specials by key: katana, pickaxe, axe-fell, axe-cleave, shovel, sickle, bow-triple (Triple Arrow), the whip's four moves (whip-snare, -disarm, -crack, -whirl) and its line costs
	 *            (whip-cast, -pull, -yank); the new forms' (spear, mace, trident-recall, trident-throw, crossbow, claws) and the accessories' per-use costs (shield-block, flint-ignite)
	 */
	public record Costs(boolean enabled, long perBlock, long perHit, long perTransform, long perBolt, long toolDrainPerMinute, long lanternDrainPerMinute,
			Map<String, Long> accessoryDrain, Map<String, MilliLevels> specials) {
		public Costs {
			accessoryDrain = Collections.unmodifiableMap(new LinkedHashMap<>(accessoryDrain));
			specials = Collections.unmodifiableMap(new LinkedHashMap<>(specials));
		}

		/** What one accessory drains a minute while it is on, in milli: 0 for an unknown one or with costs switched off. */
		public long accessoryDrainPerMinute(String id) {
			Long milli = accessoryDrain.get(id);
			return milli == null ? 0 : charge(milli);
		}

		/** {@code milli}, or 0 when arm costs are switched off. */
		public long charge(long milli) {
			return enabled ? Math.max(0, milli) : 0;
		}

		/** A special's cost at a level, 0 for an unknown key or with costs switched off. */
		public long special(String key, int level) {
			MilliLevels l = specials.get(key);
			return l == null ? 0 : charge(l.at(level));
		}
	}

	/** arms.forms.katana: Draining Cut (spec 3.7). Distances in blocks, damage and healing in health points. */
	public record Katana(String name, Levels reach, Levels arc, double band, Levels maxTargets, Levels damage, Levels latchPulses, Levels latchDamage,
			double healRatio, Levels healPerPulse, Levels healPerTarget, Levels healCap, Levels cooldownTicks) {}

	/** arms.forms.pickaxe: Fortune and Vein Rip (spec 3.8). */
	public record Pickaxe(String name, Levels fortune, Levels reach, Levels maxBlocks, int maxOffset, Levels perTick, boolean pullDrops, Levels cooldownTicks,
			Map<String, List<String>> extraOreGroups) {
		public Pickaxe {
			Map<String, List<String>> copy = new LinkedHashMap<>();
			extraOreGroups.forEach((k, v) -> copy.put(k, List.copyOf(v)));
			extraOreGroups = Collections.unmodifiableMap(copy);
		}

		/** Ores broken per tick, in tenths (ArmProtocol.veinBreakTick and the VEIN effect's arg). At least 1. */
		public int rateTenths(int level) {
			return Math.max(1, (int) Math.round(perTick.at(level) * 10));
		}

		/** The walk looks at no more than four blocks for each it may break. */
		public int visitCap(int level) {
			return 4 * maxBlocks.intAt(level);
		}
	}

	// ------------------------------------------------------------------ defaults (the bundled files' values)

	private static final List<String> DEFAULT_COMMANDS = List.of("sell", "sellhand", "sellall", "ah", "auction", "auctionhouse", "hat", "repair", "fix",
			"itemname", "iname", "rename", "lore", "itemlore", "enchant", "ench", "clear", "ci", "clearinventory", "trade");
	private static final List<String> DEFAULT_KEEP = List.of("morph", "msg", "tell", "w", "r", "reply", "me", "list", "help", "spawn", "home", "homes", "tpa",
			"tpahere", "tpaccept", "tpdeny", "back", "warp", "warps");
	private static final Map<String, MilliLevels> DEFAULT_SPECIAL_COSTS = defaultSpecialCosts();
	/** What each accessory drains a minute while on, in biomass (arm-cost.drain-per-minute): the lantern's 0.5, the shield's 0.5, the spyglass's and flint's 0.25. */
	private static final Map<String, Double> DEFAULT_ACCESSORY_DRAIN = Map.of("lantern", 0.5, "shield", 0.5, "spyglass", 0.25, "flint", 0.25);
	private static final Levels DEFAULT_FORTUNE = new Levels(1, 2, 3);
	private static final Levels DEFAULT_EFFICIENCY = new Levels(1, 2, 3);

	private static Map<String, MilliLevels> defaultSpecialCosts() {
		Map<String, MilliLevels> m = new LinkedHashMap<>();
		m.put("katana", new MilliLevels(12_000, 15_000, 18_000));
		m.put("pickaxe", new MilliLevels(8_000, 10_000, 12_000));
		m.put("axe-fell", new MilliLevels(10_000, 12_000, 14_000));
		m.put("axe-cleave", new MilliLevels(10_000, 12_000, 14_000));
		m.put("shovel", new MilliLevels(8_000, 10_000, 12_000));
		m.put("sickle", new MilliLevels(8_000, 10_000, 12_000));
		m.put("bow-triple", new MilliLevels(6_000, 8_000, 10_000));
		// the whip's four F moves (2026-11-16: the Lash is gone); each has its own row, found-nothing costs nothing
		m.put("whip-snare", new MilliLevels(6_000, 7_000, 8_000));
		m.put("whip-disarm", new MilliLevels(4_000, 5_000, 6_000));
		m.put("whip-crack", new MilliLevels(6_000, 7_000, 8_000));
		m.put("whip-whirl", new MilliLevels(10_000, 12_000, 14_000));
		m.put("whip-cast", new MilliLevels(250, 250, 250));
		m.put("whip-pull", new MilliLevels(500, 500, 500));
		m.put("whip-yank", new MilliLevels(500, 500, 500));
		// the new forms (8.0.0-beta.2): placeholders in line with the existing specials; the owner can steer them in survival.yml
		m.put("spear", new MilliLevels(10_000, 12_000, 15_000));
		m.put("mace", new MilliLevels(8_000, 10_000, 12_000));
		m.put("trident-recall", new MilliLevels(4_000, 5_000, 6_000));
		m.put("trident-throw", new MilliLevels(500, 500, 500));
		m.put("crossbow", new MilliLevels(10_000, 12_000, 15_000));
		m.put("claws", new MilliLevels(8_000, 10_000, 12_000));
		// accessory per-use costs: a blocked hit, an ignition
		m.put("shield-block", new MilliLevels(1_000, 1_000, 1_000));
		m.put("flint-ignite", new MilliLevels(500, 500, 500));
		return Collections.unmodifiableMap(m);
	}

	/** Every setting at the bundled files' values. Declared after the constants it is built from. */
	public static final ArmConfig DEFAULTS = parse(Map.of(), Map.of(), Map.of());

	// ------------------------------------------------------------------ reading

	/**
	 * @param arms the server's abilities.yml {@code arms:} section as plain values (empty when it has none)
	 * @param armCost survival.yml's {@code arm-cost:}
	 * @param bundledArms the plugin's own {@code arms:} section, laid under {@code arms}
	 */
	public static ArmConfig parse(@Nullable Map<String, Object> arms, @Nullable Map<String, Object> armCost, @Nullable Map<String, Object> bundledArms) {
		Map<String, Object> a = merge(plain(bundledArms), plain(arms));
		Map<String, Object> cost = plain(armCost);
		Map<String, Object> forms = section(a, "forms");
		Map<String, Object> lantern = section(a, "lantern");
		Map<String, Object> accessories = section(a, "accessories");
		Map<String, Object> guard = section(a, "command-guard");

		Set<String> disabled = new HashSet<>();
		for (Map.Entry<String, Object> e : forms.entrySet()) {
			if (e.getValue() instanceof Map<?, ?> m && !bool(m.get("enabled"), true)) disabled.add(e.getKey());
		}
		if (!bool(lantern.get("enabled"), true)) disabled.add("lantern");
		Map<String, Map<String, Object>> rawAccessories = new LinkedHashMap<>();
		for (String id : ArmTiers.ACCESSORIES) {
			// the lantern's section predates the others and sits beside forms:, not under accessories:
			Map<String, Object> own = id.equals("lantern") ? lantern : section(accessories, id);
			if (!id.equals("lantern") && !bool(own.get("enabled"), true)) disabled.add(id);
			rawAccessories.put(id, frozen(own));
		}

		Commands commands = new Commands(CommandGuard.Mode.parse(guard.get("mode")), commandSet(guard.get("commands"), DEFAULT_COMMANDS),
				commandSet(guard.get("keep"), DEFAULT_KEEP));

		Map<String, Map<String, Object>> rawForms = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : forms.entrySet()) {
			if (e.getValue() instanceof Map<?, ?>) rawForms.put(e.getKey(), frozen(section(forms, e.getKey())));
		}

		return new ArmConfig(bool(a.get("enabled"), true), bool(a.get("report"), false), (int) Math.clamp(integer(a.get("classic-level"), 2), 1, 3),
				nonNegativeInt(a.get("reform-lockout-ticks"), 10), nonNegativeInt(a.get("recall-seconds"), 8),
				nonNegative(ArmLedger.toMilli(number(a.get("min-biomass-to-form"), 1))), nonNegativeInt(a.get("stash-max-bytes"), 524_288),
				InventorySafety.parse(a.get("inventory-safety")), commands, BlockSpecials.parse(a.get("block-specials")), bool(a.get("players"), true),
				Math.max(0, number(a.get("player-floor"), 2)), Math.clamp(number(a.get("lesser-rate"), 0.5), 0, 1),
				Math.clamp(number(a.get("passive-rate"), 0.5), 0, 1), bool(a.get("tier-follows-biomass"), true), Set.copyOf(disabled), costs(cost), katana(section(section(forms, "katana"), "special")),
				pickaxe(section(forms, "pickaxe")), Collections.unmodifiableMap(rawForms), frozen(lantern),
				Collections.unmodifiableMap(rawAccessories));
	}

	private static Costs costs(Map<String, Object> c) {
		Map<String, Object> drain = section(c, "drain-per-minute");
		Map<String, Object> specials = section(c, "specials");
		Map<String, Long> accessoryDrain = new LinkedHashMap<>();
		for (String id : ArmTiers.ACCESSORIES) accessoryDrain.put(id, milli(drain.get(id), DEFAULT_ACCESSORY_DRAIN.get(id)));
		Map<String, MilliLevels> out = new LinkedHashMap<>(DEFAULT_SPECIAL_COSTS);
		for (Map.Entry<String, Object> e : specials.entrySet()) {
			MilliLevels fallback = DEFAULT_SPECIAL_COSTS.getOrDefault(e.getKey(), new MilliLevels(0, 0, 0));
			Levels l = levels(e.getValue(), new Levels(fallback.l1() / 1000.0, fallback.l2() / 1000.0, fallback.l3() / 1000.0));
			out.put(e.getKey(), new MilliLevels(nonNegative(ArmLedger.toMilli(l.l1())), nonNegative(ArmLedger.toMilli(l.l2())),
					nonNegative(ArmLedger.toMilli(l.l3()))));
		}
		return new Costs(bool(c.get("enabled"), true), milli(c.get("per-block"), 0.1), milli(c.get("per-hit"), 0.25), milli(c.get("per-transform"), 0.1),
				milli(c.get("per-bolt"), 1), milli(drain.get("tool"), 1), milli(drain.get("lantern"), 0.5), accessoryDrain, out);
	}

	private static Katana katana(Map<String, Object> s) {
		return new Katana(string(s.get("name"), "Draining Cut"), positive(levels(s.get("reach"), new Levels(4.5, 5.0, 5.5))),
				positive(levels(s.get("arc"), new Levels(120, 135, 150))), Math.max(0, number(s.get("band"), 2.5)),
				whole(levels(s.get("max-targets"), new Levels(4, 6, 8))), positive(levels(s.get("damage"), new Levels(6, 7, 8))),
				whole(levels(s.get("latch-pulses"), new Levels(0, 1, 2))), positive(levels(s.get("latch-damage"), new Levels(0, 1.5, 2.0))),
				Math.max(0, number(s.get("heal-ratio"), 0.5)), positive(levels(s.get("heal-per-pulse"), new Levels(1.0, 1.5, 2.0))),
				positive(levels(s.get("heal-per-target"), new Levels(2, 3, 4))), positive(levels(s.get("heal-cap"), new Levels(6, 9, 12))),
				ticks(levels(s.get("cooldown"), new Levels(7, 6, 5))));
	}

	private static Pickaxe pickaxe(Map<String, Object> form) {
		Map<String, Object> s = section(form, "special");
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : section(s, "extra-ore-groups").entrySet()) {
			List<String> materials = new ArrayList<>();
			if (e.getValue() instanceof List<?> list) {
				for (Object o : list) {
					if (o != null && !o.toString().isBlank()) materials.add(o.toString().trim());
				}
			} else if (e.getValue() != null && !e.getValue().toString().isBlank()) {
				materials.add(e.getValue().toString().trim());
			}
			if (!materials.isEmpty()) groups.put(e.getKey(), materials);
		}
		return new Pickaxe(string(s.get("name"), "Vein Rip"), whole(levels(form.get("fortune"), DEFAULT_FORTUNE)),
				positive(levels(s.get("reach"), new Levels(5, 6, 7))), whole(levels(s.get("max-blocks"), new Levels(8, 16, 32))),
				nonNegativeInt(s.get("max-offset"), 6), positive(levels(s.get("per-tick"), new Levels(1.0, 1.5, 2.0))), bool(s.get("pull-drops"), true),
				ticks(levels(s.get("cooldown"), new Levels(8, 6, 4))), groups);
	}

	// ------------------------------------------------------------------ questions

	/** The form may be used: arms are on and its section doesn't say {@code enabled: false}. */
	public boolean formEnabled(String form) {
		return enabled && !disabledForms.contains(form);
	}

	/** A form's merged section as plain values (empty when the file has none); what an ArmSpecialModule's load gets. */
	public Map<String, Object> form(String form) {
		return rawForms.getOrDefault(form, Map.of());
	}

	/** An accessory's merged section as plain values (empty when the file has none); what an ArmAccessoryModule's load gets. */
	public Map<String, Object> accessory(String id) {
		return rawAccessories.getOrDefault(id, Map.of());
	}

	/**
	 * arms.forms.&lt;form&gt;.extra-reach: blocks of reach the held arm adds (entity_interaction_range, main hand), by level;
	 * {@link ArmTiers#extraReach} when not set, 0 to turn it off (for anticheats that flag reach past vanilla's).
	 */
	public double extraReach(String form, int level) {
		double fallback = ArmTiers.extraReach(form, level);
		Levels l = positive(levels(form(form).get("extra-reach"), new Levels(fallback, fallback, fallback)));
		return Math.min(l.at(ArmTiers.clampTier(level)), 4.0);
	}

	/** The Fortune level a form's item carries at a level: arms.forms.<form>.fortune, the level by default, 0 on forms without Fortune. */
	public int fortune(String form, int level) {
		if (!ArmTiers.hasFortune(form)) return 0;
		if (form.equals("pickaxe")) return Math.clamp(pickaxe.fortune().intAt(level), 0, 255);
		return Math.clamp(whole(levels(form(form).get("fortune"), DEFAULT_FORTUNE)).intAt(level), 0, 255);
	}

	/** The Efficiency level a form's item carries at a level: arms.forms.<form>.efficiency, the level by default (0: none), 0 on forms without Efficiency. */
	public int efficiency(String form, int level) {
		if (!ArmTiers.hasEfficiency(form)) return 0;
		return Math.clamp(whole(levels(form(form).get("efficiency"), DEFAULT_EFFICIENCY)).intAt(level), 0, 255);
	}

	// The raw sections are left out (see the class comment).
	@Override
	public boolean equals(Object o) {
		return o instanceof ArmConfig c && typed().equals(c.typed());
	}

	@Override
	public int hashCode() {
		return typed().hashCode();
	}

	private List<Object> typed() {
		return List.of(enabled, report, classicLevel, reformLockoutTicks, recallSeconds, minToFormMilli, stashMaxBytes, inventorySafety, commandGuard,
				blockSpecials, players, playerFloor, lesserRate, passiveRate, tierFollowsBiomass, disabledForms, costs, katana, pickaxe);
	}

	// ------------------------------------------------------------------ plain-value helpers

	/**
	 * A copy with string keys, nested maps copied, and anything that isn't a plain value (a configuration section object
	 * that came along with a flattened view) dropped. Dotted keys ({@code forms.katana.enabled}, as a section's deep
	 * view gives them) are nested.
	 */
	static Map<String, Object> plain(@Nullable Map<?, ?> in) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (in == null) return out;
		for (Map.Entry<?, ?> e : in.entrySet()) {
			if (e.getKey() == null) continue;
			Object v = value(e.getValue());
			if (v == null) continue;
			String[] path = String.valueOf(e.getKey()).split("\\.");
			Map<String, Object> at = out;
			for (int i = 0; i < path.length - 1; i++) {
				Object next = at.get(path[i]);
				if (!(next instanceof Map<?, ?>)) {
					next = new LinkedHashMap<String, Object>();
					at.put(path[i], next);
				}
				@SuppressWarnings("unchecked")
				Map<String, Object> m = (Map<String, Object>) next;
				at = m;
			}
			String last = path[path.length - 1];
			if (v instanceof Map<?, ?> vm && at.get(last) instanceof Map<?, ?> existing) {
				@SuppressWarnings("unchecked")
				Map<String, Object> merged = merge((Map<String, Object>) existing, castMap(vm));
				at.put(last, merged);
			} else {
				at.put(last, v);
			}
		}
		return out;
	}

	private static @Nullable Object value(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) return plain(m);
		if (v instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object o : list) {
				Object p = value(o);
				if (p != null) out.add(p);
			}
			return out;
		}
		if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) return v;
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> m) {
		return (Map<String, Object>) m;
	}

	/** {@code over} laid on {@code under}: nested maps merge key by key, anything else in {@code over} replaces. */
	static Map<String, Object> merge(Map<String, Object> under, Map<String, Object> over) {
		Map<String, Object> out = new LinkedHashMap<>(under);
		for (Map.Entry<String, Object> e : over.entrySet()) {
			Object u = out.get(e.getKey());
			if (u instanceof Map<?, ?> um && e.getValue() instanceof Map<?, ?> om) out.put(e.getKey(), merge(castMap(um), castMap(om)));
			else out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	private static Map<String, Object> section(Map<String, Object> m, String key) {
		return m.get(key) instanceof Map<?, ?> s ? castMap(s) : Map.of();
	}

	/** An unmodifiable deep copy for the raw sections, so a module can't change what another one reads. */
	private static Map<String, Object> frozen(Map<String, Object> m) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), frozenValue(e.getValue()));
		return Collections.unmodifiableMap(out);
	}

	private static Object frozenValue(Object v) {
		if (v instanceof Map<?, ?> m) return frozen(castMap(m));
		if (v instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object o : list) out.add(frozenValue(o));
			return Collections.unmodifiableList(out);
		}
		return v;
	}

	private static boolean bool(@Nullable Object v, boolean fallback) {
		if (v instanceof Boolean b) return b;
		if (v instanceof String s) {
			return switch (s.trim().toLowerCase(Locale.ROOT)) {
				case "true", "on", "yes" -> true;
				case "false", "off", "no" -> false;
				default -> fallback;
			};
		}
		return fallback;
	}

	private static double number(@Nullable Object v, double fallback) {
		if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
		if (v instanceof String s) {
			try {
				double d = Double.parseDouble(s.trim());
				return Double.isFinite(d) ? d : fallback;
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		return fallback;
	}

	private static long integer(@Nullable Object v, long fallback) {
		return Math.round(number(v, fallback));
	}

	private static int nonNegativeInt(@Nullable Object v, int fallback) {
		return (int) Math.clamp(integer(v, fallback), 0, Integer.MAX_VALUE);
	}

	private static long nonNegative(long v) {
		return Math.max(0, v);
	}

	private static long milli(@Nullable Object v, double fallback) {
		return nonNegative(ArmLedger.toMilli(number(v, fallback)));
	}

	private static String string(@Nullable Object v, String fallback) {
		return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
	}

	/**
	 * Three levels from a list (fewer entries repeat the last; a bad entry takes that level's default) or one number for
	 * all three.
	 */
	private static Levels levels(@Nullable Object v, Levels fallback) {
		if (v instanceof Number || v instanceof String) {
			double d = number(v, Double.NaN);
			return Double.isNaN(d) ? fallback : new Levels(d, d, d);
		}
		if (!(v instanceof List<?> list) || list.isEmpty()) return fallback;
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) {
			Object o = list.get(Math.min(i, list.size() - 1));
			out[i] = number(o, fallback.at(i + 1));
		}
		return new Levels(out[0], out[1], out[2]);
	}

	private static Levels positive(Levels l) {
		return new Levels(Math.max(0, l.l1()), Math.max(0, l.l2()), Math.max(0, l.l3()));
	}

	private static Levels whole(Levels l) {
		return new Levels(Math.max(0, Math.round(l.l1())), Math.max(0, Math.round(l.l2())), Math.max(0, Math.round(l.l3())));
	}

	/** Seconds to ticks. */
	private static Levels ticks(Levels seconds) {
		Levels s = positive(seconds);
		return new Levels(Math.round(s.l1() * 20), Math.round(s.l2() * 20), Math.round(s.l3() * 20));
	}

	private static Set<String> commandSet(@Nullable Object v, List<String> fallback) {
		List<?> list = v instanceof List<?> l ? l : v instanceof String s ? List.of(s.split("[,\\s]+")) : fallback;
		Set<String> out = new LinkedHashSet<>();
		for (Object o : list) {
			if (o == null) continue;
			String n = CommandGuard.normalise(o.toString());
			if (!n.isEmpty()) out.add(n);
		}
		return out;
	}

	@Override
	public String toString() {
		return "ArmConfig" + typed() + " forms " + rawForms.keySet() + ", lantern " + rawLantern.keySet() + ", accessories " + rawAccessories.keySet();
	}
}
