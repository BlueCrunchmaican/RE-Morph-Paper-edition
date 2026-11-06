package dev.morphreborn.paper.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One JSON file per player under {@code plugins/RE-Morph/players}. Writes go through a single background
 * thread, are written to a temp file first and moved into place, so a crash mid-write never loses a collection.
 * A corrupt file is kept aside as {@code <uuid>.json.corrupt-<time>} instead of being overwritten.
 */
public final class DataStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path dir;
	private final Logger logger;
	private final Map<UUID, PlayerMorphData> cache = new ConcurrentHashMap<>();
	private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "RE-Morph-DataStore");
		t.setDaemon(true);
		return t;
	});

	public DataStore(Path dir, Logger logger) throws IOException {
		this.dir = dir;
		this.logger = logger;
		Files.createDirectories(dir);
	}

	/** Returns the cached collection, loading it from disk on first use. */
	public PlayerMorphData get(UUID id) {
		return cache.computeIfAbsent(id, this::load);
	}

	public boolean isCached(UUID id) {
		return cache.containsKey(id);
	}

	public void markDirty(UUID id) {
		dirty.add(id);
	}

	/** Queue writes for everything changed since the last flush. Safe to call every few seconds. */
	public void flushDirtyAsync() {
		for (UUID id : Set.copyOf(dirty)) {
			dirty.remove(id);
			PlayerMorphData data = cache.get(id);
			if (data == null) continue;
			String json = GSON.toJson(data); // serialise on the caller (main) thread, write on the worker
			writer.execute(() -> write(id, json));
		}
	}

	/** Save and drop from cache, e.g. after the player has quit. */
	public void unload(UUID id) {
		PlayerMorphData data = cache.remove(id);
		dirty.remove(id);
		if (data != null) {
			String json = GSON.toJson(data);
			writer.execute(() -> write(id, json));
		}
	}

	public void shutdown() {
		for (Map.Entry<UUID, PlayerMorphData> e : cache.entrySet()) {
			String json = GSON.toJson(e.getValue());
			UUID id = e.getKey();
			writer.execute(() -> write(id, json));
		}
		writer.shutdown();
		try {
			if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
				logger.severe("Timed out saving morph data; some changes may be lost.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Path file(UUID id) {
		return dir.resolve(id + ".json");
	}

	private PlayerMorphData load(UUID id) {
		Path file = file(id);
		if (!Files.exists(file)) {
			return new PlayerMorphData(id);
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			PlayerMorphData data = GSON.fromJson(r, PlayerMorphData.class);
			if (data == null) throw new JsonParseException("empty file");
			data.owner = id;
			if (data.morphs == null) data.morphs = new java.util.LinkedHashMap<>();
			if (data.killCounts == null) data.killCounts = new java.util.HashMap<>();
			data.morphs.values().forEach(list -> list.removeIf(v -> v == null || v.id == null || v.snbt == null));
			return data;
		} catch (IOException | JsonParseException | IllegalStateException e) {
			Path aside = dir.resolve(id + ".json.corrupt-" + System.currentTimeMillis());
			logger.log(Level.SEVERE, "Morph data for " + id + " is unreadable; keeping it as " + aside.getFileName() + " and starting empty.", e);
			try {
				Files.move(file, aside);
			} catch (IOException moveError) {
				logger.log(Level.SEVERE, "Could not move corrupt file aside", moveError);
			}
			return new PlayerMorphData(id);
		}
	}

	private void write(UUID id, String json) {
		Path target = file(id);
		Path tmp = dir.resolve(id + ".json.tmp");
		try {
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(json);
			}
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Failed to save morph data for " + id, e);
			dirty.add(id);
		}
	}
}
