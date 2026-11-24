package dev.morphreborn.paper.arm;

import dev.morphreborn.paper.MorphRebornPlugin;
import dev.morphreborn.paper.arm.rules.ArmSlots;
import dev.morphreborn.paper.arm.rules.SessionRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The arm's saved state, in the player's own persistent data (decision D25): {@code morphreborn:arm_session}, the
 * record of the arm formed now with the item it replaced, and {@code morphreborn:arm_pending}, items owed to the player
 * that couldn't be handed back (no room when they left, or bytes that didn't decode), and {@code morphreborn:arm_slots},
 * the arm slots (2026-11-16): a list of {slot, form}, labels only.
 * <p>
 * Why the player's PDC: it is written in the same player file save as the inventory, so after a crash the two always
 * agree (either the original item and no record, or the arm and its record), and it never reaches a client, where a
 * creative client could copy it. The arm item itself carries only its form and nonce.
 * <p>
 * The session record's layout is {@link SessionRecord}'s: this class only moves its plain values in and out of the
 * container, so the format is unit tested there without a server.
 */
final class ArmStash {
	static final NamespacedKey SESSION = MorphRebornPlugin.key("arm_session");
	static final NamespacedKey PENDING = MorphRebornPlugin.key("arm_pending");
	/** {@code morphreborn:arm_slots}: a list of {slot, form}, the arm slots (labels only: it holds no item). */
	static final NamespacedKey SLOTS = MorphRebornPlugin.key("arm_slots");
	/** A pending entry's reason: there was no room for it. */
	static final String OVERFLOW = "overflow";
	/** A pending entry's reason: its bytes didn't decode (kept for /morph arm <player> recover). */
	static final String DECODE = "decode";

	/** The record's fields and how each is stored (SessionRecord.toMap's names). */
	private static final Map<String, PersistentDataType<?, ?>> SCHEMA = schema();

	private static Map<String, PersistentDataType<?, ?>> schema() {
		Map<String, PersistentDataType<?, ?>> m = new LinkedHashMap<>();
		m.put("v", PersistentDataType.INTEGER);
		m.put("form", PersistentDataType.STRING);
		m.put("level", PersistentDataType.INTEGER);
		m.put("slot", PersistentDataType.INTEGER);
		m.put("nonce", PersistentDataType.LONG);
		m.put("stash", PersistentDataType.BYTE_ARRAY);
		m.put("at", PersistentDataType.LONG);
		return m;
	}

	private static final NamespacedKey P_STASH = MorphRebornPlugin.key("stash");
	private static final NamespacedKey P_REASON = MorphRebornPlugin.key("reason");
	private static final NamespacedKey P_AT = MorphRebornPlugin.key("at");
	private static final NamespacedKey B_SLOT = MorphRebornPlugin.key("slot");
	private static final NamespacedKey B_FORM = MorphRebornPlugin.key("form");

	/** An item owed to the player: its bytes, why it is waiting ({@link #OVERFLOW} or {@link #DECODE}) and since when. */
	record Pending(byte[] stash, String reason, long at) {}

	private ArmStash() {}

	// ------------------------------------------------------------------ the session record

	static boolean has(Player p) {
		return p.getPersistentDataContainer().has(SESSION);
	}

	/** The saved record, or null when there is none or it can't be read (logged by the caller). */
	static @Nullable SessionRecord read(Player p) {
		PersistentDataContainer pdc = p.getPersistentDataContainer();
		PersistentDataContainer c;
		try {
			c = pdc.get(SESSION, PersistentDataType.TAG_CONTAINER);
		} catch (IllegalArgumentException e) {
			return null; // something else under our key
		}
		if (c == null) return null;
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, PersistentDataType<?, ?>> field : SCHEMA.entrySet()) {
			Object v = readField(c, MorphRebornPlugin.key(field.getKey()), field.getValue());
			if (v != null) values.put(field.getKey(), v);
		}
		return SessionRecord.fromMap(values);
	}

	private static @Nullable Object readField(PersistentDataContainer c, NamespacedKey key, PersistentDataType<?, ?> type) {
		try {
			return c.get(key, type);
		} catch (IllegalArgumentException e) {
			return null; // stored as another type: SessionRecord.fromMap then rejects the record
		}
	}

	/** Write the record. Called in the same tick as the slot write it describes. */
	static void write(Player p, SessionRecord record) {
		PersistentDataContainer pdc = p.getPersistentDataContainer();
		PersistentDataContainer c = pdc.getAdapterContext().newPersistentDataContainer();
		for (Map.Entry<String, Object> e : record.toMap().entrySet()) {
			NamespacedKey key = MorphRebornPlugin.key(e.getKey());
			switch (e.getValue()) {
				case Integer i -> c.set(key, PersistentDataType.INTEGER, i);
				case Long l -> c.set(key, PersistentDataType.LONG, l);
				case String s -> c.set(key, PersistentDataType.STRING, s);
				case byte[] b -> c.set(key, PersistentDataType.BYTE_ARRAY, b);
				default -> throw new IllegalStateException("Arm session field " + e.getKey() + " has no storage type");
			}
		}
		pdc.set(SESSION, PersistentDataType.TAG_CONTAINER, c);
	}

	static void clear(Player p) {
		p.getPersistentDataContainer().remove(SESSION);
	}

	// ------------------------------------------------------------------ the arm slots

	/**
	 * The player's arm slots (2026-11-16): which hotbar slot each form is bound to. Only labels: no item is
	 * ever kept here, so it needs no care about the inventory and may be written whenever a binding changes. Anything
	 * unreadable counts as no binding.
	 */
	static ArmSlots readSlots(Player p) {
		List<PersistentDataContainer> list;
		try {
			list = p.getPersistentDataContainer().get(SLOTS, PersistentDataType.LIST.dataContainers());
		} catch (IllegalArgumentException e) {
			return ArmSlots.EMPTY;
		}
		if (list == null) return ArmSlots.EMPTY;
		List<ArmSlots.Binding> rows = new ArrayList<>(list.size());
		for (PersistentDataContainer c : list) {
			Object slot = readField(c, B_SLOT, PersistentDataType.INTEGER);
			Object form = readField(c, B_FORM, PersistentDataType.STRING);
			if (slot instanceof Integer i && form instanceof String f) rows.add(new ArmSlots.Binding(i, f));
		}
		return ArmSlots.fromRows(rows);
	}

	/** Save the arm slots (the key is removed when there are none). */
	static void writeSlots(Player p, ArmSlots slots) {
		PersistentDataContainer pdc = p.getPersistentDataContainer();
		if (slots.isEmpty()) {
			pdc.remove(SLOTS);
			return;
		}
		List<PersistentDataContainer> list = new ArrayList<>(slots.size());
		for (ArmSlots.Binding b : slots.toRows()) {
			PersistentDataContainer c = pdc.getAdapterContext().newPersistentDataContainer();
			c.set(B_SLOT, PersistentDataType.INTEGER, b.slot());
			c.set(B_FORM, PersistentDataType.STRING, b.form());
			list.add(c);
		}
		pdc.set(SLOTS, PersistentDataType.LIST.dataContainers(), list);
	}

	// ------------------------------------------------------------------ pending items

	static List<Pending> pending(Player p) {
		List<Pending> out = new ArrayList<>();
		List<PersistentDataContainer> list;
		try {
			list = p.getPersistentDataContainer().get(PENDING, PersistentDataType.LIST.dataContainers());
		} catch (IllegalArgumentException e) {
			return out;
		}
		if (list == null) return out;
		for (PersistentDataContainer c : list) {
			byte[] bytes = (byte[]) readField(c, P_STASH, PersistentDataType.BYTE_ARRAY);
			if (bytes == null || bytes.length == 0) continue;
			String reason = (String) readField(c, P_REASON, PersistentDataType.STRING);
			Long at = (Long) readField(c, P_AT, PersistentDataType.LONG);
			out.add(new Pending(bytes, reason == null ? OVERFLOW : reason, at == null ? 0 : at));
		}
		return out;
	}

	/** Replace the list (removed when empty). */
	static void setPending(Player p, List<Pending> entries) {
		PersistentDataContainer pdc = p.getPersistentDataContainer();
		if (entries.isEmpty()) {
			pdc.remove(PENDING);
			return;
		}
		List<PersistentDataContainer> list = new ArrayList<>(entries.size());
		for (Pending e : entries) {
			PersistentDataContainer c = pdc.getAdapterContext().newPersistentDataContainer();
			c.set(P_STASH, PersistentDataType.BYTE_ARRAY, e.stash());
			c.set(P_REASON, PersistentDataType.STRING, e.reason());
			c.set(P_AT, PersistentDataType.LONG, e.at());
			list.add(c);
		}
		pdc.set(PENDING, PersistentDataType.LIST.dataContainers(), list);
	}

	static void addPending(Player p, byte[] stash, String reason) {
		if (stash.length == 0) return;
		List<Pending> list = pending(p);
		list.add(new Pending(stash.clone(), reason, System.currentTimeMillis()));
		setPending(p, list);
	}

	// ------------------------------------------------------------------ item bytes

	/** The stash as an item: empty for no bytes, null when the bytes don't decode. */
	static @Nullable ItemStack decode(byte[] bytes) {
		if (bytes.length == 0) return ItemStack.empty();
		try {
			ItemStack item = ItemStack.deserializeBytes(bytes);
			return item == null ? ItemStack.empty() : item;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** "64 × cobblestone", for /morph arm info and the report. */
	static String describe(byte[] bytes) {
		if (bytes.length == 0) return "empty hand";
		ItemStack item = decode(bytes);
		if (item == null) return "undecodable (" + bytes.length + " bytes)";
		return item.getAmount() + " × " + item.getType().getKey().getKey() + " (" + bytes.length + " bytes)";
	}
}
