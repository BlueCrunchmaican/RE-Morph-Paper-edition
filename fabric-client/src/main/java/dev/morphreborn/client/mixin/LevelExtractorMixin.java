package dev.morphreborn.client.mixin;

import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First-person hands and held items need the real player state, so morph replacement is skipped there. */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Inject(method = "extractPlayerState", at = @At("HEAD"))
	private void morph$beginFirstPerson(CallbackInfo ci) {
		MorphWorldRenderer.extractingFirstPerson = true;
	}

	@Inject(method = "extractPlayerState", at = @At("RETURN"))
	private void morph$endFirstPerson(CallbackInfo ci) {
		MorphWorldRenderer.extractingFirstPerson = false;
	}
}
