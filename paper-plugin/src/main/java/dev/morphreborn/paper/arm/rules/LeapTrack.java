package dev.morphreborn.paper.arm.rules;

/**
 * One Skyward Leap's flight as the mace arm follows it (my design, 2026-11-16): launched, in the air, landed. It tells the
 * module when the landing happened (the ground contact that sends the landing effect when no smash did) and when the leap's no-fall
 * protection is over, which is a short while after the landing, never before it: the fall damage of that landing is dealt in the
 * same packet that sets the player on the ground, and the module's tick runs after it, so the protection outlives the ground contact
 * by {@link #LAND_GRACE} ticks. Pure (ticks and booleans in), so it is unit tested without a server.
 * <p>
 * It also keeps the fall it has been shown (the ground contact resets the player's own to 0 before the module looks): the real one,
 * noted at the fall damage event where the game still has it ({@link #sawFall}), or else the largest sampled one; and whether a smash
 * already used the fall, so the landing effect is sent once: on the smash, or on the ground if none came.
 */
public final class LeapTrack {
	/** What an update found. */
	public enum Step { NONE, LANDED, OVER }

	private enum Phase { LAUNCHED, AIRBORNE, LANDED }

	/**
	 * Ticks a launch may stay on the ground before it is called a failed one (a launch the server or a block cancelled). Liftoff is only seen
	 * when the client reports it, so a laggy player is still "grounded" here for a while after the launch: 20 ticks (was 10; review 2026-11-16,
	 * and a decision of the same day) covers about a second of latency without leaving a failed launch's protection lying about long.
	 */
	public static final int LIFT_GRACE = 20;
	/** Ticks the protection outlives the ground contact. */
	public static final int LAND_GRACE = 2;

	private final int start, protectTicks;
	private Phase phase = Phase.LAUNCHED;
	private int landedAt;
	private double maxFall, realFall = -1;
	private boolean smashed;

	/**
	 * @param start the tick of the launch
	 * @param protectTicks how long the protection lasts at most from the launch (a leap that never lands: a void, a long flight)
	 */
	public LeapTrack(int start, int protectTicks) {
		this.start = start;
		this.protectTicks = Math.max(1, protectTicks);
	}

	/**
	 * One tick.
	 *
	 * @param grounded the player is on the ground
	 * @param ended something other than the ground ended the flight: water or lava (which cancel a fall), a ladder, a vehicle, gliding,
	 *            creative flight
	 * @param fall the player's fall distance now
	 */
	public Step update(int now, boolean grounded, boolean ended, double fall) {
		if (fall > maxFall) maxFall = fall;
		if (now - start > protectTicks || ended) return Step.OVER;
		switch (phase) {
			case LAUNCHED:
				if (!grounded) phase = Phase.AIRBORNE;
				else if (now - start > LIFT_GRACE) return Step.OVER;
				return Step.NONE;
			case AIRBORNE:
				if (!grounded) return Step.NONE;
				phase = Phase.LANDED;
				landedAt = now;
				return Step.LANDED;
			default:
				return now - landedAt >= LAND_GRACE ? Step.OVER : Step.NONE;
		}
	}

	/** The player has left the ground and not yet touched it again. */
	public boolean airborne() {
		return phase == Phase.AIRBORNE;
	}

	/** The largest fall distance seen (an update's, or a smash's {@link #smash}). */
	public double maxFall() {
		return maxFall;
	}

	/** The fall distance the game had at the landing's fall damage event (still intact there); the landing effect prefers it to any sample. */
	public void sawFall(double fall) {
		if (fall > realFall) realFall = fall;
		if (fall > maxFall) maxFall = fall;
	}

	/**
	 * The fall the landing effect carries: the real one when the damage event gave it, else the largest sample, and never under
	 * {@code planned} blocks (what the leap rose, which it fell back: the samples miss the last tick's descent, and a leap this short
	 * lands without a damage event at all).
	 */
	public double landingFall(double planned) {
		return realFall >= 0 ? realFall : Math.max(maxFall, planned);
	}

	/** A smash used the fall: its effect was sent, so the ground contact sends none. {@code fall} is the fall distance it had. */
	public void smash(double fall) {
		smashed = true;
		if (fall > maxFall) maxFall = fall;
	}

	public boolean smashed() {
		return smashed;
	}
}
