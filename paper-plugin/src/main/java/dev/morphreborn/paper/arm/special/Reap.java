package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ReapPlan;
import dev.morphreborn.paper.arm.rules.ReapPlan.Crop;
import dev.morphreborn.paper.arm.rules.ReapPlan.Drop;
import dev.morphreborn.paper.arm.rules.ReapPlan.Seeded;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.tips.Lines;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Reap, the sickle arm's special (F; spec 3.9): the arm sweeps round and cuts every ripe crop within reach of the feet
 * (wheat, carrots, potatoes, beetroots, nether wart, cocoa and sweet berries at their last stage), 16 a tick, nearest
 * first, and plants each one again from its own seed. Its other half, Fortune at the arm's level, is on the item
 * itself (ArmItems), so it counts for every crop cut here and for ordinary harvesting alike.
 * <p>
 * At F the crops are planned before anything is paid (ReapPlan): none ripe in the disc and nothing happens. Then the
 * cost and cooldown, S_ARM_FX REAP with every position for players whose game draws arms, a ring of dust for everyone
 * else. Each crop breaks through Player.breakBlock as the player's own break, so protection plugins, Fortune and the
 * drops work as for harvesting by hand; one a plugin refuses, or that changed since the plan, is skipped and costs
 * nothing (crops have no hardness, so the block row bills nothing either: the special's flat cost is all).
 * <p>
 * The seed is never made up. When a crop's drops are announced (BlockDropItemEvent) one seed of its own kind is taken
 * out of them (ReapPlan.takeSeed); after the break the crop is planted again at its first stage, as the player placing
 * that seed: the block is set, BlockPlaceEvent is called, and a cancel puts back exactly what was there. A seed that
 * couldn't be planted drops to the ground instead, so the count of seeds never grows: a crop that dropped no seed isn't
 * replanted, and neither is one whose drops another plugin cancelled.
 * <p>
 * Every step first checks that the arm is still formed and in hand in the world it fired in; if not, the special stops
 * there and nothing is refunded (the crops already cut stay cut).
 */
public final class Reap implements ArmSpecialModule {
	public static final String FORM = "sickle";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "sickle";
	/** A crop further than this from the eyes when its turn comes is skipped (the disc is at most 16 wide, and a step may lag). */
	private static final double MAX_BREAK_DISTANCE_SQ = 20 * 20;
	/** Layers cut above and below the feet. */
	private static final int LAYERS = 1;
	/** The furthest the disc may reach, and the flat radius the fallback ring is drawn at (as a share of it). */
	private static final int MAX_RADIUS = 16;
	private static final double RING_SHARE = 0.6, MAX_RING = 4.5;
	/** The longest list of cells sent to the clients. */
	private static final int MAX_FX_POSITIONS = ReapPlan.MAX_PLAN;

	private @Nullable ArmContext ctx;
	/** Viewers whose game draws arms: every particle fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private Settings settings = Settings.DEFAULTS;
	private final Map<UUID, Run> runs = new HashMap<>();
	private final Listener drops = new Drops();
	/** The crop being cut right now (breakBlock is synchronous), which the drop event matches against. */
	private @Nullable Harvest current;

	/** One number for each level, as the settings give them. */
	private record Lv(double l1, double l2, double l3) {
		double at(int level) {
			return level <= 1 ? l1 : level == 2 ? l2 : l3;
		}
	}

	/** arms.forms.sickle.reap as this module reads it; the defaults are the bundled file's. */
	private record Settings(String name, Lv radius, int perTick, Lv cooldownSeconds) {
		static final Settings DEFAULTS = new Settings("Reap", new Lv(3, 5, 7), 16, new Lv(6, 5, 4));
	}

	/** The crop under the arm's blade: what it was, and whether its drops gave up a seed. */
	private static final class Harvest {
		final Block block;
		final Crop crop;
		/** One seed was taken from its drops; it is planted again or put back. */
		boolean seedTaken;
		/** The drops were cancelled after the seed came out of them, so there was no seed to plant. */
		boolean dropsCancelled;

		Harvest(Block block, Crop crop) {
			this.block = block;
			this.crop = crop;
		}
	}

	/** One special under way. */
	private static final class Run {
		final Player player;
		final long token;
		final World world;
		final int start, level, perTick;
		final List<Pos> plan;
		/** The next crop of the plan to cut. */
		int next;
		boolean over;
		int cut, skipped, replanted, returned;

		Run(Player player, long token, int start, int level, int perTick, List<Pos> plan) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.perTick = perTick;
			this.plan = plan;
		}
	}

	public Reap() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsArms;
		load(ctx.config().form(FORM));
	}

	/** arms.forms.sickle.reap: {@code name, radius, per-tick, cooldown}. */
	@Override
	public void load(Map<String, Object> formSection) {
		Map<String, Object> form = formSection == null ? Map.of() : formSection;
		Map<String, Object> r = map(form.get("reap"));
		Settings d = Settings.DEFAULTS;
		Lv radius = lv(r.get("radius"), d.radius());
		settings = new Settings(string(r.get("name"), d.name()), new Lv(clamp(radius.l1()), clamp(radius.l2()), clamp(radius.l3())),
				(int) Math.clamp(Math.round(number(r.get("per-tick"), d.perTick())), 1, 256), positive(lv(r.get("cooldown"), d.cooldownSeconds())));
	}

	private static double clamp(double radius) {
		return Math.clamp(radius, 0, MAX_RADIUS);
	}

	@Override
	public @Nullable Listener listener() {
		return drops;
	}

	@Override
	public boolean breaksBlocks() {
		return true;
	}

	@Override
	public String specialName(int level) {
		return settings.name();
	}

	@Override
	public long costMilli(int level) {
		ArmContext c = ctx;
		return c == null ? 0 : c.config().costs().special(COST_KEY, level);
	}

	@Override
	public int cooldownTicks(int level) {
		return (int) Math.max(0, Math.round(settings.cooldownSeconds().at(level) * 20));
	}

	/** "Reap: harvests and replants ripe crops within 5 blocks" for the upgrade tree. */
	@Override
	public Lines describeLines(int level) {
		// what it does is explanation; its name and its reach are what the level changes
		return Lines.builder().always(settings.name() + ":").tipMore(" harvests and replants ripe crops within").alwaysMore(" " + radius(level) + " blocks").build();
	}

	private int radius(int level) {
		return (int) Math.round(settings.radius().at(level));
	}

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		World world = p.getWorld();
		Location feet = p.getLocation();
		int radius = radius(level);
		List<Pos> plan = ReapPlan.plan(new WorldField(world), feet.getBlockX(), feet.getBlockY(), feet.getBlockZ(), radius, LAYERS, ReapPlan.MAX_PLAN);
		if (plan.isEmpty()) return SpecialResult.noTarget("No ripe crops around you");
		long cost = costMilli(level);
		int cooldown = cooldownTicks(level);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		Run old = runs.get(p.getUniqueId());
		if (old != null) end(c, old, false); // the gate refuses while one runs, so only ever a leftover
		int now = now(c);
		Run run = new Run(p, c.token(p), now, level, settings.perTick(), plan);
		runs.put(p.getUniqueId(), run);

		long[] positions = new long[Math.min(plan.size(), MAX_FX_POSITIONS)];
		for (int i = 0; i < positions.length; i++) positions[i] = plan.get(i).asLong();
		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_REAP, level, ReapPlan.ticksFor(plan.size(), run.perTick), ThreadLocalRandom.current().nextInt(1 << 30), null,
				positions, radius), ArmProtocol.fxRadius(ArmProtocol.FX_REAP));
		p.swingMainHand();
		world.playSound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1F, 1.3F);
		world.playSound(feet, Sound.ITEM_HOE_TILL, SoundCategory.PLAYERS, 0.6F, 1.5F);
		// the crescent's whip is the client's own; everyone else sees a ring of dust round the player
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.sweepArc(p, Math.min(MAX_RING, RING_SHARE * radius), 360, VanillaEffects.ARM_GREY, natives);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " at level " + level + " planned " + plan.size() + (plan.size() == 1 ? " crop" : " crops") + " within " + radius
					+ ", " + run.perTick + " a tick; cost " + cost + " milli, cooldown " + cooldown + " ticks");
		}
		return SpecialResult.FIRED;
	}

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || runs.isEmpty()) return;
		int now = now(c);
		for (Run run : List.copyOf(runs.values())) {
			if (run.over) continue;
			if (!live(c, run)) {
				end(c, run, false);
				continue;
			}
			int elapsed = now - run.start;
			while (run.next < run.plan.size() && ReapPlan.cutTick(run.next, run.perTick) <= elapsed) {
				cut(c, run, run.plan.get(run.next++));
				if (run.over) return;
				if (!live(c, run)) { // a break may have started something that took the arm
					end(c, run, false);
					break;
				}
			}
			if (!run.over && run.next >= run.plan.size()) end(c, run, true);
		}
	}

	@Override
	public boolean running(Player p) {
		Run run = runs.get(p.getUniqueId());
		return run != null && !run.over;
	}

	@Override
	public void abort(Player p) {
		ArmContext c = ctx;
		Run run = runs.get(p.getUniqueId());
		if (c != null && run != null) end(c, run, false);
	}

	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c == null) return;
		for (Run run : List.copyOf(runs.values())) end(c, run, false);
	}

	// ------------------------------------------------------------------ the steps

	/** One crop's turn: still what was planned and still ripe, it is cut as the player would cut it, then planted again. */
	private void cut(ArmContext c, Run run, Pos pos) {
		Player p = run.player;
		Block b = run.world.getBlockAt(pos.x(), pos.y(), pos.z());
		// near enough is asked first, so a crop far away (the player was moved) never loads its chunk to be looked at
		if (new Location(run.world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5).distanceSquared(p.getEyeLocation()) > MAX_BREAK_DISTANCE_SQ) {
			run.skipped++;
			return;
		}
		Crop crop = ReapPlan.crop(b.getType().name());
		BlockData data = b.getBlockData();
		if (crop == null || !(data instanceof Ageable age) || !ReapPlan.ripe(crop.block(), age.getAge())) {
			run.skipped++;
			return;
		}
		Harvest harvest = new Harvest(b, crop);
		boolean[] done = {false};
		current = harvest;
		try {
			c.asSpecialBreak(p, () -> done[0] = p.breakBlock(b));
		} finally {
			current = null;
		}
		if (!done[0]) {
			run.skipped++;
			return;
		}
		run.cut++;
		// A creative player's break drops nothing (and places seeds for free), so there is no seed to take and none to put back.
		boolean creative = p.getGameMode() == GameMode.CREATIVE;
		if (!creative && (!harvest.seedTaken || harvest.dropsCancelled)) return; // no seed came out of this crop's drops: nothing to plant
		Material seed = Material.matchMaterial(crop.seed());
		if (seed == null) return;
		String refused = replant(p, b, data, seed);
		if (refused == null) {
			run.replanted++;
			return;
		}
		if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " could not replant at " + pos.x() + " " + pos.y() + " " + pos.z() + ": " + refused);
		if (creative) return;
		if (RETRYABLE.contains(refused)) {
			// A crop close to the player was cut and then refused its seed on the very same tick (rig 2026-11-22: only the blocks touching the
			// player, 10 of 10), while the same crop two blocks away took it. Whatever the game was still settling when the break returned, the
			// next tick has: the seed is planted then, and only a second refusal puts it back on the ground.
			Bukkit.getScheduler().runTask(c.plugin(), () -> {
				String again = p.isOnline() ? replant(p, b, data, seed) : "player left";
				if (again == null) {
					run.replanted++;
					if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " replanted at " + pos.x() + " " + pos.y() + " " + pos.z() + " one tick late");
				} else {
					putBack(run, b, seed);
					if (c.config().report()) c.report(settings.name() + ": " + p.getName() + " still could not replant at " + pos.x() + " " + pos.y() + " " + pos.z() + ": " + again);
				}
			});
			return;
		}
		putBack(run, b, seed);
	}

	/** The seed goes back, where the crop stood, for the player to pick up. */
	private static void putBack(Run run, Block b, Material seed) {
		run.world.dropItemNaturally(b.getLocation().add(0.5, 0.25, 0.5), new ItemStack(seed));
		run.returned++;
	}

	private static final String OCCUPIED = "the block is not empty", NO_SUPPORT = "it cannot stand there (soil or light)", REFUSED = "the place was refused";
	/** Refusals a tick later may not repeat: the game's own state (the block, its light or support), never a plugin's cancel. */
	private static final Set<String> RETRYABLE = Set.of(OCCUPIED, NO_SUPPORT);

	/**
	 * The crop again at its first stage, placed by the player with the seed they just got from it: the block is set, then
	 * BlockPlaceEvent is called, and a cancel puts back the state saved before. Null when it was planted, else why not: the spot
	 * can't hold it any more (something is there, the soil is gone) or the place was refused.
	 */
	private @Nullable String replant(Player p, Block b, BlockData was, Material seed) {
		if (!b.getType().isAir()) return OCCUPIED;
		BlockData fresh = was.clone();
		if (!(fresh instanceof Ageable age)) return "not a crop";
		age.setAge(ReapPlan.REPLANT_AGE);
		if (!fresh.isSupported(b)) return NO_SUPPORT;
		// the block it grows on: the one below, or for cocoa the log it hangs from
		BlockFace toward = fresh instanceof Directional d ? d.getFacing() : BlockFace.DOWN;
		Block against = b.getRelative(toward);
		BlockState before = b.getState();
		b.setBlockData(fresh, false);
		BlockPlaceEvent event = new BlockPlaceEvent(b, before, against, new ItemStack(seed), p, true, EquipmentSlot.HAND);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled() || !event.canBuild()) {
			before.update(true, false);
			return REFUSED;
		}
		b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 0.5F, 1.0F);
		return null;
	}

	private void end(ArmContext c, Run run, boolean finished) {
		if (run.over) return;
		run.over = true;
		runs.remove(run.player.getUniqueId(), run);
		if (c.config().report()) {
			c.report(settings.name() + ": " + run.player.getName() + (finished ? " finished" : " stopped") + " after " + (now(c) - run.start) + " ticks: cut " + run.cut
					+ ", replanted " + run.replanted + ", seeds put back " + run.returned + ", skipped " + run.skipped + " of " + run.plan.size());
		}
	}

	// ------------------------------------------------------------------ drops

	/**
	 * Takes the replant's seed out of the crop's drops. HIGH, so protection plugins that cancel drops have been heard;
	 * MONITOR then notes a cancel that came after, so a seed that went with cancelled drops isn't planted.
	 */
	private final class Drops implements Listener {
		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onDrop(BlockDropItemEvent event) {
			Harvest h = current;
			if (h == null || !sameBlock(h.block, event.getBlock())) return;
			List<Item> items = event.getItems();
			List<Drop> drops = new ArrayList<>(items.size());
			for (Item item : items) drops.add(new Drop(item.getItemStack().getType().name(), item.getItemStack().getAmount()));
			Seeded seeded = ReapPlan.takeSeed(h.crop.block(), drops);
			if (!seeded.taken()) return;
			Item item = items.get(seeded.index());
			ItemStack stack = item.getItemStack();
			if (stack.getAmount() <= 1) {
				items.remove(seeded.index());
			} else {
				stack.setAmount(stack.getAmount() - 1);
				item.setItemStack(stack);
			}
			h.seedTaken = true;
		}

		@EventHandler(priority = EventPriority.MONITOR)
		public void onDropFinal(BlockDropItemEvent event) {
			Harvest h = current;
			if (h != null && h.seedTaken && event.isCancelled() && sameBlock(h.block, event.getBlock())) h.dropsCancelled = true;
		}
	}

	private static boolean sameBlock(Block a, Block b) {
		return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ() && a.getWorld().equals(b.getWorld());
	}

	// ------------------------------------------------------------------ the world, for the planner

	/** The world as ReapPlan sees it: crops by block, their stage, and whether the arm may break one. */
	private static final class WorldField implements ReapPlan.Field {
		private final World world;
		private final int minY, maxY;

		WorldField(World world) {
			this.world = world;
			this.minY = world.getMinHeight();
			this.maxY = world.getMaxHeight();
		}

		/** No crop outside the world's height or in a chunk that isn't loaded (the plan never loads one). */
		@Override
		public @Nullable String block(int x, int y, int z) {
			if (y < minY || y >= maxY || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
			String name = world.getType(x, y, z).name();
			return ReapPlan.crop(name) == null ? null : name;
		}

		@Override
		public int age(int x, int y, int z) {
			return world.getBlockData(x, y, z) instanceof Ageable a ? a.getAge() : 0;
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			return world.getType(x, y, z).getHardness() >= 0;
		}
	}

	// ------------------------------------------------------------------ helpers

	private boolean live(ArmContext c, Run run) {
		return c.stillArmed(run.player, run.token) && run.player.getWorld() == run.world;
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	private static Map<String, Object> map(@Nullable Object o) {
		if (!(o instanceof Map<?, ?> m)) return Map.of();
		Map<String, Object> out = new HashMap<>();
		for (Map.Entry<?, ?> e : m.entrySet()) {
			if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
		}
		return out;
	}

	private static double number(@Nullable Object v, double fallback) {
		if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
		if (v instanceof String s) {
			try {
				double d = Double.parseDouble(s.trim());
				return Double.isFinite(d) ? d : fallback;
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		return fallback;
	}

	private static String string(@Nullable Object v, String fallback) {
		return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
	}

	/** One number or a list of up to three (fewer repeat the last; a bad entry takes that level's default). */
	private static Lv lv(@Nullable Object v, Lv fallback) {
		if (v instanceof Number || v instanceof String) {
			double d = number(v, Double.NaN);
			return Double.isNaN(d) ? fallback : new Lv(d, d, d);
		}
		if (!(v instanceof List<?> list) || list.isEmpty()) return fallback;
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) out[i] = number(list.get(Math.min(i, list.size() - 1)), fallback.at(i + 1));
		return new Lv(out[0], out[1], out[2]);
	}

	private static Lv positive(Lv l) {
		return new Lv(Math.max(0, l.l1()), Math.max(0, l.l2()), Math.max(0, l.l3()));
	}
}
