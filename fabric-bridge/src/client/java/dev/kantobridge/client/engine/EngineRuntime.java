package dev.kantobridge.client.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientConfig;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.world.WorldRecord;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.6 (WS7; D8): {@link EngineLauncher} in the client. Opted in by {@code -Dkantobridge.engines=minecraft}
 * ({@code launch.ps1 -Engines minecraft}, the dev client's {@code -Pkb.engines=minecraft}) or {@code kantobridge-client.json}'s
 * {@code engines.start: "minecraft"}. In a world with a record, a moment after the join, every listed game whose link
 * isn't connected gets its engine started (HUD: "Starting Crystal…"); an engine this client started is stopped once its
 * game answered {@code session.close} (WS5's quit), never otherwise. {@code launch.json}: {@code engines.launch} or
 * {@code -Dkantobridge.engines.launch}, else {@code ../launch.json} from the game folder.
 */
public final class EngineRuntime {
	public static final String PROPERTY = "kantobridge.engines";
	public static final String LAUNCH_PROPERTY = "kantobridge.engines.launch";
	/** The join settles this long before the links' states are believed. */
	static final long SETTLE_MS = 3_000;
	static final long TICK_MS = 500;
	static final long STOP_WAIT_MS = 10_000;

	private static @Nullable EngineRuntime instance;

	private final BridgeRuntime bridge;
	private final EngineLauncher launcher;
	private long joinedAt = -1;
	private final Set<String> attempted = new HashSet<>();
	private long lastTick;

	private EngineRuntime(BridgeRuntime bridge, EngineLauncher launcher) {
		this.bridge = bridge;
		this.launcher = launcher;
	}

	public static @Nullable EngineRuntime get() {
		return instance;
	}

	/** Call once from the client entrypoint, after {@link SessionRuntime#init}. Does nothing unless opted in. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		Path configDir = FabricLoader.getInstance().getConfigDir();
		JsonObject engines = enginesSection(configDir.resolve(ClientConfig.FILE_NAME));
		// launch.ps1 -Engines minecraft sets KANTOBRIDGE_ENGINES(_LAUNCH) for Prism's Minecraft
		String fromFile = engines == null || !engines.has("start") ? "launcher" : engines.get("start").getAsString();
		String mode = System.getProperty(PROPERTY, envOr("KANTOBRIDGE_ENGINES", fromFile));
		if (!"minecraft".equals(mode)) {
			return;
		}
		String launchPath = System.getProperty(LAUNCH_PROPERTY,
				envOr("KANTOBRIDGE_ENGINES_LAUNCH", engines != null && engines.has("launch") ? engines.get("launch").getAsString() : null));
		Path launchFile = launchPath != null ? Path.of(launchPath)
				: FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolveSibling("launch.json");
		LaunchConfig config;
		try {
			config = LaunchConfig.parse(Files.readString(launchFile, StandardCharsets.UTF_8), launchFile);
		} catch (IOException | RuntimeException e) {
			KantoBridge.LOGGER.warn("engines: launch.json unreadable ({}); the engines are left to the launcher", e.getClass().getSimpleName());
			return;
		}
		EngineRuntime runtime = new EngineRuntime(bridge, new EngineLauncher(config, new RealProcesses(), EngineRuntime::listening, System::currentTimeMillis));
		instance = runtime;
		KantoBridge.LOGGER.info("engines: Minecraft starts the hidden games ({} installs)", config.installs().size());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			runtime.joinedAt = System.currentTimeMillis();
			runtime.attempted.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runtime.joinedAt = -1);
		ClientTickEvents.END_CLIENT_TICK.register(client -> runtime.tick());
		// a client that exits without leaving the world (crash, window closed) still takes its engines along
		Runtime.getRuntime().addShutdownHook(new Thread(() -> runtime.launcher.stopAll(STOP_WAIT_MS), "kantobridge-engine-exit"));
		bridge.addHudLine(runtime::hudLine);
		SessionRuntime sessions = SessionRuntime.get();
		if (sessions != null) {
			sessions.onClosed(runtime::closed);
			sessions.waitForClosedWhile(() -> !runtime.launcher.owned().isEmpty());
		}
	}

	private void tick() {
		long now = System.currentTimeMillis();
		if (now - this.lastTick < TICK_MS) {
			return;
		}
		this.lastTick = now;
		for (EngineLauncher.Engine engine : this.launcher.tick()) {
			this.report(engine);
		}
		if (this.joinedAt < 0 || now - this.joinedAt < SETTLE_MS || Minecraft.getInstance().level == null) {
			return;
		}
		WorldRecord record = ClientRegions.record();
		if (record == null) {
			return; // a legacy world: engines stay the launcher's
		}
		Map<String, Integer> ports = new LinkedHashMap<>();
		Map<String, String> regions = new LinkedHashMap<>();
		for (WorldRecord.Game game : record.games()) {
			for (BridgeLink link : this.bridge.links().all()) {
				if (game.game().equals(link.game()) && !link.connected() && !this.attempted.contains(game.game())) {
					ports.put(game.game(), link.port());
					regions.put(game.game(), game.region());
				}
			}
		}
		if (ports.isEmpty()) {
			return;
		}
		// one engine at a time (EngineLauncher#ensure): the rest on later ticks, each game tried once per join
		for (EngineLauncher.Engine engine : this.launcher.ensure(ports, regions)) {
			this.attempted.add(engine.game());
			this.report(engine);
		}
	}

	private void report(EngineLauncher.Engine engine) {
		KantoBridge.LOGGER.info("engines: {} {}{}", engine.game(), engine.state(), engine.reason() == null ? "" : " (" + engine.reason() + ")");
	}

	private void closed(BridgeLink link) {
		String game = link.game();
		if (game == null || !this.launcher.owns(game)) {
			return;
		}
		Thread stopper = new Thread(() -> {
			if (this.launcher.stop(game, STOP_WAIT_MS)) {
				KantoBridge.LOGGER.info("engines: {} stopped after its session.closed", game);
			}
		}, "kantobridge-engine-stop");
		stopper.setDaemon(false);
		stopper.start();
	}

	private ConnectionStatus.@Nullable Line hudLine() {
		List<String> starting = this.launcher.starting();
		if (starting.isEmpty()) {
			return null;
		}
		return new ConnectionStatus.Line("kantobridge.hud.engine.starting", List.of(I18n.get("kantobridge.game." + starting.get(0))), false);
	}

	/** Whether {@code game}'s engine is one this client started (the dev autopilot's check). */
	public boolean owns(String game) {
		return this.launcher.owns(game);
	}

	public @Nullable EngineLauncher.Engine engine(String game) {
		return this.launcher.engine(game);
	}

	private static @Nullable String envOr(String name, @Nullable String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

	static boolean listening(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static @Nullable JsonObject enginesSection(Path file) {
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			JsonElement engines = root.isJsonObject() ? root.getAsJsonObject().get("engines") : null;
			return engines != null && engines.isJsonObject() ? engines.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Real processes: a visible engine window, its output discarded. */
	static final class RealProcesses implements EngineLauncher.Processes {
		@Override
		public EngineLauncher.Handle start(List<String> command, Path cwd, Map<String, String> env) throws IOException {
			ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(nullFile()));
			builder.environment().putAll(env);
			return new RealHandle(builder.start().toHandle());
		}

		@Override
		public EngineLauncher.@Nullable Handle find(long pid) {
			return ProcessHandle.of(pid).map(RealHandle::new).orElse(null);
		}

		private static java.io.File nullFile() {
			return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
		}
	}

	record RealHandle(ProcessHandle handle) implements EngineLauncher.Handle {
		@Override
		public long pid() {
			return this.handle.pid();
		}

		@Override
		public boolean alive() {
			return this.handle.isAlive() && !zombie(this.handle.pid());
		}

		/** Linux: an exited process nobody reaped (a container whose PID 1 doesn't) still counts as alive to Java. */
		static boolean zombie(long pid) {
			try {
				String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"), StandardCharsets.UTF_8);
				int close = stat.lastIndexOf(')');
				return close > 0 && close + 2 < stat.length() && stat.charAt(close + 2) == 'Z';
			} catch (IOException | RuntimeException e) {
				return false; // no /proc (Windows) or gone
			}
		}

		@Override
		public void destroy(long waitMs) {
			this.handle.destroy();
			try {
				this.handle.onExit().get(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				this.handle.destroyForcibly();
			}
		}
	}
}
