package dev.kantobridge.client.net;

import dev.kantobridge.world.WorldRecord;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.3 (WS3; D3, D4, D13): what one link's {@code session.bind} says when the player enters a world:
 * the slot recorded for this player, the first-join choice, and what gen1-bridge does after ({@code continue}). Plain
 * Java; {@link SessionRuntime} feeds it per link and tick.
 *
 * <ul>
 * <li>A legacy world (no region table, no record): today's bind, nothing added.</li>
 * <li>A game the world doesn't list: {@code continue:"title"} (it stays at its title, never followed).</li>
 * <li>Otherwise {@code continue} is {@code play} for the region the player follows (the server's {@code region.state},
 * else the record's start) and {@code park} for every other listed game; the decision waits for the server's
 * presence (or the caller's timeout).</li>
 * <li>A recorded slot is bound as it is, with its recorded choice. Without one: the first-join screen's answer
 * ({@code declined}: {@code continue:"title"}, asked again next join); a game whose title says its slot holds no save
 * is {@code new} without asking; one that does is asked ({@link Ask}); before the game's status is known, wait.</li>
 * </ul>
 */
public final class JoinDecision {
	/** The first-join screen's "no thanks" (Esc): this join leaves the game at its title. */
	public static final String DECLINED = "declined";

	/** What the decision is made from. */
	public record Input(@Nullable WorldRecord record, @Nullable String linkRegion, @Nullable String linkGame, @Nullable String followedRegion,
			boolean presenceKnown, RecordedChoices.@Nullable Choice recorded, boolean slotLost, boolean statusKnown, boolean hasSave,
			@Nullable String answer) {
	}

	/** The fields the bind carries ({@code null}: absent). */
	public record Bind(@Nullable Integer slot, @Nullable String save, @Nullable String cont) {
		public static final Bind TODAY = new Bind(null, null, null);
	}

	/** The outcome. */
	public sealed interface Result permits Wait, Ask, Ready {
	}

	/** Not yet: the presence or the game's status hasn't come. */
	public record Wait(String why) implements Result {
	}

	/** The player must be asked (the first-join screen); {@code cont} is what follows the answer. */
	public record Ask(String cont) implements Result {
	}

	/** Bind now with these fields. */
	public record Ready(Bind bind) implements Result {
	}

	private JoinDecision() {
	}

	public static Result decide(Input in) {
		WorldRecord record = in.record();
		if (record == null) {
			return new Ready(Bind.TODAY);
		}
		String region = in.linkRegion();
		String listed = region == null ? null : record.gameOf(region);
		if (listed == null || (in.linkGame() != null && !listed.equals(in.linkGame()))) {
			return new Ready(new Bind(null, null, "title"));
		}
		if (!in.presenceKnown()) {
			return new Wait("presence");
		}
		String followed = in.followedRegion() != null && record.gameOf(in.followedRegion()) != null ? in.followedRegion() : record.start();
		String cont = region.equals(followed) ? "play" : "park";
		RecordedChoices.Choice recorded = in.recorded();
		if (recorded != null && recorded.slot() != null && !in.slotLost()) {
			return new Ready(new Bind(recorded.slot(), recorded.save(), cont));
		}
		if (DECLINED.equals(in.answer())) {
			return new Ready(new Bind(null, null, "title"));
		}
		if (in.answer() != null) {
			return new Ready(new Bind(null, in.answer(), cont));
		}
		if (!in.statusKnown()) {
			return new Wait("status");
		}
		if (!in.hasSave()) {
			return new Ready(new Bind(null, "new", cont));
		}
		return new Ask(cont);
	}

	/** The choice "continue this save" means here (D3, D14): the current slot singleplayer, a copy on a server. */
	public static String continueChoice(boolean multiplayer) {
		return multiplayer ? "copy" : "current";
	}
}
