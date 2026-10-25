package dev.kantobridge.builder.plan;

/** An axis-aligned box of blocks, max exclusive. */
public record Box3(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public Box3 {
		if (maxX < minX || maxY < minY || maxZ < minZ) {
			throw new IllegalArgumentException("inverted box");
		}
	}

	public boolean contains(int x, int y, int z) {
		return x >= this.minX && x < this.maxX && y >= this.minY && y < this.maxY && z >= this.minZ && z < this.maxZ;
	}

	public boolean containsColumn(int x, int z) {
		return x >= this.minX && x < this.maxX && z >= this.minZ && z < this.maxZ;
	}

	/** True when the box overlaps the 16×16 columns of chunk (chunkX, chunkZ). */
	public boolean intersectsChunk(int chunkX, int chunkZ) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		return this.minX < x0 + 16 && this.maxX > x0 && this.minZ < z0 + 16 && this.maxZ > z0;
	}

	/** True when the two boxes' columns overlap. */
	public boolean intersectsXZ(Box3 other) {
		return this.minX < other.maxX && other.minX < this.maxX && this.minZ < other.maxZ && other.minZ < this.maxZ;
	}

	/** The same box grown by {@code n} blocks in every horizontal direction. */
	public Box3 inflateXZ(int n) {
		return new Box3(this.minX - n, this.minY, this.minZ - n, this.maxX + n, this.maxY, this.maxZ + n);
	}

	public int minChunkX() {
		return this.minX >> 4;
	}

	public int maxChunkX() {
		return (this.maxX - 1) >> 4;
	}

	public int minChunkZ() {
		return this.minZ >> 4;
	}

	public int maxChunkZ() {
		return (this.maxZ - 1) >> 4;
	}
}
