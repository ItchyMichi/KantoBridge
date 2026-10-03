package dev.kantobridge.world;

/**
 * World or export data that doesn't have the expected shape. The message names only the JSON path and what was
 * wrong with it, never the value, so it is safe to log even for ROM-derived data (docs/PLAN.md §0.3).
 */
public class DataException extends Exception {
	private final String path;
	private final String problem;

	public DataException(String path, String problem) {
		super(path + ": " + problem);
		this.path = path;
		this.problem = problem;
	}

	public String path() {
		return this.path;
	}

	public String problem() {
		return this.problem;
	}
}
