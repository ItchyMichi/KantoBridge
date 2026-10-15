package dev.kantobridge.world;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The game catalogue, {@code protocol/games.json} (docs/specs/MULTI_GAME_PLAN.md §4.2), bundled in the jar beside the
 * protocol catalog: the one place that names the games. For each Gen1Recomp game id it holds the engine family (one
 * bridge adapter per family; a client draws a world with the art of its own export when both games belong to one family,
 * docs/specs/VOXEL_BLOCKS_PLAN.md §5.2), Gen1Recomp's engine, the generation, a label, the region "Add a region" offers for
 * the game (its world space: every version of one world space shares it), the compatibility profile (what its shadow
 * party can hold, §4.9), the art set, whether gen1-bridge has an engine adapter for it, and the story markers
 * {@code game.status} reports. Everything else asks this class; nothing names {@code red} or {@code blue} itself.
 *
 * <p>The static methods read the bundled catalogue; {@link #parse} reads any catalogue document (tests). Ids and symbols
 * only, never game content.
 */
public final class Games {
	/** Where the build puts the catalogue inside the jar (build.gradle, beside the protocol catalog). */
	public static final String RESOURCE = "/kantobridge/protocol/games.json";
	public static final int VERSION = 1;
	/** A game id (protocol defs {@code gameId}). */
	public static final Pattern GAME_ID = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");
	/** A region id (protocol defs {@code regionId}). */
	public static final Pattern REGION_ID = Pattern.compile("^[a-z][a-z0-9_]{0,23}$");
	/** The engine families (protocol defs {@code family}). */
	public static final Set<String> FAMILIES = Set.of("gen1", "gen2", "gen3");
	/** Gen1Recomp's engines (src/core/GameVersion.lua). */
	private static final Set<String> ENGINES = Set.of("gen1", "gs", "crystal", "game3");
	private static final Set<String> LAYOUTS = Set.of("frlg", "rse");
	private static final Set<String> ROOT_FIELDS = Set.of("version", "games");
	private static final Set<String> GAME_FIELDS = Set.of("family", "engine", "generation", "label", "region", "layout", "compat", "artSet",
			"adapter", "markers");
	private static final Set<String> COMPAT_FIELDS = Set.of("maxDex", "maxMove", "nick");
	private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9 '.-]{1,24}$");
	private static final Pattern MARKER = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");
	/** A marker's flag (protocol defs {@code flagId}). */
	private static final Pattern FLAG = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

	private static volatile @Nullable Games bundled;

	/**
	 * A game's compatibility profile (protocol defs {@code compat}): the limits of what its shadow party can hold.
	 *
	 * @param maxDex  the highest national dex number the game holds
	 * @param maxMove the highest move number the game holds
	 * @param nick    the longest nickname the game stores
	 */
	public record Compat(int maxDex, int maxMove, int nick) {
		public static final int MAX_NUMBER = 2000;
		public static final int MAX_NICK = 16;

		public Compat {
			if (!fits(maxDex, maxMove, nick)) {
				throw new IllegalArgumentException("bad compat profile");
			}
		}

		/** True when the three numbers are within the protocol's ranges. */
		public static boolean fits(long maxDex, long maxMove, long nick) {
			return maxDex >= 1 && maxDex <= MAX_NUMBER && maxMove >= 1 && maxMove <= MAX_NUMBER && nick >= 1 && nick <= MAX_NICK;
		}
	}

	/**
	 * One catalogue entry.
	 *
	 * @param layout  Gen 3 only: the map layout family ({@code frlg} or {@code rse}), else null
	 * @param adapter whether gen1-bridge has an engine adapter for the game today
	 * @param markers marker name → the flag the game's adapter resolves it with (sorted)
	 */
	public record Game(String id, String family, String engine, int generation, String label, String region, @Nullable String layout,
			Compat compat, String artSet, boolean adapter, Map<String, String> markers) {
		public Game {
			markers = Collections.unmodifiableMap(new TreeMap<>(markers));
		}
	}

	private final Map<String, Game> games;

	private Games(Map<String, Game> games) {
		this.games = Collections.unmodifiableMap(new TreeMap<>(games));
	}

	/** Parses a catalogue document, checking every field's shape (the schema's rules). */
	public static Games parse(String json) throws DataException {
		JsonObject root;
		try {
			root = ProtoJson.parseObject(json);
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
		DataReader r = DataReader.of(root, "").allowOnly(ROOT_FIELDS);
		if (r.integer("version") != VERSION) {
			throw new DataException(r.path("version"), "unsupported version");
		}
		DataReader list = r.object("games");
		Map<String, Game> out = new TreeMap<>();
		for (String id : list.json().keySet()) {
			if (!GAME_ID.matcher(id).matches()) {
				throw new DataException(list.path(), "bad game id");
			}
			DataReader g = list.object(id).allowOnly(GAME_FIELDS);
			String family = g.string("family");
			if (!FAMILIES.contains(family)) {
				throw new DataException(g.path("family"), "unknown family");
			}
			String engine = g.string("engine");
			if (!ENGINES.contains(engine)) {
				throw new DataException(g.path("engine"), "unknown engine");
			}
			int generation = g.integer("generation", 1, 3);
			String label = g.string("label", LABEL);
			String region = g.string("region", REGION_ID);
			String layout = g.has("layout") ? g.string("layout") : null;
			if (layout != null && !LAYOUTS.contains(layout)) {
				throw new DataException(g.path("layout"), "unknown layout");
			}
			DataReader c = g.object("compat").allowOnly(COMPAT_FIELDS);
			Compat compat = new Compat(c.integer("maxDex", 1, Compat.MAX_NUMBER), c.integer("maxMove", 1, Compat.MAX_NUMBER),
					c.integer("nick", 1, Compat.MAX_NICK));
			String artSet = g.string("artSet", GAME_ID);
			boolean adapter = g.bool("adapter");
			DataReader m = g.object("markers");
			Map<String, String> markers = new TreeMap<>();
			for (String name : m.json().keySet()) {
				if (!MARKER.matcher(name).matches()) {
					throw new DataException(m.path(), "bad marker name");
				}
				markers.put(name, m.string(name, FLAG));
			}
			out.put(id, new Game(id, family, engine, generation, label, region, layout, compat, artSet, adapter, markers));
		}
		if (out.isEmpty()) {
			throw new DataException(r.path("games"), "no games");
		}
		return new Games(out);
	}

	/** The catalogue bundled in the mod jar. Missing or broken means a broken build, so this throws unchecked. */
	public static Games bundled() {
		Games games = bundled;
		if (games == null) {
			synchronized (Games.class) {
				games = bundled;
				if (games == null) {
					games = loadBundled();
					bundled = games;
				}
			}
		}
		return games;
	}

	private static Games loadBundled() {
		try (InputStream in = Games.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("game catalogue missing from the jar: " + RESOURCE);
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (DataException e) {
			throw new IllegalStateException("game catalogue is invalid: " + e.getMessage(), e);
		}
	}

	// ---- this catalogue ----

	/** Every entry by id, sorted. */
	public Map<String, Game> entries() {
		return this.games;
	}

	/** An entry, or null for a game this catalogue doesn't know. */
	public @Nullable Game entry(@Nullable String game) {
		return game == null ? null : this.games.get(game);
	}

	// ---- the bundled catalogue ----

	/** True when the catalogue names the game. */
	public static boolean known(@Nullable String game) {
		return get(game) != null;
	}

	/** Every game id, sorted. */
	public static List<String> ids() {
		return new ArrayList<>(bundled().games.keySet());
	}

	/** The catalogue's entry for a game, or null for a game this mod doesn't know. */
	public static @Nullable Game get(@Nullable String game) {
		return bundled().entry(game);
	}

	/** The family of a game, or null for a game this mod doesn't know. */
	public static @Nullable String family(@Nullable String game) {
		Game entry = get(game);
		return entry == null ? null : entry.family();
	}

	/** A game's compatibility profile, or null for a game this mod doesn't know. */
	public static @Nullable Compat compat(@Nullable String game) {
		Game entry = get(game);
		return entry == null ? null : entry.compat();
	}

	/** The region id a game builds by default (its world space), or null for a game this mod doesn't know. */
	public static @Nullable String region(@Nullable String game) {
		Game entry = get(game);
		return entry == null ? null : entry.region();
	}

	/** A game's label ("Blue"), or null for a game this mod doesn't know. */
	public static @Nullable String label(@Nullable String game) {
		Game entry = get(game);
		return entry == null ? null : entry.label();
	}

	/** A game's art set (the key its textures carry), or null for a game this mod doesn't know. */
	public static @Nullable String artSet(@Nullable String game) {
		Game entry = get(game);
		return entry == null ? null : entry.artSet();
	}

	/** True when gen1-bridge has an engine adapter for the game (an unknown game has none). */
	public static boolean adapter(@Nullable String game) {
		Game entry = get(game);
		return entry != null && entry.adapter();
	}

	/** The games gen1-bridge can run today, sorted. */
	public static List<String> withAdapter() {
		List<String> out = new ArrayList<>();
		for (Game entry : bundled().games.values()) {
			if (entry.adapter()) {
				out.add(entry.id());
			}
		}
		return out;
	}

	/**
	 * True when art of {@code art}'s export can draw a world built from {@code world}'s: the same game, or one Gen 1 family
	 * (its games share tilesets). A family whose art is baked per game shares nothing across games (MG9,
	 * GEN2_BRIDGE_PLAN §7 "Art": Gold, Silver and Crystal bake different tiles, as Gen 3's games do).
	 */
	public static boolean compatible(@Nullable String art, @Nullable String world) {
		if (art == null || world == null) {
			return false;
		}
		String family = family(art);
		return art.equals(world) || ("gen1".equals(family) && family.equals(family(world)));
	}
}
