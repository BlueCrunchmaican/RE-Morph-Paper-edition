package dev.morphreborn.client.arm.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * One frame of the arm's world effects, as {@link ArmFx} hands it to every {@link FxHandler} from the world's
 * COLLECT_SUBMITS: the world pass's pose stack (camera-relative and world-aligned, so a point is drawn at
 * {@code point − camPos}), the collector to submit into, the camera's position, the game-time partial tick, the arm
 * clock at this frame ({@code Arms.TICK + partialTick}) and whether the camera is in first person (your own arm is then
 * drawn by the hand pass, with its own field of view, and the effects reach for the points it recorded).
 */
public record ArmFxFrame(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camPos, float partialTick, float time, boolean firstPersonCamera) {}
