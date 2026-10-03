package dev.kantobridge.client.packs;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.ConnectionStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Whether this player's own generated pack draws the world they are in, and what to do when it doesn't (docs/PLAN.md
 * §4.3 "Multiplayer clients", DESIGN §8 "Setup": every player's textures come from their own locally generated pack).
 * The world's tile blocks name slots of the host's slot map; the pack's {@code kantobridge.json} says which slot map it
 * was made for. In a world with a WorldIndex:
 *
 * <ul>
 * <li>the hashes agree: the pack is right ({@code texture.status{ok:true}}; reloaded first if it isn't loaded);</li>
 * <li>they differ (or there is no pack) and gen1-bridge is connected: export this player's own art (the main link pauses
 * meanwhile), build the pack against the <b>server's</b> slot map ({@code world.slots}), never this export's own, reload,
 * then report;</li>
 * <li>they differ and gen1-bridge isn't connected: placeholders ({@code ok:false}, reason {@code no_bridge}, a HUD
 * hint); a pack made for other slots is held back so its art never lands on the wrong tiles; once gen1-bridge connects,
 * the export runs;</li>
 * <li>anything that fails (the export, the build, the reload, a slot map that never comes or isn't the index's) ends in
 * placeholders with the reason, until the world changes or the player joins again.</li>
 * </ul>
 * Art never travels: the status carries a hash and a count. Plain Java, client thread only (except {@link #work}, which a
 * worker thread sets); {@code TextureSyncRuntime} is the glue.
 */
public final class TextureSync {
	/** How long the slot map may lag behind the index before the client gives up waiting for it. */
	public static final long SLOTS_WAIT_MS = 15_000;
	/** How long the HUD says a rebuilt pack is in use. */
	public static final long DONE_LINE_MS = 5_000;

	/** What the hashes and the link call for. */
	public enum Decision {
		/** Not a Kanto world (no index yet). */
		NONE,
		/** The pack was made for this world's slots and is loaded. */
		MATCH,
		/** The pack was made for this world's slots but isn't loaded (held back earlier, or written without a reload). */
		RELOAD,
		/** The pack doesn't fit and the world's slot map hasn't arrived yet. */
		WAIT_SLOTS,
		/** The slot map the server sent isn't the one its index names: nothing can be built against it. */
		SLOTS_MISMATCH,
		/** The pack doesn't fit and gen1-bridge isn't connected: placeholders. */
		NO_BRIDGE,
		/** Export this player's art and build it into the world's slots. */
		REGENERATE
	}

	/** Where the flow is for the current world. */
	public enum Phase {
		/** Nothing to judge (no Kanto world), or judging again next tick. */
		IDLE,
		/** Waiting for the slot map. */
		WAITING,
		/** Placeholders until gen1-bridge connects. */
		NO_BRIDGE,
		/** The export and the pack build run on a worker. */
		WORKING,
		/** Reloading resources with the pack offered. */
		RELOADING,
		/** The pack draws this world. */
		READY,
		/** Placeholders, for {@link #reason()}. */
		FAILED
	}

	/** The worker's step, for the HUD. */
	public enum Work {
		EXPORT,
		PACK
	}

	/**
	 * What the world and this client look like now.
	 *
	 * @param serverHash      the world's slot-map hash (its WorldIndex's), or null outside a Kanto world
	 * @param slotsHash       the hash of the slot map received in {@code world.slots}, or null before it came
	 * @param local           the generated pack's {@code kantobridge.json}, or null without a pack
	 * @param packLoaded      the generated pack is among the loaded resource packs
	 * @param bridgeConnected the main link to gen1-bridge is up (an export can run)
	 */
	public record Inputs(@Nullable String serverHash, @Nullable String slotsHash, @Nullable PackInfo local, boolean packLoaded,
			boolean bridgeConnected) {
	}

	/** What the glue does for the flow. Calls come on the client thread. */
	public interface Port {
		/** Sends {@code texture.status}; false when there is no server link (it is tried again next tick). */
		boolean report(JsonObject status);

		/** Starts the export and the pack build for the world's slot map; the end comes back as {@link #finished}. */
		void regenerate(long token);

		/**
		 * Re-scans the packs and reloads resources, offering the generated pack ({@code offer}) or holding it back; the end
		 * comes back as {@link #reloaded}.
		 */
		void reload(long token, boolean offer);

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	private final Port port;
	private final Map<String, Long> warnings = new TreeMap<>();
	private Phase phase = Phase.IDLE;
	/** The world slot-map hash the current phase is about. */
	private @Nullable String forHash;
	/** Bumped whenever the world (or its index) changes, so late answers about an older one are dropped. */
	private long token;
	private long waitingSince;
	private @Nullable String reason;
	private @Nullable JsonObject pendingReport;
	private @Nullable String lastReport;
	private boolean reloadInFlight;
	private boolean reloadOffers;
	/** A failure the tick hasn't acted on: hold a wrong pack back once the inputs are known. */
	private boolean holdBackWanted;
	private boolean hidden;
	private boolean regenerated;
	private long readyAt;
	private volatile @Nullable Work work;
	private long reports;
	private long regenerations;

	public TextureSync(Port port) {
		this.port = port;
	}

	/**
	 * The decision table (docs/PLAN.md §4.3 "Multiplayer clients"): {@code serverHash} is the world's slot-map hash (its
	 * WorldIndex's), {@code localHash} the generated pack's ({@code kantobridge.json}), {@code slotsHash} the slot map
	 * received in {@code world.slots}.
	 */
	public static Decision decide(@Nullable String serverHash, @Nullable String localHash, boolean packLoaded, @Nullable String slotsHash,
			boolean bridgeConnected) {
		if (serverHash == null) {
			return Decision.NONE;
		}
		if (serverHash.equals(localHash)) {
			return packLoaded ? Decision.MATCH : Decision.RELOAD;
		}
		if (slotsHash == null) {
			return Decision.WAIT_SLOTS;
		}
		if (!slotsHash.equals(serverHash)) {
			return Decision.SLOTS_MISMATCH;
		}
		return bridgeConnected ? Decision.REGENERATE : Decision.NO_BRIDGE;
	}

	/** A server connection began, or ended: everything is judged again. */
	public void reset() {
		this.token++;
		this.phase = Phase.IDLE;
		this.forHash = null;
		this.reason = null;
		this.pendingReport = null;
		this.lastReport = null;
		this.reloadInFlight = false;
		this.holdBackWanted = false;
		this.regenerated = false;
		this.work = null;
	}

	/**
	 * Each client tick in a world. {@code busy}: something else owns the pack now (the "Create Kanto world" flow), so this
	 * waits.
	 */
	public void tick(long nowMs, Inputs in, boolean busy) {
		if (this.pendingReport != null && this.port.report(this.pendingReport)) {
			this.delivered(this.pendingReport);
		}
		if (busy || this.reloadInFlight || this.phase == Phase.WORKING) {
			return;
		}
		String serverHash = in.serverHash();
		if (serverHash == null) {
			if (this.forHash != null) {
				this.token++;
				this.forHash = null;
			}
			this.phase = Phase.IDLE;
			return;
		}
		if (!serverHash.equals(this.forHash)) {
			// A new world, or a rebuild published a new index: judge it afresh.
			this.token++;
			this.forHash = serverHash;
			this.phase = Phase.IDLE;
			this.reason = null;
			this.holdBackWanted = false;
			this.regenerated = false;
			this.waitingSince = nowMs;
		}
		if (this.phase == Phase.FAILED) {
			if (this.holdBackWanted) {
				this.holdBackWanted = false;
				this.holdBackWrongPack(in);
			}
			return;
		}
		if (this.phase == Phase.READY) {
			return;
		}
		Decision decision = decide(serverHash, in.local() == null ? null : in.local().slotMapHash(), in.packLoaded(), in.slotsHash(),
				in.bridgeConnected());
		switch (decision) {
			case NONE -> this.phase = Phase.IDLE;
			case MATCH -> {
				this.phase = Phase.READY;
				this.hidden = false;
				this.readyAt = nowMs;
				this.queueReport(ok(serverHash, in.local() == null ? 0 : in.local().textures()));
			}
			case RELOAD -> this.startReload(true);
			case WAIT_SLOTS -> {
				this.phase = Phase.WAITING;
				if (nowMs - this.waitingSince > SLOTS_WAIT_MS) {
					this.fail("no_slots", in);
				}
			}
			case SLOTS_MISMATCH -> this.fail("slot_map_mismatch", in);
			case NO_BRIDGE -> {
				this.phase = Phase.NO_BRIDGE;
				this.reason = "no_bridge";
				this.queueReport(placeholders("no_bridge"));
				this.holdBackWrongPack(in);
			}
			case REGENERATE -> {
				this.phase = Phase.WORKING;
				this.reason = null;
				this.work = Work.EXPORT;
				this.regenerations++;
				this.port.regenerate(this.token);
			}
		}
	}

	/** The worker is done: {@code failure} is null on success, else its code. */
	public void finished(long token, @Nullable String failure) {
		if (token != this.token || this.phase != Phase.WORKING) {
			return;
		}
		this.work = null;
		if (failure != null) {
			String code = reasonCode(failure);
			this.phase = Phase.FAILED;
			this.reason = code;
			this.warn("textures." + code);
			this.queueReport(placeholders(code));
			// The inputs come with the next tick.
			this.holdBackWanted = true;
			return;
		}
		this.regenerated = true;
		this.startReload(true);
	}

	/** The reload asked for is done; {@code ok}: the generated pack is now loaded (offered) or gone (held back), as asked. */
	public void reloaded(long token, boolean ok) {
		if (token != this.token || !this.reloadInFlight) {
			return;
		}
		this.reloadInFlight = false;
		if (!this.reloadOffers) {
			// Holding a wrong pack back: the phase stands either way.
			this.hidden = ok;
			return;
		}
		if (ok) {
			// Judged again next tick: the pack now says it fits (MATCH reports it).
			this.phase = Phase.IDLE;
		} else {
			this.phase = Phase.FAILED;
			this.reason = "reload";
			this.warn("textures.reload");
			this.queueReport(placeholders("reload"));
		}
	}

	private void fail(String code, Inputs in) {
		this.phase = Phase.FAILED;
		this.reason = code;
		this.warn("textures." + code);
		this.queueReport(placeholders(code));
		this.holdBackWrongPack(in);
	}

	/** A loaded pack made for other slots draws wrong art on every tile; placeholders are better. */
	private void holdBackWrongPack(Inputs in) {
		PackInfo local = in.local();
		if (in.packLoaded() && local != null && !local.slotMapHash().equals(in.serverHash()) && !this.reloadInFlight) {
			this.startReload(false);
		}
	}

	private void startReload(boolean offer) {
		this.reloadInFlight = true;
		this.reloadOffers = offer;
		if (offer) {
			this.phase = Phase.RELOADING;
		}
		this.port.reload(this.token, offer);
	}

	private void queueReport(JsonObject status) {
		if (status.toString().equals(this.lastReport)) {
			return;
		}
		this.pendingReport = status;
		if (this.port.report(status)) {
			this.delivered(status);
		}
	}

	private void delivered(JsonObject status) {
		this.lastReport = status.toString();
		this.pendingReport = null;
		this.reports++;
	}

	/** The {@code texture.status} of a pack that draws this world. */
	public static JsonObject ok(String slotMapHash, int textures) {
		JsonObject data = new JsonObject();
		data.addProperty("ok", true);
		data.addProperty("slotMapHash", slotMapHash);
		data.addProperty("textures", Math.max(0, textures));
		return data;
	}

	/** The {@code texture.status} of placeholders, with why. */
	public static JsonObject placeholders(String reason) {
		JsonObject data = new JsonObject();
		data.addProperty("ok", false);
		data.addProperty("reason", reasonCode(reason));
		return data;
	}

	/** A failure as the protocol's reason pattern ({@code ^[a-z][a-z0-9_.]*$}, at most 64 characters). */
	public static String reasonCode(String raw) {
		StringBuilder out = new StringBuilder();
		for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' ? c : '_');
		}
		if (out.isEmpty() || out.charAt(0) < 'a' || out.charAt(0) > 'z') {
			out.insert(0, 'x');
		}
		return out.length() > 64 ? out.substring(0, 64) : out.toString();
	}

	// ---- views ----

	public Phase phase() {
		return this.phase;
	}

	/** Why the world shows placeholders, or null. */
	public @Nullable String reason() {
		return this.reason;
	}

	/** True while a pack made for other slots is held back (the world shows placeholders). */
	public boolean hidden() {
		return this.hidden;
	}

	/** The worker's step (set from the worker thread). */
	public void work(@Nullable Work step) {
		this.work = step;
	}

	/** {@code texture.status} messages delivered. */
	public long reports() {
		return this.reports;
	}

	/** Exports started for a world's slots. */
	public long regenerations() {
		return this.regenerations;
	}

	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	/** The HUD line under the status line, or null. */
	public ConnectionStatus.@Nullable Line hudLine(long nowMs) {
		return switch (this.phase) {
			case WORKING -> new ConnectionStatus.Line(this.work == Work.PACK ? "kantobridge.hud.textures.pack" : "kantobridge.hud.textures.export",
					List.of(), false);
			case RELOADING -> new ConnectionStatus.Line("kantobridge.hud.textures.reload", List.of(), false);
			case NO_BRIDGE -> new ConnectionStatus.Line("kantobridge.hud.textures.no_bridge", List.of(), true);
			case FAILED -> new ConnectionStatus.Line("kantobridge.hud.textures.failed", List.of(this.reason == null ? "?" : this.reason), true);
			case READY -> this.regenerated && nowMs - this.readyAt < DONE_LINE_MS
					? new ConnectionStatus.Line("kantobridge.hud.textures.ready", List.of(), false)
					: null;
			default -> null;
		};
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}
}
