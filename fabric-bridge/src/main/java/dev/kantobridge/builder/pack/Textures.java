package dev.kantobridge.builder.pack;

import java.util.List;

/**
 * Turns the export's 4-shade greyscale art into coloured textures (docs/PLAN.md §4.5 "Textures"): SGB palette
 * colouring (shade 0 is the lightest colour), 8×8 tiles upscaled to 16×16 with nearest-neighbour (so the block atlas
 * keeps its mip levels), Game Boy tile animations as vertical strips, and sprite sheets with shade 0 transparent.
 */
public final class Textures {
	/** Shades: white, light grey, dark grey, black. */
	public static final int[] GREY_PALETTE = {0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000};
	/** One animation step: 20 Game Boy frames at 60 Hz is a third of a second, about 7 ticks. */
	public static final int FRAME_TICKS = 7;
	/**
	 * The water sway: the tile's pixel offset at each of 8 steps (one pixel at a time right, back, one left, back), as
	 * Gen 1 rotates its water tile.
	 */
	public static final int[] WATER_OFFSETS = {1, 2, 3, 2, 1, 0, 7, 0};
	/** Flower frames per step (1-based frame images), as Gen 1 cycles its flower tile. */
	public static final int[] FLOWER_SEQUENCE = {1, 2, 3, 1, 1, 2, 3, 1};
	/** Sprite sheet layout: 4 columns (down, up, left, right) × 2 rows (standing, walking) of 16×16 frames. */
	public static final int SHEET_WIDTH = 64;
	public static final int SHEET_HEIGHT = 32;

	private static final int TILE = 8;
	private static final int SCALE = 2;

	private Textures() {
	}

	/** The Game Boy shade (0 lightest .. 3 darkest) of a pixel, or -1 when it is transparent. */
	public static int shade(int argb) {
		if ((argb >>> 24) < 128) {
			return -1;
		}
		int grey = (((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF)) / 3;
		// Nearest of the export's four greys: 255, 170, 85, 0.
		if (grey >= 213) {
			return 0;
		}
		if (grey >= 128) {
			return 1;
		}
		return grey >= 43 ? 2 : 3;
	}

	/** An opaque colour for a shade. Transparent pixels of opaque art take the lightest colour. */
	public static int colour(int[] palette, int shade) {
		return 0xFF000000 | palette[Math.max(shade, 0)];
	}

	/** The shades of one 8×8 tile of an atlas; tiles outside the atlas are blank (shade 0, as the engine draws them). */
	public static int[] tileShades(Png.Image atlas, int tilesPerRow, int tile) {
		int[] shades = new int[TILE * TILE];
		int ox = (tile % tilesPerRow) * TILE;
		int oy = (tile / tilesPerRow) * TILE;
		if (ox + TILE > atlas.width() || oy + TILE > atlas.height()) {
			return shades;
		}
		for (int y = 0; y < TILE; y++) {
			for (int x = 0; x < TILE; x++) {
				shades[y * TILE + x] = Math.max(0, shade(atlas.get(ox + x, oy + y)));
			}
		}
		return shades;
	}

	/** True when {@code tile} lies inside the atlas. */
	public static boolean inAtlas(Png.Image atlas, int tilesPerRow, int tile) {
		return (tile % tilesPerRow) * TILE + TILE <= atlas.width() && (tile / tilesPerRow) * TILE + TILE <= atlas.height();
	}

	/** A 16×16 coloured, 2× upscaled tile. */
	public static Png.Image tile(int[] shades, int[] palette) {
		Png.Image out = new Png.Image(TILE * SCALE, TILE * SCALE);
		paintTile(out, 0, shades, palette, 0);
		return out;
	}

	/** The 8-step water sway as a 16×128 strip (frametime {@link #FRAME_TICKS}). */
	public static Png.Image waterStrip(int[] shades, int[] palette) {
		return shiftStrip(shades, palette, WATER_OFFSETS);
	}

	/** A sideways-shifting tile (the export's {@code hshift}): one 16×16 frame per offset, the tile rotated right by it. */
	public static Png.Image shiftStrip(int[] shades, int[] palette, int[] offsets) {
		Png.Image out = new Png.Image(TILE * SCALE, TILE * SCALE * offsets.length);
		for (int frame = 0; frame < offsets.length; frame++) {
			paintTile(out, frame * TILE * SCALE, shades, palette, offsets[frame]);
		}
		return out;
	}

	/** Minecraft ticks per animation step for the engine's {@code period} (frames at 60 Hz); today's 7 when not given. */
	public static int frameTicks(int period) {
		return period > 0 ? Math.max(1, Math.round(period / 3.0F)) : FRAME_TICKS;
	}

	/** An animation that plays its frames in order. */
	public static String stripMeta(int frameTicks) {
		return "{\"animation\":{\"frametime\":" + frameTicks + "}}";
	}

	/** An animation that plays its frames in the order of a 1-based {@code sequence}. */
	public static String sequenceMeta(int[] sequence, int frames, int frameTicks) {
		StringBuilder order = new StringBuilder();
		for (int i = 0; i < sequence.length; i++) {
			order.append(i == 0 ? "" : ",").append(Math.max(0, Math.min(frames - 1, sequence[i] - 1)));
		}
		return "{\"animation\":{\"frametime\":" + frameTicks + ",\"frames\":[" + order + "]}}";
	}

	/** The flower frames (each 8×8 shades) as a 16×(16·n) strip; the order of steps is {@link #flowerMeta()}'s. */
	public static Png.Image flowerStrip(List<int[]> frames, int[] palette) {
		Png.Image out = new Png.Image(TILE * SCALE, TILE * SCALE * frames.size());
		for (int frame = 0; frame < frames.size(); frame++) {
			paintTile(out, frame * TILE * SCALE, frames.get(frame), palette, 0);
		}
		return out;
	}

	public static String waterMeta() {
		return "{\"animation\":{\"frametime\":" + FRAME_TICKS + "}}";
	}

	public static String flowerMeta() {
		StringBuilder frames = new StringBuilder();
		for (int i = 0; i < FLOWER_SEQUENCE.length; i++) {
			frames.append(i == 0 ? "" : ",").append(FLOWER_SEQUENCE[i] - 1);
		}
		return "{\"animation\":{\"frametime\":" + FRAME_TICKS + ",\"frames\":[" + frames + "]}}";
	}

	/** Paints one tile, rotated right by {@code shift} pixels (with wrap), 2× upscaled, at row {@code top} of {@code out}. */
	private static void paintTile(Png.Image out, int top, int[] shades, int[] palette, int shift) {
		for (int y = 0; y < TILE; y++) {
			for (int x = 0; x < TILE; x++) {
				int source = Math.floorMod(x - shift, TILE);
				int argb = colour(palette, shades[y * TILE + source]);
				for (int dy = 0; dy < SCALE; dy++) {
					for (int dx = 0; dx < SCALE; dx++) {
						out.set(x * SCALE + dx, top + y * SCALE + dy, argb);
					}
				}
			}
		}
	}

	/**
	 * An NPC texture in the fixed {@link #SHEET_WIDTH}×{@link #SHEET_HEIGHT} layout: row 0 standing, row 1 walking;
	 * columns down, up, left, right (right is left mirrored). Sheets with 3 frames reuse standing frames for walking,
	 * single-frame sheets use their frame everywhere. Shade 0 is transparent.
	 *
	 * @param sheet  the export's vertical strip of 16×16 frames
	 * @param frames how many frames the strip holds
	 */
	public static Png.Image spriteSheet(Png.Image sheet, int frames, int[] palette) {
		Png.Image out = new Png.Image(SHEET_WIDTH, SHEET_HEIGHT);
		int available = Math.min(frames, sheet.height() / 16);
		for (int row = 0; row < 2; row++) {
			for (int column = 0; column < 4; column++) {
				int source;
				boolean mirror = false;
				if (available >= 6) {
					source = row * 3 + Math.min(column, 2);
					mirror = column == 3;
				} else if (available >= 3) {
					source = Math.min(column, 2);
					mirror = column == 3;
				} else {
					source = 0;
				}
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						int sx = mirror ? 15 - x : x;
						int shade = sx < sheet.width() ? shade(sheet.get(sx, source * 16 + y)) : -1;
						int argb = shade <= 0 ? 0 : colour(palette, shade);
						out.set(column * 16 + x, row * 16 + y, argb);
					}
				}
			}
		}
		return out;
	}
}
