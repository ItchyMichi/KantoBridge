package dev.kantobridge.builder.shape;

import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The profile's class at each art tile of one map (docs/specs/PROFILE_PLAN.md §5.3): a conditional pin of the tile
 * (its neighbour above or below, read from the map's own tiles, the map's own border block outside it; {@code above}
 * rules first, each in file order), else the tile's pin, else none. Pure and pixel-free.
 */
final class ProfileClasses {
	/** The classes P1 turns into cell units: hulls, standee pools and posts, with a standee's depth in pixels. */
	static final Set<String> UNIT_CLASSES = Set.of("cylinder", "billboard", "signpost", "stool", "prop", "cutout", "bike", "console", "post");
	/** P3's too (docs/specs/PROFILE_PLAN.md §5.6): the hull variants, rising and sunken stairs, reliefs. */
	static final Set<String> P3_UNIT_CLASSES = Set.of("cylinder", "billboard", "signpost", "stool", "prop", "cutout", "bike", "console", "post",
			"stump", "can", "relief", "stair_e", "stair_w", "stair_down_e", "stair_down_w");

	private final SceneMap map;
	private final Profile.Tileset tileset;
	private final Set<String> unitClasses;

	private ProfileClasses(SceneMap map, Profile.Tileset tileset, Set<String> unitClasses) {
		this.map = map;
		this.tileset = tileset;
		this.unitClasses = unitClasses;
	}

	/** The classes of {@code map}, or null when the scene's profile says nothing about its tileset. */
	static @Nullable ProfileClasses of(Scene scene, SceneMap map) {
		return of(map, scene.profileTileset(map.tileset()));
	}

	/** The classes of {@code map} under a tileset's entry, or null without one. */
	static @Nullable ProfileClasses of(SceneMap map, Profile.@Nullable Tileset tileset) {
		return of(map, tileset, false);
	}

	/** The same, with P3's unit classes when {@code p3}. */
	static @Nullable ProfileClasses of(SceneMap map, Profile.@Nullable Tileset tileset, boolean p3) {
		return tileset == null ? null : new ProfileClasses(map, tileset, p3 ? P3_UNIT_CLASSES : UNIT_CLASSES);
	}

	Profile.Tileset tileset() {
		return this.tileset;
	}

	/** The class at art tile (tx, ty), or null when the profile pins nothing there. */
	@Nullable String at(int tx, int ty) {
		return this.tileset.classAt(this.map.tile(tx, ty), this.neighbour(tx, ty - 1), this.neighbour(tx, ty + 1));
	}

	/** The map's own tile, or its own border block's outside the map. */
	private int neighbour(int tx, int ty) {
		if (tx >= 0 && ty >= 0 && tx < this.map.artCols() && ty < this.map.artRows()) {
			return this.map.tile(tx, ty);
		}
		return this.map.ownBorderTile(tx, ty);
	}

	/**
	 * What the profile makes of a cell: the unit class of its top-left tile, else the first unit class in row-major
	 * order; {@link #OTHER} when it pins its tiles only to classes P1 doesn't make units of; null when it pins nothing.
	 */
	@Nullable String unitClass(int cx, int cy) {
		int per = this.map.perCell();
		String first = null;
		boolean any = false;
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				String cls = this.at(cx * per + dx, cy * per + dy);
				if (cls == null) {
					continue;
				}
				any = true;
				if (this.unitClasses.contains(cls)) {
					if (dx == 0 && dy == 0) {
						return cls;
					}
					first = first == null ? cls : first;
				}
			}
		}
		return first != null ? first : any ? OTHER : null;
	}

	/** A cell the profile pins to classes that aren't units (yet): it stays what our own rules make it. */
	static final String OTHER = "other";

	/**
	 * The map's solid tiles for Tier A, without the cells the profile pins to a unit class: a pinned prop, sign or tree
	 * is its own unit and never joins a wall's region or run (the research: authored tiles are never structural), so a
	 * sign beside a house leaves the house's template alone, and the pinned cell's unit stands two blocks high.
	 */
	boolean[] structural() {
		boolean[] solid = Structures.solidTiles(this.map);
		int per = this.map.perCell();
		int cols = this.map.artCols();
		for (int cy = 0; cy < this.map.h(); cy++) {
			for (int cx = 0; cx < this.map.w(); cx++) {
				String cls = this.map.kind(cx, cy) == dev.kantobridge.scene.CellKind.SOLID ? this.unitClass(cx, cy) : null;
				if (cls == null || OTHER.equals(cls)) {
					continue;
				}
				for (int dy = 0; dy < per; dy++) {
					for (int dx = 0; dx < per; dx++) {
						solid[(cy * per + dy) * cols + cx * per + dx] = false;
					}
				}
			}
		}
		return solid;
	}

	/** The ground the profile paints under a cell's prop ({@code prop_ground} of its first tile naming one), or -1. */
	int propGround(int[] tiles) {
		for (int tile : tiles) {
			Integer ground = this.tileset.propGround().get(tile);
			if (ground != null) {
				return ground;
			}
		}
		return -1;
	}

	/** The background shades {@code prop_bg} names for a cell (bit k = shade k), from its first tile naming any; 0 for none. */
	int propBackground(int[] tiles) {
		for (int tile : tiles) {
			Integer bits = this.tileset.propBg().get(tile);
			if (bits != null && bits != 0) {
				return bits;
			}
		}
		return 0;
	}
}
