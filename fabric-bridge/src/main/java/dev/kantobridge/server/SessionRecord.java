package dev.kantobridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A world's session record, {@code <world>/kantobridge/session.json} (docs/PLAN.md §4.4 {@code SessionService}): the
 * world's id, which each client hands its gen1-bridge in {@code session.bind} so the hidden game can pair a save slot
 * with this world (DESIGN §8 "Saving"), and the slot each player's hidden game reported back ({@code session.bound}),
 * kept for M9's multiplayer pairing and M10's mismatch warning.
 *
 * <p><b>Format 2 (MULTI_GAME_PLAN §4.6, MG3):</b> with several regions a player pairs one slot per region:
 * {@code players{uuid:{slot, regions:{<id>:{slot}}}}}, where {@code slot} is the legacy region's (format 1's one slot)
 * and {@code regions} holds the others. Format 1 is read as before, and written as long as no player has paired a
 * non-legacy region, so a legacy world's file stays byte for byte what it was.
 *
 * <p><b>WORLD_SESSIONS_PLAN WS2:</b> each pairing may also keep the player's save choice ({@code save}: {@code current},
 * {@code copy}, {@code new}; D3), at the player's level for the legacy region and in {@code regions} for the others.
 * A record holding one is written in format 2.
 *
 * <p>The id is created once and never changes, so it survives restarts, renames and copies of the world folder. The
 * file is replaced atomically on every write; an unreadable one is moved aside (never silently overwritten) and a new
 * id takes its place. Plain Java; the service keeps one per server and touches it on the server thread only.
 */
public final class SessionRecord {
	public static final String FILE = "session.json";
	/** Where an unreadable record is moved, so a broken pairing can be recovered by hand. */
	public static final String BROKEN_SUFFIX = ".broken";
	static final int FORMAT = 1;
	static final int FORMAT_2 = 2;
	public static final int MAX_SLOT = 255;
	private static final Set<String> FIELDS = Set.of("format", "world", "players");
	private static final Set<String> PLAYER_FIELDS_1 = Set.of("slot");
	private static final Set<String> PLAYER_FIELDS_2 = Set.of("slot", "regions", "save");
	private static final Set<String> REGION_FIELDS = Set.of("slot", "save");
	/** WS2: {@code session.bind.save}'s values. */
	public static final Set<String> SAVES = Set.of("current", "copy", "new");

	/** What {@link #loadOrCreate} found. */
	public enum Origin {
		/** Read from the file. */
		READ,
		/** No file yet: a new id. */
		CREATED,
		/** The file was unreadable: moved aside, a new id. */
		REPLACED
	}

	public record Loaded(SessionRecord record, Origin origin) {
	}

	/** One player's pairings: the legacy region's slot and the other regions'. */
	private static final class Entry {
		@Nullable Integer slot;
		final Map<String, Integer> regions = new TreeMap<>();
		/** WS2: the legacy region's save choice, and the other regions'. */
		@Nullable String save;
		final Map<String, String> saves = new TreeMap<>();
	}

	private final String world;
	private final Map<UUID, Entry> players = new TreeMap<>();

	private SessionRecord(String world) {
		this.world = world;
	}

	/** A record with a fresh random id (a lowercase UUID: a valid protocol id). */
	public static SessionRecord create() {
		return new SessionRecord(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
	}

	/** Reads {@code file}, or creates and writes a new record if there is none (or it is unreadable). */
	public static Loaded loadOrCreate(Path file) throws IOException {
		if (Files.isRegularFile(file)) {
			try {
				return new Loaded(parse(Files.readString(file, StandardCharsets.UTF_8)), Origin.READ);
			} catch (DataException | ProtocolException | IOException e) {
				Files.move(file, file.resolveSibling(file.getFileName() + BROKEN_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
				SessionRecord fresh = create();
				fresh.write(file);
				return new Loaded(fresh, Origin.REPLACED);
			}
		}
		SessionRecord fresh = create();
		fresh.write(file);
		return new Loaded(fresh, Origin.CREATED);
	}

	/** Reads a record's JSON strictly (format 1 or 2); a player entry that doesn't fit is an error, like any other. */
	public static SessionRecord parse(String json) throws DataException, ProtocolException {
		DataReader reader = DataReader.of(ProtoJson.parseObject(json), "").allowOnly(FIELDS);
		int format = reader.integer("format");
		if (format != FORMAT && format != FORMAT_2) {
			throw new DataException(reader.path("format"), "unknown format");
		}
		SessionRecord record = new SessionRecord(reader.string("world", Ids.ID));
		DataReader players = reader.object("players");
		for (Map.Entry<String, JsonElement> entry : players.json().entrySet()) {
			String key = entry.getKey();
			if (!Ids.UID.matcher(key).matches()) {
				throw new DataException(players.path(key), "not a player uuid");
			}
			DataReader player = players.object(key).allowOnly(format == FORMAT ? PLAYER_FIELDS_1 : PLAYER_FIELDS_2);
			Entry pairing = new Entry();
			if (format == FORMAT || player.has("slot")) {
				pairing.slot = player.integer("slot", 0, MAX_SLOT);
			}
			if (format == FORMAT_2 && player.has("save")) {
				pairing.save = save(player, "save");
			}
			if (format == FORMAT_2 && player.has("regions")) {
				DataReader regions = player.object("regions");
				for (Map.Entry<String, JsonElement> region : regions.json().entrySet()) {
					if (!DataReader.REGION_ID.matcher(region.getKey()).matches()) {
						throw new DataException(regions.path(region.getKey()), "not a region id");
					}
					DataReader slot = regions.object(region.getKey()).allowOnly(REGION_FIELDS);
					pairing.regions.put(region.getKey(), slot.integer("slot", 0, MAX_SLOT));
					if (slot.has("save")) {
						pairing.saves.put(region.getKey(), save(slot, "save"));
					}
				}
			}
			if (pairing.slot == null && pairing.regions.isEmpty()) {
				throw new DataException(players.path(key), "no pairing");
			}
			record.players.put(UUID.fromString(key), pairing);
		}
		return record;
	}

	private static String save(DataReader reader, String name) throws DataException {
		String value = reader.string(name);
		if (!SAVES.contains(value)) {
			throw new DataException(reader.path(name), "unknown save choice");
		}
		return value;
	}

	public String world() {
		return this.world;
	}

	/** WS2: the save choice recorded with the player's pairing of a region ({@code null}: the legacy region), or null. */
	public @Nullable String save(UUID player, @Nullable String region) {
		Entry entry = this.players.get(player);
		if (entry == null) {
			return null;
		}
		return region == null ? entry.save : entry.saves.get(region);
	}

	/**
	 * WS2: records a slot with its save choice ({@code save} null keeps the one recorded); false when nothing changed.
	 */
	public boolean put(UUID player, @Nullable String region, int slot, @Nullable String save) {
		if (save != null && !SAVES.contains(save)) {
			throw new IllegalArgumentException("unknown save choice");
		}
		boolean changed = this.put(player, region, slot);
		if (save == null) {
			return changed;
		}
		Entry entry = this.players.get(player);
		String before = region == null ? entry.save : entry.saves.get(region);
		if (region == null) {
			entry.save = save;
		} else {
			entry.saves.put(region, save);
		}
		return changed || !save.equals(before);
	}

	/** WS2: forgets a region's pairing ({@code session.no_slot}: the engine lost the slot); false when there was none. */
	public boolean forget(UUID player, @Nullable String region) {
		Entry entry = this.players.get(player);
		if (entry == null) {
			return false;
		}
		boolean had;
		if (region == null) {
			had = entry.slot != null;
			entry.slot = null;
			entry.save = null;
		} else {
			had = entry.regions.remove(region) != null;
			entry.saves.remove(region);
		}
		if (entry.slot == null && entry.regions.isEmpty()) {
			this.players.remove(player);
		}
		return had;
	}

	/** The hidden-game save slot this player's gen1-bridge reported for this world's legacy region, or null. */
	public @Nullable Integer slot(UUID player) {
		Entry entry = this.players.get(player);
		return entry == null ? null : entry.slot;
	}

	/** The slot the player paired with a region ({@code null}: the legacy region), or null. */
	public @Nullable Integer slot(UUID player, @Nullable String region) {
		if (region == null) {
			return this.slot(player);
		}
		Entry entry = this.players.get(player);
		return entry == null ? null : entry.regions.get(region);
	}

	/** The player's non-legacy pairings: region id → slot. */
	public Map<String, Integer> regions(UUID player) {
		Entry entry = this.players.get(player);
		return entry == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(entry.regions));
	}

	/** Records a player's slot for the legacy region; false when it was already that. */
	public boolean put(UUID player, int slot) {
		return this.put(player, null, slot);
	}

	/** Records a player's slot for a region ({@code null}: the legacy region); false when it was already that. */
	public boolean put(UUID player, @Nullable String region, int slot) {
		if (slot < 0 || slot > MAX_SLOT) {
			throw new IllegalArgumentException("slot out of range");
		}
		if (region != null && !DataReader.REGION_ID.matcher(region).matches()) {
			throw new IllegalArgumentException("not a region id");
		}
		Entry entry = this.players.computeIfAbsent(player, key -> new Entry());
		Integer before;
		if (region == null) {
			before = entry.slot;
			entry.slot = slot;
		} else {
			before = entry.regions.put(region, slot);
		}
		return before == null || before != slot;
	}

	public int players() {
		return this.players.size();
	}

	/** The format the record is written in: 2 once any player paired a non-legacy region, else 1. */
	public int format() {
		for (Entry entry : this.players.values()) {
			if (!entry.regions.isEmpty() || entry.save != null) {
				return FORMAT_2;
			}
		}
		return FORMAT;
	}

	public JsonObject toJson() {
		int format = this.format();
		JsonObject players = new JsonObject();
		this.players.forEach((player, pairing) -> {
			JsonObject entry = new JsonObject();
			if (pairing.slot != null) {
				entry.addProperty("slot", pairing.slot);
			}
			if (format == FORMAT_2 && pairing.save != null && pairing.slot != null) {
				entry.addProperty("save", pairing.save);
			}
			if (format == FORMAT_2 && !pairing.regions.isEmpty()) {
				JsonObject regions = new JsonObject();
				pairing.regions.forEach((region, slot) -> {
					JsonObject one = new JsonObject();
					one.addProperty("slot", slot);
					String choice = pairing.saves.get(region);
					if (choice != null) {
						one.addProperty("save", choice);
					}
					regions.add(region, one);
				});
				entry.add("regions", regions);
			}
			players.add(player.toString(), entry);
		});
		JsonObject data = new JsonObject();
		data.addProperty("format", format);
		data.addProperty("world", this.world);
		data.add("players", players);
		return data;
	}

	/** Writes the record (canonical JSON) through a temporary file, so a crash never leaves half a record. */
	public void write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, ProtoJson.write(this.toJson()) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
