package dev.kantobridge.battle;

import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFleeAttemptEvent;
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.StoryCellBlock;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.CobblemonSettings;
import dev.kantobridge.server.Regions;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Static, surfing and fishing encounters (docs/PLAN.md §2.4 flow 9, §4.4 {@code EncounterService}, P8): the hidden game
 * rolls or triggers them and the player's client relays {@code battle.start} with a {@code wild} Pokémon. The server
 * spawns that Pokémon near the player (on the given cell when it can, on the water ahead when surfing or fishing) as a
 * {@code PokemonEntity} that stands still ({@code no_ai}), never despawns, can't be battled by anyone else
 * ({@code UNBATTLEABLE}) and doesn't count towards Cobblemon's spawn cap, and starts Cobblemon's wild battle
 * ({@code BattleBuilder.pve}, {@code GEN_9_SINGLES}, flee distance {@value #FLEE_DISTANCE} or none when the rules forbid
 * running). Balls thrown at it outside its battle are refused; with {@code rules.catch} false it can't be caught at all.
 *
 * <p>The end is answered with exactly one {@code battle.result}: {@code caught} (with the Pokémon's uid, dex and level),
 * {@code win} (defeated), {@code lose}, {@code fled} or {@code aborted} (stopped with nobody winning). Because the client
 * holds the player during every battle (or, minimised, fences it well inside the flee distance: BATTLE_ROAM_PLAN), Cobblemon's
 * Run (walk away) can't work, so a flee attempt ends the battle as
 * fled at once when running is allowed; the same goes for natural wild battles in a Kanto world. The Pokémon is
 * discarded afterwards (a caught one is already gone). Logs carry kinds, counts and codes only. Server thread only.
 */
public final class EncounterService {
	/** Entity tag on every encounter Pokémon; a restart discards those a crash left in a save. */
	public static final String TAG = "kantobridge_encounter";
	public static final float FLEE_DISTANCE = CobblemonSettings.FLEE_DISTANCE;
	/** A defeated Pokémon plays its faint before it is discarded, at most this long. */
	static final int DISCARD_AFTER_TICKS = 40;

	/** A caught Pokémon as {@code battle.result} reports it. */
	record Caught(UUID uid, int dex, int level) {
	}

	/** One player's running encounter. */
	private static final class Running {
		final MinecraftServer server;
		final UUID player;
		final String id;
		final BattleStart.Kind kind;
		final PokemonEntity entity;
		final UUID pokemon;
		final boolean canFlee;
		/** The region its start was tagged with (MG8), or null in a legacy world. */
		final @Nullable String region;
		@Nullable PokemonBattle battle;
		boolean answered;
		boolean fled;
		@Nullable Caught caught;

		Running(MinecraftServer server, UUID player, BattleStart start, PokemonEntity entity, @Nullable String region) {
			this.server = server;
			this.region = region;
			this.player = player;
			this.id = start.battle();
			this.kind = start.kind();
			this.entity = entity;
			this.pokemon = entity.getPokemon().getUuid();
			this.canFlee = start.canFlee();
		}
	}

	private record Discard(PokemonEntity entity, long deadline) {
	}

	private static final Map<UUID, Running> RUNNING = new HashMap<>();
	private static final List<Discard> DISCARDS = new ArrayList<>();
	private static final List<Entity> LEFTOVERS = new ArrayList<>();
	/** Battles to stop on the next tick: flee attempts (never stopped inside Cobblemon's own dispatch). */
	private static final Queue<PokemonBattle> STOPS = new ConcurrentLinkedQueue<>();
	/** Encounter Pokémon this server manages (entity uuids). */
	private static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong STARTED = new AtomicLong();
	private static final AtomicLong FLEES = new AtomicLong();
	private static final AtomicLong REFUSED_BALLS = new AtomicLong();

	private EncounterService() {
	}

	/** Call once from common init (after {@link BattleService#init}, which routes wild kinds here). */
	public static void init() {
		CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, EncounterService::onCaptured);
		CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGHEST, EncounterService::onBallHit);
		CobblemonEvents.BATTLE_FLEE_ATTEMPT.subscribe(Priority.NORMAL, EncounterService::onFleeAttempt);
		CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, event -> {
			Running run = byBattle(event.getBattle().getBattleId());
			if (run != null) {
				run.fled = true;
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(EncounterService::tick);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.getTags().contains(TAG) && !LIVE.contains(entity.getUUID())) {
				// Discarding while the level adds the entity is unsafe; the next server tick does it.
				LEFTOVERS.add(entity);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> onStopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
	}

	// ---- starting ----

	/**
	 * Starts a wild encounter for a player whose other checks passed (no other battle, a usable Pokémon). Returns null
	 * when the battle runs, else a refusal code for {@code aborted}.
	 */
	static @Nullable String begin(ServerPlayer player, BattleStart start, @Nullable String region) {
		BattleStart.Wild wild = start.wild();
		if (wild == null) {
			return "bad_message";
		}
		Pokemon pokemon;
		try {
			pokemon = Gen1Mons.wild(wild.dex(), wild.level(), wild.dvs(), wild.details());
		} catch (Gen1Mons.Missing e) {
			return "missing_" + e.code();
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("encounter: Cobblemon failed to build the Pokémon ({})", e.getClass().getName());
			return "team_failed";
		}
		if (!start.canCatch()) {
			// Refuses every ball, in battle too (the in-battle path checks it before taking the throw).
			UncatchableProperty.INSTANCE.uncatchable().apply(pokemon);
		}
		ServerLevel level = player.serverLevel();
		PokemonEntity entity = spawn(level, player, start, pokemon);
		if (entity == null) {
			return "spawn_failed";
		}
		Running run = new Running(player.server, player.getUUID(), start, entity, region);
		BattleStartResult result;
		try {
			// Modern rules (U3), the player's real party, no healing first; no running when the rules forbid it (-1). The
			// hidden game plays the battle music (P22).
			// FP5 (FOREIGN_POKEMON_PLAN §4.12): with use (the contest's lead), Cobblemon sees only those of the real party
			com.cobblemon.mod.common.api.storage.party.PartyStore party = start.use().isEmpty() ? PlayerExtensionsKt.party(player)
					: new OnlyUsed(player, start.use());
			result = BattleMusic.starting(player, () -> BattleBuilder.INSTANCE.pve(player, entity, null, BattleFormat.Companion.getGEN_9_SINGLES(),
					false, false, start.canFlee() ? FLEE_DISTANCE : -1.0F, party));
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.warn("encounter: Cobblemon failed to start the battle ({})", e.getClass().getName());
			result = null;
		}
		if (!(result instanceof SuccessfulBattleStart started)) {
			discardNow(entity);
			return "start_failed";
		}
		PokemonBattle battle = started.getBattle();
		run.battle = battle;
		RUNNING.put(player.getUUID(), run);
		battle.getOnEndHandlers().add(ended -> {
			onEnd(run, ended);
			return Unit.INSTANCE;
		});
		long count = STARTED.incrementAndGet();
		KantoBridge.LOGGER.info("encounter: started a {} encounter (catch {}, flee {}); {} so far", start.kind().id(), start.canCatch(),
				start.canFlee(), count);
		return null;
	}

	/**
	 * FP5: the player's real party, seen through a store that lists only the {@code use} Pokémon. Cobblemon's
	 * {@code toBattleTeam} reads a store by iterating it, so the battle gets those Pokémon themselves (not copies, so
	 * their HP and experience are Cobblemon's as in any battle) and nothing else; the Pokémon never leave the real party.
	 */
	static final class OnlyUsed extends com.cobblemon.mod.common.api.storage.party.PartyStore {
		private final ServerPlayer player;
		private final java.util.List<java.util.UUID> use;

		OnlyUsed(ServerPlayer player, java.util.List<java.util.UUID> use) {
			super(player.getUUID());
			this.player = player;
			this.use = use;
		}

		@Override
		public java.util.Iterator<Pokemon> iterator() {
			java.util.List<Pokemon> out = new java.util.ArrayList<>();
			for (Pokemon pokemon : PlayerExtensionsKt.party(this.player)) {
				if (pokemon != null && this.use.contains(pokemon.getUuid())) {
					out.add(pokemon);
				}
			}
			return out.iterator();
		}
	}

	/** The encounter Pokémon: still, persistent, unbattleable for others, outside the spawn cap, facing the player. */
	private static @Nullable PokemonEntity spawn(ServerLevel level, ServerPlayer player, BattleStart start, Pokemon pokemon) {
		PokemonEntity entity = new PokemonEntity(level, pokemon, CobblemonEntities.POKEMON);
		Vec3 spot = EncounterPlacement.choose(givenCell(player.server, player, start.wild()), player.position(), player.getYRot());
		Vec3 at = new Vec3(spot.x, groundY(level, spot.x, spot.z, spot.y), spot.z);
		float yaw = ProxyPlacement.yawTowards(at, player.position());
		entity.moveTo(at.x, at.y, at.z, yaw, 0.0F);
		entity.setYHeadRot(yaw);
		entity.setYBodyRot(yaw);
		entity.getEntityData().set(PokemonEntity.getSPAWN_DIRECTION(), yaw);
		entity.setNoAi(true);
		entity.setPersistenceRequired();
		entity.getEntityData().set(PokemonEntity.getUNBATTLEABLE(), true);
		entity.getEntityData().set(PokemonEntity.getCOUNTS_TOWARDS_SPAWN_CAP(), false);
		entity.setCountsTowardsSpawnCap(false);
		entity.addTag(TAG);
		entity.finalizeSpawn(level, level.getCurrentDifficultyAt(entity.blockPosition()), MobSpawnType.EVENT, null);
		// Live before it enters the level, so the leftover check on entity load leaves it alone.
		LIVE.add(entity.getUUID());
		if (!level.addFreshEntity(entity)) {
			LIVE.remove(entity.getUUID());
			return null;
		}
		return entity;
	}

	/** The centre of the given cell on the player's map, when the WorldIndex knows the map and the cell lies in it. */
	private static @Nullable Vec3 givenCell(MinecraftServer server, ServerPlayer player, BattleStart.@Nullable Wild wild) {
		// MG3: the index of the region the player stands in (the cell is on the player's own map)
		WorldIndex index = Regions.currentIndexAt(player);
		if (wild == null || wild.x() == null || wild.y() == null || index == null) {
			return null;
		}
		// The cell is on the player's own map (the schema's wild has no map): the one the player stands on.
		return index.toCell(player.blockPosition()).map(cell -> {
			MapPlacement map = index.map(cell.map());
			return map != null && map.containsCell(wild.x(), wild.y()) ? index.toBlock(cell.map(), wild.x(), wild.y()) : null;
		}).orElse(null);
	}

	/**
	 * The top of the ground under a column, scanning down from about {@code fromY}: a tile block (a half one for water),
	 * skipping our invisible story cells and ledge colliders, which are solid only to whoever they block, and edge walls,
	 * which are only plates at a cell's side (docs/specs/CAVES_3D_PLAN.md §3.2).
	 */
	static double groundY(ServerLevel level, double x, double z, double fromY) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(fromY + 0.5), Mth.floor(z));
		for (int i = 0; i < 6; i++) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof StoryCellBlock) && !state.is(KbBlocks.LEDGE_COLLIDER) && !state.is(KbBlocks.EDGE_WALL)) {
				VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
				if (!shape.isEmpty()) {
					return pos.getY() + shape.max(Direction.Axis.Y);
				}
			}
			pos.move(Direction.DOWN);
		}
		return fromY;
	}

	// ---- during the battle ----

	/** A ball thrown at an encounter Pokémon outside its battle (by anyone): refused, the ball drops. */
	static void onBallHit(ThrownPokeballHitEvent event) {
		PokemonEntity target = event.getPokemon();
		if (LIVE.contains(target.getUUID()) && target.getBattleId() == null) {
			event.cancel();
			REFUSED_BALLS.incrementAndGet();
		}
	}

	static void onCaptured(PokemonCapturedEvent event) {
		Pokemon pokemon = event.getPokemon();
		Running run = byPokemon(pokemon.getUuid());
		if (run != null && run.player.equals(event.getPlayer().getUUID())) {
			run.caught = new Caught(pokemon.getUuid(), pokemon.getSpecies().getNationalPokedexNumber(), pokemon.getLevel());
		}
	}

	/**
	 * Run was pressed. Cobblemon would wait for the player to walk {@value #FLEE_DISTANCE} blocks away, which the held (or
	 * fenced) client never does, so the battle ends as fled now (next tick): for our encounters when the rules allow running,
	 * and for any other wild battle in a Kanto world.
	 */
	static void onFleeAttempt(BattleFleeAttemptEvent event) {
		PokemonBattle battle = event.getBattle();
		Running run = byBattle(battle.getBattleId());
		if (run != null) {
			if (run.canFlee) {
				run.fled = true;
				STOPS.add(battle);
			}
			return;
		}
		ServerPlayer player = event.getPlayer().getEntity();
		if (battle.isPvW() && player != null && Regions.exists(player.server)) {
			STOPS.add(battle);
		}
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
		ServerPlayer player = run.server.getPlayerList().getPlayer(run.player);
		Caught caught = run.caught;
		if (caught == null && playerWon && player != null) {
			// Cobblemon adds a caught Pokémon before the battle ends; should its event have been missed, the party knows.
			caught = caughtIn(player, run.pokemon);
		}
		BattleOutcome outcome = BattleOutcome.classify(decided, playerWon, run.fled, caught != null);
		RUNNING.remove(run.player, run);
		DISCARDS.add(new Discard(run.entity, run.server.getTickCount() + DISCARD_AFTER_TICKS));
		if (outcome == BattleOutcome.FLED) {
			FLEES.incrementAndGet();
		}
		if (player == null) {
			// Gone (a disconnect stops the battle): the client tells gen1-bridge the battle was aborted itself.
			warn("player_gone");
		} else if (caught != null) {
			RelayService.get().send(player, MsgTypes.BATTLE_RESULT,
					Regions.tag(BattleOutcome.caught(run.id, caught.uid(), caught.dex(), caught.level()), run.server, run.region), null);
		} else {
			RelayService.get().send(player, MsgTypes.BATTLE_RESULT, Regions.tag(outcome.result(run.id), run.server, run.region), null);
		}
		KantoBridge.LOGGER.info("encounter: a {} encounter ended ({})", run.kind.id(), outcome.id());
	}

	private static @Nullable Caught caughtIn(ServerPlayer player, UUID pokemon) {
		Pokemon found = PlayerExtensionsKt.party(player).get(pokemon);
		if (found == null) {
			found = PlayerExtensionsKt.pc(player).get(pokemon);
		}
		return found == null ? null : new Caught(found.getUuid(), found.getSpecies().getNationalPokedexNumber(), found.getLevel());
	}

	// ---- clean-up ----

	private static void tick(MinecraftServer server) {
		PokemonBattle stop;
		while ((stop = STOPS.poll()) != null) {
			if (!stop.getEnded()) {
				// stop() heals the wild Pokémon and ends the battle with nobody winning: a flight.
				stop.stop();
			}
		}
		for (Entity leftover : LEFTOVERS) {
			if (!leftover.isRemoved()) {
				leftover.discard();
				warn("leftover_encounter");
			}
		}
		LEFTOVERS.clear();
		long now = server.getTickCount();
		Iterator<Discard> it = DISCARDS.iterator();
		while (it.hasNext()) {
			Discard discard = it.next();
			PokemonEntity entity = discard.entity();
			if (entity.isRemoved()) {
				LIVE.remove(entity.getUUID());
				it.remove();
			} else if (!entity.isDeadOrDying() || now >= discard.deadline()) {
				discardNow(entity);
				it.remove();
			}
		}
	}

	private static void discardNow(PokemonEntity entity) {
		entity.discard();
		LIVE.remove(entity.getUUID());
	}

	/** The server is about to save: no encounter Pokémon may end up in the world save. */
	private static void onStopping() {
		for (Running run : RUNNING.values()) {
			run.answered = true;
			discardNow(run.entity);
		}
		for (Discard discard : DISCARDS) {
			discardNow(discard.entity());
		}
		RUNNING.clear();
		DISCARDS.clear();
	}

	/** In singleplayer the next world reuses these statics. */
	private static void reset() {
		RUNNING.clear();
		DISCARDS.clear();
		LEFTOVERS.clear();
		STOPS.clear();
		LIVE.clear();
	}

	private static @Nullable Running byBattle(UUID battle) {
		for (Running run : RUNNING.values()) {
			if (run.battle != null && run.battle.getBattleId().equals(battle)) {
				return run;
			}
		}
		return null;
	}

	private static @Nullable Running byPokemon(UUID pokemon) {
		for (Running run : RUNNING.values()) {
			if (run.pokemon.equals(pokemon)) {
				return run;
			}
		}
		return null;
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("encounter: {} ({} so far)", code, count);
	}

	// ---- views (BattleService, tests, diagnostics) ----

	/** The id of the player's running encounter, or null. */
	static @Nullable String runningId(ServerPlayer player) {
		Running run = RUNNING.get(player.getUUID());
		return run == null ? null : run.id;
	}

	/** The Pokémon of the player's running encounter, or null. */
	public static @Nullable PokemonEntity entityOf(ServerPlayer player) {
		Running run = RUNNING.get(player.getUUID());
		return run == null ? null : run.entity;
	}

	/** The Cobblemon battle of the player's running encounter, or null. */
	public static @Nullable PokemonBattle battleOf(ServerPlayer player) {
		Running run = RUNNING.get(player.getUUID());
		return run == null ? null : run.battle;
	}

	/** True for an encounter Pokémon this server manages. */
	public static boolean isEncounter(Entity entity) {
		return LIVE.contains(entity.getUUID());
	}

	public static long started() {
		return STARTED.get();
	}

	public static long flees() {
		return FLEES.get();
	}

	public static long refusedBalls() {
		return REFUSED_BALLS.get();
	}

	public static Map<String, Long> warnings() {
		Map<String, Long> out = new TreeMap<>();
		WARNINGS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}
}
