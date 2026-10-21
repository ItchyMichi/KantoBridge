package dev.kantobridge.builder.shape;

import dev.kantobridge.scene.Animated;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.PaletteModel;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.SwapSite;
import dev.kantobridge.world.MapPlacement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny scenes from ASCII art (docs/specs/VOXEL_BLOCKS_PLAN.md §6.1), one per rule, so a failure is readable and no
 * golden file moves. Made up; no game data.
 *
 * <p>One character is one art tile (one block column); its tile id is its position in {@link #LEGEND}. A cell is 2×2
 * tiles and takes its kind from its bottom-left tile, as Gen 1 does: {@code ~} water, {@code >} a ledge hopping down,
 * {@code =} a counter, an upper-case letter, {@code #} or {@code !} solid ({@code !} a sign), anything else walkable;
 * {@code +} is a walkable door. For Tier B: {@code ,} is the atlas's grass tile, {@code 0} a tile that cycles through
 * frames (a flower), {@code T} wears the foliage hint ({@link #TIER_B}).
 */
public final class ShapeFixtures {
	public static final String ATLAS = "tilesets.fix";
	public static final String PALETTE = "FIX_PALETTE";
	public static final String LEGEND = ".,+abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ#~>=0123456789!";
	public static final int PER_CELL = 2;
	public static final int ART_PX = 8;
	/** Feet level of fixture surface maps, and of the first interior layer. */
	public static final int SURFACE_FEET = 64;
	public static final int INTERIOR_FEET = 42;

	private ShapeFixtures() {
	}

	/** The tile id of an art character. */
	public static int tile(char c) {
		int id = LEGEND.indexOf(c);
		if (id < 0) {
			throw new IllegalArgumentException("not a fixture tile: " + c);
		}
		return id;
	}

	static CellKind kindOf(char c) {
		if (c == '~') {
			return CellKind.WATER;
		}
		if (c == '>') {
			return CellKind.LEDGE;
		}
		if (c == '=') {
			return CellKind.COUNTER;
		}
		if (c == '#' || c == '!' || Character.isUpperCase(c)) {
			return CellKind.SOLID;
		}
		return CellKind.WALK;
	}

	static int tagsOf(char c) {
		return c == '+' ? CellTag.DOOR.bit() | CellTag.WARP.bit() : c == '!' ? CellTag.SIGN.bit() : 0;
	}

	/** An outdoor surface map. */
	public static SceneMap surface(String id, String... rows) {
		return map(id, true, rows);
	}

	/** An interior map. */
	public static SceneMap interior(String id, String... rows) {
		return map(id, false, rows);
	}

	public static SceneMap map(String id, boolean surface, String... rows) {
		return map(id, surface, new Env(surface, surface, false, Env.DARK_NEVER), rows);
	}

	/** An outdoor map off the surface, under the open sky (a Battle Frontier outside): interior rules, drawn outdoors. */
	public static SceneMap openAir(String id, String... rows) {
		return map(id, false, new Env(true, true, false, Env.DARK_NEVER), rows);
	}

	static SceneMap map(String id, boolean surface, Env env, String... rows) {
		int cols = rows[0].length();
		if (cols % PER_CELL != 0 || rows.length % PER_CELL != 0) {
			throw new IllegalArgumentException("art must be whole cells");
		}
		int w = cols / PER_CELL;
		int h = rows.length / PER_CELL;
		int[] tiles = new int[cols * rows.length];
		for (int ty = 0; ty < rows.length; ty++) {
			if (rows[ty].length() != cols) {
				throw new IllegalArgumentException("ragged art");
			}
			for (int tx = 0; tx < cols; tx++) {
				tiles[ty * cols + tx] = tile(rows[ty].charAt(tx));
			}
		}
		byte[] kinds = new byte[w * h];
		int[] tags = new int[w * h];
		byte[] dirs = new byte[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				char c = rows[cy * PER_CELL + PER_CELL - 1].charAt(cx * PER_CELL);
				kinds[cy * w + cx] = (byte) kindOf(c).id();
				tags[cy * w + cx] = tagsOf(c);
				dirs[cy * w + cx] = (byte) (c == '>' ? 0 : SceneMap.NO_DIR);
			}
		}
		int[] border = new int[16];
		Arrays.fill(border, tile('#'));
		return new SceneMap(id, 0, "FIX", ATLAS, PALETTE, w, h, PER_CELL, surface, env, tiles, null, kinds, tags, dirs, surface ? border : null,
				border, List.of(), List.of());
	}

	/**
	 * {@code open} with one more swap site over cell (cx, cy): built open, as {@code open} shows it, closed as
	 * {@code closed} (2 rows of 2 characters); a {@code cut} site's closed cell is a cut tree.
	 */
	public static SceneMap withSite(SceneMap open, String kind, int cx, int cy, String... closed) {
		int cols = open.artCols();
		// Earlier sites keep their closed art.
		int[] closedTiles = open.closedTiles() != null ? open.closedTiles().clone() : open.tiles().clone();
		int[] closedArt = new int[PER_CELL * PER_CELL];
		int[] openArt = new int[PER_CELL * PER_CELL];
		for (int dy = 0; dy < PER_CELL; dy++) {
			for (int dx = 0; dx < PER_CELL; dx++) {
				int i = (cy * PER_CELL + dy) * cols + cx * PER_CELL + dx;
				closedTiles[i] = tile(closed[dy].charAt(dx));
				closedArt[dy * PER_CELL + dx] = closedTiles[i];
				openArt[dy * PER_CELL + dx] = open.tiles()[i];
			}
		}
		char key = closed[PER_CELL - 1].charAt(0);
		SwapSite.CellState shut = new SwapSite.CellState(kindOf(key), tagsOf(key) | ("cut".equals(kind) ? CellTag.CUT.bit() : 0), closedArt);
		SwapSite.CellState opened = new SwapSite.CellState(open.kind(cx, cy), open.tags(cx, cy), openArt);
		SwapSite site = new SwapSite(open.id() + ":" + kind + ":" + cx + ":" + cy, kind, List.of(new SwapSite.SwapCell(cx, cy, shut, opened)));
		List<SwapSite> sites = new ArrayList<>(open.swapSites());
		sites.add(site);
		return new SceneMap(open.id(), open.index(), open.tileset(), open.atlas(), open.palette(), open.w(), open.h(), open.perCell(),
				open.surface(), open.env(), open.tiles(), closedTiles, open.kinds(), open.tags(), open.dirs(), open.border(), open.ownBorder(),
				open.objects(), sites);
	}

	/** {@code map} with every tile of its drawn border (and its own) {@code tile}. */
	public static SceneMap withBorder(SceneMap map, char tile) {
		int[] border = new int[map.ownBorder().length];
		Arrays.fill(border, tile(tile));
		return new SceneMap(map.id(), map.index(), map.tileset(), map.atlas(), map.palette(), map.w(), map.h(), map.perCell(), map.surface(),
				map.env(), map.tiles(), map.closedTiles(), map.kinds(), map.tags(), map.dirs(), map.surface() ? border : null, border, map.objects(),
				map.swapSites());
	}

	/** The fixtures' atlas for Tier B: {@code ,} grass, {@code 0} a flower (frames), {@code T} foliage. */
	public static final SceneAtlas TIER_B = new SceneAtlas(ATLAS, 16, LEGEND.length(), ART_PX,
			List.of(new Animated(new int[] {tile('0')}, Animated.FRAMES, 20, new int[0], List.of("fix.flower1", "fix.flower2"), new int[] {1, 2},
					null, new int[0], null)),
			Set.of(tile(',')), Map.of(tile('T'), SceneAtlas.HINT_FOLIAGE));

	/** A scene of the maps, the first one the layout's root. */
	public static Scene scene(SceneMap... maps) {
		return scene(new SceneAtlas(ATLAS, 16, LEGEND.length(), ART_PX, List.of(), Set.of(), Map.of()), maps);
	}

	/** A scene of the maps over {@code atlas}, the first one the layout's root. */
	public static Scene scene(SceneAtlas atlas, SceneMap... maps) {
		Map<String, SceneMap> byId = new LinkedHashMap<>();
		for (SceneMap map : maps) {
			byId.put(map.id(), map);
		}
		return new Scene("fix", "fix", "fixtures/1", "0".repeat(64), PaletteModel.MAP4, PER_CELL * ART_PX, ART_PX, Map.of(ATLAS, atlas), byId,
				Map.of(), maps[0].id());
	}

	/** A surface placement with the map's north-west corner at block (x, z). */
	public static MapPlacement surfaceAt(SceneMap map, int x, int z) {
		return new MapPlacement(map.id(), x, SURFACE_FEET, z, map.w(), map.h(), 0, MapPlacement.Kind.SURFACE, map.palette(), "fix:outdoor", false,
				null);
	}

	/** An interior placement on layer {@code layer} (feet 22 blocks lower per layer). */
	public static MapPlacement interiorAt(SceneMap map, int x, int z, int layer, String parent) {
		return new MapPlacement(map.id(), x, INTERIOR_FEET - 22 * (layer - 1), z, map.w(), map.h(), layer, MapPlacement.Kind.INTERIOR,
				map.palette(), "fix:indoor", false, parent);
	}
}
