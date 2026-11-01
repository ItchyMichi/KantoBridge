package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FittedShapesComparisonTest {
	private static final int[] TILES = {1, 2, 3, 4};
	private static final String ATLAS = "gallery.synthetic";
	@AfterEach void reset() { FittedShapes.useTablesForTest(null); }

	private static String table(byte[] voxels) {
		JsonObject entry = new JsonObject();
		entry.addProperty("class", "voxels");
		entry.addProperty("v", Base64.getEncoder().encodeToString(voxels));
		JsonObject cells = new JsonObject();
		cells.add(FittedShapes.key(ATLAS, TILES), entry);
		JsonObject root = new JsonObject();
		root.addProperty("kb_fitted_shapes", 1);
		root.add("cells", cells);
		return root.toString();
	}

	@Test void baselineAndCurrentHashesRemainDistinctAndComparisonDoesNotLeak() {
		byte[] before = new byte[512], after = new byte[512];
		before[0] = 1; after[0] = 2;
		var previousHash = FittedShapes.hashParams(before);
		var currentHash = FittedShapes.hashParams(after);
		FittedShapes.useTablesForTest(Map.of("blue", new FittedShapes.Table(Map.of(FittedShapes.key(ATLAS, TILES),
				new FittedShapes.Entry("voxels", currentHash, after)), Map.of())));
		assertNull(FittedShapes.voxels(ATLAS, TILES, previousHash));
		FittedShapes.withComparisonTables(List.of(table(before)), () -> {
			assertTrue(FittedShapes.voxels(ATLAS, TILES, previousHash)[0]);
			assertFalse(FittedShapes.voxels(ATLAS, TILES, previousHash)[1]);
			assertTrue(FittedShapes.voxels(ATLAS, TILES, currentHash)[1]);
			return null;
		});
		assertNull(FittedShapes.voxels(ATLAS, TILES, previousHash));
		assertTrue(FittedShapes.voxels(ATLAS, TILES, currentHash)[1]);
	}

	@Test void failedComparisonRestoresOuterScope() {
		byte[] a = new byte[512], b = new byte[512]; a[0] = 1; b[0] = 4;
		var hashA = FittedShapes.hashParams(a); var hashB = FittedShapes.hashParams(b);
		FittedShapes.useTablesForTest(Map.of());
		FittedShapes.withComparisonTables(List.of(table(a)), () -> {
			assertThrows(IllegalStateException.class, () -> FittedShapes.withComparisonTables(List.of(table(b)), () -> {
				assertNotNull(FittedShapes.voxels(ATLAS, TILES, hashB));
				throw new IllegalStateException("intentional failed build");
			}));
			assertNotNull(FittedShapes.voxels(ATLAS, TILES, hashA));
			assertNull(FittedShapes.voxels(ATLAS, TILES, hashB));
			return null;
		});
		assertNull(FittedShapes.voxels(ATLAS, TILES, hashA));
	}
}
