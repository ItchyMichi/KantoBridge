package dev.kantobridge.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.kantobridge.client.weather.WeatherView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The hidden game's rain and snow fall where the biome has none (SE6, {@link WeatherView}): Kanto's and Hoenn's biomes have
 * no precipitation, so Minecraft's own renderer would skip every column even with the client level raining. While the
 * weather view draws precipitation, the renderer and its splashes and sounds ({@code tickRain}) take its kind instead of
 * the biome's; the biome itself is untouched, so nothing else (and no server) sees a change. Chaining MixinExtras
 * wrappers, so other mods' hooks on the same calls still run.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@WrapOperation(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Biome;hasPrecipitation()Z"))
	private boolean kantobridge$weatherFalls(Biome biome, Operation<Boolean> original) {
		return WeatherView.precipitation() != null || original.call(biome);
	}

	@WrapOperation(method = {"renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", "tickRain(Lnet/minecraft/client/Camera;)V"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)"
					+ "Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation kantobridge$weatherKind(Biome biome, BlockPos pos, Operation<Biome.Precipitation> original) {
		Biome.Precipitation ours = WeatherView.precipitation();
		return ours != null ? ours : original.call(biome, pos);
	}
}
