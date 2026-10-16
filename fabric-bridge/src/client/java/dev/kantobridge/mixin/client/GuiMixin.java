package dev.kantobridge.mixin.client;

import dev.kantobridge.client.weather.WeatherView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hidden game's weather tints the screen (SE6, {@link WeatherView}): a drought's bleaching, shade's darkening, a
 * sandstorm's tan, and the lightning's white flash, drawn with the camera overlays (vignette, pumpkin), so the crosshair,
 * hotbar and chat stay untinted above them.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
	@Inject(method = "renderCameraOverlays(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void kantobridge$weatherTint(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		WeatherView.screen(graphics);
	}
}
