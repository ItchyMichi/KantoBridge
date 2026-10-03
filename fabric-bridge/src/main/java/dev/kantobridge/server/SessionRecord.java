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
 * <p>The id is created once and never changes, so it survives restarts, renames and copies of the world folder. The
 * file is replaced atomically on every write; an unreadable one is moved aside (never silently overwritten) and a new
 * id takes its place. Plain Java; the service keeps one per server and touches it on the server thread only.
 */
public final class SessionRecord {
	public static final String FILE = "session.json";
	/** Where an unreadable record is moved, so a broken pairing can be recovered by hand. */
	public static final String BROKEN_SUFFIX = ".broken";
	static final int FORMAT = 1;
	public static final int MAX_SLOT = 255;
	private static final Set<String> FIELDS = Set.of("format", "world", "players");

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

	private final String world;
	private final Map<UUID, Integer> slots = new TreeMap<>();

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

	/** Reads a record's JSON strictly; a player entry that doesn't fit is an error, like any other. */
	public static SessionRecord parse(String json) throws DataException, ProtocolException {
		DataReader reader = DataReader.of(ProtoJson.parseObject(json), "").allowOnly(FIELDS);
		if (reader.integer("format") != FORMAT) {
			throw new DataException(reader.path("format"), "unknown format");
		}
		SessionRecord record = new SessionRecord(reader.string("world", Ids.ID));
		DataReader players = reader.object("players");
		for (Map.Entry<String, JsonElement> entry : players.json().entrySet()) {
			String key = entry.getKey();
			if (!Ids.UID.matcher(key).matches()) {
				throw new DataException(players.path(key), "not a player uuid");
			}
			DataReader player = players.object(key).allowOnly(Set.of("slot"));
			record.slots.put(UUID.fromString(key), player.integer("slot", 0, MAX_SLOT));
		}
		return record;
	}

	public String world() {
		return this.world;
	}

	/** The hidden-game save slot this player's gen1-bridge reported for this world, or null. */
	public @Nullable Integer slot(UUID player) {
		return this.slots.get(player);
	}

	/** Records a player's slot; false when it was already that. */
	public boolean put(UUID player, int slot) {
		if (slot < 0 || slot > MAX_SLOT) {
			throw new IllegalArgumentException("slot out of range");
		}
		Integer before = this.slots.put(player, slot);
		return before == null || before != slot;
	}

	public int players() {
		return this.slots.size();
	}

	public JsonObject toJson() {
		JsonObject players = new JsonObject();
		this.slots.forEach((player, slot) -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("slot", slot);
			players.add(player.toString(), entry);
		});
		JsonObject data = new JsonObject();
		data.addProperty("format", FORMAT);
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
