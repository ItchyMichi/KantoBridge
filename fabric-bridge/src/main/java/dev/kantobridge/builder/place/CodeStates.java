package dev.kantobridge.builder.place;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.world.SlotMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Turns planned {@link BlockCode}s into block states, caching the tile states. */
public final class CodeStates {
	private final BlockState[] full = new BlockState[SlotMap.CAPACITY];
	private final BlockState[] half = new BlockState[SlotMap.CAPACITY];
	private final BlockState[] shape = new BlockState[SlotMap.CAPACITY];
	private final BlockState[] vanilla = new BlockState[ZoneBlock.values().length];

	public BlockState state(int code) {
		int value = BlockCode.value(code);
		return switch (BlockCode.kind(code)) {
			case BlockCode.KIND_FULL -> this.tile(this.full, true, value);
			case BlockCode.KIND_HALF -> this.tile(this.half, false, value);
			case BlockCode.KIND_SHAPE -> this.shape(value);
			case BlockCode.KIND_DECOR -> ((value & 1) != 0 ? KbBlocks.DECOR_LEAVES : KbBlocks.DECOR).defaultBlockState()
					.setValue(dev.kantobridge.block.DecorBlock.LIT, (value & 2) != 0);
			case BlockCode.KIND_LEDGE -> KbBlocks.LEDGE_COLLIDER.defaultBlockState().setValue(LedgeColliderBlock.FACING, downhill(value));
			case BlockCode.KIND_LIGHT -> Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, Math.min(15, value));
			case BlockCode.KIND_STORY -> KbBlocks.STORY_CELL.defaultBlockState();
			case BlockCode.KIND_STORY_TILE -> KbBlocks.STORY_TILE.defaultBlockState();
			case BlockCode.KIND_VANILLA -> this.vanilla(value);
			case BlockCode.KIND_PROP -> prop(value);
			case BlockCode.KIND_EDGE -> KbBlocks.EDGE_WALL.withSides(value);
			default -> Blocks.AIR.defaultBlockState();
		};
	}

	/** A Wild Routes prop (WR7): kind and quarter from the code's value. */
	private static BlockState prop(int value) {
		dev.kantobridge.block.PropBlock.Kind[] kinds = dev.kantobridge.block.PropBlock.Kind.values();
		int kind = value / 4;
		if (kind < 0 || kind >= kinds.length) {
			return Blocks.AIR.defaultBlockState();
		}
		return KbBlocks.PROP.defaultBlockState().setValue(dev.kantobridge.block.PropBlock.KIND, kinds[kind])
				.setValue(dev.kantobridge.block.PropBlock.PART, value % 4);
	}

	/** A Wild Routes zone's vanilla block (leaves placed persistent, so they never decay). */
	private BlockState vanilla(int ordinal) {
		if (ordinal < 0 || ordinal >= this.vanilla.length) {
			return Blocks.AIR.defaultBlockState();
		}
		BlockState state = this.vanilla[ordinal];
		if (state == null) {
			state = ZoneBlock.values()[ordinal].state();
			this.vanilla[ordinal] = state;
		}
		return state;
	}

	private BlockState tile(BlockState[] cache, boolean fullShape, int index) {
		BlockState state = cache[index];
		if (state == null) {
			int family = index / SlotMap.SLOTS_PER_FAMILY;
			int slot = index % SlotMap.SLOTS_PER_FAMILY;
			state = (fullShape ? KbBlocks.tileFull() : KbBlocks.tileHalf()).get(family).withSlot(slot);
			cache[index] = state;
		}
		return state;
	}

	private BlockState shape(int index) {
		BlockState state = this.shape[index];
		if (state == null) {
			state = KbBlocks.shapeFull().get(index / SlotMap.SLOTS_PER_FAMILY).withSlot(index % SlotMap.SLOTS_PER_FAMILY);
			this.shape[index] = state;
		}
		return state;
	}

	/** Gen 1 hop direction (0 down, 1 up, 2 left, 3 right) as a Minecraft direction: map down is +Z (south). */
	public static Direction downhill(int gen1Dir) {
		return switch (gen1Dir & 3) {
			case 0 -> Direction.SOUTH;
			case 1 -> Direction.NORTH;
			case 2 -> Direction.WEST;
			default -> Direction.EAST;
		};
	}
}
