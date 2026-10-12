package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A cave map's rooms (docs/specs/CAVES_3D_PLAN.md §3.5, H5): the parts the builder may place apart so that every ladder
 * and hole lines up. A room is a connected part of the map's walk graph: its walkable and water cells joined by any edge
 * the game could ever use. Tile pairs, ledges, story blockers and swap sites (a Victory Road barrier, a Seafoam hole a
 * boulder fills) count as joined, so rooms never depend on the story (I4). Rock belongs to every room it borders, the
 * eight cells round each of a room's own; a room's rectangle holds all of them. Pure Java.
 */
public final class CavePieces {
	/**
	 * One room.
	 *
	 * @param index its number in the map, in scan order of its first cell
	 * @param floor its walkable and water cells, row by row over the whole map
	 * @param cells those and the rock it borders: the cells it draws as its own
	 */
	public record Room(String map, int index, BitSet floor, BitSet cells, int x, int y, int w, int h) {
		public Room {
			floor = (BitSet) floor.clone();
			cells = (BitSet) cells.clone();
		}

		/** True when cell (cx, cy) of a map {@code mapWidth} cells wide is this room's own. */
		public boolean owns(int mapWidth, int cx, int cy) {
			return cx >= this.x && cy >= this.y && cx < this.x + this.w && cy < this.y + this.h && this.cells.get(cy * mapWidth + cx);
		}

		/** True when cell (cx, cy) is one of its walkable or water cells. */
		public boolean walks(int mapWidth, int cx, int cy) {
			return cx >= 0 && cy >= 0 && cx < mapWidth && this.floor.get(cy * mapWidth + cx);
		}

		/** Its rectangle's mask, row by row, or null when it owns the whole rectangle. */
		public BitSet mask(int mapWidth) {
			BitSet out = new BitSet(this.w * this.h);
			for (int cy = 0; cy < this.h; cy++) {
				for (int cx = 0; cx < this.w; cx++) {
					if (this.cells.get((this.y + cy) * mapWidth + this.x + cx)) {
						out.set(cy * this.w + cx);
					}
				}
			}
			return out;
		}
	}

	private CavePieces() {
	}

	/**
	 * The rooms of {@code map}, in scan order of their first cell.
	 *
	 * @param sites the blocks ({bx, by}) of the map's swap sites: their cells may be walked on in some story state
	 */
	public static List<Room> of(ExportMap map, Collection<int[]> sites) {
		int w = map.cellWidth();
		int h = map.cellHeight();
		boolean[] walks = new boolean[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int flags = map.flags(cx, cy);
				walks[cy * w + cx] = CellFlags.has(flags, CellFlags.WALKABLE) || CellFlags.has(flags, CellFlags.WATER);
			}
		}
		for (int[] site : sites) {
			for (int dy = 0; dy < 2; dy++) {
				for (int dx = 0; dx < 2; dx++) {
					int cx = site[0] * 2 + dx;
					int cy = site[1] * 2 + dy;
					if (cx >= 0 && cy >= 0 && cx < w && cy < h) {
						walks[cy * w + cx] = true;
					}
				}
			}
		}
		int[] room = new int[w * h];
		java.util.Arrays.fill(room, -1);
		List<Room> out = new ArrayList<>();
		for (int start = 0; start < w * h; start++) {
			if (!walks[start] || room[start] >= 0) {
				continue;
			}
			int id = out.size();
			BitSet floor = new BitSet(w * h);
			Deque<Integer> queue = new ArrayDeque<>();
			room[start] = id;
			queue.add(start);
			while (!queue.isEmpty()) {
				int i = queue.poll();
				floor.set(i);
				int cx = i % w;
				int cy = i / w;
				int[][] steps = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
				for (int[] step : steps) {
					int nx = cx + step[0];
					int ny = cy + step[1];
					if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
						continue;
					}
					int j = ny * w + nx;
					if (walks[j] && room[j] < 0) {
						room[j] = id;
						queue.add(j);
					}
				}
			}
			BitSet cells = (BitSet) floor.clone();
			int minX = w;
			int minY = h;
			int maxX = -1;
			int maxY = -1;
			for (int i = floor.nextSetBit(0); i >= 0; i = floor.nextSetBit(i + 1)) {
				int cx = i % w;
				int cy = i / w;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = cx + dx;
						int ny = cy + dy;
						if (nx >= 0 && ny >= 0 && nx < w && ny < h && !walks[ny * w + nx]) {
							cells.set(ny * w + nx);
						}
					}
				}
			}
			for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
				minX = Math.min(minX, i % w);
				maxX = Math.max(maxX, i % w);
				minY = Math.min(minY, i / w);
				maxY = Math.max(maxY, i / w);
			}
			out.add(new Room(map.id(), id, floor, cells, minX, minY, maxX - minX + 1, maxY - minY + 1));
		}
		return out;
	}

	/** The room whose walkable or water cells hold (cx, cy), or null. */
	public static @Nullable Room roomAt(List<Room> rooms, int mapWidth, int cx, int cy) {
		for (Room room : rooms) {
			if (room.walks(mapWidth, cx, cy)) {
				return room;
			}
		}
		return null;
	}
}
