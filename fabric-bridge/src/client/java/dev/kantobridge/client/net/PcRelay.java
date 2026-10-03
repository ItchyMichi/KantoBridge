package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.MsgTypes;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bill's PC on the client (docs/PLAN.md §4.4 {@code PcService}, PROTOCOL.md {@code pc.open}/{@code pc.closed}):
 * gen1-bridge holds the hidden game and sends {@code pc.open}; the client relays it, the server opens Cobblemon's PC
 * screen and answers {@code pc.closed} when the screen's link is gone; the client hands that to gen1-bridge. While a PC
 * session is {@link #pending} the player stays held and our screens wait (the glue asks).
 *
 * <ul>
 * <li>Without a server, and when leaving it, {@code pc.closed} is answered here: no answer can come any more.</li>
 * <li>{@code pc.open} carries no id, so a repeat can't be told from a new request by itself: while a session runs, a
 * {@code pc.open} is gen1-bridge asking again (after a reconnect) and is ignored; when the PC closed but gen1-bridge
 * didn't get the answer (the link was down), its {@code pc.open} right after its next snapshot is answered from that
 * record, never opening the PC again (not asked within {@value #ASK_AGAIN_MS} ms of the snapshot, the record is
 * forgotten). Answers are never re-sent unasked.</li>
 * <li>Safety nets, should Cobblemon's screen not behave: if it never shows within {@value #OPEN_TIMEOUT_MS} ms, or it
 * disappears and the server hasn't answered within {@value #UNLINK_AFTER_MS} ms (another screen replaced it without
 * unlinking), the client sends Cobblemon's own unlink so the server answers; with no answer {@value #GIVE_UP_MS} ms
 * later the client answers gen1-bridge itself and drops the server's late answer if it still comes (within
 * {@value #STRAY_MS} ms). Each is a counted warning.</li>
 * </ul>
 * Plain Java, client thread only; {@link SessionRuntime} is the glue.
 */
public final class PcRelay {
	/** Where messages go. */
	public interface Port {
		/** To gen1-bridge; false when not connected (then the message is dropped). */
		boolean toBridge(String type, JsonObject data);

		/** To the server relay; false when there is no server link (then the message is dropped). */
		boolean toServer(String type, JsonObject data);

		/** Tells the server the PC screen is gone with Cobblemon's own unlink, so the server answers {@code pc.closed}. */
		void unlink();

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	public enum State {
		/** No PC session. */
		IDLE,
		/** {@code pc.open} relayed; Cobblemon's PC screen not seen yet. */
		OPENING,
		/** Cobblemon's PC screen is up. */
		OPEN,
		/** The screen went away; waiting for the server's {@code pc.closed}. */
		CLOSING,
		/** The PC closed, but gen1-bridge couldn't be told (no link): answered when it asks again. */
		UNDELIVERED
	}

	static final long OPEN_TIMEOUT_MS = 10_000;
	static final long UNLINK_AFTER_MS = 2_000;
	static final long GIVE_UP_MS = 5_000;
	static final long STRAY_MS = 30_000;
	/** After gen1-bridge's snapshot, how long an undelivered close waits to be asked for before it is forgotten. */
	static final long ASK_AGAIN_MS = 5_000;

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long opened;
		public long closed;
		public long answeredHere;
		public long repeats;
		public long unlinks;
		public long strays;
	}

	private final Port port;
	private final Stats stats = new Stats();
	private final Map<String, Long> warnings = new TreeMap<>();
	private State state = State.IDLE;
	private long since;
	private boolean unlinked;
	/** When gen1-bridge's snapshot came while a close waited to be delivered (-1: none since). */
	private long snapshotAt = -1;
	/** Until when a {@code pc.closed} from the server answers a session closed here already (-1: none expected). */
	private long strayUntil = -1;

	public PcRelay(Port port) {
		this.port = port;
	}

	/** {@code pc.open} from gen1-bridge. */
	public void fromBridge(long now) {
		switch (this.state) {
			case IDLE -> {
				this.strayUntil = -1;
				if (this.port.toServer(MsgTypes.PC_OPEN, new JsonObject())) {
					this.enter(State.OPENING, now);
					this.stats.opened++;
				} else {
					this.warn("pc.no_server");
					this.answerHere();
				}
			}
			case OPENING, OPEN, CLOSING -> this.stats.repeats++;
			case UNDELIVERED -> {
				// gen1-bridge never heard that the PC closed: this is it asking again, not a new PC.
				this.stats.repeats++;
				this.toBridge();
			}
		}
	}

	/** {@code pc.closed} from the server. */
	public void fromServer(long now) {
		if (this.strayUntil >= 0 && now <= this.strayUntil) {
			this.strayUntil = -1;
			this.stats.strays++;
			return;
		}
		if (!this.pending()) {
			// No session of ours runs: passed on when possible (gen1-bridge decides whether it waits for it), never kept.
			this.warn("pc.unexpected_close");
			if (this.port.toBridge(MsgTypes.PC_CLOSED, new JsonObject()) && this.state == State.UNDELIVERED) {
				this.state = State.IDLE;
			}
			return;
		}
		this.stats.closed++;
		this.toBridge();
	}

	/**
	 * gen1-bridge sent a snapshot (a new link): it asks again for a PC it still waits on right after, so an undelivered
	 * close not asked for within {@value #ASK_AGAIN_MS} ms belonged to nothing it waits on any more and is forgotten (a
	 * later {@code pc.open} is then a new PC).
	 */
	public void bridgeSnapshot(long now) {
		if (this.state == State.UNDELIVERED) {
			this.snapshotAt = now;
		}
	}

	/** Left the server: it can't answer any more, so a running session is closed here. */
	public void serverLeft(long now) {
		this.strayUntil = -1;
		if (this.pending()) {
			this.warn("pc.server_left");
			this.answerHere();
		}
	}

	/**
	 * Each client tick, with whether Cobblemon's PC screen is showing.
	 */
	public void tick(long now, boolean screenShown) {
		switch (this.state) {
			case OPENING -> {
				if (screenShown) {
					this.enter(State.OPEN, now);
				} else if (!this.unlinked && now - this.since > OPEN_TIMEOUT_MS) {
					this.warn("pc.open_timeout");
					this.unlink(now);
				} else if (this.unlinked && now - this.since > GIVE_UP_MS) {
					this.giveUp(now);
				}
			}
			case OPEN -> {
				if (!screenShown) {
					this.enter(State.CLOSING, now);
				}
			}
			case CLOSING -> {
				if (screenShown) {
					this.state = State.OPEN;
				} else if (!this.unlinked && now - this.since > UNLINK_AFTER_MS) {
					this.warn("pc.closed_without_unlink");
					this.unlink(now);
				} else if (this.unlinked && now - this.since > GIVE_UP_MS) {
					this.giveUp(now);
				}
			}
			case UNDELIVERED -> {
				if (this.snapshotAt >= 0 && now - this.snapshotAt > ASK_AGAIN_MS) {
					this.warn("pc.close_not_asked");
					this.state = State.IDLE;
				}
			}
			default -> {
			}
		}
	}

	// ---- views ----

	/** True while a PC session runs: the player is held and our screens wait. */
	public boolean pending() {
		return this.state == State.OPENING || this.state == State.OPEN || this.state == State.CLOSING;
	}

	public State state() {
		return this.state;
	}

	public Stats stats() {
		return this.stats;
	}

	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	// ---- helpers ----

	private void enter(State next, long now) {
		this.state = next;
		this.since = now;
		if (next == State.OPENING) {
			this.unlinked = false;
		}
	}

	private void unlink(long now) {
		this.port.unlink();
		this.unlinked = true;
		this.since = now;
		this.stats.unlinks++;
	}

	/** No answer from the server after the unlink: gen1-bridge hears it from here, and a late answer is dropped. */
	private void giveUp(long now) {
		this.warn("pc.no_answer");
		this.strayUntil = now + STRAY_MS;
		this.answerHere();
	}

	private void answerHere() {
		this.stats.answeredHere++;
		this.toBridge();
	}

	/** {@code pc.closed} to gen1-bridge; kept as undelivered (answered on the next ask) when the link is down. */
	private void toBridge() {
		this.state = this.port.toBridge(MsgTypes.PC_CLOSED, new JsonObject()) ? State.IDLE : State.UNDELIVERED;
		this.snapshotAt = -1;
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}
}
