package dev.kantobridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One hidden game's story progress as its last {@code game.status} reported it (docs/specs/MULTI_GAME_PLAN.md §4.7, MG3;
 * protocol/messages/game.status.schema.json): where the playthrough stands ({@code title}, {@code fresh}, {@code saved}),
 * whether it is in its overworld, the badge count, the game profile's markers (starter, champion, …) and the save's
 * player name. Kept per player and region in the {@code kantobridge:progress} attachment ({@link ProgressService}); the
 * pass reads it to choose between resuming and starting the far game (MG5).
 *
 * @param playthrough {@code title}, {@code fresh} or {@code saved}
 * @param ingame      the hidden game is in its overworld
 * @param started     the playthrough is not at the title
 * @param badges      badges held, 0..16
 * @param markers     marker name → reached
 * @param name        the save's player name, or null (at the title)
 * @param at          when it was reported (Unix seconds), or -1 when unknown
 */
public record ProgressRecord(String playthrough, boolean ingame, boolean started, int badges, Map<String, Boolean> markers, @Nullable String name,
		long at) {
	public static final String TITLE = "title";
	public static final String FRESH = "fresh";
	public static final String SAVED = "saved";
	public static final Set<String> PLAYTHROUGHS = Set.of(TITLE, FRESH, SAVED);
	public static final int MAX_BADGES = 16;
	public static final int MAX_NAME = 16;
	public static final int MAX_SLOT = 255;
	/** A region whose game never reported: at the title, nothing reached. */
	public static final ProgressRecord NONE = new ProgressRecord(TITLE, false, false, 0, Map.of(), null, -1);

	private static final Set<String> FIELDS = Set.of("game", "region", "playthrough", "ingame", "started", "badges", "markers", "name", "slot",
			"visitors");
	/** FP1 (FOREIGN_POKEMON_PLAN §4.1): at most a party's worth of visitors. */
	public static final int MAX_VISITORS = 6;
	private static final Pattern MARKER = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

	public static final Codec<ProgressRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("playthrough").forGetter(ProgressRecord::playthrough),
			Codec.BOOL.optionalFieldOf("ingame", false).forGetter(ProgressRecord::ingame),
			Codec.BOOL.optionalFieldOf("started", false).forGetter(ProgressRecord::started),
			Codec.INT.optionalFieldOf("badges", 0).forGetter(ProgressRecord::badges),
			Codec.unboundedMap(Codec.STRING, Codec.BOOL).optionalFieldOf("markers", Map.of()).forGetter(ProgressRecord::markers),
			Codec.STRING.optionalFieldOf("name").forGetter(record -> Optional.ofNullable(record.name())),
			Codec.LONG.optionalFieldOf("at", -1L).forGetter(ProgressRecord::at)
	).apply(instance, (playthrough, ingame, started, badges, markers, name, at) -> new ProgressRecord(
			PLAYTHROUGHS.contains(playthrough) ? playthrough : TITLE, ingame, started, Math.max(0, Math.min(MAX_BADGES, badges)), markers,
			name.orElse(null), at)));

	public ProgressRecord {
		if (!PLAYTHROUGHS.contains(playthrough)) {
			throw new IllegalArgumentException("unknown playthrough");
		}
		if (badges < 0 || badges > MAX_BADGES) {
			throw new IllegalArgumentException("badges out of range");
		}
		markers = Collections.unmodifiableMap(new TreeMap<>(markers));
	}

	/**
	 * What one {@code game.status} says, read like its schema; {@code visitors} (FP1) is how many party members the game
	 * shows as visitors, or null when it didn't say (the HUD's line, FP2).
	 */
	public record Status(String game, @Nullable String region, ProgressRecord progress, @Nullable Integer slot, @Nullable Integer visitors) {
		public Status(String game, @Nullable String region, ProgressRecord progress, @Nullable Integer slot) {
			this(game, region, progress, slot, null);
		}
	}

	/**
	 * Reads a {@code game.status} strictly (every field the schema names, nothing else). {@code now} is the report's time
	 * (Unix seconds).
	 */
	public static Status read(JsonObject data, long now) throws DataException {
		DataReader reader = DataReader.of(data, "").allowOnly(FIELDS);
		String game = reader.string("game", DataReader.GAME_ID);
		String region = reader.optionalRegion();
		String playthrough = reader.string("playthrough");
		if (!PLAYTHROUGHS.contains(playthrough)) {
			throw new DataException(reader.path("playthrough"), "unknown value");
		}
		boolean ingame = reader.bool("ingame");
		boolean started = reader.bool("started");
		int badges = reader.integer("badges", 0, MAX_BADGES);
		DataReader markerReader = reader.object("markers");
		Map<String, Boolean> markers = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : markerReader.json().entrySet()) {
			if (!MARKER.matcher(entry.getKey()).matches()) {
				throw new DataException(markerReader.path(), "bad marker name");
			}
			JsonElement value = entry.getValue();
			if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
				throw new DataException(markerReader.path(entry.getKey()), "not a boolean");
			}
			markers.put(entry.getKey(), value.getAsBoolean());
		}
		String name = null;
		if (reader.has("name")) {
			name = reader.string("name");
			if (name.isEmpty() || name.length() > MAX_NAME) {
				throw new DataException(reader.path("name"), "bad length");
			}
		}
		Integer slot = reader.has("slot") ? reader.integer("slot", 0, MAX_SLOT) : null;
		Integer visitors = reader.has("visitors") ? reader.integer("visitors", 0, MAX_VISITORS) : null;
		return new Status(game, region, new ProgressRecord(playthrough, ingame, started, badges, markers, name, now), slot, visitors);
	}

	/** True when the game was ever started in this region (a resume at the pass; a start otherwise). */
	public boolean isStarted() {
		return this.started || !TITLE.equals(this.playthrough);
	}

	/** Whether a marker was reached (false when the game never reported it). */
	public boolean marker(String name) {
		return this.markers.getOrDefault(name, false);
	}
}
