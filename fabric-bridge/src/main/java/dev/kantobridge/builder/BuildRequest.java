package dev.kantobridge.builder;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * A build that the client's "Create Kanto world" flow (docs/PLAN.md §4.3 {@code world/}) hands to the integrated
 * server: filed just before the level is created, and taken once, on {@code SERVER_STARTED}, by the server of that
 * level only ({@link HeadlessBuild} runs it through the same code path as the headless trigger). The integrated server
 * lives in the client's JVM, so one static slot is the hand-off; a dedicated server never takes it.
 *
 * <p>A request names its level by folder ({@code saves/<levelId>}), which the flow picked as a free name, so it can't
 * land in another world. An unclaimed request goes stale after {@link #TTL_MS}.
 */
public final class BuildRequest {
	/** Creating a level and starting its server takes seconds; a request older than this belongs to no one. */
	public static final long TTL_MS = 5 * 60_000L;

	private static final AtomicReference<BuildRequest> PENDING = new AtomicReference<>();
	private static volatile LongSupplier clock = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

	private final String levelId;
	private final Path bundleDir;
	private final @Nullable List<String> maps;
	private final BuildOptions options;
	private final long filedMs;
	private final BuildStatus status = new BuildStatus();
	/** WORLD_SESSIONS_PLAN WS6: the regions built after the first one, in order (empty: one build). */
	private List<Job> more = List.of();
	/** WS6: the world record the chain writes before its first build (null: none). */
	private dev.kantobridge.world.@Nullable WorldRecord record;

	/**
	 * WS6: one region build of a chained request: its bundle (a folder, or one already open: GameTests' classpath bundle),
	 * maps (null: all) and options (a region and its slot).
	 */
	public record Job(@Nullable Path bundleDir, dev.kantobridge.builder.export.@Nullable ExportBundle bundle, @Nullable List<String> maps,
			BuildOptions options) {
		public Job(Path bundleDir, @Nullable List<String> maps, BuildOptions options) {
			this(bundleDir, null, maps, options);
		}

		public Job(dev.kantobridge.builder.export.ExportBundle bundle, @Nullable List<String> maps, BuildOptions options) {
			this(null, bundle, maps, options);
		}
	}

	private BuildRequest(String levelId, Path bundleDir, @Nullable List<String> maps, BuildOptions options, long filedMs) {
		this.levelId = levelId;
		this.bundleDir = bundleDir;
		this.maps = maps == null ? null : List.copyOf(maps);
		this.options = options;
		this.filedMs = filedMs;
	}

	/**
	 * Files a request for the level in folder {@code levelId}, replacing any earlier one that was never taken.
	 *
	 * @param bundleDir the export bundle (the {@code <game>} folder holding {@code index.json})
	 * @param maps      map ids to build; null builds every map
	 */
	public static BuildRequest submit(String levelId, Path bundleDir, @Nullable List<String> maps, BuildOptions options) {
		if (Objects.requireNonNull(levelId).isEmpty()) {
			throw new IllegalArgumentException("empty level id");
		}
		BuildRequest request = new BuildRequest(levelId, Objects.requireNonNull(bundleDir), maps, Objects.requireNonNull(options),
				clock.getAsLong());
		PENDING.set(request);
		return request;
	}

	/**
	 * WORLD_SESSIONS_PLAN WS6 ("Create world"): one request for several regions, built one after another into the new
	 * level, the world record written first (its start region sets the world spawn).
	 */
	public static BuildRequest submitAll(String levelId, List<Job> jobs, dev.kantobridge.world.WorldRecord record) {
		if (jobs.isEmpty()) {
			throw new IllegalArgumentException("no region to build");
		}
		Job first = jobs.get(0);
		BuildRequest request = new BuildRequest(Objects.requireNonNull(levelId), first.bundleDir(), first.maps(), first.options(),
				clock.getAsLong());
		request.more = List.copyOf(jobs.subList(1, jobs.size()));
		request.record = Objects.requireNonNull(record);
		PENDING.set(request);
		return request;
	}

	/** WS6: the regions to build after the first one. */
	public List<Job> more() {
		return this.more;
	}

	/** WS6: the world record to write before the first build, or null. */
	public dev.kantobridge.world.@Nullable WorldRecord record() {
		return this.record;
	}

	/**
	 * Takes the pending request if it is for {@code levelId} and still fresh; a request for another level stays
	 * pending (until it goes stale).
	 */
	public static @Nullable BuildRequest take(String levelId) {
		while (true) {
			BuildRequest pending = PENDING.get();
			if (pending == null) {
				return null;
			}
			if (clock.getAsLong() - pending.filedMs > TTL_MS) {
				PENDING.compareAndSet(pending, null);
				continue;
			}
			if (!pending.levelId.equals(levelId)) {
				return null;
			}
			if (PENDING.compareAndSet(pending, null)) {
				return pending;
			}
		}
	}

	/** Takes this request back if no server has taken it (the level couldn't be created). Returns whether it did. */
	public boolean withdraw() {
		return PENDING.compareAndSet(this, null);
	}

	/** The request waiting to be taken, if any. */
	public static @Nullable BuildRequest pending() {
		return PENDING.get();
	}

	/** For tests: a fake millisecond clock (null restores the real one); also drops any pending request. */
	static void useClock(@Nullable LongSupplier testClock) {
		clock = testClock != null ? testClock : () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
		PENDING.set(null);
	}

	public String levelId() {
		return this.levelId;
	}

	public Path bundleDir() {
		return this.bundleDir;
	}

	public @Nullable List<String> maps() {
		return this.maps;
	}

	public BuildOptions options() {
		return this.options;
	}

	public BuildStatus status() {
		return this.status;
	}
}
