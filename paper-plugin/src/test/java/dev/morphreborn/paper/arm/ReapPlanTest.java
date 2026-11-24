package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.rules.ReapPlan;
import dev.morphreborn.paper.arm.rules.ReapPlan.Crop;
import dev.morphreborn.paper.arm.rules.ReapPlan.Drop;
import dev.morphreborn.paper.arm.rules.ReapPlan.Field;
import dev.morphreborn.paper.arm.rules.ReapPlan.Seeded;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Reap: which crops are cut, in what order, and that the seed for the replant is never made up. */
class ReapPlanTest {
	private static final List<String> BLOCKS = List.of("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA", "SWEET_BERRY_BUSH");
	private static final List<String> SEEDS = List.of("WHEAT_SEEDS", "CARROT", "POTATO", "BEETROOT_SEEDS", "NETHER_WART", "COCOA_BEANS", "SWEET_BERRIES");

	/** A field from a map: a block and its age, and the cells the arm can't break. */
	private static final class MapField implements Field {
		final Map<Pos, String> blocks = new HashMap<>();
		final Map<Pos, Integer> ages = new HashMap<>();
		final Set<Pos> locked = new HashSet<>();

		MapField crop(int x, int y, int z, String block, int age) {
			blocks.put(new Pos(x, y, z), block);
			ages.put(new Pos(x, y, z), age);
			return this;
		}

		@Override
		public @Nullable String block(int x, int y, int z) {
			return blocks.get(new Pos(x, y, z));
		}

		@Override
		public int age(int x, int y, int z) {
			return ages.getOrDefault(new Pos(x, y, z), 0);
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			return !locked.contains(new Pos(x, y, z));
		}
	}

	@Test
	void everyCropHasItsOwnSeedAndRipeAge() {
		assertEquals(Set.copyOf(BLOCKS), ReapPlan.blocks());
		for (int i = 0; i < BLOCKS.size(); i++) {
			Crop c = ReapPlan.crop(BLOCKS.get(i));
			assertNotNull(c, BLOCKS.get(i));
			assertEquals(SEEDS.get(i), c.seed());
			assertTrue(c.maxAge() >= 2);
		}
		assertNull(ReapPlan.crop("GRASS_BLOCK"));
		assertNull(ReapPlan.crop("PUMPKIN_STEM"));
	}

	@Test
	void onlyTheLastStageIsRipe() {
		for (String block : BLOCKS) {
			int max = ReapPlan.crop(block).maxAge();
			for (int age = 0; age < max; age++) assertFalse(ReapPlan.ripe(block, age), block + " at " + age);
			assertTrue(ReapPlan.ripe(block, max), block);
		}
		assertEquals(7, ReapPlan.crop("WHEAT").maxAge());
		assertEquals(3, ReapPlan.crop("BEETROOTS").maxAge());
		assertEquals(2, ReapPlan.crop("COCOA").maxAge());
		assertFalse(ReapPlan.ripe("STONE", 7));
	}

	// ------------------------------------------------------------------ the seed

	private static int count(List<Drop> drops, String item) {
		int n = 0;
		for (Drop d : drops) {
			if (d.item().equals(item)) n += d.amount();
		}
		return n;
	}

	@Test
	void takesExactlyOneSeed() {
		List<Drop> drops = List.of(new Drop("WHEAT", 1), new Drop("WHEAT_SEEDS", 3));
		Seeded s = ReapPlan.takeSeed("WHEAT", drops);
		assertTrue(s.taken());
		assertEquals(1, s.index());
		assertEquals(List.of(new Drop("WHEAT", 1), new Drop("WHEAT_SEEDS", 2)), s.rest());
	}

	@Test
	void aSingleSeedLeavesNoEmptyStack() {
		Seeded s = ReapPlan.takeSeed("WHEAT", List.of(new Drop("WHEAT_SEEDS", 1), new Drop("WHEAT", 1)));
		assertTrue(s.taken());
		assertEquals(List.of(new Drop("WHEAT", 1)), s.rest());
	}

	@Test
	void takesFromTheFirstStackThatHasOne() {
		Seeded s = ReapPlan.takeSeed("CARROTS", List.of(new Drop("CARROT", 2), new Drop("CARROT", 4)));
		assertEquals(0, s.index());
		assertEquals(List.of(new Drop("CARROT", 1), new Drop("CARROT", 4)), s.rest());
	}

	@Test
	void neverInventsASeed() {
		// the crop dropped no seed: nothing is taken, nothing is added, and it won't be replanted
		List<Drop> noSeed = List.of(new Drop("WHEAT", 1));
		Seeded s = ReapPlan.takeSeed("WHEAT", noSeed);
		assertFalse(s.taken());
		assertEquals(-1, s.index());
		assertEquals(noSeed, s.rest());
		// nothing at all
		assertFalse(ReapPlan.takeSeed("POTATOES", List.of()).taken());
		assertEquals(List.of(), ReapPlan.takeSeed("POTATOES", List.of()).rest());
		// another crop's seed doesn't count
		Seeded wrong = ReapPlan.takeSeed("BEETROOTS", List.of(new Drop("WHEAT_SEEDS", 5), new Drop("BEETROOT", 1)));
		assertFalse(wrong.taken());
		assertEquals(5, count(wrong.rest(), "WHEAT_SEEDS"));
		// a block that isn't a crop never takes anything
		Seeded stone = ReapPlan.takeSeed("STONE", List.of(new Drop("WHEAT_SEEDS", 2)));
		assertFalse(stone.taken());
		assertEquals(2, count(stone.rest(), "WHEAT_SEEDS"));
		// an empty stack isn't a seed
		assertFalse(ReapPlan.takeSeed("WHEAT", List.of(new Drop("WHEAT_SEEDS", 0))).taken());
	}

	@Test
	void everyItemIsAccountedForOverRandomDrops() {
		Random random = new Random(20261115L);
		String[] items = {"WHEAT", "WHEAT_SEEDS", "CARROT", "POTATO", "POISONOUS_POTATO", "BEETROOT", "BEETROOT_SEEDS", "NETHER_WART", "COCOA_BEANS", "SWEET_BERRIES"};
		for (int round = 0; round < 3000; round++) {
			String block = BLOCKS.get(random.nextInt(BLOCKS.size()));
			String seed = ReapPlan.crop(block).seed();
			List<Drop> drops = new ArrayList<>();
			int stacks = random.nextInt(6);
			for (int i = 0; i < stacks; i++) drops.add(new Drop(items[random.nextInt(items.length)], random.nextInt(5)));
			Seeded s = ReapPlan.takeSeed(block, drops);
			boolean had = count(drops, seed) > 0;
			assertEquals(had, s.taken(), "round " + round);
			for (String item : items) {
				int expected = count(drops, item) - (s.taken() && item.equals(seed) ? 1 : 0);
				assertEquals(expected, count(s.rest(), item), item + " in round " + round);
			}
			// no new kinds of item, no empty stacks made, never more stacks than before
			Set<String> before = new HashSet<>();
			for (Drop d : drops) before.add(d.item());
			for (Drop d : s.rest()) {
				assertTrue(before.contains(d.item()), "an item that wasn't there: " + d);
			}
			assertTrue(s.rest().size() <= drops.size());
			if (!s.taken()) assertEquals(drops, s.rest());
		}
	}

	// ------------------------------------------------------------------ the plan

	@Test
	void plansOnlyRipeCropsInTheDisc() {
		MapField f = new MapField().crop(0, 64, 0, "WHEAT", 7).crop(1, 64, 0, "WHEAT", 6).crop(2, 64, 1, "CARROTS", 7).crop(0, 65, 0, "POTATOES", 7)
				.crop(0, 66, 0, "POTATOES", 7).crop(4, 64, 0, "WHEAT", 7);
		List<Pos> plan = ReapPlan.plan(f, 0, 64, 0, 3, 1, 100);
		// (1,64,0) is unripe, (0,66,0) is two layers up, (4,64,0) is out of the disc
		assertEquals(List.of(new Pos(0, 64, 0), new Pos(0, 65, 0), new Pos(2, 64, 1)), plan);
	}

	@Test
	void theDiscIsRoundNotSquare() {
		MapField f = new MapField().crop(3, 64, 0, "WHEAT", 7).crop(2, 64, 2, "WHEAT", 7).crop(3, 64, 1, "WHEAT", 7).crop(0, 64, -3, "WHEAT", 7);
		// radius 3: (3,0) and (0,-3) are on the edge, (2,2) is 2.83 away, (3,1) is 3.16 away
		List<Pos> plan = ReapPlan.plan(f, 0, 64, 0, 3, 0, 100);
		assertEquals(3, plan.size());
		assertFalse(plan.contains(new Pos(3, 64, 1)));
	}

	@Test
	void nearestFirstAndAlwaysTheSameOrder() {
		MapField f = new MapField();
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) f.crop(x, 64, z, "WHEAT", 7);
		}
		List<Pos> plan = ReapPlan.plan(f, 0, 64, 0, 4, 1, 1000);
		assertEquals(new Pos(0, 64, 0), plan.get(0));
		int last = -1;
		for (Pos p : plan) {
			int flat = p.x() * p.x() + p.z() * p.z();
			assertTrue(flat >= last, "ordered by distance");
			last = flat;
		}
		// a second walk over the same field gives the same list
		assertEquals(plan, ReapPlan.plan(f, 0, 64, 0, 4, 1, 1000));
		// 4 ties at distance 1: x then z
		assertEquals(List.of(new Pos(-1, 64, 0), new Pos(0, 64, -1), new Pos(0, 64, 1), new Pos(1, 64, 0)), plan.subList(1, 5));
	}

	@Test
	void theLayerOfTheFeetComesBeforeTheOnesAboveAndBelow() {
		MapField f = new MapField().crop(1, 65, 0, "WHEAT", 7).crop(1, 63, 0, "WHEAT", 7).crop(1, 64, 0, "WHEAT", 7);
		assertEquals(List.of(new Pos(1, 64, 0), new Pos(1, 63, 0), new Pos(1, 65, 0)), ReapPlan.plan(f, 0, 64, 0, 3, 1, 10));
	}

	@Test
	void leavesWhatTheArmCannotBreakAndHonoursTheCap() {
		MapField f = new MapField().crop(0, 64, 0, "WHEAT", 7).crop(1, 64, 0, "WHEAT", 7).crop(2, 64, 0, "WHEAT", 7).crop(3, 64, 0, "WHEAT", 7);
		f.locked.add(new Pos(1, 64, 0));
		assertEquals(List.of(new Pos(0, 64, 0), new Pos(2, 64, 0)), ReapPlan.plan(f, 0, 64, 0, 3, 0, 2));
		assertEquals(3, ReapPlan.plan(f, 0, 64, 0, 3, 0, 100).size());
		assertEquals(List.of(), ReapPlan.plan(f, 0, 64, 0, 3, 0, 0));
		assertEquals(List.of(), ReapPlan.plan(new MapField(), 0, 64, 0, 7, 1, 100));
	}

	@Test
	void theCapNeverPassesTheMaximum() {
		MapField f = new MapField();
		for (int x = -7; x <= 7; x++) {
			for (int z = -7; z <= 7; z++) {
				for (int y = 63; y <= 65; y++) f.crop(x, y, z, "WHEAT", 7);
			}
		}
		assertTrue(ReapPlan.plan(f, 0, 64, 0, 7, 1, 1_000_000).size() <= ReapPlan.MAX_PLAN);
	}

	@Test
	void sixteenATick() {
		assertEquals(0, ReapPlan.cutTick(0, 16));
		assertEquals(0, ReapPlan.cutTick(15, 16));
		assertEquals(1, ReapPlan.cutTick(16, 16));
		assertEquals(0, ReapPlan.ticksFor(0, 16));
		assertEquals(1, ReapPlan.ticksFor(16, 16));
		assertEquals(2, ReapPlan.ticksFor(17, 16));
		// a bad rate is read as one a tick
		assertEquals(5, ReapPlan.cutTick(5, 0));
	}
}
