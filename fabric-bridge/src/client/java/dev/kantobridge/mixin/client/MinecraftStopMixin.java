package dev.kantobridge.mixin.client;

import dev.kantobridge.client.net.SessionRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WORLD_SESSIONS_PLAN §2.5 (WS5): the window's close (Minecraft.stop) gives every bound hidden game up to five seconds
 * for its quit save before the game goes down ({@link SessionRuntime#quitBlocking}); the integrated server then saves as
 * it always does.
 */
@Mixin(Minecraft.class)
abstract class MinecraftStopMixin {
	@Inject(method = "stop()V", at = @At("HEAD"))
	private void kantobridge$saveHiddenGamesOnClose(CallbackInfo ci) {
		SessionRuntime session = SessionRuntime.get();
		if (session != null) {
			try {
				session.quitBlocking();
			} catch (RuntimeException e) {
				// closing anyway: the hidden games run on and the next join continues them (§2.5)
			}
		}
	}
}
