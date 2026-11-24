package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.accessory.ShieldAccessory;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.look.ShieldLook;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.ShieldRules;
import dev.morphreborn.paper.arm.rules.ShieldSettings;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The Tendril Shield (my design, 2026-11-16): its settings and bundled rows, which blows are frontal, what each level cuts, the
 * velocity that sends a projectile back at its shooter (flown with the game's own arrow motion), which projectiles are the shield's, and
 * what the module says of itself.
 */
class ShieldAccessoryTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(ShieldAccessoryTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
			yaml.load(r);
		}
		return yaml;
	}

	private static Map<String, Object> nested(ConfigurationSection s) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : s.getKeys(false)) {
			Object v = s.get(key);
			out.put(key, v instanceof ConfigurationSection child ? nested(child) : v);
		}
		return out;
	}

	private static List<Double> levels(ArmConfig.Levels l) {
		return List.of(l.at(1), l.at(2), l.at(3));
	}

	// ------------------------------------------------------------------ settings

	@Test
	void theBundledSectionIsTheDefaults() throws Exception {
		YamlConfiguration abilities = load("abilities.yml");
		ConfigurationSection shield = Objects.requireNonNull(abilities.getConfigurationSection("arms.accessories.shield"));
		assertEquals(ShieldSettings.DEFAULTS, ShieldSettings.parse(nested(shield)));
		assertEquals(ShieldSettings.DEFAULTS, ShieldSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(abilities.getConfigurationSection("arms"))))
				.accessory("shield")), "through the arm config's merged section too");
		assertEquals(ShieldSettings.DEFAULTS, ShieldSettings.parse(null));
		assertEquals(ShieldSettings.DEFAULTS, ShieldSettings.parse(Map.of()));
	}

	@Test
	void theDefaultsAreTheOwnersNumbers() {
		ShieldSettings s = ShieldSettings.DEFAULTS;
		assertEquals(List.of(0.5, 0.6, 0.7), levels(s.reduction()), "frontal melee is cut 50 / 60 / 70%");
		assertTrue(s.reflectProjectiles());
		assertEquals(100.0, s.coneDegrees(), "about 100 degrees of the look");
		assertTrue(s.raiseDelayTicks() < 5, "quicker than vanilla's shield");
	}

	@Test
	void settingsAreClampedAndABadValueTakesTheDefault() {
		ShieldSettings d = ShieldSettings.DEFAULTS;
		ShieldSettings s = ShieldSettings.parse(Map.of("reduction", List.of(2, "x"), "reflect-projectiles", "off", "cone-degrees", 500, "raise-delay-ticks", -4,
				"reflect-min-speed", 5, "reflect-max-speed", 0.5));
		assertEquals(List.of(1.0, d.reduction().at(2), d.reduction().at(3)), levels(s.reduction()), "a reduction is kept to 0 to 1, a bad entry takes that level's default");
		assertFalse(s.reflectProjectiles(), "off is off");
		assertEquals(180.0, s.coneDegrees());
		assertEquals(0, s.raiseDelayTicks());
		assertEquals(0.5, s.reflectMinSpeed(), "the speeds are put in order");
		assertEquals(5.0, s.reflectMaxSpeed());
		assertEquals(List.of(0.3, 0.3, 0.3), levels(ShieldSettings.parse(Map.of("reduction", 0.3)).reduction()), "one number is every level's");
		assertEquals(d.reduction(), ShieldSettings.parse(Map.of("reduction", "lots")).reduction(), "an unreadable value is the default");
	}

	@Test
	void describeSaysWhatTheLevelDoes() {
		assertEquals("Hold right-click to raise it (an item in your main hand with its own use for the click goes first; the off hand is locked while it is up)"
				+ " · cuts frontal blows by 60% · arrows, tridents and fireballs from the front fly back at their shooter · 1 biomass per blocked hit",
				ShieldSettings.DEFAULTS.describe(2, "1 biomass"));
		assertTrue(ShieldSettings.DEFAULTS.lines(3, "").text(false).endsWith("70%"), "no cost, no cost clause");
		assertTrue(ShieldSettings.parse(Map.of("reflect-projectiles", false)).describe(1, "").contains("from the front are cut by 50% too"));
	}

	@Test
	void theBundledCostsAndDrainAreThePhaseOneRows() throws Exception {
		ConfigurationSection arm = Objects.requireNonNull(load("survival.yml").getConfigurationSection("arm-cost"));
		ArmConfig.Costs costs = ArmConfig.parse(Map.of(), nested(arm), Map.of()).costs();
		assertEquals(1000, costs.special("shield-block", 1), "a blocked hit costs 1 biomass at every level");
		assertEquals(1000, costs.special("shield-block", 3));
		assertEquals(500, costs.accessoryDrainPerMinute("shield"), "0.5 biomass a minute while it is on");
	}

	// ------------------------------------------------------------------ the front

	@Test
	void aBlowFromWhereTheOwnerLooksIsFrontal() {
		// yaw 0 faces south (+z), 90 faces west (-x), 180 north, -90 east
		assertTrue(ShieldRules.frontal(0, 0, 5, 100), "straight ahead");
		assertFalse(ShieldRules.frontal(0, 0, -5, 100), "straight behind");
		assertTrue(ShieldRules.frontal(90, -5, 0, 100), "facing west, from the west");
		assertFalse(ShieldRules.frontal(90, 5, 0, 100), "facing west, from the east");
		assertTrue(ShieldRules.frontal(180, 0, -3, 100));
		assertTrue(ShieldRules.frontal(-90, 3, 0, 100), "facing east, from the east");
	}

	@Test
	void theConeIsTheSettingsHalfAngleOnEitherSide() {
		for (int side : new int[] {-1, 1}) {
			// 99 degrees off the look is in, 101 is out (yaw 0: the look is +z; an angle a off it is (sin a, cos a))
			assertTrue(ShieldRules.frontal(0, side * Math.sin(Math.toRadians(99)), Math.cos(Math.toRadians(99)), 100), "99 of 100");
			assertFalse(ShieldRules.frontal(0, side * Math.sin(Math.toRadians(101)), Math.cos(Math.toRadians(101)), 100), "101 of 100");
		}
		assertTrue(ShieldRules.frontal(0, 5, 0, 90), "exactly beside: vanilla's 90 includes the line");
		assertFalse(ShieldRules.frontal(0, 5, -0.01, 90));
		assertTrue(ShieldRules.frontal(0, 0, -5, 180), "all round");
		assertFalse(ShieldRules.frontal(0, 0, 5, 0), "no cone, no block");
	}

	@Test
	void lookingUpOrDownChangesNothingAndASourceOnTopCounts() {
		// only the ground plane is read: a blow from a different height at the same ground offset is the same
		assertTrue(ShieldRules.frontal(0, 0, 0.0000001, 100), "right on top counts as frontal (vanilla's does)");
		assertTrue(ShieldRules.frontal(0, 0, 0, 100));
		assertTrue(ShieldRules.frontal(360, 0, 5, 100), "a yaw a turn round is the same");
		assertTrue(ShieldRules.frontal(-720, 0, 5, 100));
	}

	// ------------------------------------------------------------------ the cut

	@Test
	void eachLevelCutsItsShare() {
		ShieldSettings s = ShieldSettings.DEFAULTS;
		assertEquals(5.0, ShieldRules.cut(10, s.reduction().at(1)), 1e-9);
		assertEquals(4.0, ShieldRules.cut(10, s.reduction().at(2)), 1e-9);
		assertEquals(3.0, ShieldRules.cut(10, s.reduction().at(3)), 1e-9);
		assertEquals(3.0, ShieldRules.cut(10, s.reduction().at(7)), 1e-9, "a level past the last is the last");
		assertEquals(5.0, ShieldRules.cut(10, s.reduction().at(0)), 1e-9, "and one below the first is the first");
	}

	@Test
	void aCutIsKeptToZeroThroughAll() {
		assertEquals(10.0, ShieldRules.cut(10, -1), "a negative reduction cuts nothing");
		assertEquals(0.0, ShieldRules.cut(10, 4), "more than all cuts all");
		assertEquals(0.0, ShieldRules.cut(-3, 0.5), "no damage stays none");
	}

	@Test
	void theShieldHoldsOnceItHasBeenUpForTheDelay() {
		assertFalse(ShieldRules.holds(false, 100, 3), "down never holds");
		assertFalse(ShieldRules.holds(true, 2, 3));
		assertTrue(ShieldRules.holds(true, 3, 3));
		assertTrue(ShieldRules.holds(true, 0, 0), "no delay holds at once");
		assertTrue(ShieldRules.holds(true, 0, -2), "a negative delay is none");
	}

	// ------------------------------------------------------------------ what is sent back

	@Test
	void arrowsTridentsAndFireballsAreTheShieldsAndNothingElseIs() {
		assertEquals(ShieldRules.Kind.ARROW, ShieldRules.kind("minecraft:arrow"));
		assertEquals(ShieldRules.Kind.ARROW, ShieldRules.kind("minecraft:spectral_arrow"));
		assertEquals(ShieldRules.Kind.ARROW, ShieldRules.kind("minecraft:trident"));
		assertEquals(ShieldRules.Kind.FLYER, ShieldRules.kind("minecraft:fireball"));
		assertEquals(ShieldRules.Kind.FLYER, ShieldRules.kind("minecraft:small_fireball"));
		assertEquals(ShieldRules.Kind.FLYER, ShieldRules.kind("minecraft:wither_skull"));
		for (String other : List.of("minecraft:snowball", "minecraft:ender_pearl", "minecraft:potion", "minecraft:fishing_bobber", "minecraft:firework_rocket",
				"minecraft:egg", "minecraft:shulker_bullet", "minecraft:llama_spit", "minecraft:marker")) {
			assertEquals(ShieldRules.Kind.OTHER, ShieldRules.kind(other), other);
		}
	}

	@Test
	void aSentBackProjectileKeepsItsSpeedWithinTheLimits() {
		assertEquals(3.0, ShieldRules.reflectSpeed(3.0, 1.2, 3.0));
		assertEquals(1.2, ShieldRules.reflectSpeed(0.2, 1.2, 3.0), "a spent arrow still comes back with some force");
		assertEquals(3.0, ShieldRules.reflectSpeed(9, 1.2, 3.0), "and never faster than the greatest");
		assertEquals(1.2, ShieldRules.reflectSpeed(Double.NaN, 1.2, 3.0));
		assertEquals(2.0, ShieldRules.reflectSpeed(2.0, 3.0, 1.2), "limits given the wrong way round still work");
	}

	@Test
	void aFlyerIsAimedStraightAtTheShooter() {
		double[] v = Objects.requireNonNull(ShieldRules.aim(0, 1, 0, 6, 4, 8, 0.5, 0, 1));
		assertEquals(0.5, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-9, "at the speed asked for");
		double len = Math.sqrt(6 * 6 + 3 * 3 + 8 * 8);
		assertArrayEquals(new double[] {6 / len * 0.5, 3 / len * 0.5, 8 / len * 0.5}, v, 1e-9, "along the line to the shooter");
	}

	/** The game's arrow motion (AbstractArrow): move, then drag, then gravity, each tick. The distance of the closest pass to the target. */
	private static double closestPass(double[] v, double[] from, double[] to, double gravity, double drag) {
		double x = from[0], y = from[1], z = from[2], vx = v[0], vy = v[1], vz = v[2], best = Double.MAX_VALUE;
		for (int t = 0; t < 200; t++) {
			// the arrow travels the whole segment this tick (the game sweeps its box along it), so the pass is judged against the segment
			double sx = vx, sy = vy, sz = vz;
			double len2 = sx * sx + sy * sy + sz * sz;
			double u = len2 < 1e-12 ? 0 : Math.clamp(((to[0] - x) * sx + (to[1] - y) * sy + (to[2] - z) * sz) / len2, 0, 1);
			double px = x + sx * u - to[0], py = y + sy * u - to[1], pz = z + sz * u - to[2];
			best = Math.min(best, Math.sqrt(px * px + py * py + pz * pz));
			x += vx;
			y += vy;
			z += vz;
			vx *= drag;
			vy = vy * drag - gravity;
			vz *= drag;
		}
		return best;
	}

	@Test
	void anArrowIsLiftedSoItsArcArrivesAtTheShooter() {
		double[][] cases = {{0, 1, 0, 20, 1, 0}, {0, 1, 0, 30, 4, 12}, {5, 70, 5, -25, 64, 10}, {0, 1, 0, 8, 6, -3}, {0, 1, 0, 3, 1.5, 3}};
		for (double[] c : cases) {
			double[] from = {c[0], c[1], c[2]}, to = {c[3], c[4], c[5]};
			double[] v = Objects.requireNonNull(ShieldRules.aim(from[0], from[1], from[2], to[0], to[1], to[2], 3.0, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG));
			assertTrue(closestPass(v, from, to, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG) < 0.6, "to " + List.of(c[3], c[4], c[5]) + " passes within 0.6 blocks");
		}
	}

	@Test
	void aSlowArrowIsLiftedMoreThanAFastOne() {
		double[] slow = Objects.requireNonNull(ShieldRules.aim(0, 0, 0, 20, 0, 0, 1.2, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG));
		double[] fast = Objects.requireNonNull(ShieldRules.aim(0, 0, 0, 20, 0, 0, 3.0, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG));
		assertTrue(slow[1] / Math.hypot(slow[0], slow[2]) > fast[1] / Math.hypot(fast[0], fast[2]), "a longer flight falls further, so the angle is higher");
		assertTrue(slow[0] > 0 && fast[0] > 0, "both still go the shooter's way");
	}

	@Test
	void aShooterOutOfReachIsAimedAtAsFarAsItGoesAndNeverBlowsUp() {
		double[] v = ShieldRules.aim(0, 0, 0, 500, 0, 0, 1.2, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG);
		assertNotNull(v);
		assertTrue(v[0] > 0 && Double.isFinite(v[1]) && Math.abs(v[1]) <= 2.4, "the lift is bounded");
		double[] above = Objects.requireNonNull(ShieldRules.aim(2, 0, 2, 2, 10, 2, 3.0, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG));
		assertEquals(0, above[0], 1e-9, "a shooter straight above is aimed straight up");
		assertEquals(3.0, above[1], 1e-9);
	}

	@Test
	void anArrowIsLaunchedForTheDragAndGravityThatFollowThisTicksHit() {
		// the hit event fires before the tick's drag and gravity: what is set there must come out as the aimed first move
		double[] u = ShieldRules.launch(1.5, 0.3, -0.9, ShieldRules.ARROW_GRAVITY, ShieldRules.ARROW_DRAG);
		assertEquals(1.5, u[0] * ShieldRules.ARROW_DRAG, 1e-12);
		assertEquals(0.3, u[1] * ShieldRules.ARROW_DRAG - ShieldRules.ARROW_GRAVITY, 1e-12);
		assertEquals(-0.9, u[2] * ShieldRules.ARROW_DRAG, 1e-12);
		double[] flat = ShieldRules.launch(1, 2, 3, 0, 1);
		assertArrayEquals(new double[] {1, 2, 3}, flat, 1e-12);
		assertEquals(1.0, ShieldRules.launch(1, 0, 0, 0, 0)[0], 1e-12, "a drag of 0 is read as none, never a division by zero");
	}

	@Test
	void nothingToAimAtSendsItBackAlongItsWay() {
		assertNull(ShieldRules.aim(1, 2, 3, 1, 2, 3, 3, 0.05, 0.99), "the same point has no direction");
		assertNull(ShieldRules.aim(0, 0, 0, 5, 0, 0, 0, 0.05, 0.99), "no speed has none either");
		assertArrayEquals(new double[] {-3, 0, 0}, Objects.requireNonNull(ShieldRules.reverse(2, 0, 0, 3)), 1e-9);
		double[] r = Objects.requireNonNull(ShieldRules.reverse(1, -2, 2, 6));
		assertArrayEquals(new double[] {-2, 4, -4}, r, 1e-9, "the reverse of the velocity, at the speed asked for");
		assertNull(ShieldRules.reverse(0, 0, 0, 3), "a projectile standing still has no way back");
	}

	// ------------------------------------------------------------------ the wire

	@Test
	void theShieldsFxMovesAreInTheShieldsOwnRangeOfTheArg() {
		assertEquals(0x101, ShieldRules.fxArg(ShieldAccessory.FX_BLOCK));
		assertEquals(0x102, ShieldRules.fxArg(ShieldAccessory.FX_REFLECT));
		assertEquals(0x103, ShieldRules.fxArg(ShieldAccessory.FX_ABSORB));
		for (int move : List.of(ShieldAccessory.FX_BLOCK, ShieldAccessory.FX_REFLECT, ShieldAccessory.FX_ABSORB)) {
			assertEquals(move, ShieldRules.fxMove(ShieldRules.fxArg(move)));
		}
		assertEquals(0, ShieldRules.fxMove(2), "a plain small arg is not the shield's");
		assertEquals(0, ShieldRules.fxMove(0x201), "nor is another accessory's");
		assertEquals(ArmTiers.ACCESSORIES.indexOf("shield"), ShieldRules.fxArg(1) >> 8, "bits 8-11 are the shield's place in the accessory ring");
	}

	// ------------------------------------------------------------------ the module

	@Test
	void theModuleIsTheShieldAndKnowsItsPlaceBeforeItIsInitialised() {
		ArmAccessoryModule m = new ShieldAccessory();
		assertEquals("shield", m.id());
		assertEquals(0, m.maxMode(3), "it has no mode");
		assertNotNull(m.listener());
		assertTrue(m.describe(1).startsWith("Hold right-click to raise it"), "it can be described before init");
		m.load(Map.of("reduction", List.of(0.1, 0.2, 0.3)));
		assertTrue(m.describe(3).contains("30%"), "and a reload changes what it says");
		m.switchOffAll();
	}

	@Test
	void theLookStateIsKeptByEntityIdAndOnlyTheViewerSideSeesIt() {
		assertEquals(ShieldLook.NONE, ShieldLook.state(424242), "no shield by default");
		assertFalse(ShieldLook.any() && ShieldLook.state(424242) != ShieldLook.NONE);
		ShieldLook.forget(424242);
		assertEquals(ShieldLook.NONE, ShieldLook.state(424242));
		assertTrue(ShieldLook.NONE < ShieldLook.HELD && ShieldLook.HELD < ShieldLook.RAISED, "raised is the highest state");
	}
}
