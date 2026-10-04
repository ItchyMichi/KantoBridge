package dev.kantobridge.builder.shape;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The headless build's view of a shape plan (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6, §5.7): the geometry built as a
 * client would (V2's cubes need no pixel), its quad count, and the self-check of every placed map. Numbers and map ids
 * only leave it ({@link #toJson()} is {@code shape_selfcheck.json}).
 */
public final class ShapeReport {
	private final ShapeTable table;
	private final long tableMs;
	private final List<SelfCheck.Scores> scores;
	private final long checkMs;

	private ShapeReport(ShapeTable table, long tableMs, List<SelfCheck.Scores> scores, long checkMs) {
		this.table = table;
		this.tableMs = tableMs;
		this.scores = List.copyOf(scores);
		this.checkMs = checkMs;
	}

	/** Builds the table and checks every placed map. */
	public static ShapeReport of(Scene scene, ShapePlan plan, Collection<MapPlacement> placements) {
		long t0 = System.nanoTime();
		ShapeTable table = ShapeTable.build(plan.index(), ArtSource.NONE);
		long tableMs = (System.nanoTime() - t0) / 1_000_000L;
		long t1 = System.nanoTime();
		List<SelfCheck.Scores> scores = new ArrayList<>();
		for (MapPlacement placement : placements) {
			SceneMap map = scene.map(placement.id());
			ShapePlanner.MapShapes shapes = plan.shapes(placement.id());
			if (map == null || shapes == null) {
				continue;
			}
			scores.add(SelfCheck.score(map.id(), new Reference(map, scene.artPx()), map.atlas(),
					new PlannedGeometry(map, shapes, plan.index(), table), scene.artPx()));
		}
		scores.sort(Comparator.comparing(SelfCheck.Scores::id));
		return new ShapeReport(table, tableMs, scores, (System.nanoTime() - t1) / 1_000_000L);
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

	/** {@code shape_selfcheck.json}: per map id, its five scores. */
	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
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
		return root;
	}

	public String toText() {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(this.toJson()) + "\n";
	}

	private static double round4(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}
}
