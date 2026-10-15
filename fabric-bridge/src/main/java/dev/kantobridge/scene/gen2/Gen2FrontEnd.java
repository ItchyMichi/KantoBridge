package dev.kantobridge.scene.gen2;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Data;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.builder.plan.G2Cells;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.PaletteModel;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.SwapSite;
import dev.kantobridge.scene.gen1.Gen1FrontEnd;
import dev.kantobridge.scene.gen3.QuarterPages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Gen 2 front end (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7; the voxel plan's §5.3): the neutral scene from a Crystal
 * export bundle, reusing the builder's Gen 2 cell model ({@link G2Cells}), so the scene shows exactly what the builder
 * builds. 16-px cells of 2×2 baked 8-px tiles, as Gen 1's and 3's; the whole art one virtual atlas
 * ({@link QuarterPages#VIRTUAL}: Gen 2 bakes its pages as Gen 3's, tiles are baked tile ids) in one baked palette; kinds
 * and tags from the meanings of the collision bytes; outdoors and caves from the map's environment; the swap sites'
 * both states, as Gen 1's; no animated tiles (still frames, §5). Everything Gen-2-specific stays in here. Pure: the
 * server and every client get the same scene from the same export.
 */
public final class Gen2FrontEnd implements GameFrontEnd {
	public static final Gen2FrontEnd INSTANCE = new Gen2FrontEnd();
	public static final String ID = "kb-frontend-gen2/1";
	public static final String FAMILY = "gen2";
	static final int CELL_PX = 16;
	static final int ART_PX = 8;
	/** Art tiles along a cell's edge. */
	static final int PER_CELL = CELL_PX / ART_PX;
	/** Art tiles along a block's edge (a block is 2×2 cells). */
	static final int BLOCK_TILES = 4;

	private Gen2FrontEnd() {
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
		List<G2Data.Layout> layouts = bundle.g2layouts();
		Set<String> surface = new TreeSet<>();
		for (G2Data.Layout layout : layouts) {
			surface.addAll(layout.maps().keySet());
		}
		String root = layouts.isEmpty() ? "" : layouts.get(0).root();
		Map<String, List<ExportSwaps.Site>> sitesByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			sitesByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Map<String, G2Tiles> variants = new HashMap<>();
		Map<String, SceneMap> maps = new TreeMap<>();
		Set<Integer> grass = new TreeSet<>();
		Map<Integer, String> hints = new TreeMap<>();
		int tileCount = 0;
		// Anomalies are the planner's to report for the maps it places.
		Warnings scratch = new Warnings();
		int index = 0;
		for (String id : bundle.ids("g2map")) {
			G2Map map = bundle.g2map(id);
			if (!bundle.has("g2tiles", map.variant())) {
				continue;
			}
			G2Tiles tiles = variants.get(map.variant());
			if (tiles == null) {
				tiles = bundle.g2tiles(map.variant());
				variants.put(map.variant(), tiles);
				for (int block = 0; block < tiles.blockCount(); block++) {
					for (int t = 0; t < BLOCK_TILES * BLOCK_TILES; t++) {
						tileCount = Math.max(tileCount, tiles.tile(block, t % BLOCK_TILES, t / BLOCK_TILES) + 1);
					}
				}
			}
			MapModel model = G2Cells.model(map, tiles, sitesByMap.getOrDefault(id, List.of()), !surface.contains(id), scratch);
			SceneMap sceneMap = describe(map, model, index++, surface.contains(id));
			// The grass the game rolls encounters on, as the atlas's tall grass; the border round an outdoor map, foliage.
			for (int cy = 0; cy < model.cellHeight(); cy++) {
				for (int cx = 0; cx < model.cellWidth(); cx++) {
					if (CellFlags.has(model.flags(cx, cy), CellFlags.GRASS)) {
						for (int q = 0; q < PER_CELL * PER_CELL; q++) {
							grass.add(model.tile(cx * PER_CELL + q % PER_CELL, cy * PER_CELL + q / PER_CELL));
						}
					}
				}
			}
			if (sceneMap.env().outdoor()) {
				for (int tile : sceneMap.ownBorder()) {
					hints.put(tile, SceneAtlas.HINT_FOLIAGE);
				}
			}
			maps.put(id, sceneMap);
		}
		SceneAtlas atlas = new SceneAtlas(QuarterPages.VIRTUAL, QuarterPages.PER_ROW, tileCount, ART_PX, List.of(), grass, hints);
		return new Scene(bundle.game(), FAMILY, ID, bundle.exportHash(), PaletteModel.BAKED, CELL_PX, ART_PX, Map.of(QuarterPages.VIRTUAL, atlas), maps,
				Map.of(), root, null);
	}

	/** One map's scene description from its cell model; {@code index} its place in the export's id order. */
	static SceneMap describe(G2Map map, MapModel model, int index, boolean surface) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		int cols = w * PER_CELL;
		int rows = h * PER_CELL;
		MapModel closed = model.closedModel() == model ? null : model.closedModel();
		int[] tiles = new int[cols * rows];
		int[] closedTiles = closed == null ? null : new int[cols * rows];
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
				kinds[i] = (byte) Gen1FrontEnd.kind(model.kind(cx, cy)).id();
				tags[i] = Gen1FrontEnd.tags(flags);
				dirs[i] = (byte) (CellFlags.has(flags, CellFlags.LEDGE) ? CellFlags.ledgeDir(flags) : SceneMap.NO_DIR);
			}
		}
		int[] border = new int[BLOCK_TILES * BLOCK_TILES];
		for (int t = 0; t < border.length; t++) {
			border[t] = model.borderTile(t % BLOCK_TILES, t / BLOCK_TILES);
		}
		List<SceneMap.SceneObject> objects = new ArrayList<>();
		for (G2Map.MapObject object : map.objects()) {
			String kind = switch (object.kind()) {
				case "item" -> SceneMap.SceneObject.ITEM;
				case "trainer" -> SceneMap.SceneObject.TRAINER;
				default -> SceneMap.SceneObject.PERSON;
			};
			// a sprite a variable chooses (the bedroom's dolls) is named by its variable
			String sprite = object.sprite() != null ? object.sprite() : "SPRITE_VAR_" + object.var();
			objects.add(new SceneMap.SceneObject(object.name(), sprite, object.x(), object.y(), object.stays(), false, kind));
		}
		List<SwapSite> sites = new ArrayList<>();
		if (closed != null) {
			for (ExportSwaps.Site site : new TreeMap<>(model.sites()).values()) {
				List<SwapSite.SwapCell> cells = new ArrayList<>();
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int cx = site.bx() * 2 + dx;
						int cy = site.by() * 2 + dy;
						if (model.changes(cx, cy)) {
							cells.add(new SwapSite.SwapCell(cx, cy, state(closed, cx, cy), state(model, cx, cy)));
						}
					}
				}
				if (!cells.isEmpty()) {
					SwapSite.SwapCell first = cells.get(0);
					sites.add(new SwapSite(map.id() + ":" + site.kind() + ":" + first.x() + ":" + first.y(), site.kind(), cells));
				}
			}
		}
		boolean outdoor = map.outdoor();
		Env env = new Env(outdoor, outdoor, map.caveLike(), map.dark() ? Env.DARK_FLASH : Env.DARK_NEVER, false);
		return new SceneMap(map.id(), index, map.variant(), QuarterPages.VIRTUAL, QuarterPages.PALETTE, w, h, PER_CELL, surface, env, tiles,
				closedTiles, kinds, tags, dirs, border, border, objects, sites);
	}

	private static SwapSite.CellState state(MapModel model, int cx, int cy) {
		int[] art = new int[PER_CELL * PER_CELL];
		for (int dy = 0; dy < PER_CELL; dy++) {
			for (int dx = 0; dx < PER_CELL; dx++) {
				art[dy * PER_CELL + dx] = model.tile(cx * PER_CELL + dx, cy * PER_CELL + dy);
			}
		}
		return new SwapSite.CellState(Gen1FrontEnd.kind(model.kind(cx, cy)), Gen1FrontEnd.tags(model.flags(cx, cy)), art);
	}
}
