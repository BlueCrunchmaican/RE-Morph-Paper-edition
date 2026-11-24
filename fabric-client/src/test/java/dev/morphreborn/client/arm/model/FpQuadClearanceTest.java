package dev.morphreborn.client.arm.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.AccessoryRenderer;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.FormCoverTest;
import dev.morphreborn.client.arm.geom.FpClearance;
import dev.morphreborn.client.arm.geom.FpLayout;
import dev.morphreborn.client.arm.geom.LureGeometry;
import dev.morphreborn.client.arm.geom.ShieldMotion;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The independent check of the overlay's clearance (2026-11-24): {@link FpClearanceTest} proves the placement against the spheres the solver
 * works with; this one draws the real accessories at the placement ({@code emitFirstPerson}, every quad of every layer) and checks the actual
 * vertices: none of them inside what is held (where it could be seen), and the accessories' vertices keeping apart from one another, so
 * nothing rests on the sphere model alone (the spheres could be too small for the geometry).
 */
class FpQuadClearanceTest {
	private static final Matrix4f IDENTITY = new Matrix4f();
	private static final int LANTERN = 0, SHIELD = 1, SPYGLASS = 2, FLINT = 3;

	@BeforeEach
	@AfterEach
	void reset() {
		FpHeld.forgetAll();
		FpLayout.clear();
		FpClearance.forget();
		LureGeometry.clear();
		LureGeometry.setOffhand(0F);
		ShieldMotion.clear();
	}

	/** Counts the vertices an emit writes and keeps their positions. */
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

	private static ArmDraw draw(int mask, boolean left, boolean raised, float time) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		for (int i = 0; i < ages.length; i++) ages[i] = 500F;
		return new ArmDraw("", 2, 0, ArmDraw.CTX_FP, left, false, false, time, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER,
				ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, (mask & 1) != 0, 500F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0, 1F, mask,
				raised ? 1 << (4 * SHIELD) : 0, ages, false);
	}

	/** Every vertex accessory {@code index} writes in the overlay (all layers) for {@code d}, with the placement already in {@link FpLayout}. */
	private static List<float[]> vertices(int index, ArmDraw d) {
		Recorder rec = new Recorder();
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			if (index == LANTERN) ArmGeometry.lure().emitFirstPerson(d, layer, m);
			else {
				AccessoryRenderer r = ArmGeometry.accessory(ArmProtocol.ACCESSORIES.get(index));
				r.emitFirstPerson(d, layer, m);
			}
		}
		return rec.kept;
	}

	@Test
	void theDrawnQuadsOfEveryAccessoryStayOutOfWhatIsHeldAndApartFromEachOther() {
		float minHeld = Float.MAX_VALUE, minPair = Float.MAX_VALUE, minReal = Float.MAX_VALUE;
		int realVertices = 0;
		String worstHeld = "", worstPair = "", worstReal = "";
		int cases = 0;
		String[] forms = ArmProtocol.FORMS.toArray(new String[0]);
		for (boolean left : new boolean[] {false, true}) {
			for (String form : forms) {
				boolean bow = ArmProtocol.BOW.equals(form);
				FpPoseMath.Kind[] kinds = bow ? new FpPoseMath.Kind[] {FpPoseMath.Kind.IDLE, FpPoseMath.Kind.BOW}
						: form.equals(ArmProtocol.CROSSBOW) ? new FpPoseMath.Kind[] {FpPoseMath.Kind.CROSSBOW_CHARGE, FpPoseMath.Kind.CROSSBOW_CHARGED, FpPoseMath.Kind.IDLE}
								: new FpPoseMath.Kind[] {FpPoseMath.Kind.IDLE, FpPoseMath.Kind.WHACK};
				for (FpPoseMath.Kind kind : kinds) {
					for (float t : kind == FpPoseMath.Kind.IDLE ? new float[] {0F} : new float[] {0.3F, 0.7F, 1F}) {
						Grid real = realHeld(form, left, bow, kind, t, FpPoseMath.pose(kind, left, t, 0F, new Matrix4f()));
						for (int mask : new int[] {1, 2, 4, 8, 15}) {
							Matrix4f pose = FpPoseMath.pose(kind, left, t, 0F, new Matrix4f());
							FpHeld.forgetAll();
							FpHeld.recordHeld(true, pose, form, bow, new float[0]);
							ShieldMotion.clear();
							ArmDraw d = draw(mask, left, true, 100F);
							FpClearance.forget();
							FpOverlay.place(d, IDENTITY, (mask & 1) != 0, 0);
							FpClearance.Obstacles held = new FpClearance.Obstacles();
							FpHeld.volumes(IDENTITY, left, held);
							String where = form + " " + kind + " " + t + " set " + mask + (left ? " left" : " right");
							List<List<float[]>> all = new ArrayList<>();
							for (int index = 0; index < 4; index++) {
								if ((mask & (1 << index)) == 0) {
									all.add(List.of());
									continue;
								}
								List<float[]> vs = vertices(index, d);
								all.add(vs);
								for (float[] v : vs) {
									// the form's own drawn quads, not the spheres that stand for them: no drawn accessory vertex within half a px of a drawn point of the held form
									float near = real.nearestVisible(v);
									if (near < minReal) {
										minReal = near;
										worstReal = where + " " + ArmProtocol.ACCESSORIES.get(index) + " v=(" + v[0] + "," + v[1] + "," + v[2] + ")";
									}
									realVertices++;
									for (int j = 0; j < held.count(); j++) {
										float[] c = center(held, j);
										float dist = (float) Math.sqrt((v[0] - c[0]) * (v[0] - c[0]) + (v[1] - c[1]) * (v[1] - c[1]) + (v[2] - c[2]) * (v[2] - c[2])) - c[3];
										if (dist < minHeld && visible(v, c)) {
											minHeld = dist;
											worstHeld = where + " " + ArmProtocol.ACCESSORIES.get(index) + " v=(" + v[0] + "," + v[1] + "," + v[2] + ") sphere=(" + c[0] + "," + c[1] + "," + c[2] + " r " + c[3] + ")";
										}
									}
								}
							}
							cases++;
							for (int a = 0; a < 4; a++) {
								for (int b = a + 1; b < 4; b++) {
									for (float[] va : all.get(a)) {
										for (float[] vb : all.get(b)) {
											float dist = (float) Math.sqrt((va[0] - vb[0]) * (va[0] - vb[0]) + (va[1] - vb[1]) * (va[1] - vb[1]) + (va[2] - vb[2]) * (va[2] - vb[2]));
											if (dist < minPair && FpClearance.onScreen((va[0] + vb[0]) / 2F, (va[1] + vb[1]) / 2F, (va[2] + vb[2]) / 2F, -2.5F)) {
												minPair = dist;
												worstPair = where + " " + ArmProtocol.ACCESSORIES.get(a) + "/" + ArmProtocol.ACCESSORIES.get(b);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
		System.out.printf("overlay quads: %d cases, closest drawn vertex to a held sphere %.3f px (%s), closest pair of vertices %.3f px (%s)%n", cases, minHeld, worstHeld, minPair,
				worstPair);
		System.out.printf("overlay quads against the held forms' own drawn points: %d accessory vertices, closest %.3f px (%s)%n", realVertices, minReal, worstReal);
		assertTrue(minReal > 0.5F, "a drawn accessory vertex is " + minReal + " px from a drawn point of the held form: " + worstReal);
		// (the closest approach to the solver's own spheres is printed above for information: the spheres wrap the form with a skin and a cell, so a vertex a little inside one is not touching the form)
		assertTrue(minPair > 0.05F, "two accessories' vertices meet: " + minPair + " px apart: " + worstPair);
	}

	/** Points of what a form really draws, in a grid of 2 px cells for the nearest-point query. */
	private static final class Grid {
		final java.util.Map<Long, List<float[]>> cells = new java.util.HashMap<>();

		static long key(int x, int y, int z) {
			return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
		}

		void add(float[] p) {
			cells.computeIfAbsent(key(Math.floorDiv((int) Math.floor(p[0]), 2), Math.floorDiv((int) Math.floor(p[1]), 2), Math.floorDiv((int) Math.floor(p[2]), 2)),
					k -> new ArrayList<>()).add(p.clone());
		}

		/** The distance to the nearest point (up to about 3 px; larger is "far") where the lens can show the two, else far. */
		float nearestVisible(float[] v) {
			int cx = Math.floorDiv((int) Math.floor(v[0]), 2), cy = Math.floorDiv((int) Math.floor(v[1]), 2), cz = Math.floorDiv((int) Math.floor(v[2]), 2);
			float best = 100F;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						List<float[]> cell = cells.get(key(cx + dx, cy + dy, cz + dz));
						if (cell == null) continue;
						for (float[] p : cell) {
							float d = (float) Math.sqrt((v[0] - p[0]) * (v[0] - p[0]) + (v[1] - p[1]) * (v[1] - p[1]) + (v[2] - p[2]) * (v[2] - p[2]));
							if (d < best && FpClearance.onScreen((v[0] + p[0]) / 2F, (v[1] + p[1]) / 2F, (v[2] + p[2]) / 2F, -2.5F)) best = d;
						}
					}
				}
			}
			return best;
		}
	}

	/** What {@code form} draws in the pose {@code pose} (a hold of vanilla's) at the phase {@code t} of {@code kind}, as points in the hand pass's view space (px). */
	private static Grid realHeld(String form, boolean left, boolean bow, FpPoseMath.Kind kind, float t, Matrix4f pose) {
		Grid grid = new Grid();
		Matrix4f g = new Matrix4f();
		dev.morphreborn.client.arm.model.ArmTransforms.firstPersonFrame(form, bow, left, g);
		g.mulLocal(pose).scaleLocal(16F);
		org.joml.Vector3f v = new org.joml.Vector3f();
		float swing = kind == FpPoseMath.Kind.WHACK ? t : 0F, pull = kind == FpPoseMath.Kind.BOW || kind == FpPoseMath.Kind.CROSSBOW_CHARGE ? t : 0F;
		for (int tier : new int[] {1, 3}) {
			FormCoverTest.statePoints(form, left, tier, swing, pull, dev.morphreborn.client.arm.geom.ArmDraw.NO_SPECIAL, 100F, p -> {
				g.transformPosition(v.set(p[0], p[1], p[2]));
				grid.add(new float[] {v.x, v.y, v.z});
			});
		}
		return grid;
	}

	/**
	 * The lantern's chain is a live rope (the camera turning swings it): a bow drawn, a tool swung or held, and the camera flicked about every which way, the
	 * lantern's drawn quads stay out of what is held and of the other accessories on every frame, not only at rest.
	 */
	@Test
	void aSwungLanternChainStaysOutOfWhatIsHeldAndOfTheOtherAccessoriesWhileTheCameraTurns() {
		float minReal = Float.MAX_VALUE, minSphere = Float.MAX_VALUE, minOther = Float.MAX_VALUE;
		String worstReal = "", worstSphere = "", worstOther = "";
		int frames = 0;
		String[] helds = {"arm bow drawn", "vanilla bow drawn", "arm crossbow charging", "katana swing", "trident thrown up", "pickaxe swing"};
		for (boolean left : new boolean[] {false, true}) {
			for (String name : helds) {
				for (int mask : new int[] {1, 9, 15}) {
					FpHeld.forgetAll();
					FpClearance.forget();
					LureGeometry.clear();
					LureGeometry.setOffhand(0F);
					ShieldMotion.clear();
					java.util.Random rnd = new java.util.Random(31L * name.hashCode() + mask + (left ? 7 : 0));
					Grid bowReal = name.equals("arm bow drawn") ? realHeld(ArmProtocol.BOW, left, true, FpPoseMath.Kind.BOW, 1F, FpPoseMath.pose(FpPoseMath.Kind.BOW, left, 1F, 0F, new Matrix4f())) : null;
					for (int f = 0; f < 90; f++) {
						float t = 0.45F + 0.55F * (f % 30) / 30F;
						Matrix4f pose;
						Grid real;
						switch (name) {
							case "arm bow drawn" -> {
								pose = FpPoseMath.pose(FpPoseMath.Kind.BOW, left, 1F, 0F, new Matrix4f());
								FpHeld.recordHeld(true, pose, ArmProtocol.BOW, true, new float[0]);
								real = bowReal;
							}
							case "vanilla bow drawn" -> {
								pose = FpPoseMath.pose(FpPoseMath.Kind.BOW, left, 1F, 0F, new Matrix4f());
								FpHeld.recordHeld(true, pose, null, false, FpPoseMath.outlinePoints("bow_pulling_2", ArmTransforms.BOW_TP_RIGHT, left));
								real = null;
							}
							case "arm crossbow charging" -> {
								pose = FpPoseMath.pose(FpPoseMath.Kind.CROSSBOW_CHARGE, left, t, 0F, new Matrix4f());
								FpHeld.recordHeld(true, pose, ArmProtocol.CROSSBOW, false, new float[0]);
								real = null;
							}
							case "katana swing" -> {
								pose = FpPoseMath.pose(FpPoseMath.Kind.WHACK, left, t, 0F, new Matrix4f());
								FpHeld.recordHeld(true, pose, ArmProtocol.KATANA, false, new float[0]);
								real = null;
							}
							case "trident thrown up" -> {
								pose = FpPoseMath.pose(FpPoseMath.Kind.TRIDENT, left, t, 0F, new Matrix4f());
								FpHeld.recordHeld(true, pose, ArmProtocol.TRIDENT, false, new float[0]);
								real = null;
							}
							default -> {
								pose = FpPoseMath.pose(FpPoseMath.Kind.WHACK, left, t, 0F, new Matrix4f());
								FpHeld.recordHeld(true, pose, ArmProtocol.PICKAXE, false, new float[0]);
								real = null;
							}
						}
						// the view turns: mostly still, now and then a flick (up to 35 degrees in a frame), the rope carried against it
						float yaw = rnd.nextInt(5) == 0 ? (rnd.nextFloat() - 0.5F) * 70F : 0F, pitch = rnd.nextInt(7) == 0 ? (rnd.nextFloat() - 0.5F) * 50F : 0F;
						LureGeometry.cameraStep(yaw, pitch);
						ArmDraw d = draw(mask, left, f % 20 < 10, 100F + f);
						FpOverlay.place(d, IDENTITY, true, 0);
						FpClearance.Obstacles held = new FpClearance.Obstacles();
						FpHeld.volumes(IDENTITY, left, held);
						List<float[]> lantern = vertices(LANTERN, d);
						String where = name + " frame " + f + " set " + mask + (left ? " left" : " right");
						frames++;
						for (float[] v : lantern) {
							for (int j = 0; j < held.count(); j++) {
								float[] c = held.sphere(j);
								float dist = (float) Math.sqrt((v[0] - c[0]) * (v[0] - c[0]) + (v[1] - c[1]) * (v[1] - c[1]) + (v[2] - c[2]) * (v[2] - c[2])) - c[3];
								if (dist < minSphere && visible(v, c)) {
									minSphere = dist;
									worstSphere = where + " v=(" + v[0] + "," + v[1] + "," + v[2] + ")";
								}
							}
							if (real != null) {
								float near = real.nearestVisible(v);
								if (near < minReal) {
									minReal = near;
									worstReal = where;
								}
							}
						}
						for (int index = 1; index < 4; index++) {
							if ((mask & (1 << index)) == 0) continue;
							for (float[] w : vertices(index, d)) {
								for (float[] v : lantern) {
									float dist = (float) Math.sqrt((v[0] - w[0]) * (v[0] - w[0]) + (v[1] - w[1]) * (v[1] - w[1]) + (v[2] - w[2]) * (v[2] - w[2]));
									if (dist < minOther && FpClearance.onScreen((v[0] + w[0]) / 2F, (v[1] + w[1]) / 2F, (v[2] + w[2]) / 2F, -2.5F)) {
										minOther = dist;
										worstOther = where + " " + ArmProtocol.ACCESSORIES.get(index);
									}
								}
							}
						}
					}
				}
			}
		}
		System.out.printf("swung lantern: %d frames, closest quad vertex to a held sphere %.3f px (%s), to the bow's/crossbow's own drawn points %.3f px (%s), to another accessory %.3f px (%s)%n",
				frames, minSphere, worstSphere, minReal, worstReal, minOther, worstOther);
		assertTrue(minSphere > -0.05F, "the swung lantern's quad is inside what is held by " + (-minSphere) + " px: " + worstSphere);
		assertTrue(minReal > 0.3F, "the swung lantern is " + minReal + " px from a drawn point of the held form: " + worstReal);
		assertTrue(minOther > 0.05F, "the swung lantern meets another accessory: " + minOther + " px apart: " + worstOther);
	}


	/**
	 * The model the solver works with must put the lantern where it is drawn, shrunk and shifted too: its lure hangs a chain's length under the hook, which
	 * is not scaled. Whatever the model's ball radius, the offset between the drawn lure (its lowest vertex and its middle, under the hook) and the model's
	 * ball is the same at any placement as at home; a model that scaled the chain about the stalk's root would be off by (1 - scale) times the chain's length.
	 */
	@Test
	void theLanternModelPutsItsLureWhereItIsDrawnWhenShrunkAndShifted() {
		for (boolean left : new boolean[] {false, true}) {
			float baseY = Float.NaN, baseX = Float.NaN;
			for (float[] placement : new float[][] {{0F, 0F, 0F, 1F}, {2.5F, 3F, -1F, 0.5F}, {-3F, -4F, 0.5F, 0.75F}, {4F, 1F, 1F, 0.5F}}) {
				FpHeld.forgetAll();
				FpClearance.forget();
				LureGeometry.clear();
				LureGeometry.setOffhand(0F);
				FpClearance.Pose pose = new FpClearance.Pose().set(placement[0], placement[1], placement[2], placement[3]);
				FpLayout.clear();
				FpLayout.set(0, pose);
				// the rope settles over a few frames
				List<float[]> drawn = null;
				for (int f = 0; f < 40; f++) drawn = vertices(LANTERN, draw(1, left, false, 100F + f));
				FpClearance.Body body = new FpClearance.Body();
				LureGeometry.firstPersonBody(left, LureGeometry.offhandBlend(), body);
				FpClearance.Obstacles placed = new FpClearance.Obstacles().addPlaced(body, pose);
				float[] ball = placed.sphere(placed.count() - 1), hook = placed.sphere(5);
				float lowest = Float.MAX_VALUE, sumX = 0F;
				int n = 0;
				for (float[] v : drawn) {
					if (v[1] < hook[1] - 1.5F && Math.abs(v[0] - hook[0]) < 2.6F && Math.abs(v[2] - hook[2]) < 2.2F) {
						lowest = Math.min(lowest, v[1]);
						sumX += v[0];
						n++;
					}
				}
				assertTrue(n > 20, "the lure's vertices under the hook were found for " + java.util.Arrays.toString(placement) + ": " + n);
				float offY = lowest - (ball[1] - ball[3]), offX = sumX / n - ball[0];
				if (Float.isNaN(baseY)) {
					baseY = offY;
					baseX = offX;
				}
				assertTrue(Math.abs(offY - baseY) < 0.7F, "the drawn lure is " + (offY - baseY) + " px off where the model puts the ball for " + java.util.Arrays.toString(placement) + " (home offset " + baseY + ")");
				assertTrue(Math.abs(offX - baseX) < 0.7F, "the drawn lure is " + (offX - baseX) + " px sideways of the model's ball for " + java.util.Arrays.toString(placement));
			}
		}
		FpLayout.clear();
	}

	private static float[] center(FpClearance.Obstacles o, int j) {
		return o.sphere(j);
	}

	/** Whether the contact of vertex {@code v} with the sphere {@code c} is where the lens shows. */
	private static boolean visible(float[] v, float[] c) {
		return FpClearance.onScreen((v[0] + c[0]) / 2F, (v[1] + c[1]) / 2F, (v[2] + c[2]) / 2F, -2.5F);
	}
}
