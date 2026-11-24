package dev.morphreborn.client.arm;

import net.minecraft.client.Minecraft;

/**
 * One part of the client's arm forms that loads on its own (the look, the wheel and HUD, the world effects, the lantern,
 * the developer dump), listed by class name in {@link ArmClientModules}. A module whose class isn't in the build is
 * simply skipped, so each part can land, or ship as a beta, without the others.
 * <p>
 * A module needs a public no-argument constructor.
 */
public interface ArmClientModule {
	/** Once, at client start (after the main mod has set up its config, keys and network): register callbacks and hooks. */
	void init();

	/**
	 * Every game tick while a world is loaded and the game isn't paused, right after {@link Arms#TICK} has advanced: the
	 * same ticks the arm clock counts, so {@code mc.level} and {@code mc.player} are there.
	 */
	default void tick(Minecraft mc) {}

	/** On joining and leaving a server: drop everything kept about the last one. */
	default void reset() {}
}
