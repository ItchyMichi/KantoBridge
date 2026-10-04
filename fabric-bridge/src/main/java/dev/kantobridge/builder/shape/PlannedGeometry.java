package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.builder.shape.geom.TextureKey;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.ShapeIndex;
import java.util.ArrayList;
import java.util.List;

/**
 * One planned map's geometry as the world would draw it (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6), for the self-check: the
 * floor's top where nothing stands on it, and every block of every column: its shape's quads where it has a shape, the
 * plain cube of its own tile otherwise. Faces a solid neighbour hides are left out, as the mesher culls them.
 */
public final class PlannedGeometry implements SelfCheck.Geometry {
	private final SceneMap map;
	private final ShapePlanner.MapShapes shapes;
	private final ShapeIndex index;
	private final ShapeTable table;
	private final List<TextureKey> textures = new ArrayList<>();

	public PlannedGeometry(SceneMap map, ShapePlanner.MapShapes shapes, ShapeIndex index, ShapeTable table) {
		this.map = map;
		this.shapes = shapes;
		this.index = index;
		this.table = table;
		for (String key : table.textureKeys()) {
			this.textures.add(TextureKey.parse(key));
		}
	}

	private int height(int tx, int ty) {
		return this.shapes.heights()[this.map.cellIndexOfTile(tx, ty)];
	}

	@Override
	public boolean solid(int bx, int by, int bz) {
		if (bx < 0 || bz < 0 || bx >= this.map.artCols() || bz >= this.map.artRows()) {
			return false;
		}
		return by == -1 || (by >= 0 && by < this.height(bx, bz));
	}

	@Override
	public void forEach(SelfCheck.QuadVisitor visitor) {
		int cols = this.map.artCols();
		for (int ty = 0; ty < this.map.artRows(); ty++) {
			for (int tx = 0; tx < cols; tx++) {
				int own = this.map.tile(tx, ty);
				TextureKey ownKey = new TextureKey(this.map.atlas(), this.map.palette(), own);
				int h = this.height(tx, ty);
				if (h == 0) {
					visitor.quad(tx, -1, ty, ShapeQuad.fullFace(ShapeQuad.UP, 0), ownKey);
					continue;
				}
				ShapeKey[] column = this.shapes.open()[ty * cols + tx];
				for (int k = 0; k < h; k++) {
					ShapeKey key = column == null ? null : column[k];
					ShapeMesh mesh = key == null ? null : this.table.mesh(this.index.shapeId(key, this.map.palette()));
					if (mesh == null) {
						for (int face = 0; face < 6; face++) {
							this.emit(visitor, tx, k, ty, ShapeQuad.fullFace(face, 0), ownKey);
						}
						continue;
					}
					for (ShapeQuad quad : mesh.quads()) {
						this.emit(visitor, tx, k, ty, quad, this.textures.get(quad.texture()));
					}
				}
			}
		}
	}

	private void emit(SelfCheck.QuadVisitor visitor, int bx, int by, int bz, ShapeQuad quad, TextureKey texture) {
		if (quad.cull() >= 0) {
			int[] step = STEPS[quad.cull()];
			if (this.solid(bx + step[0], by + step[1], bz + step[2])) {
				return;
			}
		}
		visitor.quad(bx, by, bz, quad, texture);
	}

	/** Neighbour offsets by face: down, up, north, south, west, east. */
	private static final int[][] STEPS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
}
