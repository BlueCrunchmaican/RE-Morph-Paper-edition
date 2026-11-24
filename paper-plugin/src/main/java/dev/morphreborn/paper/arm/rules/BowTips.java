package dev.morphreborn.paper.arm.rules;

import dev.morphreborn.paper.tips.Lines;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The bow's arrow tips and Triple Arrow, apart from the server so the rules and the wire numbers are tested (my design,
 * 2026-11-16, 8.0.0-beta.2: Triple Arrow replaced the Harpoon Shot; Sneak+F cycles the tip; Fire and Glowing come with the flint and
 * steel and the spyglass accessories).
 * <p>
 * <b>The tips.</b> Bleed (the default), Slowness and Poison are always there; Fire is there while the flint and steel accessory is on
 * and Glowing while the spyglass is on. The cycle goes through the tips on offer, in that order, and round again. A player's choice is
 * kept; one that is not on offer (they chose Fire, then put the flint out) acts as Bleed until it is again, without forgetting the choice.
 * <p>
 * <b>The wire.</b> Everything rides on S_ARM_FX FX_BOLT's {@code arg}, which only ever held the draw's power in whole percent; the low byte is
 * still exactly that, so a client that reads only the power (and ignores the rest) keeps working:
 * <ul>
 * <li>bits 0-7: the power, 0 to 100 percent;</li>
 * <li>bits 8-10: the tip ({@link Tip#id}), on every bolt message;</li>
 * <li>bit 11: this bolt is one of a Triple Arrow volley (bits 12-13 then say which: 0 the middle, 1 the left, 2 the right);</li>
 * <li>bit 14: ARMED, a message with no arrow ({@code ids} empty): Triple Arrow is ready for the next full draw for {@code ticks} more
 * ticks; {@code ticks} 0 says it is no longer (loosed, put away, run out or the arm retracted);</li>
 * <li>bit 15: TIP, a message with no arrow: the player's arrow tip is now the tip in bits 8-10 (sent on a change, and when a draw
 * starts, so every viewer knows what is nocked).</li>
 * </ul>
 */
public final class BowTips {
	private BowTips() {}

	/** An arrow tip: its wire id, its name, and the accessory that has to be on for it to be offered (null: always). */
	public enum Tip {
		BLEED(0, "Bleed", null, 0x8A0C14),
		SLOWNESS(1, "Slowness", null, 0x5A6C81),
		POISON(2, "Poison", null, 0x4E9331),
		FIRE(3, "Fire", "flint", 0xE25822),
		GLOWING(4, "Glowing", "spyglass", 0xFFFFA0);

		private final int id;
		private final String label;
		private final @Nullable String accessory;
		private final int rgb;

		Tip(int id, String label, @Nullable String accessory, int rgb) {
			this.id = id;
			this.label = label;
			this.accessory = accessory;
			this.rgb = rgb;
		}

		public int id() {
			return id;
		}

		public String label() {
			return label;
		}

		/** The accessory that must be on, or null. */
		public @Nullable String accessory() {
			return accessory;
		}

		/** The colour of the tipped-arrow swirl a plain client sees on the bolt. */
		public int rgb() {
			return rgb;
		}

		/** The tip with this wire id; Bleed for anything else. */
		public static Tip of(int id) {
			for (Tip t : values()) {
				if (t.id == id) return t;
			}
			return BLEED;
		}

		/** The tip a saved name says ("FIRE", any case); null when it is none. */
		public static @Nullable Tip byName(@Nullable String name) {
			if (name == null) return null;
			for (Tip t : values()) {
				if (t.name().equalsIgnoreCase(name.trim())) return t;
			}
			return null;
		}

		/** Whether it is on offer while {@code on} says which accessories are on. */
		public boolean offered(Predicate<String> on) {
			return accessory == null || on.test(accessory);
		}
	}

	/** The tips in cycle order. */
	public static final List<Tip> ORDER = List.of(Tip.values());

	/** The tip that acts: the chosen one when it is on offer, Bleed when it is not (or nothing was chosen). */
	public static Tip effective(@Nullable Tip chosen, Predicate<String> accessoryOn) {
		return chosen != null && chosen.offered(accessoryOn) ? chosen : Tip.BLEED;
	}

	/** The tip after {@code current} among those on offer, round to Bleed after the last; with nothing else on offer, the same. */
	public static Tip next(Tip current, Predicate<String> accessoryOn) {
		int n = ORDER.size();
		int from = ORDER.indexOf(current);
		for (int step = 1; step <= n; step++) {
			Tip t = ORDER.get((from + step) % n);
			if (t.offered(accessoryOn)) return t;
		}
		return current;
	}

	// ------------------------------------------------------------------ the wire

	public static final int POWER_MASK = 0xFF, TIP_SHIFT = 8, TIP_MASK = 0x7, FAN_FLAG = 1 << 11, FAN_SHIFT = 12, FAN_MASK = 0x3, ARMED_FLAG = 1 << 14, TIP_FLAG = 1 << 15;
	/** A volley's places: the middle bolt, the one to the left (counter-clockwise seen from above) and the one to the right. */
	public static final int FAN_MIDDLE = 0, FAN_LEFT = 1, FAN_RIGHT = 2;
	/** The only bolts that fire: three. */
	public static final int FAN_SIZE = 3;

	/** A bolt's FX_BOLT arg: the power in percent (0 to 100), the tip, and {@code fan} the volley place (-1 for a single bolt). */
	public static int boltArg(int powerPercent, Tip tip, int fan) {
		int arg = Math.clamp(powerPercent, 0, 100) | (tip.id() & TIP_MASK) << TIP_SHIFT;
		if (fan >= 0) arg |= FAN_FLAG | (fan & FAN_MASK) << FAN_SHIFT;
		return arg;
	}

	/** The ARMED message's arg (its {@code ticks} carries the time left, 0 for no longer armed). */
	public static int armedArg(Tip tip) {
		return ARMED_FLAG | (tip.id() & TIP_MASK) << TIP_SHIFT;
	}

	/** The TIP message's arg. */
	public static int tipArg(Tip tip) {
		return TIP_FLAG | (tip.id() & TIP_MASK) << TIP_SHIFT;
	}

	public static int power(int arg) {
		return arg & POWER_MASK;
	}

	public static Tip tip(int arg) {
		return Tip.of((arg >> TIP_SHIFT) & TIP_MASK);
	}

	public static boolean fanned(int arg) {
		return (arg & FAN_FLAG) != 0;
	}

	public static int fanPlace(int arg) {
		return (arg >> FAN_SHIFT) & FAN_MASK;
	}

	public static boolean armedMessage(int arg) {
		return (arg & ARMED_FLAG) != 0;
	}

	public static boolean tipMessage(int arg) {
		return (arg & TIP_FLAG) != 0;
	}

	// ------------------------------------------------------------------ the fan

	/** The yaw offset of a volley place, in degrees: positive to the player's right. The middle 0, the left minus the spread, the right plus it. */
	public static double fanOffset(int place, double spreadDegrees) {
		return place == FAN_LEFT ? -spreadDegrees : place == FAN_RIGHT ? spreadDegrees : 0;
	}

	/**
	 * A direction turned about the vertical by {@code degrees} (positive to the player's right, as the game's yaw grows), its slope kept: x and z
	 * turn, y stays. The length of the horizontal part is kept too, so a speed given as a vector length is the same after.
	 */
	public static double[] turn(double x, double y, double z, double degrees) {
		double r = Math.toRadians(degrees);
		double c = Math.cos(r), s = Math.sin(r);
		return new double[] {x * c - z * s, y, z * c + x * s};
	}

	// ------------------------------------------------------------------ the item's lore line

	/**
	 * The F line of the arm item's lore, after "F: ": "Triple Arrow 6 biomass · Sneak+F: arrow tip" (the cost only when the bar shows biomass
	 * and it is more than nothing).
	 */
	public static String loreLine(String name, long costMilli, boolean showCost) {
		return loreLines(name, costMilli, showCost).text(true);
	}

	/** {@link #loreLine} as typed pieces for the Tooltips setting: the name and the cost stay, the key for the arrow tip is explanation. */
	public static Lines loreLines(String name, long costMilli, boolean showCost) {
		String cost = showCost && costMilli > 0 ? " " + ArmItemSpecs.biomass(costMilli) + " biomass" : "";
		return Lines.builder().always(name + cost).tip("Sneak+F: arrow tip").build();
	}
}
