package dev.morphreborn.client.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Byte-level codec for the {@code morphreborn:main} channel; mirrors {@code dev.morphreborn.paper.net.Wire}.
 * docs/PROTOCOL.md is the shared specification.
 */
public final class Wire {
	public static final String CHANNEL = "morphreborn:main";
	/**
	 * 2 (5.1.0): the client runs the chicken's slow fall and draws a perched bat. 3 (6.1.2): Survival Mode (gameplay mode in
	 * S_HELLO, S_SURVIVAL, C_OPEN_UPGRADES). Clients on another protocol are treated as unmodded.
	 */
	public static final int PROTOCOL_VERSION = 3;
	public static final int MAX_STRING = 262_144;
	public static final int MAX_LIST = 65_536;

	public static final int C_HELLO = 0;
	public static final int C_SELECT = 1;
	public static final int C_FAVOURITE = 2;
	public static final int C_DELETE = 3;
	public static final int C_ABILITY = 4;
	public static final int C_REQUEST_SYNC = 5;
	/** Open the Survival Mode upgrade menu (the server shows its chest menu). */
	public static final int C_OPEN_UPGRADES = 6;
	/** Buy the next level of an upgrade: string upgrade id. Answered with S_UPGRADES. */
	public static final int C_BUY_UPGRADE = 7;
	/** Set off a charged rage (7.2.0): no fields. Sent only to servers whose S_HELLO says they have rage. */
	public static final int C_RAGE = 8;
	/**
	 * Arm forms (8.0.0): varInt action (0 form, 1 retract, 2 lantern on, 3 lantern off; from 8.0.0-beta.2 also 6 accessory
	 * on, 7 accessory off and 8 accessory mode step), string id (at most 32): the form with action 0, the accessory with 6, 7 and 8,
	 * "" otherwise; with action 8 one more varInt follows, the step (+1 or -1).
	 * Sent only to servers whose S_HELLO says they have arm forms, at most one every 4 ticks; a refusal comes back as
	 * S_RESULT 1. Actions 6, 7 and 8 only to servers from ArmProtocol.NEW_FORMS_SINCE (older ones get the lantern's 2 and 3).
	 */
	public static final int C_ARM = 9;
	/**
	 * The player's colour for the red parts of their arm forms and the mod's other red accents (8.0.0): varInt RGB
	 * (0x000000 to 0xFFFFFF; ArmProtocol.COLOUR_DEFAULT is the shipped red). Sent after the handshake and whenever the
	 * setting changes, at most one every 20 ticks; the server drops more than one every 10 and anything out of range.
	 */
	public static final int C_ARM_COLOUR = 10;
	/**
	 * Switch a biomass passive on or off from the upgrade tree (8.0.0-beta.2): string passive id (at most 64), bool on. Sent only to servers
	 * at PassiveProtocol.SINCE that have sent an S_PASSIVES, at most one every 4 ticks; answered with S_PASSIVES.
	 */
	public static final int C_PASSIVE = 11;
	/**
	 * The player's Tooltips setting (8.0.0-beta.2): bool on. Sent once after the handshake and whenever the setting changes, only to servers at
	 * TooltipSync.SINCE; the server builds the descriptions it sends to match.
	 */
	public static final int C_TOOLTIPS = 12;

	public static final int S_HELLO = 0;
	public static final int S_COLLECTION = 1;
	public static final int S_MORPH_STATE = 2;
	public static final int S_ACQUIRE = 3;
	public static final int S_RESULT = 4;
	public static final int S_UNLOCK = 5;
	public static final int S_FORGET = 6;
	public static final int S_ANIMATE = 7;
	/** Traits the client carries out itself, as a bit set of TRAIT_* for the receiving player's current form. */
	public static final int S_TRAITS = 8;
	/**
	 * Survival Mode status: bool biomass unlocked, varInt biomass, varInt capacity, bool can morph; from 8.0.0 also
	 * varInt thousandths of biomass under the whole number (0-999) and varInt the drain per minute running now (0: none).
	 */
	public static final int S_SURVIVAL = 9;
	/**
	 * The Survival upgrade tree for the client's own screen: bool open, string message, bool unlocked, varInt biomass,
	 * varInt capacity, then per upgrade: string id, name, description, icon item, parent; varInt level, max level;
	 * string value now, value next; varInt cost (-1 when maxed); string missing requirement ("" if none); bool affordable.
	 */
	public static final int S_UPGRADES = 10;
	/** Survival morph prices: varInt count, then string variant id and varInt biomass cost each. Empty: morphing is free. */
	public static final int S_COSTS = 11;
	/**
	 * Rage meter (7.2.0): bool available, varInt hits (within the window), varInt hits needed, varInt wait ticks left,
	 * varInt wait ticks in all (0: no wait), bool raging now, varInt rage ticks left, varInt rage ticks in all.
	 */
	public static final int S_RAGE = 12;
	/**
	 * A rage's tendrils (7.2.0): varInt the raging player's entity id, varInt count, then each drained entity's id. An
	 * empty list ends them.
	 */
	public static final int S_RAGE_DRAIN = 13;
	/**
	 * A player's arm form (8.0.0), to every player in their world who draws arms: uuid player, varInt entity id, string
	 * form ("" none), string from-form, varInt level (0-3), varInt state, varInt ticks into the state, varInt its length
	 * (0 for none and formed), varInt hotbar slot + 1 (0 none), varInt why, bool lantern lit, varInt lantern level,
	 * varInt ticks since lit, varInt recall ticks left; then (8.0.0) varInt lantern brightness (0-5); then, from
	 * ArmProtocol.NEW_FORMS_SINCE, varInt count and per accessory: string id, bool on, varInt level, varInt arg (the
	 * accessory's own number: the shield raised, the spyglass zooming, 0 or 1). Cleared by S_FORGET and by a state of none.
	 */
	public static final int S_ARM = 14;
	/**
	 * An arm effect (8.0.0): varInt origin entity id, varInt kind, varInt level, varInt ticks, varInt seed, varInt count
	 * then entity ids, varInt count then block positions (longs), varInt arg. Stateful kinds are resent while they last
	 * and end with an empty list.
	 */
	public static final int S_ARM_FX = 15;
	/**
	 * The player's own arsenal (8.0.0): bool usable, string why not, bool free, varInt per block, per hit, per bolt, drain
	 * and lantern drain per minute (thousandths of biomass), varInt count, then per built form: string id, name; varInt
	 * level (0 not owned), max level; string special; varInt special cost, wait, wait left; then string last form, varInt
	 * lantern level, bool lantern lit, varInt flare cost, flare wait, flare wait left; then, from
	 * ArmProtocol.NEW_FORMS_SINCE, varInt count and per accessory: string id, varInt level owned, bool on. New fields only
	 * after those.
	 */
	public static final int S_ARSENAL = 16;
	/**
	 * A player's arm colour (8.0.0): uuid player, varInt RGB. Sent to every player whose client draws arms, for every
	 * online player whose colour is known, on the handshake and whenever one changes. Unknown players are drawn in
	 * ArmProtocol.COLOUR_DEFAULT.
	 */
	public static final int S_ARM_COLOUR = 17;

	/**
	 * S_ARM_SLOTS (the owner only, servers from 7.6.0-beta.3): the player's arm slots. varInt count, then per slot: varInt
	 * slot (hotbar 0..8), string form, varInt tier (1..3, the level it forms at); then bool held: if true the real item
	 * under the held arm, for its corner icon: string item id (namespace:path), varInt count, bool glint. Sent on join,
	 * on every binding change and every deploy or retract. Older clients skip the unknown id.
	 */
	public static final int S_ARM_SLOTS = 18;

	/**
	 * The player's biomass passives (8.0.0-beta.2; servers from PassiveProtocol.SINCE, Survival Mode only): varInt count, then per
	 * BOUGHT passive: string id, varInt state (PassiveProtocol.STATE_*). The whole list each time: a passive missing from it is not bought.
	 * Older servers never send it, and the tree then shows no toggles.
	 */
	public static final int S_PASSIVES = 19;

	public static final int TRAIT_CLIMB = 1;
	public static final int TRAIT_WEB = 2;
	public static final int TRAIT_POWDER_SNOW = 4;
	public static final int TRAIT_LAVA = 8;
	public static final int TRAIT_BERRY = 16;
	public static final int TRAIT_SLOW_FALL = 32;
	/**
	 * A flier's morph that may fly (after Morph Flight) glides while jump is held and drops when it isn't (8.0.0, the
	 * server setting passive.flight.hold-jump-glide).
	 */
	public static final int TRAIT_GLIDE_ON_JUMP = 64;

	private Wire() {}

	public static final class Out {
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);

		public Out(int packetId) {
			varInt(packetId);
		}

		public Out varInt(int value) {
			while ((value & ~0x7F) != 0) {
				buf.write((value & 0x7F) | 0x80);
				value >>>= 7;
			}
			buf.write(value);
			return this;
		}

		public Out bool(boolean b) {
			buf.write(b ? 1 : 0);
			return this;
		}

		public Out string(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			varInt(bytes.length);
			buf.writeBytes(bytes);
			return this;
		}

		public byte[] bytes() {
			return buf.toByteArray();
		}
	}

	public static final class In {
		private final byte[] data;
		private int pos;

		public In(byte[] data) {
			this.data = data;
		}

		private int read() {
			if (pos >= data.length) throw new MalformedPacketException("packet ended early");
			return data[pos++] & 0xFF;
		}

		public int varInt() {
			int value = 0;
			for (int shift = 0; shift < 35; shift += 7) {
				int b = read();
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0) return value;
			}
			throw new MalformedPacketException("varint too long");
		}

		public int count() {
			int n = varInt();
			if (n < 0 || n > MAX_LIST) throw new MalformedPacketException("bad count " + n);
			return n;
		}

		public boolean bool() {
			return read() != 0;
		}

		public long longValue() {
			long v = 0;
			for (int i = 0; i < 8; i++) v = (v << 8) | read();
			return v;
		}

		public String string() {
			int len = varInt();
			if (len < 0 || len > MAX_STRING || pos + len > data.length) throw new MalformedPacketException("bad string length " + len);
			String s = new String(data, pos, len, StandardCharsets.UTF_8);
			pos += len;
			return s;
		}

		public UUID uuid() {
			return new UUID(longValue(), longValue());
		}

		public MorphRef ref() {
			String type = string();
			String id = string();
			String snbt = string();
			MorphRef.Skin skin = null;
			if (bool()) skin = new MorphRef.Skin(uuid(), string(), string(), string());
			return new MorphRef(type, id, snbt, skin);
		}

		public @Nullable MorphRef optionalRef() {
			return bool() ? ref() : null;
		}

		public boolean hasMore() {
			return pos < data.length;
		}
	}

	public static final class MalformedPacketException extends RuntimeException {
		public MalformedPacketException(String message) {
			super(message);
		}
	}
}
