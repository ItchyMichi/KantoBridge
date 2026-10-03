package dev.kantobridge.builder;

/** Hears a build's progress. Called on the server thread. */
public interface BuildListener {
	BuildListener NONE = new BuildListener() {
	};

	/** The build entered a new phase. */
	default void onPhase(BuildPhase phase) {
	}

	/** Placement progress, in chunks. */
	default void onProgress(int doneChunks, int totalChunks) {
	}

	default void onFinished(BuildReport report) {
	}

	default void onFailed(BuildReport report) {
	}
}
