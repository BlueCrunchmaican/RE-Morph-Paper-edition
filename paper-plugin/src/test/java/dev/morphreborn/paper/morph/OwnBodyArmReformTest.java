package dev.morphreborn.paper.morph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.data.MorphRef;
import org.junit.jupiter.api.Test;

/** A bound arm forms again when the player is back in the plain own body (2026-11-17, B2-04). */
class OwnBodyArmReformTest {
	@Test
	void aTransformationBackToTheSelfFormReformsTheArm() {
		// every demorph ends on MorphRef.self (morphTo never leaves a null next); null is only the defensive "nothing" case
		assertTrue(MorphManager.backInOwnBody(null));
		assertTrue(MorphManager.backInOwnBody(MorphRef.self(null)));
	}

	@Test
	void aCreatureOrAnOwnBodyCombinationDoesNot() {
		assertFalse(MorphManager.backInOwnBody(new MorphRef("minecraft:bat", "b1", "", null)));
		// own body with something borrowed is still a combination: arms stay off (the borrow ending demorphs it, and that ends on
		// the self form, which is the first test's case)
		assertFalse(MorphManager.backInOwnBody(MorphRef.selfBody(null)));
	}
}
