package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.SwapSite;
import dev.kantobridge.world.Hashes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * Tier C planning (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4 stage 4, V7; {@code kb-shapes/5}): bounded structures as
 * templates, from tile ids alone. A template is one structure region of the measured runs ({@link Structures#measured})
 * on an outdoor map that a door folds into (a building): not a pure repeat, at most {@code templateMax} art tiles along
 * either side of its cell-aligned box, touching no swap site and no cell unit.
 *
 * <p>Its entry holds what any client needs to rebuild it from its own art, and is written to the shape index's
 * {@code templates}: the tile grid ({@code w × d} tiles, -1 outside the structure; one cell row more to the south when its
 * doors lie there), the door cells, the ground tile, the outdoor flag, and per column the Tier A keys it otherwise is,
 * bottom-up. Those fix its volume: a template's blocks, and so its collision, are exactly the Tier A columns', and they
 * are its fallback when its geometry fails its gate. Signature: the first 16 hex digits of the SHA-256 of the canonical
 * entry. Every block of the volume becomes a {@code tmpl} key on a {@code shape_full} carrier.
 */
public final class Templates {
	/** The entry's format. */
	public static final int FORMAT = 1;

	/**
	 * One template placed on a map.
	 *
	 * @param x0 the grid's west tile column on the map
	 * @param y0 the grid's north tile row on the map
	 */
	public record Placed(String sig, int x0, int y0, JsonObject entry) {
	}

	private Templates() {
	}

	/**
	 * Plans a map's templates over its columns: their keys become {@code tmpl} keys on {@code shape_full} carriers.
	 *
	 * @param runs   the map's measured runs
	 * @param keys   per art tile, its column's keys (changed in place)
	 * @param blocks per art tile, its column's blocks
	 * @param mask   per art tile, its {@code shape_full} blocks (changed in place)
	 * @param units  the map's cell units
	 * @param ground the map's ground tile
	 */
	static List<Placed> plan(SceneMap map, int atlas, ShapeParams params, List<Structures.MeasuredRun> runs, ShapeKey[][] keys, int[] blocks,
			int[] mask, List<Units.Cell> units, int ground) {
		List<Placed> out = new ArrayList<>();
		int per = map.perCell();
		if (!params.tierC() || !map.surface() || !map.env().outdoor() || per < 1) {
			return out;
		}
		int cols = map.artCols();
		int rows = map.artRows();
		boolean[] busy = busyCells(map, units);
		Map<Integer, List<Structures.MeasuredRun>> byRegion = new TreeMap<>();
		for (Structures.MeasuredRun run : runs) {
			if (run.region() >= 0) {
				byRegion.computeIfAbsent(run.region(), k -> new ArrayList<>()).add(run);
			}
		}
		for (List<Structures.MeasuredRun> region : byRegion.values()) {
			Placed placed = template(map, atlas, params, region, keys, blocks, busy, ground, per, cols, rows);
			if (placed == null) {
				continue;
			}
			for (Structures.MeasuredRun run : region) {
				for (int ty = run.n(); ty <= run.f(); ty++) {
					int i = ty * cols + run.tx();
					ShapeKey[] column = new ShapeKey[blocks[i]];
					for (int k = 0; k < column.length; k++) {
						column[k] = ShapeKey.tmpl(atlas, placed.sig(), run.tx() - placed.x0(), k, ty - placed.y0());
					}
					keys[i] = column;
					mask[i] = (1 << blocks[i]) - 1;
				}
			}
			out.add(placed);
		}
		return out;
	}

	/** Per cell: part of a swap site or of a cell unit (a structure touching one stays Tier A). */
	private static boolean[] busyCells(SceneMap map, List<Units.Cell> units) {
		boolean[] busy = new boolean[map.w() * map.h()];
		for (SwapSite site : map.swapSites()) {
			for (SwapSite.SwapCell cell : site.cells()) {
				busy[cell.y() * map.w() + cell.x()] = true;
			}
		}
		for (Units.Cell unit : units) {
			busy[unit.cy() * map.w() + unit.cx()] = true;
		}
		return busy;
	}

	/** One region's template, or null when it isn't one. */
	private static @Nullable Placed template(SceneMap map, int atlas, ShapeParams params, List<Structures.MeasuredRun> region, ShapeKey[][] keys,
			int[] blocks, boolean[] busy, int ground, int per, int cols, int rows) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		TreeSet<Long> doors = new TreeSet<>();
		boolean structure = false;
		for (Structures.MeasuredRun run : region) {
			structure |= !run.pureRepeat();
			minX = Math.min(minX, run.tx());
			maxX = Math.max(maxX, run.tx());
			minY = Math.min(minY, run.n());
			maxY = Math.max(maxY, run.f());
			if (run.doorFold()) {
				// The door cell directly south of the run.
				doors.add(cellKey(run.tx() / per, (run.f() + 1) / per));
			}
			for (int ty = run.n(); ty <= run.f(); ty++) {
				if (busy[(ty / per) * map.w() + run.tx() / per]) {
					return null;
				}
			}
		}
		if (!structure || doors.isEmpty()) {
			return null;
		}
		int x0 = (minX / per) * per;
		int y0 = (minY / per) * per;
		int x1 = (maxX / per + 1) * per;
		int y1 = (maxY / per + 1) * per;
		for (long door : doors) {
			y1 = Math.max(y1, (cellY(door) + 1) * per);
		}
		int w = x1 - x0;
		int d = y1 - y0;
		if (w > params.templateMax() || d > params.templateMax() || x1 > cols || y1 > rows) {
			return null;
		}
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		boolean[] inside = new boolean[w * d];
		for (Structures.MeasuredRun run : region) {
			for (int ty = run.n(); ty <= run.f(); ty++) {
				inside[(ty - y0) * w + run.tx() - x0] = true;
			}
		}
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < w; x++) {
				int tx = x0 + x;
				int ty = y0 + y;
				boolean door = doors.contains(cellKey(tx / per, ty / per));
				tiles.add(inside[y * w + x] || door ? map.tile(tx, ty) : -1);
				JsonArray column = new JsonArray();
				if (inside[y * w + x]) {
					int i = ty * cols + tx;
					int own = map.tile(tx, ty);
					for (int k = 0; k < blocks[i]; k++) {
						ShapeKey key = keys[i] != null && k < keys[i].length ? keys[i][k] : null;
						// A plain block of the position's own tile is that tile's cube, made explicit.
						column.add((key != null ? key : ShapeKey.cube(atlas, own, own, own, own, own, own)).text());
					}
				}
				columns.add(column);
			}
		}
		JsonArray doorCells = new JsonArray();
		for (long door : doors) {
			JsonArray cell = new JsonArray();
			cell.add(cellX(door) - x0 / per);
			cell.add(cellY(door) - y0 / per);
			doorCells.add(cell);
		}
		JsonObject entry = new JsonObject();
		entry.addProperty("template", FORMAT);
		entry.addProperty("atlas", atlas);
		entry.addProperty("w", w);
		entry.addProperty("d", d);
		entry.addProperty("perCell", per);
		entry.add("tiles", tiles);
		entry.add("doors", doorCells);
		entry.addProperty("ground", ground);
		entry.addProperty("outdoor", true);
		entry.add("columns", columns);
		return new Placed(signature(entry), x0, y0, entry);
	}

	/** A template entry's signature: the first 16 hex digits of the SHA-256 of its canonical JSON (without {@code sig}). */
	public static String signature(JsonObject entry) {
		JsonObject copy = entry.deepCopy();
		copy.remove("sig");
		return Hashes.sha256(ProtoJson.write(copy)).substring(0, 16);
	}

	/** The shape index's {@code templates}: every distinct entry, with its {@code sig}, sorted by it. */
	static JsonArray indexEntries(Map<String, JsonObject> bySig) {
		JsonArray out = new JsonArray();
		for (Map.Entry<String, JsonObject> e : new TreeMap<>(bySig).entrySet()) {
			JsonObject entry = e.getValue().deepCopy();
			entry.addProperty("sig", e.getKey());
			out.add(entry);
		}
		return out;
	}

	private static long cellKey(int cx, int cy) {
		return (long) cy << 32 | (cx & 0xFFFFFFFFL);
	}

	private static int cellX(long key) {
		return (int) key;
	}

	private static int cellY(long key) {
		return (int) (key >> 32);
	}

	/** A template entry, read: what the geometry needs. */
	public record Entry(String sig, int atlas, int w, int d, int perCell, int[] tiles, int[][] doors, int ground, boolean outdoor,
			ShapeKey[][] columns, @Nullable JsonObject band, @Nullable JsonObject hull) {
		/** A V7 template (no band table). */
		public Entry(String sig, int atlas, int w, int d, int perCell, int[] tiles, int[][] doors, int ground, boolean outdoor,
				ShapeKey[][] columns) {
			this(sig, atlas, w, d, perCell, tiles, doors, ground, outdoor, columns, null, null);
		}

		/** A V7 template or a band-table building (P2). */
		public Entry(String sig, int atlas, int w, int d, int perCell, int[] tiles, int[][] doors, int ground, boolean outdoor,
				ShapeKey[][] columns, @Nullable JsonObject band) {
			this(sig, atlas, w, d, perCell, tiles, doors, ground, outdoor, columns, band, null);
		}

		/** The profile's section that sizes this template per position (a band building's, P2; a two-cell hull's, P3), or null. */
		public @Nullable JsonObject volume() {
			return this.band != null ? this.band : this.hull;
		}

		/** The tile at grid position (x, y), -1 outside the structure and its doors. */
		public int tile(int x, int y) {
			return x < 0 || y < 0 || x >= this.w || y >= this.d ? -1 : this.tiles[y * this.w + x];
		}

		/** Blocks of the column at (x, y): its volume (a band building's or a two-cell hull's solid blocks, P2, P3). */
		public int blocks(int x, int y) {
			JsonObject volume = this.volume();
			if (volume != null && volume.has("solid")) {
				return volume.getAsJsonArray("solid").get(y * this.w + x).getAsInt();
			}
			ShapeKey[] column = this.columns[y * this.w + x];
			return column == null ? 0 : column.length;
		}

		/**
		 * Blocks the template's keys stand at (x, y): its column, or for a band building a doorway's or an overhang's decor
		 * blocks (P2), whichever is higher.
		 */
		public int keysAt(int x, int y) {
			int blocks = this.blocks(x, y);
			JsonObject volume = this.volume();
			if (volume != null && volume.has("air")) {
				blocks = Math.max(blocks, volume.getAsJsonArray("air").get(y * this.w + x).getAsInt());
			}
			return blocks;
		}

		/** The tallest column of keys. */
		public int height() {
			int max = 0;
			for (int y = 0; y < this.d; y++) {
				for (int x = 0; x < this.w; x++) {
					max = Math.max(max, this.keysAt(x, y));
				}
			}
			return max;
		}

		/** True when the cell (cx, cy) of the grid is a door cell. */
		public boolean door(int cx, int cy) {
			for (int[] door : this.doors) {
				if (door[0] == cx && door[1] == cy) {
					return true;
				}
			}
			return false;
		}

		/** Reads an index entry (its {@code sig} checked against its content). */
		public static Entry of(JsonObject json) {
			String sig = json.get("sig").getAsString();
			if (!sig.equals(signature(json)) || json.get("template").getAsInt() != FORMAT) {
				throw new IllegalArgumentException("a template whose signature doesn't match its content");
			}
			int w = json.get("w").getAsInt();
			int d = json.get("d").getAsInt();
			JsonArray tileArray = json.getAsJsonArray("tiles");
			JsonArray columnArray = json.getAsJsonArray("columns");
			if (w < 1 || d < 1 || tileArray.size() != w * d || columnArray.size() != w * d) {
				throw new IllegalArgumentException("a template's grid doesn't match its size");
			}
			int[] tiles = new int[w * d];
			ShapeKey[][] columns = new ShapeKey[w * d][];
			for (int i = 0; i < w * d; i++) {
				tiles[i] = tileArray.get(i).getAsInt();
				JsonArray column = columnArray.get(i).getAsJsonArray();
				if (!column.isEmpty()) {
					columns[i] = new ShapeKey[column.size()];
					for (int k = 0; k < column.size(); k++) {
						columns[i][k] = ShapeKey.parse(column.get(k).getAsString());
					}
				}
			}
			List<int[]> doors = new ArrayList<>();
			for (JsonElement door : json.getAsJsonArray("doors")) {
				JsonArray cell = door.getAsJsonArray();
				doors.add(new int[] {cell.get(0).getAsInt(), cell.get(1).getAsInt()});
			}
			return new Entry(sig, json.get("atlas").getAsInt(), w, d, json.get("perCell").getAsInt(), tiles, doors.toArray(new int[0][]),
					json.get("ground").getAsInt(), json.get("outdoor").getAsBoolean(), columns,
					json.has("band") ? json.getAsJsonObject("band").deepCopy() : null, json.has("hull") ? json.getAsJsonObject("hull").deepCopy() : null);
		}
	}
}
