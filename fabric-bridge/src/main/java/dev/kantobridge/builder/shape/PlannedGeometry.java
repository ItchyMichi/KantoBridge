package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.FaceMasks;
import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.builder.shape.geom.TextureKey;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.ShapeIndex;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One planned map's geometry as the world would draw it (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6), for the self-check: the
 * floor's top where no opaque block stands on it (wearing a unit's ground tile under a unit), and every block of every
 * column, every ledge lip and every tuft: its shape's quads where it has a shape, the plain cube of its own tile
 * otherwise. A face is left out where the game leaves it out: on
 * an opaque {@code tile_full} neighbour (the floor, and the columns' blocks other than {@code shape_full} roofs), and on a
 * shaped neighbour whose face mask covers it ({@link FaceMasks}).
 */
public final class PlannedGeometry implements SelfCheck.Geometry {
	private final SceneMap map;
	private final ShapePlanner.MapShapes shapes;
	private final ShapeIndex index;
	private final ShapeTable table;
	private final List<TextureKey> textures = new ArrayList<>();
	private final int cols;

	public PlannedGeometry(SceneMap map, ShapePlanner.MapShapes shapes, ShapeIndex index, ShapeTable table) {
		this.map = map;
		this.shapes = shapes;
		this.index = index;
		this.table = table;
		this.cols = map.artCols();
		for (String key : table.textureKeys()) {
			// A colour of a pack's model has no tile: such quads aren't the 2D map's (the headless build has no pack).
			this.textures.add(TextureKey.parse(key));
		}
	}

	private boolean inside(int bx, int bz) {
		return bx >= 0 && bz >= 0 && bx < this.cols && bz < this.map.artRows();
	}

	private int blocks(int bx, int bz) {
		return this.shapes.blocks()[bz * this.cols + bx];
	}

	@Override
	public boolean solid(int bx, int by, int bz) {
		if (!this.inside(bx, bz)) {
			return false;
		}
		return by == -1 || (by >= 0 && by < this.blocks(bx, bz) && (this.shapes.shapeMask()[bz * this.cols + bx] >> by & 1) == 0);
	}

	/** The shape drawn at a block of the map, or null (a plain cube, a floor, air). */
	private @Nullable ShapeMesh mesh(int bx, int by, int bz) {
		if (!this.inside(bx, bz) || by < 0) {
			return null;
		}
		ShapeKey[] column = this.shapes.open()[bz * this.cols + bx];
		ShapeKey key = column == null || by >= column.length ? null : column[by];
		return key == null ? null : this.table.mesh(this.index.shapeId(key, this.map.palette()));
	}

	@Override
	public void forEach(SelfCheck.QuadVisitor visitor) {
		for (int ty = 0; ty < this.map.artRows(); ty++) {
			for (int tx = 0; tx < this.cols; tx++) {
				int i = ty * this.cols + tx;
				TextureKey ownKey = TextureKey.of(this.map.atlas(), this.map.palette(), this.map.tile(tx, ty));
				int blocks = this.blocks(tx, ty);
				if (blocks == 0 || (this.shapes.shapeMask()[i] & 1) != 0) {
					// The floor's top shows where nothing opaque stands on it: walkable tiles, and under a unit (its ground).
					TextureKey floorKey = TextureKey.of(this.map.atlas(), this.map.palette(), this.shapes.floorTile(i, this.map.tile(tx, ty)));
					visitor.quad(tx, -1, ty, ShapeQuad.fullFace(ShapeQuad.UP, 0), floorKey);
				}
				ShapeKey[] column = this.shapes.open()[ty * this.cols + tx];
				int levels = Math.max(blocks, column == null ? 0 : column.length);
				for (int k = 0; k < levels; k++) {
					ShapeMesh mesh = this.mesh(tx, k, ty);
					if (mesh == null) {
						if (k < blocks) {
							for (int face = 0; face < 6; face++) {
								this.emit(visitor, tx, k, ty, ShapeQuad.fullFace(face, 0), ownKey, null, -1);
							}
						}
						continue;
					}
					for (int q = 0; q < mesh.size(); q++) {
						ShapeQuad quad = mesh.quads().get(q);
						TextureKey texture = this.textures.get(quad.texture());
						if (texture != null) {
							this.emit(visitor, tx, k, ty, quad, texture, mesh, q);
						}
					}
				}
			}
		}
	}

	private void emit(SelfCheck.QuadVisitor visitor, int bx, int by, int bz, ShapeQuad quad, TextureKey texture, @Nullable ShapeMesh mesh,
			int q) {
		int cull = quad.cull();
		if (cull >= 0) {
			int nx = bx + FaceMasks.DX[cull];
			int ny = by + FaceMasks.DY[cull];
			int nz = bz + FaceMasks.DZ[cull];
			if (this.solid(nx, ny, nz)) {
				return;
			}
			if (mesh != null && FaceMasks.culled(mesh, q, this.mesh(nx, ny, nz))) {
				return;
			}
		}
		visitor.quad(bx, by, bz, quad, texture);
	}
}
