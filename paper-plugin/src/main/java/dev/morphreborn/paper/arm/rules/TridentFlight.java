package dev.morphreborn.paper.arm.rules;

/**
 * The trident arm's flight, without a server (my design, 2026-11-16, 8.0.0-beta.2). A thrown trident is a projectile of the
 * module's own; this decides when its tendril takes hold and how it reels the trident in.
 * <ul>
 * <li>{@link Flight}: the outward flight. It ends, and the reel begins, when the trident has struck a creature, has rested in a block
 * for the settings' ticks, has flown the settings' ticks, or has gone further from its owner than the range.</li>
 * <li>{@link #reel}: one step of the pull. The trident comes to the hand and the hand never goes to the trident: nothing here (or in
 * the module) ever moves the player. The speed grows from {@link #START_SPEED} by the acceleration each tick up to the tier's fastest
 * and never carries the trident past the hand (the step is cut to the distance left), and the pull ends when the trident is within
 * the catch radius of the hand, or at the longest a pull may last.</li>
 * <li>Recall's sweep (which creatures are on its path) is {@link RecallSweep}.</li>
 * </ul>
 */
public final class TridentFlight {
	/** The pull starts this fast (blocks a tick), slower than the throw so the turn reads. */
	public static final double START_SPEED = 0.8;
	/** The trident is back when it is this close to the hand (blocks). */
	public static final double CATCH_RADIUS = 1.6;

	private TridentFlight() {}

	/** What the outward flight decided this tick. */
	public enum Cue {
		/** Still flying. */
		NONE,
		/** It struck a creature (or anything that took the hit): the tendril takes it back now. */
		STRUCK,
		/** It has rested in a block long enough. */
		STUCK,
		/** It has flown as long as the settings allow. */
		TIMEOUT,
		/** It got further from its owner than the range. */
		RANGE
	}

	/** One throw's outward flight, asked once a tick. */
	public static final class Flight {
		private final int start;
		private final int flightTicks, stuckTicks;
		private final double range;
		private int stuckFor;

		/**
		 * @param start the tick it was thrown
		 * @param settings the form's settings
		 * @param level the tier (the range depends on it)
		 */
		public Flight(int start, TridentSettings settings, int level) {
			this(start, settings.flightTicks(), settings.stuckTicks(), settings.range().at(level));
		}

		public Flight(int start, int flightTicks, int stuckTicks, double range) {
			this.start = start;
			this.flightTicks = Math.max(1, flightTicks);
			this.stuckTicks = Math.max(0, stuckTicks);
			this.range = range;
		}

		/**
		 * One tick of the flight.
		 *
		 * @param now the tick
		 * @param struck it struck something this flight
		 * @param inBlock it is stuck in a block this tick
		 * @param distance how far it is from its owner now (blocks)
		 */
		public Cue update(int now, boolean struck, boolean inBlock, double distance) {
			stuckFor = inBlock ? stuckFor + 1 : 0;
			if (struck) return Cue.STRUCK;
			if (inBlock && stuckFor > stuckTicks) return Cue.STUCK;
			if (distance > range) return Cue.RANGE;
			if (now - start >= flightTicks) return Cue.TIMEOUT;
			return Cue.NONE;
		}

		/** Ticks of flight left before it times out, never below 0 (what the clients are told as the throw's length). */
		public int ticksLeft(int now) {
			return Math.max(0, flightTicks - (now - start));
		}
	}

	/**
	 * One step of the pull.
	 *
	 * @param vx the velocity to give the trident (blocks a tick)
	 * @param arrived the trident is back: take it into the hand
	 */
	public record Step(double vx, double vy, double vz, boolean arrived) {}

	/**
	 * The pull's step {@code n} ticks into the reel (0 for the first).
	 *
	 * @param px the trident's place
	 * @param tx the hand's place
	 * @param n ticks since the reel began
	 * @param maxSpeed the tier's fastest pull (blocks a tick)
	 * @param accel how much faster each tick (blocks a tick)
	 * @param maxTicks the longest a pull lasts; at it the trident is back whatever the distance
	 */
	public static Step reel(double px, double py, double pz, double tx, double ty, double tz, int n, double maxSpeed, double accel, int maxTicks) {
		double dx = tx - px, dy = ty - py, dz = tz - pz;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist <= CATCH_RADIUS || n >= maxTicks) return new Step(0, 0, 0, true);
		double speed = Math.min(Math.max(START_SPEED, maxSpeed), START_SPEED + accel * Math.max(0, n));
		// never past the hand: the step is cut to the way left to the catch radius
		double step = Math.min(speed, dist - CATCH_RADIUS * 0.5);
		double k = step / dist;
		return new Step(dx * k, dy * k, dz * k, false);
	}

	/**
	 * How many ticks a pull takes to bring the trident {@code distance} blocks to a hand that stays where it is (the clients are told it,
	 * to draw the tendril's reel to the end of it). At least 1, at most {@code maxTicks}.
	 */
	public static int reelTicks(double distance, double maxSpeed, double accel, int maxTicks) {
		double left = distance;
		for (int n = 0; n < maxTicks; n++) {
			if (left <= CATCH_RADIUS) return Math.max(1, n);
			Step s = reel(0, 0, 0, left, 0, 0, n, maxSpeed, accel, maxTicks);
			left -= s.vx();
		}
		return Math.max(1, maxTicks);
	}
}
