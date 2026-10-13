package dev.kantobridge.client.world;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Regions;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
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
 */
public final class ClientRegions {
	private static final RegionDocuments DOCS = new RegionDocuments();

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
		if (index < 0 || count < 1 || hash == null || part == null) {
			KantoBridge.LOGGER.warn("world.index: dropped a malformed part");
			return;
		}
		try {
			WorldIndex parsed = DOCS.onIndexPart(region, (int) index, (int) count, hash, part);
			if (parsed == null) {
				return;
			}
			KantoBridge.LOGGER.info("world.index: received ({} parts, hash {}, region {}, {} maps, {} story cells)", count, Hashes.shortHash(hash),
					region == null ? "legacy" : region, parsed.maps().size(), parsed.storyCells().size());
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.index: rejected ({})", e.problem());
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
		boolean isIndex = "index".equals(doc);
		if ((!isIndex && !"map".equals(doc)) || index < 0 || count < 1 || hash == null || part == null) {
			KantoBridge.LOGGER.warn("world.shapes: dropped a malformed part");
			return;
		}
		try {
			boolean complete = DOCS.onShapesPart(region, isIndex, (int) index, (int) count, hash, part);
			if (DOCS.shapeIndexText(regionKey(region)) != null || DOCS.shapeMapWire(regionKey(region)) != null) {
				KantoBridge.LOGGER.info("world.shapes: {} received ({} parts, hash {}, region {}{})", doc, count, Hashes.shortHash(hash),
						region == null ? "legacy" : region, complete ? ", both in" : "");
			}
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.shapes: {} rejected ({})", doc, e.problem());
		}
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
