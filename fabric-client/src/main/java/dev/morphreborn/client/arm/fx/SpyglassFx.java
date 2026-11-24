package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.Arms;
import net.minecraft.client.Minecraft;

/**
 * The spyglass's creature marks (S_ARM_FX kind FX_ACCESSORY, the spyglass's place; 2026-11-16): while its owner zooms, the
 * server marks the creatures they look at (they glow for them alone, which is the server's business: per-viewer metadata) and tells
 * them which, so the HUD can show each one's distance ({@code SpyglassHud}). The wire contract is the server's
 * ({@code SpyglassAccessory}, docs/PROTOCOL.md): {@code arg} is {@link ArmProtocol#accessoryArg} of the spyglass's place and a move;
 * {@code origin} is the marker (the event is sent to the marker only, so one naming anyone else is ignored, and {@code ids} never carry
 * the owner).
 * <ul>
 *   <li>{@link #MARK}: {@code ids} = [the creature] (one per message), {@code ticks} = how long the mark has left without another
 *       message (the server's clock: it is told again every second while the creature is looked at, and when a look moves on), {@code level}
 *       the spyglass's level. The mark is held until then.</li>
 *   <li>{@link #UNMARK}: {@code ids} = the creatures no longer marked, or none for every mark (the zoom ended, the accessory went off).</li>
 * </ul>
 * Several marks are held at once (the server's cap is the level's: 1, 2, 3 by default, 16 at the most, and it sends an UNMARK for the one
 * it pushes out, so this side only needs a bound; past {@link #MAX} the oldest goes). Marks are kept by the client's own tick
 * ({@link #expire}), so a mark whose last message ran out is gone even if the server never said so. {@link AccessoryFx} routes the
 * spyglass's place here; this draws nothing in the world.
 */
public final class SpyglassFx {
	/** FX_ACCESSORY moves of the spyglass (server: SpyglassAccessory.FX_MARK, FX_UNMARK). */
	public static final int MARK = 1, UNMARK = 2;
	/** The most marks held (the server's most: its marks setting is kept to 16). */
	public static final int MAX = 16;
	/** What a MARK that names no time (an older or odd server) lasts, in ticks: the server's own default life. */
	static final int DEFAULT_TICKS = 100;

	private static final int[] IDS = new int[MAX];
	/** The client tick each mark ends at, and the tick it was last told. */
	private static final long[] UNTIL = new long[MAX];
	private static int count;

	private SpyglassFx() {}

	/** Whether {@code arg} is one of the spyglass's moves. */
	static boolean handles(int arg) {
		if (ArmProtocol.accessoryPlace(arg) != ArmProtocol.ACCESSORY_PLACE_SPYGLASS) return false;
		int move = ArmProtocol.accessoryMove(arg);
		return move == MARK || move == UNMARK;
	}

	static void start(ArmFxEvent e) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		apply(mc.player.getId(), e);
	}

	/** One event from the server, for the marker whose entity id is {@code ownId}; the pure part of {@link #start}. */
	static void apply(int ownId, ArmFxEvent e) {
		if (e.origin() != ownId || !handles(e.arg())) return;
		if (ArmProtocol.accessoryMove(e.arg()) == UNMARK) {
			if (e.ids().length == 0) clear();
			else for (int id : e.ids()) remove(id);
			return;
		}
		long until = e.receivedTick() + (e.ticks() > 0 ? e.ticks() : DEFAULT_TICKS);
		for (int id : e.ids()) {
			if (id >= 0 && id != ownId) put(id, until);
		}
	}

	private static void put(int id, long until) {
		for (int i = 0; i < count; i++) {
			if (IDS[i] == id) {
				UNTIL[i] = until; // told again: the server's clock replaces ours
				return;
			}
		}
		if (count == MAX) removeAt(0); // the oldest
		IDS[count] = id;
		UNTIL[count] = until;
		count++;
	}

	private static void removeAt(int i) {
		count--;
		for (int k = i; k < count; k++) {
			IDS[k] = IDS[k + 1];
			UNTIL[k] = UNTIL[k + 1];
		}
	}

	/** Drops the marks that have run out by client tick {@code now} (every client tick: SpyglassZoom's). */
	public static void expire(long now) {
		for (int i = count - 1; i >= 0; i--) {
			if (now >= UNTIL[i]) removeAt(i);
		}
	}

	/** Drops the marks that have run out by this tick. */
	public static void expire() {
		expire(Arms.TICK);
	}

	/** Drops one mark (its creature is gone). */
	public static void remove(int id) {
		for (int i = 0; i < count; i++) {
			if (IDS[i] == id) {
				removeAt(i);
				return;
			}
		}
	}

	/** How many creatures are marked. */
	public static int count() {
		return count;
	}

	/** The {@code i}th marked creature's entity id, in the order marked (0 to {@link #count} - 1). */
	public static int id(int i) {
		return IDS[i];
	}

	/** The first marked creature's entity id, or -1 for none. */
	public static int marked() {
		return count > 0 ? IDS[0] : -1;
	}

	static void clear() {
		count = 0;
	}

	/** Forgets every mark (leaving a world; not a zoom starting: a quick release and press is one zoom to the server, which keeps its marks). */
	public static void clearMark() {
		clear();
	}
}
