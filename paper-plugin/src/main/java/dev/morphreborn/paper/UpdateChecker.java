package dev.morphreborn.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

/**
 * Asks GitHub for RE:Morph's latest release at startup and every 12 hours (update-check in config.yml). When it's newer
 * than this plugin the console says so once per version, and admins (morphreborn.admin) are told when they join. The
 * request is the public "latest release" page and nothing else; pre-releases don't count.
 */
public final class UpdateChecker implements Listener {
	private static final String LATEST_API = "https://api.github.com/repos/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest";
	/** Where admins are sent to download: Modrinth's project page and GitHub's latest release. */
	public static final String MODRINTH_PAGE = "https://modrinth.com/plugin/re-morph";
	public static final String GITHUB_PAGE = "https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest";
	private static final long EVERY_TICKS = 20L * 60 * 60 * 12;

	private final MorphRebornPlugin plugin;
	private volatile @Nullable String newer;
	private @Nullable String logged;

	public UpdateChecker(MorphRebornPlugin plugin) {
		this.plugin = plugin;
	}

	public void start() {
		Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L * 10, EVERY_TICKS);
	}

	/** This plugin's version, e.g. {@code 6.6.0}. */
	public String version() {
		return plugin.getPluginMeta().getVersion();
	}

	private void check() {
		if (!plugin.settings().updateCheck) return;
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API)).timeout(Duration.ofSeconds(15))
					.header("Accept", "application/vnd.github+json").header("User-Agent", "RE-Morph-update-check").GET().build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return;
			JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
			String latest = SemVer.clean(release.get("tag_name").getAsString());
			if (SemVer.compare(latest, version()) <= 0) {
				newer = null;
				return;
			}
			newer = latest;
			if (!latest.equals(logged)) {
				logged = latest;
				plugin.getLogger().warning("RE:Morph " + latest + " is out (this server runs " + version() + "): " + MODRINTH_PAGE + " or " + GITHUB_PAGE);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			plugin.debug("Update check failed: " + e);
		}
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		String latest = newer;
		if (latest == null || !plugin.settings().updateCheck || !player.hasPermission("morphreborn.admin")) return;
		player.sendMessage(Component.text("RE:Morph " + latest + " is out; this server's plugin is " + SemVer.clean(version()) + ". ", NamedTextColor.YELLOW)
				.append(link("Modrinth", MODRINTH_PAGE)).append(Component.text(" ")).append(link("GitHub", GITHUB_PAGE)));
	}

	/** A short clickable word for a long address: chat shows the word, the hover and the click have the address. */
	private static Component link(String label, String url) {
		return Component.text("[" + label + "]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url)).hoverEvent(Component.text(url));
	}
}
