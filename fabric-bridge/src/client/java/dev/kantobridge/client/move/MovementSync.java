package dev.kantobridge.client.move;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
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
	}

	public static final String IDLE = "idle";
	public static final int PUSH_TICKS = 8;
	public static final int PUSH_REPEAT_TICKS = 16;
	/** Blocks per tick along the pushed direction below which the player counts as stopped by something. */
	static final double PUSH_PROGRESS = 0.02;
	public static final long DRIFT_MS = 500;
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
		this.calm();
	}

	/** Connected to gen1-bridge with the hidden game in its overworld. Becoming linked settles positions. */
	public void setLinked(boolean linked) {
		if (linked == this.linked) {
			return;
		}
		this.linked = linked;
		this.pending.clear();
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
		this.calm();
	}

	/**
	 * One world tick, after the player moved.
	 *
	 * @param intent the direction the movement keys point (see {@link Intent}), null for none
	 * @param yaw    the camera's yaw in degrees
	 */
	public void tick(long now, double x, double y, double z, @Nullable Dir intent, float yaw) {
		Spot here = this.index == null ? null : Spot.at(this.index, x, y, z);
		if (!this.port.forcedMoving()) {
			this.tail = null;
		}
		if (this.linked) {
			this.expire(now);
		}
		boolean ready = this.linked && this.index != null && !this.port.warping() && !this.port.forcedMoving();
		boolean moved = false;
		if (ready && this.rejoinWanted) {
			this.rejoinWanted = false;
			this.settleWanted = false;
			moved = this.rejoin(here, now);
		} else if (ready && this.settleWanted) {
			this.settleWanted = false;
			moved = this.settle(here, now);
		}
		// A teleport or walk just asked for replaces this tick's own crossing: never both.
		if (ready && !moved && IDLE.equals(this.mode) && this.hasLast && !this.port.forcedMoving()) {
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
		if (player != null) {
			this.hidden = player;
		}
		if (mode != null) {
			this.mode = mode;
		}
		if (ingame) {
			this.rejoinWanted = true;
		}
		this.calm();
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
		switch (kind) {
			case "sync" -> {
				// A turn (move.face), a step that set off a warp or moved the player without a visible step, a script
				// teleport. Steps still waiting explain any difference for now.
				this.answer(n);
				if (this.pending.isEmpty() && !target.equals(this.from())) {
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
				if (!target.equals(this.from())) {
					this.forced(target, frames, now);
				}
			}
		}
	}

	/** {@code player.blocked}: a step was refused or dropped; {@code cell} is where the hidden player stays. */
	public void onBlocked(Place cell, @Nullable Long n, long now) {
		this.hidden = cell;
		this.stats.blocked++;
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
		if (target == null || target.equals(from)) {
			return;
		}
		this.stats.snaps++;
		this.correct(from, target, SNAP_FRAMES, now);
	}

	/** {@code warp{start}}: the old map's steps and moves no longer matter. */
	public void onWarpStart() {
		this.pending.clear();
		this.tail = null;
		this.calm();
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
		if (progress > PUSH_PROGRESS) {
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
		if (target == null || target.equals(here)) {
			return false;
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
		this.teleportAllowedAt = now + TELEPORT_COOLDOWN_MS;
		this.stats.teleports++;
		this.pending.clear();
		this.tail = null;
		this.port.teleport(new Place(cell.map(), cell.x(), cell.y(), facing), now);
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
