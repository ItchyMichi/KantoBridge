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
 * succeeded and 1 when it failed (the report's {@code status} says the same).</li>
 * </ul>
 * Gradle passes them from {@code ./gradlew runServer -Pkb.build=... -Pkb.buildMaps=... -Pkb.buildReport=... -Pkb.buildExit=true}.</li>
 * </ul>
 *
 * Both open the bundle and start the builder through {@link #start}.
 */
public final class HeadlessBuild {
	public static final String PROPERTY = "kantobridge.build";
	public static final String PROPERTY_MAPS = "kantobridge.build.maps";
	public static final String PROPERTY_REPORT = "kantobridge.build.report";
	public static final String PROPERTY_EXIT = "kantobridge.build.exit";
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
		KantoBridge.LOGGER.info("builder: headless build requested ({} maps, exit when done: {})", maps == null ? "all" : maps.size(), exit);
		start(server, Path.of(bundleDir), maps, options, new BuildListener() {
			@Override
			public void onFinished(BuildReport report) {
				finish(server, exit, true);
			}

			@Override
			public void onFailed(BuildReport report) {
				finish(server, exit, false);
			}
		});
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
		KantoBridge.LOGGER.info("builder: in-process build requested ({} maps)", request.maps() == null ? "all" : request.maps().size());
		server.setDifficulty(Difficulty.PEACEFUL, true);
		server.setDifficultyLocked(true);
		BuildHold.begin(server);
		BuildTask task = start(server, request.bundleDir(), request.maps(), request.options(), new BuildListener() {
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
				status.onFinished(report);
				BuildHold.release(server);
				status.done();
			}

			@Override
			public void onFailed(BuildReport report) {
				// Players stay held: the client leaves and removes the unfinished world.
				status.onFailed(report);
			}
		});
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
			BuildReport report = new BuildReport();
			report.status = "failed";
			report.error = code;
			report.mapsRequested = maps == null ? 0 : maps.size();
			writeCopy(report, options.reportCopy());
			listener.onPhase(BuildPhase.FAILED);
			listener.onFailed(report);
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
