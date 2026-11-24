package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.model.FpHeld;
import dev.morphreborn.client.arm.model.NearBodyScale;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The volumes the first-person overlay keeps the accessories clear of are measured, not guessed (2026-11-24): each arm form's span along its tool and
 * its thickness across it as the hand pass draws it (grip space G, first person, every tier, swing, pull, special and time), against the table in
 * {@link FpHeld#FORM_SPAN}; and the accessories' own reach from their pivots on the body, against {@link NearBodyScale}'s entries. A geometry
 * change that takes a form or an accessory past its entry fails here, and the entry is changed: the drift guard {@code NearBodyReachTest} is for the
 * form's reach.
 */
class FormExtentsTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	@AfterEach
	void reset() {
		ShieldMotion.clear();
	}

	@Test
	void everyFormStaysInsideItsMeasuredFirstPersonSpan() {
		java.util.List<String> problems = new java.util.ArrayList<>();
		for (String form : ArmProtocol.FORMS) {
			FormGeometry g = ArmGeometry.get(form);
			float yMin = 0F, yMax = 0F, across = 0F;
			for (int tier = 1; tier <= 3; tier++) {
				for (float swing : new float[] {0F, 0.5F, 1F}) {
					for (float pull : new float[] {0F, 1F}) {
						for (int kind : new int[] {ArmDraw.NO_SPECIAL, ArmProtocol.FX_SWIPE, ArmProtocol.FX_VEIN, ArmProtocol.FX_WHIP, ArmProtocol.FX_BOLT, ArmProtocol.FX_SPEAR,
								ArmProtocol.FX_MACE, ArmProtocol.FX_CROSSBOW, ArmProtocol.FX_CLAWS}) {
							for (float t = 0F; t < 300F; t += 17.3F) {
								ArmBudgetTest.Draw b = ArmBudgetTest.draw(form).tier(tier).ctx(ArmDraw.CTX_FP).time(t);
								b.swing = swing;
								b.bowPull = pull;
								b.specialKind = kind;
								b.specialAge = kind == ArmDraw.NO_SPECIAL ? ArmDraw.NEVER : 4F;
								ArmDraw d = b.build();
								for (ArmLayer layer : LAYERS) {
									ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
									r.keep = true;
									g.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
									for (float[] p : r.kept) {
										yMin = Math.min(yMin, p[1]);
										yMax = Math.max(yMax, p[1]);
										across = Math.max(across, (float) Math.sqrt(p[0] * p[0] + p[2] * p[2]));
									}
								}
							}
						}
					}
				}
			}
			float[] table = FpHeld.FORM_SPAN.get(form);
			System.out.printf("span %s: y %.1f .. %.1f, across %.1f; table %s%n", form, yMin, yMax, across, table == null ? "none" : java.util.Arrays.toString(table));
			if (table == null) {
				problems.add(form + " has no first-person span entry");
				continue;
			}
			if (yMin < table[0] - 0.01F || yMax > table[1] + 0.01F) problems.add(form + " spans y " + yMin + " .. " + yMax + " past the table's " + table[0] + " .. " + table[1]);
			if (across > table[2] + 0.01F) problems.add(form + " is " + across + " px across, past the table's " + table[2]);
			if (yMax - yMin < 0.7F * (table[1] - table[0]) && table[1] - table[0] >= 4F) problems.add(form + "'s entry is far too loose: measured " + (yMax - yMin) + ", table " + (table[1] - table[0]));
		}
		assertTrue(problems.isEmpty(), String.join("\n", problems));
	}

	@Test
	void theAccessoriesStayInsideTheirReachFromTheirPivotsOnTheBody() {
		for (String id : new String[] {ArmProtocol.SHIELD, ArmProtocol.SPYGLASS, ArmProtocol.FLINT}) {
			AccessoryRenderer r = ArmGeometry.accessory(id);
			float far = 0F;
			float pivotY = NearBodyScale.accessoryPivotY(id);
			for (boolean left : new boolean[] {false, true}) {
				for (boolean slim : new boolean[] {false, true}) {
					for (float time : new float[] {100F, 133F, 177F, 250F}) {
						for (int arg = 0; arg < 2; arg++) {
							float[] ages = new float[ArmProtocol.ACCESSORIES.size()];
							for (int i = 0; i < ages.length; i++) ages[i] = 500F;
							int mask = 1 << ArmProtocol.accessoryIndex(id);
							ArmDraw d = new ArmDraw("", 2, 0, ArmDraw.CTX_TP, left, slim, false, time, 7, ArmProtocol.STATE_NONE, 1F, "", 0F, 0F, false, 1F, ArmDraw.FULL, 0F,
									ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, false, 0F, 0F, true, true, false, null, 0F, -1F, 0F, null, 0xF000F0,
									1F, mask, arg == 0 ? 0 : 1 << (4 * ArmProtocol.accessoryIndex(id)), ages, false);
							// the shield's arm is the other one: its pivot's x is that arm's centre
							float xc = (slim ? 0.5F : 1F) * (id.equals(ArmProtocol.SHIELD) ? (left ? -1F : 1F) : (left ? 1F : -1F));
							Vector3f pivot = new Vector3f(xc, pivotY, 0F);
							for (ArmLayer layer : LAYERS) {
								ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
								rec.keep = true;
								SkinMesh m = SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0);
								com.mojang.blaze3d.vertex.PoseStack ignored = null;
								r.emitThirdPerson(d, layer, m);
								for (float[] p : rec.kept) far = Math.max(far, pivot.distance(p[0], p[1], p[2]));
							}
						}
					}
				}
			}
			float table = NearBodyScale.accessoryReachPx(id);
			System.out.printf("accessory reach %s: drawn %.1f px from its pivot, table %.1f%n", id, far, table);
			assertTrue(far <= table, id + " reaches " + far + " px from its pivot, past NearBodyScale's " + table + ": raise its entry");
		}
	}

	/**
	 * The lantern's free part on the body (2026-11-24), from the pivot {@link NearBodyScale#LANTERN_PIVOT_Y}: every arm look, side, slimness, chestplate, level,
	 * age and a flare, with a tool formed (the stalk rooted on the tendril arm, the hand or under the armour's top plate) and without (the sleeve's root).
	 * The chain here hangs along the arm; in the world it swings, so its worst case is added by arithmetic: the hook's distance from the pivot plus the five
	 * links plus the cage's burst.
	 */
	@Test
	void theLanternStaysInsideItsReachFromItsPivotOnTheBody() {
		LureGeometry lure = new LureGeometry();
		float far = 0F;
		float pivotY = NearBodyScale.LANTERN_PIVOT_Y;
		try {
			for (int look : new int[] {ArmProtocol.LOOK_ARM, ArmProtocol.LOOK_HAND, ArmProtocol.LOOK_ARMOUR}) {
				SheathGeometry.lookOverride = look;
				for (String form : new String[] {"", ArmProtocol.KATANA}) {
					for (boolean left : new boolean[] {false, true}) {
						for (boolean slim : new boolean[] {false, true}) {
							for (boolean chest : new boolean[] {false, true}) {
								for (float age : new float[] {12F, 30F, 500F}) {
									for (float time : new float[] {100F, 133F, 250F}) {
										for (boolean flare : new boolean[] {false, true}) {
											LureGeometry.clear();
											if (flare) LureGeometry.flared(7, 0L);
											ArmDraw d = AccessoryGolden.build(form, form.isEmpty() ? ArmProtocol.STATE_NONE : ArmProtocol.STATE_FORMED, ArmDraw.CTX_TP, 0, left, slim, chest,
													flare ? 7.5F : time, 0, false, false, age, true);
											float xc = (slim ? 0.5F : 1F) * (left ? 1F : -1F);
											Vector3f pivot = new Vector3f(xc, pivotY, 0F);
											for (ArmLayer layer : LAYERS) {
												ArmBudgetTest.Recorder rec = new ArmBudgetTest.Recorder();
												rec.keep = true;
												lure.emitThirdPerson(d, layer, SkinMesh.begin(new PoseStack().last(), rec, 0xF000F0, 0));
												for (float[] p : rec.kept) far = Math.max(far, pivot.distance(p[0], p[1], p[2]));
											}
										}
									}
								}
							}
						}
					}
				}
			}
		} finally {
			SheathGeometry.lookOverride = -1;
			LureGeometry.clear();
		}
		// the world's chain: the hook (xc, 1.5, -11) from the pivot, then five links (the ball's cap) and the cage (a burst's faded ribs reach about 5 px past the cap)
		float swung = (float) Math.sqrt(2.9 * 2.9 + 11 * 11) + (LureGeometry.NODES - 1) + 5F;
		float table = NearBodyScale.LANTERN_REACH_PX;
		System.out.printf("lantern reach: drawn %.1f px from its pivot, swung worst case %.1f, table %.1f%n", far, swung, table);
		assertTrue(far <= table, "the lantern reaches " + far + " px from its pivot, past NearBodyScale's " + table + ": raise its entry");
		assertTrue(swung <= table, "a swung chain can reach " + swung + " px, past NearBodyScale's " + table);
	}
}
