package dev.morphreborn.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.Keys;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.data.Collection;
import dev.morphreborn.client.data.MorphState;
import dev.morphreborn.client.net.ClientNet;
import dev.morphreborn.client.net.MorphRef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * The original Morph selector and favourites radial, ported from HudHandler: same layout, textures, timings
 * (8-tick slide, 4-tick sine-eased scrolling, 3-tick radial), keys, mouse controls and colours.
 */
public final class SelectorHud {
	public static final SelectorHud INSTANCE = new SelectorHud();

	private static final Identifier TEX_FAVOURITE = tex("textures/gui/fav.png");
	private static final Identifier TEX_SELECTED = tex("textures/gui/gui_selected.png");
	private static final Identifier TEX_UNSELECTED = tex("textures/gui/gui_unselected.png");
	private static final Identifier TEX_UNSELECTED_SIDE = tex("textures/gui/gui_unselected_side.png");
	private static final int TEX = 64;

	private static final int SHOW_SELECTOR_TIME = 8;
	private static final int INDEX_TIME = 4;
	private static final int RADIAL_TIME = 3;
	/** Original preview pose: InventoryScreen.drawEntityOnScreen(0, 0, 35, -60, 0, entity). */
	private static final float PREVIEW_SCALE = 35F;
	private static final float PREVIEW_YAW = (float) Math.atan(-60F / 40F);

	private final Minecraft mc = Minecraft.getInstance();

	public boolean showSelector;
	public int showTime;
	public int indexChangeTime;
	public double lastIndexVert;
	public double lastIndexHori;
	public int indexVert;
	public int indexHori;

	public boolean showRadial;
	public int radialTime;
	private @Nullable List<Collection.Variant> radialFavourites;

	private boolean keyEnterDown, keyEscDown, keyDeleteDown, keyUpDown, keyDownDown, keyLeftDown, keyRightDown;
	private boolean favouriteWasDown;
	private int seenRevision = -1;

	private static Identifier tex(String path) {
		return Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, path);
	}

	private Collection data() {
		return MorphClient.COLLECTION;
	}

	/** Survival Mode: say why the selector doesn't open, and where to go. */
	private static void cantMorphYet() {
		ClientNet.message(Component.translatable(ClientNet.biomassUnlocked() ? "morphreborn.message.buy_morph_ability" : "morphreborn.message.unlock_biomass",
				Keys.BIOMASS.getTranslatedKeyMessage()).withStyle(ChatFormatting.GRAY));
	}

	private boolean canUseSelector() {
		return ClientNet.isActive() && ClientNet.selectorAllowed() && mc.player != null && !data().rows().isEmpty();
	}

	// ------------------------------------------------------------------ input

	/** Called every client tick (END). */
	public void tick() {
		if (mc.player == null) {
			showSelector = showRadial = false;
			return;
		}
		if (seenRevision != data().revision) {
			seenRevision = data().revision;
			if (shouldRenderSelector() && !data().rows().isEmpty()) validateIndices();
		}
		handleKeys();

		if (showSelector) showTime = Math.min(SHOW_SELECTOR_TIME, showTime + 1);
		else showTime = Math.max(0, showTime - 1);
		indexChangeTime++;
		if (indexChangeTime > INDEX_TIME) {
			indexChangeTime = INDEX_TIME;
			lastIndexVert = indexVert;
			lastIndexHori = indexHori;
		}
		if (showRadial) radialTime = Math.min(RADIAL_TIME, radialTime + 1);

		updateKeyListeners();
	}

	private void handleKeys() {
		boolean inGame = mc.gui.screen() == null;
		while (Keys.SELECTOR_UP.consumeClick()) if (inGame) onSelectorKey(false, Keys.shiftDown());
		while (Keys.SELECTOR_DOWN.consumeClick()) if (inGame) onSelectorKey(true, Keys.shiftDown());
		while (Keys.SELECTOR_LEFT.consumeClick()) if (inGame) onSelectorKey(false, true);
		while (Keys.SELECTOR_RIGHT.consumeClick()) if (inGame) onSelectorKey(true, true);
		Keys.tickHolds(this::onSelectorKey, inGame);

		boolean favDown = Keys.FAVOURITE.isDown() && inGame;
		while (Keys.FAVOURITE.consumeClick()) {
			// handled through the down/up edges below; drain so it doesn't pile up
		}
		if (favDown && !favouriteWasDown) onFavouriteKey(false);
		if (!favDown && favouriteWasDown) onFavouriteKey(true);
		favouriteWasDown = favDown;

		while (Keys.DEMORPH.consumeClick()) if (inGame && ClientNet.isActive()) ClientNet.select(MorphRef.SELF_ID);
		while (Keys.ABILITY.consumeClick()) if (inGame && ClientNet.isActive()) ClientNet.ability();
	}

	private void onSelectorKey(boolean down, boolean horizontal) {
		if (!canUseSelector()) {
			if (!ClientNet.isActive()) ClientNet.warnInactive();
			else if (ClientNet.isSurvival() && !ClientNet.survivalCanMorph()) cantMorphYet();
			return;
		}
		if (showSelector) {
			shiftIndexSelector(down, horizontal);
		} else {
			showSelector = true;
			setIndicesToCurrentMorph();
			keyEscDown = false;
			keyEnterDown = false;
		}
	}

	/** Survival Mode: the wheel also carries the Biomass button, so it opens even before the player can morph. */
	private boolean survivalWheel() {
		return ClientNet.isActive() && ClientNet.isSurvival() && mc.player != null;
	}

	private void onFavouriteKey(boolean released) {
		if (!canUseSelector() && !survivalWheel()) return;
		if (showSelector) {
			if (released) toggleFavourite();
		} else if (!released) {
			if (!showRadial) {
				radialFavourites = canUseSelector() ? data().favourites() : List.of();
				showRadial = true;
				radialTime = 0;
				mc.mouseHandler.releaseMouse();
			}
		} else if (showRadial) {
			confirmRadial();
		}
	}

	/** Enter/Esc/Delete/arrows are polled like the original (InputMappings.isKeyDown), not bound as KeyMappings. */
	private void updateKeyListeners() {
		boolean enter = InputConstants.isKeyDown(InputConstants.KEY_RETURN) || InputConstants.isKeyDown(InputConstants.KEY_NUMPADENTER);
		boolean esc = InputConstants.isKeyDown(InputConstants.KEY_ESCAPE);
		boolean delete = InputConstants.isKeyDown(InputConstants.KEY_DELETE) || InputConstants.isKeyDown(InputConstants.KEY_BACKSPACE);
		boolean up = InputConstants.isKeyDown(InputConstants.KEY_UP);
		boolean down = InputConstants.isKeyDown(InputConstants.KEY_DOWN);
		boolean left = InputConstants.isKeyDown(InputConstants.KEY_LEFT);
		boolean right = InputConstants.isKeyDown(InputConstants.KEY_RIGHT);

		if (showSelector || showRadial) {
			if (!keyEnterDown && enter && mc.gui.screen() == null) {
				if (showSelector) confirmSelector();
				else confirmRadial();
			}
			if (mc.gui.screen() != null || (!keyEscDown && esc)) {
				if (mc.gui.screen() instanceof PauseScreen) mc.gui.setScreen(null);
				if (showSelector) closeSelector();
				else closeRadial();
			}
			if (showSelector) {
				if (!keyDeleteDown && delete) deleteSelector();
				if (!keyUpDown && up) shiftIndexSelector(false, false);
				if (!keyDownDown && down) shiftIndexSelector(true, false);
				if (!keyLeftDown && left) shiftIndexSelector(false, true);
				if (!keyRightDown && right) shiftIndexSelector(true, true);
			}
		}
		keyEnterDown = enter;
		keyEscDown = esc;
		keyDeleteDown = delete;
		keyUpDown = up;
		keyDownDown = down;
		keyLeftDown = left;
		keyRightDown = right;
	}

	/** Mouse buttons while the selector/radial is open. Returns true to swallow the click. */
	public boolean onMouseButton(int button, boolean pressed) {
		if (!ClientConfig.INSTANCE.selectorAllowMouseControl || mc.gui.screen() != null || !pressed) return false;
		// A side button bound to a selector key (players often put [ and ] on mouse buttons 4 and 5) must reach its key
		// binding, or the selector opens and then never moves.
		if ((showSelector || showRadial) && boundToSelectorKey(button)) return false;
		if (showSelector) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) confirmSelector();
			else if (button == InputConstants.MOUSE_BUTTON_RIGHT) closeSelector();
			else if (button == InputConstants.MOUSE_BUTTON_MIDDLE) toggleFavourite();
			return true;
		}
		if (showRadial) {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) confirmRadial();
			else closeRadial();
			return true;
		}
		return false;
	}

	private static boolean boundToSelectorKey(int button) {
		InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
		for (net.minecraft.client.KeyMapping mapping : new net.minecraft.client.KeyMapping[] {Keys.SELECTOR_UP, Keys.SELECTOR_DOWN, Keys.SELECTOR_LEFT,
				Keys.SELECTOR_RIGHT, Keys.FAVOURITE}) {
			if (mapping.matches(key)) return true;
		}
		return false;
	}

	public boolean onMouseScroll(double delta) {
		if (!ClientConfig.INSTANCE.selectorAllowMouseControl || !showSelector || mc.gui.screen() != null || delta == 0) return false;
		boolean down = ClientConfig.INSTANCE.invertScroll ? delta > 0 : delta < 0;
		shiftIndexSelector(down, Keys.shiftDown());
		return true;
	}

	/** If the pause menu opens while the selector is up (Esc), close both, like the original's render tick hook. */
	public void onRenderTick() {
		if ((showSelector || showRadial) && mc.gui.screen() instanceof PauseScreen) {
			mc.gui.setScreen(null);
			if (showSelector) closeSelector();
			else closeRadial();
		}
	}

	// ------------------------------------------------------------------ selection logic (verbatim port)

	private @Nullable MorphRef currentMorph() {
		if (mc.player == null) return null;
		MorphState st = MorphClient.STATES.get(mc.player.getUUID());
		return st == null || st.next == null ? null : st.next;
	}

	private boolean isCurrent(MorphRef ref) {
		MorphRef current = currentMorph();
		return current == null ? ref.isSelf() : current.variantId().equals(ref.variantId());
	}

	public void setIndicesToCurrentMorph() {
		indexVert = indexHori = 0;
		MorphRef current = currentMorph();
		List<Collection.Row> rows = data().rows();
		if (current != null && !current.isSelf()) {
			for (int i = 0; i < rows.size(); i++) {
				List<Collection.Variant> variants = rows.get(i).variants;
				for (int j = 0; j < variants.size(); j++) {
					if (variants.get(j).ref.variantId().equals(current.variantId())) {
						indexVert = i;
						indexHori = j;
						lastIndexHori = variants.size() - 1;
					}
				}
			}
		}
		lastIndexVert = indexVert;
		indexChangeTime = 0;
	}

	private void confirmSelector() {
		Collection.Variant variant = selectedVariant();
		if (variant != null && !isCurrent(variant.ref)) ClientNet.select(variant.ref.variantId());
		closeSelector();
	}

	private void deleteSelector() {
		Collection.Variant variant = selectedVariant();
		if (variant != null && ClientNet.deleteAllowed() && !isCurrent(variant.ref) && !variant.ref.isSelf()) ClientNet.delete(variant.ref.variantId());
	}

	private void closeSelector() {
		showSelector = false;
		List<Collection.Row> rows = data().rows();
		if (!rows.isEmpty() && indexVert < rows.size()) indexHori = rows.get(indexVert).variants.size() - 1; // slide the row back in
		indexChangeTime = 0;
	}

	private void confirmRadial() {
		if (survivalWheel() && isMouseOnBiomassButton()) {
			closeRadial();
			ClientNet.openUpgrades();
			return;
		}
		if (isMouseOutsideRadialDeadZone() && radialFavourites != null && !radialFavourites.isEmpty()) {
			Collection.Variant variant = radialFavourites.get(selectedRadialIndex(radialFavourites.size()));
			if (!isCurrent(variant.ref)) ClientNet.select(variant.ref.variantId());
		}
		closeRadial();
	}

	private void closeRadial() {
		showRadial = false;
		radialFavourites = null;
		if (mc.gui.screen() == null) mc.mouseHandler.grabMouse();
	}

	private void toggleFavourite() {
		Collection.Variant variant = selectedVariant();
		if (variant != null && !variant.ref.isSelf()) {
			variant.favourite = !variant.favourite;
			ClientNet.favourite(variant.ref.variantId(), variant.favourite);
		}
	}

	private Collection.@Nullable Variant selectedVariant() {
		List<Collection.Row> rows = data().rows();
		if (indexVert < 0 || indexVert >= rows.size()) return null;
		List<Collection.Variant> variants = rows.get(indexVert).variants;
		return indexHori >= 0 && indexHori < variants.size() ? variants.get(indexHori) : null;
	}

	private void shiftIndexSelector(boolean isDown, boolean isHori) {
		List<Collection.Row> rows = data().rows();
		if (rows.isEmpty()) return;
		float eased = MorphState.sineify(Mth.clamp((float) indexChangeTime / INDEX_TIME, 0F, 1F));
		if (isHori) {
			lastIndexHori = lastIndexHori + (indexHori - lastIndexHori) * eased;
			int count = rows.get(indexVert).variants.size();
			indexHori = isDown ? (indexHori + 1 >= count ? 0 : indexHori + 1) : (indexHori - 1 < 0 ? count - 1 : indexHori - 1);
		} else {
			lastIndexVert = lastIndexVert + (indexVert - lastIndexVert) * eased;
			indexVert = isDown ? (indexVert + 1 >= rows.size() ? 0 : indexVert + 1) : (indexVert - 1 < 0 ? rows.size() - 1 : indexVert - 1);
			if (rows.size() > 1) {
				lastIndexHori = rows.get(indexVert).variants.size() - 1;
				indexHori = 0;
			}
		}
		indexChangeTime = 0;
	}

	private void validateIndices() {
		List<Collection.Row> rows = data().rows();
		if (indexVert >= rows.size()) {
			indexVert = 0;
			lastIndexHori = rows.get(indexVert).variants.size() - 1;
			indexHori = 0;
		} else if (indexVert < 0) {
			indexVert = rows.size() - 1;
			lastIndexHori = rows.get(indexVert).variants.size() - 1;
			indexHori = 0;
		}
		int count = rows.get(indexVert).variants.size();
		if (indexHori >= count) indexHori = 0;
		else if (indexHori < 0) indexHori = count - 1;
	}

	private boolean shouldRenderSelector() {
		return showSelector || showTime > 0;
	}


	// ------------------------------------------------------------------ drawing

	public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (mc.player == null || (data().rows().isEmpty() && !showRadial)) return;
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		if (shouldRenderSelector()) drawSelector(graphics, partialTick);
		if (showRadial && radialFavourites != null) drawRadial(graphics, partialTick);
	}

	private void drawSelector(GuiGraphicsExtractor g, float partialTick) {
		List<Collection.Row> rows = data().rows();
		if (indexVert >= rows.size()) validateIndices();
		Font font = mc.font;
		int screenW = g.guiWidth();
		int screenH = g.guiHeight();
		double size = 50 * ClientConfig.INSTANCE.selectorScale;
		float outProg = MorphState.sineify(Mth.clamp(showSelector ? (showTime + partialTick) / SHOW_SELECTOR_TIME : (showTime - partialTick) / SHOW_SELECTOR_TIME, 0F, 1F));
		int top = ClientConfig.INSTANCE.selectorDistanceFromTop;
		double posX = -size * (1F - outProg);
		float indexProg = MorphState.sineify(Mth.clamp((indexChangeTime + partialTick) / INDEX_TIME, 0F, 1F));

		// vertical stack
		double indexVertProg = lastIndexVert + (indexVert - lastIndexVert) * indexProg;
		double stackTop = top - indexVertProg * size;
		for (int i = 0; i < rows.size(); i++) {
			double y = stackTop + i * size;
			if (y > screenH || y + size < 0) continue;
			tile(g, TEX_UNSELECTED, posX, y, size, -1);
		}
		// horizontal stack of the selected row
		int variantCount = rows.get(indexVert).variants.size();
		double indexHoriProg = lastIndexHori + (indexHori - lastIndexHori) * indexProg;
		double rowStart = posX - indexHoriProg * size;
		for (int j = 0; j < variantCount - 1; j++) tile(g, TEX_UNSELECTED_SIDE, rowStart + j * size, top, size, -1);
		tile(g, TEX_UNSELECTED, rowStart + (variantCount - 1) * size, top, size, -1);
		tile(g, TEX_SELECTED, posX, top, size, -1);

		// entities (one off-screen pass), then stars and names above them
		List<MorphGuiEntities.Entry> entities = new ArrayList<>();
		List<Runnable> overlays = new ArrayList<>();
		int first = Math.max(0, indexVert - ((int) Math.ceil(top / size) + 1));
		int last = Math.min(rows.size(), indexVert + ((int) Math.ceil((screenH - top) / size) + 1));
		float pixelScale = (float) (size / 50D);
		for (int i = first; i < last; i++) {
			Collection.Row row = rows.get(i);
			double rowOffset = (i - indexVertProg) * size;
			double morphY = top + size * 0.775D + rowOffset;
			double textY = top + (size - font.lineHeight) / 2D + rowOffset;
			double favY = top + size * 0.13D + rowOffset;
			if (i == indexVert) {
				for (int j = Math.max(0, indexHori - 1); j < row.variants.size(); j++) {
					Collection.Variant variant = row.variants.get(j);
					double colOffset = (j - indexHoriProg) * size;
					double boxX = posX + colOffset;
					LivingEntity living = MorphClient.ENTITIES.forGui(variant.ref);
					if (living != null && boxX < screenW + size) {
						float entSize = Math.max(living.getBbWidth(), living.getBbHeight()) / 1.95F;
						if (j == indexHori) {
							if (showSelector) entSize *= (1F - indexProg);
							else if ((j == Math.round(lastIndexHori) && indexProg < 1F) || isCurrent(variant.ref)) entSize = 0F;
						}
						float entScale = 0.5F * (1F / Math.max(1F, entSize));
						addEntity(entities, living, (float) (posX + size / 2D - 2 + colOffset), (float) morphY, PREVIEW_SCALE * entScale * pixelScale, partialTick);
						if ((j == 0 && row.hasFavourite()) || variant.favourite) {
							boolean real = variant.favourite;
							double fx = posX + 1 + colOffset;
							overlays.add(() -> tile(g, TEX_FAVOURITE, fx, favY, size * 0.15D, real ? -1 : 0xFF00FFFF, 16));
						}
					}
					if (j == row.variants.size() - 1) {
						Collection.Variant selected = row.variants.get(Math.min(indexHori, row.variants.size() - 1));
						MutableComponent text = typeName(row.entityType).withStyle(isCurrent(selected.ref) ? ChatFormatting.GOLD : ChatFormatting.YELLOW);
						MutableComponent customName = showTime >= SHOW_SELECTOR_TIME ? customName(selected.ref) : null;
						double textX = Math.min(posX + size + 5 + colOffset, screenW - font.width(text) - 2);
						double nameY = top + size - font.lineHeight - 5 + rowOffset;
						overlays.add(() -> {
							text(g, text, textX, textY);
							if (customName != null) text(g, customName, 3, nameY);
						});
					}
				}
			} else {
				Collection.Variant variant = row.variants.getFirst();
				LivingEntity living = MorphClient.ENTITIES.forGui(variant.ref);
				if (living != null) {
					float entSize = Math.max(living.getBbWidth(), living.getBbHeight()) / 1.95F;
					if (i == Math.round(lastIndexVert)) entSize *= indexProg;
					float entScale = 0.5F * (1F / Math.max(1F, entSize));
					addEntity(entities, living, (float) (posX + size / 2D - 2), (float) morphY, PREVIEW_SCALE * entScale * pixelScale, partialTick);
				}
				boolean containsCurrent = false;
				for (Collection.Variant v : row.variants) containsCurrent |= isCurrent(v.ref);
				MutableComponent text = typeName(row.entityType).withStyle(containsCurrent ? ChatFormatting.GOLD : ChatFormatting.WHITE);
				boolean hasFav = row.hasFavourite();
				boolean realFav = variant.favourite;
				overlays.add(() -> {
					if (hasFav) tile(g, TEX_FAVOURITE, posX + 1, favY, size * 0.15D, realFav ? -1 : 0xFF00FFFF, 16);
					text(g, text, posX + size + 5, textY);
				});
			}
		}
		if (!entities.isEmpty()) g.guiRenderState.addPicturesInPictureState(new MorphGuiEntities.State(entities, screenW, screenH));
		g.nextStratum();
		overlays.forEach(Runnable::run);
	}

	private void drawRadial(GuiGraphicsExtractor g, float partialTick) {
		List<Collection.Variant> favourites = radialFavourites;
		boolean biomassButton = survivalWheel();
		if (favourites == null || (favourites.isEmpty() && !biomassButton)) return;
		Font font = mc.font;
		int screenW = g.guiWidth();
		int screenH = g.guiHeight();
		double diameter = Math.min(screenW, screenH) * ClientConfig.INSTANCE.radialScale;
		double radius = diameter / 2D;
		float radialProg = MorphState.sineify(Mth.clamp((radialTime + partialTick) / RADIAL_TIME, 0F, 1F));
		double radialDist = radius * radialProg;
		float textScale = (float) radius / 96.375F;
		float deadzoneScale = 0.55F;
		double distFromDeadzone = mouseDistanceFromCenter() - radius * deadzoneScale;
		float bonusScale = Mth.clamp((float) (distFromDeadzone / (radius * (1F - deadzoneScale) * 0.5F)), 0F, 1F);
		float cx = screenW / 2F;
		float cy = screenH / 2F;
		int slices = 100;
		g.guiRenderState.addGuiElement(new RingRenderState(cx, cy, (float) (radialDist * deadzoneScale), (float) radialDist, slices, 150 << 24));

		boolean outside = isMouseOutsideRadialDeadZone();
		int selectedIndex = outside && !favourites.isEmpty() ? selectedRadialIndex(favourites.size()) : -1; // the Survival wheel can be empty
		List<MorphGuiEntities.Entry> entities = new ArrayList<>();
		List<Runnable> overlays = new ArrayList<>();
		Component title = Component.translatable("key.morphreborn.favourite");
		if (biomassButton) {
			drawBiomassButton(g, overlays, cx, cy, (float) (radialDist * BIOMASS_BUTTON), textScale);
		} else {
			overlays.add(() -> {
				g.pose().pushMatrix();
				g.pose().translate(cx, cy);
				g.pose().scale(textScale, textScale);
				g.text(font, title, -font.width(title) / 2, -font.lineHeight / 2, 0xFFFFFFFF, true);
				g.pose().popMatrix();
			});
		}
		for (int i = 0; i < favourites.size(); i++) {
			Collection.Variant variant = favourites.get(i);
			double angle = Math.toRadians(90F + 360F * i / favourites.size());
			float ex = (float) (cx - Math.cos(angle) * radialDist * 0.775F);
			float ey = (float) (cy - Math.sin(angle) * radialDist * 0.775F);
			LivingEntity living = MorphClient.ENTITIES.forGui(variant.ref);
			if (living != null) {
				float entSize = Math.max(living.getBbWidth(), living.getBbHeight()) / 1.95F;
				float entScale = 0.4F * (1F / Math.max(1F, entSize)) * radialProg * textScale;
				if (i == selectedIndex) entScale += 0.1F * bonusScale;
				addEntity(entities, living, ex, (float) (ey + radialDist * 0.1F), PREVIEW_SCALE * entScale, partialTick);
			}
			MutableComponent name = customName(variant.ref);
			if (name == null) name = typeName(variant.ref.entityType());
			name = name.withStyle(isCurrent(variant.ref) ? ChatFormatting.GOLD : i == selectedIndex ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
			MutableComponent label = name;
			float labelScale = 0.5F * radialProg * textScale;
			float ly = (float) (radialDist * 0.1F + font.lineHeight / 1.75F * textScale);
			overlays.add(() -> {
				g.pose().pushMatrix();
				g.pose().translate(ex, ey + ly);
				g.pose().scale(labelScale, labelScale);
				g.text(font, label, -font.width(label) / 2, 0, 0xFFFFFFFF, true);
				g.pose().popMatrix();
			});
		}
		if (!entities.isEmpty()) g.guiRenderState.addPicturesInPictureState(new MorphGuiEntities.State(entities, screenW, screenH));
		g.nextStratum();
		overlays.forEach(Runnable::run);
	}

	/** Size of the wheel's centre Biomass button, as a share of the wheel's radius (the dead zone is 0.55). */
	private static final float BIOMASS_BUTTON = 0.24F; // a compact button well inside the dead zone (0.55)

	/** Mouse over the centre Biomass button. */
	private boolean isMouseOnBiomassButton() {
		double diameter = Math.min(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()) * ClientConfig.INSTANCE.radialScale;
		return mouseDistanceFromCenter() <= diameter / 2D * BIOMASS_BUTTON;
	}

	/**
	 * Survival Mode's Biomass button in the middle of the wheel: the morph's static under a red tint, with the biomass
	 * amount. Clicking it (or letting go of the key over it) opens the upgrade tree.
	 */
	private void drawBiomassButton(GuiGraphicsExtractor g, List<Runnable> overlays, float cx, float cy, float half, float textScale) {
		if (half < 4) return;
		boolean hover = isMouseOnBiomassButton();
		int x0 = Math.round(cx - half);
		int y0 = Math.round(cy - half);
		int size = Math.round(half * 2);
		int frame = mc.player == null ? 0 : mc.player.tickCount / 2;
		g.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, hover ? 0xFFFF5252 : 0xFF000000);
		for (int sx = 0; sx < size; sx += 16) {
			for (int sy = 0; sy < size; sy += 16) {
				int seed = (frame * 13 + sx * 7 + sy * 31) & 0xFFFF;
				g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, dev.morphreborn.client.render.MorphWorldRenderer.MORPH_SKIN, x0 + sx, y0 + sy,
						seed % 48, (seed / 48) % 48, Math.min(16, size - sx), Math.min(16, size - sy), 64, 64);
			}
		}
		g.fill(x0, y0, x0 + size, y0 + size, hover ? 0x709E1B1B : 0x509E1B1B);
		Font font = mc.font;
		Component label = Component.translatable("morphreborn.wheel.biomass");
		Component amount = ClientNet.biomassUnlocked() ? Component.literal(ClientNet.biomass() + " / " + ClientNet.biomassCapacity())
				: Component.translatable("morphreborn.upgrades.locked_short");
		float scale = textScale * 0.6F;
		overlays.add(() -> {
			g.pose().pushMatrix();
			g.pose().translate(cx, cy);
			g.pose().scale(scale, scale);
			g.text(font, label, -font.width(label) / 2, -font.lineHeight, hover ? 0xFFFFFFFF : 0xFFFFCDD2, true);
			g.text(font, amount, -font.width(amount) / 2, 1, 0xFFEF9A9A, true);
			g.pose().popMatrix();
		});
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void addEntity(List<MorphGuiEntities.Entry> out, LivingEntity living, float x, float y, float pixelsPerBlock, float partialTick) {
		if (pixelsPerBlock <= 0.01F) return;
		EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(living);
		EntityRenderState state = renderer.createRenderState(living, 1F);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		state.scoreText = null;
		state.lightCoords = 15728880;
		state.ageInTicks = mc.player.tickCount + partialTick;
		if (state instanceof LivingEntityRenderState l) {
			l.bodyRot = 180F + PREVIEW_YAW * 20F;
			l.yRot = PREVIEW_YAW * 20F;
			l.xRot = 0F;
			l.boundingBoxWidth /= l.scale;
			l.boundingBoxHeight /= l.scale;
			l.scale = 1F;
			l.hasRedOverlay = false;
			l.deathTime = 0;
			l.walkAnimationSpeed = 0;
		}
		out.add(new MorphGuiEntities.Entry(state, x, y, pixelsPerBlock));
	}

	private static MutableComponent typeName(String entityType) {
		Identifier id = Identifier.tryParse(entityType);
		EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		return type == null ? Component.translatable("morphreborn.morph.type.unknown") : type.getDescription().copy();
	}

	/** Italic custom name (name-tagged mobs, other players' forms), or null. */
	private @Nullable MutableComponent customName(MorphRef ref) {
		if (ref.isPlayer()) {
			if (ref.isSelf() || ref.skin() == null) return null;
			return Component.literal(ref.skin().name()).withStyle(ChatFormatting.ITALIC);
		}
		LivingEntity living = MorphClient.ENTITIES.forGui(ref);
		if (living != null && living.hasCustomName() && living.getCustomName() != null) {
			return living.getCustomName().copy().withStyle(ChatFormatting.ITALIC);
		}
		return null;
	}

	private void text(GuiGraphicsExtractor g, Component text, double x, double y) {
		g.pose().pushMatrix();
		g.pose().translate((float) x, (float) y);
		g.text(mc.font, text, 0, 0, 0xFFFFFFFF, true);
		g.pose().popMatrix();
	}

	private void tile(GuiGraphicsExtractor g, Identifier texture, double x, double y, double size, int color) {
		tile(g, texture, x, y, size, color, TEX);
	}

	private void tile(GuiGraphicsExtractor g, Identifier texture, double x, double y, double size, int color, int texSize) {
		g.pose().pushMatrix();
		g.pose().translate((float) x, (float) y);
		g.pose().scale((float) (size / texSize), (float) (size / texSize));
		g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0F, 0F, texSize, texSize, texSize, texSize, texSize, texSize, color);
		g.pose().popMatrix();
	}

	// ------------------------------------------------------------------ radial mouse maths (iChunUtil MouseHelper)

	private double mouseDistanceFromCenter() {
		double scale = mc.getWindow().getGuiScale();
		double mx = mc.mouseHandler.xpos() / scale;
		double my = mc.mouseHandler.ypos() / scale;
		double dx = mx - mc.getWindow().getGuiScaledWidth() / 2D;
		double dy = my - mc.getWindow().getGuiScaledHeight() / 2D;
		return Math.sqrt(dx * dx + dy * dy);
	}

	private boolean isMouseOutsideRadialDeadZone() {
		double diameter = Math.min(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()) * ClientConfig.INSTANCE.radialScale;
		return mouseDistanceFromCenter() > diameter / 2D * 0.55F;
	}

	/** Index of the slice under the mouse; slice 0 is at the top and indices run clockwise, matching the layout. */
	private int selectedRadialIndex(int count) {
		double scale = mc.getWindow().getGuiScale();
		double dx = mc.mouseHandler.xpos() / scale - mc.getWindow().getGuiScaledWidth() / 2D;
		double dy = mc.mouseHandler.ypos() / scale - mc.getWindow().getGuiScaledHeight() / 2D;
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) angle += Math.PI * 2;
		double slice = Math.PI * 2 / count;
		return (int) Math.floor((angle + slice / 2) / slice) % count;
	}

	public void reset() {
		showSelector = showRadial = false;
		showTime = radialTime = 0;
		radialFavourites = null;
	}
}
