package dev.morphreborn.client.arm.lantern;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import net.minecraft.core.BlockPos;

/**
 * The lantern's pool of light where its beam lands, for LambDynamicLights (https://modrinth.com/mod/lambdynamiclights):
 * a sphere centred on the beam's smoothed hit point, {@code 15·(1 − d/8)^0.7} at distance {@code d} blocks, nothing from 8
 * blocks out. Only {@link ArmLantern}'s light code makes one, and only when that mod is installed (the class implements one
 * of its interfaces, so nothing else may name it, and a build without the mod on its class path can't even refer to it);
 * the maths are in {@link ArmLantern.Spot}, which needs none of it and is what the tests exercise.
 * <p>
 * LambDynamicLights asks from its own threads, and the spot's centre is a snapshot that is replaced whole, never edited:
 * what {@link #lightAtPos} and {@link #getBoundingBox} answer always belong together.
 */
public final class SpotLightBehavior implements DynamicLightBehavior {
	private final ArmLantern.Spot spot;
	private volatile boolean removed;

	public SpotLightBehavior(double x, double y, double z) {
		spot = new ArmLantern.Spot(x, y, z);
	}

	/** Aims the light at a new centre; it only takes effect once it has moved {@link ArmLantern.Spot#MOVE_EPSILON} from the last one reported. */
	public void moveTo(double x, double y, double z) {
		spot.moveTo(x, y, z);
	}

	/** Marks the light gone, for a manager that asks ({@link #isRemoved}). */
	public void remove() {
		removed = true;
	}

	@Override
	public double lightAtPos(BlockPos pos, double falloffRatio) {
		// the sphere's own falloff stands in for the mod's linear one
		return spot.lightAt(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override
	public BoundingBox getBoundingBox() {
		int[] b = spot.box();
		return new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]);
	}

	@Override
	public boolean hasChanged() {
		return spot.changed();
	}

	@Override
	public boolean isRemoved() {
		return removed;
	}
}
