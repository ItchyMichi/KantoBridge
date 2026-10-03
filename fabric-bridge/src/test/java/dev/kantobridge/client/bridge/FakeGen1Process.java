package dev.kantobridge.client.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * {@code tools/fake_gen1} as a subprocess for integration tests (a stand-in for Gen1Recomp + gen1-bridge with a made-up
 * world), started from the project venv on a free port. {@link #start} returns null when the venv is missing, so tests
 * can skip.
 */
public final class FakeGen1Process implements AutoCloseable {
	private static final Pattern LISTENING = Pattern.compile("fake_gen1 listening on 127\\.0\\.0\\.1:(\\d+)");

	private final Process process;
	private final Writer commands;
	private final int port;
	/** fake_gen1's output lines not read yet (its console answers: counts, status). */
	private final BlockingQueue<String> output;

	private FakeGen1Process(Process process, Writer commands, int port, BlockingQueue<String> output) {
		this.process = process;
		this.commands = commands;
		this.port = port;
		this.output = output;
	}

	/** Starts fake_gen1 and waits for its port; null without the venv ({@code tools/setup.ps1} creates it). */
	public static @Nullable FakeGen1Process start() throws IOException, InterruptedException {
		Path tools = repoRoot().resolve("tools");
		Path python = null;
		for (String candidate : List.of(".venv/Scripts/python.exe", ".venv/bin/python")) {
			if (Files.isRegularFile(tools.resolve(candidate))) {
				python = tools.resolve(candidate);
				break;
			}
		}
		if (python == null) {
			return null;
		}
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
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			String line = output.poll(100, TimeUnit.MILLISECONDS);
			Matcher matcher = line == null ? null : LISTENING.matcher(line);
			if (matcher != null && matcher.find()) {
				return new FakeGen1Process(process, new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8),
						Integer.parseInt(matcher.group(1)), output);
			}
		}
		process.destroyForcibly();
		throw new AssertionError("fake_gen1 did not report its port");
	}

	public int port() {
		return this.port;
	}

	/** Types a console command (see {@code python -m fake_gen1 --help}). */
	public void command(String line) throws IOException {
		this.commands.write(line + "\n");
		this.commands.flush();
	}

	/** The next output line that starts with {@code prefix} (earlier lines are skipped), or null after {@code ms}. */
	public @Nullable String awaitLine(String prefix, long ms) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
		while (System.nanoTime() < deadline) {
			String line = this.output.poll(50, TimeUnit.MILLISECONDS);
			if (line != null && line.startsWith(prefix)) {
				return line;
			}
		}
		return null;
	}

	@Override
	public void close() throws InterruptedException {
		this.process.destroy();
		if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
			this.process.destroyForcibly();
		}
	}

	private static Path repoRoot() {
		String protocolDir = System.getProperty("kb.protocolDir");
		if (protocolDir == null) {
			throw new IllegalStateException("kb.protocolDir is not set; run the tests through Gradle");
		}
		return Path.of(protocolDir).toAbsolutePath().normalize().getParent();
	}
}
