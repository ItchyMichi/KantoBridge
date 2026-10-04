package dev.kantobridge.client.shape.vox;

import dev.kantobridge.builder.shape.geom.QuadMerger;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import java.util.List;
import java.util.Map;

/**
 * A {@code .vox} model as quads (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10). Axes: vox z → up, vox x → east, vox y → north,
 * so the face seen from MagicaVoxel's default front looks south, the side the game draws. {@code turn} (0, 90, 180 or
 * 270 degrees, clockwise seen from above) and {@code flip} (east-west mirror, applied first) correct a model built the
 * other way round. The model's minimum corner sits at the target's north-west-bottom corner. Scale: 8 voxels per block
 * (one Game Boy pixel each) or 16 (one 1/16 block); a 32-per-block model is halved (each 2×2×2 group becomes one voxel of
 * its most frequent colour where at least half of it is filled), since quads live on the 1/16 grid. Its shell is merged
 * into quads of one colour and sliced per block ({@link QuadMerger}); a quad's texture is its colour index. Pure.
 */
public final class VoxMesher {
	/**
	 * A meshed model.
	 *
	 * @param bw      blocks east
	 * @param bh      blocks up
	 * @param bd      blocks south
	 * @param blocks  per block ({@link QuadMerger#blockKey}), its quads; a quad's texture is a colour index (1..255)
	 * @param palette the model's colours (ARGB) by index
	 */
	public record Mesh(int bw, int bh, int bd, Map<Integer, List<ShapeQuad>> blocks, int[] palette) {
		/** The most quads one block holds. */
		public int maxQuads() {
			int max = 0;
			for (List<ShapeQuad> quads : this.blocks.values()) {
				max = Math.max(max, quads.size());
			}
			return max;
		}
	}

	private VoxMesher() {
	}

	/** Meshes a model at the target's corner. */
	public static Mesh mesh(VoxFile.Model model, int voxelsPerBlock, int turn, boolean flip) {
		return mesh(model, voxelsPerBlock, turn, flip, new int[3]);
	}

	/**
	 * Meshes a model, its minimum corner moved by {@code origin} voxels (x east, y up, z south; after turning).
	 *
	 * @throws IllegalArgumentException for a scale other than 8, 16 or 32, a turn not a multiple of 90, or an origin that
	 *                                  moves a voxel below the target's corner
	 */
	public static Mesh mesh(VoxFile.Model model, int voxelsPerBlock, int turn, boolean flip, int[] origin) {
		if (voxelsPerBlock != 8 && voxelsPerBlock != 16 && voxelsPerBlock != 32) {
			throw new IllegalArgumentException("voxelsPerBlock is 8, 16 or 32");
		}
		if (turn % 90 != 0) {
			throw new IllegalArgumentException("turn is a multiple of 90");
		}
		int steps = Math.floorMod(turn / 90, 4);
		// Our axes before turning: x east, y up, z south.
		int w = model.sx();
		int h = model.sz();
		int d = model.sy();
		int ow = steps % 2 == 0 ? w : d;
		int od = steps % 2 == 0 ? d : w;
		if (origin[0] < 0 || origin[1] < 0 || origin[2] < 0) {
			throw new IllegalArgumentException("the origin moves the model out of its target");
		}
		ow += origin[0];
		h += origin[1];
		od += origin[2];
		int[] colours = new int[ow * h * od];
		for (int i = 0; i < model.count(); i++) {
			int x = flip ? w - 1 - model.x(i) : model.x(i);
			int y = model.z(i);
			int z = d - 1 - model.y(i);
			int tx;
			int tz;
			switch (steps) {
				case 1 -> {
					tx = d - 1 - z;
					tz = x;
				}
				case 2 -> {
					tx = w - 1 - x;
					tz = d - 1 - z;
				}
				case 3 -> {
					tx = z;
					tz = w - 1 - x;
				}
				default -> {
					tx = x;
					tz = z;
				}
			}
			tx += origin[0];
			tz += origin[2];
			colours[((y + origin[1]) * od + tz) * ow + tx] = model.colour(i);
		}
		int scale = voxelsPerBlock;
		if (voxelsPerBlock == 32) {
			int[] halved = halve(colours, ow, h, od);
			colours = halved;
			ow = (ow + 1) / 2;
			h = (h + 1) / 2;
			od = (od + 1) / 2;
			scale = 16;
		}
		int bw = (ow + scale - 1) / scale;
		int bh = (h + scale - 1) / scale;
		int bd = (od + scale - 1) / scale;
		VoxelGrid grid = new VoxelGrid(bw * scale, bh * scale, bd * scale, scale);
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < od; z++) {
				for (int x = 0; x < ow; x++) {
					int colour = colours[(y * od + z) * ow + x];
					if (colour != 0) {
						grid.set(x, y, z, VoxelGrid.texel(colour, 0, 0));
					}
				}
			}
		}
		return new Mesh(bw, bh, bd, QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0), model.palette().clone());
	}

	/** Each 2×2×2 group as one voxel: its most frequent colour (ties: the lowest index), where at least half is filled. */
	static int[] halve(int[] colours, int w, int h, int d) {
		int hw = (w + 1) / 2;
		int hh = (h + 1) / 2;
		int hd = (d + 1) / 2;
		int[] out = new int[hw * hh * hd];
		int[] count = new int[256];
		for (int y = 0; y < hh; y++) {
			for (int z = 0; z < hd; z++) {
				for (int x = 0; x < hw; x++) {
					java.util.Arrays.fill(count, 0);
					int filled = 0;
					for (int dy = 0; dy < 2; dy++) {
						for (int dz = 0; dz < 2; dz++) {
							for (int dx = 0; dx < 2; dx++) {
								int sx = x * 2 + dx;
								int sy = y * 2 + dy;
								int sz = z * 2 + dz;
								if (sx < w && sy < h && sz < d) {
									int c = colours[(sy * d + sz) * w + sx];
									if (c != 0) {
										count[c]++;
										filled++;
									}
								}
							}
						}
					}
					if (filled >= 4) {
						int best = 0;
						for (int c = 1; c < 256; c++) {
							if (count[c] > count[best]) {
								best = c;
							}
						}
						out[(y * hd + z) * hw + x] = best;
					}
				}
			}
		}
		return out;
	}
}
