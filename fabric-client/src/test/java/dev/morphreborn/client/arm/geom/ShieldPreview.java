package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A software picture of the shield, for looking at it without the game (the game's screen is shared; it was how the owner
 * compared the two looks before picking one, 2026-11-16): the quads the renderer writes, drawn flat-shaded back to front, black ones dark, red ones red, with
 * the figure's boxes round them for scale. Third person: the off arm (the left one) in the model's own space, raised the way the
 * pose mixin raises it, from the front, three-quarters, the side and the back. First person: the hand pass's view space through
 * a 70 degree lens at 16:9, with the screen's edge drawn. Not a test of anything; it writes files when asked.
 */
final class ShieldPreview {
	private static final float PI = (float) Math.PI;

	/** One drawn quad: 4 points (x y z each), its layer. */
	private record Quad(float[] p, ArmLayer layer) {}

	private ShieldPreview() {}

	static void write(Path dir) throws IOException {
		Files.createDirectories(dir);
		// the files keep the morph look's name in them (my pick, 2026-11-16: it is the only look)
		String name = "morph";
		for (boolean raised : new boolean[] {true, false}) {
			ImageIO.write(thirdPerson(raised, false), "png", dir.resolve("shield-" + name + "-" + (raised ? "raised" : "lowered") + "-tp.png").toFile());
			ImageIO.write(thirdPerson(raised, true), "png", dir.resolve("shield-" + name + "-" + (raised ? "raised" : "lowered") + "-tp-lefty.png").toFile());
			ImageIO.write(firstPerson(raised), "png", dir.resolve("shield-" + name + "-" + (raised ? "raised" : "lowered") + "-fp.png").toFile());
		}
		// the build, in four stages, from the front
		ImageIO.write(growth(), "png", dir.resolve("shield-" + name + "-growth.png").toFile());
	}

	// ------------------------------------------------------------------ capturing

	private static List<Quad> capture(ArmDraw d, boolean firstPerson) {
		List<Quad> out = new ArrayList<>();
		ShieldRenderer r = new ShieldRenderer();
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED}) {
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

	// ------------------------------------------------------------------ third person

	/** The off arm (left) raised across the body as the pose mixin does it, or hanging; the model's own space (px, y down, the figure faces -z). */
	private static BufferedImage thirdPerson(boolean raised, boolean toolLeft) {
		ShieldMotion.clear();
		// the off arm is the right one for a left-handed player: pivot on the other side, the block pose turning the other way
		float px = toolLeft ? -5F : 5F;
		float xRot = raised ? -0.9424779F : 0F, yRot = raised ? (toolLeft ? -0.5236F : 0.5236F) : 0F;
		ArmDraw d = ShieldRendererTest.draw(500F, raised, 0, ArmDraw.CTX_TP, toolLeft, 100F);
		List<Quad> quads = capture(d, false);
		// arm space A -> model space
		Quaternionf q = new Quaternionf().rotationZYX(0F, yRot, xRot);
		Vector3f v = new Vector3f();
		for (Quad quad : quads) {
			for (int k = 0; k < 4; k++) {
				v.set(quad.p[3 * k], quad.p[3 * k + 1], quad.p[3 * k + 2]);
				q.transform(v);
				v.add(px, 2F, 0F);
				quad.p[3 * k] = v.x;
				quad.p[3 * k + 1] = v.y;
				quad.p[3 * k + 2] = v.z;
			}
		}
		int w = 600, h = 760;
		BufferedImage img = new BufferedImage(3 * w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(205, 210, 220));
		g.fillRect(0, 0, 3 * w, h);
		float[][] views = {{0F, 0F}, {-35F, 15F}, {90F, 0F}};
		for (int i = 0; i < 3; i++) {
			g.setClip(i * w, 0, w, h);
			g.translate(i * w, 0);
			scene(g, quads, views[i][0], views[i][1], w, h, q, px);
			g.translate(-i * w, 0);
		}
		g.dispose();
		return img;
	}

	/** Body, head and the arm's box as faint outlines, then the quads far to near. */
	private static void scene(Graphics2D g, List<Quad> quads, float yawDeg, float pitchDeg, int w, int h, Quaternionf armRot, float armX) {
		float scale = w >= 600 ? 26F : 14F, cx = w / 2F, cy = h * 0.40F;
		float yaw = (float) Math.toRadians(yawDeg), pitch = (float) Math.toRadians(pitchDeg);
		g.setColor(new Color(90, 100, 120));
		g.setStroke(new BasicStroke(1F));
		box(g, -4, 0, -2, 4, 12, 2, null, 0F, yaw, pitch, scale, cx, cy);
		box(g, -4, -8, -4, 4, 0, 4, null, 0F, yaw, pitch, scale, cx, cy);
		box(g, -4, 12, -2, 0, 24, 2, null, 0F, yaw, pitch, scale, cx, cy);
		box(g, 0, 12, -2, 4, 24, 2, null, 0F, yaw, pitch, scale, cx, cy);
		g.setColor(new Color(60, 120, 190));
		box(g, armX > 0 ? -1 : -3, -2, -2, armX > 0 ? 3 : 1, 10, 2, armRot, armX, yaw, pitch, scale, cx, cy);
		g.setColor(new Color(90, 100, 120));
		box(g, armX > 0 ? -8 : 4, -2, -2, armX > 0 ? -4 : 8, 10, 2, null, 0F, yaw, pitch, scale, cx, cy);
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
			s[9] = quad.layer == ArmLayer.RED ? 1F : 0F;
			float[] n = normal(quad, yaw, pitch);
			s[10] = n[0];
			s[11] = n[1];
			s[12] = n[2];
			projected.add(s);
		}
		projected.sort((a, b) -> Float.compare(b[8], a[8])); // far first: the figure faces -z, the viewer at -z
		for (float[] s : projected) fill(g, s, s[9] > 0.5F);
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

	private static void fill(Graphics2D g, float[] s, boolean red) {
		// a light from the upper left front, both sides lit
		float lambert = Math.abs(s[10] * -0.35F + s[11] * -0.55F + s[12] * -0.75F);
		float shade = 0.45F + 0.55F * Math.min(1F, lambert);
		Color c = red ? new Color(Math.min(255, (int) (215 * shade)), (int) (25 * shade), (int) (25 * shade)) : new Color((int) (52 * shade), (int) (52 * shade), (int) (60 * shade));
		int[] xs = new int[4], ys = new int[4];
		for (int k = 0; k < 4; k++) {
			xs[k] = Math.round(s[2 * k]);
			ys[k] = Math.round(s[2 * k + 1]);
		}
		g.setColor(c);
		g.fillPolygon(xs, ys, 4);
		g.setColor(red ? new Color(120, 10, 10) : new Color(18, 18, 22));
		g.setStroke(new BasicStroke(0.5F));
		g.drawPolygon(xs, ys, 4);
	}

	private static void box(Graphics2D g, float x0, float y0, float z0, float x1, float y1, float z1, Quaternionf rot, float pivotX, float yaw, float pitch, float scale, float cx,
			float cy) {
		float[][] corners = new float[8][];
		Vector3f v = new Vector3f();
		for (int i = 0; i < 8; i++) {
			v.set((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
			if (rot != null) {
				rot.transform(v);
				v.add(pivotX, 2F, 0F);
			}
			corners[i] = project(v.x, v.y, v.z, yaw, pitch);
		}
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) {
			g.drawLine(Math.round(cx + corners[e[0]][0] * scale), Math.round(cy + corners[e[0]][1] * scale), Math.round(cx + corners[e[1]][0] * scale),
					Math.round(cy + corners[e[1]][1] * scale));
		}
	}

	// ------------------------------------------------------------------ first person

	private static BufferedImage firstPerson(boolean raised) {
		ShieldMotion.clear();
		ArmDraw d = ShieldRendererTest.draw(500F, raised, 0, ArmDraw.CTX_FP, false, 100F);
		List<Quad> quads = capture(d, true);
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
			s[9] = quad.layer == ArmLayer.RED ? 1F : 0F;
			float[] n = normal(quad, 0F, 0F);
			s[10] = n[0];
			s[11] = n[1];
			s[12] = n[2];
			projected.add(s);
		}
		projected.sort((a, b) -> Float.compare(a[8], b[8])); // most negative z (farthest) first
		for (float[] s : projected) fill(g, s, s[9] > 0.5F);
		g.dispose();
		return img;
	}

	// ------------------------------------------------------------------ growth

	/** The shield growing at ages 3, 6, 9, 12, 15 and built, raised, from the front. */
	private static BufferedImage growth() {
		int w = 300, h = 400;
		float[] ages = {3F, 6F, 9F, 12F, 15F, 18F};
		BufferedImage img = new BufferedImage(ages.length * w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(205, 210, 220));
		g.fillRect(0, 0, ages.length * w, h);
		for (int i = 0; i < ages.length; i++) {
			ShieldMotion.clear();
			ArmDraw d = ShieldRendererTest.draw(ages[i], true, 0, ArmDraw.CTX_TP, false, 100F);
			List<Quad> quads = capture(d, false);
			Quaternionf q = new Quaternionf().rotationZYX(0F, 0.5236F, -0.9424779F);
			Vector3f v = new Vector3f();
			for (Quad quad : quads) {
				for (int k = 0; k < 4; k++) {
					v.set(quad.p[3 * k], quad.p[3 * k + 1], quad.p[3 * k + 2]);
					q.transform(v);
					v.add(5F, 2F, 0F);
					quad.p[3 * k] = v.x;
					quad.p[3 * k + 1] = v.y;
					quad.p[3 * k + 2] = v.z;
				}
			}
			g.setClip(i * w, 0, w, h);
			g.translate(i * w, 0);
			scene(g, quads, -35F, 10F, w, h, q, 5F);
			g.translate(-i * w, 0);
		}
		g.dispose();
		return img;
	}
}
