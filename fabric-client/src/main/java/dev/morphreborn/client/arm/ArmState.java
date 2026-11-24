package dev.morphreborn.client.arm;

import dev.morphreborn.client.net.Wire;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's arm, as the server last said (S_ARM): which form is out, which timeline it is in and how far, and the
 * lantern and recall that ride along. Immutable: every packet makes a new one, and {@link Arms#tick} replaces one whose
 * timeline has run out.
 * <p>
 * Times are relative, as S_MORPH_STATE's are: the server sends how many ticks of the state have passed, and the client
 * counts on from there with its own clock ({@link Arms#TICK}). So a viewer who arrives late (a handshake, a world change,
 * a respawn) sees the arm where it is now, not a whole forming animation replayed. Every {@code *Tick} field here is on
 * that clock.
 * <p>
 * A player whose arm is down still has a state while the lantern is lit or a recall is running (the bracelet that says
 * scrolling back re-forms the arm); otherwise a NONE state is dropped at once.
 */
public final class ArmState {
	/** The player this is about; keyed by UUID, so it survives the entity being sent again. */
	public final UUID subject;
	/** The player's entity id when the server sent this (informational: the effects and the render state use it). */
	public final int entityId;
	/** The form that is out or forming ("" with none), and, while reshaping, the one it comes from ("" otherwise). */
	public final String form, fromForm;
	/**
	 * Tier 0-3 (1 iron, 2 diamond, 3 netherite); the {@code ArmProtocol.STATE_*}; the timeline's length in ticks (0 for
	 * NONE and FORMED); the {@code ArmProtocol.WHY_*}; the hotbar slot that holds the arm (-1: none, and only ever sent to
	 * the owner in a useful way); the lantern's tier (0 = not owned).
	 */
	public final int level, state, duration, why, slot, lanternLevel;
	public final boolean lanternLit;
	/** The lantern's brightness its holder chose, 1 to 5 (0: not lit, or a server from before 8.0.0). */
	public int lanternBright;
	/** On {@link Arms#TICK}: when this state began, when the lantern was lit, and when the recall ends. */
	public final long startTick, lanternSince, recallUntil;
	/** When the packet arrived, on {@link Arms#TICK}. */
	public final long receivedTick;
	/**
	 * When this player's lit lantern went out, on {@link Arms#TICK} (the client sees it go from lit to out; Long.MIN_VALUE:
	 * not going out): the lure then draws its growth backwards for {@link #LANTERN_OUT_TICKS} (2026-11-16: the
	 * lantern needs a retraction animation). Set by Arms when the state arrives, carried through {@link #finished}.
	 */
	long lanternOutAt = Long.MIN_VALUE;
	/**
	 * What the lantern was when it went out, for drawing it back (the server sends level and brightness 0 once it is out):
	 * its tier, its brightness, and how long it had been lit (its build plays backwards from there, not from fully built).
	 */
	int lanternOutLevel, lanternOutBright;
	float lanternOutFrom;
	/** Ticks added to the lit age: a lantern lit again while it was still drawing back carries on from where it had got to. */
	float lanternAgeBoost;
	/** How long the lantern takes to draw back into the arm once it goes out (ticks). */
	public static final int LANTERN_OUT_TICKS = 20; // 1 s (the arm retracts over 1.5 s; the lantern was too quick at 0.6)

	/**
	 * One accessory as the server's S_ARM lists it (the new forms, 2026-11-16): its id, whether it is on, its tier, and an
	 * accessory-specific number ({@code arg}: the shield raised 0/1, the spyglass zooming 0/1; 0 where it has none).
	 */
	public record Accessory(String id, boolean on, int level, int arg) {}

	/** How long an accessory other than the lantern takes to draw back once it goes out, and the age a late viewer sees one at (built). */
	public static final int ACCESSORY_OUT_TICKS = 20, ACCESSORY_BUILT_TICKS = 40;

	/**
	 * The accessories the server listed in this S_ARM (after the lantern fields; empty from a server before
	 * {@link ArmProtocol#NEW_FORMS_SINCE}, and for ids this client does not know). The lantern may be in it too: for the lantern the
	 * lantern fields above are the truth (they are what every server since 8.0.0 sends), so a lantern entry here only adds its arg.
	 */
	List<Accessory> accessories = List.of();
	/**
	 * Per accessory, by its place in {@link ArmProtocol#ACCESSORIES}: when it came on, when it went out (Long.MIN_VALUE: not going
	 * out) and what it was then, so it can draw back as the lantern does. Set by {@link Arms#put}; the lantern's own are the
	 * fields above (index 0 here is unused).
	 */
	final long[] accSince = new long[ArmProtocol.ACCESSORIES.size()], accOutAt = new long[ArmProtocol.ACCESSORIES.size()];
	final int[] accOutLevel = new int[ArmProtocol.ACCESSORIES.size()], accOutArg = new int[ArmProtocol.ACCESSORIES.size()];

	{
		java.util.Arrays.fill(accOutAt, Long.MIN_VALUE);
	}

	/**
	 * A state as the server describes it, received at {@code now} ({@link Arms#TICK}): {@code time} ticks of it have passed
	 * already, the lantern has been lit for {@code lanternTime} ticks and the recall has {@code recallTicks} left. {@code
	 * slot} is the hotbar slot itself (-1: none), not the wire's slot + 1.
	 */
	public ArmState(UUID subject, int entityId, String form, String fromForm, int level, int state, int time, int duration, int slot,
			int why, boolean lanternLit, int lanternLevel, int lanternTime, int recallTicks, long now) {
		this(subject, entityId, form, fromForm, level, state, duration, why, slot, lanternLevel, lanternLit,
				now - Math.max(0, time), now - Math.max(0, lanternTime), now + Math.max(0, recallTicks), now);
	}

	private ArmState(UUID subject, int entityId, String form, String fromForm, int level, int state, int duration, int why, int slot,
			int lanternLevel, boolean lanternLit, long startTick, long lanternSince, long recallUntil, long receivedTick) {
		this.subject = subject;
		this.entityId = entityId;
		this.form = form == null ? "" : form;
		this.fromForm = fromForm == null ? "" : fromForm;
		this.level = Math.clamp(level, 0, 3);
		this.state = state;
		this.duration = Math.max(0, duration);
		this.why = why;
		this.slot = slot;
		this.lanternLevel = Math.max(0, lanternLevel);
		this.lanternLit = lanternLit;
		this.startTick = startTick;
		this.lanternSince = lanternSince;
		this.recallUntil = recallUntil;
		this.receivedTick = receivedTick;
	}

	/**
	 * Reads S_ARM after its id: uuid subject, varInt entity id, string form, string from-form, varInt level, varInt
	 * state, varInt time, varInt duration, varInt slot + 1 (0 = none), varInt why, bool lantern lit, varInt lantern
	 * level, varInt lantern time, varInt recall ticks, then (when present) varInt lantern brightness and, from servers at
	 * {@link ArmProtocol#NEW_FORMS_SINCE}, varInt n and n times {string accessory id, bool on, varInt level, varInt arg}.
	 * Anything after that is a newer server's and is left unread.
	 */
	public static ArmState read(Wire.In in, long now) {
		UUID subject = in.uuid();
		int entityId = in.varInt();
		String form = in.string();
		String fromForm = in.string();
		int level = in.varInt();
		int state = in.varInt();
		int time = in.varInt();
		int duration = in.varInt();
		int slot = in.varInt() - 1;
		int why = in.varInt();
		boolean lit = in.bool();
		int lanternLevel = in.varInt();
		int lanternTime = in.varInt();
		int recall = in.varInt();
		ArmState out = new ArmState(subject, entityId, form, fromForm, level, state, time, duration, Math.max(-1, slot), why, lit, lanternLevel,
				lanternTime, recall, now);
		out.lanternBright = in.hasMore() ? Math.clamp(in.varInt(), 0, 5) : 0; // since 8.0.0
		// since ArmProtocol.NEW_FORMS_SINCE: varInt n, then n times {string id, bool on, varInt level, varInt arg}
		if (in.hasMore()) {
			int n = in.count();
			List<Accessory> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				String id = in.string();
				boolean on = in.bool();
				int accessoryLevel = in.varInt();
				int arg = in.varInt();
				// an accessory a newer server has and this client does not is skipped, as ArmSlots skips unknown forms
				if (ArmProtocol.isAccessory(id)) list.add(new Accessory(id, on, Math.max(0, accessoryLevel), Math.max(0, arg)));
			}
			out.accessories = List.copyOf(list);
		}
		return out;
	}

	// ------------------------------------------------------------------ the accessories

	private @org.jspecify.annotations.Nullable Accessory accessory(String id) {
		for (int i = 0; i < accessories.size(); i++) { // indexed: this runs per accessory per frame, so no iterator
			Accessory a = accessories.get(i);
			if (a.id().equals(id)) return a;
		}
		return null;
	}

	/** The array {@link #drawAges} fills, and the frame (arm tick and partial tick) it was last filled for. */
	private final float[] drawAges = new float[ArmProtocol.ACCESSORIES.size()];
	private long drawAgesTick = Long.MIN_VALUE;
	private float drawAgesPt = Float.NaN;

	/**
	 * The draw ages of the accessories in {@code mask}, one per place in {@link ArmProtocol#ACCESSORIES} (0 for the rest), for
	 * {@code ArmDraw.accessoryAges}. The same array every time, refilled only when the frame has moved on, so it costs nothing per
	 * frame: a draw reads it within the frame it was built in, and the next frame's refill comes after that frame's render.
	 */
	public float[] drawAges(int mask, float pt) {
		if (drawAgesTick != Arms.TICK || drawAgesPt != pt) {
			drawAgesTick = Arms.TICK;
			drawAgesPt = pt;
			for (int i = 0; i < drawAges.length; i++) {
				drawAges[i] = (mask & (1 << i)) != 0 ? accessoryDrawAge(ArmProtocol.ACCESSORIES.get(i), pt) : 0F;
			}
		}
		return drawAges;
	}

	/** Whether accessory {@code id} is on: the lantern by its own fields (which every server sends), the rest by the list. */
	public boolean accessoryOn(String id) {
		if (ArmProtocol.LANTERN.equals(id)) return lanternLit;
		Accessory a = accessory(id);
		return a != null && a.on();
	}

	/** The accessory's tier (0: not listed); the lantern's is {@link #lanternLevel}. */
	public int accessoryLevel(String id) {
		if (ArmProtocol.LANTERN.equals(id)) return lanternLevel;
		Accessory a = accessory(id);
		return a == null ? 0 : a.level();
	}

	/** The accessory's own number (the shield raised, the spyglass zooming: 0 or 1; the lantern's is its brightness). */
	public int accessoryArg(String id) {
		if (ArmProtocol.LANTERN.equals(id)) return lanternBright;
		Accessory a = accessory(id);
		return a == null ? 0 : a.arg();
	}

	/** The accessories as listed (read-only). */
	public List<Accessory> accessories() {
		return accessories;
	}

	/** Ticks accessory {@code id} has been on at this frame (0 while it is not). The lantern's is {@link #lanternAge}. */
	public float accessoryAge(String id, float pt) {
		int i = ArmProtocol.accessoryIndex(id);
		if (i < 0) return 0F;
		if (i == 0) return lanternAge(pt);
		return accessoryOn(id) ? Math.max(0F, Arms.age(accSince[i], pt)) : 0F;
	}

	/** Whether accessory {@code id} is drawing back into the arm at {@code now}: it went out less than {@link #ACCESSORY_OUT_TICKS} ago. */
	public boolean accessoryGoingOut(String id, long now) {
		int i = ArmProtocol.accessoryIndex(id);
		if (i < 0) return false;
		if (i == 0) return lanternGoingOut(now);
		return !accessoryOn(id) && accOutAt[i] != Long.MIN_VALUE && now - accOutAt[i] < ACCESSORY_OUT_TICKS;
	}

	/**
	 * The age accessory {@code id} is drawn at, as {@link #lanternDrawAge} is for the lantern: ticks since it came on, or while it
	 * goes out {@code -1 - ticks since it went out} (negative: its growth plays backwards), 0 when it is off.
	 */
	public float accessoryDrawAge(String id, float pt) {
		int i = ArmProtocol.accessoryIndex(id);
		if (i < 0) return 0F;
		if (i == 0) return lanternDrawAge(pt);
		if (accessoryOn(id)) return accessoryAge(id, pt);
		return accessoryGoingOut(id, Arms.TICK) ? -1F - Math.max(0F, Arms.age(accOutAt[i], pt)) : 0F;
	}

	/** The accessories to draw now, as a set (bit i is {@code ArmProtocol.ACCESSORIES.get(i)}): the ones on and the ones drawing back. */
	public int accessoryMask(long now) {
		int mask = 0;
		for (int i = 0; i < ArmProtocol.ACCESSORIES.size(); i++) {
			String id = ArmProtocol.ACCESSORIES.get(i);
			if (accessoryOn(id) || accessoryGoingOut(id, now)) mask |= 1 << i;
		}
		return mask;
	}

	/** The accessories' numbers, four bits each (accessory i at bits 4i..4i+3; 15 at most), for {@code ArmDraw.accessoryArgs}. */
	public int accessoryArgs() {
		int packed = 0;
		for (int i = 1; i < ArmProtocol.ACCESSORIES.size() && i < 8; i++) {
			String id = ArmProtocol.ACCESSORIES.get(i);
			if (accessoryOn(id)) packed |= Math.min(15, accessoryArg(id)) << (4 * i);
		}
		return packed;
	}

	/** Whether any accessory is on or drawing back at {@code now}. */
	public boolean anyAccessory(long now) {
		return accessoryMask(now) != 0;
	}

	/** Ticks spent in this state, with the frame's partial tick (never negative). */
	public float elapsed(float pt) {
		return Math.max(0F, Arms.age(startTick, pt));
	}

	/** 0..1 through a timed state; 1 for FORMED and NONE, which have no timeline. */
	public float progress(float pt) {
		if (duration <= 0 || state == ArmProtocol.STATE_FORMED || state == ArmProtocol.STATE_NONE) return 1F;
		float p = elapsed(pt) / duration;
		return p < 0F ? 0F : Math.min(1F, p);
	}

	/**
	 * The arm item is in the player's hand: from the first tick of forming until a retract or melt has finished (the
	 * server keeps it in the slot that long).
	 */
	public boolean armed() {
		return state != ArmProtocol.STATE_NONE;
	}

	/** The form the hand holds: {@link #form}, or while a server sends none during a retract, the one it comes from. */
	public String heldForm() {
		return form.isEmpty() ? fromForm : form;
	}

	/** In a timeline (forming, quick-forming, reshaping, retracting, melting) rather than resting. */
	public boolean timed() {
		return duration > 0 && state != ArmProtocol.STATE_FORMED && state != ArmProtocol.STATE_NONE;
	}

	/** Ticks of the recall left at this frame (0: none): scrolling back to {@link #slot} re-forms the arm until then. */
	public float recallLeft(float pt) {
		return Math.max(0F, -Arms.age(recallUntil, pt));
	}

	/** Ticks the lantern has been lit at this frame (0 while it isn't). */
	public float lanternAge(float pt) {
		return lanternLit ? Math.max(0F, Arms.age(lanternSince, pt)) + lanternAgeBoost : 0F;
	}

	/** The lantern's tier and brightness as drawn: as it was while it draws back after going out, else as sent. */
	public int lanternDrawLevel() {
		return lanternGoingOut(Arms.TICK) ? lanternOutLevel : lanternLevel;
	}

	public int lanternDrawBright() {
		return lanternGoingOut(Arms.TICK) ? lanternOutBright : lanternBright;
	}

	/** How long the lantern had been lit when it went out (ticks). */
	public float lanternOutFrom() {
		return lanternOutFrom;
	}

	/** Whether the lantern is drawing back into the arm at {@code now}: it went out less than LANTERN_OUT_TICKS ago. */
	public boolean lanternGoingOut(long now) {
		return !lanternLit && lanternOutAt != Long.MIN_VALUE && now - lanternOutAt < LANTERN_OUT_TICKS;
	}

	/** Ticks since the lantern went out at this frame (only meaningful while {@link #lanternGoingOut}). */
	public float lanternOutAge(float pt) {
		return Math.max(0F, Arms.age(lanternOutAt, pt));
	}

	/**
	 * The lantern's age as the lure is drawn: ticks since it was lit, or while it goes out {@code -1 - ticks since it went
	 * out} (negative: the lure plays its growth backwards), 0 with no lantern.
	 */
	public float lanternDrawAge(float pt) {
		if (lanternLit) return lanternAge(pt);
		return lanternGoingOut(Arms.TICK) ? -1F - lanternOutAge(pt) : 0F;
	}

	/** Still worth keeping at {@code now}: an arm in hand, the lantern lit or going out, or a recall running. */
	boolean live(long now) {
		return armed() || lanternLit || recallUntil > now || lanternGoingOut(now) || anyAccessory(now);
	}

	/** Whether a timeline has run out at {@code now}, {@code grace} ticks after its end. */
	boolean ended(long now, int grace) {
		return timed() && now - startTick >= duration + grace;
	}

	/**
	 * What this becomes once its timeline has run out, if the server's next state hasn't come yet: forming, quick-forming
	 * and reshaping rest as FORMED (the server says so too, a moment later); retracting and melting end with no arm, the
	 * lantern and the recall kept. The time in the new state counts from the old one's end.
	 */
	ArmState finished() {
		ArmState next = finishedState();
		next.lanternBright = lanternBright;
		next.lanternOutAt = lanternOutAt;
		next.lanternOutLevel = lanternOutLevel;
		next.lanternOutBright = lanternOutBright;
		next.lanternOutFrom = lanternOutFrom;
		next.lanternAgeBoost = lanternAgeBoost;
		next.accessories = accessories;
		System.arraycopy(accSince, 0, next.accSince, 0, accSince.length);
		System.arraycopy(accOutAt, 0, next.accOutAt, 0, accOutAt.length);
		System.arraycopy(accOutLevel, 0, next.accOutLevel, 0, accOutLevel.length);
		System.arraycopy(accOutArg, 0, next.accOutArg, 0, accOutArg.length);
		return next;
	}

	private ArmState finishedState() {
		long end = startTick + duration;
		return switch (state) {
			case ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM, ArmProtocol.STATE_RESHAPING ->
					new ArmState(subject, entityId, form, "", level, ArmProtocol.STATE_FORMED, 0, why, slot, lanternLevel, lanternLit, end,
							lanternSince, recallUntil, receivedTick);
			case ArmProtocol.STATE_RETRACTING, ArmProtocol.STATE_MELTING ->
					new ArmState(subject, entityId, "", "", 0, ArmProtocol.STATE_NONE, 0, why, -1, lanternLevel, lanternLit, end, lanternSince,
							recallUntil, receivedTick);
			default -> this;
		};
	}

	@Override
	public String toString() {
		return "ArmState[" + subject + " #" + entityId + " " + (form.isEmpty() ? "-" : form) + (fromForm.isEmpty() ? "" : " from " + fromForm)
				+ " L" + level + " state " + state + " for " + duration + " why " + why + " slot " + slot + (lanternLit ? " lantern " + lanternLevel : "")
				+ "]";
	}
}
