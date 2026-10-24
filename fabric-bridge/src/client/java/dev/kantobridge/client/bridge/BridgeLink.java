package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import dev.kantobridge.client.world.RegionTags;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.Games;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One hidden game's link (MG4, docs/specs/MULTI_GAME_PLAN.md §4.5): the TCP connection to one Gen1Recomp process and
 * everything that is per hidden game: its {@link BridgeClient}, its own {@link Dispatcher} and {@link ClientBridgeState},
 * its status line, the game it is configured for (or learnt from {@code hello.ok}), whether the process turned out to
 * run another game ({@link #wrongGame}: such a link is never followed), the region of the world built from its game, and
 * the last {@code game.status} it reported (the pass's choreography, MG5, reads it). Plain Java; the client thread owns
 * its mutable state (fed through the dispatcher).
 */
public final class BridgeLink {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");

	/** What the hidden game last said about its story (MULTI_GAME_PLAN §4.7). */
	public record Status(String playthrough, boolean ingame, boolean started, int badges, @Nullable String name, @Nullable Integer slot) {
		/** True when the game is past its title screen. */
		public boolean titled() {
			return "title".equals(this.playthrough);
		}
	}

	private final ClientConfig.Link configured;
	private final Dispatcher dispatcher;
	private final ClientBridgeState state = new ClientBridgeState();
	private final ConnectionStatus status = new ConnectionStatus();
	private final BridgeClock clock;
	private final Links.RegionView regions;
	private final Links.ServerPort server;
	private final BridgeClient client;
	private volatile @Nullable String learnt;
	private volatile boolean wrongGame;
	private volatile @Nullable String wrongGameSeen;
	private volatile @Nullable Status lastStatus;
	/** FP2 (FOREIGN_POKEMON_PLAN §4.1): the visitors the last game.status counted (-1: not said). */
	private volatile int visitors = -1;
	private long wrongGameCount;

	BridgeLink(ClientConfig.Link configured, Dispatcher dispatcher, BridgeClient client, BridgeClock clock, Links.RegionView regions,
			Links.ServerPort server) {
		this.configured = configured;
		this.dispatcher = dispatcher;
		this.client = client;
		this.clock = clock;
		this.regions = regions;
		this.server = server;
		this.state.attach(dispatcher);
		dispatcher.on(MsgTypes.HELLO_OK, this::onHelloOk);
		dispatcher.on(MsgTypes.GAME_STATUS, this::onGameStatus);
		dispatcher.onState((next, detail) -> {
			this.status.update(next, detail, clock.millis());
			if (next != ConnectionState.CONNECTED) {
				this.lastStatus = null;
				this.visitors = -1;
			}
		});
	}

	/** The game configured for this link, or null for a legacy link that learns it. */
	public @Nullable String configuredGame() {
		return this.configured.game();
	}

	/** The game this link runs: configured, or learnt from {@code hello.ok}; null until then. */
	public @Nullable String game() {
		String game = this.configured.game();
		if (game != null) {
			return game;
		}
		if (this.learnt != null) {
			return this.learnt;
		}
		// Before the dispatched hello.ok is drained, the client's own handshake already names the game (no race for callers
		// that see the link connected before the body's tick drains it).
		HelloOk ok = this.client.handshake();
		return ok != null ? ok.game() : null;
	}

	public int port() {
		return this.configured.port();
	}

	public BridgeClient client() {
		return this.client;
	}

	public Dispatcher dispatcher() {
		return this.dispatcher;
	}

	public ClientBridgeState state() {
		return this.state;
	}

	public ConnectionStatus status() {
		return this.status;
	}

	public boolean connected() {
		return this.client.state() == ConnectionState.CONNECTED;
	}

	/**
	 * True when the process on this port runs another game than the link is configured for (or one the catalogue doesn't
	 * know): the link is never followed and its region is nobody's. Cleared by the next matching {@code hello.ok}.
	 */
	public boolean wrongGame() {
		return this.wrongGame;
	}

	/** The game {@code hello.ok} named while {@link #wrongGame}, for the HUD; null otherwise. */
	public @Nullable String wrongGameSeen() {
		return this.wrongGameSeen;
	}

	/** The region of the world built from this link's game, or null (no such region, no game yet, or wrong game). */
	public @Nullable String region() {
		if (this.wrongGame) {
			return null;
		}
		return this.regions.regionOf(this.game());
	}

	/** The last {@code game.status} this link reported since it connected, or null. */
	public @Nullable Status lastStatus() {
		return this.lastStatus;
	}

	/**
	 * Sends one of this link's messages to the server, tagged with its region in a region world (MULTI_GAME_PLAN §4.4: the
	 * client tags every message it relays with its link's region; a legacy world sends today's bytes).
	 */
	public boolean toServer(String type, JsonObject data, @Nullable Long re) {
		return this.postToServer(type, data, re) > 0;
	}

	/** {@link #toServer}, returning the message's {@code seq} or -1. */
	public long postToServer(String type, JsonObject data, @Nullable Long re) {
		String region = this.regions.regionWorld() && RegionTags.tagged(type) ? this.region() : null;
		return this.server.post(type, data, re, region);
	}

	private void onHelloOk(Envelope message) {
		String game = JsonFields.str(message.data(), "game");
		if (game == null) {
			return;
		}
		String expected = this.configured.game();
		if (expected == null) {
			this.learnt = game;
			this.wrongGame = false;
			this.wrongGameSeen = null;
			return;
		}
		boolean wrong = !expected.equals(game) || !Games.known(game);
		if (wrong && !this.wrongGame) {
			this.wrongGameCount++;
			LOGGER.warn("bridge: port {} runs {}, expected {}: that link is never followed", this.port(), game, expected);
		}
		this.wrongGame = wrong;
		this.wrongGameSeen = wrong ? game : null;
	}

	private void onGameStatus(Envelope message) {
		JsonObject data = message.data();
		String playthrough = JsonFields.str(data, "playthrough");
		if (playthrough == null) {
			return;
		}
		long slot = JsonFields.integer(data, "slot", -1);
		long seen = JsonFields.integer(data, "visitors", -1);
		this.visitors = seen < 0 || seen > 6 ? -1 : (int) seen;
		this.lastStatus = new Status(playthrough, JsonFields.bool(data, "ingame", false), JsonFields.bool(data, "started", false),
				(int) Math.max(0, Math.min(16, JsonFields.integer(data, "badges", 0))), JsonFields.str(data, "name"),
				slot < 0 ? null : (int) slot);
	}

	/** How many times a {@code hello.ok} named another game than configured. */
	public long wrongGameCount() {
		return this.wrongGameCount;
	}

	/** FP2: how many party members this game shows as visitors (-1 when it didn't say). */
	public int visitors() {
		return this.visitors;
	}

	/** FP2 (§4.1): the HUD's visitor line for the followed link: how many members its game shows as visitors, or null. */
	public ConnectionStatus.@Nullable Line visitorLine() {
		int n = this.visitors;
		if (n <= 0 || !this.connected()) {
			return null;
		}
		return new ConnectionStatus.Line("kantobridge.hud.visitors", java.util.List.of(label(this.game()), Integer.toString(n)), false);
	}

	/** The HUD's line for this link while it isn't the followed one, or null when there is nothing to say. */
	public ConnectionStatus.@Nullable Line hudLine() {
		if (this.wrongGame) {
			String expected = this.configured.game();
			return new ConnectionStatus.Line("kantobridge.hud.link.wrong_game",
					java.util.List.of(Integer.toString(this.port()), label(this.wrongGameSeen), label(expected)), true);
		}
		if (!this.connected()) {
			return new ConnectionStatus.Line("kantobridge.hud.link.parked_down", java.util.List.of(label(this.game()), Integer.toString(this.port())),
					true);
		}
		return null;
	}

	/** A game's catalogue label, else its id, else "?". */
	static String label(@Nullable String game) {
		if (game == null) {
			return "?";
		}
		String label = Games.label(game);
		return label == null ? game : label;
	}

	long clockMillis() {
		return this.clock.millis();
	}

	@Override
	public String toString() {
		return "BridgeLink[" + (this.game() == null ? "?" : this.game()) + ":" + this.port() + "]";
	}
}
