package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The real {@link BridgeClient}, over a real loopback socket, against {@code tools/fake_gen1} (a stand-in for
 * Gen1Recomp + gen1-bridge with a made-up world). fake_gen1 runs from the project venv; without the venv this test is
 * skipped, so run {@code tools/setup.ps1} (or {@code setup.sh}) once.
 */
class FakeGen1IntegrationTest {
	private static final Pattern LISTENING = Pattern.compile("fake_gen1 listening on 127\\.0\\.0\\.1:(\\d+)");

	@Test
	void connectsFollowsTheWalkAndReconnectsAfterADrop() throws Exception {
		Path tools = repoRoot().resolve("tools");
		Path python = venvPython(tools);
		Assumptions.assumeTrue(python != null,
				"tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 integration test");

		ProcessBuilder builder = new ProcessBuilder(python.toString(), "-u", "-m", "fake_gen1", "--port", "0")
				.directory(tools.toFile())
				.redirectErrorStream(true);
		builder.environment().put("PYTHONUTF8", "1");
		builder.environment().put("PYTHONUNBUFFERED", "1");
		Process process = builder.start();
		BlockingQueue<String> output = new LinkedBlockingQueue<>();
		Thread pump = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					output.add(line);
				}
			} catch (IOException ignored) {
				// The process ended.
			}
		}, "fake_gen1-output");
		pump.setDaemon(true);
		pump.start();
		Writer commands = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

		BridgeClient client = null;
		try {
			int port = waitForPort(output);
			Catalog catalog = Catalog.bundled();
			List<Envelope> messages = new CopyOnWriteArrayList<>();
			BridgeConfig config = BridgeConfig.fromCatalog(catalog, port).withHeartbeat(200, 5000).withReconnect(100, 500);
			client = new BridgeClient(config, catalog,
					() -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
					Transport.socket(), BridgeClock.system(), new BridgeClient.Listener() {
						@Override
						public void message(Envelope message) {
							messages.add(message);
						}
					});
			client.start();
			BridgeClient connected = client;

			await("handshake", () -> connected.state() == ConnectionState.CONNECTED);
			assertEquals("red", connected.handshake().game());
			await("snapshot", () -> count(messages, "snapshot") == 1);
			JsonObject player = last(messages, "snapshot").data().getAsJsonObject("player");
			assertEquals("TEST_TOWN", player.get("map").getAsString());
			assertEquals(5, player.get("x").getAsInt());
			assertEquals(8, player.get("y").getAsInt());
			await("two pongs", () -> connected.stats().snapshot().pongs() >= 2);

			send(commands, "walk up 2");
			await("two steps", () -> count(messages, "player.pos") >= 2);
			JsonObject pos = last(messages, "player.pos").data();
			assertEquals(6, pos.get("y").getAsInt());
			assertEquals("walk", pos.get("kind").getAsString());
			assertEquals("up", pos.get("facing").getAsString());

			send(commands, "drop");
			await("reconnect", () -> connected.stats().snapshot().handshakes() == 2 && connected.state() == ConnectionState.CONNECTED);
			await("second snapshot", () -> count(messages, "snapshot") == 2);
			assertEquals(6, last(messages, "snapshot").data().getAsJsonObject("player").get("y").getAsInt(),
					"the snapshot after the reconnect has the walked position");
			BridgeStats.Snapshot stats = connected.stats().snapshot();
			assertEquals(1, stats.reconnects(), "same fake_gen1 process, same session");
			assertEquals(0, stats.restarts());
			assertEquals(0, stats.protocolErrors(), "fake_gen1 speaks the protocol");

			send(commands, "quit");
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fake_gen1 exits on quit");
			await("disconnect after quit", () -> connected.state() != ConnectionState.CONNECTED);
		} finally {
			if (client != null) {
				client.close();
			}
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
	}

	private static Path repoRoot() {
		String protocolDir = System.getProperty("kb.protocolDir");
		if (protocolDir == null) {
			throw new IllegalStateException("kb.protocolDir is not set; run the tests through Gradle");
		}
		return Path.of(protocolDir).toAbsolutePath().normalize().getParent();
	}

	private static Path venvPython(Path tools) {
		for (String candidate : List.of(".venv/Scripts/python.exe", ".venv/bin/python")) {
			Path python = tools.resolve(candidate);
			if (Files.isRegularFile(python)) {
				return python;
			}
		}
		return null;
	}

	private static int waitForPort(BlockingQueue<String> output) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			String line = output.poll(100, TimeUnit.MILLISECONDS);
			if (line != null) {
				Matcher matcher = LISTENING.matcher(line);
				if (matcher.find()) {
					return Integer.parseInt(matcher.group(1));
				}
			}
		}
		throw new AssertionError("fake_gen1 did not report its port");
	}

	private static void send(Writer commands, String command) throws IOException {
		commands.write(command + "\n");
		commands.flush();
	}

	private static long count(List<Envelope> messages, String type) {
		return messages.stream().filter(m -> m.type().equals(type)).count();
	}

	private static Envelope last(List<Envelope> messages, String type) {
		Envelope found = null;
		for (Envelope message : messages) {
			if (message.type().equals(type)) {
				found = message;
			}
		}
		if (found == null) {
			throw new AssertionError("no " + type);
		}
		return found;
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(20);
		}
	}
}
