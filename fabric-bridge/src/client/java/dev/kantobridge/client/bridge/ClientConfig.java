package dev.kantobridge.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * The client's settings file, {@code <config dir>/kantobridge-client.json}. Only the port is configurable: the host
 * is always loopback, because gen1-bridge never listens elsewhere (docs/PROTOCOL.md "Transport"). A different port
 * lets two client/Gen1Recomp pairs share one PC.
 *
 * <p>A missing file is created with the defaults. A broken file or value falls back to the default with a warning,
 * so a typo never stops the game. The system property {@code kantobridge.port} overrides the file for dev runs.
 *
 * @param port the port gen1-bridge listens on
 */
public record ClientConfig(int port) {
	public static final String FILE_NAME = "kantobridge-client.json";
	public static final String PORT_PROPERTY = "kantobridge.port";
	public static final int DEFAULT_PORT = 27151;
	public static final ClientConfig DEFAULTS = new ClientConfig(DEFAULT_PORT);

	/** Reads (or creates) the file in {@code configDir}, then applies the system property override. */
	public static ClientConfig load(Path configDir, Properties systemProperties, Consumer<String> warn) {
		Path file = configDir.resolve(FILE_NAME);
		int port = DEFAULT_PORT;
		if (Files.isRegularFile(file)) {
			port = readPort(file, warn);
		} else {
			try {
				Files.createDirectories(configDir);
				Files.writeString(file, "{\n  \"port\": " + DEFAULT_PORT + "\n}\n", StandardCharsets.UTF_8);
			} catch (IOException e) {
				warn.accept("could not create " + FILE_NAME + " (" + e.getClass().getSimpleName() + "); using defaults");
			}
		}
		String override = systemProperties.getProperty(PORT_PROPERTY);
		if (override != null) {
			Integer parsed = parsePort(override.trim());
			if (parsed != null) {
				port = parsed;
			} else {
				warn.accept("ignoring -D" + PORT_PROPERTY + ": not a port number from 1 to 65535");
			}
		}
		return new ClientConfig(port);
	}

	private static int readPort(Path file, Consumer<String> warn) {
		JsonObject root;
		try {
			root = ProtoJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | ProtocolException e) {
			warn.accept(FILE_NAME + " is not valid JSON; using the default port " + DEFAULT_PORT);
			return DEFAULT_PORT;
		}
		JsonElement value = root.get("port");
		if (value == null) {
			return DEFAULT_PORT;
		}
		Integer port = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? parsePort(value.getAsString()) : null;
		if (port == null) {
			warn.accept(FILE_NAME + ": port must be a whole number from 1 to 65535; using " + DEFAULT_PORT);
			return DEFAULT_PORT;
		}
		return port;
	}

	private static Integer parsePort(String text) {
		try {
			int port = Integer.parseInt(text);
			return port >= 1 && port <= 65535 ? port : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
