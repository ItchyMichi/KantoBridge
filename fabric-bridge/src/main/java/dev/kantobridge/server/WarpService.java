package dev.kantobridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Teleports a player to a cell for their client (docs/PLAN.md §2.4 flow 7, §4.4 {@code WarpService}): {@code
 * warp.request{to, kind?}} → the centre of the cell's 2×2 footprint at its map's feet level, in the same level, facing
 * {@code to.facing} → {@code warp.done{ok, to}} (with {@code re}). Hidden-game warps, the client's sync on join and
 * drift corrections all come this way; a request without {@code kind} is such a sync.
 *
 * <p>Relay authority: a request only ever moves the player whose client sent it; there is no target field. Requests
 * for maps the world doesn't have, cells outside their map, or malformed places are refused with {@code ok:false} and
 * a counted warning (codes only). Server thread only.
 */
public final class WarpService {
	/** Why a request was refused. */
	public enum Refusal {
		/** {@code to} is missing or malformed. */
		BAD_REQUEST("bad_request"),
		/** The server has no WorldIndex for the region (not a built Kanto world, or a region this world lacks). */
		NO_INDEX("no_index"),
		/** The map isn't in this world. */
		UNKNOWN_MAP("unknown_map"),
		/** The cell lies outside its map. */
		BAD_CELL("bad_cell"),
		/** The player waits for a world build ({@link BuildHold}). */
		HELD("held");

		private final String code;

		Refusal(String code) {
			this.code = code;
		}

		public String code() {
			return this.code;
		}
	}

	/** A teleport's outcome: where the player now stands, or why nothing happened. */
	public record Result(@Nullable Vec3 at, @Nullable Refusal refusal) {
		static Result refused(Refusal refusal) {
			return new Result(null, refusal);
		}

		public boolean ok() {
			return this.refusal == null;
		}
	}

	private static final Set<String> FIELDS = Set.of("to", "kind", "region");
	/** protocol/defs.schema.json {@code warpKind}. */
	private static final Set<String> KINDS = Set.of("door", "stairs", "edge", "hole", "pad", "fly", "teleport", "dig", "rope",
			"blackout", "script", "elevator", "ladder", "escalator", "dive");
	private static final Map<Refusal, AtomicLong> REFUSED = new ConcurrentHashMap<>();
	private static final AtomicLong TELEPORTS = new AtomicLong();

	private WarpService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.WARP_REQUEST, WarpService::onRequest);
	}

	static void onRequest(ServerPlayer player, Envelope message) {
		JsonObject data = message.data();
		Place to = wellFormed(data) ? Place.fromJson(data.get("to")) : null;
		// MG3: the map is looked up in the region the request names, else the one the player stands in (§4.6)
		Result result = to == null ? Result.refused(Refusal.BAD_REQUEST)
				: teleport(player, Regions.currentIndexFor(player, data.has("region") ? data.get("region").getAsString() : null), to);
		JsonObject reply = new JsonObject();
		reply.addProperty("ok", result.ok());
		if (result.ok()) {
			reply.add("to", to.toJson());
		} else {
			long count = REFUSED.computeIfAbsent(result.refusal(), key -> new AtomicLong()).incrementAndGet();
			KantoBridge.LOGGER.warn("warp: refused a request ({}); {} refused so far for this reason", result.refusal().code(), count);
		}
		RelayService.get().send(player, MsgTypes.WARP_DONE, reply, message.seq());
	}

	/** Moves {@code player} to the centre of {@code to}'s cell at its map's feet level, facing {@code to.facing}. */
	public static Result teleport(ServerPlayer player, @Nullable WorldIndex index, Place to) {
		if (BuildHold.isHeld(player)) {
			return Result.refused(Refusal.HELD);
		}
		if (index == null) {
			return Result.refused(Refusal.NO_INDEX);
		}
		MapPlacement map = index.map(to.map());
		if (map == null) {
			return Result.refused(Refusal.UNKNOWN_MAP);
		}
		if (!map.containsCell(to.x(), to.y())) {
			return Result.refused(Refusal.BAD_CELL);
		}
		// a two-floor cell's floor by the hidden game's elevation (D7: under a bridge, or on its deck)
		Vec3 centre = index.toBlock(to.map(), to.x(), to.y(), to.elev());
		// BIKE_PLAN D9: a rider keeps a bike: this one goes, and a fresh one is under the player at the destination the next
		// tick (a vehicle its rider's client moves ignores the server's teleports, so the old one can't simply follow)
		if (Bikes.bikeOf(player) != null) {
			Bikes.dismount(player);
			Bikes.reseatSoon(player);
		}
		// Same level: interiors are layers of the overworld, so this is a plain position packet, no loading screen.
		player.teleportTo(player.serverLevel(), centre.x, centre.y, centre.z, Set.of(), to.facing().yaw(), player.getXRot());
		player.resetFallDistance();
		TELEPORTS.incrementAndGet();
		KantoBridge.LOGGER.debug("warp: teleported a player to {}", to.map());
		return new Result(centre, null);
	}

	/** Refused requests so far, by code (for tests and diagnostics). */
	public static Map<String, Long> refusals() {
		Map<String, Long> out = new TreeMap<>();
		REFUSED.forEach((refusal, count) -> out.put(refusal.code(), count.get()));
		return out;
	}

	public static long refusals(Refusal refusal) {
		AtomicLong count = REFUSED.get(refusal);
		return count == null ? 0 : count.get();
	}

	public static long teleports() {
		return TELEPORTS.get();
	}

	/**
	 * The schema's rules for the message around {@code to}: no other fields, {@code kind} one of the warp kinds, and
	 * {@code region} (MG3) a region id.
	 */
	private static boolean wellFormed(JsonObject data) {
		for (String key : data.keySet()) {
			if (!FIELDS.contains(key)) {
				return false;
			}
		}
		if (data.has("region")) {
			JsonElement region = data.get("region");
			if (!region.isJsonPrimitive() || !region.getAsJsonPrimitive().isString() || !DataReader.REGION_ID.matcher(region.getAsString()).matches()) {
				return false;
			}
		}
		if (!data.has("kind")) {
			return true;
		}
		JsonElement kind = data.get("kind");
		return kind.isJsonPrimitive() && kind.getAsJsonPrimitive().isString() && KINDS.contains(kind.getAsString());
	}
}
