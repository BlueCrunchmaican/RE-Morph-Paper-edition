package dev.morphreborn.client.morph;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.mixin.ShulkerAccessor;
import dev.morphreborn.client.net.MorphRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ShulkerRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A shulker main body held in a block's space, as a real shulker sits: on a floor, on a wall or upside down on a
 * ceiling, square in its block, its shell never turned with the player. The server holds the player there and decides
 * everything; it says so with vanilla's own NBT keys in the morph state's overlay, {@code {AttachFace:<b>b,Peek:<p>b}},
 * sent exactly while the shown main body is a held shulker (or about to be one). ClientNet keeps them out of the morph's
 * data ({@link MorphState#lockFace}, {@link MorphState#peek}); this class applies them:
 * <ul>
 *   <li>each tick, on the main body's morph entity: the attach face, the lid's opening (the entity animates toward it as
 *       a real shulker does), its place in the block and a body yaw of 0 ({@link #apply});</li>
 *   <li>each frame, on the main body's render state: the same, and the head turned in the attach face's frame as
 *       vanilla's ShulkerLookControl turns it, never tilted ({@link #pin});</li>
 *   <li>the local player's own movement, which is cancelled while the body is held ({@link #holdsLocalPlayer}).</li>
 * </ul>
 * A shulker that is only borrowed (another body wearing a shulker head) is never held: the keys only ever describe the
 * main body.
 */
public final class ShulkerLock {
	public static final String SHULKER = "minecraft:shulker";
	/** The overlay keys (vanilla's NBT names): the attach face as a Direction 3D id, and the target opening 0..100. */
	public static final String FACE_KEY = "AttachFace", PEEK_KEY = "Peek";
	public static final int MAX_PEEK = 100;

	private ShulkerLock() {}

	/**
	 * What a main-body shulker morph entity is set to each tick: attached by {@code face} and opened to {@code peek}, or,
	 * with no face, an ordinary free shulker (attached to the floor, closed) following the player as any morph does.
	 */
	public record Hold(@Nullable Direction face, int peek) {}

	public static boolean isShulker(@Nullable MorphRef ref) {
		return ref != null && SHULKER.equals(ref.entityType());
	}

	/** The per-tick hold for a main body {@code form} with this lock (null when the form isn't a shulker). */
	public static @Nullable Hold hold(@Nullable MorphRef form, @Nullable Direction face, int peek) {
		return isShulker(form) ? new Hold(face, peek) : null;
	}

	/**
	 * The face the player's physical body is held by, or null when it isn't held: the old form until half-way through a
	 * transformation, the new one after (as the server swaps the body, MorphManager.syncBody). Into a shulker from
	 * something else, the server sends the new body's face from the start only when it has already moved the player
	 * into that block (otherwise only from the half-way swap), so a face there is held from the start too; whether the
	 * server really holds them is {@link #holdsLocalPlayer}'s fly speed check. The old form's face counts only while it
	 * has one: a free shulker that lands and locks during the first half of a colour change gets the new body's face.
	 */
	public static @Nullable Direction heldFace(MorphState state) {
		if (state.isTransitioning() && state.morphProgress(0F) < 0.5F && isShulker(state.prev) && state.prevLockFace != null) return state.prevLockFace;
		return isShulker(state.next) ? state.lockFace : null;
	}

	/** A held shulker is shown: the body now, or either end of a transformation. */
	public static boolean showsHeld(MorphState state) {
		if (isShulker(state.next) && state.lockFace != null) return true;
		return state.isTransitioning() && isShulker(state.prev) && state.prevLockFace != null;
	}

	/**
	 * True when {@code entity} is the local player and their body is held in its block: their own game moves them by
	 * nothing at all (every kind of move, so not the morph's lid or a piston either). Server teleports still apply, as
	 * they set the position directly. Input is left alone: the server reads sneak and jump from it (the teleport).
	 * <p>
	 * Only while the server's own hold is in force, which it shows by a fly speed of exactly 0 (re-asserted every tick,
	 * put back on release): the morph state alone can go stale (the plugin disabled or reloaded under a held player
	 * sends no state without the keys), and a stale hold would freeze the player until they reconnect. Ability packets
	 * always arrive, whatever happens to the mod's channel.
	 */
	public static boolean holdsLocalPlayer(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (entity != mc.player || mc.player.isSpectator() || mc.player.isPassenger()) return false;
		if (mc.player.getAbilities().getFlyingSpeed() != 0F) return false;
		MorphState state = MorphClient.STATES.get(mc.player.getUUID());
		return state != null && heldFace(state) != null;
	}

	/**
	 * Where a held body sits: the bottom middle of its block, as Shulker.setPos rounds a position. Another player is
	 * taken where they are going: the game slides other players to a new position over a few ticks, and a shell that
	 * followed would hop through the blocks in between instead of playing vanilla's one teleport slide.
	 */
	public static Vec3 anchor(Player player) {
		PositionAndRotation target = player == Minecraft.getInstance().player ? null : player.getInterpolation().target();
		Vec3 at = target != null ? target.position() : player.position();
		return Vec3.atBottomCenterOf(BlockPos.containing(at.x, at.y + 0.5, at.z));
	}

	/**
	 * Each tick, after the morph entity was put where its player is (MorphSync): attach and open a main-body shulker as
	 * held, or as a free one. Shulker.setPos shuts the lid whenever the block changes, so this goes after it, every
	 * time.
	 */
	public static void apply(LivingEntity morph, Hold hold) {
		apply(morph, hold, false);
	}

	/**
	 * As {@link #apply(LivingEntity, Hold)}; {@code fresh} is a morph entity made just now (a new appearance key, such
	 * as the old body of a transformation whose overlay held a borrowed bee's sting): its lid starts where the hold
	 * has it, not shut, so an open shell doesn't snap shut and reopen.
	 */
	public static void apply(LivingEntity morph, Hold hold, boolean fresh) {
		if (!(morph instanceof Shulker shulker)) return;
		ShulkerAccessor access = (ShulkerAccessor) shulker;
		Direction face = hold.face() != null ? hold.face() : Direction.DOWN;
		if (shulker.getAttachFace() != face) access.morph$setAttachFace(face);
		int peek = hold.face() != null ? Math.clamp(hold.peek(), 0, MAX_PEEK) : 0;
		if (access.morph$getRawPeekAmount() != peek) access.morph$setRawPeekAmount(peek);
		if (fresh && peek > 0) {
			access.morph$setCurrentPeekAmount(peek * 0.01F);
			access.morph$setCurrentPeekAmountO(peek * 0.01F);
			shulker.refreshDimensions(); // the open shell's height (the lid's own tick would have done it, but it has nothing to do now)
		}
	}

	/**
	 * A main body's render state, each frame. Held: attached by {@code face}, body yaw 0, the head turned in the face's
	 * frame and never tilted (as vanilla's ShulkerLookControl), right side up (the ceiling is the attach face, not
	 * Dinnerbone's flip), at the bottom middle of its block where the morph entity was put; vanilla's slide after a
	 * teleport stays. Free: a shulker following the player, so no slide (it pulled the shell back up to a block at
	 * every block it walked into), and its body and head as smooth as the player's.
	 */
	public static void pin(ShulkerRenderState s, @Nullable LivingEntity morph, @Nullable Direction face, Player player, float partialTick) {
		if (face == null || morph == null) {
			s.renderOffset = Vec3.ZERO;
			s.attachFace = Direction.DOWN;
			s.yBodyRot = s.bodyRot;
			s.yHeadRot = s.bodyRot + s.yRot;
			return;
		}
		s.attachFace = face;
		s.bodyRot = 0F;
		s.yBodyRot = 0F;
		float yaw = localYaw(face, player.getViewVector(partialTick), player.getUpVector(partialTick));
		s.yHeadRot = yaw;
		s.yRot = yaw;
		s.xRot = 0F;
		s.isUpsideDown = false;
		s.x = morph.getX();
		s.y = morph.getY();
		s.z = morph.getZ();
	}

	/**
	 * The head's yaw for a shulker attached by {@code face} looking along {@code look} (ShulkerLookControl.getYRotD):
	 * measured in the plane of the block face it sits on, from vanilla's forward for that face. On a floor it is the
	 * player's own yaw. Looking straight along the face's normal it would be undefined; the top of the view ({@code up})
	 * decides then, so the head doesn't spin.
	 */
	public static float localYaw(Direction face, Vec3 look, Vec3 up) {
		Direction opening = face.getOpposite();
		Vector3f forward = opening.getRotation().transform(new Vector3f(0F, 0F, 1F)); // Shulker.FORWARD, south
		Vector3f right = new Vector3f(opening.getStepX(), opening.getStepY(), opening.getStepZ()).cross(forward);
		double r = right.x * look.x + right.y * look.y + right.z * look.z;
		double f = forward.x * look.x + forward.y * look.y + forward.z * look.z;
		if (Math.abs(r) <= 1.0E-5 && Math.abs(f) <= 1.0E-5) {
			r = right.x * up.x + right.y * up.y + right.z * up.z;
			f = forward.x * up.x + forward.y * up.y + forward.z * up.z;
		}
		return (float) Math.toDegrees(Math.atan2(-r, f));
	}

	/** The size of a body for framing and the camera: a shulker's type size, not its box, which grows as its lid opens. */
	public static float[] size(LivingEntity morph) {
		if (morph instanceof Shulker) return new float[] {morph.getType().getWidth() * morph.getScale(), morph.getType().getHeight() * morph.getScale()};
		return new float[] {morph.getBbWidth(), morph.getBbHeight()};
	}
}
