package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.ShieldMotion;
import dev.morphreborn.client.arm.geom.ShieldRenderer;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.Static;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The shield's world effects (S_ARM_FX kind FX_ACCESSORY, the shield's three moves; {@link AccessoryFx} hands them over). The wire is the
 * server's (decided 2026-11-16): {@code arg = (1 << 8) | move} ({@link ArmProtocol#accessoryArg}), {@code origin} the owner,
 * and {@code ids} never carry the owner: {@code ids[0]} is the attacker or the projectile, and a reflect's {@code ids[1]}, when it is there, is
 * the shooter (not used here). {@code positions[0]} is the block the projectile was in (a reflect's or an absorb's), which stands in for the
 * projectile when the client has no such entity.
 * <ul>
 * <li><b>BLOCK</b> (move 1): a blow the raised shield took. Sparks of static fly off the face toward whatever struck it (black
 * hairs and red ones, shed in a spray), a thin red ring spreads across the face, and a knot flashes where it landed. The shield
 * itself kicks back and rings through its weave ({@link ShieldMotion#blocked}); that part is the renderer's.</li>
 * <li><b>REFLECT</b> (move 2): a shot sent back. A tendril snaps from the shield to the projectile (out in a tick and a half,
 * held, then drawn back), black with a red strand twisting round it, with a knot and a spray of hairs where it caught the shot.</li>
 * <li><b>ABSORB</b> (move 3): a shot stopped and taken (or a player's trident bounced). The shield gives a smaller kick, and a short
 * fizzle of sparks (no ring) flies toward where the shot was: a snap, not a blow.</li>
 * </ul>
 * All are drawn in the owner's colour, from the shield's own point: the one the sheath layer records for the shield on any arm drawn in the
 * world, else (your own arm in first person, which the hand pass draws, or one that wasn't drawn this frame) worked out from the camera or the
 * eyes. The server plays the sounds for everyone, so none here.
 */
public final class ShieldFx {
	/** The shield's moves, in bits 0-7 of FX_ACCESSORY's {@code arg}; the server's {@code ShieldAccessory.FX_*}. Never renumber. */
	public static final int BLOCK = 1, REFLECT = 2, ABSORB = 3;
	/** How long each shows (ticks), and how long the reflect's tendril takes to go out. */
	static final float BLOCK_LIFE = 8F, REFLECT_LIFE = 6F, REFLECT_OUT = 1.5F, ABSORB_LIFE = 5F;
	/** The most effects drawn at once. */
	private static final int MAX = 16;
	private static final float PX = 16F;
	private static final int HAIRS = 12;

	/** One live effect. The shot's last known point is kept so the effect still has somewhere to go when the entity is gone. */
	private static final class Shot {
		final int origin, other, move, seed;
		final long start;
		double ox = Double.NaN, oy, oz;

		Shot(int origin, int other, int move, int seed, long start) {
			this.origin = origin;
			this.other = other;
			this.move = move;
			this.seed = seed;
			this.start = start;
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();
	private static final Static ST = new Static();
	private static final double[] DATA = new double[8 * HAIRS];
	/** Scratch for the frame being drawn: the face's normal and two directions square to it, and the point aimed at. */
	private static final double[] N = new double[3], PA = new double[3], QA = new double[3], TARGET = new double[3];

	private ShieldFx() {}

	/** The shield's move in an FX_ACCESSORY {@code arg}, or 0 when it is not the shield's (another accessory's place, or a move it doesn't have). */
	static int move(int arg) {
		if (ArmProtocol.accessoryPlace(arg) != ArmProtocol.ACCESSORY_PLACE_SHIELD) return 0;
		int move = ArmProtocol.accessoryMove(arg);
		return move >= BLOCK && move <= ABSORB ? move : 0;
	}

	static boolean handles(int arg) {
		return move(arg) != 0;
	}

	static void start(ArmFxEvent e) {
		int move = move(e.arg());
		if (move == 0) return;
		// the owner's own shield kicks and rings: that is the renderer's, from the same event
		ShieldMotion.blocked(e.origin(), e.receivedTick(), power(move), e.seed());
		int[] ids = e.ids();
		int other = ids.length > 0 ? ids[0] : -1;
		if (SHOTS.size() >= MAX) SHOTS.remove(0);
		Shot shot = new Shot(e.origin(), other, move, e.seed(), e.receivedTick());
		long[] positions = e.positions();
		if (move != BLOCK && positions.length > 0) {
			// the block the projectile was in stands in for it until the entity is found (BlockPos.asLong: x 26 bits at 38, z 26 bits at 12, y 12 bits at 0)
			long p = positions[0];
			shot.ox = (p >> 38) + 0.5;
			shot.oy = (p << 52 >> 52) + 0.5;
			shot.oz = (p << 26 >> 38) + 0.5;
		}
		SHOTS.add(shot);
	}

	/** How hard each move hits the shield itself: a blow 1, a reflect (the tendril throws it) 1.5, an absorb a smaller kick. */
	static float power(int move) {
		return move == REFLECT ? 1.5F : move == ABSORB ? 0.7F : 1F;
	}

	static void tick() {
		long now = Arms.TICK;
		SHOTS.removeIf(s -> now - s.start > life(s.move) + 1);
	}

	static void clear() {
		SHOTS.clear();
		ShieldMotion.clear();
	}

	static float life(int move) {
		return move == REFLECT ? REFLECT_LIFE : move == ABSORB ? ABSORB_LIFE : BLOCK_LIFE;
	}

	// ------------------------------------------------------------------ timelines (pure, tested)

	/** The sparks' spread, 0..1: out fast, easing off. */
	static float spread(float age) {
		float u = ArmMath.clamp01(age / BLOCK_LIFE);
		float x = 1F - u;
		return 1F - x * x * x;
	}

	/** The ring's size across the face, 0..1 of its reach, over the first 6 ticks. */
	static float ring(float age) {
		float u = ArmMath.clamp01(age / 6F);
		float x = 1F - u;
		return 1F - x * x;
	}

	/** The reflect's tendril: out fast over {@link #REFLECT_OUT} ticks, held to the last two, and drawn back. */
	static float snap(float age) {
		if (age <= 0F) return 0F;
		if (age < REFLECT_OUT) {
			float x = 1F - age / REFLECT_OUT;
			return 1F - x * x;
		}
		float hold = REFLECT_LIFE - 2F;
		return age < hold ? 1F : 1F - ArmMath.sm((age - hold) / 2F);
	}

	// ------------------------------------------------------------------ where the shield is

	/**
	 * The shield's face in the world: the point the sheath layer recorded for it, else for your own shield in first person the hand
	 * pass's spot carried through the camera, else the eyes' estimate on the off-hand side.
	 */
	static @Nullable Vec3 anchor(int origin, ArmFxFrame f) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !(level.getEntity(origin) instanceof Entity owner)) return null;
		boolean offLeft = !(owner instanceof Player p) || p.getMainArm() == HumanoidArm.RIGHT;
		// the overlay point only while the overlay draws the shield: on First-person Model's body (2026-11-24) the recorded body point is the shield's place
		if (ArmFx.isOwnFirstPerson(origin, f.firstPersonCamera()) && !dev.morphreborn.client.arm.model.ArmRender.accessoryOnBody(ArmProtocol.SHIELD)) {
			var cam = mc.gameRenderer.mainCamera();
			Vector3f v = ShieldRenderer.restPointFirstPerson(offLeft, new Vector3f());
			var left = cam.leftVector();
			var up = cam.upVector();
			var fwd = cam.forwardVector();
			// view space is x right, y up, z away (px): world = camera + right * x + up * y + forward * -z
			double x = -v.x / PX, y = v.y / PX, z = -v.z / PX;
			return f.camPos().add(left.x() * x + up.x() * y + fwd.x() * z, left.y() * x + up.y() * y + fwd.y() * z, left.z() * x + up.z() * y + fwd.z() * z);
		}
		Vec3 drawn = ArmTips.get(origin, false, ArmTips.accessory(ArmProtocol.SHIELD));
		if (drawn != null) return drawn;
		float pt = f.partialTick();
		Vec3 eye = owner.getEyePosition(pt);
		Vec3 look = owner.getViewVector(pt);
		double yaw = Math.toRadians(owner.getViewYRot(pt));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		double side = offLeft ? -1 : 1;
		return eye.add(look.scale(0.75)).add(right.scale(0.32 * side)).add(0, -0.45, 0);
	}

	// ------------------------------------------------------------------ drawing

	static void collect(ArmFxFrame f) {
		if (SHOTS.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		for (Shot s : SHOTS) {
			float age = Arms.age(s.start, f.partialTick());
			if (age < 0F || age > life(s.move)) continue;
			Vec3 at = anchor(s.origin, f);
			if (at == null || !TendrilFx.finite(at)) continue;
			ArmTint.owner(s.origin); // each is drawn in its owner's colour
			if (s.move == REFLECT) reflect(f, level, s, at, age);
			else block(f, level, s, at, age);
		}
	}

	/**
	 * Where the other party is, into {@link #TARGET}: the live entity (the point on it at {@code lift} of its height; remembered), else the
	 * last point known (the projectile's block, from the message). False when there is none.
	 */
	private static boolean aimPoint(ClientLevel level, Shot s, float pt, double lift) {
		Entity other = s.other >= 0 ? level.getEntity(s.other) : null;
		if (other != null && !other.isRemoved()) {
			s.ox = Mth.lerp((double) pt, other.xo, other.getX());
			s.oy = Mth.lerp((double) pt, other.yo, other.getY()) + other.getBbHeight() * lift;
			s.oz = Mth.lerp((double) pt, other.zo, other.getZ());
		}
		if (!Double.isFinite(s.ox) || !Double.isFinite(s.oy) || !Double.isFinite(s.oz)) return false;
		TARGET[0] = s.ox;
		TARGET[1] = s.oy;
		TARGET[2] = s.oz;
		return true;
	}

	/** The way the face looks, into {@link #N}: toward what struck it, else along the owner's look. */
	private static void facing(ClientLevel level, Shot s, Vec3 at, float pt) {
		if (aimPoint(level, s, pt, 0.6)) {
			double dx = TARGET[0] - at.x, dy = TARGET[1] - at.y, dz = TARGET[2] - at.z;
			double len2 = dx * dx + dy * dy + dz * dz;
			if (len2 > 1e-4) {
				double inv = 1.0 / Math.sqrt(len2);
				N[0] = dx * inv;
				N[1] = dy * inv;
				N[2] = dz * inv;
				return;
			}
		}
		Entity owner = level.getEntity(s.origin);
		if (owner == null) {
			N[0] = 0;
			N[1] = 0;
			N[2] = 1;
		} else {
			Vec3 look = owner.getViewVector(pt);
			N[0] = look.x;
			N[1] = look.y;
			N[2] = look.z;
		}
	}

	/** Two directions square to the unit {@code (nx, ny, nz)}, into {@link #PA} and {@link #QA}. */
	private static void frame(double nx, double ny, double nz) {
		double hx = 0, hy = 1, hz = 0;
		if (Math.abs(ny) >= 0.9) {
			hx = 1;
			hy = 0;
		}
		double ax = ny * hz - nz * hy, ay = nz * hx - nx * hz, az = nx * hy - ny * hx;
		double inv = 1.0 / Math.sqrt(ax * ax + ay * ay + az * az);
		ax *= inv;
		ay *= inv;
		az *= inv;
		PA[0] = ax;
		PA[1] = ay;
		PA[2] = az;
		QA[0] = ny * az - nz * ay;
		QA[1] = nz * ax - nx * az;
		QA[2] = nx * ay - ny * ax;
	}

	private static void block(ArmFxFrame f, ClientLevel level, Shot s, Vec3 at, float age) {
		float pt = f.partialTick();
		boolean fizzle = s.move == ABSORB;
		facing(level, s, at, pt);
		double nx = N[0], ny = N[1], nz = N[2];
		Vec3 face = new Vec3(at.x + nx * 0.1, at.y + ny * 0.1, at.z + nz * 0.1);
		int light = TendrilFx.light(level, at, face);
		float life = life(s.move);
		float spread = spread(age * BLOCK_LIFE / life);
		float fade = 1F - ArmMath.clamp01(age / life);
		// two directions square to the face's normal
		frame(nx, ny, nz);
		double pax = PA[0], pay = PA[1], paz = PA[2], qax = QA[0], qay = QA[1], qaz = QA[2];
		// the sparks: black hairs, then red ones, thrown out of the point in a cone about the normal (an absorb: fewer, and shorter)
		for (int pass = 0; pass < 2; pass++) {
			int count = pass == 0 ? (fizzle ? 5 : 7) : (fizzle ? 3 : 5);
			float longest = fizzle ? 4.5F : 7F;
			for (int i = 0; i < count; i++) {
				int k = pass * 7 + i;
				float psi = ArmMath.TAU * TendrilFx.hash(s.seed, k);
				float spreadAngle = 1.15F * (0.35F + 0.65F * TendrilFx.hash(s.seed + 1, k));
				double sa = Math.sin(spreadAngle), ca = Math.cos(spreadAngle);
				double cp = Math.cos(psi), sp = Math.sin(psi);
				double dx = nx * ca + (pax * cp + qax * sp) * sa;
				double dy = ny * ca + (pay * cp + qay * sp) * sa;
				double dz = nz * ca + (paz * cp + qaz * sp) * sa;
				double dist = (0.05 + 0.55 * TendrilFx.hash(s.seed + 2, k)) * spread;
				float len = longest * fade * (0.6F + 0.8F * TendrilFx.hash(s.seed + 3, k)) * (0.4F + 0.6F * (1F - spread));
				float w = (pass == 0 ? 0.8F : 0.55F) * fade;
				if (len < 0.4F || w < 0.03F) continue;
				int o = 8 * i;
				DATA[o] = face.x + dx * dist;
				DATA[o + 1] = face.y + dy * dist;
				DATA[o + 2] = face.z + dz * dist;
				DATA[o + 3] = dx;
				DATA[o + 4] = dy;
				DATA[o + 5] = dz;
				DATA[o + 6] = len;
				DATA[o + 7] = w;
			}
			TendrilFx.filaments(f, DATA, count, pass == 0 ? ArmLayer.BLACK : ArmLayer.RED, light, s.seed + pass);
		}
		// the ring spreading across the face, thin and red, thinning as it goes (a blow only: an absorb is a snap)
		float rg = ring(age);
		if (!fizzle && age < 6F && rg > 0.02F) submitRing(f, face, nx, ny, nz, (1F + 10F * rg), 0.5F * (1F - rg) + 0.12F, light, s.seed);
		// the knot where it landed, for the first two ticks
		if (age < 2F) TendrilFx.knot(f, face, (fizzle ? 2F : 3F) * (1F - age / 2F), ArmLayer.RED, light, s.seed);
	}

	// the ring's frame, for the lambda: the centre is the pose, so only the two axes and the size matter
	private static float ru0, ru1, ru2, rv0, rv1, rv2, ringR, ringW;
	private static final Loft.CurveFn RING = (t, out) -> {
		float a = ArmMath.TAU * t;
		float c = (float) Math.cos(a) * ringR, sn = (float) Math.sin(a) * ringR;
		out.set(ru0 * c + rv0 * sn, ru1 * c + rv1 * sn, ru2 * c + rv2 * sn);
	};
	private static final Loft.RadiusFn RING_RADIUS = t -> ringW;
	private static final Vector3f UP = new Vector3f(0F, 1F, 0F);

	/** The ring across the face at {@code at}, in the plane square to {@code (nx, ny, nz)}; its two axes are {@link #PA} and {@link #QA} ({@link #frame} was called). */
	private static void submitRing(ArmFxFrame f, Vec3 at, double nxd, double nyd, double nzd, float radiusPx, float widthPx, int light, int seed) {
		float a0 = (float) PA[0], a1 = (float) PA[1], a2 = (float) PA[2], b0 = (float) QA[0], b1 = (float) QA[1], b2 = (float) QA[2];
		float time = f.time();
		float nx = (float) nxd, ny = (float) nyd, nz = (float) nzd;
		TendrilFx.submit(f, at, ArmLayer.RED, light, m -> {
			ru0 = a0;
			ru1 = a1;
			ru2 = a2;
			rv0 = b0;
			rv1 = b1;
			rv2 = b2;
			ringR = radiusPx;
			ringW = widthPx;
			ST.set(time, TendrilFx.ACTIVE, 1F, seed);
			UP.set(nx, ny, nz);
			Loft.closedTube(m, RING, 14, 3, RING_RADIUS, ArmLayer.RED, ArmLayer.RED, ST, 84, 2F, 0F, UP, 1F);
		});
	}

	private static void reflect(ArmFxFrame f, ClientLevel level, Shot s, Vec3 at, float age) {
		float pt = f.partialTick();
		Vec3 to;
		// the projectile (the entity while it is there, else where it was); with neither, the tendril snaps out along where the shield faces
		if (aimPoint(level, s, pt, 0.5)) {
			to = new Vec3(TARGET[0], TARGET[1], TARGET[2]);
		} else {
			facing(level, s, at, pt);
			to = new Vec3(at.x + N[0] * 3.0, at.y + N[1] * 3.0, at.z + N[2] * 3.0);
		}
		if (!TendrilFx.finite(to)) return;
		double lx = to.x - at.x, ly = to.y - at.y, lz = to.z - at.z;
		double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
		if (len < 0.4) return;
		float reach = snap(age);
		int light = TendrilFx.light(level, at, to);
		float phase = s.seed * 0.37F;
		// black, with a red strand twisting about it
		TendrilFx.draw(f, at, to, reach, phase, 1.5F, 0.45F, 0.6F, 0.45F, ArmLayer.BLACK, light);
		TendrilFx.draw(f, at, to, reach, phase + 1.9F, 0.8F, 0.3F, 0.35F, 0.5F, ArmLayer.RED, light);
		// where it caught the shot: a knot, and a spray of hairs from it
		float bite = age - REFLECT_OUT;
		if (bite >= 0F && bite < 3F) {
			TendrilFx.knot(f, to, 3F * (1F - bite / 3F), ArmLayer.RED, light, s.seed);
			double ax = lx / len, ay = ly / len, az = lz / len;
			frame(ax, ay, az);
			double pax = PA[0], pay = PA[1], paz = PA[2], qax = QA[0], qay = QA[1], qaz = QA[2];
			float fade = 1F - bite / 3F;
			int count = 6;
			for (int i = 0; i < count; i++) {
				float psi = ArmMath.TAU * TendrilFx.hash(s.seed, i);
				float sa = 0.9F;
				double cp = Math.cos(psi), sp = Math.sin(psi);
				double dx = -ax * 0.3 + (pax * cp + qax * sp) * sa;
				double dy = -ay * 0.3 + (pay * cp + qay * sp) * sa;
				double dz = -az * 0.3 + (paz * cp + qaz * sp) * sa;
				double dist = 0.05 + 0.3 * (bite / 3F) * (0.5 + 0.5 * TendrilFx.hash(s.seed + 1, i));
				int o = 8 * i;
				DATA[o] = to.x + dx * dist;
				DATA[o + 1] = to.y + dy * dist;
				DATA[o + 2] = to.z + dz * dist;
				DATA[o + 3] = dx;
				DATA[o + 4] = dy;
				DATA[o + 5] = dz;
				DATA[o + 6] = 6F * fade * (0.6F + 0.8F * TendrilFx.hash(s.seed + 2, i));
				DATA[o + 7] = 0.6F * fade;
			}
			TendrilFx.filaments(f, DATA, count, ArmLayer.BLACK, light, s.seed + 5);
		}
	}
}
