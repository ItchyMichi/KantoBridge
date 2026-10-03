package dev.kantobridge.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The protocol's text markup (docs/PROTOCOL.md "Text"): every text field is a UTF-8 string in which {@code <PK>} and
 * {@code <MN>} are single Gen 1 glyphs and everything else is one glyph per Unicode code point (ligatures such as
 * {@code 'd} stay two characters, {@code #} already arrives expanded to {@code POKé}). No other {@code <…>} sequence is
 * a token. Plain Java; nothing here logs text.
 */
public final class Gen1Text {
	public static final String PK = "<PK>";
	public static final String MN = "<MN>";
	/** Glyphs a Gen 1 text box line holds. */
	public static final int LINE_GLYPHS = 18;
	/** The longest token a glyph may be on the wire ({@code ui.name.glyphs} items). */
	public static final int MAX_TOKEN_CHARS = 8;

	private Gen1Text() {
	}

	/** The markup split into glyph tokens: {@code <PK>}, {@code <MN>}, or one code point each. */
	public static List<String> glyphs(String markup) {
		List<String> out = new ArrayList<>(markup.length());
		int i = 0;
		while (i < markup.length()) {
			int length = tokenLength(markup, i);
			out.add(markup.substring(i, i + length));
			i += length;
		}
		return out;
	}

	/** How many glyphs the markup draws. */
	public static int count(String markup) {
		int count = 0;
		int i = 0;
		while (i < markup.length()) {
			i += tokenLength(markup, i);
			count++;
		}
		return count;
	}

	/** The first {@code glyphs} glyphs of the markup (all of it when it is shorter); tokens are never cut in half. */
	public static String prefix(String markup, int glyphs) {
		if (glyphs <= 0) {
			return "";
		}
		int i = 0;
		int taken = 0;
		while (i < markup.length() && taken < glyphs) {
			i += tokenLength(markup, i);
			taken++;
		}
		return markup.substring(0, i);
	}

	/** True when {@code glyph} is exactly one glyph: {@code <PK>}, {@code <MN>} or a single code point. */
	public static boolean isGlyph(String glyph) {
		return !glyph.isEmpty() && tokenLength(glyph, 0) == glyph.length();
	}

	/** True for the two small-letter glyphs. */
	public static boolean isSmallCaps(String glyph) {
		return PK.equals(glyph) || MN.equals(glyph);
	}

	/** The glyphs of a name joined back into markup. */
	public static String join(List<String> glyphs) {
		return String.join("", glyphs);
	}

	/**
	 * Splits markup into lines of at most {@code glyphs} glyphs, breaking at spaces where it can (for text that comes
	 * without a layout of its own, such as a choice's question). Never returns an empty list.
	 */
	public static List<String> wrap(String markup, int glyphs) {
		List<String> lines = new ArrayList<>();
		List<String> all = glyphs(markup);
		int start = 0;
		while (start < all.size()) {
			int end = Math.min(all.size(), start + Math.max(1, glyphs));
			if (end < all.size()) {
				int space = end;
				while (space > start && !all.get(space).equals(" ")) {
					space--;
				}
				if (space > start) {
					end = space;
				}
			}
			lines.add(join(all.subList(start, end)));
			start = end;
			while (start < all.size() && all.get(start).equals(" ")) {
				start++;
			}
		}
		if (lines.isEmpty()) {
			lines.add("");
		}
		return lines;
	}

	/** Length in chars of the glyph token starting at {@code i}. */
	private static int tokenLength(String markup, int i) {
		if (markup.startsWith(PK, i) || markup.startsWith(MN, i)) {
			return PK.length();
		}
		return Character.charCount(markup.codePointAt(i));
	}
}
