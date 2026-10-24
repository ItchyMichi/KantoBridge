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

	// ---- party.item ----

	static void onItem(ServerPlayer player, Envelope message) {
		PartyItem item;
		try {
			item = PartyItem.read(message.data());
		} catch (DataException e) {
			reject(player, message, "item", e);
			return;
		}
		Pokemon pokemon = find(player, item.uid());
		if (pokemon == null) {
			warn("item_unknown_uid");
			return;
		}
		boolean applied = switch (item.kind()) {
			case STONE -> evolveWithStone(player, pokemon, PartyItem.STONES.get(item.item()));
			case MACHINE -> teach(pokemon, item.move(), item.slot(), item.forget());
			case RARE_CANDY -> rareCandy(player, pokemon);
			case OTHER -> {
				warn("item_unsupported");
				yield false;
			}
		};
		if (applied) {
			ITEMS_USED.incrementAndGet();
			markDirty(player);
			KantoBridge.LOGGER.info("party: applied a {} from the bag", item.kind().name().toLowerCase(java.util.Locale.ROOT));
		}
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
		warn("item_no_evolution");
		return false;
	}

	/**
	 * A TM or HM: the move goes into the slot holding {@code forget} (FP1: the move replaced, by number, since the hidden
	 * slot and Cobblemon's need not match), else {@code slot}, else the first free slot, with full PP; the move it
	 * replaces is benched, so it stays re-learnable from Cobblemon's summary (P13: nobody softlocks over an HM).
	 */
	static boolean teach(Pokemon pokemon, int number, @Nullable Integer slot, @Nullable Integer forget) {
		MoveTemplate template = Gen1Mons.move(number);
		if (template == null) {
			warn("item_move_missing");
			return false;
		}
		MoveSet moves = pokemon.getMoveSet();
		for (Move known : moves.getMoves()) {
			if (known.getTemplate() == template) {
				// Gen 1 refuses a move the Pokémon knows; the hidden game shouldn't send one.
				warn("item_move_known");
				return false;
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
			warn("item_no_slot");
			return false;
		}
		Move replaced = moves.get(target);
		if (replaced != null) {
			pokemon.getBenchedMoves().add(new BenchedMove(replaced.getTemplate(), replaced.getRaisedPpStages()));
		}
		pokemon.getBenchedMoves().remove(template);
		moves.setMove(target, template.create());
		return true;
	}

	/** Rare Candy: the experience to the next level, as Cobblemon's own candy gives it (with the player's level-up UI). */
	static boolean rareCandy(ServerPlayer player, Pokemon pokemon) {
		if (pokemon.getLevel() >= com.cobblemon.mod.common.Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel()) {
			warn("item_max_level");
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
	 * The evolution rule (P14): false when the result is a species past the owner's followed region's ceiling. FP1: the
	 * sync's {@code stones} apply the same rule, so the hidden stone check and Cobblemon agree.
	 */
	static boolean evolutionAllowed(Evolution evolution, @Nullable ServerPlayer owner) {
		String target = evolution.getResult().getSpecies();
		Species species = target == null ? null : speciesById(target);
		return species == null || species.getNationalPokedexNumber() <= evolutionCeilingOf(owner);
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
