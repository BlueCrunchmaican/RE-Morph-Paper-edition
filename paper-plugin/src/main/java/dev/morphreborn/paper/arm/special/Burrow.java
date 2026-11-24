package dev.morphreborn.paper.arm.special;

import dev.morphreborn.paper.PluginSettings;
import dev.morphreborn.paper.arm.ArmProtocol;
import dev.morphreborn.paper.arm.api.ArmContext;
import dev.morphreborn.paper.arm.api.ArmFxMsg;
import dev.morphreborn.paper.arm.api.ArmSpecialModule;
import dev.morphreborn.paper.arm.api.SpecialCharge;
import dev.morphreborn.paper.arm.api.SpecialResult;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmConfig.Levels;
import dev.morphreborn.paper.arm.rules.BurrowPlane;
import dev.morphreborn.paper.arm.rules.VeinPlanner.Pos;
import dev.morphreborn.paper.effects.SkinEffects;
import dev.morphreborn.paper.effects.VanillaEffects;
import dev.morphreborn.paper.survival.SurvivalMode;
import dev.morphreborn.paper.tips.Lines;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Effect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;

/**
 * Burrow, the shovel arm's special (F; spec 3.9): the arm opens a window of 10, 15 or 20 seconds in which every block
 * the player digs takes the eight around it, across the face they dug. Dig the top of a dirt block and a 3 by 3 of
 * earth goes with it; dig a hillside's face and it is the face's 3 by 3. Its other half, Fortune at the arm's level
 * (flint from gravel, most of all), is on the item itself, so it counts for the dug block and for its neighbours alike.
 * <p>
 * F opens the window straight away (nothing to aim at, so there is no "no target"), pays the cost and starts the
 * cooldown, which is the window and {@code extra-cooldown} seconds more. S_ARM_FX BURROW is stateful: the ticks left,
 * sent when the window opens, every 20 ticks while it lasts and as 0 when it closes, so the clients draw the digging
 * claw exactly as long as it is open (and drop it themselves a second or so after the last word if the server goes
 * quiet).
 * <p>
 * The trigger is a BlockBreakEvent after everyone else has had their say (MONITOR, so a protection plugin's refusal
 * means no 3 by 3 either), except for breaks the specials make themselves (Vein Rip, Fell, and the neighbours Burrow
 * breaks: none of them starts another). The block must be one the arm digs as a shovel does. The face is the one of the
 * last BlockDamageEvent at that block (the face the mouse was on when the dig began), or, for a dig that never raised
 * one, the face the player is looking at. A neighbour goes if BurrowPlane says so: not air, not a tile entity (chests,
 * furnaces, spawners), the right tool for it, and no harder than the block dug plus half a point. The neighbours are
 * broken on the next tick, one by one through Player.breakBlock (so claims, Fortune and the block row's billing of 0.1
 * a block work as for digging by hand); one a plugin refuses, or that changed in the meantime, is skipped and costs
 * nothing.
 * <p>
 * The window ends when it runs out, or when the arm isn't formed and in hand (a retract, a reshape): nothing is refunded.
 */
public final class Burrow implements ArmSpecialModule {
	public static final String FORM = "shovel";
	/** Its row in survival.yml arm-cost.specials. */
	private static final String COST_KEY = "shovel";
	/** A neighbour further than this from the eyes when its turn comes is skipped (anticheats and claims expect reach). */
	private static final double MAX_BREAK_DISTANCE_SQ = 8 * 8;
	/** How far the look is traced for the face of a dig that raised no BlockDamageEvent. */
	private static final double FACE_REACH = 8;
	/** A remembered face is dropped after this long: a dig that never ended. */
	private static final int FACE_STALE_TICKS = 1200;
	/** The neighbours go a tick after the block dug, never inside its own break event. */
	private static final int BREAK_DELAY = 1;

	private @Nullable ArmContext ctx;
	/** Viewers whose game draws arms: every particle fallback leaves them out. */
	private Predicate<Player> natives = v -> false;
	private Settings settings = Settings.parse(Map.of());
	private final Map<UUID, Window> windows = new HashMap<>();
	/** The face each digging player last started on. */
	private final Map<UUID, Dug> faces = new HashMap<>();
	private final List<Pending> pending = new ArrayList<>();
	private final Events events = new Events();

	/** arms.forms.shovel.burrow as this module reads it: the window and the extra cooldown, in ticks. */
	private record Settings(String name, Levels windowTicks, int extraCooldownTicks) {
		static Settings parse(Map<String, Object> s) {
			Map<String, Object> burrow = map(s.get("burrow"));
			Levels seconds = levels(burrow.get("window"), new Levels(10, 15, 20));
			return new Settings(string(burrow.get("name"), "Burrow"), new Levels(Math.round(Math.max(0, seconds.l1()) * 20), Math.round(Math.max(0, seconds.l2()) * 20),
					Math.round(Math.max(0, seconds.l3()) * 20)), (int) Math.max(0, Math.round(number(burrow.get("extra-cooldown"), 10) * 20)));
		}
	}

	/** One open window. */
	private static final class Window {
		final Player player;
		final long token;
		final World world;
		final int level, seed, start, end;
		boolean over;
		int lastSent;
		int digs, broken, skipped;

		Window(Player player, long token, int level, int seed, int start, int end) {
			this.player = player;
			this.token = token;
			this.world = player.getWorld();
			this.level = level;
			this.seed = seed;
			this.start = start;
			this.end = end;
			this.lastSent = start;
		}
	}

	/** The block a player started digging, and the face their mouse was on. */
	private record Dug(World world, int x, int y, int z, BlockFace face, int tick) {}

	/** A neighbour waiting for its turn: where, and what it was when the dig that took it broke. */
	private record Planned(Pos pos, Material material) {}

	/** The neighbours of one dug block, to break at {@code at}. */
	private static final class Pending {
		final Window window;
		final double centreHardness;
		final int at;
		final List<Planned> blocks;

		Pending(Window window, double centreHardness, int at, List<Planned> blocks) {
			this.window = window;
			this.centreHardness = centreHardness;
			this.at = at;
			this.blocks = blocks;
		}
	}

	public Burrow() {}

	@Override
	public String form() {
		return FORM;
	}

	@Override
	public void init(ArmContext ctx) {
		this.ctx = ctx;
		this.natives = ctx::drawsArms;
	}

	@Override
	public void load(Map<String, Object> formSection) {
		settings = Settings.parse(formSection == null ? Map.of() : formSection);
	}

	@Override
	public @Nullable Listener listener() {
		return events;
	}

	@Override
	public boolean breaksBlocks() {
		return true;
	}

	@Override
	public SpecialResult fire(Player p, int level, boolean sneaking, SpecialCharge charge) {
		ArmContext c = ctx;
		if (c == null) return SpecialResult.refused(settings.name() + " isn't ready yet.");
		long cost = costMilli(level);
		int cooldown = cooldownTicks(level);
		if (!charge.pay(cost, cooldown)) return new SpecialResult(SpecialResult.Outcome.REFUSED, null); // pay showed why

		int now = now(c), ticks = windowTicks(level);
		Window old = windows.get(p.getUniqueId());
		if (old != null) end(c, old, false); // the gate refuses while one runs, so only ever a leftover
		Window w = new Window(p, c.token(p), level, ThreadLocalRandom.current().nextInt(1 << 30), now, now + ticks);
		windows.put(p.getUniqueId(), w);

		p.swingMainHand();
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_DIG, 0.6F, 1.3F);
		sendWindow(c, w, ticks);
		// the pack has no claw of its own: players with it see the puff at the hand, as everyone without the mod does
		VanillaEffects vanilla = c.plugin().effects();
		if (vanilla != null) vanilla.armBurst(SkinEffects.armTip(p), natives);
		if (c.config().report()) {
			c.report(settings.name() + ": " + p.getName() + " at level " + level + " opened a window of " + ticks + " ticks; cost " + cost + " milli, cooldown "
					+ cooldown + " ticks");
		}
		return SpecialResult.FIRED;
	}

	@Override
	public void tick() {
		ArmContext c = ctx;
		if (c == null || (windows.isEmpty() && pending.isEmpty())) return;
		int now = now(c);
		// the neighbours first: a window that closes this tick still lets the digs it saw finish. The due ones are taken out
		// before any is broken, so a break that raises events can't change the list under the walk.
		List<Pending> due = null;
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
			Pending d = it.next();
			if (d.at > now) continue;
			it.remove();
			if (due == null) due = new ArrayList<>();
			due.add(d);
		}
		if (due != null) {
			for (Pending d : due) {
				if (live(c, d.window)) breakNeighbours(c, d, now);
			}
		}
		for (Window w : List.copyOf(windows.values())) {
			if (w.over) continue;
			if (!live(c, w)) {
				end(c, w, false);
			} else if (now >= w.end) {
				end(c, w, true);
			} else if (now - w.lastSent >= ArmProtocol.DRAIN_RESEND_TICKS) {
				sendWindow(c, w, w.end - now);
			}
		}
		faces.values().removeIf(d -> now - d.tick() > FACE_STALE_TICKS);
	}

	@Override
	public boolean running(Player p) {
		Window w = windows.get(p.getUniqueId());
		return w != null && !w.over;
	}

	@Override
	public void abort(Player p) {
		ArmContext c = ctx;
		Window w = windows.get(p.getUniqueId());
		if (c != null && w != null) end(c, w, false);
		pending.removeIf(d -> d.window.player.getUniqueId().equals(p.getUniqueId()));
	}

	@Override
	public void abortAll() {
		ArmContext c = ctx;
		if (c == null) return;
		for (Window w : List.copyOf(windows.values())) end(c, w, false);
		pending.clear();
		faces.clear();
	}

	@Override
	public String specialName(int level) {
		return settings.name();
	}

	@Override
	public long costMilli(int level) {
		return config().costs().special(COST_KEY, level);
	}

	/** The window and the extra cooldown after it. */
	@Override
	public int cooldownTicks(int level) {
		return windowTicks(level) + settings.extraCooldownTicks();
	}

	/**
	 * The level in a few words for the upgrade tree, after the manager's "Diamond strength · Fortune II · ": "Burrow:
	 * 3x3 for 15 s" (spec 5.1). The Fortune is the manager's to add (it is the item's, not the special's).
	 */
	@Override
	public Lines describeLines(int level) {
		// the 3x3 is what the window is, and the seconds are its length: both the level's numbers, so all of it stays
		return Lines.always(settings.name() + ": 3x3 for " + SurvivalMode.number(windowTicks(level) / 20.0) + " s");
	}

	private int windowTicks(int level) {
		return (int) Math.max(0, Math.round(settings.windowTicks().at(level)));
	}

	// ------------------------------------------------------------------ the window

	/** S_ARM_FX BURROW: the ticks left in the window; 0 says it is closed. */
	private void sendWindow(ArmContext c, Window w, int ticksLeft) {
		c.fx(w.player, new ArmFxMsg(w.player.getEntityId(), ArmProtocol.FX_BURROW, w.level, Math.max(0, ticksLeft), w.seed, null, null, 0),
				ArmProtocol.fxRadius(ArmProtocol.FX_BURROW));
		w.lastSent = now(c);
	}

	/** The window is over, or stopped ({@code finished} false): the clients are told it is closed. Nothing is refunded. */
	private void end(ArmContext c, Window w, boolean finished) {
		if (w.over) return;
		w.over = true;
		windows.remove(w.player.getUniqueId(), w);
		faces.remove(w.player.getUniqueId());
		if (w.player.isOnline()) sendWindow(c, w, 0);
		if (c.config().report()) {
			c.report(settings.name() + ": " + w.player.getName() + (finished ? " closed" : " stopped") + " after " + (now(c) - w.start) + " ticks: " + w.digs
					+ " dug, broke " + w.broken + " around them, skipped " + w.skipped);
		}
	}

	// ------------------------------------------------------------------ the trigger

	/**
	 * A block was dug inside an open window: pick its neighbours, and queue them for the next tick. Runs after every
	 * plugin that could have cancelled the break.
	 */
	private void dug(BlockBreakEvent event) {
		ArmContext c = ctx;
		if (c == null) return;
		Player p = event.getPlayer();
		Window w = windows.get(p.getUniqueId());
		Block b = event.getBlock();
		if (w == null || w.over || b.getWorld() != w.world || c.inSpecialBreak(p) || !c.stillArmed(p, w.token)) return;
		ArmConfig cfg = c.config();
		if (!PluginSettings.WorldRule.valueOf(cfg.blockSpecials().name()).allows(w.world)) return;
		ItemStack arm = p.getInventory().getItemInMainHand();
		double hardness = b.getType().getHardness();
		if (!BurrowPlane.startsBurrow(b.isPreferredTool(arm), hardness)) return;
		BlockFace face = faceOf(c, p, b);
		if (face == null) return;
		List<Pos> ring = BurrowPlane.ring(face.getModX(), face.getModY(), face.getModZ(), b.getX(), b.getY(), b.getZ());
		List<Planned> blocks = new ArrayList<>(8);
		int minY = w.world.getMinHeight(), maxY = w.world.getMaxHeight();
		for (Pos pos : ring) {
			if (pos.y() < minY || pos.y() >= maxY || !w.world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue;
			Block n = w.world.getBlockAt(pos.x(), pos.y(), pos.z());
			Material type = n.getType();
			if (BurrowPlane.accepts(hardness, type.getHardness(), !type.isAir() && n.isPreferredTool(arm), isTile(n), type.isAir())) blocks.add(new Planned(pos, type));
		}
		w.digs++;
		if (blocks.isEmpty()) return;
		pending.add(new Pending(w, hardness, now(c) + BREAK_DELAY, blocks));
	}

	/** The face a block was dug across: the one its dig began on, else the one the player is looking at; null if neither says. */
	private @Nullable BlockFace faceOf(ArmContext c, Player p, Block b) {
		Dug d = faces.get(p.getUniqueId());
		if (d != null && d.world() == b.getWorld() && d.x() == b.getX() && d.y() == b.getY() && d.z() == b.getZ()) return d.face();
		RayTraceResult r = p.rayTraceBlocks(FACE_REACH, FluidCollisionMode.NEVER);
		if (r != null && b.equals(r.getHitBlock())) return r.getHitBlockFace();
		return null;
	}

	/** A chest, a furnace, a sign, a spawner: anything with a tile entity. The live block is looked at, not a copy. */
	private static boolean isTile(Block b) {
		return b.getState(false) instanceof TileState;
	}

	/** The neighbours' turn: each one still what it was, near enough, broken as the player would break it. */
	private void breakNeighbours(ArmContext c, Pending d, int now) {
		Window w = d.window;
		Player p = w.player;
		ItemStack arm = p.getInventory().getItemInMainHand();
		for (Planned planned : d.blocks) {
			// each break bills, and the arm may have melted since the last one
			if (!live(c, w)) return;
			Pos pos = planned.pos();
			Block b = w.world.getBlockAt(pos.x(), pos.y(), pos.z());
			Location centre = new Location(w.world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
			boolean broke = false;
			// near enough is asked first, so a block far away (the player was moved) never loads its chunk to be looked at
			if (centre.distanceSquared(p.getEyeLocation()) <= MAX_BREAK_DISTANCE_SQ && b.getType() == planned.material()
					&& BurrowPlane.accepts(d.centreHardness, b.getType().getHardness(), b.isPreferredTool(arm), isTile(b), b.getType().isAir())) {
				BlockData data = b.getBlockData();
				boolean[] done = {false};
				c.asSpecialBreak(p, () -> done[0] = p.breakBlock(b));
				broke = done[0];
				// the game shows a break to everyone but the breaker, who didn't see this one coming apart under their own
				// spade, so the breaker gets the same level event
				if (broke) p.playEffect(b.getLocation(), Effect.DESTROY_BLOCK_WITH_SOUND, data);
			}
			if (broke) w.broken++;
			else w.skipped++;
		}
	}

	private final class Events implements Listener {
		/** Remembers the face each digging player's mouse is on, for the window's owner only. */
		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onDamage(BlockDamageEvent event) {
			ArmContext c = ctx;
			if (c == null || windows.isEmpty()) return;
			Player p = event.getPlayer();
			if (!windows.containsKey(p.getUniqueId())) return;
			Block b = event.getBlock();
			faces.put(p.getUniqueId(), new Dug(b.getWorld(), b.getX(), b.getY(), b.getZ(), event.getBlockFace(), now(c)));
		}

		@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
		public void onBreak(BlockBreakEvent event) {
			if (windows.isEmpty()) return;
			dug(event);
		}
	}

	// ------------------------------------------------------------------ helpers

	private ArmConfig config() {
		ArmContext c = ctx;
		return c == null ? ArmConfig.DEFAULTS : c.config();
	}

	private boolean live(ArmContext c, Window w) {
		return c.stillArmed(w.player, w.token) && w.player.getWorld() == w.world;
	}

	/** The server's own tick count: steps are timed against it whichever order the manager ticks things in. */
	private static int now(ArmContext c) {
		return c.plugin().getServer().getCurrentTick();
	}

	// ------------------------------------------------------------------ settings helpers (plain values, as the file gave them)

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(@Nullable Object v) {
		return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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

	/** Three levels from a list (fewer entries repeat the last; a bad entry takes that level's default) or one number for all three. */
	private static Levels levels(@Nullable Object v, Levels fallback) {
		if (v instanceof Number || v instanceof String) {
			double d = number(v, Double.NaN);
			return Double.isNaN(d) ? fallback : new Levels(d, d, d);
		}
		if (!(v instanceof List<?> list) || list.isEmpty()) return fallback;
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) out[i] = number(list.get(Math.min(i, list.size() - 1)), fallback.at(i + 1));
		return new Levels(out[0], out[1], out[2]);
	}
}
