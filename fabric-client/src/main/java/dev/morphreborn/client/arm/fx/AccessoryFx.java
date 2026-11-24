package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;

/**
 * The accessories' world effects (S_ARM_FX kind FX_ACCESSORY, 20; the new forms, 2026-11-16). The kind is shared by the
 * accessories, and its {@code arg} is {@code (place << 8) | move} (decided 2026-11-16: the accessory's place in
 * {@link ArmProtocol#ACCESSORIES} in bits 8-11, its move in bits 0-7; {@link ArmProtocol#accessoryPlace} and
 * {@link ArmProtocol#accessoryMove} read it, and {@code ids} never carry the owner, who is the event's {@code origin}). This handler
 * only routes, by place: each accessory's effects live in a class of its own that it hands the whole event to (the shield's are
 * {@link ShieldFx}, the spyglass's {@link SpyglassFx}, flint and steel's {@link FlintFx}), and that class reads the move itself. A
 * place nobody here draws (the lantern's, 0, which sends none; or a newer server's) is dropped. A later accessory with world effects
 * takes the next place, a case in each of the four methods below, and a class of its own.
 */
public final class AccessoryFx implements FxHandler {
	public AccessoryFx() {}

	@Override
	public int[] kinds() {
		return new int[] {ArmProtocol.FX_ACCESSORY};
	}

	@Override
	public void start(ArmFxEvent e) {
		switch (ArmProtocol.accessoryPlace(e.arg())) {
			case ArmProtocol.ACCESSORY_PLACE_SHIELD -> ShieldFx.start(e);
			case ArmProtocol.ACCESSORY_PLACE_SPYGLASS -> SpyglassFx.start(e);
			case ArmProtocol.ACCESSORY_PLACE_FLINT -> FlintFx.start(e);
			default -> { }
		}
	}

	@Override
	public void tick() {
		ShieldFx.tick();
		FlintFx.tick();
	}

	@Override
	public void collect(ArmFxFrame f) {
		ShieldFx.collect(f);
		FlintFx.collect(f);
	}

	@Override
	public void clear() {
		ShieldFx.clear();
		SpyglassFx.clear();
		FlintFx.clear();
	}
}
