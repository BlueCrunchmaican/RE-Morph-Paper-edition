package dev.morphreborn.client.arm.geom;

import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.ArmMath;
import dev.morphreborn.client.arm.render.Pulse;
import dev.morphreborn.client.arm.render.Static;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Everything one arm draw needs, worked out once and then frozen. The item path builds it while the held item is
 * extracted (ArmDrawFactory: the owner, the arm's state from the server, the in-hand reactions), and the renderer
 * finishes it when it submits (level of detail, world-down, the tail's physics, the first-person elbow point, the
 * light) into a new copy. Geometry only ever reads it: every submitted geometry lambda rebuilds its own layer from this
 * record, so nothing a lambda captures is ever changed after the fact (the renderer runs the lambdas later, at dispatch).
 * <p>
 * The fields:
 * <ul>
 *   <li>{@code form}, {@code tier} (1 iron, 2 diamond, 3 netherite: the levels Raw, Honed, Awakened), {@code lod}
 *       (0 full .. 3 a few boxes; GUI icons are {@link #LOD_ICON}), {@code ctx} ({@link #CTX_FP}, {@link #CTX_TP},
 *       {@link #CTX_GUI}), which hand ({@code leftHand}), the owner's {@code slim} arms and {@code chestArmour}.</li>
 *   <li>{@code time}: the draw's clock in ticks with the partial tick (the arm clock, wrapped hourly so the float keeps
 *       its fractions; the preview screen's own clock there). Every other time here is an age, or a start on this
 *       clock.</li>
 *   <li>{@code seed}: per player (the entity id), so two arms never writhe or fizz in step: {@link #phase()}.</li>
 *   <li>{@code state} ({@code ArmProtocol.STATE_*}), {@code progress} 0..1 through its timeline (1 when FORMED or
 *       NONE), and while reshaping the form it comes from ({@code fromForm}, "" otherwise).</li>
 *   <li>{@code swing}: the attack animation 0..1 (0 at rest); {@code bowPull}: a drawn bow's power 0..1, as vanilla's
 *       bow; {@code mining}: the owner is digging a block right now.</li>
 *   <li>{@code biomassFrac} (b, 0..1 of capacity) and {@code biomassAbs} (B, whole biomass): {@link #lowStage()}.
 *       Free arms (Classic, creative) read full: b 1, B {@link #FULL}.</li>
 *   <li>{@code surge}: a gain's surge, 1 when it lands and 0 after 30 ticks.</li>
 *   <li>{@code specialAge} and {@code specialKind}: the special in hand, by the effect kind that began it
 *       ({@code ArmProtocol.FX_SWIPE} for Draining Cut, {@code FX_VEIN} for Vein Rip; {@link #NO_SPECIAL} for none).
 *       While it runs, {@code specialAge} is the ticks since it began (0 or more); once it has ended it is the negated
 *       ticks since the end (Draining Cut's hamon recedes over the 10 ticks after); {@link #NEVER} when there has been
 *       none worth showing.</li>
 *   <li>{@code hitAge}, {@code breakAge}: ticks since the last melee hit and the last block broken ({@link #NEVER}:
 *       none recently).</li>
 *   <li>{@code pulses}: {@code pulseCount} pulse events as (Pulse.Kind ordinal, start on {@code time}) pairs, ready for
 *       {@code Pulse.amp} ({@link #pulse} adds the idle pulses and the surge).</li>
 *   <li>{@code lanternLit}, {@code lanternAge}, {@code recallLeft} (ticks of the recall left, 0 none): the lantern and
 *       the recall bracelet.</li>
 *   <li>{@code glow} (the player's glow setting), {@code dissolve} (fronts erode: use vertex alpha ramps; off: cut
 *       hard), {@code drips} (drips may be drawn here: off in GUI views, picture-in-picture, at a distance, during a
 *       surge's pause is the geometry's own call).</li>
 *   <li>{@code ropeNodes}: the hanging tail ({@link #ROPE_NODES} nodes of {@link #ROPE_LINK_PX}), x, y, z per node in
 *       G px <b>relative to the tail's root</b> (node 0 is 0, 0, 0): the geometry adds its own root, so the physics
 *       never has to know where each form's tail hangs from. Simulated in the world at LOD 0; a still droop where
 *       nothing simulates (GUI, previews, picture-in-picture); null at LOD 1 and beyond, where no tail is drawn.</li>
 *   <li>{@code downX/Y/Z}: world down as a unit vector in the geometry's own space (G for tools, A for the sheath), for
 *       drips and drooping; (0, -1, 0) in GUI views and previews.</li>
 *   <li>{@code fpAnchor}: first person only, the forearm's far end A (section 2.3 F1) in G px, already final (the
 *       camera-fixed elbow blended 55% toward the rig point and kept 13..22 px from the wrist); null elsewhere and when
 *       it couldn't be worked out (then the forearm uses the rig point).</li>
 *   <li>{@code light}: the packed light the geometry is drawn with (glow layers use full bright regardless).</li>
 *   <li>{@code accessories}, {@code accessoryArgs}, {@code accessoryAges} (the new forms, 2026-11-16): the accessories to draw, as a
 *       set (bit i is {@code ArmProtocol.ACCESSORIES.get(i)}: the ones on and the ones drawing back, the lantern's bit too),
 *       each one's own number four bits apiece (the shield raised, the spyglass zooming: 0 or 1) and each one's age as the
 *       lure's is (ticks since it came on, negative while it draws back, indexed like the bits; null when none is drawn).
 *       {@code thrown}: the held tool is away (the trident, until it is back). Read them with {@link #accessoryOn},
 *       {@link #accessoryArg}, {@link #accessoryAge}; a crossbow's pull is {@code bowPull}, as the bow's.</li>
 *   <li>{@code pulseSpeed}: how fast this owner's pulses run, as a multiple of the usual ({@code Pulse}'s speed; the
 *       owner's own choice, 2026-11-16, as the server relays it), 1 when unknown. Read by {@link #pulse}, so every
 *       swelling, the red faces' pulse light and the lantern's stalk and seam run at it together.</li>
 * </ul>
 * The record's arrays are never written after it is built; don't write them either.
 */
public record ArmDraw(String form, int tier, int lod, int ctx /* 0 FP,1 TP,2 GUI */, boolean leftHand, boolean slim, boolean chestArmour,
		float time, int seed, int state, float progress, String fromForm, float swing, float bowPull, boolean mining, float biomassFrac, float biomassAbs,
		float surge, float specialAge, int specialKind, float hitAge, float breakAge, float[] pulses, int pulseCount, boolean lanternLit, float lanternAge,
		float recallLeft, boolean glow, boolean dissolve, boolean drips, float @Nullable [] ropeNodes, float downX, float downY, float downZ,
		float @Nullable [] fpAnchor, int light, float pulseSpeed, int accessories, int accessoryArgs, float @Nullable [] accessoryAges, boolean thrown) {
	/** An ArmDraw with no accessories and nothing thrown: every draw built before the new forms existed. */
	public ArmDraw(String form, int tier, int lod, int ctx, boolean leftHand, boolean slim, boolean chestArmour, float time, int seed, int state, float progress,
			String fromForm, float swing, float bowPull, boolean mining, float biomassFrac, float biomassAbs, float surge, float specialAge, int specialKind,
			float hitAge, float breakAge, float[] pulses, int pulseCount, boolean lanternLit, float lanternAge, float recallLeft, boolean glow, boolean dissolve,
			boolean drips, float @Nullable [] ropeNodes, float downX, float downY, float downZ, float @Nullable [] fpAnchor, int light, float pulseSpeed) {
		this(form, tier, lod, ctx, leftHand, slim, chestArmour, time, seed, state, progress, fromForm, swing, bowPull, mining, biomassFrac, biomassAbs, surge,
				specialAge, specialKind, hitAge, breakAge, pulses, pulseCount, lanternLit, lanternAge, recallLeft, glow, dissolve, drips, ropeNodes, downX, downY,
				downZ, fpAnchor, light, pulseSpeed, 0, 0, null, false);
	}

	/** An ArmDraw whose owner pulses at the usual speed: every draw built before the pulse speed existed. */
	public ArmDraw(String form, int tier, int lod, int ctx, boolean leftHand, boolean slim, boolean chestArmour, float time, int seed, int state, float progress,
			String fromForm, float swing, float bowPull, boolean mining, float biomassFrac, float biomassAbs, float surge, float specialAge, int specialKind,
			float hitAge, float breakAge, float[] pulses, int pulseCount, boolean lanternLit, float lanternAge, float recallLeft, boolean glow, boolean dissolve,
			boolean drips, float @Nullable [] ropeNodes, float downX, float downY, float downZ, float @Nullable [] fpAnchor, int light) {
		this(form, tier, lod, ctx, leftHand, slim, chestArmour, time, seed, state, progress, fromForm, swing, bowPull, mining, biomassFrac, biomassAbs, surge,
				specialAge, specialKind, hitAge, breakAge, pulses, pulseCount, lanternLit, lanternAge, recallLeft, glow, dissolve, drips, ropeNodes, downX, downY,
				downZ, fpAnchor, light, 1F);
	}

	/** A speed outside the range a player may ask for (a bad relay, a hand-built draw) is the nearest allowed one; NaN is the usual. */
	public ArmDraw {
		pulseSpeed = Pulse.clampSpeed(pulseSpeed);
	}

	/** Whether accessory {@code id} is to be drawn (on, or drawing back). The lantern's is also {@link #lanternLit}. */
	public boolean accessoryOn(String id) {
		int i = ArmProtocol.accessoryIndex(id);
		return i >= 0 && (accessories & (1 << i)) != 0;
	}

	/** Whether any accessory is to be drawn. */
	public boolean anyAccessory() {
		return accessories != 0;
	}

	/** Accessory {@code id}'s own number (0..15: the shield raised, the spyglass zooming), 0 when it is off or has none. */
	public int accessoryArg(String id) {
		int i = ArmProtocol.accessoryIndex(id);
		return i >= 0 && i < 8 ? (accessoryArgs >>> (4 * i)) & 15 : 0;
	}

	/** Accessory {@code id}'s age as the lure's: ticks since it came on, {@code -1 - ticks} while it draws back, 0 when it is off or unknown. */
	public float accessoryAge(String id) {
		int i = ArmProtocol.accessoryIndex(id);
		return accessoryAges != null && i >= 0 && i < accessoryAges.length ? accessoryAges[i] : 0F;
	}

	/** {@link #ctx}: first person (vanilla hands, the hand pass). */
	public static final int CTX_FP = 0;
	/** {@link #ctx}: third person (other players, F5, First-person Model's body, the paper doll and inventory preview). */
	public static final int CTX_TP = 1;
	/** {@link #ctx}: a GUI icon (hotbar, inventory, the Arsenal wheel, the upgrade tree). */
	public static final int CTX_GUI = 2;

	/** {@link #lod}: full detail (own arm, anything within 8 blocks). */
	public static final int LOD_FULL = 0;
	/** {@link #lod} of GUI icons: drawn as LOD 1, without forearm, physics or drips. */
	public static final int LOD_ICON = 1;
	/** {@link #lod}: the coarsest, a few boxes (beyond 48 blocks). */
	public static final int LOD_FAR = 3;

	/** An age for something that hasn't happened (or not for so long it shows nothing): far past every reaction's end. */
	public static final float NEVER = 1.0E6F;
	/** {@link #specialKind} when no special is in hand. */
	public static final int NO_SPECIAL = -1;
	/** {@link #biomassAbs} of an arm that costs nothing (Classic, creative) or whose biomass isn't known: full. */
	public static final float FULL = 1.0E6F;

	/** The hanging tail: nodes and the length of each link in px (the katana's sageo, section 2.4 K7). */
	public static final int ROPE_NODES = 6;
	public static final float ROPE_LINK_PX = 1.6F;

	/** {@link #lowStage()}: enough biomass. */
	public static final int LOW_NONE = 0;
	/** {@link #lowStage()}: thinning (b under 0.25 or B under 30). */
	public static final int LOW_THINNING = 1;
	/** {@link #lowStage()}: starving (b under 0.10 or B under 10). */
	public static final int LOW_STARVING = 2;
	/** {@link #lowStage()}: failing (B under 2 or b under 0.02). */
	public static final int LOW_FAILING = 3;

	/** Ticks a gain's surge lasts (cadence 1, idle pulses doubled, fuzz longer, drips paused). */
	public static final float SURGE_TICKS = 30F;

	public boolean firstPerson() {
		return ctx == CTX_FP;
	}

	public boolean thirdPerson() {
		return ctx == CTX_TP;
	}

	public boolean gui() {
		return ctx == CTX_GUI;
	}

	/** The per-player phase {@code ph = seed · 0.37} radians (section 2.0). */
	public float phase() {
		return ArmMath.phase(seed);
	}

	/** World down in the geometry's space into {@code out} (unit length; (0, -1, 0) if it was never set). */
	public Vector3f down(Vector3f out) {
		out.set(downX, downY, downZ);
		float l = out.length();
		return l > 1e-6F && Float.isFinite(l) ? out.div(l) : out.set(0F, -1F, 0F);
	}

	/**
	 * The low-biomass stage (section 2.4 "Low biomass"): {@link #LOW_FAILING}, {@link #LOW_STARVING},
	 * {@link #LOW_THINNING} or {@link #LOW_NONE}, the worst that applies.
	 */
	public int lowStage() {
		return lowStage(biomassFrac, biomassAbs);
	}

	/** {@link #lowStage()} for a fraction {@code b} and an absolute {@code abs}. */
	public static int lowStage(float b, float abs) {
		if (abs < 2F || b < 0.02F) return LOW_FAILING;
		if (b < 0.10F || abs < 10F) return LOW_STARVING;
		if (b < 0.25F || abs < 30F) return LOW_THINNING;
		return LOW_NONE;
	}

	/** Whether a special is running in hand now ({@link #specialAge} counts up from its start). */
	public boolean specialRunning() {
		return specialKind != NO_SPECIAL && specialAge >= 0F && specialAge < NEVER;
	}

	/** Ticks since the special in hand ended (its recovery), or {@link #NEVER} while one runs or there was none. */
	public float specialEndedAgo() {
		return specialKind != NO_SPECIAL && specialAge < 0F ? -specialAge : NEVER;
	}

	/**
	 * The static's cadence now ({@code Static.cadence}'s value, flags included): moving states, a running special, a hit
	 * within 6 ticks and a gain's surge all re-pattern every tick; otherwise by the low-biomass stage.
	 */
	public int cadence() {
		boolean hit = hitAge < 6F || breakAge < 6F || surge > 0F || mining;
		return Static.cadence(state, specialRunning(), hit, lowStage());
	}

	/**
	 * The crawl direction for {@code Static.set}: +1 shoulder to tip, −1 while the arm is fed (a FEED pulse on the
	 * arm, or a surge).
	 */
	public float crawlDir() {
		if (surge > 0F) return -1F;
		float feeding = 0F;
		for (int i = 0; i < pulseCount && 2 * i + 1 < pulses.length; i++) {
			if ((int) pulses[2 * i] == Pulse.Kind.FEED.ordinal()) {
				float age = time - pulses[2 * i + 1];
				if (age >= 0F && age < 12F) feeding = 1F;
			}
		}
		return feeding > 0F ? -1F : 1F;
	}

	/**
	 * The swelling at feed coordinate {@code phi} (px from the shoulder) on a form whose feed coordinate ends at
	 * {@code feedLength}: the idle pulses (doubled during a surge) plus every event pulse, all run at {@link #pulseSpeed}.
	 */
	public float pulse(float phi, float feedLength) {
		float idle = Pulse.idle(phi, time, phase(), feedLength, pulseSpeed);
		return idle * (1F + surge) + Pulse.events(phi, time, pulses, pulseCount, feedLength, pulseSpeed);
	}

	/** A timed state (forming, quick-forming, reshaping, retracting, melting) rather than resting. */
	public boolean timed() {
		return state != ArmProtocol.STATE_FORMED && state != ArmProtocol.STATE_NONE;
	}

	/**
	 * Whether the first-person hand pass draws the arm (forearm and claw) with the tool: no (2026-11-15:
	 * "vanilla first person: show just the tool"; with First-person Model the tendril arm is on its body instead). The
	 * geometry stays for a later change of mind.
	 */
	public static final boolean FIRST_PERSON_ARM = false;

	/** Real ticks into the current timeline: {@code progress × its length} (0 when resting). */
	public float realTicks() {
		return progress * ArmProtocol.duration(state);
	}

	/**
	 * The tool's timelines as every form's geometry is drawn for them: FORM 14 ticks, quick-form 8, reshape 16, retract 12,
	 * melt 20. The protocol's own lengths are about twice these (2026-11-15: the tools and the arm changed too
	 * fast to see anything), and a FORM or quick-form gives the arm itself its own lead first ({@link #armLead}), so
	 * {@link #stateTicks} maps the real timeline onto this one.
	 */
	public static int toolTicks(int state) {
		return switch (state) {
			case ArmProtocol.STATE_FORMING -> 14;
			case ArmProtocol.STATE_QUICKFORM -> 8;
			case ArmProtocol.STATE_RESHAPING -> 16;
			case ArmProtocol.STATE_RETRACTING -> 12;
			case ArmProtocol.STATE_MELTING -> 20;
			default -> 0;
		};
	}

	/**
	 * Real ticks the arm takes to turn before the tool starts: FORM 16, quick-form 8, none for the rest. The owner,
	 * 2026-11-16: "Shoulder > down to the hand > from the hand, weapon or tool forms > complete", so the arm finishes
	 * reaching the hand (its tendrils and the claw, by about tick 15) before the tool grows out of it.
	 */
	public static int armLead(int state) {
		return state == ArmProtocol.STATE_FORMING ? 16 : state == ArmProtocol.STATE_QUICKFORM ? 8 : 0;
	}

	/**
	 * Ticks into the current timeline on the tool's beat ({@link #toolTicks}): 0 through the arm's lead, then the rest
	 * of the real timeline stretched over the tool's (0 when resting).
	 */
	public float stateTicks() {
		int length = ArmProtocol.duration(state), lead = armLead(state);
		if (length <= lead) return 0F;
		return Math.max(0F, realTicks() - lead) * toolTicks(state) / (length - lead);
	}

	/** The progress at which {@code state}'s tool timeline reaches {@code toolTick} (the dumps and tests pose by it). */
	public static float progressAt(int state, float toolTick) {
		int length = ArmProtocol.duration(state), lead = armLead(state), tool = toolTicks(state);
		if (length <= 0 || tool <= 0) return 1F;
		return ArmMath.clamp01((lead + toolTick * (length - lead) / tool) / length);
	}

	// ------------------------------------------------------------------ the arm's own turning (real ticks)
	//
	// Decided 2026-11-15: the arm turns the way a body morphs, in the mod's morph static: the static creeps over the
	// skin arm, the skin arm goes under it, and the tendrils grow out of the static and become the new arm while the
	// static sinks into them. Everything grows out of what was there; nothing pops. Decided 2026-11-16: it goes from
	// the shoulder down to the hand, and then the tool forms out of the hand (it went from the hand up before). Going back
	// plays it backwards: the tool goes back into the hand, the tendrils draw back up into the shoulder through a static
	// arm, and the static slides off up to the shoulder.

	/** How much of the skin arm the morph static covers, 0..1, from the shoulder down (1: the whole arm, the skin arm hidden). */
	public float armStatic() {
		float t = realTicks();
		return switch (state) {
			case ArmProtocol.STATE_FORMING -> ArmMath.sm(t / 9F);
			// twice as fast as it was (t/4): I saw the wrist bare for about three ticks before the static got down to it
			// (2026-11-16); the shoulder-first order is kept, only the sweep is quicker, and the skin arm is hidden from tick 2
			case ArmProtocol.STATE_QUICKFORM -> ArmMath.sm(t / 2F);
			case ArmProtocol.STATE_RETRACTING -> 1F - ArmMath.sm((t - 23F) / 7F);
			case ArmProtocol.STATE_MELTING -> 1F - ArmMath.sm((t - 32F) / 8F);
			case ArmProtocol.STATE_NONE -> 0F;
			default -> 1F;
		};
	}

	/**
	 * How far the tendril arm has grown out of the static, 0..1, from the shoulder down to the hand (its tendrils come out
	 * one after another, the claw closes on the grip last). FORM ticks 4..15, so the arm is whole before the tool starts.
	 */
	public float armGrowth() {
		float t = realTicks();
		return switch (state) {
			case ArmProtocol.STATE_FORMING -> ArmMath.sm((t - 4F) / 11F);
			case ArmProtocol.STATE_QUICKFORM -> ArmMath.sm((t - 2F) / 5F);
			case ArmProtocol.STATE_RETRACTING -> 1F - ArmMath.sm((t - 14F) / 10F);
			case ArmProtocol.STATE_MELTING -> 1F - ArmMath.sm((t - 20F) / 13F);
			case ArmProtocol.STATE_NONE -> 0F;
			default -> 1F;
		};
	}

	/**
	 * How much the static arm still shows, 0..1: whole while it creeps and before the tendrils come, sinking into them
	 * as they grow (and rising out of them as they go). 0 with no static at all.
	 */
	public float staticShow() {
		if (armStatic() <= 0.001F) return 0F;
		return 1F - ArmMath.sm((armGrowth() - 0.25F) / 0.65F);
	}

	/** True once the static covers the whole skin arm: the skin arm is hidden and the static or the tendrils are the arm. */
	public boolean armReplaced() {
		return !form.isEmpty() && armStatic() >= 0.999F;
	}

	// ------------------------------------------------------------------ the timelines (section 2.6), as geometry reads them
	//
	// Every form reads its timelines through these, so all of them grow, collapse, retract and melt to the same beat. Two
	// kinds of growth: FORM (and a reshape's new form) is the detailed timeline, where each part has its own moment (the
	// spine grows from the guard, the guard's lobes pop one by one, the wraps spiral down, the fuzz sprouts, the hamon
	// draws); everything else (quick-form, a reshape's old form collapsing, retract, melt) grows or shrinks the tool as one
	// piece out of the grip, by {@link #reach}. A form needs only both readings to play every timeline.

	/** A reshape draws the old form until this tick, then the new one forms (section 2.6 RESHAPE). */
	public static final float RESHAPE_SWAP = 7F;
	/** A tick far past the end of FORM: "long since formed", so no forming detail is still settling. */
	public static final float LONG_FORMED = 100F;

	/** The form drawn this frame: while a reshape's old form collapses (its first 7 ticks) that one, else {@link #form}. */
	public String drawnForm() {
		return collapsing() && !fromForm.isEmpty() ? fromForm : form;
	}

	/** A reshape's first stretch: the old form collapsing into the grip. */
	public boolean collapsing() {
		return state == ArmProtocol.STATE_RESHAPING && stateTicks() < RESHAPE_SWAP;
	}

	/**
	 * True while the tool plays FORM's detailed timeline: forming, and a reshape's new form (which plays FORM's ticks 1..14,
	 * compressed). Then each part follows {@link #formTick}. Otherwise the tool grows or shrinks as a whole by
	 * {@link #reach}.
	 */
	public boolean detailedForm() {
		return state == ArmProtocol.STATE_FORMING || state == ArmProtocol.STATE_RESHAPING && !collapsing();
	}

	/**
	 * The FORM timeline's tick the tool's details are at. Forming: the state's own ticks. A reshape's new form: FORM's
	 * ticks 1..14 squeezed into the reshape's ticks 7..16. Quick-form: 1..14 over its ticks 1..8, so the details come in
	 * with the growth. Anything else: {@link #LONG_FORMED}.
	 */
	public float formTick() {
		float t = stateTicks();
		return switch (state) {
			case ArmProtocol.STATE_FORMING -> t;
			case ArmProtocol.STATE_RESHAPING -> t < RESHAPE_SWAP ? LONG_FORMED : FORM_GROW_FROM + (t - RESHAPE_SWAP) * (13F / 9F);
			case ArmProtocol.STATE_QUICKFORM -> FORM_GROW_FROM + Math.max(0F, t - 1F) * (13F / 7F);
			default -> LONG_FORMED;
		};
	}

	/**
	 * How far the tool reaches out of the grip, 0..1 (growth by truncation: 1 is the whole tool). FORM's spine
	 * {@code sm((t−1)/7)}, straight out of the fist before any detail; quick-form {@code sm((t−1)/6)}; a reshape's old form collapsing {@code 1 − sm(t/6)} and its new
	 * one as FORM; retract {@code 1 − sm((t−2)/7)}; melt's erosion front {@code 1 − sm((t−2)/14)}; none 0; formed 1.
	 */
	public float reach() {
		float t = stateTicks();
		return switch (state) {
			case ArmProtocol.STATE_FORMING -> formReach(t);
			case ArmProtocol.STATE_QUICKFORM -> ArmMath.sm((t - 1F) / 6F);
			case ArmProtocol.STATE_RESHAPING -> collapsing() ? 1F - ArmMath.sm(t / 6F) : formReach(formTick());
			case ArmProtocol.STATE_RETRACTING -> 1F - ArmMath.sm((t - 2F) / 7F);
			case ArmProtocol.STATE_MELTING -> 1F - ArmMath.sm((t - 2F) / 14F);
			case ArmProtocol.STATE_NONE -> 0F;
			default -> 1F;
		};
	}

	/** FORM: the tool grows out of the fist from this tick, for {@link #FORM_GROW_TICKS}; its details follow it out. */
	public static final float FORM_GROW_FROM = 1F, FORM_GROW_TICKS = 7F;

	/** FORM's reach at FORM tick {@code ft}. */
	public static float formReach(float ft) {
		return ArmMath.sm((ft - FORM_GROW_FROM) / FORM_GROW_TICKS);
	}

	/**
	 * The width in px of the eroding fringe at the growth front (used only when {@link #dissolve} is on): forming and
	 * reshaping 1.5 (with a growth bulb), quick-form and retract 1.2, melt 3 (its erosion front is wide and ragged).
	 */
	public float fringe() {
		return switch (state) {
			case ArmProtocol.STATE_MELTING -> 3F;
			case ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_RETRACTING -> 1.2F;
			default -> 1.5F;
		};
	}

	/** Whether the growth front swells (FORM's growth bulb, and a collapsing reshape's "fronts swelling"). */
	public boolean frontBulb() {
		return state == ArmProtocol.STATE_FORMING || state == ArmProtocol.STATE_RESHAPING;
	}

	/**
	 * How many burst tendrils erupt from the knuckles: none. The tool grows straight out of the fist and sprouts its own
	 * tendrils once it is out (my call after the first look, 2026-11-15); the burst code stays for a later use.
	 */
	public int burstCount() {
		return 0;
	}

	/** The burst tendrils' clock (FORM's ticks; a reshape's new form joins at tick 4), or −1 with none. */
	public float burstTick() {
		return burstCount() > 0 ? formTick() : -1F;
	}

	/** Ticks into a chosen retract, or −1. */
	public float retractTick() {
		return state == ArmProtocol.STATE_RETRACTING ? stateTicks() : -1F;
	}

	/** Ticks into a melt, or −1. */
	public float meltTick() {
		return state == ArmProtocol.STATE_MELTING ? stateTicks() : -1F;
	}

	/** Ticks into the reshape's churning knot (reshape ticks 5..9 as 0..4), or −1 outside it. */
	public float knotTick() {
		if (state != ArmProtocol.STATE_RESHAPING) return -1F;
		float t = stateTicks() - 5F;
		return t >= 0F && t <= 4F ? t : -1F;
	}

	/**
	 * How much of the arm the sheath (third person) or the forearm (first person) covers, 0..1: 1 reaches up to the elbow
	 * (sheath top y 3.2), 0 is nothing. It follows {@link #armGrowth}: it grows with the arm and goes back with it.
	 */
	public float sheathCover() {
		return armGrowth();
	}

	/** How far the helices have unwound, 0..1: retract ticks 6..11 and melt ticks 10..20 (radius and thickness halve). */
	public float unwind() {
		return state == ArmProtocol.STATE_RETRACTING || state == ArmProtocol.STATE_MELTING ? 1F - armGrowth() : 0F;
	}

	/**
	 * The extra turn the helices have made spinning up while forming (0.6 rad a tick at tick 4, slowing to the idle 0.05
	 * by tick 12): the integral of that extra speed, in radians. Constant (its final value) once formed, so the helices
	 * never jump back.
	 */
	public float helixSpin() {
		float u = ArmMath.clamp(formTick() - 4F, 0F, 8F);
		return 0.55F * (u - u * u / 16F);
	}

	/**
	 * FORM's (and quick-form's) settle pulse at feed coordinate {@code phi}: one bright swelling (amplitude 1, σ 2) running
	 * from the shoulder to the tip over FORM ticks 11..14 (quick-form 6..8).
	 */
	public float settle(float phi, float feedLength) {
		float u;
		if (state == ArmProtocol.STATE_QUICKFORM) u = (stateTicks() - 6F) / 2F;
		else if (detailedForm()) u = (formTick() - 11F) / 3F;
		else return 0F;
		if (u < 0F || u > 1.2F) return 0F;
		return ArmMath.gauss(phi - feedLength * u, 2F) * ArmMath.sm(u * 8F);
	}

	/** {@link #pulse} plus {@link #settle}: what every swelling reads. */
	public float swell(float phi, float feedLength) {
		return pulse(phi, feedLength) + settle(phi, feedLength);
	}

	/**
	 * The static's cadence for this frame: {@link #cadence()}, except that FORM slows to the idle cadence from its tick 10
	 * (section 2.6) and a melt keeps cadence 1 throughout.
	 */
	public int staticCadence() {
		if (detailedForm() && formTick() >= 10F) {
			boolean hit = hitAge < 6F || breakAge < 6F || surge > 0F || mining;
			return Static.cadence(ArmProtocol.STATE_FORMED, specialRunning(), hit, lowStage());
		}
		return cadence();
	}

	/** Melting's first 8 ticks swap red and black every other tick. */
	public boolean swapRedBlack() {
		float t = meltTick();
		return t >= 0F && t < 8F && ((int) Math.floor(time) & 1) == 1;
	}

	/** Melting's droop: each point sinks {@code droop · (d/L)²} px toward world down (4 px at the tip by tick 8). */
	public float droop() {
		float t = meltTick();
		return t < 0F ? 0F : 4F * ArmMath.sm(t / 8F);
	}

	/** The hit's hamon flare, 0..1: full for 4 ticks after a hit, then gone over 6. */
	public float hitFlare() {
		if (!(hitAge >= 0F) || hitAge >= 10F) return 0F;
		return hitAge < 4F ? 1F : 1F - ArmMath.sm((hitAge - 4F) / 6F);
	}

	/** Ticks since the newest FEED pulse began (a heal arriving, a gain), or {@link #NEVER}. */
	public float feedAge() {
		float best = NEVER;
		for (int i = 0; i < pulseCount && 2 * i + 1 < pulses.length; i++) {
			if ((int) pulses[2 * i] != Pulse.Kind.FEED.ordinal()) continue;
			float age = time - pulses[2 * i + 1];
			if (age >= 0F && age < best) best = age;
		}
		return best;
	}

	/** The fist knot's beat: up to +25% for 3 ticks after a hit or a FEED pulse (0 otherwise). */
	public float knotBeat() {
		float age = Math.min(hitAge >= 0F ? hitAge : NEVER, feedAge());
		return age < 3F ? 0.25F * (float) Math.sin(Math.PI * age / 3F) : 0F;
	}

	/**
	 * How strongly the special of {@code kind} shows in hand, 0..1: easing in over its first 2 ticks (the wind-up), held
	 * while it runs, easing out over the 10 ticks after it ends (Draining Cut's hamon recedes).
	 */
	public float specialWeight(int kind) {
		if (specialKind != kind) return 0F;
		if (specialRunning()) return ArmMath.sm(specialAge / 2F);
		float ago = specialEndedAgo();
		return ago < 10F ? 1F - ArmMath.sm(ago / 10F) : 0F;
	}

	/**
	 * A failing arm's spasm (scale on the tool): every 40 ± 8 ticks it clenches to 0.92 for 3 ticks, then overshoots to
	 * 1.03 and settles. 1 when not failing.
	 */
	public float spasm() {
		if (lowStage() != LOW_FAILING) return 1F;
		float t = time + (seed & 63) * 7.3F;
		int cycle = (int) Math.floor(t / 40F);
		float jitter = (float) ((((cycle * 0x9E3779B1L + seed) >>> 13) & 1023) / 1023.0 * 16.0 - 8.0);
		float age = t - (cycle * 40F + 8F + jitter * 0.5F);
		if (age < 0F || age > 7F) return 1F;
		if (age < 1F) return ArmMath.lerp(1F, 0.92F, ArmMath.sm(age));
		if (age < 3F) return 0.92F;
		if (age < 4.5F) return ArmMath.lerp(0.92F, 1.03F, ArmMath.sm((age - 3F) / 1.5F));
		return ArmMath.lerp(1.03F, 1F, ArmMath.sm((age - 4.5F) / 2.5F));
	}

	/** How the static's layers map this frame: BLACK and RED trade places on a melt's odd ticks. */
	public ArmLayer paint(ArmLayer intended) {
		if (!swapRedBlack()) return intended;
		return intended == ArmLayer.BLACK ? ArmLayer.RED : intended == ArmLayer.RED ? ArmLayer.BLACK : intended;
	}

	/** The rate multiplier for drips: ×2 starving, ×3 failing, ×4 melting. */
	public float dripRate() {
		if (state == ArmProtocol.STATE_MELTING) return 4F;
		float rate = switch (lowStage()) {
			case LOW_FAILING -> 3F;
			case LOW_STARVING -> 2F;
			default -> 1F;
		};
		// spending shows: a burst after each hit or broken block, a little more while mining or running a special (the
		// user: drips are the arm visibly losing biomass as it's used)
		if (hitAge < 10F || breakAge < 10F) rate += 1.5F;
		if (mining) rate += 0.5F;
		if (specialRunning()) rate += 1F;
		return rate;
	}

	/** Model px per block for drips falling here: G px in a hand are drawn smaller than world px. */
	public float blocksToPx() {
		return firstPerson() ? 16F / 0.68F : 16F / 0.85F;
	}

	/** Whether drips are drawn: the setting and the renderer's say ({@link #drips}), and never during a gain's surge. */
	public boolean dripsNow() {
		return drips && surge <= 0F && lod == LOD_FULL && !gui();
	}

	// ------------------------------------------------------------------ copies the renderer makes at submit

	/**
	 * This draw finished for one submit: level of detail, drips, the tail, world down, the first-person elbow point and
	 * the light. Everything else is kept.
	 */
	public ArmDraw finish(int lod, boolean drips, float @Nullable [] ropeNodes, float downX, float downY, float downZ, float @Nullable [] fpAnchor, int light) {
		return new ArmDraw(form, tier, lod, ctx, leftHand, slim, chestArmour, time, seed, state, progress, fromForm, swing, bowPull, mining, biomassFrac,
				biomassAbs, surge, specialAge, specialKind, hitAge, breakAge, pulses, pulseCount, lanternLit, lanternAge, recallLeft, glow, dissolve, drips,
				ropeNodes, downX, downY, downZ, fpAnchor, light, pulseSpeed, accessories, accessoryArgs, accessoryAges, thrown);
	}

	/** The same draw of another form (a reshape's old form, or the placeholder's stand-in). */
	public ArmDraw withForm(String form) {
		return new ArmDraw(form, tier, lod, ctx, leftHand, slim, chestArmour, time, seed, state, progress, fromForm, swing, bowPull, mining, biomassFrac,
				biomassAbs, surge, specialAge, specialKind, hitAge, breakAge, pulses, pulseCount, lanternLit, lanternAge, recallLeft, glow, dissolve, drips,
				ropeNodes, downX, downY, downZ, fpAnchor, light, pulseSpeed, accessories, accessoryArgs, accessoryAges, thrown);
	}

	/** The same draw in another state and progress (the sheath's own reading of a timeline, the preview's sliders). */
	public ArmDraw withState(int state, float progress) {
		return new ArmDraw(form, tier, lod, ctx, leftHand, slim, chestArmour, time, seed, state, progress, fromForm, swing, bowPull, mining, biomassFrac,
				biomassAbs, surge, specialAge, specialKind, hitAge, breakAge, pulses, pulseCount, lanternLit, lanternAge, recallLeft, glow, dissolve, drips,
				ropeNodes, downX, downY, downZ, fpAnchor, light, pulseSpeed, accessories, accessoryArgs, accessoryAges, thrown);
	}

	/**
	 * A plain draw of {@code form} at {@code tier}, resting and full, for tests and tools: nothing happening, no pulses,
	 * world down along −Y, {@code ctx} and {@code lod} as given.
	 */
	public static ArmDraw rest(String form, int tier, int ctx, int lod, float time) {
		return new ArmDraw(form, tier, lod, ctx, false, false, false, time, 0, ArmProtocol.STATE_FORMED, 1F, "", 0F, 0F, false, 1F, FULL, 0F, NEVER,
				NO_SPECIAL, NEVER, NEVER, new float[0], 0, false, 0F, 0F, true, false, lod == LOD_FULL && ctx != CTX_GUI, null, 0F, -1F, 0F, null,
				0xF000F0);
	}
}
