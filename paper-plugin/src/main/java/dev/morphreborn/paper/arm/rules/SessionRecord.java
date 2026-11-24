package dev.morphreborn.paper.arm.rules;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The saved half of an arm session: what the player's PDC holds under {@code morphreborn:arm_session} while an arm is
 * formed. The item the arm replaced (the stash) lives here, in the player's own data, never in the arm item: the PDC is
 * written in the same save as the inventory, so after a crash the two always agree (either the original item and no
 * record, or the arm and its record), and it never reaches a client, where a creative client could copy it.
 * <p>
 * {@link #toMap()} and {@link #fromMap} are the plain-value form the PDC code reads and writes, so the format is tested
 * here without a server: {@code v} int, {@code form} string, {@code level} int, {@code slot} int, {@code nonce} long,
 * {@code stash} byte array (empty: the hand was empty), {@code at} long (epoch millis when formed).
 *
 * @param v the record's layout version, {@link #VERSION}
 * @param form the form id ("katana")
 * @param level 1 iron, 2 diamond, 3 netherite
 * @param slot the hotbar slot the arm took (0-8)
 * @param nonce the session's id, also on the arm item: an arm stack whose nonce isn't a live session's is a stray
 * @param stash the replaced item as {@code ItemStack.serializeAsBytes} (it carries its data version, so it updates across
 *            game versions); never modify it
 * @param at when it was formed, epoch millis
 */
public record SessionRecord(int v, String form, int level, int slot, long nonce, byte[] stash, long at) {
	/** The layout written now. A later layout may add fields; reading only needs these. */
	public static final int VERSION = 1;

	public SessionRecord {
		if (form == null) throw new IllegalArgumentException("form");
		if (stash == null) stash = new byte[0];
	}

	public boolean stashEmpty() {
		return stash.length == 0;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("v", v);
		m.put("form", form);
		m.put("level", level);
		m.put("slot", slot);
		m.put("nonce", nonce);
		m.put("stash", stash.clone());
		m.put("at", at);
		return m;
	}

	/**
	 * The record in {@code m}, or null when it can't be one: a field missing or of the wrong type, or a version below 1.
	 * A newer version is read as long as its version 1 fields are there. Numbers of any boxed type are accepted (the PDC
	 * code may read an int as a long).
	 */
	public static @Nullable SessionRecord fromMap(@Nullable Map<String, Object> m) {
		if (m == null) return null;
		if (!(m.get("v") instanceof Number v) || v.intValue() < 1) return null;
		if (!(m.get("form") instanceof String form) || form.isEmpty()) return null;
		if (!(m.get("level") instanceof Number level)) return null;
		if (!(m.get("slot") instanceof Number slot)) return null;
		if (!(m.get("nonce") instanceof Number nonce)) return null;
		if (!(m.get("stash") instanceof byte[] stash)) return null;
		if (!(m.get("at") instanceof Number at)) return null;
		return new SessionRecord(v.intValue(), form, level.intValue(), slot.intValue(), nonce.longValue(), stash.clone(), at.longValue());
	}

	// A record compares arrays by identity; two records with the same stash bytes are the same record.
	@Override
	public boolean equals(Object o) {
		return o instanceof SessionRecord r && v == r.v && level == r.level && slot == r.slot && nonce == r.nonce && at == r.at && form.equals(r.form)
				&& Arrays.equals(stash, r.stash);
	}

	@Override
	public int hashCode() {
		return ((form.hashCode() * 31 + level) * 31 + slot) * 31 + Long.hashCode(nonce) * 7 + Arrays.hashCode(stash);
	}

	@Override
	public String toString() {
		return "SessionRecord[v=" + v + ", form=" + form + ", level=" + level + ", slot=" + slot + ", nonce=" + Long.toHexString(nonce) + ", stash="
				+ stash.length + " bytes, at=" + at + "]";
	}
}
