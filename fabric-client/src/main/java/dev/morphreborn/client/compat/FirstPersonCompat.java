package dev.morphreborn.client.compat;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.MorphState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Optional support for First-person Model by tr7zw (https://modrinth.com/mod/first-person-model), which draws the
 * player's own model in first person and has a public API for other mods (not required, not bundled, none of its code
 * used; everything goes through reflection).
 *
 * <p>While First-person Model draws the body, a morphed player's body is their morph: the morph renderer already swaps
 * it in, and this class adds what a mob body needs on top of that. The mod's own toggle stays in charge: with its body
 * view switched off, first person is the plain view as before, and switching it back on brings the morph's body back.
 * <ul>
 *   <li>Placement: the mod shifts the body back by a player-shaped amount so the camera sits at the eyes. A mob's head
 *       is somewhere else (a pig's is in front of its body), so for mob morphs the shift is taken from the morph's own
 *       head instead ({@link #place}, measured by the renderer each frame). Mobs with the head on top of the body
 *       (zombies, villagers, endermen) keep the mod's own shift.</li>
 *   <li>Transformations play on the body, the placement sliding from the old form's to the new one's.</li>
 *   <li>Pauses: forms whose model has no head to put the camera at (slimes, ghasts, squid) keep the plain first-person
 *       view; a transformation pauses only if neither form has a head. A shulker held in its block pauses while its
 *       shell is shut, so the view out of the block is clear, and is drawn while it is open (my call: "you can see how
 *       the shulker would"), in its block, without whatever part of it the camera is inside.</li>
 * </ul>
 * The head itself is hidden by {@code FirstPersonMorph} at draw time.
 */
public final class FirstPersonCompat {
	private static final String API = "dev.tr7zw.firstperson.api.";
	private static @Nullable Field renderingFlag;
	/**
	 * World-space shifts that put the camera at the morph's head, for the old and new form (the same outside a
	 * transformation), and how far the transformation has got. Null means keep the mod's own (player-shaped) shift.
	 */
	private static volatile @Nullable Vec3 fromOffset;
	private static volatile @Nullable Vec3 toOffset;
	private static volatile float progress = 1F;
	/** For a form that keeps the mod's own shift (null offset): how much it is scaled (a giant-sized player's is). */
	private static volatile float fromOwnScale = 1F;
	private static volatile float toOwnScale = 1F;

	private FirstPersonCompat() {}

	public static void init() {
		try {
			// Not initialised here: the mod sets its own classes up in its own entrypoint.
			Class<?> core = Class.forName("dev.tr7zw.firstperson.FirstPersonModelCore", false, FirstPersonCompat.class.getClassLoader());
			Field flag = core.getField("isRenderingPlayer");
			Class<?> api = Class.forName(API + "FirstPersonAPI");
			Class<?> offsetHandler = Class.forName(API + "PlayerOffsetHandler");
			Class<?> activationHandler = Class.forName(API + "ActivationHandler");
			Method register = api.getMethod("registerPlayerHandler", Object.class);
			register.invoke(null, Proxy.newProxyInstance(offsetHandler.getClassLoader(), new Class<?>[] {offsetHandler}, (proxy, method, args) -> switch (method.getName()) {
				case "applyOffset" -> offset((Vec3) args[3]);
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				case "toString" -> "RE:Morph first-person offset";
				default -> null;
			}));
			register.invoke(null, Proxy.newProxyInstance(activationHandler.getClassLoader(), new Class<?>[] {activationHandler}, (proxy, method, args) -> switch (method.getName()) {
				case "preventFirstperson" -> pause();
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				case "toString" -> "RE:Morph first-person pause";
				default -> null;
			}));
			renderingFlag = flag;
			MorphClient.LOGGER.info("First-person Model found: morphs will show as your body in its first-person view.");
		} catch (ClassNotFoundException e) {
			// not installed
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			MorphClient.LOGGER.warn("First-person Model found, but not the API RE:Morph uses ({}); morphs keep the plain first-person view.", e.toString());
			renderingFlag = null;
		}
	}

	/** True while the first-person mod is drawing {@code entity} as the player's own body in first person. */
	public static boolean drawingBody(Entity entity) {
		Field flag = renderingFlag;
		if (flag == null) return false;
		Minecraft mc = Minecraft.getInstance();
		if (entity != mc.getCameraEntity() || mc.options.getCameraType() != CameraType.FIRST_PERSON) return false;
		try {
			return flag.getBoolean(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * True while the first-person mod is drawing the camera player's own body in first person, whoever the caller is drawing
	 * for: the item pipeline asks it when it submits the arm form held in the body's hand (the body's third-person pose puts
	 * that hand about half a block from the camera, see {@code ArmFormRenderer}). The mod sets its flag only around the
	 * camera player's own extract and submit, so it is false for every other player's body.
	 */
	public static boolean drawingOwnBody() {
		Field flag = renderingFlag;
		if (flag == null || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) return false;
		try {
			return flag.getBoolean(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * {@link #drawingOwnBody()} and the thing being drawn belongs to the camera entity ({@code entityId}: an arm draw's seed is its owner's
	 * id). The mod's flag is one static, set round the camera player's extract and submit and cleared at the end; if an exception in either
	 * left it set, every other player's form drawn in the world pass would read it as the own body's, so the owner is checked too
	 * (fix round, 2026-11-16).
	 */
	public static boolean drawingOwnBody(int entityId) {
		if (!drawingOwnBody()) return false;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		return camera != null && camera.getId() == entityId;
	}

	/** Where the body goes for the form being drawn; called by the renderer each frame the body view is on. */
	public static void place(@Nullable Vec3 offset) {
		place(offset, offset, 1F);
	}

	/** Same; {@code ownScale} scales the mod's own shift where {@code offset} is null (a giant-sized player). */
	public static void place(@Nullable Vec3 offset, float ownScale) {
		place(offset, offset, 1F, ownScale, ownScale);
	}

	/** Mid-transformation, with each end's scale for the mod's own shift. */
	public static void place(@Nullable Vec3 from, @Nullable Vec3 to, float t, float fromScale, float toScale) {
		place(from, to, t);
		fromOwnScale = Float.isFinite(fromScale) && fromScale > 0F ? fromScale : 1F;
		toOwnScale = Float.isFinite(toScale) && toScale > 0F ? toScale : 1F;
	}

	/** Same, mid-transformation: {@code t} runs from the old form's placement (0) to the new one's (1). */
	public static void place(@Nullable Vec3 from, @Nullable Vec3 to, float t) {
		// First-person Model adds this to the real player's position while it draws: a measurement that isn't a number must
		// never get there (a NaN height froze the game in a raycast; see FirstPersonMorph.measureDragon)
		fromOffset = finite(from) ? from : null;
		toOffset = finite(to) ? to : null;
		progress = Float.isFinite(t) ? t : 1F;
		fromOwnScale = 1F;
		toOwnScale = 1F;
	}

	private static boolean finite(@Nullable Vec3 v) {
		return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
	}

	private static Vec3 offset(Vec3 current) {
		MorphState state = localState();
		if (state == null || !state.isMorphed()) return current;
		// a held shell stays square in its block, where the camera already is: no player-shaped shift
		if (dev.morphreborn.client.morph.ShulkerLock.showsHeld(state)) return Vec3.ZERO;
		Vec3 from = fromOffset != null ? fromOffset : current.scale(fromOwnScale);
		Vec3 to = toOffset != null ? toOffset : current.scale(toOwnScale);
		float t = progress;
		return t >= 1F ? to : t <= 0F ? from : from.lerp(to, t);
	}

	/**
	 * Pause the body view for forms with no head to put the camera at (while transforming: if neither has one), and for
	 * a shulker held in its block while its shell is shut (and through a transformation into or out of one): the camera
	 * is inside the shut shell, whose faces are drawn from both sides. Open, the shell is drawn as the shulker would see
	 * it (FirstPersonMorph.beforeDraw leaves out the head, which is the camera, and any part the camera is inside), so
	 * it shows from the moment it starts to open until it has shut again.
	 */
	private static boolean pause() {
		MorphState state = localState();
		Minecraft mc = Minecraft.getInstance();
		if (state == null || !state.isMorphed() || mc.player == null) return false;
		if (dev.morphreborn.client.morph.ShulkerLock.showsHeld(state)) {
			// a transformation, or a change of the borrowed form (its melt draws the whole shell round the camera)
			if (state.isTransitioning() || state.isBorrowChanging() || state.next == null) return true;
			net.minecraft.world.entity.LivingEntity morph = MorphClient.ENTITIES.forWorld(mc.player.getUUID(), state.next);
			return !(morph instanceof net.minecraft.world.entity.monster.Shulker shell) || shell.getClientPeekAmount(1F) <= 0.01F;
		}
		java.util.UUID id = mc.player.getUUID();
		if (state.isTransitioning()) return !hasHead(id, state.prev) && !hasHead(id, state.next);
		return !hasHead(id, state.next);
	}

	private static boolean hasHead(java.util.UUID player, dev.morphreborn.client.net.@Nullable MorphRef form) {
		return form == null || dev.morphreborn.client.render.FirstPersonMorph.hasHead(player, form);
	}

	private static @Nullable MorphState localState() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : MorphClient.STATES.get(mc.player.getUUID());
	}

	// ------------------------------------------------------------------ arm forms

	/** First-person Model's render-state interface and its two arm flags, looked up once (null: not installed). */
	private static volatile boolean armFlagsLooked;
	private static @Nullable Class<?> renderStateAccess;
	private static @Nullable Method hideRightArm;
	private static @Nullable Method hideLeftArm;

	/**
	 * True when First-person Model hides {@code arm} of the player {@code state} draws: its body view leaves the arm
	 * out where it shows the vanilla first-person hands instead (its ITEMS and ALL modes), so the arm forms' sheath on
	 * that arm is left out too, and the hand pass draws the arm. Read from the mod's {@code LivingEntityRenderStateAccess}
	 * on the render state, by reflection; false without the mod or when anything about it isn't as expected.
	 */
	public static boolean hidesArm(net.minecraft.client.renderer.entity.state.AvatarRenderState state, net.minecraft.world.entity.HumanoidArm arm) {
		if (!armFlagsLooked) lookUpArmFlags();
		Class<?> access = renderStateAccess;
		if (access == null || !access.isInstance(state)) return false;
		Method flag = arm == net.minecraft.world.entity.HumanoidArm.LEFT ? hideLeftArm : hideRightArm;
		if (flag == null) return false;
		try {
			return Boolean.TRUE.equals(flag.invoke(state));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	private static synchronized void lookUpArmFlags() {
		if (armFlagsLooked) return;
		try {
			Class<?> access = Class.forName("dev.tr7zw.firstperson.access.LivingEntityRenderStateAccess", false, FirstPersonCompat.class.getClassLoader());
			hideRightArm = access.getMethod("hideRightArm");
			hideLeftArm = access.getMethod("hideLeftArm");
			renderStateAccess = access;
		} catch (ClassNotFoundException e) {
			// not installed
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			MorphClient.LOGGER.warn("First-person Model found, but not its arm flags ({}); arm sheaths show on its body regardless.", e.toString());
			renderStateAccess = null;
		}
		armFlagsLooked = true;
	}
}
