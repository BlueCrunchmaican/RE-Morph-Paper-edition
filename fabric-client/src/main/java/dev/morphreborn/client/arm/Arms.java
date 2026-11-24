package dev.morphreborn.client.arm;

import dev.morphreborn.client.net.Wire;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Everything the client knows about arm forms, all of it from the server: every player's arm (S_ARM, keyed by UUID with
 * the entity id alongside), this player's own arsenal (S_ARSENAL), and the effects as they arrive (S_ARM_FX, handed to
 * listeners). The client never decides an arm on its own: it draws what it is told and counts the timelines on between
 * packets.
 * <p>
 * Arm state can't live with the morph states ({@code MorphClient.STATES}): those are dropped whenever a player is in
 * their own body, and arms only exist in your own body. So it has its own maps here.
 * <p>
 * The clock: {@link #TICK} counts game ticks while a world is loaded and the game isn't paused (ArmClientInit advances
 * it), and every tick stamp in the arm code is on it. All of this runs on the client thread (packets, ticks, rendering).
 */
public final class Arms {
	/** The arm clock: game ticks since the game started, frozen while paused or out of a world. */
	public static long TICK;

	/** A finished retract or melt waits this long for the server's own NONE before ending here. */
	private static final int END_GRACE_TICKS = 20;

	private static final Map<UUID, ArmState> STATES = new HashMap<>();
	private static final Map<Integer, ArmState> BY_ENTITY = new HashMap<>();
	/**
	 * Players whose arm state has reached us in this view already, including ones that have since gone empty and been dropped from
	 * {@link #STATES}. A state arriving for one of them is a live change (an accessory switched on); one for a player not in here
	 * is a first sight (joining, or entering view), which may be mid-way through. Cleared with {@link #forget} and {@link #clear}.
	 */
	private static final Set<UUID> SEEN = new java.util.HashSet<>();
	/** This player's own UUID, once the client knows it (set by {@link #clearOthers}, or read from {@link #ownLookup}); null before. */
	private static @Nullable UUID own;
	/**
	 * Where to ask who this player is (ArmClientInit hands in the local player). {@link #clear} wipes {@link #own} on joining,
	 * possibly after the level change that had set it, so the first state's decision asks here instead of trusting what was kept.
	 */
	private static @Nullable Supplier<@Nullable UUID> ownLookup;
	private static final Collection<ArmState> VIEW = Collections.unmodifiableCollection(STATES.values());
	private static volatile ArmArsenal arsenal = ArmArsenal.EMPTY;
	private static final List<Consumer<ArmFxEvent>> FX_LISTENERS = new CopyOnWriteArrayList<>();
	/** Listener classes that threw once already, so a broken one doesn't fill the log with every effect. */
	private static final Set<Class<?>> FAILED = ConcurrentHashMap.newKeySet();

	private Arms() {}

	/** The arm clock at this frame: {@link #TICK} plus the partial tick (the game-time one, frozen while paused). */
	public static float time(float partialTick) {
		return TICK + partialTick;
	}

	/**
	 * Ticks since {@code sinceTick} at this frame. Subtracts in whole ticks first, so it stays exact however long the game
	 * has run, where {@code time(pt) - sinceTick} would lose the fraction in a float.
	 */
	public static float age(long sinceTick, float partialTick) {
		return (float) (TICK - sinceTick) + partialTick;
	}

	public static @Nullable ArmState state(UUID player) {
		return STATES.get(player);
	}

	/** The state of the player with this entity id, as of the last S_ARM about them. */
	public static @Nullable ArmState stateByEntity(int entityId) {
		return BY_ENTITY.get(entityId);
	}

	/** Every player's state the server has told us about (a live, read-only view: copy it before changing anything). */
	public static Collection<ArmState> states() {
		return VIEW;
	}

	public static ArmArsenal arsenal() {
		return arsenal;
	}

	// ------------------------------------------------------------------ packets

	/** S_ARM: a player's arm changed (or the server resends it to someone arriving). A NONE with nothing left to show clears it. */
	public static void handleState(Wire.In in) {
		put(ArmState.read(in, TICK));
	}

	/** S_ARM_FX: hands the effect to every listener; one that throws is logged once and the rest still get it. */
	public static void handleFx(Wire.In in) {
		ArmFxEvent event = ArmFxEvent.read(in, TICK);
		for (Consumer<ArmFxEvent> listener : FX_LISTENERS) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				if (FAILED.add(listener.getClass())) {
					// the mod's own logger by name: MorphClient.LOGGER is the same one, without loading the whole client here
					org.slf4j.LoggerFactory.getLogger("RE:Morph").error("An arm effect listener failed (kind {}); later failures of it aren't logged", event.kind(), e);
				}
			}
		}
	}

	/** S_ARSENAL: this player's own forms, costs and waits. */
	public static void handleArsenal(Wire.In in) {
		arsenal = ArmArsenal.read(in, TICK);
	}

	/**
	 * C_ARM: form, retract or switch an accessory ({@code ArmProtocol.ACTION_*}); the string goes with FORM (the form) and with
	 * the accessory actions (the accessory's id), and is empty with every other action; mode steps carry a varInt after it.
	 */
	public static Wire.Out requestPacket(int action, String form) {
		return requestPacket(action, form, 0);
	}

	/** As {@link #requestPacket(int, String)}; {@code step} (+1 or -1) goes after the string with ACTION_ACCESSORY_MODE, and (1 held, 0 let go) with ACTION_ACCESSORY_HOLD. */
	public static Wire.Out requestPacket(int action, String form, int step) {
		boolean named = action == ArmProtocol.ACTION_FORM || action == ArmProtocol.ACTION_ACCESSORY_ON || action == ArmProtocol.ACTION_ACCESSORY_OFF
				|| action == ArmProtocol.ACTION_ACCESSORY_MODE || action == ArmProtocol.ACTION_ACCESSORY_HOLD;
		String id = named && form != null ? form : "";
		if (id.length() > ArmProtocol.MAX_FORM_ID) id = id.substring(0, ArmProtocol.MAX_FORM_ID);
		Wire.Out out = new Wire.Out(Wire.C_ARM).varInt(action).string(id);
		if (action == ArmProtocol.ACTION_ACCESSORY_MODE) out.varInt(Integer.signum(step));
		else if (action == ArmProtocol.ACTION_ACCESSORY_HOLD) out.varInt(step > 0 ? 1 : 0); // a held key's 1 or 0 (the spyglass's zoom)
		return out;
	}

	/** Receives every arm effect as it arrives, on the client thread. */
	public static void addFxListener(Consumer<ArmFxEvent> listener) {
		FX_LISTENERS.add(listener);
	}

	// ------------------------------------------------------------------ bookkeeping

	private static void put(ArmState state) {
		ArmState old = STATES.get(state.subject);
		if (old != null && BY_ENTITY.get(old.entityId) == old) BY_ENTITY.remove(old.entityId);
		// the lantern's retract animation: going out now (remember what it was: the server sends level and brightness 0
		// once it is out), still drawing back from an earlier state, or lit again before it had finished drawing back
		if (old != null) {
			if (!state.lanternLit && old.lanternLit) {
				state.lanternOutAt = TICK;
				state.lanternOutLevel = old.lanternLevel;
				state.lanternOutBright = old.lanternBright;
				state.lanternOutFrom = old.lanternAge(0F);
			} else if (!state.lanternLit && state.lanternOutAt == Long.MIN_VALUE) {
				state.lanternOutAt = old.lanternOutAt;
				state.lanternOutLevel = old.lanternOutLevel;
				state.lanternOutBright = old.lanternOutBright;
				state.lanternOutFrom = old.lanternOutFrom;
			} else if (state.lanternLit && old.lanternGoingOut(TICK)) {
				// lit again mid-retract: carry on growing from where it had drawn back to
				float out = Math.min(1F, (float) (TICK - old.lanternOutAt) / ArmState.LANTERN_OUT_TICKS);
				state.lanternAgeBoost = Math.max(0F, (1F - out) * Math.min(old.lanternOutFrom,
						dev.morphreborn.client.arm.geom.LureGeometry.BUILT_AT + dev.morphreborn.client.arm.geom.LureGeometry.HOLD_LEAD) - state.lanternAge(0F));
			} else if (state.lanternLit && old.lanternLit) {
				state.lanternAgeBoost = old.lanternAgeBoost; // the same lighting: keep the carried-on age
			}
		}
		// the player's own state is never a late arrival (accessories are switched off on every world change and aren't kept across
		// a relog), so for them an accessory with no state before it was just switched on and grows from nothing
		boolean fresh = old != null || SEEN.contains(state.subject) || isOwn(state.subject);
		SEEN.add(state.subject);
		accessories(old, fresh, state);
		if (!state.live(TICK)) {
			STATES.remove(state.subject);
			return;
		}
		STATES.put(state.subject, state);
		BY_ENTITY.put(state.entityId, state);
	}

	/** Whether {@code id} is this player: the kept UUID, or the local player's if the kept one was wiped. */
	private static boolean isOwn(UUID id) {
		if (id.equals(own)) return true;
		if (own != null || ownLookup == null) return false;
		own = ownLookup.get();
		return id.equals(own);
	}

	/** ArmClientInit: how to read the local player's UUID. */
	public static void ownLookup(Supplier<@Nullable UUID> lookup) {
		ownLookup = lookup;
	}

	/**
	 * The accessories other than the lantern (whose transitions are worked out above): when each came on, and what it was when it
	 * went out, so it can draw back. An accessory the server switches on grows from age 0 (2026-11-16: a fresh toggle was
	 * drawing already built because the state before it, empty, had been dropped); only a first sight of someone else (joining,
	 * entering view: {@code fresh} false) shows them already built, as a viewer arriving late sees an arm formed. One that is off
	 * carries a going-out animation through the next packets, as the lantern's does.
	 */
	private static void accessories(@Nullable ArmState old, boolean fresh, ArmState state) {
		for (int i = 1; i < ArmProtocol.ACCESSORIES.size(); i++) {
			String id = ArmProtocol.ACCESSORIES.get(i);
			boolean on = state.accessoryOn(id);
			boolean was = old != null && old.accessoryOn(id);
			if (on) {
				state.accSince[i] = was ? old.accSince[i] : !fresh ? TICK - ArmState.ACCESSORY_BUILT_TICKS : TICK;
			} else if (was) {
				state.accOutAt[i] = TICK;
				state.accOutLevel[i] = old.accessoryLevel(id);
				state.accOutArg[i] = old.accessoryArg(id);
			} else if (old != null) {
				state.accOutAt[i] = old.accOutAt[i];
				state.accOutLevel[i] = old.accOutLevel[i];
				state.accOutArg[i] = old.accOutArg[i];
			}
		}
	}

	/** S_FORGET: the player left, or left this world. */
	public static void forget(UUID player) {
		SEEN.remove(player);
		ArmState old = STATES.remove(player);
		if (old != null && BY_ENTITY.get(old.entityId) == old) BY_ENTITY.remove(old.entityId);
	}

	/**
	 * After a world change: everyone else's arm goes, since the server only resends the players who are armed in the new
	 * world (one who retracted while we were away would otherwise keep theirs). Our own stays; the server sends it again.
	 */
	public static void clearOthers(@Nullable UUID own) {
		if (own != null) Arms.own = own;
		for (UUID id : new ArrayList<>(STATES.keySet())) {
			if (!id.equals(own)) forget(id);
		}
		SEEN.removeIf(id -> !id.equals(own));
	}

	/** On joining or leaving a server: nothing of the last one stays. */
	public static void clear() {
		STATES.clear();
		BY_ENTITY.clear();
		SEEN.clear();
		own = null;
		arsenal = ArmArsenal.EMPTY;
		ArmTips.clear();
		ArmGuiOverride.clear();
	}

	/**
	 * One game tick (ArmClientInit calls it while a world is loaded and the game isn't paused): advances the clock and
	 * settles timelines that have run out before the server's next state arrived, so a late or lost packet never leaves an
	 * arm stuck mid-animation. A state with nothing left to show (no arm, the lantern out, the recall over) goes.
	 */
	public static void tick() {
		TICK++;
		if (STATES.isEmpty()) return;
		List<ArmState> settled = null;
		for (Iterator<ArmState> it = STATES.values().iterator(); it.hasNext(); ) {
			ArmState s = it.next();
			boolean forming = s.state == ArmProtocol.STATE_FORMING || s.state == ArmProtocol.STATE_QUICKFORM || s.state == ArmProtocol.STATE_RESHAPING;
			if (s.ended(TICK, forming ? 0 : END_GRACE_TICKS)) {
				if (settled == null) settled = new ArrayList<>();
				settled.add(s.finished());
			} else if (!s.live(TICK)) {
				it.remove();
				if (BY_ENTITY.get(s.entityId) == s) BY_ENTITY.remove(s.entityId);
			}
		}
		if (settled != null) {
			for (ArmState s : settled) put(s);
		}
	}
}
