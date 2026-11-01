package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.ChunkCodesView;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EmeraldFittedCounterTest {
	private static final SceneAtlas ATLAS = new SceneAtlas("g3q", 32, 10000, 8, List.of(), Set.of(), Map.of());
	private static final String[] KEYS = {"g3q/7989-7990-7991-7992", "g3q/8318-8319-8239-8320", "g3q/8319-8319-8149-8149",
			"g3q/8319-8321-8322-8323", "g3q/8319-8319-8330-8320", "g3q/7740-7740-7764-7764", "g3q/7741-7743-7741-7743",
			"g3q/7740-7788-7764-7764", "g3q/7788-7789-7764-7764"};

	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	private static FittedShapes.Entry entry(int height) {
		byte[] voxels = new byte[FittedShapes.VOXEL_BYTES];
		if (height > 0) {
			Arrays.fill(voxels, 0, height * 32, (byte) 255);
		}
		return new FittedShapes.Entry("voxels", List.of(), voxels);
	}

	private static FittedShapes.Table table(String key, FittedShapes.Entry entry, boolean flag, boolean linked, boolean layered) {
		return new FittedShapes.Table(Map.of(key, entry), Map.of(), linked ? Map.of(key, key) : Map.of(), Set.of(),
				layered ? Map.of(key, List.of(new FittedShapes.Layer(new byte[512], null))) : Map.of(), List.of(), Set.of(), Map.of(), flag ? Set.of(key) : Set.of());
	}

	private static SceneMap map(String key) {
		int[] q = Arrays.stream(key.substring(4).split("-")).mapToInt(Integer::parseInt).toArray();
		SceneMap fixture = ShapeFixtures.interior("EM_COUNTER", "..==..", "..==..");
		int[] tiles = fixture.tiles().clone();
		for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) tiles[z * fixture.artCols() + 2 + x] = q[z * 2 + x];
		return new SceneMap(fixture.id(), 0, "EMERALD", "g3q", "BAKED", fixture.w(), fixture.h(), 2, false,
				fixture.env(), tiles, null, fixture.kinds(), fixture.tags(), fixture.dirs(), null, fixture.ownBorder(), List.of(), List.of()).withHeadroom(9);
	}

	private static ShapePlanner.MapShapes plan(SceneMap map, String game, FittedShapes.Table table) {
		FittedShapes.useTablesForTest(Map.of(game, table));
		String previous = FittedShapes.enter(game);
		try { return ShapePlanner.planMap(map, ATLAS, 0, ShapeParams.current(true)); }
		finally { FittedShapes.leave(previous); }
	}

	private static void same(ShapePlanner.MapShapes a, ShapePlanner.MapShapes b) {
		assertArrayEquals(a.open(), b.open());
		assertArrayEquals(a.blocks(), b.blocks());
		assertArrayEquals(a.shapeMask(), b.shapeMask());
		assertArrayEquals(a.floor(), b.floor());
		assertArrayEquals(a.decor(), b.decor());
		assertEquals(a.units(), b.units());
	}

	@Test
	void counterOptInRequiresAnExplicitBooleanAndVoxelClass() {
		Set<String> flags = new HashSet<>();
		FittedShapes.parseCounters("""
			{"cells":{"good":{"class":"voxels","counter":true},"false":{"class":"voxels","counter":false},
			"string":{"class":"voxels","counter":"true"},"number":{"class":"voxels","counter":1},
			"columns":{"class":"columns","counter":true},"missing":{"counter":true},"null":null,"array":[]}}
			""", flags);
		assertEquals(Set.of("good"), flags);
	}

	@Test
	void allNineAuditedKeysKeepOneCollisionBlockAndOnlyTheirRaisedArtUsesDecor() {
		for (String key : KEYS) for (int height : List.of(8, 11)) {
			SceneMap map = map(key);
			byte[] kinds = map.kinds().clone(); int[] tags = map.tags().clone();
			ShapePlanner.MapShapes before = plan(map, "emerald", table(key, entry(height), false, false, false));
			ShapePlanner.MapShapes after = plan(map, "emerald", table(key, entry(height), true, false, false));
			assertEquals(1, after.units().size());
			assertEquals("counter_voxels", after.units().getFirst().source());
			assertArrayEquals(before.blocks(), after.blocks()); assertArrayEquals(before.floor(), after.floor());
			assertArrayEquals(kinds, map.kinds()); assertArrayEquals(tags, map.tags());
			assertEquals(CellKind.COUNTER, map.kind(1, 0));
			for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
				int i = z * map.artCols() + 2 + x;
				assertEquals(1, after.blocks()[i]); assertEquals(1, after.shapeMask()[i]);
				assertEquals(height > 8 ? 2 : 0, after.decor()[i]);
				assertEquals(height > 8 ? 2 : 1, after.open()[i].length);
				assertEquals(ShapeKey.Kind.CELL, after.open()[i][0].kind());
				assertEquals(-1, after.open()[i][0].number(4));
			}
		}
	}

	@Test
	void otherGamesAndInvalidLayeredLinkedEmptyOrOverheightEntriesKeepNativeCounters() {
		String key = KEYS[0]; SceneMap map = map(key);
		for (String game : List.of("blue", "crystal", "red"))
			same(plan(map, game, table(key, entry(11), false, false, false)), plan(map, game, table(key, entry(11), true, false, false)));
		ShapePlanner.MapShapes nativeCounter = plan(map, "emerald", table(key, entry(11), false, false, false));
		for (FittedShapes.Table invalid : List.of(table(key, entry(0), true, false, false), table(key, entry(11), true, true, false),
				table(key, entry(11), true, false, true), table(key, new FittedShapes.Entry("voxels", List.of(), new byte[1024]), true, false, false)))
			same(nativeCounter, plan(map, "emerald", invalid));
		SceneMap low = map.withHeadroom(1);
		same(plan(low, "emerald", table(key, entry(11), false, false, false)), plan(low, "emerald", table(key, entry(11), true, false, false)));
	}

	@Test
	void profilePinsAndHeldBuildingClaimsWinOverCounterOptIn() throws Exception {
		// Profiles intentionally address only their original <=1023 tile range.
		String key = "g3q/9-10-11-12"; SceneMap map = map(key);
		FittedShapes.useTablesForTest(Map.of("emerald", table(key, entry(11), true, false, false)));
		String previous = FittedShapes.enter("emerald");
		try {
			var profile = ProfileUnitsTest.tileset("counter = {9,10,11,12}");
			var pinned = ShapePlanner.planMap(map, ATLAS, 0, ShapeParams.current(true), profile, null, new TreeMap<>());
			assertTrue(pinned.units().isEmpty());
			assertEquals(0, pinned.decor()[2]);
			int n = map.artCols() * map.artRows();
			ShapeKey[][] keys = new ShapeKey[n][]; int[] blocks = new int[n], mask = new int[n], decor = new int[n], floor = new int[n];
			Arrays.fill(floor, -1); boolean[] held = new boolean[n];
			for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) { int i=z*map.artCols()+2+x; blocks[i]=1; held[i]=true; }
			List<Units.Cell> units = new ArrayList<>();
			Units.plan(map, ATLAS, 0, keys, blocks, mask, floor, decor, units, null, null, new TreeMap<>(), held);
			assertTrue(units.isEmpty()); assertEquals(1, blocks[2]); assertEquals(0, mask[2]); assertEquals(0, decor[2]); assertNull(keys[2]);
		} finally { FittedShapes.leave(previous); }
	}

	@Test
	void actualWorldPlanEmitsOneShapeColliderAndOneDecorCarrierAndKeepsCounterFlags() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticGen3Bundle());
		var options = new WorldPlanner.Options(null, TestData.G3_ORIGIN_X, TestData.G3_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, true);
		FittedShapes.useTablesForTest(Map.of("emerald", new FittedShapes.Table(Map.of(), Map.of())));
		WorldPlan before = WorldPlanner.plan(bundle, options);
		SceneMap map = before.scene().map("EM_TEST_HOUSE");
		int[] q = {map.tile(2,2),map.tile(3,2),map.tile(2,3),map.tile(3,3)};
		String key = FittedShapes.key(map.atlas(), q);
		FittedShapes.useTablesForTest(Map.of("emerald", table(key, entry(11), true, false, false)));
		WorldPlan after = WorldPlanner.plan(bundle, options);
		var place = after.index().map("EM_TEST_HOUSE");
		ChunkCodesView original = new ChunkCodesView(before), current = new ChunkCodesView(after);
		assertEquals(CellKind.COUNTER, after.scene().map("EM_TEST_HOUSE").kind(1,1));
		assertArrayEquals(before.scene().map("EM_TEST_HOUSE").kinds(), after.scene().map("EM_TEST_HOUSE").kinds());
		assertArrayEquals(before.scene().map("EM_TEST_HOUSE").tags(), after.scene().map("EM_TEST_HOUSE").tags());
		assertEquals(MapModel.Kind.COUNTER, after.maps().stream().filter(m->m.id().equals("EM_TEST_HOUSE")).findFirst().orElseThrow().model().kind(1,1));
		for (int z=0; z<2; z++) for (int x=0; x<2; x++) {
			int bx=place.x()+2+x,bz=place.z()+2+z,feet=place.y();
			assertEquals(original.code(bx,feet-1,bz),current.code(bx,feet-1,bz));
			assertEquals(BlockCode.KIND_SHAPE,current.kind(bx,feet,bz));
			assertEquals(BlockCode.decor(false),current.code(bx,feet+1,bz));
			assertEquals(ShapeKey.Kind.CELL,after.shapes().index().key(after.shapes().map().shapeAt(bx,feet+1,bz)).kind());
		}
	}
}
