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
public abstract class EntityRenderStateMixin implements FirstPersonMorph.Mark, dev.morphreborn.client.render.HybridMorph.Mark {
	@Unique
	private boolean morph$firstPersonBody;
	@Unique
	private @org.jspecify.annotations.Nullable EntityRenderState morph$borrowed;
	@Unique
	private boolean morph$hideHead;
	@Unique
	private boolean morph$lendsEars;

	@Override
	public @org.jspecify.annotations.Nullable EntityRenderState morph$borrowed() {
		return this.morph$borrowed;
	}

	@Override
	public void morph$setBorrowed(@org.jspecify.annotations.Nullable EntityRenderState borrowed) {
		this.morph$borrowed = borrowed;
	}

	@Override
	public boolean morph$hideHead() {
		return this.morph$hideHead;
	}

	@Override
	public void morph$setHideHead(boolean hide) {
		this.morph$hideHead = hide;
	}

	@Override
	public boolean morph$lendsEars() {
		return this.morph$lendsEars;
	}

	@Override
	public void morph$setLendsEars(boolean ears) {
		this.morph$lendsEars = ears;
	}

	@Override
	public boolean morph$firstPersonBody() {
		return this.morph$firstPersonBody;
	}

	@Override
	public void morph$setFirstPersonBody(boolean body) {
		this.morph$firstPersonBody = body;
	}
}
