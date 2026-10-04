package dev.kantobridge.builder.shape.geom;

/**
 * Which pixels of a drawing are not part of the thing drawn (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): the
 * candidates (the lightest shade, or transparent) that a 4-connected flood reaches from the drawing's 1-pixel apron on
 * the sides that border flat ground. The planner says which sides seed (outdoors only the south side, indoors every
 * side that borders flat ground). White sealed behind dark trim (window frames, wall stripes) is never reached, so it
 * stays part of the thing. Shades: 0 the lightest .. 3 the darkest, -1 transparent.
 */
public final class Background {
	/** Seed sides, a bit each. */
	public static final int NORTH = 1;
	public static final int SOUTH = 2;
	public static final int WEST = 4;
	public static final int EAST = 8;

	private Background() {
	}

	/** True for a shade the background may have: the lightest, or transparent. */
	public static boolean candidate(int shade) {
		return shade <= 0;
	}

	/**
	 * The background of a {@code w × h} drawing (row-major shades) whose apron is open on {@code seedSides}: every
	 * candidate the flood reaches.
	 */
	public static boolean[] of(int[] shades, int w, int h, int seedSides) {
		boolean[] out = new boolean[w * h];
		int[] queue = new int[w * h];
		int tail = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean edge = (y == 0 && (seedSides & NORTH) != 0) || (y == h - 1 && (seedSides & SOUTH) != 0) || (x == 0 && (seedSides & WEST) != 0)
						|| (x == w - 1 && (seedSides & EAST) != 0);
				int i = y * w + x;
				if (edge && candidate(shades[i]) && !out[i]) {
					out[i] = true;
					queue[tail++] = i;
				}
			}
		}
		int head = 0;
		while (head < tail) {
			int i = queue[head++];
			int x = i % w;
			int y = i / w;
			int[] next = {y > 0 ? i - w : -1, x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y < h - 1 ? i + w : -1};
			for (int n : next) {
				if (n >= 0 && !out[n] && candidate(shades[n])) {
					out[n] = true;
					queue[tail++] = n;
				}
			}
		}
		return out;
	}

	/** Set pixels of a mask. */
	public static int count(boolean[] mask) {
		int n = 0;
		for (boolean b : mask) {
			n += b ? 1 : 0;
		}
		return n;
	}
}
