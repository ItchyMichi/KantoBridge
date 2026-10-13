package dev.kantobridge.builder.zone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.block.PropBlock;
import dev.kantobridge.builder.pack.HabitatPoolWriter;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.HabitatPlanner;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.pokemon.GenOneIds;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.CellRole;
import dev.kantobridge.routes.gen.RouteLayout;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * One Wild Routes zone, planned (docs/specs/WILD_ROUTES_PLAN.md §4.4, the forest theme): its generated layout placed
 * in a slot, the land around it, and how every block column of its box is built. Pure data, like {@link WorldPlan}:
 * {@link #renderColumn} writes {@link BlockCode}s, so planning never needs Minecraft's registries.
 *
 * <ul>
 * <li><b>The maze</b> is flat: its walkable cells have their floor at {@link #FLOOR} (feet at Kanto's walking level,
 * so lines of sight and the spawn rule work as in Kanto). Path cells are dirt path, the rest grass; tall grass gets
 * short grass on top, flower cells a flower.</li>
 * <li><b>Walls</b> are hedges of leaves 3–5 blocks tall, now and then with a trunk in them; rocks are 2-block
 * boulders; ponds are water at the floor with a barrier above, so they stop you as Kanto's water does until Surf.</li>
 * <li><b>The land around it</b> follows the {@link TerrainSampler} (Minecraft's own noise in a real build), sampled
 * every {@link #GRID} blocks (vanilla's noise costs about a millisecond a column, check C2) and blended smoothly between,
 * with a little small-scale relief of our own, kept between {@code FLOOR - 4} and {@link #SCENERY_MAX}, with a ridge
 * rising along the box's edge so the void beyond never shows, and our own scattered trees.</li>
 * </ul>
 *
 * <p>Logs carry zone ids and counts only.
 */
public final class ZonePlan {
	public static final int FEET = Scale.SURFACE_Y;
	public static final int FLOOR = FEET - 1;
	/**
	 * The zone's ground reaches no deeper: 5 blocks under its lowest land. Shallow on purpose, since every block costs
	 * placing time once (check C2: about 4 million blocks a zone at FEET - 20).
	 */
	public static final int MIN_Y = FEET - 10;
	/** The top of the zone's volume (exclusive): the highest land plus a tree. */
	public static final int MAX_Y = FEET + 48;
	public static final int SCENERY_MAX = MAX_Y - 14;
	/** Blocks of land between the layout's box and the zone's box. */
	public static final int PAD = 16;
	/** How far in from the box's edge the ridge rises, and to what height. */
	public static final int RIDGE = 40;
	public static final int RIDGE_TOP = FEET + 30;
	/** The terrain is sampled every GRID blocks and blended between (check C2: about 1 ms per vanilla sample). */
	public static final int GRID = 16;
	/** Our own small-scale relief on top of the sampled land: its cell size and height in blocks. */
	public static final int RELIEF_CELL = 6;
	public static final double RELIEF = 2.0;
	/** Our scenery trees stand on a jittered grid of this spacing. */
	public static final int TREE_SPACING = 7;
	/** A vine hangs on about one leaf face in this many (the canopy's density; docs/specs/CAVES_AND_WOODS_PLAN.md §5.5). */
	public static final int VINE_EVERY = 9;
	/** Vines beside the maze keep this high: a player who can't jump never reaches one to climb out over a hedge. */
	public static final int VINE_LOW = FEET + 2;
	public static final String BIOME = "kantobridge:wild_forest";
	public static final String THEME = "forest";

	private record Tree(int x, int z, int trunk, ZoneBlock log, ZoneBlock leaves) {
	}

	private final String id;
	/** MG3: what the zone's pool ids start with after {@code kantobridge:} (its region's prefix; nothing for the legacy region). */
	private final String poolPrefix;
	private final RouteLayout layout;
	private final int x0;
	private final int z0;
	private final int width;
	private final int depth;
	private final int layoutX0;
	private final int layoutZ0;
	private final int gridW;
	private final int gridD;
	private final int[] grid;
	private final Map<Long, Tree> trees = new HashMap<>();
	private final MapPlacement routeMap;
	private final WorldIndex.Zone zone;
	private final ZoneObjects objects;
	/** WR6: the habitat blocks over each area's tall grass, and each such area's pool (canonical JSON by pool id). */
	private final List<WorldIndex.Habitat> habitats = new ArrayList<>();
	private final Map<String, String> pools = new TreeMap<>();
	/** Layout cells (as {@link #cellKey}) holding a trainer, and the ball index of each ball's cell. */
	private final Set<Long> trainerCells = new HashSet<>();
	private final Map<Long, Integer> ballCells = new HashMap<>();

	/**
	 * @param id       the zone's id
	 * @param routeMap the route map's id in the hidden game ({@code KB_ROUTE_<ID>})
	 * @param palette  the palette the route map's placement carries (its town's)
	 * @param layout   the generated layout
	 * @param slotX    the slot's north-west block X
	 * @param slotZ    the slot's north-west block Z
	 * @param terrain  the land's height
	 */
	public ZonePlan(String id, String routeMap, String palette, RouteLayout layout, int slotX, int slotZ, TerrainSampler terrain) {
		this(id, routeMap, palette, layout, slotX, slotZ, terrain, ZoneObjects.NONE);
	}

	/**
	 * A zone with its trainers and balls (WR5): each trainer stands on a barrier (a blocker shared by everyone), each ball
	 * on a story cell (closed for each player until they take it); the WorldIndex entry lists both in the route map's
	 * grid.
	 */
	public ZonePlan(String id, String routeMap, String palette, RouteLayout layout, int slotX, int slotZ, TerrainSampler terrain,
			ZoneObjects objects) {
		this(id, routeMap, palette, layout, slotX, slotZ, terrain, objects, "");
	}

	/**
	 * A zone of a region (MULTI_GAME_PLAN §4.6, MG3): its pool ids carry the region's prefix ({@code poolPrefix}, nothing
	 * for the legacy region), so two regions built from one game never share a zone's pools.
	 */
	public ZonePlan(String id, String routeMap, String palette, RouteLayout layout, int slotX, int slotZ, TerrainSampler terrain,
			ZoneObjects objects, String poolPrefix) {
		this.id = id;
		this.poolPrefix = java.util.Objects.requireNonNull(poolPrefix, "poolPrefix");
		this.objects = objects;
		this.layout = layout;
		this.width = Scale.cellToBlock(layout.width()) + 2 * PAD;
		this.depth = Scale.cellToBlock(layout.height()) + 2 * PAD;
		if (this.width > ZoneRegions.SLOT || this.depth > ZoneRegions.SLOT) {
			throw new IllegalArgumentException("zone " + id + " doesn't fit its slot");
		}
		// centred in the slot, on even blocks so cells keep their 2x2 grid
		this.x0 = slotX + ((ZoneRegions.SLOT - this.width) / 4) * 2;
		this.z0 = slotZ + ((ZoneRegions.SLOT - this.depth) / 4) * 2;
		this.layoutX0 = this.x0 + PAD;
		this.layoutZ0 = this.z0 + PAD;
		this.gridW = this.width / GRID + 2;
		this.gridD = this.depth / GRID + 2;
		this.grid = new int[this.gridW * this.gridD];
		// vanilla's noise is safe to ask from several threads (its own worker threads do), and it is the slow part
		java.util.stream.IntStream.range(0, this.grid.length).parallel().forEach(k -> this.grid[k] =
				terrain.height(this.x0 + (k % this.gridW) * GRID, this.z0 + (k / this.gridW) * GRID));
		this.plantTrees();
		Cell door = layout.trailhead().routeMapCells().get(0);
		this.routeMap = new MapPlacement(routeMap, this.layoutX0 + Scale.cellToBlock(door.x()), FEET,
				this.layoutZ0 + Scale.cellToBlock(door.y()), 2, 2, 0, MapPlacement.Kind.ROUTE, palette, BIOME, false, null);
		List<WorldIndex.ZoneTrainer> trainers = new ArrayList<>();
		for (ZoneObjects.Trainer t : objects.trainers()) {
			trainers.add(new WorldIndex.ZoneTrainer(t.id(), t.cell().x() - door.x(), t.cell().y() - door.y(), t.facing(), t.sight(), t.cls(),
					t.sprite(), t.party()));
			this.trainerCells.add(cellKey(t.cell().x(), t.cell().y()));
		}
		List<WorldIndex.ZoneBall> balls = new ArrayList<>();
		for (int i = 0; i < objects.balls().size(); i++) {
			ZoneObjects.Ball b = objects.balls().get(i);
			balls.add(new WorldIndex.ZoneBall(b.id(), b.cell().x() - door.x(), b.cell().y() - door.y(), b.item()));
			this.ballCells.put(cellKey(b.cell().x(), b.cell().y()), i);
		}
		this.planWild(palette);
		this.zone = new WorldIndex.Zone(id, routeMap, THEME, this.x0, FEET, this.z0, this.width, this.depth, BIOME, trainers, balls,
				objects.ballSprite(), this.dungeon(door));
	}

	/**
	 * WR7: the dungeon's entry, if the layout has one: its cell in the route map's grid, the way to its one open
	 * neighbour, and its area's pool and highest wild level (another area's pool when its own has no grass).
	 */
	private WorldIndex.@Nullable ZoneDungeon dungeon(Cell door) {
		Cell entrance = this.layout.dungeon();
		if (entrance == null) {
			return null;
		}
		Dir out = Dir.DOWN;
		for (dev.kantobridge.routes.gen.Dir d : dev.kantobridge.routes.gen.Dir.values()) {
			Cell next = entrance.step(d);
			if (this.layout.inside(next) && this.layout.at(next).walkable()) {
				out = ZoneObjects.dir(d);
			}
		}
		int area = this.layout.areaOf(entrance);
		String pool = area >= 0 && this.pools.containsKey(poolId(this.poolPrefix, this.id, area)) ? poolId(this.poolPrefix, this.id, area)
				: this.pools.isEmpty() ? null : this.pools.keySet().iterator().next();
		int top = 1;
		for (ZoneContent.Slot slot : this.objects.pool()) {
			top = Math.max(top, slot.level());
		}
		int level = Math.min(100, top + (area >= 0 ? this.layout.areas().get(area).levelOffset() : 0));
		return new WorldIndex.ZoneDungeon(entrance.x() - door.x(), entrance.y() - door.y(), out, pool, level, this.objects.dungeonItems());
	}

	/**
	 * WR6 (docs/specs/WILD_ROUTES_PLAN.md §4.7): each area with tall grass gets a habitat pool from the town's neighbours,
	 * its levels raised by the area's level offset, and habitat blocks 3 under the feet covering its grass the way
	 * Kanto's are planned ({@link HabitatPlanner#cover}).
	 */
	private void planWild(String palette) {
		if (this.objects.pool().isEmpty()) {
			return;
		}
		Map<Integer, List<int[]>> grass = new TreeMap<>();
		for (int y = 0; y < this.layout.height(); y++) {
			for (int x = 0; x < this.layout.width(); x++) {
				int area = this.layout.at(x, y) == CellRole.GRASS ? this.layout.areaOf(new Cell(x, y)) : -1;
				if (area >= 0) {
					grass.computeIfAbsent(area, a -> new ArrayList<>()).add(new int[] {x, y});
				}
			}
		}
		// the layout's grid as a map, so the cover runs as it does on Kanto's maps
		MapPlacement grid = new MapPlacement(this.routeMap.id(), this.layoutX0, FEET, this.layoutZ0, this.layout.width(), this.layout.height(), 0,
				MapPlacement.Kind.ROUTE, palette, BIOME, false, null);
		for (Map.Entry<Integer, List<int[]>> e : grass.entrySet()) {
			String pool = poolId(this.poolPrefix, this.id, e.getKey());
			this.pools.put(pool, ProtoJson.write(pool(this.routeMap.id(), e.getKey(), this.objects.pool(),
					this.layout.areas().get(e.getKey()).levelOffset())));
			for (int[] block : HabitatPlanner.cover(grid, e.getValue())) {
				this.habitats.add(new WorldIndex.Habitat(this.routeMap.id(), block[0], FEET - HabitatPlanner.BELOW_FEET, block[1], pool));
			}
		}
	}

	/** An area's habitat pool id in the legacy region: {@code kantobridge:zone_<zone>_a<area>}. */
	public static String poolId(String zone, int area) {
		return poolId("", zone, area);
	}

	/** An area's habitat pool id in a region (MG3): {@code kantobridge:<prefix>zone_<zone>_a<area>}. */
	public static String poolId(String prefix, String zone, int area) {
		return HabitatPlanner.POOL_NAMESPACE + ":" + prefix + "zone_" + zone + "_a" + (area + 1);
	}

	/** An area's pool, in the format of Kanto's ({@link HabitatPoolWriter#pool}), levels raised by {@code levelOffset}. */
	static JsonObject pool(String routeMap, int area, List<ZoneContent.Slot> slots, int levelOffset) {
		JsonArray spawns = new JsonArray();
		for (ZoneContent.Slot slot : slots) {
			JsonObject spawn = new JsonObject();
			spawn.addProperty("species", GenOneIds.NAMESPACE + ":" + GenOneIds.speciesId(slot.dex()));
			spawn.addProperty("spawnablePositionType", HabitatPoolWriter.POSITION);
			spawn.addProperty("bucket", HabitatPoolWriter.BUCKET);
			spawn.addProperty("weight", slot.weight());
			spawn.addProperty("levelRange", Integer.toString(Math.max(1, Math.min(100, slot.level() + levelOffset))));
			spawns.add(spawn);
		}
		JsonObject pool = new JsonObject();
		// the habitat editor shows this name; a map symbol and an area number, not game text
		pool.addProperty("name", "Kanto Bridge " + routeMap + " a" + (area + 1));
		pool.add("spawns", spawns);
		return pool;
	}

	/** The habitat blocks over the zone's tall grass (WR6), sorted by Z, then X within each area. */
	public List<WorldIndex.Habitat> habitats() {
		return List.copyOf(this.habitats);
	}

	/** The zone's habitat pools (WR6): canonical JSON by pool id. */
	public Map<String, String> pools() {
		return java.util.Collections.unmodifiableMap(this.pools);
	}

	private static long cellKey(int cx, int cy) {
		return ((long) cx << 32) ^ (cy & 0xFFFFFFFFL);
	}

	/** The zone's trainers and balls, at layout cells. */
	public ZoneObjects objects() {
		return this.objects;
	}

	/**
	 * The story cells of the zone's balls (type {@code route}), keyed in the route map's grid, in ball order. The world's
	 * plan adds them to its table ({@link WorldPlan#withZones}).
	 */
	public List<StoryCellTable.Entry> storyEntries() {
		List<StoryCellTable.Entry> out = new ArrayList<>();
		for (WorldIndex.ZoneBall b : this.zone.balls()) {
			out.add(new StoryCellTable.Entry(new CellPos(this.routeMap.id(), b.x(), b.y()), StoryCellType.ROUTE, b.id(), null));
		}
		return out;
	}

	public String id() {
		return this.id;
	}

	public RouteLayout layout() {
		return this.layout;
	}

	/** The route map's placement at the trailhead: its cell (0, 0) is A, (1, 0) is x1. */
	public MapPlacement routeMap() {
		return this.routeMap;
	}

	/** The zone's WorldIndex entry. */
	public WorldIndex.Zone zone() {
		return this.zone;
	}

	/** The blocks the zone owns: everything inside is built or cleared to air. */
	public Box3 volume() {
		return new Box3(this.x0, MIN_Y, this.z0, this.x0 + this.width, MAX_Y, this.z0 + this.depth);
	}

	public WorldPlan.BiomeRegion biomeRegion() {
		return new WorldPlan.BiomeRegion(this.volume(), BIOME);
	}

	/** Our scenery trees (a count for the build report). */
	public int trees() {
		return this.trees.size();
	}

	/** The block of layout cell (cx, cy)'s north-west corner. */
	public int[] blockOf(Cell cell) {
		return new int[] {this.layoutX0 + Scale.cellToBlock(cell.x()), this.layoutZ0 + Scale.cellToBlock(cell.y())};
	}

	/** The layout role under block column (x, z): scenery outside the layout's box. */
	public CellRole roleAt(int x, int z) {
		int cx = Math.floorDiv(x - this.layoutX0, Scale.CELL_BLOCKS);
		int cy = Math.floorDiv(z - this.layoutZ0, Scale.CELL_BLOCKS);
		if (cx < 0 || cy < 0 || cx >= this.layout.width() || cy >= this.layout.height()) {
			return CellRole.SCENERY;
		}
		return this.layout.at(cx, cy);
	}

	// ---- rendering ----

	/** Writes block column (x, z) of the zone's box (outside the box, nothing), without its balls' story cells. */
	public void renderColumn(WorldPlan.CodeSink sink, int x, int z) {
		this.renderColumn(sink, x, z, null);
	}

	/**
	 * Writes block column (x, z) of the zone's box (outside the box, nothing). {@code ballStory} gives each ball's index in
	 * the world's story-cell table (by ball order), or is null for none.
	 */
	public void renderColumn(WorldPlan.CodeSink sink, int x, int z, int @Nullable [] ballStory) {
		if (x < this.x0 || z < this.z0 || x >= this.x0 + this.width || z >= this.z0 + this.depth) {
			return;
		}
		this.renderRole(sink, x, z);
		long key = cellKey(Math.floorDiv(x - this.layoutX0, Scale.CELL_BLOCKS), Math.floorDiv(z - this.layoutZ0, Scale.CELL_BLOCKS));
		if (this.trainerCells.contains(key)) {
			sink.set(x, FEET, z, BlockCode.vanilla(ZoneBlock.BARRIER));
		}
		Integer ball = this.ballCells.get(key);
		if (ball != null && ballStory != null && ballStory[ball] >= 0) {
			sink.set(x, FEET, z, BlockCode.story(ballStory[ball]));
		}
	}

	private void renderRole(WorldPlan.CodeSink sink, int x, int z) {
		CellRole role = this.roleAt(x, z);
		switch (role) {
			case SCENERY -> this.scenery(sink, x, z);
			case WALL -> this.hedge(sink, x, z);
			case ROCK -> {
				this.ground(sink, x, z, FLOOR, ZoneBlock.GRASS_BLOCK);
				ZoneBlock stone = hash(x >> 1, z >> 1, 7) % 3 == 0 ? ZoneBlock.COBBLESTONE : ZoneBlock.MOSSY_COBBLESTONE;
				sink.set(x, FEET, z, BlockCode.vanilla(stone));
				sink.set(x, FEET + 1, z, BlockCode.vanilla(stone));
			}
			case WATER -> {
				this.ground(sink, x, z, FLOOR - 1, ZoneBlock.DIRT);
				sink.set(x, FLOOR, z, BlockCode.vanilla(ZoneBlock.WATER));
				sink.set(x, FEET, z, BlockCode.vanilla(ZoneBlock.BARRIER));
			}
			case DUNGEON -> {
				// WR7: the dungeon's entrance, a prop drawn a quarter a block
				this.ground(sink, x, z, FLOOR, ZoneBlock.COARSE_DIRT);
				int part = ((z - this.layoutZ0) & 1) * 2 + ((x - this.layoutX0) & 1);
				sink.set(x, FEET, z, BlockCode.prop(PropBlock.Kind.DUNGEON_ENTRANCE, part));
			}
			case PATH -> this.ground(sink, x, z, FLOOR, ZoneBlock.DIRT_PATH);
			case GRASS -> {
				this.ground(sink, x, z, FLOOR, ZoneBlock.GRASS_BLOCK);
				sink.set(x, FEET, z, BlockCode.vanilla(hash(x, z, 3) % 5 == 0 ? ZoneBlock.FERN : ZoneBlock.SHORT_GRASS));
			}
			case FLOWER -> {
				this.ground(sink, x, z, FLOOR, ZoneBlock.GRASS_BLOCK);
				int h = hash(x, z, 5);
				if (h % 3 == 0) {
					ZoneBlock[] flowers = {ZoneBlock.DANDELION, ZoneBlock.POPPY, ZoneBlock.AZURE_BLUET, ZoneBlock.OXEYE_DAISY};
					sink.set(x, FEET, z, BlockCode.vanilla(flowers[(h >>> 4) % flowers.length]));
				}
			}
			default -> this.ground(sink, x, z, FLOOR, ZoneBlock.GRASS_BLOCK);
		}
		if (role.walkable()) {
			this.hedgeVines(sink, x, z);
		}
	}

	/** Column offsets north, east, south, west. */
	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DZ = {-1, 0, 1, 0};

	/**
	 * Vines on a hedge's side over a walkable column (CW4): on the first hedge beside it (north, east, south, west) whose
	 * face the hash picks, from the hedge's top down two to four blocks, never below {@link #VINE_LOW}.
	 */
	private void hedgeVines(WorldPlan.CodeSink sink, int x, int z) {
		for (int d = 0; d < 4; d++) {
			int nx = x + DX[d];
			int nz = z + DZ[d];
			if (!this.inBox(nx, nz) || this.roleAt(nx, nz) != CellRole.WALL || hash(x, z, 37 + d) % VINE_EVERY != 0) {
				continue;
			}
			int top = FEET + hedgeHeight(nx, nz) - 1;
			int bottom = Math.max(VINE_LOW, top - 1 - hash(x, z, 41 + d) % 3);
			for (int y = bottom; y <= top; y++) {
				sink.set(x, y, z, BlockCode.vanilla(ZoneBlock.vineOn(d)));
			}
			return;
		}
	}

	private boolean inBox(int x, int z) {
		return x >= this.x0 && z >= this.z0 && x < this.x0 + this.width && z < this.z0 + this.depth;
	}

	/** A hedge's height at (x, z): 3..5 blocks, one per cell. */
	private static int hedgeHeight(int x, int z) {
		return 3 + hash(x >> 1, z >> 1, 11) % 3;
	}

	/** Stone and dirt from the bottom of the zone up to {@code top}, which gets {@code surface}. */
	private void ground(WorldPlan.CodeSink sink, int x, int z, int top, ZoneBlock surface) {
		for (int y = MIN_Y; y < top; y++) {
			sink.set(x, y, z, BlockCode.vanilla(y >= top - 3 ? ZoneBlock.DIRT : ZoneBlock.STONE));
		}
		sink.set(x, top, z, BlockCode.vanilla(surface));
	}

	private void hedge(WorldPlan.CodeSink sink, int x, int z) {
		this.ground(sink, x, z, FLOOR, ZoneBlock.GRASS_BLOCK);
		int cx = x >> 1;
		int cz = z >> 1;
		int h = hash(cx, cz, 11);
		int height = hedgeHeight(x, z);
		ZoneBlock[] kind = leafKind(h >>> 8);
		boolean trunk = (h >>> 4) % 5 == 0 && (x & 1) == 0 && (z & 1) == 0;
		for (int y = FEET; y < FEET + height; y++) {
			sink.set(x, y, z, BlockCode.vanilla(trunk && y < FEET + height - 1 ? kind[0] : kind[1]));
		}
	}

	/** {log, leaves}: mostly oak, some birch, a little dark oak. */
	private static ZoneBlock[] leafKind(int h) {
		int v = h % 10;
		if (v < 7) {
			return new ZoneBlock[] {ZoneBlock.OAK_LOG, ZoneBlock.OAK_LEAVES};
		}
		if (v < 9) {
			return new ZoneBlock[] {ZoneBlock.BIRCH_LOG, ZoneBlock.BIRCH_LEAVES};
		}
		return new ZoneBlock[] {ZoneBlock.DARK_OAK_LOG, ZoneBlock.DARK_OAK_LEAVES};
	}

	private void scenery(WorldPlan.CodeSink sink, int x, int z) {
		int top = this.sceneryHeight(x, z);
		this.ground(sink, x, z, top, top >= SCENERY_MAX - 6 ? ZoneBlock.STONE : ZoneBlock.GRASS_BLOCK);
		// trees: the trunk's own column and the leaves of any tree within 2 blocks
		int gx = Math.floorDiv(x - this.x0, TREE_SPACING);
		int gz = Math.floorDiv(z - this.z0, TREE_SPACING);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Tree tree = this.trees.get(key(gx + dx, gz + dz));
				if (tree != null) {
					this.treeColumn(sink, tree, x, z);
				}
			}
		}
		if (top < SCENERY_MAX - 6 && hash(x, z, 13) % 9 == 0 && sinkFree(x, z)) {
			sink.set(x, top + 1, z, BlockCode.vanilla(hash(x, z, 17) % 4 == 0 ? ZoneBlock.FERN : ZoneBlock.SHORT_GRASS));
		}
		this.treeVines(sink, x, z, top);
	}

	/**
	 * Vines on a scenery tree's crown (CW4): a column just outside one of its four wide faces (three out along an axis,
	 * at most one across), when the hash picks it, hangs a vine from the upper wide layer down two to four blocks, kept two
	 * blocks above the ground; none where another tree is in the way.
	 */
	private void treeVines(WorldPlan.CodeSink sink, int x, int z, int ground) {
		int gx = Math.floorDiv(x - this.x0, TREE_SPACING);
		int gz = Math.floorDiv(z - this.z0, TREE_SPACING);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Tree tree = this.trees.get(key(gx + dx, gz + dz));
				if (tree == null) {
					continue;
				}
				int ox = x - tree.x;
				int oz = z - tree.z;
				int d = ox == 3 && Math.abs(oz) <= 1 ? 3 : ox == -3 && Math.abs(oz) <= 1 ? 1 : oz == 3 && Math.abs(ox) <= 1 ? 0
						: oz == -3 && Math.abs(ox) <= 1 ? 2 : -1;
				if (d < 0 || hash(x, z, 43) % VINE_EVERY != 0) {
					continue;
				}
				int top = this.sceneryHeight(tree.x, tree.z) + 1 + tree.trunk - 1;
				int bottom = Math.max(ground + 2, top - 1 - hash(x, z, 47) % 3);
				for (int y = bottom; y <= top; y++) {
					if (this.treeBlockAt(x, y, z)) {
						// Another tree's crown or trunk is in the way: no vine here.
						return;
					}
				}
				for (int y = bottom; y <= top; y++) {
					sink.set(x, y, z, BlockCode.vanilla(ZoneBlock.vineOn(d)));
				}
				return;
			}
		}
	}

	/** True when a scenery tree's trunk or leaves take (x, y, z). */
	private boolean treeBlockAt(int x, int y, int z) {
		int gx = Math.floorDiv(x - this.x0, TREE_SPACING);
		int gz = Math.floorDiv(z - this.z0, TREE_SPACING);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Tree tree = this.trees.get(key(gx + dx, gz + dz));
				if (tree == null) {
					continue;
				}
				int ax = Math.abs(x - tree.x);
				int az = Math.abs(z - tree.z);
				int base = this.sceneryHeight(tree.x, tree.z) + 1;
				int crown = base + tree.trunk;
				if (ax == 0 && az == 0 && y >= base && y < crown) {
					return true;
				}
				if (y >= crown - 2 && y < crown + 2) {
					int r = y < crown ? 2 : 1;
					if (ax <= r && az <= r && !(ax == r && az == r && (y < crown || y == crown + 1))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** No tree trunk or leaves at this column's first block above the ground (so grass never sits in a trunk). */
	private boolean sinkFree(int x, int z) {
		int gx = Math.floorDiv(x - this.x0, TREE_SPACING);
		int gz = Math.floorDiv(z - this.z0, TREE_SPACING);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				Tree tree = this.trees.get(key(gx + dx, gz + dz));
				if (tree != null && Math.abs(tree.x - x) <= 2 && Math.abs(tree.z - z) <= 2) {
					return false;
				}
			}
		}
		return true;
	}

	private void treeColumn(WorldPlan.CodeSink sink, Tree tree, int x, int z) {
		int ax = Math.abs(x - tree.x);
		int az = Math.abs(z - tree.z);
		if (ax > 2 || az > 2) {
			return;
		}
		int base = this.sceneryHeight(tree.x, tree.z) + 1;
		int crown = base + tree.trunk;
		if (ax == 0 && az == 0) {
			for (int y = base; y < crown; y++) {
				sink.set(x, y, z, BlockCode.vanilla(tree.log));
			}
		}
		// two wide layers just below the crown, two narrow ones on top (corners left out)
		for (int y = crown - 2; y < crown + 2; y++) {
			boolean wide = y < crown;
			int r = wide ? 2 : 1;
			if (ax > r || az > r || (ax == r && az == r && (wide || y == crown + 1))) {
				continue;
			}
			if (ax == 0 && az == 0 && y < crown) {
				continue; // the trunk
			}
			sink.set(x, y, z, BlockCode.vanilla(tree.leaves));
		}
	}

	/** The land's height at column (x, z): the sampled terrain, clamped, with the ridge along the box's edge. */
	public int sceneryHeight(int x, int z) {
		double fx = (x - this.x0) / (double) GRID;
		double fz = (z - this.z0) / (double) GRID;
		int i = Math.max(0, Math.min(this.gridW - 2, (int) Math.floor(fx)));
		int j = Math.max(0, Math.min(this.gridD - 2, (int) Math.floor(fz)));
		double tx = smooth(Math.max(0, Math.min(1, fx - i)));
		double tz = smooth(Math.max(0, Math.min(1, fz - j)));
		double h00 = this.grid[j * this.gridW + i];
		double h10 = this.grid[j * this.gridW + i + 1];
		double h01 = this.grid[(j + 1) * this.gridW + i];
		double h11 = this.grid[(j + 1) * this.gridW + i + 1];
		double h = (h00 * (1 - tx) + h10 * tx) * (1 - tz) + (h01 * (1 - tx) + h11 * tx) * tz + relief(x, z);
		int edge = Math.min(Math.min(x - this.x0, this.x0 + this.width - 1 - x), Math.min(z - this.z0, this.z0 + this.depth - 1 - z));
		if (edge < RIDGE) {
			double f = 1.0 - edge / (double) RIDGE;
			f = f * f * (3 - 2 * f);
			h = Math.max(h, h + (RIDGE_TOP - h) * f);
		}
		return (int) Math.round(Math.max(FLOOR - 4, Math.min(SCENERY_MAX, h)));
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	/** Value noise of our own, RELIEF blocks high on a RELIEF_CELL grid: bumps the sampled land's smooth slopes. */
	private static double relief(int x, int z) {
		int cx = Math.floorDiv(x, RELIEF_CELL);
		int cz = Math.floorDiv(z, RELIEF_CELL);
		double tx = smooth((x - cx * RELIEF_CELL) / (double) RELIEF_CELL);
		double tz = smooth((z - cz * RELIEF_CELL) / (double) RELIEF_CELL);
		double a = corner(cx, cz);
		double b = corner(cx + 1, cz);
		double c = corner(cx, cz + 1);
		double d = corner(cx + 1, cz + 1);
		return RELIEF * ((a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz);
	}

	private static double corner(int cx, int cz) {
		return (hash(cx, cz, 23) % 2001) / 1000.0 - 1.0;
	}

	private void plantTrees() {
		int nx = this.width / TREE_SPACING + 1;
		int nz = this.depth / TREE_SPACING + 1;
		for (int gz = 0; gz < nz; gz++) {
			for (int gx = 0; gx < nx; gx++) {
				int h = hash(gx, gz, 19);
				if (h % 3 == 0) {
					continue; // a clearing
				}
				int x = this.x0 + gx * TREE_SPACING + 1 + (h >>> 4) % (TREE_SPACING - 2);
				int z = this.z0 + gz * TREE_SPACING + 1 + (h >>> 8) % (TREE_SPACING - 2);
				if (!this.allScenery(x, z, 3) || this.sceneryHeight(x, z) >= SCENERY_MAX - 6) {
					continue;
				}
				ZoneBlock[] kind = leafKind(h >>> 12);
				this.trees.put(key(gx, gz), new Tree(x, z, 4 + (h >>> 16) % 3, kind[0], kind[1]));
			}
		}
	}

	/** Every column within {@code r} of (x, z) is scenery inside the box. */
	private boolean allScenery(int x, int z, int r) {
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				int bx = x + dx;
				int bz = z + dz;
				if (bx < this.x0 || bz < this.z0 || bx >= this.x0 + this.width || bz >= this.z0 + this.depth
						|| this.roleAt(bx, bz) != CellRole.SCENERY) {
					return false;
				}
			}
		}
		return true;
	}

	private static long key(int gx, int gz) {
		return ((long) gz << 32) ^ (gx & 0xFFFFFFFFL);
	}

	/** A small integer hash of a column or cell and a salt: the same zone always looks the same. */
	static int hash(int a, int b, int salt) {
		long h = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return (int) (h & 0x7FFFFFFF);
	}
}
