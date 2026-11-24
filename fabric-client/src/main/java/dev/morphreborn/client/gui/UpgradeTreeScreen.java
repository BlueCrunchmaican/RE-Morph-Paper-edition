package dev.morphreborn.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.morphreborn.client.Keys;
import dev.morphreborn.client.arm.Arms;
import dev.morphreborn.client.arm.gui.ArmWheel;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.passive.PassiveProtocol;
import dev.morphreborn.client.render.MorphWorldRenderer;
import dev.morphreborn.client.tips.Tagged;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Survival Mode's upgrade tree for players with the mod, in the advancement screen's style: each upgrade is a framed
 * status-effect icon, linked to the upgrade it grows from; drag or scroll to move around, hover for details, click to
 * buy. The window sits on the morph's black static. Players without the mod get the server's chest menu instead.
 * <p>
 * The standard tree is shown in sections: Biomass, then the Morph Ability as a lump of biomass on its own, the Morph
 * section it opens, Arm Shaping as a second, bladed lump, and the Arsenal (the arm forms) it opens; below them (or
 * beside them, in a short window) the Abilities that Morph Abilities opens. Each of Biomass and Morph lists its
 * upgrades like a file tree (an upgrade under the one it grows from); the Arsenal and the Abilities are grids, the
 * Arsenal's icons your own arm shaped into each form, turning. A server tree without the Morph Ability is drawn as one
 * plain tree instead, and one without Arm Shaping (servers before 8.0.0, or arm forms switched off) exactly as before.
 * <p>
 * Where everything goes is worked out by {@link TreeLayout}, which uses no game classes, so the layout is tested
 * without a game (ArsenalLayoutTest).
 */
public final class UpgradeTreeScreen extends Screen implements HidesPaperDoll {
	private static final Identifier FRAME_DONE = Identifier.withDefaultNamespace("advancements/task_frame_obtained");
	private static final Identifier FRAME_TODO = Identifier.withDefaultNamespace("advancements/task_frame_unobtained");
	private static final Identifier FRAME_MAXED = Identifier.withDefaultNamespace("advancements/challenge_frame_obtained");
	private static final int NODE = 26;
	private static final int COLUMN = 44;
	private static final int ROW = 30;
	private static final int PADDING = 14;
	private static final int BORDER = 9;
	private static final int TITLE = 18;
	private static final int MIN_INSIDE_W = 234;
	private static final int MIN_INSIDE_H = 113;
	private static final String MORPH_ABILITY = "morph_ability";
	private static final String MORPH_ABILITIES = "morph_abilities";
	/** Opens the Arsenal: the arm forms (servers from 8.0.0). */
	private static final String ARM_SHAPING = "arm_shaping";
	/** The arm forms' upgrade ids are this plus the form id ("arm_katana"). */
	private static final String ARM_PREFIX = "arm_";
	/** The orbs' box (the Morph Ability's and Arm Shaping's). */
	private static final int ORB = 32;
	/** How far an upgrade sits right of the one it grows from, in a section's file tree. */
	private static final int INDENT = 22;
	private static final int PANEL_PAD = 8;
	private static final int PANEL_TITLE = 14;
	/**
	 * Gap between the upper sections and the orbs. It is 22 (it was 34, 2026-11-18): the upper row is then 418 wide, which with its padding is
	 * what a 960 by 520 window at GUI scale 2 shows, so the Arsenal is not cut off at the right on opening.
	 */
	private static final int GAP = 22;
	private static final int GRID = 32;
	private static final int ABILITY_COLUMNS = 7;
	/**
	 * The Arsenal grid is always three columns wide: two (74 pixels) is too narrow for its "Arsenal (locked)" title, and
	 * three keeps it shorter than the Morph panel beside it for up to 15 forms.
	 */
	private static final int ARSENAL_COLUMNS = 3;
	/** The Arsenal grid keeps three columns up to nine nodes; the twelve tools and four accessories (2026-11-16) make it four across and four down. */
	private static final int ARSENAL_WIDE_FROM = 10, ARSENAL_WIDE_COLUMNS = 4;

	/** The Arsenal grid's columns for {@code count} nodes. */
	static int arsenalColumns(int count) {
		return count >= ARSENAL_WIDE_FROM ? ARSENAL_WIDE_COLUMNS : ARSENAL_COLUMNS;
	}
	/** The gap between the upper row and the Abilities, below or beside it. */
	private static final int ABILITIES_GAP = 22;
	/** Arm icons: a form not yet buyable is drawn dim, as the Arsenal wheel draws one not owned. */
	private static final int LOCKED_TINT = 0xFF404040;

	/** Which part of the tree an upgrade is in: the server's chest menu sorts upgrades by the same rule. */
	public enum Section { BIOMASS, MORPH_ABILITY, MORPH, ABILITIES, ARM_SHAPING, ARSENAL, PASSIVES }

	/** The Passives grid is four columns wide (the eight passives of survival.yml make two rows). */
	private static final int PASSIVE_COLUMNS = 4;
	/** The window's margin to the screen's edge (GUI px): small, as the content pans and every pixel of the viewport counts at 240 tall. */
	private static final int SCREEN_MARGIN = 8;
	/** The scrollbar's width, drawn inside the frame's edge. */
	private static final int BAR = 3;

	/** A section's backdrop, in content coordinates. */
	public record Panel(Section section, int x, int y, int w, int h) {}

	private UpgradeTree tree;
	private TreeLayout layout;
	/** The left button went down on this screen (a release left over from the click that opened it buys nothing). */
	private boolean pressed;
	private int insideW;
	private int insideH;
	private int left;
	private int top;
	private double scrollX;
	private double scrollY;
	private boolean dragged;
	/** The first {@link #init} has run (a later one is a resize). */
	private boolean laidOut;
	private String message = "";
	private int messageTicks;
	private int ticks;
	/** When to ask the server for a fresh tree: it pushes one only after a purchase (the rig, 2026-11-16: stale levels after an admin change). */
	private final TreeRefreshPacer refresh = new TreeRefreshPacer(0);

	public UpgradeTreeScreen(UpgradeTree tree) {
		super(Component.translatable("morphreborn.upgrades.title"));
		this.tree = tree;
		this.layout = TreeLayout.of(tree.nodes(), 0, 0);
	}

	/**
	 * The server sent the tree again (a purchase's answer, or the one this screen asked for): show the new state in place. The levels, costs and
	 * texts are read from {@link #tree} on every frame, so nothing else is rebuilt; scroll and hover stay as they are. Only when the upgrades
	 * themselves changed (another tree, a server that reloaded survival.yml) is the layout redone, and then the scroll is kept too, clamped.
	 */
	public void update(UpgradeTree newTree) {
		boolean sameShape = tree.sameShape(newTree);
		this.tree = newTree;
		refresh.received(ticks);
		if (!newTree.message().isEmpty()) {
			message = newTree.message();
			messageTicks = 60;
		}
		if (!sameShape && width > 0) relayout(true);
	}

	/**
	 * Something this tree shows changed on the server (a passive's state, the biomass balance or capacity): ask for a fresh tree soon, not at
	 * once, so a stream of changes is one request a second at most ({@link TreeRefreshPacer}).
	 */
	public void dataChanged() {
		refresh.changed();
	}

	@Override
	protected void init() {
		// a resize or a GUI scale change re-inits this same screen: the view stays where the player had it
		relayout(laidOut);
		laidOut = true;
	}

	/** Lays the tree out for this window; {@code keepScroll}: the view stays where it was (clamped to the new content) instead of starting over. */
	private void relayout(boolean keepScroll) {
		layout = TreeLayout.of(tree.nodes(), width, height);
		insideW = Mth.clamp(layout.contentW(), MIN_INSIDE_W, TreeLayout.maxInsideWidth(width));
		insideH = Mth.clamp(layout.contentH(), MIN_INSIDE_H, TreeLayout.maxInsideHeight(height));
		left = (width - insideW - BORDER * 2) / 2;
		top = (height - insideH - TITLE - BORDER) / 2;
		// centred when it fits; otherwise it opens on the trunk (the Morph Ability's orb) and keeps the view across a resize
		scrollX = keepScroll ? TreeLayout.scrollFor(scrollX, layout.contentW(), insideW) : TreeLayout.initialScroll(layout.contentW(), insideW, layout.focusX());
		scrollY = keepScroll ? TreeLayout.scrollFor(scrollY, layout.contentH(), insideH) : TreeLayout.initialScroll(layout.contentH(), insideH, layout.focusY());
	}

	@Override
	public void tick() {
		ticks++;
		if (messageTicks > 0) messageTicks--;
		// Survival only: the server answers C_OPEN_UPGRADES in Classic with a chat line, and the mode can change under an open screen
		if (ClientNet.isSurvival() && refresh.due(ticks)) ClientNet.refreshUpgrades(); // counted, so a late answer after Esc cannot reopen the tree
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		dragged = false;
		pressed = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (Math.abs(dx) + Math.abs(dy) > 0.5) dragged = true;
		scroll(dx, dy);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		// Only a click made on this screen buys: the favourites wheel opens it on a click, and that click's release
		// lands here over whatever sits under the mouse.
		if (pressed && !dragged && event.button() == InputConstants.MOUSE_BUTTON_LEFT) { // 26.3 numbers mouse buttons from 1
			// a click on a bought passive's switch turns it on or off; anywhere else on the node it buys, as before
			UpgradeTree.Node toggle = switchAt(event.x(), event.y());
			if (toggle != null) {
				ClientNet.togglePassive(toggle.id());
			} else {
				UpgradeTree.Node node = nodeAt(event.x(), event.y());
				if (node != null) ClientNet.buyUpgrade(node.id());
			}
		}
		pressed = false;
		dragged = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollXAmount, double scrollYAmount) {
		// Shift + wheel goes across (a mouse with no sideways wheel could not reach the Arsenal column otherwise); a sideways wheel does too
		if (scrollXAmount == 0 && minecraft != null && minecraft.hasShiftDown()) scroll(scrollYAmount * 16, 0);
		else scroll(scrollXAmount * 16, scrollYAmount * 16);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (Keys.BIOMASS.matches(event)) {
			onClose();
			return true;
		}
		// the view can be moved from the keyboard: arrows or WASD, Page Up / Down, Home for the opening view
		int step = 24;
		switch (event.key()) {
			case InputConstants.KEY_LEFT, InputConstants.KEY_A -> scroll(step, 0);
			case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> scroll(-step, 0);
			case InputConstants.KEY_UP, InputConstants.KEY_W -> scroll(0, step);
			case InputConstants.KEY_DOWN, InputConstants.KEY_S -> scroll(0, -step);
			case InputConstants.KEY_PAGEUP -> scroll(0, insideH * 0.8);
			case InputConstants.KEY_PAGEDOWN -> scroll(0, -insideH * 0.8);
			case InputConstants.KEY_HOME -> {
				scrollX = TreeLayout.initialScroll(layout.contentW(), insideW, layout.focusX());
				scrollY = TreeLayout.initialScroll(layout.contentH(), insideH, layout.focusY());
			}
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	private void scroll(double dx, double dy) {
		if (layout.contentW() > insideW) scrollX = Mth.clamp(scrollX + dx, insideW - layout.contentW(), 0);
		if (layout.contentH() > insideH) scrollY = Mth.clamp(scrollY + dy, insideH - layout.contentH(), 0);
	}

	private int insideLeft() {
		return left + BORDER;
	}

	private int insideTop() {
		return top + TITLE;
	}

	/** The upgrades drawn as a lump of biomass instead of a framed icon: the Morph Ability and Arm Shaping. */
	private boolean isOrb(String id) {
		return layout.sectioned() && (id.equals(MORPH_ABILITY) || id.equals(ARM_SHAPING));
	}

	/**
	 * Whether ({@code mx}, {@code my}) is on something drawn over the tree that is not a node: a scrollbar along an edge the content runs
	 * past, or the strip with the server's last answer. A click there must not buy the node underneath (the bar is not a drag target).
	 */
	private boolean overCue(double mx, double my) {
		double right = insideLeft() + insideW;
		double bottom = insideTop() + insideH;
		if (layout.contentH() > insideH && mx >= right - BAR - 1) return true;
		if (layout.contentW() > insideW && my >= bottom - BAR - 1) return true;
		return messageTicks > 0 && !message.isEmpty() && my >= bottom - 13;
	}

	private UpgradeTree.@Nullable Node nodeAt(double mx, double my) {
		if (mx < insideLeft() || my < insideTop() || mx >= insideLeft() + insideW || my >= insideTop() + insideH) return null;
		if (overCue(mx, my)) return null;
		for (UpgradeTree.Node n : tree.nodes()) {
			int[] p = layout.position(n.id());
			if (p == null) continue;
			double x = insideLeft() + scrollX + p[0];
			double y = insideTop() + scrollY + p[1];
			if (p[2] == ORB && isOrb(n.id())) {
				if (BiomassOrb.contains(x + ORB / 2.0, y + ORB / 2.0, ORB / 32F, mx, my)) return n;
			} else if (mx >= x && mx < x + p[2] && my >= y && my < y + p[2]) {
				return n;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.nextStratum();
		drawWindow(g);
		drawInside(g);
		drawHeader(g);
		UpgradeTree.Node toggle = switchAt(mouseX, mouseY);
		UpgradeTree.Node hovered = toggle != null ? toggle : nodeAt(mouseX, mouseY);
		// wrapped to the screen and kept on it on every edge (vanilla's positioner clamps only the bottom)
		if (toggle != null) UiTooltip.show(g, font, switchTooltip(toggle), mouseX, mouseY);
		// the description (the line under the name) gives way first when the tooltip is too tall: the numbers and the cost are what it is for
		else if (hovered != null) UiTooltip.show(g, font, tooltip(hovered), mouseX, mouseY, TipUi.on() && !hovered.description().isEmpty() ? 1 : -1);
	}

	/** Vanilla-style window: light grey frame with a bevel, title on top. */
	private void drawWindow(GuiGraphicsExtractor g) {
		int x0 = left;
		int y0 = top;
		int x1 = insideLeft() + insideW + BORDER;
		int y1 = insideTop() + insideH + BORDER;
		g.fill(x0, y0, x1, y1, 0xFF000000);
		g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);
		g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);
		g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);
		g.fill(x0 + 3, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);
		g.fill(x1 - 3, y0 + 3, x1 - 1, y1 - 1, 0xFF555555);
		g.fill(insideLeft() - 1, insideTop() - 1, insideLeft() + insideW + 1, insideTop() + insideH + 1, 0xFF373737);
	}

	/**
	 * The title, and your biomass. The amount is read live rather than from the tree the server sent: the tree only
	 * comes again after a purchase, and a formed arm spends biomass while the window is open.
	 */
	private void drawHeader(GuiGraphicsExtractor g) {
		boolean live = ClientNet.isSurvival() && ClientNet.biomassUnlocked() && ClientNet.biomassCapacity() > 0;
		Component amount = tree.unlocked()
				? Component.translatable("morphreborn.hud.biomass", live ? ClientNet.biomass() : tree.biomass(), live ? ClientNet.biomassCapacity() : tree.capacity())
				: Component.translatable("morphreborn.upgrades.locked_short");
		// one line for both: the amount is cut with an ellipsis before it can run into the title (large capacities, a narrow window)
		int room = insideW - 8;
		Component shownTitle = UiText.fit(font, title, Math.max(24, room - Math.min(font.width(amount), room / 2) - 8));
		Component shownAmount = UiText.fit(font, amount, Math.max(24, room - font.width(shownTitle) - 8));
		g.text(font, shownTitle, left + 8, top + 6, 0xFF404040, false);
		g.text(font, shownAmount, insideLeft() + insideW - font.width(shownAmount), top + 6, tree.unlocked() ? 0xFF9E1B1B : 0xFF5D4037, false);
	}

	private void drawInside(GuiGraphicsExtractor g) {
		int ix = insideLeft();
		int iy = insideTop();
		g.enableScissor(ix, iy, ix + insideW, iy + insideH);
		drawStatic(g, ix, iy);
		int ox = ix + (int) Math.floor(scrollX);
		int oy = iy + (int) Math.floor(scrollY);
		if (layout.sectioned()) for (Panel panel : layout.panels()) drawPanel(g, panel, ox, oy);
		for (boolean outline : new boolean[] {true, false}) {
			for (UpgradeTree.Node n : tree.nodes()) {
				if (layout.sectioned()) drawSectionLink(g, n, ox, oy, outline);
				else drawLink(g, n, ox, oy, outline);
			}
		}
		float time = ticks + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (UpgradeTree.Node n : tree.nodes()) {
			if (isOrb(n.id())) drawOrb(g, n, ox, oy, time);
			else drawNode(g, n, ox, oy, time);
		}
		if (!tree.unlocked()) {
			// that biomass is locked is state; how to unlock it is a tip (the Tooltips setting), wrapped to the window (it is longer than a narrow one)
			List<FormattedCharSequence> how = TipUi.on() ? UiText.wrap(font, Component.translatable("morphreborn.upgrades.locked_how"), insideW - 16) : List.of();
			int band = 14 + how.size() * 10;
			int y0 = iy + (insideH - band) / 2;
			g.fill(ix, y0, ix + insideW, y0 + band + 4, 0xC0000000);
			g.centeredText(font, Component.translatable("morphreborn.upgrades.locked"), ix + insideW / 2, y0 + 4, 0xFFE57373);
			int ly = y0 + 16;
			for (FormattedCharSequence line : how) {
				g.centeredText(font, line, ix + insideW / 2, ly, 0xFFBDBDBD);
				ly += 10;
			}
		}
		drawScrollCues(g, ix, iy);
		// the last answer from the server (Biomass Capacity is already fully upgraded, not enough biomass...) in a strip inside the frame,
		// so it is on screen however small the window (it was drawn in the margin under the frame, which is now 8 px)
		if (messageTicks > 0 && !message.isEmpty()) {
			Component text = UiText.fit(font, Component.literal(message), insideW - 12);
			g.fill(ix, iy + insideH - 13, ix + insideW, iy + insideH, 0xD0000000);
			g.centeredText(font, text, ix + insideW / 2, iy + insideH - 11, 0xFFFFFFFF);
		}
		g.disableScissor();
	}

	/**
	 * What shows that the window pans: a scrollbar along each edge the content runs past, and a fade at every edge with more behind it (the
	 * content is a good deal bigger than a short window; nothing else said so).
	 */
	private void drawScrollCues(GuiGraphicsExtractor g, int ix, int iy) {
		boolean across = layout.contentW() > insideW;
		boolean down = layout.contentH() > insideH;
		if (!across && !down) return;
		int fade = 10;
		if (down && scrollY < -0.5) g.fillGradient(ix, iy, ix + insideW, iy + fade, 0xC0000000, 0x00000000);
		if (down && scrollY > insideH - layout.contentH() + 0.5) g.fillGradient(ix, iy + insideH - fade, ix + insideW, iy + insideH, 0x00000000, 0xC0000000);
		if (across && scrollX < -0.5) g.fillGradient(ix, iy, ix + fade, iy + insideH, 0xC0000000, 0x00000000);
		if (across && scrollX > insideW - layout.contentW() + 0.5) g.fillGradient(ix + insideW - fade, iy, ix + insideW, iy + insideH, 0x00000000, 0xC0000000);
		if (down) {
			int track = insideH - (across ? BAR + 2 : 0) - 2;
			int[] thumb = TreeLayout.thumb(track, insideH, layout.contentH(), -scrollY);
			int x = ix + insideW - BAR - 1;
			g.fill(x, iy + 1, x + BAR, iy + 1 + track, 0x60000000);
			g.fill(x, iy + 1 + thumb[0], x + BAR, iy + 1 + thumb[0] + thumb[1], 0xC0FFFFFF);
		}
		if (across) {
			int track = insideW - (down ? BAR + 2 : 0) - 2;
			int[] thumb = TreeLayout.thumb(track, insideW, layout.contentW(), -scrollX);
			int y = iy + insideH - BAR - 1;
			g.fill(ix + 1, y, ix + 1 + track, y + BAR, 0x60000000);
			g.fill(ix + 1 + thumb[0], y, ix + 1 + thumb[0] + thumb[1], y + BAR, 0xC0FFFFFF);
		}
	}

	/** The morph's black static as the backdrop, shifting every few ticks like the transformation skin. */
	private void drawStatic(GuiGraphicsExtractor g, int ix, int iy) {
		int frame = ticks / 3;
		int startX = ((int) Math.floor(scrollX) % 16) - 16;
		int startY = ((int) Math.floor(scrollY) % 16) - 16;
		for (int x = startX; x < insideW; x += 16) {
			for (int y = startY; y < insideH; y += 16) {
				int seed = (x * 31 + y * 17 + frame * 7) & 0x7FFFFFFF;
				g.blit(RenderPipelines.GUI_TEXTURED, MorphWorldRenderer.MORPH_SKIN, ix + x, iy + y, (seed % 48), ((seed / 48) % 48), 16, 16, 64, 64);
			}
		}
		g.fill(ix, iy, ix + insideW, iy + insideH, 0x66000000);
	}

	/** Advancement-style link to the parent: a black outline under a white line, dimmed while the parent isn't bought. */
	private void drawLink(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy, boolean outline) {
		int[] me = layout.position(n.id());
		int[] parent = layout.position(n.parent());
		if (me == null || parent == null) return;
		int px = ox + parent[0] + NODE;
		int py = oy + parent[1] + NODE / 2;
		int mx = ox + me[0];
		int my = oy + me[1] + NODE / 2;
		int split = px + (mx - px) / 2;
		boolean lit = levelOf(n.parent()) > 0;
		int col = outline ? 0xFF000000 : lit ? 0xFFFFFFFF : 0xFF808080;
		int w = outline ? 1 : 0;
		g.fill(px, py - w, split + w + 1, py + w + 1, col);
		g.fill(split - w, Math.min(py, my) - w, split + w + 1, Math.max(py, my) + w + 1, col);
		g.fill(split - w, my - w, mx, my + w + 1, col);
	}

	/** A section's backdrop: a dark panel with a coloured edge and its name. */
	private void drawPanel(GuiGraphicsExtractor g, Panel panel, int ox, int oy) {
		int x0 = ox + panel.x();
		int y0 = oy + panel.y();
		int x1 = x0 + panel.w();
		int y1 = y0 + panel.h();
		int edge = switch (panel.section()) {
			case BIOMASS -> 0xFF9E1B1B;
			case MORPH -> 0xFF7B1FA2;
			case ARSENAL -> 0xFF7A1A1A;
			case PASSIVES -> 0xFF2E7D6B;
			default -> 0xFFB26A00;
		};
		int text = switch (panel.section()) {
			case BIOMASS -> 0xFFEF9A9A;
			case MORPH -> 0xFFCE93D8;
			case ARSENAL -> 0xFFE05050;
			case PASSIVES -> 0xFF80CBC4;
			default -> 0xFFFFCC80;
		};
		g.fill(x0, y0, x1, y1, 0x90000000);
		g.fill(x0, y0, x1, y0 + 1, edge);
		g.fill(x0, y1 - 1, x1, y1, edge);
		g.fill(x0, y0, x0 + 1, y1, edge);
		g.fill(x1 - 1, y0, x1, y1, edge);
		Component name = Component.translatable("morphreborn.upgrades.section." + panel.section().name().toLowerCase(java.util.Locale.ROOT));
		// "(locked)" is state and stays; "buy Morph Abilities first" is the tip, so with tooltips off the short form is used
		if (panel.section() == Section.ABILITIES && levelOf(MORPH_ABILITIES) <= 0) {
			Component locked = Component.translatable("morphreborn.upgrades.section.abilities_locked", name);
			name = TipUi.on() && font.width(locked) <= panel.w() - 10 ? locked : Component.translatable("morphreborn.upgrades.section.abilities_locked_short", name);
		} else if (panel.section() == Section.ARSENAL && levelOf(ARM_SHAPING) <= 0) {
			Component locked = Component.translatable("morphreborn.upgrades.section.arsenal_locked", name);
			name = TipUi.on() && font.width(locked) <= panel.w() - 10 ? locked : Component.translatable("morphreborn.upgrades.section.arsenal_short", name);
		}
		g.text(font, name, x0 + 5, y0 + 4, text, false);
	}

	/**
	 * Links in the sectioned tree. Inside a section: down from the upgrade it grows from, then across (a file tree).
	 * The Morph section's first upgrades hang off the orb; the orb off Biomass's first upgrade (it needs Biomass
	 * Capacity); the Abilities section off Morph Abilities. Arm Shaping hangs off the Morph Ability too, by a line that
	 * climbs out of the top of its orb and runs above the Morph section, and the Arsenal off Arm Shaping.
	 */
	private void drawSectionLink(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy, boolean outline) {
		int[] me = layout.position(n.id());
		if (me == null) return;
		int col = outline ? 0xFF000000 : 0xFF808080;
		int w = outline ? 1 : 0;
		Section section = layout.section(n.id());
		int[] parent = layout.position(n.parent());
		if (section == Section.MORPH_ABILITY) {
			Panel biomass = layout.panel(Section.BIOMASS);
			UpgradeTree.Node first = firstIn(Section.BIOMASS);
			if (biomass == null || first == null) return;
			int[] from = layout.position(first.id());
			boolean lit = n.level() > 0 || n.missing().isEmpty();
			int c = outline ? col : lit ? 0xFFFFFFFF : col;
			int px = ox + biomass.x() + biomass.w();
			int py = oy + from[1] + NODE / 2;
			int split = px + GAP / 2;
			int my = oy + me[1] + ORB / 2;
			hLine(g, ox + from[0] + NODE, px, py, w, c);
			hLine(g, px, split, py, w, c);
			vLine(g, split, py, my, w, c);
			hLine(g, split, ox + me[0] + 2, my, w, c);
			return;
		}
		if (section == Section.ARM_SHAPING) {
			// up out of the Morph Ability's orb, over the top of the Morph section, and down into Arm Shaping's
			int[] from = layout.position(MORPH_ABILITY);
			if (from == null) return;
			boolean lit = n.level() > 0 || n.missing().isEmpty();
			int c = outline ? col : lit ? 0xFFFFFFFF : col;
			int fx = ox + from[0] + ORB / 2;
			int tx = ox + me[0] + ORB / 2;
			int above = oy + layout.upperTop() - 7;
			vLine(g, fx, oy + from[1] + 2, above, w, c);
			hLine(g, fx, tx, above, w, c);
			vLine(g, tx, above, oy + me[1] + 2, w, c);
			return;
		}
		if (section == Section.ARSENAL) {
			// one short line from Arm Shaping's orb into the Arsenal, as the Abilities have one from Morph Abilities
			Panel arsenal = layout.panel(Section.ARSENAL);
			int[] orb = layout.position(ARM_SHAPING);
			if (arsenal == null || orb == null || firstIn(Section.ARSENAL) != n) return;
			int c = outline ? col : levelOf(ARM_SHAPING) > 0 ? 0xFFFFFFFF : col;
			int y = oy + orb[1] + ORB / 2;
			hLine(g, ox + orb[0] + ORB - 2, ox + arsenal.x(), y, w, c);
			return;
		}
		if (section == Section.ABILITIES) {
			if (!n.parent().equals(MORPH_ABILITIES) || parent == null || firstIn(Section.ABILITIES) != n) return;
			Panel abilities = layout.panel(Section.ABILITIES);
			int c = outline ? col : levelOf(MORPH_ABILITIES) > 0 ? 0xFFFFFFFF : col;
			if (layout.abilitiesBeside()) {
				int px = ox + parent[0] + NODE;
				int py = oy + parent[1] + NODE / 2;
				int ax = ox + abilities.x();
				int ay = Mth.clamp(py, oy + abilities.y() + 8, oy + abilities.y() + abilities.h() - 8);
				int split = ax - 11;
				Panel morph = layout.panel(Section.MORPH);
				if (layout.armRow() && morph != null) {
					// Arm Shaping and the Arsenal stand between the Morph section and the Abilities: go round under them
					int out = ox + morph.x() + morph.w() + 11;
					int under = oy + layout.upperBottom() + 6;
					hLine(g, px, out, py, w, c);
					vLine(g, out, py, under, w, c);
					hLine(g, out, split, under, w, c);
					vLine(g, split, under, ay, w, c);
				} else {
					hLine(g, px, split, py, w, c);
					vLine(g, split, py, ay, w, c);
				}
				hLine(g, split, ax, ay, w, c);
				return;
			}
			int px = ox + parent[0] + NODE / 2;
			vLine(g, px, oy + parent[1] + NODE, oy + abilities.y(), w, c);
			return;
		}
		if (section == Section.PASSIVES) {
			// the passives are a grid under Biomass: one line from the upgrade they grow from (Biomass Capacity) down into the panel
			Panel passives = layout.panel(Section.PASSIVES);
			if (passives == null || parent == null || firstIn(Section.PASSIVES) != n) return;
			int c = outline ? col : levelOf(n.parent()) > 0 ? 0xFFFFFFFF : col;
			vLine(g, ox + parent[0] + NODE / 2, oy + parent[1] + NODE, oy + passives.y(), w, c);
			return;
		}
		if (parent == null) return;
		boolean lit = levelOf(n.parent()) > 0;
		int c = outline ? col : lit ? 0xFFFFFFFF : col;
		int my = oy + me[1] + NODE / 2;
		if (n.parent().equals(MORPH_ABILITY)) {
			// hangs off the orb: across from the orb, down the gap, across into the section
			int px = ox + parent[0] + ORB - 2;
			int py = oy + parent[1] + ORB / 2;
			int split = px + 2 + GAP / 2;
			hLine(g, px, split, py, w, c);
			vLine(g, split, py, my, w, c);
			hLine(g, split, ox + me[0], my, w, c);
			return;
		}
		int px = ox + parent[0] + NODE / 2;
		vLine(g, px, oy + parent[1] + NODE, my, w, c);
		hLine(g, px, ox + me[0], my, w, c);
	}

	private UpgradeTree.@Nullable Node firstIn(Section section) {
		for (UpgradeTree.Node n : tree.nodes()) if (layout.section(n.id()) == section) return n;
		return null;
	}

	private static void hLine(GuiGraphicsExtractor g, int x0, int x1, int y, int w, int color) {
		g.fill(Math.min(x0, x1) - w, y - w, Math.max(x0, x1) + w + 1, y + w + 1, color);
	}

	private static void vLine(GuiGraphicsExtractor g, int x, int y0, int y1, int w, int color) {
		g.fill(x - w, Math.min(y0, y1) - w, x + w + 1, Math.max(y0, y1) + w + 1, color);
	}

	/**
	 * The orbs. The Morph Ability: a lump of biomass with tendrils, its icon in the middle; gold-ringed once bought.
	 * Arm Shaping: the bladed lump (dark blood-red ring, tendrils hooked like sickles, a katana of static inside), also
	 * gold-ringed once bought. Both glow while they can be bought.
	 */
	private void drawOrb(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy, float time) {
		int[] p = layout.position(n.id());
		if (p == null) return;
		float cx = ox + p[0] + ORB / 2F;
		float cy = oy + p[1] + ORB / 2F;
		boolean buyable = n.buyable(tree.unlocked());
		float glow = buyable ? 0.6F + 0.4F * (float) Math.sin(time * 0.2) : 0;
		if (n.id().equals(ARM_SHAPING)) {
			BiomassOrb.drawBlade(g, cx, cy, ORB / 32F, time, n.level() > 0 ? 0xFFE0B040 : BiomassOrb.BLADE_RING, BiomassOrb.BLADE_TINT, glow);
			return;
		}
		int ring = n.level() > 0 ? 0xFFE0B040 : buyable ? 0xFFB71C1C : 0xFF5A5A5A;
		int tint = n.level() > 0 ? 0x307B1FA2 : 0x509E1B1B;
		BiomassOrb.draw(g, cx, cy, ORB / 32F, time, ring, tint, glow);
		if (n.icon().startsWith("effect:")) {
			Identifier effect = Identifier.tryParse(n.icon().substring("effect:".length()));
			if (effect != null) g.blitSprite(RenderPipelines.GUI_TEXTURED, effect.withPrefix("mob_effect/"), Math.round(cx) - 9, Math.round(cy) - 9, 18, 18);
		} else {
			g.fakeItem(icon(n.icon()), Math.round(cx) - 8, Math.round(cy) - 8);
		}
	}

	private int levelOf(String id) {
		for (UpgradeTree.Node n : tree.nodes()) if (n.id().equals(id)) return n.level();
		return 1;
	}

	private void drawNode(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy, float time) {
		int[] p = layout.position(n.id());
		if (p == null) return;
		int x = ox + p[0];
		int y = oy + p[1];
		if (n.buyable(tree.unlocked())) {
			// Something you can buy right now: a soft pulsing red glow behind the frame.
			int alpha = 0x50 + (int) (0x40 * (0.5 + 0.5 * Math.sin(ticks * 0.2)));
			g.fill(x - 2, y - 2, x + NODE + 2, y + NODE + 2, (alpha << 24) | 0xE53935);
		}
		Identifier frame = n.maxed() ? FRAME_MAXED : n.level() > 0 ? FRAME_DONE : FRAME_TODO;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, frame, x, y, NODE, NODE);
		String form = layout.section(n.id()) == Section.ARSENAL ? armForm(n.id()) : null;
		if (form != null) {
			drawArm(g, n, form, x, y, time);
		} else if (n.icon().startsWith("effect:")) {
			// the status effect's own icon (the server's survival.yml picks it), as the inventory draws it
			Identifier effect = Identifier.tryParse(n.icon().substring("effect:".length()));
			if (effect != null) g.blitSprite(RenderPipelines.GUI_TEXTURED, effect.withPrefix("mob_effect/"), x + 4, y + 4, 18, 18);
		} else {
			g.fakeItem(icon(n.icon()), x + 5, y + 5);
		}
		if (n.maxLevel() > 1 && n.level() > 0) {
			String level = String.valueOf(n.level());
			g.text(font, level, x + NODE - 2 - font.width(level), y + NODE - 9, 0xFFFFFFFF, true);
		}
		if (ClientNet.canTogglePassive(n.id())) drawSwitch(g, ClientNet.PASSIVES.state(n.id()), x, y);
	}

	// ------------------------------------------------------------------ passive toggles (8.0.0-beta.2)

	/**
	 * The passive switch's drawn size and where it sits on a node: bottom left (the level number is bottom right), in the strip under the icon
	 * (the item icon ends at y+21, a status effect's at y+22), so it never covers the icon and a click on the icon still buys.
	 * The black border runs one pixel past this on every side.
	 */
	private static final int SWITCH_W = 12, SWITCH_H = 3, SWITCH_X = 2, SWITCH_Y = NODE - 4;

	/**
	 * A small On/Off switch on a bought passive (2026-11-16: toggles in the upgrades menu), coloured as the chest menu's panes are:
	 * green running, yellow on but idle, red on but out of biomass, grey off. The knob sits right when it is on, left when it is off.
	 */
	private static void drawSwitch(GuiGraphicsExtractor g, PassiveProtocol.State state, int x, int y) {
		int sx = x + SWITCH_X;
		int sy = y + SWITCH_Y;
		g.fill(sx - 1, sy - 1, sx + SWITCH_W + 1, sy + SWITCH_H + 1, 0xFF000000);
		g.fill(sx, sy, sx + SWITCH_W, sy + SWITCH_H, state.colour());
		int knob = state.on() ? sx + SWITCH_W - 4 : sx;
		g.fill(knob, sy, knob + 4, sy + SWITCH_H, 0xFFFFFFFF);
	}

	/** The bought passive whose switch is under the mouse (its strip, so a click on the icon above still buys), or null. */
	private UpgradeTree.@Nullable Node switchAt(double mx, double my) {
		if (mx < insideLeft() || my < insideTop() || mx >= insideLeft() + insideW || my >= insideTop() + insideH) return null;
		if (overCue(mx, my)) return null;
		for (UpgradeTree.Node n : tree.nodes()) {
			if (isOrb(n.id()) || !ClientNet.canTogglePassive(n.id())) continue;
			int[] p = layout.position(n.id());
			if (p == null) continue;
			double sx = insideLeft() + scrollX + p[0] + SWITCH_X;
			double sy = insideTop() + scrollY + p[1] + SWITCH_Y;
			// the switch is drawn small but hit from a bigger strip (a click one pixel off bought another level at GUI scale 1): the strip
			// under the icon down to the node's bottom edge, and a little to the right of the switch
			if (mx >= sx - SWITCH_X && mx < sx + SWITCH_W + 4 && my >= sy - 2 && my < sy - SWITCH_Y + NODE) return n;
		}
		return null;
	}

	/** The tooltip over a switch: the passive, what it is doing, and what a click does. */
	private static List<Component> switchTooltip(UpgradeTree.Node n) {
		PassiveProtocol.State state = ClientNet.PASSIVES.state(n.id());
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(n.name()).withStyle(ChatFormatting.WHITE));
		lines.add(stateLine(state));
		// what the switch is doing stays; what a click does is a tip
		if (TipUi.on()) lines.add(Component.translatable(state.on() ? "morphreborn.passive.click_off" : "morphreborn.passive.click_on").withStyle(ChatFormatting.YELLOW));
		return lines;
	}

	/** "On: running" in the state's colour (green running, yellow idle, red no biomass, grey off). */
	private static MutableComponent stateLine(PassiveProtocol.State state) {
		return Component.translatable(state.langKey()).withStyle(s -> s.withColor(state.colour() & 0xFFFFFF));
	}

	/** The form an Arsenal upgrade shapes your arm into ("arm_katana": "katana"), or null for any other upgrade. */
	private static @Nullable String armForm(String id) {
		if (!id.startsWith(ARM_PREFIX) || id.equals(ARM_SHAPING)) return null;
		String form = id.substring(ARM_PREFIX.length());
		return ArmWheel.hasItem(form) || dev.morphreborn.client.arm.ArmProtocol.isAccessory(form) ? form : null;
	}

	/**
	 * An Arsenal form's icon: your own arm shaped into it at the tier bought (iron before it is bought), turning as on
	 * the wheel; an accessory, which has no item, as its wheel lump's icon (the lantern's lure, the vanilla shield, spyglass or
	 * flint and steel). Dim while it can't be bought yet (Arm Shaping first).
	 */
	private void drawArm(GuiGraphicsExtractor g, UpgradeTree.Node n, String form, int x, int y, float time) {
		boolean locked = !tree.unlocked() || (n.level() <= 0 && !n.missing().isEmpty());
		if (ArmWheel.hasItem(form)) {
			int tier = Math.clamp(n.level(), 1, 3);
			ArmWheel.drawIcon(g, form, tier, x + 5, y + 5, 1F, ArmWheel.turntable(time), 20F, locked ? LOCKED_TINT : 0xFFFFFFFF);
		} else if (form.equals(dev.morphreborn.client.arm.ArmProtocol.LANTERN)) {
			ArmWheel.drawLure(g, x + NODE / 2F + 3F, y + NODE / 2F - 2F, 0.8F, time, n.level() > 0 && Arms.arsenal().lanternLit(), locked);
		} else {
			ArmWheel.drawAccessory(g, form, x + NODE / 2F, y + NODE / 2F, 0.8F, time, n.level() > 0 && accessoryOn(form), locked);
		}
	}

	/** Whether accessory {@code id} is on (your own arm state's say, else the arsenal's), for its tree icon. */
	private static boolean accessoryOn(String id) {
		var own = net.minecraft.client.Minecraft.getInstance().player;
		var state = own == null ? null : Arms.state(own.getUUID());
		if (state != null) return state.accessoryOn(id);
		var entry = Arms.arsenal().accessory(id);
		return entry != null && entry.on();
	}

	private static ItemStack icon(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return new ItemStack(Items.PAPER);
		return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.PAPER));
	}

	/**
	 * The tooltip over an upgrade. Typed lines (Tagged), filtered by the Tooltips setting: the name, level, now and next values, cost and what is
	 * needed always stay; the description (what it does, how to use it) and what a click does are tips. The server has already left its own
	 * explanation out of the description and the values for a player who turned Tooltips off, so with it off this shows only the numbers.
	 */
	private List<Component> tooltip(UpgradeTree.Node n) {
		List<Tagged<Component>> lines = new ArrayList<>();
		lines.add(Tagged.always(Component.literal(n.name()).withStyle(n.maxed() ? ChatFormatting.GOLD : n.level() > 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
		if (!n.description().isEmpty()) lines.add(Tagged.tip(Component.literal(n.description()).withStyle(ChatFormatting.GRAY)));
		lines.add(Tagged.always(Component.empty()));
		if (n.maxLevel() == 1) lines.add(Tagged.always(Component.translatable(n.level() > 0 ? "morphreborn.upgrades.bought" : "morphreborn.upgrades.not_bought")));
		else lines.add(Tagged.always(Component.translatable("morphreborn.upgrades.level", n.level(), n.maxLevel())));
		if (!n.now().isEmpty()) addValue(lines, "morphreborn.upgrades.now", n.now());
		// only what the next level changes: the rest is the "Now:" line again (Tendril Flint's two sentences made the tooltip as tall as the window)
		if (!n.next().isEmpty()) addValue(lines, "morphreborn.upgrades.next", ValueDiff.changed(n.now(), n.next()));
		if (!n.maxed()) {
			MutableComponent cost = Component.translatable("morphreborn.upgrades.cost", n.cost());
			lines.add(Tagged.always(cost.withStyle(n.affordable() ? ChatFormatting.GREEN : ChatFormatting.RED)));
		}
		if (!n.missing().isEmpty() && !n.maxed()) lines.add(Tagged.always(Component.translatable("morphreborn.upgrades.needs", n.missing()).withStyle(ChatFormatting.RED)));
		if (ClientNet.canTogglePassive(n.id())) {
			PassiveProtocol.State passive = ClientNet.PASSIVES.state(n.id());
			lines.add(Tagged.always(stateLine(passive)));
			lines.add(Tagged.tip(Component.translatable(passive.on() ? "morphreborn.passive.hint_off" : "morphreborn.passive.hint_on").withStyle(ChatFormatting.GRAY)));
		}
		lines.add(Tagged.always(Component.empty()));
		if (n.maxed()) lines.add(Tagged.always(Component.translatable(n.maxLevel() == 1 ? "morphreborn.upgrades.unlocked" : "morphreborn.upgrades.maxed").withStyle(ChatFormatting.GOLD)));
		else if (!tree.unlocked()) lines.add(Tagged.always(Component.translatable("morphreborn.upgrades.locked_short").withStyle(ChatFormatting.GRAY)));
		else if (n.missing().isEmpty()) lines.add(Tagged.tip(Component.translatable("morphreborn.upgrades.click").withStyle(ChatFormatting.YELLOW)));
		return Tagged.filter(lines, TipUi.on(), UpgradeTreeScreen::blank);
	}

	/** A spacer line: no text of its own and no siblings (asked of each line on every frame the tooltip is up, so it builds no string). */
	private static boolean blank(Component c) {
		return c.getContents() instanceof PlainTextContents text && text.text().isEmpty() && c.getSiblings().isEmpty();
	}

	/** "Now: ..." or "Next: ...": one line, wrapped to the screen's width by {@link UiTooltip} like the description (a level's numbers can be long). */
	private static void addValue(List<Tagged<Component>> lines, String key, String value) {
		lines.add(Tagged.always(Component.translatable(key, value).withStyle(ChatFormatting.AQUA)));
	}

	// ------------------------------------------------------------------ layout

	/**
	 * Where every upgrade, orb and section panel goes, in content coordinates (the window scrolls over them). Pure: it
	 * reads only the tree's ids and parents and the screen's size, so it is tested without a game.
	 * <p>
	 * The sectioned tree's upper row, left to right: [Biomass] [Morph Ability orb] [Morph] [Arm Shaping orb] [Arsenal],
	 * 22 apart, all hanging from the row's top (the orbs level with the first upgrade of Biomass, so the trunk is one run
	 * along the top of the first screen). Biomass and Morph list their upgrades as file trees, the Arsenal is a grid
	 * three or four wide. The lower row, under the upper one, holds the Passives (a grid four wide, under Biomass, which
	 * they grow from) and the Abilities (up to seven wide, filling the rest of the row). Without Passives, the Abilities
	 * go beside the upper row instead in a window too short for them below it and wide enough. The standard tree
	 * (survival.yml as shipped: 4 + 6 upgrades, 13 abilities, 8 passives, 16 arm forms) is 418 wide across the upper row and
	 * 446 by 330 with the lower row. That is more than a short window shows, so the window pans (scrollbars, arrow keys,
	 * shift + wheel) and opens on the trunk. Without Arm Shaping in the tree the row stops after Morph, as before arm forms.
	 */
	public static final class TreeLayout {
		/** Node id -> {x, y, size}. */
		private final Map<String, int[]> positions = new HashMap<>();
		private final Map<Section, Panel> panels = new EnumMap<>(Section.class);
		private final Map<String, Section> sections = new HashMap<>();
		private boolean sectioned;
		private boolean abilitiesBeside;
		private boolean armRow;
		private int contentW;
		private int contentH;
		private int upperSpan;
		private int upperTop;
		private int upperBottom;

		private TreeLayout() {}

		/**
		 * Lays out {@code nodes} for a screen {@code screenW} by {@code screenH} GUI pixels (0 by 0 before the screen
		 * knows its size: then the Abilities go below).
		 */
		public static TreeLayout of(List<UpgradeTree.Node> nodes, int screenW, int screenH) {
			TreeLayout l = new TreeLayout();
			l.sectioned = nodes.stream().anyMatch(n -> n.id().equals(MORPH_ABILITY));
			if (l.sectioned) l.layoutSections(nodes, screenW, screenH);
			else l.layoutTidy(nodes);
			return l;
		}

		/**
		 * The scroll offset (zero or negative) for content {@code content} wide or tall in a window {@code inside} across, starting from
		 * {@code scroll}: centred when it all fits, otherwise kept within the content.
		 */
		public static double scrollFor(double scroll, int content, int inside) {
			if (content <= inside) return (inside - content) / 2.0;
			return Math.clamp(scroll, (double) (inside - content), 0.0);
		}

		/**
		 * The scroll the window opens on, along one axis: the content's point {@code focus} in the middle of the window when the content is
		 * bigger (kept within the content), the content centred when it fits.
		 */
		public static double initialScroll(int content, int inside, int focus) {
			if (content <= inside) return (inside - content) / 2.0;
			return Math.clamp(inside / 2.0 - focus, (double) (inside - content), 0.0);
		}

		/** The widest the window's inside gets on a screen this wide (its frame and a margin round it). */
		public static int maxInsideWidth(int screenW) {
			return Math.max(MIN_INSIDE_W, screenW - SCREEN_MARGIN * 2 - BORDER * 2);
		}

		/** The tallest the window's inside gets on a screen this tall (its title, frame and a margin round it). */
		public static int maxInsideHeight(int screenH) {
			return Math.max(MIN_INSIDE_H, screenH - SCREEN_MARGIN * 2 - BORDER - TITLE);
		}

		/**
		 * A scrollbar's thumb on a track {@code track} long, for a window {@code inside} across showing content {@code content} across,
		 * scrolled {@code offset} in (zero or more): {start, length} along the track, at least 10 long and always within it.
		 */
		public static int[] thumb(int track, int inside, int content, double offset) {
			if (content <= inside || track <= 0) return new int[] {0, Math.max(0, track)};
			int length = Math.clamp(Math.round((float) track * inside / content), Math.min(10, track), track);
			double share = Math.clamp(offset / (content - inside), 0.0, 1.0);
			return new int[] {(int) Math.round((track - length) * share), length};
		}

		/** The content point the window opens on: the middle of the Morph Ability's orb (the tree's trunk), or the top left of a plain tree. */
		public int focusX() {
			int[] orb = positions.get(MORPH_ABILITY);
			return sectioned && orb != null ? orb[0] + ORB / 2 : 0;
		}

		public int focusY() {
			int[] orb = positions.get(MORPH_ABILITY);
			return sectioned && orb != null ? orb[1] + ORB / 2 : 0;
		}

		/**
		 * Which section {@code id} is in, by its ancestry, as the server's chest menu decides it: the Morph Ability and
		 * Arm Shaping are their own; then the nearest of Morph Abilities (Abilities) and Arm Shaping (Arsenal) up the
		 * parent chain; then anything under the Morph Ability is Morph, and the rest Biomass. Arm Shaping is tested
		 * before the Morph Ability on purpose: it hangs off the Morph Ability, so the plain walk would file it and every
		 * form under Morph (as a 7.5.0 client does).
		 *
		 * @param parentOf an upgrade's parent ("root" for none), or null for an id that isn't in the tree
		 */
		public static Section sectionOf(String id, Function<String, @Nullable String> parentOf) {
			if (id.equals(MORPH_ABILITY)) return Section.MORPH_ABILITY;
			if (id.equals(ARM_SHAPING)) return Section.ARM_SHAPING;
			// the passives (8.0.0): a grid of their own, as the chest menu gives them a page (they hang off Biomass Capacity)
			if (id.startsWith("passive_")) return Section.PASSIVES;
			boolean underMorph = false;
			String parent = parentOf.apply(id);
			for (int guard = 0; guard < 64 && parent != null && !parent.equals("root"); guard++) {
				if (parent.equals(MORPH_ABILITIES)) return Section.ABILITIES;
				if (parent.equals(ARM_SHAPING)) return Section.ARSENAL;
				if (parent.equals(MORPH_ABILITY)) underMorph = true;
				parent = parentOf.apply(parent);
			}
			return underMorph ? Section.MORPH : Section.BIOMASS;
		}

		/** The node's {x, y, size}, or null when it isn't placed. */
		public int @Nullable [] position(String id) {
			return positions.get(id);
		}

		public @Nullable Panel panel(Section section) {
			return panels.get(section);
		}

		public java.util.Collection<Panel> panels() {
			return Collections.unmodifiableCollection(panels.values());
		}

		public @Nullable Section section(String id) {
			return sections.get(id);
		}

		/** Laid out in sections (the tree has the Morph Ability); otherwise as one plain tree. */
		public boolean sectioned() {
			return sectioned;
		}

		/** The Abilities sit at the end of the upper row instead of below it (a short window). */
		public boolean abilitiesBeside() {
			return abilitiesBeside;
		}

		/** Arm Shaping or the Arsenal is in the upper row after the Morph section. */
		public boolean armRow() {
			return armRow;
		}

		public int contentW() {
			return contentW;
		}

		public int contentH() {
			return contentH;
		}

		/** How wide the upper row is, from Biomass's left edge to the last panel's right edge. */
		public int upperSpan() {
			return upperSpan;
		}

		/** The upper row's top and bottom (the tallest of its panels and orbs). */
		public int upperTop() {
			return upperTop;
		}

		public int upperBottom() {
			return upperBottom;
		}

		private void layoutSections(List<UpgradeTree.Node> nodes, int screenW, int screenH) {
			Map<String, UpgradeTree.Node> byId = new HashMap<>();
			for (UpgradeTree.Node n : nodes) byId.put(n.id(), n);
			Function<String, @Nullable String> parentOf = id -> {
				UpgradeTree.Node n = byId.get(id);
				return n == null ? null : n.parent();
			};
			Map<Section, List<UpgradeTree.Node>> bySection = new EnumMap<>(Section.class);
			for (UpgradeTree.Node n : nodes) {
				Section section = sectionOf(n.id(), parentOf);
				sections.put(n.id(), section);
				bySection.computeIfAbsent(section, k -> new ArrayList<>()).add(n);
			}
			List<Object[]> biomassRows = fileTree(bySection.getOrDefault(Section.BIOMASS, List.of()));
			List<Object[]> morphRows = fileTree(bySection.getOrDefault(Section.MORPH, List.of()));
			List<UpgradeTree.Node> abilities = bySection.getOrDefault(Section.ABILITIES, List.of());
			List<UpgradeTree.Node> arsenal = bySection.getOrDefault(Section.ARSENAL, List.of());
			List<UpgradeTree.Node> passives = bySection.getOrDefault(Section.PASSIVES, List.of());
			boolean shaping = byId.containsKey(ARM_SHAPING);
			armRow = shaping || !arsenal.isEmpty();

			int biomassW = panelWidth(biomassRows);
			int morphW = panelWidth(morphRows);
			int arsenalColumns = arsenalColumns(arsenal.size());
			int arsenalW = arsenal.isEmpty() ? 0 : arsenalColumns * GRID - (GRID - NODE) + PANEL_PAD * 2;
			int arsenalH = arsenal.isEmpty() ? 0 : gridHeight((arsenal.size() + arsenalColumns - 1) / arsenalColumns);
			int passiveColumns = Math.clamp(passives.size(), 1, PASSIVE_COLUMNS);
			int passivesW = passives.isEmpty() ? 0 : passiveColumns * GRID - (GRID - NODE) + PANEL_PAD * 2;
			int passivesH = passives.isEmpty() ? 0 : gridHeight((passives.size() + passiveColumns - 1) / passiveColumns);
			int upperH = Math.max(Math.max(panelHeight(biomassRows.size()), panelHeight(morphRows.size())), Math.max(ORB + 8, arsenalH));
			upperSpan = biomassW + GAP + ORB + GAP + morphW + (shaping ? GAP + ORB : 0) + (arsenal.isEmpty() ? 0 : GAP + arsenalW);
			int n = abilities.size();
			// Below the other sections (as approved), unless the window is too short for that and wide enough to put
			// them beside the upper row, in as few rows as the sections next to them
			int belowColumns = Math.clamp(n, 1, ABILITY_COLUMNS);
			int belowH = gridHeight((n + belowColumns - 1) / belowColumns);
			int naturalW = belowColumns * GRID - (GRID - NODE) + PANEL_PAD * 2;
			int belowW = Math.max(naturalW, upperSpan);
			int availW = screenW > 0 ? maxInsideWidth(screenW) : 0;
			int availH = screenH > 0 ? maxInsideHeight(screenH) : Integer.MAX_VALUE;
			// beside: as many rows as the window has room for (at least as many as fit next to the other sections), so
			// the grid is as narrow as it can be
			int rowsBesideUpper = (upperH - PANEL_TITLE - PANEL_PAD + (GRID - NODE)) / GRID;
			int rowsInWindow = availH == Integer.MAX_VALUE ? n : (availH - PADDING * 2 - PANEL_TITLE - PANEL_PAD + (GRID - NODE)) / GRID;
			int sideRows = Math.clamp(Math.max(rowsBesideUpper, rowsInWindow), 1, Math.max(1, n));
			int sideColumns = Math.max(1, (n + sideRows - 1) / sideRows);
			int sideW = sideColumns * GRID - (GRID - NODE) + PANEL_PAD * 2;
			int sideH = gridHeight((n + sideColumns - 1) / sideColumns);
			// Passives share the lower row with the Abilities (2026-11-18): the window pans now, and the row beside the others only widened it
			abilitiesBeside = passives.isEmpty() && n > 0 && PADDING * 2 + upperH + ABILITIES_GAP + belowH > availH
					&& PADDING * 2 + upperSpan + ABILITIES_GAP + sideW <= availW;
			int columns = abilitiesBeside ? sideColumns : belowColumns;
			int gridRows = (n + columns - 1) / columns;
			int gridW = columns * GRID - (GRID - NODE);
			int abilitiesW;
			int span;
			// the lower row, passives then abilities: its width, and the left edge it starts from
			int lowerNeed = passivesW + (n == 0 ? 0 : (passives.isEmpty() ? 0 : ABILITIES_GAP) + naturalW);
			int lowerX = PADDING;
			if (!passives.isEmpty()) {
				span = Math.max(upperSpan, lowerNeed);
				// the Abilities fill what is left of the row's width (as they filled all of it when they were alone)
				abilitiesW = n == 0 ? 0 : Math.max(naturalW, span - passivesW - ABILITIES_GAP);
			} else {
				abilitiesW = abilitiesBeside ? sideW : belowW;
				span = n == 0 ? upperSpan : abilitiesBeside ? upperSpan + ABILITIES_GAP + sideW : Math.max(upperSpan, belowW);
			}

			int x = PADDING + (abilitiesBeside ? 0 : (span - upperSpan) / 2);
			// side by side, the shorter of the two is centred on the taller
			int y = PADDING + (abilitiesBeside ? Math.max(0, (sideH - upperH) / 2) : 0);
			upperTop = y;
			upperBottom = y + upperH;
			// Everything in the upper row hangs from its top (2026-11-18): the orbs sit level with the first upgrade of Biomass, so the trunk
			// (Biomass, orb, Morph, orb, Arsenal) is one run along the top of the first screen and the window opens on it, not on a middle that
			// is off screen in a short window.
			int orbY = y + PANEL_TITLE + (NODE - ORB) / 2;
			if (!biomassRows.isEmpty()) {
				Panel panel = new Panel(Section.BIOMASS, x, y, biomassW, panelHeight(biomassRows.size()));
				panels.put(Section.BIOMASS, panel);
				placeRows(panel, biomassRows);
			}
			x += biomassW + GAP;
			positions.put(MORPH_ABILITY, new int[] {x, orbY, ORB});
			x += ORB + GAP;
			if (!morphRows.isEmpty()) {
				Panel panel = new Panel(Section.MORPH, x, y, morphW, panelHeight(morphRows.size()));
				panels.put(Section.MORPH, panel);
				placeRows(panel, morphRows);
			}
			x += morphW;
			if (shaping) {
				x += GAP;
				positions.put(ARM_SHAPING, new int[] {x, orbY, ORB});
				x += ORB;
			}
			if (!arsenal.isEmpty()) {
				x += GAP;
				Panel panel = new Panel(Section.ARSENAL, x, y, arsenalW, arsenalH);
				panels.put(Section.ARSENAL, panel);
				placeGrid(panel, arsenal, Math.min(arsenalColumns, arsenal.size()));
				x += arsenalW;
			}
			int bottom = upperBottom;
			int lowerY = bottom + ABILITIES_GAP;
			if (!passives.isEmpty()) {
				Panel panel = new Panel(Section.PASSIVES, lowerX, lowerY, passivesW, passivesH);
				panels.put(Section.PASSIVES, panel);
				placeGrid(panel, passives, passiveColumns);
				bottom = Math.max(bottom, lowerY + passivesH);
			}
			if (!abilities.isEmpty()) {
				int ah = gridHeight(gridRows);
				int ay = abilitiesBeside ? PADDING + Math.max(0, (upperH - ah) / 2) : lowerY;
				int ax = abilitiesBeside ? x + ABILITIES_GAP : passives.isEmpty() ? PADDING + (span - abilitiesW) / 2 : lowerX + passivesW + ABILITIES_GAP;
				Panel panel = new Panel(Section.ABILITIES, ax, ay, abilitiesW, ah);
				panels.put(Section.ABILITIES, panel);
				int gx = panel.x() + (panel.w() - gridW) / 2;
				for (int i = 0; i < abilities.size(); i++) {
					positions.put(abilities.get(i).id(), new int[] {gx + (i % columns) * GRID, ay + PANEL_TITLE + (i / columns) * GRID, NODE});
				}
				bottom = Math.max(bottom, ay + ah);
			}
			contentW = span + PADDING * 2;
			contentH = bottom + PADDING;
		}

		/** A grid of {@code columns} centred in its panel, under the panel's title, filled row by row. */
		private void placeGrid(Panel panel, List<UpgradeTree.Node> nodes, int columns) {
			int gridW = columns * GRID - (GRID - NODE);
			int gx = panel.x() + (panel.w() - gridW) / 2;
			for (int i = 0; i < nodes.size(); i++) {
				positions.put(nodes.get(i).id(), new int[] {gx + (i % columns) * GRID, panel.y() + PANEL_TITLE + (i / columns) * GRID, NODE});
			}
		}

		/** A section's upgrades in file-tree order: {node, depth}, each under the upgrade it grows from. */
		private static List<Object[]> fileTree(List<UpgradeTree.Node> nodes) {
			Map<String, UpgradeTree.Node> inSection = new HashMap<>();
			for (UpgradeTree.Node n : nodes) inSection.put(n.id(), n);
			Map<String, List<UpgradeTree.Node>> children = new HashMap<>();
			List<UpgradeTree.Node> roots = new ArrayList<>();
			for (UpgradeTree.Node n : nodes) {
				if (inSection.containsKey(n.parent())) children.computeIfAbsent(n.parent(), k -> new ArrayList<>()).add(n);
				else roots.add(n);
			}
			List<Object[]> rows = new ArrayList<>();
			for (UpgradeTree.Node root : roots) addRows(root, 0, children, rows);
			return rows;
		}

		private static void addRows(UpgradeTree.Node node, int depth, Map<String, List<UpgradeTree.Node>> children, List<Object[]> rows) {
			if (depth > 32) return;
			rows.add(new Object[] {node, depth});
			for (UpgradeTree.Node kid : children.getOrDefault(node.id(), List.of())) addRows(kid, depth + 1, children, rows);
		}

		private static int panelWidth(List<Object[]> rows) {
			int w = 0;
			for (Object[] row : rows) w = Math.max(w, (int) row[1] * INDENT + NODE);
			return Math.max(64, w + PANEL_PAD * 2);
		}

		private static int gridHeight(int rows) {
			return PANEL_TITLE + rows * GRID - (GRID - NODE) + PANEL_PAD;
		}

		private static int panelHeight(int rows) {
			return rows == 0 ? 0 : PANEL_TITLE + rows * ROW - (ROW - NODE) + PANEL_PAD;
		}

		private void placeRows(Panel panel, List<Object[]> rows) {
			for (int i = 0; i < rows.size(); i++) {
				UpgradeTree.Node n = (UpgradeTree.Node) rows.get(i)[0];
				positions.put(n.id(), new int[] {panel.x() + PANEL_PAD + (int) rows.get(i)[1] * INDENT, panel.y() + PANEL_TITLE + i * ROW, NODE});
			}
		}

		/**
		 * Advancement-style tidy tree, for server trees without the standard sections: depth goes left to right, every
		 * leaf gets its own row and a parent sits level with its first child.
		 */
		private void layoutTidy(List<UpgradeTree.Node> nodes) {
			Map<String, List<UpgradeTree.Node>> children = new HashMap<>();
			List<UpgradeTree.Node> roots = new ArrayList<>();
			Map<String, UpgradeTree.Node> byId = new HashMap<>();
			for (UpgradeTree.Node n : nodes) byId.put(n.id(), n);
			for (UpgradeTree.Node n : nodes) {
				if (n.parent().equals("root") || !byId.containsKey(n.parent())) roots.add(n);
				else children.computeIfAbsent(n.parent(), k -> new ArrayList<>()).add(n);
			}
			int[] nextRow = {0};
			int[] maxDepth = {0};
			for (UpgradeTree.Node root : roots) place(root, 0, children, nextRow, maxDepth);
			contentW = maxDepth[0] * COLUMN + NODE + PADDING * 2;
			contentH = Math.max(1, nextRow[0]) * ROW - (ROW - NODE) + PADDING * 2;
		}

		private int place(UpgradeTree.Node node, int depth, Map<String, List<UpgradeTree.Node>> children, int[] nextRow, int[] maxDepth) {
			maxDepth[0] = Math.max(maxDepth[0], depth);
			List<UpgradeTree.Node> kids = children.getOrDefault(node.id(), List.of());
			int row;
			if (kids.isEmpty()) {
				row = nextRow[0]++;
			} else {
				row = -1;
				for (UpgradeTree.Node kid : kids) {
					int r = place(kid, depth + 1, children, nextRow, maxDepth);
					if (row < 0) row = r;
				}
			}
			positions.put(node.id(), new int[] {PADDING + depth * COLUMN, PADDING + row * ROW, NODE});
			return row;
		}
	}
}
