package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.model.FormCover;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The solid the first-person overlay keeps the accessories out of is measured from what the forms really draw (2026-11-24, see {@code FormCover}). Every
 * quad of every layer, in every tier, swing, pull, special and time, both hands, is sampled finely ({@link #sample}); {@link #generate} wraps the samples in
 * spheres by cells and writes the table when the env {@code FORM_COVER_OUT} names a file; {@link #noDrawnPointOfAnyFormIsOutsideItsCover} checks the table in
 * the repo on a second set of states (other times, other swings and pulls, the other hand) that it was not made from.
 */
public class FormCoverTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	/** The size of the cells the samples are wrapped by, and the finest voxel the generator snaps to (px). */
	private static final float CELL = 3.2F, VOXEL = 0.8F;
	/** The skin the generator adds to every sphere, so states between the measured ones are held too (px). */
	private static final float SKIN = 1.0F;
	private static final int[] KINDS = {ArmDraw.NO_SPECIAL, ArmProtocol.FX_SWIPE, ArmProtocol.FX_VEIN, ArmProtocol.FX_WHIP, ArmProtocol.FX_BOLT, ArmProtocol.FX_SPEAR,
			ArmProtocol.FX_MACE, ArmProtocol.FX_CROSSBOW, ArmProtocol.FX_CLAWS};

	/** Feeds {@code sink} points over every quad {@code form} draws in the first-person hand pass for the given states (grip space G, px). */
	static void sample(String form, boolean left, float timeFrom, float timeStep, float[] swings, float[] pulls, int[] tiers, Consumer<float[]> sink) {
		FormGeometry g = ArmGeometry.get(form);
		for (int tier : tiers) {
			for (float swing : swings) {
				for (float pull : pulls) {
					for (int kind : KINDS) {
						for (float t = timeFrom; t < 300F; t += timeStep) {
							ArmBudgetTest.Draw b = ArmBudgetTest.draw(form).tier(tier).ctx(ArmDraw.CTX_FP).time(t);
							b.swing = swing;
							b.bowPull = pull;
							b.leftHand = left;
							b.specialKind = kind;
							b.specialAge = kind == ArmDraw.NO_SPECIAL ? ArmDraw.NEVER : 4F;
							ArmDraw d = b.build();
							for (ArmLayer layer : LAYERS) {
								ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
								r.keep = true;
								g.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
								quads(r.kept, sink);
							}
						}
					}
				}
			}
		}
	}

	/** Every vertex (four to a quad) {@code form} draws in one state of the first-person hand pass, all layers, in grip space G (px). */
	public static List<float[]> stateVertices(String form, boolean left, int tier, float swing, float pull, float time) {
		FormGeometry g = ArmGeometry.get(form);
		ArmBudgetTest.Draw b = ArmBudgetTest.draw(form).tier(tier).ctx(ArmDraw.CTX_FP).time(time);
		b.swing = swing;
		b.bowPull = pull;
		b.leftHand = left;
		ArmDraw d = b.build();
		List<float[]> out = new ArrayList<>();
		for (ArmLayer layer : LAYERS) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			g.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
			out.addAll(r.kept);
		}
		return out;
	}

	/** Feeds {@code sink} points over every quad {@code form} draws in one state of the first-person hand pass (grip space G, px), all layers. */
	public static void statePoints(String form, boolean left, int tier, float swing, float pull, int kind, float time, Consumer<float[]> sink) {
		FormGeometry g = ArmGeometry.get(form);
		ArmBudgetTest.Draw b = ArmBudgetTest.draw(form).tier(tier).ctx(ArmDraw.CTX_FP).time(time);
		b.swing = swing;
		b.bowPull = pull;
		b.leftHand = left;
		b.specialKind = kind;
		b.specialAge = kind == ArmDraw.NO_SPECIAL ? ArmDraw.NEVER : 4F;
		ArmDraw d = b.build();
		for (ArmLayer layer : LAYERS) {
			ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
			r.keep = true;
			g.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
			quads(r.kept, sink);
		}
	}

	/** Every quad of {@code vs} (four vertices each), sampled about every 0.8 px across its face. */
	public static void quads(List<float[]> vs, Consumer<float[]> sink) {
		float[] p = new float[3];
		for (int q = 0; q + 3 < vs.size(); q += 4) {
			float[] a = vs.get(q), b = vs.get(q + 1), c = vs.get(q + 2), d = vs.get(q + 3);
			int n = (int) Math.min(40, Math.ceil(Math.max(dist(a, b), dist(d, c)) / 0.8F)), m = (int) Math.min(40, Math.ceil(Math.max(dist(a, d), dist(b, c)) / 0.8F));
			n = Math.max(n, 1);
			m = Math.max(m, 1);
			for (int i = 0; i <= n; i++) {
				float s = i / (float) n;
				for (int j = 0; j <= m; j++) {
					float t = j / (float) m;
					for (int k = 0; k < 3; k++) p[k] = (a[k] + (b[k] - a[k]) * s) * (1F - t) + (d[k] + (c[k] - d[k]) * s) * t;
					sink.accept(p);
				}
			}
		}
	}

	private static float dist(float[] a, float[] b) {
		return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	/** The spheres wrapping the samples of {@code form}: voxels of {@link #VOXEL}, grouped in cells of {@link #CELL}, each cell's occupied voxels held by one sphere. */
	static float[] cover(String form) {
		java.util.Set<Long> voxels = new java.util.HashSet<>();
		int[] all = {1, 2, 3};
		for (boolean left : new boolean[] {false, true}) {
			sample(form, left, 0F, 7.9F, new float[] {0F, 0.25F, 0.5F, 0.75F, 1F}, new float[] {0F, 0.5F, 1F}, all,
					p -> voxels.add(key(Math.round(p[0] / VOXEL), Math.round(p[1] / VOXEL), Math.round(p[2] / VOXEL))));
		}
		Map<Long, List<int[]>> cells = new HashMap<>();
		int per = Math.round(CELL / VOXEL);
		for (long k : voxels) {
			int x = signed((int) ((k >> 42) & 0x1FFFFF)), y = signed((int) ((k >> 21) & 0x1FFFFF)), z = signed((int) (k & 0x1FFFFF));
			cells.computeIfAbsent(key(Math.floorDiv(x, per), Math.floorDiv(y, per), Math.floorDiv(z, per)), c -> new ArrayList<>()).add(new int[] {x, y, z});
		}
		List<float[]> spheres = new ArrayList<>();
		for (List<int[]> cell : cells.values()) {
			float[] lo = {1e9F, 1e9F, 1e9F}, hi = {-1e9F, -1e9F, -1e9F};
			for (int[] v : cell) {
				for (int k = 0; k < 3; k++) {
					lo[k] = Math.min(lo[k], v[k] * VOXEL);
					hi[k] = Math.max(hi[k], v[k] * VOXEL);
				}
			}
			float cx = (lo[0] + hi[0]) / 2F, cy = (lo[1] + hi[1]) / 2F, cz = (lo[2] + hi[2]) / 2F, r = 0F;
			for (int[] v : cell) {
				float dx = v[0] * VOXEL - cx, dy = v[1] * VOXEL - cy, dz = v[2] * VOXEL - cz;
				r = Math.max(r, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			// a voxel's samples are within half its diagonal of its centre
			spheres.add(new float[] {cx, cy, cz, r + VOXEL * 0.87F + SKIN});
		}
		spheres.sort((a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : a[1] != b[1] ? Float.compare(a[1], b[1]) : Float.compare(a[2], b[2]));
		float[] out = new float[4 * spheres.size()];
		for (int i = 0; i < spheres.size(); i++) System.arraycopy(spheres.get(i), 0, out, 4 * i, 4);
		return out;
	}

	private static int signed(int v) {
		return (v << 11) >> 11;
	}

	@Test
	void generate() throws Exception {
		String out = System.getenv("FORM_COVER_OUT");
		if (out == null || out.isBlank()) return;
		StringBuilder sb = new StringBuilder("# Each arm form's solid in grip space G (px): form x y z r. Written by FormCoverTest.generate (env FORM_COVER_OUT); see FormCover.\n");
		for (String form : ArmProtocol.FORMS) {
			float[] c = cover(form);
			System.out.printf("cover %s: %d spheres%n", form, c.length / 4);
			for (int i = 0; i < c.length; i += 4) sb.append(String.format(java.util.Locale.ROOT, "%s %.2f %.2f %.2f %.2f%n", form, c[i], c[i + 1], c[i + 2], c[i + 3]));
		}
		java.nio.file.Files.writeString(java.nio.file.Path.of(out), sb.toString().replace("\r\n", "\n"), java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	void noDrawnPointOfAnyFormIsOutsideItsCover() {
		List<String> problems = new ArrayList<>();
		for (String form : ArmProtocol.FORMS) {
			float[] cov = FormCover.of(form);
			if (cov == null || cov.length == 0) {
				problems.add(form + " has no cover");
				continue;
			}
			int[] outside = {0}, total = {0};
			float[] worst = {0F};
			java.util.Set<Long> seen = new java.util.HashSet<>();
			for (boolean left : new boolean[] {true, false}) {
				// other times, other swings and pulls than the generator's: the cover must hold states it was not measured at
				sample(form, left, 3.3F, 23.7F, new float[] {0.125F, 0.625F}, new float[] {0.3F}, new int[] {1, 2, 3}, p -> {
					if (!seen.add(key(Math.round(p[0] / 0.4F), Math.round(p[1] / 0.4F), Math.round(p[2] / 0.4F)))) return;
					total[0]++;
					float best = 1e9F;
					for (int i = 0; i < cov.length; i += 4) {
						float dx = p[0] - cov[i], dy = p[1] - cov[i + 1], dz = p[2] - cov[i + 2];
						best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - cov[i + 3]);
					}
					if (best > 0F) {
						outside[0]++;
						worst[0] = Math.max(worst[0], best);
					}
				});
			}
			System.out.printf("cover %s: %d spheres, %d sample points, %d outside (worst %.2f px)%n", form, cov.length / 4, total[0], outside[0], worst[0]);
			if (outside[0] > 0) problems.add(form + ": " + outside[0] + " of " + total[0] + " drawn points are outside its cover, by up to " + worst[0] + " px: regenerate form_cover.txt");
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}
}
