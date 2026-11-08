package dev.kantobridge.bag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * A diagnostic trace of the bag mirror and the trainer ledger, for reproducing items that come back or go missing (the
 * user's report of 2026-11-07): one line per event in {@code logs/kb-bagtrace.log} under the game directory, from the
 * server ({@code S}) and the client ({@code C}) alike (in singleplayer both write the same file, in order). gen1-bridge
 * writes the hidden game's side as {@code bagtrace.*} lines in Gen1Recomp's mod log.
 *
 * <p>A line is {@code HH:mm:ss.SSS side event key=value ...}; maps print as {@code {A:1,B:2}} in key order. Item symbols,
 * canonical ids, counts, region ids, message seqs and the first six digits of stamps only, never game text. On unless
 * the JVM runs with {@code -Dkantobridge.bagtrace=false}; past {@value #MAX_BYTES} bytes the file moves to
 * {@code kb-bagtrace.1.log} and a new one starts. A write that fails turns the trace off with one warning.
 */
public final class BagTrace {
	public static final String FILE = "kb-bagtrace.log";
	static final long MAX_BYTES = 16L * 1024 * 1024;
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("kantobridge.bagtrace", "true"));
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	private static @Nullable BufferedWriter out;
	private static boolean failed;
	private static long written;

	private BagTrace() {
	}

	/** True while lines are written. */
	public static boolean on() {
		return ENABLED && !failed;
	}

	/** A server-side event: {@code kv} alternates keys and values. */
	public static void server(String event, Object... kv) {
		log("S", event, kv);
	}

	/** A client-side event: {@code kv} alternates keys and values. */
	public static void client(String event, Object... kv) {
		log("C", event, kv);
	}

	/** A player as the trace names them: the first eight hex digits of the UUID (no names). */
	public static String who(java.util.UUID player) {
		return player.toString().substring(0, 8);
	}

	/** The first six digits of a stamp, or {@code -}. */
	public static String stamp(@Nullable String stamp) {
		return stamp == null ? "-" : stamp.substring(0, Math.min(6, stamp.length()));
	}

	/** Only the entries of a count map that aren't zero (the readable part of a whole-bag map). */
	public static Map<String, Integer> nonZero(@Nullable Map<String, Integer> counts) {
		Map<String, Integer> out = new TreeMap<>();
		if (counts != null) {
			counts.forEach((key, value) -> {
				if (value != null && value != 0) {
					out.put(key, value);
				}
			});
		}
		return out;
	}

	static String line(String side, String event, Object... kv) {
		StringBuilder line = new StringBuilder(LocalTime.now().format(TIME)).append(' ').append(side).append(' ').append(event);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			line.append(' ').append(kv[i]).append('=').append(value(kv[i + 1]));
		}
		return line.toString();
	}

	static String value(@Nullable Object value) {
		if (value == null) {
			return "-";
		}
		if (value instanceof Map<?, ?> map) {
			StringBuilder out = new StringBuilder("{");
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((key, v) -> sorted.put(String.valueOf(key), v));
			sorted.forEach((key, v) -> out.append(out.length() > 1 ? "," : "").append(key).append(':').append(signed(v)));
			return out.append('}').toString();
		}
		if (value instanceof JsonObject json) {
			Map<String, Object> sorted = new TreeMap<>();
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				JsonElement v = entry.getValue();
				sorted.put(entry.getKey(), v.isJsonPrimitive() ? v.getAsJsonPrimitive().getAsString() : "?");
			}
			return value(sorted);
		}
		if (value instanceof JsonElement json) {
			return json.isJsonPrimitive() ? json.getAsJsonPrimitive().getAsString() : json.isJsonNull() ? "-" : "?";
		}
		return String.valueOf(value);
	}

	private static String signed(@Nullable Object v) {
		return v == null ? "-" : String.valueOf(v);
	}

	private static synchronized void log(String side, String event, Object... kv) {
		if (!on()) {
			return;
		}
		String text;
		try {
			text = line(side, event, kv);
		} catch (RuntimeException e) {
			text = side + " " + event + " (unprintable)";
		}
		write(text);
	}

	private static void write(String text) {
		try {
			if (out == null || written > MAX_BYTES) {
				open();
			}
			out.write(text);
			out.newLine();
			out.flush();
			written += text.length() + 1;
		} catch (IOException | RuntimeException | LinkageError e) {
			failed = true;
			close();
			KantoBridge.LOGGER.warn("bagtrace: stopped, the trace file can't be written ({})", e.getClass().getSimpleName());
		}
	}

	private static void open() throws IOException {
		close();
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs");
		Files.createDirectories(dir);
		Path file = dir.resolve(FILE);
		if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
			Files.move(file, dir.resolve("kb-bagtrace.1.log"), StandardCopyOption.REPLACE_EXISTING);
		}
		written = Files.exists(file) ? Files.size(file) : 0;
		out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		out.write("# kb-bagtrace opened " + LocalDateTime.now().withNano(0));
		out.newLine();
		out.flush();
		KantoBridge.LOGGER.info("bagtrace: writing the bag and ledger trace to logs/{}", FILE);
	}

	private static void close() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException ignored) {
				// the trace is diagnostic only
			}
			out = null;
		}
	}
}
