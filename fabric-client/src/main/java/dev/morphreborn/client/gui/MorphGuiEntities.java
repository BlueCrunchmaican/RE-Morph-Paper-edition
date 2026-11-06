package dev.morphreborn.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws every selector/radial preview entity into one off-screen texture. Vanilla's GUI entity renderer owns a single
 * texture, so only one entity per frame survives; the original selector shows a whole column of live mobs at once.
 */
public final class MorphGuiEntities {
	/** One preview: feet at GUI ({@code x}, {@code y}), {@code pixelsPerBlock} size, original -10 degree tilt. */
	public record Entry(EntityRenderState state, float x, float y, float pixelsPerBlock) {}

	public record State(List<Entry> entries, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
			implements PictureInPictureRenderState {
		public State(List<Entry> entries, int width, int height) {
			this(entries, 0, 0, width, height, null, new ScreenRectangle(0, 0, width, height));
		}

		@Override
		public float scale() {
			return 1F;
		}
	}

	public static final class Renderer extends PictureInPictureRenderer<State> {
		/**
		 * Original: rotatef(-10, X) then drawEntityOnScreen's scale(1,1,-1) and Z flip. Our base pose already carries the
		 * z mirror, and mirroring negates an X rotation, so the tilt becomes +10 applied after the Z flip.
		 */
		private static final Quaternionf ROTATION = new Quaternionf().rotateX((float) Math.toRadians(10F)).rotateZ((float) Math.PI);

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected float getTranslateY(final int height, final int guiScale) {
			return 0F;
		}

		@Override
		protected void renderToTexture(State state, PoseStack poseStack, SubmitNodeCollector collector) {
			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
			float halfWidth = (state.x1() - state.x0()) / 2F;
			CameraRenderState camera = new CameraRenderState();
			for (Entry e : state.entries()) {
				poseStack.pushPose();
				poseStack.translate(e.x() - state.x0() - halfWidth, e.y() - state.y0(), 0F);
				poseStack.scale(e.pixelsPerBlock(), e.pixelsPerBlock(), e.pixelsPerBlock());
				poseStack.rotate(ROTATION);
				dispatcher.submit(e.state(), camera, 0.0, 0.0, 0.0, poseStack, collector);
				poseStack.popPose();
			}
		}

		@Override
		protected String getTextureLabel() {
			return "morphreborn selector";
		}
	}

	private MorphGuiEntities() {}
}
