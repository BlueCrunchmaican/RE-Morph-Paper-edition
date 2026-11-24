package dev.morphreborn.paper.arm.api;

import java.util.List;
import java.util.UUID;

/**
 * S_ARM: a player's arm and accessories as clients that draw arms are told (spec section 4). Sent on every change, so a
 * client can draw it from the message alone; it never carries one-off effects (those are S_ARM_FX), so a resend never
 * replays one.
 *
 * @param form the form now ("" for none)
 * @param fromForm the form being reshaped from ("" unless RESHAPING)
 * @param level 0 to 3
 * @param state ArmProtocol.STATE_*
 * @param time ticks spent in the state so far
 * @param duration the state's length in ticks (0 for NONE and FORMED)
 * @param slot the hotbar slot the arm is in, -1 for none
 * @param why ArmProtocol.WHY_*: why it changed
 * @param lanternTime ticks since the lantern was lit
 * @param recallTicks always 0 since 7.6.0-beta.3: the timed recall was replaced by arm slots (S_ARM_SLOTS); kept so the packet's layout stays
 * @param lanternBright the lantern's brightness the player chose, 1 to 5 (0 when it isn't lit); sent last, after the
 *     fields older clients read (8.0.0)
 * @param accessories every accessory that is on, in the order they were switched on (8.0.0-beta.2): sent only to clients at
 *     ArmProtocol.NEW_FORMS_SINCE, after the lantern fields; the lantern's own fields above stay for older clients and the lantern
 *     is in this list as well. An accessory not in the list is off.
 */
public record ArmStateMsg(UUID subject, int entityId, String form, String fromForm, int level, int state, int time, int duration, int slot, int why,
		boolean lanternLit, int lanternLevel, int lanternTime, int recallTicks, int lanternBright, List<Accessory> accessories) {
	public ArmStateMsg {
		accessories = List.copyOf(accessories);
	}

	/** Without a list of accessories (the older fields only: a message for an older client, or a test of them). */
	public ArmStateMsg(UUID subject, int entityId, String form, String fromForm, int level, int state, int time, int duration, int slot, int why,
			boolean lanternLit, int lanternLevel, int lanternTime, int recallTicks, int lanternBright) {
		this(subject, entityId, form, fromForm, level, state, time, duration, slot, why, lanternLit, lanternLevel, lanternTime, recallTicks, lanternBright, List.of());
	}

	/** Without a brightness (none lit, or a test of the older fields). */
	public ArmStateMsg(UUID subject, int entityId, String form, String fromForm, int level, int state, int time, int duration, int slot, int why,
			boolean lanternLit, int lanternLevel, int lanternTime, int recallTicks) {
		this(subject, entityId, form, fromForm, level, state, time, duration, slot, why, lanternLit, lanternLevel, lanternTime, recallTicks, 0, List.of());
	}

	/**
	 * One accessory in S_ARM: its id (ArmForm.ACCESSORIES), whether it is on, the level it was switched on at, and the
	 * accessory-specific argument (ArmAccessoryModule.arg: the shield raised 0 or 1, the spyglass zooming 0 or 1, the lantern's brightness).
	 */
	public record Accessory(String id, boolean on, int level, int arg) {}
}
