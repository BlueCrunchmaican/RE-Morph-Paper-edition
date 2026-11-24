package dev.morphreborn.paper.arm.rules;

import java.util.Set;

/**
 * The Tendril Shield's arithmetic (my design, 2026-11-16, 8.0.0-beta.2): which blows are frontal, how much of one a level
 * cuts, which projectiles are sent back, the velocity that sends one at its shooter, and (2026-11-17) what raises it. Pure, so each rule is unit tested without a
 * server; the module only feeds it positions and applies what it says.
 */
public final class ShieldRules {
	/** Arrows and tridents: gravity a tick and the air's drag, as the game's own (AbstractArrow). */
	public static final double ARROW_GRAVITY = 0.05, ARROW_DRAG = 0.99;
	/** A projectile that was sent back this many times is taken instead (two shields sending one to each other would never end). */
	public static final int MAX_REFLECTS = 3;
	/** A longest flight the aim reckons with, in ticks; a shooter further than the arrow can reach in that gets an arrow aimed as far as it goes. */
	private static final int AIM_TICKS = 60;

	/** Projectile types (entity type keys) that are sent back, with gravity (arrows, tridents) or without (fireballs, skulls, wind charges). */
	private static final Set<String> ARROWS = Set.of("minecraft:arrow", "minecraft:spectral_arrow", "minecraft:trident");
	private static final Set<String> FLYERS = Set.of("minecraft:fireball", "minecraft:small_fireball", "minecraft:wither_skull", "minecraft:dragon_fireball",
			"minecraft:wind_charge", "minecraft:breeze_wind_charge");

	private ShieldRules() {}

	/** What a projectile is to the shield. */
	public enum Kind {
		/** An arrow or a trident: it falls, so it is aimed with a lift. */
		ARROW,
		/** A fireball, a skull or a wind charge: it flies straight, and is aimed along its line. */
		FLYER,
		/** Anything else (a snowball, a pearl, a potion, a firework): the shield does not touch it. */
		OTHER
	}

	/** The shield's view of a projectile by its entity type key ({@code minecraft:arrow}). */
	public static Kind kind(String entityTypeKey) {
		if (ARROWS.contains(entityTypeKey)) return Kind.ARROW;
		if (FLYERS.contains(entityTypeKey)) return Kind.FLYER;
		return Kind.OTHER;
	}

	/**
	 * Whether a blow from {@code (dx, dz)} (the source's position minus the owner's, on the ground plane) is frontal: within
	 * {@code halfAngleDegrees} of where the owner looks ({@code yawDegrees}, Minecraft's: 0 faces south, +z, 90 faces west, -x). Only the
	 * ground plane counts, so looking up or down changes nothing, as vanilla's shield. A source right on top of the owner counts as frontal
	 * (vanilla's does too). 180 or more is all round, 0 or less is nothing.
	 */
	public static boolean frontal(double yawDegrees, double dx, double dz, double halfAngleDegrees) {
		if (halfAngleDegrees <= 0) return false;
		if (halfAngleDegrees >= 180) return true;
		double len = Math.hypot(dx, dz);
		if (len < 1e-6) return true;
		double yaw = Math.toRadians(yawDegrees);
		double lx = -Math.sin(yaw), lz = Math.cos(yaw);
		double cos = (dx * lx + dz * lz) / len;
		return cos >= Math.cos(Math.toRadians(halfAngleDegrees)) - 1e-9;
	}

	/** The damage left after a cut of {@code reduction} (0 none, 1 all of it); a reduction outside 0 to 1 is kept to it. */
	public static double cut(double damage, double reduction) {
		return Math.max(0, damage) * (1 - Math.clamp(reduction, 0, 1));
	}

	/** Whether the raised shield holds yet: it has been up for the raise delay (the right-click, or the sneak of an owner who has no other way, started {@code raisedFor} ticks ago). */
	public static boolean holds(boolean raised, int raisedFor, int delayTicks) {
		return raised && raisedFor >= Math.max(0, delayTicks);
	}

	/** The speed a sent-back projectile leaves at: the speed it came with, kept between the settings' least and greatest. */
	public static double reflectSpeed(double incoming, double min, double max) {
		double lo = Math.min(min, max), hi = Math.max(min, max);
		return Math.clamp(Double.isFinite(incoming) ? incoming : lo, lo, hi);
	}

	/**
	 * The velocity (blocks a tick) that sends a projectile from {@code (fx, fy, fz)} to {@code (tx, ty, tz)}: straight at it when it
	 * doesn't fall ({@code gravity} 0), otherwise lifted so that the arc, flown with the game's own motion (move, drag, then gravity, each
	 * tick), arrives at the target's height when it has covered the ground to it. The horizontal speed is kept; the vertical is corrected
	 * a few times against a short simulation. Null when the two points are the same.
	 */
	public static double[] aim(double fx, double fy, double fz, double tx, double ty, double tz, double speed, double gravity, double drag) {
		double dx = tx - fx, dy = ty - fy, dz = tz - fz;
		double h = Math.hypot(dx, dz), dist = Math.sqrt(h * h + dy * dy);
		if (dist < 1e-6 || !(speed > 0)) return null;
		if (gravity <= 0 || h < 1e-6) return new double[] {dx / dist * speed, dy / dist * speed, dz / dist * speed};
		double ux = dx / h, uz = dz / h;
		double vh = speed * h / dist;
		double vy = dy / dist * speed;
		for (int pass = 0; pass < 8; pass++) {
			double x = 0, y = 0, hv = vh, yv = vy, stepX = 0, stepY = 0;
			int t = 0;
			while (x < h && t < AIM_TICKS) {
				stepX = hv;
				stepY = yv;
				x += hv;
				y += yv;
				hv *= drag;
				yv = yv * drag - gravity;
				t++;
			}
			if (x < h) break; // the arrow would not get there: it is sent as far as it goes
			// the height where the arc crosses the shooter's distance, not where the last whole tick ended
			double at = stepX > 0 ? y - stepY * (x - h) / stepX : y;
			vy += (dy - at) / t;
			vy = Math.clamp(vy, -2 * speed, 2 * speed);
		}
		return new double[] {ux * vh, vy, uz * vh};
	}

	/**
	 * The velocity to set on an arrow inside its hit event so that its first move is {@code (wx, wy, wz)} (what {@link #aim} worked out): the
	 * hit event fires inside {@code AbstractArrow.stepMoveAndHit}, and the same tick then applies the air's drag and the gravity before the next
	 * tick's move, so the velocity set there is dragged and dropped once before it is ever flown (review of 2026-11-16: reflected arrows
	 * landed short). {@code gravity} 0 for an arrow that doesn't fall. The vertical comes out as {@code (wy + gravity) / drag}.
	 */
	public static double[] launch(double wx, double wy, double wz, double gravity, double drag) {
		double d = drag > 0 ? drag : 1;
		return new double[] {wx / d, (wy + Math.max(0, gravity)) / d, wz / d};
	}

	/**
	 * The velocity back along the way a projectile came, at {@code speed}: for a projectile with no shooter to aim at (a dispenser's, a
	 * mob that is gone). Null when it was standing still.
	 */
	public static double[] reverse(double vx, double vy, double vz, double speed) {
		double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (len < 1e-9 || !(speed > 0)) return null;
		return new double[] {-vx / len * speed, -vy / len * speed, -vz / len * speed};
	}

	// ------------------------------------------------------------------ what raises it (decided 2026-11-17: the use key, main hand first)

	/** How long a client's held-key signal (C_ARM ACTION_ACCESSORY_HOLD) is believed without a repeat: the client repeats every 10 seconds, so a lost release cannot leave the shield up for ever. */
	public static final int HOLD_TIMEOUT_TICKS = 300;
	/**
	 * How long a player without the mod can hold the shield up on one press (ticks, 5 minutes): a game sends the use packet once and then nothing
	 * until the key is let go, and the release is not sent while a screen is open, so a key let go behind the pause menu or the inventory would
	 * otherwise leave the shield up for ever. A press older than this is not held; the next click raises it again.
	 */
	public static final int GHOST_TIMEOUT_TICKS = 6000;
	/**
	 * How old a shown-shield press has to be before it raises the shield (ticks). The use packet is heard the moment it arrives, but the main
	 * hand's own handlers for the same click (flint, a block, a mount) run on the main thread a little later; waiting two ticks lets them say so
	 * first, so a click the main hand used never flickers the shield up.
	 */
	public static final int GHOST_SETTLE_TICKS = 2;
	/**
	 * A held-key signal this soon after the last one is a new press, not the repeat: the client repeats every 200 ticks, so a 1 that comes
	 * earlier means the release in between was lost (it starts a click of its own).
	 */
	public static final int REPEAT_MIN_TICKS = 100;
	/** Two signals this close together (ticks) are one right-click: the main hand's use and the shield's raise come from the same click, and which thread hears first is luck. */
	public static final int SAME_CLICK_TICKS = 2;

	/** How an owner's shield is raised. Decided again every tick from who the owner is, so a game mode change or a reconnect with the mod takes effect at once. */
	public enum Control {
		/** The mod's client at the new forms' version: it tells the server when the use key is held and the main hand did not use the click (ACTION_ACCESSORY_HOLD). */
		KEY,
		/**
		 * A game without the mod: the server shows its own client a real shield in the off hand by packet, so the game raises it natively on a
		 * right-click the main hand did not use, and reads the use packets that come back (look/ShieldGrip).
		 */
		GHOST,
		/** The one case with no way to read a right-click (no PacketEvents, a Bedrock player, creative mode): holding sneak, as the shield was first built. */
		SNEAK
	}

	/**
	 * Which way this owner raises the shield. {@code modded}: the client draws the new forms. {@code packets}: the packet look is running
	 * (PacketEvents). A Bedrock player's game does not raise an off-hand shield on the use key the way Java's does (Geyser translates it), so the packets are not to be relied on there; a creative player's inventory
	 * can write whatever it shows (a shield that is only on screen could be picked up and become a real one), so both keep sneak. {@code packets} is
	 * per player: false for a game with no off hand to show a shield in.
	 */
	public static Control control(boolean modded, boolean packets, boolean bedrock, boolean creative) {
		if (modded) return Control.KEY;
		return packets && !bedrock && !creative ? Control.GHOST : Control.SNEAK;
	}

	/** Whether the owner is asking for the shield to be up: the way their control reads it ({@code keyDown}: the mod's held key, {@code ghostDown}: the use packet of the shown shield). */
	public static boolean wantsRaise(Control control, boolean sneaking, boolean keyDown, boolean ghostDown) {
		return switch (control) {
			case KEY -> keyDown;
			case GHOST -> ghostDown;
			case SNEAK -> sneaking;
		};
	}

	/** Whether two tick stamps (-1: never) belong to one click. */
	public static boolean sameClick(int a, int b) {
		return a >= 0 && b >= 0 && Math.abs(a - b) <= SAME_CLICK_TICKS;
	}

	/**
	 * Whether the mod's held key still counts: the client said held ({@code told}), the last repeat was {@code toldAt}, and it was not the click that
	 * the main hand used ({@code ignored}, until the key is let go).
	 */
	public static boolean keyHeld(boolean told, int toldAt, int now, boolean ignored) {
		return told && !ignored && now - toldAt <= HOLD_TIMEOUT_TICKS;
	}

	/**
	 * Whether the held-key signal that just came starts a click of its own ({@code keyDown}: the key was already told held, {@code keyAt}: the tick
	 * of that last 1): a first one does, and so does one that is too soon after the last to be the client's repeat.
	 */
	public static boolean newPress(boolean keyDown, int keyAt, int now) {
		return !keyDown || now - keyAt < REPEAT_MIN_TICKS;
	}

	/**
	 * Whether the shown shield's use packet counts: it came ({@code pressedAt}, -1 while the key is up), it has settled ({@link #GHOST_SETTLE_TICKS})
	 * and is not older than {@link #GHOST_TIMEOUT_TICKS}, and the main hand did not use that click ({@code usedAt}: the tick one of the plugin's own
	 * right-click uses (flint, a carried block, milking, a mount) took the click, -1 never).
	 */
	public static boolean ghostHeld(int pressedAt, int usedAt, int now) {
		return pressedAt >= 0 && now - pressedAt >= GHOST_SETTLE_TICKS && now - pressedAt <= GHOST_TIMEOUT_TICKS && !sameClick(pressedAt, usedAt);
	}

	/**
	 * S_ARM_FX kind FX_ACCESSORY {@code arg} for a shield move: the accessory's place in the accessory ring (the shield is 1, as in
	 * {@code ArmProtocol.ACCESSORIES}) in bits 8-11, the move in bits 0-7, so the accessories' moves can never meet on one number.
	 */
	public static int fxArg(int move) {
		return (1 << 8) | (move & 0xFF);
	}

	/** The move in an FX_ACCESSORY {@code arg}, or 0 when the arg is not the shield's. */
	public static int fxMove(int arg) {
		return (arg >> 8 & 0xF) == 1 ? arg & 0xFF : 0;
	}
}
