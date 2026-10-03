package dev.kantobridge.proto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The repository's protocol/ folder (set by Gradle as {@code kb.protocolDir}): schemas' examples and vectors. */
final class ProtocolFiles {
	private ProtocolFiles() {
	}

	static Path dir() {
		String dir = System.getProperty("kb.protocolDir");
		if (dir == null) {
			throw new IllegalStateException("kb.protocolDir is not set; run the tests through Gradle");
		}
		return Path.of(dir);
	}

	static String read(String relative) throws IOException {
		return Files.readString(dir().resolve(relative), StandardCharsets.UTF_8);
	}

	static JsonElement readJson(String relative) throws IOException, ProtocolException {
		return ProtoJson.parse(read(relative));
	}

	/** One example envelope, as parsed JSON, with the file it came from. */
	record Example(String file, JsonObject json) {
		String type() {
			return this.json.get("type").getAsString();
		}

		long seq() {
			return this.json.get("seq").getAsLong();
		}
	}

	/** Every envelope in protocol/examples/*.json (each file is a JSON array). */
	static List<Example> examples() throws IOException, ProtocolException {
		List<Example> out = new ArrayList<>();
		List<Path> files;
		try (Stream<Path> stream = Files.list(dir().resolve("examples"))) {
			files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			JsonElement parsed = ProtoJson.parse(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonArray()) {
				throw new IllegalStateException(file.getFileName() + " is not a JSON array");
			}
			JsonArray array = parsed.getAsJsonArray();
			for (JsonElement element : array) {
				out.add(new Example(file.getFileName().toString(), element.getAsJsonObject()));
			}
		}
		return out;
	}
}
