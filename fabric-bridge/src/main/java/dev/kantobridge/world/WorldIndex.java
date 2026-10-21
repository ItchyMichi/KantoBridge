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
 * join (protocol/world/world_index.schema.json): the scale, every map's placement (and, for a map whose cells don't all
 * sit there, its {@link MapLayout}), the slot-map hash, the story-cell table, the habitat blocks and (Wild Routes) the
 * zones. Clients turn block positions into cells and back with it.
 *
 * <p>The JSON is canonical (sorted keys, compact), so the same index always has the same text and hash.
 */
public final class WorldIndex {
	public static final int VERSION = 1;

	private static final Set<String> ROOT_FIELDS = Set.of("version", "game", "family", "exportHash", "scale", "maps", "slotMapHash",
			"storyCells", "habitats", "zones", "dungeons", "shafts");
	private static final Set<String> SHAFT_FIELDS = Set.of("kind", "top", "bottom", "up", "x", "z", "y0", "y1", "wall");
	private static final Set<String> SHAFT_END_FIELDS = Set.of("map", "x", "y");
	/** The longest shaft, in blocks. */
	public static final int MAX_SHAFT = 256;
	private static final Set<String> ZONE_FIELDS = Set.of("id", "routeMap", "theme", "x", "y", "z", "w", "d", "biome", "trainers", "balls",
			"ballSprite", "dungeon", "pass", "gateHouse");
	private static final Set<String> PASS_FIELDS = Set.of("cells", "route", "toRegion", "end");
	private static final Set<String> GATE_HOUSE_FIELDS = Set.of("x", "y", "w", "d", "facing");
	/** The most pass cells a gate house's far doorway spans. */
	public static final int MAX_PASS_CELLS = 4;
	/** The biggest gate house footprint, in cells. */
	public static final int MAX_GATE_HOUSE = 16;
	private static final Set<String> DUNGEON_FIELDS = Set.of("x", "y", "out", "pool", "level", "items");
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
			"parent", "pieces", "levels", "ramps", "ceilings");
	private static final Set<String> HABITAT_FIELDS = Set.of("map", "x", "y", "z", "pool", "range");
	/** A habitat block's {@code RangeOfInfluence} unless it says otherwise (PLAN §4.5). */
	public static final int HABITAT_RANGE = 14;
	/** How far above or below a map's feet level a block still counts as on that map (layers are 22 apart). */
	private static final int LAYER_REACH_DOWN = 4;
	private static final int LAYER_REACH_UP = 6;
	/**
	 * The same for a cell of a map with a layout, from the cell's own feet level: narrower, since the cave area stacks
	 * pieces 11 blocks apart (docs/specs/CAVES_3D_PLAN.md §3.1, HD3).
	 */
	private static final int PIECE_REACH_DOWN = 3;
	private static final int PIECE_REACH_UP = 5;

	/**
	 * One habitat block (M6).
	 *
	 * @param range its {@code RangeOfInfluence}: {@link #HABITAT_RANGE}, or less in the cave area, where floors lie 11
	 *              blocks apart (docs/specs/CAVES_3D_PLAN.md §3.3, HD8)
	 */
	public record Habitat(String map, int x, int y, int z, String pool, int range) {
		public Habitat {
			if (range < 1 || range > 64) {
				throw new IllegalArgumentException("bad habitat range");
			}
		}

		/** A habitat block of the usual range. */
		public Habitat(String map, int x, int y, int z, String pool) {
			this(map, x, y, z, pool, HABITAT_RANGE);
		}
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
			List<ZoneBall> balls, @Nullable String ballSprite, @Nullable ZoneDungeon dungeon, @Nullable Pass pass, @Nullable GateHouse gateHouse) {
		public Zone {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(routeMap, "routeMap");
			Objects.requireNonNull(theme, "theme");
			Objects.requireNonNull(biome, "biome");
			if (w < 1 || d < 1) {
				throw new IllegalArgumentException("zone size must be positive");
			}
			if ((pass == null) != (gateHouse == null)) {
				throw new IllegalArgumentException("a pass comes with its gate house");
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

		/** A free-roam zone (no pass): every zone before MG5. */
		public Zone(String id, String routeMap, String theme, int x, int y, int z, int w, int d, String biome, List<ZoneTrainer> trainers,
				List<ZoneBall> balls, @Nullable String ballSprite, @Nullable ZoneDungeon dungeon) {
			this(id, routeMap, theme, x, y, z, w, d, biome, trainers, balls, ballSprite, dungeon, null, null);
		}

		/** True when cell (cx, cy) of the route map's grid is one of this zone's pass cells (MG5). */
		public boolean passCell(int cx, int cy) {
			if (this.pass == null) {
				return false;
			}
			for (int[] cell : this.pass.cells()) {
				if (cell[0] == cx && cell[1] == cy) {
					return true;
				}
			}
			return false;
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
	 * A connecting route's pass (docs/specs/MULTI_GAME_PLAN.md §4.8, D12; MG5): the far doorway of this half's gate house,
	 * where a crossing into the other region starts.
	 *
	 * @param cells    the pass cells in the route map's grid (the trailhead's A is 0, 0), each {@code {x, y}}
	 * @param route    the connecting route's id (both halves share it)
	 * @param toRegion the region of the other half
	 * @param end      which end of the route this half is: {@code from} or {@code to}
	 */
	public record Pass(List<int[]> cells, String route, String toRegion, String end) {
		public static final String END_FROM = "from";
		public static final String END_TO = "to";

		public Pass {
			cells = cells.stream().map(c -> new int[] {c[0], c[1]}).toList();
			if (cells.isEmpty() || cells.size() > MAX_PASS_CELLS || route == null || !ZONE_ID.matcher(route).matches() || toRegion == null
					|| !Games.REGION_ID.matcher(toRegion).matches() || !(END_FROM.equals(end) || END_TO.equals(end))) {
				throw new IllegalArgumentException("bad pass");
			}
			for (int[] cell : cells) {
				if (cell.length != 2 || Math.abs(cell[0]) > MAX_ZONE_CELL || Math.abs(cell[1]) > MAX_ZONE_CELL) {
					throw new IllegalArgumentException("bad pass cell");
				}
			}
		}

		/** The end a player standing in this half heads for when crossing: the other one. */
		public String dir() {
			return END_FROM.equals(this.end) ? END_TO : END_FROM;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Pass other) || !this.route.equals(other.route) || !this.toRegion.equals(other.toRegion)
					|| !this.end.equals(other.end) || this.cells.size() != other.cells.size()) {
				return false;
			}
			for (int i = 0; i < this.cells.size(); i++) {
				if (!java.util.Arrays.equals(this.cells.get(i), other.cells.get(i))) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			int h = Objects.hash(this.route, this.toRegion, this.end);
			for (int[] cell : this.cells) {
				h = 31 * h + java.util.Arrays.hashCode(cell);
			}
			return h;
		}
	}

	/**
	 * The gate house at a connecting route half's far gate (MG5, D12): its footprint in the route map's grid (walls
	 * included), and the way from its near doorway toward the pass.
	 *
	 * @param x      the footprint's north-west cell
	 * @param w      the footprint's width in cells (X)
	 * @param d      the footprint's depth in cells (Y)
	 * @param facing from the near doorway toward the pass (the server teleports a crossing player just inside the pass,
	 *               one cell against it)
	 */
	public record GateHouse(int x, int y, int w, int d, Dir facing) {
		public GateHouse {
			if (Math.abs(x) > MAX_ZONE_CELL || Math.abs(y) > MAX_ZONE_CELL || w < 1 || w > MAX_GATE_HOUSE || d < 1 || d > MAX_GATE_HOUSE
					|| facing == null) {
				throw new IllegalArgumentException("bad gate house");
			}
		}

		/** True when cell (cx, cy) of the route map's grid lies in the footprint. */
		public boolean contains(int cx, int cy) {
			return cx >= this.x && cx < this.x + this.w && cy >= this.y && cy < this.y + this.d;
		}
	}

	/**
	 * A zone's dungeon (WR7, docs/specs/WILD_ROUTES_PLAN.md §4.8).
	 *
	 * @param x     its entrance cell in the route map's grid
	 * @param out   the way from the entrance to its one open neighbour, where you come back out
	 * @param pool  the habitat pool of the entrance's area (the dungeon's wild Pokémon), or null when the zone has none
	 * @param level that area's highest wild level (the floor count follows it)
	 * @param items the items its floors may hold: the game's own, by tier, without the ones a dungeon never gives
	 */
	public record ZoneDungeon(int x, int y, Dir out, @Nullable String pool, int level, List<String> items) {
		public ZoneDungeon {
			items = List.copyOf(items);
			if (Math.abs(x) > MAX_ZONE_CELL || Math.abs(y) > MAX_ZONE_CELL || out == null || level < 1 || level > 100
					|| (pool != null && !DataReader.RESOURCE_ID.matcher(pool).matches()) || items.size() > 64
					|| !items.stream().allMatch(i -> DataReader.SYMBOL.matcher(i).matches())) {
				throw new IllegalArgumentException("bad zone dungeon");
			}
		}

		/** A dungeon whose floors hold no items. */
		public ZoneDungeon(int x, int y, Dir out, @Nullable String pool, int level) {
			this(x, y, out, pool, level, List.of());
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

	/**
	 * A cave's real ladder or hole (docs/specs/CAVES_3D_PLAN.md §3.5, §4.1): its top cell straight over its bottom cell,
	 * one 2 × 2 column at ({@code x}, {@code z}) from the bottom cell's feet {@code y0} up to the top cell's floor
	 * {@code y1}.
	 *
	 * @param kind {@code ladder} or {@code hole}
	 * @param up   it is climbed up too (a hole, or a ladder whose bottom leads elsewhere, isn't)
	 * @param wall the side of the column a ladder stands on (HD6); null for a hole
	 */
	public record Shaft(String kind, CellPos top, CellPos bottom, boolean up, int x, int z, int y0, int y1, @Nullable Dir wall) {
		public static final String LADDER = "ladder";
		public static final String HOLE = "hole";

		public Shaft {
			if (!LADDER.equals(kind) && !HOLE.equals(kind) || top == null || bottom == null || y1 <= y0 || y1 - y0 > MAX_SHAFT
					|| HOLE.equals(kind) && up || LADDER.equals(kind) != (wall != null)) {
				throw new IllegalArgumentException("bad shaft");
			}
		}

		/**
		 * True when block (bx, by, bz) is inside the shaft: its column, over the bottom cell's feet, up to the top cell's
		 * floor. A ladder's top cell keeps its floor beside the ladder, to step off onto.
		 */
		public boolean contains(int bx, int by, int bz) {
			return bx >= this.x && bx < this.x + Scale.CELL_BLOCKS && bz >= this.z && bz < this.z + Scale.CELL_BLOCKS && by > this.y0
					&& (by < this.y1 || by == this.y1 && (this.wall == null || this.ladderAt(bx, bz)));
		}

		/** True when block column (bx, bz) is in the half of the shaft's column along its ladder's wall. */
		public boolean ladderAt(int bx, int bz) {
			if (this.wall == null || bx < this.x || bx >= this.x + Scale.CELL_BLOCKS || bz < this.z || bz >= this.z + Scale.CELL_BLOCKS) {
				return false;
			}
			return switch (this.wall) {
				case UP -> bz == this.z;
				case DOWN -> bz == this.z + 1;
				case LEFT -> bx == this.x;
				case RIGHT -> bx == this.x + 1;
			};
		}

		/** True when {@code cell} is one of its two ends. */
		public boolean end(CellPos cell) {
			return this.top.equals(cell) || this.bottom.equals(cell);
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
	/** The game's engine family when the index says it (MG1); a legacy index says nothing. */
	private final @Nullable String family;
	private final String exportHash;
	private final int surfaceY;
	private final int layerStep;
	private final Map<String, MapPlacement> maps;
	private final Map<String, MapLayout> layouts;
	private final String slotMapHash;
	private final StoryCellTable storyCells;
	private final List<Habitat> habitats;
	private final List<Zone> zones;
	private final @Nullable Dungeons dungeons;
	private final List<Shaft> shafts;
	private final String json;
	private final String hash;

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats) {
		this(game, null, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, Map.of(), slotMapHash, storyCells, habitats, List.of(), null,
				List.of());
	}

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats, List<Zone> zones) {
		this(game, null, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, Map.of(), slotMapHash, storyCells, habitats, zones, null,
				List.of());
	}

	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, String slotMapHash, StoryCellTable storyCells,
			List<Habitat> habitats, List<Zone> zones, @Nullable Dungeons dungeons) {
		this(game, null, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, Map.of(), slotMapHash, storyCells, habitats, zones, dungeons,
				List.of());
	}

	/**
	 * @param layouts the layouts of maps whose cells don't all sit at their placement (CAVES_3D_PLAN §3.1), by map id;
	 *                trivial ones are dropped
	 */
	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, Map<String, MapLayout> layouts, String slotMapHash,
			StoryCellTable storyCells, List<Habitat> habitats, List<Zone> zones, @Nullable Dungeons dungeons) {
		this(game, null, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, layouts, slotMapHash, storyCells, habitats, zones, dungeons,
				List.of());
	}

	/** The same with the caves' shafts (CAVES_3D_PLAN §3.5). */
	public WorldIndex(String game, String exportHash, Collection<MapPlacement> maps, Map<String, MapLayout> layouts, String slotMapHash,
			StoryCellTable storyCells, List<Habitat> habitats, List<Zone> zones, @Nullable Dungeons dungeons, List<Shaft> shafts) {
		this(game, null, exportHash, Scale.SURFACE_Y, Scale.LAYER_STEP, maps, layouts, slotMapHash, storyCells, habitats, zones, dungeons, shafts);
	}

	private WorldIndex(String game, @Nullable String family, String exportHash, int surfaceY, int layerStep, Collection<MapPlacement> maps,
			Map<String, MapLayout> layouts, String slotMapHash, StoryCellTable storyCells, List<Habitat> habitats, List<Zone> zones,
			@Nullable Dungeons dungeons, List<Shaft> shafts) {
		if (!Games.known(game)) {
			throw new IllegalArgumentException("unknown game");
		}
		if (family != null && !Games.FAMILIES.contains(family)) {
			throw new IllegalArgumentException("unknown family");
		}
		if (!DataReader.HASH.matcher(exportHash).matches() || !DataReader.HASH.matcher(slotMapHash).matches()) {
			throw new IllegalArgumentException("invalid hash");
		}
		this.game = game;
		this.family = family;
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
		Map<String, MapLayout> layoutsById = new TreeMap<>();
		for (Map.Entry<String, MapLayout> entry : layouts.entrySet()) {
			MapPlacement map = byId.get(entry.getKey());
			MapLayout layout = Objects.requireNonNull(entry.getValue(), "layout");
			if (map == null || layout.w() != map.w() || layout.h() != map.h()) {
				throw new IllegalArgumentException("a layout must fit a placed map");
			}
			if (!layout.trivial()) {
				layoutsById.put(entry.getKey(), layout);
			}
		}
		this.layouts = Collections.unmodifiableMap(layoutsById);
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
		for (Shaft shaft : shafts) {
			if (!byId.containsKey(shaft.top().map()) || !byId.containsKey(shaft.bottom().map())) {
				throw new IllegalArgumentException("a shaft's ends must be on placed maps");
			}
		}
		this.shafts = List.copyOf(shafts);
		this.json = ProtoJson.write(this.toJsonObject());
		this.hash = Hashes.sha256(this.json);
	}

	public String game() {
		return this.game;
	}

	/** The game's engine family as the index records it (MG1), or null for an index that doesn't say (every legacy index). */
	public @Nullable String family() {
		return this.family;
	}

	/**
	 * The same index recording {@code family} (null for none): a region's index names its game's family; a legacy world's
	 * never does, so every golden stays byte-identical.
	 */
	public WorldIndex withFamily(@Nullable String family) {
		return new WorldIndex(this.game, family, this.exportHash, this.surfaceY, this.layerStep, this.maps.values(), this.layouts,
				this.slotMapHash, this.storyCells, this.habitats, this.zones, this.dungeons, this.shafts);
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

	/** The layouts of maps whose cells don't all sit at their placement, by id (sorted; none in most worlds). */
	public Map<String, MapLayout> layouts() {
		return this.layouts;
	}

	/** A map's layout, or null when its cells all sit at its placement (one piece, level 0). */
	public @Nullable MapLayout layout(String id) {
		return this.layouts.get(id);
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
	/** The caves' shafts (CAVES_3D_PLAN §3.5). */
	public List<Shaft> shafts() {
		return this.shafts;
	}

	/** The shaft whose inside holds block (x, y, z), or null. */
	public @Nullable Shaft shaftAt(int x, int y, int z) {
		for (Shaft shaft : this.shafts) {
			if (shaft.contains(x, y, z)) {
				return shaft;
			}
		}
		return null;
	}

	/** The shaft {@code cell} is an end of, or null. */
	public @Nullable Shaft shaftEnd(CellPos cell) {
		for (Shaft shaft : this.shafts) {
			if (shaft.end(cell)) {
				return shaft;
			}
		}
		return null;
	}

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
	 * few blocks of {@code y}; for a map with a layout, the piece whose cell holds the column and whose floor is within
	 * a few blocks of {@code y}. Empty outside every map (gaps, borders, between layers and pieces).
	 */
	public Optional<CellPos> toCell(int x, int y, int z) {
		if (this.shaftAt(x, y, z) != null) {
			// Inside a shaft: between floors, in no cell
			return Optional.empty();
		}
		for (MapPlacement map : this.maps.values()) {
			MapLayout layout = this.layouts.get(map.id());
			if (layout != null) {
				CellPos cell = pieceCell(map, layout, x, y, z);
				if (cell != null) {
					return Optional.of(cell);
				}
				continue;
			}
			if (y < map.y() - LAYER_REACH_DOWN || y >= map.y() + LAYER_REACH_UP || !map.containsColumn(x, z)) {
				continue;
			}
			return Optional.of(new CellPos(map.id(), Scale.blockToCell(x - map.x()), Scale.blockToCell(z - map.z())));
		}
		return Optional.empty();
	}

	private static @Nullable CellPos pieceCell(MapPlacement map, MapLayout layout, int x, int y, int z) {
		for (MapLayout.Piece piece : layout.pieces()) {
			int cx = Scale.blockToCell(x - map.x() - piece.dx());
			int cy = Scale.blockToCell(z - map.z() - piece.dz());
			if (!piece.contains(cx, cy)) {
				continue;
			}
			int feet = map.y() + piece.dy() + layout.level(cx, cy);
			if (y >= feet - PIECE_REACH_DOWN && y < feet + PIECE_REACH_UP) {
				return new CellPos(map.id(), cx, cy);
			}
		}
		return null;
	}

	public Optional<CellPos> toCell(BlockPos pos) {
		return this.toCell(pos.getX(), pos.getY(), pos.getZ());
	}

	/** The centre of a cell's 2×2 footprint at its feet level, or null for an unknown map. */
	public @Nullable Vec3 toBlock(String map, int x, int y) {
		BlockPos origin = this.cellOrigin(map, x, y);
		if (origin == null) {
			return null;
		}
		// A ramp's middle is where its second and third half steps meet: whoever stands there stands on the third.
		MapLayout layout = this.layouts.get(map);
		MapLayout.Ramp ramp = layout == null ? null : layout.ramp(x, y);
		return new Vec3(origin.getX() + 1.0, origin.getY() + (ramp == null ? 0.0 : ramp.rise() * 0.75), origin.getZ() + 1.0);
	}

	/**
	 * How far the floor has risen under point (cx, cy) of cell (rx, ry), both in cells (docs/specs/CAVES_3D_PLAN.md §3.4):
	 * on a ramp, from 0 at its low edge to its rise at its high edge, evenly; 0 off one.
	 */
	public double rampLift(String map, int rx, int ry, double cx, double cy) {
		MapLayout layout = this.layouts.get(map);
		MapLayout.Ramp ramp = layout == null ? null : layout.ramp(rx, ry);
		if (ramp == null) {
			return 0.0;
		}
		double along = switch (ramp.dir()) {
			case RIGHT -> cx - (rx - 0.5);
			case LEFT -> rx + 0.5 - cx;
			case DOWN -> cy - (ry - 0.5);
			case UP -> ry + 0.5 - cy;
		};
		return ramp.rise() * Math.max(0.0, Math.min(1.0, along));
	}

	/** True when cell (x, y) of a map is a ramp. */
	public boolean ramp(String map, int x, int y) {
		MapLayout layout = this.layouts.get(map);
		return layout != null && layout.ramp(x, y) != null;
	}

	/**
	 * The north-west block of a cell at its feet level (its piece's offset and its level included), or null for an
	 * unknown map. A cell outside the map, or in no piece, sits at the map's placement.
	 */
	public @Nullable BlockPos cellOrigin(String map, int x, int y) {
		MapPlacement placement = this.maps.get(map);
		if (placement == null) {
			return null;
		}
		MapLayout layout = this.layouts.get(map);
		MapLayout.Piece piece = layout == null ? null : layout.pieceAt(x, y);
		int level = layout == null ? 0 : layout.level(x, y);
		return new BlockPos(placement.x() + Scale.cellToBlock(x) + (piece == null ? 0 : piece.dx()),
				placement.y() + (piece == null ? 0 : piece.dy()) + level, placement.z() + Scale.cellToBlock(y) + (piece == null ? 0 : piece.dz()));
	}

	/**
	 * The north-west block of a cell at its level's feet: its piece's feet level, without the cell's own raise. Walking
	 * never leaves a level (ramps climb within it), so this is the level a movement {@code Spot} keeps. Null for an
	 * unknown map.
	 */
	public @Nullable BlockPos levelOrigin(String map, int x, int y) {
		BlockPos origin = this.cellOrigin(map, x, y);
		MapLayout layout = this.layouts.get(map);
		return origin == null || layout == null ? origin : origin.below(layout.level(x, y));
	}

	// ---- regions (MG3) ----

	/**
	 * The box every placed block of this index lies in (docs/specs/MULTI_GAME_PLAN.md §4.6: the implicit legacy
	 * region's bounds): the maps' footprints (a map with a layout by its pieces, up to their highest level) with the
	 * reach a cell has below and above its feet, the zones' boxes, the dungeon region and the shafts. Inclusive on
	 * every side; null for an index without maps.
	 */
	public @Nullable RegionRecord.Box boundsOfPlacements() {
		int[] box = null;
		for (MapPlacement map : this.maps.values()) {
			MapLayout layout = this.layouts.get(map.id());
			if (layout == null) {
				box = extend(box, map.x(), map.z(), map.x() + Scale.cellToBlock(map.w()) - 1, map.z() + Scale.cellToBlock(map.h()) - 1,
						map.y() - LAYER_REACH_DOWN, map.y() + LAYER_REACH_UP - 1);
				continue;
			}
			for (MapLayout.Piece piece : layout.pieces()) {
				int maxLevel = 0;
				// its ceilings' blocks (HEIGHTS_ALL_GAMES_PLAN D5) count too
				int top = 0;
				for (int cy = piece.y(); cy < piece.y() + piece.h(); cy++) {
					for (int cx = piece.x(); cx < piece.x() + piece.w(); cx++) {
						if (piece.contains(cx, cy)) {
							maxLevel = Math.max(maxLevel, layout.level(cx, cy));
							top = Math.max(top, layout.ceiling(cx, cy));
						}
					}
				}
				int x0 = map.x() + piece.dx() + Scale.cellToBlock(piece.x());
				int z0 = map.z() + piece.dz() + Scale.cellToBlock(piece.y());
				int feet = map.y() + piece.dy();
				box = extend(box, x0, z0, x0 + Scale.cellToBlock(piece.w()) - 1, z0 + Scale.cellToBlock(piece.h()) - 1, feet - PIECE_REACH_DOWN,
						feet + Math.max(maxLevel + PIECE_REACH_UP - 1, top));
			}
		}
		for (Zone zone : this.zones) {
			box = extend(box, zone.x(), zone.z(), zone.x() + zone.w() - 1, zone.z() + zone.d() - 1, zone.y() - LAYER_REACH_DOWN,
					zone.y() + LAYER_REACH_UP - 1);
		}
		if (this.dungeons != null) {
			box = extend(box, this.dungeons.x(), this.dungeons.z(), this.dungeons.x() + this.dungeons.w() - 1,
					this.dungeons.z() + this.dungeons.d() - 1, this.dungeons.y() - LAYER_REACH_DOWN, this.dungeons.y() + LAYER_REACH_UP - 1);
		}
		for (Shaft shaft : this.shafts) {
			box = extend(box, shaft.x(), shaft.z(), shaft.x() + Scale.CELL_BLOCKS - 1, shaft.z() + Scale.CELL_BLOCKS - 1, shaft.y0(), shaft.y1());
		}
		return box == null ? null : new RegionRecord.Box(box[0], box[1], box[2], box[3], box[4], box[5]);
	}

	private static int[] extend(int @Nullable [] box, int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
		if (box == null) {
			return new int[] {minX, minZ, maxX, maxZ, minY, maxY};
		}
		box[0] = Math.min(box[0], minX);
		box[1] = Math.min(box[1], minZ);
		box[2] = Math.max(box[2], maxX);
		box[3] = Math.max(box[3], maxZ);
		box[4] = Math.min(box[4], minY);
		box[5] = Math.max(box[5], maxY);
		return box;
	}

	// ---- JSON ----

	private JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.addProperty("game", this.game);
		if (this.family != null) {
			root.addProperty("family", this.family);
		}
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
			MapLayout layout = this.layouts.get(map.id());
			if (layout != null) {
				layout.writeTo(item);
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
			if (habitat.range() != HABITAT_RANGE) {
				item.addProperty("range", habitat.range());
			}
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
					JsonArray items = new JsonArray();
					zone.dungeon().items().forEach(items::add);
					dungeon.add("items", items);
					item.add("dungeon", dungeon);
				}
				// MG5: a connecting route's half, written only when the zone has a pass (free-roam zones keep their bytes)
				if (zone.pass() != null && zone.gateHouse() != null) {
					JsonObject pass = new JsonObject();
					JsonArray cells = new JsonArray();
					for (int[] cell : zone.pass().cells()) {
						JsonArray c = new JsonArray();
						c.add(cell[0]);
						c.add(cell[1]);
						cells.add(c);
					}
					pass.add("cells", cells);
					pass.addProperty("route", zone.pass().route());
					pass.addProperty("toRegion", zone.pass().toRegion());
					pass.addProperty("end", zone.pass().end());
					item.add("pass", pass);
					JsonObject house = new JsonObject();
					house.addProperty("x", zone.gateHouse().x());
					house.addProperty("y", zone.gateHouse().y());
					house.addProperty("w", zone.gateHouse().w());
					house.addProperty("d", zone.gateHouse().d());
					house.addProperty("facing", zone.gateHouse().facing().id());
					item.add("gateHouse", house);
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
		if (!this.shafts.isEmpty()) {
			JsonArray shafts = new JsonArray();
			for (Shaft shaft : this.shafts) {
				JsonObject item = new JsonObject();
				item.addProperty("kind", shaft.kind());
				item.add("top", end(shaft.top()));
				item.add("bottom", end(shaft.bottom()));
				item.addProperty("up", shaft.up());
				item.addProperty("x", shaft.x());
				item.addProperty("z", shaft.z());
				item.addProperty("y0", shaft.y0());
				item.addProperty("y1", shaft.y1());
				if (shaft.wall() != null) {
					item.addProperty("wall", shaft.wall().id());
				}
				shafts.add(item);
			}
			root.add("shafts", shafts);
		}
		return root;
	}

	private static JsonObject end(CellPos cell) {
		JsonObject out = new JsonObject();
		out.addProperty("map", cell.map());
		out.addProperty("x", cell.x());
		out.addProperty("y", cell.y());
		return out;
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
		if (!Games.known(game)) {
			throw new DataException(root.path("game"), "unknown game");
		}
		String family = root.has("family") ? root.string("family") : null;
		if (family != null && !Games.FAMILIES.contains(family)) {
			throw new DataException(root.path("family"), "unknown family");
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
		Map<String, MapLayout> layouts = new TreeMap<>();
		for (String id : mapsReader.json().keySet()) {
			if (!DataReader.MAP_ID.matcher(id).matches()) {
				throw new DataException(mapsReader.path(), "bad map id");
			}
			DataReader map = mapsReader.object(id).allowOnly(MAP_FIELDS);
			MapPlacement.Kind kind = MapPlacement.Kind.byId(map.string("kind"));
			if (kind == null) {
				throw new DataException(map.path("kind"), "unknown kind");
			}
			MapPlacement placement = new MapPlacement(id, map.integer("x"), map.integer("y"), map.integer("z"),
					map.integer("w", 1, Integer.MAX_VALUE), map.integer("h", 1, Integer.MAX_VALUE), map.integer("layer", 0, Integer.MAX_VALUE), kind,
					map.string("palette", DataReader.SYMBOL), map.string("biome", DataReader.RESOURCE_ID), map.bool("dark"),
					map.optionalString("parent", DataReader.MAP_ID));
			maps.add(placement);
			MapLayout layout = MapLayout.read(map, placement.w(), placement.h());
			if (layout != null) {
				layouts.put(id, layout);
			}
		}
		String slotMapHash = root.string("slotMapHash", DataReader.HASH);
		StoryCellTable storyCells = StoryCellTable.fromJson(root.array("storyCells"), "/storyCells");
		List<Habitat> habitats = new ArrayList<>();
		for (DataReader item : DataReader.objects(root.array("habitats"), "/habitats")) {
			item.allowOnly(HABITAT_FIELDS);
			habitats.add(new Habitat(item.string("map", DataReader.MAP_ID), item.integer("x"), item.integer("y"), item.integer("z"),
					item.string("pool", DataReader.RESOURCE_ID), item.has("range") ? item.integer("range", 1, 64) : HABITAT_RANGE));
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
					List<String> items = new ArrayList<>();
					for (com.google.gson.JsonElement i : d.array("items")) {
						if (!i.isJsonPrimitive() || !DataReader.SYMBOL.matcher(i.getAsString()).matches()) {
							throw new DataException(d.path("items"), "bad item id");
						}
						items.add(i.getAsString());
					}
					dungeon = new ZoneDungeon(d.integer("x", -MAX_ZONE_CELL, MAX_ZONE_CELL), d.integer("y", -MAX_ZONE_CELL, MAX_ZONE_CELL), out,
							d.optionalString("pool", DataReader.RESOURCE_ID), d.integer("level", 1, 100), items);
				}
				// MG5: the pass and its gate house come together or not at all
				if (item.has("pass") != item.has("gateHouse")) {
					throw new DataException(item.path(), "a pass comes with its gate house");
				}
				Pass pass = null;
				GateHouse gateHouse = null;
				if (item.has("pass")) {
					DataReader p = item.object("pass").allowOnly(PASS_FIELDS);
					List<int[]> cells = new ArrayList<>();
					for (com.google.gson.JsonElement c : p.array("cells")) {
						if (!c.isJsonArray()) {
							throw new DataException(p.path("cells"), "a pass cell is [x, y]");
						}
						int[] cell = DataReader.intArray(c.getAsJsonArray(), p.path("cells"), -MAX_ZONE_CELL, MAX_ZONE_CELL);
						if (cell.length != 2) {
							throw new DataException(p.path("cells"), "a pass cell is [x, y]");
						}
						cells.add(cell);
					}
					if (cells.isEmpty() || cells.size() > MAX_PASS_CELLS) {
						throw new DataException(p.path("cells"), "a pass has 1 to " + MAX_PASS_CELLS + " cells");
					}
					String end = p.string("end");
					if (!Pass.END_FROM.equals(end) && !Pass.END_TO.equals(end)) {
						throw new DataException(p.path("end"), "unknown end");
					}
					pass = new Pass(cells, p.string("route", ZONE_ID), p.string("toRegion", Games.REGION_ID), end);
					DataReader h = item.object("gateHouse").allowOnly(GATE_HOUSE_FIELDS);
					Dir facing = Dir.byId(h.string("facing"));
					if (facing == null) {
						throw new DataException(h.path("facing"), "unknown facing");
					}
					gateHouse = new GateHouse(h.integer("x", -MAX_ZONE_CELL, MAX_ZONE_CELL), h.integer("y", -MAX_ZONE_CELL, MAX_ZONE_CELL),
							h.integer("w", 1, MAX_GATE_HOUSE), h.integer("d", 1, MAX_GATE_HOUSE), facing);
				}
				zones.add(new Zone(item.string("id", ZONE_ID), item.string("routeMap", DataReader.MAP_ID), theme, item.integer("x"),
						item.integer("y"), item.integer("z"), item.integer("w", 1, Integer.MAX_VALUE), item.integer("d", 1, Integer.MAX_VALUE),
						item.string("biome", DataReader.RESOURCE_ID), trainers, balls, item.optionalString("ballSprite", DataReader.SYMBOL), dungeon,
						pass, gateHouse));
			}
		}
		Dungeons dungeons = null;
		if (root.has("dungeons")) {
			DataReader region = root.object("dungeons").allowOnly(DUNGEONS_FIELDS);
			dungeons = new Dungeons(region.integer("x"), region.integer("y"), region.integer("z"), region.integer("w", 1, Integer.MAX_VALUE),
					region.integer("d", 1, Integer.MAX_VALUE));
		}
		List<Shaft> shafts = new ArrayList<>();
		if (root.has("shafts")) {
			for (DataReader item : DataReader.objects(root.array("shafts"), "/shafts")) {
				item.allowOnly(SHAFT_FIELDS);
				DataReader top = item.object("top").allowOnly(SHAFT_END_FIELDS);
				DataReader bottom = item.object("bottom").allowOnly(SHAFT_END_FIELDS);
				String kind = item.string("kind", DataReader.SYMBOL);
				int y0 = item.integer("y0");
				int y1 = item.integer("y1");
				boolean up = item.bool("up");
				Dir wall = item.has("wall") ? Dir.byId(item.string("wall", DataReader.SYMBOL)) : null;
				if (!Shaft.LADDER.equals(kind) && !Shaft.HOLE.equals(kind) || y1 <= y0 || y1 - y0 > MAX_SHAFT || Shaft.HOLE.equals(kind) && up
						|| Shaft.LADDER.equals(kind) != (wall != null)) {
					throw new DataException("/shafts", "bad shaft");
				}
				shafts.add(new Shaft(kind, new CellPos(top.string("map", DataReader.MAP_ID), top.integer("x", 0, 4096), top.integer("y", 0, 4096)),
						new CellPos(bottom.string("map", DataReader.MAP_ID), bottom.integer("x", 0, 4096), bottom.integer("y", 0, 4096)), up,
						item.integer("x"), item.integer("z"), y0, y1, wall));
			}
		}
		try {
			return new WorldIndex(game, family, exportHash, surfaceY, layerStep, maps, layouts, slotMapHash, storyCells, habitats, zones,
					dungeons, shafts);
		} catch (IllegalArgumentException e) {
			throw new DataException("/zones", e.getMessage());
		}
	}
}
