package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.fx.CrossbowFx;
import dev.morphreborn.client.arm.fx.TridentFx;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ForearmGeometry;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.ArmTex;
import dev.morphreborn.client.arm.render.ArmTint;
import dev.morphreborn.client.arm.render.Rope;
import dev.morphreborn.client.net.ClientNet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link ArmDraw} for every arm drawn: once while the item is extracted (who holds it, what the server says
 * the arm is doing, what just happened to it) and once more when it is submitted ({@link #finish}: how fine to draw
 * it, world down, the tail's physics, the first-person elbow, the light), into a fresh record each time, so nothing a
 * geometry lambda captured is ever changed after the fact (D19).
 * <p>
 * The clock is the arm clock wrapped hourly ({@link #clock}): the same frame-exact time in the world, the hand and the
 * GUI, kept small enough for a float to hold its fractions. Every reaction is turned into an age here, from the exact
 * tick it happened, so a wrap can only ever make one frame of one animation jump.
 * <p>
 * Without anything from the server (a single-player world, or a stack given with {@code /give}), an arm reads as
 * formed and full: it draws exactly as a formed arm does.
 */
public final class ArmDrawFactory {
	/** The arm clock wraps every hour (as the Arsenal wheel's turntable does). */
	public static final long CLOCK_WRAP = 72000L;
	/** Where a tail hangs from in G px: the katana's kashira (section 2.4 K7). The physics only needs the point to move with the hilt. */
	static final Vector3fc ROPE_ROOT = new Vector3f(0F, -5.4F, 0F);
	/** The tail's links in blocks for the world simulation (1.6 px), and gravity: 0.35/16 blocks per tick², world down. */
	private static final float ROPE_LINK_BLOCKS = ArmDraw.ROPE_LINK_PX / 16F;
	private static final Vector3fc GRAVITY = new Vector3f(0F, -0.35F / 16F, 0F);
	/** A rope not stepped for this many frames starts again at rest (it went out of view, the context changed). */
	private static final int ROPE_GAP_FRAMES = 5;
	/** The LOD distances in blocks at MEDIUM detail (HIGH × 1.5, LOW × 0.5). */
	private static final float LOD1 = 8F, LOD2 = 20F, LOD3 = 48F;

	/** Where in the frame a draw is submitted: it decides the LOD, physics, drips and recording. */
	public enum Pass {
		/** The world pass (third person: other players, F5, First-person Model's body). */
		WORLD,
		/** The first-person hand pass. */
		HAND,
		/** A third-person draw outside the world pass: the paper doll, the inventory preview. LOD 0, no physics or drips. */
		PIP,
		/** A GUI item (hotbar, inventory, the wheel, the tree, item frames and the ground). */
		GUI,
		/** The arm preview screen: its own clock, LOD as chosen there, no physics or drips. */
		PREVIEW
	}

	private ArmDrawFactory() {}

	/** The arm clock now, wrapped: {@code (Arms.TICK mod 72000) + partialTick}. */
	public static float clock(float partialTick) {
		return (Arms.TICK % CLOCK_WRAP) + partialTick;
	}

	/** This frame's game-time partial tick (frozen while paused). */
	public static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	// ------------------------------------------------------------------ the description

	/**
	 * One arm draw being described, field by field as {@link ArmDraw} has them, with the defaults of a formed, full,
	 * quiet arm. The world fills it from the owner here; the preview screen fills it from its sliders. Not for keeping:
	 * {@link #build} copies it into the immutable record.
	 */
	public static final class Spec {
		public String form = "", fromForm = "";
		public int tier = 1, lod = ArmDraw.LOD_FULL, ctx = ArmDraw.CTX_TP, seed, state = ArmProtocol.STATE_FORMED;
		public boolean leftHand, slim, chestArmour, mining, lanternLit, glow = true, dissolve, drips;
		public float time, progress = 1F, swing, bowPull, biomassFrac = 1F, biomassAbs = ArmDraw.FULL, surge, specialAge = ArmDraw.NEVER;
		public int specialKind = ArmDraw.NO_SPECIAL;
		public float hitAge = ArmDraw.NEVER, breakAge = ArmDraw.NEVER, lanternAge, recallLeft;
		public float[] pulses = new float[0];
		public int pulseCount;
		public int light = ArmTex.FULL_BRIGHT;
		/** The owner's pulse speed as a multiple of the usual (the owner's own choice, relayed by the server): 1 when unknown or in a preview. */
		public float pulseSpeed = 1F;
		/** The accessories to draw (the set, their numbers and their ages: see ArmDraw), and whether the held tool is away (a thrown trident). */
		public int accessories, accessoryArgs;
		public float @Nullable [] accessoryAges;
		public boolean thrown;

		public Spec reset() {
			form = fromForm = "";
			tier = 1;
			lod = ArmDraw.LOD_FULL;
			ctx = ArmDraw.CTX_TP;
			seed = 0;
			state = ArmProtocol.STATE_FORMED;
			leftHand = slim = chestArmour = mining = lanternLit = dissolve = drips = false;
			glow = true;
			time = swing = bowPull = surge = lanternAge = recallLeft = 0F;
			progress = biomassFrac = 1F;
			biomassAbs = ArmDraw.FULL;
			specialAge = hitAge = breakAge = ArmDraw.NEVER;
			specialKind = ArmDraw.NO_SPECIAL;
			pulses = new float[0];
			pulseCount = 0;
			light = ArmTex.FULL_BRIGHT;
			pulseSpeed = 1F;
			accessories = accessoryArgs = 0;
			accessoryAges = null;
			thrown = false;
			return this;
		}

		/** Takes the accessories {@code st} says are on (or drawing back) at {@code pt}. */
		public Spec accessories(ArmState st, float pt) {
			accessories = st.accessoryMask(Arms.TICK);
			accessoryArgs = st.accessoryArgs();
			if (accessories == 0) {
				accessoryAges = null;
				return this;
			}
			accessoryAges = st.drawAges(accessories, pt); // the state's own array, refilled once a frame: nothing allocated here
			return this;
		}

		/**
		 * The reactions in {@code r} as ages and pulses, {@code now} on the reaction's own clock and {@link #time} the
		 * draw's: every pulse starts at {@code time − age}.
		 */
		public Spec reactions(ArmReactions.@Nullable Reaction r, double now) {
			if (r == null) return this;
			hitAge = age(now - r.hitAt);
			breakAge = age(now - r.breakAt);
			double sinceSurge = now - r.surgeAt;
			surge = sinceSurge >= 0 && sinceSurge < ArmDraw.SURGE_TICKS ? 1F - (float) (sinceSurge / ArmDraw.SURGE_TICKS) : 0F;
			specialKind = ArmDraw.NO_SPECIAL;
			specialAge = ArmDraw.NEVER;
			if (r.specialKind != ArmDraw.NO_SPECIAL) {
				double since = now - r.specialAt;
				if (since >= 0 && since < r.specialLength) {
					specialKind = r.specialKind;
					specialAge = (float) since;
				} else if (since >= r.specialLength && since - r.specialLength < 40) {
					specialKind = r.specialKind;
					// negated ticks since the end (never exactly 0, which would read as just begun)
					specialAge = -Math.max(1e-3F, (float) (since - r.specialLength));
				}
			}
			int n = r.pulseCount;
			float[] p = new float[2 * n];
			for (int i = 0; i < n; i++) {
				p[2 * i] = r.pulseKind[i];
				p[2 * i + 1] = time - (float) (now - r.pulseAt[i]);
			}
			pulses = p;
			pulseCount = n;
			return this;
		}

		private static float age(double a) {
			return a < 0 || a > ArmDraw.NEVER ? ArmDraw.NEVER : (float) a;
		}

		public ArmDraw build() {
			return new ArmDraw(form, tier, lod, ctx, leftHand, slim, chestArmour, time, seed, state, ArmMath.clamp01(progress), fromForm, ArmMath.clamp01(swing),
					ArmMath.clamp01(bowPull), mining, ArmMath.clamp01(biomassFrac), Math.max(0F, biomassAbs), ArmMath.clamp01(surge), specialAge, specialKind, hitAge,
					breakAge, pulses, pulseCount, lanternLit, lanternAge, recallLeft, glow, dissolve, drips, null, 0F, -1F, 0F, null, light, pulseSpeed,
					accessories, accessoryArgs, accessoryAges, thrown);
		}
	}

	/** Render thread scratch for the world's draws (extraction is single-threaded). */
	private static final Spec SCRATCH = new Spec();

	// ------------------------------------------------------------------ from the world

	/** The draw context an item display context belongs to. */
	public static int ctx(ItemDisplayContext ctx) {
		return switch (ctx) {
			case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> ArmDraw.CTX_FP;
			case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> ArmDraw.CTX_TP;
			default -> ArmDraw.CTX_GUI;
		};
	}

	/**
	 * The draw of a held (or shown) arm stack, at extraction. {@code owner} may be null (some screens, item frames,
	 * the ground): then it is a formed, full arm with nobody's reactions.
	 */
	public static ArmDraw forItem(ArmStacks.Recognized arm, ItemStack stack, ItemDisplayContext displayContext, @Nullable ItemOwner owner) {
		float pt = partialTick();
		Spec s = SCRATCH.reset();
		s.form = arm.form();
		s.tier = arm.tier();
		s.ctx = ctx(displayContext);
		s.leftHand = displayContext.leftHand();
		s.time = clock(pt);
		LivingEntity living = owner == null ? null : owner.asLivingEntity();
		if (living != null) {
			boolean hand = s.ctx != ArmDraw.CTX_GUI;
			boolean main = hand ? displayContext.leftHand() == (living.getMainArm() == HumanoidArm.LEFT) : living.getMainHandItem() == stack;
			owner(s, living, arm.form(), main, pt);
		}
		// the real item standing in for the arm while tucked is no biomass reading (a damaged tool would draw starving)
		if (!(living instanceof AbstractClientPlayer p && p == Minecraft.getInstance().player && ownBiomass(s))) stackBiomass(s, ArmStacks.biomassSource(stack, living));
		s.glow = ClientConfig.INSTANCE.armGlow;
		s.dissolve = ArmTex.dissolveOn();
		return s.build();
	}

	/** What the owner adds: the arm's timeline, the swing, the bow, mining, armour, skin and reactions. */
	private static void owner(Spec s, LivingEntity living, String form, boolean main, float pt) {
		s.seed = living.getId();
		s.pulseSpeed = ArmTint.pulseSpeedOfEntity(s.seed);
		if (living instanceof AbstractClientPlayer p) s.slim = p.getSkin().model() == PlayerModelType.SLIM;
		s.chestArmour = !living.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
		ArmState st = Arms.state(living.getUUID());
		if (st != null) {
			if (main && st.armed() && st.heldForm().equals(form)) {
				s.state = st.state;
				s.progress = st.progress(pt);
				s.fromForm = st.fromForm;
			}
			s.lanternLit = st.lanternLit || st.lanternGoingOut(Arms.TICK);
			s.lanternAge = st.lanternDrawAge(pt);
			s.recallLeft = st.recallLeft(pt);
			s.accessories(st, pt);
		}
		InteractionHand hand = main ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		LivingEntity.SwingDescription swing = living.getCurrentSwing();
		if (swing != null && swing.hand() == hand) s.swing = living.getSwingAnimation(pt);
		if (living.isUsingItem() && living.getUsedItemHand() == hand) {
			// vanilla's bow power: (p² + 2p)/3 of the seconds drawn, at most 1
			float p = (living.getTicksUsingItem() + pt) / 20F;
			s.bowPull = Math.min(1F, (p * p + 2F * p) / 3F);
		}
		s.mining = main && mining(living);
		// a thrown trident is away from the hand until it is back (the hand keeps a stub of tendril meanwhile): TridentFx knows
		s.thrown = main && ArmProtocol.TRIDENT.equals(form) && TridentFx.isThrown(living.getId())
				// and the crossbow's groove is empty while its stack has no bolt in it (it loads itself, so only between a shot and its regrow)
				|| main && ArmProtocol.CROSSBOW.equals(form) && !CrossbowFx.loaded(living);
		s.reactions(ArmReactions.get(living.getId()), Arms.TICK + (double) pt);
	}

	/** A sheath with no arm held: just who it is and what just happened to them. */
	private static void bare(Spec s, AbstractClientPlayer player, ArmReactions.@Nullable Reaction r, double now) {
		s.seed = player.getId();
		s.pulseSpeed = ArmTint.pulseSpeedOfEntity(s.seed);
		s.slim = player.getSkin().model() == PlayerModelType.SLIM;
		s.chestArmour = !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
		s.reactions(r, now);
	}

	/**
	 * Whether {@code living} is digging a block right now: this player while the game says so; anyone else while the
	 * world shows a crack they are making (Vein Rip's cracks carry an id of their own and don't count).
	 */
	static boolean mining(LivingEntity living) {
		Minecraft mc = Minecraft.getInstance();
		if (living == mc.player) return mc.gameMode != null && mc.gameMode.isDestroying();
		if (!(living.level() instanceof ClientLevel level)) return false;
		int id = living.getId();
		for (Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>> e : level.destructionProgress().long2ObjectEntrySet()) {
			for (BlockDestructionProgress p : e.getValue()) {
				if (p.getId() == id) return true;
			}
		}
		return false;
	}

	/**
	 * This player's own biomass, exact, from the server (Survival). Free arms (Classic, creative) and a client that
	 * knows nothing (no server mod) read full. Returns false when it didn't apply, so the stack is read instead.
	 */
	private static boolean ownBiomass(Spec s) {
		if (!ClientNet.isSurvival() || !ClientNet.biomassUnlocked()) return false;
		if (Arms.arsenal().free()) {
			s.biomassFrac = 1F;
			s.biomassAbs = ArmDraw.FULL;
			return true;
		}
		int capacity = ClientNet.biomassCapacity();
		if (capacity <= 0) return false;
		float abs = ClientNet.biomassMilli() / 1000F;
		s.biomassAbs = abs;
		s.biomassFrac = abs / capacity;
		return true;
	}

	/**
	 * Someone else's biomass, from the arm stack's durability: the server shows biomass as the bar (max damage = capacity,
	 * damage = what is missing, whole biomass only). An item with no bar is a free arm: full.
	 */
	private static void stackBiomass(Spec s, ItemStack stack) {
		// no bar is no biomass reading: Classic arms are damageable (max 2, damage 0) only so the bar can exist, and a
		// full arm shows none either; without this a Classic arm would read 2 biomass and draw starving
		if (stack == null || !stack.isDamageableItem() || !stack.isBarVisible()) {
			s.biomassFrac = 1F;
			s.biomassAbs = ArmDraw.FULL;
			return;
		}
		int max = Math.max(1, stack.getMaxDamage());
		int left = Math.max(0, max - stack.getDamageValue());
		s.biomassAbs = left;
		s.biomassFrac = left / (float) max;
	}

	/**
	 * The sheath's draw for {@code player} (third person, arm space), or null when there is nothing on the arm: the arm
	 * held (the stack, or what the server says while the stack is on its way), the sheath receding after a forced
	 * retract, or with no arm the recall bracelet and the lantern.
	 */
	public static @Nullable ArmDraw forSheath(AbstractClientPlayer player, float pt) {
		HumanoidArm mainArm = player.getMainArm();
		ItemStack held = player.getMainHandItem();
		ItemDisplayContext ctx = mainArm == HumanoidArm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
		ArmStacks.Recognized arm = ArmStacks.recognize(held, player, ctx);
		ArmState st = Arms.state(player.getUUID());
		ArmReactions.Reaction r = ArmReactions.get(player.getId());
		double now = Arms.TICK + (double) pt;
		Spec s = SCRATCH.reset();
		s.time = clock(pt);
		s.ctx = ArmDraw.CTX_TP;
		s.leftHand = mainArm == HumanoidArm.LEFT;
		if (arm != null || st != null && st.armed()) {
			// the arm is out: its own timeline (formed when the server hasn't said, as with a stack from /give), or what
			// the server says while the stack is still on its way
			s.form = arm != null ? arm.form() : st.heldForm();
			s.tier = arm != null ? arm.tier() : Math.max(1, st.level);
			owner(s, player, s.form, true, pt);
		} else if (r != null && now - r.poppedAt < ArmReactions.RECEDE_TICKS) {
			// a forced retract: the item went at once, and the sheath slides down the arm over 4 ticks (RETRACT's own
			// slide, its ticks 6 to 11, run quicker)
			s.form = r.poppedForm;
			s.tier = r.poppedTier;
			bare(s, player, r, now);
			// a lit lantern (and any accessory) keeps drawing through the recede, on the sheath that is sliding off: this branch used
			// to leave them out, so the lantern vanished for these ticks and then snapped in with its holder fully grown
			// (2026-11-16)
			if (st != null) {
				s.lanternLit = st.lanternLit || st.lanternGoingOut(Arms.TICK);
				s.lanternAge = st.lanternDrawAge(pt);
				s.recallLeft = st.recallLeft(pt);
				s.accessories(st, pt);
			}
			s.state = ArmProtocol.STATE_RETRACTING;
			s.progress = (14F + 16F * ArmMath.clamp01((float) (now - r.poppedAt) / ArmReactions.RECEDE_TICKS)) / ArmProtocol.RETRACT_TICKS;
		} else if (st != null && (st.lanternLit || st.lanternGoingOut(Arms.TICK) || st.recallLeft(pt) > 0F || st.anyAccessory(Arms.TICK))) {
			// no arm: the recall bracelet, or only the accessories (on, or drawing back as they go out)
			s.form = "";
			bare(s, player, r, now);
			s.state = ArmProtocol.STATE_NONE;
			s.progress = 1F;
			s.lanternLit = st.lanternLit || st.lanternGoingOut(Arms.TICK);
			s.lanternAge = st.lanternDrawAge(pt);
			s.recallLeft = st.recallLeft(pt);
			s.accessories(st, pt);
		} else {
			return null;
		}
		if (!(player == Minecraft.getInstance().player && ownBiomass(s))) stackBiomass(s, arm != null ? ArmStacks.biomassSource(held, player) : null);
		s.glow = ClientConfig.INSTANCE.armGlow;
		s.dissolve = ArmTex.dissolveOn();
		return s.build();
	}

	// ------------------------------------------------------------------ at submit

	private static final Matrix3f NORMAL = new Matrix3f();
	private static final Matrix4f INVERSE = new Matrix4f();
	private static final Vector3f V = new Vector3f(), W = new Vector3f();
	private static final float[] NODES = new float[3 * ArmDraw.ROPE_NODES];
	private static final Vector3fc DOWN = new Vector3f(0F, -1F, 0F);

	/**
	 * {@code d} finished for one submit at {@code pose} (G space, the 1/16 included): its LOD ({@code forcedLod} ≥ 0
	 * overrides), drips, the tail, world down and, in first person, the elbow point. {@code viewToBase} maps view space
	 * (blocks) into the space {@code pose} maps into: the inverse view rotation in the hand pass, the preview's camera
	 * there; null elsewhere.
	 * <p>
	 * Also makes {@code d}'s owner (its seed, the owner's entity id) the one whose colour the layers submitted next are
	 * drawn in ({@link ArmTint}), so every arm draw is in its owner's colour without the geometry or the renderers knowing.
	 */
	public static ArmDraw finish(ArmDraw d, Pass pass, PoseStack.Pose pose, int light, @Nullable Matrix4fc viewToBase, int forcedLod) {
		ArmTint.owner(pass == Pass.PREVIEW ? Integer.MIN_VALUE : d.seed());
		int lod = lod(d, pass, pose, forcedLod);
		boolean live = pass == Pass.WORLD || pass == Pass.HAND;
		boolean drips = live && lod == ArmDraw.LOD_FULL && ClientConfig.INSTANCE.armDrips;
		// the tail droops toward the ground in the world and toward the bottom of the picture elsewhere (an icon, the doll,
		// a preview: their space has y pointing down the screen, so the same sum comes out upside down there)
		Vector3f down = worldDown(pose, new Vector3f());
		if (!live) down.negate();
		float[] rope = null;
		if (lod == ArmDraw.LOD_FULL) rope = live ? simulate(d, pass, pose) : droop(down);
		float[] anchor = d.firstPerson() && viewToBase != null ? fpAnchor(d, pose, viewToBase) : null;
		return d.finish(lod, drips, rope, down.x, down.y, down.z, anchor, light);
	}

	/**
	 * The sheath's draw finished for one submit in arm space A: LOD, drips and world down, no tail and no elbow. Sets the
	 * colour's owner as {@link #finish} does.
	 */
	public static ArmDraw finishSheath(ArmDraw d, Pass pass, PoseStack.Pose pose, int light, int forcedLod) {
		ArmTint.owner(pass == Pass.PREVIEW ? Integer.MIN_VALUE : d.seed());
		int lod = lod(d, pass, pose, forcedLod);
		boolean live = pass == Pass.WORLD || pass == Pass.HAND;
		boolean drips = live && lod == ArmDraw.LOD_FULL && ClientConfig.INSTANCE.armDrips;
		Vector3f down = live ? worldDown(pose, new Vector3f()) : new Vector3f(DOWN);
		return d.finish(lod, drips, null, down.x, down.y, down.z, null, light);
	}

	/**
	 * Section 2.10: your own first-person arm is drawn in full, GUI icons as ICON, the paper doll and the inventory
	 * preview in full; everything else by its distance from the camera (8, 20 and 48 blocks at MEDIUM detail, × 1.5 at
	 * HIGH, × 0.5 at LOW, where your own arm is never finer than LOD 1).
	 */
	static int lod(ArmDraw d, Pass pass, PoseStack.Pose pose, int forced) {
		if (forced >= 0) return Math.min(ArmDraw.LOD_FAR, forced);
		if (pass == Pass.GUI) return ArmDraw.LOD_ICON;
		String detail = ClientConfig.INSTANCE.armDetail;
		boolean low = "LOW".equals(detail);
		int lod = 0;
		if (pass == Pass.WORLD) {
			Matrix4f m = pose.pose();
			float dist = (float) Math.sqrt(m.m30() * m.m30() + m.m31() * m.m31() + m.m32() * m.m32());
			float mul = "HIGH".equals(detail) ? 1.5F : low ? 0.5F : 1F;
			lod = dist < LOD1 * mul ? 0 : dist < LOD2 * mul ? 1 : dist < LOD3 * mul ? 2 : 3;
		}
		if (low && own(d.seed())) lod = Math.max(lod, 1);
		return lod;
	}

	private static boolean own(int entityId) {
		var player = Minecraft.getInstance().player;
		return player != null && player.getId() == entityId;
	}

	/**
	 * World down in the pose's own space: the world pass and the hand pass are both world-aligned (the hand pass starts
	 * from the inverse view rotation), so it is the inverse of the pose's normal matrix applied to (0, −1, 0).
	 */
	static Vector3f worldDown(PoseStack.Pose pose, Vector3f out) {
		NORMAL.set(pose.normal());
		if (Math.abs(NORMAL.determinant()) < 1e-12F) return out.set(DOWN);
		NORMAL.invert().transform(out.set(0F, -1F, 0F));
		float l = out.length();
		return l > 1e-6F && Float.isFinite(l) ? out.div(l) : out.set(DOWN);
	}

	/** Where a tail lies when nothing simulates it (GUI views, previews, picture-in-picture): a still droop. */
	private static float[] droop(Vector3fc down) {
		float[] out = new float[3 * ArmDraw.ROPE_NODES];
		Rope.droop(new Vector3f(), DOWN, down, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, out);
		return out;
	}

	/** One player's tail in one view (the world or the hand pass), carried from frame to frame. */
	private static final class RopeState {
		final Rope rope = new Rope(ArmDraw.ROPE_NODES, ROPE_LINK_BLOCKS);
		long frame = Long.MIN_VALUE / 2;
		float lastTime;
		String form = "";
		/** The simulation's origin in the world (third person), so the floats stay small however far out a player is. */
		double ox, oy, oz;
		double flickSeen = ArmReactions.LONG_AGO;
	}

	private static final Map<Long, RopeState> ROPES = new HashMap<>();

	/**
	 * The tail's nodes for this submit, in G px relative to its root (section 2.2 Rope): stepped once a frame per player
	 * and view, in the world for third person (relative to an origin of its own, so walking past a player doesn't sway
	 * their tail, but their own swing and stride do), and in the hand pass's world-aligned space in first person, so a
	 * camera turn swings it. A heal's arrival flicks it; a starving arm's hangs limp.
	 */
	private static float[] simulate(ArmDraw d, Pass pass, PoseStack.Pose pose) {
		long key = ((long) d.seed() << 1) | (pass == Pass.HAND ? 1L : 0L);
		RopeState r = ROPES.computeIfAbsent(key, k -> new RopeState());
		long frame = ArmRender.frame();
		Matrix4f m = pose.pose();
		m.transformPosition(V.set(ROPE_ROOT));
		double cx = 0, cy = 0, cz = 0;
		if (pass == Pass.WORLD) {
			Vec3 cam = ArmTipsRecorder.cameraPos();
			cx = cam.x;
			cy = cam.y;
			cz = cam.z;
		}
		double ax = V.x + cx, ay = V.y + cy, az = V.z + cz;
		boolean far = Math.abs(ax - r.ox) > 32 || Math.abs(ay - r.oy) > 32 || Math.abs(az - r.oz) > 32;
		if (frame - r.frame > ROPE_GAP_FRAMES || !r.form.equals(d.form()) || far) {
			r.ox = ax;
			r.oy = ay;
			r.oz = az;
			r.form = d.form();
			r.rope.reset(W.set(0F, 0F, 0F));
			r.lastTime = d.time();
			r.frame = frame - 1;
			ArmReactions.Reaction react = ArmReactions.get(d.seed());
			if (react != null) r.flickSeen = react.flickAt;
		}
		W.set((float) (ax - r.ox), (float) (ay - r.oy), (float) (az - r.oz));
		if (r.frame != frame) {
			float dt = d.time() - r.lastTime;
			if (!(dt >= 0F) || dt > 20F) dt = 0F; // the clock wrapped, or a long hitch: hold rather than fling
			r.lastTime = d.time();
			r.frame = frame;
			ArmReactions.Reaction react = ArmReactions.get(d.seed());
			double now = Arms.TICK + (double) partialTick();
			if (react != null && react.flickAt > r.flickSeen && react.flickAt <= now) {
				// the tail flicks as a heal arrives: a shove toward the edge side and up
				r.flickSeen = react.flickAt;
				Vector3f side = m.transformDirection(new Vector3f(1F, 0F, 0F));
				float l = side.length();
				if (l > 1e-6F) side.div(l);
				r.rope.impulse(side.x * 0.06F, 0.05F + side.y * 0.06F, side.z * 0.06F);
			}
			float damping = d.lowStage() >= ArmDraw.LOW_STARVING ? 0.6F : 0.86F;
			r.rope.step(W, GRAVITY, dt, damping);
		}
		r.rope.copyNodes(NODES);
		// back into G px, relative to the root
		INVERSE.set(m);
		if (Math.abs(INVERSE.determinant()) < 1e-18F) return droop(DOWN);
		INVERSE.invert();
		float[] out = new float[3 * ArmDraw.ROPE_NODES];
		for (int i = 0; i < ArmDraw.ROPE_NODES; i++) {
			double px = NODES[3 * i] + r.ox - cx, py = NODES[3 * i + 1] + r.oy - cy, pz = NODES[3 * i + 2] + r.oz - cz;
			INVERSE.transformPosition(V.set((float) px, (float) py, (float) pz));
			out[3 * i] = V.x - ROPE_ROOT.x();
			out[3 * i + 1] = V.y - ROPE_ROOT.y();
			out[3 * i + 2] = V.z - ROPE_ROOT.z();
		}
		return out;
	}

	/**
	 * Section 2.3 F1: the forearm's far end. The camera-fixed elbow (view space, blocks) brought into G px through the
	 * pose, blended 55% toward the rig point so the far end partly follows the swing, then kept 13..22 px from the
	 * wrist. Null if the pose can't be inverted.
	 */
	static float @Nullable [] fpAnchor(ArmDraw d, PoseStack.Pose pose, Matrix4fc viewToBase) {
		float sh = d.leftHand() ? -1F : 1F;
		Vector3f a = viewToBase.transformPosition(new Vector3f(ForearmGeometry.VIEW_X * sh, ForearmGeometry.VIEW_Y, ForearmGeometry.VIEW_Z));
		Matrix4f inv = new Matrix4f(pose.pose());
		if (Math.abs(inv.determinant()) < 1e-18F) return null;
		inv.invert().transformPosition(a);
		if (!Float.isFinite(a.x) || !Float.isFinite(a.y) || !Float.isFinite(a.z)) return null;
		// the blend toward the rig point and the 13..22 px clamp are the forearm's own (one copy, mirrored per hand)
		return ForearmGeometry.anchor(a.x, a.y, a.z, d.leftHand(), new float[3]);
	}

	/** Drops the tails of players no longer drawn (once a tick). */
	static void prune() {
		long frame = ArmRender.frame();
		for (Iterator<RopeState> it = ROPES.values().iterator(); it.hasNext(); ) {
			if (frame - it.next().frame > 600) it.remove();
		}
	}

	static void clear() {
		ROPES.clear();
	}
}
