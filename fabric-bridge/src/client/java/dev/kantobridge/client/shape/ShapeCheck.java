package dev.kantobridge.client.shape;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import org.jetbrains.annotations.Nullable;

/**
 * When a client accepts the world's shapes (docs/specs/VOXEL_BLOCKS_PLAN.md §4.4): the shape index parses and its
 * version is known; the shape map decodes; the map's {@code indexHash} is the hash of the {@code world.index} the client
 * holds; its {@code shapeHash} is the SHA-256 of the received index text. Otherwise the world draws cubes, for the
 * {@link Rejected#code() reason} given. Pure.
 */
public final class ShapeCheck {
	public static final String UNSUPPORTED = "geometry_unsupported";
	public static final String MISMATCH = "shape_map_mismatch";

	/** The pair this client draws. */
	public record Accepted(ShapeIndex index, ShapeMap map) {
	}

	/** Why the pair is refused: a {@code texture.status} reason code. */
	public static final class Rejected extends Exception {
		private final String code;

		Rejected(String code, String detail) {
			super(detail, null, false, false);
			this.code = code;
		}

		public String code() {
			return this.code;
		}
	}

	private ShapeCheck() {
	}

	/**
	 * Checks both shape documents.
	 *
	 * @param indexText      the shape index's text as received
	 * @param mapWire        the shape map's wire form (Base64 of its deflated bytes)
	 * @param worldIndexHash the SHA-256 of the WorldIndex text this client holds, or null when it holds none
	 * @param maxMapBytes    the largest inflated map accepted
	 */
	public static Accepted check(String indexText, String mapWire, @Nullable String worldIndexHash, int maxMapBytes) throws Rejected {
		ShapeIndex index;
		try {
			index = ShapeIndex.fromJson(indexText);
		} catch (DataException e) {
			throw new Rejected(UNSUPPORTED, "shape index: " + e.problem());
		}
		ShapeMap map;
		try {
			map = ShapeMap.fromWire(mapWire, maxMapBytes);
		} catch (DataException e) {
			throw new Rejected(MISMATCH, "shape map: " + e.problem());
		}
		if (worldIndexHash == null || !map.indexHash().equals(worldIndexHash)) {
			throw new Rejected(MISMATCH, "the shape map names another world index");
		}
		String received = Hashes.sha256(indexText);
		if (!map.shapeHash().equals(received) || !index.hash().equals(received)) {
			throw new Rejected(MISMATCH, "the shape map names another shape index");
		}
		return new Accepted(index, map);
	}
}
