package dev.morphreborn.client.mixin.arm;

import dev.morphreborn.client.arm.ArmHooks;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands each avatar render state to the arm renderer once vanilla has filled it (ArmHooks.extract), so the arm's
 * sheath and timing ride on the state from extraction to drawing. Here rather than in the entity dispatcher, because
 * the inventory screen's preview makes its state directly and would be missed there; this also covers the world,
 * First-person Model's body and the paper doll.
 * <p>
 * The descriptor names {@code Avatar}: the method's entity type is the class's type variable, erased to its first bound.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void morph$armSnapshot(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		ArmHooks.extract.extract(entity, state, partialTicks);
	}
}
