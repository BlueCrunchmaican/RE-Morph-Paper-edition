package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.SkinMesh;

/**
 * The lantern's lure on its stalk over the other arm (phase 2, LureGeometry, listed by name in {@link ArmGeometry}).
 * The sheath layer calls {@link #emitThirdPerson} in arm space A of the <b>off</b> arm while the lantern is lit; the
 * first-person hand pass calls {@link #emitFirstPerson} in the hand pass's view-anchored space. Both draw only the faces
 * of {@code layer}.
 */
public interface LureRenderer {
	void emitThirdPerson(ArmDraw d, ArmLayer layer, SkinMesh m);

	void emitFirstPerson(ArmDraw d, ArmLayer layer, SkinMesh m);
}
