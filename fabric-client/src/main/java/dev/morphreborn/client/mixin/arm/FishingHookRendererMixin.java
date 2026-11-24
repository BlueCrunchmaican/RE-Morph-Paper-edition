package dev.morphreborn.client.mixin.arm;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.fx.WhipFx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A whip is a fishing rod, so its line and bobber are a vanilla fishing hook's. For a hook whose owner holds a whip, the
 * renderer here swaps vanilla's black string and sprite for the whip's: extraction marks the render state with what the
 * hook needs (WhipFx.hookOf, null for every other hook), and the submit hands those hooks to WhipFx to be drawn at the
 * end of the frame's entity submits, when the whip's tip has been recorded whichever of the player and the hook came
 * first, and cancels the vanilla draw. Every other hook draws as vanilla; so does every hook on a client without this mod.
 */
@Mixin(FishingHookRenderer.class)
public abstract class FishingHookRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V",
			at = @At("TAIL"))
	private void morphreborn$whipHook(FishingHook entity, FishingHookRenderState state, float partialTicks, CallbackInfo ci) {
		WhipFx.Hook hook = WhipFx.hookOf(entity);
		// a state that never had one gets no data map at all
		if (hook != null || state.getData(WhipFx.HOOK) != null) state.setData(WhipFx.HOOK, hook);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"), cancellable = true)
	private void morphreborn$whipLine(FishingHookRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		WhipFx.Hook hook = state.getData(WhipFx.HOOK);
		if (hook == null) return;
		WhipFx.queue(hook, state.x, state.y, state.z, state.lightCoords);
		ci.cancel();
	}
}
