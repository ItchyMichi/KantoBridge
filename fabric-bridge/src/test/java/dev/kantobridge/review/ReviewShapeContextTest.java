package dev.kantobridge.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ReviewShapeContextTest {
	private static final String DOCUMENT = """
			{"atlases":["test.wall"],"geoms":["slab:0|3|8","cell:0|1,2,3,4|5|1,1,0|v=g1_voxels"],
			 "instances":[[0,0],[1,1]],"palettes":["plain","night"],"planner":"kb-shapes/12",
			 "params":{},"templates":[{"ignored":{"large":[1,2,3]}}],"version":1}
			""";

	@Test
	void resolvesOneBasedInstanceThroughGeometryAndPaletteAndFindsTheWholeUnitOrigin() throws Exception {
		JsonObject surface = JsonParser.parseString("{\"block\":[11,65,20],\"modelIdentity\":\"stable-surface\"}").getAsJsonObject();
		JsonObject context = ReviewShapeContext.describe(DOCUMENT, 2, surface);
		assertEquals("cell:0|1,2,3,4|5|1,1,0|v=g1_voxels", context.get("shapeKey").getAsString());
		assertEquals("test.wall", context.get("atlas").getAsString());
		assertEquals("night", context.get("palette").getAsString());
		assertEquals("[10,64,20]", context.get("modelOrigin").toString());
		assertEquals("cell:0|1,2,3,4|5|0,0,0|v=g1_voxels", context.get("modelKey").getAsString());
		assertEquals("stable-surface", context.get("modelIdentity").getAsString());
		assertFalse(surface.has("shapeKey"), "enrichment must not mutate the active hover selection");
	}

	@Test
	void findsTemplateOriginAndSignatureRegardlessOfJsonFieldOrder() throws Exception {
		String document = """
				{"instances":[[0,0]],"templates":[],"version":1,"geoms":["tmpl:0|0123456789abcdef|4,7,2"],
				 "palettes":["plain"],"atlases":["test.wall"],"planner":"kb-shapes/12"}
				""";
		JsonObject context = ReviewShapeContext.describe(document, 1, JsonParser.parseString("{\"block\":[24,71,12]}").getAsJsonObject());
		assertEquals("0123456789abcdef", context.get("templateSignature").getAsString());
		assertEquals("[20,64,10]", context.get("modelOrigin").toString());
	}

	@Test
	void missingShapeKeepsTheUsefulSurfaceLocation() throws Exception {
		JsonObject context = ReviewShapeContext.describe(DOCUMENT, 500, JsonParser.parseString("{\"block\":[1,2,3]}").getAsJsonObject());
		assertEquals("shape_not_found", context.get("provenance").getAsString());
		assertEquals("[1,2,3]", context.get("block").toString());
	}
}
