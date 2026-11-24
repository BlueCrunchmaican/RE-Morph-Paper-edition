package dev.morphreborn.paper.survival;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Where each upgrade sits in the chest menu ({@link SurvivalMenu}), worked out from the ids and their parents alone so it
 * is tested without a server. The 54 slots, laid out like the client mod's tree:
 * <pre>
 *  0  1  2  3 [St] 5  6 [fr][Cl]     St status, Cl close, fr the Morph Ability's frame
 * [Bi][b ][b ][b ][b ][b ][fr][MA][fr]
 * [Mo][m ][m ][m ][m ][m ][m ][fr]26
 * [Ab][a ][a ][a ][a ][a ][a ][a ]35
 * [Ab][a ][a ][a ][a ][a ][a ][a ]44
 * [AS][Ar][Pa]                      [To]   Arm Shaping, then the doors to the Arsenal page and the Passives page; To the Tooltips switch (53, on every page)
 * </pre>
 * The Biomass, Morph and Abilities rows fill in survival.yml order. Arm Shaping sits in a fixed slot, and the Arsenal is a page
 * of its own (2026-11-16: twelve tools and four accessories do not fit a row of eight):
 * <pre>
 * [Bk][  ][  ][  ][St][  ][  ][  ][Cl]     Bk back to the upgrades, St status, Cl close
 * [To][ka][pi][ax][sh][si][bo][wh]  9..16   Tools: katana, pickaxe, axe, shovel, sickle, bow, whip
 * [  ][sp][ma][tr][cr][cl]          18..23  then spear, mace, trident, crossbow, claws
 * [Ac][la][sh][sg][fl]              27..31  Accessories: lantern, shield, spyglass, flint and steel
 * </pre>
 * Each Arsenal form has a fixed slot by id, so one missing from this build (not offered, so not passed in) leaves its slot
 * empty rather than shifting the others along. The main page's door (slot 46) opens the Arsenal page; Back (slot 0 there) returns.
 * <p>
 * The eight biomass passives are a third page, the Passives page (2026-11-16: passives get toggles in the upgrades menu;
 * they used to sit in stand-in slots on the main page, which are filler again). Each passive is in a column of three:
 * <pre>
 * [Bk][  ][  ][  ][St][  ][  ][  ][Cl]
 * [Pa][su][ne][gi][fs][an][ts][sc][bs]  9..17   the passives (sustenance, night eyes, gills, fire shedding, antitoxin,
 * [Sw][  ][  ][  ][  ][  ][  ][  ][  ]  18..26  tendril sense, scavenger, borrowed scent); under each its On/Off pane
 * [Lv][  ][  ][  ][  ][  ][  ][  ][  ]  27..35  and under that its buy button (the next level)
 * </pre>
 * A click on a passive toggles it once bought (left click, the one a touch or Bedrock player has), right or shift click buys its
 * next level; the buy button under each does that too, so buying never needs a right click. The door is slot 47.
 * <p>
 * The menu holds one row of six Morph upgrades and all six are used, which is why the Arsenal has a page of its own and
 * Close moved up from the bottom row (49) to 8. An upgrade with no room gets no slot; the menu warns about it, since a
 * player without the client mod can then only buy it with /morph upgrade.
 */
public final class SurvivalMenuLayout {
	public static final int SIZE = 54;
	public static final int STATUS_SLOT = 4;
	public static final int CLOSE_SLOT = 8;
	/** The Tooltips switch, bottom right corner of every page (a button for players without the client mod, 2026-11-17; no page uses it). */
	public static final int TOOLTIPS_SLOT = 53;
	public static final int MORPH_ABILITY_SLOT = 16;
	public static final int ARM_SHAPING_SLOT = 45;
	/** The main page's door to the Arsenal page, beside Arm Shaping. */
	public static final int ARSENAL_DOOR_SLOT = 46;
	/** The main page's door to the Passives page, beside the Arsenal's. */
	public static final int PASSIVES_DOOR_SLOT = 47;
	/** The Arsenal page's way back to the main page, and its two labels: the tools and the accessories. */
	public static final int ARSENAL_BACK_SLOT = 0, TOOLS_LABEL = 9, ACCESSORIES_LABEL = 27;
	/** The Passives page's way back (the same slot as the Arsenal's) and its three row labels: the passives, their switches, their buy buttons. */
	public static final int PASSIVES_BACK_SLOT = 0, PASSIVES_LABEL = 9, SWITCH_LABEL = 18, BUY_LABEL = 27;
	/** The pages of the menu. */
	public enum Page { MAIN, ARSENAL, PASSIVES }
	/** The Morph Ability's frame. */
	static final int[] FRAME = {7, 15, 17, 25};
	/** The section labels: Biomass, Morph, and Abilities twice (one per row). */
	static final int BIOMASS_LABEL = 9, MORPH_LABEL = 18, ABILITIES_LABEL = 27, ABILITIES_LABEL_2 = 36;
	static final int[] BIOMASS_ROW = {10, 11, 12, 13, 14};
	static final int[] MORPH_ROW = {19, 20, 21, 22, 23, 24};
	static final int[] ABILITY_ROWS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
	/** The Passives page's slots by upgrade id: the eight in a row, in the order the command lists them. */
	public static final Map<String, Integer> PASSIVE_SLOTS = passiveSlots();
	/**
	 * The Arsenal page's slots by upgrade id: the first seven tools in the chest order of old, then the five new ones on the
	 * next row, then the accessories (lantern first, as in the wheel's inner ring) on their own row.
	 */
	public static final Map<String, Integer> ARSENAL_SLOTS = arsenalSlots();

	private static Map<String, Integer> arsenalSlots() {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put(SurvivalConfig.ARM_KATANA, 10);
		m.put(SurvivalConfig.ARM_PICKAXE, 11);
		m.put(SurvivalConfig.ARM_AXE, 12);
		m.put(SurvivalConfig.ARM_SHOVEL, 13);
		m.put(SurvivalConfig.ARM_SICKLE, 14);
		m.put(SurvivalConfig.ARM_BOW, 15);
		m.put(SurvivalConfig.ARM_WHIP, 16);
		m.put(SurvivalConfig.ARM_SPEAR, 19);
		m.put(SurvivalConfig.ARM_MACE, 20);
		m.put(SurvivalConfig.ARM_TRIDENT, 21);
		m.put(SurvivalConfig.ARM_CROSSBOW, 22);
		m.put(SurvivalConfig.ARM_CLAWS, 23);
		m.put(SurvivalConfig.ARM_LANTERN, 28);
		m.put(SurvivalConfig.ARM_SHIELD, 29);
		m.put(SurvivalConfig.ARM_SPYGLASS, 30);
		m.put(SurvivalConfig.ARM_FLINT, 31);
		return Collections.unmodifiableMap(m);
	}

	private static Map<String, Integer> passiveSlots() {
		Map<String, Integer> m = new LinkedHashMap<>();
		int slot = 10;
		for (String id : PassiveSettings.ALL) m.put(id, slot++);
		return Collections.unmodifiableMap(m);
	}

	private SurvivalMenuLayout() {}

	/** The three things a click on the Passives page can land on, by where it is: the passive, its switch, its buy button. */
	public enum PassiveRole { ITEM, SWITCH, BUY }

	/** What a click on the Passives page does. */
	public enum PassiveAction { NONE, TOGGLE, BUY }

	/** The pane under a passive's item that switches it on and off. */
	public static int switchSlot(int itemSlot) {
		return itemSlot + 9;
	}

	/** The button under a passive's switch that buys its next level. */
	public static int buySlot(int itemSlot) {
		return itemSlot + 18;
	}

	/** What the Passives-page slot is, or null for one that holds neither. */
	public static @Nullable PassiveRole passiveRole(int slot) {
		int first = 10, count = PassiveSettings.ALL.size();
		if (slot >= first && slot < first + count) return PassiveRole.ITEM;
		if (slot >= switchSlot(first) && slot < switchSlot(first) + count) return PassiveRole.SWITCH;
		if (slot >= buySlot(first) && slot < buySlot(first) + count) return PassiveRole.BUY;
		return null;
	}

	/** The slot of the passive whose column {@code slot} (an item, switch or buy slot) is in. */
	public static int passiveItemSlot(int slot) {
		PassiveRole role = passiveRole(slot);
		return role == PassiveRole.SWITCH ? slot - 9 : role == PassiveRole.BUY ? slot - 18 : slot;
	}

	/**
	 * What a click does: on the passive, a left click switches a bought one (and buys one not bought yet), a right or shift click
	 * ({@code alt}) buys the next level; on the switch pane, a bought passive is switched and one not bought is left alone; on the
	 * buy button, always a buy (which says so when the passive is already fully upgraded). Left click only is all a Bedrock player has.
	 */
	public static PassiveAction passiveAction(PassiveRole role, boolean owned, boolean alt) {
		return switch (role) {
			case ITEM -> alt || !owned ? PassiveAction.BUY : PassiveAction.TOGGLE;
			case SWITCH -> owned ? PassiveAction.TOGGLE : PassiveAction.NONE;
			case BUY -> PassiveAction.BUY;
		};
	}

	/**
	 * True when {@code id} has its own place on another page (an Arsenal form, a passive), so the main page leaving it out is
	 * no news. One with no place anywhere (an admin's own upgrade under Arm Shaping) is still warned about.
	 */
	public static boolean onOtherPage(String id, Function<String, @Nullable String> parentOf) {
		return switch (section(id, parentOf)) {
			case ARSENAL -> ARSENAL_SLOTS.containsKey(id);
			case PASSIVES -> PASSIVE_SLOTS.containsKey(id);
			default -> false;
		};
	}

	/** Which part of the tree an upgrade is in, by its ancestry. The client's tree screen sorts nodes by the same rule. */
	public enum Section { BIOMASS, MORPH_ABILITY, MORPH, ABILITIES, ARM_SHAPING, ARSENAL, PASSIVES }

	/**
	 * The section of {@code id}: Morph Ability and Arm Shaping are their own; then the nearest of Morph Abilities
	 * (Abilities) and Arm Shaping (the Arsenal) up the parent chain; then anything under Morph Ability is Morph, and the
	 * rest is Biomass. Arm Shaping is tested before Morph Ability on purpose: it hangs off Morph Ability, so the plain
	 * walk would file it and every form under Morph.
	 *
	 * @param parentOf an upgrade's parent ("root" for none), or null for an id that isn't in the tree
	 */
	public static Section section(String id, Function<String, @Nullable String> parentOf) {
		if (id.equals(SurvivalConfig.MORPH_ABILITY)) return Section.MORPH_ABILITY;
		if (id.equals(SurvivalConfig.ARM_SHAPING)) return Section.ARM_SHAPING;
		if (id.startsWith("passive_")) return Section.PASSIVES;
		boolean underMorph = false;
		String parent = parentOf.apply(id);
		for (int guard = 0; guard < 64 && parent != null && !parent.equals("root"); guard++) {
			if (parent.equals(SurvivalConfig.MORPH_ABILITIES)) return Section.ABILITIES;
			if (parent.equals(SurvivalConfig.ARM_SHAPING)) return Section.ARSENAL;
			if (parent.equals(SurvivalConfig.MORPH_ABILITY)) underMorph = true;
			parent = parentOf.apply(parent);
		}
		return underMorph ? Section.MORPH : Section.BIOMASS;
	}

	/**
	 * A slot on the main page for each upgrade that has room, in the order given (survival.yml's, so the rows fill as the file
	 * reads). The Arsenal's forms and the passives are not here: they are on pages of their own ({@link #assignArsenal},
	 * {@link #assignPassives}). Ids left out of the result have no slot on this page: a full row, an upgrade of another page, or
	 * a slot already taken.
	 */
	public static Map<String, Integer> assign(List<String> ids, Function<String, @Nullable String> parentOf) {
		Map<String, Integer> out = new LinkedHashMap<>();
		Set<Integer> used = new HashSet<>();
		int b = 0, m = 0, a = 0;
		for (String id : ids) {
			int slot = switch (section(id, parentOf)) {
				case MORPH_ABILITY -> MORPH_ABILITY_SLOT;
				case ARM_SHAPING -> ARM_SHAPING_SLOT;
				case ARSENAL, PASSIVES -> -1;
				case BIOMASS -> b < BIOMASS_ROW.length ? BIOMASS_ROW[b++] : -1;
				case MORPH -> m < MORPH_ROW.length ? MORPH_ROW[m++] : -1;
				case ABILITIES -> a < ABILITY_ROWS.length ? ABILITY_ROWS[a++] : -1;
			};
			if (slot < 0 || !used.add(slot)) continue;
			out.put(id, slot);
		}
		return out;
	}

	/**
	 * A slot on the Passives page for each passive in {@code ids} that has one: by id ({@link #PASSIVE_SLOTS}). An admin's own
	 * {@code passive_} upgrade has none (nothing here would run it), so no room: the menu warns about it.
	 */
	public static Map<String, Integer> assignPassives(List<String> ids, Function<String, @Nullable String> parentOf) {
		Map<String, Integer> out = new LinkedHashMap<>();
		Set<Integer> used = new HashSet<>();
		for (String id : ids) {
			if (section(id, parentOf) != Section.PASSIVES) continue;
			int slot = PASSIVE_SLOTS.getOrDefault(id, -1);
			if (slot < 0 || !used.add(slot)) continue;
			out.put(id, slot);
		}
		return out;
	}

	/**
	 * A slot on the Arsenal page for each Arsenal upgrade in {@code ids} that has one: by id ({@link #ARSENAL_SLOTS}). Anything
	 * else under Arm Shaping, an admin's own upgrade, has none and so no room: the menu warns about it.
	 */
	public static Map<String, Integer> assignArsenal(List<String> ids, Function<String, @Nullable String> parentOf) {
		Map<String, Integer> out = new LinkedHashMap<>();
		Set<Integer> used = new HashSet<>();
		for (String id : ids) {
			if (section(id, parentOf) != Section.ARSENAL) continue;
			int slot = ARSENAL_SLOTS.getOrDefault(id, -1);
			if (slot < 0 || !used.add(slot)) continue;
			out.put(id, slot);
		}
		return out;
	}
}
