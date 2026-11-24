package dev.morphreborn.client.arm.render;

/**
 * Swellings that travel along an arm form, the 3D version of the biomass bar's pulsing seam. Everything is measured on
 * the feed coordinate φ, in px from the shoulder: the sheath covers 0..8, the fist 8..12, then the tool to its tip
 * ({@code feedLength}, about 36.5 on a katana). A pulse is a Gaussian bump {@code A·exp(−(φ−p)²/(2σ²))} whose centre p
 * moves at a fixed speed, and the geometry reads its strength wherever it is: tubes swell, a blade thickens and its
 * hamon lifts, veins brighten.
 * <ul>
 *   <li>{@link Kind#IDLE}: the arm breathing. Two pulses every 46 ticks, half a period apart, shoulder to tip at
 *       2.2 px a tick (σ 3, amplitude 0.45), offset per player.</li>
 *   <li>{@link Kind#SPEND}: every billed action (a hit, a break, a special, a bolt). It leaves the fist where the arm
 *       hands biomass to the tool (φ 12) and runs to the tip at 5 px a tick (σ 2, 0.60), so a katana's reaches the tip
 *       about 4.9 ticks after the hit.</li>
 *   <li>{@link Kind#FEED}: biomass coming in (a heal's arrival, a kill, a gain), tip to shoulder at 4 px a tick
 *       (σ 2.5, 0.80).</li>
 * </ul>
 * Every pulse eases in over its first tick (a bump never pops in at full strength) and fades out as it runs past its
 * end. Pure maths with no state: the draw passes the pulse events it has (kind and start, in ticks).
 * <p>
 * The pulse speed (2026-11-16: each player picks how fast their pulses run, and everyone sees it): every
 * reader takes a {@code speed}, the multiple of the usual (1 is the usual, 0.25 to 3 the range a player may ask for).
 * It runs the pulses' clock fast or slow, not just their travel: a pulse's whole age (its speed along the arm, its
 * ease-in, the idle period, the lifetime) is {@code speed} times the real ticks since it started, so at 2 the idle
 * train repeats every 23 real ticks and a SPEND reaches a katana's tip in about 2.5.
 */
public final class Pulse {
	public enum Kind {
		IDLE(2.2F, 3.0F, 0.45F),
		SPEND(5.0F, 2.0F, 0.60F),
		FEED(4.0F, 2.5F, 0.80F);

		/** px per tick, the bump's σ in px, and its amplitude. */
		public final float speed, sigma, amplitude;

		Kind(float speed, float sigma, float amplitude) {
			this.speed = speed;
			this.sigma = sigma;
			this.amplitude = amplitude;
		}
	}

	/** Where the fist ends and the tool begins on the feed coordinate: SPEND pulses start here. */
	public static final float TOOL_START = 12F;
	/** IDLE's period, and the second pulse's offset in it. */
	public static final float IDLE_PERIOD = 46F, IDLE_OFFSET = 23F;
	/** Ticks a pulse takes to swell to full strength once it starts. */
	private static final float EASE_IN = 1F;

	private static final Kind[] KINDS = Kind.values();

	private Pulse() {}

	/**
	 * The total swelling at feed coordinate {@code phi} at {@code time}: the two IDLE pulses (offset by
	 * {@code seedPhase}, the player's phase in radians: a full turn shifts them a whole period) plus {@code count}
	 * events from {@code events}, stored as (kind ordinal, start tick) pairs. Feed into the effects as
	 * {@code radius × (1 + amp)}, {@code brightness × (1 + 1.6·amp)} and so on.
	 */
	public static float amp(float phi, float time, float seedPhase, float[] events, int count, float feedLength) {
		return amp(phi, time, seedPhase, events, count, feedLength, 1F);
	}

	/** {@link #amp(float, float, float, float[], int, float)} with the pulses running at {@code speed} times the usual. */
	public static float amp(float phi, float time, float seedPhase, float[] events, int count, float feedLength, float speed) {
		return idle(phi, time, seedPhase, feedLength, speed) + events(phi, time, events, count, feedLength, speed);
	}

	/** The IDLE pulses alone (a surge doubles them, so the draw may want them apart). */
	public static float idle(float phi, float time, float seedPhase, float feedLength) {
		return idle(phi, time, seedPhase, feedLength, 1F);
	}

	/** {@link #idle(float, float, float, float)} at {@code speed} times the usual: the clock the train is read on runs that much faster. */
	public static float idle(float phi, float time, float seedPhase, float feedLength, float speed) {
		float shift = ArmMath.frac(seedPhase / ArmMath.TAU) * IDLE_PERIOD;
		float sum = 0F;
		for (int train = 0; train < 2; train++) {
			float age = time * clampSpeed(speed) - shift - train * IDLE_OFFSET;
			age -= (float) Math.floor(age / IDLE_PERIOD) * IDLE_PERIOD; // the last start at or before now
			sum += at(Kind.IDLE, phi, age, feedLength);
		}
		return sum;
	}

	/** The event pulses alone: {@code count} (kind ordinal, start tick) pairs from {@code events}. */
	public static float events(float phi, float time, float[] events, int count, float feedLength) {
		return events(phi, time, events, count, feedLength, 1F);
	}

	/** {@link #events(float, float, float[], int, float)} at {@code speed} times the usual: each pulse's age is that much more than the ticks since it started. */
	public static float events(float phi, float time, float[] events, int count, float feedLength, float speed) {
		if (events == null) return 0F;
		float s = clampSpeed(speed);
		int n = Math.min(count, events.length / 2);
		float sum = 0F;
		for (int i = 0; i < n; i++) {
			int kind = (int) events[2 * i];
			if (kind < 0 || kind >= KINDS.length) continue;
			sum += at(KINDS[kind], phi, (time - events[2 * i + 1]) * s, feedLength);
		}
		return sum;
	}

	/** The slowest and fastest a pulse may run, as a multiple of the usual (ArmProtocol.PULSE_SPEED_MIN / MAX in percent). */
	public static final float MIN_SPEED = 0.25F, MAX_SPEED = 3F;

	/** {@code speed} kept to {@link #MIN_SPEED} .. {@link #MAX_SPEED}; NaN and anything else unusable is the usual speed (1). */
	public static float clampSpeed(float speed) {
		return Float.isNaN(speed) ? 1F : Math.clamp(speed, MIN_SPEED, MAX_SPEED);
	}

	/** One pulse of {@code kind}, {@code age} ticks after it started, as felt at {@code phi}: {@code A·G(φ)}. */
	public static float at(Kind kind, float phi, float age, float feedLength) {
		if (!(age >= 0F) || !alive(kind, age, feedLength)) return 0F;
		float p = centre(kind, age, feedLength);
		return kind.amplitude * ArmMath.sm(age / EASE_IN) * ArmMath.gauss(phi - p, kind.sigma);
	}

	/** Where a pulse's centre is {@code age} ticks after it started. */
	public static float centre(Kind kind, float age, float feedLength) {
		return switch (kind) {
			case IDLE -> kind.speed * age;
			case SPEND -> TOOL_START + kind.speed * age;
			case FEED -> feedLength - kind.speed * age;
		};
	}

	/** Whether a pulse that age is still on the arm (its centre less than 3σ past its end): older ones can be dropped. */
	public static boolean alive(Kind kind, float age, float feedLength) {
		return age >= 0F && age <= lifetime(kind, feedLength);
	}

	/** Ticks from a pulse's start until its centre is 3σ past the end it runs to. */
	public static float lifetime(Kind kind, float feedLength) {
		float run = switch (kind) {
			case IDLE -> feedLength;
			case SPEND -> Math.max(0F, feedLength - TOOL_START);
			case FEED -> feedLength;
		};
		return (run + 3F * kind.sigma) / kind.speed;
	}
}
