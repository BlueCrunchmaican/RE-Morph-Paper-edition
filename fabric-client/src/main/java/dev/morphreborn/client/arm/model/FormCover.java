package dev.morphreborn.client.arm.model;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Each arm form's solid, as spheres in its grip space G (px), measured from the quads the form really draws in the first-person hand pass (2026-11-24):
 * every tier, swing, pull, special and time, both hands, each quad sampled finely, the samples grouped into cells of a few px and each cell wrapped in the
 * smallest sphere that holds what is in it. The first-person overlay keeps the accessories out of this union ({@link FpHeld}), so a pickaxe's head ends,
 * a bow's limbs, a sickle's blade, a trident's prongs and a whip's loop count where they are, not as a rod through the middle of the tool. The table is
 * {@code assets/morphreborn/arm/form_cover.txt}, one {@code form x y z r} per line, written by {@code FormCoverTest} (env {@code FORM_COVER_OUT}); that test also
 * checks, on samples it did not generate from, that no drawn point of any form is outside it, so a geometry change that grows a form fails there until the
 * table is regenerated.
 */
public final class FormCover {
	private FormCover() {
	}

	private static final String RESOURCE = "/assets/morphreborn/arm/form_cover.txt";
	private static final Map<String, float[]> COVERS = load();

	/** The sphere table of {@code form}: x, y, z, r for each sphere, in G px; null for a form without one. */
	public static float[] of(String form) {
		return COVERS.get(form);
	}

	private static Map<String, float[]> load() {
		Map<String, java.util.ArrayList<Float>> raw = new HashMap<>();
		try (InputStream in = FormCover.class.getResourceAsStream(RESOURCE)) {
			if (in != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = r.readLine()) != null) {
					line = line.strip();
					if (line.isEmpty() || line.startsWith("#")) continue;
					String[] f = line.split("\\s+");
					if (f.length != 5) continue;
					java.util.ArrayList<Float> list = raw.computeIfAbsent(f[0], k -> new java.util.ArrayList<>());
					for (int i = 1; i < 5; i++) list.add(Float.parseFloat(f[i]));
				}
			}
		} catch (Exception e) {
			raw.clear(); // an unreadable table: every form falls back on its measured span
		}
		Map<String, float[]> out = new HashMap<>();
		for (Map.Entry<String, java.util.ArrayList<Float>> e : raw.entrySet()) {
			float[] a = new float[e.getValue().size()];
			for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
			out.put(e.getKey(), a);
		}
		return out;
	}
}
