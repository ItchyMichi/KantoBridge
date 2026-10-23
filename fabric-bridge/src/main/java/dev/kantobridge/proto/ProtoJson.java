package dev.kantobridge.proto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON for the bridge protocol, built on Gson's tree types.
 *
 * <p>Parsing accepts RFC 8259 only: no NaN/Infinity, comments, single quotes, raw control characters in strings,
 * lone surrogates, duplicate keys or trailing data. Gson's own readers are lenient in several of these (and
 * Minecraft ships Gson 2.10.1, which has no strict mode), so this is a small hand-written parser.
 *
 * <p>Writing is canonical and byte-identical to Python's
 * {@code json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False)}: keys sorted by code point,
 * quote and backslash escaped, control characters as the short escapes (b, f, n, r, t) or six-character u-escapes
 * with lowercase hex, everything else raw UTF-8. The redaction hashes in protocol/README.md depend on this.
 */
public final class ProtoJson {
	/** Deeper nesting is rejected; real messages stay well below it. */
	public static final int MAX_DEPTH = 64;

	private static final Comparator<String> CODE_POINT_ORDER = ProtoJson::compareCodePoints;

	private ProtoJson() {
	}

	/** Parses one complete JSON value. */
	public static JsonElement parse(String text) throws ProtocolException {
		Parser parser = new Parser(text);
		parser.skipWhitespace();
		JsonElement value = parser.readValue(0);
		parser.skipWhitespace();
		if (parser.pos != text.length()) {
			throw new ProtocolException("json.trailing", "trailing data after the JSON value at offset " + parser.pos);
		}
		return value;
	}

	/** Parses one complete JSON value that must be an object. */
	public static JsonObject parseObject(String text) throws ProtocolException {
		JsonElement value = parse(text);
		if (!value.isJsonObject()) {
			throw new ProtocolException("json.not_object", "the top-level JSON value is not an object");
		}
		return value.getAsJsonObject();
	}

	/** Canonical compact JSON (see the class comment). */
	public static String write(JsonElement value) {
		StringBuilder out = new StringBuilder();
		writeValue(value, out);
		return out.toString();
	}

	/** The UTF-8 bytes of {@link #write(JsonElement)}. */
	public static byte[] canonicalUtf8(JsonElement value) {
		return write(value).getBytes(StandardCharsets.UTF_8);
	}

	// ---- writing ----

	private static void writeValue(JsonElement value, StringBuilder out) {
		if (value == null || value.isJsonNull()) {
			out.append("null");
		} else if (value.isJsonObject()) {
			List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(value.getAsJsonObject().entrySet());
			entries.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
			out.append('{');
			for (int i = 0; i < entries.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				writeString(entries.get(i).getKey(), out);
				out.append(':');
				writeValue(entries.get(i).getValue(), out);
			}
			out.append('}');
		} else if (value.isJsonArray()) {
			JsonArray array = value.getAsJsonArray();
			out.append('[');
			for (int i = 0; i < array.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				writeValue(array.get(i), out);
			}
			out.append(']');
		} else {
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				out.append(primitive.getAsBoolean() ? "true" : "false");
			} else if (primitive.isString()) {
				writeString(primitive.getAsString(), out);
			} else {
				out.append(numberText(primitive.getAsNumber()));
			}
		}
	}

	private static String numberText(Number number) {
		if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte
				|| number instanceof BigInteger) {
			return number.toString();
		}
		BigDecimal decimal = number instanceof BigDecimal big ? big : new BigDecimal(number.toString());
		if (decimal.signum() == 0) {
			return "0";
		}
		try {
			// Integral values are written as integers, like the Lua and Python encoders do for protocol numbers.
			return decimal.toBigIntegerExact().toString();
		} catch (ArithmeticException notIntegral) {
			// The protocol carries no fractional numbers; this form is valid JSON but not guaranteed to match Python.
			return decimal.toString();
		}
	}

	/** Appends a string as {@link #write} writes it (for writers that stream a large document in pieces). */
	public static void appendString(String text, StringBuilder out) {
		writeString(text, out);
	}

	/** Appends a value as {@link #write} writes it. */
	public static void append(JsonElement value, StringBuilder out) {
		writeValue(value, out);
	}

	private static void writeString(String text, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
					} else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

	/** Orders strings by Unicode code point, as Python does (UTF-16 order differs above U+FFFF). */
	static int compareCodePoints(String a, String b) {
		int i = 0;
		int j = 0;
		while (i < a.length() && j < b.length()) {
			int ca = a.codePointAt(i);
			int cb = b.codePointAt(j);
			if (ca != cb) {
				return Integer.compare(ca, cb);
			}
			i += Character.charCount(ca);
			j += Character.charCount(cb);
		}
		return Integer.compare(a.length() - i, b.length() - j);
	}

	// ---- parsing ----

	private static final class Parser {
		private final String text;
		private int pos;

		Parser(String text) {
			this.text = text;
		}

		void skipWhitespace() {
			while (this.pos < this.text.length()) {
				char c = this.text.charAt(this.pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					this.pos++;
				} else {
					return;
				}
			}
		}

		JsonElement readValue(int depth) throws ProtocolException {
			if (this.pos >= this.text.length()) {
				throw this.error("json.syntax", "unexpected end of input");
			}
			char c = this.text.charAt(this.pos);
			return switch (c) {
				case '{' -> this.readObject(depth + 1);
				case '[' -> this.readArray(depth + 1);
				case '"' -> new JsonPrimitive(this.readString());
				case 't' -> this.readLiteral("true", new JsonPrimitive(true));
				case 'f' -> this.readLiteral("false", new JsonPrimitive(false));
				case 'n' -> this.readLiteral("null", JsonNull.INSTANCE);
				default -> {
					if (c == '-' || (c >= '0' && c <= '9')) {
						yield this.readNumber();
					}
					throw this.error("json.syntax", "unexpected character");
				}
			};
		}

		private JsonElement readLiteral(String word, JsonElement value) throws ProtocolException {
			if (!this.text.startsWith(word, this.pos)) {
				throw this.error("json.syntax", "invalid literal");
			}
			this.pos += word.length();
			return value;
		}

		private JsonObject readObject(int depth) throws ProtocolException {
			this.checkDepth(depth);
			this.pos++; // '{'
			JsonObject object = new JsonObject();
			this.skipWhitespace();
			if (this.peek() == '}') {
				this.pos++;
				return object;
			}
			while (true) {
				this.skipWhitespace();
				if (this.peek() != '"') {
					throw this.error("json.syntax", "expected a string key");
				}
				String key = this.readString();
				if (object.has(key)) {
					throw this.error("json.duplicate_key", "duplicate object key");
				}
				this.skipWhitespace();
				this.expect(':');
				this.skipWhitespace();
				object.add(key, this.readValue(depth));
				this.skipWhitespace();
				char c = this.peek();
				this.pos++;
				if (c == '}') {
					return object;
				}
				if (c != ',') {
					this.pos--;
					throw this.error("json.syntax", "expected ',' or '}'");
				}
			}
		}

		private JsonArray readArray(int depth) throws ProtocolException {
			this.checkDepth(depth);
			this.pos++; // '['
			JsonArray array = new JsonArray();
			this.skipWhitespace();
			if (this.peek() == ']') {
				this.pos++;
				return array;
			}
			while (true) {
				this.skipWhitespace();
				array.add(this.readValue(depth));
				this.skipWhitespace();
				char c = this.peek();
				this.pos++;
				if (c == ']') {
					return array;
				}
				if (c != ',') {
					this.pos--;
					throw this.error("json.syntax", "expected ',' or ']'");
				}
			}
		}

		private String readString() throws ProtocolException {
			this.pos++; // opening quote
			StringBuilder out = new StringBuilder();
			while (true) {
				if (this.pos >= this.text.length()) {
					throw this.error("json.string", "unterminated string");
				}
				char c = this.text.charAt(this.pos++);
				if (c == '"') {
					return out.toString();
				}
				if (c < 0x20) {
					this.pos--;
					throw this.error("json.string", "raw control character in string");
				}
				if (c == '\\') {
					this.readEscape(out);
				} else if (Character.isHighSurrogate(c)) {
					if (this.pos >= this.text.length() || !Character.isLowSurrogate(this.text.charAt(this.pos))) {
						throw this.error("json.string", "unpaired surrogate");
					}
					out.append(c).append(this.text.charAt(this.pos++));
				} else if (Character.isLowSurrogate(c)) {
					throw this.error("json.string", "unpaired surrogate");
				} else {
					out.append(c);
				}
			}
		}

		private void readEscape(StringBuilder out) throws ProtocolException {
			if (this.pos >= this.text.length()) {
				throw this.error("json.string", "unterminated escape");
			}
			char e = this.text.charAt(this.pos++);
			switch (e) {
				case '"' -> out.append('"');
				case '\\' -> out.append('\\');
				case '/' -> out.append('/');
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'u' -> {
					char unit = this.readHex4();
					if (Character.isHighSurrogate(unit)) {
						if (!this.text.startsWith("\\u", this.pos)) {
							throw this.error("json.string", "unpaired surrogate escape");
						}
						this.pos += 2;
						char low = this.readHex4();
						if (!Character.isLowSurrogate(low)) {
							throw this.error("json.string", "unpaired surrogate escape");
						}
						out.append(unit).append(low);
					} else if (Character.isLowSurrogate(unit)) {
						throw this.error("json.string", "unpaired surrogate escape");
					} else {
						out.append(unit);
					}
				}
				default -> {
					this.pos--;
					throw this.error("json.string", "invalid escape");
				}
			}
		}

		private char readHex4() throws ProtocolException {
			if (this.pos + 4 > this.text.length()) {
				throw this.error("json.string", "short \\u escape");
			}
			int value = 0;
			for (int i = 0; i < 4; i++) {
				int digit = Character.digit(this.text.charAt(this.pos + i), 16);
				if (digit < 0) {
					throw this.error("json.string", "invalid \\u escape");
				}
				value = (value << 4) | digit;
			}
			this.pos += 4;
			return (char) value;
		}

		private JsonPrimitive readNumber() throws ProtocolException {
			int start = this.pos;
			if (this.peek() == '-') {
				this.pos++;
			}
			char first = this.peek();
			if (first == '0') {
				this.pos++;
			} else if (first >= '1' && first <= '9') {
				this.skipDigits();
			} else {
				throw this.error("json.number", "invalid number");
			}
			boolean integral = true;
			if (this.peek() == '.') {
				integral = false;
				this.pos++;
				if (!isDigit(this.peek())) {
					throw this.error("json.number", "invalid number");
				}
				this.skipDigits();
			}
			char e = this.peek();
			if (e == 'e' || e == 'E') {
				integral = false;
				this.pos++;
				char sign = this.peek();
				if (sign == '+' || sign == '-') {
					this.pos++;
				}
				if (!isDigit(this.peek())) {
					throw this.error("json.number", "invalid number");
				}
				this.skipDigits();
			}
			String literal = this.text.substring(start, this.pos);
			if (integral) {
				BigInteger big = new BigInteger(literal);
				return big.bitLength() < 64 ? new JsonPrimitive(big.longValue()) : new JsonPrimitive(big);
			}
			return new JsonPrimitive(new BigDecimal(literal));
		}

		private void skipDigits() {
			while (isDigit(this.peek())) {
				this.pos++;
			}
		}

		private static boolean isDigit(char c) {
			return c >= '0' && c <= '9';
		}

		private char peek() {
			return this.pos < this.text.length() ? this.text.charAt(this.pos) : '\0';
		}

		private void expect(char c) throws ProtocolException {
			if (this.peek() != c) {
				throw this.error("json.syntax", "expected '" + c + "'");
			}
			this.pos++;
		}

		private void checkDepth(int depth) throws ProtocolException {
			if (depth > MAX_DEPTH) {
				throw this.error("json.depth", "nesting deeper than " + MAX_DEPTH);
			}
		}

		private ProtocolException error(String code, String what) {
			return new ProtocolException(code, what + " at offset " + this.pos);
		}
	}
}
