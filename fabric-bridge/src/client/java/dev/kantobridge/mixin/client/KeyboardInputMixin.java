package dev.kantobridge.mixin.client;

import dev.kantobridge.client.move.InputLock;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the player still while the hidden game isn't taking steps ({@link InputLock}, docs/PLAN.md §4.3). At the tail of
 * {@code tick}, after the keys are read and before {@code LocalPlayer.aiStep} uses them, so it also survives the input
 * object being replaced on login and respawn (research P4 §4).
 */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin implements InputLock.Marker {
	@Inject(method = "tick", at = @At("TAIL"))
	private void kantobridge$holdWhileLocked(boolean isSneaking, float sneakingSpeedMultiplier, CallbackInfo ci) {
		InputLock.apply((Input) (Object) this);
	}
}
