package dev.morphreborn.client.mixin;

import dev.morphreborn.client.render.FirstPersonMorph;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lets a render state carry "this is the local player's first-person morph body" from extraction to drawing. On the
 * base state, so it also covers the ender dragon, whose state isn't a living one.
 */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements FirstPersonMorph.Mark {
	@Unique
	private boolean morph$firstPersonBody;

	@Override
	public boolean morph$firstPersonBody() {
		return this.morph$firstPersonBody;
	}

	@Override
	public void morph$setFirstPersonBody(boolean body) {
		this.morph$firstPersonBody = body;
	}
}
