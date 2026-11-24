package dev.morphreborn.client.gui;

/**
 * Where the bottom-left HUD cluster (the biomass bar, the frenzy meter) may go, apart from the drawing so it is tested without a game
 * (UiLayoutTest). They were fixed at x 6 and 100 wide, which runs into the hotbar (it starts at half the width less 91, and its offhand slot
 * 29 further left) on any screen under about 394 scaled px wide; the bar now takes the room the hotbar leaves, and rises above the survival
 * HUD (hearts, armour, XP) when there is not even a little.
 */
public final class HudLayout {
	/** The cluster's left edge (GUI px, before the cluster's own scale). */
	public static final int LEFT = 6;
	/** The hotbar's half width, and the offhand slot's width plus its gap, which sits left of it when it holds something. */
	public static final int HOTBAR_HALF = 91, OFFHAND = 29;
	/** The gap kept between the cluster and the hotbar. */
	public static final int GAP = 4;
	/** The narrowest the biomass bar gets before the cluster is raised instead. */
	public static final int MIN_BAR = 56;
	/** How far the cluster is raised: over the hearts and armour rows (the survival HUD's top is about 59 up). */
	public static final int LIFT = 60;

	/** How long a raised cluster stays raised after what raised it is gone (ns): ten seconds. */
	public static final long LIFT_HOLD_NS = 10_000_000_000L;

	private HudLayout() {}

	/**
	 * Keeps the cluster's lift from flickering. The room beside the hotbar changes with things the player does all the time (an item in the
	 * offhand, an arm out, the lantern lit), so near the threshold the bar and the meter beside it jumped 60 px up and down with each; a
	 * raised cluster now stays raised for {@link #LIFT_HOLD_NS} after the last frame that needed it. Raising is immediate.
	 */
	public static final class Latch {
		private long heldUntil = Long.MIN_VALUE;

		/** The lift to use this frame: {@code wanted} (0 or {@link #LIFT}) when raised, else {@link #LIFT} while the hold lasts, else 0. */
		public int apply(int wanted, long nowNs) {
			if (wanted > 0) {
				heldUntil = nowNs + LIFT_HOLD_NS;
				return wanted;
			}
			return heldUntil != Long.MIN_VALUE && nowNs < heldUntil ? LIFT : 0;
		}
	}

	/** The right edge (screen px) the cluster must stay left of: the hotbar's (and the offhand slot's, when it holds an item) left edge, less a gap. */
	public static float maxRight(int guiW, boolean offhand) {
		return guiW / 2F - HOTBAR_HALF - (offhand ? OFFHAND : 0) - GAP;
	}

	/**
	 * How much of the screen's bottom the cluster is raised by (screen px: 0 or {@link #LIFT}): raised when the room left of the hotbar, at the
	 * cluster's scale {@code s}, is under {@code needed} of its own px (the cluster's least width plus whatever sits past its end).
	 */
	public static int lift(int guiW, float s, boolean offhand, int needed) {
		return (maxRight(guiW, offhand) / s - LEFT - needed) >= 0 ? 0 : LIFT;
	}

	/**
	 * The biomass bar's width (the cluster's own px): the wanted width, or what is left of the room beside the hotbar, never under
	 * {@link #MIN_BAR}; when it was raised ({@code lifted}) the whole width of the screen is free.
	 *
	 * @param past the room needed past the bar's end (the arm icon), cluster px
	 */
	public static int barWidth(int guiW, float s, boolean offhand, boolean lifted, int wanted, int past) {
		return barWidth(guiW, s, offhand, lifted, wanted, past, MIN_BAR);
	}

	/**
	 * {@link #barWidth(int, float, boolean, boolean, int, int)} with the least width given: the bar can be no narrower than its own amount text
	 * (a capacity of 51200 shows "51200 / 51200", 73 px, and the amount is right-aligned to the bar's end, so a 57 px bar ran it off the screen's
	 * left edge at a scaled width of 320). Never under {@link #MIN_BAR}.
	 */
	public static int barWidth(int guiW, float s, boolean offhand, boolean lifted, int wanted, int past, int least) {
		float room = (lifted ? guiW - 4F : maxRight(guiW, offhand)) / s - LEFT - 2 - past;
		int floor = Math.max(MIN_BAR, least);
		return Math.max(floor, Math.min(wanted, (int) Math.floor(room)));
	}
}
