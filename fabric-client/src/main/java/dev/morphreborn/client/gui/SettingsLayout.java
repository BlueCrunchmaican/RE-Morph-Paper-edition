package dev.morphreborn.client.gui;

import dev.morphreborn.client.arm.ArmProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the client's settings pages hold and in which order: the main page, the Arm Forms page and the Paper Doll page, each a few
 * titled sections of settings that go two to a row. Only data and arithmetic, apart from any widget, so a unit test can check that every
 * saved setting has a place, that nothing is listed twice, that every name and tooltip exists and fits its button, and that the two columns
 * fit the narrowest window (SettingsLayoutTest). The screens build their widgets from this (SettingsWidgets), so a setting added here
 * and nowhere else shows up in the right section.
 * <p>
 * Order: the settings people change most come first on a page, the ones that look after themselves last. Each setting's name is the
 * language key {@code morphreborn.settings.<id>} and its hover text the same with {@code .tooltip}; a choice's values are
 * {@code morphreborn.settings.<id>.<value in lower case>}.
 */
public final class SettingsLayout {
	/** How a setting is changed. */
	public enum Kind {
		/** On or off (the transition setting reads Morph or Plain instead). */
		TOGGLE,
		/** One of a few named values. */
		CHOICE,
		/** A slider with a number in its label. */
		SLIDER,
		/** A button that opens another page. */
		PAGE
	}

	/**
	 * One setting. {@code field} is the {@code ClientConfig} field it changes (null for a button); {@code values} are a choice's values;
	 * {@code widest} is the longest value text a slider can show, for the fit test.
	 */
	public record Setting(String id, Kind kind, String field, List<String> values, String widest) {
		public String nameKey() {
			return "morphreborn.settings." + id;
		}

		public String tooltipKey() {
			return nameKey() + ".tooltip";
		}

		public String valueKey(String value) {
			return nameKey() + "." + value.toLowerCase(Locale.ROOT);
		}
	}

	/** A titled group of settings; they run two to a row, left to right. */
	public record Section(String key, List<Setting> settings) {
		/** How many rows the section takes. */
		public int rows() {
			return (settings.size() + 1) / 2;
		}
	}

	/** A page: its language key and its sections, in order. {@code id} names the section keys: {@code morphreborn.settings.<id>.section.<key>}. */
	public record Page(String id, String titleKey, List<Section> sections) {
		public String sectionKey(Section s) {
			return "morphreborn.settings." + id + ".section." + s.key();
		}

		/** Every setting of the page, in order. */
		public List<Setting> settings() {
			List<Setting> out = new ArrayList<>();
			for (Section s : sections) out.addAll(s.settings());
			return out;
		}
	}

	/** Each widget is this wide, and the two columns are this far apart: vanilla's options list, which the pages are laid out in. */
	public static final int COLUMN_W = 150, COLUMN_GAP = 10;
	/** The room a button's text has: the button less the margin vanilla keeps either side (longer text scrolls, which is hard to read). */
	public static final int TEXT_ROOM = COLUMN_W - 4;

	/** The left edge of column {@code col} (0 or 1) on a screen {@code guiW} wide. */
	public static int columnX(int guiW, int col) {
		return guiW / 2 - COLUMN_W - COLUMN_GAP / 2 + col * (COLUMN_W + COLUMN_GAP);
	}

	/** The situations that show the paper doll in "While Active" mode, in settings order: the player's own movement and actions, then the morph-specific ones. */
	public static final List<String> DOLL_TRIGGERS = List.of("WALKING", "CROUCHING", "SPRINTING", "AIRBORNE", "ELYTRA", "SWIMMING",
			"FLYING", "CLIMBING", "RIDING", "BLOCKING", "USING_ITEM", "SWINGING", "HURT", "ON_FIRE", "POWDER_SNOW", "SLEEPING", "TRANSFORMING", "ABILITY");

	private static final List<String> ABSORB_STYLES = List.of("TENDRILS", "RED_TENDRILS", "WISPS", "PLAIN", "OFF");
	private static final List<String> METER_MODES = List.of("CHANGES", "ALWAYS", "OFF");

	// ---------------------------------------------------------------- the main page

	/**
	 * The main page: the two pages it opens come right under the general switches, then what morphing looks like, the selector, and what is
	 * drawn on the screen. Tooltips is the first setting of all: its own hover text is always there, so the way back is never hidden.
	 */
	public static final Page MAIN = new Page("main", "morphreborn.settings.title", List.of(
			new Section("general", List.of(toggle("tooltips", "tooltips"), toggle("updateCheck", "updateCheck"))),
			new Section("pages", List.of(page("doll.open"), page("arms.open"))),
			new Section("morphing", List.of(choice("absorbMorph", "absorbMorph", ABSORB_STYLES), choice("absorbBiomass", "absorbBiomass", ABSORB_STYLES),
					toggle("transition", "transitionAnimation"), toggle("nameTags", "showNameTags"))),
			new Section("selector", List.of(toggle("mouse", "selectorAllowMouseControl"), toggle("invertScroll", "invertScroll"))),
			new Section("onScreen", List.of(choice("biomassBar", "biomassBar", METER_MODES), choice("frenzyMeter", "frenzyMeter", METER_MODES),
					toggle("unlockToasts", "unlockToasts")))));

	// ---------------------------------------------------------------- the Arm Forms page

	/**
	 * Arm Forms: first what your own arm is like, which everyone with the mod sees (look, colour, pulse speed, lantern brightness), then the
	 * hotbar bar and the wheel you use all the time, and last how arms and the lantern are drawn on your screen (detail, glow, fizz, drips,
	 * the vein outline, the lantern's lights).
	 */
	public static final Page ARMS = new Page("arms", "morphreborn.settings.arms.title", List.of(
			new Section("yours", List.of(choice("armLook", "armLook", ArmProtocol.LOOK_NAMES), page("armColour.open"), slider("armPulseSpeed", "armPulseSpeed", "3.00x"),
					choice("lanternBrightness", "lanternBrightness", List.of("1", "2", "3", "4", "5")))),
			new Section("hotbar", List.of(choice("armBar", "armBar", List.of("GOO", "VANILLA")), slider("armWheelScale", "armWheelScale", "100%"))),
			new Section("effects", List.of(choice("armDetail", "armDetail", List.of("HIGH", "MEDIUM", "LOW")), toggle("armGlow", "armGlow"),
					choice("armDissolve", "armDissolve", List.of("AUTO", "ON", "OFF")), toggle("armDrips", "armDrips"),
					toggle("armSenseOutline", "armSenseOutline"), toggle("lanternDynamicLight", "lanternDynamicLight"),
					toggle("lanternMotes", "lanternMotes"), toggle("lanternGlow", "lanternGlow")))));

	// ---------------------------------------------------------------- the Paper Doll page

	/** The Paper Doll page: on or off and when, where it sits, how it looks and is lit, when it steps aside, and the situations that show it. */
	public static final Page DOLL = new Page("doll", "morphreborn.settings.doll.title", List.of(
			new Section("doll", List.of(toggle("doll", "doll"), choice("dollShow", "dollShow", List.of("ALWAYS", "MORPHED", "ACTIVE")),
					toggle("dollVehicle", "dollVehicle"), slider("dollLinger", "dollLinger", "5.0 s"))),
			new Section("placement", List.of(choice("dollCorner", "dollCorner", List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT")),
					slider("dollSize", "dollSize", "800 px"), slider("dollX", "dollX", "1600 px"), slider("dollY", "dollY", "1200 px"))),
			new Section("look", List.of(slider("dollAngle", "dollAngle", "-180°"), slider("dollTilt", "dollTilt", "-60°"),
					choice("dollHead", "dollHead", List.of("FOLLOW", "TURN", "LOOK", "FORWARD", "AT_VIEWER")), choice("dollScale", "dollScale", List.of("TRUE_SIZE", "FILL")),
					toggle("dollLockElytra", "dollLockElytra"), toggle("dollLockSpin", "dollLockSpin"))),
			new Section("style", List.of(choice("dollLight", "dollLight", List.of("BRIGHT", "WORLD")), slider("dollOpacity", "dollOpacity", "100%"),
					slider("dollVignette", "dollVignette", "32 px"), slider("dollOutlineStrength", "dollOutlineStrength", "100%"))),
			new Section("hide", List.of(toggle("dollInMenus", "dollInMenus"), toggle("dollHideThirdPerson", "dollHideThirdPerson"),
					toggle("dollHideDebug", "dollHideDebug"), toggle("dollHideMusic", "dollHideMusic"))),
			new Section("showWhen", triggers())));

	/** Every page, the main one first. */
	public static final List<Page> PAGES = List.of(MAIN, ARMS, DOLL);

	/** The id of a "While Active" situation's setting ({@code dollWhen.walking}). */
	public static String triggerId(String trigger) {
		return "dollWhen." + trigger.toLowerCase(Locale.ROOT);
	}

	private static List<Setting> triggers() {
		List<Setting> out = new ArrayList<>();
		for (String t : DOLL_TRIGGERS) out.add(new Setting(triggerId(t), Kind.TOGGLE, "dollShowWhen", List.of(), ""));
		return List.copyOf(out);
	}

	private static Setting toggle(String id, String field) {
		return new Setting(id, Kind.TOGGLE, field, List.of(), "");
	}

	private static Setting choice(String id, String field, List<String> values) {
		return new Setting(id, Kind.CHOICE, field, List.copyOf(values), "");
	}

	private static Setting slider(String id, String field, String widest) {
		return new Setting(id, Kind.SLIDER, field, List.of(), widest);
	}

	private static Setting page(String id) {
		return new Setting(id, Kind.PAGE, null, List.of(), "");
	}

	private SettingsLayout() {}
}
