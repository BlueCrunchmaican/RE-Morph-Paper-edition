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

	public static final int TRAIT_CLIMB = 1;
	public static final int TRAIT_WEB = 2;
	public static final int TRAIT_POWDER_SNOW = 4;
	public static final int TRAIT_LAVA = 8;
	public static final int TRAIT_BERRY = 16;
	public static final int TRAIT_SLOW_FALL = 32;

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
