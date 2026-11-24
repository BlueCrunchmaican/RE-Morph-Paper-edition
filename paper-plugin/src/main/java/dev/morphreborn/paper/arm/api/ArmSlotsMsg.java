package dev.morphreborn.paper.arm.api;

import java.util.List;

/**
 * S_ARM_SLOTS: the owner's arm slots (2026-11-16; servers from 7.6.0-beta.3), as their client is told. The
 * client draws the arm over each bound slot, and over the held one with the real item under it in the corner. Sent to the
 * owner only.
 *
 * @param slots every binding, by slot
 * @param held an arm is out in the held slot (forming, formed or going back)
 * @param heldItem the item under it, as its id ("minecraft:stone"; "minecraft:air" for an empty slot); "" when not held
 * @param heldCount that item's count
 * @param heldGlint that item has enchantments or an enchantment glint override
 */
public record ArmSlotsMsg(List<Slot> slots, boolean held, String heldItem, int heldCount, boolean heldGlint) {
	/** One binding: the hotbar slot (0 to 8), the form id and the level (1 to 3) it would form at now. */
	public record Slot(int slot, String form, int tier) {}

	public ArmSlotsMsg {
		slots = List.copyOf(slots);
		if (heldItem == null) heldItem = "";
	}

	/** No arm out: the bindings alone. */
	public static ArmSlotsMsg idle(List<Slot> slots) {
		return new ArmSlotsMsg(slots, false, "", 0, false);
	}
}
