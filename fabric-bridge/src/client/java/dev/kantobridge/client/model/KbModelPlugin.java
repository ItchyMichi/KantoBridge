package dev.kantobridge.client.model;

import dev.kantobridge.block.KbBlocks;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives every state of every carrier block our runtime model (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1): one block-state
 * resolver per carrier, which sets a {@link TileUnbakedModel} exactly once per state. A block with a resolver never reads
 * a blockstate JSON from any pack, so the jar's placeholder blockstates and an older generated pack's are dead for these
 * blocks. Registered once from client init; Fabric calls it at the start of every resource reload.
 */
public final class KbModelPlugin implements ModelLoadingPlugin {
	private static boolean registered;

	private KbModelPlugin() {
	}

	/** Call once from the client entrypoint, after the blocks are registered. */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		ModelLoadingPlugin.register(new KbModelPlugin());
		MeshStats.setModel("runtime");
	}

	@Override
	public void onInitializeModelLoader(Context context) {
		for (KbBlocks.Carrier carrier : KbBlocks.carriers()) {
			context.registerBlockStateResolver(carrier.block(), resolver -> {
				for (BlockState state : resolver.block().getStateDefinition().getPossibleStates()) {
					resolver.setModel(state, new TileUnbakedModel(carrier.kind(), carrier.slotIndex(state)));
				}
			});
		}
	}
}
