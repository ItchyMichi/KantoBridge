package dev.kantobridge.client.act;

import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the player why a field move didn't happen (PROTOCOL.md {@code act.field}, {@code act.result}): gen1-bridge answers
 * every {@code act.field} with an {@code act.result} whose {@code re} is the request's {@code seq}; a refusal's reason
 * code becomes a short line over the hotbar, translated through lang keys ({@code kantobridge.field.action.*},
 * {@code kantobridge.field.reason.*}). Answers to other requests (A on a sign that says nothing, START during a cutscene)
 * stay silent: they are ordinary Gen 1 outcomes. One field request is followed at a time: while one waits for its
 * answer (at most {@value #PENDING_MS} ms; the hidden game waits for the player to stand still first) no other goes
 * out, so a held right-click doesn't pile up {@code busy} refusals. A dropped link forgets it, since sequence numbers
 * start over. Plain Java, client thread.
 */
public final class FieldFeedback {
	/** The {@code act.field} refusals PROTOCOL.md lists; anything else reads as {@code other}. */
	public static final Set<String> REASONS = Set.of("not_ingame", "not_idle", "not_adjacent", "no_move", "no_badge", "no_target", "not_here",
			"no_item", "already", "busy", "failed");
	/** The {@code act.field} actions. */
	public static final Set<String> ACTIONS = Set.of("cut", "surf", "strength", "flash", "dig", "teleport", "bicycle", "fish", "fly");
	public static final String OTHER = "other";
	/** How long a request in flight holds back the next: gen1-bridge answers within about half a second. */
	public static final long PENDING_MS = 3000;

	/** A line to show: the action's and the reason's lang keys. */
	public record Message(String actionKey, String reasonKey) {
	}

	private long pendingSeq = -1;
	private @Nullable String pendingAction;
	private long pendingSince;
	private long sent;
	private long refused;
	private @Nullable String lastReason;

	/** An {@code act.field} went out with this {@code seq} at {@code now} (ms). */
	public void sent(long seq, String action, long now) {
		this.pendingSeq = seq;
		this.pendingAction = action;
		this.pendingSince = now;
		this.sent++;
	}

	/** True while a request sent less than {@value #PENDING_MS} ms ago waits for its answer: the next one waits. */
	public boolean busy(long now) {
		return this.pendingAction != null && now - this.pendingSince < PENDING_MS;
	}

	/** The link changed (dropped, reconnected): its sequence numbers and the request in flight are gone. */
	public void connectionChanged() {
		this.pendingSeq = -1;
		this.pendingAction = null;
	}

	/**
	 * An {@code act.result}. Returns the line to show when it refuses the field request in flight, else null (it answered
	 * something else, or the move happened).
	 */
	public @Nullable Message onResult(@Nullable Long re, boolean ok, @Nullable String reason) {
		if (re == null || re != this.pendingSeq || this.pendingAction == null) {
			return null;
		}
		String action = this.pendingAction;
		this.connectionChanged();
		if (ok) {
			return null;
		}
		return this.refusal(action, reason);
	}

	/** A refusal decided here, before anything went out (a target that isn't next to the player). */
	public Message refusal(String action, @Nullable String reason) {
		this.refused++;
		this.lastReason = reason != null && REASONS.contains(reason) ? reason : OTHER;
		return new Message(actionKey(action), reasonKey(reason));
	}

	public static String actionKey(@Nullable String action) {
		return "kantobridge.field.action." + (action != null && ACTIONS.contains(action) ? action : OTHER);
	}

	public static String reasonKey(@Nullable String reason) {
		return "kantobridge.field.reason." + (reason != null && REASONS.contains(reason) ? reason : OTHER);
	}

	/** True while a field request waits for its answer. */
	public boolean pending() {
		return this.pendingAction != null;
	}

	public long sentCount() {
		return this.sent;
	}

	public long refusedCount() {
		return this.refused;
	}

	/** The last refusal's reason code (a known code or {@code other}), or null. */
	public @Nullable String lastReason() {
		return this.lastReason;
	}
}
