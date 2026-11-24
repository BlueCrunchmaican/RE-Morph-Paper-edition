package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * The goo bar: an arm's durability bar is your biomass (the server writes it so), and with the mod it is drawn as a
 * small piece of the biomass bar instead of vanilla's green-to-red line: blood red under the morph skin's static,
 * re-patterned every two ticks, its top rippling, a bright seam where the fill ends, and now and then a drop of goo
 * hanging under it. Below a quarter the seam pulses; below a tenth the static flickers every tick, starving.
 * <p>
 * Drawn after vanilla's decorations (ExtractItemDecorationsCallback runs at their end) over the same 13 by 2 pixels, a
 * pixel of black skin round it, so the plain bar never shows through. The fill follows your live biomass to the
 * thousandth while Survival's numbers are there (the stack's own bar only moves when the server rewrites it, which it
 * holds off while you dig), and the stack's bar otherwise. Only arm stacks with a bar showing (Classic arms have none),
 * and only with the client setting on GOO.
 */
public final class ArmBarDecoration {
	private static final int WIDTH = 13;

	private ArmBarDecoration() {}

	public static void draw(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
		if (!stack.isBarVisible() || !"GOO".equals(ClientConfig.INSTANCE.armBar) || !ArmStacks.isArm(stack)) return;
		float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float time = ArmWheel.uiTime(pt);
		float frac = fraction(stack);
		int filled = Math.clamp(Math.round(WIDTH * frac), frac > 0F ? 1 : 0, WIDTH);
		boolean starving = frac < 0.1F;
		int frame = starving ? (int) time : (int) (time / 2);
		int left = x + 2;
		int top = y + 13;

		// the skin: a black rim round the whole bar
		g.fill(left - 1, top - 1, left + WIDTH + 1, top + 3, 0xF0000000);
		int baseU = (frame * 13) & 63;
		int baseV = (frame * 29) % 50;
		for (int c = 0; c < WIDTH; c++) {
			boolean in = c < filled;
			// the goo's top ripples up a pixel where it swells
			int lift = in && Mth.sin(c * 0.9F + time * 0.25F) > 0.55F ? 1 : 0;
			int y0 = top - lift;
			g.fill(left + c, y0, left + c + 1, top + 2, in ? 0xFF9E1B1B : 0xFF231C1E);
			if (in) {
				g.blit(RenderPipelines.GUI_TEXTURED, MorphWorldRenderer.MORPH_SKIN, left + c, y0, (baseU + c * 3) & 63, (baseV + c * 7) % 60, 1, 2 + lift, 64, 64,
						0xB0FFFFFF);
			}
		}
		// the edge of the fill: bright, and pulsing once it runs low
		if (filled > 0 && filled < WIDTH) {
			int seam = 0xFF5252;
			int alpha = frac < 0.25F ? Math.round(0x90 + 0x6F * (0.5F + 0.5F * Mth.sin(time * 0.6F))) : 0xFF;
			g.fill(left + filled - 1, top, left + filled, top + 2, (alpha << 24) | seam);
		}
		// a drop of goo gathering under the fill, then falling off the slot
		if (filled > 2) {
			float phase = Mth.frac(time * 0.012F + (x * 31 + y * 17) * 0.013F);
			int c = 1 + Math.floorMod(x * 7 + y * 3, filled - 1);
			if (phase > 0.55F && phase < 0.8F) g.fill(left + c, top + 2, left + c + 1, top + 3, 0xFF8E1616);
			else if (phase >= 0.8F && phase < 0.92F) g.fill(left + c, top + 3, left + c + 1, top + 4, 0xC08E1616);
		}
	}

	/**
	 * How full the bar is: live biomass over capacity while Survival's numbers are known (the bar is biomass), else the
	 * stack's own durability bar (the server keeps damage at capacity minus biomass).
	 */
	private static float fraction(ItemStack stack) {
		int capacity = ClientNet.biomassCapacity();
		if (ClientNet.isSurvival() && ClientNet.biomassUnlocked() && capacity > 0) {
			return Mth.clamp(ClientNet.biomassMilli() / (capacity * 1000F), 0F, 1F);
		}
		return Mth.clamp(stack.getBarWidth() / (float) WIDTH, 0F, 1F);
	}
}
