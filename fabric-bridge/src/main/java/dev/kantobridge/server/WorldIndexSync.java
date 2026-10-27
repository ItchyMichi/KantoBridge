package dev.kantobridge.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.ShapeDocs;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Gives every joining player the world's WorldIndex as {@code world.index} parts (docs/PLAN.md §2.2, PROTOCOL.md
 * "Relay only"), so clients can map blocks to cells, the world's slot map as {@code world.slots} parts (M9), so a
 * joining player whose own pack was made for other slots can build their art into the host's (PLAN §4.3 "Multiplayer
 * clients"), and the shape documents as {@code world.shapes} (the voxel plan's V2). Only the slot assignments travel;
 * the art never leaves each player's machine.
 *
 * <p><b>Every document on demand</b> (MG9 G2G, docs/specs/GEN2_BRIDGE_PLAN.md §8): the index and the slot map are
 * offered as the shapes are: {@code world.index.offer} and {@code world.slots.offer} name each by the SHA-256 of its
 * text with its wire size, and a client that lacks one asks with {@code world.index.get} or {@code world.slots.get};
 * the parts that answer carry it deflated ({@code enc:"deflate"}). A rejoin of an unchanged world sends offers only.
 *
 * <p><b>Shapes on demand</b> (2026-10-15, docs/SEVERAL_GAMES_FOLLOWUPS.md item 4, the voxel plan's R10): a region's
 * shape documents are offered, not pushed: {@code world.shapes.offer} names each by the SHA-256 of what it holds (the
 * shape index's text, the shape map's bytes) with its wire size, and a client that lacks one asks with
 * {@code world.shapes.get}; only then do that document's {@code world.shapes} parts go out (the index deflated too,
 * {@code enc:"deflate"}). A client keeps what it received, so a join sends only the offers once it has them. The
 * same document asked for again within {@link #RESEND_MS} goes out once.
 *
 * <p><b>Regions (MULTI_GAME_PLAN §4.6, MG3):</b> a world holds one index and one pair of shape documents per region
 * and one slot map for all of them. A world with {@code regions.json} sends, on join, {@code world.regions} (the table
 * and every record), then each region's {@code world.index} parts tagged with {@code region}, the one
 * {@code world.slots}, then each region's {@code world.shapes.offer} tagged with {@code region}. A <b>legacy world</b>
 * (no {@code regions.json}: one implicit region, its documents at the root of {@code kantobridge/}) sends what it sent
 * before: {@code world.index}, {@code world.slots}, its shapes' offer, no {@code region} anywhere (§4.14).
 * Every region's documents are read from {@code <world>/kantobridge/} when the server starts and replaced when a build
 * finishes ({@link #publishRegion}); {@link Regions#of} is the view the services read.
 *
 * <p>The slot map is canonicalised and sent only when the publishing region's index names it: a region's
 * {@code slotMapHash} is the hash of that region's slice of the one map (version 2), or of the whole map (version 1).
 * Logs carry part counts and short hashes only.
 */
public final class WorldIndexSync {
	/**
	 * One region as published: its record (null when an invalid index was published raw), the SHA-256 of its index text
	 * and that text's wire parts (deflated, G2G), its index, and its shape documents.
	 */
	private record Published(@Nullable RegionRecord region, String hash, List<WorldIndexParts.Part> parts, @Nullable WorldIndex index,
			@Nullable String shapesHash, @Nullable String shapeMapHash, List<WorldIndexParts.Part> shapeIndexParts,
			List<WorldIndexParts.Part> shapeMapParts) {
	}

	/** One server's published world: its regions in table order, the one slot map, and the view the services read. */
	private static final class World {
		/** The region table (pitch and order); null for a legacy world, which has one implicit region. */
		@Nullable RegionTable table;
		final LinkedHashMap<String, Published> regions = new LinkedHashMap<>();
		/** The slot map's hash (of its canonical text) and that text's wire parts (deflated, G2G); null and empty for none. */
		@Nullable String slotsHash;
		List<WorldIndexParts.Part> slotParts = List.of();
		@Nullable Regions view;
		/** The {@code world.regions} document's parts, built for the world id they name (lazily: the id comes later). */
		@Nullable String regionsWorldId;
		List<WorldIndexParts.Part> regionParts = List.of();
		/** WORLD_SESSIONS_PLAN WS2: the world record (world.json, else the table's), read with the document; null: not yet. */
		dev.kantobridge.world.@Nullable WorldRecord record;

		boolean legacy() {
			return this.table == null;
		}
	}

	/** The key the legacy publish path files its one region under when no record could be made (an invalid index). */
	private static final String NO_RECORD = "";
	private static final String UNKNOWN_WORLD = "unknown";

	private static final Map<MinecraftServer, World> CURRENT = Collections.synchronizedMap(new WeakHashMap<>());
	/** A document a player asked for again sooner than this after it went out is not sent twice. */
	static final long RESEND_MS = 5_000;
	private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<String> GET_FIELDS = Set.of("index", "map", "region");
	private static final Set<String> INDEX_GET_FIELDS = Set.of("hash", "region");
	private static final Set<String> SLOTS_GET_FIELDS = Set.of("hash");
	/** Per player: when each document (region, doc, hash) last went out to them. Server thread. */
	private static final Map<UUID, Map<String, Long>> SENT = new HashMap<>();

	private WorldIndexSync() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WorldIndexSync::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(CURRENT::remove);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(server, handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.getPlayer().getUUID()));
		RelayService.get().register(MsgTypes.WORLD_SHAPES_GET, WorldIndexSync::onShapesGet);
		RelayService.get().register(MsgTypes.WORLD_INDEX_GET, WorldIndexSync::onIndexGet);
		RelayService.get().register(MsgTypes.WORLD_SLOTS_GET, WorldIndexSync::onSlotsGet);
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
		// G2G: the index goes out deflated when a client asks for it; its offer names the text's hash
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(ShapeDocs.wire(json), WorldIndexParts.PART_CHARS);
		if (slots != null) {
			String named = index == null ? null : index.slotMapHash();
			// Version 2: a region's index names the hash of its own slice of the one map; version 1 names the whole map.
			String mine = region != null && slots.version() == SlotMap.VERSION_2 && slots.regions().containsKey(region.id())
					? slots.regionHash(region.id()) : slots.hash();
			if (named != null && mine.equals(named)) {
				// The canonical text: its SHA-256 is the whole map's hash, which the client checks on its own (deflated, G2G).
				world.slotParts = WorldIndexParts.split(ShapeDocs.wire(slots.toJson()), WorldIndexParts.PART_CHARS);
				world.slotsHash = slots.hash();
			} else {
				KantoBridge.LOGGER.warn("world.slots: the slot map ({}) isn't the one the index names; not sent", Hashes.shortHash(slots.hash()));
				world.slotParts = List.of();
				world.slotsHash = null;
			}
		}
		String shapesHash = null;
		String shapeMapHash = null;
		List<WorldIndexParts.Part> shapeIndexParts = List.of();
		List<WorldIndexParts.Part> shapeMapParts = List.of();
		if (shapeIndex != null && shapeMap != null) {
			try {
				ShapeMap map = ShapeMap.decode(shapeMap);
				String indexHash = Hashes.sha256(json);
				String shapeHash = Hashes.sha256(shapeIndex);
				if (map.indexHash().equals(indexHash) && map.shapeHash().equals(shapeHash)) {
					// both deflated (item 4): the index is several times the map as plain text
					shapeIndexParts = WorldIndexParts.split(ShapeDocs.wire(shapeIndex), WorldIndexParts.PART_CHARS);
					shapeMapParts = WorldIndexParts.split(map.wire(), WorldIndexParts.PART_CHARS);
					shapesHash = shapeHash;
					shapeMapHash = map.hash();
				} else {
					KantoBridge.LOGGER.warn("world.shapes: the shape map ({}) doesn't name this index and its shape index; not sent",
							Hashes.shortHash(map.hash()));
				}
			} catch (DataException e) {
				KantoBridge.LOGGER.warn("world.shapes: the shape map is unreadable ({}); not sent", e.problem());
			}
		}
		Published published = new Published(region, Hashes.sha256(json), parts, index, shapesHash, shapeMapHash, shapeIndexParts,
				shapeMapParts);
		world.regions.put(key, published);
		world.regionsWorldId = null;
		world.record = null;
		world.view = view(world);
		KantoBridge.LOGGER.info("world.index{}: {} parts on request, hash {}; world.slots: {} parts on request, hash {}; world.shapes: {}+{} parts on request, index {}",
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

	/** The {@code world.index} parts the server sends for the primary region when asked (deflated, G2G), or an empty list. */
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

	/** The {@code world.slots} parts the server sends when asked (deflated, G2G), or an empty list. */
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

	/** The {@code world.index.offer} the server sends for the primary region, or null when none is published. */
	public static @Nullable JsonObject currentIndexOffer(MinecraftServer server) {
		World world = CURRENT.get(server);
		Published published = primary(world);
		return published == null ? null : indexOffer(published, world.legacy() ? null : regionIdOf(published));
	}

	public static @Nullable JsonObject currentIndexOffer(MinecraftServer server, String regionId) {
		World world = CURRENT.get(server);
		Published published = region(server, regionId);
		return published == null ? null : indexOffer(published, world.legacy() ? null : regionIdOf(published));
	}

	/** The {@code world.slots.offer} the server sends, or null when it sends no slot map. */
	public static @Nullable JsonObject currentSlotsOffer(MinecraftServer server) {
		World world = CURRENT.get(server);
		return world == null || world.slotsHash == null ? null : offerDoc(world.slotsHash, world.slotParts);
	}

	/** The {@code world.shapes.offer} the server sends the primary region's joining players, or null without shapes. */
	public static @Nullable JsonObject currentShapeOffer(MinecraftServer server) {
		World world = CURRENT.get(server);
		Published published = primary(world);
		return published == null ? null : offer(published, world.legacy() ? null : regionIdOf(published));
	}

	public static @Nullable JsonObject currentShapeOffer(MinecraftServer server, String regionId) {
		World world = CURRENT.get(server);
		Published published = region(server, regionId);
		return published == null ? null : offer(published, world.legacy() ? null : regionIdOf(published));
	}

	/** The SHA-256 of the primary region's shape map bytes (what its offer names), or null. */
	public static @Nullable String currentShapeMapHash(MinecraftServer server) {
		Published published = primary(CURRENT.get(server));
		return published == null ? null : published.shapeMapHash();
	}

	/**
	 * The {@code world.shapes} parts the server would send for the primary region when a client asks for both documents,
	 * index first (the index deflated), or an empty list.
	 */
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

	/**
	 * WORLD_SESSIONS_PLAN WS2: the world's record of its games: {@code kantobridge/world.json}, else derived from the region
	 * table (a legacy world: its regions' games, the start slot 0's region; a world with no table: its one region). Null
	 * without a published world.
	 */
	public static dev.kantobridge.world.@Nullable WorldRecord worldRecord(MinecraftServer server) {
		World world = CURRENT.get(server);
		return world == null ? null : record(server, world);
	}

	private static dev.kantobridge.world.@Nullable WorldRecord record(MinecraftServer server, World world) {
		if (world.record != null) {
			return world.record;
		}
		dev.kantobridge.world.WorldRecord record = null;
		try {
			record = dev.kantobridge.world.WorldRecord.read(server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER));
		} catch (IOException | DataException | RuntimeException e) {
			KantoBridge.LOGGER.warn("world.json: the world record is unreadable ({}); the table's is used", e.getClass().getSimpleName());
		}
		if (record == null && world.table != null) {
			record = dev.kantobridge.world.WorldRecord.legacy(world.table);
		}
		if (record == null && world.view != null && world.view.legacy() != null) {
			RegionRecord legacy = world.view.legacy();
			try {
				record = new dev.kantobridge.world.WorldRecord(legacy.id(), List.of(new dev.kantobridge.world.WorldRecord.Game(legacy.game(), legacy.id())));
			} catch (IllegalArgumentException e) {
				record = null;
			}
		}
		world.record = record;
		return record;
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
	 * {@link #send} without asking whether the player's client has our mod. A legacy world: the index's
	 * {@code world.index.offer}, the {@code world.slots.offer}, the shapes' {@code world.shapes.offer}. A region world:
	 * {@code world.regions}, every region's index offer, the slots' offer, every region's shape offer. Every document's
	 * parts follow a client's {@code get} (G2G). Returns the messages sent. GameTests use it (their mock players'
	 * connections never register channels).
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
		int sent = sendParts(player, MsgTypes.WORLD_REGIONS, regionParts, null, false);
		if (sent < regionParts.size()) {
			return sent;
		}
		List<Published> regions = orderedRegions(world);
		for (Published published : regions) {
			// G2G: the index is offered; the client asks for it when its cache lacks it
			if (!send(player, MsgTypes.WORLD_INDEX_OFFER, indexOffer(published, regionIdOf(published)))) {
				return sent;
			}
			sent++;
		}
		if (world.slotsHash != null) {
			if (!send(player, MsgTypes.WORLD_SLOTS_OFFER, offerDoc(world.slotsHash, world.slotParts))) {
				return sent;
			}
			sent++;
		}
		for (Published published : regions) {
			// item 4: the shape documents are offered; the client asks for what it lacks
			if (published.shapesHash() == null) {
				continue;
			}
			if (!sendOffer(player, published, regionIdOf(published))) {
				return sent;
			}
			sent++;
		}
		KantoBridge.LOGGER.debug("world.regions: sent {} parts for {} regions", sent, regions.size());
		return sent;
	}

	private static int sendLegacy(World world, ServerPlayer player) {
		Published published = world.regions.values().iterator().next();
		if (!send(player, MsgTypes.WORLD_INDEX_OFFER, indexOffer(published, null))) {
			return 0;
		}
		int slots = 0;
		if (world.slotsHash != null) {
			if (!send(player, MsgTypes.WORLD_SLOTS_OFFER, offerDoc(world.slotsHash, world.slotParts))) {
				return 1;
			}
			slots = 1;
		}
		int shapes = 0;
		if (published.shapesHash() != null && sendOffer(player, published, null)) {
			shapes = 1;
		}
		KantoBridge.LOGGER.debug("world.index: offered (hash {}), world.slots offers {}, world.shapes offers {}", Hashes.shortHash(published.hash()),
				slots, shapes);
		return 1 + slots + shapes;
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
		// WORLD_SESSIONS_PLAN WS2: the world record's start and games (world.json, else derived from the table)
		dev.kantobridge.world.WorldRecord record = record(server, world);
		if (record != null) {
			JsonObject rec = record.toJsonObject();
			document.add("start", rec.get("start"));
			document.add("games", rec.get("games"));
		}
		world.regionParts = WorldIndexParts.split(ProtoJson.write(document), WorldIndexParts.PART_CHARS);
		world.regionsWorldId = worldId;
		return world.regionParts;
	}

	/** A region's {@code world.index.offer}: its index text's hash and wire size, tagged with {@code tag} (null: untagged). */
	private static JsonObject indexOffer(Published published, @Nullable String tag) {
		JsonObject data = offerDoc(published.hash(), published.parts());
		if (tag != null) {
			data.addProperty("region", tag);
		}
		return data;
	}

	/**
	 * A region's {@code world.shapes.offer}: each document by content hash and wire size (null when it has no shapes),
	 * tagged with {@code tag} (null in a legacy world, whose messages carry no region).
	 */
	private static @Nullable JsonObject offer(Published published, @Nullable String tag) {
		if (published.shapesHash() == null || published.shapeMapHash() == null) {
			return null;
		}
		JsonObject data = new JsonObject();
		data.add("index", offerDoc(published.shapesHash(), published.shapeIndexParts()));
		data.add("map", offerDoc(published.shapeMapHash(), published.shapeMapParts()));
		if (tag != null) {
			data.addProperty("region", tag);
		}
		return data;
	}

	private static JsonObject offerDoc(String hash, List<WorldIndexParts.Part> parts) {
		long chars = 0;
		for (WorldIndexParts.Part part : parts) {
			chars += part.text().length();
		}
		JsonObject doc = new JsonObject();
		doc.addProperty("hash", hash);
		doc.addProperty("chars", chars);
		return doc;
	}

	private static boolean send(ServerPlayer player, String type, JsonObject data) {
		if (!RelayService.get().send(player, type, data, null)) {
			KantoBridge.LOGGER.error("{} could not be sent", type);
			return false;
		}
		return true;
	}

	private static boolean sendOffer(ServerPlayer player, Published published, @Nullable String tag) {
		JsonObject data = offer(published, tag);
		if (data == null) {
			return true;
		}
		if (!RelayService.get().send(player, MsgTypes.WORLD_SHAPES_OFFER, data, null)) {
			KantoBridge.LOGGER.error("world.shapes.offer could not be sent");
			return false;
		}
		return true;
	}

	/**
	 * {@code world.shapes.get}: the documents a client lacks, each named by the content hash its offer gave. A document the
	 * server still publishes goes out as {@code world.shapes} parts, once per {@link #RESEND_MS}; one it no longer
	 * publishes (a rebuild came in between: a new offer is on its way) is left out. Server thread.
	 */
	static void onShapesGet(ServerPlayer player, Envelope message) {
		String region;
		String indexHash;
		String mapHash;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(GET_FIELDS);
			indexHash = reader.optionalString("index", CONTENT_HASH);
			mapHash = reader.optionalString("map", CONTENT_HASH);
			region = reader.optionalRegion();
			if (indexHash == null && mapHash == null) {
				throw new DataException("/", "names no document");
			}
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("world.shapes.get: malformed at {} ({})", e.path(), e.problem());
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		// absent: the legacy region (a legacy world's one region, else slot 0's)
		World world = CURRENT.get(player.server);
		Published published = world == null ? null : region == null ? primary(world) : world.regions.get(region);
		if (published == null) {
			KantoBridge.LOGGER.debug("world.shapes.get: no such region");
			return;
		}
		String tag = world.legacy() ? null : regionIdOf(published);
		long now = System.currentTimeMillis();
		Map<String, Long> sent = sentTo(player);
		if (indexHash != null && indexHash.equals(published.shapesHash()) && due(sent, tag, "index", indexHash, now)) {
			sendShapes(player, "index", published.shapeIndexParts(), tag);
		}
		if (mapHash != null && mapHash.equals(published.shapeMapHash()) && due(sent, tag, "map", mapHash, now)) {
			sendShapes(player, "map", published.shapeMapParts(), tag);
		}
	}

	/**
	 * {@code world.index.get}: a region's index a client lacks, named by the hash its offer gave. The index the server still
	 * publishes goes out as deflated {@code world.index} parts, once per {@link #RESEND_MS}; an older one (a rebuild came in
	 * between: a new offer is on its way) is left out. Server thread.
	 */
	static void onIndexGet(ServerPlayer player, Envelope message) {
		String region;
		String hash;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(INDEX_GET_FIELDS);
			hash = reader.string("hash", CONTENT_HASH);
			region = reader.optionalRegion();
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("world.index.get: malformed at {} ({})", e.path(), e.problem());
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		// absent: the legacy region (a legacy world's one region, else slot 0's)
		World world = CURRENT.get(player.server);
		Published published = world == null ? null : region == null ? primary(world) : world.regions.get(region);
		if (published == null) {
			KantoBridge.LOGGER.debug("world.index.get: no such region");
			return;
		}
		String tag = world.legacy() ? null : regionIdOf(published);
		if (hash.equals(published.hash()) && due(sentTo(player), tag, "world", hash, System.currentTimeMillis())) {
			sendParts(player, MsgTypes.WORLD_INDEX, published.parts(), tag, true);
		}
	}

	/** {@code world.slots.get}: the slot map a client lacks, by the hash its offer gave; as {@link #onIndexGet}. Server thread. */
	static void onSlotsGet(ServerPlayer player, Envelope message) {
		String hash;
		try {
			hash = DataReader.of(message.data(), "").allowOnly(SLOTS_GET_FIELDS).string("hash", CONTENT_HASH);
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("world.slots.get: malformed at {} ({})", e.path(), e.problem());
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		World world = CURRENT.get(player.server);
		if (world == null || !hash.equals(world.slotsHash)) {
			KantoBridge.LOGGER.debug("world.slots.get: not the published slot map");
			return;
		}
		if (due(sentTo(player), null, "slots", hash, System.currentTimeMillis())) {
			sendParts(player, MsgTypes.WORLD_SLOTS, world.slotParts, null, true);
		}
	}

	private static Map<String, Long> sentTo(ServerPlayer player) {
		return SENT.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
	}

	/** True (and noted) when this document didn't go out to the player within {@link #RESEND_MS}. */
	private static boolean due(Map<String, Long> sent, @Nullable String region, String doc, String hash, long now) {
		String key = (region == null ? "" : region) + "/" + doc + "/" + hash;
		Long last = sent.get(key);
		if (last != null && now - last < RESEND_MS) {
			return false;
		}
		sent.put(key, now);
		return true;
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
			if ("index".equals(doc)) {
				data.addProperty("enc", ShapeDocs.ENC_DEFLATE);
			}
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

	/** Sends a document's parts; {@code deflated}: their text is the document's wire form ({@code enc:"deflate"}). */
	private static int sendParts(ServerPlayer player, String type, List<WorldIndexParts.Part> parts, @Nullable String region, boolean deflated) {
		int sent = 0;
		for (WorldIndexParts.Part part : parts) {
			JsonObject data = new JsonObject();
			data.addProperty("index", part.index());
			data.addProperty("count", part.count());
			data.addProperty("hash", part.hash());
			data.addProperty("part", part.text());
			if (deflated) {
				data.addProperty("enc", ShapeDocs.ENC_DEFLATE);
			}
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
