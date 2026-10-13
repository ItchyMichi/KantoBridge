package dev.kantobridge.client.bridge;

import dev.kantobridge.proto.Catalog;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Headless export (docs/PLAN.md §4.5 "ExportBundle"): downloads gen1-bridge's world export as role {@code builder}
 * into {@code <out>/<game>/}, with the same {@link ExportClient} the game uses. Run it through Gradle:
 *
 * <pre>{@code ./gradlew exportCli -Pkb.exportPort=<port> -Pkb.exportOut=<dir> [-Pkb.exportTimeout=<seconds>]}</pre>
 *
 * Arguments: {@code --port <n>} (default 27151), {@code --out <dir>} (required), {@code --timeout <seconds>} (how long
 * to wait for the index, default 120). It prints one line starting with {@code export:} (counts, game and index hash,
 * or the failure code; {@code union_room_on} also says what to click) and exits 0 on success, 1 when the export failed
 * and 2 on bad arguments. The bundle is ROM-derived, so nothing of its content is printed.
 */
public final class ExportCli {
	public static final int EXIT_OK = 0;
	public static final int EXIT_FAILED = 1;
	public static final int EXIT_USAGE = 2;
	/** Set by the Gradle task: this mod's version and the Minecraft version, for {@code hello}. */
	public static final String MOD_VERSION_PROPERTY = "kantobridge.modVersion";
	public static final String MINECRAFT_VERSION_PROPERTY = "kantobridge.minecraftVersion";

	/** Parsed arguments. */
	record Args(int port, Path out, long indexTimeoutMs) {
	}

	private ExportCli() {
	}

	public static void main(String[] args) {
		System.exit(run(args, System.out));
	}

	static int run(String[] args, PrintStream out) {
		Args parsed;
		try {
			parsed = parse(args);
		} catch (IllegalArgumentException e) {
			out.println("export: usage: --out <dir> [--port <n>] [--timeout <seconds>] (" + e.getMessage() + ")");
			return EXIT_USAGE;
		}
		Catalog catalog = Catalog.bundled();
		BridgeConfig config = BridgeConfig.fromCatalog(catalog, parsed.port());
		HelloInfo hello = new HelloInfo(System.getProperty(MOD_VERSION_PROPERTY, "unknown"), System.getProperty(MINECRAFT_VERSION_PROPERTY),
				null, null);
		ExportClient.Options options = new ExportClient.Options(parsed.indexTimeoutMs(), ExportClient.Options.DEFAULTS.stallTimeoutMs(),
				ExportClient.Options.DEFAULTS.pipeline());
		ExportClient client = new ExportClient(config, catalog, hello, Transport.socket(), BridgeClock.system(), options);
		try {
			ExportClient.Result result = client.run(parsed.out(), ExportClient.Listener.NONE);
			out.println(summary(result));
			return EXIT_OK;
		} catch (ExportClient.ExportException e) {
			out.println(failed(e, parsed.port()));
			return EXIT_FAILED;
		} catch (InterruptedException e) {
			out.println("export: failed interrupted");
			return EXIT_FAILED;
		}
	}

	/**
	 * The one line the CLI prints when the export failed: the code with its detail and the port; gen1-bridge's
	 * {@code union_room_on} refusal (Gen1Recomp's Union Room rewrites the Pokémon Centers, so there is nothing to export
	 * until it is off) says what to click instead of the detail.
	 */
	static String failed(ExportClient.ExportException e, int port) {
		String reason = e.code().equals("union_room_on")
				? "union_room_on (the hidden game's Union Room is on: Launcher Options → Union Room → OFF, then restart Gen1Recomp)"
				: e.getMessage();
		return "export: failed " + reason + " port=" + port;
	}

	/** The one line the CLI prints on success: counts, the game and the index hash. */
	static String summary(ExportClient.Result result) {
		return String.format(Locale.ROOT, "export: ok game=%s resources=%d bytes=%d kinds=%s index=%s engine=%s bridge=%s ms=%d", result.game(),
				result.resources(), result.bytes(), ExportClient.kindsText(result.kinds()), result.indexHash(), result.engine(), result.bridge(),
				result.elapsedMs());
	}

	static Args parse(String[] args) {
		int port = ClientConfig.DEFAULT_PORT;
		Path out = null;
		long timeoutMs = ExportClient.Options.DEFAULTS.indexTimeoutMs();
		for (int i = 0; i < args.length; i++) {
			String name = args[i];
			String value = i + 1 < args.length ? args[i + 1] : null;
			switch (name) {
				case "--port" -> {
					port = number(value, "--port", 1, 65535);
					i++;
				}
				case "--out" -> {
					if (value == null || value.isBlank()) {
						throw new IllegalArgumentException("--out needs a folder");
					}
					out = Path.of(value.trim()).toAbsolutePath().normalize();
					i++;
				}
				case "--timeout" -> {
					timeoutMs = number(value, "--timeout", 1, 3600) * 1000L;
					i++;
				}
				default -> throw new IllegalArgumentException("unknown argument " + name);
			}
		}
		if (out == null) {
			throw new IllegalArgumentException("--out is required");
		}
		return new Args(port, out, timeoutMs);
	}

	private static int number(@Nullable String value, String name, int min, int max) {
		try {
			int n = Integer.parseInt(value == null ? "" : value.trim());
			if (n >= min && n <= max) {
				return n;
			}
		} catch (NumberFormatException ignored) {
			// Reported below.
		}
		throw new IllegalArgumentException(name + " needs a whole number from " + min + " to " + max);
	}
}
