package dev.kantobridge.server;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
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
 * "Relay only"), so clients can map blocks to cells. The index is read from {@code <world>/kantobridge/world_index.json}
 * when the server starts and replaced when a build finishes. Logs carry part counts and short hashes only.
 */
public final class WorldIndexSync {
	/** One server's current index, already split. */
	private record Published(String hash, List<WorldIndexParts.Part> parts) {
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

	/** Reads the world's index file, if it has one. */
	public static void load(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER).resolve(BuildTask.INDEX_FILE);
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			WorldIndex.fromJson(json);
			publish(server, json, false);
		} catch (IOException | DataException e) {
			KantoBridge.LOGGER.warn("world.index: the world's index is unreadable ({}); clients won't get one", e.getClass().getSimpleName());
		}
	}

	/** Makes {@code json} the index joining players get; with {@code broadcast}, sends it to everyone online now. */
	public static void publish(MinecraftServer server, String json, boolean broadcast) {
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(json, WorldIndexParts.PART_CHARS);
		Published published = new Published(parts.get(0).hash(), parts);
		CURRENT.put(server, published);
		KantoBridge.LOGGER.info("world.index: {} parts, hash {}", parts.size(), Hashes.shortHash(published.hash()));
		if (broadcast) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				send(server, player);
			}
		}
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

	/** Sends the current index to one player whose client has our mod. Returns the number of parts sent. */
	public static int send(MinecraftServer server, ServerPlayer player) {
		Published published = CURRENT.get(server);
		if (published == null || !ServerPlayNetworking.canSend(player, KbJsonPayload.TYPE)) {
			return 0;
		}
		int sent = 0;
		for (WorldIndexParts.Part part : published.parts()) {
			JsonObject data = new JsonObject();
			data.addProperty("index", part.index());
			data.addProperty("count", part.count());
			data.addProperty("hash", part.hash());
			data.addProperty("part", part.text());
			if (!RelayService.get().send(player, MsgTypes.WORLD_INDEX, data, null)) {
				KantoBridge.LOGGER.error("world.index: part {}/{} could not be sent", part.index() + 1, part.count());
				return sent;
			}
			sent++;
		}
		KantoBridge.LOGGER.debug("world.index: sent {} parts (hash {})", sent, Hashes.shortHash(published.hash()));
		return sent;
	}
}
