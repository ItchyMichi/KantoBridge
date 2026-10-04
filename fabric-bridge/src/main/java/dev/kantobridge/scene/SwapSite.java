package dev.kantobridge.scene;

import java.util.List;
import java.util.Objects;

/**
 * A place whose cells change during play (a cut tree, a card-key door, a scripted swap; docs/specs/VOXEL_BLOCKS_PLAN.md
 * §5.3): per cell its open state (what the world is built with) and its closed state (what a player still sees while
 * their hidden game has it closed).
 *
 * @param id a stable id within the map, e.g. {@code TEST_TOWN:cut:10:14} (the first changing cell)
 */
public record SwapSite(String id, String kind, List<SwapCell> cells) {
	public SwapSite {
		Objects.requireNonNull(id, "id");
		cells = List.copyOf(cells);
	}

	/** One state of a cell: its kind, tags and art tiles (the map's {@code perCell × perCell}, row-major). */
	public record CellState(CellKind kind, int tags, int[] art) {
		public CellState {
			Objects.requireNonNull(kind, "kind");
			if (art.length == 0) {
				throw new IllegalArgumentException("a cell has art tiles");
			}
			art = art.clone();
		}
	}

	/** One changing cell. */
	public record SwapCell(int x, int y, CellState closed, CellState open) {
	}
}
