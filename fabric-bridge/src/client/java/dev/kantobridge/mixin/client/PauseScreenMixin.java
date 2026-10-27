package dev.kantobridge.mixin.client;

import dev.kantobridge.client.net.SessionRuntime;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WORLD_SESSIONS_PLAN §2.5 (WS5): "Save and Quit to Title" first saves every bound hidden game ({@link SessionRuntime#quit});
 * the leave itself runs once they have answered (or the wait ran out), through {@code client.net.Leave}, the same steps
 * this method takes.
 */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin {
	@Inject(method = "onDisconnect()V", at = @At("HEAD"), cancellable = true)
	private void kantobridge$saveHiddenGamesFirst(CallbackInfo ci) {
		SessionRuntime session = SessionRuntime.get();
		if (session != null && session.quit()) {
			ci.cancel();
		}
	}
}
