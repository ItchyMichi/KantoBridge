package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.SlotMap;
import org.junit.jupiter.api.Test;

/**
 * A pack's own catalogue of a Gen 2 or Gen 3 export enumerates its keys with a region's room (MG7 E7, MG9 J7), never the
 * legacy 16 families: a real Hoenn's keys don't fit those, and a player's pack must hold every game's art.
 */
class ArtCatalogPackRoomTest {
	@Test
	void aPacksCatalogueHasARegionsRoom() {
		assertEquals(80, ArtCatalog.packRoom("emerald").families().size(), "a Gen 3 region's families");
		assertEquals(SlotMap.familiesFor("gen2"), ArtCatalog.packRoom("crystal").families().size());
	}

	@Test
	void everyFamilysExportIsCatalogued() throws Exception {
		Warnings warnings = new Warnings();
		for (var dir : new java.nio.file.Path[] {TestData.syntheticBundle(), TestData.syntheticGen2Bundle(), TestData.syntheticGen3Bundle()}) {
			ArtCatalog art = ArtCatalog.ofExport(ExportBundle.open(dir), warnings);
			assertFalse(art.slotMap().slots().isEmpty(), "keys for " + dir.getFileName());
		}
	}
}
