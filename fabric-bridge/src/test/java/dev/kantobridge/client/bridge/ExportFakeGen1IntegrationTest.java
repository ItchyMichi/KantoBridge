package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.Catalog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real {@link ExportClient} over a loopback socket against {@code tools/fake_gen1}, as the "Create Kanto world"
 * flow uses it: the player's main link is connected, pauses for the export (fake_gen1, like gen1-bridge, serves one
 * connection at a time), and reconnects afterwards. The downloaded bundle must be byte-identical to the one
 * {@code python -m fake_gen1 --write-bundle} writes. Skipped without the project venv ({@code tools/setup.ps1}).
 */
class ExportFakeGen1IntegrationTest {
	private static final Pattern LISTENING = Pattern.compile("fake_gen1 listening on 127\\.0\\.0\\.1:(\\d+)");

	@TempDir
	Path temp;

	@Test
	void exportsTheSyntheticWorldAsBuilderByteForByte() throws Exception {
		Path tools = repoRoot().resolve("tools");
		Path python = venvPython(tools);
		Assumptions.assumeTrue(python != null,
				"tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 integration tests");

		Process process = python(tools, python, "-u", "-m", "fake_gen1", "--port", "0", "--game", "red").start();
		BlockingQueue<String> output = pump(process);
		Writer commands = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
		Catalog catalog = Catalog.bundled();
		BridgeClient main = null;
		try {
			int port = waitForPort(output);
			BridgeConfig config = BridgeConfig.fromCatalog(catalog, port).withHeartbeat(200, 5000).withReconnect(100, 500);
			main = new BridgeClient(config, catalog,
					() -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
					Transport.socket(), BridgeClock.system(), new BridgeClient.Listener() {
					});
			main.start();
			BridgeClient link = main;
			await("main link", () -> link.state() == ConnectionState.CONNECTED);

			Path out = this.temp.resolve("export");
			ExportClient.Result result;
			try (BridgeClient.Pause pause = link.pauseReconnects("export", 5000)) {
				assertTrue(pause.settled());
				ExportClient exporter = new ExportClient(BridgeConfig.fromCatalog(catalog, port), catalog,
						new HelloInfo("0.1.0", "1.21.1", null, null), Transport.socket(), BridgeClock.system(), ExportClient.Options.DEFAULTS);
				result = exporter.run(out, ExportClient.Listener.NONE);
			}
			assertEquals("red", result.game());
			await("main link back", () -> link.state() == ConnectionState.CONNECTED && link.stats().snapshot().handshakes() == 2);
			assertEquals(1, link.stats().snapshot().reconnects(), "the same fake_gen1 session: a reconnect and resync");

			Path reference = this.temp.resolve("python");
			Process writer = python(tools, python, "-m", "fake_gen1", "--write-bundle", reference.toString(), "--game", "red").start();
			drain(writer);
			assertTrue(writer.waitFor(60, TimeUnit.SECONDS) && writer.exitValue() == 0, "fake_gen1 --write-bundle failed");

			Map<String, byte[]> expected = FakeExportPeer.files(reference.resolve("red"));
			Map<String, byte[]> actual = FakeExportPeer.files(result.bundleDir());
			assertEquals(expected.keySet(), actual.keySet(), "the same files");
			for (Map.Entry<String, byte[]> file : expected.entrySet()) {
				assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey() + " differs from Python's bundle");
			}
			assertEquals(result.indexHash(), ExportBundle.open(result.bundleDir()).exportHash());
			assertEquals(expected.size() - 1, result.resources(), "every resource plus index.json");

			send(commands, "quit");
			assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fake_gen1 exits on quit");
		} finally {
			if (main != null) {
				main.close();
			}
			process.destroy();
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		}
	}

	private static ProcessBuilder python(Path tools, Path python, String... args) {
		List<String> command = new java.util.ArrayList<>();
		command.add(python.toString());
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command).directory(tools.toFile()).redirectErrorStream(true);
		builder.environment().put("PYTHONUTF8", "1");
		builder.environment().put("PYTHONUNBUFFERED", "1");
		return builder;
	}

	private static BlockingQueue<String> pump(Process process) {
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
		return output;
	}

	private static void drain(Process process) {
		pump(process);
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
