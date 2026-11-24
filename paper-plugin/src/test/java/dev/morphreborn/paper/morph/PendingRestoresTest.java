package dev.morphreborn.paper.morph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A quit inside the restore window must keep the saved morph (B3-13). */
class PendingRestoresTest {
	private final UUID id = UUID.randomUUID();

	@Test
	void aQuitBeforeTheRestoreRunsKeepsWhatWasSaved() {
		PendingRestores restores = new PendingRestores();
		restores.begin(id);
		assertTrue(restores.cancel(id), "the restore had not run: the saved morph is not to be overwritten");
	}

	@Test
	void aQuitAfterTheRestoreSavesTheLiveState() {
		PendingRestores restores = new PendingRestores();
		int ticket = restores.begin(id);
		assertTrue(restores.claim(id, ticket));
		assertFalse(restores.cancel(id));
	}

	@Test
	void anOldJoinsRestoreDoesNotFireForTheNextJoin() {
		PendingRestores restores = new PendingRestores();
		int first = restores.begin(id);
		restores.cancel(id); // quit inside the window
		int second = restores.begin(id); // and back
		assertFalse(restores.claim(id, first));
		assertTrue(restores.claim(id, second));
	}

	@Test
	void aSpentTicketIsNotClaimedTwice() {
		PendingRestores restores = new PendingRestores();
		int ticket = restores.begin(id);
		assertTrue(restores.claim(id, ticket));
		assertFalse(restores.claim(id, ticket));
	}
}
