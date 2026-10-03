package dev.kantobridge.client.bridge;

import org.jetbrains.annotations.Nullable;

/**
 * When the pause overlay shows, and what it says (M10 crash recovery; DESIGN §9 "if one side crashes, the other pauses";
 * PLAN §2.4 flow 13, §4.3 {@code ConnectionHud}: the status corner and the pause overlay). Plain Java on the client
 * thread: {@link #state} follows the link's state changes, {@link #tick} decides once per client tick.
 *
 * <ul>
 * <li>It shows while the player is in a Kanto world (following the hidden game) and the link hasn't been ready
 * ({@code hello.ok} received) for more than {@link #GRACE_MS}, so a quick reconnect never flashes it.</li>
 * <li>Never during the export or world-build flows ({@code excluded}: the link is paused on purpose then, or a world is
 * being made), and never outside a Kanto world: the status corner says enough there. Leaving an excluded stretch starts
 * the grace over.</li>
 * <li>What it says: gen1-bridge refused the link for its protocol ({@link Kind#VERSION}, with both numbers) or another
 * reason ({@link Kind#REFUSED}); the link opens but nothing answers, for {@link #NOT_ANSWERING_MS} or since the last
 * silence timeout ({@link Kind#NOT_ANSWERING}: Gen1Recomp may show an error); otherwise Gen1Recomp isn't there
 * ({@link Kind#NOT_CONNECTED}). A refusal or a timeout counts until the next handshake or another kind of failure.</li>
 * <li>It goes as soon as the link is ready.</li>
 * </ul>
 */
public final class LinkPause {
	/** How long the link may be down before the overlay shows. */
	public static final long GRACE_MS = 1_500;
	/** A link that opened but got no {@code hello.ok} for this long isn't answering (the session's silence timeout). */
	public static final long NOT_ANSWERING_MS = 10_000;

	/** Why the game waits. */
	public enum Kind {
		/** Gen1Recomp isn't running (the link is refused or dropped), or it hasn't connected yet. */
		NOT_CONNECTED,
		/** The link opens but gen1-bridge doesn't answer: Gen1Recomp may be showing an error. */
		NOT_ANSWERING,
		/** gen1-bridge speaks another protocol: the two halves need matching versions. */
		VERSION,
		/** gen1-bridge refused the link for another reason. */
		REFUSED
	}

	/**
	 * What the overlay shows.
	 *
	 * @param reason        the refusal's code ({@link Kind#REFUSED}), else null
	 * @param theirProtocol gen1-bridge's protocol number ({@link Kind#VERSION}; -1 when it didn't say)
	 * @param retryInMs     time until the next attempt, or -1 while one is under way
	 */
	public record View(Kind kind, @Nullable String reason, int theirProtocol, long retryInMs) {
	}

	private ConnectionState state = ConnectionState.IDLE;
	private @Nullable String refusal;
	private int refusalProtocol = -1;
	private boolean timedOut;
	private long handshakeSince = -1;
	private long downSince = -1;
	private boolean shown;
	private long shows;

	/**
	 * The link changed state ({@link Dispatcher} order, client thread).
	 *
	 * @param detail        the state's detail (a refusal's reason, a drop's cause)
	 * @param theirProtocol gen1-bridge's protocol number when it refused, else -1
	 */
	public void state(ConnectionState next, @Nullable String detail, int theirProtocol, long now) {
		switch (next) {
			case CONNECTED -> {
				this.refusal = null;
				this.refusalProtocol = -1;
				this.timedOut = false;
				this.handshakeSince = -1;
			}
			case HANDSHAKE -> {
				if (this.handshakeSince < 0) {
					this.handshakeSince = now;
				}
			}
			case REJECTED -> {
				this.refusal = detail == null ? "unknown" : detail;
				this.refusalProtocol = theirProtocol;
				this.timedOut = false;
				this.handshakeSince = -1;
			}
			case DISCONNECTED -> {
				// Whatever failed before (a refusal, a timeout) gave way to this failure.
				this.refusal = null;
				this.refusalProtocol = -1;
				this.timedOut = "timeout".equals(detail);
				this.handshakeSince = -1;
			}
			default -> this.handshakeSince = -1;
		}
		this.state = next;
	}

	/**
	 * Once per client tick: whether the overlay shows now.
	 *
	 * @param kanto    the player is in a Kanto world, following the hidden game
	 * @param excluded an export or a world build runs (or the link is paused for one)
	 */
	public boolean tick(long now, boolean kanto, boolean excluded) {
		boolean ready = this.state == ConnectionState.CONNECTED || this.state == ConnectionState.STOPPED;
		if (!kanto || excluded || ready || this.state == ConnectionState.PAUSED) {
			this.downSince = -1;
			this.shown = false;
			return false;
		}
		if (this.downSince < 0) {
			this.downSince = now;
		}
		boolean show = now - this.downSince > GRACE_MS;
		if (show && !this.shown) {
			this.shows++;
		}
		this.shown = show;
		return show;
	}

	/** True while the overlay shows (as of the last {@link #tick}). */
	public boolean shown() {
		return this.shown;
	}

	/**
	 * What the overlay says now, or null while it doesn't show.
	 *
	 * @param retryAt when the next attempt starts (the link's clock), or -1 while one is under way
	 */
	public @Nullable View view(long now, long retryAt) {
		if (!this.shown) {
			return null;
		}
		Kind kind;
		if (this.refusal != null) {
			kind = this.refusal.equals("protocol") ? Kind.VERSION : Kind.REFUSED;
		} else if (this.timedOut || (this.state == ConnectionState.HANDSHAKE && this.handshakeSince >= 0
				&& now - this.handshakeSince >= NOT_ANSWERING_MS)) {
			kind = Kind.NOT_ANSWERING;
		} else {
			kind = Kind.NOT_CONNECTED;
		}
		return new View(kind, kind == Kind.REFUSED ? this.refusal : null, kind == Kind.VERSION ? this.refusalProtocol : -1,
				retryAt < 0 ? -1 : Math.max(0, retryAt - now));
	}

	/** How many times the overlay came up (F7). */
	public long shows() {
		return this.shows;
	}
}
