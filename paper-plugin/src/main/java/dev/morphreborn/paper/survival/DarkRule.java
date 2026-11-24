package dev.morphreborn.paper.survival;

/**
 * When Night Eyes counts as being in the dark, so it drains (and shows night vision) only then. Pure, so it is unit tested.
 * <p>
 * Bukkit's {@code Block#getLightFromSky} is the raw sky light, which does not drop at night (a field under the moon reads 15),
 * so the night and a thunderstorm are taken off it here. The player is dark at an effective light of {@code darkLevel} or less
 * (survival.yml {@code dark-light-level}, 6) and light again at {@code darkLevel + 3} or more, and it keeps its answer for at
 * least {@link #HOLD_TICKS} (5 seconds), so standing at a torch's edge doesn't flicker the effect and the billing.
 */
public final class DarkRule {
	/** The shortest time one answer is kept. */
	public static final int HOLD_TICKS = 100;
	/** How far above the dark level it has to get before it is light again. */
	public static final int HYSTERESIS = 3;
	/** What the night takes off the sky light (vanilla's sky darkness at midnight). */
	static final int NIGHT_DARKEN = 11;
	/** What a thunderstorm takes off the sky light by day. */
	static final int THUNDER_DARKEN = 5;

	private boolean dark;
	private long changedAt = Long.MIN_VALUE / 2;

	/** The light that counts: the brighter of the block light and the sky light after the night or the storm. */
	public static int effectiveLight(int blockLight, int skyLight, boolean night, boolean thunder) {
		int sky = night ? skyLight - NIGHT_DARKEN : thunder ? skyLight - THUNDER_DARKEN : skyLight;
		return Math.max(Math.max(0, blockLight), Math.max(0, sky));
	}

	/** Whether the player is in the dark now; {@code now} is a tick count that only has to increase. */
	public boolean update(long now, int blockLight, int skyLight, boolean night, boolean thunder, int darkLevel) {
		int light = effectiveLight(blockLight, skyLight, night, thunder);
		boolean want = dark ? light < darkLevel + HYSTERESIS : light <= darkLevel;
		if (want != dark && now - changedAt >= HOLD_TICKS) {
			dark = want;
			changedAt = now;
		}
		return dark;
	}

	/** The last answer, without a new reading. */
	public boolean dark() {
		return dark;
	}

	/** Forget it (the passive stopped): the next reading answers at once. */
	public void reset() {
		dark = false;
		changedAt = Long.MIN_VALUE / 2;
	}
}
