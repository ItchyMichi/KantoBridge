package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The canvas's shape per game: the Game Boy's for Blue and Crystal, the GBA's for Emerald, each with its game's text box,
 * and a line too wide for the box squeezed into it.
 */
class CanvasShapeTest {
	@Test
	void emeraldGetsTheGbasScreenEveryOtherGameTheGameBoys() {
		assertSame(CanvasShape.GBA, CanvasShape.of("gen3"));
		assertSame(CanvasShape.GAME_BOY, CanvasShape.of("gen1"));
		assertSame(CanvasShape.GAME_BOY, CanvasShape.of("gen2"));
		assertSame(CanvasShape.GAME_BOY, CanvasShape.of(null), "before a handshake");
		assertEquals(240, CanvasShape.GBA.width());
		assertEquals(160, CanvasShape.GBA.height());
		assertEquals(Gen1Canvas.WIDTH, CanvasShape.GAME_BOY.width());
		assertEquals(Gen1Canvas.HEIGHT, CanvasShape.GAME_BOY.height());
	}

	@Test
	void aPicturesShapeIsItsScreens() {
		assertSame(CanvasShape.GAME_BOY, CanvasShape.ofSize(160, 144));
		assertSame(CanvasShape.GBA, CanvasShape.ofSize(240, 160));
		CanvasShape other = CanvasShape.ofSize(200, 100);
		assertEquals(200, other.width());
		assertFalse(other.tiled());
	}

	@Test
	void eachTextBoxIsTheBottomSixTilesAcrossTheWholeWidth() {
		assertEquals(new Gen1Box(0, 96, 160, 48), CanvasShape.GAME_BOY.textBox(), "Gen 1's 20×6 tiles");
		assertEquals(Gen1Box.tiles(0, 12, 20, 6), CanvasShape.GAME_BOY.textBox());
		assertEquals(112, CanvasShape.GAME_BOY.firstLineY(), "Gen 1's text rows 14 and 16");
		assertEquals(new Gen1Box(0, 112, 240, 48), CanvasShape.GBA.textBox(), "30×6 tiles on the GBA's 30×20");
		assertEquals(128, CanvasShape.GBA.firstLineY());
		for (CanvasShape shape : List.of(CanvasShape.GAME_BOY, CanvasShape.GBA)) {
			Gen1Box box = shape.textBox();
			// The second line's glyphs (9 pixels with descenders) end above the frame's bottom (3 pixels).
			assertTrue(shape.firstLineY() + Gen1Draw.ROW + 9 <= box.y() + box.h() - 3, "both lines inside the frame");
			assertEquals(box.w() - 2 * Gen1Draw.TILE, shape.lineWidth(), "a tile in from each side");
		}
	}

	@Test
	void theGbasLinesHoldWhatEmeraldsEngineLaysOut() {
		// TextIR.toTextBox wraps Gen 3's text to 208 pixels; the GBA box's lines have more than that.
		assertTrue(CanvasShape.GBA.lineWidth() >= 208 + 8, "room for Minecraft's font running a little wider");
		assertEquals(144, CanvasShape.GAME_BOY.lineWidth());
		assertEquals(18, CanvasShape.GAME_BOY.lineGlyphs(), "Gen 1's 18 glyphs a line");
		assertEquals(Integer.MAX_VALUE, CanvasShape.GBA.lineGlyphs(), "proportional: the width limits a line");
	}

	@Test
	void aLineTooWideIsSqueezedIntoTheBox() {
		assertEquals(1.0F, CanvasShape.GBA.squeeze(224));
		assertEquals(1.0F, CanvasShape.GBA.squeeze(10));
		assertEquals(0.8F, CanvasShape.GBA.squeeze(280), 1e-6);
		assertEquals(1.0F, CanvasShape.GAME_BOY.squeeze(144));
		assertEquals(0.5F, CanvasShape.GAME_BOY.squeeze(288), 1e-6);
		for (int width : new int[] {145, 200, 230, 400}) {
			assertTrue(width * CanvasShape.GAME_BOY.squeeze(width) <= 144.0001F, "squeezed to fit: " + width);
		}
	}

	@Test
	void textWithoutALayoutWrapsByGlyphsOnTheGameBoyAndByWidthOnTheGba() {
		String question = "Would you like to save the game and also the other game too?";
		// On the Game Boy the tile grid decides, whatever the font measures.
		for (String line : CanvasShape.GAME_BOY.wrap(question, s -> 1000)) {
			assertTrue(Gen1Text.count(line) <= 18, line);
		}
		// On the GBA the line's width does: about 37 six-pixel glyphs, so this needs two lines, not four.
		List<String> lines = CanvasShape.GBA.wrap(question, s -> 6 * Gen1Text.count(s));
		assertEquals(2, lines.size());
		for (String line : lines) {
			assertTrue(6 * Gen1Text.count(line) <= 224, line);
		}
	}
}
