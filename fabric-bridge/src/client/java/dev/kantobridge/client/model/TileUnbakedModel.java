package dev.kantobridge.client.model;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.CarrierKind;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The unbaked model of one carrier state (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1): no dependencies; baking only resolves the
 * jar's placeholder sprite and returns a {@link TileBakedModel} for the state's kind and slot. Every quad comes from the
 * {@link RenderSnapshot} at mesh time, so nothing slot-specific is baked here.
 */
final class TileUnbakedModel implements UnbakedModel {
	/** The jar's placeholder tile ({@code textures/block/tile_placeholder.png}, stitched into the block atlas). */
	static final ResourceLocation PLACEHOLDER = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "block/tile_placeholder");
	static final Material PLACEHOLDER_MATERIAL = new Material(TextureAtlas.LOCATION_BLOCKS, PLACEHOLDER);

	private final CarrierKind kind;
	private final int slot;

	TileUnbakedModel(CarrierKind kind, int slot) {
		this.kind = kind;
		this.slot = slot;
	}

	@Override
	public Collection<ResourceLocation> getDependencies() {
		return List.of();
	}

	@Override
	public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver) {
	}

	@Override
	public @Nullable BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state) {
		return new TileBakedModel(this.kind, this.slot, sprites.apply(PLACEHOLDER_MATERIAL));
	}
}
