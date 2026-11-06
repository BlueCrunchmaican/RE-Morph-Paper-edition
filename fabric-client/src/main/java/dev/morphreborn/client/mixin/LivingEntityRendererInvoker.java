package dev.morphreborn.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets ModelCapture replay the exact transforms LivingEntityRenderer.submit applies before drawing the model. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
	@Invoker("setupRotations")
	void morph$setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale);

	@Invoker("scale")
	void morph$scale(LivingEntityRenderState state, PoseStack poseStack);
}
