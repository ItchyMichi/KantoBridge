package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.zone.ZonePlan;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Collection;
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

	public static final String BIOME_OUTDOOR = "kantobridge:kanto_outdoor";
	public static final String BIOME_INTERIOR = "kantobridge:kanto_interior";
	public static final String BIOME_CAVE = "kantobridge:kanto_cave";

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

	WorldPlan(String game, String exportHash, int originX, int originZ, List<PlacedMap> maps, @Nullable Box3 surfaceBox, int[] surfaceOwner,
			boolean[] surfaceVoid, StoryCellTable storyCells, Map<String, int[]> storyIndex, ArtCatalog art, @Nullable Spawn spawn,
			WorldIndex index, Map<String, String> pools, Warnings warnings, int mapsRequested, int mapsUnplaced, int edgeMismatches, int overlaps,
			@Nullable Scene scene, @Nullable ShapePlan shapes) {
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
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : this.slotMap.slots().entrySet()) {
			SlotMap.Key key = entry.getKey();
			TileSlots slots = this.tileSlots.computeIfAbsent(key.atlas() + "|" + key.palette(), k -> new TileSlots());
			(key.shape() == SlotMap.Shape.FULL ? slots.full : slots.half)[key.tile()] = entry.getValue().index();
		}
	}

	/** A copy of {@code base} with zones and the index that lists them. */
	private WorldPlan(WorldPlan base, List<ZonePlan> zones, WorldIndex index) {
		this.game = base.game;
		this.exportHash = base.exportHash;
		this.originX = base.originX;
		this.originZ = base.originZ;
		this.maps = base.maps;
		this.surfaceBox = base.surfaceBox;
		this.surfaceOwner = base.surfaceOwner;
		this.surfaceVoid = base.surfaceVoid;
		this.storyCells = base.storyCells;
		this.storyIndex = base.storyIndex;
		this.art = base.art;
		this.slotMap = base.slotMap;
		this.tileSlots.putAll(base.tileSlots);
		this.spawn = base.spawn;
		this.index = index;
		this.pools = base.pools;
		this.warnings = base.warnings;
		this.mapsRequested = base.mapsRequested;
		this.mapsUnplaced = base.mapsUnplaced;
		this.edgeMismatches = base.edgeMismatches;
		this.overlaps = base.overlaps;
		this.scene = base.scene;
		this.shapes = base.shapes;
		this.zones = List.copyOf(zones);
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
		for (ZonePlan zone : zones) {
			placements.add(zone.routeMap());
			entries.add(zone.zone());
		}
		WorldIndex withZones = new WorldIndex(this.game, this.exportHash, placements, this.index.slotMapHash(), this.storyCells,
				this.index.habitats(), entries);
		return new WorldPlan(this, zones, withZones);
	}

	/** The plan's Wild Routes zones (empty for a build without any). */
	public List<ZonePlan> zones() {
		return this.zones;
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
				out.add(interiorVolume(map.placement()));
			}
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

	/** The volume of an interior: its footprint and enclosure ring, from the floor block up to the ceiling. */
	public static Box3 interiorVolume(MapPlacement p) {
		InteriorPacker.Box box = InteriorPacker.footprint(p.x(), p.z(), p.blockWidth(), p.blockDepth());
		return new Box3(box.minX(), p.y() - 1, box.minZ(), box.maxX(), p.y() + CEILING_ABOVE_FEET + 1, box.maxZ());
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
				out.add(interiorVolume(p));
			}
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
			Box3 v = interiorVolume(map.placement()).inflateXZ(4);
			int feet = map.placement().y();
			out.add(new BiomeRegion(new Box3(v.minX(), feet - 4, v.minZ(), v.maxX(), feet + 8, v.maxZ()), map.placement().biome()));
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
			Box3 volume = interiorVolume(map.placement());
			if (!volume.intersectsChunk(chunkX, chunkZ)) {
				continue;
			}
			for (int z = Math.max(z0, volume.minZ()); z < Math.min(z0 + 16, volume.maxZ()); z++) {
				for (int x = Math.max(x0, volume.minX()); x < Math.min(x0 + 16, volume.maxX()); x++) {
					this.renderInteriorColumn(out::set, map, x, z);
				}
			}
		}
		for (ZonePlan zone : this.zones) {
			Box3 volume = zone.volume();
			if (!volume.intersectsChunk(chunkX, chunkZ)) {
				continue;
			}
			for (int z = Math.max(z0, volume.minZ()); z < Math.min(z0 + 16, volume.maxZ()); z++) {
				for (int x = Math.max(x0, volume.minX()); x < Math.min(x0 + 16, volume.maxX()); x++) {
					zone.renderColumn(out::set, x, z);
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

	private void renderInteriorColumn(CodeSink sink, PlacedMap map, int x, int z) {
		MapPlacement p = map.placement();
		if (p.containsColumn(x, z)) {
			this.renderMapColumn(sink, map, x, z);
			return;
		}
		// The enclosure ring: border-block art from the floor block up to the ceiling; a dressed room's wall tile (V7).
		ShapePlanner.MapShapes planned = this.shapes == null ? null : this.shapes.shapes(map.id());
		int tile = planned != null && planned.wallTile() >= 0 ? planned.wallTile() : this.ringTile(map, x - p.x(), z - p.z());
		int code = BlockCode.full(this.slot(map, tile, false));
		for (int y = p.y() - 1; y <= p.y() + CEILING_ABOVE_FEET; y++) {
			sink.set(x, y, z, code);
		}
	}

	private void renderMapColumn(CodeSink sink, PlacedMap map, int x, int z) {
		MapPlacement p = map.placement();
		MapModel model = map.model();
		int tx = x - p.x();
		int ty = z - p.z();
		int cx = tx / Scale.CELL_BLOCKS;
		int cy = ty / Scale.CELL_BLOCKS;
		int feet = p.y();
		int tile = model.tile(tx, ty);
		int column = 0;
		ShapePlanner.MapShapes planned = this.shapes == null ? null : this.shapes.shapes(map.id());
		int planIndex = ty * model.cellWidth() * Scale.CELL_BLOCKS + tx;
		int story = this.storyIndex.get(map.id())[cy * model.cellWidth() + cx];
		switch (model.kind(cx, cy)) {
			case GROUND -> {
				int floorTile = planned == null ? tile : planned.floorTile(planIndex, tile);
				sink.set(x, feet - 1, z, BlockCode.full(this.slot(map, floorTile, false)));
				// A tuft (V4) stands on a decor carrier at feet level, unless a story block takes the position; a building's
				// doorway or overhang (P2) on decor carriers up its column.
				int decor = planned == null ? 0 : planned.decor()[planIndex];
				for (int k = 0; k < 31 && decor >> k != 0; k++) {
					if ((decor >> k & 1) != 0 && (k > 0 || story < 0)) {
						sink.set(x, feet + k, z, BlockCode.decor(false));
						column = Math.max(column, k + 1);
					}
				}
			}
			case WATER -> sink.set(x, feet - 1, z, BlockCode.half(this.slot(map, tile, true)));
			case LEDGE -> {
				sink.set(x, feet - 1, z, BlockCode.full(this.slot(map, tile, false)));
				sink.set(x, feet, z, BlockCode.ledge(CellFlags.ledgeDir(model.flags(cx, cy))));
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
			}
		}
		if (story >= 0) {
			// Tile cells get the block that carries the bottom of their closed art; the others render nothing.
			StoryCellTable.Entry entry = this.storyCells.entries().get(story);
			boolean swapCell = entry.type() == StoryCellType.TILE;
			sink.set(x, feet, z, swapCell ? BlockCode.storyTile(story) : BlockCode.story(story));
			ShapePlan.Closed closed = swapCell && this.shapes != null ? this.shapes.closed(entry.key()) : null;
			if (closed != null) {
				// The rest of this column of the closed art (V5): decor carriers in the air above, drawn only for players who
				// see the cell closed; no collision (it comes from the story block and each player's story state).
				int h = closed.heights()[(ty - cy * Scale.CELL_BLOCKS) * Scale.CELL_BLOCKS + tx - cx * Scale.CELL_BLOCKS];
				for (int k = 1; k < h; k++) {
					sink.set(x, feet + k, z, BlockCode.decor(closed.leaves()));
				}
				column = Math.max(column, h);
			}
		}
		if (map.interior()) {
			if (!openSky(p)) {
				// The ceiling: the border block's art; a dressed room's wall tile, its shape darkening it (V7).
				int ceiling = planned != null && planned.wallTile() >= 0 ? planned.wallTile() : model.borderTile(tx, ty);
				sink.set(x, feet + CEILING_ABOVE_FEET, z, BlockCode.full(this.slot(map, ceiling, false)));
			}
			// A carrier wins: the light goes only where the position is still air.
			if (!p.dark() && Math.floorMod(tx, LIGHT_SPACING) == 1 && Math.floorMod(ty, LIGHT_SPACING) == 1 && column <= LIGHT_ABOVE_FEET) {
				sink.set(x, feet + LIGHT_ABOVE_FEET, z, BlockCode.light(LIGHT_LEVEL));
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
