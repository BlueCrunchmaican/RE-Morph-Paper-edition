package dev.morphreborn.client.arm;

import java.util.List;

/**
 * The arm forms' numbers on the wire (C_ARM, S_ARM, S_ARM_FX, S_ARSENAL), the same names and values as the server's
 * {@code dev.morphreborn.paper.arm.ArmProtocol}, so both sides read each other: a state, a reason or an effect kind is
 * only ever sent as one of these ints, and neither side may renumber one; new values are only ever added. docs/PROTOCOL.md
 * is the shared specification. The server's {@code CLIENT_SINCE} (the first client release that draws arms) has no use
 * here, so it isn't copied; what only the client needs (the item's tag, the form ids, the tier names) comes at the end.
 * <p>
 * Pure constants and arithmetic: nothing here touches the game, so the unit tests can use it.
 */
public final class ArmProtocol {
	// C_ARM actions
	/** Form (or reshape into) the string form that follows. */
	public static final int ACTION_FORM = 0;
	/** Put the arm back (a chosen retract: the 12-tick timeline). */
	public static final int ACTION_RETRACT = 1;
	/** The lantern's own toggles, kept for servers from before {@link #NEW_FORMS_SINCE} (a newer one takes {@link #ACTION_ACCESSORY_ON} with "lantern" too). */
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

	// The new forms and accessories (2026-11-16: spear, mace, trident, crossbow and claws; shield, spyglass and
	// flint and steel beside the lantern). One accessory at a time is no longer the rule, so a toggle names its accessory.
	/** C_ARM: switch the accessory whose id is the string that follows on. Several may be on at once. */
	public static final int ACTION_ACCESSORY_ON = 6;
	/** C_ARM: switch that accessory off. */
	public static final int ACTION_ACCESSORY_OFF = 7;
	/**
	 * C_ARM: step the mode of the accessory whose id is the string (the lantern's brightness; 2026-11-16: the wheel's
	 * scroll over its lump). The step, +1 or -1, follows the string as one more varInt (C_ARM has no other number to carry it, so it is
	 * appended; sent for this action only). The server clamps at the ends as the lantern's brightness does, and an accessory with no
	 * mode ignores it. Only to servers from {@link #NEW_FORMS_SINCE}.
	 */
	public static final int ACTION_ACCESSORY_MODE = 8;
	/**
	 * C_ARM: an accessory's key is held (1) or let go (0): the spyglass's zoom (2026-11-16). The string is the accessory's id and one
	 * varInt follows it, as the step does for {@link #ACTION_ACCESSORY_MODE}. A held key repeats its 1 every {@link #HOLD_REPEAT_TICKS}
	 * (the server ends a zoom it hears nothing of for 15 seconds at the least), and the server skips the 4-tick throttle for this
	 * action, so neither is dropped. Only to servers from {@link #NEW_FORMS_SINCE}.
	 */
	public static final int ACTION_ACCESSORY_HOLD = 9;
	/** How often a held accessory key repeats its 1 (about every 10 seconds). */
	public static final int HOLD_REPEAT_TICKS = 200;
	/**
	 * The first version on both sides with the new forms: FX kinds {@link #FX_SPEAR} up, and the accessory lists appended to
	 * S_ARM and S_ARSENAL, the accessory actions of C_ARM. Older clients are sent none of it; older servers none of the new forms.
	 */
	public static final String NEW_FORMS_SINCE = "8.0.0-beta.2";
	/** The longest form id C_ARM may carry (the server reads it with that cap). */
	public static final int MAX_FORM_ID = 32;
	/** The client sends at most one C_ARM every 4 ticks; the server drops more than one every 2. */
	public static final int CLIENT_THROTTLE_TICKS = 4, SERVER_THROTTLE_TICKS = 2;

	// S_ARM states
	public static final int STATE_NONE = 0;
	public static final int STATE_FORMING = 1;
	public static final int STATE_FORMED = 2;
	public static final int STATE_RESHAPING = 3;
	public static final int STATE_RETRACTING = 4;
	public static final int STATE_MELTING = 5;
	public static final int STATE_QUICKFORM = 6;

	// S_ARM reasons (why the state changed; they pick the animation and the message)
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
	public static final int WHY_RECALL = 13;
	public static final int WHY_UPGRADE = 14;
	/** Biomass crossed a third of the bar: the arm stepped down (or back up) a level. */
	public static final int WHY_BIOMASS = 15;

	// Timeline lengths, in ticks
	// (more than doubled on 2026-11-15: the arm and the tools changed too fast to see; the mod's geometry keeps
	// its own beat, ArmDraw.toolTicks)
	public static final int FORM_TICKS = 40;
	public static final int QUICKFORM_TICKS = 20;
	public static final int RESHAPE_TICKS = 32;
	public static final int RETRACT_TICKS = 30;
	public static final int MELT_TICKS = 40;

	// S_ARM_FX kinds
	/** A melee hit: ids = [victim]. */
	public static final int FX_HIT = 0;
	/** A block broken by the arm: positions = [block]. */
	public static final int FX_BREAK = 1;
	/** Draining Cut's swing: ids = the creatures hit, in order; arg = the arc in degrees; ticks = 6. */
	public static final int FX_SWIPE = 2;
	/** Stateful: the creatures latched now (Draining Cut); resent on change and every {@link #DRAIN_RESEND_TICKS}; an empty list ends it. */
	public static final int FX_DRAIN = 3;
	/** One healing pulse running home: ids = [creature]; ticks = how long it travels. */
	public static final int FX_PULSE = 4;
	/** Vein Rip: positions = the ores in break order; arg = ores broken per tick, times 10; ticks = the whole rip. */
	public static final int FX_VEIN = 5;
	/** Fell: positions top-down; arg = logs per tick. */
	public static final int FX_FELL = 6;
	/** Cleave: ids = [creature]. */
	public static final int FX_CLEAVE = 7;
	/** Burrow (stateful): ticks = the window left. */
	public static final int FX_BURROW = 8;
	/** Reap: positions = the crops. */
	public static final int FX_REAP = 9;
	/**
	 * A biomass bolt: ids = [arrow]; arg = the draw power in percent in bits 0-7 and, from servers at {@link #NEW_FORMS_SINCE}, the arrow's
	 * tip in bits 8-10 (0 bleed, 1 slowness, 2 poison, 3 fire, 4 glowing) and bit 11 for an arrow of the Triple Arrow fan. See BoltFx and the
	 * server's BowTips.
	 */
	public static final int FX_BOLT = 10;
	/** Harpoon Shot (stateful): ids = [arrow or creature]; positions = [block]. */
	public static final int FX_HARPOON = 11;
	/** The lantern's Flare: positions = [cell]; ticks = how long it burns. */
	public static final int FX_FLARE = 12;
	/**
	 * The old Lash special's crack (ids = the creature hit or none, positions = the end point, arg 1 creature / 2 block / 0
	 * miss). Servers from 7.6.0-beta.4 no longer fire it; it stays drawn so a new client on an older server still sees it.
	 */
	public static final int FX_WHIP = 13;
	/**
	 * One of the whip's moves (decided 2026-11-16): {@code arg} is the move ({@link #WHIP_STRIKE} ...), {@code level}
	 * the tier, {@code ticks} the move's visible duration, {@code origin} the whip's owner. ids: STRIKE [target], SNARE [target],
	 * DISARM [target, dropped item entity or -1 when it was destroyed], CRACK and WHIRL [creatures hit...]; positions: CRACK
	 * [the crack's far point along the look as a BlockPos long], the others none. Sent only to clients from
	 * {@link #WHIP_MOVES_SINCE}.
	 */
	public static final int FX_WHIP_MOVE = 14;
	/**
	 * The new forms' effects, one kind per form with {@code arg} choosing the move (phase 2 defines them, as the whip's); only to
	 * clients from {@link #NEW_FORMS_SINCE}. FX_ACCESSORY is the accessories' (shield, spyglass, flint and steel).
	 */
	public static final int FX_SPEAR = 15, FX_MACE = 16, FX_TRIDENT = 17, FX_CROSSBOW = 18, FX_CLAWS = 19, FX_ACCESSORY = 20;
	public static final int FX_KINDS = 21;
	/**
	 * FX_ACCESSORY's {@code arg} is {@code (place << 8) | move}: the accessory's place in {@link #ACCESSORIES} (lantern 0, shield 1, spyglass 2,
	 * flint 3) in bits 8-11 and its move in bits 0-7 (decided 2026-11-16; the server's copy is its ArmProtocol). {@code origin} is
	 * the owner and {@code ids} never carry the owner. The lantern sends none today (its state is all in S_ARM). Neither side may renumber.
	 */
	public static final int ACCESSORY_PLACE_LANTERN = 0, ACCESSORY_PLACE_SHIELD = 1, ACCESSORY_PLACE_SPYGLASS = 2, ACCESSORY_PLACE_FLINT = 3;
	public static final int ACCESSORY_PLACE_SHIFT = 8, ACCESSORY_PLACE_MASK = 0xF, ACCESSORY_MOVE_MASK = 0xFF;

	/** The first version on both sides with the whip's moves (FX_WHIP_MOVE). */
	public static final String WHIP_MOVES_SINCE = "7.6.0-beta.4";
	/** FX_WHIP_MOVE's {@code arg}: left-click lash, F on a creature (snare), F on a snared one (disarm), F on nothing (crack), Sneak+F (whirl). */
	public static final int WHIP_STRIKE = 1, WHIP_SNARE = 2, WHIP_DISARM = 3, WHIP_CRACK = 4, WHIP_WHIRL = 5;

	/** S_ARM_FX reaches players who draw arms this far from the origin; HIT and BREAK only {@link #FX_RADIUS_NEAR}. */
	public static final double FX_RADIUS = 96, FX_RADIUS_NEAR = 32;
	/** A client drops a stateful effect it hasn't heard of for this long (its owner went out of range, or the end was missed). */
	public static final int STATEFUL_TIMEOUT_TICKS = 60;
	public static final int DRAIN_RESEND_TICKS = 20;

	/** Vein Rip cracks each ore this many ticks ahead: 0.45 at two ticks before its break, 0.9 at one. */
	public static final int VEIN_CRACK_LEAD = 2;
	public static final float VEIN_CRACK_FIRST = 0.45f, VEIN_CRACK_SECOND = 0.9f;

	// C_ARM_COLOUR / S_ARM_COLOUR: each player's colour for the red parts of their arm (and the mod's other red accents)
	/**
	 * The colour everyone has until they choose another: the red the arm textures are painted in (morphskin_red.png's
	 * brightest texel, 0x69181A, at full brightness). A player in this colour is drawn with the shipped textures as they
	 * are; any other colour is the same textures recoloured (ArmTint).
	 */
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
	/** The first server whose lantern light tapers (the halo the client's coloured wash is drawn to match); before it the light was a flat circle. */
	public static final String LANTERN_TAPER_SINCE = "8.0.0-beta.1";
	/** The usual speed (100%) and the slowest and fastest a player may ask for (a quarter to three times). */
	public static final int PULSE_SPEED_DEFAULT = 100, PULSE_SPEED_MIN = 25, PULSE_SPEED_MAX = 300;

	/** {@code percent} kept to {@link #PULSE_SPEED_MIN} .. {@link #PULSE_SPEED_MAX}: a request out of range is the nearest allowed speed. */
	public static int clampPulseSpeed(int percent) {
		return Math.clamp(percent, PULSE_SPEED_MIN, PULSE_SPEED_MAX);
	}

	/** The multiplier a pulse speed in percent means (1 is the usual speed). */
	public static float pulseSpeedFactor(int percent) {
		return clampPulseSpeed(percent) / 100F;
	}

	/**
	 * The pulse speed slider's mapping (2026-11-16: "perhaps a slider for speed"): a position 0..1 as the speed
	 * multiplier, on a log scale so the usual speed sits near the middle and slow and fast get the same room, in steps of
	 * 0.05 so the saved number stays tidy and exactly 1.0 can be picked.
	 */
	public static float pulseSpeedFromSlider(double position) {
		double lo = Math.log(PULSE_SPEED_MIN / 100.0), hi = Math.log(PULSE_SPEED_MAX / 100.0);
		double speed = Math.exp(lo + Math.clamp(position, 0.0, 1.0) * (hi - lo));
		return pulseSpeedFactor(pulseSpeedPercent((float) (Math.round(speed * 20.0) / 20.0)));
	}

	/** {@link #pulseSpeedFromSlider}'s inverse: the slider position 0..1 of a speed multiplier. */
	public static double pulseSpeedToSlider(float factor) {
		double lo = Math.log(PULSE_SPEED_MIN / 100.0), hi = Math.log(PULSE_SPEED_MAX / 100.0);
		return (Math.log(pulseSpeedFactor(pulseSpeedPercent(factor))) - lo) / (hi - lo);
	}

	/**
	 * A speed as the multiplier the settings file keeps (1.0 usual) as the whole percent the wire carries, snapped to steps
	 * of 5% and kept in range; NaN is the usual speed. The snap is the slider's own step, so a speed typed with
	 * /morphclient pulsespeed or edited into the file is the one the slider shows (never a different value than is stored).
	 */
	public static int pulseSpeedPercent(float factor) {
		if (Float.isNaN(factor)) return PULSE_SPEED_DEFAULT;
		// clamp before snapping: a huge factor rounds to Integer.MAX_VALUE, which times 5 would wrap
		return clampPulseSpeed(Math.round(Math.clamp(factor, 0F, 10F) * 20F) * 5);
	}

	private ArmProtocol() {}

	/** Whether {@code rgb} is a colour C_ARM_COLOUR may carry: 0x000000 to 0xFFFFFF, nothing in the top byte. */
	public static boolean validColour(int rgb) {
		return (rgb & ~0xFFFFFF) == 0;
	}

	/** A look's name ("arm", "hand", "armour"; any case, spaces ignored) as its number, or the default for anything else. */
	public static int parseLook(@org.jspecify.annotations.Nullable String name) {
		if (name == null) return LOOK_DEFAULT;
		int i = LOOK_NAMES.indexOf(name.strip().toLowerCase(java.util.Locale.ROOT));
		return i < 0 ? LOOK_DEFAULT : i;
	}

	/** {@code rgb} as "#RRGGBB" (upper case), the form the settings file keeps. */
	public static String colourHex(int rgb) {
		return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	/**
	 * "#RRGGBB" or "RRGGBB" (either case, spaces around ignored) as a colour, or {@link #COLOUR_DEFAULT} for anything
	 * else (null, the wrong length, a stray letter), so a hand-edited settings file can never break the arm's colour.
	 */
	public static int parseColour(@org.jspecify.annotations.Nullable String text) {
		if (text == null) return COLOUR_DEFAULT;
		String s = text.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.length() != 6) return COLOUR_DEFAULT;
		int rgb = 0;
		for (int i = 0; i < 6; i++) {
			int digit = Character.digit(s.charAt(i), 16); // no signs: "-12345" is not a colour
			if (digit < 0) return COLOUR_DEFAULT;
			rgb = (rgb << 4) | digit;
		}
		return rgb;
	}

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
	 * {@code rateTenths / 10} ores a tick. The client's tendrils reach each ore on the tick the server breaks it.
	 */
	public static int veinBreakTick(int index, int rateTenths) {
		return 3 + index * 10 / Math.max(1, rateTenths);
	}

	/**
	 * The breaker id Vein Rip's cracks are sent under: unlike any real entity's, so they never replace the player's own
	 * digging crack, and one per owner, so two players' veins don't clear each other's. On the client it tells a vein's
	 * cracks from the owner really digging.
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

	// ------------------------------------------------------------------ the client's own

	/** The first string of an arm stack's {@code custom_model_data}; then the form id and the tier's name. */
	public static final String CMD_TAG = "morphreborn:arm";

	public static final String KATANA = "katana";
	public static final String PICKAXE = "pickaxe";
	public static final String AXE = "axe";
	public static final String SHOVEL = "shovel";
	public static final String SICKLE = "sickle";
	public static final String BOW = "bow";
	public static final String WHIP = "whip";
	/** The new tool forms (2026-11-16); hidden by the server until their special module exists. */
	public static final String SPEAR = "spear";
	public static final String MACE = "mace";
	public static final String TRIDENT = "trident";
	public static final String CROSSBOW = "crossbow";
	public static final String CLAWS = "claws";
	/** The accessories are not slot forms: they never become an item. */
	public static final String LANTERN = "lantern";
	public static final String SHIELD = "shield";
	public static final String SPYGLASS = "spyglass";
	public static final String FLINT = "flint";
	/** The slot forms in the server's (and the upgrade tree's) order; the accessories come after them. */
	public static final List<String> FORMS = List.of(KATANA, PICKAXE, AXE, SHOVEL, SICKLE, BOW, WHIP, SPEAR, MACE, TRIDENT, CROSSBOW, CLAWS);
	/**
	 * The Arsenal wheel's tool slots, clockwise from the top (the server's ArmForm.WHEEL: S_ARSENAL lists built forms in this
	 * order). The first seven are the wheel as it was; the five new ones follow and show only once the server has built them.
	 */
	public static final List<String> WHEEL = List.of(KATANA, AXE, BOW, WHIP, SICKLE, SHOVEL, PICKAXE, SPEAR, MACE, TRIDENT, CROSSBOW, CLAWS);
	/** The forms the wheel always shows a socket for: the wheel before the new forms (a new form with nothing built is not shown at all). */
	public static final List<String> CLASSIC_FORMS = List.of(KATANA, AXE, BOW, WHIP, SICKLE, SHOVEL, PICKAXE);
	/** The accessories, in the wheel's ring order (the lantern first, where it always sat; the rest follow it round). */
	public static final List<String> ACCESSORIES = List.of(LANTERN, SHIELD, SPYGLASS, FLINT);

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

	/** Whether {@code id} is an accessory this client knows. */
	public static boolean isAccessory(String id) {
		return ACCESSORIES.contains(id);
	}

	/** An accessory's place in {@link #ACCESSORIES} (its bit in a draw's accessory set), or -1 for an id this client doesn't know. */
	public static int accessoryIndex(String id) {
		return ACCESSORIES.indexOf(id);
	}

	/** Tiers (S_ARM's level, the third custom_model_data string). */
	public static final int TIER_IRON = 1, TIER_DIAMOND = 2, TIER_NETHERITE = 3;
	private static final String[] TIER_NAMES = {"", "iron", "diamond", "netherite"};

	/** The tier's name in the item's custom_model_data ("iron", "diamond", "netherite"), or "" for anything else. */
	public static String tierName(int tier) {
		return tier >= 1 && tier < TIER_NAMES.length ? TIER_NAMES[tier] : "";
	}

	/** The tier a name stands for (1 iron, 2 diamond, 3 netherite), or 0 when it is none of them. */
	public static int tier(String name) {
		if (name == null) return 0;
		for (int i = 1; i < TIER_NAMES.length; i++) {
			if (TIER_NAMES[i].equals(name)) return i;
		}
		return 0;
	}
}
