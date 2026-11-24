package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A critical or sweeping hit with an arm (S_ARM_FX HIT): three tendrils whip out of the creature that was hit, 40% of
 * the way toward the blade in 2 ticks, and snap back in 2, as if the blow tore at the biomass inside it. Ordinary hits
 * show only the in-hand reaction (the hamon flare, the beads off the tip), which the look draws.
 * <p>
 * HIT carries only the creature, so the server says what kind of hit it was in {@code arg}, as its billing judged it
 * from the damage event: {@link #ARG_CRIT} for a critical hit, {@link #ARG_SWEEP} for a creature caught by a sword's
 * sweep, 0 for a plain one. The server knows (it saw the event); the client could only guess from a striker's falling
 * and sprinting, which for other players arrives late and coarse.
 */
public final class HitFx implements FxHandler {
	/** HIT's {@code arg}: a critical hit, a creature the sweep caught (read as bits, so a server may set both). */
	public static final int ARG_CRIT = 1, ARG_SWEEP = 2;
	/** Ticks out and back; the share of the way to the blade; how many tendrils; how many hits show at once. */
	private static final float OUT = 2F, BACK = 2F, SHARE = 0.4F;
	private static final int WHIPS = 3, MAX = 16;
	/** How far apart the three tendrils end, across the line to the blade (blocks). */
	private static final double SPREAD = 0.16;
	/** A tendril's width at the creature and at its tip (px). */
	private static final float WIDTH_ROOT = 1.6F, WIDTH_TIP = 0.5F;

	private record Whip(int origin, int victim, long start) {}

	private final List<Whip> whips = new ArrayList<>();

	public HitFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_HIT};
	}

	@Override
	public void start(ArmFxEvent e) {
		if (e.ids().length == 0 || !shows(e.arg())) return;
		if (whips.size() >= MAX) whips.remove(0);
		whips.add(new Whip(e.origin(), e.ids()[0], e.receivedTick()));
	}

	/** Whether a hit of this {@code arg} gets the world tendrils: critical and sweeping hits do, plain ones don't. */
	static boolean shows(int arg) {
		return (arg & (ARG_CRIT | ARG_SWEEP)) != 0;
	}

	@Override
	public void tick() {
		long now = Arms.TICK;
		whips.removeIf(w -> now - w.start > OUT + BACK);
	}

	/** How far the tendrils reach {@code age} ticks after the hit: out fast (eased out) over 2 ticks, back over 2. */
	static float reach(float age) {
		if (age < OUT) {
			float x = 1F - ArmMath.clamp01(age / OUT);
			return 1F - x * x;
		}
		return 1F - ArmMath.sm((age - OUT) / BACK);
	}

	@Override
	public void collect(ArmFxFrame f) {
		if (whips.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float pt = f.partialTick();
		for (Whip w : whips) {
			ArmTint.owner(w.origin); // this effect is drawn in its owner's colour
			float reach = reach(Arms.age(w.start, pt));
			if (!(reach > 0.001F)) continue;
			if (!(level.getEntity(w.victim) instanceof LivingEntity victim) || victim.isRemoved()) continue;
			Vec3 blade = ArmFx.anchor(w.origin, ArmTips.MID);
			if (blade == null) continue;
			Vec3 from = TendrilFx.wound(victim, pt);
			Vec3 line = blade.subtract(from);
			if (line.lengthSqr() < 1e-4) continue;
			Vec3 side = line.cross(new Vec3(0, 1, 0));
			side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
			Vec3 up = side.cross(line).normalize();
			Vec3 tip = from.add(line.scale(SHARE));
			int light = TendrilFx.light(level, from, tip);
			for (int k = 0; k < WHIPS; k++) {
				double angle = ArmMath.TAU * k / WHIPS + w.victim * 0.9;
				Vec3 off = side.scale(Math.cos(angle) * SPREAD).add(up.scale(Math.sin(angle) * SPREAD));
				// each leaves the body a little apart and fans out toward the blade
				TendrilFx.draw(f, from.add(off.scale(0.35)), tip.add(off), reach, w.victim * 1.7F + k * 2.1F, WIDTH_ROOT, WIDTH_TIP, 0F, 0F, ArmLayer.RED, light);
			}
		}
	}

	@Override
	public void clear() {
		whips.clear();
	}
}
