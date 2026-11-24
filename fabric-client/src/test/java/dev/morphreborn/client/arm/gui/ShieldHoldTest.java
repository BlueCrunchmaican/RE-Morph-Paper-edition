package dev.morphreborn.client.arm.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.gui.ShieldHold.Click;
import org.junit.jupiter.api.Test;

/**
 * The Tendril Shield's raise on right-click (decided 2026-11-17): what a click becomes given the main hand and the shield, that the off hand is
 * skipped only while the shield is on, how a held press is kept, and what the server is told. The mixin and the key need the game; these are the
 * parts that do not.
 */
class ShieldHoldTest {
	@Test
	void aClickTheMainHandDidNotUseRaisesTheShield() {
		assertEquals(Click.RAISE, ShieldHold.decide(true, true, false, false), "an empty hand on the air, a sword, a plain item: the shield's");
	}

	@Test
	void aClickTheMainHandUsedStaysTheMainHands() {
		assertEquals(Click.MAIN_HAND, ShieldHold.decide(true, true, true, false), "a bow draw, food, a block placed, a door, a villager: the item's");
	}

	@Test
	void anItemAlreadyInUseKeepsItsUseAndNothingTriedIsNothing() {
		assertEquals(Click.LEAVE, ShieldHold.decide(true, true, false, true), "a drawn bow or a meal is not interrupted by the shield");
		assertEquals(Click.LEAVE, ShieldHold.decide(true, false, false, false), "breaking a block or busy hands: the game tried nothing");
		assertEquals(Click.LEAVE, ShieldHold.decide(true, false, true, false));
	}

	@Test
	void withTheShieldOffTheGameIsExactlyVanilla() {
		for (boolean tried : new boolean[] {true, false}) {
			for (boolean used : new boolean[] {true, false}) {
				for (boolean using : new boolean[] {true, false}) {
					assertEquals(Click.LEAVE, ShieldHold.decide(false, tried, used, using));
				}
			}
		}
		assertFalse(ShieldHold.skipsHand(false, true), "the off hand works as ever with no shield");
	}

	@Test
	void theOffHandIsTheShieldsWhileItIsOn() {
		assertTrue(ShieldHold.skipsHand(true, true));
		assertFalse(ShieldHold.skipsHand(true, false), "the main hand is never skipped");
	}

	@Test
	void aRaisedShieldIsNotClickedAgainWhileTheKeyStaysDown() {
		assertTrue(ShieldHold.blocksClick(true, true));
		assertFalse(ShieldHold.blocksClick(true, false), "a new press after the release is a new click");
		assertFalse(ShieldHold.blocksClick(false, true), "the main hand is tried as often as ever when the shield is not up");
	}

	@Test
	void itStaysUpOnlyWhileTheKeyTheAccessoryAndNoScreenAllowIt() {
		assertTrue(ShieldHold.stays(true, true, true, false));
		assertFalse(ShieldHold.stays(true, false, true, false), "key let go");
		assertFalse(ShieldHold.stays(true, true, false, false), "shield off, or the server does not offer it");
		assertFalse(ShieldHold.stays(true, true, true, true), "a screen opening lowers it");
		assertFalse(ShieldHold.stays(false, true, true, false), "never armed: a held key alone raises nothing (the main hand may have the click)");
	}

	@Test
	void theServerIsToldOnceWhenItRisesAndLowersAndRepeatedWhileItStaysUp() {
		assertEquals(1, ShieldHold.sync(true, false));
		assertEquals(-1, ShieldHold.sync(false, true));
		assertEquals(0, ShieldHold.sync(true, true));
		assertEquals(0, ShieldHold.sync(false, false));
		assertEquals(0, ShieldHold.sync(true, true, ArmProtocol.HOLD_REPEAT_TICKS - 1));
		assertEquals(1, ShieldHold.sync(true, true, ArmProtocol.HOLD_REPEAT_TICKS), "the repeat that keeps it raised on the server");
		assertEquals(-1, ShieldHold.sync(false, true, ArmProtocol.HOLD_REPEAT_TICKS), "a release is never a repeat");
	}

	@Test
	void theMixinsHooksAreQuietWithNoShieldOn() {
		ShieldHold.reset();
		// with no player the accessory cannot be on: a click is tried as the game always did and nothing is armed
		assertTrue(ShieldHold.begin(true, true));
		assertFalse(ShieldHold.skip(true), "the off hand is not skipped when the shield is off");
		ShieldHold.note(false, true);
		ShieldHold.end(false);
		assertTrue(ShieldHold.begin(true, true), "so a held key never blocks the next click");
		ShieldHold.reset();
	}

	@Test
	void anArmCrossbowClickIsTheArmsWhateverTheGameAnswers() {
		// the arm crossbow loads itself, so the game's own use of an empty crossbow fails while the server fires the bolt: that click is not the shield's
		assertTrue(ShieldHold.armCrossbowClick(ArmProtocol.CROSSBOW, ArmProtocol.STATE_FORMED, true));
		assertTrue(ShieldHold.armCrossbowClick(ArmProtocol.CROSSBOW, ArmProtocol.STATE_FORMING, true));
		assertFalse(ShieldHold.armCrossbowClick(ArmProtocol.CROSSBOW, ArmProtocol.STATE_RETRACTING, true), "an arm on its way out fires nothing");
		assertFalse(ShieldHold.armCrossbowClick(ArmProtocol.CROSSBOW, ArmProtocol.STATE_NONE, true));
		assertFalse(ShieldHold.armCrossbowClick(ArmProtocol.KATANA, ArmProtocol.STATE_FORMED, true), "a katana's click is still the shield's");
		assertFalse(ShieldHold.armCrossbowClick("", ArmProtocol.STATE_NONE, true));
	}

	@Test
	void aTuckedCrossbowArmLeavesTheClickToTheShield() {
		// the session stays formed while another slot is selected, but that slot's item takes the click and the server fires no bolt
		assertFalse(ShieldHold.armCrossbowClick(ArmProtocol.CROSSBOW, ArmProtocol.STATE_FORMED, false));
	}
}
