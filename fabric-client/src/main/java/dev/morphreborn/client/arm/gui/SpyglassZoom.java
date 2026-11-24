package dev.morphreborn.client.arm.gui;

import dev.morphreborn.client.Keys;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.fx.SpyglassFx;
import dev.morphreborn.client.arm.geom.SpyglassMotion;
import dev.morphreborn.client.arm.model.ArmRender;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.net.ClientNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The spyglass accessory's zoom (2026-11-16): a client key, "Spyglass zoom" (Z by default, configurable in Controls like
 * any other), that while held, with the spyglass accessory on, zooms your view as a vanilla spyglass does and tells the server.
 * <ul>
 *   <li><b>The view.</b> The zoom is a field-of-view change ({@link #fov}, read by AbstractClientPlayerMixin from the game's own
 *       {@code getFieldOfViewModifier}: there is no Fabric API event for the field of view) eased in and out over a few ticks, to
 *       the vanilla spyglass's 0.1. The rest is vanilla's: while {@link #zooming} the player counts as scoping (PlayerScopeMixin),
 *       so the game itself draws its scope vignette, hides the hands, slows the mouse and shrinks the beacon beams and sign text as
 *       it does for the item. It zooms in first person only, like the item.</li>
 *   <li><b>The server.</b> It is told with C_ARM ACTION_ACCESSORY_HOLD (9), the accessory's id ("spyglass") and a varInt 1 when the key goes
 *       down or 0 when it is let go, the server's contract (2026-11-16; the server skips C_ARM's 4-tick throttle for it, as the
 *       client does, so a tap is never held back or dropped). While the key stays down the 1 is repeated every
 *       {@link ArmProtocol#HOLD_REPEAT_TICKS} (10 seconds): the server ends a zoom it hears nothing of. At most one packet a tick; a
 *       change the server could not be told of is tried again next tick.</li>
 *   <li><b>The eye.</b> It is also this client's answer for the eye stalk ({@link SpyglassMotion.Source}): your own stalk reaches out
 *       the moment you press the key, not when the server's word comes back, and every eye looks along its owner's view.</li>
 * </ul>
 * Ticks run every client tick, paused or not (a screen opening lets go of the key and ends the zoom).
 */
public final class SpyglassZoom implements SpyglassMotion.Source {
	/** The field-of-view multiplier fully zoomed: the vanilla spyglass's (Player.getFieldOfViewModifier returns 0.1). */
	public static final float FOV_MULTIPLIER = 0.1F;
	/** How much of the zoom each tick adds going in and takes off coming out (about 5 and 4 ticks end to end, then smoothed again by the camera's own easing). */
	static final float IN_STEP = 0.2F, OUT_STEP = 0.25F;

	private static final SpyglassZoom INSTANCE = new SpyglassZoom();
	/** The key says zoom (and everything allows it), and the server has been told the key's last answer. */
	private static boolean want, sent;
	/** Ticks since the server was last told the key is held (the repeat's clock). */
	private static int sinceTold;
	/** How far zoomed, 0..1, eased once a tick. */
	private static float level;
	/** This client's own player's entity id, for the eye (-1: none). */
	private static int ownId = -1;

	private SpyglassZoom() {}

	/** ArmUi's init: lets the eye geometry ask this client what it cannot know itself. */
	public static void init() {
		SpyglassMotion.source(INSTANCE);
	}

	/** Whether the key is zooming the view now (the game then counts the player as scoping). */
	public static boolean zooming() {
		return want;
	}

	/** How far zoomed, 0..1 (eased: still above 0 for a few ticks after the key is let go). */
	public static float level() {
		return level;
	}

	/**
	 * The field-of-view modifier with the zoom applied: {@code original} (the game's own, for sprinting, flying, speed) at no zoom,
	 * the vanilla spyglass's 0.1 at full, smoothly between. Called once a tick by the game's camera, which smooths it again.
	 */
	public static float fov(float original) {
		return fov(original, level);
	}

	static float fov(float original, float level) {
		if (!(level > 0F)) return original;
		return ArmMath.lerp(original, FOV_MULTIPLIER, ArmMath.sm(level));
	}

	// ------------------------------------------------------------------ the pure parts

	/**
	 * Whether the zoom should be on: the key is held, the spyglass accessory is on (and the server offers it), the view is first person,
	 * and no screen is open. Vanilla's spyglass zooms in first person only.
	 */
	static boolean wants(boolean keyDown, boolean accessoryOn, boolean firstPerson, boolean screenOpen) {
		return keyDown && accessoryOn && firstPerson && !screenOpen;
	}

	/** One tick of the ease: {@code level} moves toward 1 or 0 by {@link #IN_STEP} or {@link #OUT_STEP}. */
	static float step(float level, boolean zooming) {
		if (zooming) return Math.min(1F, level + IN_STEP);
		return Math.max(0F, level - OUT_STEP);
	}

	/** What to tell the server this tick: +1 start, -1 stop, 0 nothing (it already knows). */
	static int sync(boolean want, boolean sent) {
		return want == sent ? 0 : want ? 1 : -1;
	}

	/** As {@link #sync(boolean, boolean)}, and +1 again (the repeat that keeps the zoom alive) once the key has been held {@code sinceTold} ticks since the server was told. */
	static int sync(boolean want, boolean sent, int sinceTold) {
		int tell = sync(want, sent);
		return tell == 0 && want && sinceTold >= ArmProtocol.HOLD_REPEAT_TICKS ? 1 : tell;
	}

	// ------------------------------------------------------------------ the tick

	/** Every client tick (ArmUi registers it): reads the key, eases the zoom, and keeps the server told. */
	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		boolean now = false;
		if (player != null && mc.level != null) {
			ownId = player.getId();
			ArmState st = Arms.state(player.getUUID());
			boolean accessoryOn = st != null && st.accessoryOn(ArmProtocol.SPYGLASS) && ClientNet.armNewFormsOffered();
			now = wants(Keys.SPYGLASS.isDown(), accessoryOn, mc.options.getCameraType().isFirstPerson(), mc.gui.screen() != null);
		} else {
			ownId = -1;
		}
		want = now;
		level = step(level, want);
		SpyglassFx.expire(); // marks the server has not renewed are over
		// the server: told when the key goes down and up (a stop is retried every tick until it goes), and again every 10 seconds
		// while it stays down. With no server to tell (left it, or an older one) there is nothing owed, so the book is closed
		int tell = sync(want, sent, sinceTold);
		if (tell > 0) {
			if (ClientNet.sendAccessoryHold(ArmProtocol.SPYGLASS, true)) {
				sent = true;
				sinceTold = 0;
			}
		} else if (tell < 0) {
			if (!ClientNet.armNewFormsOffered() || ClientNet.sendAccessoryHold(ArmProtocol.SPYGLASS, false)) sent = false;
		}
		if (sent && want) sinceTold++;
	}

	/** Leaving a world or server: the zoom ends and nothing is owed to the server. */
	public static void reset() {
		SpyglassFx.clearMark(); // not on a new press: a quick release and press never reaches the server's coalescing, so it keeps the marks and so does this
		want = false;
		sent = false;
		sinceTold = 0;
		level = 0F;
		ownId = -1;
	}

	// ------------------------------------------------------------------ what the eye asks

	@Override
	public boolean ownZoom(int entityId) {
		return want && entityId == ownId;
	}

	@Override
	public boolean look(int entityId, float pt, float[] out) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		Entity e = mc.level.getEntity(entityId);
		if (e == null) return false;
		out[0] = e.getViewYRot(pt);
		out[1] = e.getViewXRot(pt);
		return true;
	}

	/** The eye draws in a lambda the game runs after the world's own submits, so the world pass's flag is already off: the draw's own is read (ArmSheathLayer sets it from the pass it was submitted in). */
	@Override
	public boolean worldPass() {
		return ArmRender.accessoryDrawnInWorld();
	}
}
