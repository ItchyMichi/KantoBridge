package dev.kantobridge.builder.shape;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * What the builder knows about positions that the shape planner doesn't (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4): the
 * feet-level positions its story blocks take, and the void fill of the surface. Positions only: keys never depend on
 * it (they come from the whole export), only which positions of the placed maps get them.
 *
 * @param storyCells per placed map, the cells whose feet level holds a story block
 * @param voidFill   the surface's void fill, or null when the build places no surface map
 */
public record Occupancy(Map<String, BitSet> storyCells, @Nullable VoidFill voidFill) {
	/** Nothing taken, no void fill. */
	public static final Occupancy NONE = new Occupancy(Map.of(), null);

	public Occupancy {
		Map<String, BitSet> copy = new TreeMap<>();
		storyCells.forEach((map, cells) -> copy.put(map, (BitSet) cells.clone()));
		storyCells = java.util.Collections.unmodifiableMap(copy);
	}

	/** True when the feet level of cell {@code cellIndex} ({@code cy * w + cx}) of {@code map} holds a story block. */
	public boolean storyAt(String map, int cellIndex) {
		BitSet cells = this.storyCells.get(map);
		return cells != null && cells.get(cellIndex);
	}

	/**
	 * The void fill of the surface: the surface box in cells ({@code w × d} from block {@code (minX, minZ)}), and per cell
	 * the map whose border a void cell wears (null for the cells of a map's body).
	 */
	public record VoidFill(int minX, int minZ, int w, int d, int cellBlocks, String[] voidOwner) {
		public VoidFill {
			if (w < 0 || d < 0 || cellBlocks < 1 || voidOwner.length != w * d) {
				throw new IllegalArgumentException("a void fill is w × d cells");
			}
			voidOwner = voidOwner.clone();
		}

		public @Nullable String owner(int cell) {
			return this.voidOwner[cell];
		}

		/**
		 * Per cell, its distance in cells to the nearest cell of a map body (8-connected steps: a ring is a square), 0 on a
		 * body; {@link Integer#MAX_VALUE} without any body.
		 */
		public int[] distances() {
			int[] dist = new int[this.w * this.d];
			java.util.Arrays.fill(dist, Integer.MAX_VALUE);
			int[] queue = new int[dist.length];
			int tail = 0;
			for (int i = 0; i < dist.length; i++) {
				if (this.voidOwner[i] == null) {
					dist[i] = 0;
					queue[tail++] = i;
				}
			}
			int head = 0;
			while (head < tail) {
				int i = queue[head++];
				int x = i % this.w;
				int z = i / this.w;
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int nz = z + dz;
						if (nx < 0 || nz < 0 || nx >= this.w || nz >= this.d) {
							continue;
						}
						int n = nz * this.w + nx;
						if (dist[n] == Integer.MAX_VALUE) {
							dist[n] = dist[i] + 1;
							queue[tail++] = n;
						}
					}
				}
			}
			return dist;
		}
	}
}
