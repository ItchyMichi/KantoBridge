package dev.kantobridge.builder.shape;

import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * What the shape planner decided for a world (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4): the shape index (keys of the whole
 * export, hashed), the shape map (ids per position of the placed maps), the keys of every map's columns, and counts for
 * the build report. Immutable.
 */
public final class ShapePlan {
	private final ShapeParams params;
	private final ShapeIndex index;
	private final ShapeMap map;
	private final Map<String, ShapePlanner.MapShapes> maps;
	private final Map<ShapeKey.Kind, Integer> keysByKind;
	private final long shapedBlocks;
	private final long planMs;
	private final long decorSkipped;
	private final Occupancy.@Nullable VoidFill voidFill;
	private final Map<Integer, VoidUnit> voidUnits;
	private final Map<String, Closed> closedColumns;

	/**
	 * A tile story cell's closed columns (V5): per column of the cell (north-west, north-east, south-west, south-east)
	 * its height in blocks from feet level, and whether it is foliage (its carriers above feet level are
	 * {@code decor_leaves}).
	 */
	public record Closed(int[] heights, boolean leaves) {
		/** The tallest column. */
		public int height() {
			int max = 0;
			for (int h : this.heights) {
				max = Math.max(max, h);
			}
			return max;
		}
	}

	/**
	 * A void-fill cell planned as a unit of its owner's border (V4).
	 *
	 * @param owner    the map whose border it wears
	 * @param ground   the tile its floor block wears, -1 for its own
	 * @param distance its distance in cells to the nearest map body
	 * @param key      the key of its first block
	 */
	public record VoidUnit(String owner, int ground, int distance, ShapeKey key) {
	}

	ShapePlan(ShapeParams params, ShapeIndex index, ShapeMap map, Map<String, ShapePlanner.MapShapes> maps, Map<ShapeKey.Kind, Integer> keysByKind,
			long shapedBlocks, long planMs, long decorSkipped, Occupancy.@Nullable VoidFill voidFill, Map<Integer, VoidUnit> voidUnits,
			Map<String, Closed> closedColumns) {
		this.closedColumns = Collections.unmodifiableMap(new TreeMap<>(closedColumns));
		this.decorSkipped = decorSkipped;
		this.voidFill = voidFill;
		this.voidUnits = Collections.unmodifiableMap(new TreeMap<>(voidUnits));
		this.params = params;
		this.index = index;
		this.map = map;
		this.maps = Collections.unmodifiableMap(new TreeMap<>(maps));
		Map<ShapeKey.Kind, Integer> byKind = new EnumMap<>(ShapeKey.Kind.class);
		byKind.putAll(keysByKind);
		this.keysByKind = Collections.unmodifiableMap(byKind);
		this.shapedBlocks = shapedBlocks;
		this.planMs = planMs;
	}

	/** The same plan with its shape map bound to the WorldIndex whose text hashes to {@code indexHash}. */
	public ShapePlan withIndexHash(String indexHash) {
		return new ShapePlan(this.params, this.index, this.map.withHeader(indexHash, this.index.hash()), this.maps, this.keysByKind,
				this.shapedBlocks, this.planMs, this.decorSkipped, this.voidFill, this.voidUnits, this.closedColumns);
	}

	public ShapeParams params() {
		return this.params;
	}

	public ShapeIndex index() {
		return this.index;
	}

	public ShapeMap map() {
		return this.map;
	}

	/** A map's planned keys (every map of the export has them), or null for a map the scene doesn't hold. */
	public ShapePlanner.@Nullable MapShapes shapes(String mapId) {
		return this.maps.get(mapId);
	}

	public Map<String, ShapePlanner.MapShapes> maps() {
		return this.maps;
	}

	/** Distinct keys per kind. */
	public Map<ShapeKey.Kind, Integer> keysByKind() {
		return this.keysByKind;
	}

	/** Positions of the placed maps with a shape. */
	public long shapedBlocks() {
		return this.shapedBlocks;
	}

	public long planMs() {
		return this.planMs;
	}

	/** Decor pieces (tufts) skipped because a story block takes their position. */
	public long decorSkipped() {
		return this.decorSkipped;
	}

	/** The void fill the plan was made for, or null. */
	public Occupancy.@Nullable VoidFill voidFill() {
		return this.voidFill;
	}

	/** The void-fill cells planned as units, by surface cell index ({@code cz * w + cx} of the void fill). */
	public Map<Integer, VoidUnit> voidUnits() {
		return this.voidUnits;
	}

	/** The closed columns of a tile story cell with an overlay, or null. */
	public @Nullable Closed closed(String storyKey) {
		return this.closedColumns.get(storyKey);
	}

	/** Every tile story cell with an overlay, by key. */
	public Map<String, Closed> closedColumns() {
		return this.closedColumns;
	}

	/** The unit planned for a void-fill cell, or null (a plain void column). */
	public @Nullable VoidUnit voidUnit(int cell) {
		return this.voidUnits.get(cell);
	}
}
