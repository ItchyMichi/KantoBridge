package dev.kantobridge.builder.shape.geom;

import java.util.List;

/**
 * The quads of one shape (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5), with its face masks: per block face, the 8×8 cells
 * (2/16 block each) its quads on that face cover completely and the outlines of those quads, and per quad its footprint
 * and outline on the face it lies on. A quad on a block face is dropped when its footprint lies inside the neighbour's
 * opposite mask, or the neighbour's opposite face holds the same outline ({@link FaceMasks}). Immutable; safe to read
 * from mesh threads.
 */
public final class ShapeMesh {
	private final List<ShapeQuad> quads;
	private final long[] faceMasks;
	private final long[] footprints;
	private final long[] outlines;
	/** Per face, the outlines of the quads lying on it, sorted. */
	private final long[][] faceOutlines = new long[6][];

	public ShapeMesh(List<ShapeQuad> quads) {
		this.quads = List.copyOf(quads);
		this.faceMasks = FaceMasks.coverage(this.quads);
		this.footprints = new long[this.quads.size()];
		this.outlines = new long[this.quads.size()];
		int[] perFace = new int[6];
		for (int i = 0; i < this.quads.size(); i++) {
			ShapeQuad quad = this.quads.get(i);
			this.footprints[i] = FaceMasks.footprint(quad);
			this.outlines[i] = FaceMasks.outline(quad);
			if (quad.cull() >= 0) {
				perFace[quad.cull()]++;
			}
		}
		for (int face = 0; face < 6; face++) {
			this.faceOutlines[face] = new long[perFace[face]];
			perFace[face] = 0;
		}
		for (int i = 0; i < this.quads.size(); i++) {
			int cull = this.quads.get(i).cull();
			if (cull >= 0) {
				this.faceOutlines[cull][perFace[cull]++] = this.outlines[i];
			}
		}
		for (long[] list : this.faceOutlines) {
			java.util.Arrays.sort(list);
		}
	}

	public List<ShapeQuad> quads() {
		return this.quads;
	}

	public int size() {
		return this.quads.size();
	}

	/** The cells of block face {@code face} (0..5) this shape covers completely. */
	public long faceMask(int face) {
		return this.faceMasks[face];
	}

	/** The cells quad {@code quad} touches on the face it lies on (0 for a quad inside the block). */
	public long footprint(int quad) {
		return this.footprints[quad];
	}

	/** Quad {@code quad}'s outline on the face it lies on (its corners in the face's coordinates, as a set). */
	public long outline(int quad) {
		return this.outlines[quad];
	}

	/** True when a quad lying on face {@code face} has outline {@code outline}. */
	public boolean hasOutline(int face, long outline) {
		return java.util.Arrays.binarySearch(this.faceOutlines[face], outline) >= 0;
	}
}
