package dev.morphreborn.client.arm;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where each drawn arm's blade tip, middle and grip (and the lantern's lure) were in the world this frame, so the world
 * effects (healing tendrils into the blade, a vein's tendrils out of the pick) start exactly at the drawn tool instead of
 * a guess. The arm renderer records them while it submits a world or first-person hand draw, never from a GUI or a
 * picture-in-picture draw; the effects read them at the end of the world's submits.
 * <p>
 * Frames: everything recorded since the last {@link #nextFrame} counts as current. ArmFx calls {@code nextFrame()}
 * last thing in its COLLECT_SUBMITS handler. That handler runs after this frame's entity submits but before this
 * frame's first-person hand pass, so the third-person points it reads are this frame's and the first-person ones are
 * from the hand pass just before: both as good as current. A first-person point is the world point on the same pixel
 * as the tool on screen (the hand pass has its own field of view); the recorder works that out.
 * <p>
 * Render thread only.
 */
public final class ArmTips {
	public static final int TIP = 0, MID = 1, GRIP = 2, LURE = 3;
	/** The other accessories' points (4 up; the point is the low four bits of the key, so 4..15 are free): the shield, spyglass and flint and steel. */
	private static final int ACCESSORY_FIRST = 4;

	/** The point an accessory's renderer records ({@link #LURE} for the lantern, 4 and up for the rest in {@code ArmProtocol.ACCESSORIES}'s order), or -1 for an unknown id. */
	public static int accessory(String id) {
		int i = ArmProtocol.accessoryIndex(id);
		return i < 0 ? -1 : i == 0 ? LURE : ACCESSORY_FIRST + i - 1;
	}

	private static final class Point {
		double x, y, z;
		long frame = -1;
	}

	private static final Map<Long, Point> POINTS = new HashMap<>();
	private static long frame;

	private ArmTips() {}

	private static long key(int entityId, boolean firstPerson, int point) {
		return ((long) entityId << 8) | (firstPerson ? 0x10L : 0L) | (point & 0xF);
	}

	public static void record(int entityId, boolean firstPerson, int point, double x, double y, double z) {
		Point p = POINTS.computeIfAbsent(key(entityId, firstPerson, point), k -> new Point());
		p.x = x;
		p.y = y;
		p.z = z;
		p.frame = frame;
	}

	/** The point recorded in the current frame, or null when that arm wasn't drawn (culled, out of view, never armed). */
	public static @Nullable Vec3 get(int entityId, boolean firstPerson, int point) {
		Point p = POINTS.get(key(entityId, firstPerson, point));
		return p == null || p.frame != frame ? null : new Vec3(p.x, p.y, p.z);
	}

	/** Called by ArmFx at the end of its COLLECT_SUBMITS handler: what is recorded from here on belongs to the next frame. */
	public static void nextFrame() {
		frame++;
		// arms that stopped being drawn (players gone, out of view) leave their points behind; sweep them now and then
		if ((frame & 255) == 0) POINTS.values().removeIf(p -> frame - p.frame > 256);
	}

	static void clear() {
		POINTS.clear();
	}
}
