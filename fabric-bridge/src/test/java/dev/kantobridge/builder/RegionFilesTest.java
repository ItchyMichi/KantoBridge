package dev.kantobridge.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.pack.DatapackWriter;
import dev.kantobridge.builder.pack.HabitatPoolWriter;
import dev.kantobridge.builder.plan.RegionBuild;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.SlotMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MG3: the builder's region files, family ranges and the datapack merge agree with the world model. */
class RegionFilesTest {
	@TempDir
	Path temp;

	@Test
	void theLegacyDocumentsAreTheBuildersRootFiles() {
		// world/ can't import the builder: the record names the root files itself
		assertEquals(BuildTask.INDEX_FILE, RegionRecord.ROOT_INDEX);
		assertEquals(BuildTask.SHAPE_INDEX_FILE, RegionRecord.ROOT_SHAPE_INDEX);
		assertEquals(BuildTask.SHAPE_MAP_FILE, RegionRecord.ROOT_SHAPE_MAP);
		assertEquals(RegionRecord.Documents.root(), new RegionRecord.Documents(BuildTask.INDEX_FILE, BuildTask.SHAPE_INDEX_FILE, BuildTask.SHAPE_MAP_FILE));
		assertEquals("regions/hoenn/" + BuildTask.INDEX_FILE, RegionRecord.fileIndex("hoenn"));
	}

	@Test
	void thePoolPrefixIsEmptyAtSlotZeroOnly() {
		assertEquals("", RegionBuild.poolPrefix("kanto", 0));
		assertEquals("", RegionBuild.poolPrefix("hoenn", 0), "a world whose first region is Hoenn keeps plain ids too");
		assertEquals("hoenn_", RegionBuild.poolPrefix("hoenn", 1));
		assertEquals("test2_", RegionBuild.poolPrefix("test2", 1));
	}

	@Test
	void theNextFreeRangeSkipsWhatOtherRegionsHold() {
		assertEquals(SlotMap.LEGACY_FAMILIES, BuildTask.nextFreeRange(List.of()));
		RegionRecord kanto = record("kanto", 0, SlotMap.LEGACY_FAMILIES);
		assertEquals(new SlotMap.Range(16, 31), BuildTask.nextFreeRange(List.of(kanto)));
		RegionRecord other = record("johto", 1, new SlotMap.Range(16, 31));
		assertEquals(new SlotMap.Range(32, 47), BuildTask.nextFreeRange(List.of(kanto, other)), "MG7: 96 families per shape");
		assertEquals(SlotMap.LEGACY_FAMILIES, BuildTask.nextFreeRange(List.of(other)), "the first range is free again");
	}

	@Test
	void aGen3RegionTakesEightyFamilies() {
		// MG7 (GEN3_BRIDGE_PLAN §10 E7): Hoenn's flattened art needs about 72 families of 256
		assertEquals(80, SlotMap.familiesFor("gen3"));
		assertEquals(16, SlotMap.familiesFor("gen1"));
		assertEquals(16, SlotMap.familiesFor(null));
		RegionRecord kanto = record("kanto", 0, SlotMap.LEGACY_FAMILIES);
		assertEquals(new SlotMap.Range(16, 95), BuildTask.nextFreeRange(List.of(kanto), 80), "Kanto and Hoenn fill the 96");
		assertEquals(new SlotMap.Range(0, 79), BuildTask.nextFreeRange(List.of(), 80));
		RegionRecord johto = record("johto", 1, new SlotMap.Range(16, 31));
		assertNull(BuildTask.nextFreeRange(List.of(kanto, johto), 80), "80 contiguous families from 32 would pass 95");
	}

	@Test
	void theTableIsEmptyWithoutAFile() {
		assertTrue(BuildTask.readTable(this.temp).isEmpty());
	}

	@Test
	void theDatapackMergeKeepsTheOtherRegionsPools() throws Exception {
		Path world = this.temp.resolve("world");
		Path pools = world.resolve("datapacks").resolve(DatapackWriter.FOLDER).resolve(HabitatPoolWriter.FOLDER);
		Files.createDirectories(pools);
		// made-up pool texts: the merge copies them as they are
		Files.writeString(pools.resolve("test_town.json"), "{\"old\":1}", StandardCharsets.UTF_8);
		Files.writeString(pools.resolve("zone_test_zone_a1.json"), "{\"old\":2}", StandardCharsets.UTF_8);
		Files.writeString(pools.resolve("test2_test_town.json"), "{\"old\":3}", StandardCharsets.UTF_8);
		Files.writeString(pools.resolve("test2_zone_test_zone_a1.json"), "{\"old\":4}", StandardCharsets.UTF_8);
		Files.writeString(pools.resolve("notes.txt"), "ignored", StandardCharsets.UTF_8);
		// the unprefixed region (slot 0) rebuilt: its own pools replaced, test2's kept
		Map<String, String> merged = BuildTask.mergedPools(world, Map.of("kantobridge:test_town", "{\"new\":1}"), "", List.of("test2_"));
		assertEquals(Set.of("kantobridge:test_town", "kantobridge:test2_test_town", "kantobridge:test2_zone_test_zone_a1"), merged.keySet());
		assertEquals("{\"new\":1}", merged.get("kantobridge:test_town"));
		assertEquals("{\"old\":3}", merged.get("kantobridge:test2_test_town"));
		// test2 rebuilt: everything not its own kept
		merged = BuildTask.mergedPools(world, Map.of("kantobridge:test2_test_town", "{\"new\":3}"), "test2_", List.of(""));
		assertEquals(Set.of("kantobridge:test_town", "kantobridge:zone_test_zone_a1", "kantobridge:test2_test_town"), merged.keySet());
		assertEquals("{\"new\":3}", merged.get("kantobridge:test2_test_town"));
		// a world without a datapack yet
		assertEquals(Map.of("kantobridge:x", "{}"), BuildTask.mergedPools(this.temp.resolve("fresh"), Map.of("kantobridge:x", "{}"), "", List.of()));
		assertTrue(BuildTask.keepsPool("hoenn_route_101", "", List.of("hoenn_")));
		assertFalse(BuildTask.keepsPool("route_1", "", List.of("hoenn_")));
		assertFalse(BuildTask.keepsPool("hoenn_route_101", "hoenn_", List.of("")));
		assertTrue(BuildTask.keepsPool("route_1", "hoenn_", List.of("")));
	}

	private static RegionRecord record(String id, int slot, SlotMap.Range families) {
		String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
		return new RegionRecord(id, "red", "gen1", hash, slot, new RegionRecord.Origin(RegionTable.PITCH * slot, 64, 0),
				new RegionRecord.Box(RegionTable.PITCH * slot, 0, RegionTable.PITCH * slot + 100, 100, -256, 319), RegionRecord.Areas.NONE, families,
				RegionRecord.Documents.of(id), new RegionRecord.DocumentHashes(hash, hash, hash), RegionRecord.NO_GENERATOR,
				new RegionRecord.Rules("gen1", null, RegionRecord.Compat.of(Games.compat("red"))), RegionRecord.Namespaces.of("red", id));
	}
}
