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
 * The client's copy of the server's WorldIndex (docs/PLAN.md §2.2), assembled from {@code world.index} parts and
 * checked against their hash, and of the world's slot map, from {@code world.slots} parts (M9: a joining player builds
 * their own art into these slots, PLAN §4.3 "Multiplayer clients"). Client thread only; cleared on disconnect. Logs carry
 * part counts and short hashes, never the contents.
 */
public final class ClientWorldIndex {
	private static final WorldIndexParts.Assembler ASSEMBLER = new WorldIndexParts.Assembler();
	private static final WorldIndexParts.Assembler SLOT_ASSEMBLER = new WorldIndexParts.Assembler();
	private static @Nullable WorldIndex current;
	private static @Nullable SlotMap slots;
	/** Told whenever the index or the slot map arrives or goes (the render snapshot, ...). */
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

	private ClientWorldIndex() {
	}

	/** Call once from client init (after the relay). */
	public static void init() {
		ClientRelay.get().register(MsgTypes.WORLD_INDEX, ClientWorldIndex::onPart);
		ClientRelay.get().register(MsgTypes.WORLD_SLOTS, ClientWorldIndex::onSlotsPart);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> clear());
	}

	/** Runs {@code listener} on the client thread whenever the index or the slot map arrives or goes. */
	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	private static void changed() {
		for (Runnable listener : LISTENERS) {
			listener.run();
		}
	}

	/** The index of the world this client is in, or null before it has arrived. */
	public static @Nullable WorldIndex current() {
		return current;
	}

	/** The world's slot map (its hash checked against the parts' hash), or null before it has arrived or without one. */
	public static @Nullable SlotMap slotMap() {
		return slots;
	}

	public static void clear() {
		current = null;
		slots = null;
		ASSEMBLER.reset();
		SLOT_ASSEMBLER.reset();
		changed();
	}

	static void onPart(Envelope message) {
		JsonObject data = message.data();
		long index = JsonFields.integer(data, "index", -1);
		long count = JsonFields.integer(data, "count", -1);
		String hash = JsonFields.str(data, "hash");
		String part = JsonFields.str(data, "part");
		if (index < 0 || count < 1 || hash == null || part == null) {
			KantoBridge.LOGGER.warn("world.index: dropped a malformed part");
			return;
		}
		try {
			String json = ASSEMBLER.accept((int) index, (int) count, hash, part);
			if (json == null) {
				return;
			}
			current = WorldIndex.fromJson(json);
			KantoBridge.LOGGER.info("world.index: received ({} parts, hash {}, {} maps, {} story cells)", count, Hashes.shortHash(hash),
					current.maps().size(), current.storyCells().size());
			changed();
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.index: rejected ({})", e.problem());
		}
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
			KantoBridge.LOGGER.info("world.slots: received ({} parts, hash {}, {} slots)", count, Hashes.shortHash(hash), received.size());
			changed();
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.slots: rejected ({})", e.problem());
		}
	}

	/**
	 * One {@code world.slots} part into {@code assembler}; the slot map once the last part is in (null before). The text's
	 * SHA-256 must be the parts' {@code hash}, and so must the parsed map's own hash: the slot map the index names.
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
