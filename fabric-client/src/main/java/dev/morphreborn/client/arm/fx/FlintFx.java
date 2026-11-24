package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.geom.FlintMotion;
import dev.morphreborn.client.arm.geom.FlintRenderer;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The flint and steel's world effects (S_ARM_FX kind FX_ACCESSORY, place 3 of the accessory ring; {@link AccessoryFx} routes the whole event
 * here and this reads the move, {@code arg & 0xFF}, as I decided on 2026-11-16: the server's encoding stands, {@code ids} never carry
 * the owner, who is the event's {@code origin}):
 * <ul>
 * <li><b>0x301 IGNITE</b> ({@code ids} = [the creature]; {@code positions} = [the block it is in]): a hit, a bow's Fire tip or a crossbow
 * bolt set something alight. Where it hit, a burst of sparks: hairs of static (black, then red) thrown back toward where it came from, bright
 * cubes that fly out, fall and cool, and a knot that flashes in the first ticks. When the owner's flint and steel is on, its knuckles clack
 * and spray ({@link FlintMotion#struck}, harder than a switch-on's; that part is the renderer's) and from the striker to the target a short
 * trail of embers is dropped, one after another as the line runs out over three ticks, that then fall and go out. When it is not on (a bow's
 * Fire tip or a crossbow's bolt lit it) there is no striker to strike or to drop embers from: the burst alone, thrown back toward the owner.</li>
 * <li><b>0x302 LIGHT</b> ({@code positions} = [the block that was lit]): the striker lit a block. The same strike, trail and burst, at the
 * block's middle.</li>
 * <li><b>0x303 SPARK</b> (no ids, no positions): the striker switched on, cosmetic. The knuckles strike once, held until the tendril has
 * grown out ({@link #SPARK_DELAY}), and that is all it does: the sparks are the renderer's, so there is nothing to draw in the world.</li>
 * </ul>
 * Everything is drawn in the owner's colour, from the striker's own point: the one the sheath layer records for it on any arm drawn in the
 * world, else (your own arm in first person, which the hand pass draws, or one that wasn't drawn this frame) worked out from the camera or
 * the eyes. The impact point is latched the first frame it can be worked out (the creature's wound, else the block it was in, else a few
 * blocks out along the owner's look), so a creature knocked back, killed by the burning or out of range never drags the sparks or makes them
 * jump. The server plays the sounds for everyone, so none here.
 */
public final class FlintFx {
	/** The moves in bits 0-7 of FX_ACCESSORY's {@code arg}, and the whole args with flint's place (3) in bits 8-11: 0x301, 0x302, 0x303. */
	public static final int MOVE_IGNITE = 1, MOVE_LIGHT = 2, MOVE_SPARK = 3;
	public static final int IGNITE = ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, MOVE_IGNITE), LIGHT = ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, MOVE_LIGHT),
			SPARK = ArmProtocol.accessoryArg(ArmProtocol.ACCESSORY_PLACE_FLINT, MOVE_SPARK);
	/**
	 * Ticks a switch-on's spark waits for its strike: the tendril takes {@link FlintMotion#BUILD_TICKS} to grow and the knuckles only strike
	 * once it is more than 0.9 grown (13 ticks in), so a strike at once would be over before it could be seen.
	 */
	public static final int SPARK_DELAY = 13;
	/** How long an ignite shows (ticks), how long its trail takes to run out to the target, and the burst's. */
	static final float LIFE = 16F, TRAIL_TICKS = 3F, BURST_LIFE = 9F;
	/** The most effects drawn at once, the embers of a trail, the burst's hairs and its flying sparks. */
	private static final int MAX = 12, EMBERS = 10, HAIRS = 12, FLYERS = 10;
	/** Blocks a falling ember or spark drops in a tick squared times a half. */
	private static final double FALL = 0.004;
	/** Blocks from its owner a hit may be and still be the striker's own strike (a melee blow's reach with room, a clicked block's). */
	private static final double STRIKE_RANGE = 8.0;
	private static final float PX = 16F;

	/** One effect. The impact point ({@code x}, {@code y}, {@code z}) is latched once, in {@link #latch}, and never moves again. */
	private static final class Shot {
		final int origin, other, level, seed;
		final long start, block;
		final boolean hasBlock, striker;
		boolean latched;
		double x, y, z;

		Shot(int origin, int other, int level, int seed, long start, boolean striker, boolean hasBlock, long block) {
			this.origin = origin;
			this.other = other;
			this.level = level;
			this.seed = seed;
			this.start = start;
			this.striker = striker;
			this.hasBlock = hasBlock;
			this.block = block;
		}
	}

	private static final List<Shot> SHOTS = new ArrayList<>();
	private static final double[] DATA = new double[8 * HAIRS];
	private static final double[] BEADS = new double[4 * Math.max(EMBERS, FLYERS)];
	/** Whether the owner (an entity id) has the flint and steel on, as the arm states say; tests put their own in. */
	static IntPredicate strikerOn = FlintFx::ownerStrikerOn;

	private FlintFx() {}

	private static boolean ownerStrikerOn(int entityId) {
		ArmState s = Arms.stateByEntity(entityId);
		return s != null && s.accessoryOn(ArmProtocol.FLINT);
	}

	/** Whether {@code arg} is one of flint's (its place, 3, in bits 8-11) with a move this client draws. */
	static boolean handles(int arg) {
		int move = ArmProtocol.accessoryMove(arg);
		return ArmProtocol.accessoryPlace(arg) == ArmProtocol.ACCESSORY_PLACE_FLINT && move >= MOVE_IGNITE && move <= MOVE_SPARK;
	}

	static void start(ArmFxEvent e) {
		if (!handles(e.arg())) return;
		int move = ArmProtocol.accessoryMove(e.arg());
		if (move == MOVE_SPARK) {
			FlintMotion.struck(e.origin(), e.receivedTick() + SPARK_DELAY, FlintMotion.POWER_SPARK, e.seed());
			return;
		}
		// a bow's Fire tip or a crossbow's bolt may light a creature with no flint and steel on (a bolt is lit at the crossbow, and the flint may be
		// off by the time it lands): then there is no striker to strike and none to drop embers from, only the burst where it landed
		// and it strikes only what it could have struck: a bow's Fire tip is offered only while the flint is on, so a far arrow's ignite finds the
		// striker "on" too, and the knuckles must not clack nor embers be dropped across thirty blocks of air for it (2026-11-16)
		boolean striker = strikerOn.test(e.origin()) && withinStrike(e);
		if (striker) FlintMotion.struck(e.origin(), e.receivedTick(), move == MOVE_IGNITE ? FlintMotion.POWER_IGNITE : FlintMotion.POWER_SPARK, e.seed());
		int other = move == MOVE_IGNITE && e.ids().length > 0 ? e.ids()[0] : -1;
		boolean hasBlock = e.positions().length > 0;
		if (SHOTS.size() >= MAX) SHOTS.remove(0);
		SHOTS.add(new Shot(e.origin(), other, Math.max(1, Math.min(3, e.level())), e.seed(), e.receivedTick(), striker, hasBlock, hasBlock ? e.positions()[0] : 0L));
	}

	/** Whether a hit this far (blocks, squared) from its owner is one the striker could have struck: melee and a block clicked are within reach, an arrow's is not. */
	static boolean strikeRange(double distSq) {
		return distSq <= STRIKE_RANGE * STRIKE_RANGE;
	}

	/** Whether the event's target (the creature, else the block) is within the striker's range of its owner; true when either is unknown here, as before. */
	private static boolean withinStrike(ArmFxEvent e) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc == null ? null : mc.level;
		if (level == null || !(level.getEntity(e.origin()) instanceof Entity owner)) return true;
		Entity other = e.ids().length > 0 ? level.getEntity(e.ids()[0]) : null;
		if (other != null) return strikeRange(owner.distanceToSqr(other));
		if (e.positions().length > 0) {
			long b = e.positions()[0];
			return strikeRange(owner.distanceToSqr(BlockPos.getX(b) + 0.5, BlockPos.getY(b) + 0.5, BlockPos.getZ(b) + 0.5));
		}
		return true;
	}

	static void tick() {
		long now = Arms.TICK;
		SHOTS.removeIf(s -> now - s.start > LIFE + 1);
	}

	static void clear() {
		SHOTS.clear();
		FlintMotion.clear();
	}

	// ------------------------------------------------------------------ timelines (pure, tested)

	/** How far the trail's head has run from the striker toward the target, 0..1: it takes {@link #TRAIL_TICKS}, quickly at first. */
	static float head(float age) {
		float u = ArmMath.clamp01(age / TRAIL_TICKS);
		float x = 1F - u;
		return 1F - x * x;
	}

	/** Where along the line (0 the striker, 1 the target) ember {@code i} of {@code n} is dropped. */
	static float dropAt(int i, int n) {
		return n <= 1 ? 0F : (float) i / (n - 1);
	}

	/** Ticks since ember {@code i} of {@code n} was dropped {@code age} ticks into the effect: negative until the head reaches it. */
	static float emberAge(float age, int i, int n) {
		return age - TRAIL_TICKS * dropAt(i, n);
	}

	/** How long ember {@code i} of an effect seeded {@code seed} burns: 6 to 12 ticks. */
	static float emberLife(int seed, int i) {
		return 6F + 6F * TendrilFx.hash(seed + 5, i);
	}

	/** The ember's size (px) at {@code a} ticks old: it starts bright and small, swells for a tick and shrinks as it burns down; 0 outside its life. */
	static float emberSize(float a, float life) {
		if (!(a >= 0F) || a >= life) return 0F;
		float u = a / life;
		return 2.4F * ArmMath.sm(a * 2F) * (1F - u) * (1F - u * 0.5F);
	}

	/** The burst's spread, 0..1: out fast, easing off. */
	static float spread(float age) {
		float u = ArmMath.clamp01(age / BURST_LIFE);
		float x = 1F - u;
		return 1F - x * x * x;
	}

	/** The knot's size (px) at the point of impact: it flashes for the first two ticks. */
	static float knot(float age) {
		return age < 2F ? 3.6F * (1F - age / 2F) : 0F;
	}

	// ------------------------------------------------------------------ where the striker is

	/**
	 * The striker's knuckles in the world: the point the sheath layer recorded for it, else for your own striker in first person the hand
	 * pass's spot carried through the camera, else the eyes' estimate on the main-arm side.
	 */
	static @Nullable Vec3 anchor(int origin, ArmFxFrame f) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !(level.getEntity(origin) instanceof Entity owner)) return null;
		boolean mainLeft = owner instanceof Player p && p.getMainArm() == HumanoidArm.LEFT;
		// the overlay point only while the overlay draws the striker: on First-person Model's body (2026-11-24) the recorded body point is its place
		if (ArmFx.isOwnFirstPerson(origin, f.firstPersonCamera()) && !dev.morphreborn.client.arm.model.ArmRender.accessoryOnBody(ArmProtocol.FLINT)) {
			var cam = mc.gameRenderer.mainCamera();
			Vector3f v = FlintRenderer.restPointFirstPerson(mainLeft, new Vector3f());
			var left = cam.leftVector();
			var up = cam.upVector();
			var fwd = cam.forwardVector();
			// view space is x right, y up, z away (px): world = camera + right * x + up * y + forward * -z
			double x = -v.x / PX, y = v.y / PX, z = -v.z / PX;
			return f.camPos().add(left.x() * x + up.x() * y + fwd.x() * z, left.y() * x + up.y() * y + fwd.y() * z, left.z() * x + up.z() * y + fwd.z() * z);
		}
		Vec3 drawn = ArmTips.get(origin, false, ArmTips.accessory(ArmProtocol.FLINT));
		if (drawn != null) return drawn;
		float pt = f.partialTick();
		Vec3 eye = owner.getEyePosition(pt);
		Vec3 look = owner.getViewVector(pt);
		double yaw = Math.toRadians(owner.getViewYRot(pt));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		double side = mainLeft ? -1 : 1;
		return eye.add(look.scale(0.7)).add(right.scale(0.4 * side)).add(0, -0.6, 0);
	}

	// ------------------------------------------------------------------ drawing

	static void collect(ArmFxFrame f) {
		if (SHOTS.isEmpty()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		for (Shot s : SHOTS) {
			float age = Arms.age(s.start, f.partialTick());
			if (age < 0F || age > LIFE) continue;
			latch(level, s, f.partialTick());
			Vec3 to = new Vec3(s.x, s.y, s.z);
			if (!TendrilFx.finite(to)) continue;
			// where it came from: the striker's knuckles, or with no striker on the owner's eyes (the direction of the burst's hairs only)
			Vec3 from = s.striker ? anchor(s.origin, f) : null;
			boolean trail = from != null;
			if (from == null) from = eyeOf(level, s.origin, f.partialTick());
			if (from == null || !TendrilFx.finite(from)) from = to.add(0, 1, 0);
			ArmTint.owner(s.origin); // each is drawn in its owner's colour
			int light = TendrilFx.light(level, from, to);
			if (trail) trail(f, s, from, to, age, light);
			if (age < BURST_LIFE) burst(f, s, from, to, age, light);
		}
	}

	private static @Nullable Vec3 eyeOf(ClientLevel level, int origin, float pt) {
		return level.getEntity(origin) instanceof Entity owner ? owner.getEyePosition(pt) : null;
	}

	/**
	 * Fixes where the hit was, once: the creature's body if it is there now, else the middle of the block the server said it stood in (or the
	 * block that was lit), else a few blocks out along the owner's look. A creature that moves, dies or is unloaded afterwards moves nothing.
	 */
	private static void latch(ClientLevel level, Shot s, float pt) {
		if (s.latched) return;
		s.latched = true;
		Entity other = s.other >= 0 ? level.getEntity(s.other) : null;
		if (other != null && !other.isRemoved()) {
			Vec3 w = TendrilFx.wound(other, pt);
			s.x = w.x;
			s.y = w.y;
			s.z = w.z;
		} else if (s.hasBlock) {
			s.x = BlockPos.getX(s.block) + 0.5;
			s.y = BlockPos.getY(s.block) + 0.5;
			s.z = BlockPos.getZ(s.block) + 0.5;
		} else {
			Entity owner = level.getEntity(s.origin);
			Vec3 at = owner == null ? new Vec3(0, 0, 2.5) : owner.getEyePosition(pt).add(owner.getViewVector(pt).scale(2.5));
			s.x = at.x;
			s.y = at.y;
			s.z = at.z;
		}
	}

	/** The embers dropped along the line from the striker to the target: they fall and go out one by one. */
	private static void trail(ArmFxFrame f, Shot s, Vec3 from, Vec3 to, float age, int light) {
		int count = 0;
		double headX = to.x - from.x, headY = to.y - from.y, headZ = to.z - from.z;
		for (int i = 0; i < EMBERS; i++) {
			float a = emberAge(age, i, EMBERS);
			float size = emberSize(a, emberLife(s.seed, i));
			if (size < 0.05F) continue;
			float at = dropAt(i, EMBERS);
			// a little off the line, each its own way, and pulled down as it burns
			double jx = (TendrilFx.hash(s.seed, i * 3) - 0.5) * 0.12, jy = (TendrilFx.hash(s.seed, i * 3 + 1) - 0.5) * 0.12, jz = (TendrilFx.hash(s.seed, i * 3 + 2) - 0.5) * 0.12;
			int o = 4 * count++;
			BEADS[o] = from.x + headX * at + jx;
			BEADS[o + 1] = from.y + headY * at + jy - FALL * a * a;
			BEADS[o + 2] = from.z + headZ * at + jz;
			BEADS[o + 3] = size;
		}
		if (count > 0) TendrilFx.beads(f, BEADS, count, ArmLayer.GLOW, light, s.seed + 3);
	}

	/** Where the hit landed: hairs of static thrown back toward where it came from, bright cubes flying out and falling, and the knot's flash. */
	private static void burst(ArmFxFrame f, Shot s, Vec3 from, Vec3 to, float age, int light) {
		Vec3 n = from.subtract(to);
		n = n.lengthSqr() > 1e-4 ? n.normalize() : new Vec3(0, 1, 0);
		Vec3 helper = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 pa = n.cross(helper).normalize();
		Vec3 qa = n.cross(pa);
		float spread = spread(age);
		float fade = 1F - ArmMath.clamp01(age / BURST_LIFE);
		float power = 1F + 0.15F * (s.level - 1);
		// the hairs: black, then red, thrown out of the point in a wide cone about the way back to the striker
		for (int pass = 0; pass < 2; pass++) {
			int count = pass == 0 ? 7 : 5;
			for (int i = 0; i < count; i++) {
				int k = pass * 7 + i;
				float psi = ArmMath.TAU * TendrilFx.hash(s.seed, k + 40);
				float cone = 1.25F * (0.3F + 0.7F * TendrilFx.hash(s.seed + 1, k));
				double sa = Math.sin(cone), ca = Math.cos(cone);
				double dx = n.x * ca + (pa.x * Math.cos(psi) + qa.x * Math.sin(psi)) * sa;
				double dy = n.y * ca + (pa.y * Math.cos(psi) + qa.y * Math.sin(psi)) * sa;
				double dz = n.z * ca + (pa.z * Math.cos(psi) + qa.z * Math.sin(psi)) * sa;
				double dist = (0.05 + 0.5 * TendrilFx.hash(s.seed + 2, k)) * spread * power;
				float len = 6F * power * fade * (0.6F + 0.8F * TendrilFx.hash(s.seed + 3, k)) * (0.4F + 0.6F * (1F - spread));
				float w = (pass == 0 ? 0.75F : 0.5F) * fade;
				int o = 8 * i;
				if (len < 0.4F || w < 0.03F) {
					// out of sight: a hair too small to draw keeps its place with no length
					DATA[o] = to.x;
					DATA[o + 1] = to.y;
					DATA[o + 2] = to.z;
					DATA[o + 3] = dx;
					DATA[o + 4] = dy;
					DATA[o + 5] = dz;
					DATA[o + 6] = 0.0;
					DATA[o + 7] = 0.0;
					continue;
				}
				DATA[o] = to.x + dx * dist;
				DATA[o + 1] = to.y + dy * dist;
				DATA[o + 2] = to.z + dz * dist;
				DATA[o + 3] = dx;
				DATA[o + 4] = dy;
				DATA[o + 5] = dz;
				DATA[o + 6] = len;
				DATA[o + 7] = w;
			}
			TendrilFx.filaments(f, DATA, count, pass == 0 ? ArmLayer.BLACK : ArmLayer.RED, light, s.seed + pass);
		}
		// the flying sparks: bright cubes out of the point, slowing, falling and shrinking as they cool
		int flyers = 0;
		for (int i = 0; i < FLYERS; i++) {
			float life = BURST_LIFE * (0.55F + 0.45F * TendrilFx.hash(s.seed + 9, i));
			float u = age / life;
			if (u >= 1F) continue;
			float psi = ArmMath.TAU * TendrilFx.hash(s.seed + 11, i);
			float cone = 1.4F * (0.2F + 0.8F * TendrilFx.hash(s.seed + 12, i));
			double sa = Math.sin(cone), ca = Math.cos(cone);
			double dx = n.x * ca + (pa.x * Math.cos(psi) + qa.x * Math.sin(psi)) * sa;
			double dy = n.y * ca + (pa.y * Math.cos(psi) + qa.y * Math.sin(psi)) * sa;
			double dz = n.z * ca + (pa.z * Math.cos(psi) + qa.z * Math.sin(psi)) * sa;
			double reach = (0.25 + 0.5 * TendrilFx.hash(s.seed + 13, i)) * power * (1F - (1F - u) * (1F - u));
			int o = 4 * flyers++;
			BEADS[o] = to.x + dx * reach;
			BEADS[o + 1] = to.y + dy * reach - FALL * age * age;
			BEADS[o + 2] = to.z + dz * reach;
			BEADS[o + 3] = 1.5F * (1F - u) * power;
		}
		if (flyers > 0) TendrilFx.beads(f, BEADS, flyers, ArmLayer.GLOW, light, s.seed + 6);
		// the knot where it landed, for the first two ticks
		float knot = knot(age);
		if (knot > 0F) TendrilFx.knot(f, to, knot * power, ArmLayer.RED, light, s.seed);
	}
}
