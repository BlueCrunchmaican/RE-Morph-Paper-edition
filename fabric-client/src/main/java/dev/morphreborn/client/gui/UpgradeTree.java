package dev.morphreborn.client.gui;

import java.util.List;

/** The Survival upgrade tree as the server sent it (S_UPGRADES): the server decides everything, this only shows it. */
public record UpgradeTree(String message, boolean unlocked, int biomass, int capacity, List<Node> nodes) {
	/**
	 * @param cost    next level's price, -1 when fully upgraded
	 * @param missing what must be bought first, empty if nothing
	 */
	public record Node(String id, String name, String description, String icon, String parent, int level, int maxLevel, String now, String next, int cost,
			String missing, boolean affordable) {
		public boolean maxed() {
			return cost < 0;
		}

		/** Can be bought right now. */
		public boolean buyable(boolean unlocked) {
			return unlocked && !maxed() && missing.isEmpty() && affordable;
		}
	}
}
