package dev.morphreborn.paper.ability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.data.MorphRef;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The multimorph rules that are plain logic: who blasts, what a same-body change keeps, how much room a body needs. */
class MultimorphRulesTest {
	private static MorphRef form(String type, String snbt) {
		return new MorphRef(type, type.substring(10) + "1", snbt, null);
	}

	private static final String TNT_CUBE_SNBT = "{equipment:{body:{id:\"minecraft:tnt\",count:1}}}";
	private static final MorphRef EMPTY_CUBE = form("minecraft:sulfur_cube", "");
	private static final MorphRef TNT_CUBE = form("minecraft:sulfur_cube", TNT_CUBE_SNBT);
	private static final MorphRef CREEPER = form("minecraft:creeper", "");
	private static final MorphRef ZOMBIE = form("minecraft:zombie", "");

	/** What AbilityManager.canBlast says without the game's item tags: a creeper always, a cube only with TNT in it. */
	private static boolean canBlast(MorphRef f) {
		return f.entityType().equals("minecraft:creeper") || (f.entityType().equals("minecraft:sulfur_cube") && f.snbt().contains("tnt"));
	}

	@Test
	void aCubeWithNoBlockDoesNotHideABorrowedCreeper() {
		assertEquals(CREEPER, Blasters.pick(EMPTY_CUBE, CREEPER, MultimorphRulesTest::canBlast));
	}

	@Test
	void aCubeWithNoBlockAndNothingElseBlastsNothing() {
		assertNull(Blasters.pick(EMPTY_CUBE, null, MultimorphRulesTest::canBlast));
		assertNull(Blasters.pick(EMPTY_CUBE, ZOMBIE, MultimorphRulesTest::canBlast));
	}

	@Test
	void theMainFormBlastsFirstAndABorrowedCubeBlastsUnderAnyBody() {
		assertEquals(TNT_CUBE, Blasters.pick(TNT_CUBE, CREEPER, MultimorphRulesTest::canBlast));
		assertEquals(CREEPER, Blasters.pick(CREEPER, TNT_CUBE, MultimorphRulesTest::canBlast));
		assertEquals(TNT_CUBE, Blasters.pick(ZOMBIE, TNT_CUBE, MultimorphRulesTest::canBlast));
	}

	@Test
	void aLitBorrowedCubeStaysLitWhileTheCreeperIsTheBody() {
		assertTrue(Blasters.isOneOf(TNT_CUBE, CREEPER, TNT_CUBE));
		assertFalse(Blasters.isOneOf(TNT_CUBE, CREEPER, ZOMBIE));
		assertFalse(Blasters.isOneOf(null, CREEPER, ZOMBIE));
	}

	@Test
	void aSameBodyChangeKeepsTheStateOfFormsStillThere() {
		assertTrue(BodyState.keepsCharge(List.of("minecraft:pig", "minecraft:creeper")));
		assertFalse(BodyState.keepsCharge(List.of("minecraft:pig", "minecraft:cow")));
		assertTrue(BodyState.keepsPerch(List.of("minecraft:bat", "minecraft:cow")));
		assertFalse(BodyState.keepsPerch(List.of("minecraft:cow")));
		assertTrue(BodyState.keepsSting(List.of("minecraft:iron_golem", "minecraft:bee")));
		assertFalse(BodyState.keepsSting(List.of("minecraft:iron_golem", "minecraft:cow")));
	}

	@Test
	void theSameEffectFromTwoFormsIsTheStrongerOne() {
		record Effect(String type, int amplifier) {}
		List<Effect> both = List.of(new Effect("jump_boost", 0), new Effect("speed", 1), new Effect("jump_boost", 2));
		List<Effect> out = BodyState.strongest(both, Effect::type, Effect::amplifier);
		assertEquals(List.of(new Effect("jump_boost", 2), new Effect("speed", 1)), out);
		// equal strength: the main form's (the first) stays
		assertEquals(List.of(new Effect("jump_boost", 1)), BodyState.strongest(List.of(new Effect("jump_boost", 1), new Effect("jump_boost", 1)), Effect::type, Effect::amplifier));
	}

	@Test
	void aTallWideBodyNeedsRoomForAllOfIt() {
		assertEquals(2, BodyRoom.blocksHigh(1.8));
		assertEquals(3, BodyRoom.blocksHigh(2.7)); // an iron golem
		assertEquals(4, BodyRoom.blocksHigh(4.0)); // a ghast: exactly four
		assertEquals(13, BodyRoom.blocksHigh(12.6));
		assertArrayEquals(new int[] {0, 0}, BodyRoom.span(0.6));
		assertArrayEquals(new int[] {-1, 1}, BodyRoom.span(1.4));
		assertArrayEquals(new int[] {-2, 2}, BodyRoom.span(4.0)); // a giant's width
	}

	@Test
	void aFitCheckReadsAGiantsBlocksButNotADragons() {
		assertEquals(2, BodyRoom.cells(0.6, 1.8));
		assertEquals(300, BodyRoom.cells(4.0, 12.0)); // a giant: 5 x 5 x 12
		assertTrue(BodyRoom.cells(4.0, 12.0) <= BodyRoom.MAX_SCAN);
		assertTrue(BodyRoom.cells(16.0, 8.0) > BodyRoom.MAX_SCAN); // a dragon: 17 x 17 x 8
	}

	@Test
	void aFrogsTongueMakesItsOwnColourOfFroglight() {
		assertEquals("pearlescent", Frogs.froglight("{variant:\"minecraft:warm\"}"));
		assertEquals("verdant", Frogs.froglight("{variant:\"minecraft:cold\"}"));
		assertEquals("ochre", Frogs.froglight("{variant:\"minecraft:temperate\"}"));
		assertEquals("ochre", Frogs.froglight(""));
	}
}
