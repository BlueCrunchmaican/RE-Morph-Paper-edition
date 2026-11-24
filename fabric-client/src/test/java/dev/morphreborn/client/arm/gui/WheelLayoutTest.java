package dev.morphreborn.client.arm.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.ArmArsenal;
import dev.morphreborn.client.arm.ArmProtocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Arsenal wheel's layout with twelve tools and four accessories (2026-11-16): the slots' icons and labels keep
 * clear of each other at every slot count up to twelve and every wheel size, the accessories sit evenly round the inner ring with
 * the lantern where it always was, nothing in the ring touches the middle's lump, and the five new tools stay out of the wheel until
 * the server builds them. Boxes use the same arithmetic the drawing does (WheelLayout, and the wheel's own constants), with
 * generous text widths.
 */
class WheelLayoutTest {
	/** The original wheel's radius in GUI px (ts = 1), and what the drawing puts under a slot's icon. */
	private static final float R1 = 96.375F;
	private static final float LINE = 9F;

	/** One slot's icon, name and pips as a box (x0, y0, x1, y1) at wheel text scale {@code ts}, for slot {@code i} of {@code n}. */
	private static float[] stack(int i, int n, float ts) {
		float r = R1 * ts;
		float a = WheelLayout.slotAngle(i, n);
		float ex = (float) Math.sin(a) * r * WheelLayout.SLOT_RADIUS;
		float ey = (float) -Math.cos(a) * r * WheelLayout.SLOT_RADIUS;
		float size = 16F * WheelLayout.iconScale(n, ts);
		float iy = ey - 5F * Math.min(1F, ts);
		float label = 0.5F * ts;
		float nameW = 8 * 6F * label; // "Crossbow" at most
		float pipsW = 3 * 7F * label;
		float halfW = Math.max(size, Math.max(nameW, pipsW)) / 2F;
		float top = iy - size / 2F;
		float bottom = iy + size / 2F + 1F + LINE * label + 1F + 6F * label;
		return new float[] {ex - halfW, top, ex + halfW, bottom};
	}

	private static boolean overlap(float[] a, float[] b) {
		return a[0] < b[2] - 0.01F && b[0] < a[2] - 0.01F && a[1] < b[3] - 0.01F && b[1] < a[3] - 0.01F;
	}

	@Test
	void toolSlotsKeepClearOfEachOtherUpToTwelve() {
		for (int n = 1; n <= 12; n++) {
			for (float ts : new float[] {0.5F, 0.8F, 1F, 1.3F, 2F}) {
				List<float[]> boxes = new ArrayList<>();
				for (int i = 0; i < n; i++) boxes.add(stack(i, n, ts));
				for (int i = 0; i < n; i++) {
					for (int j = i + 1; j < n; j++) {
						assertFalse(overlap(boxes.get(i), boxes.get(j)), n + " slots at ts " + ts + ": slot " + i + " and " + j + " overlap");
					}
				}
			}
		}
	}

	@Test
	void iconsShrinkOnlyPastEightSlots() {
		for (int n = 1; n <= WheelLayout.FULL_SIZE_SLOTS; n++) assertEquals(1F, WheelLayout.iconFit(n), 0F, "n " + n);
		assertTrue(WheelLayout.iconFit(9) < 1F);
		assertTrue(WheelLayout.iconFit(12) < WheelLayout.iconFit(9));
		// twelve icons are still 22 px wide: not so small they stop reading
		assertEquals(22.4F, 16F * WheelLayout.iconScale(12, 1F), 0.1F);
		// the wheel as it was (seven slots) keeps its 32 px icons
		assertEquals(32F, 16F * WheelLayout.iconScale(7, 1F), 1e-4F);
		// a bigger wheel does not make the icons bigger (they are capped at ts 1), a smaller one shrinks them
		assertEquals(32F, 16F * WheelLayout.iconScale(7, 2F), 1e-4F);
		assertEquals(16F, 16F * WheelLayout.iconScale(7, 0.5F), 1e-4F);
	}

	@Test
	void slotsAreEvenlySpacedFromTheTop() {
		assertEquals(0F, WheelLayout.slotAngle(0, 12), 0F);
		assertEquals((float) Math.PI, WheelLayout.slotAngle(6, 12), 1e-5F);
		assertEquals(WheelLayout.slotAngle(1, 12) - WheelLayout.slotAngle(0, 12), WheelLayout.slotAngle(12, 12) - WheelLayout.slotAngle(11, 12), 1e-5F);
	}

	@Test
	void theLanternKeepsItsPlaceBelowTheMiddle() {
		float[] d = new float[2];
		for (int count = 1; count <= 6; count++) {
			WheelLayout.accessoryDirection(0, count, d);
			assertEquals(0F, d[0], 1e-5F, "count " + count);
			assertEquals(1F, d[1], 1e-5F, "straight down the screen");
		}
	}

	@Test
	void fourAccessoriesSitRoundTheRing() {
		float[][] want = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // bottom (lantern), left, top, right: clockwise as the shield, spyglass, flint follow
		float[] d = new float[2];
		for (int k = 0; k < 4; k++) {
			WheelLayout.accessoryDirection(k, 4, d);
			assertEquals(want[k][0], d[0], 1e-5F, "k " + k);
			assertEquals(want[k][1], d[1], 1e-5F, "k " + k);
		}
		assertFalse(WheelLayout.accessoryAbove(0, 4));
		assertFalse(WheelLayout.accessoryAbove(1, 4));
		assertTrue(WheelLayout.accessoryAbove(2, 4), "the one at the top labels above itself");
		assertFalse(WheelLayout.accessoryAbove(3, 4));
	}

	@Test
	void accessoryLumpsClearEachOtherAndTheMiddle() {
		for (float ts : new float[] {0.5F, 1F, 1.5F}) {
			float r = R1 * ts * WheelLayout.SATELLITE_DROP;
			float orb = 16F * WheelLayout.SATELLITE_SCALE * ts; // BiomassOrb.RADIUS times the scale
			float middle = 17F * ts; // the middle lump: its two lines of text at most
			for (int count = 1; count <= 6; count++) {
				float[] a = new float[2], b = new float[2];
				for (int j = 0; j < count; j++) {
					WheelLayout.accessoryDirection(j, count, a);
					assertTrue(r - orb > middle, "the lump is clear of the middle at ts " + ts);
					assertTrue(r + orb < R1 * ts * WheelLayout.DEAD_ZONE, "and inside the dead zone, where the mouse is not on a tool slice");
					for (int k = j + 1; k < count; k++) {
						WheelLayout.accessoryDirection(k, count, b);
						float dx = (a[0] - b[0]) * r, dy = (a[1] - b[1]) * r;
						assertTrue(Math.sqrt(dx * dx + dy * dy) >= 2F * orb, "lumps " + j + " and " + k + " of " + count + " at ts " + ts + " overlap");
					}
				}
			}
		}
	}

	@Test
	void theFiveNewToolsAreLeftOutUntilTheServerBuildsThem() {
		ArmArsenal none = ArmArsenal.EMPTY;
		assertEquals(ArmProtocol.CLASSIC_FORMS, WheelLayout.activeSlots(none), "the wheel as it was: seven sockets");
		List<ArmArsenal.Entry> entries = List.of(new ArmArsenal.Entry("katana", "Tendril Katana", 1, 3, "Draining Cut", 0, 0, 0L),
				new ArmArsenal.Entry("trident", "Tendril Trident", 1, 3, "Recall", 0, 0, 0L));
		ArmArsenal built = new ArmArsenal(true, "", true, 0, 0, 0, 0, 0, entries, "", 0, false, 0, 0, 0L, 0L);
		List<String> active = WheelLayout.activeSlots(built);
		assertEquals(8, active.size());
		assertEquals("trident", active.getLast(), "a built new form joins in wheel order");
		assertFalse(active.contains("spear"));
		// all twelve built: the whole wheel
		List<ArmArsenal.Entry> all = new ArrayList<>();
		for (String form : ArmProtocol.WHEEL) all.add(new ArmArsenal.Entry(form, form, 1, 3, "", 0, 0, 0L));
		assertEquals(ArmProtocol.WHEEL, WheelLayout.activeSlots(new ArmArsenal(true, "", true, 0, 0, 0, 0, 0, all, "", 0, false, 0, 0, 0L, 0L)));
	}

	/**
	 * The hint lines ("Tap: form ...") never touch the hotbar, the XP number or the hearts (the rig, 2026-11-16: they sat on them):
	 * for every window size and GUI scale the game offers and every wheel size, the block is on screen and ends above the HUD's top,
	 * with one to four lines (the most the wheel writes: the empty-arsenal line, the survival buy hint, the tap line and the close hint).
	 */
	@Test
	void hintLinesStayOffTheHud() {
		int[][] windows = {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
		for (int[] w : windows) {
			for (int scale = 1; scale <= 6; scale++) {
				float guiH = (float) Math.ceil(w[1] / (double) scale), guiW = (float) Math.ceil(w[0] / (double) scale);
				if (guiH < 120F) continue; // the game won't go below a 320 x 240 GUI
				for (float wheel : new float[] {0.2F, 0.5F, 0.75F, 1F}) {
					float radius = Math.min(guiW, guiH) * wheel / 2F;
					float ts = radius / R1;
					float line = 9F * 0.45F * ts + 1F;
					for (int lines = 1; lines <= 4; lines++) {
						float block = lines * line - 1F;
						float top = WheelLayout.hintTop(guiH / 2F, radius, 4F * ts, block, guiH);
						String at = w[0] + "x" + w[1] + " gui " + scale + " wheel " + wheel + " lines " + lines;
						assertTrue(top >= WheelLayout.HINT_EDGE - 0.001F, "on screen: " + at);
						assertTrue(top + block <= guiH - WheelLayout.HUD_RESERVE + 0.001F, "above the HUD: " + at + " top " + top + " block " + block);
					}
				}
			}
		}
	}

	@Test
	void hintLinesKeepUnderTheRingWhenThereIsRoomAndGoOverItWhenThereIsNot() {
		// a tall screen and a small wheel: where they always were, 4 under the ring
		assertEquals(300F + 80F + 4F, WheelLayout.hintTop(300F, 80F, 4F, 30F, 600F), 0.001F);
		// the default wheel on a 240 GUI px tall screen: the ring's bottom is under the HUD's top, so over the ring
		float radius = 240F * 0.75F / 2F;
		float top = WheelLayout.hintTop(120F, radius, 4F, 15F, 240F);
		assertEquals(120F - radius - 4F - 15F, top, 0.001F);
		assertTrue(top > 0F);
	}

	/**
	 * How far down the wheel's labels reach for a fitted ring (the lowest slot's icon, its name and pips under it): what the drawing tracks as
	 * {@code labelsBottom}, rebuilt here from the same arithmetic.
	 */
	private static float labelsReach(WheelLayout.Fit fit, int guiScale, int n) {
		float ts = WheelLayout.textScale(fit.radius());
		return fit.cy() + Math.max(0.2F, WheelLayout.lowestSlot(n)) * WheelLayout.SLOT_RADIUS * fit.radius() + 10F * WheelLayout.iconScaleFor(n, ts, guiScale)
				+ WheelLayout.labelBand(guiScale, ts);
	}

	/**
	 * The hint lines sat on the names of Spear, Pickaxe and Spade at 960 by 520, GUI scale 2 with Tooltips on (2026-11-18): the old rule put them
	 * under the ring's edge, and the lowest names hang below it. For every window, GUI scale, wheel size and slot count the block that is put
	 * under the ring now starts below the labels and ends above the HUD, one put over the ring ends above the ring, and when neither fits the
	 * caller is told (NaN) and has the free column beside the ring.
	 */
	@Test
	void hintLinesClearTheSlotLabelsOrGoBesideTheRing() {
		int[][] windows = {{854, 480}, {960, 520}, {1280, 720}, {1366, 768}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
		int beside = 0;
		for (int[] win : windows) {
			for (int setting = 0; setting <= 6; setting++) {
				int scale = dev.morphreborn.client.gui.UiLayout.guiScale(win[0], win[1], setting);
				float guiW = dev.morphreborn.client.gui.UiLayout.scaled(win[0], scale), guiH = dev.morphreborn.client.gui.UiLayout.scaled(win[1], scale);
				for (float share : new float[] {0.2F, 0.5F, 0.75F, 1F}) {
					for (int n : new int[] {5, 7, 12}) {
						WheelLayout.Fit fit = WheelLayout.fit(Math.min(guiW, guiH) * share / 2F, guiW, guiH, scale, n);
						float ts = WheelLayout.textScale(fit.radius());
						float reach = labelsReach(fit, scale, n);
						for (int lines = 1; lines <= 4; lines++) {
							float block = lines * (9F * Math.max(0.45F * ts, 0.75F) + 1F) - 1F;
							String at = win[0] + "x" + win[1] + " gui " + scale + " wheel " + share + " slots " + n + " lines " + lines;
							float top = WheelLayout.hintClear(fit.cy(), fit.radius(), 4F * ts, block, guiH, reach);
							if (Float.isNaN(top)) {
								beside++;
								continue;
							}
							assertTrue(top + block <= guiH - WheelLayout.HUD_RESERVE + 0.001F, "above the HUD: " + at);
							assertTrue(top >= WheelLayout.HINT_EDGE - 0.001F, "on screen: " + at);
							boolean over = top + block <= fit.cy() - fit.radius();
							assertTrue(over || top >= Math.max(reach, fit.cy() + fit.radius()), "over the ring or clear under its labels: " + at + " top " + top + " reach " + reach);
						}
					}
				}
			}
		}
		assertTrue(beside > 0, "some sizes have no room under or over the ring");
	}

	@Test
	void theHintLinesAtTheReportedSizeGoBesideTheRingNotOverTheNames() {
		// 960 by 520 at GUI scale 2: the default wheel, twelve tools; the old rule's block began under the ring's edge and ran into the lowest names
		int scale = 2;
		float guiW = 480F, guiH = 260F;
		WheelLayout.Fit fit = WheelLayout.fit(guiH * 0.75F / 2F, guiW, guiH, scale, 12);
		float ts = WheelLayout.textScale(fit.radius());
		float reach = labelsReach(fit, scale, 12);
		float block = 2 * (9F * Math.max(0.45F * ts, 0.75F) + 1F) - 1F;
		float old = WheelLayout.hintTop(fit.cy(), fit.radius(), 4F * ts, block, guiH);
		assertTrue(old < reach, "the old placement ran into the labels (block from " + old + ", labels to " + reach + ")");
		assertTrue(Float.isNaN(WheelLayout.hintClear(fit.cy(), fit.radius(), 4F * ts, block, guiH, reach)), "no clear spot under or over the ring");
		assertTrue(WheelLayout.hintSideWidth(guiW, guiW / 2F, fit.radius()) >= WheelLayout.HINT_MIN_SIDE, "but a free column beside it");
		// plenty of room under the labels: they stay centred below, as before
		assertEquals(300F + 80F + 4F, WheelLayout.hintClear(300F, 80F, 4F, 30F, 600F, 300F + 80F - 10F), 0.001F);
		// labels reaching further down than the ring push the block down with them
		assertEquals(300F + 100F + 4F, WheelLayout.hintClear(300F, 80F, 4F, 30F, 600F, 300F + 100F), 0.001F);
	}

	@Test
	void lanternBrightnessStepsWithinItsRangeWithoutWrapping() {
		assertEquals(3, WheelLayout.maxBrightness(1));
		assertEquals(4, WheelLayout.maxBrightness(2));
		assertEquals(5, WheelLayout.maxBrightness(3));
		assertEquals(5, WheelLayout.maxBrightness(9), "the server clamps the level to 3");
		assertEquals(4, WheelLayout.steppedBrightness(3, 1, 3));
		assertEquals(5, WheelLayout.steppedBrightness(5, 1, 3), "the top stays the top");
		assertEquals(1, WheelLayout.steppedBrightness(1, -1, 3), "and so does the bottom");
		assertEquals(3, WheelLayout.steppedBrightness(3, 1, 1), "a level 1 lantern stops at 3");
		assertEquals(2, WheelLayout.steppedBrightness(0, -1, 3), "unknown reads as the middle one");
	}

	@Test
	void aCursorThatHasNotMovedHasChosenNoLump() {
		assertFalse(WheelLayout.moved(0, 0), "the cursor the game centred on opening: a tap or a hold there picks no lump");
		assertFalse(WheelLayout.moved(1.0, 1.0));
		assertTrue(WheelLayout.moved(2.0, 0), "a real move does");
		assertTrue(WheelLayout.moved(0, -30));
	}
}
