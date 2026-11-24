package dev.morphreborn.client.arm.geom;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The quad streams of the accessories as they were before the 2026-11-24 holder work, as hashes: the lantern in every view and detail
 * level, the shield's classic bands and the overlay views, the spyglass and flint in the overlay. {@link AccessoryGoldenTest} compares
 * them with the recorded file, so every look that must not change is proved not to have.
 */
final class AccessoryGolden {
	private AccessoryGolden() {
	}

	/** One draw of the grid: which accessories are on (a bit set), the form, the view, the detail. */
	static ArmDraw build(String form, int state, int ctx, int lod, boolean left, boolean slim, boolean chest, float time, int mask, boolean raised, boolean zoom,
			float age, boolean lantern) {
		float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
		int args = 0;
		for (int i = 0; i < ages.length; i++) ages[i] = age;
		if (raised) args |= 1 << (4 * ArmProtocol.accessoryIndex(ArmProtocol.SHIELD));
		if (zoom) args |= 1 << (4 * ArmProtocol.accessoryIndex(ArmProtocol.SPYGLASS));
		return new ArmDraw(form, 2, lod, ctx, left, slim, chest, time, 7, state, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F, ArmDraw.NEVER, ArmDraw.NO_SPECIAL,
				ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, lantern, lantern ? age : 0F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0, 1F, mask, args, ages,
				false);
	}

	static int bit(String id) {
		return 1 << ArmProtocol.accessoryIndex(id);
	}

	/** A hash of every vertex the renderer writes for {@code d}, all layers, plus the quad count. */
	static String hash(AccessoryRenderer r, LureRenderer lure, ArmDraw d) {
		long h = 0xcbf29ce484222325L;
		int quads = 0;
		for (ArmLayer layer : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW}) {
			ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
			rec.keep = true;
			SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
			Loft.pulseFrom(null, 0F);
			if (r != null) {
				if (d.firstPerson()) r.emitFirstPerson(d, layer, m);
				else r.emitThirdPerson(d, layer, m);
			} else {
				if (d.firstPerson()) lure.emitFirstPerson(d, layer, m);
				else lure.emitThirdPerson(d, layer, m);
			}
			quads += rec.quads();
			for (float[] v : rec.kept) {
				for (float f : v) {
					h ^= Float.floatToIntBits(f);
					h *= 0x100000001b3L;
				}
			}
			h ^= layer.ordinal();
			h *= 0x100000001b3L;
		}
		return Long.toHexString(h) + ":" + quads;
	}

	/** The whole grid: key to hash. Keys name the accessory, view, detail, form, side, slimness, armour, time and age. */
	static Map<String, String> grid() {
		Map<String, String> out = new TreeMap<>();
		String[] ids = {ArmProtocol.SHIELD, ArmProtocol.SPYGLASS, ArmProtocol.FLINT, "lantern"};
		String[] forms = {"", ArmProtocol.KATANA};
		for (String id : ids) {
			AccessoryRenderer r = id.equals("lantern") ? null : ArmGeometry.accessory(id);
			LureRenderer lure = r == null ? ArmGeometry.lure() : null;
			for (int ctx : new int[] {ArmDraw.CTX_TP, ArmDraw.CTX_FP}) {
				for (int lod = 0; lod <= 3; lod++) {
					for (String form : forms) {
						for (int variant = 0; variant < 4; variant++) {
							boolean left = (variant & 1) != 0, slimChest = (variant & 2) != 0;
							for (float age : new float[] {4F, 500F}) {
								for (int aux = 0; aux < 2; aux++) {
									float time = aux == 0 ? 100F : 237.5F;
									boolean armed = aux == 1;
									int mask = id.equals("lantern") ? 0 : bit(id);
									ArmDraw d = build(form, form.isEmpty() ? ArmProtocol.STATE_NONE : ArmProtocol.STATE_FORMED, ctx, lod, left, slimChest, slimChest, time,
											mask, armed, armed, age, id.equals("lantern"));
									String key = id + "/" + (ctx == ArmDraw.CTX_TP ? "tp" : "fp") + "/lod" + lod + "/" + (form.isEmpty() ? "bare" : form) + "/"
											+ (left ? "L" : "R") + (slimChest ? "sc" : "wn") + "/age" + (int) age + "/t" + (int) time + (armed ? "/armed" : "");
									out.put(key, hash(r, lure, d));
								}
							}
						}
					}
				}
			}
		}
		return out;
	}

	static List<String> lines(Map<String, String> grid) {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, String> e : grid.entrySet()) lines.add(e.getKey() + " " + e.getValue());
		return lines;
	}
}
