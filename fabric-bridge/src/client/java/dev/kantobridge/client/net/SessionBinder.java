package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The save pairing's client side (docs/PLAN.md §4.1 "Saving and sessions", §4.4 {@code SessionService}; DESIGN §8):
 * gen1-bridge hears {@code session.bind{world, multiplayer}} once the player is in a Kanto world whose id is known, and
 * again after every (re)connect, so the hidden game can load or create the save slot paired with that world; its answer
 * {@code session.bound{slot}} is kept (F7) and, once the protocol gives that type a {@code c2s} leg, relayed to the
 * server's pairing record.
 *
 * <p>{@code multiplayer} is the client's own view: false on the integrated server it runs itself (singleplayer, or a
 * LAN world it hosts), true on any other server. A change of world or of that view binds again. Plain Java, client
 * thread only; {@link SessionRuntime} is the glue.
 *
 * <p>M9: gen1-bridge may switch the hidden game to the world's own save slot first and answer much later; its
 * {@code session.bound} carries {@code re}, the bind's {@code seq}, so an answer to an older bind (the player moved on
 * to another world meanwhile) is told apart and ignored. {@link SessionGate} holds the world's data until the answer.
 *
 * <p>M10: {@code session.bound} may say how the hidden game reached the slot ({@code switched}: {@code none},
 * {@code title}, {@code saved}, {@code moved}); the binder keeps it ({@link #switched}) for the gate.
 *
 * <p>FOREIGN_POKEMON_PLAN §4.15 (FP1): the bind carries {@code incompatible}, the link's region record's
 * {@code rules.compat.onIncompatible} ({@code visitor} or {@code leave_out}), once the world's regions are known; a change
 * binds again. Absent (a legacy world, or before the regions arrive) means {@code visitor} to gen1-bridge.
 */
public final class SessionBinder {
	/** Where messages go. */
	public interface Port {
		/** To gen1-bridge: the message's {@code seq}, or -1 when not connected (then the message is dropped). */
		long toBridge(String type, JsonObject data);

		/** To the server relay; false when there is no server link. */
		boolean toServer(String type, JsonObject data);

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long binds;
		public long bounds;
		public long boundsRelayed;
	}

	private final Port port;
	private final boolean boundUp;
	private final Stats stats = new Stats();
	private final Map<String, Long> warnings = new TreeMap<>();
	/** What the glue last said, so an unchanged world costs nothing. */
	private @Nullable String input;
	private boolean inputMultiplayer;
	private @Nullable String world;
	private boolean multiplayer;
	private boolean connected;
	/** Counts connections to gen1-bridge; each gets its own bind. */
	private long link;
	private long sentLink = -1;
	/** The last bind's {@code seq} on its link (-1: none). */
	private long sentSeq = -1;
	private @Nullable String sentWorld;
	private boolean sentMultiplayer;
	/** FP1: the region's onIncompatible to send (null: none), and the last one sent. */
	private @Nullable String incompatible;
	private @Nullable String sentIncompatible;
	private @Nullable Integer slot;
	/** The last accepted answer's {@code switched}, or null when it didn't say. */
	private @Nullable String switched;
	/** WS2/WS3: the last accepted answer's {@code save}, {@code started} and {@code parked}. */
	private @Nullable String save;
	private @Nullable String started;
	private boolean parked;

	/**
	 * @param boundUp relay {@code session.bound} to the server (the protocol's catalog decides)
	 */
	/** {@code session.bound.switched}'s values. */
	static final Set<String> SWITCHES = Set.of("none", "title", "saved", "moved");
	/** WS2/WS3: {@code session.bound}'s fields, {@code save}'s and {@code started}'s values. */
	static final Set<String> BOUND_FIELDS = Set.of("slot", "switched", "save", "started", "parked");
	static final Set<String> SAVES = Set.of("current", "copy", "new");
	static final Set<String> STARTED = Set.of("continue", "new", "none");

	public SessionBinder(Port port, boolean boundUp) {
		this.port = port;
		this.boundUp = boundUp;
	}

	/** A new connection to gen1-bridge: it gets the bind again. */
	public void linkUp() {
		this.connected = true;
		this.link++;
		this.slot = null;
	}

	public void linkDown() {
		this.connected = false;
	}

	/** The world the player is in: its id (null outside a Kanto world, or while unknown) and the multiplayer view. */
	public void world(@Nullable String world, boolean multiplayer) {
		if (Objects.equals(world, this.input) && multiplayer == this.inputMultiplayer) {
			return;
		}
		this.input = world;
		this.inputMultiplayer = multiplayer;
		String valid = world != null && Ids.ID.matcher(world).matches() ? world : null;
		if (world != null && valid == null) {
			this.warn("session.bad_world");
		}
		this.world = valid;
		this.multiplayer = valid != null && multiplayer;
		this.slot = null;
	}

	/**
	 * WORLD_SESSIONS_PLAN WS1 (the cause WS0 found): the player left the world (Minecraft's disconnect). The next entry
	 * into any world, the same one included, binds again on this link: without this a world reopened while gen1-bridge
	 * stayed connected was never bound, so the pairing's gate held the player for good.
	 */
	public void leftWorld() {
		this.world(null, false);
		this.sentLink = -1;
		this.sentSeq = -1;
		this.sentWorld = null;
		this.sentIncompatible = null;
		this.slot = null;
	}

	/** FP1: the link's region's onIncompatible ({@code visitor}, {@code leave_out}; anything else or null: none). */
	public void incompatible(@Nullable String value) {
		this.incompatible = value != null && INCOMPATIBLE.contains(value) ? value : null;
	}

	/** {@code session.bind.incompatible}'s values. */
	static final Set<String> INCOMPATIBLE = Set.of("visitor", "leave_out");

	/** Each client tick: binds when this link hasn't heard about this world (or its region's choice) yet. */
	public void tick() {
		String current = this.world;
		if (!this.connected || current == null) {
			return;
		}
		if (this.sentLink == this.link && current.equals(this.sentWorld) && this.multiplayer == this.sentMultiplayer
				&& Objects.equals(this.incompatible, this.sentIncompatible)) {
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("world", current);
		data.addProperty("multiplayer", this.multiplayer);
		if (this.incompatible != null) {
			data.addProperty("incompatible", this.incompatible);
		}
		long seq = this.port.toBridge(MsgTypes.SESSION_BIND, data);
		if (seq >= 0) {
			this.sentLink = this.link;
			this.sentSeq = seq;
			this.sentWorld = current;
			this.sentMultiplayer = this.multiplayer;
			this.sentIncompatible = this.incompatible;
			this.slot = null;
			this.stats.binds++;
		}
	}

	/** The same bind again on this link at the next tick (gen1-bridge refused it; it may succeed now). */
	public void rebind() {
		this.sentLink = -1;
	}

	/** {@code session.bound{slot}} from gen1-bridge, without its {@code re}. */
	public void onBound(JsonObject data) {
		this.onBound(data, null);
	}

	/**
	 * {@code session.bound{slot}} from gen1-bridge; {@code re} is the bind it answers (null: not said). Returns true when it
	 * answers this link's latest bind for the current world.
	 */
	public boolean onBound(JsonObject data, @Nullable Long re) {
		long value = JsonFields.integer(data, "slot", -1);
		String how = JsonFields.str(data, "switched");
		boolean switchedOk = data.has("switched") ? how != null && SWITCHES.contains(how) : how == null;
		// WORLD_SESSIONS_PLAN WS2/WS3: the choice applied, what the continue did, whether the game is parked
		String save = JsonFields.str(data, "save");
		String started = JsonFields.str(data, "started");
		boolean extrasOk = (!data.has("save") || save != null && SAVES.contains(save))
				&& (!data.has("started") || started != null && STARTED.contains(started))
				&& (!data.has("parked") || data.get("parked").isJsonPrimitive() && data.get("parked").getAsJsonPrimitive().isBoolean());
		if (value < 0 || value > 255 || !switchedOk || !extrasOk || !BOUND_FIELDS.containsAll(data.keySet())) {
			this.warn("session.bad_bound");
			return false;
		}
		if (this.world == null || this.sentLink != this.link || !this.world.equals(this.sentWorld)) {
			// An answer to no bind of this link (the player left the world meanwhile): nothing to pair.
			this.warn("session.stray_bound");
			return false;
		}
		if (re != null && this.sentSeq > 0 && re != this.sentSeq) {
			// The answer to an older bind of this link (another world's): the latest one is still being worked on.
			this.warn("session.stale_bound");
			return false;
		}
		this.slot = (int) value;
		this.switched = how;
		this.save = save;
		this.started = started;
		this.parked = data.has("parked") && data.get("parked").getAsBoolean();
		this.stats.bounds++;
		if (this.boundUp) {
			JsonObject up = new JsonObject();
			up.addProperty("slot", this.slot);
			if (save != null) {
				// WS2: the server records the choice with the slot
				up.addProperty("save", save);
			}
			if (this.port.toServer(MsgTypes.SESSION_BOUND, up)) {
				this.stats.boundsRelayed++;
			}
		}
		return true;
	}

	// ---- views ----

	/** The world id bound (or about to be), or null. */
	public @Nullable String world() {
		return this.world;
	}

	public boolean multiplayer() {
		return this.multiplayer;
	}

	/** The save slot gen1-bridge paired with this world on this link, or null. */
	public @Nullable Integer slot() {
		return this.slot;
	}

	/**
	 * How the hidden game reached the slot in the last accepted {@code session.bound} ({@code none}, {@code title},
	 * {@code saved}, {@code moved}), or null when it didn't say.
	 */
	public @Nullable String switched() {
		return this.switched;
	}

	/** WS2: the choice gen1-bridge applied in the last accepted answer ({@code current}, {@code copy}, {@code new}), or null. */
	public @Nullable String save() {
		return this.save;
	}

	/** WS3: what the last accepted answer's continue did ({@code continue}, {@code new}, {@code none}), or null. */
	public @Nullable String started() {
		return this.started;
	}

	/** WS3: the last accepted answer said the game is parked. */
	public boolean parked() {
		return this.parked;
	}

	/** The {@code seq} of this link's latest bind, or -1. */
	public long sentSeq() {
		return this.sentLink == this.link ? this.sentSeq : -1;
	}

	/** True when this link's gen1-bridge has been told about the current world. */
	public boolean bound() {
		return this.world != null && this.connected && this.sentLink == this.link && this.world.equals(this.sentWorld)
				&& this.multiplayer == this.sentMultiplayer;
	}

	public Stats stats() {
		return this.stats;
	}

	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}
}
