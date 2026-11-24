package dev.morphreborn.client.arm.render;

/**
 * The morph skin's static on 3D arm geometry. The biomass bar's static flickers because it picks a new spot on the
 * noise sheet every two ticks; a model box's never does, because its texture offset only moves when its size changes
 * (ModelCapture seeds it from the box's index). Here every segment of every piece owns an offset on the 64-unit sheet
 * that jumps to a new spot every {@code cadence} ticks, so a held katana fizzes like the bar.
 * <p>
 * The jumps don't happen everywhere at once: a segment's moment is delayed by 0.2 tick per pixel of its feed
 * coordinate φ (px from the shoulder), so each re-pattern sweeps along the arm and tool at 5 px a tick, shoulder to
 * tip ({@code crawlDir} +1), or tip to shoulder while the arm feeds (−1). That crawl is what makes the biomass look as
 * if it flows into the weapon. A {@code crawlDir} of 0 re-patterns everything together (GUI icons).
 * <p>
 * An instance is plain mutable state, set once per draw ({@link #set}) and read for every segment; it is cheap to keep
 * one per geometry lambda.
 */
public final class Static {
	/** A flag {@link #cadence} adds for starving arms: they re-pattern twice as fast for a moment every 11 ticks. */
	public static final int FLICKER = 0x100;
	/** Starving's cadence: every 3 ticks, flickering. */
	public static final int STARVING = 3 | FLICKER;
	/** How often a starving arm's static flickers, and how much faster it runs meanwhile. */
	private static final float FLICKER_PERIOD = 11F;
	/** Ticks of delay per px of feed coordinate: the re-pattern front runs at 1/0.2 = 5 px a tick. */
	private static final float CRAWL = 0.2F;

	// ArmProtocol's states (section 4; the same numbers on both sides of the wire), written out so this class needs
	// nothing beyond itself: 0 NONE, 1 FORMING, 2 FORMED, 3 RESHAPING, 4 RETRACTING, 5 MELTING, 6 QUICKFORM.
	private static final int FORMED = 2;
	private static final int NONE = 0;

	private float time;
	private int cadence = 2;
	private boolean flicker;
	private float crawlDir = 1F;
	private int seed;

	/**
	 * Sets the clock ({@code t = Arms.TICK + partial}), the cadence ({@link #cadence}'s value, flags and all), the crawl
	 * direction and a per-player seed (the entity id), so two players' arms never show the same pattern.
	 */
	public Static set(float time, int cadence, float crawlDir, int seed) {
		this.time = time;
		this.cadence = Math.max(1, period(cadence));
		this.flicker = (cadence & FLICKER) != 0;
		this.crawlDir = crawlDir;
		this.seed = seed;
		return this;
	}

	/** The plain period in ticks of a {@link #cadence} value (without its flags). */
	public static int period(int cadence) {
		return cadence & 0xFF;
	}

	/** Segment {@code seg} of piece {@code part}'s u offset on the sheet now, at feed coordinate {@code phi} px. */
	public float u0(int part, int seg, float phi) {
		return (part * 37 + seg * 13 + frame(phi) * 29) & 63;
	}

	/** As {@link #u0}, its v offset. */
	public float v0(int part, int seg, float phi) {
		return (part * 11 + seg * 7 + frame(phi) * 17) & 63;
	}

	/**
	 * The pattern number at feed coordinate {@code phi}: {@code floor((t − 0.2·dir·φ) / cadence)}, plus the seed. During
	 * a starving flicker it counts at twice the rate, from numbers of its own, so the pattern stutters and then settles
	 * back.
	 */
	int frame(float phi) {
		float tau = time - CRAWL * crawlDir * phi;
		int frame = (int) Math.floor(tau / cadence);
		if (flicker) {
			float inWindow = tau - (float) Math.floor(tau / FLICKER_PERIOD) * FLICKER_PERIOD;
			if (inWindow < cadence) frame = 7919 + (int) Math.floor(tau * 2F / cadence);
		}
		return frame + seed;
	}

	/**
	 * How often the static re-patterns, in ticks (section 2.2): 1 while forming, reshaping, retracting, melting or
	 * quick-forming, in a special, or for a hit (a gain's 30-tick surge counts as a hit here); otherwise 3 when thinning
	 * ({@code lowStage} 1) and 3 with a flicker ({@link #STARVING}) when starving or failing (2, 3); otherwise 2 (idle).
	 * Movement wins over hunger: a starving arm that is swinging fizzes fast. Timelines that slow down partway (FORM's
	 * cadence 2 from tick 10) pass {@code FORMED} for that stretch. Pass the result to {@link #set} as it is.
	 */
	public static int cadence(int state, boolean special, boolean hit, int lowStage) {
		boolean moving = state != NONE && state != FORMED;
		if (moving || special || hit) return 1;
		if (lowStage >= 2) return STARVING;
		if (lowStage == 1) return 3;
		return 2;
	}
}
