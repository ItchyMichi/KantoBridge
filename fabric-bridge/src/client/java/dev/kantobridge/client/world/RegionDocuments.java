package dev.kantobridge.client.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Regions;
import dev.kantobridge.world.ShapeDocs;
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
 *
 * <p>Shapes on demand (the follow-up's item 4): a region's {@code world.shapes.offer} names its two documents by the
 * SHA-256 of what they hold; {@link #onShapesOffer} drops held documents the offer replaced and says which the region
 * lacks ({@link Wants}). The glue takes those from its cache ({@link #provideShape}) or asks the server
 * ({@code world.shapes.get}); the parts that come back are checked against the offer (a document of an older offer is
 * refused) and each completed one goes to the {@link DocumentListener} (the cache). Parts no offer announced (a server
 * from before item 4 pushes them on join) are taken as before, without the content check.
 *
 * <p>The index on demand too (MG9 G2G): a region's {@code world.index.offer} names its index by the SHA-256 of its text;
 * {@link #onIndexOffer} says whether the region lacks it, the glue takes it from its cache ({@link #provideIndex}) or
 * asks ({@code world.index.get}), and the deflated parts that answer must hold what the offer named; each one completed
 * goes to the {@link IndexListener} (the cache). A held index stays until the one offered in its place is in. A region's
 * shape pair is told only once its index is in (it may come later than its shapes now); the set's listeners see both
 * then.
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

	/**
	 * Told when one offered shape document has come in from parts: its content hash and the parts' text, which is
	 * Base64 of the deflated document when {@code deflated} (the map always; the index from servers since item 4).
	 */
	@FunctionalInterface
	public interface DocumentListener {
		void document(String region, boolean isIndex, String hash, String wire, boolean deflated);
	}

	/** Told when an offered index has come in from parts: its content hash and the parts' text (Base64 of it deflated). */
	@FunctionalInterface
	public interface IndexListener {
		void index(String region, String hash, String wire);
	}

	/** What a region lacks after its offer: each document's content hash, or null where the region holds it already. */
	public record Wants(@Nullable String index, @Nullable String map) {
		public boolean any() {
			return this.index != null || this.map != null;
		}
	}

	/**
	 * The largest shape document accepted, inflated. Hoenn's index with the landscape models is about 79 MB (2026-10-23;
	 * 64 MB refused it, and the region drew cubes), its map 25.
	 */
	public static final int MAX_DOC_BYTES = 256 * 1024 * 1024;

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
		/** The content hashes of the held documents (the map's only when it was offered), and of the last offer. */
		@Nullable String shapeIndexHash;
		@Nullable String shapeMapHash;
		@Nullable String offeredIndex;
		@Nullable String offeredMap;
		/** The content hash the region's last {@code world.index.offer} named (G2G), or null (pushed parts, unannounced). */
		@Nullable String offeredWorld;
	}

	private final WorldIndexParts.Assembler tableParts = new WorldIndexParts.Assembler();
	private final Map<String, Held> held = new LinkedHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private @Nullable ShapesListener shapesListener;
	private @Nullable DocumentListener documentListener;
	private @Nullable IndexListener indexListener;
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
	public void onDocument(@Nullable DocumentListener listener) {
		this.documentListener = listener;
	}

	public void onShapes(@Nullable ShapesListener listener) {
		this.shapesListener = listener;
	}

	/** Sets the one listener for offered indices that came in from parts. */
	public void onIndexDocument(@Nullable IndexListener listener) {
		this.indexListener = listener;
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
		return this.onIndexPart(region, false, index, count, hash, part);
	}

	/**
	 * {@link #onIndexPart(String, int, int, String, String)}; {@code deflated}: the parts' text is Base64 of the deflated
	 * index ({@code enc:"deflate"}, G2G) and {@code hash} that text's. A completed index the region's offer doesn't name
	 * (an older offer's, after a rebuild) is refused.
	 */
	public @Nullable WorldIndex onIndexPart(@Nullable String region, boolean deflated, int index, int count, String hash, String part)
			throws DataException {
		String key = this.key(region);
		Held slot = this.held.computeIfAbsent(key, k -> new Held());
		String text = slot.indexParts.accept(index, count, hash, part);
		if (text == null) {
			return null;
		}
		String json = deflated ? ShapeDocs.unwireText(text, MAX_DOC_BYTES) : text;
		String contentHash = deflated ? Hashes.sha256(json) : hash;
		if (slot.offeredWorld != null && !slot.offeredWorld.equals(contentHash)) {
			throw new DataException("/", "not the offered document");
		}
		WorldIndex parsed = WorldIndex.fromJson(json);
		String id = this.adoptIndex(key, slot, parsed, contentHash);
		if (slot.offeredWorld != null && deflated && this.indexListener != null) {
			this.indexListener.index(id, contentHash, text);
		}
		return parsed;
	}

	/**
	 * A region's {@code world.index.offer} (null: untagged): the offered hash is noted, and returned when the region lacks
	 * that index (else null). A held index stays until the one offered in its place is in.
	 */
	public @Nullable String onIndexOffer(@Nullable String region, String hash) throws DataException {
		String key = this.key(region);
		Held slot = this.held.computeIfAbsent(key, k -> new Held());
		slot.offeredWorld = hash;
		if (slot.index != null && hash.equals(slot.indexHash)) {
			return null;
		}
		slot.indexParts.reset();
		return hash;
	}

	/**
	 * An offered index the glue found in its own cache (its text, checked against {@code hash}). Ignored (null) unless the
	 * region's offer still names that hash; otherwise the index, adopted as if its parts had come in.
	 */
	public @Nullable WorldIndex provideIndex(@Nullable String region, String hash, String json) throws DataException {
		String key = this.key(region);
		Held slot = this.held.get(key);
		if (slot == null || !hash.equals(slot.offeredWorld)) {
			return null;
		}
		WorldIndex parsed = WorldIndex.fromJson(json);
		slot.indexParts.reset();
		this.adoptIndex(key, slot, parsed, hash);
		return parsed;
	}

	/** Makes {@code parsed} the region's index and publishes; returns the id it is filed under (a legacy world's own). */
	private String adoptIndex(String key, Held slot, WorldIndex parsed, String contentHash) {
		slot.index = parsed;
		slot.indexHash = contentHash;
		if (!this.fromTable) {
			// A legacy world: its one index names the implicit region (again after a rebuild: the bounds may have moved).
			RegionRecord record = RegionRecord.legacy(parsed, null, null, null);
			this.held.remove(key);
			this.held.put(record.id(), slot);
			this.adopt(Regions.legacy(record), false);
			return record.id();
		}
		this.publish();
		return key;
	}

	/** The content hash the region's last {@code world.index.offer} named, or null. */
	public @Nullable String offeredIndex(@Nullable String region) {
		Held slot;
		try {
			slot = this.held.get(this.key(region));
		} catch (DataException e) {
			return null;
		}
		return slot == null ? null : slot.offeredWorld;
	}

	/**
	 * A region's {@code world.shapes.offer} (null: untagged): held documents it replaced are dropped, and the documents the
	 * region lacks are returned by content hash.
	 */
	public Wants onShapesOffer(@Nullable String region, String indexHash, String mapHash) throws DataException {
		String key = this.key(region);
		Held slot = this.held.computeIfAbsent(key, k -> new Held());
		slot.offeredIndex = indexHash;
		slot.offeredMap = mapHash;
		if (!indexHash.equals(slot.shapeIndexHash)) {
			slot.shapeIndexText = null;
			slot.shapeIndexHash = null;
			slot.shapeIndexParts.reset();
		}
		if (!mapHash.equals(slot.shapeMapHash)) {
			slot.shapeMapWire = null;
			slot.shapeMapHash = null;
			slot.shapeMapParts.reset();
		}
		return new Wants(slot.shapeIndexText == null ? indexHash : null, slot.shapeMapWire == null ? mapHash : null);
	}

	/** The content hash the region's last offer gave a document (the index, else the map), or null. */
	public @Nullable String offered(@Nullable String region, boolean isIndex) {
		Held slot;
		try {
			slot = this.held.get(this.key(region));
		} catch (DataException e) {
			return null;
		}
		return slot == null ? null : isIndex ? slot.offeredIndex : slot.offeredMap;
	}

	/**
	 * An offered document the glue found in its own cache (its content checked against {@code hash}): the index's text or
	 * the map's wire form. Ignored unless the region's offer still names that hash. Returns true when the region's pair
	 * is complete (the {@link ShapesListener} has been told).
	 */
	public boolean provideShape(@Nullable String region, boolean isIndex, String hash, String content) {
		String key;
		try {
			key = this.key(region);
		} catch (DataException e) {
			return false;
		}
		Held slot = this.held.get(key);
		if (slot == null || !hash.equals(isIndex ? slot.offeredIndex : slot.offeredMap)) {
			return false;
		}
		if (isIndex) {
			slot.shapeIndexText = content;
			slot.shapeIndexHash = hash;
			slot.shapeIndexParts.reset();
		} else {
			slot.shapeMapWire = content;
			slot.shapeMapHash = hash;
			slot.shapeMapParts.reset();
		}
		return this.tell(key, slot);
	}

	/** {@link #onShapesPart(String, boolean, boolean, int, int, String, String)} for a part that isn't deflated text. */
	public boolean onShapesPart(@Nullable String region, boolean isIndex, int index, int count, String hash, String part) throws DataException {
		return this.onShapesPart(region, isIndex, false, index, count, hash, part);
	}

	/**
	 * One {@code world.shapes} part for {@code region} (null: untagged); {@code deflated}: an index part is Base64 of the
	 * deflated text ({@code enc:"deflate"}). Returns true when the region's shape index and shape map are both in (the
	 * {@link ShapesListener} has been told), else false. A completed document the region's offer doesn't name (an older
	 * offer's, after a rebuild) is refused.
	 */
	public boolean onShapesPart(@Nullable String region, boolean isIndex, boolean deflated, int index, int count, String hash, String part)
			throws DataException {
		String key = this.key(region);
		Held slot = this.held.computeIfAbsent(key, k -> new Held());
		String text = (isIndex ? slot.shapeIndexParts : slot.shapeMapParts).accept(index, count, hash, part);
		if (text == null) {
			return false;
		}
		String offered = isIndex ? slot.offeredIndex : slot.offeredMap;
		String content = isIndex && deflated ? ShapeDocs.unwireText(text, MAX_DOC_BYTES) : text;
		String contentHash = isIndex ? Hashes.sha256(content) : offered == null ? null : Hashes.sha256(ShapeDocs.unwire(text, MAX_DOC_BYTES));
		if (offered != null && !offered.equals(contentHash)) {
			throw new DataException("/", "not the offered document");
		}
		if (isIndex) {
			slot.shapeIndexText = content;
			slot.shapeIndexHash = contentHash;
			if (offered == null) {
				// pushed (a server from before item 4): a new shape index makes a map received for the old one stale
				slot.shapeMapWire = null;
				slot.shapeMapHash = null;
			}
		} else {
			slot.shapeMapWire = text;
			slot.shapeMapHash = contentHash;
		}
		if (offered != null && contentHash != null && this.documentListener != null && !UNNAMED.equals(key)) {
			this.documentListener.document(key, isIndex, contentHash, text, !isIndex || deflated);
		}
		return this.tell(key, slot);
	}

	/**
	 * Tells the listener a region's complete pair; true when it is complete. Not before the region's index is in (G2G: the
	 * shapes may come from the cache while the index comes from the server): the set's listeners see both once it is.
	 */
	private boolean tell(String key, Held slot) {
		if (slot.shapeIndexText == null || slot.shapeMapWire == null || slot.index == null) {
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
