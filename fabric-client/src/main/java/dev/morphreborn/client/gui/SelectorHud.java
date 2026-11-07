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
	/** Multimorph: the main body Shift-picked in the selector or the wheel; the next pick is the form it borrows from. */
	private Collection.@Nullable Variant pendingMain;
	/** The wheel stays open after its key was let go with Shift held, for the second pick. */
	private boolean radialSticky;
	private int seenRevision = -1;

	private static Identifier tex(String path) {
		return Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, path);
	}

	private Collection data() {
		return MorphClient.COLLECTION;
	}

	/** Survival Mode: say why the selector doesn't open, and where to go. */
	private static void cantMorphYet() {
		net.minecraft.network.chat.MutableComponent message;
		if (!ClientNet.biomassUnlocked()) message = Component.translatable("morphreborn.message.unlock_biomass");
		else if (Keys.BIOMASS.isUnbound()) message = Component.translatable("morphreborn.message.buy_morph_ability_no_key");
		else message = Component.translatable("morphreborn.message.buy_morph_ability", Keys.BIOMASS.getTranslatedKeyMessage());
		ClientNet.message(message.withStyle(ChatFormatting.GRAY));
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
			confirmRadial(false);
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
				else confirmRadial(true); // Enter is a deliberate pick, like a click
			}
			if (mc.gui.screen() != null || (!keyEscDown && esc)) {
				if (mc.gui.screen() instanceof PauseScreen) mc.gui.setScreen(null);
				if (showSelector) closeSelector();
				else closeRadial();
			}
			if (showSelector) {
				if (!keyDeleteDown && delete) {
					if (pendingMain != null) closeSelector(); // picking a multimorph: Backspace closes, it never deletes
					else deleteSelector();
				}
				if (!keyUpDown && up) shiftIndexSelector(false, false);
				if (!keyDownDown && down) shiftIndexSelector(true, false);
				if (!keyLeftDown && left) shiftIndexSelector(false, true);
				if (!keyRightDown && right) shiftIndexSelector(true, true);
			} else if (!keyDeleteDown && delete) {
				closeRadial();
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
			if (button == InputConstants.MOUSE_BUTTON_LEFT) confirmRadial(true);
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

	/** The form the player's body borrows from now, or null. */
	private @Nullable MorphRef borrowedNow() {
		if (mc.player == null) return null;
		MorphState st = MorphClient.STATES.get(mc.player.getUUID());
		return st == null ? null : st.borrowed;
	}

	/** Why {@code main} can't borrow from {@code other}, or null if it can. */
	private static @Nullable Component cannotBorrow(MorphRef main, MorphRef other) {
		if (!MultimorphPreview.combinable(other)) return Component.translatable("morphreborn.multimorph.cant_combine", typeName(other.entityType()));
		if (main.entityType().equals(other.entityType())) return Component.translatable("morphreborn.multimorph.same_type");
		return null;
	}

	/** Shift held on a pick: start a multimorph with {@code ref} as its main body. False if this pick isn't one. */
	private boolean startMultimorph(Collection.Variant variant) {
		if (!Keys.shiftDown() || !ClientNet.multimorphAllowed()) return false;
		if (MultimorphPreview.combinable(variant.ref)) pendingMain = variant;
		else ClientNet.message(Component.translatable("morphreborn.multimorph.cant_combine", typeName(variant.ref.entityType())).withStyle(ChatFormatting.GRAY));
		return true;
	}

	/** The second pick of a multimorph. False (and why, on screen) if {@code ref} can't be borrowed. */
	private boolean finishMultimorph(MorphRef ref) {
		Collection.Variant main = pendingMain;
		if (main == null) return false;
		Component why = cannotBorrow(main.ref, ref);
		if (why != null) {
			ClientNet.message(why.copy().withStyle(ChatFormatting.GRAY));
			return false;
		}
		ClientNet.select(main.ref.variantId(), ref.variantId());
		return true;
	}

	/** An ordinary pick. Picking the body you are already in drops what it borrows. */
	private void pick(MorphRef ref) {
		if (!isCurrent(ref) || borrowedNow() != null) ClientNet.select(ref.variantId());
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

	/**
	 * Enter or a click. With Shift held on a creature, it becomes a multimorph's main body (its tile turns red, with
	 * tendrils) and the selector stays open: picking another creature makes it the borrowed form, picking the main body
	 * again makes you just that creature, and right click, Esc or Backspace closes the selector.
	 */
	private void confirmSelector() {
		Collection.Variant variant = selectedVariant();
		if (variant == null) {
			closeSelector();
			return;
		}
		if (pendingMain != null && !variant.ref.isSelf()) {
			if (variant.ref.variantId().equals(pendingMain.ref.variantId())) pick(variant.ref); // the same one again: just that creature
			else if (!finishMultimorph(variant.ref)) return;
			closeSelector();
			return;
		}
		if (pendingMain == null && startMultimorph(variant)) return;
		pick(variant.ref);
		closeSelector();
	}

	private void deleteSelector() {
		Collection.Variant variant = selectedVariant();
		if (variant != null && ClientNet.deleteAllowed() && !isCurrent(variant.ref) && !variant.ref.isSelf()) ClientNet.delete(variant.ref.variantId());
	}

	private void closeSelector() {
		showSelector = false;
		pendingMain = null;
		List<Collection.Row> rows = data().rows();
		if (!rows.isEmpty() && indexVert < rows.size()) indexHori = rows.get(indexVert).variants.size() - 1; // slide the row back in
		indexChangeTime = 0;
	}

	/**
	 * Letting go of the favourite key (or a left click) picks the favourite under the mouse. In the middle nothing is
	 * picked and the wheel just closes; only a click on the Biomass button there opens the upgrade tree, so letting go
	 * with the mouse still in the middle never opens it by accident.
	 */
	private void confirmRadial(boolean click) {
		if (survivalWheel() && isMouseOnBiomassButton()) {
			closeRadial();
			if (click) ClientNet.openUpgrades();
			return;
		}
		Collection.Variant variant = isMouseOutsideRadialDeadZone() && radialFavourites != null && !radialFavourites.isEmpty()
				? radialFavourites.get(selectedRadialIndex(radialFavourites.size())) : null;
		if (pendingMain != null) {
			if (variant != null && variant.ref.variantId().equals(pendingMain.ref.variantId())) {
				pick(variant.ref); // the same one again: just that creature
				closeRadial();
				return;
			}
			if (variant != null) {
				if (finishMultimorph(variant.ref) || (!click && !radialSticky)) closeRadial();
				return;
			}
			// the middle: no multimorph after all
			pendingMain = null;
			if (!click || radialSticky) closeRadial();
			return;
		}
		// Shift on a favourite: a multimorph's main body; let go of the key with Shift held and the wheel stays open
		if (variant != null && startMultimorph(variant)) {
			if (pendingMain != null && !click) radialSticky = true;
			else if (pendingMain == null && !click) closeRadial();
			return;
		}
		if (variant != null) pick(variant.ref);
		closeRadial();
	}

	private void closeRadial() {
		showRadial = false;
		radialFavourites = null;
		pendingMain = null;
		radialSticky = false;
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
		if (pendingMain != null) {
			int[] at = positionOf(pendingMain);
			if (at != null) {
				double rx = at[0] == indexVert ? posX + (at[1] - indexHoriProg) * size : posX;
				double ry = at[0] == indexVert ? top : stackTop + at[0] * size;
				tile(g, TEX_SELECTED, rx, ry, size, pulseRed(partialTick)); // the whole tile, tinted red
				tendrilFrame(g, rx, ry, size, (mc.player == null ? 0 : mc.player.tickCount) + partialTick);
			}
			multimorphPanel(g, entities, overlays, screenW - PANEL_W - 8, top, selectedVariant(), partialTick);
		}
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
						MutableComponent text = typeName(row.entityType).withStyle(isCurrent(selected.ref) ? ChatFormatting.GOLD : ChatFormatting.YELLOW)
								.append(price(selected.ref));
						MorphRef lent = borrowedNow();
						if (lent != null && isCurrent(selected.ref)) text.append(Component.literal(" + ").append(typeName(lent.entityType())).withStyle(ChatFormatting.GOLD));
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
				MutableComponent text = typeName(row.entityType).withStyle(containsCurrent ? ChatFormatting.GOLD : ChatFormatting.WHITE).append(price(variant.ref));
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
		if (pendingMain != null) {
			multimorphPanel(g, entities, overlays, screenW - PANEL_W - 8, (screenH - PANEL_H) / 2, selectedIndex >= 0 ? favourites.get(selectedIndex) : null, partialTick);
		}
		Component title = Component.translatable("key.morphreborn.favourite");
		if (biomassButton) {
			drawBiomassButton(g, overlays, cx, cy, textScale * radialProg, partialTick);
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
			if (pendingMain != null && variant.ref.variantId().equals(pendingMain.ref.variantId())) {
				ring(g, ex, ey, (float) (radialDist * 0.17D), (float) (radialDist * 0.2D), partialTick);
			}
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
			Component cost = wheelPrice(variant.ref);
			float labelScale = 0.5F * radialProg * textScale;
			float ly = (float) (radialDist * 0.1F + font.lineHeight / 1.75F * textScale);
			overlays.add(() -> {
				g.pose().pushMatrix();
				g.pose().translate(ex, ey + ly);
				g.pose().scale(labelScale, labelScale);
				g.text(font, label, -font.width(label) / 2, 0, 0xFFFFFFFF, true);
				if (cost != null) g.text(font, cost, -font.width(cost) / 2, font.lineHeight, 0xFFFFFFFF, true);
				g.pose().popMatrix();
			});
		}
		if (!entities.isEmpty()) g.guiRenderState.addPicturesInPictureState(new MorphGuiEntities.State(entities, screenW, screenH));
		g.nextStratum();
		overlays.forEach(Runnable::run);
	}

	/** The Biomass button's text size, as a share of the wheel's text size. */
	private static final float BIOMASS_TEXT = 0.5F;

	private Component biomassLabel() {
		return Component.translatable("morphreborn.wheel.biomass");
	}

	private Component biomassAmount() {
		return ClientNet.biomassUnlocked() ? Component.literal(ClientNet.biomass() + " / " + ClientNet.biomassCapacity())
				: Component.translatable("morphreborn.upgrades.locked_short");
	}

	/** The Biomass button's radius: just big enough for its two lines of text. */
	private float biomassButtonRadius(float textScale) {
		Font font = mc.font;
		float scale = textScale * BIOMASS_TEXT;
		float textWidth = Math.max(font.width(biomassLabel()), font.width(biomassAmount())) * scale;
		return Math.max(textWidth / 2F, font.lineHeight * scale) + 4F * textScale;
	}

	/** Mouse over the centre Biomass button (the round part, not its tendrils). */
	private boolean isMouseOnBiomassButton() {
		double diameter = Math.min(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()) * ClientConfig.INSTANCE.radialScale;
		float r = biomassButtonRadius((float) (diameter / 2D) / 96.375F);
		double scale = mc.getWindow().getGuiScale();
		double mx = mc.mouseHandler.xpos() / scale;
		double my = mc.mouseHandler.ypos() / scale;
		return BiomassOrb.contains(mc.getWindow().getGuiScaledWidth() / 2D, mc.getWindow().getGuiScaledHeight() / 2D, r / BiomassOrb.RADIUS, mx, my);
	}

	/**
	 * Survival Mode's Biomass button in the middle of the wheel: a lump of biomass with tendrils reaching out, the
	 * morph's static inside, and the biomass amount. Clicking it opens the upgrade tree; letting go of the key over it
	 * only closes the wheel.
	 */
	private void drawBiomassButton(GuiGraphicsExtractor g, List<Runnable> overlays, float cx, float cy, float textScale, float partialTick) {
		float r = biomassButtonRadius(textScale);
		if (r < 4) return;
		boolean hover = isMouseOnBiomassButton();
		float time = (mc.player == null ? 0 : mc.player.tickCount) + partialTick;
		BiomassOrb.draw(g, cx, cy, r / BiomassOrb.RADIUS, time, hover ? 0xFFE53935 : 0xFF6D1010, hover ? 0x709E1B1B : 0x509E1B1B, hover ? 0.8F : 0F);
		Font font = mc.font;
		Component label = biomassLabel();
		Component amount = biomassAmount();
		Component click = Component.translatable("morphreborn.wheel.biomass_click");
		boolean showClick = hover && ClientConfig.INSTANCE.selectorAllowMouseControl;
		float scale = textScale * BIOMASS_TEXT;
		overlays.add(() -> {
			g.pose().pushMatrix();
			g.pose().translate(cx, cy);
			g.pose().scale(scale, scale);
			g.text(font, label, -font.width(label) / 2, -font.lineHeight, hover ? 0xFFFFFFFF : 0xFFFFCDD2, true);
			g.text(font, amount, -font.width(amount) / 2, 1, 0xFFEF9A9A, true);
			g.pose().popMatrix();
			if (showClick) {
				// under the orb and its tendrils, so the hint never sits on top of the text
				float hintScale = scale * 0.8F;
				g.pose().pushMatrix();
				g.pose().translate(cx, cy + r * 1.85F);
				g.pose().scale(hintScale, hintScale);
				g.text(font, click, -font.width(click) / 2, 0, 0xFFFFEB3B, true);
				g.pose().popMatrix();
			}
		});
	}

	private void addEntity(List<MorphGuiEntities.Entry> out, LivingEntity living, float x, float y, float pixelsPerBlock, float partialTick) {
		if (pixelsPerBlock <= 0.01F) return;
		out.add(new MorphGuiEntities.Entry(MultimorphPreview.guiState(living, 180F + PREVIEW_YAW * 20F, PREVIEW_YAW * 20F, partialTick), x, y, pixelsPerBlock));
	}

	// ------------------------------------------------------------------ multimorph picking

	private static final int PANEL_W = 152;
	private static final int PANEL_H = 166;

	/** Where {@code variant} sits in the selector: {row, column}, or null. */
	private int @Nullable [] positionOf(Collection.Variant variant) {
		List<Collection.Row> rows = data().rows();
		for (int i = 0; i < rows.size(); i++) {
			List<Collection.Variant> variants = rows.get(i).variants;
			for (int j = 0; j < variants.size(); j++) {
				if (variants.get(j).ref.variantId().equals(variant.ref.variantId())) return new int[] {i, j};
			}
		}
		return null;
	}

	/** Biomass red, pulsing: marks a multimorph's chosen main body. */
	private int pulseRed(float partialTick) {
		float pulse = 0.65F + 0.35F * Mth.sin(((mc.player == null ? 0 : mc.player.tickCount) + partialTick) * 0.25F);
		return ((int) (pulse * 255F) << 24) | 0xE53935;
	}

	/**
	 * Black and red tendrils of goo reaching out of a tile's four edges, swaying and breathing in and out, like the
	 * biomass bar's and the Biomass button's: they mark a multimorph's chosen main body.
	 */
	private void tendrilFrame(GuiGraphicsExtractor g, double x, double y, double size, float time) {
		int perSide = 4;
		for (int side = 0; side < 4; side++) {
			for (int k = 0; k < perSide; k++) {
				int i = side * perSide + k;
				double along = (k + 0.5 + 0.3 * Math.sin(time * 0.02 + i * 1.3)) / perSide;
				// where it grows from, and outwards
				double bx, by, nx, ny;
				switch (side) {
					case 0 -> { bx = x + along * size; by = y; nx = 0; ny = -1; }
					case 1 -> { bx = x + size; by = y + along * size; nx = 1; ny = 0; }
					case 2 -> { bx = x + (1 - along) * size; by = y + size; nx = 0; ny = 1; }
					default -> { bx = x; by = y + (1 - along) * size; nx = -1; ny = 0; }
				}
				double length = size * (0.11 + 0.07 * Math.sin(time * 0.055 + i * 2.3));
				boolean red = i % 2 == 0;
				for (double s = 0; s <= length; s += 0.8) {
					double t = s / length;
					double wobble = Math.sin(time * 0.16 + s * 0.5 + i * 1.7) * 2.2 * t;
					float px = (float) (bx + nx * s - ny * wobble);
					float py = (float) (by + ny * s + nx * wobble);
					float dot = (float) (2.6 - 1.8 * t);
					int alpha = t < 0.7 ? 0xFF : (int) (0xFF * (1 - (t - 0.7) / 0.3));
					int color = (alpha << 24) | (red ? lerpColor(0x7A1018, 0xE53935, t) : lerpColor(0x050505, 0x2A1214, t));
					g.pose().pushMatrix();
					g.pose().translate(px - dot / 2, py - dot / 2);
					g.pose().scale(dot, dot);
					g.fill(0, 0, 1, 1, color);
					g.pose().popMatrix();
				}
			}
		}
	}

	private static int lerpColor(int from, int to, double t) {
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int gr = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (r << 16) | (gr << 8) | b;
	}

	/** The wheel has no tiles: a pulsing red ring around the chosen main body there. */
	private void ring(GuiGraphicsExtractor g, float cx, float cy, float inner, float outer, float partialTick) {
		g.guiRenderState.addGuiElement(new RingRenderState(cx, cy, inner, outer, 48, pulseRed(partialTick)));
	}

	/** The preview while a multimorph is being picked: the main body with {@code candidate} (the morph under the cursor) on it. */
	private void multimorphPanel(GuiGraphicsExtractor g, List<MorphGuiEntities.Entry> entities, List<Runnable> overlays, int x, int y,
			Collection.@Nullable Variant candidate, float partialTick) {
		Collection.Variant pending = pendingMain;
		if (pending == null) return;
		MorphRef main = pending.ref;
		MorphRef lent = candidate != null && !candidate.ref.variantId().equals(main.variantId()) && cannotBorrow(main, candidate.ref) == null ? candidate.ref : null;
		List<Component> lines = new ArrayList<>();
		lines.add(lent == null ? Component.translatable("morphreborn.multimorph.pair", typeName(main.entityType()), "?").withStyle(ChatFormatting.YELLOW)
				: Component.translatable("morphreborn.multimorph.pair", typeName(main.entityType()), typeName(lent.entityType())).withStyle(ChatFormatting.YELLOW));
		int cost = (isCurrent(main) ? 0 : Math.max(0, ClientNet.morphCost(main.variantId()))) + (lent == null ? 0 : Math.max(0, ClientNet.morphCost(lent.variantId())));
		if (lent != null) cost = ClientNet.multimorphCost(cost);
		if (cost > 0) {
			lines.add(Component.literal(String.format(java.util.Locale.ROOT, "%,d biomass", cost))
					.withStyle(cost <= ClientNet.biomass() ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
		}
		lines.add(Component.translatable(lent == null ? "morphreborn.multimorph.pick_second" : "morphreborn.multimorph.pick_to_combine").withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("morphreborn.multimorph.again", typeName(main.entityType())).withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("morphreborn.multimorph.close").withStyle(ChatFormatting.DARK_GRAY));
		MultimorphPreview.panel(g, entities, overlays, x, y, PANEL_W, PANEL_H, main, lent, Component.translatable("morphreborn.multimorph.title"), lines, partialTick);
	}

	private static MutableComponent typeName(String entityType) {
		Identifier id = Identifier.tryParse(entityType);
		EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
		return type == null ? Component.translatable("morphreborn.morph.type.unknown") : type.getDescription().copy();
	}

	/**
	 * Survival: "  12 biomass" after a morph's name, red when you can afford it and grey when you can't. Nothing when
	 * morphing is free, or for the form you're already in.
	 */
	private Component price(MorphRef ref) {
		int cost = ClientNet.morphCost(ref.variantId());
		if (cost <= 0 || isCurrent(ref)) return Component.empty();
		boolean affordable = cost <= ClientNet.biomass();
		return Component.literal(String.format(java.util.Locale.ROOT, "  %,d biomass", cost)).withStyle(affordable ? ChatFormatting.RED : ChatFormatting.DARK_GRAY);
	}

	/** Survival: the price line under a favourite in the wheel, coloured like the selector's; null when free or current. */
	private @Nullable Component wheelPrice(MorphRef ref) {
		int cost = ClientNet.morphCost(ref.variantId());
		if (cost <= 0 || isCurrent(ref)) return null;
		boolean affordable = cost <= ClientNet.biomass();
		// Light grey, not the selector's dark grey: the wheel's ring behind the text is dark.
		return Component.literal(String.format(java.util.Locale.ROOT, "%,d biomass", cost)).withStyle(affordable ? ChatFormatting.RED : ChatFormatting.GRAY);
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
		pendingMain = null;
		radialSticky = false;
		showSelector = showRadial = false;
		showTime = radialTime = 0;
		radialFavourites = null;
	}
}
