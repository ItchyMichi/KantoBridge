package dev.kantobridge.client.move;

import java.util.ArrayDeque;
import org.jetbrains.annotations.Nullable;

/**
 * Drives the player along moves the hidden game makes on its own (docs/PLAN.md §2.4 flow 6, §4.3 {@code ForcedMover}):
 * scripted walks, ledge hops, spinners, a walk Minecraft didn't ask for, and the client's own snap-backs. Plain Java.
 *
 * <p>Each leg is a target point and a duration in Gen 1 frames (60 a second). Every world tick advances three frames
 * (Minecraft ticks 20 times a second), possibly finishing one leg and starting the next within the tick, and returns
 * the horizontal velocity that takes the player from where it really is to where the legs say it should be. The glue
 * sets it before the player's own movement, so collision, step-up and the server's movement checks treat it like
 * walking; a tick lost to a collision is caught up on the next one.
 *
 * <p>After the last leg the player is checked: arrived means one braking tick (otherwise inertia carries it about a
 * third of a cell on); not arrived means up to {@value #SETTLE_TICKS} ticks pushing towards the target, then giving up
 * ({@link #takeStuck}), and the caller teleports instead.
 *
 * <p>STEP_SPEED_PLAN §2.5 (Part B, D6): a leg started late is otherwise never caught up, so on a ride (the hidden game
 * drives, the client follows) the latency stays as a constant lag. While legs wait behind the one under way, a tick
 * spends {@code 1 + waiting} ticks' worth of frames (at most {@value #MAX_RATE}), and never moves the player more than
 * {@value #MAX_CATCH_UP} blocks in that tick (far below the server's vehicle and player move checks, 10 blocks a tick):
 * the frames that don't fit wait for the next tick, so nothing is skipped. A backlog of two legs drains within about a
 * cell; a single leg keeps its frames exactly.
 */
public final class ForcedMover {
	/** Gen 1 frames per Minecraft tick. */
	public static final double FRAMES_PER_TICK = 3.0;
	/** Within this many blocks of the target the move is done. */
	public static final double ARRIVED = 0.2;
	public static final int SETTLE_TICKS = 10;
	/** Blocks per tick while settling. */
	public static final double SETTLE_SPEED = 0.4;
	/** Legs waiting beyond this mean Minecraft fell far behind: keep only the newest. */
	public static final int MAX_LEGS = 16;
	/** STEP_SPEED_PLAN Part B: the most ticks' worth of frames one tick spends while legs wait. */
	public static final int MAX_RATE = 3;
	/** STEP_SPEED_PLAN Part B: blocks a catch-up tick may move at most. */
	public static final double MAX_CATCH_UP = 3.0;
	private static final double EPSILON = 1.0E-9;

	/** A horizontal velocity in blocks per tick. */
	public record Velocity(double x, double z) {
		public static final Velocity ZERO = new Velocity(0.0, 0.0);
	}

	private record Leg(double x, double z, double frames) {
	}

	private final ArrayDeque<Leg> legs = new ArrayDeque<>();
	private @Nullable Leg current;
	private double framesLeft;
	private double targetX;
	private double targetZ;
	/** Ticks spent checking the arrival after the last leg; -1 when not checking. */
	private int settle = -1;
	private boolean stuck;
	private long added;
	private long stuckCount;
	private long catchUpTicks;

	/** Adds a leg: reach block point ({@code x}, {@code z}) {@code frames} Gen 1 frames after the previous leg ends. */
	public void add(double x, double z, int frames) {
		if (this.legs.size() >= MAX_LEGS) {
			this.legs.clear();
		}
		this.legs.add(new Leg(x, z, Math.max(1, frames)));
		this.targetX = x;
		this.targetZ = z;
		this.settle = -1;
		this.added++;
	}

	/** Drops every leg (a warp makes them moot). */
	public void clear() {
		this.legs.clear();
		this.current = null;
		this.settle = -1;
	}

	/** True while a leg runs or the arrival is being checked: the player's own input stays locked. */
	public boolean active() {
		return this.current != null || !this.legs.isEmpty() || this.settle >= 0;
	}

	/** The velocity to give the player this tick, or null to leave it alone. */
	public @Nullable Velocity tick(double px, double pz) {
		if (this.current != null || !this.legs.isEmpty()) {
			double x = px;
			double z = pz;
			int waiting = this.legs.size() - (this.current == null ? 1 : 0);
			int rate = 1 + Math.min(Math.max(0, waiting), MAX_RATE - 1);
			double budget = FRAMES_PER_TICK * rate;
			double room = rate > 1 ? MAX_CATCH_UP : Double.POSITIVE_INFINITY;
			if (rate > 1) {
				this.catchUpTicks++;
			}
			while (budget > EPSILON) {
				if (this.current == null) {
					this.current = this.legs.poll();
					if (this.current == null) {
						break;
					}
					this.framesLeft = this.current.frames();
				}
				double use = Math.min(budget, this.framesLeft);
				double share = use / this.framesLeft;
				double dist = Math.hypot(this.current.x() - x, this.current.z() - z) * share;
				boolean full = false;
				if (dist > room) {
					// the catch-up's cap: only the frames that fit; the rest wait for the next tick
					use *= room / dist;
					share = use / this.framesLeft;
					full = true;
				}
				x += (this.current.x() - x) * share;
				z += (this.current.z() - z) * share;
				room -= Math.min(dist, room);
				this.framesLeft -= use;
				budget -= use;
				if (this.framesLeft <= EPSILON) {
					this.current = null;
				}
				if (full) {
					break;
				}
			}
			if (this.current == null && this.legs.isEmpty()) {
				this.settle = 0;
			}
			return new Velocity(x - px, z - pz);
		}
		if (this.settle < 0) {
			return null;
		}
		double dx = this.targetX - px;
		double dz = this.targetZ - pz;
		double distance = Math.hypot(dx, dz);
		if (distance <= ARRIVED) {
			this.settle = -1;
			return Velocity.ZERO;
		}
		if (this.settle >= SETTLE_TICKS) {
			this.settle = -1;
			this.stuck = true;
			this.stuckCount++;
			return Velocity.ZERO;
		}
		this.settle++;
		double scale = Math.min(1.0, SETTLE_SPEED / distance);
		return new Velocity(dx * scale, dz * scale);
	}

	/** True once after the player failed to reach a target. */
	public boolean takeStuck() {
		boolean was = this.stuck;
		this.stuck = false;
		return was;
	}

	/** BIKE_PLAN B2: legs waiting behind the one under way (the overlay's ride line). */
	public int queued() {
		return this.legs.size();
	}

	/** Legs added so far. */
	public long added() {
		return this.added;
	}

	/** STEP_SPEED_PLAN Part B: ticks that caught up (spent more than one tick's frames) so far. */
	public long catchUpTicks() {
		return this.catchUpTicks;
	}

	/** Moves given up so far. */
	public long stuckCount() {
		return this.stuckCount;
	}
}
