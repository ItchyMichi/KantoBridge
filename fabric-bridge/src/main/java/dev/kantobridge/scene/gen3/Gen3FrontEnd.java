package dev.kantobridge.scene.gen3;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.builder.plan.G3Built;
import dev.kantobridge.builder.plan.G3Cells;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.PaletteModel;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.gen1.Gen1FrontEnd;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Gen 3 front end (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6; the voxel plan's §5.3): the neutral scene from an Emerald
 * export bundle, reusing the builder's Gen 3 cell model ({@link G3Cells}), so the scene shows exactly what the builder
 * builds. 16-px cells of 2×2 8-px quarters, as Gen 1's; the whole art one virtual atlas ({@link QuarterPages#VIRTUAL},
 * tiles are quarter ids) in one baked palette; kinds and tags from the collision bytes; outdoors, the open air and caves
 * from the map types; no swap sites (Gen 3's terrain changes come at run time; SE3: the cells they can change are
 * described in their built states, {@link G3Built}) and no animated tiles (still frames, §5). Everything Gen-3-specific stays in here. Pure: the server and every client get the same scene from the same
 * export.
 */
public final class Gen3FrontEnd implements GameFrontEnd {
	public static final Gen3FrontEnd INSTANCE = new Gen3FrontEnd();
	public static final String ID = "kb-frontend-gen3/1";
	public static final String FAMILY = "gen3";
	static final int CELL_PX = 16;
	static final int ART_PX = 8;
	/** Art tiles along a cell's edge. */
	static final int PER_CELL = CELL_PX / ART_PX;

	private Gen3FrontEnd() {
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
		Set<String> surface = bundle.hasG3Layout() ? bundle.g3layout().maps().keySet() : Set.of();
		String root = bundle.hasG3Layout() ? bundle.g3layout().root() : "";
		Map<String, G3Tiles> pairs = new HashMap<>();
		Map<String, SceneMap> maps = new TreeMap<>();
		Set<Integer> grass = new TreeSet<>();
		Map<Integer, String> hints = new TreeMap<>();
		int quarters = 0;
		// Anomalies are the planner's to report for the maps it places.
		Warnings scratch = new Warnings();
		int index = 0;
		for (String id : bundle.ids("g3map")) {
			// SE3: the cells the story changes in their built states, as the builder builds them
			G3Map map = G3Built.map(bundle, id);
			if (!bundle.has("g3tiles", map.pair())) {
				continue;
			}
			G3Tiles tiles = pairs.get(map.pair());
			if (tiles == null) {
				tiles = bundle.g3tiles(map.pair());
				pairs.put(map.pair(), tiles);
				for (int mid : tiles.mids()) {
					for (int quarter : tiles.quarters(mid)) {
						quarters = Math.max(quarters, quarter + 1);
					}
				}
			}
			MapModel model = G3Cells.model(map, tiles, !surface.contains(id), scratch);
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
		SceneAtlas atlas = new SceneAtlas(QuarterPages.VIRTUAL, QuarterPages.PER_ROW, quarters, ART_PX, List.of(), grass, hints);
		return new Scene(bundle.game(), FAMILY, ID, bundle.exportHash(), PaletteModel.BAKED, CELL_PX, ART_PX, Map.of(QuarterPages.VIRTUAL, atlas), maps,
				Map.of(), root, null);
	}

	/** One map's scene description from its cell model; {@code index} its place in the export's id order. */
	static SceneMap describe(G3Map map, MapModel model, int index, boolean surface) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		int cols = w * PER_CELL;
		int rows = h * PER_CELL;
		int[] tiles = new int[cols * rows];
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				tiles[ty * cols + tx] = model.tile(tx, ty);
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
		int[] border = squareBorder(map, model);
		List<SceneMap.SceneObject> objects = new ArrayList<>();
		for (G3Map.MapObject object : map.objects()) {
			String kind = switch (object.kind()) {
				case "item" -> SceneMap.SceneObject.ITEM;
				case "trainer" -> SceneMap.SceneObject.TRAINER;
				default -> SceneMap.SceneObject.PERSON;
			};
			objects.add(new SceneMap.SceneObject(object.name(), object.gfx(), object.x(), object.y(), Boolean.TRUE.equals(object.stays()), false, kind));
		}
		boolean outdoor = G3Cells.outdoor(map);
		Env env = new Env(outdoor, outdoor, G3Cells.cave(map), map.dark() ? Env.DARK_FLASH : Env.DARK_NEVER, false);
		return new SceneMap(map.id(), index, map.pair(), QuarterPages.VIRTUAL, QuarterPages.PALETTE, w, h, PER_CELL, surface, env, tiles, null, kinds,
				tags, dirs, border, border, objects, List.of());
	}

	/**
	 * The border as the scene needs it, a square of art tiles: {@code 2bw × 2bh} repeated to a square whose side both
	 * divide (Emerald's 2×2-metatile borders are already one).
	 */
	static int[] squareBorder(G3Map map, MapModel model) {
		int cols = map.border().w() * PER_CELL;
		int rows = map.border().h() * PER_CELL;
		int side = cols / gcd(cols, rows) * rows;
		int[] out = new int[side * side];
		for (int ty = 0; ty < side; ty++) {
			for (int tx = 0; tx < side; tx++) {
				out[ty * side + tx] = model.borderTile(tx, ty);
			}
		}
		return out;
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}
}
