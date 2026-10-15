package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One Gen 2 map (protocol/export/g2map.schema.json; MG9 G2D, docs/specs/GEN2_BRIDGE_PLAN.md §6): its blocks ({@code w x h}
 * 32-px blocks, each 2x2 cells, as the import holds them), border block, the art variant it is drawn with
 * ({@link G2Tiles}), where it is, connections in blocks, warps, events and objects in cells. Map ids carry the
 * {@code G2_} prefix. ROM-derived: never logged.
 *
 * @param env     the engine's environment (TOWN and ROUTE are outdoors)
 * @param daytime the daytime its art is baked at
 * @param area    {@code johto} or {@code kanto}, by the engine's landmark rule
 * @param dark    black until Flash
 */
public record G2Map(String id, int w, int h, int[] blocks, int border, String variant, String tileset, String env, @Nullable String palette,
		String daytime, int group, int num, String area, boolean dark, @Nullable String fishGroup, List<Connection> connections, List<Warp> warps,
		List<Event> coords, List<Event> signs, List<MapObject> objects) {
	public static final Pattern VARIANT = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
	private static final Pattern SYM = Pattern.compile("^[A-Z0-9_]{1,64}$");
	private static final Pattern ENV = Pattern.compile("^[A-Z_]{1,32}$");
	private static final Set<String> DIRS = Set.of("north", "south", "east", "west");
	private static final Set<String> DAYTIMES = Set.of("MORN", "DAY", "NITE");
	private static final Set<String> AREAS = Set.of("johto", "kanto");
	private static final Set<String> OBJECT_KINDS = Set.of("person", "trainer", "item", "boulder", "rock");
	/** The environments that are outdoors (the engine's Map.lua:130-140). */
	public static final Set<String> OUTDOOR = Set.of("TOWN", "ROUTE");
	/** The environments whose every non-ice floor rolls the grass table (FieldMoves.lua:272-289). */
	public static final Set<String> CAVE_LIKE = Set.of("CAVE", "DUNGEON");

	/** A connection: direction ({@code north}, {@code south}, {@code east}, {@code west}), offset in blocks. */
	public record Connection(String dir, String map, int offset) {
	}

	/**
	 * A warp: its cell and destination; {@code warp} is the destination's warp, 0-based; {@code dynamic} for the destination
	 * remembered at run time (warp -1), {@code to} then the map's own fallback or null.
	 */
	public record Warp(int x, int y, @Nullable String to, int warp, boolean dynamic) {
	}

	/** A coord event ({@code value}: its scene) or a bg event ({@code value}: its kind), in cells. */
	public record Event(int x, int y, int value) {
	}

	/**
	 * An object: its index ({@code O<lid>} in the story), sprite (null for a variable one, {@code var} its raw id), OBJ
	 * palette override (-1 none), cell, movement byte, kind, event flag (-1 none), hour window (null always there), whether
	 * it keeps its cell, and {@code big} ({@code sym}/{@code asym}) for a two-by-two object.
	 */
	public record MapObject(int lid, @Nullable String sprite, int var, int palette, int x, int y, int movement, String kind, int flag,
			int @Nullable [] hours, boolean stays, @Nullable String big) {
		/** Its name in the story: {@code O<lid>}, as the adapter's toggles and live objects name it (G2C). */
		public String name() {
			return "O" + this.lid;
		}
	}

	/** Width in cells. */
	public int cellWidth() {
		return this.w * 2;
	}

	/** Height in cells. */
	public int cellHeight() {
		return this.h * 2;
	}

	public boolean inBlocks(int bx, int by) {
		return bx >= 0 && by >= 0 && bx < this.w && by < this.h;
	}

	public boolean inCells(int cx, int cy) {
		return cx >= 0 && cy >= 0 && cx < this.cellWidth() && cy < this.cellHeight();
	}

	/** The block the map data has at (bx, by), as stored (0 included). */
	public int dataBlock(int bx, int by) {
		return this.blocks[by * this.w + bx];
	}

	/**
	 * The block drawn at (bx, by): the border block for block 0, as the engine reads it (BorderFill.blockFor); block 0
	 * also collides as a wall ({@link #wallBlock}).
	 */
	public int drawnBlock(int bx, int by) {
		int b = this.dataBlock(bx, by);
		return b == 0 ? this.border : b;
	}

	/** True when (bx, by) holds block 0: the border's art, a wall's collision. */
	public boolean wallBlock(int bx, int by) {
		return this.dataBlock(bx, by) == 0;
	}

	/** True for an outdoor map (TOWN, ROUTE). */
	public boolean outdoor() {
		return OUTDOOR.contains(this.env);
	}

	/** True for a cave or dungeon (every non-ice floor rolls the grass table). */
	public boolean caveLike() {
		return CAVE_LIKE.contains(this.env);
	}

	static G2Map parse(DataReader r) throws DataException {
		String id = r.string("id", DataReader.MAP_ID);
		int w = r.integer("w", 1, 255);
		int h = r.integer("h", 1, 255);
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(r.string("blocks"));
		} catch (IllegalArgumentException e) {
			throw new DataException(r.path("blocks"), "not base64");
		}
		if (raw.length != w * h) {
			throw new DataException(r.path("blocks"), "blocks do not fill the map");
		}
		int[] blocks = new int[raw.length];
		for (int i = 0; i < raw.length; i++) {
			blocks[i] = raw[i] & 0xFF;
		}
		String daytime = r.string("daytime");
		if (!DAYTIMES.contains(daytime)) {
			throw new DataException(r.path("daytime"), "unknown daytime");
		}
		String area = r.string("area");
		if (!AREAS.contains(area)) {
			throw new DataException(r.path("area"), "unknown area");
		}
		List<Connection> connections = new ArrayList<>();
		for (DataReader c : DataReader.objects(r.array("connections"), r.path("connections"))) {
			String dir = c.string("dir");
			if (!DIRS.contains(dir)) {
				throw new DataException(c.path("dir"), "unknown direction");
			}
			connections.add(new Connection(dir, c.string("map", DataReader.MAP_ID), c.integer("offset", -255, 255)));
		}
		List<Warp> warps = new ArrayList<>();
		for (DataReader wr : DataReader.objects(r.array("warps"), r.path("warps"))) {
			boolean dynamic = wr.has("dynamic") && wr.bool("dynamic");
			String to = wr.optionalString("to", DataReader.MAP_ID);
			warps.add(new Warp(wr.integer("x", 0, 511), wr.integer("y", 0, 511), to, wr.integer("warp", 0, 255), dynamic || to == null));
		}
		List<Event> coords = new ArrayList<>();
		for (DataReader e : DataReader.objects(r.array("coords"), r.path("coords"))) {
			coords.add(new Event(e.integer("x", 0, 511), e.integer("y", 0, 511), e.integer("scene", 0, 255)));
		}
		List<Event> signs = new ArrayList<>();
		for (DataReader e : DataReader.objects(r.array("signs"), r.path("signs"))) {
			signs.add(new Event(e.integer("x", 0, 511), e.integer("y", 0, 511), e.integer("kind", 0, 255)));
		}
		List<MapObject> objects = new ArrayList<>();
		for (DataReader o : DataReader.objects(r.array("objects"), r.path("objects"))) {
			String kind = o.string("kind");
			if (!OBJECT_KINDS.contains(kind)) {
				throw new DataException(o.path("kind"), "unknown object kind");
			}
			int[] hours = null;
			if (o.has("hours")) {
				JsonArray hs = o.array("hours");
				hours = DataReader.intArray(hs, o.path("hours"), -1, 255);
				if (hours.length != 2) {
					throw new DataException(o.path("hours"), "two hours");
				}
			}
			String big = o.has("big") ? o.string("big") : null;
			if (big != null && !big.equals("sym") && !big.equals("asym")) {
				throw new DataException(o.path("big"), "sym or asym");
			}
			objects.add(new MapObject(o.integer("lid", 1, 255), o.optionalString("sprite", SYM), o.has("var") ? o.integer("var", 0, 255) : -1,
					o.has("palette") ? o.integer("palette", 0, 7) : -1, o.integer("x", -16, 527), o.integer("y", -16, 527),
					o.integer("movement", 0, 255), kind, o.has("flag") ? o.integer("flag", 0, 65534) : -1, hours, o.bool("stays"), big));
		}
		return new G2Map(id, w, h, blocks, r.integer("border", 0, 255), r.string("variant", VARIANT), r.string("tileset", SYM),
				r.string("env", ENV), r.optionalString("palette", SYM), daytime, r.integer("group", 0, 255), r.integer("num", 0, 255), area,
				r.has("dark") && r.bool("dark"), r.optionalString("fishGroup", SYM), List.copyOf(connections), List.copyOf(warps),
				List.copyOf(coords), List.copyOf(signs), List.copyOf(objects));
	}
}
