package dev.morphreborn.client;

/**
 * Semantic Versioning order (semver.org): MAJOR.MINOR.PATCH, a pre-release ({@code -beta.1}) sorts before its release,
 * build metadata ({@code +26.3}) is ignored. A leading "v" (GitHub tags) is allowed. The plugin has the same class.
 */
public final class SemVer {
	private SemVer() {}

	/** {@code v6.6.0+26.3} -> {@code 6.6.0}: the version without a tag prefix or build metadata. */
	public static String clean(String version) {
		String v = version.trim();
		if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
		int plus = v.indexOf('+');
		return plus < 0 ? v : v.substring(0, plus);
	}

	/** Negative when {@code a} is older than {@code b}, positive when newer, 0 when the same release. */
	public static int compare(String a, String b) {
		String x = clean(a), y = clean(b);
		int dx = x.indexOf('-'), dy = y.indexOf('-');
		String[] core1 = (dx < 0 ? x : x.substring(0, dx)).split("\\.");
		String[] core2 = (dy < 0 ? y : y.substring(0, dy)).split("\\.");
		for (int i = 0; i < 3; i++) {
			int c = Long.compare(number(core1, i), number(core2, i));
			if (c != 0) return c;
		}
		if (dx < 0 || dy < 0) return Boolean.compare(dx < 0, dy < 0); // a release is newer than its pre-releases
		String[] pre1 = x.substring(dx + 1).split("\\.");
		String[] pre2 = y.substring(dy + 1).split("\\.");
		for (int i = 0; i < Math.min(pre1.length, pre2.length); i++) {
			boolean n1 = pre1[i].matches("\\d+"), n2 = pre2[i].matches("\\d+");
			int c = n1 && n2 ? Long.compare(Long.parseLong(pre1[i]), Long.parseLong(pre2[i]))
					: n1 != n2 ? (n1 ? -1 : 1) : pre1[i].compareTo(pre2[i]);
			if (c != 0) return c;
		}
		return Integer.compare(pre1.length, pre2.length);
	}

	private static long number(String[] parts, int i) {
		if (i >= parts.length) return 0;
		try {
			return Long.parseLong(parts[i].replaceAll("\\D.*", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
