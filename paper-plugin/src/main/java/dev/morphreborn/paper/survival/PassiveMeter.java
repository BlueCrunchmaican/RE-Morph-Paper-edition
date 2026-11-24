package dev.morphreborn.paper.survival;

import dev.morphreborn.paper.arm.rules.DrainAccumulator;
import java.util.HashMap;
import java.util.Map;

/**
 * One player's bookkeeping for one passive (my rule, 2026-11-16: a passive uses no biomass until it acts). Pure, so it is
 * unit tested; {@link Passives} does the actual spending (all of it or nothing, through SurvivalMode.armSpend).
 * <ul>
 *   <li>A drain a minute is counted exactly in milli by the arm code's {@link DrainAccumulator} and ticked only on the
 *       runs in which the passive is acting, so a passive that is idle accrues nothing.</li>
 *   <li>A window: "for each second of exposure" (Fire Shedding, a re-applied poison) bills the first event and lets the
 *       rest of that second through free. It opens only once the bill is paid, so a refused payment is asked again at the
 *       next event instead of being forgiven for a second; and it is a window from the first event, never from the last,
 *       so a flurry of events is one bill and toggling the passive cannot reset it for a gain.</li>
 *   <li>A memory of things already paid for (Scavenger's item entities), forgotten after their lifetime so it stays small.</li>
 * </ul>
 */
public final class PassiveMeter {
	private final DrainAccumulator drain = new DrainAccumulator();
	private final Map<String, Long> windows = new HashMap<>();
	private final Map<String, Long> paidUntil = new HashMap<>();

	/**
	 * {@code ticks} of acting at {@code milliPerMinute}; returns the whole milli now due, which the caller bills. A rate of 0
	 * or less accrues nothing. After {@code T} ticks of acting exactly {@code floor(T * rate / 1200)} milli has come due.
	 */
	public long drain(long milliPerMinute, int ticks) {
		for (int i = 0; i < ticks; i++) drain.tick(milliPerMinute);
		return drain.takeDue();
	}

	/** Not acting any more: what is left over (under 1 milli) is dropped. */
	public void stopDrain() {
		drain.reset();
	}

	/** True when {@code key}'s window, opened less than {@code length} ticks before {@code now}, is still open: free to go on. */
	public boolean windowOpen(String key, long now, int length) {
		Long at = windows.get(key);
		return at != null && now >= at && now - at < length;
	}

	/** Open {@code key}'s window at {@code now} (after paying for its first event). */
	public void openWindow(String key, long now) {
		windows.put(key, now);
		if (windows.size() > 64) windows.values().removeIf(at -> now - at > 1200);
	}

	/** True when {@code key} was paid for and not yet forgotten. */
	public boolean paid(String key, long now) {
		Long until = paidUntil.get(key);
		return until != null && now < until;
	}

	/** Remember {@code key} as paid for until {@code ttl} ticks from {@code now}. */
	public void remember(String key, long now, long ttl) {
		paidUntil.put(key, now + ttl);
		if (paidUntil.size() > 256) paidUntil.values().removeIf(until -> now >= until);
	}

	/** Forget what has expired; true when nothing paid for is left (an offline player's meter can then be dropped). */
	public boolean prune(long now) {
		paidUntil.values().removeIf(until -> now >= until);
		return paidUntil.isEmpty();
	}

	/** How many things are remembered as paid for (for tests). */
	int remembered() {
		return paidUntil.size();
	}
}
