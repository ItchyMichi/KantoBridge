package dev.kantobridge.client.world;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.jetbrains.annotations.Nullable;

/**
 * The client's copy of the server's WorldIndex (docs/PLAN.md §2.2), assembled from {@code world.index} parts and
 * checked against their hash. Movement sync, warps and story views read it in later milestones. Client thread only;
 * cleared on disconnect. Logs carry part counts and short hashes, never the index.
 */
public final class ClientWorldIndex {
	private static final WorldIndexParts.Assembler ASSEMBLER = new WorldIndexParts.Assembler();
	private static @Nullable WorldIndex current;

	private ClientWorldIndex() {
	}

	/** Call once from client init (after the relay). */
	public static void init() {
		ClientRelay.get().register(MsgTypes.WORLD_INDEX, ClientWorldIndex::onPart);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> clear());
	}

	/** The index of the world this client is in, or null before it has arrived. */
	public static @Nullable WorldIndex current() {
		return current;
	}

	public static void clear() {
		current = null;
		ASSEMBLER.reset();
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
		} catch (DataException e) {
			KantoBridge.LOGGER.warn("world.index: rejected ({})", e.problem());
		}
	}
}
