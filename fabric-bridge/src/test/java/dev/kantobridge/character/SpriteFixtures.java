package dev.kantobridge.character;

import java.util.Arrays;

/**
 * Made-up character sheets from ASCII art (no game data): 16 × 16 frames, {@code #} opaque, anything else transparent,
 * laid out as the generated sheet is (columns down, up, left, right; rows standing, walking).
 */
final class SpriteFixtures {
	static final int N = 16;

	/** A humanoid as Gen 1 draws one: a big head, a neck, a torso with arms, two legs. */
	static final String[] PERSON = {
			"................",
			"...##########...",
			"...##########...",
			"...##########...",
			"...##########...",
			"...##########...",
			"...##########...",
			".....######.....",
			"...##########...",
			"...##########...",
			"...##########...",
			".....######.....",
			"......#..#......",
			"......#..#......",
			"......#..#......",
			"................"};

	/** A ball: round, nothing humanoid. */
	static final String[] BALL = {
			"................",
			"................",
			"................",
			"......####......",
			"....########....",
			"...##########...",
			"...##########...",
			"..############..",
			"..############..",
			"...##########...",
			"...##########...",
			"....########....",
			"......####......",
			"................",
			"................",
			"................"};

	/** An 8 × 8 box in the frame's middle. */
	static final String[] BOX = box(4, 4, 12, 12);

	static String[] box(int x0, int y0, int x1, int y1) {
		String[] out = new String[N];
		for (int y = 0; y < N; y++) {
			char[] row = new char[N];
			Arrays.fill(row, '.');
			if (y >= y0 && y < y1) {
				Arrays.fill(row, x0, x1, '#');
			}
			out[y] = new String(row);
		}
		return out;
	}

	static String[] empty() {
		return box(0, 0, 0, 0);
	}

	/** A sheet whose every frame of a row is {@code stand} (row 0) and {@code walk} (row 1), with these frame counts. */
	static SpriteFrames sheet(String[] down, String[] up, String[] left, String[] walkDown, int frames) {
		boolean[] opaque = new boolean[4 * N * 2 * N];
		String[][][] layout = {{down, up, left, mirror(left)}, {walkDown, up, left, mirror(left)}};
		for (int row = 0; row < 2; row++) {
			for (int column = 0; column < 4; column++) {
				String[] art = layout[row][column];
				for (int y = 0; y < N; y++) {
					for (int x = 0; x < N; x++) {
						opaque[(row * N + y) * 4 * N + column * N + x] = art[y].charAt(x) == '#';
					}
				}
			}
		}
		return SpriteFrames.of(opaque, 4 * N, 2 * N, frames);
	}

	/** Every frame the same. */
	static SpriteFrames same(String[] art, int frames) {
		return sheet(art, art, art, art, frames);
	}

	static String[] mirror(String[] art) {
		String[] out = new String[art.length];
		for (int i = 0; i < art.length; i++) {
			out[i] = new StringBuilder(art[i]).reverse().toString();
		}
		return out;
	}

	private SpriteFixtures() {
	}
}
