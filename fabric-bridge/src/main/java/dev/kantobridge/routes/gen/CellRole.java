package dev.kantobridge.routes.gen;

/**
 * What a cell of a zone is, before a theme decides how it looks: the forest draws {@link #WALL} as trees, a cave would
 * draw it as rock. The symbols are for logs, tests and fingerprints.
 */
public enum CellRole {
	/** Blocks the way: the maze's walls (trees in the forest). */
	WALL('T'),
	GROUND('.'),
	/** Tall grass: wild Pokémon appear here. */
	GRASS('"'),
	PATH(','),
	/** A boulder-sized obstacle inside a clearing. */
	ROCK('o'),
	WATER('~'),
	FLOWER('*'),
	/** A dungeon's entrance: you step into it. */
	DUNGEON('D'),
	/** The land around and between the areas: not part of the maze, never walked. */
	SCENERY(' ');

	public final char symbol;

	CellRole(char symbol) {
		this.symbol = symbol;
	}

	/** Cells you walk on. A dungeon's entrance isn't one: stepping into it starts a visit. */
	public boolean walkable() {
		return this == GROUND || this == GRASS || this == PATH || this == FLOWER;
	}
}
