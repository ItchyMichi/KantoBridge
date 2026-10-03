package dev.kantobridge.client.net;

import org.jetbrains.annotations.Nullable;

/**
 * This player's {@code activity} for the server (protocol/messages/activity.schema.json; M9's speech bubbles): in a
 * Cobblemon battle, in a dialogue (a hidden-game UI is open on our side), in a cutscene (the hidden game drives the
 * player: {@code cutscene} or {@code forced} mode), or nothing; in that order of precedence. It is sent when it changes
 * and again after every join. Plain Java, client thread only.
 */
public final class ActivityTracker {
	public static final String NONE = "none";
	public static final String DIALOGUE = "dialogue";
	public static final String CUTSCENE = "cutscene";
	public static final String BATTLE = "battle";

	private @Nullable String sent;

	/**
	 * @param battle   the player fights a Cobblemon battle
	 * @param dialogue a hidden-game UI is open on our side
	 * @param mode     the hidden game's mode ({@code player.state}), or null when unknown
	 */
	public static String of(boolean battle, boolean dialogue, @Nullable String mode) {
		if (battle) {
			return BATTLE;
		}
		if (dialogue) {
			return DIALOGUE;
		}
		if ("cutscene".equals(mode) || "forced".equals(mode)) {
			return CUTSCENE;
		}
		return NONE;
	}

	/** The activity to send now, or null when the server already has it. */
	public @Nullable String next(String current) {
		return current.equals(this.sent) ? null : current;
	}

	/** The server got {@code activity}. */
	public void sent(String activity) {
		this.sent = activity;
	}

	/** A new server (or none): the next activity goes out whatever it is. */
	public void reset() {
		this.sent = null;
	}

	public @Nullable String last() {
		return this.sent;
	}
}
