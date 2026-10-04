package dev.kantobridge.client.model;

import dev.kantobridge.block.CarrierKind;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The runtime model of every carrier state (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1): one tiny object per state, knowing
 * only its kind and slot. In the chunk mesh ({@link #emitBlockQuads}, on mesh worker threads, concurrently) it reads one
 * {@link RenderSnapshot} and emits what it says for this position; for state-keyed consumers ({@link #getQuads}: the
 * story renderer's {@code renderSingleBlock}, the breaking overlay, distant-terrain mods) it gives the plain cube of the
 * slot. It never keeps the mesher's {@code pos}, never asks the light engine, and touches no client state but the
 * snapshot (§3.6). Only the public Fabric rendering API is used (I6).
 */
final class TileBakedModel implements BakedModel {
	private final CarrierKind kind;
	private final int slot;
	/** The jar's placeholder, resolved when the model was baked: drawn before the first snapshot exists. */
	private final TextureAtlasSprite placeholder;

	TileBakedModel(CarrierKind kind, int slot, TextureAtlasSprite placeholder) {
		this.kind = kind;
		this.slot = slot;
		this.placeholder = placeholder;
	}

	@Override
	public boolean isVanillaAdapter() {
		return false;
	}

	@Override
	public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos, Supplier<RandomSource> randomSupplier,
			RenderContext context) {
		long started = MeshStats.ENABLED ? System.nanoTime() : 0L;
		RenderSnapshot snapshot = RenderSnapshot.current();
		int quads = snapshot.emitCube(this.kind, this.slot, this.placeholder, context.getEmitter());
		if (MeshStats.ENABLED) {
			MeshStats.ours(System.nanoTime() - started, quads);
		}
	}

	@Override
	public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
		return RenderSnapshot.current().quads(this.kind, this.slot, side, this.placeholder);
	}

	@Override
	public boolean useAmbientOcclusion() {
		return true;
	}

	@Override
	public boolean isGui3d() {
		return false;
	}

	@Override
	public boolean usesBlockLight() {
		return true;
	}

	@Override
	public boolean isCustomRenderer() {
		return false;
	}

	@Override
	public TextureAtlasSprite getParticleIcon() {
		return RenderSnapshot.current().sprite(this.kind, this.slot, this.placeholder);
	}

	@Override
	public ItemTransforms getTransforms() {
		return ItemTransforms.NO_TRANSFORMS;
	}

	@Override
	public ItemOverrides getOverrides() {
		return ItemOverrides.EMPTY;
	}
}
