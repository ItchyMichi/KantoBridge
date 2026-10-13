package dev.kantobridge.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's link manager (MG4, docs/specs/MULTI_GAME_PLAN.md §1 "Do I need a master bridge", §4.5): one
 * {@link BridgeLink} per hidden Gen1Recomp process, each on its own port, exactly one of them <b>followed</b> (movement,
 * screens, NPCs and story go through it) and the others <b>parked</b> (connected, heartbeat on, {@code party.sync} and
 * {@code dex.sync} still applied, their hidden player held still).
 *
 * <ul>
 * <li>{@link #body()} is the dispatcher the per-body runtimes subscribe to; it hears the followed link only.</li>
 * <li>{@link #follow} swaps the followed link: the body hears {@code DISCONNECTED(swap)} and the new link's state, the
 * {@link #onFollow} listeners relink the movement sync, a {@code resync} goes to the new link, the region bound to the
 * followed link changes, and {@code region.state} goes up.</li>
 * <li>{@link #onServer} routes a server message by its {@code region} to that region's link (untagged: the legacy
 * region's link, else the followed one; the syncs fan out to every link).</li>
 * <li>A link whose {@code hello.ok} names another game than configured ({@link BridgeLink#wrongGame}) is never followed
 * and no region's.</li>
 * <li>With no followed link chosen yet, the first connected, non-wrong link is followed (one legacy link: that one,
 * from the start, so the pause overlay works before it connects). The server's stored presence ({@code region.state}
 * on join) names the region to follow instead, when a link runs its game.</li>
 * </ul>
 *
 * Plain Java with injected ports, so it is tested without Minecraft; {@link BridgeRuntime} builds it. With one legacy
 * link everything behaves as before MG4: the body hears that link from the start, nothing is tagged in a legacy world,
 * and no {@code region.state} is sent there.
 */
public final class Links {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");

	/** Makes one link's client, listening through {@code listener} (the link's dispatcher). */
	@FunctionalInterface
	public interface ClientFactory {
		BridgeClient create(BridgeConfig config, BridgeClient.Listener listener);
	}

	/** The world's regions, as the client knows them ({@code client.world.ClientRegions}). */
	public interface RegionView {
		/** The region built from {@code game}'s export, or null (no game, no such region). */
		@Nullable String regionOf(@Nullable String game);

		/** The legacy region's id (the implicit {@code kanto} of a legacy world), or null before any index. */
		@Nullable String legacy();

		/** True in a region world ({@code world.regions} received): messages carry regions. */
		boolean regionWorld();

		/** The followed link's region, so the world index shim follows the link; null unbinds. */
		void followed(@Nullable String region);
	}

	/** The relay to the Minecraft server. */
	public interface ServerPort {
		/** Sends a message, tagged with {@code region} when it isn't null; the message's {@code seq}, or -1 when not sent. */
		long post(String type, JsonObject data, @Nullable Long re, @Nullable String region);

		/** True while a server connection can take messages. */
		boolean canSend();
	}

	/** Routes one server message to a link. */
	@FunctionalInterface
	public interface ServerHandler {
		void handle(BridgeLink link, Envelope message);
	}

	private static volatile @Nullable Links instance;

	private final Catalog catalog;
	private final BridgeClock clock;
	private final RegionView regions;
	private final ServerPort server;
	private final List<BridgeLink> links = new ArrayList<>();
	private final FollowedDispatcher body;
	private final List<BiConsumer<BridgeLink, Boolean>> followListeners = new CopyOnWriteArrayList<>();
	private final Map<String, Long> warnings = new TreeMap<>();
	private volatile BridgeLink followed;
	/** False until a link was followed for a reason (configured alone, the first to connect, asked for). */
	private boolean followedChosen;
	/** The region the server's stored presence asks to follow, until a link running its game is up. */
	private @Nullable String wantedRegion;
	private boolean started;
	/** The region bound as the followed one ({@link RegionView#followed}), re-checked every tick. */
	private @Nullable String boundRegion;
	/** The last {@code region.state} sent, as a key, so an unchanged presence costs nothing. */
	private @Nullable String sentPresence;
	private long follows;

	public Links(Catalog catalog, List<ClientConfig.Link> configured, ClientFactory clients, BridgeClock clock, RegionView regions,
			ServerPort server) {
		if (configured.isEmpty()) {
			throw new IllegalArgumentException("at least one link");
		}
		this.catalog = catalog;
		this.clock = clock;
		this.regions = regions;
		this.server = server;
		this.body = new FollowedDispatcher(catalog);
		for (ClientConfig.Link entry : configured) {
			Dispatcher dispatcher = new Dispatcher(catalog);
			BridgeClient client = clients.create(BridgeConfig.fromCatalog(catalog, entry.port()), dispatcher);
			// The link's own handlers first (its game, wrong_game), then the body's forwarders, then the follow decisions.
			BridgeLink link = new BridgeLink(entry, dispatcher, client, clock, regions, server);
			this.links.add(link);
			this.body.attach(link);
			dispatcher.onState((state, detail) -> this.linkState(link, state));
			dispatcher.on(MsgTypes.HELLO_OK, message -> this.helloOk(link));
		}
		this.followed = this.links.get(0);
		this.followedChosen = this.links.size() == 1;
		this.body.setFollowed(this.followed);
	}

	/** The running manager, or null before client init. */
	public static @Nullable Links get() {
		return instance;
	}

	static void install(@Nullable Links links) {
		instance = links;
	}

	// ---- links ----

	public List<BridgeLink> all() {
		return List.copyOf(this.links);
	}

	/** The link configured for (or learnt to run) {@code game}, or null. */
	public @Nullable BridgeLink byGame(@Nullable String game) {
		if (game == null) {
			return null;
		}
		for (BridgeLink link : this.links) {
			if (game.equals(link.game())) {
				return link;
			}
		}
		return null;
	}

	/** The followed link (never null). */
	public BridgeLink followed() {
		return this.followed;
	}

	/** Every other link. */
	public List<BridgeLink> parked() {
		List<BridgeLink> out = new ArrayList<>();
		for (BridgeLink link : this.links) {
			if (link != this.followed) {
				out.add(link);
			}
		}
		return out;
	}

	/** The link of the game {@code regionId}'s region was built from, or null (absent, or a wrong game). */
	public @Nullable BridgeLink forRegion(@Nullable String regionId) {
		if (regionId == null) {
			return null;
		}
		for (BridgeLink link : this.links) {
			if (!link.wrongGame() && regionId.equals(link.region())) {
				return link;
			}
		}
		return null;
	}

	/** The link bound to the legacy region (server messages without a {@code region} are its), or null. */
	public @Nullable BridgeLink legacy() {
		return this.forRegion(this.regions.legacy());
	}

	/** The body's dispatcher: the followed link's traffic. */
	public FollowedDispatcher body() {
		return this.body;
	}

	/** Called on each follow with the new link and whether a teleport is allowed (a started game's first room). */
	public void onFollow(BiConsumer<BridgeLink, Boolean> listener) {
		this.followListeners.add(listener);
	}

	/**
	 * Follows {@code game}'s link (client thread). False, with a counted warning, for an unknown game, a wrong-game link or
	 * one that isn't connected; true (and nothing else) when it is followed already.
	 *
	 * @param allowTeleport the movement sync may teleport onto the new hidden player's cell (a {@code start}); else it
	 *                      walks at most one ordinary step (a {@code resume})
	 */
	public boolean follow(String game, boolean allowTeleport) {
		BridgeLink next = this.byGame(game);
		if (next == null) {
			this.warn("links.follow_unknown");
			return false;
		}
		if (next.wrongGame()) {
			this.warn("links.follow_wrong_game");
			return false;
		}
		if (!next.connected()) {
			this.warn("links.follow_down");
			return false;
		}
		this.followedChosen = true;
		this.wantedRegion = null;
		if (next == this.followed) {
			return true;
		}
		this.swap(next, allowTeleport);
		return true;
	}

	private void swap(BridgeLink next, boolean allowTeleport) {
		BridgeLink previous = this.followed;
		this.followed = next;
		this.follows++;
		LOGGER.info("bridge: following {} (port {}) instead of {} (port {})", orDash(next.game()), next.port(), orDash(previous.game()),
				previous.port());
		this.bind(next.region());
		this.body.swap(next);
		for (BiConsumer<BridgeLink, Boolean> listener : this.followListeners) {
			try {
				listener.accept(next, allowTeleport);
			} catch (RuntimeException e) {
				LOGGER.warn("bridge: a follow listener failed with {}", e.getClass().getName());
			}
		}
		// A fresh snapshot re-seeds the body (puppets, screens, the story's full state).
		next.client().send(MsgTypes.RESYNC, new JsonObject());
	}

	/** A link's state changed (its dispatcher, client thread). */
	private void linkState(BridgeLink link, ConnectionState state) {
		if (state == ConnectionState.CONNECTED) {
			this.consider(link);
		}
	}

	/** A link's {@code hello.ok} was applied (after {@link BridgeLink}'s own handler): its game and wrong_game are known now. */
	private void helloOk(BridgeLink link) {
		if (link == this.followed && link.wrongGame()) {
			// The followed link runs another game: a good connected link takes over, else the next one to connect does.
			this.followedChosen = false;
			for (BridgeLink other : this.links) {
				if (other != link && other.connected() && !other.wrongGame()) {
					this.followedChosen = true;
					this.swap(other, false);
					return;
				}
			}
			return;
		}
		this.consider(link);
	}

	/** A link is up (or just said its game): it is followed when it is the wanted one, or when none was chosen yet. */
	private void consider(BridgeLink link) {
		if (!link.connected() || link.wrongGame()) {
			return;
		}
		boolean wanted = this.wantedRegion != null && this.wantedRegion.equals(link.region());
		if (!wanted && this.followedChosen) {
			return;
		}
		if (wanted) {
			this.wantedRegion = null;
		}
		this.followedChosen = true;
		if (link != this.followed) {
			this.swap(link, false);
		}
	}

	private void bind(@Nullable String region) {
		if (!Objects.equals(region, this.boundRegion)) {
			this.boundRegion = region;
			this.regions.followed(region);
		}
	}

	// ---- the server ----

	/**
	 * Registers the one handler for a server-to-client type and routes each message by its {@code region}: a tagged
	 * message to that region's link; an untagged one (or one tagged with a region no link runs, counted) to the legacy
	 * region's link, else the followed one.
	 *
	 * @param fanOutUntagged an untagged message goes to every link ({@code party.sync}, {@code dex.sync}: Cobblemon's
	 *                       state is every hidden game's)
	 */
	public void onServer(String type, boolean fanOutUntagged, ServerHandler handler) {
		ClientRelay.get().register(type, message -> this.route(message, fanOutUntagged, handler));
	}

	void route(Envelope message, boolean fanOutUntagged, ServerHandler handler) {
		String region = JsonFields.str(message.data(), "region");
		if (region != null) {
			BridgeLink target = this.forRegion(region);
			if (target != null) {
				handler.handle(target, message);
				return;
			}
			this.warn("links.no_link_for_region");
		}
		if (fanOutUntagged) {
			for (BridgeLink link : this.links) {
				handler.handle(link, message);
			}
			return;
		}
		BridgeLink target = this.legacy();
		handler.handle(target != null ? target : this.followed, message);
	}

	/**
	 * The server's stored presence ({@code region.state} S→C on join; a crossing's end, MG5): the region it names as
	 * followed is followed here when a connected link runs its game, else as soon as one is. Nothing changes with no
	 * {@code followed}.
	 */
	public void serverPresence(JsonObject data) {
		String region = JsonFields.str(data, "followed");
		if (region == null) {
			return;
		}
		this.wantedRegion = region;
		BridgeLink target = this.forRegion(region);
		if (target != null) {
			this.consider(target);
		}
	}

	// ---- ticks ----

	/** Starts every link's socket thread (once). */
	public void start() {
		if (this.started) {
			return;
		}
		this.started = true;
		for (BridgeLink link : this.links) {
			link.client().start();
		}
	}

	/** Runs the queued events of every link's dispatcher on the calling (client) thread; the followed link's first. */
	public int drain(int maxPerLink) {
		int count = this.followed.dispatcher().drain(maxPerLink);
		for (BridgeLink link : this.links) {
			if (link != this.followed) {
				count += link.dispatcher().drain(maxPerLink);
			}
		}
		return count;
	}

	/**
	 * Once per client tick: the followed link's region stays bound as the followed one (the regions may arrive after the
	 * link connected), a wanted region is followed once its link is up, and {@code region.state} goes up when the presence
	 * changed (a follow, a link connecting or dropping, the regions arriving), in a region world only (a legacy world
	 * sends today's bytes).
	 */
	public void tick() {
		if (this.wantedRegion != null) {
			BridgeLink wanted = this.forRegion(this.wantedRegion);
			if (wanted != null) {
				this.consider(wanted);
			}
		}
		this.bind(this.followed.region());
		if (!this.regions.regionWorld() || !this.server.canSend()) {
			return;
		}
		String key = this.presenceKey();
		if (key.equals(this.sentPresence)) {
			return;
		}
		if (this.server.post(MsgTypes.REGION_STATE, this.presence(), null, null) > 0) {
			this.sentPresence = key;
		}
	}

	/** The server joined or left: the presence goes again once it can, and a stored wish is forgotten. */
	public void serverChanged() {
		this.sentPresence = null;
		this.wantedRegion = null;
	}

	/** {@code region.state}'s data: the followed link's region and the connected parked links' regions. */
	public JsonObject presence() {
		JsonObject data = new JsonObject();
		String followedRegion = this.followedRegion();
		if (followedRegion != null) {
			data.addProperty("followed", followedRegion);
		}
		JsonArray parked = new JsonArray();
		for (String region : this.parkedRegions(followedRegion)) {
			parked.add(region);
		}
		data.add("parked", parked);
		return data;
	}

	private @Nullable String followedRegion() {
		return this.followed.connected() ? this.followed.region() : null;
	}

	private Set<String> parkedRegions(@Nullable String followedRegion) {
		Set<String> out = new LinkedHashSet<>();
		for (BridgeLink link : this.links) {
			String region = link.connected() ? link.region() : null;
			if (link != this.followed && region != null && !region.equals(followedRegion)) {
				out.add(region);
			}
		}
		return out;
	}

	private String presenceKey() {
		String followedRegion = this.followedRegion();
		return (followedRegion == null ? "-" : followedRegion) + "|" + String.join(",", this.parkedRegions(followedRegion));
	}

	/** Closes every link for good. */
	public void closeAll() {
		for (BridgeLink link : this.links) {
			link.client().close();
		}
	}

	public long follows() {
		return this.follows;
	}

	/** Counted refusals and oddities, by code. */
	public Map<String, Long> warnings() {
		return this.warnings;
	}

	public Catalog catalog() {
		return this.catalog;
	}

	public BridgeClock clock() {
		return this.clock;
	}

	/** Counts {@code code}; logs its first occurrence and every hundredth after it. */
	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		if (count == 1 || count % 100 == 0) {
			LOGGER.warn("bridge: {} ({} so far)", code, count);
		}
	}

	private static String orDash(@Nullable String value) {
		return value == null ? "-" : value;
	}

	/** One entry per link for the F7 overlay: followed/parked/wrong, game, port, state. */
	public String debugLine() {
		StringBuilder out = new StringBuilder();
		for (BridgeLink link : this.links) {
			if (out.length() > 0) {
				out.append("; ");
			}
			out.append(link == this.followed ? "followed" : link.wrongGame() ? "wrong_game" : "parked").append(' ')
					.append(orDash(link.game())).append(':').append(link.port()).append(' ')
					.append(link.client().state().name().toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}
}
