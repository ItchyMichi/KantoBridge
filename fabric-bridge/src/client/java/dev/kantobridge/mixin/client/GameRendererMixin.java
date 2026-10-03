package dev.kantobridge.mixin.client;

import dev.kantobridge.client.act.ActRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The crosshair meets closed art as each player sees it (docs/PLAN.md §6 M8 item 4): after Minecraft's own pick, a
 * standing Cut tree or a shut door drawn over its invisible story block takes the hit if the look ray reaches it first,
 * so right-clicking its trunk reaches its cell ({@code ActRuntime#pickClosedArt}).
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "pick(F)V", at = @At("TAIL"))
	private void kantobridge$pickClosedArt(float partialTicks, CallbackInfo ci) {
		ActRuntime.pickClosedArt(partialTicks);
	}
}
