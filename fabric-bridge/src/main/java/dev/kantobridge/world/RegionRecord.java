package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One region of the overworld, {@code <world>/kantobridge/regions/<id>/region.json} (docs/specs/MULTI_GAME_PLAN.md
 * §4.6, MG3; protocol/world/region.schema.json): built from one game's export at a slot of the {@link RegionTable},
 * it names its game and family, the export, its slot and origin, the box it owns ({@link #bounds()}: no two regions'
 * overlap), the areas the client and server use for "which region am I in" ({@link #areas()}), the slot-map family
 * range its tiles use, its documents and their hashes, the shape generator, its rules and namespaces. Relayed inside
 * {@code world.regions}. Ids, numbers and hashes only.
 *
 * <p>A legacy world (no {@code regions.json}, §4.14) has one implicit record, {@link #legacy}: {@code kanto} at slot 0,
 * families 0..15, its documents at the root of {@code kantobridge/}; nothing on disk changes until a build with a
 * region writes the new layout.
 *
 * <p>The JSON is canonical (sorted keys, compact); {@link #hash()} is its SHA-256.
 */
public final class RegionRecord {
	public static final int VERSION = 1;
	public static final String FILE = "region.json";
	/** The folder under {@code kantobridge/} that holds the regions' folders. */
	public static final String DIR = "regions";
	/** The id of every legacy world's one region. */
	public static final String LEGACY_ID = SlotMap.LEGACY_REGION;
	/** The legacy world's documents, at the root of {@code kantobridge/} (they must agree with {@code BuildTask}'s names). */
	public static final String ROOT_INDEX = "world_index.json";
	public static final String ROOT_SHAPE_INDEX = "shape_index.json";
	public static final String ROOT_SHAPE_MAP = "shape_map.bin";
	/** The habitat pool namespace every pool id starts with. */
	public static final String POOL_NAMESPACE = "kantobridge";
	/** The shape generator named when a world has no shape documents yet. */
	public static final String NO_GENERATOR = "kb-shapes/0";
	public static final String LEAVE_OUT = "leave_out";
	public static final String VISITOR = "visitor";
	public static final int MAX_SLOT = RegionTable.MAX_SLOT;

	private static final Pattern DOCUMENT = Pattern.compile("^(regions/[a-z][a-z0-9_]{0,23}/)?[A-Za-z0-9_.-]{1,64}$");
	private static final Pattern GENERATOR = Pattern.compile("^kb-shapes/[0-9]{1,4}$");
	private static final Pattern POOLS = Pattern.compile("^kantobridge:[a-z][a-z0-9_]{0,23}$");
	private static final Set<String> ROOT_FIELDS = Set.of("version", "id", "game", "family", "exportHash", "slot", "origin", "bounds", "areas",
			"families", "files", "hashes", "generator", "rules", "namespaces");
	private static final Set<String> ORIGIN_FIELDS = Set.of("x", "y", "z");
	private static final Set<String> BOX_FIELDS = Set.of("minX", "minZ", "maxX", "maxZ", "minY", "maxY");
	private static final Set<String> AREAS_FIELDS = Set.of("surface", "zones", "dungeons", "caves");
	private static final Set<String> AREA_FIELDS = Set.of("x", "z", "w", "d");
	private static final Set<String> DOCUMENT_FIELDS = Set.of("index", "shapeIndex", "shapeMap");
	private static final Set<String> GENERATOR_FIELDS = Set.of("shapes");
	private static final Set<String> RULES_FIELDS = Set.of("profile", "movement", "compat");
	private static final Set<String> MOVEMENT_FIELDS = Set.of("walk", "bike");
	private static final Set<String> COMPAT_FIELDS = Set.of("maxDex", "maxMove", "nick", "onIncompatible");
	private static final Set<String> NAMESPACE_FIELDS = Set.of("artSet", "pools");
	private static final Set<String> ON_INCOMPATIBLE = Set.of(LEAVE_OUT, VISITOR);
	/** The hash of nothing: a document the world doesn't have yet. */
	private static final String EMPTY_HASH = Hashes.sha256("");

	/** The block the region's surface plans from: {@code (pitch · slot, surfaceY, 0)}. */
	public record Origin(int x, int y, int z) {
	}

	/** A box of blocks, inclusive on every side. */
	public record Box(int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
		public Box {
			if (minX > maxX || minZ > maxZ || minY > maxY) {
				throw new IllegalArgumentException("bad box");
			}
		}

		public boolean contains(int x, int z) {
			return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
		}

		public boolean overlaps(Box other) {
			return this.minX <= other.maxX && other.minX <= this.maxX && this.minZ <= other.maxZ && other.minZ <= this.maxZ
					&& this.minY <= other.maxY && other.minY <= this.maxY;
		}

		/** The smallest box holding both. */
		public Box union(Box other) {
			return new Box(Math.min(this.minX, other.minX), Math.min(this.minZ, other.minZ), Math.max(this.maxX, other.maxX),
					Math.max(this.maxZ, other.maxZ), Math.min(this.minY, other.minY), Math.max(this.maxY, other.maxY));
		}
	}

	/**
	 * A box on the ground plane: its north-west corner and size in blocks; zero size means the area was not built.
	 *
	 * @param w width (X)
	 * @param d depth (Z)
	 */
	public record Area(int x, int z, int w, int d) {
		public static final Area NONE = new Area(0, 0, 0, 0);

		public Area {
			if (w < 0 || d < 0) {
				throw new IllegalArgumentException("bad area");
			}
		}

		public boolean isEmpty() {
			return this.w == 0 || this.d == 0;
		}

		public boolean contains(int x, int z) {
			return !this.isEmpty() && x >= this.x && x < this.x + this.w && z >= this.z && z < this.z + this.d;
		}

		/** The smallest area holding both (an empty one adds nothing). */
		public Area union(Area other) {
			if (this.isEmpty()) {
				return other;
			}
			if (other.isEmpty()) {
				return this;
			}
			int x0 = Math.min(this.x, other.x);
			int z0 = Math.min(this.z, other.z);
			int x1 = Math.max(this.x + this.w, other.x + other.w);
			int z1 = Math.max(this.z + this.d, other.z + other.d);
			return new Area(x0, z0, x1 - x0, z1 - z0);
		}
	}

	/** Where the region's parts are: the towns and routes at feet level, the wild-zone slots, the dungeon slots, the caves. */
	public record Areas(Area surface, Area zones, Area dungeons, Area caves) {
		public static final Areas NONE = new Areas(Area.NONE, Area.NONE, Area.NONE, Area.NONE);

		public Areas {
			Objects.requireNonNull(surface, "surface");
			Objects.requireNonNull(zones, "zones");
			Objects.requireNonNull(dungeons, "dungeons");
			Objects.requireNonNull(caves, "caves");
		}

		public boolean contains(int x, int z) {
			return this.surface.contains(x, z) || this.zones.contains(x, z) || this.dungeons.contains(x, z) || this.caves.contains(x, z);
		}
	}

	/** The region's documents, relative to {@code <world>/kantobridge/}. */
	public record Documents(String index, String shapeIndex, String shapeMap) {
		public Documents {
			for (String path : new String[] {index, shapeIndex, shapeMap}) {
				if (path == null || !DOCUMENT.matcher(path).matches()) {
					throw new IllegalArgumentException("bad document path");
				}
			}
		}

		/** The documents of region {@code id} under {@code regions/<id>/}. */
		public static Documents of(String id) {
			return new Documents(fileIndex(id), fileShapeIndex(id), fileShapeMap(id));
		}

		/** The legacy world's documents at the root. */
		public static Documents root() {
			return new Documents(ROOT_INDEX, ROOT_SHAPE_INDEX, ROOT_SHAPE_MAP);
		}
	}

	/** The documents' SHA-256 hashes (the hash of an empty text for one the world doesn't have yet). */
	public record DocumentHashes(String index, String shapeIndex, String shapeMap) {
		public DocumentHashes {
			for (String hash : new String[] {index, shapeIndex, shapeMap}) {
				if (hash == null || !DataReader.HASH.matcher(hash).matches()) {
					throw new IllegalArgumentException("bad hash");
				}
			}
		}
	}

	/** Frames per cell, when a game needs its own speeds (absent: the engine's). */
	public record Movement(int walk, int bike) {
		public Movement {
			if (walk < 1 || walk > 64 || bike < 1 || bike > 64) {
				throw new IllegalArgumentException("bad movement speeds");
			}
		}
	}

	/**
	 * The region's compatibility profile (§4.9): what its shadow party can hold and what happens to the rest.
	 *
	 * @param nick           the longest nickname, or null when the record doesn't say
	 * @param onIncompatible {@link #VISITOR} (FOREIGN_POKEMON_PLAN Q12, the default since FP1: a member the game lacks
	 *                       is a visitor) or {@link #LEAVE_OUT} (it is left out); sent to the link at bind
	 */
	public record Compat(int maxDex, int maxMove, @Nullable Integer nick, String onIncompatible) {
		public Compat {
			if (maxDex < 1 || maxDex > Games.Compat.MAX_NUMBER || maxMove < 1 || maxMove > Games.Compat.MAX_NUMBER
					|| nick != null && (nick < 1 || nick > Games.Compat.MAX_NICK)) {
				throw new IllegalArgumentException("bad compat profile");
			}
			if (onIncompatible == null || !ON_INCOMPATIBLE.contains(onIncompatible)) {
				throw new IllegalArgumentException("bad onIncompatible");
			}
		}

		/** The catalogue's profile for a game; FP1: incompatible Pokémon are visitors (Q12's default). */
		public static Compat of(Games.Compat compat) {
			return new Compat(compat.maxDex(), compat.maxMove(), compat.nick(), VISITOR);
		}
	}

	/** The region's rules: the profile (its family), movement speeds when a game needs its own, the compat profile. */
	public record Rules(String profile, @Nullable Movement movement, Compat compat) {
		public Rules {
			if (profile == null || !Games.FAMILIES.contains(profile)) {
				throw new IllegalArgumentException("unknown profile");
			}
			Objects.requireNonNull(compat, "compat");
		}
	}

	/**
	 * @param artSet the art set the region is drawn with (its game's export)
	 * @param pools  the habitat pool id prefix, {@code kantobridge:<region>}
	 */
	public record Namespaces(String artSet, String pools) {
		public Namespaces {
			if (artSet == null || !Games.GAME_ID.matcher(artSet).matches()) {
				throw new IllegalArgumentException("bad art set");
			}
			if (pools == null || !POOLS.matcher(pools).matches()) {
				throw new IllegalArgumentException("bad pool prefix");
			}
		}

		public static Namespaces of(String game, String region) {
			String artSet = Games.artSet(game);
			return new Namespaces(artSet == null ? game : artSet, POOL_NAMESPACE + ":" + region);
		}
	}

	private final String id;
	private final String game;
	private final String family;
	private final String exportHash;
	private final int slot;
	private final Origin origin;
	private final Box bounds;
	private final Areas areas;
	private final SlotMap.Range families;
	private final Documents files;
	private final DocumentHashes hashes;
	private final String generator;
	private final Rules rules;
	private final Namespaces namespaces;
	private final String json;
	private final String hash;

	public RegionRecord(String id, String game, String family, String exportHash, int slot, Origin origin, Box bounds, Areas areas,
			SlotMap.Range families, Documents files, DocumentHashes hashes, String generator, Rules rules, Namespaces namespaces) {
		if (id == null || !Games.REGION_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("invalid region id");
		}
		if (!Games.known(game)) {
			throw new IllegalArgumentException("unknown game");
		}
		if (family == null || !Games.FAMILIES.contains(family)) {
			throw new IllegalArgumentException("unknown family");
		}
		if (exportHash == null || !DataReader.HASH.matcher(exportHash).matches()) {
			throw new IllegalArgumentException("invalid hash");
		}
		if (slot < 0 || slot > MAX_SLOT) {
			throw new IllegalArgumentException("slot out of range");
		}
		if (generator == null || !GENERATOR.matcher(generator).matches()) {
			throw new IllegalArgumentException("bad generator");
		}
		Objects.requireNonNull(files, "files");
		for (String path : new String[] {files.index(), files.shapeIndex(), files.shapeMap()}) {
			if (path.startsWith(DIR + "/") && !path.startsWith(DIR + "/" + id + "/")) {
				throw new IllegalArgumentException("a document of another region");
			}
		}
		this.id = id;
		this.game = game;
		this.family = family;
		this.exportHash = exportHash;
		this.slot = slot;
		this.origin = Objects.requireNonNull(origin, "origin");
		this.bounds = Objects.requireNonNull(bounds, "bounds");
		this.areas = Objects.requireNonNull(areas, "areas");
		this.families = Objects.requireNonNull(families, "families");
		this.files = files;
		this.hashes = Objects.requireNonNull(hashes, "hashes");
		this.generator = generator;
		this.rules = Objects.requireNonNull(rules, "rules");
		this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
		this.json = ProtoJson.write(this.toJsonObject());
		this.hash = Hashes.sha256(this.json);
	}

	/** {@code regions/<id>/world_index.json}. */
	public static String fileIndex(String id) {
		return DIR + "/" + id + "/" + ROOT_INDEX;
	}

	/** {@code regions/<id>/shape_index.json}. */
	public static String fileShapeIndex(String id) {
		return DIR + "/" + id + "/" + ROOT_SHAPE_INDEX;
	}

	/** {@code regions/<id>/shape_map.bin}. */
	public static String fileShapeMap(String id) {
		return DIR + "/" + id + "/" + ROOT_SHAPE_MAP;
	}

	/** {@code regions/<id>/region.json}. */
	public static String fileRecord(String id) {
		return DIR + "/" + id + "/" + FILE;
	}

	/**
	 * The implicit record of a legacy world (§4.14): its one region, named by the game's world space ({@code kanto}
	 * for Red and Blue), at slot 0 with origin (0, the index's surface level, 0), families 0..15, its documents at the
	 * root, bounds and areas read from the index ({@link WorldIndex#boundsOfPlacements()}; the cave area is not an
	 * index fact, so it stays empty and the caves count through the bounds), the catalogue's compat profile leaving
	 * incompatible Pokémon out, pools {@code kantobridge:<id>}.
	 *
	 * @param planner        the shape index's planner id ({@code kb-shapes/<n>}), or null for a world without shapes
	 * @param shapeIndexHash the shape index's hash, or null without one
	 * @param shapeMapHash   the shape map's hash, or null without one
	 */
	public static RegionRecord legacy(WorldIndex index, @Nullable String planner, @Nullable String shapeIndexHash, @Nullable String shapeMapHash) {
		String game = index.game();
		String id = Games.region(game);
		if (id == null) {
			id = LEGACY_ID;
		}
		String family = index.family() != null ? index.family() : Games.family(game);
		Box bounds = index.boundsOfPlacements();
		if (bounds == null) {
			bounds = new Box(0, 0, 0, 0, index.surfaceY() - 1, index.surfaceY());
		}
		Area surface = Area.NONE;
		for (MapPlacement map : index.maps().values()) {
			if (map.kind() == MapPlacement.Kind.SURFACE) {
				surface = surface.union(new Area(map.x(), map.z(), Scale.cellToBlock(map.w()), Scale.cellToBlock(map.h())));
			}
		}
		Area zones = Area.NONE;
		for (WorldIndex.Zone zone : index.zones()) {
			zones = zones.union(new Area(zone.x(), zone.z(), zone.w(), zone.d()));
		}
		WorldIndex.Dungeons dungeons = index.dungeons();
		Area dungeonArea = dungeons == null ? Area.NONE : new Area(dungeons.x(), dungeons.z(), dungeons.w(), dungeons.d());
		Games.Compat compat = Games.compat(game);
		Rules rules = new Rules(family, null, compat == null ? new Compat(1, 1, null, LEAVE_OUT) : Compat.of(compat));
		return new RegionRecord(id, game, family, index.exportHash(), 0, new Origin(0, index.surfaceY(), 0), bounds,
				new Areas(surface, zones, dungeonArea, Area.NONE), SlotMap.LEGACY_FAMILIES, Documents.root(),
				new DocumentHashes(index.hash(), shapeIndexHash == null ? EMPTY_HASH : shapeIndexHash, shapeMapHash == null ? EMPTY_HASH : shapeMapHash),
				planner == null ? NO_GENERATOR : planner, rules, Namespaces.of(game, id));
	}

	public String id() {
		return this.id;
	}

	public String game() {
		return this.game;
	}

	public String family() {
		return this.family;
	}

	public String exportHash() {
		return this.exportHash;
	}

	public int slot() {
		return this.slot;
	}

	public Origin origin() {
		return this.origin;
	}

	public Box bounds() {
		return this.bounds;
	}

	public Areas areas() {
		return this.areas;
	}

	public SlotMap.Range families() {
		return this.families;
	}

	public Documents files() {
		return this.files;
	}

	public DocumentHashes hashes() {
		return this.hashes;
	}

	/** The shape planner id, {@code kb-shapes/<n>}. */
	public String generator() {
		return this.generator;
	}

	public Rules rules() {
		return this.rules;
	}

	public Namespaces namespaces() {
		return this.namespaces;
	}

	/** True for the legacy world's implicit region: its documents sit at the root of {@code kantobridge/}. */
	public boolean isLegacy() {
		return ROOT_INDEX.equals(this.files.index());
	}

	/** {@code regions/<id>/}, the folder of a region's documents (empty for the legacy region's root). */
	public String dir() {
		return this.isLegacy() ? "" : DIR + "/" + this.id + "/";
	}

	/**
	 * What the region's habitat pool ids and zone pool ids start with after {@code kantobridge:}: nothing at slot 0 (the
	 * legacy region's pools stay {@code kantobridge:<map>}, whether its documents sit at the root or, once rebuilt, under
	 * {@code regions/<id>/}, so its WorldIndex keeps its hash), {@code <id>_} otherwise (two games both have a ROUTE_1).
	 */
	public String poolPrefix() {
		return this.slot == 0 ? "" : this.id + "_";
	}

	public RegionRecord withBounds(Box bounds) {
		return new RegionRecord(this.id, this.game, this.family, this.exportHash, this.slot, this.origin, bounds, this.areas, this.families,
				this.files, this.hashes, this.generator, this.rules, this.namespaces);
	}

	public RegionRecord withAreas(Areas areas) {
		return new RegionRecord(this.id, this.game, this.family, this.exportHash, this.slot, this.origin, this.bounds, areas, this.families,
				this.files, this.hashes, this.generator, this.rules, this.namespaces);
	}

	public RegionRecord withHashes(DocumentHashes hashes) {
		return new RegionRecord(this.id, this.game, this.family, this.exportHash, this.slot, this.origin, this.bounds, this.areas, this.families,
				this.files, hashes, this.generator, this.rules, this.namespaces);
	}

	public RegionRecord withGenerator(String generator) {
		return new RegionRecord(this.id, this.game, this.family, this.exportHash, this.slot, this.origin, this.bounds, this.areas, this.families,
				this.files, this.hashes, generator, this.rules, this.namespaces);
	}

	/** Canonical JSON text (the contents of {@code region.json}). */
	public String toJson() {
		return this.json;
	}

	/** SHA-256 of {@link #toJson()}. */
	public String hash() {
		return this.hash;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof RegionRecord record && record.json.equals(this.json);
	}

	@Override
	public int hashCode() {
		return this.json.hashCode();
	}

	@Override
	public String toString() {
		return "RegionRecord[" + this.id + " slot " + this.slot + " " + this.game + "]";
	}

	private static JsonObject area(Area area) {
		JsonObject out = new JsonObject();
		out.addProperty("x", area.x());
		out.addProperty("z", area.z());
		out.addProperty("w", area.w());
		out.addProperty("d", area.d());
		return out;
	}

	private JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.addProperty("id", this.id);
		root.addProperty("game", this.game);
		root.addProperty("family", this.family);
		root.addProperty("exportHash", this.exportHash);
		root.addProperty("slot", this.slot);
		JsonObject origin = new JsonObject();
		origin.addProperty("x", this.origin.x());
		origin.addProperty("y", this.origin.y());
		origin.addProperty("z", this.origin.z());
		root.add("origin", origin);
		JsonObject bounds = new JsonObject();
		bounds.addProperty("minX", this.bounds.minX());
		bounds.addProperty("minZ", this.bounds.minZ());
		bounds.addProperty("maxX", this.bounds.maxX());
		bounds.addProperty("maxZ", this.bounds.maxZ());
		bounds.addProperty("minY", this.bounds.minY());
		bounds.addProperty("maxY", this.bounds.maxY());
		root.add("bounds", bounds);
		JsonObject areas = new JsonObject();
		areas.add("surface", area(this.areas.surface()));
		areas.add("zones", area(this.areas.zones()));
		areas.add("dungeons", area(this.areas.dungeons()));
		areas.add("caves", area(this.areas.caves()));
		root.add("areas", areas);
		JsonArray families = new JsonArray();
		families.add(this.families.lo());
		families.add(this.families.hi());
		root.add("families", families);
		JsonObject files = new JsonObject();
		files.addProperty("index", this.files.index());
		files.addProperty("shapeIndex", this.files.shapeIndex());
		files.addProperty("shapeMap", this.files.shapeMap());
		root.add("files", files);
		JsonObject hashes = new JsonObject();
		hashes.addProperty("index", this.hashes.index());
		hashes.addProperty("shapeIndex", this.hashes.shapeIndex());
		hashes.addProperty("shapeMap", this.hashes.shapeMap());
		root.add("hashes", hashes);
		JsonObject generator = new JsonObject();
		generator.addProperty("shapes", this.generator);
		root.add("generator", generator);
		JsonObject rules = new JsonObject();
		rules.addProperty("profile", this.rules.profile());
		if (this.rules.movement() != null) {
			JsonObject movement = new JsonObject();
			movement.addProperty("walk", this.rules.movement().walk());
			movement.addProperty("bike", this.rules.movement().bike());
			rules.add("movement", movement);
		}
		JsonObject compat = new JsonObject();
		compat.addProperty("maxDex", this.rules.compat().maxDex());
		compat.addProperty("maxMove", this.rules.compat().maxMove());
		if (this.rules.compat().nick() != null) {
			compat.addProperty("nick", this.rules.compat().nick());
		}
		compat.addProperty("onIncompatible", this.rules.compat().onIncompatible());
		rules.add("compat", compat);
		root.add("rules", rules);
		JsonObject namespaces = new JsonObject();
		namespaces.addProperty("artSet", this.namespaces.artSet());
		namespaces.addProperty("pools", this.namespaces.pools());
		root.add("namespaces", namespaces);
		return root;
	}

	private static Area readArea(DataReader areas, String name) throws DataException {
		DataReader a = areas.object(name).allowOnly(AREA_FIELDS);
		return new Area(a.integer("x"), a.integer("z"), a.integer("w", 0, Integer.MAX_VALUE), a.integer("d", 0, Integer.MAX_VALUE));
	}

	/** Reads a stored record strictly (protocol/world/region.schema.json). */
	public static RegionRecord fromJson(String text) throws DataException {
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
		String id = root.string("id", Games.REGION_ID);
		String game = root.string("game", Games.GAME_ID);
		if (!Games.known(game)) {
			throw new DataException(root.path("game"), "unknown game");
		}
		String family = root.string("family");
		if (!Games.FAMILIES.contains(family)) {
			throw new DataException(root.path("family"), "unknown family");
		}
		String exportHash = root.string("exportHash", DataReader.HASH);
		int slot = root.integer("slot", 0, MAX_SLOT);
		DataReader o = root.object("origin").allowOnly(ORIGIN_FIELDS);
		Origin origin = new Origin(o.integer("x"), o.integer("y"), o.integer("z"));
		DataReader b = root.object("bounds").allowOnly(BOX_FIELDS);
		Box bounds;
		try {
			bounds = new Box(b.integer("minX"), b.integer("minZ"), b.integer("maxX"), b.integer("maxZ"), b.integer("minY"), b.integer("maxY"));
		} catch (IllegalArgumentException e) {
			throw new DataException(b.path(), "bad box");
		}
		DataReader a = root.object("areas").allowOnly(AREAS_FIELDS);
		Areas areas = new Areas(readArea(a, "surface"), readArea(a, "zones"), readArea(a, "dungeons"), readArea(a, "caves"));
		int[] range = DataReader.intArray(root.array("families"), root.path("families"), 0, SlotMap.FAMILIES_PER_SHAPE - 1);
		if (range.length != 2 || range[0] > range[1]) {
			throw new DataException(root.path("families"), "bad family range");
		}
		DataReader f = root.object("files").allowOnly(DOCUMENT_FIELDS);
		Documents files;
		try {
			files = new Documents(f.string("index", DOCUMENT), f.string("shapeIndex", DOCUMENT), f.string("shapeMap", DOCUMENT));
		} catch (IllegalArgumentException e) {
			throw new DataException(f.path(), "bad document path");
		}
		DataReader h = root.object("hashes").allowOnly(DOCUMENT_FIELDS);
		DocumentHashes hashes = new DocumentHashes(h.string("index", DataReader.HASH), h.string("shapeIndex", DataReader.HASH),
				h.string("shapeMap", DataReader.HASH));
		String generator = root.object("generator").allowOnly(GENERATOR_FIELDS).string("shapes", GENERATOR);
		DataReader r = root.object("rules").allowOnly(RULES_FIELDS);
		String profile = r.string("profile");
		if (!Games.FAMILIES.contains(profile)) {
			throw new DataException(r.path("profile"), "unknown profile");
		}
		Movement movement = null;
		DataReader m = r.optionalObject("movement");
		if (m != null) {
			m.allowOnly(MOVEMENT_FIELDS);
			movement = new Movement(m.integer("walk", 1, 64), m.integer("bike", 1, 64));
		}
		DataReader c = r.object("compat").allowOnly(COMPAT_FIELDS);
		Integer nick = c.isNull("nick") ? null : c.integer("nick", 1, Games.Compat.MAX_NICK);
		String onIncompatible = c.string("onIncompatible");
		if (!ON_INCOMPATIBLE.contains(onIncompatible)) {
			throw new DataException(c.path("onIncompatible"), "unknown value");
		}
		Compat compat = new Compat(c.integer("maxDex", 1, Games.Compat.MAX_NUMBER), c.integer("maxMove", 1, Games.Compat.MAX_NUMBER), nick,
				onIncompatible);
		DataReader n = root.object("namespaces").allowOnly(NAMESPACE_FIELDS);
		Namespaces namespaces = new Namespaces(n.string("artSet", Games.GAME_ID), n.string("pools", POOLS));
		try {
			return new RegionRecord(id, game, family, exportHash, slot, origin, bounds, areas, new SlotMap.Range(range[0], range[1]), files,
					hashes, generator, new Rules(profile, movement, compat), namespaces);
		} catch (IllegalArgumentException e) {
			throw new DataException("", e.getMessage());
		}
	}
}
