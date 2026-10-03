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
import dev.kantobridge.server.SaveService;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 */
public final class DaycareService {
	public static final String FOLDER = "daycare";
	/** Daycare experience's source, for Cobblemon's events: a side mod. */
	static final String EXPERIENCE_SOURCE = "kantobridge_daycare";

	private static final Map<MinecraftServer, Map<UUID, DaycareStore>> STORES = Collections.synchronizedMap(new WeakHashMap<>());
	/** Per player: the uids withdrawn since the server started, so a repeated withdrawal is told from a mismatch. */
	private static final Map<UUID, Set<UUID>> WITHDRAWN = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong DEPOSITS = new AtomicLong();
	private static final AtomicLong WITHDRAWALS = new AtomicLong();
	private static final AtomicLong REPEATS = new AtomicLong();

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
		try {
			uid = UUID.fromString(DataReader.of(message.data(), "").allowOnly(Set.of("uid")).string("uid", Ids.UID));
		} catch (DataException e) {
			reject(player, message, "deposit", e);
			return;
		}
		deposit(player, uid);
	}

	static void onWithdraw(ServerPlayer player, Envelope message) {
		UUID uid;
		int steps;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "steps"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			steps = reader.integer("steps", 0, Integer.MAX_VALUE);
		} catch (DataException e) {
			reject(player, message, "withdraw", e);
			return;
		}
		withdraw(player, uid, steps);
	}

	// ---- the daycare ----

	/** Boards a party (or PC) Pokémon at the daycare; true when it is there afterwards. */
	public static boolean deposit(ServerPlayer player, UUID uid) {
		DaycareStore store = store(player);
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
		if (!write(player, store)) {
			store.remove(uid);
			warn("deposit_io");
			return false;
		}
		boolean removed = fromPc ? pc.remove(pokemon) : party.remove(pokemon);
		if (!removed) {
			store.remove(uid);
			write(player, store);
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

	/** Brings a Pokémon back from the daycare with {@code steps}' worth of experience; true when it is back. */
	public static boolean withdraw(ServerPlayer player, UUID uid, int steps) {
		DaycareStore store = store(player);
		if (store == null) {
			return false;
		}
		JsonObject data = store.get(uid);
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		Pokemon existing = party.get(uid);
		if (existing == null) {
			existing = PlayerExtensionsKt.pc(player).get(uid);
		}
		if (data == null) {
			if (existing != null && WITHDRAWN.getOrDefault(player.getUUID(), Set.of()).contains(uid)) {
				REPEATS.incrementAndGet();
				return true;
			}
			// Never boarded here (or long since back): the hidden game and Minecraft disagree.
			warn(existing != null ? "withdraw_not_deposited" : "withdraw_unknown_uid");
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
		if (!write(player, store)) {
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

	/** The uids the player's daycare holds (tests, diagnostics); empty when its file can't be read. */
	public static Set<UUID> held(ServerPlayer player) {
		DaycareStore store = store(player);
		return store == null ? Set.of() : Set.copyOf(store.uids());
	}

	/** The player's daycare file. */
	public static Path file(MinecraftServer server, UUID player) {
		return server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER).resolve(FOLDER).resolve(player + ".json");
	}

	/** Forgets the daycares read so far (tests: the next access reads the files again, as after a restart). */
	public static void forget(MinecraftServer server) {
		STORES.remove(server);
		WITHDRAWN.clear();
	}

	private static @Nullable DaycareStore store(ServerPlayer player) {
		Map<UUID, DaycareStore> stores = STORES.computeIfAbsent(player.server, key -> new HashMap<>());
		DaycareStore store = stores.get(player.getUUID());
		if (store != null) {
			return store;
		}
		try {
			DaycareStore.Loaded loaded = DaycareStore.load(file(player.server, player.getUUID()));
			if (loaded.origin() == DaycareStore.Origin.REPLACED) {
				warn("store_unreadable");
				KantoBridge.LOGGER.warn("daycare: a player's daycare file was unreadable; moved aside");
			}
			stores.put(player.getUUID(), loaded.store());
			return loaded.store();
		} catch (IOException e) {
			warn("store_io");
			return null;
		}
	}

	private static boolean write(ServerPlayer player, DaycareStore store) {
		try {
			store.write(file(player.server, player.getUUID()));
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
}
