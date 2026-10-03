package dev.kantobridge.builder.plan;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * The planned contents of one chunk: for every Y level the build touches, 16×16 {@link BlockCode}s. Levels iterate
 * top-down, the order the placer writes them in (roofs and ground first, so interiors are built into darkness).
 */
public final class ChunkCodes {
	private final int chunkX;
	private final int chunkZ;
	private final TreeMap<Integer, int[]> levels = new TreeMap<>((a, b) -> Integer.compare(b, a));

	public ChunkCodes(int chunkX, int chunkZ) {
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
	}

	public int chunkX() {
		return this.chunkX;
	}

	public int chunkZ() {
		return this.chunkZ;
	}

	public int minX() {
		return this.chunkX << 4;
	}

	public int minZ() {
		return this.chunkZ << 4;
	}

	/** Y levels top-down, with their codes ({@code [lz * 16 + lx]}). */
	public Map<Integer, int[]> levels() {
		return this.levels;
	}

	private int[] level(int y) {
		return this.levels.computeIfAbsent(y, k -> {
			int[] codes = new int[256];
			Arrays.fill(codes, BlockCode.UNOWNED);
			return codes;
		});
	}

	/** The code at world (x, y, z), which must lie in this chunk. */
	public int get(int x, int y, int z) {
		int[] codes = this.levels.get(y);
		return codes == null ? BlockCode.UNOWNED : codes[index(x, z)];
	}

	public void set(int x, int y, int z, int code) {
		this.level(y)[index(x, z)] = code;
	}

	/** Claims a position for the build (air unless something is planned there). */
	public void own(int x, int y, int z) {
		int[] codes = this.level(y);
		int i = index(x, z);
		if (codes[i] == BlockCode.UNOWNED) {
			codes[i] = BlockCode.AIR;
		}
	}

	/** Positions owned by the build. */
	public int ownedCount() {
		int n = 0;
		for (int[] codes : this.levels.values()) {
			for (int code : codes) {
				if (code != BlockCode.UNOWNED) {
					n++;
				}
			}
		}
		return n;
	}

	private int index(int x, int z) {
		return ((z - this.minZ()) << 4) | (x - this.minX());
	}
}
