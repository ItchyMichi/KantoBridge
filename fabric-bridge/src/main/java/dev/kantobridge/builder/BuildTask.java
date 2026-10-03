package dev.kantobridge.builder;

import com.cobblemon.mod.common.api.habitats.HabitatPool;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.DatapackWriter;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.place.BlockPlacer;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
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

	private record Prepared(WorldPlan plan, @Nullable ResourcePackWriter.Result pack, List<Box3> previousVolumes,
			List<WorldIndex.Habitat> previousHabitats, long planMs) {
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
			WorldPlan plan = WorldPlanner.plan(this.bundle, new WorldPlanner.Options(this.maps, this.options.originX(), this.options.originZ(),
					this.minBuildHeight));
			ResourcePackWriter.Result pack = this.options.writePack() ? ResourcePackWriter.write(plan, this.bundle, this.packDir, plan.warnings())
					: null;
			WorldIndex previous = previousIndex(this.worldDir);
			return new Prepared(plan, pack, previous == null ? List.of() : WorldPlan.volumesOf(previous),
					previous == null ? List.of() : previous.habitats(), (System.nanoTime() - t0) / 1_000_000L);
		} catch (BundleException e) {
			throw new Failure(e.code(), e);
		} catch (PlanException e) {
			throw new Failure(e.code(), e);
		} catch (IOException e) {
			throw new Failure("io.pack", e);
		}
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
			WorldIndexSync.publish(this.level.getServer(), plan.index(), true);
		}
		this.phase = BuildPhase.DONE;
		KantoBridge.LOGGER.info("builder: done: {} maps, {} story cells, {} habitat blocks, {} pools, {} blocks placed, {} cleared, {} chunks, {} textures, {} warnings, {} ms",
				this.report.mapsBuilt, this.report.storyCells, this.report.habitatBlocks, this.report.pools, this.report.blocksPlaced,
				this.report.blocksCleared, this.report.chunks, this.report.packTextures, this.report.warnings, this.report.elapsedMs);
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
		r.pools = plan.pools().size();
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
	}
}
