package dev.morphreborn.client.mixin.arm;

import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.ArmStacks;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arm forms through the item path. Every item's look, in every view, is built in {@code appendItemLayers}: an arm stack
 * gets the mod's own model there instead of the one its item_model names (ArmHooks.itemLayers decides and fills the
 * layers), so it keeps every vanilla transform around it: the swing, the bow draw, the equip, the hand poses, other
 * mods' hand passes. The vanilla hand methods themselves are left alone.
 * <p>
 * The swap animation: any component change on a held stack plays the hand's lower-and-raise, unless the item model's
 * properties say not to. An arm never bobs (its own forming animation is the change you see), so for arm stacks the
 * answer is "no swap animation" and a full-size hand.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {
	@Inject(method = "appendItemLayers(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V",
			at = @At("HEAD"), cancellable = true)
	private void morph$armLayers(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext, @Nullable Level level,
			@Nullable ItemOwner owner, int seed, CallbackInfo ci) {
		if (ArmHooks.itemLayers.append(output, item, (ItemModelResolver) (Object) this, displayContext, level, owner, seed)) ci.cancel();
	}

	/**
	 * An empty main hand draws the arm while you are tucked (2026-11-16: the arm stays on the hand while the
	 * inventory is open). The arm replaced an empty hand, the tuck wrote air back, and {@code updateForTopItem} builds no
	 * model for air, so the third-person hand and the inventory doll would show no tool. Here, once, for every empty
	 * stack that is asked about as your main hand ({@link ArmStacks#tuckedStandIn}); the first-person hand is the same
	 * through FirstPersonHandsAndItemsMixin, which its renderer needs because it branches on an empty stack itself. The
	 * call goes back through {@code updateForTopItem} with the stand-in, which is not empty, so this does not run twice.
	 */
	@Inject(method = "updateForTopItem(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V",
			at = @At("HEAD"), cancellable = true)
	private void morph$tuckedHand(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext, @Nullable Level level,
			@Nullable ItemOwner owner, int seed, CallbackInfo ci) {
		if (!ArmHooks.live || !item.isEmpty()) return;
		ItemStack standIn = ArmStacks.tuckedStandIn(item, owner, displayContext);
		if (standIn == item) return;
		((ItemModelResolver) (Object) this).updateForTopItem(output, standIn, displayContext, level, owner, seed);
		ci.cancel();
	}

	@Inject(method = "shouldPlaySwapAnimation(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
	private void morph$armNoSwap(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (ArmHooks.live && ArmStacks.isArm(stack)) cir.setReturnValue(false);
	}

	@Inject(method = "swapAnimationScale(Lnet/minecraft/world/item/ItemStack;)F", at = @At("HEAD"), cancellable = true)
	private void morph$armSwapScale(ItemStack stack, CallbackInfoReturnable<Float> cir) {
		if (ArmHooks.live && ArmStacks.isArm(stack)) cir.setReturnValue(1F);
	}
}
