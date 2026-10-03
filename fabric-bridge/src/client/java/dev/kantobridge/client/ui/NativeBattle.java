package dev.kantobridge.client.ui;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The special battles that stay in the hidden game (docs/PLAN.md P9, §6 M8 item 7; PROTOCOL.md {@code battle.native}):
 * the Safari Zone, the old man's catching demo and the unidentified ghost of Pokémon Tower. gen1-bridge announces each
 * ({@code start}, {@code end}, and {@code start} again after every snapshot) and the player plays it on the mirror
 * screen, the hidden game's live picture, every key a Game Boy button.
 *
 * <p>The battle screen itself already reaches us as a mirror UI (the UI observer's {@code BattleState}), with its text
 * boxes as our own screens over it. Between them (the intro's flashes, a box closing before the next opens) the hidden
 * game shows UIs we don't get, so while a native battle runs and nothing of the hidden game's is open, the mirror stays
 * up on a UI of our own ({@link #UI_ID}, never a gen1-bridge id), keys still going to the hidden game; the player is
 * held throughout. As a safety net, like {@link UiRouter}'s, a battle the hidden player has been idle through for
 * {@link #IDLE_GRACE_MS} is over whether or not its {@code end} came (counted as stale). Plain Java, client thread.
 */
public final class NativeBattle {
	/** The mirror UI kept up between the battle's own UIs; gen1-bridge's ids start at 1. */
	public static final int UI_ID = -1;
	public static final Set<String> KINDS = Set.of("safari", "old_man", "ghost");
	/** No battle screen is up while the hidden player stands idle in its overworld this long. */
	public static final long IDLE_GRACE_MS = 1000;

	private final UiSession session = new UiSession(UI_ID, UiKind.MIRROR, new Object(), "native_battle");
	private @Nullable String kind;
	/** Since when the hidden player has been idle, or -1. */
	private long idleSince = -1;
	private long starts;
	private long ends;
	private long stale;

	/** A {@code battle.native{kind, phase}}; anything malformed changes nothing. */
	public void onMessage(JsonObject data) {
		String kind = JsonFields.str(data, "kind");
		String phase = JsonFields.str(data, "phase");
		if (kind == null || !KINDS.contains(kind)) {
			return;
		}
		if ("start".equals(phase)) {
			if (!kind.equals(this.kind)) {
				this.starts++;
			}
			this.kind = kind;
		} else if ("end".equals(phase) && kind.equals(this.kind)) {
			this.kind = null;
			this.ends++;
		}
	}

	/** A snapshot: gen1-bridge announces a battle still running again right after it. */
	public void onSnapshot() {
		this.kind = null;
	}

	/** The link dropped: whatever runs will be announced after the next snapshot. */
	public void onDisconnected() {
		this.kind = null;
		this.idleSince = -1;
	}

	/** {@code player.state}'s mode. */
	public void onMode(@Nullable String mode, long now) {
		if ("idle".equals(mode)) {
			if (this.idleSince < 0) {
				this.idleSince = now;
			}
		} else if (mode != null) {
			this.idleSince = -1;
		}
	}

	/** Ends a battle the hidden player has stood idle through for {@link #IDLE_GRACE_MS} (its {@code end} went missing). */
	public void tick(long now) {
		if (this.kind != null && this.idleSince >= 0 && now - this.idleSince >= IDLE_GRACE_MS) {
			this.kind = null;
			this.stale++;
		}
	}

	public boolean active() {
		return this.kind != null;
	}

	/** The battle running ({@code safari}, {@code old_man}, {@code ghost}), or null. */
	public @Nullable String kind() {
		return this.kind;
	}

	/** What our screen shows: the hidden game's own UIs first, else the battle's mirror while one runs, else nothing. */
	public UiRouter.@Nullable View view(UiRouter router) {
		UiRouter.View view = router.view();
		if (view != null || this.kind == null) {
			return view;
		}
		return new UiRouter.View(this.session, null);
	}

	/** True when a mirror screen showing {@code session} takes the keys: it is the top UI, or our battle mirror alone. */
	public boolean takesInput(UiRouter router, UiSession session) {
		if (session == this.session) {
			return this.kind != null && !router.open();
		}
		return router.isTop(session);
	}

	public long starts() {
		return this.starts;
	}

	public long ends() {
		return this.ends;
	}

	/** Battles ended by the idle safety net. */
	public long stale() {
		return this.stale;
	}
}
