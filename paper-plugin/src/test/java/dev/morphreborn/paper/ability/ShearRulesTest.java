package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.ability.ShearRules.Candidate;
import dev.morphreborn.paper.ability.ShearRules.Pick;
import org.junit.jupiter.api.Test;

/** Which form shears take wool from (B1-12, owner's decision 2026-11-17: either form counts). */
class ShearRulesTest {
	private static final Candidate WOOLLY = new Candidate(true, true, false);
	private static final Candidate BALD = new Candidate(true, true, true);
	private static final Candidate LAMB = new Candidate(true, false, false);
	private static final Candidate COW = new Candidate(false, true, false);

	@Test
	void aMainSheepIsShearedFirst() {
		assertEquals(Pick.MAIN, ShearRules.pick(WOOLLY, WOOLLY));
		assertEquals(Pick.MAIN, ShearRules.pick(WOOLLY, Candidate.NONE));
	}

	@Test
	void aBorrowedSheepOnACowIsShorn() {
		assertEquals(Pick.LENT, ShearRules.pick(COW, WOOLLY));
	}

	@Test
	void aBaldMainSheepLeavesTheBorrowedOneToShear() {
		assertEquals(Pick.LENT, ShearRules.pick(BALD, WOOLLY));
	}

	@Test
	void aLambIsNotShornWhicheverFormItIs() {
		assertEquals(Pick.NONE, ShearRules.pick(LAMB, Candidate.NONE));
		assertEquals(Pick.NONE, ShearRules.pick(COW, LAMB));
		assertEquals(Pick.LENT, ShearRules.pick(LAMB, WOOLLY));
	}

	@Test
	void nothingToShearGivesNothing() {
		assertEquals(Pick.NONE, ShearRules.pick(COW, COW));
		assertEquals(Pick.NONE, ShearRules.pick(BALD, BALD));
		assertEquals(Pick.NONE, ShearRules.pick(Candidate.NONE, Candidate.NONE));
	}
}
