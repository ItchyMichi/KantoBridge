package dev.kantobridge.dungeon;

import dev.kantobridge.builder.place.CodeStates;
import dev.kantobridge.builder.plan.Box3;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Builds and clears dungeon floors while the game runs (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7): one job at a time, a
 * chunk at a time, within a time budget per tick. A build writes every position of the floor's volume (what the
 * {@link DungeonFloorPlan} renders, air elsewhere); a clear writes air. Blocks are set as the world builder sets them
 * (clients told, no neighbour updates or physics), and only where they differ. Server thread only.
 */
public final class DungeonPlacer {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private static final class Job {
		final Box3 volume;
		final @Nullable DungeonFloorPlan plan;
		final Runnable done;
		final Deque<ChunkPos> chunks = new ArrayDeque<>();

		Job(Box3 volume, @Nullable DungeonFloorPlan plan, Runnable done) {
			this.volume = volume;
			this.plan = plan;
			this.done = done;
			for (int cz = volume.minZ() >> 4; cz <= (volume.maxZ() - 1) >> 4; cz++) {
				for (int cx = volume.minX() >> 4; cx <= (volume.maxX() - 1) >> 4; cx++) {
					this.chunks.add(new ChunkPos(cx, cz));
				}
			}
		}
	}

	private final ServerLevel level;
	private final CodeStates states = new CodeStates();
	private final Deque<Job> jobs = new ArrayDeque<>();
	private long placed;

	public DungeonPlacer(ServerLevel level) {
		this.level = level;
	}

	public ServerLevel level() {
		return this.level;
	}

	/** Builds {@code plan}'s floor; {@code done} runs once it stands. */
	public void build(DungeonFloorPlan plan, Runnable done) {
		this.jobs.add(new Job(plan.volume(), plan, done));
	}

	/** Clears {@code volume} to air; {@code done} runs once it is empty. */
	public void clear(Box3 volume, Runnable done) {
		this.jobs.add(new Job(volume, null, done));
	}

	/** Works until {@code deadlineNanos} (at least one chunk) or until nothing is left. */
	public void tick(long deadlineNanos) {
		boolean first = true;
		while (!this.jobs.isEmpty() && (first || System.nanoTime() < deadlineNanos)) {
			first = false;
			Job job = this.jobs.peek();
			ChunkPos chunk = job.chunks.poll();
			if (chunk != null) {
				this.place(job, chunk);
			}
			if (job.chunks.isEmpty()) {
				this.jobs.poll();
				job.done.run();
			}
		}
	}

	private void place(Job job, ChunkPos chunk) {
		this.level.getChunk(chunk.x, chunk.z);
		Box3 v = job.volume;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		Map<Integer, Integer> column = new HashMap<>();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int z = Math.max(v.minZ(), chunk.getMinBlockZ()); z < Math.min(v.maxZ(), chunk.getMaxBlockZ() + 1); z++) {
			for (int x = Math.max(v.minX(), chunk.getMinBlockX()); x < Math.min(v.maxX(), chunk.getMaxBlockX() + 1); x++) {
				column.clear();
				if (job.plan != null) {
					job.plan.renderColumn((bx, by, bz, code) -> column.put(by, code), x, z);
				}
				for (int y = v.minY(); y < v.maxY(); y++) {
					Integer code = column.get(y);
					BlockState desired = code == null ? air : this.states.state(code);
					pos.set(x, y, z);
					if (this.level.getBlockState(pos) != desired) {
						this.level.setBlock(pos, desired, FLAGS);
						this.placed++;
					}
				}
			}
		}
	}

	public boolean idle() {
		return this.jobs.isEmpty();
	}

	/** Blocks written so far. */
	public long placed() {
		return this.placed;
	}
}
