package dev.morphreborn.paper.ability;

/**
 * How far a short, fixed-range aimed ability (a bee's sting, an iron golem's toss, a creaking's scare, a frog's tongue,
 * infest and sort) reaches from a body larger than a player's. Bukkit-free so it can be tested.
 *
 * <p>The aim is traced from the eye, which a scaled body lifts far from its feet: a giant's eye is ten blocks up, so a
 * 3 block sting never met the cow at its ankle (rig B1-05). My call (2026-11-17): do not scale the range with
 * the body; just make it work. So the look still picks the target, but what decides whether it is close enough is the
 * gap between the target and the body's own hitbox edge, which is the ability's normal range and no more. Reach past
 * the body therefore stays what it always was.
 */
final class BodyReach {
	private BodyReach() {}

	/** An axis-aligned box: the player's body or a target's hitbox. */
	record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

	/**
	 * Whether the body is bigger than a player's. A player-sized (or smaller) body keeps the plain eye trace exactly as it
	 * was; the small tolerance keeps float noise on a 1.0 scale from counting as bigger.
	 */
	static boolean applies(double scale) {
		return scale > 1.0 + 1e-3;
	}

	/** The distance between two boxes' nearest points; 0 when they touch or overlap. */
	static double gap(Box a, Box b) {
		double dx = Math.max(0, Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()));
		double dy = Math.max(0, Math.max(a.minY() - b.maxY(), b.minY() - a.maxY()));
		double dz = Math.max(0, Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ()));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** True when the target is within {@code range} of the body's hitbox edge. */
	static boolean within(Box body, Box target, double range) {
		return gap(body, target) <= range;
	}

	/**
	 * How long the look must be traced to find every target {@link #within} reach: such a target is at most {@code range}
	 * from some point of the body, and that point is at most the eye's distance to the body's farthest corner away.
	 */
	static double traceLength(double range, Box body, double eyeX, double eyeY, double eyeZ) {
		double dx = Math.max(Math.abs(eyeX - body.minX()), Math.abs(eyeX - body.maxX()));
		double dy = Math.max(Math.abs(eyeY - body.minY()), Math.abs(eyeY - body.maxY()));
		double dz = Math.max(Math.abs(eyeZ - body.minZ()), Math.abs(eyeZ - body.maxZ()));
		return range + Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
