package dev.morphreborn.paper.arm.rules;

/**
 * Dying with an arm formed: the arm must vanish and the item it replaced (the stash) must come out exactly once, whether
 * keepInventory is on or off, whether a gravestone plugin later reads the drops, and whether another plugin cancels the
 * death. So at the lowest priority, before anyone else reads the event, the arm is swapped for the stash in place: in
 * the drops, in the items to keep, and in the live slot.
 * <p>
 * Where the stash goes depends on how the game will hand items back:
 * <ul>
 * <li>keepInventory on: the inventory stays as it is, so the stash belongs in the live slot only. Drops never get it
 * (that would copy it); the items to keep get it in the arm's place if the arm was listed there.</li>
 * <li>keepInventory off, the arm in the items to keep (a soulbound-style plugin put it there): Paper keeps the listed
 * items that still match the live inventory, so the stash takes the arm's place there and in the live slot, and stays
 * out of the drops.</li>
 * <li>keepInventory off otherwise: the stash drops, in the arm's place, or added to the drops when the arm wasn't
 * listed (another plugin took it out before us). The live slot gets it too, which matters only if the death is
 * cancelled; otherwise the inventory is cleared after the event.</li>
 * </ul>
 * Pure, so every combination is unit tested.
 */
public final class DeathLedger {
	private DeathLedger() {}

	/**
	 * How to carry it out. Every arm stack is removed from both lists in every case. Then {@code replaceInDrops} puts the
	 * stash in the drops where the arm was (appended when the arm wasn't listed), {@code replaceInKept} puts it in the
	 * items to keep where the arm was, {@code removeOnly} says the stash is empty so nothing takes the arm's place, and
	 * {@code writeSlot} writes the live slot with the stash (air when empty). Then the session ends: the record is
	 * cleared, with no lockout and no effects.
	 */
	public record Plan(boolean replaceInDrops, boolean replaceInKept, boolean removeOnly, boolean writeSlot) {}

	/**
	 * @param keepInventory the event's keepInventory
	 * @param dropsIndex where the session arm is in the drops, -1 if it isn't
	 * @param keepIndex where it is in the items to keep, -1 if it isn't
	 * @param stashEmpty the arm replaced an empty hand
	 */
	public static Plan plan(boolean keepInventory, int dropsIndex, int keepIndex, boolean stashEmpty) {
		if (stashEmpty) return new Plan(false, false, true, true);
		if (keepInventory) return new Plan(false, keepIndex >= 0, false, true);
		if (keepIndex >= 0) return new Plan(false, true, false, true);
		return new Plan(true, false, false, true);
	}
}
