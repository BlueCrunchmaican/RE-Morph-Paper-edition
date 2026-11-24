package dev.morphreborn.client.arm.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.arm.render.TendrilPath;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The arm's world effects drawn in world space: living tendrils between two points (a creature and the blade, the pick
 * and an ore, one ore and the next), and the small pieces that fly off them (beads of biomass, shed hairs, the knot a
 * tendril latches with). It is the frenzy's tendril (RageTethers) drawn the arm's way: one smooth lofted strand through
 * {@link TendrilPath} rather than a chain of boxes, the morph skin's static fizzing along it and re-patterning every tick
 * (these are all specials and hits), growing and drawing back by truncation, never by stretching.
 * <p>
 * Every draw is its own geometry submit in the layer's shared render type, so all of them, and every arm on screen,
 * batch into the same three or four draws. The pose is moved to the draw's first point (camera-relative, so far-out
 * coordinates keep their precision) and scaled by 1/16, so the geometry is written in model pixels like the arms'. A
 * lambda captures only values worked out before it is submitted; the shared scratch below is touched only inside one
 * lambda at a time (the renderer runs them one after another).
 */
public final class TendrilFx {
	/** The colour a vein seen through stone is outlined in: the outline layer takes the vertex colour. */
	public static final int SENSE_RGB = 0xC62828;
	/** Static cadences (Static.cadence): world effects are specials and hits, so they re-pattern every tick; a calm latch every 2. */
	public static final int ACTIVE = 1, CALM = 2;
	/** Model pixels a block. */
	private static final float PX = 16F;
	/** Below this (blocks) a tendril has no length and isn't drawn. */
	private static final double MIN_LENGTH = 1e-3;

	private static final Static ST = new Static();
	private static final Vector3f FROM = new Vector3f(), TO = new Vector3f();
	private static final Matrix4f LOCAL = new Matrix4f();
	private static final Quaternionf TURN = new Quaternionf();

	/** Draws one piece of world-effect geometry, in model pixels from the point the pose was moved to. */
	@FunctionalInterface
	interface Geometry {
		void emit(SkinMesh m);
	}

	private TendrilFx() {}

	/**
	 * A tendril from {@code from} to {@code to} (world), drawn out to {@code reach} (0..1) of its length from
	 * {@code from}, writhing with {@code phase}, {@code widthFromPx} wide at {@code from} and {@code widthToPx} at
	 * {@code to}, with one swelling of strength {@code swellAmp} at {@code swellPos} (0 at {@code from}, 1 at
	 * {@code to}; strength 0: none), in {@code layer}. The writhe's size follows the length, as the frenzy's does.
	 */
	public static void draw(ArmFxFrame f, Vec3 from, Vec3 to, float reach, float phase, float widthFromPx, float widthToPx, float swellPos, float swellAmp,
			ArmLayer layer, int light) {
		draw(f, from, to, reach, phase, widthFromPx, widthToPx, swellAmp != 0F ? new float[] {swellPos, swellAmp} : null, layer, light, ACTIVE);
	}

	/**
	 * As {@link #draw(ArmFxFrame, Vec3, Vec3, float, float, float, float, float, float, ArmLayer, int)}, with any number of
	 * swellings ({@code swells}: position, strength pairs; null: none) and the static's cadence. A tendril seen through
	 * stone ({@link ArmLayer#SENSE}) is drawn in {@link #SENSE_RGB}.
	 */
	public static void draw(ArmFxFrame f, Vec3 from, Vec3 to, float reach, float phase, float widthFromPx, float widthToPx, float @Nullable [] swells,
			ArmLayer layer, int light, int cadence) {
		if (!(reach > 0F)) return;
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(length > MIN_LENGTH) || !Double.isFinite(length) || !finite(from)) return;
		float tx = (float) (dx * PX), ty = (float) (dy * PX), tz = (float) (dz * PX);
		float amp = writhe((float) length) * PX;
		int pieces = TendrilPath.pieces((float) length);
		float r = Math.min(1F, reach);
		float time = f.time();
		float w0 = widthFromPx, w1 = widthToPx;
		float[] bumps = swells == null || swells.length < 2 ? null : swells.clone();
		int seed = seed(phase);
		int part = seed & 63;
		TendrilPath.WidthFn width = s -> ArmMath.lerp(w0, w1, s);
		TendrilPath.SwellFn swell = bumps == null ? null : s -> swellAt(bumps, s);
		submit(f, from, layer, light, m -> {
			FROM.set(0F);
			TO.set(tx, ty, tz);
			ST.set(time, cadence, 1F, seed);
			TendrilPath.sweep(m, FROM, TO, r, phase, time, amp, width, swell, pieces, layer, layer, ST, part);
		});
	}

	/**
	 * Beads of biomass: a small cube at each world point. {@code xyzs} holds x, y, z and the cube's size in px for each
	 * of {@code count} beads; ones smaller than a hundredth of a pixel are left out.
	 */
	public static void beads(ArmFxFrame f, double[] xyzs, int count, ArmLayer layer, int light, int seed) {
		if (count <= 0 || xyzs.length < 4) return;
		int n = Math.min(count, xyzs.length / 4);
		Vec3 at = new Vec3(xyzs[0], xyzs[1], xyzs[2]);
		if (!finite(at)) return;
		float[] rel = relative(xyzs, n, 4, at);
		float time = f.time();
		submit(f, at, layer, light, m -> {
			ST.set(time, ACTIVE, 0F, seed);
			for (int i = 0; i < n; i++) {
				float size = rel[4 * i + 3];
				if (!(size > 0.01F)) continue;
				float half = size / 2F;
				m.box(null, rel[4 * i] - half, rel[4 * i + 1] - half, rel[4 * i + 2] - half, size, size, size, ST.u0(41, i, 0F), ST.v0(41, i, 0F), 1F, 1F,
						SkinMesh.WHITE);
			}
		});
	}

	/**
	 * Shed hairs: thin strands tapering to a quarter of their width. {@code data} holds, for each of {@code count}
	 * hairs, its root (x, y, z, world), its direction (x, y, z; any length), its length and its root width (px).
	 */
	public static void filaments(ArmFxFrame f, double[] data, int count, ArmLayer layer, int light, int seed) {
		if (count <= 0 || data.length < 8) return;
		int n = Math.min(count, data.length / 8);
		Vec3 at = new Vec3(data[0], data[1], data[2]);
		if (!finite(at)) return;
		float[] rel = relative(data, n, 8, at);
		float time = f.time();
		submit(f, at, layer, light, m -> {
			ST.set(time, ACTIVE, 0F, seed);
			for (int i = 0; i < n; i++) {
				int o = 8 * i;
				float len = rel[o + 6], w = rel[o + 7];
				float dx = rel[o + 3], dy = rel[o + 4], dz = rel[o + 5];
				float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (!(len > 0.01F) || !(w > 0.005F) || !(d > 1e-6F)) continue;
				TURN.rotationTo(0F, 0F, 1F, dx / d, dy / d, dz / d);
				LOCAL.translation(rel[o], rel[o + 1], rel[o + 2]).rotate(TURN);
				m.frustum(LOCAL, len, w, w, w * 0.25F, w * 0.25F, ST.u0(43, i, 0F), ST.v0(43, i, 0F), 2F, 1F, 1F, SkinMesh.WHITE);
			}
		});
	}

	/**
	 * The knot a tendril latches with: two cubes of {@code sizePx} at {@code at}, one turned against the other, so it
	 * reads as a clenched lump rather than a box.
	 */
	public static void knot(ArmFxFrame f, Vec3 at, float sizePx, ArmLayer layer, int light, int seed) {
		if (!(sizePx > 0.01F) || !finite(at)) return;
		float time = f.time();
		float turn = 0.6F + 0.4F * ArmMath.frac(seed * 0.618F);
		submit(f, at, layer, light, m -> {
			ST.set(time, ACTIVE, 0F, seed);
			float half = sizePx / 2F;
			m.box(null, -half, -half, -half, sizePx, sizePx, sizePx, ST.u0(45, 0, 0F), ST.v0(45, 0, 0F), 1F, 1F, SkinMesh.WHITE);
			LOCAL.translation(0.25F * sizePx, 0.2F * sizePx, -0.15F * sizePx).rotateXYZ(turn, 0.7F * turn, 0.4F);
			float small = 0.8F * sizePx;
			m.box(LOCAL, -small / 2F, -small / 2F, -small / 2F, small, small, small, ST.u0(45, 1, 0F), ST.v0(45, 1, 0F), 1F, 1F, SkinMesh.WHITE);
		});
	}

	/**
	 * Submits {@code g} in {@code layer} with the pose at {@code at} (world) and in model pixels. The glow layer is
	 * drawn at full brightness; the outline layer takes no light, and its vertex colour is the outline's.
	 */
	static void submit(ArmFxFrame f, Vec3 at, ArmLayer layer, int light, Geometry g) {
		RenderType type = ArmTex.type(layer, layer != ArmLayer.SENSE && ArmTex.dissolveOn());
		int lit = layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light;
		Vec3 cam = f.camPos();
		PoseStack poseStack = f.poseStack();
		poseStack.pushPose();
		poseStack.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
		poseStack.scale(1F / PX, 1F / PX, 1F / PX);
		f.collector().submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			SkinMesh m = SkinMesh.begin(pose, buffer, lit, OverlayTexture.NO_OVERLAY);
			if (layer == ArmLayer.SENSE) m.tint(SENSE_RGB);
			g.emit(m);
		});
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ shared maths

	/** The frenzy's writhe for a tendril {@code lengthBlocks} long, in blocks: {@code min(0.35, 0.06·length + 0.05)}. */
	public static float writhe(float lengthBlocks) {
		return Math.min(0.35F, 0.06F * Math.max(0F, lengthBlocks) + 0.05F);
	}

	/** The width multiplier at {@code s} of several swellings ({@code bumps}: position, strength pairs), summed. */
	static float swellAt(float[] bumps, float s) {
		float k = 1F;
		for (int i = 0; i + 1 < bumps.length; i += 2) k += TendrilPath.swell(s, bumps[i], bumps[i + 1]) - 1F;
		return Math.max(0F, k);
	}

	/** Where a creature is wounded: the middle of its body, 0.6 of its height up. */
	public static Vec3 wound(Entity e, float partialTick) {
		return e.getPosition(partialTick).add(0, e.getBbHeight() * 0.6, 0);
	}

	/**
	 * The light for something between {@code a} and {@code b}: the brighter of the two places, block and sky light each,
	 * so a tendril reaching out of a dark cave into daylight isn't drawn black. Full light when there is no world.
	 */
	public static int light(@Nullable Level level, Vec3 a, Vec3 b) {
		if (level == null) return LightCoordsUtil.FULL_BRIGHT;
		if (!finite(a) || !finite(b)) return LightCoordsUtil.FULL_BRIGHT;
		BlockPos pa = BlockPos.containing(a), pb = BlockPos.containing(b);
		int block = Math.max(level.getBrightness(LightLayer.BLOCK, pa), level.getBrightness(LightLayer.BLOCK, pb));
		int sky = Math.max(level.getBrightness(LightLayer.SKY, pa), level.getBrightness(LightLayer.SKY, pb));
		return LightCoordsUtil.pack(block, sky);
	}

	/** A small per-tendril number from its phase, so neighbouring tendrils' static and bits never match. */
	static int seed(float phase) {
		int h = Float.floatToIntBits(phase) * 0x9E3779B1;
		return (h ^ (h >>> 15)) & 0x7FFF;
	}

	/** The fractional part of a hash of {@code i} and {@code j}: a fixed "random" number in 0..1 for bead and hair spreads. */
	static float hash(int i, int j) {
		int h = i * 0x27D4EB2D + j * 0x165667B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	static boolean finite(Vec3 v) {
		return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
	}

	/** {@code n} records of {@code stride} doubles whose first three are a world point, as px from {@code at}; the rest copied. */
	private static float[] relative(double[] data, int n, int stride, Vec3 at) {
		float[] out = new float[n * stride];
		for (int i = 0; i < n; i++) {
			int o = stride * i;
			out[o] = (float) ((data[o] - at.x) * PX);
			out[o + 1] = (float) ((data[o + 1] - at.y) * PX);
			out[o + 2] = (float) ((data[o + 2] - at.z) * PX);
			for (int k = 3; k < stride; k++) out[o + k] = (float) data[o + k];
		}
		return out;
	}
}
