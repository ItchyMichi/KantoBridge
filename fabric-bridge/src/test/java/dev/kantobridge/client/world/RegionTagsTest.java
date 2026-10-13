package dev.kantobridge.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link RegionTags#TAGGED} is exactly the set of message types whose schema carries a {@code region} (MG3,
 * docs/PROTOCOL.md "Regions (MG3)"): Java bundles only the catalog, so the schemas are read from the repository's
 * protocol/ folder ({@code kb.protocolDir}, set by Gradle).
 */
class RegionTagsTest {
	private static Path protocolDir() {
		String dir = System.getProperty("kb.protocolDir");
		if (dir == null) {
			throw new IllegalStateException("kb.protocolDir is not set; run the tests through Gradle");
		}
		return Path.of(dir);
	}

	/** True when the schema's data object (or one of its alternatives) has a {@code region} property. */
	private static boolean hasRegion(JsonObject schema) {
		JsonElement properties = schema.get("properties");
		if (properties != null && properties.isJsonObject() && properties.getAsJsonObject().has("region")) {
			return true;
		}
		for (String keyword : List.of("oneOf", "anyOf", "allOf")) {
			JsonElement alternatives = schema.get(keyword);
			if (alternatives != null && alternatives.isJsonArray()) {
				for (JsonElement alternative : alternatives.getAsJsonArray()) {
					if (alternative.isJsonObject() && hasRegion(alternative.getAsJsonObject())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** True when {@code region} is a required field of the request itself (party.stash, region.*), not a tag. */
	private static boolean requiresRegion(JsonObject schema) {
		JsonElement required = schema.get("required");
		if (required == null || !required.isJsonArray()) {
			return false;
		}
		for (JsonElement field : required.getAsJsonArray()) {
			if (field.isJsonPrimitive() && "region".equals(field.getAsString())) {
				return true;
			}
		}
		return false;
	}

	private static Set<String> taggedBySchemas() throws Exception {
		Set<String> out = new TreeSet<>();
		try (Stream<Path> files = Files.list(protocolDir().resolve("messages"))) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".schema.json")).sorted().toList()) {
				JsonObject schema = ProtoJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
				if (hasRegion(schema) && !requiresRegion(schema)) {
					out.add(schema.get("x-type").getAsString());
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out;
	}

	@Test
	void theTaggedSetIsExactlyTheSchemasWithARegion() throws Exception {
		Set<String> schemas = taggedBySchemas();
		assertEquals(schemas, new TreeSet<>(RegionTags.TAGGED));
		assertTrue(schemas.size() >= 34, "the 33 relayed types of MG3 plus game.status: " + schemas.size());
	}

	@Test
	void theTagLandsOnlyWhereTheSchemaAllowsIt() {
		assertTrue(RegionTags.tagged("story.state"));
		assertTrue(RegionTags.tagged("warp.request"));
		assertTrue(RegionTags.tagged("game.status"));
		assertFalse(RegionTags.tagged("activity"), "the activity is the body's, not a region's");
		assertFalse(RegionTags.tagged("relay.hello"));
		assertFalse(RegionTags.tagged("region.state"), "it names regions itself");
		assertFalse(RegionTags.tagged("hello"));
	}

	@Test
	void aLegacyWorldAddsNoTag() {
		RegionDocuments docs = new RegionDocuments();
		assertFalse(docs.regionWorld(), "no world.regions: no tag (today's bytes)");
	}
}
