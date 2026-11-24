package dev.morphreborn.paper.arm;

import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.ALLOW;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.CANCEL;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.CANCEL_PURGE;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.CANCEL_RESYNC;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.DENY_USE;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.BRIGHTNESS;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.PURGE;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.RETRACT_THEN_ALLOW;
import static dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision.SPECIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.morphreborn.paper.arm.rules.ArmGuardRules;
import dev.morphreborn.paper.arm.rules.ArmGuardRules.Act;
import dev.morphreborn.paper.arm.rules.ArmGuardRules.Decision;
import dev.morphreborn.paper.arm.rules.ArmGuardRules.GuardCtx;
import org.junit.jupiter.api.Test;

/** Every guard row (spec 3.4) both ways: the arm can't leave the player, and nothing else is touched. */
class ArmGuardRulesTest {
	private static final GuardCtx NONE = GuardCtx.NONE;
	private static final GuardCtx ARMED = NONE.withArmed(true);
	private static final GuardCtx ARM = NONE.withArm(true);
	private static final GuardCtx ARMED_ARM = ARMED.withArm(true);
	private static final GuardCtx ARMED_HOME = ARMED.withHome(true);

	private static void is(Decision expected, Act act, GuardCtx c) {
		assertEquals(expected, ArmGuardRules.decide(act, c), act + " " + c);
	}

	@Test
	void everyActHasARule() {
		for (Act act : Act.values()) ArmGuardRules.decide(act, NONE); // a missing case would not compile; this checks none throws
	}

	@Test
	void g1DropQAndCtrlQ() {
		is(CANCEL, Act.DROP, ARMED_ARM); // Q and Ctrl+Q fire the same event
		is(CANCEL, Act.DROP, ARM); // a stray arm doesn't leave either
		is(ALLOW, Act.DROP, ARMED); // dropping anything else while armed
		is(ALLOW, Act.DROP, NONE);
	}

	@Test
	void g2SwapHandsFiresTheSpecial() {
		is(SPECIAL, Act.SWAP_HANDS, ARMED_ARM);
		// decided 2026-11-16: with a tool out, Sneak+F is the tool's own special (sneaking set) even with the lantern lit, so the whip's
		// Whirl works; the lantern's brightness no longer shadows it
		is(SPECIAL, Act.SWAP_HANDS, ARMED_ARM.withSneaking(true).withLanternLit(true), "Sneak+F with a tool out and the lantern lit is the tool's special");
		is(SPECIAL, Act.SWAP_HANDS, ARMED_ARM.withSneaking(true).withLanternLit(true).withAccessoryOn(true));
		is(SPECIAL, Act.SWAP_HANDS, ARMED_ARM.withSneaking(true), "sneaking without a lit lantern is still the special");
		is(CANCEL, Act.SWAP_HANDS, ARMED_ARM.withRetracting(true));
		is(CANCEL_PURGE, Act.SWAP_HANDS, ARM, "a foreign arm in hand");
		is(ALLOW, Act.SWAP_HANDS, NONE);
	}

	@Test
	void g2bBrightnessWithoutAToolSession() {
		is(BRIGHTNESS, Act.SWAP_HANDS, NONE.withLanternLit(true).withSneaking(true));
		is(ALLOW, Act.SWAP_HANDS, NONE.withLanternLit(true), "plain F still swaps");
		is(ALLOW, Act.SWAP_HANDS, NONE.withSneaking(true));
		// another accessory on is not the lantern: nothing to step, so Sneak+F with no tool out still swaps hands
		is(ALLOW, Act.SWAP_HANDS, NONE.withAccessoryOn(true).withSneaking(true), "a shield, spyglass or flint has no brightness");
		is(BRIGHTNESS, Act.SWAP_HANDS, NONE.withAccessoryOn(true).withLanternLit(true).withSneaking(true));
		// an armed player holding a foreign arm or retracting is unaffected by the lantern
		is(CANCEL, Act.SWAP_HANDS, ARMED_ARM.withRetracting(true).withSneaking(true).withLanternLit(true));
	}

	private static void is(Decision expected, Act act, GuardCtx c, String why) {
		assertEquals(expected, ArmGuardRules.decide(act, c), why);
	}

	@Test
	void g3Clicks() {
		// click and shift-click on home (also another player's inventory through /invsee), or with an arm current/cursor
		is(CANCEL_RESYNC, Act.CLICK, ARMED_HOME);
		is(CANCEL_RESYNC, Act.CLICK, ARM);
		is(ALLOW, Act.CLICK, ARMED);
		is(ALLOW, Act.CLICK, NONE.withHome(true), "home means nothing without a session");
		// number key on and off home
		is(CANCEL_RESYNC, Act.NUMBER_KEY, ARMED_HOME);
		is(ALLOW, Act.NUMBER_KEY, ARMED);
		// F in an inventory (button 40) never fires a special
		is(CANCEL_RESYNC, Act.SWAP_OFFHAND, ARMED_HOME);
		is(ALLOW, Act.SWAP_OFFHAND, ARMED);
		// creative middle click on an arm, double click with an arm cursor
		is(CANCEL_RESYNC, Act.CLONE_STACK, ARM);
		is(ALLOW, Act.CLONE_STACK, ARMED);
		is(CANCEL_RESYNC, Act.COLLECT_TO_CURSOR, ARM);
		is(ALLOW, Act.COLLECT_TO_CURSOR, ARMED_HOME);
	}

	@Test
	void g4Drag() {
		is(CANCEL, Act.DRAG, ARMED_HOME);
		is(CANCEL, Act.DRAG, ARM);
		is(ALLOW, Act.DRAG, ARMED);
	}

	@Test
	void g5Creative() {
		is(CANCEL_RESYNC, Act.CREATIVE_SET, ARMED_HOME);
		is(CANCEL_RESYNC, Act.CREATIVE_SET, ARM);
		is(ALLOW, Act.CREATIVE_SET, ARMED);
		is(CANCEL_RESYNC, Act.CREATIVE_DROP, ARM);
		is(ALLOW, Act.CREATIVE_DROP, ARMED);
	}

	@Test
	void g6PickBlock() {
		is(CANCEL, Act.PICK_BLOCK, ARMED);
		is(ALLOW, Act.PICK_BLOCK, NONE);
	}

	@Test
	void g7SlotChangeRetracts() {
		is(RETRACT_THEN_ALLOW, Act.HELD_SLOT, ARMED_HOME);
		is(ALLOW, Act.HELD_SLOT, ARMED, "scrolling between other slots");
		is(ALLOW, Act.HELD_SLOT, NONE.withHome(true));
	}

	@Test
	void g8ShelfPotSign() {
		// the listener sorts shelves, pots, signs and hanging signs into this act; each is the same rule
		for (String block : new String[] {"shelf", "decorated pot", "sign", "hanging sign"}) {
			is(DENY_USE, Act.USE_STORAGE_BLOCK, ARMED_ARM, block);
			is(ALLOW, Act.USE_STORAGE_BLOCK, ARMED, block + " with another item");
		}
		is(CANCEL, Act.USE_STORAGE_BLOCK, ARMED_ARM.withRetracting(true));
	}

	@Test
	void g9FrameStandAllaySulfurCube() {
		for (String entity : new String[] {"item frame", "glow item frame", "armour stand", "allay", "sulfur cube"}) {
			is(CANCEL, Act.USE_ITEM_TAKER, ARMED_ARM, entity);
			is(ALLOW, Act.USE_ITEM_TAKER, ARMED, entity + " with another item");
		}
	}

	@Test
	void g10Holders() {
		is(CANCEL, Act.HOLDER_CHANGE, ARM);
		is(ALLOW, Act.HOLDER_CHANGE, ARMED);
	}

	@Test
	void g11ItemDamage() {
		is(CANCEL, Act.ITEM_DAMAGE, ARMED_ARM);
		is(ALLOW, Act.ITEM_DAMAGE, ARMED);
	}

	@Test
	void g12ConsumeMendBreak() {
		for (Act act : new Act[] {Act.CONSUME, Act.MEND, Act.BREAK_ITEM}) {
			is(CANCEL, act, ARMED_ARM);
			is(ALLOW, act, ARMED);
		}
	}

	@Test
	void g13Prepare() {
		is(CANCEL, Act.PREPARE, ARM);
		is(ALLOW, Act.PREPARE, ARMED);
	}

	@Test
	void g14HopperAndDispenser() {
		is(CANCEL_PURGE, Act.MOVE_ITEM, ARM);
		is(ALLOW, Act.MOVE_ITEM, NONE);
		is(CANCEL_PURGE, Act.DISPENSE, ARM);
		is(ALLOW, Act.DISPENSE, NONE);
	}

	@Test
	void g15Spawn() {
		is(CANCEL, Act.ITEM_SPAWN, ARM);
		is(ALLOW, Act.ITEM_SPAWN, NONE);
	}

	@Test
	void g16Pickup() {
		is(CANCEL_PURGE, Act.PICKUP, ARM);
		is(ALLOW, Act.PICKUP, NONE);
	}

	@Test
	void g17g18Strays() {
		is(PURGE, Act.EQUIPMENT_CHANGED, ARM);
		is(ALLOW, Act.EQUIPMENT_CHANGED, NONE);
		is(PURGE, Act.INVENTORY_OPEN, ARM);
		is(ALLOW, Act.INVENTORY_OPEN, ARMED);
	}

	@Test
	void g19ListedVersusKeptCommand() {
		is(RETRACT_THEN_ALLOW, Act.COMMAND, ARMED.withCommandRetracts(true));
		is(ALLOW, Act.COMMAND, ARMED, "a command CommandGuard keeps");
		is(ALLOW, Act.COMMAND, NONE.withCommandRetracts(true), "nothing to retract");
	}

	@Test
	void g20CrossWorldWithSafetyOnOrOff() {
		GuardCtx crossWorld = ARMED.withCrossWorld(true);
		is(RETRACT_THEN_ALLOW, Act.TELEPORT, crossWorld.withInventorySafety(true));
		is(ALLOW, Act.TELEPORT, crossWorld, "no per-world inventory plugin: the arm comes along");
		is(ALLOW, Act.TELEPORT, ARMED.withInventorySafety(true), "same world keeps the arm");
		is(RETRACT_THEN_ALLOW, Act.TELEPORT, NONE.withLanternLit(true).withCrossWorld(true).withInventorySafety(true), "a lit lantern goes out");
		is(ALLOW, Act.TELEPORT, NONE.withCrossWorld(true).withInventorySafety(true));
	}

	@Test
	void g21GameModeAndSpectator() {
		is(RETRACT_THEN_ALLOW, Act.GAME_MODE, ARMED.withToSpectator(true));
		is(RETRACT_THEN_ALLOW, Act.GAME_MODE, ARMED.withInventorySafety(true));
		is(ALLOW, Act.GAME_MODE, ARMED, "survival to creative with no inventory plugin");
		is(RETRACT_THEN_ALLOW, Act.GAME_MODE, NONE.withLanternLit(true).withToSpectator(true));
		is(ALLOW, Act.GAME_MODE, NONE.withToSpectator(true));
	}

	@Test
	void g22g23g24DeathQuitMorph() {
		for (Act act : new Act[] {Act.DEATH, Act.QUIT, Act.MORPH_START}) {
			is(RETRACT_THEN_ALLOW, act, ARMED);
			is(RETRACT_THEN_ALLOW, act, NONE.withLanternLit(true));
			is(ALLOW, act, NONE);
		}
	}

	@Test
	void g25Disable() {
		is(RETRACT_THEN_ALLOW, Act.PLUGIN_DISABLE, NONE);
	}

	@Test
	void g26RetractingCancels() {
		GuardCtx retracting = ARMED_ARM.withRetracting(true);
		for (Act act : new Act[] {Act.BLOCK_BREAK, Act.BLOCK_DAMAGE, Act.MELEE, Act.INTERACT}) {
			is(CANCEL, act, retracting);
			is(ALLOW, act, ARMED_ARM, act + " while formed");
			is(ALLOW, act, NONE);
		}
		is(ALLOW, Act.INTERACT, ARMED.withRetracting(true), "using another hand's item is fine");
	}

	@Test
	void g27SlotChange() {
		is(PURGE, Act.SLOT_CHANGE, ARM, "an arm in a slot of a player with no session");
		is(PURGE, Act.SLOT_CHANGE, ARMED_ARM, "an arm in a slot other than home");
		is(ALLOW, Act.SLOT_CHANGE, ARMED_HOME.withArm(true), "the arm in its home slot");
		is(RETRACT_THEN_ALLOW, Act.SLOT_CHANGE, ARMED_HOME, "home lost its arm without a retract");
		is(ALLOW, Act.SLOT_CHANGE, ARMED_HOME.withRetracting(true), "the retract's own write");
		is(ALLOW, Act.SLOT_CHANGE, ARMED);
	}
}
