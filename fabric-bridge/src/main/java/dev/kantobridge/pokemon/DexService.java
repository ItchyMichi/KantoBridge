package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.pokedex.SpeciesDexRecord;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Pokédex between Cobblemon and the hidden game (docs/PLAN.md §4.4 {@code DexService}, DESIGN §6 "Pokédex sync"):
 * Oak's aides and Oak's comments read the hidden game's Pokédex, so every Pokémon seen or caught in Cobblemon reaches it.
 *
 * <ul>
 * <li><b>Cobblemon → the hidden game:</b> {@code POKEDEX_DATA_CHANGED_POST} (and Cobblemon's data sync on joining) make a
 * {@code dex.sync{seen, owned}} (dex ≤ 151) go to the client, at most every {@value #SYNC_EVERY_TICKS} ticks and only when
 * it changed; the client relays it to gen1-bridge.</li>
 * <li><b>The hidden game → Cobblemon:</b> its entries (seen in a native battle: the Safari Zone, the old man's demo) are
 * written with {@code setKnowledgeProgress}, which posts no event, so the two directions never loop; owned is never
 * downgraded. They arrive as {@code dex.sync} C→S when the protocol's catalog gives that type a {@code c2s} leg (the
 * client relays its snapshot's {@code dex}); until then {@link #applyHidden} has no caller but the tests.</li>
 * </ul>
 * Logs carry counts only. Server thread only.
 */
public final class DexService {
	static final int SYNC_EVERY_TICKS = 10;

	private static final Map<UUID, SyncDebounce> WATCHES = new ConcurrentHashMap<>();
	private static final AtomicLong SENT = new AtomicLong();
	private static final AtomicLong APPLIED = new AtomicLong();

	private DexService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		CobblemonEvents.DATA_SYNCHRONIZED.subscribe(Priority.NORMAL, player -> {
			watch(player);
		});
		CobblemonEvents.POKEDEX_DATA_CHANGED_POST.subscribe(Priority.NORMAL, event -> {
			markDirty(event.getPlayerUUID());
		});
		Catalog.Entry entry = Catalog.bundled().get(MsgTypes.DEX_SYNC);
		if (entry != null && entry.legs().contains(Catalog.Leg.C2S)) {
			RelayService.get().register(MsgTypes.DEX_SYNC, DexService::onHidden);
		}
		ServerTickEvents.END_SERVER_TICK.register(DexService::tick);
		ServerPlayerEvents.LEAVE.register(player -> WATCHES.remove(player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> WATCHES.clear());
	}

	/** Starts following a player's Pokédex; the first sync goes out on the next tick. */
	public static void watch(ServerPlayer player) {
		WATCHES.computeIfAbsent(player.getUUID(), id -> new SyncDebounce(SYNC_EVERY_TICKS)).forget();
	}

	static void markDirty(UUID player) {
		SyncDebounce watch = WATCHES.get(player);
		if (watch != null) {
			watch.mark();
		}
	}

	private static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		for (Map.Entry<UUID, SyncDebounce> entry : WATCHES.entrySet()) {
			SyncDebounce watch = entry.getValue();
			if (!watch.due(now)) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			JsonObject data = snapshot(player).toJson();
			String json = ProtoJson.write(data);
			if (watch.offer(json) && RelayService.get().send(player, MsgTypes.DEX_SYNC, data, null)) {
				watch.sent(json, now);
				SENT.incrementAndGet();
			}
		}
	}

	/** The player's Cobblemon Pokédex as {@code dex.sync} carries it: the first 151 species only. */
	public static DexSync snapshot(ServerPlayer player) {
		TreeSet<Integer> seen = new TreeSet<>();
		TreeSet<Integer> owned = new TreeSet<>();
		for (Map.Entry<ResourceLocation, SpeciesDexRecord> entry : PlayerExtensionsKt.pokedex(player).getSpeciesRecords().entrySet()) {
			ResourceLocation id = entry.getKey();
			int dex = GenOneIds.NAMESPACE.equals(id.getNamespace()) ? GenOneIds.dexOf(id.getPath()) : 0;
			if (dex == 0) {
				continue;
			}
			PokedexEntryProgress knowledge = entry.getValue().getKnowledge();
			if (knowledge == PokedexEntryProgress.OWNED) {
				owned.add(dex);
			} else if (knowledge == PokedexEntryProgress.SEEN) {
				seen.add(dex);
			}
		}
		return new DexSync(seen, owned);
	}

	// ---- the hidden game's entries ----

	static void onHidden(ServerPlayer player, Envelope message) {
		DexSync hidden;
		try {
			hidden = DexSync.read(message.data());
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("dex: malformed dex.sync at {} ({})", e.path(), e.problem());
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		applyHidden(player, hidden);
	}

	/**
	 * Writes the hidden game's entries into Cobblemon's Pokédex without events (no echo through
	 * {@code POKEDEX_DATA_CHANGED_POST}): owned species become owned, seen ones seen; nothing is downgraded. Returns how
	 * many species changed; then the merged Pokédex goes back as {@code dex.sync}.
	 */
	public static int applyHidden(ServerPlayer player, DexSync hidden) {
		PokedexManager pokedex = PlayerExtensionsKt.pokedex(player);
		int changed = 0;
		for (int dex : hidden.seen()) {
			PokedexEntryProgress want = hidden.owned().contains(dex) ? PokedexEntryProgress.OWNED : PokedexEntryProgress.SEEN;
			Species species = Gen1Mons.species(dex);
			if (species == null) {
				continue;
			}
			ResourceLocation id = species.getResourceIdentifier();
			if (pokedex.getKnowledgeForSpecies(id).ordinal() >= want.ordinal()) {
				continue;
			}
			pokedex.getOrCreateSpeciesRecord(id).getOrCreateFormRecord(species.getStandardForm().getName()).setKnowledgeProgress(want);
			changed++;
		}
		if (changed > 0) {
			APPLIED.addAndGet(changed);
			markDirty(player.getUUID());
			KantoBridge.LOGGER.debug("dex: {} species from the hidden game", changed);
		}
		return changed;
	}

	// ---- views (tests, diagnostics) ----

	/** {@code dex.sync} messages sent; hidden-game species written. */
	public static long[] stats() {
		return new long[] {SENT.get(), APPLIED.get()};
	}

	public static boolean watching(ServerPlayer player) {
		return WATCHES.containsKey(player.getUUID());
	}
}
