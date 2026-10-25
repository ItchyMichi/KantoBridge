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
 * Block registration. Tile families: {@code tile_full_00..111} (full cubes), {@code tile_half_00..111} (half height) and
 * {@code shape_full_00..111} (full collision around shaped art, never occluding; the voxel plan's V3). Plus
 * {@code decor} and {@code decor_leaves} (visual only), and the invisible world blocks: {@code story_cell} and
 * {@code story_tile} (a swap site's cell, whose closed art the client draws per player), each with its block entity,
 * and {@code ledge_collider} (which carries the ledge lip since V3).
 *
 * <p>The blocks the client's runtime model draws are its <b>carriers</b> ({@link #carriers()}, docs/specs/VOXEL_BLOCKS_PLAN.md
 * §3.3): each with its {@link CarrierKind} and family.
 */
public final class KbBlocks {
	/**
	 * Families per shape; 3 shapes × 112 families × 256 slots = 86,016 tile states. A world of several games shares one
	 * slot map with a family range per region (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3, D9): Kanto's 16 (it uses 3,926
	 * of their 4,096 full slots), since MG7 a Gen 3 region's 80 (Hoenn's flattened art uses about 18,400 full slots:
	 * docs/specs/GEN3_BRIDGE_PLAN.md §5 "Measured", §10 E7), and since MG9 a Gen 2 region's 16 (Johto's baked art uses
	 * 3,560 full slots: GEN2_BRIDGE_PLAN §11 J7). MG3 measured about 15 MB a side per 16 families.
	 */
	public static final int FAMILIES_PER_SHAPE = 112;

	private static final VoxelShape FULL = Shapes.block();
	private static final VoxelShape HALF = Block.box(0, 0, 0, 16, 8, 16);

	private static final List<TileBlock> TILE_FULL = new ArrayList<>();
	private static final List<TileBlock> TILE_HALF = new ArrayList<>();
	private static final List<ShapeBlock> SHAPE_FULL = new ArrayList<>();
	private static final List<Carrier> CARRIERS = new ArrayList<>();
	private static boolean registered;

	/** Set by {@link #register()}. */
	public static StoryCellBlock STORY_CELL;
	public static StoryTileBlock STORY_TILE;
	public static LedgeColliderBlock LEDGE_COLLIDER;
	/** Invisible walls on tile-pair edges (docs/specs/CAVES_3D_PLAN.md §3.2). */
	public static EdgeWallBlock EDGE_WALL;
	/** A wall for bikers only (FIELD_MECHANICS_AUDIT_PLAN §13.8 item 10). */
	public static BikeWallBlock BIKE_WALL;
	/** A waterfall's foot wall, open during a forced move (WATER_AND_BRIDGES_PLAN D4). */
	public static FallWallBlock FALL_WALL;
	/** The half steps of a cave's stairs (docs/specs/CAVES_3D_PLAN.md §3.4). */
	public static StepColliderBlock STEP_COLLIDER;
	public static SeatColliderBlock SEAT_COLLIDER;
	/** A cave shaft's ladder (docs/specs/CAVES_3D_PLAN.md §3.5). */
	public static ShaftLadderBlock SHAFT_LADDER;
	public static DecorBlock DECOR;
	public static DecorBlock DECOR_LEAVES;
	/** Wild Routes' props (WR7): the dungeon entrance, stairs and exit. */
	public static PropBlock PROP;
	public static BlockEntityType<StoryCellBlockEntity> STORY_CELL_ENTITY;
	public static BlockEntityType<StoryCellBlockEntity> STORY_TILE_ENTITY;

	/**
	 * A block the client's runtime model draws.
	 *
	 * @param family the tile family (0..31) of a slot-carrying kind, 0 otherwise
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
			SHAPE_FULL.add(register(shapeId(family), new ShapeBlock(shapeProperties())));
		}
		for (int family = 0; family < FAMILIES_PER_SHAPE; family++) {
			CARRIERS.add(new Carrier(TILE_FULL.get(family), CarrierKind.TILE_FULL, family));
		}
		for (int family = 0; family < FAMILIES_PER_SHAPE; family++) {
			CARRIERS.add(new Carrier(TILE_HALF.get(family), CarrierKind.TILE_HALF, family));
		}
		for (int family = 0; family < FAMILIES_PER_SHAPE; family++) {
			CARRIERS.add(new Carrier(SHAPE_FULL.get(family), CarrierKind.SHAPE_FULL, family));
		}
		STORY_CELL = register("story_cell", new StoryCellBlock(invisibleProperties()));
		STORY_TILE = register("story_tile", new StoryTileBlock(invisibleProperties()));
		LEDGE_COLLIDER = register("ledge_collider", new LedgeColliderBlock(invisibleProperties()));
		EDGE_WALL = register("edge_wall", new EdgeWallBlock(invisibleProperties()));
		BIKE_WALL = register("bike_wall", new BikeWallBlock(invisibleProperties()));
		FALL_WALL = register("fall_wall", new FallWallBlock(invisibleProperties()));
		STEP_COLLIDER = register("step_collider", new StepColliderBlock(invisibleProperties()));
		SEAT_COLLIDER = register("seat_collider", new SeatColliderBlock(invisibleProperties()));
		SHAFT_LADDER = register("shaft_ladder", new ShaftLadderBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0F, 3_600_000.0F)
				.noLootTable().noOcclusion().sound(net.minecraft.world.level.block.SoundType.LADDER).pushReaction(PushReaction.BLOCK)));
		DECOR = register("decor", new DecorBlock(decorProperties()));
		DECOR_LEAVES = register("decor_leaves", new DecorBlock(decorProperties()));
		PROP = register("prop", new PropBlock(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1.0F, 3_600_000.0F)
				.noLootTable().noOcclusion().noCollission().isSuffocating((state, level, pos) -> false)
				.isViewBlocking((state, level, pos) -> false)));
		CARRIERS.add(new Carrier(DECOR, CarrierKind.DECOR, 0));
		CARRIERS.add(new Carrier(DECOR_LEAVES, CarrierKind.DECOR_LEAVES, 0));
		CARRIERS.add(new Carrier(LEDGE_COLLIDER, CarrierKind.LEDGE, 0));
		CARRIERS.add(new Carrier(STEP_COLLIDER, CarrierKind.STEP, 0));
		CARRIERS.add(new Carrier(STORY_TILE, CarrierKind.STORY_TILE, 0));
		CARRIERS.add(new Carrier(SEAT_COLLIDER, CarrierKind.SEAT, 0));
		// No data fixer: the block entities hold only our own two strings.
		STORY_CELL_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("story_cell"),
				BlockEntityType.Builder.of(StoryCellBlockEntity::new, STORY_CELL).build(null));
		STORY_TILE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("story_tile"),
				BlockEntityType.Builder.of(StoryCellBlockEntity::tile, STORY_TILE).build(null));
	}

	public static String tileId(String shape, int family) {
		return String.format(Locale.ROOT, "tile_%s_%02d", shape, family);
	}

	public static String shapeId(int family) {
		return String.format(Locale.ROOT, "shape_full_%02d", family);
	}

	public static List<TileBlock> tileFull() {
		return Collections.unmodifiableList(TILE_FULL);
	}

	public static List<TileBlock> tileHalf() {
		return Collections.unmodifiableList(TILE_HALF);
	}

	public static List<ShapeBlock> shapeFull() {
		return Collections.unmodifiableList(SHAPE_FULL);
	}

	/** Every carrier block, with its kind and family (registration order). */
	public static List<Carrier> carriers() {
		return Collections.unmodifiableList(CARRIERS);
	}

	/** Every tile block: full, half, then shape families. */
	public static List<TileBlock> allTiles() {
		List<TileBlock> all = new ArrayList<>(TILE_FULL);
		all.addAll(TILE_HALF);
		all.addAll(SHAPE_FULL);
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

	// Shaped tiles: a full cube to walk into and to pick, but no occlusion, never suffocating or view-blocking.
	private static BlockBehaviour.Properties shapeProperties() {
		return tileProperties()
				.noOcclusion()
				.isSuffocating((state, level, pos) -> false)
				.isViewBlocking((state, level, pos) -> false)
				.isRedstoneConductor((state, level, pos) -> false);
	}

	// Decoration: nothing to collide with or to pick; drawn by the runtime model only. A lit one gives an interior
	// light's light (CW1).
	private static BlockBehaviour.Properties decorProperties() {
		return invisibleProperties().noCollission().lightLevel(state -> state.getValue(DecorBlock.LIT) ? DecorBlock.LIGHT : 0);
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
				.pushReaction(PushReaction.BLOCK)
				// Not ground to stand on for Cobblemon's spawner (it asks isSolid): no wild Pokémon on an invisible wall,
				// ledge or story cell (docs/specs/CAVES_3D_PLAN.md §3.3)
				.forceSolidOff();
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, path);
	}

	private static <T extends Block> T register(String path, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, id(path), block);
	}
}
