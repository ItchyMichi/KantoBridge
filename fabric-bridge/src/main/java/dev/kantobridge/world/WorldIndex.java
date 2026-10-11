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
			"storyCells", "habitats", "zones", "dungeons");
	private static final Set<String> ZONE_FIELDS = Set.of("id", "routeMap", "theme", "x", "y", "z", "w", "d", "biome", "trainers", "balls",
			"ballSprite", "dungeon");
	private static final Set<String> DUNGEON_FIELDS = Set.of("x", "y", "out", "pool", "level");
	private static final Set<String> DUNGEONS_FIELDS = Set.of("x", "y", "z", "w", "d");
	private static final Set<String> TRAINER_FIELDS = Set.of("id", "x", "y", "facing", "sight", "class", "sprite", "party");
	private static final Set<String> MEMBER_FIELDS = Set.of("species", "level");
	private static final Set<String> BALL_FIELDS = Set.of("id", "x", "y", "item");
	/** A zone's trainer or ball, as its layout names it (protocol defs {@code routeObjectId}), e.g. a2.t1. */
	public static final java.util.regex.Pattern ZONE_OBJECT_ID = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,31}$");
	/** The farthest a zone trainer sees, in cells. */
	public static final int MAX_SIGHT = 8;
	/** The most trainers, and the most balls, one zone lists. */
	public static final int MAX_ZONE_OBJECTS = 64;
	/** Zone cells lie within this many cells of the route map's A. */
	public static final int MAX_ZONE_CELL = 4096;
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
	public record Zone(String id, String routeMap, String theme, int x, int y, int z, int w, int d, String biome, List<ZoneTrainer> trainers,
			List<ZoneBall> balls, @Nullable String ballSprite, @Nullable ZoneDungeon dungeon) {
		public Zone {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(routeMap, "routeMap");
			Objects.requireNonNull(theme, "theme");
			Objects.requireNonNull(biome, "biome");
			if (w < 1 || d < 1) {
				throw new IllegalArgumentException("zone size must be positive");
			}
			trainers = List.copyOf(trainers);
			balls = List.copyOf(balls);
			if (trainers.size() > MAX_ZONE_OBJECTS || balls.size() > MAX_ZONE_OBJECTS) {
				throw new IllegalArgumentException("too many zone objects");
			}
			Set<String> ids = new java.util.HashSet<>();
			for (ZoneTrainer t : trainers) {
				if (!ids.add(t.id())) {
					throw new IllegalArgumentException("duplicate zone object");
				}
			}
			for (ZoneBall b : balls) {
				if (!ids.add(b.id())) {
					throw new IllegalArgumentException("duplicate zone object");
				}
			}
			if (ballSprite != null && !DataReader.SYMBOL.matcher(ballSprite).matches()) {
				throw new IllegalArgumentException("bad ball sprite");
			}
		}

		/** A zone without trainers or balls. */
		public Zone(String id, String routeMap, String theme, int x, int y, int z, int w, int d, String biome) {
			this(id, routeMap, theme, x, y, z, w, d, biome, List.of(), List.of(), null, null);
		}

		/** A zone without a dungeon. */
		public Zone(String id, String routeMap, String theme, int x, int y, int z, int w, int d, String biome, List<ZoneTrainer> trainers,
				List<ZoneBall> balls, @Nullable String ballSprite) {
			this(id, routeMap, theme, x, y, z, w, d, biome, trainers, balls, ballSprite, null);
		}

		/** True when block column (bx, bz) lies inside the zone's box. */
		public boolean containsColumn(int bx, int bz) {
			return bx >= this.x && bx < this.x + this.w && bz >= this.z && bz < this.z + this.d;
		}

		public @Nullable ZoneTrainer trainer(String trainerId) {
			for (ZoneTrainer t : this.trainers) {
				if (t.id().equals(trainerId)) {
					return t;
				}
			}
			return null;
		}

		public @Nullable ZoneBall ball(String ballId) {
			for (ZoneBall b : this.balls) {
				if (b.id().equals(ballId)) {
					return b;
				}
			}
			return null;
		}
	}

	/**
	 * A zone's dungeon (WR7, docs/specs/WILD_ROUTES_PLAN.md §4.8).
	 *
	 * @param x     its entrance cell in the route map's grid
	 * @param out   the way from the entrance to its one open neighbour, where you come back out
	 * @param pool  the habitat pool of the entrance's area (the dungeon's wild Pokémon), or null when the zone has none
	 * @param level that area's highest wild level (the floor count follows it)
	 */
	public record ZoneDungeon(int x, int y, Dir out, @Nullable String pool, int level) {
		public ZoneDungeon {
			if (Math.abs(x) > MAX_ZONE_CELL || Math.abs(y) > MAX_ZONE_CELL || out == null || level < 1 || level > 100
					|| (pool != null && !DataReader.RESOURCE_ID.matcher(pool).matches())) {
				throw new IllegalArgumentException("bad zone dungeon");
			}
		}
	}

	/**
	 * The dungeon region (WR7, docs/specs/WILD_ROUTES_PLAN.md §4.8): the box of every dungeon slot, its first floors' feet
	 * level {@code y}. A player inside it whose hidden player is on a route map is away, as in a zone.
	 */
	public record Dungeons(int x, int y, int z, int w, int d) {
		public Dungeons {
			if (w < 1 || d < 1) {
				throw new IllegalArgumentException("dungeon region size must be positive");
			}
		}

		public boolean containsColumn(int bx, int bz) {
			return bx >= this.x && bx < this.x + this.w && bz >= this.z && bz < this.z + this.d;
		}
	}

	/** One Pokémon of a zone trainer's party: a species id (ROM-derived, never logged) and its level. */
	public record PartyMember(String species, int level) {
		public PartyMember {
			if (species == null || !DataReader.SYMBOL.matcher(species).matches() || level < 1 || level > 100) {
				throw new IllegalArgumentException("bad party member");
			}
		}
	}

	/**
	 * A Wild Routes trainer (docs/specs/WILD_ROUTES_PLAN.md §4.6, WR5).
	 *
	 * @param x      its cell in the route map's grid (the trailhead's A is 0, 0), so {@link #cellOrigin} finds it
	 * @param facing the way it looks
	 * @param sight  how many cells ahead it sees (its lane is clear that far)
	 * @param cls    its Gen 1 trainer class, e.g. OPP_YOUNGSTER
	 * @param sprite its overworld sprite
	 */
	public record ZoneTrainer(String id, int x, int y, Dir facing, int sight, String cls, String sprite, List<PartyMember> party) {
		public ZoneTrainer {
			party = List.copyOf(party);
			if (id == null || !ZONE_OBJECT_ID.matcher(id).matches() || Math.abs(x) > MAX_ZONE_CELL || Math.abs(y) > MAX_ZONE_CELL
					|| facing == null || sight < 1 || sight > MAX_SIGHT || cls == null || !DataReader.SYMBOL.matcher(cls).matches()
					|| sprite == null || !DataReader.SYMBOL.matcher(sprite).matches() || party.isEmpty() || party.size() > 6) {
				throw new IllegalArgumentException("bad zone trainer");
			}
		}

		/** True when cell (cx, cy) of the route map's grid is in this trainer's sight: straight ahead, within range. */
		public boolean sees(int cx, int cy) {
			int dx = cx - this.x;
			int dy = cy - this.y;
			int along = dx * this.facing.dx() + dy * this.facing.dy();
			int across = dx * this.facing.dy() - dy * this.facing.dx();
			return across == 0 && along >= 1 && along <= this.sight;
		}
	}

	/** A Wild Routes item ball (WR5): its cell in the route map's grid and its item. */
	public record ZoneBall(String id, int x, int y, String item) {
		public ZoneBall {
			if (id == null || !ZONE_OBJECT_ID.matcher(id).matches() || Math.abs(x) > MAX_ZONE_CELL || Math.abs(y) > MAX_ZONE_CELL
					|| item == null || !DataReader.SYMBOL.matcher(item).matches()) {
				throw new IllegalArgumentException("bad zone ball");
			}
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
	private final @Nullable Dungeons dungeons;
	private final String json;
	private final String hash;

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats) {
		this(game, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, slotMapHash, storyCells, habitats, List.of(), null);
	}

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats, List<Zone> zones) {
		this(game, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, slotMapHash, storyCells, habitats, zones, null);
	}

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats, List<Zone> zones, @Nullable Dungeons dungeons) {
		this(game, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, slotMapHash, storyCells, habitats, zones, dungeons);
	}

	private WorldIndex(String game, String exportHash, int surfaceY, int layerStep, Collection<MapPlacement> maps, String slotMapHash,
			StoryCellTable storyCells, List<Habitat> habitats, List<Zone> zones, @Nullable Dungeons dungeons) {
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
		this.dungeons = dungeons;
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

	/** The dungeon region (WR7), or null in a world without dungeons. */
	public @Nullable Dungeons dungeons() {
		return this.dungeons;
	}

	/** True when block column (bx, bz) lies in the dungeon region. */
	public boolean inDungeons(int bx, int bz) {
		return this.dungeons != null && this.dungeons.containsColumn(bx, bz);
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
				JsonArray trainers = new JsonArray();
				for (ZoneTrainer t : zone.trainers()) {
					JsonObject o = new JsonObject();
					o.addProperty("id", t.id());
					o.addProperty("x", t.x());
					o.addProperty("y", t.y());
					o.addProperty("facing", t.facing().id());
					o.addProperty("sight", t.sight());
					o.addProperty("class", t.cls());
					o.addProperty("sprite", t.sprite());
					JsonArray party = new JsonArray();
					for (PartyMember m : t.party()) {
						JsonObject member = new JsonObject();
						member.addProperty("species", m.species());
						member.addProperty("level", m.level());
						party.add(member);
					}
					o.add("party", party);
					trainers.add(o);
				}
				item.add("trainers", trainers);
				JsonArray balls = new JsonArray();
				for (ZoneBall b : zone.balls()) {
					JsonObject o = new JsonObject();
					o.addProperty("id", b.id());
					o.addProperty("x", b.x());
					o.addProperty("y", b.y());
					o.addProperty("item", b.item());
					balls.add(o);
				}
				item.add("balls", balls);
				if (zone.ballSprite() != null) {
					item.addProperty("ballSprite", zone.ballSprite());
				}
				if (zone.dungeon() != null) {
					JsonObject dungeon = new JsonObject();
					dungeon.addProperty("x", zone.dungeon().x());
					dungeon.addProperty("y", zone.dungeon().y());
					dungeon.addProperty("out", zone.dungeon().out().id());
					if (zone.dungeon().pool() != null) {
						dungeon.addProperty("pool", zone.dungeon().pool());
					}
					dungeon.addProperty("level", zone.dungeon().level());
					item.add("dungeon", dungeon);
				}
				zones.add(item);
			}
			root.add("zones", zones);
		}
		if (this.dungeons != null) {
			JsonObject region = new JsonObject();
			region.addProperty("x", this.dungeons.x());
			region.addProperty("y", this.dungeons.y());
			region.addProperty("z", this.dungeons.z());
			region.addProperty("w", this.dungeons.w());
			region.addProperty("d", this.dungeons.d());
			root.add("dungeons", region);
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
				List<ZoneTrainer> trainers = new ArrayList<>();
				for (DataReader t : DataReader.objects(item.array("trainers"), item.path("trainers"))) {
					t.allowOnly(TRAINER_FIELDS);
					Dir facing = Dir.byId(t.string("facing"));
					if (facing == null) {
						throw new DataException(t.path("facing"), "unknown facing");
					}
					List<PartyMember> party = new ArrayList<>();
					for (DataReader m : DataReader.objects(t.array("party"), t.path("party"))) {
						m.allowOnly(MEMBER_FIELDS);
						party.add(new PartyMember(m.string("species", DataReader.SYMBOL), m.integer("level", 1, 100)));
					}
					if (party.isEmpty() || party.size() > 6) {
						throw new DataException(t.path("party"), "a party has 1 to 6 Pokémon");
					}
					trainers.add(new ZoneTrainer(t.string("id", ZONE_OBJECT_ID), t.integer("x", -MAX_ZONE_CELL, MAX_ZONE_CELL),
							t.integer("y", -MAX_ZONE_CELL, MAX_ZONE_CELL), facing, t.integer("sight", 1, MAX_SIGHT), t.string("class", DataReader.SYMBOL),
							t.string("sprite", DataReader.SYMBOL), party));
				}
				List<ZoneBall> balls = new ArrayList<>();
				for (DataReader b : DataReader.objects(item.array("balls"), item.path("balls"))) {
					b.allowOnly(BALL_FIELDS);
					balls.add(new ZoneBall(b.string("id", ZONE_OBJECT_ID), b.integer("x", -MAX_ZONE_CELL, MAX_ZONE_CELL),
							b.integer("y", -MAX_ZONE_CELL, MAX_ZONE_CELL), b.string("item", DataReader.SYMBOL)));
				}
				ZoneDungeon dungeon = null;
				if (item.has("dungeon")) {
					DataReader d = item.object("dungeon").allowOnly(DUNGEON_FIELDS);
					Dir out = Dir.byId(d.string("out"));
					if (out == null) {
						throw new DataException(d.path("out"), "unknown direction");
					}
					dungeon = new ZoneDungeon(d.integer("x", -MAX_ZONE_CELL, MAX_ZONE_CELL), d.integer("y", -MAX_ZONE_CELL, MAX_ZONE_CELL), out,
							d.optionalString("pool", DataReader.RESOURCE_ID), d.integer("level", 1, 100));
				}
				zones.add(new Zone(item.string("id", ZONE_ID), item.string("routeMap", DataReader.MAP_ID), theme, item.integer("x"),
						item.integer("y"), item.integer("z"), item.integer("w", 1, Integer.MAX_VALUE), item.integer("d", 1, Integer.MAX_VALUE),
						item.string("biome", DataReader.RESOURCE_ID), trainers, balls, item.optionalString("ballSprite", DataReader.SYMBOL), dungeon));
			}
		}
		Dungeons dungeons = null;
		if (root.has("dungeons")) {
			DataReader region = root.object("dungeons").allowOnly(DUNGEONS_FIELDS);
			dungeons = new Dungeons(region.integer("x"), region.integer("y"), region.integer("z"), region.integer("w", 1, Integer.MAX_VALUE),
					region.integer("d", 1, Integer.MAX_VALUE));
		}
		try {
			return new WorldIndex(game, exportHash, surfaceY, layerStep, maps, slotMapHash, storyCells, habitats, zones, dungeons);
		} catch (IllegalArgumentException e) {
			throw new DataException("/zones", e.getMessage());
		}
	}
}
