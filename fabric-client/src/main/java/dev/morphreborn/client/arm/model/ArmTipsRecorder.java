package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.FormGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Writes where each drawn arm's tip, middle and grip are in the world ({@link ArmTips}), as the arm is submitted, so the
 * world effects start and end exactly on the drawn tool (section 2.1 "Recorded points").
 * <ul>
 *   <li>Third person, in the world pass: the pose is camera-relative and world-aligned, so the world point is the camera
 *       position plus {@code pose · local}.</li>
 *   <li>First person, in the hand pass: the hand is drawn with its own field of view (the HUD's 70°, not the world's),
 *       so its pixels aren't where the same offset would be in the world. The point in view space is scaled across the
 *       screen by {@code r = tan(worldFov/2) / tan(hudFov/2)} at the same depth, which lands it on the very pixel the
 *       tool shows on, then turned back into the world.</li>
 * </ul>
 * Never from a GUI or picture-in-picture draw: their poses aren't in the world (ArmFormRenderer only calls this from
 * the world and hand passes). Render thread only.
 */
public final class ArmTipsRecorder {
	private static final int[] POINTS = {ArmTips.TIP, ArmTips.MID, ArmTips.GRIP};
	private static final Vector3f LOCAL = new Vector3f(), P = new Vector3f();
	private static final Matrix4f VIEW_INV = new Matrix4f();

	private ArmTipsRecorder() {}

	/** This frame's camera, as extracted for rendering. */
	static CameraRenderState camera() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
	}

	/** The camera's position this frame. */
	public static Vec3 cameraPos() {
		return camera().pos;
	}

	/**
	 * The inverse of the view rotation (view space to world-aligned): what the hand pass starts its pose from, and so
	 * what takes a view-space point into the hand pass's space.
	 */
	public static Matrix4f viewToWorld(Matrix4f out) {
		return camera().viewRotationMatrix.invert(out);
	}

	/** {@code r}: how much wider the world's view is than the hand's, at the same depth. 1 when it can't be worked out. */
	static float fovRatio() {
		Minecraft mc = Minecraft.getInstance();
		float world = mc.gameRenderer.mainCamera().getFov();
		float hud = camera().hudFov;
		if (!(world > 0F && world < 180F && hud > 0F && hud < 180F)) return 1F;
		double r = Math.tan(Math.toRadians(world) / 2) / Math.tan(Math.toRadians(hud) / 2);
		return Double.isFinite(r) && r > 0 ? (float) r : 1F;
	}

	/** Records {@code d}'s tip, middle and grip as drawn at {@code pose} (G space), under the owner's entity id. */
	public static void record(ArmDraw d, FormGeometry geometry, PoseStack.Pose pose, boolean firstPerson) {
		Matrix4f m = pose.pose();
		for (int which : POINTS) {
			geometry.point(d, which, LOCAL.set(0F, 0F, 0F));
			record(d.seed(), firstPerson, which, m, LOCAL);
		}
	}

	/** Records one point {@code local} of the pose {@code m}'s space (the lure, from the sheath layer or the hand pass). */
	public static void record(int entityId, boolean firstPerson, int which, Matrix4fc m, Vector3fc local) {
		m.transformPosition(P.set(local));
		if (!Float.isFinite(P.x) || !Float.isFinite(P.y) || !Float.isFinite(P.z)) return;
		Vec3 cam = cameraPos();
		if (firstPerson) {
			// into view space (the hand pass's pose is world-aligned), widen to the world's field of view, back out
			CameraRenderState camera = camera();
			camera.viewRotationMatrix.transformDirection(P);
			float r = fovRatio();
			P.x *= r;
			P.y *= r;
			camera.viewRotationMatrix.invert(VIEW_INV).transformDirection(P);
		}
		ArmTips.record(entityId, firstPerson, which, cam.x + P.x, cam.y + P.y, cam.z + P.z);
	}
}
