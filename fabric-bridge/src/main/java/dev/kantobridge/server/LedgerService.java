package dev.kantobridge.server;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Items;
import dev.kantobridge.world.RegionRecord;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The one trainer economy on the server (docs/specs/MULTI_GAME_PLAN.md §4.9, D7, MG6): each player's {@link Ledger}
 * (wallet and item ledger, persistent) is <b>projected</b> into the followed hidden game as {@code bag.set} and <b>read
 * back</b> from that game's {@code bag.sync}. The rules are {@link LedgerRules}' (plain Java); this class keeps, per
 * player and region, what was last projected, carries the messages and reacts to the game's answers.
 *
 * <ul>
 * <li>A region world only: {@link #enabled} is false for a legacy world (no {@code regions.json}), whose bag keeps
 * today's per-game path in {@link dev.kantobridge.bag.BagService} untouched.</li>
 * <li>{@code bag.sync} with the projection's stamp → the read-back; without a stamp → the save's one-time adoption, then
 * a projection; with another stamp (an older save restored) → a fresh projection, no diff.</li>
 * <li>Projections are sent after a crossing ({@link PassService} → {@link #onFollowed}), after an adoption, and again
 * when the game answered {@code busy} (a few ticks later) or {@code not_ingame} (on its next {@code save.loaded}).</li>
 * <li>The forwarded {@code act.result{kind:"bag.set"}} says what the bag could not hold ({@code left}): it stays in the
 * ledger ("in your trunk") and the remembered projection shrinks by it, so the next read-back doesn't count the
 * shortfall as consumption.</li>
 * <li>A mirrored item Minecraft consumed debits the ledger ({@link #use}); the followed game still gets its
 * {@code bag.use}, and its baseline moves with it.</li>
 * </ul>
 * Logs carry region ids, counts and codes only. Server thread only.
 */
public final class LedgerService {
	/** Ticks before a projection refused {@code busy} is sent again. */
	static final int RETRY_TICKS = 40;
	private static final Set<String> RESULT_FIELDS = Set.of("ok", "kind", "reason", "detail", "moved", "left", "region");
	private static final Pattern REASON = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
	private static final Random RANDOM = new SecureRandom();

	/** What this server last projected into one player's region. */
	private static final class Slot {
		@Nullable LedgerRules.Projection projected;
		/** A projection the game refused {@code not_ingame}: again on its next {@code save.loaded}. */
		boolean pending;
		/** A projection the game refused {@code busy}: again at this tick (-1: none). */
		long retryAt = -1;
		@Nullable String game;
	}

	private static final Map<UUID, Map<String, Slot>> SLOTS = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong SYNCS = new AtomicLong();
	private static final AtomicLong ADOPTIONS = new AtomicLong();
	private static final AtomicLong PROJECTIONS = new AtomicLong();
	private static final AtomicLong REPROJECTED = new AtomicLong();
	private static final AtomicLong RESULTS = new AtomicLong();

	private LedgerService() {
	}

	/** Call once from common init, after the relay and the region services. */
	public static void init() {
		Ledger.init();
		RelayService.get().register(MsgTypes.ACT_RESULT, LedgerService::onResult);
		ServerTickEvents.END_SERVER_TICK.register(LedgerService::tick);
		ServerPlayerEvents.LEAVE.register(player -> SLOTS.remove(player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SLOTS.clear());
	}

	/** True in a region world (several games): the ledger owns bag and money. A legacy world keeps the per-game bag. */
	public static boolean enabled(@Nullable MinecraftServer server) {
		Regions regions = Regions.of(server);
		return regions != null && !regions.isLegacyWorld();
	}

	// ---- bag.sync ----

	/**
	 * A hidden game's bag report, relayed by the client ({@code bag.sync}: items by that game's symbols, {@code money}, the
	 * {@code stamp} of the last projection or null): the read-back, the first adoption, or a fresh projection.
	 *
	 * @param money the save's money, or -1 when the report carried none
	 */
	public static void onSync(ServerPlayer player, RegionRecord region, Map<String, Integer> bag, long money, @Nullable String stamp) {
		String family = Games.family(region.game());
		if (family == null) {
			warn("unknown_game");
			return;
		}
		Items catalogue = Items.bundled();
		Slot slot = slot(player.getUUID(), region.id());
		slot.game = region.game();
		Ledger ledger = Ledger.of(player);
		SYNCS.incrementAndGet();
		if (stamp != null) {
			if (slot.projected != null && stamp.equals(slot.projected.stamp())) {
				Ledger out = LedgerRules.readBack(ledger, catalogue, family, slot.projected, bag, money);
				if (!out.equals(ledger)) {
					Ledger.set(player, out);
				}
				slot.projected = new LedgerRules.Projection(bag, money < 0 ? slot.projected.money() : money, stamp);
			} else if (slot.projected == null && stamp.equals(ledger.stamp(region.id()))) {
				// After a restart: the save carries the stamp this server projected last, and the ledger already holds
				// everything that game reported while followed, so its bag is the baseline again.
				slot.projected = new LedgerRules.Projection(bag, money < 0 ? ledger.money() : money, stamp);
			} else {
				// An older save restored (save.mismatch kinds): the ledger's view stands.
				REPROJECTED.incrementAndGet();
				KantoBridge.LOGGER.info("ledger: {} reports a save with another stamp; projecting again", region.id());
				project(player, region.id(), region.game());
			}
			return;
		}
		String key = adoptionKey(player, region.id());
		if (!ledger.isAdopted(key)) {
			Ledger.set(player, LedgerRules.adopt(ledger, catalogue, family, bag, Math.max(0, money), key));
			ADOPTIONS.incrementAndGet();
			KantoBridge.LOGGER.info("ledger: adopted a save of {} ({} item kinds, money {})", region.id(), bag.size(), Math.max(0, money));
		}
		project(player, region.id(), region.game());
	}

	/** What marks a save as merged: its save pair, or, for a New Game never saved, the region's fresh-game key. */
	private static String adoptionKey(ServerPlayer player, String region) {
		SavePair pair = SavePairs.of(player, region);
		return pair.pair() != null ? pair.pair() : region + ":fresh";
	}

	// ---- bag.set ----

	/** Writes the ledger into a region's game: {@code bag.set} to the player's client, which relays it to that link. */
	public static boolean project(ServerPlayer player, String region, String game) {
		String family = Games.family(game);
		if (family == null) {
			warn("unknown_game");
			return false;
		}
		Items catalogue = Items.bundled();
		Ledger ledger = Ledger.of(player);
		LedgerRules.Projected projected = LedgerRules.project(ledger, catalogue, family, LedgerRules.Limits.of(family));
		String stamp = LedgerRules.newStamp(RANDOM);
		JsonObject items = new JsonObject();
		for (Map.Entry<String, Integer> entry : projected.items().entrySet()) {
			items.addProperty(entry.getKey(), entry.getValue());
		}
		JsonObject data = new JsonObject();
		data.add("items", items);
		data.addProperty("money", ledger.money());
		data.addProperty("stamp", stamp);
		Regions.tag(data, player.server, region);
		if (!RelayService.get().send(player, MsgTypes.BAG_SET, data, null)) {
			warn("send_failed");
			return false;
		}
		Slot slot = slot(player.getUUID(), region);
		slot.game = game;
		slot.pending = false;
		slot.retryAt = -1;
		slot.projected = new LedgerRules.Projection(projected.items(), ledger.money(), stamp);
		Ledger.set(player, ledger.withStamp(region, stamp));
		PROJECTIONS.incrementAndGet();
		KantoBridge.LOGGER.info("ledger: projected into {} ({} item kinds, {} left in the trunk)", region, projected.items().size(),
				projected.left().size());
		return true;
	}

	/**
	 * A crossing ended ({@link PassService#onRegionState}): the far game, now followed, gets the ledger — unless it was never
	 * projected into, when its first {@code bag.sync} adopts it first (projecting before that would overwrite its bag).
	 */
	public static void onFollowed(ServerPlayer player, @Nullable String region) {
		if (region == null || !enabled(player.server)) {
			return;
		}
		Regions regions = Regions.of(player.server);
		RegionRecord record = regions == null ? null : regions.byId(region);
		if (record == null) {
			return;
		}
		Slot slot = slot(player.getUUID(), region);
		if (slot.projected == null && Ledger.of(player).stamp(region) == null) {
			return;
		}
		project(player, region, record.game());
	}

	/** A region's game loaded a save ({@code save.loaded}): a projection it refused {@code not_ingame} is owed now. */
	public static void onLoaded(ServerPlayer player, @Nullable String region) {
		if (region == null || !enabled(player.server)) {
			return;
		}
		Slot slot = SLOTS.getOrDefault(player.getUUID(), Map.of()).get(region);
		if (slot != null && slot.pending && slot.game != null) {
			project(player, region, slot.game);
		}
	}

	// ---- consumption in Minecraft ----

	/**
	 * Minecraft consumed a mirrored item (a thrown ball, a potion): the ledger falls; the followed game's remembered
	 * projection falls too, since the {@code bag.use} it also receives will show the same fall in its next report.
	 */
	public static void use(ServerPlayer player, String symbol, int qty) {
		if (!enabled(player.server) || qty <= 0) {
			return;
		}
		Items catalogue = Items.bundled();
		Ledger.set(player, LedgerRules.use(Ledger.of(player), catalogue, symbol, qty));
		RegionRecord followed = RegionPresenceService.followed(player);
		if (followed == null) {
			return;
		}
		Slot slot = SLOTS.getOrDefault(player.getUUID(), Map.of()).get(followed.id());
		if (slot == null || slot.projected == null) {
			return;
		}
		String id = catalogue.canonical("gen1", symbol);
		String theirs = id == null ? null : LedgerRules.symbolFor(catalogue, Games.family(followed.game()), id);
		if (theirs != null) {
			slot.projected = slot.projected.less(Map.of(theirs, qty));
		}
	}

	/** The mirror's targets in a region world: the ledger's mirrored items by their Gen 1 symbols. */
	public static Map<String, Integer> mirrorTargets(ServerPlayer player) {
		return LedgerRules.mirrorTargets(Ledger.of(player), Items.bundled());
	}

	// ---- act.result{kind:"bag.set"} ----

	/** The hidden game's answer to a projection, forwarded by the client. Answers of other kinds are not the server's. */
	static void onResult(ServerPlayer player, Envelope message) {
		boolean ok;
		String reason;
		String regionId;
		Map<String, Integer> left = new TreeMap<>();
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(RESULT_FIELDS);
			if (!reader.has("kind") || !LedgerRules.KIND.equals(reader.string("kind"))) {
				return;
			}
			ok = reader.bool("ok");
			reason = reader.optionalString("reason", REASON);
			regionId = reader.optionalRegion();
			DataReader entries = reader.optionalObject("left");
			if (entries != null) {
				for (String symbol : entries.json().keySet()) {
					if (!Ids.ITEM_ID.matcher(symbol).matches()) {
						throw new DataException(entries.path(), "bad item id");
					}
					left.put(symbol, DataReader.asInt(entries.json().get(symbol), entries.path() + "/item", 1, Ledger.MAX_ITEM));
				}
			}
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("ledger: malformed act.result at {} ({})", e.path(), e.problem());
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		RESULTS.incrementAndGet();
		RegionRecord record;
		Regions regions = Regions.of(player.server);
		if (regions == null) {
			return;
		}
		record = regionId != null ? regions.byId(regionId) : RegionPresenceService.followed(player);
		if (record == null) {
			warn("unknown_region");
			return;
		}
		Slot slot = slot(player.getUUID(), record.id());
		if (ok || "too_many".equals(reason)) {
			// what did not fit stays in the ledger (the trunk); the game's bag holds the rest
			if (slot.projected != null && !left.isEmpty()) {
				slot.projected = slot.projected.less(left);
			}
			return;
		}
		if ("busy".equals(reason)) {
			slot.retryAt = player.server.getTickCount() + RETRY_TICKS;
		} else if ("not_ingame".equals(reason)) {
			slot.pending = true;
		} else {
			warn("refused");
		}
	}

	private static void tick(MinecraftServer server) {
		if (SLOTS.isEmpty() || server.getTickCount() % 20 != 0) {
			return;
		}
		long now = server.getTickCount();
		for (Map.Entry<UUID, Map<String, Slot>> player : SLOTS.entrySet()) {
			for (Map.Entry<String, Slot> entry : player.getValue().entrySet()) {
				Slot slot = entry.getValue();
				if (slot.retryAt >= 0 && now >= slot.retryAt && slot.game != null) {
					slot.retryAt = -1;
					ServerPlayer online = server.getPlayerList().getPlayer(player.getKey());
					if (online != null) {
						project(online, entry.getKey(), slot.game);
					}
				}
			}
		}
	}

	private static Slot slot(UUID player, String region) {
		return SLOTS.computeIfAbsent(player, id -> new HashMap<>()).computeIfAbsent(region, id -> new Slot());
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("ledger: {} ({} so far)", code, count);
	}

	// ---- views (tests, diagnostics) ----

	/** What the ledger holds that the given family's games cannot take now (the trunk), canonical id → quantity. */
	public static Map<String, Integer> trunk(ServerPlayer player, String family) {
		return LedgerRules.project(Ledger.of(player), Items.bundled(), family, LedgerRules.Limits.of(family)).left();
	}

	/** The projection this server remembers for a player's region, or null. */
	public static @Nullable LedgerRules.Projection projected(ServerPlayer player, String region) {
		Slot slot = SLOTS.getOrDefault(player.getUUID(), Map.of()).get(region);
		return slot == null ? null : slot.projected;
	}

	public static long syncs() {
		return SYNCS.get();
	}

	public static long adoptions() {
		return ADOPTIONS.get();
	}

	public static long projections() {
		return PROJECTIONS.get();
	}

	public static long reprojected() {
		return REPROJECTED.get();
	}

	public static long results() {
		return RESULTS.get();
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> warnings() {
		Map<String, Long> out = new LinkedHashMap<>();
		WARNINGS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	/** Forgets what was projected (tests: as after a restart). */
	public static void forget(@Nullable ServerPlayer player) {
		if (player == null) {
			SLOTS.clear();
		} else {
			SLOTS.remove(player.getUUID());
		}
	}
}
