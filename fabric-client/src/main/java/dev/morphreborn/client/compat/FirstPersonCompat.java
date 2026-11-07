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
 *       view; a transformation pauses only if neither form has a head.</li>
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

	/** Where the body goes for the form being drawn; called by the renderer each frame the body view is on. */
	public static void place(@Nullable Vec3 offset) {
		place(offset, offset, 1F);
	}

	/** Same, mid-transformation: {@code t} runs from the old form's placement (0) to the new one's (1). */
	public static void place(@Nullable Vec3 from, @Nullable Vec3 to, float t) {
		fromOffset = from;
		toOffset = to;
		progress = t;
	}

	private static Vec3 offset(Vec3 current) {
		MorphState state = localState();
		if (state == null || !state.isMorphed()) return current;
		Vec3 from = fromOffset != null ? fromOffset : current;
		Vec3 to = toOffset != null ? toOffset : current;
		float t = progress;
		return t >= 1F ? to : t <= 0F ? from : from.lerp(to, t);
	}

	/** Pause the body view for forms with no head to put the camera at (while transforming: if neither has one). */
	private static boolean pause() {
		MorphState state = localState();
		Minecraft mc = Minecraft.getInstance();
		if (state == null || !state.isMorphed() || mc.player == null) return false;
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
}
