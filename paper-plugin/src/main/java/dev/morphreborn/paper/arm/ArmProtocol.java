package dev.morphreborn.paper.arm;

/**
 * The arm forms' share of the companion protocol (spec section 4; docs/PROTOCOL.md): C_ARM's actions, S_ARM's states
 * and reasons, the timelines' lengths and S_ARM_FX's kinds. The client mod has the same numbers in its own ArmProtocol,
 * so neither side may renumber anything; new values are only ever added.
 * <p>
 * Which clients get arm packets is decided by version ({@link #CLIENT_SINCE}), not by the protocol number, which stays
 * 3: everything here is additive, and a modded client older than the arms still gets the server's particle fallbacks.
 */
public final class ArmProtocol {
	/**
	 * The first client mod version that draws arms. Older modded clients are treated like unmodded ones for arms (they
	 * see particles and the vanilla tool). The number is a test build from before the release was named 8.0.0, and it stays so
	 * the gate keeps working for clients that built from it.
	 */
	public static final String CLIENT_SINCE = "7.6.0-beta.2";

	// C_ARM actions
	public static final int ACTION_FORM = 0;
	public static final int ACTION_RETRACT = 1;
	/** The lantern on and off: kept for clients older than {@link #NEW_FORMS_SINCE}, which know no other accessory. */
	public static final int ACTION_LANTERN_ON = 2;
	public static final int ACTION_LANTERN_OFF = 3;

	// Arm slots (2026-11-16): each arm form stays bound to a hotbar slot after scrolling away. Only the held
	// slot holds the arm item; an unheld bound slot keeps its real item and the client draws the arm over it. One slot
	// per form; bindings persist (death, quit, world change); scrolling onto a bound slot re-forms it (QUICKFORM).
	/** C_ARM: the player's inventory (or any container screen) opened: the held arm steps aside for its real item. */
	public static final int ACTION_INVENTORY_OPEN = 4;
	/** C_ARM: the screen closed: the held slot's arm comes back (QUICKFORM) over whatever is in the slot now. */
	public static final int ACTION_INVENTORY_CLOSED = 5;
	/** The first version on both sides with arm slots (S_ARM_SLOTS, the inventory actions). */
	public static final String SLOTS_SINCE = "7.6.0-beta.3";
	/**
	 * The first version on both sides with the whip's moves (2026-11-16: the whip rework): {@link #FX_WHIP_MOVE}
	 * goes only to clients at least this new. An older client that draws arms still gets the server's particles for them,
	 * and the old {@link #FX_WHIP} lash is no longer sent by this server at all.
	 */
	public static final String WHIP_MOVES_SINCE = "7.6.0-beta.4";

	// The new forms (2026-11-16; 8.0.0-beta.2): the spear, mace, trident, crossbow and claws, and the shield, spyglass and
	// flint accessories beside the lantern. Everything they add on the wire is gated by this one version: the new FX kinds
	// (FX_SPEAR .. FX_ACCESSORY) and the trailing accessory lists of S_ARM and S_ARSENAL go only to clients at least this new, and
	// the accessory on and off actions come only from them. An older client that draws arms sees none of it, and (for the new
	// FX kinds) still gets the server's particles.
	/** The first version on both sides with the new forms' wire fields. */
	public static final String NEW_FORMS_SINCE = "8.0.0-beta.2";
	/**
	 * The first client mod version that has the sound events {@code morphreborn:arm_form} and {@code morphreborn:arm_retract}
	 * (Morph's own recordings for an arm forming and retracting). Older modded clients hear the stand-in instead, as an
	 * unknown sound id would be silent.
	 */
	public static final String ARM_SOUNDS_SINCE = "8.0.0-beta.2";
	/**
	 * C_ARM: switch an accessory on, with its id (ArmForm.ACCESSORIES: lantern, shield, spyglass, flint) in the form string.
	 * ACTION_LANTERN_ON / OFF (2 and 3) keep working for the lantern from older clients.
	 */
	public static final int ACTION_ACCESSORY_ON = 6;
	/** C_ARM: switch the accessory named in the form string off. */
	public static final int ACTION_ACCESSORY_OFF = 7;
	/**
	 * C_ARM: step the mode of the accessory named in the form string (the lantern's brightness; the wheel's scroll over an
	 * accessory). It carries one more field after the form string, a varInt step, +1 or -1 (anything else counts as its sign); no
	 * other action has it. The server clamps at the first and last mode, as the lantern's brightness does. Only sent to servers
	 * at least {@link #NEW_FORMS_SINCE} (2026-11-16).
	 */
	public static final int ACTION_ACCESSORY_MODE = 8;
	/**
	 * C_ARM: a held key of an accessory goes down or up (the spyglass's zoom key; 2026-11-16). The form string names the
	 * accessory and one more varInt follows it, 1 while the key is held and 0 once it is let go (anything above 0 counts as held, anything
	 * else as let go). A client that keeps a key held repeats the 1 about every 10 seconds, so a lost 0 cannot leave it on for ever (the
	 * server ends a hold it has not heard of for the accessory's timeout). It skips the 4-tick throttle, as the screen signals do: a dropped
	 * 0 after a 1 would leave the zoom on. Sent only to servers at least {@link #NEW_FORMS_SINCE}; an accessory that is off or has no key ignores it.
	 */
	public static final int ACTION_ACCESSORY_HOLD = 9;

	// The whip's moves, carried in ArmFxMsg.arg of an FX_WHIP_MOVE. Never renumber.
	/** The plain left-click attack landing: a quick lash to what was hit. */
	public static final int WHIP_STRIKE = 1;
	/** F at a creature: the lash wraps it and holds it. */
	public static final int WHIP_SNARE = 2;
	/** F at a snared creature: its held item is ripped away and flung to the owner (or destroyed). */
	public static final int WHIP_DISARM = 3;
	/** F at nothing: a sonic crack ahead, a cone that knocks back and scares. */
	public static final int WHIP_CRACK = 4;
	/** Sneak+F: the lash spins round the owner. */
	public static final int WHIP_WHIRL = 5;
	/** The longest form id C_ARM may carry. */
	public static final int MAX_FORM_ID = 32;
	/** The client sends at most one C_ARM every 4 ticks; the server drops more than one every 2. */
	public static final int CLIENT_THROTTLE_TICKS = 4, SERVER_THROTTLE_TICKS = 2;

	// C_ARM_COLOUR / S_ARM_COLOUR: each player's colour for the red parts of their arm (and the mod's other red accents)
	/** The colour everyone has until they choose another: the arm textures' own red. Players without the mod always see it. */
	public static final int COLOUR_DEFAULT = 0xFF3A3F;
	/** The client sends at most one C_ARM_COLOUR every 20 ticks; the server drops more than one every 10. */
	public static final int COLOUR_CLIENT_THROTTLE_TICKS = 20, COLOUR_SERVER_THROTTLE_TICKS = 10;

	// The arm look (2026-11-16: each player's choice, seen by everyone, like the colour), carried as a varInt
	// appended to C_ARM_COLOUR and S_ARM_COLOUR (read only when present: a client or server without it means the default).
	// Never renumber: 0 the tendril arm (the look since the first arm forms, the default), 1 the player's own Minecraft
	// arm holding the tool with a few small tendrils round the hand, 2 the tendril arm armoured with chitin plates.
	public static final int LOOK_ARM = 0, LOOK_HAND = 1, LOOK_ARMOUR = 2;
	public static final int LOOK_DEFAULT = LOOK_ARM;
	/** The looks' names, by number, as the client's settings file and /morphclient armlook spell them. */
	public static final java.util.List<String> LOOK_NAMES = java.util.List.of("arm", "hand", "armour");

	/** Whether {@code look} is a look C_ARM_COLOUR may carry. */
	public static boolean validLook(int look) {
		return look >= 0 && look < LOOK_NAMES.size();
	}

	/** {@code look} if it is a known look, else the default (a newer client's look this build doesn't know). */
	public static int lookOrDefault(int look) {
		return validLook(look) ? look : LOOK_DEFAULT;
	}

	// The pulse speed (2026-11-16: "the light pulse, a choice in the morph client settings, the players choice but
	// it should show for multiple people ... a slow and fast pulse, perhaps a slider for speed"): how fast the pulses
	// travelling along this player's arm forms and lantern run, as a whole percent of the usual speed, a varInt appended
	// after the look to C_ARM_COLOUR and S_ARM_COLOUR (read only when present: nothing means the usual speed). It goes only
	// to clients at {@link #PULSE_SPEED_SINCE}; older ones show every pulse at the usual speed.
	/** The first version on both sides with the pulse speed field. */
	public static final String PULSE_SPEED_SINCE = "8.0.0-beta.1";
	/** The usual speed (100%) and the slowest and fastest a player may ask for (a quarter to three times). */
	public static final int PULSE_SPEED_DEFAULT = 100, PULSE_SPEED_MIN = 25, PULSE_SPEED_MAX = 300;

	/** {@code percent} kept to {@link #PULSE_SPEED_MIN} .. {@link #PULSE_SPEED_MAX}: a request out of range is the nearest allowed speed. */
	public static int clampPulseSpeed(int percent) {
		return Math.clamp(percent, PULSE_SPEED_MIN, PULSE_SPEED_MAX);
	}

	/** Whether {@code rgb} is a colour C_ARM_COLOUR may carry: 0x000000 to 0xFFFFFF, nothing in the top byte. */
	public static boolean validColour(int rgb) {
		return (rgb & ~0xFFFFFF) == 0;
	}

	// S_ARM states
	public static final int STATE_NONE = 0;
	public static final int STATE_FORMING = 1;
	public static final int STATE_FORMED = 2;
	public static final int STATE_RESHAPING = 3;
	public static final int STATE_RETRACTING = 4;
	public static final int STATE_MELTING = 5;
	public static final int STATE_QUICKFORM = 6;

	// S_ARM reasons (why)
	public static final int WHY_CHOSEN = 0;
	public static final int WHY_DEPLETED = 1;
	public static final int WHY_DEATH = 2;
	public static final int WHY_MORPH = 3;
	public static final int WHY_SLOT = 4;
	public static final int WHY_ADMIN = 5;
	public static final int WHY_MODE_OR_RELOAD = 6;
	public static final int WHY_WORLD = 7;
	public static final int WHY_QUIT = 8;
	public static final int WHY_COMMAND = 9;
	public static final int WHY_GAMEMODE = 10;
	public static final int WHY_DISABLE = 11;
	public static final int WHY_ERROR = 12;
	/** The quick re-form of scrolling onto a bound slot (or an inventory screen closing); named for the recall arm slots replaced. */
	public static final int WHY_RECALL = 13;
	public static final int WHY_UPGRADE = 14;
	/** Biomass crossed a third of the bar: the arm stepped down (or back up) a level. */
	public static final int WHY_BIOMASS = 15;

	// Timeline lengths in ticks (spec 2.6)
	// (more than doubled on 2026-11-15: the arm and the tools changed too fast to see; the mod's geometry keeps
	// its own beat, ArmDraw.toolTicks)
	public static final int FORM_TICKS = 40;
	public static final int QUICKFORM_TICKS = 20;
	public static final int RESHAPE_TICKS = 32;
	public static final int RETRACT_TICKS = 30;
	public static final int MELT_TICKS = 40;

	// S_ARM_FX kinds
	public static final int FX_HIT = 0;
	public static final int FX_BREAK = 1;
	public static final int FX_SWIPE = 2;
	/** Stateful: the creatures linked now; resent on change and every {@link #DRAIN_RESEND_TICKS}; an empty list ends it. */
	public static final int FX_DRAIN = 3;
	public static final int FX_PULSE = 4;
	public static final int FX_VEIN = 5;
	public static final int FX_FELL = 6;
	public static final int FX_CLEAVE = 7;
	/** Stateful. */
	public static final int FX_BURROW = 8;
	public static final int FX_REAP = 9;
	public static final int FX_BOLT = 10;
	/** Stateful. */
	public static final int FX_HARPOON = 11;
	public static final int FX_FLARE = 12;
	/**
	 * The whip's old Lash (a crack along the sight that grappled or yanked). The Lash was removed on 2026-11-16 and this
	 * server no longer sends it; the number stays reserved so a client that still draws it keeps working.
	 */
	public static final int FX_WHIP = 13;
	/**
	 * One of the whip's moves ({@code arg} is WHIP_STRIKE .. WHIP_WHIRL; only to clients at {@link #WHIP_MOVES_SINCE}). level is the
	 * tier, ticks how long it shows (SNARE: the hold; WHIRL about 8; CRACK about 6; STRIKE about 5; DISARM about 6). ids:
	 * STRIKE [target], SNARE [target], DISARM [target, the dropped item's entity id or -1 when it was destroyed],
	 * CRACK and WHIRL [creatures hit...]; positions: CRACK [the far point of the crack, BlockPos.asLong], else empty.
	 */
	public static final int FX_WHIP_MOVE = 14;
	// The new forms' effects (8.0.0-beta.2; only to clients at NEW_FORMS_SINCE). Each family multiplexes its moves on ArmFxMsg.arg,
	// which phase 2 defines next to the module that sends it (like WHIP_STRIKE .. WHIP_WHIRL above).
	public static final int FX_SPEAR = 15;
	public static final int FX_MACE = 16;
	public static final int FX_TRIDENT = 17;
	public static final int FX_CROSSBOW = 18;
	public static final int FX_CLAWS = 19;
	/** The accessories' effects: the shield's block and reflect, flint's sparks and ignitions, the spyglass's marking. */
	public static final int FX_ACCESSORY = 20;
	public static final int FX_KINDS = 21;
	/**
	 * FX_ACCESSORY's {@code arg} is {@code (place << 8) | move}: the accessory's place in the accessory ring (ArmForm.ACCESSORIES: lantern 0,
	 * shield 1, spyglass 2, flint 3) in bits 8-11 and its move in bits 0-7, so two accessories' moves never meet on one number (decided
	 * 2026-11-16). {@code origin} is the owner and {@code ids} never carry the owner. The lantern sends no FX_ACCESSORY today (its
	 * state is all in S_ARM); place 0 is kept for it. Each accessory's rules class keeps its own moves; the client keeps a copy of these
	 * (client ArmProtocol), and neither side may renumber.
	 */
	public static final int ACCESSORY_PLACE_LANTERN = 0, ACCESSORY_PLACE_SHIELD = 1, ACCESSORY_PLACE_SPYGLASS = 2, ACCESSORY_PLACE_FLINT = 3;
	public static final int ACCESSORY_PLACE_SHIFT = 8, ACCESSORY_PLACE_MASK = 0xF, ACCESSORY_MOVE_MASK = 0xFF;

	/** S_ARM_FX reaches players who draw arms this far from the origin; HIT and BREAK only {@link #FX_RADIUS_NEAR}. */
	public static final double FX_RADIUS = 96, FX_RADIUS_NEAR = 32;
	/** A client drops a stateful effect it hasn't heard of for this long. */
	public static final int STATEFUL_TIMEOUT_TICKS = 60;
	public static final int DRAIN_RESEND_TICKS = 20;

	/** Vein Rip cracks each ore this many ticks ahead: 0.45 at two ticks before its break, 0.9 at one. */
	public static final int VEIN_CRACK_LEAD = 2;
	public static final float VEIN_CRACK_FIRST = 0.45f, VEIN_CRACK_SECOND = 0.9f;

	private ArmProtocol() {}

	/** A state's timeline length, 0 for NONE and FORMED (they last until changed). */
	public static int duration(int state) {
		return switch (state) {
			case STATE_FORMING -> FORM_TICKS;
			case STATE_RESHAPING -> RESHAPE_TICKS;
			case STATE_RETRACTING -> RETRACT_TICKS;
			case STATE_MELTING -> MELT_TICKS;
			case STATE_QUICKFORM -> QUICKFORM_TICKS;
			default -> 0;
		};
	}

	/** States in which the arm digs and hits: everything but retracting and melting (and none). */
	public static boolean usable(int state) {
		return state == STATE_FORMING || state == STATE_FORMED || state == STATE_RESHAPING || state == STATE_QUICKFORM;
	}

	public static boolean statefulFx(int kind) {
		return kind == FX_DRAIN || kind == FX_BURROW || kind == FX_HARPOON;
	}

	/** How far an effect of this kind is sent. */
	public static double fxRadius(int kind) {
		return kind == FX_HIT || kind == FX_BREAK ? FX_RADIUS_NEAR : FX_RADIUS;
	}

	/**
	 * The tick after the plan Vein Rip breaks ore {@code index} (0 first): 3 ticks of crack lead for the first, then
	 * {@code rateTenths / 10} ores a tick. The client draws the network on the same clock, so this is protocol.
	 */
	public static int veinBreakTick(int index, int rateTenths) {
		return 3 + index * 10 / Math.max(1, rateTenths);
	}

	/**
	 * The breaker id Vein Rip's cracks are sent under: unlike any real entity's, so they never replace the player's own
	 * digging crack, and one per owner, so two players' veins don't clear each other's.
	 */
	public static int crackSourceId(int ownerEntityId) {
		return 0x7A000000 | (ownerEntityId & 0xFFFFFF);
	}

	public static String stateName(int state) {
		return switch (state) {
			case STATE_NONE -> "none";
			case STATE_FORMING -> "forming";
			case STATE_FORMED -> "formed";
			case STATE_RESHAPING -> "reshaping";
			case STATE_RETRACTING -> "retracting";
			case STATE_MELTING -> "melting";
			case STATE_QUICKFORM -> "quickform";
			default -> "state " + state;
		};
	}

	public static String whyName(int why) {
		return switch (why) {
			case WHY_CHOSEN -> "chosen";
			case WHY_DEPLETED -> "depleted";
			case WHY_DEATH -> "death";
			case WHY_MORPH -> "morph";
			case WHY_SLOT -> "slot";
			case WHY_ADMIN -> "admin";
			case WHY_MODE_OR_RELOAD -> "mode or reload";
			case WHY_WORLD -> "world";
			case WHY_QUIT -> "quit";
			case WHY_COMMAND -> "command";
			case WHY_GAMEMODE -> "game mode";
			case WHY_DISABLE -> "disable";
			case WHY_ERROR -> "error";
			case WHY_RECALL -> "recall";
			case WHY_UPGRADE -> "upgrade";
			case WHY_BIOMASS -> "biomass";
			default -> "why " + why;
		};
	}

	/** An FX_ACCESSORY {@code arg} for {@code move} (0 to 255) of the accessory at {@code place} (0 to 15). */
	public static int accessoryArg(int place, int move) {
		return (place & ACCESSORY_PLACE_MASK) << ACCESSORY_PLACE_SHIFT | (move & ACCESSORY_MOVE_MASK);
	}

	/** The accessory's place in an FX_ACCESSORY {@code arg}. */
	public static int accessoryPlace(int arg) {
		return arg >> ACCESSORY_PLACE_SHIFT & ACCESSORY_PLACE_MASK;
	}

	/** The move in an FX_ACCESSORY {@code arg}. */
	public static int accessoryMove(int arg) {
		return arg & ACCESSORY_MOVE_MASK;
	}

	/** Whether {@code kind} is one of the new forms' effects, which only clients at {@link #NEW_FORMS_SINCE} are sent. */
	public static boolean newFormsFx(int kind) {
		return kind >= FX_SPEAR && kind <= FX_ACCESSORY;
	}

	public static String fxName(int kind) {
		return switch (kind) {
			case FX_HIT -> "hit";
			case FX_BREAK -> "break";
			case FX_SWIPE -> "swipe";
			case FX_DRAIN -> "drain";
			case FX_PULSE -> "pulse";
			case FX_VEIN -> "vein";
			case FX_FELL -> "fell";
			case FX_CLEAVE -> "cleave";
			case FX_BURROW -> "burrow";
			case FX_REAP -> "reap";
			case FX_BOLT -> "bolt";
			case FX_HARPOON -> "harpoon";
			case FX_FLARE -> "flare";
			case FX_WHIP -> "whip";
			case FX_WHIP_MOVE -> "whip_move";
			case FX_SPEAR -> "spear";
			case FX_MACE -> "mace";
			case FX_TRIDENT -> "trident";
			case FX_CROSSBOW -> "crossbow";
			case FX_CLAWS -> "claws";
			case FX_ACCESSORY -> "accessory";
			default -> "fx " + kind;
		};
	}
}
