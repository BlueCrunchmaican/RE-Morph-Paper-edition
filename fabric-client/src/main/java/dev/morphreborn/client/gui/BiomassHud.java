package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * Survival Mode's biomass bar for players with the mod, in the style of the morph itself: the fill is blood red under
 * the morph skin's flickering black static, with tendrils of it rising and swaying along the top, surging when biomass comes in. The
 * text sits on its own dark strip with a shadow so it stays readable on any background. Bottom left; shown for a few
 * seconds after biomass changes, always, or never (client settings). Players without the mod get the action bar.
 */
public final class BiomassHud {
	private static final int WIDTH = 100;
	private static final int BAR_H = 6;
	private static final int SHOW_TICKS = 100;
	private static final int FADE_TICKS = 20;
	/** How long the "+21" floats and the tendrils surge after a gain. */
	private static final int GAIN_TICKS = 30;

	private BiomassHud() {}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientNet.isSurvival() || !ClientNet.biomassUnlocked()) return;
		String mode = ClientConfig.INSTANCE.biomassBar;
		if (mode.equals("OFF")) return;
		float alpha = 1F;
		if (!mode.equals("ALWAYS")) {
			long since = ClientNet.ticksSinceBiomassChange();
			if (since > SHOW_TICKS + FADE_TICKS) return;
			if (since > SHOW_TICKS) alpha = 1F - (since - SHOW_TICKS) / (float) FADE_TICKS;
		}
		int a = Math.round(alpha * 255);
		if (a < 8) return;
		float time = mc.player.tickCount + deltaTracker.getGameTimeDeltaPartialTick(false);
		Font font = mc.font;
		int amount = ClientNet.biomass();
		int capacity = Math.max(1, ClientNet.biomassCapacity());
		boolean full = amount >= capacity;
		int x = 6;
		int barY = g.guiHeight() - 11;
		int textY = barY - 16; // a gap between the text strip and the bar, where the tendrils sway over the world
		int filled = Math.round(WIDTH * Math.min(1F, amount / (float) capacity));
		long sinceGain = ClientNet.ticksSinceGain();
		float surge = sinceGain < GAIN_TICKS ? 1F - sinceGain / (float) GAIN_TICKS : 0F;
		int frame = (int) (time / 2);
		int skin = (a << 24) | 0xFFFFFF;

		// legible strip behind the text
		g.fill(x - 2, textY - 2, x + WIDTH + 2, textY + 9, scaled(0x90, a) << 24);

		// tendrils of the morph's black static rising and swaying from the fill, taller while biomass pours in
		for (int i = 0; filled > 2 && i * 4 + 2 < filled; i++) {
			float sway = (float) Math.sin(time * 0.15 + i * 1.7);
			float reach = 3.5F + 2.5F * (float) Math.sin(time * 0.11 + i * 2.3) + surge * 5F;
			int h = Math.max(1, Math.round(reach));
			int tx = x + i * 4 + 2 + Math.round(sway);
			int seed = (frame * 5 + i * 11) & 0xFFFF;
			blitSkin(g, tx, barY - 1 - h, seed % 62, (seed / 62) % 56, i % 3 == 0 ? 2 : 1, h, skin);
		}

		// the bar: dark grey when empty; the fill is blood red under the morph skin's flickering static
		g.fill(x - 1, barY - 1, x + WIDTH + 1, barY + BAR_H + 1, (scaled(0xE0, a) << 24));
		g.fill(x, barY, x + WIDTH, barY + BAR_H, (scaled(0xD0, a) << 24) | 0x2B2B2B);
		if (filled > 0) {
			g.fill(x, barY, x + filled, barY + BAR_H, (a << 24) | 0x9E1B1B);
			int staticTint = (scaled(0xB0, a) << 24) | 0xFFFFFF;
			for (int sx = 0; sx < filled; sx += 16) {
				int w = Math.min(16, filled - sx);
				int seed = (frame * 13 + sx * 7) & 0xFFFF;
				blitSkin(g, x + sx, barY, seed % 48, (seed / 48) % 58, w, BAR_H, staticTint);
			}
			// a bright leading edge, and a few strands reaching on into the empty part
			if (filled < WIDTH) {
				g.fill(x + filled - 1, barY, x + filled, barY + BAR_H, (a << 24) | 0xFF5252);
				for (int j = 0; j < 3; j++) {
					int len = Math.min(WIDTH - filled, Math.max(1, Math.round(2.5F + 2F * (float) Math.sin(time * 0.2 + j * 2.1) + surge * 4F)));
					int seed = (frame * 3 + j * 29) & 0xFFFF;
					blitSkin(g, x + filled, barY + 1 + j * 2, seed % 60, (seed / 60) % 60, len, 1, skin);
				}
			}
		}

		// label, amount, and the last gain floating up above the amount
		g.text(font, Component.translatable("morphreborn.hud.biomass_label"), x, textY, (a << 24) | 0xBDBDBD, true);
		String value = amount + " / " + capacity + (full ? " FULL" : "");
		int valueX = x + WIDTH - font.width(value);
		g.text(font, value, valueX, textY, (a << 24) | (full ? 0xFFD54F : 0xEF9A9A), true);
		if (sinceGain < GAIN_TICKS * 2 && ClientNet.lastGain() > 0) {
			float t = sinceGain / (GAIN_TICKS * 2F);
			int gainAlpha = Math.round(a * (1F - t));
			if (gainAlpha > 8) {
				String gain = "+" + ClientNet.lastGain();
				g.text(font, gain, x + WIDTH - font.width(gain), textY - 11 - Math.round(t * 8), (gainAlpha << 24) | 0xFF6E6E, true);
			}
		}
	}

	private static void blitSkin(GuiGraphicsExtractor g, int x, int y, int u, int v, int w, int h, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, MorphWorldRenderer.MORPH_SKIN, x, y, u, v, w, h, 64, 64, color);
	}

	private static int scaled(int value, int alpha) {
		return value * alpha / 255;
	}
}
