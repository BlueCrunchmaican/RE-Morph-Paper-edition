package dev.morphreborn.paper.arm.look;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * What players without the mod are shown of the Tendril Shield (decided 2026-11-16): a shield in the owner's off hand,
 * and held up while it is raised. The owner's real off-hand item is never touched: the shield is only in the packets other players are
 * sent about the owner, rewritten by {@link ArmEquipmentView} from the states kept here.
 * <p>
 * This class knows nothing of PacketEvents, so the shield accessory can use it where PacketEvents isn't installed (then nobody is
 * shown a shield). {@link ArmEquipmentView} registers itself as the {@link Sender} when it starts; the map is read on netty threads, so it
 * is concurrent and holds plain numbers.
 */
public final class ShieldLook {
	/** No shield: the owner's real off hand is what everyone is shown. */
	public static final int NONE = 0;
	/** A shield in the off hand, down. */
	public static final int HELD = 1;
	/** A shield in the off hand, raised: the owner's flags say the off hand is in use, so viewers draw the blocking pose. */
	public static final int RAISED = 2;

	/** Sends viewers the owner's off hand and flags again, so a changed state shows; implemented by the packet rewriter. */
	public interface Sender {
		/**
		 * @param owner the shield's owner
		 * @param state the state now
		 * @param before the state it was in
		 * @param only a single viewer to send to (one who just began to track the owner), or null for everyone tracking the owner
		 */
		void send(Player owner, int state, int before, @Nullable Player only);
	}

	private static final ConcurrentMap<Integer, Integer> STATES = new ConcurrentHashMap<>();
	private static volatile @Nullable Sender sender;

	private ShieldLook() {}

	/** The state of the shield the entity (by its entity id) shows, {@link #NONE} when it has none. Safe on any thread. */
	public static int state(int entityId) {
		return STATES.getOrDefault(entityId, NONE);
	}

	/** Whether any player has a shield: the rewriter's cheap way out for every other packet. */
	public static boolean any() {
		return !STATES.isEmpty();
	}

	/** Set the owner's state and, when it changed, have everyone tracking the owner sent it. */
	public static void set(Player owner, int state) {
		int before = swap(owner.getEntityId(), state);
		Sender s = sender;
		if (before != state && s != null) s.send(owner, state, before, null);
	}

	/** The owner is gone (offline): forget the state without sending anything. */
	public static void forget(int entityId) {
		STATES.remove(entityId);
	}

	/** {@code viewer} began to track {@code owner}, who has a shield: send them what vanilla leaves out (the raised flags). */
	public static void tracked(Player viewer, Player owner) {
		int state = state(owner.getEntityId());
		Sender s = sender;
		if (state != NONE && s != null) s.send(owner, state, NONE, viewer);
	}

	/**
	 * The packet rewriter takes this over at start (and gives it up with null at shutdown). The states are NOT dropped with it (they were,
	 * until the review of 2026-11-16): they belong to the shield accessory, which keeps its own states and puts each out with {@link #set} or
	 * {@link #forget}, so a look module restarted on its own (a partial reload) leaves the two agreeing instead of a shield that is on but
	 * shown as nothing.
	 */
	public static void use(@Nullable Sender s) {
		sender = s;
	}

	private static int swap(int entityId, int state) {
		Integer was = state == NONE ? STATES.remove(entityId) : STATES.put(entityId, state);
		return was == null ? NONE : was;
	}
}
