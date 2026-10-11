package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What the world builder writes to {@code <world>/kantobridge/world_index.json} and the server sends to clients on
 * join (protocol/world/world_index.schema.json): the scale, every map's placement, the slot-map hash, the
 * story-cell table, the habitat blocks and (Wild Routes) the zones. Clients turn block positions into cells and back
 * with it.
 *
 * <p>The JSON is canonical (sorted keys, compact), so the same index always has the same text and hash.
 */
public final class WorldIndex {
	public static final int VERSION = 1;

	private static final Set<String> ROOT_FIELDS = Set.of("version", "game", "exportHash", "scale", "maps", "slotMapHash",
			"storyCells", "habitats", "zones");
	private static final Set<String> ZONE_FIELDS = Set.of("id", "routeMap", "theme", "x", "y", "z", "w", "d", "biome");
	private static final java.util.regex.Pattern ZONE_ID = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	private static final Set<String> THEMES = Set.of("forest");
	private static final Set<String> SCALE_FIELDS = Set.of("cellBlocks", "surfaceY", "layerStep");
	private static final Set<String> MAP_FIELDS = Set.of("x", "y", "z", "w", "h", "layer", "kind", "palette", "biome", "dark",
			"parent");
	private static final Set<String> HABITAT_FIELDS = Set.of("map", "x", "y", "z", "pool");
	private static final Set<String> GAMES = Set.of("red", "blue");
	/** How far above or below a map's feet level a block still counts as on that map (layers are 22 apart). */
	private static final int LAYER_REACH_DOWN = 4;
	private static final int LAYER_REACH_UP = 6;

	/** One habitat block (M6). */
	public record Habitat(String map, int x, int y, int z, String pool) {
	}

	/**
	 * One Wild Routes zone (docs/specs/WILD_ROUTES_PLAN.md §4.4): the box of blocks it owns (its areas, gates and the
	 * land around them), the feet level of its walkable floor, its route map (a {@link MapPlacement.Kind#ROUTE}
	 * placement at the trailhead) and its biome. Inside the box, the client sends no steps and the drift rule is off.
	 *
	 * @param x the box's north-west block X
	 * @param z the box's north-west block Z
	 * @param w the box's width in blocks (X)
	 * @param d the box's depth in blocks (Z)
	 * @param y the feet level of the zone's walkable floor
	 */
	public record Zone(String id, String routeMap, String theme, int x, int y, int z, int w, int d, String biome) {
		public Zone {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(routeMap, "routeMap");
			Objects.requireNonNull(theme, "theme");
			Objects.requireNonNull(biome, "biome");
			if (w < 1 || d < 1) {
				throw new IllegalArgumentException("zone size must be positive");
			}
		}

		/** True when block column (bx, bz) lies inside the zone's box. */
		public boolean containsColumn(int bx, int bz) {
			return bx >= this.x && bx < this.x + this.w && bz >= this.z && bz < this.z + this.d;
		}
	}

	private final String game;
	private final String exportHash;
	private final int surfaceY;
	private final int layerStep;
	private final Map<String, MapPlacement> maps;
	private final String slotMapHash;
	private final StoryCellTable storyCells;
	private final List<Habitat> habitats;
	private final List<Zone> zones;
	private final String json;
	private final String hash;

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats) {
		this(game, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, slotMapHash, storyCells, habitats, List.of());
	}

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats, List<Zone> zones) {
		this(game, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, slotMapHash, storyCells, habitats, zones);
	}

	private WorldIndex(String game, String exportHash, int surfaceY, int layerStep, Collection<MapPlacement> maps, String slotMapHash,
			StoryCellTable storyCells, List<Habitat> habitats, List<Zone> zones) {
		if (!GAMES.contains(game)) {
			throw new IllegalArgumentException("unknown game");
		}
		if (!DataReader.HASH.matcher(exportHash).matches() || !DataReader.HASH.matcher(slotMapHash).matches()) {
			throw new IllegalArgumentException("invalid hash");
		}
		this.game = game;
		this.exportHash = exportHash;
		this.surfaceY = surfaceY;
		this.layerStep = layerStep;
		Map<String, MapPlacement> byId = new TreeMap<>();
		for (MapPlacement map : maps) {
			if (byId.put(map.id(), map) != null) {
				throw new IllegalArgumentException("duplicate map placement");
			}
		}
		this.maps = Collections.unmodifiableMap(byId);
		this.slotMapHash = slotMapHash;
		this.storyCells = Objects.requireNonNull(storyCells, "storyCells");
		this.habitats = List.copyOf(habitats);
		List<Zone> sortedZones = new ArrayList<>(zones);
		sortedZones.sort(java.util.Comparator.comparing(Zone::id));
		for (int i = 0; i < sortedZones.size(); i++) {
			Zone zone = sortedZones.get(i);
			if (i > 0 && sortedZones.get(i - 1).id().equals(zone.id())) {
				throw new IllegalArgumentException("duplicate zone");
			}
			MapPlacement routeMap = byId.get(zone.routeMap());
			if (routeMap == null || routeMap.kind() != MapPlacement.Kind.ROUTE) {
				throw new IllegalArgumentException("a zone's route map must be a route placement");
			}
		}
		this.zones = List.copyOf(sortedZones);
		this.json = ProtoJson.write(this.toJsonObject());
		this.hash = Hashes.sha256(this.json);
	}

	public String game() {
		return this.game;
	}

	public String exportHash() {
		return this.exportHash;
	}

	public int surfaceY() {
		return this.surfaceY;
	}

	public int layerStep() {
		return this.layerStep;
	}

	/** Every placed map, by id (sorted). */
	public Map<String, MapPlacement> maps() {
		return this.maps;
	}

	public @Nullable MapPlacement map(String id) {
		return this.maps.get(id);
	}

	public String slotMapHash() {
		return this.slotMapHash;
	}

	public StoryCellTable storyCells() {
		return this.storyCells;
	}

	public List<Habitat> habitats() {
		return this.habitats;
	}

	/** Wild Routes zones, sorted by id (empty in a world built without any). */
	public List<Zone> zones() {
		return this.zones;
	}

	/** The zone whose box holds block column (bx, bz), or null. */
	public @Nullable Zone zoneAt(int bx, int bz) {
		for (Zone zone : this.zones) {
			if (zone.containsColumn(bx, bz)) {
				return zone;
			}
		}
		return null;
	}

	/** Canonical JSON text. */
	public String toJson() {
		return this.json;
	}

	/** SHA-256 of {@link #toJson()}: the {@code hash} of {@code world.index}. */
	public String hash() {
		return this.hash;
	}

	// ---- lookups ----

	/**
	 * The cell under a block position: the map whose footprint holds the column and whose walking surface is within a
	 * few blocks of {@code y}. Empty outside every map (gaps, borders, between layers).
	 */
	public Optional<CellPos> toCell(int x, int y, int z) {
		for (MapPlacement map : this.maps.values()) {
			if (y < map.y() - LAYER_REACH_DOWN || y >= map.y() + LAYER_REACH_UP || !map.containsColumn(x, z)) {
				continue;
			}
			return Optional.of(new CellPos(map.id(), Scale.blockToCell(x - map.x()), Scale.blockToCell(z - map.z())));
		}
		return Optional.empty();
	}

	public Optional<CellPos> toCell(BlockPos pos) {
		return this.toCell(pos.getX(), pos.getY(), pos.getZ());
	}

	/** The centre of a cell's 2×2 footprint at feet level, or null for an unknown map. */
	public @Nullable Vec3 toBlock(String map, int x, int y) {
		MapPlacement placement = this.maps.get(map);
		if (placement == null) {
			return null;
		}
		return new Vec3(placement.x() + Scale.cellToBlock(x) + 1.0, placement.y(), placement.z() + Scale.cellToBlock(y) + 1.0);
	}

	/** The north-west block of a cell at feet level, or null for an unknown map. */
	public @Nullable BlockPos cellOrigin(String map, int x, int y) {
		MapPlacement placement = this.maps.get(map);
		if (placement == null) {
			return null;
		}
		return new BlockPos(placement.x() + Scale.cellToBlock(x), placement.y(), placement.z() + Scale.cellToBlock(y));
	}

	// ---- JSON ----

	private JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.addProperty("game", this.game);
		root.addProperty("exportHash", this.exportHash);
		JsonObject scale = new JsonObject();
		scale.addProperty("cellBlocks", Scale.CELL_BLOCKS);
		scale.addProperty("surfaceY", this.surfaceY);
		scale.addProperty("layerStep", this.layerStep);
		root.add("scale", scale);
		JsonObject maps = new JsonObject();
		for (MapPlacement map : this.maps.values()) {
			JsonObject item = new JsonObject();
			item.addProperty("x", map.x());
			item.addProperty("y", map.y());
			item.addProperty("z", map.z());
			item.addProperty("w", map.w());
			item.addProperty("h", map.h());
			item.addProperty("layer", map.layer());
			item.addProperty("kind", map.kind().id());
			item.addProperty("palette", map.palette());
			item.addProperty("biome", map.biome());
			item.addProperty("dark", map.dark());
			if (map.parent() == null) {
				item.add("parent", JsonNull.INSTANCE);
			} else {
				item.addProperty("parent", map.parent());
			}
			maps.add(map.id(), item);
		}
		root.add("maps", maps);
		root.addProperty("slotMapHash", this.slotMapHash);
		root.add("storyCells", this.storyCells.toJson());
		JsonArray habitats = new JsonArray();
		for (Habitat habitat : this.habitats) {
			JsonObject item = new JsonObject();
			item.addProperty("map", habitat.map());
			item.addProperty("x", habitat.x());
			item.addProperty("y", habitat.y());
			item.addProperty("z", habitat.z());
			item.addProperty("pool", habitat.pool());
			habitats.add(item);
		}
		root.add("habitats", habitats);
		// Wild Routes: written only when there are zones, so a world without any keeps its bytes (and goldens)
		if (!this.zones.isEmpty()) {
			JsonArray zones = new JsonArray();
			for (Zone zone : this.zones) {
				JsonObject item = new JsonObject();
				item.addProperty("id", zone.id());
				item.addProperty("routeMap", zone.routeMap());
				item.addProperty("theme", zone.theme());
				item.addProperty("x", zone.x());
				item.addProperty("y", zone.y());
				item.addProperty("z", zone.z());
				item.addProperty("w", zone.w());
				item.addProperty("d", zone.d());
				item.addProperty("biome", zone.biome());
				zones.add(item);
			}
			root.add("zones", zones);
		}
		return root;
	}

	/** Parses and checks a WorldIndex document against the schema's rules. */
	public static WorldIndex fromJson(String text) throws DataException {
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
		String game = root.string("game");
		if (!GAMES.contains(game)) {
			throw new DataException(root.path("game"), "unknown game");
		}
		String exportHash = root.string("exportHash", DataReader.HASH);
		DataReader scale = root.object("scale").allowOnly(SCALE_FIELDS);
		if (scale.integer("cellBlocks") != Scale.CELL_BLOCKS) {
			throw new DataException(scale.path("cellBlocks"), "must be " + Scale.CELL_BLOCKS);
		}
		int surfaceY = scale.integer("surfaceY");
		int layerStep = scale.integer("layerStep", 16, Integer.MAX_VALUE);
		DataReader mapsReader = root.object("maps");
		List<MapPlacement> maps = new ArrayList<>();
		for (String id : mapsReader.json().keySet()) {
			if (!DataReader.MAP_ID.matcher(id).matches()) {
				throw new DataException(mapsReader.path(), "bad map id");
			}
			DataReader map = mapsReader.object(id).allowOnly(MAP_FIELDS);
			MapPlacement.Kind kind = MapPlacement.Kind.byId(map.string("kind"));
			if (kind == null) {
				throw new DataException(map.path("kind"), "unknown kind");
			}
			maps.add(new MapPlacement(id, map.integer("x"), map.integer("y"), map.integer("z"), map.integer("w", 1, Integer.MAX_VALUE),
					map.integer("h", 1, Integer.MAX_VALUE), map.integer("layer", 0, Integer.MAX_VALUE), kind,
					map.string("palette", DataReader.SYMBOL), map.string("biome", DataReader.RESOURCE_ID), map.bool("dark"),
					map.optionalString("parent", DataReader.MAP_ID)));
		}
		String slotMapHash = root.string("slotMapHash", DataReader.HASH);
		StoryCellTable storyCells = StoryCellTable.fromJson(root.array("storyCells"), "/storyCells");
		List<Habitat> habitats = new ArrayList<>();
		for (DataReader item : DataReader.objects(root.array("habitats"), "/habitats")) {
			item.allowOnly(HABITAT_FIELDS);
			habitats.add(new Habitat(item.string("map", DataReader.MAP_ID), item.integer("x"), item.integer("y"), item.integer("z"),
					item.string("pool", DataReader.RESOURCE_ID)));
		}
		List<Zone> zones = new ArrayList<>();
		if (root.has("zones")) {
			for (DataReader item : DataReader.objects(root.array("zones"), "/zones")) {
				item.allowOnly(ZONE_FIELDS);
				String theme = item.string("theme");
				if (!THEMES.contains(theme)) {
					throw new DataException(item.path("theme"), "unknown theme");
				}
				zones.add(new Zone(item.string("id", ZONE_ID), item.string("routeMap", DataReader.MAP_ID), theme, item.integer("x"),
						item.integer("y"), item.integer("z"), item.integer("w", 1, Integer.MAX_VALUE), item.integer("d", 1, Integer.MAX_VALUE),
						item.string("biome", DataReader.RESOURCE_ID)));
			}
		}
		try {
			return new WorldIndex(game, exportHash, surfaceY, layerStep, maps, slotMapHash, storyCells, habitats, zones);
		} catch (IllegalArgumentException e) {
			throw new DataException("/zones", e.getMessage());
		}
	}
}
