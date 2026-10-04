package dev.kantobridge.scene;

import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.scene.profile.ProfileSource;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The neutral description of one game's world (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): its atlases, maps and sprites
 * in terms every game family can fill. A front end per family ({@link GameFrontEnd}) builds it from that family's
 * export; the shape planner, the geometry and the character code read only this. A scene of a real game is
 * ROM-derived: it lives in memory and is never written to disk by default.
 *
 * @param family    the game family ({@code gen1})
 * @param frontEnd  the front end that built it, with its version ({@code kb-frontend-gen1/1})
 * @param cellPx    pixels per walk-grid cell edge
 * @param artPx     pixels per art tile edge
 * @param layoutRoot the surface map the layout is relative to
 * @param profile    the shape profile the export carried (docs/specs/PROFILE_PLAN.md), or null when it carried none
 */
public record Scene(String game, String family, String frontEnd, String exportHash, PaletteModel paletteModel, int cellPx, int artPx,
		Map<String, SceneAtlas> atlases, Map<String, SceneMap> maps, Map<String, SceneSprite> sprites, String layoutRoot,
		@Nullable ProfileSource profile) {
	public Scene {
		atlases = Collections.unmodifiableMap(new TreeMap<>(atlases));
		maps = Collections.unmodifiableMap(new TreeMap<>(maps));
		sprites = Collections.unmodifiableMap(new TreeMap<>(sprites));
	}

	/** A scene without a profile. */
	public Scene(String game, String family, String frontEnd, String exportHash, PaletteModel paletteModel, int cellPx, int artPx,
			Map<String, SceneAtlas> atlases, Map<String, SceneMap> maps, Map<String, SceneSprite> sprites, String layoutRoot) {
		this(game, family, frontEnd, exportHash, paletteModel, cellPx, artPx, atlases, maps, sprites, layoutRoot, null);
	}

	/** The profile's entry for a tileset, or null without a readable profile or an entry. */
	public Profile.@Nullable Tileset profileTileset(String tilesetId) {
		Profile read = this.profile == null ? null : this.profile.profile();
		return read == null ? null : read.tileset(tilesetId);
	}

	public @Nullable SceneMap map(String id) {
		return this.maps.get(id);
	}

	public @Nullable SceneAtlas atlas(String id) {
		return this.atlases.get(id);
	}
}
