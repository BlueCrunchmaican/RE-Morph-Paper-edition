package dev.morphreborn.paper.ability;

import dev.morphreborn.paper.MorphRebornPlugin;
import io.papermc.paper.entity.TeleportFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Devouring Frenzy's hover (my idea): while in a frenzy, Space lifts the player on tendrils up to its level's
 * {@code hover} blocks above the ground (4, 6 and 8 by default, growing with its reach), crouching lets them down, and they go over the terrain on the tendrils'
 * legs. It is something the player does: the first press of Space in the frenzy starts it, and crouching on the ground
 * ends it. The game's own flight carries the player (smooth, Space to rise and crouch to sink), the server only keeping
 * them within the height above whatever is below. When the frenzy ends in the air the legs don't just go: they
 * lower the player to the ground, still walking however the player moves, without fall damage; once down they draw
 * back out of the ground into the body ({@link #RETRACT_TICKS}). Crouching down to the ground draws them back the same way.
 * <p>
 * The legs: six tendrils from the hips to the ground around the player, walking as a spider's do (see gait); each foot planted until the body has moved
 * well past it, then stepping. Players with the mod draw them themselves (S_RAGE_DRAIN's hover flag); the others see
 * them in the morph skin (with the pack) or as particle lines.
 */
public final class FrenzyHover implements Listener {
	/**
	 * How far a foot stands out from the body (at least, and per block of the body's width: a ravager's stood inside it),
	 * and how far from where it should stand a foot may be left before it steps (blocks, plus a share of the spread).
	 */
	private static final double REACH = 1.0, REACH_PER_WIDTH = 0.85, STEP = 0.6, STEP_PER_REACH = 0.3;
	/** Legs (my call: more than four), pieces each is drawn in, and ticks a step takes. */
	static final int LEGS = 6, PIECES = 3, STEP_TICKS = 6;
	/**
	 * Ticks of the body's motion a step lands ahead by (at most {@link #LEAD_SHARE} of the step distance), and how many
	 * step distances behind a foot steps whatever its neighbours do (a quick turn, a teleport).
	 */
	static final double LEAD_TICKS = 6, LEAD_SHARE = 0.8, FAR = 2.5;
	/** Where a leg leaves the body: this share of its width out from the middle (its side), this share of its height up. */
	static final double HIP_OUT = 0.2, HIP_UP = 0.45;
	/** Blocks the feet stand out per block the body is above the ground. */
	static final double SPREAD_PER_HEIGHT = 0.5;
	/** Ticks of protection from fall damage after a hover ends (they drift down under slow falling meanwhile). */
	private static final int SAFE_TICKS = 200;
	/** Ticks the legs take to draw back into the body once the player is down, and how high a foot lifts as it goes. */
	static final int RETRACT_TICKS = 10;
	static final double RETRACT_LIFT = 0.3;
	/** The longest the legs lower a player after the frenzy (a long way down), in ticks; then they draw back anyway. */
	private static final int LOWER_TICKS = 200;
	/** Blocks above the ground that count as down. */
	private static final double DOWN = 0.15;
	/** A teleport further than this (blocks) ends legs that are lowering or drawing back. */
	private static final double TELEPORT_GONE = 4;
	/**
	 * What a player's legs are doing, for the mod (S_RAGE_DRAIN, 8.0.0): none, hovering, lowering the player after the
	 * frenzy, drawing back into the body. It was a bool before: mods from 7.4.0 read 1 to 3 as hovering and 0 as not.
	 */
	public static final int LEGS_NONE = 0, LEGS_HOVER = 1, LEGS_LOWER = 2, LEGS_RETRACT = 3;

	private final MorphRebornPlugin plugin;
	/** abilities.yml rage.hover-speed: the share of the player's walking speed a hover goes at. */
	private double speed = 1;
	/**
	 * The game's flying speed (as Bukkit sets it) that goes as fast as a walk, per point of the movement speed attribute
	 * (a player's 0.1 walks 4.3 blocks a second; a flying speed of 0.1 flies 10.9).
	 */
	private static final double FLY_PER_WALK = 0.39;
	/** Sprinting adds this share to the movement speed attribute (the game's sprint modifier). */
	private static final double SPRINT_BOOST = 1.3;

	/** The flight state before the hover, put back after it. */
	private record Saved(boolean allowFlight, boolean flying, float flySpeed) {}

	private static final class Hover {
		final Saved saved;
		/** Where each foot stands (world), planted until the body moves on; a stepping foot's start, its step's tick and arc. */
		final Vector[] feet = new Vector[LEGS], from = new Vector[LEGS];
		final int[] stepAt = new int[LEGS];
		final double[] arc = new double[LEGS];
		/** Where the body was at the last legs, and when: how fast it goes (a player's velocity on the server is no guide). */
		@Nullable Vector last;
		int lastAt;
		/** The set of legs (0: even, 1: odd) that stepped last, so the other goes next. */
		int lastSet = 1;
		/** The flying speed last set, so it is only sent when it changes. */
		float flySpeed = -1;
		/** The world it began in (a change of world ends the legs at once). */
		final UUID world;
		/** After the frenzy: when the legs began lowering the player, and when they began drawing back (-1: not yet). */
		int loweringAt = -1, retractAt = -1;

		Hover(Saved saved, UUID world) {
			this.saved = saved;
			this.world = world;
		}
	}

	private final Map<UUID, Hover> hovering = new HashMap<>();
	/** Legs still out after their hover ended: lowering the player, then drawing back. */
	private final Map<UUID, Hover> ending = new HashMap<>();
	/** Players spared fall damage, until this tick. */
	private final Map<UUID, Integer> safe = new HashMap<>();
	private int tick;

	public FrenzyHover(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	public void load(@Nullable ConfigurationSection c) {
		speed = c == null ? 1 : Math.clamp(c.getDouble("hover-speed", 1), 0.1, 3);
	}

	public boolean hovering(Player p) {
		return hovering.containsKey(p.getUniqueId());
	}

	/** What {@code p}'s legs are doing ({@link #LEGS_NONE} to {@link #LEGS_RETRACT}), for the mod. */
	public int legsState(Player p) {
		if (hovering.containsKey(p.getUniqueId())) return LEGS_HOVER;
		Hover h = ending.get(p.getUniqueId());
		return h == null ? LEGS_NONE : h.retractAt >= 0 ? LEGS_RETRACT : LEGS_LOWER;
	}

	/** One tick of {@code p}'s frenzy, whose tendrils lift it up to {@code maxHeight} blocks (its level's). */
	void tick(Player p, int now, double maxHeight) {
		tick = now;
		Hover h = hovering.get(p.getUniqueId());
		if (h == null) {
			// the first press of Space in the frenzy lifts the player; never on top of a flight they have anyway (a flying
			// morph, a resting bat), nor riding, gliding or swimming
			if (maxHeight > 0 && survivalLike(p) && p.getCurrentInput().isJump() && !p.getAllowFlight() && !p.isInsideVehicle() && !p.isGliding()
					&& !p.isSwimming()) {
				start(p);
			}
			return;
		}
		double ground = groundBelow(p);
		double height = Double.isNaN(ground) ? maxHeight + 1 : p.getY() - ground;
		if (p.getCurrentInput().isSneak() && height < DOWN) {
			plugin.rage().reportHover(p, "hover over: crouched on the ground");
			release(p, now); // crouched all the way down: walking again, the legs drawing back into the body
			return;
		}
		if (!p.isFlying()) p.setFlying(true); // a landing or a hit can stop the game's flight
		pace(p, h);
		if (height > maxHeight + 0.05 && !Double.isNaN(ground)) {
			// no higher than the tendrils reach: back down to it, keeping the way the player moves
			Location at = p.getLocation();
			at.setY(ground + maxHeight);
			p.teleport(at, TeleportFlag.Relative.VELOCITY_X, TeleportFlag.Relative.VELOCITY_Y, TeleportFlag.Relative.VELOCITY_Z,
					TeleportFlag.Relative.VELOCITY_ROTATION);
		}
		p.setFallDistance(0);
		if (now % 2 == 0) legs(p, h, ground, now);
	}

	private void start(Player p) {
		// legs still drawing back from the last hover give way to the new ones
		if (ending.remove(p.getUniqueId()) != null) plugin.skin().legsEnd(p);
		Hover h = new Hover(new Saved(p.getAllowFlight(), p.isFlying(), p.getFlySpeed()), p.getWorld().getUID());
		hovering.put(p.getUniqueId(), h);
		p.setAllowFlight(true);
		pace(p, h);
		p.setFlying(true);
		p.setVelocity(new Vector(0, 0.3, 0));
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.8F, 0.6F);
		plugin.rage().reportHover(p, "hover started");
		plugin.rage().hoverChanged(p);
	}

	/**
	 * The hover and its legs end at once (leaving, dying, another world, a shutdown); {@code drift}: in the air, the
	 * player sinks slowly and takes no fall.
	 */
	void stop(Player p, boolean drift) {
		Hover h = hovering.remove(p.getUniqueId());
		Hover after = ending.remove(p.getUniqueId());
		if (h == null && after == null) return;
		if (h != null && p.isOnline()) {
			restoreFlight(p, h);
			if (drift && !p.isOnGround()) drift(p);
		}
		plugin.skin().legsEnd(p);
		plugin.rage().hoverChanged(p);
	}

	/**
	 * The hover ends but the legs stay a little (my call: they shouldn't just vanish and leave the player floating
	 * down): in the air they lower the player to the ground, walking however the player goes (the game's flight is off,
	 * slow falling carries them and the legs keep stepping), then draw back out of the ground into the body. On the
	 * ground, or with nothing in reach below, they draw back at once.
	 */
	void release(Player p, int now) {
		Hover h = hovering.remove(p.getUniqueId());
		if (h == null) return;
		if (!p.isOnline()) {
			plugin.skin().legsEnd(p);
			return;
		}
		restoreFlight(p, h);
		ending.put(p.getUniqueId(), h);
		double ground = groundBelow(p);
		if (Double.isNaN(ground) || down(p, ground)) {
			retract(p, h, now);
		} else {
			h.loweringAt = now;
			drift(p);
			plugin.rage().reportHover(p, "legs lowering");
		}
		plugin.rage().hoverChanged(p);
	}

	/** Every tick: legs lowering a player after the frenzy, or drawing back into the body. */
	void tickEnding(int now) {
		if (ending.isEmpty()) return;
		for (Map.Entry<UUID, Hover> e : List.copyOf(ending.entrySet())) {
			Player p = plugin.getServer().getPlayer(e.getKey());
			Hover h = e.getValue();
			if (p == null || !p.isOnline()) {
				ending.remove(e.getKey());
				continue;
			}
			if (p.isDead() || !p.isValid() || !p.getWorld().getUID().equals(h.world)) {
				finish(p);
				continue;
			}
			double ground = groundBelow(p);
			if (h.retractAt < 0) {
				// down, or lifted off another way (into water, onto a mount, gliding, a flying morph's own flight), or too long
				if (Double.isNaN(ground) || down(p, ground) || p.isInWater() || p.isInLava() || p.isInsideVehicle() || p.isGliding() || p.isFlying()
						|| now - h.loweringAt > LOWER_TICKS) {
					retract(p, h, now);
					plugin.rage().hoverChanged(p);
				} else if ((now - h.loweringAt) % 20 == 19) {
					drift(p); // a long way down: the slow falling and the fall protection last
					// and the legs for players with the mod who came in sight since (during the frenzy Rage.tick says it)
					plugin.rage().hoverChanged(p);
				}
			}
			if (h.retractAt >= 0 && now - h.retractAt >= RETRACT_TICKS) {
				finish(p);
				continue;
			}
			if (now % 2 != 0) continue;
			if (h.retractAt >= 0) drawBack(p, h, now);
			else legs(p, h, ground, now);
		}
	}

	/**
	 * The legs begin drawing back: each foot comes up out of the ground and into the hip ({@link #drawBack}). A foot in
	 * the middle of a step is held where it is.
	 */
	private void retract(Player p, Hover h, int now) {
		for (int i = 0; i < LEGS; i++) {
			if (h.feet[i] == null) continue;
			h.feet[i] = footAt(h, i, now);
			h.from[i] = null;
		}
		h.retractAt = now;
		plugin.rage().reportHover(p, "legs drawing back");
		double ground = groundBelow(p);
		if (!Double.isNaN(ground) && down(p, ground)) {
			// down: the slow falling the legs gave isn't wanted any more (a potion's own is left alone)
			PotionEffect slow = p.getPotionEffect(PotionEffectType.SLOW_FALLING);
			if (slow != null && slow.isAmbient() && !slow.hasParticles() && !slow.hasIcon()) p.removePotionEffect(PotionEffectType.SLOW_FALLING);
		} else if (!p.isOnGround()) {
			drift(p); // drawn back in the air (nothing below in reach, too long): drift down as before
		}
	}

	/** The legs are gone. */
	private void finish(Player p) {
		ending.remove(p.getUniqueId());
		plugin.skin().legsEnd(p);
		plugin.rage().hoverChanged(p);
	}

	/** The game's flight back as it was before the hover; a flying morph's stays (the frenzy may have ended in another body that flies). */
	private void restoreFlight(Player p, Hover h) {
		p.setFlying(false);
		if (plugin.abilities().grantsFlight(p)) {
			// a flier borrowed mid-hover set its own speed (and the saved one is from before it, the game's 0.1): the flier's wins
			plugin.abilities().refreshMovement(p);
			return;
		}
		p.setFlySpeed(h.saved.flySpeed());
		p.setAllowFlight(h.saved.allowFlight());
	}

	/** Slow falling for a while, and no fall damage: the player comes down gently. */
	private void drift(Player p) {
		safe.put(p.getUniqueId(), plugin.getServer().getCurrentTick() + SAFE_TICKS);
		p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, true, false, false));
	}

	private static boolean down(Player p, double ground) {
		return p.isOnGround() || p.getY() - ground < DOWN;
	}

	/**
	 * The legs: six tendrils set evenly round the body, each from the hip, arched up and out, to a foot on the ground,
	 * writhing as the frenzy's tendrils do, walking as a spider's do ({@link #gait}). {@code legs} draws them for players
	 * without the mod.
	 */
	private void legs(Player p, Hover h, double ground, int now) {
		if (Double.isNaN(ground)) return;
		// spread wider the higher it stands (at 8 blocks the legs bunched into a cage under the player)
		double reach = Math.max(Math.max(REACH, p.getWidth() * REACH_PER_WIDTH), (p.getY() - ground) * SPREAD_PER_HEIGHT);
		Vector body = p.getLocation().toVector();
		Vector motion = h.last == null || now <= h.lastAt ? new Vector() : body.clone().subtract(h.last).multiply(1.0 / (now - h.lastAt)).setY(0);
		h.last = body.clone();
		h.lastAt = now;
		Vector[] outs = outwards(p), ideals = new Vector[LEGS];
		for (int i = 0; i < LEGS; i++) ideals[i] = body.clone().add(outs[i].clone().multiply(reach));
		gait(p, h, ideals, motion, reach, ground, now);
		Vector[] at = new Vector[LEGS];
		for (int i = 0; i < LEGS; i++) at[i] = footAt(h, i, now);
		draw(p, outs, at, 1, now);
	}

	/**
	 * Drawing back (my call: from the ground back into you): each foot comes up out of the ground into its hip over
	 * {@link #RETRACT_TICKS}, lifting a little as it goes, the leg straightening and thinning as it shortens. The mod draws
	 * the same (RageTethers).
	 */
	private void drawBack(Player p, Hover h, int now) {
		for (Vector foot : h.feet) if (foot == null) return; // never drawn
		double t = Math.clamp((now - h.retractAt) / (double) RETRACT_TICKS, 0, 1);
		double in = t * t * (3 - 2 * t);
		Vector[] outs = outwards(p), at = new Vector[LEGS];
		for (int i = 0; i < LEGS; i++) {
			Vector hip = hip(p, outs[i]);
			at[i] = h.feet[i].clone().add(hip.subtract(h.feet[i]).multiply(in)).add(new Vector(0, RETRACT_LIFT * Math.sin(Math.PI * t), 0));
		}
		draw(p, outs, at, 1 - in, now);
	}

	/** Where leg {@code i}'s foot is now (world): planted, or along its step, eased along the ground and lifted in an arc that grows with the step. */
	private static Vector footAt(Hover h, int i, int now) {
		if (h.from[i] == null) return h.feet[i].clone();
		double t = Math.clamp((now - h.stepAt[i]) / (double) STEP_TICKS, 0, 1);
		double along = t * t * (3 - 2 * t);
		return h.from[i].clone().add(h.feet[i].clone().subtract(h.from[i]).multiply(along)).add(new Vector(0, h.arc[i] * Math.sin(Math.PI * t), 0));
	}

	/** Which way each leg goes out: round the body from the front, every 60 degrees, turned with it. */
	private static Vector[] outwards(Player p) {
		double yaw = Math.toRadians(p.getBodyYaw());
		Vector[] outs = new Vector[LEGS];
		for (int i = 0; i < LEGS; i++) {
			double angle = yaw + Math.toRadians(30 + 60 * i);
			outs[i] = new Vector(-Math.sin(angle), 0, Math.cos(angle));
		}
		return outs;
	}

	/** Where a leg leaves the body: inside it at the waist (out at a quarter of the spread, then at its sides, a gap showed). */
	private static Vector hip(Player p, Vector out) {
		return p.getLocation().toVector().add(out.clone().multiply(p.getWidth() * HIP_OUT)).add(new Vector(0, p.getHeight() * HIP_UP, 0));
	}

	/**
	 * The legs from the hips to {@code feet}, for players without the mod: in the skin, or as particle lines. {@code
	 * shape} (1, less while drawing back) scales how far they arch and how thick they are.
	 */
	private void draw(Player p, Vector[] outs, Vector[] feet, double shape, int now) {
		List<Vector[]> segments = new ArrayList<>();
		List<Float> widths = new ArrayList<>();
		for (int i = 0; i < LEGS; i++) {
			Vector[] points = curve(hip(p, outs[i]), feet[i], outs[i], i * 1.3, now, shape);
			for (int j = 0; j < PIECES; j++) {
				segments.add(new Vector[] {points[j], points[j + 1]});
				// pixels, thinning to the foot, and to half as they draw back
				widths.add((float) ((2.4 + (1.2 - 2.4) * (j + 0.5) / PIECES) * (0.5 + 0.5 * shape)));
			}
		}
		boolean drawn = plugin.skin().legs(p, segments, widths);
		plugin.effects().legs(p, segments, drawn);
	}

	/**
	 * A walking gait (my call: each leg works out where it needs to be; all six jumped together, then two at a time in
	 * odd pairs). The legs go in two alternating sets of three, round the body every other one (front-left, back-left
	 * and right; the other three), as insects and spiders walk: a foot stays planted until the body has left it {@code
	 * STEP + STEP_PER_REACH * reach} from where it should stand; then its set steps, a tick apart, to spots a little ahead
	 * of where they should stand, the way the body is going, and the other set waits until they have landed. When both
	 * sets are due, the one that didn't step last goes. A foot left {@link #FAR} times that behind (a quick turn, a
	 * teleport) steps at once. The mod walks its legs the same way (RageTethers.gait).
	 */
	private static void gait(Player p, Hover h, Vector[] ideals, Vector motion, double reach, double ground, int now) {
		double due = STEP + STEP_PER_REACH * reach;
		Vector lead = motion.clone().multiply(LEAD_TICKS);
		if (lead.length() > due * LEAD_SHARE) lead.normalize().multiply(due * LEAD_SHARE);
		double[] off = new double[LEGS];
		boolean anyLifted = false;
		boolean[] setDue = new boolean[2];
		for (int i = 0; i < LEGS; i++) {
			if (h.from[i] != null && now - h.stepAt[i] >= STEP_TICKS) h.from[i] = null; // landed
			if (h.feet[i] == null) {
				double y = groundAt(p, ideals[i].getX(), ideals[i].getZ(), p.getY());
				h.feet[i] = new Vector(ideals[i].getX(), Double.isNaN(y) ? ground : y, ideals[i].getZ());
			}
			off[i] = h.feet[i].clone().setY(0).distance(ideals[i].clone().setY(0));
			if (h.from[i] != null) anyLifted = true;
			else if (off[i] > due * FAR) step(p, h, i, ideals[i].clone().add(lead), ground, now); // left far behind
			else if (off[i] > due) setDue[i % 2] = true;
		}
		if (anyLifted || !(setDue[0] || setDue[1])) return;
		int set = setDue[0] && setDue[1] ? 1 - h.lastSet : setDue[0] ? 0 : 1;
		int ripple = 0;
		for (int i = set; i < LEGS; i += 2) {
			if (off[i] < 0.15) continue; // already where it should stand
			step(p, h, i, ideals[i].clone().add(lead), ground, now + ripple++);
		}
		h.lastSet = set;
	}

	/** Leg {@code i} steps to the ground under {@code ahead}, starting at tick {@code at}. */
	private static void step(Player p, Hover h, int i, Vector ahead, double ground, int at) {
		double y = groundAt(p, ahead.getX(), ahead.getZ(), p.getY());
		Vector to = new Vector(ahead.getX(), Double.isNaN(y) ? ground : y, ahead.getZ());
		h.from[i] = h.feet[i];
		h.feet[i] = to;
		h.stepAt[i] = at;
		h.arc[i] = Math.min(0.6, 0.15 + 0.2 * h.from[i].distance(to));
	}

	/**
	 * One leg's points from {@code hip} to {@code foot}: arched up and {@code out}, writhing sideways, all of it scaled by
	 * {@code bend} (1; less while drawing back, so a short leg doesn't loop over the hip). The mod draws the same,
	 * RageTethers.leg.
	 */
	static Vector[] curve(Vector hip, Vector foot, Vector out, double phase, double time, double bend) {
		Vector[] points = new Vector[PIECES + 1];
		Vector line = foot.clone().subtract(hip);
		double length = line.length();
		Vector side = out.clone().crossProduct(new Vector(0, 1, 0));
		if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
		side.normalize();
		for (int k = 0; k <= PIECES; k++) {
			double s = k / (double) PIECES;
			double bow = Math.sin(Math.PI * s) * bend;
			points[k] = hip.clone().add(line.clone().multiply(s)).add(new Vector(0, bow * (0.4 + 0.15 * length), 0)).add(out.clone().multiply(bow * (0.2 + 0.12 * length)))
					.add(side.clone().multiply(bow * 0.15 * Math.sin(phase + s * 5 + time * 0.3)));
		}
		return points;
	}

	/**
	 * A hover goes at the player's walking speed (my call: no sprint-flying; a faster morph or multimorph goes faster):
	 * the flying speed follows their movement speed (a morph's, a Speed effect), less the sprint's boost, and halved while
	 * sprinting, since sprinting doubles the game's flight. Sent only when it changes.
	 */
	private void pace(Player p, Hover h) {
		org.bukkit.attribute.AttributeInstance move = p.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
		double walk = move == null ? 0.1 : move.getValue();
		if (p.isSprinting()) walk /= SPRINT_BOOST;
		double fly = walk * FLY_PER_WALK * speed / (p.isSprinting() ? 2 : 1);
		float value = (float) Math.clamp(fly, 0.005, 1);
		if (Math.abs(value - h.flySpeed) < 1e-4F) return;
		h.flySpeed = value;
		p.setFlySpeed(value);
	}

	/** The top of the ground under the player's feet, or NaN with nothing within 32 blocks. */
	private static double groundBelow(Player p) {
		return groundAt(p, p.getX(), p.getZ(), p.getY() + 0.1);
	}

	private static double groundAt(Player p, double x, double z, double fromY) {
		Location from = new Location(p.getWorld(), x, fromY + 0.5, z);
		RayTraceResult hit = p.getWorld().rayTraceBlocks(from, new Vector(0, -1, 0), 32, FluidCollisionMode.ALWAYS, true);
		return hit == null ? Double.NaN : hit.getHitPosition().getY();
	}

	private static boolean survivalLike(Player p) {
		GameMode mode = p.getGameMode();
		return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFall(EntityDamageEvent event) {
		if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player p)) return;
		Integer until = safe.remove(p.getUniqueId());
		if (until != null && plugin.getServer().getCurrentTick() <= until) event.setCancelled(true);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		stop(event.getPlayer(), false);
		safe.remove(event.getPlayer().getUniqueId());
	}

	/**
	 * The legs go with the body: after the frenzy nothing else ends them on a death (Rage has nothing left to end), and
	 * an immediate respawn could come before the next tick notices, carrying them to the spawn point.
	 */
	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		stop(event.getEntity(), false);
		safe.remove(event.getEntity().getUniqueId());
	}

	/**
	 * A teleport (a pearl, /home) while the legs are lowering or drawing back: they can't reach back across it, so they
	 * go at once. Small nudges stay under the distance. While hovering the gait steps a far-left foot at once instead.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		if (!ending.containsKey(event.getPlayer().getUniqueId())) return;
		Location from = event.getFrom(), to = event.getTo();
		if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > TELEPORT_GONE * TELEPORT_GONE) finish(event.getPlayer());
	}

	public void releaseAll() {
		for (UUID id : List.copyOf(hovering.keySet())) {
			Player p = plugin.getServer().getPlayer(id);
			if (p != null) stop(p, true);
			else hovering.remove(id);
		}
		for (UUID id : List.copyOf(ending.keySet())) {
			Player p = plugin.getServer().getPlayer(id);
			if (p != null) stop(p, true);
			else ending.remove(id);
		}
	}
}
