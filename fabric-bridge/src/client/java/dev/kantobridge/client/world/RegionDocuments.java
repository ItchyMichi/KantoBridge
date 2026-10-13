package dev.kantobridge.client.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Regions;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of a world's regions and their documents (docs/specs/MULTI_GAME_PLAN.md §4.6, §4.14, MG3), as the
 * relay delivers them on join: the {@code world.regions} parts (the region table and every record), then each region's
 * {@code world.index} parts tagged with its {@code region}, then each region's {@code world.shapes} parts. A legacy
 * world sends no table and no tags: its one index makes the implicit record ({@link RegionRecord#legacy}) and the
 * client behaves exactly as before MG3.
 *
 * <p>A {@link RegionSet} is published (its epoch grows) when the table arrives, when a region's index is complete, on a
 * new table (a rebuild) and when everything is cleared; the shape pair of a region goes to the {@link ShapesListener}
 * once both documents are in. Plain Java, one thread (the client's); {@link ClientRegions} is the Minecraft glue.
 */
public final class RegionDocuments {
	/** Where untagged parts collect before the legacy world's index named its region. */
	static final String UNNAMED = "";
	/** {@code world.regions}' document version. */
	public static final int VERSION = 1;

	/** The regions as the client knows them now; {@code epoch} grows with every change. */
	public record RegionSet(Regions regions, long epoch) {
	}

	/** Told when a region's shape index text and shape map wire form are both in. */
	@FunctionalInterface
	public interface ShapesListener {
		void shapes(String region, String indexText, String mapWire);
	}

	/** One region's documents, as far as they have arrived. */
	private static final class Held {
		final WorldIndexParts.Assembler indexParts = new WorldIndexParts.Assembler();
		final WorldIndexParts.Assembler shapeIndexParts = new WorldIndexParts.Assembler();
		final WorldIndexParts.Assembler shapeMapParts = new WorldIndexParts.Assembler();
		@Nullable WorldIndex index;
		/** The parts' hash of the index text: what the region's shape map must name. */
		@Nullable String indexHash;
		@Nullable String shapeIndexText;
		@Nullable String shapeMapWire;
	}

	private final WorldIndexParts.Assembler tableParts = new WorldIndexParts.Assembler();
	private final Map<String, Held> held = new LinkedHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private @Nullable ShapesListener shapesListener;
	private @Nullable Regions regions;
	/** True once a {@code world.regions} document named the regions (a region world); false for a legacy world. */
	private boolean fromTable;
	private @Nullable String worldId;
	private long epoch;
	private @Nullable String followedId;

	/** Runs {@code listener} whenever the set changes (a table, an index, a clear). */
	public void addListener(Runnable listener) {
		this.listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/** Sets the one listener for complete shape pairs. */
	public void onShapes(@Nullable ShapesListener listener) {
		this.shapesListener = listener;
	}

	// ---- feeding ----

	/**
	 * One {@code world.regions} part. Returns the document's hash once the last part is in (the set is then published),
	 * else null.
	 */
	public @Nullable String onRegionsPart(int index, int count, String hash, String part) throws DataException {
		String json = this.tableParts.accept(index, count, hash, part);
		if (json == null) {
			return null;
		}
		Regions parsed = parseDocument(json);
		this.worldId = worldOf(json);
		this.adopt(parsed, true);
		return hash;
	}

	/**
	 * One {@code world.index} part for {@code region} (null for an untagged part: the legacy world's, or the legacy
	 * region's). Returns the index once the last part is in (the set is then published), else null. A tagged part for
	 * a region the table doesn't name is refused.
	 */
	public @Nullable WorldIndex onIndexPart(@Nullable String region, int index, int count, String hash, String part) throws DataException {
		String key = this.key(region);
		Held slot = this.held.computeIfAbsent(key, k -> new Held());
		String json = slot.indexParts.accept(index, count, hash, part);
		if (json == null) {
			return null;
		}
		WorldIndex parsed = WorldIndex.fromJson(json);
		slot.index = parsed;
		slot.indexHash = hash;
		if (!this.fromTable) {
			// A legacy world: its one index names the implicit region (again after a rebuild: the bounds may have moved).
			RegionRecord record = RegionRecord.legacy(parsed, null, null, null);
			this.held.remove(key);
			this.held.put(record.id(), slot);
			this.adopt(Regions.legacy(record), false);
		} else {
			this.publish();
		}
		return parsed;
	}

	/**
	 * One {@code world.shapes} part for {@code region} (null: untagged). Returns true when the region's shape index and
	 * shape map are both in (the {@link ShapesListener} has been told), else false.
	 */
	public boolean onShapesPart(@Nullable String region, boolean isIndex, int index, int count, String hash, String part) throws DataException {
		String key = this.key(region);
		Held slot = this.held.computeIfAbsent(key, k -> new Held());
		String text = (isIndex ? slot.shapeIndexParts : slot.shapeMapParts).accept(index, count, hash, part);
		if (text == null) {
			return false;
		}
		if (isIndex) {
			slot.shapeIndexText = text;
			// A new shape index makes any map received for the old one stale.
			slot.shapeMapWire = null;
		} else {
			slot.shapeMapWire = text;
		}
		if (slot.shapeIndexText == null || slot.shapeMapWire == null) {
			return false;
		}
		if (this.shapesListener != null && !UNNAMED.equals(key)) {
			this.shapesListener.shapes(key, slot.shapeIndexText, slot.shapeMapWire);
		}
		return true;
	}

	/** Everything goes (a disconnect); the set is published empty. */
	public void clear() {
		this.tableParts.reset();
		this.held.clear();
		this.regions = null;
		this.fromTable = false;
		this.worldId = null;
		this.followedId = null;
		this.publish();
	}

	// ---- views ----

	/** The regions and the epoch they were published at, or null before any region is known. */
	public @Nullable RegionSet set() {
		return this.regions == null ? null : new RegionSet(this.regions, this.epoch);
	}

	/** Grows with every published change (also a clear). */
	public long epoch() {
		return this.epoch;
	}

	public @Nullable Regions regions() {
		return this.regions;
	}

	/** True once a {@code world.regions} document arrived: the world has a region table. */
	public boolean regionWorld() {
		return this.fromTable;
	}

	/** True for a world known only through an untagged index: one implicit region (§4.14). */
	public boolean legacyWorld() {
		return this.regions != null && !this.fromTable;
	}

	/** The world id the table named, or null. */
	public @Nullable String worldId() {
		return this.worldId;
	}

	public @Nullable RegionRecord byId(@Nullable String id) {
		return this.regions == null || id == null ? null : this.regions.byId(id);
	}

	/** The region built from {@code game}'s export, or null. */
	public @Nullable RegionRecord byGame(@Nullable String game) {
		if (this.regions == null || game == null) {
			return null;
		}
		for (RegionRecord record : this.regions.records().values()) {
			if (record.game().equals(game)) {
				return record;
			}
		}
		return null;
	}

	/** The region block column (x, z) lies in (its bounds, else its areas), or null. */
	public @Nullable RegionRecord at(int x, int z) {
		return this.regions == null ? null : this.regions.at(x, z);
	}

	/**
	 * The followed region: the one {@link #setFollowed} named when it is in the set, else the only region, else null
	 * (several regions and nothing followed yet: the glue falls back to the region under the player's feet).
	 */
	public @Nullable RegionRecord followed() {
		if (this.regions == null) {
			return null;
		}
		if (this.followedId != null) {
			RegionRecord named = this.regions.byId(this.followedId);
			if (named != null) {
				return named;
			}
		}
		return this.regions.size() == 1 ? this.regions.records().values().iterator().next() : null;
	}

	/** Binds the followed region (MG4: the followed link's); null unbinds. */
	public void setFollowed(@Nullable String id) {
		if (!Objects.equals(this.followedId, id)) {
			this.followedId = id;
			this.publish();
		}
	}

	public @Nullable String followedId() {
		return this.followedId;
	}

	/** A region's index, or null before it arrived. */
	public @Nullable WorldIndex indexOf(@Nullable String region) {
		Held slot = region == null ? null : this.held.get(region);
		return slot == null ? null : slot.index;
	}

	/** The SHA-256 of a region's index text, as its parts said, or null. */
	public @Nullable String indexHash(@Nullable String region) {
		Held slot = region == null ? null : this.held.get(region);
		return slot == null ? null : slot.indexHash;
	}

	/** A region's shape index text, or null. */
	public @Nullable String shapeIndexText(@Nullable String region) {
		Held slot = region == null ? null : this.held.get(region);
		return slot == null ? null : slot.shapeIndexText;
	}

	/** A region's shape map wire form, or null. */
	public @Nullable String shapeMapWire(@Nullable String region) {
		Held slot = region == null ? null : this.held.get(region);
		return slot == null ? null : slot.shapeMapWire;
	}

	/** The regions whose index has arrived, in table order. */
	public List<String> indexed() {
		List<String> out = new ArrayList<>();
		if (this.regions != null) {
			for (String id : this.regions.ids()) {
				Held slot = this.held.get(id);
				if (slot != null && slot.index != null) {
					out.add(id);
				}
			}
		}
		return out;
	}

	// ---- helpers ----

	/** The key a part files under: its tag, else the legacy region once named, else {@link #UNNAMED}. */
	private String key(@Nullable String region) throws DataException {
		if (region != null) {
			if (this.regions != null && this.regions.byId(region) == null) {
				throw new DataException("/region", "unknown region");
			}
			return region;
		}
		RegionRecord legacy = this.regions == null ? null : this.regions.legacy();
		return legacy == null ? UNNAMED : legacy.id();
	}

	/** Makes {@code parsed} the set, keeping the documents of the regions it still names; publishes. */
	private void adopt(Regions parsed, boolean fromTable) {
		Held unnamed = this.held.remove(UNNAMED);
		RegionRecord legacy = parsed.legacy();
		if (unnamed != null && legacy != null && !this.held.containsKey(legacy.id())) {
			// Untagged parts that arrived before the table are the legacy region's.
			this.held.put(legacy.id(), unnamed);
		}
		this.held.keySet().retainAll(parsed.records().keySet());
		this.regions = parsed;
		this.fromTable = fromTable;
		if (this.followedId != null && parsed.byId(this.followedId) == null) {
			this.followedId = null;
		}
		this.publish();
	}

	private void publish() {
		this.epoch++;
		for (Runnable listener : this.listeners) {
			listener.run();
		}
	}

	/** The {@code world.regions} document: {@code {"version":1,"world":"<id>","table":{...},"regions":[...]}}. */
	static Regions parseDocument(String json) throws DataException {
		JsonObject root = root(json);
		JsonElement version = root.get("version");
		if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber() || version.getAsInt() != VERSION) {
			throw new DataException("/version", "unsupported version");
		}
		JsonElement table = root.get("table");
		if (table == null || !table.isJsonObject()) {
			throw new DataException("/table", "expected an object");
		}
		JsonElement records = root.get("regions");
		if (records == null || !records.isJsonArray()) {
			throw new DataException("/regions", "expected an array");
		}
		Map<String, String> texts = new LinkedHashMap<>();
		int i = 0;
		for (JsonElement element : records.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				throw new DataException("/regions/" + i, "expected an object");
			}
			JsonElement id = element.getAsJsonObject().get("id");
			if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
				throw new DataException("/regions/" + i + "/id", "expected a string");
			}
			if (texts.put(id.getAsString(), ProtoJson.write(element)) != null) {
				throw new DataException("/regions/" + i + "/id", "duplicate region");
			}
			i++;
		}
		return Regions.parse(ProtoJson.write(table), texts);
	}

	private static @Nullable String worldOf(String json) throws DataException {
		JsonElement world = root(json).get("world");
		if (world == null) {
			return null;
		}
		if (!world.isJsonPrimitive() || !world.getAsJsonPrimitive().isString()) {
			throw new DataException("/world", "expected a string");
		}
		return world.getAsString();
	}

	private static JsonObject root(String json) throws DataException {
		try {
			return ProtoJson.parseObject(json);
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
	}

	/** The implicit region id of a legacy world for a game: its default region, else the slot map's legacy region. */
	public static String legacyIdOf(String game) {
		String region = Games.region(game);
		return region == null ? RegionRecord.LEGACY_ID : region;
	}
}
