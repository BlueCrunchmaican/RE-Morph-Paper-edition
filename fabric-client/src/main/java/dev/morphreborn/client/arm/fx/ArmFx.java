package dev.morphreborn.client.arm.fx;

import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.ArmClientModule;
import dev.morphreborn.client.arm.ArmFxEvent;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.ArmTips;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.render.ArmTint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The arm's world effects: what happens out in the world when an arm strikes, drains or rips (a special's swipe,
 * healing tendrils latched from a creature into the blade, a vein of ore torn out along tendrils). The in-hand
 * reactions are the look's; these are the parts drawn in world space, for the owner and every viewer who draws arms.
 * <p>
 * The effects themselves are {@link FxHandler}s, loaded by class name from {@link #HANDLERS} the way the arm modules
 * are: a handler whose class isn't in the build is skipped, so phase 2's forms add theirs as new files. Each effect from
 * the server goes to the handler that declared its kind; a handler that throws is logged once and keeps being called,
 * so one broken effect never takes the others (or the game) with it.
 * <p>
 * Drawing happens in the world's COLLECT_SUBMITS, after every entity has been submitted, so the points the arm renderer
 * recorded for this frame (the blade's tip and middle, the grip) are there to reach for. This module registers its
 * handler in a phase of its own after the default one, because it ends every frame by calling
 * {@link ArmTips#nextFrame}: anything else that reads the recorded points from COLLECT_SUBMITS (the morph effects, a
 * later lantern) runs first and still sees them as current.
 * <p>
 * It also puffs the ink of the arm's timelines at the hand (a new arm, a retract or melt ending, a forced retract
 * popping the item away). The server puffs only for players whose client doesn't draw arms, so a client that draws
 * them puffs its own, from the arm states it is sent; see {@link #endPuff}.
 */
public final class ArmFx implements ArmClientModule {
	static final List<String> HANDLERS = List.of("dev.morphreborn.client.arm.fx.HitFx", "dev.morphreborn.client.arm.fx.SwipeFx",
			"dev.morphreborn.client.arm.fx.DrainFx", "dev.morphreborn.client.arm.fx.VeinFx", "dev.morphreborn.client.arm.fx.FellFx",
			"dev.morphreborn.client.arm.fx.CleaveFx", "dev.morphreborn.client.arm.fx.BurrowFx", "dev.morphreborn.client.arm.fx.ReapFx",
			"dev.morphreborn.client.arm.fx.BoltFx", "dev.morphreborn.client.arm.fx.HarpoonFx", "dev.morphreborn.client.arm.fx.FlareFx",
			"dev.morphreborn.client.arm.fx.WhipFx", "dev.morphreborn.client.arm.fx.WhipMoveFx", "dev.morphreborn.client.arm.fx.LanternGlowFx",
			// one handler per new kind, registered ahead of their classes (2026-11-16): one not in the build is skipped (a debug line) and
			// its effect is dropped. SpearFx (FX_SPEAR), MaceFx, TridentFx, CrossbowFx, ClawsFx, AccessoryFx (FX_ACCESSORY)
			"dev.morphreborn.client.arm.fx.SpearFx", "dev.morphreborn.client.arm.fx.MaceFx", "dev.morphreborn.client.arm.fx.TridentFx",
			"dev.morphreborn.client.arm.fx.CrossbowFx", "dev.morphreborn.client.arm.fx.ClawsFx", "dev.morphreborn.client.arm.fx.AccessoryFx"
	);

	/** The COLLECT_SUBMITS phase the effects draw in: after the default one, so {@link ArmTips#nextFrame} really is last. */
	private static final Identifier PHASE = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "arm_fx");

	private static final List<FxHandler> LOADED = new ArrayList<>();
	private static final @Nullable FxHandler[] BY_KIND = new FxHandler[ArmProtocol.FX_KINDS];
	/** Handlers that threw once already: logged the first time only. */
	private static final Set<FxHandler> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());
	private static boolean started;

	/** Ink puffs (spec 2.6): a new arm, a chosen retract ending (tick 11 of 12), a melt ending, a forced retract. */
	static final int PUFF_NONE = -1, PUFF_FORM = 0, PUFF_RETRACTED = 1, PUFF_MELTED = 2, PUFF_POPPED = 3;
	/** A state this far in when first seen is someone else's arm we arrived late to: it forms without a puff. */
	private static final int FRESH_TICKS = 2;
	/** A resent state whose start is within this of the last one's is the same timeline (the network moves it a tick). */
	private static final int SAME_START_TICKS = 2;
	/** The chosen retract's puff, a tick before the item goes. */
	static final int RETRACT_PUFF_TICK = 11;
	/** A retract or melt that ran to within this of its end finished; one cut off sooner was forced. */
	private static final int END_SLACK_TICKS = 2;
	/** The most puffs waiting for a frame (a burst of states while the window is hidden can't pile up). */
	private static final int MAX_PENDING = 32;
	/** The morph skin's greys the puff's dust is drawn in (spec 2.6). */
	private static final int[] INK_GREYS = {0x0F0F0F, 0x262626};

	/** What the puffs know of one player's arm: the timeline last seen, and whether its ending has puffed yet. */
	private static final class Seen {
		int entityId, state, duration;
		long startTick, seenTick;
		boolean endPuffed;
	}

	private static final Map<UUID, Seen> SEEN = new HashMap<>();
	/** Puffs waiting for the next frame's collect, where the arm's drawn grip is known: entity id, puff kind. */
	private static final List<int[]> PENDING = new ArrayList<>();
	private static boolean puffsFailed;

	public ArmFx() {}

	@Override
	public void init() {
		if (started) return;
		started = true;
		load();
		Arms.addFxListener(ArmFx::start);
		LevelRenderEvents.COLLECT_SUBMITS.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		LevelRenderEvents.COLLECT_SUBMITS.register(PHASE, ArmFx::collect);
		// entity ids mean nothing in the next world: whatever was showing goes
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> clearAll());
	}

	@Override
	public void tick(Minecraft mc) {
		try {
			watchStates(Arms.TICK);
		} catch (RuntimeException e) {
			if (!puffsFailed) MorphClient.LOGGER.error("Arm ink puffs failed; later failures aren't logged", e);
			puffsFailed = true;
		}
		for (FxHandler handler : LOADED) {
			try {
				handler.tick();
			} catch (RuntimeException e) {
				failed(handler, "tick", e);
			}
		}
	}

	@Override
	public void reset() {
		clearAll();
	}

	/** Loads every handler in the build and maps the kinds each one declares; the first to claim a kind keeps it. */
	private static void load() {
		for (String name : HANDLERS) {
			Class<?> type;
			try {
				type = Class.forName(name, true, ArmFx.class.getClassLoader());
			} catch (ClassNotFoundException e) {
				MorphClient.LOGGER.debug("Arm effect {} isn't in this build", name);
				continue;
			} catch (LinkageError e) {
				MorphClient.LOGGER.error("Arm effect {} could not be loaded", name, e);
				continue;
			}
			if (!FxHandler.class.isAssignableFrom(type)) {
				MorphClient.LOGGER.error("Arm effect {} is not an FxHandler; skipped", name);
				continue;
			}
			FxHandler handler;
			int[] kinds;
			try {
				handler = (FxHandler) type.getDeclaredConstructor().newInstance();
				kinds = handler.kinds();
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				MorphClient.LOGGER.error("Arm effect {} failed to start; skipped", name, e);
				continue;
			}
			LOADED.add(handler);
			for (int kind : kinds == null ? new int[0] : kinds) {
				if (kind < 0 || kind >= BY_KIND.length) {
					MorphClient.LOGGER.warn("Arm effect {} declares unknown kind {}; ignored", name, kind);
				} else if (BY_KIND[kind] != null) {
					MorphClient.LOGGER.warn("Arm effect {} declares {}, which {} already draws; ignored", name, ArmProtocol.fxName(kind), BY_KIND[kind].getClass().getName());
				} else {
					BY_KIND[kind] = handler;
				}
			}
		}
		MorphClient.LOGGER.debug("Arm effects running: {}", LOADED.size());
	}

	/** An effect from the server, to the handler of its kind (kinds nobody draws, BREAK among them, are dropped here). */
	private static void start(ArmFxEvent e) {
		int kind = e.kind();
		FxHandler handler = kind >= 0 && kind < BY_KIND.length ? BY_KIND[kind] : null;
		if (handler == null) return;
		try {
			handler.start(e);
		} catch (RuntimeException ex) {
			failed(handler, "start", ex);
		}
	}

	private static void collect(LevelRenderContext ctx) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return;
			if (!PENDING.isEmpty()) spawnPuffs(mc.level);
			if (LOADED.isEmpty()) return;
			float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			ArmFxFrame frame = new ArmFxFrame(ctx.poseStack(), ctx.submitNodeCollector(), ctx.levelState().cameraRenderState.pos, partialTick,
					Arms.time(partialTick), mc.options.getCameraType().isFirstPerson());
			for (FxHandler handler : LOADED) {
				try {
					ArmTint.reset(); // a handler sets each effect's owner (ArmTint.owner); nothing carries over from the last
					handler.collect(frame);
				} catch (RuntimeException e) {
					failed(handler, "collect", e);
				}
			}
		} finally {
			ArmTint.reset();
			// last thing, every frame, effects or not: what the arm renderer records from here on is the next frame's
			ArmTips.nextFrame();
		}
	}

	private static void clearAll() {
		SEEN.clear();
		PENDING.clear();
		for (FxHandler handler : LOADED) {
			try {
				handler.clear();
			} catch (RuntimeException e) {
				failed(handler, "clear", e);
			}
		}
	}

	private static void failed(FxHandler handler, String what, RuntimeException e) {
		if (FAILED.add(handler)) MorphClient.LOGGER.error("Arm effect {} failed in {}; later failures aren't logged", handler.getClass().getName(), what, e);
	}

	// ------------------------------------------------------------------ ink puffs

	/**
	 * Looks over every arm state for the moments that puff, once a tick after the arm clock has moved: a FORM that has
	 * just begun, a chosen retract reaching its eleventh tick, and an arm that is gone, to a NONE or with no state left at
	 * all, which puffs as {@link #endPuff} says. The puff itself waits for the next frame, where the hand's drawn grip is
	 * known. A state seen for the first time well into its timeline (arriving in a world while someone forms) doesn't puff.
	 */
	private static void watchStates(long now) {
		for (ArmState s : Arms.states()) {
			Seen seen = SEEN.get(s.subject);
			boolean same = seen != null && seen.state == s.state && Math.abs(seen.startTick - s.startTick) <= SAME_START_TICKS;
			if (!same) {
				if (seen != null && s.state == ArmProtocol.STATE_NONE) {
					queue(s.entityId, endPuff(seen.state, now - seen.startTick, seen.duration, seen.endPuffed, s.why));
				}
				if (s.state == ArmProtocol.STATE_FORMING && now - s.startTick <= FRESH_TICKS) queue(s.entityId, PUFF_FORM);
				if (seen == null) SEEN.put(s.subject, seen = new Seen());
				seen.state = s.state;
				seen.startTick = s.startTick;
				seen.duration = s.duration;
				seen.endPuffed = false;
			}
			seen.entityId = s.entityId;
			seen.seenTick = now;
			if (!seen.endPuffed && s.state == ArmProtocol.STATE_RETRACTING && now - s.startTick >= RETRACT_PUFF_TICK) {
				queue(s.entityId, PUFF_RETRACTED);
				seen.endPuffed = true;
			}
		}
		// an arm whose state went altogether: a NONE with no lantern or recall to keep it, a timeline settled here, a forget
		for (Iterator<Seen> it = SEEN.values().iterator(); it.hasNext(); ) {
			Seen seen = it.next();
			if (seen.seenTick == now) continue;
			queue(seen.entityId, endPuff(seen.state, now - seen.startTick, seen.duration, seen.endPuffed, -1));
			it.remove();
		}
	}

	/**
	 * The puff for an arm whose timeline ended with no arm: {@code state} is the state it was in, {@code elapsed} ticks into
	 * its {@code duration}, {@code endPuffed} whether its own puff has played already (the retract's, at tick 11), and
	 * {@code why} the NONE's reason (-1 when the state went without a NONE being seen). A chosen retract or a melt that ran
	 * its course ends with its own puff. Anything else that ends with the arm out was forced (a slot change, a morph, a
	 * command, a world change): the item went at once, and the bigger puff masks the pop. A death puffs nothing, as the
	 * server shows nothing for one either (the body falls instead).
	 */
	static int endPuff(int state, long elapsed, int duration, boolean endPuffed, int why) {
		if (endPuffed || why == ArmProtocol.WHY_DEATH) return PUFF_NONE;
		boolean ran = elapsed >= duration - END_SLACK_TICKS;
		return switch (state) {
			case ArmProtocol.STATE_NONE -> PUFF_NONE;
			case ArmProtocol.STATE_RETRACTING -> ran ? PUFF_RETRACTED : PUFF_POPPED;
			case ArmProtocol.STATE_MELTING -> ran ? PUFF_MELTED : PUFF_POPPED;
			default -> PUFF_POPPED;
		};
	}

	/**
	 * Off (2026-11-15: no ink puff as the arm goes): the arm now turns in and out of the morph static over its
	 * own timelines (ArmDraw.armStatic), so nothing needs masking. The rules stay for players without the mod, whose puff
	 * the server sends.
	 */
	static final boolean PUFFS_ON = false;

	private static void queue(int entityId, int puff) {
		if (puff == PUFF_NONE || !PUFFS_ON) return;
		if (PENDING.size() >= MAX_PENDING) PENDING.remove(0);
		PENDING.add(new int[] {entityId, puff});
	}

	/**
	 * Puffs the waiting ink at each player's hand: the grip as drawn this frame, else the estimate from the eyes. None for a
	 * player who has gone, or is dying (the death's NONE may have been dropped before its reason was seen).
	 */
	private static void spawnPuffs(ClientLevel level) {
		RandomSource r = level.getRandom();
		for (int[] p : PENDING) {
			Entity entity = level.getEntity(p[0]);
			if (entity == null || entity.isRemoved() || entity instanceof LivingEntity living && living.isDeadOrDying()) continue;
			Vec3 at = anchor(p[0], ArmTips.GRIP);
			if (at == null || !TendrilFx.finite(at)) continue;
			switch (p[1]) {
				case PUFF_FORM -> puff(level, r, at, 4, 6, 0);
				case PUFF_RETRACTED -> puff(level, r, at, 2, 4, 0);
				case PUFF_MELTED -> puff(level, r, at, 6, 4, 0);
				default -> puff(level, r, at, 6, 0, 2);
			}
		}
		PENDING.clear();
	}

	/**
	 * {@code ink} squid ink, {@code dust} skin-grey dust and {@code smoke} large smoke at {@code at}, spread as the server's
	 * puff for players without the mod is (a particle packet's gaussian offsets and speeds), so both look alike.
	 */
	private static void puff(ClientLevel level, RandomSource r, Vec3 at, int ink, int dust, int smoke) {
		for (int i = 0; i < ink; i++) {
			level.addParticle(ParticleTypes.SQUID_INK, at.x + r.nextGaussian() * 0.08, at.y + r.nextGaussian() * 0.08, at.z + r.nextGaussian() * 0.08,
					r.nextGaussian() * 0.02, r.nextGaussian() * 0.02, r.nextGaussian() * 0.02);
		}
		for (int i = 0; i < smoke; i++) {
			level.addParticle(ParticleTypes.LARGE_SMOKE, at.x + r.nextGaussian() * 0.06, at.y + r.nextGaussian() * 0.06, at.z + r.nextGaussian() * 0.06,
					r.nextGaussian() * 0.01, r.nextGaussian() * 0.01, r.nextGaussian() * 0.01);
		}
		for (int i = 0; i < dust; i++) {
			DustParticleOptions grey = new DustParticleOptions(INK_GREYS[r.nextInt(INK_GREYS.length)], 0.8F + r.nextFloat() * 0.6F);
			level.addParticle(grey, at.x + r.nextGaussian() * 0.12, at.y + r.nextGaussian() * 0.12, at.z + r.nextGaussian() * 0.12, 0, 0, 0);
		}
	}

	// ------------------------------------------------------------------ where the arm is

	/**
	 * Where {@code point} ({@code ArmTips.TIP}, {@code MID}, {@code GRIP} or {@code LURE}) of {@code entityId}'s arm is in
	 * the world this frame, for an effect to start or end at: the point the arm renderer recorded while drawing it (for
	 * the camera's own player in first person, the hand pass's point, which is on the same pixel as the tool on screen;
	 * with First-person Model's body view, the body's), or, when the arm wasn't drawn (out of view, culled, the hand
	 * hidden with F1), an estimate from the eyes: {@code eye + look·1.0 + right·0.35 − up·0.3} on the main-hand side,
	 * a little further along the look for the tip and back toward the hand for the grip. Null only when that entity
	 * isn't in the world.
	 */
	public static @Nullable Vec3 anchor(int entityId, int point) {
		Minecraft mc = Minecraft.getInstance();
		if (isOwnFirstPerson(entityId, mc.options.getCameraType().isFirstPerson())) {
			Vec3 drawn = ArmTips.get(entityId, true, point);
			if (drawn != null) return drawn;
		}
		Vec3 drawn = ArmTips.get(entityId, false, point);
		if (drawn != null) return drawn;
		if (mc.level == null) return null;
		Entity entity = mc.level.getEntity(entityId);
		return entity == null ? null : estimate(entity, point, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	/**
	 * The fallback for {@link #anchor}: roughly where a held tool is, from the eyes. The server guesses the blade the
	 * same way for players without the mod, so the two agree when nothing better is known.
	 */
	static Vec3 estimate(Entity entity, int point, float partialTick) {
		Vec3 eye = entity.getEyePosition(partialTick);
		Vec3 look = entity.getViewVector(partialTick);
		double yaw = Math.toRadians(entity.getViewYRot(partialTick));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 up = right.cross(look);
		if (up.lengthSqr() < 1e-6) up = new Vec3(0, 1, 0);
		else up = up.normalize();
		double hand = entity instanceof Player player && player.getMainArm() == HumanoidArm.LEFT ? -1 : 1;
		if (point == ArmTips.LURE) {
			// the lantern hangs over the other arm
			return eye.add(look.scale(0.6)).add(right.scale(-0.45 * hand)).add(up.scale(0.05));
		}
		double along = switch (point) {
			case ArmTips.TIP -> 1.35;
			case ArmTips.GRIP -> 0.6;
			default -> 1.0;
		};
		return eye.add(look.scale(along)).add(right.scale(0.35 * hand)).add(up.scale(-0.3));
	}

	/** Whether {@code entityId} is the camera's own entity seen in first person (its arm is in the hand pass, not the world). */
	static boolean isOwnFirstPerson(int entityId, boolean firstPersonCamera) {
		if (!firstPersonCamera) return false;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		return camera != null && camera.getId() == entityId;
	}
}
