package dev.kantobridge.client.world;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.shape.ShapeDocCache;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeDocs;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of the server's world documents (docs/PLAN.md §2.2): the WorldIndex of the followed region
 * ({@link #current()}), the world's slot map from {@code world.slots} parts (M9: one map per world, shared by every
 * region since MG3; a joining player builds their own art into these slots, PLAN §4.3 "Multiplayer clients"), and the
 * regions' indices and shape documents, which {@link ClientRegions} keeps per region (docs/specs/MULTI_GAME_PLAN.md
 * §4.6). Since MG3 this class is the shim the older call sites read: {@code current()} is the followed region's index,
 * {@code currentHash()} its text hash; a legacy world (no {@code world.regions}) has one implicit region and behaves as
 * before. Client thread only; cleared on disconnect. Logs carry part counts and short hashes, never the contents.
 *
 * <p>Since MG9 G2G the slot map is offered by hash ({@code world.slots.offer}): one held already stays, one in the
 * shape-document cache is taken from there (read off the client thread), else it is asked for ({@code world.slots.get})
 * and the deflated parts that answer must hold what the offer named. Unannounced parts (a server from before G2G) are
 * taken as before.
 */
public final class ClientWorldIndex {
	private static final WorldIndexParts.Assembler SLOT_ASSEMBLER = new WorldIndexParts.Assembler();
	private static @Nullable SlotMap slots;
	/** The hash the last {@code world.slots.offer} named, or null (none yet, or a server from before G2G). */
	private static @Nullable String offeredSlots;
	private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");
	/** Told whenever an index, the regions or the slot map arrive or go (the render snapshot, ...). */
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
	private static boolean registered;

	private ClientWorldIndex() {
	}

	/** Call once from client init (after the relay). */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		ClientRelay.get().register(MsgTypes.WORLD_REGIONS, ClientRegions::onRegionsPart);
		ClientRelay.get().register(MsgTypes.WORLD_INDEX, ClientRegions::onIndexPart);
		ClientRelay.get().register(MsgTypes.WORLD_SLOTS, ClientWorldIndex::onSlotsPart);
		ClientRelay.get().register(MsgTypes.WORLD_INDEX_OFFER, ClientRegions::onIndexOffer);
		ClientRelay.get().register(MsgTypes.WORLD_SLOTS_OFFER, ClientWorldIndex::onSlotsOffer);
		ClientRelay.get().register(MsgTypes.WORLD_SHAPES, ClientRegions::onShapesPart);
		ClientRelay.get().register(MsgTypes.WORLD_SHAPES_OFFER, ClientRegions::onShapesOffer);
		// A region's index arriving or going changes what the shim returns.
		ClientRegions.addListener(ClientWorldIndex::changed);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> clear());
	}

	/** Runs {@code listener} on the client thread whenever an index, the regions or the slot map arrives or goes. */
	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	private static void changed() {
		for (Runnable listener : LISTENERS) {
			listener.run();
		}
	}

	/** The followed region's index (a legacy world's one index), or null before it has arrived. */
	public static @Nullable WorldIndex current() {
		return ClientRegions.followedIndex();
	}

	/** The SHA-256 of the followed region's index text, or null before it has arrived. */
	public static @Nullable String currentHash() {
		return ClientRegions.followedIndexHash();
	}

	/** The world's slot map (its hash checked against the parts' hash), or null before it has arrived or without one. */
	public static @Nullable SlotMap slotMap() {
		return slots;
	}

	public static void clear() {
		slots = null;
		offeredSlots = null;
		SLOT_ASSEMBLER.reset();
		// The regions' clear tells the listeners through ClientRegions.
		ClientRegions.clear();
	}

	static void onSlotsPart(Envelope message) {
		JsonObject data = message.data();
		long index = JsonFields.integer(data, "index", -1);
		long count = JsonFields.integer(data, "count", -1);
		String hash = JsonFields.str(data, "hash");
		String part = JsonFields.str(data, "part");
		String enc = JsonFields.str(data, "enc");
		if (index < 0 || count < 1 || hash == null || part == null || (enc != null && !ShapeDocs.ENC_DEFLATE.equals(enc))) {
			KantoBridge.LOGGER.warn("world.slots: dropped a malformed part");
			return;
		}
		try {
			boolean deflated = enc != null;
			String[] wire = new String[1];
			SlotMap received = assembleSlots(SLOT_ASSEMBLER, (int) index, (int) count, hash, part, deflated, offeredSlots, wire);
			if (received == null) {
				return;
			}
			slots = received;
			KantoBridge.LOGGER.info("world.slots: received ({} parts{}, hash {}, version {}, {} slots, {} regions)", count, deflated ? " deflated" : "",
					Hashes.shortHash(received.hash()), received.version(), received.size(), received.regions().size());
			if (deflated && offeredSlots != null) {
				keep(received.hash(), wire[0]);
			}
			changed();
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.slots: rejected ({})", e.problem());
		}
	}

	/**
	 * {@code world.slots.offer} (G2G): a map held already stays; one the cache holds is taken from there (read off the
	 * client thread), else it is asked for ({@code world.slots.get}).
	 */
	static void onSlotsOffer(Envelope message) {
		String hash = JsonFields.str(message.data(), "hash");
		long chars = JsonFields.integer(message.data(), "chars", -1);
		if (hash == null || !CONTENT_HASH.matcher(hash).matches()) {
			KantoBridge.LOGGER.warn("world.slots.offer: dropped a malformed offer");
			return;
		}
		offeredSlots = hash;
		if (slots != null && slots.hash().equals(hash)) {
			KantoBridge.LOGGER.info("world.slots.offer: held (hash {})", Hashes.shortHash(hash));
			return;
		}
		SLOT_ASSEMBLER.reset();
		ShapeDocCache docs = ClientRegions.cache();
		Minecraft minecraft = Minecraft.getInstance();
		Util.backgroundExecutor().execute(() -> {
			ShapeDocCache.Loaded loaded = docs.read(hash, ShapeDocCache.SLOT_MAP, RegionDocuments.MAX_DOC_BYTES);
			minecraft.execute(() -> slotsFromCache(hash, chars, loaded));
		});
	}

	/** Back on the client thread: the cached map goes in, else it is asked for (while the offer still names it). */
	private static void slotsFromCache(String hash, long chars, ShapeDocCache.@Nullable Loaded loaded) {
		if (!hash.equals(offeredSlots) || slots != null && slots.hash().equals(hash)) {
			return;
		}
		if (loaded != null) {
			try {
				SlotMap map = cachedSlots(hash, loaded.content());
				slots = map;
				KantoBridge.LOGGER.info("world.slots.offer: from the cache (hash {}, version {}, {} slots, {} regions)", Hashes.shortHash(hash),
						map.version(), map.size(), map.regions().size());
				changed();
				return;
			} catch (DataException e) {
				KantoBridge.LOGGER.warn("world.slots.offer: the cached map is unusable ({}); asking", e.problem());
			}
		}
		JsonObject get = new JsonObject();
		get.addProperty("hash", hash);
		KantoBridge.LOGGER.info("world.slots.offer: asked for it (hash {}, {} chars)", Hashes.shortHash(hash), chars);
		if (ClientRelay.get().post(MsgTypes.WORLD_SLOTS_GET, get, null, null) < 0) {
			KantoBridge.LOGGER.warn("world.slots.get: could not be sent");
		}
	}

	/** A slot map read back from the cache: its text must parse and the map's hash must be the offered one. */
	static SlotMap cachedSlots(String hash, byte[] content) throws DataException {
		SlotMap map = SlotMap.fromJson(ShapeDocs.text(content));
		if (!map.hash().equals(hash)) {
			throw new DataException("/hash", "slot map hash mismatch");
		}
		return map;
	}

	/** A slot map that came in for an offer goes to the cache (written off the client thread). */
	private static void keep(String hash, String wire) {
		ShapeDocCache docs = ClientRegions.cache();
		Util.ioPool().execute(() -> {
			try {
				docs.write(hash, ShapeDocCache.SLOT_MAP, Base64.getDecoder().decode(wire));
			} catch (IOException | IllegalArgumentException e) {
				KantoBridge.LOGGER.warn("world.slots: could not keep it ({})", e.getClass().getSimpleName());
			}
		});
	}

	/**
	 * One {@code world.slots} part into {@code assembler}; the slot map once the last part is in (null before). The text's
	 * SHA-256 must be the parts' {@code hash}, and so must the parsed map's own hash (the whole document's; a region's
	 * index names its slice's).
	 */
	public static @Nullable SlotMap assembleSlots(WorldIndexParts.Assembler assembler, int index, int count, String hash, String part)
			throws DataException {
		return assembleSlots(assembler, index, count, hash, part, false, null, null);
	}

	/**
	 * {@link #assembleSlots(WorldIndexParts.Assembler, int, int, String, String)}; {@code deflated}: the parts' text is
	 * Base64 of the deflated map ({@code enc:"deflate"}, G2G) and {@code hash} that text's, so the map's own hash is
	 * checked against the text it inflates to. With {@code offered} (the last offer's hash) a map that isn't the offered
	 * one is refused. {@code wire}, when given, receives the parts' whole text.
	 */
	public static @Nullable SlotMap assembleSlots(WorldIndexParts.Assembler assembler, int index, int count, String hash, String part,
			boolean deflated, @Nullable String offered, String @Nullable [] wire) throws DataException {
		String text = assembler.accept(index, count, hash, part);
		if (text == null) {
			return null;
		}
		String json = deflated ? ShapeDocs.unwireText(text, RegionDocuments.MAX_DOC_BYTES) : text;
		String contentHash = deflated ? Hashes.sha256(json) : hash;
		SlotMap map = SlotMap.fromJson(json);
		if (!map.hash().equals(contentHash)) {
			throw new DataException("/hash", "slot map hash mismatch");
		}
		if (offered != null && !offered.equals(contentHash)) {
			throw new DataException("/", "not the offered document");
		}
		if (wire != null) {
			wire[0] = text;
		}
		return map;
	}
}
