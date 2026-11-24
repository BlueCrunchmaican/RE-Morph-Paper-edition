package dev.morphreborn.paper.arm.rules;

/**
 * When a bound held slot forms its arm after a join. The deploy runs a tick after the join repair, but a player who logs in
 * with a saved morph gets it back only a few ticks later (MorphManager.RESTORE_DELAY_TICKS, scheduled by its own join
 * handler, which has run by the time the deploy does). An arm formed in between would be popped again by that morph starting,
 * at every login of a morphed player. So the deploy of a player whose morph is about to be restored waits until it has been.
 * Pure, so the rule is tested.
 */
public final class JoinDeploy {
	private JoinDeploy() {}

	/**
	 * How many ticks more the deploy waits, counted from the tick it was due (one tick after the join).
	 *
	 * @param morphRestorePending the join handler left a saved morph to restore (it keeps {@code current} only when it will)
	 * @param restoreDelayTicks the restore's delay after the join
	 * @return 0 to deploy now; otherwise enough ticks for the restore to have run, plus one, so the morph state is settled
	 *         when the deploy asks (it then refuses silently for a player who is morphed: nothing is said at a login)
	 */
	public static long extraWaitTicks(boolean morphRestorePending, long restoreDelayTicks) {
		return morphRestorePending ? restoreDelayTicks + 1 : 0;
	}
}
