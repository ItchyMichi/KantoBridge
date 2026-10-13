package dev.kantobridge.builder.export;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A downloaded export (docs/PROTOCOL.md "Hashes and the bundle"): {@code <game>/index.json} (the {@code export.index}
 * reply) plus {@code <kind>/<id>.json}, each exactly its resource's canonical JSON.
 *
 * <p>Opening a bundle reads every listed resource and checks its size and SHA-256 against the index, so a build never
 * starts from a partial or altered export. The contents are ROM-derived: nothing here logs or reports anything but
 * kinds, ids, counts and hashes.
 */
public final class ExportBundle {
	public static final Set<String> KINDS = Set.of("map", "tileset", "image", "sprite", "palettes", "encounters", "field", "names",
			"layout", "swaps", "profile", "routes");
	/** Ids that are safe as file names everywhere (no separators, colons or leading dots). */
	private static final Pattern FILE_ID = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");
	private static final Pattern FULL_HASH = Pattern.compile("^[0-9a-f]{64}$");
	/**
	 * The id of the Gen 1 family's palettes resource (SGB palettes, protocol/export/palettes.schema.json): the one place
	 * that names it. Another family's export carries its own palette model (MULTI_GAME_PLAN §4.10).
	 */
	public static final String GEN1_PALETTES_ID = "sgb";

	/** One entry of the index. */
	public record Resource(String kind, String id, String hash, long size) {
		public String path() {
			return this.kind + "/" + this.id + ".json";
		}
	}

	/** Reads one file of the bundle by its relative path; null when it doesn't exist. */
	@FunctionalInterface
	public interface Source {
		@Nullable byte[] read(String relativePath) throws IOException;
	}

	private final String game;
	private final String engine;
	private final String bridge;
	private final String exportHash;
	private final List<Resource> resources;
	private final Map<String, JsonObject> data;
	private final Map<String, Object> typed = new HashMap<>();

	private ExportBundle(String game, String engine, String bridge, String exportHash, List<Resource> resources,
			Map<String, JsonObject> data) {
		this.game = game;
		this.engine = engine;
		this.bridge = bridge;
		this.exportHash = exportHash;
		this.resources = List.copyOf(resources);
		this.data = Collections.unmodifiableMap(data);
	}

	/** Opens the bundle in {@code dir} (the {@code <game>} folder that holds {@code index.json}). */
	public static ExportBundle open(Path dir) throws BundleException {
		Path root = dir.toAbsolutePath().normalize();
		return read(relative -> {
			Path file = root.resolve(relative).normalize();
			if (!file.startsWith(root)) {
				throw new IOException("path escapes the bundle");
			}
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		});
	}

	/** Opens a bundle shipped as resources, e.g. the GameTests' synthetic bundle; {@code prefix} ends with '/'. */
	public static ExportBundle fromClasspath(ClassLoader loader, String prefix) throws BundleException {
		return read(relative -> {
			try (InputStream in = loader.getResourceAsStream(prefix + relative)) {
				return in == null ? null : in.readAllBytes();
			}
		});
	}

	public static ExportBundle read(Source source) throws BundleException {
		byte[] indexBytes = fetch(source, "index.json");
		if (indexBytes == null) {
			throw new BundleException("bundle.missing", "index.json");
		}
		JsonObject index;
		try {
			index = ProtoJson.parseObject(new String(indexBytes, StandardCharsets.UTF_8));
		} catch (ProtocolException e) {
			throw new BundleException("bundle.index", "index.json is not valid JSON (" + e.code() + ")");
		}
		String game;
		String engine;
		String bridge;
		List<Resource> resources = new ArrayList<>();
		try {
			DataReader r = DataReader.of(index, "index.json");
			game = r.string("game");
			if (!Games.known(game)) {
				throw new DataException(r.path("game"), "unknown game");
			}
			engine = r.string("engine", Pattern.compile("^[A-Za-z0-9_.+-]{1,64}$"));
			bridge = r.string("bridge", Pattern.compile("^[A-Za-z0-9_.+-]{1,64}$"));
			Set<String> seen = new TreeSet<>();
			for (DataReader entry : DataReader.objects(r.array("resources"), r.path("resources"))) {
				String kind = entry.string("kind");
				if (!KINDS.contains(kind)) {
					throw new DataException(entry.path("kind"), "unknown kind");
				}
				String id = entry.string("id");
				if (!FILE_ID.matcher(id).matches() || id.contains("..")) {
					throw new DataException(entry.path("id"), "unsupported id");
				}
				Resource resource = new Resource(kind, id, entry.string("hash", FULL_HASH), entry.longValue("size", 0, 1L << 30));
				if (!seen.add(resource.path())) {
					throw new DataException(entry.path(), "duplicate resource");
				}
				resources.add(resource);
			}
		} catch (DataException e) {
			throw new BundleException("bundle.index", e.getMessage(), e);
		}
		Map<String, JsonObject> data = new LinkedHashMap<>();
		for (Resource resource : resources) {
			byte[] bytes = fetch(source, resource.path());
			if (bytes == null) {
				throw new BundleException("bundle.missing", resource.path());
			}
			if (bytes.length != resource.size()) {
				throw new BundleException("bundle.size", resource.path());
			}
			if (!Hashes.sha256(bytes).equals(resource.hash())) {
				throw new BundleException("bundle.hash", resource.path());
			}
			JsonElement parsed;
			try {
				parsed = ProtoJson.parse(new String(bytes, StandardCharsets.UTF_8));
			} catch (ProtocolException e) {
				throw new BundleException("bundle.format", resource.path() + " is not valid JSON (" + e.code() + ")");
			}
			if (!parsed.isJsonObject()) {
				throw new BundleException("bundle.format", resource.path() + " is not an object");
			}
			data.put(resource.path(), parsed.getAsJsonObject());
		}
		return new ExportBundle(game, engine, bridge, Hashes.canonical(index), resources, data);
	}

	private static @Nullable byte[] fetch(Source source, String path) throws BundleException {
		try {
			return source.read(path);
		} catch (IOException e) {
			throw new BundleException("bundle.io", path, e);
		}
	}

	public String game() {
		return this.game;
	}

	public String engine() {
		return this.engine;
	}

	public String bridge() {
		return this.bridge;
	}

	/** SHA-256 of {@code index.json}'s canonical JSON: names the export a world was built from. */
	public String exportHash() {
		return this.exportHash;
	}

	public List<Resource> resources() {
		return this.resources;
	}

	/** The ids of one kind, sorted. */
	public List<String> ids(String kind) {
		List<String> out = new ArrayList<>();
		for (Resource resource : this.resources) {
			if (resource.kind().equals(kind)) {
				out.add(resource.id());
			}
		}
		Collections.sort(out);
		return out;
	}

	public boolean has(String kind, String id) {
		return this.data.containsKey(kind + "/" + id + ".json");
	}

	/** A resource's parsed JSON, or null. ROM-derived: never log it. */
	public @Nullable JsonObject json(String kind, String id) {
		return this.data.get(kind + "/" + id + ".json");
	}

	public ExportMap map(String id) throws BundleException {
		return this.typed("map", id, ExportMap::parse, ExportMap.class);
	}

	public ExportTileset tileset(String id) throws BundleException {
		return this.typed("tileset", id, ExportTileset::parse, ExportTileset.class);
	}

	public ExportImage image(String id) throws BundleException {
		return this.typed("image", id, ExportImage::parseImage, ExportImage.class);
	}

	public ExportImage sprite(String id) throws BundleException {
		return this.typed("sprite", id, ExportImage::parseSprite, ExportImage.class);
	}

	/** The id of this export's palettes resource: the Gen 1 family's ({@link #GEN1_PALETTES_ID}). */
	public String palettesId() {
		return GEN1_PALETTES_ID;
	}

	/** True when the export carries its palettes resource. */
	public boolean hasPalettes() {
		return this.has("palettes", this.palettesId());
	}

	public ExportPalettes palettes() throws BundleException {
		return this.typed("palettes", this.palettesId(), ExportPalettes::parse, ExportPalettes.class);
	}

	/**
	 * The id of this export's surface layout resource: gen1-bridge exports the layout under the game's default region id
	 * (the catalogue's; {@code kanto} for Red and Blue), MULTI_GAME_PLAN §4.2.
	 */
	public String layoutId() {
		return Objects.requireNonNull(Games.region(this.game), "layout id");
	}

	/** True when the export carries its surface layout resource. */
	public boolean hasLayout() {
		return this.has("layout", this.layoutId());
	}

	public ExportLayout layout() throws BundleException {
		return this.typed("layout", this.layoutId(), ExportLayout::parse, ExportLayout.class);
	}

	/** The swap sites; none when the export has no swaps resource. */
	public ExportSwaps swaps() throws BundleException {
		return this.has("swaps", "all") ? this.typed("swaps", "all", ExportSwaps::parse, ExportSwaps.class) : ExportSwaps.empty();
	}

	/** The wild encounter tables, or null when the export has no encounters resource. */
	public @Nullable ExportEncounters encounters() throws BundleException {
		return this.has("encounters", "all") ? this.typed("encounters", "all", ExportEncounters::parse, ExportEncounters.class) : null;
	}

	/**
	 * Dramaless Shape's shape profile (docs/specs/PROFILE_PLAN.md), or null when the export has none (the mod isn't
	 * installed, or the bundle predates the profile plan).
	 */
	public @Nullable ExportProfile profile() throws BundleException {
		return this.has("profile", ExportProfile.ID) ? this.typed("profile", ExportProfile.ID, ExportProfile::parse, ExportProfile.class) : null;
	}

	/** Wild Routes (WR2): the zones the hidden game runs; none when the export has no routes resource (an older one). */
	public ExportRoutes routes() throws BundleException {
		return this.has("routes", ExportRoutes.ID) ? this.typed("routes", ExportRoutes.ID, ExportRoutes::parse, ExportRoutes.class)
				: ExportRoutes.empty();
	}

	/**
	 * The game's own items that aren't key items (the names resource's {@code items}, by id; their names are never
	 * read), for Wild Routes' item balls. Empty when the export has no names resource.
	 */
	public Set<String> itemIds() throws BundleException {
		if (!this.has("names", "all")) {
			return Set.of();
		}
		return this.typed("names", "all", ExportBundle::parseItemIds, ItemIds.class).ids();
	}

	private record ItemIds(Set<String> ids) {
	}

	private static ItemIds parseItemIds(DataReader r) throws DataException {
		DataReader items = r.object("items");
		Set<String> ids = new java.util.TreeSet<>();
		for (String id : items.json().keySet()) {
			if (!DataReader.SYMBOL.matcher(id).matches()) {
				throw new DataException(items.path(), "bad item id");
			}
			DataReader item = items.object(id);
			if (!(item.has("key") && item.bool("key"))) {
				ids.add(id);
			}
		}
		return new ItemIds(java.util.Collections.unmodifiableSet(ids));
	}

	/** The field tables the habitat planner reads; empty when the export has no field resource. */
	public ExportField field() throws BundleException {
		return this.has("field", "all") ? this.typed("field", "all", ExportField::parse, ExportField.class) : ExportField.empty();
	}

	@FunctionalInterface
	private interface Parser<T> {
		T parse(DataReader reader) throws DataException;
	}

	private synchronized <T> T typed(String kind, String id, Parser<T> parser, Class<T> type) throws BundleException {
		String path = kind + "/" + id + ".json";
		Object cached = this.typed.get(path);
		if (cached != null) {
			return type.cast(cached);
		}
		JsonObject json = this.data.get(path);
		if (json == null) {
			throw new BundleException("bundle.missing", path);
		}
		T value;
		try {
			value = parser.parse(DataReader.of(json, path));
		} catch (DataException e) {
			throw BundleException.format(kind, id, e);
		}
		this.typed.put(path, value);
		return value;
	}
}
