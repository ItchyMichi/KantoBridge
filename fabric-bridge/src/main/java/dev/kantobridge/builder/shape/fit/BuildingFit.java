package dev.kantobridge.builder.shape.fit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.builder.shape.geom.ShapeOverrides;
import dev.kantobridge.builder.shape.geom.TemplateRule;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import dev.kantobridge.scene.ArtSource;
import java.util.Base64;
import org.jetbrains.annotations.Nullable;

/**
 * Buildings in the fit (docs/specs/FITTED_SHAPES.md §5): a V7 template's one setting is its roof rows, the top tile rows
 * of its drawing folded back as roof ({@link TemplateRule}). A template's volume is known, so its target is the 3D model
 * scaled into that box: {@code w × 8} wide, its tallest column's height and {@code d × 8} deep, voxels indexed
 * {@code (y * depth + z) * width + x}.
 */
final class BuildingFit {
	/** The roof rows a fit tries; 0 is the column version (no band split). */
	static final int MAX_ROOF_ROWS = 6;

	private BuildingFit() {
	}

	/** A building's dump: its key, size, today's pick and its voxels, and its drawing's shades (ROM-derived). */
	static JsonObject dump(FitTool.Building b, ArtSource art) {
		Templates.Entry entry = b.entry();
		int artPx = art.artPx();
		int w = entry.w() * artPx;
		int d = entry.d() * artPx;
		byte[] shades = new byte[w * d];
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < w; x++) {
				int tile = entry.tile(x / artPx, y / artPx);
				int[] s = tile < 0 ? null : art.tileShades(b.atlas(), tile);
				shades[y * w + x] = (byte) (s == null ? -1 : s[(y % artPx) * artPx + x % artPx]);
			}
		}
		JsonObject out = new JsonObject();
		out.addProperty("struct", b.key());
		out.addProperty("w", entry.w());
		out.addProperty("d", entry.d());
		out.addProperty("height", entry.height());
		out.addProperty("kind", b.template().kind().id());
		out.addProperty("roofRows", b.template().roofRows());
		out.addProperty("shades", Base64.getEncoder().encodeToString(shades));
		VoxelGrid today = occupancy(entry, b.template(), artPx);
		out.addProperty("voxels", bits(today));
		return out;
	}

	/** The fit's row: the best roof rows against the target, today's pick beside it; the table entry when it is kept. */
	record Fit(int roofRows, double iou, int todayRows, double todayIou, String todayKind) {
	}

	static @Nullable Fit fit(FitTool.Building b, ArtSource art, String targetBits) {
		Templates.Entry entry = b.entry();
		int artPx = art.artPx();
		int w = entry.w() * artPx;
		int h = entry.height() * artPx;
		int d = entry.d() * artPx;
		boolean[] target = FitTool.unbits(targetBits, w * h * d);
		double today = iou(target, occupancy(entry, b.template(), artPx));
		int best = -1;
		double bestIou = -1;
		for (int rows = 0; rows <= MAX_ROOF_ROWS; rows++) {
			int r = rows;
			TemplateRule.Template t = TemplateRule.build(entry, b.atlas(), art, new ShapeOverrides() {
				@Override
				public int roofRows(String atlas, String sig) {
					return r;
				}
			});
			if (rows > 0 && t.kind() != TemplateRule.Kind.BUILDING) {
				continue; // fails its gates
			}
			double iou = iou(target, occupancy(entry, t, artPx));
			if (iou > bestIou) {
				bestIou = iou;
				best = rows;
			}
		}
		return best < 0 ? null : new Fit(best, bestIou, b.template().roofRows(), today, b.template().kind().id());
	}

	/** A template's voxels: a building's grid, else its columns as solid blocks (each position as high as its keys). */
	static VoxelGrid occupancy(Templates.Entry entry, TemplateRule.Template t, int artPx) {
		if (t.voxels() != null) {
			return t.voxels();
		}
		VoxelGrid grid = new VoxelGrid(entry.w() * artPx, entry.height() * artPx, entry.d() * artPx, artPx);
		for (int gy = 0; gy < entry.d(); gy++) {
			for (int gx = 0; gx < entry.w(); gx++) {
				int blocks = entry.blocks(gx, gy);
				for (int y = 0; y < blocks * artPx; y++) {
					for (int z = gy * artPx; z < (gy + 1) * artPx; z++) {
						for (int x = gx * artPx; x < (gx + 1) * artPx; x++) {
							grid.set(x, y, z, 0);
						}
					}
				}
			}
		}
		return grid;
	}

	static double iou(boolean[] target, VoxelGrid grid) {
		int w = grid.width();
		int d = grid.depth();
		int both = 0;
		int either = 0;
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < d; z++) {
				for (int x = 0; x < w; x++) {
					boolean a = target[(y * d + z) * w + x];
					boolean b = grid.solid(x, y, z);
					both += a && b ? 1 : 0;
					either += a || b ? 1 : 0;
				}
			}
		}
		return either == 0 ? 0 : (double) both / either;
	}

	static String bits(VoxelGrid grid) {
		int w = grid.width();
		int d = grid.depth();
		boolean[] out = new boolean[w * grid.height() * d];
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < d; z++) {
				for (int x = 0; x < w; x++) {
					out[(y * d + z) * w + x] = grid.solid(x, y, z);
				}
			}
		}
		return FitTool.bits(out);
	}

	static JsonObject row(String key, Fit fit) {
		JsonObject row = new JsonObject();
		row.addProperty("struct", key);
		row.addProperty("today", fit.todayKind() + (fit.todayRows() >= 0 ? ":" + fit.todayRows() : ""));
		row.addProperty("today_iou", FitTool.round(fit.todayIou()));
		row.addProperty("fit", "roofRows:" + fit.roofRows());
		row.addProperty("fit_iou", FitTool.round(fit.iou()));
		return row;
	}

	static JsonElement entry(Fit fit) {
		JsonObject out = new JsonObject();
		out.addProperty("roofRows", fit.roofRows());
		out.addProperty("iou", FitTool.round(fit.iou()));
		return out;
	}

	/** The structs' part of a dump. */
	static JsonArray dumpAll(FitTool.Export export) {
		JsonArray out = new JsonArray();
		for (FitTool.Building b : export.buildings()) {
			if (b.entry().height() <= 0) {
				continue;
			}
			out.add(dump(b, export.art()));
		}
		return out;
	}
}
