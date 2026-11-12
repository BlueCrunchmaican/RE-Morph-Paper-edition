package dev.morphreborn.paper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.morphreborn.paper.nbt.Snbt;
import dev.morphreborn.paper.variant.VariantCapture;
import dev.morphreborn.paper.variant.VariantRules;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class VariantFilterTest {
	private static VariantCapture capture(boolean babies) {
		YamlConfiguration variants = YamlConfiguration.loadConfiguration(
				new InputStreamReader(VariantFilterTest.class.getResourceAsStream("/variants.yml"), StandardCharsets.UTF_8));
		YamlConfiguration config = new YamlConfiguration();
		config.set("morphs.allow-baby-morphs", babies);
		config.set("morphs.disabled-types", java.util.List.of("minecraft:armor_stand", "othermod:*"));
		return new VariantCapture(VariantRules.load(variants, config), Logger.getLogger("test"));
	}

	@Test
	void parsesVanillaStyleSnbt() {
		Map<String, Object> tag = Snbt.parseCompound("{Health:8.0f,Color:14b,Air:300s,UUID:[I;1,2,3,4],Pos:[1.5d,64.0d,-3.25d],"
				+ "VillagerData:{level:2,profession:\"minecraft:farmer\",type:'minecraft:plains'},\"odd key\":\"a\\\"b\",big:5L,flag:true}");
		assertEquals((byte) 14, tag.get("Color"));
		assertEquals((short) 300, tag.get("Air"));
		assertEquals(8.0F, tag.get("Health"));
		assertArrayEquals(new int[] {1, 2, 3, 4}, (int[]) tag.get("UUID"));
		assertEquals("a\"b", tag.get("odd key"));
		assertEquals(5L, tag.get("big"));
		assertEquals((byte) 1, tag.get("flag"));
		assertEquals("minecraft:farmer", ((Map<?, ?>) tag.get("VillagerData")).get("profession"));
	}

	@Test
	void roundTripIsCanonical() {
		String a = Snbt.write(Snbt.parseCompound("{b:1,a:{z:2b,y:[1.0f,2.0f]}}"));
		String b = Snbt.write(Snbt.parseCompound("{ a : { y:[1.0f, 2.0f], z:2b }, b:1 }"));
		assertEquals(a, b);
		assertEquals("{a:{y:[1.0f,2.0f],z:2b},b:1}", a);
		assertEquals(a, Snbt.write(Snbt.parseCompound(a)));
	}

	@Test
	void rejectsGarbage() {
		assertThrows(IllegalArgumentException.class, () -> Snbt.parseCompound("{a:"));
		assertThrows(IllegalArgumentException.class, () -> Snbt.parseCompound("[1,2]"));
		assertThrows(IllegalArgumentException.class, () -> Snbt.parseCompound("{a:1}}"));
	}

	@Test
	void sheepColoursAreDifferentVariantsButHealthIsIgnored() {
		VariantCapture c = capture(true);
		String white = c.filter("minecraft:sheep", "{Health:8.0f,Color:0b,Sheared:0b,Motion:[0.0d,0.0d,0.0d],UUID:[I;1,2,3,4]}");
		String whiteHurt = c.filter("minecraft:sheep", "{Health:2.0f,Color:0b,Sheared:0b,Motion:[0.1d,0.0d,0.0d],UUID:[I;9,9,9,9]}");
		String black = c.filter("minecraft:sheep", "{Health:8.0f,Color:15b,Sheared:0b}");
		assertEquals("{Color:0b,Sheared:0b}", white);
		assertEquals(white, whiteHurt);
		assertNotEquals(white, black);
	}

	@Test
	void babiesAreNormalised() {
		VariantCapture c = capture(true);
		String baby1 = c.filter("minecraft:cow", "{Age:-23000,variant:\"minecraft:temperate\"}");
		String baby2 = c.filter("minecraft:cow", "{Age:-5,variant:\"minecraft:temperate\"}");
		String adult = c.filter("minecraft:cow", "{Age:1200,variant:\"minecraft:temperate\"}");
		assertEquals(baby1, baby2);
		assertNotEquals(baby1, adult);
		assertTrue(baby1.contains("Age:-24000") && baby1.contains("AgeLocked:1b"));
		assertEquals("{variant:\"minecraft:temperate\"}", adult);
		String babyZombie = c.filter("minecraft:zombie", "{IsBaby:1b,CanBreakDoors:0b}");
		assertTrue(babyZombie.contains("IsBaby:1b"));
		assertEquals("{variant:\"minecraft:temperate\"}", capture(false).filter("minecraft:cow", "{Age:-23000,variant:\"minecraft:temperate\"}"));
	}

	@Test
	void villagerProfessionsAndCustomNames() {
		VariantCapture c = capture(true);
		String farmer = c.filter("minecraft:villager", "{VillagerData:{level:1,profession:\"minecraft:farmer\",type:\"minecraft:plains\"},Gossips:[],Xp:3}");
		String smith = c.filter("minecraft:villager", "{VillagerData:{level:1,profession:\"minecraft:weaponsmith\",type:\"minecraft:plains\"},Xp:9}");
		assertNotEquals(farmer, smith);
		assertTrue(!farmer.contains("Xp") && !farmer.contains("Gossips"));
		assertTrue(c.filter("minecraft:pig", "{CustomName:\"Bob\"}").contains("Bob"));
	}

	@Test
	void pandaKeepsOnlyVisibleGene() {
		VariantCapture c = capture(true);
		assertEquals("{HiddenGene:\"normal\",MainGene:\"normal\"}", c.filter("minecraft:panda", "{MainGene:\"brown\",HiddenGene:\"lazy\"}"));
		assertEquals("{HiddenGene:\"brown\",MainGene:\"brown\"}", c.filter("minecraft:panda", "{MainGene:\"brown\",HiddenGene:\"brown\"}"));
		assertEquals("{HiddenGene:\"normal\",MainGene:\"lazy\"}", c.filter("minecraft:panda", "{MainGene:\"lazy\",HiddenGene:\"weak\"}"));
	}

	@Test
	void creaturesWithLooks() {
		VariantRules r = capture(true).rules();
		// a look key in a default mob's save data
		assertTrue(VariantCapture.hasLooks(r, "minecraft:sheep", "{Health:8.0f,Color:0b,Sheared:0b}"));
		assertTrue(VariantCapture.hasLooks(r, "minecraft:creeper", "{powered:0b,Fuse:30s}"));
		assertTrue(VariantCapture.hasLooks(r, "minecraft:villager", "{Age:0,VillagerData:{level:1,profession:\"minecraft:none\",type:\"minecraft:plains\"}}"));
		// babies only
		assertTrue(VariantCapture.hasLooks(r, "minecraft:zombie", "{IsBaby:0b,CanBreakDoors:0b}"));
		assertTrue(VariantCapture.hasLooks(r, "minecraft:polar_bear", "{Age:0,AngerTime:0}"));
		// one look: no look key, no babies; custom names don't count
		assertTrue(!VariantCapture.hasLooks(r, "minecraft:iron_golem", "{PlayerCreated:0b,CustomName:\"Bob\"}"));
		assertTrue(!VariantCapture.hasLooks(r, "minecraft:blaze", "{Health:20.0f}"));
		// never a baby in normal play
		assertTrue(!VariantCapture.hasLooks(r, "minecraft:wandering_trader", "{Age:0,DespawnDelay:0}"));
		// tadpoles drop Age (it's how far they've grown, not babyhood)
		assertTrue(!VariantCapture.hasLooks(r, "minecraft:tadpole", "{Age:0}"));
		// a per-type key: the sulfur cube's block, though an empty cube doesn't save it
		assertTrue(VariantCapture.hasLooks(r, "minecraft:sulfur_cube", "{Size:1}") && VariantCapture.hasLooks(r, "minecraft:sulfur_cube", null));
		// players: every skin
		assertTrue(VariantCapture.hasLooks(r, "minecraft:player", null));
		// no babies allowed: a polar bear has one look
		assertTrue(!VariantCapture.hasLooks(capture(false).rules(), "minecraft:polar_bear", "{Age:0,AngerTime:0}"));
	}

	@Test
	void collapsedTypesHaveOneLook() {
		YamlConfiguration variants = YamlConfiguration.loadConfiguration(
				new InputStreamReader(VariantFilterTest.class.getResourceAsStream("/variants.yml"), StandardCharsets.UTF_8));
		YamlConfiguration config = new YamlConfiguration();
		config.set("morphs.single-variant-types", java.util.List.of("sheep"));
		VariantRules r = VariantRules.load(variants, config);
		assertTrue(!VariantCapture.hasLooks(r, "minecraft:sheep", "{Color:0b,Sheared:0b,Age:0}"));
		assertTrue(VariantCapture.hasLooks(r, "minecraft:cow", "{Age:0,variant:\"minecraft:temperate\"}"));
	}

	@Test
	void disabledTypesSupportWildcards() {
		VariantRules rules = capture(true).rules();
		assertTrue(rules.isDisabled("minecraft:armor_stand"));
		assertTrue(rules.isDisabled("othermod:anything"));
		assertTrue(!rules.isDisabled("minecraft:pig"));
	}
}
