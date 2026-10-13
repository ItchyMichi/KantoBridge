package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.dressing.Apron;
import dev.kantobridge.builder.shape.dressing.Canopy;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.builder.zone.ZonePlan;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the builder decided, before any block is placed: map placements and cell models, the surface void fill,
 * the story cells, the slot map, the spawn and the WorldIndex. Pure data; {@link #renderChunk} turns it into
 * {@link BlockCode}s one chunk at a time, so a whole-Kanto build never holds every block in memory.
 */
public final class WorldPlan {
	/** Void fill around the surface maps: 3 Gen 1 blocks, the border ring Gen1Recomp draws. */
	public static final int SURFACE_RING = 12;
	/**
	 * The surface volume: ground blocks at Y 63 up to ground + 32 (the profile plan's P2: a band-table building stands as
	 * tall as its facade is drawn, 21 blocks for the tallest; it was ground + 8 for the voxel plan's six measured
	 * rows, a roof's rise and a tree's trunk). The placer only writes where the world differs, so the air above costs
	 * reads, and a rebuild clears whatever an older build left up there.
	 */
	public static final int SURFACE_MIN_Y = Scale.GROUND_Y;
	public static final int SURFACE_MAX_Y = Scale.GROUND_Y + 32;
	/** Interiors: the ceiling is 4 blocks above the floor block, i.e. at feet + 3. */
	public static final int CEILING_ABOVE_FEET = 3;
	/** Interior lights: {@code minecraft:light} level 15 at feet + 2 on a 4-block grid. */
	public static final int LIGHT_LEVEL = 15;
	public static final int LIGHT_ABOVE_FEET = 2;
	public static final int LIGHT_SPACING = 4;
	/**
	 * How far past its map an interior under the open sky reaches, in blocks: a canopy wood's apron and its moss wall (the
	 * caves and woods plan's CW3). Inside the packer's gap, so nothing else stands there; air around a sky interior without
	 * an apron.
	 */
	public static final int SKY_MARGIN = Apron.CELLS * Scale.CELL_BLOCKS + 1;

	public static final String BIOME_OUTDOOR = "kantobridge:kanto_outdoor";
	public static final String BIOME_INTERIOR = "kantobridge:kanto_interior";
	public static final String BIOME_CAVE = "kantobridge:kanto_cave";
	/**
	 * The cave area's caves (docs/specs/CAVES_3D_PLAN.md §3.3): the cave biome, with wild Pokémon at any height (the
	 * spawn rule's walking-level test leaves it out) and the air between rock and ceiling made solid.
	 */
	public static final String BIOME_CAVE_3D = "kantobridge:cave_3d";

	/** One biome over a box of blocks. */
	public record BiomeRegion(Box3 box, String biome) {
	}

	/** The world spawn: a walkable cell, its north-west block and its feet level. */
	public record Spawn(String map, int x, int y, int blockX, int feetY, int blockZ) {
	}

	/** Receives planned codes; {@link ChunkCodes} is one. */
	@FunctionalInterface
	public interface CodeSink {
		void set(int x, int y, int z, int code);
	}

	/** Slot indices of one (atlas, palette) pair by tile id, -1 where the slot map has none. */
	private static final class TileSlots {
		final int[] full = filled(1024);
		final int[] half = filled(1024);

		private static int[] filled(int n) {
			int[] a = new int[n];
			java.util.Arrays.fill(a, -1);
			return a;
		}
	}

	private final String game;
	private final String exportHash;
	private final int originX;
	private final int originZ;
	private final List<PlacedMap> maps;
	private final @Nullable Box3 surfaceBox;
	private final int[] surfaceOwner;
	private final boolean[] surfaceVoid;
	private final StoryCellTable storyCells;
	private final Map<String, int[]> storyIndex;
	private final ArtCatalog art;
	private final SlotMap slotMap;
	private final Map<String, TileSlots> tileSlots = new java.util.HashMap<>();
	private final @Nullable Spawn spawn;
	private final WorldIndex index;
	private final Map<String, String> pools;
	private final Warnings warnings;
	private final int mapsRequested;
	private final int mapsUnplaced;
	private final int edgeMismatches;
	private final int overlaps;
	private final @Nullable Scene scene;
	private final @Nullable ShapePlan shapes;
	/** Wild Routes zones (docs/specs/WILD_ROUTES_PLAN.md §4.4); empty unless {@link #withZones} added some. */
	private final List<ZonePlan> zones;
	/** Each zone's balls' indices in {@link #storyCells}, by zone, then ball order. */
	private final List<int[]> zoneBallStory;
	/** The cave area's box ({@code minX, minZ, maxX, maxZ}, max exclusive; CAVES_3D_PLAN §3.3), or null without caves. */
	private final int @Nullable [] caveArea;
	/** The region this plan builds (MULTI_GAME_PLAN §4.6, MG3), or null for a legacy build. */
	private final @Nullable RegionBuild region;

	WorldPlan(String game, String exportHash, int originX, int originZ, List<PlacedMap> maps, @Nullable Box3 surfaceBox, int[] surfaceOwner,
			boolean[] surfaceVoid, StoryCellTable storyCells, Map<String, int[]> storyIndex, ArtCatalog art, @Nullable Spawn spawn,
			WorldIndex index, Map<String, String> pools, Warnings warnings, int mapsRequested, int mapsUnplaced, int edgeMismatches, int overlaps,
			@Nullable Scene scene, @Nullable ShapePlan shapes) {
		this(game, exportHash, originX, originZ, maps, surfaceBox, surfaceOwner, surfaceVoid, storyCells, storyIndex, art, spawn, index, pools,
				warnings, mapsRequested, mapsUnplaced, edgeMismatches, overlaps, scene, shapes, null, null);
	}

	WorldPlan(String game, String exportHash, int originX, int originZ, List<PlacedMap> maps, @Nullable Box3 surfaceBox, int[] surfaceOwner,
			boolean[] surfaceVoid, StoryCellTable storyCells, Map<String, int[]> storyIndex, ArtCatalog art, @Nullable Spawn spawn,
			WorldIndex index, Map<String, String> pools, Warnings warnings, int mapsRequested, int mapsUnplaced, int edgeMismatches, int overlaps,
			@Nullable Scene scene, @Nullable ShapePlan shapes, int @Nullable [] caveArea, @Nullable RegionBuild region) {
		this.game = game;
		this.exportHash = exportHash;
		this.originX = originX;
		this.originZ = originZ;
		this.maps = List.copyOf(maps);
		this.surfaceBox = surfaceBox;
		this.surfaceOwner = surfaceOwner;
		this.surfaceVoid = surfaceVoid;
		this.storyCells = storyCells;
		this.storyIndex = storyIndex;
		this.art = art;
		this.slotMap = art.slotMap();
		this.spawn = spawn;
		this.index = index;
		this.pools = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(pools));
		this.warnings = warnings;
		this.mapsRequested = mapsRequested;
		this.mapsUnplaced = mapsUnplaced;
		this.edgeMismatches = edgeMismatches;
		this.overlaps = overlaps;
		this.scene = scene;
		this.shapes = shapes;
		this.zones = List.of();
		this.zoneBallStory = List.of();
		this.caveArea = caveArea == null ? null : caveArea.clone();
		this.region = region;
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : this.slotMap.slots().entrySet()) {
			SlotMap.Key key = entry.getKey();
			TileSlots slots = this.tileSlots.computeIfAbsent(key.atlas() + "|" + key.palette(), k -> new TileSlots());
			(key.shape() == SlotMap.Shape.FULL ? slots.full : slots.half)[key.tile()] = entry.getValue().index();
		}
	}

	/** A copy of {@code base} with zones, the story cells their balls joined, and the index that lists them. */
	private WorldPlan(WorldPlan base, List<ZonePlan> zones, WorldIndex index, StoryCellTable storyCells, Map<String, int[]> storyIndex,
			List<int[]> zoneBallStory, Map<String, String> pools) {
		this.game = base.game;
		this.exportHash = base.exportHash;
		this.originX = base.originX;
		this.originZ = base.originZ;
		this.maps = base.maps;
		this.surfaceBox = base.surfaceBox;
		this.surfaceOwner = base.surfaceOwner;
		this.surfaceVoid = base.surfaceVoid;
		this.storyCells = storyCells;
		this.storyIndex = storyIndex;
		this.art = base.art;
		this.slotMap = base.slotMap;
		this.tileSlots.putAll(base.tileSlots);
		this.spawn = base.spawn;
		this.index = index;
		this.pools = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(pools));
		this.warnings = base.warnings;
		this.mapsRequested = base.mapsRequested;
		this.mapsUnplaced = base.mapsUnplaced;
		this.edgeMismatches = base.edgeMismatches;
		this.overlaps = base.overlaps;
		this.scene = base.scene;
		// the shape map names the index it goes with (WorldIndexSync and every client check it): the new one, which lists the
		// zones, or nobody would get the shapes
		this.shapes = base.shapes == null ? null : base.shapes.withIndexHash(index.hash());
		this.zones = List.copyOf(zones);
		this.zoneBallStory = List.copyOf(zoneBallStory);
		this.caveArea = base.caveArea;
		this.region = base.region;
	}

	/**
	 * This plan with Wild Routes zones (docs/specs/WILD_ROUTES_PLAN.md §4.4): their volumes, blocks and biomes, and the
	 * WorldIndex's route-map placements and zone entries. No zones, the same plan.
	 */
	public WorldPlan withZones(List<ZonePlan> zones) {
		if (zones.isEmpty()) {
			return this;
		}
		List<MapPlacement> placements = new ArrayList<>(this.index.maps().values());
		List<WorldIndex.Zone> entries = new ArrayList<>();
		// WR5: the zones' balls join the story-cell table, which stays sorted, so the maps' cells are pointed at their
		// entries' new places
		List<StoryCellTable.Entry> cells = new ArrayList<>(this.storyCells.entries());
		for (ZonePlan zone : zones) {
			placements.add(zone.routeMap());
			entries.add(zone.zone());
			cells.addAll(zone.storyEntries());
		}
		StoryCellTable table = StoryCellTable.of(cells);
		Map<String, Integer> place = new HashMap<>();
		for (int i = 0; i < table.size(); i++) {
			place.put(table.entries().get(i).key(), i);
		}
		Map<String, int[]> storyIndex = new HashMap<>();
		this.storyIndex.forEach((map, old) -> {
			int[] moved = new int[old.length];
			for (int i = 0; i < old.length; i++) {
				moved[i] = old[i] < 0 ? old[i] : place.get(this.storyCells.entries().get(old[i]).key());
			}
			storyIndex.put(map, moved);
		});
		List<int[]> ballStory = new ArrayList<>();
		for (ZonePlan zone : zones) {
			List<StoryCellTable.Entry> balls = zone.storyEntries();
			int[] at = new int[balls.size()];
			for (int i = 0; i < at.length; i++) {
				at[i] = place.get(balls.get(i).key());
			}
			ballStory.add(at);
		}
		// WR6: the zones' habitat blocks and pools join Kanto's
		List<WorldIndex.Habitat> habitats = new ArrayList<>(this.index.habitats());
		Map<String, String> pools = new java.util.TreeMap<>(this.pools);
		for (ZonePlan zone : zones) {
			habitats.addAll(zone.habitats());
			pools.putAll(zone.pools());
		}
		habitats.sort(java.util.Comparator.comparing(WorldIndex.Habitat::map).thenComparingInt(WorldIndex.Habitat::z)
				.thenComparingInt(WorldIndex.Habitat::x));
		// WR7: a world with a dungeon gets the dungeon region, north of Kanto
		boolean dungeons = entries.stream().anyMatch(z -> z.dungeon() != null);
		WorldIndex withZones = new WorldIndex(this.game, this.exportHash, placements, this.index.layouts(), this.index.slotMapHash(), table,
				habitats, entries, dungeons ? dev.kantobridge.dungeon.DungeonSlots.region(this.surfaceBox, this.originX, this.originZ) : null,
				this.index.shafts());
		return new WorldPlan(this, zones, withZones, table, storyIndex, ballStory, pools);
	}

	/** The plan's Wild Routes zones (empty for a build without any). */
	public List<ZonePlan> zones() {
		return this.zones;
	}

	/** The region this plan builds (MULTI_GAME_PLAN §4.6, MG3), or null for a legacy build. */
	public @Nullable RegionBuild region() {
		return this.region;
	}

	/** What this build's habitat pool ids start with after {@code kantobridge:}: the region's prefix, nothing for a legacy build. */
	public String poolPrefix() {
		return this.region == null ? "" : this.region.poolPrefix();
	}

	/**
	 * The world-level slot map (MG3): every region's entries for a region build; the same map as {@link #slotMap()} for a
	 * legacy build. What {@code slot_map.json} holds.
	 */
	public SlotMap worldSlotMap() {
		return this.art.worldSlotMap();
	}

	// ---- the region's bounds and areas (MULTI_GAME_PLAN §4.6, MG3) ----

	/** The dungeon area the index reserves (the dungeon slots north of the surface), or none when no zone has a dungeon. */
	public RegionRecord.Area dungeonArea() {
		WorldIndex.Dungeons dungeons = this.index.dungeons();
		return dungeons == null ? RegionRecord.Area.NONE : new RegionRecord.Area(dungeons.x(), dungeons.z(), dungeons.w(), dungeons.d());
	}

	/** The box of the zones built (their slots' land included), or none without zones. */
	public RegionRecord.Area zoneArea() {
		RegionRecord.Area out = RegionRecord.Area.NONE;
		for (ZonePlan zone : this.zones) {
			Box3 v = zone.volume();
			out = out.union(new RegionRecord.Area(v.minX(), v.minZ(), v.maxX() - v.minX(), v.maxZ() - v.minZ()));
		}
		return out;
	}

	/** The cave area (CAVES_3D_PLAN §3.3), or none when the build placed no caves. */
	public RegionRecord.Area caveArea() {
		int[] a = this.caveArea;
		if (a == null || a[2] <= a[0] || a[3] <= a[1]) {
			return RegionRecord.Area.NONE;
		}
		return new RegionRecord.Area(a[0], a[1], a[2] - a[0], a[3] - a[1]);
	}

	/** The surface box on the ground plane (the surface maps and their void ring), or none without surface maps. */
	public RegionRecord.Area surfaceArea() {
		Box3 s = this.surfaceBox;
		return s == null ? RegionRecord.Area.NONE : new RegionRecord.Area(s.minX(), s.minZ(), s.maxX() - s.minX(), s.maxZ() - s.minZ());
	}

	/** The region record's areas: where this build's parts are. */
	public RegionRecord.Areas regionAreas() {
		return new RegionRecord.Areas(this.surfaceArea(), this.zoneArea(), this.dungeonArea(), this.caveArea());
	}

	/**
	 * The region record's bounds: the smallest box (inclusive) holding every volume this build owns (the surface and its
	 * ring, the interiors with their spill, the shafts, the zones), the dungeon area when the index reserves one (a
	 * generous height: its floors stack {@link dev.kantobridge.dungeon.DungeonSlots#FLOOR_STEP} apart) and the cave area.
	 * Null for a plan with nothing placed.
	 */
	public RegionRecord.@Nullable Box regionBounds() {
		RegionRecord.Box out = null;
		for (Box3 v : this.volumes()) {
			out = union(out, new RegionRecord.Box(v.minX(), v.minZ(), v.maxX() - 1, v.maxZ() - 1, v.minY(), v.maxY() - 1));
		}
		WorldIndex.Dungeons dungeons = this.index.dungeons();
		if (dungeons != null) {
			out = union(out, new RegionRecord.Box(dungeons.x(), dungeons.z(), dungeons.x() + dungeons.w() - 1, dungeons.z() + dungeons.d() - 1,
					dungeons.y() - 2, dungeons.y() + dev.kantobridge.dungeon.DungeonSlots.FLOOR_STEP * 10));
		}
		int[] caves = this.caveArea;
		if (caves != null && caves[2] > caves[0] && caves[3] > caves[1]) {
			int minY = out == null ? Scale.GROUND_Y : out.minY();
			int maxY = out == null ? Scale.SURFACE_Y + CEILING_ABOVE_FEET : out.maxY();
			out = union(out, new RegionRecord.Box(caves[0], caves[1], caves[2] - 1, caves[3] - 1, minY, maxY));
		}
		return out;
	}

	private static RegionRecord.Box union(RegionRecord.@Nullable Box a, RegionRecord.Box b) {
		return a == null ? b : a.union(b);
	}

	public String game() {
		return this.game;
	}

	public String exportHash() {
		return this.exportHash;
	}

	public int originX() {
		return this.originX;
	}

	public int originZ() {
		return this.originZ;
	}

	public List<PlacedMap> maps() {
		return this.maps;
	}

	public @Nullable PlacedMap map(String id) {
		for (PlacedMap map : this.maps) {
			if (map.id().equals(id)) {
				return map;
			}
		}
		return null;
	}

	public @Nullable Box3 surfaceBox() {
		return this.surfaceBox;
	}

	public StoryCellTable storyCells() {
		return this.storyCells;
	}

	/** The slot map of the whole export (the same for every filter). */
	public SlotMap slotMap() {
		return this.slotMap;
	}

	/** The art the whole export needs: what the resource pack is generated from. */
	public ArtCatalog art() {
		return this.art;
	}

	public @Nullable Spawn spawn() {
		return this.spawn;
	}

	public WorldIndex index() {
		return this.index;
	}

	/** The habitat blocks of the built maps (the WorldIndex's {@code habitats}). */
	public List<WorldIndex.Habitat> habitats() {
		return this.index.habitats();
	}

	/**
	 * The habitat pools of the whole export (canonical JSON by pool id, e.g. {@code kantobridge:route_1}): what the
	 * datapack holds, whatever the filter.
	 */
	public Map<String, String> pools() {
		return this.pools;
	}

	/** The habitat blocks in chunk (chunkX, chunkZ). */
	public List<WorldIndex.Habitat> habitatsIn(int chunkX, int chunkZ) {
		return habitatsIn(this.index.habitats(), chunkX, chunkZ);
	}

	/** The habitat blocks of {@code habitats} in chunk (chunkX, chunkZ). */
	public static List<WorldIndex.Habitat> habitatsIn(List<WorldIndex.Habitat> habitats, int chunkX, int chunkZ) {
		List<WorldIndex.Habitat> out = new ArrayList<>();
		for (WorldIndex.Habitat habitat : habitats) {
			if (habitat.x() >> 4 == chunkX && habitat.z() >> 4 == chunkZ) {
				out.add(habitat);
			}
		}
		return out;
	}

	public Warnings warnings() {
		return this.warnings;
	}

	public int mapsRequested() {
		return this.mapsRequested;
	}

	/** Maps asked for (known to the export) that got no place: their ids are in the warnings' {@code maps_unplaced} details. */
	public int mapsUnplaced() {
		return this.mapsUnplaced;
	}

	public int edgeMismatches() {
		return this.edgeMismatches;
	}

	/** The whole export as the neutral scene, or null when shapes weren't planned. */
	public @Nullable Scene scene() {
		return this.scene;
	}

	/** The world's shapes (index, map, keys per map column), or null when they weren't planned. */
	public @Nullable ShapePlan shapes() {
		return this.shapes;
	}

	public int overlaps() {
		return this.overlaps;
	}

	/** The deepest interior layer used (0 when there are no interiors). */
	public int maxLayer() {
		int max = 0;
		for (PlacedMap map : this.maps) {
			max = Math.max(max, map.placement().layer());
		}
		return max;
	}

	// ---- volumes ----

	/** The block boxes the build owns: everything inside is either planned or cleared to air. */
	public List<Box3> volumes() {
		List<Box3> out = new ArrayList<>();
		if (this.surfaceBox != null) {
			out.add(this.surfaceBox);
		}
		for (PlacedMap map : this.maps) {
			if (map.interior()) {
				for (MapLayout.Piece piece : parts(map.placement(), map.layout())) {
					out.add(partVolume(map.placement(), piece, maxCeiling(map.layout())));
				}
			}
		}
		for (WorldIndex.Shaft shaft : this.index.shafts()) {
			out.add(shaftVolume(shaft));
		}
		for (ZonePlan zone : this.zones) {
			out.add(zone.volume());
		}
		return out;
	}

	/**
	 * An open-air interior under the open sky (the voxel plan's V7): an interior placement with the outdoor biome. The
	 * packer gave it a spot nothing lies above, so it has no ceiling.
	 */
	public static boolean openSky(MapPlacement p) {
		return p.kind() == MapPlacement.Kind.INTERIOR && BIOME_OUTDOOR.equals(p.biome());
	}

	/**
	 * The volume of an interior: its footprint and enclosure ring, from the floor block up to the ceiling; under the open
	 * sky up to a wood's canopy (the caves and woods plan's CW2), which the packer keeps free above it anyway, and out to
	 * {@link #SKY_MARGIN} past the map for a wood's apron (CW3).
	 */
	public static Box3 interiorVolume(MapPlacement p) {
		return interiorVolume(p, CEILING_ABOVE_FEET);
	}

	/**
	 * The parts a map is drawn in (CAVES_3D_PLAN §3.5): its layout's pieces, each at its offset with the rock round it,
	 * or the whole map at its placement.
	 */
	public static List<MapLayout.Piece> parts(MapPlacement p, @Nullable MapLayout layout) {
		return layout == null ? List.of(new MapLayout.Piece(0, 0, p.w(), p.h(), 0, 0, 0)) : layout.pieces();
	}

	/** The volume of one part of an interior: its rectangle and enclosure ring at its offset ({@link #interiorVolume}). */
	public static Box3 partVolume(MapPlacement p, MapLayout.Piece piece, int ceiling) {
		boolean sky = openSky(p);
		int top = sky ? Canopy.TOP_LEVEL : ceiling;
		int grow = sky ? SKY_MARGIN - InteriorPacker.RING : 0;
		InteriorPacker.Box box = InteriorPacker.footprint(p.x() + piece.dx() + Scale.cellToBlock(piece.x()), p.z() + piece.dz() + Scale.cellToBlock(piece.y()),
				Scale.cellToBlock(piece.w()), Scale.cellToBlock(piece.h()));
		int feet = p.y() + piece.dy();
		return new Box3(box.minX() - grow, feet - 1, box.minZ() - grow, box.maxX() + grow, feet + top + 1, box.maxZ() + grow);
	}

	/** A cell's north-west block at its feet, {x, y, z}: its piece's offset and its level counted. */
	public static int[] cellOrigin(MapPlacement p, @Nullable MapLayout layout, int cx, int cy) {
		MapLayout.Piece piece = layout == null ? null : layout.pieceAt(cx, cy);
		return new int[] {p.x() + Scale.cellToBlock(cx) + (piece == null ? 0 : piece.dx()), p.y() + (piece == null ? 0 : piece.dy()) + level(layout, cx, cy),
				p.z() + Scale.cellToBlock(cy) + (piece == null ? 0 : piece.dz())};
	}

	/** A shaft's volume: its column and the ring of rock round it, from its bottom cell's feet to its top cell's. */
	public static Box3 shaftVolume(WorldIndex.Shaft shaft) {
		return new Box3(shaft.x() - 1, shaft.y0(), shaft.z() - 1, shaft.x() + Scale.CELL_BLOCKS + 1, shaft.y1() + 2,
				shaft.z() + Scale.CELL_BLOCKS + 1);
	}

	/** The same, with its highest ceiling {@code ceiling} blocks above its feet (a raised cave's, {@link #maxCeiling}). */
	public static Box3 interiorVolume(MapPlacement p, int ceiling) {
		InteriorPacker.Box box = InteriorPacker.footprint(p.x(), p.z(), p.blockWidth(), p.blockDepth());
		boolean sky = openSky(p);
		int top = sky ? Canopy.TOP_LEVEL : ceiling;
		int grow = sky ? SKY_MARGIN - InteriorPacker.RING : 0;
		return new Box3(box.minX() - grow, p.y() - 1, box.minZ() - grow, box.maxX() + grow, p.y() + top + 1, box.maxZ() + grow);
	}

	// ---- raised ground (docs/specs/CAVES_3D_PLAN.md §3.4): heights in blocks above the placement's feet ----

	/** A cell's level: how high its floor stands; 0 without a layout. */
	public static int level(@Nullable MapLayout layout, int cx, int cy) {
		return layout == null ? 0 : layout.level(cx, cy);
	}

	/** Where a cell's hangings and light count from: its floor, a ramp's top. */
	public static int hangBase(@Nullable MapLayout layout, int cx, int cy) {
		if (layout == null) {
			return 0;
		}
		MapLayout.Ramp ramp = layout.ramp(cx, cy);
		return layout.level(cx, cy) + (ramp == null ? 0 : ramp.rise());
	}

	/** A cell's ceiling: 3 blocks of air over its floor (U5), over a ramp's top. */
	public static int ceilingLevel(@Nullable MapLayout layout, int cx, int cy) {
		return hangBase(layout, cx, cy) + CEILING_ABOVE_FEET;
	}

	/**
	 * The top of a cell's ceiling: as high as the highest ceiling of its eight neighbours, so no gap opens between ceilings
	 * of different heights.
	 */
	public static int ceilingTop(@Nullable MapLayout layout, int cx, int cy) {
		int top = ceilingLevel(layout, cx, cy);
		if (layout == null) {
			return top;
		}
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				int nx = cx + dx;
				int ny = cy + dy;
				if (nx >= 0 && ny >= 0 && nx < layout.w() && ny < layout.h()) {
					top = Math.max(top, ceilingLevel(layout, nx, ny));
				}
			}
		}
		return top;
	}

	/** A map's highest ceiling: {@link #CEILING_ABOVE_FEET} without a layout. */
	public static int maxCeiling(@Nullable MapLayout layout) {
		int top = CEILING_ABOVE_FEET;
		if (layout == null) {
			return top;
		}
		for (int cy = 0; cy < layout.h(); cy++) {
			for (int cx = 0; cx < layout.w(); cx++) {
				top = Math.max(top, ceilingLevel(layout, cx, cy));
			}
		}
		return top;
	}

	/** True for a block of a ramp's high row: the half of its cell toward the way it climbs. */
	static boolean highRow(Dir climb, int bx, int bz) {
		return switch (climb) {
			case RIGHT -> bx == 1;
			case LEFT -> bx == 0;
			case DOWN -> bz == 1;
			case UP -> bz == 0;
		};
	}

	/** The columns of an interior's enclosure ring, as {x, z}. */
	public static List<int[]> ringColumns(MapPlacement p) {
		InteriorPacker.Box box = InteriorPacker.footprint(p.x(), p.z(), p.blockWidth(), p.blockDepth());
		List<int[]> out = new ArrayList<>();
		for (int z = box.minZ(); z < box.maxZ(); z++) {
			for (int x = box.minX(); x < box.maxX(); x++) {
				if (!p.containsColumn(x, z)) {
					out.add(new int[] {x, z});
				}
			}
		}
		return out;
	}

	/** The surface volume around surface maps placed at {@code placements}. */
	public static @Nullable Box3 surfaceVolume(Collection<MapPlacement> placements) {
		Box3 box = null;
		for (MapPlacement p : placements) {
			if (p.kind() != MapPlacement.Kind.SURFACE) {
				continue;
			}
			Box3 own = new Box3(p.x(), SURFACE_MIN_Y, p.z(), p.x() + p.blockWidth(), SURFACE_MAX_Y, p.z() + p.blockDepth());
			box = box == null ? own : new Box3(Math.min(box.minX(), own.minX()), SURFACE_MIN_Y, Math.min(box.minZ(), own.minZ()),
					Math.max(box.maxX(), own.maxX()), SURFACE_MAX_Y, Math.max(box.maxZ(), own.maxZ()));
		}
		return box == null ? null : box.inflateXZ(SURFACE_RING);
	}

	/** The volumes a WorldIndex's maps occupied, so a rebuild can clear what an older build left behind. */
	public static List<Box3> volumesOf(WorldIndex previous) {
		List<Box3> out = new ArrayList<>();
		Box3 surface = surfaceVolume(previous.maps().values());
		if (surface != null) {
			out.add(surface);
		}
		for (MapPlacement p : previous.maps().values()) {
			if (p.kind() == MapPlacement.Kind.INTERIOR) {
				MapLayout layout = previous.layout(p.id());
				for (MapLayout.Piece piece : parts(p, layout)) {
					out.add(partVolume(p, piece, maxCeiling(layout)));
				}
			}
		}
		for (WorldIndex.Shaft shaft : previous.shafts()) {
			out.add(shaftVolume(shaft));
		}
		// Wild Routes: an older build's zones are cleared too
		for (WorldIndex.Zone zone : previous.zones()) {
			out.add(new Box3(zone.x(), ZonePlan.MIN_Y, zone.z(), zone.x() + zone.w(), ZonePlan.MAX_Y, zone.z() + zone.d()));
		}
		return out;
	}

	/** Every chunk that touches {@code volumes}, sorted by Z then X. */
	public static List<long[]> chunks(List<Box3> volumes) {
		TreeSet<Long> keys = new TreeSet<>();
		for (Box3 box : volumes) {
			for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
				for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
					keys.add(((long) cz << 32) ^ (cx & 0xFFFFFFFFL));
				}
			}
		}
		List<long[]> out = new ArrayList<>(keys.size());
		for (long key : keys) {
			out.add(new long[] {(int) key, (int) (key >> 32)});
		}
		return out;
	}

	/** Biomes per region (docs/PLAN.md §4.5 "Biomes"): around the surface walking level, and around each interior. */
	public List<BiomeRegion> biomeRegions() {
		List<BiomeRegion> out = new ArrayList<>();
		if (this.surfaceBox != null) {
			Box3 b = this.surfaceBox;
			out.add(new BiomeRegion(new Box3(b.minX(), Scale.SURFACE_Y - 8, b.minZ(), b.maxX(), Scale.SURFACE_Y + 24, b.maxZ()),
					BIOME_OUTDOOR));
		}
		for (PlacedMap map : this.maps) {
			if (!map.interior()) {
				continue;
			}
			int ceiling = maxCeiling(map.layout());
			int top = openSky(map.placement()) ? Canopy.TOP_LEVEL + 2 : 8 + ceiling - CEILING_ABOVE_FEET;
			for (MapLayout.Piece piece : parts(map.placement(), map.layout())) {
				Box3 v = partVolume(map.placement(), piece, ceiling).inflateXZ(4);
				int feet = map.placement().y() + piece.dy();
				out.add(new BiomeRegion(new Box3(v.minX(), feet - 4, v.minZ(), v.maxX(), feet + top, v.maxZ()), map.placement().biome()));
			}
		}
		for (ZonePlan zone : this.zones) {
			out.add(zone.biomeRegion());
		}
		return out;
	}

	// ---- rendering ----

	/**
	 * The planned codes of chunk (chunkX, chunkZ): every position inside the build's volumes (and inside
	 * {@code extraClear}, e.g. an older build's volumes) is planned or air.
	 */
	public ChunkCodes renderChunk(int chunkX, int chunkZ, List<Box3> extraClear) {
		ChunkCodes out = new ChunkCodes(chunkX, chunkZ);
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		List<Box3> owned = new ArrayList<>(this.volumes());
		owned.addAll(extraClear);
		for (Box3 box : owned) {
			if (!box.intersectsChunk(chunkX, chunkZ)) {
				continue;
			}
			for (int y = box.minY(); y < box.maxY(); y++) {
				for (int z = Math.max(z0, box.minZ()); z < Math.min(z0 + 16, box.maxZ()); z++) {
					for (int x = Math.max(x0, box.minX()); x < Math.min(x0 + 16, box.maxX()); x++) {
						out.own(x, y, z);
					}
				}
			}
		}
		Box3 surface = this.surfaceBox;
		if (surface != null && surface.intersectsChunk(chunkX, chunkZ)) {
			for (int z = Math.max(z0, surface.minZ()); z < Math.min(z0 + 16, surface.maxZ()); z++) {
				for (int x = Math.max(x0, surface.minX()); x < Math.min(x0 + 16, surface.maxX()); x++) {
					this.renderSurfaceColumn(out::set, x, z);
				}
			}
		}
		for (PlacedMap map : this.maps) {
			if (!map.interior()) {
				continue;
			}
			for (MapLayout.Piece piece : parts(map.placement(), map.layout())) {
				Box3 volume = partVolume(map.placement(), piece, maxCeiling(map.layout()));
				if (!volume.intersectsChunk(chunkX, chunkZ)) {
					continue;
				}
				for (int z = Math.max(z0, volume.minZ()); z < Math.min(z0 + 16, volume.maxZ()); z++) {
					for (int x = Math.max(x0, volume.minX()); x < Math.min(x0 + 16, volume.maxX()); x++) {
						this.renderInteriorColumn(out::set, map, piece, x, z);
					}
				}
			}
		}
		// The caves' shafts (CAVES_3D_PLAN §3.5), over the rooms they open into
		for (WorldIndex.Shaft shaft : this.index.shafts()) {
			if (shaftVolume(shaft).intersectsChunk(chunkX, chunkZ)) {
				this.renderShaft(out, shaft, x0, z0);
			}
		}
		for (int zi = 0; zi < this.zones.size(); zi++) {
			ZonePlan zone = this.zones.get(zi);
			Box3 volume = zone.volume();
			if (!volume.intersectsChunk(chunkX, chunkZ)) {
				continue;
			}
			int[] ballStory = this.zoneBallStory.get(zi);
			for (int z = Math.max(z0, volume.minZ()); z < Math.min(z0 + 16, volume.maxZ()); z++) {
				for (int x = Math.max(x0, volume.minX()); x < Math.min(x0 + 16, volume.maxX()); x++) {
					zone.renderColumn(out::set, x, z, ballStory);
				}
			}
		}
		return out;
	}

	/** The spawn cell's floor blocks, placed before anything else so an early joiner has ground to stand on. */
	public List<int[]> spawnPlatform() {
		List<int[]> out = new ArrayList<>();
		if (this.spawn == null) {
			return out;
		}
		for (int dz = 0; dz < Scale.CELL_BLOCKS; dz++) {
			for (int dx = 0; dx < Scale.CELL_BLOCKS; dx++) {
				int x = this.spawn.blockX() + dx;
				int z = this.spawn.blockZ() + dz;
				this.renderSurfaceOrInterior(x, z, (bx, by, bz, code) -> {
					if (by == this.spawn.feetY() - 1) {
						out.add(new int[] {bx, by, bz, code});
					}
				});
			}
		}
		return out;
	}

	private void renderSurfaceOrInterior(int x, int z, CodeSink sink) {
		for (PlacedMap map : this.maps) {
			if (map.placement().containsColumn(x, z) && map.placement().y() == this.spawn.feetY()) {
				this.renderMapColumn(sink, map, x, z);
				return;
			}
		}
	}

	private void renderSurfaceColumn(CodeSink sink, int x, int z) {
		Box3 box = this.surfaceBox;
		int i = this.surfaceCell(box, x, z);
		PlacedMap owner = this.maps.get(this.surfaceOwner[i]);
		if (!this.surfaceVoid[i]) {
			this.renderMapColumn(sink, owner, x, z);
			return;
		}
		MapPlacement p = owner.placement();
		int slot = this.slot(owner, this.ringTile(owner, x - p.x(), z - p.z()), false);
		ShapePlan.VoidUnit unit = this.shapes == null ? null : this.shapes.voidUnit(i);
		if (unit != null) {
			// The ring's border tree (V4): its floor wears the owner's ground, the unit's blocks collide as the void did.
			sink.set(x, Scale.GROUND_Y, z, BlockCode.full(unit.ground() >= 0 ? this.slot(owner, unit.ground(), false) : slot));
			for (int k = 0; k < Scale.CELL_BLOCKS; k++) {
				sink.set(x, p.y() + k, z, BlockCode.shape(slot));
			}
			return;
		}
		int code = BlockCode.full(slot);
		for (int y = Scale.GROUND_Y; y <= Scale.GROUND_Y + HeightRules.VOID; y++) {
			sink.set(x, y, z, code);
		}
	}

	private int surfaceCell(Box3 box, int x, int z) {
		int w = (box.maxX() - box.minX()) / Scale.CELL_BLOCKS;
		return ((z - box.minZ()) / Scale.CELL_BLOCKS) * w + (x - box.minX()) / Scale.CELL_BLOCKS;
	}

	private void renderInteriorColumn(CodeSink sink, PlacedMap map, MapLayout.Piece piece, int x, int z) {
		MapPlacement p = map.placement();
		// The map's own cells as this part places them (its whole rectangle for a map in one piece)
		int tx = x - p.x() - piece.dx();
		int ty = z - p.z() - piece.dz();
		int cx = Math.floorDiv(tx, Scale.CELL_BLOCKS);
		int cy = Math.floorDiv(ty, Scale.CELL_BLOCKS);
		boolean inside = cx >= piece.x() && cy >= piece.y() && cx < piece.x() + piece.w() && cy < piece.y() + piece.h();
		if (inside && piece.contains(cx, cy)) {
			this.renderMapColumn(sink, map, piece, x, z);
			return;
		}
		if (this.drawnBySameFrame(map, piece, cx, cy, inside)) {
			// Another piece at this offset draws the column (rooms of one map that didn't move apart)
			return;
		}
		if (inside) {
			// Another room's cell in this one's rectangle: rock (CAVES_3D_PLAN §3.5)
			this.renderRingColumn(sink, map, piece, tx, ty, x, z);
			return;
		}
		ShapePlanner.MapShapes planned = this.shapes == null ? null : this.shapes.shapes(map.id());
		Apron.Plan apron = planned == null || !openSky(p) ? null : planned.apron();
		if (apron != null) {
			this.renderApronColumn(sink, map, apron, x, z);
			return;
		}
		InteriorPacker.Box footprint = InteriorPacker.footprint(p.x() + piece.dx() + Scale.cellToBlock(piece.x()),
				p.z() + piece.dz() + Scale.cellToBlock(piece.y()), Scale.cellToBlock(piece.w()), Scale.cellToBlock(piece.h()));
		if (x < footprint.minX() || x >= footprint.maxX() || z < footprint.minZ() || z >= footprint.maxZ()) {
			// The margin a sky interior keeps for an apron (CW3): air without one.
			return;
		}
		this.renderRingColumn(sink, map, piece, tx, ty, x, z);
		if (planned != null && planned.curtain() != null && openSky(p)) {
			// An open-sky wood's leaf curtain above the ring (CW2), on decor carriers: the ring keeps its collision.
			for (int k = Canopy.CURTAIN_FROM; k < Canopy.TOP_LEVEL; k++) {
				sink.set(x, p.y() + k, z, BlockCode.decor(true));
			}
		}
	}

	/**
	 * A column of an enclosure ring, or of rock where a room's rectangle holds another room's cell: border-block art from
	 * the floor block up to the highest ceiling; a dressed room's wall tile (V7). ({@code tx}, {@code ty}) is the column
	 * in the map's own blocks.
	 */
	private void renderRingColumn(CodeSink sink, PlacedMap map, MapLayout.Piece piece, int tx, int ty, int x, int z) {
		MapPlacement p = map.placement();
		ShapePlanner.MapShapes planned = this.shapes == null ? null : this.shapes.shapes(map.id());
		int tile = planned != null && planned.wallTile() >= 0 ? planned.wallTile() : this.ringTile(map, tx, ty);
		int code = BlockCode.full(this.slot(map, tile, false));
		int base = p.y() + piece.dy();
		for (int y = base - 1; y <= base + maxCeiling(map.layout()); y++) {
			sink.set(x, y, z, code);
		}
	}

	/**
	 * True when another piece of the map at the same offset draws column (cx, cy) (in the map's cells): it owns that cell,
	 * or ({@code inside} false, a ring column) its rectangle holds it.
	 */
	private boolean drawnBySameFrame(PlacedMap map, MapLayout.Piece piece, int cx, int cy, boolean inside) {
		MapLayout layout = map.layout();
		if (layout == null) {
			return false;
		}
		for (MapLayout.Piece other : layout.pieces()) {
			if (other == piece || other.dx() != piece.dx() || other.dy() != piece.dy() || other.dz() != piece.dz()) {
				continue;
			}
			boolean holds = cx >= other.x() && cy >= other.y() && cx < other.x() + other.w() && cy < other.y() + other.h();
			if (inside ? other.contains(cx, cy) : holds) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A cave's shaft (CAVES_3D_PLAN §3.5): air from over its bottom cell's feet up through its top cell's floor; a ladder
	 * (Minecraft's, climbed as one) up one wall from the bottom cell's feet to the top cell's, unless it is a hole, and
	 * then the top cell's floor kept beside it, so a player climbing up steps off onto it; rock round it where it runs
	 * between the two rooms; a light half way up unless the cave is dark.
	 */
	private void renderShaft(ChunkCodes out, WorldIndex.Shaft shaft, int x0, int z0) {
		PlacedMap top = this.map(shaft.top().map());
		PlacedMap bottom = this.map(shaft.bottom().map());
		if (top == null || bottom == null) {
			return;
		}
		Box3 topVolume = partVolume(top.placement(), part(top, shaft.top().x(), shaft.top().y()), maxCeiling(top.layout()));
		Box3 bottomVolume = partVolume(bottom.placement(), part(bottom, shaft.bottom().x(), shaft.bottom().y()), maxCeiling(bottom.layout()));
		Dir wall = shaft.wall();
		MapPlacement tp = top.placement();
		int rock = BlockCode.full(this.slot(top, top.model().borderTile(shaft.top().x() * Scale.CELL_BLOCKS, shaft.top().y() * Scale.CELL_BLOCKS), false));
		int middle = (shaft.y0() + shaft.y1()) / 2;
		boolean lit = !bottom.placement().dark() && !tp.dark();
		for (int z = shaft.z() - 1; z <= shaft.z() + Scale.CELL_BLOCKS; z++) {
			for (int x = shaft.x() - 1; x <= shaft.x() + Scale.CELL_BLOCKS; x++) {
				if (x < x0 || x >= x0 + 16 || z < z0 || z >= z0 + 16) {
					continue;
				}
				int bx = x - shaft.x();
				int bz = z - shaft.z();
				boolean column = bx >= 0 && bz >= 0 && bx < Scale.CELL_BLOCKS && bz < Scale.CELL_BLOCKS;
				if (!column) {
					// The ring: rock where neither room's volume holds the position
					for (int y = shaft.y0() + 1; y <= shaft.y1(); y++) {
						if (!topVolume.contains(x, y, z) && !bottomVolume.contains(x, y, z)) {
							out.set(x, y, z, rock);
						}
					}
					continue;
				}
				boolean ladder = wall != null && onWall(wall, bx, bz);
				for (int y = shaft.y0() + 1; y <= shaft.y1(); y++) {
					if (shaft.contains(x, y, z)) {
						out.set(x, y, z, BlockCode.AIR);
					}
				}
				if (ladder) {
					for (int y = shaft.y0(); y <= shaft.y1() + 1; y++) {
						out.set(x, y, z, BlockCode.ladder(wall.opposite()));
					}
				} else if (lit && bx == (wall == Dir.LEFT ? 1 : 0) && bz == (wall == Dir.UP ? 1 : 0)) {
					out.set(x, middle, z, BlockCode.light(LIGHT_LEVEL));
				}
			}
		}
	}

	/** The piece that draws cell (cx, cy) of a map: its layout's, else the whole map. */
	private static MapLayout.Piece part(PlacedMap map, int cx, int cy) {
		MapLayout layout = map.layout();
		MapLayout.Piece piece = layout == null ? null : layout.pieceAt(cx, cy);
		return piece != null ? piece : parts(map.placement(), layout).get(0);
	}

	/**
	 * The wall a shaft's ladder stands on (HD6): the one you face walking into its top cell, opposite the cell's only
	 * walkable neighbour; north when it has several (or none).
	 */
	static Dir ladderWall(MapModel model, int cx, int cy) {
		Dir from = null;
		int count = 0;
		for (Dir dir : Dir.values()) {
			int nx = cx + dir.dx();
			int ny = cy + dir.dy();
			if (nx >= 0 && ny >= 0 && nx < model.cellWidth() && ny < model.cellHeight()
					&& (model.kind(nx, ny) == MapModel.Kind.GROUND || model.kind(nx, ny) == MapModel.Kind.WATER)) {
				from = dir;
				count++;
			}
		}
		return count == 1 ? from.opposite() : Dir.UP;
	}

	/** True when block (bx, bz) of a 2 × 2 column lies along its {@code wall} side. */
	static boolean onWall(Dir wall, int bx, int bz) {
		return switch (wall) {
			case UP -> bz == 0;
			case DOWN -> bz == 1;
			case LEFT -> bx == 0;
			case RIGHT -> bx == 1;
		};
	}

	/**
	 * A column of a canopy wood's apron (the caves and woods plan's CW3), all vanilla but its floor: the floor block in the
	 * forest's grass tile, the apron's leaves, logs and vines, the invisible wall's barriers on the enclosure ring, and the
	 * moss wall around it all.
	 */
	private void renderApronColumn(CodeSink sink, PlacedMap map, Apron.Plan apron, int x, int z) {
		MapPlacement p = map.placement();
		int tx = x - p.x();
		int ty = z - p.z();
		byte kind = apron.kind(tx, ty);
		if (kind == Apron.WALL) {
			for (int k = -1; k <= Apron.WALL_TOP; k++) {
				sink.set(x, p.y() + k, z, BlockCode.vanilla(ZoneBlock.MOSS_BLOCK));
			}
			return;
		}
		ZoneBlock[] column = apron.column(tx, ty);
		if (kind != Apron.WOOD || column == null) {
			return;
		}
		// The planner picks a floor tile the map shows on dry ground, so it has a slot; the ring's own tile if not.
		int floor = this.hasSlot(map, apron.floorTile()) ? apron.floorTile() : this.ringTile(map, tx, ty);
		sink.set(x, p.y() - 1, z, BlockCode.full(this.slot(map, floor, false)));
		for (int k = 0; k < column.length; k++) {
			if (column[k] != null) {
				sink.set(x, p.y() + k, z, BlockCode.vanilla(column[k]));
			}
		}
	}

	private void renderMapColumn(CodeSink sink, PlacedMap map, int x, int z) {
		this.renderMapColumn(sink, map, parts(map.placement(), map.layout()).get(0), x, z);
	}

	/** A column of one of the map's cells as {@code piece} places it. */
	private void renderMapColumn(CodeSink sink, PlacedMap map, MapLayout.Piece piece, int x, int z) {
		MapPlacement p = map.placement();
		MapModel model = map.model();
		int tx = x - p.x() - piece.dx();
		int ty = z - p.z() - piece.dz();
		// The feet level of the map at this piece; each cell stands on its own level above it.
		int base = p.y() + piece.dy();
		int cx = tx / Scale.CELL_BLOCKS;
		int cy = ty / Scale.CELL_BLOCKS;
		// A cell of a cave's raised ground stands on its level; a ramp's steps climb from its floor (CAVES_3D_PLAN §3.4).
		MapLayout layout = map.layout();
		MapLayout.Ramp ramp = layout == null ? null : layout.ramp(cx, cy);
		int feet = base + level(layout, cx, cy);
		// The hangings and the light count from a ramp's top.
		int lift = hangBase(layout, cx, cy) - level(layout, cx, cy);
		int tile = model.tile(tx, ty);
		int column = 0;
		ShapePlanner.MapShapes planned = this.shapes == null ? null : this.shapes.shapes(map.id());
		int planIndex = ty * model.cellWidth() * Scale.CELL_BLOCKS + tx;
		int story = this.storyIndex.get(map.id())[cy * model.cellWidth() + cx];
		// The levels from feet a block already takes: the hangings (CW1) go only where nothing is.
		long taken = 0;
		switch (model.kind(cx, cy)) {
			case GROUND -> {
				int floorTile = planned == null ? tile : planned.floorTile(planIndex, tile);
				sink.set(x, feet - 1, z, BlockCode.full(this.slot(map, floorTile, false)));
				int from = 0;
				if (ramp != null) {
					// A ramp's two half steps: on the floor in its low row, on a block in its high row (whose carrier draws the
					// stair's art there).
					if (highRow(ramp.dir(), tx - cx * Scale.CELL_BLOCKS, ty - cy * Scale.CELL_BLOCKS)) {
						sink.set(x, feet, z, BlockCode.full(this.slot(map, tile, false)));
						sink.set(x, feet + 1, z, BlockCode.step(ramp.dir()));
						from = 2;
					} else {
						sink.set(x, feet, z, BlockCode.step(ramp.dir()));
						from = 1;
					}
					column = from;
					taken |= (1L << from) - 1;
				}
				// A tuft (V4) stands on a decor carrier at feet level, unless a story block takes the position; a building's
				// doorway or overhang (P2) on decor carriers up its column; over a ramp's steps, the rest of its stair.
				int decor = planned == null ? 0 : planned.decor()[planIndex];
				for (int k = from; k < 31 && decor >> k != 0; k++) {
					if ((decor >> k & 1) != 0 && (k > 0 || story < 0)) {
						sink.set(x, feet + k, z, BlockCode.decor(false));
						column = Math.max(column, k + 1);
						taken |= 1L << k;
					}
				}
			}
			case WATER -> sink.set(x, feet - 1, z, BlockCode.half(this.slot(map, tile, true)));
			case LEDGE -> {
				sink.set(x, feet - 1, z, BlockCode.full(this.slot(map, tile, false)));
				sink.set(x, feet, z, BlockCode.ledge(CellFlags.ledgeDir(model.flags(cx, cy))));
				taken |= 1L;
			}
			case COUNTER, SOLID -> {
				int slot = this.slot(map, tile, false);
				int code = BlockCode.full(slot);
				// The floor block wears a unit's ground tile (V4), else its own.
				sink.set(x, feet - 1, z, planned == null ? code : BlockCode.full(this.slot(map, planned.floorTile(planIndex, tile), false)));
				// The column on the floor block: its planned blocks (tile_full; shape_full where a roof or a unit stands), else
				// today's.
				int blocks = planned == null ? model.height(cx, cy) : planned.blocks()[planIndex];
				int shaped = planned == null ? 0 : planned.shapeMask()[planIndex];
				for (int k = 0; k < blocks; k++) {
					sink.set(x, feet + k, z, (shaped >> k & 1) != 0 ? BlockCode.shape(slot) : code);
				}
				column = blocks;
				taken |= (1L << Math.min(blocks, 62)) - 1;
			}
		}
		// Where a story block stands, a level above the feet on a ramp's steps (CAVES_3D_PLAN §3.4), and its code.
		int storyLevel = -1;
		int storyCode = 0;
		if (story >= 0) {
			// Tile cells get the block that carries the bottom of their closed art; the others render nothing.
			StoryCellTable.Entry entry = this.storyCells.entries().get(story);
			boolean swapCell = entry.type() == StoryCellType.TILE;
			// On a ramp the steps keep their places: any other story block (a boulder's, an object's) stands on them.
			storyLevel = ramp == null || swapCell ? 0 : highRow(ramp.dir(), tx - cx * Scale.CELL_BLOCKS, ty - cy * Scale.CELL_BLOCKS) ? 2 : 1;
			storyCode = swapCell ? BlockCode.storyTile(story) : BlockCode.story(story);
			sink.set(x, feet + storyLevel, z, storyCode);
			taken |= 1L << storyLevel;
			ShapePlan.Closed closed = swapCell && this.shapes != null ? this.shapes.closed(entry.key()) : null;
			if (closed != null) {
				// The rest of this column of the closed art (V5): decor carriers in the air above, drawn only for players who
				// see the cell closed; no collision (it comes from the story block and each player's story state).
				int h = closed.heights()[(ty - cy * Scale.CELL_BLOCKS) * Scale.CELL_BLOCKS + tx - cx * Scale.CELL_BLOCKS];
				for (int k = 1; k < h; k++) {
					sink.set(x, feet + k, z, BlockCode.decor(closed.leaves()));
				}
				column = Math.max(column, h);
				taken |= (1L << Math.min(h, 62)) - 1;
			}
		}
		// An invisible wall on the cell's tile-pair edges (CAVES_3D_PLAN §3.2): at feet, else in the block above, where it
		// still stops a player 1.8 blocks tall.
		int edges = map.edges().blockSides(cx, cy, tx - cx * Scale.CELL_BLOCKS, ty - cy * Scale.CELL_BLOCKS);
		if (edges != 0) {
			if ((taken & 1) == 0) {
				sink.set(x, feet, z, BlockCode.edge(edges));
				taken |= 1;
			} else if ((taken & 2) == 0) {
				sink.set(x, feet + 1, z, BlockCode.edge(edges));
				taken |= 2;
			} else if (storyLevel == 0) {
				// Neither is free (a swap site's closed art over its story block): the story block carries the plates.
				sink.set(x, feet, z, BlockCode.kind(storyCode) == BlockCode.KIND_STORY_TILE ? BlockCode.storyTile(story, edges)
						: BlockCode.story(story, edges));
			}
		}
		// A carrier wins: the light goes only where the position is still air, or a hanging's carrier there gives it.
		boolean light = map.interior() && !p.dark() && Math.floorMod(tx, LIGHT_SPACING) == 1 && Math.floorMod(ty, LIGHT_SPACING) == 1
				&& column <= LIGHT_ABOVE_FEET + lift;
		int hung = planned == null ? 0 : ShapePlanner.hungFits(planned.hungMask()[planIndex], ShapePlanner.hangTop(p));
		int leaves = planned == null ? 0 : planned.hungLeaves()[planIndex];
		// The cave area's rock fill (CAVES_3D_PLAN §3.3, HD9): over rock, the air up to the ceiling is solid, so a floor is
		// the only place a wild Pokémon can stand there.
		boolean fill = map.interior() && BIOME_CAVE_3D.equals(p.biome())
				&& (model.kind(cx, cy) == MapModel.Kind.SOLID || model.kind(cx, cy) == MapModel.Kind.COUNTER);
		for (int k = 0; k < 31 && hung >> k != 0; k++) {
			if ((hung >> k & 1) == 0 || (taken >> (k + lift) & 1) != 0) {
				continue;
			}
			// A hanging (CW1, CW2): a decor carrier for its piece, foliage for a wood's; the one on a light's position gives
			// the light (CD15).
			boolean lit = light && k == LIGHT_ABOVE_FEET;
			sink.set(x, feet + lift + k, z, BlockCode.decor((leaves >> k & 1) != 0, lit, fill && k < CEILING_ABOVE_FEET));
			light &= !lit;
			taken |= 1L << (k + lift);
		}
		if (fill) {
			for (int k = column; k < CEILING_ABOVE_FEET; k++) {
				if ((taken >> k & 1) != 0) {
					continue;
				}
				// A light's position keeps its light, on a solid carrier; elsewhere an invisible barrier.
				boolean lit = light && k == LIGHT_ABOVE_FEET;
				sink.set(x, feet + k, z, lit ? BlockCode.decor(false, true, true) : BlockCode.vanilla(dev.kantobridge.builder.zone.ZoneBlock.BARRIER));
				light &= !lit;
				taken |= 1L << k;
			}
		}
		if (map.interior()) {
			// The ceiling's art: the border block's; a dressed room's wall tile, its shape darkening it (V7).
			int ceiling = planned != null && planned.wallTile() >= 0 ? planned.wallTile() : model.borderTile(tx, ty);
			if (!openSky(p)) {
				// The ceiling, and up to its neighbours' where theirs are higher (raised ground).
				int rock = BlockCode.full(this.slot(map, ceiling, false));
				for (int k = ceilingLevel(layout, cx, cy); k <= ceilingTop(layout, cx, cy); k++) {
					sink.set(x, base + k, z, rock);
				}
			}
			if (feet > base) {
				// Under a raised floor, rock in the ceiling's art down to the map's floor level.
				int rock = BlockCode.full(this.slot(map, ceiling, false));
				for (int y = base - 1; y < feet - 1; y++) {
					sink.set(x, y, z, rock);
				}
			}
			if (light) {
				sink.set(x, feet + lift + LIGHT_ABOVE_FEET, z, BlockCode.light(LIGHT_LEVEL));
			}
		}
	}

	/**
	 * The art of the void fill and the interior rings at tile (tx, ty) of a map: the border the game really draws there
	 * when this build plans shapes (the voxel plan's V3), else the map's own border block, as before.
	 */
	private int ringTile(PlacedMap map, int tx, int ty) {
		return this.shapes != null ? map.model().drawnBorderTile(tx, ty) : map.model().borderTile(tx, ty);
	}

	private boolean hasSlot(PlacedMap map, int tile) {
		TileSlots slots = this.tileSlots.get(map.atlas() + "|" + map.palette());
		return slots != null && tile >= 0 && tile < slots.full.length && slots.full[tile] >= 0;
	}

	private int slot(PlacedMap map, int tile, boolean half) {
		TileSlots slots = this.tileSlots.get(map.atlas() + "|" + map.palette());
		int index = slots == null ? -1 : (half ? slots.half : slots.full)[tile];
		if (index < 0) {
			// The planner collects a key for every tile it renders; a gap here is a bug, not bad data.
			throw new IllegalStateException("no slot planned for a rendered tile on " + map.id());
		}
		return index;
	}
}
