package dev.kantobridge.builder.pack;

import java.util.Locale;

/**
 * The one owner of the generated pack's path rules (docs/specs/VOXEL_BLOCKS_PLAN.md §4.7, layout 2): every texture is
 * addressed by its key and the <b>art set</b> it was drawn from (the game of the export, a path segment), never by a
 * slot. A guest drawing a host's world turns the host's keys into its own sprites with these, so the rule must not
 * change without a new layout. Pure.
 */
public final class ArtKeys {
	public static final String NAMESPACE = "kantobridge";
	/** The generated pack's format (written to {@code kantobridge.json} as {@code layout}). */
	public static final int LAYOUT = 2;

	private ArtKeys() {
	}

	/**
	 * The texture path of a tile, relative to {@code assets/kantobridge/textures/}, without {@code .png}:
	 * {@code block/<set>/tile/<atlas>/<palette>/<tile, 4 digits>}. Also the sprite id's path in the block atlas.
	 */
	public static String texturePath(String artSet, String atlas, String palette, int tile) {
		return String.format(Locale.ROOT, "block/%s/tile/%s/%s/%04d", pathPart(artSet), pathPart(atlas), pathPart(palette), tile);
	}

	/** The block-atlas sprite id of a tile: {@code kantobridge:block/<set>/tile/<atlas>/<palette>/<tile>}. */
	public static String spriteId(String artSet, String atlas, String palette, int tile) {
		return NAMESPACE + ":" + texturePath(artSet, atlas, palette, tile);
	}

	/** The pack file of a tile's texture. */
	public static String textureFile(String artSet, String atlas, String palette, int tile) {
		return "assets/" + NAMESPACE + "/textures/" + texturePath(artSet, atlas, palette, tile) + ".png";
	}

	/** The NPC sheet of a sprite in a palette: {@code kantobridge:textures/npc/<set>/<palette>/<sprite>.png}. */
	public static String npcTexture(String artSet, String palette, String sprite) {
		return NAMESPACE + ":textures/npc/" + pathPart(artSet) + "/" + pathPart(palette) + "/" + pathPart(sprite) + ".png";
	}

	/** The surfing player's sheet in a palette: {@code kantobridge:textures/player/<set>/<palette>/surf.png}. */
	public static String surfTexture(String artSet, String palette) {
		return NAMESPACE + ":textures/player/" + pathPart(artSet) + "/" + pathPart(palette) + "/surf.png";
	}

	/** A pack file for a texture id ({@code kantobridge:textures/...png}). */
	public static String file(String textureId) {
		return "assets/" + NAMESPACE + "/" + textureId.substring(NAMESPACE.length() + 1);
	}

	/** An id as a resource-path segment: lowercase, anything outside {@code [a-z0-9_.-]} replaced by '_'. */
	public static String pathPart(String id) {
		String lower = id.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ? c : '_');
		}
		return out.toString();
	}
}
