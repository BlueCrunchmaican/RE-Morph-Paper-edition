package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.GridView;
import dev.morphreborn.paper.arm.rules.VeinPlanner;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.arm.rules.VeinSummary;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tips.Lines;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Vein Rip, the pickaxe arm's special (F; spec 3.8): looking at an ore, the arm sends a tendril into it that runs
 * through the rest of the vein and tears every ore of it out, one after another, in the order it reaches them. Its
 * other half, Fortune at the arm's level, is on the item itself (ArmItems), so it counts for ordinary mining and for
 * every ore torn out here alike.
 * <p>
 * At F the vein is planned before anything is paid: the block looked at (within reach, fluids ignored) must be an ore
 * of a known group, and one the arm is hard enough for (an iron arm can't take ancient debris), or nothing happens. The
 * plan is VeinPlanner's: a walk over all 26 neighbours within the ore's group (deepslate ores with their stone kin, never
 * across groups), only through blocks the arm can break, up to the level's most and within six blocks each way, nearest
 * first. Then the cost and cooldown, S_ARM_FX VEIN with the whole plan for players whose game draws arms (they draw the
 * network on the same clock), a tendril or a line of particles to the ore being torn for everyone else.
 * <p>
 * Ore {@code i} breaks {@code ArmProtocol.veinBreakTick(i, rate)} ticks after F: three ticks for the first, then the
 * level's ores a tick. Two ticks before, it cracks for everyone near (0.45, then 0.9 a tick later), under a breaker id of
 * the owner's own so it never disturbs their own digging. It breaks through Player.breakBlock, so protection plugins,
 * Fortune and the block row's billing (0.1 an ore) work as for mining by hand; an ore a plugin refuses, or that changed
 * since the plan is skipped and costs nothing. Only ores within eight blocks of the eyes are planned at all (so the tendrils
 * drawn are the ores that will come out), and one the player has since walked more than nine blocks from is skipped. The drops
 * land at the player's feet (arms.forms.pickaxe.special.pull-drops), and so does the experience, given to the player as a
 * hand-mined ore's would be (it used to stay in the cavity the ore left, out of reach in the rock). When the rip ends the player
 * is told on the action bar how many ores came out.
 * <p>
 * Every step first checks that the arm is still formed and in hand in the world it fired in; if not, the special stops
 * there, its cracks are cleared, and nothing is refunded (the ores already torn out stay billed).
 */
public final class VeinRip implements ArmSpecialModule {
	public static final String FORM = "pickaxe";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "pickaxe";
	/** Players this near an ore see it crack. */
	private static final double CRACK_RANGE_SQ = 64 * 64;
	/** Ticks between the particle lines to the ore being torn (the dust lasts a while). */
	private static final int LINE_EVERY = 4;
	private static final double LINE_STEP = 0.4;
	/** The root tendril's thickness in pixels, at the arm and at the ore. */
	private static final float TENDRIL_FROM_PX = 2.0F, TENDRIL_TO_PX = 1.0F;
	/** Blocks only an operator places, which a player's break never takes (GameMasterBlock in the game). */
	private static final Set<Material> GAME_MASTER = Set.of(Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
			Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.TEST_BLOCK, Material.TEST_INSTANCE_BLOCK);

	private @Nullable ArmContext ctx;
	/** Viewers whose game draws arms: every particle and pack fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private ArmConfig.Pickaxe pickaxe = ArmConfig.DEFAULTS.pickaxe();
	/** Every ore block to its group's name. */
	private Map<Material, String> groups = Map.of();
	private final Map<UUID, Run> runs = new HashMap<>();
	private final Listener drops = new Drops();

	/** The root tendril's key in SkinEffects: one per player. */
	private record StrandKey(UUID owner) {}

	/** One ore of the plan: where, what it was, when it breaks, and how far it has got (0, cracked once, twice, done). */
	private static final class Ore {
		final Pos pos;
		final Block block;
		final Material material;
		final int breakTick;
		int stage;

		Ore(Pos pos, Block block, int breakTick) {
			this.pos = pos;
			this.block = block;
			this.material = block.getType();
			this.breakTick = breakTick;
		}

		Location centre() {
			return block.getLocation().add(0.5, 0.5, 0.5);
		}
	}

	/** One special under way. */
	private static final class Run {
		final Player player;
		final long token;
		final World world;
		final int start, level, seed;
		final Ore[] ores;
		/** The ore group, for the end-of-rip line. */
		final String group;
		final Set<Pos> planned = new HashSet<>();
		final StrandKey key;
		/** The first ore not done yet. */
		int next;
		boolean over;
		int broken, skipped, outOfReach;

		Run(Player player, long token, int start, int level, int seed, String group, Ore[] ores) {
			this.player = player;
			this.group = group;
			this.token = token;
			this.world = player.getWorld();
			this.start = start;
			this.level = level;
			this.seed = seed;
			this.ores = ores;
			for (Ore o : ores) planned.add(o.pos);
			this.key = new StrandKey(player.getUniqueId());
		}
	}

	public VeinRip() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsArms;
		this.pickaxe = ctx.config().pickaxe();
		this.groups = oreGroups(pickaxe.extraOreGroups(), ctx.plugin().getLogger());
	}

	/** The section is read with ArmConfig's own rules, so the module sees exactly what the manager's config does. */
	@Override
	public void load(Map<String, Object> formSection) {
		Map<String, Object> section = formSection == null ? Map.of() : formSection;
		pickaxe = ArmConfig.parse(Map.<String, Object>of("forms", Map.<String, Object>of(FORM, section)), null, null).pickaxe();
		ArmContext c = ctx;
		groups = oreGroups(pickaxe.extraOreGroups(), c == null ? null : c.plugin().getLogger());
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
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(pickaxe.name() + " isn't ready yet.");
		RayTraceResult ray = p.rayTraceBlocks(pickaxe.reach().at(level), FluidCollisionMode.NEVER);
		Block start = ray == null ? null : ray.getHitBlock();
		String group = start == null ? null : groups.get(start.getType());
		if (start == null || group == null) return SpecialResult.noTarget("Look at an ore, then press F"); // the ray stops at the first block, so ore behind stone isn't found
		ItemStack arm = p.getInventory().getItemInMainHand();
		if (!start.isPreferredTool(arm)) return SpecialResult.refused("Your arm isn't hard enough for that");
		World world = start.getWorld();
		Location eyes = p.getEyeLocation();
		List<Pos> plan = VeinPlanner.plan(new WorldGrid(world, arm, eyes), new Pos(start.getX(), start.getY(), start.getZ()), pickaxe.maxBlocks().intAt(level),
				pickaxe.maxOffset(), pickaxe.visitCap(level));
		if (plan.isEmpty()) {
			boolean near = VeinPlanner.withinReach(eyes.getX(), eyes.getY(), eyes.getZ(), start.getX(), start.getY(), start.getZ(), VeinPlanner.PLAN_REACH);
			return SpecialResult.refused(near ? "Your arm can't tear that out" : "That ore is too far for your arm to tear out");
		}
		// a lone ore is what a pick swing does, so it costs no biomass and no cooldown (2026-11-16)
		if (plan.size() == 1) return SpecialResult.refused("Only one ore in reach: just mine it");
		long cost = costMilli(level);
		int cooldown = cooldownTicks(level);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c), rate = pickaxe.rateTenths(level);
		Ore[] ores = new Ore[plan.size()];
		long[] positions = new long[plan.size()];
		for (int i = 0; i < ores.length; i++) {
			Pos pos = plan.get(i);
			ores[i] = new Ore(pos, world.getBlockAt(pos.x(), pos.y(), pos.z()), ArmProtocol.veinBreakTick(i, rate));
			positions[i] = pos.asLong();
		}
		Run old = runs.get(p.getUniqueId());
		if (old != null) end(c, old, false); // the gate refuses while one runs, so only ever a leftover
		Run run = new Run(p, c.token(p), now, level, ThreadLocalRandom.current().nextInt(1 << 30), group, ores);
		runs.put(p.getUniqueId(), run);

		c.fx(p, new ArmFxMsg(p.getEntityId(), ArmProtocol.FX_VEIN, level, ores[ores.length - 1].breakTick, run.seed, null, positions, rate),
				ArmProtocol.fxRadius(ArmProtocol.FX_VEIN));
		world.playSound(p.getLocation(), Sound.ENTITY_WARDEN_DIG, 0.7F, 1.5F);
		draw(c, run, now);
		if (c.config().report()) {
			c.report(pickaxe.name() + ": " + p.getName() + " at level " + level + " planned " + ores.length + (ores.length == 1 ? " ore" : " ores") + " of " + group
					+ " from " + start.getX() + " " + start.getY() + " " + start.getZ() + ", " + SurvivalMode.number(rate / 10.0) + " a tick; cost " + cost
					+ " milli, cooldown " + cooldown + " ticks");
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
			step(c, run, now);
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

	@Override
	public String specialName(int level) {
		return pickaxe.name();
	}

	@Override
	public long costMilli(int level) {
		ArmContext c = ctx;
		return (c == null ? ArmConfig.DEFAULTS : c.config()).costs().special(COST_KEY, level);
	}

	@Override
	public int cooldownTicks(int level) {
		return Math.max(0, pickaxe.cooldownTicks().intAt(level));
	}

	/**
	 * The level in a few words for the upgrade tree, after the manager's "Diamond strength · Fortune II · ": "Vein Rip:
	 * up to 16 ores of a vein" (spec 5.1). The Fortune is the manager's to add (it is the item's, not the special's), so
	 * it isn't repeated here.
	 */
	@Override
	public Lines describeLines(int level) {
		int max = pickaxe.maxBlocks().intAt(level);
		return Lines.builder().always(pickaxe.name() + ": up to " + max + (max == 1 ? " ore" : " ores")).tipMore(" of a vein").build();
	}

	// ------------------------------------------------------------------ the steps

	private void step(ArmContext c, Run run, int now) {
		int elapsed = now - run.start;
		VanillaEffects vanilla = c.plugin().effects();
		for (int i = run.next; i < run.ores.length; i++) {
			Ore o = run.ores[i];
			if (o.breakTick - ArmProtocol.VEIN_CRACK_LEAD > elapsed) break; // the rest come later (the plan's ticks never go down)
			if (o.stage < 1) {
				crack(run, o, ArmProtocol.VEIN_CRACK_FIRST);
				if (vanilla != null && o.block.getType() == o.material) vanilla.crumble(run.player, o.centre(), o.block.getBlockData(), natives);
				o.stage = 1;
			}
			if (o.stage < 2 && elapsed >= o.breakTick - 1) {
				crack(run, o, ArmProtocol.VEIN_CRACK_SECOND);
				o.stage = 2;
			}
			if (o.stage < 3 && elapsed >= o.breakTick) {
				// several ores can break in one tick, and each one bills: the arm may have melted since the last
				if (!live(c, run)) {
					end(c, run, false);
					return;
				}
				tear(c, run, o);
				o.stage = 3;
				if (run.over) return;
			}
		}
		while (run.next < run.ores.length && run.ores[run.next].stage == 3) run.next++;
		if (run.next >= run.ores.length) {
			end(c, run, true);
			return;
		}
		if (elapsed % 2 == 0) draw(c, run, now);
	}

	/** An ore's turn: still what was planned, still near enough, it is broken as the player would break it. */
	private void tear(ArmContext c, Run run, Ore o) {
		Player p = run.player;
		Block b = o.block;
		Location centre = o.centre();
		boolean broke = false;
		// the plan kept to eight blocks from the eyes; the player may have walked on since, so a little more is allowed here
		Location eyes = p.getEyeLocation();
		boolean near = VeinPlanner.withinReach(eyes.getX(), eyes.getY(), eyes.getZ(), b.getX(), b.getY(), b.getZ(), VeinPlanner.TEAR_REACH);
		if (b.getType() == o.material && near) {
			BlockData data = b.getBlockData();
			boolean[] done = {false};
			c.asSpecialBreak(p, () -> done[0] = p.breakBlock(b));
			broke = done[0];
			if (broke) {
				// The game shows a break (its pieces and sound) to everyone but the breaker, who is meant to have seen it
				// coming apart under their own pick; this one wasn't, so the breaker gets the same level event (2001, the
				// spec's Effect.STEP_SOUND, which the API is removing in favour of this name).
				p.playEffect(b.getLocation(), Effect.DESTROY_BLOCK_WITH_SOUND, data);
				b.getWorld().playSound(centre, Sound.BLOCK_SCULK_SPREAD, 0.25F, 1.6F);
			}
		}
		if (broke) run.broken++;
		else {
			run.skipped++;
			if (!near) run.outOfReach++;
		}
		crack(run, o, 0F);
	}

	/** The crack on an ore, for everyone near: {@code progress} 0.45, 0.9, or 0 to clear it. */
	private static void crack(Run run, Ore o, float progress) {
		Location at = o.block.getLocation();
		int source = ArmProtocol.crackSourceId(run.player.getEntityId());
		for (Player v : run.world.getPlayers()) {
			if (v.getLocation().distanceSquared(at) <= CRACK_RANGE_SQ) v.sendBlockDamage(at, progress, source);
		}
	}

	/**
	 * The tendril to the ore being torn, for players whose game doesn't draw arms: the black skin from the arm for players
	 * with the pack (their own copy below their view for the owner), a line of the skin's grey dust for the rest.
	 */
	private void draw(ArmContext c, Run run, int now) {
		int i = run.next;
		while (i < run.ores.length && run.ores[i].stage == 3) i++;
		if (i >= run.ores.length) return;
		Location tip = SkinEffects.armTip(run.player), ore = run.ores[i].centre();
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendril(run.player, run.key, tip, ore, "stick", SkinEffects.ARM_SEGMENTS, TENDRIL_FROM_PX, TENDRIL_TO_PX, true, natives);
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null && (now - run.start) % LINE_EVERY == 0) {
			vanilla.line(run.player, tip, ore, new Particle.DustOptions(VanillaEffects.ARM_GREY, 1.0F), LINE_STEP, natives);
		}
	}

	/** The special is over, or stopped ({@code finished} false): cracks left on the ores are cleared, nothing is refunded. */
	private void end(ArmContext c, Run run, boolean finished) {
		if (run.over) return;
		run.over = true;
		runs.remove(run.player.getUniqueId(), run);
		for (Ore o : run.ores) {
			if (o.stage == 1 || o.stage == 2) crack(run, o, 0F);
		}
		SkinEffects skin = c.plugin().skin();
		if (skin != null) skin.tendrilEnd(run.key);
		if (!finished && run.player.isOnline()) {
			// an empty FX_VEIN cuts this ripper's veins short on clients that draw them: a reshape stops the rip while the
			// arm stays usable, so the client can't tell from S_ARM alone
			c.fx(run.player, new ArmFxMsg(run.player.getEntityId(), ArmProtocol.FX_VEIN, run.level, 0, run.seed, null, null, 0),
					ArmProtocol.fxRadius(ArmProtocol.FX_VEIN));
		}
		Player owner = run.player;
		if (owner.isOnline()) {
			// the player's own word on how it went: a partial rip or an empty creative one is no longer a silent mystery
			boolean creative = owner.getGameMode() == GameMode.CREATIVE;
			owner.sendActionBar(Component.text(VeinSummary.of(pickaxe.name(), run.group, run.ores.length, run.broken, run.outOfReach,
					run.skipped - run.outOfReach, finished, creative), NamedTextColor.GRAY));
		}
		if (c.config().report()) {
			c.report(pickaxe.name() + ": " + run.player.getName() + (finished ? " finished" : " stopped") + " after " + (now(c) - run.start) + " ticks: broke "
					+ run.broken + ", skipped " + run.skipped + " of " + run.ores.length);
		}
	}

	// ------------------------------------------------------------------ drops

	/** The ores' drops and their experience land with the player, as if the tendril carried them back. */
	private final class Drops implements Listener {
		/**
		 * The experience of a torn ore goes straight to the player, as the orbs of a hand-mined ore would reach them, instead of
		 * being left where the ore was (in rock, where no orb can be picked up). Last, so that a plugin that cancels the break or
		 * changes the experience has had its say. Creative breaks have none (vanilla), so there is nothing to give.
		 */
		@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
		public void onBreak(BlockBreakEvent event) {
			if (runs.isEmpty() || !pickaxe.pullDrops()) return;
			int exp = event.getExpToDrop();
			if (exp <= 0) return;
			Player p = event.getPlayer();
			Run run = runs.get(p.getUniqueId());
			Block b = event.getBlock();
			if (run == null || run.over || b.getWorld() != run.world || !run.planned.contains(new Pos(b.getX(), b.getY(), b.getZ()))) return;
			event.setExpToDrop(0);
			p.giveExp(exp, true); // with mending, as picking the orbs up would (the arm itself refuses it: ArmGuardListener.onMend)
		}


		@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
		public void onDrop(BlockDropItemEvent event) {
			if (runs.isEmpty() || !pickaxe.pullDrops()) return;
			Player p = event.getPlayer();
			Run run = runs.get(p.getUniqueId());
			Block b = event.getBlock();
			if (run == null || run.over || b.getWorld() != run.world || !run.planned.contains(new Pos(b.getX(), b.getY(), b.getZ()))) return;
			// The event's items are not in the world yet, and teleporting an entity that isn't spawned does nothing on Paper (rig 2026-11-22: every
			// stack stayed in the cavity), so the stacks are taken out of the event and dropped at the player's feet instead, at rest and ready to
			// be picked up.
			Location feet = p.getLocation().add(0, 0.25, 0);
			List<ItemStack> carried = new ArrayList<>(event.getItems().size());
			for (Item item : event.getItems()) carried.add(item.getItemStack());
			event.getItems().clear();
			for (ItemStack stack : carried) {
				p.getWorld().dropItem(feet, stack, dropped -> {
					dropped.setVelocity(new Vector());
					dropped.setPickupDelay(0);
				});
			}
		}
	}

	// ------------------------------------------------------------------ the world, for the planner

	/** The world as VeinPlanner sees it: ore groups by block, and whether this arm may break a block. */
	private final class WorldGrid implements GridView {
		private final World world;
		private final ItemStack arm;
		private final int minY, maxY;

		/** Where the owner's eyes were at F, which the planner's reach is measured from. */
		private final double eyeX, eyeY, eyeZ;

		WorldGrid(World world, ItemStack arm, Location eyes) {
			this.world = world;
			this.arm = arm;
			this.eyeX = eyes.getX();
			this.eyeY = eyes.getY();
			this.eyeZ = eyes.getZ();
			this.minY = world.getMinHeight();
			this.maxY = world.getMaxHeight();
		}

		/** No group outside the world's height or in a chunk that isn't loaded (the walk never loads one). */
		@Override
		public @Nullable String group(int x, int y, int z) {
			if (y < minY || y >= maxY || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
			return groups.get(world.getType(x, y, z));
		}

		@Override
		public boolean breakable(int x, int y, int z) {
			// too far for a break from the eyes counts as unbreakable: the walk doesn't plan it or go on through it, so the
			// tendrils drawn are the ores that really come out (2026-11-16)
			if (!VeinPlanner.withinReach(eyeX, eyeY, eyeZ, x, y, z, VeinPlanner.PLAN_REACH)) return false;
			Block b = world.getBlockAt(x, y, z);
			Material type = b.getType();
			return type.getHardness() >= 0 && !GAME_MASTER.contains(type) && b.isPreferredTool(arm);
		}
	}

	/**
	 * Every ore block to its group (spec 3.8): coal, iron, copper, gold (without nether gold), redstone, lapis, diamond
	 * and emerald, each with its deepslate kin; nether gold, nether quartz and ancient debris alone. Then
	 * arms.forms.pickaxe.special.extra-ore-groups, {@code name: [block, ...]}, where a block is a block id or a block tag
	 * as {@code #namespace:tag}: a new name makes a new group, a built-in group's name adds to it, and a block listed
	 * there leaves the group it had. Names that match no block are logged and left out.
	 */
	static Map<Material, String> oreGroups(Map<String, List<String>> extra, @Nullable Logger log) {
		Map<Material, String> out = new HashMap<>();
		add(out, "coal", Tag.COAL_ORES);
		add(out, "iron", Tag.IRON_ORES);
		add(out, "copper", Tag.COPPER_ORES);
		add(out, "gold", Tag.GOLD_ORES);
		add(out, "redstone", Tag.REDSTONE_ORES);
		add(out, "lapis", Tag.LAPIS_ORES);
		add(out, "diamond", Tag.DIAMOND_ORES);
		add(out, "emerald", Tag.EMERALD_ORES);
		// after the tags: the game's gold ores include nether gold, which is a vein of its own
		out.put(Material.NETHER_GOLD_ORE, "nether_gold");
		out.put(Material.NETHER_QUARTZ_ORE, "nether_quartz");
		out.put(Material.ANCIENT_DEBRIS, "ancient_debris");
		for (Map.Entry<String, List<String>> e : extra.entrySet()) {
			String group = e.getKey().trim().toLowerCase(Locale.ROOT);
			if (group.isEmpty()) continue;
			for (String name : e.getValue()) {
				List<Material> found = blocks(name);
				if (found.isEmpty() && log != null) {
					log.warning("abilities.yml arms.forms.pickaxe.special.extra-ore-groups." + e.getKey() + ": no block or block tag called '" + name
							+ "'; left out.");
				}
				for (Material m : found) out.put(m, group);
			}
		}
		return Map.copyOf(out);
	}

	private static void add(Map<Material, String> out, String group, @Nullable Tag<Material> tag) {
		if (tag == null) return;
		for (Material m : tag.getValues()) out.put(m, group);
	}

	/** The blocks a name stands for: a block id ({@code diamond_ore}, {@code minecraft:diamond_ore}) or a tag ({@code #minecraft:logs}). */
	private static List<Material> blocks(String name) {
		String n = name.trim();
		if (n.startsWith("#")) {
			NamespacedKey key = NamespacedKey.fromString(n.substring(1).toLowerCase(Locale.ROOT));
			Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
			return tag == null ? List.of() : List.copyOf(tag.getValues());
		}
		Material m = Material.matchMaterial(n);
		return m != null && m.isBlock() ? List.of(m) : List.of();
	}

	// ------------------------------------------------------------------ helpers

	private boolean live(ArmContext c, Run run) {
		return c.stillArmed(run.player, run.token) && run.player.getWorld() == run.world;
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}
}
