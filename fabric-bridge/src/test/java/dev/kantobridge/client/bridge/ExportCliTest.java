package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ExportCli}: arguments, exit codes and its single {@code export:} line. */
class ExportCliTest {
	@TempDir
	Path temp;

	@Test
	void parsesArgumentsWithDefaults() {
		ExportCli.Args args = ExportCli.parse(new String[] {"--out", this.temp.toString()});
		assertEquals(ClientConfig.DEFAULT_PORT, args.port());
		assertEquals(this.temp.toAbsolutePath().normalize(), args.out());
		assertEquals(ExportClient.Options.DEFAULTS.indexTimeoutMs(), args.indexTimeoutMs());
		ExportCli.Args set = ExportCli.parse(new String[] {"--port", "27196", "--timeout", "90", "--out", "x"});
		assertEquals(27196, set.port());
		assertEquals(90_000, set.indexTimeoutMs());
		assertThrows(IllegalArgumentException.class, () -> ExportCli.parse(new String[] {}));
		assertThrows(IllegalArgumentException.class, () -> ExportCli.parse(new String[] {"--out", "x", "--port", "0"}));
		assertThrows(IllegalArgumentException.class, () -> ExportCli.parse(new String[] {"--out", "x", "--port"}));
		assertThrows(IllegalArgumentException.class, () -> ExportCli.parse(new String[] {"--out", "x", "--verbose"}));
	}

	@Test
	void badArgumentsExitTwoAndAFailedExportExitsOne() throws Exception {
		ByteArrayOutputStream usage = new ByteArrayOutputStream();
		assertEquals(ExportCli.EXIT_USAGE, ExportCli.run(new String[] {"--port", "x"}, new PrintStream(usage, true, StandardCharsets.UTF_8)));
		assertTrue(usage.toString(StandardCharsets.UTF_8).startsWith("export: usage"));

		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		ByteArrayOutputStream failed = new ByteArrayOutputStream();
		int code = ExportCli.run(new String[] {"--port", Integer.toString(closedPort), "--out", this.temp.toString()},
				new PrintStream(failed, true, StandardCharsets.UTF_8));
		assertEquals(ExportCli.EXIT_FAILED, code);
		String text = failed.toString(StandardCharsets.UTF_8);
		assertTrue(text.startsWith("export: failed unreachable"), text);
		assertEquals(1, text.lines().count(), "one line");
		try (var entries = Files.list(this.temp)) {
			assertFalse(entries.findAny().isPresent(), "nothing written");
		}
	}

	@Test
	void theSummaryIsOneLineOfCountsAndHashes() {
		TreeMap<String, Integer> kinds = new TreeMap<>();
		kinds.put("map", 3);
		kinds.put("image", 1);
		String line = ExportCli.summary(new ExportClient.Result(this.temp.resolve("red"), "red", "0.3.49", "0.1.0", 4, 1234, "ab".repeat(32),
				kinds, 56));
		assertEquals("export: ok game=red resources=4 bytes=1234 kinds=image:1,map:3 index=" + "ab".repeat(32)
				+ " engine=0.3.49 bridge=0.1.0 ms=56", line);
	}
}
