package dev.morphreborn.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arm dump (dev/ArmDump) runs with no client: every form's geometry is run into a recorder, so the dump the pack
 * generator reads can be made from here. With {@code MORPHREBORN_DUMPARMS=<dir>} set the whole dump is written there
 * (that is how tools/gen_arm_pack.py gets its input); without it a small one goes to a temporary folder, and what is
 * checked is the same either way: every built form has all three tiers, every pose has quads, and every number is finite.
 */
class ArmDumpTest {
	@Test
	void dumpsEveryBuiltFormWithAllItsPoses() throws IOException {
		String env = System.getenv(ArmDump.ENV);
		Path dir = env != null && !env.isBlank() ? Path.of(env.strip()) : Files.createTempDirectory("arm-dump");
		String said = ArmDump.write(dir);
		System.out.println(said);
		for (String form : ArmDump.FORMS) {
			Path file = dir.resolve(form + ".json");
			assertTrue(Files.isRegularFile(file), form + " was not written: " + said);
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			assertEquals(form, root.get("form").getAsString());
			JsonArray tiers = root.getAsJsonArray("tiers");
			assertEquals(3, tiers.size(), form + " tiers");
			boolean bow = root.get("bow").getAsBoolean();
			for (JsonElement t : tiers) {
				JsonArray poses = t.getAsJsonObject().getAsJsonArray("poses");
				// 12 writhe, fp, 5 forming, 3 low, gui = 22 (+ 7 draws for the bow, + the cast for the whip, + the crossbow's empty groove: 12 writhe,
				// 3 low, gui, 5 forming stages, and its 3 pull stages, + the trident's two drawn throws)
				int extra = form.equals("whip") ? 1 : form.equals("crossbow") ? 12 + 3 + 1 + 5 + 3 : form.equals("trident") ? 2 : 0;
				assertEquals((bow ? 29 : 22) + extra, poses.size(), form + " poses");
				for (JsonElement p : poses) {
					JsonObject pose = p.getAsJsonObject();
					JsonArray quads = pose.getAsJsonArray("quads");
					String id = pose.get("id").getAsString();
					// a form at the very start of FORM has next to nothing drawn, anything else is a whole tool
					if (!id.equals("f0") && !id.equals("e_f0")) assertTrue(quads.size() > 20, form + " " + id + " has only " + quads.size() + " quads");
					for (JsonElement q : quads) {
						JsonArray a = q.getAsJsonArray();
						assertEquals(13, a.size());
						for (JsonElement v : a) assertTrue(Double.isFinite(v.getAsDouble()), form + " " + id + " has a bad number");
					}
				}
			}
		}
		for (String name : List.of("_sleeve.json")) {
			JsonObject root = JsonParser.parseString(Files.readString(dir.resolve(name))).getAsJsonObject();
			assertEquals(3, root.getAsJsonArray("tiers").size(), name);
		}
	}

	/** The pack's use states (2026-11-16): the crossbow's empty groove has less drawn than the loaded one and its bolt grows through the pull poses; the trident's drawn throw differs from its rest. */
	@Test
	void theUseStatesDifferFromTheRestingPoses() throws IOException {
		Path dir = Files.createTempDirectory("arm-dump-use");
		ArmDump.write(dir, List.of("crossbow", "trident"));
		JsonArray crossbow = JsonParser.parseString(Files.readString(dir.resolve("crossbow.json"))).getAsJsonObject().getAsJsonArray("tiers");
		JsonArray trident = JsonParser.parseString(Files.readString(dir.resolve("trident.json"))).getAsJsonObject().getAsJsonArray("tiers");
		for (int t = 0; t < 3; t++) {
			int loaded = quads(crossbow.get(t), "w00"), empty = quads(crossbow.get(t), "e_w00");
			assertTrue(empty < loaded, "tier " + (t + 1) + ": an empty groove draws less than a loaded one (" + empty + " vs " + loaded + ")");
			int p0 = quads(crossbow.get(t), "pull0"), p1 = quads(crossbow.get(t), "pull1"), p2 = quads(crossbow.get(t), "pull2");
			assertTrue(empty <= p0 && p0 <= p1 && p1 <= p2 && p2 <= loaded, "tier " + (t + 1) + ": the bolt grows with the pull: " + empty + " " + p0 + " " + p1 + " " + p2 + " " + loaded);
			// the stages are different poses (the string cocks and the bolt lengthens even where no new quad is added)
			String[] stages = {"e_w00", "pull0", "pull1", "pull2", "w00"};
			for (int s = 1; s < stages.length; s++) {
				assertTrue(!coords(crossbow.get(t), stages[s - 1]).equals(coords(crossbow.get(t), stages[s])), "tier " + (t + 1) + ": " + stages[s - 1] + " and " + stages[s] + " are the same pose");
			}
			// forming with the groove empty (right after a shot): the five stages have no bolt, so the arm doesn't pop a bolt in and out
			for (int n = 0; n < 5; n++) {
				assertTrue(quads(crossbow.get(t), "e_f" + n) <= quads(crossbow.get(t), "f" + n), "tier " + (t + 1) + ": forming stage " + n + " of an empty groove has no more than a loaded one");
			}
			assertTrue(!coords(crossbow.get(t), "e_f4").equals(coords(crossbow.get(t), "f4")), "tier " + (t + 1) + ": the empty groove's last forming stage differs from the loaded one");
			// a drawn throw is the same fork with the side prongs spread: not the resting pose's points
			assertTrue(!coords(trident.get(t), "pullg").equals(coords(trident.get(t), "w00")), "tier " + (t + 1) + ": a drawn throw differs from rest");
			assertTrue(!coords(trident.get(t), "pullt").equals(coords(trident.get(t), "pullg")), "tier " + (t + 1) + ": the raised pose is in its own frame");
		}
	}

	private static JsonObject pose(JsonElement tier, String id) {
		for (JsonElement p : tier.getAsJsonObject().getAsJsonArray("poses")) {
			if (p.getAsJsonObject().get("id").getAsString().equals(id)) return p.getAsJsonObject();
		}
		throw new AssertionError("no pose " + id);
	}

	private static int quads(JsonElement tier, String id) {
		return pose(tier, id).getAsJsonArray("quads").size();
	}

	private static String coords(JsonElement tier, String id) {
		return pose(tier, id).getAsJsonArray("quads").toString();
	}
}
