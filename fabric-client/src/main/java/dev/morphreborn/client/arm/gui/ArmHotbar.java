package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.arm.ArmSlots;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Your arm slots on the hotbar (2026-11-16). A bound slot that is not the held, deployed arm shows its form's
 * arm instead of the real item, with the real item small in the slot's top left corner; the held slot, where the real
 * arm item is, shows the arm as always and the item under it small in the same corner (the server sends that one in
 * S_ARM_SLOTS, since the client only has the arm). With a container screen open (the same test as ArmInventorySignal's:
 * the server has tucked the arm away) nothing extra is drawn at all, so the inventory shows the real items; chat, the pause
 * menu and other screens leave the hotbar as it is. A held bound slot with no arm out (it was refused, the arm melted)
 * shows its real item: you are holding that, and only just after you select it is the arm drawn while the server forms it.
 * The moment after a container screen closes is the other exception: the arm is still formed and its item is on its way
 * back, so the held bound slot keeps the arm (with the real item in the corner) instead of flashing flat.
 * <p>
 * Only the drawing changes: nothing reads or writes the inventory, and {@code Slot.getItem()} is never touched. The
 * hotbar mixin ({@code HudMixin}) asks {@link #decide} for each slot and calls {@link #draw}.
 * <p>
 * Render thread only (the decision is plain data and is unit tested).
 */
public final class ArmHotbar {
	/** What to draw in a hotbar slot. */
	public enum Kind {
		/** Vanilla's own drawing, untouched. */
		NORMAL,
		/** An unheld bound slot: the form's arm, with the real item small in the corner. */
		ARM_ICON,
		/** The held slot's real arm item, drawn as vanilla does, with the item under it small in the corner. */
		HELD_CORNER
	}

	/** The decision for one slot: what to draw, and for an {@link Kind#ARM_ICON} which form at which tier. */
	public record Plan(Kind kind, @Nullable String form, int tier) {
		public static final Plan NORMAL = new Plan(Kind.NORMAL, null, 0);
	}

	/** The corner item's size against a full slot item, and its offset inside the slot's 16 pixels. */
	static final float CORNER_SCALE = 0.5F;
	/** How long after selecting a bound slot its arm is drawn before the server's arm item arrives (ns). */
	static final long FORMING_GRACE_NS = 600_000_000L;
	/** The selected slot last seen while drawing, and when it was selected. */
	private static int lastSelected = -1;
	private static long selectedAt;

	private ArmHotbar() {}

	/**
	 * What to draw in hotbar slot {@code slot} (0 to 8).
	 *
	 * @param heldSlot    the selected hotbar slot
	 * @param screenOpen  a container screen is open: nothing extra is drawn, the real items show
	 * @param stackIsArm  the slot's actual stack is an arm item (the deployed arm: only the held slot ever holds one)
	 * @param heldForming the held slot was selected just now, so its arm is on its way from the server
	 * @param tucked      your arm is tucked ({@link ArmInventorySignal#tucked}) with its session formed: with no screen open
	 *                    that is the moment after the screen closed, when the real item is still in the held slot but the
	 *                    arm is out (and about to be put back), so the held bound slot keeps showing the arm
	 * @param bindings    the nine bindings by slot (null: none), as {@link ArmSlots#bindings()}
	 */
	public static Plan decide(int slot, int heldSlot, boolean screenOpen, boolean stackIsArm, boolean heldForming, boolean tucked,
			ArmSlots.@Nullable Binding[] bindings) {
		if (screenOpen || slot < 0 || slot >= bindings.length) return Plan.NORMAL;
		ArmSlots.Binding b = bindings[slot];
		if (b == null) return Plan.NORMAL;
		if (stackIsArm) {
			// the deployed arm is in the held slot; an arm anywhere else is no business of the hotbar (the server deletes strays)
			return slot == heldSlot ? new Plan(Kind.HELD_CORNER, b.form(), b.tier()) : Plan.NORMAL;
		}
		// the held slot with no arm out holds its real item (drawn as it is), except just after it was selected, and while
		// the arm is tucked and about to be put back (2026-11-16: it flashed flat after closing the inventory)
		if (slot == heldSlot && !heldForming && !tucked) return Plan.NORMAL;
		return new Plan(Kind.ARM_ICON, b.form(), b.tier());
	}

	/** The plan for {@code slot} from the live game state: the selected slot, whether a screen is open, the slot's stack. */
	public static Plan decideNow(Minecraft mc, int slot, ItemStack stack) {
		if (!ArmSlots.any() || mc.player == null) return Plan.NORMAL;
		int held = mc.player.getInventory().getSelectedSlot();
		long now = System.nanoTime();
		if (held != lastSelected) {
			lastSelected = held;
			selectedAt = now;
		}
		return decide(slot, held, mc.gui.screen() instanceof AbstractContainerScreen<?>, !stack.isEmpty() && ArmStacks.isArm(stack),
				now - selectedAt < FORMING_GRACE_NS, tuckedWithArm(mc), ArmSlots.bindings());
	}

	/** Your arm is tucked and your session is formed (the tuck is only ever about a formed arm). */
	private static boolean tuckedWithArm(Minecraft mc) {
		if (mc.player == null || !ArmInventorySignal.tucked(mc.player)) return false;
		ArmState state = Arms.state(mc.player.getUUID());
		return state != null && state.armed();
	}

	/**
	 * Draws the arm icon for an {@link Kind#ARM_ICON} slot at the slot's top left ({@code x}, {@code y}) and the real
	 * item {@code real} small in its corner (nothing when the slot is empty).
	 */
	public static void drawArmIcon(GuiGraphicsExtractor g, Plan plan, ItemStack real, int x, int y) {
		if (plan.form() != null) g.fakeItem(ArmWheel.armStack(plan.form(), plan.tier()), x, y);
		drawCorner(g, real, x, y);
	}

	/** Draws {@code stack} at half size in the top left corner of the slot at ({@code x}, {@code y}); nothing for null or empty. */
	public static void drawCorner(GuiGraphicsExtractor g, @Nullable ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) return;
		g.pose().pushMatrix();
		try {
			g.pose().translate(x, y);
			g.pose().scale(CORNER_SCALE, CORNER_SCALE);
			g.fakeItem(stack, 0, 0);
			// its count (and durability bar, cooldown) as vanilla draws them, at the corner item's half size: "it needs to be
			// small" (2026-11-16)
			if (Minecraft.getInstance().getWindow().getGuiScale() > 1) g.itemDecorations(Minecraft.getInstance().font, stack, 0, 0);
		} finally {
			g.pose().popMatrix();
		}
		// at GUI scale 1 the half-size count is 4 device px tall, under the font's 8: the count alone is drawn at the usual size instead (the
		// item stays small). Not itemDecorations: its durability bar and cooldown shading would cover the whole slot, arm icon included
		if (Minecraft.getInstance().getWindow().getGuiScale() <= 1 && stack.getCount() != 1) {
			net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
			String amount = String.valueOf(stack.getCount());
			g.text(font, amount, x + 17 - font.width(amount), y + 9, -1, true);
		}
	}

	/**
	 * The slot index the hotbar loop is on, from the x it draws at (centre - 90 + 20 * i + 2): 0 to 8, or -1 when {@code x}
	 * is not a hotbar slot's (the offhand draws elsewhere).
	 */
	public static int slotAt(int x, int guiWidth) {
		int d = x - (guiWidth / 2 - 90 + 2);
		if (d < 0 || d % 20 != 0) return -1;
		int i = d / 20;
		return i < 9 ? i : -1;
	}
}
