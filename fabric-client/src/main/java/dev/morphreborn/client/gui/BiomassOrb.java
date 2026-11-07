package dev.morphreborn.client.gui;

import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * A round lump of biomass: the morph's black static inside a ring, with tendrils reaching out of it that sway and
 * stretch. The favourites wheel's Biomass button and the upgrade tree's Morph Ability are drawn with it.
 * <p>
 * It is drawn in its own units, 32 across (tendrils reach about 13 further), scaled to the size asked for.
 */
public final class BiomassOrb {
	/** Radius in orb units. */
	public static final int RADIUS = 16;
	private static final int TENDRILS = 9;

	private BiomassOrb() {}

	/**
	 * @param scale GUI pixels per orb unit (1 = 32 pixels across)
	 * @param time  ticks plus the partial tick; moves the tendrils and the static
	 * @param ring  the ring's colour
	 * @param tint  laid over the static inside (translucent)
	 * @param glow  0-1: a soft red halo (hovered, or something to buy)
	 */
	public static void draw(GuiGraphicsExtractor g, float cx, float cy, float scale, float time, int ring, int tint, float glow) {
		if (scale <= 0.05F) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		if (glow > 0) {
			int a = Math.round(Math.min(1F, glow) * 0x60);
			disc(g, RADIUS + 7, ((a / 3) << 24) | 0xE53935);
			disc(g, RADIUS + 4, ((a * 2 / 3) << 24) | 0xE53935);
		}
		for (int i = 0; i < TENDRILS; i++) tendril(g, i, time);
		disc(g, RADIUS, 0xFF000000);
		disc(g, RADIUS - 1, ring);
		staticDisc(g, RADIUS - 3, time);
		disc(g, RADIUS - 3, tint);
		g.pose().popMatrix();
	}

	/** Whether a point is inside the orb (not its tendrils) drawn at this centre and scale. */
	public static boolean contains(double cx, double cy, float scale, double x, double y) {
		double r = RADIUS * scale;
		return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r;
	}

	/** A filled circle, one row of pixels at a time. */
	private static void disc(GuiGraphicsExtractor g, int r, int color) {
		for (int y = -r; y < r; y++) {
			double dy = y + 0.5;
			int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
			if (half > 0) g.fill(-half, y, half, y + 1, color);
		}
	}

	/** The morph's static clipped to a circle, a new pattern every two ticks like the transformation skin. */
	private static void staticDisc(GuiGraphicsExtractor g, int r, float time) {
		int frame = (int) (time / 2);
		for (int y = -r; y < r; y++) {
			double dy = y + 0.5;
			int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
			if (half <= 0) continue;
			int w = half * 2;
			int seed = (frame * 31 + (y + r) * 17 + frame * (y + r) * 7) & 0xFFFF;
			int u = seed % Math.max(1, 64 - w);
			int v = (seed / 64) % 64;
			g.blit(RenderPipelines.GUI_TEXTURED, MorphWorldRenderer.MORPH_SKIN, -half, y, u, v, w, 1, 64, 64);
		}
	}

	/** One tendril: a wavy, tapering line from inside the ring outwards, its length breathing in and out. */
	private static void tendril(GuiGraphicsExtractor g, int i, float time) {
		double angle = Math.PI * 2 * i / TENDRILS + 0.3 * Math.sin(time * 0.015 + i * 1.9);
		double length = 9 + 4 * Math.sin(time * 0.055 + i * 2.3);
		double dx = Math.cos(angle);
		double dy = Math.sin(angle);
		for (double s = 0; s <= length; s += 0.8) {
			double t = s / length;
			double wobble = Math.sin(time * 0.16 + s * 0.5 + i * 1.7) * 2.6 * t;
			double reach = RADIUS - 2 + s;
			float px = (float) (dx * reach - dy * wobble);
			float py = (float) (dy * reach + dx * wobble);
			float size = (float) (3.4 - 2.4 * t);
			int alpha = t < 0.75 ? 0xFF : (int) (0xFF * (1 - (t - 0.75) / 0.25));
			int color = (alpha << 24) | lerp(0x5A0E16, 0xC62828, t);
			g.pose().pushMatrix();
			g.pose().translate(px - size / 2, py - size / 2);
			g.pose().scale(size, size);
			g.fill(0, 0, 1, 1, color);
			g.pose().popMatrix();
		}
	}

	private static int lerp(int from, int to, double t) {
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int gr = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (r << 16) | (gr << 8) | b;
	}
}
