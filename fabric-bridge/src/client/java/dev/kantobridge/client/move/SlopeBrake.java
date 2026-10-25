package dev.kantobridge.client.move;

import org.jetbrains.annotations.Nullable;

/**
 * Cycling Road's brake (docs/PLAN.md P19, §6 M8 item 5): the hidden game rolls a biker downhill one step at a time
 * unless a direction, A or B is held, and the roll pulls the Minecraft player along ({@code player.pos} kind
 * {@code slope}, walked by the {@link ForcedMover}). Holding sneak while the hidden player is on a slope map holds B
 * there: {@code move.hold{button:"b", on}} on each change. While on a slope map sneak only brakes ({@link InputLock}
 * keeps the player from crouching). Plain Java; {@link MoveRuntime} sends what {@link #tick} returns.
 *
 * <p>gen1-bridge drops every hold when its link drops, so after a reconnect a held brake is sent again ({@link #reset}).
 */
public final class SlopeBrake {
	/** What gen1-bridge was last told (false: nothing held). */
	private boolean held;
	private long sent;

	/**
	 * One tick.
	 *
	 * @param following the movement sync follows the hidden game for this player (a Kanto world, not a free camera)
	 * @param connected the link to gen1-bridge is up
	 * @param slope     the hidden player is on a slope map ({@code player.state.slope})
	 * @param sneak     the sneak key is down
	 * @return the {@code on} value to send now, or null when nothing changes
	 */
	public @Nullable Boolean tick(boolean following, boolean connected, boolean slope, boolean sneak) {
		return this.tick(following, connected, slope, sneak, false);
	}

	/**
	 * The same with a run wanted too ({@code run}: the sprint key on foot in a game that runs, FA8 item 7): Emerald runs
	 * while B is held, so the one hold of B serves both.
	 */
	public @Nullable Boolean tick(boolean following, boolean connected, boolean slope, boolean sneak, boolean run) {
		if (!connected) {
			// Nothing can be sent, and gen1-bridge forgets the hold with the link anyway.
			this.held = false;
			return null;
		}
		boolean want = following && (slope && sneak || run);
		if (want == this.held) {
			return null;
		}
		this.held = want;
		this.sent++;
		return want;
	}

	/** The last value could not be sent, or the link was replaced: start from "not held" again. */
	public void reset() {
		this.held = false;
	}

	public boolean held() {
		return this.held;
	}

	/** Holds and releases sent so far. */
	public long sent() {
		return this.sent;
	}
}
