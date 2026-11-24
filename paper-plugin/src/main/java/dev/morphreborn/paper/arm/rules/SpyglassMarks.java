package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;

/**
 * The creatures one player has marked with the Tendril Spyglass (my design, 2026-11-16): which they are, when each was last
 * looked at, and who goes when a new one comes over the cap. Pure (entity ids and ticks only), so it is unit tested without a server;
 * the module feeds it what the look found and does what it says (the glow, the effect messages).
 * <p>
 * A creature that is looked at again is refreshed; one that is not looked at lasts {@code life} ticks from the last glance. When the cap
 * is full the one seen longest ago goes. {@link #see} also says whether to tell the client again (a mark is announced when it is made
 * and every {@link SpyglassRules#resendTicks} (20 ticks at most, less for a short mark life) while it is refreshed, so the client's own timer, which starts at each message, never runs
 * out under a mark the server still holds). The message says how long the mark has left (its {@code ticks}), so the client's clock ends
 * where the server's does; {@link #stale} gives the marks whose clock the client has wrong after a look moved on (2026-11-16: the
 * review's drift, a HUD mark gone up to a second before its glow).
 */
public final class SpyglassMarks {
	private static final int[] NONE = new int[0];

	private final ArrayList<Mark> marks = new ArrayList<>(3);

	private static final class Mark {
		final int id;
		int seen, told;
		/** The tick the client's own timer ends at, from the last message it was sent (what that message said was left). */
		int until;

		Mark(int id, int now, int life) {
			this.id = id;
			this.seen = now;
			this.told = now;
			this.until = now + life;
		}
	}

	/**
	 * What {@link #see} did.
	 *
	 * @param announce tell the client of this mark (new, or not told for {@link SpyglassRules#RESEND_TICKS})
	 * @param isNew it wasn't marked before
	 * @param evicted the entity id that was dropped to make room, or {@link #NO_ONE}
	 */
	public record Seen(boolean announce, boolean isNew, int evicted) {}

	/** {@link Seen#evicted} when nobody was. */
	public static final int NO_ONE = Integer.MIN_VALUE;

	/**
	 * The creature {@code id} was looked at at tick {@code now}, with room for {@code cap} marks (at least one); {@code life} is how long a
	 * mark outlasts a glance, which an announcement (the client's timer) is told as it has left: all of it, as the glance is now.
	 */
	public Seen see(int id, int now, int cap, int life) {
		return see(id, now, cap, life, SpyglassRules.RESEND_TICKS);
	}

	/** As {@link #see(int, int, int, int)}, with the client told again after {@code resend} ticks ({@link SpyglassRules#resendTicks}). */
	public Seen see(int id, int now, int cap, int life, int resend) {
		cap = Math.max(1, cap);
		for (Mark m : marks) {
			if (m.id != id) continue;
			m.seen = now;
			boolean tell = now - m.told >= resend;
			if (tell) {
				m.told = now;
				m.until = now + life;
			}
			return new Seen(tell, false, NO_ONE);
		}
		int evicted = NO_ONE;
		if (marks.size() >= cap) { // the cap is fixed for as long as the accessory is on, so one goes at a time: the one seen longest ago
			Mark oldest = marks.get(0);
			for (Mark m : marks) {
				if (m.seen < oldest.seen) oldest = m;
			}
			marks.remove(oldest);
			evicted = oldest.id;
		}
		marks.add(new Mark(id, now, life));
		return new Seen(true, true, evicted);
	}

	/**
	 * The marks whose glance outlasts what their client was last told ({@code seen + life} past its timer), other than {@code except}
	 * (the one being looked at: it is still being refreshed on its own interval), in the order marked; each is taken as told now, with its
	 * remaining life from {@link #left}. The same shared empty array when there are none, so a scan with nothing to say allocates nothing.
	 */
	public int[] stale(int now, int life, int except) {
		int n = 0;
		for (Mark m : marks) {
			if (m.id != except && m.seen + life > m.until) n++;
		}
		if (n == 0) return NONE;
		int[] out = new int[n];
		int at = 0;
		for (Mark m : marks) {
			if (m.id != except && m.seen + life > m.until) {
				m.told = now;
				m.until = m.seen + life;
				out[at++] = m.id;
			}
		}
		return out;
	}

	/** How many ticks mark {@code id} has left at {@code now} if it is not looked at again, given a glance's {@code life}; 0 when it isn't marked. */
	public int left(int id, int now, int life) {
		for (Mark m : marks) {
			if (m.id == id) return Math.max(1, m.seen + life - now);
		}
		return 0;
	}

	/** Every mark not looked at for {@code life} ticks, removed; their entity ids (the same shared empty array when there are none). */
	public int[] expire(int now, int life) {
		if (marks.isEmpty()) return NONE;
		int n = 0;
		for (Mark m : marks) {
			if (now - m.seen >= life) n++;
		}
		if (n == 0) return NONE;
		int[] out = new int[n];
		int at = 0;
		for (int i = marks.size() - 1; i >= 0; i--) {
			if (now - marks.get(i).seen >= life) {
				out[at++] = marks.remove(i).id;
			}
		}
		return out;
	}

	/** Drops the marks over a cap that has gone down (the one seen longest ago first); their ids, the same shared empty array when none. */
	public int[] trim(int cap) {
		cap = Math.max(1, cap);
		if (marks.size() <= cap) return NONE;
		int[] out = new int[marks.size() - cap];
		for (int k = 0; k < out.length; k++) {
			Mark oldest = marks.get(0);
			for (Mark m : marks) {
				if (m.seen < oldest.seen) oldest = m;
			}
			marks.remove(oldest);
			out[k] = oldest.id;
		}
		return out;
	}

	/** Whether {@code id} is marked. */
	public boolean has(int id) {
		for (Mark m : marks) {
			if (m.id == id) return true;
		}
		return false;
	}

	/** Drop one mark; whether it was there. */
	public boolean remove(int id) {
		return marks.removeIf(m -> m.id == id);
	}

	/** Drop them all; their entity ids. */
	public int[] clear() {
		if (marks.isEmpty()) return NONE;
		int[] out = ids();
		marks.clear();
		return out;
	}

	/** The marked entity ids, in the order they were marked. */
	public int[] ids() {
		if (marks.isEmpty()) return NONE;
		int[] out = new int[marks.size()];
		for (int i = 0; i < out.length; i++) out[i] = marks.get(i).id;
		return out;
	}

	public int size() {
		return marks.size();
	}

	public boolean isEmpty() {
		return marks.isEmpty();
	}
}
