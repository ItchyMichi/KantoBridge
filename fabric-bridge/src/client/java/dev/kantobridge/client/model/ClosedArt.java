package dev.kantobridge.client.model;

import dev.kantobridge.world.Scale;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The closed story art of a world's tile cells as the chunk mesh draws it (docs/specs/VOXEL_BLOCKS_PLAN.md §5.9, V5):
 * the shape map's overlays, looked up by block column without allocating (the mesh threads' path: a binary search over
 * sorted column keys), and per overlay what the world's story table says of the cell for the fallback without a shape
 * table: its closed art's slots, and whether it is flat (walkable art drawn on the floor). Immutable; built on the client
 * thread with a snapshot.
 */
public final class ClosedArt {
	private final String[] keys;
	private final Map<String, Integer> byKey;
	/** Sorted packed block columns ({@link #pack}). */
	private final long[] columnKeys;
	/** Parallel to {@link #columnKeys}: {overlay, column 0..3, feet y}. */
	private final int[][] columnInfo;
	/** [overlay][column][level]: shape ids bottom-up from feet level. */
	private final int[][][] ids;
	/** [overlay][column]: the closed art's full-shape slot, -1 when the story table doesn't say. */
	private final int[][] slots;
	private final boolean[] flat;
	/** [overlay]: x, y, z of the cell's north-west column at feet level. */
	private final int[][] origins;

	private ClosedArt(List<ShapeMap.Overlay> overlays, @Nullable WorldIndex index) {
		int n = overlays.size();
		this.keys = new String[n];
		this.byKey = new HashMap<>();
		this.ids = new int[n][][];
		this.slots = new int[n][4];
		this.flat = new boolean[n];
		this.origins = new int[n][];
		long[] packed = new long[n * 4];
		int[][] info = new int[n * 4][];
		for (int o = 0; o < n; o++) {
			ShapeMap.Overlay overlay = overlays.get(o);
			this.keys[o] = overlay.key();
			this.byKey.put(overlay.key(), o);
			this.ids[o] = overlay.columns();
			this.origins[o] = new int[] {overlay.x(), overlay.y(), overlay.z()};
			StoryCellTable.Entry entry = index == null ? null : index.storyCells().get(overlay.key());
			StoryCellTable.Closed closed = entry == null ? null : entry.closed();
			this.flat[o] = closed != null && closed.height() == 0;
			for (int c = 0; c < 4; c++) {
				int dx = c % Scale.CELL_BLOCKS;
				int dz = c / Scale.CELL_BLOCKS;
				this.slots[o][c] = closed == null ? -1 : closed.art(dx, dz);
				packed[o * 4 + c] = pack(overlay.x() + dx, overlay.z() + dz);
				info[o * 4 + c] = new int[] {o, c, overlay.y()};
			}
		}
		Integer[] order = new Integer[n * 4];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Long.compare(packed[a], packed[b]));
		this.columnKeys = new long[order.length];
		this.columnInfo = new int[order.length][];
		for (int i = 0; i < order.length; i++) {
			this.columnKeys[i] = packed[order[i]];
			this.columnInfo[i] = info[order[i]];
		}
	}

	/** The closed art of a shape map's overlays, or null when it has none (a world built before V5). */
	public static @Nullable ClosedArt of(@Nullable ShapeMap map, @Nullable WorldIndex index) {
		return map == null || map.overlays().isEmpty() ? null : new ClosedArt(map.overlays(), index);
	}

	static long pack(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public int overlays() {
		return this.keys.length;
	}

	/** The overlay of a story cell, or -1. */
	public int overlay(String key) {
		Integer o = this.byKey.get(key);
		return o == null ? -1 : o;
	}

	public String key(int overlay) {
		return this.keys[overlay];
	}

	/**
	 * The overlay column at block (x, y, z): of the overlays in that column, the one whose feet level is the highest at
	 * or below {@code y} (stacked floors hold several); {overlay, column, feet y}, or null. Allocation-free.
	 */
	public int @Nullable [] at(int x, int y, int z) {
		long key = pack(x, z);
		int i = Arrays.binarySearch(this.columnKeys, key);
		if (i < 0) {
			return null;
		}
		while (i > 0 && this.columnKeys[i - 1] == key) {
			i--;
		}
		int[] best = null;
		for (; i < this.columnKeys.length && this.columnKeys[i] == key; i++) {
			int[] info = this.columnInfo[i];
			if (info[2] <= y && (best == null || info[2] > best[2])) {
				best = info;
			}
		}
		return best;
	}

	/** A column's shape ids, bottom-up from feet level. */
	public int[] ids(int overlay, int column) {
		return this.ids[overlay][column];
	}

	/** A column's closed art slot (the fallback cube), -1 when unknown. */
	public int slot(int overlay, int column) {
		return this.slots[overlay][column];
	}

	/** True when the cell's closed art is walkable art, drawn flat on the floor. */
	public boolean flat(int overlay) {
		return this.flat[overlay];
	}

	/** The cell's closed art's height in blocks: its tallest column; 0 for flat art. */
	public int height(int overlay) {
		if (this.flat[overlay]) {
			return 0;
		}
		int max = 0;
		for (int[] column : this.ids[overlay]) {
			max = Math.max(max, column.length);
		}
		return max;
	}

	/** x, y, z of the cell's north-west column at feet level. */
	public int[] origin(int overlay) {
		return this.origins[overlay].clone();
	}

	/** The overlays of {@code keys} (those that have one) as a bit set. */
	public BitSet bits(Collection<String> keys) {
		BitSet out = new BitSet(this.keys.length);
		for (String key : keys) {
			int o = this.overlay(key);
			if (o >= 0) {
				out.set(o);
			}
		}
		return out;
	}

	/** The block box of a cell's closed columns: {minX, minY, minZ, maxX, maxY, maxZ}, inclusive. */
	public int[] box(int overlay) {
		int[] o = this.origins[overlay];
		int h = Math.max(1, this.height(overlay));
		return new int[] {o[0], o[1], o[2], o[0] + Scale.CELL_BLOCKS - 1, o[1] + h - 1, o[2] + Scale.CELL_BLOCKS - 1};
	}
}
