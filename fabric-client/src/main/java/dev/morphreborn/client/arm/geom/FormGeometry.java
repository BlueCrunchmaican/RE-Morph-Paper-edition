package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.util.function.Consumer;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * One arm form's look: the katana, the pickaxe, and phase 2's axe, spade, sickle, bow and whip. An implementation has
 * a public no-argument constructor and is listed by class name in {@link ArmGeometry}, so a form can arrive as one new
 * file; a form whose class isn't in the build is drawn by {@link PlaceholderGeometry}.
 * <p>
 * Everything is in grip space G (section 2.0): the origin at the fist's centre on the hilt or haft, +Y toward the
 * working end, +X toward the leading side (edge, pick point), +Z = X × Y, in model pixels. The item path has already
 * placed G where the vanilla sword's grip pixel is (or, in the GUI, where the form's GUI fit puts it), so a geometry
 * never deals with item transforms; it applies its own local transforms (a lobe's tilt, a recoil) through the matrices
 * it passes to {@link SkinMesh}.
 * <p>
 * Geometry is stateless between calls: every call works from the {@link ArmDraw} alone and may use static scratch
 * (render thread only). The renderer calls {@link #emit} once per layer per draw, inside its geometry lambdas.
 */
public interface FormGeometry {
	/** The form id this draws ({@code ArmProtocol.KATANA} and so on). */
	String form();

	/**
	 * Writes this form's faces of {@code layer} (only those) for {@code d} into {@code m}, in G px, with its local
	 * transforms applied inside. In first person ({@code d.ctx() == ArmDraw.CTX_FP}) the forms with a fist also draw the
	 * forearm and fist themselves. Called for BLACK and RED always, GLOW when {@code d.glow()} is on; never for SENSE.
	 */
	void emit(ArmDraw d, ArmLayer layer, SkinMesh m);

	/** The rest pose's extent, as points in G px (a few corners are enough): the item's bounding box. */
	void extents(Consumer<Vector3fc> out);

	/**
	 * Where point {@code which} ({@code ArmTips.TIP}, {@code MID} or {@code GRIP}) is for {@code d}, in G px, into
	 * {@code out}: the blade's tip, the middle of the blade (where healing tendrils latch), the grip. The world effects
	 * start and end on these.
	 */
	Vector3f point(ArmDraw d, int which, Vector3f out);

	/** The feed coordinate at the working end's tip for {@code tier}: the sheath 0..8, the fist 8..12, then the tool. */
	float feedLength(int tier);

	/** The GUI fit (section 2.0 L_GUI): the scale {@code k} (pixels of item per pixel of form). */
	float guiScale();

	/** The GUI fit: {@code g}, the item pixel (along both axes) the grip is put on. */
	float guiGripPx();

	/** True: drawn with the bow's item transforms and the bow's G frame (the recurve). */
	boolean bowTransforms();
}
