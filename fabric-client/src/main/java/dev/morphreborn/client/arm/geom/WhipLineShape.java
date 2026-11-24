package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.Loft;
import org.joml.Vector3f;

/**
 * The shape of a whip's cast line (decided 2026-11-16: the grapple look is a blend of the arcing chain
 * throw and the straight tendril shot). Pure maths, no game types, so it is tested alone; {@code WhipFx} draws it.
 * <p>
 * The line is a path from the whip's tip (s = 0) to the hook (s = 1), written in px <i>relative to the hook</i> with
 * {@code d} the whip's offset from it. Three shapes are mixed by two weights:
 * <ul>
 *   <li><b>taut</b> (both weights 0): the straight line {@code d·(1 − s)}. A shot, and the reel once the hook has caught;</li>
 *   <li><b>slack</b> (the hook floating or bobbing): vanilla's own hang, {@code y·(a² + a)/2} with {@code a = 1 − s}, as it
 *       was before the grapple look, so fishing reads like fishing;</li>
 *   <li><b>arc</b> (the hook flying): a quadratic curve from the tip to the hook whose tangent at the hook is the hook's
 *       own heading, so the line trails along the hook's real ballistic path as a thrown chain does, hunching up behind a
 *       falling hook and sagging under a rising one; plus a light sag of its own. Faster hooks fly straighter (the
 *       shot), slower ones hang more (the throw).</li>
 * </ul>
 * Over each rides the tendril's writhe (pinned at both ends), quieter the more the line is taut, and after the hook
 * catches, a short decaying shiver across the line as the arc snaps straight.
 * <p>
 * {@link Motion} is the per-owner memory that turns what the hook is doing (flying, caught, floating) into those weights
 * and eases between them, so the shapes never jump.
 */
public final class WhipLineShape {
	/** What a hook is doing: floating or bobbing in water, flying, or caught (a block, the ground or a creature). */
	public static final int RESTING = 0, FLYING = 1, ANCHORED = 2;

	/**
	 * Below this speed (blocks per tick) a hook not in water counts as still. A hook that <i>stopped</i> (see {@link #ARREST_DROP})
	 * is anchored at once; one that merely creeps to a halt must stay still for {@link #STILL_TICKS}.
	 */
	public static final float STILL_SPEED = 0.08F;
	/**
	 * The apex of a steep cast keeps a hook under {@link #STILL_SPEED} for up to about 7 ticks (gravity 0.03 and drag 0.92 move its
	 * vertical speed by at least 0.0236 a tick across the 0.16 band), so a bare stillness test cannot tell it from an anchor
	 * (fix round, 2026-11-16): the fallback waits longer than any apex lasts.
	 */
	public static final float STILL_TICKS = 8F;
	/**
	 * A hook that slows by more than this in one step (blocks per tick) and ends under {@link #STILL_SPEED} has hit something: in
	 * flight a tick changes the speed by at most 0.03 + 8% of it, so from a speed that would end under the still line that is
	 * under 0.045. The client's own hook zeroes its motion on a wall and the server holds an anchor at zero, so the stop is sharp.
	 */
	public static final float ARREST_DROP = 0.07F;
	/** Above {@link #SLOW} a hook's heading is trusted; by {@link #FAST} it is fully so (blocks per tick). */
	public static final float SLOW = 0.10F, FAST = 0.35F;
	/** The arc's biggest offset from the straight line, a share of the line's length (the midpoint moves half as far). */
	public static final float MAX_BULGE = 0.30F;
	/** The flying line's own sag: a share of its length at rest speed, at most {@link #MAX_SAG_PX} (px); fast hooks sag half as much. */
	public static final float SAG = 0.05F, MAX_SAG_PX = 14F;
	/** The shiver after a catch: its size (a share of the length, at most {@link #SHIVER_MAX_PX}), how fast it dies and its rate. */
	public static final float SHIVER = 0.05F, SHIVER_MAX_PX = 6F, SHIVER_DECAY = 0.7F, SHIVER_RATE = 2.6F, SHIVER_TICKS = 8F;

	private WhipLineShape() {}

	/** How far a hook's heading can be trusted at {@code speed} (blocks per tick): 0 below {@link #SLOW}, 1 from {@link #FAST}. */
	public static float trust(float speed) {
		float x = Math.clamp((speed - SLOW) / (FAST - SLOW), 0F, 1F);
		return x * x * (3F - 2F * x);
	}

	/** The shiver across a line {@code age} ticks after its hook caught, from −1 to 1: nothing before, dying away by {@link #SHIVER_TICKS}. */
	public static float shiver(float age) {
		if (!(age >= 0F) || age >= SHIVER_TICKS) return 0F;
		float fade = 1F - age / SHIVER_TICKS;
		return (float) (Math.exp(-SHIVER_DECAY * age) * Math.sin(SHIVER_RATE * age)) * fade;
	}

	/**
	 * What a hook is doing: {@link #RESTING} in water (unless it has caught a creature), {@link #ANCHORED} when it has caught
	 * something or has been still for {@link #STILL_TICKS}, else {@link #FLYING}.
	 */
	public static int modeOf(boolean inWater, boolean caught, float stillTicks) {
		if (inWater && !caught) return RESTING;
		return caught || stillTicks >= STILL_TICKS ? ANCHORED : FLYING;
	}

	/**
	 * One owner's line over time: the mode the hook is in, how long it has been still, and the weights of the slack and arc
	 * shapes easing toward that mode's. Ticks, on the arm clock (the caller passes the frame's {@code dt}).
	 */
	public static final class Motion {
		/** The slack and the arc weights now (their sum is at most 1; what is left is taut). */
		public float slack, arc;
		/** Ticks since the hook caught (large when it hasn't just). */
		public float snapAge = 99F;
		/** The arm clock when this was last advanced (the caller's own bookkeeping). */
		public float stamp;
		/**
		 * The hook's last trusted heading while it flew (blocks per tick; zero when it was too slow to have one). The line keeps
		 * trailing along it while the arc eases out after a catch, so the bulge and sag fade with the arc instead of jumping when
		 * the hook's own speed drops to nothing.
		 */
		public float hx, hy, hz;
		private int mode;
		private float still;
		private float lastSpeed = -1F;
		private boolean arrested;

		/** A line first seen in {@code mode} starts in its shape. */
		public Motion(int mode) {
			this.mode = mode;
			slack = mode == RESTING ? 1F : 0F;
			arc = mode == FLYING ? 1F : 0F;
		}

		public int mode() {
			return mode;
		}

		/** True while the line is taut for the reel: caught, with the arc gone. */
		public boolean anchored() {
			return mode == ANCHORED;
		}

		/**
		 * Advances {@code dt} ticks (0..2) with the hook's state: in water, caught (creature or ground) and its velocity
		 * (blocks per tick). The arc collapses in about two ticks when the hook catches (the snap) and builds in two when it
		 * flies; the slack follows over about five. A hook that stops dead (see {@link #ARREST_DROP}) is anchored on that
		 * frame; it stays so until it moves again.
		 */
		public void update(float dt, boolean inWater, boolean caught, float vx, float vy, float vz) {
			float raw = dt;
			dt = Math.clamp(dt, 0F, 2F);
			float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
			// a sample more than a tick and a half after the last may span several ticks of a lob's apex: not a sharp stop
			// a splashdown also slows a hook sharply (the water takes 70 percent of it), and that is a float, not an anchor
			if (speed >= STILL_SPEED || inWater) arrested = false;
			else if (lastSpeed - speed > ARREST_DROP && raw <= 1.5F) arrested = true;
			lastSpeed = speed;
			still = speed < STILL_SPEED ? still + dt : 0F;
			int next = modeOf(inWater, caught, arrested ? STILL_TICKS : still);
			if (next == FLYING) {
				if (speed >= SLOW) {
					hx = vx;
					hy = vy;
					hz = vz;
				} else {
					hx = hy = hz = 0F;
				}
			}
			if (next == ANCHORED && mode == FLYING) snapAge = 0F;
			else snapAge += dt;
			mode = next;
			float slackTo = mode == RESTING ? 1F : 0F, arcTo = mode == FLYING ? 1F : 0F;
			slack = approach(slack, slackTo, (slackTo > slack ? 0.2F : 0.3F) * dt);
			arc = approach(arc, arcTo, (arcTo > arc ? 0.5F : mode == ANCHORED ? 0.55F : 0.25F) * dt);
			arc = Math.min(arc, 1F - slack);
		}

		private static float approach(float v, float to, float step) {
			return v < to ? Math.min(to, v + step) : Math.max(to, v - step);
		}
	}

	/**
	 * A line's path as a curve (px relative to the hook), for {@link Loft}: {@link #aim} it at the whip, {@link #trail} it along
	 * the hook's heading, {@link #mix} in a {@link Motion}, then {@link #at}. Mutable and reused: nothing is allocated per frame.
	 */
	public static final class Shape implements Loft.CurveFn {
		/** The whip's offset from the hook, its length and the frame across it (px): {@code side} then {@code up}. */
		public float dx, dy, dz, len, sx, sy, sz, ux, uy, uz;
		/** The arc's control-point offset from the midpoint, and its sag. */
		public float bx, by, bz, sag;
		/** The mix: slack and arc weights, the writhe (px, already quieted by tension), its phase and the clock, the shiver (px). */
		public float slack, arc, writhe, phase, time, shiver;

		/** Points the line at the whip, {@code (dx, dy, dz)} px from the hook, and builds its frame across the line. */
		public Shape aim(float dx, float dy, float dz) {
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
			len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			bx = by = bz = sag = 0F;
			if (len < 1e-4F) {
				sx = 1F;
				sy = sz = 0F;
				ux = uz = 0F;
				uy = 1F;
				return this;
			}
			// side = dir × world up, up = side × dir: as the line's frame has always been
			float fx = dx / len, fy = dy / len, fz = dz / len;
			float cx = -fz, cy = 0F, cz = fx;
			float cl = (float) Math.sqrt(cx * cx + cz * cz);
			if (cl < 1e-3F) {
				cx = 1F;
				cz = 0F;
			} else {
				cx /= cl;
				cz /= cl;
			}
			sx = cx;
			sy = cy;
			sz = cz;
			float ox = cy * fz - cz * fy, oy = cz * fx - cx * fz, oz = cx * fy - cy * fx;
			float ol = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
			ux = ox / ol;
			uy = oy / ol;
			uz = oz / ol;
			return this;
		}

		/**
		 * Lays the arc along the hook's heading {@code (vx, vy, vz)} (blocks per tick; any scale of it will do for the
		 * direction): the control point sits behind the hook along its heading, so the curve arrives with the hook's own
		 * tangent. How far that is trusted grows with the hook's speed.
		 */
		public Shape trail(float vx, float vy, float vz) {
			float sp = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
			// (the caller hands in the hook's last flying heading while the arc eases out, so k is steady through a catch)
			float k = len < 1e-4F || !(sp > 1e-6F) ? 0F : trust(sp);
			sag = Math.min(SAG * len, MAX_SAG_PX) * (1F - 0.5F * k);
			if (k <= 0F) {
				bx = by = bz = 0F;
				return this;
			}
			// the control point sits behind the hook, |d|/2 back along its heading: C = −v̂·|d|/2, and the bulge is C − d/2
			float inv = len / sp;
			bx = -0.5F * (vx * inv + dx);
			by = -0.5F * (vy * inv + dy);
			bz = -0.5F * (vz * inv + dz);
			float m = (float) Math.sqrt(bx * bx + by * by + bz * bz), cap = MAX_BULGE * len;
			float scale = k * (m > cap ? cap / m : 1F);
			bx *= scale;
			by *= scale;
			bz *= scale;
			return this;
		}

		/**
		 * Takes the weights from {@code mo} and sets the writhe: {@code base} px when the line is slack, a third of that
		 * taut, with the shiver after a catch ({@code amp} px at most).
		 */
		public Shape mix(Motion mo, float base, float phase, float time) {
			slack = mo.slack;
			arc = mo.arc;
			float taut = Math.max(0F, 1F - slack - arc);
			writhe = base * (1F - 0.35F * arc - 0.65F * taut);
			this.phase = phase;
			this.time = time;
			shiver = Math.min(SHIVER * len, SHIVER_MAX_PX) * shiver(mo.snapAge);
			return this;
		}

		/** The line's point at {@code s} (0 at the whip, 1 at the hook), in px relative to the hook. */
		@Override
		public void at(float s, Vector3f out) {
			float a = 1F - s, as = a * s;
			float env = (float) Math.sin(Math.PI * s);
			float wr = writhe * env;
			float w1 = (float) Math.sin(phase + 7F * s + 0.35F * time) * wr;
			float w2 = 0.6F * (float) Math.cos(0.8F * phase + 5F * s + 0.28F * time) * wr + shiver * env;
			float arcK = 2F * as * arc;
			out.set(dx * a + arcK * bx + sx * w1 + ux * w2,
					dy * a - 0.5F * slack * dy * as + arcK * by - 4F * as * arc * sag + sy * w1 + uy * w2,
					dz * a + arcK * bz + sz * w1 + uz * w2);
		}
	}
}
