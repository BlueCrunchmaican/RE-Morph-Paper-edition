package dev.morphreborn.client.arm;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.net.Wire;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Every player's arm colour, arm look and pulse speed as this client knows them (C_ARM_COLOUR / S_ARM_COLOUR,
 * docs/PROTOCOL.md): your own from your settings ({@code ClientConfig.armColour}, {@code armLook},
 * {@code armPulseSpeed}), everyone else's as the server relays them. A player the server has said nothing about is in
 * {@link ArmProtocol#COLOUR_DEFAULT}, {@link ArmProtocol#LOOK_DEFAULT} and {@link ArmProtocol#PULSE_SPEED_DEFAULT}.
 * {@code ArmTint} turns the colours into textures; the sheath draws the looks; the arm's draw runs each player's pulses
 * at their speed (2026-11-16: the pulse speed is the player's choice, and everyone sees it).
 * <p>
 * Client thread only.
 */
public final class ArmColours {
	private static final Map<UUID, Integer> OTHERS = new HashMap<>();
	/** Other players' looks (absent: the default). */
	private static final Map<UUID, Integer> LOOKS = new HashMap<>();
	/** Other players' pulse speeds in percent (absent: the usual speed). */
	private static final Map<UUID, Integer> PULSES = new HashMap<>();
	/** Your own player's id, so a relay of your own colour never overrides your setting. */
	private static @Nullable UUID self;
	/** The colour, look and pulse speed last sent to this server, or -1 for none yet; and when (on the client's tick). */
	private static int sent = -1, sentLook = -1, sentPulse = -1;
	private static long sentAt = Long.MIN_VALUE / 2;

	private ArmColours() {}

	/** Your own colour: your setting, the default when it is missing or unreadable. */
	public static int own() {
		return ArmProtocol.parseColour(ClientConfig.INSTANCE.armColour);
	}

	/** {@code player}'s colour: yours from your setting, anyone else's as the server last said, else the default. */
	public static int of(UUID player) {
		if (player.equals(self)) return own();
		Integer rgb = OTHERS.get(player);
		return rgb == null ? ArmProtocol.COLOUR_DEFAULT : rgb;
	}

	/** Your own look: your setting, the default when it is missing or unknown. */
	public static int ownLook() {
		return ArmProtocol.parseLook(ClientConfig.INSTANCE.armLook);
	}

	/** {@code player}'s look: yours from your setting, anyone else's as the server last said, else the default. */
	public static int lookOf(UUID player) {
		if (player.equals(self)) return ownLook();
		Integer look = LOOKS.get(player);
		return look == null ? ArmProtocol.LOOK_DEFAULT : look;
	}

	/** Your own pulse speed in percent of the usual: your setting kept to the allowed range ({@code ClientConfig.armPulseSpeed}). */
	public static int ownPulseSpeed() {
		return ArmProtocol.pulseSpeedPercent(ClientConfig.INSTANCE.armPulseSpeed);
	}

	/** {@code player}'s pulse speed in percent: yours from your setting, anyone else's as the server last said, else the usual. */
	public static int pulseSpeedOf(UUID player) {
		if (player.equals(self)) return ownPulseSpeed();
		Integer percent = PULSES.get(player);
		return percent == null ? ArmProtocol.PULSE_SPEED_DEFAULT : percent;
	}

	/**
	 * S_ARM_COLOUR, after its id: uuid player, varInt RGB, then (servers from the arm look) varInt look, then (servers
	 * from {@link ArmProtocol#PULSE_SPEED_SINCE}) varInt pulse speed in percent. A colour out of range drops the packet;
	 * a look this build doesn't know is the default and a pulse speed out of range the nearest allowed one.
	 */
	public static void handle(Wire.In in) {
		UUID player = in.uuid();
		int rgb = in.varInt();
		if (!ArmProtocol.validColour(rgb)) return;
		int look = in.hasMore() ? ArmProtocol.lookOrDefault(in.varInt()) : ArmProtocol.LOOK_DEFAULT;
		int pulse = in.hasMore() ? ArmProtocol.clampPulseSpeed(in.varInt()) : ArmProtocol.PULSE_SPEED_DEFAULT;
		if (rgb == ArmProtocol.COLOUR_DEFAULT) OTHERS.remove(player);
		else OTHERS.put(player, rgb);
		if (look == ArmProtocol.LOOK_DEFAULT) LOOKS.remove(player);
		else LOOKS.put(player, look);
		if (pulse == ArmProtocol.PULSE_SPEED_DEFAULT) PULSES.remove(player);
		else PULSES.put(player, pulse);
	}

	/** C_ARM_COLOUR for {@code rgb} and {@code look}, for a server from before the pulse speed (it reads no more than that). */
	public static Wire.Out packet(int rgb, int look) {
		return new Wire.Out(Wire.C_ARM_COLOUR).varInt(rgb & 0xFFFFFF).varInt(ArmProtocol.lookOrDefault(look));
	}

	/** C_ARM_COLOUR for {@code rgb}, {@code look} and {@code pulseSpeed} (percent, kept to the allowed range). */
	public static Wire.Out packet(int rgb, int look, int pulseSpeed) {
		return packet(rgb, look).varInt(ArmProtocol.clampPulseSpeed(pulseSpeed));
	}

	/** {@link #tick(UUID, long, boolean)} for a server from before the pulse speed. */
	public static Wire.@Nullable Out tick(@Nullable UUID selfId, long tick) {
		return tick(selfId, tick, false);
	}

	/**
	 * Every client tick while connected to a RE:Morph server: your colour, look and pulse speed go to the server once
	 * after the handshake and again whenever any setting has changed, at most once every
	 * {@link ArmProtocol#COLOUR_CLIENT_THROTTLE_TICKS} (a setting changed again sooner waits, and the latest one is sent).
	 * {@code serverTakesPulse}: the server is from {@link ArmProtocol#PULSE_SPEED_SINCE}, so the pulse speed goes along
	 * (an older server reads no more than the look, and the pulse speed does not count as a change to send to it).
	 * Returns the packet to send now, or null.
	 */
	public static Wire.@Nullable Out tick(@Nullable UUID selfId, long tick, boolean serverTakesPulse) {
		self = selfId;
		int rgb = own(), look = ownLook();
		int pulse = serverTakesPulse ? ownPulseSpeed() : ArmProtocol.PULSE_SPEED_DEFAULT;
		if ((rgb == sent && look == sentLook && pulse == sentPulse) || tick - sentAt < ArmProtocol.COLOUR_CLIENT_THROTTLE_TICKS) return null;
		sent = rgb;
		sentLook = look;
		sentPulse = pulse;
		sentAt = tick;
		return serverTakesPulse ? packet(rgb, look, pulse) : packet(rgb, look);
	}

	/** Leaving a server (or joining one): forget everyone's colours, looks and pulse speeds and what was sent. */
	public static void clear() {
		OTHERS.clear();
		LOOKS.clear();
		PULSES.clear();
		sent = -1;
		sentLook = -1;
		sentPulse = -1;
		sentAt = Long.MIN_VALUE / 2;
	}

	/** For tests: the colours known for other players. */
	static Map<UUID, Integer> others() {
		return OTHERS;
	}

	/** For tests: the looks known for other players. */
	static Map<UUID, Integer> looks() {
		return LOOKS;
	}

	/** For tests: the pulse speeds (percent) known for other players. */
	static Map<UUID, Integer> pulses() {
		return PULSES;
	}
}
