package dev.kantobridge.builder.shape.geom;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What a client's texture packs change in its geometry (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10): shape rules and
 * {@code .vox} models, for the keys the world already has. A pack never changes a key, a carrier or collision, and an
 * override that can't be used falls back to the generated geometry, so it can never leave a hole (I9). The defaults are
 * the generated geometry.
 */
public interface ShapeOverrides {
	/** No pack. */
	ShapeOverrides NONE = new ShapeOverrides() {
	};

	/** A hash of everything here that changes geometry: part of the shape table cache's name. */
	default String geometryHash() {
		return ShapeTable.NO_OVERRIDES;
	}

	/**
	 * A cell unit replaced by a model ({@code cell:} key), or null.
	 *
	 * @param tiles the unit's four tiles, row-major
	 */
	default @Nullable Model unitModel(String atlas, int[] tiles) {
		return null;
	}

	/**
	 * The style a rule gives a cell unit ({@code hull}, {@code object} or {@code columns}), or null for the generated
	 * choice. A unit's class is the class a {@code tile:} rule pins on its tiles, else {@code tree} for foliage, else
	 * {@code prop}.
	 */
	default @Nullable String unitStyle(String atlas, int[] tiles, boolean foliage) {
		return null;
	}

	/** An object's slab depth in pixels by its class's rule, or 0 for the generated one. */
	default int objectDepth(String atlas, int[] tiles, boolean foliage) {
		return 0;
	}

	/** False when a rule lays a tile's tuft flat ({@code class:grass} or {@code class:flower} style {@code flat}). */
	default boolean tuft(String atlas, int tile, boolean flower) {
		return true;
	}

	/**
	 * A model's geometry.
	 *
	 * @param blocks per block of the target (its own block index, e.g. {@link CellUnits#block}), the quads; a quad's
	 *               texture is a colour index into {@code colours}
	 * @param colours ARGB by colour index
	 */
	record Model(List<List<ShapeQuad>> blocks, int[] colours) {
	}
}
