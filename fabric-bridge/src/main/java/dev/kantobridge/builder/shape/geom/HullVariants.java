package dev.kantobridge.builder.shape.geom;

/**
 * P3's pinned units beyond P1's (docs/specs/PROFILE_PLAN.md §5.6): the research's hull variants, stairs and reliefs,
 * re-implemented on our hull (Dramaless Shape's code is not copied). One voxel per pixel of a cell's {@code w × w}
 * drawing; y up from the floor, z south, the cell's middle at {@code w / 2}.
 *
 * <pre>
 * stump   the drawing's top cap rows are its cut face: stripped from the body, which stands as a plain hull, and laid on
 *         the body's highest row by depth (the face's top row along the row's north end, its last along the south)
 * can     the same with its mouth's cap rows; its base rows stripped and rebuilt as the foot (the front and back keep the
 *         drawn base); the body's lowest row repeated up to the can's height; the body narrowing by taper pixels toward
 *         the floor (the drawing resampled into the narrower span); the top well rows hollowed to walls two voxels thick
 *         where a chord is longer than four; the mouth laid on the rim and on the well's floor
 * stairs  four steps rising across the cell (east: from the west edge), each a quarter of the class height higher and a
 *         quarter of the cell wide, as deep as the cell; every voxel the pixel the drawing shows at its column and its
 *         height (the drawing standing up), so the front and back are the drawing cut to the staircase, a tread the row at
 *         its height, a riser and the last step's side their edge columns
 * relief  the drawing lying flat: each pixel a flood from the drawing's border through the shades found on that border
 *         (not black) can't reach stands as a column of the class height, in its own texel
 * planter a {@code w × h} drawing (two cells, one above the other) carved row by row as a hull, every row its own height
 *         from the floor; the crown, stem and drape (the rows above the last quarter) no deeper than SPRAY voxels, the pot
 *         round; a hole in a crown row's span under a drawn pixel of its column backed by a plate one voxel thick in the
 *         middle
 * </pre>
 */
final class HullVariants {
	private static final int BLACK = 3;

	private HullVariants() {
	}

	/** A stump: {@code capRows} rows of cut face laid on its round top. */
	static VoxelGrid stump(boolean[] mask, int[] texels, int w, int artPx, int capRows) {
		int top = firstRow(mask, w);
		if (top < 0 || capRows <= 0) {
			return Hulls.hull(mask, texels, w, w, w, artPx);
		}
		int capEnd = Math.min(top + capRows - 1, w - 2);
		boolean[] body = mask.clone();
		clearRows(body, w, top, capEnd);
		int bodyTop = firstRow(body, w);
		if (bodyTop < 0) {
			return Hulls.hull(mask, texels, w, w, w, artPx);
		}
		VoxelGrid grid = Hulls.hull(body, texels, w, w, w, artPx);
		project(grid, texels, w, top, capEnd, w - 1 - bodyTop);
		return grid;
	}

	/** A can: its mouth, base, height, taper and well (the tileset's parameters). */
	static VoxelGrid can(boolean[] mask, int[] texels, int w, int artPx, int capRows, int baseRows, int height, int well, int taper) {
		int top = firstRow(mask, w);
		int bottom = lastRow(mask, w);
		if (top < 0) {
			return Hulls.hull(mask, texels, w, w, w, artPx);
		}
		int capEnd = capRows > 0 ? Math.min(top + capRows - 1, w - 2) : top - 1;
		boolean[] body = mask.clone();
		int[] art = texels.clone();
		clearRows(body, w, top, capEnd);
		int baseStart = bottom + 1;
		if (baseRows > 0) {
			baseStart = Math.max(bottom - baseRows + 1, capEnd + 2);
			clearRows(body, w, baseStart, bottom);
		}
		int bodyTop = firstRow(body, w);
		int bodyBottom = lastRow(body, w);
		if (bodyTop < 0) {
			return Hulls.hull(mask, texels, w, w, w, artPx);
		}
		// The height: the body's lowest row repeated up to the can's height; the foot: the same row down to the floor.
		int want = Math.max(0, w - Math.max(1, height));
		for (int py = Math.min(want, bodyTop); py < w; py++) {
			if (py < bodyTop || py > bodyBottom) {
				copyRow(body, art, w, bodyBottom, py);
			}
		}
		bodyTop = Math.min(bodyTop, want);
		// The taper: each row narrower toward the floor, its drawing resampled into the narrower span.
		if (taper > 0 && bodyTop < w - 1) {
			for (int py = bodyTop; py < w; py++) {
				int inset = (int) Math.floor(taper / 2.0 * (py - bodyTop) / (w - 1 - bodyTop) + 0.5);
				narrow(body, art, w, py, inset);
			}
		}
		VoxelGrid grid = Hulls.hull(body, art, w, w, w, artPx);
		// The drawn base on the foot's front and back.
		for (int py = baseStart; py <= bottom && py < w; py++) {
			int y = w - 1 - py;
			for (int x = 0; x < w; x++) {
				if (!mask[py * w + x]) {
					continue;
				}
				int z0 = -1;
				int z1 = -1;
				for (int z = 0; z < w; z++) {
					if (grid.solid(x, y, z)) {
						z0 = z0 < 0 ? z : z0;
						z1 = z;
					}
				}
				if (z0 >= 0) {
					grid.set(x, y, z0, texels[py * w + x]);
					grid.set(x, y, z1, texels[py * w + x]);
				}
			}
		}
		// The well: the top rows hollowed where a chord is longer than four voxels.
		int yTop = w - 1 - bodyTop;
		int floorY = Math.max(0, yTop - Math.max(0, well));
		for (int y = floorY + 1; y <= yTop && well > 0; y++) {
			for (int x = 0; x < w; x++) {
				int z0 = -1;
				int z1 = -1;
				for (int z = 0; z < w; z++) {
					if (grid.solid(x, y, z)) {
						z0 = z0 < 0 ? z : z0;
						z1 = z;
					}
				}
				if (z0 >= 0 && z1 - z0 + 1 > 4) {
					for (int z = z0 + 2; z <= z1 - 2; z++) {
						grid.clear(x, y, z);
					}
				}
			}
		}
		if (capEnd >= top) {
			project(grid, texels, w, top, capEnd, yTop);
			if (well > 0) {
				project(grid, texels, w, top, capEnd, floorY);
			}
		}
		return grid;
	}

	/** Four steps across the cell, rising {@code rise} pixels each, toward the east or the west. */
	static VoxelGrid stairs(int[] texels, int w, int artPx, boolean east, int rise) {
		VoxelGrid grid = new VoxelGrid(w, w, w, artPx);
		int run = w / 4;
		for (int step = 0; step < 4; step++) {
			int x0 = east ? step * run : w - run - step * run;
			int high = Math.min(w, (step + 1) * Math.max(1, rise));
			for (int x = x0; x < x0 + run; x++) {
				for (int y = 0; y < high; y++) {
					int row = Math.max(0, Math.min(w - 1, w - 1 - y));
					for (int z = 0; z < w; z++) {
						grid.set(x, y, z, texels[row * w + x]);
					}
				}
			}
		}
		return grid;
	}

	/** A relief: the drawing's enclosed and outlined pixels as columns {@code height} high, lying flat. */
	static VoxelGrid relief(int[] shades, int[] texels, boolean[] skip, int w, int artPx, int height) {
		boolean[] background = new boolean[4];
		for (int i = 0; i < w * w; i++) {
			int x = i % w;
			int y = i / w;
			if ((x == 0 || y == 0 || x == w - 1 || y == w - 1) && !skip[i] && shades[i] != BLACK) {
				background[shades[i]] = true;
			}
		}
		boolean[] reached = new boolean[w * w];
		int[] queue = new int[w * w];
		int tail = 0;
		for (int i = 0; i < w * w; i++) {
			int x = i % w;
			int y = i / w;
			if ((x == 0 || y == 0 || x == w - 1 || y == w - 1) && (skip[i] || background[shades[i]])) {
				reached[i] = true;
				queue[tail++] = i;
			}
		}
		for (int head = 0; head < tail; head++) {
			int i = queue[head];
			int x = i % w;
			int y = i / w;
			int[] next = {y > 0 ? i - w : -1, x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y < w - 1 ? i + w : -1};
			for (int n : next) {
				if (n >= 0 && !reached[n] && (skip[n] || background[shades[n]])) {
					reached[n] = true;
					queue[tail++] = n;
				}
			}
		}
		VoxelGrid grid = new VoxelGrid(w, w, w, artPx);
		int h = Math.max(1, Math.min(w, height));
		for (int i = 0; i < w * w; i++) {
			if (reached[i] || skip[i]) {
				continue;
			}
			for (int y = 0; y < h; y++) {
				grid.set(i % w, y, i / w, texels[i]);
			}
		}
		return grid;
	}

	/** A planter's crown rows are no deeper than this. */
	static final int SPRAY = 5;

	/** A planter: its drawing {@code w} wide and {@code h} high, carved as {@code w} deep. */
	static VoxelGrid planter(boolean[] mask, int[] texels, int w, int h, int artPx) {
		VoxelGrid grid = new VoxelGrid(w, h, w, artPx);
		int bottom = lastRow(mask, w);
		int pot = h - h / 4;
		for (int py = 0; py < h && bottom >= 0; py++) {
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
			for (int x = a; x <= b; x++) {
				int t = texels[row * w + x];
				if (!mask[row * w + x]) {
					if (py >= 1 && py < pot && drawnAbove(mask, w, x, row)) {
						grid.set(x, y, w / 2, t);
					}
					continue;
				}
				double dx = x + 0.5 - centre;
				int chord = Math.max(1, (int) Math.floor(2 * Math.sqrt(Math.max(0, r * r - dx * dx)) + 0.5));
				if (py < pot) {
					chord = Math.min(chord, SPRAY);
				}
				int z0 = (int) Math.floor(w / 2.0 - chord / 2.0 + 0.5);
				for (int z = z0; z < z0 + chord; z++) {
					grid.set(x, y, z, t);
					grid.setCap(x, y, z, texels[Math.min(row + 1, bottom) * w + x]);
				}
			}
		}
		return grid;
	}

	private static boolean drawnAbove(boolean[] mask, int w, int x, int row) {
		for (int y = 0; y < row; y++) {
			if (mask[y * w + x]) {
				return true;
			}
		}
		return false;
	}

	/** Lays the drawing's rows {@code r0..r1} on the tops of layer {@code y} by depth, its row's north end to its south. */
	private static void project(VoxelGrid grid, int[] texels, int w, int r0, int r1, int y) {
		int zn = w;
		int zs = -1;
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < w; z++) {
				if (grid.solid(x, y, z)) {
					zn = Math.min(zn, z);
					zs = Math.max(zs, z);
				}
			}
		}
		if (zs < 0) {
			return;
		}
		for (int x = 0; x < w; x++) {
			for (int z = zn; z <= zs; z++) {
				if (!grid.solid(x, y, z)) {
					continue;
				}
				double t = zs == zn ? 0 : (z - zn) / (double) (zs - zn);
				int row = r0 + (int) Math.floor(t * (r1 - r0) + 0.5);
				grid.setCap(x, y, z, texels[row * w + x]);
			}
		}
	}

	private static int firstRow(boolean[] mask, int w) {
		for (int i = 0; i < mask.length; i++) {
			if (mask[i]) {
				return i / w;
			}
		}
		return -1;
	}

	private static int lastRow(boolean[] mask, int w) {
		for (int i = mask.length - 1; i >= 0; i--) {
			if (mask[i]) {
				return i / w;
			}
		}
		return -1;
	}

	private static void clearRows(boolean[] mask, int w, int r0, int r1) {
		for (int y = Math.max(0, r0); y <= r1 && y * w < mask.length; y++) {
			for (int x = 0; x < w; x++) {
				mask[y * w + x] = false;
			}
		}
	}

	private static void copyRow(boolean[] mask, int[] art, int w, int from, int to) {
		System.arraycopy(mask, from * w, mask, to * w, w);
		System.arraycopy(art, from * w, art, to * w, w);
	}

	/** Narrows row {@code py}'s span by {@code inset} pixels each side (at least two wide), resampling its drawing. */
	private static void narrow(boolean[] mask, int[] art, int w, int py, int inset) {
		int a = -1;
		int b = -1;
		for (int x = 0; x < w; x++) {
			if (mask[py * w + x]) {
				a = a < 0 ? x : a;
				b = x;
			}
		}
		if (a < 0 || inset <= 0) {
			return;
		}
		int na = a + inset;
		int nb = b - inset;
		if (nb - na + 1 < 2) {
			int mid = (a + b) / 2;
			na = mid;
			nb = Math.min(w - 1, mid + 1);
		}
		boolean[] rowMask = new boolean[w];
		int[] rowArt = new int[w];
		for (int x = 0; x < w; x++) {
			rowArt[x] = art[py * w + x];
		}
		for (int x = na; x <= nb; x++) {
			int src = nb == na ? a : a + (int) Math.floor((x - na) * (double) (b - a) / (nb - na) + 0.5);
			rowMask[x] = mask[py * w + src];
			rowArt[x] = art[py * w + src];
		}
		for (int x = 0; x < w; x++) {
			mask[py * w + x] = rowMask[x];
			art[py * w + x] = rowArt[x];
		}
	}
}
