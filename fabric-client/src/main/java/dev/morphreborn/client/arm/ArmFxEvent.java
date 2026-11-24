package dev.morphreborn.client.arm;

import dev.morphreborn.client.net.Wire;

/**
 * One arm effect from the server (S_ARM_FX): a hit, a broken block, a special's swing, tendrils latched on, a pulse of
 * health running home, a vein being ripped out. {@link Arms} hands each one to the listeners (the in-hand reactions and
 * the world effects) as it arrives; nothing keeps it after that, so a resent arm state never replays an effect.
 * <p>
 * {@code kind} is one of {@code ArmProtocol.FX_*}, and the kind says which fields mean something: {@code ids} are entity
 * ids, {@code positions} are {@code BlockPos.asLong} values, {@code ticks} and {@code arg} are per kind (a travel time, a
 * rate, an arc). The stateful kinds (DRAIN, BURROW, HARPOON) are resent while they last and end with an empty list;
 * {@code receivedTick} (on {@link Arms#TICK}) tells their handler when it last heard of one.
 */
public record ArmFxEvent(int origin, int kind, int level, int ticks, int seed, int[] ids, long[] positions, int arg, long receivedTick) {
	/**
	 * Reads S_ARM_FX after its id: varInt origin entity id, varInt kind, varInt level, varInt ticks, varInt seed,
	 * varInt n and n entity ids, varInt m and m block positions (8-byte longs), varInt arg. Anything after that is a
	 * newer server's and is left unread.
	 */
	public static ArmFxEvent read(Wire.In in, long now) {
		int origin = in.varInt();
		int kind = in.varInt();
		int level = in.varInt();
		int ticks = in.varInt();
		int seed = in.varInt();
		int n = in.count();
		int[] ids = new int[n];
		for (int i = 0; i < n; i++) ids[i] = in.varInt();
		int m = in.count();
		long[] positions = new long[m];
		for (int i = 0; i < m; i++) positions[i] = in.longValue();
		int arg = in.varInt();
		return new ArmFxEvent(origin, kind, level, ticks, seed, ids, positions, arg, now);
	}

	/** Ticks since this arrived, with the frame's partial tick. */
	public float age(float pt) {
		return Arms.age(receivedTick, pt);
	}

	/**
	 * Whether this ends a stateful effect: Draining Cut's tendrils and the harpoon end with an empty list, Burrow with no
	 * window left. Always false for the one-off kinds.
	 */
	public boolean ends() {
		return switch (kind) {
			case ArmProtocol.FX_DRAIN -> ids.length == 0;
			case ArmProtocol.FX_HARPOON -> ids.length == 0 && positions.length == 0;
			case ArmProtocol.FX_BURROW -> ticks <= 0;
			default -> false;
		};
	}
}
