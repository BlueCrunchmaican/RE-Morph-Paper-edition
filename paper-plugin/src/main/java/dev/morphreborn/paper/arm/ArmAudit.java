package dev.morphreborn.paper.arm;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * A formed arm's invariants, checked every tick (spec 3.4): the arm is the held item, in its home slot, and nowhere
 * else. The event guards keep it so on every path the research found, but other plugins can still move things without
 * an event (a plugin calling setHeldItemSlot, /clear, an inventory editor), and this is the net under them:
 * <ul>
 * <li>A1: the held slot isn't home any more: retract, as a scroll away would (and form the arm bound to the slot now held,
 * if there is one: a plugin moved the held slot without the event);</li>
 * <li>A2: the home slot lost the arm: retract, and the item it replaced comes back where it can (lostArm); not for a tucked
 * arm (a screen is open, 2026-11-16), which has no item and whose real item is in the slot;</li>
 * <li>A3: once a second (staggered across players), and when an inventory opens or closes: any other arm in the
 * player's inventory, on their cursor or in the inventory they have open is a stray, and deleted.</li>
 * </ul>
 * A check that throws retracts the arm (ERROR) rather than leaving it in an unknown state.
 */
final class ArmAudit {
	private final ArmManager arms;

	ArmAudit(ArmManager arms) {
		this.arms = arms;
	}

	/** One tick's check of one session. */
	void check(Player p, ArmSession s, int tick) {
		PlayerInventory inv = p.getInventory();
		if (inv.getHeldItemSlot() != s.slot) { // A1
			arms.finish(p, s, ArmProtocol.WHY_SLOT, ArmManager.Overflow.DROP);
			arms.scrolledOnto(p, inv.getHeldItemSlot());
			return;
		}
		if (!s.tucked && !arms.isSessionArm(s, inv.getItem(s.slot))) { // A2 (a tucked arm has no item: its real item is in the slot, as it should be)
			arms.plugin().getLogger().warning(p.getName() + "'s " + s.form + " arm left its slot without a retract (another plugin?). The item it held is"
					+ " given back.");
			arms.finish(p, s, ArmProtocol.WHY_ERROR, ArmManager.Overflow.DROP);
			return;
		}
		if (Math.floorMod(tick + p.getUniqueId().hashCode(), 20) == 0) scan(p, s); // A3
	}

	/**
	 * A3: delete every arm in the player's inventory (slots 0-40), on their cursor and in the inventory they have open,
	 * except a live session's arm in its own home slot (theirs, or another armed player's seen through /invsee).
	 */
	void scan(Player p, @Nullable ArmSession s) {
		PlayerInventory inv = p.getInventory();
		for (int slot = 0; slot < inv.getSize(); slot++) {
			ItemStack item = inv.getItem(slot);
			if (!ArmItems.isArm(item)) continue;
			if (s != null && slot == s.slot && arms.isSessionArm(s, item)) continue;
			stray(p, s, item);
			int at = slot;
			arms.write(p, () -> inv.setItem(at, null));
		}
		ItemStack cursor = p.getItemOnCursor();
		if (ArmItems.isArm(cursor)) {
			stray(p, s, cursor);
			p.setItemOnCursor(null);
		}
		InventoryView view = p.getOpenInventory();
		Inventory top = view.getTopInventory();
		if (!(top instanceof PlayerInventory) || top.getHolder() != p) arms.purge(top);
	}

	private void stray(Player p, @Nullable ArmSession s, ItemStack item) {
		if (s != null && ArmItems.nonceOf(item) == s.nonce) {
			arms.plugin().getLogger().warning("A copy of " + p.getName() + "'s " + s.form + " arm was outside its slot; deleted.");
		} else {
			arms.plugin().debug("Deleted a stray arm from " + p.getName() + "'s inventory.");
		}
	}
}
