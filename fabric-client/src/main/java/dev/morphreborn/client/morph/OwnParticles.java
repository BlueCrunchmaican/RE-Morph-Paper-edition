package dev.morphreborn.client.morph;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Particles your own morph's body makes, in first person. A morph body runs its mob's client tick, and some mobs give
 * off particles all over their body: a blaze puts out two big smoke clouds every tick, anywhere from its feet to above
 * its head. Seen from inside that body the smoke sits right on the camera and hides everything. So while your own body
 * ticks in first person, what it gives off is moved well behind you (and behind the camera, whichever way you look),
 * where rising smoke stays out of view, and only half of it is kept. Other players, and you in third person, still see all of it where the mob would make it.
 */
public final class OwnParticles {
	/** How far behind the camera the particles go. */
	private static final double BEHIND = 0.6;
	/** How far behind you they go, along the ground: far enough that smoke rising from there stays out of view. */
	private static final double BACK = 1.6;
	private static boolean active;
	private static Vec3 eye = Vec3.ZERO;
	private static Vec3 look = new Vec3(0, 0, 1);
	private static Vec3 ahead = new Vec3(0, 0, 1);
	private static int count;

	private OwnParticles() {}

	/** Whether the local player's own morph body is ticking now, in first person. */
	public static boolean active() {
		return active;
	}

	public static void begin(Player player) {
		Minecraft mc = Minecraft.getInstance();
		active = player == mc.player && mc.options.getCameraType().isFirstPerson();
		if (!active) return;
		eye = player.getEyePosition();
		look = player.getViewVector(1F);
		double yaw = Math.toRadians(player.getYRot());
		ahead = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
	}

	public static void end() {
		active = false;
	}

	/** Back on after a moved particle was spawned, for the rest of the same tick. */
	public static void resume() {
		active = true;
	}

	/** Every other one is kept. */
	public static boolean keep() {
		return (count++ & 1) == 0;
	}

	/**
	 * The particle's spot moved back along the ground until it is {@link #BACK} behind you, then, if it would still be
	 * in front of the camera (looking down), back along the view until it is {@link #BEHIND} behind the camera.
	 */
	public static Vec3 behind(double x, double y, double z) {
		Vec3 p = new Vec3(x, y, z);
		double forward = p.subtract(eye).dot(ahead);
		if (forward > -BACK) p = p.subtract(ahead.scale(forward + BACK));
		double inView = p.subtract(eye).dot(look);
		return inView > -BEHIND ? p.subtract(look.scale(inView + BEHIND)) : p;
	}
}
