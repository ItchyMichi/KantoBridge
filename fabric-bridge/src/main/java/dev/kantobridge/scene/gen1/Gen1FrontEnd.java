package dev.kantobridge.scene.gen1;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.export.ExportImage;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportProfile;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.PaletteModel;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.SceneSprite;
import dev.kantobridge.scene.SwapSite;
import dev.kantobridge.scene.profile.ProfileSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * The Gen 1 front end (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): the neutral scene from a Red/Blue export bundle, reusing
 * {@link MapModel}'s logic, so the scene shows exactly what the builder builds: the tile under every block (swap sites
 * open), both states of every changing cell, cell kinds from the export's per-cell flags, and the border the game
 * really draws ({@code borderDrawn}). Everything Gen-1-specific stays in here: 16-px cells of 2×2 8-px tiles, flag bits,
 * one palette per map, the forest tileset that is open to the sky, the cave tileset.
 */
public final class Gen1FrontEnd implements GameFrontEnd {
	public static final Gen1FrontEnd INSTANCE = new Gen1FrontEnd();
	public static final String ID = "kb-frontend-gen1/1";
	public static final String FAMILY = "gen1";
	/** Gen 1 caves are drawn with this tileset. */
	static final String CAVE_TILESET = WorldPlanner.CAVE_TILESET;
	/**
	 * The woods under a leaf canopy (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2): Dramaless Shape's own list
	 * ({@code lib/DayNight.lua}, {@code DayNight.CANOPY}), which Kanto in First Person roofs. The Safari Zone shares the
	 * forest tileset and keeps its sky.
	 */
	public static final java.util.Set<String> CANOPY_MAPS = java.util.Set.of("VIRIDIAN_FOREST");
	static final int CELL_PX = 16;
	static final int ART_PX = 8;
	/** Art tiles along a cell's edge. */
	static final int PER_CELL = CELL_PX / ART_PX;
	/** Gen 1 blocks (and so borders) are 4×4 art tiles; swap sites are whole blocks. */
	static final int BLOCK_TILES = 4;
	static final int CELLS_PER_BLOCK = BLOCK_TILES / PER_CELL;
	/** Classes the front end knows about single tiles: the tiles of a cuttable tree are foliage. */
	public static final String HINT_TREE = dev.kantobridge.scene.SceneAtlas.HINT_FOLIAGE;

	private Gen1FrontEnd() {
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String family() {
		return FAMILY;
	}

	@Override
	public Scene describe(ExportBundle bundle) throws BundleException, PlanException {
		ExportField field = bundle.field();
		String forest = field.indoor() == null ? null : field.indoor().excludedTileset();
		Map<String, List<ExportSwaps.Site>> swapsByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			swapsByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Map<String, ExportTileset> tilesets = new TreeMap<>();
		Map<String, AtlasFacts> atlasFacts = new TreeMap<>();
		Map<String, SceneMap> maps = new TreeMap<>();
		// Anomalies are the planner's to report for the maps it places.
		Warnings scratch = new Warnings();
		for (String id : bundle.ids("map")) {
			ExportMap map = bundle.map(id);
			if (!bundle.has("tileset", map.tileset())) {
				continue;
			}
			ExportTileset tileset = tilesets.get(map.tileset());
			if (tileset == null) {
				tileset = bundle.tileset(map.tileset());
				tilesets.put(map.tileset(), tileset);
			}
			AtlasFacts facts = atlasFacts.computeIfAbsent(tileset.image(), k -> new AtlasFacts());
			facts.add(tileset);
			MapModel model = MapModel.build(map, tileset, swapsByMap.getOrDefault(id, List.of()), !map.surface(), scratch);
			SceneMap sceneMap = describe(map, tileset, model, forest);
			if (sceneMap.env().outdoor() && sceneMap.border() != null) {
				// The tree wall the game draws around outdoor maps is foliage.
				for (int tile : sceneMap.border()) {
					facts.hints.put(tile, HINT_TREE);
				}
			}
			maps.put(id, sceneMap);
		}
		Map<String, SceneAtlas> atlases = new TreeMap<>();
		for (Map.Entry<String, AtlasFacts> entry : atlasFacts.entrySet()) {
			atlases.put(entry.getKey(), entry.getValue().atlas(entry.getKey()));
		}
		Map<String, SceneSprite> sprites = new TreeMap<>();
		for (String id : bundle.ids("sprite")) {
			ExportImage sprite = bundle.sprite(id);
			sprites.put(id, new SceneSprite(id, id, CELL_PX, CELL_PX, sprite.frames(), true));
		}
		String root = bundle.has("layout", "kanto") ? bundle.layout().root() : WorldPlanner.DEFAULT_ROOT;
		// The profile plan: another mod's shape profile, when the export carried one (read as data, never run).
		ExportProfile carried = bundle.profile();
		ProfileSource profile = carried == null ? null : ProfileSource.read(carried.text(), carried.version(), carried.enabled());
		return new Scene(bundle.game(), FAMILY, ID, bundle.exportHash(), PaletteModel.MAP4, CELL_PX, ART_PX, atlases, maps, sprites, root,
				profile);
	}

	/** One map's scene description from its cell model. */
	static SceneMap describe(ExportMap map, ExportTileset tileset, MapModel model, @Nullable String forest) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		int cols = w * PER_CELL;
		int rows = h * PER_CELL;
		int[] tiles = new int[cols * rows];
		boolean anyChange = false;
		for (int cy = 0; cy < h && !anyChange; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (model.changes(cx, cy)) {
					anyChange = true;
					break;
				}
			}
		}
		MapModel closed = anyChange ? model.closedModel() : null;
		int[] closedTiles = anyChange ? new int[cols * rows] : null;
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				tiles[ty * cols + tx] = model.tile(tx, ty);
				if (closedTiles != null) {
					closedTiles[ty * cols + tx] = closed.tile(tx, ty);
				}
			}
		}
		byte[] kinds = new byte[w * h];
		int[] tags = new int[w * h];
		byte[] dirs = new byte[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int i = cy * w + cx;
				int flags = model.flags(cx, cy);
				kinds[i] = (byte) kind(model.kind(cx, cy)).id();
				tags[i] = tags(flags);
				dirs[i] = (byte) (CellFlags.has(flags, CellFlags.LEDGE) ? CellFlags.ledgeDir(flags) : SceneMap.NO_DIR);
			}
		}
		Integer drawn = map.borderDrawn();
		int[] border = drawn == null ? null : blockTiles(tileset, drawn);
		int[] ownBorder = blockTiles(tileset, map.border());
		List<SceneMap.SceneObject> objects = new ArrayList<>();
		for (ExportMap.MapObject object : map.objects()) {
			String kind = object.item() != null ? SceneMap.SceneObject.ITEM
					: object.trainer() ? SceneMap.SceneObject.TRAINER : object.pokemon() ? SceneMap.SceneObject.POKEMON : SceneMap.SceneObject.PERSON;
			objects.add(new SceneMap.SceneObject(object.name(), object.sprite(), object.x(), object.y(), object.stays(), object.hidden(), kind));
		}
		List<SwapSite> sites = new ArrayList<>();
		for (ExportSwaps.Site site : new TreeMap<>(model.sites()).values()) {
			List<SwapSite.SwapCell> cells = new ArrayList<>();
			for (int dy = 0; dy < CELLS_PER_BLOCK; dy++) {
				for (int dx = 0; dx < CELLS_PER_BLOCK; dx++) {
					int cx = site.bx() * CELLS_PER_BLOCK + dx;
					int cy = site.by() * CELLS_PER_BLOCK + dy;
					if (closed != null && model.changes(cx, cy)) {
						cells.add(new SwapSite.SwapCell(cx, cy, state(closed, cx, cy), state(model, cx, cy)));
					}
				}
			}
			if (!cells.isEmpty()) {
				SwapSite.SwapCell first = cells.get(0);
				sites.add(new SwapSite(map.id() + ":" + site.kind() + ":" + first.x() + ":" + first.y(), site.kind(), cells));
			}
		}
		boolean outdoor = map.outside();
		boolean openAir = outdoor || tileset.id().equals(forest);
		Env env = new Env(outdoor, openAir, CAVE_TILESET.equals(tileset.id()), map.dark() ? Env.DARK_FLASH : Env.DARK_NEVER,
				openAir && !map.surface() && CANOPY_MAPS.contains(map.id()));
		return new SceneMap(map.id(), map.index(), tileset.id(), tileset.image(), map.palette(), w, h, PER_CELL, map.surface(), env, tiles,
				closedTiles, kinds, tags, dirs, border, ownBorder, objects, sites);
	}

	static CellKind kind(MapModel.Kind kind) {
		return switch (kind) {
			case GROUND -> CellKind.WALK;
			case WATER -> CellKind.WATER;
			case LEDGE -> CellKind.LEDGE;
			case COUNTER -> CellKind.COUNTER;
			case SOLID -> CellKind.SOLID;
		};
	}

	/** Gen 1's per-cell flag bits as neutral tags. */
	static int tags(int flags) {
		int tags = 0;
		tags |= CellFlags.has(flags, CellFlags.GRASS) ? CellTag.GRASS.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.DOOR) ? CellTag.DOOR.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.WARP) ? CellTag.WARP.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.WARP_TILE) ? CellTag.WARP_TILE.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.SHORE) ? CellTag.SHORE.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.CUT_TREE) ? CellTag.CUT.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.PAD) ? CellTag.PAD.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.HOLE) ? CellTag.HOLE.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.SPINNER) ? CellTag.SPINNER.bit() : 0;
		tags |= CellFlags.has(flags, CellFlags.SIGN) ? CellTag.SIGN.bit() : 0;
		return tags;
	}

	private static SwapSite.CellState state(MapModel model, int cx, int cy) {
		int[] art = new int[PER_CELL * PER_CELL];
		for (int dy = 0; dy < PER_CELL; dy++) {
			for (int dx = 0; dx < PER_CELL; dx++) {
				art[dy * PER_CELL + dx] = model.tile(cx * PER_CELL + dx, cy * PER_CELL + dy);
			}
		}
		return new SwapSite.CellState(kind(model.kind(cx, cy)), tags(model.flags(cx, cy)), art);
	}

	/** The tiles of a Gen 1 block, row-major; an unknown block shows tile 0. */
	private static int[] blockTiles(ExportTileset tileset, int block) {
		int[] out = new int[BLOCK_TILES * BLOCK_TILES];
		for (int t = 0; t < out.length; t++) {
			out[t] = Math.max(0, tileset.tile(block, t % BLOCK_TILES, t / BLOCK_TILES));
		}
		return out;
	}

	/** What the tilesets drawing from one atlas image say about its tiles. */
	private static final class AtlasFacts {
		int tilesPerRow;
		int tiles;
		final List<dev.kantobridge.scene.Animated> animated = new ArrayList<>();
		final Set<String> animatedSeen = new TreeSet<>();
		final Set<Integer> grass = new TreeSet<>();
		final Map<Integer, String> hints = new TreeMap<>();

		void add(ExportTileset tileset) {
			this.tilesPerRow = tileset.tilesPerRow();
			this.tiles = Math.max(this.tiles, tileset.tiles());
			if (tileset.grass() != null) {
				this.grass.add(tileset.grass());
			}
			for (int tile : new TreeSet<>(tileset.cutTree())) {
				this.hints.put(tile, HINT_TREE);
			}
			for (ExportTileset.Animated entry : tileset.animated()) {
				dev.kantobridge.scene.Animated scene = new dev.kantobridge.scene.Animated(entry.tiles(), entry.kind(), entry.period(),
						entry.offsets(), entry.images(), entry.sequence(), entry.image(), entry.stripOffsets(), entry.gate());
				// Several tilesets may share an atlas; each entry once.
				if (this.animatedSeen.add(entry.kind() + java.util.Arrays.toString(entry.tiles()))) {
					this.animated.add(scene);
				}
			}
		}

		SceneAtlas atlas(String id) {
			return new SceneAtlas(id, this.tilesPerRow, this.tiles, ART_PX, this.animated, this.grass, this.hints);
		}
	}
}
