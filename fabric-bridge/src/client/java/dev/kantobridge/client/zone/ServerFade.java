package dev.kantobridge.client.zone;

/**
 * The client's half of {@code route.fade} (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7): fade out over {@link #OUT_MS},
 * stay dark (and hold the player) until the server's teleport has moved the player and the world there is drawn (or
 * {@link #TIMEOUT_MS} after the move was due), then fade in over {@link #IN_MS}. A second fade while dark only pushes the
 * wait back. Plain Java, times in milliseconds.
 */
final class ServerFade {
	static final long OUT_MS = 400;
	static final long IN_MS = 400;
	static final long TIMEOUT_MS = 15_000;
	/** A move shorter than this isn't the teleport (blocks). */
	static final double MOVED = 1.0;

	enum Phase {
		NONE, OUT, HOLD, IN
	}

	private Phase phase = Phase.NONE;
	private long since;
	private long deadline;
	private double fromX;
	private double fromY;
	private double fromZ;

	/** {@code route.fade}: the server moves the player in about {@code ticks}. */
	void start(int ticks, double x, double y, double z, long now) {
		if (this.phase == Phase.NONE || this.phase == Phase.IN) {
			this.phase = Phase.OUT;
			this.since = now;
		}
		this.fromX = x;
		this.fromY = y;
		this.fromZ = z;
		this.deadline = now + ticks * 50L + TIMEOUT_MS;
	}

	void tick(double x, double y, double z, boolean drawn, long now) {
		if (this.phase == Phase.OUT && now - this.since >= OUT_MS) {
			this.phase = Phase.HOLD;
			this.since = now;
		}
		if (this.phase == Phase.HOLD) {
			double dx = x - this.fromX;
			double dy = y - this.fromY;
			double dz = z - this.fromZ;
			boolean moved = dx * dx + dy * dy + dz * dz > MOVED * MOVED;
			if ((moved && drawn) || now > this.deadline) {
				this.phase = Phase.IN;
				this.since = now;
			}
		}
		if (this.phase == Phase.IN && now - this.since >= IN_MS) {
			this.phase = Phase.NONE;
		}
	}

	float alpha(long now) {
		return switch (this.phase) {
			case NONE -> 0.0F;
			case OUT -> Math.min(1.0F, (now - this.since) / (float) OUT_MS);
			case HOLD -> 1.0F;
			case IN -> Math.max(0.0F, 1.0F - (now - this.since) / (float) IN_MS);
		};
	}

	/** True while the player is held: fading out or dark. */
	boolean holding() {
		return this.phase == Phase.OUT || this.phase == Phase.HOLD;
	}

	Phase phase() {
		return this.phase;
	}
}
