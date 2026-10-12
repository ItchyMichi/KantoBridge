package dev.kantobridge.client.move;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * One physical cell of the built world: the block of its north-west corner at its level's feet (a map's feet level; for
 * a map with a layout, its piece's, without the cell's own raise, so walking up a ramp stays on one level:
 * docs/specs/CAVES_3D_PLAN.md §3.1). The outdoor maps share one cell grid (PLAN §2.2), so spots compare across map
 * seams, where the hidden game may still name a cell by the map it is leaving ({@code y = -1}); interiors and pieces are
 * told apart by their level.
 */
public record Spot(int x, int y, int z) {
	/** A map's cell (which may lie just outside the map), or null for a map this world doesn't have. */
	public static @Nullable Spot of(WorldIndex index, String map, int cellX, int cellY) {
		BlockPos origin = index.levelOrigin(map, cellX, cellY);
		return origin == null ? null : new Spot(origin.getX(), origin.getY(), origin.getZ());
	}

	public static @Nullable Spot of(WorldIndex index, Place place) {
		return of(index, place.map(), place.x(), place.y());
	}

	/** The cell under a feet position, or null when it is on no map (gaps, void fill, between layers). */
	public static @Nullable Spot at(WorldIndex index, double x, double y, double z) {
		CellPos cell = index.toCell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).orElse(null);
		return cell == null ? null : of(index, cell.map(), cell.x(), cell.y());
	}

	/** The map cell this spot is, or null off every map. */
	public @Nullable CellPos cell(WorldIndex index) {
		return index.toCell(this.x, this.y, this.z).orElse(null);
	}

	/** The block X of the cell's centre (the middle of its 2×2 footprint). */
	public double centreX() {
		return this.x + Scale.CELL_BLOCKS / 2.0;
	}

	public double centreZ() {
		return this.z + Scale.CELL_BLOCKS / 2.0;
	}

	/** The spot {@code cellsX, cellsY} cells away on the same level. */
	public Spot offset(int cellsX, int cellsY) {
		return new Spot(this.x + cellsX * Scale.CELL_BLOCKS, this.y, this.z + cellsY * Scale.CELL_BLOCKS);
	}

	/** Cells between the two, counted along the axes (Gen 1 steps); only meaningful on one level. */
	public int cellsTo(Spot other) {
		return (Math.abs(other.x - this.x) + Math.abs(other.z - this.z) + Scale.CELL_BLOCKS - 1) / Scale.CELL_BLOCKS;
	}

	public boolean sameLevel(Spot other) {
		return this.y == other.y;
	}
}
