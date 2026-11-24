package dev.morphreborn.client.arm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import org.junit.jupiter.api.Test;

/**
 * A form in the hand of First-person Model's body, half a block from your camera (the rig, 2026-11-16: a bow at full draw filled the side of the
 * view, the whip's swing crossed the lens) is drawn smaller by how near it is. The rule's own numbers; the real chain from the body to the camera
 * (vanilla's own arm pose, the form's real vertices) is in {@code NearBodyRealChainTest}, and the reach table is checked against the geometry in
 * {@code NearBodyReachTest}.
 */
class NearBodyScaleTest {
	@Test
	void farFormsKeepTheirSize() {
		assertEquals(1F, NearBodyScale.of(1.2, 1.4), 0F, "a hand hanging at the side while you look down: unchanged");
		assertEquals(1F, NearBodyScale.of(3.0, 0.7), 0F);
		assertEquals(1F, NearBodyScale.of(NearBodyScale.NEAR + NearBodyScale.BAND, 5.0), 1e-6F, "whole by the end of the band, however long the form");
	}

	@Test
	void nearFormsNeverSubtendMoreThanTheCap() {
		for (double reach : new double[] {0.5, 0.7, 1.4, 2.0}) {
			for (double d = 0.1; d <= NearBodyScale.NEAR; d += 0.05) {
				float k = NearBodyScale.of(d, reach);
				assertTrue(k * reach <= NearBodyScale.CAP * d + 1e-6 || k <= NearBodyScale.MIN + 1e-6F, "reach " + reach + " at " + d + " scaled " + k);
			}
		}
	}

	@Test
	void theScaleNeverShrinksAsTheFormMovesAway() {
		float last = 0F;
		for (double d = 0.0; d <= 2.0; d += 0.02) {
			float k = NearBodyScale.of(d, 1.0);
			assertTrue(k >= last - 1e-6F, "bigger once further off, at " + d);
			assertTrue(k >= NearBodyScale.MIN && k <= 1F);
			last = k;
		}
	}

	@Test
	void accessoriesOnTheBodyKeepTheSameRuleWithTheirOwnReachAndCap() {
		for (String id : new String[] {ArmProtocol.SHIELD, ArmProtocol.SPYGLASS, ArmProtocol.FLINT}) {
			float reach = NearBodyScale.accessoryReachPx(id);
			assertTrue(reach > 5F && reach < 30F, id + " has its own measured reach: " + reach);
			double cap = NearBodyScale.accessoryCap(id);
			// half a block from the lens at 1/16 block a px: the free part may not subtend more than its cap
			float k = NearBodyScale.of(0.4, reach / 16.0, cap);
			assertTrue(k * reach / 16.0 <= cap * 0.4 + 1e-6, id + " is held to its cap when near");
			assertEquals(1F, NearBodyScale.of(1.2, reach / 16.0, cap), 0F, id + " keeps its size when the hand hangs a block away");
		}
		assertTrue(NearBodyScale.accessoryCap(ArmProtocol.SHIELD) > NearBodyScale.accessoryCap(ArmProtocol.SPYGLASS), "a shield may fill more of the view than a blade");
		assertTrue(NearBodyScale.of(0.4, 1.0, NearBodyScale.SHIELD_CAP) > NearBodyScale.of(0.4, 1.0), "so it is drawn larger at the same distance");
	}

	@Test
	void badNumbersChangeNothing() {
		assertEquals(1F, NearBodyScale.of(Double.NaN, 1.0), 0F);
		assertEquals(1F, NearBodyScale.of(0.5, 0.0), 0F);
		assertEquals(1F, NearBodyScale.of(-1.0, 1.0), 0F);
		assertEquals(1F, NearBodyScale.of(0.5, Double.POSITIVE_INFINITY), 0F);
	}

	@Test
	void theReachTableHasEveryFormAndNeverGoesUnderTheRestBox() {
		for (String form : ArmProtocol.FORMS) {
			float measured = NearBodyScale.reachPx(form, 0F);
			assertTrue(measured > 5F, form + " has a measured reach: " + measured);
			assertEquals(Math.max(measured, 40F), NearBodyScale.reachPx(form, 40F), 0F, form + ": a rest box bigger than the table's number wins");
		}
		assertEquals(17F, NearBodyScale.reachPx("a-new-form", 17F), 0F, "a form not in the table yet gets its rest box");
		assertEquals(5F, NearBodyScale.reachPx(null, 5F), 0F);
	}
}
