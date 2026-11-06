package dev.morphreborn.paper.nbt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Minimal SNBT reader/writer so variant data can be filtered without touching server internals.
 * <p>
 * Values map to: compound = {@code Map<String,Object>}, list = {@code List<Object>}, numbers keep their
 * boxed type (Byte/Short/Integer/Long/Float/Double), strings are String, typed arrays are byte[]/int[]/long[].
 * {@link #write} emits keys sorted, so equal data always produces an identical string (used as variant identity).
 */
public final class Snbt {
	private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9._+\\-]+");

	private Snbt() {}

	public static Map<String, Object> parseCompound(String input) {
		Reader reader = new Reader(input);
		reader.skipWs();
		Object value = reader.readValue();
		reader.skipWs();
		if (!reader.atEnd()) {
			throw reader.error("trailing data");
		}
		if (!(value instanceof Map<?, ?>)) {
			throw reader.error("expected a compound");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> compound = (Map<String, Object>) value;
		return compound;
	}

	public static String write(Object value) {
		StringBuilder out = new StringBuilder();
		writeValue(out, value);
		return out.toString();
	}

	private static void writeValue(StringBuilder out, Object value) {
		switch (value) {
			case Map<?, ?> map -> {
				out.append('{');
				Map<String, Object> sorted = new TreeMap<>();
				map.forEach((k, v) -> sorted.put((String) k, v));
				Iterator<Map.Entry<String, Object>> it = sorted.entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<String, Object> e = it.next();
					writeKey(out, e.getKey());
					out.append(':');
					writeValue(out, e.getValue());
					if (it.hasNext()) out.append(',');
				}
				out.append('}');
			}
			case List<?> list -> {
				out.append('[');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) out.append(',');
					writeValue(out, list.get(i));
				}
				out.append(']');
			}
			case String s -> writeString(out, s);
			case Byte b -> out.append(b).append('b');
			case Short s -> out.append(s).append('s');
			case Integer i -> out.append(i);
			case Long l -> out.append(l).append('L');
			case Float f -> out.append(f).append('f');
			case Double d -> out.append(d).append('d');
			case Boolean b -> out.append(b ? "1b" : "0b");
			case byte[] arr -> {
				out.append("[B;");
				for (int i = 0; i < arr.length; i++) {
					if (i > 0) out.append(',');
					out.append(arr[i]).append('b');
				}
				out.append(']');
			}
			case int[] arr -> {
				out.append("[I;");
				for (int i = 0; i < arr.length; i++) {
					if (i > 0) out.append(',');
					out.append(arr[i]);
				}
				out.append(']');
			}
			case long[] arr -> {
				out.append("[L;");
				for (int i = 0; i < arr.length; i++) {
					if (i > 0) out.append(',');
					out.append(arr[i]).append('L');
				}
				out.append(']');
			}
			default -> throw new IllegalArgumentException("Unsupported SNBT value " + value.getClass());
		}
	}

	private static void writeKey(StringBuilder out, String key) {
		if (BARE_KEY.matcher(key).matches()) {
			out.append(key);
		} else {
			writeString(out, key);
		}
	}

	private static void writeString(StringBuilder out, String s) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\t' -> out.append("\\t");
				case '\r' -> out.append("\\r");
				default -> out.append(c);
			}
		}
		out.append('"');
	}

	private static final class Reader {
		private final String s;
		private int pos;

		Reader(String s) {
			this.s = s;
		}

		boolean atEnd() {
			return pos >= s.length();
		}

		IllegalArgumentException error(String message) {
			return new IllegalArgumentException("SNBT " + message + " at " + pos + ": " + s.substring(Math.max(0, pos - 20), Math.min(s.length(), pos + 20)));
		}

		void skipWs() {
			while (!atEnd() && Character.isWhitespace(s.charAt(pos))) pos++;
		}

		char peek() {
			if (atEnd()) throw error("unexpected end");
			return s.charAt(pos);
		}

		void expect(char c) {
			skipWs();
			if (peek() != c) throw error("expected '" + c + "'");
			pos++;
		}

		Object readValue() {
			skipWs();
			char c = peek();
			if (c == '{') return readCompound();
			if (c == '[') return readListOrArray();
			if (c == '"' || c == '\'') return readQuoted();
			return parseBare(readBare());
		}

		Map<String, Object> readCompound() {
			expect('{');
			Map<String, Object> map = new LinkedHashMap<>();
			skipWs();
			if (peek() == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWs();
				String key = (peek() == '"' || peek() == '\'') ? readQuoted() : readBare();
				if (key.isEmpty()) throw error("empty key");
				expect(':');
				map.put(key, readValue());
				skipWs();
				char c = peek();
				pos++;
				if (c == '}') return map;
				if (c != ',') throw error("expected ',' or '}'");
			}
		}

		Object readListOrArray() {
			expect('[');
			skipWs();
			if (pos + 1 < s.length() && s.charAt(pos + 1) == ';') {
				char type = Character.toUpperCase(s.charAt(pos));
				pos += 2;
				List<Object> raw = readElements();
				switch (type) {
					case 'B' -> {
						byte[] arr = new byte[raw.size()];
						for (int i = 0; i < arr.length; i++) arr[i] = ((Number) raw.get(i)).byteValue();
						return arr;
					}
					case 'I' -> {
						int[] arr = new int[raw.size()];
						for (int i = 0; i < arr.length; i++) arr[i] = ((Number) raw.get(i)).intValue();
						return arr;
					}
					case 'L' -> {
						long[] arr = new long[raw.size()];
						for (int i = 0; i < arr.length; i++) arr[i] = ((Number) raw.get(i)).longValue();
						return arr;
					}
					default -> throw error("unknown array type " + type);
				}
			}
			return readElements();
		}

		private List<Object> readElements() {
			List<Object> list = new ArrayList<>();
			skipWs();
			if (peek() == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(readValue());
				skipWs();
				char c = peek();
				pos++;
				if (c == ']') return list;
				if (c != ',') throw error("expected ',' or ']'");
			}
		}

		String readQuoted() {
			char quote = peek();
			pos++;
			StringBuilder out = new StringBuilder();
			while (true) {
				char c = peek();
				pos++;
				if (c == quote) return out.toString();
				if (c == '\\') {
					char n = peek();
					pos++;
					switch (n) {
						case 'n' -> out.append('\n');
						case 't' -> out.append('\t');
						case 'r' -> out.append('\r');
						case 'b' -> out.append('\b');
						case 'f' -> out.append('\f');
						case 's' -> out.append(' ');
						case 'u' -> {
							out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
							pos += 4;
						}
						default -> out.append(n);
					}
				} else {
					out.append(c);
				}
			}
		}

		String readBare() {
			skipWs();
			int start = pos;
			while (!atEnd()) {
				char c = s.charAt(pos);
				if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+') {
					pos++;
				} else {
					break;
				}
			}
			return s.substring(start, pos);
		}

		Object parseBare(String token) {
			if (token.isEmpty()) throw error("empty value");
			if (token.equals("true")) return (byte) 1;
			if (token.equals("false")) return (byte) 0;
			String digits = token.replace("_", "");
			char last = Character.toLowerCase(digits.charAt(digits.length() - 1));
			try {
				if (digits.matches("[-+]?\\d+[bB]")) return Byte.parseByte(strip(digits));
				if (digits.matches("[-+]?\\d+[sS]")) return Short.parseShort(strip(digits));
				if (digits.matches("[-+]?\\d+[lL]")) return Long.parseLong(strip(digits));
				if (digits.matches("[-+]?\\d+")) return Integer.parseInt(digits);
				if (digits.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fF]")) return Float.parseFloat(strip(digits));
				if (digits.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[dD]?")) {
					return Double.parseDouble(last == 'd' ? strip(digits) : digits);
				}
			} catch (NumberFormatException ignored) {
				// fall through: treat as a string, the same way vanilla treats unparseable bare words
			}
			return token;
		}

		private static String strip(String token) {
			return token.substring(0, token.length() - 1);
		}
	}
}
