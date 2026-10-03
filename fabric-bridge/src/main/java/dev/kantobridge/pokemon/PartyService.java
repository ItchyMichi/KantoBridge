package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionTestedEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The player's Pokémon between Cobblemon and the hidden game (docs/PLAN.md §2.4 flow 11, §4.4 {@code PartyService}).
 *
 * <ul>
 * <li><b>{@code party.sync}</b> (S→C, relayed to gen1-bridge): the party and the PC's count, sent once Cobblemon has
 * synchronised the player's data, then whenever the party or the PC changes, at most every
 * {@value #SYNC_EVERY_TICKS} ticks, only when the content changed, and not during a battle (one goes out after it).</li>
 * <li><b>{@code party.give}</b>: the hidden game gives a Pokémon; it goes into the party, or the PC when the party is
 * full ({@code PlayerPartyStore.add}), with exact moves, IVs from DVs when given, its nickname, and an NPC original
 * trainer for trades (others belong to the player). gen1-bridge sends a gift again after every snapshot until a
 * {@code party.sync} shows it, so a {@code give} id is applied once: the Pokémon keeps it in its persistent data, which
 * also holds across server restarts.</li>
 * <li><b>{@code party.heal}</b> heals the party; <b>{@code party.rename}</b> is the Name Rater (P16), and renames from
 * Cobblemon's own screens are cancelled; <b>{@code party.take}</b> removes a Pokémon traded away (daycare is M8's).
 * {@code party.item} arrives in M6.</li>
 * <li>Evolutions into species beyond 151 are blocked (P14).</li>
 * </ul>
 *
 * Logs carry codes and counts only, never names. Server thread only.
 */
public final class PartyService {
	static final int SYNC_EVERY_TICKS = 10;
	/** A given Pokémon's persistent-data key holding the {@code give} id it came with. */
	public static final String GIVE_KEY = "kantobridge_give";

	/** One player's watch on their party and PC. */
	private static final class Watch {
		final UUID player;
		@Nullable ObservableSubscription<Unit> party;
		@Nullable ObservableSubscription<Unit> pc;
		boolean dirty = true;
		long lastSent = Long.MIN_VALUE / 2;
		@Nullable String lastJson;

		Watch(UUID player) {
			this.player = player;
		}

		void unsubscribe() {
			if (this.party != null) {
				this.party.unsubscribe();
				this.party = null;
			}
			if (this.pc != null) {
				this.pc.unsubscribe();
				this.pc = null;
			}
		}
	}

	private static final Map<UUID, Watch> WATCHES = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong GIVES = new AtomicLong();
	private static final AtomicLong REPEATED_GIVES = new AtomicLong();
	private static final AtomicLong EVOLUTIONS_BLOCKED = new AtomicLong();

	private PartyService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		CobblemonEvents.DATA_SYNCHRONIZED.subscribe(Priority.NORMAL, player -> {
			watch(player);
		});
		RelayService relay = RelayService.get();
		relay.register(MsgTypes.PARTY_GIVE, PartyService::onGive);
		relay.register(MsgTypes.PARTY_HEAL, PartyService::onHeal);
		relay.register(MsgTypes.PARTY_RENAME, PartyService::onRename);
		relay.register(MsgTypes.PARTY_TAKE, PartyService::onTake);
		relay.register(MsgTypes.PARTY_ITEM, (player, message) -> warn("item_unsupported"));
		// P16: nicknames change only through the Name Rater (and the catch or gift prompt), never Cobblemon's screens.
		CobblemonEvents.POKEMON_NICKNAMED.subscribe(Priority.HIGHEST, event -> {
			event.cancel();
		});
		CobblemonEvents.EVOLUTION_TESTED.subscribe(Priority.LOWEST, PartyService::filterEvolution);
		ServerTickEvents.END_SERVER_TICK.register(PartyService::tick);
		ServerPlayerEvents.LEAVE.register(PartyService::unwatch);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WATCHES.values().forEach(Watch::unsubscribe);
			WATCHES.clear();
		});
	}

	// ---- party.sync ----

	/** Starts following a player's party and PC (on Cobblemon's data sync); the first sync goes out on the next tick. */
	public static void watch(ServerPlayer player) {
		Watch watch = WATCHES.computeIfAbsent(player.getUUID(), Watch::new);
		watch.unsubscribe();
		watch.dirty = true;
		watch.lastJson = null;
		// The observables say only that something changed; the tick builds the message and compares it.
		watch.party = PlayerExtensionsKt.party(player).getAnyChangeObservable().subscribe(Priority.NORMAL, unit -> {
			watch.dirty = true;
		});
		watch.pc = PlayerExtensionsKt.pc(player).getAnyChangeObservable().subscribe(Priority.NORMAL, unit -> {
			watch.dirty = true;
		});
	}

	private static void unwatch(ServerPlayer player) {
		Watch watch = WATCHES.remove(player.getUUID());
		if (watch != null) {
			watch.unsubscribe();
		}
	}

	/** Asks for a sync of this player's party at the next chance (after a change made here). */
	public static void markDirty(ServerPlayer player) {
		Watch watch = WATCHES.get(player.getUUID());
		if (watch != null) {
			watch.dirty = true;
		}
	}

	private static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		for (Watch watch : WATCHES.values()) {
			if (!watch.dirty || now - watch.lastSent < SYNC_EVERY_TICKS) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(watch.player);
			if (player == null) {
				continue;
			}
			// HP and PP change every turn of a battle; the hidden game needs the state after it.
			if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
				continue;
			}
			sync(player, watch, now);
		}
	}

	private static void sync(ServerPlayer player, Watch watch, long now) {
		watch.dirty = false;
		JsonObject data = snapshot(player);
		String json = ProtoJson.write(data);
		if (json.equals(watch.lastJson)) {
			return;
		}
		if (RelayService.get().send(player, MsgTypes.PARTY_SYNC, data, null)) {
			watch.lastJson = json;
			watch.lastSent = now;
			KantoBridge.LOGGER.debug("party: synced {} Pokémon", data.getAsJsonArray("party").size());
		}
	}

	/** The {@code party.sync} body for a player right now. */
	public static JsonObject snapshot(ServerPlayer player) {
		return PartySync.of(PlayerExtensionsKt.party(player), PlayerExtensionsKt.pc(player));
	}

	// ---- requests from the hidden game ----

	static void onGive(ServerPlayer player, Envelope message) {
		PartyGive give;
		try {
			give = PartyGive.read(message.data());
		} catch (DataException e) {
			reject(player, message, "give", e);
			return;
		}
		if (given(player, give.give())) {
			// Expected: gen1-bridge repeats a gift until a party.sync shows it.
			REPEATED_GIVES.incrementAndGet();
			KantoBridge.LOGGER.debug("party: ignored a repeated give");
			return;
		}
		Pokemon pokemon;
		try {
			pokemon = Gen1Mons.gift(give.mon());
		} catch (Gen1Mons.Missing e) {
			warn("give_missing_" + e.code());
			return;
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("party: Cobblemon failed to create a Pokémon ({})", e.getClass().getName());
			warn("give_failed");
			return;
		}
		if (give.nick() != null && !give.nick().isEmpty()) {
			pokemon.setNickname(Component.literal(give.nick()));
		}
		if (give.source() == PartyGive.Source.TRADE && give.ot() != null && !give.ot().isBlank()) {
			// A traded Pokémon keeps its NPC trainer (and Cobblemon gives it the traded-Pokémon XP bonus, as Gen 1 does).
			pokemon.setOriginalTrainer(give.ot());
		}
		pokemon.getPersistentData().putString(GIVE_KEY, give.give());
		// The party, or the PC when the party is full; it sets the player as original trainer when none is set.
		if (!PlayerExtensionsKt.party(player).add(pokemon)) {
			warn("give_no_room");
			return;
		}
		markDirty(player);
		long count = GIVES.incrementAndGet();
		KantoBridge.LOGGER.info("party: gave a Pokémon ({}); {} so far", give.source().id(), count);
	}

	static void onHeal(ServerPlayer player, Envelope message) {
		if (!message.data().keySet().isEmpty()) {
			reject(player, message, "heal", null);
			return;
		}
		PlayerExtensionsKt.party(player).heal();
		markDirty(player);
	}

	static void onRename(ServerPlayer player, Envelope message) {
		UUID uid;
		String nick;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "nick"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			nick = PartyGive.name(reader, "nick");
		} catch (DataException e) {
			reject(player, message, "rename", e);
			return;
		}
		Pokemon pokemon = find(player, uid);
		if (pokemon == null) {
			warn("rename_unknown_uid");
			return;
		}
		// The server-side setter posts no event, so the cancellation of player renames doesn't apply.
		pokemon.setNickname(nick.isEmpty() ? null : Component.literal(nick));
		markDirty(player);
	}

	static void onTake(ServerPlayer player, Envelope message) {
		UUID uid;
		String reason;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "reason"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			reason = reader.string("reason");
			if (!reason.equals("trade") && !reason.equals("daycare")) {
				throw new DataException(reader.path("reason"), "unknown reason");
			}
		} catch (DataException e) {
			reject(player, message, "take", e);
			return;
		}
		if (reason.equals("daycare")) {
			// P15: the daycare holds Pokémon on the server; that comes with M8.
			warn("take_daycare_unsupported");
			return;
		}
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		PCStore pc = PlayerExtensionsKt.pc(player);
		Pokemon inParty = party.get(uid);
		Pokemon inPc = inParty == null ? pc.get(uid) : null;
		boolean removed = inParty != null ? party.remove(inParty) : inPc != null && pc.remove(inPc);
		if (!removed) {
			warn("take_unknown_uid");
			return;
		}
		markDirty(player);
		KantoBridge.LOGGER.info("party: a Pokémon was traded away");
	}

	/** True when a Pokémon in the party or the PC came with this {@code give} id. */
	static boolean given(ServerPlayer player, String give) {
		for (Pokemon pokemon : PlayerExtensionsKt.party(player)) {
			if (give.equals(pokemon.getPersistentData().getString(GIVE_KEY))) {
				return true;
			}
		}
		for (Pokemon pokemon : PlayerExtensionsKt.pc(player)) {
			if (give.equals(pokemon.getPersistentData().getString(GIVE_KEY))) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable Pokemon find(ServerPlayer player, UUID uid) {
		Pokemon pokemon = PlayerExtensionsKt.party(player).get(uid);
		return pokemon != null ? pokemon : PlayerExtensionsKt.pc(player).get(uid);
	}

	private static void reject(ServerPlayer player, Envelope message, String what, @Nullable DataException e) {
		if (e != null) {
			KantoBridge.LOGGER.debug("party: malformed {} at {} ({})", what, e.path(), e.problem());
		}
		warn(what + "_bad_message");
		RelayService.get().sendError(player, "bad_message", message.seq());
	}

	// ---- evolutions (P14) ----

	static void filterEvolution(EvolutionTestedEvent event) {
		if (!event.getResult()) {
			return;
		}
		String target = event.getEvolution().getResult().getSpecies();
		Species species = target == null ? null : speciesById(target);
		if (species != null && species.getNationalPokedexNumber() > GenOneIds.MAX_DEX) {
			event.setResult(false);
			EVOLUTIONS_BLOCKED.incrementAndGet();
		}
	}

	private static @Nullable Species speciesById(String id) {
		String lower = id.toLowerCase(java.util.Locale.ROOT);
		ResourceLocation location = ResourceLocation.tryParse(lower.contains(":") ? lower : GenOneIds.NAMESPACE + ":" + lower);
		return location == null ? null : PokemonSpecies.getByIdentifier(location);
	}

	// ---- views (tests, diagnostics) ----

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("party: {} ({} so far)", code, count);
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	/** Gifts ignored because their {@code give} id was applied already. */
	public static long repeatedGives() {
		return REPEATED_GIVES.get();
	}

	public static long evolutionsBlocked() {
		return EVOLUTIONS_BLOCKED.get();
	}

	/** True while the player's party is followed (after Cobblemon synchronised their data). */
	public static boolean watching(ServerPlayer player) {
		Watch watch = WATCHES.get(player.getUUID());
		return watch != null && watch.party != null;
	}
}
