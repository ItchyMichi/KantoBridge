package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A world's record of its games (docs/specs/WORLD_SESSIONS_PLAN.md §2.1, WS2; protocol/world/world.schema.json):
 * {@code <world>/kantobridge/world.json}, the games the world plays (one per world space: D4 of the multi-game plan) and
 * its start region, whose town is the world spawn and whose game a fresh player follows first. Written by the creation
 * flow (WS6) and "Add a region" (appends); read by the server (sent in the {@code world.regions} document) and the client.
 *
 * <p>A world without the file is a legacy world: {@link #legacy} derives the record from its region table (its regions'
 * games, the start slot 0's region), and nothing about the world changes until something writes the file. Shared by the
 * server and the client; plain Java. The JSON is canonical; ids only.
 *
 * @param start the start region (one of {@code games}' regions)
 * @param games the games in the order they were chosen, each with its region
 */
public record WorldRecord(String start, List<Game> games) {
	public static final String FILE = "world.json";
	public static final int VERSION = 1;
	public static final int MAX_GAMES = 16;
	private static final Set<String> ROOT_FIELDS = Set.of("version", "start", "games");
	private static final Set<String> GAME_FIELDS = Set.of("game", "region");

	/** One game of the world and the region (world space) it plays in. */
	public record Game(String game, String region) {
		public Game {
			if (game == null || !DataReader.GAME_ID.matcher(game).matches()) {
				throw new IllegalArgumentException("invalid game id");
			}
			if (region == null || !DataReader.REGION_ID.matcher(region).matches()) {
				throw new IllegalArgumentException("invalid region id");
			}
		}
	}

	/**
	 * @throws IllegalArgumentException with no games, more than {@link #MAX_GAMES}, two games of one region (one game per
	 *                                  world space), or a start that isn't one of the games' regions
	 */
	public WorldRecord {
		games = List.copyOf(games);
		if (games.isEmpty() || games.size() > MAX_GAMES) {
			throw new IllegalArgumentException("a world plays 1 to " + MAX_GAMES + " games");
		}
		Set<String> regions = new HashSet<>();
		for (Game g : games) {
			if (!regions.add(g.region())) {
				throw new IllegalArgumentException("one game per world space");
			}
		}
		if (start == null || !regions.contains(start)) {
			throw new IllegalArgumentException("the start must be one of the games' regions");
		}
	}

	/** The game of a region, or null. */
	public @Nullable String gameOf(String region) {
		for (Game g : this.games) {
			if (g.region().equals(region)) {
				return g.game();
			}
		}
		return null;
	}

	/** The (first) region a game plays in, or null. */
	public @Nullable String regionOf(String game) {
		for (Game g : this.games) {
			if (g.game().equals(game)) {
				return g.region();
			}
		}
		return null;
	}

	/** The start region's game. */
	public String startGame() {
		String game = this.gameOf(this.start);
		return game == null ? this.games.get(0).game() : game;
	}

	/** This record with one more game (WS6's "Add a region"); the start stays. */
	public WorldRecord with(Game game) {
		List<Game> more = new ArrayList<>(this.games);
		more.add(game);
		return new WorldRecord(this.start, more);
	}

	/**
	 * A legacy world's record: its table's regions' games in slot order, the start slot 0's region (else the first). Null
	 * for an empty table.
	 */
	public static @Nullable WorldRecord legacy(RegionTable table) {
		if (table.entries().isEmpty()) {
			return null;
		}
		List<Game> games = new ArrayList<>();
		for (RegionTable.Entry entry : table.entries()) {
			games.add(new Game(entry.game(), entry.id()));
		}
		RegionTable.Entry first = table.bySlot(0);
		try {
			return new WorldRecord(first != null ? first.id() : table.entries().get(0).id(), games);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.addProperty("start", this.start);
		JsonArray list = new JsonArray();
		for (Game g : this.games) {
			JsonObject one = new JsonObject();
			one.addProperty("game", g.game());
			one.addProperty("region", g.region());
			list.add(one);
		}
		root.add("games", list);
		return root;
	}

	/** Canonical text (sorted keys, compact). */
	public String toJson() {
		return ProtoJson.write(this.toJsonObject());
	}

	/** Reads a record strictly (the schema's rules). */
	public static WorldRecord fromJson(String text) throws DataException {
		try {
			return fromJson(ProtoJson.parse(text));
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
	}

	/** {@link #fromJson(String)} from parsed JSON (the {@code world.regions} document's {@code start} and {@code games}). */
	public static WorldRecord fromJson(JsonElement json) throws DataException {
		DataReader root = DataReader.of(json, "").allowOnly(ROOT_FIELDS);
		if (root.integer("version") != VERSION) {
			throw new DataException(root.path("version"), "unsupported version");
		}
		return read(root);
	}

	/** {@code start} and {@code games} from a reader (the record, or the document that carries them). */
	public static WorldRecord read(DataReader root) throws DataException {
		String start = root.string("start", DataReader.REGION_ID);
		List<Game> games = new ArrayList<>();
		int i = 0;
		for (JsonElement element : root.array("games")) {
			DataReader g = DataReader.of(element, root.path("games") + "[" + i++ + "]").allowOnly(GAME_FIELDS);
			games.add(new Game(g.string("game", DataReader.GAME_ID), g.string("region", DataReader.REGION_ID)));
		}
		try {
			return new WorldRecord(start, games);
		} catch (IllegalArgumentException e) {
			throw new DataException(root.path("games"), e.getMessage());
		}
	}

	/** The record in a world's data folder ({@code <world>/kantobridge}), or null when there is none. */
	public static @Nullable WorldRecord read(Path dataFolder) throws IOException, DataException {
		Path file = dataFolder.resolve(FILE);
		return Files.isRegularFile(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : null;
	}

	/** Writes the record into a world's data folder through a temporary file. */
	public void write(Path dataFolder) throws IOException {
		Files.createDirectories(dataFolder);
		Path file = dataFolder.resolve(FILE);
		Path temp = dataFolder.resolve(FILE + ".tmp");
		Files.writeString(temp, this.toJson() + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
