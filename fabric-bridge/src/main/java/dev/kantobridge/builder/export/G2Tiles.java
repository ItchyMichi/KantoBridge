package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A Gen 2 art variant (protocol/export/g2tiles.schema.json; MG9 G2D, docs/specs/GEN2_BRIDGE_PLAN.md §6): a tileset drawn with
 * one palette set and roof at the baked daytime. Per block id its 16 8x8 tiles (row-major, ids {@code page * 1024 + index}
 * into the baked pages, the same addressing as Gen 3's quarters) and its four cells' collision bytes (top left, top right,
 * bottom left, bottom right), and what the engine's own predicates say each byte used here means. ROM-derived: never
 * logged.
 */
public final class G2Tiles {
	/** Blocks per tileset (the engine's, always 128). */
	public static final int BLOCKS = 128;
	private static final Pattern SYM = Pattern.compile("^[A-Z0-9_]{1,64}$");
	private static final Set<String> PERMS = Set.of("land", "water", "wall");
	private static final Set<String> DIRS = Set.of("up", "down", "left", "right");
	private static final Set<String> WARPS = Set.of("door", "stairs", "ladder", "pad", "hole", "edge");

	/**
	 * What one COLL byte means, as the export's predicates say: its permission ({@code land}, {@code water}, {@code wall}),
	 * grass and encounters, ice, whirlpool, trees, waterfall and current, counter, the facings that hop a ledge, the
	 * directions a player standing on a one-way cell may not leave by ({@code sides}), and for a neighbour in a direction
	 * the direction this cell refuses it ({@code neighbor}), the warp kind and a mat's press, and whether arriving faces down.
	 */
	public record Meaning(String perm, boolean grass, boolean longGrass, boolean encounter, boolean ice, boolean whirlpool, boolean cutTree,
			boolean cuttable, boolean headbutt, boolean waterfall, boolean counter, @Nullable String current, List<String> ledge,
			List<String> sides, Map<String, String> neighbor, @Nullable String warp, @Nullable String mat, boolean facesDown) {
		public Meaning {
			ledge = List.copyOf(ledge);
			sides = List.copyOf(sides);
			neighbor = Collections.unmodifiableMap(new TreeMap<>(neighbor));
		}

		/** The meaning of a byte the export doesn't describe: a wall. */
		public static Meaning wall() {
			return new Meaning("wall", false, false, false, false, false, false, false, false, false, false, null, List.of(), List.of(), Map.of(),
					null, null, false);
		}
	}

	private final String id;
	private final String tileset;
	private final @Nullable String roof;
	private final int[][] blocks;
	private final int[][] coll;
	private final Map<Integer, Meaning> meanings;

	private G2Tiles(String id, String tileset, @Nullable String roof, int[][] blocks, int[][] coll, Map<Integer, Meaning> meanings) {
		this.id = id;
		this.tileset = tileset;
		this.roof = roof;
		this.blocks = blocks;
		this.coll = coll;
		this.meanings = Collections.unmodifiableMap(meanings);
	}

	public String id() {
		return this.id;
	}

	public String tileset() {
		return this.tileset;
	}

	public @Nullable String roof() {
		return this.roof;
	}

	/** Blocks this variant describes (block ids 0 .. count-1). */
	public int blockCount() {
		return this.blocks.length;
	}

	/** The tile at (tx, ty) of a block (0-3 each), or -1 for a block the variant lacks. */
	public int tile(int block, int tx, int ty) {
		if (block < 0 || block >= this.blocks.length) {
			return -1;
		}
		return this.blocks[block][ty * 4 + tx];
	}

	/** The COLL byte of cell (qx, qy) of a block (0-1 each), or 0xFF for a block the variant lacks. */
	public int coll(int block, int qx, int qy) {
		if (block < 0 || block >= this.coll.length) {
			return 0xFF;
		}
		return this.coll[block][qy * 2 + qx];
	}

	/** What a COLL byte means; a byte the export doesn't describe is a wall. */
	public Meaning meaning(int coll) {
		Meaning m = this.meanings.get(coll);
		return m == null ? Meaning.wall() : m;
	}

	/** True when the export describes this COLL byte. */
	public boolean knows(int coll) {
		return this.meanings.containsKey(coll);
	}

	static G2Tiles parse(DataReader r) throws DataException {
		String id = r.string("id", G2Map.VARIANT);
		JsonArray rows = r.array("blocks");
		JsonArray colls = r.array("coll");
		if (rows.size() > 256 || rows.size() != colls.size()) {
			throw new DataException(r.path("blocks"), "blocks and coll differ");
		}
		int[][] blocks = new int[rows.size()][];
		int[][] coll = new int[colls.size()][];
		for (int b = 0; b < rows.size(); b++) {
			if (!rows.get(b).isJsonArray() || !colls.get(b).isJsonArray()) {
				throw new DataException(r.path("blocks") + "/" + b, "not an array");
			}
			blocks[b] = DataReader.intArray(rows.get(b).getAsJsonArray(), r.path("blocks") + "/" + b, 0, 1048575);
			coll[b] = DataReader.intArray(colls.get(b).getAsJsonArray(), r.path("coll") + "/" + b, 0, 255);
			if (blocks[b].length != 16 || coll[b].length != 4) {
				throw new DataException(r.path("blocks") + "/" + b, "16 tiles and 4 bytes a block");
			}
		}
		Map<Integer, Meaning> meanings = new TreeMap<>();
		DataReader m = r.object("meanings");
		for (String key : m.json().keySet()) {
			int coll2;
			try {
				coll2 = Integer.parseInt(key);
			} catch (NumberFormatException e) {
				throw new DataException(m.path(), "a COLL byte");
			}
			if (coll2 < 0 || coll2 > 255) {
				throw new DataException(m.path(), "a COLL byte");
			}
			meanings.put(coll2, meaning(m.object(key)));
		}
		return new G2Tiles(id, r.string("tileset", SYM), r.optionalString("roof", SYM), blocks, coll, meanings);
	}

	private static Meaning meaning(DataReader o) throws DataException {
		String perm = o.string("perm");
		if (!PERMS.contains(perm)) {
			throw new DataException(o.path("perm"), "unknown permission");
		}
		String current = o.has("current") ? dir(o, "current") : null;
		String warp = null;
		if (o.has("warp")) {
			warp = o.string("warp");
			if (!WARPS.contains(warp)) {
				throw new DataException(o.path("warp"), "unknown warp kind");
			}
		}
		Map<String, String> neighbor = new TreeMap<>();
		if (o.has("neighbor")) {
			DataReader n = o.object("neighbor");
			for (String side : n.json().keySet()) {
				if (!DIRS.contains(side)) {
					throw new DataException(n.path(), "unknown side");
				}
				neighbor.put(side, dir(n, side));
			}
		}
		return new Meaning(perm, flag(o, "grass"), flag(o, "longGrass"), flag(o, "encounter"), flag(o, "ice"), flag(o, "whirlpool"),
				flag(o, "cutTree"), flag(o, "cuttable"), flag(o, "headbutt"), flag(o, "waterfall"), flag(o, "counter"), current, dirs(o, "ledge"),
				dirs(o, "sides"), neighbor, warp, o.has("mat") ? dir(o, "mat") : null, flag(o, "facesDown"));
	}

	private static boolean flag(DataReader o, String name) throws DataException {
		return o.has(name) && o.bool(name);
	}

	private static String dir(DataReader o, String name) throws DataException {
		String d = o.string(name);
		if (!DIRS.contains(d)) {
			throw new DataException(o.path(name), "unknown direction");
		}
		return d;
	}

	private static List<String> dirs(DataReader o, String name) throws DataException {
		if (!o.has(name)) {
			return List.of();
		}
		JsonArray a = o.array(name);
		String[] out = new String[a.size()];
		for (int i = 0; i < a.size(); i++) {
			String d = a.get(i).isJsonPrimitive() ? a.get(i).getAsString() : "";
			if (!DIRS.contains(d)) {
				throw new DataException(o.path(name) + "/" + i, "unknown direction");
			}
			out[i] = d;
		}
		return List.of(out);
	}
}
