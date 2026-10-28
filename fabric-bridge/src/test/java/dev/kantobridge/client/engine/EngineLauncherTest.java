package dev.kantobridge.client.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WORLD_SESSIONS_PLAN WS7: the launcher with a stub process (start, port wait, already listening, stale PID, stop order). */
class EngineLauncherTest {
	@TempDir
	Path root;

	private final Set<Integer> listening = new HashSet<>();
	private final Map<Long, Stub> processes = new HashMap<>();
	private final List<String> log = new ArrayList<>();
	private long now;
	private long nextPid = 100;

	private final class Stub implements EngineLauncher.Handle {
		final long pid;
		boolean alive = true;

		Stub(long pid) {
			this.pid = pid;
		}

		@Override
		public long pid() {
			return this.pid;
		}

		@Override
		public boolean alive() {
			return this.alive;
		}

		@Override
		public void destroy(long waitMs) {
			EngineLauncherTest.this.log.add("destroy " + this.pid);
			this.alive = false;
		}
	}

	private final EngineLauncher.Processes stubs = new EngineLauncher.Processes() {
		@Override
		public EngineLauncher.Handle start(List<String> command, Path cwd, Map<String, String> env) {
			Stub stub = new Stub(EngineLauncherTest.this.nextPid++);
			EngineLauncherTest.this.processes.put(stub.pid, stub);
			EngineLauncherTest.this.log.add("start " + String.join(" ", command) + " in " + cwd.getFileName() + " " + env);
			return stub;
		}

		@Override
		public EngineLauncher.Handle find(long pid) {
			return EngineLauncherTest.this.processes.get(pid);
		}
	};

	private LaunchConfig config;

	@BeforeEach
	void installs() throws IOException {
		Files.createDirectories(this.root.resolve("local/gen1recomp/blue"));
		Files.writeString(this.root.resolve("local/gen1recomp/blue/" + LaunchConfig.MARKER), "");
		Files.createDirectories(this.root.resolve("local/gen1recomp-johto/crystal"));
		Files.writeString(this.root.resolve("local/gen1recomp-johto/crystal/" + LaunchConfig.MARKER), "");
		Files.createDirectories(this.root.resolve("local/gen1recomp-hoenn"));
		String json = "{\"installs\":{\"kanto\":{\"dir\":\"local\\\\gen1recomp\",\"games\":[\"blue\"],\"play\":\"blue\"},"
				+ "\"johto\":{\"dir\":\"local\\\\gen1recomp-johto\",\"games\":[\"crystal\"]},\"hoenn\":{\"dir\":\"local\\\\gen1recomp-hoenn\",\"games\":[\"emerald\"]}},"
				+ "\"ports\":{\"first\":27151},\"engine\":{\"command\":[\"/app/AppRun\",\"--game={game}\"],\"env\":{\"XDG_DATA_HOME\":\"{installParent}\"}}}";
		this.config = LaunchConfig.parse(json, this.root.resolve("local/launch.json"));
	}

	private EngineLauncher launcher() {
		return new EngineLauncher(this.config, this.stubs, this.listening::contains, () -> this.now);
	}

	private static Map<String, Integer> ports(Object... pairs) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			out.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return out;
	}

	private static final Map<String, String> REGIONS = Map.of("blue", "kanto", "crystal", "johto", "emerald", "hoenn");

	@Test
	void startsWritesThePidFileWaitsForThePortAndStopsOnlyItsOwn() throws IOException {
		this.listening.add(27151); // blue: the user's
		EngineLauncher launcher = this.launcher();
		launcher.ensure(ports("blue", 27151, "crystal", 27152), REGIONS);
		assertEquals(EngineLauncher.State.EXTERNAL, launcher.engine("blue").state());
		assertEquals(EngineLauncher.State.STARTING, launcher.engine("crystal").state());
		assertTrue(this.log.get(0).startsWith("start /app/AppRun --game=crystal in gen1recomp-johto {XDG_DATA_HOME=" + this.root.resolve("local")),
				this.log.get(0));
		Path pid = this.root.resolve("local/gen1recomp-johto/" + EngineLauncher.PID_FILE);
		assertEquals("100", Files.readString(pid));
		assertEquals(List.of("crystal"), launcher.starting());
		this.now = 5_000;
		this.listening.add(27152);
		launcher.tick();
		assertEquals(EngineLauncher.State.RUNNING, launcher.engine("crystal").state());
		launcher.ensure(ports("blue", 27151, "crystal", 27152), REGIONS);
		assertEquals(1, this.log.size(), "a running engine isn't started twice");
		assertFalse(launcher.stop("blue", 1000), "the user's engine is never stopped");
		assertTrue(launcher.stop("crystal", 1000));
		assertEquals("destroy 100", this.log.get(1));
		assertFalse(Files.exists(pid), "the PID file goes with the process");
		assertEquals(List.of(), launcher.owned());
	}

	@Test
	void enginesStartOneAtATimeLowestPortFirstAndAllStopWhenMinecraftCloses() {
		EngineLauncher launcher = this.launcher();
		Map<String, Integer> both = ports("crystal", 27152, "blue", 27151);
		assertEquals(List.of("blue"), launcher.ensure(both, REGIONS).stream().map(EngineLauncher.Engine::game).toList());
		assertEquals(List.of(), launcher.ensure(both, REGIONS), "crystal waits while blue starts");
		assertEquals(List.of(), launcher.tick(), "nothing changed yet");
		this.listening.add(27151);
		assertEquals(List.of("blue"), launcher.tick().stream().map(EngineLauncher.Engine::game).toList());
		assertEquals(List.of("crystal"), launcher.ensure(both, REGIONS).stream().map(EngineLauncher.Engine::game).toList());
		assertEquals(EngineLauncher.State.STARTING, launcher.engine("crystal").state());
		assertEquals(List.of("blue", "crystal"), launcher.stopAll(1000));
		assertEquals(List.of("destroy 100", "destroy 101"), this.log.subList(2, 4));
	}

	@Test
	void aPortThatNeverAnswersIsReportedAfterTheWaitAndAnExitedEngineToo() {
		EngineLauncher launcher = this.launcher();
		launcher.ensure(ports("crystal", 27152), REGIONS);
		this.now = EngineLauncher.PORT_WAIT_MS + 1;
		launcher.tick();
		assertEquals("port_timeout", launcher.engine("crystal").reason());
		assertTrue(launcher.owns("crystal"), "still ours to stop");
		EngineLauncher second = new EngineLauncher(this.config, this.stubs, this.listening::contains, () -> this.now);
		this.processes.get(100L).alive = false;
		second.ensure(ports("blue", 27151), REGIONS);
		this.processes.get(101L).alive = false;
		second.tick();
		assertEquals("exited", second.engine("blue").reason());
	}

	@Test
	void aStalePidFileIsRemovedAndALiveOneIsReportedNotKilled() throws IOException {
		Path pid = this.root.resolve("local/gen1recomp/" + EngineLauncher.PID_FILE);
		Files.writeString(pid, "42");
		EngineLauncher launcher = this.launcher();
		launcher.ensure(ports("blue", 27151), REGIONS);
		assertEquals(EngineLauncher.State.STARTING, launcher.engine("blue").state(), "pid 42 is gone: started");
		Files.writeString(pid, "7");
		this.processes.put(7L, new Stub(7));
		EngineLauncher other = this.launcher();
		other.ensure(ports("blue", 27151), REGIONS);
		assertEquals("pid_alive", other.engine("blue").reason());
		assertTrue(this.processes.get(7L).alive, "never killed");
	}

	@Test
	void aGameNotImportedIsNotStartedAndTheWindowsDefaultIsTheExe() {
		EngineLauncher launcher = this.launcher();
		launcher.ensure(ports("emerald", 27153), REGIONS);
		assertEquals("not_installed", launcher.engine("emerald").reason());
		LaunchConfig plain = LaunchConfig.parse("{\"installs\":{\"kanto\":{\"dir\":\"local/gen1recomp\"}}}", this.root.resolve("local/launch.json"));
		LaunchConfig.Install kanto = plain.installOf("blue", "kanto");
		assertEquals(List.of(this.root.resolve("local/gen1recomp/gen1recomp.exe").toString(), "--game=blue"), plain.commandFor(kanto, "blue"));
		assertEquals(kanto.dir(), plain.cwdFor(kanto, "blue"));
	}
}
