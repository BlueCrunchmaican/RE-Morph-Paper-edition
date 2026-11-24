package dev.morphreborn.client.arm.render;

import dev.morphreborn.client.ClientConfig;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.render.MorphWorldRenderer;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The arm forms' textures and render types. Every layer is opaque, depth-writing and unculled: the morph skin's
 * cut-out types, never a translucent one (unsorted translucent pieces swap draw order frame to frame and flicker, as
 * MorphWorldRenderer found). Forming, melting and cracks erode instead of fading: with dissolve on, the black, red and
 * glow layers draw with {@code RenderTypes.entityCutoutDissolve}, which drops every pixel whose vertex alpha is below
 * the noise mask's alpha at the same texture spot; geometry ramps its alpha down over the last pixel or so of a front,
 * and the front frays into fizzing noise that re-patterns with the static. With dissolve off, the same textures draw
 * with plain {@code entityCutout} and fronts are cut off hard.
 * <ul>
 *   <li>{@link ArmLayer#BLACK}: morphskin.png; {@link ArmLayer#RED}: morphskin_red.png.</li>
 *   <li>{@link ArmLayer#GLOW}: morphskin_glow.png, a bright red remap of the same noise, drawn at
 *       {@link #FULL_BRIGHT}: it glows by being bright and unshaded, the one route that survives shader packs' solid
 *       hand pass (no emissive or translucent types).</li>
 *   <li>{@link ArmLayer#SENSE}: the outline buffer with the red skin, for veins seen through stone.</li>
 * </ul>
 * The three or four types are shared by every armed player, so all arms on screen batch into as many draws.
 */
public final class ArmTex {
	public static final Identifier SKIN = MorphWorldRenderer.MORPH_SKIN;
	public static final Identifier SKIN_RED = MorphWorldRenderer.MORPH_SKIN_RED;
	/** The bright skin for glowing parts (tools/gen_arm_textures.py). */
	public static final Identifier GLOW = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "textures/skin/morphskin_glow.png");
	/** The dissolve mask: white, its alpha a smooth noise spread evenly over 0.02..0.98 (tools/gen_arm_textures.py). */
	public static final Identifier MASK = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "textures/skin/morphskin_dissolve.png");
	/**
	 * The black static a shade lighter (×1.1 + 3), for Photon only (2026-11-15: Photon draws the arm's blacks
	 * a little too dark; the game without shaders and the other packs are right as they are).
	 */
	public static final Identifier SKIN_PHOTON = Identifier.fromNamespaceAndPath(MorphClient.MOD_ID, "textures/skin/morphskin_photon.png");
	private static final @Nullable RenderType[] PHOTON_TYPES = new RenderType[2];

	/** Light for the glow layer: full block and sky light, so the lightmap leaves it as bright as its texture. */
	public static final int FULL_BRIGHT = 0xF000F0;

	private static final ArmLayer[] LAYERS = ArmLayer.values();
	/** The types by layer and dissolve, looked up once: vanilla's memoised lookups build a key object every call. */
	private static final @Nullable RenderType[] TYPES = new RenderType[LAYERS.length * 2];

	private ArmTex() {}

	/** Recoloured red and glow types, by (colour, layer, dissolve): see {@link ArmTint}. */
	private static final java.util.Map<Long, RenderType> TINTED = new java.util.HashMap<>();

	/**
	 * {@code layer}'s render type, eroding (dissolve) or hard-edged, in the current owner's colour ({@link ArmTint}: the
	 * red and glow layers only; black and the outline are the same for everyone). The outline layer is the same either
	 * way.
	 */
	public static RenderType type(ArmLayer layer, boolean dissolve) {
		if (layer == ArmLayer.BLACK && photon()) {
			int i = dissolve ? 1 : 0;
			RenderType type = PHOTON_TYPES[i];
			if (type == null) {
				PHOTON_TYPES[i] = type = dissolve ? RenderTypes.entityCutoutDissolve(SKIN_PHOTON, MASK) : RenderTypes.entityCutout(SKIN_PHOTON);
			}
			return type;
		}
		int rgb = ArmTint.current();
		if (rgb != dev.morphreborn.client.arm.ArmProtocol.COLOUR_DEFAULT && (layer == ArmLayer.RED || layer == ArmLayer.GLOW)) {
			long key = ((long) rgb << 8) | (layer.ordinal() << 1) | (dissolve ? 1 : 0);
			RenderType type = TINTED.get(key);
			if (type == null) {
				Identifier texture = layer == ArmLayer.RED ? ArmTint.redSkin(rgb) : ArmTint.glowSkin(rgb);
				type = dissolve ? RenderTypes.entityCutoutDissolve(texture, MASK) : RenderTypes.entityCutout(texture);
				TINTED.put(key, type);
			}
			return type;
		}
		int i = layer.ordinal() * 2 + (dissolve ? 1 : 0);
		RenderType type = TYPES[i];
		if (type == null) TYPES[i] = type = create(layer, dissolve);
		return type;
	}

	/**
	 * ArmTint released these recoloured skins: drop the recoloured types (all of them: a handful, rebuilt on next use,
	 * which is simpler than matching each type to its texture).
	 */
	static void forget(Identifier[] skins) {
		TINTED.clear();
	}

	private static RenderType create(ArmLayer layer, boolean dissolve) {
		Identifier texture = switch (layer) {
			case BLACK -> SKIN;
			case RED, SENSE -> SKIN_RED;
			case GLOW -> GLOW;
		};
		if (layer == ArmLayer.SENSE) return RenderTypes.outline(texture);
		return dissolve ? RenderTypes.entityCutoutDissolve(texture, MASK) : RenderTypes.entityCutout(texture);
	}

	// ------------------------------------------------------------------ dissolve setting

	/** Iris's {@code IrisApi} instance and its {@code isShaderPackInUse}, when Iris is installed. */
	private static volatile @Nullable Object iris;
	private static volatile @Nullable Method shaderPackInUse;
	private static volatile boolean irisLooked;

	/**
	 * Whether arm fronts should erode now. ON and OFF are the player's say; AUTO (the default) erodes unless an Iris
	 * shader pack is in use, because packs draw the hand with programs of their own that may not honour the dissolve
	 * mask, and a front that should be eroding would show half see-through there. Hard truncation looks right everywhere.
	 */
	public static boolean dissolveOn() {
		return switch (setting()) {
			case "ON" -> true;
			case "OFF" -> false;
			default -> !shaderPackInUse();
		};
	}

	/** The {@code armDissolve} setting, upper-cased; AUTO when it is missing (a hand-edited settings file). */
	private static String setting() {
		String value = ClientConfig.INSTANCE.armDissolve;
		return value == null ? "AUTO" : value.trim().toUpperCase(java.util.Locale.ROOT);
	}

	private static volatile @Nullable Method packName;
	private static volatile boolean packNameLooked;
	private static long photonCheckedAt;
	private static boolean photonNow;

	/**
	 * True while the shader pack in use is a Photon one (its file name says so), looked up at most every half second:
	 * Iris's {@code Iris.getCurrentPackName}, by reflection.
	 */
	public static boolean photon() {
		long now = System.nanoTime();
		if (now - photonCheckedAt < 500_000_000L && photonCheckedAt != 0L) return photonNow;
		photonCheckedAt = now;
		boolean on = false;
		if (shaderPackInUse()) {
			if (!packNameLooked) {
				try {
					packName = Class.forName("net.irisshaders.iris.Iris", false, ArmTex.class.getClassLoader()).getMethod("getCurrentPackName");
				} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
					packName = null;
				}
				packNameLooked = true;
			}
			Method m = packName;
			if (m != null) {
				try {
					Object name = m.invoke(null);
					on = name != null && name.toString().toLowerCase(java.util.Locale.ROOT).contains("photon");
				} catch (ReflectiveOperationException | RuntimeException e) {
					on = false;
				}
			}
		}
		photonNow = on;
		return on;
	}

	/** True while Iris has a shader pack switched on (Iris isn't needed; everything goes through reflection). */
	private static boolean shaderPackInUse() {
		if (!irisLooked) {
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false, ArmTex.class.getClassLoader());
				iris = api.getMethod("getInstance").invoke(null);
				shaderPackInUse = api.getMethod("isShaderPackInUse");
			} catch (ClassNotFoundException e) {
				// not installed
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				MorphClient.LOGGER.warn("Iris found, but not the API RE:Morph uses ({}); arm fronts erode as without shaders.", e.toString());
				iris = null;
				shaderPackInUse = null;
			}
			irisLooked = true;
		}
		Object api = iris;
		Method inUse = shaderPackInUse;
		if (api == null || inUse == null) return false;
		try {
			return Boolean.TRUE.equals(inUse.invoke(api));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
