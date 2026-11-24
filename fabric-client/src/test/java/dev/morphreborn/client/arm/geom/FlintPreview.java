package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * A software picture of the flint striker, for looking at it without the game (the game's screen is shared): the quads the renderer
 * writes, drawn flat-shaded back to front, black ones dark, red ones red and the glowing ones light, with the figure's boxes round
 * them for scale. Third person: the arm (the right one) in the model's own space from the front, three-quarters and the side, resting,
 * shut and striking, and a strike frame by frame. First person: the hand pass's view space through a 70 degree lens at 16:9. Not a test
 * of anything; it writes files when asked.
 */
final class FlintPreview {
	/** One drawn quad: 4 points (x y z each) and its layer. */
	private record Quad(float[] p, ArmLayer layer) {}

	private static final long STRIKE_AT = 1000L;

	private FlintPreview() {}

	static void write(Path dir) throws IOException {
		Files.createDirectories(dir);
		for (String state : new String[] {"rest", "shut", "strike"}) {
			ImageIO.write(thirdPerson(state, false), "png", dir.resolve("flint-" + state + "-tp.png").toFile());
		}
		ImageIO.write(thirdPerson("rest", true), "png", dir.resolve("flint-rest-tp-lefty.png").toFile());
		ImageIO.write(closeUp("rest"), "png", dir.resolve("flint-rest-closeup.png").toFile());
		ImageIO.write(closeUp("strike"), "png", dir.resolve("flint-strike-closeup.png").toFile());
		ImageIO.write(firstPerson("rest"), "png", dir.resolve("flint-rest-fp.png").toFile());
		ImageIO.write(firstPerson("strike"), "png", dir.resolve("flint-strike-fp.png").toFile());
		ImageIO.write(growth(), "png", dir.resolve("flint-growth.png").toFile());
		ImageIO.write(sequence(), "png", dir.resolve("flint-strike-sequence.png").toFile());
		FlintMotion.clear();
	}

	/** The draw in a preview state: "rest" a quiet moment, "shut" the knuckles shut, "strike" an ignite's sparks 3 ticks in. */
	private static ArmDraw state(String state, boolean toolLeft, int ctx, String form) {
		FlintMotion.clear();
		float time = FlintRendererTest.quietTime();
		if (state.equals("shut")) {
			FlintMotion.struck(FlintRendererTest.SEED, STRIKE_AT, FlintMotion.POWER_SPARK, 4242);
			time = STRIKE_AT + FlintMotion.SHUT_TICKS;
		} else if (state.equals("strike")) {
			FlintMotion.struck(FlintRendererTest.SEED, STRIKE_AT, FlintMotion.POWER_IGNITE, 4242);
			time = STRIKE_AT + 3F;
		}
		return FlintRendererTest.draw(500F, 0, ctx, toolLeft, time, form);
	}

	private static List<Quad> capture(ArmDraw d, boolean firstPerson) {
		List<Quad> out = new ArrayList<>();
		FlintRenderer r = new FlintRenderer();
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
			ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
			rec.keep = true;
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			if (firstPerson) r.emitFirstPerson(d, layer, m);
			else r.emitThirdPerson(d, layer, m);
			for (int i = 0; i + 3 < rec.kept.size(); i += 4) {
				float[] p = new float[12];
				for (int k = 0; k < 4; k++) {
					float[] v = rec.kept.get(i + k);
					p[3 * k] = v[0];
					p[3 * k + 1] = v[1];
					p[3 * k + 2] = v[2];
				}
				out.add(new Quad(p, layer));
			}
		}
		return out;
	}

	/** Moves quads from arm space A to the model's (the shoulder's pivot), and flips y: the preview draws with y down, as the model does. */
	private static List<Quad> moved(List<Quad> quads, boolean toolLeft) {
		float px = toolLeft ? 5F : -5F;
		for (Quad q : quads) {
			for (int k = 0; k < 4; k++) {
				q.p[3 * k] += px;
				q.p[3 * k + 1] += 2F;
			}
		}
		return quads;
	}

	private static BufferedImage canvas(int w, int h, int n) {
		BufferedImage img = new BufferedImage(n * w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(205, 210, 220));
		g.fillRect(0, 0, n * w, h);
		g.dispose();
		return img;
	}

	private static BufferedImage thirdPerson(String state, boolean toolLeft) {
		List<Quad> quads = moved(capture(state(state, toolLeft, ArmDraw.CTX_TP, ArmProtocol.KATANA), false), toolLeft);
		return views(quads, 520, 560, 22F, 0.42F, toolLeft ? 5F : -5F);
	}

	/** Big, on the hand only. */
	private static BufferedImage closeUp(String state) {
		return views(moved(capture(state(state, false, ArmDraw.CTX_TP, ArmProtocol.KATANA), false), false), 640, 640, 60F, -0.72F, -5F);
	}

	/** Front, three-quarters and side by side. */
	private static BufferedImage views(List<Quad> quads, int w, int h, float scale, float centreY, float armX) {
		BufferedImage img = canvas(w, h, 3);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		float[][] views = {{0F, 0F}, {-40F, 12F}, {90F, 0F}};
		for (int i = 0; i < 3; i++) {
			g.setClip(i * w, 0, w, h);
			g.translate(i * w, 0);
			scene(g, quads, views[i][0], views[i][1], w, h, scale, h * centreY, armX);
			g.translate(-i * w, 0);
		}
		g.dispose();
		return img;
	}

	private static BufferedImage growth() {
		float[] ages = {2F, 4F, 6F, 8F, 10F, 12F, 14F};
		int w = 300, h = 360;
		BufferedImage img = canvas(w, h, ages.length);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (int i = 0; i < ages.length; i++) {
			FlintMotion.clear();
			List<Quad> quads = moved(capture(FlintRendererTest.draw(ages[i], 0, ArmDraw.CTX_TP, false, FlintRendererTest.quietTime(), ArmProtocol.KATANA), false), false);
			g.setClip(i * w, 0, w, h);
			g.translate(i * w + 90, 0);
			scene(g, quads, -40F, 12F, w, h, 30F, h * 0.4F, -5F);
			g.translate(-i * w - 90, 0);
		}
		g.dispose();
		return img;
	}

	/** An ignite's strike at 0, 1, 2, 3, 5, 7 and 9 ticks, close on the hand, from three-quarters. */
	private static BufferedImage sequence() {
		float[] ages = {0F, 1F, 2F, 3F, 5F, 7F, 9F};
		int w = 300, h = 360;
		BufferedImage img = canvas(w, h, ages.length);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (int i = 0; i < ages.length; i++) {
			FlintMotion.clear();
			FlintMotion.struck(FlintRendererTest.SEED, STRIKE_AT, FlintMotion.POWER_IGNITE, 4242);
			List<Quad> quads = moved(capture(FlintRendererTest.draw(500F, 0, ArmDraw.CTX_TP, false, STRIKE_AT + ages[i], ArmProtocol.KATANA), false), false);
			g.setClip(i * w, 0, w, h);
			g.translate(i * w, 0);
			scene(g, quads, -35F, 8F, w, h, 36F, h * -0.8F, -5F);
			g.translate(-i * w, 0);
		}
		g.dispose();
		return img;
	}

	/** Body, head and the arm's box as faint outlines, then the quads far to near. */
	private static void scene(Graphics2D g, List<Quad> quads, float yawDeg, float pitchDeg, int w, int h, float scale, float cy, float armX) {
		float cx = w / 2F;
		float yaw = (float) Math.toRadians(yawDeg), pitch = (float) Math.toRadians(pitchDeg);
		g.setColor(new Color(90, 100, 120));
		g.setStroke(new BasicStroke(1F));
		box(g, -4, 0, -2, 4, 12, 2, yaw, pitch, scale, cx, cy);
		box(g, -4, -8, -4, 4, 0, 4, yaw, pitch, scale, cx, cy);
		box(g, -4, 12, -2, 0, 24, 2, yaw, pitch, scale, cx, cy);
		box(g, 0, 12, -2, 4, 24, 2, yaw, pitch, scale, cx, cy);
		g.setColor(new Color(60, 120, 190));
		box(g, armX < 0 ? -8 : 4, 0, -2, armX < 0 ? -4 : 8, 12, 2, yaw, pitch, scale, cx, cy);
		g.setColor(new Color(90, 100, 120));
		box(g, armX < 0 ? 4 : -8, 0, -2, armX < 0 ? 8 : -4, 12, 2, yaw, pitch, scale, cx, cy);
		List<float[]> projected = new ArrayList<>();
		for (Quad quad : quads) {
			float[] s = new float[14];
			float depth = 0F;
			for (int k = 0; k < 4; k++) {
				float[] p = project(quad.p[3 * k], quad.p[3 * k + 1], quad.p[3 * k + 2], yaw, pitch);
				s[2 * k] = cx + p[0] * scale;
				s[2 * k + 1] = cy + p[1] * scale;
				depth += p[2] / 4F;
			}
			s[8] = depth;
			s[9] = quad.layer == ArmLayer.BLACK ? 0F : quad.layer == ArmLayer.RED ? 1F : 2F;
			float[] n = normal(quad, yaw, pitch);
			s[10] = n[0];
			s[11] = n[1];
			s[12] = n[2];
			projected.add(s);
		}
		projected.sort((a, b) -> Float.compare(b[8], a[8])); // far first: the figure faces -z, the viewer at -z
		for (float[] s : projected) fill(g, s, (int) s[9]);
	}

	private static float[] project(float x, float y, float z, float yaw, float pitch) {
		float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
		float x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
		float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
		float y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
		return new float[] {x1, y2, z2};
	}

	private static float[] normal(Quad q, float yaw, float pitch) {
		float[] a = project(q.p[0], q.p[1], q.p[2], yaw, pitch), b = project(q.p[3], q.p[4], q.p[5], yaw, pitch), c = project(q.p[6], q.p[7], q.p[8], yaw, pitch),
				d = project(q.p[9], q.p[10], q.p[11], yaw, pitch);
		float ux = c[0] - a[0], uy = c[1] - a[1], uz = c[2] - a[2], vx = d[0] - b[0], vy = d[1] - b[1], vz = d[2] - b[2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return l < 1e-9F ? new float[] {0F, 0F, 1F} : new float[] {nx / l, ny / l, nz / l};
	}

	private static void fill(Graphics2D g, float[] s, int layer) {
		// a light from the upper left front, both sides lit
		float lambert = Math.abs(s[10] * -0.35F + s[11] * -0.55F + s[12] * -0.75F);
		float shade = 0.45F + 0.55F * Math.min(1F, lambert);
		Color c = layer == 1 ? new Color(Math.min(255, (int) (215 * shade)), (int) (25 * shade), (int) (25 * shade))
				: layer == 2 ? new Color(Math.min(255, (int) (255 * shade)), Math.min(255, (int) (225 * shade)), Math.min(255, (int) (190 * shade)))
				: new Color((int) (52 * shade), (int) (52 * shade), (int) (60 * shade));
		int[] xs = new int[4], ys = new int[4];
		for (int k = 0; k < 4; k++) {
			xs[k] = Math.round(s[2 * k]);
			ys[k] = Math.round(s[2 * k + 1]);
		}
		g.setColor(c);
		g.fillPolygon(xs, ys, 4);
		g.setColor(layer == 1 ? new Color(120, 10, 10) : layer == 2 ? new Color(150, 120, 60) : new Color(18, 18, 22));
		g.setStroke(new BasicStroke(0.5F));
		g.drawPolygon(xs, ys, 4);
	}

	private static void box(Graphics2D g, float x0, float y0, float z0, float x1, float y1, float z1, float yaw, float pitch, float scale, float cx, float cy) {
		float[][] corners = new float[8][];
		for (int i = 0; i < 8; i++) {
			corners[i] = project((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1, yaw, pitch);
		}
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) {
			g.drawLine(Math.round(cx + corners[e[0]][0] * scale), Math.round(cy + corners[e[0]][1] * scale), Math.round(cx + corners[e[1]][0] * scale),
					Math.round(cy + corners[e[1]][1] * scale));
		}
	}

	// ------------------------------------------------------------------ first person

	private static BufferedImage firstPerson(String state) {
		List<Quad> quads = capture(state(state, false, ArmDraw.CTX_FP, ""), true);
		int w = 1280, h = 720;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(120, 160, 120));
		g.fillRect(0, 0, w, h);
		float f = (h / 2F) / (float) Math.tan(Math.toRadians(35));
		List<float[]> projected = new ArrayList<>();
		for (Quad quad : quads) {
			float[] s = new float[14];
			float depth = 0F;
			boolean behind = false;
			for (int k = 0; k < 4; k++) {
				float z = quad.p[3 * k + 2];
				if (z > -0.5F) behind = true;
				s[2 * k] = w / 2F + quad.p[3 * k] / -z * f;
				s[2 * k + 1] = h / 2F - quad.p[3 * k + 1] / -z * f;
				depth += z / 4F;
			}
			if (behind) continue;
			s[8] = depth;
			s[9] = quad.layer == ArmLayer.BLACK ? 0F : quad.layer == ArmLayer.RED ? 1F : 2F;
			float[] n = normal(quad, 0F, 0F);
			s[10] = n[0];
			s[11] = n[1];
			s[12] = n[2];
			projected.add(s);
		}
		projected.sort((a, b) -> Float.compare(a[8], b[8])); // most negative z (farthest) first
		for (float[] s : projected) fill(g, s, (int) s[9]);
		g.dispose();
		return img;
	}
}
