package dev.kantobridge.client.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.6 (WS7; D8): Minecraft starts the hidden games a world lists. For each game whose port isn't
 * answering: its install from {@link LaunchConfig} (the game imported there), the command started with the install as
 * working folder, {@code <install>/kb_engine.pid} written, the port awaited ({@link #PORT_WAIT_MS}); the link connects
 * by itself. A port already answering is an engine the user (or {@code launch.ps1}) started: used, never stopped. A stale
 * PID file whose process is gone is removed; one whose process is alive but not listening is reported, not killed. Only
 * engines this launcher started are stopped ({@link #stop}), after their game answered {@code session.close}. Plain
 * Java: processes, ports and the clock come in through {@link Processes}, {@link Ports} and a clock.
 */
public final class EngineLauncher {
	public static final long PORT_WAIT_MS = 30_000;
	public static final String PID_FILE = "kb_engine.pid";

	/** Starts and finds processes. */
	public interface Processes {
		Handle start(List<String> command, Path cwd, Map<String, String> env) throws IOException;

		/** A running process by PID, or null. */
		@Nullable
		Handle find(long pid);
	}

	/** One engine process. */
	public interface Handle {
		long pid();

		boolean alive();

		/** Ends it (Process.destroy) and waits up to {@code waitMs} for it to go. */
		void destroy(long waitMs);
	}

	/** Whether something listens on a loopback port. */
	public interface Ports {
		boolean listening(int port);
	}

	/** One game's engine state. */
	public enum State {
		/** Found listening: the user's, never stopped. */
		EXTERNAL,
		/** Started, its port not answering yet. */
		STARTING,
		/** Started by us and listening. */
		RUNNING,
		/** Not started: {@link Engine#reason}. */
		FAILED,
		/** Stopped by us. */
		STOPPED
	}

	/** One game's engine. */
	public static final class Engine {
		final String game;
		final int port;
		@Nullable
		Handle handle;
		@Nullable
		Path pidFile;
		@Nullable
		Path installDir;
		State state;
		@Nullable
		String reason;
		long startedAt;

		Engine(String game, int port, State state) {
			this.game = game;
			this.port = port;
			this.state = state;
		}

		public String game() {
			return this.game;
		}

		public State state() {
			return this.state;
		}

		public @Nullable String reason() {
			return this.reason;
		}

		public @Nullable Long pid() {
			return this.handle == null ? null : this.handle.pid();
		}
	}

	private final LaunchConfig config;
	private final Processes processes;
	private final Ports ports;
	private final LongSupplier clock;
	private final Map<String, Engine> engines = new LinkedHashMap<>();

	public EngineLauncher(LaunchConfig config, Processes processes, Ports ports, LongSupplier clock) {
		this.config = config;
		this.processes = processes;
		this.ports = ports;
		this.clock = clock;
	}

	/**
	 * Makes sure each game (with its world space {@code regions[game]} and its link's port) has an engine: found, or
	 * started. One engine starts at a time, lowest port first: gen1-bridge takes the first free port from its own
	 * setting, so engines started together could swap ports; the next waits until the last answers or fails (call
	 * again). Two games of one install never run at once. Returns the engines touched now.
	 */
	public List<Engine> ensure(Map<String, Integer> portByGame, Map<String, String> regionByGame) {
		List<Engine> touched = new ArrayList<>();
		if (!this.starting().isEmpty()) {
			return touched;
		}
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(portByGame.entrySet());
		entries.sort(Map.Entry.comparingByValue());
		for (Map.Entry<String, Integer> entry : entries) {
			String game = entry.getKey();
			int port = entry.getValue();
			Engine known = this.engines.get(game);
			if (known != null && (known.state == State.STARTING || known.state == State.RUNNING || known.state == State.EXTERNAL)
					&& (known.handle == null || known.handle.alive())) {
				continue;
			}
			Engine engine = this.ensureOne(game, port, regionByGame.get(game));
			this.engines.put(game, engine);
			touched.add(engine);
			if (engine.state == State.STARTING) {
				break;
			}
		}
		return touched;
	}

	private boolean installBusy(Path dir) {
		for (Engine engine : this.engines.values()) {
			if (dir.equals(engine.installDir) && engine.handle != null && engine.handle.alive()
					&& (engine.state == State.STARTING || engine.state == State.RUNNING)) {
				return true;
			}
		}
		return false;
	}

	private Engine ensureOne(String game, int port, @Nullable String region) {
		if (this.ports.listening(port)) {
			return new Engine(game, port, State.EXTERNAL);
		}
		Engine engine = new Engine(game, port, State.FAILED);
		LaunchConfig.Install install = this.config.installOf(game, region);
		if (install == null || !install.imported(game)) {
			engine.reason = "not_installed";
			return engine;
		}
		if (this.installBusy(install.dir())) {
			engine.reason = "install_busy";
			return engine;
		}
		Path pidFile = install.dir().resolve(PID_FILE);
		Long stale = readPid(pidFile);
		if (stale != null) {
			Handle old = this.processes.find(stale);
			if (old != null && old.alive()) {
				// alive but not listening: reported, never killed
				engine.reason = "pid_alive";
				return engine;
			}
			try {
				Files.deleteIfExists(pidFile);
			} catch (IOException e) {
				engine.reason = "pid_file";
				return engine;
			}
		}
		try {
			Handle handle = this.processes.start(this.config.commandFor(install, game), this.config.cwdFor(install, game),
					this.config.envFor(install, game));
			Files.writeString(pidFile, Long.toString(handle.pid()), StandardCharsets.UTF_8);
			engine.handle = handle;
			engine.pidFile = pidFile;
			engine.state = State.STARTING;
			engine.startedAt = this.clock.getAsLong();
			engine.installDir = install.dir();
		} catch (IOException | RuntimeException e) {
			engine.reason = "start_failed";
		}
		return engine;
	}

	/**
	 * Polls the started engines' ports: STARTING becomes RUNNING, or FAILED ("port_timeout", "exited") after the wait.
	 * Returns the engines that changed.
	 */
	public List<Engine> tick() {
		long now = this.clock.getAsLong();
		List<Engine> changed = new ArrayList<>();
		for (Engine engine : this.engines.values()) {
			if (engine.state != State.STARTING) {
				continue;
			}
			if (engine.handle != null && !engine.handle.alive()) {
				engine.state = State.FAILED;
				engine.reason = "exited";
				this.removePid(engine);
			} else if (this.ports.listening(engine.port)) {
				engine.state = State.RUNNING;
			} else if (now - engine.startedAt > PORT_WAIT_MS) {
				// left running (it may still come up), reported
				engine.state = State.FAILED;
				engine.reason = "port_timeout";
			}
			if (engine.state != State.STARTING) {
				changed.add(engine);
			}
		}
		return changed;
	}

	/** True when this launcher started {@code game}'s engine and it is still ours to stop. */
	public boolean owns(String game) {
		Engine engine = this.engines.get(game);
		return engine != null && engine.handle != null && (engine.state == State.RUNNING || engine.state == State.STARTING
				|| "port_timeout".equals(engine.reason));
	}

	/** Stops {@code game}'s engine if this launcher started it (after its session.closed). Returns whether it did. */
	public boolean stop(String game, long waitMs) {
		Engine engine = this.engines.get(game);
		if (engine == null || !this.owns(game)) {
			return false;
		}
		engine.handle.destroy(waitMs);
		engine.state = State.STOPPED;
		this.removePid(engine);
		return true;
	}

	/** Stops every engine this launcher still owns (Minecraft closing). Returns the games stopped. */
	public List<String> stopAll(long waitMs) {
		List<String> stopped = new ArrayList<>();
		for (String game : this.owned()) {
			if (this.stop(game, waitMs)) {
				stopped.add(game);
			}
		}
		return stopped;
	}

	/** The engines this launcher started and still owns. */
	public List<String> owned() {
		List<String> out = new ArrayList<>();
		for (String game : this.engines.keySet()) {
			if (this.owns(game)) {
				out.add(game);
			}
		}
		return out;
	}

	public @Nullable Engine engine(String game) {
		return this.engines.get(game);
	}

	/** The games starting now (the HUD's "Starting Crystal…"). */
	public List<String> starting() {
		List<String> out = new ArrayList<>();
		for (Engine engine : this.engines.values()) {
			if (engine.state == State.STARTING) {
				out.add(engine.game);
			}
		}
		return out;
	}

	private void removePid(Engine engine) {
		if (engine.pidFile != null) {
			try {
				Files.deleteIfExists(engine.pidFile);
			} catch (IOException ignored) {
				// a stale file is removed at the next start
			}
		}
	}

	static @Nullable Long readPid(Path file) {
		try {
			return Files.isRegularFile(file) ? Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim()) : null;
		} catch (IOException | NumberFormatException e) {
			return -1L;
		}
	}
}
