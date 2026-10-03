package dev.kantobridge.pokemon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.world.DataException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One player's daycare (docs/PLAN.md P15): the Pokémon the hidden game's daycare holds, each as Cobblemon's own JSON of
 * it, in {@code <world>/kantobridge/daycare/<player uuid>.json}:
 *
 * <pre>{"format": 1, "pokemon": [{"uid": "…", "data": {…Cobblemon's Pokémon JSON…}}]}</pre>
 *
 * The file is replaced atomically on every change; an unreadable one is moved aside ({@code .broken}, so its Pokémon can
 * be recovered by hand), never overwritten. The Pokémon's data (a nickname, a trainer's name) stays in the world folder
 * like Cobblemon's own stores and is never logged. Plain Java; the service touches it on the server thread only.
 */
public final class DaycareStore {
	public static final int FORMAT = 1;
	public static final String BROKEN_SUFFIX = ".broken";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** What {@link #load} found. */
	public enum Origin {
		READ,
		/** No file: an empty daycare. */
		EMPTY,
		/** The file was unreadable: moved aside, an empty daycare. */
		REPLACED
	}

	public record Loaded(DaycareStore store, Origin origin) {
	}

	private final Map<UUID, JsonObject> pokemon = new LinkedHashMap<>();

	public static DaycareStore empty() {
		return new DaycareStore();
	}

	/** Reads {@code file}; a missing file is an empty daycare, an unreadable one is moved aside first. */
	public static Loaded load(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return new Loaded(empty(), Origin.EMPTY);
		}
		try {
			return new Loaded(parse(Files.readString(file, StandardCharsets.UTF_8)), Origin.READ);
		} catch (DataException | IOException e) {
			Files.move(file, file.resolveSibling(file.getFileName() + BROKEN_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
			return new Loaded(empty(), Origin.REPLACED);
		}
	}

	public static DaycareStore parse(String text) throws DataException {
		JsonElement root;
		try {
			root = JsonParser.parseString(text);
		} catch (JsonParseException e) {
			throw new DataException("", "invalid JSON");
		}
		if (!root.isJsonObject()) {
			throw new DataException("", "not an object");
		}
		JsonObject object = root.getAsJsonObject();
		JsonElement format = object.get("format");
		if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber() || format.getAsInt() != FORMAT) {
			throw new DataException("/format", "unknown format");
		}
		JsonElement list = object.get("pokemon");
		if (list == null || !list.isJsonArray()) {
			throw new DataException("/pokemon", "not an array");
		}
		DaycareStore store = new DaycareStore();
		int i = 0;
		for (JsonElement item : list.getAsJsonArray()) {
			String path = "/pokemon/" + i++;
			if (!item.isJsonObject()) {
				throw new DataException(path, "not an object");
			}
			JsonElement uid = item.getAsJsonObject().get("uid");
			JsonElement data = item.getAsJsonObject().get("data");
			if (uid == null || !uid.isJsonPrimitive() || !uid.getAsJsonPrimitive().isString() || !Ids.UID.matcher(uid.getAsString()).matches()) {
				throw new DataException(path + "/uid", "not a uid");
			}
			if (data == null || !data.isJsonObject()) {
				throw new DataException(path + "/data", "not an object");
			}
			if (store.pokemon.put(UUID.fromString(uid.getAsString()), data.getAsJsonObject()) != null) {
				throw new DataException(path + "/uid", "duplicate");
			}
		}
		return store;
	}

	public boolean contains(UUID uid) {
		return this.pokemon.containsKey(uid);
	}

	/** A copy of the Pokémon's data, or null. */
	public @Nullable JsonObject get(UUID uid) {
		JsonObject data = this.pokemon.get(uid);
		return data == null ? null : data.deepCopy();
	}

	public void put(UUID uid, JsonObject data) {
		this.pokemon.put(uid, data.deepCopy());
	}

	public boolean remove(UUID uid) {
		return this.pokemon.remove(uid) != null;
	}

	public int size() {
		return this.pokemon.size();
	}

	/** The Pokémon held, in the order they came. */
	public List<UUID> uids() {
		return new ArrayList<>(this.pokemon.keySet());
	}

	public JsonObject toJson() {
		JsonArray list = new JsonArray();
		this.pokemon.forEach((uid, data) -> {
			JsonObject item = new JsonObject();
			item.addProperty("uid", uid.toString().toLowerCase(Locale.ROOT));
			item.add("data", data.deepCopy());
			list.add(item);
		});
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.add("pokemon", list);
		return root;
	}

	/** Writes the daycare through a temporary file, so a crash never leaves half of it. */
	public void write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, GSON.toJson(this.toJson()) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
