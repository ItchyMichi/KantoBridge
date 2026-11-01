package dev.kantobridge.client.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ModelReviewPickerTest {
	private static final String HASH = "0".repeat(64);

	@Test
	void tallRoofStillNamesItsMapAndCell() {
		WorldIndex index = new WorldIndex("red", HASH, List.of(placement("TOWN", 10, 64, 20)), HASH,
				StoryCellTable.of(List.of()), List.of());
		assertEquals(new CellPos("TOWN", 2, 3), ModelReviewPicker.cellAt(index, new BlockPos(15, 76, 27)));
		assertNull(ModelReviewPicker.cellAt(index, new BlockPos(100, 76, 100)));
	}

	@Test
	void tallDisplacedCavePieceUsesPieceOffsetsAndMask() {
		MapLayout layout = MapLayout.of(10, 10, List.of(new MapLayout.Piece(2, 3, 3, 3, 100, -32, 200)), null, List.of());
		WorldIndex index = new WorldIndex("red", HASH, List.of(placement("CAVE", 10, 64, 20)), Map.of("CAVE", layout), HASH,
				StoryCellTable.of(List.of()), List.of(), List.of(), null);
		assertEquals(new CellPos("CAVE", 2, 3), ModelReviewPicker.cellAt(index, new BlockPos(115, 44, 227)));
	}

	private static MapPlacement placement(String id, int x, int y, int z) {
		return new MapPlacement(id, x, y, z, 10, 10, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null);
	}
}
