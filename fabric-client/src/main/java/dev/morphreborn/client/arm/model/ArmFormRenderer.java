package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.FormGeometry;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.Loft;
import dev.morphreborn.client.arm.render.SkinMesh;
import dev.morphreborn.client.arm.render.Static;
import dev.morphreborn.client.compat.FirstPersonCompat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws an arm form wherever an item is drawn. The arm's item model ({@link ArmFormItemModel}) puts this renderer on
 * the item's one layer with the arm's {@link ArmDraw} as its argument, so it is called from inside vanilla's own item
 * submit, already posed by the hand, the swing, the bow draw, the equip and other mods' hand passes; the pose it gets is
 * grip space G (or the GUI fit), in model pixels.
 * <p>
 * Per submit it finishes the draw ({@link ArmDrawFactory#finish}: LOD, tail physics, world down, the first-person
 * elbow), records the tool's points in the world and hand passes ({@link ArmTipsRecorder}), and submits one geometry
 * lambda per layer (black, red, and glow when it is on) on the shared arm render types, so every arm on screen batches
 * into three draws. Each lambda rebuilds its own layer from the frozen draw when the dispatcher runs it (D19).
 * <p>
 * While reshaping, the geometry drawn is {@link ArmDraw#drawnForm()}'s: the form being left for RESHAPE's first 7 ticks
 * (collapsing into the grip by {@link ArmDraw#reach()}), then the new one (growing as FORM does from its tick 4), so
 * there is only ever one tool, and in first person one forearm. The churning knot of RESHAPE's ticks 5..9 is drawn here,
 * at the grip, over whichever form it is.
 * <p>
 * One instance per GUI tint ({@link #of}); render thread only.
 */
public final class ArmFormRenderer implements SpecialModelRenderer<ArmDraw> {
	/** The mod's logger by name (MorphClient.LOGGER is the same one), so this class loads without the client. */
	private static final Logger LOG = LoggerFactory.getLogger("RE:Morph");
	/** The plain renderer: no tint, the light it is given. */
	public static final ArmFormRenderer INSTANCE = new ArmFormRenderer(SkinMesh.WHITE, false);

	private static final ArmLayer[] DRAWN = {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW};
	private static final Map<Long, ArmFormRenderer> TINTED = new HashMap<>();
	/** Forms whose geometry threw once already: logged the first time only. */
	private static final Set<String> FAILED = new HashSet<>();

	/** RESHAPE's knot churns for its ticks 5..9 (section 2.6): ArmDraw.knotTick runs 0..4 over them. */
	private static final float KNOT_TICKS = 4F;

	private final int tint;
	private final boolean fullBright;

	private ArmFormRenderer(int tint, boolean fullBright) {
		this.tint = tint & 0xFFFFFF;
		this.fullBright = fullBright;
	}

	/** The renderer for a GUI icon dimmed to {@code tintRgb} (0xFFFFFF: none), and drawn fully lit if asked. */
	public static ArmFormRenderer of(int tintRgb, boolean fullBright) {
		int rgb = tintRgb & 0xFFFFFF;
		if (rgb == SkinMesh.WHITE && !fullBright) return INSTANCE;
		long key = ((long) rgb << 1) | (fullBright ? 1L : 0L);
		return TINTED.computeIfAbsent(key, k -> new ArmFormRenderer(rgb, fullBright));
	}

	@Override
	public void submit(@Nullable ArmDraw draw, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, boolean hasFoil,
			int outlineColor) {
		if (draw == null) return;
		ArmDrawFactory.Pass pass = switch (draw.ctx()) {
			case ArmDraw.CTX_FP -> ArmDrawFactory.Pass.HAND;
			case ArmDraw.CTX_GUI -> ArmDrawFactory.Pass.GUI;
			default -> ArmRender.inWorldPass() ? ArmDrawFactory.Pass.WORLD : ArmDrawFactory.Pass.PIP;
		};
		Matrix4f viewToBase = pass == ArmDrawFactory.Pass.HAND ? ArmTipsRecorder.viewToWorld(new Matrix4f()) : null;
		// First-person Model's body in your own first-person view holds the form in its third-person hand, half a block from the
		// camera, and (with its default "vanilla hands: off") draws no hand pass: the bow at full draw filled the side of the view
		// and the whip's swing crossed the lens (the rig, 2026-11-16). Drawn smaller about the grip there, by how near it is. Only for
		// the camera player's own form (the draw's seed is its owner's id): the mod's flag is one static, so it is not trusted alone.
		float near = pass == ArmDrawFactory.Pass.WORLD && draw.ctx() == ArmDraw.CTX_TP && FirstPersonCompat.drawingOwnBody(draw.seed()) ? bodyScale(draw, poseStack.last()) : 1F;
		if (near < 1F) {
			poseStack.pushPose();
			poseStack.scale(near, near, near);
		}
		try {
			submit(draw, pass, poseStack, collector, fullBright ? ArmTex.FULL_BRIGHT : lightCoords, overlayCoords, viewToBase, -1, tint);
		} finally {
			if (near < 1F) poseStack.popPose();
		}
	}

	/**
	 * Each form's rest-pose box reach from its grip, in G px (the farthest of its extents), worked out once: the geometry is code, so it can't
	 * change under a resource reload. {@link NearBodyScale#reachPx} takes the larger of this and the measured reach of the drawn form.
	 */
	private static final Map<String, Float> REST_REACH_PX = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * {@link NearBodyScale} for {@code draw} at {@code pose} (G space of a world-pass submit, so camera-relative): its grip's
	 * distance from the camera and its reach in blocks, the px reach (the form being drawn, {@link ArmDraw#drawnForm()}) times the
	 * pose's own scale (the display transform's and the 1/16).
	 */
	private static float bodyScale(ArmDraw draw, PoseStack.Pose pose) {
		Matrix4f m = pose.pose();
		double dist = Math.sqrt((double) m.m30() * m.m30() + (double) m.m31() * m.m31() + (double) m.m32() * m.m32());
		double px = Math.sqrt((double) m.m00() * m.m00() + (double) m.m01() * m.m01() + (double) m.m02() * m.m02());
		String form = draw.drawnForm();
		float rest = REST_REACH_PX.computeIfAbsent(form, f -> {
			float[] far = {0F};
			ArmGeometry.get(f).extents(v -> far[0] = Math.max(far[0], v.length()));
			return far[0];
		});
		return NearBodyScale.of(dist, NearBodyScale.reachPx(form, rest) * px);
	}

	/**
	 * Submits {@code draw} at the pose on top of {@code poseStack} (G space or the GUI fit, px) in {@code pass}:
	 * finished there, recorded in the world and hand passes, one lambda per layer. {@code viewToBase} is for the
	 * first-person elbow (see {@link ArmDrawFactory#finish}); {@code forcedLod} ≥ 0 sets the LOD (the preview);
	 * {@code tintRgb} dims everything (a locked icon).
	 */
	public static void submit(ArmDraw draw, ArmDrawFactory.Pass pass, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
			@Nullable Matrix4fc viewToBase, int forcedLod, int tintRgb) {
		long started = System.nanoTime();
		try {
			PoseStack.Pose pose = poseStack.last();
			ArmDraw d = ArmDrawFactory.finish(draw, pass, pose, light, viewToBase, forcedLod);
			FormGeometry geometry = ArmGeometry.get(d.drawnForm());
			if (pass == ArmDrawFactory.Pass.WORLD || pass == ArmDrawFactory.Pass.HAND) {
				ArmTipsRecorder.record(d, geometry, pose, pass == ArmDrawFactory.Pass.HAND);
			}
			boolean knot = d.knotTick() >= 0F;
			long frame = ArmRender.frame();
			boolean preview = pass == ArmDrawFactory.Pass.PREVIEW;
			int rgb = tintRgb & 0xFFFFFF;
			for (ArmLayer layer : DRAWN) {
				if (layer == ArmLayer.GLOW && !d.glow()) continue;
				int layerLight = layer == ArmLayer.GLOW ? ArmTex.FULL_BRIGHT : light;
				collector.submitCustomGeometry(poseStack, ArmTex.type(layer, d.dissolve()),
						(p, vc) -> emit(p, vc, layer, layerLight, overlay, rgb, d, geometry, knot, frame, preview));
			}
		} catch (RuntimeException e) {
			if (FAILED.add("submit:" + draw.form())) LOG.error("Arm form {} failed to draw; later failures aren't logged", draw.form(), e);
		} finally {
			if (pass == ArmDrawFactory.Pass.PREVIEW) Stats.nanos += System.nanoTime() - started;
		}
	}

	/** One layer of one arm, inside the dispatcher: the form (while reshaping, the one drawn now), and the knot. */
	private static void emit(PoseStack.Pose pose, VertexConsumer vc, ArmLayer layer, int light, int overlay, int rgb, ArmDraw d, FormGeometry geometry,
			boolean knot, long frame, boolean preview) {
		long started = preview ? System.nanoTime() : 0L;
		SkinMesh m = SkinMesh.begin(pose, vc, light, overlay);
		m.tint(rgb);
		Loft.pulseFrom(d, geometry.feedLength(d.tier()));
		try {
			geometry.emit(d, layer, m);
			if (knot) {
				m.transform(null).tint(rgb);
				knot(d, layer, m, frame);
			}
		} catch (RuntimeException e) {
			if (FAILED.add(d.form())) LOG.error("Arm geometry for {} failed; later failures aren't logged", d.form(), e);
		} finally {
			Loft.pulseFrom(null, 0F);
		}
		if (preview) {
			Stats.nanos += System.nanoTime() - started;
			Stats.quads += m.quads();
		}
	}

	private static final Static KNOT_STATIC = new Static();
	private static final Matrix4f KNOT_LOCAL = new Matrix4f();

	/**
	 * RESHAPE's knot (ticks 5..9): 8 boxes of 0.9..1.4 px orbiting the grip at 2..3 px with a little noise, swelling in
	 * and out at the ends, red and black swapping every frame.
	 */
	static void knot(ArmDraw d, ArmLayer layer, SkinMesh m, long frame) {
		if (layer != ArmLayer.BLACK && layer != ArmLayer.RED) return;
		float t = d.knotTick();
		if (t < 0F) return;
		float envelope = ArmMath.sm(t) * (1F - ArmMath.sm(t - (KNOT_TICKS - 1F)));
		if (envelope <= 0.01F) return;
		KNOT_STATIC.set(d.time(), 1, 1F, d.seed());
		float time = d.time(), ph = d.phase();
		for (int i = 0; i < 8; i++) {
			boolean red = ((frame + i) & 1L) == 0L;
			if ((layer == ArmLayer.RED) != red) continue;
			float angle = 0.45F * time * (1F + 0.11F * i) + i * (ArmMath.TAU / 8F) + ph;
			float radius = 2F + 0.5F * (1F + (float) Math.sin(0.7F * time + 1.7F * i));
			float y = 2.2F + 0.7F * (float) Math.sin(0.5F * time + 2.3F * i);
			float size = (0.9F + 0.5F * ArmMath.frac(i * 0.618F)) * envelope;
			KNOT_LOCAL.translation(radius * (float) Math.cos(angle), y, radius * (float) Math.sin(angle))
					.rotateXYZ(0.9F * time + i, 0.7F * time + 2F * i, 0.5F * i);
			m.box(KNOT_LOCAL, -size / 2F, -size / 2F, -size / 2F, size, size, size, KNOT_STATIC.u0(7, i, 12F), KNOT_STATIC.v0(7, i, 12F), 1F, 1F, SkinMesh.WHITE);
		}
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		// the layer's own extents are set per form by the item model
	}

	@Override
	public @Nullable ArmDraw extractArgument(ItemStack stack) {
		// the item model builds the argument itself, from the holder as well as the stack
		return null;
	}

	/** What the preview screen's stress counter reads: time spent on preview arms (submit and geometry) and their quads. */
	public static final class Stats {
		static long nanos;
		static int quads;

		private Stats() {}

		/** The time and quads since the last call, and starts counting again. */
		public static long[] take() {
			long[] out = {nanos, quads};
			nanos = 0L;
			quads = 0;
			return out;
		}
	}
}
