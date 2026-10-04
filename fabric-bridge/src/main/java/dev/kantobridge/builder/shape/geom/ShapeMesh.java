package dev.kantobridge.builder.shape.geom;

import java.util.List;

/** The quads of one shape (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5). Immutable; safe to read from mesh threads. */
public record ShapeMesh(List<ShapeQuad> quads) {
	public ShapeMesh {
		quads = List.copyOf(quads);
	}

	public int size() {
		return this.quads.size();
	}
}
