package dev.morphreborn.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The paper doll blits its off-screen texture itself, to draw it see-through at the chosen opacity. */
@Mixin(PictureInPictureRenderer.class)
public interface PictureInPictureRendererAccessor {
	@Accessor("textureView")
	GpuTextureView morph$textureView();
}
