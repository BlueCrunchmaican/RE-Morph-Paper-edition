package dev.morphreborn.client.arm;

import dev.morphreborn.client.net.Wire;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Your own arm slots as the server last said (S_ARM_SLOTS, servers from 7.6.0-beta.3; 2026-11-16): which
 * hotbar slot each arm form is bound to, and what the held arm is hiding. Only the held slot ever holds the arm item;
 * an unheld bound slot keeps its real item and the hotbar draws the arm over it ({@code ArmHotbar}).
 * <p>
 * The "held display" is the real item under the held, deployed arm (its stash on the server), sent only so the hotbar can
 * draw it small in the slot's corner: its id, its count and whether it glints. It is never an item the client could put
 * back anywhere; it is only ever drawn.
 * <p>
 * The decoding is plain data and is unit tested; building an {@link ItemStack} needs the game's registries, so that
 * happens lazily on the render thread ({@link #heldStack()}). Client thread only.
 */
public final class ArmSlots {
	/** The hotbar has nine slots. */
	public static final int SLOTS = 9;

	/** One bound slot: the hotbar slot (0 to 8), the form bound to it, and the tier (1 to 3) it would form at. */
	public record Binding(int slot, String form, int tier) {}

	/** The real item under the held arm: its id ("minecraft:air" when the slot was empty), count, and whether it glints. */
	public record Held(String itemId, int count, boolean glint) {
		/** Whether there is anything to draw (the slot under the arm held an item). */
		public boolean visible() {
			return count > 0 && !"minecraft:air".equals(itemId);
		}
	}

	/** One S_ARM_SLOTS as read: the bindings in the order sent, and the held display (null when no arm is out). */
	public record Snapshot(List<Binding> bindings, @Nullable Held held) {
		public static final Snapshot EMPTY = new Snapshot(List.of(), null);
	}

	/** The bindings by hotbar slot (null: none). */
	private static final Binding[] BY_SLOT = new Binding[SLOTS];
	private static @Nullable Held held;
	/** The stack built from {@link #held}, once, and what it was built for (so a new display builds a new one). */
	private static @Nullable ItemStack heldStack;
	private static @Nullable Held heldStackFor;

	private ArmSlots() {}

	/**
	 * S_ARM_SLOTS after its id: varInt count; per binding varInt slot, string form, varInt tier; then bool held and, if
	 * true, string item id, varInt count, bool glint. A slot outside 0 to 8 or a form this build has no arm for is
	 * skipped (a newer server may have forms this client can't draw); a later binding of the same slot wins, and a tier
	 * is clamped to 1 to 3. Throws Wire.MalformedPacketException on a short packet, as every reader here does.
	 */
	public static Snapshot read(Wire.In in) {
		int count = in.count();
		List<Binding> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int slot = in.varInt();
			String form = in.string();
			int tier = Math.clamp(in.varInt(), 1, 3);
			if (slot < 0 || slot >= SLOTS || !ArmProtocol.FORMS.contains(form)) continue;
			list.add(new Binding(slot, form, tier));
		}
		Held h = null;
		if (in.bool()) {
			String id = in.string();
			int n = in.varInt();
			boolean glint = in.bool();
			h = new Held(id, n, glint);
		}
		return new Snapshot(list, h);
	}

	/** S_ARM_SLOTS: replaces everything this client knew of your arm slots. */
	public static void handle(Wire.In in) {
		apply(read(in));
	}

	/** Makes {@code snapshot} what is known (the whole message is the state: nothing of the last one stays). */
	public static void apply(Snapshot snapshot) {
		java.util.Arrays.fill(BY_SLOT, null);
		for (Binding b : snapshot.bindings()) BY_SLOT[b.slot()] = b;
		held = snapshot.held();
		heldStack = null;
		heldStackFor = null;
	}

	/** On joining or leaving a server: nothing of the last one stays. */
	public static void clear() {
		apply(Snapshot.EMPTY);
	}

	/** The binding of hotbar slot {@code slot}, or null (also for a slot outside 0 to 8). */
	public static @Nullable Binding binding(int slot) {
		return slot >= 0 && slot < SLOTS ? BY_SLOT[slot] : null;
	}

	/** The nine bindings by slot (null: none), for the pure hotbar decision. Do not change the array. */
	public static @Nullable Binding[] bindings() {
		return BY_SLOT;
	}

	/** Whether any slot is bound. */
	public static boolean any() {
		for (Binding b : BY_SLOT) {
			if (b != null) return true;
		}
		return false;
	}

	/** What is under the held arm, or null when no arm is out. */
	public static @Nullable Held held() {
		return held;
	}

	/**
	 * The real item under the held arm as a stack to draw (the count, and the glint as an enchantment glint override), or
	 * null when there is none, the slot was empty, or the id isn't an item this client knows (a modded item it lacks).
	 * Built once per display. Render thread only.
	 */
	public static @Nullable ItemStack heldStack() {
		Held h = held;
		if (h == null || !h.visible()) return null;
		if (heldStackFor == h) return heldStack;
		heldStackFor = h;
		heldStack = null;
		Identifier id = Identifier.tryParse(h.itemId());
		if (id == null) return null;
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || item == Items.AIR) return null;
		ItemStack stack = new ItemStack(item, Math.clamp(h.count(), 1, Math.max(1, item.getDefaultMaxStackSize())));
		if (h.glint()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		heldStack = stack;
		return stack;
	}
}
