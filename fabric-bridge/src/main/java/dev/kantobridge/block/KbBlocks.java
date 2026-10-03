package dev.kantobridge.block;

import dev.kantobridge.KantoBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block registration. Tile families: {@code tile_full_00..15} (full cubes) and {@code tile_half_00..15} (half height). */
public final class KbBlocks {
	/** Families per shape; 2 shapes × 16 families × 256 slots = 8192 tile states. */
	public static final int FAMILIES_PER_SHAPE = 16;

	private static final VoxelShape FULL = Shapes.block();
	private static final VoxelShape HALF = Block.box(0, 0, 0, 16, 8, 16);

	private static final List<TileBlock> TILE_FULL = new ArrayList<>();
	private static final List<TileBlock> TILE_HALF = new ArrayList<>();
	private static boolean registered;

	private KbBlocks() {
	}

	/** Registers every block once; safe to call again (unit tests call it after bootstrap). */
	public static synchronized void register() {
		if (registered) {
			return;
		}
		registered = true;
		for (int family = 0; family < FAMILIES_PER_SHAPE; family++) {
			TILE_FULL.add(register(tileId("full", family), new TileBlock(tileProperties(), FULL)));
			TILE_HALF.add(register(tileId("half", family), new TileBlock(tileProperties(), HALF)));
		}
	}

	public static String tileId(String shape, int family) {
		return String.format(Locale.ROOT, "tile_%s_%02d", shape, family);
	}

	public static List<TileBlock> tileFull() {
		return Collections.unmodifiableList(TILE_FULL);
	}

	public static List<TileBlock> tileHalf() {
		return Collections.unmodifiableList(TILE_HALF);
	}

	/** Every tile block, full families first. */
	public static List<TileBlock> allTiles() {
		List<TileBlock> all = new ArrayList<>(TILE_FULL);
		all.addAll(TILE_HALF);
		return Collections.unmodifiableList(all);
	}

	// World blocks: players in Adventure can't break them anyway, and nothing may push or drop them.
	private static BlockBehaviour.Properties tileProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.STONE)
				.strength(-1.0F, 3_600_000.0F)
				.noLootTable()
				.sound(SoundType.STONE)
				.pushReaction(PushReaction.BLOCK);
	}

	private static <T extends Block> T register(String path, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, path), block);
	}
}
