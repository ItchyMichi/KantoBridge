package dev.kantobridge.builder;

/** The phases of a build, in order. */
public enum BuildPhase {
	/** Planning and writing the resource pack, off the server thread. */
	PLANNING,
	/** Placing blocks and biomes, a few milliseconds per tick. */
	PLACING,
	/** Writing the WorldIndex, slot map, datapack and report. */
	FINISHING,
	DONE,
	FAILED;

	public boolean finished() {
		return this == DONE || this == FAILED;
	}
}
