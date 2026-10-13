package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.BuildHold;
import dev.kantobridge.server.Regions;
import dev.kantobridge.server.SaveService;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code party.stash} (docs/specs/MULTI_GAME_PLAN.md §4.7 "The first start", D5, MG5): before a region's game is started
 * for the first time, every party member goes into Cobblemon's PC, so the new story's starter has room and nothing is
 * lost or left out of a full-party script. The PC must hold them all: when it can't, nothing moves and the client hears
 * {@code error{code:"stash.pc_full"}}; otherwise the party is emptied into the PC's first free slots, both stores are
 * written, the next {@code party.sync} goes out, and {@code act.result{ok:true, kind:"stash", moved}} answers. A player
 * in a Cobblemon battle or held for a world build is refused ({@code stash.busy}). Logs carry codes and counts only.
 * Server thread only.
 */
public final class StashService {
	public static final String PC_FULL = "stash.pc_full";
	public static final String BUSY = "stash.busy";
	public static final String NO_PC = "stash.no_pc";
	private static final Set<String> FIELDS = Set.of("region");

	private static final Map<String, AtomicLong> REFUSED = new ConcurrentHashMap<>();
	private static final AtomicLong STASHED = new AtomicLong();
	private static final AtomicLong MOVED = new AtomicLong();

	private StashService() {
	}

	/** Call once from common init, after the relay and {@link PartyService#init}. */
	public static void init() {
		RelayService.get().register(MsgTypes.PARTY_STASH, StashService::onStash);
	}

	static void onStash(ServerPlayer player, Envelope message) {
		String region;
		try {
			region = DataReader.of(message.data(), "").allowOnly(FIELDS).string("region", DataReader.REGION_ID);
		} catch (DataException e) {
			refuse(player, "bad_message", message.seq());
			return;
		}
		Regions regions = Regions.of(player.server);
		if (regions != null && regions.byId(region) == null) {
			refuse(player, "bad_message", message.seq());
			return;
		}
		if (BuildHold.isHeld(player) || BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
			refuse(player, BUSY, message.seq());
			return;
		}
		PlayerPartyStore party;
		PCStore pc;
		try {
			party = PlayerExtensionsKt.party(player);
			pc = PlayerExtensionsKt.pc(player);
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("stash: Cobblemon has no stores for the player ({})", e.getClass().getName());
			refuse(player, NO_PC, message.seq());
			return;
		}
		int moved = stash(party, pc);
		if (moved < 0) {
			refuse(player, PC_FULL, message.seq());
			return;
		}
		if (moved > 0) {
			SaveService.savePokemon(player);
			PartyService.markDirty(player);
		}
		JsonObject data = new JsonObject();
		data.addProperty("ok", true);
		data.addProperty("kind", "stash");
		data.addProperty("moved", moved);
		Regions.tag(data, player.server, region);
		RelayService.get().send(player, MsgTypes.ACT_RESULT, data, message.seq());
		STASHED.incrementAndGet();
		MOVED.addAndGet(moved);
		KantoBridge.LOGGER.info("stash: {} party members went into the PC before a start", moved);
	}

	/**
	 * Moves every party member into the PC (recalled first when sent out). Returns how many moved, or -1 when the PC
	 * could not hold them all: everything is then back in the party (its order may differ).
	 */
	static int stash(PlayerPartyStore party, PCStore pc) {
		List<Pokemon> members = new ArrayList<>();
		for (Pokemon pokemon : party) {
			members.add(pokemon);
		}
		List<Pokemon> moved = new ArrayList<>();
		for (Pokemon pokemon : members) {
			if (pokemon.getEntity() != null) {
				// Sent out: back in its ball before it changes stores.
				pokemon.recall();
			}
			if (!party.remove(pokemon)) {
				continue;
			}
			if (pc.add(pokemon)) {
				moved.add(pokemon);
				continue;
			}
			// The PC is full: undo, nothing is lost.
			party.add(pokemon);
			for (Pokemon back : moved) {
				pc.remove(back);
				party.add(back);
			}
			return -1;
		}
		return moved.size();
	}

	private static void refuse(ServerPlayer player, String code, long seq) {
		long count = REFUSED.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		RelayService.get().sendError(player, code, seq);
		KantoBridge.LOGGER.info("stash: refused {} ({} so far)", code, count);
	}

	// ---- views (tests, diagnostics) ----

	public static long refused(String code) {
		AtomicLong count = REFUSED.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> refused() {
		Map<String, Long> out = new TreeMap<>();
		REFUSED.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	/** Stashes answered {@code ok}. */
	public static long stashed() {
		return STASHED.get();
	}

	/** Party members moved into the PC by stashes, in all. */
	public static long moved() {
		return MOVED.get();
	}
}
