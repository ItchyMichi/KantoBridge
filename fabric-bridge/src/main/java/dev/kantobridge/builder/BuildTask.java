package dev.kantobridge.builder;

import com.cobblemon.mod.common.api.habitats.HabitatPool;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.builder.pack.DatapackWriter;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.place.BlockPlacer;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.PlanException;
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
import dev.kantobridge.scene.gen1.BundleArtSource;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.scene.profile.ProfileSource;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
			List<WorldIndex.Habitat> previousHabitats, long planMs, @Nullable ShapeReport shapes, long packMs, long zoneMs) {
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
			WorldPlan planned = WorldPlanner.plan(this.bundle, new WorldPlanner.Options(this.maps, this.options.originX(), this.options.originZ(),
					this.minBuildHeight, this.options.shapes()));
			// Wild Routes (WR3, docs/specs/WILD_ROUTES_PLAN.md §4.4): the zones the export lists, each in its slot on the
			// vanilla overworld's land for this world's seed. A zone whose town isn't in this build is left out.
			long zoneStarted = System.nanoTime();
			ExportRoutes routes = this.bundle.routes();
			List<String> towns = routes.zones().stream().map(ExportRoutes.Zone::townMap).toList();
			WorldPlan plan = withZones(planned, routes, this.level.getSeed(),
					() -> VanillaTerrain.of(this.level.getServer().registryAccess(), this.level.getSeed()), this.options.originX(),
					this.options.originZ(), routes.zones().isEmpty() ? ZoneContent.EMPTY : ZoneContent.of(this.bundle, towns));
			long zoneMs = (System.nanoTime() - zoneStarted) / 1_000_000L;
			long packStarted = System.nanoTime();
			ResourcePackWriter.Result pack = this.options.writePack() ? ResourcePackWriter.write(plan, this.bundle, this.packDir, plan.warnings())
					: null;
			long packMs = (System.nanoTime() - packStarted) / 1_000_000L;
			WorldIndex previous = previousIndex(this.worldDir);
			// The geometry a client would build from the shape index, the self-check of every placed map (recorded) and the
			// quads the planned world draws.
			ShapeReport shapes = plan.shapes() == null || plan.scene() == null ? null : ShapeReport.of(plan, BundleArtSource.of(this.bundle));
			// The character styles' pure parts on the export's sprites (the voxel plan's V8): what each client would fall back to.
			CharacterReport characters = plan.shapes() == null ? null : CharacterReport.of(this.bundle);
			return new Prepared(plan, pack, characters, previous == null ? List.of() : WorldPlan.volumesOf(previous),
					previous == null ? List.of() : previous.habitats(), (System.nanoTime() - t0) / 1_000_000L, shapes, packMs, zoneMs);
		} catch (BundleException e) {
			throw new Failure(e.code(), e);
		} catch (PlanException e) {
			throw new Failure(e.code(), e);
		} catch (IOException e) {
			throw new Failure("io.pack", e);
		}
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
		List<ZonePlan> zones = ZonePlanner.plan(new ExportRoutes(kept), seed, regions, terrain.get(),
				town -> plan.index().map(town).palette(), content);
		return plan.withZones(zones);
	}

	/** The volumes of the build this world had before, so this one clears them. */
	static List<Box3> previousVolumes(Path worldDir) {
		WorldIndex previous = previousIndex(worldDir);
		return previous == null ? List.of() : WorldPlan.volumesOf(previous);
	}

	/** The index of the build this world had before (its volumes and habitat blocks are cleared), or null. */
	static @Nullable WorldIndex previousIndex(Path worldDir) {
		Path file = worldDir.resolve(DATA_FOLDER).resolve(INDEX_FILE);
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
		if (spawn != null && this.options.setWorldSpawn()) {
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
		try {
			Path data = this.worldDir.resolve(DATA_FOLDER);
			Files.createDirectories(data);
			Files.writeString(data.resolve(SLOT_MAP_FILE), plan.slotMap().toJson(), StandardCharsets.UTF_8);
			Files.writeString(data.resolve(INDEX_FILE), plan.index().toJson(), StandardCharsets.UTF_8);
			ShapePlan shapes = plan.shapes();
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
		} catch (IOException e) {
			this.fail(new Failure("io.world", e));
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
			WorldIndexSync.publish(this.level.getServer(), plan.index(), plan.slotMap(), shapes == null ? null : shapes.index().toJson(),
					shapes == null ? null : shapes.map().encode(), true);
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
			r.mapsRequested = this.maps == null ? this.bundle.ids("map").size() : this.maps.size();
			return;
		}
		WorldPlan plan = this.prepared.plan();
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
