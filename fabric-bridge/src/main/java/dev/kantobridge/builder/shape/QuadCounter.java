package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.ChunkCodes;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.geom.FaceMasks;
import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.world.ShapeMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The quads the world draws (docs/specs/VOXEL_BLOCKS_PLAN.md §3.8), counted as the client draws them: a block's faces
 * on an opaque neighbour are culled (Minecraft), a shape's faces on a shaped neighbour by face masks
 * ({@link FaceMasks}); a carrier without a shape draws its plain cube (tile and shape carriers) or nothing. Pure; the
 * headless build counts its planned world with it, per shaped block and per 16×16×16 section.
 */
public final class QuadCounter {
	/** Quads per block counted exactly up to here; above, one bucket. */
	private static final int HISTOGRAM = 512;

	/** What the counter reads at a position. */
	public interface World {
		/** The planned {@link BlockCode} (air or unowned where nothing is planned). */
		int code(int x, int y, int z);

		/** The shape id, 0 for none. */
		int shapeId(int x, int y, int z);

		/** The id of the closed story art drawn at a position for this player (V5), 0 for none. */
		default int closedId(int x, int y, int z) {
			return 0;
		}
	}

	/**
	 * The counts.
	 *
	 * @param shapedBlocks positions with a shape
	 * @param shapeQuads   their quads, after culling
	 * @param sections     sections with at least one quad
	 * @param densest      the section with the most quads: its block coordinates (multiples of 16)
	 */
	public record Stats(long shapedBlocks, long shapeQuads, double meanBlock, int maxBlock, int p99Block, long sections, int sectionP50,
			int sectionP95, int sectionMax, int @Nullable [] densest) {
		public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, null);
	}

	private QuadCounter() {
	}

	/** True when the block {@code neighbour} hides the face of its neighbour that touches it from direction {@code face}. */
	static boolean occludes(int neighbour, int face) {
		int kind = BlockCode.kind(neighbour);
		// A tile_full hides every face it touches; water (a half block) only the top of the block under it.
		return kind == BlockCode.KIND_FULL || (kind == BlockCode.KIND_HALF && face == ShapeQuad.UP);
	}

	/** The quads drawn at one position. */
	public static int quadsAt(World world, ShapeTable table, int x, int y, int z) {
		int code = world.code(x, y, z);
		int kind = BlockCode.kind(code);
		if (kind <= 0 || kind == BlockCode.KIND_LIGHT || kind == BlockCode.KIND_STORY || kind == BlockCode.KIND_STORY_TILE
				|| kind == BlockCode.KIND_EDGE) {
			return 0;
		}
		int id = world.shapeId(x, y, z);
		ShapeMesh mesh = id == 0 ? null : table.mesh(id);
		if (mesh != null) {
			int n = 0;
			for (int q = 0; q < mesh.size(); q++) {
				int cull = mesh.quads().get(q).cull();
				if (cull >= 0) {
					int nx = x + FaceMasks.DX[cull];
					int ny = y + FaceMasks.DY[cull];
					int nz = z + FaceMasks.DZ[cull];
					if (occludes(world.code(nx, ny, nz), cull)) {
						continue;
					}
					int neighbour = world.shapeId(nx, ny, nz);
					if (neighbour != 0 && FaceMasks.culled(mesh, q, table.mesh(neighbour))) {
						continue;
					}
				}
				n++;
			}
			return n;
		}
		if (kind != BlockCode.KIND_FULL && kind != BlockCode.KIND_SHAPE && kind != BlockCode.KIND_HALF) {
			return 0;
		}
		int n = 0;
		for (int face = 0; face < 6; face++) {
			// The half cube's top lies inside the block: never culled.
			boolean onSurface = kind != BlockCode.KIND_HALF || face != ShapeQuad.UP;
			if (onSurface && occludes(world.code(x + FaceMasks.DX[face], y + FaceMasks.DY[face], z + FaceMasks.DZ[face]), face)) {
				continue;
			}
			n++;
		}
		return n;
	}

	/**
	 * The quads the runtime model emits at one position, before Minecraft culls faces against opaque neighbours (the
	 * renderer does that after the model): a shape's quads but those its shaped neighbours' face masks hide, a carrier's
	 * plain cube without a shape, nothing for decoration, ledges and story blocks but the closed story art they carry for
	 * this player ({@link World#closedId}). The dev-only mesh check compares this,
	 * summed over a section, with what the model emitted there (docs/specs/VOXEL_BLOCKS_PLAN.md §6.3).
	 */
	public static int emittedAt(World world, @Nullable ShapeTable table, int x, int y, int z) {
		int kind = BlockCode.kind(world.code(x, y, z));
		int closed = 0;
		if (kind == BlockCode.KIND_STORY_TILE || kind == BlockCode.KIND_DECOR) {
			// Closed story art: every quad of its shape, neither culling nor culled (V5).
			int id = world.closedId(x, y, z);
			ShapeMesh mesh = id == 0 || table == null ? null : table.mesh(id);
			closed = mesh == null ? 0 : mesh.size();
		}
		if (kind == BlockCode.KIND_STORY_TILE) {
			return closed;
		}
		if (kind != BlockCode.KIND_FULL && kind != BlockCode.KIND_HALF && kind != BlockCode.KIND_SHAPE && kind != BlockCode.KIND_DECOR
				&& kind != BlockCode.KIND_LEDGE && kind != BlockCode.KIND_STEP) {
			return 0;
		}
		int id = world.shapeId(x, y, z);
		ShapeMesh mesh = id == 0 || table == null ? null : table.mesh(id);
		if (mesh == null) {
			return closed + (kind == BlockCode.KIND_DECOR || kind == BlockCode.KIND_LEDGE || kind == BlockCode.KIND_STEP ? 0 : 6);
		}
		int n = closed;
		for (int q = 0; q < mesh.size(); q++) {
			int cull = mesh.quads().get(q).cull();
			if (cull >= 0) {
				int neighbour = world.shapeId(x + FaceMasks.DX[cull], y + FaceMasks.DY[cull], z + FaceMasks.DZ[cull]);
				if (neighbour != 0 && FaceMasks.culled(mesh, q, table.mesh(neighbour))) {
					continue;
				}
			}
			n++;
		}
		return n;
	}

	/** Counts a planned world: every position of its volumes, the shape ids from its shape map. */
	public static Stats count(WorldPlan plan, ShapeMap map, ShapeTable table) {
		return count(plan, map, table, plan.volumes());
	}

	/** The same over the chunks of {@code volumes} only (a dry run's cave area, docs/specs/CAVES_3D_PLAN.md §3.4). */
	public static Stats count(WorldPlan plan, ShapeMap map, ShapeTable table, List<Box3> volumes) {
		ShapeMap.Lookup lookup = map.lookup();
		Map<Long, ChunkCodes> cache = new HashMap<>();
		World world = new World() {
			@Override
			public int code(int x, int y, int z) {
				int cx = x >> 4;
				int cz = z >> 4;
				ChunkCodes codes = cache.computeIfAbsent(key(cx, cz), k -> plan.renderChunk(cx, cz, List.of()));
				return codes.get(x, y, z);
			}

			@Override
			public int shapeId(int x, int y, int z) {
				return lookup.shapeAt(x, y, z);
			}
		};
		List<long[]> chunks = new ArrayList<>(WorldPlan.chunks(volumes));
		chunks.sort((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
		int[] histogram = new int[HISTOGRAM + 1];
		long shaped = 0;
		long shapeQuads = 0;
		int maxBlock = 0;
		Map<Long, Integer> sections = new HashMap<>();
		long row = Long.MIN_VALUE;
		for (long[] chunk : chunks) {
			int cx = (int) chunk[0];
			int cz = (int) chunk[1];
			if (cz != row) {
				// Chunks two rows back are no one's neighbours any more.
				long keep = cz - 1L;
				cache.entrySet().removeIf(e -> (int) (e.getKey() & 0xFFFFFFFFL) < keep);
				row = cz;
			}
			ChunkCodes codes = cache.computeIfAbsent(key(cx, cz), k -> plan.renderChunk(cx, cz, List.of()));
			for (Map.Entry<Integer, int[]> level : codes.levels().entrySet()) {
				int y = level.getKey();
				int[] values = level.getValue();
				for (int i = 0; i < 256; i++) {
					if (values[i] == BlockCode.UNOWNED || values[i] == BlockCode.AIR) {
						continue;
					}
					int x = codes.minX() + (i & 15);
					int z = codes.minZ() + (i >> 4);
					int quads = quadsAt(world, table, x, y, z);
					if (quads > 0) {
						sections.merge(sectionKey(x >> 4, y >> 4, z >> 4), quads, Integer::sum);
					}
					if (lookup.shapeAt(x, y, z) != 0) {
						shaped++;
						shapeQuads += quads;
						maxBlock = Math.max(maxBlock, quads);
						histogram[Math.min(quads, HISTOGRAM)]++;
					}
				}
			}
		}
		int[] totals = sections.values().stream().mapToInt(Integer::intValue).sorted().toArray();
		int[] densest = null;
		int best = -1;
		for (Map.Entry<Long, Integer> e : sections.entrySet()) {
			if (e.getValue() > best || (e.getValue() == best && e.getKey() < sectionKey(densest[0] >> 4, densest[1] >> 4, densest[2] >> 4))) {
				best = e.getValue();
				long k = e.getKey();
				densest = new int[] {(int) (k >> 40) * 16, (int) ((k << 24) >> 44) * 16, (int) ((k << 44) >> 44) * 16};
			}
		}
		return new Stats(shaped, shapeQuads, shaped == 0 ? 0 : shapeQuads / (double) shaped, maxBlock, percentile(histogram, shaped, 0.99),
				totals.length, rank(totals, 0.50), rank(totals, 0.95), totals.length == 0 ? 0 : totals[totals.length - 1], densest);
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	/** A section's key: x in the top 24 bits, y in the next 20, z in the low 20 (all signed). */
	static long sectionKey(int sx, int sy, int sz) {
		return ((long) sx << 40) | (((long) sy & 0xFFFFF) << 20) | ((long) sz & 0xFFFFF);
	}

	/** The value at fraction {@code q} (nearest rank) of a histogram of {@code total} values. */
	private static int percentile(int[] histogram, long total, double q) {
		if (total == 0) {
			return 0;
		}
		long rank = (long) Math.ceil(q * total);
		long seen = 0;
		for (int v = 0; v < histogram.length; v++) {
			seen += histogram[v];
			if (seen >= rank) {
				return v;
			}
		}
		return histogram.length - 1;
	}

	/** The value at fraction {@code q} (nearest rank) of sorted values. */
	private static int rank(int[] sorted, double q) {
		if (sorted.length == 0) {
			return 0;
		}
		int index = (int) Math.ceil(q * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}
}
