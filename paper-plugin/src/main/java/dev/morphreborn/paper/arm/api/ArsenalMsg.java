package dev.morphreborn.paper.arm.api;

import java.util.List;

/**
 * S_ARSENAL: what the owner's wheel and tree show (spec section 4). Built forms only, in the wheel's order; levels 0
 * are forms not owned. Costs in milli-biomass, cooldowns in ticks.
 *
 * @param usable the player can form an arm here at all
 * @param whyNot why not, when not ("" otherwise)
 * @param free arms cost nothing here (Classic, creative, arm-cost off)
 * @param lastForm the form a J tap re-forms ("" for none)
 * @param lanternLevel 0: the lantern isn't owned
 * @param accessories every built accessory in ArmForm.ACCESSORIES order, the lantern included (8.0.0-beta.2): sent only to clients at
 *     ArmProtocol.NEW_FORMS_SINCE, after the flare's fields; the lantern's own fields stay for older clients
 */
public record ArsenalMsg(boolean usable, String whyNot, boolean free, int perBlockMilli, int perHitMilli, int perBoltMilli, int drainMilliPerMinute,
		int lanternDrainMilliPerMinute, List<Entry> entries, String lastForm, int lanternLevel, boolean lanternLit, int flareCostMilli,
		int flareCooldownTicks, int flareCooldownLeftTicks, List<AccessoryEntry> accessories) {
	public ArsenalMsg {
		entries = List.copyOf(entries);
		accessories = List.copyOf(accessories);
	}

	/** Without accessories (the older fields only: for an older client, or a test of them). */
	public ArsenalMsg(boolean usable, String whyNot, boolean free, int perBlockMilli, int perHitMilli, int perBoltMilli, int drainMilliPerMinute,
			int lanternDrainMilliPerMinute, List<Entry> entries, String lastForm, int lanternLevel, boolean lanternLit, int flareCostMilli,
			int flareCooldownTicks, int flareCooldownLeftTicks) {
		this(usable, whyNot, free, perBlockMilli, perHitMilli, perBoltMilli, drainMilliPerMinute, lanternDrainMilliPerMinute, entries, lastForm, lanternLevel,
				lanternLit, flareCostMilli, flareCooldownTicks, flareCooldownLeftTicks, List.of());
	}

	/** One form: id, display name, owned level (0: not owned), max level, its special and that special's cost and cooldown now. */
	public record Entry(String id, String name, int level, int maxLevel, String specialName, int specialCostMilli, int cooldownTicks,
			int cooldownLeftTicks) {}

	/** One accessory: id, the level the player owns (0: not owned), and whether it is on now. */
	public record AccessoryEntry(String id, int level, boolean on) {}
}
