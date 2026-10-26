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
			double budget = FRAMES_PER_TICK;
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
				x += (this.current.x() - x) * share;
				z += (this.current.z() - z) * share;
				this.framesLeft -= use;
				budget -= use;
				if (this.framesLeft <= EPSILON) {
					this.current = null;
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

	/** Moves given up so far. */
	public long stuckCount() {
		return this.stuckCount;
	}
}
