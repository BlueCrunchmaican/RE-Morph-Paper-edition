package dev.morphreborn.paper.arm.api;

import dev.morphreborn.paper.tips.Lines;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * An accessory (2026-11-16: several may be on at once): the lantern, and the shield, spyglass and flint and
 * steel. Not an item and never in a slot, and never saved: it is off after a rejoin (what is saved is the player's
 * choice of its {@link #mode}). Each is a class with a public no-argument constructor named in
 * {@code ArmModules.ACCESSORIES}; one missing from a build is simply not offered, hidden from the tree, the chest menu,
 * the wheel and S_ARSENAL, as a tool form whose special is missing is.
 * <p>
 * The manager keeps who has what on (in the order they were switched on), the level each was switched on at, whether an
 * admin made it free, and the drain; this class is only the accessory's own behaviour.
 */
public interface ArmAccessoryModule {
	/** The accessory's id ("lantern"): an {@code ArmForm} of kind ACCESSORY, and what the player types and the protocol carries. */
	String id();

	void init(ArmContext ctx);

	/**
	 * Its settings as plain values, the bundled defaults merged in: abilities.yml {@code arms.lantern} for the lantern,
	 * {@code arms.accessories.<id>} for the others. Called at enable and on every reload.
	 */
	void load(Map<String, Object> section);

	default @Nullable Listener listener() {
		return null;
	}

	/** Whether it is on for this player (the manager asks every tick to notice one that put itself out). */
	boolean isOn(Player p);

	/** Ticks since it was switched on, 0 when it isn't. */
	int onTicks(Player p);

	/** Switch it on at a level (1 to 3): null when it is now on, otherwise why not. */
	@Nullable String switchOn(Player p, int level);

	/** Switch it off (ArmProtocol.WHY_*), undoing everything it did (light cells, a raised shield, a zoom). */
	void switchOff(Player p, int why);

	/** Every player's, at a reload, a mode switch or shutdown. */
	void switchOffAll();

	/** Once a tick, from the manager. */
	void tick();

	/** The level's numbers in words, for the upgrade tree, as typed pieces: what stays with Tooltips off is only the rates (Lines). */
	Lines describeLines(int level);

	/** {@link #describeLines} in full, as one line (Tooltips on). */
	default String describe(int level) {
		return describeLines(level).text(true);
	}

	/**
	 * What S_ARM carries for it besides on and its level: accessory-specific, 0 when it isn't on (the shield raised 0 or 1, the
	 * spyglass zooming 0 or 1; the lantern's brightness).
	 */
	default int arg(Player p) {
		return 0;
	}

	/**
	 * How many values its mode has at a level, 0 for an accessory without one. The mode is the player's lasting choice about it,
	 * 1 up to this (the lantern's brightness, 1 to 5), kept on the player between sessions.
	 */
	default int maxMode(int level) {
		return 0;
	}

	/** The mode a player who never chose gets. */
	default int defaultMode() {
		return 1;
	}

	/** The player's mode, already kept to 1 .. {@link #maxMode}: applied now if it is on, and kept for the next time it is. */
	default void mode(Player p, int mode) {}

	/**
	 * The player's client holds this accessory's key down or lets it go (C_ARM ACTION_ACCESSORY_HOLD: the spyglass's zoom key, and the shield's use key
	 * on a click the main hand did not use), called only while the accessory is on. A client repeats a held key about every 10 seconds, so an accessory times a hold out by itself.
	 */
	default void hold(Player p, boolean held) {}
}
