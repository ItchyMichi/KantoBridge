package dev.kantobridge.client.ui;

import java.util.List;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;

/**
 * The shape of the canvas our screens draw on: the hidden game's own screen, so its text fits the box its engine laid it
 * out for. Blue and Crystal use the Game Boy's 160×144, with Gen 1's 20×6-tile text box and one glyph a tile (18 a line).
 * Emerald (family {@code gen3}) uses the GBA's 240×160, with a 30×6-tile box whose lines hold 224 pixels: the engine wraps
 * Gen 3's proportional text to 208 (Gen1Recomp {@code TextIR.toTextBox}), and Minecraft's font is about as wide as Gen 3's.
 * A line that is still too wide is squeezed sideways into the box ({@link #squeeze}) and never runs over its edge.
 *
 * @param width  in canvas pixels
 * @param height in canvas pixels
 * @param tiled  the game's text sits on the tile grid, one glyph a tile (the Game Boy's); otherwise it is proportional
 */
public record CanvasShape(int width, int height, boolean tiled) {
	public static final CanvasShape GAME_BOY = new CanvasShape(160, 144, true);
	public static final CanvasShape GBA = new CanvasShape(240, 160, false);
	/** Where a text box's lines start, one tile in from its left edge. */
	public static final int TEXT_X = Gen1Draw.TILE;
	/** The text box is six tiles high: a frame tile, two lines two tiles apart, and their gaps. */
	static final int TEXT_BOX_TILES = 6;

	/** The dialog canvas for a game of {@code family} (hello.ok's): the GBA's for {@code gen3}, else the Game Boy's. */
	public static CanvasShape of(@Nullable String family) {
		return "gen3".equals(family) ? GBA : GAME_BOY;
	}

	/** The shape of a picture of {@code width}×{@code height} pixels (the mirror's): one of the two screens, or a proportional one. */
	public static CanvasShape ofSize(int width, int height) {
		if (width == GAME_BOY.width && height == GAME_BOY.height) {
			return GAME_BOY;
		}
		if (width == GBA.width && height == GBA.height) {
			return GBA;
		}
		return new CanvasShape(width, height, false);
	}

	/** The text box: the full width across the bottom six tiles. */
	public Gen1Box textBox() {
		return Gen1Box.tiles(0, this.height / Gen1Draw.TILE - TEXT_BOX_TILES, this.width / Gen1Draw.TILE, TEXT_BOX_TILES);
	}

	/** The top of the text box's first line (its second is {@link Gen1Draw#ROW} lower). */
	public int firstLineY() {
		return this.textBox().y() + 2 * Gen1Draw.TILE;
	}

	/** The room a text box line has, in canvas pixels: from {@link #TEXT_X} to one tile from the right edge. */
	public int lineWidth() {
		return this.width - 2 * Gen1Draw.TILE;
	}

	/** The glyphs a line holds on the tile grid; no limit for proportional text (the line's width limits it). */
	public int lineGlyphs() {
		return this.tiled ? this.lineWidth() / Gen1Draw.TILE : Integer.MAX_VALUE;
	}

	/**
	 * Text that comes without a layout of its own (a choice's question, a list's footer) as this box's lines: by glyphs on
	 * the tile grid, by {@code width} (canvas pixels) for proportional text.
	 */
	public List<String> wrap(String markup, ToIntFunction<String> width) {
		return this.tiled ? Gen1Text.wrap(markup, this.lineGlyphs()) : Gen1Text.wrap(markup, this.lineWidth(), width);
	}

	/** The sideways scale that fits a line {@code width} pixels wide into a text box line: 1 when it fits as it is. */
	public float squeeze(int width) {
		return width <= this.lineWidth() ? 1.0F : (float) this.lineWidth() / width;
	}
}
