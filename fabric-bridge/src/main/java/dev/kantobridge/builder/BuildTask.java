package dev.kantobridge.builder;

import com.cobblemon.mod.common.api.habitats.HabitatPool;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.builder.pack.DatapackWriter;
import dev.kantobridge.builder.pack.HabitatPoolWriter;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.place.BlockPlacer;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.HabitatPlanner;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.RegionBuild;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.builder.shape.QuadCounter;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.ShapeReport;
import dev.kantobridge.builder.zone.TerrainSampler;
import dev.kantobridge.builder.zone.VanillaTerrain;
import dev.kantobridge.builder.zone.ZoneContent;
import dev.kantobridge.builder.zone.ZonePlan;
import dev.kantobridge.builder.zone.ZonePlanner;
import dev.kantobridge.builder.zone.ZoneRegions;
import dev.kantobridge.character.CharacterReport;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.scene.profile.ProfileSource;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.ShapeDocs;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * One running build: planning and the resource pack off the server thread, then block placement a few milliseconds
 * per server tick, then the WorldIndex, slot map, datapack and report. Created by {@link KantoWorldBuilder#start};
 * ticked by {@link BuildTasks}.
 *
 * <p>Several games (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): a <b>region build</b> ({@link BuildOptions#region()}) plans
 * from its slot's origin, assigns its tiles inside its own family range of the world's one slot map, prefixes its pool ids,
 * writes its documents under {@code kantobridge/regions/<id>/} with its record, updates {@code regions.json}, keeps every
 * other region's pools in the datapack and refuses to overlap another region ({@code build.region_overlap}). A world that
 * has {@code regions.json} takes region builds only ({@code build.region_required}). A <b>legacy build</b> (no region)
 * writes exactly what it wrote before regions existed (§4.14); the first region build of a legacy world records its old
 * Kanto as the implicit legacy region (its documents stay at the root until Kanto itself is rebuilt as a region).
 *
 * <p>Logs carry counts, map ids, phases and short hashes only.
 */
public final class BuildTask {
	/** Where a world keeps the builder's files. */
	public static final String DATA_FOLDER = "kantobridge";
	public static final String INDEX_FILE = "world_index.json";
	public static final String SLOT_MAP_FILE = "slot_map.json";
	/** The voxel plan's shape files (docs/specs/VOXEL_BLOCKS_PLAN.md §4.1): keys, and positions → ids. */
	public static final String SHAPE_INDEX_FILE = "shape_index.json";
	public static final String SHAPE_MAP_FILE = "shape_map.bin";
	/** The recorded self-check, per map id: beside the build report (and its copy). */
	public static final String SELFCHECK_FILE = "shape_selfcheck.json";

	private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "kantobridge-builder");
		thread.setDaemon(true);
		return thread;
	});

	/** A failure with a report code. */
	static final class Failure extends RuntimeException {
		final String code;

		Failure(String code, Throwable cause) {
			super(code, cause);
			this.code = code;
		}
	}

	private record Prepared(WorldPlan plan, @Nullable ResourcePackWriter.Result pack, @Nullable CharacterReport characters, List<Box3> previousVolumes,
			List<WorldIndex.Habitat> previousHabitats, long planMs, @Nullable ShapeReport shapes, long packMs, long zoneMs,
			@Nullable RegionSetup region) {
	}

	/**
	 * What a region build (MG3) decided before planning and must write after it.
	 *
	 * @param build         what the planner was told
	 * @param originX       the region's cell origin (the world offset plus the slot's)
	 * @param originZ       likewise
	 * @param table         the region table as read (the built region joins it at the end)
	 * @param others        every other region's record (the overlap check, the datapack merge)
	 * @param implicitLegacy the legacy world's old Kanto as a record when the table didn't list it yet: written and added
	 *                      to the table at the end, unless it is the region being rebuilt
	 * @param previousIndex the index of this region's previous build, or null
	 * @param rootDocuments true when this region's previous documents sit at the root (they are removed at the end)
	 */
	private record RegionSetup(RegionBuild build, int originX, int originZ, RegionTable table, Map<String, RegionRecord> others,
			@Nullable RegionRecord implicitLegacy, @Nullable WorldIndex previousIndex, boolean rootDocuments) {
	}

	private final ServerLevel level;
	private final ExportBundle bundle;
	private final @Nullable List<String> maps;
	private final BuildOptions options;
	private final BuildListener listener;
	private final Path worldDir;
	private final Path packDir;
	private final int minBuildHeight;
	private final long startNanos = System.nanoTime();
	private final BuildReport report = new BuildReport();

	private volatile BuildPhase phase = BuildPhase.PLANNING;
	private @Nullable CompletableFuture<Prepared> preparing;
	private @Nullable Prepared prepared;
	private @Nullable BlockPlacer placer;
	private long placeStartNanos;
	/** {@link #indexWireChars}'s memo: the plan it was worked out for, and the size. */
	private @Nullable ShapePlan indexWirePlan;
	private long indexWireChars;
	/** {@link #worldIndexWireChars}'s memo, as {@link #indexWirePlan}. */
	private @Nullable WorldPlan worldIndexWirePlan;
	private long worldIndexWireChars;
	private int lastDecile = -1;

	BuildTask(ServerLevel level, ExportBundle bundle, @Nullable List<String> maps, BuildOptions options, BuildListener listener) {
		this.level = level;
		this.bundle = bundle;
		this.maps = maps == null ? null : List.copyOf(maps);
		this.options = options;
		this.listener = listener;
		this.worldDir = options.worldDir() != null ? options.worldDir() : KantoWorldBuilder.worldDir(level);
		this.packDir = options.packDir() != null ? options.packDir() : KantoWorldBuilder.defaultPackDir();
		this.minBuildHeight = level.getMinBuildHeight();
	}

	void start() {
		KantoBridge.LOGGER.info("builder: start (export {}, {} maps requested)", Hashes.shortHash(this.bundle.exportHash()),
				this.maps == null ? "all" : this.maps.size());
		this.listener.onPhase(BuildPhase.PLANNING);
		this.preparing = CompletableFuture.supplyAsync(this::prepare, PLANNER);
	}

	public MinecraftServer server() {
		return this.level.getServer();
	}

	public ServerLevel level() {
		return this.level;
	}

	public BuildPhase phase() {
		return this.phase;
	}

	public boolean isDone() {
		return this.phase.finished();
	}

	public boolean succeeded() {
		return this.phase == BuildPhase.DONE;
	}

	/** The report: complete once the build is done or failed. */
	public BuildReport report() {
		return this.report;
	}

	/** The plan, once planning has finished. */
	public @Nullable WorldPlan plan() {
		return this.prepared != null ? this.prepared.plan() : null;
	}

	public Path worldDir() {
		return this.worldDir;
	}

	public Path packDir() {
		return this.packDir;
	}

	/** Placement progress: done and total chunks (0/0 before placement). */
	public int[] progress() {
		BlockPlacer p = this.placer;
		return p == null ? new int[] {0, 0} : new int[] {p.doneChunks(), p.totalChunks()};
	}

	// ---- off-thread: plan and pack ----

	private Prepared prepare() {
		long t0 = System.nanoTime();
		try {
			Path data = this.worldDir.resolve(DATA_FOLDER);
			RegionSetup region = null;
			int originX = this.options.originX();
			int originZ = this.options.originZ();
			if (this.options.regionBuild()) {
				region = this.prepareRegion(data);
				originX = region.originX();
				originZ = region.originZ();
				// MG5 (D4): one region per world space. The default region id of a world space (Games.region) is refused
				// once another game of that space has a region in the table; a test world's second Kanto uses its own id
				// (test_kanto2), which is always allowed, as is anything under -Dkantobridge.testRegions=true.
				String space = Games.region(this.bundle.game());
				if (space != null && space.equals(this.options.region()) && !Boolean.getBoolean("kantobridge.testRegions")) {
					for (RegionRecord other : region.others().values()) {
						if (space.equals(Games.region(other.game()))) {
							KantoBridge.LOGGER.error("builder: region {} already holds the world space of game {}", other.id(), this.bundle.game());
							throw new Failure("build.region_exists", null);
						}
					}
				}
			} else if (Files.isRegularFile(data.resolve(RegionTable.FILE))) {
				// A world with regions takes region builds only: a legacy build would write a root index beside them.
				throw new Failure("build.region_required", null);
			}
			WorldPlan planned = WorldPlanner.plan(this.bundle, new WorldPlanner.Options(this.maps, originX, originZ, this.minBuildHeight,
					this.options.shapes()), region == null ? null : region.build());
			// Wild Routes (WR3, docs/specs/WILD_ROUTES_PLAN.md §4.4): the zones the export lists, each in its slot on the
			// vanilla overworld's land for this world's seed. A zone whose town isn't in this build is left out.
			long zoneStarted = System.nanoTime();
			ExportRoutes routes = this.bundle.routes();
			List<String> towns = routes.zones().stream().map(ExportRoutes.Zone::townMap).toList();
			// MG8: a Gen 3 export's zones take their pools, sprites and items from its g3 resources; MG9 G2H: a Gen 2's from its g2
			List<String> classes = routes.zones().stream().flatMap(z -> z.classes().stream()).distinct().toList();
			ZoneContent content = routes.zones().isEmpty() ? ZoneContent.EMPTY
					: this.bundle.isGen3() ? ZoneContent.ofGen3(this.bundle, towns, classes)
							: this.bundle.isGen2() ? ZoneContent.ofGen2(this.bundle, towns, classes) : ZoneContent.of(this.bundle, towns);
			WorldPlan plan = withZones(planned, routes, this.level.getSeed(),
					() -> VanillaTerrain.of(this.level.getServer().registryAccess(), this.level.getSeed()), originX, originZ, content);
			long zoneMs = (System.nanoTime() - zoneStarted) / 1_000_000L;
			if (region != null) {
				// MG3: a region never overlaps another's box (the record's bounds)
				RegionRecord.Box bounds = plan.regionBounds();
				for (RegionRecord other : region.others().values()) {
					if (bounds != null && bounds.overlaps(other.bounds())) {
						KantoBridge.LOGGER.error("builder: region {} would overlap region {}", this.options.region(), other.id());
						throw new Failure("build.region_overlap", null);
					}
				}
			}
			long packStarted = System.nanoTime();
			ResourcePackWriter.Result pack = this.options.writePack() ? ResourcePackWriter.write(plan, this.bundle, this.packDir, plan.warnings())
					: null;
			long packMs = (System.nanoTime() - packStarted) / 1_000_000L;
			WorldIndex previous = region != null ? region.previousIndex() : previousIndex(this.worldDir);
			// The geometry a client would build from the shape index, the self-check of every placed map (recorded) and the
			// quads the planned world draws.
			ShapeReport shapes = plan.shapes() == null || plan.scene() == null ? null : ShapeReport.of(plan, FrontEnds.artSource(this.bundle));
			// The character styles' pure parts on the export's sprites (the voxel plan's V8): what each client would fall back to.
			CharacterReport characters = plan.shapes() == null ? null : CharacterReport.of(this.bundle);
			return new Prepared(plan, pack, characters, previous == null ? List.of() : WorldPlan.volumesOf(previous),
					previous == null ? List.of() : previous.habitats(), (System.nanoTime() - t0) / 1_000_000L, shapes, packMs, zoneMs, region);
		} catch (BundleException e) {
			throw new Failure(e.code(), e);
		} catch (PlanException e) {
			throw new Failure(e.code(), e);
		} catch (IOException e) {
			throw new Failure("io.pack", e);
		}
	}

	/**
	 * MG3: what a region build needs before planning: the region table and the other regions' records, the region's
	 * family range (its own from an earlier build, else the next free range of 16), its previous index and the world's
	 * slot map (migrated to version 2 when it is a legacy map), and the legacy world's implicit Kanto record when the
	 * table doesn't list it yet.
	 */
	private RegionSetup prepareRegion(Path data) {
		String id = this.options.region();
		int slot = this.options.slot();
		RegionTable table = readTable(data);
		RegionTable.Entry atSlot = table.bySlot(slot);
		if (atSlot != null && !atSlot.id().equals(id)) {
			KantoBridge.LOGGER.error("builder: slot {} already holds region {}", slot, atSlot.id());
			throw new Failure("build.slot_taken", null);
		}
		RegionTable.Entry listed = table.byId(id);
		if (listed != null && listed.slot() != slot) {
			KantoBridge.LOGGER.error("builder: region {} sits at slot {}, not {}", id, listed.slot(), slot);
			throw new Failure("build.region_slot", null);
		}
		Map<String, RegionRecord> records = new LinkedHashMap<>();
		for (RegionTable.Entry entry : table.entries()) {
			records.put(entry.id(), readRecord(data, entry.id()));
		}
		// the legacy world's Kanto: the root index, when no record describes the root documents yet
		WorldIndex rootIndex = previousIndex(this.worldDir);
		RegionRecord implicitLegacy = null;
		if (rootIndex != null && records.values().stream().noneMatch(RegionRecord::isLegacy)) {
			implicitLegacy = legacyRecord(data, rootIndex);
		}
		RegionRecord existing = records.get(id);
		boolean rootDocuments = false;
		if (existing == null && implicitLegacy != null && implicitLegacy.id().equals(id)) {
			existing = implicitLegacy;
			rootDocuments = true;
		} else if (existing != null && existing.isLegacy()) {
			rootDocuments = true;
		}
		if (existing != null && existing.slot() != slot) {
			KantoBridge.LOGGER.error("builder: region {} was built at slot {}, not {}", id, existing.slot(), slot);
			throw new Failure("build.region_slot", null);
		}
		Map<String, RegionRecord> others = new LinkedHashMap<>(records);
		others.remove(id);
		if (implicitLegacy != null && !implicitLegacy.id().equals(id)) {
			others.put(implicitLegacy.id(), implicitLegacy);
		}
		SlotMap.Range families = existing != null ? existing.families()
				: nextFreeRange(others.values(), SlotMap.familiesFor(Games.family(this.bundle.game())));
		if (families == null) {
			KantoBridge.LOGGER.error("builder: no free family range for region {} ({} families per shape)", id, SlotMap.FAMILIES_PER_SHAPE);
			throw new Failure("build.slot_map_full", null);
		}
		WorldIndex previous = existing == null ? null : existing.isLegacy() ? rootIndex : readIndex(data.resolve(existing.files().index()));
		String artSet = Games.artSet(this.bundle.game());
		if (artSet == null) {
			artSet = this.bundle.game();
		}
		SlotMap previousSlots = readSlotMap(data.resolve(SLOT_MAP_FILE));
		if (previousSlots != null && previousSlots.version() == SlotMap.VERSION && !SlotMap.LEGACY_REGION.equals(id)) {
			// a legacy map holds the legacy region's slots at families 0..15: it becomes version 2 before another region joins
			RegionRecord legacy = implicitLegacy != null ? implicitLegacy : records.values().stream().filter(RegionRecord::isLegacy).findFirst().orElse(null);
			String legacySet = legacy == null ? null : Games.artSet(legacy.game());
			if (legacySet == null) {
				KantoBridge.LOGGER.warn("builder: the world's legacy slot map names no game; starting a new one");
				previousSlots = null;
			} else {
				previousSlots = previousSlots.migrate(legacySet);
			}
		}
		int originX = this.options.originX() + table.originX(slot);
		int originZ = this.options.originZ() + table.originZ(slot);
		RegionBuild build = new RegionBuild(id, slot, artSet, families, previousSlots, RegionBuild.poolPrefix(id, slot));
		KantoBridge.LOGGER.info("builder: region {} at slot {} (origin {}, {}; families {}..{}; {} other regions)", id, slot, originX, originZ,
				families.lo(), families.hi(), others.size());
		return new RegionSetup(build, originX, originZ, table, others, implicitLegacy, previous, rootDocuments);
	}

	/** The next free range of 16 families, or null when every range is taken (MULTI_GAME_PLAN §4.6, D9). */
	static SlotMap.@Nullable Range nextFreeRange(Collection<RegionRecord> others) {
		return nextFreeRange(others, SlotMap.LEGACY_FAMILIES.size());
	}

	/**
	 * The next free range of {@code size} families starting on a multiple of 16 (MG7: a Gen 3 region takes 80, E7), or
	 * null when none is free.
	 */
	static SlotMap.@Nullable Range nextFreeRange(Collection<RegionRecord> others, int size) {
		int step = SlotMap.LEGACY_FAMILIES.size();
		for (int lo = 0; lo + size - 1 < SlotMap.FAMILIES_PER_SHAPE; lo += step) {
			SlotMap.Range candidate = new SlotMap.Range(lo, lo + size - 1);
			boolean free = true;
			for (RegionRecord other : others) {
				if (other.families().overlaps(candidate)) {
					free = false;
				}
			}
			if (free) {
				return candidate;
			}
		}
		return null;
	}

	/** The world's region table, or an empty one when it has none. */
	static RegionTable readTable(Path data) {
		Path file = RegionTable.file(data);
		if (!Files.isRegularFile(file)) {
			return RegionTable.empty();
		}
		try {
			return RegionTable.fromJson(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.error("builder: the world's region table is unreadable ({})", e.getClass().getSimpleName());
			throw new Failure("build.regions_unreadable", e);
		}
	}

	/** A region's record, {@code regions/<id>/region.json}. */
	private static RegionRecord readRecord(Path data, String id) {
		Path file = data.resolve(RegionRecord.fileRecord(id));
		try {
			return RegionRecord.fromJson(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.error("builder: the record of region {} is unreadable ({})", id, e.getClass().getSimpleName());
			throw new Failure("build.region_unreadable", e);
		}
	}

	/** The world's slot map, or null when it has none or it is unreadable (a warning). */
	private static @Nullable SlotMap readSlotMap(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return SlotMap.fromJson(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.warn("builder: the world's slot map is unreadable ({}); starting a new one", e.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * The legacy world's Kanto as a region record (MULTI_GAME_PLAN §4.14): the root index with the root shape documents'
	 * planner and hashes, when they are there.
	 */
	private static RegionRecord legacyRecord(Path data, WorldIndex rootIndex) {
		String planner = null;
		String shapeIndexHash = null;
		String shapeMapHash = null;
		Path shapeIndex = data.resolve(SHAPE_INDEX_FILE);
		Path shapeMap = data.resolve(SHAPE_MAP_FILE);
		if (Files.isRegularFile(shapeIndex) && Files.isRegularFile(shapeMap)) {
			try {
				ShapeIndex index = ShapeIndex.fromJson(Files.readString(shapeIndex, StandardCharsets.UTF_8));
				planner = index.planner();
				shapeIndexHash = index.hash();
				shapeMapHash = ShapeMap.decode(Files.readAllBytes(shapeMap)).hash();
			} catch (IOException | DataException e) {
				KantoBridge.LOGGER.warn("builder: the root shape documents are unreadable ({}); the legacy record names none",
						e.getClass().getSimpleName());
				planner = null;
				shapeIndexHash = null;
				shapeMapHash = null;
			}
		}
		try {
			return RegionRecord.legacy(rootIndex, planner, shapeIndexHash, shapeMapHash);
		} catch (IllegalArgumentException e) {
			// the planner id of an older shape index may not fit the record: the record then names no generator
			return RegionRecord.legacy(rootIndex, null, shapeIndexHash, shapeMapHash);
		}
	}

	/** The record a region build writes: the plan's bounds, areas and documents, the catalogue's rules. */
	private RegionRecord regionRecord(WorldPlan plan, @Nullable ShapePlan shapes, RegionSetup setup) {
		String game = this.bundle.game();
		String id = setup.build().id();
		String family = Games.family(game);
		if (family == null) {
			throw new Failure("build.unknown_game", null);
		}
		RegionRecord.Box bounds = plan.regionBounds();
		if (bounds == null) {
			bounds = new RegionRecord.Box(setup.originX(), setup.originZ(), setup.originX(), setup.originZ(), Scale.GROUND_Y, Scale.SURFACE_Y);
		}
		String empty = Hashes.sha256("");
		RegionRecord.DocumentHashes hashes = new RegionRecord.DocumentHashes(plan.index().hash(), shapes == null ? empty : shapes.index().hash(),
				shapes == null ? empty : shapes.map().hash());
		String generator = shapes == null ? RegionRecord.NO_GENERATOR : shapes.index().planner();
		Games.Compat compat = Games.compat(game);
		RegionRecord.Rules rules = new RegionRecord.Rules(family, null,
				compat == null ? new RegionRecord.Compat(1, 1, null, RegionRecord.LEAVE_OUT) : RegionRecord.Compat.of(compat));
		try {
			return new RegionRecord(id, game, family, this.bundle.exportHash(), setup.build().slot(), new RegionRecord.Origin(setup.originX(),
					Scale.SURFACE_Y, setup.originZ()), bounds, plan.regionAreas(), setup.build().families(), RegionRecord.Documents.of(id), hashes,
					generator, rules, RegionRecord.Namespaces.of(game, id));
		} catch (IllegalArgumentException e) {
			if (!RegionRecord.NO_GENERATOR.equals(generator)) {
				// a planner id the record can't carry: the record names no generator rather than failing the build
				return new RegionRecord(id, game, family, this.bundle.exportHash(), setup.build().slot(), new RegionRecord.Origin(setup.originX(),
						Scale.SURFACE_Y, setup.originZ()), bounds, plan.regionAreas(), setup.build().families(), RegionRecord.Documents.of(id), hashes,
						RegionRecord.NO_GENERATOR, rules, RegionRecord.Namespaces.of(game, id));
			}
			throw new Failure("build.region_record", e);
		}
	}

	/**
	 * The datapack's pools after this build (MG3, R2): the other regions' pools as the world has them, this region's as
	 * planned. A pool belongs to the region whose prefix it starts with; the unprefixed region (slot 0) owns those that
	 * match no other region's prefix.
	 */
	static Map<String, String> mergedPools(Path worldDir, Map<String, String> own, String ownPrefix, Collection<String> otherPrefixes)
			throws IOException {
		Map<String, String> out = new TreeMap<>();
		Path folder = worldDir.resolve("datapacks").resolve(DatapackWriter.FOLDER).resolve(HabitatPoolWriter.FOLDER);
		if (Files.isDirectory(folder)) {
			try (java.util.stream.Stream<Path> files = Files.list(folder)) {
				for (Path file : files.toList()) {
					String name = file.getFileName().toString();
					if (!name.endsWith(".json") || !Files.isRegularFile(file)) {
						continue;
					}
					String path = name.substring(0, name.length() - ".json".length());
					if (keepsPool(path, ownPrefix, otherPrefixes)) {
						out.put(HabitatPlanner.POOL_NAMESPACE + ":" + path, Files.readString(file, StandardCharsets.UTF_8));
					}
				}
			}
		}
		out.putAll(own);
		return out;
	}

	/** True when a pool file of the world belongs to another region than the one being built. */
	static boolean keepsPool(String path, String ownPrefix, Collection<String> otherPrefixes) {
		if (ownPrefix.isEmpty()) {
			for (String prefix : otherPrefixes) {
				if (!prefix.isEmpty() && path.startsWith(prefix)) {
					return true;
				}
			}
			return false;
		}
		return !path.startsWith(ownPrefix);
	}

	/** {@link #withZones} without trainers or balls. */
	public static WorldPlan withZones(WorldPlan plan, ExportRoutes routes, long seed, java.util.function.Supplier<TerrainSampler> terrain,
			int originX, int originZ) {
		return withZones(plan, routes, seed, terrain, originX, originZ, ZoneContent.EMPTY);
	}

	/**
	 * {@code plan} with the export's zones (Wild Routes, WR3): planned in id order into slots south of Kanto, on the
	 * land {@code terrain} describes (made only when there are zones to plan), with trainers and balls from
	 * {@code content} (WR5). A zone whose town map isn't placed in this build (a filtered build) is left out, with a
	 * warning.
	 */
	public static WorldPlan withZones(WorldPlan plan, ExportRoutes routes, long seed, java.util.function.Supplier<TerrainSampler> terrain,
			int originX, int originZ, ZoneContent content) {
		if (routes.zones().isEmpty()) {
			return plan;
		}
		List<ExportRoutes.Zone> kept = new java.util.ArrayList<>();
		for (ExportRoutes.Zone zone : routes.zones()) {
			if (plan.index().map(zone.townMap()) != null) {
				kept.add(zone);
			} else {
				plan.warnings().warn("zone_town_unbuilt");
			}
		}
		if (kept.isEmpty()) {
			return plan;
		}
		ZoneRegions regions = ZoneRegions.of(plan.surfaceBox(), originX, originZ);
		// MG3: a region's zones carry its pool prefix (nothing for a legacy build)
		List<ZonePlan> zones = ZonePlanner.plan(new ExportRoutes(kept), seed, regions, terrain.get(),
				town -> plan.index().map(town).palette(), content, plan.poolPrefix());
		return plan.withZones(zones);
	}

	/** The volumes of the build this world had before, so this one clears them. */
	static List<Box3> previousVolumes(Path worldDir) {
		WorldIndex previous = previousIndex(worldDir);
		return previous == null ? List.of() : WorldPlan.volumesOf(previous);
	}

	/** The index of the build this world had before (its volumes and habitat blocks are cleared), or null. */
	static @Nullable WorldIndex previousIndex(Path worldDir) {
		return readIndex(worldDir.resolve(DATA_FOLDER).resolve(INDEX_FILE));
	}

	/** A stored WorldIndex, or null when the file isn't there or is unreadable (a warning). */
	static @Nullable WorldIndex readIndex(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return WorldIndex.fromJson(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.warn("builder: the previous world index is unreadable ({}); not clearing its volumes", e.getClass().getSimpleName());
			return null;
		}
	}

	// ---- server thread ----

	void tick() {
		try {
			switch (this.phase) {
				case PLANNING -> this.tickPlanning();
				case PLACING -> this.tickPlacing();
				default -> {
				}
			}
		} catch (RuntimeException e) {
			this.fail(e);
		}
	}

	private void tickPlanning() {
		if (this.preparing == null || !this.preparing.isDone()) {
			return;
		}
		try {
			this.prepared = this.preparing.join();
		} catch (CompletionException | CancellationException e) {
			this.fail(e.getCause() != null ? e.getCause() : e);
			return;
		}
		WorldPlan plan = this.prepared.plan();
		Map<String, HabitatPool> pools;
		try {
			// Cobblemon gets the pools now; the datapack written at the end holds them for later loads.
			pools = HabitatService.ensurePools(plan.pools());
		} catch (RuntimeException e) {
			this.fail(new Failure("habitat.pool", e));
			return;
		}
		this.placer = new BlockPlacer(this.level, plan, this.prepared.previousVolumes(), this.prepared.previousHabitats(), pools);
		this.placer.placeSpawnPlatform();
		WorldPlan.Spawn spawn = plan.spawn();
		// MG3: a region at another slot never moves the world's spawn
		if (spawn != null && this.options.setWorldSpawn() && (!this.options.regionBuild() || this.options.slot() == 0)) {
			this.level.setDefaultSpawnPos(new BlockPos(spawn.blockX(), spawn.feetY(), spawn.blockZ()), 0.0F);
		}
		KantoBridge.LOGGER.info("builder: planned {} maps ({} warnings), {} story cells, {} habitat blocks on {} pools, {} slots, {} chunks to place in {} ms",
				plan.maps().size(), plan.warnings().total(), plan.storyCells().size(), plan.habitats().size(), plan.pools().size(),
				plan.slotMap().size(), this.placer.totalChunks(), this.prepared.planMs());
		this.placeStartNanos = System.nanoTime();
		this.phase = BuildPhase.PLACING;
		this.listener.onPhase(BuildPhase.PLACING);
	}

	private void tickPlacing() {
		BlockPlacer p = this.placer;
		boolean done = p.tick(System.nanoTime() + this.options.tickBudgetMs() * 1_000_000L);
		int total = Math.max(1, p.totalChunks());
		int decile = p.doneChunks() * 10 / total;
		if (decile != this.lastDecile) {
			this.lastDecile = decile;
			this.listener.onProgress(p.doneChunks(), p.totalChunks());
			KantoBridge.LOGGER.info("builder: placed {}/{} chunks", p.doneChunks(), p.totalChunks());
		}
		if (done) {
			this.finish();
		}
	}

	private void finish() {
		this.phase = BuildPhase.FINISHING;
		this.listener.onPhase(BuildPhase.FINISHING);
		WorldPlan plan = this.prepared.plan();
		RegionSetup setup = this.prepared.region();
		RegionRecord record = null;
		try {
			Path data = this.worldDir.resolve(DATA_FOLDER);
			Files.createDirectories(data);
			ShapePlan shapes = plan.shapes();
			if (setup == null) {
				Files.writeString(data.resolve(SLOT_MAP_FILE), plan.slotMap().toJson(), StandardCharsets.UTF_8);
				Files.writeString(data.resolve(INDEX_FILE), plan.index().toJson(), StandardCharsets.UTF_8);
				if (shapes != null) {
					Files.writeString(data.resolve(SHAPE_INDEX_FILE), shapes.index().toJson(), StandardCharsets.UTF_8);
					Files.write(data.resolve(SHAPE_MAP_FILE), shapes.map().encode());
				} else {
					// An unshaped build leaves no stale shape files behind: a world without them shows cubes.
					Files.deleteIfExists(data.resolve(SHAPE_INDEX_FILE));
					Files.deleteIfExists(data.resolve(SHAPE_MAP_FILE));
				}
				if (this.options.writeDatapack()) {
					DatapackWriter.write(this.worldDir, plan.pools());
				}
			} else {
				// MG3: the region's documents under regions/<id>/, the world's slot map and region table at the root
				String id = setup.build().id();
				Path dir = data.resolve(RegionRecord.DIR).resolve(id);
				Files.createDirectories(dir);
				Files.writeString(data.resolve(SLOT_MAP_FILE), plan.worldSlotMap().toJson(), StandardCharsets.UTF_8);
				Files.writeString(dir.resolve(INDEX_FILE), plan.index().toJson(), StandardCharsets.UTF_8);
				if (shapes != null) {
					Files.writeString(dir.resolve(SHAPE_INDEX_FILE), shapes.index().toJson(), StandardCharsets.UTF_8);
					Files.write(dir.resolve(SHAPE_MAP_FILE), shapes.map().encode());
				} else {
					Files.deleteIfExists(dir.resolve(SHAPE_INDEX_FILE));
					Files.deleteIfExists(dir.resolve(SHAPE_MAP_FILE));
				}
				record = this.regionRecord(plan, shapes, setup);
				Files.writeString(dir.resolve(RegionRecord.FILE), record.toJson(), StandardCharsets.UTF_8);
				RegionTable table = setup.table().with(new RegionTable.Entry(id, this.bundle.game(), setup.build().slot()));
				RegionRecord legacy = setup.implicitLegacy();
				List<String> otherPrefixes = new java.util.ArrayList<>();
				for (RegionRecord other : setup.others().values()) {
					otherPrefixes.add(other.poolPrefix());
				}
				if (legacy != null && !legacy.id().equals(id)) {
					// the legacy world's Kanto joins the table as it is: its documents stay at the root until it is rebuilt
					Path legacyDir = data.resolve(RegionRecord.DIR).resolve(legacy.id());
					Files.createDirectories(legacyDir);
					Files.writeString(legacyDir.resolve(RegionRecord.FILE), legacy.toJson(), StandardCharsets.UTF_8);
					table = table.with(new RegionTable.Entry(legacy.id(), legacy.game(), legacy.slot()));
				}
				Files.writeString(RegionTable.file(data), table.toJson(), StandardCharsets.UTF_8);
				if (setup.rootDocuments()) {
					// this region's documents were the root's: they live under regions/<id>/ from now on
					Files.deleteIfExists(data.resolve(INDEX_FILE));
					Files.deleteIfExists(data.resolve(SHAPE_INDEX_FILE));
					Files.deleteIfExists(data.resolve(SHAPE_MAP_FILE));
				}
				if (this.options.writeDatapack()) {
					DatapackWriter.write(this.worldDir, mergedPools(this.worldDir, plan.pools(), plan.poolPrefix(), otherPrefixes));
				}
			}
		} catch (IOException e) {
			this.fail(new Failure("io.world", e));
			return;
		} catch (IllegalArgumentException e) {
			this.fail(new Failure("build.region_table", e));
			return;
		} catch (Failure e) {
			this.fail(e);
			return;
		}
		this.report.worldIndex = true;
		plan.warnings().warn("habitat_unconfigured", this.placer.counts().habitatFailures);
		this.fillReport();
		this.report.status = "ok";
		this.writeReport();
		if (this.options.publishIndex()) {
			// With its slot map and shapes, so players who join later (or are online now) draw it with their own art.
			ShapePlan shapes = plan.shapes();
			if (record == null) {
				WorldIndexSync.publish(this.level.getServer(), plan.index(), plan.slotMap(), shapes == null ? null : shapes.index().toJson(),
						shapes == null ? null : shapes.map().encode(), true);
			} else {
				WorldIndexSync.publishRegion(this.level.getServer(), record, plan.index(), plan.worldSlotMap(),
						shapes == null ? null : shapes.index().toJson(), shapes == null ? null : shapes.map().encode(), true);
			}
		}
		this.phase = BuildPhase.DONE;
		KantoBridge.LOGGER.info("builder: done: {} maps, {} story cells, {} habitat blocks, {} pools, {} blocks placed, {} cleared, {} chunks, {} textures, {} warnings, {} ms",
				this.report.mapsBuilt, this.report.storyCells, this.report.habitatBlocks, this.report.pools, this.report.blocksPlaced,
				this.report.blocksCleared, this.report.chunks, this.report.packTextures, this.report.warnings, this.report.elapsedMs);
		KantoBridge.LOGGER.info("builder: throughput {} blocks/s, {} chunks/s while placing; {} maps/s overall",
				BuildReport.rate(this.report.blocksPlaced, this.report.placeMs), BuildReport.rate(this.report.chunks, this.report.placeMs),
				BuildReport.rate(this.report.mapsBuilt, this.report.elapsedMs));
		this.report.warningCodes.forEach((code, count) -> KantoBridge.LOGGER.warn("builder: warning {} x{}", code, count));
		this.listener.onPhase(BuildPhase.DONE);
		this.listener.onFinished(this.report);
	}

	/** Stops the build (server shutdown, or a caller giving up); releases its chunk tickets. */
	public void cancel() {
		this.fail(new Failure("cancelled", new CancellationException()));
	}

	private void fail(Throwable error) {
		if (this.phase.finished()) {
			return;
		}
		if (this.placer != null) {
			this.placer.release();
		}
		String code = error instanceof Failure failure ? failure.code : "internal." + error.getClass().getSimpleName();
		if (code.startsWith("internal.")) {
			KantoBridge.LOGGER.error("builder: failed ({})", code, error);
		} else {
			// Bundle and plan messages name only kinds, ids and JSON paths (never content), so they may be logged.
			Throwable cause = error.getCause();
			String detail = cause instanceof BundleException || cause instanceof PlanException ? cause.getMessage() : code;
			KantoBridge.LOGGER.error("builder: failed ({}): {}", code, detail);
		}
		this.fillReport();
		this.report.status = "failed";
		this.report.error = code;
		this.writeReport();
		this.phase = BuildPhase.FAILED;
		this.listener.onPhase(BuildPhase.FAILED);
		this.listener.onFailed(this.report);
	}

	private void fillReport() {
		BuildReport r = this.report;
		r.game = this.bundle.game();
		r.exportHash = this.bundle.exportHash();
		long now = System.nanoTime();
		r.elapsedMs = (now - this.startNanos) / 1_000_000L;
		if (this.prepared == null) {
			r.mapsRequested = this.maps == null
					? this.bundle.ids(this.bundle.isGen3() ? "g3map" : this.bundle.isGen2() ? "g2map" : "map").size() : this.maps.size();
			return;
		}
		WorldPlan plan = this.prepared.plan();
		RegionSetup setup = this.prepared.region();
		if (setup != null) {
			SlotMap.Range families = setup.build().families();
			ShapePlan shapePlan = plan.shapes();
			r.region = new BuildReport.RegionReport(setup.build().id(), setup.build().slot(), setup.originX(), Scale.SURFACE_Y, setup.originZ(),
					plan.regionBounds(), plan.regionAreas(), families.lo(), families.hi(), shapePlan == null ? 0 : shapePlan.map().wire().length(),
					shapePlan == null ? 0 : this.indexWireChars(shapePlan), this.worldIndexWireChars(plan));
		}
		r.planMs = this.prepared.planMs();
		r.placeMs = this.placeStartNanos == 0 ? 0 : (now - this.placeStartNanos) / 1_000_000L;
		r.mapsRequested = plan.mapsRequested();
		r.mapsBuilt = plan.maps().size();
		r.mapsUnplaced = plan.mapsUnplaced();
		r.surfaceMaps = (int) plan.maps().stream().filter(m -> !m.interior()).count();
		r.interiors = (int) plan.maps().stream().filter(PlacedMap::interior).count();
		r.layers = plan.maxLayer();
		r.overlaps = plan.overlaps();
		r.edgeMismatches = plan.edgeMismatches();
		r.storyCells = plan.storyCells().size();
		r.storyCellsByType.clear();
		for (Map.Entry<StoryCellType, Integer> entry : plan.storyCells().countByType().entrySet()) {
			r.storyCellsByType.put(entry.getKey().id(), entry.getValue());
		}
		r.warnings = plan.warnings().total();
		r.warningCodes = plan.warnings().warnings();
		r.notes = plan.warnings().notes();
		r.slotMapHash = plan.slotMap().hash();
		r.slotsFull = plan.slotMap().count(SlotMap.Shape.FULL);
		r.slotsHalf = plan.slotMap().count(SlotMap.Shape.HALF);
		r.worldIndexHash = plan.index().hash();
		r.spawnMap = plan.spawn() != null ? plan.spawn().map() : null;
		WorldPlan.Spawn spawn = plan.spawn();
		r.spawnBlock = spawn == null ? null : new int[] {spawn.blockX(), spawn.feetY(), spawn.blockZ()};
		Box3 surface = plan.surfaceBox();
		r.surfaceBox = surface == null ? null
				: new int[] {surface.minX(), surface.minY(), surface.minZ(), surface.maxX(), surface.maxY(), surface.maxZ()};
		r.pools = plan.pools().size();
		r.zones = plan.zones().size();
		r.zoneTrees = plan.zones().stream().mapToInt(ZonePlan::trees).sum();
		r.zoneTrainers = plan.zones().stream().mapToInt(z -> z.zone().trainers().size()).sum();
		r.zoneBalls = plan.zones().stream().mapToInt(z -> z.zone().balls().size()).sum();
		// MG5: the connecting halves' pass cells (one gate house each)
		r.zonePasses = plan.zones().stream().mapToInt(z -> z.zone().pass() == null ? 0 : z.zone().pass().cells().size()).sum();
		r.zoneWalkableCells = plan.zones().stream().mapToInt(z -> z.layout().stats().walkable()).sum();
		r.zonePlanMs = this.prepared.zoneMs();
		ResourcePackWriter.Result pack = this.prepared.pack();
		if (pack != null) {
			r.packTextures = pack.textures();
			r.tileTextures = pack.tileTextures();
			r.npcTextures = pack.npcTextures();
			r.animatedTextures = pack.animated();
			r.packHash = pack.hash();
		}
		if (this.placer != null) {
			BlockPlacer.Counts counts = this.placer.counts();
			r.blocksPlaced = counts.blocksPlaced;
			r.blocksCleared = counts.blocksCleared;
			r.chunks = counts.chunks;
			r.biomeChunks = counts.biomeChunks;
			r.storyCellBlocks = counts.storyCells;
			r.ledgeColliders = counts.ledgeColliders;
			r.lightBlocks = counts.lights;
			r.habitatBlocks = counts.habitatBlocks;
			r.blocksByKind.clear();
			r.blocksByKind.put("tile_full", counts.byKind[BlockCode.KIND_FULL]);
			r.blocksByKind.put("tile_half", counts.byKind[BlockCode.KIND_HALF]);
			r.blocksByKind.put("story_cell", counts.byKind[BlockCode.KIND_STORY]);
			r.blocksByKind.put("story_tile", counts.byKind[BlockCode.KIND_STORY_TILE]);
			r.blocksByKind.put("ledge", counts.byKind[BlockCode.KIND_LEDGE]);
			r.blocksByKind.put("edge_wall", counts.byKind[BlockCode.KIND_EDGE]);
			r.blocksByKind.put("step_collider", counts.byKind[BlockCode.KIND_STEP]);
			r.blocksByKind.put("shaft_ladder", counts.byKind[BlockCode.KIND_LADDER]);
			r.blocksByKind.put("light", counts.byKind[BlockCode.KIND_LIGHT]);
			r.blocksByKind.put("habitat", counts.habitatBlocks);
			r.blocksByKind.put("shape_full", counts.byKind[BlockCode.KIND_SHAPE]);
			r.blocksByKind.put("decor", counts.byKind[BlockCode.KIND_DECOR] - counts.decorLeaves);
			r.blocksByKind.put("decor_leaves", counts.decorLeaves);
			r.shapeHostsAdded = counts.byKind[BlockCode.KIND_SHAPE] + counts.byKind[BlockCode.KIND_DECOR];
		}
		this.fillShapes(r, plan);
	}

	/** The voxel plan's groups: cells by kind, solid heights, the shape files and the recorded self-check. */
	/** The shape index's wire form in characters (deflated, item 4), worked out once per plan. */
	/** The plan's WorldIndex on the wire: Base64 of its deflated text (MG9 G2G), worked out once per plan. */
	private long worldIndexWireChars(WorldPlan plan) {
		if (this.worldIndexWirePlan != plan) {
			this.worldIndexWirePlan = plan;
			this.worldIndexWireChars = ShapeDocs.wire(plan.index().toJson()).length();
		}
		return this.worldIndexWireChars;
	}

	private long indexWireChars(ShapePlan shapes) {
		if (this.indexWirePlan != shapes) {
			this.indexWirePlan = shapes;
			this.indexWireChars = ShapeDocs.wire(shapes.index().toJson()).length();
		}
		return this.indexWireChars;
	}

	private void fillShapes(BuildReport r, WorldPlan plan) {
		r.cellsByKind.clear();
		r.solidCellsByHeight.clear();
		ShapePlan planned = plan.shapes();
		for (PlacedMap map : plan.maps()) {
			MapModel model = map.model();
			// A shaped build's heights are the planner's (the tallest column of the cell); an unshaped one's the classes.
			ShapePlanner.MapShapes columns = planned == null ? null : planned.shapes(map.id());
			for (int cy = 0; cy < model.cellHeight(); cy++) {
				for (int cx = 0; cx < model.cellWidth(); cx++) {
					MapModel.Kind kind = model.kind(cx, cy);
					String name = switch (kind) {
						case GROUND -> "ground";
						case WATER -> "water";
						case LEDGE -> "ledge";
						case COUNTER -> "counter";
						case SOLID -> "solid";
					};
					r.cellsByKind.merge(name, 1, Integer::sum);
					if (kind == MapModel.Kind.SOLID) {
						int height = model.height(cx, cy);
						if (columns != null) {
							height = 0;
							int cols = model.cellWidth() * 2;
							for (int dy = 0; dy < 2; dy++) {
								for (int dx = 0; dx < 2; dx++) {
									height = Math.max(height, columns.blocks()[(cy * 2 + dy) * cols + cx * 2 + dx]);
								}
							}
						}
						r.solidCellsByHeight.merge(Integer.toString(height), 1, Integer::sum);
					}
				}
			}
		}
		r.worldIndexBytes = plan.index().toJson().getBytes(StandardCharsets.UTF_8).length;
		r.worldIndexWireChars = this.worldIndexWireChars(plan);
		ProfileSource source = plan.scene() == null ? null : plan.scene().profile();
		if (source != null) {
			// The profile plan's report (§5.7): what the export carried, as counts and a short hash.
			r.profileStatus = source.status();
			r.profileVersion = source.modVersion();
			r.profileEnabled = source.enabled();
			Profile profile = source.profile();
			if (profile != null) {
				r.profileHash = profile.hash().substring(0, 16);
				r.profileTilesets = profile.counts().tilesets();
				r.profilePins = profile.counts().pins();
				r.profileConds = profile.counts().conds();
				r.profileBuildingEntries = profile.counts().buildingEntries();
				r.profileUnknown.clear();
				profile.counts().unknown().forEach((k, v) -> r.profileUnknown.put(k, (long) v));
			}
		}
		ShapePlan shapes = plan.shapes();
		if (shapes == null) {
			return;
		}
		r.unitsBySource.clear();
		r.profileBuildings.clear();
		r.profileClasses.clear();
		r.dressing.clear();
		shapes.unitTally().forEach((k, v) -> {
			if (k.startsWith("dress_")) {
				// The caves and woods plan's hangings (§4.2), by piece.
				r.dressing.put(k.substring(6), (long) v);
				return;
			}
			(k.startsWith("building") ? r.profileBuildings
					: k.startsWith("box_") || k.startsWith("bookcase_") || k.startsWith("canopy") || k.startsWith("planter") ? r.profileClasses
							: r.unitsBySource).put(k, (long) v);
		});
		r.shapeIndexHash = shapes.index().hash();
		r.shapePlanner = shapes.index().planner();
		r.shapeMapHash = shapes.map().hash();
		r.shapeMapBytes = shapes.map().size();
		r.shapeMapWireChars = shapes.map().wire().length();
		r.shapeIndexBytes = shapes.index().toJson().getBytes(StandardCharsets.UTF_8).length;
		r.shapeIndexWireChars = this.indexWireChars(shapes);
		r.shapeKeys = shapes.index().geoms().size();
		r.shapeKeysByKind.clear();
		shapes.keysByKind().forEach((kind, count) -> r.shapeKeysByKind.put(kind.id(), count));
		r.shapeInstances = shapes.index().size();
		r.shapedBlocks = shapes.shapedBlocks();
		r.shapePlanMs = shapes.planMs();
		r.shapeDecorSkipped = shapes.decorSkipped();
		r.closedShapeCells = shapes.closedColumns().size();
		r.voidHullCells = 0;
		r.voidRingUnits = 0;
		for (ShapePlan.VoidUnit unit : shapes.voidUnits().values()) {
			// Beyond the ring the void fill never holds a unit (the e2e gate keeps this at 0).
			if (unit.distance() > shapes.params().hullRing()) {
				r.voidHullCells++;
			} else {
				r.voidRingUnits++;
			}
		}
		r.packMs = this.prepared.packMs();
		ShapeReport report = this.prepared.shapes();
		if (report != null) {
			r.shapeTableMs = report.tableMs();
			QuadCounter.Stats quads = report.quads();
			r.shapeQuadsTotal = quads.shapeQuads();
			r.shapeQuadsMeanBlock = quads.meanBlock();
			r.shapeQuadsMaxBlock = quads.maxBlock();
			r.shapeQuadsP99Block = quads.p99Block();
			r.shapeQuadsSectionP50 = quads.sectionP50();
			r.shapeQuadsSectionP95 = quads.sectionP95();
			r.shapeQuadsSectionMax = quads.sectionMax();
			r.densestSection = quads.densest();
			r.quadCountMs = report.quadMs();
			r.structuresByKind = new java.util.LinkedHashMap<>(report.structures());
			r.structures = report.structures().values().stream().mapToLong(Long::longValue).sum();
			r.shapeTemplates = report.shapeTemplates();
			CharacterReport characters = this.prepared.characters();
			if (characters != null) {
				r.spritesByFrames = new java.util.LinkedHashMap<>(characters.spritesByFrames());
				r.characterFallbacks = new java.util.LinkedHashMap<>(characters.fallbacks());
			}
			r.selfCheck = new BuildReport.SelfCheckSummary(report.scores().size(), report.holes(), report.groundMatchMin(), report.structureMatchMin(),
					report.structureMatchP05(), report.occludedGroundMax(), report.backFaces(), report.unitsFailed(), report.templatesFailed(),
					report.checkMs());
		}
	}

	private void writeReport() {
		Path report = this.worldDir.resolve(DATA_FOLDER).resolve(BuildReport.FILE);
		WorldPlan plan = this.prepared != null ? this.prepared.plan() : null;
		if (plan != null) {
			// The symbol lists behind the warning counts: beside each copy of the report, never in the log.
			try {
				BuildDiagnostics.writeBeside(report, plan.warnings(), plan.mapsUnplaced());
				if (this.options.reportCopy() != null) {
					BuildDiagnostics.writeBeside(this.options.reportCopy(), plan.warnings(), plan.mapsUnplaced());
				}
				this.report.diagnostics = true;
			} catch (IOException e) {
				KantoBridge.LOGGER.error("builder: could not write the build diagnostics ({})", e.getClass().getSimpleName());
			}
		}
		try {
			this.report.writeTo(report);
			if (this.options.reportCopy() != null) {
				this.report.writeTo(this.options.reportCopy());
			}
		} catch (IOException e) {
			KantoBridge.LOGGER.error("builder: could not write the build report ({})", e.getClass().getSimpleName());
		}
		ShapeReport shapes = this.prepared != null ? this.prepared.shapes() : null;
		if (shapes != null) {
			// Scores per map id, numbers only: beside each copy of the report.
			try {
				Files.writeString(report.resolveSibling(SELFCHECK_FILE), shapes.toText(), StandardCharsets.UTF_8);
				if (this.options.reportCopy() != null) {
					Path copy = this.options.reportCopy().toAbsolutePath();
					Files.writeString(copy.resolveSibling(SELFCHECK_FILE), shapes.toText(), StandardCharsets.UTF_8);
				}
			} catch (IOException e) {
				KantoBridge.LOGGER.error("builder: could not write the self-check ({})", e.getClass().getSimpleName());
			}
		}
	}
}
