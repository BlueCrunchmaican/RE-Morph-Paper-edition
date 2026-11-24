package dev.morphreborn.client.arm.compat;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
import dev.morphreborn.client.arm.ArmLights;

/**
 * Optional support for LambDynamicLights (https://modrinth.com/mod/lambdynamiclights): its own entrypoint
 * ({@code lambdynlights:initializer} in fabric.mod.json) calls this when that mod is installed, and never otherwise,
 * so this is the only class that names it at all. It only keeps the light manager for the lantern ({@link ArmLights}).
 * Only its public API is compiled against; nothing of it is bundled.
 */
public final class ArmLdl implements DynamicLightsInitializer {
	@Override
	public void onInitializeDynamicLights(DynamicLightsContext context) {
		if (Boolean.getBoolean("morphreborn.disable")) return; // the mod is switched off: no light of ours either
		ArmLights.manager = context.dynamicLightBehaviorManager();
	}
}
