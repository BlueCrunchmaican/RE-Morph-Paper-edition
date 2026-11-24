package dev.morphreborn.client.dev;

import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.morphreborn.client.arm.ArmClientModule;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.FormGeometry;
import dev.morphreborn.client.arm.geom.LureRenderer;
import dev.morphreborn.client.arm.geom.SheathGeometry;
import dev.morphreborn.client.arm.render.ArmLayer;
import dev.morphreborn.client.arm.render.Rope;
import dev.morphreborn.client.arm.render.SkinMesh;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development tool, not part of play: writes what every arm form looks like, as quads, for {@code tools/gen_arm_pack.py}
 * (the resource pack players without the mod see arm forms with). The form geometry is plain Java over {@link SkinMesh},
 * so nothing here needs a world, a window or a GPU: each form's geometry is run into a recording vertex consumer, once
 * per pose, and the quads are written out.
 * <ul>
 *   <li>{@code MORPHREBORN_DUMPARMS=<dir>} in a development environment writes the dump when the client starts;</li>
 *   <li>{@code /morphclient dumparms [dir]} does it from a dev client (default folder: {@code run/arm-dump});</li>
 *   <li>{@link #write(Path)} is the same thing for a unit test or a small main, with no client at all.</li>
 * </ul>
 * One file per form, {@code <form>.json}, with its tiers' poses; {@code _sleeve.json} (the third-person arm shell) and
 * {@code _lure.json} (the lantern's lure) beside them. Every quad is four corners in the geometry's own space (grip space
 * G in px for a tool, the arm's space A for the sleeve, the lure's own frame turned so world down is -Y) with the layer
 * it is drawn in (0 black, 1 red, 2 glow). The poses, per tier:
 * <ul>
 *   <li>{@code w00..w11}: third person at rest, 8 ticks apart (the writhe loop is 96 ticks long, so twelve of them
 *       cover it), the tail drooping a little differently in each;</li>
 *   <li>{@code fp}: first person at rest (with {@code w00}'s tail), so the forearm and fist the tool draws itself there
 *       are what it has over {@code w00};</li>
 *   <li>{@code f0..f4}: FORM's growth at the middle of each fifth of its timeline;</li>
 *   <li>{@code low1..low3}: thinning, starving and failing biomass;</li>
 *   <li>{@code gui}: the hotbar icon;</li>
 *   <li>the whip only: {@code cast}, the lash gathered up while a line is out;</li>
 *   <li>the bow only: {@code pull0..pull5}, the drawn bow at the powers a draw of 0, 4, 9, 14, 19 and 25 ticks has
 *       ({@code pull6}: the second pose of a full draw);</li>
 *   <li>the crossbow only (2026-11-16, the pack's use states): everything above is the loaded crossbow (the stack's charged
 *       projectile, which is what the groove's bolt follows), and {@code e_w00..e_w11}, {@code e_low1..e_low3} and
 *       {@code e_gui} and {@code e_f0..e_f4} (an arm forming or retracting with nothing in the groove) are the same with the groove empty ({@link ArmDraw#thrown}), {@code pull0..pull2} the bolt regrowing
 *       out of the stock at 35, 65 and 90 percent (what a vanilla pull while the game charges the empty
 *       weapon can show: the geometry has no draw of its own);</li>
 *   <li>the trident only: {@code pullg}, a throw drawn (the side prongs spread, the points lit) in grip space G as every
 *       other pose (the first-person view, which the game raises itself), and {@code pullt}, the same in the third-person
 *       raised pose's frame (the trident geometry's hold fix, which lands G where the game's {@code trident_throwing}
 *       block holds it, so the pack draws it with that block).</li>
 * </ul>
 * The file also records each form's GUI fit and whether it is drawn with the bow's frame, so the generator needs nothing
 * else from the game.
 */
public final class ArmDump implements ArmClientModule {
	private static final Logger LOG = LoggerFactory.getLogger("RE:Morph");
	/** The environment variable that makes a development client write the dump at start. */
	public static final String ENV = "MORPHREBORN_DUMPARMS";
	/** The forms with geometry, in the wheel's order. */
	public static final List<String> FORMS = List.of(ArmProtocol.KATANA, ArmProtocol.AXE, ArmProtocol.BOW, ArmProtocol.WHIP, ArmProtocol.SICKLE,
			ArmProtocol.SHOVEL, ArmProtocol.PICKAXE,
			// the new forms (2026-11-16): the same poses, and the use states the pack reads (the crossbow's empty groove and pulls, the trident's
			// drawn throw: e_*, pull0..2, pullg, pullt below); the spear's charge has no pose, since vanilla has no model state for it
			ArmProtocol.SPEAR, ArmProtocol.MACE, ArmProtocol.TRIDENT, ArmProtocol.CROSSBOW, ArmProtocol.CLAWS);
	/** Writhe keyframes: the loop's length in ticks and how many frames it is cut into. */
	public static final int LOOP_TICKS = 96, WRITHE_FRAMES = 12;
	/** The ticks into FORM's 14 the five forming poses are drawn at: the middle of each fifth. */
	public static final float[] FORM_TICKS = {1.4F, 4.2F, 7F, 9.8F, 12.6F};
	/** Biomass fractions for the three low stages (with an absolute amount of a hundred times that). */
	public static final float[] LOW_FRACTIONS = {0.20F, 0.08F, 0.01F};
	/** A drawn bow's power at draws of 0, 4, 9, 14, 19 and 25 ticks (vanilla: (f^2 + 2f) / 3 with f = ticks / 20, at most 1). */
	public static final float[] BOW_POWERS = {0F, 0.147F, 0.367F, 0.63F, 0.934F, 1F};
	/** How much of the crossbow's bolt is out in its three pull poses (the pack's vanilla pull stages): a regrow's first, middle and last part. */
	public static final float[] CROSSBOW_PULL_GROWTH = {0.35F, 0.65F, 0.9F};

	@Override
	public void init() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		Hooks.register();
		String dir = System.getenv(ENV);
		if (dir != null && !dir.isBlank()) {
			try {
				LOG.info("Arm dump: {}", write(Path.of(dir.strip())));
			} catch (IOException | RuntimeException e) {
				LOG.error("Arm dump failed", e);
			}
		}
	}

	/** The command, kept out of this class's own linking so the dump runs where the client's classes aren't started. */
	private static final class Hooks {
		static void register() {
			net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
				var command = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("morphclient").then(
						net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("dumparms").executes(c -> run(c.getSource(), "arm-dump"))
								.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("dir", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
										.executes(c -> run(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "dir")))));
				dispatcher.register(command);
			});
		}

		private static int run(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String dir) {
			String result;
			try {
				result = write(Path.of(dir));
			} catch (IOException | RuntimeException e) {
				LOG.error("Arm dump failed", e);
				result = "The arm dump failed: " + e.getMessage();
			}
			source.sendFeedback(net.minecraft.network.chat.Component.literal(result));
			return 1;
		}
	}

	// ------------------------------------------------------------------ the dump

	/** Writes the dump into {@code dir} (made if need be) and says what was written. Needs no running client. */
	public static String write(Path dir) throws IOException {
		return write(dir, FORMS);
	}

	/** {@link #write(Path)} for some forms only (a quick smoke run). */
	public static String write(Path dir, List<String> forms) throws IOException {
		Files.createDirectories(dir);
		StringBuilder said = new StringBuilder("Arm dump in ").append(dir.toAbsolutePath()).append(':');
		for (String form : forms) {
			if (!ArmGeometry.built(form)) {
				said.append(' ').append(form).append(" (no geometry)");
				continue;
			}
			int quads = writeForm(dir.resolve(form + ".json"), form);
			said.append(' ').append(form).append(' ').append(quads);
		}
		said.append(" sleeve ").append(writeSleeve(dir.resolve("_sleeve.json"), ArmProtocol.KATANA));
		// the crossbow is held with a pistol grip of its own (the sheath closes its claw elsewhere), so its arm shell is another file
		said.append(" crossbow sleeve ").append(writeSleeve(dir.resolve("_sleeve_crossbow.json"), ArmProtocol.CROSSBOW));
		if (ArmGeometry.lure() != null) said.append(" lure ").append(writeLure(dir.resolve("_lure.json")));
		return said.toString();
	}

	private static int writeForm(Path file, String form) throws IOException {
		FormGeometry g = ArmGeometry.get(form);
		int total = 0;
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8); JsonWriter w = new JsonWriter(out)) {
			w.beginObject();
			w.name("form").value(form);
			w.name("bow").value(g.bowTransforms());
			w.name("guiK").value(g.guiScale());
			w.name("guiG").value(g.guiGripPx());
			w.name("loopTicks").value(LOOP_TICKS);
			w.name("tiers").beginArray();
			for (int tier = 1; tier <= 3; tier++) {
				w.beginObject();
				w.name("tier").value(tier);
				w.name("poses").beginArray();
				for (int k = 0; k < WRITHE_FRAMES; k++) {
					total += pose(w, g, "w" + String.format(Locale.ROOT, "%02d", k), draw(form, tier).time(k * (float) LOOP_TICKS / WRITHE_FRAMES).sway(k).build());
				}
				// the tail and drips are the third person's, so the forearm and fist are all this adds to the w00 pose
				total += pose(w, g, "fp", draw(form, tier).ctx(ArmDraw.CTX_FP).down(worldDown(ArmDraw.CTX_TP, form)).sway(0).build());
				for (int n = 0; n < FORM_TICKS.length; n++) {
					float ticks = FORM_TICKS[n];
					total += pose(w, g, "f" + n, draw(form, tier).state(ArmProtocol.STATE_FORMING, ArmDraw.progressAt(ArmProtocol.STATE_FORMING, ticks)).time(ticks).build());
				}
				for (int n = 0; n < LOW_FRACTIONS.length; n++) {
					total += pose(w, g, "low" + (n + 1), draw(form, tier).biomass(LOW_FRACTIONS[n], LOW_FRACTIONS[n] * 100F).build());
				}
				total += pose(w, g, "gui", draw(form, tier).ctx(ArmDraw.CTX_GUI).lod(ArmDraw.LOD_ICON).build());
				if (form.equals(ArmProtocol.WHIP)) {
					// a cast line out: the lash gathers up (the hook's line, which the renderer draws, takes its place)
					dev.morphreborn.client.arm.geom.WhipGeometry.markCast(0, 0F);
					dev.morphreborn.client.arm.geom.WhipGeometry.markCast(0, 6F);
					total += pose(w, g, "cast", draw(form, tier).time(6F).build());
					dev.morphreborn.client.arm.geom.WhipGeometry.clearMarks();
				}
				if (g.bowTransforms()) {
					for (int n = 0; n < BOW_POWERS.length; n++) {
						total += pose(w, g, "pull" + n, draw(form, tier).pull(BOW_POWERS[n]).build());
					}
					total += pose(w, g, "pull6", draw(form, tier).pull(1F).time(3F).build());
				}
				if (form.equals(ArmProtocol.CROSSBOW)) {
					// the groove empty: the stack carries no charged projectile (after a shot, until the bolt is whole again)
					for (int k = 0; k < WRITHE_FRAMES; k++) {
						total += pose(w, g, "e_w" + String.format(Locale.ROOT, "%02d", k),
								draw(form, tier).thrown(true).time(k * (float) LOOP_TICKS / WRITHE_FRAMES).sway(k).build());
					}
					for (int n = 0; n < LOW_FRACTIONS.length; n++) {
						total += pose(w, g, "e_low" + (n + 1), draw(form, tier).thrown(true).biomass(LOW_FRACTIONS[n], LOW_FRACTIONS[n] * 100F).build());
					}
					total += pose(w, g, "e_gui", draw(form, tier).thrown(true).ctx(ArmDraw.CTX_GUI).lod(ArmDraw.LOD_ICON).build());
					// forming or retracting with the groove empty (right after a shot): the same five stages with no bolt, so it doesn't pop in and out
					for (int n = 0; n < FORM_TICKS.length; n++) {
						float ticks = FORM_TICKS[n];
						total += pose(w, g, "e_f" + n, draw(form, tier).thrown(true).state(ArmProtocol.STATE_FORMING, ArmDraw.progressAt(ArmProtocol.STATE_FORMING, ticks)).time(ticks).build());
					}
					// the bolt part grown: a regrow mark gives the growth (age / 40 ticks), read at the draw's own time (0)
					for (int n = 0; n < CROSSBOW_PULL_GROWTH.length; n++) {
						dev.morphreborn.client.arm.geom.CrossbowGeometry.markRegrow(0, 0F, 40, Math.round(40F * (1F - CROSSBOW_PULL_GROWTH[n])), 1);
						try {
							total += pose(w, g, "pull" + n, draw(form, tier).thrown(true).armUp(true).time(0F).build());
						} finally {
							// only the dump's own owner (0): clearMarks() would wipe the live viewers' marks when this runs in a dev client
							dev.morphreborn.client.arm.geom.CrossbowGeometry.clearMark(0);
						}
					}
				}
				if (form.equals(ArmProtocol.TRIDENT)) {
					// a throw drawn. G frame: the full-detail draw in a context with no hold fix (the GUI's), the way the resting poses are
					total += pose(w, g, "pullg", draw(form, tier).ctx(ArmDraw.CTX_GUI).down(worldDown(ArmDraw.CTX_TP, form)).sway(0).pull(1F).build());
					// the third person's raised pose: the geometry's own hold fix applies (a pull in third person), and the tail hangs from the raised arm
					total += pose(w, g, "pullt", draw(form, tier).down(worldDownRaised()).sway(0).pull(1F).build());
				}
				w.endArray();
				w.endObject();
			}
			w.endArray();
			w.endObject();
		}
		return total;
	}

	/** The third-person arm shell, per tier: the form says where the hand holds (the crossbow's pistol grip, the others' sword hold) and which pulses it reads. */
	private static int writeSleeve(Path file, String form) throws IOException {
		int total = 0;
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8); JsonWriter w = new JsonWriter(out)) {
			w.beginObject();
			w.name("tiers").beginArray();
			for (int tier = 1; tier <= 3; tier++) {
				w.beginObject();
				w.name("tier").value(tier);
				w.name("poses").beginArray();
				ArmDraw d = draw(form, tier).down(0F, 1F, 0F).build();
				w.beginObject();
				w.name("id").value("sleeve");
				w.name("quads").beginArray();
				for (ArmLayer layer : layers(d)) {
					Recorder r = new Recorder();
					SheathGeometry.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0), false, false);
					total += r.write(w, layer);
				}
				w.endArray();
				w.endObject();
				w.endArray();
				w.endObject();
			}
			w.endArray();
			w.endObject();
		}
		return total;
	}

	/**
	 * The lantern's lure, drawn in the off arm's space A with world down along +Y (the arm hangs along +Y there), then
	 * turned half a turn about X so it hangs along -Y in the lure's own frame, as an item display shows it. Its stalk is
	 * left out: only what lies within a few px of the lure's rest point is kept (the rest point is the origin afterwards).
	 */
	private static int writeLure(Path file) throws IOException {
		LureRenderer lure = ArmGeometry.lure();
		if (lure == null) return 0;
		int total = 0;
		Vector3f at = dev.morphreborn.client.arm.geom.LureGeometry.restPointThirdPerson(false, false, new Vector3f());
		try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8); JsonWriter w = new JsonWriter(out)) {
			w.beginObject();
			w.name("at").beginArray().value(at.x).value(at.y).value(at.z).endArray();
			w.name("tiers").beginArray();
			for (int tier = 1; tier <= 3; tier++) {
				w.beginObject();
				w.name("tier").value(tier);
				w.name("poses").beginArray();
				for (int k = 0; k < 6; k++) {
					ArmDraw d = draw(ArmProtocol.LANTERN, tier).lantern(true, 100F + 5F * k).time(100F + 5F * k).down(0F, 1F, 0F).build();
					w.beginObject();
					w.name("id").value("l" + k);
					w.name("quads").beginArray();
					for (ArmLayer layer : layers(d)) {
						Recorder r = new Recorder();
						lure.emitThirdPerson(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
						total += r.write(w, layer);
					}
					w.endArray();
					w.endObject();
				}
				w.endArray();
				w.endObject();
			}
			w.endArray();
			w.endObject();
		}
		return total;
	}

	private static int pose(JsonWriter w, FormGeometry g, String id, ArmDraw d) throws IOException {
		int total = 0;
		w.beginObject();
		w.name("id").value(id);
		w.name("t").value(d.time());
		w.name("quads").beginArray();
		for (ArmLayer layer : layers(d)) {
			Recorder r = new Recorder();
			g.emit(d, layer, SkinMesh.begin(new PoseStack().last(), r, 0xF000F0, 0));
			total += r.write(w, layer);
		}
		w.endArray();
		w.endObject();
		return total;
	}

	private static ArmLayer[] layers(ArmDraw d) {
		return d.glow() ? new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED, ArmLayer.GLOW} : new ArmLayer[] {ArmLayer.BLACK, ArmLayer.RED};
	}

	// ------------------------------------------------------------------ the recorder

	/** A vertex consumer that keeps the quads {@link SkinMesh} writes (it writes whole vertices, four to a quad). */
	static final class Recorder implements VertexConsumer {
		private final List<float[]> corners = new ArrayList<>();

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			corners.add(new float[] {x, y, z});
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException("SkinMesh writes whole vertices");
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}

		/** Each quad as {@code [layer, x0, y0, z0, ... x3, y3, z3]}, rounded to a thousandth of a pixel; the count. */
		int write(JsonWriter w, ArmLayer layer) throws IOException {
			int n = corners.size() / 4;
			for (int q = 0; q < n; q++) {
				w.beginArray();
				w.value(layer.ordinal());
				for (int c = 0; c < 4; c++) {
					float[] p = corners.get(4 * q + c);
					for (float v : p) w.value(Math.round(v * 1000F) / 1000.0);
				}
				w.endArray();
			}
			return n;
		}
	}

	// ------------------------------------------------------------------ the draws

	static Builder draw(String form, int tier) {
		return new Builder(form, tier);
	}

	/** A resting draw to change field by field (the record has 36 components). Third person, full detail, no drips. */
	static final class Builder {
		private final String form;
		private final int tier;
		private int lod = ArmDraw.LOD_FULL, ctx = ArmDraw.CTX_TP, state = ArmProtocol.STATE_FORMED, sway = -1;
		private float time = 0F, progress = 1F, biomassFrac = 1F, biomassAbs = ArmDraw.FULL, bowPull = 0F, lanternAge = 0F, downX, downY = -1F, downZ;
		private boolean lanternLit, customDown, thrown, armUp;

		Builder(String form, int tier) {
			this.form = form;
			this.tier = tier;
		}

		Builder time(float t) {
			time = t;
			return this;
		}

		Builder ctx(int c) {
			ctx = c;
			return this;
		}

		Builder lod(int l) {
			lod = l;
			return this;
		}

		Builder state(int s, float p) {
			state = s;
			progress = p;
			return this;
		}

		Builder biomass(float frac, float abs) {
			biomassFrac = frac;
			biomassAbs = abs;
			return this;
		}

		Builder pull(float p) {
			bowPull = p;
			return this;
		}

		/** The arm is posed up for a crossbow even with its groove empty (a vanilla pull: the game charges it). */
		Builder armUp(boolean up) {
			armUp = up;
			return this;
		}

		/** The held stack says the tool is away (a thrown trident) or, for the crossbow, that nothing is charged (the groove is empty). */
		Builder thrown(boolean t) {
			thrown = t;
			return this;
		}

		Builder lantern(boolean lit, float age) {
			lanternLit = lit;
			lanternAge = age;
			return this;
		}

		Builder down(Vector3f v) {
			return down(v.x, v.y, v.z);
		}

		Builder down(float x, float y, float z) {
			downX = x;
			downY = y;
			downZ = z;
			customDown = true;
			return this;
		}

		/** The tail's droop at writhe frame {@code k}: it swings a little round the loop. */
		Builder sway(int k) {
			sway = k;
			return this;
		}

		ArmDraw build() {
			boolean gui = ctx == ArmDraw.CTX_GUI;
			Vector3f down = customDown ? new Vector3f(downX, downY, downZ)
					: form.equals(ArmProtocol.CROSSBOW) && ctx == ArmDraw.CTX_TP ? worldDownCrossbow(armUp || !thrown) : worldDown(ctx, form);
			float[] nodes = null;
			if (lod == ArmDraw.LOD_FULL) {
				nodes = new float[3 * ArmDraw.ROPE_NODES];
				float a = sway < 0 ? 0F : (float) (2.0 * Math.PI * sway / WRITHE_FRAMES);
				Vector3f out = new Vector3f(0.3F + 0.22F * (float) Math.sin(a), -0.95F, 0.24F * (float) Math.cos(a)).normalize();
				Rope.droop(new Vector3f(), out, down, ArmDraw.ROPE_NODES, ArmDraw.ROPE_LINK_PX, nodes);
			}
			return new ArmDraw(form, tier, lod, ctx, false, false, false, time, 0, state, progress, "", 0F, bowPull, false, biomassFrac, biomassAbs, 0F,
					ArmDraw.NEVER, ArmDraw.NO_SPECIAL, ArmDraw.NEVER, ArmDraw.NEVER, new float[0], 0, lanternLit, lanternAge, 0F, true, false, false, nodes,
					down.x, down.y, down.z, null, 0xF000F0, 1F, 0, 0, null, thrown);
		}
	}

	/**
	 * World down in a form's grip space G for a hand held at rest: through the same chain the game draws the item with
	 * (first person: the handheld transform and the grip frame, in the world-aligned hand pass; third person: the arm
	 * hanging, the item's turn into the hand, then the same two). The renderer works it out from the real pose each
	 * frame; this is the pose a model in a pack can only ever show.
	 */
	static Vector3f worldDown(int ctx, String form) {
		boolean bow = form.equals(ArmProtocol.BOW);
		Quaternionf grip = new Quaternionf().rotationZ((float) Math.toRadians(-45.0));
		Matrix3f m = new Matrix3f();
		Vector3f down = new Vector3f();
		if (ctx == ArmDraw.CTX_FP) {
			Quaternionf item = new Quaternionf().rotationXYZ(0F, (float) Math.toRadians(-90.0), (float) Math.toRadians(25.0));
			m.rotation(new Quaternionf(item).mul(grip));
			return m.invert().transform(down.set(0F, -1F, 0F));
		}
		if (ctx == ArmDraw.CTX_GUI) return down.set(0F, -1F, 0F);
		Quaternionf item = bow ? new Quaternionf().rotationXYZ((float) Math.toRadians(-80.0), (float) Math.toRadians(260.0), (float) Math.toRadians(-40.0))
				: new Quaternionf().rotationXYZ(0F, (float) Math.toRadians(-90.0), (float) Math.toRadians(55.0));
		Quaternionf hand = new Quaternionf().rotationX((float) Math.toRadians(-90.0)).mul(new Quaternionf().rotationY((float) Math.toRadians(180.0)));
		m.rotation(hand.mul(item).mul(grip));
		// model space has +Y down, so world down is +Y there
		return m.invert().transform(down.set(0F, 1F, 0F));
	}

	/**
	 * World down in the trident's grip space G while a throw is drawn in third person (the raised pose): the humanoid pose lifts the
	 * arm straight up (a half turn about X), so world down is -Y in the arm's own frame; from there the same chain as {@link #worldDown}, with
	 * the game's {@code trident_throwing} block for the item and the geometry's raised hold fix for the frame (G to the throw block's box).
	 */
	static Vector3f worldDownRaised() {
		Quaternionf item = new Quaternionf().rotationXYZ(0F, (float) Math.toRadians(90.0), (float) Math.toRadians(180.0));
		Quaternionf hand = new Quaternionf().rotationX((float) Math.toRadians(-90.0)).mul(new Quaternionf().rotationY((float) Math.toRadians(180.0)));
		org.joml.Matrix4f x = dev.morphreborn.client.arm.model.ArmTransforms.handFrame(net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
				ArmProtocol.TRIDENT, false, false, new org.joml.Matrix4f());
		Quaternionf fix = x.getNormalizedRotation(new Quaternionf());
		Matrix3f m = new Matrix3f();
		m.rotation(hand.mul(item).mul(fix));
		return m.invert().transform(new Vector3f(0F, -1F, 0F));
	}

	/**
	 * World down in the crossbow's grip space G in third person: vanilla's crossbow display block for the item (the pack holds it as
	 * the mod does, 2026-11-16) and the grip frame. {@code armUp}: the arm is posed as the game poses a loaded or charging crossbow
	 * (AnimationUtils.animateCrossbowHold: yRot -0.3, xRot -pi/2 + 0.1), so world down is turned by that pose; otherwise it hangs.
	 */
	static Vector3f worldDownCrossbow(boolean armUp) {
		Quaternionf grip = new Quaternionf().rotationZ((float) Math.toRadians(-45.0));
		Quaternionf item = new Quaternionf().rotationXYZ((float) Math.toRadians(-90.0), 0F, (float) Math.toRadians(-60.0));
		Quaternionf hand = new Quaternionf().rotationX((float) Math.toRadians(-90.0)).mul(new Quaternionf().rotationY((float) Math.toRadians(180.0)));
		Matrix3f m = new Matrix3f();
		m.rotation(hand.mul(item).mul(grip));
		Vector3f down = new Vector3f(0F, 1F, 0F);
		if (armUp) {
			Matrix3f pose = new Matrix3f().rotation(new Quaternionf().rotationZYX(0F, -0.3F, (float) (-Math.PI / 2) + 0.1F));
			pose.invert().transform(down);
		}
		return m.invert().transform(down);
	}
}
