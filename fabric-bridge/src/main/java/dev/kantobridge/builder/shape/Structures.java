package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;

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
 * separate           rows of separate things, two rows of them enough (D9's check): a pure repeat; with visible rows
 *                    also a repeat read whose body is foliage (a tree line under its crowns); without, every repeat read
 * unit               read as a repeat: 2; else min(L, 6; the screen's rows with H9)
 * region             4-connected tiles of the runs that aren't pure repeats, in row-major scan order
 * modeH              the most frequent unit (as before D9: at most 6) among the region's runs not read as repeats (ties:
 *                    the taller; none: 0)
 * h                  a door fold: modeH; read as a repeat (not pure) with modeH &gt; unit: modeH; else unit    (1..6)
 *                    With visible rows (H9, D9): unit up to the screen's rows; a door fold max(modeH, L + 2), a repeat
 *                    read's max(modeH, min(L, 2) + 2)
 * origin             f, or with a door cell directly south of the run its southern tile row (the door fold)
 * r                  outdoors, h &gt;= 2, L &gt;= 4, not a plain repeat (read as one and not raised),
 *                    tile(n) != tile(n+1) ? min(2, h - 1) : 0;   wallH = h - r
 * models             a cell holds a fitted model in use (r = 0 before). With visible rows such a run stands as before,
 *                    unless the model is on the cell over a door (a cave mouth, a wall exit, "mouth"): then no fold,
 *                    the run rises as drawn (min(L, the screen's rows)) past the model, roofless, outdoors or under the
 *                    open sky
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
	 * @param separate   rows of separate things (a tree line, a fence): two rows of them are enough (D9)
	 * @param models     a cell of it holds a fitted model in use, which D9 keeps in use: the run stands as before, unless
	 * @param mouth      the model is on the cell over a door (a cave mouth, a wall exit): no fold, the run rises as drawn
	 *                   past the model, roofless, its models lifted to their rows ({@link ShapePlanner#stackFaces})
	 */
	public record MeasuredRun(int tx, int n, int f, boolean pureRepeat, boolean repeatRead, int unit, int region, boolean doorFold,
			int origin, int h, int r, boolean separate, boolean models, boolean mouth) {
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

	/** The art tiles of {@code map} that {@code atlas} hints as foliage (a tree's, the tree wall's). */
	public static boolean[] foliage(SceneMap map, SceneAtlas atlas) {
		int cols = map.artCols();
		boolean[] out = new boolean[cols * map.artRows()];
		for (int i = 0; i < out.length; i++) {
			int tile = map.tile(i % cols, i / cols);
			out[i] = tile >= 0 && SceneAtlas.HINT_FOLIAGE.equals(atlas.hints().get(tile));
		}
		return out;
	}

	/** Every tile of rows {@code n..f} of column {@code tx} is marked in {@code marks} (null: none is). */
	private static boolean every(boolean @Nullable [] marks, int cols, int tx, int n, int f) {
		if (marks == null) {
			return false;
		}
		for (int ty = n; ty <= f; ty++) {
			if (!marks[ty * cols + tx]) {
				return false;
			}
		}
		return true;
	}

	/** A run's height before roofs: see the class comment. */
	private static int height(boolean surface, int interiorWall, boolean door, int vote, boolean repeat, boolean pure, int unit, int cap) {
		int h;
		if (!surface) {
			h = interiorWall;
		} else if (door && vote > 0) {
			h = vote;
		} else if (repeat && !pure && vote > unit) {
			h = vote;
		} else {
			h = unit;
		}
		return Math.max(1, Math.min(cap, h));
	}

	/** A run's roof rows: see the class comment. */
	private static int roof(SceneMap map, boolean outdoors, int tx, int n, int length, int h, boolean plainRepeat) {
		if (outdoors && h >= 2 && length >= ROOF_MIN_ROWS && !plainRepeat && map.tile(tx, n) != map.tile(tx, n + 1)) {
			return Math.min(2, h - 1);
		}
		return 0;
	}

	/** Some tile of the {@code per} × {@code per} cell at tile ({@code x0}, {@code y0}) is marked in {@code marks}. */
	private static boolean anyIn(boolean[] marks, int cols, int x0, int y0, int per) {
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				if (marks[(y0 + dy) * cols + x0 + dx]) {
					return true;
				}
			}
		}
		return false;
	}

	/** Some tile of rows {@code n..f} of column {@code tx} is marked in {@code marks} (null: none is). */
	private static boolean any(boolean @Nullable [] marks, int cols, int tx, int n, int f) {
		if (marks == null) {
			return false;
		}
		for (int ty = n; ty <= f; ty++) {
			if (marks[ty * cols + tx]) {
				return true;
			}
		}
		return false;
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
		return measured(map, solid, maxUnit, minRepeat, interiorWall, false);
	}

	/**
	 * The same; with {@code visibleRows} (H9, docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D9) a door's column holds the door's own
	 * rows and every row of the run over it, up to {@code maxUnit} (the screen's rows), and never less than its building.
	 */
	public static List<MeasuredRun> measured(SceneMap map, boolean[] solid, int maxUnit, int minRepeat, int interiorWall, boolean visibleRows) {
		return measured(map, solid, maxUnit, minRepeat, interiorWall, visibleRows, null, null);
	}

	/**
	 * The same, {@code foliage} marking the art tiles that draw foliage and {@code fitted} the tiles of cells that hold a
	 * fitted model (null: none). With {@code visibleRows} (D9) a plain face stands as tall as drawn up to {@code maxUnit} (the
	 * screen's rows) and a door's column holds the door's rows and the face over it; a run read as a repeat stays one (a
	 * repeat read whose body is foliage is rows of separate things, for the check); a run with a cell holding a fitted model
	 * in use stands as before, so the model stays in use, unless the model is the doorway of the door under it (a cave
	 * mouth): that face rises past it, roofless.
	 */
	public static List<MeasuredRun> measured(SceneMap map, boolean[] solid, int maxUnit, int minRepeat, int interiorWall, boolean visibleRows,
			boolean @Nullable [] foliage, boolean @Nullable [] fitted) {
		int cols = map.artCols();
		int rows = map.artRows();
		// 1. Runs: a piece, plus the repeating piece directly south of it when the piece itself doesn't repeat.
		// body: a head over a repeating body, the body's first row; else n
		record Cut(int tx, int n, int f, boolean pureRepeat, boolean repeatTail, int body) {
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
					cuts.add(new Cut(stretch.tx(), stretch.n() + piece.n(), stretch.n() + body.f(), false, true, stretch.n() + body.n()));
					p += 2;
				} else {
					cuts.add(new Cut(stretch.tx(), stretch.n() + piece.n(), stretch.n() + piece.f(), piece.repeat(), false, stretch.n() + piece.n()));
					p++;
				}
			}
		}
		// 2. Units, and the regions of everything that isn't a pure repeat. Each run's reading and unit as before D9 (H9), the
		// cap then HeightRules.MAX_UNIT: what the region votes with, and what a run D9 leaves alone stands at.
		int cap0 = visibleRows ? Math.min(maxUnit, HeightRules.MAX_UNIT) : maxUnit;
		boolean[] structure = new boolean[cols * rows];
		int[] unit0 = new int[cuts.size()];
		boolean[] repeat0 = new boolean[cuts.size()];
		boolean[] separate = new boolean[cuts.size()];
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
			repeat0[c] = cut.pureRepeat() || cut.repeatTail() || trim;
			// D9: a tree line under its crowns (its body foliage: the repeating rows, or the rows over a trimmed foot) is rows of
			// separate trees
			separate[c] = repeat0[c] && (!visibleRows || cut.pureRepeat() || every(foliage, cols, tx, cut.body(), cut.repeatTail() ? f : f - 1));
			unit0[c] = repeat0[c] ? HeightRules.WALL : Math.min(length, cap0);
		}
		int[] region = regions(structure, cols, rows);
		int regionCount = 0;
		for (int r : region) {
			regionCount = Math.max(regionCount, r + 1);
		}
		// 3. The region vote: the most frequent unit among the runs measured from their drawing (ties: the taller).
		int[][] votes = new int[regionCount][cap0 + 1];
		for (int c = 0; c < cuts.size(); c++) {
			Cut cut = cuts.get(c);
			if (!repeat0[c]) {
				votes[region[cut.n() * cols + cut.tx()]][unit0[c]]++;
			}
		}
		int[] modeH = new int[regionCount];
		for (int r = 0; r < regionCount; r++) {
			int best = 0;
			for (int u = 1; u <= cap0; u++) {
				if (votes[r][u] > 0 && votes[r][u] >= votes[r][best]) {
					best = u;
				}
			}
			modeH[r] = best;
		}
		// 4. Heights, door folds and roofs: as before D9; with visible rows (D9) a plain face as tall as drawn up to the
		// screen's rows, a door's column holding the door's rows and the face over it, unless the run has a cell holding a
		// fitted model that is in use (no roof before): it stands as it stood, or over a cave mouth's model rises past it.
		boolean outdoors = map.surface() && map.env().outdoor();
		int per = map.perCell();
		// the tiles of runs roofed before D9: a cell with one holds no fitted model in use
		boolean[] roofed0 = new boolean[cols * rows];
		for (int c = 0; visibleRows && c < cuts.size(); c++) {
			Cut cut = cuts.get(c);
			int reg = cut.pureRepeat() ? -1 : region[cut.n() * cols + cut.tx()];
			boolean door = cut.f() + 1 < rows && map.kindAtTile(cut.tx(), cut.f() + 1) == CellKind.WALK
					&& map.hasTag(cut.tx() / per, (cut.f() + 1) / per, CellTag.DOOR);
			int h0 = height(map.surface(), interiorWall, door, reg < 0 ? 0 : modeH[reg], repeat0[c], cut.pureRepeat(), unit0[c], cap0);
			if (roof(map, outdoors, cut.tx(), cut.n(), cut.f() - cut.n() + 1, h0, repeat0[c] && h0 == unit0[c]) > 0) {
				for (int ty = cut.n(); ty <= cut.f(); ty++) {
					roofed0[ty * cols + cut.tx()] = true;
				}
			}
		}
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
			boolean repeat = repeat0[c];
			int unit = unit0[c];
			int h = height(map.surface(), interiorWall, door, vote, repeat, cut.pureRepeat(), unit, cap0);
			int r = roof(map, outdoors, tx, n, length, h, repeat && h == unit);
			boolean models = false;
			boolean mouth = false;
			if (visibleRows) {
				// a cell of it holds a fitted model in use (no roof before)
				models = r == 0 && any(fitted, cols, tx, n, f);
				// such a model over the door (a cave mouth's, a wall exit's: its whole cell unroofed before) is the doorway
				mouth = models && door && fitted[f * cols + tx] && !anyIn(roofed0, cols, (tx / per) * per, (f / per) * per, per);
				if (mouth) {
					// the face over it folds no door and rises as drawn, outdoors or under the open sky, roofless so its models stay
					// in use (ShapePlanner.stackFaces lifts them to their rows)
					door = false;
					repeat = false;
					unit = Math.min(length, maxUnit);
					h = unit;
					r = 0;
				} else if (!models) {
					// a plain face as tall as drawn up to the screen's rows, a door's column its rows and the face over it; a run
					// read as a repeat stays one (the user's choice, 2026-10-21: no taller rimmed cliffs or tree lines)
					unit = repeat ? HeightRules.WALL : Math.min(length, maxUnit);
					if (map.surface() && door) {
						h = Math.min(maxUnit, Math.max(vote, (repeat ? Math.min(length, HeightRules.WALL) : length) + per));
					} else {
						h = height(map.surface(), interiorWall, false, vote, repeat, cut.pureRepeat(), unit, maxUnit);
					}
					r = roof(map, outdoors, tx, n, length, h, repeat && h == unit);
				}
			}
			int origin = door ? ((f + 1) / per) * per + per - 1 : f;
			out.add(new MeasuredRun(tx, n, f, cut.pureRepeat(), repeat, unit, reg, door, origin, h, r, separate[c], models, mouth));
		}
		return out;
	}
}
