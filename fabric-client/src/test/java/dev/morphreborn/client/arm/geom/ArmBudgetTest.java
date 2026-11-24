package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Rope;
import dev.morphreborn.client.arm.render.SkinMesh;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The arm's cost in quads (section 2.10), counted the way the renderer draws it: every layer (black, red, and glow with
 * the glow setting on) of the tool, plus the first-person forearm and fist (which the tool draws itself) or the
 * third-person sheath, taking the worst moment over a long stretch of time (drips gather and fall, so the count moves).
 * <ul>
 *   <li>Own arm, first person, full detail: at most 1300 steady, 1700 through FORM's and RESHAPE's bursts.</li>
 *   <li>Another player, third person, full detail: at most 1200 (levels 1 and 2; level 3's extra pieces are allowed the
 *       own-arm ceiling, 1300).</li>
 *   <li>LOD 1 about 480, LOD 2 about 96 (each with a fifth's leeway), LOD 3 thirty (four boxes and one).</li>
 *   <li>GUI icons no more than LOD 1's tool.</li>
 * </ul>
 * Also holds the helpers the other geometry tests share: a vertex recorder and a draw builder.
 */
class ArmBudgetTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	private static final String[] FORMS = {ArmProtocol.KATANA, ArmProtocol.PICKAXE, ArmProtocol.SPEAR, ArmProtocol.MACE, ArmProtocol.TRIDENT, ArmProtocol.CROSSBOW,
			ArmProtocol.CLAWS};

	// ------------------------------------------------------------------ helpers shared by the geometry tests

	/** A vertex consumer that counts what it is given and checks every value is a number and every normal unit length. */
	static final class Recorder implements VertexConsumer {
		int vertices;
		final java.util.List<float[]> kept = new java.util.ArrayList<>();
		boolean keep;

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			for (float f : new float[] {x, y, z, u, v, nx, ny, nz}) assertTrue(Float.isFinite(f), "a vertex value isn't a number");
			assertEquals(1F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-3F, "normal length");
			vertices++;
			if (keep) kept.add(new float[] {x, y, z});
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException("SkinMesh writes whole vertices");
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

		int quads() {
			return vertices / 4;
		}
	}

	/** A draw to change field by field (the record's constructor has 36 arguments). */
	static final class Draw {
		String form = ArmProtocol.KATANA;
		int tier = 2, lod = 0, ctx = ArmDraw.CTX_TP, seed = 7, state = ArmProtocol.STATE_FORMED, specialKind = ArmDraw.NO_SPECIAL, pulseCount;
		boolean leftHand, slim, chest, mining, glow = true, dissolve = true, drips = true, rope = true;
		float time = 100F, progress = 1F, swing, bowPull, biomassFrac = 1F, biomassAbs = ArmDraw.FULL, surge, specialAge = ArmDraw.NEVER, hitAge = ArmDraw.NEVER,
				breakAge = ArmDraw.NEVER, recallLeft;
		String fromForm = "";
		float[] pulses = new float[0];

		Draw form(String f) {
			form = f;
			return this;
		}

		Draw tier(int t) {
			tier = t;
			return this;
		}

		Draw lod(int l) {
			lod = l;
			return this;
		}

		Draw ctx(int c) {
			ctx = c;
			return this;
		}

		Draw time(float t) {
			time = t;
			return this;
		}

		/**
		 * Poses {@code s} at {@code p} of its tool timeline (FORM's ticks / 14 and so on, as the geometry is drawn): the
		 * arm's own lead and the longer real timeline are mapped in by {@link ArmDraw#progressAt}.
		 */
		Draw state(int s, float p) {
			state = s;
			progress = ArmDraw.toolTicks(s) > 0 ? ArmDraw.progressAt(s, p * ArmDraw.toolTicks(s)) : p;
			return this;
		}

		/** Poses {@code s} at real progress {@code p} (the arm's own turning). */
		Draw real(int s, float p) {
			state = s;
			progress = p;
			return this;
		}

		ArmDraw build() {
			float[] nodes = null;
			if (rope && lod == 0) {
				nodes = new float[3 * ArmDraw.ROPE_NODES];
				Rope.droop(new Vector3f(), new Vector3f(0.3F, -0.95F, 0F).normalize(), new Vector3f(0F, -1F, 0F), ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, nodes);
			}
			boolean gui = ctx == ArmDraw.CTX_GUI;
			return new ArmDraw(form, tier, lod, ctx, leftHand, slim, chest, time, seed, state, progress, fromForm, swing, bowPull, mining, biomassFrac, biomassAbs, surge,
					specialAge, specialKind, hitAge, breakAge, pulses, pulseCount, false, 0F, recallLeft, glow, dissolve, drips && lod == 0 && !gui, nodes, 0F, -1F, 0F,
					null, 0xF000F0);
		}
	}

	static Draw draw(String form) {
		return new Draw().form(form);
	}

	/**
	 * Quads {@code d}'s tool draws in all its layers (first person: with the forearm and fist it brings), drawn as the
	 * renderer draws it: by {@code d.drawnForm()}'s geometry, the form being left for a reshape's first 7 ticks.
	 */
	static int toolQuads(ArmDraw d) {
		FormGeometry g = ArmGeometry.get(d.drawnForm());
		int total = 0;
		for (ArmLayer layer : LAYERS) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			Recorder r = new Recorder();
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0);
			g.emit(d, layer, m);
			assertEquals(r.quads(), m.quads(), "quads counted twice or lost");
			total += r.quads();
		}
		return total;
	}

	/** Quads {@code d}'s third-person sheath draws in all its layers. */
	static int sheathQuads(ArmDraw d) {
		int total = 0;
		for (ArmLayer layer : LAYERS) {
			if (layer == ArmLayer.GLOW && !d.glow()) continue;
			Recorder r = new Recorder();
			SheathGeometry.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0), d.slim(), d.leftHand());
			total += r.quads();
		}
		return total;
	}

	/** The most quads over a long stretch of time (drips gather and fall, glows pulse). */
	static int worst(Draw b, boolean sheath) {
		int max = 0;
		for (float t = 0F; t < 420F; t += 3.7F) {
			ArmDraw d = b.time(t).build();
			max = Math.max(max, toolQuads(d) + (sheath ? sheathQuads(d) : 0));
		}
		return max;
	}

	// ------------------------------------------------------------------ the budget (section 2.10)

	@Test
	void ownArmFirstPersonStaysUnderItsCeilings() {
		for (String form : FORMS) {
			for (int tier = 1; tier <= 3; tier++) {
				int steady = worst(draw(form).tier(tier).ctx(ArmDraw.CTX_FP), false);
				System.out.printf("%s L%d own FP LOD0 steady: %d%n", form, tier, steady);
				assertTrue(steady <= 1300, form + " L" + tier + " first person: " + steady + " quads, over 1300");
				assertTrue(steady >= (ArmDraw.FIRST_PERSON_ARM ? 700 : 400), form + " L" + tier + " first person: only " + steady + " quads; the detail is missing (first person is the tool alone)");
			}
		}
	}

	@Test
	void burstsStayUnderTheBurstCeiling() {
		for (String form : FORMS) {
			String other = form.equals(ArmProtocol.KATANA) ? ArmProtocol.PICKAXE : ArmProtocol.KATANA;
			for (int tier = 1; tier <= 3; tier++) {
				int worst = 0;
				for (int step = 0; step <= 56; step++) {
					float p = step / 56F;
					// FORM, with its burst tendrils
					worst = Math.max(worst, toolQuads(draw(form).tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 14F).state(ArmProtocol.STATE_FORMING, p).build()));
					// RESHAPE: one tool at a time (the old one collapsing, then the new one forming) and the renderer's 8-box knot
					Draw b = draw(form).tier(tier).ctx(ArmDraw.CTX_FP).time(50F + p * 16F).state(ArmProtocol.STATE_RESHAPING, p);
					b.fromForm = other;
					ArmDraw d = b.build();
					worst = Math.max(worst, toolQuads(d) + (d.knotTick() >= 0F ? 8 * 6 : 0));
				}
				System.out.printf("%s L%d own FP burst: %d%n", form, tier, worst);
				assertTrue(worst <= 1700, form + " L" + tier + " forming or reshaping: " + worst + " quads, over 1700");
			}
		}
	}

	@Test
	void anotherPlayerUpCloseStaysUnderItsCeiling() {
		// every arm look a player can choose (the tendril arm, the plain hand, the armour), drawn for another player
		try {
			for (int look = 0; look < dev.morphreborn.client.arm.ArmProtocol.LOOK_NAMES.size(); look++) {
				SheathGeometry.lookOverride = look;
				String name = dev.morphreborn.client.arm.ArmProtocol.LOOK_NAMES.get(look);
				for (String form : FORMS) {
					for (int tier = 1; tier <= 3; tier++) {
						int quads = worst(draw(form).tier(tier), true);
						System.out.printf("%s %s L%d TP LOD0 tool+sheath: %d%n", name, form, tier, quads);
						int ceiling = tier >= 3 ? 1300 : 1200;
						assertTrue(quads <= ceiling, name + " " + form + " L" + tier + " third person: " + quads + " quads, over " + ceiling);
					}
				}
			}
		} finally {
			SheathGeometry.lookOverride = -1;
		}
	}

	@Test
	void levelsOfDetailShed() {
		for (String form : FORMS) {
			for (int tier = 1; tier <= 3; tier++) {
				int lod1Tool = worst(draw(form).tier(tier).lod(1), false), lod1 = worst(draw(form).tier(tier).lod(1), true);
				int lod2 = worst(draw(form).tier(tier).lod(2), true), lod3 = worst(draw(form).tier(tier).lod(3), true);
				int icon = worst(draw(form).tier(tier).lod(ArmDraw.LOD_ICON).ctx(ArmDraw.CTX_GUI), false);
				System.out.printf("%s L%d LOD1 tool %d total %d, LOD2 %d, LOD3 %d, icon %d%n", form, tier, lod1Tool, lod1, lod2, lod3, icon);
				assertTrue(lod1Tool <= 360, form + " L" + tier + " LOD 1 tool: " + lod1Tool + " (about 300)");
				assertTrue(lod1 <= 576, form + " L" + tier + " LOD 1: " + lod1 + " (about 480)");
				assertTrue(lod2 <= 116, form + " L" + tier + " LOD 2: " + lod2 + " (about 96)");
				assertTrue(lod3 <= 30, form + " L" + tier + " LOD 3: " + lod3 + " (four boxes and one)");
				assertTrue(icon <= 360, form + " L" + tier + " icon: " + icon);
				assertTrue(lod3 < lod2 && lod2 < lod1, form + " L" + tier + ": each level of detail should cost less than the one before");
			}
		}
	}

	@Test
	void retractedArmDrawsNothingAndTheBraceletIsSmall() {
		for (String form : FORMS) {
			ArmDraw none = draw(form).state(ArmProtocol.STATE_NONE, 1F).build();
			assertEquals(0, toolQuads(none), form + ": no arm, no tool");
			assertEquals(0, sheathQuads(none), form + ": no arm, no sheath");
			Draw b = draw("").state(ArmProtocol.STATE_NONE, 1F);
			b.recallLeft = 100F;
			int bracelet = sheathQuads(b.build());
			assertTrue(bracelet > 0 && bracelet <= 100, "the recall bracelet: " + bracelet + " quads");
		}
	}
}
