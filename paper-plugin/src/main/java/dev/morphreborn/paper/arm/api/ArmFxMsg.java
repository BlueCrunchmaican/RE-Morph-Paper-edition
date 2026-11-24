package dev.morphreborn.paper.arm.api;

import java.util.Arrays;

/**
 * S_ARM_FX: one effect of an arm's special for clients that draw arms (spec section 4: ArmProtocol.FX_* says which
 * fields each kind uses).
 *
 * @param origin the arm owner's entity id
 * @param kind ArmProtocol.FX_*
 * @param level the special's level
 * @param ticks a length in ticks (a pulse's travel, a vein's total, a flare's life)
 * @param seed for the client's randomness, so every screen draws the same tendrils
 * @param ids entity ids (targets in hit order, linked creatures, an arrow)
 * @param positions blocks as BlockPos.asLong (a vein in break order, a flare's cell)
 * @param arg one more number (the arc in degrees, the ores per tick times 10)
 */
public record ArmFxMsg(int origin, int kind, int level, int ticks, int seed, int[] ids, long[] positions, int arg) {
	public ArmFxMsg {
		ids = ids == null ? new int[0] : ids.clone();
		positions = positions == null ? new long[0] : positions.clone();
	}

	// A record compares arrays by identity; two messages with the same contents are the same message.
	@Override
	public boolean equals(Object o) {
		return o instanceof ArmFxMsg m && origin == m.origin && kind == m.kind && level == m.level && ticks == m.ticks && seed == m.seed && arg == m.arg
				&& Arrays.equals(ids, m.ids) && Arrays.equals(positions, m.positions);
	}

	@Override
	public int hashCode() {
		return ((((origin * 31 + kind) * 31 + level) * 31 + ticks) * 31 + seed) * 31 + arg + Arrays.hashCode(ids) * 7 + Arrays.hashCode(positions);
	}

	@Override
	public String toString() {
		return "ArmFxMsg[origin=" + origin + ", kind=" + kind + ", level=" + level + ", ticks=" + ticks + ", seed=" + seed + ", ids=" + Arrays.toString(ids)
				+ ", positions=" + Arrays.toString(positions) + ", arg=" + arg + "]";
	}
}
