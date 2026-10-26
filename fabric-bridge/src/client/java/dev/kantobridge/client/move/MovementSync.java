package dev.kantobridge.client.move;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the hidden Gen 1 player in step with the Minecraft player (docs/PLAN.md §2.4 flows 1, 6 and 13, §4.3
 * {@code MovementSync}). Plain Java: {@link MoveRuntime} feeds it one {@link #tick} per world tick and the bridge's
 * movement messages, and it answers through a {@link Port}.
 *
 * <ul>
 * <li><b>Steps.</b> When the player's feet cross into an adjacent cell it sends {@code move.step{dir, n}} (a corner cut
 * in one tick becomes two steps, in the order the boundaries were crossed). Pushing one direction against something for
 * {@value #PUSH_TICKS} ticks without moving sends {@code move.step{dir, n, push:true}} (exit mats, gates), again every
 * {@value #PUSH_REPEAT_TICKS} ticks while it lasts.</li>
 * <li><b>Facing.</b> Standing still with the camera in another quadrant than the hidden player faces sends
 * {@code move.face} once the quadrant has held for {@value #FACE_STABLE_TICKS} ticks.</li>
 * <li><b>Answers.</b> gen1-bridge answers every step exactly once, carrying its {@code n}: a {@code player.pos} at the
 * step's start (walk, bike, slope; or {@code sync} when the step set off a warp or moved the player without a visible
 * step, as exit mats do; {@code ledge} for a hop; {@code push} for a Strength push, where the player stays), or a
 * {@code player.blocked} (refused, or dropped from its queue). A walk this side asked for and Minecraft is already
 * making needs nothing. Any other move (a walk Minecraft didn't ask for, the Cycling Road's roll, scripts and door
 * walk-outs, spinners, currents, ledge hops) is walked by the {@code ForcedMover}; a {@code sync} to another cell is
 * snapped to. {@code player.blocked} snaps the player back to the hidden cell's centre once no other step is
 * waiting for its answer.</li>
 * <li><b>Drift.</b> If the cells disagree for more than {@value #DRIFT_MS} ms with no step outstanding, the player is
 * steered back (up to {@value #STEER_CELLS} cells on one level) or teleported ({@code warp.request}, behind the fade).</li>
 * <li><b>Joining.</b> On joining a world, on (re)linking (a reconnect, the hidden game leaving its title screen, the
 * player leaving creative or spectator) and after a snapshot, the player is teleported onto the hidden player's cell
 * unless already there. After a warp, the player walks to wherever the hidden player went meanwhile (a door walk-out),
 * or is teleported if that is far.</li>
 * <li><b>Ladders and holes</b> (docs/specs/CAVES_3D_PLAN.md §3.6): no step is sent onto a cave shaft's end (the hidden
 * game would warp at once): walking onto one, or dropping into a shaft from its top, starts a climb while the hidden
 * player waits where it was; in the shaft nothing is sent and nothing is corrected. Arrived at the far end (feet on its
 * floor going down, at its feet level going up) it sends {@code warp.take} and holds the controls; the warp that answers
 * carries the take's {@code re} and needs no fade or teleport. Refused (after {@value #TAKE_RETRY_MS} ms of retries while
 * the hidden game is busy) or unanswered for {@value #TAKE_TIMEOUT_MS} ms, the player goes back to the hidden cell.
 * Stepping off an end to where the hidden player waits ends the climb; to another of its neighbours, the hidden player
 * walks round the end to it (HD12; a refused step pulls the player back).</li>
 * <li><b>Wild Routes zones</b> (docs/specs/WILD_ROUTES_PLAN.md §4.5). Inside a zone's box, off its route map's cells,
 * while the hidden player stands on that route map (or anywhere in the dungeon region while it stands on a route map),
 * the player is out in the zone: no steps, faces or drift fixes, and no teleport back on joining. Walking back onto the trailhead's x1 needs nothing; stepping on to A is an ordinary
 * step, and A's script takes the hidden player home.</li>
 * </ul>
 *
 * Steps, faces and drift corrections happen only while linked (connected, hidden game in its overworld), in mode
 * {@code idle}, and while neither a warp nor a forced move is running. Client thread only; logs nothing itself (the
 * glue reports its counters).
 */
public final class MovementSync {
	/** What the model asks of Minecraft and the bridge. */
	public interface Port {
		/** Send {@code move.step}. */
		void step(Dir dir, long n, boolean push);

		/** Send {@code move.face}. */
		void face(Dir dir);

		/** Drive the player to the spot's centre over {@code frames} Gen 1 frames (60 a second). */
		void walkTo(Spot target, int frames);

		/** Teleport the player through the server ({@code warp.request} without a kind), behind the fade. */
		void teleport(Place to, long now);

		/** True while a warp fades and teleports; positions are settled when it ends. */
		boolean warping();

		/** True while the forced mover drives the player. */
		boolean forcedMoving();

		/** Send {@code warp.take} (a cave shaft's warp, CAVES_3D_PLAN §3.6); its {@code seq}, or -1 when it was dropped. */
		long take(CellPos from, Place to, String kind);

		/** BIKE_PLAN B9: drop the forced path being walked (a predicted cell the engine didn't take). */
		default void stopWalk() {
		}
	}

	public static final String IDLE = "idle";
	/** The player's feet height at the last tick (a teleport onto a two-floor cell keeps the floor nearest it); NaN before. */
	private double lastY = Double.NaN;
	public static final int PUSH_TICKS = 8;
	public static final int PUSH_REPEAT_TICKS = 16;
	/** Blocks per tick along the pushed direction below which the player counts as stopped by something. */
	static final double PUSH_PROGRESS = 0.02;
	public static final long DRIFT_MS = 500;
	/** BIKE_PLAN B9: a predicted cell the engine hasn't confirmed by then is taken back. */
	public static final long PREDICT_MS = 600;
	/** BIKE_PLAN B9: a ride step this recent means the bike is rolling: the engine's stream leads, nothing is predicted. */
	public static final long ROLLING_MS = 400;
	public static final int STEER_CELLS = 2;
	/**
	 * A step gen1-bridge hasn't answered by then is forgotten (counted as lost). gen1-bridge answers every step within
	 * about 12 logic steps of its turn, behind at most two others (16 frames each); this leaves room for a slow frame.
	 */
	public static final long STEP_TIMEOUT_MS = 3000;
	public static final int FACE_STABLE_TICKS = 4;
	public static final long FACE_RESEND_MS = 1000;
	/** Blocks per tick below which the player counts as standing. */
	static final double STANDING = 0.01;
	/** Teleports this side asks for are at least this far apart. */
	public static final long TELEPORT_COOLDOWN_MS = 3000;
	/** After the server refused a teleport, wait longer before asking again. */
	public static final long REFUSED_TELEPORT_COOLDOWN_MS = 10_000;
	/** Frames per cell when steering back after drift or a warp: walking speed. */
	public static final int WALK_FRAMES = 16;
	/** Frames per cell when snapping back after a refused step or a sync. */
	public static final int SNAP_FRAMES = 6;
	/** A {@code warp.take} whose warp hasn't arrived by then failed. */
	public static final long TAKE_TIMEOUT_MS = 3000;
	/** A busy hidden game is asked again for this long. */
	public static final long TAKE_RETRY_MS = 1000;
	/** Between two asks while it is busy. */
	public static final long TAKE_RETRY_EVERY_MS = 200;
	/** Blocks above the floor the feet may still be, going down, to count as arrived. */
	static final double LANDED = 0.1;
	/** Off a cell's floor by more than this: lifted (a ladder's climb up from its bottom begins). */
	static final double LIFT = 0.05;

	/** A cave shaft being climbed (CAVES_3D_PLAN §3.6): the end the player came in by, and the one it leads to. */
	private record Climb(WorldIndex.Shaft shaft, CellPos near, CellPos far) {
		boolean down() {
			return this.far.equals(this.shaft.bottom());
		}
	}

	private record Pending(Dir dir, @Nullable Spot expected, long sentAt) {
	}

	/** Counters for the debug overlay and the logs. */
	public static final class Stats {
		public long steps;
		public long pushes;
		public long faces;
		public long positions;
		public long forced;
		public long blocked;
		public long snaps;
		public long drifts;
		public long steers;
		public long teleports;
		public long syncs;
		public long lost;
		public long jumps;
		/** Ticks spent out in a Wild Routes zone, off the route map's cells. */
		public long zoneTicks;
		/** Shafts climbed into, warps taken at their far ends, and takes that failed (the player sent back). */
		public long climbs;
		public long takes;
		public long takesFailed;
		/** MG4: followed-link changes relinked here, and resumes refused because the hidden player was over a step away. */
		public long relinks;
		public long relinkFar;
		/** BIKE_PLAN B9: cells pre-moved on a ride's key press, confirmed by the engine, and taken back. */
		public long predicted;
		public long predictConfirmed;
		public long predictMissed;
	}

	private final Port port;
	private final Stats stats = new Stats();
	/** Steps sent and not yet answered, by {@code n}. */
	private final TreeMap<Long, Pending> pending = new TreeMap<>();
	private @Nullable WorldIndex index;
	private boolean linked;
	private String mode = "unknown";
	/** The hidden player's cell (a step's target while it walks) and facing. */
	private @Nullable Place hidden;
	/**
	 * Joined, (re)linked or resynced: put the player on the hidden player's cell at the next chance, by teleport unless
	 * already there.
	 */
	private boolean rejoinWanted;
	/** A warp ended, or the hidden player moved during one: walk the player there if close (a door walk-out). */
	private boolean settleWanted;
	/**
	 * MG4: the followed link changed to a game being resumed: the snapshot that follows settles instead of rejoining, and
	 * that settle walks at most one ordinary step (never a teleport: the hidden player waits on the far gate house's
	 * route map, where the server put this player too).
	 */
	private boolean relinking;
	private boolean relinkStep;
	private long nextN = 1;

	private boolean hasLast;
	private @Nullable Spot last;
	private double lastX;
	private double lastZ;
	/** Where the forced path being walked ends; null when the forced mover is idle. */
	private @Nullable Spot tail;

	private @Nullable Dir pushDir;
	private int pushTicks;
	private int pushSentAt = -1;
	private @Nullable Dir faceCandidate;
	private int faceTicks;
	private @Nullable Dir lastFace;
	private long lastFaceAt;
	private long driftSince = -1;
	private long teleportAllowedAt = Long.MIN_VALUE;
	private @Nullable Climb climb;
	/**
	 * BIKE_PLAN D1: the hidden player rides a bike and is followed: the hidden game drives ({@code move.ride}), so no steps,
	 * pushes or faces are sent; every position it reports is walked as a forced move.
	 */
	private boolean riding;
	/**
	 * BIKE_PLAN B9: the cell the client walked to ahead of the engine on a ride's key press, until the engine's
	 * {@code player.pos} confirms it (or moves elsewhere, bumps, or says nothing for {@value #PREDICT_MS} ms).
	 */
	private @Nullable Spot predicted;
	private long predictedAt;
	/** When the engine last reported a ride step (the bike rolling). */
	private long rideAt = Long.MIN_VALUE / 2;
	/** The last tick found the player out in a Wild Routes zone (the ride is free there: nothing follows). */
	private boolean away;
	/** The {@code warp.take} sent at the far end, waiting for its answer and its warp; -1 for none. */
	private long takeSeq = -1;
	private long takeSentAt;
	/** When the first ask for the current far end went out (busy retries stop after {@link #TAKE_RETRY_MS}). */
	private long takeFirstAt = -1;
	/** A busy answer came: ask again from then on. */
	private long takeRetryAt = -1;

	public MovementSync(Port port) {
		this.port = port;
	}

	// ---- inputs from the glue ----

	/** The world's index (null outside Kanto worlds). A new index starts over and settles positions. */
	public void setIndex(@Nullable WorldIndex index) {
		if (index == this.index) {
			return;
		}
		this.index = index;
		this.pending.clear();
		this.hasLast = false;
		this.tail = null;
		this.rejoinWanted = index != null;
		this.endClimb();
		this.calm();
	}

	/** Connected to gen1-bridge with the hidden game in its overworld. Becoming linked settles positions. */
	public void setLinked(boolean linked) {
		if (linked == this.linked) {
			return;
		}
		this.linked = linked;
		this.pending.clear();
		this.endClimb();
		this.calm();
		if (linked) {
			this.rejoinWanted = true;
		}
	}

	/** The player entered a world: no crossing from where they were before, and settle positions. */
	public void onWorldJoin() {
		this.pending.clear();
		this.hasLast = false;
		this.tail = null;
		this.rejoinWanted = true;
		this.endClimb();
		this.calm();
	}

	/**
	 * One world tick, after the player moved.
	 *
	 * @param intent the direction the movement keys point (see {@link Intent}), null for none
	 * @param yaw    the camera's yaw in degrees
	 */
	public void tick(long now, double x, double y, double z, @Nullable Dir intent, float yaw) {
		this.lastY = y;
		Spot here = this.index == null ? null : Spot.at(this.index, x, y, z);
		if (!this.port.forcedMoving()) {
			this.tail = null;
		}
		if (this.linked) {
			this.expire(now);
		}
		boolean ready = this.linked && this.index != null && !this.port.warping() && !this.port.forcedMoving();
		// Wild Routes: out in a zone, where the hidden player waits on the route map
		boolean away = this.awayInZone(here, x, z);
		this.away = away;
		if (away) {
			this.stats.zoneTicks++;
		}
		boolean moved = false;
		if (ready && this.rejoinWanted) {
			this.rejoinWanted = false;
			this.settleWanted = false;
			moved = !away && this.rejoin(here, now);
		} else if (ready && this.settleWanted) {
			this.settleWanted = false;
			moved = !away && this.settle(here, now);
		}
		// A cave shaft (CAVES_3D_PLAN §3.6): climbing in it, or waiting for its warp, sends nothing else.
		if (ready && !moved && !away && IDLE.equals(this.mode) && this.hasLast && !this.riding && this.climbing(here, x, y, z, yaw, now)) {
			this.calm();
		} else if (ready && !moved && !away && IDLE.equals(this.mode) && this.hasLast && !this.port.forcedMoving() && this.riding) {
			// BIKE_PLAN D1: the hidden game drives the bike; only a drift is corrected
			this.calm();
			if (this.predicted != null && now - this.predictedAt > PREDICT_MS) {
				// BIKE_PLAN B9: the engine never took the predicted cell (a turn in place, a trick): back to the hidden cell
				this.unpredict();
				Spot target = this.hiddenSpot();
				if (here != null && target != null && !target.equals(here)) {
					this.stats.snaps++;
					this.correct(here, target, SNAP_FRAMES, now);
				}
			} else if (this.predicted == null) {
				this.drift(here, now);
			}
		} else if (ready && !moved && !away && IDLE.equals(this.mode) && this.hasLast && !this.port.forcedMoving()) {
			this.crossings(here, x, z, now);
			this.push(intent, here, x, z, now);
			this.face(intent, yaw, x, z, now);
			this.drift(here, now);
		} else {
			this.calm();
		}
		this.last = here;
		this.lastX = x;
		this.lastZ = z;
		this.hasLast = true;
	}

	// ---- bridge messages (client thread, in arrival order) ----

	/** A {@code snapshot}: a new connection or a resync. Steps in flight are forgotten. */
	public void onSnapshot(boolean ingame, @Nullable Place player, @Nullable String mode) {
		this.pending.clear();
		this.predicted = null;
		if (player != null) {
			this.hidden = player;
		}
		if (mode != null) {
			this.mode = mode;
		}
		if (ingame) {
			if (this.relinking) {
				// MG4: the resync after a follow: settle (one step at most), never the join's teleport.
				this.settleWanted = true;
				this.relinkStep = true;
			} else {
				this.rejoinWanted = true;
			}
		}
		this.relinking = false;
		this.calm();
	}

	/**
	 * MG4 ({@code Links.follow}): the body follows another hidden game from now on. Steps in flight and the forced path
	 * are forgotten; the new hidden player's cell is taken ({@code hidden}, when known). With {@code allowTeleport} (a
	 * game started at a pass: its first room) the player is put on the hidden cell as on a join; otherwise (a resume)
	 * the follow-up snapshot settles the player with at most one ordinary step, since the server teleported the body into
	 * the far gate house already, and a farther hidden cell is reported, not chased.
	 */
	public void linkFrom(@Nullable Place hidden, boolean allowTeleport) {
		this.pending.clear();
		this.endClimb();
		this.calm();
		this.tail = null;
		this.hasLast = false;
		if (hidden != null) {
			this.hidden = hidden;
		}
		// Without a cell yet, the snapshot the resync brings does it.
		this.rejoinWanted = allowTeleport && hidden != null;
		this.settleWanted = !allowTeleport && hidden != null;
		this.relinking = !allowTeleport;
		this.relinkStep = !allowTeleport;
		this.stats.relinks++;
	}

	/** {@code player.state}'s mode. */
	public void onMode(String mode) {
		if (!mode.equals(this.mode)) {
			this.mode = mode;
			this.calm();
		}
	}

	/** {@code player.pos}: the hidden player's cell, at a step's start (its target) or after any other change. */
	public void onPosition(Place place, String kind, int frames, @Nullable Long n, long now) {
		Place before = this.hidden;
		this.hidden = place;
		this.stats.positions++;
		if (before == null || before.facing() != place.facing()) {
			this.lastFace = null;
		}
		Spot target = this.index == null ? null : Spot.of(this.index, place);
		if (target == null || !this.linked) {
			// Nothing to follow yet (or no more): linking settles the player wherever the hidden one is by then.
			this.answer(n);
			return;
		}
		if (this.port.warping()) {
			// The teleport isn't done yet (an exit mat's step is answered here, as a sync after the warp's start);
			// whatever moved after the arrival, such as a door walk-out, is settled when the warp ends.
			this.answer(n);
			this.settleWanted = true;
			return;
		}
		if (this.riding && !"sync".equals(kind)) {
			this.rideAt = now;
		}
		if (this.predicted != null) {
			// BIKE_PLAN B9: the engine's first step: the predicted cell (already walked), or elsewhere
			if (!"sync".equals(kind) && sameCell(this.predicted, target)) {
				this.predicted = null;
				this.stats.predictConfirmed++;
				this.answer(n);
				return;
			}
			this.unpredict();
		}
		switch (kind) {
			case "sync" -> {
				// A turn (move.face), a step that set off a warp or moved the player without a visible step, a script
				// teleport. Steps still waiting explain any difference for now.
				this.answer(n);
				if (this.pending.isEmpty() && !target.equals(this.from()) && !this.waitsForClimb(target)) {
					this.correct(this.from(), target, SNAP_FRAMES, now);
				}
			}
			case "walk", "bike", "surf", "slope" -> {
				// A step this side asked for, or one it didn't (the user walking in Gen1Recomp's window, the Cycling
				// Road rolling downhill).
				if (!this.ours(target, n)) {
					this.forced(target, frames, now);
				}
			}
			default -> {
				// script, spin, current, ledge, push: the game moves the player itself (a Strength push leaves the
				// player where it is, frames 0).
				this.answer(n);
				if (!target.equals(this.from()) && !this.waitsForClimb(target)) {
					this.forced(target, frames, now);
				}
			}
		}
	}

	/** {@code player.blocked}: a step was refused or dropped; {@code cell} is where the hidden player stays. */
	public void onBlocked(Place cell, @Nullable Long n, long now) {
		this.hidden = cell;
		this.stats.blocked++;
		boolean unpredicted = this.predicted != null;
		if (unpredicted) {
			// BIKE_PLAN B9: the engine refused the cell the client went ahead to: back to the hidden cell's centre
			this.unpredict();
		}
		if (n != null) {
			this.answer(n);
		} else {
			this.pending.pollFirstEntry();
		}
		if (this.index == null || !this.linked || this.port.warping() || !this.pending.isEmpty()) {
			// Not following, or other steps are still on their way and their answers decide.
			return;
		}
		Spot target = Spot.of(this.index, cell);
		Spot from = this.from();
		if (unpredicted && target != null) {
			this.stats.snaps++;
			this.correct(target, target, SNAP_FRAMES, now);
			return;
		}
		if (target == null || target.equals(from) || this.waitsForClimb(target)) {
			return;
		}
		this.stats.snaps++;
		this.correct(from, target, SNAP_FRAMES, now);
	}

	/** {@code warp{start}}: the old map's steps and moves no longer matter. */
	public void onWarpStart() {
		this.pending.clear();
		this.predicted = null;
		this.tail = null;
		this.endClimb();
		this.calm();
	}

	/** True when {@code re} names the {@code warp.take} waiting for its warp: that warp needs no fade. */
	public boolean takes(@Nullable Long re) {
		return re != null && this.takeSeq >= 0 && re == this.takeSeq;
	}

	/** The {@code warp.take}'s own warp: the player already stands at {@code to}; the climb is over. */
	public void onTakenWarp(String phase, @Nullable Place to) {
		if (!"arrive".equals(phase) || to == null) {
			return;
		}
		this.hidden = to;
		this.pending.clear();
		this.tail = null;
		this.endClimb();
		this.stats.takes++;
	}

	/** {@code warp.taken}: an answer to a {@code warp.take}; a refusal sends the player back (busy: asked again). */
	public void onTaken(@Nullable Long re, boolean ok, @Nullable String reason, long now) {
		if (!this.takes(re) || ok) {
			return;
		}
		this.takeSeq = -1;
		if ("busy".equals(reason) && now - this.takeFirstAt < TAKE_RETRY_MS) {
			this.takeRetryAt = now + TAKE_RETRY_EVERY_MS;
			return;
		}
		this.failTake(now);
	}

	/** True while a {@code warp.take} waits for its warp: the controls are held. */
	public boolean takeHolding() {
		return this.takeSeq >= 0 || this.takeRetryAt >= 0;
	}

	/** True while the player climbs a cave shaft (or stands at its end, the hidden player waiting beside it). */
	public boolean climbing() {
		return this.climb != null;
	}

	/** {@code warp{arrive}}: the hidden player is at the destination. */
	public void onWarpArrive(Place to) {
		this.hidden = to;
		this.pending.clear();
		this.hasLast = false;
		this.settleWanted = true;
	}

	/** The warp (or a teleport this side asked for) is over: no crossing across it, and settle what moved meanwhile. */
	public void onWarpEnded() {
		this.hasLast = false;
		this.settleWanted = true;
	}

	/** The server's answer to a teleport this side asked for. */
	public void onTeleportResult(boolean ok, long now) {
		if (!ok) {
			this.teleportAllowedAt = Math.max(this.teleportAllowedAt, now + REFUSED_TELEPORT_COOLDOWN_MS);
		}
	}

	/** The forced mover couldn't reach its target (something in the way): teleport to the hidden cell instead. */
	public void onStuck(long now) {
		this.tail = null;
		Spot target = this.hiddenSpot();
		if (target != null) {
			this.teleport(target, now);
		}
	}

	/** BIKE_PLAN D1: the hidden game drives (on a bike, followed): no steps, pushes or faces from here. */
	public void setRiding(boolean riding) {
		if (riding != this.riding) {
			this.riding = riding;
			if (this.predicted != null) {
				// BIKE_PLAN B9: off the bike (or unlinked) before the engine answered: the walk ahead stops here
				this.predicted = null;
				this.port.stopWalk();
				this.tail = null;
			}
			this.calm();
		}
	}

	/**
	 * BIKE_PLAN B9 (D1's fallback): the rider pressed {@code dir} while the bike stands on the hidden cell. The engine's
	 * first step reaches the client a round trip later, so the client walks the next cell at once, over
	 * {@code frames} (the bike's first gear); the engine's {@code player.pos} for that cell confirms it, anything else
	 * takes it back ({@link #onPosition}, {@link #onBlocked}, the timeout in {@link #tick}). Returns whether it started.
	 */
	public boolean predictRide(Dir dir, int frames, long now) {
		if (!this.riding || !this.linked || this.index == null || this.predicted != null || this.port.forcedMoving()
				|| this.port.warping() || !IDLE.equals(this.mode) || now - this.rideAt < ROLLING_MS || this.away) {
			return false;
		}
		Spot from = this.hiddenSpot();
		if (from == null || this.last == null || !from.equals(this.last) || this.hiddenUnbuilt()) {
			return false;
		}
		Spot next = from.offset(dir.dx(), dir.dy());
		this.predicted = next;
		this.predictedAt = now;
		this.stats.predicted++;
		this.driftSince = -1;
		this.walk(next, Math.max(1, frames));
		return true;
	}

	/** BIKE_PLAN B9: a cell predicted and not yet confirmed. */
	public @Nullable Spot predicted() {
		return this.predicted;
	}

	/** BIKE_PLAN B9: the prediction was wrong: stop walking it; the caller moves the player from where it stands. */
	private void unpredict() {
		this.predicted = null;
		this.stats.predictMissed++;
		this.port.stopWalk();
		this.tail = null;
	}

	private static boolean sameCell(Spot a, Spot b) {
		return a.x() == b.x() && a.z() == b.z();
	}

	public boolean riding() {
		return this.riding;
	}

	/** True when the last tick found the player out in a Wild Routes zone. */
	public boolean away() {
		return this.away;
	}

	// ---- views ----

	public Stats stats() {
		return this.stats;
	}

	public String mode() {
		return this.mode;
	}

	public boolean linked() {
		return this.linked;
	}

	public @Nullable WorldIndex index() {
		return this.index;
	}

	public @Nullable Place hidden() {
		return this.hidden;
	}

	/** The hidden player's spot, or null when unknown or on a map this world doesn't have. */
	public @Nullable Spot hiddenSpot() {
		return this.index == null || this.hidden == null ? null : Spot.of(this.index, this.hidden);
	}

	/** True when the hidden player stands on a map this world doesn't have (a partial build). */
	public boolean hiddenUnbuilt() {
		return this.index != null && this.hidden != null && this.index.map(this.hidden.map()) == null;
	}

	public int pendingSteps() {
		return this.pending.size();
	}

	/** The {@code n} the next step will carry. */
	public long nextStep() {
		return this.nextN;
	}

	// ---- internals ----

	/**
	 * A cave shaft (CAVES_3D_PLAN §3.6): starts a climb when the player steps onto one of its ends or drops into it, takes
	 * the warp at its far end, and ends it when the player steps off. Returns true while a climb lasts: the tick then
	 * sends no steps and makes no corrections.
	 */
	private boolean climbing(@Nullable Spot here, double x, double y, double z, float yaw, long now) {
		WorldIndex world = this.index;
		if (world == null || world.shafts().isEmpty()) {
			return false;
		}
		CellPos cell = here == null ? null : here.cell(world);
		CellPos hiddenCell = this.hidden == null ? null : new CellPos(this.hidden.map(), this.hidden.x(), this.hidden.y());
		WorldIndex.Shaft inside = world.shaftAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		if (this.climb == null) {
			WorldIndex.Shaft end = cell == null ? null : climbableEnd(world, cell);
			if (end != null && !cell.equals(hiddenCell)) {
				// Onto an end: no step (the game would warp at once); the hidden player waits beside it.
				this.climb = new Climb(end, cell, cell.equals(end.top()) ? end.bottom() : end.top());
				this.stats.climbs++;
				return true;
			}
			if (inside != null && hiddenCell != null) {
				// Dropped in from an end the hidden player stands on or beside (after a climb up, it stands on the top).
				CellPos near = beside(hiddenCell, inside.top()) ? inside.top() : inside.up() && beside(hiddenCell, inside.bottom()) ? inside.bottom() : null;
				if (near != null) {
					this.climb = new Climb(inside, near, near.equals(inside.top()) ? inside.bottom() : inside.top());
					this.stats.climbs++;
					return true;
				}
			}
			WorldIndex.Shaft under = cell == null || !cell.equals(hiddenCell) ? null : ladderBottom(world, cell);
			if (under != null && y > under.y0() + LIFT) {
				// Lifted off the floor of a ladder's bottom where the hidden player stands: climbing up already (the shaft's
				// air begins a block higher), so pressing on against the ladder is no push.
				this.climb = new Climb(under, cell, under.top());
				this.stats.climbs++;
				return true;
			}
			return false;
		}
		Climb c = this.climb;
		if (this.takeSeq >= 0) {
			// Waiting for the take's warp (or its answer).
			if (now - this.takeSentAt > TAKE_TIMEOUT_MS) {
				this.takeSeq = -1;
				this.failTake(now);
			}
			return true;
		}
		boolean arrived = c.far().equals(cell) && (c.down() ? y < c.shaft().y0() + LANDED : y > c.shaft().y1() + 1 - LANDED);
		if (arrived && (this.takeRetryAt < 0 || now >= this.takeRetryAt)) {
			if (this.takeFirstAt < 0) {
				this.takeFirstAt = now;
			}
			this.takeRetryAt = -1;
			this.takeSentAt = now;
			this.takeSeq = this.port.take(c.near(), new Place(c.far().map(), c.far().x(), c.far().y(), Dir.fromYaw(yaw)), c.shaft().kind());
			if (this.takeSeq < 0) {
				// Dropped (not connected): sent back.
				this.failTake(now);
			}
			return true;
		}
		if (cell != null && inside == null && cell.equals(hiddenCell) && !cell.equals(c.far()) && this.onFloor(world, cell, y)) {
			// On the floor of the cell the hidden player stands on (back where it waits, or down again from a hop at a
			// ladder's bottom): no climb any more, and no crossing from the end.
			this.endClimb();
			return true;
		}
		if (cell == null || cell.equals(c.near()) || cell.equals(c.far()) || inside == c.shaft()) {
			// In the shaft, or at one of its ends.
			return true;
		}
		if (c.near().equals(hiddenCell)) {
			// Off the end the hidden player stands on (a hop off a ladder's bottom): an ordinary step from it.
			this.endClimb();
			return false;
		}
		this.endClimb();
		this.walkRound(world, hiddenCell, cell, c.near(), now);
		return true;
	}

	/** True when {@code y} is on (or just over) the floor of {@code cell}. */
	private boolean onFloor(WorldIndex world, CellPos cell, double y) {
		net.minecraft.world.phys.Vec3 at = world.toBlock(cell.map(), cell.x(), cell.y());
		return at != null && y <= at.y + LIFT;
	}

	/** The ladder whose bottom is {@code cell} and that is climbed up too, or null. */
	private static WorldIndex.@Nullable Shaft ladderBottom(WorldIndex world, CellPos cell) {
		WorldIndex.Shaft shaft = world.shaftEnd(cell);
		return shaft != null && shaft.up() && WorldIndex.Shaft.LADDER.equals(shaft.kind()) && cell.equals(shaft.bottom()) ? shaft : null;
	}

	/**
	 * True while climbing when {@code target} is where the hidden player waits, on or beside the climb's near end: a late
	 * answer (to a turn or a push made before the climb) moves nobody out of the shaft, where the player stands in no
	 * cell.
	 */
	private boolean waitsForClimb(@Nullable Spot target) {
		Climb c = this.climb;
		WorldIndex world = this.index;
		CellPos cell = c == null || target == null || world == null ? null : target.cell(world);
		return cell != null && beside(cell, c.near());
	}

	/** A shaft end the player may climb or drop from: a top, or the bottom of a ladder climbed up too. */
	private static WorldIndex.@Nullable Shaft climbableEnd(WorldIndex world, CellPos cell) {
		WorldIndex.Shaft shaft = world.shaftEnd(cell);
		if (shaft == null) {
			return null;
		}
		return cell.equals(shaft.top()) || shaft.up() ? shaft : null;
	}

	/** True when {@code a} is {@code b} or one of its four neighbours, on one map. */
	private static boolean beside(CellPos a, CellPos b) {
		return a.map().equals(b.map()) && Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) <= 1;
	}

	/**
	 * HD12: the player stepped off a shaft's end onto another of its neighbours: the hidden player walks round the end to
	 * it (two steps round a corner, four across), the steps answered as ones Minecraft already made. A refused step pulls
	 * the player back, as any refused step does; a cell no walk round reaches is a pull back at once.
	 */
	private void walkRound(WorldIndex world, @Nullable CellPos from, CellPos to, CellPos end, long now) {
		if (from == null || !from.map().equals(to.map()) || !beside(from, end) || !beside(to, end) || from.equals(end)) {
			Spot back = this.hiddenSpot();
			if (back != null) {
				this.correct(this.last, back, WALK_FRAMES, now);
			}
			return;
		}
		int ax = from.x() - end.x();
		int ay = from.y() - end.y();
		int bx = to.x() - end.x();
		int by = to.y() - end.y();
		List<Dir> path = new java.util.ArrayList<>();
		if (ax * bx + ay * by == 0) {
			// Round the corner: along the side from's at, then onto to's.
			path.add(Dir.of(bx, by));
			path.add(Dir.of(-ax, -ay));
		} else {
			// Across: out to the side, along two, back in.
			Dir side = Dir.of(ay, ax);
			path.add(side);
			path.add(Dir.of(-ax, -ay));
			path.add(Dir.of(-ax, -ay));
			path.add(side.opposite());
		}
		Spot at = Spot.of(world, from.map(), from.x(), from.y());
		for (Dir dir : path) {
			at = at == null ? null : at.offset(dir.dx(), dir.dy());
			this.send(dir, at, false, now);
		}
	}

	/** The take failed: back to the hidden player's cell, through the fade. */
	private void failTake(long now) {
		this.stats.takesFailed++;
		this.endClimb();
		Spot target = this.hiddenSpot();
		if (target != null) {
			this.teleportAllowedAt = Long.MIN_VALUE;
			this.teleport(target, now);
		}
	}

	private void endClimb() {
		this.climb = null;
		this.takeSeq = -1;
		this.takeFirstAt = -1;
		this.takeRetryAt = -1;
	}

	private void crossings(@Nullable Spot here, double x, double z, long now) {
		Spot from = this.last;
		if (from == null || here == null || from.equals(here) || !from.sameLevel(here)) {
			return;
		}
		int blocksX = here.x() - from.x();
		int blocksZ = here.z() - from.z();
		if (blocksX % Scale.CELL_BLOCKS != 0 || blocksZ % Scale.CELL_BLOCKS != 0) {
			this.stats.jumps++;
			return;
		}
		int cellsX = blocksX / Scale.CELL_BLOCKS;
		int cellsY = blocksZ / Scale.CELL_BLOCKS;
		if (Math.abs(cellsX) + Math.abs(cellsY) == 1) {
			this.send(Dir.of(cellsX, cellsY), here, false, now);
		} else if (Math.abs(cellsX) == 1 && Math.abs(cellsY) == 1) {
			// A corner cut within one tick. Gen 1 can't step diagonally: send the boundary crossed first, then the other.
			double tx = crossingTime(this.lastX, x, cellsX > 0 ? from.x() + Scale.CELL_BLOCKS : from.x());
			double tz = crossingTime(this.lastZ, z, cellsY > 0 ? from.z() + Scale.CELL_BLOCKS : from.z());
			Dir first = tx <= tz ? Dir.of(cellsX, 0) : Dir.of(0, cellsY);
			Dir second = tx <= tz ? Dir.of(0, cellsY) : Dir.of(cellsX, 0);
			this.send(first, from.offset(first.dx(), first.dy()), false, now);
			this.send(second, here, false, now);
		} else {
			// More than a cell in one tick (lag, a teleport the bridge didn't cause): drift correction sorts it out.
			this.stats.jumps++;
		}
	}

	private static double crossingTime(double from, double to, double boundary) {
		return to == from ? 1.0 : (boundary - from) / (to - from);
	}

	private void push(@Nullable Dir intent, @Nullable Spot here, double x, double z, long now) {
		if (intent == null || here == null || !here.equals(this.last)) {
			this.resetPush(intent);
			return;
		}
		if (intent != this.pushDir) {
			this.resetPush(intent);
		}
		double progress = intent.dx() * (x - this.lastX) + intent.dy() * (z - this.lastZ);
		WorldIndex world = this.index;
		CellPos cell = world == null ? null : here.cell(world);
		WorldIndex.Shaft ladder = cell == null ? null : ladderBottom(world, cell);
		if (progress > PUSH_PROGRESS || ladder != null && intent == ladder.wall()) {
			// Moving, or pressing against a ladder from its bottom: that climbs it (behind the ladder the game may have
			// open floor, where a push would walk the hidden player away).
			this.resetPush(intent);
			return;
		}
		this.pushTicks++;
		boolean due = this.pushSentAt < 0 ? this.pushTicks >= PUSH_TICKS : this.pushTicks - this.pushSentAt >= PUSH_REPEAT_TICKS;
		if (due && this.pending.isEmpty()) {
			this.pushSentAt = this.pushTicks;
			this.send(intent, null, true, now);
		}
	}

	private void resetPush(@Nullable Dir intent) {
		this.pushDir = intent;
		this.pushTicks = 0;
		this.pushSentAt = -1;
	}

	private void face(@Nullable Dir intent, float yaw, double x, double z, long now) {
		boolean standing = Math.abs(x - this.lastX) < STANDING && Math.abs(z - this.lastZ) < STANDING;
		Place place = this.hidden;
		Dir quadrant = Dir.fromYaw(yaw);
		if (intent != null || !standing || !this.pending.isEmpty() || place == null || quadrant == place.facing()) {
			this.faceCandidate = null;
			this.faceTicks = 0;
			return;
		}
		if (quadrant != this.faceCandidate) {
			this.faceCandidate = quadrant;
			this.faceTicks = 0;
		}
		this.faceTicks++;
		if (this.faceTicks >= FACE_STABLE_TICKS && (quadrant != this.lastFace || now - this.lastFaceAt >= FACE_RESEND_MS)) {
			this.lastFace = quadrant;
			this.lastFaceAt = now;
			this.stats.faces++;
			this.port.face(quadrant);
		}
	}

	/**
	 * Wild Routes (docs/specs/WILD_ROUTES_PLAN.md §4.5, §4.8): true off every map's cells while the hidden player stands
	 * on a zone's route map and the player is inside that zone's box, or in the dungeon region.
	 */
	private boolean awayInZone(@Nullable Spot here, double x, double z) {
		if (here != null || this.index == null || this.hidden == null) {
			return false;
		}
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		WorldIndex.Zone zone = this.index.zoneAt(bx, bz);
		if (zone != null) {
			return zone.routeMap().equals(this.hidden.map());
		}
		MapPlacement map = this.index.inDungeons(bx, bz) ? this.index.map(this.hidden.map()) : null;
		return map != null && map.kind() == MapPlacement.Kind.ROUTE;
	}

	private void drift(@Nullable Spot here, long now) {
		Spot target = this.hiddenSpot();
		if (target == null || !this.pending.isEmpty() || target.equals(here)) {
			this.driftSince = -1;
			return;
		}
		if (this.driftSince < 0) {
			this.driftSince = now;
			return;
		}
		if (now - this.driftSince <= DRIFT_MS) {
			return;
		}
		this.driftSince = -1;
		this.stats.drifts++;
		this.correct(here, target, WALK_FRAMES, now);
	}

	/**
	 * Join, reconnect, resync: teleport the player onto the hidden player's cell unless it is already there. Returns
	 * whether the player needed moving.
	 */
	private boolean rejoin(@Nullable Spot here, long now) {
		Spot target = this.hiddenSpot();
		this.driftSince = -1;
		this.endClimb();
		if (target == null || target.equals(here)) {
			return false;
		}
		this.stats.syncs++;
		this.teleport(target, now);
		return true;
	}

	/** After a warp: walk to where the hidden player went meanwhile (a door walk-out), or teleport if it is far. */
	private boolean settle(@Nullable Spot here, long now) {
		Spot target = this.hiddenSpot();
		this.driftSince = -1;
		boolean relink = this.relinkStep;
		this.relinkStep = false;
		if (target == null || target.equals(here)) {
			return false;
		}
		if (relink) {
			// MG4: after a follow, one ordinary step at most; farther is a mismatch to report, never a teleport.
			if (here == null || !here.sameLevel(target) || here.cellsTo(target) > 1) {
				this.stats.relinkFar++;
				return false;
			}
			this.stats.syncs++;
			this.walk(target, WALK_FRAMES);
			return true;
		}
		this.stats.syncs++;
		this.correct(here, target, WALK_FRAMES, now);
		return true;
	}

	private void expire(long now) {
		while (!this.pending.isEmpty() && now - this.pending.firstEntry().getValue().sentAt() > STEP_TIMEOUT_MS) {
			this.pending.pollFirstEntry();
			this.stats.lost++;
		}
	}

	private void send(Dir dir, @Nullable Spot expected, boolean push, long now) {
		long n = this.nextN++;
		this.pending.put(n, new Pending(dir, expected, now));
		if (push) {
			this.stats.pushes++;
		} else {
			this.stats.steps++;
		}
		this.port.step(dir, n, push);
	}

	/**
	 * gen1-bridge answers every step exactly once, with its {@code n}: that step leaves the queue, and only that one (a
	 * dropped waiting step must not take the others with it). Returns the step, or null if it wasn't waiting.
	 */
	private @Nullable Pending answer(@Nullable Long n) {
		return n == null ? null : this.pending.remove(n);
	}

	/**
	 * Whether a reported walk is a step this side sent and Minecraft is already making: by {@code n} (the step's expected
	 * cell), else, from a gen1-bridge that doesn't echo {@code n}, by the oldest step's expected cell; else by where the
	 * player stands. The step answered leaves the queue.
	 */
	private boolean ours(Spot target, @Nullable Long n) {
		if (n != null) {
			Pending answered = this.answer(n);
			if (answered != null) {
				return target.equals(answered.expected());
			}
		} else if (!this.pending.isEmpty()) {
			Map.Entry<Long, Pending> oldest = this.pending.firstEntry();
			if (target.equals(oldest.getValue().expected())) {
				this.pending.pollFirstEntry();
				return true;
			}
		}
		return target.equals(this.last);
	}

	/** A move the game makes: walk it if it continues the current path, else teleport. */
	private void forced(Spot target, int frames, long now) {
		this.stats.forced++;
		this.driftSince = -1;
		Spot from = this.from();
		if (from != null && from.sameLevel(target) && from.cellsTo(target) <= STEER_CELLS) {
			this.walk(target, frames > 0 ? frames : SNAP_FRAMES);
		} else {
			this.teleport(target, now);
		}
	}

	/** Steer to {@code target} if it is close on the same level, else teleport. */
	private void correct(@Nullable Spot from, Spot target, int framesPerCell, long now) {
		if (from != null && from.sameLevel(target) && from.cellsTo(target) <= STEER_CELLS) {
			this.stats.steers++;
			this.walk(target, Math.max(SNAP_FRAMES, framesPerCell * from.cellsTo(target)));
		} else {
			this.teleport(target, now);
		}
	}

	private void walk(Spot target, int frames) {
		this.tail = target;
		this.port.walkTo(target, frames);
	}

	private void teleport(Spot target, long now) {
		WorldIndex world = this.index;
		if (world == null || now < this.teleportAllowedAt) {
			return;
		}
		// The cell's own map, even when the hidden game named it from the map it is leaving.
		CellPos cell = target.cell(world);
		if (cell == null) {
			return;
		}
		Dir facing = this.hidden != null ? this.hidden.facing() : Dir.DOWN;
		// the floor of a two-floor cell (D7): the hidden player's elevation where it stands there, else the floor nearest
		// the player's feet
		int elev = -1;
		if (this.hidden != null && this.hidden.cell().equals(cell)) {
			elev = this.hidden.elev();
		}
		if (elev < 0 && world.twoFloors(cell.map(), cell.x(), cell.y()) && !Double.isNaN(this.lastY)) {
			elev = world.elevOf(cell.map(), cell.x(), cell.y(), world.floorAt(cell, this.lastY));
		}
		this.teleportAllowedAt = now + TELEPORT_COOLDOWN_MS;
		this.stats.teleports++;
		this.pending.clear();
		this.tail = null;
		this.port.teleport(new Place(cell.map(), cell.x(), cell.y(), facing, elev), now);
	}

	/** Where corrections start from: the end of the forced path being walked, else where the player stands. */
	private @Nullable Spot from() {
		return this.tail != null ? this.tail : this.last;
	}

	private void calm() {
		this.resetPush(null);
		this.faceCandidate = null;
		this.faceTicks = 0;
		this.driftSince = -1;
	}
}
