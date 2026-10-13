package dev.kantobridge.server;

import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.api.habitats.HabitatPhaseOrder;
import com.cobblemon.mod.common.api.habitats.HabitatPool;
import com.cobblemon.mod.common.api.habitats.HabitatPools;
import com.cobblemon.mod.common.api.habitats.HabitatSpawn;
import com.cobblemon.mod.common.api.habitats.spawningstyle.NaturalHabitatSpawning;
import com.cobblemon.mod.common.block.habitat.HabitatBlockEntity;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.pack.DatapackWriter;
import dev.kantobridge.builder.pack.HabitatPoolWriter;
import dev.kantobridge.builder.plan.HabitatPlanner;
import dev.kantobridge.world.WorldIndex;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Cobblemon habitat blocks for Kanto's wild Pokémon (docs/PLAN.md P8, §4.4 {@code HabitatService}, §4.5 "Placing blocks").
 *
 * <ul>
 * <li><b>At build time</b> the builder places a {@code cobblemon:habitat_block} at each planned spot with
 * {@code Level.setBlock} (so the block is a point of interest Cobblemon's spawner finds) and configures it through the
 * block-entity API: natural spawning, {@code ReplaceSpawns} off (overlapping spheres add up instead of vetoing each
 * other), {@code RangeOfInfluence} {@value #RANGE}, the map's pool, phase order SIMPLE, then
 * {@code refreshFromSettings()}. The world's datapack holds the pools for later loads; {@link #ensurePools} hands them to
 * Cobblemon at once, so a fresh build spawns without a reload.</li>
 * <li><b>At run time</b> every habitat block the WorldIndex lists is checked once its chunk is loaded: still a habitat
 * block, natural, range {@value #RANGE}, its own pool attached. A block whose pool didn't resolve (the world's datapack
 * disabled) gets it back from the datapack's files, and a block loaded from the save is prepared at once, as its first
 * tick would (its spawns attached) even while its chunk doesn't tick. Counts only are logged.</li>
 * </ul>
 */
public final class HabitatService {
	public static final int RANGE = HabitatPlanner.RANGE;

	/** What placing one habitat block did. */
	public enum Placed {
		/** A new habitat block, configured. */
		NEW,
		/** An existing habitat block, configured again (another pool, other settings). */
		RECONFIGURED,
		/** Already configured as planned. */
		UNCHANGED,
		/** No block entity, or no pool: the block isn't usable. */
		FAILED
	}

	/** Runtime counters (since the server started). */
	public static final class Counts {
		public final AtomicLong checked = new AtomicLong();
		public final AtomicLong ok = new AtomicLong();
		public final AtomicLong repaired = new AtomicLong();
		public final AtomicLong missingBlock = new AtomicLong();
		public final AtomicLong missingPool = new AtomicLong();

		void reset() {
			this.checked.set(0);
			this.ok.set(0);
			this.repaired.set(0);
			this.missingBlock.set(0);
			this.missingPool.set(0);
		}
	}

	private static final Counts COUNTS = new Counts();
	/** Chunks loaded since the last tick whose habitats are to be checked. */
	private static final Set<Long> PENDING_CHUNKS = ConcurrentHashMap.newKeySet();
	/** The habitats of the current WorldIndex by chunk, rebuilt when the index changes. */
	private static final Map<Long, List<WorldIndex.Habitat>> BY_CHUNK = new HashMap<>();
	private static @Nullable String indexedHash;

	private HabitatService() {
	}

	/** Call once from common init, after {@link WorldIndexSync#init}. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(HabitatService::onStarted);
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
				PENDING_CHUNKS.add(chunk.getPos().toLong());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(HabitatService::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PENDING_CHUNKS.clear();
			BY_CHUNK.clear();
			indexedHash = null;
			COUNTS.reset();
		});
	}

	// ---- pools ----

	/**
	 * Makes Cobblemon know these pools now (canonical JSON by pool id), as its datapack loader would: parsed with
	 * Cobblemon's own reader, validated, not editable. A pool Cobblemon already has with the same spawns is kept (its
	 * blocks then count as unchanged); a different one is replaced. Returns the pools by id. Server thread.
	 */
	public static Map<String, HabitatPool> ensurePools(Map<String, String> pools) {
		Map<ResourceLocation, HabitatPool> registry = HabitatPools.INSTANCE.getHabitatPoolsById();
		Map<String, HabitatPool> out = new LinkedHashMap<>();
		int added = 0;
		for (Map.Entry<String, String> entry : pools.entrySet()) {
			ResourceLocation id = ResourceLocation.parse(entry.getKey());
			HabitatPool parsed = parse(id, entry.getValue());
			HabitatPool existing = registry.get(id);
			if (existing != null && signature(existing).equals(signature(parsed))) {
				out.put(entry.getKey(), existing);
				continue;
			}
			registry.put(id, parsed);
			out.put(entry.getKey(), parsed);
			added++;
		}
		if (added > 0) {
			KantoBridge.LOGGER.info("habitats: {} pools handed to Cobblemon ({} already known)", added, pools.size() - added);
		}
		return out;
	}

	static HabitatPool parse(ResourceLocation id, String json) {
		HabitatPool pool = HabitatPools.INSTANCE.parse(new BufferedReader(new StringReader(json)), id);
		pool.setId(id);
		pool.validate();
		pool.setEditable(false);
		return pool;
	}

	/** What a pool spawns, comparable across instances (species, position, bucket, weight, levels, phases per entry). */
	static String signature(HabitatPool pool) {
		StringBuilder out = new StringBuilder();
		for (HabitatSpawn spawn : pool.getSpawns()) {
			out.append(spawn.getSpecies().getResourceIdentifier()).append('|').append(spawn.getSpawnablePositionType()).append('|')
					.append(spawn.getBucket()).append('|').append(spawn.getWeight()).append('|').append(spawn.getLevelRange().getFirst())
					.append('-').append(spawn.getLevelRange().getLast()).append('|').append(spawn.getPhases() != null).append(';');
		}
		return out.toString();
	}

	// ---- blocks ----

	/**
	 * Places (or keeps) a configured habitat block at {@code pos}. {@code flags} are {@code setBlock}'s for a new block;
	 * Cobblemon's own refresh then updates its state flags.
	 */
	public static Placed place(ServerLevel level, BlockPos pos, HabitatPool pool, int flags) {
		return place(level, pos, pool, flags, RANGE);
	}

	/** The same with a {@code RangeOfInfluence} of {@code range} (the cave area's are smaller, CAVES_3D_PLAN §3.3). */
	public static Placed place(ServerLevel level, BlockPos pos, HabitatPool pool, int flags, int range) {
		boolean isNew = !level.getBlockState(pos).is(CobblemonBlocks.HABITAT_BLOCK);
		if (isNew) {
			level.setBlock(pos, CobblemonBlocks.HABITAT_BLOCK.defaultBlockState(), flags);
		}
		if (!(level.getBlockEntity(pos) instanceof HabitatBlockEntity habitat)) {
			return Placed.FAILED;
		}
		if (!isNew && configured(habitat, pool, range)) {
			return Placed.UNCHANGED;
		}
		configure(habitat, pool, range);
		return isNew ? Placed.NEW : Placed.RECONFIGURED;
	}

	/** The block-entity configuration of PLAN §4.5. */
	public static void configure(HabitatBlockEntity habitat, HabitatPool pool) {
		configure(habitat, pool, RANGE);
	}

	/** The same with a {@code RangeOfInfluence} of {@code range}. */
	public static void configure(HabitatBlockEntity habitat, HabitatPool pool, int range) {
		NaturalHabitatSpawning style = new NaturalHabitatSpawning(habitat);
		style.setReplaceSpawns(false);
		style.setRangeOfInfluence(range);
		habitat.setSpawningStyle(style);
		habitat.setPool(pool);
		habitat.setPhaseOrder(HabitatPhaseOrder.SIMPLE);
		// Rebuilds the spawn details and the block's state flags (natural, not cancelling regular spawns).
		habitat.refreshFromSettings();
		habitat.setInitialized(true);
		habitat.setChanged();
	}

	/** True when the block is configured as planned with exactly this pool. */
	public static boolean configured(HabitatBlockEntity habitat, HabitatPool pool) {
		return configured(habitat, pool, RANGE);
	}

	public static boolean configured(HabitatBlockEntity habitat, HabitatPool pool, int range) {
		return habitat.getPool() == pool && configuredFor(habitat, pool.getId().toString(), range);
	}

	/** True when the block's settings are the planned ones and its pool has this id and spawns something. */
	public static boolean configuredFor(HabitatBlockEntity habitat, String poolId) {
		return configuredFor(habitat, poolId, RANGE);
	}

	/** The same for a block of {@code RangeOfInfluence} {@code range}. */
	public static boolean configuredFor(HabitatBlockEntity habitat, String poolId, int range) {
		return habitat.getSpawningStyle() instanceof NaturalHabitatSpawning style && !style.getReplaceSpawns()
				&& style.getRangeOfInfluence() == range && habitat.getPhaseOrder() == HabitatPhaseOrder.SIMPLE
				&& habitat.getPool().getId().toString().equals(poolId) && !habitat.getPool().getSpawns().isEmpty();
	}

	/** Removes a habitat block an older build left (only if it is one). Returns true when it was there. */
	public static boolean remove(ServerLevel level, BlockPos pos, int flags) {
		if (!level.getBlockState(pos).is(CobblemonBlocks.HABITAT_BLOCK)) {
			return false;
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
		return true;
	}

	// ---- run time ----

	private static void onStarted(MinecraftServer server) {
		List<WorldIndex.Habitat> habitats = allHabitats(server);
		if (habitats.isEmpty()) {
			return;
		}
		Set<String> needed = new HashSet<>();
		for (WorldIndex.Habitat habitat : habitats) {
			needed.add(habitat.pool());
		}
		int known = 0;
		Map<ResourceLocation, HabitatPool> registry = HabitatPools.INSTANCE.getHabitatPoolsById();
		for (String pool : needed) {
			if (registry.containsKey(ResourceLocation.parse(pool))) {
				known++;
			}
		}
		if (known < needed.size()) {
			// The world's datapack isn't enabled (the player turned it off?): read its pools ourselves.
			int loaded = loadWorldPools(server, needed);
			KantoBridge.LOGGER.warn("habitats: {} of {} pools weren't loaded from the world's datapack ({}); {} read from its files", needed.size() - known,
					needed.size(), server.getPackRepository().getSelectedIds().contains(DatapackWriter.PACK_ID) ? "enabled" : "not enabled", loaded);
		}
		// Chunks loaded during start-up (the spawn area) are checked now that the index is known.
		int checked = verifyLoaded(server);
		KantoBridge.LOGGER.info("habitats: {} habitat blocks on {} pools listed; {} loaded and checked ({} ok, {} reconfigured)",
				habitats.size(), needed.size(), checked, COUNTS.ok.get(), COUNTS.repaired.get());
	}

	/** Every published region's habitat blocks (MG3; a legacy world: its one index's). Pool ids are unique across regions. */
	static List<WorldIndex.Habitat> allHabitats(MinecraftServer server) {
		Regions regions = Regions.of(server);
		if (regions == null) {
			return List.of();
		}
		List<WorldIndex.Habitat> out = new ArrayList<>();
		for (WorldIndex index : regions.indices().values()) {
			out.addAll(index.habitats());
		}
		return out;
	}

	/** The hashes of every published index, joined: when it changes, the chunk table is rebuilt. */
	private static String indicesKey(MinecraftServer server) {
		Regions regions = Regions.of(server);
		if (regions == null) {
			return "";
		}
		StringBuilder key = new StringBuilder();
		for (WorldIndex index : regions.indices().values()) {
			key.append(index.hash()).append(';');
		}
		return key.toString();
	}

	/** Checks every listed habitat block whose chunk is loaded now; returns how many were checked. */
	public static int verifyLoaded(MinecraftServer server) {
		List<WorldIndex.Habitat> habitats = allHabitats(server);
		if (habitats.isEmpty()) {
			return 0;
		}
		ServerLevel level = server.overworld();
		int checked = 0;
		for (WorldIndex.Habitat habitat : habitats) {
			if (level.isLoaded(new BlockPos(habitat.x(), habitat.y(), habitat.z()))) {
				check(level, habitat);
				checked++;
			}
		}
		return checked;
	}

	/** Reads the world datapack's pool files for the ids in {@code needed} that Cobblemon lacks. */
	static int loadWorldPools(MinecraftServer server, Set<String> needed) {
		Path folder = server.getWorldPath(LevelResource.ROOT).resolve("datapacks").resolve(DatapackWriter.FOLDER);
		Map<String, String> pools = new TreeMap<>();
		Map<ResourceLocation, HabitatPool> registry = HabitatPools.INSTANCE.getHabitatPoolsById();
		for (String pool : needed) {
			if (registry.containsKey(ResourceLocation.parse(pool))) {
				continue;
			}
			Path file = folder.resolve(HabitatPoolWriter.path(pool));
			try {
				if (Files.isRegularFile(file)) {
					pools.put(pool, Files.readString(file, StandardCharsets.UTF_8));
				}
			} catch (IOException e) {
				KantoBridge.LOGGER.warn("habitats: a pool file is unreadable ({})", e.getClass().getSimpleName());
			}
		}
		try {
			ensurePools(pools);
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.warn("habitats: a pool file is invalid ({})", e.getClass().getSimpleName());
			return 0;
		}
		return pools.size();
	}

	private static void tick(MinecraftServer server) {
		if (PENDING_CHUNKS.isEmpty()) {
			return;
		}
		List<WorldIndex.Habitat> habitats = allHabitats(server);
		if (habitats.isEmpty()) {
			PENDING_CHUNKS.clear();
			return;
		}
		Map<Long, List<WorldIndex.Habitat>> byChunk = byChunk(indicesKey(server), habitats);
		List<Long> chunks = new ArrayList<>(PENDING_CHUNKS);
		PENDING_CHUNKS.removeAll(chunks);
		ServerLevel level = server.overworld();
		for (long chunk : chunks) {
			List<WorldIndex.Habitat> inChunk = byChunk.get(chunk);
			if (inChunk != null) {
				for (WorldIndex.Habitat habitat : inChunk) {
					check(level, habitat);
				}
			}
		}
	}

	/** Checks one listed habitat block in a loaded chunk, repairing its pool when it can. */
	static void check(ServerLevel level, WorldIndex.Habitat habitat) {
		BlockPos pos = new BlockPos(habitat.x(), habitat.y(), habitat.z());
		if (!level.isLoaded(pos)) {
			return;
		}
		COUNTS.checked.incrementAndGet();
		BlockEntity entity = level.getBlockEntity(pos);
		if (!(entity instanceof HabitatBlockEntity block)) {
			long n = COUNTS.missingBlock.incrementAndGet();
			KantoBridge.LOGGER.warn("habitats: a listed habitat block is missing on {} ({} so far)", habitat.map(), n);
			return;
		}
		if (configuredFor(block, habitat.pool(), habitat.range())) {
			if (!block.getInitialized()) {
				// Loaded from the save but not ticked yet (its chunk isn't ticking): prepare it as its first tick would, so its
				// spawns are attached for the player spawner at once.
				block.refreshFromSettings();
				block.setInitialized(true);
			}
			COUNTS.ok.incrementAndGet();
			return;
		}
		HabitatPool pool = HabitatPools.INSTANCE.getHabitatPoolsById().get(ResourceLocation.parse(habitat.pool()));
		if (pool == null) {
			long n = COUNTS.missingPool.incrementAndGet();
			KantoBridge.LOGGER.warn("habitats: no pool for a habitat block on {} ({} so far)", habitat.map(), n);
			return;
		}
		configure(block, pool, habitat.range());
		long n = COUNTS.repaired.incrementAndGet();
		KantoBridge.LOGGER.info("habitats: reconfigured a habitat block on {} ({} so far)", habitat.map(), n);
	}

	private static Map<Long, List<WorldIndex.Habitat>> byChunk(String key, List<WorldIndex.Habitat> habitats) {
		if (!key.equals(indexedHash)) {
			BY_CHUNK.clear();
			for (WorldIndex.Habitat habitat : habitats) {
				BY_CHUNK.computeIfAbsent(ChunkPos.asLong(habitat.x() >> 4, habitat.z() >> 4), chunk -> new ArrayList<>()).add(habitat);
			}
			indexedHash = key;
		}
		return BY_CHUNK;
	}

	// ---- views (tests, diagnostics) ----

	public static Counts counts() {
		return COUNTS;
	}
}
