package dev.morphreborn.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.gui.SettingsLayout.Kind;
import dev.morphreborn.client.gui.SettingsLayout.Page;
import dev.morphreborn.client.gui.SettingsLayout.Section;
import dev.morphreborn.client.gui.SettingsLayout.Setting;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The client's settings pages (the 2026-11-24 reorganisation): every saved setting has a place, none is listed twice, every name, tooltip
 * and choice has its text, every name fits its button, each page's sections are tidy, and the two columns fit the narrowest window at every
 * GUI scale.
 */
class SettingsLayoutTest {
	private static JsonObject lang;

	@BeforeAll
	static void loadLang() throws IOException {
		try (InputStream in = SettingsLayoutTest.class.getResourceAsStream("/assets/morphreborn/lang/en_us.json")) {
			assertNotNull(in, "the English language file");
			lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String text(String key) {
		assertTrue(lang.has(key), "language key " + key);
		return lang.get(key).getAsString();
	}

	private static List<Setting> all() {
		List<Setting> out = new ArrayList<>();
		for (Page p : SettingsLayout.PAGES) out.addAll(p.settings());
		return out;
	}

	// ------------------------------------------------------------------ every setting has one place

	/**
	 * Saved settings with no widget, on purpose: hand-edited numbers (the selector's distance and size, the favourites radial's size, the
	 * tendril count and fade), the older names kept so an old file loads (acquisitionAnimation, dollOutline), and the one-time flag of the
	 * doll's pixel conversion. The commands write some of them too (/morphclient).
	 */
	private static final Set<String> FILE_ONLY = Set.of("selectorDistanceFromTop", "selectorScale", "radialScale", "acquisitionAnimation",
			"acquisitionTendrilMaxChild", "acquisitionTendrilPartOpacity", "dollScreenPixels", "dollOutline",
			// the colour is the arm colour page's, and has no row of its own
			"armColour");

	@Test
	void everySavedSettingHasAPlaceOrIsFileOnlyOnPurpose() {
		Set<String> reachable = new HashSet<>();
		for (Setting s : all()) if (s.field() != null) reachable.add(s.field());
		List<String> missing = new ArrayList<>();
		for (Field f : ClientConfig.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			if (!reachable.contains(f.getName()) && !FILE_ONLY.contains(f.getName())) missing.add(f.getName());
		}
		assertTrue(missing.isEmpty(), "settings no page reaches: " + missing);
		// and the other way: a page never names a setting that is not saved, and the file-only list holds only real fields
		Set<String> fields = new HashSet<>();
		for (Field f : ClientConfig.class.getFields()) fields.add(f.getName());
		for (String r : reachable) assertTrue(fields.contains(r), "a page changes " + r + ", which ClientConfig does not have");
		for (String f : FILE_ONLY) assertTrue(fields.contains(f), f + " is listed file-only but is not a setting");
		for (String f : FILE_ONLY) assertFalse(reachable.contains(f), f + " is both file-only and on a page");
	}

	@Test
	void theArmColourIsReachedFromItsButton() {
		assertTrue(all().stream().anyMatch(s -> s.id().equals("armColour.open") && s.kind() == Kind.PAGE));
	}

	@Test
	void noSettingIsListedTwice() {
		Set<String> ids = new HashSet<>();
		for (Setting s : all()) assertTrue(ids.add(s.id()), s.id() + " is on a page twice");
		// the only fields that several rows share are the doll's situations, one list in the file
		Set<String> fields = new HashSet<>();
		for (Setting s : all()) {
			if (s.field() == null || s.id().startsWith("dollWhen.")) continue;
			assertTrue(fields.add(s.field()), s.field() + " is changed by two rows");
		}
	}

	@Test
	void everyPageSettingHasAWidgetAndNoWidgetIsOrphaned() {
		Set<String> ids = new HashSet<>();
		for (Setting s : all()) {
			ids.add(s.id());
			assertTrue(SettingsWidgets.has(s.id()), s.id() + " has no widget");
		}
		// a maker for a setting no page lists would be dead code
		for (String id : makerIds()) assertTrue(ids.contains(id), id + " has a widget but is on no page");
	}

	/** The ids SettingsWidgets knows, by reading its map: it is private, and a test needs no other door. */
	@SuppressWarnings("unchecked")
	private static Set<String> makerIds() {
		try {
			Field f = SettingsWidgets.class.getDeclaredField("MAKERS");
			f.setAccessible(true);
			return new HashSet<>(((java.util.Map<String, ?>) f.get(null)).keySet());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void theDollsSituationsAreAllOnItsPage() {
		Set<String> ids = new HashSet<>();
		for (Setting s : SettingsLayout.DOLL.settings()) ids.add(s.id());
		for (String t : SettingsLayout.DOLL_TRIGGERS) assertTrue(ids.contains(SettingsLayout.triggerId(t)), t);
	}

	// ------------------------------------------------------------------ the text

	@Test
	void everyNameTooltipSectionAndChoiceHasItsText() {
		for (Page p : SettingsLayout.PAGES) {
			assertFalse(text(p.titleKey()).isBlank());
			for (Section sec : p.sections()) {
				assertFalse(text(p.sectionKey(sec)).isBlank());
				for (Setting s : sec.settings()) {
					assertFalse(text(s.nameKey()).isBlank(), s.id());
					assertTrue(text(s.tooltipKey()).length() >= 20, s.id() + " has a tooltip that explains");
					if (s.kind() == Kind.CHOICE) {
						assertTrue(s.values().size() >= 2, s.id());
						for (String v : s.values()) assertFalse(text(s.valueKey(v)).isBlank(), s.id() + " " + v);
					}
				}
			}
		}
		assertFalse(text("morphreborn.settings.animation.morph").isBlank());
		assertFalse(text("morphreborn.settings.animation.vanilla").isBlank());
	}

	@Test
	void noSectionKeyIsLeftOverInTheLanguageFile() {
		Set<String> used = new HashSet<>();
		for (Page p : SettingsLayout.PAGES) for (Section s : p.sections()) used.add(p.sectionKey(s));
		for (String key : lang.keySet()) {
			if (key.matches("morphreborn\\.settings\\.(main|arms|doll)\\.section\\.[A-Za-z]+")) assertTrue(used.contains(key), key + " is on no page");
		}
	}

	/** The advance of one character of the game's default font, in GUI pixels (glyph width plus the one pixel gap): narrow letters and marks, the rest 6. */
	private static int advance(char c) {
		return switch (c) {
			case 'i', '!', ':', ';', ',', '.', '|' -> 2;
			case 'l', '\'' -> 3;
			case 't', 'I', '[', ']', ' ', '*', '"', '(', ')', '<', '>', '{', '}' -> 4;
			case 'f', 'k' -> 5;
			case '@', '~' -> 7;
			default -> 6;
		};
	}

	private static int width(String s) {
		int w = 0;
		for (char c : s.toCharArray()) w += advance(c);
		return w;
	}

	/** The widest "Name: value" a setting's button can show, as the game would measure it (the estimate leans wide, never narrow). */
	private static int widest(Setting s) {
		String name = text(s.nameKey());
		return switch (s.kind()) {
			case PAGE -> width(name);
			case TOGGLE -> {
				String a = s.id().equals("transition") ? text("morphreborn.settings.animation.morph") : "On";
				String b = s.id().equals("transition") ? text("morphreborn.settings.animation.vanilla") : "Off";
				yield width(name + ": ") + Math.max(width(a), width(b));
			}
			case CHOICE -> {
				int w = 0;
				for (String v : s.values()) w = Math.max(w, width(text(s.valueKey(v))));
				yield width(name + ": ") + w;
			}
			case SLIDER -> width(name + ": ") + Math.max(width(s.widest()), s.id().equals("dollVignette") ? width(text("morphreborn.settings.dollVignette.off")) : 0);
		};
	}

	@Test
	void everyNameFitsItsButtonWithoutScrolling() {
		List<String> tooWide = new ArrayList<>();
		for (Setting s : all()) {
			int w = widest(s);
			if (w > SettingsLayout.TEXT_ROOM) tooWide.add(s.id() + " " + w + " px of " + SettingsLayout.TEXT_ROOM);
		}
		assertTrue(tooWide.isEmpty(), "names too long for a 150 px button: " + tooWide);
	}

	@Test
	void sectionTitlesAreShortAndFitTheListsWidth() {
		for (Page p : SettingsLayout.PAGES) {
			for (Section s : p.sections()) assertTrue(width(text(p.sectionKey(s))) <= 2 * SettingsLayout.COLUMN_W, p.id() + "/" + s.key());
			assertTrue(width(text(p.titleKey())) <= 200, p.id());
		}
	}

	// ------------------------------------------------------------------ tidy sections

	@Test
	void sectionsAreNotEmptyNorCrammed() {
		for (Page p : SettingsLayout.PAGES) {
			assertFalse(p.sections().isEmpty(), p.id());
			Set<String> keys = new HashSet<>();
			for (Section s : p.sections()) {
				assertTrue(keys.add(s.key()), p.id() + " has the section " + s.key() + " twice");
				assertFalse(s.settings().isEmpty(), p.id() + "/" + s.key());
				// a section of the doll's situations is the one long list; every other has a handful of rows
				if (!s.key().equals("showWhen")) assertTrue(s.rows() <= 4, p.id() + "/" + s.key() + " has " + s.rows() + " rows");
			}
		}
	}

	@Test
	void theMainPageStartsWithTooltipsAndGoesDeepestLast() {
		Page main = SettingsLayout.MAIN;
		assertEquals("tooltips", main.sections().get(0).settings().get(0).id(), "Tooltips first, so the way back is never hidden");
		// every other page is reached from the main one
		Set<String> opened = new HashSet<>();
		for (Setting s : main.settings()) if (s.kind() == Kind.PAGE) opened.add(s.id());
		assertTrue(opened.contains("doll.open") && opened.contains("arms.open"));
		// and the arm colour from the arms page
		assertTrue(SettingsLayout.ARMS.settings().stream().anyMatch(s -> s.id().equals("armColour.open")));
	}

	@Test
	void emptyCellsAreFewAndOnlyAtTheEndOfASection() {
		// a section of an odd count leaves its last cell empty; that is the only gap there may be, and there are only a few
		int blanks = 0;
		for (Page p : SettingsLayout.PAGES) {
			for (Section s : p.sections()) if (s.settings().size() % 2 == 1) blanks++;
		}
		assertTrue(blanks <= 3, "odd sections: " + blanks);
	}

	// ------------------------------------------------------------------ the two columns

	/** The scaled widths the game keeps a screen at: 320 and up (the least vanilla's GUI scale allows) to a 4K screen at scale 1. */
	private static final int[] WIDTHS = {320, 427, 480, 640, 854, 960, 1280, 1920, 3840};

	@Test
	void theColumnsDoNotOverlapAndStayOnTheScreenAtEveryWidth() {
		int[][] windows = {{854, 480}, {960, 520}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}};
		List<Integer> widths = new ArrayList<>();
		for (int w : WIDTHS) widths.add(w);
		for (int[] win : windows) {
			for (int setting : new int[] {0, 1, 2, 3, 4, 5, 6}) {
				int scale = UiLayout.guiScale(win[0], win[1], setting);
				widths.add(UiLayout.scaled(win[0], scale));
			}
		}
		for (int w : widths) {
			UiLayout.Rect left = new UiLayout.Rect(SettingsLayout.columnX(w, 0), 0, SettingsLayout.COLUMN_W, 20);
			UiLayout.Rect right = new UiLayout.Rect(SettingsLayout.columnX(w, 1), 0, SettingsLayout.COLUMN_W, 20);
			assertFalse(left.overlaps(right), "overlap at " + w);
			assertTrue(left.x() >= 0 && right.right() <= w, "on screen at " + w);
			assertEquals(w / 2F, (left.x() + right.right()) / 2F, 1F, "centred at " + w);
			assertEquals(SettingsLayout.COLUMN_GAP, right.x() - left.right(), 1e-3F, "the gap at " + w);
		}
	}

	@Test
	void theLayoutHoldsTheGapsVanillasOptionsListUses() {
		// vanilla puts the right column 160 px right of the left (its X_OFFSET) and every small widget 150 wide
		assertEquals(160, SettingsLayout.columnX(400, 1) - SettingsLayout.columnX(400, 0));
		assertEquals(150, SettingsLayout.COLUMN_W);
		assertEquals(400 / 2 - 155, SettingsLayout.columnX(400, 0));
	}

	@Test
	void everyIdIsLowerCamelCaseWithAtMostOneDotSoItsKeyIsPredictable() {
		for (Setting s : all()) assertTrue(s.id().matches("[a-z][A-Za-z]*(\\.[a-z][A-Za-z_]*)?"), s.id());
	}
}
