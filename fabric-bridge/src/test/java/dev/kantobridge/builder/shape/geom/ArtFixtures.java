package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.scene.ArtSource;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Tiny drawings in shades for the Tier B suites (docs/specs/VOXEL_BLOCKS_PLAN.md §6.1): one character per pixel,
 * {@code 0}..{@code 3} a shade (0 the lightest), {@code .} the lightest too, {@code -} transparent. Made up; no game art.
 */
final class ArtFixtures {
	static final int ART_PX = 8;

	private ArtFixtures() {
	}

	/** Shades from rows of characters, row-major. */
	static int[] shades(String... rows) {
		int w = rows[0].length();
		int[] out = new int[w * rows.length];
		for (int y = 0; y < rows.length; y++) {
			if (rows[y].length() != w) {
				throw new IllegalArgumentException("ragged drawing");
			}
			for (int x = 0; x < w; x++) {
				char c = rows[y].charAt(x);
				out[y * w + x] = c == '.' ? 0 : c == '-' ? -1 : c - '0';
			}
		}
		return out;
	}

	/** The four 8×8 tiles of a 16×16 drawing (row-major: north-west, north-east, south-west, south-east). */
	static int[][] tiles(int[] drawing) {
		int[][] out = new int[4][ART_PX * ART_PX];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				out[(y / ART_PX) * 2 + x / ART_PX][(y % ART_PX) * ART_PX + x % ART_PX] = drawing[y * 16 + x];
			}
		}
		return out;
	}

	/** Per pixel of a 16×16 drawing, its texel: the tile it lies in and its column and row there. */
	static int[] texels() {
		int[] out = new int[256];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				out[y * 16 + x] = VoxelGrid.texel((y / ART_PX) * 2 + x / ART_PX, x % ART_PX, y % ART_PX);
			}
		}
		return out;
	}

	/** A round crown of radius {@code r} with a dark outline, centred at (7.5, 6.5), on the lightest ground. */
	static int[] crown(double r) {
		int[] out = new int[256];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double d2 = (x - 7.5) * (x - 7.5) + (y - 6.5) * (y - 6.5);
				out[y * 16 + x] = d2 < (r - 1.2) * (r - 1.2) ? ((x * 3 + y * 5) % 7 == 0 ? 1 : 2) : d2 < r * r ? 3 : 0;
			}
		}
		return out;
	}

	/** A sign: a board with a dark rim on a two-pixel post, on the lightest ground. */
	static int[] sign() {
		return shades(
				"................",
				".33333333333333.",
				".31111111111113.",
				".31111111111113.",
				".31111111111113.",
				".31111111111113.",
				".31111111111113.",
				".33333333333333.",
				".......33.......",
				".......33.......",
				".......33.......",
				".......33.......",
				".......33.......",
				".......33.......",
				".......33.......",
				".......33.......");
	}

	/** An art source over made-up tiles of one atlas. */
	static ArtSource art(String atlas, Map<Integer, int[]> tiles, Map<Integer, List<int[]>> frames) {
		return new ArtSource() {
			@Override
			public int @Nullable [] tileShades(String a, int tile) {
				return atlas.equals(a) ? tiles.get(tile) : null;
			}

			@Override
			public List<int[]> frameShades(String a, int tile) {
				return atlas.equals(a) ? frames.getOrDefault(tile, List.of()) : List.of();
			}

			@Override
			public int artPx() {
				return ART_PX;
			}
		};
	}
}
