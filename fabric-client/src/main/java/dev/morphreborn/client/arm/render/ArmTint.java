package dev.morphreborn.client.arm.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.arm.ArmColours;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.render.MorphWorldRenderer;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Each player's colour for the red parts of their arm (the {@link ArmLayer#RED} and {@link ArmLayer#GLOW} layers) and
 * for the mod's other red accents (the red absorb tendrils, the frenzy's tendrils and legs). Black stays black.
 * <p>
 * <b>How it stays out of the geometry's way.</b> The colour lives in the textures, not in the vertices: a player in a
 * colour other than {@link ArmProtocol#COLOUR_DEFAULT} is drawn with a copy of the red and glow skins recoloured into
 * it, built once per colour on first use from whatever {@code morphskin_red.png} and {@code morphskin_glow.png} are
 * loaded (a resource pack's included). So geometry never knows about colours: any piece any form emits in the red or
 * glow layer, today's or one added or reshaped later, comes out in its owner's colour, and the vertex colour stays free
 * for brightness (pulses, locked icons). The default colour uses the shipped textures as they are, so it looks exactly
 * as it always has.
 * <p>
 * <b>Whose colour.</b> {@link ArmTex#type} draws in the colour of the current owner. Every arm draw is finished by
 * {@code ArmDrawFactory.finish} or {@code finishSheath}, which set the owner from the draw's seed (the owner's entity
 * id); the world effects set it per effect with {@link #owner} before they submit, and {@code ArmFx} puts it back to
 * the default before each handler. Anything new that draws arm layers outside those paths should call {@link #owner}
 * (or it is drawn in the default red). Render thread only.
 * <p>
 * <b>The recolour.</b> Each texel is taken to HSV and moved by how the chosen colour differs from the default: hue
 * turned by the difference, saturation and brightness scaled by the ratio. The default maps to itself; white keeps the
 * static's shading in greys; any hue keeps the glow's bright, near-white highlights.
 */
public final class ArmTint {
	/** Recoloured skins kept at once; the oldest is released past this (a server with more colours than this rebuilds). */
	private static final int MAX_COLOURS = 48;

	private static int owner = Integer.MIN_VALUE;
	private static int current = ArmProtocol.COLOUR_DEFAULT;

	/** Colour → its recoloured red skin, and its glow skin; least recently used first. */
	private static final Map<Integer, Identifier[]> SKINS = new LinkedHashMap<>(16, 0.75F, true);
	private static boolean failedOnce;

	private ArmTint() {}

	// ------------------------------------------------------------------ whose colour

	/** Draws from now on are {@code entityId}'s (an arm's seed, an effect's origin). */
	public static void owner(int entityId) {
		owner = entityId;
		current = ofEntity(entityId); // looked up every time (two map lookups): a colour or an entity may arrive any frame
	}

	/**
	 * Draws from now on are in {@code rgb} rather than the owner's colour (an arrow tip's bleed red or poison green): the red
	 * and glow skins are recoloured into it the way a player's colour is. Call {@link #owner} again to go back to the owner's.
	 */
	public static void colour(int rgb) {
		current = rgb & 0xFFFFFF;
	}

	/** Draws from now on are in the default red (no owner). */
	public static void reset() {
		owner = Integer.MIN_VALUE;
		current = ArmProtocol.COLOUR_DEFAULT;
	}

	/** The current owner's colour. */
	public static int current() {
		return current;
	}

	/**
	 * {@code entityId}'s colour: your own straight from your setting (so you see it at once, on any server), anyone
	 * else's as their server last said, the default when unknown (no such entity, a server without colours, a preview).
	 */
	public static int ofEntity(int entityId) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null) return ArmProtocol.COLOUR_DEFAULT;
			if (mc.player != null && mc.player.getId() == entityId) return ArmColours.own();
			var entity = mc.level.getEntity(entityId);
			return entity == null ? ArmProtocol.COLOUR_DEFAULT : ArmColours.of(entity.getUUID());
		} catch (RuntimeException | LinkageError e) {
			return ArmProtocol.COLOUR_DEFAULT;
		}
	}

	/**
	 * {@code entityId}'s arm look (ArmProtocol.LOOK_*): your own straight from your setting, anyone else's as their
	 * server last said, the default when unknown (no such entity, a server without looks, a preview).
	 */
	public static int lookOfEntity(int entityId) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null) return ArmProtocol.LOOK_DEFAULT;
			if (mc.player != null && mc.player.getId() == entityId) return ArmColours.ownLook();
			var entity = mc.level.getEntity(entityId);
			return entity == null ? ArmProtocol.LOOK_DEFAULT : ArmColours.lookOf(entity.getUUID());
		} catch (RuntimeException | LinkageError e) {
			return ArmProtocol.LOOK_DEFAULT;
		}
	}

	/**
	 * {@code entityId}'s pulse speed as a multiple of the usual (1 is the usual): your own straight from your setting,
	 * anyone else's as their server last said, the usual speed when unknown (no such entity, a server without it, a preview).
	 */
	public static float pulseSpeedOfEntity(int entityId) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null) return 1F;
			if (mc.player != null && mc.player.getId() == entityId) return ArmProtocol.pulseSpeedFactor(ArmColours.ownPulseSpeed());
			var entity = mc.level.getEntity(entityId);
			return entity == null ? 1F : ArmProtocol.pulseSpeedFactor(ArmColours.pulseSpeedOf(entity.getUUID()));
		} catch (RuntimeException | LinkageError e) {
			return 1F;
		}
	}

	// ------------------------------------------------------------------ the skins

	/** The red skin in {@code rgb}: the shipped one for the default (or if a recolour can't be built). */
	public static Identifier redSkin(int rgb) {
		Identifier[] skins = skins(rgb);
		return skins == null ? MorphWorldRenderer.MORPH_SKIN_RED : skins[0];
	}

	/** The glow skin in {@code rgb}: the shipped one for the default (or if a recolour can't be built). */
	public static Identifier glowSkin(int rgb) {
		Identifier[] skins = skins(rgb);
		return skins == null ? ArmTex.GLOW : skins[1];
	}

	/** The red skin in the colour of the player with this entity id. */
	public static Identifier redSkinOf(int entityId) {
		return redSkin(ofEntity(entityId));
	}

	private static Identifier @Nullable [] skins(int rgb) {
		rgb &= 0xFFFFFF;
		if (rgb == ArmProtocol.COLOUR_DEFAULT) return null;
		Identifier[] skins = SKINS.get(rgb);
		if (skins != null) return skins;
		try {
			Identifier red = build(MorphWorldRenderer.MORPH_SKIN_RED, rgb, "red");
			Identifier glow = build(ArmTex.GLOW, rgb, "glow");
			if (red == null || glow == null) return null;
			skins = new Identifier[] {red, glow};
		} catch (RuntimeException | LinkageError e) {
			if (!failedOnce) {
				failedOnce = true;
				MorphClient.LOGGER.warn("Couldn't recolour the arm skins; arms are drawn in the default red", e);
			}
			return null;
		}
		SKINS.put(rgb, skins);
		if (SKINS.size() > MAX_COLOURS) {
			var oldest = SKINS.entrySet().iterator().next();
			SKINS.remove(oldest.getKey());
			var textures = Minecraft.getInstance().getTextureManager();
			for (Identifier id : oldest.getValue()) textures.release(id);
			ArmTex.forget(oldest.getValue());
		}
		return skins;
	}

	/** Releases every recoloured skin (resources reloaded: they are rebuilt from the new textures on next use). */
	public static void clearSkins() {
		if (SKINS.isEmpty()) return;
		var textures = Minecraft.getInstance().getTextureManager();
		for (Identifier[] skins : SKINS.values()) {
			for (Identifier id : skins) textures.release(id);
			ArmTex.forget(skins);
		}
		SKINS.clear();
	}

	/** {@code source} recoloured into {@code rgb}, registered as a texture; null when the source can't be read. */
	private static @Nullable Identifier build(Identifier source, int rgb, String name) {
		Minecraft mc = Minecraft.getInstance();
		Optional<Resource> resource = mc.getResourceManager().getResource(source);
		if (resource.isEmpty()) return null;
		NativeImage image;
		try (InputStream in = resource.get().open()) {
			image = NativeImage.read(in);
		} catch (IOException e) {
			MorphClient.LOGGER.warn("Couldn't read {} to recolour it: {}", source, e.toString());
			return null;
		}
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) image.setPixel(x, y, recolour(image.getPixel(x, y), rgb));
		}
		Identifier id = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "dynamic/skin_" + name + "_" + String.format(java.util.Locale.ROOT, "%06x", rgb));
		String label = id.toString();
		mc.getTextureManager().register(id, new DynamicTexture(() -> label, image));
		return id;
	}

	// ------------------------------------------------------------------ the recolour (pure)

	private static final float[] DEFAULT_HSV = hsv(ArmProtocol.COLOUR_DEFAULT, new float[3]);

	/**
	 * One texel ({@code argb}, alpha kept) moved from the default red to {@code rgb}: hue turned by the difference,
	 * saturation and brightness scaled by the ratio (each kept to 0..1). The default colour gives the texel back as it was.
	 */
	public static int recolour(int argb, int rgb) {
		rgb &= 0xFFFFFF;
		if (rgb == ArmProtocol.COLOUR_DEFAULT) return argb;
		float[] target = hsv(rgb, new float[3]);
		float[] t = hsv(argb & 0xFFFFFF, new float[3]);
		float hue = t[0] + (target[0] - DEFAULT_HSV[0]);
		float sat = DEFAULT_HSV[1] > 0F ? t[1] * target[1] / DEFAULT_HSV[1] : target[1];
		float val = DEFAULT_HSV[2] > 0F ? t[2] * target[2] / DEFAULT_HSV[2] : target[2];
		return (argb & 0xFF000000) | rgb(hue, clamp01(sat), clamp01(val));
	}

	/** Hue (0..1, a turn), saturation and value of {@code rgb} into {@code out}. */
	static float[] hsv(int rgb, float[] out) {
		float r = ((rgb >> 16) & 0xFF) / 255F, g = ((rgb >> 8) & 0xFF) / 255F, b = (rgb & 0xFF) / 255F;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), span = max - min;
		float h;
		if (span <= 0F) h = 0F;
		else if (max == r) h = ((g - b) / span) / 6F;
		else if (max == g) h = ((b - r) / span + 2F) / 6F;
		else h = ((r - g) / span + 4F) / 6F;
		out[0] = h - (float) Math.floor(h);
		out[1] = max <= 0F ? 0F : span / max;
		out[2] = max;
		return out;
	}

	/** {@code h} (turns, any value), {@code s} and {@code v} (0..1) as 0xRRGGBB. */
	static int rgb(float h, float s, float v) {
		h -= (float) Math.floor(h);
		float sector = h * 6F;
		int i = Math.min(5, (int) sector);
		float f = sector - i, p = v * (1F - s), q = v * (1F - s * f), u = v * (1F - s * (1F - f));
		float r, g, b;
		switch (i) {
			case 0 -> { r = v; g = u; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = u; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = u; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return (Math.round(r * 255F) << 16) | (Math.round(g * 255F) << 8) | Math.round(b * 255F);
	}

	private static float clamp01(float v) {
		return v < 0F ? 0F : Math.min(1F, v);
	}
}
