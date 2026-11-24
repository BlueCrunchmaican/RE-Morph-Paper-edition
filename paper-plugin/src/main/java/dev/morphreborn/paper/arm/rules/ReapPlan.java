package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reap's plan, the sickle arm's special (spec 3.9): which ripe crops around the player are cut, in what order, and what
 * becomes of the seed. Two rules matter and both are kept here so they can be tested without a server:
 * <ul>
 * <li>only a crop at its last growth stage is cut (wheat, carrots, potatoes, beetroots, nether wart, cocoa and sweet
 * berries), nearest first, in a disc around the feet and one block either way;</li>
 * <li>replanting costs exactly one seed, taken from that crop's own drops. {@link #takeSeed} removes one seed item
 * from the drops and never adds anything: a crop that dropped no seed is not replanted, and a seed that could not be
 * planted goes back to the ground, so the number of seeds in the world never grows.</li>
 * </ul>
 * Block and item names are the server's material names ({@code WHEAT}, {@code WHEAT_SEEDS}); this class only compares
 * them. Pure.
 */
public final class ReapPlan {
	/** A crop starts again from its first stage. */
	public static final int REPLANT_AGE = 0;
	/** The most crops one Reap plans (a seven-block disc over three layers holds well under this). */
	public static final int MAX_PLAN = 1024;

	/**
	 * One kind of crop: the block, the item it is planted from, and the age at which it is ripe.
	 *
	 * @param seed the one item taken from its drops to replant it
	 */
	public record Crop(String block, String seed, int maxAge) {}

	private static final Map<String, Crop> CROPS = crops();

	private ReapPlan() {}

	private static Map<String, Crop> crops() {
		Map<String, Crop> m = new LinkedHashMap<>();
		add(m, "WHEAT", "WHEAT_SEEDS", 7);
		add(m, "CARROTS", "CARROT", 7);
		add(m, "POTATOES", "POTATO", 7);
		add(m, "BEETROOTS", "BEETROOT_SEEDS", 3);
		add(m, "NETHER_WART", "NETHER_WART", 3);
		add(m, "COCOA", "COCOA_BEANS", 2);
		add(m, "SWEET_BERRY_BUSH", "SWEET_BERRIES", 3);
		return Map.copyOf(m);
	}

	private static void add(Map<String, Crop> m, String block, String seed, int maxAge) {
		m.put(block, new Crop(block, seed, maxAge));
	}

	/** The crop a block is, or null when Reap leaves it alone. */
	public static @Nullable Crop crop(String block) {
		return CROPS.get(block);
	}

	/** Every block Reap cuts. */
	public static Set<String> blocks() {
		return CROPS.keySet();
	}

	/** The block is a crop of the list at its last stage. */
	public static boolean ripe(String block, int age) {
		Crop c = CROPS.get(block);
		return c != null && age >= c.maxAge();
	}

	// ------------------------------------------------------------------ the seed

	/** One stack among a block's drops. */
	public record Drop(String item, int amount) {}

	/**
	 * What taking the seed left.
	 *
	 * @param rest the drops without the seed: the same stacks in the same order, the seed's one smaller (gone when it was
	 *            the last of its stack)
	 * @param index the stack of the original list the seed came from, -1 when none was taken
	 * @param taken a seed was taken, so the crop may be replanted
	 */
	public record Seeded(List<Drop> rest, int index, boolean taken) {}

	/**
	 * Takes exactly one seed of the crop's kind out of its drops, from the first stack that has one. Nothing is made up:
	 * a block that isn't a crop, or drops with no seed in them, give back the drops unchanged and {@code taken} false,
	 * and then the crop is not replanted.
	 */
	public static Seeded takeSeed(String block, List<Drop> drops) {
		Crop c = CROPS.get(block);
		if (c != null) {
			for (int i = 0; i < drops.size(); i++) {
				Drop d = drops.get(i);
				if (!d.item().equals(c.seed()) || d.amount() < 1) continue;
				List<Drop> rest = new ArrayList<>(drops.size());
				for (int j = 0; j < drops.size(); j++) {
					if (j != i) rest.add(drops.get(j));
					else if (d.amount() > 1) rest.add(new Drop(d.item(), d.amount() - 1));
				}
				return new Seeded(List.copyOf(rest), i, true);
			}
		}
		return new Seeded(List.copyOf(drops), -1, false);
	}

	// ------------------------------------------------------------------ the field

	/** The world as the plan reads it, so tests can use a map. */
	public interface Field {
		/** The block's material name when it is one of the {@link #blocks() crops}, else null (air, stone, out of the world). */
		@Nullable String block(int x, int y, int z);

		/** The crop's growth stage. */
		int age(int x, int y, int z);

		/** The arm may break it (a protection plugin's say comes later, at the break itself). */
		boolean breakable(int x, int y, int z);
	}

	/** Nearest first (flat distance, then the layer closest to the feet), then by x and z so the order is always the same. */
	private record Entry(Pos pos, int flat, int dy) {}

	private static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::flat).thenComparingInt((Entry e) -> Math.abs(e.dy()))
			.thenComparingInt(Entry::dy).thenComparingInt((Entry e) -> e.pos().x()).thenComparingInt((Entry e) -> e.pos().z());

	/**
	 * The ripe crops Reap cuts, nearest first: every block within {@code radius} (flat distance, from the centre of the
	 * block the feet are in) and {@code layers} blocks above and below it that is a ripe, breakable crop.
	 *
	 * @param cx where the feet are
	 * @param cap the most crops planned (at most {@link #MAX_PLAN})
	 */
	public static List<Pos> plan(Field field, int cx, int cy, int cz, int radius, int layers, int cap) {
		int r = Math.max(0, radius), up = Math.max(0, layers), most = Math.clamp(cap, 0, MAX_PLAN);
		List<Entry> found = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int flat = dx * dx + dz * dz;
				if (flat > r * r) continue;
				for (int dy = -up; dy <= up; dy++) {
					int x = cx + dx, y = cy + dy, z = cz + dz;
					String block = field.block(x, y, z);
					if (block == null || !ripe(block, field.age(x, y, z)) || !field.breakable(x, y, z)) continue;
					found.add(new Entry(new Pos(x, y, z), flat, dy));
				}
			}
		}
		found.sort(ORDER);
		List<Pos> out = new ArrayList<>(Math.min(found.size(), most));
		for (int i = 0; i < found.size() && i < most; i++) out.add(found.get(i).pos());
		return List.copyOf(out);
	}

	/** The tick, counted from F, that crop number {@code index} of the plan is cut: {@code perTick} each tick, the first at once. */
	public static int cutTick(int index, int perTick) {
		return index / Math.max(1, perTick);
	}

	/** How many ticks cutting {@code count} crops takes. */
	public static int ticksFor(int count, int perTick) {
		return count <= 0 ? 0 : cutTick(count - 1, perTick) + 1;
	}
}
