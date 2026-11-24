package dev.morphreborn.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.client.arm.gui.WheelLayout;
import dev.morphreborn.client.gui.UpgradeTreeScreen.Panel;
import dev.morphreborn.client.gui.UpgradeTreeScreen.Section;
import dev.morphreborn.client.gui.UpgradeTreeScreen.TreeLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every screen and overlay the mod draws, fitted to every window size and GUI scale (the 2026-11-18 layout round): the window sizes from
 * 854 by 480 to 3840 by 2160 (including 960 by 520, the one the upgrade tree overflowed in, and the ultrawide 3440 by 1440), the GUI scale
 * settings 1 to 6 and Auto as vanilla clamps them, and the pure layout classes' answers asserted: everything drawn inside the screen's margin,
 * the text a whole number of device texels per font pixel and never under one, and the upgrade tree's window reachable and opening on its trunk.
 */
class UiFitTest {
	/** Window sizes in device pixels (width, height). */
	private static final int[][] WINDOWS = {{854, 480}, {960, 520}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}};
	/** GUI scale settings: 0 is Auto. */
	private static final int[] SETTINGS = {0, 1, 2, 3, 4, 5, 6};

	/** One case: the window, the GUI scale vanilla ends up with, and the scaled screen. */
	private record Case(int winW, int winH, int scale, int w, int h) {}

	private static List<Case> cases() {
		List<Case> out = new ArrayList<>();
		for (int[] win : WINDOWS) {
			for (int setting : SETTINGS) {
				int scale = UiLayout.guiScale(win[0], win[1], setting);
				out.add(new Case(win[0], win[1], scale, UiLayout.scaled(win[0], scale), UiLayout.scaled(win[1], scale)));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ the shared maths

	@Test
	void guiScaleIsVanillasRule() {
		assertEquals(2, UiLayout.guiScale(854, 480, 0));
		assertEquals(2, UiLayout.guiScale(960, 520, 0));
		assertEquals(3, UiLayout.guiScale(1280, 720, 0));
		assertEquals(3, UiLayout.guiScale(1600, 900, 0));
		assertEquals(4, UiLayout.guiScale(1920, 1080, 0));
		assertEquals(6, UiLayout.guiScale(2560, 1440, 0));
		assertEquals(9, UiLayout.guiScale(3840, 2160, 0));
		// an explicit setting above what keeps the screen at 320 by 240 is clamped to it
		assertEquals(2, UiLayout.guiScale(854, 480, 6));
		assertEquals(1, UiLayout.guiScale(854, 480, 1));
		for (Case c : cases()) {
			assertTrue(c.w >= UiLayout.MIN_SCALED_W && c.h >= UiLayout.MIN_SCALED_H, c + ": vanilla never goes under 320 by 240");
		}
	}

	@Test
	void textIsWholeTexelsAndNeverUnderOne() {
		for (Case c : cases()) {
			for (float wanted : new float[] {0.05F, 0.2F, 0.45F, 0.5F, 0.75F, 1F, 1.7F, 4F}) {
				int texels = UiLayout.texels(c.scale, wanted);
				assertTrue(texels >= 1, c + " wanted " + wanted);
				float scale = UiLayout.textScale(c.scale, wanted);
				assertEquals(texels, scale * c.scale, 1e-3F, c + ": whole texels per font pixel");
			}
		}
	}

	@Test
	void tooltipsStayOnTheScreenOnEveryEdge() {
		for (Case c : cases()) {
			for (int[] box : new int[][] {{40, 12}, {UiLayout.tooltipMaxWidth(c.w) + 8, 80}, {UiLayout.TOOLTIP_MAX_W, UiLayout.tooltipMaxLines(c.h) * 10 - 2}}) {
				for (int mx : new int[] {0, c.w / 2, c.w - 1}) {
					for (int my : new int[] {0, c.h / 2, c.h - 1}) {
						int[] at = UiLayout.tooltipPos(c.w, c.h, mx, my, box[0], box[1]);
						assertTrue(at[0] >= 5 && at[1] >= 5, c + " top left of the box at the mouse " + mx + "," + my);
						assertTrue(at[0] + box[0] <= c.w - 5 && at[1] + box[1] <= c.h - 5, c + " bottom right of the box at the mouse " + mx + "," + my);
					}
				}
			}
			assertTrue(UiLayout.tooltipMaxWidth(c.w) <= c.w / 2 || UiLayout.tooltipMaxWidth(c.w) == 80, c + ": half the screen at most");
		}
	}

	// ------------------------------------------------------------------ the Arsenal wheel and the favourites radial

	@Test
	void wheelRingAndLabelsStayInsideTheScreenAtEverySizeAndCount() {
		for (Case c : cases()) {
			for (double setting : new double[] {0.2, 0.75, 1.0}) {
				for (int n : new int[] {5, 7, 12}) {
					float wanted = Math.min(c.w, c.h) * (float) setting / 2F;
					float radius = WheelLayout.fitRadius(wanted, c.w, c.h, c.scale, n);
					String at = c + " wheel " + setting + " slots " + n + " radius " + radius;
					assertTrue(radius <= wanted + 0.01F, at + ": never bigger than asked");
					assertTrue(radius <= Math.min(c.w, c.h) / 2F - UiLayout.MARGIN + 0.01F, at + ": the ring is inside the margin");
					float ts = WheelLayout.textScale(radius);
					// the lowest slot's icon, name and pips end on screen
					float bottom = c.h / 2F + WheelLayout.lowestSlot(n) * WheelLayout.SLOT_RADIUS * radius + 10F * WheelLayout.iconScaleFor(n, ts, c.scale)
							+ WheelLayout.labelBand(WheelLayout.slotLabelScale(c.scale, ts, radius, n, c.h));
					assertTrue(bottom <= c.h - UiLayout.MARGIN + 0.05F, at + ": labels end at " + bottom + " of " + c.h);
					// the text is whole texels, at least one
					float label = WheelLayout.slotLabelScale(c.scale, ts, radius, n, c.h);
					assertEquals(Math.round(label * c.scale), label * c.scale, 1e-3F, at + ": whole texels");
					assertTrue(label * c.scale >= 1F - 1e-3F, at + ": at least one texel");
					// the icons are whole device pixels and never smaller than one
					float icon = 16F * WheelLayout.iconScaleFor(n, ts, c.scale) * c.scale;
					assertEquals(Math.round(icon), icon, 1e-2F, at + ": whole device pixels");
					assertTrue(icon >= 1F);
					if (setting >= 0.75 && c.h >= 480) assertTrue(radius >= 60F, at + ": a usable wheel");
				}
			}
		}
	}

	@Test
	void wheelRadiusKeepsGrowingWithTheSetting() {
		for (Case c : cases()) {
			float last = 0F;
			for (double setting = 0.2; setting <= 1.0001; setting += 0.1) {
				float radius = WheelLayout.fitRadius(Math.min(c.w, c.h) * (float) setting / 2F, c.w, c.h, c.scale, 12);
				assertTrue(radius >= last - 0.01F, c + ": the wheel never shrinks as the setting grows (" + setting + ")");
				last = radius;
			}
		}
	}

	@Test
	void theInfoCardIsAlwaysOnScreenAndBesideTheRingWhenThereIsRoom() {
		for (Case c : cases()) {
			float radius = WheelLayout.fitRadius(Math.min(c.w, c.h) * 0.75F / 2F, c.w, c.h, c.scale, 12);
			UiLayout.Rect safe = UiLayout.safeScreen(c.w, c.h);
			for (float hoverX : new float[] {-1F, 0F, 1F}) {
				for (float[] size : new float[][] {{60, 30}, {120, 70}, {190, Math.min(160, c.h - 12)}}) {
					UiLayout.Rect card = WheelLayout.placeCard(c.w, c.h, c.w / 2F, c.h / 2F, radius, hoverX, size[0], size[1]);
					assertTrue(card.inside(safe), c + " card " + size[0] + "x" + size[1] + " hover " + hoverX + " at " + card);
					float side = (c.w - 2 * radius) / 2F - WheelLayout.CARD_GAP - UiLayout.MARGIN;
					if (side >= size[0] && size[1] <= c.h - 2 * UiLayout.MARGIN) {
						assertTrue(card.right() <= c.w / 2F - radius + 0.01F || card.x() >= c.w / 2F + radius - 0.01F, c + ": beside the ring, not over it: " + card);
					}
				}
			}
		}
	}

	@Test
	void theMiddleLumpStaysClearOfTheAccessories() {
		for (Case c : cases()) {
			float radius = WheelLayout.fitRadius(Math.min(c.w, c.h) * 0.75F / 2F, c.w, c.h, c.scale, 7);
			float ts = WheelLayout.textScale(radius);
			float limit = WheelLayout.centreLimit(radius, true);
			float satelliteInner = WheelLayout.SATELLITE_DROP * radius - WheelLayout.SATELLITE_SCALE * ts * 16F;
			assertTrue(limit <= satelliteInner, c + ": the lump (" + limit + ") ends before the accessories' (" + satelliteInner + ")");
			assertTrue(WheelLayout.centreLimit(radius, false) <= WheelLayout.DEAD_ZONE * radius, c + ": inside the dead zone");
		}
	}

	@Test
	void theRadialFitsWithItsNamesAndPrices() {
		for (Case c : cases()) {
			for (double setting : new double[] {0.2, 0.75, 1.0}) {
				float radius = WheelLayout.fitRadialRadius(Math.min(c.w, c.h) * (float) setting / 2F, c.w, c.h, c.scale);
				float ts = WheelLayout.textScale(radius);
				float s = WheelLayout.labelScale(c.scale, ts);
				float bottom = c.h / 2F + WheelLayout.SLOT_RADIUS * radius + 0.1F * radius + 5.2F * ts + 18F * s + 2F;
				assertTrue(bottom <= c.h - UiLayout.MARGIN + 0.05F, c + " radial " + setting + " labels end at " + bottom);
				assertTrue(radius <= Math.min(c.w, c.h) / 2F - UiLayout.MARGIN + 0.01F);
			}
		}
	}

	// ------------------------------------------------------------------ the HUD

	@Test
	void theBiomassBarKeepsOffTheHotbarOrRisesAboveTheSurvivalHud() {
		for (Case c : cases()) {
			for (boolean offhand : new boolean[] {false, true}) {
				for (int past : new int[] {0, 16}) {
					for (float s : new float[] {1F, c.scale >= 4 ? (c.scale - 1) / (float) c.scale : 1F}) {
						int lift = HudLayout.lift(c.w, s, offhand, HudLayout.MIN_BAR + past);
						int width = HudLayout.barWidth(c.w, s, offhand, lift > 0, 140, past);
						String at = c + " offhand " + offhand + " past " + past + " scale " + s;
						assertTrue(width >= HudLayout.MIN_BAR, at + ": never narrower than the least");
						float right = (HudLayout.LEFT + width + 2 + past) * s;
						if (lift == 0) {
							assertTrue(right <= HudLayout.maxRight(c.w, offhand) + 1F, at + ": clear of the hotbar, ends at " + right);
						} else {
							assertEquals(HudLayout.LIFT, lift);
							assertTrue(right <= c.w, at + ": raised, and still inside the screen");
						}
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ the upgrade tree

	/** survival.yml's upgrades in its order: id and parent. */
	private static final String[][] SHIPPED = {
			{"biomass_capacity", "root"}, {"biomass_efficiency", "biomass_capacity"}, {"biomass_reach", "biomass_capacity"},
			{"biomass_max_mass_absorbable", "biomass_capacity"}, {"morph_ability", "root"}, {"morph_capacity", "morph_ability"},
			{"morph_variant_capacity", "morph_capacity"}, {"morph_efficiency", "morph_ability"}, {"morph_abilities", "morph_ability"},
			{"multimorph", "morph_ability"}, {"rage", "morph_ability"}, {"ability_night_vision", "morph_abilities"},
			{"ability_water_breathing", "morph_abilities"}, {"ability_swift_swimming", "morph_abilities"}, {"ability_fire_immunity", "morph_abilities"},
			{"ability_soft_landing", "morph_abilities"}, {"passive_sustenance", "biomass_capacity"}, {"passive_night_eyes", "passive_sustenance"},
			{"passive_gills", "passive_sustenance"}, {"passive_fire_shedding", "passive_sustenance"}, {"passive_antitoxin", "passive_sustenance"},
			{"passive_tendril_sense", "passive_sustenance"}, {"passive_scavenger", "passive_sustenance"}, {"passive_borrowed_scent", "passive_sustenance"},
			{"morph_flight", "morph_abilities"}, {"ability_climbing", "morph_abilities"}, {"ability_leaping", "morph_abilities"},
			{"ability_mob_strength", "morph_abilities"}, {"ability_sure_footing", "morph_abilities"}, {"ability_defences", "morph_abilities"},
			{"ability_attack_effects", "morph_abilities"}, {"ability_camouflage", "morph_abilities"}, {"arm_shaping", "morph_ability"},
			{"arm_katana", "arm_shaping"}, {"arm_pickaxe", "arm_shaping"}, {"arm_axe", "arm_shaping"}, {"arm_shovel", "arm_shaping"},
			{"arm_sickle", "arm_shaping"}, {"arm_bow", "arm_shaping"}, {"arm_whip", "arm_shaping"}, {"arm_spear", "arm_shaping"},
			{"arm_mace", "arm_shaping"}, {"arm_trident", "arm_shaping"}, {"arm_crossbow", "arm_shaping"}, {"arm_claws", "arm_shaping"},
			{"arm_lantern", "arm_shaping"}, {"arm_shield", "arm_shaping"}, {"arm_spyglass", "arm_shaping"}, {"arm_flint", "arm_shaping"}};

	private static List<UpgradeTree.Node> shipped() {
		List<UpgradeTree.Node> nodes = new ArrayList<>();
		for (String[] row : SHIPPED) nodes.add(new UpgradeTree.Node(row[0], row[0], "", "minecraft:paper", row[1], 0, 1, "", "", 1, "", true));
		return nodes;
	}

	@Test
	void passivesHaveASectionOfTheirOwn() {
		TreeLayout layout = TreeLayout.of(shipped(), 640, 360);
		Panel passives = layout.panel(Section.PASSIVES);
		assertNotNull(passives);
		int count = 0;
		for (String[] row : SHIPPED) {
			if (!row[0].startsWith("passive_")) continue;
			count++;
			assertEquals(Section.PASSIVES, layout.section(row[0]));
			int[] at = layout.position(row[0]);
			assertNotNull(at, row[0]);
			assertTrue(at[0] >= passives.x() && at[0] + at[2] <= passives.x() + passives.w(), row[0] + " inside the panel across");
			assertTrue(at[1] >= passives.y() + 14 && at[1] + at[2] <= passives.y() + passives.h(), row[0] + " inside the panel down");
		}
		assertEquals(8, count);
		assertEquals(4 * 32 - 6 + 16, passives.w(), "four across");
		assertEquals(14 + 2 * 32 - 6 + 8, passives.h(), "two rows");
		// Biomass is what is left: the four upgrades, not twelve rows
		assertEquals(14 + 4 * 30 - 4 + 8, layout.panel(Section.BIOMASS).h());
	}

	@Test
	void theTreeIsAboutFiveHundredBy330NotFiveHundredBy508() {
		TreeLayout layout = TreeLayout.of(shipped(), 640, 360);
		assertTrue(layout.contentH() <= 340, "content height " + layout.contentH());
		assertTrue(layout.contentW() <= 520, "content width " + layout.contentW());
		assertTrue(layout.contentH() < 508 - 150, "well under the 508 it was");
	}

	@Test
	void everyNodeIsInsideTheContentAndNoTwoOverlap() {
		for (Case c : cases()) {
			TreeLayout layout = TreeLayout.of(shipped(), c.w, c.h);
			List<int[]> boxes = new ArrayList<>();
			for (String[] row : SHIPPED) {
				int[] at = layout.position(row[0]);
				assertNotNull(at, c + " " + row[0]);
				assertTrue(at[0] >= 0 && at[1] >= 0 && at[0] + at[2] <= layout.contentW() && at[1] + at[2] <= layout.contentH(), c + " " + row[0] + " inside the content");
				for (int[] other : boxes) {
					boolean apart = at[0] + at[2] <= other[0] || other[0] + other[2] <= at[0] || at[1] + at[2] <= other[1] || other[1] + other[2] <= at[1];
					assertTrue(apart, c + " " + row[0] + " overlaps another");
				}
				boxes.add(at);
			}
			for (Panel p : layout.panels()) {
				assertTrue(p.x() >= 0 && p.y() >= 0 && p.x() + p.w() <= layout.contentW() && p.y() + p.h() <= layout.contentH(), c + " " + p.section() + " panel inside the content");
			}
		}
	}

	@Test
	void theTrunkIsOnTheFirstScreenAndTheWindowOpensOnIt() {
		for (Case c : cases()) {
			TreeLayout layout = TreeLayout.of(shipped(), c.w, c.h);
			int insideW = Math.clamp(layout.contentW(), 234, TreeLayout.maxInsideWidth(c.w));
			int insideH = Math.clamp(layout.contentH(), 113, TreeLayout.maxInsideHeight(c.h));
			// the frame, its title and the 8 px margin fit the screen
			int frameW = insideW + 18;
			int frameH = insideH + 18 + 9;
			assertTrue(frameW + 16 <= c.w && frameH + 16 <= c.h, c + ": the window " + frameW + "x" + frameH + " fits the screen with its margin");
			double scrollX = TreeLayout.initialScroll(layout.contentW(), insideW, layout.focusX());
			double scrollY = TreeLayout.initialScroll(layout.contentH(), insideH, layout.focusY());
			assertTrue(scrollX <= (insideW - layout.contentW()) / 2.0 + 1e-9 || scrollX >= insideW - layout.contentW(), c + ": scroll is within the content");
			assertTrue(scrollX <= Math.max(0, (insideW - layout.contentW()) / 2.0) + 1e-9 && scrollY <= Math.max(0, (insideH - layout.contentH()) / 2.0) + 1e-9);
			assertTrue(scrollX >= Math.min(0, insideW - layout.contentW()) - 1e-9 && scrollY >= Math.min(0, insideH - layout.contentH()) - 1e-9);
			// the Morph Ability orb (the trunk), whole, is in the window as it opens
			int[] orb = layout.position("morph_ability");
			assertNotNull(orb);
			assertTrue(orb[0] + scrollX >= 0 && orb[0] + orb[2] + scrollX <= insideW, c + ": the orb is across in view");
			assertTrue(orb[1] + scrollY >= 0 && orb[1] + orb[2] + scrollY <= insideH, c + ": the orb is down in view");
			// the first upgrade of Biomass is in view too (the root of the tree)
			int[] root = layout.position("biomass_capacity");
			assertTrue(root[0] + scrollX >= 0 && root[1] + root[2] + scrollY <= insideH, c + ": Biomass Capacity in view");
		}
	}

	@Test
	void everythingCanBeScrolledTo() {
		for (Case c : cases()) {
			TreeLayout layout = TreeLayout.of(shipped(), c.w, c.h);
			int insideW = Math.clamp(layout.contentW(), 234, TreeLayout.maxInsideWidth(c.w));
			int insideH = Math.clamp(layout.contentH(), 113, TreeLayout.maxInsideHeight(c.h));
			// scrolled to the far end each way, the content's last row and column are reached
			assertEquals(insideW - layout.contentW(), Math.min(0, insideW - layout.contentW()));
			double farX = TreeLayout.scrollFor(-1e9, layout.contentW(), insideW);
			double farY = TreeLayout.scrollFor(-1e9, layout.contentH(), insideH);
			assertTrue(layout.contentW() + farX <= insideW + 1e-9 && layout.contentH() + farY <= insideH + 1e-9, c + ": the far corner can be brought into view");
			// and the scrollbars' thumbs sit within their tracks at both ends
			for (double offset : new double[] {0, Math.max(0, layout.contentH() - insideH) / 2.0, Math.max(0, layout.contentH() - insideH)}) {
				int[] thumb = TreeLayout.thumb(insideH - 2, insideH, layout.contentH(), offset);
				assertTrue(thumb[0] >= 0 && thumb[0] + thumb[1] <= insideH - 2, c + ": the thumb is inside its track");
				assertTrue(thumb[1] >= Math.min(10, insideH - 2) || layout.contentH() <= insideH);
			}
		}
	}

	@Test
	void theUpperRowHangsFromItsTopAndTheOrbsLevelWithTheFirstUpgrade() {
		TreeLayout layout = TreeLayout.of(shipped(), 640, 360);
		int[] root = layout.position("biomass_capacity");
		int[] orb = layout.position("morph_ability");
		int[] shaping = layout.position("arm_shaping");
		assertEquals(root[1] + 13, orb[1] + 16, "the trunk is one straight line: the orb's middle is level with the first upgrade's");
		assertEquals(orb[1], shaping[1]);
		assertTrue(orb[1] + orb[2] <= 14 + 70, "the trunk is in the first 85 px of the tree");
	}

	@Test
	void aTreeWithoutPassivesIsLaidOutAsBefore() {
		List<UpgradeTree.Node> nodes = new ArrayList<>();
		for (UpgradeTree.Node n : shipped()) if (!n.id().startsWith("passive_")) nodes.add(n);
		TreeLayout layout = TreeLayout.of(nodes, 640, 480);
		assertTrue(layout.panel(Section.PASSIVES) == null);
		Panel abilities = layout.panel(Section.ABILITIES);
		assertNotNull(abilities);
		// below the upper row, filling its width, as when it was alone
		assertTrue(abilities.y() >= layout.upperBottom() + 22);
	}
	@Test
	void theShippedTreeFitsAcrossA960By520WindowAndOpensOnItsTrunk() {
		// my window (GUI scale 2: 480 by 260): the Arsenal was cut off at the right until the upper row's gaps were cut from 34 to 22
		TreeLayout layout = TreeLayout.of(shipped(), 480, 260);
		assertEquals(418, layout.upperSpan());
		assertEquals(446, TreeLayout.maxInsideWidth(480));
		assertTrue(layout.contentW() <= TreeLayout.maxInsideWidth(480), "no panning across: " + layout.contentW());
		Panel arsenal = layout.panel(Section.ARSENAL);
		assertNotNull(arsenal);
		double scrollX = TreeLayout.initialScroll(layout.contentW(), layout.contentW(), layout.focusX());
		assertTrue(arsenal.x() + arsenal.w() + scrollX <= layout.contentW(), "the Arsenal is whole in view");
	}

	// ------------------------------------------------------------------ text on whole device pixels (2026-11-18, second round)

	@Test
	void snappedPositionsAreWholeDevicePixels() {
		for (Case c : cases()) {
			for (float gui : new float[] {0F, 0.25F, 41.25F, 41.5F, 41.75F, 100.3333F, 479.9F}) {
				float snapped = UiLayout.snap(c.scale, gui);
				assertEquals(Math.round(snapped * c.scale), snapped * c.scale, 1e-3F, c + ": " + gui + " snaps to a device pixel");
				assertTrue(Math.abs(snapped - gui) <= 0.5F / c.scale + 1e-4F, c + ": moved by half a device pixel at most");
			}
		}
	}

	@Test
	void theWheelsLabelsAreTwoTexelsWhenTheSlotsAllowAndNeverBiggerThanVanillaText() {
		// 960 by 520 at GUI scale 2, seven tools: the names were 8 device px tall, half the info card's; now 16 (a full vanilla line)
		float radius = WheelLayout.fitRadius(Math.min(480, 260) * 0.75F / 2F, 480, 260, 2, 7);
		float ts = WheelLayout.textScale(radius);
		assertEquals(1F, WheelLayout.slotLabelScale(2, ts, radius, 7, 260), 1e-6F);
		assertEquals(0.5F, WheelLayout.labelScale(2, ts), 1e-6F, "it was half of that");
		for (Case c : cases()) {
			for (int n : new int[] {5, 7, 8, 12}) {
				float r = WheelLayout.fitRadius(Math.min(c.w, c.h) * 0.75F / 2F, c.w, c.h, c.scale, n);
				float t = WheelLayout.textScale(r);
				float label = WheelLayout.slotLabelScale(c.scale, t, r, n, c.h);
				String at = c + " slots " + n;
				assertTrue(label >= WheelLayout.labelScale(c.scale, t) - 1e-6F, at + ": never under the base size");
				if (label > WheelLayout.labelScale(c.scale, t) + 1e-6F) {
					assertEquals(2F, label * c.scale, 1e-4F, at + ": raised to two texels");
					assertTrue(label <= 1F + 1e-6F, at + ": no bigger than vanilla text");
					assertTrue(WheelLayout.NAME_PX * label <= WheelLayout.slotSpacing(r, n) - 2F + 1e-3F, at + ": a typical name fits between slots");
				}
			}
		}
	}

	@Test
	void theHintsAndTheCardAreAtLeastThreeQuartersOfAGuiPixelAndWholeTexels() {
		for (Case c : cases()) {
			for (float ts : new float[] {0.4F, 1F, 3F, 8.4F}) {
				float hint = WheelLayout.hintScale(c.scale, ts);
				float card = WheelLayout.cardScale(c.scale, ts);
				assertEquals(Math.round(hint * c.scale), hint * c.scale, 1e-3F, c + " hint whole texels");
				assertEquals(Math.round(card * c.scale), card * c.scale, 1e-3F, c + " card whole texels");
				assertTrue(hint >= 0.6F && card >= 0.6F, c + ": hint " + hint + " card " + card);
				assertTrue(card >= WheelLayout.labelScale(c.scale, ts) * 0.5F, c + ": the card keeps up with the labels on a big ring");
			}
		}
	}

	@Test
	void theBiomassBarIsNeverNarrowerThanItsAmountText() {
		for (Case c : cases()) {
			for (int amountW : new int[] {40, 73, 90}) {
				for (boolean offhand : new boolean[] {false, true}) {
					for (float s : new float[] {1F, c.scale >= 4 ? (c.scale - 1) / (float) c.scale : 1F}) {
						int least = Math.max(HudLayout.MIN_BAR, amountW);
						int lift = HudLayout.lift(c.w, s, offhand, least);
						int width = HudLayout.barWidth(c.w, s, offhand, lift > 0, 140, 0, least);
						String at = c + " amount " + amountW + " offhand " + offhand + " scale " + s;
						assertTrue(width >= amountW, at + ": the amount fits in the bar (" + width + ")");
						if (lift == 0) assertTrue((HudLayout.LEFT + width + 2) * s <= HudLayout.maxRight(c.w, offhand) + 1F, at + ": clear of the hotbar");
					}
				}
			}
		}
		// a case I hit: 51200 as a capacity, 73 px of text, a 320 px scaled width and nothing in the offhand: the bar was 57 wide
		assertEquals(HudLayout.LIFT, HudLayout.lift(320, 1F, false, 73));
		assertTrue(HudLayout.barWidth(320, 1F, false, true, 140, 0, 73) >= 73);
	}

	// ------------------------------------------------------------------ second fix round (2026-11-18)

	@Test
	void theWheelIsRaisedOrShrunkToClearTheSurvivalHudAndKeepsTheCursorInTheDeadZone() {
		for (Case c : cases()) {
			for (double setting : new double[] {0.2, 0.75, 1.0}) {
				for (int n : new int[] {5, 7, 12}) {
					float wanted = Math.min(c.w, c.h) * (float) setting / 2F;
					float fitted = WheelLayout.fitRadius(wanted, c.w, c.h, c.scale, n);
					WheelLayout.Fit fit = WheelLayout.fit(wanted, c.w, c.h, c.scale, n);
					String at = c + " wheel " + setting + " slots " + n + " " + fit;
					assertTrue(fit.radius() <= fitted + 0.01F, at + ": never bigger than the screen fit");
					assertTrue(fit.radius() >= Math.max(20F, fitted * WheelLayout.MIN_HUD_SHRINK) - 0.01F, at + ": keeps most of its size");
					assertTrue(fit.cy() <= c.h / 2F + 0.01F, at + ": only ever raised");
					assertTrue(fit.cy() - fit.radius() >= UiLayout.MARGIN - 0.01F, at + ": the ring's top is inside the margin");
					// the cursor starts at the screen's middle: it must be in the dead zone or a tap would pick a form
					assertTrue(c.h / 2F - fit.cy() <= WheelLayout.MAX_RISE * fit.radius() + 0.01F, at + ": rise limited");
					assertTrue(c.h / 2F - fit.cy() < WheelLayout.DEAD_ZONE * fit.radius(), at + ": the cursor's start is in the dead zone");
					float ts = WheelLayout.textScale(fit.radius());
					float bottom = fit.cy() + WheelLayout.lowestSlot(n) * WheelLayout.SLOT_RADIUS * fit.radius() + 10F * WheelLayout.iconScaleFor(n, ts, c.scale)
							+ WheelLayout.labelBand(c.scale, ts);
					assertTrue(bottom <= c.h - UiLayout.MARGIN + 0.05F, at + ": labels still end on screen");
					boolean atLimit = fit.radius() <= Math.max(20F, fitted * WheelLayout.MIN_HUD_SHRINK) + 0.05F
							&& c.h / 2F - fit.cy() >= Math.min(WheelLayout.MAX_RISE * fit.radius(), c.h / 2F - fit.radius() - UiLayout.MARGIN) - 0.05F;
					assertTrue(bottom <= c.h - WheelLayout.HUD_RESERVE + 0.05F || atLimit, at + ": labels clear of the HUD (" + bottom + ") unless the limits were reached");
				}
			}
		}
		// the report: 854 by 480 at GUI 2, twelve tools at the default size: the lowest labels were over the hearts
		WheelLayout.Fit fit = WheelLayout.fit(240F * 0.75F / 2F, 427, 240, 2, 12);
		assertTrue(fit.cy() < 120F, "raised: " + fit);
	}

	@Test
	void aNameThatFitsIsDrawnAtTheLargerSizeWhateverTheOthersDo() {
		// twelve tools at GUI 2 on 854 by 480: a typical name (44 px) does not fit at two texels, so every name stayed at one; "Bow" (about 16 px) does
		float radius = WheelLayout.fitRadius(240F * 0.75F / 2F, 427, 240, 2, 12);
		float ts = WheelLayout.textScale(radius);
		assertEquals(WheelLayout.labelScale(2, ts), WheelLayout.slotLabelScale(2, ts, radius, 12, 240), 1e-6F, "the crowded ring's shared size is still the small one");
		float max = WheelLayout.nameScaleMax(2, ts, radius, 12, 240);
		assertEquals(1F, max, 1e-6F, "one GUI px per font px at GUI 2: two texels");
		assertTrue(16F * max <= WheelLayout.slotSpacing(radius, 12) - 2F, "a short name fits at that size");
	}

	// ------------------------------------------------------------------ settings buttons and tooltips (2026-11-18)

	/** The default font's advance per character (its glyph and the pixel after it), the narrow ones listed; everything else is 6. */
	private static int glyphAdvance(char c) {
		return switch (c) {
			case ' ' -> 4;
			case 'i', ':', ',', '.', '!', ';', '|' -> 2;
			case 'l', '\'', '`' -> 3;
			case 't', 'I', '[', ']', '*' -> 4;
			case 'f', 'k', '(', ')', '<', '>', '{', '}', '"' -> 5;
			default -> 6;
		};
	}

	/** The width in GUI px of {@code text}, as the font measures it (the sum of the advances): "New Morph: Black Tendrils" came to 129 px on screen. */
	private static int textWidth(String text) {
		int w = 0;
		for (char c : text.toCharArray()) w += glyphAdvance(c);
		return w;
	}

	/** A settings button is 150 px wide and its label is clipped 2 px in from each side. */
	private static final int BUTTON_TEXT_W = 146;

	@Test
	void everySettingsButtonLabelFitsTheButton() throws java.io.IOException {
		assertEquals(129, textWidth("New Morph: Black Tendrils"), "the width table matches the screen");
		String json;
		try (java.io.InputStream in = UiFitTest.class.getResourceAsStream("/assets/morphreborn/lang/en_us.json")) {
			assertNotNull(in);
			json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		java.util.Map<String, String> lang = new java.util.HashMap<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(morphreborn\\.settings\\.[A-Za-z0-9_.]+)\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
		while (m.find()) lang.put(m.group(1), m.group(2));
		int checked = 0;
		for (var e : lang.entrySet()) {
			String key = e.getKey();
			String prefix = "morphreborn.settings.";
			String rest = key.substring(prefix.length());
			int dot = rest.indexOf('.');
			if (dot < 0 || rest.indexOf('.', dot + 1) >= 0 || java.util.Set.of("tooltip", "open", "title", "help").contains(rest.substring(dot + 1))) continue;
			String base = lang.get(prefix + rest.substring(0, dot));
			if (base == null) continue;
			// a named choice: "Setting: Choice"
			String label = base + ": " + e.getValue();
			assertTrue(textWidth(label) <= BUTTON_TEXT_W, "\"" + label + "\" is " + textWidth(label) + " px, the button holds " + BUTTON_TEXT_W + " (" + key + ")");
			checked++;
		}
		assertTrue(checked > 20, "the choices were found");
		// an on/off switch and a value that is a number or a percentage: "Setting: OFF" and "Setting: 100%"
		for (var e : lang.entrySet()) {
			String rest = e.getKey().substring("morphreborn.settings.".length());
			if (rest.contains(".") || e.getValue().length() > 40) continue;
			assertTrue(textWidth(e.getValue() + ": OFF") <= BUTTON_TEXT_W || e.getValue().contains("..."), "\"" + e.getValue() + "\" with its value fits (" + e.getKey() + ")");
		}
	}

	@Test
	void tooltipsTakeNoMoreThanThreeQuartersOfTheScreenAndCutTheirDescriptionFirst() {
		// 960 by 520 at GUI 2: the Tendril Flint tooltip was 25 lines on a 260 px tall screen, the whole of it
		assertEquals(19, UiLayout.tooltipMaxLines(260), "19 lines of 10 px and the box's padding are 198 px of 260");
		for (Case c : cases()) {
			int lines = UiLayout.tooltipMaxLines(c.h);
			assertTrue(lines >= 1 && lines * 10 - 2 <= c.h - 2 * 5, c + ": inside the screen's margins");
			assertTrue(lines >= UiLayout.TOOLTIP_MIN_CAP, c + ": still room for a name, a value and a cost");
		}
		// description first: all of it when it fits, what is left of the room when not, none when under two lines would be
		assertEquals(7, UiLayout.elasticKeep(10, 7, 19, 2));
		assertEquals(6, UiLayout.elasticKeep(13, 7, 19, 2));
		assertEquals(2, UiLayout.elasticKeep(17, 7, 19, 2));
		assertEquals(0, UiLayout.elasticKeep(18, 7, 19, 2), "one line of a description says nothing");
		assertEquals(0, UiLayout.elasticKeep(30, 7, 19, 2), "the rest alone is already too tall: the description goes");
	}

	@Test
	void theNextLevelLineShowsOnlyWhatChanges() {
		String now = "While it is on, every melee hit you land sets the target alight · fire lasts 4 s · 0.5 biomass per ignition · your bow and crossbow shots burn";
		String next = "While it is on, every melee hit you land sets the target alight · fire lasts 5 s · 0.5 biomass per ignition · your bow and crossbow shots burn";
		assertEquals("fire lasts 5 s", ValueDiff.changed(now, next));
		assertEquals("12 hearts", ValueDiff.changed("10 hearts", "12 hearts"), "a single clause is left as it is");
		assertEquals("a · b", ValueDiff.changed("a · b", "a · b"), "nothing changed: the whole line, not an empty one");
		assertEquals("c · d", ValueDiff.changed("a · b", "a · c · d"), "new clauses in order");
		assertEquals("x", ValueDiff.changed("", "x"));
	}

	@Test
	void theCornerRaiseIsHeldSoItDoesNotFlickerWhenTheOffhandOrAnArmComesAndGoes() {
		HudLayout.Latch latch = new HudLayout.Latch();
		assertEquals(0, latch.apply(0, 0L), "never raised: stays down");
		assertEquals(HudLayout.LIFT, latch.apply(HudLayout.LIFT, 1_000L));
		assertEquals(HudLayout.LIFT, latch.apply(0, 1_000L + HudLayout.LIFT_HOLD_NS - 1), "held while the hold lasts");
		assertEquals(0, latch.apply(0, 1_000L + HudLayout.LIFT_HOLD_NS + 1), "down again after it");
		assertEquals(HudLayout.LIFT, latch.apply(HudLayout.LIFT, 5_000_000_000L), "raised at once when it is needed again");
	}
}
