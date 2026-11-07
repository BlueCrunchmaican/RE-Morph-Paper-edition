package dev.morphreborn.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.morphreborn.client.Keys;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.render.MorphWorldRenderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Survival Mode's upgrade tree for players with the mod, in the advancement screen's style: each upgrade is a framed
 * status-effect icon, linked to the upgrade it grows from; drag or scroll to move around, hover for details, click to
 * buy. The window sits on the morph's black static. Players without the mod get the server's chest menu instead.
 * <p>
 * The standard tree is shown in three sections: Biomass, then the Morph Ability as a lump of biomass on its own, the
 * Morph section it opens, and below them the Abilities that Morph Abilities opens. Each section lists its upgrades
 * like a file tree (an upgrade under the one it grows from). A server tree without the Morph Ability is drawn as one
 * plain tree instead.
 */
public final class UpgradeTreeScreen extends Screen {
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
	/** The Morph Ability orb's box. */
	private static final int ORB = 32;
	/** How far an upgrade sits right of the one it grows from, in a section's file tree. */
	private static final int INDENT = 22;
	private static final int PANEL_PAD = 8;
	private static final int PANEL_TITLE = 14;
	/** Gap between the upper sections and the orb. */
	private static final int GAP = 34;
	private static final int GRID = 32;
	private static final int ABILITY_COLUMNS = 7;

	private enum Section { BIOMASS, MORPH_ABILITY, MORPH, ABILITIES }

	private record Panel(Section section, int x, int y, int w, int h) {}

	private UpgradeTree tree;
	/** Node id -> {x, y, size} in content coordinates. */
	private final Map<String, int[]> positions = new HashMap<>();
	private final Map<Section, Panel> panels = new HashMap<>();
	private final Map<String, Section> sections = new HashMap<>();
	private boolean sectioned;
	/** The Abilities sit beside the Morph section instead of below, when the window is too short for below. */
	private boolean abilitiesBeside;
	/** The left button went down on this screen (a release left over from the click that opened it buys nothing). */
	private boolean pressed;
	private int contentW;
	private int contentH;
	private int insideW;
	private int insideH;
	private int left;
	private int top;
	private double scrollX;
	private double scrollY;
	private boolean dragged;
	private String message = "";
	private int messageTicks;
	private int ticks;

	public UpgradeTreeScreen(UpgradeTree tree) {
		super(Component.translatable("morphreborn.upgrades.title"));
		this.tree = tree;
		layout();
	}

	/** The server answered a purchase (or biomass changed): show the new state, keep the view where it is. */
	public void update(UpgradeTree newTree) {
		boolean sameShape = newTree.nodes().size() == tree.nodes().size();
		this.tree = newTree;
		if (!newTree.message().isEmpty()) {
			message = newTree.message();
			messageTicks = 60;
		}
		if (!sameShape) init();
	}

	private void layout() {
		positions.clear();
		panels.clear();
		sections.clear();
		sectioned = tree.nodes().stream().anyMatch(n -> n.id().equals(MORPH_ABILITY));
		if (sectioned) layoutSections();
		else layoutTidy();
	}

	/** Which section a node is in, by its ancestry (the same rule as the server's chest menu). */
	private static Section sectionOf(UpgradeTree.Node n, Map<String, UpgradeTree.Node> byId) {
		if (n.id().equals(MORPH_ABILITY)) return Section.MORPH_ABILITY;
		boolean underMorph = false;
		String parent = n.parent();
		for (int guard = 0; guard < 64 && !parent.equals("root"); guard++) {
			if (parent.equals(MORPH_ABILITIES)) return Section.ABILITIES;
			if (parent.equals(MORPH_ABILITY)) underMorph = true;
			UpgradeTree.Node p = byId.get(parent);
			if (p == null) break;
			parent = p.parent();
		}
		return underMorph ? Section.MORPH : Section.BIOMASS;
	}

	/**
	 * Biomass and Morph side by side with the Morph Ability orb between them, Abilities as a grid underneath. Inside
	 * Biomass and Morph every upgrade gets a row, indented under the one it grows from.
	 */
	private void layoutSections() {
		Map<String, UpgradeTree.Node> byId = new HashMap<>();
		for (UpgradeTree.Node n : tree.nodes()) byId.put(n.id(), n);
		Map<Section, List<UpgradeTree.Node>> bySection = new HashMap<>();
		for (UpgradeTree.Node n : tree.nodes()) {
			Section section = sectionOf(n, byId);
			sections.put(n.id(), section);
			bySection.computeIfAbsent(section, k -> new ArrayList<>()).add(n);
		}
		List<Object[]> biomassRows = fileTree(bySection.getOrDefault(Section.BIOMASS, List.of()));
		List<Object[]> morphRows = fileTree(bySection.getOrDefault(Section.MORPH, List.of()));
		List<UpgradeTree.Node> abilities = bySection.getOrDefault(Section.ABILITIES, List.of());

		int biomassW = panelWidth(biomassRows);
		int morphW = panelWidth(morphRows);
		int upperH = Math.max(Math.max(panelHeight(biomassRows.size()), panelHeight(morphRows.size())), ORB + 8);
		int upperSpan = biomassW + GAP + ORB + GAP + morphW;
		int n = abilities.size();
		// Below the other sections (as approved), unless the window is too short for that and wide enough to put
		// them beside the Morph section, in as few rows as the sections next to them
		int belowColumns = Math.clamp(n, 1, ABILITY_COLUMNS);
		int belowH = gridHeight((n + belowColumns - 1) / belowColumns);
		int belowW = Math.max(belowColumns * GRID - (GRID - NODE) + PANEL_PAD * 2, upperSpan);
		int availW = width > 0 ? Math.max(MIN_INSIDE_W, width - 40 - BORDER * 2) : 0;
		int availH = height > 0 ? Math.max(MIN_INSIDE_H, height - 50 - BORDER - TITLE) : Integer.MAX_VALUE;
		// beside: as many rows as the window has room for (at least as many as fit next to the other sections), so
		// the grid is as narrow as it can be
		int rowsBesideUpper = (upperH - PANEL_TITLE - PANEL_PAD + (GRID - NODE)) / GRID;
		int rowsInWindow = availH == Integer.MAX_VALUE ? n : (availH - PADDING * 2 - PANEL_TITLE - PANEL_PAD + (GRID - NODE)) / GRID;
		int sideRows = Math.clamp(Math.max(rowsBesideUpper, rowsInWindow), 1, Math.max(1, n));
		int sideColumns = Math.max(1, (n + sideRows - 1) / sideRows);
		int sideW = sideColumns * GRID - (GRID - NODE) + PANEL_PAD * 2;
		int sideH = gridHeight((n + sideColumns - 1) / sideColumns);
		abilitiesBeside = n > 0 && PADDING * 2 + upperH + 22 + belowH > availH && PADDING * 2 + upperSpan + 22 + sideW <= availW;
		int columns = abilitiesBeside ? sideColumns : belowColumns;
		int gridRows = (n + columns - 1) / columns;
		int gridW = columns * GRID - (GRID - NODE);
		int abilitiesW = abilitiesBeside ? sideW : belowW;
		int span = n == 0 ? upperSpan : abilitiesBeside ? upperSpan + 22 + sideW : Math.max(upperSpan, belowW);

		int x = PADDING + (abilitiesBeside ? 0 : (span - upperSpan) / 2);
		// side by side, the shorter of the two is centred on the taller
		int y = PADDING + (abilitiesBeside ? Math.max(0, (sideH - upperH) / 2) : 0);
		if (!biomassRows.isEmpty()) {
			Panel panel = new Panel(Section.BIOMASS, x, y + (upperH - panelHeight(biomassRows.size())) / 2, biomassW, panelHeight(biomassRows.size()));
			panels.put(Section.BIOMASS, panel);
			placeRows(panel, biomassRows);
		}
		x += biomassW + GAP;
		positions.put(MORPH_ABILITY, new int[] {x, y + (upperH - ORB) / 2, ORB});
		x += ORB + GAP;
		if (!morphRows.isEmpty()) {
			Panel panel = new Panel(Section.MORPH, x, y + (upperH - panelHeight(morphRows.size())) / 2, morphW, panelHeight(morphRows.size()));
			panels.put(Section.MORPH, panel);
			placeRows(panel, morphRows);
		}
		int bottom = y + upperH;
		if (!abilities.isEmpty()) {
			int ah = gridHeight(gridRows);
			int ay = abilitiesBeside ? PADDING + Math.max(0, (upperH - ah) / 2) : bottom + 22;
			int ax = abilitiesBeside ? x + morphW + 22 : PADDING + (span - abilitiesW) / 2;
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
	private void layoutTidy() {
		Map<String, List<UpgradeTree.Node>> children = new HashMap<>();
		List<UpgradeTree.Node> roots = new ArrayList<>();
		Map<String, UpgradeTree.Node> byId = new HashMap<>();
		for (UpgradeTree.Node n : tree.nodes()) byId.put(n.id(), n);
		for (UpgradeTree.Node n : tree.nodes()) {
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

	@Override
	protected void init() {
		layout();
		insideW = Mth.clamp(contentW, MIN_INSIDE_W, Math.max(MIN_INSIDE_W, width - 40 - BORDER * 2));
		insideH = Mth.clamp(contentH, MIN_INSIDE_H, Math.max(MIN_INSIDE_H, height - 50 - BORDER - TITLE));
		left = (width - insideW - BORDER * 2) / 2;
		top = (height - insideH - TITLE - BORDER) / 2;
		// centred when it fits, otherwise starting at the top left (the root)
		scrollX = contentW <= insideW ? (insideW - contentW) / 2.0 : 0;
		scrollY = contentH <= insideH ? (insideH - contentH) / 2.0 : 0;
	}

	@Override
	public void tick() {
		ticks++;
		if (messageTicks > 0) messageTicks--;
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
			UpgradeTree.Node node = nodeAt(event.x(), event.y());
			if (node != null) ClientNet.buyUpgrade(node.id());
		}
		pressed = false;
		dragged = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollXAmount, double scrollYAmount) {
		scroll(scrollXAmount * 16, scrollYAmount * 16);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (Keys.BIOMASS.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private void scroll(double dx, double dy) {
		if (contentW > insideW) scrollX = Mth.clamp(scrollX + dx, insideW - contentW, 0);
		if (contentH > insideH) scrollY = Mth.clamp(scrollY + dy, insideH - contentH, 0);
	}

	private int insideLeft() {
		return left + BORDER;
	}

	private int insideTop() {
		return top + TITLE;
	}

	private UpgradeTree.@Nullable Node nodeAt(double mx, double my) {
		if (mx < insideLeft() || my < insideTop() || mx >= insideLeft() + insideW || my >= insideTop() + insideH) return null;
		for (UpgradeTree.Node n : tree.nodes()) {
			int[] p = positions.get(n.id());
			if (p == null) continue;
			double x = insideLeft() + scrollX + p[0];
			double y = insideTop() + scrollY + p[1];
			if (p[2] == ORB && sectioned && n.id().equals(MORPH_ABILITY)) {
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
		if (messageTicks > 0 && !message.isEmpty()) {
			g.centeredText(font, Component.literal(message), width / 2, insideTop() + insideH + BORDER + 4, 0xFFFFFFFF);
		}
		UpgradeTree.Node hovered = nodeAt(mouseX, mouseY);
		if (hovered != null) g.setTooltipForNextFrame(font, tooltip(hovered), Optional.empty(), mouseX, mouseY);
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

	private void drawHeader(GuiGraphicsExtractor g) {
		g.text(font, title, left + 8, top + 6, 0xFF404040, false);
		Component amount = tree.unlocked() ? Component.translatable("morphreborn.hud.biomass", tree.biomass(), tree.capacity())
				: Component.translatable("morphreborn.upgrades.locked_short");
		g.text(font, amount, insideLeft() + insideW - font.width(amount), top + 6, tree.unlocked() ? 0xFF9E1B1B : 0xFF5D4037, false);
	}

	private void drawInside(GuiGraphicsExtractor g) {
		int ix = insideLeft();
		int iy = insideTop();
		g.enableScissor(ix, iy, ix + insideW, iy + insideH);
		drawStatic(g, ix, iy);
		int ox = ix + (int) Math.floor(scrollX);
		int oy = iy + (int) Math.floor(scrollY);
		if (sectioned) for (Panel panel : panels.values()) drawPanel(g, panel, ox, oy);
		for (boolean outline : new boolean[] {true, false}) {
			for (UpgradeTree.Node n : tree.nodes()) {
				if (sectioned) drawSectionLink(g, n, ox, oy, outline);
				else drawLink(g, n, ox, oy, outline);
			}
		}
		for (UpgradeTree.Node n : tree.nodes()) {
			if (sectioned && n.id().equals(MORPH_ABILITY)) drawOrb(g, n, ox, oy);
			else drawNode(g, n, ox, oy);
		}
		if (!tree.unlocked()) {
			g.fill(ix, iy + insideH / 2 - 14, ix + insideW, iy + insideH / 2 + 14, 0xC0000000);
			g.centeredText(font, Component.translatable("morphreborn.upgrades.locked"), ix + insideW / 2, iy + insideH / 2 - 10, 0xFFE57373);
			g.centeredText(font, Component.translatable("morphreborn.upgrades.locked_how"), ix + insideW / 2, iy + insideH / 2 + 2, 0xFFBDBDBD);
		}
		g.disableScissor();
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
		int[] me = positions.get(n.id());
		int[] parent = positions.get(n.parent());
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
			default -> 0xFFB26A00;
		};
		int text = switch (panel.section()) {
			case BIOMASS -> 0xFFEF9A9A;
			case MORPH -> 0xFFCE93D8;
			default -> 0xFFFFCC80;
		};
		g.fill(x0, y0, x1, y1, 0x90000000);
		g.fill(x0, y0, x1, y0 + 1, edge);
		g.fill(x0, y1 - 1, x1, y1, edge);
		g.fill(x0, y0, x0 + 1, y1, edge);
		g.fill(x1 - 1, y0, x1, y1, edge);
		Component name = Component.translatable("morphreborn.upgrades.section." + panel.section().name().toLowerCase(java.util.Locale.ROOT));
		if (panel.section() == Section.ABILITIES && levelOf(MORPH_ABILITIES) <= 0) {
			Component locked = Component.translatable("morphreborn.upgrades.section.abilities_locked", name);
			name = font.width(locked) <= panel.w() - 10 ? locked : Component.translatable("morphreborn.upgrades.section.abilities_locked_short", name);
		}
		g.text(font, name, x0 + 5, y0 + 4, text, false);
	}

	/**
	 * Links in the sectioned tree. Inside a section: down from the upgrade it grows from, then across (a file tree).
	 * The Morph section's first upgrades hang off the orb; the orb off Biomass's first upgrade (it needs Biomass
	 * Capacity); the Abilities section off Morph Abilities.
	 */
	private void drawSectionLink(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy, boolean outline) {
		int[] me = positions.get(n.id());
		if (me == null) return;
		int col = outline ? 0xFF000000 : 0xFF808080;
		int w = outline ? 1 : 0;
		Section section = sections.get(n.id());
		int[] parent = positions.get(n.parent());
		if (section == Section.MORPH_ABILITY) {
			Panel biomass = panels.get(Section.BIOMASS);
			UpgradeTree.Node first = firstIn(Section.BIOMASS);
			if (biomass == null || first == null) return;
			int[] from = positions.get(first.id());
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
		if (section == Section.ABILITIES) {
			if (!n.parent().equals(MORPH_ABILITIES) || parent == null || firstIn(Section.ABILITIES) != n) return;
			Panel abilities = panels.get(Section.ABILITIES);
			int c = outline ? col : levelOf(MORPH_ABILITIES) > 0 ? 0xFFFFFFFF : col;
			if (abilitiesBeside) {
				int px = ox + parent[0] + NODE;
				int py = oy + parent[1] + NODE / 2;
				int ax = ox + abilities.x();
				int ay = Mth.clamp(py, oy + abilities.y() + 8, oy + abilities.y() + abilities.h() - 8);
				int split = ax - 11;
				hLine(g, px, split, py, w, c);
				vLine(g, split, py, ay, w, c);
				hLine(g, split, ax, ay, w, c);
				return;
			}
			int px = ox + parent[0] + NODE / 2;
			vLine(g, px, oy + parent[1] + NODE, oy + abilities.y(), w, c);
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
		for (UpgradeTree.Node n : tree.nodes()) if (sections.get(n.id()) == section) return n;
		return null;
	}

	private static void hLine(GuiGraphicsExtractor g, int x0, int x1, int y, int w, int color) {
		g.fill(Math.min(x0, x1) - w, y - w, Math.max(x0, x1) + w + 1, y + w + 1, color);
	}

	private static void vLine(GuiGraphicsExtractor g, int x, int y0, int y1, int w, int color) {
		g.fill(x - w, Math.min(y0, y1) - w, x + w + 1, Math.max(y0, y1) + w + 1, color);
	}

	/** The Morph Ability: a lump of biomass with tendrils, its icon in the middle; gold-ringed once bought. */
	private void drawOrb(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy) {
		int[] p = positions.get(n.id());
		if (p == null) return;
		float cx = ox + p[0] + ORB / 2F;
		float cy = oy + p[1] + ORB / 2F;
		float time = ticks + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		boolean buyable = n.buyable(tree.unlocked());
		float glow = buyable ? 0.6F + 0.4F * (float) Math.sin(time * 0.2) : 0;
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

	private void drawNode(GuiGraphicsExtractor g, UpgradeTree.Node n, int ox, int oy) {
		int[] p = positions.get(n.id());
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
		if (n.icon().startsWith("effect:")) {
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
	}

	private static ItemStack icon(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return new ItemStack(Items.PAPER);
		return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.PAPER));
	}

	private List<Component> tooltip(UpgradeTree.Node n) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(n.name()).withStyle(n.maxed() ? ChatFormatting.GOLD : n.level() > 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE));
		for (String part : wrap(n.description(), 38)) lines.add(Component.literal(part).withStyle(ChatFormatting.GRAY));
		lines.add(Component.empty());
		if (n.maxLevel() == 1) lines.add(Component.translatable(n.level() > 0 ? "morphreborn.upgrades.bought" : "morphreborn.upgrades.not_bought"));
		else lines.add(Component.translatable("morphreborn.upgrades.level", n.level(), n.maxLevel()));
		if (!n.now().isEmpty()) lines.add(Component.translatable("morphreborn.upgrades.now", n.now()).withStyle(ChatFormatting.AQUA));
		if (!n.next().isEmpty()) lines.add(Component.translatable("morphreborn.upgrades.next", n.next()).withStyle(ChatFormatting.AQUA));
		if (!n.maxed()) {
			MutableComponent cost = Component.translatable("morphreborn.upgrades.cost", n.cost());
			lines.add(cost.withStyle(n.affordable() ? ChatFormatting.GREEN : ChatFormatting.RED));
		}
		if (!n.missing().isEmpty() && !n.maxed()) lines.add(Component.translatable("morphreborn.upgrades.needs", n.missing()).withStyle(ChatFormatting.RED));
		lines.add(Component.empty());
		if (n.maxed()) lines.add(Component.translatable(n.maxLevel() == 1 ? "morphreborn.upgrades.unlocked" : "morphreborn.upgrades.maxed").withStyle(ChatFormatting.GOLD));
		else if (!tree.unlocked()) lines.add(Component.translatable("morphreborn.upgrades.locked_short").withStyle(ChatFormatting.DARK_GRAY));
		else if (n.missing().isEmpty()) lines.add(Component.translatable("morphreborn.upgrades.click").withStyle(ChatFormatting.YELLOW));
		return lines;
	}

	private static List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
				out.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty()) out.add(line.toString());
		return out;
	}
}
