package dev.kantobridge.battle;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.BuildHold;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Trainer, rival and scripted battles (docs/PLAN.md §2.4 flow 8, §4.4 {@code BattleService}, U3, P11): gen1-bridge defers
 * the hidden game's battle and the player's client relays {@code battle.start}. The server spawns a hidden battle proxy
 * (a Cobblemon {@code NPCEntity} of a {@code kantobridge:battle_proxy*} class, never sent to any client) on the trainer's
 * cell, gives it the hidden game's team with exact Gen 1 moves and IVs from DVs, and starts Cobblemon's
 * {@code GEN_9_SINGLES} battle with {@code BattleBuilder.pvn}. Its end handler answers {@code battle.result} exactly
 * once: win, lose (a forfeit is a loss), fled, or aborted when nobody won ({@code stop()}: a disconnect, a crash).
 * The proxy is discarded once Cobblemon's own cleanup is done, 10 s after the end at the latest.
 *
 * <p>One battle per player, trainer or wild: a repeated {@code battle.start} with the running battle's id is ignored;
 * anything else that can't run (another battle, no usable Pokémon, a malformed message) is answered {@code aborted} with
 * a counted warning. Wild kinds (static, surf, fish, and scripted battles with a {@code wild} Pokémon) go to
 * {@link EncounterService}. Logs carry kinds, counts, class ids and codes, never teams or names. Server thread only.
 */
public final class BattleService {
	/** The proxy goes at the latest this long after the end (PLAN §4.4: Cobblemon's recall takes about 7 s). */
	static final int DISCARD_AFTER_TICKS = 200;

	/** A battle that is running for a player. */
	private static final class Running {
		final MinecraftServer server;
		final UUID player;
		final String id;
		final BattleStart.Kind kind;
		final ProxyClass proxyClass;
		final NPCEntity proxy;
		@Nullable PokemonBattle battle;
		boolean answered;

		Running(MinecraftServer server, UUID player, String id, BattleStart.Kind kind, ProxyClass proxyClass, NPCEntity proxy) {
			this.server = server;
			this.player = player;
			this.id = id;
			this.kind = kind;
			this.proxyClass = proxyClass;
			this.proxy = proxy;
		}
	}

	private record Discard(NPCEntity proxy, long deadline) {
	}

	/** Running battles by player. */
	private static final Map<UUID, Running> RUNNING = new HashMap<>();
	private static final List<Discard> DISCARDS = new ArrayList<>();
	private static final List<Entity> LEFTOVERS = new ArrayList<>();
	/** Cobblemon battle ids that {@code BATTLE_FLED} marked before they stopped. */
	private static final Set<UUID> FLED = ConcurrentHashMap.newKeySet();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong STARTED = new AtomicLong();
	private static final AtomicLong DUPLICATES = new AtomicLong();

	private BattleService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.BATTLE_START, BattleService::onStart);
		CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, event -> {
			FLED.add(event.getBattle().getBattleId());
		});
		ServerTickEvents.END_SERVER_TICK.register(BattleService::tick);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (BattleProxies.leftover(entity)) {
				// Discarding while the level adds the entity is unsafe; the next server tick does it.
				LEFTOVERS.add(entity);
			}
		});
		// Cobblemon stops a leaving player's battle itself; this covers any path where it doesn't.
		ServerPlayerEvents.LEAVE.register(BattleService::onLeave);
		ServerLifecycleEvents.SERVER_STOPPING.register(BattleService::onStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
	}

	private static void onLeave(ServerPlayer player) {
		Running run = RUNNING.get(player.getUUID());
		if (run != null && run.battle != null && !run.battle.getEnded()) {
			run.battle.stop();
		}
	}

	// ---- starting ----

	static void onStart(ServerPlayer player, Envelope message) {
		JsonObject data = message.data();
		BattleStart start;
		try {
			start = BattleStart.read(data);
		} catch (DataException e) {
			// The path and reason name a schema location, never a value.
			KantoBridge.LOGGER.debug("battle: malformed battle.start at {} ({})", e.path(), e.problem());
			RelayService.get().sendError(player, "bad_message", message.seq());
			String id = BattleStart.idOf(data);
			if (id != null) {
				refuse(player, id, "bad_message");
			} else {
				warn("bad_message");
			}
			return;
		}
		Running running = RUNNING.get(player.getUUID());
		String runningId = running != null ? running.id : EncounterService.runningId(player);
		if (runningId != null) {
			if (runningId.equals(start.battle())) {
				// The same battle again (a client relaying twice): it is already being fought.
				DUPLICATES.incrementAndGet();
				KantoBridge.LOGGER.debug("battle: ignored a repeated battle.start");
			} else {
				refuse(player, start.battle(), "busy");
			}
			return;
		}
		if (BuildHold.isHeld(player)) {
			refuse(player, start.battle(), "held");
			return;
		}
		if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
			refuse(player, start.battle(), "busy");
			return;
		}
		if (!hasUsablePokemon(player)) {
			refuse(player, start.battle(), "no_party");
			return;
		}
		if (!start.hasTeam()) {
			// Static, surfing and fishing encounters (and scripted wild battles): a wild Pokémon, not a trainer.
			String refused = EncounterService.begin(player, start);
			if (refused != null) {
				refuse(player, start.battle(), refused);
			}
			return;
		}
		begin(player, start);
	}

	private static void begin(ServerPlayer player, BattleStart start) {
		ProxyClass proxyClass = ProxyClass.of(start.kind(), start.trainer() == null ? null : start.trainer().cls());
		NPCClass npcClass = NPCClasses.getByIdentifier(ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, proxyClass.path()));
		if (npcClass == null) {
			refuse(player, start.battle(), "no_class");
			return;
		}
		List<Pokemon> team = new ArrayList<>(start.team().size());
		try {
			for (Gen1Mon mon : start.team()) {
				team.add(Gen1Mons.trainerMon(mon));
			}
		} catch (Gen1Mons.Missing e) {
			refuse(player, start.battle(), "missing_" + e.code());
			return;
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("battle: Cobblemon failed to build the team ({})", e.getClass().getName());
			refuse(player, start.battle(), "team_failed");
			return;
		}
		ServerLevel level = player.serverLevel();
		NPCEntity proxy = spawnProxy(level, player, start, npcClass, team);
		if (proxy == null) {
			refuse(player, start.battle(), "spawn_failed");
			return;
		}
		Running run = new Running(player.server, player.getUUID(), start.battle(), start.kind(), proxyClass, proxy);
		BattleStartResult result;
		try {
			// Modern rules (U3); the player's real party (no clone: damage and XP stick); no healing first, as in Gen 1.
			result = BattleBuilder.INSTANCE.pvn(player, proxy, null, BattleFormat.Companion.getGEN_9_SINGLES(), false, false);
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.warn("battle: Cobblemon failed to start the battle ({})", e.getClass().getName());
			result = null;
		}
		if (!(result instanceof SuccessfulBattleStart started)) {
			discardNow(proxy);
			refuse(player, start.battle(), "start_failed");
			return;
		}
		PokemonBattle battle = started.getBattle();
		run.battle = battle;
		RUNNING.put(player.getUUID(), run);
		battle.getOnEndHandlers().add(ended -> {
			onEnd(run, ended);
			return Unit.INSTANCE;
		});
		long count = STARTED.incrementAndGet();
		KantoBridge.LOGGER.info("battle: started a {} battle against {} Pokémon (class {}, skill {}); {} so far", start.kind().id(), team.size(),
				proxyClass.path(), proxyClass.skill(), count);
	}

	/** The proxy: the class, hidden for everyone, no AI, the trainer's name for the battle UI, the team as its party. */
	private static @Nullable NPCEntity spawnProxy(ServerLevel level, ServerPlayer player, BattleStart start, NPCClass npcClass, List<Pokemon> team) {
		NPCEntity proxy = new NPCEntity(level);
		proxy.setNpc(npcClass);
		Vec3 at = ProxyPlacement.choose(trainerCell(player.server, start.trainer()), player.position(), player.getYRot());
		float yaw = ProxyPlacement.yawTowards(at, player.position());
		proxy.moveTo(at.x, at.y, at.z, yaw, 0.0F);
		proxy.setYHeadRot(yaw);
		proxy.setYBodyRot(yaw);
		proxy.initialize(1);
		proxy.addTag(BattleProxies.TAG);
		// No movement, no gravity: it stands where the trainer stands until it is discarded.
		proxy.setNoAi(true);
		BattleStart.Trainer trainer = start.trainer();
		if (trainer != null && trainer.name() != null && !trainer.name().isBlank()) {
			// Cobblemon's battle UI names the opponent after the NPC (ROM-derived; may pass through the server, U8).
			proxy.setCustomName(Component.literal(trainer.name()));
		}
		NPCPartyStore party = new NPCPartyStore(proxy);
		for (Pokemon pokemon : team) {
			party.add(pokemon);
		}
		proxy.setParty(party);
		// Live before it enters the level, so the leftover check on entity load leaves it alone.
		BattleProxies.live(proxy);
		if (!level.addFreshEntity(proxy)) {
			BattleProxies.dead(proxy);
			return null;
		}
		return proxy;
	}

	/** The centre of the trainer's cell, if the WorldIndex knows the map and the cell lies in it. */
	private static @Nullable Vec3 trainerCell(MinecraftServer server, BattleStart.@Nullable Trainer trainer) {
		WorldIndex index = WorldIndexSync.current(server);
		if (trainer == null || !trainer.hasCell() || index == null) {
			return null;
		}
		MapPlacement map = index.map(trainer.map());
		if (map == null || !map.containsCell(trainer.x(), trainer.y())) {
			return null;
		}
		return index.toBlock(trainer.map(), trainer.x(), trainer.y());
	}

	private static boolean hasUsablePokemon(ServerPlayer player) {
		for (Pokemon pokemon : PlayerExtensionsKt.party(player)) {
			if (!pokemon.isFainted()) {
				return true;
			}
		}
		return false;
	}

	// ---- ending ----

	private static void onEnd(Running run, PokemonBattle battle) {
		if (run.answered) {
			return;
		}
		run.answered = true;
		boolean decided = !battle.getWinners().isEmpty();
		boolean playerWon = false;
		for (BattleActor winner : battle.getWinners()) {
			if (winner instanceof PlayerBattleActor && winner.getUuid().equals(run.player)) {
				playerWon = true;
			}
		}
		BattleOutcome outcome = BattleOutcome.classify(decided, playerWon, FLED.remove(battle.getBattleId()));
		RUNNING.remove(run.player, run);
		DISCARDS.add(new Discard(run.proxy, run.server.getTickCount() + DISCARD_AFTER_TICKS));
		ServerPlayer player = run.server.getPlayerList().getPlayer(run.player);
		if (player == null) {
			// Gone (a disconnect stops the battle): the client tells gen1-bridge the battle was aborted itself.
			warn("player_gone");
		} else {
			answer(player, run.id, outcome);
		}
		KantoBridge.LOGGER.info("battle: a {} battle ended ({}, class {})", run.kind.id(), outcome.id(), run.proxyClass.path());
	}

	private static void answer(ServerPlayer player, String battle, BattleOutcome outcome) {
		RelayService.get().send(player, MsgTypes.BATTLE_RESULT, outcome.result(battle), null);
	}

	/** Answers {@code aborted} for a battle that can't run, with a counted warning (codes only). */
	private static void refuse(ServerPlayer player, String battle, String code) {
		warn(code);
		answer(player, battle, BattleOutcome.ABORTED);
	}

	// ---- clean-up ----

	private static void tick(MinecraftServer server) {
		for (Entity leftover : LEFTOVERS) {
			if (!leftover.isRemoved()) {
				leftover.discard();
				warn("leftover_proxy");
			}
		}
		LEFTOVERS.clear();
		long now = server.getTickCount();
		Iterator<Discard> it = DISCARDS.iterator();
		while (it.hasNext()) {
			Discard discard = it.next();
			NPCEntity proxy = discard.proxy();
			if (proxy.isRemoved()) {
				// Unloaded with its chunk: no longer live, so the leftover check removes it when it loads again.
				BattleProxies.dead(proxy);
				it.remove();
			} else if (!proxy.isInBattle() || now >= discard.deadline()) {
				discardNow(proxy);
				it.remove();
			}
		}
	}

	private static void discardNow(NPCEntity proxy) {
		proxy.discard();
		BattleProxies.dead(proxy);
	}

	/** The server is about to save: no proxy may end up in the world save. */
	private static void onStopping(MinecraftServer server) {
		for (Running run : RUNNING.values()) {
			run.answered = true;
			discardNow(run.proxy);
		}
		for (Discard discard : DISCARDS) {
			discardNow(discard.proxy());
		}
		RUNNING.clear();
		DISCARDS.clear();
	}

	/** In singleplayer the next world reuses these statics. */
	private static void reset() {
		RUNNING.clear();
		DISCARDS.clear();
		LEFTOVERS.clear();
		FLED.clear();
		BattleProxies.clear();
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("battle: {} ({} so far)", code, count);
	}

	// ---- views (tests, diagnostics) ----

	/** The proxy of the player's running battle, or null. */
	public static @Nullable NPCEntity proxyOf(ServerPlayer player) {
		Running run = RUNNING.get(player.getUUID());
		return run == null ? null : run.proxy;
	}

	/** The Cobblemon battle the player is fighting for the hidden game, or null. */
	public static @Nullable PokemonBattle battleOf(ServerPlayer player) {
		Running run = RUNNING.get(player.getUUID());
		return run == null ? null : run.battle;
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

	public static long started() {
		return STARTED.get();
	}

	public static long duplicates() {
		return DUPLICATES.get();
	}
}
