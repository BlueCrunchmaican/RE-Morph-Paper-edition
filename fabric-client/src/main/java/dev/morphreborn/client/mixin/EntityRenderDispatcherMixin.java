package dev.morphreborn.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.render.MorphTransitionRenderState;
import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swaps a morphed player's render state for their morph, and routes transitions to our renderer. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void morph$extract(E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
		EntityRenderState replacement = MorphWorldRenderer.extract((EntityRenderDispatcher) (Object) this, entity, partialTicks);
		if (replacement != null) cir.setReturnValue(replacement);
	}

	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private <S extends EntityRenderState> void morph$submit(S renderState, CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
		if (renderState instanceof MorphTransitionRenderState transition) {
			MorphWorldRenderer.submitTransition((EntityRenderDispatcher) (Object) this, transition, camera, x, y, z, poseStack, submitNodeCollector);
			ci.cancel();
		}
	}

	/** Multimorph: the borrowed form's parts, on the main body that was just submitted. */
	@Inject(method = "submit", at = @At("RETURN"))
	private <S extends EntityRenderState> void morph$submitHybrid(S renderState, CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
		dev.morphreborn.client.render.HybridMorph.submit((EntityRenderDispatcher) (Object) this, renderState, x, y, z, poseStack, submitNodeCollector);
	}
}
