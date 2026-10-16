package dev.kantobridge.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kantobridge.client.weather.WeatherView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The sky takes the hidden game's weather tint (SE6, {@link WeatherView}): pale in fog and snow, tan in a sandstorm, grey
 * under ash, bright and warm in a drought, darker in shade. A chaining MixinExtras injector.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@ModifyReturnValue(method = "getSkyColor(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"))
	private Vec3 kantobridge$weatherSky(Vec3 original) {
		return WeatherView.sky(original);
	}
}
