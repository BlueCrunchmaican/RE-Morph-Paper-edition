package dev.morphreborn.paper.arm;

import org.bukkit.event.inventory.InventoryType;

/**
 * The unprompted re-form of a bound arm when the player is back in their own body (2026-11-17): the part that decides
 * whether now is a fair moment. Kept free of the server so it can be tested.
 */
final class OwnBodyDeploy {
	private OwnBodyDeploy() {}

	/**
	 * A timer decides this moment (a transformation ending, a borrow running out), not the player, so it must not close a
	 * container they have open: forming an arm swaps the held slot and closes the screen. Only the modded client reports its screens
	 * to the arm code, so the open view itself is the check; the player's own inventory view (CRAFTING, or CREATIVE) is no screen.
	 * When this says no, nothing re-forms until the slot is scrolled onto or the modded client's screen closes, as before.
	 */
	static boolean fairMoment(InventoryType open) {
		return fairMoment(open.name());
	}

	/** By enum name: InventoryType can't load without a server (its constants read the registry), so the rule is tested on names. */
	static boolean fairMoment(String openTypeName) {
		return "CRAFTING".equals(openTypeName) || "CREATIVE".equals(openTypeName);
	}
}
