package dev.kantobridge.client.world;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of the server's world documents (docs/PLAN.md §2.2): the WorldIndex of the followed region
 * ({@link #current()}), the world's slot map from {@code world.slots} parts (M9: one map per world, shared by every
 * region since MG3; a joining player builds their own art into these slots, PLAN §4.3 "Multiplayer clients"), and the
 * regions' indices and shape documents, which {@link ClientRegions} keeps per region (docs/specs/MULTI_GAME_PLAN.md
 * §4.6). Since MG3 this class is the shim the older call sites read: {@code current()} is the followed region's index,
 * {@code currentHash()} its text hash; a legacy world (no {@code world.regions}) has one implicit region and behaves as
 * before. Client thread only; cleared on disconnect. Logs carry part counts and short hashes, never the contents.
 */
public final class ClientWorldIndex {
	private static final WorldIndexParts.Assembler SLOT_ASSEMBLER = new WorldIndexParts.Assembler();
	private static @Nullable SlotMap slots;
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
		ClientRelay.get().register(MsgTypes.WORLD_SHAPES, ClientRegions::onShapesPart);
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
		if (index < 0 || count < 1 || hash == null || part == null) {
			KantoBridge.LOGGER.warn("world.slots: dropped a malformed part");
			return;
		}
		try {
			SlotMap received = assembleSlots(SLOT_ASSEMBLER, (int) index, (int) count, hash, part);
			if (received == null) {
				return;
			}
			slots = received;
			KantoBridge.LOGGER.info("world.slots: received ({} parts, hash {}, version {}, {} slots, {} regions)", count, Hashes.shortHash(hash),
					received.version(), received.size(), received.regions().size());
			changed();
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.slots: rejected ({})", e.problem());
		}
	}

	/**
	 * One {@code world.slots} part into {@code assembler}; the slot map once the last part is in (null before). The text's
	 * SHA-256 must be the parts' {@code hash}, and so must the parsed map's own hash (the whole document's; a region's
	 * index names its slice's).
	 */
	public static @Nullable SlotMap assembleSlots(WorldIndexParts.Assembler assembler, int index, int count, String hash, String part)
			throws DataException {
		String json = assembler.accept(index, count, hash, part);
		if (json == null) {
			return null;
		}
		SlotMap map = SlotMap.fromJson(json);
		if (!map.hash().equals(hash)) {
			throw new DataException("/hash", "slot map hash mismatch");
		}
		return map;
	}
}
