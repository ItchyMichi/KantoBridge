package dev.kantobridge.builder.shape.fit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The builders and settings a cell's fit tries (docs/specs/FITTED_SHAPES.md §3), each as the profile's class and
 * parameters ({@link CellUnits.Context}): the round hull ({@code cylinder}); the stump's cap rows; the can's cap, base,
 * height, well and taper (a lathe: the drawing's rows turned round, stretched and narrowed toward the floor); the standee
 * pools by depth ({@code billboard} 10, {@code prop} 5, {@code signpost} 2, {@code cutout} 1 pixel); and the column
 * version. Every candidate is built by the same code the game runs, with its gates: one that fails them is not a
 * candidate.
 */
final class Candidates {
	/** A fit replaces today's pick only when its IoU is at least this much higher. */
	static final double MARGIN = 0.05;
	/** ... and at least this high: below it the model and every builder disagree too much for the fit to mean anything. */
	static final double MIN_IOU = 0.4;

	record Candidate(String cls, List<Integer> params) {
		String label() {
			StringBuilder out = new StringBuilder(this.cls);
			for (int p : this.params) {
				out.append(out.length() == this.cls.length() ? ":" : ",").append(p);
			}
			return out.toString();
		}

		JsonObject entry() {
			JsonObject out = new JsonObject();
			out.addProperty("class", this.cls);
			if (!this.params.isEmpty()) {
				JsonArray k = new JsonArray();
				this.params.forEach(k::add);
				out.add("k", k);
			}
			return out;
		}
	}

	record Best(Candidate candidate, double iou, int tried) {
		String label() {
			return this.candidate.label();
		}

		JsonObject entry() {
			JsonObject out = this.candidate.entry();
			out.addProperty("iou", FitTool.round(this.iou));
			return out;
		}
	}

	private Candidates() {
	}

	static List<Candidate> all() {
		List<Candidate> out = new ArrayList<>();
		out.add(new Candidate("cylinder", List.of()));
		for (int cap = 2; cap <= 10; cap += 2) {
			out.add(new Candidate("stump", List.of(cap)));
		}
		for (int cap : new int[] {0, 3, 6, 9}) {
			for (int base : new int[] {0, 3, 6}) {
				for (int height = 4; height <= 16; height += 2) {
					for (int well : new int[] {0, 4, 8}) {
						for (int taper = 0; taper <= 8; taper += 2) {
							out.add(new Candidate("can", List.of(cap, base, height, well, taper)));
						}
					}
				}
			}
		}
		for (String pool : List.of("billboard", "prop", "signpost", "cutout")) {
			out.add(new Candidate(pool, List.of()));
		}
		out.add(new Candidate("columns", List.of()));
		return out;
	}

	/** The candidate whose voxels best match the target, over the cell's planned variants (contexts). */
	static Best best(int @Nullable [][] tiles, List<String> variants, boolean[] target, int artPx) {
		Candidate best = null;
		double bestIou = -1;
		int tried = 0;
		for (Candidate c : all()) {
			for (String variant : variants) {
				CellUnits.Unit unit = build(tiles, variant, c, artPx);
				if (unit == null) {
					continue;
				}
				tried++;
				double iou = FitTool.iou(target, FitTool.occupancy(unit));
				if (iou > bestIou) {
					bestIou = iou;
					best = c;
				}
			}
		}
		return new Best(best, bestIou, tried);
	}

	/** A candidate built in a variant's context, or null when it fails its gates. */
	static CellUnits.@Nullable Unit build(int @Nullable [][] tiles, String variant, Candidate c, int artPx) {
		CellUnits.Context base = CellUnits.Context.parse(variant);
		if (c.cls().equals("columns")) {
			return CellUnits.build(tiles, base, 2, artPx, CellUnits.MAX_QUADS, "columns", 0);
		}
		CellUnits.Context context = new CellUnits.Context(base.flatSides(), base.indoor(), base.repeatsVertically(), base.sign(), base.tree(), c.cls(),
				base.background(), c.params());
		CellUnits.Unit unit = CellUnits.build(tiles, context, 2, artPx);
		return unit.failed() != null || unit.kind() == CellUnits.Kind.COLUMNS ? null : unit;
	}
}
