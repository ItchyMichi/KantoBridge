package dev.kantobridge.client.net;

import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Holds a world's data on both hops until the hidden game is on that world's save (M9; DESIGN §8 "Saving": each
 * multiplayer world gets its own paired story save). gen1-bridge answers {@code session.bind} with
 * {@code session.bound{slot}} only once the hidden game runs that world's save slot, which may mean saving the game it
 * is in, switching slots at runtime and going back to the title first, so the answer can come much later. Until it does,
 * what the hidden game sends belongs to whatever save it still runs, and the world's Cobblemon state must not reach it.
 *
 * <ul>
 * <li><b>Held</b> (in a Kanto world, this link's bind for it not answered yet): gen1-bridge's story data for the server
 * ({@link #UP}) waits here, in order (only the newest {@code bag.sync}); a {@code pc.open} is answered closed at once (the
 * PC can't open before the pairing; gen1-bridge lets its PC menu go). The relays send nothing to the server
 * ({@link #serverOpen}) and forward nothing of the server's to gen1-bridge ({@link #bridgeOpen}): they keep the newest
 * {@code party.sync}/{@code dex.sync}, queue {@code bag.use}s, keep battle results and saves' answers for when they are
 * asked again, and the player is held (the hidden game isn't this world's yet).</li>
 * <li><b>Open</b> ({@code session.bound} for this link's latest bind, matched by its {@code re}): when the hidden game
 * runs the same save as at the last opening (the same gen1-bridge session and world: a reconnect), what waited is this
 * world's and goes to the server in order. Otherwise it may be another world's game: it is dropped, the relays drop
 * their offline queues, and gen1-bridge is asked for a fresh snapshot ({@code resync}): it says whether its game is now
 * at the title ({@code ingame:false}: no teleports) and sends again what that game still has pending. Either way the
 * world's newest syncs go to gen1-bridge then.</li>
 * <li>A world other than the last one drops what the relays queued for the previous world at once.</li>
 * <li>{@code session.switch_waiting} (a forwarded {@code log}) names why the switch waits; {@code session.*} refusals
 * are tried again with a back-off ({@code session.no_slots}: an installation without save slots plays unpaired, as
 * before M9). A Kanto world whose id never arrives opens unpaired after {@value #NO_ID_TIMEOUT_MS} ms. The HUD says
 * what is going on.</li>
 * </ul>
 * Plain Java, client thread only; {@link SessionRuntime} is the glue. Logs carry codes and counts.
 */
public final class SessionGate {
	/** Where the gate is. */
	public enum State {
		/** Not in a Kanto world: nothing to pair (the relays' own rules apply). */
		IDLE,
		/** In a Kanto world whose pairing isn't answered on this link. */
		HELD,
		/** Paired (or playing unpaired): data flows. */
		OPEN
	}

	/** What becomes of one of gen1-bridge's messages for the server. */
	public enum Admit {
		/** To its relay now. */
		PASS,
		/** Waits in the gate. */
		HELD,
		/** {@code pc.open} while held: answer {@code pc.closed} to gen1-bridge at once. */
		ANSWER_CLOSED
	}

	/** gen1-bridge's story data the client relays to the server (G→M → C→S), gated here. */
	public static final Set<String> UP = Set.of(MsgTypes.BATTLE_START, MsgTypes.PARTY_GIVE, MsgTypes.PARTY_TAKE, MsgTypes.PARTY_HEAL,
			MsgTypes.PARTY_RENAME, MsgTypes.PARTY_ITEM, MsgTypes.DAYCARE_DEPOSIT, MsgTypes.DAYCARE_WITHDRAW, MsgTypes.BAG_SYNC,
			MsgTypes.SAVE_BEGIN, MsgTypes.PC_OPEN);
	/** gen1-bridge's reason the switch waits ({@code log} code), from its line's {@code reason=…}. */
	public static final String WAITING_LOG = "session.switch_waiting";
	/** A refusal that won't go away: no save slots to pair (a cartridge install); play on unpaired. */
	public static final String NO_SLOTS = "session.no_slots";

	/** How long a Kanto world may go without a world id before the gate opens unpaired. */
	public static final long NO_ID_TIMEOUT_MS = 10_000;
	/** How long a refusal or an unpaired opening stays on the HUD. */
	public static final long NOTICE_MS = 8_000;
	/** A pairing that takes longer than this shows on the HUD even before gen1-bridge says why. */
	public static final long QUIET_WAIT_MS = 1_500;
	/** After a stale opening, how long the player waits for gen1-bridge's fresh snapshot at most. */
	public static final long SETTLE_TIMEOUT_MS = 10_000;
	/** The first wait before a refused bind is tried again; it doubles up to {@link #RETRY_MAX_MS}. */
	public static final long RETRY_FIRST_MS = 5_000;
	public static final long RETRY_MAX_MS = 60_000;
	static final int HELD_MAX = 64;

	private static final Pattern REASON = Pattern.compile("reason=([a-z][a-z0-9_]{0,31})");
	private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
	private static final Set<String> REASONS = Set.of("busy", "new_game", "save_failed");

	/** What the glue does for the gate. Calls come on the client thread. */
	public interface Port {
		/** A held message goes to its relay now: the gate opened for the world it belongs to. */
		void replay(Envelope message);

		/**
		 * The gate opened. {@code stale}: what waited came from a game that may be another world's (dropped already), so the
		 * relays drop their offline queues too and gen1-bridge is asked for a fresh snapshot; either way the world's newest
		 * {@code party.sync}/{@code dex.sync} go to gen1-bridge, and {@code bag.use}s that waited.
		 */
		void opened(boolean stale);

		/** The player is in another world than before: what the relays queued for the previous one goes. */
		void worldChanged();

		/** The bind is sent again on this link (after a refusal). */
		void rebind();

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long opened;
		public long staleOpenings;
		public long held;
		public long replayed;
		public long dropped;
		public long answeredClosed;
		public long refusals;
		public long waits;
		public long unpaired;
	}

	private final Port port;
	private final Stats stats = new Stats();
	private final Map<String, Long> warnings = new TreeMap<>();
	private final Deque<Envelope> held = new ArrayDeque<>();

	private boolean linked;
	private long link;
	private @Nullable String bridgeSession;
	private boolean kanto;
	private long kantoSince;
	private @Nullable String world;
	/** The last world id seen (a world change drops the previous world's queues). */
	private @Nullable String lastWorld;
	/** This link's latest bind for the current world, by its {@code seq} (-1: none). */
	private long bindSeq = -1;
	private long bindLink = -1;
	private @Nullable String bindWorld;
	/** Open for this link and world. */
	private boolean open;
	private long openLink = -1;
	private @Nullable String openWorld;
	private boolean unpaired;
	/** The gen1-bridge session and world of the last opening: the save the hidden game ran then. */
	private @Nullable String lastOpenSession;
	private @Nullable String lastOpenWorld;
	private boolean everOpened;
	private long heldSince = -1;
	/** A stale opening asked for a fresh snapshot that hasn't come yet: the hidden game's state is unknown. */
	private boolean settling;
	private long settlingSince;
	private @Nullable String waiting;
	private long retryAt = -1;
	private long retryDelay = RETRY_FIRST_MS;
	private @Nullable String noticeKey;
	private @Nullable String noticeArg;
	private long noticeUntil = -1;

	public SessionGate(Port port) {
		this.port = port;
	}

	// ---- inputs ----

	/** A new connection to gen1-bridge (its {@code hello.ok} session id): its own bind is needed before data flows. */
	public void linkUp(@Nullable String session, long now) {
		this.linked = true;
		this.link++;
		this.bridgeSession = session;
		this.closed(now);
	}

	public void linkDown(long now) {
		this.linked = false;
		this.link++;
		this.closed(now);
	}

	/**
	 * Where the player is: in a Kanto world ({@code kanto}) with this id ({@code world}, null while unknown), or not. A
	 * different world than the last one drops the previous world's queues.
	 */
	public void world(boolean kanto, @Nullable String world, long now) {
		if (!kanto) {
			if (this.kanto) {
				this.kanto = false;
				this.world = null;
				this.waiting = null;
				this.retryAt = -1;
			}
			return;
		}
		if (!this.kanto) {
			this.kanto = true;
			this.kantoSince = now;
			this.closed(now);
		}
		if (Objects.equals(world, this.world)) {
			return;
		}
		if (world != null && this.lastWorld != null && !world.equals(this.lastWorld)) {
			this.dropHeld("session.world_changed");
			this.port.worldChanged();
		}
		if (world != null) {
			this.lastWorld = world;
		}
		this.world = world;
		this.closed(now);
	}

	/** The binder sent {@code session.bind} for the current world on this link, as message {@code seq}. */
	public void bindSent(long seq, long now) {
		this.bindSeq = seq;
		this.bindLink = this.link;
		this.bindWorld = this.world;
		if (this.state() != State.OPEN && this.heldSince < 0) {
			this.heldSince = now;
		}
	}

	/** gen1-bridge answered {@code session.bound} (the binder checked it answers this link's latest bind). */
	public void bound(@Nullable Long re, long now) {
		if (!this.answers(re)) {
			this.warn("session.stray_bound");
			return;
		}
		this.open(now, false);
	}

	/**
	 * An {@code error} from gen1-bridge: a {@code session.*} refusal of this link's latest bind (its {@code re}) is shown,
	 * and tried again later; {@code session.no_slots} opens unpaired. Other errors are none of the gate's business.
	 */
	public void error(@Nullable String code, @Nullable Long re, long now) {
		if (code == null || !CODE.matcher(code).matches() || this.state() != State.HELD) {
			return;
		}
		boolean session = code.startsWith("session.");
		boolean ours = re != null && this.bindSeq > 0 && re == this.bindSeq && this.bindLink == this.link;
		if (!session && !ours) {
			return;
		}
		if (re != null && !ours) {
			// A refusal of an older bind: the latest one is still being worked on.
			return;
		}
		if (code.equals("session.pending")) {
			// gen1-bridge refused something of ours while its switch waits; not a refusal of the bind.
			this.warn("session.refused_pending");
			return;
		}
		this.stats.refusals++;
		this.warn("session.refused");
		this.notice("kantobridge.hud.session.refused", code, now);
		if (code.equals(NO_SLOTS)) {
			this.unpaired(now);
			return;
		}
		this.retryAt = now + this.retryDelay;
		this.retryDelay = Math.min(RETRY_MAX_MS, this.retryDelay * 2);
	}

	/** A {@code log} line gen1-bridge forwarded: {@value #WAITING_LOG} names why the switch waits. */
	public void log(@Nullable String code, @Nullable String msg, long now) {
		if (!WAITING_LOG.equals(code) || this.state() != State.HELD) {
			return;
		}
		String reason = "other";
		Matcher matcher = msg == null ? null : REASON.matcher(msg);
		if (matcher != null && matcher.find()) {
			reason = matcher.group(1);
		}
		if (!reason.equals(this.waiting)) {
			this.waiting = reason;
			this.stats.waits++;
		}
	}

	/** gen1-bridge sent a snapshot: after a stale opening, the hidden game's state is known again. */
	public void snapshot() {
		this.settling = false;
	}

	/** Each client tick: a refused bind's retry, a Kanto world that never told its id, a snapshot that never came. */
	public void tick(long now) {
		State state = this.state();
		if (this.settling && now - this.settlingSince > SETTLE_TIMEOUT_MS) {
			this.settling = false;
			this.warn("session.no_snapshot");
		}
		if (state != State.HELD) {
			return;
		}
		if (this.retryAt >= 0 && now >= this.retryAt && this.linked) {
			this.retryAt = -1;
			this.port.rebind();
		}
		if (this.world == null && now - this.kantoSince > NO_ID_TIMEOUT_MS) {
			this.warn("session.no_world_id");
			this.notice("kantobridge.hud.session.unpaired", "no_world_id", now);
			this.unpaired(now);
		}
	}

	// ---- decisions ----

	/**
	 * One of gen1-bridge's messages bound for the server: whether it goes to its relay now, waits here, or (a
	 * {@code pc.open} while held) is answered closed at once.
	 */
	public Admit admit(Envelope message) {
		if (!UP.contains(message.type()) || this.state() != State.HELD) {
			return Admit.PASS;
		}
		if (message.type().equals(MsgTypes.PC_OPEN)) {
			this.stats.answeredClosed++;
			return Admit.ANSWER_CLOSED;
		}
		if (message.type().equals(MsgTypes.BAG_SYNC)) {
			// A whole bag: only the newest matters.
			this.held.removeIf(waiting -> waiting.type().equals(MsgTypes.BAG_SYNC));
		}
		if (this.held.size() >= HELD_MAX) {
			this.held.removeFirst();
			this.stats.dropped++;
			this.warn("session.held_full");
		}
		this.held.addLast(message);
		this.stats.held++;
		return Admit.HELD;
	}

	/** True when the relays may send to the server (paired, or playing unpaired). */
	public boolean serverOpen() {
		return this.state() == State.OPEN;
	}

	/** True when the relays may forward the server's data (or their own answers) to gen1-bridge: not while held. */
	public boolean bridgeOpen() {
		return this.state() != State.HELD;
	}

	/** True while a Kanto world's pairing isn't answered: the hidden game isn't this world's yet. */
	public boolean held() {
		return this.state() == State.HELD;
	}

	/**
	 * True while the player must wait: the pairing isn't answered, or a stale opening's fresh snapshot hasn't come (the
	 * hidden game may be at its title now, so its last known position means nothing: no teleports to it).
	 */
	public boolean holdsPlayer() {
		State state = this.state();
		return state == State.HELD || (state == State.OPEN && this.settling);
	}

	public State state() {
		if (!this.kanto) {
			return State.IDLE;
		}
		return this.open && this.openLink == this.link && Objects.equals(this.openWorld, this.world) ? State.OPEN : State.HELD;
	}

	// ---- views ----

	/** Why the switch waits ({@code busy}, {@code new_game}, {@code save_failed}, …), or null. */
	public @Nullable String waiting() {
		return this.state() == State.HELD ? this.waiting : null;
	}

	/** True when the gate opened without a pairing (no save slots, no world id). */
	public boolean unpaired() {
		return this.state() == State.OPEN && this.unpaired;
	}

	public int heldCount() {
		return this.held.size();
	}

	public Stats stats() {
		return this.stats;
	}

	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	/** The HUD line under the status line, or null. */
	public ConnectionStatus.@Nullable Line hudLine(long now) {
		State state = this.state();
		if (this.noticeKey != null && now < this.noticeUntil) {
			return new ConnectionStatus.Line(this.noticeKey, List.of(this.noticeArg == null ? "?" : this.noticeArg), true);
		}
		if (state != State.HELD || !this.linked) {
			return null;
		}
		if (this.waiting != null) {
			String key = REASONS.contains(this.waiting) ? "kantobridge.hud.session.waiting." + this.waiting : "kantobridge.hud.session.waiting";
			return new ConnectionStatus.Line(key, List.of(this.waiting), true);
		}
		if (this.heldSince >= 0 && now - this.heldSince > QUIET_WAIT_MS) {
			return new ConnectionStatus.Line("kantobridge.hud.session.pairing", List.of(), false);
		}
		return null;
	}

	// ---- helpers ----

	/** The world or link changed: the gate holds until this link's bind for this world is answered. */
	private void closed(long now) {
		this.open = false;
		this.unpaired = false;
		this.settling = false;
		this.waiting = null;
		this.retryAt = -1;
		this.retryDelay = RETRY_FIRST_MS;
		this.heldSince = this.kanto ? now : -1;
	}

	private boolean answers(@Nullable Long re) {
		if (!this.kanto || this.open || this.bindLink != this.link || !Objects.equals(this.bindWorld, this.world)) {
			return false;
		}
		return re == null || this.bindSeq <= 0 || re == this.bindSeq;
	}

	private void unpaired(long now) {
		this.stats.unpaired++;
		this.open(now, true);
	}

	private void open(long now, boolean unpaired) {
		boolean stale = !this.everOpened || !Objects.equals(this.bridgeSession, this.lastOpenSession)
				|| !Objects.equals(this.world, this.lastOpenWorld);
		this.open = true;
		this.openLink = this.link;
		this.openWorld = this.world;
		this.unpaired = unpaired;
		this.everOpened = true;
		this.lastOpenSession = this.bridgeSession;
		this.lastOpenWorld = this.world;
		this.waiting = null;
		this.retryAt = -1;
		this.retryDelay = RETRY_FIRST_MS;
		this.heldSince = -1;
		this.stats.opened++;
		this.settling = stale;
		this.settlingSince = now;
		List<Envelope> waited = new ArrayList<>(this.held);
		this.held.clear();
		if (stale) {
			this.stats.staleOpenings++;
			if (!waited.isEmpty()) {
				this.stats.dropped += waited.size();
				this.warn("session.held_dropped");
			}
			waited.clear();
		}
		this.port.opened(stale);
		for (Envelope message : waited) {
			this.stats.replayed++;
			this.port.replay(message);
		}
	}

	private void dropHeld(String code) {
		if (this.held.isEmpty()) {
			return;
		}
		this.stats.dropped += this.held.size();
		this.held.clear();
		this.warn(code);
	}

	private void notice(String key, String arg, long now) {
		this.noticeKey = key;
		this.noticeArg = arg;
		this.noticeUntil = now + NOTICE_MS;
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}
}
