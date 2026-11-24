package dev.morphreborn.paper.arm.rules;

/**
 * The steady drain of a formed arm (1 biomass a minute) and a lit lantern (0.5 a minute), counted exactly. A minute is
 * 1200 ticks, and 1000 milli a minute is 0.833 milli a tick, which no whole number per tick can bill. So every tick adds
 * the rate to a remainder, and each full 1200 of it is one milli due: after T ticks exactly
 * {@code floor(T * rate / 1200)} milli has come due, never a milli more or less, whatever the rate.
 * <p>
 * The caller bills what is due every 20 ticks ({@link #takeDue()}) so a balance doesn't change every tick. Forming and
 * retracting can't dodge the drain: a session's leftover remainder (less than 1 milli) is the most a retract ever
 * drops ({@link #reset()}). AFK doesn't pause it. One accumulator per player; pure, so it is unit tested.
 */
public final class DrainAccumulator {
	/** Ticks in a minute: the rate is milli a minute. */
	public static final int TICKS_PER_MINUTE = 1200;

	/** Rate-ticks not yet worth a whole milli; always below {@link #TICKS_PER_MINUTE}. */
	private long remainder;
	/** Whole milli come due and not yet billed. */
	private long due;

	/**
	 * One tick at {@code rateMilliPerMinute}. Call it once a tick per thing draining (the tool and the lantern add up),
	 * or once with their sum; both come to the same total. A rate of 0 or less adds nothing.
	 */
	public void tick(long rateMilliPerMinute) {
		if (rateMilliPerMinute <= 0) return;
		remainder += rateMilliPerMinute;
		due += remainder / TICKS_PER_MINUTE;
		remainder %= TICKS_PER_MINUTE;
	}

	/** The milli come due since the last call, which the caller now bills (partially: whatever the balance allows). */
	public long takeDue() {
		long d = due;
		due = 0;
		return d;
	}

	/** What is due right now without taking it (for /morph arm info). */
	public long peekDue() {
		return due;
	}

	/** The part-milli carried to the next tick, in rate-ticks out of 1200 (for /morph arm info). */
	public long remainder() {
		return remainder;
	}

	/**
	 * Forget everything, the remainder and anything due. At a retract, bill {@link #takeDue()} first: what's dropped
	 * here is then under 1 milli.
	 */
	public void reset() {
		remainder = 0;
		due = 0;
	}
}
