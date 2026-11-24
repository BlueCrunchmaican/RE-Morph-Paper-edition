package dev.morphreborn.paper.arm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The arm rules stay pure: nothing under arm/rules may use the server's types (Bukkit, Paper, Adventure, Minecraft) or
 * the plugin's other packages, which use them. That is what lets every rule be tested without a server, and what keeps
 * a rule from quietly depending on server state.
 */
class RulesIsolationTest {
	private static final Path RULES = Path.of("src/main/java/dev/morphreborn/paper/arm/rules");
	private static final List<String> FORBIDDEN = List.of("org.bukkit", "io.papermc", "com.destroystokyo", "net.kyori", "net.minecraft", "net.md_5",
			"com.github.retrooper", "me.libraryaddict");

	@Test
	void noServerTypesInRules() throws IOException {
		assertTrue(Files.isDirectory(RULES), "run from the paper-plugin folder (Gradle does)");
		List<String> problems = new ArrayList<>();
		int files = 0;
		try (Stream<Path> paths = Files.walk(RULES)) {
			for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
				files++;
				String source = Files.readString(file, StandardCharsets.UTF_8);
				for (String line : source.split("\n")) {
					String l = line.trim();
					if (!l.startsWith("import ")) continue;
					String name = l.substring("import ".length()).replace("static ", "").trim();
					for (String f : FORBIDDEN) {
						if (name.startsWith(f + ".")) problems.add(file.getFileName() + ": " + l);
					}
					// the typed description pieces (paper.tips) are as server-free as the rules and are shared with the menus
					if (name.startsWith("dev.morphreborn.") && !name.startsWith("dev.morphreborn.paper.arm.rules.") && !name.startsWith("dev.morphreborn.paper.tips.")) {
						problems.add(file.getFileName() + ": " + l);
					}
				}
				String code = stripComments(source);
				for (String f : FORBIDDEN) {
					if (code.contains(f + ".")) problems.add(file.getFileName() + ": a fully qualified " + f + " name");
				}
			}
		}
		assertTrue(files >= 15, "found the rules (" + files + " files)");
		assertTrue(problems.isEmpty(), "server types in arm/rules: " + problems);
	}

	/** The code without comments and string contents, so prose that names a package doesn't count. */
	private static String stripComments(String s) {
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
				while (i < s.length() && s.charAt(i) != '\n') i++;
			} else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
				int end = s.indexOf("*/", i + 2);
				i = end < 0 ? s.length() : end + 2;
			} else if (c == '"') {
				i++;
				while (i < s.length() && s.charAt(i) != '"') i += s.charAt(i) == '\\' ? 2 : 1;
				i++;
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}
}
