package dev.morphreborn.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * Tendrils of goo drawn flat on the screen, shared by the wheels and the HUD so they all move alike.
 * <ul>
 *   <li>{@link #frame}: black and red tendrils reaching out of a tile's four edges, swaying and breathing in and out,
 *       like the biomass bar's and the Biomass button's. The selector uses it to mark a multimorph's chosen main body.</li>
 *   <li>{@link #reach}: one tendril from a point to another, the frenzy's tendril maths (RageTethers) laid flat: a
 *       sideways wave that is still at both ends, thinning along its length, a swelling running out along it, and
 *       growth by cutting it off at a share of its length, so it grows out of its root and draws back into it rather
 *       than fading. The Arsenal wheel reaches from its middle to the form under the mouse with it.</li>
 * </ul>
 * Everything is drawn as small squares of colour (a fill under a scaled pose), the same way BiomassOrb draws its
 * tendrils, so they stay soft at any GUI scale.
 */
public final class Tendrils {
	private Tendrils() {}

	/**
	 * Tendrils out of the four edges of the {@code size} square at ({@code x}, {@code y}): four a side, every other one
	 * red, each swaying on its own and stretching and shrinking over a few seconds.
	 */
	public static void frame(GuiGraphicsExtractor g, double x, double y, double size, float time) {
		int perSide = 4;
		for (int side = 0; side < 4; side++) {
			for (int k = 0; k < perSide; k++) {
				int i = side * perSide + k;
				double along = (k + 0.5 + 0.3 * Math.sin(time * 0.02 + i * 1.3)) / perSide;
				// where it grows from, and outwards
				double bx, by, nx, ny;
				switch (side) {
					case 0 -> { bx = x + along * size; by = y; nx = 0; ny = -1; }
					case 1 -> { bx = x + size; by = y + along * size; nx = 1; ny = 0; }
					case 2 -> { bx = x + (1 - along) * size; by = y + size; nx = 0; ny = 1; }
					default -> { bx = x; by = y + (1 - along) * size; nx = -1; ny = 0; }
				}
				double length = size * (0.11 + 0.07 * Math.sin(time * 0.055 + i * 2.3));
				boolean red = i % 2 == 0;
				for (double s = 0; s <= length; s += 0.8) {
					double t = s / length;
					double wobble = Math.sin(time * 0.16 + s * 0.5 + i * 1.7) * 2.2 * t;
					float px = (float) (bx + nx * s - ny * wobble);
					float py = (float) (by + ny * s + nx * wobble);
					float dot = (float) (2.6 - 1.8 * t);
					int alpha = t < 0.7 ? 0xFF : (int) (0xFF * (1 - (t - 0.7) / 0.3));
					int color = (alpha << 24) | (red ? lerpColor(0x7A1018, 0xE53935, t) : lerpColor(0x050505, 0x2A1214, t));
					dot(g, px, py, dot, color);
				}
			}
		}
	}

	/**
	 * One tendril from ({@code x0}, {@code y0}) towards ({@code x1}, {@code y1}), grown out to {@code reach} (0-1) of the
	 * way. As RageTethers': the sideways wave is {@code sin(pi s)} times an amplitude that grows with the length (so both
	 * ends stay put), moving with time and {@code phase}; a swelling runs out from the root once every few seconds; the
	 * width goes from {@code width0} at the root to {@code width1} at the far end (GUI pixels), and a growing front tapers
	 * to a point. Red tendrils go from dark to biomass red, black ones from black to a dark red-brown, as the frame's.
	 *
	 * @param alpha 0-255, for wheels that fade
	 */
	public static void reach(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float reach, float phase, float time, float width0,
			float width1, boolean red, int alpha) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = Mth.sqrt(dx * dx + dy * dy);
		if (length < 2F || reach <= 0.01F || alpha <= 8) return;
		float ux = dx / length;
		float uy = dy / length;
		float amp = Math.min(0.18F * length, 3F + 0.06F * length);
		// where the swelling is along the tendril: 0 at the root, running out to 1
		float pulse = Mth.frac(time * 0.07F + phase);
		float end = length * Math.min(1F, reach);
		for (float d = 0; d <= end; d += 0.8F) {
			float s = d / length;
			float wave = Mth.sin(s * Mth.PI) * amp;
			// two waves across, as the 3D tendril's sideways and up-and-down ones seen flat
			float off = wave * (Mth.sin(phase + s * 7F + time * 0.35F) + 0.35F * Mth.cos(phase * 0.8F + s * 5F + time * 0.28F)) / 1.35F;
			float px = x0 + ux * d - uy * off;
			float py = y0 + uy * d + ux * off;
			float swell = 1F + 0.8F * (float) Math.exp(-(s - pulse) * (s - pulse) / 0.006F);
			float w = Mth.lerp(s, width0, width1) * swell;
			float front = end - d;
			if (reach < 1F && front < 3F) w *= Math.max(0.3F, front / 3F);
			int a = s < 0.85F ? alpha : Math.round(alpha * (1F - (s - 0.85F) / 0.15F * 0.6F));
			int color = red ? lerpColor(0x7A1018, 0xE53935, s) : lerpColor(0x050505, 0x2A1214, s);
			dot(g, px, py, w, (Math.clamp(a, 0, 255) << 24) | color);
		}
	}

	/** {@code from} to {@code to} (RGB, no alpha) by {@code t} from 0 to 1. */
	public static int lerpColor(int from, int to, double t) {
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int gr = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (r << 16) | (gr << 8) | b;
	}

	/** A {@code size} square of {@code color} (ARGB) centred on ({@code x}, {@code y}). */
	public static void dot(GuiGraphicsExtractor g, float x, float y, float size, int color) {
		if (size <= 0.05F) return;
		g.pose().pushMatrix();
		g.pose().translate(x - size / 2, y - size / 2);
		g.pose().scale(size, size);
		g.fill(0, 0, 1, 1, color);
		g.pose().popMatrix();
	}
}
