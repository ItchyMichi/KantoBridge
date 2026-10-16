package dev.kantobridge.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.character.StyleFallback;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.Games;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * The client's settings file, {@code <config dir>/kantobridge-client.json}:
 *
 * <ul>
 * <li>{@code port}: the port gen1-bridge listens on. The host is always loopback, because gen1-bridge never listens
 * elsewhere (docs/PROTOCOL.md "Transport"). A different port lets two client/Gen1Recomp pairs share one PC. It is one
 * <b>link</b> whose game is learnt from {@code hello.ok}.</li>
 * <li>{@code links} (MG4, several games, docs/specs/MULTI_GAME_PLAN.md §4.5): {@code [{"game": "blue", "port": 27151},
 * {"game": "emerald", "port": 27152}]}, one hidden Gen1Recomp process per game, each on its own port. {@code game} is
 * optional (a link without one learns its game from {@code hello.ok}); a link whose process turns out to run another
 * game is never followed ({@code wrong_game}). When both keys are present {@code links} wins.</li>
 * <li>{@code shapes}: {@code "on"} (default) or {@code "off"}: whether the world's shaped art is drawn
 * (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1); {@code off} draws today's cubes. The debugging switch and the fallback.</li>
 * <li>{@code characters}: {@code {"style": "flat" | "thick" | "carved" | "rigged", "packs": true | false}} (V8, §5.11):
 * how NPCs are drawn (unset: a pack's suggestion, else thick cards), and whether texture packs may give sprites models and
 * styles.</li>
 * <li>{@code weather}: {@code "on"} (default) or {@code "off"} (SE6, docs/specs/STORY_EVENTS_PLAN.md §4.1 S8): whether
 * the followed hidden game's weather (Emerald's rain, fog, ash, sandstorm, drought, ...) is drawn for this player
 * ({@code client.weather.WeatherView}).</li>
 * </ul>
 *
 * <p>A missing file is created with the defaults (one link on port 27151, written as {@code port} so INSTALL.md stays
 * true). A broken file or value falls back to the default with a warning, so a typo never stops the game. The system
 * property {@code kantobridge.links} ({@code blue:27151,emerald:27152}; a bare number is a link without a game) replaces
 * the file's links for dev runs; without it, {@code kantobridge.port} overrides the file as one link, as before.
 *
 * @param links           the hidden games' links, in order; never empty (the first is the legacy one)
 * @param shapes          draw the world's shapes
 * @param characterStyle  {@code characters.style}, or null when unset
 * @param characterPacks  {@code characters.packs}
 * @param weather         {@code weather}: draw the hidden game's weather
 */
public record ClientConfig(List<Link> links, boolean shapes, StyleFallback.@Nullable Style characterStyle, boolean characterPacks,
		boolean weather) {
	public static final String FILE_NAME = "kantobridge-client.json";
	public static final String PORT_PROPERTY = "kantobridge.port";
	/** MG4: {@code -Dkantobridge.links=blue:27151,emerald:27152}. */
	public static final String LINKS_PROPERTY = "kantobridge.links";
	public static final int DEFAULT_PORT = 27151;
	/** Links a config may list at most (one hidden process each). */
	public static final int MAX_LINKS = 16;
	public static final ClientConfig DEFAULTS = new ClientConfig(DEFAULT_PORT, true);

	/**
	 * One hidden game's link (MG4).
	 *
	 * @param game the game the process on {@code port} is expected to run, or null for a link that learns it from
	 *             {@code hello.ok} (the legacy {@code port} setting)
	 * @param port the port its gen1-bridge listens on
	 */
	public record Link(@Nullable String game, int port) {
		public Link {
			if (port < 1 || port > 65535) {
				throw new IllegalArgumentException("port out of range");
			}
			if (game != null && !Games.GAME_ID.matcher(game).matches()) {
				throw new IllegalArgumentException("not a game id");
			}
		}

		/** A link that learns its game from {@code hello.ok}. */
		public static Link legacy(int port) {
			return new Link(null, port);
		}

		/** True when the link names its game. */
		public boolean configured() {
			return this.game != null;
		}
	}

	public ClientConfig {
		if (links.isEmpty()) {
			throw new IllegalArgumentException("a config needs at least one link");
		}
		links = List.copyOf(links);
	}

	/** Every setting before SE6, the weather drawn. */
	public ClientConfig(List<Link> links, boolean shapes, StyleFallback.@Nullable Style characterStyle, boolean characterPacks) {
		this(links, shapes, characterStyle, characterPacks, true);
	}

	public ClientConfig(int port) {
		this(port, true);
	}

	public ClientConfig(int port, boolean shapes) {
		this(port, shapes, null, true);
	}

	/** One legacy link on {@code port} (the shape every setting had before MG4). */
	public ClientConfig(int port, boolean shapes, StyleFallback.@Nullable Style characterStyle, boolean characterPacks) {
		this(List.of(Link.legacy(port)), shapes, characterStyle, characterPacks);
	}

	/** The first link's port (the one legacy callers mean). */
	public int port() {
		return this.links.get(0).port();
	}

	/** Reads (or creates) the file in {@code configDir}, then applies the system property overrides. */
	public static ClientConfig load(Path configDir, Properties systemProperties, Consumer<String> warn) {
		Path file = configDir.resolve(FILE_NAME);
		List<Link> links = List.of(Link.legacy(DEFAULT_PORT));
		boolean shapes = true;
		StyleFallback.Style style = null;
		boolean packs = true;
		boolean weather = true;
		if (Files.isRegularFile(file)) {
			JsonObject root = readRoot(file, warn);
			if (root != null) {
				links = readLinks(root, warn);
				shapes = readSwitch(root, "shapes", true, warn);
				weather = readSwitch(root, "weather", true, warn);
				JsonElement characters = root.get("characters");
				if (characters != null && characters.isJsonObject()) {
					style = readStyle(characters.getAsJsonObject(), warn);
					packs = readSwitch(characters.getAsJsonObject(), "packs", true, warn);
				} else if (characters != null) {
					warn.accept(FILE_NAME + ": characters must be an object; using the defaults");
				}
			}
		} else {
			try {
				Files.createDirectories(configDir);
				Files.writeString(file, "{\n  \"port\": " + DEFAULT_PORT + ",\n  \"shapes\": \"on\"\n}\n", StandardCharsets.UTF_8);
			} catch (IOException e) {
				warn.accept("could not create " + FILE_NAME + " (" + e.getClass().getSimpleName() + "); using defaults");
			}
		}
		String linksOverride = systemProperties.getProperty(LINKS_PROPERTY);
		if (linksOverride != null) {
			List<Link> parsed = parseLinks(linksOverride, warn);
			if (!parsed.isEmpty()) {
				links = parsed;
			} else {
				warn.accept("ignoring -D" + LINKS_PROPERTY + ": no usable link in it");
			}
		} else {
			String override = systemProperties.getProperty(PORT_PROPERTY);
			if (override != null) {
				Integer parsed = parsePort(override.trim());
				if (parsed != null) {
					links = List.of(Link.legacy(parsed));
				} else {
					warn.accept("ignoring -D" + PORT_PROPERTY + ": not a port number from 1 to 65535");
				}
			}
		}
		return new ClientConfig(links, shapes, style, packs, weather);
	}

	/**
	 * The links of {@code -Dkantobridge.links}: entries {@code game:port} or a bare {@code port} (a legacy link), separated
	 * by commas. Entries that don't check out are skipped with a warning; so are repeated ports and games.
	 */
	public static List<Link> parseLinks(String spec, Consumer<String> warn) {
		List<Link> out = new ArrayList<>();
		Set<Integer> ports = new HashSet<>();
		Set<String> games = new HashSet<>();
		for (String raw : spec.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) {
				continue;
			}
			int colon = entry.indexOf(':');
			String game = colon < 0 ? null : entry.substring(0, colon).trim();
			Integer port = parsePort(colon < 0 ? entry : entry.substring(colon + 1).trim());
			if (port == null) {
				warn.accept(LINKS_PROPERTY + ": \"" + entry + "\" has no port from 1 to 65535; skipped");
				continue;
			}
			if (game != null && game.isEmpty()) {
				game = null;
			}
			if (game != null && !Games.known(game)) {
				warn.accept(LINKS_PROPERTY + ": \"" + entry + "\" names a game the catalogue doesn't know; skipped");
				continue;
			}
			add(out, ports, games, game, port, warn);
			if (out.size() >= MAX_LINKS) {
				break;
			}
		}
		return out;
	}

	/** The file's links: the {@code links} array, else the legacy {@code port}. */
	static List<Link> readLinks(JsonObject root, Consumer<String> warn) {
		JsonElement array = root.get("links");
		if (array == null) {
			return List.of(Link.legacy(readPort(root, warn)));
		}
		if (!array.isJsonArray()) {
			warn.accept(FILE_NAME + ": links must be an array; using port");
			return List.of(Link.legacy(readPort(root, warn)));
		}
		if (root.has("port")) {
			warn.accept(FILE_NAME + ": both links and port are set; links wins");
		}
		List<Link> out = new ArrayList<>();
		Set<Integer> ports = new HashSet<>();
		Set<String> games = new HashSet<>();
		for (JsonElement element : (JsonArray) array) {
			if (!element.isJsonObject()) {
				warn.accept(FILE_NAME + ": a link must be an object; skipped");
				continue;
			}
			JsonObject link = element.getAsJsonObject();
			JsonElement portValue = link.get("port");
			Integer port = portValue != null && portValue.isJsonPrimitive() && portValue.getAsJsonPrimitive().isNumber()
					? parsePort(portValue.getAsString())
					: null;
			if (port == null) {
				warn.accept(FILE_NAME + ": a link needs a port from 1 to 65535; skipped");
				continue;
			}
			String game = null;
			JsonElement gameValue = link.get("game");
			if (gameValue != null) {
				game = gameValue.isJsonPrimitive() && gameValue.getAsJsonPrimitive().isString() ? gameValue.getAsString() : null;
				if (game == null || !Games.known(game)) {
					warn.accept(FILE_NAME + ": a link names a game the catalogue doesn't know; skipped");
					continue;
				}
			}
			add(out, ports, games, game, port, warn);
			if (out.size() >= MAX_LINKS) {
				break;
			}
		}
		if (out.isEmpty()) {
			warn.accept(FILE_NAME + ": no usable link in links; using port");
			return List.of(Link.legacy(readPort(root, warn)));
		}
		return out;
	}

	private static void add(List<Link> out, Set<Integer> ports, Set<String> games, @Nullable String game, int port, Consumer<String> warn) {
		if (ports.contains(port)) {
			warn.accept(FILE_NAME + ": port " + port + " is listed twice; the second link is skipped");
			return;
		}
		if (game != null && games.contains(game)) {
			warn.accept(FILE_NAME + ": game " + game + " is listed twice; the second link is skipped");
			return;
		}
		ports.add(port);
		if (game != null) {
			games.add(game);
		}
		out.add(new Link(game, port));
	}

	/** {@code characters.style}: a style's name; anything else is unset (with a warning). */
	static StyleFallback.@Nullable Style readStyle(JsonObject characters, Consumer<String> warn) {
		JsonElement value = characters.get("style");
		if (value == null) {
			return null;
		}
		StyleFallback.Style style = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? StyleFallback.Style.byId(value.getAsString())
				: null;
		if (style == null || style == StyleFallback.Style.MODEL) {
			warn.accept(FILE_NAME + ": characters.style must be \"flat\", \"thick\", \"carved\" or \"rigged\"; leaving it unset");
			return null;
		}
		return style;
	}

	private static @Nullable JsonObject readRoot(Path file, Consumer<String> warn) {
		try {
			return ProtoJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | ProtocolException e) {
			warn.accept(FILE_NAME + " is not valid JSON; using the defaults (port " + DEFAULT_PORT + ")");
			return null;
		}
	}

	private static int readPort(JsonObject root, Consumer<String> warn) {
		JsonElement value = root.get("port");
		if (value == null) {
			return DEFAULT_PORT;
		}
		Integer port = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? parsePort(value.getAsString()) : null;
		if (port == null) {
			warn.accept(FILE_NAME + ": port must be a whole number from 1 to 65535; using " + DEFAULT_PORT);
			return DEFAULT_PORT;
		}
		return port;
	}

	/** An on/off setting: {@code "on"}, {@code "off"}, {@code true} or {@code false}; anything else is the default. */
	static boolean readSwitch(JsonObject root, String name, boolean fallback, Consumer<String> warn) {
		JsonElement value = root.get(name);
		if (value == null) {
			return fallback;
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
			return value.getAsBoolean();
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
			String text = value.getAsString();
			if (text.equals("on")) {
				return true;
			}
			if (text.equals("off")) {
				return false;
			}
		}
		warn.accept(FILE_NAME + ": " + name + " must be \"on\" or \"off\"; using " + (fallback ? "\"on\"" : "\"off\""));
		return fallback;
	}

	private static @Nullable Integer parsePort(String text) {
		try {
			int port = Integer.parseInt(text);
			return port >= 1 && port <= 65535 ? port : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
