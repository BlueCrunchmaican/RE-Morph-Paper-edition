package dev.morphreborn.paper.morph;

import dev.morphreborn.paper.data.MorphRef;
import dev.morphreborn.paper.nbt.Snbt;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Builds never-spawned entity instances from variant data. They answer "how big is this morph", "what are its
 * attributes" and give Lib's Disguises a real data watcher to copy, so every variant renders correctly for
 * vanilla clients without per-mob code (the server-side twin of the original's {@code createEntityInstance}).
 */
public final class MorphTemplates {
	private static final int MAX_CACHED = 256;

	private final Logger logger;
	private final Map<String, LivingEntity> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, LivingEntity> eldest) {
			return size() > MAX_CACHED;
		}
	};

	public MorphTemplates(Logger logger) {
		this.logger = logger;
	}

	/** @return an unspawned entity for a mob morph, or null for players / unknown types. */
	public @Nullable LivingEntity get(MorphRef ref, World world) {
		if (ref.isPlayer()) return null;
		String key = world.getUID() + "|" + ref.entityType() + "|" + ref.snbt();
		LivingEntity cached = cache.get(key);
		if (cached != null) return cached;
		LivingEntity created = create(ref, world);
		if (created != null) cache.put(key, created);
		return created;
	}

	public void clear() {
		cache.clear();
	}

	private @Nullable LivingEntity create(MorphRef ref, World world) {
		try {
			Map<String, Object> tag = ref.snbt().isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(Snbt.parseCompound(ref.snbt()));
			tag.put("id", ref.entityType());
			// No NoAI: the template is never spawned, and Lib's Disguises copies the flag to vanilla viewers, whose
			// client then freezes the ender dragon's wings and the copper golem's animations.
			tag.put("Silent", (byte) 1);
			tag.put("PersistenceRequired", (byte) 1);
			EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(Snbt.write(tag));
			Entity entity = snapshot.createEntity(world);
			if (entity instanceof LivingEntity living) return living;
			logger.warning("Morph type " + ref.entityType() + " is not a living entity; it cannot be used.");
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "Could not build morph " + ref.entityType() + " " + ref.snbt(), e);
		}
		return null;
	}
}
