package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.morphreborn.paper.arm.look.ArmEquipmentView;
import dev.morphreborn.paper.arm.look.Bedrock;
import dev.morphreborn.paper.arm.rules.ArmTiers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The resource pack's arm forms (tools/gen_arm_pack.py, from the client's geometry dump) and the rewriter's rules: what
 * players without the mod see an arm form as. The pack is checked as files, the way the game reads them: every built form
 * has an item definition and every model it names, at every tier; the animated textures' frames divide their images;
 * the definitions never play the equip bob; nothing overrides a vanilla item or the item atlas; and the forming float the
 * rewriter sends is one the definition understands.
 */
class ArmPackTest {
	private static final Path PACK = Path.of("..", "resourcepack", "assets", "morphreborn");
	private static final Path ARM_ITEMS = PACK.resolve("items").resolve("arm");
	private static final Path ARM_MODELS = PACK.resolve("models").resolve("item").resolve("arm");
	private static final Path ARM_TEXTURES = PACK.resolve("textures").resolve("item").resolve("arm");
	private static final Path CLIENT_GEOMETRY = Path.of("..", "fabric-client", "src", "main", "java", "dev", "morphreborn", "client", "arm", "geom");
	/** The client geometry class of each tool form: a form is built when its class is in the client. */
	private static final Map<String, String> GEOMETRY = Map.ofEntries(Map.entry("katana", "KatanaGeometry"), Map.entry("pickaxe", "PickaxeGeometry"),
			Map.entry("axe", "WarAxeGeometry"), Map.entry("shovel", "SpadeGeometry"), Map.entry("sickle", "SickleGeometry"), Map.entry("bow", "RecurveGeometry"),
			Map.entry("whip", "WhipGeometry"), Map.entry("spear", "SpearGeometry"), Map.entry("mace", "MaceGeometry"), Map.entry("trident", "TridentGeometry"),
			Map.entry("crossbow", "CrossbowGeometry"), Map.entry("claws", "ClawsGeometry"));

	/** The tool forms the client can draw (all of them when the client sources aren't beside the plugin). */
	private static List<String> builtForms() {
		List<String> out = new ArrayList<>();
		for (String form : ArmTiers.TOOL_FORMS) {
			if (!Files.isDirectory(CLIENT_GEOMETRY) || Files.isRegularFile(CLIENT_GEOMETRY.resolve(GEOMETRY.get(form) + ".java"))) out.add(form);
		}
		return out;
	}

	private static JsonObject json(Path file) throws IOException {
		return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
	}

	/** Every {@code "model": "<id>"} of an item definition (nested anywhere), as pack-relative model names ("katana/t1_body"). */
	private static void modelsOf(JsonElement e, Set<String> out) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("model") && o.get("model").isJsonPrimitive()) {
				String id = o.get("model").getAsString();
				assertTrue(id.startsWith("morphreborn:item/arm/"), "a model outside the arm folder: " + id);
				out.add(id.substring("morphreborn:item/arm/".length()));
			}
			for (Map.Entry<String, JsonElement> c : o.entrySet()) modelsOf(c.getValue(), out);
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) modelsOf(c, out);
		}
	}

	@Test
	void everyBuiltFormHasItsItemAndItsModelsAtEveryTier() throws IOException {
		List<String> forms = builtForms();
		assertTrue(forms.size() >= 2, "the katana and pickaxe at least are built: " + forms);
		for (String form : forms) {
			Path item = ARM_ITEMS.resolve(form + ".json");
			assertTrue(Files.isRegularFile(item), "no item definition for " + form);
			Set<String> models = new TreeSet<>();
			modelsOf(json(item), models);
			for (int tier = 1; tier <= 3; tier++) {
				String t = "t" + tier;
				List<String> want = new ArrayList<>(List.of(form + "/" + t + "_body", form + "/" + t + "_fp", form + "/" + t + "_icon",
						// the crossbow's claw closes round its pistol grip, so its arm shell is its own
						(form.equals("crossbow") ? "sleeve/crossbow_" : "sleeve/") + t));
				for (int n = 0; n < 5; n++) want.add(form + "/" + t + "_f" + n);
				for (int n = 1; n <= 3; n++) want.add(form + "/" + t + "_low" + n);
				if (form.equals("bow")) for (int n = 0; n <= 6; n++) want.add(form + "/" + t + "_pull" + n);
				if (form.equals("whip")) want.add(form + "/" + t + "_cast");
				if (form.equals("crossbow")) {
					// the empty groove's look and the three pull stages (2026-11-16)
					want.addAll(List.of(form + "/" + t + "_e_body", form + "/" + t + "_e_icon"));
					for (int n = 1; n <= 3; n++) want.add(form + "/" + t + "_e_low" + n);
					for (int n = 0; n <= 2; n++) want.add(form + "/" + t + "_pull" + n);
				}
				if (form.equals("trident")) want.addAll(List.of(form + "/" + t + "_drawn", form + "/" + t + "_raised", "sleeve/" + t + "_raised"));
				for (String name : want) {
					// forming's first pose can be empty (nothing has grown yet): that is the definition's "empty" model, not a file
					if (name.endsWith("_f0") && !models.contains(name)) continue;
					// first person is the tool alone since 7.6.0: no forearm model, so none to use (the body is the whole of it)
					if (name.endsWith("_fp") && !models.contains(name) && !Files.exists(ARM_ITEMS.getParent().getParent().resolve("models/item/arm/" + name + ".json"))) continue;
					assertTrue(models.contains(name), form + " tier " + tier + ": the definition doesn't use " + name);
				}
				// at least one writhe keyframe per tier
				assertTrue(models.stream().anyMatch(m -> m.startsWith(form + "/" + t + "_w")), form + " tier " + tier + " has no writhe frames");
			}
			for (String m : models) {
				Path file = ARM_MODELS.resolve(m + ".json");
				assertTrue(Files.isRegularFile(file), form + ": the definition names a model that isn't there: " + m);
				checkModel(file);
			}
		}
		// the lantern's lure, the model the server's item display shows
		Path lure = ARM_ITEMS.resolve("lantern_lure.json");
		assertTrue(Files.isRegularFile(lure), "no lantern_lure item definition");
		Set<String> lureModels = new TreeSet<>();
		modelsOf(json(lure), lureModels);
		assertFalse(lureModels.isEmpty(), "the lure's definition names no model");
		for (String m : lureModels) checkModel(ARM_MODELS.resolve(m + ".json"));
	}

	/** A model the game will take: within the element limits and the item atlas's frame, with a parent that is the pack's own. */
	private static void checkModel(Path file) throws IOException {
		JsonObject model = json(file);
		String parent = model.get("parent").getAsString();
		assertTrue(parent.startsWith("morphreborn:item/arm/"), file + ": a parent outside the pack: " + parent);
		JsonObject parentModel = json(ARM_MODELS.resolve(parent.substring("morphreborn:item/arm/".length()) + ".json"));
		assertFalse(parentModel.has("parent"), "the display parents have no parent of their own (a vanilla one could skew them)");
		JsonObject textures = parentModel.getAsJsonObject("textures");
		JsonArray elements = model.getAsJsonArray("elements");
		assertTrue(elements.size() >= 1 && elements.size() <= 120, file + " has " + elements.size() + " elements");
		for (JsonElement el : elements) {
			JsonObject e = el.getAsJsonObject();
			for (String key : new String[] {"from", "to"}) {
				for (JsonElement v : e.getAsJsonArray(key)) {
					double d = v.getAsDouble();
					assertTrue(d >= -16 && d <= 32, file + ": " + key + " leaves -16..32: " + d);
				}
			}
			if (e.has("light_emission")) assertTrue(e.get("light_emission").getAsInt() >= 0 && e.get("light_emission").getAsInt() <= 15);
			assertTrue(e.getAsJsonObject("faces").size() >= 1, file + ": an element with no faces");
			for (Map.Entry<String, JsonElement> f : e.getAsJsonObject("faces").entrySet()) {
				JsonObject face = f.getValue().getAsJsonObject();
				String ref = face.get("texture").getAsString();
				assertTrue(ref.startsWith("#") && textures.has(ref.substring(1)), file + ": a face's texture isn't in the parent: " + ref);
				for (JsonElement u : face.getAsJsonArray("uv")) {
					double d = u.getAsDouble();
					// a face may not read past its frame: it would show the next frame of the animation
					assertTrue(d >= 0 && d <= 16, file + ": a uv outside the frame: " + d);
				}
			}
		}
	}

	@Test
	void everyAnimationFrameDividesItsImage() throws IOException {
		assertTrue(Files.isDirectory(ARM_TEXTURES), "no arm textures");
		int checked = 0;
		try (Stream<Path> files = Files.list(ARM_TEXTURES)) {
			for (Path png : files.filter(p -> p.toString().endsWith(".png")).toList()) {
				BufferedImage image = ImageIO.read(png.toFile());
				assertTrue(image != null, png + " isn't a png");
				Path meta = Path.of(png + ".mcmeta");
				assertTrue(Files.isRegularFile(meta), png + " has no animation");
				JsonObject animation = json(meta).getAsJsonObject("animation");
				int w = animation.has("width") ? animation.get("width").getAsInt() : image.getWidth();
				int h = animation.has("height") ? animation.get("height").getAsInt() : image.getWidth();
				assertTrue(w > 0 && h > 0, png + ": no frame size");
				assertEquals(0, image.getWidth() % w, png + ": the frame width doesn't divide the image");
				assertEquals(0, image.getHeight() % h, png + ": the frame height doesn't divide the image");
				int frames = (image.getWidth() / w) * (image.getHeight() / h);
				assertTrue(frames >= 2, png + " has no animation to speak of");
				assertTrue(animation.get("frametime").getAsInt() >= 1);
				if (animation.has("frames")) {
					for (JsonElement f : animation.getAsJsonArray("frames")) {
						int index = f.isJsonObject() ? f.getAsJsonObject().get("index").getAsInt() : f.getAsInt();
						assertTrue(index >= 0 && index < frames, png + ": frame " + index + " isn't in the image");
					}
				}
				checked++;
			}
		}
		assertTrue(checked >= 4, "the static, red static, glow static and vein textures are there: " + checked);
	}

	@Test
	void theDefinitionsNeverPlayTheEquipBob() throws IOException {
		try (Stream<Path> files = Files.list(ARM_ITEMS)) {
			List<Path> all = files.filter(p -> p.toString().endsWith(".json")).toList();
			assertFalse(all.isEmpty());
			for (Path p : all) {
				JsonObject def = json(p);
				assertTrue(def.has("hand_animation_on_swap"), p + " leaves the equip bob on");
				assertFalse(def.get("hand_animation_on_swap").getAsBoolean(), p + " plays the equip bob");
			}
		}
	}

	@Test
	void nothingOverridesAVanillaItemOrTheItemAtlas() throws IOException {
		Path minecraft = PACK.resolveSibling("minecraft");
		Path items = minecraft.resolve("items");
		if (Files.isDirectory(items)) {
			try (Stream<Path> files = Files.walk(items)) {
				assertEquals(0, files.filter(Files::isRegularFile).count(), "files under assets/minecraft/items override vanilla items");
			}
		}
		// the head pack's generator owns the atlas and already includes every texture folder 'item': nothing arm in it
		Path atlas = minecraft.resolve("atlases").resolve("items.json");
		assertTrue(Files.isRegularFile(atlas));
		String text = Files.readString(atlas);
		assertFalse(text.contains("item/arm"), "the arm textures were added to the item atlas by hand");
		assertFalse(text.contains("textures/item"), "the arm textures were added to the item atlas by hand");
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		for (JsonElement s : root.getAsJsonArray("sources")) {
			JsonObject source = s.getAsJsonObject();
			assertEquals("minecraft:single", source.get("type").getAsString(), "the atlas's sources are the head pack's: single textures");
		}
		// and nothing of ours lives under the vanilla namespace's models or textures either
		assertFalse(Files.exists(minecraft.resolve("models")), "arm models are in the morphreborn namespace");
	}

	@Test
	void theSkinMasksAreCopiedFromTheClient() throws IOException {
		Path client = Path.of("..", "fabric-client", "src", "main", "resources", "assets", "morphreborn", "textures", "skin");
		for (String name : List.of("morphskin_dissolve.png", "morphskin_glow.png")) {
			Path ours = PACK.resolve("textures").resolve("skin").resolve(name);
			assertTrue(Files.isRegularFile(ours), name + " isn't in the pack");
			if (Files.isDirectory(client)) assertEquals(Files.mismatch(client.resolve(name), ours), -1L, name + " differs from the client's");
		}
	}

	@Test
	void theFormingFloatSelectsEachStepOfTheGrowth() throws IOException {
		JsonObject def = json(ARM_ITEMS.resolve("katana.json"));
		// the first tier's forming dispatch: custom_model_data float 0, entries 1.0 .. 1.8, the finished arm as its fallback
		JsonObject forming = findDispatch(def, "minecraft:custom_model_data");
		assertTrue(forming != null && forming.get("index").getAsInt() == 0, "no forming dispatch on custom_model_data[0]");
		List<Double> thresholds = new ArrayList<>();
		for (JsonElement e : forming.getAsJsonArray("entries")) thresholds.add(e.getAsJsonObject().get("threshold").getAsDouble());
		assertEquals(5, thresholds.size());
		assertEquals(1.0, thresholds.get(0), 1e-9, "no float (0) must fall below the first threshold: that is the finished arm");
		// FORM is 40 ticks long; five points through it pick five different models
		List<Integer> picked = new ArrayList<>();
		for (int tick : new int[] {0, 9, 17, 26, 34}) {
			float value = ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_FORMING, tick, ArmProtocol.FORM_TICKS);
			assertTrue(value >= 1F && value < 2F, "forming is 1 + progress: " + value);
			picked.add(pick(thresholds, value));
		}
		assertEquals(List.of(0, 1, 2, 3, 4), picked);
		// a quick-form is 20 ticks; its steps grow too
		int last = -1;
		for (int tick : new int[] {0, 8, 15}) {
			int at = pick(thresholds, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_QUICKFORM, tick, ArmProtocol.QUICKFORM_TICKS));
			assertTrue(at > last, "quick-form steps grow");
			last = at;
		}
		// a retract shrinks along the same models; a finished arm has no float
		assertTrue(pick(thresholds, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_RETRACTING, 0, ArmProtocol.RETRACT_TICKS))
				> pick(thresholds, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_RETRACTING, 25, ArmProtocol.RETRACT_TICKS)));
		assertEquals(0F, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_FORMED, 100, 0));
		assertEquals(0F, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_NONE, 0, 0));
		// a reshape's old form is still collapsing for its first 7/16: the new one starts at the first model
		assertEquals(0, pick(thresholds, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_RESHAPING, 6, ArmProtocol.RESHAPE_TICKS)));
		assertEquals(4, pick(thresholds, ArmEquipmentView.Rules.formingValue(ArmProtocol.STATE_RESHAPING, 30, ArmProtocol.RESHAPE_TICKS)));
	}

	/** The index of the entry the game picks: the last whose threshold is at or below {@code value}. */
	private static int pick(List<Double> thresholds, float value) {
		int at = -1;
		for (int i = 0; i < thresholds.size(); i++) if (thresholds.get(i) <= value) at = i;
		assertNotEquals(-1, at, "below the first threshold: the arm would draw as finished");
		return at;
	}

	/** The first range_dispatch of the given property found anywhere in a definition. */
	private static JsonObject findDispatch(JsonElement e, String property) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("type") && o.get("type").getAsString().equals("minecraft:range_dispatch") && o.has("property")
					&& o.get("property").getAsString().equals(property)) return o;
			for (Map.Entry<String, JsonElement> c : o.entrySet()) {
				JsonObject found = findDispatch(c.getValue(), property);
				if (found != null) return found;
			}
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) {
				JsonObject found = findDispatch(c, property);
				if (found != null) return found;
			}
		}
		return null;
	}

	@Test
	void theDefinitionsReadTheTierFromTheThirdModelString() throws IOException {
		// ArmItemSpec's strings are ["morphreborn:arm", form, tier name]: the tier is index 2
		JsonObject def = json(ARM_ITEMS.resolve("katana.json"));
		JsonObject icon = def.getAsJsonObject("model").getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("model");
		assertEquals("minecraft:select", icon.get("type").getAsString());
		assertEquals("minecraft:custom_model_data", icon.get("property").getAsString());
		assertEquals(2, icon.get("index").getAsInt());
		Set<String> tiers = new TreeSet<>();
		for (JsonElement c : icon.getAsJsonArray("cases")) tiers.add(c.getAsJsonObject().get("when").getAsString());
		assertEquals(new TreeSet<>(List.of(ArmTiers.tierName(1), ArmTiers.tierName(2), ArmTiers.tierName(3))), tiers);
		assertEquals(List.of("morphreborn:arm", "katana", "diamond"), ArmEquipmentView.Rules.modelStrings("katana", 2));
	}

	@Test
	void theBowReadsItsDrawAndTheWhipItsCast() throws IOException {
		if (builtForms().contains("bow")) {
			JsonObject def = json(ARM_ITEMS.resolve("bow.json"));
			JsonObject duration = findDispatch(def, "minecraft:use_duration");
			assertTrue(duration != null, "the bow has no draw dispatch");
			assertEquals(0.05, duration.get("scale").getAsDouble(), 1e-9);
			JsonObject cycle = findDispatch(def, "minecraft:use_cycle");
			assertTrue(cycle != null && cycle.get("period").getAsDouble() == 4.0, "full draw alternates two poses every 4 ticks");
			assertTrue(def.toString().contains("minecraft:using_item"));
		}
		if (builtForms().contains("whip")) assertTrue(json(ARM_ITEMS.resolve("whip.json")).toString().contains("minecraft:fishing_rod/cast"));
	}

	/** Every dispatch of a given kind found anywhere in a definition (select and condition too: {@code type} names the node, {@code property} what it reads). */
	private static void nodesReading(JsonElement e, String type, String property, List<JsonObject> out) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("type") && o.get("type").getAsString().equals(type) && o.has("property") && o.get("property").getAsString().equals(property)) out.add(o);
			for (Map.Entry<String, JsonElement> c : o.entrySet()) nodesReading(c.getValue(), type, property, out);
		} else if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) nodesReading(c, type, property, out);
		}
	}

	@Test
	void theCrossbowReadsItsChargedStateAndItsPull() throws IOException {
		if (!builtForms().contains("crossbow")) return;
		JsonObject def = json(ARM_ITEMS.resolve("crossbow.json"));
		// the server charges the arm with a marker arrow and clears it after a shot: the charged state is the loaded look, nothing the empty groove's
		List<JsonObject> charge = new ArrayList<>();
		nodesReading(def, "minecraft:select", "minecraft:charge_type", charge);
		assertFalse(charge.isEmpty(), "the crossbow doesn't read its charged state");
		for (JsonObject select : charge) {
			Set<String> when = new TreeSet<>();
			for (JsonElement c : select.getAsJsonArray("cases")) when.add(c.getAsJsonObject().get("when").getAsString());
			assertEquals(new TreeSet<>(List.of("arrow", "rocket")), when, "a loaded crossbow, whatever it holds, is the loaded look");
			assertTrue(select.has("fallback"), "an empty groove needs a look");
		}
		// the pull while the game charges an empty one: vanilla's own property and thresholds, three stages
		List<JsonObject> pull = new ArrayList<>();
		nodesReading(def, "minecraft:range_dispatch", "minecraft:crossbow/pull", pull);
		assertFalse(pull.isEmpty(), "the crossbow doesn't read its pull");
		for (JsonObject p : pull) {
			JsonArray entries = p.getAsJsonArray("entries");
			assertEquals(2, entries.size());
			assertEquals(0.58, entries.get(0).getAsJsonObject().get("threshold").getAsDouble(), 1e-9);
			assertEquals(1.0, entries.get(1).getAsJsonObject().get("threshold").getAsDouble(), 1e-9);
		}
		List<JsonObject> using = new ArrayList<>();
		nodesReading(def, "minecraft:condition", "minecraft:using_item", using);
		assertFalse(using.isEmpty());
	}

	@Test
	void theTridentReadsItsDrawnThrow() throws IOException {
		if (!builtForms().contains("trident")) return;
		JsonObject def = json(ARM_ITEMS.resolve("trident.json"));
		List<JsonObject> using = new ArrayList<>();
		nodesReading(def, "minecraft:condition", "minecraft:using_item", using);
		assertEquals(2, using.size(), "first person and third person each read the draw");
		// third person's raised pose is drawn with the game's own throwing block (ArmTransforms.TRIDENT_THROW_TP_*), which its fork is placed for
		JsonObject raised = json(ARM_MODELS.resolve("raised.json"));
		JsonObject right = raised.getAsJsonObject("display").getAsJsonObject("thirdperson_righthand");
		assertEquals(List.of(0.0, 90.0, 180.0), doubles(right.getAsJsonArray("rotation")));
		assertEquals(List.of(8.0, -17.0, 9.0), doubles(right.getAsJsonArray("translation")));
		JsonObject left = raised.getAsJsonObject("display").getAsJsonObject("thirdperson_lefthand");
		assertEquals(List.of(-8.0, -17.0, 9.0), doubles(left.getAsJsonArray("translation")));
		assertPreFlipped(raised.getAsJsonObject("display"), "thirdperson");
	}

	/**
	 * A left-hand block is the right one pre-flipped (2026-11-16): the game's left-hand fix (ItemTransform.apply) negates a left block's x translation
	 * and its y and z rotation, so after it the item lands exactly where the right-hand block puts it, which is what the models and the arm shell are
	 * fitted for (the shell is placed by the right block alone). Compared as the game reads them: the fix applied to the left block gives the right block.
	 */
	private static void assertPreFlipped(JsonObject display, String context) {
		List<Double> rr = doubles(display.getAsJsonObject(context + "_righthand").getAsJsonArray("rotation"));
		List<Double> lr = doubles(display.getAsJsonObject(context + "_lefthand").getAsJsonArray("rotation"));
		List<Double> rt = doubles(display.getAsJsonObject(context + "_righthand").getAsJsonArray("translation"));
		List<Double> lt = doubles(display.getAsJsonObject(context + "_lefthand").getAsJsonArray("translation"));
		assertEquals(rr.get(0), lr.get(0), 1e-9, context + " rotation x is not touched by the fix");
		assertEquals(rr.get(1), -lr.get(1), 1e-9, context + " rotation y after the left-hand fix");
		// 180 and -180 are the same half turn
		assertEquals(Math.sin(Math.toRadians(rr.get(2))), Math.sin(Math.toRadians(-lr.get(2))), 1e-9, context + " rotation z after the left-hand fix");
		assertEquals(Math.cos(Math.toRadians(rr.get(2))), Math.cos(Math.toRadians(-lr.get(2))), 1e-9, context + " rotation z after the left-hand fix");
		if (context.equals("thirdperson")) {
			assertEquals(rt.get(0), -lt.get(0), 1e-9, context + " translation x after the left-hand fix");
		} else {
			assertEquals(rt.get(0), lt.get(0), 1e-9, context + " translation x stays raw, as handheld's does");
		}
		assertEquals(rt.get(1), lt.get(1), 1e-9);
		assertEquals(rt.get(2), lt.get(2), 1e-9);
	}

	@Test
	void theCrossbowIsHeldAsVanillaHoldsOne() throws IOException {
		if (!builtForms().contains("crossbow")) return;
		JsonObject crossbow = json(ARM_MODELS.resolve("crossbow.json"));
		JsonObject display = crossbow.getAsJsonObject("display");
		assertEquals(List.of(-90.0, 0.0, -60.0), doubles(display.getAsJsonObject("thirdperson_righthand").getAsJsonArray("rotation")));
		assertEquals(List.of(-90.0, 0.0, 60.0), doubles(display.getAsJsonObject("thirdperson_lefthand").getAsJsonArray("rotation")));
		assertPreFlipped(display, "thirdperson");
		assertPreFlipped(display, "firstperson");
		assertEquals(List.of(-90.0, 0.0, -55.0), doubles(display.getAsJsonObject("firstperson_righthand").getAsJsonArray("rotation")));
		assertEquals(0.9, display.getAsJsonObject("thirdperson_righthand").getAsJsonArray("scale").get(0).getAsDouble(), 1e-9);
	}

	private static List<Double> doubles(JsonArray a) {
		List<Double> out = new ArrayList<>();
		for (JsonElement e : a) out.add(e.getAsDouble());
		return out;
	}

	@Test
	void theWritheReadsTheCompassWithNoTarget() throws IOException {
		JsonObject def = json(ARM_ITEMS.resolve("katana.json"));
		JsonObject compass = findDispatch(def, "minecraft:compass");
		assertTrue(compass != null, "no writhe dispatch");
		assertEquals("none", compass.get("target").getAsString());
		assertEquals(12.0, compass.get("scale").getAsDouble(), 1e-9, "twelve keyframes of the loop");
	}

	@Test
	void whoSeesThePackModel() {
		// arm models on, auto: only a viewer whose game loaded the pack, and not one that draws arms itself or is Bedrock
		assertTrue(ArmEquipmentView.Rules.seesPack(true, "auto", false, false, true));
		assertFalse(ArmEquipmentView.Rules.seesPack(true, "auto", false, false, false));
		assertFalse(ArmEquipmentView.Rules.seesPack(true, "auto", true, false, true), "a client that draws arms draws them itself");
		assertFalse(ArmEquipmentView.Rules.seesPack(true, "auto", false, true, true), "Bedrock never has the pack");
		// pack: everyone without the mod, loaded or not; off, or arm models off: nobody
		assertTrue(ArmEquipmentView.Rules.seesPack(true, "pack", false, false, false));
		assertFalse(ArmEquipmentView.Rules.seesPack(true, "off", false, false, true));
		assertFalse(ArmEquipmentView.Rules.seesPack(false, "auto", false, false, true));
		assertEquals("morphreborn:arm/katana", ArmEquipmentView.Rules.packModel("katana"));
		assertEquals("minecraft:netherite_sword", ArmEquipmentView.Rules.plainModel("katana", 3));
		assertEquals("minecraft:bow", ArmEquipmentView.Rules.plainModel("bow", 1));
	}

	@Test
	void withoutFloodgateNobodyIsBedrock() {
		// no Floodgate on the test class path: the answer is no, and asking never throws
		assertFalse(Bedrock.is(UUID.randomUUID()));
		Bedrock.forget(UUID.randomUUID());
	}

	@Test
	void everyFormsIconIsInsideTheSlot() throws IOException {
		for (String form : builtForms()) {
			for (int tier = 1; tier <= 3; tier++) {
				JsonObject model = json(ARM_MODELS.resolve(form).resolve("t" + tier + "_icon.json"));
				double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
				for (JsonElement el : model.getAsJsonArray("elements")) {
					for (double[] c : corners(el.getAsJsonObject())) {
						lo = Math.min(lo, Math.min(c[0], c[1]));
						hi = Math.max(hi, Math.max(c[0], c[1]));
					}
				}
				// a slot is 0..16 in x and y; the geometry's own fit keeps to 1..15 and boxes round it add a little
				assertTrue(lo > -2.5 && hi < 18.5, form + " tier " + tier + ": the icon strays from its slot: " + lo + ".." + hi);
			}
		}
	}

	/** An element's eight corners after its rotation (the game's rotationZYX about the origin: x first, then y, then z). */
	private static List<double[]> corners(JsonObject e) {
		double[] from = new double[3], to = new double[3];
		for (int i = 0; i < 3; i++) {
			from[i] = e.getAsJsonArray("from").get(i).getAsDouble();
			to[i] = e.getAsJsonArray("to").get(i).getAsDouble();
		}
		double[] origin = {0, 0, 0};
		double rx = 0, ry = 0, rz = 0;
		if (e.has("rotation")) {
			JsonObject r = e.getAsJsonObject("rotation");
			for (int i = 0; i < 3; i++) origin[i] = r.getAsJsonArray("origin").get(i).getAsDouble();
			rx = Math.toRadians(r.has("x") ? r.get("x").getAsDouble() : 0);
			ry = Math.toRadians(r.has("y") ? r.get("y").getAsDouble() : 0);
			rz = Math.toRadians(r.has("z") ? r.get("z").getAsDouble() : 0);
		}
		List<double[]> out = new ArrayList<>();
		for (int mask = 0; mask < 8; mask++) {
			double x = ((mask & 1) == 0 ? from[0] : to[0]) - origin[0];
			double y = ((mask & 2) == 0 ? from[1] : to[1]) - origin[1];
			double z = ((mask & 4) == 0 ? from[2] : to[2]) - origin[2];
			double y1 = y * Math.cos(rx) - z * Math.sin(rx), z1 = y * Math.sin(rx) + z * Math.cos(rx); // about x
			double x2 = x * Math.cos(ry) + z1 * Math.sin(ry), z2 = -x * Math.sin(ry) + z1 * Math.cos(ry); // about y
			double x3 = x2 * Math.cos(rz) - y1 * Math.sin(rz), y3 = x2 * Math.sin(rz) + y1 * Math.cos(rz); // about z
			out.add(new double[] {x3 + origin[0], y3 + origin[1], z2 + origin[2]});
		}
		return out;
	}
}
