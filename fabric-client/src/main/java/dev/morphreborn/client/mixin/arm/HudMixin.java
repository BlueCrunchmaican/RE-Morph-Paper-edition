package dev.morphreborn.client.mixin.arm;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.ArmSlots;
import dev.morphreborn.client.arm.gui.ArmHotbar;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Arm slots on the hotbar (2026-11-16; see {@link ArmHotbar}). The hotbar loop in {@code extractItemHotbar}
 * draws each of the nine slots through {@code extractSlot}; its first call is that loop (the offhand's two come after in
 * the bytecode), so wrapping that one call is all it takes: a bound slot that isn't the deployed arm is drawn as the
 * form's arm with its real item small in the corner, the held arm gets the item under it in the corner after vanilla has
 * drawn it. Everything else goes straight to vanilla. Inventory contents and {@code Slot.getItem()} are never touched:
 * only what is drawn changes.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Hud;extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
					ordinal = 0))
	private void morph$armSlot(Hud self, GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker, Player player, ItemStack stack, int seed,
			Operation<Void> original) {
		ArmHotbar.Plan plan = ArmHotbar.Plan.NORMAL;
		int slot = -1;
		if (ArmHooks.live) {
			slot = ArmHotbar.slotAt(x, graphics.guiWidth());
			if (slot >= 0) {
				try {
					plan = ArmHotbar.decideNow(Minecraft.getInstance(), slot, stack);
				} catch (RuntimeException e) {
					plan = ArmHotbar.Plan.NORMAL; // never take the HUD down for a decoration
				}
			}
		}
		switch (plan.kind()) {
			case ARM_ICON -> ArmHotbar.drawArmIcon(graphics, plan, stack, x, y);
			case HELD_CORNER -> {
				original.call(self, graphics, x, y, deltaTracker, player, stack, seed);
				ArmHotbar.drawCorner(graphics, ArmSlots.heldStack(), x, y);
			}
			default -> original.call(self, graphics, x, y, deltaTracker, player, stack, seed);
		}
	}
}
