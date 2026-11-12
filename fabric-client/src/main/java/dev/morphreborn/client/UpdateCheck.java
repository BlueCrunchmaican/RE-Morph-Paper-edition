package dev.morphreborn.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.jspecify.annotations.Nullable;

/**
 * Version notices in chat, each time the player joins a server or a single-player world (Update Check setting, on by
 * default):
 * <ul>
 * <li>a newer RE:Morph release is out (GitHub's public "latest release" page, asked at game start and again when the
 * answer is 6 hours old; pre-releases don't count). Shown on any server, with RE:Morph or without, except one whose
 * plugin is newer than this mod: that server already tells the player to update (its old-client message);</li>
 * <li>the server's RE:Morph is older than this mod. An older server can't know about this mod's version, so only the
 * mod can say it.</li>
 * </ul>
 * Everything runs on the client thread except the GitHub request itself.
 */
public final class UpdateCheck {
	public static final String LATEST_API = "https://api.github.com/repos/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest";
	/** Where players are sent to download: Modrinth's project page and GitHub's latest release. */
	public static final String MODRINTH_PAGE = "https://modrinth.com/plugin/re-morph";
	public static final String GITHUB_PAGE = "https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest";
	/**
	 * GitHub is asked again on a join once its answer is this old (6 hours), or this long after a failed ask (10
	 * minutes). The system properties exist for testing: {@code -Dmorphreborn.updateCheck.refreshSeconds=60}.
	 */
	private static final long REFRESH_MILLIS = Long.getLong("morphreborn.updateCheck.refreshSeconds", 6L * 60 * 60) * 1000,
			RETRY_MILLIS = Long.getLong("morphreborn.updateCheck.retrySeconds", 10L * 60) * 1000;
	private static volatile @Nullable String latest;
	private static volatile boolean asking;
	private static volatile long askedAt;
	/** This connection: whether it's known yet if the server runs RE:Morph, whether that server already told the player
	 * to update, and whether the release line was shown. */
	private static boolean decided, serverSaidUpdate, releaseShown;

	private UpdateCheck() {}

	/** At game start. */
	public static void start() {
		ask();
	}

	private static void ask() {
		if (!ClientConfig.INSTANCE.updateCheck || asking) return;
		asking = true;
		askedAt = System.currentTimeMillis();
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API)).timeout(Duration.ofSeconds(15))
					.header("Accept", "application/vnd.github+json").header("User-Agent", "RE-Morph-update-check").GET().build();
			http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
				if (response.statusCode() != 200) throw new IllegalStateException("GitHub answered " + response.statusCode());
				JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
				latest = SemVer.clean(release.get("tag_name").getAsString());
				MorphClient.LOGGER.info("RE:Morph update check: the latest release is {} (this mod: {}).", latest, ownVersion());
				Minecraft.getInstance().execute(UpdateCheck::showRelease); // the answer came after the join
			}).whenComplete((ignored, e) -> {
				asking = false;
				if (e != null) failed(e);
			});
		} catch (RuntimeException e) {
			asking = false;
			failed(e);
		}
	}

	private static void failed(Throwable e) {
		MorphClient.LOGGER.info("RE:Morph update check failed ({}); asking again on a join after {} s.", e.toString(), RETRY_MILLIS / 1000);
	}

	/** This mod's version, e.g. {@code 6.6.0} (without the {@code +26.3} build metadata). */
	public static String ownVersion() {
		return SemVer.clean(FabricLoader.getInstance().getModContainer(MorphClient.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0"));
	}

	/** Joined a server or a single-player world: nothing is known about it yet. Asks GitHub again if its answer is stale. */
	public static void onConnect() {
		decided = false;
		serverSaidUpdate = false;
		releaseShown = false;
		if (System.currentTimeMillis() - askedAt > (latest == null ? RETRY_MILLIS : REFRESH_MILLIS)) ask();
	}

	public static void onDisconnect() {
		decided = false;
	}

	/**
	 * The server answered as a RE:Morph server. {@code serverVersion} is its plugin's version ("" from servers before
	 * 6.6.0); {@code compatible} is false when it speaks another protocol. A repeated hello (the gameplay mode changed)
	 * says nothing new.
	 */
	public static void onRemorphServer(String serverVersion, boolean compatible) {
		if (decided) return;
		decided = true;
		if (!ClientConfig.INSTANCE.updateCheck) return;
		String own = ownVersion();
		int age = serverVersion.isEmpty() ? -1 : SemVer.compare(serverVersion, own);
		serverSaidUpdate = age > 0 || !compatible; // the server's old-client or outdated message
		if (age < 0 && (compatible || !serverVersion.isEmpty())) {
			String server = SemVer.clean(serverVersion);
			Component line = !compatible ? Component.translatable("morphreborn.update.serverIncompatible", server, own)
					: serverVersion.isEmpty() ? Component.translatable("morphreborn.update.serverOlderUnknown", own)
					: Component.translatable("morphreborn.update.serverOlder", server, own);
			tell(line.copy().withStyle(ChatFormatting.GRAY));
		}
		showRelease();
	}

	/** The server doesn't run RE:Morph (or this is a single-player world). */
	public static void onOtherServer() {
		if (decided) return;
		decided = true;
		showRelease();
	}

	private static void showRelease() {
		String newest = latest;
		if (!decided || releaseShown || serverSaidUpdate || newest == null || !ClientConfig.INSTANCE.updateCheck) return;
		String own = ownVersion();
		if (SemVer.compare(newest, own) <= 0) return;
		releaseShown = tell(Component.translatable("morphreborn.update.available", newest, own).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" ")).append(link("Modrinth", MODRINTH_PAGE)).append(Component.literal(" ")).append(link("GitHub", GITHUB_PAGE)));
	}

	private static boolean tell(Component line) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		mc.player.sendSystemMessage(line);
		return true;
	}

	/** A short clickable word for a long address: chat shows the word, the hover and the click have the address. */
	private static Component link(String label, String url) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))).withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
	}
}
