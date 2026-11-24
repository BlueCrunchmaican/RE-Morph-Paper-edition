package dev.morphreborn.paper.disguise;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.GameMode;
import org.bukkit.entity.Pose;
import org.junit.jupiter.api.Test;

/** When the pack's lent head and parts are drawn at all (B3-02). */
class LentShownTest {
	@Test
	void aStandingPlayerShowsThem() {
		assertTrue(BorrowedHeads.lentShown(false, GameMode.SURVIVAL, Pose.STANDING, false));
		assertTrue(BorrowedHeads.lentShown(false, GameMode.CREATIVE, Pose.SNEAKING, false));
	}

	@Test
	void anInvisibleOrSpectatingPlayerShowsNothing() {
		assertFalse(BorrowedHeads.lentShown(true, GameMode.SURVIVAL, Pose.STANDING, false));
		assertFalse(BorrowedHeads.lentShown(false, GameMode.SPECTATOR, Pose.STANDING, false));
	}

	@Test
	void aBodyLyingDownOrLatchedSwimmingShowsNothing() {
		assertFalse(BorrowedHeads.lentShown(false, GameMode.SURVIVAL, Pose.SLEEPING, false));
		assertFalse(BorrowedHeads.lentShown(false, GameMode.SURVIVAL, Pose.SWIMMING, true));
	}

	@Test
	void aSwimmingPoseThatHasNotLatchedYetStillShows() {
		assertTrue(BorrowedHeads.lentShown(false, GameMode.SURVIVAL, Pose.SWIMMING, false)); // a flap at the surface (PoseLatch)
	}
}
