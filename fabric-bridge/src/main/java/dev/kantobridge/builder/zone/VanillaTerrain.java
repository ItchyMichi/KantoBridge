package dev.kantobridge.builder.zone;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * The land's height from Minecraft's own overworld noise for a seed (docs/specs/WILD_ROUTES_PLAN.md §4.4, research
 * §6.1), without generating a chunk: a noise generator for the vanilla overworld settings and biome preset, asked
 * for the base height of a column ({@code NoiseBasedChunkGenerator#getBaseHeight} with {@code WORLD_SURFACE_WG}, so
 * the sea counts as land at sea level). Kanto's world itself stays a flat void; this generator only answers questions.
 *
 * <p>Thread-safe for reads once built (vanilla asks the same objects from its worker threads), so the builder may
 * sample off the server thread. Check C2 measures its cost; {@link ZonePlan} samples every {@link ZonePlan#GRID}
 * blocks.
 */
public final class VanillaTerrain implements TerrainSampler {
	private static final LevelHeightAccessor HEIGHTS = LevelHeightAccessor.create(-64, 384);

	private final NoiseBasedChunkGenerator generator;
	private final RandomState random;

	private VanillaTerrain(NoiseBasedChunkGenerator generator, RandomState random) {
		this.generator = generator;
		this.random = random;
	}

	/**
	 * The vanilla overworld's terrain for {@code seed}, from {@code registries}: the server's (a real build) or
	 * Minecraft's built-in ones ({@code VanillaRegistries.createLookup()}, which the tests use).
	 */
	public static VanillaTerrain of(HolderLookup.Provider registries, long seed) {
		Holder<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(
				registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
		NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
		RandomState random = RandomState.create(registries.asGetterLookup(), NoiseGeneratorSettings.OVERWORLD, seed);
		return new VanillaTerrain(generator, random);
	}

	@Override
	public int height(int x, int z) {
		// getBaseHeight answers the first air block above the land; the land's top block is one below
		return this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, HEIGHTS, this.random) - 1;
	}
}
