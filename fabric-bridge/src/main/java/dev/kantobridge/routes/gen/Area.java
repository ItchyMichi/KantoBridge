package dev.kantobridge.routes.gen;

import java.util.EnumMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One area in its own frame (docs/specs/WILD_ROUTES_PLAN.md §4.3): {@link AreaGenerator#SIZE} cells square, its
 * entrance on the bottom edge, its exits on the other edges. {@link RouteGenerator} turns it into place.
 */
final class Area {
	/** A trainer standing on {@code cell}, looking {@code facing} along {@code sight} clear cells. */
	record AreaTrainer(Cell cell, Dir facing, int sight) {
	}

	/** {@code [y][x]}. */
	final CellRole[][] cells;
	/** The block maze: exit masks, row-major. */
	final int[] blocks;
	final int entry;
	/** Each exit's side and block. */
	final EnumMap<Dir, Integer> exits;
	/** The entrance's two cells, on the bottom edge. */
	final List<Cell> start;
	/** Each exit's two cells. */
	final EnumMap<Dir, List<Cell>> openings;
	final List<Cell> items;
	final List<AreaTrainer> trainers;
	final @Nullable Cell dungeon;
	final int openBlocks;
	/** Dead-end blocks, not counting one the dungeon took. */
	final int deadEnds;
	/** Blocks with three or four ways out. */
	final int junctions;

	Area(CellRole[][] cells, int[] blocks, int entry, EnumMap<Dir, Integer> exits, List<Cell> start,
			EnumMap<Dir, List<Cell>> openings, List<Cell> items, List<AreaTrainer> trainers, @Nullable Cell dungeon,
			int openBlocks, int deadEnds, int junctions) {
		this.cells = cells;
		this.blocks = blocks;
		this.entry = entry;
		this.exits = exits;
		this.start = List.copyOf(start);
		this.openings = openings;
		this.items = List.copyOf(items);
		this.trainers = List.copyOf(trainers);
		this.dungeon = dungeon;
		this.openBlocks = openBlocks;
		this.deadEnds = deadEnds;
		this.junctions = junctions;
	}
}
