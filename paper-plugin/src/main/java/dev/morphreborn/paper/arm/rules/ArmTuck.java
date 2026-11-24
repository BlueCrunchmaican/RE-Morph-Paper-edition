package dev.morphreborn.paper.arm.rules;

/**
 * Tucking the arm while a container screen is open (2026-11-16: "when the inventory opens you should maintain
 * the hand morph, it should not go back to your hand"). The screen opening (the modded client's ACTION_INVENTORY_OPEN)
 * gives the held slot its real item back, so the inventory shows it and lets it be moved, but the arm stays formed: the
 * session lives on, drawn on the body for everyone, with no arm item anywhere. The screen closing (ACTION_INVENTORY_CLOSED)
 * puts the arm item back over whatever the slot holds then, with no forming animation.
 * <p>
 * These are the two decisions, pure so the rows can be tested: what each signal does for the state the player's arm is in.
 */
public final class ArmTuck {
	private ArmTuck() {}

	/** What a screen opening does to the player's arm. */
	public enum OnOpen {
		/** The real item goes back into the slot and the session stays formed, tucked. */
		TUCK,
		/** The arm goes back for good at once, as a scroll away would (it cannot be tucked). */
		END,
		/** Nothing to do: no arm, or it is tucked already. */
		NOTHING
	}

	/**
	 * @param hasSession an arm is out
	 * @param tucked it is tucked already (a repeated OPEN)
	 * @param ending it is retracting or melting: a tuck would hand the item back with the arm still going, so it ends now
	 * @param armAtHome the arm item is in its slot (if it is not, something else took it and the ordinary retract repairs it)
	 * @param stashReadable the item it replaced decodes (if it does not, the ordinary retract keeps its bytes for /morph arm recover)
	 */
	public static OnOpen onOpen(boolean hasSession, boolean tucked, boolean ending, boolean armAtHome, boolean stashReadable) {
		if (!hasSession || tucked) return OnOpen.NOTHING;
		if (ending || !armAtHome || !stashReadable) return OnOpen.END;
		return OnOpen.TUCK;
	}

	/** What a screen closing does. */
	public enum OnClose {
		/** No arm out: the held slot, if it is bound, forms its arm (quick). */
		DEPLOY_BOUND,
		/** Tucked and still in its slot: the slot's item is stashed and the arm item goes back, the same session. */
		UNTUCK,
		/** Tucked and the selection moved meanwhile (a lying client, another plugin): the session ends, and the new slot counts as a scroll. */
		END_AND_SCROLL,
		/** Tucked but what is in the slot now is too big for the arm to hold: the session ends and the item stays where it is. */
		END_TOO_BIG,
		/**
		 * Tucked and retracting or melting: it ends at once, as a screen opening ends an ending arm. There is no arm in the
		 * hand to see it out, and nothing to give back (the stash is empty, the real item is in the slot); left to run out it
		 * would only keep the arm's guards and billing on a session that has nothing in the hand.
		 */
		END_ENDING,
		/** Nothing to do: an arm that is not tucked. */
		NOTHING
	}

	/**
	 * @param hasSession an arm is out
	 * @param tucked it is tucked
	 * @param ending it is retracting or melting
	 * @param heldMoved the held slot is not the arm's slot any more
	 * @param itemFits the slot's item fits the arm's stash (only asked when the selection did not move)
	 */
	public static OnClose onClose(boolean hasSession, boolean tucked, boolean ending, boolean heldMoved, boolean itemFits) {
		if (!hasSession) return OnClose.DEPLOY_BOUND;
		if (!tucked) return OnClose.NOTHING;
		if (ending) return OnClose.END_ENDING;
		if (heldMoved) return OnClose.END_AND_SCROLL;
		return itemFits ? OnClose.UNTUCK : OnClose.END_TOO_BIG;
	}
}
