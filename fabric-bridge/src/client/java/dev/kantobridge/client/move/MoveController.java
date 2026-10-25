package dev.kantobridge.client.move;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Walking and warps without Minecraft (docs/PLAN.md §2.4 flows 1, 6, 7 and 13, §4.3 {@code move/}): turns gen1-bridge's
 * movement messages and the server's {@code warp.done} into calls on {@link MovementSync}, {@link ForcedMover} and
 * {@link WarpFade}, decides the {@link InputLock}, and sends what they ask for. {@link MoveRuntime} is the Minecraft
 * glue around it; tests drive it directly (also against {@code fake_gen1}).
 *
 * <p>A world tick is {@link #beforeMove} (fade, forced velocity, lock; before the player's own movement) and
 * {@link #afterMove} (the new position to the movement sync). Only Kanto worlds (with a WorldIndex) are followed, and
 * never a free camera (creative, spectator), which is put back where the hidden player is on returning.
 */
public final class MoveController {
	/** What the controller needs from Minecraft and the two links. */
	public interface Port {
		/** Sends a message to gen1-bridge; false when not connected (dropped). */
		boolean toBridge(String type, JsonObject data);

		/** The same for a request whose answer names it: its {@code seq}, or -1 when it was dropped. */
		default long toBridgeTracked(String type, JsonObject data) {
			return this.toBridge(type, data) ? 0 : -1;
		}

		/** Sends a message to the server relay; returns its {@code seq}, or -1 when it couldn't. */
		long toServer(String type, JsonObject data);

		/** True once the player stands at {@code target} (a cell centre; null: anywhere) and its sections are drawn. */
		boolean destinationReady(@Nullable Vec3 target);

		/** A counted warning: an area, a short code, and how many so far. */
		void warn(String area, String code, long count);
	}

	/**
	 * One tick's view of the player and the links.
	 *
	 * @param index      the world's WorldIndex, or null outside Kanto worlds
	 * @param freeCamera creative or spectator
	 * @param mode       the hidden game's mode ({@code player.state})
	 * @param holds      one of our screens that holds the player is open
	 * @param battle     the player fights a Cobblemon battle (the hidden game waits for its result)
	 */
	public record Frame(@Nullable WorldIndex index, boolean freeCamera, boolean connected, boolean ingame, String mode, boolean holds,
			boolean battle, double x, double z) {
		/** A frame outside any battle. */
		public Frame(@Nullable WorldIndex index, boolean freeCamera, boolean connected, boolean ingame, String mode, boolean holds, double x,
				double z) {
			this(index, freeCamera, connected, ingame, mode, holds, false, x, z);
		}
	}

	static final long LOST_WARNING_EVERY_MS = 10_000;

	private final Port port;
	private final ForcedMover mover = new ForcedMover();
	/** Whether the local player is walked by a forced move now (the story state relays it: the fall walls open, D4). */
	private static volatile boolean forcedNow;
	private final MovementSync sync;
	private final WarpFade fade;
	private final Map<String, Long> warnings = new TreeMap<>();
	private InputLock.Reason reason = InputLock.Reason.NONE;
	private boolean following;
	/** The {@code seq} of the {@code warp.request} in flight, or -1. */
	private long requestSeq = -1;
	private @Nullable Vec3 requestTarget;
	private @Nullable WorldIndex index;
	private long lostReported;
	private long lostWarnedAt = Long.MIN_VALUE;

	public MoveController(Port port) {
		this.port = port;
		this.sync = new MovementSync(new SyncPort());
		this.fade = new WarpFade(new FadePort());
	}

	// ---- ticks ----

	/** Start of a world tick, before the player moves: returns the velocity to give the player, or null. */
	public ForcedMover.@Nullable Velocity beforeMove(long now, Frame frame) {
		this.index = frame.index();
		this.sync.setIndex(frame.index());
		boolean kanto = frame.index() != null;
		this.following = kanto && !frame.freeCamera();
		// A battle unlinks the movement sync: nothing may reach the waiting hidden game, and relinking afterwards puts the
		// player back on the hidden cell if a sent-out Pokémon pushed them off it.
		this.sync.setLinked(this.following && frame.connected() && frame.ingame() && !frame.battle());
		if (!this.following) {
			// A free camera (or no Kanto world): nothing to drive or fade; linking again settles the player.
			this.mover.clear();
			this.fade.reset();
			this.requestSeq = -1;
			this.requestTarget = null;
		}
		this.fade.tick(now);
		ForcedMover.Velocity velocity = this.following ? this.mover.tick(frame.x(), frame.z()) : null;
		forcedNow = this.following && this.mover.active();
		// A cave shaft's warp being taken holds the controls too (CAVES_3D_PLAN §3.6).
		this.reason = InputLock.reason(kanto, frame.freeCamera(), frame.battle(), frame.connected(), frame.ingame(), this.sync.hiddenUnbuilt(),
				frame.mode(), this.fade.holding() || this.sync.takeHolding(), this.mover.active(), frame.holds());
		return velocity;
	}

	/**
	 * MG4 ({@code Links.follow}): the body follows another hidden game from now on. The forced path, the warp fade and
	 * the teleport in flight belonged to the old one; the movement sync relinks from the new hidden player's cell.
	 */
	public void relink(@Nullable Place hidden, boolean allowTeleport) {
		this.mover.clear();
		this.fade.reset();
		this.requestSeq = -1;
		this.requestTarget = null;
		this.sync.linkFrom(hidden, allowTeleport);
	}

	/** End of a world tick, after the player moved. */
	public void afterMove(long now, double x, double y, double z, @Nullable Dir intent, float yaw) {
		if (this.mover.takeStuck()) {
			this.warn("move", "forced_stuck");
			this.sync.onStuck(now);
		}
		this.sync.tick(now, x, y, z, intent, yaw);
		long lost = this.sync.stats().lost;
		if (lost > this.lostReported && now - this.lostWarnedAt >= LOST_WARNING_EVERY_MS) {
			this.port.warn("move", "steps_unanswered", lost);
			this.lostReported = lost;
			this.lostWarnedAt = now;
		}
	}

	// ---- messages ----

	/** A gen1-bridge message (the movement ones; others are ignored). */
	public void onBridgeMessage(Envelope message, long now) {
		JsonObject data = message.data();
		switch (message.type()) {
			case MsgTypes.SNAPSHOT -> {
				JsonObject state = JsonFields.obj(data, "state");
				this.sync.onSnapshot(JsonFields.bool(data, "ingame", false), Place.fromJson(data.get("player")),
						state == null ? null : JsonFields.str(state, "mode"));
			}
			case MsgTypes.PLAYER_POS -> {
				Place place = place(data, JsonFields.str(data, "facing"));
				String kind = JsonFields.str(data, "kind");
				if (place != null && kind != null) {
					int frames = (int) Math.max(0, Math.min(3600, JsonFields.integer(data, "frames", 0)));
					this.sync.onPosition(place, kind, frames, step(data), now);
				}
			}
			case MsgTypes.PLAYER_BLOCKED -> {
				// Gen 1 turns the player towards the step it refused.
				Place cell = place(data, JsonFields.str(data, "dir"));
				if (cell != null) {
					this.sync.onBlocked(cell, step(data), now);
				}
			}
			case MsgTypes.PLAYER_STATE -> {
				String mode = JsonFields.str(data, "mode");
				if (mode != null) {
					this.sync.onMode(mode);
				}
			}
			case MsgTypes.WARP -> this.onWarp(data, message.re(), now);
			case MsgTypes.WARP_TAKEN -> this.sync.onTaken(message.re(), JsonFields.bool(data, "ok", false), JsonFields.str(data, "reason"), now);
			default -> {
			}
		}
	}

	private void onWarp(JsonObject data, @Nullable Long re, long now) {
		String phase = JsonFields.str(data, "phase");
		String kind = JsonFields.str(data, "kind");
		if (phase == null || kind == null) {
			return;
		}
		if (this.sync.takes(re)) {
			// A cave shaft's warp, taken where the player already stands (CAVES_3D_PLAN §3.6): no fade, no teleport.
			this.sync.onTakenWarp(phase, Place.fromJson(data.get("to")));
			return;
		}
		if (phase.equals("start")) {
			this.sync.onWarpStart();
			if (this.following) {
				this.mover.clear();
				this.fade.start(kind, now);
			}
		} else if (phase.equals("arrive")) {
			Place to = Place.fromJson(data.get("to"));
			if (to == null) {
				return;
			}
			this.sync.onWarpArrive(to);
			if (this.following) {
				this.mover.clear();
				this.fade.arrive(to, kind, now);
			}
		}
	}

	/** A server message: {@code warp.done} for the request in flight (older answers are ignored). */
	public void onServerMessage(Envelope message, long now) {
		if (!message.type().equals(MsgTypes.WARP_DONE)) {
			return;
		}
		Long re = message.re();
		if (this.requestSeq < 0 || (re != null && re != this.requestSeq)) {
			return;
		}
		boolean ok = JsonFields.bool(message.data(), "ok", false);
		this.fade.done(ok, now);
		this.sync.onTeleportResult(ok, now);
	}

	/** The player entered a world. */
	public void onJoin() {
		this.mover.clear();
		this.fade.reset();
		this.requestSeq = -1;
		this.requestTarget = null;
		this.sync.onWorldJoin();
	}

	/** The player left the world. */
	public void onLeave() {
		this.mover.clear();
		this.fade.reset();
		this.requestSeq = -1;
		this.requestTarget = null;
		this.sync.setIndex(null);
		this.index = null;
		this.following = false;
		this.reason = InputLock.Reason.NONE;
	}

	// ---- views ----

	public InputLock.Reason reason() {
		return this.reason;
	}

	/** True while the hidden game is followed for this player (a Kanto world, not a free camera). */
	public boolean following() {
		return this.following;
	}

	public MovementSync sync() {
		return this.sync;
	}

	public WarpFade fade() {
		return this.fade;
	}

	public ForcedMover mover() {
		return this.mover;
	}

	/** Why the player can't walk, when the connection line doesn't already say: a status-style line, or null. */
	public ConnectionStatus.@Nullable Line hint() {
		return switch (this.reason) {
			case NOT_IN_GAME -> new ConnectionStatus.Line("kantobridge.hud.move.not_in_game", List.of(), true);
			case UNBUILT_MAP -> {
				Place hidden = this.sync.hidden();
				yield new ConnectionStatus.Line("kantobridge.hud.move.unbuilt", List.of(hidden == null ? "?" : hidden.map()), true);
			}
			default -> null;
		};
	}

	// ---- helpers ----

	/** True while the local player is walked by a forced move (WATER_AND_BRIDGES_PLAN D4: a waterfall's foot wall opens). */
	public static boolean forcedNow() {
		return forcedNow;
	}

	/** A place from a message's {@code map}, {@code x}, {@code y} and the given facing; null if anything is off. */
	static @Nullable Place place(JsonObject data, @Nullable String facing) {
		String map = JsonFields.str(data, "map");
		long x = JsonFields.integer(data, "x", Long.MIN_VALUE);
		long y = JsonFields.integer(data, "y", Long.MIN_VALUE);
		Dir dir = Dir.byId(facing);
		if (map == null || dir == null || Math.abs(x) > Place.MAX_COORD || Math.abs(y) > Place.MAX_COORD) {
			return null;
		}
		// the hidden game's elevation (Gen 3: the floor of a two-floor cell, D7); absent or out of range: none
		long elev = JsonFields.integer(data, "elev", -1);
		try {
			return new Place(map, (int) x, (int) y, dir, elev >= 0 && elev <= 15 ? (int) elev : -1);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static @Nullable Long step(JsonObject data) {
		long n = JsonFields.integer(data, "n", -1);
		return n > 0 ? n : null;
	}

	private void warn(String area, String code) {
		long count = this.warnings.merge(area + "." + code, 1L, Long::sum);
		this.port.warn(area, code, count);
	}

	/** The movement sync's requests, carried out with the links and the forced mover. */
	private final class SyncPort implements MovementSync.Port {
		@Override
		public void step(Dir dir, long n, boolean push) {
			JsonObject data = new JsonObject();
			data.addProperty("dir", dir.id());
			data.addProperty("n", n);
			if (push) {
				data.addProperty("push", true);
			}
			MoveController.this.port.toBridge(MsgTypes.MOVE_STEP, data);
		}

		@Override
		public void face(Dir dir) {
			JsonObject data = new JsonObject();
			data.addProperty("dir", dir.id());
			MoveController.this.port.toBridge(MsgTypes.MOVE_FACE, data);
		}

		@Override
		public void walkTo(Spot target, int frames) {
			MoveController.this.mover.add(target.centreX(), target.centreZ(), frames);
		}

		@Override
		public void teleport(Place to, long now) {
			if (MoveController.this.following) {
				MoveController.this.fade.sync(to, now);
			}
		}

		@Override
		public boolean warping() {
			return MoveController.this.fade.holding();
		}

		@Override
		public boolean forcedMoving() {
			return MoveController.this.mover.active();
		}

		@Override
		public long take(CellPos from, Place to, String kind) {
			JsonObject data = new JsonObject();
			JsonObject near = new JsonObject();
			near.addProperty("map", from.map());
			near.addProperty("x", from.x());
			near.addProperty("y", from.y());
			data.add("from", near);
			data.add("to", to.toJson());
			data.addProperty("kind", kind);
			return MoveController.this.port.toBridgeTracked(MsgTypes.WARP_TAKE, data);
		}
	}

	/** The fade's requests: the relay's {@code warp.request}, and whether the destination is drawn yet. */
	private final class FadePort implements WarpFade.Port {
		@Override
		public boolean request(Place to, @Nullable String kind) {
			JsonObject data = new JsonObject();
			data.add("to", to.toJson());
			if (kind != null) {
				data.addProperty("kind", kind);
			}
			long seq = MoveController.this.port.toServer(MsgTypes.WARP_REQUEST, data);
			if (seq < 0) {
				return false;
			}
			WorldIndex world = MoveController.this.index;
			MoveController.this.requestSeq = seq;
			MoveController.this.requestTarget = world == null ? null : world.toBlock(to.map(), to.x(), to.y(), to.elev());
			return true;
		}

		@Override
		public boolean destinationReady() {
			return MoveController.this.port.destinationReady(MoveController.this.requestTarget);
		}

		@Override
		public void warn(String code) {
			MoveController.this.warn("warp", code);
		}

		@Override
		public void ended() {
			MoveController.this.requestSeq = -1;
			MoveController.this.requestTarget = null;
			MoveController.this.sync.onWarpEnded();
		}
	}
}
