package dev.kantobridge.builder.shape.geom;

import java.util.Arrays;

/**
 * Voxels at pixel resolution (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): one art pixel per voxel edge, so {@code artPx}
 * voxels along a block's edge (one Game Boy pixel is 2/16 block). x runs east, y up, z south, from the grid's
 * north-west bottom corner, which lies on a block corner.
 *
 * <p>Each solid voxel wears one texel: a slot in the shape's tile list, and the texel's column and row in that tile. A
 * voxel may wear another texel on its top face (a hull's cap keeps its outline on the rim only).
 */
public final class VoxelGrid {
	/** No voxel. */
	public static final int EMPTY = -1;

	private final int w;
	private final int h;
	private final int d;
	private final int artPx;
	private final int[] texels;
	private int[] caps;

	/**
	 * @param w     voxels east
	 * @param h     voxels up
	 * @param d     voxels south
	 * @param artPx voxels per block edge (pixels per art tile edge); a divisor of 16
	 */
	public VoxelGrid(int w, int h, int d, int artPx) {
		if (w < 1 || h < 1 || d < 1 || artPx < 1 || 16 % artPx != 0 || w % artPx != 0 || h % artPx != 0 || d % artPx != 0) {
			throw new IllegalArgumentException("a voxel grid is whole blocks of artPx voxels, artPx a divisor of 16");
		}
		this.w = w;
		this.h = h;
		this.d = d;
		this.artPx = artPx;
		this.texels = new int[w * h * d];
		Arrays.fill(this.texels, EMPTY);
	}

	/** A texel: a slot in the shape's tile list (0..1023), its column and row (0..31). */
	public static int texel(int slot, int u, int v) {
		return slot << 10 | u << 5 | v;
	}

	public static int slot(int texel) {
		return texel >>> 10;
	}

	public static int u(int texel) {
		return texel >> 5 & 31;
	}

	public static int v(int texel) {
		return texel & 31;
	}

	public int width() {
		return this.w;
	}

	public int height() {
		return this.h;
	}

	public int depth() {
		return this.d;
	}

	public int artPx() {
		return this.artPx;
	}

	private int index(int x, int y, int z) {
		return (y * this.d + z) * this.w + x;
	}

	public boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.w && y < this.h && z < this.d;
	}

	/** Sets a voxel (outside the grid: ignored). */
	public void set(int x, int y, int z, int texel) {
		if (this.inside(x, y, z)) {
			this.texels[this.index(x, y, z)] = texel;
		}
	}

	/** Sets the texel a voxel's top face wears (it must be solid to show). */
	public void setCap(int x, int y, int z, int texel) {
		if (!this.inside(x, y, z)) {
			return;
		}
		if (this.caps == null) {
			this.caps = new int[this.texels.length];
			Arrays.fill(this.caps, EMPTY);
		}
		this.caps[this.index(x, y, z)] = texel;
	}

	public boolean solid(int x, int y, int z) {
		return this.inside(x, y, z) && this.texels[this.index(x, y, z)] != EMPTY;
	}

	/** The voxel's texel, or {@link #EMPTY}. */
	public int at(int x, int y, int z) {
		return this.inside(x, y, z) ? this.texels[this.index(x, y, z)] : EMPTY;
	}

	/** The texel the voxel's top face wears: its cap, else its own. */
	public int cap(int x, int y, int z) {
		if (!this.inside(x, y, z)) {
			return EMPTY;
		}
		int i = this.index(x, y, z);
		return this.caps != null && this.caps[i] != EMPTY && this.texels[i] != EMPTY ? this.caps[i] : this.texels[i];
	}

	/** Solid voxels. */
	public int count() {
		int n = 0;
		for (int t : this.texels) {
			n += t != EMPTY ? 1 : 0;
		}
		return n;
	}
}
