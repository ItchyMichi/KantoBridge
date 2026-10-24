package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent;
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionTestedEvent;
import com.cobblemon.mod.common.api.moves.BenchedMove;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveSet;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource;
import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution;
import com.cobblemon.mod.common.trade.PlayerTradeParticipant;
import com.cobblemon.mod.common.trade.TradeParticipant;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.server.RegionPresenceService;
import dev.kantobridge.server.SavePairService;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.RegionRecord;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * The player's Pokémon between Cobblemon and the hidden game (docs/PLAN.md §2.4 flow 11, §4.4 {@code PartyService}).
 *
 * <ul>
 * <li><b>{@code party.sync}</b> (S→C, relayed to gen1-bridge): the party and the PC's count, sent once Cobblemon has
 * synchronised the player's data, then whenever the party or the PC changes, at most every
 * {@value #SYNC_EVERY_TICKS} ticks, only when the content changed, and not during a battle; one always goes out after
 * each battle (catches included). A gift carries its {@code give} id, so gen1-bridge matches it exactly.</li>
 * <li><b>{@code party.give}</b>: the hidden game gives a Pokémon; it goes into the party, or the PC when the party is
 * full ({@code PlayerPartyStore.add}), with exact moves, IVs from DVs when given, its nickname, and an NPC original
 * trainer for trades (others belong to the player). gen1-bridge sends a gift again after every snapshot until a
 * {@code party.sync} shows it, so a {@code give} id is applied once: the Pokémon keeps it in its persistent data, which
 * also holds across server restarts.</li>
 * <li><b>{@code party.heal}</b> heals the party; <b>{@code party.rename}</b> is the Name Rater (P16), and renames from
 * Cobblemon's own screens are cancelled; <b>{@code party.take}</b> removes a Pokémon traded away (reason
 * {@code daycare} boards it at the daycare instead: {@link DaycareService}). A native catch (the Safari Zone) arrives as
 * a gift like any other and belongs to the player; a trade keeps its NPC trainer's name and its nickname.</li>
 * <li><b>{@code party.item}</b>: an item the hidden game used on a Pokémon. A stone evolves it at once (P14:
 * {@code forceEvolve} of the evolution that takes that stone), a TM/HM teaches its move into the given slot or a free one
 * (the move it replaces stays re-learnable, P13), Rare Candy gives the experience to the next level (Cobblemon's level-up
 * evolution prompt follows, P14).</li>
 * <li>Evolutions into species beyond the owner's game are blocked (P14; MG7: the followed region's compat ceiling, Gen 1's
 * 151 when there is none).</li>
 * <li><b>Player trades</b> (DESIGN §8, M9) run on Cobblemon's own trade; both players' {@code party.sync} (and
 * {@code dex.sync}, from Cobblemon's Pokédex events) follow from the stores' change observers, debounced as ever, and a
 * trade evolution is Cobblemon's own accept prompt for the receiver. A {@code give} id belongs to the hidden game that
 * gave the Pokémon, so a Pokémon that changes hands loses it: the receiver's gen1-bridge must never match it against
 * one of its own gifts.</li>
 * </ul>
 *
 * M10: gifts and takes count as the save pair's operations ({@link SavePairService}): a repeated gift (its id already on a
 * Pokémon) or a take of a Pokémon no longer there is a repeat. Logs carry codes and counts only, never names. Server
 * thread only.
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
		/** The player was in a battle: one sync goes out once it is over. */
		boolean inBattle;

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
	private static final AtomicLong ITEMS_USED = new AtomicLong();
	private static final AtomicLong TRADES = new AtomicLong();
	/** Rare Candy's experience source: a side mod. */
	static final String EXPERIENCE_SOURCE = "kantobridge";

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
		relay.register(MsgTypes.PARTY_ITEM, PartyService::onItem);
		// FOREIGN_POKEMON_PLAN §4.1, Q16 (FP2): a SWITCH in the hidden party menu
		relay.register(MsgTypes.PARTY_ORDER, PartyService::onOrder);
		// §4.3, §4.5 (FP4): effects outside items, tutors, the Deleter and the Reminder
		relay.register(MsgTypes.PARTY_CHANGE, PartyService::onChange);
		relay.register(MsgTypes.PARTY_LEARN, PartyService::onLearn);
		relay.register(MsgTypes.PARTY_FORGET, PartyService::onForget);
		// P16: nicknames change only through the Name Rater (and the catch or gift prompt), never Cobblemon's screens.
		CobblemonEvents.POKEMON_NICKNAMED.subscribe(Priority.HIGHEST, event -> {
			event.cancel();
		});
		CobblemonEvents.EVOLUTION_TESTED.subscribe(Priority.LOWEST, PartyService::filterEvolution);
		CobblemonEvents.TRADE_EVENT_POST.subscribe(Priority.NORMAL, event -> {
			onTraded(event);
		});
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
			ServerPlayer player = server.getPlayerList().getPlayer(watch.player);
			if (player == null) {
				continue;
			}
			// HP and PP change every turn of a battle; the hidden game needs the state after it.
			if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
				watch.inBattle = true;
				continue;
			}
			if (watch.inBattle) {
				// The battle is over: one sync goes out even if nothing changed (PROTOCOL.md "Party rules").
				watch.inBattle = false;
				watch.dirty = true;
				watch.lastJson = null;
			}
			if (!watch.dirty || now - watch.lastSent < SYNC_EVERY_TICKS) {
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
		return PartySync.of(PlayerExtensionsKt.party(player), PlayerExtensionsKt.pc(player), player);
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
		boolean repeat = given(player, give.give());
		SavePairService.operation(player, "give", give.give(), repeat);
		if (repeat) {
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
		// {} apart from MG3's optional region tag
		if (message.data().keySet().stream().anyMatch(key -> !key.equals("region"))) {
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
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "nick", "region"));
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
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "reason", "region"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			reason = reader.string("reason");
			// FP1: "quest" (Kenya's delivery, Shuckie going home) takes it away like a trade
			if (!reason.equals("trade") && !reason.equals("daycare") && !reason.equals("quest")) {
				throw new DataException(reader.path("reason"), "unknown reason");
			}
		} catch (DataException e) {
			reject(player, message, "take", e);
			return;
		}
		if (reason.equals("daycare")) {
			// P15: the daycare holds it on the server (gen1-bridge sends daycare.deposit; this is the same, once).
			DaycareService.deposit(player, uid);
			return;
		}
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		PCStore pc = PlayerExtensionsKt.pc(player);
		Pokemon inParty = party.get(uid);
		Pokemon inPc = inParty == null ? pc.get(uid) : null;
		// Gone already: gen1-bridge sent it again (or it's a mismatch, warned below).
		SavePairService.operation(player, "take", uid.toString(), inParty == null && inPc == null);
		Pokemon traded = inParty != null ? inParty : inPc;
		if (traded != null && traded.getEntity() != null) {
			// Sent out: back in its ball before it leaves for good.
			traded.recall();
		}
		boolean removed = inParty != null ? party.remove(inParty) : inPc != null && pc.remove(inPc);
		if (!removed) {
			warn("take_unknown_uid");
			return;
		}
		markDirty(player);
		KantoBridge.LOGGER.info("party: a Pokémon was {}", reason.equals("quest") ? "taken by a quest" : "traded away");
	}

	// ---- party.order (FP2) ----

	/**
	 * A SWITCH in the hidden party menu (R1: the lead is the same Pokémon in both): the named members move to the front in
	 * that order, the others keep theirs after them. Applying it twice changes nothing, so a repeat is harmless.
	 */
	static void onOrder(ServerPlayer player, Envelope message) {
		List<UUID> wanted = new java.util.ArrayList<>();
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uids", "region"));
			com.google.gson.JsonArray uids = reader.array("uids");
			if (uids.isEmpty() || uids.size() > PartySync.MAX_PARTY) {
				throw new DataException(reader.path("uids"), "needs 1-6 uids");
			}
			for (int i = 0; i < uids.size(); i++) {
				com.google.gson.JsonElement e = uids.get(i);
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || !Ids.UID.matcher(e.getAsString()).matches()) {
					throw new DataException(reader.path("uids") + "/" + i, "not a uid");
				}
				UUID uid = UUID.fromString(e.getAsString());
				if (wanted.contains(uid)) {
					throw new DataException(reader.path("uids") + "/" + i, "repeated");
				}
				wanted.add(uid);
			}
		} catch (DataException e) {
			reject(player, message, "order", e);
			return;
		}
		PlayerPartyStore party = PlayerExtensionsKt.party(player);
		List<UUID> slots = new java.util.ArrayList<>();
		for (int i = 0; i < party.size(); i++) {
			Pokemon pokemon = party.get(i);
			slots.add(pokemon == null ? null : pokemon.getUuid());
		}
		List<int[]> swaps = orderSwaps(slots, wanted);
		for (int[] swap : swaps) {
			party.swap(swap[0], swap[1]);
		}
		if (!swaps.isEmpty()) {
			markDirty(player);
			KantoBridge.LOGGER.info("party: reordered by the hidden game ({} swaps)", swaps.size());
		}
	}

	/**
	 * The swaps that put {@code wanted}'s members (those in the party) into the party's occupied slots first, in that
	 * order, the other members after them in their order. {@code slots} has a uid or null per slot. Plain Java.
	 */
	public static List<int[]> orderSwaps(List<UUID> slots, List<UUID> wanted) {
		List<Integer> occupied = new java.util.ArrayList<>();
		for (int i = 0; i < slots.size(); i++) {
			if (slots.get(i) != null) {
				occupied.add(i);
			}
		}
		List<UUID> desired = new java.util.ArrayList<>();
		for (UUID uid : wanted) {
			if (slots.contains(uid)) {
				desired.add(uid);
			}
		}
		for (int i : occupied) {
			if (!desired.contains(slots.get(i))) {
				desired.add(slots.get(i));
			}
		}
		List<UUID> now = new java.util.ArrayList<>(slots);
		List<int[]> swaps = new java.util.ArrayList<>();
		for (int k = 0; k < occupied.size(); k++) {
			int target = occupied.get(k);
			UUID want = desired.get(k);
			if (!want.equals(now.get(target))) {
				int from = now.indexOf(want);
				swaps.add(new int[] {target, from});
				java.util.Collections.swap(now, target, from);
			}
		}
		return swaps;
	}

	// ---- party.item ----

	/** FP4: the refusal reasons (protocol/messages/act.result.schema.json, kind item). */
	static final String UNKNOWN_UID = "unknown_uid";
	static final String NO_SLOT = "no_slot";
	static final String MOVE_KNOWN = "move_known";
	static final String NO_EVOLUTION = "no_evolution";
	static final String MAX_LEVEL = "max_level";
	static final String NO_EFFECT = "no_effect";
	static final String UNSUPPORTED = "unsupported";
	/** FP4: the item ids applied per player, newest last (an id is applied once; gen1-bridge re-sends until a sync). */
	static final int ITEM_IDS_KEPT = 64;
	private static final Map<UUID, java.util.LinkedHashSet<String>> ITEM_IDS = new ConcurrentHashMap<>();
	private static final AtomicLong ITEMS_REPEATED = new AtomicLong();
	private static final AtomicLong ITEMS_REFUSED = new AtomicLong();

	static void onItem(ServerPlayer player, Envelope message) {
		PartyItem item;
		try {
			item = PartyItem.read(message.data());
		} catch (DataException e) {
			reject(player, message, "item", e);
			return;
		}
		if (item.id() != null && !firstItemId(player.getUUID(), item.id())) {
			ITEMS_REPEATED.incrementAndGet();
			return;
		}
		String region = dev.kantobridge.server.Regions.tagOf(message.data());
		Pokemon pokemon = find(player, item.uid());
		String refusal;
		if (pokemon == null) {
			refusal = UNKNOWN_UID;
		} else {
			refusal = switch (item.kind()) {
				case STONE -> evolveWithStone(player, pokemon, PartyItem.STONES.get(item.item())) ? null : NO_EVOLUTION;
				case MACHINE -> teachOrWhy(pokemon, item.move(), item.slot(), item.forget());
				case RARE_CANDY -> rareCandy(player, pokemon) ? null : MAX_LEVEL;
				case MEDICINE, PP, VITAMIN -> cobblemonItem(player, pokemon, item, itemFamily(player, region));
				case OTHER -> UNSUPPORTED;
			};
		}
		if (refusal == null) {
			ITEMS_USED.incrementAndGet();
			markDirty(player);
			KantoBridge.LOGGER.info("party: applied a {} from the bag", item.kind().name().toLowerCase(java.util.Locale.ROOT));
			return;
		}
		warn("item_" + refusal);
		ITEMS_REFUSED.incrementAndGet();
		// §4.4: the next sync shows the hidden record what Cobblemon kept; an id'd item hears why
		markDirty(player);
		if (item.id() != null) {
			JsonObject data = new JsonObject();
			data.addProperty("ok", false);
			data.addProperty("kind", "item");
			data.addProperty("reason", refusal);
			data.addProperty("id", item.id());
			dev.kantobridge.server.Regions.tag(data, player.server, region);
			RelayService.get().send(player, MsgTypes.ACT_RESULT, data, message.seq());
		}
	}

	// ---- party.change, party.learn, party.forget (FP4) ----

	private static final AtomicLong CHANGES = new AtomicLong();
	private static final AtomicLong LEARNED = new AtomicLong();
	private static final AtomicLong FORGOTTEN = new AtomicLong();
	/** The protocol's statuses (party.change.schema.json) and Cobblemon's; {@code none} clears. */
	static final Map<String, com.cobblemon.mod.common.pokemon.status.PersistentStatus> STATUSES = Map.of(
			"slp", com.cobblemon.mod.common.api.pokemon.status.Statuses.SLEEP,
			"psn", com.cobblemon.mod.common.api.pokemon.status.Statuses.POISON,
			"brn", com.cobblemon.mod.common.api.pokemon.status.Statuses.BURN,
			"frz", com.cobblemon.mod.common.api.pokemon.status.Statuses.FROZEN,
			"par", com.cobblemon.mod.common.api.pokemon.status.Statuses.PARALYSIS);

	/** The Pokémon a party.change/learn/forget names once its id is new, or null (counted, a sync forced when unknown). */
	private static @Nullable Pokemon effectTarget(ServerPlayer player, UUID uid, String id, String what) {
		if (!firstItemId(player.getUUID(), id)) {
			ITEMS_REPEATED.incrementAndGet();
			return null;
		}
		Pokemon pokemon = find(player, uid);
		if (pokemon == null) {
			warn(what + "_unknown_uid");
			markDirty(player);
		}
		return pokemon;
	}

	/**
	 * §4.3 (Q17): an effect the hidden game had on a Pokémon outside items (overworld poison, the haircut brothers, a
	 * massage): Cobblemon takes the value (HP clamped to its own maximum, the status, the friendship) and the next sync
	 * shows it. An id applies once.
	 */
	static void onChange(ServerPlayer player, Envelope message) {
		UUID uid;
		String id;
		Integer hp;
		String status;
		Integer friendship;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "id", "hp", "status", "friendship", "region"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			id = reader.string("id", Ids.ID);
			hp = reader.has("hp") ? reader.integer("hp", 0, 9999) : null;
			status = reader.has("status") ? reader.string("status") : null;
			if (status != null && !status.equals("none") && !STATUSES.containsKey(status)) {
				throw new DataException(reader.path("status"), "unknown status");
			}
			friendship = reader.has("friendship") ? reader.integer("friendship", 0, 255) : null;
		} catch (DataException e) {
			reject(player, message, "change", e);
			return;
		}
		Pokemon pokemon = effectTarget(player, uid, id, "change");
		if (pokemon == null) {
			return;
		}
		if (hp != null) {
			pokemon.setCurrentHealth(Math.max(0, Math.min(hp, pokemon.getMaxHealth())));
		}
		if (status != null) {
			if (status.equals("none")) {
				pokemon.setStatus(null);
			} else {
				pokemon.applyStatus(STATUSES.get(status));
			}
		}
		if (friendship != null) {
			pokemon.setFriendship(friendship, true);
		}
		CHANGES.incrementAndGet();
		markDirty(player);
	}

	/**
	 * §4.5 (Q18): a Move Tutor or the Move Reminder taught a move in the hidden game: as a machine, into the slot of the
	 * move it replaced ({@code forget}) or a free one, the replaced move benched.
	 */
	static void onLearn(ServerPlayer player, Envelope message) {
		UUID uid;
		String id;
		int move;
		Integer forget;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "id", "move", "forget", "region"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			id = reader.string("id", Ids.ID);
			move = reader.integer("move", 1, Gen1Mon.MAX_MOVE_NUM);
			forget = reader.has("forget") ? reader.integer("forget", 1, Gen1Mon.MAX_MOVE_NUM) : null;
		} catch (DataException e) {
			reject(player, message, "learn", e);
			return;
		}
		Pokemon pokemon = effectTarget(player, uid, id, "learn");
		if (pokemon == null) {
			return;
		}
		String refusal = teachOrWhy(pokemon, move, null, forget);
		if (refusal != null) {
			warn("learn_" + refusal);
		} else {
			LEARNED.incrementAndGet();
		}
		markDirty(player);
	}

	/** §4.5 (Q18): the Move Deleter: the move goes to the bench (re-learnable from Cobblemon's summary); never the last one. */
	static void onForget(ServerPlayer player, Envelope message) {
		UUID uid;
		String id;
		int move;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("uid", "id", "move", "region"));
			uid = UUID.fromString(reader.string("uid", Ids.UID));
			id = reader.string("id", Ids.ID);
			move = reader.integer("move", 1, Gen1Mon.MAX_MOVE_NUM);
		} catch (DataException e) {
			reject(player, message, "forget", e);
			return;
		}
		Pokemon pokemon = effectTarget(player, uid, id, "forget");
		if (pokemon == null) {
			return;
		}
		MoveSet moves = pokemon.getMoveSet();
		int slot = -1;
		for (int i = 0; i < MoveSet.MOVE_COUNT && slot < 0; i++) {
			Move known = moves.get(i);
			if (known != null && known.getTemplate().getNum() == move) {
				slot = i;
			}
		}
		if (slot < 0 || moves.getMoves().size() <= 1) {
			warn(slot < 0 ? "forget_not_known" : "forget_last_move");
			markDirty(player);
			return;
		}
		Move gone = moves.get(slot);
		pokemon.getBenchedMoves().add(new BenchedMove(gone.getTemplate(), gone.getRaisedPpStages()));
		moves.setMove(slot, null);
		// the hidden game shifts the rest up; Cobblemon's slots follow
		for (int i = slot; i < MoveSet.MOVE_COUNT - 1; i++) {
			if (moves.get(i) == null && moves.get(i + 1) != null) {
				moves.swapMove(i, i + 1);
			}
		}
		FORGOTTEN.incrementAndGet();
		markDirty(player);
	}

	/** FP4: party.change, party.learn and party.forget applied. */
	public static long changesApplied() {
		return CHANGES.get();
	}

	public static long movesLearned() {
		return LEARNED.get();
	}

	public static long movesForgotten() {
		return FORGOTTEN.get();
	}

	/** True the first time a player's item id comes (FP4: an id is applied once); the newest {@link #ITEM_IDS_KEPT} are kept. */
	static boolean firstItemId(UUID player, String id) {
		java.util.LinkedHashSet<String> ids = ITEM_IDS.computeIfAbsent(player, key -> new java.util.LinkedHashSet<>());
		synchronized (ids) {
			if (!ids.add(id)) {
				return false;
			}
			if (ids.size() > ITEM_IDS_KEPT) {
				java.util.Iterator<String> it = ids.iterator();
				it.next();
				it.remove();
			}
			return true;
		}
	}

	/** The item symbols' family for a message from {@code region} (its record's; untagged: the player's region's), else Gen 1's. */
	static String itemFamily(ServerPlayer player, @Nullable String region) {
		dev.kantobridge.server.Regions regions = dev.kantobridge.server.Regions.of(player.server);
		RegionRecord record = regions == null ? null : regions.regionOf(player, region);
		return record == null ? "gen1" : record.family();
	}

	/**
	 * FP4 (§4.4): medicine, PP items and vitamins through Cobblemon's own item for the catalogue entry (its amounts, its
	 * EV and PP-stage rules; the mirror's item, else Cobblemon's item of the canonical id): an item that picks a move gets the member's move numbered {@code move}; a revive (not a
	 * Pokémon-selecting item in Cobblemon) brings a fainted Pokémon back to half its HP, a Max Revive to all of it.
	 * Returns null when it was applied, else the refusal's reason.
	 */
	static @Nullable String cobblemonItem(ServerPlayer player, Pokemon pokemon, PartyItem item, String family) {
		String canonical = dev.kantobridge.world.Items.canonicalOf(family, item.item());
		// the mirror's item; else (vitamins, PP Up, the drinks: never mirrored) Cobblemon's item of the same id
		String path = dev.kantobridge.world.Items.cobblemonPath(canonical);
		if (path == null && canonical != null) {
			path = canonical;
		}
		Item cobblemon = path == null ? Items.AIR
				: BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(GenOneIds.NAMESPACE, path));
		if (cobblemon == Items.AIR) {
			return UNSUPPORTED;
		}
		ItemStack stack = new ItemStack(cobblemon);
		if (cobblemon instanceof com.cobblemon.mod.common.item.interactive.ReviveItem revive) {
			if (!pokemon.isFainted()) {
				return NO_EFFECT;
			}
			pokemon.setCurrentHealth(revive.getMax() ? pokemon.getMaxHealth() : Math.max(1, pokemon.getMaxHealth() / 2));
			return null;
		}
		if (cobblemon instanceof com.cobblemon.mod.common.api.item.PokemonAndMoveSelectingItem picking) {
			Move move = null;
			for (int i = 0; i < MoveSet.MOVE_COUNT && item.move() != null && move == null; i++) {
				Move known = pokemon.getMoveSet().get(i);
				if (known != null && known.getTemplate().getNum() == item.move()) {
					move = known;
				}
			}
			if (move == null || !picking.canUseOnPokemon(stack, pokemon) || !picking.canUseOnMove(stack, pokemon, move)) {
				return NO_EFFECT;
			}
			picking.applyToPokemon(player, stack, pokemon, move);
			return null;
		}
		if (cobblemon instanceof com.cobblemon.mod.common.api.item.PokemonSelectingItem selecting) {
			if (!selecting.canUseOnPokemon(stack, pokemon)) {
				return NO_EFFECT;
			}
			var result = selecting.applyToPokemon(player, stack, pokemon);
			return result != null && result.getResult().consumesAction() ? null : NO_EFFECT;
		}
		return UNSUPPORTED;
	}

	/** P14: the evolution that takes this stone happens at once ({@code forceEvolve}; Cobblemon's own path only queues). */
	static boolean evolveWithStone(ServerPlayer player, Pokemon pokemon, String stonePath) {
		Item stone = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(GenOneIds.NAMESPACE, stonePath));
		if (stone == Items.AIR) {
			warn("item_stone_missing");
			return false;
		}
		ItemInteractionEvolution.ItemInteractionContext context = new ItemInteractionEvolution.ItemInteractionContext(new ItemStack(stone),
				player.level());
		for (Evolution evolution : pokemon.getEvolutions()) {
			// test() posts EVOLUTION_TESTED, so evolutions beyond the owner's game stay blocked (P14).
			if (evolution instanceof ItemInteractionEvolution byItem && byItem.testContext(pokemon, context) && byItem.test(pokemon)) {
				byItem.forceEvolve(pokemon);
				return true;
			}
		}
		return false;
	}

	/** {@link #teachOrWhy}, as a yes or no. */
	static boolean teach(Pokemon pokemon, int number, @Nullable Integer slot, @Nullable Integer forget) {
		return teachOrWhy(pokemon, number, slot, forget) == null;
	}

	/**
	 * A TM or HM: the move goes into the slot holding {@code forget} (FP1: the move replaced, by number, since the hidden
	 * slot and Cobblemon's need not match), else {@code slot}, else the first free slot, with full PP; the move it
	 * replaces is benched, so it stays re-learnable from Cobblemon's summary (P13: nobody softlocks over an HM). Returns
	 * null when taught, else the refusal's reason.
	 */
	static @Nullable String teachOrWhy(Pokemon pokemon, int number, @Nullable Integer slot, @Nullable Integer forget) {
		MoveTemplate template = Gen1Mons.move(number);
		if (template == null) {
			warn("item_move_missing");
			return UNSUPPORTED;
		}
		MoveSet moves = pokemon.getMoveSet();
		for (Move known : moves.getMoves()) {
			if (known.getTemplate() == template) {
				// The hidden game refuses a move the Pokémon knows; it shouldn't send one.
				return MOVE_KNOWN;
			}
		}
		int target = slot == null ? -1 : slot;
		if (forget != null) {
			target = -1;
			for (int i = 0; i < MoveSet.MOVE_COUNT && target < 0; i++) {
				Move known = moves.get(i);
				if (known != null && known.getTemplate().getNum() == forget) {
					target = i;
				}
			}
		}
		if (target < 0 || moves.get(target) == null) {
			target = -1;
			for (int i = 0; i < MoveSet.MOVE_COUNT && target < 0; i++) {
				if (moves.get(i) == null) {
					target = i;
				}
			}
		}
		if (target < 0) {
			return NO_SLOT;
		}
		Move replaced = moves.get(target);
		if (replaced != null) {
			pokemon.getBenchedMoves().add(new BenchedMove(replaced.getTemplate(), replaced.getRaisedPpStages()));
		}
		pokemon.getBenchedMoves().remove(template);
		moves.setMove(target, template.create());
		return null;
	}

	/** Rare Candy: the experience to the next level, as Cobblemon's own candy gives it (with the player's level-up UI). */
	static boolean rareCandy(ServerPlayer player, Pokemon pokemon) {
		if (pokemon.getLevel() >= com.cobblemon.mod.common.Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel()) {
			return false;
		}
		pokemon.addExperienceWithPlayer(player, new SidemodExperienceSource(EXPERIENCE_SOURCE), pokemon.getExperienceToNextLevel());
		return true;
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

	// ---- player trades (M9) ----

	/** After a Cobblemon trade between two players: the traded Pokémon forget their gift ids; both parties sync. */
	static void onTraded(TradeEvent.Post event) {
		int cleared = 0;
		for (Pokemon pokemon : List.of(event.getTradeParticipant1Pokemon(), event.getTradeParticipant2Pokemon())) {
			if (pokemon.getPersistentData().contains(GIVE_KEY)) {
				pokemon.getPersistentData().remove(GIVE_KEY);
				// Stores save on change; the change also marks the new owner's party for its sync.
				pokemon.onChange(null);
				cleared++;
			}
		}
		for (TradeParticipant participant : List.of(event.getTradeParticipant1(), event.getTradeParticipant2())) {
			if (participant instanceof PlayerTradeParticipant player) {
				markDirty(player.getPlayer());
			}
		}
		long count = TRADES.incrementAndGet();
		KantoBridge.LOGGER.info("party: two players traded ({} gift ids cleared); {} trades so far", cleared, count);
	}

	// ---- evolutions (P14) ----

	static void filterEvolution(EvolutionTestedEvent event) {
		if (!event.getResult()) {
			return;
		}
		if (!evolutionAllowed(event.getEvolution(), event.getPokemon().getOwnerPlayer())) {
			event.setResult(false);
			EVOLUTIONS_BLOCKED.incrementAndGet();
		}
	}

	/**
	 * The evolution rule (P14; FOREIGN_POKEMON_PLAN Q9, FP2): a Pokémon may evolve into a species past the owner's followed
	 * region's ceiling unless that region's {@code evolveBeyond} is off (it is a visitor there afterwards). The sync's
	 * {@code stones} apply the same rule, so the hidden stone check and Cobblemon agree.
	 */
	static boolean evolutionAllowed(Evolution evolution, @Nullable ServerPlayer owner) {
		String target = evolution.getResult().getSpecies();
		Species species = target == null ? null : speciesById(target);
		RegionRecord followed = owner == null ? null : RegionPresenceService.followed(owner);
		return species == null || evolutionAllowed(species.getNationalPokedexNumber(), followed == null ? null : followed.rules().compat());
	}

	/** The rule for a result's dex under a compat profile: allowed when within the ceiling or when evolveBeyond is on (Q9). */
	public static boolean evolutionAllowed(int dex, @Nullable RegionRecord.Compat compat) {
		return compat == null || compat.evolvesBeyond() || dex <= compat.maxDex();
	}

	/** The highest dex a player's Pokémon may evolve into (MG7, MULTI_GAME_PLAN §4.9): their followed region's ceiling. */
	static int evolutionCeilingOf(@Nullable ServerPlayer owner) {
		RegionRecord followed = owner == null ? null : RegionPresenceService.followed(owner);
		return evolutionCeiling(followed == null ? null : followed.rules().compat());
	}

	/** A compat profile's evolution ceiling: its maxDex; Gen 1's 151 without one (no owner online, no region). */
	public static int evolutionCeiling(@Nullable RegionRecord.Compat compat) {
		return compat == null ? GenOneIds.MAX_DEX : compat.maxDex();
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

	/** {@code party.item}s applied. */
	public static long itemsUsed() {
		return ITEMS_USED.get();
	}

	/** FP4: {@code party.item}s ignored because their id was applied already. */
	public static long itemsRepeated() {
		return ITEMS_REPEATED.get();
	}

	/** FP4: {@code party.item}s Cobblemon refused (each counted under {@code item_<reason>} too). */
	public static long itemsRefused() {
		return ITEMS_REFUSED.get();
	}

	/** Cobblemon trades between players seen. */
	public static long trades() {
		return TRADES.get();
	}

	/** True while the player's party is followed (after Cobblemon synchronised their data). */
	public static boolean watching(ServerPlayer player) {
		Watch watch = WATCHES.get(player.getUUID());
		return watch != null && watch.party != null;
	}
}
