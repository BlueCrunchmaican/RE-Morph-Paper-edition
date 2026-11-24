package dev.morphreborn.client.arm.lantern;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;

/**
 * The warm red motes that drift in a lit lantern's beam (setting {@code lanternMotes}), so the beam can be seen in the dark
 * and not only where it lands: one dust mote every 4 ticks for every lit lantern, somewhere along the part of the beam
 * that is within 8 blocks of the camera, a little off its line. They come from the same shared state every viewer has (who
 * is lit, where they look), so everyone with the setting on sees everyone's.
 * <p>
 * The placing is plain maths ({@link #due}, {@link #nearRange}, {@link #pick}) and is tested without a game; only
 * {@link #spawn} touches the world.
 */
public final class LanternMotes {
	/** One mote every this many ticks per lantern. */
	public static final int PERIOD = 4;
	/** Motes only show within this many blocks of the camera (further out they'd be a pixel each, for nothing). */
	public static final double NEAR = 8.0;
	/** Motes start this far from the lure, so none sits on the lure itself. */
	public static final double FROM = 0.7;
	/** How far off the beam's line a mote may drift, blocks. */
	private static final double SPREAD = 0.22;
	/** The warm reds the motes take: ember, red, amber. */
	private static final int[] COLOURS = {0xE0452B, 0xC23B22, 0xFF7A33};

	private LanternMotes() {}

	/** Whether the lantern of the player with this entity id sheds a mote at game tick {@code tick} (staggered per player). */
	public static boolean due(long tick, int entityId) {
		return Math.floorMod(tick + entityId, (long) PERIOD) == 0L;
	}

	/**
	 * The stretch of the beam within {@code radius} of the camera, as distances along it: the beam leaves ({@code ex, ey,
	 * ez}) along the unit vector ({@code dx, dy, dz}) for {@code length} blocks, the camera is at ({@code cx, cy, cz}).
	 * Writes {@code [from, to]}, clipped to {@code [FROM, length]}, into {@code out} and returns true; false when no part
	 * of the beam is that close (or it is too short to have a mote).
	 */
	public static boolean nearRange(double ex, double ey, double ez, double dx, double dy, double dz, double length, double cx, double cy, double cz,
			double radius, double[] out) {
		if (!(length > FROM)) return false;
		double ox = ex - cx, oy = ey - cy, oz = ez - cz;
		// |o + t·d|² = r²  with d a unit vector
		double b = ox * dx + oy * dy + oz * dz;
		double c = ox * ox + oy * oy + oz * oz - radius * radius;
		double disc = b * b - c;
		if (disc < 0.0) return false;
		double root = Math.sqrt(disc);
		double from = Math.max(FROM, -b - root), to = Math.min(length, -b + root);
		if (!(from < to)) return false;
		out[0] = from;
		out[1] = to;
		return true;
	}

	/**
	 * Where along {@code [from, to]} a mote goes for the sample {@code u} in 0..1: eased toward the lure, so the beam is
	 * brightest where it starts and thins as it travels.
	 */
	public static double pick(double u, double from, double to) {
		double k = Math.min(1.0, Math.max(0.0, u));
		return from + (to - from) * k * k;
	}

	/** No mote is shed this close to the camera: a dust particle (0.55 to 0.95 wide) seen from a few tenths of a block fills a quarter of the screen. */
	public static final double CLEAR = 2.5;

	/** Whether the point ({@code x, y, z}) is far enough from the camera for a mote to be shed there. */
	public static boolean clearOfCamera(double x, double y, double z, double cx, double cy, double cz) {
		double dx = x - cx, dy = y - cy, dz = z - cz;
		return dx * dx + dy * dy + dz * dz >= CLEAR * CLEAR;
	}

	/**
	 * Sheds this tick's mote for a lantern whose beam leaves ({@code ex, ey, ez}) along the unit vector ({@code dx, dy,
	 * dz}) for {@code length} blocks, if any of it is within {@link #NEAR} of the camera at ({@code cx, cy, cz}).
	 */
	public static void spawn(ClientLevel level, double ex, double ey, double ez, double dx, double dy, double dz, double length, double cx, double cy,
			double cz) {
		double[] range = new double[2];
		if (!nearRange(ex, ey, ez, dx, dy, dz, length, cx, cy, cz, NEAR, range)) return;
		RandomSource r = level.getRandom();
		double t = pick(r.nextDouble(), range[0], range[1]);
		double x = ex + dx * t + (r.nextDouble() - 0.5) * 2.0 * SPREAD;
		double y = ey + dy * t + (r.nextDouble() - 0.5) * 2.0 * SPREAD;
		double z = ez + dz * t + (r.nextDouble() - 0.5) * 2.0 * SPREAD;
		if (!clearOfCamera(x, y, z, cx, cy, cz)) return; // the owner's own first person, right after a move, was the case (rig 2026-11-22)
		DustParticleOptions mote = new DustParticleOptions(COLOURS[r.nextInt(COLOURS.length)], 0.55F + r.nextFloat() * 0.4F);
		level.addParticle(mote, x, y, z, 0.0, 0.0, 0.0);
	}
}
