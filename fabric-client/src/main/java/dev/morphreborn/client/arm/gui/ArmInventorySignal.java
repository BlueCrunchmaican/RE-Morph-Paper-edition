package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.net.ClientNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Tells the server when a container screen is open (2026-11-16): your own inventory, the creative inventory,
 * a chest, anything that is an {@code AbstractContainerScreen}. The server cannot see your own inventory open (vanilla
 * sends nothing), and the held arm has to step aside for its real item while the screen shows it. C_ARM
 * ACTION_INVENTORY_OPEN goes when one opens, ACTION_INVENTORY_CLOSED when it closes (to no screen or to one that isn't a
 * container); only to servers that know the actions (ArmProtocol.SLOTS_SINCE), only while arms are offered, and never the
 * same one twice in a row.
 * <p>
 * A request the C_ARM throttle holds back (one every 4 ticks, shared with the wheel's) is not counted as sent: it is
 * tried again next tick, so a CLOSED is never lost after an OPEN; a screen opened and closed again inside the wait sends
 * nothing at all, which leaves the server exactly where it was. Called every client tick.
 */
public final class ArmInventorySignal {
	/** What the server was last told: true after OPEN. */
	private static boolean sentOpen;
	/**
	 * When the CLOSED that ends a tuck actually went out (System.nanoTime), for {@link #tucked}. Measured from the send,
	 * not from the screen closing: the shared throttle can hold CLOSED back, and the server only starts putting the arm
	 * item back once it has it (2026-11-16).
	 */
	private static long closeSentAt = Long.MIN_VALUE / 2;
	/** The hotbar slot that was held when OPEN went out: scrolling off it ends the grace at once (-1: none yet). */
	private static int openSlot = -1;
	/** How long after CLOSED goes out your held item is still drawn as the arm, while the server puts it back (ns). */
	static final long UNTUCK_GRACE_NS = 400_000_000L;

	private ArmInventorySignal() {}

	/**
	 * The action to send now, or -1 for none: OPEN when a container screen is up and the server wasn't told, CLOSED when
	 * none is and it was. Pure: the tick feeds it the facts.
	 */
	public static int next(boolean containerOpen, boolean sentOpen) {
		if (containerOpen == sentOpen) return -1;
		return containerOpen ? ArmProtocol.ACTION_INVENTORY_OPEN : ArmProtocol.ACTION_INVENTORY_CLOSED;
	}

	/**
	 * Whether the arm is tucked at {@code now}: the server was told a screen is open, or CLOSED went out under {@link
	 * #UNTUCK_GRACE_NS} ago and the slot is still the one held when the screen opened. Scrolling to another slot ends the
	 * grace at once: that slot's item is no arm, and drawing it as one (food, blocks) until the server's S_ARM arrives
	 * would be wrong. Pure: the tick and the render thread feed it the facts.
	 */
	public static boolean tuckedAt(boolean sentOpen, long now, long closeSentAt, int selectedSlot, int openSlot) {
		if (sentOpen) return true;
		return now - closeSentAt < UNTUCK_GRACE_NS && selectedSlot == openSlot;
	}

	public static void tick(Minecraft mc) {
		boolean offered = ClientNet.armSlotsOffered();
		if (!offered) {
			// nothing to tell (no server, or one without arm slots): the next server starts from "closed"
			sentOpen = false;
			return;
		}
		boolean open = mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>;
		int action = next(open, sentOpen);
		if (action < 0) return;
		if (ClientNet.sendArmInventory(action)) {
			sentOpen = open;
			if (open) {
				openSlot = mc.player.getInventory().getSelectedSlot();
				closeSentAt = Long.MIN_VALUE / 2; // a grace left over from the last screen is over
			} else {
				closeSentAt = System.nanoTime();
			}
		}
	}

	/** On joining and leaving a server. */
	public static void reset() {
		sentOpen = false;
		closeSentAt = Long.MIN_VALUE / 2;
		openSlot = -1;
	}

	/**
	 * Whether {@code entity} is you with your arm tucked away for a container screen: the server was told the screen is
	 * open (or CLOSED went out under {@link #UNTUCK_GRACE_NS} ago, on the same hotbar slot, and the arm item is on its way
	 * back). The held real item is then drawn as the arm (ArmStacks.recognize), so the arm stays on you
	 * (2026-11-16). Render thread.
	 */
	public static boolean tucked(net.minecraft.world.entity.LivingEntity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || entity != mc.player || !ClientNet.armSlotsOffered()) return false;
		return tuckedAt(sentOpen, System.nanoTime(), closeSentAt, mc.player.getInventory().getSelectedSlot(), openSlot);
	}
}
