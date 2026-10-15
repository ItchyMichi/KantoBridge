package dev.kantobridge.builder.pack;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Baked art (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6 "Textures", §10 E4): a Gen 3 export's quarters are RGBA already, so a
 * texture is the quarter's own pixels, 8×8 upscaled to 16×16 with nearest-neighbour as Gen 1's are, never coloured with a
 * palette. Tiles are opaque, as the GBA draws a background (its clear pixels keep their colour). For the geometry, which
 * reads shades only, a quarter's pixels give shades by luminance (0 the lightest .. 3 the darkest).
 */
public final class BakedTextures {
	private static final int TILE = 8;
	private static final int SCALE = 2;

	private BakedTextures() {
	}

	/** The {@code 0xAARRGGBB} pixels of quarter {@code index} of a page {@code perRow} quarters wide, or null outside it. */
	public static int @Nullable [] quarter(Png.Image page, int perRow, int index) {
		int ox = (index % perRow) * TILE;
		int oy = (index / perRow) * TILE;
		if (index < 0 || ox + TILE > page.width() || oy + TILE > page.height()) {
			return null;
		}
		int[] out = new int[TILE * TILE];
		for (int y = 0; y < TILE; y++) {
			for (int x = 0; x < TILE; x++) {
				out[y * TILE + x] = page.get(ox + x, oy + y);
			}
		}
		return out;
	}

	/** A 16×16 opaque texture of an 8×8 quarter, 2× nearest. */
	public static Png.Image tile(int[] argb) {
		if (argb.length != TILE * TILE) {
			throw new IllegalArgumentException("a quarter is 8x8");
		}
		Png.Image out = new Png.Image(TILE * SCALE, TILE * SCALE);
		for (int y = 0; y < TILE * SCALE; y++) {
			for (int x = 0; x < TILE * SCALE; x++) {
				out.set(x, y, 0xFF000000 | argb[(y / SCALE) * TILE + x / SCALE]);
			}
		}
		return out;
	}

	/** An animated quarter's frames (the follow-up's item 8), each 16×16 as {@link #tile}, one below the other. */
	public static Png.Image strip(List<int[]> frames) {
		Png.Image out = new Png.Image(TILE * SCALE, TILE * SCALE * frames.size());
		for (int f = 0; f < frames.size(); f++) {
			Png.Image frame = tile(frames.get(f));
			for (int y = 0; y < TILE * SCALE; y++) {
				for (int x = 0; x < TILE * SCALE; x++) {
					out.set(x, f * TILE * SCALE + y, frame.get(x, y));
				}
			}
		}
		return out;
	}

	/**
	 * An animated quarter's {@code .mcmeta} (item 8): its loop ({@code [frame, GBA frames]} in order, 60 a second) in
	 * Minecraft ticks (20 a second). A step ends on the tick nearest its GBA time from the loop's start; one that ends on
	 * the tick the step before ended is too short to show and is left out. A loop not a whole number of ticks long runs
	 * three times, so every loop restarts on an exact tick: quarters whose loops differ in length keep the steps they share
	 * in step.
	 */
	public static String loopMeta(int[][] loop) {
		long total = 0;
		for (int[] step : loop) {
			total += step[1];
		}
		int repeats = total % 3 == 0 ? 1 : 3;
		StringBuilder frames = new StringBuilder();
		long gba = 0;
		long ticks = 0;
		for (int r = 0; r < repeats; r++) {
			for (int[] step : loop) {
				gba += step[1];
				long until = Math.round(gba / 3.0);
				if (until <= ticks) {
					continue;
				}
				frames.append(frames.length() == 0 ? "" : ",").append("{\"index\":").append(step[0]).append(",\"time\":")
						.append(until - ticks).append('}');
				ticks = until;
			}
		}
		return "{\"animation\":{\"frames\":[" + frames + "]}}";
	}

	/** The smallest square cell a Gen 3 sheet's frames take: two blocks for a 32-pixel frame (people are 16 × 32). */
	public static final int SHEET_CELL = 32;

	/**
	 * A Gen 3 object-event sheet (the follow-up's item 8: its {@code g3sprite} strip of {@code frames} frames of
	 * {@code w × h}) in the generated layout of {@link Textures#spriteSheet}: four columns facing down, up, left and right
	 * (left mirrored), a standing row and a walking row. A sheet of nine frames or more is a walker (Gen 3's order: three
	 * standing, then two steps a facing): standing 0, 1, 2, walking 3, 5, 7. A sheet of three faces but never walks. Any
	 * other count is an object of one look, frame 0 everywhere and never mirrored (an item ball; a tree or rock whose other
	 * frames are its cut or smash). Each frame sits at the bottom middle of a square cell of {@link #SHEET_CELL} (or its
	 * larger side), so a 16 × 32 person draws one block wide and two tall where Gen 1's 16 × 16 draws two by two. Null when
	 * the strip is smaller than its frames.
	 */
	public static Png.@Nullable Image sheet(Png.Image strip, int w, int h, int frames) {
		if (w < 1 || h < 1 || frames < 1 || strip.width() < w || strip.height() < h * frames) {
			return null;
		}
		int cell = Math.max(SHEET_CELL, Math.max(w, h));
		boolean faces = frames >= 9 || frames == 3;
		int[] standing = faces ? new int[] {0, 1, 2} : new int[] {0, 0, 0};
		int[] walking = frames >= 9 ? new int[] {3, 5, 7} : standing;
		Png.Image out = new Png.Image(cell * 4, cell * 2);
		int ox = (cell - w) / 2;
		int oy = cell - h;
		for (int row = 0; row < 2; row++) {
			int[] picks = row == 0 ? standing : walking;
			for (int column = 0; column < 4; column++) {
				int frame = picks[Math.min(column, 2)];
				boolean mirror = faces && column == 3;
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int sx = mirror ? w - 1 - x : x;
						out.set(column * cell + ox + x, row * cell + oy + y, strip.get(sx, frame * h + y));
					}
				}
			}
		}
		return out;
	}

	/**
	 * The shades of a quarter by luminance (Gen 1's four greys' thresholds). A background tile is opaque, as Gen 1's are to
	 * the geometry: a clear pixel takes shade 0.
	 */
	public static int[] shades(int[] argb) {
		int[] out = new int[argb.length];
		for (int i = 0; i < argb.length; i++) {
			out[i] = Math.max(0, Textures.shade(argb[i]));
		}
		return out;
	}
}
