package dev.kantobridge.client.world;

import dev.kantobridge.world.WorldRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.2 (WS6; D1–D3): the "Create world" screen's rules, without Minecraft. One row per game the client
 * knows (its links); a tick per game, one "start here" among the ticked, and per game with a save "continue this save" or
 * "new game". Rules: at least one ticked game; one game per world space (a second game of a ticked game's space is greyed:
 * "same world as X"); the start must be a ticked game (default: the first ticked); a ticked game whose link is down is
 * refused. {@link #record} is the world record the build writes; {@link #choices} the creating player's first-join
 * choices. Plain Java.
 */
public final class CreateWorldRules {
	/** One game the client knows. */
	public record Row(String game, String region, boolean connected, boolean hasSave) {
	}

	/** Why Create can't go on (null: it can). */
	public enum Problem {
		NONE_TICKED,
		LINK_DOWN,
		NO_START
	}

	private final List<Row> rows;
	private final Map<String, Boolean> ticked = new LinkedHashMap<>();
	private final Map<String, Boolean> keepSave = new LinkedHashMap<>();
	private @Nullable String start;

	public CreateWorldRules(List<Row> rows) {
		this.rows = List.copyOf(rows);
		for (Row row : this.rows) {
			this.ticked.put(row.game(), false);
			this.keepSave.put(row.game(), row.hasSave());
		}
	}

	public List<Row> rows() {
		return this.rows;
	}

	/** The ticked game of another row's world space, or null: that row can't be ticked ("same world as X"). */
	public @Nullable String blockedBy(Row row) {
		for (Row other : this.rows) {
			if (!other.game().equals(row.game()) && other.region().equals(row.region()) && this.ticked.getOrDefault(other.game(), false)) {
				return other.game();
			}
		}
		return null;
	}

	/** Ticks or unticks a game (a game blocked by its space's ticked one stays unticked). Returns whether it is ticked now. */
	public boolean toggle(String game) {
		Row row = this.row(game);
		if (row == null) {
			return false;
		}
		boolean now = !this.ticked.getOrDefault(game, false);
		if (now && this.blockedBy(row) != null) {
			return false;
		}
		this.ticked.put(game, now);
		if (!now && game.equals(this.start)) {
			this.start = null;
		}
		return now;
	}

	public boolean ticked(String game) {
		return this.ticked.getOrDefault(game, false);
	}

	/** Makes a ticked game the start (D2). */
	public boolean startAt(String game) {
		if (!this.ticked(game)) {
			return false;
		}
		this.start = game;
		return true;
	}

	/** The start game: the chosen one, else the first ticked. */
	public @Nullable String start() {
		if (this.start != null && this.ticked(this.start)) {
			return this.start;
		}
		for (Row row : this.rows) {
			if (this.ticked(row.game())) {
				return row.game();
			}
		}
		return null;
	}

	/** Cycles "continue this save" / "new game" for a game with a save. */
	public boolean toggleSave(String game) {
		Row row = this.row(game);
		if (row == null || !row.hasSave()) {
			return false;
		}
		boolean now = !this.keepSave.getOrDefault(game, true);
		this.keepSave.put(game, now);
		return now;
	}

	public boolean keepsSave(String game) {
		Row row = this.row(game);
		return row != null && row.hasSave() && this.keepSave.getOrDefault(game, true);
	}

	/** What stops Create, or null. */
	public @Nullable Problem problem() {
		List<Row> chosen = this.chosen();
		if (chosen.isEmpty()) {
			return Problem.NONE_TICKED;
		}
		for (Row row : chosen) {
			if (!row.connected()) {
				return Problem.LINK_DOWN;
			}
		}
		return this.start() == null ? Problem.NO_START : null;
	}

	/** The first ticked game whose link is down (the HUD's "Crystal isn't running"), or null. */
	public @Nullable String downGame() {
		for (Row row : this.chosen()) {
			if (!row.connected()) {
				return row.game();
			}
		}
		return null;
	}

	/** The ticked rows in the order shown (their slots: 0, 1, …). */
	public List<Row> chosen() {
		List<Row> out = new ArrayList<>();
		for (Row row : this.rows) {
			if (this.ticked(row.game())) {
				out.add(row);
			}
		}
		return out;
	}

	/** The world record the build writes (null while Create can't go on). */
	public @Nullable WorldRecord record() {
		if (this.problem() != null) {
			return null;
		}
		List<WorldRecord.Game> games = new ArrayList<>();
		String startRegion = null;
		for (Row row : this.chosen()) {
			games.add(new WorldRecord.Game(row.game(), row.region()));
			if (row.game().equals(this.start())) {
				startRegion = row.region();
			}
		}
		return new WorldRecord(startRegion, games);
	}

	/** The creating player's first-join choices by region: {@code current} (keep the save) or {@code new} (D3). */
	public Map<String, String> choices() {
		Map<String, String> out = new LinkedHashMap<>();
		for (Row row : this.chosen()) {
			out.put(row.region(), this.keepsSave(row.game()) ? "current" : "new");
		}
		return out;
	}

	private @Nullable Row row(String game) {
		for (Row row : this.rows) {
			if (row.game().equals(game)) {
				return row;
			}
		}
		return null;
	}
}
