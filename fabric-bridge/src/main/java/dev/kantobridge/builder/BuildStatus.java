package dev.kantobridge.builder;

import org.jetbrains.annotations.Nullable;

/**
 * Where a requested build stands ({@link BuildRequest}), for whoever filed it: the client's progress screen reads it
 * while the integrated server writes it. Written on the server thread, read from any thread. Counts, phases and codes
 * only.
 */
public final class BuildStatus implements BuildListener {
	/** The request's life, coarser than {@link BuildPhase}. */
	public enum State {
		/** Filed; the server hasn't started (or hasn't taken it) yet. */
		WAITING,
		/** The server took it and is planning or placing. */
		RUNNING,
		/** Built, and the waiting players were let in at the spawn. */
		DONE,
		/** Failed or cancelled; see {@link #error()}. */
		FAILED
	}

	private volatile State state = State.WAITING;
	private volatile @Nullable BuildPhase phase;
	private volatile int doneChunks;
	private volatile int totalChunks;
	private volatile @Nullable String error;
	private volatile int mapsBuilt;
	private volatile int warnings;
	private volatile @Nullable BuildTask task;
	private volatile boolean cancelRequested;
	/** WS6: which region of a chained request is building (0-based) and how many there are. */
	private volatile int regionIndex;
	private volatile int regionCount = 1;

	/** WS6: the chain's position: region {@code index} (0-based) of {@code count}. */
	public void region(int index, int count) {
		this.regionIndex = index;
		this.regionCount = Math.max(1, count);
	}

	public int regionIndex() {
		return this.regionIndex;
	}

	public int regionCount() {
		return this.regionCount;
	}

	public State state() {
		return this.state;
	}

	/** The build's phase once it runs, else null. */
	public @Nullable BuildPhase phase() {
		return this.phase;
	}

	public int doneChunks() {
		return this.doneChunks;
	}

	public int totalChunks() {
		return this.totalChunks;
	}

	/** The failure's report code ({@code bundle.missing}, {@code cancelled}, ...), once failed. */
	public @Nullable String error() {
		return this.error;
	}

	public int mapsBuilt() {
		return this.mapsBuilt;
	}

	public int warnings() {
		return this.warnings;
	}

	/**
	 * Asks the server to stop the build; it then fails with {@code cancelled}. Safe from any thread: the cancel runs on
	 * the build's server thread.
	 */
	public void cancel() {
		this.cancelRequested = true;
		BuildTask running = this.task;
		if (running != null) {
			running.server().execute(running::cancel);
		}
	}

	public boolean cancelRequested() {
		return this.cancelRequested;
	}

	// ---- server side ----

	/** The server took the request and started this task. Server thread. */
	void started(BuildTask started) {
		this.task = started;
		this.state = State.RUNNING;
		if (this.cancelRequested) {
			started.cancel();
		}
	}

	/** The build is complete and players may enter. Server thread. */
	void done() {
		this.state = State.DONE;
	}

	/** Failed before or during the build. Server thread. */
	void failed(@Nullable String code) {
		this.error = code == null ? "failed" : code;
		this.state = State.FAILED;
	}

	@Override
	public void onPhase(BuildPhase next) {
		this.phase = next;
	}

	@Override
	public void onProgress(int done, int total) {
		this.doneChunks = done;
		this.totalChunks = total;
	}

	@Override
	public void onFinished(BuildReport report) {
		this.mapsBuilt = report.mapsBuilt;
		this.warnings = report.warnings;
		this.doneChunks = this.totalChunks;
	}

	@Override
	public void onFailed(BuildReport report) {
		this.failed(report.error);
	}
}
