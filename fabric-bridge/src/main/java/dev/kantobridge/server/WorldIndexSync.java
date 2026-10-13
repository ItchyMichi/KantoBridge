package dev.kantobridge.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Sends the world's WorldIndex to every joining player as {@code world.index} parts (docs/PLAN.md §2.2, PROTOCOL.md
 * "Relay only"), so clients can map blocks to cells, followed by the world's slot map as {@code world.slots} parts (M9),
 * so a joining player whose own pack was made for other slots can build their art into the host's (PLAN §4.3
 * "Multiplayer clients"), and the shape documents as {@code world.shapes} (the voxel plan's V2). Only the slot
 * assignments travel; the art never leaves each player's machine.
 *
 * <p><b>Regions (MULTI_GAME_PLAN §4.6, MG3):</b> a world holds one index and one pair of shape documents per region
 * and one slot map for all of them. A world with {@code regions.json} sends, on join, {@code world.regions} (the table
 * and every record), then each region's {@code world.index} parts tagged with {@code region}, the one
 * {@code world.slots}, then each region's {@code world.shapes} tagged with {@code region}. A <b>legacy world</b> (no
 * {@code regions.json}: one implicit region, its documents at the root of {@code kantobridge/}) sends exactly what it
 * sent before: {@code world.index}, {@code world.slots}, {@code world.shapes}, no {@code region} anywhere (§4.14).
 * Every region's documents are read from {@code <world>/kantobridge/} when the server starts and replaced when a build
 * finishes ({@link #publishRegion}); {@link Regions#of} is the view the services read.
 *
 * <p>The slot map is canonicalised and sent only when the publishing region's index names it: a region's
 * {@code slotMapHash} is the hash of that region's slice of the one map (version 2), or of the whole map (version 1).
 * Logs carry part counts and short hashes only.
 */
public final class WorldIndexSync {
	/** One region as published: its record (null when an invalid index was published raw), index and shape documents. */
	private record Published(@Nullable RegionRecord region, String hash, List<WorldIndexParts.Part> parts, @Nullable WorldIndex index,
			@Nullable String shapesHash, List<WorldIndexParts.Part> shapeIndexParts, List<WorldIndexParts.Part> shapeMapParts) {
	}

	/** One server's published world: its regions in table order, the one slot map, and the view the services read. */
	private static final class World {
		/** The region table (pitch and order); null for a legacy world, which has one implicit region. */
		@Nullable RegionTable table;
		final LinkedHashMap<String, Published> regions = new LinkedHashMap<>();
		@Nullable String slotsHash;
		List<WorldIndexParts.Part> slotParts = List.of();
		@Nullable Regions view;
		/** The {@code world.regions} document's parts, built for the world id they name (lazily: the id comes later). */
		@Nullable String regionsWorldId;
		List<WorldIndexParts.Part> regionParts = List.of();

		boolean legacy() {
			return this.table == null;
		}
	}

	/** The key the legacy publish path files its one region under when no record could be made (an invalid index). */
	private static final String NO_RECORD = "";
	private static final String UNKNOWN_WORLD = "unknown";

	private static final Map<MinecraftServer, World> CURRENT = Collections.synchronizedMap(new WeakHashMap<>());

	private WorldIndexSync() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WorldIndexSync::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(CURRENT::remove);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(server, handler.getPlayer()));
	}

	// ---- loading ----

	/**
	 * Reads the world's documents: with {@code regions.json}, every region's record, index and shapes; otherwise the
	 * root index file and its slot map and shapes beside it, if the world has them (a legacy world).
	 */
	public static void load(MinecraftServer server) {
		Path data = server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER);
		Path tableFile = RegionTable.file(data);
		if (Files.isRegularFile(tableFile)) {
			loadRegions(server, data, tableFile);
			return;
		}
		Path file = data.resolve(BuildTask.INDEX_FILE);
		if (!Files.isRegularFile(file)) {
			return;
		}
		String json;
		WorldIndex index;
		try {
			json = Files.readString(file, StandardCharsets.UTF_8);
			index = WorldIndex.fromJson(json);
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.warn("world.index: the world's index is unreadable ({}); clients won't get one", e.getClass().getSimpleName());
			return;
		}
		String[] shapes = new String[1];
		byte[][] map = new byte[1][];
		readShapes(data.resolve(BuildTask.SHAPE_INDEX_FILE), data.resolve(BuildTask.SHAPE_MAP_FILE), shapes, map);
		publish(server, index, json, readSlots(data.resolve(BuildTask.SLOT_MAP_FILE)), shapes[0], map[0], false);
	}

	/** A region world: the table, the one slot map, then every region's documents (an unreadable region is skipped). */
	private static void loadRegions(MinecraftServer server, Path data, Path tableFile) {
		RegionTable table;
		try {
			table = RegionTable.fromJson(Files.readString(tableFile, StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.warn("world.regions: the world's region table is unreadable ({}); clients won't get a world", e.getClass().getSimpleName());
			return;
		}
		SlotMap slots = readSlots(data.resolve(BuildTask.SLOT_MAP_FILE));
		World world = new World();
		world.table = table;
		CURRENT.put(server, world);
		int published = 0;
		for (RegionTable.Entry entry : table.entries()) {
			RegionRecord record;
			WorldIndex index;
			try {
				record = RegionRecord.fromJson(Files.readString(data.resolve(RegionRecord.fileRecord(entry.id())), StandardCharsets.UTF_8));
				index = WorldIndex.fromJson(Files.readString(data.resolve(record.files().index()), StandardCharsets.UTF_8));
			} catch (IOException | DataException e) {
				KantoBridge.LOGGER.warn("world.regions: region {}'s record or index is unreadable ({}); it is left out", entry.id(),
						e.getClass().getSimpleName());
				continue;
			}
			if (!record.id().equals(entry.id()) || record.slot() != entry.slot() || !record.game().equals(entry.game())) {
				KantoBridge.LOGGER.warn("world.regions: region {}'s record disagrees with the table; it is left out", entry.id());
				continue;
			}
			String[] shapes = new String[1];
			byte[][] map = new byte[1][];
			readShapes(data.resolve(record.files().shapeIndex()), data.resolve(record.files().shapeMap()), shapes, map);
			publishRegion(server, record, index, slots, shapes[0], map[0], false);
			published++;
		}
		KantoBridge.LOGGER.info("world.regions: {} of {} regions published", published, table.size());
	}

	private static void readShapes(Path shapeIndex, Path shapeMap, String[] shapes, byte[][] map) {
		if (Files.isRegularFile(shapeIndex) && Files.isRegularFile(shapeMap)) {
			try {
				shapes[0] = Files.readString(shapeIndex, StandardCharsets.UTF_8);
				map[0] = Files.readAllBytes(shapeMap);
			} catch (IOException e) {
				KantoBridge.LOGGER.warn("world.shapes: the world's shape files are unreadable ({}); clients draw cubes", e.getClass().getSimpleName());
				shapes[0] = null;
				map[0] = null;
			}
		}
	}

	/** The world's stored slot map, or null when it has none or it is unreadable (a warning). */
	private static @Nullable SlotMap readSlots(Path file) {
		if (!Files.isRegularFile(file)) {
			KantoBridge.LOGGER.warn("world.slots: the world has no slot map; joining players keep their own packs");
			return null;
		}
		try {
			return SlotMap.fromJson(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.warn("world.slots: the world's slot map is unreadable ({}); joining players keep their own packs",
					e.getClass().getSimpleName());
			return null;
		}
	}

	// ---- publishing (legacy world: one implicit region) ----

	/** Makes {@code index} the one joining players get (without a slot map); with {@code broadcast}, sends it to everyone. */
	public static void publish(MinecraftServer server, WorldIndex index, boolean broadcast) {
		publish(server, index, index.toJson(), null, null, null, broadcast);
	}

	/**
	 * Makes {@code index} and the slot map it was built with ({@code slots}, null for none) the ones joining players get;
	 * with {@code broadcast}, sends both to everyone online now. A slot map whose hash isn't the index's is left out.
	 */
	public static void publish(MinecraftServer server, WorldIndex index, @Nullable SlotMap slots, boolean broadcast) {
		publish(server, index, index.toJson(), slots, null, null, broadcast);
	}

	/**
	 * {@link #publish(MinecraftServer, WorldIndex, SlotMap, boolean)} with the world's shapes: the shape index's text and
	 * the shape map's bytes (both null for none). They go out only when the map's header names this index and that
	 * shape index; otherwise clients draw cubes.
	 */
	public static void publish(MinecraftServer server, WorldIndex index, @Nullable SlotMap slots, @Nullable String shapeIndex,
			byte @Nullable [] shapeMap, boolean broadcast) {
		publish(server, index, index.toJson(), slots, shapeIndex, shapeMap, broadcast);
	}

	/** {@link #publish(MinecraftServer, WorldIndex, boolean)} for index JSON text (checked; an invalid one refuses warps). */
	public static void publish(MinecraftServer server, String json, boolean broadcast) {
		WorldIndex index = null;
		try {
			index = WorldIndex.fromJson(json);
		} catch (DataException e) {
			// Still handed to clients (they check it too), but the server can't resolve warps with it.
			KantoBridge.LOGGER.warn("world.index: the published index is invalid ({}); warps will be refused", e.problem());
		}
		publish(server, index, json, null, null, null, broadcast);
	}

	/**
	 * The legacy publish: the world becomes one implicit region ({@link RegionRecord#legacy}) whose documents sit at the
	 * root; joining players get today's sequence. Replaces whatever the server published before.
	 */
	private static void publish(MinecraftServer server, @Nullable WorldIndex index, String json, @Nullable SlotMap slots,
			@Nullable String shapeIndex, byte @Nullable [] shapeMap, boolean broadcast) {
		RegionRecord record = null;
		if (index != null) {
			String shapeIndexHash = shapeIndex == null ? null : Hashes.sha256(shapeIndex);
			String shapeMapHash = shapeMap == null ? null : Hashes.sha256(shapeMap);
			record = RegionRecord.legacy(index, null, shapeIndexHash, shapeMapHash);
		}
		World world = new World();
		CURRENT.put(server, world);
		publishInto(server, world, record == null ? NO_RECORD : record.id(), record, index, json, slots, shapeIndex, shapeMap, broadcast);
	}

	// ---- publishing (region worlds) ----

	/**
	 * The region table of a region world ({@code regions.json}): sets the pitch and the order joining players see the
	 * regions in. Publishing a region without it makes the table from the published records (the default pitch).
	 */
	public static void publishTable(MinecraftServer server, RegionTable table) {
		World world = CURRENT.get(server);
		if (world == null || world.legacy()) {
			world = new World();
			CURRENT.put(server, world);
		}
		world.table = table;
		world.regionsWorldId = null;
		world.view = view(world);
	}

	/**
	 * Makes a region's documents the ones joining players get (MG3: the builder calls it when a region's build finishes,
	 * {@link #load} for every region at start): its record, its index, the world's one slot map ({@code slots}, null to
	 * keep the one published before; sent only when this index names its slice), and its shape documents (both null for
	 * none; sent only when the map's header names this index and shape index). A legacy world becomes a region world: the
	 * implicit region's root documents are replaced by the records published from now on. With {@code broadcast}, everyone
	 * online gets the whole world again.
	 */
	public static void publishRegion(MinecraftServer server, RegionRecord region, WorldIndex index, @Nullable SlotMap slots,
			@Nullable String shapeIndexText, byte @Nullable [] shapeMap, boolean broadcast) {
		World world = CURRENT.get(server);
		if (world == null || world.legacy()) {
			world = new World();
			world.table = RegionTable.empty();
			CURRENT.put(server, world);
		}
		world.table = world.table.without(region.id()).with(new RegionTable.Entry(region.id(), region.game(), region.slot()));
		publishInto(server, world, region.id(), region, index, index.toJson(), slots, shapeIndexText, shapeMap, broadcast);
	}

	private static void publishInto(MinecraftServer server, World world, String key, @Nullable RegionRecord region, @Nullable WorldIndex index,
			String json, @Nullable SlotMap slots, @Nullable String shapeIndex, byte @Nullable [] shapeMap, boolean broadcast) {
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(json, WorldIndexParts.PART_CHARS);
		if (slots != null) {
			String named = index == null ? null : index.slotMapHash();
			// Version 2: a region's index names the hash of its own slice of the one map; version 1 names the whole map.
			String mine = region != null && slots.version() == SlotMap.VERSION_2 && slots.regions().containsKey(region.id())
					? slots.regionHash(region.id()) : slots.hash();
			if (named != null && mine.equals(named)) {
				// The canonical text: its SHA-256 is the whole map's hash, which the client checks on its own.
				world.slotParts = WorldIndexParts.split(slots.toJson(), WorldIndexParts.PART_CHARS);
				world.slotsHash = slots.hash();
			} else {
				KantoBridge.LOGGER.warn("world.slots: the slot map ({}) isn't the one the index names; not sent", Hashes.shortHash(slots.hash()));
				world.slotParts = List.of();
				world.slotsHash = null;
			}
		}
		String shapesHash = null;
		List<WorldIndexParts.Part> shapeIndexParts = List.of();
		List<WorldIndexParts.Part> shapeMapParts = List.of();
		if (shapeIndex != null && shapeMap != null) {
			try {
				ShapeMap map = ShapeMap.decode(shapeMap);
				String indexHash = Hashes.sha256(json);
				String shapeHash = Hashes.sha256(shapeIndex);
				if (map.indexHash().equals(indexHash) && map.shapeHash().equals(shapeHash)) {
					shapeIndexParts = WorldIndexParts.split(shapeIndex, WorldIndexParts.PART_CHARS);
					shapeMapParts = WorldIndexParts.split(map.wire(), WorldIndexParts.PART_CHARS);
					shapesHash = shapeHash;
				} else {
					KantoBridge.LOGGER.warn("world.shapes: the shape map ({}) doesn't name this index and its shape index; not sent",
							Hashes.shortHash(map.hash()));
				}
			} catch (DataException e) {
				KantoBridge.LOGGER.warn("world.shapes: the shape map is unreadable ({}); not sent", e.problem());
			}
		}
		Published published = new Published(region, parts.get(0).hash(), parts, index, shapesHash, shapeIndexParts, shapeMapParts);
		world.regions.put(key, published);
		world.regionsWorldId = null;
		world.view = view(world);
		KantoBridge.LOGGER.info("world.index{}: {} parts, hash {}; world.slots: {} parts, hash {}; world.shapes: {}+{} parts, index {}",
				world.legacy() ? "" : " (" + key + ")", parts.size(), Hashes.shortHash(published.hash()), world.slotParts.size(),
				world.slotsHash == null ? "-" : Hashes.shortHash(world.slotsHash), shapeIndexParts.size(), shapeMapParts.size(),
				shapesHash == null ? "-" : Hashes.shortHash(shapesHash));
		if (broadcast) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				send(server, player);
			}
		}
	}

	/** The services' view of the published world, or null when no region has a record (an invalid index published raw). */
	private static @Nullable Regions view(World world) {
		Map<String, RegionRecord> records = new LinkedHashMap<>();
		Map<String, WorldIndex> indices = new LinkedHashMap<>();
		List<RegionTable.Entry> entries = new ArrayList<>();
		for (Published published : orderedRegions(world)) {
			if (published.region() == null) {
				continue;
			}
			records.put(published.region().id(), published.region());
			if (published.index() != null) {
				indices.put(published.region().id(), published.index());
			}
			entries.add(new RegionTable.Entry(published.region().id(), published.region().game(), published.region().slot()));
		}
		if (records.isEmpty()) {
			return null;
		}
		try {
			RegionTable table = world.table == null ? new RegionTable(entries) : new RegionTable(world.table.pitch(), entries);
			return new Regions(new dev.kantobridge.world.Regions(table, records), indices);
		} catch (IllegalArgumentException e) {
			KantoBridge.LOGGER.warn("world.regions: the published records don't make a table ({}); services see no regions", e.getMessage());
			return null;
		}
	}

	/** The published regions in table order (slot order), the ones the table lacks last. */
	private static List<Published> orderedRegions(World world) {
		List<Published> out = new ArrayList<>();
		if (world.table != null) {
			for (RegionTable.Entry entry : world.table.entries()) {
				Published published = world.regions.get(entry.id());
				if (published != null) {
					out.add(published);
				}
			}
		}
		for (Published published : world.regions.values()) {
			if (!out.contains(published)) {
				out.add(published);
			}
		}
		return out;
	}

	/** The region every single-index view means: the legacy region (slot 0), else the first published one. */
	private static @Nullable Published primary(@Nullable World world) {
		if (world == null || world.regions.isEmpty()) {
			return null;
		}
		for (Published published : world.regions.values()) {
			if (published.region() != null && published.region().slot() == 0) {
				return published;
			}
		}
		return orderedRegions(world).get(0);
	}

	private static @Nullable Published region(MinecraftServer server, String regionId) {
		World world = CURRENT.get(server);
		return world == null ? null : world.regions.get(regionId);
	}

	/**
	 * Forgets the server's published world (no more {@code world.index} for joining players, no warps, no habitat checks).
	 * For GameTests that publish one for themselves: a lingering index would make the runtime habitat check judge a later
	 * build's blocks before they are placed.
	 */
	public static void withdraw(MinecraftServer server) {
		CURRENT.remove(server);
	}

	// ---- views ----

	/** The services' view of the world's regions, or null when none is published. */
	public static @Nullable Regions regions(@Nullable MinecraftServer server) {
		World world = server == null ? null : CURRENT.get(server);
		return world == null ? null : world.view;
	}

	/** True for a world published with {@code regions.json} (several regions, the new wire); false for a legacy world or none. */
	public static boolean isRegionWorld(@Nullable MinecraftServer server) {
		World world = server == null ? null : CURRENT.get(server);
		return world != null && !world.legacy();
	}

	/**
	 * The server's current WorldIndex (where every map lies), or null when the world has none. With several regions it is
	 * the legacy region's (slot 0), else the first published one: callers that know their region ask
	 * {@link #current(MinecraftServer, String)} or {@link Regions}.
	 */
	public static @Nullable WorldIndex current(MinecraftServer server) {
		Published published = primary(CURRENT.get(server));
		return published == null ? null : published.index();
	}

	/** One region's published WorldIndex, or null. */
	public static @Nullable WorldIndex current(MinecraftServer server, String regionId) {
		Published published = region(server, regionId);
		return published == null ? null : published.index();
	}

	/** The hash of the index the server currently hands out (the primary region's), or null. */
	public static @Nullable String currentHash(MinecraftServer server) {
		Published published = primary(CURRENT.get(server));
		return published == null ? null : published.hash();
	}

	public static @Nullable String currentHash(MinecraftServer server, String regionId) {
		Published published = region(server, regionId);
		return published == null ? null : published.hash();
	}

	/** The {@code world.index} parts the server would send for the primary region, or an empty list. */
	public static List<WorldIndexParts.Part> currentParts(MinecraftServer server) {
		Published published = primary(CURRENT.get(server));
		return published == null ? List.of() : published.parts();
	}

	public static List<WorldIndexParts.Part> currentParts(MinecraftServer server, String regionId) {
		Published published = region(server, regionId);
		return published == null ? List.of() : published.parts();
	}

	/** The hash of the slot map the server hands out ({@code world.slots}), or null when it sends none. */
	public static @Nullable String currentSlotsHash(MinecraftServer server) {
		World world = CURRENT.get(server);
		return world == null ? null : world.slotsHash;
	}

	/** The {@code world.slots} parts the server would send, or an empty list. */
	public static List<WorldIndexParts.Part> currentSlotParts(MinecraftServer server) {
		World world = CURRENT.get(server);
		return world == null ? List.of() : world.slotParts;
	}

	/** The hash of the shape index the server sends for the primary region ({@code world.shapes}), or null. */
	public static @Nullable String currentShapesHash(MinecraftServer server) {
		Published published = primary(CURRENT.get(server));
		return published == null ? null : published.shapesHash();
	}

	public static @Nullable String currentShapesHash(MinecraftServer server, String regionId) {
		Published published = region(server, regionId);
		return published == null ? null : published.shapesHash();
	}

	/** The {@code world.shapes} parts the server would send for the primary region, index first, or an empty list. */
	public static List<WorldIndexParts.Part> currentShapeParts(MinecraftServer server) {
		return shapeParts(primary(CURRENT.get(server)));
	}

	public static List<WorldIndexParts.Part> currentShapeParts(MinecraftServer server, String regionId) {
		return shapeParts(region(server, regionId));
	}

	private static List<WorldIndexParts.Part> shapeParts(@Nullable Published published) {
		if (published == null) {
			return List.of();
		}
		List<WorldIndexParts.Part> out = new ArrayList<>(published.shapeIndexParts());
		out.addAll(published.shapeMapParts());
		return out;
	}

	/** The {@code world.regions} parts a region world would send (empty for a legacy world or none). */
	public static List<WorldIndexParts.Part> currentRegionParts(MinecraftServer server) {
		World world = CURRENT.get(server);
		return world == null || world.legacy() ? List.of() : regionParts(server, world);
	}

	// ---- sending ----

	/** Sends the current world to one player whose client has our mod. Returns the number of parts sent. */
	public static int send(MinecraftServer server, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, KbJsonPayload.TYPE)) {
			return 0;
		}
		return sendTo(server, player);
	}

	/**
	 * {@link #send} without asking whether the player's client has our mod. A legacy world: the {@code world.index}
	 * parts, the {@code world.slots} parts, the {@code world.shapes} parts. A region world: {@code world.regions}, every
	 * region's {@code world.index}, the slots, every region's shapes. GameTests use it (their mock players' connections
	 * never register channels).
	 */
	public static int sendTo(MinecraftServer server, ServerPlayer player) {
		World world = CURRENT.get(server);
		if (world == null || world.regions.isEmpty()) {
			return 0;
		}
		if (world.legacy()) {
			return sendLegacy(world, player);
		}
		List<WorldIndexParts.Part> regionParts = regionParts(server, world);
		int sent = sendParts(player, MsgTypes.WORLD_REGIONS, regionParts, null);
		if (sent < regionParts.size()) {
			return sent;
		}
		List<Published> regions = orderedRegions(world);
		for (Published published : regions) {
			int count = sendParts(player, MsgTypes.WORLD_INDEX, published.parts(), regionIdOf(published));
			sent += count;
			if (count < published.parts().size()) {
				return sent;
			}
		}
		int slots = sendParts(player, MsgTypes.WORLD_SLOTS, world.slotParts, null);
		sent += slots;
		if (slots < world.slotParts.size()) {
			return sent;
		}
		for (Published published : regions) {
			String region = regionIdOf(published);
			int shapes = sendShapes(player, "index", published.shapeIndexParts(), region);
			sent += shapes;
			if (shapes < published.shapeIndexParts().size()) {
				return sent;
			}
			shapes = sendShapes(player, "map", published.shapeMapParts(), region);
			sent += shapes;
			if (shapes < published.shapeMapParts().size()) {
				return sent;
			}
		}
		KantoBridge.LOGGER.debug("world.regions: sent {} parts for {} regions", sent, regions.size());
		return sent;
	}

	private static int sendLegacy(World world, ServerPlayer player) {
		Published published = world.regions.values().iterator().next();
		int sent = sendParts(player, MsgTypes.WORLD_INDEX, published.parts(), null);
		if (sent < published.parts().size()) {
			return sent;
		}
		int slots = sendParts(player, MsgTypes.WORLD_SLOTS, world.slotParts, null);
		int shapes = 0;
		if (slots == world.slotParts.size()) {
			shapes = sendShapes(player, "index", published.shapeIndexParts(), null);
			if (shapes == published.shapeIndexParts().size()) {
				shapes += sendShapes(player, "map", published.shapeMapParts(), null);
			}
		}
		KantoBridge.LOGGER.debug("world.index: sent {} parts (hash {}), world.slots {} parts, world.shapes {} parts", sent,
				Hashes.shortHash(published.hash()), slots, shapes);
		return sent + slots + shapes;
	}

	private static @Nullable String regionIdOf(Published published) {
		return published.region() == null ? null : published.region().id();
	}

	/**
	 * The {@code world.regions} document, {@code {"version":1,"world":<id>,"table":{…},"regions":[…]}} in canonical JSON,
	 * split into parts; rebuilt when the world id (known once the session record is read) or the regions changed.
	 */
	private static List<WorldIndexParts.Part> regionParts(MinecraftServer server, World world) {
		String worldId = SessionService.worldId(server);
		if (worldId == null) {
			worldId = UNKNOWN_WORLD;
		}
		if (worldId.equals(world.regionsWorldId) && !world.regionParts.isEmpty()) {
			return world.regionParts;
		}
		Regions view = world.view;
		JsonObject document = new JsonObject();
		document.addProperty("version", 1);
		document.addProperty("world", worldId);
		JsonArray records = new JsonArray();
		try {
			RegionTable table = view == null ? (world.table == null ? RegionTable.empty() : world.table) : view.table();
			document.add("table", ProtoJson.parseObject(table.toJson()));
			if (view != null) {
				for (RegionRecord record : view.regions().records().values()) {
					records.add(ProtoJson.parseObject(record.toJson()));
				}
			}
		} catch (ProtocolException e) {
			// Our own canonical text always parses.
			throw new IllegalStateException("region documents don't parse", e);
		}
		document.add("regions", records);
		world.regionParts = WorldIndexParts.split(ProtoJson.write(document), WorldIndexParts.PART_CHARS);
		world.regionsWorldId = worldId;
		return world.regionParts;
	}

	private static int sendShapes(ServerPlayer player, String doc, List<WorldIndexParts.Part> parts, @Nullable String region) {
		int sent = 0;
		for (WorldIndexParts.Part part : parts) {
			JsonObject data = new JsonObject();
			data.addProperty("doc", doc);
			data.addProperty("index", part.index());
			data.addProperty("count", part.count());
			data.addProperty("hash", part.hash());
			data.addProperty("part", part.text());
			if (region != null) {
				data.addProperty("region", region);
			}
			if (!RelayService.get().send(player, MsgTypes.WORLD_SHAPES, data, null)) {
				KantoBridge.LOGGER.error("world.shapes: {} part {}/{} could not be sent", doc, part.index() + 1, part.count());
				return sent;
			}
			sent++;
		}
		return sent;
	}

	private static int sendParts(ServerPlayer player, String type, List<WorldIndexParts.Part> parts, @Nullable String region) {
		int sent = 0;
		for (WorldIndexParts.Part part : parts) {
			JsonObject data = new JsonObject();
			data.addProperty("index", part.index());
			data.addProperty("count", part.count());
			data.addProperty("hash", part.hash());
			data.addProperty("part", part.text());
			if (region != null) {
				data.addProperty("region", region);
			}
			if (!RelayService.get().send(player, type, data, null)) {
				KantoBridge.LOGGER.error("{}: part {}/{} could not be sent", type, part.index() + 1, part.count());
				return sent;
			}
			sent++;
		}
		return sent;
	}
}
