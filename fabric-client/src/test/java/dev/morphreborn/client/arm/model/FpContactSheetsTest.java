package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.AccessoryRenderer;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.FormCoverTest;
import dev.morphreborn.client.arm.geom.FpClearance;
import dev.morphreborn.client.arm.geom.FpLayout;
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
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Contact sheets of the overlay's accessories through the hand pass's 70 degree 16:9 lens (2026-11-24), at home and after making room, with what is held
 * drawn as the grey spheres the solver keeps clear of. Written only when {@code FP_SHEETS_DIR} names a folder; not a test of anything.
 */
class FpContactSheetsTest {
	private record Case(String file, String title, String form, boolean bow, FpPoseMath.Kind kind, float t, int mask, boolean left, boolean raised) {}

	private static final class Recorder implements VertexConsumer {
		final List<float[]> kept = new ArrayList<>();

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			kept.add(new float[] {x, y, z});
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException();
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}

	@Test
	void writeSheets() throws IOException {
		String dir = System.getenv("FP_SHEETS_DIR");
		if (dir == null || dir.isEmpty()) return;
		Path out = Path.of(dir);
		Files.createDirectories(out);
		Case[] cases = {
				new Case("all-four-bow-drawn", "all four + bow arm drawn", ArmProtocol.BOW, true, FpPoseMath.Kind.BOW, 1F, 15, false, true),
				new Case("shield-raised-katana-swing", "shield raised + katana swing", ArmProtocol.KATANA, false, FpPoseMath.Kind.WHACK, 0.5F, 2, false, true),
				new Case("flint-crossbow-charged", "flint + crossbow charged", ArmProtocol.CROSSBOW, false, FpPoseMath.Kind.CROSSBOW_CHARGED, 0F, 8, false, false),
				new Case("spyglass-lantern-katana", "spyglass + lantern + katana", ArmProtocol.KATANA, false, FpPoseMath.Kind.IDLE, 0F, 5, false, false),
				new Case("all-four-mace-swing", "all four + mace swing", ArmProtocol.MACE, false, FpPoseMath.Kind.WHACK, 0.4F, 15, false, false),
				new Case("all-four-whip-idle-left", "all four + whip idle (left hand)", ArmProtocol.WHIP, false, FpPoseMath.Kind.IDLE, 0F, 15, true, false),
				new Case("all-four-trident", "all four + trident raised", ArmProtocol.TRIDENT, false, FpPoseMath.Kind.TRIDENT, 1F, 15, false, true),
				new Case("all-four-nothing", "all four, nothing held", ArmProtocol.KATANA, false, FpPoseMath.Kind.IDLE, 0F, 15, false, true),
				new Case("shield-lowered", "shield out, lowered (the cue)", ArmProtocol.KATANA, false, FpPoseMath.Kind.IDLE, 0F, 2, false, false),
				new Case("shield-raised", "shield raised", ArmProtocol.KATANA, false, FpPoseMath.Kind.IDLE, 0F, 2, false, true),
				new Case("shield-lowered-left", "shield out, lowered, left-handed", ArmProtocol.KATANA, false, FpPoseMath.Kind.IDLE, 0F, 2, true, false)};
		for (Case c : cases) {
			BufferedImage a = sheet(c, false), b = sheet(c, true);
			BufferedImage both = new BufferedImage(a.getWidth() * 2, a.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = both.createGraphics();
			g.drawImage(a, 0, 0, null);
			g.drawImage(b, a.getWidth(), 0, null);
			g.dispose();
			ImageIO.write(both, "png", out.resolve(c.file + ".png").toFile());
		}
	}

	private static ArmDraw draw(Case c) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		for (int i = 0; i < ages.length; i++) ages[i] = 500F;
		return new ArmDraw("", 2, 0, ArmDraw.CTX_FP, c.left, false, false, 100F, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, (c.mask & 1) != 0, 500F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0, 1F, c.mask,
				c.raised ? 1 << 4 : 0, ages, false);
	}

	private static BufferedImage sheet(Case c, boolean makeRoom) {
		dev.morphreborn.client.arm.geom.ShieldMotion.clear(); // the raise eases from what it last was: each sheet starts at rest in what it shows
		FpHeld.forgetAll();
		FpLayout.clear();
		FpClearance.forget();
		Matrix4f pose = FpPoseMath.pose(c.kind, c.left, c.t, 0F, new Matrix4f());
		FpHeld.recordHeld(true, pose, c.form, c.bow, new float[0]);
		ArmDraw d = draw(c);
		if (makeRoom) FpOverlay.place(d, new Matrix4f(), (c.mask & 1) != 0, 0);
		FpClearance.Obstacles held = new FpClearance.Obstacles();
		FpHeld.volumes(new Matrix4f(), c.left, held);
		int w = 960, h = 540;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(120, 160, 120));
		g.fillRect(0, 0, w, h);
		float f = (h / 2F) / (float) Math.tan(Math.toRadians(35));
		// what is held: grey spheres
		for (int j = 0; j < held.count(); j++) {
			float[] s = held.sphere(j);
			if (-s[2] < 0.5F) continue;
			float sx = w / 2F + s[0] / -s[2] * f, sy = h / 2F - s[1] / -s[2] * f, r = s[3] / -s[2] * f;
			g.setColor(new Color(110, 110, 120, 140));
			g.fillOval(Math.round(sx - r), Math.round(sy - r), Math.round(2 * r), Math.round(2 * r));
		}
		List<float[]> quads = new ArrayList<>();
		// the held arm form's own drawn quads (tier 3, the pose's swing and pull), carried into the view space by its grip frame as the hand pass does
		if (c.form != null) {
			Matrix4f gm = new Matrix4f();
			ArmTransforms.firstPersonFrame(c.form, c.bow, c.left, gm);
			gm.mulLocal(pose).scaleLocal(16F);
			java.util.List<float[]> vs = FormCoverTest.stateVertices(c.form, c.left, 3, c.kind == FpPoseMath.Kind.WHACK ? c.t : 0F, c.kind == FpPoseMath.Kind.BOW || c.kind == FpPoseMath.Kind.CROSSBOW_CHARGE ? c.t : 0F, 100F);
			org.joml.Vector3f tv = new org.joml.Vector3f();
			for (int i = 0; i + 3 < vs.size(); i += 4) {
				float[] q = new float[14];
				boolean behind = false;
				float depth = 0F;
				for (int k = 0; k < 4; k++) {
					float[] v0 = vs.get(i + k);
					gm.transformPosition(tv.set(v0[0], v0[1], v0[2]));
					if (tv.z > -0.5F) behind = true;
					q[2 * k] = w / 2F + tv.x / -tv.z * f;
					q[2 * k + 1] = h / 2F - tv.y / -tv.z * f;
					depth += tv.z / 4F;
				}
				if (behind) continue;
				q[8] = depth;
				q[9] = 3F;
				quads.add(q);
			}
		}
		for (int index = 0; index < 4; index++) {
			if ((c.mask & (1 << index)) == 0) continue;
			for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
				Recorder rec = new Recorder();
				SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
				Loft.pulseFrom(null, 0F);
				if (index == 0) ArmGeometry.lure().emitFirstPerson(d, layer, m);
				else {
					AccessoryRenderer r = ArmGeometry.accessory(ArmProtocol.ACCESSORIES.get(index));
					r.emitFirstPerson(d, layer, m);
				}
				for (int i = 0; i + 3 < rec.kept.size(); i += 4) {
					float[] q = new float[14];
					boolean behind = false;
					float depth = 0F;
					for (int k = 0; k < 4; k++) {
						float[] v = rec.kept.get(i + k);
						if (v[2] > -0.5F) behind = true;
						q[2 * k] = w / 2F + v[0] / -v[2] * f;
						q[2 * k + 1] = h / 2F - v[1] / -v[2] * f;
						depth += v[2] / 4F;
					}
					if (behind) continue;
					q[8] = depth;
					q[9] = layer == ArmLayer.BLACK ? 0F : layer == ArmLayer.RED ? 1F : 2F;
					quads.add(q);
				}
			}
		}
		quads.sort((p, q) -> Float.compare(p[8], q[8]));
		for (float[] q : quads) {
			int layer = (int) q[9];
			Color col = layer == 1 ? new Color(215, 25, 25) : layer == 2 ? new Color(255, 225, 190) : layer == 3 ? new Color(90, 120, 200) : new Color(52, 52, 60);
			int[] xs = new int[4], ys = new int[4];
			for (int k = 0; k < 4; k++) {
				xs[k] = Math.round(q[2 * k]);
				ys[k] = Math.round(q[2 * k + 1]);
			}
			g.setColor(col);
			g.fillPolygon(xs, ys, 4);
			g.setColor(layer == 1 ? new Color(120, 10, 10) : new Color(18, 18, 22));
			g.setStroke(new BasicStroke(0.5F));
			g.drawPolygon(xs, ys, 4);
		}
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 16));
		g.drawString(c.title + (makeRoom ? "  (after: made room)" : "  (before: homes)"), 10, 22);
		g.dispose();
		return img;
	}
}
