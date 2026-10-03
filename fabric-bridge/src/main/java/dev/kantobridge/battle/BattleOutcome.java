package dev.kantobridge.battle;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.UUID;

/**
 * How a Cobblemon battle ended, as {@code battle.result} reports it (docs/PLAN.md §4.4 {@code BattleService} and
 * {@code EncounterService}, protocol/messages/battle.result.schema.json). Cobblemon runs one set of end handlers for every
 * ending; whether anybody won tells a real result from {@code stop()} (a crash, a disconnect, {@code /stopbattle}), the
 * {@code BATTLE_FLED} event (or our own end of a flee attempt) marks the stops that were a flight, and Cobblemon's
 * capture event marks a won wild battle that was a catch. Plain Java.
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
	 * @param fled      the battle ended as a flight before it stopped
	 */
	public static BattleOutcome classify(boolean decided, boolean playerWon, boolean fled) {
		return classify(decided, playerWon, fled, false);
	}

	/**
	 * {@link #classify(boolean, boolean, boolean)} for a wild battle: a catch is a win that took the Pokémon. A catch
	 * counts even when the battle then stopped without a winner (the Pokémon is in the party already).
	 */
	public static BattleOutcome classify(boolean decided, boolean playerWon, boolean fled, boolean caught) {
		if (caught) {
			return CAUGHT;
		}
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

	/** The {@code battle.result} body for a catch: the caught Pokémon's Cobblemon uuid, national dex number and level. */
	public static JsonObject caught(String battle, UUID uid, int dex, int level) {
		if (dex < 1 || dex > 2000 || level < 1 || level > 100) {
			throw new IllegalArgumentException("not a catch the protocol can carry");
		}
		JsonObject catchData = new JsonObject();
		catchData.addProperty("uid", uid.toString());
		catchData.addProperty("dex", dex);
		catchData.addProperty("level", level);
		JsonObject data = new JsonObject();
		data.addProperty("battle", battle);
		data.addProperty("outcome", CAUGHT.id());
		data.add("caught", catchData);
		return data;
	}
}
