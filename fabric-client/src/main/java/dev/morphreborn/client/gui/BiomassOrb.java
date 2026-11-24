package dev.morphreborn.client.gui;

import dev.morphreborn.client.render.MorphWorldRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * A round lump of biomass: the morph's black static inside a ring, with tendrils reaching out of it that sway and
 * stretch. The favourites wheel's Biomass button, the Arsenal wheel's middle and the upgrade tree's Morph Ability are
 * drawn with it; the tree's Arm Shaping is its bladed kin ({@link #drawBlade}).
 * <p>
 * It is drawn in its own units, 32 across (tendrils reach about 13 further), scaled to the size asked for.
 */
public final class BiomassOrb {
	/** Radius in orb units. */
	public static final int RADIUS = 16;
	private static final int TENDRILS = 9;
	/** Arm Shaping's orb: a darker blood-red ring, and a near-black tint so the blade inside stands out. */
	public static final int BLADE_RING = 0xFF5A0E16;
	public static final int BLADE_TINT = 0xB02A0508;
	/** How far the tip of a bladed tendril hooks round, in radians, whatever its length: a sickle's curl. */
	private static final double CURL = 2.6;

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

	/**
	 * Arm Shaping, the lump that opens the Arsenal: the same orb, its nine tendrils grown into blades whose last quarter
	 * hooks round into a sickle's curl (each breathing in and out on its own, {@code 10 + 3 sin(0.05t + 1.7i)} long), and
	 * in the middle, instead of an icon, a miniature katana made of the morph's static, re-patterned every two ticks
	 * like the skin, with a red edge that breathes. The parameters are {@link #draw}'s.
	 */
	public static void drawBlade(GuiGraphicsExtractor g, float cx, float cy, float scale, float time, int ring, int tint, float glow) {
		if (scale <= 0.05F) return;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		if (glow > 0) {
			int a = Math.round(Math.min(1F, glow) * 0x60);
			disc(g, RADIUS + 7, ((a / 3) << 24) | 0xE53935);
			disc(g, RADIUS + 4, ((a * 2 / 3) << 24) | 0xE53935);
		}
		for (int i = 0; i < TENDRILS; i++) bladeTendril(g, i, time);
		disc(g, RADIUS, 0xFF000000);
		disc(g, RADIUS - 1, ring);
		staticDisc(g, RADIUS - 3, time);
		disc(g, RADIUS - 3, tint);
		katana(g, time);
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

	/**
	 * One bladed tendril: out of the ring along its own direction, swaying a little, then over its last quarter turning
	 * ever tighter until it has hooked {@link #CURL} round (all of them the same way, so the orb reads as a ring of
	 * sickles), thinning to a sharp, brighter point.
	 */
	private static void bladeTendril(GuiGraphicsExtractor g, int i, float time) {
		double angle = Math.PI * 2 * i / TENDRILS + 0.3 * Math.sin(time * 0.015 + i * 1.9);
		double length = 10 + 3 * Math.sin(time * 0.05 + 1.7 * i);
		double step = 0.7;
		// turning per unit of length over the hook, rising from nothing to twice the mean so the total is CURL
		double curlRate = CURL / (0.125 * length);
		double x = Math.cos(angle) * (RADIUS - 2);
		double y = Math.sin(angle) * (RADIUS - 2);
		double bend = 0;
		for (double s = 0; s <= length; s += step) {
			double t = s / length;
			if (t > 0.75) bend += step * curlRate * (t - 0.75) / 0.25;
			double sway = 0.25 * Math.sin(time * 0.12 + i * 1.7 + t * 2.0) * t;
			double heading = angle + sway + bend;
			x += Math.cos(heading) * step;
			y += Math.sin(heading) * step;
			float size = (float) (3.2 - 2.6 * t);
			int alpha = t < 0.9 ? 0xFF : (int) (0xFF * (1 - (t - 0.9) / 0.1 * 0.7));
			int color = t < 0.75 ? lerp(0x5A0E16, 0xC62828, t / 0.75) : lerp(0xC62828, 0xE05050, (t - 0.75) / 0.25);
			Tendrils.dot(g, (float) x, (float) y, size, (alpha << 24) | color);
		}
	}

	/**
	 * The miniature katana in Arm Shaping's orb, hilt low left and tip high right, inside the static disc: a wrapped
	 * hilt, a guard across it, and a slightly curved blade tapering to the tip, every square of it a texel of the morph
	 * skin picked anew every two ticks (so it crawls like the arm itself), its edge a red line that breathes.
	 */
	private static void katana(GuiGraphicsExtractor g, float time) {
		int frame = (int) (time / 2);
		// the blade's axis: from the hilt's end to the tip, and across it (the edge side is +across)
		float ax = 0.70710677F, ay = -0.70710677F;
		float nx = 0.70710677F, ny = 0.70710677F;
		float hiltX = -8.5F, hiltY = 8.5F;
		// the wrapped hilt: dark red and black, diamond by diamond
		for (int k = 0; k <= 7; k++) {
			float d = k * 0.75F;
			Tendrils.dot(g, hiltX + ax * d, hiltY + ay * d, 1.9F, k % 2 == 0 ? 0xFF7A1A1A : 0xFF1A0406);
		}
		// the guard: a short bar across the blade's root
		float guard = 5.9F;
		for (int k = -3; k <= 3; k++) {
			float d = k * 0.8F;
			Tendrils.dot(g, hiltX + ax * guard + nx * d, hiltY + ay * guard + ny * d, 1.5F, k == 0 ? 0xFFE05050 : 0xFF5A0E16);
		}
		// the blade: static all along, curved a little with the red edge on the outside of the curve, as a katana's is
		float edge = 0.55F + 0.45F * (float) Math.sin(time * 0.12);
		int edgeAlpha = Math.round(0x90 + 0x6F * edge);
		float bladeLength = 17.5F;
		int k = 0;
		for (float d = 0; d <= bladeLength; d += 0.7F, k++) {
			float u = d / bladeLength;
			float bow = 1.3F * (float) Math.sin(Math.PI * u) * (1F - 0.3F * u);
			float px = hiltX + ax * (guard + 0.6F + d) + nx * bow;
			float py = hiltY + ay * (guard + 0.6F + d) + ny * bow;
			float w = 2.5F - 1.8F * u;
			int seed = (frame * 37 + k * 53 + frame * k * 11) & 0xFFFF;
			skinDot(g, px, py, w, seed % 64, (seed / 64) % 64);
			Tendrils.dot(g, px + nx * (w * 0.5F), py + ny * (w * 0.5F), Math.max(0.5F, 0.9F - 0.4F * u), (edgeAlpha << 24) | 0xE05050);
		}
	}

	/** A {@code size} square of one morph skin texel, centred on ({@code x}, {@code y}). */
	private static void skinDot(GuiGraphicsExtractor g, float x, float y, float size, int u, int v) {
		g.pose().pushMatrix();
		g.pose().translate(x - size / 2, y - size / 2);
		g.pose().scale(size, size);
		g.blit(RenderPipelines.GUI_TEXTURED, MorphWorldRenderer.MORPH_SKIN, 0, 0, u, v, 1, 1, 64, 64);
		g.pose().popMatrix();
	}

	private static int lerp(int from, int to, double t) {
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int gr = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (r << 16) | (gr << 8) | b;
	}
}
