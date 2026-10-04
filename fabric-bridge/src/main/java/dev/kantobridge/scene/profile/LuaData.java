package dev.kantobridge.scene.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a Lua file that is only data (docs/specs/PROFILE_PLAN.md §3.2): an optional {@code return}, then one table
 * constructor whose values are {@code nil}, booleans, numbers, strings and nested tables, with comments anywhere. It
 * never runs anything: a name that isn't a field key, an operator other than a number's minus, or a call is an error.
 * Pure Java; the text it reads is a third-party mod's own file, so an error names a line and column, never the text.
 */
public final class LuaData {
	/** The deepest nesting accepted. */
	public static final int MAX_DEPTH = 64;
	/** The longest source accepted, in characters. */
	public static final int MAX_CHARS = 4 * 1024 * 1024;

	/** A table: its positional values (Lua's 1..n) and its keyed ones (a String or Long key), each in source order. */
	public static final class Table {
		private final List<Object> array = new ArrayList<>();
		private final Map<Object, Object> hash = new LinkedHashMap<>();

		/** The positional values, in order. */
		public List<Object> array() {
			return Collections.unmodifiableList(this.array);
		}

		/** The keyed values, in source order (keys are Strings or Longs). */
		public Map<Object, Object> hash() {
			return Collections.unmodifiableMap(this.hash);
		}

		/** A keyed value, or null. */
		public @Nullable Object get(String key) {
			return this.hash.get(key);
		}

		/** The value at integer key {@code key}: a positional one (1-based) or a keyed one. */
		public @Nullable Object get(long key) {
			if (key >= 1 && key <= this.array.size()) {
				return this.array.get((int) key - 1);
			}
			return this.hash.get(key);
		}

		/** A keyed table, or null when absent or not a table. */
		public @Nullable Table table(String key) {
			return this.get(key) instanceof Table t ? t : null;
		}

		/** A keyed integer, or {@code fallback} when absent or not an integer. */
		public long integer(String key, long fallback) {
			return this.get(key) instanceof Long n ? n : fallback;
		}

		/** A keyed number (integer or not), or {@code fallback}. */
		public double number(String key, double fallback) {
			Object value = this.get(key);
			return value instanceof Long n ? n : value instanceof Double d ? d : fallback;
		}

		/** A keyed string, or null. */
		public @Nullable String string(String key) {
			return this.get(key) instanceof String s ? s : null;
		}

		/** A keyed boolean, or {@code fallback}. */
		public boolean bool(String key, boolean fallback) {
			return this.get(key) instanceof Boolean b ? b : fallback;
		}

		/** Every positional integer (others skipped). */
		public List<Long> integers() {
			List<Long> out = new ArrayList<>();
			for (Object value : this.array) {
				if (value instanceof Long n) {
					out.add(n);
				}
			}
			return out;
		}

		/** Every positional string (others skipped). */
		public List<String> strings() {
			List<String> out = new ArrayList<>();
			for (Object value : this.array) {
				if (value instanceof String s) {
					out.add(s);
				}
			}
			return out;
		}
	}

	/** Why a source isn't data: a reason and where. */
	public static final class DataException extends Exception {
		private final int line;
		private final int column;

		DataException(String reason, int line, int column) {
			super(reason + " at " + line + ":" + column);
			this.line = line;
			this.column = column;
		}

		public int line() {
			return this.line;
		}

		public int column() {
			return this.column;
		}
	}

	private final String src;
	private int pos;
	private int depth;

	private LuaData(String src) {
		this.src = src;
	}

	/** The table a data-only Lua source returns. */
	public static Table parse(String source) throws DataException {
		if (source.length() > MAX_CHARS) {
			throw new DataException("source too long", 1, 1);
		}
		LuaData reader = new LuaData(source);
		reader.skip();
		if (reader.word("return")) {
			reader.skip();
		}
		Table root = reader.table();
		reader.skip();
		if (reader.peek() == ';') {
			reader.pos++;
			reader.skip();
		}
		if (reader.pos < reader.src.length()) {
			throw reader.error("text after the table");
		}
		return root;
	}

	// ---- syntax ----

	private Table table() throws DataException {
		if (this.peek() != '{') {
			throw this.error("a table expected");
		}
		if (++this.depth > MAX_DEPTH) {
			throw this.error("nested too deeply");
		}
		this.pos++;
		Table out = new Table();
		while (true) {
			this.skip();
			char c = this.peek();
			if (c == '}') {
				this.pos++;
				break;
			}
			if (c == '[' && !this.longBracketAhead()) {
				this.pos++;
				this.skip();
				Object key = this.value();
				this.skip();
				this.expect(']');
				this.skip();
				this.expect('=');
				this.skip();
				Object value = this.value();
				out.hash.put(this.key(key), value == null ? Nil.NIL : value);
			} else if (isNameStart(c) && this.keyedName()) {
				String name = this.name();
				this.skip();
				this.expect('=');
				this.skip();
				Object value = this.value();
				out.hash.put(name, value == null ? Nil.NIL : value);
			} else {
				Object value = this.value();
				out.array.add(value == null ? Nil.NIL : value);
			}
			this.skip();
			c = this.peek();
			if (c == ',' || c == ';') {
				this.pos++;
			} else if (c != '}') {
				throw this.error("',' or '}' expected");
			}
		}
		this.depth--;
		strip(out);
		return out;
	}

	/** A value: nil, true, false, a number, a string or a table. Null stands for nil. */
	private @Nullable Object value() throws DataException {
		char c = this.peek();
		if (c == '{') {
			return this.table();
		}
		if (c == '"' || c == '\'') {
			return this.quoted(c);
		}
		if (c == '[' && this.longBracketAhead()) {
			return this.longString();
		}
		if (c == '-' || c == '.' || isDigit(c)) {
			return this.number();
		}
		if (isNameStart(c)) {
			int start = this.pos;
			String name = this.name();
			return switch (name) {
				case "nil" -> null;
				case "true" -> Boolean.TRUE;
				case "false" -> Boolean.FALSE;
				default -> throw this.errorAt("not data: a name", start);
			};
		}
		throw this.error(c == 0 ? "unexpected end" : "not data");
	}

	private Object key(@Nullable Object key) throws DataException {
		if (key instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
			return (long) d.doubleValue();
		}
		if (key instanceof String || key instanceof Long || key instanceof Boolean) {
			return key;
		}
		throw this.error(key == null ? "a nil key" : "an unusable key");
	}

	/** Nil values hold their place while reading; they are removed from the hash part (Lua drops them). */
	private static void strip(Table table) {
		table.hash.values().removeIf(v -> v == Nil.NIL);
		for (int i = 0; i < table.array.size(); i++) {
			if (table.array.get(i) == Nil.NIL) {
				table.array.set(i, null);
			}
		}
	}

	private enum Nil {
		NIL
	}

	// ---- tokens ----

	private Object number() throws DataException {
		int start = this.pos;
		boolean negative = false;
		if (this.peek() == '-') {
			negative = true;
			this.pos++;
			this.skip();
		}
		int digits = this.pos;
		if (this.peek() == '0' && (this.peekAt(1) == 'x' || this.peekAt(1) == 'X')) {
			this.pos += 2;
			int hexStart = this.pos;
			while (Character.digit(this.peek(), 16) >= 0) {
				this.pos++;
			}
			if (this.pos == hexStart || this.peek() == '.' || this.peek() == 'p' || this.peek() == 'P') {
				throw this.errorAt("a hexadecimal number this reader doesn't take", start);
			}
			long value = Long.parseUnsignedLong(this.src.substring(hexStart, this.pos), 16);
			return negative ? -value : value;
		}
		boolean real = false;
		while (isDigit(this.peek())) {
			this.pos++;
		}
		if (this.peek() == '.') {
			real = true;
			this.pos++;
			while (isDigit(this.peek())) {
				this.pos++;
			}
		}
		if (this.peek() == 'e' || this.peek() == 'E') {
			real = true;
			this.pos++;
			if (this.peek() == '+' || this.peek() == '-') {
				this.pos++;
			}
			int exp = this.pos;
			while (isDigit(this.peek())) {
				this.pos++;
			}
			if (exp == this.pos) {
				throw this.errorAt("a malformed number", start);
			}
		}
		String text = this.src.substring(digits, this.pos);
		if (text.isEmpty() || text.equals(".") || isNameStart(this.peek())) {
			throw this.errorAt("a malformed number", start);
		}
		if (!real) {
			try {
				long value = Long.parseLong(text);
				return negative ? -value : value;
			} catch (NumberFormatException e) {
				real = true;
			}
		}
		double value = Double.parseDouble(text);
		return negative ? -value : value;
	}

	private String quoted(char quote) throws DataException {
		int start = this.pos;
		this.pos++;
		StringBuilder out = new StringBuilder();
		while (true) {
			if (this.pos >= this.src.length()) {
				throw this.errorAt("an unfinished string", start);
			}
			char c = this.src.charAt(this.pos++);
			if (c == quote) {
				return out.toString();
			}
			if (c == '\n' || c == '\r') {
				throw this.errorAt("an unfinished string", start);
			}
			if (c != '\\') {
				out.append(c);
				continue;
			}
			char e = this.peek();
			this.pos++;
			switch (e) {
				case 'a' -> out.append('\u0007');
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'v' -> out.append('\u000B');
				case '\\', '"', '\'' -> out.append(e);
				case '\n' -> out.append('\n');
				case 'x' -> {
					int hi = Character.digit(this.peek(), 16);
					int lo = Character.digit(this.peekAt(1), 16);
					if (hi < 0 || lo < 0) {
						throw this.error("a malformed escape");
					}
					this.pos += 2;
					out.append((char) (hi * 16 + lo));
				}
				case 'z' -> {
					while (this.pos < this.src.length() && Character.isWhitespace(this.src.charAt(this.pos))) {
						this.pos++;
					}
				}
				default -> {
					if (!isDigit(e)) {
						throw this.error("a malformed escape");
					}
					int value = e - '0';
					for (int k = 0; k < 2 && isDigit(this.peek()); k++) {
						value = value * 10 + (this.src.charAt(this.pos++) - '0');
					}
					if (value > 255) {
						throw this.error("a malformed escape");
					}
					out.append((char) value);
				}
			}
		}
	}

	/** True at {@code [[} or {@code [=*[}. */
	private boolean longBracketAhead() {
		int i = this.pos + 1;
		while (i < this.src.length() && this.src.charAt(i) == '=') {
			i++;
		}
		return i < this.src.length() && this.src.charAt(i) == '[';
	}

	private String longString() throws DataException {
		int start = this.pos;
		int level = this.bracketLevel();
		if (this.peek() == '\r') {
			this.pos++;
		}
		if (this.peek() == '\n') {
			this.pos++;
		}
		String close = "]" + "=".repeat(level) + "]";
		int end = this.src.indexOf(close, this.pos);
		if (end < 0) {
			throw this.errorAt("an unfinished long string", start);
		}
		String out = this.src.substring(this.pos, end);
		this.pos = end + close.length();
		return out;
	}

	/** Consumes {@code [=*[} and returns the number of {@code =}. */
	private int bracketLevel() {
		this.pos++;
		int level = 0;
		while (this.peek() == '=') {
			level++;
			this.pos++;
		}
		this.pos++;
		return level;
	}

	private String name() {
		int start = this.pos;
		while (this.pos < this.src.length() && isNamePart(this.src.charAt(this.pos))) {
			this.pos++;
		}
		return this.src.substring(start, this.pos);
	}

	/** True when the name at the cursor is followed by {@code =} (not {@code ==}): a keyed field. */
	private boolean keyedName() {
		int save = this.pos;
		this.name();
		int after = this.pos;
		this.pos = save;
		int i = after;
		while (i < this.src.length() && Character.isWhitespace(this.src.charAt(i))) {
			i++;
		}
		return i < this.src.length() && this.src.charAt(i) == '=' && (i + 1 >= this.src.length() || this.src.charAt(i + 1) != '=');
	}

	private boolean word(String word) {
		if (this.src.startsWith(word, this.pos) && !isNamePart(this.peekAt(word.length()))) {
			this.pos += word.length();
			return true;
		}
		return false;
	}

	/** Skips whitespace and comments. */
	private void skip() throws DataException {
		while (this.pos < this.src.length()) {
			char c = this.src.charAt(this.pos);
			if (Character.isWhitespace(c)) {
				this.pos++;
			} else if (c == '-' && this.peekAt(1) == '-') {
				int start = this.pos;
				this.pos += 2;
				if (this.peek() == '[' && this.longBracketAhead()) {
					int level = this.bracketLevel();
					String close = "]" + "=".repeat(level) + "]";
					int end = this.src.indexOf(close, this.pos);
					if (end < 0) {
						throw this.errorAt("an unfinished comment", start);
					}
					this.pos = end + close.length();
				} else {
					while (this.pos < this.src.length() && this.src.charAt(this.pos) != '\n') {
						this.pos++;
					}
				}
			} else if (c == '﻿' && this.pos == 0) {
				this.pos++;
			} else {
				return;
			}
		}
	}

	private void expect(char c) throws DataException {
		if (this.peek() != c) {
			throw this.error("'" + c + "' expected");
		}
		this.pos++;
	}

	private char peek() {
		return this.pos < this.src.length() ? this.src.charAt(this.pos) : 0;
	}

	private char peekAt(int ahead) {
		int i = this.pos + ahead;
		return i < this.src.length() ? this.src.charAt(i) : 0;
	}

	private DataException error(String reason) {
		return this.errorAt(reason, this.pos);
	}

	private DataException errorAt(String reason, int at) {
		int line = 1;
		int column = 1;
		for (int i = 0; i < Math.min(at, this.src.length()); i++) {
			if (this.src.charAt(i) == '\n') {
				line++;
				column = 1;
			} else {
				column++;
			}
		}
		return new DataException(reason, line, column);
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isNameStart(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	private static boolean isNamePart(char c) {
		return isNameStart(c) || isDigit(c);
	}
}
