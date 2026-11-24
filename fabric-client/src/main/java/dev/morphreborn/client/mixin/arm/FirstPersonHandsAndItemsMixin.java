package dev.morphreborn.client.mixin.arm;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.gui.ArmInventorySignal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the bow arm is drawn, first person shows only the hand that draws it, as with a bow: vanilla decides which hands
 * to show by looking for the bow item, and the bow arm is paper, so the check also says yes to it. Every {@code is}
 * call in the method is wrapped, but only the ones asking about the bow change.
 * <p>
 * The target is {@code is(Object)}: {@code ItemStack.is(Item)} is the generic {@code TypedInstance.is(T)}, erased, and
 * that is what the bytecode calls.
 * <p>
 * Tucked for a container screen (2026-11-16: the arm stays on the hand), two more things. The first-person
 * renderer branches on an empty main-hand stack itself (it draws the bare arm and never the item state), so an empty
 * hand cannot be given an arm through the item model resolver alone: the stack the render state is given is replaced
 * here, for drawing only; the tracked item, the swap logic and the inventory are untouched. And the swap animation:
 * the tuck puts the real item (or air) where the arm was, which vanilla would play as the hand lowering and raising
 * on every inventory open; that change is taken instantly instead (the arm coming back is already instant, see
 * ItemModelResolverMixin).
 */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {
	@Shadow
	private ItemStack mainHandItem;

	/**
	 * {@code extractRenderState} reads {@code this.mainHandItem} once, into {@code state.mainHandItem} (the later
	 * {@code mainHandItem} reads are the state's own field of the same name, on another class).
	 */
	@ModifyExpressionValue(method = "extractRenderState(Lnet/minecraft/client/player/LocalPlayer;FLnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/FirstPersonHandsAndItems;mainHandItem:Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack morph$tuckedMainHand(ItemStack visible) {
		if (!ArmHooks.live || !visible.isEmpty()) return visible;
		return ArmStacks.tuckedStandIn(visible, Minecraft.getInstance().player);
	}

	/**
	 * The arm in the visible main hand being replaced while tucked is the tuck itself, not a change of item: no dip.
	 * (Anything else replaced goes the vanilla way, and so does the arm being put back: its model says no swap.)
	 */
	@Inject(method = "shouldInstantlyReplaceVisibleItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/player/LocalPlayer;)Z",
			at = @At("HEAD"), cancellable = true)
	private void morph$tuckNoDip(ItemStack visible, ItemStack expected, LocalPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (ArmHooks.live && visible == this.mainHandItem && ArmStacks.isArm(visible) && ArmInventorySignal.tucked(player)) cir.setReturnValue(true);
	}

	@WrapOperation(method = "evaluateWhichHandsToRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z"))
	private static boolean morph$bowArmHands(ItemStack stack, Object item, Operation<Boolean> original) {
		return original.call(stack, item) || item == Items.BOW && ArmHooks.live && ArmStacks.isBowArm(stack);
	}
}
