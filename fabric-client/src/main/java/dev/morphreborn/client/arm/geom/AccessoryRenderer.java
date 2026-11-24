package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * One accessory's look (2026-11-16: the shield, the spyglass and flint and steel beside the lantern, each grown out of
 * the arm in the lantern's tendril style). An implementation has a public no-argument constructor and is listed by class name
 * in {@link ArmGeometry#ACCESSORIES}, so an accessory arrives as one new file; one whose class is not in the build is simply
 * not drawn (there is no placeholder, as for the lantern's lure, which has its own {@link LureRenderer}).
 * <p>
 * The sheath layer calls {@link #emitThirdPerson} once per layer in arm space A (px) of the arm the accessory sits on, while
 * {@code d.accessoryOn(id())}; the first-person hand pass calls {@link #emitFirstPerson} in its view-anchored space. Both draw
 * only the faces of {@code layer} (BLACK and RED always, GLOW when {@code d.glow()}), and read the accessory's own state from the
 * draw: {@link ArmDraw#accessoryAge} (ticks since it came on; negative while it draws back, so its build plays backwards),
 * {@link ArmDraw#accessoryArg} (the shield raised, the spyglass zooming), and the usual timelines, pulses and light.
 */
public interface AccessoryRenderer {
	/** The accessory this draws ({@code ArmProtocol.SHIELD} and so on). */
	String id();

	/** True: drawn in the space of the arm that does NOT hold the tool (the shield); false: on the arm that holds it, over the tool. */
	default boolean offArm() {
		return false;
	}

	void emitThirdPerson(ArmDraw d, ArmLayer layer, SkinMesh m);

	void emitFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m);

	/**
	 * Where the accessory sits at rest in arm space A of its arm, into {@code out}, for the world effects that start on it (recorded
	 * as {@code ArmTips.accessory(id())}); null: it has no point of its own.
	 */
	default @Nullable Vector3f restPoint(boolean slim, boolean leftArm, Vector3f out) {
		return null;
	}
}
