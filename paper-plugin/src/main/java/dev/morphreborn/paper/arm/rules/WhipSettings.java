package dev.morphreborn.paper.arm.rules;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * abilities.yml arms.forms.whip as typed values (spec section 9): the grapple, the yank and the F moves. Read from the
 * form's own section, which the manager hands the module with the bundled defaults already merged in; every value that
 * is missing or unreadable takes the default, which is exactly what the bundled file says (tested), and numbers are
 * clamped rather than rejected. Distances are in blocks, damage in health points, cooldowns and durations in seconds in
 * the file and ticks here. Pure, so it is unit tested without a server.
 *
 * @param name the F special's name for messages and the wheel ("Whip moves")
 * @param grappleRange how far the hook may be for a reel-in to pull you, by level
 * @param speedPerBlock the pull's speed per block of distance, before the clamps
 * @param minSpeed the slowest pull
 * @param maxSpeed the fastest pull, by level
 * @param lift extra upward speed when the hook is above your eyes
 * @param yankFactor how many times vanilla's pull a creature on the line gets, by level
 * @param maxMass creatures at or above this mass (kg, SurvivalMode.massOf) pull you instead of being pulled
 * @param fishBiomassChance the chance (0 to 1) that a fish caught with the whip gives biomass
 * @param fishBiomassMilli the biomass a lucky catch gives, in milli
 * @param moves the F moves' numbers
 * @param snarePlayers the Snare may take hold of players (also needs arms.players)
 * @param disarmPlayers the Disarm may rip a held item from a snared player (their item drops at their own feet)
 */
public record WhipSettings(String name, ArmConfig.Levels grappleRange, double speedPerBlock, double minSpeed, ArmConfig.Levels maxSpeed, double lift,
		ArmConfig.Levels yankFactor, double maxMass, double fishBiomassChance, long fishBiomassMilli, Moves moves, boolean snarePlayers, boolean disarmPlayers) {
	/**
	 * The F moves (2026-11-16). All four are there from level 1; the level raises the numbers. The cooldowns
	 * are in ticks.
	 *
	 * @param reach how far F looks for a creature to Snare or Disarm, by level
	 * @param sharedCooldownTicks the short cooldown every move starts on the whole form (the manager keeps one per form); each move's own is below
	 * @param snareSeconds how long the Snare holds, by level
	 * @param snareCooldown the Snare's own cooldown, by level
	 * @param disarmCooldown the Disarm's own cooldown, by level
	 * @param crackRange how far the Crack's cone reaches, by level
	 * @param crackHalfAngle the cone's half angle in degrees
	 * @param crackDamage the Crack's damage to each creature, by level
	 * @param crackKnockback how hard the Crack pushes (blocks a tick), by level
	 * @param crackFleeSeconds how long mobs the Crack hit run from you
	 * @param crackCooldown the Crack's own cooldown, by level
	 * @param whirlRadius how far round you the Whirl reaches, by level
	 * @param whirlDamage the Whirl's damage to each creature, by level
	 * @param whirlKnockback how hard the Whirl pushes (blocks a tick), by level
	 * @param whirlCooldown the Whirl's own cooldown, by level
	 */
	public record Moves(ArmConfig.Levels reach, int sharedCooldownTicks, ArmConfig.Levels snareSeconds, ArmConfig.Levels snareCooldown,
			ArmConfig.Levels disarmCooldown, ArmConfig.Levels crackRange, double crackHalfAngle, ArmConfig.Levels crackDamage, ArmConfig.Levels crackKnockback,
			double crackFleeSeconds, ArmConfig.Levels crackCooldown, ArmConfig.Levels whirlRadius, ArmConfig.Levels whirlDamage, ArmConfig.Levels whirlKnockback,
			ArmConfig.Levels whirlCooldown) {
		/** How long the Snare holds, in ticks. */
		public int snareTicks(int level) {
			return Math.max(1, (int) Math.round(snareSeconds.at(level) * 20));
		}

		/** A move's own cooldown in ticks (before the gameplay mode's cooldown factor). */
		public int cooldownTicks(WhipMoves.Move move, int level) {
			ArmConfig.Levels l = switch (move) {
				case SNARE -> snareCooldown;
				case DISARM -> disarmCooldown;
				case CRACK -> crackCooldown;
				case WHIRL -> whirlCooldown;
			};
			return Math.max(0, l.intAt(level));
		}

		/** How long the Crack makes mobs flee, in ticks. */
		public int fleeTicks() {
			return Math.max(0, (int) Math.round(crackFleeSeconds * 20));
		}
	}

	/** The defaults: the bundled abilities.yml's whip section. */
	public static final WhipSettings DEFAULTS = new WhipSettings("Whip moves", new ArmConfig.Levels(12, 16, 20), 0.18, 0.6, new ArmConfig.Levels(1.8, 2.0, 2.2), 0.25,
			new ArmConfig.Levels(2.0, 2.4, 2.8), 1200, 0.2, 500,
			new Moves(new ArmConfig.Levels(8, 10, 12), 8, new ArmConfig.Levels(2, 2.5, 3), new ArmConfig.Levels(120, 100, 80), new ArmConfig.Levels(80, 70, 60),
					new ArmConfig.Levels(6, 7, 8), 35, new ArmConfig.Levels(2, 2.5, 3), new ArmConfig.Levels(0.6, 0.73, 0.87), 3, new ArmConfig.Levels(100, 90, 80),
					new ArmConfig.Levels(4, 4.5, 5), new ArmConfig.Levels(4, 5, 6), new ArmConfig.Levels(0.6, 0.73, 0.87), new ArmConfig.Levels(200, 180, 160)),
			false, false);

	/** The form's section as plain values (nested maps, lists, numbers) to settings; null or empty gives the defaults. */
	public static WhipSettings parse(@Nullable Map<String, Object> form) {
		if (form == null || form.isEmpty()) return DEFAULTS;
		Map<String, Object> grapple = section(form, "grapple"), yank = section(form, "yank"), moves = section(form, "moves");
		Map<String, Object> snare = section(moves, "snare"), disarm = section(moves, "disarm"), crack = section(moves, "crack"), whirl = section(moves, "whirl");
		WhipSettings d = DEFAULTS;
		Moves dm = d.moves;
		Moves m = new Moves(levels(moves.get("reach"), dm.reach, 0, 64), (int) Math.round(number(moves.get("shared-cooldown"), dm.sharedCooldownTicks / 20.0, 0, 60) * 20),
				levels(snare.get("seconds"), dm.snareSeconds, 0.1, 60), cooldown(snare.get("cooldown"), dm.snareCooldown),
				cooldown(disarm.get("cooldown"), dm.disarmCooldown), levels(crack.get("range"), dm.crackRange, 0, 64),
				number(crack.get("half-angle"), dm.crackHalfAngle, 1, 90), levels(crack.get("damage"), dm.crackDamage, 0, 1000),
				levels(crack.get("knockback"), dm.crackKnockback, 0, WhipMoves.MAX_PUSH), number(crack.get("flee-seconds"), dm.crackFleeSeconds, 0, 60),
				cooldown(crack.get("cooldown"), dm.crackCooldown), levels(whirl.get("radius"), dm.whirlRadius, 0, 32),
				levels(whirl.get("damage"), dm.whirlDamage, 0, 1000), levels(whirl.get("knockback"), dm.whirlKnockback, 0, WhipMoves.MAX_PUSH),
				cooldown(whirl.get("cooldown"), dm.whirlCooldown));
		return new WhipSettings(string(moves.get("name"), d.name), levels(grapple.get("range"), d.grappleRange, 0, 1000),
				number(grapple.get("speed-per-block"), d.speedPerBlock, 0, 10), number(grapple.get("min-speed"), d.minSpeed, 0, 10),
				levels(grapple.get("max-speed"), d.maxSpeed, 0, 10), number(grapple.get("lift"), d.lift, 0, 5),
				levels(yank.get("factor"), d.yankFactor, 1, 10), number(yank.get("max-mass"), d.maxMass, 0, 1_000_000),
				number(form.get("fish-biomass-chance"), d.fishBiomassChance, 0, 1), ArmLedger.toMilli(number(form.get("fish-biomass"), d.fishBiomassMilli / 1000.0, 0, 1000)),
				m, bool(form.get("snare-players"), d.snarePlayers), bool(form.get("disarm-players"), d.disarmPlayers));
	}

	/**
	 * The biomass (in milli) a caught fish gives for a roll in [0, 1): the set amount when the roll is under the chance,
	 * else 0. A chance of 0 or an amount of 0 gives nothing, whatever the roll.
	 */
	public long fishGainMilli(double roll) {
		return fishBiomassChance > 0 && roll < fishBiomassChance ? fishBiomassMilli : 0;
	}

	/** How far the hook may be at a level. */
	public double range(int level) {
		return grappleRange.at(level);
	}

	/** The fastest pull at a level (never under the slowest). */
	public double speedCap(int level) {
		return Math.max(minSpeed, maxSpeed.at(level));
	}

	/** The yank's multiple of vanilla's pull at a level (never under 1). */
	public double factor(int level) {
		return Math.max(1, yankFactor.at(level));
	}

	// ------------------------------------------------------------------ plain-value helpers

	private static Map<String, Object> section(Map<String, Object> m, String key) {
		if (!(m.get(key) instanceof Map<?, ?> s)) return Map.of();
		@SuppressWarnings("unchecked")
		Map<String, Object> out = (Map<String, Object>) s;
		return out;
	}

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

	/** A cooldown in seconds in the file, ticks here (the fallback is already in ticks). */
	private static ArmConfig.Levels cooldown(@Nullable Object v, ArmConfig.Levels fallbackTicks) {
		return seconds(levels(v, new ArmConfig.Levels(fallbackTicks.l1() / 20, fallbackTicks.l2() / 20, fallbackTicks.l3() / 20), 0, 3600));
	}

	/** Seconds to whole ticks. */
	private static ArmConfig.Levels seconds(ArmConfig.Levels s) {
		return new ArmConfig.Levels(Math.round(s.l1() * 20), Math.round(s.l2() * 20), Math.round(s.l3() * 20));
	}
}
