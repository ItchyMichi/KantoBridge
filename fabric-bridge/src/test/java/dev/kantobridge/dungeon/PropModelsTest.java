package dev.kantobridge.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.block.PropBlock;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** WR7: every prop state (kind × quarter) has its variant in the blockstate and its model on the classpath. */
class PropModelsTest {
	@Test
	void everyKindAndQuarterHasAModel() throws Exception {
		JsonObject variants;
		try (InputStream in = PropModelsTest.class.getResourceAsStream("/assets/kantobridge/blockstates/prop.json")) {
			assertNotNull(in);
			variants = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
		}
		assertEquals(PropBlock.Kind.values().length * 4, variants.size());
		for (PropBlock.Kind kind : PropBlock.Kind.values()) {
			for (int part = 0; part < 4; part++) {
				JsonObject variant = variants.getAsJsonObject("kind=" + kind.getSerializedName() + ",part=" + part);
				assertNotNull(variant, kind + " " + part);
				String model = variant.get("model").getAsString();
				String path = "/assets/kantobridge/models/" + model.substring(model.indexOf(':') + 1) + ".json";
				try (InputStream in = PropModelsTest.class.getResourceAsStream(path)) {
					assertNotNull(in, path);
				}
			}
		}
	}
}
