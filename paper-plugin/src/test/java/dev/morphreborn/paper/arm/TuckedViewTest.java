package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.api.ArmView;
import dev.morphreborn.paper.arm.look.ArmEquipmentView;
import dev.morphreborn.paper.arm.rules.CrossbowBolt;
import dev.morphreborn.paper.arm.rules.JoinDeploy;
import org.junit.jupiter.api.Test;

/** What other players are shown of a tucked arm (2026-11-16), and when the join deploy waits for a morph restore. */
class TuckedViewTest {
	@Test
	void aTuckedOwnersEquipmentWithNoMainHandGetsOne() {
		// vanilla's pairing leaves out an empty slot: a viewer coming into range of a tucked owner with an empty hand gets none
		assertTrue(ArmEquipmentView.Rules.needsMainHandEntry(true, false));
		assertFalse(ArmEquipmentView.Rules.needsMainHandEntry(true, true), "an entry there is rewritten in place");
		assertFalse(ArmEquipmentView.Rules.needsMainHandEntry(false, false), "an arm in hand is never added to a packet that omits it");
		assertFalse(ArmEquipmentView.Rules.needsMainHandEntry(false, true));
	}

	@Test
	void theConvenienceConstructorsCarryNoBar() {
		ArmView plain = new ArmView("katana", 2, ArmProtocol.STATE_FORMED, 10, 0);
		assertFalse(plain.tucked());
		assertEquals(0, plain.maxDamage());
		assertEquals(0, plain.damage());
		ArmView tucked = new ArmView("katana", 2, ArmProtocol.STATE_FORMED, 10, 0, true);
		assertTrue(tucked.tucked());
		assertEquals(0, tucked.maxDamage());
		ArmView withBar = new ArmView("katana", 2, ArmProtocol.STATE_FORMED, 10, 0, true, 100, 35);
		assertEquals(100, withBar.maxDamage());
		assertEquals(35, withBar.damage());
		assertFalse(withBar.charged(), "no bolt unless the session says so");
		ArmView loaded = new ArmView("crossbow", 2, ArmProtocol.STATE_FORMED, 10, 0, true, 100, 35, true);
		assertTrue(loaded.charged(), "a tucked loaded crossbow's stub is shown charged");
	}

	private static ArmSession session(String form) {
		return new ArmSession(java.util.UUID.randomUUID(), form, 2, 0, 1L, new byte[0], 0L, false);
	}

	@Test
	void aTuckedCrossbowsStandInFollowsTheBoltWhileTheScreenIsOpen() {
		// rig bug B1 (2026-11-16): shot, then the inventory opens: the stub is tucked empty, and must fill when the bolt has regrown
		ArmSession s = session("crossbow");
		s.tucked = true;
		s.tuckCharged = false;
		CrossbowBolt bolt = new CrossbowBolt(7);
		bolt.fired(100, 25);
		bolt.advance(110);
		assertFalse(bolt.loaded());
		assertFalse(s.refreshTuckCharged(bolt.loaded()), "still growing: the stand-in stays empty and nothing is sent");
		assertFalse(s.view().charged());
		bolt.advance(125);
		assertTrue(bolt.loaded(), "the regrow ran on while tucked");
		assertTrue(s.refreshTuckCharged(bolt.loaded()), "whole: the stand-in changes, so the view and the hand are sent");
		assertTrue(s.view().charged());
		assertTrue(s.view().tucked());
		assertFalse(s.refreshTuckCharged(true), "already shown: nothing is sent again");
	}

	@Test
	void aTuckedCrossbowsStandInEmptiesToo() {
		ArmSession s = session("crossbow");
		s.tucked = true;
		s.tuckCharged = true;
		assertTrue(s.refreshTuckCharged(false), "a snapshot that was stale (the bolt is gone) is put right");
		assertFalse(s.view().charged());
	}

	@Test
	void theStandInIsStaleWhenItDisagreesWithTheBolt() {
		// the decision CrossbowArm.tickTucked makes each tick: it reads the bolt's state against what the view shows
		CrossbowBolt bolt = new CrossbowBolt(3);
		assertFalse(bolt.standInStale(true), "whole bolt, shown charged");
		assertTrue(bolt.standInStale(false), "whole bolt, shown empty: the snapshot is old");
		bolt.fired(0, 25);
		assertTrue(bolt.standInStale(true), "growing bolt, shown charged");
		assertFalse(bolt.standInStale(false), "growing bolt, shown empty");
	}

	@Test
	void aBurstWhoseEndWasMissedWhileTuckedRegrowsAsLongAsInHand() {
		CrossbowBolt quick = new CrossbowBolt(4);
		assertTrue(quick.beginBurst(10), "Quick Charge III: 10 ticks");
		assertTrue(quick.recoverOrphanedBurst(200, 25), "marked as running with nothing running it: it ends");
		assertEquals(10, quick.totalTicks(), "the burst's own length, not the base one");
		quick.advance(209);
		assertFalse(quick.loaded());
		quick.advance(210);
		assertTrue(quick.loaded());
		assertFalse(quick.recoverOrphanedBurst(300, 25), "nothing to recover once it is not bursting");

		CrossbowBolt unknown = new CrossbowBolt(5);
		assertTrue(unknown.beginBurst());
		assertTrue(unknown.recoverOrphanedBurst(0, 25));
		assertEquals(25, unknown.totalTicks(), "no length kept: the fallback");
	}

	@Test
	void onlyATuckedCrossbowKeepsAChargedStandIn() {
		ArmSession open = session("crossbow");
		assertFalse(open.refreshTuckCharged(true), "not tucked: there is a real item, and the stand-in is not shown");
		assertFalse(open.view().charged());
		ArmSession katana = session("katana");
		katana.tucked = true;
		assertFalse(katana.refreshTuckCharged(true), "no other form has a bolt");
		assertFalse(katana.view().charged());
	}

	@Test
	void theJoinDeployWaitsForAMorphThatIsAboutToBeRestored() {
		assertEquals(0, JoinDeploy.extraWaitTicks(false, 5), "nothing to restore: deploy when it was due");
		assertTrue(JoinDeploy.extraWaitTicks(true, 5) > 5 - 1, "the restore is 5 ticks after the join and the deploy is due at 1: it waits past it");
		assertEquals(6, JoinDeploy.extraWaitTicks(true, 5));
	}
}
