package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.scene.gen3.QuarterPages;
import org.jetbrains.annotations.Nullable;

/**
 * The texture a shape's quad wears, by key (docs/specs/VOXEL_BLOCKS_PLAN.md §4.9): {@code tile:<atlas>/<tile>@<PALETTE>},
 * a tile of an atlas in one palette. Each client resolves it to a sprite of its own art set; a pack may override it.
 *
 * @param atlas   the atlas image id
 * @param palette the palette id
 * @param tile    the tile index
 */
public record TextureKey(String atlas, String palette, int tile) {
	public static final String TILE = "tile:";

	/**
	 * The key of a scene's tile: as given, except a Gen 3 scene's virtual atlas, whose tiles are quarter ids, names the
	 * quarter's page and its index there (MG7, {@link QuarterPages}), as its texture is keyed.
	 */
	public static TextureKey of(String atlas, String palette, int tile) {
		return new TextureKey(QuarterPages.atlasOf(atlas, tile), palette, QuarterPages.tileOf(atlas, tile));
	}

	/** The key's text. */
	public String text() {
		return TILE + this.atlas + "/" + this.tile + "@" + this.palette;
	}

	/** Parses a tile key; null for anything else. */
	public static @Nullable TextureKey parse(String text) {
		if (!text.startsWith(TILE)) {
			return null;
		}
		int slash = text.lastIndexOf('/');
		int at = text.lastIndexOf('@');
		if (slash <= TILE.length() || at <= slash + 1 || at == text.length() - 1) {
			return null;
		}
		try {
			int tile = Integer.parseInt(text.substring(slash + 1, at), 10);
			return tile < 0 || tile > 1023 ? null : new TextureKey(text.substring(TILE.length(), slash), text.substring(at + 1), tile);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
