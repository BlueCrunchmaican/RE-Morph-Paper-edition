package dev.morphreborn.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.morphreborn.client.net.MorphRef;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /morphclient preview [main] [borrowed]}: any two creatures combined, drawn as a multimorph is, without owning
 * or morphing into them. For checking that combinations look right: arrows change the forms, dragging turns the pair,
 * scrolling zooms.
 */
public final class MultimorphPreviewScreen extends Screen {
	private final List<String> types = new ArrayList<>();
	private int main;
	/** Index into {@link #types}, or -1 for the main body alone. */
	private int borrowed = -1;
	private float yaw = 205F;
	private float zoom = 1F;
	private final String startMain;
	private final @Nullable String startBorrowed;

	public MultimorphPreviewScreen(String main, @Nullable String borrowed) {
		super(Component.translatable("morphreborn.preview.title"));
		this.startMain = normalise(main);
		this.startBorrowed = borrowed == null ? null : normalise(borrowed);
	}

	private static String normalise(String type) {
		return type.contains(":") ? type : "minecraft:" + type;
	}

	/** Every creature that can be part of a multimorph, by id. */
	public static List<String> combinableTypes() {
		List<String> out = new ArrayList<>();
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return out;
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
			if (!MultimorphPreview.combinable(new MorphRef(id, id, "", null))) continue;
			Entity probe;
			try {
				probe = type.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
			} catch (RuntimeException e) {
				continue;
			}
			if (probe instanceof LivingEntity && !(probe instanceof ArmorStand) && !(probe instanceof Player)) out.add(id);
		}
		out.sort(null);
		return out;
	}

	@Override
	protected void init() {
		if (types.isEmpty()) {
			types.addAll(combinableTypes());
			main = Math.max(0, types.indexOf(startMain));
			borrowed = startBorrowed == null ? -1 : types.indexOf(startBorrowed);
		}
	}

	private static MorphRef ref(String type) {
		return new MorphRef(type, "preview:" + type, "", null);
	}

	private static Component name(String type) {
		EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(type));
		return t.getDescription();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (types.isEmpty()) return super.keyPressed(event);
		int n = types.size();
		switch (event.key()) {
			case InputConstants.KEY_RIGHT -> borrowed = borrowed + 1 >= n ? -1 : borrowed + 1;
			case InputConstants.KEY_LEFT -> borrowed = borrowed - 1 < -1 ? n - 1 : borrowed - 1;
			case InputConstants.KEY_DOWN -> main = (main + 1) % n;
			case InputConstants.KEY_UP -> main = (main - 1 + n) % n;
			case InputConstants.KEY_SPACE -> borrowed = -1;
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		yaw -= (float) dx * 1.5F;
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		zoom = Math.clamp(zoom * (scrollY > 0 ? 1.15F : 1F / 1.15F), 0.3F, 4F);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		if (types.isEmpty()) return;
		String mainType = types.get(main);
		String lentType = borrowed < 0 ? null : types.get(borrowed);
		MorphRef mainRef = ref(mainType);
		MorphRef lentRef = lentType == null ? null : ref(lentType);
		float extent = MultimorphPreview.extent(mainRef, lentRef);
		float pixelsPerBlock = Math.min(height * 0.6F, width * 0.5F) / extent * zoom;
		EntityRenderState state = MultimorphPreview.hybrid(mainRef, lentRef, yaw, 0F, partialTick);
		if (state != null) {
			g.guiRenderState.addPicturesInPictureState(new MorphGuiEntities.State(List.of(new MorphGuiEntities.Entry(state, width / 2F, height * 0.78F, pixelsPerBlock)),
					width, height));
		}
		g.nextStratum();
		Component pair = lentType == null ? name(mainType).copy().withStyle(ChatFormatting.YELLOW)
				: Component.translatable("morphreborn.multimorph.pair", name(mainType), name(lentType)).withStyle(ChatFormatting.YELLOW);
		g.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFCDD2, true);
		g.text(font, pair, (width - font.width(pair)) / 2, 22, 0xFFFFFFFF, true);
		// two short lines, so they fit a small window
		Component keys = Component.translatable("morphreborn.preview.help").withStyle(ChatFormatting.GRAY);
		Component mouse = Component.translatable("morphreborn.preview.help_mouse").withStyle(ChatFormatting.GRAY);
		g.text(font, keys, (width - font.width(keys)) / 2, height - 24, 0xFFFFFFFF, true);
		g.text(font, mouse, (width - font.width(mouse)) / 2, height - 13, 0xFFFFFFFF, true);
	}
}
