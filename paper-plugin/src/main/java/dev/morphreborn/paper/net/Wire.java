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
	/** Survival Mode status: bool biomass unlocked, varInt biomass, varInt capacity, bool can morph. */
	public static final int S_SURVIVAL = 9;
	/**
	 * The Survival upgrade tree for the client's own screen: bool open, string message, bool unlocked, varInt biomass,
	 * varInt capacity, then per upgrade: string id, name, description, icon item, parent; varInt level, max level;
	 * string value now, value next; varInt cost (-1 when maxed); string missing requirement ("" if none); bool affordable.
	 */
	public static final int S_UPGRADES = 10;
	/**
	 * Survival morph prices: varInt count, then per morph a string variant id and a varInt biomass cost. An empty list
	 * means morphing is free. New in 6.1.6; older clients ignore it.
	 */
	public static final int S_COSTS = 11;

	public static final int TRAIT_CLIMB = 1;
	public static final int TRAIT_WEB = 2;
	public static final int TRAIT_POWDER_SNOW = 4;
	public static final int TRAIT_LAVA = 8;
	public static final int TRAIT_BERRY = 16;
	public static final int TRAIT_SLOW_FALL = 32;

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
