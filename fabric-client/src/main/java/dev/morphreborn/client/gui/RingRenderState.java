package dev.morphreborn.client.gui;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/** The radial menu's translucent black ring (original: a GL_TRIANGLE_STRIP of black at alpha 150). */
public record RingRenderState(float cx, float cy, float inner, float outer, int slices, int color, ScreenRectangle bounds) implements GuiElementRenderState {
	private static final Matrix3x2f IDENTITY = new Matrix3x2f();

	public RingRenderState(float cx, float cy, float inner, float outer, int slices, int color) {
		this(cx, cy, inner, outer, slices, color,
				new ScreenRectangle((int) Math.floor(cx - outer), (int) Math.floor(cy - outer), (int) Math.ceil(outer * 2) + 1, (int) Math.ceil(outer * 2) + 1));
	}

	@Override
	public void buildVertices(VertexConsumer consumer) {
		for (int i = 0; i < slices; i++) {
			double a0 = Math.PI * 2 * i / slices;
			double a1 = Math.PI * 2 * (i + 1) / slices;
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			consumer.addVertexWith2DPose(IDENTITY, cx + c0 * outer, cy + s0 * outer).setColor(color);
			consumer.addVertexWith2DPose(IDENTITY, cx + c0 * inner, cy + s0 * inner).setColor(color);
			consumer.addVertexWith2DPose(IDENTITY, cx + c1 * inner, cy + s1 * inner).setColor(color);
			consumer.addVertexWith2DPose(IDENTITY, cx + c1 * outer, cy + s1 * outer).setColor(color);
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI;
	}

	@Override
	public TextureSetup textureSetup() {
		return TextureSetup.noTexture();
	}

	@Override
	public @Nullable ScreenRectangle scissorArea() {
		return null;
	}
}
