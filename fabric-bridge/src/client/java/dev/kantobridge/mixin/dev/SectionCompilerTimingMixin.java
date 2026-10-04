package dev.kantobridge.mixin.dev;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.kantobridge.client.model.MeshStats;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev only (docs/specs/VOXEL_BLOCKS_PLAN.md §0.2, §10): times every chunk section's mesh for {@link MeshStats}. Its config
 * ({@code kantobridge.dev.mixins.json}) applies it only when {@code -Dkantobridge.meshstats=true} is set
 * ({@link dev.kantobridge.client.model.MeshStatsMixinPlugin}); a normal game never loads it.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerTimingMixin {
	@Inject(method = "compile", at = @At("HEAD"))
	private void kantobridge$sectionStart(SectionPos sectionPos, RenderChunkRegion region, VertexSorting vertexSorting,
			SectionBufferBuilderPack buffers, CallbackInfoReturnable<SectionCompiler.Results> cir) {
		MeshStats.sectionStart(sectionPos.asLong());
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void kantobridge$sectionEnd(SectionPos sectionPos, RenderChunkRegion region, VertexSorting vertexSorting,
			SectionBufferBuilderPack buffers, CallbackInfoReturnable<SectionCompiler.Results> cir) {
		MeshStats.sectionEnd();
	}
}
