package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Who is bleeding and when the next pulse is due: the claws' Bleed, apart from the server so its timing is tested. A bleed is a
 * count of pulses, one every {@code interval} ticks; a new hit on someone already bleeding tops the count up to the new hit's
 * (never adds to it, so three slashes of one Rake are one bleed, not three) and leaves the pulse clock alone, so hitting
 * again never delays the next pulse. A pulse that could not land because the creature was still inside its hurt window is
 * tried again next tick ({@link #retry}), up to {@link #MAX_RETRIES} times, then given up as a pulse.
 *
 * @param <K> what a bleed is keyed by (the wounded and the one who wounded them)
 */
public final class BleedLedger<K> {
	/** How many ticks in a row one pulse may wait out a hurt window before it is counted as spent. */
	public static final int MAX_RETRIES = 20;

	private static final class Entry {
		int pulsesLeft, nextPulse, interval, retries;
	}

	private final Map<K, Entry> entries = new HashMap<>();

	/**
	 * Start or refresh a bleed. True when it is new. A refresh raises the pulses left to {@code pulses} when that is more and keeps
	 * the clock; with {@code pulses} of 0 or less it does nothing.
	 */
	public boolean start(K key, int now, int pulses, int intervalTicks) {
		if (pulses <= 0) return false;
		Entry e = entries.get(key);
		if (e == null) {
			e = new Entry();
			e.pulsesLeft = pulses;
			e.interval = Math.max(1, intervalTicks);
			e.nextPulse = now + e.interval;
			entries.put(key, e);
			return true;
		}
		e.pulsesLeft = Math.max(e.pulsesLeft, pulses);
		return false;
	}

	/** The bleeds whose pulse is due at {@code now}, in no particular order. */
	public List<K> due(int now) {
		List<K> out = new ArrayList<>();
		for (Map.Entry<K, Entry> e : entries.entrySet()) {
			if (e.getValue().nextPulse <= now) out.add(e.getKey());
		}
		return out;
	}

	/**
	 * A pulse landed (or was given up): one fewer is left and the next is {@code interval} ticks on. True when that was the last
	 * and the bleed is over (it is removed).
	 */
	public boolean pulsed(K key, int now) {
		Entry e = entries.get(key);
		if (e == null) return true;
		e.retries = 0;
		e.pulsesLeft--;
		if (e.pulsesLeft <= 0) {
			entries.remove(key);
			return true;
		}
		e.nextPulse = now + e.interval;
		return false;
	}

	/** The pulse could not land this tick: try again next tick, and when it has been tried {@link #MAX_RETRIES} times, count it as spent. True when that ended the bleed. */
	public boolean retry(K key, int now) {
		Entry e = entries.get(key);
		if (e == null) return true;
		if (++e.retries > MAX_RETRIES) return pulsed(key, now);
		e.nextPulse = now + 1;
		return false;
	}

	public void stop(K key) {
		entries.remove(key);
	}

	/** Stop every bleed {@code drop} says to; returns how many ended. */
	public int stopIf(java.util.function.Predicate<K> drop) {
		int n = 0;
		for (Iterator<K> it = entries.keySet().iterator(); it.hasNext(); ) {
			if (drop.test(it.next())) {
				it.remove();
				n++;
			}
		}
		return n;
	}

	public void clear() {
		entries.clear();
	}

	public boolean bleeding(K key) {
		return entries.containsKey(key);
	}

	public int size() {
		return entries.size();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** How many pulses are left for a bleed, 0 when it isn't bleeding. */
	public int pulsesLeft(K key) {
		Entry e = entries.get(key);
		return e == null ? 0 : e.pulsesLeft;
	}

	/** How many ticks a bleed has left from {@code now} to its last pulse (0 when it isn't bleeding): the time a client shows it for. */
	public int ticksLeft(K key, int now) {
		Entry e = entries.get(key);
		if (e == null) return 0;
		return Math.max(0, e.nextPulse - now) + (e.pulsesLeft - 1) * e.interval;
	}

	/** Every key bleeding now. */
	public List<K> keys() {
		return new ArrayList<>(entries.keySet());
	}
}
