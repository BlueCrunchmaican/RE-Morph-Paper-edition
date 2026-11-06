package dev.morphreborn.client.mixin;

import dev.morphreborn.client.gui.SelectorHud;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse control of the selector and radial menu (original HudHandler.onRawMouseInput / onMouseScroll). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void morph$onButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (SelectorHud.INSTANCE.onMouseButton(info.button(), action == 1)) ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void morph$onScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (SelectorHud.INSTANCE.onMouseScroll(yoffset != 0 ? yoffset : xoffset)) ci.cancel();
	}
}
