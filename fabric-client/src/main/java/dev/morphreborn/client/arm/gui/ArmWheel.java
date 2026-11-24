package dev.morphreborn.client.arm.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.Keys;
import dev.morphreborn.client.arm.ArmArsenal;
import dev.morphreborn.client.arm.ArmGuiOverride;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmState;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.gui.BiomassOrb;
import dev.morphreborn.client.gui.RingRenderState;
import dev.morphreborn.client.gui.SelectorHud;
import dev.morphreborn.client.gui.Tendrils;
import dev.morphreborn.client.gui.TipUi;
import dev.morphreborn.client.gui.UiLayout;
import dev.morphreborn.client.gui.UiText;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.tips.Tagged;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * The Arsenal wheel: hold the arm key (J) and your arm forms sit round a ring, each your own arm shaped into it, turning
 * slowly; let go over one (or click it) and your arm becomes it. Built like the favourites wheel (SelectorHud): the
 * same translucent ring, a 3-tick sine-eased opening, a dead zone of 55% of the radius in the middle, and the slice
 * under the mouse picked with atan2, clockwise from the top.
 * <ul>
 *   <li>The slots are fixed, clockwise from the top: katana, axe, bow, whip, sickle, shovel, pickaxe, then
 *       (2026-11-16) spear, mace, trident, crossbow and claws. A form the server hasn't built (S_ARSENAL doesn't list it)
 *       leaves its slot empty, except the five new ones, which are left out of the wheel altogether until built (so the wheel
 *       is the seven it always was, and its slices are laid out from however many are showing); one not bought is drawn
 *       dim, with "Not owned". From eight slots up the icons shrink so twelve still sit clear of each other.</li>
 *   <li>Each icon is the live arm through the item path (a fake client-only stack, posed by {@link ArmGuiOverride}):
 *       twice item size, on a turntable at 0.6 rad/s tilted 20 degrees, a quarter bigger under the mouse.</li>
 *   <li>Under each: its name and level pips in the tier's colour (iron, diamond, netherite with a red rim); under the
 *       mouse also its special and cost ("F: Draining Cut · 15"), whether the special is ready, and the drain.</li>
 *   <li>Tendrils reach from the middle to the slice under the mouse (the frenzy's tendril maths, laid flat), growing
 *       out over six ticks and drawing back when the mouse leaves.</li>
 *   <li>In the middle a lump of biomass reads "Retract" (an arm is out: clicking it puts the arm back) or "Bare Arm",
 *       with your biomass. Round it, an inner ring of small satellite lumps switches the accessories on and off (several
 *       can be on at once): the lantern straight below the middle where it has always been, then the shield, the spyglass
 *       and flint and steel evenly spaced round the ring with it. The ring is laid out from the number of accessories the
 *       server lists. Scrolling over the lantern steps its brightness (Sneak+F does that only with no tool out).</li>
 * </ul>
 * A quick tap of the key (under 4 ticks, the mouse still in the middle) re-forms the last form, or retracts the arm if
 * one is out; with nothing to re-form, the wheel stays open for a click (another tap closes it). Letting go in the
 * middle after holding retracts an arm that is out, as letting go on a form forms it (my call, 2026-11-16;
 * until then the middle needed a click). Esc, a right click or the pause menu close it without picking.
 * <p>
 * The wheel never predicts: it sends C_ARM and closes, and the arm changes when the server's S_ARM arrives (a refusal
 * comes back as S_RESULT on the action bar). On a server without arm forms the key only says so.
 */
public final class ArmWheel {
	/** The slots, clockwise from the top (the server's ArmForm.WHEEL). */
	public static final List<String> SLOTS = ArmProtocol.WHEEL;

	private static final int OPEN_TICKS = 3;
	/** A press shorter than this, let go in the middle, is a tap. */
	private static final int TAP_TICKS = 4;
	private static final float DEAD_ZONE = WheelLayout.DEAD_ZONE;
	/** Where the icons sit: halfway across the ring. */
	private static final float SLOT_RADIUS = WheelLayout.SLOT_RADIUS;
	/** The accessories' satellite lumps: this share of the radius from the middle (the first, the lantern, straight below it), at this orb scale (times the text scale). */
	private static final float SATELLITE_DROP = WheelLayout.SATELLITE_DROP;
	private static final float SATELLITE_SCALE = WheelLayout.SATELLITE_SCALE;
	/** A scroll step over the lantern's lump is held back this many ticks, so a fast flick does not flood the chat command it is sent as (older servers; a newer one's packet has the request throttle). */
	private static final int BRIGHTNESS_STEP_TICKS = 2;
	/** The vanilla items the other accessories' lumps are drawn as (they have no item of their own, being tendrils): recognisable at a glance. */
	private static final Map<String, Item> ACCESSORY_ICONS = Map.of(ArmProtocol.SHIELD, Items.SHIELD, ArmProtocol.SPYGLASS, Items.SPYGLASS,
			ArmProtocol.FLINT, Items.FLINT_AND_STEEL);
	/** Hover tendrils: ticks to reach the slice, and to draw back (as the frenzy's). */
	private static final float GROW = 6F;
	private static final float RETRACT = 6F;
	/** Turntable speed, radians per tick (0.6 rad/s), and tilt. */
	private static final float TURN_PER_TICK = 0.03F;
	private static final float TILT = 20F;
	/** Locked (not bought) icons. */
	private static final int LOCKED_TINT = 0xFF404040;
	/** Level pips: iron, diamond, netherite (netherite also gets a red rim, or it would vanish on the dark ring). */
	private static final int[] PIP = {0xD8D8D8, 0x4AEDD9, 0x4D3B3C};
	private static final int PIP_RIM_NETHERITE = 0xFFC62828;

	private static final DecimalFormat ONE_DECIMAL_DOWN = format("#,##0.#", RoundingMode.FLOOR);
	private static final DecimalFormat TWO_DECIMALS = format("#,##0.##", RoundingMode.HALF_UP);

	/** One fake arm stack per form and tier, built once (render thread only). */
	private static final Map<String, ItemStack[]> STACKS = new HashMap<>();

	/** After every static the constructor reads (SLOTS): static fields initialise in source order. */
	public static final ArmWheel INSTANCE = new ArmWheel();

	private final Minecraft mc = Minecraft.getInstance();

	private boolean open;
	/** Where the cursor was when the wheel opened (the game puts it at the window's middle), so a lump under it is not taken for a choice the player made. */
	private double pressX, pressY;
	/** Left open by a tap that had nothing to re-form: stays until a click, Esc, a right click, or the key again. */
	private boolean sticky;
	/** The key went down again on a wheel a tap left open: another tap closes it, a hold picks as usual. */
	private boolean reopened;
	private int openTicks;
	private int heldTicks;
	/** Ticks since the wheel opened: the hover tendrils' clock. */
	private int clock;
	private boolean keyWasDown;
	private boolean escWasDown;
	/** Per slot: when the tendrils began reaching for it, and when they began drawing back (NaN: not). On {@link #clock}. */
	private final float[] reachFrom = new float[SLOTS.size()];
	private final float[] reachBack = new float[SLOTS.size()];

	private ArmWheel() {
		clearReach();
	}

	public boolean isOpen() {
		return open;
	}

	// ------------------------------------------------------------------ input

	/** Every client tick, paused or not (ArmUi registers it): the key's edges, Esc, menus, and the opening. */
	public void tick(Minecraft client) {
		if (client.player == null) {
			if (open) close();
			while (Keys.ARM.consumeClick()) {
				// nothing to do without a player; drained so it doesn't pile up
			}
			keyWasDown = false;
			return;
		}
		if (open && client.gui.screen() != null) {
			// Esc opens the pause menu before this tick: close the wheel, not the game (as the favourites wheel does). Any
			// other menu just closes the wheel, so letting go of the key in a menu never picks.
			if (client.gui.screen() instanceof PauseScreen) client.gui.setScreen(null);
			close();
		}
		boolean inGame = client.gui.screen() == null;
		int clicks = 0;
		while (Keys.ARM.consumeClick()) clicks++;
		boolean down = inGame && Keys.ARM.isDown();
		if (down && !keyWasDown) {
			press();
		} else if (!down && keyWasDown) {
			release();
		} else if (!down && clicks > 0 && inGame) {
			// pressed and let go between two ticks: a tap
			press();
			if (open) release();
		}
		keyWasDown = down;
		boolean esc = InputConstants.isKeyDown(InputConstants.KEY_ESCAPE);
		if (open && esc && !escWasDown) close();
		escWasDown = esc;
		if (open) {
			openTicks = Math.min(OPEN_TICKS, openTicks + 1);
			if (down) heldTicks++;
			clock++;
		}
	}

	private void press() {
		if (open) {
			// a tap left it open: holding the key again picks on release as usual, tapping it again closes it
			sticky = false;
			reopened = true;
			heldTicks = 0;
			return;
		}
		if (SelectorHud.INSTANCE.showSelector || SelectorHud.INSTANCE.showRadial) return; // one wheel at a time
		if (!ClientNet.isActive()) {
			ClientNet.warnInactive();
			return;
		}
		if (!ClientNet.armsOffered()) {
			ClientNet.message(Component.translatable("morphreborn.arm.not_here").withStyle(ChatFormatting.GRAY));
			return;
		}
		open = true;
		sticky = false;
		reopened = false;
		openTicks = 0;
		heldTicks = 0;
		clock = 0;
		lastBrightnessStep = -1000;
		sentBrightness = -1;
		clearReach();
		mc.mouseHandler.releaseMouse();
		pressX = mouseX();
		pressY = mouseY();
	}

	private void release() {
		if (!open || sticky) return;
		if (heldTicks < TAP_TICKS && !outsideDeadZone() && chosenAccessory(false) < 0) {
			if (reopened || tap()) close();
			else sticky = true; // nothing to re-form: leave it open for a click
			return;
		}
		pick(false);
	}

	/** The tap: retract an arm that is out, else re-form the last form. False when there is nothing to do. */
	private boolean tap() {
		ArmState own = own();
		if (own != null && ArmProtocol.usable(own.state)) {
			ClientNet.sendArm(ArmProtocol.ACTION_RETRACT, "");
			return true;
		}
		String last = Arms.arsenal().lastForm();
		if (last.isEmpty()) return false;
		ClientNet.sendArm(ArmProtocol.ACTION_FORM, last);
		return true;
	}

	/**
	 * Picks what is under the mouse and closes: a form (unless it is the one already out), an accessory's satellite (it
	 * switches, and the others stay as they are), or the middle (retract, when an arm is out). Esc or a right click close
	 * without picking.
	 */
	private void pick(boolean click) {
		ArmArsenal arsenal = Arms.arsenal();
		ArmState own = own();
		int accessory = chosenAccessory(click);
		if (accessory >= 0) {
			ArmArsenal.AccessoryEntry entry = arsenal.accessories().get(accessory);
			if (entry.owned()) ClientNet.sendAccessory(entry.id(), !accessoryOn(own, entry));
		} else if (outsideDeadZone()) {
			List<String> active = activeSlots(arsenal);
			String form = active.isEmpty() ? "" : active.get(selectedSlot(active.size()));
			boolean already = own != null && ArmProtocol.usable(own.state) && form.equals(own.heldForm());
			if (arsenal.entry(form) != null && !already) ClientNet.sendArm(ArmProtocol.ACTION_FORM, form);
		} else if ((!click || onCentre()) && own != null && ArmProtocol.usable(own.state)) {
			// letting go anywhere in the middle retracts, as letting go on a form forms it (2026-11-16: open
			// the wheel and close it again to get the hand back, no click); a click must land on the middle lump
			ClientNet.sendArm(ArmProtocol.ACTION_RETRACT, "");
		}
		close();
	}

	/** Mouse buttons while the wheel is open (SelectorHud asks first): left picks, any other closes. True swallows it. */
	public boolean onMouseButton(int button, boolean pressed) {
		if (!open || mc.gui.screen() != null || !pressed) return false;
		// the wheel's own key on a mouse button must reach its key mapping, or letting go would never be seen
		if (Keys.ARM.matches(InputConstants.Type.MOUSE.getOrCreate(button))) return false;
		if (button == InputConstants.MOUSE_BUTTON_LEFT) pick(true);
		else close();
		return true;
	}

	/**
	 * The mouse wheel while the wheel is open: over the lantern's lump it steps the lantern's brightness (2026-11-16:
	 * Sneak+F steps it only with no tool out now, so the wheel is where it is set with a tool out), anywhere else it does nothing:
	 * scrolling the hotbar off the arm would retract it. True swallows it either way.
	 */
	public boolean onMouseScroll(double delta) {
		if (!open || mc.gui.screen() != null) return false;
		int accessory = accessoryAt();
		if (accessory >= 0 && delta != 0D) {
			ArmArsenal.AccessoryEntry entry = Arms.arsenal().accessories().get(accessory);
			if (ArmProtocol.LANTERN.equals(entry.id()) && entry.owned()) stepLanternBrightness(delta > 0D ? 1 : -1, entry.level());
		}
		return true;
	}

	private int lastBrightnessStep = -1000;
	/** The brightness last sent from the wheel, so a quick flick of several steps builds on it before the server's S_ARM comes back (-1: none yet this opening). */
	private int sentBrightness = -1;

	private void stepLanternBrightness(int dir, int level) {
		if (mc.player == null || !ClientNet.armsOffered()) return;
		// a server from NEW_FORMS_SINCE steps its own brightness by the packet (2026-11-16); it clamps, so nothing is
		// worked out here. The packet shares the 4-tick request throttle, which is why a step it drops is not counted as made.
		if (ClientNet.armNewFormsOffered()) {
			if (ClientNet.sendAccessoryStep(ArmProtocol.LANTERN, dir)) lastBrightnessStep = clock;
			return;
		}
		if (clock - lastBrightnessStep < BRIGHTNESS_STEP_TICKS) return;
		ArmState own = own();
		int current = sentBrightness > 0 ? sentBrightness : own != null && own.lanternBright > 0 ? own.lanternBright : ClientConfig.INSTANCE.lanternBrightness;
		int next = WheelLayout.steppedBrightness(current, dir, level);
		lastBrightnessStep = clock;
		if (next == current && sentBrightness > 0) return;
		sentBrightness = next;
		ClientConfig.INSTANCE.lanternBrightness = next;
		mc.player.connection.sendCommand("morph arm lantern bright " + next);
	}

	private void close() {
		open = false;
		sticky = false;
		reopened = false;
		ArmGuiOverride.clear();
		if (mc.gui.screen() == null && mc.player != null) mc.mouseHandler.grabMouse();
	}

	/** Joining or leaving a server. */
	public void reset() {
		if (open) close();
		keyWasDown = false;
		escWasDown = false;
		clearReach();
	}

	private void clearReach() {
		Arrays.fill(reachFrom, Float.NaN);
		Arrays.fill(reachBack, Float.NaN);
	}

	private @Nullable ArmState own() {
		return mc.player == null ? null : Arms.state(mc.player.getUUID());
	}

	// ------------------------------------------------------------------ geometry (GUI pixels)

	/**
	 * The ring's radius: the Arsenal Wheel Size setting's share of the screen, as large as it fits ({@link WheelLayout#fitRadius}: the lowest
	 * slot's name and pips stay on screen at every size). The drawing and the mouse maths both use this, so what is hit is what is drawn.
	 */
	private float radius() {
		return fit().radius();
	}

	/** The ring's radius and middle ({@link WheelLayout#fit}: raised a little and, where it must be, smaller, so the lowest labels clear the survival HUD). */
	private WheelLayout.Fit fit() {
		var window = mc.getWindow();
		float wanted = Math.min(window.getGuiScaledWidth(), window.getGuiScaledHeight()) * (float) ClientConfig.INSTANCE.armWheelScale / 2F;
		return WheelLayout.fit(wanted, window.getGuiScaledWidth(), window.getGuiScaledHeight(), guiScale(), activeSlots(Arms.arsenal()).size());
	}

	/** Vanilla's GUI scale (device pixels per GUI pixel), which the text sizes are snapped to. */
	private int guiScale() {
		return Math.max(1, (int) mc.getWindow().getGuiScale());
	}

	private float centreX() {
		return mc.getWindow().getGuiScaledWidth() / 2F;
	}

	private float centreY() {
		return fit().cy();
	}

	/** The wheel's text size: 1 at the original wheel's radius. */
	private static float textScale(float radius) {
		return WheelLayout.textScale(radius);
	}

	/**
	 * The mouse in GUI pixels, as screens get it: the cursor is in window coordinates, which aren't the framebuffer's on
	 * a high-density display, so it is scaled by the GUI's size over the window's rather than divided by the GUI scale.
	 */
	private double mouseX() {
		return mc.mouseHandler.getScaledXPos(mc.getWindow());
	}

	private double mouseY() {
		return mc.mouseHandler.getScaledYPos(mc.getWindow());
	}

	private boolean outsideDeadZone() {
		double dx = mouseX() - centreX();
		double dy = mouseY() - centreY();
		return Math.sqrt(dx * dx + dy * dy) > radius() * DEAD_ZONE;
	}

	/** The slot under the mouse out of {@code n} showing: 0 at the top, clockwise (the favourites wheel's maths). */
	private int selectedSlot(int n) {
		double angle = Math.atan2(mouseX() - centreX(), -(mouseY() - centreY()));
		if (angle < 0) angle += Math.PI * 2;
		double slice = Math.PI * 2 / n;
		return (int) Math.floor((angle + slice / 2) / slice) % n;
	}

	/** The tool slots showing, in wheel order ({@link WheelLayout#activeSlots}). */
	static List<String> activeSlots(ArmArsenal arsenal) {
		return WheelLayout.activeSlots(arsenal);
	}

	/**
	 * The middle lump's radius: just big enough for its two lines of text (drawn at the wheel's whole-texel text scale), as the Biomass
	 * button's, but never past {@link WheelLayout#centreLimit} (the accessories' lumps round it): a long amount gets shorter first.
	 */
	private float centreRadius(float radius) {
		Font font = mc.font;
		float ts = textScale(radius);
		float scale = centreScale(radius);
		float textWidth = Math.max(font.width(centreLabel(own())), font.width(centreAmountFitted(Arms.arsenal(), radius))) * scale;
		float wanted = Math.max(textWidth / 2F, font.lineHeight * scale) + 4F * ts;
		return Math.min(wanted, WheelLayout.centreLimit(radius, !Arms.arsenal().accessories().isEmpty()));
	}

	/**
	 * The scale of the middle's two lines: the slot labels' ({@link WheelLayout#slotLabelScale}) when both of its words and the longest amount still
	 * fit across the lump at that size (the lump is as wide as the accessories' lumps round it allow), else the smaller whole-texel size. It
	 * does not depend on what the lump shows now, so it does not jump as the arm is formed.
	 */
	private float centreScale(float radius) {
		Font font = mc.font;
		float ts = textScale(radius);
		float base = WheelLayout.labelScale(guiScale(), ts);
		float big = WheelLayout.slotLabelScale(guiScale(), ts, radius, activeSlots(Arms.arsenal()).size(), mc.getWindow().getGuiScaledHeight());
		if (big <= base) return base;
		float room = (WheelLayout.centreLimit(radius, !Arms.arsenal().accessories().isEmpty()) - 4F * ts) * 2F;
		int widest = Math.max(Math.max(font.width(Component.translatable("morphreborn.arm.wheel.retract")), font.width(Component.translatable("morphreborn.arm.wheel.bare_arm"))),
				font.width("999.9k"));
		return widest * big <= room ? big : base;
	}

	/** The middle's amount line, shortened ("51.2k biomass", then "51.2k") until it fits the lump's limit. */
	private Component centreAmountFitted(ArmArsenal arsenal, float radius) {
		Component full = centreAmount(arsenal);
		if (!ClientNet.isSurvival() || arsenal.free()) return full;
		float ts = textScale(radius);
		float room = (WheelLayout.centreLimit(radius, !arsenal.accessories().isEmpty()) - 4F * ts) * 2F / centreScale(radius);
		if (mc.font.width(full) <= room) return full;
		String compact = compactBiomass(ClientNet.biomassMilli());
		Component shorter = Component.translatable("morphreborn.arm.wheel.biomass", compact);
		return mc.font.width(shorter) <= room ? shorter : Component.literal(compact);
	}

	/** Biomass in thousands to a tenth, rounded down ("51.2k"), or as {@link #biomass} under a thousand. */
	static String compactBiomass(long milli) {
		if (milli < 1_000_000L) return biomass(milli);
		return String.format(Locale.ROOT, "%.1fk", (milli / 100_000L) / 10.0);
	}

	private boolean onCentre() {
		return BiomassOrb.contains(centreX(), centreY(), centreRadius(radius()) / BiomassOrb.RADIUS, mouseX(), mouseY());
	}

	/**
	 * The accessory lump the player has chosen, or -1: the one under the mouse, but for the key's release and the hover only once the mouse has
	 * moved. A tap or a hold with the mouse still on the lump the game centred it on picks nothing, so a tap retracts or re-forms the arm and a
	 * hold lets go quietly instead of lighting the lantern. A click on a lump always counts.
	 */
	private int chosenAccessory(boolean click) {
		return click || WheelLayout.moved(mouseX() - pressX, mouseY() - pressY) ? accessoryAt() : -1;
	}

	/** The accessory lump under the mouse (an index into {@code arsenal.accessories()}), or -1. */
	private int accessoryAt() {
		List<ArmArsenal.AccessoryEntry> accessories = Arms.arsenal().accessories();
		int count = accessories.size();
		float r = radius();
		float[] d = new float[2];
		for (int k = 0; k < count; k++) {
			WheelLayout.accessoryDirection(k, count, d);
			if (BiomassOrb.contains(centreX() + d[0] * r * SATELLITE_DROP, centreY() + d[1] * r * SATELLITE_DROP, SATELLITE_SCALE * textScale(r), mouseX(),
					mouseY())) {
				return k;
			}
		}
		return -1;
	}

	/** Whether {@code entry} is on: your own arm state's say when there is one, else the arsenal's. */
	private static boolean accessoryOn(@Nullable ArmState own, ArmArsenal.AccessoryEntry entry) {
		return own != null ? own.accessoryOn(entry.id()) : entry.on();
	}

	// ------------------------------------------------------------------ drawing

	/** The HUD element (after the selector's). */
	public void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		if (!open || mc.player == null) return;
		float pt = deltaTracker.getGameTimeDeltaPartialTick(false);
		float time = uiTime(pt);
		float now = clock + pt;
		Font font = mc.font;
		ArmArsenal arsenal = Arms.arsenal();
		ArmState own = own();
		boolean armed = own != null && ArmProtocol.usable(own.state);
		String held = own != null && own.armed() ? own.heldForm() : "";

		float radius = radius();
		float prog = MorphState.sineify(Mth.clamp((openTicks + pt) / OPEN_TICKS, 0F, 1F));
		float dist = radius * prog;
		float ts = textScale(radius);
		float cx = centreX();
		float cy = centreY();
		List<String> slots = activeSlots(arsenal);
		int n = slots.size();
		int guiScale = guiScale();
		float iconBase = WheelLayout.iconScaleFor(n, ts, guiScale) * prog;
		// the text is drawn at whole device texels per font pixel once the ring is open (a fractional scale smeared it); while it opens it
		// is left out (three ticks)
		boolean opened = prog >= 1F;
		float labelScale = WheelLayout.slotLabelScale(guiScale, ts, radius, n, mc.getWindow().getGuiScaledHeight());
		float nameMax = WheelLayout.nameScaleMax(guiScale, ts, radius, n, mc.getWindow().getGuiScaledHeight());
		float nameBase = WheelLayout.labelScale(guiScale, ts);
		float centreScale = centreScale(radius);
		float cardScale = WheelLayout.cardScale(guiScale, ts);

		g.guiRenderState.addGuiElement(new RingRenderState(cx, cy, dist * DEAD_ZONE, dist, 100, 150 << 24));

		boolean outside = outsideDeadZone();
		int hovered = outside && n > 0 ? selectedSlot(n) : -1;
		if (hovered >= 0 && arsenal.entry(slots.get(hovered)) == null) hovered = -1; // an empty slot doesn't light up
		boolean onCentre = !outside && onCentre();
		List<ArmArsenal.AccessoryEntry> accessories = arsenal.accessories();
		int hoveredAccessory = chosenAccessory(false);
		// the reach tendrils are kept per slot of the full wheel, so a slot showing later never inherits another's
		updateReach(hovered >= 0 ? SLOTS.indexOf(slots.get(hovered)) : -1, now);

		float slice = Mth.TWO_PI / Math.max(1, n);
		if (hovered >= 0) {
			float a = hovered * slice;
			g.guiRenderState.addGuiElement(new Sector(cx, cy, dist * DEAD_ZONE, dist, a - slice / 2, a + slice / 2, 0x40B71C1C));
			g.guiRenderState.addGuiElement(new Sector(cx, cy, dist - 2F * prog, dist, a - slice / 2, a + slice / 2, 0xC0E53935));
		}

		// the middle: retract or bare arm, and the accessories' satellites round it
		float centreR = centreRadius(radius) * prog;
		if (centreR >= 4F) {
			boolean lit = onCentre && armed;
			BiomassOrb.draw(g, cx, cy, centreR / BiomassOrb.RADIUS, time, lit ? 0xFFE53935 : 0xFF6D1010, lit ? 0x709E1B1B : 0x509E1B1B, lit ? 0.8F : 0F);
		}
		int accessoryCount = accessories.size();
		float satelliteScale = SATELLITE_SCALE * ts * prog;
		float[] dir = new float[2];
		for (int k = 0; k < accessoryCount; k++) {
			ArmArsenal.AccessoryEntry entry = accessories.get(k);
			WheelLayout.accessoryDirection(k, accessoryCount, dir);
			float sx = cx + dir[0] * dist * SATELLITE_DROP;
			float sy = cy + dir[1] * dist * SATELLITE_DROP;
			boolean on = entry.owned() && accessoryOn(own, entry);
			boolean hover = k == hoveredAccessory && entry.owned();
			float pulse = 0.5F + 0.5F * Mth.sin(time * 0.2F);
			BiomassOrb.draw(g, sx, sy, satelliteScale, time, !entry.owned() ? 0xFF2A0508 : on ? 0xFFFF7043 : hover ? 0xFFE53935 : 0xFF5A0E16,
					on ? 0x40FF5A4A : 0x702A0508, on ? 0.4F + 0.4F * pulse : hover ? 0.8F : 0F);
			drawAccessory(g, entry.id(), sx, sy, satelliteScale, time, on, !entry.owned());
		}

		// tendrils reaching for the slice under the mouse, and drawing back from the last one
		for (int i = 0; i < n; i++) {
			float reach = reach(SLOTS.indexOf(slots.get(i)), now);
			if (reach <= 0.01F) continue;
			float a = i * slice;
			float ux = Mth.sin(a);
			float uy = -Mth.cos(a);
			float ex = cx + ux * dist * SLOT_RADIUS;
			float ey = cy + uy * dist * SLOT_RADIUS;
			float from = Math.max(centreR * 0.85F, 4F);
			for (int k = 0; k < 3; k++) {
				float spread = (k - 1) * 3.5F * Math.min(1F, ts);
				float x0 = cx + ux * from - uy * spread * 0.6F;
				float y0 = cy + uy * from + ux * spread * 0.6F;
				float x1 = ex - ux * 8F * iconBase * 0.6F - uy * spread;
				float y1 = ey - uy * 8F * iconBase * 0.6F + ux * spread;
				float w = Math.min(1F, ts);
				Tendrils.reach(g, x0, y0, x1, y1, reach, i * 1.3F + k * 2.1F, time, (k == 1 ? 2.8F : 2.2F) * w, 0.9F * w, k != 1, 0xFF);
			}
		}

		// the forms: an empty socket where one isn't built, the live arm where it is, ringed while it is out
		float yaw = (float) Math.toDegrees(time * TURN_PER_TICK) % 360F;
		float[] iconSize = new float[n];
		float[] iconY = new float[n];
		for (int i = 0; i < n; i++) {
			String form = slots.get(i);
			float a = i * slice;
			float ex = cx + Mth.sin(a) * dist * SLOT_RADIUS;
			float ey = cy - Mth.cos(a) * dist * SLOT_RADIUS;
			float iy = ey - 5F * Math.min(1F, ts) * prog;
			iconY[i] = iy;
			ArmArsenal.Entry entry = arsenal.entry(form);
			if (entry == null) {
				float r = 5F * ts * prog;
				if (r >= 1F) g.guiRenderState.addGuiElement(new RingRenderState(ex, iy, r, r + 1F, 24, 0x40FFFFFF));
				continue;
			}
			float scale = iconBase * (1F + 0.25F * reach(SLOTS.indexOf(form), now));
			iconSize[i] = 16F * scale;
			if (form.equals(held)) {
				float ring = 8F * iconBase + 3F;
				g.guiRenderState.addGuiElement(new RingRenderState(ex, iy, ring, ring + 1.5F, 48, pulseRed(time)));
			}
			int tier = Math.max(1, own != null && form.equals(held) ? own.level : entry.level());
			drawIcon(g, form, tier, ex - 8F * scale, iy - 8F * scale, scale, yaw, TILT, entry.owned() ? 0xFFFFFFFF : LOCKED_TINT);
		}

		g.nextStratum();

		int guiW = mc.getWindow().getGuiScaledWidth();
		int guiH = mc.getWindow().getGuiScaledHeight();

		// names and level pips under the icons, on a dark strip (2026-11-18): the hovered form's special, cost and drain are in the info card
		// below, not under its icon, where they ran into the neighbouring slots' labels and the survival HUD
		float spacing = WheelLayout.slotSpacing(radius, n);
		// how far down the slot labels reach, for the hint lines under the ring (the lowest names hang below the ring's edge at a small window)
		float labelsBottom = cy + radius;
		for (int i = 0; opened && i < n; i++) {
			String form = slots.get(i);
			ArmArsenal.Entry entry = arsenal.entry(form);
			if (entry == null) continue;
			float a = i * slice;
			float ex = cx + Mth.sin(a) * dist * SLOT_RADIUS;
			float y = iconY[i] + iconSize[i] / 2F + 1F;
			boolean isHeld = form.equals(held);
			int color = isHeld ? 0xFFFFD54F : i == hovered ? 0xFFFFEB3B : entry.owned() ? 0xFFFFFFFF : 0xFF9E9E9E;
			Component name = Component.translatableWithFallback("morphreborn.arm.form." + form + ".short", entry.name());
			// each name at the largest size that fits between its neighbours' (a crowded ring kept even "Bow" at half of vanilla's text size); one that
			// fits at none is drawn only for the form you hold, at the larger size, and left to the info card for the form you point at
			float nameW = font.width(name);
			float gap = spacing - 2F;
			float nameScale = nameW * nameMax <= gap ? nameMax : nameW * labelScale <= gap ? labelScale : nameW * nameBase <= gap ? nameBase
					: i == hovered ? 0F : isHeld ? nameMax : 0F;
			boolean showName = nameScale > 0F;
			Component stateLine = isHeld && i != hovered && own != null ? Component.translatable("morphreborn.arm.state." + ArmProtocol.stateName(own.state)) : null;
			int pipCount = entry.owned() ? Math.clamp(entry.maxLevel(), 1, 5) : 0;
			float pipWidth = pipCount == 0 ? 0F : (pipCount * 5F + (pipCount - 1) * 2F) * labelScale;
			float width = Math.max(Math.max(showName ? nameW * nameScale : 0F, pipWidth), stateLine == null ? 0F : font.width(stateLine) * labelScale);
			float height = (showName ? font.lineHeight * nameScale + 1F : 0F) + (pipCount > 0 ? 6F * labelScale + 2F : 0F)
					+ (stateLine == null ? 0F : font.lineHeight * labelScale + 1F);
			if (width <= 0F) continue;
			// denser than the other strips: the lowest slots sit over the survival HUD's hearts and armour at the default size
			UiText.backing(g, ex - width / 2F, y, ex + width / 2F, y + height - 1F, 2, 0xB0000000);
			labelsBottom = Math.max(labelsBottom, y + height + 1F);
			if (showName) {
				text(g, name, ex, y, nameScale, color);
				y += font.lineHeight * nameScale + 1F;
			}
			if (pipCount > 0) {
				int tier = isHeld && own != null ? Math.max(1, own.level) : entry.level();
				pips(g, ex, y, labelScale, entry.level(), entry.maxLevel(), tier);
				y += 6F * labelScale + 2F;
			}
			if (stateLine != null) text(g, stateLine, ex, y, labelScale, 0xFFFFD54F);
		}

		// the middle's text
		if (centreR >= 4F && opened) {
			Component label = centreLabel(own);
			Component amount = centreAmountFitted(arsenal, radius);
			float scale = centreScale;
			UiText.centred(g, font, label, cx, cy - font.lineHeight * scale, scale, onCentre && armed ? 0xFFFFFFFF : 0xFFFFCDD2);
			UiText.centred(g, font, amount, cx, cy + scale, scale, 0xFFEF9A9A);
			if (onCentre && armed && TipUi.on()) {
				Component click = Component.translatableWithFallback("morphreborn.arm.wheel.click_retract", "Let go: retract");
				UiText.centredBacked(g, font, click, cx, cy - centreR * 1.9F - font.lineHeight * scale, scale, 0xFFFFEB3B);
			}
		}
		if (opened) {
			for (int k = 0; k < accessoryCount; k++) {
				ArmArsenal.AccessoryEntry entry = accessories.get(k);
				// the accessory under the mouse is explained in the info card; its lump keeps no label of its own
				if (k == hoveredAccessory) continue;
				WheelLayout.accessoryDirection(k, accessoryCount, dir);
				float sx = cx + dir[0] * dist * SATELLITE_DROP;
				float sy = cy + dir[1] * dist * SATELLITE_DROP;
				boolean on = entry.owned() && accessoryOn(own, entry);
				boolean above = WheelLayout.accessoryAbove(k, accessoryCount);
				// the label under the lump, or over it for the one at the top (away from the middle's text)
				Component line;
				if (on) {
					line = Component.translatable(ArmProtocol.LANTERN.equals(entry.id()) ? "morphreborn.arm.wheel.lantern_lit" : "morphreborn.arm.wheel.accessory_on")
							.withStyle(ChatFormatting.GOLD);
				} else if (hoveredAccessory < 0) {
					line = accessoryName(entry.id()).withStyle(style -> style.withColor(entry.owned() ? 0xBDBDBD : 0x9E9E9E));
				} else {
					continue;
				}
				// the accessories' labels stay at the smaller size: at two texels they would run across the middle lump they sit beside
				float accessoryScale = WheelLayout.labelScale(guiScale, ts);
				float height = font.lineHeight * accessoryScale;
				float y = above ? sy - 16F * satelliteScale - height - 1F : sy + 16F * satelliteScale + 1F;
				UiText.centredBacked(g, font, line, sx, y, accessoryScale, 0xFFFFFFFF);
			}

			// under the mouse, one card for what the form or accessory does (instead of lines under the icon, over its neighbours)
			List<Component> card = null;
			float hoverX = 0F;
			if (hovered >= 0) {
				ArmArsenal.Entry entry = arsenal.entry(slots.get(hovered));
				card = new ArrayList<>(details(arsenal, entry));
				card.add(0, formName(arsenal, slots.get(hovered)).copy().withStyle(ChatFormatting.WHITE));
				hoverX = Mth.sin(hovered * slice);
			} else if (hoveredAccessory >= 0 && hoveredAccessory < accessoryCount) {
				ArmArsenal.AccessoryEntry entry = accessories.get(hoveredAccessory);
				card = new ArrayList<>(accessoryDetails(arsenal, own, entry, entry.owned() && accessoryOn(own, entry)));
				WheelLayout.accessoryDirection(hoveredAccessory, accessoryCount, dir);
				hoverX = dir[0];
			}
			if (card != null && !card.isEmpty()) drawCard(g, font, card, cardScale, guiW, guiH, cx, cy, radius, hoverX);
		}

		// under the ring: why nothing can be formed, then what a tap does and how to close
		// Tooltips (2026-11-17): that there are no forms yet and why one can't be formed are state; what to buy, what a tap does and how to close are tips
		List<Tagged<Component>> tagged = new ArrayList<>();
		if (arsenal.entries().isEmpty()) {
			tagged.add(Tagged.always(Component.translatable("morphreborn.arm.wheel.empty").withStyle(ChatFormatting.WHITE)));
			if (ClientNet.isSurvival()) {
				tagged.add(Tagged.tip((Keys.BIOMASS.isUnbound() ? Component.translatable("morphreborn.arm.wheel.buy_hint_no_key")
						: Component.translatable("morphreborn.arm.wheel.buy_hint", Keys.BIOMASS.getTranslatedKeyMessage())).withStyle(ChatFormatting.GRAY)));
			}
		} else if (!arsenal.usable() && !arsenal.whyNot().isEmpty()) {
			tagged.add(Tagged.always(Component.literal(arsenal.whyNot()).withStyle(ChatFormatting.RED)));
		}
		if (armed) {
			tagged.add(Tagged.tip(Component.translatable("morphreborn.arm.wheel.tap_retract").withStyle(ChatFormatting.GRAY)));
		} else if (!arsenal.lastForm().isEmpty()) {
			tagged.add(Tagged.tip(Component.translatable("morphreborn.arm.wheel.tap_reform", formName(arsenal, arsenal.lastForm())).withStyle(ChatFormatting.GRAY)));
		}
		// light enough to read on the dark strip behind it (dark grey was 2.8:1 on the bare world)
		tagged.add(Tagged.tip(Component.translatable("morphreborn.arm.wheel.close").withStyle(style -> style.withColor(0x9E9E9E))));
		List<Component> hints = Tagged.filter(tagged, TipUi.on());
		float hintScale = WheelLayout.hintScale(guiScale, ts);
		if (!hints.isEmpty() && opened) {
			// off the hotbar and the XP number at every GUI scale (the rig, 2026-11-16): WheelLayout.hintTop picks under or over the ring.
			// Wrapped to the screen (a long line was cut at 320 wide) and on a dark strip like every text over the world.
			List<FormattedCharSequence> lines = UiText.wrapAll(font, hints, (int) ((guiW - 2 * UiLayout.MARGIN - 4F) / hintScale));
			float step = font.lineHeight * hintScale + 1F;
			float hx = cx;
			float y = WheelLayout.hintClear(cy, radius, 4F * ts, lines.size() * step - 1F, guiH, labelsBottom);
			if (Float.isNaN(y)) {
				// neither under the slot labels nor over the ring (a small window, 2026-11-18): in the free column beside the ring, at the top, on
				// the side away from the info card, wrapped to it; with no such column, over the ring's bottom as before
				float side = WheelLayout.hintSideWidth(guiW, cx, radius);
				if (side >= WheelLayout.HINT_MIN_SIDE) {
					lines = UiText.wrapAll(font, hints, (int) (side / hintScale));
					float widest = 0F;
					for (FormattedCharSequence line : lines) widest = Math.max(widest, font.width(line) * hintScale);
					boolean cardLeft;
					if (hovered >= 0) {
						cardLeft = Mth.sin(hovered * slice) > 0F;
					} else if (hoveredAccessory >= 0 && hoveredAccessory < accessoryCount) {
						WheelLayout.accessoryDirection(hoveredAccessory, accessoryCount, dir);
						cardLeft = dir[0] > 0F;
					} else {
						cardLeft = false;
					}
					hx = cardLeft ? guiW - UiLayout.MARGIN - widest / 2F : UiLayout.MARGIN + widest / 2F;
					y = WheelLayout.HINT_EDGE + UiLayout.MARGIN;
				} else {
					y = WheelLayout.hintTop(cy, radius, 4F * ts, lines.size() * step - 1F, guiH, labelsBottom);
				}
			}
			for (FormattedCharSequence line : lines) {
				float half = font.width(line) * hintScale / 2F;
				if (half > 0F) UiText.backing(g, hx - half, y, hx + half, y + font.lineHeight * hintScale, 2, UiText.BACKING);
				UiText.centred(g, font, line, hx, y, hintScale, 0xFFFFFFFF);
				y += step;
			}
		}
	}

	/**
	 * The info card for the form or accessory under the mouse: its lines wrapped to the room beside the ring, at a normal text size on a dark
	 * backing, put by {@link WheelLayout#placeCard} where it covers no other label and stays on screen (the lines used to hang under the
	 * icon at a fraction of a pixel per font pixel, over the next slot's name and, at the bottom, over the hotbar).
	 */
	private void drawCard(GuiGraphicsExtractor g, Font font, List<Component> lines, float scale, int guiW, int guiH, float cx, float cy, float radius, float hoverX) {
		float pad = 3F * scale;
		float sideRoom = (guiW - 2F * radius) / 2F - WheelLayout.CARD_GAP - UiLayout.MARGIN;
		float maxW = UiLayout.clamp(sideRoom, 100F, 190F);
		List<FormattedCharSequence> wrapped = new ArrayList<>(UiText.wrapAll(font, lines, Math.max(16, (int) ((maxW - 2F * pad) / scale))));
		float step = (font.lineHeight + 1F) * scale;
		// never taller than the screen: the last lines go first
		int most = Math.max(1, (int) ((guiH - 2F * UiLayout.MARGIN - 2F * pad) / step));
		while (wrapped.size() > most) wrapped.removeLast();
		int widest = 0;
		for (FormattedCharSequence line : wrapped) widest = Math.max(widest, font.width(line));
		float w = widest * scale + 2F * pad;
		float h = wrapped.size() * step + 2F * pad - scale;
		UiLayout.Rect at = WheelLayout.placeCard(guiW, guiH, cx, cy, radius, hoverX, w, h);
		int x0 = Math.round(at.x());
		int y0 = Math.round(at.y());
		int x1 = Math.round(at.right());
		int y1 = Math.round(at.bottom());
		g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF6D1010);
		g.fill(x0, y0, x1, y1, 0xF0101010);
		float y = at.y() + pad;
		for (FormattedCharSequence line : wrapped) {
			UiText.at(g, font, line, at.x() + pad, y, scale, 0xFFFFFFFF);
			y += step;
		}
	}

	/** Under the mouse: the special, whether it is ready, and the drain; or that it isn't bought. */
	private List<Component> details(ArmArsenal arsenal, ArmArsenal.Entry entry) {
		List<Component> lines = new ArrayList<>(3);
		if (!entry.owned()) {
			lines.add(Component.translatable("morphreborn.arm.wheel.not_owned").withStyle(ChatFormatting.GRAY));
			return lines;
		}
		if (!entry.specialName().isEmpty()) {
			// the special's name and its cost stay; the "F:" in front of them is the key hint
			String key = TipUi.on() ? "morphreborn.arm.wheel.special" : "morphreborn.arm.wheel.special_plain";
			lines.add((arsenal.free() ? Component.translatable(key + "_free", entry.specialName())
					: Component.translatable(key, entry.specialName(), cost(entry.specialCostMilli()))).withStyle(ChatFormatting.RED));
			lines.add(ready(entry.cooldownLeft(Arms.TICK)));
		}
		if (!arsenal.free() && arsenal.drainMilliPerMinute() > 0) {
			lines.add(Component.translatable("morphreborn.arm.wheel.drain", cost(arsenal.drainMilliPerMinute())).withStyle(style -> style.withColor(0xFF6E6E)));
		}
		return lines;
	}

	/** An accessory's name (its short form name: "Lantern", "Shield", ...), falling back to its id. */
	private static net.minecraft.network.chat.MutableComponent accessoryName(String id) {
		return Component.translatableWithFallback("morphreborn.arm.form." + id + ".short", id);
	}

	/**
	 * Under the mouse on an accessory's lump: what a click does, and for the lantern its brightness and what scrolling does (the
	 * owner, 2026-11-16: Sneak+F steps it only with no tool out, so the wheel steps it with a tool out); a lantern of a server
	 * before 8.0.0 has no brightness to show. The lantern's drain is the one S_ARSENAL carries for it; the other accessories'
	 * drain is in the total the HUD shows, so their lump says only that they cost while on.
	 */
	private List<Component> accessoryDetails(ArmArsenal arsenal, @Nullable ArmState own, ArmArsenal.AccessoryEntry entry, boolean on) {
		List<Tagged<Component>> lines = new ArrayList<>(6);
		if (!entry.owned()) {
			lines.add(Tagged.always(accessoryName(entry.id()).withStyle(ChatFormatting.GRAY)));
			lines.add(Tagged.always(Component.translatable("morphreborn.arm.wheel.not_owned").withStyle(ChatFormatting.GRAY)));
			return Tagged.filter(lines, TipUi.on());
		}
		// Tooltips (2026-11-17): the name, the lantern's brightness and the cost stay; the click and the key that use it are tips
		lines.add(Tagged.always(accessoryName(entry.id()).withStyle(ChatFormatting.WHITE)));
		if (ArmProtocol.LANTERN.equals(entry.id())) {
			int bright = own != null ? own.lanternBright : 0;
			lines.add(Tagged.always(Component.translatable("morphreborn.arm.wheel.lantern_brightness", bright > 0 ? String.valueOf(bright) : "-").withStyle(ChatFormatting.RED)));
			lines.add(Tagged.tip(Component.translatable(on ? "morphreborn.arm.wheel.lantern_off" : "morphreborn.arm.wheel.lantern_on").withStyle(ChatFormatting.YELLOW)));
			lines.add(Tagged.tip(Component.translatable("morphreborn.arm.wheel.lantern_scroll").withStyle(ChatFormatting.RED)));
			if (!arsenal.free() && arsenal.lanternDrainMilliPerMinute() > 0) {
				lines.add(Tagged.always(Component.translatable("morphreborn.arm.wheel.drain", cost(arsenal.lanternDrainMilliPerMinute())).withStyle(style -> style.withColor(0xFF6E6E))));
			}
			return Tagged.filter(lines, TipUi.on());
		}
		lines.add(Tagged.tip(Component.translatable(on ? "morphreborn.arm.wheel.accessory_click_off" : "morphreborn.arm.wheel.accessory_click_on").withStyle(ChatFormatting.YELLOW)));
		for (Component how : accessoryHow(entry.id())) lines.add(Tagged.tip(how.copy().withStyle(ChatFormatting.RED)));
		if (!arsenal.free()) lines.add(Tagged.always(Component.translatable("morphreborn.arm.wheel.accessory_costs").withStyle(style -> style.withColor(0xFF6E6E))));
		return Tagged.filter(lines, TipUi.on());
	}

	/**
	 * How an accessory is used, line by line and exactly (the shield's click, the spyglass's key as it is bound now, what the flint and steel
	 * does with no key at all): the part of the wheel the Tooltips setting turns off.
	 */
	private static List<Component> accessoryHow(String id) {
		return switch (id) {
			case ArmProtocol.SHIELD -> List.of(Component.translatable("morphreborn.arm.how.shield"), Component.translatable("morphreborn.arm.how.shield2"));
			case ArmProtocol.SPYGLASS -> List.of(Component.translatable("morphreborn.arm.how.spyglass", Keys.SPYGLASS.getTranslatedKeyMessage()),
					Component.translatable("morphreborn.arm.how.spyglass2"));
			case ArmProtocol.FLINT -> List.of(Component.translatable("morphreborn.arm.how.flint"), Component.translatable("morphreborn.arm.how.flint2"));
			default -> List.of();
		};
	}

	private static Component ready(int ticksLeft) {
		if (ticksLeft <= 0) return Component.translatable("morphreborn.arm.wheel.ready").withStyle(ChatFormatting.GREEN);
		return Component.translatable("morphreborn.arm.wheel.ready_in", (ticksLeft + 19) / 20).withStyle(ChatFormatting.GOLD);
	}

	private static Component centreLabel(@Nullable ArmState own) {
		return Component.translatable(own != null && ArmProtocol.usable(own.state) ? "morphreborn.arm.wheel.retract" : "morphreborn.arm.wheel.bare_arm");
	}

	/** Your biomass to a tenth, or "Free" where arms cost nothing (Classic, creative, costs off). */
	private static Component centreAmount(ArmArsenal arsenal) {
		if (!ClientNet.isSurvival() || arsenal.free()) return Component.translatable("morphreborn.arm.wheel.free");
		return Component.translatable("morphreborn.arm.wheel.biomass", biomass(ClientNet.biomassMilli()));
	}

	private static Component formName(ArmArsenal arsenal, String form) {
		ArmArsenal.Entry entry = arsenal.entry(form);
		return Component.translatableWithFallback("morphreborn.arm.form." + form + ".short", entry == null ? form : entry.name());
	}

	/** Text centred on {@code x}, its top at {@code y}, at {@code scale}, with a shadow. */
	private void text(GuiGraphicsExtractor g, Component text, float x, float y, float scale, int color) {
		UiText.centred(g, mc.font, text, x, y, scale, color);
	}

	/**
	 * Level pips centred on {@code x}: {@code max} of them, the first {@code level} lit in the colour of the tier the form
	 * is now ({@code tier}: iron, diamond, netherite), the rest dark sockets.
	 */
	private static void pips(GuiGraphicsExtractor g, float x, float y, float scale, int level, int max, int tier) {
		int count = Math.clamp(max, 1, 5);
		int size = 5;
		int gap = 2;
		int width = count * size + (count - 1) * gap;
		int color = 0xFF000000 | PIP[Math.clamp(tier, 1, 3) - 1];
		int rim = tier >= 3 ? PIP_RIM_NETHERITE : 0xFF000000;
		// the left edge on a whole device pixel, the middle worked out from the exact width
		int gs = UiText.guiScale();
		g.pose().pushMatrix();
		g.pose().translate(UiLayout.snap(gs, x - width * scale / 2F), UiLayout.snap(gs, y));
		g.pose().scale(scale, scale);
		for (int k = 0; k < count; k++) {
			int px = k * (size + gap);
			if (k < level) {
				g.fill(px - 1, -1, px + size + 1, size + 1, rim);
				g.fill(px, 0, px + size, size, color);
				g.fill(px, 0, px + size, 1, 0x40FFFFFF); // a glint along the top
			} else {
				g.fill(px, 0, px + size, size, 0xFF101010);
				g.fill(px + 1, 1, px + size - 1, size - 1, 0xFF303030);
			}
		}
		g.pose().popMatrix();
	}

	/**
	 * The lantern's lure (the lantern has no item to draw): a stalk curling up into a bulb at ({@code x}, {@code y}),
	 * glowing while lit. It is about 17 by 18 of its units, the bulb at the middle and the stalk hanging down and to the
	 * left of it. The wheel's satellite and the upgrade tree's lantern use it; {@code dim} greys it for a lantern that
	 * can't be bought yet.
	 */
	public static void drawLure(GuiGraphicsExtractor g, float x, float y, float scale, float time, boolean lit, boolean dim) {
		if (scale <= 0.05F) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		for (int k = 0; k <= 12; k++) {
			float u = k / 12F;
			float sx = -9F + 9F * u - 3.5F * Mth.sin(u * Mth.PI) + 0.6F * Mth.sin(time * 0.15F + u * 4F) * u;
			float sy = 10F - 11F * u;
			Tendrils.dot(g, sx, sy, 2.6F - 1.2F * u, 0xFF000000 | (dim ? 0x202020 : Tendrils.lerpColor(0x1A0406, 0x5A0E16, u)));
		}
		if (lit && !dim) {
			float pulse = 0.5F + 0.5F * Mth.sin(time * 0.25F);
			Tendrils.dot(g, 0F, -1F, 9F + 2F * pulse, (Math.round(0x40 + 0x30 * pulse) << 24) | 0xFF5A4A);
			Tendrils.dot(g, 0F, -1F, 5F, 0xFFFF5A4A);
			Tendrils.dot(g, 0F, -1F, 2.4F, 0xFFFFD0C0);
		} else {
			Tendrils.dot(g, 0F, -1F, 4.5F, dim ? 0xFF303030 : 0xFF5A0E16);
			Tendrils.dot(g, 0F, -1F, 2F, dim ? 0xFF181818 : 0xFF2A0508);
		}
		g.pose().popMatrix();
	}

	/**
	 * An accessory's lump icon at ({@code x}, {@code y}) at {@code scale}: the lantern's lure, and for the others the vanilla item
	 * they stand for (a tendril shield, spyglass or flint and steel has no item of its own), greyed down while it is not bought.
	 */
	public static void drawAccessory(GuiGraphicsExtractor g, String id, float x, float y, float scale, float time, boolean on, boolean dim) {
		if (scale <= 0.05F) return;
		if (ArmProtocol.LANTERN.equals(id)) {
			drawLure(g, x, y, scale, time, on, dim);
			return;
		}
		Item item = ACCESSORY_ICONS.get(id);
		if (item == null) return;
		float size = 16F * scale * 1.4F;
		g.pose().pushMatrix();
		try {
			g.pose().translate(x - size / 2F, y - size / 2F);
			g.pose().scale(size / 16F, size / 16F);
			g.fakeItem(new ItemStack(item), 0, 0);
		} finally {
			g.pose().popMatrix();
		}
		if (dim) g.guiRenderState.addGuiElement(new RingRenderState(x, y, 0F, 12F * scale, 24, 0xC0101010));
	}

	private static int pulseRed(float time) {
		float pulse = 0.65F + 0.35F * Mth.sin(time * 0.25F);
		return ((int) (pulse * 255F) << 24) | 0xE53935;
	}

	/** Starts or ends the tendrils' reach as the mouse moves between slices; a slice left mid-retract picks up from there. */
	private void updateReach(int hovered, float now) {
		for (int i = 0; i < reachFrom.length; i++) {
			boolean reaching = !Float.isNaN(reachFrom[i]) && Float.isNaN(reachBack[i]);
			if (i == hovered && !reaching) {
				float current = reach(i, now);
				reachFrom[i] = now - current * GROW;
				reachBack[i] = Float.NaN;
			} else if (i != hovered && reaching) {
				reachBack[i] = now;
			} else if (i != hovered && !Float.isNaN(reachBack[i]) && now - reachBack[i] > RETRACT) {
				reachFrom[i] = Float.NaN;
				reachBack[i] = Float.NaN;
			}
		}
	}

	/**
	 * How far slot {@code i}'s tendrils reach now, 0-1: growing out, times drawing back (as RageTethers'). The growth stops
	 * where it was when the mouse left, so a tendril left half grown only ever shrinks from there.
	 */
	private float reach(int i, float now) {
		if (Float.isNaN(reachFrom[i])) return 0F;
		boolean back = !Float.isNaN(reachBack[i]);
		float grow = Mth.clamp(((back ? Math.min(now, reachBack[i]) : now) - reachFrom[i]) / GROW, 0F, 1F);
		float left = back ? Mth.clamp(1F - (now - reachBack[i]) / RETRACT, 0F, 1F) : 1F;
		return grow * left;
	}

	// ------------------------------------------------------------------ shared with the HUD, the tree and the bar

	/**
	 * The arm clock for drawing: {@link Arms#TICK} plus the partial tick, wrapped every hour so the float keeps its
	 * fractions however long the game runs (a turntable jumps once an hour, unseen).
	 */
	public static float uiTime(float partialTick) {
		return (Arms.TICK % 72000L) + partialTick;
	}

	/** The turntable's angle now, in degrees. */
	public static float turntable(float time) {
		return (float) Math.toDegrees(time * TURN_PER_TICK) % 360F;
	}

	/**
	 * Draws the live arm of {@code form} at {@code tier} as a GUI item, its 16-pixel box's top left at ({@code x},
	 * {@code y}) and {@code scale} times item size, posed by {@link ArmGuiOverride} (turntable {@code yawDeg}, tilt
	 * {@code pitchDeg}, {@code tintArgb}, full bright). The item path takes the pose while the stack is extracted, inside
	 * this call; a pose nothing took (no arm model in this build: vanilla's tier tool is drawn) is dropped after it.
	 */
	public static void drawIcon(GuiGraphicsExtractor g, String form, int tier, float x, float y, float scale, float yawDeg, float pitchDeg, int tintArgb) {
		if (scale <= 0.05F) return;
		ItemStack stack = armStack(form, tier);
		ArmGuiOverride.set(new ArmGuiOverride.Override(yawDeg, pitchDeg, 1F, tintArgb, true));
		g.pose().pushMatrix();
		try {
			g.pose().translate(x, y);
			g.pose().scale(scale, scale);
			g.fakeItem(stack, 0, 0);
		} finally {
			g.pose().popMatrix();
			ArmGuiOverride.clear();
		}
	}

	/**
	 * A client-only arm stack, as the server builds them: the form's base item, the vanilla tier tool as its item model
	 * (what is drawn without the arm model), and the {@code ["morphreborn:arm", form, tier]} strings the arm model is
	 * recognised by. Never sent anywhere; one per form and tier, built once.
	 */
	public static ItemStack armStack(String form, int tier) {
		int t = Math.clamp(tier, 1, 3);
		ItemStack[] tiers = STACKS.computeIfAbsent(form, f -> new ItemStack[4]);
		ItemStack stack = tiers[t];
		if (stack == null) {
			stack = new ItemStack(baseItem(form));
			stack.set(DataComponents.CUSTOM_MODEL_DATA,
					new CustomModelData(List.of(), List.of(), List.of(ArmProtocol.CMD_TAG, form, ArmProtocol.tierName(t)), List.of()));
			Identifier model = vanillaModel(form, t);
			if (model != null) stack.set(DataComponents.ITEM_MODEL, model);
			tiers[t] = stack;
		}
		return stack;
	}

	/** Whether a form has an arm item at all (the lantern is an accessory and never does). */
	public static boolean hasItem(String form) {
		return ArmProtocol.FORMS.contains(form);
	}

	/** The item every tier of a form is made from (as the server's ArmForm): the tier lives in the components only. */
	public static Item baseItem(String form) {
		return switch (form) {
			case ArmProtocol.KATANA -> Items.IRON_SWORD;
			case ArmProtocol.PICKAXE -> Items.IRON_PICKAXE;
			case ArmProtocol.AXE -> Items.IRON_AXE;
			case ArmProtocol.SHOVEL -> Items.IRON_SHOVEL;
			case ArmProtocol.SICKLE -> Items.IRON_HOE;
			case ArmProtocol.WHIP -> Items.FISHING_ROD;
			// the new forms (2026-11-16), the same bases as the server's ArmForm: one spear material for every tier, the vanilla mace,
			// trident and crossbow (the crossbow is real so vanilla draws its loaded pose for everyone), shears for the claws
			case ArmProtocol.SPEAR -> Items.IRON_SPEAR;
			case ArmProtocol.MACE -> Items.MACE;
			case ArmProtocol.TRIDENT -> Items.TRIDENT;
			case ArmProtocol.CROSSBOW -> Items.CROSSBOW;
			case ArmProtocol.CLAWS -> Items.SHEARS;
			default -> Items.PAPER;
		};
	}

	/** The vanilla tool a form looks like at a tier to players without the arm model (as the server's ArmForm.vanillaModel). */
	public static @Nullable Identifier vanillaModel(String form, int tier) {
		String metal = switch (tier) {
			case 2 -> "diamond";
			case 3 -> "netherite";
			default -> "iron";
		};
		String path = switch (form) {
			case ArmProtocol.KATANA -> metal + "_sword";
			case ArmProtocol.PICKAXE -> metal + "_pickaxe";
			case ArmProtocol.AXE -> metal + "_axe";
			case ArmProtocol.SHOVEL -> metal + "_shovel";
			case ArmProtocol.SICKLE -> metal + "_hoe";
			case ArmProtocol.BOW -> "bow";
			case ArmProtocol.WHIP -> "fishing_rod";
			case ArmProtocol.SPEAR -> metal + "_spear";
			case ArmProtocol.MACE -> "mace";
			case ArmProtocol.TRIDENT -> "trident";
			case ArmProtocol.CROSSBOW -> "crossbow";
			case ArmProtocol.CLAWS -> "shears";
			default -> null;
		};
		return path == null ? null : Identifier.withDefaultNamespace(path);
	}

	/** Biomass to a tenth, rounded down (never more than you have): "123.4". */
	public static String biomass(long milli) {
		synchronized (ONE_DECIMAL_DOWN) {
			return ONE_DECIMAL_DOWN.format(milli / 1000.0);
		}
	}

	/** A cost or drain to a hundredth, without trailing zeros: "15", "0.25", "12.5". */
	public static String cost(long milli) {
		synchronized (TWO_DECIMALS) {
			return TWO_DECIMALS.format(milli / 1000.0);
		}
	}

	private static DecimalFormat format(String pattern, RoundingMode rounding) {
		DecimalFormat f = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
		f.setRoundingMode(rounding);
		return f;
	}

	/**
	 * A slice of a ring between two angles (radians, clockwise from the top, as the slots are counted): the slice under
	 * the mouse and its bright rim. RingRenderState's quads, cut to the slice.
	 */
	private record Sector(float cx, float cy, float inner, float outer, float a0, float a1, int color, ScreenRectangle bounds)
			implements GuiElementRenderState {
		private static final Matrix3x2f IDENTITY = new Matrix3x2f();

		Sector(float cx, float cy, float inner, float outer, float a0, float a1, int color) {
			this(cx, cy, inner, outer, a0, a1, color,
					new ScreenRectangle((int) Math.floor(cx - outer), (int) Math.floor(cy - outer), (int) Math.ceil(outer * 2) + 1, (int) Math.ceil(outer * 2) + 1));
		}

		@Override
		public void buildVertices(VertexConsumer consumer) {
			int steps = Math.max(2, (int) Math.ceil((a1 - a0) / Mth.TWO_PI * 100));
			for (int i = 0; i < steps; i++) {
				float b0 = a0 + (a1 - a0) * i / steps;
				float b1 = a0 + (a1 - a0) * (i + 1) / steps;
				float s0 = Mth.sin(b0), c0 = Mth.cos(b0);
				float s1 = Mth.sin(b1), c1 = Mth.cos(b1);
				consumer.addVertexWith2DPose(IDENTITY, cx + s0 * outer, cy - c0 * outer).setColor(color);
				consumer.addVertexWith2DPose(IDENTITY, cx + s0 * inner, cy - c0 * inner).setColor(color);
				consumer.addVertexWith2DPose(IDENTITY, cx + s1 * inner, cy - c1 * inner).setColor(color);
				consumer.addVertexWith2DPose(IDENTITY, cx + s1 * outer, cy - c1 * outer).setColor(color);
			}
		}

		@Override
		public RenderPipeline pipeline() {
			return RenderPipelines.GUI;
		}

		@Override
		public TextureSetup textureSetup() {
			return TextureSetup.noTexture();
		}

		@Override
		public @Nullable ScreenRectangle scissorArea() {
			return null;
		}
	}
}
