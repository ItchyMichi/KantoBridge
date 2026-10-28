package dev.kantobridge.client.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.6 (WS7): what {@link EngineLauncher} needs from {@code launch.json} (version 2, as
 * {@code tools/setup_games.ps1} writes it and {@code tools/kb_games.py} reads it): each install's folder and games, and
 * the command template that starts an engine. Never reads anything inside an install but a game folder's
 * {@code rom-cache.complete} marker's presence (CLAUDE.md: the folders are ROM-derived).
 *
 * <p>{@code engine} (optional): {@code {"command": ["<path>/AppRun", "--game={game}"], "cwd": "...", "env": {...}}};
 * {@code {game}}, {@code {install}} and {@code {installParent}} are replaced. Without it the Windows default is
 * {@code <install>/gen1recomp.exe --game=<id>} in the install's folder. Relative folders resolve against the file's
 * folder, then its parent (the dev file is {@code local/launch.json} with {@code local\gen1recomp}).
 */
public record LaunchConfig(Map<String, Install> installs, @Nullable List<String> command, @Nullable String cwd, Map<String, String> env) {
	public static final String MARKER = "rom-cache.complete";

	/** One install: its world space, folder and the games it lists (null: not listed, the markers decide). */
	public record Install(String space, Path dir, @Nullable List<String> games) {
		/** True when the game's folder holds the import's completion marker. */
		public boolean imported(String game) {
			return Files.isRegularFile(this.dir.resolve(game).resolve(MARKER));
		}

		public boolean holds(String game, @Nullable String region) {
			return this.games != null ? this.games.contains(game) : this.space.equals(region);
		}
	}

	public LaunchConfig {
		installs = Map.copyOf(installs);
		command = command == null ? null : List.copyOf(command);
		env = Map.copyOf(env);
	}

	/** The install holding {@code game} (its world space {@code region}), or null. */
	public @Nullable Install installOf(String game, @Nullable String region) {
		for (Install install : this.installs.values()) {
			if (install.holds(game, region)) {
				return install;
			}
		}
		return null;
	}

	/** The command that starts {@code game} in {@code install}. */
	public List<String> commandFor(Install install, String game) {
		if (this.command == null || this.command.isEmpty()) {
			return List.of(install.dir().resolve("gen1recomp.exe").toString(), "--game=" + game);
		}
		List<String> out = new ArrayList<>();
		for (String part : this.command) {
			out.add(fill(part, install, game));
		}
		return out;
	}

	public Path cwdFor(Install install, String game) {
		return this.cwd == null ? install.dir() : Path.of(fill(this.cwd, install, game));
	}

	public Map<String, String> envFor(Install install, String game) {
		Map<String, String> out = new LinkedHashMap<>();
		this.env.forEach((key, value) -> out.put(key, fill(value, install, game)));
		return out;
	}

	private static String fill(String text, Install install, String game) {
		Path parent = install.dir().getParent();
		return text.replace("{game}", game).replace("{install}", install.dir().toString())
				.replace("{installParent}", parent == null ? "" : parent.toString());
	}

	/** Parses {@code launch.json}'s text; {@code file} is where it lives (for relative folders). */
	public static LaunchConfig parse(String text, Path file) {
		JsonElement parsed = JsonParser.parseString(text);
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("launch.json must hold an object");
		}
		JsonObject root = parsed.getAsJsonObject();
		Path base = file.toAbsolutePath().getParent();
		Map<String, Install> installs = new LinkedHashMap<>();
		JsonElement listed = root.get("installs");
		if (listed != null && listed.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : listed.getAsJsonObject().entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					continue;
				}
				JsonObject install = entry.getValue().getAsJsonObject();
				String dir = str(install, "dir");
				if (dir == null) {
					continue;
				}
				List<String> games = null;
				JsonElement g = install.get("games");
				if (g != null && g.isJsonArray()) {
					games = new ArrayList<>();
					for (JsonElement game : g.getAsJsonArray()) {
						if (game.isJsonPrimitive()) {
							games.add(game.getAsString());
						}
					}
				}
				installs.put(entry.getKey(), new Install(entry.getKey(), resolve(base, dir), games));
			}
		} else if (str(root, "gen1recomp") != null) {
			installs.put("kanto", new Install("kanto", resolve(base, str(root, "gen1recomp")), null));
		}
		List<String> command = null;
		String cwd = null;
		Map<String, String> env = new LinkedHashMap<>();
		JsonElement engine = root.get("engine");
		if (engine != null && engine.isJsonObject()) {
			JsonObject e = engine.getAsJsonObject();
			JsonElement c = e.get("command");
			if (c != null && c.isJsonArray()) {
				command = new ArrayList<>();
				for (JsonElement part : (JsonArray) c) {
					command.add(part.getAsString());
				}
			}
			cwd = str(e, "cwd");
			JsonElement v = e.get("env");
			if (v != null && v.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : v.getAsJsonObject().entrySet()) {
					env.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		}
		return new LaunchConfig(installs, command, cwd, env);
	}

	private static Path resolve(@Nullable Path base, String dir) {
		Path path = Path.of(dir.replace('\\', '/'));
		if (path.isAbsolute() || base == null) {
			return path.normalize();
		}
		Path here = base.resolve(path).normalize();
		Path parent = base.getParent();
		return Files.isDirectory(here) || parent == null ? here : parent.resolve(path).normalize();
	}

	private static @Nullable String str(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}
}
