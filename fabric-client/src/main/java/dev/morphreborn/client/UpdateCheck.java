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
 * Once per game start (Update Check setting, on by default) the mod asks GitHub for RE:Morph's latest release. When it
 * is newer than this mod, the first RE:Morph server joined afterwards gets a chat line with short Modrinth and GitHub links. The
 * request is the public "latest release" page and nothing else; pre-releases don't count.
 */
public final class UpdateCheck {
	public static final String LATEST_API = "https://api.github.com/repos/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest";
	/** Where players are sent to download: Modrinth's project page and GitHub's latest release. */
	public static final String MODRINTH_PAGE = "https://modrinth.com/plugin/re-morph";
	public static final String GITHUB_PAGE = "https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest";
	private static volatile @Nullable String latest;
	private static boolean told;

	private UpdateCheck() {}

	public static void start() {
		if (!ClientConfig.INSTANCE.updateCheck) return;
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API)).timeout(Duration.ofSeconds(15))
					.header("Accept", "application/vnd.github+json").header("User-Agent", "RE-Morph-update-check").GET().build();
			http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
				if (response.statusCode() != 200) return;
				JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
				latest = SemVer.clean(release.get("tag_name").getAsString());
			}).exceptionally(e -> {
				MorphClient.LOGGER.debug("RE:Morph update check failed: {}", e.toString());
				return null;
			});
		} catch (RuntimeException e) {
			MorphClient.LOGGER.debug("RE:Morph update check failed: {}", e.toString());
		}
	}

	/** This mod's version, e.g. {@code 6.6.0} (without the {@code +26.3} build metadata). */
	public static String ownVersion() {
		return SemVer.clean(FabricLoader.getInstance().getModContainer(MorphClient.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0"));
	}

	/**
	 * A RE:Morph server accepted this client. {@code serverVersion} is the plugin's version ("" from servers before
	 * 6.6.0). A server newer than this mod already tells the player to update, so the line isn't repeated then.
	 */
	public static void onJoin(String serverVersion) {
		String newest = latest;
		if (told || newest == null || !ClientConfig.INSTANCE.updateCheck) return;
		String own = ownVersion();
		if (SemVer.compare(newest, own) <= 0) return;
		if (!serverVersion.isEmpty() && SemVer.compare(serverVersion, own) > 0) return;
		told = true;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player == null) return;
			mc.player.sendSystemMessage(Component.translatable("morphreborn.update.available", newest, own).withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(" ")).append(link("Modrinth", MODRINTH_PAGE)).append(Component.literal(" ")).append(link("GitHub", GITHUB_PAGE)));
		});
	}

	/** A short clickable word for a long address: chat shows the word, the hover and the click have the address. */
	private static Component link(String label, String url) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))).withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
	}
}
