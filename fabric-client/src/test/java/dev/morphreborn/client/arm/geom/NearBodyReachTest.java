package dev.morphreborn.client.arm.geom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.model.NearBodyScale;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import org.junit.jupiter.api.Test;

/**
 * The reach {@link NearBodyScale} shrinks a form by is a table (the farthest vertex of any pose), not the form's rest-pose box: the rest box
 * said 12.5 px for the bow and 9 for the whip, and the drawn bow reaches 22 and the whip 31 (fix round, 2026-11-16, found when the rig's
 * wedge was replayed on the real chain). This samples every form over its tiers, swings, pulls, specials and times, in every layer, and fails
 * when a geometry change takes a form past its entry: change the number in {@code NearBodyScale}, which is the point of the drift guard.
 */
class NearBodyReachTest {
	private static final ArmLayer[] LAYERS = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};

	@Test
	void everyFormStaysInsideItsMeasuredReach() {
		for (String form : ArmProtocol.FORMS) {
			FormGeometry g = ArmGeometry.get(form);
			float far = 0F;
			String at = "";
			for (int tier = 1; tier <= 3; tier++) {
				for (float swing : new float[] {0F, 0.5F, 1F}) {
					for (float pull : new float[] {0F, 1F}) {
						for (int kind : new int[] {ArmDraw.NO_SPECIAL, ArmProtocol.FX_SWIPE, ArmProtocol.FX_VEIN, ArmProtocol.FX_WHIP, ArmProtocol.FX_BOLT,
								ArmProtocol.FX_SPEAR, ArmProtocol.FX_MACE, ArmProtocol.FX_CROSSBOW, ArmProtocol.FX_CLAWS}) {
							for (float t = 0F; t < 300F; t += 17.3F) {
								ArmBudgetTest.Draw b = ArmBudgetTest.draw(form).tier(tier).ctx(ArmDraw.CTX_TP).time(t);
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
										float len = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
										if (len > far) {
											far = len;
											at = "tier " + tier + " swing " + swing + " pull " + pull + " special " + kind + " time " + t + " " + layer;
										}
									}
								}
							}
						}
					}
				}
			}
			float table = NearBodyScale.reachPx(form, 0F);
			System.out.printf("reach %s: drawn %.1f px (%s), table %.1f%n", form, far, at, table);
			assertTrue(far <= table, form + " reaches " + far + " px (" + at + "), past NearBodyScale's " + table + ": raise its entry");
			assertTrue(far >= 0.8F * table, form + " reaches only " + far + " px against NearBodyScale's " + table + ": the entry is far too loose, lower it");
		}
	}

	@Test
	void theSheathStaysInsideItsReachFromTheHandEnd() {
		float far = 0F;
		try {
			for (int look = 0; look < ArmProtocol.LOOK_NAMES.size(); look++) {
				SheathGeometry.lookOverride = look;
				for (String form : ArmProtocol.FORMS) {
					for (int tier = 1; tier <= 3; tier++) {
						for (float swing : new float[] {0F, 0.5F, 1F}) {
							for (boolean slim : new boolean[] {false, true}) {
								for (float t = 0F; t < 300F; t += 23.3F) {
									ArmBudgetTest.Draw b = ArmBudgetTest.draw(form).tier(tier).ctx(ArmDraw.CTX_TP).time(t);
									b.swing = swing;
									b.slim = slim;
									ArmDraw d = b.build();
									for (ArmLayer layer : LAYERS) {
										ArmBudgetTest.Recorder r = new ArmBudgetTest.Recorder();
										r.keep = true;
										SheathGeometry.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0), slim, false);
										for (float[] p : r.kept) {
											float dy = p[1] - NearBodyScale.SHEATH_HAND_Y;
											far = Math.max(far, (float) Math.sqrt(p[0] * p[0] + dy * dy + p[2] * p[2]));
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
		}
		System.out.printf("reach sheath: drawn %.1f px from the hand end, table %.1f%n", far, NearBodyScale.SHEATH_REACH_PX);
		assertTrue(far <= NearBodyScale.SHEATH_REACH_PX, "the sheath reaches " + far + " px from its hand end, past " + NearBodyScale.SHEATH_REACH_PX);
		assertTrue(far >= 0.8F * NearBodyScale.SHEATH_REACH_PX, "the sheath reaches only " + far + " px: SHEATH_REACH_PX is far too loose");
	}
}
