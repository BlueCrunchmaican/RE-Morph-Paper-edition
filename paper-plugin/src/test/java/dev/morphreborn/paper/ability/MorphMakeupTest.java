package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.ability.MorphMakeup.Kind;
import dev.morphreborn.paper.data.MorphRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Camouflage is for mobs and Borrowed Scent is for the player (2026-11-17, on B2-02 and B2-09). */
class MorphMakeupTest {
	private static MorphRef creature(String type) {
		return new MorphRef("minecraft:" + type, type + "-1", "", null);
	}

	private static final MorphRef OWN_BODY = MorphRef.selfBody(null);

	@Test
	void aBodyOfCreaturesAloneIsCamouflagesWhicheverFormCounts() {
		MorphMakeup single = MorphMakeup.of(creature("zombie"), null);
		assertEquals(Kind.CAMOUFLAGE, single.kind());
		assertEquals(List.of("minecraft:zombie"), single.creatures());
		// cow + zombie and zombie + cow: the lent form counts too, and neither is Scent's
		MorphMakeup cowZombie = MorphMakeup.of(creature("cow"), creature("zombie"));
		assertEquals(Kind.CAMOUFLAGE, cowZombie.kind());
		assertEquals(List.of("minecraft:cow", "minecraft:zombie"), cowZombie.creatures());
		assertEquals(List.of("minecraft:zombie", "minecraft:cow"), MorphMakeup.of(creature("zombie"), creature("cow")).creatures());
		assertFalse(cowZombie.scentSpares("minecraft:zombie"));
	}

	@Test
	void borrowedScentDoesNothingForACreatureOnlyBody() {
		// pig + zombie paid 6 a minute for nothing before: it is not Scent's now, so it neither acts nor bills
		MorphMakeup pigZombie = MorphMakeup.of(creature("pig"), creature("zombie"));
		assertNotScent(pigZombie, "minecraft:pig");
		assertNotScent(pigZombie, "minecraft:zombie");
		assertNotScent(MorphMakeup.of(creature("zombie"), null), "minecraft:zombie");
	}

	private static void assertNotScent(MorphMakeup m, String mob) {
		assertTrue(m.kind() != Kind.SCENT);
		assertFalse(m.scentSpares(mob));
	}

	@Test
	void yourOwnBodyWithACreatureLentIsScentsForThatCreatureOnly() {
		MorphMakeup m = MorphMakeup.of(OWN_BODY, creature("zombie"));
		assertEquals(Kind.SCENT, m.kind());
		assertEquals(List.of("minecraft:zombie"), m.creatures());
		assertTrue(m.scentSpares("minecraft:zombie"));
		assertFalse(m.scentSpares("minecraft:skeleton"));
		assertFalse(m.scentSpares("minecraft:player"));
	}

	@Test
	void aCreatureWithYourOwnHeadIsScentsForTheCreature() {
		MorphMakeup m = MorphMakeup.of(creature("zombie"), OWN_BODY);
		assertEquals(Kind.SCENT, m.kind());
		assertTrue(m.scentSpares("minecraft:zombie"));
		assertFalse(m.scentSpares("minecraft:cow"));
	}

	@Test
	void anotherPlayersFormCountsAsYouToo() {
		// a player-type form that is not your own body is still a human frame with a creature on it
		MorphRef someone = new MorphRef(MorphRef.PLAYER_TYPE, "p-1", "", null);
		assertEquals(Kind.SCENT, MorphMakeup.of(someone, creature("creeper")).kind());
		assertEquals(Kind.SCENT, MorphMakeup.of(creature("creeper"), someone).kind());
	}

	@Test
	void yourOwnBodyWithNothingBorrowedHasNothingToHide() {
		assertEquals(MorphMakeup.NONE, MorphMakeup.of(null, null));
		assertEquals(Kind.NONE, MorphMakeup.of(OWN_BODY, null).kind());
		assertEquals(Kind.NONE, MorphMakeup.of(new MorphRef(MorphRef.PLAYER_TYPE, "p-1", "", null), null).kind());
		assertFalse(MorphMakeup.NONE.scentSpares("minecraft:zombie"));
		assertTrue(MorphMakeup.NONE.creatures().isEmpty());
		// a borrowed form with no body (a stale lend) is nothing either
		assertEquals(Kind.NONE, MorphMakeup.of(null, creature("zombie")).kind());
	}

	@Test
	void theSameCreatureTwiceCountsOnce() {
		MorphMakeup m = MorphMakeup.of(creature("zombie"), new MorphRef("minecraft:zombie", "zombie-2", "{IsBaby:1b}", null));
		assertEquals(Kind.CAMOUFLAGE, m.kind());
		assertEquals(List.of("minecraft:zombie"), m.creatures());
	}

	@Test
	void classicKeepsCamouflageForABodyWithYouInIt() {
		// Classic has no Borrowed Scent to bill, so a creature with your own head must not lose its protection there
		MorphMakeup withOwnHead = MorphMakeup.of(creature("zombie"), OWN_BODY);
		assertTrue(withOwnHead.camouflages(false));
		assertFalse(withOwnHead.camouflages(true));
		assertTrue(MorphMakeup.of(OWN_BODY, creature("zombie")).camouflages(false));
		// creatures alone are Camouflage's in both modes; nothing is anyone's
		assertTrue(MorphMakeup.of(creature("cow"), creature("zombie")).camouflages(true));
		assertTrue(MorphMakeup.of(creature("cow"), creature("zombie")).camouflages(false));
		assertFalse(MorphMakeup.NONE.camouflages(false));
		assertFalse(MorphMakeup.NONE.camouflages(true));
	}

	/** The server's answer for a few kinds, as AbilityManager gives it: Enemy types and always-hostile ones, then the provokable neutrals. */
	private static boolean targets(String type) {
		boolean enemy = Set.of("minecraft:zombie", "minecraft:creeper", "minecraft:piglin", "minecraft:enderman").contains(type);
		return MorphMakeup.canTargetPlayers(type, enemy, type.equals("minecraft:warden"));
	}

	@Test
	void scentIdlesForACreatureThatNeverTargetsPlayers() {
		// decided 2026-11-17: the bar should not go down for scent that does nothing
		for (String peaceful : List.of("cow", "pig", "sheep", "chicken", "villager", "horse", "cat", "snow_golem", "axolotl")) {
			assertFalse(MorphMakeup.of(OWN_BODY, creature(peaceful)).scentHasWork(MorphMakeupTest::targets), peaceful + " lent to your body");
			assertFalse(MorphMakeup.of(creature(peaceful), OWN_BODY).scentHasWork(MorphMakeupTest::targets), peaceful + " with your head");
		}
	}

	@Test
	void scentKeepsBillingHostileAndProvokableKinds() {
		for (String able : List.of("zombie", "creeper", "piglin", "enderman", "warden", "wolf", "bee", "iron_golem", "polar_bear", "goat")) {
			assertTrue(MorphMakeup.of(OWN_BODY, creature(able)).scentHasWork(MorphMakeupTest::targets), able + " lent to your body");
			assertTrue(MorphMakeup.of(creature(able), OWN_BODY).scentHasWork(MorphMakeupTest::targets), able + " with your head");
		}
	}

	@Test
	void scentBillsARabbitBecauseTheKillerBunnyIsOne() {
		// the evil variant adds a player target goal, and the decision is by kind: a rabbit lent to your body must not go unprotected
		assertTrue(MorphMakeup.of(OWN_BODY, creature("rabbit")).scentHasWork(MorphMakeupTest::targets));
		assertTrue(MorphMakeup.of(creature("rabbit"), OWN_BODY).scentHasWork(MorphMakeupTest::targets));
	}

	@Test
	void theServersClassDecidesTheSameWayAsTheFlag() {
		// the path AbilityManager takes from Registry.ENTITY_TYPE: the class's Enemy-ness, with the real API classes
		assertTrue(MorphMakeup.canTargetPlayers("minecraft:zombie", org.bukkit.entity.Zombie.class, false));
		assertTrue(MorphMakeup.canTargetPlayers("minecraft:creeper", org.bukkit.entity.Creeper.class, false));
		assertTrue(MorphMakeup.canTargetPlayers("minecraft:rabbit", org.bukkit.entity.Rabbit.class, false));
		assertTrue(MorphMakeup.canTargetPlayers("minecraft:wolf", org.bukkit.entity.Wolf.class, false));
		assertTrue(MorphMakeup.canTargetPlayers("minecraft:warden", org.bukkit.entity.Cow.class, true), "always hostile wins over the class");
		assertFalse(MorphMakeup.canTargetPlayers("minecraft:cow", org.bukkit.entity.Cow.class, false));
		assertFalse(MorphMakeup.canTargetPlayers("minecraft:pig", org.bukkit.entity.Pig.class, false));
		assertFalse(MorphMakeup.canTargetPlayers("minecraft:villager", org.bukkit.entity.Villager.class, false));
	}

	@Test
	void aKindTheServerDoesNotResolveStillCounts() {
		// no class (the key or the registry entry is missing): bill and spare rather than idle for a creature that may well be hostile
		assertTrue(MorphMakeup.canTargetPlayers("minecraft:zombie", (Class<?>) null, false));
		assertTrue(MorphMakeup.of(OWN_BODY, creature("zombie")).scentHasWork(t -> MorphMakeup.canTargetPlayers(t, (Class<?>) null, false)));
	}

	@Test
	void scentHasNoWorkWhenItIsNotScentsAnyway() {
		// creatures alone, your own body alone and no morph never bill, whatever the creature is
		assertFalse(MorphMakeup.of(creature("zombie"), creature("cow")).scentHasWork(t -> true));
		assertFalse(MorphMakeup.of(creature("zombie"), null).scentHasWork(t -> true));
		assertFalse(MorphMakeup.of(OWN_BODY, null).scentHasWork(t -> true));
		assertFalse(MorphMakeup.NONE.scentHasWork(t -> true));
		// and the decision follows the predicate it is given, for the creature in the body
		assertTrue(MorphMakeup.of(OWN_BODY, creature("cow")).scentHasWork("minecraft:cow"::equals));
		assertFalse(MorphMakeup.of(OWN_BODY, creature("cow")).scentHasWork("minecraft:zombie"::equals));
	}

	@Test
	void aGiantDrawnAsAPlayerStaysTheGiantsCamouflage() {
		// main giant, lent player (GiantPlayer): no giant mob exists to spare, so Scent would bill for nothing
		MorphMakeup m = MorphMakeup.of(creature("giant"), OWN_BODY);
		assertEquals(Kind.CAMOUFLAGE, m.kind());
		assertEquals(List.of("minecraft:giant"), m.creatures());
		assertFalse(m.scentSpares("minecraft:giant"));
		// the reverse (your body borrowing a giant's head) is a multimorph like any other: Scent's
		assertEquals(Kind.SCENT, MorphMakeup.of(OWN_BODY, creature("giant")).kind());
	}
}
