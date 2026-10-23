package dev.kantobridge.builder.shape.geom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The quads of one shape (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5), with its face masks: per block face, the 8×8 cells
 * (2/16 block each) its quads on that face cover completely. A quad on a block face is dropped when its footprint lies
 * inside the neighbour's opposite mask, or the neighbour's opposite face holds the same outline ({@link FaceMasks}).
 *
 * <p>A view over a {@link QuadStore}: a table's meshes share one store and one pool of face masks, and a quad's footprint
 * and outline are worked out when asked (2026-10-23: kept per quad and per mesh as objects, Hoenn's table held about 1.3
 * GB). The per-quad accessors allocate nothing, for the mesh threads; {@link #quads()} builds objects. Immutable; safe to
 * read from mesh threads.
 */
public final class ShapeMesh {
	private final QuadStore store;
	private final int first;
	private final int count;
	private final long[] masks;
	private final int maskAt;

	/** A mesh of its own (a builder's, a test's). */
	public ShapeMesh(List<ShapeQuad> quads) {
		this.store = new QuadStore();
		for (ShapeQuad quad : quads) {
			this.store.add(quad);
		}
		this.store.trim();
		this.first = 0;
		this.count = quads.size();
		this.masks = FaceMasks.coverage(quads);
		this.maskAt = 0;
	}

	/** Quads {@code first .. first + count - 1} of {@code store}, their face masks at {@code masks[maskAt .. maskAt + 5]}. */
	ShapeMesh(QuadStore store, int first, int count, long[] masks, int maskAt) {
		this.store = store;
		this.first = first;
		this.count = count;
		this.masks = masks;
		this.maskAt = maskAt;
	}

	/** The quads as objects (allocates; the mesh threads use the per-quad accessors). */
	public List<ShapeQuad> quads() {
		List<ShapeQuad> out = new ArrayList<>(this.count);
		for (int q = 0; q < this.count; q++) {
			out.add(this.store.quad(this.first + q));
		}
		return Collections.unmodifiableList(out);
	}

	/** Quad {@code q} as an object (allocates). */
	public ShapeQuad quad(int q) {
		return this.store.quad(this.check(q));
	}

	public int size() {
		return this.count;
	}

	private int check(int q) {
		if (q < 0 || q >= this.count) {
			throw new IndexOutOfBoundsException(q);
		}
		return this.first + q;
	}

	// ---- one quad's fields, allocating nothing ----

	public int face(int q) {
		return this.store.face(this.check(q));
	}

	/** The block face quad {@code q} lies on, or -1. */
	public int cull(int q) {
		return this.store.cull(this.check(q));
	}

	public int texture(int q) {
		return this.store.texture(this.check(q));
	}

	public int flags(int q) {
		return this.store.flags(this.check(q));
	}

	public boolean has(int q, int flag) {
		return (this.flags(q) & flag) != 0;
	}

	/** Quad {@code q}'s tint, 0..3. */
	public int tint(int q) {
		return this.flags(q) >> ShapeQuad.TINT_SHIFT & 3;
	}

	public int x(int q, int corner) {
		return this.store.coord(this.check(q), corner * 3);
	}

	public int y(int q, int corner) {
		return this.store.coord(this.check(q), corner * 3 + 1);
	}

	public int z(int q, int corner) {
		return this.store.coord(this.check(q), corner * 3 + 2);
	}

	/** Corner {@code i}'s u in 1/16 of the texture, as {@link ShapeQuad#cornerU}. */
	public float cornerU(int q, int i) {
		return this.cornerUv(this.check(q), i, 0);
	}

	/** Corner {@code i}'s v in 1/16 of the texture, as {@link ShapeQuad#cornerV}. */
	public float cornerV(int q, int i) {
		return this.cornerUv(this.check(q), i, 1);
	}

	/** {@link ShapeQuad}'s face conventions, read from the store ({@code at} is the store's index). */
	private float cornerUv(int at, int i, int axis) {
		QuadStore s = this.store;
		if (s.cornerUvs(at)) {
			return s.uv(at, i * 2 + axis);
		}
		int face = s.face(at);
		float uv0 = s.uv(at, axis);
		float uv1 = s.uv(at, axis + 2);
		float f;
		if (axis == 0) {
			f = switch (face) {
				case ShapeQuad.DOWN, ShapeQuad.UP, ShapeQuad.SOUTH -> frac(s, at, 0, i, false);
				case ShapeQuad.NORTH -> frac(s, at, 0, i, true);
				case ShapeQuad.WEST -> frac(s, at, 2, i, false);
				default -> frac(s, at, 2, i, true);
			};
		} else {
			f = switch (face) {
				case ShapeQuad.DOWN -> frac(s, at, 2, i, true);
				case ShapeQuad.UP -> frac(s, at, 2, i, false);
				default -> frac(s, at, 1, i, true);
			};
		}
		return uv0 + f * (uv1 - uv0);
	}

	/** Corner i's place along {@code axis} as a fraction of the quad's extent, from its far end when {@code flip}. */
	private static float frac(QuadStore s, int at, int axis, int i, boolean flip) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int c = 0; c < 4; c++) {
			int v = s.coord(at, c * 3 + axis);
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		if (max == min) {
			return 0;
		}
		int v = s.coord(at, i * 3 + axis);
		return ((flip ? max - v + min : v) - min) / (float) (max - min);
	}

	// ---- face masks ----

	/** The cells of block face {@code face} (0..5) this shape covers completely. */
	public long faceMask(int face) {
		return this.masks[this.maskAt + face];
	}

	/** The cells quad {@code quad} touches on the face it lies on (0 for a quad inside the block). */
	public long footprint(int quad) {
		return FaceMasks.footprint(this, quad);
	}

	/** Quad {@code quad}'s outline on the face it lies on (its corners in the face's coordinates, as a set). */
	public long outline(int quad) {
		return FaceMasks.outline(this, quad);
	}

	/** True when a quad lying on face {@code face} has outline {@code outline}. */
	public boolean hasOutline(int face, long outline) {
		for (int q = 0; q < this.count; q++) {
			if (this.store.cull(this.first + q) == face && FaceMasks.outline(this, q) == outline) {
				return true;
			}
		}
		return false;
	}

	/** The same quads of the same store (a table gives a new view on each lookup). */
	@Override
	public boolean equals(Object other) {
		return other instanceof ShapeMesh mesh && mesh.store == this.store && mesh.first == this.first && mesh.count == this.count;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this.store) * 31 + this.first;
	}
}
