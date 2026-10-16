package dev.kantobridge.client.story;

import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * What a tile story cell's block draws while its player sees the cell closed (docs/PLAN.md §4.3 {@code StoryCellRenderer}):
 * the closed art of its own quarter of the cell (one 8×8 tile per block, as the world's tile blocks show it) as a full
 * tile block repeated up the closed column, or flat on the floor when the closed state is walkable art. SE3: a Gen 3
 * {@code cell} cell draws the same way the state its player's hidden game shows ({@link #piece(WorldIndex,
 * StoryCellTable.Entry, int, int, int)}). Plain Java.
 */
public final class StoryCellArt {
	/**
	 * One block's closed art.
	 *
	 * @param slot   the full-shape slot index (family {@code slot / 256}, state {@code slot % 256})
	 * @param height blocks drawn from the cell's feet level upwards; 0 draws a thin layer on the floor
	 */
	public record Piece(int slot, int height) {
		public int family() {
			return this.slot / SlotMap.SLOTS_PER_FAMILY;
		}

		public int state() {
			return this.slot % SlotMap.SLOTS_PER_FAMILY;
		}
	}

	private StoryCellArt() {
	}

	/**
	 * The art of the story-cell block at block column ({@code x}, {@code z}) of {@code entry}'s cell, or null when the
	 * entry has no closed art (a WorldIndex from before M8), its map isn't placed, or the column isn't in the cell.
	 */
	public static @Nullable Piece piece(WorldIndex index, StoryCellTable.Entry entry, int x, int z) {
		StoryCellTable.Closed closed = entry.closed();
		BlockPos origin = index.cellOrigin(entry.cell().map(), entry.cell().x(), entry.cell().y());
		if (closed == null || origin == null) {
			return null;
		}
		int dx = x - origin.getX();
		int dz = z - origin.getZ();
		if (dx < 0 || dz < 0 || dx >= Scale.CELL_BLOCKS || dz >= Scale.CELL_BLOCKS) {
			return null;
		}
		return new Piece(closed.art(dx, dz), closed.height());
	}

	/**
	 * SE3: the art of the story-cell block at block column ({@code x}, {@code z}) of a {@code cell} entry's cell in its
	 * state {@code state} (an index into the entry's {@code states}), or null when the state is none of them (the built
	 * state shows), the map isn't placed, or the column isn't in the cell.
	 */
	public static @Nullable Piece piece(WorldIndex index, StoryCellTable.Entry entry, int state, int x, int z) {
		BlockPos origin = index.cellOrigin(entry.cell().map(), entry.cell().x(), entry.cell().y());
		if (state < 0 || state >= entry.states().size() || origin == null) {
			return null;
		}
		int dx = x - origin.getX();
		int dz = z - origin.getZ();
		if (dx < 0 || dz < 0 || dx >= Scale.CELL_BLOCKS || dz >= Scale.CELL_BLOCKS) {
			return null;
		}
		StoryCellTable.CellState shown = entry.states().get(state);
		return new Piece(shown.art(dx, dz), shown.height());
	}
}
