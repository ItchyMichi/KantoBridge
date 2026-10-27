package dev.kantobridge.client.zone;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.MsgTypes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The client's half of a crossing (docs/specs/MULTI_GAME_PLAN.md §4.8 steps 3–4, §4.7 "The first start"; PROTOCOL.md
 * "Crossing rules (MG5)"), without Minecraft: the player stands on a connecting route's pass, {@code pass.cross} goes up,
 * and on the server's {@code route.fade} the followed hidden game is parked, the far game is resumed onto its route map
 * (or, never started, has the party stashed into the PC and is started from its title, with the player's name entered
 * for it, D5/D6), then the far link is followed. The server teleports the body into the far gate house once the new
 * {@code region.state} arrives (the link manager sends it on a follow). Anything that goes wrong ends the crossing with
 * {@code pass.abort} and lifts the fade.
 *
 * <p>Plain Java: {@link PassRuntime} feeds it the links, the server and the ticks. Times in milliseconds.
 */
public final class PassChoreography {
	/** A crossing that hasn't followed the far link this long after {@code pass.cross} is aborted (the server's timer is 15 s too). */
	public static final long TIMEOUT_MS = 15_000;
	/** After a start, the far game has this long after its intro's last screen closed to reach its overworld. */
	public static final long INGAME_GRACE_MS = 10_000;
	/** The target's route map cell the hidden player is resumed onto: x1 of the plan's {@code [A][x1]} (A is 0,0). */
	public static final int ARRIVE_X = 1;
	public static final int ARRIVE_Y = 0;
	public static final String ARRIVE_FACING = "right";

	public enum State {
		IDLE,
		/** {@code pass.cross} sent, the server's fade not here yet. */
		CROSS_SENT,
		/** {@code region.park} sent to the followed link. */
		PARKING,
		/** {@code party.stash} sent to the server (the far game is at its title). */
		STASHING,
		/** {@code region.start} sent to the far link. */
		STARTING,
		/** {@code region.resume} sent to the far link. */
		RESUMING,
		/** The far game was started and followed; its intro runs; waiting for {@code game.status{ingame:true}}. */
		AWAIT_INGAME
	}

	/** What the host does to the dungeon fade during a crossing. */
	public enum Fade {
		/** Lift it: the crossing ended where the player stands. */
		LIFT,
		/** Keep it from timing out while the far game's intro shows its screens. */
		EXTEND,
		/** Dark again until the final teleport lands (after the intro's last screen). */
		RESTART
	}

	/** One hidden game's link, as the choreography sees it. */
	public interface LinkPort {
		/** The region of the world built from its game, or null. */
		@Nullable String region();

		boolean connected();

		/** The last {@code game.status}'s playthrough ({@code title}, {@code fresh}, {@code saved}), or null when unknown. */
		@Nullable String playthrough();

		/** The save's player name from the last {@code game.status}, or null. */
		@Nullable String name();

		/** Sends to the hidden game; its {@code seq}, or -1 when not sent (not connected). */
		long sendTracked(String type, JsonObject data);

		/**
		 * WORLD_SESSIONS_PLAN WS3 (B1): the last {@code game.status} at the title said the active slot holds a save
		 * ({@code game.status.save}): a CONTINUE, not a new story.
		 */
		default boolean titleSave() {
			return false;
		}
	}

	/** The client around the choreography: the links, the server relay, the UI and the fade. */
	public interface Host {
		@Nullable LinkPort followed();

		/** The link of the game {@code region} was built from, or null (none, or a wrong game). */
		@Nullable LinkPort forRegion(String region);

		/** Sends to the Minecraft server; the message's {@code seq}, or -1 when not sent. */
		long toServer(String type, JsonObject data);

		/** Follows the link of {@code region}'s game; false when it can't be followed. */
		boolean follow(String region, boolean allowTeleport);

		/** Arms the automatic answer to the next naming screen (D6), or disarms it with null. */
		void armName(@Nullable String name);

		void fade(Fade action);

		/** A HUD line (a lang key and its arguments). */
		void notice(String key, List<String> args, boolean warning);

		/** The label of {@code region}'s game for the HUD. */
		String label(String region);
	}

	/**
	 * A pass under the player's feet.
	 *
	 * @param route       the connecting route
	 * @param dir         the end the player heads for ({@code to} from the route's {@code from} half)
	 * @param fromRegion  the region the player stands in
	 * @param toRegion    the far half's region
	 * @param farRouteMap the far half's route map id, or null while the far region's index isn't known
	 */
	public record PassAt(String route, String dir, String fromRegion, String toRegion, @Nullable String farRouteMap) {
	}

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long crossings;
		public long resumes;
		public long starts;
		public long stashes;
		/** WS3 (B1): saved far games at their title CONTINUEd without a stash. */
		public long continuedAtTitle;
		public long aborts;
		public long timeouts;
		public long serverErrors;
	}

	private final Host host;
	private final Stats stats = new Stats();
	private final Map<String, Long> aborts = new TreeMap<>();
	private State state = State.IDLE;
	private @Nullable PassAt pass;
	private @Nullable LinkPort from;
	private @Nullable LinkPort target;
	/** When {@code pass.cross} went up: the whole crossing's clock. */
	private long since;
	private long parkSeq = -1;
	private long stashSeq = -1;
	private long startSeq = -1;
	private long resumeSeq = -1;
	private long lastUiClose = -1;
	/** The route a {@code pass.cross} went up for while the player stands on its pass (asked once per standing). */
	private @Nullable String armedRoute;

	public PassChoreography(Host host) {
		this.host = host;
	}

	// ---- events ----

	/**
	 * Once per tick: the pass under the player's feet (null when none), and whether the body is idle (following the hidden
	 * game, standing). {@code pass.cross} goes up once per standing on a pass.
	 */
	public void standing(@Nullable PassAt here, boolean idle, long now) {
		if (here == null) {
			this.armedRoute = null;
			return;
		}
		if (this.state != State.IDLE || !idle || here.route().equals(this.armedRoute)) {
			return;
		}
		LinkPort followed = this.host.followed();
		if (followed == null || !followed.connected() || !here.fromRegion().equals(followed.region())) {
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("route", here.route());
		data.addProperty("dir", here.dir());
		if (this.host.toServer(MsgTypes.PASS_CROSS, data) <= 0) {
			return;
		}
		this.armedRoute = here.route();
		this.pass = here;
		this.from = followed;
		this.target = null;
		this.since = now;
		this.state = State.CROSS_SENT;
		this.stats.crossings++;
	}

	/** The server's {@code route.fade} arrived: the crossing was accepted, the followed game is parked now. */
	public void fadeStarted(long now) {
		if (this.state != State.CROSS_SENT || this.pass == null || this.from == null) {
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("region", this.pass.fromRegion());
		this.parkSeq = this.from.sendTracked(MsgTypes.REGION_PARK, data);
		if (this.parkSeq <= 0) {
			this.abort("link_down", now);
			return;
		}
		this.state = State.PARKING;
	}

	/** A hidden game's {@code act.result} (any link). */
	public void linkResult(LinkPort link, @Nullable Long re, boolean ok, @Nullable String reason, long now) {
		if (re == null) {
			return;
		}
		switch (this.state) {
			case PARKING -> {
				if (link == this.from && re == this.parkSeq) {
					if (ok) {
						this.parked(now);
					} else {
						this.abort(reason == null ? "park_refused" : reason, now);
					}
				}
			}
			case RESUMING -> {
				if (link == this.target && re == this.resumeSeq) {
					if (ok && this.pass != null && this.host.follow(this.pass.toRegion(), false)) {
						this.stats.resumes++;
						this.finish();
					} else {
						this.abort(ok ? "follow_failed" : reason == null ? "resume_refused" : reason, now);
					}
				}
			}
			case STARTING -> {
				if (link == this.target && re == this.startSeq) {
					if (!ok) {
						this.abort(reason == null ? "start_refused" : reason, now);
						return;
					}
					// The intro runs as the body's screens from now on; the player's name answers its naming screen (D6).
					this.host.armName(this.from == null ? null : this.from.name());
					if (this.pass == null || !this.host.follow(this.pass.toRegion(), true)) {
						this.host.armName(null);
						this.abort("follow_failed", now);
						return;
					}
					this.stats.starts++;
					this.state = State.AWAIT_INGAME;
					this.lastUiClose = -1;
				}
			}
			default -> {
			}
		}
	}

	private void parked(long now) {
		PassAt here = this.pass;
		if (here == null) {
			this.abort("internal", now);
			return;
		}
		LinkPort far = this.host.forRegion(here.toRegion());
		if (far == null || !far.connected()) {
			this.abort("no_link", now);
			this.host.notice("kantobridge.pass.no_link", List.of(this.host.label(here.toRegion())), true);
			return;
		}
		this.target = far;
		if ("title".equals(far.playthrough()) && far.titleSave()) {
			// WS3 (B1): a saved game waiting at its title is CONTINUEd as it is: its party is its own, nothing is stashed
			this.stats.continuedAtTitle++;
			this.host.notice("kantobridge.pass.starting", List.of(this.host.label(here.toRegion())), false);
			this.startFar(now);
			return;
		}
		if ("title".equals(far.playthrough())) {
			// D5: every party member into the PC first, so the new story's starter has room and nothing is lost.
			JsonObject data = new JsonObject();
			data.addProperty("region", here.toRegion());
			this.stashSeq = this.host.toServer(MsgTypes.PARTY_STASH, data);
			if (this.stashSeq <= 0) {
				this.abort("server_down", now);
				return;
			}
			this.host.notice("kantobridge.pass.starting", List.of(this.host.label(here.toRegion())), false);
			this.state = State.STASHING;
			return;
		}
		if (here.farRouteMap() == null) {
			this.abort("not_built", now);
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("region", here.toRegion());
		JsonObject to = new JsonObject();
		to.addProperty("map", here.farRouteMap());
		to.addProperty("x", ARRIVE_X);
		to.addProperty("y", ARRIVE_Y);
		to.addProperty("facing", ARRIVE_FACING);
		data.add("to", to);
		this.resumeSeq = far.sendTracked(MsgTypes.REGION_RESUME, data);
		if (this.resumeSeq <= 0) {
			this.abort("link_down", now);
			return;
		}
		this.state = State.RESUMING;
	}

	/** The server's {@code act.result} (S→C): the answer to {@code party.stash}. */
	public void serverResult(@Nullable Long re, boolean ok, @Nullable String kind, long now) {
		if (this.state != State.STASHING || re == null || re != this.stashSeq || !"stash".equals(kind)) {
			return;
		}
		if (!ok) {
			this.abort("stash_refused", now);
			return;
		}
		this.stats.stashes++;
		this.startFar(now);
	}

	/** {@code region.start} to the far game (after the stash, or at once for a saved game at its title: B1). */
	private void startFar(long now) {
		if (this.pass == null || this.target == null) {
			this.abort("internal", now);
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("region", this.pass.toRegion());
		this.startSeq = this.target.sendTracked(MsgTypes.REGION_START, data);
		if (this.startSeq <= 0) {
			this.abort("link_down", now);
			return;
		}
		this.state = State.STARTING;
	}

	/** An {@code error} from the server. */
	public void serverError(@Nullable String code, @Nullable Long re, long now) {
		if (code == null || this.state == State.IDLE) {
			return;
		}
		if ("stash.pc_full".equals(code) && (re == null || re == this.stashSeq)) {
			this.stats.serverErrors++;
			this.host.notice("kantobridge.pass.pc_full", List.of(), true);
			this.abort("stash.pc_full", now);
			return;
		}
		if (code.startsWith("pass.")) {
			this.stats.serverErrors++;
			this.aborts.merge(code, 1L, Long::sum);
			String label = this.pass == null ? "?" : this.host.label(this.pass.toRegion());
			switch (code) {
				case "pass.no_link" -> this.host.notice("kantobridge.pass.no_link", List.of(label), true);
				case "pass.not_built", "pass.no_region" -> this.host.notice("kantobridge.pass.not_built", List.of(label), true);
				case "pass.timeout" -> this.host.notice("kantobridge.pass.timeout", List.of(), true);
				default -> this.host.notice("kantobridge.pass.failed", List.of(code), true);
			}
			// The server ended it (a refusal or its timer): nothing to abort; the fade lifts where the player stands.
			if (this.state != State.CROSS_SENT) {
				this.host.fade(Fade.LIFT);
			}
			this.end();
		}
	}

	/** A hidden game's {@code game.status} (any link). */
	public void gameStatus(LinkPort link, boolean ingame, long now) {
		if (this.state == State.AWAIT_INGAME && link == this.target && ingame) {
			this.finish();
		}
	}

	/** One of the body's UIs closed (the intro's screens, after a start). */
	public void uiClosed(long now) {
		if (this.state == State.AWAIT_INGAME) {
			this.lastUiClose = now;
			// Dark again for the final teleport into the first room.
			this.host.fade(Fade.RESTART);
		}
	}

	/** A link dropped. */
	public void linkDown(LinkPort link, long now) {
		if (this.state == State.IDLE) {
			return;
		}
		if (link == this.from || link == this.target) {
			this.abort("link_down", now);
		}
	}

	/** Once per tick: the timers. */
	public void tick(long now) {
		switch (this.state) {
			case IDLE -> {
			}
			case CROSS_SENT -> {
				if (now - this.since > TIMEOUT_MS) {
					// The server never answered (its refusal was lost, or it is gone): try again from the next standing.
					this.stats.timeouts++;
					this.host.notice("kantobridge.pass.timeout", List.of(), true);
					this.end();
				}
			}
			case AWAIT_INGAME -> {
				this.host.fade(Fade.EXTEND);
				if (this.lastUiClose >= 0 && now - this.lastUiClose > INGAME_GRACE_MS) {
					// The game was started and is followed; only the overworld never came: the crossing is over anyway.
					this.stats.timeouts++;
					this.finish();
				}
			}
			default -> {
				if (now - this.since > TIMEOUT_MS) {
					this.stats.timeouts++;
					this.abort("timeout", now);
				}
			}
		}
	}

	// ---- endings ----

	/**
	 * The crossing fails here: {@code pass.abort} tells the server (which may hold a crossing for this player even while
	 * its fade hasn't arrived), the fade lifts where the player stands, and a HUD line says why.
	 */
	private void abort(String reason, long now) {
		this.stats.aborts++;
		this.aborts.merge(reason, 1L, Long::sum);
		if (this.pass != null) {
			JsonObject data = new JsonObject();
			data.addProperty("route", this.pass.route());
			data.addProperty("reason", reason.matches("^[a-z][a-z0-9_.]{0,63}$") ? reason : "failed");
			this.host.toServer(MsgTypes.PASS_ABORT, data);
		}
		if ("timeout".equals(reason)) {
			this.host.notice("kantobridge.pass.timeout", List.of(), true);
		} else if (!"stash.pc_full".equals(reason) && !"no_link".equals(reason)) {
			this.host.notice("kantobridge.pass.failed", List.of(reason), true);
		}
		this.host.fade(Fade.LIFT);
		this.end();
	}

	/** The crossing is done: the far link is followed; the fade ends by itself when the teleport has landed. */
	private void finish() {
		this.end();
	}

	private void end() {
		this.state = State.IDLE;
		this.pass = null;
		this.from = null;
		this.target = null;
		this.parkSeq = -1;
		this.stashSeq = -1;
		this.startSeq = -1;
		this.resumeSeq = -1;
		this.lastUiClose = -1;
	}

	// ---- views ----

	public State state() {
		return this.state;
	}

	/** True while a crossing is under way. */
	public boolean crossing() {
		return this.state != State.IDLE;
	}

	/** The crossing's route, or null. */
	public @Nullable String route() {
		return this.pass == null ? null : this.pass.route();
	}

	public Stats stats() {
		return this.stats;
	}

	/** Aborts and server refusals by reason or code. */
	public Map<String, Long> aborts() {
		return this.aborts;
	}
}
