package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.SemVer;
import dev.morphreborn.paper.arm.rules.ArmItemSpecs;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.WhipMoves;
import dev.morphreborn.paper.arm.rules.WhipMoves.HoldKind;
import dev.morphreborn.paper.arm.rules.WhipMoves.Move;
import dev.morphreborn.paper.arm.rules.WhipSettings;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The whip's F moves (2026-11-16), pure: which move F is, the disarm's drop rule, the cone, the pushes, the cooldowns. */
class WhipMovesTest {
	private static final double EPS = 1e-9;

	// ------------------------------------------------------------------ which move F is

	@Test
	void sneakingIsAlwaysTheWhirl() {
		for (boolean holdable : new boolean[] {false, true}) {
			for (boolean snared : new boolean[] {false, true}) assertEquals(Move.WHIRL, WhipMoves.pick(true, holdable, snared), holdable + " " + snared);
		}
	}

	@Test
	void aimingPicksTheMove() {
		assertEquals(Move.CRACK, WhipMoves.pick(false, false, false), "nothing in reach: the Crack");
		assertEquals(Move.SNARE, WhipMoves.pick(false, true, false), "a creature: the Snare");
		assertEquals(Move.DISARM, WhipMoves.pick(false, true, true), "a creature you snared: the Disarm");
		assertEquals(Move.CRACK, WhipMoves.pick(false, false, true), "a snared thing the lash can no longer hold is nothing: the Crack");
	}

	@Test
	void everyMoveHasItsOwnCostRowAndName() {
		Set<String> keys = new HashSet<>();
		for (Move m : Move.values()) {
			assertTrue(m.costKey().startsWith("whip-"), m.costKey());
			assertTrue(keys.add(m.costKey()), "no two moves share a row");
			assertFalse(m.label().isBlank());
		}
		assertEquals(List.of("whip-snare", "whip-disarm", "whip-crack", "whip-whirl"), List.of(Move.SNARE.costKey(), Move.DISARM.costKey(), Move.CRACK.costKey(), Move.WHIRL.costKey()));
	}

	// ------------------------------------------------------------------ reach by tier

	@Test
	void theLeftClickReachesSixToSevenBlocks() {
		assertEquals(6.0, ArmTiers.whipStrikeReach(1), EPS);
		assertEquals(6.5, ArmTiers.whipStrikeReach(2), EPS);
		assertEquals(7.0, ArmTiers.whipStrikeReach(3), EPS);
		assertEquals(7.0, ArmTiers.whipStrikeReach(9), EPS, "a tier past netherite reads as netherite");
		assertEquals(3.0, ArmTiers.extraReach("whip", 1), EPS, "the item adds 3 / 3.5 / 4 to the player's own 3");
		assertEquals(4.0, ArmTiers.extraReach("whip", 3), EPS);
	}

	@Test
	void theSpecialsOwnReachGrowsWithTheLevel() {
		WhipSettings.Moves m = WhipSettings.DEFAULTS.moves();
		assertEquals(List.of(8.0, 10.0, 12.0), List.of(m.reach().at(1), m.reach().at(2), m.reach().at(3)));
		assertTrue(m.reach().at(1) > ArmTiers.whipStrikeReach(3), "F finds a creature the left-click could not hit");
	}

	// ------------------------------------------------------------------ the disarm's drop rule

	@Test
	void aGuaranteedItemAlwaysDrops() {
		for (double roll : new double[] {0, 0.5, 0.999}) {
			assertTrue(WhipMoves.disarmDrops(1.0, roll), "chance 1, roll " + roll);
			assertTrue(WhipMoves.disarmDrops(2.0, roll), "a picked-up item (chance 2), roll " + roll);
		}
	}

	@Test
	void anItemThatCouldNeverDropNeverDoes() {
		for (double roll : new double[] {0, 0.001, 0.999}) {
			assertFalse(WhipMoves.disarmDrops(0.0, roll), "chance 0, roll " + roll);
			assertFalse(WhipMoves.disarmDrops(-1.0, roll));
			assertFalse(WhipMoves.disarmDrops(Double.NaN, roll), "an unreadable chance is no chance");
		}
	}

	@Test
	void aChanceIsRolledAgainst() {
		// a mob's usual 8.5% for what it spawned holding
		assertTrue(WhipMoves.disarmDrops(0.085, 0.0));
		assertTrue(WhipMoves.disarmDrops(0.085, 0.0849));
		assertFalse(WhipMoves.disarmDrops(0.085, 0.085), "a roll at the chance misses");
		assertFalse(WhipMoves.disarmDrops(0.085, 0.9));
	}

	@Test
	void theDropRuleNeverGivesMoreThanTheChance() {
		// anti-farm: over many rolls, the drops are what the chance says, never more
		int drops = 0, rolls = 10_000;
		for (int i = 0; i < rolls; i++) if (WhipMoves.disarmDrops(0.085, (double) i / rolls)) drops++;
		assertEquals(850, drops);
	}

	// ------------------------------------------------------------------ the cone and the circle

	@Test
	void theConeCatchesWhatIsAheadWithinRange() {
		// looking along +x, 6 blocks, 35 degrees either side
		assertTrue(WhipMoves.inCone(3, 0, 0, 1, 0, 0, 6, 35, 0));
		assertTrue(WhipMoves.inCone(6, 0, 0, 1, 0, 0, 6, 35, 0), "right at the end");
		assertFalse(WhipMoves.inCone(6.1, 0, 0, 1, 0, 0, 6, 35, 0), "just past it");
		assertFalse(WhipMoves.inCone(-3, 0, 0, 1, 0, 0, 6, 35, 0), "behind you");
		assertFalse(WhipMoves.inCone(0, 0, 3, 1, 0, 0, 6, 35, 0), "to the side");
	}

	@Test
	void theConeIsThirtyFiveDegreesEitherSide() {
		double d = 4;
		for (double deg : new double[] {0, 10, 34.9}) {
			double rad = Math.toRadians(deg);
			assertTrue(WhipMoves.inCone(d * Math.cos(rad), 0, d * Math.sin(rad), 1, 0, 0, 6, 35, 0), deg + " degrees right");
			assertTrue(WhipMoves.inCone(d * Math.cos(rad), 0, -d * Math.sin(rad), 1, 0, 0, 6, 35, 0), deg + " degrees left");
			assertTrue(WhipMoves.inCone(d * Math.cos(rad), d * Math.sin(rad), 0, 1, 0, 0, 6, 35, 0), deg + " degrees up");
		}
		double rad = Math.toRadians(36);
		assertFalse(WhipMoves.inCone(d * Math.cos(rad), 0, d * Math.sin(rad), 1, 0, 0, 6, 35, 0), "36 degrees is outside");
		assertFalse(WhipMoves.inCone(d * Math.cos(rad), d * Math.sin(rad), 0, 1, 0, 0, 6, 35, 0));
	}

	@Test
	void theConeFollowsTheLookInAnyDirection() {
		// straight down, and diagonal, with a look that is not a unit vector
		assertTrue(WhipMoves.inCone(0, -4, 0.5, 0, -7, 0, 6, 35, 0));
		assertFalse(WhipMoves.inCone(0, 4, 0, 0, -7, 0, 6, 35, 0));
		assertTrue(WhipMoves.inCone(3, 3, 0, 5, 5, 0, 6, 35, 0));
		assertFalse(WhipMoves.inCone(3, 3, 0, 1, 0, 0, 6, 35, 0), "45 degrees off a flat look");
	}

	@Test
	void aWideCreatureIsCaughtByTheEdgeAndOneAtYourEyeAlways() {
		// 40 degrees off at 4 blocks: outside for a point, inside for a creature 1.3 blocks across each way
		double rad = Math.toRadians(40);
		double x = 4 * Math.cos(rad), z = 4 * Math.sin(rad);
		assertFalse(WhipMoves.inCone(x, 0, z, 1, 0, 0, 6, 35, 0));
		assertTrue(WhipMoves.inCone(x, 0, z, 1, 0, 0, 6, 35, 0.65));
		assertTrue(WhipMoves.inCone(0.1, 0, -0.2, 1, 0, 0, 6, 35, 0.3), "right on you");
		assertTrue(WhipMoves.inCone(6.4, 0, 0, 1, 0, 0, 6, 35, 0.5), "its width counts at the far end too");
		assertFalse(WhipMoves.inCone(7, 0, 0, 1, 0, 0, 6, 35, 0.5));
	}

	@Test
	void aZeroLookCatchesNothing() {
		assertFalse(WhipMoves.inCone(1, 0, 0, 0, 0, 0, 6, 35, 0));
	}

	@Test
	void theWhirlIsACircleRoundYou() {
		assertTrue(WhipMoves.inRadius(3, 0, 0, 4, 0));
		assertTrue(WhipMoves.inRadius(0, -2, 3, 4, 0), "3D: 3.6 away");
		assertFalse(WhipMoves.inRadius(3, 3, 0, 4, 0), "4.2 away");
		assertTrue(WhipMoves.inRadius(3, 3, 0, 4, 0.4), "a wide creature at the edge");
		assertTrue(WhipMoves.inRadius(0, 0, 0, 4, 0), "right on you");
		assertFalse(WhipMoves.inRadius(5, 0, 0, 4, 0));
	}

	// ------------------------------------------------------------------ pushes and flung items

	@Test
	void aPushGoesAwayFromThePointAtItsStrength() {
		double[] v = WhipMoves.push(3, 4, 1.0, 0);
		assertEquals(0.6, v[0], EPS);
		assertEquals(0.8, v[2], EPS);
		assertEquals(WhipMoves.PUSH_LIFT, v[1], EPS);
		assertEquals(1.0, Math.hypot(v[0], v[2]), EPS);
	}

	@Test
	void knockbackResistanceSoftensAPushAndFullResistanceStopsIt() {
		double[] half = WhipMoves.push(1, 0, 1.0, 0.5);
		assertEquals(0.5, half[0], EPS);
		double[] none = WhipMoves.push(1, 0, 1.0, 1.0);
		assertArrayEquals(new double[] {0, 0, 0}, none, EPS, "unmoved: not even lifted");
		assertEquals(0.0, WhipMoves.push(1, 0, 1.0, 7)[0], EPS, "a resistance past 1 is 1");
		assertEquals(1.0, WhipMoves.push(1, 0, 1.0, -3)[0], EPS, "and below 0 is 0");
	}

	@Test
	void aPushIsNeverFasterThanTheCapOrBackwards() {
		assertEquals(WhipMoves.MAX_PUSH, Math.hypot(WhipMoves.push(1, 0, 50, 0)[0], 0), EPS);
		assertArrayEquals(new double[] {0, 0, 0}, WhipMoves.push(1, 0, -2, 0), EPS, "a negative strength pushes nothing");
		double[] onIt = WhipMoves.push(0, 0, 1.0, 0);
		assertEquals(0, onIt[0], EPS);
		assertEquals(0, onIt[2], EPS);
		assertEquals(WhipMoves.PUSH_LIFT, onIt[1], EPS, "a creature right on the point goes straight up");
	}

	@Test
	void aFlungItemFliesTowardItsOwner() {
		double[] v = WhipMoves.fling(4, 0, 3);
		assertEquals(0.44, v[0], EPS);
		assertEquals(WhipMoves.FLING_LIFT, v[1], EPS);
		assertEquals(0.33, v[2], EPS);
		double[] up = WhipMoves.fling(0, 2, 0);
		assertEquals(WhipMoves.FLING_SPEED + WhipMoves.FLING_LIFT, up[1], EPS);
		assertArrayEquals(new double[] {0, WhipMoves.FLING_LIFT, 0}, WhipMoves.fling(0, 0, 0), EPS, "no gap: it just rises");
	}

	// ------------------------------------------------------------------ cooldowns

	@Test
	void eachMoveHasItsOwnCooldown() {
		WhipMoves.Cooldowns c = new WhipMoves.Cooldowns();
		assertEquals(0, c.left(Move.SNARE, 100));
		c.start(Move.SNARE, 100, 120);
		assertEquals(120, c.left(Move.SNARE, 100));
		assertEquals(20, c.left(Move.SNARE, 200));
		assertEquals(0, c.left(Move.SNARE, 220), "ready exactly when it is up");
		assertEquals(0, c.left(Move.SNARE, 5000));
		// the others were never touched
		assertEquals(0, c.left(Move.DISARM, 100));
		assertEquals(0, c.left(Move.CRACK, 100));
		assertEquals(0, c.left(Move.WHIRL, 100));
		c.start(Move.WHIRL, 150, 200);
		assertEquals(70, c.left(Move.SNARE, 150));
		assertEquals(200, c.left(Move.WHIRL, 150));
		assertEquals(200, c.longestLeft(150));
		assertEquals(0, c.longestLeft(10_000));
	}

	@Test
	void aCooldownRecordIsExpiredOnlyWhenEveryMoveIsReady() {
		WhipMoves.Cooldowns c = new WhipMoves.Cooldowns();
		assertTrue(c.expired(0), "a fresh record has nothing to remember");
		c.start(Move.WHIRL, 100, 200);
		assertFalse(c.expired(100));
		assertFalse(c.expired(299), "a running cooldown is never lost (a relog must not dodge it)");
		assertTrue(c.expired(300), "ready exactly when it is up");
		c.start(Move.SNARE, 300, 50);
		assertFalse(c.expired(320), "one move running keeps the whole record");
		assertTrue(c.expired(350));
	}

	@Test
	void aMoveThatFoundNothingStartsNoCooldown() {
		// the module starts a cooldown only when it pays: nothing started means ready now
		WhipMoves.Cooldowns c = new WhipMoves.Cooldowns();
		assertEquals(0, c.left(Move.CRACK, 40));
		c.start(Move.CRACK, 40, -5);
		assertEquals(0, c.left(Move.CRACK, 40), "a negative cooldown is none");
	}

	@Test
	void theDefaultCooldownsAndCostsGrowKinderWithTheLevel() {
		WhipSettings.Moves m = WhipSettings.DEFAULTS.moves();
		for (Move move : Move.values()) {
			assertTrue(m.cooldownTicks(move, 1) > m.cooldownTicks(move, 2) && m.cooldownTicks(move, 2) > m.cooldownTicks(move, 3), move + " cools faster each level");
		}
		assertTrue(m.sharedCooldownTicks() < m.cooldownTicks(Move.DISARM, 3), "the shared pause is the shortest wait");
		assertNotEquals(m.cooldownTicks(Move.SNARE, 1), m.cooldownTicks(Move.WHIRL, 1), "the moves' cooldowns are their own");
	}

	// ------------------------------------------------------------------ the contract with the client

	@Test
	void theProtocolConstantsAreTheContract() {
		assertEquals(13, ArmProtocol.FX_WHIP, "the old lash keeps its number");
		assertEquals(14, ArmProtocol.FX_WHIP_MOVE);
		assertEquals(21, ArmProtocol.FX_KINDS, "the new forms' kinds 15 to 20 (8.0.0-beta.2)");
		assertEquals("whip_move", ArmProtocol.fxName(ArmProtocol.FX_WHIP_MOVE));
		assertEquals("7.6.0-beta.4", ArmProtocol.WHIP_MOVES_SINCE);
		assertEquals(List.of(1, 2, 3, 4, 5), List.of(ArmProtocol.WHIP_STRIKE, ArmProtocol.WHIP_SNARE, ArmProtocol.WHIP_DISARM, ArmProtocol.WHIP_CRACK, ArmProtocol.WHIP_WHIRL));
		assertFalse(ArmProtocol.statefulFx(ArmProtocol.FX_WHIP_MOVE), "a move is a one-off, not a state the client has to time out");
		assertEquals(ArmProtocol.FX_RADIUS, ArmProtocol.fxRadius(ArmProtocol.FX_WHIP_MOVE));
	}

	@Test
	void onlyClientsFromTheWhipMovesVersionGetThem() {
		assertTrue(SemVer.compare("7.6.0-beta.4", ArmProtocol.WHIP_MOVES_SINCE) >= 0);
		assertTrue(SemVer.compare("7.6.0", ArmProtocol.WHIP_MOVES_SINCE) >= 0);
		assertTrue(SemVer.compare("7.6.0-beta.3", ArmProtocol.WHIP_MOVES_SINCE) < 0, "a client with arm slots but no whip moves still gets particles");
		assertTrue(SemVer.compare("7.6.0-beta.2", ArmProtocol.WHIP_MOVES_SINCE) < 0);
		assertTrue(SemVer.compare(ArmProtocol.SLOTS_SINCE, ArmProtocol.WHIP_MOVES_SINCE) < 0);
	}

	// ------------------------------------------------------------------ words

	@Test
	void theItemsFLineNamesEachMoveWithItsCost() {
		assertEquals("Snare 6 · Disarm 4 · Crack 6 · Whirl (sneak) 10 biomass", WhipMoves.loreLine(6_000, 4_000, 6_000, 10_000, true));
		assertEquals("Snare 6.5 · Disarm 0.25 · Crack 6 · Whirl (sneak) 10 biomass", WhipMoves.loreLine(6_500, 250, 6_000, 10_000, true));
		assertEquals("Snare · Disarm · Crack · Whirl (sneak)", WhipMoves.loreLine(6_000, 4_000, 6_000, 10_000, false), "Classic: no bar, no costs");
		assertEquals("Snare · Disarm · Crack · Whirl (sneak)", WhipMoves.loreLine(0, 0, 0, 0, true), "arm costs off: free");
		// and it sits in the lore as the special's own line
		assertEquals(List.of("Your own arm, reshaped.", "F: Snare · Disarm · Crack · Whirl (sneak)"),
				ArmItemSpecs.of("whip", 1, false, 0, 0, 0, WhipMoves.loreLine(0, 0, 0, 0, false)).lore());
	}

	@Test
	void theUpgradeTreeNamesTheMoves() {
		assertEquals("Hits 6 blocks · Snare 2 s · Disarm · Crack 6 blocks · Whirl 4 blocks", WhipMoves.describe(6, 2, 6, 4));
		assertEquals("Hits 6.5 blocks · Snare 2.5 s · Disarm · Crack 7 blocks · Whirl 4.5 blocks", WhipMoves.describe(6.5, 2.5, 7, 4.5));
	}

	// ------------------------------------------------------------------ holding what moves without walking

	@Test
	void flyersSwimmersAndHoppersAreHeldTheirOwnWay() {
		for (String k : List.of("phantom", "ghast", "vex", "bee", "allay", "parrot", "bat", "blaze", "breeze")) {
			assertEquals(HoldKind.HOVER, WhipMoves.holdKind("minecraft:" + k, true, true, true), k);
		}
		for (String k : List.of("cod", "salmon", "squid", "dolphin", "guardian")) assertEquals(HoldKind.SWIMMER, WhipMoves.holdKind("minecraft:" + k, true, true, true), k);
		assertEquals(HoldKind.HOPPER, WhipMoves.holdKind("minecraft:slime", true, true, true));
		assertEquals(HoldKind.HOPPER, WhipMoves.holdKind("minecraft:magma_cube", true, true, true));
		assertEquals(HoldKind.ROOTED, WhipMoves.holdKind("minecraft:shulker", true, true, true), "held by cancelling its teleport");
		assertEquals(HoldKind.WALKER, WhipMoves.holdKind("minecraft:zombie", true, true, true));
	}

	@Test
	void anUnknownMobWithNoGravityFloatsAndOneWithGravityWalks() {
		assertEquals(HoldKind.HOVER, WhipMoves.holdKind("minecraft:something_new", true, true, false));
		assertEquals(HoldKind.WALKER, WhipMoves.holdKind("minecraft:something_new", true, true, true));
	}

	@Test
	void onlyAMobWithItsAiOnCanBeHeldWhatEverItsAttributes() {
		// an armour stand has the speed and jump attributes like everything living, but nothing walks it: F at it is a Crack
		assertEquals(HoldKind.NONE, WhipMoves.holdKind("minecraft:armor_stand", false, false, true));
		assertEquals(HoldKind.NONE, WhipMoves.holdKind("minecraft:armor_stand", false, true, false), "not a mob: no kind, even floating");
		assertEquals(HoldKind.NONE, WhipMoves.holdKind("minecraft:zombie", true, false, true), "a mob with its AI off stands still on its own");
		assertEquals(HoldKind.NONE, WhipMoves.holdKind("minecraft:slime", true, false, true), "a known kind with no AI is still nothing to hold");
		assertEquals(HoldKind.NONE, WhipMoves.holdKind("minecraft:shulker", true, false, true));
	}

	@Test
	void aHeldFloaterIsStoppedOnEveryAxisAndAWalkerOnlyAlongTheGround() {
		assertArrayEquals(new double[] {0, 0, 0}, WhipMoves.heldVelocity(HoldKind.HOVER, 0.3, 0.2, -0.1, false), EPS);
		assertArrayEquals(new double[] {0, -0.4, 0}, WhipMoves.heldVelocity(HoldKind.WALKER, 0.3, -0.4, 0.2, false), EPS, "a walker still falls");
		assertArrayEquals(new double[] {0, 0, 0}, WhipMoves.heldVelocity(HoldKind.SWIMMER, 0.3, 0.2, 0.1, true), EPS);
		assertArrayEquals(new double[] {0, -0.5, 0}, WhipMoves.heldVelocity(HoldKind.SWIMMER, 0.3, -0.5, 0.1, false), EPS, "out of the water a fish just falls");
	}

	@Test
	void aSnaredWalkerOrHopperNeverRisesButKeepsFalling() {
		// the hop is made inside the creature's own tick, after the module's, so no ground test can decide it: rising is always taken back
		assertArrayEquals(new double[] {0, 0, 0}, WhipMoves.heldVelocity(HoldKind.HOPPER, 0.2, 0.42, 0.2, false), EPS, "the hop is taken back");
		assertArrayEquals(new double[] {0, 0, 0}, WhipMoves.heldVelocity(HoldKind.WALKER, 0.2, 0.28, 0.2, false), EPS, "a shove upward too");
		assertArrayEquals(new double[] {0, -0.3, 0}, WhipMoves.heldVelocity(HoldKind.HOPPER, 0.2, -0.3, 0.2, false), EPS, "a fall is kept");
		assertArrayEquals(new double[] {0, -0.3, 0}, WhipMoves.heldVelocity(HoldKind.WALKER, 0.2, -0.3, 0.2, false), EPS);
		assertArrayEquals(new double[] {0, 0, 0}, WhipMoves.heldVelocity(HoldKind.HOPPER, 0.2, 0.3, 0.2, true), EPS, "in the air it never rises either");
	}

	@Test
	void rootedAndUnholdableCreaturesAreLeftAsTheyAre() {
		assertArrayEquals(new double[] {0.1, 0.2, 0.3}, WhipMoves.heldVelocity(HoldKind.ROOTED, 0.1, 0.2, 0.3, false), EPS);
		assertArrayEquals(new double[] {0.1, 0.2, 0.3}, WhipMoves.heldVelocity(HoldKind.NONE, 0.1, 0.2, 0.3, false), EPS);
	}

	// ------------------------------------------------------------------ protection and quiet whiffs

	@Test
	void aTeamStopsTheLashOnlyWithFriendlyFireOff() {
		assertTrue(WhipMoves.teamProtects(true, false), "teammates, friendly fire off: vanilla stops it before any event");
		assertFalse(WhipMoves.teamProtects(true, true), "friendly fire on: the team allows it");
		assertFalse(WhipMoves.teamProtects(false, false), "not on the same team");
		assertFalse(WhipMoves.teamProtects(false, true));
	}

	@Test
	void aBlowThatVanillaSwallowedInItsInvulnerabilityFramesStillLandsIfTheTargetWasAllowed() {
		assertTrue(WhipMoves.blowLanded(4, true, false, false, false), "health fell");
		assertTrue(WhipMoves.blowLanded(0, true, false, false, false), "the event ran and nothing cancelled it");
		assertFalse(WhipMoves.blowLanded(0, true, true, true, true), "cancelled by a protection plugin: never, frames or not");
		assertFalse(WhipMoves.blowLanded(0, false, false, false, true), "no event and no frames: something else stopped it");
		assertTrue(WhipMoves.blowLanded(0, false, false, true, true), "swallowed by the frames only: the push and the scare still happen");
		assertFalse(WhipMoves.blowLanded(0, false, false, true, false), "frames, but the test said the target may not be touched");
	}

	@Test
	void aPlayerInANoPvpWorldIsNeverAffectedAndACancelledTestStopsAnything() {
		assertTrue(WhipMoves.mayAffect(true, true, false));
		assertFalse(WhipMoves.mayAffect(true, false, false), "PvP off");
		assertFalse(WhipMoves.mayAffect(true, true, true), "a protection plugin said no to a player");
		assertTrue(WhipMoves.mayAffect(false, false, false), "the world's PvP flag says nothing about a mob");
		assertFalse(WhipMoves.mayAffect(false, true, true), "a claim's animals are safe from the lash too");
	}

	@Test
	void aWhiffIsShownOnlyWithinSixteenBlocks() {
		assertEquals(16.0, WhipMoves.WHIFF_RADIUS, EPS);
		assertTrue(WhipMoves.nearWhiff(0));
		assertTrue(WhipMoves.nearWhiff(16 * 16));
		assertFalse(WhipMoves.nearWhiff(16.1 * 16.1));
		assertFalse(WhipMoves.nearWhiff(96 * 96), "never the 96 blocks a real move reaches");
	}
}
