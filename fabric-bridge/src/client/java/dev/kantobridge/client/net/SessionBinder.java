package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import java.util.Map;
import java.util.Objects;
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
 */
public final class SessionBinder {
	/** Where messages go. */
	public interface Port {
		/** To gen1-bridge; false when not connected (then the message is dropped). */
		boolean toBridge(String type, JsonObject data);

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
	private @Nullable String sentWorld;
	private boolean sentMultiplayer;
	private @Nullable Integer slot;

	/**
	 * @param boundUp relay {@code session.bound} to the server (the protocol's catalog decides)
	 */
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

	/** Each client tick: binds when this link hasn't heard about this world yet. */
	public void tick() {
		String current = this.world;
		if (!this.connected || current == null) {
			return;
		}
		if (this.sentLink == this.link && current.equals(this.sentWorld) && this.multiplayer == this.sentMultiplayer) {
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("world", current);
		data.addProperty("multiplayer", this.multiplayer);
		if (this.port.toBridge(MsgTypes.SESSION_BIND, data)) {
			this.sentLink = this.link;
			this.sentWorld = current;
			this.sentMultiplayer = this.multiplayer;
			this.stats.binds++;
		}
	}

	/** {@code session.bound{slot}} from gen1-bridge. */
	public void onBound(JsonObject data) {
		long value = JsonFields.integer(data, "slot", -1);
		if (value < 0 || value > 255 || data.size() != 1) {
			this.warn("session.bad_bound");
			return;
		}
		if (this.world == null || this.sentLink != this.link) {
			// An answer to no bind of this link (the player left the world meanwhile): nothing to pair.
			this.warn("session.stray_bound");
			return;
		}
		this.slot = (int) value;
		this.stats.bounds++;
		if (this.boundUp) {
			JsonObject up = new JsonObject();
			up.addProperty("slot", this.slot);
			if (this.port.toServer(MsgTypes.SESSION_BOUND, up)) {
				this.stats.boundsRelayed++;
			}
		}
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
