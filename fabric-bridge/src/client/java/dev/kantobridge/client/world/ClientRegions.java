package dev.kantobridge.client.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.shape.ShapeDocCache;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Regions;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The regions of the world this client is in (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): the Minecraft glue around
 * {@link RegionDocuments}. {@code world.regions}, {@code world.index} and {@code world.shapes} parts are fed here by
 * {@link ClientWorldIndex} (which keeps the world-level slot map and stays the shim every older call site reads);
 * {@link #followed()} is the region whose index {@code ClientWorldIndex.current()} returns: the one MG4's link manager
 * binds, else the only region, else the one under the player's feet. Client thread only. Logs carry ids, part counts
 * and short hashes, never the contents.
 *
 * <p>Shapes on demand (the follow-up's item 4): on a region's {@code world.shapes.offer} the documents it lacks are
 * looked up in {@link ShapeDocCache} (off the client thread: reading back means inflating and hashing them), and the
 * ones still missing are asked for with one {@code world.shapes.get}; every document that comes in is written to the
 * cache. A rejoin of an unchanged world therefore downloads no shapes at all.
 *
 * <p>Since MG9 G2G a region's index comes the same way ({@code world.index.offer}, the cache, {@code world.index.get},
 * deflated {@code world.index} parts), so a rejoin downloads no index either.
 */
public final class ClientRegions {
	private static final RegionDocuments DOCS = new RegionDocuments();
	private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static @Nullable ShapeDocCache cache;

	static {
		DOCS.onDocument(ClientRegions::keep);
		DOCS.onIndexDocument(ClientRegions::keepIndex);
	}

	private ClientRegions() {
	}

	/** The pure state behind this glue (tests drive it directly). */
	static RegionDocuments documents() {
		return DOCS;
	}

	/** Runs {@code listener} on the client thread whenever the set changes (a table, an index, a clear). */
	public static void addListener(Runnable listener) {
		DOCS.addListener(listener);
	}

	/** Sets the one listener for a region's complete shape pair: (region, the shape index's text, the shape map's wire form). */
	public static void onShapes(RegionDocuments.ShapesListener listener) {
		DOCS.onShapes(listener);
	}

	// ---- views ----

	/** The regions and their epoch, or null before any region is known. */
	public static RegionDocuments.@Nullable RegionSet set() {
		return DOCS.set();
	}

	public static long epoch() {
		return DOCS.epoch();
	}

	public static @Nullable Regions regions() {
		return DOCS.regions();
	}

	/** True once the server sent a region table (a region world); false in a legacy world and outside any world. */
	public static boolean regionWorld() {
		return DOCS.regionWorld();
	}

	public static @Nullable RegionRecord byId(@Nullable String id) {
		return DOCS.byId(id);
	}

	/** The region built from {@code game}'s export, or null (MG4: the region of a link). */
	public static @Nullable RegionRecord byGame(@Nullable String game) {
		return DOCS.byGame(game);
	}

	/** The region block (x, y, z) lies in (its bounds, else its areas; y plays no part: regions span the height), or null. */
	public static @Nullable RegionRecord at(int x, int y, int z) {
		return DOCS.at(x, z);
	}

	/**
	 * The followed region: the one bound with {@link #setFollowed} (MG4: the followed link's), else the only region, else
	 * the region under the local player's feet; null when none of those names one.
	 */
	public static @Nullable RegionRecord followed() {
		RegionRecord bound = DOCS.followed();
		if (bound != null) {
			return bound;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null) {
			return null;
		}
		return DOCS.at(player.getBlockX(), player.getBlockZ());
	}

	/** Binds the followed region (MG4); null unbinds. */
	public static void setFollowed(@Nullable String id) {
		DOCS.setFollowed(id);
	}

	/** The followed region's index, or null (what {@link ClientWorldIndex#current()} returns). */
	public static @Nullable WorldIndex followedIndex() {
		RegionRecord record = followed();
		return record == null ? null : DOCS.indexOf(record.id());
	}

	/** The followed region's index text hash, or null. */
	public static @Nullable String followedIndexHash() {
		RegionRecord record = followed();
		return record == null ? null : DOCS.indexHash(record.id());
	}

	public static @Nullable WorldIndex indexOf(@Nullable String region) {
		return DOCS.indexOf(region);
	}

	/** The SHA-256 of a region's index text, as its parts said: what its shape map must name. */
	public static @Nullable String indexHash(@Nullable String region) {
		return DOCS.indexHash(region);
	}

	/** A region's shape documents once both are in: (the shape index's text, the shape map's wire form), else null. */
	public static @Nullable ShapeDocs shapesOf(@Nullable String region) {
		String index = DOCS.shapeIndexText(region);
		String map = DOCS.shapeMapWire(region);
		return index == null || map == null ? null : new ShapeDocs(index, map);
	}

	/** A region's shape index text and shape map wire form. */
	public record ShapeDocs(String indexText, String mapWire) {
	}

	/** The regions whose index has arrived, in table order. */
	public static List<String> indexed() {
		return DOCS.indexed();
	}

	/**
	 * The {@code region} the client tags its relayed messages with (MG3 §4.4): the followed region's id in a region
	 * world; null in a legacy world (today's bytes) and while no region is followed.
	 */
	public static @Nullable String tagFor() {
		if (!DOCS.regionWorld()) {
			return null;
		}
		RegionRecord record = followed();
		return record == null ? null : record.id();
	}

	public static void clear() {
		DOCS.clear();
	}

	// ---- parts ----

	static void onRegionsPart(Envelope message) {
		JsonObject data = message.data();
		long index = JsonFields.integer(data, "index", -1);
		long count = JsonFields.integer(data, "count", -1);
		String hash = JsonFields.str(data, "hash");
		String part = JsonFields.str(data, "part");
		if (index < 0 || count < 1 || hash == null || part == null) {
			KantoBridge.LOGGER.warn("world.regions: dropped a malformed part");
			return;
		}
		try {
			if (DOCS.onRegionsPart((int) index, (int) count, hash, part) == null) {
				return;
			}
			Regions regions = DOCS.regions();
			KantoBridge.LOGGER.info("world.regions: received ({} parts, hash {}, {} regions: {})", count, Hashes.shortHash(hash),
					regions == null ? 0 : regions.size(), regions == null ? "-" : String.join(",", regions.ids()));
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.regions: rejected ({})", e.problem());
		}
	}

	static void onIndexPart(Envelope message) {
		JsonObject data = message.data();
		long index = JsonFields.integer(data, "index", -1);
		long count = JsonFields.integer(data, "count", -1);
		String hash = JsonFields.str(data, "hash");
		String part = JsonFields.str(data, "part");
		String region = JsonFields.str(data, "region");
		String enc = JsonFields.str(data, "enc");
		if (index < 0 || count < 1 || hash == null || part == null || (enc != null && !dev.kantobridge.world.ShapeDocs.ENC_DEFLATE.equals(enc))) {
			KantoBridge.LOGGER.warn("world.index: dropped a malformed part");
			return;
		}
		try {
			WorldIndex parsed = DOCS.onIndexPart(region, enc != null, (int) index, (int) count, hash, part);
			if (parsed == null) {
				return;
			}
			KantoBridge.LOGGER.info("world.index: received ({} parts{}, hash {}, region {}, {} maps, {} story cells)", count,
					enc != null ? " deflated" : "", Hashes.shortHash(DOCS.indexHash(regionKey(region))), region == null ? "legacy" : region,
					parsed.maps().size(), parsed.storyCells().size());
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.index: rejected ({})", e.problem());
		}
	}

	/**
	 * A region's {@code world.index.offer} (G2G): an index it lacks comes from the cache when the cache has it (read off
	 * the client thread), else from the server ({@code world.index.get}).
	 */
	static void onIndexOffer(Envelope message) {
		JsonObject data = message.data();
		String region = JsonFields.str(data, "region");
		String hash = JsonFields.str(data, "hash");
		long chars = JsonFields.integer(data, "chars", -1);
		if (hash == null || !CONTENT_HASH.matcher(hash).matches()) {
			KantoBridge.LOGGER.warn("world.index.offer: dropped a malformed offer");
			return;
		}
		String wanted;
		try {
			wanted = DOCS.onIndexOffer(region, hash);
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.index.offer: rejected ({})", e.problem());
			return;
		}
		String label = region == null ? "legacy" : region;
		if (wanted == null) {
			KantoBridge.LOGGER.info("world.index.offer: region {} holds it (hash {})", label, Hashes.shortHash(hash));
			return;
		}
		ShapeDocCache docs = cache();
		Minecraft minecraft = Minecraft.getInstance();
		Util.backgroundExecutor().execute(() -> {
			ShapeDocCache.Loaded loaded = docs.read(hash, ShapeDocCache.WORLD_INDEX, RegionDocuments.MAX_DOC_BYTES);
			minecraft.execute(() -> indexFromCache(region, label, hash, chars, loaded));
		});
	}

	/** Back on the client thread: the cached index goes in, else it is asked for (while the offer still names it). */
	private static void indexFromCache(@Nullable String region, String label, String hash, long chars, ShapeDocCache.@Nullable Loaded loaded) {
		if (!hash.equals(DOCS.offeredIndex(region))) {
			return;
		}
		if (loaded != null) {
			try {
				WorldIndex parsed = DOCS.provideIndex(region, hash, dev.kantobridge.world.ShapeDocs.text(loaded.content()));
				if (parsed != null) {
					KantoBridge.LOGGER.info("world.index.offer: region {}: from the cache (hash {}, {} maps)", label, Hashes.shortHash(hash),
							parsed.maps().size());
					return;
				}
			} catch (DataException e) {
				KantoBridge.LOGGER.warn("world.index.offer: region {}: the cached index is unusable ({}); asking", label, e.problem());
			}
		}
		JsonObject get = new JsonObject();
		get.addProperty("hash", hash);
		if (region != null) {
			get.addProperty("region", region);
		}
		KantoBridge.LOGGER.info("world.index.offer: region {}: asked for it (hash {}, {} chars)", label, Hashes.shortHash(hash), chars);
		if (ClientRelay.get().post(MsgTypes.WORLD_INDEX_GET, get, null, null) < 0) {
			KantoBridge.LOGGER.warn("world.index.get: could not be sent (region {})", label);
		}
	}

	static void onShapesPart(Envelope message) {
		JsonObject data = message.data();
		String doc = JsonFields.str(data, "doc");
		long index = JsonFields.integer(data, "index", -1);
		long count = JsonFields.integer(data, "count", -1);
		String hash = JsonFields.str(data, "hash");
		String part = JsonFields.str(data, "part");
		String region = JsonFields.str(data, "region");
		String enc = JsonFields.str(data, "enc");
		boolean isIndex = "index".equals(doc);
		if ((!isIndex && !"map".equals(doc)) || index < 0 || count < 1 || hash == null || part == null
				|| (enc != null && !dev.kantobridge.world.ShapeDocs.ENC_DEFLATE.equals(enc))) {
			KantoBridge.LOGGER.warn("world.shapes: dropped a malformed part");
			return;
		}
		try {
			boolean complete = DOCS.onShapesPart(region, isIndex, enc != null, (int) index, (int) count, hash, part);
			String key = regionKey(region);
			if (index == count - 1 && (isIndex ? DOCS.shapeIndexText(key) : DOCS.shapeMapWire(key)) != null) {
				KantoBridge.LOGGER.info("world.shapes: {} received ({} parts, hash {}, region {}{})", doc, count, Hashes.shortHash(hash),
						region == null ? "legacy" : region, complete ? ", both in" : "");
			}
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.shapes: {} rejected ({})", doc, e.problem());
		}
	}

	/**
	 * A region's {@code world.shapes.offer}: what it lacks comes from the cache when the cache has it (read off the client
	 * thread), else from the server ({@code world.shapes.get}).
	 */
	static void onShapesOffer(Envelope message) {
		JsonObject data = message.data();
		String region = JsonFields.str(data, "region");
		String indexHash = offeredHash(data.get("index"));
		String mapHash = offeredHash(data.get("map"));
		if (indexHash == null || mapHash == null) {
			KantoBridge.LOGGER.warn("world.shapes.offer: dropped a malformed offer");
			return;
		}
		RegionDocuments.Wants wants;
		try {
			wants = DOCS.onShapesOffer(region, indexHash, mapHash);
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.shapes.offer: rejected ({})", e.problem());
			return;
		}
		String label = region == null ? "legacy" : region;
		if (!wants.any()) {
			KantoBridge.LOGGER.info("world.shapes.offer: region {} holds both (index {}, map {})", label, Hashes.shortHash(indexHash),
					Hashes.shortHash(mapHash));
			return;
		}
		ShapeDocCache docs = cache();
		Minecraft minecraft = Minecraft.getInstance();
		Util.backgroundExecutor().execute(() -> {
			ShapeDocCache.Loaded index = wants.index() == null ? null : docs.read(wants.index(), true, RegionDocuments.MAX_DOC_BYTES);
			ShapeDocCache.Loaded map = wants.map() == null ? null : docs.read(wants.map(), false, RegionDocuments.MAX_DOC_BYTES);
			minecraft.execute(() -> fromCache(region, label, wants, index, map));
		});
	}

	/** Back on the client thread: the cached documents go in, the rest is asked for (only what the offer still names). */
	private static void fromCache(@Nullable String region, String label, RegionDocuments.Wants wants, ShapeDocCache.@Nullable Loaded index,
			ShapeDocCache.@Nullable Loaded map) {
		if (index != null) {
			DOCS.provideShape(region, true, wants.index(), new String(index.content(), StandardCharsets.UTF_8));
		}
		if (map != null) {
			DOCS.provideShape(region, false, wants.map(), Base64.getEncoder().encodeToString(map.deflated()));
		}
		String askIndex = index == null && wants.index() != null && wants.index().equals(DOCS.offered(region, true)) ? wants.index() : null;
		String askMap = map == null && wants.map() != null && wants.map().equals(DOCS.offered(region, false)) ? wants.map() : null;
		KantoBridge.LOGGER.info("world.shapes.offer: region {}: from the cache {}, asked for {}", label,
				(index != null ? 1 : 0) + (map != null ? 1 : 0), (askIndex != null ? 1 : 0) + (askMap != null ? 1 : 0));
		if (askIndex == null && askMap == null) {
			return;
		}
		JsonObject get = new JsonObject();
		if (askIndex != null) {
			get.addProperty("index", askIndex);
		}
		if (askMap != null) {
			get.addProperty("map", askMap);
		}
		if (region != null) {
			get.addProperty("region", region);
		}
		if (ClientRelay.get().post(MsgTypes.WORLD_SHAPES_GET, get, null, null) < 0) {
			KantoBridge.LOGGER.warn("world.shapes.get: could not be sent (region {})", label);
		}
	}

	private static @Nullable String offeredHash(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		String hash = JsonFields.str(element.getAsJsonObject(), "hash");
		return hash != null && CONTENT_HASH.matcher(hash).matches() ? hash : null;
	}

	/** A document that came in for an offer goes to the cache (written off the client thread). */
	private static void keep(String region, boolean isIndex, String hash, String wire, boolean deflated) {
		ShapeDocCache docs = cache();
		Util.ioPool().execute(() -> {
			try {
				byte[] bytes = deflated ? Base64.getDecoder().decode(wire) : dev.kantobridge.world.ShapeDocs.deflate(wire.getBytes(StandardCharsets.UTF_8));
				docs.write(hash, isIndex, bytes);
			} catch (IOException | IllegalArgumentException e) {
				KantoBridge.LOGGER.warn("world.shapes: could not keep a document ({})", e.getClass().getSimpleName());
			}
		});
	}

	/** An offered index that came in from parts goes to the cache (written off the client thread). */
	private static void keepIndex(String region, String hash, String wire) {
		ShapeDocCache docs = cache();
		Util.ioPool().execute(() -> {
			try {
				docs.write(hash, ShapeDocCache.WORLD_INDEX, Base64.getDecoder().decode(wire));
			} catch (IOException | IllegalArgumentException e) {
				KantoBridge.LOGGER.warn("world.index: could not keep it ({})", e.getClass().getSimpleName());
			}
		});
	}

	/** The documents this client keeps by hash: shapes, and since G2G indices and slot maps. */
	static synchronized ShapeDocCache cache() {
		if (cache == null) {
			cache = new ShapeDocCache(FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("shapes").resolve("docs"),
					ShapeDocCache.MAX_TOTAL_BYTES);
		}
		return cache;
	}

	/** The id an untagged part files under once the legacy region is known. */
	private static @Nullable String regionKey(@Nullable String region) {
		if (region != null) {
			return region;
		}
		Regions regions = DOCS.regions();
		RegionRecord legacy = regions == null ? null : regions.legacy();
		return legacy == null ? null : legacy.id();
	}

	/** The F7 line (ids and counts only). */
	public static String debugLine() {
		Regions regions = DOCS.regions();
		RegionRecord current = followed();
		return "Regions: " + (regions == null ? 0 : regions.size()) + (DOCS.regionWorld() ? " (table)" : " (legacy)") + "  followed "
				+ (current == null ? "-" : current.id()) + "  indexed " + DOCS.indexed().size() + "  epoch " + DOCS.epoch();
	}
}
