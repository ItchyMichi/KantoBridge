package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The structure analysis of one map from tile ids alone (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4, stage 3): which tiles are
 * solid, the vertical runs of solid tiles in each tile column, the 4-connected regions they form, and each run's
 * measured height. Integers only, row-major scans, no pixel: the same map always gives the same result (I7, I8).
 *
 * <p>Coordinates are art tiles; one tile is one block column; rows grow southward. A stretch is rows {@code n..f} of
 * one tile column ({@code n} north, {@code f} the front).
 *
 * <p><b>Measured runs</b> ({@code kb-shapes/2}, {@link #measured}): {@link HeightRules}'s repeat rule cuts each stretch into
 * pieces (repeating stretches of at least four rows: tree lines, plain walls, cliff bodies; and what lies between), and a
 * run is a piece plus the repeating piece directly south of it, so a tree line touching a building stays its own run
 * while a building's drawn head (its roof rows) keeps the facade below it. Per run:
 *
 * <pre>
 * read as a repeat   a pure repeat; a head over a repeating body (its front is a repeat: a cliff under its rim, a
 *                    plain facade under its roof); or the trim rule: L &gt; 2, tile(f-1) == tile(f-2) != tile(f)
 * unit               read as a repeat: 2; else min(L, 6)
 * region             4-connected tiles of the runs that aren't pure repeats, in row-major scan order
 * modeH              the most frequent unit among the region's runs not read as repeats (ties: the taller; none: 0)
 * h                  a door fold: modeH; read as a repeat (not pure) with modeH &gt; unit: modeH; else unit    (1..6)
 * origin             f, or with a door cell directly south of the run its southern tile row (the door fold)
 * r                  outdoors, h &gt;= 2, L &gt;= 4, not a plain repeat (read as one and not raised),
 *                    tile(n) != tile(n+1) ? min(2, h - 1) : 0;   wallH = h - r
 * interiors          every run: h = the interior wall height, r = 0
 * </pre>
 */
public final class Structures {
	/** A maximal vertical stretch of solid tiles in one tile column. */
	public record Run(int tx, int n, int f) {
		public int length() {
			return this.f - this.n + 1;
		}
	}

	/**
	 * One measured run (rows {@code n..f} of tile column {@code tx}) and its planned height.
	 *
	 * @param pureRepeat the run is one repeating stretch (a tree line, a fence, a plain wall)
	 * @param repeatRead read as a repeat: pure, a head over a repeating body, or by the trim rule
	 * @param unit       the measured unit, in tile rows (blocks)
	 * @param region     the run's structure region, or -1 for a pure repeat
	 * @param origin     the fold origin: {@code f}, or the door cell's southern row
	 * @param h          the planned height in blocks (1..6)
	 * @param r          roof rows (0..2): the column ends in one roof block at {@code feet + h - r}
	 */
	public record MeasuredRun(int tx, int n, int f, boolean pureRepeat, boolean repeatRead, int unit, int region, boolean doorFold,
			int origin, int h, int r) {
		public int length() {
			return this.f - this.n + 1;
		}

		/** The eave height: cube bands below the roof (all of {@code h} without a roof). */
		public int wallH() {
			return this.h - this.r;
		}

		/** Blocks of the column above the floor block: the bands, plus the roof block. */
		public int blocks() {
			return this.wallH() + (this.r > 0 ? 1 : 0);
		}

		/** Read as a repeat and not raised by the region vote: a flat top, never a roof. */
		public boolean plainRepeat() {
			return this.repeatRead && this.h == this.unit;
		}
	}

	/** One piece of a stretch as {@link HeightRules}'s repeat rule cuts it. */
	record Segment(int n, int f, boolean repeat) {
	}

	/** A roof needs this many drawn rows: two roof rows over at least two wall rows (signs and lone trees have fewer). */
	public static final int ROOF_MIN_ROWS = 4;

	private Structures() {
	}

	/** Per art tile, true when its cell is solid. */
	public static boolean[] solidTiles(SceneMap map) {
		int cols = map.artCols();
		int rows = map.artRows();
		boolean[] solid = new boolean[cols * rows];
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				solid[ty * cols + tx] = map.kindAtTile(tx, ty) == CellKind.SOLID;
			}
		}
		return solid;
	}

	/** Every run of every tile column, column by column, north to south. */
	public static List<Run> runs(boolean[] solid, int cols, int rows) {
		List<Run> out = new ArrayList<>();
		for (int tx = 0; tx < cols; tx++) {
			int ty = 0;
			while (ty < rows) {
				if (!solid[ty * cols + tx]) {
					ty++;
					continue;
				}
				int n = ty;
				while (ty < rows && solid[ty * cols + tx]) {
					ty++;
				}
				out.add(new Run(tx, n, ty - 1));
			}
		}
		return out;
	}

	/**
	 * The run each solid tile belongs to (an index into {@code runs}), -1 for tiles that aren't solid.
	 */
	public static int[] runOf(List<Run> runs, int cols, int rows) {
		int[] out = new int[cols * rows];
		Arrays.fill(out, -1);
		for (int r = 0; r < runs.size(); r++) {
			Run run = runs.get(r);
			for (int ty = run.n(); ty <= run.f(); ty++) {
				out[ty * cols + run.tx()] = r;
			}
		}
		return out;
	}

	/**
	 * 4-connected regions of solid tiles, numbered in row-major scan order of their first tile; -1 for tiles that aren't
	 * solid. One map has one atlas, so a region never crosses an atlas change.
	 */
	public static int[] regions(boolean[] solid, int cols, int rows) {
		int[] region = new int[cols * rows];
		Arrays.fill(region, -1);
		int next = 0;
		int[] queue = new int[cols * rows];
		for (int start = 0; start < solid.length; start++) {
			if (!solid[start] || region[start] >= 0) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			region[start] = next;
			while (head < tail) {
				int i = queue[head++];
				int x = i % cols;
				int y = i / cols;
				int[] neighbours = {y > 0 ? i - cols : -1, x > 0 ? i - 1 : -1, x < cols - 1 ? i + 1 : -1, y < rows - 1 ? i + cols : -1};
				for (int n : neighbours) {
					if (n >= 0 && solid[n] && region[n] < 0) {
						region[n] = next;
						queue[tail++] = n;
					}
				}
			}
			next++;
		}
		return region;
	}

	/**
	 * The measured unit of every solid tile (tile rows, 1..{@code maxUnit}), 0 elsewhere: {@link HeightRules#measure}, the
	 * drawn unit along each tile column with its period-1/period-2 repeat rule.
	 */
	public static int[] units(SceneMap map, boolean[] solid) {
		return HeightRules.measure(map.tiles(), map.artCols(), map.artRows(), solid);
	}

	/**
	 * Today's classified height of every cell (V2, {@code heights: "classified"}): solid cells of surface maps take
	 * {@link HeightRules#classify} of the tallest unit among their tiles (2 or 4 blocks), interior solid cells the
	 * interior wall height, counters 1 block, everything else 0. Exactly what {@code MapModel} builds.
	 */
	public static int[] classifiedHeights(SceneMap map, int[] units, int interiorWall) {
		int w = map.w();
		int h = map.h();
		int cols = map.artCols();
		int per = map.perCell();
		int[] heights = new int[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				CellKind kind = map.kind(cx, cy);
				int height = 0;
				if (kind == CellKind.COUNTER) {
					height = HeightRules.COUNTER;
				} else if (kind == CellKind.SOLID) {
					if (!map.surface()) {
						height = interiorWall;
					} else {
						int unit = 0;
						for (int dy = 0; dy < per; dy++) {
							for (int dx = 0; dx < per; dx++) {
								unit = Math.max(unit, units[(cy * per + dy) * cols + cx * per + dx]);
							}
						}
						height = HeightRules.classify(unit);
					}
				}
				heights[cy * w + cx] = height;
			}
		}
		return heights;
	}

	// ---- measured runs (kb-shapes/2) ----

	/** One stretch's pieces, north to south, by the repeat rule of {@link HeightRules}. */
	static List<Segment> segments(int[] seq, int length, int minRepeat) {
		List<Segment> out = new ArrayList<>();
		int i = 0;
		while (i < length) {
			int repeat = HeightRules.repeatLength(seq, length, i);
			if (repeat >= minRepeat) {
				out.add(new Segment(i, i + repeat - 1, true));
				i += repeat;
				continue;
			}
			int j = i + 1;
			while (j < length && HeightRules.repeatLength(seq, length, j) < minRepeat) {
				j++;
			}
			out.add(new Segment(i, j - 1, false));
			i = j;
		}
		return out;
	}

	/**
	 * Measured runs of a map ({@code kb-shapes/2}), column by column, north to south; see the class comment.
	 *
	 * @param maxUnit      the tallest unit, in tile rows
	 * @param minRepeat    the shortest repeating stretch that cuts a stretch
	 * @param interiorWall interior wall height in blocks
	 */
	public static List<MeasuredRun> measured(SceneMap map, boolean[] solid, int maxUnit, int minRepeat, int interiorWall) {
		int cols = map.artCols();
		int rows = map.artRows();
		// 1. Runs: a piece, plus the repeating piece directly south of it when the piece itself doesn't repeat.
		record Cut(int tx, int n, int f, boolean pureRepeat, boolean repeatTail) {
		}
		List<Cut> cuts = new ArrayList<>();
		int[] seq = new int[rows];
		for (Run stretch : runs(solid, cols, rows)) {
			int length = stretch.length();
			for (int k = 0; k < length; k++) {
				seq[k] = map.tile(stretch.tx(), stretch.n() + k);
			}
			List<Segment> pieces = segments(seq, length, minRepeat);
			int p = 0;
			while (p < pieces.size()) {
				Segment piece = pieces.get(p);
				if (!piece.repeat() && p + 1 < pieces.size()) {
					// A head over a repeating body (segments() never puts two non-repeating pieces in a row).
					Segment body = pieces.get(p + 1);
					cuts.add(new Cut(stretch.tx(), stretch.n() + piece.n(), stretch.n() + body.f(), false, true));
					p += 2;
				} else {
					cuts.add(new Cut(stretch.tx(), stretch.n() + piece.n(), stretch.n() + piece.f(), piece.repeat(), false));
					p++;
				}
			}
		}
		// 2. Units, and the regions of everything that isn't a pure repeat.
		boolean[] structure = new boolean[cols * rows];
		int[] unit = new int[cuts.size()];
		boolean[] repeatRead = new boolean[cuts.size()];
		for (int c = 0; c < cuts.size(); c++) {
			Cut cut = cuts.get(c);
			int tx = cut.tx();
			int f = cut.f();
			int length = f - cut.n() + 1;
			if (!cut.pureRepeat()) {
				for (int ty = cut.n(); ty <= f; ty++) {
					structure[ty * cols + tx] = true;
				}
			}
			boolean trim = !cut.pureRepeat() && !cut.repeatTail() && length > 2 && map.tile(tx, f - 1) == map.tile(tx, f - 2)
					&& map.tile(tx, f) != map.tile(tx, f - 1);
			repeatRead[c] = cut.pureRepeat() || cut.repeatTail() || trim;
			unit[c] = repeatRead[c] ? HeightRules.WALL : Math.min(length, maxUnit);
		}
		int[] region = regions(structure, cols, rows);
		int regionCount = 0;
		for (int r : region) {
			regionCount = Math.max(regionCount, r + 1);
		}
		// 3. The region vote: the most frequent unit among the runs measured from their drawing (ties: the taller).
		int[][] votes = new int[regionCount][maxUnit + 1];
		for (int c = 0; c < cuts.size(); c++) {
			Cut cut = cuts.get(c);
			if (!repeatRead[c]) {
				votes[region[cut.n() * cols + cut.tx()]][unit[c]]++;
			}
		}
		int[] modeH = new int[regionCount];
		for (int r = 0; r < regionCount; r++) {
			int best = 0;
			for (int u = 1; u <= maxUnit; u++) {
				if (votes[r][u] > 0 && votes[r][u] >= votes[r][best]) {
					best = u;
				}
			}
			modeH[r] = best;
		}
		// 4. Heights, door folds and roofs.
		boolean outdoors = map.surface() && map.env().outdoor();
		int per = map.perCell();
		List<MeasuredRun> out = new ArrayList<>(cuts.size());
		for (int c = 0; c < cuts.size(); c++) {
			Cut cut = cuts.get(c);
			int tx = cut.tx();
			int n = cut.n();
			int f = cut.f();
			int length = f - n + 1;
			int reg = cut.pureRepeat() ? -1 : region[n * cols + tx];
			int vote = reg < 0 ? 0 : modeH[reg];
			boolean door = f + 1 < rows && map.kindAtTile(tx, f + 1) == CellKind.WALK && map.hasTag(tx / per, (f + 1) / per, CellTag.DOOR);
			int origin = door ? ((f + 1) / per) * per + per - 1 : f;
			int h;
			if (!map.surface()) {
				h = interiorWall;
			} else if (door && vote > 0) {
				h = vote;
			} else if (repeatRead[c] && !cut.pureRepeat() && vote > unit[c]) {
				h = vote;
			} else {
				h = unit[c];
			}
			h = Math.max(1, Math.min(maxUnit, h));
			boolean plainRepeat = repeatRead[c] && h == unit[c];
			int r = 0;
			if (outdoors && h >= 2 && length >= ROOF_MIN_ROWS && !plainRepeat && map.tile(tx, n) != map.tile(tx, n + 1)) {
				r = Math.min(2, h - 1);
			}
			out.add(new MeasuredRun(tx, n, f, cut.pureRepeat(), repeatRead[c], unit[c], reg, door, origin, h, r));
		}
		return out;
	}
}
