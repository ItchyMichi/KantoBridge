package dev.kantobridge.client.move;

import dev.kantobridge.world.Dir;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The rider's held intent while the hidden player is on a bike (docs/specs/BIKE_PLAN.md §2.4, D4): each world tick the
 * movement keys as read physically (the input lock zeroes the player's own impulses) and the camera's yaw become one Gen 1
 * direction ({@link Intent}), and sneak or jump becomes B. Plain Java; {@link MoveController} sends what {@link #tick}
 * returns as {@code move.ride}.
 *
 * <ul>
 * <li><b>Diagonals.</b> Two keys whose world directions don't clearly differ keep the direction held the tick before (none
 * if nothing was held): a slight angle never flickers between two directions.</li>
 * <li><b>The side-jump assist.</b> Emerald's Acro bike jumps sideways (or turns round in a jump) when B comes with a new
 * direction within 4 frames, the direction no later than B (bike/rse.lua:446-453, :489-512). A B pressed with no direction
 * held is therefore held back up to {@value #ASSIST_TICKS} ticks: a direction pressed meanwhile goes out in the same
 * message as B, so gen1-bridge presses both in one logic step; with none, B goes alone (a wheelie, a bunny hop's charge).
 * A B pressed while a direction is held goes at once.</li>
 * </ul>
 */
public final class RideInput {
	/** What gen1-bridge is asked to hold. */
	public record Ride(@Nullable Dir dir, boolean b) {
		public static final Ride NONE = new Ride(null, false);
	}

	/** Ticks a B pressed with no direction waits for one. */
	public static final int ASSIST_TICKS = 2;

	private @Nullable Dir dir;
	private boolean bWas;
	/** Ticks a fresh B has waited (-1: none waiting). */
	private int waiting = -1;
	private Ride sent = Ride.NONE;
	private long changes;
	private long assisted;

	/**
	 * One tick.
	 *
	 * @param forward the forward key is down (likewise {@code back}, {@code left}, {@code right}: strafing)
	 * @param yaw     the camera's yaw in degrees (0 looks south)
	 * @param b       sneak or jump is down
	 * @return the ride to send now, or null when nothing changes
	 */
	public @Nullable Ride tick(boolean forward, boolean back, boolean left, boolean right, float yaw, boolean b) {
		float fwd = (forward ? 1.0F : 0.0F) - (back ? 1.0F : 0.0F);
		float side = (left ? 1.0F : 0.0F) - (right ? 1.0F : 0.0F);
		Dir now;
		if (fwd == 0.0F && side == 0.0F) {
			now = null;
		} else {
			Dir d = Intent.of(side, fwd, yaw);
			now = d != null ? d : this.dir;
		}
		boolean dirChanged = now != this.dir;
		this.dir = now;
		boolean rising = b && !this.bWas;
		this.bWas = b;
		boolean bOut = b;
		if (!b) {
			// a tap shorter than the wait still goes out: pressed now, released the next tick
			bOut = this.waiting >= 0;
			this.waiting = -1;
		} else if (rising && now == null) {
			// a fresh B with no direction: wait for one (the side-jump assist)
			this.waiting = 0;
			bOut = false;
		} else if (this.waiting >= 0) {
			if (now != null && dirChanged) {
				this.waiting = -1;
				this.assisted++;
			} else if (++this.waiting >= ASSIST_TICKS) {
				this.waiting = -1;
			} else {
				bOut = false;
			}
		}
		Ride want = new Ride(now, bOut);
		if (want.equals(this.sent)) {
			return null;
		}
		this.sent = want;
		this.changes++;
		return want;
	}

	/** The ride last returned (what gen1-bridge holds). */
	public Ride sent() {
		return this.sent;
	}

	/** The ride ended (off the bike, a warp, a link change, a mode other than idle): nothing is held any more. */
	public void reset() {
		this.dir = null;
		this.bWas = false;
		this.waiting = -1;
		this.sent = Ride.NONE;
	}

	/** True while something is held (a null ride is owed when the ride ends). */
	public boolean holding() {
		return !Objects.equals(this.sent, Ride.NONE);
	}

	/** Rides sent so far. */
	public long changes() {
		return this.changes;
	}

	/** B sent together with a direction by the side-jump assist. */
	public long assisted() {
		return this.assisted;
	}
}
