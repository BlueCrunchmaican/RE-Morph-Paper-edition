package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Arm slots (2026-11-16): which hotbar slot each arm form is bound to. A binding is only a label: it says
 * "scrolling onto this slot forms this arm", and it holds nothing (the item in a bound slot is the player's own, and
 * only the held slot ever holds the arm item). The saved form of it is the player's PDC list of {slot, form}
 * ({@link #toRows()} and {@link #fromRows}).
 * <p>
 * One slot per form: binding a form to a slot removes that form's binding elsewhere, and binding a different form to a
 * bound slot replaces it. A value is never changed; every change returns a new one (or this one, if nothing changed).
 */
public final class ArmSlots {
	/** The hotbar. */
	public static final int SLOTS = 9;

	public static final ArmSlots EMPTY = new ArmSlots(new TreeMap<>());

	/** One binding, in the order the PDC list holds them (by slot). */
	public record Binding(int slot, String form) {}

	/** Slot to form id, by slot. Never modified once built. */
	private final TreeMap<Integer, String> bySlot;

	private ArmSlots(TreeMap<Integer, String> bySlot) {
		this.bySlot = bySlot;
	}

	public static boolean validSlot(int slot) {
		return slot >= 0 && slot < SLOTS;
	}

	public boolean isEmpty() {
		return bySlot.isEmpty();
	}

	public int size() {
		return bySlot.size();
	}

	/** The form bound to {@code slot}, or null. */
	public @Nullable String formAt(int slot) {
		return bySlot.get(slot);
	}

	/** The slot {@code form} is bound to, or -1. */
	public int slotOf(String form) {
		for (Map.Entry<Integer, String> e : bySlot.entrySet()) {
			if (e.getValue().equals(form)) return e.getKey();
		}
		return -1;
	}

	/** Every binding, by slot. */
	public List<Binding> bindings() {
		List<Binding> out = new ArrayList<>(bySlot.size());
		for (Map.Entry<Integer, String> e : bySlot.entrySet()) out.add(new Binding(e.getKey(), e.getValue()));
		return Collections.unmodifiableList(out);
	}

	/**
	 * {@code form} bound to {@code slot}: the form's binding anywhere else is removed, and whatever form held the slot is
	 * replaced. This one if it was already so.
	 *
	 * @throws IllegalArgumentException for a slot outside the hotbar or an empty form
	 */
	public ArmSlots bind(int slot, String form) {
		if (!validSlot(slot)) throw new IllegalArgumentException("slot " + slot);
		if (form == null || form.isEmpty()) throw new IllegalArgumentException("form");
		if (form.equals(bySlot.get(slot))) return this;
		TreeMap<Integer, String> next = new TreeMap<>(bySlot);
		next.values().removeIf(form::equals);
		next.put(slot, form);
		return new ArmSlots(next);
	}

	/** {@code slot} without a binding. This one if it had none. */
	public ArmSlots unbind(int slot) {
		if (!bySlot.containsKey(slot)) return this;
		TreeMap<Integer, String> next = new TreeMap<>(bySlot);
		next.remove(slot);
		return new ArmSlots(next);
	}

	/** {@code form} without a binding. This one if it had none. */
	public ArmSlots unbindForm(String form) {
		int slot = slotOf(form);
		return slot < 0 ? this : unbind(slot);
	}

	/** Only the bindings whose form {@code keep} accepts (a form the player no longer owns goes). This one if none went. */
	public ArmSlots retain(Predicate<String> keep) {
		TreeMap<Integer, String> next = new TreeMap<>(bySlot);
		if (!next.values().removeIf(form -> !keep.test(form))) return this;
		return new ArmSlots(next);
	}

	// ------------------------------------------------------------------ an admin's forced arm

	/**
	 * Why an admin-forced arm ({@code /morph arm <player> <form> [level] [free]}) does not bind the slot it takes, or null
	 * when it does. A binding is only a label, and what it forms later is the player's own arm at their own level at
	 * normal cost, so it can carry neither a form the player could not form themselves nor an admin's {@code free} or
	 * level: bound anyway, the first tuck would re-form it as something else (or refuse it and drop the binding). Such an
	 * arm is a one-off that goes back like any arm and leaves the bindings alone.
	 *
	 * @param ownedLevel the level the player has of the form the normal way (0 when they could not form it)
	 * @param free the admin asked for {@code free}
	 * @param levelOverride the admin's level, 0 for the player's own
	 */
	public static @Nullable String adminOneOffReason(int ownedLevel, boolean free, int levelOverride) {
		if (ownedLevel < 1) return "they don't have that form themselves";
		if (free && levelOverride > 0) return "it is free and has a set level";
		if (free) return "it is free";
		if (levelOverride > 0) return "it has a set level";
		return null;
	}

	/** Whether an admin-forced arm binds the slot it takes (see {@link #adminOneOffReason}). */
	public static boolean adminBinds(int ownedLevel, boolean free, int levelOverride) {
		return adminOneOffReason(ownedLevel, free, levelOverride) == null;
	}

	// ------------------------------------------------------------------ saved form

	/** The rows the PDC list is written from. */
	public List<Binding> toRows() {
		return bindings();
	}

	/**
	 * The slots a saved list holds. A row that can't be a binding (a slot outside the hotbar, an empty form) is skipped,
	 * and a later row wins over an earlier one for the same slot or form, so damaged data still gives a valid value.
	 */
	public static ArmSlots fromRows(@Nullable List<Binding> rows) {
		ArmSlots out = EMPTY;
		if (rows == null) return out;
		for (Binding row : rows) {
			if (row == null || row.form() == null || row.form().isEmpty() || !validSlot(row.slot())) continue;
			out = out.bind(row.slot(), row.form());
		}
		return out;
	}

	/** The plain text form, "0:katana,3:bow" (empty for none), for logs and the report. */
	public String encode() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, String> e : bySlot.entrySet()) {
			if (!sb.isEmpty()) sb.append(',');
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	/** What {@link #encode()} wrote; an entry that isn't "slot:form" with a hotbar slot is skipped. */
	public static ArmSlots decode(@Nullable String text) {
		if (text == null || text.isBlank()) return EMPTY;
		List<Binding> rows = new ArrayList<>();
		for (String part : text.split(",")) {
			int colon = part.indexOf(':');
			if (colon <= 0) continue;
			try {
				rows.add(new Binding(Integer.parseInt(part.substring(0, colon).trim()), part.substring(colon + 1).trim()));
			} catch (NumberFormatException e) {
				// skipped
			}
		}
		return fromRows(rows);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ArmSlots other && bySlot.equals(other.bySlot);
	}

	@Override
	public int hashCode() {
		return bySlot.hashCode();
	}

	@Override
	public String toString() {
		return "ArmSlots[" + encode() + "]";
	}
}
