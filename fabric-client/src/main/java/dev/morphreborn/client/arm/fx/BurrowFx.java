package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.model.ArmReactions;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Burrow (S_ARM_FX BURROW, stateful): while the shovel's window is open, the spade shows it out in the world too.
 * <ul>
 *   <li>The ring: six short tendrils standing round the leaf's tip (the spade's drawn tip, wherever the arm renderer put
 *       it this frame), turning slowly and writhing, black with a red one among them, opening over 3 ticks when the window
 *       starts and closing over 3 when it ends.</li>
 *   <li>The claw print: while the owner is digging a block (their own game says so; for everyone else the crack the
 *       server shows on the block says so), eight red tendrils crawl across the face being dug, from its centre to the
 *       centre of each of the eight neighbours in that plane, the 3 by 3 the window will break, growing over 3 ticks and
 *       drawn back over 2 when the digging stops. Which face: from the owner's own hit result, or for others by looking
 *       along their eyes' direction.</li>
 * </ul>
 * The effect is resent while the window lasts ({@code ticks} = what is left) and ends with a resend of 0; one not
 * heard from for the protocol's stateful timeout is dropped. Everything shown is from that effect and the world's own
 * cracks, so every viewer sees the same.
 */
public final class BurrowFx implements FxHandler {
	/** Ticks the ring takes to open and to close, the print to grow and to draw back. */
	static final float RING_OPEN = 3F, RING_CLOSE = 3F, PRINT_GROW = 3F, PRINT_BACK = 2F;
	/** The ring: how many tendrils, the radius it stands at (blocks), how far each reaches, and its turn (radians a tick). */
	private static final int RING = 6;
	private static final float RING_RADIUS = 0.16F, RING_REACH = 0.34F, RING_TURN = 0.07F;
	/** Widths in px. */
	private static final float RING_FROM_PX = 1.1F, RING_TO_PX = 0.35F, PRINT_FROM_PX = 1.1F, PRINT_TO_PX = 0.6F;
	/** The most windows shown (players with a shovel's window open), and how far a block can be dug from (blocks). */
	static final int MAX = 8;
	private static final double REACH = 8.0;

	private static final class Window {
		final int origin;
		final long opened;
		long heard;
		/** Ticks left as the server last said. */
		int left;
		/** When the window ended (-1: open): the ring closes from then. */
		long closedAt = -1;
		/** Digging: since when (-1: not), when it stopped, the block, the face (a Direction's ordinal). */
		long diggingSince = -1, stoppedAt = Long.MIN_VALUE / 2;
		long block;
		int face = Direction.UP.ordinal();

		Window(int origin, long now, int left) {
			this.origin = origin;
			this.opened = now;
			this.heard = now;
			this.left = left;
		}

		boolean open() {
			return closedAt < 0;
		}
	}

	private final Map<Integer, Window> windows = new HashMap<>();

	public BurrowFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_BURROW};
	}

	@Override
	public void start(ArmFxEvent e) {
		long now = e.receivedTick();
		Window w = windows.get(e.origin());
		inHand(e, now);
		if (e.ends()) {
			if (w != null && w.open()) w.closedAt = now;
			return;
		}
		if (w == null || !w.open()) {
			if (w == null && windows.size() >= MAX) return;
			w = new Window(e.origin(), now, e.ticks());
			windows.put(e.origin(), w);
		}
		w.heard = now;
		w.left = e.ticks();
	}

	/**
	 * The shovel in hand: the window is a special in its owner's reactions, so the leaf splits into its claws and stays so
	 * for as long as the window is open. The effect is resent while it lasts; a resend extends the window already running
	 * (so the claws, which open over their first ticks, never close and open again), and the effect with no ticks left ends it.
	 */
	private static void inHand(ArmFxEvent e, long now) {
		ArmReactions.Reaction r = FellFx.reaction(e.origin(), now);
		if (e.ends()) {
			r.endSpecial(ArmProtocol.FX_BURROW, now);
		} else if (r.specialKind == ArmProtocol.FX_BURROW && now < r.specialAt + r.specialLength) {
			r.specialLength = now - r.specialAt + Math.max(1, e.ticks());
		} else {
			r.special(ArmProtocol.FX_BURROW, now, Math.max(1, e.ticks()));
		}
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		Minecraft mc = Minecraft.getInstance();
		for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
			Window w = it.next();
			if (w.open()) {
				if (now - w.heard > ArmProtocol.STATEFUL_TIMEOUT_TICKS || !armed(w.origin)) w.closedAt = now;
			} else if (now - w.closedAt > RING_CLOSE + PRINT_BACK + 1) {
				it.remove();
				continue;
			}
			if (mc != null) watchDigging(mc, w, now);
		}
	}

	@Override
	public void clear() {
		windows.clear();
	}

	/** Whether the digger's arm is still out and usable (its S_ARM says so); a window can't go on without it. */
	private static boolean armed(int origin) {
		ArmState state = Arms.stateByEntity(origin);
		return state != null && ArmProtocol.usable(state.state);
	}

	// ------------------------------------------------------------------ pure timing

	/** The ring's opening, 0..1: out over {@link #RING_OPEN} ticks from the window's start, in over {@link #RING_CLOSE} from its end. */
	static float ring(float sinceOpen, float sinceClose) {
		float open = ArmMath.sm(sinceOpen / RING_OPEN);
		return sinceClose < 0F ? open : Math.min(open, 1F - ArmMath.sm(sinceClose / RING_CLOSE));
	}

	/** The print's reach, 0..1: growing over 3 ticks from when digging began, drawn back over 2 once it stopped. */
	static float print(float sinceDig, float sinceStop) {
		float grow = ArmMath.sm(sinceDig / PRINT_GROW);
		return sinceStop < 0F ? grow : Math.min(grow, 1F - ArmMath.sm(sinceStop / PRINT_BACK));
	}

	/**
	 * The eight neighbours of a face's centre in its plane (the other eight of the 3 by 3 the window breaks), as offsets
	 * in blocks along the plane's two axes, (a, b) pairs, into {@code out} (16 floats): never (0, 0), every other
	 * combination of -1, 0 and 1 once.
	 */
	static void neighbours(float[] out) {
		int n = 0;
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				if (i == 0 && j == 0) continue;
				out[2 * n] = i;
				out[2 * n + 1] = j;
				n++;
			}
		}
	}

	// ------------------------------------------------------------------ what the digger is doing

	/** Notes this tick whether {@code w}'s owner is digging, which block and from which face. */
	private static void watchDigging(Minecraft mc, Window w, long now) {
		ClientLevel level = mc.level;
		if (level == null) return;
		boolean digging = false;
		if (mc.player != null && mc.player.getId() == w.origin) {
			MultiPlayerGameMode gm = mc.gameMode;
			if (gm != null && gm.isDestroying() && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				digging = true;
				w.block = hit.getBlockPos().asLong();
				w.face = hit.getDirection().ordinal();
			}
		} else {
			long cracked = crackedBy(level, w.origin);
			if (cracked != Long.MIN_VALUE) {
				digging = true;
				w.block = cracked;
				w.face = faceOf(level, w.origin, cracked);
			}
		}
		if (digging) {
			if (w.diggingSince < 0) w.diggingSince = now;
		} else if (w.diggingSince >= 0) {
			w.diggingSince = -1;
			w.stoppedAt = now;
		}
	}

	/** The block {@code id} is cracking (the server shows others' digging as a crack carrying their id), or MIN_VALUE for none. */
	private static long crackedBy(ClientLevel level, int id) {
		for (Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>> e : level.destructionProgress().long2ObjectEntrySet()) {
			for (BlockDestructionProgress p : e.getValue()) {
				if (p.getId() == id) return p.getPos().asLong();
			}
		}
		return Long.MIN_VALUE;
	}

	/**
	 * The face of {@code block} that {@code id} is digging: where their look meets it, else the face toward their eyes. The
	 * look is the client's own copy of the entity's, as accurate as everything else drawn of them.
	 */
	private static int faceOf(ClientLevel level, int id, long block) {
		Entity e = level.getEntity(id);
		BlockPos pos = BlockPos.of(block);
		if (e == null) return Direction.UP.ordinal();
		Vec3 eye = e.getEyePosition(1F), look = e.getViewVector(1F);
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit.getDirection().ordinal();
		Vec3 centre = Vec3.atCenterOf(pos);
		return Direction.getApproximateNearest(eye.x - centre.x, eye.y - centre.y, eye.z - centre.z).ordinal();
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void collect(ArmFxFrame f) {
		if (windows.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		for (Window w : windows.values()) {
			ArmTint.owner(w.origin); // this effect is drawn in its owner's colour
			float sinceOpen = Arms.age(w.opened, pt);
			float sinceClose = w.open() ? -1F : Arms.age(w.closedAt, pt);
			float ring = ring(sinceOpen, sinceClose);
			if (ring > 0.01F) ring(f, level, w, ring);
			float pr = w.diggingSince >= 0 ? print(Arms.age(w.diggingSince, pt), -1F) : print(1000F, Arms.age(w.stoppedAt, pt));
			if (pr > 0.01F) claws(f, level, w, pr);
		}
	}

	/** The ring of tendrils round the spade's tip. */
	private static void ring(ArmFxFrame f, ClientLevel level, Window w, float open) {
		Vec3 tip = ArmFx.anchor(w.origin, ArmTips.TIP);
		if (tip == null || !TendrilFx.finite(tip)) return;
		boolean own = ArmFx.isOwnFirstPerson(w.origin, f.firstPersonCamera());
		float scale = own ? 0.6F : 1F;
		float time = f.time();
		int light = TendrilFx.light(level, tip, tip);
		// the ring stands in a plane across the way the owner looks (the spade points the way they dig)
		Entity e = level.getEntity(w.origin);
		Vec3 look = e == null ? new Vec3(0, 0, 1) : e.getViewVector(f.partialTick());
		Vec3 up = Math.abs(look.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 a = look.cross(up).normalize(), b = look.cross(a).normalize();
		for (int k = 0; k < RING; k++) {
			double ang = RING_TURN * time + ArmMath.TAU * k / RING;
			Vec3 radial = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
			Vec3 from = tip.add(radial.scale(RING_RADIUS * scale * open));
			float writhe = (float) Math.sin(0.2 * time + 1.7 * k);
			Vec3 to = from.add(radial.scale(RING_REACH * 0.5 * scale)).add(look.scale(RING_REACH * (0.7 + 0.2 * writhe) * scale));
			ArmLayer layer = k == 3 ? ArmLayer.RED : ArmLayer.BLACK;
			TendrilFx.draw(f, from, to, open, w.origin * 0.37F + k * 2.1F, RING_FROM_PX * scale, RING_TO_PX * scale, 0F, 0F, layer, light);
		}
	}

	/** The claw print on the face being dug: eight tendrils crawling from its centre to the neighbours' centres. */
	private static void claws(ArmFxFrame f, ClientLevel level, Window w, float reach) {
		BlockPos pos = BlockPos.of(w.block);
		Direction face = Direction.values()[Math.clamp(w.face, 0, 5)];
		Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		Vec3 centre = Vec3.atCenterOf(pos).add(n.scale(0.5 + 0.03));
		// the plane's two axes
		Vec3 u = face.getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 v = n.cross(u);
		float[] pairs = new float[16];
		neighbours(pairs);
		int light = TendrilFx.light(level, centre, centre.add(n));
		float time = f.time();
		for (int k = 0; k < 8; k++) {
			Vec3 to = centre.add(u.scale(pairs[2 * k])).add(v.scale(pairs[2 * k + 1]));
			boolean diagonal = pairs[2 * k] != 0 && pairs[2 * k + 1] != 0;
			TendrilFx.draw(f, centre, to, reach, w.origin * 0.37F + k * 1.3F + 0.05F * time, PRINT_FROM_PX, diagonal ? PRINT_TO_PX * 0.8F : PRINT_TO_PX, 0F, 0F,
					ArmLayer.RED, light);
		}
	}

	/** Test hook: whether {@code origin} has a window open, and whether it is digging. */
	boolean[] stateOf(int origin) {
		Window w = windows.get(origin);
		return w == null ? null : new boolean[] {w.open(), w.diggingSince >= 0};
	}
}
