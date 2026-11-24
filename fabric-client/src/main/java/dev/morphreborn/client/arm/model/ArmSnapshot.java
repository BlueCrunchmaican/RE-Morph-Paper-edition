package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmTint;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.morph.MorphEntities;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import org.jspecify.annotations.Nullable;

/**
 * What a player's arm looks like this frame, taken when their avatar render state is filled (ArmHooks.extract, the
 * tail of {@code AvatarRenderer.extractRenderState}) and carried on that state to the sheath layer that draws it. That
 * hook runs for every avatar state made: the world, First-person Model's body, the paper doll and the inventory
 * preview, so the sheath follows the arm in all of them.
 * <p>
 * Only players in their own body get one (arm forms are own-body only): not the morph renderer's stand-in players, not
 * mannequins, not anyone morphed or mid-transformation, and not spectators. And only when there is something on the
 * arm: the arm held, a sheath receding after a forced retract, the recall bracelet, or the lantern.
 *
 * @param draw     the sheath's draw (third person, arm space; ArmDrawFactory.forSheath)
 * @param mainArm  the arm the sheath is on (the lantern's lure goes on the other)
 * @param slim     slim arms (3 px wide)
 * @param entityId the player's entity id (the lure's recorded point)
 * @param camera   this is the camera's own player (in first person their lure is the hand pass's, over the tool where they see it, so the world pass leaves it off their body)
 */
public record ArmSnapshot(ArmDraw draw, HumanoidArm mainArm, boolean slim, int entityId, boolean camera) {
	/** How far the tendrils must cover the arm before the player's own arm is no longer drawn (PlayerModelArmMixin). */
	public static final float REPLACE_COVER = 0.98F;

	/**
	 * Whether the tendril arm has replaced the player's own arm: a form is out and covers the whole arm, and the player's
	 * arm look isn't the plain hand (their own arm holding the tool, ArmProtocol.LOOK_HAND), which never hides it.
	 */
	public boolean replacesArm() {
		return draw.armReplaced() && ArmTint.lookOfEntity(entityId) != ArmProtocol.LOOK_HAND;
	}

	/** Where the snapshot rides on the avatar render state. */
	public static final RenderStateDataKey<ArmSnapshot> ARM = RenderStateDataKey.create(() -> "morphreborn:arm");

	/** ArmHooks.extract: takes the snapshot for {@code entity} onto {@code state}, or leaves none. */
	public static void extract(Avatar entity, AvatarRenderState state, float partialTick) {
		ArmSnapshot snapshot = take(entity, state);
		// a state that never had one gets no data map at all (most players hold no arm)
		if (snapshot != null || state.getData(ARM) != null) state.setData(ARM, snapshot);
	}

	private static @Nullable ArmSnapshot take(Avatar entity, AvatarRenderState state) {
		if (!(entity instanceof AbstractClientPlayer player) || entity instanceof MorphEntities.MorphPlayer || state.isSpectator) return null;
		MorphState morph = MorphClient.STATES.get(player.getUUID());
		if (morph != null && (morph.isMorphed() || morph.isTransitioning())) return null;
		// the game's own partial tick, not the state's: the inventory preview extracts with a fixed 1.0
		ArmDraw draw = ArmDrawFactory.forSheath(player, ArmDrawFactory.partialTick());
		if (draw == null) return null;
		return new ArmSnapshot(draw, player.getMainArm(), draw.slim(), player.getId(), player == Minecraft.getInstance().getCameraEntity());
	}
}
