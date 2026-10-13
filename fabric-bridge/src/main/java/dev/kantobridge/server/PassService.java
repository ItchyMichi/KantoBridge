package dev.kantobridge.server;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.WorldIndex;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The pass service (docs/specs/MULTI_GAME_PLAN.md §4.8, MG5): crossings between regions through a connecting route's
 * gate houses. The decisions are {@link PassCrossings}' (plain Java); this class asks the published indices, the
 * player's {@link RegionPresenceService presence} and {@link ProgressService progress}, and carries the actions out:
 * {@code route.fade} on an accepted {@code pass.cross}, the teleport into the far gate house when the client's
 * {@code region.state} names the far region ({@link RegionPresenceService#apply} calls {@link #onRegionState}),
 * {@code error{code:"pass.timeout"}} on a {@code pass.abort} or when a crossing takes longer than 15 s. Refusals are
 * {@code error} codes ({@code pass.no_region}, {@code pass.not_built}, {@code pass.no_link}, {@code pass.busy}). Logs
 * carry codes and counts only. Server thread only.
 */
public final class PassService {
	/** A connecting route's id (protocol defs {@code routeId}). */
	static final Pattern ROUTE_ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	static final Pattern REASON = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
	private static final Set<String> CROSS_FIELDS = Set.of("route", "dir");
	private static final Set<String> ABORT_FIELDS = Set.of("route", "reason");
	private static final Set<String> DIRS = Set.of("to", "from");

	private static final Map<MinecraftServer, PassCrossings> CROSSINGS = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<String, AtomicLong> REFUSED = new ConcurrentHashMap<>();
	private static final AtomicLong ACCEPTED = new AtomicLong();
	private static final AtomicLong TELEPORTS = new AtomicLong();
	private static final AtomicLong RELINKS = new AtomicLong();
	private static final AtomicLong TIMEOUTS = new AtomicLong();
	private static final AtomicLong ABORTED = new AtomicLong();

	private PassService() {
	}

	/** Call once from common init, after the relay and the region services. */
	public static void init() {
		RelayService.get().register(MsgTypes.PASS_CROSS, PassService::onCross);
		RelayService.get().register(MsgTypes.PASS_ABORT, PassService::onAbort);
		ServerTickEvents.END_SERVER_TICK.register(PassService::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> crossings(server).forget(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(CROSSINGS::remove);
	}

	/** The crossings of one server (made on first use). */
	public static PassCrossings crossings(MinecraftServer server) {
		return CROSSINGS.computeIfAbsent(server, s -> new PassCrossings(new WorldLookup(s)));
	}

	static void onCross(ServerPlayer player, Envelope message) {
		String route;
		String dir;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(CROSS_FIELDS);
			route = reader.string("route", ROUTE_ID);
			dir = reader.string("dir");
			if (!DIRS.contains(dir)) {
				throw new DataException(reader.path("dir"), "not an end");
			}
		} catch (DataException e) {
			refuse(player, PassCrossings.BAD_MESSAGE, message.seq());
			return;
		}
		MinecraftServer server = player.server;
		PassCrossings.Decision decision = crossings(server).cross(player.getUUID(), route, dir, player.getX(), player.getY(), player.getZ(),
				server.getTickCount());
		if (decision instanceof PassCrossings.Refused refused) {
			refuse(player, refused.code(), message.seq());
			return;
		}
		PassCrossings.Accepted accepted = (PassCrossings.Accepted) decision;
		JsonObject fade = new JsonObject();
		fade.addProperty("ticks", PassCrossings.FADE_TICKS);
		Regions.tag(fade, server, accepted.from());
		RelayService.get().send(player, MsgTypes.ROUTE_FADE, fade, null);
		long count = ACCEPTED.incrementAndGet();
		KantoBridge.LOGGER.info("pass: a crossing from {} to {} begins ({}; {} so far)", accepted.from(), accepted.to(),
				accepted.start() ? "the far game starts" : "a resume", count);
	}

	static void onAbort(ServerPlayer player, Envelope message) {
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(ABORT_FIELDS);
			reader.string("route", ROUTE_ID);
			reader.string("reason", REASON);
		} catch (DataException e) {
			RelayService.get().sendError(player, PassCrossings.BAD_MESSAGE, message.seq());
			return;
		}
		if (crossings(player.server).abort(player.getUUID())) {
			ABORTED.incrementAndGet();
			RelayService.get().sendError(player, PassCrossings.TIMEOUT, null);
			KantoBridge.LOGGER.info("pass: a crossing was aborted by the client");
		}
	}

	/**
	 * The client's {@code region.state} (called by {@link RegionPresenceService#apply}): when it ends a crossing, the player
	 * is put into the far half's gate house; after a start the client's own relink places them.
	 */
	public static void onRegionState(ServerPlayer player, RegionPresence presence) {
		PassCrossings.Move move = crossings(player.server).onRegionState(player.getUUID(), presence.followed());
		if (move instanceof PassCrossings.Teleport teleport) {
			player.teleportTo(player.serverLevel(), teleport.x(), teleport.y(), teleport.z(), Set.of(), teleport.yaw(), player.getXRot());
			player.resetFallDistance();
			TELEPORTS.incrementAndGet();
			KantoBridge.LOGGER.info("pass: a crossing arrived in {}", presence.followed());
		} else if (move instanceof PassCrossings.Relink) {
			RELINKS.incrementAndGet();
			KantoBridge.LOGGER.info("pass: a crossing started the far game; the client places the player");
		}
		if (!(move instanceof PassCrossings.None)) {
			// One trainer economy (MG6): the game now followed gets the player's wallet and items.
			LedgerService.onFollowed(player, presence.followed());
		}
	}

	private static void tick(MinecraftServer server) {
		PassCrossings crossings = CROSSINGS.get(server);
		if (crossings == null || crossings.crossings().isEmpty()) {
			return;
		}
		for (UUID id : crossings.tick(server.getTickCount())) {
			TIMEOUTS.incrementAndGet();
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				RelayService.get().sendError(player, PassCrossings.TIMEOUT, null);
			}
			KantoBridge.LOGGER.warn("pass: a crossing timed out ({} so far)", TIMEOUTS.get());
		}
	}

	private static void refuse(ServerPlayer player, String code, long seq) {
		long count = REFUSED.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		RelayService.get().sendError(player, code, seq);
		KantoBridge.LOGGER.info("pass: refused {} ({} so far)", code, count);
	}

	/** The published world as {@link PassCrossings} needs it. */
	static final class WorldLookup implements PassCrossings.Lookup {
		private final MinecraftServer server;

		WorldLookup(MinecraftServer server) {
			this.server = server;
		}

		@Override
		public PassCrossings.@Nullable Half standing(UUID player, double x, double y, double z) {
			Regions regions = Regions.of(this.server);
			if (regions == null) {
				return null;
			}
			RegionRecord region = regions.at(Mth.floor(x), Mth.floor(z));
			WorldIndex index = region == null ? null : regions.indexOf(region.id());
			if (index == null) {
				return null;
			}
			WorldIndex.Zone zone = index.zoneAt(Mth.floor(x), Mth.floor(z));
			return zone == null ? null : halfOf(region.id(), index, zone);
		}

		@Override
		public boolean regionExists(String region) {
			Regions regions = Regions.of(this.server);
			return regions != null && regions.byId(region) != null;
		}

		@Override
		public boolean regionBuilt(String region) {
			Regions regions = Regions.of(this.server);
			return regions != null && regions.indexOf(region) != null;
		}

		@Override
		public PassCrossings.@Nullable Half half(String route, String region) {
			Regions regions = Regions.of(this.server);
			WorldIndex index = regions == null ? null : regions.indexOf(region);
			if (index == null) {
				return null;
			}
			for (WorldIndex.Zone zone : index.zones()) {
				WorldIndex.Pass pass = zone.pass();
				if (pass != null && pass.route().equals(route)) {
					return halfOf(region, index, zone);
				}
			}
			return null;
		}

		@Override
		public boolean hasLink(UUID player, String region) {
			ServerPlayer online = this.server.getPlayerList().getPlayer(player);
			return online != null && RegionPresenceService.hasLink(online, region);
		}

		@Override
		public @Nullable String playthrough(UUID player, String region) {
			ServerPlayer online = this.server.getPlayerList().getPlayer(player);
			if (online == null || !ProgressService.all(online).byRegion().containsKey(region)) {
				return null;
			}
			return ProgressService.of(online, region).playthrough();
		}

		/** The half a zone with a pass and a gate house describes, or null for any other zone. */
		static PassCrossings.@Nullable Half halfOf(String region, WorldIndex index, WorldIndex.Zone zone) {
			WorldIndex.Pass pass = zone.pass();
			WorldIndex.GateHouse house = zone.gateHouse();
			MapPlacement routeMap = index.map(zone.routeMap());
			if (pass == null || house == null || routeMap == null || pass.cells().isEmpty()) {
				return null;
			}
			return new PassCrossings.Half(region, zone.id(), pass.route(), pass.end(), pass.toRegion(), routeMap.x(), zone.y(), routeMap.z(),
					pass.cells(), house.facing());
		}
	}

	// ---- views (tests, diagnostics) ----

	/** True while the player's crossing is under way. */
	public static boolean inProgress(ServerPlayer player) {
		PassCrossings crossings = CROSSINGS.get(player.server);
		return crossings != null && crossings.crossing(player.getUUID()) != null;
	}

	public static long accepted() {
		return ACCEPTED.get();
	}

	public static long refused(String code) {
		AtomicLong count = REFUSED.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> refused() {
		Map<String, Long> out = new TreeMap<>();
		REFUSED.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	public static long teleports() {
		return TELEPORTS.get();
	}

	public static long relinks() {
		return RELINKS.get();
	}

	public static long timeouts() {
		return TIMEOUTS.get();
	}

	public static long aborted() {
		return ABORTED.get();
	}

	/** Tests: forget every crossing of {@code server}. */
	public static void reset(MinecraftServer server) {
		CROSSINGS.remove(server);
	}

	/** Tests: the halves {@code route} has in the published world, by region id. */
	public static List<String> halvesOf(MinecraftServer server, String route) {
		Regions regions = Regions.of(server);
		if (regions == null) {
			return List.of();
		}
		List<String> out = new java.util.ArrayList<>();
		for (String region : regions.ids()) {
			if (new WorldLookup(server).half(route, region) != null) {
				out.add(region);
			}
		}
		return out;
	}
}
