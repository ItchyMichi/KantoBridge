package dev.kantobridge.builder.place;

import com.cobblemon.mod.common.api.habitats.HabitatPool;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.ChunkCodes;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Places a {@link WorldPlan} in a level as a time-sliced server task (docs/PLAN.md §4.5 "Placing blocks").
 *
 * <ul>
 * <li>Chunk by chunk, levels top-down, within a time budget per tick.</li>
 * <li>Each chunk is held by a region ticket while it is built and until the light engine has caught up with it; at most
 * {@link #MAX_LIGHT_BACKLOG} chunks may wait for light before placement pauses.</li>
 * <li>{@code setBlock} flags 18 (clients + known shape: no neighbour updates, no physics), or 16 while no player
 * watches the chunk.</li>
 * <li>Re-runnable: every position inside the build's volumes (and an older build's) is set to its planned state or
 * cleared; unchanged blocks are left alone, so a second identical build places nothing.</li>
 * <li>Biomes are written per chunk in the same pass.</li>
 * <li>Habitat blocks (under the ground, outside the volumes) are placed and configured once their chunk is built
 * ({@link HabitatService#place}); an older build's that this plan doesn't keep are removed.</li>
 * </ul>
 * Runs on the server thread only.
 */
public final class BlockPlacer {
	/** A non-persistent ticket that keeps a chunk loaded (FULL) while the builder works on it. */
	public static final TicketType<ChunkPos> TICKET = TicketType.create("kantobridge_build", Comparator.comparingLong(ChunkPos::toLong));
	/** Chunks allowed to wait for the light engine at once; the back-pressure on light work. */
	public static final int MAX_LIGHT_BACKLOG = 4;
	private static final int FLAGS_SEEN = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	private static final int FLAGS_UNSEEN = Block.UPDATE_KNOWN_SHAPE;
	private static final int DEADLINE_CHECK = 256;

	/** Counts, for the build report. */
	public static final class Counts {
		public int blocksPlaced;
		public int blocksCleared;
		public int storyCells;
		public int ledgeColliders;
		public int stepColliders;
		public int lights;
		public int chunks;
		public int biomeChunks;
		/** Habitat blocks configured as planned (new, reconfigured or unchanged). */
		public int habitatBlocks;
		/** Planned habitat blocks that couldn't be configured (no pool, no block entity). */
		public int habitatFailures;
		/** An older build's habitat blocks removed. */
		public int habitatsRemoved;
		/** Planned blocks by {@link BlockCode} kind (air excluded), whether they changed or not. */
		public final int[] byKind = new int[16];
		/** Of {@code byKind[KIND_DECOR]}, the foliage ones ({@code decor_leaves}). */
		public int decorLeaves;
	}

	private record LightWait(ChunkPos pos, CompletableFuture<?> future) {
	}

	private final ServerLevel level;
	private final WorldPlan plan;
	private final List<Box3> extraClear;
	private final List<WorldIndex.Habitat> previousHabitats;
	private final Map<String, HabitatPool> pools;
	private final List<long[]> chunks;
	private final CodeStates states = new CodeStates();
	private final BiomeWriter biomes;
	private final Deque<LightWait> lightWaits = new ArrayDeque<>();
	private final Counts counts = new Counts();
	private int nextChunk;
	private Work current;
	private boolean released;

	/** One chunk in progress: its codes flattened top-down, and a cursor. */
	private static final class Work {
		final ChunkPos pos;
		final LevelChunk chunk;
		final int[] ys;
		final int[][] codes;
		final int flags;
		int level;
		int index;

		Work(ChunkPos pos, LevelChunk chunk, ChunkCodes planned, int flags) {
			this.pos = pos;
			this.chunk = chunk;
			this.flags = flags;
			Map<Integer, int[]> levels = planned.levels();
			this.ys = new int[levels.size()];
			this.codes = new int[levels.size()][];
			int i = 0;
			for (Map.Entry<Integer, int[]> entry : levels.entrySet()) {
				this.ys[i] = entry.getKey();
				this.codes[i] = entry.getValue();
				i++;
			}
		}
	}

	/**
	 * @param extraClear volumes an older build occupied, cleared too
	 */
	public BlockPlacer(ServerLevel level, WorldPlan plan, List<Box3> extraClear) {
		this(level, plan, extraClear, List.of(), Map.of());
	}

	/**
	 * @param extraClear       volumes an older build occupied, cleared too
	 * @param previousHabitats an older build's habitat blocks, removed unless this plan keeps them
	 * @param pools            the plan's habitat pools as Cobblemon knows them ({@link HabitatService#ensurePools})
	 */
	public BlockPlacer(ServerLevel level, WorldPlan plan, List<Box3> extraClear, List<WorldIndex.Habitat> previousHabitats,
			Map<String, HabitatPool> pools) {
		this.level = level;
		this.plan = plan;
		this.extraClear = List.copyOf(extraClear);
		this.previousHabitats = List.copyOf(previousHabitats);
		this.pools = Map.copyOf(pools);
		List<Box3> all = new ArrayList<>(plan.volumes());
		all.addAll(extraClear);
		this.chunks = WorldPlan.chunks(all);
		this.biomes = new BiomeWriter(level, plan.biomeRegions());
	}

	public int totalChunks() {
		return this.chunks.size();
	}

	public int doneChunks() {
		return this.counts.chunks;
	}

	public Counts counts() {
		return this.counts;
	}

	/** Places the spawn cell's floor at once, so a player joining mid-build has something to stand on. */
	public void placeSpawnPlatform() {
		for (int[] block : this.plan.spawnPlatform()) {
			BlockPos pos = new BlockPos(block[0], block[1], block[2]);
			BlockState state = this.states.state(block[3]);
			if (this.level.getBlockState(pos) != state) {
				this.level.setBlock(pos, state, FLAGS_SEEN);
			}
		}
	}

	/**
	 * Works until {@code deadlineNanos} ({@link System#nanoTime()}); returns true once every chunk is placed and lit and
	 * all tickets are released.
	 */
	public boolean tick(long deadlineNanos) {
		while (System.nanoTime() < deadlineNanos) {
			this.releaseLitChunks();
			if (this.current == null) {
				if (this.nextChunk >= this.chunks.size()) {
					if (this.lightWaits.isEmpty()) {
						this.released = true;
						return true;
					}
					return false;
				}
				if (this.lightWaits.size() >= MAX_LIGHT_BACKLOG) {
					return false;
				}
				this.current = this.start(this.chunks.get(this.nextChunk++));
			}
			if (this.placeSome(deadlineNanos)) {
				this.finishChunk();
			}
		}
		return false;
	}

	/** Drops every ticket this placer holds (on cancel or server stop). */
	public void release() {
		if (this.released) {
			return;
		}
		this.released = true;
		if (this.current != null) {
			this.level.getChunkSource().removeRegionTicket(TICKET, this.current.pos, 0, this.current.pos);
			this.current = null;
		}
		for (LightWait wait : this.lightWaits) {
			this.level.getChunkSource().removeRegionTicket(TICKET, wait.pos(), 0, wait.pos());
		}
		this.lightWaits.clear();
	}

	private Work start(long[] chunk) {
		ChunkPos pos = new ChunkPos((int) chunk[0], (int) chunk[1]);
		this.level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
		LevelChunk levelChunk = this.level.getChunk(pos.x, pos.z);
		if (this.biomes.apply(levelChunk)) {
			this.counts.biomeChunks++;
		}
		boolean seen = !this.level.getChunkSource().chunkMap.getPlayers(pos, false).isEmpty();
		return new Work(pos, levelChunk, this.plan.renderChunk(pos.x, pos.z, this.extraClear), seen ? FLAGS_SEEN : FLAGS_UNSEEN);
	}

	/** Places until the chunk is done (true) or the deadline passes (false). */
	private boolean placeSome(long deadlineNanos) {
		Work work = this.current;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int steps = 0;
		int x0 = work.pos.getMinBlockX();
		int z0 = work.pos.getMinBlockZ();
		while (work.level < work.ys.length) {
			int[] codes = work.codes[work.level];
			int y = work.ys[work.level];
			while (work.index < 256) {
				int i = work.index++;
				int code = codes[i];
				if (code != BlockCode.UNOWNED) {
					pos.set(x0 + (i & 15), y, z0 + (i >> 4));
					this.apply(work, pos, code);
				}
				if (++steps % DEADLINE_CHECK == 0 && System.nanoTime() >= deadlineNanos) {
					return false;
				}
			}
			work.level++;
			work.index = 0;
		}
		return true;
	}

	private void apply(Work work, BlockPos pos, int code) {
		BlockState desired = this.states.state(code);
		BlockState current = work.chunk.getBlockState(pos);
		if (current != desired) {
			this.level.setBlock(pos.immutable(), desired, work.flags);
			if (desired.isAir()) {
				this.counts.blocksCleared++;
			} else {
				this.counts.blocksPlaced++;
			}
		}
		int kind = BlockCode.kind(code);
		if (kind > 0 && kind < this.counts.byKind.length) {
			this.counts.byKind[kind]++;
		}
		switch (kind) {
			case BlockCode.KIND_STORY, BlockCode.KIND_STORY_TILE -> {
				this.counts.storyCells++;
				StoryCellTable.Entry entry = this.plan.storyCells().entries().get(BlockCode.value(code));
				BlockEntity blockEntity = this.level.getBlockEntity(pos);
				if (blockEntity instanceof StoryCellBlockEntity cell && cell.set(entry.key(), entry.type()) && current == desired
						&& work.flags == FLAGS_SEEN) {
					// Same block, new data: make sure watching clients get the block entity again.
					this.level.sendBlockUpdated(pos, desired, desired, Block.UPDATE_CLIENTS);
				}
			}
			case BlockCode.KIND_LEDGE -> this.counts.ledgeColliders++;
			case BlockCode.KIND_STEP -> this.counts.stepColliders++;
			case BlockCode.KIND_LIGHT -> this.counts.lights++;
			case BlockCode.KIND_DECOR -> this.counts.decorLeaves += BlockCode.value(code) == 1 ? 1 : 0;
			default -> {
			}
		}
	}

	private void finishChunk() {
		Work work = this.current;
		this.current = null;
		this.habitats(work);
		this.counts.chunks++;
		this.lightWaits.add(new LightWait(work.pos, this.level.getChunkSource().getLightEngine().waitForPendingTasks(work.pos.x, work.pos.z)));
	}

	/** The chunk's habitat blocks: an older build's that this plan drops go, the planned ones are placed or kept. */
	private void habitats(Work work) {
		List<WorldIndex.Habitat> planned = this.plan.habitatsIn(work.pos.x, work.pos.z);
		Set<BlockPos> keep = new HashSet<>();
		for (WorldIndex.Habitat habitat : planned) {
			keep.add(new BlockPos(habitat.x(), habitat.y(), habitat.z()));
		}
		for (WorldIndex.Habitat old : WorldPlan.habitatsIn(this.previousHabitats, work.pos.x, work.pos.z)) {
			BlockPos pos = new BlockPos(old.x(), old.y(), old.z());
			if (!keep.contains(pos) && HabitatService.remove(this.level, pos, work.flags)) {
				this.counts.habitatsRemoved++;
				this.counts.blocksCleared++;
			}
		}
		for (WorldIndex.Habitat habitat : planned) {
			HabitatPool pool = this.pools.get(habitat.pool());
			if (pool == null) {
				this.counts.habitatFailures++;
				continue;
			}
			switch (HabitatService.place(this.level, new BlockPos(habitat.x(), habitat.y(), habitat.z()), pool, work.flags, habitat.range())) {
				case NEW -> {
					this.counts.habitatBlocks++;
					this.counts.blocksPlaced++;
				}
				case RECONFIGURED, UNCHANGED -> this.counts.habitatBlocks++;
				case FAILED -> this.counts.habitatFailures++;
			}
		}
	}

	private void releaseLitChunks() {
		while (!this.lightWaits.isEmpty() && this.lightWaits.peek().future().isDone()) {
			LightWait wait = this.lightWaits.poll();
			this.level.getChunkSource().removeRegionTicket(TICKET, wait.pos(), 0, wait.pos());
		}
	}
}
