package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One Gen 3 map (protocol/export/g3map.schema.json; MG7 G3D, docs/specs/GEN3_BRIDGE_PLAN.md §5): its layout's whole grid in
 * cells ({@code w x h}, 16x16 metatiles) with its true size ({@code trueW x trueH}: a small map is padded and the cells past
 * its true size show the border), each cell's metatile, collision byte and elevation, the border, connections, warps and
 * objects. ROM-derived: never logged.
 *
 * @param coll the engine's collision byte per cell (Gen 2's vocabulary: 0x00 walk, 0x07 blocked, 0x18 tall grass, 0x29
 *             water, 0x71/0x72 doors and warps, 0x82 sign, 0x90 counter, 0xA0-0xA3 ledges; GEN3_BRIDGE_PLAN §5)
 * @param dark a cave that needs Flash (the follow-up's item 8: the map header's cave flag)
 */
public record G3Map(String id, int w, int h, int trueW, int trueH, String pair, int[] mids, int[] coll, int[] elev, Border border,
		List<Connection> connections, List<Warp> warps, List<MapObject> objects, int mapType, String kind, int music, int weather,
		boolean dark) {
	public static final Pattern PAIR = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
	private static final Set<String> DIRS = Set.of("north", "south", "east", "west", "dive", "emerge");
	private static final Set<String> OBJECT_KINDS = Set.of("person", "item", "trainer", "boulder", "berry", "other");
	private static final Pattern KIND = Pattern.compile("^[a-z_]{1,32}$");
	private static final Pattern GFX = Pattern.compile("^[A-Z0-9_]{1,64}$");

	/** A lit map (an export from before item 8, tests). */
	public G3Map(String id, int w, int h, int trueW, int trueH, String pair, int[] mids, int[] coll, int[] elev, Border border,
			List<Connection> connections, List<Warp> warps, List<MapObject> objects, int mapType, String kind, int music, int weather) {
		this(id, w, h, trueW, trueH, pair, mids, coll, elev, border, connections, warps, objects, mapType, kind, music, weather, false);
	}

	/** The border's metatiles, row-major. */
	public record Border(int w, int h, int[] mids) {
	}

	/** A connection: direction ({@code north}, {@code south}, {@code east}, {@code west}, {@code dive}, {@code emerge}), offset in cells. */
	public record Connection(String dir, String map, int offset) {
	}

	/** A warp: its cell and destination ({@code to} null for a dynamic one, set at run time). */
	public record Warp(int x, int y, @Nullable String to, int warp, boolean dynamic) {
	}

	/**
	 * An object template: its local id, sprite, cell, elevation, hide flag (0 none) and kind; {@code stays} whether it keeps
	 * its cell (the engine's movement for it: it stands, turns or steps in place), null when the export doesn't say.
	 */
	public record MapObject(int lid, String gfx, int x, int y, int elev, int flag, String kind, @Nullable Boolean stays) {
		/** Its name in the story: {@code O<lid>}, as the adapter's toggles and live objects name it (G3C). */
		public String name() {
			return "O" + this.lid;
		}
	}

	/** The cell index of {@code (x, y)}, row-major over {@code w}. */
	public int index(int x, int y) {
		return y * this.w + x;
	}

	public boolean inGrid(int x, int y) {
		return x >= 0 && y >= 0 && x < this.w && y < this.h;
	}

	/** True for a cell of the map itself (not its padding). */
	public boolean inMap(int x, int y) {
		return x >= 0 && y >= 0 && x < this.trueW && y < this.trueH;
	}

	public int mid(int x, int y) {
		return this.mids[this.index(x, y)];
	}

	public int coll(int x, int y) {
		return this.coll[this.index(x, y)];
	}

	public int elev(int x, int y) {
		return this.elev[this.index(x, y)];
	}

	static G3Map parse(DataReader r) throws DataException {
		String id = r.string("id", DataReader.MAP_ID);
		int w = r.integer("w", 1, 1023);
		int h = r.integer("h", 1, 1023);
		int trueW = r.integer("trueW", 1, 1023);
		int trueH = r.integer("trueH", 1, 1023);
		if (trueW > w || trueH > h) {
			throw new DataException(r.path("trueW"), "true size past the grid");
		}
		byte[] cells;
		try {
			cells = Base64.getDecoder().decode(r.string("cells"));
		} catch (IllegalArgumentException e) {
			throw new DataException(r.path("cells"), "not base64");
		}
		if (cells.length != w * h * 4) {
			throw new DataException(r.path("cells"), "cells do not fill the grid");
		}
		int n = w * h;
		int[] mids = new int[n];
		int[] coll = new int[n];
		int[] elev = new int[n];
		for (int i = 0; i < n; i++) {
			int mid = (cells[i * 4] & 0xFF) | (cells[i * 4 + 1] & 0xFF) << 8;
			if (mid > 1023) {
				throw new DataException(r.path("cells"), "metatile out of range");
			}
			mids[i] = mid;
			coll[i] = cells[i * 4 + 2] & 0xFF;
			elev[i] = cells[i * 4 + 3] & 0xFF;
		}
		DataReader b = r.object("border");
		int bw = b.integer("w", 1, 16);
		int bh = b.integer("h", 1, 16);
		int[] border = DataReader.intArray(b.array("mids"), b.path("mids"), 0, 1023);
		if (border.length != bw * bh) {
			throw new DataException(b.path("mids"), "border size");
		}
		List<Connection> connections = new ArrayList<>();
		for (DataReader c : DataReader.objects(r.array("connections"), r.path("connections"))) {
			String dir = c.string("dir");
			if (!DIRS.contains(dir)) {
				throw new DataException(c.path("dir"), "unknown direction");
			}
			connections.add(new Connection(dir, c.string("map", DataReader.MAP_ID), c.integer("offset", -1024, 1024)));
		}
		List<Warp> warps = new ArrayList<>();
		for (DataReader wr : DataReader.objects(r.array("warps"), r.path("warps"))) {
			boolean dynamic = wr.has("dynamic") && wr.bool("dynamic");
			String to = wr.optionalString("to", DataReader.MAP_ID);
			warps.add(new Warp(wr.integer("x", 0, 1023), wr.integer("y", 0, 1023), to, wr.integer("warp", 0, 255), dynamic || to == null));
		}
		List<MapObject> objects = new ArrayList<>();
		for (DataReader o : DataReader.objects(r.array("objects"), r.path("objects"))) {
			String kind = o.string("kind");
			if (!OBJECT_KINDS.contains(kind)) {
				throw new DataException(o.path("kind"), "unknown object kind");
			}
			objects.add(new MapObject(o.integer("lid", 1, 254), o.string("gfx", GFX), o.integer("x", -64, 1087), o.integer("y", -64, 1087),
					o.has("elev") ? o.integer("elev", 0, 15) : 0, o.has("flag") ? o.integer("flag", 1, 65534) : 0, kind,
					o.has("stays") ? o.bool("stays") : null));
		}
		return new G3Map(id, w, h, trueW, trueH, r.string("pair", PAIR), mids, coll, elev, new Border(bw, bh, border),
				List.copyOf(connections), List.copyOf(warps), List.copyOf(objects), r.integer("mapType", 0, 255), r.string("kind", KIND),
				r.integer("music", 0, 65535), r.integer("weather", 0, 255), r.has("dark") && r.bool("dark"));
	}
}
