package dev.morphreborn.client.render;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Render state for a player who is mid-transformation. Holds fully extracted states for both forms; the dispatcher
 * mixin routes it to {@link MorphWorldRenderer#submitTransition} instead of a regular entity renderer.
 */
public final class MorphTransitionRenderState extends EntityRenderState {
	public LivingEntityRenderState from;
	public LivingEntityRenderState to;
	public EntityRenderer<?, ?> fromRenderer;
	public EntityRenderer<?, ?> toRenderer;
	/** Eased 0..1 progress of the box transition (0 = still the old body, 1 = new body). */
	public float transition;
	/** Morph skin opacity over the old/new body. */
	public float skinAlpha;
}
