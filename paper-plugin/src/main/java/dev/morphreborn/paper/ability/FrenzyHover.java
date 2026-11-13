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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Devouring Frenzy's hover (the user's idea): while in a frenzy, Space lifts the player on tendrils up to its level's
 * {@code hover} blocks above the ground (4, 6 and 8 by default, growing with its reach), crouching lets them down, and they go over the terrain on the tendrils'
 * legs. It is something the player does: the first press of Space in the frenzy starts it, and crouching on the ground
 * ends it. The game's own flight carries the player (smooth, Space to rise and crouch to sink), the server only keeping
 * them within the height above whatever is below; when the frenzy ends they drift down without fall damage.
 * <p>
 * The legs: four tendrils from the hips to the ground around the player, each foot planted until the body has moved
 * well past it, then stepping. Players with the mod draw them themselves (S_RAGE_DRAIN's hover flag); the others see
 * them in the morph skin (with the pack) or as particle lines.
 */
public final class FrenzyHover implements Listener {
	/**
	 * How far a foot stands out from the body (at least, and per block of the body's width: a ravager's stood inside it),
	 * and how far past it the body may move before it steps.
	 */
	private static final double REACH = 1.0, REACH_PER_WIDTH = 0.85, STEP = 1.1;
	/** Legs (the user: more than four), pieces each is drawn in, and ticks a step takes. */
	static final int LEGS = 6, PIECES = 3, STEP_TICKS = 4;
	/** Blocks the feet stand out per block the body is above the ground. */
	static final double SPREAD_PER_HEIGHT = 0.5;
	/** Ticks of protection from fall damage after a hover ends (they drift down under slow falling meanwhile). */
	private static final int SAFE_TICKS = 200;

	private final MorphRebornPlugin plugin;
	private float speed = 0.05F;

	/** The flight state before the hover, put back after it. */
	private record Saved(boolean allowFlight, boolean flying, float flySpeed) {}

	private static final class Hover {
		final Saved saved;
		/** Where each foot stands (world), planted until the body moves on; a stepping foot's start and its step's tick. */
		final Vector[] feet = new Vector[LEGS], from = new Vector[LEGS];
		final int[] stepAt = new int[LEGS];

		Hover(Saved saved) {
			this.saved = saved;
		}
	}

	private final Map<UUID, Hover> hovering = new HashMap<>();
	/** Players spared fall damage, until this tick. */
	private final Map<UUID, Integer> safe = new HashMap<>();
	private int tick;

	public FrenzyHover(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	public void load(@Nullable ConfigurationSection c) {
		speed = c == null ? 0.05F : (float) Math.clamp(c.getDouble("hover-speed", 0.05), 0.01, 0.2);
	}

	public boolean hovering(Player p) {
		return hovering.containsKey(p.getUniqueId());
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
		if (p.getCurrentInput().isSneak() && height < 0.15) {
			plugin.rage().reportHover(p, "hover over: crouched on the ground");
			stop(p, false); // crouched all the way down: walking again
			return;
		}
		if (!p.isFlying()) p.setFlying(true); // a landing or a hit can stop the game's flight
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
		hovering.put(p.getUniqueId(), new Hover(new Saved(p.getAllowFlight(), p.isFlying(), p.getFlySpeed())));
		p.setAllowFlight(true);
		p.setFlySpeed(speed);
		p.setFlying(true);
		p.setVelocity(new Vector(0, 0.3, 0));
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SPIDER_STEP, 0.8F, 0.6F);
		plugin.rage().reportHover(p, "hover started");
		plugin.rage().hoverChanged(p);
	}

	/** The hover ends; {@code drift}: in the air when the frenzy ran out, so the player sinks slowly and takes no fall. */
	void stop(Player p, boolean drift) {
		Hover h = hovering.remove(p.getUniqueId());
		if (h == null) return;
		if (p.isOnline()) {
			p.setFlySpeed(h.saved.flySpeed());
			p.setFlying(false);
			// a flying morph's flight stays (the frenzy may have ended in another body that flies)
			if (!plugin.abilities().grantsFlight(p)) p.setAllowFlight(h.saved.allowFlight());
			if (drift && !p.isOnGround()) {
				safe.put(p.getUniqueId(), plugin.getServer().getCurrentTick() + SAFE_TICKS);
				p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, true, false, false));
			}
		}
		plugin.skin().legsEnd(p);
		plugin.rage().hoverChanged(p);
	}

	/**
	 * The legs: six tendrils set evenly round the body, each from the hip, arched up and out, to a foot on the ground,
	 * writhing as the frenzy's tendrils do. A foot stays where it stands until the body has moved well past it, then lifts
	 * and swings to a spot a little ahead over a few ticks. {@code legs} draws them for players without the mod.
	 */
	private void legs(Player p, Hover h, double ground, int now) {
		if (Double.isNaN(ground)) return;
		double yaw = Math.toRadians(p.getBodyYaw());
		// spread wider the higher it stands (at 8 blocks the legs bunched into a cage under the player)
		double reach = Math.max(Math.max(REACH, p.getWidth() * REACH_PER_WIDTH), (p.getY() - ground) * SPREAD_PER_HEIGHT);
		Vector body = p.getLocation().toVector();
		List<Vector[]> segments = new ArrayList<>();
		List<Float> widths = new ArrayList<>();
		for (int i = 0; i < LEGS; i++) {
			// round the body from the front, turned with it
			double angle = yaw + Math.toRadians(30 + 60 * i);
			Vector out = new Vector(-Math.sin(angle), 0, Math.cos(angle));
			Vector ideal = body.clone().add(out.clone().multiply(reach));
			Vector foot = h.feet[i];
			// it steps when it's this far from where it should stand (risen from the ground, a foot planted close in steps out)
			if (foot == null || (h.from[i] == null && foot.clone().setY(0).distance(ideal.clone().setY(0)) > STEP + 0.35 * reach)) {
				Vector ahead = ideal.clone().add(p.getVelocity().clone().setY(0).multiply(4));
				double y = groundAt(p, ahead.getX(), ahead.getZ(), p.getY());
				Vector to = new Vector(ahead.getX(), Double.isNaN(y) ? ground : y, ahead.getZ());
				h.from[i] = foot == null ? to : foot;
				h.feet[i] = to;
				h.stepAt[i] = now;
			}
			Vector at = h.feet[i];
			if (h.from[i] != null) {
				double t = Math.min(1, (now - h.stepAt[i]) / (double) STEP_TICKS);
				at = h.from[i].clone().add(h.feet[i].clone().subtract(h.from[i]).multiply(t)).add(new Vector(0, 0.5 * Math.sin(Math.PI * t), 0));
				if (t >= 1) h.from[i] = null;
			}
			Vector hip = body.clone().add(out.clone().multiply(reach * 0.25)).add(new Vector(0, p.getHeight() * 0.35, 0));
			Vector[] points = curve(hip, at, out, i * 1.3, now);
			for (int j = 0; j < PIECES; j++) {
				segments.add(new Vector[] {points[j], points[j + 1]});
				widths.add((float) (2.4 + (1.2 - 2.4) * (j + 0.5) / PIECES)); // pixels, thinning to the foot
			}
		}
		boolean drawn = plugin.skin().legs(p, segments, widths);
		plugin.effects().legs(p, segments, drawn);
	}

	/**
	 * One leg's points from {@code hip} to {@code foot}: arched up and {@code out}, writhing sideways (the mod draws the
	 * same, RageTethers.leg).
	 */
	static Vector[] curve(Vector hip, Vector foot, Vector out, double phase, double time) {
		Vector[] points = new Vector[PIECES + 1];
		Vector line = foot.clone().subtract(hip);
		double length = line.length();
		Vector side = out.clone().crossProduct(new Vector(0, 1, 0));
		if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
		side.normalize();
		for (int k = 0; k <= PIECES; k++) {
			double s = k / (double) PIECES;
			double bow = Math.sin(Math.PI * s);
			points[k] = hip.clone().add(line.clone().multiply(s)).add(new Vector(0, bow * (0.4 + 0.15 * length), 0)).add(out.clone().multiply(bow * (0.2 + 0.12 * length)))
					.add(side.clone().multiply(bow * 0.15 * Math.sin(phase + s * 5 + time * 0.3)));
		}
		return points;
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

	public void releaseAll() {
		for (UUID id : List.copyOf(hovering.keySet())) {
			Player p = plugin.getServer().getPlayer(id);
			if (p != null) stop(p, true);
			else hovering.remove(id);
		}
	}
}
