package dev.morphreborn.paper.arm.look;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Whether a player joined from Bedrock, through Floodgate (reflection: nothing here needs Floodgate installed, and
 * nothing is shaded). Without Floodgate nobody is Bedrock, as far as RE:Morph can tell. A Bedrock client can't load a
 * Java resource pack and draws items its own way, so it sees an arm form as the plain tool (and a bow as a bow).
 * <p>
 * Safe to ask from any thread: the answer is cached per player, and the lookup is Floodgate's own thread-safe one.
 */
public final class Bedrock {
	/** The cache is cleared when it grows past this many players (it only ever holds ids that were asked about). */
	private static final int MAX_CACHED = 4096;
	private static final ConcurrentMap<UUID, Boolean> CACHE = new ConcurrentHashMap<>();
	private static volatile @Nullable Object api;
	private static volatile @Nullable Method isFloodgatePlayer;
	private static volatile boolean absent;

	private Bedrock() {}

	public static boolean is(Player p) {
		return is(p.getUniqueId());
	}

	public static boolean is(UUID id) {
		Boolean known = CACHE.get(id);
		if (known != null) return known;
		Method m = isFloodgatePlayer;
		Object floodgate = api;
		if (m == null || floodgate == null) {
			if (!load()) return false; // no Floodgate (yet): nobody is Bedrock, and that isn't remembered
			m = isFloodgatePlayer;
			floodgate = api;
			if (m == null || floodgate == null) return false;
		}
		boolean answer;
		try {
			answer = Boolean.TRUE.equals(m.invoke(floodgate, id));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
		if (CACHE.size() >= MAX_CACHED) CACHE.clear();
		CACHE.put(id, answer);
		return answer;
	}

	/** Forget a player (they left, or Floodgate was reloaded): the next question asks again. */
	public static void forget(UUID id) {
		CACHE.remove(id);
	}

	/** Finds Floodgate's API (once it is there: Floodgate may enable after us, so a miss is retried, though not every time). */
	private static synchronized boolean load() {
		if (isFloodgatePlayer != null) return true;
		long now = System.nanoTime();
		if (absent && now - lastTry < 30_000_000_000L) return false;
		lastTry = now;
		for (ClassLoader loader : loaders()) {
			try {
				Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
				Object instance = type.getMethod("getInstance").invoke(null);
				if (instance == null) continue;
				isFloodgatePlayer = type.getMethod("isFloodgatePlayer", UUID.class);
				api = instance;
				absent = false;
				return true;
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				// not on this loader
			}
		}
		absent = true;
		return false;
	}

	private static long lastTry;

	private static ClassLoader[] loaders() {
		ClassLoader own = Bedrock.class.getClassLoader();
		Plugin floodgate = Bukkit.getServer() == null ? null : Bukkit.getPluginManager().getPlugin("floodgate");
		ClassLoader theirs = floodgate == null ? null : floodgate.getClass().getClassLoader();
		return theirs == null || theirs == own ? new ClassLoader[] {own} : new ClassLoader[] {theirs, own};
	}
}
