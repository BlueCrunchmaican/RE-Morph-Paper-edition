package dev.morphreborn.client.mixin.arm;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.morphreborn.client.arm.ArmHooks;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.gui.SpyglassZoom;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drawing the bow arm zooms the view in as a bow does. Vanilla checks for the bow item itself, and the bow arm is made
 * from paper (its shots are the server's own, not a vanilla bow's), so the check also says yes to it.
 * <p>
 * The target is {@code is(Object)}: {@code ItemStack.is(Item)} is the generic {@code TypedInstance.is(T)}, erased, and
 * that is what the bytecode calls.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@WrapOperation(method = "getFieldOfViewModifier", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z"))
	private boolean morph$bowArmZoom(ItemStack stack, Object item, Operation<Boolean> original) {
		return original.call(stack, item) || item == Items.BOW && ArmHooks.live && ArmStacks.isBowArm(stack);
	}

	/**
	 * The spyglass accessory's zoom (2026-11-16): the game zooms for a scope only while an item is in use, so the
	 * accessory's own field of view is applied to whatever the method worked out, eased by SpyglassZoom. First person only, as the
	 * item; every return of the method goes through here. There is no Fabric API event for the field of view, hence the mixin.
	 */
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void morph$tendrilZoom(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
		if (!ArmHooks.live || !firstPerson || !((Object) this instanceof LocalPlayer)) return;
		float was = cir.getReturnValueF();
		float now = SpyglassZoom.fov(was);
		if (now != was) cir.setReturnValue(now);
	}
}
