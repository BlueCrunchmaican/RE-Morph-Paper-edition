package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.look.ShieldGrip;
import dev.morphreborn.paper.arm.rules.ShieldRules;
import dev.morphreborn.paper.arm.rules.ShieldRules.Control;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What raises the Tendril Shield (decided 2026-11-17: right-click, main hand first): how an owner's click is read, which clicks count, and the
 * registry a player without the mod is read through (their use packets) and the plugin's own main-hand uses are noted in.
 */
class ShieldRightClickTest {
	private static final int ID = 7101;

	@AfterEach
	void clean() {
		ShieldGrip.forget(ID);
		ShieldGrip.use(null);
	}

	/** A Player that knows its entity id, is online, and records updateInventory calls. */
	private static Player player(int entityId, List<String> calls) {
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
			switch (method.getName()) {
				case "getEntityId" -> {
					return entityId;
				}
				case "isOnline" -> {
					return true;
				}
				case "updateInventory" -> {
					calls.add("updateInventory");
					return null;
				}
				default -> {
					Class<?> r = method.getReturnType();
					if (r == boolean.class) return false;
					if (r == int.class) return 0;
					if (r == long.class) return 0L;
					if (r == double.class) return 0D;
					if (r == float.class) return 0F;
					return null;
				}
			}
		});
	}

	// ------------------------------------------------------------------ the control

	@Test
	void theModsPlayersUseTheKeyWhateverElseIsTrue() {
		assertEquals(Control.KEY, ShieldRules.control(true, true, false, false));
		assertEquals(Control.KEY, ShieldRules.control(true, false, false, false), "no PacketEvents needs nothing from the mod's game");
		assertEquals(Control.KEY, ShieldRules.control(true, true, false, true), "creative included: the mod draws its own shield");
	}

	@Test
	void aGameWithoutTheModIsShownTheShieldWhenItCanBe() {
		assertEquals(Control.GHOST, ShieldRules.control(false, true, false, false));
		assertEquals(Control.SNEAK, ShieldRules.control(false, false, false, false), "no PacketEvents: sneak");
		assertEquals(Control.SNEAK, ShieldRules.control(false, true, true, false), "a Bedrock player keeps sneak");
		assertEquals(Control.SNEAK, ShieldRules.control(false, true, false, true), "a creative player could pick the shown shield up, so sneak");
	}

	@Test
	void eachControlReadsItsOwnSignalOnly() {
		assertTrue(ShieldRules.wantsRaise(Control.KEY, false, true, false));
		assertFalse(ShieldRules.wantsRaise(Control.KEY, true, false, true), "sneak and the shown shield do nothing for the mod's player");
		assertTrue(ShieldRules.wantsRaise(Control.GHOST, false, false, true));
		assertFalse(ShieldRules.wantsRaise(Control.GHOST, true, true, false), "sneaking no longer raises it");
		assertTrue(ShieldRules.wantsRaise(Control.SNEAK, true, false, false));
		assertFalse(ShieldRules.wantsRaise(Control.SNEAK, false, true, true));
	}

	// ------------------------------------------------------------------ main hand first

	@Test
	void twoSignalsCloseTogetherAreOneClick() {
		assertTrue(ShieldRules.sameClick(100, 100));
		assertTrue(ShieldRules.sameClick(100, 98));
		assertTrue(ShieldRules.sameClick(98, 100), "whichever thread heard first");
		assertFalse(ShieldRules.sameClick(100, 97), "a later click is its own");
		assertFalse(ShieldRules.sameClick(-1, 0), "never is never");
		assertFalse(ShieldRules.sameClick(100, -1));
	}

	@Test
	void aClickTheMainHandUsedDoesNotRaiseIt() {
		int later = 100 + ShieldRules.GHOST_SETTLE_TICKS;
		assertTrue(ShieldRules.ghostHeld(100, -1, later), "nothing used the click: the shield's");
		assertFalse(ShieldRules.ghostHeld(100, 100, later), "flint (or a carried block, a mount) took the click");
		assertFalse(ShieldRules.ghostHeld(100, 101, later));
		assertTrue(ShieldRules.ghostHeld(200, 100, 200 + ShieldRules.GHOST_SETTLE_TICKS), "a later click is the shield's again");
		assertFalse(ShieldRules.ghostHeld(-1, -1, later), "key up");
		assertFalse(ShieldRules.ghostHeld(-1, 5, later));
	}

	@Test
	void aPressSettlesBeforeItRaisesSoTheMainHandsHandlersAreHeardFirst() {
		assertFalse(ShieldRules.ghostHeld(100, -1, 100), "the tick the use packet arrived: the main hand's handlers may not have run yet");
		assertFalse(ShieldRules.ghostHeld(100, -1, 100 + ShieldRules.GHOST_SETTLE_TICKS - 1));
		assertTrue(ShieldRules.ghostHeld(100, -1, 100 + ShieldRules.GHOST_SETTLE_TICKS));
	}

	@Test
	void aShownShieldPressDoesNotHoldForEverWithTheKeyUp() {
		// the game sends the release only from its key handling, which does not run behind a screen
		assertTrue(ShieldRules.ghostHeld(100, -1, 100 + ShieldRules.GHOST_TIMEOUT_TICKS));
		assertFalse(ShieldRules.ghostHeld(100, -1, 101 + ShieldRules.GHOST_TIMEOUT_TICKS), "a press that old is not held");
		assertTrue(ShieldRules.GHOST_TIMEOUT_TICKS >= 20 * 60, "a long hold is still a hold: at least a minute");
	}

	@Test
	void aHeldKeySignalTooSoonAfterTheLastIsANewPress() {
		assertTrue(ShieldRules.newPress(false, 0, 500), "the first one");
		assertFalse(ShieldRules.newPress(true, 100, 100 + ShieldRules.REPEAT_MIN_TICKS), "a repeat of the held key");
		assertTrue(ShieldRules.newPress(true, 100, 100 + ShieldRules.REPEAT_MIN_TICKS - 1), "the client never repeats this early: its release was lost");
		assertTrue(ShieldRules.REPEAT_MIN_TICKS < 200, "the client repeats every 200 ticks (ArmProtocol.HOLD_REPEAT_TICKS)");
	}

	@Test
	void theModsHeldKeyIsBelievedUntilReleasedOrUnrepeated() {
		assertTrue(ShieldRules.keyHeld(true, 100, 101, false));
		assertTrue(ShieldRules.keyHeld(true, 100, 100 + ShieldRules.HOLD_TIMEOUT_TICKS, false));
		assertFalse(ShieldRules.keyHeld(true, 100, 101 + 100 + ShieldRules.HOLD_TIMEOUT_TICKS, false), "a release that was lost must not hold it up for ever");
		assertFalse(ShieldRules.keyHeld(false, 100, 101, false), "released");
		assertFalse(ShieldRules.keyHeld(true, 100, 101, true), "the main hand's click");
		assertTrue(ShieldRules.HOLD_TIMEOUT_TICKS > 200, "the client repeats every 10 seconds (200 ticks), so one repeat in time is always enough");
	}

	// ------------------------------------------------------------------ the registry

	@Test
	void aShownShieldIsHeardOnlyForPlayersWhoAreShownOne() {
		List<String> calls = new ArrayList<>();
		Player p = player(ID, calls);
		ShieldGrip.press(ID);
		assertEquals(-1, ShieldGrip.pressedAt(ID), "a use packet from a player with no shown shield is nothing");
		List<Player> shown = new ArrayList<>();
		ShieldGrip.use(shown::add);
		assertTrue(ShieldGrip.available());
		assertTrue(ShieldGrip.available(p), "no client filter: every player can be shown it");
		ShieldGrip.clients(who -> false);
		assertFalse(ShieldGrip.available(p), "a game with no off hand cannot");
		ShieldGrip.clients(who -> {
			throw new IllegalStateException("no client version yet");
		});
		assertTrue(ShieldGrip.available(p), "a client that cannot be asked is shown it, as before");
		ShieldGrip.clients(null);
		ShieldGrip.clock(40);
		ShieldGrip.setGhost(p, true);
		assertEquals(List.of(p), shown, "the owner's game is sent the shield once");
		ShieldGrip.setGhost(p, true);
		assertEquals(1, shown.size(), "and not again for the same state");
		assertTrue(ShieldGrip.ghost(ID));
		ShieldGrip.press(ID);
		assertEquals(40, ShieldGrip.pressedAt(ID), "the use packet is stamped with the server's tick");
		ShieldGrip.release(ID);
		assertEquals(-1, ShieldGrip.pressedAt(ID), "the release lowers it");
		ShieldGrip.press(ID);
		ShieldGrip.setGhost(p, false);
		assertFalse(ShieldGrip.ghost(ID));
		assertEquals(-1, ShieldGrip.pressedAt(ID), "taking the shown shield away forgets the key");
		assertEquals(List.of("updateInventory"), calls, "and the real inventory (the real off-hand item) is sent back");
		ShieldGrip.setGhost(p, false);
		assertEquals(1, calls.size(), "nothing to send back when nothing was shown");
	}

	@Test
	void theMainHandsOwnUsesAreNotedOnlyForShieldOwners() {
		Player p = player(ID, new ArrayList<>());
		ShieldGrip.clock(10);
		ShieldGrip.mainHandUsed(p);
		assertEquals(-1, ShieldGrip.usedAt(ID), "a player without the shield on is not tracked");
		ShieldGrip.owner(ID, true);
		ShieldGrip.clock(12);
		ShieldGrip.mainHandUsed(p);
		assertEquals(12, ShieldGrip.usedAt(ID));
		ShieldGrip.owner(ID, false);
		assertEquals(-1, ShieldGrip.usedAt(ID), "switching the shield off forgets everything");
	}

	@Test
	void withoutThePacketViewAPlayerWithoutTheModKeepsSneak() {
		assertFalse(ShieldGrip.available());
		assertEquals(Control.SNEAK, ShieldRules.control(false, ShieldGrip.available(), false, false));
	}
}
