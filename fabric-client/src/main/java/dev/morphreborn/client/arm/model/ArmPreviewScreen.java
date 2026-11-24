package dev.morphreborn.client.arm.model;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.FormGeometry;
import dev.morphreborn.client.arm.render.ArmTex;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * {@code /morphclient arm <form> [level]}: one arm form on its own, for judging the look (D49) without a server, a
 * Survival tree or any biomass. Everything the arm reacts to is a control here:
 * <ul>
 *   <li>the form and its tier; the state (formed, or one of the timelines: FORM, QUICKFORM, RESHAPE, RETRACT, MELT)
 *       with its progress, played on a loop or held on the slider; the biomass left (the thinning, starving and failing
 *       looks); mining (the pick's gnaw);</li>
 *   <li>a swing, a hit, and mocked specials: Draining Cut (wind-up, the swing, three heals arriving down the blade) and
 *       Vein Rip (the prong extended, eight ores breaking in turn);</li>
 *   <li>three cameras: the turntable (the GUI fit, turned, as the wheel shows it), the arm (a dummy arm in its sheath
 *       holding the tool through the third-person chain, turned) and first person (the hand pass's own layout, swing
 *       and forearm, seen square on);</li>
 *   <li>the level of detail, glow, and a stress mode drawing ten arms with what they cost the CPU per frame (the
 *       target is under 2.5 ms for ten).</li>
 * </ul>
 * Readouts: the katana's EDGE_SIGN and the fist's KNUCKLE_SIDE as built (check that the edge leads the swing and the
 * knuckles face out), the form's GUI fit and where its rest pose lands along the slot's diagonal (it should stay within
 * 1..15 px), and the arm's quads. Next to the view the real hotbar icon is drawn at 1× and 2×.
 * <p>
 * Not a pause screen, and it runs on its own clock (real time, 20 ticks a second), so it animates even in a paused
 * single-player world. Drag turns the view, scroll zooms.
 */
public final class ArmPreviewScreen extends Screen implements dev.morphreborn.client.gui.HidesPaperDoll {
	private enum Camera {
		TURNTABLE("morphreborn.arm.preview.camera.turntable", "Turntable"),
		ARM("morphreborn.arm.preview.camera.third_person", "Third person (arm)"),
		FIRST_PERSON("morphreborn.arm.preview.camera.first_person", "First person"),
		/** Looking down the tool's length from its point, its guard (or head) square on: the katana guard's symbol. */
		DOWN_BLADE("morphreborn.arm.preview.camera.down_blade", "Down the blade");

		final String key, fallback;

		Camera(String key, String fallback) {
			this.key = key;
			this.fallback = fallback;
		}

		Component label() {
			return Component.translatableWithFallback(key, fallback);
		}
	}

	/** The states the preview shows, in the cycle's order (FORMED is "idle"). */
	private static final List<Integer> STATES = List.of(ArmProtocol.STATE_FORMED, ArmProtocol.STATE_FORMING, ArmProtocol.STATE_QUICKFORM,
			ArmProtocol.STATE_RESHAPING, ArmProtocol.STATE_RETRACTING, ArmProtocol.STATE_MELTING);
	/** Ticks a looping timeline holds at its end before it starts again. */
	private static final float LOOP_HOLD = 12F;
	/** The biomass capacity the slider is a fraction of (the absolute thresholds then follow the fraction's). */
	private static final float CAPACITY = 200F;
	private static final int ROW = 21, PANEL = 160;

	private String form;
	private int tier;
	private int state = ArmProtocol.STATE_FORMED;
	private float progress;
	private boolean play = true;
	private float biomass = 1F;
	private boolean mining, glow = true, stress;
	private Camera camera = Camera.TURNTABLE;
	private int lod;
	private static final float DEFAULT_YAW = 35F, DEFAULT_PITCH = 12F;
	private float yaw = DEFAULT_YAW, pitch = DEFAULT_PITCH, zoom = 1F;

	/** The preview's own clock: real time in ticks since it opened. */
	private final long openedAt = Util.getMillis();
	private double playFrom;
	/** The mocked reactions, on that clock, and what is still to come (Vein Rip's breaks). */
	private final ArmReactions.Reaction reaction = new ArmReactions.Reaction();
	private final List<Double> pendingBreaks = new ArrayList<>();
	private double swingAt = ArmReactions.LONG_AGO, swingLength = 6, miningUntil = ArmReactions.LONG_AGO;

	private float msPerFrame;
	private int quads;
	private @Nullable Slider progressSlider;
	private @Nullable CycleButton<Boolean> playButton;

	public ArmPreviewScreen(String form, int tier) {
		super(Component.translatable("morphreborn.arm.preview.title", Component.translatable("morphreborn.arm.form." + form)));
		this.form = form;
		this.tier = Math.clamp(tier, 1, 3);
	}

	private double now() {
		return (Util.getMillis() - openedAt) / 50.0;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ controls

	@Override
	protected void init() {
		int x = 6, half = (PANEL - 4) / 2, y = 24;
		addRenderableWidget(CycleButton.<String>builder(f -> Component.translatable("morphreborn.arm.form." + f + ".short"), form)
				.withValues(ArmProtocol.FORMS).create(x, y, half, 18, Component.translatableWithFallback("morphreborn.arm.preview.form", "Form"),
						(b, v) -> setForm(v)));
		addRenderableWidget(CycleButton.<Integer>builder(t -> Component.translatable("morphreborn.arm.tier." + ArmProtocol.tierName(t)), tier)
				.withValues(1, 2, 3).displayOnlyValue().create(x + half + 4, y, half, 18, Component.translatable("morphreborn.arm.preview.tier", ""),
						(b, v) -> tier = v));
		y += ROW;
		addRenderableWidget(CycleButton.<Integer>builder(s -> Component.translatable("morphreborn.arm.state." + ArmProtocol.stateName(s)), state)
				.withValues(STATES).displayOnlyValue().create(x, y, half, 18, Component.literal("State"), (b, v) -> {
					state = v;
					restart();
				}));
		playButton = addRenderableWidget(CycleButton.onOffBuilder(play).create(x + half + 4, y, half, 18, Component.translatableWithFallback("morphreborn.arm.preview.play", "Play"),
				(b, v) -> {
					play = v;
					if (v) restart();
				}));
		y += ROW;
		progressSlider = addRenderableWidget(new Slider(x, y, PANEL, progress,
				v -> Component.translatable("morphreborn.arm.preview.progress", Math.round(v * 100)), v -> {
					progress = (float) v;
					play = false;
					if (playButton != null) playButton.setValue(false);
				}));
		y += ROW;
		addRenderableWidget(new Slider(x, y, PANEL, biomass, v -> Component.translatable("morphreborn.arm.preview.biomass", Math.round(v * 100)),
				v -> biomass = (float) v));
		y += ROW;
		addRenderableWidget(CycleButton.onOffBuilder(mining).create(x, y, half, 18, Component.translatable("morphreborn.arm.preview.mining"), (b, v) -> mining = v));
		addRenderableWidget(CycleButton.<Camera>builder(Camera::label, camera).withValues(Camera.values()).displayOnlyValue()
				.create(x + half + 4, y, half, 18, Component.translatable("morphreborn.arm.preview.camera", ""), (b, v) -> camera = v));
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("morphreborn.arm.preview.swing"), b -> swing(now(), 6)).bounds(x, y, half, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("morphreborn.arm.preview.hit"), b -> {
			double t = now();
			swing(t, 6);
			reaction.hit(t + 2);
		}).bounds(x + half + 4, y, half, 18).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.translatable("morphreborn.arm.preview.draining_cut"), b -> drainingCut()).bounds(x, y, half, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("morphreborn.arm.preview.vein_rip"), b -> veinRip()).bounds(x + half + 4, y, half, 18).build());
		y += ROW;
		addRenderableWidget(CycleButton.<Integer>builder(l -> Component.translatableWithFallback("morphreborn.arm.preview.lod", "Detail: LOD %s", l), lod)
				.withValues(0, 1, 2, 3).displayOnlyValue().create(x, y, half, 18, Component.empty(), (b, v) -> lod = v));
		addRenderableWidget(CycleButton.onOffBuilder(glow).create(x + half + 4, y, half, 18, Component.translatableWithFallback("morphreborn.arm.preview.glow", "Glow"),
				(b, v) -> glow = v));
		y += ROW;
		addRenderableWidget(CycleButton.onOffBuilder(stress).create(x, y, PANEL, 18, Component.translatable("morphreborn.arm.preview.stress"), (b, v) -> stress = v));
	}

	private void setForm(String f) {
		form = f;
		reaction.clear();
		pendingBreaks.clear();
	}

	private void restart() {
		playFrom = now();
	}

	private void swing(double at, double length) {
		swingAt = at;
		swingLength = length;
	}

	/** Draining Cut in hand (section 2.7): a 2-tick wind-up, the swing, then three heals arriving down the blade. */
	private void drainingCut() {
		double t = now();
		reaction.special(ArmProtocol.FX_SWIPE, t, ArmReactions.DRAIN_CUT_TICKS);
		swing(t + 2, 4);
		reaction.hit(t + 3);
		reaction.heal(t + 9);
		reaction.heal(t + 13);
		reaction.heal(t + 17);
	}

	/** Vein Rip in hand: the prong out and the eye bright, eight ores breaking a tick apart after the 3-tick crack lead. */
	private void veinRip() {
		double t = now();
		int ores = 8;
		reaction.special(ArmProtocol.FX_VEIN, t, ArmProtocol.veinBreakTick(ores - 1, 10) + 1);
		pendingBreaks.clear();
		for (int i = 0; i < ores; i++) pendingBreaks.add(t + ArmProtocol.veinBreakTick(i, 10));
		miningUntil = t + ArmProtocol.veinBreakTick(ores - 1, 10) + 1;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (super.mouseDragged(event, dx, dy)) return true;
		if (event.x() < PANEL + 10) return false;
		yaw += (float) dx * 1.5F;
		pitch = Math.clamp(pitch + (float) dy * 1.0F, -80F, 80F);
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x < PANEL + 10) return super.mouseScrolled(x, y, scrollX, scrollY);
		zoom = Math.clamp(zoom * (scrollY > 0 ? 1.15F : 1F / 1.15F), 0.25F, 6F);
		return true;
	}

	// ------------------------------------------------------------------ the draw

	/** The arm as the controls describe it now, on the preview's clock. */
	private ArmDraw draw(double t, int ctx, int seed) {
		ArmDrawFactory.Spec s = new ArmDrawFactory.Spec();
		s.form = form;
		s.tier = tier;
		s.ctx = ctx;
		s.seed = seed;
		s.time = (float) (t % ArmDrawFactory.CLOCK_WRAP);
		s.state = state;
		s.progress = state == ArmProtocol.STATE_FORMED ? 1F : progress;
		s.fromForm = state == ArmProtocol.STATE_RESHAPING ? (ArmProtocol.KATANA.equals(form) ? ArmProtocol.PICKAXE : ArmProtocol.KATANA) : "";
		double sinceSwing = t - swingAt;
		s.swing = sinceSwing >= 0 && sinceSwing < swingLength ? (float) (sinceSwing / swingLength) : 0F;
		s.mining = mining || t < miningUntil;
		s.biomassFrac = biomass;
		s.biomassAbs = biomass * CAPACITY;
		s.glow = glow;
		s.dissolve = ArmTex.dissolveOn();
		s.chestArmour = false;
		s.slim = slim();
		s.light = ArmTex.FULL_BRIGHT;
		// the preview shows your own pulses the way the world does: at the speed you chose in the settings
		s.pulseSpeed = dev.morphreborn.client.arm.ArmProtocol.pulseSpeedFactor(dev.morphreborn.client.arm.ArmColours.ownPulseSpeed());
		s.reactions(reaction, t);
		return s.build();
	}

	private static boolean slim() {
		var player = Minecraft.getInstance().player;
		return player != null && player.getSkin().model() == PlayerModelType.SLIM;
	}

	private static Identifier skin() {
		var player = Minecraft.getInstance().player;
		return player != null ? player.getSkin().body().texturePath() : DefaultPlayerSkin.getDefaultTexture();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		double t = now();
		advance(t);
		long[] stats = ArmFormRenderer.Stats.take();
		msPerFrame = msPerFrame * 0.9F + (stats[0] / 1.0E6F) * 0.1F;
		quads = (int) stats[1];

		int x0 = PANEL + 12, y0 = 22, x1 = width - 8, y1 = height - 56;
		if (x1 - x0 > 40 && y1 - y0 > 40) {
			// a light backdrop, so the black static reads in screenshots; the picture goes on top of it
			g.fillGradient(x0, y0, x1, y1, 0xFFA9B4BF, 0xFF5D6670);
			g.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, 0xFF2A2F35);
			g.nextStratum();
			g.guiRenderState.addPicturesInPictureState(new State(entries(t, x1 - x0, y1 - y0), x0, y0, x1, y1));
		}
		g.nextStratum();
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFCDD2, true);
		readouts(g, x0, y1 + 4);
		icons(g, x1 - 60, y0 + 6);
		if (!dev.morphreborn.client.gui.TipUi.on()) return; // the keys and the mouse are the tip (the Tooltips setting)
		Component help = Component.translatable("morphreborn.arm.preview.help").withStyle(ChatFormatting.GRAY);
		Component shownHelp = dev.morphreborn.client.gui.UiText.fit(font, help, width - 8);
		g.text(font, shownHelp, (width - font.width(shownHelp)) / 2, height - 11, 0xFFFFFFFF, true);
	}

	/** Moves the clock-driven controls on: the looping timeline and Vein Rip's breaks. */
	private void advance(double t) {
		if (play && state != ArmProtocol.STATE_FORMED) {
			float length = ArmProtocol.duration(state);
			float into = (float) ((t - playFrom) % (length + LOOP_HOLD));
			progress = Math.min(1F, into / length);
			if (progressSlider != null) progressSlider.show(progress);
		}
		for (int i = pendingBreaks.size() - 1; i >= 0; i--) {
			if (pendingBreaks.get(i) <= t) reaction.breakBlock(pendingBreaks.remove(i));
		}
	}

	/** The arms to draw: one in the middle, or ten in two rows for the stress test. */
	private List<Entry> entries(double t, int w, int h) {
		List<Entry> out = new ArrayList<>();
		int ctx = switch (camera) {
			case TURNTABLE -> ArmDraw.CTX_GUI;
			case ARM -> ArmDraw.CTX_TP;
			case FIRST_PERSON -> ArmDraw.CTX_FP;
			case DOWN_BLADE -> ArmDraw.CTX_TP;
		};
		float base = switch (camera) {
			case TURNTABLE -> Math.min(w, h) * 0.8F;
			case ARM -> Math.min(w, h) * 0.38F;
			case FIRST_PERSON -> h / 1.7F;
			case DOWN_BLADE -> Math.min(w, h) * 1.6F;
		};
		float swing = 0F;
		double sinceSwing = t - swingAt;
		if (sinceSwing >= 0 && sinceSwing < swingLength) swing = (float) (sinceSwing / swingLength);
		Identifier skin = skin();
		boolean slim = slim();
		if (!stress) {
			out.add(new Entry(draw(t, ctx, -1), camera, w / 2F, h / 2F, base * zoom, yaw, pitch, swing, lod, slim, skin));
			return out;
		}
		for (int i = 0; i < 10; i++) {
			float cx = w * (0.1F + 0.2F * (i % 5)), cy = h * (i < 5 ? 0.27F : 0.73F);
			out.add(new Entry(draw(t + i * 3.1, ctx, -(7 * i + 1)), camera, cx, cy, base * zoom * 0.36F, yaw + 36F * i, pitch, swing, lod, slim, skin));
		}
		return out;
	}

	// ------------------------------------------------------------------ readouts

	private void readouts(GuiGraphicsExtractor g, int x, int y) {
		FormGeometry geometry = ArmGeometry.get(form);
		int line = 0;
		Component edge = Component.translatable("morphreborn.arm.preview.edge_sign", sign("dev.morphreborn.client.arm.geom.KatanaGeometry", "EDGE_SIGN"))
				.append(Component.literal("  (the red edge's side of G: it should face down and lead the swing)").withStyle(ChatFormatting.GRAY));
		g.text(font, dev.morphreborn.client.gui.UiText.fit(font, edge, width - x - 4), x, y + 10 * line++, 0xFFFFFFFF, true);
		Component knuckle = Component.translatable("morphreborn.arm.preview.knuckle_side", sign("dev.morphreborn.client.arm.geom.FistGeometry", "KNUCKLE_SIDE"))
				.append(Component.literal("  (knuckles should face out of the fist)").withStyle(ChatFormatting.GRAY));
		g.text(font, dev.morphreborn.client.gui.UiText.fit(font, knuckle, width - x - 4), x, y + 10 * line++, 0xFFFFFFFF, true);
		List<Vector3fc> extents = new ArrayList<>();
		geometry.extents(v -> extents.add(new Vector3f(v)));
		float[] span = ArmTransforms.guiSpan(geometry.guiScale(), geometry.guiGripPx(), extents);
		boolean fits = span[0] >= 1F && span[1] <= 15F;
		Component fit = Component.translatable("morphreborn.arm.preview.gui_fit", fmt(geometry.guiScale(), 2), fmt(geometry.guiGripPx(), 1))
				.append(Component.literal(String.format(Locale.ROOT, "  diagonal %.1f..%.1f px ", span[0], span[1])).withStyle(ChatFormatting.GRAY))
				.append(Component.literal(fits ? "fits 1..15" : "outside 1..15").withStyle(fits ? ChatFormatting.GREEN : ChatFormatting.RED));
		g.text(font, dev.morphreborn.client.gui.UiText.fit(font, fit, width - x - 4), x, y + 10 * line++, 0xFFFFFFFF, true);
		String built = ArmGeometry.built(form) ? "" : "  (placeholder: this form's geometry isn't in the build)";
		Component cost = Component.literal(quads + " quads  ").append(Component.translatable("morphreborn.arm.preview.ms", fmt(msPerFrame, 2)))
				.append(Component.literal(stress ? "  for 10 arms (target under 2.5)" : "").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(built).withStyle(ChatFormatting.YELLOW));
		g.text(font, dev.morphreborn.client.gui.UiText.fit(font, cost, width - x - 4), x, y + 10 * line, 0xFFFFFFFF, true);
	}

	private static String fmt(float v, int digits) {
		return String.format(Locale.ROOT, "%." + digits + "f", v);
	}

	private static final Map<String, String> SIGNS = new HashMap<>();

	/** A geometry's sign constant as built ("+1"/"-1"), or the spec's +1 when that class isn't in the build yet. */
	private static String sign(String className, String field) {
		return SIGNS.computeIfAbsent(className + "#" + field, k -> {
			try {
				Field f = Class.forName(className, false, ArmPreviewScreen.class.getClassLoader()).getDeclaredField(field);
				f.setAccessible(true);
				Object v = f.get(null);
				if (v instanceof Number n) return n.floatValue() >= 0F ? "+1" : "-1";
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				// not built yet
			}
			return "+1 (spec; not in the build)";
		});
	}

	private static final Map<String, ItemStack> STACKS = new HashMap<>();

	/** The real hotbar icon (the GUI context through the item path), at 1× and 2×, with the slot's 1..15 px frame. */
	private void icons(GuiGraphicsExtractor g, int x, int y) {
		ItemStack stack = STACKS.computeIfAbsent(form + "/" + tier, k -> {
			ItemStack s = new ItemStack(Items.IRON_SWORD);
			s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(ArmProtocol.CMD_TAG, form, ArmProtocol.tierName(tier)), List.of()));
			return s;
		});
		g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
		g.outline(x, y, 16, 16, 0xFF373737);
		g.fakeItem(stack, x, y);
		int x2 = x + 22;
		g.fill(x2 - 1, y - 1, x2 + 33, y + 33, 0xFF8B8B8B);
		g.outline(x2 + 1, y + 1, 30, 30, 0x60FFFFFF);
		g.pose().pushMatrix();
		g.pose().translate(x2, y);
		g.pose().scale(2F, 2F);
		g.fakeItem(stack, 0, 0);
		g.pose().popMatrix();
	}

	/** A 0..1 slider labelled by {@code label}, whose value the screen may also move ({@link #show}). */
	private static final class Slider extends AbstractSliderButton {
		private final DoubleFunction<Component> label;
		private final DoubleConsumer set;

		Slider(int x, int y, int width, double value, DoubleFunction<Component> label, DoubleConsumer set) {
			super(x, y, width, 18, Component.empty(), value);
			this.label = label;
			this.set = set;
			updateMessage();
		}

		void show(double v) {
			this.value = Math.clamp(v, 0.0, 1.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(label.apply(value));
		}

		@Override
		protected void applyValue() {
			set.accept(value);
		}
	}

	// ------------------------------------------------------------------ the picture

	/**
	 * One arm in the picture: its draw, the camera, its centre in GUI pixels from the picture's top left, its size in
	 * GUI pixels per block, the turntable's angles, the swing (for the first-person and arm cameras' own transforms), the
	 * LOD, and the dummy arm's skin.
	 */
	record Entry(ArmDraw draw, Camera camera, float cx, float cy, float pixelsPerBlock, float yaw, float pitch, float swing, int lod, boolean slim,
			Identifier skin) {}

	/** The picture's state: the arms and where on screen they go. */
	record State(List<Entry> entries, int x0, int y0, int x1, int y1, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
			implements PictureInPictureRenderState {
		State(List<Entry> entries, int x0, int y0, int x1, int y1) {
			this(entries, x0, y0, x1, y1, null, new ScreenRectangle(x0, y0, x1 - x0, y1 - y0));
		}

		@Override
		public float scale() {
			return 1F;
		}
	}

	/**
	 * Draws the preview's arms into the picture's texture, through the same renderer as the game ({@link ArmFormRenderer}
	 * and the sheath layer's submit) in the PREVIEW pass: no physics, no drips, nothing recorded in the world. Each
	 * camera builds the pose chain its view has in game.
	 */
	public static final class Renderer extends PictureInPictureRenderer<State> {
		/** View space (y up, +z toward the viewer) into the picture's (y down, the viewer at −z). */
		private static final Quaternionf VIEW = new Quaternionf().rotationX((float) Math.PI);
		private @Nullable PlayerModel wide, slim;

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected float getTranslateY(int height, int guiScale) {
			return 0F;
		}

		@Override
		protected String getTextureLabel() {
			return "morphreborn arm preview";
		}

		@Override
		protected void renderToTexture(State state, PoseStack poseStack, SubmitNodeCollector collector) {
			if (state.entries().isEmpty()) return;
			Camera camera = state.entries().getFirst().camera();
			Minecraft.getInstance().gameRenderer.lighting().setupFor(camera == Camera.TURNTABLE ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ENTITY_IN_UI);
			float halfWidth = (state.x1() - state.x0()) / 2F;
			for (Entry e : state.entries()) {
				poseStack.pushPose();
				poseStack.translate(e.cx() - halfWidth, e.cy(), 0F);
				poseStack.scale(e.pixelsPerBlock(), e.pixelsPerBlock(), e.pixelsPerBlock());
				poseStack.rotate(VIEW);
				switch (e.camera()) {
					case TURNTABLE -> turntable(e, poseStack, collector);
					case ARM -> arm(e, poseStack, collector);
					case FIRST_PERSON -> firstPerson(e, poseStack, collector);
					case DOWN_BLADE -> downBlade(e, poseStack, collector);
				}
				poseStack.popPose();
			}
		}

		/** The GUI icon's pose, large and turned about the slot's centre: the item box centred, then the GUI fit. */
		private static void turntable(Entry e, PoseStack poseStack, SubmitNodeCollector collector) {
			FormGeometry geometry = ArmGeometry.get(e.draw().form());
			poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(e.pitch())).rotateY((float) Math.toRadians(e.yaw())));
			poseStack.translate(-0.5F, -0.5F, -0.5F);
			poseStack.mulPose(ArmTransforms.gui(geometry.guiScale(), geometry.guiGripPx(), new Matrix4f()));
			ArmFormRenderer.submit(e.draw(), ArmDrawFactory.Pass.PREVIEW, poseStack, collector, ArmTex.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, null, e.lod(), 0xFFFFFF);
		}

		/**
		 * Down the tool's length, from its point: G's +Y turned toward the viewer, the guard (G y 3) at the middle, the
		 * drag still tilting it; in grip space, px, as in the hand (no GUI fit).
		 */
		private static void downBlade(Entry e, PoseStack poseStack, SubmitNodeCollector collector) {
			// square on at the screen's opening angle; dragging tilts it from there
			poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(e.pitch() - DEFAULT_PITCH)).rotateY((float) Math.toRadians(e.yaw() - DEFAULT_YAW)));
			poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
			poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(90)));
			poseStack.translate(0F, -3F, 0F);
			ArmFormRenderer.submit(e.draw(), ArmDrawFactory.Pass.PREVIEW, poseStack, collector, ArmTex.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, null, e.lod(), 0xFFFFFF);
		}

		/**
		 * A right arm as a player's in third person, holding the tool: the arm bone in the item-holding pose (and the
		 * swing), the sheath in arm space, then the third-person item chain (ItemInHandLayer's grip offsets, the handheld
		 * transform, grip space).
		 */
		private void arm(Entry e, PoseStack poseStack, SubmitNodeCollector collector) {
			PlayerModel model = model(e.slim());
			ModelPart arm = model.rightArm;
			arm.resetPose();
			arm.x = 0F;
			arm.y = 0F;
			arm.z = 0F;
			// HumanoidModel's ITEM pose, then a WHACK swing's lift of the arm
			float swing = e.swing() > 0F ? (float) Math.sin(Math.PI * e.swing()) : 0F;
			arm.xRot = -(float) Math.PI / 10F - 1.2F * swing;
			arm.yRot = -0.15F * swing;
			poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(e.pitch())).rotateY((float) Math.toRadians(e.yaw() + 160F)));
			// model space (y down, as the entity renderer's scale(-1, -1, 1) leaves it), the hand a little right of the middle
			poseStack.scale(-1F, -1F, 1F);
			poseStack.translate(1F / 16F, -7F / 16F, 6F / 16F);
			int light = ArmTex.FULL_BRIGHT;
			collector.submitModelPart(arm, poseStack, RenderTypes.entityCutout(e.skin()), light, OverlayTexture.NO_OVERLAY, null);
			poseStack.pushPose();
			arm.translateAndRotate(poseStack);
			poseStack.scale(1F / 16F, 1F / 16F, 1F / 16F);
			ArmSheathLayer.submitSheath(e.draw(), ArmDrawFactory.Pass.PREVIEW, poseStack, collector, light, OverlayTexture.NO_OVERLAY, e.slim(), false, e.lod());
			poseStack.popPose();
			poseStack.pushPose();
			arm.translateAndRotate(poseStack);
			poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(-90)));
			poseStack.rotate(new Quaternionf().rotationY((float) Math.PI));
			poseStack.translate(1F / 16F, 2F / 16F, -10F / 16F);
			FormGeometry geometry = ArmGeometry.get(e.draw().form());
			ArmTransforms.forForm(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, e.draw().form(), geometry.bowTransforms()).apply(false, poseStack.last());
			poseStack.mulPose(ArmTransforms.handFrame(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, e.draw().form(), geometry.bowTransforms(), false, new Matrix4f()));
			ArmFormRenderer.submit(e.draw(), ArmDrawFactory.Pass.PREVIEW, poseStack, collector, light, OverlayTexture.NO_OVERLAY, null, e.lod(), 0xFFFFFF);
			poseStack.popPose();
		}

		/**
		 * The first-person hand pass's own layout, seen square on: the right hand's item placement, vanilla's WHACK swing
		 * when swinging, the first-person transform and grip space, with the camera-fixed elbow point worked out from this
		 * view, as in game.
		 */
		private static void firstPerson(Entry e, PoseStack poseStack, SubmitNodeCollector collector) {
			// the hand and the forearm sit right of and below the middle of the view: bring them to the middle
			poseStack.translate(-0.45F, 0.5F, 0F);
			Matrix4f view = new Matrix4f(poseStack.last().pose());
			poseStack.translate(0.56F, -0.52F, -0.72F);
			float a = e.swing();
			if (a > 0F) {
				float sqrt = (float) Math.sqrt(a);
				poseStack.translate(-0.4F * (float) Math.sin(sqrt * Math.PI), 0.2F * (float) Math.sin(sqrt * Math.PI * 2), -0.2F * (float) Math.sin(a * Math.PI));
				float ySwing = (float) Math.sin(a * a * Math.PI);
				float xzSwing = (float) Math.sin(sqrt * Math.PI);
				poseStack.rotate(new Quaternionf().rotationY((float) Math.toRadians(45F + ySwing * -20F)));
				poseStack.rotate(new Quaternionf().rotationZ((float) Math.toRadians(xzSwing * -20F)));
				poseStack.rotate(new Quaternionf().rotationX((float) Math.toRadians(xzSwing * -80F)));
				poseStack.rotate(new Quaternionf().rotationY((float) Math.toRadians(-45F)));
			}
			FormGeometry geometry = ArmGeometry.get(e.draw().form());
			ArmTransforms.forForm(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, e.draw().form(), geometry.bowTransforms()).apply(false, poseStack.last());
			poseStack.mulPose(ArmTransforms.gripFrame(ArmTransforms.Set.of(e.draw().form(), geometry.bowTransforms()), new Matrix4f()));
			ArmFormRenderer.submit(e.draw(), ArmDrawFactory.Pass.PREVIEW, poseStack, collector, ArmTex.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, view, e.lod(),
					0xFFFFFF);
		}

		/** The player model the dummy arm is taken from (baked once, wide and slim). */
		private PlayerModel model(boolean slimArms) {
			if (slimArms) {
				if (slim == null) slim = new PlayerModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
				return slim;
			}
			if (wide == null) wide = new PlayerModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
			return wide;
		}
	}
}
