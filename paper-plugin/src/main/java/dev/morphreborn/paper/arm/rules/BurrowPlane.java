package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Burrow's reach (spec 3.9): while the window is open, every block the shovel arm digs takes the eight around it in
 * the plane it was dug across, the one the player's mouse was on. Dig the top of a dirt block and the ring lies flat
 * about it, a 3 by 3 hole; dig a wall's face and it is the wall's 3 by 3. A neighbour only goes when the arm digs it
 * as a shovel does and it is no harder than the block dug, within half a point: sand, dirt, gravel and clay, never the
 * stone beside them, and never a chest or a furnace.
 * <p>
 * Pure: the module reads the world and asks these questions, so they are unit tested.
 */
public final class BurrowPlane {
	/** A neighbour may be this much harder than the block dug (clay, 0.6, goes with dirt, 0.5; stone, 1.5, doesn't). */
	public static final double HARDNESS_SLACK = 0.5;

	private BurrowPlane() {}

	/** The axis a face points along. The ring lies in the plane across it. */
	public enum Axis {
		X, Y, Z;

		/** The axis of a face's normal (its block offset: east is +1, 0, 0), null when it is not one of the six. */
		public static @Nullable Axis ofNormal(int nx, int ny, int nz) {
			int count = (nx != 0 ? 1 : 0) + (ny != 0 ? 1 : 0) + (nz != 0 ? 1 : 0);
			if (count != 1) return null;
			return nx != 0 ? X : ny != 0 ? Y : Z;
		}
	}

	/**
	 * The eight blocks around {@code (x, y, z)} in the plane across {@code normal}, in a ring: the corner, the edge after
	 * it, and so on round, each one touching the one before. A face on the top or bottom gives the flat 3 by 3, a face
	 * to the north or south the upright one across it, a face to the east or west the upright one along it.
	 */
	public static List<Pos> ring(Axis normal, int x, int y, int z) {
		// the plane's two directions, and the ring's offsets in them
		int[][] offsets = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
		List<Pos> out = new ArrayList<>(8);
		for (int[] o : offsets) {
			out.add(switch (normal) {
				case Y -> new Pos(x + o[0], y, z + o[1]);
				case X -> new Pos(x, y + o[0], z + o[1]);
				case Z -> new Pos(x + o[0], y + o[1], z);
			});
		}
		return out;
	}

	/** The ring for a face given by its block offset, empty when it is not one of the six faces. */
	public static List<Pos> ring(int nx, int ny, int nz, int x, int y, int z) {
		Axis a = Axis.ofNormal(nx, ny, nz);
		return a == null ? List.of() : ring(a, x, y, z);
	}

	/**
	 * Whether the block dug takes its neighbours at all: the arm digs it as a shovel does (the right tool, so a
	 * tunnel of stone dug with a spade gives no 3 by 3 of stone) and it can be broken.
	 */
	public static boolean startsBurrow(boolean preferredTool, double hardness) {
		return preferredTool && hardness >= 0;
	}

	/**
	 * Whether a neighbour goes with the block dug: not air, not a tile entity (chests, furnaces, signs, spawners), can be
	 * broken, the arm digs it as a shovel does, and it is no harder than the block dug plus {@link #HARDNESS_SLACK}.
	 *
	 * @param centreHardness the dug block's hardness
	 * @param hardness the neighbour's, negative for blocks that can't be broken
	 */
	public static boolean accepts(double centreHardness, double hardness, boolean preferredTool, boolean tileEntity, boolean air) {
		if (air || tileEntity || !preferredTool || hardness < 0) return false;
		return hardness <= centreHardness + HARDNESS_SLACK;
	}
}
