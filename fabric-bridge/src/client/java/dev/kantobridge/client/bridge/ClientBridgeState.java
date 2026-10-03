package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import org.jetbrains.annotations.Nullable;

/**
 * What the client knows about the hidden game: the handshake, whether its player is live ({@link #ingame}), and the
 * player's map, cell, facing and mode from {@code snapshot}, {@code player.pos}, {@code player.state} and {@code warp}.
 * Client thread only (fed by {@link Dispatcher}); the movement sync ({@code client.move}) reads it.
 */
public final class ClientBridgeState {
	/** {@code hello.ok.tested}'s format (a version only). */
	private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("^[0-9A-Za-z.+_-]{1,32}$");

	private @Nullable HelloOk handshake;
	private @Nullable String map;
	private int x;
	private int y;
	private @Nullable String facing;
	private @Nullable String moveKind;
	private String mode = "unknown";
	private boolean bike;
	private boolean surf;
	private boolean slope;
	private boolean hidden;
	private boolean dark;
	private boolean lit;
	private @Nullable String warpPhase;
	private @Nullable String warpKind;
	private long snapshots;
	private long positions;
	/** The hidden game is in its overworld, so its player position is live (snapshot {@code ingame}). */
	private boolean ingame;

	/** Subscribes to the messages this state follows. */
	public void attach(Dispatcher dispatcher) {
		dispatcher.on(MsgTypes.HELLO_OK, this::onHelloOk);
		dispatcher.on(MsgTypes.SNAPSHOT, this::onSnapshot);
		dispatcher.on(MsgTypes.PLAYER_POS, this::onPlayerPos);
		dispatcher.on(MsgTypes.PLAYER_STATE, message -> this.applyState(message.data()));
		dispatcher.on(MsgTypes.WARP, this::onWarp);
		dispatcher.onState((state, detail) -> {
			if (state != ConnectionState.CONNECTED) {
				this.handshake = null;
				this.ingame = false;
			}
		});
	}

	void onHelloOk(Envelope message) {
		JsonObject data = message.data();
		String session = JsonFields.str(data, "session");
		String game = JsonFields.str(data, "game");
		String engine = JsonFields.str(data, "engine");
		String bridge = JsonFields.str(data, "bridge");
		if (session != null && game != null && engine != null && bridge != null) {
			String tested = JsonFields.str(data, "tested");
			this.handshake = new HelloOk(session, game, engine, bridge, tested != null && VERSION.matcher(tested).matches() ? tested : null);
		}
	}

	void onSnapshot(Envelope message) {
		this.snapshots++;
		JsonObject data = message.data();
		this.ingame = JsonFields.bool(data, "ingame", false);
		JsonObject player = JsonFields.obj(data, "player");
		if (player != null) {
			this.applyPlace(player);
			this.moveKind = "sync";
		}
		JsonObject state = JsonFields.obj(data, "state");
		if (state != null) {
			this.applyState(state);
		}
	}

	void onPlayerPos(Envelope message) {
		this.positions++;
		JsonObject data = message.data();
		this.applyPlace(data);
		this.moveKind = JsonFields.str(data, "kind");
		// gen1-bridge reports positions only from its overworld: the title screen is behind.
		this.ingame = true;
	}

	void onWarp(Envelope message) {
		JsonObject data = message.data();
		this.warpPhase = JsonFields.str(data, "phase");
		this.warpKind = JsonFields.str(data, "kind");
		JsonObject to = JsonFields.obj(data, "to");
		if (to != null) {
			this.applyPlace(to);
			this.ingame = true;
		}
	}

	private void applyPlace(JsonObject place) {
		String nextMap = JsonFields.str(place, "map");
		if (nextMap == null) {
			return;
		}
		this.map = nextMap;
		this.x = (int) JsonFields.integer(place, "x", this.x);
		this.y = (int) JsonFields.integer(place, "y", this.y);
		String nextFacing = JsonFields.str(place, "facing");
		if (nextFacing != null) {
			this.facing = nextFacing;
		}
	}

	private void applyState(JsonObject state) {
		String nextMode = JsonFields.str(state, "mode");
		if (nextMode != null) {
			this.mode = nextMode;
		}
		this.bike = JsonFields.bool(state, "bike", this.bike);
		this.surf = JsonFields.bool(state, "surf", this.surf);
		this.slope = JsonFields.bool(state, "slope", this.slope);
		this.hidden = JsonFields.bool(state, "hidden", this.hidden);
		this.dark = JsonFields.bool(state, "dark", this.dark);
		this.lit = JsonFields.bool(state, "lit", this.lit);
	}

	public @Nullable HelloOk handshake() {
		return this.handshake;
	}

	/** True once a snapshot or position has given the player's place. */
	public boolean hasPosition() {
		return this.map != null;
	}

	public @Nullable String map() {
		return this.map;
	}

	public int x() {
		return this.x;
	}

	public int y() {
		return this.y;
	}

	public @Nullable String facing() {
		return this.facing;
	}

	/** The {@code kind} of the last position change ({@code walk}, {@code script}, …; {@code sync} for a snapshot). */
	public @Nullable String moveKind() {
		return this.moveKind;
	}

	public String mode() {
		return this.mode;
	}

	public boolean bike() {
		return this.bike;
	}

	public boolean surf() {
		return this.surf;
	}

	public boolean slope() {
		return this.slope;
	}

	public boolean hidden() {
		return this.hidden;
	}

	public boolean dark() {
		return this.dark;
	}

	public boolean lit() {
		return this.lit;
	}

	public @Nullable String warpPhase() {
		return this.warpPhase;
	}

	public @Nullable String warpKind() {
		return this.warpKind;
	}

	/**
	 * True when the hidden player's position is live: the last snapshot said {@code ingame}, or a position or warp
	 * arrival came since. False at the hidden game's title screen and while disconnected.
	 */
	public boolean ingame() {
		return this.ingame;
	}

	public long snapshots() {
		return this.snapshots;
	}

	public long positions() {
		return this.positions;
	}
}
