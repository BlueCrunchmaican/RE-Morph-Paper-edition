package dev.morphreborn.paper.arm;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each online player's arm colour, arm look and pulse speed (C_ARM_COLOUR, docs/PROTOCOL.md), as their client last sent
 * them: the colour other players with the mod draw the red parts of their arm in (and the mod's other red accents),
 * which look their arm forms have (ArmProtocol.LOOK_*), and how fast the pulses on their arm and lantern run, in percent
 * of the usual (ArmProtocol.PULSE_SPEED_*; 2026-11-16). Players without the mod always see the default
 * colour, the resource pack's own red, and the default look, whatever is chosen here.
 * <p>
 * Only the rules live here (what is accepted, how often); ClientChannel relays the result, and the colour, look and
 * pulse speed are kept in the player's file (PlayerMorphData.armColour, armLook, armPulseSpeed) so they are known from
 * the moment they join. Pure: no server types, so the unit tests can use it.
 */
public final class ArmColours {
	private final Map<UUID, Integer> colours = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> looks = new ConcurrentHashMap<>();
	/** Pulse speeds in percent (absent: the usual speed). */
	private final Map<UUID, Integer> pulseSpeeds = new ConcurrentHashMap<>();
	/** Each player's latest request held by the throttle (colour, look, pulse speed), until {@link #takePending}. */
	private final Map<UUID, int[]> pending = new ConcurrentHashMap<>();
	/** The server tick of each player's last C_ARM_COLOUR that was acted on. */
	private final Map<UUID, Integer> lastAt = new ConcurrentHashMap<>();

	/** What a C_ARM_COLOUR came to. */
	public enum Result {
		/** Taken, and the colour, the look or the pulse speed is different from before: tell everyone. */
		CHANGED,
		/** Taken, but it was already that colour and look: nothing to tell. */
		SAME,
		/** A colour out of range: dropped. */
		DROPPED,
		/**
		 * Sooner than {@link ArmProtocol#COLOUR_SERVER_THROTTLE_TICKS} after the last one taken: held, the latest such
		 * request replacing any earlier, for {@link #takePending} once the window has passed. A lagging server can see an
		 * honest client's requests closer together than the client sent them, so the latest is never simply lost.
		 */
		THROTTLED
	}

	/** {@link #request(UUID, int, int, int, int)} from a client with no pulse speed to send: the usual speed. */
	public Result request(UUID player, int rgb, int look, int now) {
		return request(player, rgb, look, ArmProtocol.PULSE_SPEED_DEFAULT, now);
	}

	/**
	 * {@code player} asked for {@code rgb}, {@code look} and {@code pulseSpeed} (percent) on server tick {@code now}. A
	 * look this build does not know becomes the default and a pulse speed out of range the nearest allowed one; neither
	 * ever causes the colour to be dropped.
	 */
	public Result request(UUID player, int rgb, int look, int pulseSpeed, int now) {
		if (!ArmProtocol.validColour(rgb)) return Result.DROPPED;
		int keptLook = ArmProtocol.lookOrDefault(look);
		int keptPulse = ArmProtocol.clampPulseSpeed(pulseSpeed);
		Integer last = lastAt.get(player);
		if (last != null && now >= last && now - last < ArmProtocol.COLOUR_SERVER_THROTTLE_TICKS) {
			pending.put(player, new int[] {rgb, keptLook, keptPulse});
			return Result.THROTTLED;
		}
		pending.remove(player);
		lastAt.put(player, now);
		Integer old = colours.put(player, rgb);
		Integer oldLook = looks.put(player, keptLook);
		Integer oldPulse = pulseSpeeds.put(player, keptPulse);
		return old != null && old == rgb && oldLook != null && oldLook == keptLook && pulseOf(oldPulse) == keptPulse ? Result.SAME : Result.CHANGED;
	}

	private static int pulseOf(@org.jspecify.annotations.Nullable Integer stored) {
		return stored == null ? ArmProtocol.PULSE_SPEED_DEFAULT : stored;
	}

	/** {@link #restore(UUID, int, int, int)} for a file with no pulse speed: the usual speed. */
	public void restore(UUID player, int rgb, int look) {
		restore(player, rgb, look, ArmProtocol.PULSE_SPEED_DEFAULT);
	}

	/**
	 * Restores a colour, look and pulse speed kept from before (the player's file), unless their client has already sent
	 * them this session. A colour out of range is ignored; a look this build does not know becomes the default and a
	 * pulse speed out of range the nearest allowed one.
	 */
	public void restore(UUID player, int rgb, int look, int pulseSpeed) {
		if (!ArmProtocol.validColour(rgb)) return;
		colours.putIfAbsent(player, rgb);
		looks.putIfAbsent(player, ArmProtocol.lookOrDefault(look));
		pulseSpeeds.putIfAbsent(player, ArmProtocol.clampPulseSpeed(pulseSpeed));
	}

	/** {@code player}'s colour, or {@link ArmProtocol#COLOUR_DEFAULT} when none is known. */
	public int of(UUID player) {
		Integer rgb = colours.get(player);
		return rgb == null ? ArmProtocol.COLOUR_DEFAULT : rgb;
	}

	/** {@code player}'s arm look, or {@link ArmProtocol#LOOK_DEFAULT} when none is known. */
	public int lookOf(UUID player) {
		Integer look = looks.get(player);
		return look == null ? ArmProtocol.LOOK_DEFAULT : look;
	}

	/** {@code player}'s pulse speed in percent of the usual, or {@link ArmProtocol#PULSE_SPEED_DEFAULT} when none is known. */
	public int pulseSpeedOf(UUID player) {
		return pulseOf(pulseSpeeds.get(player));
	}

	/** Whether {@code player} has a colour, a look or a pulse speed of their own (anything but the defaults). */
	public boolean custom(UUID player) {
		return of(player) != ArmProtocol.COLOUR_DEFAULT || lookOf(player) != ArmProtocol.LOOK_DEFAULT
			|| pulseSpeedOf(player) != ArmProtocol.PULSE_SPEED_DEFAULT;
	}

	/** {@code player} left: forget them (their file keeps the colour, look and pulse speed). */
	public void forget(UUID player) {
		colours.remove(player);
		looks.remove(player);
		pulseSpeeds.remove(player);
		lastAt.remove(player);
		pending.remove(player);
	}

	/** The latest request {@code player} sent while throttled (colour, look, pulse speed), now taken; null when there is none. */
	public int @org.jspecify.annotations.Nullable [] takePending(UUID player) {
		return pending.remove(player);
	}

	/** Every known colour, by player. */
	public Map<UUID, Integer> all() {
		return Map.copyOf(colours);
	}

	/** Every known look, by player. */
	public Map<UUID, Integer> allLooks() {
		return Map.copyOf(looks);
	}

	/** Every known pulse speed (percent), by player. */
	public Map<UUID, Integer> allPulseSpeeds() {
		return Map.copyOf(pulseSpeeds);
	}
}
