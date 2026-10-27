package dev.kantobridge.server;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.PokemonStoreManager;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.factory.PokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.mixin.PokemonStoreManagerAccessor;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft's half of a save (docs/PLAN.md §2.4 flow 12, §4.4 {@code SaveService}; DESIGN §6 "Saving: both sides save at
 * the same moment"): the hidden game saves (its menu, or a Pokémon Center heal) and gen1-bridge sends
 * {@code save.begin{save, reason}}, which the player's client relays here. The server then saves
 *
 * <ol>
 * <li>the player's party and PC (Cobblemon writes Pokémon stores only on its own timer, at logout and at shutdown),</li>
 * <li>the player's other Cobblemon data (Pokédex, general data, TM moves),</li>
 * <li>every player's data, every level's chunks and {@code level.dat}, as {@code /save-all} does,</li>
 * </ol>
 *
 * and answers {@code save.done{save, ok}} (with {@code re}). Everything is serialised at this moment on the server thread
 * and the files are written by the usual background writers, without a flush, so the server doesn't stall (the same
 * guarantee as Minecraft's autosave). {@code ok:false} for any exception, a world the builder holds players out of, and
 * levels with saving turned off ({@code /save-off}); each a counted warning. A repeated {@code save} id is answered again
 * from what the first one did, without saving twice. Logs carry reasons, counts and times. Server thread only.
 *
 * <p>M10: {@code save.begin} also names the hidden save it belongs to ({@code pair}, {@code at}); it becomes the player's
 * {@link SavePair} before anything is written, so the player data this save writes carries it ({@link SavePairService}).
 * The hidden game's save happened whatever Minecraft's does, so the pair is kept even when this save fails.
 */
public final class SaveService {
	private static final Set<String> REASONS = Set.of("menu", "center", "world", "quit");
	/** Answers kept per player for repeated ids (gen1-bridge sends a save again after a reconnect until answered). */
	static final int REMEMBERED = 16;

	/** Per player: the last saves' ids and whether they worked, oldest first. */
	private static final Map<UUID, LinkedHashMap<String, Boolean>> ANSWERS = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong SAVES = new AtomicLong();
	private static final AtomicLong REPEATS = new AtomicLong();

	private SaveService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.SAVE_BEGIN, SaveService::onBegin);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ANSWERS.clear();
			ASKED.clear();
		});
		// WORLD_SESSIONS_PLAN WS4 (§2.4, D5): Minecraft's own saves ask the hidden games for theirs
		ServerLifecycleEvents.BEFORE_SAVE.register(SaveService::beforeSave);
	}

	/** WS4: one {@code save.request} per player at most this often (the vanilla autosave runs every 5 minutes). */
	public static final long SAVE_MIN_MS = 60_000;
	/** WS4: when each player was last asked (wall clock), and the requests sent. */
	private static final Map<UUID, Long> ASKED = new HashMap<>();
	private static final AtomicLong REQUESTS = new AtomicLong();
	/** WS4's loop guard: true while {@link #save} runs Minecraft's save for a hidden save (its BEFORE_SAVE is ours). */
	private static boolean saving;

	/**
	 * WS4: Minecraft is about to save the world (the autosave, {@code /save-all}; WS0: the stop too, skipped here: the
	 * quit choreography saves the hidden games before a client leaves). Every player whose client can hear us gets
	 * {@code save.request{reason:"world"}} (the client fans it out to its bound links), at most once per
	 * {@link #SAVE_MIN_MS}; a save {@link #save} itself runs is not asked about (the loop guard).
	 */
	static void beforeSave(MinecraftServer server, boolean flush, boolean force) {
		if (saving || !server.isRunning()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			request(player, now);
		}
	}

	/** WS4: sends one player {@code save.request{reason:"world"}} unless they were asked within SAVE_MIN_MS; true when sent. */
	public static boolean request(ServerPlayer player, long now) {
		Long last = ASKED.get(player.getUUID());
		if (last != null && now - last < SAVE_MIN_MS) {
			return false;
		}
		JsonObject data = new JsonObject();
		data.addProperty("reason", "world");
		if (!RelayService.get().send(player, MsgTypes.SAVE_REQUEST, data, null)) {
			return false;
		}
		ASKED.put(player.getUUID(), now);
		REQUESTS.incrementAndGet();
		return true;
	}

	/** WS4: {@code save.request}s sent to players. */
	public static long requests() {
		return REQUESTS.get();
	}

	/** WS4 (tests): forgets when the players were asked. */
	public static void resetRequests() {
		ASKED.clear();
	}

	static void onBegin(ServerPlayer player, Envelope message) {
		JsonObject data = message.data();
		String save;
		String reason;
		String pair;
		long at;
		String region;
		try {
			DataReader reader = DataReader.of(data, "").allowOnly(Set.of("save", "reason", "pair", "at", "region"));
			save = reader.string("save", Ids.ID);
			reason = reader.string("reason");
			if (!REASONS.contains(reason)) {
				throw new DataException(reader.path("reason"), "unknown reason");
			}
			pair = reader.string("pair", DataReader.HASH);
			at = reader.has("at") ? reader.longValue("at", 0, Long.MAX_VALUE) : -1;
			// MG3: the region whose hidden game saved (its pair is kept per region)
			region = Regions.currentRegionId(player, reader.optionalRegion());
			if (reader.has("region") && region == null) {
				throw new DataException(reader.path("region"), "unknown region");
			}
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("save: malformed save.begin at {} ({})", e.path(), e.problem());
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			String id = idOf(data);
			if (id != null) {
				// The hidden game waits for this id: tell it nothing was saved.
				answer(player, id, false, message.seq());
			}
			return;
		}
		// The hidden game saved: its pair goes into the player's data before this save writes it (a repeat changes nothing).
		SavePairService.saveBegun(player, region, pair, at);
		LinkedHashMap<String, Boolean> answers = ANSWERS.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>());
		Boolean before = answers.get(save);
		if (before != null) {
			REPEATS.incrementAndGet();
			KantoBridge.LOGGER.debug("save: answered a repeated save.begin again");
			answer(player, save, before, message.seq());
			return;
		}
		boolean ok = save(player, reason);
		answers.put(save, ok);
		Iterator<String> oldest = answers.keySet().iterator();
		while (answers.size() > REMEMBERED && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
		answer(player, save, ok, message.seq());
	}

	/** Saves Minecraft's side of a hidden-game save for {@code player} (see the class comment). */
	public static boolean save(ServerPlayer player, String reason) {
		MinecraftServer server = player.server;
		if (BuildHold.isHolding(server)) {
			// Held players are spectators until the build is done; no save may keep that mode.
			warn("held");
			return false;
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (level.noSave()) {
				warn("saving_off");
				return false;
			}
		}
		long started = System.nanoTime();
		try {
			int stores = savePokemon(player);
			if (stores < 2) {
				// Another mod's storage: it keeps its own schedule, so this save can't promise the Pokémon.
				warn("pokemon_not_saved");
				return false;
			}
			saveCobblemonData(player);
			// WS4's loop guard: this save is the hidden game's own; its BEFORE_SAVE asks nobody
			boolean levels;
			saving = true;
			try {
				levels = server.saveEverything(true, false, false);
			} finally {
				saving = false;
			}
			if (!levels) {
				warn("no_levels");
				return false;
			}
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("save: saving failed ({})", e.getClass().getName());
			warn("failed");
			return false;
		}
		long count = SAVES.incrementAndGet();
		KantoBridge.LOGGER.info("save: saved for a {} save in {} ms; {} so far", reason, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
				count);
		return true;
	}

	/**
	 * Hands the player's party and PC to Cobblemon's store writer now (serialised here, written by its save thread).
	 * Returns how many of the two stores were handed over.
	 */
	public static int savePokemon(ServerPlayer player) {
		PokemonStoreManager storage = Cobblemon.INSTANCE.getStorage();
		List<PokemonStore<?>> stores = List.of(storage.getParty(player), storage.getPC(player));
		int saved = 0;
		for (PokemonStoreFactory factory : ((PokemonStoreManagerAccessor) storage).kantobridge$factories()) {
			if (factory instanceof FileBackedPokemonStoreFactory<?> files) {
				for (PokemonStore<?> store : stores) {
					// The factory that cached a store is the one that loaded it, and the one that must write it.
					if (files.isCached(store)) {
						files.save(store, player.registryAccess());
						saved++;
					}
				}
			}
		}
		return saved;
	}

	/** The player's Pokédex, general data and TM moves (Cobblemon saves these every two minutes and at logout). */
	static void saveCobblemonData(ServerPlayer player) {
		for (PlayerInstancedDataFactory<?> factory : Cobblemon.INSTANCE.getPlayerDataManager().getFactories().values()) {
			factory.saveSingle(player.getUUID());
		}
	}

	private static void answer(ServerPlayer player, String save, boolean ok, long re) {
		JsonObject data = new JsonObject();
		data.addProperty("save", save);
		data.addProperty("ok", ok);
		RelayService.get().send(player, MsgTypes.SAVE_DONE, data, re);
	}

	/** The {@code save} id of a malformed message, if it has a usable one. */
	private static @Nullable String idOf(JsonObject data) {
		JsonElement value = data.get("save");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			return null;
		}
		String id = value.getAsString();
		return Ids.ID.matcher(id).matches() ? id : null;
	}

	// ---- views (tests, diagnostics) ----

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("save: {} ({} so far)", code, count);
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> warnings() {
		Map<String, Long> out = new TreeMap<>();
		WARNINGS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	/** Saves made (repeats and refusals not counted). */
	public static long saves() {
		return SAVES.get();
	}

	/** Repeated ids answered without saving again. */
	public static long repeats() {
		return REPEATS.get();
	}
}
