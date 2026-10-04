package dev.kantobridge.builder.shape;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The headless build's view of a shape plan (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6, §5.7): the geometry built as a
 * client would, from the build's own export (pixels for tufts and units, V4), its quad count, the self-check of every
 * placed map and of every unit, and what the structures became. Numbers, kinds, map ids and signature hashes only leave
 * it ({@link #toJson()} is {@code shape_selfcheck.json}).
 */
public final class ShapeReport {
	/** Structure kinds of the build report ({@code structures_by_kind}, §4.10; {@code flat}: all-void units). */
	public static final List<String> STRUCTURE_KINDS = List.of("volume", "object", "hull", "flat", "building", "fallback");

	/**
	 * One unit as the table built it: its signature's hash (16 hex digits), the kind it became, the candidate that failed
	 * its gate (or null), its scores and the most quads of one of its blocks, and how many cells of the build show it.
	 */
	public record UnitRow(String signature, String kind, @Nullable String failed, double match, double cover, int quads, long cells) {
	}

	private final ShapeTable table;
	private final long tableMs;
	private final List<SelfCheck.Scores> scores;
	private final long checkMs;
	private final QuadCounter.Stats quads;
	private final long quadMs;
	private final Map<String, Long> structures;
	private final List<UnitRow> units;

	private ShapeReport(ShapeTable table, long tableMs, List<SelfCheck.Scores> scores, long checkMs, QuadCounter.Stats quads, long quadMs,
			Map<String, Long> structures, List<UnitRow> units) {
		this.table = table;
		this.tableMs = tableMs;
		this.scores = List.copyOf(scores);
		this.checkMs = checkMs;
		this.quads = quads;
		this.quadMs = quadMs;
		this.structures = Collections.unmodifiableMap(new LinkedHashMap<>(structures));
		this.units = List.copyOf(units);
	}

	/** Builds the table with {@code art}, checks every placed map and unit, and counts the quads the planned world draws. */
	public static ShapeReport of(WorldPlan world, ArtSource art) {
		Scene scene = world.scene();
		ShapePlan plan = world.shapes();
		if (scene == null || plan == null) {
			throw new IllegalArgumentException("a plan without shapes");
		}
		ShapeReport checked = of(scene, plan, world.index().maps().values(), art);
		long t0 = System.nanoTime();
		QuadCounter.Stats quads = QuadCounter.count(world, plan.map(), checked.table);
		return new ShapeReport(checked.table, checked.tableMs, checked.scores, checked.checkMs, quads, (System.nanoTime() - t0) / 1_000_000L,
				checked.structures, checked.units);
	}

	/** Builds the table with {@code art} and checks every placed map and unit (no quad count). */
	public static ShapeReport of(Scene scene, ShapePlan plan, Collection<MapPlacement> placements, ArtSource art) {
		long t0 = System.nanoTime();
		ShapeTable table = ShapeTable.build(plan.index(), art);
		long tableMs = (System.nanoTime() - t0) / 1_000_000L;
		long t1 = System.nanoTime();
		List<SelfCheck.Scores> scores = new ArrayList<>();
		Map<String, Long> structures = new LinkedHashMap<>();
		for (String kind : STRUCTURE_KINDS) {
			structures.put(kind, 0L);
		}
		Map<String, long[]> cells = new TreeMap<>();
		Map<String, CellUnits.Unit> used = new TreeMap<>();
		for (MapPlacement placement : placements) {
			SceneMap map = scene.map(placement.id());
			ShapePlanner.MapShapes shapes = plan.shapes(placement.id());
			if (map == null || shapes == null) {
				continue;
			}
			scores.add(SelfCheck.score(map.id(), new Reference(map, scene.artPx(), shapes, table), map.atlas(),
					new PlannedGeometry(map, shapes, plan.index(), table), scene.artPx()));
			for (Units.Cell cell : shapes.units()) {
				countUnit(table, map.atlas(), cell.key(), structures, cells, used);
			}
			structures.merge("volume", (long) volumes(map, shapes), Long::sum);
		}
		for (ShapePlan.VoidUnit unit : plan.voidUnits().values()) {
			SceneMap map = scene.map(unit.owner());
			if (map != null) {
				countUnit(table, map.atlas(), unit.key(), structures, cells, used);
			}
		}
		List<UnitRow> rows = new ArrayList<>();
		for (Map.Entry<String, CellUnits.Unit> e : used.entrySet()) {
			CellUnits.Unit unit = e.getValue();
			rows.add(new UnitRow(Hashes.sha256(e.getKey()).substring(0, 16), unit.kind().id(), unit.failed() == null ? null : unit.failed().id(),
					unit.match(), unit.cover(), unit.maxQuads(), cells.get(e.getKey())[0]));
		}
		rows.sort(Comparator.comparing(UnitRow::signature));
		scores.sort(Comparator.comparing(SelfCheck.Scores::id));
		return new ShapeReport(table, tableMs, scores, (System.nanoTime() - t1) / 1_000_000L, QuadCounter.Stats.EMPTY, 0, structures, rows);
	}

	/** Counts one cell of a unit under the kind its geometry became (a unit drawn as columns is a fallback). */
	private static void countUnit(ShapeTable table, String atlas, ShapeKey key, Map<String, Long> structures, Map<String, long[]> cells,
			Map<String, CellUnits.Unit> used) {
		CellUnits.Unit unit = table.unit(atlas, key);
		if (unit == null) {
			return;
		}
		String kind = unit.kind() == CellUnits.Kind.COLUMNS ? "fallback" : unit.kind().id();
		structures.merge(kind, 1L, Long::sum);
		String signature = atlas + "|" + key.number(0) + "," + key.number(1) + "," + key.number(2) + "," + key.number(3) + "|" + key.variant();
		cells.computeIfAbsent(signature, k -> new long[1])[0]++;
		used.put(signature, unit);
	}

	/** Tier A structures of a map: the 4-connected regions of its solid tiles that no unit took. */
	private static int volumes(SceneMap map, ShapePlanner.MapShapes shapes) {
		boolean[] solid = Structures.solidTiles(map);
		int cols = map.artCols();
		int per = map.perCell();
		for (Units.Cell cell : shapes.units()) {
			for (int dy = 0; dy < per; dy++) {
				for (int dx = 0; dx < per; dx++) {
					solid[(cell.cy() * per + dy) * cols + cell.cx() * per + dx] = false;
				}
			}
		}
		int regions = 0;
		for (int r : Structures.regions(solid, cols, map.artRows())) {
			regions = Math.max(regions, r + 1);
		}
		return regions;
	}

	/** What the structures of the placed maps became, by kind ({@link #STRUCTURE_KINDS}): regions and unit cells. */
	public Map<String, Long> structures() {
		return this.structures;
	}

	/** Every unit the placed world shows, by signature hash. */
	public List<UnitRow> units() {
		return this.units;
	}

	/** Unit cells drawn as columns because their hull or object failed its gate. */
	public long unitsFailed() {
		return this.units.stream().filter(u -> u.failed() != null && CellUnits.Kind.COLUMNS.id().equals(u.kind())).mapToLong(UnitRow::cells).sum();
	}

	/** The quads the planned world draws, per shaped block and per section. */
	public QuadCounter.Stats quads() {
		return this.quads;
	}

	public long quadMs() {
		return this.quadMs;
	}

	public ShapeTable table() {
		return this.table;
	}

	public long tableMs() {
		return this.tableMs;
	}

	public long checkMs() {
		return this.checkMs;
	}

	public List<SelfCheck.Scores> scores() {
		return this.scores;
	}

	public long holes() {
		return this.scores.stream().mapToLong(SelfCheck.Scores::holes).sum();
	}

	public double groundMatchMin() {
		return this.scores.stream().mapToDouble(SelfCheck.Scores::groundMatch).min().orElse(1.0);
	}

	public double structureMatchMin() {
		return this.scores.stream().mapToDouble(SelfCheck.Scores::structureMatch).min().orElse(1.0);
	}

	/** The 5th percentile of the maps' structure match (nearest rank). */
	public double structureMatchP05() {
		double[] sorted = this.scores.stream().mapToDouble(SelfCheck.Scores::structureMatch).sorted().toArray();
		if (sorted.length == 0) {
			return 1.0;
		}
		int rank = (int) Math.ceil(0.05 * sorted.length);
		return sorted[Math.max(0, rank - 1)];
	}

	public double occludedGroundMax() {
		return this.scores.stream().mapToDouble(SelfCheck.Scores::occludedGround).max().orElse(0.0);
	}

	public long backFaces() {
		return this.scores.stream().mapToLong(SelfCheck.Scores::backFaces).sum();
	}

	/** {@code shape_selfcheck.json}: per map id, its five scores; per unit signature hash, what it became. */
	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("version", 2);
		JsonObject maps = new JsonObject();
		for (SelfCheck.Scores s : this.scores) {
			JsonObject one = new JsonObject();
			one.addProperty("holes", s.holes());
			one.addProperty("ground_match", round4(s.groundMatch()));
			one.addProperty("structure_match", round4(s.structureMatch()));
			one.addProperty("occluded_ground", round4(s.occludedGround()));
			one.addProperty("back_faces", s.backFaces());
			maps.add(s.id(), one);
		}
		root.add("maps", maps);
		JsonObject units = new JsonObject();
		for (UnitRow u : this.units) {
			JsonObject one = new JsonObject();
			one.addProperty("kind", u.kind());
			if (u.failed() != null) {
				one.addProperty("failed", u.failed());
			}
			one.addProperty("match", round4(u.match()));
			one.addProperty("cover", round4(u.cover()));
			one.addProperty("quads", u.quads());
			one.addProperty("cells", u.cells());
			units.add(u.signature(), one);
		}
		root.add("units", units);
		return root;
	}

	public String toText() {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this.toJson()) + "\n";
	}

	private static double round4(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}
}
