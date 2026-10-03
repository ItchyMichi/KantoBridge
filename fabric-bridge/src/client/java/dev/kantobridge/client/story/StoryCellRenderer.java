package dev.kantobridge.client.story;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a tile story cell's closed state for the local player only (docs/PLAN.md §4.3 {@code StoryCellRenderer};
 * PROTOCOL.md "Story rules (M8)"): the world is built with every swap site open, and while this player's hidden game
 * still has the site closed (a Cut tree standing, a card-key door locked), each block of the cell draws its closed art:
 * the tile block of its slot (the player's own generated pack has the texture; the slot map listed both states of every
 * site), stacked up the closed column, or flat on the floor where the closed state is walkable art. Object cells stay
 * invisible: puppets draw people and items. Runs only for {@code story_tile} blocks, the one type it is registered for.
 */
public final class StoryCellRenderer implements BlockEntityRenderer<StoryCellBlockEntity> {
	/** Closed art is scenery: draw it as far as a player might see a tree line. */
	static final int VIEW_DISTANCE = 256;
	/** Flat art: a thin layer just above the floor's top face. */
	static final float FLAT_LIFT = 0.002F;
	static final float FLAT_THICKNESS = 0.01F;

	private final BlockRenderDispatcher blocks;

	public StoryCellRenderer(BlockEntityRendererProvider.Context context) {
		this.blocks = context.getBlockRenderDispatcher();
	}

	@Override
	public void render(StoryCellBlockEntity cell, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight,
			int packedOverlay) {
		String key = cell.cellKey();
		WorldIndex index = ClientWorldIndex.current();
		if (key == null || index == null || cell.type() != StoryCellType.TILE || !ClientStoryView.drawn(key)) {
			return;
		}
		StoryCellTable.Entry entry = index.storyCells().get(key);
		if (entry == null) {
			return;
		}
		BlockPos pos = cell.getBlockPos();
		StoryCellArt.Piece piece = StoryCellArt.piece(index, entry, pos.getX(), pos.getZ());
		if (piece == null || piece.family() >= KbBlocks.tileFull().size()) {
			return;
		}
		BlockState state = KbBlocks.tileFull().get(piece.family()).withSlot(piece.state());
		if (piece.height() == 0) {
			poseStack.pushPose();
			poseStack.translate(0.0F, FLAT_LIFT, 0.0F);
			poseStack.scale(1.0F, FLAT_THICKNESS, 1.0F);
			this.blocks.renderSingleBlock(state, poseStack, buffers, packedLight, packedOverlay);
			poseStack.popPose();
			return;
		}
		for (int y = 0; y < piece.height(); y++) {
			poseStack.pushPose();
			poseStack.translate(0.0F, y, 0.0F);
			this.blocks.renderSingleBlock(state, poseStack, buffers, packedLight, packedOverlay);
			poseStack.popPose();
		}
	}

	@Override
	public int getViewDistance() {
		return VIEW_DISTANCE;
	}

	@Override
	public boolean shouldRender(StoryCellBlockEntity cell, Vec3 cameraPos) {
		return cell.cellKey() != null && ClientStoryView.drawn(cell.cellKey()) && BlockEntityRenderer.super.shouldRender(cell, cameraPos);
	}
}
