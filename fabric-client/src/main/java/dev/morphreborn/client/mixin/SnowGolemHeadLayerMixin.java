package dev.morphreborn.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.render.HybridMorph;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.SnowGolemHeadLayer;
import net.minecraft.client.renderer.entity.state.SnowGolemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A snow golem's pumpkin is drawn by this layer, not by the head part, so hiding the head under a borrowed one left the pumpkin
 * drawn round it: the lent head sat inside it and nothing of it showed (rig, 2026-11-19, 19 pairs; 27 more with a piece poking out,
 * and a bigger head showing through it as a second head). A golem whose head is lent drops the pumpkin with it; one that keeps its own
 * head (it lends only ears or parts) keeps the pumpkin, and the ears stand on it (HybridMorph.placeEars).
 */
@Mixin(SnowGolemHeadLayer.class)
public abstract class SnowGolemHeadLayerMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/SnowGolemRenderState;FF)V",
			at = @At("HEAD"), cancellable = true)
	private void morph$noPumpkinOnALentHead(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, SnowGolemRenderState state, float yRot, float xRot,
			CallbackInfo ci) {
		if (((HybridMorph.Mark) state).morph$hideHead()) ci.cancel();
	}
}
