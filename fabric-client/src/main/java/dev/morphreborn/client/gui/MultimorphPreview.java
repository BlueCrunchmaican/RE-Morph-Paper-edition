package dev.morphreborn.client.gui;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.net.MorphRef;
import dev.morphreborn.client.render.HybridMorph;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.DragonFlightHistory;
import org.jspecify.annotations.Nullable;

/**
 * Multimorph previews: a main form with a borrowed one drawn on it exactly as the world draws it ({@link HybridMorph}),
 * for the selector and the favourites wheel while a combination is being picked, and for the preview screen
 * ({@code /morphclient preview}).
 */
public final class MultimorphPreview {
	private MultimorphPreview() {}

	/** Morphs that can be part of a multimorph: any creature or player form, and your own body on servers from 7.1.3. */
	public static boolean combinable(MorphRef ref) {
		return !ref.isSelf() || dev.morphreborn.client.net.ClientNet.serverCombinesOwnBody();
	}

	/**
	 * A morph's render state posed for a GUI preview: full light, no shadow or name, body facing {@code bodyRot} and the
	 * head turned {@code headYaw} off it.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static EntityRenderState guiState(LivingEntity living, float bodyRot, float headYaw, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(living);
		EntityRenderState state = renderer.createRenderState(living, 1F);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		state.scoreText = null;
		state.lightCoords = 15728880;
		state.ageInTicks = (mc.player == null ? 0 : mc.player.tickCount) + partialTick;
		// GUI mobs don't tick: a phantom flaps by the clock instead of standing still
		if (state instanceof PhantomRenderState phantom) phantom.flapTime = state.ageInTicks;
		// a GUI dragon has no flight to face along: it faces as a living body would (its renderer turns by -yRot)
		if (state instanceof EnderDragonRenderState dragon) {
			for (int i = 0; i < DragonFlightHistory.LENGTH; i++) dragon.flightHistory.record(0.0, bodyRot - 180F);
		}
		if (state instanceof LivingEntityRenderState l) {
			l.bodyRot = bodyRot;
			l.yRot = headYaw;
			l.xRot = 0F;
			l.boundingBoxWidth /= l.scale;
			l.boundingBoxHeight /= l.scale;
			l.scale = 1F;
			l.hasRedOverlay = false;
			l.deathTime = 0;
			l.walkAnimationSpeed = 0;
		}
		return state;
	}

	/** {@code main}'s preview state with {@code borrowed} drawn on it (alone when null); null if it can't be made. */
	public static @Nullable EntityRenderState hybrid(MorphRef main, @Nullable MorphRef borrowed, float bodyRot, float headYaw, float partialTick) {
		LivingEntity body = MorphClient.ENTITIES.forGui(main);
		if (body == null) return null;
		EntityRenderState state = guiState(body, bodyRot, headYaw, partialTick);
		LivingEntity lent = borrowed == null ? null : MorphClient.ENTITIES.forGui(borrowed);
		if (lent != null) HybridMorph.attach(Minecraft.getInstance().getEntityRenderDispatcher(), state, guiState(lent, bodyRot, headYaw, partialTick));
		return state;
	}

	private static final String DRAGON = "minecraft:ender_dragon";

	/** How much room a preview of the pair needs, in blocks: the main body, widened for wings a borrowed flyer spreads. */
	public static float extent(MorphRef main, @Nullable MorphRef borrowed) {
		LivingEntity body = MorphClient.ENTITIES.forGui(main);
		// the dragon's box (16 by 8) is twice what its model shows in a GUI: framed by it, a dragon body was a speck, and
		// anything it borrows is small beside it
		if (main.entityType().equals(DRAGON)) return 8F;
		float size = body == null ? 2F : Math.max(body.getBbHeight(), body.getBbWidth());
		LivingEntity lent = borrowed == null || borrowed.entityType().equals(DRAGON) ? null : MorphClient.ENTITIES.forGui(borrowed);
		if (lent != null) size = Math.max(size, Math.max(lent.getBbWidth(), body == null ? 0F : body.getBbWidth()) * 1.6F);
		return Math.max(size, 0.5F); // a borrowed dragon's parts are scaled to the body
	}

	/**
	 * A preview panel at ({@code x}, {@code y}), {@code w} by {@code h}: a dark box, {@code title} on top, the pair turning
	 * slowly, and {@code lines} under it. The box is drawn now; the pair goes into {@code entities} (drawn in the HUD's one
	 * off-screen entity pass) and the text into {@code overlays} (drawn above the entities).
	 */
	public static void panel(GuiGraphicsExtractor g, List<MorphGuiEntities.Entry> entities, List<Runnable> overlays, int x, int y, int w, int h,
			MorphRef main, @Nullable MorphRef borrowed, Component title, List<Component> lines, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0E53935);
		g.fill(x, y, x + w, y + h, 0xD0100808);
		int lineH = font.lineHeight + 1;
		int textBottom = lines.size() * lineH + 4;
		float area = h - (font.lineHeight + 8) - textBottom - 6;
		float time = (mc.player == null ? 0 : mc.player.tickCount) + partialTick;
		float yaw = 180F + 25F + (time * 2.5F) % 360F; // a slow turn, so wings and tails show from every side
		float extent = extent(main, borrowed);
		float pixelsPerBlock = Math.min(area / extent, (w - 12) / extent) * 0.85F;
		EntityRenderState state = hybrid(main, borrowed, yaw, 0F, partialTick);
		if (state != null) entities.add(new MorphGuiEntities.Entry(state, x + w / 2F, y + h - textBottom - 6, pixelsPerBlock));
		overlays.add(() -> {
			g.text(font, title, x + (w - font.width(title)) / 2, y + 4, 0xFFFFCDD2, true);
			int ly = y + h - textBottom + 2;
			for (Component line : lines) {
				g.text(font, line, x + (w - font.width(line)) / 2, ly, 0xFFFFFFFF, true);
				ly += lineH;
			}
		});
	}
}
