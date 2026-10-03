package dev.kantobridge.server;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
 * "Multiplayer clients"). Only the slot assignments travel; the art never leaves each player's machine.
 *
 * <p>Both are read from {@code <world>/kantobridge/} when the server starts and replaced when a build finishes. The slot
 * map is canonicalised and sent only when its hash is the WorldIndex's {@code slotMapHash} (a stray file would make
 * every client draw the wrong art). Logs carry part counts and short hashes only.
 */
public final class WorldIndexSync {
	/**
	 * One server's current index, already split, and parsed for the server's own lookups (warps); its slot map's parts
	 * (empty when the world has none that matches the index).
	 */
	private record Published(String hash, List<WorldIndexParts.Part> parts, @Nullable WorldIndex index, @Nullable String slotsHash,
			List<WorldIndexParts.Part> slotParts) {
	}

	private static final Map<MinecraftServer, Published> CURRENT = Collections.synchronizedMap(new WeakHashMap<>());

	private WorldIndexSync() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WorldIndexSync::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(CURRENT::remove);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(server, handler.getPlayer()));
	}

	/** Reads the world's index file, and its slot map beside it, if it has them. */
	public static void load(MinecraftServer server) {
		Path data = server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER);
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
		publish(server, index, json, readSlots(data.resolve(BuildTask.SLOT_MAP_FILE)), false);
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

	/** Makes {@code index} the one joining players get (without a slot map); with {@code broadcast}, sends it to everyone. */
	public static void publish(MinecraftServer server, WorldIndex index, boolean broadcast) {
		publish(server, index, index.toJson(), null, broadcast);
	}

	/**
	 * Makes {@code index} and the slot map it was built with ({@code slots}, null for none) the ones joining players get;
	 * with {@code broadcast}, sends both to everyone online now. A slot map whose hash isn't the index's is left out.
	 */
	public static void publish(MinecraftServer server, WorldIndex index, @Nullable SlotMap slots, boolean broadcast) {
		publish(server, index, index.toJson(), slots, broadcast);
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
		publish(server, index, json, null, broadcast);
	}

	private static void publish(MinecraftServer server, @Nullable WorldIndex index, String json, @Nullable SlotMap slots, boolean broadcast) {
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(json, WorldIndexParts.PART_CHARS);
		String slotsHash = null;
		List<WorldIndexParts.Part> slotParts = List.of();
		if (slots != null) {
			if (index != null && slots.hash().equals(index.slotMapHash())) {
				// The canonical text: its SHA-256 is the slot map's hash, which the client checks against the index's.
				slotParts = WorldIndexParts.split(slots.toJson(), WorldIndexParts.PART_CHARS);
				slotsHash = slots.hash();
			} else {
				KantoBridge.LOGGER.warn("world.slots: the slot map ({}) isn't the one the index names; not sent", Hashes.shortHash(slots.hash()));
			}
		}
		Published published = new Published(parts.get(0).hash(), parts, index, slotsHash, slotParts);
		CURRENT.put(server, published);
		KantoBridge.LOGGER.info("world.index: {} parts, hash {}; world.slots: {} parts, hash {}", parts.size(), Hashes.shortHash(published.hash()),
				slotParts.size(), slotsHash == null ? "-" : Hashes.shortHash(slotsHash));
		if (broadcast) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				send(server, player);
			}
		}
	}

	/**
	 * Forgets the server's published index (no more {@code world.index} for joining players, no warps, no habitat checks).
	 * For GameTests that publish one for themselves: a lingering index would make the runtime habitat check judge a later
	 * build's blocks before they are placed.
	 */
	public static void withdraw(MinecraftServer server) {
		CURRENT.remove(server);
	}

	/** The server's current WorldIndex (where every map lies), or null when the world has none. */
	public static @Nullable WorldIndex current(MinecraftServer server) {
		Published published = CURRENT.get(server);
		return published == null ? null : published.index();
	}

	/** The hash of the index the server currently hands out, or null. */
	public static @Nullable String currentHash(MinecraftServer server) {
		Published published = CURRENT.get(server);
		return published == null ? null : published.hash();
	}

	/** The parts the server would send, or an empty list. */
	public static List<WorldIndexParts.Part> currentParts(MinecraftServer server) {
		Published published = CURRENT.get(server);
		return published == null ? List.of() : published.parts();
	}

	/** The hash of the slot map the server hands out with its index ({@code world.slots}), or null when it sends none. */
	public static @Nullable String currentSlotsHash(MinecraftServer server) {
		Published published = CURRENT.get(server);
		return published == null ? null : published.slotsHash();
	}

	/** The {@code world.slots} parts the server would send, or an empty list. */
	public static List<WorldIndexParts.Part> currentSlotParts(MinecraftServer server) {
		Published published = CURRENT.get(server);
		return published == null ? List.of() : published.slotParts();
	}

	/** Sends the current index and slot map to one player whose client has our mod. Returns the number of parts sent. */
	public static int send(MinecraftServer server, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, KbJsonPayload.TYPE)) {
			return 0;
		}
		return sendTo(server, player);
	}

	/**
	 * {@link #send} without asking whether the player's client has our mod: the {@code world.index} parts, then the
	 * {@code world.slots} parts. GameTests use it (their mock players' connections never register channels).
	 */
	public static int sendTo(MinecraftServer server, ServerPlayer player) {
		Published published = CURRENT.get(server);
		if (published == null) {
			return 0;
		}
		int sent = sendParts(player, MsgTypes.WORLD_INDEX, published.parts());
		if (sent < published.parts().size()) {
			return sent;
		}
		int slots = sendParts(player, MsgTypes.WORLD_SLOTS, published.slotParts());
		KantoBridge.LOGGER.debug("world.index: sent {} parts (hash {}), world.slots {} parts", sent, Hashes.shortHash(published.hash()), slots);
		return sent + slots;
	}

	private static int sendParts(ServerPlayer player, String type, List<WorldIndexParts.Part> parts) {
		int sent = 0;
		for (WorldIndexParts.Part part : parts) {
			JsonObject data = new JsonObject();
			data.addProperty("index", part.index());
			data.addProperty("count", part.count());
			data.addProperty("hash", part.hash());
			data.addProperty("part", part.text());
			if (!RelayService.get().send(player, type, data, null)) {
				KantoBridge.LOGGER.error("{}: part {}/{} could not be sent", type, part.index() + 1, part.count());
				return sent;
			}
			sent++;
		}
		return sent;
	}
}
