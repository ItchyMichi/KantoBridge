package dev.kantobridge.battle;

import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * How a Cobblemon battle ended, as {@code battle.result} reports it (docs/PLAN.md §4.4 {@code BattleService},
 * protocol/messages/battle.result.schema.json). Cobblemon runs one set of end handlers for every ending; whether
 * anybody won tells a real result from {@code stop()} (a crash, a disconnect, {@code /stopbattle}), and the
 * {@code BATTLE_FLED} event marks the stops that were a flight. Plain Java.
 */
public enum BattleOutcome {
	WIN, LOSE, FLED, CAUGHT, ABORTED;

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The outcome of an ended battle.
	 *
	 * @param decided   Cobblemon named winners (a real result, not {@code stop()})
	 * @param playerWon the player is among the winners; a forfeit is a loss (Showdown makes the other side win)
	 * @param fled      {@code BATTLE_FLED} fired for this battle before it stopped
	 */
	public static BattleOutcome classify(boolean decided, boolean playerWon, boolean fled) {
		if (decided) {
			return playerWon ? WIN : LOSE;
		}
		return fled ? FLED : ABORTED;
	}

	/** The {@code battle.result} body for this outcome (not {@link #CAUGHT}, which also needs the catch). */
	public JsonObject result(String battle) {
		if (this == CAUGHT) {
			throw new IllegalStateException("a catch needs its Pokémon");
		}
		JsonObject data = new JsonObject();
		data.addProperty("battle", battle);
		data.addProperty("outcome", this.id());
		return data;
	}
}
