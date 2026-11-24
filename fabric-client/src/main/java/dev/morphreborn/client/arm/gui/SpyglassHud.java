package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.fx.SpyglassFx;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The spyglass's readout (2026-11-16): while you zoom, each creature the server has marked (SpyglassFx: up to the level's
 * cap, 1 / 2 / 3) is named under the crosshair, one line each, with its distance in blocks. Drawn after everything the game does, so
 * it sits over the scope's vignette. Nothing is allocated per frame: a line's name and distance are built again only when its
 * creature or the distance's tenth changes.
 */
public final class SpyglassHud {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "spyglass");
	/** Pixels under the screen's middle where the readout starts, and between its two lines. */
	private static final int DROP = 14, LINE = 11;
	/** Pixels between a line's name and its distance. */
	private static final int GAP = 6;

	/** What each line shows, as built last time (by line, the order the marks are in). */
	private static final int[] SHOWN_ID = new int[SpyglassFx.MAX], SHOWN_TENTHS = new int[SpyglassFx.MAX];
	private static final @Nullable Component[] NAME = new Component[SpyglassFx.MAX], DISTANCE = new Component[SpyglassFx.MAX];

	static {
		reset();
	}

	private SpyglassHud() {}

	/** ArmUi's init. */
	public static void init() {
		HudElementRegistry.addLast(ID, SpyglassHud::render);
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		if (!SpyglassZoom.zooming()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return; // (F1 hides the HUD elements altogether)
		float pt = deltaTracker.getGameTimeDeltaPartialTick(false);
		LocalPlayer p = mc.player;
		Font font = mc.font;
		int cx = g.guiWidth() / 2, y = g.guiHeight() / 2 + DROP;
		int line = 0;
		// in the order marked; a creature that is gone is dropped from the marks as it is met (the next one then takes its index)
		for (int i = 0; i < SpyglassFx.count();) {
			int id = SpyglassFx.id(i);
			Entity e = mc.level.getEntity(id);
			if (e == null || e.isRemoved()) {
				SpyglassFx.remove(id); // gone: out of range, killed, or unloaded
				continue;
			}
			i++;
			if (line >= SHOWN_ID.length) break;
			// from your eye to the creature's middle, both as they are drawn this frame (no vectors made per frame)
			double dx = Mth.lerp(pt, e.xo, e.getX()) - Mth.lerp(pt, p.xo, p.getX());
			double dy = Mth.lerp(pt, e.yo, e.getY()) + e.getBbHeight() / 2D - (Mth.lerp(pt, p.yo, p.getY()) + p.getEyeHeight());
			double dz = Mth.lerp(pt, e.zo, e.getZ()) - Mth.lerp(pt, p.zo, p.getZ());
			int tenths = tenths(Math.sqrt(dx * dx + dy * dy + dz * dz));
			if (id != SHOWN_ID[line] || tenths != SHOWN_TENTHS[line] || NAME[line] == null) {
				SHOWN_ID[line] = id;
				SHOWN_TENTHS[line] = tenths;
				NAME[line] = e.getDisplayName();
				DISTANCE[line] = Component.translatable("morphreborn.spyglass.distance", format(tenths));
			}
			Component n = NAME[line], dist = DISTANCE[line];
			line++;
			if (n == null || dist == null) continue;
			int nw = font.width(n), x = cx - (nw + GAP + font.width(dist)) / 2;
			// on the same dark strip as the biomass bar's text: white and gold on snow, sky or sand was unreadable
			g.fill(x - 2, y - 1, x + nw + GAP + font.width(dist) + 2, y + font.lineHeight + 1, 0x90000000);
			g.text(font, n, x, y, 0xFFFFFFFF, true);
			g.text(font, dist, x + nw + GAP, y, 0xFFFFD54F, true);
			y += LINE;
		}
	}

	/** {@code blocks} in tenths of a block, rounded; never negative. */
	static int tenths(double blocks) {
		return (int) Math.max(0L, Math.round(blocks * 10D));
	}

	/** {@code tenths} as "23.4" (the locale's decimal point is not used: the readout reads the same everywhere). */
	static String format(int tenths) {
		return (tenths / 10) + "." + (tenths % 10);
	}

	/** Forgets what is cached (leaving a world). */
	public static void reset() {
		java.util.Arrays.fill(SHOWN_ID, -1);
		java.util.Arrays.fill(SHOWN_TENTHS, -1);
		java.util.Arrays.fill(NAME, null);
		java.util.Arrays.fill(DISTANCE, null);
	}
}
