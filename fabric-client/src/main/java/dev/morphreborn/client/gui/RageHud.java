package dev.morphreborn.client.gui;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.Keys;
import dev.morphreborn.client.net.ClientNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The rage meter (servers from 7.2.0): one strip in the biomass bar's style, just above the bar when the bar shows and
 * in its place when it doesn't (Classic has no bar). "RAGE", then a segment for each hit it takes to charge it, lit as
 * hits come in and going out again as they get old; charged, the segments glow, the label pulses and it says READY
 * until the Rage key sets it off; while raging, a bar running down with the seconds left; after a rage, a bar filling
 * back up during the wait, and the time left. It follows the biomass bar's display setting (when it changes, always,
 * never), and always shows while charged or raging.
 */
public final class RageHud {
	private static final int SHOW_TICKS = 100;
	private static final int FADE_TICKS = 20;
	/** At most this many hit segments; more hits share them. */
	private static final int MAX_SEGMENTS = 20;
	private static final int BAR = 40;

	private static boolean hintShown;

	private RageHud() {}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientNet.rageAvailable()) return;
		String mode = ClientConfig.INSTANCE.biomassBar;
		if (mode.equals("OFF")) return;
		boolean raging = ClientNet.raging();
		boolean ready = ClientNet.rageReady();
		long since = ClientNet.ticksSinceRageChange();
		if (ready && Keys.RAGE.isUnbound() && !hintShown) {
			hintShown = true; // once a game: the key starts unbound. In chat: on the action bar the server's lines hid it
			// servers before 7.3.0 have /morph rage only
			String hint = ClientNet.serverAtLeast("7.3.0-beta.1") ? "morphreborn.rage.bind_hint" : "morphreborn.rage.bind_hint_old";
			mc.player.sendSystemMessage(Component.translatable(hint).withStyle(ChatFormatting.GRAY));
		}
		float alpha = 1F;
		boolean withBar = BiomassHud.shownAlpha > 8;
		if (!mode.equals("ALWAYS") && !raging && !ready) {
			// shown with the biomass bar, and for a while after the meter changes
			float own = since > SHOW_TICKS + FADE_TICKS ? 0F : since > SHOW_TICKS ? 1F - (since - SHOW_TICKS) / (float) FADE_TICKS : 1F;
			alpha = Math.max(own, withBar ? BiomassHud.shownAlpha / 255F : 0F);
		}
		int a = Math.round(alpha * 255);
		if (a < 8) return;
		float time = mc.player.tickCount + deltaTracker.getGameTimeDeltaPartialTick(false);
		Font font = mc.font;

		int guiScale = (int) Math.round(mc.getWindow().getGuiScale());
		float s = withBar ? BiomassHud.shownScale : guiScale >= 4 ? (guiScale - 1) / (float) guiScale : 1F;
		g.pose().pushMatrix();
		g.pose().translate(0, g.guiHeight());
		g.pose().scale(s, s);
		int x = 6;
		// the biomass bar's label strip sits at -27; this goes on top of it, or in its place
		int y = withBar ? -27 - 13 : -27;

		Component label = Component.translatable("morphreborn.hud.rage_label");
		int labelW = font.width(label);
		int hits = ClientNet.rageHits();
		int needed = Math.max(1, ClientNet.rageHitsNeeded());
		int waitLeft = ClientNet.rageWaitLeft();
		int wait = ClientNet.rageWait();
		int rageLeft = ClientNet.rageLeft();
		int rageLength = ClientNet.rageLength();
		boolean waiting = !raging && waitLeft > 0 && wait > 0;
		int segments = Math.min(needed, MAX_SEGMENTS);
		String status = raging ? (rageLeft + 19) / 20 + "s" : waiting ? clock(waitLeft)
				: ready ? Component.translatable("morphreborn.hud.rage_ready").getString() : "";
		int meterW = raging || waiting ? BAR : segments * 4 - 1;
		int width = labelW + 4 + meterW + (status.isEmpty() ? 0 : 5 + font.width(status));

		g.fill(x - 2, y - 2, x + width + 2, y + 9, scaled(0x90, a) << 24);
		float pulse = 0.5F + 0.5F * (float) Math.sin(time * (raging ? 0.9 : 0.25));
		int labelColor = raging || ready ? lerp(0xC62828, 0xFF5252, pulse) : hits > 0 ? 0xE57373 : 0xBDBDBD;
		g.text(font, label, x, y, (a << 24) | labelColor, true);
		int mx = x + labelW + 4;
		if (raging) {
			// the rage running out: a bright bar that shrinks
			int filled = rageLength <= 0 ? BAR : Math.round(BAR * Math.clamp(rageLeft / (float) rageLength, 0F, 1F));
			g.fill(mx, y + 2, mx + BAR, y + 7, (a << 24) | 0x3A1416);
			if (filled > 0) g.fill(mx, y + 2, mx + filled, y + 7, (a << 24) | lerp(0xC62828, 0xFF5252, pulse));
		} else if (waiting) {
			// the wait: a bar that fills back up as it runs out
			int filled = Math.round(BAR * (1F - waitLeft / (float) wait));
			g.fill(mx, y + 3, mx + BAR, y + 6, (a << 24) | 0x3A1416);
			if (filled > 0) g.fill(mx, y + 3, mx + filled, y + 6, (a << 24) | 0x7A2224);
		} else {
			// a segment per hit (shared when there are many), lit as hits come in; all glowing when charged
			for (int i = 0; i < segments; i++) {
				boolean lit = ready || (long) hits * segments >= (long) (i + 1) * needed;
				int color = !lit ? 0x3A1416 : ready ? lerp(0x9E1B1B, 0xE53935, pulse) : 0xC62828;
				g.fill(mx + i * 4, y + 1, mx + i * 4 + 3, y + 7, (a << 24) | color);
			}
		}
		if (!status.isEmpty()) g.text(font, status, mx + meterW + 5, y, (a << 24) | (raging ? 0xFF8A80 : ready ? 0xFFD54F : 0xBDBDBD), true);
		g.pose().popMatrix();
	}

	/** "0:42", or "1:05". */
	private static String clock(int ticks) {
		int seconds = (ticks + 19) / 20;
		return seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
	}

	private static int lerp(int from, int to, float t) {
		int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int gr = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (r << 16) | (gr << 8) | b;
	}

	private static int scaled(int value, int alpha) {
		return value * alpha / 255;
	}
}
