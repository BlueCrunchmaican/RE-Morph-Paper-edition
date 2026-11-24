package dev.morphreborn.paper.arm.look;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * How a player WITHOUT the mod raises the Tendril Shield with the use key (decided 2026-11-17).
 * <p>
 * A vanilla game sends nothing at all for a right-click with an empty hand on the air, and a plain item in the main hand sends one use
 * packet per click that says nothing about the key staying down, so the key cannot be read from what the game does with the hand it has.
 * What the game does do, for a shield in the off hand, is raise it natively (main hand first, as it always has: a block to place, food, a
 * bow, a door or a villager in front of the main hand takes the click, and only a click the main hand did not use goes on to the off hand),
 * send a use packet for the OFF_HAND when it does, and send RELEASE_USE_ITEM when the key is let go. So the owner is shown a shield in the
 * off hand ({@link #setGhost}: the packets about their own inventory are rewritten by {@link ShieldHandView}), and the server reads those
 * two packets as the key going down and up. The real off-hand item is never read, changed or moved; the server only does not let it be
 * used while the shield is on (ShieldAccessory's guards).
 * <p>
 * This class knows nothing of PacketEvents, so the shield accessory can use it where PacketEvents isn't installed ({@link #available} is
 * then false and a player without the mod keeps sneak). The maps are read on netty threads (the use packets) and written on the main thread,
 * so they are concurrent and hold plain numbers, keyed by entity id as {@link ShieldLook} is.
 */
public final class ShieldGrip {
	/** Sends the owner the shield in their off hand (on), implemented by the packet view; taking it away is Bukkit's own inventory update. */
	public interface Sender {
		/** The owner's game is shown a shield in the off hand now. Main thread. */
		void showShield(Player owner);
	}

	/** Owners who have the shield on (by entity id): the plugin's own right-click uses are only noted for these. */
	private static final ConcurrentMap<Integer, Boolean> OWNERS = new ConcurrentHashMap<>();
	/** Owners whose game is being shown the off-hand shield. */
	private static final ConcurrentMap<Integer, Boolean> GHOSTS = new ConcurrentHashMap<>();
	/** The tick the off-hand use packet came, while the key is down (the release removes it). */
	private static final ConcurrentMap<Integer, Integer> PRESSED = new ConcurrentHashMap<>();
	/** The tick a right-click of the main hand was used by this plugin's own handlers (flint, a carried block, milking, a mount, a trade). */
	private static final ConcurrentMap<Integer, Integer> USED = new ConcurrentHashMap<>();
	private static volatile @Nullable Sender sender;
	/** Whether a player's game has an off hand to be shown a shield in (a client from before 1.9, through a protocol plugin, has none); null: assume it does. */
	private static volatile @Nullable Predicate<Player> offHandClient;
	/** The server tick, kept by the shield accessory, so the netty thread can stamp what it hears. */
	private static volatile int clock;

	private ShieldGrip() {}

	/** The packet view takes this over at start (and gives it up with null at shutdown or when it fails). */
	public static void use(@Nullable Sender s) {
		sender = s;
		if (s == null) offHandClient = null;
	}

	/** The packet view says which players' games can be shown the shield at all (set after {@link #use}). */
	public static void clients(@Nullable Predicate<Player> hasOffHand) {
		offHandClient = hasOffHand;
	}

	/** Whether a player without the mod can be shown the shield (the packet view is running). */
	public static boolean available() {
		return sender != null;
	}

	/** Whether this player's game can be shown the shield: the packet view is running and their game has an off hand. A player it is not true for keeps sneak. */
	public static boolean available(Player p) {
		if (sender == null) return false;
		Predicate<Player> clients = offHandClient;
		if (clients == null) return true;
		try {
			return clients.test(p);
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** The shield accessory's tick: the clock the stamps are taken from. */
	public static void clock(int tick) {
		clock = tick;
	}

	/** An owner switched the shield on or off (by entity id). */
	public static void owner(int entityId, boolean on) {
		if (on) {
			OWNERS.put(entityId, Boolean.TRUE);
			return;
		}
		forget(entityId);
	}

	/** Forget everything kept for this entity id (the shield went off, the player left or died). Safe on any thread. */
	public static void forget(int entityId) {
		OWNERS.remove(entityId);
		GHOSTS.remove(entityId);
		PRESSED.remove(entityId);
		USED.remove(entityId);
	}

	// ------------------------------------------------------------------ the shown shield

	/** Whether this player's game is shown the off-hand shield (the rewriter's cheap way out). Safe on any thread. */
	public static boolean ghost(int entityId) {
		return !GHOSTS.isEmpty() && GHOSTS.containsKey(entityId);
	}

	/**
	 * Start or stop showing the owner's own game the shield in the off hand. Started: the off hand is sent as a shield now and every later
	 * packet about it is rewritten. Stopped: the real inventory is sent again, which is the real off-hand item. Main thread.
	 */
	public static void setGhost(Player owner, boolean on) {
		int id = owner.getEntityId();
		if (on) {
			if (GHOSTS.put(id, Boolean.TRUE) != null) return;
			Sender s = sender;
			if (s != null) s.showShield(owner);
			return;
		}
		PRESSED.remove(id);
		if (GHOSTS.remove(id) != null && owner.isOnline()) owner.updateInventory();
	}

	// ------------------------------------------------------------------ the owner's own use packets (netty thread)

	/** The game sent a use packet for the off hand: the use key went down on a click the main hand did not use. */
	public static void press(int entityId) {
		if (GHOSTS.containsKey(entityId)) PRESSED.put(entityId, clock);
	}

	/** The game sent RELEASE_USE_ITEM: the key is up. */
	public static void release(int entityId) {
		PRESSED.remove(entityId);
	}

	/** The tick the key went down (-1 while it is up). */
	public static int pressedAt(int entityId) {
		return PRESSED.getOrDefault(entityId, -1);
	}

	// ------------------------------------------------------------------ main hand first

	/**
	 * One of this plugin's own right-click uses took the click in the main hand (flint and steel lighting something, a carried block put
	 * down or picked up, a cow milked, a mount ridden, a villager player's trade): the game cannot know that, so the shield is told, and
	 * the click does not raise it. Noted only for a player who has the shield on. Main thread.
	 */
	public static void mainHandUsed(Player p) {
		int id = p.getEntityId();
		if (OWNERS.containsKey(id)) USED.put(id, clock);
	}

	/** The tick a right-click was last used by the main hand's own plugin handlers (-1 never). */
	public static int usedAt(int entityId) {
		return USED.getOrDefault(entityId, -1);
	}
}
