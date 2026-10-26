package dev.kantobridge.mixin;

import dev.kantobridge.entity.BikeEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BIKE_PLAN §2.5 (D4): sneak never dismounts our bike: it is B (the Acro's tricks, the Cycling Road's brake). Getting off
 * is the hidden game's ({@code act.field{bicycle}}, the bag); the server removes the bike when the story state says so.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void kantobridge$keepTheBike(CallbackInfoReturnable<Boolean> cir) {
		if (((Player) (Object) this).getVehicle() instanceof BikeEntity) {
			cir.setReturnValue(false);
		}
	}
}
