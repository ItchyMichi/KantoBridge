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

	ShapePlan(ShapeParams params, ShapeIndex index, ShapeMap map, Map<String, ShapePlanner.MapShapes> maps, Map<ShapeKey.Kind, Integer> keysByKind,
			long shapedBlocks, long planMs) {
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
				this.shapedBlocks, this.planMs);
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
}
