package dev.kantobridge.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kantobridge.client.weather.WeatherView;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hidden game's fog, ash haze, sandstorm and their tints (SE6, {@link WeatherView}): after vanilla has set the fog up,
 * the fog closes in towards the weather's distance, and after it has chosen the fog colour, the colour takes the weather's
 * tint (and the clear colour with it). Nothing changes under water, in lava or powder snow, or where the weather has no
 * fog.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	@Shadow
	private static float fogRed;
	@Shadow
	private static float fogGreen;
	@Shadow
	private static float fogBlue;

	@Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V", at = @At("RETURN"))
	private static void kantobridge$weatherFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog, float partialTick,
			CallbackInfo ci) {
		WeatherView.fog(camera, mode);
	}

	@Inject(method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V", at = @At("RETURN"))
	private static void kantobridge$weatherFogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorld,
			CallbackInfo ci) {
		float[] tint = WeatherView.tint(camera);
		if (tint == null) {
			return;
		}
		fogRed = Mth.lerp(tint[3], fogRed, tint[0]);
		fogGreen = Mth.lerp(tint[3], fogGreen, tint[1]);
		fogBlue = Mth.lerp(tint[3], fogBlue, tint[2]);
		RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
	}
}
