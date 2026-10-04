package dev.kantobridge.builder.shape.geom;

/**
 * Round cells (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): a tree, a bush or a boulder drawn in one cell becomes a
 * hull. Every number here is our own tuning constant (R16), judged by the self-check and the play-test.
 *
 * <pre>
 * outline  the darkest shade
 * mask     the outline plus everything a flood from the drawing's border through the other shades can't reach; when
 *          that encloses almost nothing (a dithered drawing), flood through the two light shades only
 * round    the mask covers enough of the cell, is about as wide as tall and left/right symmetric, its top row is
 *          narrow and its widest row lies below its upper quarter (a crown or a boulder, never a board on a post)
 * hull     each mask row a disc: its span's centre and half-width r; a mask pixel dx from the centre stands as a run
 *          2·floor(sqrt(r² − dx²)) pixels deep, rounded to a multiple of DEPTH_STEP (at least 2), around the cell's
 *          middle, as high above the ground as its row lies above the drawing's bottom row; rows under the mask repeat
 *          its lowest row down to the ground. The rounding makes neighbouring runs share their planes, so their faces
 *          merge (a third fewer quads), and puts the widest rows' fronts on the cell's own edges
 * texels   every voxel of a run wears its pixel's texel (front, back as seen through it, and sides); its top wears the
 *          texel one row down, so a cap shows the crown, not its outline
 * </pre>
 */
public final class Hulls {
	/** The mask's share of the cell, at least. */
	static final double MIN_AREA = 0.30;
	/** Below this share of the cell enclosed (outline left out), the drawing is dithered: flood through light shades only. */
	static final double MIN_ENCLOSED = 0.10;
	/** Mask pixels whose mirror image isn't in the mask, at most (a share of the mask). */
	static final double MAX_ASYMMETRY = 0.15;
	/** The top row's width, at most (a share of the widest row). */
	static final double MAX_TOP_WIDTH = 0.75;
	static final int OUTLINE = 3;
	/** Run depths are multiples of this many pixels (half a block). */
	static final int DEPTH_STEP = 4;

	private Hulls() {
	}

	/**
	 * The outline-plus-enclosure mask of a {@code w × h} drawing; {@code skip} pixels (void tiles) never belong to it
	 * and let the flood through.
	 */
	public static boolean[] mask(int[] shades, boolean[] skip, int w, int h) {
		boolean[] mask = unreached(shades, skip, w, h, OUTLINE - 1);
		int enclosed = 0;
		for (int i = 0; i < mask.length; i++) {
			enclosed += mask[i] && shades[i] < OUTLINE ? 1 : 0;
		}
		if (enclosed < MIN_ENCLOSED * w * h) {
			mask = unreached(shades, skip, w, h, 1);
		}
		return mask;
	}

	/** What a 4-connected flood from the border through shades up to {@code passMax} (and skipped pixels) can't reach. */
	private static boolean[] unreached(int[] shades, boolean[] skip, int w, int h, int passMax) {
		boolean[] reached = new boolean[w * h];
		int[] queue = new int[w * h];
		int tail = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				boolean border = x == 0 || y == 0 || x == w - 1 || y == h - 1;
				if (border && (skip[i] || shades[i] <= passMax)) {
					reached[i] = true;
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
				if (n >= 0 && !reached[n] && (skip[n] || shades[n] <= passMax)) {
					reached[n] = true;
					queue[tail++] = n;
				}
			}
		}
		boolean[] mask = new boolean[w * h];
		for (int i = 0; i < mask.length; i++) {
			mask[i] = !reached[i] && !skip[i];
		}
		return mask;
	}

	/** True when a mask is a round thing (see the class comment). */
	public static boolean round(boolean[] mask, int w, int h) {
		int area = Background.count(mask);
		if (area < MIN_AREA * w * h) {
			return false;
		}
		int top = -1;
		int bottom = -1;
		int widest = 0;
		int widestRow = -1;
		int minX = w;
		int maxX = -1;
		int[] width = new int[h];
		for (int y = 0; y < h; y++) {
			int a = -1;
			int b = -1;
			for (int x = 0; x < w; x++) {
				if (mask[y * w + x]) {
					a = a < 0 ? x : a;
					b = x;
				}
			}
			if (a < 0) {
				continue;
			}
			top = top < 0 ? y : top;
			bottom = y;
			width[y] = b - a + 1;
			minX = Math.min(minX, a);
			maxX = Math.max(maxX, b);
			if (width[y] > widest) {
				widest = width[y];
				widestRow = y;
			}
		}
		int height = bottom - top + 1;
		if (Math.abs(widest - height) > Math.max(2, Math.max(widest, height) / 4)) {
			return false;
		}
		int asymmetric = 0;
		for (int y = top; y <= bottom; y++) {
			for (int x = 0; x < w; x++) {
				if (!mask[y * w + x]) {
					continue;
				}
				int mirror = minX + maxX - x;
				if (mirror < 0 || mirror >= w || !mask[y * w + mirror]) {
					asymmetric++;
				}
			}
		}
		return asymmetric <= MAX_ASYMMETRY * area && width[top] <= MAX_TOP_WIDTH * widest && widestRow - top >= height / 4;
	}

	/**
	 * The hull of a mask as voxels: {@code w} wide, {@code h} high, {@code d} deep (the drawing's own depth: its rows of
	 * tiles), centred on the drawing's middle row of depth.
	 *
	 * @param texels per pixel, the texel it shows ({@link VoxelGrid#texel})
	 */
	public static VoxelGrid hull(boolean[] mask, int[] texels, int w, int h, int d, int artPx) {
		VoxelGrid grid = new VoxelGrid(w, h, d, artPx);
		int bottom = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				bottom = mask[y * w + x] ? y : bottom;
			}
		}
		if (bottom < 0) {
			return grid;
		}
		for (int py = 0; py < h; py++) {
			int row = Math.min(py, bottom);
			int a = -1;
			int b = -1;
			for (int x = 0; x < w; x++) {
				if (mask[row * w + x]) {
					a = a < 0 ? x : a;
					b = x;
				}
			}
			if (a < 0) {
				continue;
			}
			double r = (b - a + 1) / 2.0;
			double centre = (a + b + 1) / 2.0;
			int y = h - 1 - py;
			for (int px = a; px <= b; px++) {
				if (!mask[row * w + px]) {
					continue;
				}
				double dx = px + 0.5 - centre;
				int chord = 2 * (int) Math.floor(Math.sqrt(Math.max(0, r * r - dx * dx)));
				chord = DEPTH_STEP * (int) Math.round(chord / (double) DEPTH_STEP);
				chord = Math.max(2, Math.min(d, chord));
				int z0 = d / 2 - chord / 2;
				int face = texels[row * w + px];
				int cap = texels[Math.min(row + 1, bottom) * w + px];
				for (int z = z0; z < z0 + chord; z++) {
					grid.set(px, y, z, face);
					grid.setCap(px, y, z, cap);
				}
			}
		}
		return grid;
	}
}
