package dev.kantobridge.builder.shape.geom;

import org.jetbrains.annotations.Nullable;

/**
 * Objects (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): signs, posts, plants and other small things drawn on a
 * background. Every number here is our own tuning constant (R16), judged by the self-check and the play-test.
 *
 * <pre>
 * sprite-like  a tile whose share of {@link Background background} pixels is at least SPRITE_SHARE
 * cluster      4-connected sprite-like tiles; one makes the drawing an object when it meets flat ground (outdoors on its
 *              south side, indoors on any side that borders flat ground) and its background share is at least
 *              OBJECT_SHARE; a drawing that repeats vertically (a tree wall's edge) is scenery, never an object
 * slabs        per 8-connected component of the drawing's other pixels: a slab DEPTH pixels deep, standing with its front
 *              at the south edge of its lowest pixel's row, so it covers exactly its own drawing in the game's view;
 *              front = the drawing, back = the same pixels (as seen through), edges = each pixel's own texel
 * </pre>
 */
public final class ObjectRule {
	/** A tile is sprite-like from this background share. */
	static final double SPRITE_SHARE = 0.25;
	/** A cluster needs this background share. */
	static final double OBJECT_SHARE = 0.15;
	/** Slab depth, in pixels. */
	public static final int DEPTH = 2;

	private ObjectRule() {
	}

	/**
	 * True when a drawing of {@code perCell × perCell} tiles is an object.
	 *
	 * @param background its background ({@link Background#of})
	 * @param skip       pixels that belong to nothing (void tiles)
	 * @param flatSides  the sides that border flat ground ({@link Background#NORTH} ...)
	 */
	public static boolean applies(boolean[] background, boolean[] skip, int perCell, int artPx, int flatSides, boolean indoor,
			boolean repeatsVertically) {
		if (repeatsVertically) {
			return false;
		}
		int w = perCell * artPx;
		int tiles = perCell * perCell;
		int[] bg = new int[tiles];
		int[] all = new int[tiles];
		for (int y = 0; y < w; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (skip[i]) {
					continue;
				}
				int t = (y / artPx) * perCell + x / artPx;
				all[t]++;
				bg[t] += background[i] ? 1 : 0;
			}
		}
		boolean[] sprite = new boolean[tiles];
		for (int t = 0; t < tiles; t++) {
			sprite[t] = all[t] > 0 && bg[t] >= SPRITE_SHARE * all[t];
		}
		int[] cluster = new int[tiles];
		java.util.Arrays.fill(cluster, -1);
		for (int start = 0; start < tiles; start++) {
			if (!sprite[start] || cluster[start] >= 0) {
				continue;
			}
			// One 4-connected cluster: its background share and the sides it touches.
			int[] queue = new int[tiles];
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			cluster[start] = start;
			int clusterBg = 0;
			int clusterAll = 0;
			int sides = 0;
			while (head < tail) {
				int t = queue[head++];
				int tx = t % perCell;
				int ty = t / perCell;
				clusterBg += bg[t];
				clusterAll += all[t];
				sides |= ty == 0 ? Background.NORTH : 0;
				sides |= ty == perCell - 1 ? Background.SOUTH : 0;
				sides |= tx == 0 ? Background.WEST : 0;
				sides |= tx == perCell - 1 ? Background.EAST : 0;
				int[] next = {ty > 0 ? t - perCell : -1, tx > 0 ? t - 1 : -1, tx < perCell - 1 ? t + 1 : -1, ty < perCell - 1 ? t + perCell : -1};
				for (int n : next) {
					if (n >= 0 && sprite[n] && cluster[n] < 0) {
						cluster[n] = start;
						queue[tail++] = n;
					}
				}
			}
			int ground = indoor ? flatSides : flatSides & Background.SOUTH;
			if ((sides & ground) != 0 && clusterAll > 0 && clusterBg >= OBJECT_SHARE * clusterAll) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The segmentation of a standee the profile pins (docs/specs/PROFILE_PLAN.md §5.4): the standee pools, the strict
	 * {@code cutout}, and {@code post}.
	 */
	public enum Pool {
		STANDEE, CUTOUT, POST
	}

	/** The pool of a pinned class, or null when the class isn't a standee or a post. */
	public static @Nullable Pool pool(@Nullable String cls) {
		if (cls == null) {
			return null;
		}
		return switch (cls) {
			case "billboard", "signpost", "stool", "prop", "bike", "console" -> Pool.STANDEE;
			case "cutout" -> Pool.CUTOUT;
			case "post" -> Pool.POST;
			default -> null;
		};
	}

	/** A pinned standee's slab depth in pixels (the research's pool depths). */
	public static int poolDepth(String cls) {
		return switch (cls) {
			case "billboard", "stool", "console" -> 10;
			case "post" -> 6;
			case "prop" -> 5;
			case "signpost", "bike" -> 2;
			default -> 1;
		};
	}

	/**
	 * A pinned standee's background (forced: no applicability test). Black is never background. The candidates are
	 * the shades {@code bgBits} names (bit k = shade k), else the non-black shades on the drawing's border, else white;
	 * {@link Pool#POST} and {@link Pool#CUTOUT} flood through white only. A 4-connected flood from the border through
	 * candidates (and skipped pixels) is the background, so what a solid outline encloses stays. A cutout's dark and
	 * light pixels are background wherever they are.
	 */
	public static boolean[] forcedBackground(int[] shades, boolean[] skip, int w, int h, Pool pool, int bgBits) {
		int candidates;
		if (pool != Pool.STANDEE) {
			candidates = 1;
		} else if ((bgBits & 7) != 0) {
			candidates = bgBits & 7;
		} else {
			candidates = 0;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = y * w + x;
					if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && !skip[i] && shades[i] != Hulls.OUTLINE) {
						candidates |= 1 << shades[i];
					}
				}
			}
			candidates = candidates == 0 ? 1 : candidates;
		}
		boolean[] background = new boolean[w * h];
		int[] queue = new int[w * h];
		int tail = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && passes(shades[i], skip[i], candidates)) {
					background[i] = true;
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
				if (n >= 0 && !background[n] && passes(shades[n], skip[n], candidates)) {
					background[n] = true;
					queue[tail++] = n;
				}
			}
		}
		if (pool == Pool.CUTOUT) {
			for (int i = 0; i < background.length; i++) {
				background[i] |= shades[i] == 1 || shades[i] == 2;
			}
		}
		return background;
	}

	private static boolean passes(int shade, boolean skip, int candidates) {
		return skip || (shade != Hulls.OUTLINE && (candidates & (1 << shade)) != 0);
	}

	/** The largest 8-connected component of a foreground (a cutout keeps only it); ties keep the first in scan order. */
	public static boolean[] largest(boolean[] foreground, int w, int h) {
		int[] component = new int[w * h];
		java.util.Arrays.fill(component, -1);
		int[] queue = new int[w * h];
		int best = -1;
		int bestSize = 0;
		for (int start = 0; start < w * h; start++) {
			if (!foreground[start] || component[start] >= 0) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			component[start] = start;
			while (head < tail) {
				int i = queue[head++];
				int x = i % w;
				int y = i / w;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < w && ny < h && foreground[ny * w + nx] && component[ny * w + nx] < 0) {
							component[ny * w + nx] = start;
							queue[tail++] = ny * w + nx;
						}
					}
				}
			}
			if (tail > bestSize) {
				best = start;
				bestSize = tail;
			}
		}
		boolean[] out = new boolean[w * h];
		for (int i = 0; i < out.length; i++) {
			out[i] = best >= 0 && component[i] == best;
		}
		return out;
	}

	/** The object's own pixels: neither background nor skipped. */
	public static boolean[] foreground(boolean[] background, boolean[] skip) {
		boolean[] out = new boolean[background.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = !background[i] && !skip[i];
		}
		return out;
	}

	/**
	 * The slabs of a {@code w × h} drawing's foreground as voxels ({@code d} deep): one per 8-connected component, its
	 * lowest pixel on the ground at the front.
	 *
	 * @param texels per pixel, the texel it shows ({@link VoxelGrid#texel})
	 */
	public static VoxelGrid slabs(boolean[] foreground, int[] texels, int w, int h, int d, int artPx) {
		return slabs(foreground, texels, w, h, d, artPx, DEPTH);
	}

	/** {@link #slabs} with another depth in pixels (a pack's rule, V6). */
	public static VoxelGrid slabs(boolean[] foreground, int[] texels, int w, int h, int d, int artPx, int depth) {
		VoxelGrid grid = new VoxelGrid(w, h, d, artPx);
		int[] component = new int[w * h];
		java.util.Arrays.fill(component, -1);
		int[] queue = new int[w * h];
		for (int start = 0; start < w * h; start++) {
			if (!foreground[start] || component[start] >= 0) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			component[start] = start;
			int lowest = start / w;
			while (head < tail) {
				int i = queue[head++];
				int x = i % w;
				int y = i / w;
				lowest = Math.max(lowest, y);
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < w && ny < h && foreground[ny * w + nx] && component[ny * w + nx] < 0) {
							component[ny * w + nx] = start;
							queue[tail++] = ny * w + nx;
						}
					}
				}
			}
			// The component stands on the ground with its front at the south edge of its lowest row.
			for (int k = 0; k < tail; k++) {
				int i = queue[k];
				int x = i % w;
				int y = i / w;
				for (int z = Math.max(0, lowest + 1 - depth); z <= Math.min(d - 1, lowest); z++) {
					grid.set(x, lowest - y, z, texels[i]);
				}
			}
		}
		return grid;
	}
}
