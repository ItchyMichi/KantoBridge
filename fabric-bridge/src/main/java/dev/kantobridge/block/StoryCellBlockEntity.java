package dev.kantobridge.block;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellType;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The data of one story-cell block: which cell it belongs to ({@code "MAP:x:y"}) and what kind of blockade it is.
 * Clients need it too (their own collision prediction, and the closed art of tile cells), so it travels in chunk data
 * and block-entity updates. Tile cells ({@link StoryTileBlock}) use their own block-entity type, the only one a
 * renderer is registered for, so the thousands of water and boulder cells cost nothing per frame.
 */
public class StoryCellBlockEntity extends BlockEntity {
	private static final String KEY = "cell";
	private static final String TYPE = "type";

	private @Nullable String cellKey;
	private StoryCellType type = StoryCellType.OBJECT;

	public StoryCellBlockEntity(BlockPos pos, BlockState state) {
		this(KbBlocks.STORY_CELL_ENTITY, pos, state);
	}

	protected StoryCellBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	/** The block entity of a tile cell ({@link KbBlocks#STORY_TILE_ENTITY}). */
	public static StoryCellBlockEntity tile(BlockPos pos, BlockState state) {
		return new StoryCellBlockEntity(KbBlocks.STORY_TILE_ENTITY, pos, state);
	}

	/** The cell key, or null while the block has no data yet (treated as closed). */
	public @Nullable String cellKey() {
		return this.cellKey;
	}

	public StoryCellType type() {
		return this.type;
	}

	/** Sets the cell; returns true when anything changed. */
	public boolean set(String cellKey, StoryCellType type) {
		if (!CellPos.isKey(cellKey)) {
			throw new IllegalArgumentException("invalid cell key");
		}
		Objects.requireNonNull(type, "type");
		if (cellKey.equals(this.cellKey) && type == this.type) {
			return false;
		}
		this.cellKey = cellKey;
		this.type = type;
		this.setChanged();
		return true;
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		String key = tag.getString(KEY);
		this.cellKey = CellPos.isKey(key) ? key : null;
		StoryCellType parsed = StoryCellType.byId(tag.getString(TYPE));
		this.type = parsed != null ? parsed : StoryCellType.OBJECT;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		if (this.cellKey != null) {
			tag.putString(KEY, this.cellKey);
			tag.putString(TYPE, this.type.id());
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
