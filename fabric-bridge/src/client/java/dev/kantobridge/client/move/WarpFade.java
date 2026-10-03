package dev.kantobridge.client.move;

import dev.kantobridge.world.Place;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A warp as the Minecraft player sees it (docs/PLAN.md §2.4 flow 7, §4.3 {@code WarpFade}; research P4 §5): fade out
 * and hold the player on {@code warp{start}}; once dark and the destination is known ({@code warp{arrive, to}}), ask the
 * server to teleport ({@code warp.request}); after {@code warp.done} wait until the destination is drawn, then fade in.
 * The client's own teleports (join, reconnect, drift) run the same way through {@link #sync}.
 *
 * <p>Every wait has a timeout that fades back in and reports a counted warning, so a lost message never leaves the
 * screen dark. Fly, Teleport, Dig, Escape Rope, warp pads and holes fade to white as in Gen 1; everything else to
 * black. Plain Java, client thread only.
 */
public final class WarpFade {
	public enum Phase {
		IDLE,
		/** Fading out. */
		OUT,
		/** Dark: waiting for the destination, the server, then the destination's sections. */
		DARK,
		/** Fading in; the player is free again. */
		IN
	}

	/** What the fade asks of the glue. */
	public interface Port {
		/** Sends {@code warp.request{to, kind?}}; false if it couldn't be sent. */
		boolean request(Place to, @Nullable String kind);

		/** True once the player stands at the requested place and the sections around it are drawn. */
		boolean destinationReady();

		/** A counted warning: a timeout or a refusal (a short code). */
		void warn(String code);

		/** The screen starts opening: the warp is over (or given up). */
		void ended();
	}

	public static final long FADE_OUT_MS = 250;
	public static final long FADE_IN_MS = 250;
	/** Fly and Dig play long departure animations before they arrive. */
	public static final long ARRIVE_TIMEOUT_MS = 10_000;
	public static final long DONE_TIMEOUT_MS = 5_000;
	public static final long READY_TIMEOUT_MS = 3_000;
	public static final int BLACK = 0x000000;
	public static final int WHITE = 0xFFFFFF;
	/** Warp kinds Gen 1 fades to white. */
	private static final Set<String> WHITE_KINDS = Set.of("fly", "teleport", "dig", "rope", "pad", "hole");

	private final Port port;
	private Phase phase = Phase.IDLE;
	private long phaseAt;
	private float fromAlpha;
	private long startedAt;
	private @Nullable Place destination;
	private @Nullable String kind;
	private boolean requested;
	private long requestedAt;
	private boolean answered;
	private boolean ok;
	private long answeredAt;
	private int colour = BLACK;
	private long warps;
	private long warnings;

	public WarpFade(Port port) {
		this.port = port;
	}

	/** {@code warp{start, kind}}: fade out (from wherever a fade-in had got to) and wait for the arrival. */
	public void start(String kind, long now) {
		this.begin(kind, now);
	}

	/** {@code warp{arrive, to, kind}}: the destination. Starts the fade if no {@code start} came first. */
	public void arrive(Place to, String kind, long now) {
		if (this.phase == Phase.IDLE || this.phase == Phase.IN) {
			this.begin(kind, now);
		} else {
			this.kind = kind;
			// A second arrival (a warp chained onto the last) is requested afresh.
			this.requested = false;
			this.answered = false;
		}
		this.destination = to;
	}

	/** A teleport this side wants (no hidden-game warp): ignored while a warp is already dark or fading out. */
	public boolean sync(Place to, long now) {
		if (this.phase == Phase.OUT || this.phase == Phase.DARK) {
			return false;
		}
		this.begin(null, now);
		this.destination = to;
		return true;
	}

	/** Back to idle at once, no fade (leaving the world, or the player no longer followed). */
	public void reset() {
		this.phase = Phase.IDLE;
		this.destination = null;
		this.kind = null;
		this.requested = false;
		this.answered = false;
	}

	/** {@code warp.done{ok}} for the request in flight; anything else is ignored. */
	public void done(boolean ok, long now) {
		if (this.phase != Phase.DARK || !this.requested || this.answered) {
			return;
		}
		this.answered = true;
		this.ok = ok;
		this.answeredAt = now;
	}

	/** Advances the fade; call every tick. */
	public void tick(long now) {
		if (this.phase == Phase.OUT && this.alpha(now) >= 1.0F) {
			this.phase = Phase.DARK;
			this.phaseAt = now;
		}
		if (this.phase == Phase.DARK) {
			this.dark(now);
		}
		if (this.phase == Phase.IN && now - this.phaseAt >= FADE_IN_MS) {
			this.phase = Phase.IDLE;
		}
	}

	private void dark(long now) {
		Place to = this.destination;
		if (to == null) {
			if (now - this.startedAt > ARRIVE_TIMEOUT_MS) {
				this.giveUp("arrive_timeout", now);
			}
			return;
		}
		if (!this.requested) {
			this.requested = true;
			this.requestedAt = now;
			if (!this.port.request(to, this.kind)) {
				this.giveUp("request_failed", now);
			}
			return;
		}
		if (!this.answered) {
			if (now - this.requestedAt > DONE_TIMEOUT_MS) {
				this.giveUp("done_timeout", now);
			}
			return;
		}
		if (!this.ok) {
			this.giveUp("refused", now);
			return;
		}
		if (this.port.destinationReady()) {
			this.open(now);
		} else if (now - this.answeredAt > READY_TIMEOUT_MS) {
			this.giveUp("ready_timeout", now);
		}
	}

	private void begin(@Nullable String warpKind, long now) {
		this.fromAlpha = this.alpha(now);
		this.phase = Phase.OUT;
		this.phaseAt = now;
		this.startedAt = now;
		this.destination = null;
		this.kind = warpKind;
		this.requested = false;
		this.answered = false;
		this.colour = warpKind != null && WHITE_KINDS.contains(warpKind) ? WHITE : BLACK;
		this.warps++;
	}

	private void giveUp(String code, long now) {
		this.warnings++;
		this.port.warn(code);
		this.open(now);
	}

	private void open(long now) {
		this.phase = Phase.IN;
		this.phaseAt = now;
		this.port.ended();
	}

	/** How opaque the overlay is now, 0 to 1. */
	public float alpha(long now) {
		return switch (this.phase) {
			case IDLE -> 0.0F;
			case OUT -> Math.min(1.0F, this.fromAlpha + (now - this.phaseAt) / (float) FADE_OUT_MS);
			case DARK -> 1.0F;
			case IN -> Math.max(0.0F, 1.0F - (now - this.phaseAt) / (float) FADE_IN_MS);
		};
	}

	/** The overlay colour (RGB). */
	public int colour() {
		return this.colour;
	}

	public Phase phase() {
		return this.phase;
	}

	/** True while anything is drawn (fading out, dark or fading in). */
	public boolean active() {
		return this.phase != Phase.IDLE;
	}

	/** True while the screen is fading out or dark: the player is held and positions wait. */
	public boolean holding() {
		return this.phase == Phase.OUT || this.phase == Phase.DARK;
	}

	/** The place being requested or waited for, if any. */
	public @Nullable Place destination() {
		return this.destination;
	}

	/** True while a {@code warp.request} is out without an answer. */
	public boolean awaitingAnswer() {
		return this.phase == Phase.DARK && this.requested && !this.answered;
	}

	public long warps() {
		return this.warps;
	}

	public long warnings() {
		return this.warnings;
	}
}
