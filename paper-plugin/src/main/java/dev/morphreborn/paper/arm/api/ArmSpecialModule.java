package dev.morphreborn.paper.arm.api;

import dev.morphreborn.paper.tips.Lines;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * One form's special (F with the arm formed): Draining Cut for the katana, Vein Rip for the pickaxe, and so on. Loaded
 * by class name (ArmModules.SPECIALS) with a public no-argument constructor; a form whose module is missing from the
 * build counts as not built and is hidden everywhere.
 * <p>
 * The manager's gate runs first (session FORMED, arm in hand, not already running, cooldown ready, block rules), then
 * {@link #fire}. A module finds its targets before paying, so a swing at nothing costs nothing; every scheduled step
 * afterwards checks {@link ArmContext#stillArmed} and stops quietly when the arm is gone.
 */
public interface ArmSpecialModule {
	/** The form id it belongs to ("katana"). */
	String form();

	void init(ArmContext ctx);

	/** abilities.yml arms.forms.<form> as plain values, the bundled defaults merged in. Called at enable and on every reload. */
	void load(Map<String, Object> formSection);

	/** Events of its own (registered by the manager), if any. */
	default @Nullable Listener listener() {
		return null;
	}

	/** It breaks blocks, so arms.block-specials and adventure mode apply. */
	boolean breaksBlocks();

	/**
	 * Sneak+F is a move of its own that costs nothing and starts no cooldown (the bow's arrow-tip cycling), so the manager's cooldown
	 * gate lets it through while the form's special is cooling down. Every other gate still applies.
	 */
	default boolean sneakBypassesCooldown() {
		return false;
	}

	SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge);

	/** Once a tick, from the manager. */
	default void tick() {}

	/** A special of this player's is still under way. */
	default boolean running(Player p) {
		return false;
	}

	/** Stop this player's running special now (a retract, a reshape, leaving). */
	default void abort(Player p) {}

	/** Stop every running special (disable, reload, mode switch). */
	default void abortAll() {}

	String specialName(int level);

	long costMilli(int level);

	int cooldownTicks(int level);

	/** The level's numbers in words, for the upgrade tree, as typed pieces: what stays with Tooltips off is only the rates (Lines). */
	Lines describeLines(int level);

	/** {@link #describeLines} in full, as one line (Tooltips on). */
	default String describe(int level) {
		return describeLines(level).text(true);
	}

	/**
	 * The F line of the arm item's lore, after "F: ", when one name and one cost can't say what F does (the whip's four
	 * moves: "Snare 6 · Disarm 4 · Crack 6 · Whirl (sneak) 10 biomass"). Null (the default) keeps the usual "F: name · cost
	 * biomass". {@code survival}: the bar shows biomass, so costs are worth showing; {@code charge} turns a cost in milli
	 * into what is charged (0 when arm costs are off).
	 */
	default @Nullable String loreLine(int level, boolean survival, java.util.function.LongUnaryOperator charge) {
		Lines lines = loreLines(level, survival, charge);
		return lines == null ? null : lines.text(true);
	}

	/** {@link #loreLine} as typed pieces, for the Tooltips setting (the cost stays, the key hints are explanation); null as it is. */
	default @Nullable Lines loreLines(int level, boolean survival, java.util.function.LongUnaryOperator charge) {
		return null;
	}
}
