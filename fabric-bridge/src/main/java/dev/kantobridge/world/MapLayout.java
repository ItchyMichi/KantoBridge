package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Where the cells of one map sit when they don't all share its placement's rectangle and feet level
 * (docs/specs/CAVES_3D_PLAN.md §3.1): its pieces (parts of the map placed apart), each cell's level (a raised floor)
 * and ramp (a stairs cell that climbs between levels), and its ceiling (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D5). A map
 * without a layout is one piece at its placement, every cell at level 0, its ceilings the builder's default. WorldIndex
 * keys {@code pieces}, {@code levels}, {@code ramps} and {@code ceilings} of a map; each is written only when it says
 * more than that default, so a world without layouts keeps its bytes. Pure Java.
 *
 * <p>A walkable cell belongs to exactly one piece. A wall cell may be drawn by several pieces, which all list it; lookups
 * by cell take the first piece that does.
 */
public final class MapLayout {
	/** The most pieces one map may have. */
	public static final int MAX_PIECES = 256;
	/** The highest level a cell may have, in blocks above its piece's feet level. */
	public static final int MAX_LEVEL = 64;
	/** The farthest a piece may sit from its map's placement, in blocks, across. */
	public static final int MAX_OFFSET = 1 << 22;
	/** The farthest a piece may sit from its map's placement, in blocks, up or down. */
	public static final int MAX_OFFSET_Y = 1024;
	/** The highest a ramp climbs, in blocks. */
	public static final int MAX_RISE = 8;
	/** The highest a ceiling may stand, in blocks above its piece's feet level. */
	public static final int MAX_CEILING = MAX_LEVEL + 64;

	static final Set<String> KEYS = Set.of("pieces", "levels", "ramps", "ceilings");
	private static final Set<String> PIECE_FIELDS = Set.of("x", "y", "w", "h", "mask", "dx", "dy", "dz");
	private static final Set<String> RAMP_FIELDS = Set.of("x", "y", "dir", "rise");

	/**
	 * One part of a map placed on its own.
	 *
	 * @param x    the north-west cell of its rectangle
	 * @param w    the rectangle's width in cells
	 * @param mask which cells of the rectangle are the piece's, row by row; null for all of them
	 * @param dx   its offset from the map's placement in blocks (X); {@code dy} and {@code dz} likewise
	 */
	public record Piece(int x, int y, int w, int h, @Nullable BitSet mask, int dx, int dy, int dz) {
		public Piece {
			if (x < 0 || y < 0 || w < 1 || h < 1) {
				throw new IllegalArgumentException("bad piece rectangle");
			}
			if (Math.abs(dx) > MAX_OFFSET || Math.abs(dz) > MAX_OFFSET || Math.abs(dy) > MAX_OFFSET_Y) {
				throw new IllegalArgumentException("piece too far from its map");
			}
			if (mask != null) {
				if (mask.length() > w * h || mask.isEmpty()) {
					throw new IllegalArgumentException("bad piece mask");
				}
				mask = (BitSet) mask.clone();
			}
		}

		/** A whole rectangle. */
		public Piece(int x, int y, int w, int h, int dx, int dy, int dz) {
			this(x, y, w, h, null, dx, dy, dz);
		}

		/** True when cell (cx, cy) of the map is this piece's. */
		public boolean contains(int cx, int cy) {
			if (cx < this.x || cy < this.y || cx >= this.x + this.w || cy >= this.y + this.h) {
				return false;
			}
			return this.mask == null || this.mask.get((cy - this.y) * this.w + (cx - this.x));
		}

		@Override
		public @Nullable BitSet mask() {
			return this.mask == null ? null : (BitSet) this.mask.clone();
		}
	}

	/**
	 * A stairs cell: it climbs {@code rise} blocks towards {@code dir}, from the cell's own level on its low side.
	 */
	public record Ramp(int x, int y, Dir dir, int rise) {
		public Ramp {
			if (dir == null || rise < 1 || rise > MAX_RISE) {
				throw new IllegalArgumentException("bad ramp");
			}
		}
	}

	private final int w;
	private final int h;
	private final List<Piece> pieces;
	/** Per cell, row by row: the index of the first piece that lists it, -1 for none. */
	private final short[] pieceOf;
	private final int[] levels;
	private final Map<Integer, Ramp> ramps;
	/** Per cell, row by row: its ceiling's level (the first solid block over the room), or null for the builder's default. */
	private final int @Nullable [] ceilings;

	private MapLayout(int w, int h, List<Piece> pieces, int[] levels, List<Ramp> ramps, int @Nullable [] ceilings) {
		this.w = w;
		this.h = h;
		this.pieces = List.copyOf(pieces);
		this.levels = levels.clone();
		this.ceilings = ceilings == null ? null : ceilings.clone();
		this.pieceOf = new short[w * h];
		Arrays.fill(this.pieceOf, (short) -1);
		for (int p = this.pieces.size() - 1; p >= 0; p--) {
			Piece piece = this.pieces.get(p);
			for (int cy = piece.y(); cy < piece.y() + piece.h(); cy++) {
				for (int cx = piece.x(); cx < piece.x() + piece.w(); cx++) {
					if (piece.contains(cx, cy)) {
						this.pieceOf[cy * w + cx] = (short) p;
					}
				}
			}
		}
		Map<Integer, Ramp> byCell = new TreeMap<>();
		for (Ramp ramp : ramps) {
			if (byCell.put(ramp.y() * w + ramp.x(), ramp) != null) {
				throw new IllegalArgumentException("two ramps on one cell");
			}
		}
		this.ramps = Collections.unmodifiableMap(byCell);
	}

	/**
	 * A layout for a map of {@code w × h} cells.
	 *
	 * @param pieces its pieces, at least one; their rectangles lie inside the map
	 * @param levels each cell's level, row by row ({@code w × h} values), or null for all 0
	 * @param ramps  its stairs cells, inside the map
	 */
	public static MapLayout of(int w, int h, List<Piece> pieces, int @Nullable [] levels, List<Ramp> ramps) {
		if (w < 1 || h < 1) {
			throw new IllegalArgumentException("map size must be positive");
		}
		if (pieces.isEmpty() || pieces.size() > MAX_PIECES) {
			throw new IllegalArgumentException("a layout has 1 to " + MAX_PIECES + " pieces");
		}
		for (Piece piece : pieces) {
			if (piece.x() + piece.w() > w || piece.y() + piece.h() > h) {
				throw new IllegalArgumentException("piece outside its map");
			}
		}
		int[] lv = levels == null ? new int[w * h] : levels;
		if (lv.length != w * h) {
			throw new IllegalArgumentException("one level per cell");
		}
		for (int level : lv) {
			if (level < 0 || level > MAX_LEVEL) {
				throw new IllegalArgumentException("level out of range");
			}
		}
		for (Ramp ramp : ramps) {
			if (ramp.x() < 0 || ramp.y() < 0 || ramp.x() >= w || ramp.y() >= h) {
				throw new IllegalArgumentException("ramp outside its map");
			}
		}
		return new MapLayout(w, h, pieces, lv, ramps, null);
	}

	/**
	 * The same layout with each cell's ceiling (D5): the level of the first solid block over its room, in blocks above its
	 * piece's feet level, row by row ({@code w × h} values, each 1 to {@link #MAX_CEILING}); null for the builder's default.
	 */
	public MapLayout withCeilings(int @Nullable [] ceilings) {
		if (ceilings != null) {
			if (ceilings.length != this.w * this.h) {
				throw new IllegalArgumentException("one ceiling per cell");
			}
			for (int c : ceilings) {
				if (c < 1 || c > MAX_CEILING) {
					throw new IllegalArgumentException("ceiling out of range");
				}
			}
		}
		return new MapLayout(this.w, this.h, this.pieces, this.levels, new ArrayList<>(this.ramps.values()), ceilings);
	}

	/** One piece covering the whole map at its placement, with levels and ramps. */
	public static MapLayout flat(int w, int h, int @Nullable [] levels, List<Ramp> ramps) {
		return of(w, h, List.of(new Piece(0, 0, w, h, 0, 0, 0)), levels, ramps);
	}

	public int w() {
		return this.w;
	}

	public int h() {
		return this.h;
	}

	public List<Piece> pieces() {
		return this.pieces;
	}

	/** The first piece that lists cell (cx, cy), or null for a cell outside the map or in no piece. */
	public @Nullable Piece pieceAt(int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= this.w || cy >= this.h) {
			return null;
		}
		int p = this.pieceOf[cy * this.w + cx];
		return p < 0 ? null : this.pieces.get(p);
	}

	/** A cell's level, in blocks above its piece's feet level; 0 outside the map. */
	public int level(int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= this.w || cy >= this.h) {
			return 0;
		}
		return this.levels[cy * this.w + cx];
	}

	/** A cell's ceiling level (the first solid block over it, above its piece's feet level), or -1 for the default. */
	public int ceiling(int cx, int cy) {
		if (this.ceilings == null || cx < 0 || cy < 0 || cx >= this.w || cy >= this.h) {
			return -1;
		}
		return this.ceilings[cy * this.w + cx];
	}

	/** True when the layout sets the ceilings. */
	public boolean hasCeilings() {
		return this.ceilings != null;
	}

	/**
	 * The least room over any cell: its ceiling less its floor (a ramp's top), the most a column may stand anywhere in the
	 * map; -1 without ceilings.
	 */
	public int headroom() {
		if (this.ceilings == null) {
			return -1;
		}
		int least = Integer.MAX_VALUE;
		for (int i = 0; i < this.ceilings.length; i++) {
			Ramp ramp = this.ramps.get(i);
			least = Math.min(least, this.ceilings[i] - this.levels[i] - (ramp == null ? 0 : ramp.rise()));
		}
		return least;
	}

	/** The ramp on a cell, or null. */
	public @Nullable Ramp ramp(int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= this.w || cy >= this.h) {
			return null;
		}
		return this.ramps.get(cy * this.w + cx);
	}

	public Collection<Ramp> ramps() {
		return this.ramps.values();
	}

	/** True when the layout says nothing a map without one doesn't: one whole piece at 0, all levels 0, no ramps. */
	public boolean trivial() {
		return this.wholeAtZero() && this.flatLevels() && this.ramps.isEmpty() && this.ceilings == null;
	}

	private boolean wholeAtZero() {
		if (this.pieces.size() != 1) {
			return false;
		}
		Piece only = this.pieces.get(0);
		return only.x() == 0 && only.y() == 0 && only.w() == this.w && only.h() == this.h && only.mask == null && only.dx() == 0
				&& only.dy() == 0 && only.dz() == 0;
	}

	private boolean flatLevels() {
		for (int level : this.levels) {
			if (level != 0) {
				return false;
			}
		}
		return true;
	}

	// ---- JSON ----

	/** Adds this layout's keys to a map's WorldIndex entry, each only when it differs from the default. */
	void writeTo(JsonObject item) {
		if (!this.wholeAtZero()) {
			JsonArray pieces = new JsonArray();
			for (Piece piece : this.pieces) {
				JsonObject o = new JsonObject();
				o.addProperty("x", piece.x());
				o.addProperty("y", piece.y());
				o.addProperty("w", piece.w());
				o.addProperty("h", piece.h());
				if (piece.mask != null) {
					o.add("mask", runs(piece.mask, piece.w() * piece.h()));
				}
				o.addProperty("dx", piece.dx());
				o.addProperty("dy", piece.dy());
				o.addProperty("dz", piece.dz());
				pieces.add(o);
			}
			item.add("pieces", pieces);
		}
		if (!this.flatLevels()) {
			JsonArray levels = new JsonArray();
			int i = 0;
			while (i < this.levels.length) {
				int j = i;
				while (j < this.levels.length && this.levels[j] == this.levels[i]) {
					j++;
				}
				levels.add(this.levels[i]);
				levels.add(j - i);
				i = j;
			}
			item.add("levels", levels);
		}
		if (!this.ramps.isEmpty()) {
			JsonArray ramps = new JsonArray();
			for (Ramp ramp : this.ramps.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("x", ramp.x());
				o.addProperty("y", ramp.y());
				o.addProperty("dir", ramp.dir().id());
				o.addProperty("rise", ramp.rise());
				ramps.add(o);
			}
			item.add("ramps", ramps);
		}
		if (this.ceilings != null) {
			item.add("ceilings", valueRuns(this.ceilings));
		}
	}

	/** Values as (value, count) pairs, row by row. */
	private static JsonArray valueRuns(int[] values) {
		JsonArray out = new JsonArray();
		int i = 0;
		while (i < values.length) {
			int j = i;
			while (j < values.length && values[j] == values[i]) {
				j++;
			}
			out.add(values[i]);
			out.add(j - i);
			i = j;
		}
		return out;
	}

	/** A mask as alternating runs of cells, out of the piece first (the first run may be 0). */
	private static JsonArray runs(BitSet mask, int cells) {
		JsonArray out = new JsonArray();
		boolean in = false;
		int i = 0;
		while (i < cells) {
			int j = i;
			while (j < cells && mask.get(j) == in) {
				j++;
			}
			out.add(j - i);
			in = !in;
			i = j;
		}
		return out;
	}

	/** The layout a map's WorldIndex entry describes, or null when it has none of the keys (or only defaults). */
	static @Nullable MapLayout read(DataReader map, int w, int h) throws DataException {
		if (!map.has("pieces") && !map.has("levels") && !map.has("ramps") && !map.has("ceilings")) {
			return null;
		}
		List<Piece> pieces = new ArrayList<>();
		if (map.has("pieces")) {
			List<DataReader> items = DataReader.objects(map.array("pieces"), map.path("pieces"));
			if (items.isEmpty() || items.size() > MAX_PIECES) {
				throw new DataException(map.path("pieces"), "a layout has 1 to " + MAX_PIECES + " pieces");
			}
			for (DataReader p : items) {
				p.allowOnly(PIECE_FIELDS);
				int pw = p.integer("w", 1, w);
				int ph = p.integer("h", 1, h);
				BitSet mask = null;
				if (p.has("mask")) {
					mask = mask(p.array("mask"), p.path("mask"), pw * ph);
				}
				try {
					pieces.add(new Piece(p.integer("x", 0, w - 1), p.integer("y", 0, h - 1), pw, ph, mask, p.integer("dx", -MAX_OFFSET, MAX_OFFSET),
							p.integer("dy", -MAX_OFFSET_Y, MAX_OFFSET_Y), p.integer("dz", -MAX_OFFSET, MAX_OFFSET)));
				} catch (IllegalArgumentException e) {
					throw new DataException(p.path(), e.getMessage());
				}
			}
		} else {
			pieces.add(new Piece(0, 0, w, h, 0, 0, 0));
		}
		int[] levels = null;
		if (map.has("levels")) {
			int[] runs = DataReader.intArray(map.array("levels"), map.path("levels"), 0, Math.max(w * h, MAX_LEVEL));
			if (runs.length % 2 != 0) {
				throw new DataException(map.path("levels"), "levels come in (level, count) pairs");
			}
			levels = new int[w * h];
			int i = 0;
			for (int r = 0; r < runs.length; r += 2) {
				int level = runs[r];
				int count = runs[r + 1];
				if (level > MAX_LEVEL || count < 1 || i + count > levels.length) {
					throw new DataException(map.path("levels"), "bad level run");
				}
				Arrays.fill(levels, i, i + count, level);
				i += count;
			}
			if (i != levels.length) {
				throw new DataException(map.path("levels"), "one level per cell");
			}
		}
		List<Ramp> ramps = new ArrayList<>();
		if (map.has("ramps")) {
			for (DataReader r : DataReader.objects(map.array("ramps"), map.path("ramps"))) {
				r.allowOnly(RAMP_FIELDS);
				Dir dir = Dir.byId(r.string("dir"));
				if (dir == null) {
					throw new DataException(r.path("dir"), "unknown direction");
				}
				ramps.add(new Ramp(r.integer("x", 0, w - 1), r.integer("y", 0, h - 1), dir, r.integer("rise", 1, MAX_RISE)));
			}
		}
		int[] ceilings = null;
		if (map.has("ceilings")) {
			int[] runs = DataReader.intArray(map.array("ceilings"), map.path("ceilings"), 0, Math.max(w * h, MAX_CEILING));
			if (runs.length % 2 != 0) {
				throw new DataException(map.path("ceilings"), "ceilings come in (ceiling, count) pairs");
			}
			ceilings = new int[w * h];
			int i = 0;
			for (int r = 0; r < runs.length; r += 2) {
				if (runs[r] < 1 || runs[r] > MAX_CEILING || runs[r + 1] < 1 || i + runs[r + 1] > ceilings.length) {
					throw new DataException(map.path("ceilings"), "bad ceiling run");
				}
				Arrays.fill(ceilings, i, i + runs[r + 1], runs[r]);
				i += runs[r + 1];
			}
			if (i != ceilings.length) {
				throw new DataException(map.path("ceilings"), "one ceiling per cell");
			}
		}
		try {
			MapLayout layout = of(w, h, pieces, levels, ramps).withCeilings(ceilings);
			return layout.trivial() ? null : layout;
		} catch (IllegalArgumentException e) {
			throw new DataException(map.path(), e.getMessage());
		}
	}

	private static BitSet mask(JsonArray array, String path, int cells) throws DataException {
		int[] runs = DataReader.intArray(array, path, 0, cells);
		BitSet mask = new BitSet(cells);
		boolean in = false;
		int i = 0;
		for (int r = 0; r < runs.length; r++) {
			if (runs[r] == 0 && r > 0) {
				throw new DataException(path, "empty run");
			}
			if (i + runs[r] > cells) {
				throw new DataException(path, "mask longer than its rectangle");
			}
			if (in) {
				mask.set(i, i + runs[r]);
			}
			i += runs[r];
			in = !in;
		}
		if (i != cells) {
			throw new DataException(path, "mask shorter than its rectangle");
		}
		if (mask.isEmpty()) {
			throw new DataException(path, "empty mask");
		}
		return mask;
	}
}
