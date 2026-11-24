package dev.morphreborn.paper.morph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.data.MorphRef;
import org.junit.jupiter.api.Test;

/** Your own body with nothing borrowed is just you (B3-01). */
class OwnBodyComboTest {
	@Test
	void ownBodyThatLostWhatItBorrowedIsDemorphed() {
		assertTrue(MorphManager.shouldDemorphWithout(MorphRef.selfBody(null)));
	}

	@Test
	void aCreatureBodyJustDropsTheBorrowedForm() {
		assertFalse(MorphManager.shouldDemorphWithout(new MorphRef("minecraft:pig", "p1", "", null)));
		assertFalse(MorphManager.shouldDemorphWithout(null));
	}
}
