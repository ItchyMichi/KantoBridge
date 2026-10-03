package dev.kantobridge.builder.place;

import dev.kantobridge.builder.plan.WorldPlan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Writes biomes per 4×4×4 cell with {@code ChunkAccess.fillBiomesFromNoise} and resends them to watching players
 * (refs/_research/P5_mc_content_testing.md §5.4). Never {@code /fillbiome}: it stops at 32,768 blocks.
 */
public final class BiomeWriter {
	private final ServerLevel level;
	private final List<WorldPlan.BiomeRegion> regions;
	private final Map<String, Holder<Biome>> holders = new HashMap<>();

	public BiomeWriter(ServerLevel level, List<WorldPlan.BiomeRegion> regions) {
		this.level = level;
		this.regions = regions;
		Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
		for (WorldPlan.BiomeRegion region : regions) {
			this.holders.computeIfAbsent(region.biome(),
					id -> registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id))));
		}
	}

	/** Returns true when the chunk had a region to write. */
	public boolean apply(LevelChunk chunk) {
		int cx = chunk.getPos().x;
		int cz = chunk.getPos().z;
		boolean any = false;
		for (WorldPlan.BiomeRegion region : this.regions) {
			any |= region.box().intersectsChunk(cx, cz);
		}
		if (!any) {
			return false;
		}
		chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
			int x = qx << 2;
			int y = qy << 2;
			int z = qz << 2;
			for (WorldPlan.BiomeRegion region : this.regions) {
				if (region.box().contains(x, y, z)) {
					return this.holders.get(region.biome());
				}
			}
			return chunk.getNoiseBiome(qx, qy, qz);
		}, this.level.getChunkSource().randomState().sampler());
		chunk.setUnsaved(true);
		this.level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
		return true;
	}
}
