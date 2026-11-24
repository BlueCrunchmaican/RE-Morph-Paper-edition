package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.morphreborn.paper.arm.special.WhipLash;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** The Lash's end point goes on the wire as the game's BlockPos.asLong, which the client unpacks; Paper's own block key differs. */
class WhipFxPositionTest {
	/** BlockPos.asLong as the game writes it: 26 bits of x, 26 of z, 12 of y. */
	private static long blockPos(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	@Test
	void theEndPointIsPackedAsBlockPosDoes() {
		int[][] cases = {{0, 0, 0}, {10, 64, -20}, {-1, -60, -1}, {-30000000, 319, 29999999}, {123456, 5, -654321}};
		for (int[] c : cases) {
			Vector end = new Vector(c[0] + 0.4, c[1] + 0.7, c[2] + 0.1);
			assertEquals(blockPos(c[0], c[1], c[2]), WhipLash.endBlockKey(end), "at " + c[0] + " " + c[1] + " " + c[2]);
		}
	}

	@Test
	void paperBlockKeyIsNotTheWireFormat() {
		// the reason the Lash must not use Block.getBlockKey: it packs the same block to a different number
		assertNotEquals(blockPos(10, 64, -20), Block.getBlockKey(10, 64, -20));
	}
}
