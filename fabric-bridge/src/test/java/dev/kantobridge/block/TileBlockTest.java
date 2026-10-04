package dev.kantobridge.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.McBootstrap;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spike S2: tile families × 256 states register, map slots 1:1, and stay cheap in memory. Since the voxel plan's V3: 48
 * families (full, half, shape) and the carriers of the runtime model.
 */
class TileBlockTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
		KbBlocks.register();
	}

	@Test
	void everyTileFamilyIsACarrierWithItsSlots() {
		List<KbBlocks.Carrier> carriers = KbBlocks.carriers();
		assertEquals(3 * 16 + 3, carriers.size(), "the tile, half and shape families, decor, decor_leaves and the ledge collider");
		for (int family = 0; family < KbBlocks.FAMILIES_PER_SHAPE; family++) {
			KbBlocks.Carrier full = carriers.get(family);
			KbBlocks.Carrier half = carriers.get(KbBlocks.FAMILIES_PER_SHAPE + family);
			KbBlocks.Carrier shape = carriers.get(2 * KbBlocks.FAMILIES_PER_SHAPE + family);
			assertSame(KbBlocks.tileFull().get(family), full.block());
			assertSame(KbBlocks.tileHalf().get(family), half.block());
			assertSame(KbBlocks.shapeFull().get(family), shape.block());
			assertEquals(CarrierKind.TILE_FULL, full.kind());
			assertEquals(CarrierKind.TILE_HALF, half.kind());
			assertEquals(CarrierKind.SHAPE_FULL, shape.kind());
			assertEquals(family, full.family());
			assertEquals(family, shape.family());
		}
		assertEquals(List.of(CarrierKind.DECOR, CarrierKind.DECOR_LEAVES, CarrierKind.LEDGE),
				carriers.subList(48, 51).stream().map(KbBlocks.Carrier::kind).toList());
		assertSame(KbBlocks.LEDGE_COLLIDER, carriers.get(50).block());
		assertEquals(0, carriers.get(50).slotIndex(KbBlocks.LEDGE_COLLIDER.defaultBlockState()), "no slot");
		KbBlocks.Carrier carrier = carriers.get(3);
		TileBlock block = (TileBlock) carrier.block();
		for (int slot : new int[] {0, 1, 17, 255}) {
			assertEquals(3 * 256 + slot, carrier.slotIndex(block.withSlot(slot)), "family × 256 + hi × 16 + lo");
		}
		assertTrue(CarrierKind.TILE_HALF.half() && !CarrierKind.TILE_FULL.half());
		assertTrue(CarrierKind.TILE_FULL.drawsCube() && CarrierKind.TILE_FULL.hasSlot());
		assertTrue(CarrierKind.SHAPE_FULL.drawsCube() && CarrierKind.SHAPE_FULL.hasSlot() && !CarrierKind.SHAPE_FULL.half(),
				"a shape tile's fallback is its slot's full cube");
		for (CarrierKind kind : List.of(CarrierKind.DECOR, CarrierKind.DECOR_LEAVES, CarrierKind.LEDGE)) {
			assertTrue(!kind.drawsCube() && !kind.hasSlot(), kind + " draws only its shape");
		}
	}

	@Test
	void fortyEightFamiliesOf256States() {
		List<TileBlock> tiles = KbBlocks.allTiles();
		assertEquals(48, tiles.size());
		int states = 0;
		for (TileBlock block : tiles) {
			assertEquals(256, block.getStateDefinition().getPossibleStates().size());
			states += block.getStateDefinition().getPossibleStates().size();
		}
		assertEquals(12288, states);
		for (int family = 0; family < KbBlocks.FAMILIES_PER_SHAPE; family++) {
			assertSame(KbBlocks.tileFull().get(family), byId(KbBlocks.tileId("full", family)));
			assertSame(KbBlocks.tileHalf().get(family), byId(KbBlocks.tileId("half", family)));
			assertSame(KbBlocks.shapeFull().get(family), byId(KbBlocks.shapeId(family)));
		}
		assertEquals("tile_full_00", KbBlocks.tileId("full", 0));
		assertEquals("tile_half_15", KbBlocks.tileId("half", 15));
		assertEquals("shape_full_07", KbBlocks.shapeId(7));
	}

	@Test
	void slotsMapOneToOne() {
		TileBlock block = KbBlocks.tileFull().get(0);
		Set<BlockState> seen = new HashSet<>();
		for (int slot = 0; slot < 256; slot++) {
			BlockState state = block.withSlot(slot);
			assertEquals(slot, TileBlock.slot(state));
			assertTrue(seen.add(state), "two slots share a state");
		}
		assertThrows(IllegalArgumentException.class, () -> block.withSlot(256));
		assertThrows(IllegalArgumentException.class, () -> block.withSlot(-1));
	}

	@Test
	void shapesPerFamily() {
		BlockState full = KbBlocks.tileFull().get(5).withSlot(17);
		BlockState half = KbBlocks.tileHalf().get(5).withSlot(17);
		assertTrue(full.isCollisionShapeFullBlock(null, null), "full tiles must be full blocks (Cobblemon spawns need solid ground)");
		assertEquals(0.5, half.getShape(null, null).max(Direction.Axis.Y));
	}

	@Test
	void stateMemoryStaysSmall() {
		// Builds the same 32 two-property state definitions again and measures what they retain, as the P5 research
		// harness did. One 0..255 property would retain about 95 MB here; hi/lo should stay near 5 MB.
		long before = usedHeapAfterGc();
		List<StateDefinition<Block, BlockState>> definitions = new ArrayList<>();
		for (int i = 0; i < 32; i++) {
			definitions.add(new StateDefinition.Builder<Block, BlockState>(Blocks.STONE)
					.add(TileBlock.HI, TileBlock.LO)
					.create(Block::defaultBlockState, BlockState::new));
		}
		long after = usedHeapAfterGc();
		int states = definitions.stream().mapToInt(d -> d.getPossibleStates().size()).sum();
		double megabytes = (after - before) / (1024.0 * 1024.0);
		KantoBridge.LOGGER.info("S2 memory: {} states in 32 hi/lo definitions retain about {} MB", states, String.format("%.1f", megabytes));
		assertEquals(8192, states);
		assertTrue(megabytes < 32, "tile states retain " + megabytes + " MB");
	}

	private static Block byId(String path) {
		return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, path));
	}

	private static long usedHeapAfterGc() {
		for (int i = 0; i < 4; i++) {
			System.gc();
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}
}
