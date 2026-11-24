package dev.morphreborn.paper.arm.rules;

import org.jspecify.annotations.Nullable;

/**
 * A read-only look at the blocks around a special, so the planners that walk them (VeinPlanner, and the phase 2 fell,
 * burrow and reap planners) stay pure: the server side answers from the world, tests from a map.
 */
public interface GridView {
	/**
	 * The group the block at x, y, z belongs to ("diamond": diamond ore and deepslate diamond ore), or null when it is in
	 * none (stone, air, outside the world). A plan never crosses from one group to another.
	 */
	@Nullable String group(int x, int y, int z);

	/** Whether the arm may break it: the right tool for it, hardness 0 or more, not a game-master block. */
	boolean breakable(int x, int y, int z);
}
