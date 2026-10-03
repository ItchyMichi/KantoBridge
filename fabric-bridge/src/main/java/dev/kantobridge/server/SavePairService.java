package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.DaycareService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Save pairs (M10 crash recovery; docs/PROTOCOL.md "Saving"): the world remembers which hidden-game save it last saved
 * together with, and warns the player when the hidden game turns out to run from another one.
 *
 * <ul>
 * <li><b>{@code save.begin{pair, at}}</b> ({@link SaveService}, before it saves): the player's {@link SavePair} becomes
 * that pair with no operations, so it is in the player data the save writes. A repeat of the same pair changes
 * nothing.</li>
 * <li><b>Operations</b>: every {@code party.give}, {@code party.take}, {@code daycare.deposit} and
 * {@code daycare.withdraw} the hidden game sends counts once. gen1-bridge sends one again after each snapshot until a
 * {@code party.sync} shows it, so a message that changes nothing (a gift whose id a Pokémon already carries, a take or
 * deposit of a Pokémon no longer there, a withdrawal of one already back) is a repeat and doesn't count, and neither
 * does the same operation on the same gift or Pokémon twice in a row (a gift that couldn't be given, sent again).</li>
 * <li><b>{@code save.loaded}</b> (C→S, which save the hidden game runs from): compared with the stored pair
 * ({@link SavePairRules}); a mismatch is sent to the player's client as {@code save.mismatch}, every time (the client
 * shows each distinct one once). Then the daycare is brought in line with the hidden game's
 * ({@link DaycareService#reconcile}): a Pokémon the server boards that the hidden daycare doesn't (Gen1Recomp closed
 * without saving after a deposit) comes back to the party.</li>
 * </ul>
 * The stored pair changes only with {@code save.begin} and the count, so a mismatch stands until both games save
 * together again. A client speaks only for its own player. Logs carry kinds, counts and short hashes. Server thread only.
 */
public final class SavePairService {
	/** Operations remembered per player for "the same operation twice in a row". */
	static final int REMEMBERED = 128;

	/** Per player: the last operation counted per gift id or Pokémon uid (in memory; a restart forgets it). */
	private static final Map<UUID, Map<String, String>> LAST = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> MISMATCHES = new ConcurrentHashMap<>();
	private static final AtomicLong LOADED = new AtomicLong();
	private static final AtomicLong OPS = new AtomicLong();
	private static final AtomicLong REPEATS = new AtomicLong();

	private SavePairService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		SavePair.init();
		RelayService.get().register(MsgTypes.SAVE_LOADED, SavePairService::onLoaded);
		ServerPlayerEvents.LEAVE.register(player -> LAST.remove(player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST.clear());
	}

	// ---- save.begin and operations ----

	/**
	 * The hidden game saved ({@code save.begin}): this world's pair is now {@code pair} (its time {@code at}, or -1), with
	 * no operations since. Call before the save writes the player's data. A repeated pair keeps its count.
	 */
	public static void saveBegun(ServerPlayer player, String pair, long at) {
		SavePair before = SavePair.of(player);
		if (pair.equals(before.pair())) {
			return;
		}
		SavePair.set(player, new SavePair(pair, Math.max(-1, at), 0));
		KantoBridge.LOGGER.debug("save pair: now {}", dev.kantobridge.world.Hashes.shortHash(pair));
	}

	/**
	 * One of the hidden game's Pokémon operations arrived.
	 *
	 * @param type    {@code give}, {@code take}, {@code deposit} or {@code withdraw}
	 * @param subject the gift's id or the Pokémon's uid
	 * @param repeat  it changes nothing: gen1-bridge sent it again
	 */
	public static void operation(ServerPlayer player, String type, String subject, boolean repeat) {
		Map<String, String> last = LAST.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
				return this.size() > REMEMBERED;
			}
		});
		String before = last.put(subject, type);
		if (repeat || type.equals(before)) {
			REPEATS.incrementAndGet();
			return;
		}
		SavePair.set(player, SavePair.of(player).counted());
		OPS.incrementAndGet();
	}

	// ---- save.loaded ----

	static void onLoaded(ServerPlayer player, Envelope message) {
		SavePairRules.Loaded loaded;
		try {
			loaded = read(DataReader.of(message.data(), ""));
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("save pair: malformed save.loaded at {} ({})", e.path(), e.problem());
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		LOADED.incrementAndGet();
		SavePairRules.Mismatch mismatch = SavePairRules.compare(SavePair.of(player), loaded);
		if (mismatch != null) {
			MISMATCHES.computeIfAbsent(mismatch.kind().id(), key -> new AtomicLong()).incrementAndGet();
			KantoBridge.LOGGER.warn("save pair: the hidden game's save doesn't match this world's ({}, ops {})", mismatch.kind().id(),
					mismatch.ops() < 0 ? "-" : Integer.toString(mismatch.ops()));
			RelayService.get().send(player, MsgTypes.SAVE_MISMATCH, mismatch.toJson(), null);
		}
		// The daycare follows the hidden game's (it logs what came back).
		DaycareService.reconcile(player, loaded.daycare());
	}

	/** {@code save.loaded}'s data, checked like its schema (the relay checks envelopes only). */
	static SavePairRules.Loaded read(DataReader reader) throws DataException {
		reader.allowOnly(Set.of("fresh", "ops", "pair", "at", "daycare"));
		boolean fresh = reader.bool("fresh");
		int ops = reader.integer("ops", 0, Integer.MAX_VALUE);
		String pair = reader.optionalString("pair", DataReader.HASH);
		long at = reader.has("at") ? reader.longValue("at", 0, Long.MAX_VALUE) : -1;
		String daycare = reader.optionalString("daycare", Ids.UID);
		if (!fresh && pair == null) {
			throw new DataException(reader.path("pair"), "required unless fresh");
		}
		return new SavePairRules.Loaded(fresh, pair, at, ops, daycare == null ? null : UUID.fromString(daycare));
	}

	// ---- views (tests, diagnostics) ----

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("save pair: {} ({} so far)", code, count);
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	/** {@code save.mismatch}es sent, per kind. */
	public static Map<String, Long> mismatches() {
		Map<String, Long> out = new TreeMap<>();
		MISMATCHES.forEach((kind, count) -> out.put(kind, count.get()));
		return out;
	}

	/** {@code save.loaded}s read. */
	public static long loaded() {
		return LOADED.get();
	}

	/** Operations counted (repeats not). */
	public static long operations() {
		return OPS.get();
	}

	/** Operations that were repeats. */
	public static long repeats() {
		return REPEATS.get();
	}

	/** The player's save pair (tests). */
	public static SavePair of(ServerPlayer player) {
		return SavePair.of(player);
	}

	/** Forgets the remembered operations (tests: as after a restart). */
	public static void forget(@Nullable ServerPlayer player) {
		if (player == null) {
			LAST.clear();
		} else {
			LAST.remove(player.getUUID());
		}
	}
}
