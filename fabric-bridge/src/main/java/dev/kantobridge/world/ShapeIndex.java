package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A world's shape keys (docs/specs/VOXEL_BLOCKS_PLAN.md §4.2, {@code <world>/kantobridge/shape_index.json}): the sorted
 * atlases and palettes the keys refer to, the sorted geometry keys ({@code geoms}: geom id = index), and the sorted
 * (geom, palette) instances (shape id = index + 1; 0 means "no shape here"). Canonical JSON written like the slot map;
 * {@link #hash()} is the SHA-256 of that text. The planner version and parameters are inside it, so they are inside the
 * hash. Map data only (tile ids), never pixels. Shaped like {@link SlotMap}.
 */
public final class ShapeIndex {
	public static final int VERSION = 1;
	private static final Pattern ATLAS = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
	private static final Pattern PLANNER = Pattern.compile("^[a-z0-9-]{1,32}/[0-9]{1,4}$");
	private static final Set<String> ROOT_FIELDS = Set.of("version", "planner", "params", "atlases", "palettes", "geoms", "templates",
			"instances");

	/** One shape: a geometry key in a palette. */
	public record Instance(int geom, int palette) implements Comparable<Instance> {
		@Override
		public int compareTo(Instance other) {
			int c = Integer.compare(this.geom, other.geom);
			return c != 0 ? c : Integer.compare(this.palette, other.palette);
		}
	}

	/** A key in a palette, as the planner names a shape before the index is sorted. */
	public record Use(ShapeKey key, String palette) {
	}

	private final String planner;
	private final JsonObject params;
	private final List<String> atlases;
	private final List<String> palettes;
	private final List<ShapeKey> geoms;
	/** The templates as canonical JSON text (Hoenn's parts specs held as a JSON tree came to about 70 MB). */
	private final String templates;
	/** The instances as sorted (geom, palette) pairs ({@link #pair}): shape id = index + 1. */
	private final long[] pairs;
	/** Open addressing over the keys' hash codes: geom id + 1, 0 for an empty slot. */
	private final int[] geomTable;
	private final String hash;

	private ShapeIndex(String planner, JsonObject params, List<String> atlases, List<String> palettes, List<ShapeKey> geoms,
			JsonArray templates, List<Instance> instances) {
		this.planner = planner;
		this.params = params.deepCopy();
		this.atlases = List.copyOf(atlases);
		this.palettes = List.copyOf(palettes);
		this.geoms = List.copyOf(geoms);
		this.templates = ProtoJson.write(templates);
		this.pairs = new long[instances.size()];
		for (int i = 0; i < this.pairs.length; i++) {
			Instance instance = instances.get(i);
			this.pairs[i] = pair(instance.geom(), instance.palette());
		}
		this.geomTable = new int[Integer.highestOneBit(Math.max(4, this.geoms.size() * 2 - 1)) << 1];
		for (int i = 0; i < this.geoms.size(); i++) {
			int slot = this.slot(this.geoms.get(i));
			this.geomTable[slot] = i + 1;
		}
		// The canonical text is hashed as it is written and not kept (Hoenn's runs to 80 MB); toJson() writes it again.
		java.security.MessageDigest digest = Hashes.sha256();
		this.write(chunk -> digest.update(chunk.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		this.hash = java.util.HexFormat.of().formatHex(digest.digest());
	}

	/** The geom table's slot for {@code key}: its own, or the empty one where it would go. */
	private int slot(ShapeKey key) {
		int mask = this.geomTable.length - 1;
		int h = key.hashCode();
		int slot = (h ^ (h >>> 16)) * 0x9E3779B1 & mask;
		while (this.geomTable[slot] != 0 && !this.geoms.get(this.geomTable[slot] - 1).equals(key)) {
			slot = (slot + 1) & mask;
		}
		return slot;
	}

	/**
	 * Builds the index from the shapes a plan uses. {@code atlases} and {@code palettes} are the full sorted lists the keys'
	 * atlas indices refer to (every atlas and palette of the export, so the lists never depend on a build's filter).
	 */
	public static ShapeIndex of(String planner, JsonObject params, Collection<String> atlases, Collection<String> palettes, Collection<Use> uses,
			JsonArray templates) {
		List<String> atlasList = new ArrayList<>(new TreeSet<>(atlases));
		List<String> paletteList = new ArrayList<>(new TreeSet<>(palettes));
		TreeSet<ShapeKey> keys = new TreeSet<>();
		for (Use use : uses) {
			keys.add(use.key());
		}
		List<ShapeKey> geoms = new ArrayList<>(keys);
		Map<ShapeKey, Integer> geomIds = new HashMap<>();
		for (int i = 0; i < geoms.size(); i++) {
			geomIds.put(geoms.get(i), i);
		}
		TreeSet<Instance> instances = new TreeSet<>();
		for (Use use : uses) {
			int palette = Collections.binarySearch(paletteList, use.palette());
			if (palette < 0) {
				throw new IllegalArgumentException("a shape names a palette outside the list");
			}
			if (use.key().atlas() >= atlasList.size()) {
				throw new IllegalArgumentException("a shape names an atlas outside the list");
			}
			instances.add(new Instance(geomIds.get(use.key()), palette));
		}
		return new ShapeIndex(planner, params, atlasList, paletteList, geoms, templates, new ArrayList<>(instances));
	}

	private static long pair(int geom, int palette) {
		return ((long) geom << 32) | (palette & 0xFFFFFFFFL);
	}

	// ---- lookups ----

	/** The shape id of a key in a palette (index + 1), or 0 when the index has no such shape. */
	public int shapeId(ShapeKey key, String palette) {
		int geom = this.geomTable[this.slot(key)] - 1;
		int paletteIndex = Collections.binarySearch(this.palettes, palette);
		if (geom < 0 || paletteIndex < 0) {
			return 0;
		}
		int at = java.util.Arrays.binarySearch(this.pairs, pair(geom, paletteIndex));
		return at < 0 ? 0 : at + 1;
	}

	/** The instance of shape id {@code id} (1-based), or null. */
	public @Nullable Instance instance(int id) {
		if (id < 1 || id > this.pairs.length) {
			return null;
		}
		long pair = this.pairs[id - 1];
		return new Instance((int) (pair >>> 32), (int) pair);
	}

	/** The key of shape id {@code id}, or null. */
	public @Nullable ShapeKey key(int id) {
		return id >= 1 && id <= this.pairs.length ? this.geoms.get((int) (this.pairs[id - 1] >>> 32)) : null;
	}

	/** The palette of shape id {@code id}, or null. */
	public @Nullable String palette(int id) {
		return id >= 1 && id <= this.pairs.length ? this.palettes.get((int) this.pairs[id - 1]) : null;
	}

	/** The atlas id a key's atlas index names. */
	public String atlas(int index) {
		return this.atlases.get(index);
	}

	public String planner() {
		return this.planner;
	}

	public JsonObject params() {
		return this.params.deepCopy();
	}

	public List<String> atlases() {
		return this.atlases;
	}

	public List<String> palettes() {
		return this.palettes;
	}

	public List<ShapeKey> geoms() {
		return this.geoms;
	}

	/** The instances in shape id order (a view: each is made when read). */
	public List<Instance> instances() {
		return new java.util.AbstractList<>() {
			@Override
			public Instance get(int i) {
				return ShapeIndex.this.instance(i + 1);
			}

			@Override
			public int size() {
				return ShapeIndex.this.pairs.length;
			}
		};
	}

	public JsonArray templates() {
		try {
			return ProtoJson.parse(this.templates).getAsJsonArray();
		} catch (ProtocolException e) {
			throw new IllegalStateException("the index's own templates text", e);
		}
	}

	/** Shapes in the index (the highest shape id). */
	public int size() {
		return this.pairs.length;
	}

	/** Canonical JSON text (the contents of {@code shape_index.json}), written anew on each call. */
	public String toJson() {
		StringBuilder out = new StringBuilder();
		this.write(chunk -> out.append(chunk));
		return out.toString();
	}

	/** SHA-256 of {@link #toJson()}. */
	public String hash() {
		return this.hash;
	}

	/**
	 * Writes the canonical JSON in pieces of about 64K characters to {@code sink}: what {@code ProtoJson.write} gives for
	 * the index as a JSON object (its keys in code point order), without building that object.
	 */
	private void write(java.util.function.Consumer<StringBuilder> sink) {
		StringBuilder out = new StringBuilder(1 << 17);
		out.append("{\"atlases\":");
		strings(this.atlases, out);
		out.append(",\"geoms\":[");
		for (int i = 0; i < this.geoms.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			ProtoJson.appendString(this.geoms.get(i).text(), out);
			flush(out, sink);
		}
		out.append("],\"instances\":[");
		for (int i = 0; i < this.pairs.length; i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('[').append(this.pairs[i] >>> 32).append(',').append((int) this.pairs[i]).append(']');
			flush(out, sink);
		}
		out.append("],\"palettes\":");
		strings(this.palettes, out);
		out.append(",\"params\":");
		ProtoJson.append(this.params, out);
		out.append(",\"planner\":");
		ProtoJson.appendString(this.planner, out);
		out.append(",\"templates\":");
		// Everything before the templates goes first, then their text as one piece.
		sink.accept(out);
		out.setLength(0);
		sink.accept(new StringBuilder(this.templates));
		out.append(",\"version\":").append(VERSION).append('}');
		sink.accept(out);
	}

	private static void strings(List<String> values, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			ProtoJson.appendString(values.get(i), out);
		}
		out.append(']');
	}

	private static void flush(StringBuilder out, java.util.function.Consumer<StringBuilder> sink) {
		if (out.length() >= 1 << 16) {
			sink.accept(out);
			out.setLength(0);
		}
	}

	/** The index as a JSON object, as {@link #toJson()} writes it (tests compare the two). */
	JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.addProperty("planner", this.planner);
		root.add("params", this.params.deepCopy());
		JsonArray atlases = new JsonArray();
		this.atlases.forEach(atlases::add);
		root.add("atlases", atlases);
		JsonArray palettes = new JsonArray();
		this.palettes.forEach(palettes::add);
		root.add("palettes", palettes);
		JsonArray geoms = new JsonArray();
		this.geoms.forEach(key -> geoms.add(key.text()));
		root.add("geoms", geoms);
		root.add("templates", this.templates());
		JsonArray instances = new JsonArray();
		for (Instance instance : this.instances()) {
			JsonArray pair = new JsonArray();
			pair.add(instance.geom());
			pair.add(instance.palette());
			instances.add(pair);
		}
		root.add("instances", instances);
		return root;
	}

	/** Reads a stored or received index; its lists are re-sorted, so the hash never depends on how they were listed. */
	public static ShapeIndex fromJson(String text) throws DataException {
		DataReader root;
		try {
			root = DataReader.of(ProtoJson.parse(text), "");
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
		root.allowOnly(ROOT_FIELDS);
		if (root.integer("version") != VERSION) {
			throw new DataException(root.path("version"), "unsupported version");
		}
		String planner = root.string("planner", PLANNER);
		JsonObject params = root.object("params").json();
		List<String> atlases = strings(root, "atlases", ATLAS);
		List<String> palettes = strings(root, "palettes", DataReader.SYMBOL);
		// Keys name atlases by index, so the lists must already be in their canonical (sorted) order.
		if (!atlases.equals(new ArrayList<>(new TreeSet<>(atlases))) || !palettes.equals(new ArrayList<>(new TreeSet<>(palettes)))) {
			throw new DataException("/atlases", "atlases and palettes must be sorted");
		}
		JsonArray geomArray = root.array("geoms");
		List<ShapeKey> stored = new ArrayList<>();
		for (int i = 0; i < geomArray.size(); i++) {
			String keyText = DataReader.asString(geomArray.get(i), "/geoms/" + i);
			try {
				ShapeKey key = ShapeKey.parse(keyText);
				if (key.atlas() >= atlases.size()) {
					throw new DataException("/geoms/" + i, "atlas index out of range");
				}
				stored.add(key);
			} catch (IllegalArgumentException e) {
				throw new DataException("/geoms/" + i, "invalid shape key");
			}
		}
		JsonArray instanceArray = root.array("instances");
		List<Use> uses = new ArrayList<>();
		for (int i = 0; i < instanceArray.size(); i++) {
			JsonElement item = instanceArray.get(i);
			String path = "/instances/" + i;
			if (!item.isJsonArray() || item.getAsJsonArray().size() != 2) {
				throw new DataException(path, "not a [geom, palette] pair");
			}
			int geom = DataReader.asInt(item.getAsJsonArray().get(0), path + "/0", 0, Math.max(0, stored.size() - 1));
			int palette = DataReader.asInt(item.getAsJsonArray().get(1), path + "/1", 0, Math.max(0, palettes.size() - 1));
			if (stored.isEmpty() || palettes.isEmpty()) {
				throw new DataException(path, "no geometry or palette to refer to");
			}
			uses.add(new Use(stored.get(geom), palettes.get(palette)));
		}
		if (new TreeSet<>(stored).size() != stored.size()) {
			throw new DataException("/geoms", "duplicate keys");
		}
		ShapeIndex index = of(planner, params, atlases, palettes, uses, root.array("templates"));
		if (index.pairs.length != uses.size()) {
			throw new DataException("/instances", "duplicate instances");
		}
		if (index.geoms.size() != stored.size()) {
			throw new DataException("/geoms", "a key no instance uses");
		}
		return index;
	}

	private static List<String> strings(DataReader root, String name, Pattern pattern) throws DataException {
		JsonArray array = root.array(name);
		List<String> out = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			out.add(DataReader.asString(array.get(i), "/" + name + "/" + i, pattern));
		}
		if (new TreeSet<>(out).size() != out.size()) {
			throw new DataException("/" + name, "duplicates");
		}
		return out;
	}
}
