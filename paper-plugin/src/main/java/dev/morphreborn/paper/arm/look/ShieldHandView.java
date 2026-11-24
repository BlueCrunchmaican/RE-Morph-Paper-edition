package dev.morphreborn.paper.arm.look;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The packet half of {@link ShieldGrip} (decided 2026-11-17): for a player without the mod who has the Tendril Shield on,
 * <ul>
 *   <li><b>what they are shown.</b> Every packet that sets their own off hand (the player inventory window's slot 45 in a window's contents
 *       or a single slot, and the inventory slot 40 of SetPlayerInventory) is rewritten to carry a vanilla shield, so their game keeps it
 *       there through every resync (a container closing, a respawn, an inventory update). {@link #showShield} sends the first one. The
 *       server's real off-hand item is never changed: it is only not shown to this one player while the shield is on.</li>
 *   <li><b>what is heard.</b> The use packet for the OFF_HAND is the game having raised that shield (the main hand did not use the click),
 *       RELEASE_USE_ITEM is the key being let go; both go to {@link ShieldGrip}. Nothing is cancelled or changed, so the server still sees every
 *       packet as it always did (the off hand's own use is refused by the shield accessory's guards, which also cover the mod's players).</li>
 * </ul>
 * Runs on netty threads and reads only {@link ShieldGrip}'s concurrent maps. A failure is logged once and switches the view off: players
 * without the mod then keep sneak ({@link ShieldGrip#available}) and are sent their real inventory again.
 */
final class ShieldHandView {
	/** The player inventory window (container 0) has the off hand at slot 45; SetPlayerInventory counts it as inventory slot 40. */
	private static final int WINDOW_SLOT = 45, INVENTORY_SLOT = 40;

	private final Logger log;
	private @Nullable PacketListenerCommon listener;
	private volatile boolean failed;

	ShieldHandView(Logger log) {
		this.log = log;
	}

	/** Register with PacketEvents and become {@link ShieldGrip}'s way of showing the shield. */
	void start() {
		listener = PacketEvents.getAPI().getEventManager().registerListener(new Rewriter());
		ShieldGrip.use(this::showShield);
		ShieldGrip.clients(ShieldHandView::hasOffHand);
	}

	/** A game from before 1.9 (reached through a protocol plugin) has no off hand and no shield to raise with it. */
	private static boolean hasOffHand(Player p) {
		return PacketEvents.getAPI().getPlayerManager().getClientVersion(p).isNewerThanOrEquals(ClientVersion.V_1_9);
	}

	/** Give up the registration (shutdown). */
	void stop() {
		ShieldGrip.use(null);
		PacketListenerCommon l = listener;
		listener = null;
		if (l != null && PacketEvents.getAPI() != null) PacketEvents.getAPI().getEventManager().unregisterListener(l);
	}

	private static ItemStack shield() {
		return ItemStack.builder().type(ItemTypes.SHIELD).amount(1).build();
	}

	/** The owner's own off hand is a shield now: sent once, then the rewriter keeps it there. Main thread. */
	private void showShield(Player owner) {
		if (failed) return;
		try {
			PacketEvents.getAPI().getPlayerManager().sendPacket(owner, new WrapperPlayServerSetPlayerInventory(INVENTORY_SLOT, shield()));
		} catch (Throwable t) {
			failedOnce(t);
		}
	}

	private void failedOnce(Throwable t) {
		if (failed) return;
		failed = true;
		ShieldGrip.use(null); // the accessory sees "not available" on its next tick and gives each player their real inventory back
		log.log(Level.WARNING, "The shield's off-hand look failed; players without the mod now raise the shield by sneaking.", t);
	}

	private final class Rewriter extends PacketListenerAbstract {
		Rewriter() {
			super(PacketListenerPriority.NORMAL);
		}

		@Override
		public void onPacketSend(PacketSendEvent event) {
			if (failed || !(event.getPlayer() instanceof Player owner) || !ShieldGrip.ghost(owner.getEntityId())) return;
			try {
				if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
					WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
					if (packet.getWindowId() != 0 || packet.getSlot() != WINDOW_SLOT) return;
					packet.setItem(shield());
					event.markForReEncode(true);
				} else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
					WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
					List<ItemStack> items = packet.getItems();
					if (packet.getWindowId() != 0 || items.size() <= WINDOW_SLOT) return;
					List<ItemStack> copy = new ArrayList<>(items);
					copy.set(WINDOW_SLOT, shield());
					packet.setItems(copy);
					event.markForReEncode(true);
				} else if (event.getPacketType() == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
					WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
					if (packet.getSlot() != INVENTORY_SLOT) return;
					packet.setStack(shield());
					event.markForReEncode(true);
				}
			} catch (Throwable t) {
				failedOnce(t);
			}
		}

		@Override
		public void onPacketReceive(PacketReceiveEvent event) {
			if (failed || !(event.getPlayer() instanceof Player owner) || !ShieldGrip.ghost(owner.getEntityId())) return;
			try {
				if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
					if (new WrapperPlayClientUseItem(event).getHand() == InteractionHand.OFF_HAND) ShieldGrip.press(owner.getEntityId());
				} else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
					if (new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.RELEASE_USE_ITEM) ShieldGrip.release(owner.getEntityId());
				}
			} catch (Throwable t) {
				failedOnce(t);
			}
		}
	}
}
