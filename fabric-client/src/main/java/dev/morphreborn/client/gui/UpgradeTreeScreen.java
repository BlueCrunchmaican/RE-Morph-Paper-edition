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
 * Survival Mode's upgrade tree for players with the mod, laid out like the advancement screen: each upgrade is a framed
 * status-effect icon, linked to the upgrade it grows from; drag or scroll to move around, hover for details, click to buy. The window
 * sits on the morph's black static. Players without the mod get the server's chest menu instead.
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

	private UpgradeTree tree;
	private final Map<String, int[]> positions = new HashMap<>();
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
		if (!sameShape) {
			layout();
			init();
		}
	}

	/**
	 * Advancement-style tidy tree: depth goes left to right, every leaf gets its own row and a parent sits level with
	 * its first child.
	 */
	private void layout() {
		positions.clear();
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
		positions.put(node.id(), new int[] {PADDING + depth * COLUMN, PADDING + row * ROW});
		return row;
	}

	@Override
	protected void init() {
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
		if (!dragged && event.button() == InputConstants.MOUSE_BUTTON_LEFT) { // 26.3 numbers mouse buttons from 1
			UpgradeTree.Node node = nodeAt(event.x(), event.y());
			if (node != null) ClientNet.buyUpgrade(node.id());
		}
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
			if (mx >= x && mx < x + NODE && my >= y && my < y + NODE) return n;
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
		for (boolean outline : new boolean[] {true, false}) {
			for (UpgradeTree.Node n : tree.nodes()) drawLink(g, n, ox, oy, outline);
		}
		for (UpgradeTree.Node n : tree.nodes()) drawNode(g, n, ox, oy);
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
