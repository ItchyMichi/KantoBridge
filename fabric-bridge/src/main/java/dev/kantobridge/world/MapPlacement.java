package dev.kantobridge.world;

import java.util.Locale;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Where one map sits in the Minecraft world (WorldIndex {@code maps}).
 *
 * @param x       block X of the north-west corner of the map's cell (0, 0)
 * @param y       feet level (block Y) of the map's walking surface
 * @param z       block Z of the north-west corner of the map's cell (0, 0)
 * @param w       width in cells
 * @param h       height in cells
 * @param layer   0 for the surface and route maps, {@code k ≥ 1} for interior layers
 * @param palette the SGB palette name (interiors take their outdoor parent's)
 * @param biome   the biome id written over the map's volume
 * @param dark    a dark map (no lights; Flash gives Night Vision)
 * @param parent  interiors: the map whose door leads here; null for surface and route maps
 */
public record MapPlacement(String id, int x, int y, int z, int w, int h, int layer, Kind kind, String palette, String biome,
		boolean dark, @Nullable String parent) {
	public enum Kind {
		SURFACE,
		INTERIOR,
		/**
		 * A Wild Routes zone's route map (docs/specs/WILD_ROUTES_PLAN.md §4.2, §4.4): a small map of the hidden game's
		 * own that no export draws, placed at the zone's trailhead so its cells A and x1 are the trailhead's.
		 */
		ROUTE;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable Kind byId(String id) {
			for (Kind kind : values()) {
				if (kind.id().equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}

	public MapPlacement {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(palette, "palette");
		Objects.requireNonNull(biome, "biome");
		if (w < 1 || h < 1) {
			throw new IllegalArgumentException("map size must be positive");
		}
		if (layer < 0) {
			throw new IllegalArgumentException("negative layer");
		}
	}

	/** Width in blocks. */
	public int blockWidth() {
		return this.w * Scale.CELL_BLOCKS;
	}

	/** Depth (Z extent) in blocks. */
	public int blockDepth() {
		return this.h * Scale.CELL_BLOCKS;
	}

	/** True when block column (bx, bz) lies on one of this map's cells. */
	public boolean containsColumn(int bx, int bz) {
		return bx >= this.x && bx < this.x + this.blockWidth() && bz >= this.z && bz < this.z + this.blockDepth();
	}

	public boolean containsCell(int cx, int cy) {
		return cx >= 0 && cx < this.w && cy >= 0 && cy < this.h;
	}
}
