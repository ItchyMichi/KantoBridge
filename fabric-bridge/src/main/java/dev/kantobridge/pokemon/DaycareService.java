package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.Regions;
import dev.kantobridge.server.SavePairService;
import dev.kantobridge.server.SaveService;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.RegionRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Gen 1's daycare done with Cobblemon (docs/PLAN.md P15; PROTOCOL.md {@code daycare.deposit}, {@code daycare.withdraw}):
 *
 * <ul>
 * <li><b>{@code daycare.deposit{uid}}</b> (the hidden game boarded a party member): the Pokémon leaves the party (or the
 * PC) for the player's daycare on the server ({@link DaycareStore}, persistent in the world's {@code kantobridge/}
 * folder); {@code party.sync} follows without it.</li>
 * <li><b>{@code daycare.withdraw{uid, steps}}</b>: it comes back to the party (the PC when the party is full) with one
 * experience point per step walked meanwhile, never past level 100 ({@link DaycareRules}); Cobblemon's level-ups,
 * learned moves and evolution prompt follow as after any experience.</li>
 * </ul>
 * The daycare file is written before the Pokémon leaves the party, and the player's party and PC are saved right after,
 * so a crash can't lose it; should one leave it in both, the party's copy wins (a deposit replaces the stale entry, a
 * withdrawal gives that copy the steps), never making a second Pokémon with the same uid. A repeated deposit or
 * withdrawal changes nothing; a uid the server can't match (not in the party, not in the daycare) is refused with a
 * counted warning. A {@code party.take{reason:"daycare"}} is handled as a deposit. Logs carry codes and counts, never
 * names. Server thread only.
 *
 * <p>Emerald's daycare holds two (docs/SEVERAL_GAMES_FOLLOWUPS.md item 7): the store holds whatever the hidden game
 * boards, each deposited and withdrawn on its own.
 *
 * <p>M10: the hidden game says which Pokémon its daycare holds ({@code save.loaded.daycare}, and {@code boarded} when it
 * holds more than one); {@link #reconcile} sends
 * every other Pokémon this daycare holds for the player back to the party (the PC when full): Gen1Recomp closed without
 * saving after a deposit no longer strands it here. A withdrawal of a Pokémon already in the party or PC changes nothing,
 * whatever the reason. Deposits and withdrawals count as the save pair's operations ({@link SavePairService}).
 */
public final class DaycareService {
	public static final String FOLDER = "daycare";
	/** Daycare experience's source, for Cobblemon's events: a side mod. */
	static final String EXPERIENCE_SOURCE = "kantobridge_daycare";

	/** Per server: the stores read so far, by {@link #key(String, UUID)} (MG3: one daycare per region and player). */
	private static final Map<MinecraftServer, Map<String, DaycareStore>> STORES = Collections.synchronizedMap(new WeakHashMap<>());
	/** Per player: the uids withdrawn since the server started, so a repeated withdrawal is told from a mismatch. */
	private static final Map<UUID, Set<UUID>> WITHDRAWN = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong DEPOSITS = new AtomicLong();
	private static final AtomicLong WITHDRAWALS = new AtomicLong();
	private static final AtomicLong REPEATS = new AtomicLong();
	private static final AtomicLong RETURNED = new AtomicLong();

	private DaycareService() {
	}

	/** Call once from common init, after the relay and {@link PartyService}. */
	public static void init() {
		RelayService relay = RelayService.get();
		relay.register(MsgTypes.DAYCARE_DEPOSIT, DaycareService::onDeposit);
		relay.register(MsgTypes.DAYCARE_WITHDRAW, DaycareService::onWithdraw);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			STORES.remove(server);
			WITHDRAWN.clear();
		});
	}

	// ---- messages ----

	static void onDeposit(ServerPlayer player, Envelope message) {
		UUID uid;
		String region;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "region"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			region = regionOf(player, reader);
		} catch (DataException e) {
			reject(player, message, "deposit", e);
			return;
		}
		deposit(player, region, uid);
	}

	static void onWithdraw(ServerPlayer player, Envelope message) {
		UUID uid;
		int steps;
		String region;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "steps", "region"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			steps = reader.integer("steps", 0, Integer.MAX_VALUE);
			region = regionOf(player, reader);
		} catch (DataException e) {
			reject(player, message, "withdraw", e);
			return;
		}
		withdraw(player, region, uid, steps);
	}

	/** MG3: the region a message is about (its {@code region}, else the player's); an unknown id is malformed. */
	private static @Nullable String regionOf(ServerPlayer player, DataReader reader) throws DataException {
		String region = Regions.currentRegionId(player, reader.optionalRegion());
		if (reader.has("region") && region == null) {
			throw new DataException(reader.path("region"), "unknown region");
		}
		return region;
	}

	// ---- the daycare ----

	/** Boards a party (or PC) Pokémon at the legacy region's daycare; true when it is there afterwards. */
	public static boolean deposit(ServerPlayer player, UUID uid) {
		return deposit(player, null, uid);
	}

	/** {@link #deposit(ServerPlayer, UUID)} at a region's daycare (MG3; {@code null}: the legacy region). */
	public static boolean deposit(ServerPlayer player, @Nullable String region, UUID uid) {
		DaycareStore store = store(player, region);
		if (store == null) {
			return false;
		}
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		PCStore pc = PlayerExtensionsKt.pc(player);
		Pokemon pokemon = party.get(uid);
		boolean fromPc = false;
		if (pokemon == null) {
			pokemon = pc.get(uid);
			fromPc = pokemon != null;
		}
		// Not in the party or PC: nothing to board, so gen1-bridge sent it again (or it's a mismatch, warned below).
		SavePairService.operation(player, region, "deposit", uid.toString(), pokemon == null);
		if (pokemon == null) {
			if (store.contains(uid)) {
				// gen1-bridge repeats what it hasn't seen applied; the daycare has it already.
				REPEATS.incrementAndGet();
				return true;
			}
			warn("deposit_unknown_uid");
			return false;
		}
		if (store.contains(uid)) {
			// An entry left from before a crash that kept it in the party too: the party's copy is the one boarding now.
			warn("deposit_replaced_stale");
		}
		if (pokemon.getEntity() != null) {
			pokemon.recall();
		}
		JsonObject data;
		try {
			data = pokemon.saveToJSON(player.registryAccess(), new JsonObject());
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("daycare: Cobblemon couldn't write the Pokémon ({})", e.getClass().getName());
			warn("deposit_unwritable");
			return false;
		}
		// The daycare gets it on disk first: should anything below fail, it is still in the party as well, never lost.
		store.put(uid, data);
		if (!write(player, region, store)) {
			store.remove(uid);
			warn("deposit_io");
			return false;
		}
		boolean removed = fromPc ? pc.remove(pokemon) : party.remove(pokemon);
		if (!removed) {
			store.remove(uid);
			write(player, region, store);
			warn("deposit_not_removed");
			return false;
		}
		Set<UUID> withdrawn = WITHDRAWN.get(player.getUUID());
		if (withdrawn != null) {
			withdrawn.remove(uid);
		}
		SaveService.savePokemon(player);
		PartyService.markDirty(player);
		long count = DEPOSITS.incrementAndGet();
		KantoBridge.LOGGER.info("daycare: a Pokémon was deposited ({} held); {} deposits so far", store.size(), count);
		return true;
	}

	/** Brings a Pokémon back from the legacy region's daycare with {@code steps}' worth of experience; true when it is back. */
	public static boolean withdraw(ServerPlayer player, UUID uid, int steps) {
		return withdraw(player, null, uid, steps);
	}

	/** {@link #withdraw(ServerPlayer, UUID, int)} from a region's daycare (MG3; {@code null}: the legacy region). */
	public static boolean withdraw(ServerPlayer player, @Nullable String region, UUID uid, int steps) {
		DaycareStore store = store(player, region);
		if (store == null) {
			return false;
		}
		JsonObject data = store.get(uid);
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		Pokemon existing = party.get(uid);
		if (existing == null) {
			existing = PlayerExtensionsKt.pc(player).get(uid);
		}
		// Not boarded here: nothing to bring back, so gen1-bridge sent it again (or it's a mismatch, warned below).
		SavePairService.operation(player, region, "withdraw", uid.toString(), data == null);
		if (data == null) {
			if (existing != null) {
				// Already back in the party or PC (a repeat, the reconcile, a hidden game gone back to an older save): it stays
				// as it is, never a second Pokémon with the same uid. Unexplained ones are counted.
				REPEATS.incrementAndGet();
				if (!WITHDRAWN.getOrDefault(player.getUUID(), Set.of()).contains(uid)) {
					warn("withdraw_not_deposited");
				}
				return true;
			}
			// Never boarded here (or long since back): the hidden game and Minecraft disagree.
			warn("withdraw_unknown_uid");
			return false;
		}
		Pokemon pokemon;
		if (existing != null) {
			// The party kept a copy (a crash between the deposit's two writes): never a second Pokémon with one uid. That
			// copy comes back with the steps; the daycare's entry goes.
			warn("withdraw_already_back");
			pokemon = existing;
		} else {
			try {
				pokemon = Pokemon.Companion.loadFromJSON(player.registryAccess(), data);
			} catch (RuntimeException e) {
				KantoBridge.LOGGER.warn("daycare: Cobblemon couldn't read the Pokémon ({})", e.getClass().getName());
				warn("withdraw_unreadable");
				return false;
			}
			// The party, or the PC when it is full.
			if (!party.add(pokemon)) {
				warn("withdraw_no_room");
				return false;
			}
		}
		store.remove(uid);
		if (!write(player, region, store)) {
			// It is back; a later withdrawal of the stale entry finds it in the party and only drops the entry.
			warn("withdraw_io");
		}
		WITHDRAWN.computeIfAbsent(player.getUUID(), key -> new HashSet<>()).add(uid);
		int cap = DaycareRules.levelCap(Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel());
		int experience = DaycareRules.experience(steps, pokemon.getExperience(), pokemon.getExperienceGroup().getExperience(cap));
		if (experience > 0) {
			SidemodExperienceSource source = new SidemodExperienceSource(EXPERIENCE_SOURCE);
			if (party.get(uid) != null) {
				// In the party: the player's client shows the level-ups.
				pokemon.addExperienceWithPlayer(player, source, experience);
			} else {
				pokemon.addExperience(source, experience);
			}
		}
		SaveService.savePokemon(player);
		PartyService.markDirty(player);
		long count = WITHDRAWALS.incrementAndGet();
		KantoBridge.LOGGER.info("daycare: a Pokémon was withdrawn ({} experience for {} steps); {} withdrawals so far", experience, steps, count);
		return true;
	}

	/**
	 * The hidden game's daycare holds {@code hidden} (or nothing): every other Pokémon this daycare holds for the player comes
	 * back to the party (the PC when it is full), without experience (it never stayed in the hidden game's daycare). One
	 * already in the party or PC (a stale entry after a crash) only loses its entry. The Pokémon is put back and saved
	 * before its entry goes, so a crash in between leaves it in both, never in neither. Returns how many came back.
	 */
	public static int reconcile(ServerPlayer player, @Nullable UUID hidden) {
		return reconcile(player, null, hidden == null ? Set.of() : Set.of(hidden));
	}

	/**
	 * {@link #reconcile(ServerPlayer, UUID)} for a region's daycare (MG3; {@code null}: the legacy region), whose hidden
	 * daycare holds every uid in {@code hidden} (Gen 3's holds two).
	 */
	public static int reconcile(ServerPlayer player, @Nullable String region, Set<UUID> hidden) {
		DaycareStore store = store(player, region);
		if (store == null || store.size() == 0) {
			return 0;
		}
		List<UUID> stray = store.uids();
		stray.removeAll(hidden);
		if (stray.isEmpty()) {
			return 0;
		}
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		PCStore pc = PlayerExtensionsKt.pc(player);
		List<UUID> back = new ArrayList<>();
		List<UUID> stale = new ArrayList<>();
		for (UUID uid : stray) {
			if (party.get(uid) != null || pc.get(uid) != null) {
				// A crash left it in both: the party's copy is the one; only the entry goes.
				stale.add(uid);
				continue;
			}
			Pokemon pokemon;
			try {
				pokemon = Pokemon.Companion.loadFromJSON(player.registryAccess(), store.get(uid));
			} catch (RuntimeException e) {
				KantoBridge.LOGGER.warn("daycare: Cobblemon couldn't read the Pokémon ({})", e.getClass().getName());
				warn("reconcile_unreadable");
				continue;
			}
			// The party, or the PC when it is full.
			if (!party.add(pokemon)) {
				warn("reconcile_no_room");
				continue;
			}
			back.add(uid);
		}
		if (back.isEmpty() && stale.isEmpty()) {
			return 0;
		}
		if (!back.isEmpty()) {
			// In the party or PC on disk before the daycare lets go of them.
			SaveService.savePokemon(player);
		}
		for (UUID uid : back) {
			store.remove(uid);
			// A later withdrawal of it (the hidden game's older save still boards it) is a repeat.
			WITHDRAWN.computeIfAbsent(player.getUUID(), key -> new HashSet<>()).add(uid);
		}
		for (UUID uid : stale) {
			store.remove(uid);
			warn("reconcile_stale_entry");
		}
		if (!write(player, region, store)) {
			// What came back is back; a later reconcile or withdrawal finds it in the party and only drops the entry.
			warn("reconcile_io");
		}
		PartyService.markDirty(player);
		if (!back.isEmpty()) {
			long count = RETURNED.addAndGet(back.size());
			KantoBridge.LOGGER.info("daycare: {} Pokémon the hidden game doesn't board came back to the party; {} so far", back.size(), count);
		}
		return back.size();
	}

	/** The uids the player's legacy-region daycare holds (tests, diagnostics); empty when its file can't be read. */
	public static Set<UUID> held(ServerPlayer player) {
		return held(player, null);
	}

	/** The uids a region's daycare holds for the player (MG3; {@code null}: the legacy region). */
	public static Set<UUID> held(ServerPlayer player, @Nullable String region) {
		DaycareStore store = store(player, region);
		return store == null ? Set.of() : Set.copyOf(store.uids());
	}

	/** The player's daycare file for the legacy region. */
	public static Path file(MinecraftServer server, UUID player) {
		return server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER).resolve(FOLDER).resolve(player + ".json");
	}

	/**
	 * The player's daycare file for a region (MG3): the legacy region's at {@code kantobridge/daycare/}, another's at
	 * {@code kantobridge/regions/<id>/daycare/}.
	 */
	public static Path file(MinecraftServer server, @Nullable String region, UUID player) {
		if (Regions.isLegacy(server, region)) {
			return file(server, player);
		}
		return server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER).resolve(RegionRecord.DIR).resolve(region).resolve(FOLDER)
				.resolve(player + ".json");
	}

	private static String key(@Nullable String region, UUID player) {
		return (region == null ? "" : region) + "/" + player;
	}

	/** Forgets the daycares read so far (tests: the next access reads the files again, as after a restart). */
	public static void forget(MinecraftServer server) {
		STORES.remove(server);
		WITHDRAWN.clear();
	}

	private static @Nullable DaycareStore store(ServerPlayer player, @Nullable String region) {
		// the legacy region's daycare is the one every world had: one key for null and for its id
		String regionKey = Regions.isLegacy(player.server, region) ? null : region;
		Map<String, DaycareStore> stores = STORES.computeIfAbsent(player.server, server -> new HashMap<>());
		DaycareStore store = stores.get(key(regionKey, player.getUUID()));
		if (store != null) {
			return store;
		}
		try {
			DaycareStore.Loaded loaded = DaycareStore.load(file(player.server, regionKey, player.getUUID()));
			if (loaded.origin() == DaycareStore.Origin.REPLACED) {
				warn("store_unreadable");
				KantoBridge.LOGGER.warn("daycare: a player's daycare file was unreadable; moved aside");
			}
			stores.put(key(regionKey, player.getUUID()), loaded.store());
			return loaded.store();
		} catch (IOException e) {
			warn("store_io");
			return null;
		}
	}

	private static boolean write(ServerPlayer player, @Nullable String region, DaycareStore store) {
		try {
			store.write(file(player.server, Regions.isLegacy(player.server, region) ? null : region, player.getUUID()));
			return true;
		} catch (IOException e) {
			KantoBridge.LOGGER.warn("daycare: the daycare file can't be written ({})", e.getClass().getSimpleName());
			return false;
		}
	}

	private static void reject(ServerPlayer player, Envelope message, String what, DataException e) {
		KantoBridge.LOGGER.debug("daycare: malformed {} at {} ({})", what, e.path(), e.problem());
		warn(what + "_bad_message");
		RelayService.get().sendError(player, "bad_message", message.seq());
	}

	// ---- views (tests, diagnostics) ----

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("daycare: {} ({} so far)", code, count);
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

	public static long deposits() {
		return DEPOSITS.get();
	}

	public static long withdrawals() {
		return WITHDRAWALS.get();
	}

	/** Repeated deposits and withdrawals that changed nothing. */
	public static long repeats() {
		return REPEATS.get();
	}

	/** Pokémon the reconcile brought back from the daycare. */
	public static long returned() {
		return RETURNED.get();
	}
}
