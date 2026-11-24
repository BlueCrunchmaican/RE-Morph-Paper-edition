package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;

/**
 * The world effect for some S_ARM_FX kinds: the swipe of a special, the tendrils latched on to creatures, a vein being
 * ripped out of the rock. {@link ArmFx} loads each handler by class name, so the forms' effects can arrive in any
 * order (phase 2 adds its own handlers as new files), and hands every effect of a kind the handler declares to it.
 * <p>
 * Everything happens on the client thread, which is also the render thread: effects arrive with the packets, ticks
 * run while a world is loaded and the game isn't paused, and frames are collected after the world's entity submits.
 * A handler needs a public no-argument constructor.
 */
public interface FxHandler {
	/** The kinds ({@code ArmProtocol.FX_*}) this handler draws. */
	int[] kinds();

	/** An effect of one of those kinds arrived. Ages count from its {@code receivedTick} on the arm clock. */
	void start(ArmFxEvent e);

	/** One game tick (the arm clock has just advanced): time out stateful effects and drop finished ones. */
	void tick();

	/**
	 * This frame's geometry: submit whatever is showing. Anything a geometry lambda captures must be worked out here and
	 * never changed afterwards, because the lambdas run later, when the frame is dispatched.
	 */
	void collect(ArmFxFrame f);

	/** Forget everything: joining or leaving a server, or a world change (entity ids mean nothing in the next one). */
	void clear();
}
