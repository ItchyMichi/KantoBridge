package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The text markup (docs/PROTOCOL.md "Text"): glyph tokens, counts, prefixes for the typewriter, wrapping. */
class Gen1TextTest {
	@Test
	void pkAndMnAreOneGlyphEachAndEverythingElseOnePerCodePoint() {
		assertEquals(List.of("<PK>", "<MN>", " ", "é", "♂", "×", "¥", "'", "d"), Gen1Text.glyphs("<PK><MN> é♂×¥'d"));
		assertEquals(9, Gen1Text.count("<PK><MN> é♂×¥'d"));
		assertEquals(0, Gen1Text.count(""));
		// Ligatures stay two characters; POKé arrives expanded.
		assertEquals(4, Gen1Text.count("POKé"));
		assertEquals(2, Gen1Text.count("'s"));
	}

	@Test
	void otherAngleBracketSequencesAreLiteralText() {
		assertEquals(List.of("<", "P", "K"), Gen1Text.glyphs("<PK"));
		assertEquals(4, Gen1Text.count("<XY>"));
		assertEquals(List.of("<", "p", "k", ">"), Gen1Text.glyphs("<pk>"));
		assertEquals(List.of("A", "<PK>", ">"), Gen1Text.glyphs("A<PK>>"));
	}

	@Test
	void codePointsOutsideTheBasicPlaneStayWhole() {
		String emoji = new String(Character.toChars(0x1F600));
		assertEquals(List.of("A", emoji, "B"), Gen1Text.glyphs("A" + emoji + "B"));
		assertTrue(Gen1Text.isGlyph(emoji));
	}

	@Test
	void prefixesNeverCutATokenInHalf() {
		String line = "A<PK>B";
		assertEquals("", Gen1Text.prefix(line, 0));
		assertEquals("A", Gen1Text.prefix(line, 1));
		assertEquals("A<PK>", Gen1Text.prefix(line, 2));
		assertEquals("A<PK>B", Gen1Text.prefix(line, 3));
		assertEquals("A<PK>B", Gen1Text.prefix(line, 99));
		assertEquals("", Gen1Text.prefix(line, -1));
	}

	@Test
	void singleGlyphTokens() {
		for (String glyph : List.of("A", "é", " ", "<PK>", "<MN>", "♀")) {
			assertTrue(Gen1Text.isGlyph(glyph), glyph);
		}
		for (String notOne : List.of("", "AB", "ED", "<PK>A", "<P", "lower case")) {
			assertFalse(Gen1Text.isGlyph(notOne), notOne);
		}
		assertTrue(Gen1Text.isSmallCaps("<PK>") && Gen1Text.isSmallCaps("<MN>"));
		assertFalse(Gen1Text.isSmallCaps("PK"));
	}

	@Test
	void wrappingBreaksAtSpacesWithinTheGlyphLimit() {
		assertEquals(List.of("Do you want to", "heal your <PK><MN>?"), Gen1Text.wrap("Do you want to heal your <PK><MN>?", 18));
		assertEquals(List.of(""), Gen1Text.wrap("", 18));
		// No space: a hard break at the limit.
		assertEquals(List.of("ABCDE", "FGH"), Gen1Text.wrap("ABCDEFGH", 5));
		for (String line : Gen1Text.wrap("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG AND KEEPS RUNNING", 18)) {
			assertTrue(Gen1Text.count(line) <= 18, "line within 18 glyphs");
			assertFalse(line.startsWith(" "), "no leading space");
		}
	}
}
