package dev.morphreborn.paper.arm.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Who Draining Cut's swipe reaches: a flat arc centred on where the player looks, out to {@code reach} from the eyes.
 * Reach is measured to the nearest point of a creature's box, not its centre, so a big creature whose side is in reach
 * is cut like a vanilla hit would reach it; the arc and the vertical band are measured to its centre.
 * <p>
 * The hits come back in the order the blade sweeps through them: from the right of the arc to the left for a
 * right-handed player (the vanilla swing), mirrored for a left-handed one, with the nearer of two at the same angle
 * first. {@link Hit#angleNorm} is where along the sweep each one is (0 the start, 1 the end), which spreads the cuts over
 * ticks 1 to 3. When more are in reach than the level may cut, the nearest are kept. Pure, so it is unit tested.
 */
public final class ArcTargets {
	private ArcTargets() {}

	/** A creature in range: its entity id, its box centre and its box. */
	public record Candidate(int id, double cx, double cy, double cz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

	/** A creature the swipe cuts: how far along the sweep (0..1) and how far from the eyes (to its box). */
	public record Hit(int id, double angleNorm, double dist) {}

	/**
	 * @param yawDeg the player's yaw as the game has it (0 looks along +z, 90 along -x)
	 * @param arcDeg the arc's whole width (120: 60 either side of the look)
	 * @param band how far a centre may be above or below the eyes
	 * @param max the most creatures cut
	 */
	public static List<Hit> select(double ex, double ey, double ez, double yawDeg, double reach, double arcDeg, double band, int max, boolean leftHanded,
			List<Candidate> cs) {
		if (max <= 0 || reach <= 0 || arcDeg <= 0) return List.of();
		double yaw = Math.toRadians(yawDeg);
		// look along the ground, and the player's right (look x up)
		double lx = -Math.sin(yaw), lz = Math.cos(yaw);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double half = Math.min(180, arcDeg / 2);
		List<Hit> in = new ArrayList<>();
		for (Candidate c : cs) {
			if (Math.abs(c.cy() - ey) > band) continue;
			double dist = boxDistance(ex, ey, ez, c);
			if (dist > reach) continue;
			double dx = c.cx() - ex, dz = c.cz() - ez;
			// a centre right on the eyes (the player stands inside it) counts as straight ahead
			double signed = dx * dx + dz * dz < 1e-12 ? 0 : Math.toDegrees(Math.atan2(dx * rx + dz * rz, dx * lx + dz * lz)); // + to the right
			if (Math.abs(signed) > half + 1e-9) continue;
			double fromStart = leftHanded ? signed + half : half - signed;
			in.add(new Hit(c.id(), Math.clamp(fromStart / (2 * half), 0, 1), dist));
		}
		if (in.size() > max) {
			in.sort(Comparator.comparingDouble(Hit::dist).thenComparingDouble(Hit::angleNorm).thenComparingInt(Hit::id));
			in = new ArrayList<>(in.subList(0, max));
		}
		in.sort(Comparator.comparingDouble(Hit::angleNorm).thenComparingDouble(Hit::dist).thenComparingInt(Hit::id));
		return List.copyOf(in);
	}

	/** From the eyes to the nearest point of the box (0 inside it). */
	public static double boxDistance(double ex, double ey, double ez, Candidate c) {
		double dx = nearest(ex, c.minX(), c.maxX()) - ex, dy = nearest(ey, c.minY(), c.maxY()) - ey, dz = nearest(ez, c.minZ(), c.maxZ()) - ez;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** {@code v} clamped into the box side (Math.clamp would throw on a box given the wrong way round). */
	private static double nearest(double v, double a, double b) {
		return Math.max(Math.min(a, b), Math.min(Math.max(a, b), v));
	}

	/** The tick after F a hit lands on: t1 at the start of the sweep to t3 at its end (spec 3.7). */
	public static int hitTick(double angleNorm) {
		return 1 + (int) Math.floor(2 * Math.clamp(angleNorm, 0, 1));
	}
}
