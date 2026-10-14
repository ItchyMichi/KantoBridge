package dev.kantobridge.builder.pack;

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
