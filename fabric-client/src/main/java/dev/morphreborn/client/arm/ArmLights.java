package dev.morphreborn.client.arm;

import org.jspecify.annotations.Nullable;

/**
 * LambDynamicLights' behaviour manager, when that mod is installed: the lantern adds its beam's light through it. Kept
 * as a plain Object so nothing outside the lantern code (and arm.compat.ArmLdl, which sets it) ever loads a
 * LambDynamicLights class; without the mod it stays null and the lantern simply has no dynamic light.
 */
public final class ArmLights {
	/** A {@code dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager}, or null. */
	public static volatile @Nullable Object manager;

	private ArmLights() {}

	public static boolean available() {
		return manager != null;
	}
}
