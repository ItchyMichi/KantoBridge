package dev.kantobridge.client.packs;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.world.Games;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Whether this player's own generated pack draws the world they are in, and what to do when it doesn't (docs/PLAN.md
 * §4.3 "Multiplayer clients", DESIGN §8 "Setup": every player's textures come from their own locally generated pack).
 * Since the voxel plan's V1 (docs/specs/VOXEL_BLOCKS_PLAN.md §5.2) the pack holds textures by key only, so one pack fits
 * every world of a compatible game; the runtime model turns the world's slots into keys through the world's slot map.
 * The decision table:
 *
 * <pre>
 * usable = the pack is layout 2 and its game can draw the world's (the same game, or red with blue)
 * no usable pack:      gen1-bridge connected to a compatible game ? EXPORT + GENERATE + one RELOAD
 *                                                                : placeholders, "no_art" (the export runs once it connects)
 * a usable pack, not loaded:                                       RELOAD
 * loaded:  missing = the world's slot-map keys whose sprite is absent from the block atlas
 *          missing == 0                                         -> ready
 *          missing &gt; 0 and gen1-bridge connected, compatible   -> EXPORT + GENERATE + RELOAD (the art is stale)
 *          missing &gt; 0 otherwise                               -> ready with placeholders, "art_incomplete"
 * </pre>
 *
 * The export runs at most once per world. Anything that fails (the export, the build, the reload, a slot map that never
 * comes) ends in placeholders with the reason, until the world changes or the player joins again. Art never travels:
 * {@code texture.status} carries a hash and counts. Plain Java, client thread only (except {@link #work}, which a worker
 * thread sets); {@code TextureSyncRuntime} is the glue.
 */
public final class TextureSync {
	/** How long the slot map may lag behind the index before the client gives up waiting for it. */
	public static final long SLOTS_WAIT_MS = 15_000;
	/** How long the HUD says a rebuilt pack is in use. */
	public static final long DONE_LINE_MS = 5_000;

	/** What the inputs call for. */
	public enum Decision {
		/** Not a Kanto world (no index yet). */
		NONE,
		/** The loaded pack has every texture the world's slot map names. */
		READY,
		/** The loaded pack lacks some, and nothing can be exported now: those draw as placeholders. */
		INCOMPLETE,
		/** A usable pack is on disk but not the one loaded (written without a reload, or newer). */
		RELOAD,
		/** The world's slot map hasn't arrived (or hasn't been resolved against the atlas yet). */
		WAIT_SLOTS,
		/** Export this player's art and generate the pack from it. */
		REGENERATE,
		/** No usable pack and no compatible gen1-bridge: placeholders. */
		NO_ART
	}

	/** Where the flow is for the current world. */
	public enum Phase {
		/** Nothing to judge (no Kanto world), or judging again next tick. */
		IDLE,
		/** Waiting for the slot map. */
		WAITING,
		/** Placeholders until gen1-bridge connects to a compatible game. */
		NO_ART,
		/** The export and the pack build run on a worker. */
		WORKING,
		/** Reloading resources. */
		RELOADING,
		/** The pack draws this world. */
		READY,
		/** The pack draws this world, but {@code missing} keys show placeholders. */
		INCOMPLETE,
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
	 * @param worldGame        the world's game (its WorldIndex's), or null outside a Kanto world
	 * @param worldSlotMapHash the world's slot-map hash (its WorldIndex's), or null
	 * @param local            the generated pack on disk ({@code kantobridge.json}), or null without a pack
	 * @param packLoaded       that pack is the one the last resource reload loaded
	 * @param missing          the world's slot-map keys without a sprite in the atlas; -1 while that isn't known (no slot
	 *                         map yet, or not resolved against the loaded pack yet)
	 * @param bridgeCompatible the main link to gen1-bridge is up, and its game can draw this world
	 * @param shapes           shape ids this client has geometry for (the voxel plan's V2); 0 while it draws cubes
	 * @param shapeIndexHash   the shape index they come from, or null without shapes
	 * @param shapesReason     why it draws cubes ({@code no_shapes}, {@code shape_map_mismatch}, {@code geometry_unsupported},
	 *                         {@code shapes_off}; {@code building} while the table builds), or null when it draws its shapes
	 */
	public record Inputs(@Nullable String worldGame, @Nullable String worldSlotMapHash, @Nullable PackInfo local, boolean packLoaded,
			int missing, boolean bridgeCompatible, int shapes, @Nullable String shapeIndexHash, @Nullable String shapesReason) {
		public Inputs(@Nullable String worldGame, @Nullable String worldSlotMapHash, @Nullable PackInfo local, boolean packLoaded, int missing,
				boolean bridgeCompatible) {
			this(worldGame, worldSlotMapHash, local, packLoaded, missing, bridgeCompatible, 0, null, null);
		}
	}

	/** What the glue does for the flow. Calls come on the client thread. */
	public interface Port {
		/** Sends {@code texture.status}; false when there is no server link (it is tried again next tick). */
		boolean report(JsonObject status);

		/** Starts the export and the pack build; the end comes back as {@link #finished}. */
		void regenerate(long token);

		/** Re-scans the packs and reloads resources; the end comes back as {@link #reloaded}. */
		void reload(long token);

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	private final Port port;
	private final Map<String, Long> warnings = new TreeMap<>();
	private Phase phase = Phase.IDLE;
	/** The world (game and slot-map hash) the current phase is about. */
	private @Nullable String forWorld;
	/** Bumped whenever the world (or its index) changes, so late answers about an older one are dropped. */
	private long token;
	private long waitingSince;
	private @Nullable String reason;
	private @Nullable JsonObject pendingReport;
	private @Nullable String lastReport;
	private boolean reloadInFlight;
	private boolean regenerated;
	private long readyAt;
	private int missing = -1;
	private volatile @Nullable Work work;
	private long reports;
	private long regenerations;

	public TextureSync(Port port) {
		this.port = port;
	}

	/** The decision table (docs/specs/VOXEL_BLOCKS_PLAN.md §5.2); {@code regenerated}: the export already ran for this world. */
	public static Decision decide(Inputs in, boolean regenerated) {
		if (in.worldGame() == null) {
			return Decision.NONE;
		}
		PackInfo local = in.local();
		boolean usable = local != null && local.usable() && Games.compatible(local.game(), in.worldGame());
		boolean canExport = in.bridgeCompatible() && !regenerated;
		if (!usable) {
			return canExport ? Decision.REGENERATE : Decision.NO_ART;
		}
		if (!in.packLoaded()) {
			return Decision.RELOAD;
		}
		if (in.missing() < 0) {
			return Decision.WAIT_SLOTS;
		}
		if (in.missing() == 0) {
			return Decision.READY;
		}
		return canExport ? Decision.REGENERATE : Decision.INCOMPLETE;
	}

	/** A server connection began, or ended: everything is judged again. */
	public void reset() {
		this.token++;
		this.phase = Phase.IDLE;
		this.forWorld = null;
		this.reason = null;
		this.pendingReport = null;
		this.lastReport = null;
		this.reloadInFlight = false;
		this.regenerated = false;
		this.missing = -1;
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
		if (in.worldGame() == null) {
			if (this.forWorld != null) {
				this.token++;
				this.forWorld = null;
			}
			this.phase = Phase.IDLE;
			return;
		}
		String world = in.worldGame() + "|" + in.worldSlotMapHash();
		if (!world.equals(this.forWorld)) {
			// A new world, or a rebuild published a new index: judge it afresh.
			this.token++;
			this.forWorld = world;
			this.phase = Phase.IDLE;
			this.reason = null;
			this.regenerated = false;
			this.missing = -1;
			this.waitingSince = nowMs;
		}
		if (this.phase == Phase.READY) {
			// Ready stays ready; only a change of shapes (their table finished, or went) is reported again.
			this.queueReport(withShapes(ok(in.worldSlotMapHash(), textures(in.local()), 0), in));
			return;
		}
		if (this.phase == Phase.FAILED) {
			return;
		}
		Decision decision = decide(in, this.regenerated);
		switch (decision) {
			case NONE -> this.phase = Phase.IDLE;
			case READY -> {
				this.phase = Phase.READY;
				this.reason = null;
				this.missing = 0;
				this.readyAt = nowMs;
				this.queueReport(withShapes(ok(in.worldSlotMapHash(), textures(in.local()), 0), in));
			}
			case INCOMPLETE -> {
				// Judged again every tick: gen1-bridge may still connect and refresh the art once.
				this.phase = Phase.INCOMPLETE;
				this.reason = "art_incomplete";
				this.missing = in.missing();
				this.queueReport(withShapes(incomplete(in.worldSlotMapHash(), textures(in.local()), in.missing()), in));
			}
			case RELOAD -> this.startReload();
			case WAIT_SLOTS -> {
				this.phase = Phase.WAITING;
				if (nowMs - this.waitingSince > SLOTS_WAIT_MS) {
					this.fail("no_slots");
				}
			}
			case NO_ART -> {
				this.phase = Phase.NO_ART;
				this.reason = "no_art";
				this.queueReport(placeholders("no_art"));
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
		this.regenerated = true;
		if (failure != null) {
			this.fail(reasonCode(failure));
			return;
		}
		this.startReload();
	}

	/** The reload asked for is done; {@code ok}: the generated pack is now loaded. */
	public void reloaded(long token, boolean ok) {
		if (token != this.token || !this.reloadInFlight) {
			return;
		}
		this.reloadInFlight = false;
		if (ok) {
			// Judged again next tick, against the newly loaded pack.
			this.phase = Phase.IDLE;
		} else {
			this.fail("reload");
		}
	}

	private void fail(String code) {
		this.phase = Phase.FAILED;
		this.reason = code;
		this.warn("textures." + code);
		this.queueReport(placeholders(code));
	}

	private void startReload() {
		this.reloadInFlight = true;
		this.phase = Phase.RELOADING;
		this.port.reload(this.token);
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

	private static int textures(@Nullable PackInfo local) {
		return local == null ? 0 : local.textures();
	}

	/** The {@code texture.status} of a client that draws the world with its own art. */
	public static JsonObject ok(@Nullable String slotMapHash, int textures, int missing) {
		JsonObject data = new JsonObject();
		data.addProperty("ok", true);
		if (slotMapHash != null) {
			data.addProperty("slotMapHash", slotMapHash);
		}
		data.addProperty("textures", Math.max(0, textures));
		data.addProperty("missing", Math.max(0, missing));
		return data;
	}

	/** The {@code texture.status} of a client whose own art lacks {@code missing} of the world's keys. */
	public static JsonObject incomplete(@Nullable String slotMapHash, int textures, int missing) {
		JsonObject data = placeholders("art_incomplete");
		if (slotMapHash != null) {
			data.addProperty("slotMapHash", slotMapHash);
		}
		data.addProperty("textures", Math.max(0, textures));
		data.addProperty("missing", Math.max(0, missing));
		return data;
	}

	/**
	 * A status with the shapes this client draws (V2): how many, from which shape index. A client that draws its own art
	 * but cubes says why in {@code reason} (an art reason already there wins); "still building" is not reported.
	 */
	static JsonObject withShapes(JsonObject status, Inputs in) {
		if (in.shapeIndexHash() != null) {
			status.addProperty("shapes", Math.max(0, in.shapes()));
			status.addProperty("shapeIndexHash", in.shapeIndexHash());
		} else if (in.shapesReason() != null && !"building".equals(in.shapesReason()) && !status.has("reason")) {
			status.addProperty("shapes", 0);
			status.addProperty("reason", reasonCode(in.shapesReason()));
		}
		return status;
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

	/** The world's keys this client draws as placeholders, as last judged; -1 while unknown. */
	public int missing() {
		return this.missing;
	}

	/** The worker's step (set from the worker thread). */
	public void work(@Nullable Work step) {
		this.work = step;
	}

	/** {@code texture.status} messages delivered. */
	public long reports() {
		return this.reports;
	}

	/** Exports started for a world. */
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
			case NO_ART -> new ConnectionStatus.Line("kantobridge.hud.textures.no_art", List.of(), true);
			case INCOMPLETE -> new ConnectionStatus.Line("kantobridge.hud.textures.incomplete", List.of(Integer.toString(this.missing)), true);
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
