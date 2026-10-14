package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The WorldIndex model: JSON round trips in the schema's shape, lookups, story cells and the parts used to send it. */
class WorldIndexTest {
	private static final String HASH_A = "a".repeat(64);
	private static final String HASH_B = "b".repeat(64);

	static WorldIndex sample() {
		MapPlacement town = new MapPlacement("TEST_TOWN", 100, 64, 200, 20, 18, 0, MapPlacement.Kind.SURFACE, "TEST_PALETTE",
				"kantobridge:kanto_outdoor", false, null);
		MapPlacement house = new MapPlacement("TEST_HOUSE", 102, 42, 194, 10, 10, 1, MapPlacement.Kind.INTERIOR, "TEST_PALETTE",
				"kantobridge:kanto_interior", false, "TEST_TOWN");
		StoryCellTable cells = StoryCellTable.of(List.of(
				new StoryCellTable.Entry(new CellPos("TEST_TOWN", 12, 12), StoryCellType.WATER, null, null),
				new StoryCellTable.Entry(new CellPos("TEST_TOWN", 15, 4), StoryCellType.OBJECT, "TESTTOWN_BALL", null),
				new StoryCellTable.Entry(new CellPos("TEST_TOWN", 10, 14), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15)),
				new StoryCellTable.Entry(new CellPos("TEST_HOUSE", 6, 4), StoryCellType.OBJECT, "TESTHOUSE_MOM", null)));
		return new WorldIndex("red", HASH_A, List.of(town, house), HASH_B, cells,
				List.of(new WorldIndex.Habitat("TEST_TOWN", 120, 61, 210, "kantobridge:test_town")));
	}

	@Test
	void jsonRoundTripIsExact() throws Exception {
		WorldIndex index = sample();
		WorldIndex read = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), read.toJson());
		assertEquals(index.hash(), read.hash());
		assertEquals(Hashes.sha256(index.toJson()), index.hash());
		assertEquals(index.maps(), read.maps());
		assertEquals(index.storyCells().entries(), read.storyCells().entries());
		assertEquals(index.habitats(), read.habitats());
	}

	@Test
	void jsonHasTheSchemaShape() throws Exception {
		JsonObject root = ProtoJson.parseObject(sample().toJson());
		assertEquals(List.of("exportHash", "game", "habitats", "maps", "scale", "slotMapHash", "storyCells", "version"),
				root.keySet().stream().sorted().toList());
		JsonObject scale = root.getAsJsonObject("scale");
		assertEquals(2, scale.get("cellBlocks").getAsInt());
		assertEquals(64, scale.get("surfaceY").getAsInt());
		assertEquals(22, scale.get("layerStep").getAsInt());
		JsonObject town = root.getAsJsonObject("maps").getAsJsonObject("TEST_TOWN");
		assertEquals(List.of("biome", "dark", "h", "kind", "layer", "palette", "parent", "w", "x", "y", "z"),
				town.keySet().stream().sorted().toList());
		assertTrue(town.get("parent").isJsonNull());
		assertEquals("surface", town.get("kind").getAsString());
		// Story cells are sorted by map, row, column.
		assertEquals("TEST_HOUSE:6:4", root.getAsJsonArray("storyCells").get(0).getAsJsonObject().get("key").getAsString());
		assertEquals("TEST_TOWN:15:4", root.getAsJsonArray("storyCells").get(1).getAsJsonObject().get("key").getAsString());
		assertEquals("TEST_TOWN:12:12", root.getAsJsonArray("storyCells").get(2).getAsJsonObject().get("key").getAsString());
		JsonObject tile = root.getAsJsonArray("storyCells").get(3).getAsJsonObject();
		assertEquals("tile", tile.get("type").getAsString());
		assertEquals("cut", tile.getAsJsonObject("swap").get("kind").getAsString());
	}

	@Test
	void storyCellsCarryTheirM8State() throws Exception {
		StoryCellTable cells = StoryCellTable.of(List.of(
				new StoryCellTable.Entry(new CellPos("TEST_TOWN", 15, 4), StoryCellType.OBJECT, "TESTTOWN_BALL", null, true, null, null),
				new StoryCellTable.Entry(new CellPos("TEST_TOWN", 10, 14), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15),
						false, 14, new StoryCellTable.Closed(14, true, 2, List.of(13, 13, 13, 4095)))));
		WorldIndex index = new WorldIndex("red", HASH_A, sample().maps().values(), HASH_B, cells, List.of());
		WorldIndex read = WorldIndex.fromJson(index.toJson());
		assertEquals(index.storyCells().entries(), read.storyCells().entries());
		JsonObject ball = ProtoJson.parseObject(index.toJson()).getAsJsonArray("storyCells").get(0).getAsJsonObject();
		assertEquals(List.of("hidden", "key", "obj", "type"), ball.keySet().stream().sorted().toList());
		JsonObject tile = ProtoJson.parseObject(index.toJson()).getAsJsonArray("storyCells").get(1).getAsJsonObject();
		assertEquals(List.of("closed", "key", "start", "swap", "type"), tile.keySet().stream().sorted().toList());
		assertEquals(List.of("art", "block", "height", "pass"), tile.getAsJsonObject("closed").keySet().stream().sorted().toList());
		// Entries from before M8 (no state fields) still read, and an object that starts visible says nothing.
		JsonObject plain = ProtoJson.parseObject(sample().toJson()).getAsJsonArray("storyCells").get(0).getAsJsonObject();
		assertEquals(List.of("key", "obj", "type"), plain.keySet().stream().sorted().toList());

		String good = index.toJson();
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"art\":[13,13,13,4095]", "\"art\":[13,13,13]")));
		// a closed-art slot past the slot map's capacity (24,576 with 96 families, MG7); 4095 itself is a family-15 slot
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("4095", "24576")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"height\":2", "\"height\":9")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"start\":14", "\"start\":256")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"pass\":\"solid\"", "\"pass\":\"solid\",\"x\":1")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"pass\":\"solid\"", "\"pass\":\"lava\"")));
	}

	@Test
	void anyCatalogueGameIsAccepted() throws Exception {
		// MG1: the game is an open catalogue id (protocol/games.json), no longer red or blue only
		WorldIndex yellow = new WorldIndex("yellow", HASH_A, sample().maps().values(), HASH_B, sample().storyCells(), List.of());
		assertEquals("yellow", yellow.game());
		assertEquals("yellow", WorldIndex.fromJson(yellow.toJson()).game());
		String emerald = sample().toJson().replace("\"game\":\"red\"", "\"game\":\"emerald\"");
		assertEquals("emerald", WorldIndex.fromJson(emerald).game());
		assertThrows(IllegalArgumentException.class,
				() -> new WorldIndex("nope", HASH_A, sample().maps().values(), HASH_B, sample().storyCells(), List.of()));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(sample().toJson().replace("\"game\":\"red\"", "\"game\":\"nope\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(sample().toJson().replace("\"game\":\"red\"", "\"game\":\"Red\"")));
	}

	@Test
	void theFamilyIsOptionalAndRoundTrips() throws Exception {
		// MG1: an index without a family writes none (every golden stays byte-identical); one with it carries it
		WorldIndex plain = sample();
		assertNull(plain.family());
		assertFalse(ProtoJson.parseObject(plain.toJson()).has("family"));
		WorldIndex gen1 = plain.withFamily("gen1");
		assertEquals("gen1", gen1.family());
		assertEquals("gen1", ProtoJson.parseObject(gen1.toJson()).get("family").getAsString());
		WorldIndex read = WorldIndex.fromJson(gen1.toJson());
		assertEquals(gen1.toJson(), read.toJson());
		assertEquals("gen1", read.family());
		assertEquals(plain.maps(), gen1.maps());
		assertTrue(!plain.hash().equals(gen1.hash()), "the family is part of the canonical text");
		assertNull(gen1.withFamily(null).family());
		assertEquals(plain.toJson(), gen1.withFamily(null).toJson());
		assertThrows(IllegalArgumentException.class, () -> plain.withFamily("gen9"));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(gen1.toJson().replace("\"family\":\"gen1\"", "\"family\":\"gen9\"")));
	}

	@Test
	void strictReadingRejectsMalformedIndexes() {
		String good = sample().toJson();
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"version\":1", "\"version\":2")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"cellBlocks\":2", "\"cellBlocks\":3")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"game\":\"red\"", "\"game\":\"green\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"type\":\"water\"", "\"type\":\"lava\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("{\"exportHash\"", "{\"extra\":1,\"exportHash\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good + "x"));
	}

	@Test
	void blocksMapToCellsAndBack() {
		WorldIndex index = sample();
		assertEquals(Optional.of(new CellPos("TEST_TOWN", 0, 0)), index.toCell(new BlockPos(100, 64, 200)));
		assertEquals(Optional.of(new CellPos("TEST_TOWN", 0, 0)), index.toCell(new BlockPos(101, 63, 201)), "standing on water");
		assertEquals(Optional.of(new CellPos("TEST_TOWN", 19, 17)), index.toCell(new BlockPos(139, 64, 235)));
		assertEquals(Optional.empty(), index.toCell(new BlockPos(140, 64, 200)), "east of the map");
		assertEquals(Optional.empty(), index.toCell(new BlockPos(99, 64, 200)), "west of the map");
		assertEquals(Optional.of(new CellPos("TEST_HOUSE", 4, 8)), index.toCell(new BlockPos(110, 42, 210)), "the interior layer");
		assertEquals(Optional.empty(), index.toCell(new BlockPos(110, 53, 210)), "between the layers");

		Vec3 centre = index.toBlock("TEST_TOWN", 3, 5);
		assertEquals(new Vec3(107.0, 64.0, 211.0), centre, "the centre of the cell's 2×2 footprint");
		assertEquals(new BlockPos(106, 64, 210), index.cellOrigin("TEST_TOWN", 3, 5));
		assertEquals(Optional.of(new CellPos("TEST_TOWN", 3, 5)), index.toCell(BlockPos.containing(centre)));
		assertNull(index.toBlock("NOWHERE", 0, 0));
	}

	@Test
	void cellKeys() {
		assertEquals("PALLET_TOWN:5:-1", new CellPos("PALLET_TOWN", 5, -1).key());
		assertEquals(new CellPos("ROUTE_1", 12, 30), CellPos.parse("ROUTE_1:12:30"));
		assertThrows(IllegalArgumentException.class, () -> CellPos.parse("route_1:1:2"));
		assertThrows(IllegalArgumentException.class, () -> CellPos.parse("ROUTE_1:1"));
		assertThrows(IllegalArgumentException.class, () -> StoryCellTable.of(List.of(
				new StoryCellTable.Entry(new CellPos("A", 1, 1), StoryCellType.WATER, null, null),
				new StoryCellTable.Entry(new CellPos("A", 1, 1), StoryCellType.OBJECT, "X", null))));
		assertEquals(2, (int) sample().storyCells().countByType().get(StoryCellType.OBJECT));
		assertEquals(0, (int) sample().storyCells().countByType().get(StoryCellType.BOULDER_AREA));
	}

	@Test
	void partsReassembleInAnyOrderAndCheckTheHash() throws Exception {
		String json = sample().toJson();
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(json, 100);
		assertTrue(parts.size() > 3);
		assertEquals(Hashes.sha256(json), parts.get(0).hash());
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		String whole = null;
		for (int i = parts.size() - 1; i >= 0; i--) {
			WorldIndexParts.Part part = parts.get(i);
			String result = assembler.accept(part.index(), part.count(), part.hash(), part.text());
			if (i > 0) {
				assertNull(result);
			} else {
				whole = result;
			}
		}
		assertEquals(json, whole);

		// A corrupted part fails the hash check.
		WorldIndexParts.Assembler broken = new WorldIndexParts.Assembler();
		for (WorldIndexParts.Part part : parts) {
			String text = part.index() == 1 ? part.text() + " " : part.text();
			if (part.index() < part.count() - 1) {
				broken.accept(part.index(), part.count(), part.hash(), text);
			} else {
				assertThrows(DataException.class, () -> broken.accept(part.index(), part.count(), part.hash(), part.text()));
			}
		}

		assertEquals(1, WorldIndexParts.split("", 10).size());
	}

	@Test
	void aFullPartFitsTheRelayEvenWhenEveryCharacterIsEscaped() throws Exception {
		dev.kantobridge.proto.Catalog catalog = dev.kantobridge.proto.Catalog.bundled();
		JsonObject data = new JsonObject();
		data.addProperty("index", 0);
		data.addProperty("count", 1);
		data.addProperty("hash", HASH_A);
		data.addProperty("part", "\"".repeat(WorldIndexParts.PART_CHARS));
		dev.kantobridge.proto.Envelope message = new dev.kantobridge.proto.Envelope(dev.kantobridge.proto.MsgTypes.WORLD_INDEX, 99, null, data);
		catalog.check(message, dev.kantobridge.proto.Catalog.Leg.S2C);
		int bytes = message.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		assertTrue(bytes <= catalog.relayMaxBytes(), "a world.index part of " + bytes + " bytes exceeds the relay limit");
	}

	@Test
	void aZonesPassComesWithItsGateHouse() throws Exception {
		WorldIndex.Pass pass = new WorldIndex.Pass(List.of(new int[] {9, 0}), "eastern_pass", "hoenn", "from");
		WorldIndex.GateHouse house = new WorldIndex.GateHouse(1, -3, 9, 7, Dir.RIGHT);
		WorldIndex.Zone half = new WorldIndex.Zone("eastern_pass", "KB_ROUTE_EASTERN_PASS", "forest", 1000, 64, 5000, 200, 200,
				"kantobridge:wild_forest", List.of(), List.of(), null, null, pass, house);
		assertTrue(half.passCell(9, 0));
		assertFalse(half.passCell(8, 0));
		assertEquals("to", pass.dir());
		assertTrue(house.contains(9, 0) && !house.contains(10, 0));
		// a zone's route map is a route placement inside its box
		List<MapPlacement> maps = new ArrayList<>(sample().maps().values());
		maps.add(new MapPlacement("KB_ROUTE_EASTERN_PASS", 1010, 64, 5010, 2, 2, 0, MapPlacement.Kind.ROUTE, "TEST_PALETTE",
				"kantobridge:wild_forest", false, null));
		maps.add(new MapPlacement("KB_ROUTE_PLAIN", 1020, 64, 5020, 2, 2, 0, MapPlacement.Kind.ROUTE, "TEST_PALETTE",
				"kantobridge:wild_forest", false, null));
		WorldIndex index = new WorldIndex("red", HASH_A, maps, HASH_B, StoryCellTable.empty(), List.of(), List.of(half));
		WorldIndex back = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), back.toJson());
		assertEquals(half, back.zones().get(0));
		assertEquals(pass, back.zones().get(0).pass());
		// a free-roam zone writes neither field
		WorldIndex.Zone plain = new WorldIndex.Zone("plain", "KB_ROUTE_PLAIN", "forest", 1000, 64, 5000, 200, 200, "kantobridge:wild_forest");
		String plainJson = new WorldIndex("red", HASH_A, maps, HASH_B, StoryCellTable.empty(), List.of(), List.of(plain)).toJson();
		assertFalse(plainJson.contains("\"pass\"") || plainJson.contains("\"gateHouse\""));
		// refusals
		assertThrows(IllegalArgumentException.class, () -> new WorldIndex.Zone("x", "KB_ROUTE_X", "forest", 0, 64, 0, 1, 1, "kantobridge:wild_forest",
				List.of(), List.of(), null, null, pass, null));
		assertThrows(IllegalArgumentException.class, () -> new WorldIndex.Pass(List.of(), "eastern_pass", "hoenn", "from"));
		assertThrows(IllegalArgumentException.class, () -> new WorldIndex.Pass(List.of(new int[] {9, 0}), "eastern_pass", "hoenn", "middle"));
		assertThrows(IllegalArgumentException.class, () -> new WorldIndex.Pass(List.of(new int[] {9, 0}), "eastern_pass", "Hoenn", "from"));
		assertThrows(IllegalArgumentException.class, () -> new WorldIndex.GateHouse(1, -3, 0, 7, Dir.RIGHT));
		String json = index.toJson();
		assertThrows(DataException.class, () -> WorldIndex.fromJson(json.replace("\"end\":\"from\"", "\"end\":\"middle\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(json.replace("\"gateHouse\":", "\"hut\":")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(json.replace("\"cells\":[[9,0]]", "\"cells\":[[9,0,1]]")));
	}
}
