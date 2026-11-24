package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.arm.accessory.FlintAccessory;
import dev.morphreborn.paper.arm.api.ArmAccessoryModule;
import dev.morphreborn.paper.arm.rules.ArmConfig;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import dev.morphreborn.paper.arm.rules.FlintRules;
import dev.morphreborn.paper.arm.rules.FlintRules.Target;
import dev.morphreborn.paper.arm.rules.FlintSettings;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * The Tendril Flint and Steel (my design, 2026-11-16): its settings and bundled rows, which blows are melee hits that light
 * their target, what a right-click may light, the S_ARM_FX numbers, and what the module says of itself.
 */
class FlintAccessoryTest {
	private static YamlConfiguration load(String file) throws Exception {
		YamlConfiguration yaml = new YamlConfiguration();
		try (Reader r = new InputStreamReader(Objects.requireNonNull(FlintAccessoryTest.class.getResourceAsStream("/" + file)), StandardCharsets.UTF_8)) {
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
		ConfigurationSection flint = Objects.requireNonNull(abilities.getConfigurationSection("arms.accessories.flint"));
		assertEquals(FlintSettings.DEFAULTS, FlintSettings.parse(nested(flint)));
		assertEquals(FlintSettings.DEFAULTS, FlintSettings.parse(ArmConfig.parse(Map.of(), Map.of(), nested(Objects.requireNonNull(abilities.getConfigurationSection("arms"))))
				.accessory("flint")), "through the arm config's merged section too");
		assertEquals(FlintSettings.DEFAULTS, FlintSettings.parse(null));
		assertEquals(FlintSettings.DEFAULTS, FlintSettings.parse(Map.of()));
	}

	@Test
	void theDefaultsAreTheOwnersNumbers() {
		FlintSettings s = FlintSettings.DEFAULTS;
		assertEquals(List.of(3.0, 4.0, 5.0), levels(s.igniteSeconds()), "melee hits burn 3 / 4 / 5 seconds");
		assertEquals(60, s.igniteTicks(1));
		assertEquals(80, s.igniteTicks(2));
		assertEquals(100, s.igniteTicks(3));
		assertEquals(List.of(4.0, 5.0, 6.0), levels(s.boltSeconds()), "a bolt burns as long as the bow's Fire tip");
		assertEquals(80, s.boltTicks(1));
		assertTrue(s.lightBlocks());
	}

	@Test
	void settingsAreClampedAndABadValueTakesTheDefault() {
		FlintSettings d = FlintSettings.DEFAULTS;
		FlintSettings s = FlintSettings.parse(Map.of("ignite-seconds", List.of(-4, "x", 9999), "bolt-seconds", 2, "light-blocks", "off"));
		assertEquals(List.of(0.0, d.igniteSeconds().at(2), 600.0), levels(s.igniteSeconds()), "kept to 0 to 600, a bad entry takes that level's default");
		assertEquals(0, s.igniteTicks(1), "no seconds, no fire");
		assertEquals(List.of(2.0, 2.0, 2.0), levels(s.boltSeconds()), "one number is every level's");
		assertFalse(s.lightBlocks(), "off is off");
		assertEquals(d.igniteSeconds(), FlintSettings.parse(Map.of("ignite-seconds", "lots")).igniteSeconds(), "an unreadable value is the default");
		assertEquals(List.of(3.0, 7.0, 7.0), levels(FlintSettings.parse(Map.of("ignite-seconds", List.of(3, 7))).igniteSeconds()), "a short list repeats its last");
	}

	@Test
	void describeSaysWhatTheLevelDoes() {
		assertEquals("While it is on, every melee hit you land sets the target alight · fire lasts 4 s · 0.5 biomass per ignition · your bow and crossbow shots burn"
				+ " · right-click a campfire, candle, TNT or portal frame with a formed arm in your main hand to light it", FlintSettings.DEFAULTS.describe(2, "0.5 biomass"));
		assertTrue(FlintSettings.DEFAULTS.describe(3, "").contains("fire lasts 5 s ·"), "no cost, no cost clause");
		assertFalse(FlintSettings.DEFAULTS.describe(3, "").contains("per ignition"));
		assertFalse(FlintSettings.parse(Map.of("light-blocks", false)).describe(1, "").contains("campfire"));
		assertTrue(FlintSettings.parse(Map.of("ignite-seconds", 1.5)).describe(1, "").contains("1.5 s"));
	}

	@Test
	void theBundledCostsAndDrainAreThePhaseOneRows() throws Exception {
		ConfigurationSection arm = Objects.requireNonNull(load("survival.yml").getConfigurationSection("arm-cost"));
		ArmConfig.Costs costs = ArmConfig.parse(Map.of(), nested(arm), Map.of()).costs();
		assertEquals(500, costs.special("flint-ignite", 1), "an ignition costs half a biomass at every level");
		assertEquals(500, costs.special("flint-ignite", 3));
		assertEquals(250, costs.accessoryDrainPerMinute("flint"), "a quarter of a biomass a minute while it is on");
	}

	// ------------------------------------------------------------------ the blows

	@Test
	void aMeleeBlowIsTheOwnersOwnAttackOrSweepOrTheNewWeaponsBlow() {
		assertTrue(FlintRules.meleeBlow(true, "minecraft:player_attack", true), "a plain blow or a sweep");
		assertTrue(FlintRules.meleeBlow(false, "minecraft:spear", true), "the spear's jab and charge have no Bukkit cause");
		assertTrue(FlintRules.meleeBlow(false, "minecraft:mace_smash", true), "nor has the mace's smash");
		assertFalse(FlintRules.meleeBlow(false, "minecraft:arrow", true), "an arrow is no melee hit");
		assertFalse(FlintRules.meleeBlow(false, "minecraft:thorns", true), "thorns neither");
		assertFalse(FlintRules.meleeBlow(true, "minecraft:player_attack", false), "a blow the owner did not strike themself (a thrown thing, a pet) is not theirs");
		assertFalse(FlintRules.meleeBlow(false, "minecraft:spear", false));
		assertTrue(ArmTiers.isWeaponDamageType("minecraft:spear") && ArmTiers.isWeaponDamageType("minecraft:mace_smash"), "the same two types the billing counts as melee");
	}

	@Test
	void theBlowThatKillsLightsNothing() {
		assertTrue(FlintRules.lethal(4, 4), "exactly dead is dead");
		assertTrue(FlintRules.lethal(3, 7));
		assertFalse(FlintRules.lethal(20, 7.5));
		assertFalse(FlintRules.lethal(0.5, 0), "a blow of nothing kills nothing");
	}

	// ------------------------------------------------------------------ the shots

	private static final UUID ARCHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final UUID OTHER = UUID.fromString("66666666-7777-8888-9999-000000000000");

	@Test
	void aShotsStampCarriesItsShooterAndTheFlintsLevel() {
		String stamp = FlintRules.stamp(ARCHER, 2);
		assertEquals(ARCHER + ":2", stamp);
		assertEquals(ARCHER, FlintRules.stampOwner(stamp));
		assertEquals(2, FlintRules.stampLevel(stamp));
		assertEquals(1, FlintRules.stampLevel(FlintRules.stamp(ARCHER, 0)), "the level is kept to 1 to 3");
		assertEquals(3, FlintRules.stampLevel(FlintRules.stamp(ARCHER, 9)));
	}

	@Test
	void aTagThatIsNoStampIsNobodysAndLevelOne() {
		assertNull(FlintRules.stampOwner(null));
		assertNull(FlintRules.stampOwner(""));
		assertNull(FlintRules.stampOwner("1"));
		assertNull(FlintRules.stampOwner("not-a-uuid:2"));
		assertEquals(1, FlintRules.stampLevel(null));
		assertEquals(1, FlintRules.stampLevel("no level"));
		assertEquals(1, FlintRules.stampLevel(ARCHER + ":x"));
		assertFalse(FlintRules.stampedBy("1", ARCHER));
	}

	@Test
	void anArrowIsLitAtTheLaunchOnlyWithFlintOnAndOnlyOnce() {
		assertTrue(FlintRules.litAtLaunch(true, false, false), "a vanilla bow's or crossbow's arrow, or a thrown trident");
		assertFalse(FlintRules.litAtLaunch(false, false, false), "flint off: nothing is lit");
		assertFalse(FlintRules.litAtLaunch(true, true, false), "the arm's own bolts burn by the Fire tip or by the crossbow's module");
		assertFalse(FlintRules.litAtLaunch(true, false, true), "never lit twice (the crossbow arm's bolt is stamped before its launch event)");
	}

	@Test
	void aLitShotBurnsOnlyForItsShooterAndNotWhenItIsAlightItself() {
		String stamp = FlintRules.stamp(ARCHER, 3);
		assertTrue(FlintRules.shotBurns(stamp, ARCHER, 0));
		assertFalse(FlintRules.shotBurns(stamp, OTHER, 0), "a shot a shield turned round has a new shooter: it burns nobody");
		assertFalse(FlintRules.shotBurns(null, ARCHER, 0), "a shot that was never lit");
		assertFalse(FlintRules.shotBurns(stamp, ARCHER, 100), "a Flame bow's arrow is on fire itself: the game lights its target, free");
	}

	@Test
	void aShotBurnsAsLongAsTheBoltRowByTheLevelItWasLoosedAt() {
		FlintSettings s = FlintSettings.DEFAULTS;
		assertEquals(80, s.boltTicks(FlintRules.stampLevel(FlintRules.stamp(ARCHER, 1))), "4 s");
		assertEquals(100, s.boltTicks(FlintRules.stampLevel(FlintRules.stamp(ARCHER, 2))), "5 s, as Flame's");
		assertEquals(120, s.boltTicks(FlintRules.stampLevel(FlintRules.stamp(ARCHER, 3))), "6 s");
		assertFalse(FlintRules.lethal(20, 9), "the shot that does not kill lights it");
		assertTrue(FlintRules.lethal(6, 9), "the shot that kills lights nothing, as a melee blow doesn't");
	}

	// ------------------------------------------------------------------ the blocks

	@Test
	void aRightClickLightsOnlyWhatFlintAndSteelLights() {
		assertEquals(Target.CAMPFIRE, FlintRules.target("minecraft:campfire", false, false));
		assertEquals(Target.CAMPFIRE, FlintRules.target("minecraft:soul_campfire", false, false));
		assertEquals(Target.CANDLE, FlintRules.target("minecraft:candle", false, false));
		assertEquals(Target.CANDLE, FlintRules.target("minecraft:red_candle", false, false), "any colour");
		assertEquals(Target.CANDLE_CAKE, FlintRules.target("minecraft:candle_cake", false, false));
		assertEquals(Target.CANDLE_CAKE, FlintRules.target("minecraft:blue_candle_cake", false, false), "not mistaken for a candle");
		assertEquals(Target.TNT, FlintRules.target("minecraft:tnt", false, false));
		assertEquals(Target.PORTAL_FRAME, FlintRules.target("minecraft:obsidian", false, false));
		assertEquals(Target.NONE, FlintRules.target("minecraft:crying_obsidian", false, false), "crying obsidian is no portal frame");
		assertEquals(Target.NONE, FlintRules.target("minecraft:grass_block", false, false), "a shovel's path, an axe's strip: the click is the arm's own");
		assertEquals(Target.NONE, FlintRules.target("minecraft:furnace", false, false));
	}

	@Test
	void blocksAreLitOnlyInSurvivalAndCreative() {
		assertTrue(FlintRules.mayLightIn("SURVIVAL"));
		assertTrue(FlintRules.mayLightIn("CREATIVE"));
		assertFalse(FlintRules.mayLightIn("ADVENTURE"), "spawn and lobby protection: vanilla flint and steel can't be used there either");
		assertFalse(FlintRules.mayLightIn("SPECTATOR"));
		assertFalse(FlintRules.mayLightIn(""));
	}

	@Test
	void vanillaSpawnProtectionIsASquareThatOpsAndEmptyOpListsSkip() {
		assertTrue(FlintRules.underSpawnProtection(true, true, false, 16, 16, -16), "the square's corner is inside (the larger axis, inclusive)");
		assertTrue(FlintRules.underSpawnProtection(true, true, false, 16, 0, 3));
		assertFalse(FlintRules.underSpawnProtection(true, true, false, 16, 17, 0));
		assertFalse(FlintRules.underSpawnProtection(true, true, true, 16, 0, 0), "an op may build");
		assertFalse(FlintRules.underSpawnProtection(true, false, false, 16, 0, 0), "a server with no ops has no protection");
		assertFalse(FlintRules.underSpawnProtection(true, true, false, 0, 0, 0), "radius 0 turns it off");
		assertFalse(FlintRules.underSpawnProtection(false, true, false, 16, 0, 0), "only the spawn's world");
	}

	@Test
	void aLitOrDrownedLightableIsLeftAlone() {
		for (String key : List.of("minecraft:campfire", "minecraft:soul_campfire", "minecraft:candle", "minecraft:green_candle", "minecraft:candle_cake",
				"minecraft:pink_candle_cake")) {
			assertEquals(Target.NONE, FlintRules.target(key, true, false), key + " already lit: a shovel puts it out, nothing here");
			assertEquals(Target.NONE, FlintRules.target(key, false, true), key + " under water");
		}
		assertEquals(Target.TNT, FlintRules.target("minecraft:tnt", true, true), "TNT has neither state");
	}

	// ------------------------------------------------------------------ the wire

	@Test
	void fxArgsCarryFlintsPlaceInTheAccessoryRingAndTheMove() {
		assertEquals(ArmTiers.ACCESSORIES.indexOf("flint"), FlintRules.PLACE, "flint is place 3");
		assertEquals(0x301, FlintRules.fxArg(FlintRules.FX_IGNITE));
		assertEquals(0x302, FlintRules.fxArg(FlintRules.FX_LIGHT));
		assertEquals(0x303, FlintRules.fxArg(FlintRules.FX_SPARK), "the switch-on spark, added 2026-11-16");
		for (int move : List.of(FlintRules.FX_IGNITE, FlintRules.FX_LIGHT, FlintRules.FX_SPARK)) assertEquals(move, FlintRules.fxMove(FlintRules.fxArg(move)));
		assertEquals(0, FlintRules.fxMove(1), "a plain small arg is not flint's");
		assertEquals(0, FlintRules.fxMove(0x201), "nor is the spyglass's");
		assertEquals(0, FlintRules.fxMove(0x101), "nor the shield's");
		assertEquals(ArmProtocol.FX_ACCESSORY, 20);
	}

	// ------------------------------------------------------------------ the module

	@Test
	void theModuleIsFlintAndSteelAndKnowsItsPlaceBeforeItIsInitialised() {
		ArmAccessoryModule m = new FlintAccessory();
		assertEquals("flint", m.id());
		assertEquals(FlintAccessory.ID, m.id());
		assertEquals(0, m.maxMode(3), "it has no mode");
		assertNotNull(m.listener());
		assertTrue(m.describe(1).contains("fire lasts 3 s"), "it can be described before init");
		m.load(Map.of("ignite-seconds", List.of(1, 2, 3)));
		assertTrue(m.describe(3).contains("fire lasts 3 s"), "and a reload changes what it says");
		assertTrue(m.describe(2).contains("2 s"));
		m.switchOffAll();
		m.tick();
	}
}
