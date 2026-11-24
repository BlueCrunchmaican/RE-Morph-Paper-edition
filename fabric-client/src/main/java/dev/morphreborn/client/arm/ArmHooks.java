package dev.morphreborn.client.arm;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Where the arm mixins (mixin/arm) hand over to the arm renderer. The mixins stay thin and only call these; the look
 * module (arm.model.ArmRender) sets them when it loads, so a build without it draws plain vanilla items. Each starts as
 * a no-op.
 * <ul>
 *   <li>{@link #itemLayers}: at the head of {@code ItemModelResolver.appendItemLayers}, the one place every item's look
 *       passes through (first and third person, the GUI, frames, the paper doll). Return true when the stack was an arm
 *       and its layers were filled in: vanilla's own model is then skipped. Called for every item drawn, so the
 *       not-an-arm answer must be cheap.</li>
 *   <li>{@link #extract}: at the end of {@code AvatarRenderer.extractRenderState}, for every avatar render state made
 *       (the world, First-person Model's body, the paper doll, the inventory preview), mannequins and morph bodies
 *       included: the hook itself skips what has no arm.</li>
 *   <li>{@link #afterHands}: at the end of {@code FirstPersonHandsAndItemsRenderer.submitHandsWithItems}, after both
 *       first-person hands. Iris calls that method twice a frame with a shader pack (solid and translucent hand passes),
 *       so the hook keeps itself to once a frame.</li>
 * </ul>
 * {@link #live} is false while the mod is switched off ({@code -Dmorphreborn.disable}), so the client then behaves as a
 * vanilla one would even with arm stacks in view.
 */
public final class ArmHooks {
	@FunctionalInterface
	public interface ItemLayers {
		boolean append(ItemStackRenderState out, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext ctx, @Nullable Level level,
				@Nullable ItemOwner owner, int seed);
	}

	@FunctionalInterface
	public interface Extract {
		void extract(Avatar entity, AvatarRenderState state, float partialTick);
	}

	@FunctionalInterface
	public interface AfterHands {
		void afterHands(float partialTick, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState player,
				FirstPersonHandsAndItemsRenderState hands);
	}

	/**
	 * Vanilla's first-person renderer is about to submit the item in a hand (2026-11-24): the pose it has on the stack (its own animation in it) and
	 * the item's model box, for the overlay's accessories to make room for.
	 */
	@FunctionalInterface
	public interface HeldPose {
		void heldPose(boolean mainHand, org.joml.Matrix4fc pose, net.minecraft.world.item.ItemStack stack, net.minecraft.client.renderer.item.ItemStackRenderState model);
	}

	public static volatile ItemLayers itemLayers = (o, s, r, c, l, w, seed) -> false;
	public static volatile HeldPose heldPose = (m, p, s, b) -> {};
	public static volatile Extract extract = (e, s, pt) -> {};
	public static volatile AfterHands afterHands = (pt, ps, c, p, h) -> {};
	/** Set by ArmClientInit when the mod runs; the mixins that read arm stacks themselves do nothing until then. */
	public static volatile boolean live;

	private ArmHooks() {}
}
