package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
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
 * Software pictures of each accessory on each arm the game can show under it (2026-11-24), for looking at the tendril holders without the
 * game: the quads the real geometry writes (the accessory, the tendril arm / plain hand / armour sheath it sits on, the lantern's own
 * stalk when asked), flat shaded back to front over the skin arm's box (a chestplate's is a pixel bigger), from the front, three quarters,
 * the side and the back. Writes files only when asked ({@code ACCESSORY_LOOKS_DIR}); not a test of anything.
 */
final class AccessoryLooksPreview {
	record Quad(float[] p, ArmLayer layer) {}

	/** What the arm under the accessory shows. */
	enum Arm {
		PLAIN("plain", "", -1, false), CHEST("chest", "", -1, true), TENDRIL("tendril", ArmProtocol.KATANA, ArmProtocol.LOOK_ARM, false),
		HAND("hand", ArmProtocol.KATANA, ArmProtocol.LOOK_HAND, false), ARMOUR("armour", ArmProtocol.KATANA, ArmProtocol.LOOK_ARMOUR, false),
		ARMOUR_CHEST("armour-chest", ArmProtocol.KATANA, ArmProtocol.LOOK_ARMOUR, true);

		final String label, form;
		final int look;
		final boolean chest;

		Arm(String label, String form, int look, boolean chest) {
			this.label = label;
			this.form = form;
			this.look = look;
			this.chest = chest;
		}
	}

	private AccessoryLooksPreview() {}

	/** What a picture is of: the lantern's near-lens scale and the arm sheath's (1 = none) and the hand (set by {@link #writeLanternOnBody}). */
	private static float previewNear = 1F, previewSheath = 1F;
	private static boolean previewLeft, previewLantern;

	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	private static final float[][] VIEWS = {{0F, 0F}, {-40F, 12F}, {90F, 0F}, {180F, 0F}};
	private static final String[] VIEW_NAMES = {"front", "three-quarter", "side", "back"};

	static void write(Path dir) throws IOException {
		Files.createDirectories(dir);
		for (Arm arm : Arm.values()) {
			for (String id : new String[] {ArmProtocol.SPYGLASS, ArmProtocol.FLINT}) {
				ImageIO.write(sheet(arm, 1 << ArmProtocol.accessoryIndex(id), false, id + " on " + arm.label), "png", dir.resolve(id + "-" + arm.label + ".png").toFile());
			}
			int both = (1 << ArmProtocol.accessoryIndex(ArmProtocol.SPYGLASS)) | (1 << ArmProtocol.accessoryIndex(ArmProtocol.FLINT));
			ImageIO.write(sheet(arm, both, true, "spyglass + flint + lantern on " + arm.label), "png", dir.resolve("all-" + arm.label + ".png").toFile());
		}
		for (boolean chest : new boolean[] {false, true}) {
			for (boolean classic : new boolean[] {false, true}) {
				for (boolean raised : new boolean[] {false, true}) {
					String name = "shield-" + (classic ? "classic" : "tendril") + (chest ? "-chest" : "") + (raised ? "-raised" : "-lowered");
					ImageIO.write(shieldSheet(chest, classic, raised, name), "png", dir.resolve(name + ".png").toFile());
				}
			}
		}
		ShieldRenderer.classicBands(false);
	}

	/**
	 * The lantern drawn on the body under First-person Model (2026-11-24): per arm look, the bare arm, the tendril arm, the plain hand and the armour, each
	 * with and without a chestplate, both hands, at the near-lens scales the game would use (1 = far, then the scale at the distances given).
	 */
	static void writeLanternOnBody(Path dir) throws IOException {
		Files.createDirectories(dir);
		double[] dists = {10.0, 0.62, 0.45};
		previewLantern = true;
		try {
			for (Arm arm : Arm.values()) {
				for (boolean left : new boolean[] {false, true}) {
					for (double dist : dists) {
						float near = nearAt(dist);
						previewNear = near;
						// the arm form's sheath goes smaller about the hand by its own rule, and the lantern's stalk root with it (second pass)
						previewSheath = dev.morphreborn.client.arm.model.NearBodyScale.of(dist, dev.morphreborn.client.arm.model.NearBodyScale.SHEATH_REACH_PX / 16.0 * 0.9375);
						previewLeft = left;
						String name = "lantern-" + arm.label + (left ? "-left" : "-right") + "-near" + Math.round(near * 100);
						ImageIO.write(sheet(arm, 0, true, name), "png", dir.resolve(name + ".png").toFile());
					}
				}
			}
		} finally {
			previewNear = 1F;
			previewSheath = 1F;
			previewLantern = false;
			previewLeft = false;
		}
	}

	/** The scale {@code NearBodyScale} gives the lantern with its hand {@code dist} blocks from the lens (reach in blocks as the layer takes it). */
	private static float nearAt(double dist) {
		return dev.morphreborn.client.arm.model.NearBodyScale.of(dist, dev.morphreborn.client.arm.model.NearBodyScale.LANTERN_REACH_PX / 16.0 * 0.9375,
				dev.morphreborn.client.arm.model.NearBodyScale.LANTERN_CAP);
	}

	// ------------------------------------------------------------------ capture

	private static ArmDraw draw(Arm arm, int mask, boolean lantern, float time, float age) {
		String form = arm.form;
		return AccessoryGolden.build(form, form.isEmpty() ? ArmProtocol.STATE_NONE : ArmProtocol.STATE_FORMED, ArmDraw.CTX_TP, 0, previewLeft, false, arm.chest, time, mask, true,
				false, age, lantern);
	}

	static List<Quad> capture(AccessoryRenderer r, LureRenderer lure, ArmDraw d) {
		List<Quad> out = new ArrayList<>();
		for (ArmLayer layer : LAYERS) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
			rec.keep = true;
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			AccessoryView.set(previewNear, previewNear < 1F, previewSheath);
			try {
				if (r != null) r.emitThirdPerson(d, layer, m);
				else if (lure != null) lure.emitThirdPerson(d, layer, m);
				else SheathGeometry.emit(d, layer, m, d.slim(), d.leftHand());
			} finally {
				AccessoryView.set(1F, false);
			}
			int from = out.size();
			add(out, rec, layer);
			// the sheath on the body is drawn about its hand end by ArmSheathLayer (x and z about the arm's pivot line, y about NearBodyScale.SHEATH_HAND_Y)
			if (r == null && lure == null && previewSheath < 1F) {
				float s = previewSheath, hy = dev.morphreborn.client.arm.model.NearBodyScale.SHEATH_HAND_Y;
				for (int i = from; i < out.size(); i++) {
					float[] p = out.get(i).p();
					for (int k = 0; k < 4; k++) {
						p[3 * k] *= s;
						p[3 * k + 1] = hy + s * (p[3 * k + 1] - hy);
						p[3 * k + 2] *= s;
					}
				}
			}
		}
		return out;
	}

	private static void add(List<Quad> out, ArmBudgetTest.Recorder rec, ArmLayer layer) {
		for (int i = 0; i + 3 < rec.kept.size(); i += 4) {
			float[] p = new float[12];
			for (int k = 0; k < 4; k++) System.arraycopy(rec.kept.get(i + k), 0, p, 3 * k, 3);
			out.add(new Quad(p, layer));
		}
	}

	/** The arm's whole drawing (its sheath and the accessories on it) in arm space A. */
	static List<Quad> armQuads(Arm arm, int mask, boolean lantern, float time) {
		SheathGeometry.lookOverride = arm.look;
		List<Quad> quads = new ArrayList<>();
		try {
			ArmDraw d = draw(arm, mask, lantern, time, 500F);
			if (!arm.form.isEmpty()) quads.addAll(capture(null, null, d));
			for (String id : new String[] {ArmProtocol.SHIELD, ArmProtocol.SPYGLASS, ArmProtocol.FLINT}) {
				if ((mask & (1 << ArmProtocol.accessoryIndex(id))) != 0) quads.addAll(capture(ArmGeometry.accessory(id), null, d));
			}
			if (lantern) quads.addAll(capture(null, ArmGeometry.lure(), d));
		} finally {
			SheathGeometry.lookOverride = -1;
		}
		return quads;
	}

	// ------------------------------------------------------------------ pictures

	private static BufferedImage sheet(Arm arm, int mask, boolean lantern, String title) {
		SpyglassMotion.clear();
		FlintMotion.clear();
		List<Quad> quads = armQuads(arm, mask, lantern, 100F);
		int w = 460, h = 560;
		BufferedImage img = new BufferedImage(VIEWS.length * w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(205, 210, 220));
		g.fillRect(0, 0, img.getWidth(), h);
		for (int i = 0; i < VIEWS.length; i++) {
			g.setClip(i * w, 0, w, h);
			g.translate(i * w, 0);
			scene(g, quads, arm, VIEWS[i][0], VIEWS[i][1], w, h, previewLantern ? 34F : 50F, previewLantern ? 8F : 2.5F, previewLeft ? 5F : -5F, 0F);
			g.setColor(Color.BLACK);
			g.setFont(new Font("SansSerif", Font.PLAIN, 14));
			g.drawString(title + " : " + VIEW_NAMES[i], 8, 18);
			g.translate(-i * w, 0);
		}
		g.dispose();
		return img;
	}

	private static BufferedImage shieldSheet(boolean chest, boolean classic, boolean raised, String title) {
		ShieldMotion.clear();
		ShieldRenderer.classicBands(classic);
		float[] ages = {500F};
		ArmDraw d = AccessoryGolden.build("", ArmProtocol.STATE_NONE, ArmDraw.CTX_TP, 0, false, false, chest, 100F, 1 << ArmProtocol.accessoryIndex(ArmProtocol.SHIELD), raised,
				false, ages[0], false);
		List<Quad> quads = capture(ArmGeometry.accessory(ArmProtocol.SHIELD), null, d);
		int w = 460, h = 600;
		BufferedImage img = new BufferedImage(VIEWS.length * w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(205, 210, 220));
		g.fillRect(0, 0, img.getWidth(), h);
		for (int i = 0; i < VIEWS.length; i++) {
			g.setClip(i * w, 0, w, h);
			g.translate(i * w, 0);
			scene(g, quads, chest ? Arm.CHEST : Arm.PLAIN, VIEWS[i][0], VIEWS[i][1], w, h, raised ? 24F : 40F, raised ? 8F : 7F, 5F, raised ? 1F : 0F);
			g.setColor(Color.BLACK);
			g.setFont(new Font("SansSerif", Font.PLAIN, 14));
			g.drawString(title + " : " + VIEW_NAMES[i], 8, 18);
			g.translate(-i * w, 0);
		}
		g.dispose();
		ShieldRenderer.classicBands(false);
		return img;
	}

	/**
	 * The arm's skin box (left out where a tendril arm or the armour replaces it) and the body round it faintly, then the quads far to near.
	 * {@code raise} turns the whole arm into the shield's blocking pose about the shoulder.
	 */
	static void scene(Graphics2D g, List<Quad> quads, Arm arm, float yawDeg, float pitchDeg, int w, int h, float scale, float cy, float armX, float raise) {
		float yaw = (float) Math.toRadians(yawDeg), pitch = (float) Math.toRadians(pitchDeg);
		float[] pose = {-0.94F * raise, (armX < 0 ? 0.5F : -0.5F) * raise};
		// centred on the point of the arm in focus (cy is its arm-space y, in px), whatever the view
		float[] focus = place(armX < 0 ? -1F : 1F, cy, 0F, armX, pose);
		float[] fp = project(focus[0], focus[1], focus[2], yaw, pitch);
		float cx = w / 2F - fp[0] * scale;
		cy = h / 2F - fp[1] * scale;
		g.setStroke(new BasicStroke(1F));
		g.setColor(new Color(90, 100, 120));
		wire(g, -4, 0, -2, 4, 12, 2, yaw, pitch, scale, cx, cy, armX * 0F, 0F, null);
		wire(g, -4, -8, -4, 4, 0, 4, yaw, pitch, scale, cx, cy, 0F, 0F, null);
		float other = -armX;
		wire(g, other - 2 - (other > 0 ? 0F : 2F), 0, -2, other + 2 + (other > 0 ? 2F : 0F) - 0F, 12, 2, yaw, pitch, scale, cx, cy, 0F, 0F, null);
		float grow = arm.chest ? 1F : 0F;
		boolean skin = arm.form.isEmpty() || arm.look == ArmProtocol.LOOK_HAND;
		List<float[]> projected = new ArrayList<>();
		if (skin) {
			g.setColor(new Color(215, 170, 130, 150));
			float xcA = armX < 0 ? -1F : 1F;
			solidBox(g, xcA - 2 - grow, -grow, -2 - grow, xcA + 2 + grow, 12 + grow, 2 + grow, yaw, pitch, scale, cx, cy, armX, pose);
		}
		for (Quad quad : quads) {
			float[] s = new float[14];
			float depth = 0F;
			for (int k = 0; k < 4; k++) {
				float[] a = place(quad.p[3 * k], quad.p[3 * k + 1], quad.p[3 * k + 2], armX, pose);
				float[] p = project(a[0], a[1], a[2], yaw, pitch);
				s[2 * k] = cx + p[0] * scale;
				s[2 * k + 1] = cy + p[1] * scale;
				depth += p[2] / 4F;
			}
			s[8] = depth;
			s[9] = quad.layer == ArmLayer.BLACK ? 0F : quad.layer == ArmLayer.RED ? 1F : 2F;
			float[] n = normal(quad, armX, pose, yaw, pitch);
			s[10] = n[0];
			s[11] = n[1];
			s[12] = n[2];
			projected.add(s);
		}
		projected.sort((a, b) -> Float.compare(b[8], a[8]));
		for (float[] s : projected) fill(g, s, (int) s[9]);
	}

	/** A point of arm space A in the model's: the shoulder's pivot at ({@code armX}, 2), turned by the pose (x then y rotation) about it. */
	private static float[] place(float x, float y, float z, float armX, float[] pose) {
		float cx = (float) Math.cos(pose[0]), sx = (float) Math.sin(pose[0]);
		float y1 = y * cx - z * sx, z1 = y * sx + z * cx;
		float cy = (float) Math.cos(pose[1]), sy = (float) Math.sin(pose[1]);
		float x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
		return new float[] {x2 + armX, y1 + 2F, z2};
	}

	private static float[] project(float x, float y, float z, float yaw, float pitch) {
		float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
		float x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
		float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
		float y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
		return new float[] {x1, y2, z2};
	}

	private static float[] normal(Quad q, float armX, float[] pose, float yaw, float pitch) {
		float[][] c = new float[4][];
		for (int k = 0; k < 4; k++) {
			float[] a = place(q.p[3 * k], q.p[3 * k + 1], q.p[3 * k + 2], armX, pose);
			c[k] = project(a[0], a[1], a[2], yaw, pitch);
		}
		float ux = c[2][0] - c[0][0], uy = c[2][1] - c[0][1], uz = c[2][2] - c[0][2], vx = c[3][0] - c[1][0], vy = c[3][1] - c[1][1], vz = c[3][2] - c[1][2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return l < 1e-9F ? new float[] {0F, 0F, 1F} : new float[] {nx / l, ny / l, nz / l};
	}

	private static void fill(Graphics2D g, float[] s, int layer) {
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

	private static void wire(Graphics2D g, float x0, float y0, float z0, float x1, float y1, float z1, float yaw, float pitch, float scale, float cx, float cy, float ax,
			float az, float[] pose) {
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

	/** A box of the arm (arm-local coordinates about its centre line) as six translucent faces, in the pose. */
	private static void solidBox(Graphics2D g, float x0, float y0, float z0, float x1, float y1, float z1, float yaw, float pitch, float scale, float cx, float cy, float armX,
			float[] pose) {
		float[][] c = new float[8][];
		for (int i = 0; i < 8; i++) {
			float[] a = place(((i & 1) == 0 ? x0 : x1), (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1, armX, pose);
			c[i] = project(a[0], a[1], a[2], yaw, pitch);
		}
		int[][] faces = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
		List<float[]> order = new ArrayList<>();
		for (int[] f : faces) {
			float depth = 0F;
			for (int v : f) depth += c[v][2] / 4F;
			order.add(new float[] {depth, f[0], f[1], f[2], f[3]});
		}
		order.sort((a, b) -> Float.compare(b[0], a[0]));
		for (float[] o : order) {
			int[] xs = new int[4], ys = new int[4];
			for (int k = 0; k < 4; k++) {
				xs[k] = Math.round(cx + c[(int) o[1 + k]][0] * scale);
				ys[k] = Math.round(cy + c[(int) o[1 + k]][1] * scale);
			}
			g.fillPolygon(xs, ys, 4);
			Color keep = g.getColor();
			g.setColor(new Color(120, 90, 70, 200));
			g.drawPolygon(xs, ys, 4);
			g.setColor(keep);
		}
	}
}
