package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One exported map (protocol/export/map.schema.json): the map record plus gen1-bridge's derived fields. Sizes are in
 * Gen 1 blocks ({@code width}/{@code height}); coordinates of warps, signs and objects are cells.
 *
 * @param blocks  {@code width*height} block ids, row-major
 * @param cells   per-cell flags ({@link CellFlags}), {@code cells[cy][cx]}, {@code height*2} rows of {@code width*2}
 * @param parent      interiors: the warp in another map that first leads here; null for surface or unreachable maps
 * @param lastMap     interiors: the outdoor map their {@code LAST_MAP} exits return to
 * @param borderDrawn the block the engine really draws around the map (outdoors: the player's void fill), or null when
 *                    it draws none; {@code border} when an older export doesn't say (the voxel plan's V2)
 */
public record ExportMap(String id, int index, String tileset, int width, int height, int[] blocks, int border, boolean surface,
		boolean outside, Map<Direction, Connection> connections, List<Warp> warps, List<Sign> signs, List<MapObject> objects,
		int[][] cells, @Nullable Parent parent, @Nullable String lastMap, boolean dark, String palette, @Nullable Integer borderDrawn) {

	/** Connection directions, as the export names them. */
	public enum Direction {
		NORTH,
		SOUTH,
		WEST,
		EAST;

		public String id() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}

		public Direction opposite() {
			return switch (this) {
				case NORTH -> SOUTH;
				case SOUTH -> NORTH;
				case WEST -> EAST;
				case EAST -> WEST;
			};
		}
	}

	/** A connection: the neighbour and its offset in Gen 1 blocks. */
	public record Connection(String map, int offset) {
	}

	/** A warp entry; {@code warp} is the 1-based index into {@code to}'s warps; {@code to} may be {@code LAST_MAP}. */
	public record Warp(int x, int y, String to, int warp) {
	}

	public record Sign(int x, int y) {
	}

	public record Parent(String map, int warp, int x, int y) {
	}

	/** A map object; {@code movement} is {@code WALK} or {@code STAY}; exactly one payload or none. */
	public record MapObject(int index, String name, String sprite, int x, int y, String movement, String range, boolean hidden,
			@Nullable String item, boolean trainer, boolean pokemon) {
		public boolean stays() {
			return "STAY".equals(this.movement);
		}
	}

	private static final Pattern WARP_TARGET = Pattern.compile("^([A-Z0-9_]{1,64}|LAST_MAP)$");

	public ExportMap {
		connections = Collections.unmodifiableMap(connections);
		warps = List.copyOf(warps);
		signs = List.copyOf(signs);
		objects = List.copyOf(objects);
	}

	/** A map whose drawn border is its own border block. */
	public ExportMap(String id, int index, String tileset, int width, int height, int[] blocks, int border, boolean surface, boolean outside,
			Map<Direction, Connection> connections, List<Warp> warps, List<Sign> signs, List<MapObject> objects, int[][] cells,
			@Nullable Parent parent, @Nullable String lastMap, boolean dark, String palette) {
		this(id, index, tileset, width, height, blocks, border, surface, outside, connections, warps, signs, objects, cells, parent, lastMap,
				dark, palette, border);
	}

	/** Width in cells. */
	public int cellWidth() {
		return this.width * 2;
	}

	/** Height in cells. */
	public int cellHeight() {
		return this.height * 2;
	}

	/** Flags of cell (cx, cy); 0 outside the map. */
	public int flags(int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= this.cellWidth() || cy >= this.cellHeight()) {
			return 0;
		}
		return this.cells[cy][cx];
	}

	/** Block id at Gen 1 block (bx, by). */
	public int block(int bx, int by) {
		return this.blocks[by * this.width + bx];
	}

	/** The 1-based warp {@code number}, or null. */
	public @Nullable Warp warp(int number) {
		return number >= 1 && number <= this.warps.size() ? this.warps.get(number - 1) : null;
	}

	static ExportMap parse(DataReader r) throws DataException {
		String id = r.string("id", DataReader.MAP_ID);
		int width = r.integer("width", 1, 255);
		int height = r.integer("height", 1, 255);
		int[] blocks = DataReader.intArray(r.array("blocks"), r.path("blocks"), 0, 255);
		if (blocks.length != width * height) {
			throw new DataException(r.path("blocks"), "length is not width*height");
		}
		Map<Direction, Connection> connections = new EnumMap<>(Direction.class);
		DataReader conn = r.object("connections");
		for (Direction direction : Direction.values()) {
			if (conn.has(direction.id())) {
				DataReader c = conn.object(direction.id());
				connections.put(direction, new Connection(c.string("map", DataReader.MAP_ID), c.integer("offset", -255, 255)));
			}
		}
		List<Warp> warps = new ArrayList<>();
		for (DataReader w : DataReader.objects(r.array("warps"), r.path("warps"))) {
			warps.add(new Warp(w.integer("x", -4096, 4096), w.integer("y", -4096, 4096), w.string("to", WARP_TARGET),
					w.integer("warp", 1, 255)));
		}
		List<Sign> signs = new ArrayList<>();
		for (DataReader s : DataReader.objects(r.array("signs"), r.path("signs"))) {
			signs.add(new Sign(s.integer("x", -4096, 4096), s.integer("y", -4096, 4096)));
		}
		List<MapObject> objects = new ArrayList<>();
		for (DataReader o : DataReader.objects(r.array("objects"), r.path("objects"))) {
			String movement = o.string("movement");
			if (!movement.equals("WALK") && !movement.equals("STAY")) {
				throw new DataException(o.path("movement"), "unknown movement");
			}
			objects.add(new MapObject(o.integer("index", 1, 255), o.string("name", DataReader.SYMBOL),
					o.string("sprite", DataReader.SYMBOL), o.integer("x", -4096, 4096), o.integer("y", -4096, 4096), movement,
					o.string("range", DataReader.SYMBOL), o.bool("hidden"), o.has("item") ? o.string("item", DataReader.SYMBOL) : null,
					o.has("trainer"), o.has("pokemon")));
		}
		JsonArray rows = r.array("cells");
		int cellWidth = width * 2;
		int cellHeight = height * 2;
		if (rows.size() != cellHeight) {
			throw new DataException(r.path("cells"), "row count is not height*2");
		}
		int[][] cells = new int[cellHeight][];
		for (int y = 0; y < cellHeight; y++) {
			String rowPath = r.path("cells") + "/" + y;
			if (!rows.get(y).isJsonArray()) {
				throw new DataException(rowPath, "not an array");
			}
			cells[y] = DataReader.intArray(rows.get(y).getAsJsonArray(), rowPath, 0, 65535);
			if (cells[y].length != cellWidth) {
				throw new DataException(rowPath, "row length is not width*2");
			}
		}
		Parent parent = null;
		DataReader p = r.optionalObject("parent");
		if (p != null) {
			parent = new Parent(p.string("map", DataReader.MAP_ID), p.integer("warp", 1, 255), p.integer("x", -4096, 4096),
					p.integer("y", -4096, 4096));
		}
		int border = r.integer("border", 0, 255);
		Integer borderDrawn = !r.has("borderDrawn") ? Integer.valueOf(border) : r.isNull("borderDrawn") ? null : r.integer("borderDrawn", 0, 255);
		return new ExportMap(id, r.integer("index", 0, 255), r.string("tileset", DataReader.SYMBOL), width, height, blocks, border,
				r.bool("surface"), r.bool("outside"), connections, warps, signs, objects, cells, parent, r.optionalString("lastMap", DataReader.MAP_ID),
				r.bool("dark"), r.string("palette", DataReader.SYMBOL), borderDrawn);
	}
}
