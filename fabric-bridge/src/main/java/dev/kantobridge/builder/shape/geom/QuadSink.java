package dev.kantobridge.builder.shape.geom;

/**
 * Where a shape's quads go (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): the client's emitter in production, a recorder in
 * tests. {@code textureRef} is the quad's texture resolved for the sink (a sprite index on the client).
 */
@FunctionalInterface
public interface QuadSink {
	void quad(ShapeQuad quad, int textureRef);
}
