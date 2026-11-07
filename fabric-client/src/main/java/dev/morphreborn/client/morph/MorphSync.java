package dev.morphreborn.client.morph;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps a morph entity moving with its player (port of the original MorphState.tick / syncEntityWithPlayer):
 * position, rotation, pose and status flags are copied before and after the entity's own client tick, so mob-specific
 * animations (wings, tails, squish, AnimationStates) run while the body follows the player exactly.
 */
public final class MorphSync {
	/** Types whose client tick threw once; they are rendered without ticking from then on. */
	private static final Set<EntityType<?>> NO_TICK = new HashSet<>();

	private MorphSync() {}

	public static void tick(LivingEntity morph, Player player) {
		syncPosition(morph, player);
		if (!NO_TICK.contains(morph.getType())) {
			try {
				morph.tick();
			} catch (RuntimeException e) {
				NO_TICK.add(morph.getType());
			}
		}
		syncPosition(morph, player);
		syncStatus(morph, player);
		MorphAnimations.tickSwing(morph, player);
	}

	public static void syncPosition(LivingEntity morph, Player player) {
		morph.setPos(player.getX(), player.getY(), player.getZ());
		morph.xo = player.xo;
		morph.yo = player.yo;
		morph.zo = player.zo;
		morph.xOld = player.xOld;
		morph.yOld = player.yOld;
		morph.zOld = player.zOld;
		morph.setYRot(player.getYRot());
		morph.setXRot(player.getXRot());
		morph.yRotO = player.yRotO;
		morph.xRotO = player.xRotO;
		morph.yBodyRot = player.yBodyRot;
		morph.yBodyRotO = player.yBodyRotO;
		morph.yHeadRot = player.yHeadRot;
		morph.yHeadRotO = player.yHeadRotO;
		if (morph instanceof EnderDragon) {
			// The dragon's yaw points the other way from every other mob's: it flies along (sin, -cos) of its yaw
			// (EnderDragon.aiStep) and its renderer turns by -yaw, so the player's own yaw drew it tail first.
			morph.setYRot(Mth.wrapDegrees(player.getYRot() + 180.0F));
			morph.yRotO = player.yRotO + 180.0F;
		}
		// Other players' speed is only known when they are knocked back; their position change is the real one, and
		// some mobs animate from it (a walking bee's wings).
		morph.setDeltaMovement(player == Minecraft.getInstance().player ? player.getDeltaMovement()
				: new Vec3(player.getX() - player.xo, player.getY() - player.yo, player.getZ() - player.zo));
		morph.tickCount = player.tickCount;
	}

	private static void syncStatus(LivingEntity morph, Player player) {
		morph.setOnGround(player.onGround());
		morph.horizontalCollision = player.horizontalCollision;
		morph.verticalCollision = player.verticalCollision;
		morph.setShiftKeyDown(player.isShiftKeyDown());
		morph.setSprinting(player.isSprinting());
		morph.setSwimming(player.isSwimming());
		morph.setInvisible(player.isInvisible());
		morph.setRemainingFireTicks(player.getRemainingFireTicks());
		morph.hurtTime = player.hurtTime;
		morph.hurtDuration = player.hurtDuration;
		morph.deathTime = player.deathTime;
		syncEquipment(morph, player);
		syncAim(morph, player);
		Pose pose = MorphAnimations.pose(morph, player);
		if (morph.getPose() != pose) {
			morph.setPose(pose);
			morph.refreshDimensions();
		}
	}

	private static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND,
			EquipmentSlot.OFFHAND};

	/**
	 * The morph wears and holds whatever the player wears and holds right now (the original's syncInventory for
	 * rendering). Mobs whose models have no armour or hands simply don't draw it.
	 */
	private static void syncEquipment(LivingEntity morph, Player player) {
		for (EquipmentSlot slot : GEAR) {
			ItemStack wanted = player.getItemBySlot(slot);
			if (!ItemStack.matches(morph.getItemBySlot(slot), wanted)) morph.setItemSlot(slot, wanted.copy());
		}
		if (morph instanceof Mob mob) mob.setLeftHanded(player.getMainArm() == HumanoidArm.LEFT);
	}

	/**
	 * Ranged-weapon poses, which vanilla only shows on mobs in combat: skeletons and illusioners raise a held bow,
	 * pillagers and piglins load a crossbow while the player loads theirs, drowned wind up while a trident is drawn.
	 */
	private static void syncAim(LivingEntity morph, Player player) {
		if (!(morph instanceof Mob mob)) return;
		ItemStack using = player.isUsingItem() ? player.getUseItem() : ItemStack.EMPTY;
		boolean aiming = player.getMainHandItem().is(Items.BOW) || using.is(Items.BOW) || using.is(Items.CROSSBOW) || using.is(Items.TRIDENT);
		if (mob.isAggressive() != aiming) mob.setAggressive(aiming);
		if (mob instanceof CrossbowAttackMob crossbow) crossbow.setChargingCrossbow(using.is(Items.CROSSBOW));
	}

	/**
	 * After extracting the morph's render state, make the animation inputs exactly the player's (interpolated with the
	 * frame's partial tick), so walking, looking and swinging match what the player does.
	 */
	public static void copyAnimation(EntityRenderState morphState, EntityRenderState playerState, Player player, float partialTick) {
		morphState.x = playerState.x;
		morphState.y = playerState.y;
		morphState.z = playerState.z;
		morphState.isInvisible = playerState.isInvisible;
		morphState.outlineColor = playerState.outlineColor;
		morphState.lightCoords = playerState.lightCoords;
		morphState.displayFireAnimation = playerState.displayFireAnimation;
		if (morphState instanceof LivingEntityRenderState m && playerState instanceof LivingEntityRenderState p) {
			m.bodyRot = p.bodyRot;
			m.yRot = p.yRot;
			m.xRot = p.xRot;
			m.walkAnimationPos = p.walkAnimationPos;
			m.walkAnimationSpeed = p.walkAnimationSpeed;
			m.hasRedOverlay = p.hasRedOverlay;
			m.deathTime = p.deathTime;
			m.isInWater = p.isInWater;
			m.isUpsideDown = p.isUpsideDown || m.isUpsideDown;
		}
		if (morphState instanceof ArmedEntityRenderState armed) {
			armed.swingAnimation = player.getSwingAnimation(partialTick);
		}
		if (morphState instanceof HumanoidRenderState humanoid && playerState instanceof HumanoidRenderState p) {
			humanoid.isCrouching = p.isCrouching;
			// Sitting in a boat, minecart or saddle: humanoid models have the pose; the morph entity itself never rides.
			humanoid.isPassenger = p.isPassenger;
		}
	}
}
