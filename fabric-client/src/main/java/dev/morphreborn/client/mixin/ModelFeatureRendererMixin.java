package dev.morphreborn.client.mixin;

import dev.morphreborn.client.render.FirstPersonMorph;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides a first-person morph body's head between posing and drawing it, and shows it again straight after, so the
 * same model draws whole everywhere else (other players, the paper doll, the selector) in the same frame.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {
	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER))
	private void morph$hideHead(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		FirstPersonMorph.beforeDraw(submit.model(), submit.state());
	}

	@Inject(method = "prepareModel", at = @At("TAIL"))
	private void morph$showHead(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		FirstPersonMorph.afterDraw();
	}
}
