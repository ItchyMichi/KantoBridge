package dev.kantobridge.block;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.world.SlotMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block registration. Tile families: {@code tile_full_00..15} (full cubes) and {@code tile_half_00..15} (half
 * height). Plus the invisible world blocks: {@code story_cell} and {@code story_tile} (a swap site's cell, whose closed
 * art the client draws per player), each with its block entity, and {@code ledge_collider}.
 *
 * <p>The blocks the client's runtime model draws are its <b>carriers</b> ({@link #carriers()}, docs/specs/VOXEL_BLOCKS_PLAN.md
 * §3.3): each with its {@link CarrierKind} and family.
 */
public final class KbBlocks {
	/** Families per shape; 2 shapes × 16 families × 256 slots = 8192 tile states. */
	public static final int FAMILIES_PER_SHAPE = 16;

	private static final VoxelShape FULL = Shapes.block();
	private static final VoxelShape HALF = Block.box(0, 0, 0, 16, 8, 16);

	private static final List<TileBlock> TILE_FULL = new ArrayList<>();
	private static final List<TileBlock> TILE_HALF = new ArrayList<>();
	private static final List<Carrier> CARRIERS = new ArrayList<>();
	private static boolean registered;

	/** Set by {@link #register()}. */
	public static StoryCellBlock STORY_CELL;
	public static StoryTileBlock STORY_TILE;
	public static LedgeColliderBlock LEDGE_COLLIDER;
	public static BlockEntityType<StoryCellBlockEntity> STORY_CELL_ENTITY;
	public static BlockEntityType<StoryCellBlockEntity> STORY_TILE_ENTITY;

	/**
	 * A block the client's runtime model draws.
	 *
	 * @param family the tile family (0..15) of a slot-carrying kind, 0 otherwise
	 */
	public record Carrier(Block block, CarrierKind kind, int family) {
		/** The slot index of a state: {@code family × 256 + hi × 16 + lo} for a slot-carrying kind, else 0. */
		public int slotIndex(BlockState state) {
			return this.kind.hasSlot() ? this.family * SlotMap.SLOTS_PER_FAMILY + TileBlock.slot(state) : 0;
		}
	}

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
		for (int family = 0; family < FAMILIES_PER_SHAPE; family++) {
			CARRIERS.add(new Carrier(TILE_FULL.get(family), CarrierKind.TILE_FULL, family));
		}
		for (int family = 0; family < FAMILIES_PER_SHAPE; family++) {
			CARRIERS.add(new Carrier(TILE_HALF.get(family), CarrierKind.TILE_HALF, family));
		}
		STORY_CELL = register("story_cell", new StoryCellBlock(invisibleProperties()));
		STORY_TILE = register("story_tile", new StoryTileBlock(invisibleProperties()));
		LEDGE_COLLIDER = register("ledge_collider", new LedgeColliderBlock(invisibleProperties()));
		// No data fixer: the block entities hold only our own two strings.
		STORY_CELL_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("story_cell"),
				BlockEntityType.Builder.of(StoryCellBlockEntity::new, STORY_CELL).build(null));
		STORY_TILE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("story_tile"),
				BlockEntityType.Builder.of(StoryCellBlockEntity::tile, STORY_TILE).build(null));
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

	/** Every carrier block, with its kind and family (registration order). */
	public static List<Carrier> carriers() {
		return Collections.unmodifiableList(CARRIERS);
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

	// Invisible blocks: no occlusion, never suffocating or view-blocking (their cached collision is a full cube).
	private static BlockBehaviour.Properties invisibleProperties() {
		return BlockBehaviour.Properties.of()
				.mapColor(MapColor.NONE)
				.strength(-1.0F, 3_600_000.0F)
				.noLootTable()
				.noOcclusion()
				.noTerrainParticles()
				.isSuffocating((state, level, pos) -> false)
				.isViewBlocking((state, level, pos) -> false)
				.isRedstoneConductor((state, level, pos) -> false)
				.pushReaction(PushReaction.BLOCK);
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, path);
	}

	private static <T extends Block> T register(String path, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, id(path), block);
	}
}
