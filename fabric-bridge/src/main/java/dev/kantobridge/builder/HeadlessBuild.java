package dev.kantobridge.builder;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.server.BuildHold;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * The build triggers that run when a server has started (docs/PLAN.md §4.5 "Running the builder"):
 *
 * <ul>
 * <li><b>In-process request</b> ({@link BuildRequest}): the client's "Create Kanto world" flow files one before it
 * creates the level; the integrated server of that level takes it and builds every requested map, holding joining
 * players until the world is ready ({@link BuildHold}).</li>
 * <li><b>Headless</b>: with {@code -Dkantobridge.build=<bundle dir>} (the {@code <game>} folder holding
 * {@code index.json}) the server builds its overworld:
 * <ul>
 * <li>{@code -Dkantobridge.build.maps=A,B,...}: only these maps (default: every map);</li>
 * <li>{@code -Dkantobridge.build.report=<path>}: also copy {@code build_report.json} there;</li>
 * <li>{@code -Dkantobridge.build.exit=true}: stop the server when the build ends; the process exits 0 when it
 * succeeded and 1 when it failed (the report's {@code status} says the same);</li>
 * <li>{@code -Dkantobridge.build.region=<id> -Dkantobridge.build.slot=<n>} (both or neither): a region build
 * (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3) of region {@code <id>} at slot {@code <n>} of the region table; without
 * them a legacy build. A malformed pair ends in a failed report ({@code build.region_option}).</li>
 * </ul>
 * Gradle passes them from {@code ./gradlew runServer -Pkb.build=... -Pkb.buildMaps=... -Pkb.buildReport=... -Pkb.buildExit=true
 * -Pkb.buildRegion=... -Pkb.buildSlot=...}.</li>
 * </ul>
 *
 * Both open the bundle and start the builder through {@link #start}.
 */
public final class HeadlessBuild {
	public static final String PROPERTY = "kantobridge.build";
	public static final String PROPERTY_MAPS = "kantobridge.build.maps";
	public static final String PROPERTY_REPORT = "kantobridge.build.report";
	public static final String PROPERTY_EXIT = "kantobridge.build.exit";
	/** MG3: a region build's region id and slot. */
	public static final String PROPERTY_REGION = "kantobridge.build.region";
	public static final String PROPERTY_SLOT = "kantobridge.build.slot";
	/** WORLD_SESSIONS_PLAN WS6: {@code game=bundleDir,...} (slot order) and the start region: one build of several regions. */
	public static final String PROPERTY_GAMES = "kantobridge.build.games";
	public static final String PROPERTY_START = "kantobridge.build.start";
	public static final int EXIT_OK = 0;
	public static final int EXIT_FAILED = 1;

	private static volatile int exitCode = -1;

	private HeadlessBuild() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(HeadlessBuild::onStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(HeadlessBuild::onStopped);
	}

	private static void onStarted(MinecraftServer server) {
		if (!server.isDedicatedServer() && System.getProperty("fabric-api.gametest") == null) {
			BuildRequest request = BuildRequest.take(levelId(server));
			if (request != null) {
				try {
					startRequested(server, request);
				} catch (RuntimeException e) {
					// The flow is waiting on this request: fail it rather than the server.
					KantoBridge.LOGGER.error("builder: in-process build could not start ({})", e.getClass().getSimpleName(), e);
					request.status().failed("internal." + e.getClass().getSimpleName());
				}
				return;
			}
		}
		String games = System.getProperty(PROPERTY_GAMES);
		if (games != null && !games.isBlank() && System.getProperty("fabric-api.gametest") == null) {
			startGames(server, games, System.getProperty(PROPERTY_START));
			return;
		}
		String bundleDir = System.getProperty(PROPERTY);
		if (bundleDir == null || bundleDir.isBlank()) {
			return;
		}
		if (System.getProperty("fabric-api.gametest") != null) {
			KantoBridge.LOGGER.warn("builder: ignoring -D{} in a GameTest run", PROPERTY);
			return;
		}
		List<String> maps = BuildOptions.parseMaps(System.getProperty(PROPERTY_MAPS));
		String reportPath = System.getProperty(PROPERTY_REPORT);
		boolean exit = Boolean.parseBoolean(System.getProperty(PROPERTY_EXIT, "false"));
		BuildOptions options = BuildOptions.defaults().withReportCopy(reportPath == null || reportPath.isBlank() ? null : Path.of(reportPath));
		BuildListener listener = new BuildListener() {
			@Override
			public void onFinished(BuildReport report) {
				finish(server, exit, true);
			}

			@Override
			public void onFailed(BuildReport report) {
				finish(server, exit, false);
			}
		};
		BuildOptions.RegionOption region;
		try {
			region = BuildOptions.parseRegion(System.getProperty(PROPERTY_REGION), System.getProperty(PROPERTY_SLOT));
		} catch (IllegalArgumentException e) {
			// The options were typed by whoever ran the build: the message names only what is wrong with them.
			KantoBridge.LOGGER.error("builder: bad region options ({})", e.getMessage());
			failEarly("build.region_option", maps, options, listener);
			return;
		}
		if (region != null) {
			options = options.withRegion(region.region(), region.slot());
		}
		KantoBridge.LOGGER.info("builder: headless build requested ({} maps, exit when done: {}, {})", maps == null ? "all" : maps.size(), exit,
				region == null ? "legacy build" : "region " + region.region() + " at slot " + region.slot());
		start(server, Path.of(bundleDir), maps, options, listener);
	}

	/**
	 * WS6's headless trigger ({@code tools/e2e/build_world.py --games blue,crystal --start johto}): every game a region at
	 * its slot (the catalogue's region), the world record written first, the builds one after another; the server exits
	 * when the last is done if asked.
	 */
	private static void startGames(MinecraftServer server, String games, @Nullable String start) {
		boolean exit = Boolean.parseBoolean(System.getProperty(PROPERTY_EXIT, "false"));
		String reportPath = System.getProperty(PROPERTY_REPORT);
		List<BuildRequest.Job> jobs = new java.util.ArrayList<>();
		List<dev.kantobridge.world.WorldRecord.Game> listed = new java.util.ArrayList<>();
		dev.kantobridge.world.WorldRecord record;
		try {
			int slot = 0;
			for (String pair : games.split(",")) {
				String[] parts = pair.trim().split("=", 2);
				String region = dev.kantobridge.world.Games.region(parts[0]);
				if (parts.length != 2 || region == null) {
					throw new IllegalArgumentException("not game=bundle: " + parts[0]);
				}
				BuildOptions options = BuildOptions.defaults().withRegion(region, slot++)
						.withReportCopy(reportPath == null || reportPath.isBlank() ? null : Path.of(reportPath));
				jobs.add(new BuildRequest.Job(Path.of(parts[1]), null, options));
				listed.add(new dev.kantobridge.world.WorldRecord.Game(parts[0], region));
			}
			record = new dev.kantobridge.world.WorldRecord(start == null || start.isBlank() ? listed.get(0).region() : start, listed);
		} catch (IllegalArgumentException e) {
			KantoBridge.LOGGER.error("builder: bad games options ({})", e.getMessage());
			finish(server, exit, false);
			return;
		}
		KantoBridge.LOGGER.info("builder: headless build of {} regions requested (start {})", jobs.size(), record.start());
		BuildStatus status = new BuildStatus();
		server.setDifficulty(Difficulty.PEACEFUL, true);
		server.setDifficultyLocked(true);
		runChain(server, status, jobs, record, server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER));
		boolean[] ended = {false};
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(ticking -> {
			if (ticking == server && !ended[0] && (status.state() == BuildStatus.State.DONE || status.state() == BuildStatus.State.FAILED)) {
				ended[0] = true;
				finish(server, exit, status.state() == BuildStatus.State.DONE);
			}
		});
	}

	/** A failed report before any build ran (copied if asked) and {@code onFailed}, the way an unopenable bundle ends. */
	private static void failEarly(String code, @Nullable List<String> maps, BuildOptions options, BuildListener listener) {
		BuildReport report = new BuildReport();
		report.status = "failed";
		report.error = code;
		report.mapsRequested = maps == null ? 0 : maps.size();
		writeCopy(report, options.reportCopy());
		listener.onPhase(BuildPhase.FAILED);
		listener.onFailed(report);
	}

	/** The folder name of the server's world: what {@link BuildRequest}s are matched by. */
	static String levelId(MinecraftServer server) {
		Path dir = KantoWorldBuilder.worldDir(server.overworld()).getFileName();
		return dir == null ? "" : dir.toString();
	}

	/**
	 * Runs a request from the client's flow: a Kanto world's difficulty (Peaceful, locked, PLAN Appendix D), players
	 * held until the build is done, then let in at the new spawn.
	 */
	private static void startRequested(MinecraftServer server, BuildRequest request) {
		BuildStatus status = request.status();
		KantoBridge.LOGGER.info("builder: in-process build requested ({} maps{})", request.maps() == null ? "all" : request.maps().size(),
				request.more().isEmpty() ? "" : ", " + (1 + request.more().size()) + " regions");
		server.setDifficulty(Difficulty.PEACEFUL, true);
		server.setDifficultyLocked(true);
		BuildHold.begin(server);
		List<BuildRequest.Job> jobs = new java.util.ArrayList<>();
		jobs.add(new BuildRequest.Job(request.bundleDir(), request.maps(), request.options()));
		jobs.addAll(request.more());
		runChain(server, status, jobs, request.record(), server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER));
	}

	/**
	 * WORLD_SESSIONS_PLAN WS6: a chained request's builds, one after another, the world record written into
	 * {@code dataFolder} first (so each region build knows the start region: the spawn). GameTests run it with a world
	 * folder of their own.
	 */
	public static void runChain(MinecraftServer server, BuildStatus status, List<BuildRequest.Job> jobs,
			dev.kantobridge.world.@Nullable WorldRecord record, Path dataFolder) {
		if (record != null) {
			try {
				record.write(dataFolder);
				KantoBridge.LOGGER.info("builder: world record written ({} games, start {})", record.games().size(), record.start());
			} catch (IOException e) {
				KantoBridge.LOGGER.warn("builder: the world record couldn't be written ({})", e.getClass().getSimpleName());
			}
		}
		runJob(server, status, jobs, 0);
	}

	/** WS6: one region build of the chain; the next starts when it finishes, the hold lifts after the last. */
	private static void runJob(MinecraftServer server, BuildStatus status, List<BuildRequest.Job> jobs, int index) {
		BuildRequest.Job job = jobs.get(index);
		status.region(index, jobs.size());
		BuildListener listener = new BuildListener() {
			@Override
			public void onPhase(BuildPhase phase) {
				status.onPhase(phase);
			}

			@Override
			public void onProgress(int doneChunks, int totalChunks) {
				status.onProgress(doneChunks, totalChunks);
			}

			@Override
			public void onFinished(BuildReport report) {
				if (index + 1 < jobs.size() && !status.cancelRequested()) {
					KantoBridge.LOGGER.info("builder: region {} of {} built; the next one", index + 1, jobs.size());
					runJob(server, status, jobs, index + 1);
					return;
				}
				status.onFinished(report);
				BuildHold.release(server);
				status.done();
			}

			@Override
			public void onFailed(BuildReport report) {
				// Players stay held: the client leaves and removes the unfinished world.
				status.onFailed(report);
			}
		};
		BuildTask task = job.bundle() != null ? KantoWorldBuilder.start(server.overworld(), job.bundle(), job.maps(), job.options(), listener)
				: start(server, job.bundleDir(), job.maps(), job.options(), listener);
		if (task != null) {
			status.started(task);
		}
	}

	/**
	 * Opens the bundle and starts the build in the server's overworld; the one code path of both triggers. A bundle
	 * that can't be opened ends here: a failed report (copied if asked) and {@code onFailed}, and null is returned.
	 */
	static @Nullable BuildTask start(MinecraftServer server, Path bundleDir, @Nullable List<String> maps, BuildOptions options,
			BuildListener listener) {
		ExportBundle bundle;
		try {
			bundle = ExportBundle.open(bundleDir);
		} catch (BundleException | RuntimeException e) {
			String code = e instanceof BundleException bundleError ? bundleError.code() : "internal." + e.getClass().getSimpleName();
			KantoBridge.LOGGER.error("builder: cannot open the export bundle ({})", code);
			failEarly(code, maps, options, listener);
			return null;
		}
		return KantoWorldBuilder.start(server.overworld(), bundle, maps, options, listener);
	}

	private static void writeCopy(BuildReport report, @Nullable Path path) {
		if (path == null) {
			return;
		}
		try {
			report.writeTo(path);
		} catch (IOException e) {
			KantoBridge.LOGGER.error("builder: could not write the build report copy ({})", e.getClass().getSimpleName());
		}
	}

	private static void finish(MinecraftServer server, boolean exit, boolean ok) {
		if (!exit) {
			return;
		}
		exitCode = ok ? EXIT_OK : EXIT_FAILED;
		KantoBridge.LOGGER.info("builder: stopping the server (exit code {})", exitCode);
		server.halt(false);
	}

	private static void onStopped(MinecraftServer server) {
		int code = exitCode;
		if (code < 0) {
			return;
		}
		// SERVER_STOPPED runs on the server thread after the world is saved. Exit from another thread once that thread
		// has ended: the dedicated server's shutdown hook joins it, so exiting from it directly would deadlock.
		Thread serverThread = Thread.currentThread();
		Thread exiter = new Thread(() -> {
			try {
				serverThread.join(60_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			System.exit(code);
		}, "kantobridge-exit");
		exiter.setDaemon(false);
		exiter.start();
	}
}
