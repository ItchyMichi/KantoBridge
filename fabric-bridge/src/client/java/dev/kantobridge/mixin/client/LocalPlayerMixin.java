package dev.kantobridge.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.kantobridge.client.move.SpeedSync;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No sprinting in Kanto (docs/PLAN.md P2): Gen 1 has one walking speed, and the hidden game can't step faster. The
 * server can't stop a client from sprinting, so the check every sprint start goes through says no ({@link SpeedSync}).
 * A chaining MixinExtras injector, so other mods' hooks on the same method still run.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin implements SpeedSync.Marker {
	@ModifyReturnValue(method = "canStartSprinting", at = @At("RETURN"))
	private boolean kantobridge$noSprint(boolean original) {
		return original && !SpeedSync.sprintBlocked((LocalPlayer) (Object) this);
	}
}
