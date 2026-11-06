package dev.morphreborn.client.compat;

import dev.morphreborn.client.MorphClient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.jspecify.annotations.Nullable;

/**
 * Optional support for tr7zw's PaperDoll mod (not required, not bundled, none of its code used).
 *
 * <p>PaperDoll builds its doll's render state by calling the player renderer's {@code createRenderState} directly, which
 * skips the dispatcher hook RE:Morph uses to swap a morphed player for their morph, so on its own it shows a plain
 * player. While it draws, it sets its public flag {@code PaperDollShared.isExtractingPaperDoll}; the
 * {@code EntityRendererMixin} reads that flag through this class and hands PaperDoll the morph (or the transformation)
 * instead. If PaperDoll isn't installed, or a future version renames the flag, this simply stays off.
 */
public final class PaperDollCompat {
	private static final @Nullable Field FLAG;
	private static final @Nullable Field INSTANCE;

	static {
		Field flag = null, instance = null;
		try {
			Class<?> shared = Class.forName("dev.tr7zw.paperdoll.PaperDollShared");
			flag = shared.getField("isExtractingPaperDoll");
			if (!Modifier.isStatic(flag.getModifiers())) instance = shared.getField("instance");
			MorphClient.LOGGER.info("PaperDoll found: its doll will show morphs.");
		} catch (ClassNotFoundException e) {
			// not installed
		} catch (ReflectiveOperationException | LinkageError e) {
			MorphClient.LOGGER.warn("PaperDoll found, but not the flag RE:Morph looks for ({}); its doll will show plain players.", e.toString());
			flag = null;
			instance = null;
		}
		FLAG = flag;
		INSTANCE = instance;
	}

	private PaperDollCompat() {}

	/** True while PaperDoll is building its doll. */
	public static boolean drawing() {
		if (FLAG == null) return false;
		try {
			Object owner = INSTANCE == null ? null : INSTANCE.get(null);
			if (INSTANCE != null && owner == null) return false;
			return FLAG.getBoolean(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
