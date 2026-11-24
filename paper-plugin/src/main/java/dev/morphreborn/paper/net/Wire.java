package dev.morphreborn.paper.net;

import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.data.PlayerSkin;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Byte-level codec for the {@code morphreborn:main} channel. Mirrors {@code dev.morphreborn.client.net.Wire}
 * in the Fabric mod byte for byte; docs/PROTOCOL.md is the shared specification.
 */
public final class Wire {
	public static final String CHANNEL = "morphreborn:main";
	/**
	 * 2 (5.1.0): the client runs the chicken's slow fall and draws a perched bat. 3 (6.1.2): Survival Mode (gameplay mode in
	 * S_HELLO, S_SURVIVAL, C_OPEN_UPGRADES). Clients on another protocol are treated as unmodded.
	 */
	public static final int PROTOCOL_VERSION = 3;
	/** Hard caps that reject garbage before allocating. */
	public static final int MAX_STRING = 262_144;
	public static final int MAX_LIST = 65_536;

	// client -> server
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
	 * Arm forms (8.0.0): varInt action (ArmProtocol.ACTION_*: form, retract, lantern on, lantern off, and since
	 * 7.6.0-beta.3 inventory open and inventory closed, and since 8.0.0-beta.2 accessory on and accessory off), string form id
	 * (at most 32; "" unless forming, or, for the accessory actions, the accessory id: lantern, shield, spyglass, flint). Action 8 (accessory
	 * mode) and action 9 (accessory hold: a held key, the spyglass's zoom) carry one more varInt after the string: a step of +1 or -1 for
	 * mode, 1 held or 0 let go for hold (ArmProtocol.ACTION_ACCESSORY_MODE / _HOLD). Sent only to servers whose S_HELLO says they offer arms; answered with S_ARM, or
	 * S_RESULT 1 and a message when refused.
	 */
	public static final int C_ARM = 9;
	/**
	 * The player's colour for the red parts of their arm forms and the mod's other red accents (8.0.0): varInt RGB
	 * (0x000000 to 0xFFFFFF; ArmProtocol.COLOUR_DEFAULT is the shipped red). Sent after the handshake and whenever the
	 * setting changes, at most one every 20 ticks; the server drops more than one every 10 and anything out of range.
	 */
	public static final int C_ARM_COLOUR = 10;
	/**
	 * Switch a biomass passive on or off from the upgrade tree (8.0.0-beta.2; I wanted the toggles in the upgrades menu, and the
	 * modded tree is the menu modded players get): string passive id (at most 64), bool on. Sent only to servers at
	 * PassiveProtocol.SINCE that have sent an S_PASSIVES; the server answers with S_PASSIVES, and ignores it for a passive that is
	 * not bought, outside Survival Mode, or more than one in 2 ticks (answering with the real states all the same).
	 */
	public static final int C_PASSIVE = 11;
	/**
	 * The player's Tooltips setting (8.0.0-beta.2, decided 2026-11-17): bool on (the explanation lines are shown), sent once after the handshake and
	 * whenever the setting changes, only to servers at TooltipPrefs.SINCE. The server keeps it with the player's data and builds the descriptions it
	 * sends (S_UPGRADES' description and values, the chest-menu text) to match; a client older than TooltipPrefs.SINCE never sends it and counts as on.
	 */
	public static final int C_TOOLTIPS = 12;

	// server -> client
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
	 * Survival Mode status: bool biomass unlocked, varInt biomass, varInt capacity, bool can morph. Since 8.0.0, after
	 * them (older clients stop reading before it): varInt the biomass's thousandths (0-999), varInt what the arm and
	 * lantern drain now, thousandths a minute (0: nothing drains).
	 */
	public static final int S_SURVIVAL = 9;
	/**
	 * The Survival upgrade tree for the client's own screen: bool open, string message, bool unlocked, varInt biomass,
	 * varInt capacity, then per upgrade: string id, name, description, icon item, parent; varInt level, max level;
	 * string value now, value next; varInt cost (-1 when maxed); string missing requirement ("" if none); bool affordable.
	 */
	public static final int S_UPGRADES = 10;
	/**
	 * Survival morph prices: varInt count, then per morph a string variant id and a varInt biomass cost. An empty list
	 * means morphing is free. New in 6.3.0; older clients ignore it.
	 */
	public static final int S_COSTS = 11;
	/**
	 * Rage meter (7.2.0): bool available, varInt hits (within the window), varInt hits needed, varInt wait ticks left,
	 * varInt wait ticks in all (0: no wait), bool raging now, varInt rage ticks left, varInt rage ticks in all.
	 */
	public static final int S_RAGE = 12;
	/**
	 * A rage's tendrils (7.2.0): varInt the raging player's entity id, varInt count, then each drained entity's id. An
	 * empty list ends them. Sent to players with the mod who are near.
	 */
	public static final int S_RAGE_DRAIN = 13;
	/**
	 * A player's arm and lantern (8.0.0): uuid player, varInt entity id, string form ("" none), string form reshaped
	 * from, varInt level, varInt state (ArmProtocol.STATE_*), varInt ticks in the state, varInt the state's length (0
	 * for none and formed), varInt slot + 1 (0: none), varInt why (ArmProtocol.WHY_*), bool lantern lit, varInt lantern
	 * level, varInt ticks since lit, varInt recall ticks left (always 0 from 7.6.0-beta.3: arm slots replaced the recall),
	 * varInt lantern brightness; then, only to clients at ArmProtocol.NEW_FORMS_SINCE (8.0.0-beta.2), varInt accessory count and
	 * per accessory that is on, in switch-on order: string id, bool on, varInt level, varInt arg (accessory-specific). Sent to
	 * clients that draw arms in the player's world, the player included; S_FORGET and state none clear it.
	 */
	public static final int S_ARM = 14;
	/**
	 * An arm special's effect (8.0.0): varInt origin entity id, varInt kind (ArmProtocol.FX_*), varInt level, varInt
	 * ticks, varInt seed, varInt count then each entity id, varInt count then each block as a long (BlockPos.asLong),
	 * varInt arg. Sent to clients that draw arms near the origin.
	 */
	public static final int S_ARM_FX = 15;
	/**
	 * The player's own Arsenal (8.0.0): bool usable, string why not, bool free, varInt milli a block, a hit and a bolt,
	 * varInt drain and lantern drain (milli a minute), varInt count, then per form: string id, name, varInt level (0: not
	 * owned), max level, string special, varInt its cost (milli), cooldown, cooldown left (ticks); then string last
	 * form, varInt lantern level (0: not owned), bool lantern lit, varInt flare cost (milli), cooldown, cooldown left; then, only
	 * to clients at ArmProtocol.NEW_FORMS_SINCE (8.0.0-beta.2), varInt accessory count and per built accessory in
	 * ArmForm.ACCESSORIES order: string id, varInt owned level (0: not owned), bool on.
	 * New fields only ever go after these, never inside the per-form part.
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
	 * The player's biomass passives (8.0.0-beta.2; the owner only, clients from PassiveProtocol.SINCE, Survival Mode only): varInt
	 * count, then per BOUGHT passive: string id, varInt state (PassiveProtocol.STATE_*: 1 off, 2 running, 3 idle, 4 no biomass; 0, not
	 * bought, is never listed but is read as "off the list"). The whole list each time: a passive missing from it is not bought. Sent after the
	 * handshake, with the upgrade tree, after C_PASSIVE and whenever a state changes (coalesced: at most one every 5 ticks).
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
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

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

		public Out longValue(long v) {
			for (int i = 7; i >= 0; i--) buf.write((int) (v >>> (i * 8)) & 0xFF);
			return this;
		}

		public Out string(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			varInt(bytes.length);
			buf.writeBytes(bytes);
			return this;
		}

		public Out uuid(UUID id) {
			longValue(id.getMostSignificantBits());
			return longValue(id.getLeastSignificantBits());
		}

		public Out ref(MorphRef ref) {
			string(ref.entityType());
			string(ref.variantId());
			string(ref.snbt());
			PlayerSkin skin = ref.skin();
			bool(skin != null);
			if (skin != null) {
				uuid(skin.profileId());
				string(skin.name());
				string(skin.textureValue());
				string(skin.textureSignature());
			}
			return this;
		}

		public Out optionalRef(@Nullable MorphRef ref) {
			bool(ref != null);
			if (ref != null) ref(ref);
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

		public boolean bool() {
			return read() != 0;
		}

		public long longValue() {
			long v = 0;
			for (int i = 0; i < 8; i++) v = (v << 8) | read();
			return v;
		}

		public String string(int max) {
			int len = varInt();
			if (len < 0 || len > Math.min(max, MAX_STRING) || pos + len > data.length) throw new MalformedPacketException("bad string length " + len);
			String s = new String(data, pos, len, StandardCharsets.UTF_8);
			pos += len;
			return s;
		}

		public UUID uuid() {
			return new UUID(longValue(), longValue());
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
