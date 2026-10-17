package dev.kantobridge.builder.shape.fit;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.ArtUnitProbe;
import dev.kantobridge.builder.shape.FittedShapes;
import dev.kantobridge.builder.shape.Occupancy;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.Hulls;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.builder.shape.geom.TemplateRule;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.GameFrontEnd;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Fitted shapes (docs/specs/FITTED_SHAPES.md): a headless tool over an export bundle, plain Java, no Minecraft.
 *
 * <pre>
 * dump &lt;bundle&gt; &lt;out.json&gt;              every planned unit once per cell (atlas and four tiles): its drawing's shades,
 *                                        its foreground, and the voxels today's pick builds (ROM-derived: local/ only)
 * locate &lt;bundle&gt; &lt;out.json&gt;          per planned cell, one map and cell where it stands and a walkable cell to look at it
 *                                        from (UnitLocations), for the in-scene review sheets
 * applied &lt;bundle&gt;                      the planner's unit tally with the jar's table on ({@code fit}: units it re-picked,
 *                                        {@code fit_columns}: cells it left to Tier A)
 * fit &lt;bundle&gt; &lt;targets.json&gt; &lt;out.json&gt; per cell, every builder and setting of {@link Candidates} against the
 *                                        cell's target voxels (made from its drawing); the best by voxel IoU, today's
 *                                        pick's IoU beside it (the table and its report: no pixels)
 * </pre>
 *
 * Voxels are {@link VoxelGrid}'s, x east, y up, z south, a unit 16 × 16 × 16; a grid travels as base64 of one bit per
 * voxel, index {@code (y * 16 + z) * 16 + x}, least significant bit first.
 */
public final class FitTool {
	static final int W = ShapeTable.UNIT_CELL * 8;

	private FitTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 3 && args[0].equals("dump")) {
			dump(Path.of(args[1]), Path.of(args[2]));
		} else if (args.length == 4 && args[0].equals("fit")) {
			fit(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
		} else if (args.length == 3 && args[0].equals("candidates")) {
			candidates(Path.of(args[1]), Path.of(args[2]));
		} else if (args.length == 4 && args[0].equals("cells")) {
			cells(Path.of(args[1]), args[2], Path.of(args[3]));
		} else if (args.length == 3 && args[0].equals("locate")) {
			locate(Path.of(args[1]), Path.of(args[2]));
		} else if (args.length == 2 && args[0].equals("applied")) {
			applied(Path.of(args[1]));
		} else {
			System.err.println("usage: FitTool dump <bundle> <out.json> | fit <bundle> <targets.json> <out.json> | applied <bundle>");
			System.exit(2);
		}
	}

	/** A planned unit: one per variant of a cell. */
	record Planned(String atlas, int[] tiles, String variant, CellUnits.Unit unit) {
		String cell() {
			return cellKey(this.atlas, this.tiles);
		}
	}

	/** A cell's key in the table: {@code <atlas>/<t0>-<t1>-<t2>-<t3>}, as a pack's {@code cell:} key names it. */
	static String cellKey(String atlas, int[] tiles) {
		StringBuilder out = new StringBuilder(atlas).append('/');
		for (int t = 0; t < tiles.length; t++) {
			out.append(t > 0 ? "-" : "").append(tiles[t]);
		}
		return out.toString();
	}

	/** A building template the table built (V7: no profile band table or two-cell hull). */
	record Building(String atlas, Templates.Entry entry, TemplateRule.Template template) {
		String key() {
			return this.atlas + "/" + this.entry.sig();
		}
	}

	record Export(String game, ArtSource art, List<Planned> units, List<Building> buildings) {
	}

	/** Plans the bundle as the pack template does (the profile from the export, no placements) and lists its units. */
	static Export plan(Path bundleDir) throws Exception {
		FittedShapes.setEnabled(false);
		ExportBundle bundle = ExportBundle.open(bundleDir);
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		if (front == null) {
			throw new IllegalArgumentException("no front end for " + bundle.game());
		}
		ArtSource art = FrontEnds.artSource(bundle);
		ShapePlan plan = ShapePlanner.plan(front.describe(bundle), List.of(), ShapeParams.current(), Occupancy.NONE, new ArtUnitProbe(art));
		ShapeTable table = ShapeTable.build(plan.index(), art);
		Map<String, Templates.Entry> entries = ShapeTable.entries(plan.index());
		List<Planned> units = new ArrayList<>();
		for (Map.Entry<String, CellUnits.Unit> e : new TreeMap<>(table.units()).entrySet()) {
			String[] parts = e.getKey().split("\\|", -1);
			int[] tiles = java.util.Arrays.stream(parts[1].split(",")).mapToInt(Integer::parseInt).toArray();
			units.add(new Planned(parts[0], tiles, parts[2], e.getValue()));
		}
		List<Building> buildings = new ArrayList<>();
		for (Map.Entry<String, TemplateRule.Template> e : new TreeMap<>(table.templates()).entrySet()) {
			String[] parts = e.getKey().split("\\|", -1);
			Templates.Entry entry = entries.get(parts[1]);
			if (entry != null && entry.volume() == null) {
				buildings.add(new Building(parts[0], entry, e.getValue()));
			}
		}
		return new Export(bundle.game(), art, units, buildings);
	}

	/** A cell's tiles' shades, null for a tile the art doesn't have. */
	static int[][] tiles(ArtSource art, String atlas, int[] ids) {
		int[][] out = new int[ids.length][];
		for (int t = 0; t < ids.length; t++) {
			out[t] = ids[t] < 0 ? null : art.tileShades(atlas, ids[t]);
		}
		return out;
	}

	/** The drawing as one {@code W × W} array of shades, row-major (-1 where a tile is missing or transparent). */
	static int[] drawing(int[][] tiles, int artPx) {
		int per = ShapeTable.UNIT_CELL;
		int w = per * artPx;
		int[] out = new int[w * w];
		for (int y = 0; y < w; y++) {
			for (int x = 0; x < w; x++) {
				int[] tile = tiles[(y / artPx) * per + x / artPx];
				out[y * w + x] = tile == null ? -1 : tile[(y % artPx) * artPx + x % artPx];
			}
		}
		return out;
	}

	/**
	 * Cells no rule makes a unit (solid, low, not lone, not a tree, not probed): a dump in dump's format (no variants, so the
	 * depth run takes groundMask, the pixels that differ from the floor tile the planner puts under the cell, as foreground),
	 * with how often each stands, for the depth run and the review (section 13).
	 */
	static void candidates(Path bundleDir, Path out) throws Exception {
		FittedShapes.setEnabled(false);
		ExportBundle bundle = ExportBundle.open(bundleDir);
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		ArtSource art = FrontEnds.artSource(bundle);
		Map<String, int[]> skipped = new java.util.concurrent.ConcurrentHashMap<>();
		FittedShapes.collectSkipped(skipped);
		try {
			ShapePlanner.plan(front.describe(bundle), List.of(), ShapeParams.current(), Occupancy.NONE, new ArtUnitProbe(art));
		} finally {
			FittedShapes.collectSkipped(null);
		}
		JsonArray list = new JsonArray();
		for (Map.Entry<String, int[]> e : new TreeMap<>(skipped).entrySet()) {
			String key = e.getKey();
			int slash = key.lastIndexOf('/');
			String atlas = key.substring(0, slash);
			int[] ids = java.util.Arrays.stream(key.substring(slash + 1).split("-")).mapToInt(Integer::parseInt).toArray();
			int[][] tiles = tiles(art, atlas, ids);
			boolean complete = true;
			for (int[] t : tiles) {
				complete &= t != null;
			}
			if (!complete) {
				continue;
			}
			int[] shades = drawing(tiles, art.artPx());
			byte[] bytes = new byte[shades.length];
			for (int i = 0; i < shades.length; i++) {
				bytes[i] = (byte) shades[i];
			}
			JsonObject cell = new JsonObject();
			cell.addProperty("cell", key);
			cell.addProperty("atlas", atlas);
			cell.addProperty("uses", e.getValue()[0]);
			int ground = e.getValue()[1];
			if (e.getValue()[2] != 0) {
				cell.addProperty("raised", true);
			}
			int[][] floor = ground >= 0 ? tiles(art, atlas, new int[] {ground, ground, ground, ground}) : null;
			if (floor != null && floor[0] != null) {
				// the object: what differs from the floor drawn under it (a balloon over a market's paving)
				int[] under = drawing(floor, art.artPx());
				boolean[] drawn = new boolean[shades.length];
				for (int i = 0; i < shades.length; i++) {
					drawn[i] = shades[i] >= 0 && shades[i] != under[i];
				}
				cell.addProperty("ground", ground);
				cell.addProperty("groundMask", bits(drawn));
			}
			cell.addProperty("shades", Base64.getEncoder().encodeToString(bytes));
			cell.addProperty("hullMask", bits(Hulls.mask(shades, new boolean[shades.length], W, W)));
			cell.add("variants", new JsonArray());
			list.add(cell);
		}
		JsonObject root = new JsonObject();
		root.addProperty("game", bundle.game());
		root.addProperty("artPx", art.artPx());
		root.add("cells", list);
		Files.writeString(out, new GsonBuilder().create().toJson(root) + "\n", StandardCharsets.UTF_8);
		System.out.println("candidates: " + bundle.game() + " " + list.size() + " cells, " + skipped.values().stream().mapToInt(v -> v[0]).sum() + " placements");
	}

	/** Every cell of one map as the planner sees it with the jar's table on (UnitLocations.describe). */
	static void cells(Path bundleDir, String mapId, Path out) throws Exception {
		ExportBundle bundle = ExportBundle.open(bundleDir);
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		dev.kantobridge.scene.Scene scene = front.describe(bundle);
		Map<String, String> why = new java.util.concurrent.ConcurrentHashMap<>();
		FittedShapes.collectWhy(why);
		ShapePlan plan;
		try {
			plan = ShapePlanner.plan(scene, List.of(), ShapeParams.current(), Occupancy.NONE, new ArtUnitProbe(FrontEnds.artSource(bundle)));
		} finally {
			FittedShapes.collectWhy(null);
		}
		JsonArray rows = new JsonArray();
		dev.kantobridge.builder.shape.UnitLocations.describe(scene, plan, mapId, why).forEach(rows::add);
		Files.writeString(out, new GsonBuilder().create().toJson(rows) + "\n", StandardCharsets.UTF_8);
		System.out.println("cells: " + mapId + " " + rows.size());
	}

	static void locate(Path bundleDir, Path out) throws Exception {
		ExportBundle bundle = ExportBundle.open(bundleDir);
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		if (front == null) {
			throw new IllegalArgumentException("no front end for " + bundle.game());
		}
		FittedShapes.setEnabled(false);
		dev.kantobridge.scene.Scene scene = front.describe(bundle);
		ShapePlan plan = ShapePlanner.plan(scene, List.of(), ShapeParams.current(), Occupancy.NONE, new ArtUnitProbe(FrontEnds.artSource(bundle)));
		JsonObject root = new JsonObject();
		dev.kantobridge.builder.shape.UnitLocations.locate(scene, plan, null).forEach(root::add);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n", StandardCharsets.UTF_8);
		System.out.println("locate: " + bundle.game() + " " + root.size() + " cells");
	}

	static void applied(Path bundleDir) throws Exception {
		ExportBundle bundle = ExportBundle.open(bundleDir);
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		if (front == null) {
			throw new IllegalArgumentException("no front end for " + bundle.game());
		}
		FittedShapes.setEnabled(true);
		ShapePlan plan = ShapePlanner.plan(front.describe(bundle), List.of(), ShapeParams.current(), Occupancy.NONE,
				new ArtUnitProbe(FrontEnds.artSource(bundle)));
		System.out.println("applied: " + bundle.game() + " " + plan.unitTally());
		// What the units became with this art: a voxel shape over the quad budget falls back to the other builders.
		Map<String, Integer> kinds = new TreeMap<>();
		for (CellUnits.Unit unit : ShapeTable.build(plan.index(), FrontEnds.artSource(bundle)).units().values()) {
			kinds.merge(unit.kind().id(), 1, Integer::sum);
		}
		System.out.println("built: " + bundle.game() + " " + kinds);
	}

	static void dump(Path bundleDir, Path out) throws Exception {
		Export export = plan(bundleDir);
		Map<String, JsonObject> cells = new TreeMap<>();
		for (Planned p : export.units()) {
			JsonObject cell = cells.get(p.cell());
			if (cell == null) {
				int[][] tiles = tiles(export.art(), p.atlas(), p.tiles());
				int[] shades = drawing(tiles, export.art().artPx());
				boolean complete = true;
				for (int[] tile : tiles) {
					complete &= tile != null;
				}
				cell = new JsonObject();
				cell.addProperty("cell", p.cell());
				cell.addProperty("atlas", p.atlas());
				JsonArray ids = new JsonArray();
				for (int id : p.tiles()) {
					ids.add(id);
				}
				cell.add("tiles", ids);
				byte[] bytes = new byte[shades.length];
				for (int i = 0; i < shades.length; i++) {
					bytes[i] = (byte) shades[i];
				}
				cell.addProperty("shades", Base64.getEncoder().encodeToString(bytes));
				if (complete) {
					cell.addProperty("hullMask", bits(Hulls.mask(shades, new boolean[shades.length], W, W)));
				}
				cell.add("variants", new JsonArray());
				cells.put(p.cell(), cell);
			}
			JsonObject variant = new JsonObject();
			variant.addProperty("variant", p.variant());
			variant.addProperty("kind", p.unit().kind().id());
			variant.addProperty("match", round(p.unit().match()));
			variant.addProperty("cover", round(p.unit().cover()));
			variant.addProperty("foreground", bits(p.unit().foreground()));
			VoxelGrid grid = occupancy(p.unit());
			if (grid != null) {
				variant.addProperty("voxels", bits(grid));
			}
			cell.getAsJsonArray("variants").add(variant);
		}
		JsonObject root = new JsonObject();
		root.addProperty("game", export.game());
		root.addProperty("artPx", export.art().artPx());
		JsonArray list = new JsonArray();
		cells.values().forEach(list::add);
		root.add("cells", list);
		JsonArray structs = BuildingFit.dumpAll(export);
		root.add("structs", structs);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, new GsonBuilder().create().toJson(root) + "\n", StandardCharsets.UTF_8);
		System.out.println("dump: " + export.game() + " " + cells.size() + " cells, " + export.units().size() + " units, " + structs.size()
				+ " buildings");
	}

	static void fit(Path bundleDir, Path targetsFile, Path out) throws Exception {
		Export export = plan(bundleDir);
		JsonObject targets = JsonParser.parseString(Files.readString(targetsFile, StandardCharsets.UTF_8)).getAsJsonObject();
		Map<String, List<Planned>> byCell = new TreeMap<>();
		for (Planned p : export.units()) {
			byCell.computeIfAbsent(p.cell(), k -> new ArrayList<>()).add(p);
		}
		JsonObject table = new JsonObject();
		JsonArray report = new JsonArray();
		int better = 0;
		int fitted = 0;
		for (Map.Entry<String, List<Planned>> e : byCell.entrySet()) {
			JsonElement t = targets.get(e.getKey());
			if (t == null || !t.isJsonPrimitive()) {
				continue;
			}
			boolean[] target = unbits(t.getAsString(), W * W * W);
			Planned first = e.getValue().get(0);
			int[][] tiles = tiles(export.art(), first.atlas(), first.tiles());
			// Today's pick: the best of its variants (a cell's variants differ only by context).
			double today = -1;
			String todayKind = null;
			for (Planned p : e.getValue()) {
				double iou = iou(target, occupancy(p.unit()));
				if (iou > today) {
					today = iou;
					todayKind = p.unit().kind().id();
				}
			}
			Candidates.Best best = Candidates.best(tiles, e.getValue().stream().map(Planned::variant).toList(), target, export.art().artPx());
			fitted++;
			JsonObject row = new JsonObject();
			row.addProperty("cell", e.getKey());
			row.addProperty("today", todayKind);
			row.addProperty("today_iou", round(today));
			row.addProperty("fit", best.label());
			row.addProperty("fit_iou", round(best.iou()));
			row.addProperty("candidates", best.tried());
			if (best.voxels() != null) {
				row.addProperty("fit_voxels", best.voxels());
			}
			report.add(row);
			// The table keeps a fit only where it beats today's pick by a clear margin; elsewhere today's pick stands.
			if (best.iou() >= today + Candidates.MARGIN && best.iou() >= Candidates.MIN_IOU) {
				table.add(e.getKey(), best.entry());
				better++;
			}
		}
		JsonObject structs = new JsonObject();
		int structsFitted = 0;
		int structsKept = 0;
		for (Building b : export.buildings()) {
			JsonElement t = targets.get(b.key());
			if (t == null || !t.isJsonPrimitive()) {
				continue;
			}
			BuildingFit.Fit fit = BuildingFit.fit(b, export.art(), t.getAsString());
			if (fit == null) {
				continue;
			}
			structsFitted++;
			report.add(BuildingFit.row(b.key(), fit));
			if (fit.iou() >= fit.todayIou() + Candidates.MARGIN && fit.iou() >= Candidates.MIN_IOU) {
				structs.add(b.key(), BuildingFit.entry(fit));
				structsKept++;
			}
		}
		JsonObject root = new JsonObject();
		root.addProperty("kb_fitted_shapes", 1);
		root.addProperty("game", export.game());
		root.addProperty("planner", ShapeParams.current().planner());
		root.add("cells", table);
		// Proposed only: the planner reads "structs", and a model can't tell a roof row from a wall row (docs/specs/
		// FITTED_SHAPES.md §5), so a building's fit waits for someone to look at it.
		root.add("structs_proposed", structs);
		JsonObject summary = new JsonObject();
		summary.addProperty("cells", byCell.size());
		summary.addProperty("fitted", fitted);
		summary.addProperty("kept", better);
		summary.addProperty("buildings", export.buildings().size());
		summary.addProperty("buildings_fitted", structsFitted);
		summary.addProperty("buildings_kept", structsKept);
		root.add("summary", summary);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n", StandardCharsets.UTF_8);
		Path reportFile = out.resolveSibling(out.getFileName().toString().replace(".json", "") + ".report.json");
		Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardCharsets.UTF_8);
		System.out.println("fit: " + export.game() + " " + fitted + " cells fitted, " + better + " kept (of " + byCell.size() + "); "
				+ structsFitted + " buildings fitted, " + structsKept + " kept (of " + export.buildings().size() + ")");
	}

	/** A unit's occupancy: its voxels, or every voxel for the column version; null for a flat unit (nothing stands). */
	static @Nullable VoxelGrid occupancy(CellUnits.Unit unit) {
		if (unit.voxels() != null) {
			return unit.voxels();
		}
		if (unit.kind() == CellUnits.Kind.COLUMNS) {
			VoxelGrid all = new VoxelGrid(W, W, W, 8);
			for (int y = 0; y < W; y++) {
				for (int z = 0; z < W; z++) {
					for (int x = 0; x < W; x++) {
						all.set(x, y, z, 0);
					}
				}
			}
			return all;
		}
		return null;
	}

	/**
	 * Voxel IoU of a target with a grid (0 for none), the target moved north or south so that both have the same mean
	 * depth: the model's depth is centred in the cell, while a builder may stand its shape anywhere along it (a standee's
	 * slab), and only the shape is compared.
	 */
	static double iou(boolean[] target, @Nullable VoxelGrid grid) {
		int shift = grid == null ? 0 : (int) Math.round(meanZ(grid) - meanZ(target));
		int targetCount = 0;
		for (boolean a : target) {
			targetCount += a ? 1 : 0;
		}
		int both = 0;
		int gridCount = 0;
		for (int y = 0; y < W && grid != null; y++) {
			for (int z = 0; z < W; z++) {
				for (int x = 0; x < W; x++) {
					if (!grid.solid(x, y, z)) {
						continue;
					}
					gridCount++;
					int tz = z - shift;
					both += tz >= 0 && tz < W && target[(y * W + tz) * W + x] ? 1 : 0;
				}
			}
		}
		int either = targetCount + gridCount - both;
		return either == 0 ? 0 : (double) both / either;
	}

	static double meanZ(boolean[] voxels) {
		long sum = 0;
		int n = 0;
		for (int i = 0; i < voxels.length; i++) {
			if (voxels[i]) {
				sum += (i / W) % W;
				n++;
			}
		}
		return n == 0 ? W / 2.0 : (double) sum / n;
	}

	static double meanZ(VoxelGrid grid) {
		long sum = 0;
		int n = 0;
		for (int y = 0; y < W; y++) {
			for (int z = 0; z < W; z++) {
				for (int x = 0; x < W; x++) {
					if (grid.solid(x, y, z)) {
						sum += z;
						n++;
					}
				}
			}
		}
		return n == 0 ? W / 2.0 : (double) sum / n;
	}

	static String bits(VoxelGrid grid) {
		boolean[] out = new boolean[W * W * W];
		for (int y = 0; y < W; y++) {
			for (int z = 0; z < W; z++) {
				for (int x = 0; x < W; x++) {
					out[(y * W + z) * W + x] = grid.solid(x, y, z);
				}
			}
		}
		return bits(out);
	}

	static String bits(boolean[] values) {
		byte[] bytes = new byte[(values.length + 7) / 8];
		for (int i = 0; i < values.length; i++) {
			if (values[i]) {
				bytes[i >> 3] |= (byte) (1 << (i & 7));
			}
		}
		return Base64.getEncoder().encodeToString(bytes);
	}

	static boolean[] unbits(String text, int n) {
		byte[] bytes = Base64.getDecoder().decode(text);
		boolean[] out = new boolean[n];
		for (int i = 0; i < n && (i >> 3) < bytes.length; i++) {
			out[i] = (bytes[i >> 3] >> (i & 7) & 1) != 0;
		}
		return out;
	}

	static double round(double value) {
		return Math.round(value * 1000) / 1000.0;
	}
}
