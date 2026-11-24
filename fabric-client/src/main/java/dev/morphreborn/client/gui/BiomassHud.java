package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.gui.ArmWheel;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Survival Mode's biomass bar for players with the mod, in the style of the morph itself: a living lump of biomass
 * whose edges ripple and drip, the fill blood red under the morph skin's flickering static, with black and red
 * tendrils swaying along the top, all of it surging when biomass comes in. The text sits on its own dark strip with a
 * shadow so it stays readable on any background; when storage is full, the label reads FULL and the text turns
 * yellow. Bottom left; shown for a few seconds after biomass changes, always, or never (client settings). Players
 * without the mod get the action bar.
 * <p>
 * Arm forms (servers from 8.0.0) spend biomass in tenths, so the fill follows it to the thousandth; a spend falls away
 * past the amount as a dark red "−0.25"; and while something drains it (a formed arm, a lit lantern) the bar stays up,
 * the arm (or the lantern's lure) sits just past its end, and strands of goo are pulled out of the fill into it.
 */
public final class BiomassHud {
	private static final int WIDTH = 100;
	private static final int BAR_H = 6;
	private static final int SHOW_TICKS = 100;
	private static final int FADE_TICKS = 20;
	/** How long the "+21" floats and the tendrils surge after a gain. */
	private static final int GAIN_TICKS = 30;
	/** How long the "−0.25" of a spend takes to fall away, and the least spend that gets one (a tenth: one block). */
	private static final int SPEND_TICKS = 40;
	private static final long SPEND_SHOWN_MILLI = 100;

	/** This frame's bar: its opacity (0: not drawn) and the scale it was drawn at, for the rage meter above it. */
	static int shownAlpha;
	static float shownScale = 1F;
	/** How far (screen px) the cluster was raised over the survival HUD this frame, for the frenzy meter that sits with it. */
	static int shownLift;
	/** Holds the raise for a while once it was needed, so an offhand item or an arm coming and going does not make the cluster jump. */
	private static final HudLayout.Latch LIFT = new HudLayout.Latch();

	private BiomassHud() {}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		shownAlpha = 0;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientNet.isSurvival() || !ClientNet.biomassUnlocked()) return;
		String mode = ClientConfig.INSTANCE.biomassBar;
		if (mode.equals("OFF")) return;
		float pt = deltaTracker.getGameTimeDeltaPartialTick(false);
		// arm forms (8.0.0): something is draining biomass (a formed arm, a lit lantern), and the last thing that spent it
		boolean draining = ClientNet.drainMilliPerMinute() > 0;
		long sinceSpend = Arms.TICK - ClientNet.lastSpendAt();
		float alpha = 1F;
		if (!mode.equals("ALWAYS") && !draining) {
			// while something drains, biomass is changing all the time, so "when it changes" shows the bar throughout
			long since = Math.min(ClientNet.ticksSinceBiomassChange(), Math.max(0, sinceSpend));
			if (since > SHOW_TICKS + FADE_TICKS) return;
			if (since > SHOW_TICKS) alpha = 1F - (since - SHOW_TICKS) / (float) FADE_TICKS;
		}
		int a = Math.round(alpha * 255);
		if (a < 8) return;
		float time = mc.player.tickCount + pt;
		Font font = mc.font;
		int amount = ClientNet.biomass();
		int capacity = Math.max(1, ClientNet.biomassCapacity());
		boolean full = amount >= capacity;

		// At large GUI scales the bar is drawn one scale step smaller (5 instead of 6, 3 instead of 4), so it stays a
		// corner readout and its pixels stay whole.
		int guiScale = (int) Math.round(mc.getWindow().getGuiScale());
		float s = guiScale >= 4 ? (guiScale - 1) / (float) guiScale : 1F;
		shownAlpha = a;
		shownScale = s;
		// the room the hotbar leaves (and what the arm or lure past the bar's end needs): the bar is narrower on a small screen, and the whole
		// cluster rises over the survival HUD when there is hardly any (it ran over the hotbar's first slots under about 394 scaled px wide)
		ArmState own = Arms.state(mc.player.getUUID());
		boolean offhand = !mc.player.getOffhandItem().isEmpty();
		int past = own != null && ((own.armed() && ArmWheel.hasItem(own.heldForm())) || own.lanternLit) ? 16 : 0;
		// the bar is never narrower than its widest amount, so the number cannot run off the left edge; when that much room is not beside the hotbar the cluster rises
		int least = Math.max(HudLayout.MIN_BAR, font.width(Math.max(amount, capacity) + " / " + capacity));
		int lift = LIFT.apply(HudLayout.lift(g.guiWidth(), s, offhand, least + past), System.nanoTime());
		shownLift = lift;
		g.pose().pushMatrix();
		g.pose().translate(0, g.guiHeight() - lift);
		g.pose().scale(s, s);

		// "BIOMASS" (or "FULL") on the left, the amount on the right. The bar is as wide as the widest amount this
		// capacity can show, so the two never meet and the bar doesn't jump around as the number changes.
		Component label = Component.translatable(full ? "morphreborn.hud.biomass_full" : "morphreborn.hud.biomass_label");
		String value = amount + " / " + capacity;
		int labelW = Math.max(font.width(Component.translatable("morphreborn.hud.biomass_label")), font.width(Component.translatable("morphreborn.hud.biomass_full")));
		int width = HudLayout.barWidth(g.guiWidth(), s, offhand, lift > 0, Math.max(WIDTH, labelW + 6 + font.width(capacity + " / " + capacity)), past, least);
		// a bar too narrow for both keeps the amount and drops the word
		boolean showLabel = width >= labelW + 6 + font.width(value);
		int x = HudLayout.LEFT;
		int barY = -11;
		int textY = barY - 16; // a gap between the text strip and the bar, where the tendrils sway over the world
		// filled to the thousandth (servers from 8.0.0 send it; arm forms spend tenths), so a block or the drain moves it
		int filled = Math.round(width * Math.min(1F, ClientNet.biomassMilli() / (capacity * 1000F)));
		long sinceGain = ClientNet.ticksSinceGain();
		float surge = sinceGain < GAIN_TICKS ? 1F - sinceGain / (float) GAIN_TICKS : 0F;
		int frame = (int) (time / 2);

		// legible strip behind the text
		g.fill(x - 2, textY - 2, x + width + 2, textY + 9, scaled(0x90, a) << 24);

		// The bar is a living lump of biomass, not a box: its top and bottom edges ripple, its ends are rounded, the
		// boundary between full and empty bulges and pulses, drops of goo gather on its underside and fall, and tendrils
		// sway along its top. Everything moves harder for a moment when biomass comes in. Filled: blood red under the
		// morph skin's flickering black static; empty: dark goo; a black skin holds it all together.
		int[] top = new int[width];
		int[] bottom = new int[width];
		float amp = 1F + surge * 1.4F;
		float pulse = 0.5F + 0.35F * (float) Math.sin(time * 0.3);
		for (int c = 0; c < width; c++) {
			float up = (float) (Math.sin(c * 0.31 + time * 0.13) * 0.7 + Math.sin(c * 0.13 - time * 0.07 + 1.3) * 0.6) * amp;
			float down = (float) (Math.sin(c * 0.27 - time * 0.11 + 2.1) * 0.7 + Math.sin(c * 0.09 + time * 0.05) * 0.6) * amp;
			int end = Math.min(c, width - 1 - c);
			int inset = end == 0 ? 2 : end == 1 ? 1 : 0;
			int bulge = 0;
			if (filled > 0 && filled < width && Math.abs(c - filled) <= 3) bulge = Math.round((3 - Math.abs(c - filled)) * pulse);
			top[c] = barY - Math.round(up) + inset - bulge;
			bottom[c] = barY + BAR_H + Math.round(down) - inset + bulge;
			if (bottom[c] - top[c] < 2) bottom[c] = top[c] + 2;
		}
		int skinColor = scaled(0xE6, a) << 24;
		int staticTint = (scaled(0xB0, a) << 24) | 0xFFFFFF;
		int baseU = (frame * 13) & 63;
		int baseV = (frame * 29) % 50;
		g.fill(x - 1, top[0], x, bottom[0], skinColor);
		g.fill(x + width, top[width - 1], x + width + 1, bottom[width - 1], skinColor);
		for (int c = 0; c < width; c++) {
			int px = x + c;
			boolean inFill = c < filled;
			g.fill(px, top[c] - 1, px + 1, bottom[c] + 1, skinColor);
			g.fill(px, top[c], px + 1, bottom[c], inFill ? (a << 24) | 0x9E1B1B : (scaled(0xD0, a) << 24) | 0x231C1E);
			if (inFill) blitSkin(g, px, top[c], (baseU + c) & 63, Math.floorMod(baseV + top[c] - barY, 50), 1, bottom[c] - top[c], staticTint);
		}

		// the edge of the fill: a bright seam, and strands of red goo reaching on into the empty part
		if (filled > 0 && filled < width) {
			g.fill(x + filled - 1, top[filled - 1], x + filled, bottom[filled - 1], (a << 24) | 0xFF5252);
			for (int j = 0; j < 3; j++) {
				int len = Math.min(width - filled, Math.max(1, Math.round(2.5F + 2F * (float) Math.sin(time * 0.2 + j * 2.1) + surge * 4F)));
				int sy = barY + 1 + j * 2;
				for (int k = 0; k < len; k++) {
					float t = (k + 1) / (float) len;
					int wy = sy + Math.round((float) Math.sin(time * 0.3 + k * 0.8 + j) * 0.6F);
					g.fill(x + filled + k, wy, x + filled + k + 1, wy + 1, (Math.round(a * (1F - t * 0.6F)) << 24) | lerp(0xC62828, 0x1A0A0C, t));
				}
			}
		}

		// drops of goo: a thread gathers on the underside, then a drop breaks off and falls away
		for (int j = 0; j * 13 + 6 < width - 2; j++) {
			int c = j * 13 + 6 + (j * 7) % 5;
			if (c > width - 2) break; // the jitter can carry the last drop past the end (a 100-wide bar crashed at 101)
			float phase = (float) ((time * (0.010 + (j % 3) * 0.004) + j * 0.37) % 1.0);
			int color = c < filled ? 0x8E1616 : 0x101010;
			int base = bottom[c];
			if (phase < 0.75F) {
				int len = Math.round(phase / 0.75F * 3F);
				if (len > 0) g.fill(x + c, base, x + c + 1, base + len, (a << 24) | color);
				if (len >= 3) g.fill(x + c, base + len - 1, x + c + 2, base + len + 1, (a << 24) | color);
			} else {
				float t = (phase - 0.75F) / 0.25F;
				int dy = 3 + Math.round(t * t * 8F);
				int dropA = Math.round(a * (1F - t));
				if (dropA > 8) g.fill(x + c, base + dy, x + c + 2, base + dy + 2, (dropA << 24) | color);
			}
		}

		// tendrils along the top, black and red: red ones rise from the fill, black ones from all of it (shorter over the
		// empty part); all of them taller while biomass pours in, thinning and fading at the tip
		for (int i = 0; i * 3 + 2 < width - 1; i++) {
			int c = i * 3 + 2;
			boolean red = c < filled && i % 2 == 0;
			float reach = red ? 3F + 2.5F * (float) Math.sin(time * 0.11 + i * 2.3) + surge * 5F
					: (c < filled ? 2.5F : 1.5F) + 2F * (float) Math.sin(time * 0.09 + i * 1.9) + (c < filled ? surge * 4F : 0F);
			int h = Math.round(reach);
			if (h < 1) continue;
			int base = top[c] - 1;
			for (int dy = 0; dy < h; dy++) {
				float t = (dy + 1) / (float) h;
				int sway = Math.round((float) Math.sin(time * 0.15 + i * 1.7 + dy * 0.5) * t * 1.5F);
				int strandA = t < 0.6F ? a : Math.round(a * (1F - (t - 0.6F) / 0.5F));
				if (strandA <= 8) continue;
				int color = red ? lerp(0x8E1616, 0x4A0A10, t) : lerp(0x0C0C0C, 0x2A1418, t);
				g.fill(x + c + sway, base - 1 - dy, x + c + sway + 1, base - dy, (strandA << 24) | color);
			}
		}

		// Arm forms: what is drinking the biomass sits just past the bar's end (your arm, or the lantern's lure), and while
		// it drains, strands of goo are pulled out of the fill's edge along the bar into it, the reverse of a gain's surge
		int iconX = x + width + 4;
		int iconY = barY + BAR_H / 2;
		if (draining) drainStrands(g, x, filled, width, barY, iconX, time, a);
		if (alpha >= 0.5F && own != null) {
			if (own.armed() && ArmWheel.hasItem(own.heldForm())) {
				ArmWheel.drawIcon(g, own.heldForm(), Math.max(1, own.level), iconX, iconY - 6, 0.75F, ArmWheel.turntable(time), 20F, 0xFFFFFFFF);
			} else if (own.lanternLit) {
				lure(g, iconX + 6, iconY, time, a);
			}
		}

		// label, amount, and the last gain floating up above the amount
		if (showLabel) g.text(font, label, x, textY, (a << 24) | (full ? 0xFFD54F : 0xBDBDBD), true);
		g.text(font, value, x + width - font.width(value), textY, (a << 24) | (full ? 0xFFD54F : 0xEF9A9A), true);
		if (sinceGain < GAIN_TICKS * 2 && ClientNet.lastGain() > 0) {
			float t = sinceGain / (GAIN_TICKS * 2F);
			int gainAlpha = Math.round(a * (1F - t));
			if (gainAlpha > 8) {
				String gain = "+" + ClientNet.lastGain();
				g.text(font, gain, x + width - font.width(gain), textY - 11 - Math.round(t * 8), (gainAlpha << 24) | 0xFF6E6E, true);
			}
		}
		// and the last spend (a block, a hit, a special: a tenth or more; the drain's own trickle isn't worth a number)
		// falling away past the amount like a drop, dark red
		long spent = ClientNet.lastSpendMilli();
		if (sinceSpend >= 0 && sinceSpend < SPEND_TICKS && spent >= SPEND_SHOWN_MILLI) {
			float t = Math.min(1F, (sinceSpend + pt) / SPEND_TICKS);
			int spendAlpha = Math.round(a * (1F - t * t));
			if (spendAlpha > 8) {
				Component spend = Component.translatable("morphreborn.hud.arm_spend", ArmWheel.cost(spent));
				g.text(font, spend, x + width + 4, textY + Math.round(t * t * 10F), (spendAlpha << 24) | 0xE53935, true);
			}
		}
		g.pose().popMatrix();
	}

	/**
	 * While something drains biomass: three strands of red goo pulled from the fill's edge along the empty part of the bar
	 * (or straight off the end when it is full) into whatever drinks it at {@code toX}, in pieces that travel as they go,
	 * swaying, thinning as they arrive.
	 */
	private static void drainStrands(GuiGraphicsExtractor g, int x, int filled, int width, int barY, int toX, float time, int a) {
		int from = x + Math.max(0, filled - 1);
		int length = toX - from;
		if (length < 2) return;
		for (int j = 0; j < 3; j++) {
			int sy = barY + 1 + j * 2;
			for (int k = 0; k < length; k++) {
				// pieces three pixels long every seven, moving along at about a pixel a tick
				float phase = k - time * 0.9F - j * 2.3F;
				if (Math.floorMod((int) Math.floor(phase), 7) > 2) continue;
				float t = (k + 1) / (float) length;
				int wy = sy + Math.round(Mth.sin(time * 0.3F + k * 0.6F + j * 1.7F) * 0.8F);
				int strandA = Math.round(a * (1F - t * 0.5F));
				g.fill(from + k, wy, from + k + 1, wy + 1, (strandA << 24) | lerp(0xE53935, 0x7A1018, t));
			}
		}
	}

	/** The lantern's lure, as a small dot of light on a curled stalk, for a lantern lit with no arm out. */
	private static void lure(GuiGraphicsExtractor g, int cx, int cy, float time, int a) {
		for (int k = 0; k <= 6; k++) {
			float u = k / 6F;
			float sx = cx - 5F + 5F * u - 2F * Mth.sin(u * Mth.PI);
			float sy = cy + 5F - 6F * u;
			Tendrils.dot(g, sx, sy, 1.6F - 0.6F * u, (a << 24) | lerp(0x1A0406, 0x5A0E16, u));
		}
		float pulse = 0.5F + 0.5F * Mth.sin(time * 0.25F);
		Tendrils.dot(g, cx, cy - 1, 5F + pulse, (Math.round(a * (0.25F + 0.2F * pulse)) << 24) | 0xFF5A4A);
		Tendrils.dot(g, cx, cy - 1, 2.6F, (a << 24) | 0xFF5A4A);
		Tendrils.dot(g, cx, cy - 1, 1.2F, (a << 24) | 0xFFD0C0);
	}

	private static int lerp(int from, int to, float t) {
		int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int gr = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (r << 16) | (gr << 8) | b;
	}

	private static void blitSkin(GuiGraphicsExtractor g, int x, int y, int u, int v, int w, int h, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, MorphWorldRenderer.MORPH_SKIN, x, y, u, v, w, h, 64, 64, color);
	}

	private static int scaled(int value, int alpha) {
		return value * alpha / 255;
	}
}
