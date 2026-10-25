package dev.kantobridge.field;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.block.SeatColliderBlock;
import dev.kantobridge.block.StepColliderBlock;
import dev.kantobridge.builder.place.CodeStates;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.ChunkCodesView;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A player box in a planned world (FA6, docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4.3): the planned blocks' collision
 * shapes, the player-dependent ones (ledge colliders, story blocks) from their own rules, a 0.6 step and gravity, as
 * {@link FieldParity} has them. Objects' story cells are left out (people move; the replay checks terrain).
 */
final class PlanPhysics {
	static final double HALF = 0.3;
	static final double HEIGHT = 1.8;
	static final double STEP_UP = 0.6;
	static final double EPS = 1.0E-6;
	private static final VoxelShape HALF_SHAPE = Block.box(0, 0, 0, 16, 8, 16);

	private final WorldPlan plan;
	private final List<StoryCellTable.Entry> entries;
	private final int surfaceY;
	private ChunkCodesView view;
	private int lookups;

	PlanPhysics(WorldPlan plan) {
		this.plan = plan;
		this.entries = plan.storyCells().entries();
		this.surfaceY = plan.index().surfaceY();
		this.view = new ChunkCodesView(plan);
	}

	/** Drops the rendered chunks now and then (memory). */
	void trim() {
		if (this.lookups > 2_000_000) {
			this.view = new ChunkCodesView(this.plan);
			this.lookups = 0;
		}
	}

	static AABB box(double x, double y, double z) {
		return new AABB(x - HALF, y, z - HALF, x + HALF, y + HEIGHT, z + HALF);
	}

	/** The position after a horizontal move by (dx, dz) from (x, y, z): blocked, stepped up or fallen. */
	double[] move(double x, double y, double z, double dx, double dz, StoryState state) {
		double nx = x + dx;
		double nz = z + dz;
		double ny = y;
		AABB box = box(nx, ny, nz);
		// judged for the player's box before the move, as Minecraft's CollisionContext does
		AABB now = box(x, y, z);
		List<AABB> hits = this.colliders(box, state, now);
		if (!hits.isEmpty()) {
			double top = ny;
			for (AABB hit : hits) {
				top = Math.max(top, hit.maxY);
			}
			if (top - ny <= STEP_UP + EPS && this.colliders(box(nx, top, nz), state, now).isEmpty()) {
				ny = top;
			} else {
				// blocked along both axes: try each alone, as Minecraft slides along walls
				if (dx != 0 && dz != 0) {
					double[] a = this.move(x, y, z, dx, 0, state);
					if (a[0] != x) {
						return a;
					}
					return this.move(x, y, z, 0, dz, state);
				}
				return new double[] {x, y, z};
			}
		}
		Double fall = this.restingY(nx, ny, nz, state, 16.0);
		return new double[] {nx, fall == null ? ny : fall, nz};
	}

	/** The highest floor under the box at (x, z) from fromY down at most reach; null for none. */
	Double restingY(double x, double fromY, double z, StoryState state, double reach) {
		AABB column = new AABB(x - HALF, fromY - reach, z - HALF, x + HALF, fromY + EPS, z + HALF);
		double best = Double.NEGATIVE_INFINITY;
		for (AABB a : this.shapesIn(column, state, box(x, fromY, z))) {
			if (a.maxY <= fromY + EPS && a.maxY > best) {
				best = a.maxY;
			}
		}
		return best == Double.NEGATIVE_INFINITY ? null : best;
	}

	List<AABB> colliders(AABB box, StoryState state) {
		return this.colliders(box, state, box);
	}

	List<AABB> colliders(AABB box, StoryState state, AABB player) {
		List<AABB> out = new ArrayList<>();
		for (AABB a : this.shapesIn(box, state, player)) {
			if (a.minX < box.maxX - EPS && a.maxX > box.minX + EPS && a.minY < box.maxY - EPS && a.maxY > box.minY + EPS
					&& a.minZ < box.maxZ - EPS && a.maxZ > box.minZ + EPS) {
				out.add(a);
			}
		}
		return out;
	}

	private List<AABB> shapesIn(AABB region, StoryState state, AABB player) {
		List<AABB> out = new ArrayList<>();
		for (int bx = (int) Math.floor(region.minX); bx <= (int) Math.floor(region.maxX - EPS); bx++) {
			for (int bz = (int) Math.floor(region.minZ); bz <= (int) Math.floor(region.maxZ - EPS); bz++) {
				for (int by = (int) Math.floor(region.minY) - 1; by <= (int) Math.floor(region.maxY - EPS); by++) {
					VoxelShape shape = this.shapeAt(bx, by, bz, state, player);
					if (shape.isEmpty()) {
						continue;
					}
					for (AABB a : shape.toAabbs()) {
						out.add(a.move(bx, by, bz));
					}
				}
			}
		}
		return out;
	}

	private VoxelShape shapeAt(int x, int y, int z, StoryState state, AABB player) {
		this.lookups++;
		int code = this.view.code(x, y, z);
		if (code == BlockCode.UNOWNED) {
			return y < this.surfaceY ? Shapes.block() : Shapes.empty();
		}
		int value = BlockCode.value(code);
		return switch (BlockCode.kind(code)) {
			case BlockCode.KIND_FULL, BlockCode.KIND_SHAPE, BlockCode.KIND_VANILLA -> Shapes.block();
			case BlockCode.KIND_HALF -> HALF_SHAPE;
			case BlockCode.KIND_LEDGE -> LedgeColliderBlock.walls(player, new BlockPos(x, y, z), CodeStates.downhill(value), LedgeColliderBlock.Mode.of(value >> 2))
					? LedgeColliderBlock.WALL : Shapes.empty();
			case BlockCode.KIND_STORY, BlockCode.KIND_STORY_TILE -> {
				int i = BlockCode.storyIndex(code);
				StoryCellTable.Entry e = i >= 0 && i < this.entries.size() ? this.entries.get(i) : null;
				boolean closed = e != null && e.type() != StoryCellType.OBJECT && e.type() != StoryCellType.BOULDER_AREA
						&& state.blocks(e.key(), e.type());
				yield closed ? Shapes.block() : EdgeWallBlock.shape(BlockCode.storySides(code));
			}
			case BlockCode.KIND_EDGE -> EdgeWallBlock.shape(value);
			case BlockCode.KIND_STEP -> StepColliderBlock.shape(CodeStates.downhill(value), StepColliderBlock.Stage.of(value >> 2 & 3));
			case BlockCode.KIND_SEAT -> SeatColliderBlock.SHAPE;
			case BlockCode.KIND_FALL_WALL -> state.forced() ? Shapes.empty() : Shapes.block();
			case BlockCode.KIND_DECOR -> (value & 4) != 0 ? Shapes.block() : Shapes.empty();
			default -> Shapes.empty();
		};
	}
}
