package dev.morphreborn.client.gui;

import java.util.List;

/** The Survival upgrade tree as the server sent it (S_UPGRADES): the server decides everything, this only shows it. */
public record UpgradeTree(String message, boolean unlocked, int biomass, int capacity, List<Node> nodes) {
	/**
	 * Both trees have the same upgrades in the same order with the same parents: the layout (which uses only ids and parents) is then the same, so
	 * a refreshed tree can be taken in place without laying out again. Levels, costs and texts do not matter here.
	 */
	public boolean sameShape(UpgradeTree other) {
		if (nodes.size() != other.nodes.size()) return false;
		for (int i = 0; i < nodes.size(); i++) {
			Node a = nodes.get(i);
			Node b = other.nodes.get(i);
			if (!a.id().equals(b.id()) || !a.parent().equals(b.parent())) return false;
		}
		return true;
	}

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
